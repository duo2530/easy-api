package com.itangcent.idea.plugin.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.itangcent.common.logger.traceError
import com.itangcent.common.model.Request
import com.itangcent.idea.plugin.api.ClassApiExporterHelper
import com.itangcent.idea.plugin.api.dependency.EndpointTableDependency
import com.itangcent.idea.plugin.api.dependency.MybatisPlusEndpointDependencyAnalyzer
import com.itangcent.idea.plugin.api.dependency.TableDependencyMarkdownFormatter
import com.itangcent.idea.plugin.api.export.ExportChannel
import com.itangcent.idea.plugin.api.export.ExportDoc
import com.itangcent.idea.plugin.api.export.core.ConfigurableMethodFilter
import com.itangcent.idea.plugin.api.export.core.MethodFilter
import com.itangcent.idea.plugin.utils.NotificationUtils
import com.itangcent.idea.psi.resourceMethod
import com.itangcent.idea.utils.FileSaveHelper
import com.itangcent.intellij.context.ActionContext
import com.itangcent.intellij.context.ActionContextBuilder
import com.itangcent.intellij.extend.guice.singleton
import com.itangcent.intellij.extend.guice.with
import com.itangcent.intellij.file.DefaultLocalFileRepository
import com.itangcent.intellij.file.LocalFileRepository
import com.itangcent.intellij.logger.Logger

/** Exports Controller endpoint to MyBatis-Plus table dependencies as Markdown. */
class TableDependencyExportAction : ApiExportAction("Export Endpoint Table Dependencies") {

    override fun afterBuildActionContext(event: AnActionEvent, builder: ActionContextBuilder) {
        super.afterBuildActionContext(event, builder)
        builder.bind(LocalFileRepository::class) { it.with(DefaultLocalFileRepository::class).singleton() }
        builder.bindInstance(ExportChannel::class, ExportChannel.of("markdown"))
        builder.bindInstance(ExportDoc::class, ExportDoc.of("request"))
        builder.bind(MethodFilter::class) { it.with(ConfigurableMethodFilter::class).singleton() }
        builder.bindInstance("file.save.default", "endpoint-table-dependencies.md")
        builder.bindInstance("file.save.last.location.key", "com.itangcent.table.dependency.export.path")
    }

    override fun actionPerformed(actionContext: ActionContext, project: Project?, anActionEvent: AnActionEvent) {
        super.actionPerformed(actionContext, project, anActionEvent)
        if (project == null) return

        val logger = actionContext.instance(Logger::class)
        try {
            val requests = actionContext.instance(ClassApiExporterHelper::class)
                .export()
                .filterIsInstance<Request>()
            if (requests.isEmpty()) {
                NotificationUtils.notifyInfo(project, "No API found to analyze")
                return
            }

            val analyzer = MybatisPlusEndpointDependencyAnalyzer(project)
            val endpoints = requests.mapNotNull { request ->
                actionContext.callInReadUI {
                    val sourceMethod = request.resourceMethod() ?: return@callInReadUI null
                    EndpointTableDependency(
                        httpMethod = request.method ?: "UNKNOWN",
                        path = request.path?.url() ?: "-",
                        controllerMethod =
                            "${sourceMethod.containingClass?.qualifiedName ?: "<unknown>"}.${sourceMethod.name}",
                        dependency = analyzer.analyze(sourceMethod),
                    )
                }
            }

            if (endpoints.isEmpty()) {
                NotificationUtils.notifyInfo(project, "No Java endpoint method found to analyze")
                return
            }

            val content = TableDependencyMarkdownFormatter.format(endpoints)
            actionContext.instance(FileSaveHelper::class).saveOrCopy(
                content,
                onCopy = {
                    NotificationUtils.notifyInfo(project, "Endpoint table dependencies copied to clipboard")
                },
                onSaveSuccess = {
                    NotificationUtils.notifyInfo(project, "Endpoint table dependencies exported to: $it")
                },
                onSaveFailed = {
                    NotificationUtils.notifyError(project, "Failed to save endpoint table dependencies: ${it ?: "unknown error"}")
                },
            )
        } catch (e: Exception) {
            logger.traceError("Endpoint table dependency export failed", e)
            NotificationUtils.notifyError(project, "Endpoint table dependency export failed: ${e.message}")
        }
    }
}
