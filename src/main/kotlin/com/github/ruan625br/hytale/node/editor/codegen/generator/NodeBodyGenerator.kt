package com.github.ruan625br.hytale.node.editor.codegen.generator

import com.github.ruan625br.hytale.node.editor.codegen.model.GraphNode
import com.github.ruan625br.hytale.node.editor.codegen.model.NodeGraph
import com.github.ruan625br.hytale.node.editor.codegen.resolver.GraphResolver.ResolveChain
import com.github.ruan625br.hytale.node.editor.codegen.resolver.GraphResolver.ScopeVar
import com.github.ruan625br.hytale.node.editor.graph.ComponentSchemaIndexer

typealias ImportNames = MutableSet<String>

class NodeBodyGenerator(
    private val graph: NodeGraph,
    private val allGraphs: Map<String, NodeGraph> = emptyMap()
) {

    val requiredImports = mutableMapOf<String, ImportNames>()
    val variableDeclarations = mutableMapOf<String, Any?>()

    fun generateBody(chain: ResolveChain): String = buildString {
        for (node in graph.nodes) {
            val lines = generateNode(node, chain.scope)
            lines.forEach { appendLine(it) }
            if (lines.isNotEmpty()) appendLine()
        }
    }.trimEnd()

    fun generateNode(
        node: GraphNode,
        scope: Map<String, ScopeVar>
    ): List<String> {
        val data = node.data
        println("data: $data")
        println("scope: $scope")

        val resolveFieldValue: (String) -> String = { id ->
            resolveFieldValue(node, id, scope, graph)
        }

        return when (data["label"]) {
            "SendMessage" -> {
                val messageExpr = resolveFieldValue("message")

                when {
                    scope["context"]   != null -> listOf("""context.sendMessage(Message.raw($messageExpr))""")
                    scope["playerRef"] != null -> listOf("""${scope["playerRef"]!!.kotlinExpr}?.sendMessage(Message.raw($messageExpr))""")
                    else -> listOf("""// SendMessage""")
                }
            }

            "Log" -> {
                addRequiredImport("java.util.logging", "Level")
                val messageExpr = resolveFieldValue("message")
                val level = data["level"] ?: "INFO"
                listOf("""LOGGER.at(Level.$level).log($messageExpr)""")

            }

            "WorldExecute" -> {
                val world = scope["world"]?.kotlinExpr ?: "null"
                val innerChain = resolveInnerChain(node)
                buildList {
                    add("$world?.execute {")
                    innerChain.forEach { add("    $it") }
                    add("}")
                }
            }

            "Branch" -> { //TODO: implement
                val condition = scope["condition"]?.kotlinExpr ?: "false"
                listOf("if ($condition) {", "    // true branch", "} else {", "    // false branch", "}")
            }


            "FormatString" -> { //TODO: implement
                val format = data["format"]?.escapeKotlin() ?: ""
                val arg0 = scope["arg0"]?.kotlinExpr ?: "\"\""
                val arg1 = scope["arg1"]?.kotlinExpr ?: "\"\""
                listOf("""val formatted = "$format".replace("{0}", $arg0).replace("{1}", $arg1)""")
            }

            "AppendString" -> { //TODO: implement
                val a = scope["a"]?.kotlinExpr ?: "\"\""
                val b = scope["b"]?.kotlinExpr ?: "\"\""
                listOf("val appended = $a + $b")
            }

            "StringToInt" -> { //TODO: implement
                val value = scope["value"]?.kotlinExpr ?: "\"\""
                listOf("val intValue = $value.toIntOrNull() ?: 0")
            }

            "IntToString" -> { //TODO: implement
                val value = scope["value"]?.kotlinExpr ?: "0"
                listOf("val strValue = $value.toString()")
            }

            "AND" -> { //TODO: implement
                val a = scope["a"]?.kotlinExpr ?: "false"
                val b = scope["b"]?.kotlinExpr ?: "false"
                listOf("val andResult = $a && $b")
            }

            "OR" -> { //TODO: implement
                val a = scope["a"]?.kotlinExpr ?: "false"
                val b = scope["b"]?.kotlinExpr ?: "false"
                listOf("val orResult = $a || $b")
            }

            "NOT" -> { //TODO: implement
                val a = scope["a"]?.kotlinExpr ?: "false"
                listOf("val notResult = !$a")
            }

            "CallGraph" -> {
                val alias = data["graphAlias"] ?: return emptyList()
                val target = allGraphs[alias] ?: return listOf("// TODO: grafo '$alias' não encontrado")
                val objectName = target.name.toPascalCase()
                val player = scope["playerRef"]?.kotlinExpr ?: "null"
                val world = scope["world"]?.kotlinExpr ?: "null"
                listOf("$objectName.invoke(playerRef = $player, world = $world)")
            }

            "CallFunction" -> {
                data["functionId"] ?: return emptyList()
                val fnName = data["functionName"]?.toCamelCase() ?: "unknownFunction"
                val args = resolveDataEdgeArgs(node, scope)
                listOf("$fnName($args)")
            }

            "GetVariable" -> {
                data["variableName"] ?: return listOf("// variável não encontrada")
                // Apenas expõe o nome — o GraphResolver vai mapear o output port para o nome da var
                emptyList() // Get é transparente — a variável entra no scope diretamente
            }


            "SetVariable" -> {
                val name = data["variableName"] ?: return emptyList()
                val mutable = data["isMutable"] == "true"
                val valueExpr = resolveFieldValue("value")
                val value = valueExpr//scope["value"]?.kotlinExpr ?: data["defaultValue"] ?: "null"
                val isDeclared = variableDeclarations.containsKey(name)
                val keyword = when {
                    isDeclared -> "" //TODO: check if is mutable
                    mutable -> "var"
                    else -> "val"
                }
                variableDeclarations[name] = value
                listOf("$keyword $name = $value".trim())
            }

            "StringLiteral" -> {
                //not implement this
                //val valueExpr = resolveFieldValue("string")
                listOf()
            }

            // math
            "Accumulate" -> {
                val firstExpression = resolveFieldValue("a")
                val secondExpression = resolveFieldValue("value")

                listOf("$firstExpression += $secondExpression")
            }

            else -> listOf("// TODO: node '${data["label"]}' not implemented")

        }
    }

    private fun resolveInnerChain(containerNode: GraphNode): List<String> {
        val innerEdge = graph.edges.firstOrNull { edge ->
            edge.source == containerNode.id &&
                    edge.sourceHandle?.startsWith("exec") == true
        }

        val innerNode = innerEdge?.target?.let { id -> graph.nodes.find { it.id == id } }
            ?: return emptyList()

        return generateNode(innerNode, emptyMap())
    }

    private fun resolveDataEdgeArgs(node: GraphNode, scope: Map<String, ScopeVar>): String {
        val dataEdges = graph.edges.filter { edge ->
            edge.target == node.id &&
                    edge.targetHandle?.endsWith(":flow") == false
        }
        return dataEdges.joinToString(", ") { edge ->
            val portId = edge.targetHandle?.split(":")?.firstOrNull() ?: return@joinToString "\"\""
            scope[portId]?.kotlinExpr ?: "\"\""
        }
    }

    private fun resolveFieldValue(
        node: GraphNode,
        fieldId: String,
        scope: Map<String, ScopeVar>,
        graph: NodeGraph
    ): String {
        //targetHandle with field: field_<fieldId>:PortType
        val edgeIsValid: (String) -> Boolean = { edgeId ->
            edgeId.startsWith("field_$fieldId:") || edgeId.startsWith("$fieldId:")
        }
        var dataEdge = graph.edges.firstOrNull { edge ->
            edge.target == node.id &&
                    edge.targetHandle?.let { edgeIsValid(it) } ?: false
        }

        if (dataEdge != null) {
            val sourceNode = graph.nodes.find { it.id == dataEdge.source }
            val data = sourceNode?.data
            val varName = data?.get("variableName")
            val fieldName = data?.get("fieldName") //fallback to component field
            val targetName = varName ?: fieldName

            targetName?.let { return it }

            val paramSchema = ComponentSchemaIndexer.getParamFromEdgeId(dataEdge.sourceHandle ?: "", sourceNode)

            println("Data edge: $dataEdge")
            println("Param schema: $paramSchema")
            return paramSchema?.name ?: "null"
        }

        //string literal
        dataEdge = graph.edges.firstOrNull { edge ->
            edge.target == node.id &&
                    edge.targetHandle?.contains(fieldId) == true
        }
        if (dataEdge != null) {
            val sourceNode = graph.nodes.find { it.id == dataEdge.source }
            val value = sourceNode?.data?.get("string")?.escapeKotlin() ?: "null"
            return "\"$value\""
        }

        //not wired
        val literal = node.data[fieldId]?.escapeKotlin() ?: ""
        return "\"$literal\""
    }

    fun String.toPascalCase(): String = split(Regex("[^a-zA-Z0-9]"))
        .joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }

    fun String.toCamelCase(): String = toPascalCase()
        .replaceFirstChar { it.lowercase() }

    fun String.escapeKotlin(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    fun addRequiredImport(packageName: String, vararg names: String) {
        requiredImports[packageName]?.addAll(names)
    }
}