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
                java.util.List<T> selectList(Object wrapper);
                int insert(T entity);
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package com.baomidou.mybatisplus.extension.service;
            public interface IService<T> {
                T getById(Object id);
                java.util.List<T> list();
                boolean save(T entity);
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package com.baomidou.mybatisplus.extension.service.impl;
            import com.baomidou.mybatisplus.core.mapper.BaseMapper;
            import com.baomidou.mybatisplus.extension.service.IService;
            public class ServiceImpl<M extends BaseMapper<T>, T> implements IService<T> {
                protected M baseMapper;
                public T getById(Object id) { return baseMapper.selectById(id); }
                public java.util.List<T> list() { return baseMapper.selectList(null); }
                public boolean save(T entity) { return baseMapper.insert(entity) > 0; }
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package com.baomidou.mybatisplus.core.conditions.query;
            public class LambdaQueryWrapper<T> {
                public LambdaQueryWrapper<T> eq(Object column, Object value) { return this; }
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

    fun testDoesNotTreatMybatisPlusWrapperHelpersAsDatabaseAccess() {
        val helper = myFixture.addClass(
            """
            package example;
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
            public class WrapperOnlyService {
                public void prepare() { new LambdaQueryWrapper<User>().eq("id", 1L); }
            }
            """.trimIndent(),
        )
        val method = helper.findMethodsByName("prepare", false).single()

        val result = MybatisPlusEndpointDependencyAnalyzer(project).analyze(method)

        assertTrue(result.tableAccesses.isEmpty())
    }

    fun testFollowsCustomIServiceMethodToImplementationAndMapper() {
        myFixture.addClass(
            """
            package example;
            import com.baomidou.mybatisplus.annotation.TableName;
            @TableName("region")
            public class Region {}
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package example;
            import com.baomidou.mybatisplus.extension.service.IService;
            public interface IRegionService extends IService<Region> {
                java.util.List<Region> queryActiveRegionList();
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package example;
            import com.baomidou.mybatisplus.core.mapper.BaseMapper;
            public interface RegionMapper extends BaseMapper<Region> {}
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package example;
            import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
            public class RegionServiceImpl extends ServiceImpl<RegionMapper, Region> implements IRegionService {
                private RegionMapper regionMapper;
                public java.util.List<Region> queryActiveRegionList() {
                    return regionMapper.selectList(null);
                }
            }
            """.trimIndent(),
        )
        val controller = myFixture.addClass(
            """
            package example;
            public class RegionController {
                private IRegionService regionService;
                public java.util.List<Region> activeRegionList() {
                    return regionService.queryActiveRegionList();
                }
            }
            """.trimIndent(),
        )
        val method = controller.findMethodsByName("activeRegionList", false).single()

        val result = MybatisPlusEndpointDependencyAnalyzer(project).analyze(method)

        assertEquals(listOf("region"), result.tableAccesses.map { it.tableName }.distinct())
        assertEquals(DatabaseOperation.SELECT, result.tableAccesses.single().operation)
        assertTrue(result.tableAccesses.single().callChain.any { it.contains("RegionServiceImpl.queryActiveRegionList") })
        assertTrue(result.tableAccesses.single().callChain.last().contains("BaseMapper.selectList"))
        assertTrue(result.unresolvedCalls.none { it.call.contains("IRegionService.queryActiveRegionList") })
    }
}
