package com.github.ruan625br.hytale.node.editor.graph

import com.github.ruan625br.hytale.node.editor.codegen.model.GraphNode
import com.github.ruan625br.hytale.node.editor.codegen.model.NodeGraph
import com.github.ruan625br.hytale.node.editor.codegen.model.toPascalCase
import io.ktor.utils.io.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.*

class ComponentSchemaIndexer(private val basePath: String) {

    fun buildEncodedSchemaJson(): String {
        val index = buildIndex()
        println(index)
        val json = Json.encodeToString(index)
        println("Schama json: $json")
        return Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    fun buildIndex(): Map<String, ComponentSchema> {
        val projectDir = File(basePath)

        return projectDir.walkTopDown()
            .filter { it.extension == "hgraph" }
            .mapNotNull { file ->
                runCatching { extractSchema(file) }.getOrNull()
            }
            .associateBy { it.id }
    }

    private fun extractSchema(file: File): ComponentSchema? {

        val json = Json { ignoreUnknownKeys = true }
        val graph = json.decodeFromString<NodeGraph>(file.readText())
        if (graph.graphType != "component") return null

        val id = file.nameWithoutExtension
        val fields = graph.nodes
            .filter { it.data["label"] == "ComponentField" }
            .map { node ->
                ComponentFieldSchema(
                    name = node.data["fieldName"] ?: return@map null,
                    type = node.data["fieldType"] ?: "String",
                    defaultValue = node.data["defaultValue"] ?: " ",
                    isMutable = node.data["isMutable"] != "false",
                )
            }
            .filterNotNull()

        val methods = graph.nodes
            .filter { it.data["label"] == "ComponentMethod" }
            .map { methodNode ->
                val params = extractMethodParams(methodNode, graph)
                ComponentMethodSchema(
                    name = methodNode.data["methodName"] ?: return@map null,
                    returnType = methodNode.data["returnType"] ?: "Unit",
                    params = params,
                )
            }
            .filterNotNull()

        println("ID: $id. Fields: $methods")

        return ComponentSchema(
            id = id,
            fields = fields,
            methods = methods,
        )

    }
    /*

    private fun extractMethodParams(
        methodNode: GraphNode,
        graph:      NodeGraph
    ): List<ComponentFieldSchema> {
        return graph.edges
            .filter { it.target == methodNode.id && it.targetHandle?.endsWith(":flow") == false }
            .mapNotNull { edge ->
                val paramNode = graph.nodes.find { it.id == edge.source } ?: return@mapNotNull null
                ComponentFieldSchema(
                    name         = paramNode.data["fieldName"]    ?: return@mapNotNull null,
                    type         = paramNode.data["fieldType"]    ?: "String",
                    defaultValue = paramNode.data["defaultValue"] ?: " ",
                    isMutable    = false,
                )
            }
    }
*/

    fun extractMethodParams(methodNode: GraphNode, graph: NodeGraph): List<ComponentFieldSchema> {
        val paramsJson = methodNode.data["_params"]
        if (!paramsJson.isNullOrBlank() && paramsJson != "[]") {
            return runCatching {
                Json.decodeFromString<List<ParamData>>(paramsJson).map {
                    ComponentFieldSchema(
                        id = it.id,
                        name = it.name,
                        type = it.type,
                        defaultValue = "",
                        isMutable = false,
                    )
                }
            }.getOrElse { emptyList() }
        }

        return graph.edges
            .filter { it.target == methodNode.id && it.targetHandle?.endsWith(":flow") == false }
            .mapNotNull { edge ->
                val paramNode = graph.nodes.find { it.id == edge.source } ?: return@mapNotNull null
                ComponentFieldSchema(
                    id = paramNode.id,
                    name = paramNode.data["fieldName"] ?: return@mapNotNull null,
                    type = paramNode.data["fieldType"] ?: "String",
                    defaultValue = "",
                    isMutable = false,
                )
            }
    }

    companion object {
        fun getParamFromEdgeId(edgeId: String, methodNode: GraphNode?): ComponentFieldSchema? {
            val paramsJson = methodNode?.data["_params"]

            //TODO: remove suffix with regex for case with param type} :number, :string
            val targetParamId = edgeId.removePrefix("param_").replaceAfterLast(":", "")
                .replace(":", "")

            return runCatching {
                if (paramsJson.isNullOrBlank()) return null
                val json = Json { ignoreUnknownKeys = true }
                val params = json.decodeFromString<List<ParamData>>(paramsJson).map {
                    ComponentFieldSchema(
                        id = it.id,
                        name = it.name,
                        type = it.type,
                        defaultValue = "",
                        isMutable = false,
                    )
                }
                println("Params: $params")
                params.firstOrNull { it.id?.startsWith(targetParamId, true) == true }
            }.getOrElse {
                println("getParamFromEdgeId: ${it.message}")
                null
            }
        }
    }
}

@Serializable
data class ComponentSchema(
    val id: String,
    val fields: List<ComponentFieldSchema>,
    val methods: List<ComponentMethodSchema>
)

@Serializable
data class ComponentFieldSchema(
    val id: String? = null,
    val name: String,
    val type: String,
    val defaultValue: String,
    val isMutable: Boolean,
)

@Serializable
data class ComponentMethodSchema(
    val name: String,
    val returnType: String,
    val params: List<ComponentFieldSchema>
)

@Serializable
private data class ParamData(val id: String, val name: String, val type: String)
