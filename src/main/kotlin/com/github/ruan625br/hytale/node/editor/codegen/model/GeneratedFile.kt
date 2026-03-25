package com.github.ruan625br.hytale.node.editor.codegen.model

import com.intellij.openapi.project.Project
import com.squareup.kotlinpoet.FileSpec

data class GeneratedFile(
    val path: String,
    val fileSpec: FileSpec,
)

data class GenerationResult(val files: List<GeneratedFile>)

fun String.toPath() = replace('.', '/')

fun String.toPascalCase(): String {
    return this
        .split(Regex("[^a-zA-Z0-9]+"))
        .filter { it.isNotBlank() }
        .joinToString("") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
}

fun String.toFunName(): String {
    return this
        .split(Regex("[^a-zA-Z0-9]+"))
        .filter { it.isNotBlank() }
        .joinToString("") { word ->
            word.replaceFirstChar { it.lowercase() }
        }
}

fun Project.removeBasePathFromFilePath(filePath: String): String {
   return filePath.replace(basePath.toString(), "")
}