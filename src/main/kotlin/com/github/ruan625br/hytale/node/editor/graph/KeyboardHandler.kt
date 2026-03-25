package com.github.ruan625br.hytale.node.editor.graph

import com.intellij.psi.impl.source.jsp.jspXml.JspExpression
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.executeJavaScript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.cef.browser.CefBrowser
import org.cef.handler.CefKeyboardHandler
import org.cef.handler.CefKeyboardHandlerAdapter
import org.cef.misc.EventFlags
fun JBCefBrowser.addGraphKeyboardHandler(scope: CoroutineScope) {
    val handler = object : CefKeyboardHandlerAdapter() {

        override fun onKeyEvent(browser: CefBrowser?, event: CefKeyboardHandler.CefKeyEvent?): Boolean {

            if (event == null) return false

            if (event.type !== CefKeyboardHandler.CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN) {
                return false
            }

            val ctrl = event.modifiers and EventFlags.EVENTFLAG_CONTROL_DOWN != 0
            val meta = event.modifiers and EventFlags.EVENTFLAG_COMMAND_DOWN != 0
            val mod = ctrl || meta

            val char = event.character.lowercaseChar()
            val keyCode = event.windows_key_code

            println(
                "chegou: $mod, $char, $keyCode"
            )

            if (event.focus_on_editable_field) return false

                when {
                    mod && char == 's' -> {
                        println("s 0")
                            callHytaleShortcut("save")
                        println("s")
                        return true
                    }

                    mod && char == 'z' -> {
                        println("z")
                            callHytaleShortcut("undo")

                        return true
                    }

                    mod && char == 'y' -> {
                        println("y")
                            callHytaleShortcut("redo")

                        return true
                    }

                    mod && keyCode == 13 -> { //Enter
                        println("enter")
                            callHytaleShortcut("generate")

                        return true
                    }

                    !mod && char == 'c' -> {
                        println("c")
                        callHytaleShortcut("group")
                        return true
                    }
                }


            println("""
                onEvent: 
type=${event.type}
char=${event.character}
unmod=${event.unmodified_character}
keyCode=${event.windows_key_code}
modifiers=${event.modifiers}
editable=${event.focus_on_editable_field}
""".trimIndent())
            return false
        }
    }
    jbCefClient.addKeyboardHandler(handler, cefBrowser)
}

private fun JBCefBrowser.callHytaleShortcut(action: String) {
    cefBrowser.executeJavaScript(
        "window.__hytaleShortcut('$action')",
        cefBrowser.url,
        0
    )
}