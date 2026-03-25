package com.github.ruan625br.hytale.node.editor.graph

import com.github.ruan625br.hytale.node.editor.codegen.generator.KotlinCodeGenerator
import com.github.ruan625br.hytale.node.editor.codegen.model.NodeGraph
import com.github.ruan625br.hytale.node.editor.codegen.model.removeBasePathFromFilePath
import com.github.ruan625br.hytale.node.editor.graph.manager.GraphEditorManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import java.beans.PropertyChangeListener
import java.io.File
import java.util.*
import javax.swing.JComponent

class GraphFileEditor(
    private val project: Project,
    private val file: VirtualFile
) : UserDataHolderBase(), FileEditor {

    private val browser = JBCefBrowser()
    private var modified = false
    private val json = Json { ignoreUnknownKeys = true }

    init {
        GraphEditorManager.register(this)

        browser.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {

           /* override fun onConsoleMessage(
                browser: CefBrowser?,
                level: CefSettings.LogSeverity?,
                message: String?,
                source: String?,
                line: Int
            ): Boolean {
                println("JCEF Console [$level] $source:$line — $message")
                return true
            }*/

        }, browser.cefBrowser)

        val appUrl = resolveAppUrl()
        browser.loadURL(appUrl)


        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadError(
                browser: CefBrowser?,
                frame: CefFrame?,
                errorCode: CefLoadHandler.ErrorCode?,
                errorText: String?,
                failedUrl: String?
            ) {
                println("JCEF load error: $errorCode — $errorText — url: $failedUrl")

            }

            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                setupRouter()

                if (frame?.isMain == true) {
                    loadGraphIntoEditor()
                }
            }
        }, browser.cefBrowser)
    }

    override fun getComponent(): JComponent = browser.component

    override fun getPreferredFocusedComponent(): JComponent = browser.component

    override fun getName(): String = "Node Editor"

    override fun isModified(): Boolean = modified

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun getState(level: FileEditorStateLevel): FileEditorState {
        val currentUrl = browser.cefBrowser.url ?: resolveAppUrl()
        return HytaleGraphEditorState(
            currentUrl,
            getZoomAndOffset()
        )
    }

    override fun setState(state: FileEditorState) {
        if (state is HytaleGraphEditorState) {
            restoreZoomAndOffset(state)
        }
    }

    override fun getFile(): VirtualFile {
        return file
    }


    override fun dispose() {
        GraphEditorManager.unregister(this)
        Disposer.dispose(browser)
    }

    fun setupRouter() {
        val router = CefMessageRouter.create()
        router.addHandler(object : CefMessageRouterHandlerAdapter() {
            override fun onQuery(
                browser: CefBrowser?,
                frame: CefFrame?,
                queryId: Long,
                request: String?,
                persistent: Boolean,
                callback: CefQueryCallback?
            ): Boolean {

                val msg = try {
                    json.decodeFromString<BridgeMessage>(request!!)
                } catch (e: Exception) {
                    println("Erro ao deserializar BridgeMessage: ${e.message}")
                    callback?.failure(400, "deserialização falhou: ${e.message}")
                    return true
                }
                println("Msg: $msg")

                when (msg.type) {
                    "GRAPH_CHANGED" -> {
                        modified = true
                        ReadAction.run<RuntimeException> {
                            FileDocumentManager.getInstance().getDocument(file)
                        }
                        callback?.success("ok")
                    }

                    "SAVE" -> {
                        println("Save")
                        saveGraph(msg.payload)
                        modified = false

                        val schema = ComponentSchemaIndexer(project.basePath!!).buildEncodedSchemaJson()
                        println("Schama: $schema")

                        executeJs(
                            "window.__hytaleLoadSchemaIndex(`$schema`)",
                        )

                        callback?.success("ok")
                    }

                    "GENERATE_CODE" -> {
                        println("GenerateCode")
                        val generator = KotlinCodeGenerator(project)

                        val result = runCatching {
                            val siblings = file.parent?.children
                                ?.filter { it.extension == "hgraph" && it != file }
                                ?.associate { sibling ->
                                    val alias = sibling.nameWithoutExtension
                                    val content = String(sibling.contentsToByteArray(), Charsets.UTF_8)
                                    alias to content
                                } ?: emptyMap()

                            println("Siblings: $siblings")

                            generator.generate(msg.payload, siblings)
                        }.getOrElse { e ->
                            println("Error generating code: ${e.message}")
                            callback?.failure(400, "generating code: ${e.message}")
                            return true
                        }

                        WriteCommandAction.runWriteCommandAction(project) {
                            result.files.forEach { generate ->
                                generator.writeGeneratedFile(generate)
                            }
                        }

                        println("Code generation successful, ${result.files.size} files generated.")
                        callback?.success("""{"files":${result.files.size}}""")
                    }
                }
                return true
            }
        }, true)
        browser.jbCefClient.cefClient.addMessageRouter(router)
    }


    private fun loadGraphIntoEditor() {
        println("Loading graph into editor...")
        val content = String(file.contentsToByteArray(), Charsets.UTF_8)

        val graphPath = project.removeBasePathFromFilePath(file.path)
        val graphDecoded = runCatching {
            json.decodeFromString<NodeGraph>(content)
        }.getOrElse { e ->
            println("Error decoding graph: ${e.message}")
            null
        }
        val graphCopy = graphDecoded?.copy(
            graphPath = graphPath,
            name = graphDecoded.name.ifBlank { file.name },
        )
        val newGraph = graphCopy ?: NodeGraph(name = file.name, path = graphPath)

        val targetContent = Json.encodeToString(newGraph)
        val targetContentEncoded = Base64.getEncoder()
            .encodeToString(targetContent.toByteArray(Charsets.UTF_8))

        println(buildString {
            appendLine("BasePath: ${project.basePath}")
        })

        println("File: ${file.path}")
        println("Load graph: $newGraph")
        val schema = ComponentSchemaIndexer(project.basePath!!).buildEncodedSchemaJson()

        println("Schama: $schema")
        println(targetContentEncoded)


        executeJs(
            """
            window.__hytaleLoadGraph(atob("$targetContentEncoded"))
            """.trimIndent(),
        )
        reloadSchema()
    }

    private fun saveGraph(jsonContent: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            println("Save in file this is old?: ${file.path}")
            val graph = json.decodeFromString<NodeGraph>(jsonContent)
            println("Graph: $graph")
            val file = File(project.basePath, graph.graphPath)
            println("Target path: ${file.path}")
            try {
                file.outputStream().use { stream ->
                    stream.write(jsonContent.toByteArray())
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                }
                GraphEditorManager.reloadSchemaIndex()
            } catch (e: IOException) {
                println("Error saving graph: ${e.message}")
            }

            /*file.getOutputStream(this).use { stream ->
                stream.write(jsonContent.toByteArray(Charsets.UTF_8))
            }*/
        }
    }

    private fun resolveAppUrl(): String {
        return "http://localhost:5173/"
    }

    private fun getZoomAndOffset(): String {
        // Poderia executar JS para pegar o viewport atual do ReactFlow
        return "{}"
    }

    private fun restoreZoomAndOffset(state: HytaleGraphEditorState) {
        executeJs("window.__hytaleRestoreViewport(${state.viewport})")
    }

    fun reloadSchema() {
        println("Reload schema: $")
        val schema = ComponentSchemaIndexer(project.basePath!!).buildEncodedSchemaJson()
        executeJs("""window.__hytaleLoadSchemaIndex('$schema')""")
    }

    fun executeJs(script: String) {
        browser.cefBrowser.executeJavaScript(
            script,
            browser.cefBrowser.url,
            0
        )
    }
}

@Serializable
data class HytaleGraphEditorState(
    val url: String,
    val viewport: String
) : FileEditorState {
    override fun canBeMergedWith(other: FileEditorState, level: FileEditorStateLevel) = false
}

@Serializable
data class BridgeMessage(val type: String, val payload: String)