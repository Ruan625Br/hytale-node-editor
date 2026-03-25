package com.github.ruan625br.hytale.node.editor.file

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object HytaleGraphFileType : LanguageFileType(HytaleGraphLanguage) {

    override fun getName(): String = "Hytale Graph"
    override fun getDescription(): String = "Hytale node editor graph"
    override fun getDefaultExtension(): String = "hgraph"

    override fun getIcon(): Icon = AllIcons.FileTypes.Json
}