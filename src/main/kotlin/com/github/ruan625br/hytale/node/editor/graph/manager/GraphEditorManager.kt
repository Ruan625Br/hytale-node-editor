package com.github.ruan625br.hytale.node.editor.graph.manager

import com.github.ruan625br.hytale.node.editor.graph.GraphFileEditor

object GraphEditorManager {
    val editors = mutableSetOf<GraphFileEditor>()

    fun register(editor: GraphFileEditor) {
        editors.add(editor)
    }

    fun unregister(editor: GraphFileEditor) {
        editors.remove(editor)
    }


    fun broadcast(js: String) {
        editors.forEach { editor ->
            editor.executeJs(js)
        }
    }
    fun reloadSchemaIndex() {
        editors.forEach { editor ->
            editor.reloadSchema()
        }
    }
}