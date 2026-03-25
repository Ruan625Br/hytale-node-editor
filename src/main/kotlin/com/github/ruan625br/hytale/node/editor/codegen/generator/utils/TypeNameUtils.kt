package com.github.ruan625br.hytale.node.editor.codegen.generator.utils

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asTypeName

object TypeNameUtils {

    fun kotlinTypeFromString(type: String): TypeName {
        return when (type) {
            "Float", "float" -> Float::class.asTypeName()
            "Int" -> Int::class.asTypeName()
            "Boolean" -> Boolean::class.asTypeName()
            "String", "string" -> String::class.asTypeName()
            "Double" -> Double::class.asTypeName()
            "Vector3d" -> ClassName("com.hypixel.hytale.math.vector", "Vector3d")
            "Ref" -> ClassName("com.hypixel.hytale.component", "Ref")
                .parameterizedBy(ClassName("com.hypixel.hytale.server.core.universe.world.storage", "EntityStore"))
                .copy(nullable = true)

            else -> String::class.asTypeName()
        }
    }

    fun defaultValueForType(type: String, useStringLiteral: Boolean = false): Any? {
        return when (type) {
            "Float" -> 0f
            "Int" -> 0
            "Boolean" -> false
            "String" -> if (useStringLiteral) "\"\"" else ""
            "Double" -> 0.0
            else -> null
        }
    }
}