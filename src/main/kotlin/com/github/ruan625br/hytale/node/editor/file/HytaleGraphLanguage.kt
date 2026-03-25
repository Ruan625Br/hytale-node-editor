package com.github.ruan625br.hytale.node.editor.file

import com.intellij.lang.Language


object HytaleGraphLanguage : Language("HytaleGraph") {
    private fun readResolve(): Any = HytaleGraphLanguage
    override fun isCaseSensitive(): Boolean {
        return false
    }
}