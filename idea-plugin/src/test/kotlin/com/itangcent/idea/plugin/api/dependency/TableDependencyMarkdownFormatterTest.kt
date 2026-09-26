package com.itangcent.idea.plugin.api.dependency

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TableDependencyMarkdownFormatterTest {

    @Test
    fun `renders endpoint table relationship and reverse index`() {
        val content = TableDependencyMarkdownFormatter.format(
            listOf(
                EndpointTableDependency(
                    httpMethod = "GET",
                    path = "/users/{id}",
                    controllerMethod = "example.UserController.detail",
                    dependency = EndpointDependencyResult(
                        tableAccesses = listOf(
                            TableAccess(
                                tableName = "sys_user",
                                operation = DatabaseOperation.SELECT,
                                entityClass = "example.User",
                                evidence = "@TableName(\"sys_user\")",
                                confidence = "HIGH",
                                callChain = listOf(
                                    "example.UserController.detail",
                                    "example.UserServiceImpl.find",
                                    "com.baomidou.mybatisplus.core.mapper.BaseMapper.selectById",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(content.contains("| GET /users/{id} | `example.UserController.detail` | `sys_user` | SELECT | HIGH |"))
        assertTrue(content.contains("## 数据表倒排索引"))
        assertTrue(content.contains("| `sys_user` | GET /users/{id} | SELECT |"))
    }
}
