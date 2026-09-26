package com.itangcent.idea.plugin.api.dependency

enum class DatabaseOperation {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    UNKNOWN,
}

data class TableAccess(
    val tableName: String,
    val operation: DatabaseOperation,
    val entityClass: String?,
    val evidence: String,
    val confidence: String,
    val callChain: List<String>,
)

data class UnresolvedCall(
    val call: String,
    val reason: String,
    val callChain: List<String>,
)

data class EndpointDependencyResult(
    val tableAccesses: List<TableAccess> = emptyList(),
    val unresolvedCalls: List<UnresolvedCall> = emptyList(),
)

data class EndpointTableDependency(
    val httpMethod: String,
    val path: String,
    val controllerMethod: String,
    val dependency: EndpointDependencyResult,
)
