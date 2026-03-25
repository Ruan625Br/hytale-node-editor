package com.github.ruan625br.hytale.node.editor.provider

import com.github.ruan625br.hytale.node.editor.graph.GraphFileEditor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class GraphEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file.extension == "hgraph"
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return GraphFileEditor(project, file)
    }

    override fun getEditorTypeId(): String = "HytaleGraph"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
}