package com.itangcent.idea.plugin.api.dependency

object TableDependencyMarkdownFormatter {

    fun format(endpoints: List<EndpointTableDependency>): String = buildString {
        appendLine("# 接口数据表关系")
        appendLine()
        appendLine("> 静态分析范围：Spring MVC → Java Service/实现类 → MyBatis-Plus。HIGH 表示表名来自 `@TableName`，MEDIUM 表示按实体类名推断。")
        appendLine()
        appendLine("## 接口清单")
        appendLine()
        appendLine("| 接口 | Controller 方法 | 数据表 | 操作 | 置信度 |")
        appendLine("|---|---|---|---|---|")
        endpoints.forEach { endpoint ->
            if (endpoint.dependency.tableAccesses.isEmpty()) {
                appendLine("| ${cell(endpoint.httpMethod + " " + endpoint.path)} | `${code(endpoint.controllerMethod)}` | 未识别 | UNKNOWN | LOW |")
            } else {
                endpoint.dependency.tableAccesses
                    .distinctBy { Triple(it.tableName, it.operation, it.callChain) }
                    .forEach { access ->
                        appendLine(
                            "| ${cell(endpoint.httpMethod + " " + endpoint.path)} | `${code(endpoint.controllerMethod)}` | " +
                                "`${code(access.tableName)}` | ${access.operation} | ${access.confidence} |",
                        )
                    }
            }
        }

        appendLine()
        appendLine("## 调用链证据")
        appendLine()
        endpoints.forEach { endpoint ->
            appendLine("### ${endpoint.httpMethod} ${endpoint.path}")
            appendLine()
            if (endpoint.dependency.tableAccesses.isEmpty()) {
                appendLine("- 未识别到 MyBatis-Plus 表访问。")
            } else {
                endpoint.dependency.tableAccesses.forEach { access ->
                    appendLine("- `${code(access.tableName)}` / ${access.operation} / ${access.confidence}")
                    appendLine("  - 实体：`${code(access.entityClass ?: "未知")}`")
                    appendLine("  - 证据：${access.evidence}")
                    appendLine("  - 调用链：`${code(access.callChain.joinToString(" → "))}`")
                }
            }
            endpoint.dependency.unresolvedCalls.forEach { unresolved ->
                appendLine("- ⚠ 未解析 `${code(unresolved.call)}`：${unresolved.reason}")
            }
            appendLine()
        }

        appendLine("## 数据表倒排索引")
        appendLine()
        appendLine("| 数据表 | 涉及接口 | 操作 |")
        appendLine("|---|---|---|")
        val reverseIndex = endpoints
            .flatMap { endpoint ->
                endpoint.dependency.tableAccesses.map { access -> Triple(access.tableName, endpoint, access.operation) }
            }
            .groupBy { it.first }
            .toSortedMap()
        if (reverseIndex.isEmpty()) {
            appendLine("| 未识别 | — | — |")
        } else {
            reverseIndex.forEach { (table, records) ->
                val apiList = records.map { "${it.second.httpMethod} ${it.second.path}" }.distinct().joinToString("<br>")
                val operations = records.map { it.third.name }.distinct().joinToString("、")
                appendLine("| `${code(table)}` | ${cell(apiList)} | $operations |")
            }
        }
    }.trimEnd() + "\n"

    private fun cell(value: String): String = value.replace("|", "\\|").replace("\n", "<br>")

    private fun code(value: String): String = value.replace("`", "\\`")
}
