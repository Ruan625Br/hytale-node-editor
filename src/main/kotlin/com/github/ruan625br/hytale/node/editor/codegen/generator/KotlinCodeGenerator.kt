package com.github.ruan625br.hytale.node.editor.codegen.generator

import com.github.ruan625br.hytale.node.editor.codegen.generator.component.ComponentGenerator
import com.github.ruan625br.hytale.node.editor.codegen.model.*
import com.github.ruan625br.hytale.node.editor.codegen.resolver.GraphResolver
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.squareup.kotlinpoet.*
import kotlinx.serialization.json.Json
import java.io.File

class KotlinCodeGenerator(
    private val project: Project
) {
    private val basePkg = "com.yourmod.generated"
    private val json = Json { ignoreUnknownKeys = true }


    fun generate(graphJson: String, allGraphJsons: Map<String, String> = emptyMap()): GenerationResult {
        println("Graphs ${allGraphJsons.size}")
        val graph = json.decodeFromString<NodeGraph>(graphJson)
        val allGraphs = allGraphJsons.filter { (_, v) -> v.isNotBlank() }.mapValues { (_, v) ->
            json.decodeFromString<NodeGraph>(v)
        }
        val files = mutableListOf<GeneratedFile>()

        when (graph.graphType) {
            "event" -> files += generateListener(graph, allGraphs)
            "component" -> files += generateComponent(graph, allGraphs)
            //TODO: implement
            else -> {
                println("Unknown graph type ${graph.graphType}")
            }
        }

        return GenerationResult(files)
    }

    private fun generateListener(graph: NodeGraph, allGraphs: Map<String, NodeGraph>): GeneratedFile {
        val className = "${graph.name.toPascalCase()}Listener"
        val resolver = GraphResolver(graph)
        val resolved = resolver.resolve()
        val bodyGen = NodeBodyGenerator(graph, allGraphs)
        val body = bodyGen.generateBody(resolved)
        val eventClass = resolved.entryNode.data["eventClass"] ?: error("No event class")
        val handlerName = "on${eventClass.removeSuffix("Event")}"

        val classSpec = TypeSpec.classBuilder(className)
            .addProperty(
                PropertySpec.builder(
                    "LOGGER", ClassName("com.hypixel.hytale.logger", "HytaleLogger")
                )
                    .addModifiers(KModifier.PRIVATE).initializer("HytaleLogger.forEnclosingClass()").build()
            )
            .addFunction(
                FunSpec.builder("register")
                    .addParameter("eventRegistry", ClassName("com.hypixel.hytale.event", "EventRegistry"))
                    .addStatement(
                        "eventRegistry.register(%T::class.java, ::%L)",
                        ClassName("com.hypixel.hytale.server.core.event.events.player", eventClass),
                        handlerName
                    )
                    .build()
            )
            .addFunction(
                FunSpec.builder(handlerName)
                    .addModifiers(KModifier.PRIVATE)
                    .addParameter(
                        "event",
                        ClassName("com.hypixel.hytale.server.core.event.events.player", eventClass)
                    )
                    .addCode(CodeBlock.of(body))
                    .build()
            )
            .build()

        val fileSpec =
            FileSpec.builder("$basePkg.listeners", className)
                .addFileComment("Generated file. Do not modify!")
                .addImport("com.hypixel.hytale.event", "EventRegistry")
                .addImport("com.hypixel.hytale.logger", "HytaleLogger")
                .addImport("com.hypixel.hytale.server.core", "Message")
                .addImport("com.hypixel.hytale.server.core.event.events.player", eventClass)
                .addImports(bodyGen.requiredImports.toMap())
                .addType(
                    classSpec

                ).build()

        return GeneratedFile(
            path = pathToFile("listeners/$className.kt"), fileSpec = fileSpec
        )
    }

    private fun generateComponent(graph: NodeGraph, allGraphs: Map<String, NodeGraph>): GeneratedFile {
        println("Generating component")
        val className = "${graph.name.toPascalCase()}Component"
        val bodyGen = NodeBodyGenerator(graph, allGraphs)
        val generator = ComponentGenerator(graph, basePkg, bodyGen)
        return GeneratedFile(
            path = pathToFile("components/$className.kt"), fileSpec = generator.generate()
        )
    }

    fun writeGeneratedFile(generated: GeneratedFile) {
        val projectPath = project.basePath ?: return
        "$projectPath/${generated.path}"
        val file = File("$projectPath/src/main/kotlin")
        generated.fileSpec.writeTo(file)

        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
    }


    fun pathToFile(file: String): String {
        return "src/main/kotlin/${basePkg.toPath()}/$file"
    }

    fun FileSpec.Builder.addImports(imports: Map<String, MutableSet<String>>) = apply {
        println("imports: $imports")
        imports.forEach { (packageName, names) ->
            addImport(packageName, names)
        }
    }

}