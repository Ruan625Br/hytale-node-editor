package com.github.ruan625br.hytale.node.editor.codegen.resolver

import com.github.ruan625br.hytale.node.editor.codegen.model.GraphNode
import com.github.ruan625br.hytale.node.editor.codegen.model.NodeGraph
import fleet.util.safeAs

class GraphResolver(private val graph: NodeGraph) {

    fun resolve(): ResolveChain {
        val entryLabels = setOf(
            "PlayerConnectEvent", "PlayerDisconnectEvent", "BlockPlaceEvent",
            "CommandEntry", "FunctionEntry"
        )

        val entry = graph.nodes.firstOrNull { it.data["label"] in entryLabels }
            ?: error("Graph ${graph.name} no has entry node")

        val flowAdj = graph.edges
            .filter { it.sourceHandle?.endsWith(":flow") == true }
            .groupBy { it.source }
            .mapValues { (_, edges) -> edges.map { it.target } }

        val chain = mutableListOf<GraphNode>()
        val visited = mutableSetOf<String>()
        var current: String? = flowAdj[entry.id]?.firstOrNull()

        while (current != null && current !in visited) {
            visited.add(current)
            val node = graph.nodes.find { it.id == current } ?: break
            chain.add(node)
            current = flowAdj[current]?.firstOrNull()
        }

        val scope = buildInitialScope(entry)
        return ResolveChain(entry, chain, scope)
    }


    fun resolve(entry: GraphNode): ResolveChain {
        val flowAdj = graph.edges
            .filter { it.sourceHandle?.endsWith(":flow") == true }
            .groupBy { it.source }
            .mapValues { (_, edges) -> edges.map { it.target } }

        val chain = mutableListOf<GraphNode>()
        val visited = mutableSetOf<String>()
        var current: String? = flowAdj[entry.id]?.firstOrNull()

        while (current != null && current !in visited) {
            visited.add(current)
            val node = graph.nodes.find { it.id == current } ?: break
            chain.add(node)
            current = flowAdj[current]?.firstOrNull()
        }

        val scope = buildInitialScope(entry)
        return ResolveChain(entry, chain, scope)
    }

    fun resolve(graph: NodeGraph): Pair<List<GraphNode>, Map<String, ScopeVar>> {
        val scope     = mutableMapOf<String, ScopeVar>()
        val nodeById  = graph.nodes.associateBy { it.id }
        val ordered   = mutableListOf<GraphNode>()
        val visited   = mutableSetOf<String>()

        val entryNode = graph.nodes.firstOrNull { node ->
            val hasExecOut = graph.edges.any { e ->
                e.source == node.id && e.sourceHandle?.endsWith(":flow") == true
            }
            val hasExecIn = graph.edges.any { e ->
                e.target == node.id && e.targetHandle?.endsWith(":flow") == true
            }
            hasExecOut && !hasExecIn
        } ?: return emptyList<GraphNode>() to emptyMap()

        populateEntryScope(entryNode, scope)

        // Walk pela chain de flow
        fun walk(node: GraphNode) {
            if (node.id in visited) return
            visited.add(node.id)

            // Antes de processar este nó, resolve os data edges que chegam nele
            resolveDataInputs(node, graph, nodeById, scope)

            ordered.add(node)

            // Popula scope com os outputs deste nó para os próximos usarem
            populateNodeOutputScope(node, graph, scope)
            val nextEdge = graph.edges.firstOrNull { e ->
                e.source == node.id &&
                        (e.sourceHandle?.endsWith(":flow") == true) &&
                        (e.sourceHandle?.startsWith("exec") == true || e.sourceHandle?.startsWith("exec_out") == true)
            }
            val nextNode = nextEdge?.target?.let { nodeById[it] }
            if (nextNode != null) walk(nextNode)
        }

        walk(entryNode)
        return ordered to scope
    }

    private fun populateEntryScope(entry: GraphNode, scope: MutableMap<String, ScopeVar>) {
        when (entry.data["label"]) {
            "SystemEntry" -> {
                scope["dt"]  = ScopeVar("dt",  "dt")
                scope["ref"] = ScopeVar("ref", "ref")
            }
            "PlayerConnectEvent", "PlayerDisconnectEvent", "BlockPlaceEvent" -> {
                scope["playerRef"] = ScopeVar("playerRef", "event.playerRef")
                scope["world"]     = ScopeVar("world",     "event.world")
            }
            "CommandEntry" -> {
                scope["context"] = ScopeVar("context", "commandContext")
            }
        }
    }

    private fun populateNodeOutputScope(
        node:  GraphNode,
        graph: NodeGraph,
        scope: MutableMap<String, ScopeVar>,
    ) {
        val varName = "v_${node.id.take(6)}"

        when (node.data["label"]) {
            "Add"        -> scope["result"] = ScopeVar("result", "$varName")
            "Accumulate" -> scope["result"] = ScopeVar("result", "$varName")
            "Decrement"  -> scope["result"] = ScopeVar("result", "$varName")
            "GetField", "Get Field" -> {
                val comp  = node.data["componentId"]?.replaceFirstChar { it.lowercase() } ?: "component"
                val field = node.data["fieldName"] ?: "field"
                scope["value"] = ScopeVar("value", "$comp.$field")
            }
        }
    }

    private fun resolveDataInputs(
        node:     GraphNode,
        graph:    NodeGraph,
        nodeById: Map<String, GraphNode>,
        scope:    MutableMap<String, ScopeVar>,
    ) {
        graph.edges
            .filter { e -> e.target == node.id }
            .filter { e -> e.targetHandle?.endsWith(":flow") == false }
            .forEach { edge ->
                val sourceNode   = nodeById[edge.source] ?: return@forEach
                val sourcePortId = edge.sourceHandle?.split(":")?.firstOrNull() ?: return@forEach
                val targetPortId = edge.targetHandle?.split(":")?.firstOrNull() ?: return@forEach

                // Expressão que este port representa
                val expr = scope[sourcePortId]?.kotlinExpr
                    ?: generateInlineExpr(sourceNode, sourcePortId)

                scope[targetPortId] = ScopeVar(targetPortId, expr)
            }
    }

    private fun generateInlineExpr(node: GraphNode, portId: String): String {
        return when (node.data["label"]) {
            "Number Literal" -> node.data["value"] ?: "0"
            "Get Variable"   -> node.data["variableName"] ?: "unknown"
            "Get Field", "GetField" -> {
                val comp  = node.data["componentId"]?.replaceFirstChar { it.lowercase() } ?: "component"
                val field = node.data["fieldName"] ?: "field"
                "$comp.$field"
            }
            else -> "/* unresolved: ${node.data["label"]} */"
        }
    }

    private fun buildInitialScope(entry: GraphNode): Map<String, ScopeVar> {
        val scope = mutableMapOf<String, ScopeVar>()

        when (entry.data["label"]) {
            "PlayerConnectEvent", "PlayerDisconnectEvent" -> {
                scope["playerRef"] = ScopeVar("playerRef", "event.playerRef", "player")
                scope["world"]     = ScopeVar("world",     "event.world!!",   "world")
            }
            "BlockPlaceEvent" -> {
                scope["playerRef"] = ScopeVar("playerRef", "event.playerRef", "player")
                scope["blockId"]   = ScopeVar("blockId",   "event.blockId",   "string")
            }
            "CommandEntry" -> {
                scope["context"]   = ScopeVar("context",   "context",         "context")
            }
            "FunctionEntry" -> {
                scope["playerRef"] = ScopeVar("playerRef", "playerRef",       "player")
                scope["world"]     = ScopeVar("world",     "world",           "world")
            }
        }
        return scope
    }

    data class ScopeVar(
        val portId: String,
        val kotlinExpr: String,
        val type: String = "",
    )

    data class ResolveChain(
        val entryNode: GraphNode,
        val chain: List<GraphNode>,
        val scope: Map<String, ScopeVar>,
    )
}