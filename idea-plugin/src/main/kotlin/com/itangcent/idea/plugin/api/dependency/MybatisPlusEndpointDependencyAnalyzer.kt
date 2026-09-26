package com.itangcent.idea.plugin.api.dependency

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.MethodSignatureUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiTypesUtil
import com.intellij.psi.util.TypeConversionUtil

/**
 * Walks Java PSI calls from one endpoint and extracts MyBatis-Plus table access.
 *
 * PSI access must be executed inside an IntelliJ read action. Supported persistence
 * seams are `BaseMapper<T>`, `IService<T>`, and `ServiceImpl<M,T>`.
 */
class MybatisPlusEndpointDependencyAnalyzer(private val project: Project) {

    private val projectScope = GlobalSearchScope.projectScope(project)
    private val implementationCache = mutableMapOf<PsiMethod, List<PsiMethod>>()

    fun analyze(endpointMethod: PsiMethod, maxDepth: Int = DEFAULT_MAX_DEPTH): EndpointDependencyResult {
        val tables = mutableListOf<TableAccess>()
        val unresolved = mutableListOf<UnresolvedCall>()
        walk(endpointMethod, listOf(displayName(endpointMethod)), emptySet(), 0, maxDepth, tables, unresolved)
        return EndpointDependencyResult(
            tableAccesses = tables.distinctBy {
                listOf(it.tableName, it.operation.name, it.callChain.joinToString("->"))
            },
            unresolvedCalls = unresolved.distinctBy { it.call + it.reason + it.callChain.joinToString("->") },
        )
    }

    private fun walk(
        method: PsiMethod,
        chain: List<String>,
        activePath: Set<PsiMethod>,
        depth: Int,
        maxDepth: Int,
        tables: MutableList<TableAccess>,
        unresolved: MutableList<UnresolvedCall>,
    ) {
        if (depth >= maxDepth) {
            unresolved += UnresolvedCall(displayName(method), "超过最大调用深度 $maxDepth", chain)
            return
        }
        if (method in activePath) return

        val body = method.body ?: return
        val nextActivePath = activePath + method
        val calls = PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression::class.java)
        for (call in calls) {
            val target = call.resolveMethod()
            if (target == null) {
                unresolved += UnresolvedCall(call.methodExpression.text, "IDEA 无法解析方法引用", chain)
                continue
            }

            val callChain = chain + displayName(target)
            if (isMybatisPlusTerminal(call, target)) {
                val entity = resolveEntity(call, target, method.containingClass)
                if (entity == null) {
                    unresolved += UnresolvedCall(displayName(target), "无法从 MyBatis-Plus 泛型解析实体", callChain)
                    continue
                }
                val table = resolveTable(entity)
                tables += TableAccess(
                    tableName = table.name,
                    operation = inferOperation(target.name),
                    entityClass = entity.qualifiedName,
                    evidence = table.evidence,
                    confidence = table.confidence,
                    callChain = callChain,
                )
                continue
            }

            if (isProjectSource(target)) {
                val implementations = resolveImplementations(target)
                if (implementations.isEmpty()) {
                    if (target.body != null) {
                        walk(target, callChain, nextActivePath, depth + 1, maxDepth, tables, unresolved)
                    } else {
                        unresolved += UnresolvedCall(displayName(target), "项目方法没有方法体或可解析实现类", callChain)
                    }
                } else {
                    if (implementations.size > 1) {
                        unresolved += UnresolvedCall(
                            displayName(target),
                            "发现 ${implementations.size} 个项目实现，无法静态确定 Spring 实际注入项；以下结果包含全部候选实现",
                            callChain,
                        )
                    }
                    implementations.forEach { implementation ->
                        walk(
                            implementation,
                            chain + displayName(implementation),
                            nextActivePath,
                            depth + 1,
                            maxDepth,
                            tables,
                            unresolved,
                        )
                    }
                }
            }
        }
    }

    private fun resolveImplementations(method: PsiMethod): List<PsiMethod> {
        if (!method.hasModifierProperty(PsiModifier.ABSTRACT) && method.containingClass?.isInterface != true) {
            return emptyList()
        }
        return implementationCache.getOrPut(method) {
            val owner = method.containingClass ?: return@getOrPut emptyList()
            ClassInheritorsSearch.search(owner, projectScope, true)
                .findAll()
                .filter { isProjectSource(it) }
                .mapNotNull { inheritor ->
                    inheritor.findMethodsByName(method.name, false)
                        .firstOrNull { MethodSignatureUtil.areSignaturesEqual(it, method) }
                        ?: inheritor.findMethodsByName(method.name, false).firstOrNull {
                            it.parameterList.parametersCount == method.parameterList.parametersCount
                        }
                }
                .filter { it.body != null }
        }
    }

    private fun isMybatisPlusTerminal(call: PsiMethodCallExpression, target: PsiMethod): Boolean {
        val owner = target.containingClass
        val qualifierClass = PsiTypesUtil.getPsiClass(call.methodExpression.qualifierExpression?.type)
        return isInheritor(qualifierClass, BASE_MAPPER) ||
            isInheritor(qualifierClass, I_SERVICE) ||
            isInheritor(owner, BASE_MAPPER) ||
            isInheritor(owner, I_SERVICE)
    }

    private fun resolveEntity(
        call: PsiMethodCallExpression,
        target: PsiMethod,
        currentClass: PsiClass?,
    ): PsiClass? {
        val targetOwner = target.containingClass
        entityFromResolvedOwner(targetOwner, call.resolveMethodGenerics().substitutor)?.let { return it }

        val qualifierClass = PsiTypesUtil.getPsiClass(call.methodExpression.qualifierExpression?.type)
        for (candidate in listOfNotNull(qualifierClass, currentClass, targetOwner)) {
            entityFromHierarchy(candidate, BASE_MAPPER, 0)?.let { return it }
            entityFromHierarchy(candidate, I_SERVICE, 0)?.let { return it }
            entityFromHierarchy(candidate, SERVICE_IMPL, 1)?.let { return it }
        }
        return null
    }

    private fun entityFromResolvedOwner(owner: PsiClass?, substitutor: PsiSubstitutor): PsiClass? {
        if (owner == null) return null
        val typeParameter = when (owner.qualifiedName) {
            BASE_MAPPER, I_SERVICE -> owner.typeParameters.getOrNull(0)
            SERVICE_IMPL -> owner.typeParameters.getOrNull(1)
            else -> null
        } ?: return null
        return PsiTypesUtil.getPsiClass(substitutor.substitute(typeParameter))
    }

    private fun entityFromHierarchy(candidate: PsiClass, baseFqn: String, parameterIndex: Int): PsiClass? {
        val base = JavaPsiFacade.getInstance(project).findClass(baseFqn, projectScope) ?: return null
        if (!InheritanceUtil.isInheritorOrSelf(candidate, base, true)) return null
        val substitutor = TypeConversionUtil.getSuperClassSubstitutor(base, candidate, PsiSubstitutor.EMPTY)
        val parameter = base.typeParameters.getOrNull(parameterIndex) ?: return null
        return PsiTypesUtil.getPsiClass(substitutor.substitute(parameter))
    }

    private fun resolveTable(entity: PsiClass): ResolvedTable {
        val annotation = entity.modifierList?.findAnnotation(TABLE_NAME)
        val value = annotation?.findAttributeValue("value")
        val constant = value?.let {
            JavaPsiFacade.getInstance(project).constantEvaluationHelper.computeConstantExpression(it) as? String
        }?.takeIf { it.isNotBlank() }
        if (constant != null) {
            return ResolvedTable(constant, "@TableName(\"$constant\")", "HIGH")
        }
        return ResolvedTable(camelToSnake(entity.name ?: "unknown_table"), "实体类名按 MyBatis-Plus 默认规则推断", "MEDIUM")
    }

    private fun isProjectSource(method: PsiMethod): Boolean = isProjectSource(method.containingFile?.virtualFile)

    private fun isProjectSource(psiClass: PsiClass): Boolean = isProjectSource(psiClass.containingFile?.virtualFile)

    private fun isProjectSource(file: com.intellij.openapi.vfs.VirtualFile?): Boolean =
        file != null && ProjectFileIndex.getInstance(project).isInContent(file)

    private fun isInheritor(psiClass: PsiClass?, baseFqn: String): Boolean =
        psiClass != null && (psiClass.qualifiedName == baseFqn || InheritanceUtil.isInheritor(psiClass, baseFqn))

    private fun inferOperation(methodName: String): DatabaseOperation {
        val name = methodName.lowercase()
        return when {
            name.startsWith("select") || name.startsWith("get") || name.startsWith("list") ||
                name.startsWith("count") || name.startsWith("exists") || name.startsWith("page") -> DatabaseOperation.SELECT
            name.startsWith("insert") || name.startsWith("save") -> DatabaseOperation.INSERT
            name.startsWith("update") -> DatabaseOperation.UPDATE
            name.startsWith("delete") || name.startsWith("remove") -> DatabaseOperation.DELETE
            else -> DatabaseOperation.UNKNOWN
        }
    }

    private fun displayName(method: PsiMethod): String =
        "${method.containingClass?.qualifiedName ?: "<unknown>"}.${method.name}"

    private fun camelToSnake(value: String): String =
        value.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()

    private data class ResolvedTable(val name: String, val evidence: String, val confidence: String)

    companion object {
        const val DEFAULT_MAX_DEPTH = 16
        private const val BASE_MAPPER = "com.baomidou.mybatisplus.core.mapper.BaseMapper"
        private const val I_SERVICE = "com.baomidou.mybatisplus.extension.service.IService"
        private const val SERVICE_IMPL = "com.baomidou.mybatisplus.extension.service.impl.ServiceImpl"
        private const val TABLE_NAME = "com.baomidou.mybatisplus.annotation.TableName"
    }
}
