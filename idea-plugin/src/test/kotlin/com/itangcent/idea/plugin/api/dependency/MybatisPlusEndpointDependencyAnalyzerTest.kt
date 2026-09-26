package com.itangcent.idea.plugin.api.dependency

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class MybatisPlusEndpointDependencyAnalyzerTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addClass(
            """
            package com.baomidou.mybatisplus.core.mapper;
            public interface BaseMapper<T> {
                T selectById(Object id);
                int insert(T entity);
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package com.baomidou.mybatisplus.annotation;
            public @interface TableName { String value(); }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package example;
            import com.baomidou.mybatisplus.annotation.TableName;
            @TableName("sys_user")
            public class User {}
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package example;
            import com.baomidou.mybatisplus.core.mapper.BaseMapper;
            public interface UserMapper extends BaseMapper<User> {}
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package example;
            public interface UserService { User find(Long id); }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package example;
            public class UserServiceImpl implements UserService {
                private UserMapper userMapper;
                public User find(Long id) { return userMapper.selectById(id); }
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package example;
            public class UserController {
                private UserService userService;
                public User detail(Long id) { return userService.find(id); }
            }
            """.trimIndent(),
        )
    }

    fun testFindsTableAcrossServiceInterfaceAndMapper() {
        val controller = JavaPsiFacade.getInstance(project)
            .findClass("example.UserController", GlobalSearchScope.projectScope(project))!!
        val method = controller.findMethodsByName("detail", false).single()

        val result = MybatisPlusEndpointDependencyAnalyzer(project).analyze(method)

        assertEquals(listOf("sys_user"), result.tableAccesses.map { it.tableName }.distinct())
        assertEquals(DatabaseOperation.SELECT, result.tableAccesses.single().operation)
        assertTrue(result.tableAccesses.single().callChain.any { it.contains("UserServiceImpl.find") })
        assertTrue(result.tableAccesses.single().callChain.last().contains("BaseMapper.selectById"))
    }
}
