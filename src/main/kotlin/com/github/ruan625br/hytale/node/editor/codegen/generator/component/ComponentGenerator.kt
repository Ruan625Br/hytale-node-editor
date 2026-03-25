package com.github.ruan625br.hytale.node.editor.codegen.generator.component

import com.fasterxml.jackson.databind.util.ClassUtil.defaultValue
import com.github.ruan625br.hytale.node.editor.codegen.generator.NodeBodyGenerator
import com.github.ruan625br.hytale.node.editor.codegen.generator.utils.HytaleClassName.ENTITY_STORE
import com.github.ruan625br.hytale.node.editor.codegen.generator.utils.HytaleCodecUtils.codecTypeFromString
import com.github.ruan625br.hytale.node.editor.codegen.generator.utils.TypeNameUtils
import com.github.ruan625br.hytale.node.editor.codegen.generator.utils.TypeNameUtils.defaultValueForType
import com.github.ruan625br.hytale.node.editor.codegen.generator.utils.TypeNameUtils.kotlinTypeFromString
import com.github.ruan625br.hytale.node.editor.codegen.model.GraphNode
import com.github.ruan625br.hytale.node.editor.codegen.model.NodeGraph
import com.github.ruan625br.hytale.node.editor.codegen.model.toFunName
import com.github.ruan625br.hytale.node.editor.codegen.model.toPascalCase
import com.github.ruan625br.hytale.node.editor.codegen.resolver.GraphResolver
import com.github.ruan625br.hytale.node.editor.graph.ComponentMethodSchema
import com.github.ruan625br.hytale.node.editor.graph.ComponentSchemaIndexer
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import io.opentelemetry.sdk.logs.data.Body

class ComponentGenerator(
    private val graph: NodeGraph,
    private val basePkg: String,
    private val bodyGenerator: NodeBodyGenerator) {

    fun generate(): FileSpec {
        val className = "${graph.name.toPascalCase()}Component"
        val fields = graph.nodes
            .filter { it.data["label"] == "ComponentField" }
            .map { node ->
                node.toComponent()
            }

        val constructorSpec = FunSpec.constructorBuilder().apply {
            fields.forEach { field ->
                addParameter(
                    ParameterSpec.builder(field.name, kotlinTypeFromString(field.type))
                        .apply {
                            field.defaultValue?.let {
                                val defaultValue = if (field.type == "String") "\"$it\"" else it
                                defaultValue(defaultValue)
                            }
                        }
                        .build()
                )
            }
        }.build()

        val cloneBody = buildString {
            appendLine("return $className().also {")
            fields.forEach { f ->
                if (f.isMutable) {
                    appendLine("    it.${f.name} = this.${f.name}")
                }
            }
            appendLine("}")
        }

        val codecBody = buildString {
            appendLine("BuilderCodec")
            appendLine("    .builder($className::class.java, ::$className)")
            fields.forEach { f ->

                appendLine(
                    """    .append(KeyedCodec("${f.name.replaceFirstChar { it.uppercase() }}", ${
                        codecTypeFromString(
                            f.type
                        )
                    }),"""
                )
                val setterBody = if (f.isMutable) """c.${f.name} = v""" else """ /*${f.name} is not mutable.*/"""
                appendLine("""        { c, v -> $setterBody}, { it.${f.name}}).add()""")
            }
            append("    .build()")

        }

        val companionSpec = TypeSpec.companionObjectBuilder()
            .addProperty(
                PropertySpec.builder(
                    "Type",
                    ClassName("com.hypixel.hytale.component", "ComponentType")
                        .parameterizedBy(ENTITY_STORE, ClassName("$basePkg.components", className))
                )
                    .mutable(true)
                    .addModifiers(KModifier.LATEINIT)
                    .build()
            )
            .addProperty(
                PropertySpec.builder(
                    "CODEC",
                    ClassName("com.hypixel.hytale.codec.builder", "BuilderCodec")
                        .parameterizedBy(ClassName("$basePkg.components", className))
                )
                    .initializer(CodeBlock.of(codecBody))
                    .build()
            )
            .build()

        val classSpec = TypeSpec.classBuilder(className)
            //.primaryConstructor(constructorSpec)
            .addSuperinterface(
                ClassName("com.hypixel.hytale.component", "Component")
                    .parameterizedBy(ENTITY_STORE)
            )
            .apply {
                fields.forEach { f ->
                    addProperty(
                        PropertySpec.builder(f.name, kotlinTypeFromString(f.type))
                            .apply {
                                mutable(f.isMutable)
                                if (!f.isMutable && f.defaultValue == null) {
                                    initializer(defaultValueForType(f.type).toString())
                                } else {
                                    val defaultValue = if (f.type == "String") "\"${f.defaultValue}\"" else f.defaultValue ?: defaultValueForType(f.type)
                                    initializer(defaultValue.toString())

                                }
                            }
                            .build()
                    )
                }

                addMethods()
            }
            .addFunction(
                FunSpec.builder("clone")
                    .addModifiers(KModifier.OVERRIDE)
                    .addAnnotation(ClassName("javax.annotation", "Nullable"))
                    .returns(
                        ClassName("com.hypixel.hytale.component", "Component")
                            .parameterizedBy(ENTITY_STORE)
                            .copy(nullable = true)
                    )
                    .addCode(CodeBlock.of(cloneBody))
                    .build()
            )
            .addType(companionSpec)
            .build()

        val fileSpec = FileSpec.builder("$basePkg.components", className)
            .addFileComment("Generated file. Do not modify!")
            .addImport("com.hypixel.hytale.codec.builder", "BuilderCodec", "BuilderCodec")
            .addImport("com.hypixel.hytale.codec", "Codec", "KeyedCodec")
            .addType(classSpec)
            .build()
        return fileSpec
    }


    fun TypeSpec.Builder.addMethods() {
        val indexer = ComponentSchemaIndexer("")
        val methods = graph.nodes
            .filter { it.data["label"] == "ComponentMethod" }
            .map { methodNode ->
                val params = indexer.extractMethodParams(methodNode, graph)
                methodNode to ComponentMethodSchema(
                    name = methodNode.data["methodName"] ?: return@map null,
                    returnType = methodNode.data["returnType"] ?: "Unit",
                    params = params,
                )
            }
            .filterNotNull()

        methods.forEach { (methodNode, schema) ->
            val body = generateBodyForMethod(methodNode, schema)
            val funSpec = FunSpec.builder(schema.name.toFunName())
                .apply {
                    schema.params.forEach { param ->
                        addParameter(param.name, kotlinTypeFromString(param.type))
                    }
                    addCode(CodeBlock.of(body))
                }

            addFunction(funSpec.build())
        }
    }

    fun generateBodyForMethod(methodNode: GraphNode, methodSchema: ComponentMethodSchema): String {
        val resolver = GraphResolver(graph)
        val resolved = resolver.resolve(methodNode)

        val lines = resolved.chain.flatMap { node ->
            bodyGenerator.generateNode(node, resolved.scope)
        }

        return buildString {
            lines.forEach { appendLine(it) }
        }.trimEnd()
    }

    fun GraphNode.toComponent(): ComponentFieldSpec {
        return ComponentFieldSpec(
            name = data["fieldName"] ?: "field",
            type = data["fieldType"] ?: "String",
            defaultValue = data["defaultValue"],
            isMutable = data["isMutable"] != "false",
        )
    }

    data class ComponentFieldSpec(
        val name: String,
        val type: String,
        val defaultValue: String?,
        val isMutable: Boolean,
    )
}