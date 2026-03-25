package com.github.ruan625br.hytale.node.editor.codegen.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class NodeGraph(
    val graphType: String,
    val graphPath: String = "",
    val name: String,
    val imports: List<GraphImport> = emptyList(),
    val functions: List<JsonElement>  = emptyList(),  // não deserializa em detalhes — só para não quebrar
    val variables: List<JsonElement>  = emptyList(),
    val nodes: List<GraphNode> = emptyList(),
    val edges: List<GraphEdge> = emptyList(),
) {
    constructor(name: String, path: String) : this(
        graphType = "event",
        graphPath = path,
        name = name,
    )
}

@Serializable
data class GraphNode(
    val id: String,
    val type: String,
    val data: Map<String, String> = emptyMap(),
    val position: Position = Position.ZERO
)


@Serializable
data class GraphEdge(
    val id: String,
    val source: String,
    val target: String,
    val sourceHandle: String? = null,
    val targetHandle: String? = null,
)

@Serializable
data class GraphImport(val alias: String, val path: String)

@Serializable
data class Position(val x: Float, val y: Float) {

    companion object {
        val ZERO = Position(0f, 0f)
    }
}