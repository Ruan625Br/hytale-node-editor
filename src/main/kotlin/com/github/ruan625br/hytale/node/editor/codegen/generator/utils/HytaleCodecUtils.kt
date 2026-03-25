package com.github.ruan625br.hytale.node.editor.codegen.generator.utils

object HytaleCodecUtils {

    fun codecTypeFromString(type: String): String {
        return when (type) {
            "Float"   -> "Codec.FLOAT"
            "Int"     -> "Codec.INTEGER"
            "Boolean" -> "Codec.BOOL"
            "String"  -> "Codec.STRING"
            "Double"  -> "Codec.DOUBLE"
            else      -> "Codec.STRING"
        }
    }
}