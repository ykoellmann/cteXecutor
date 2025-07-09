package com.ykoellmann.ctexecutor

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor

class RunAction(override val title: String = "Execute CTE") : CopyAndEditAction() {

    override fun handleSql(editor: Editor, sql: String) {
        executeSql(editor, sql)
    }

    /**
     * Inserts the given SQL into the document, selects it, executes it,
     * and deletes it afterwards.
     */
    fun executeSql(editor: Editor, sql: String) {
        val project = editor.project ?: return
        val document = editor.document

        val runnable = Runnable {
            val insertionPoint = insertSql(editor, sql)

            val offset = editor.caretModel.offset
            val start = insertionPoint
            val end = insertionPoint + sql.length + 1

            editor.caretModel.moveToOffset(end)
            editor.selectionModel.setSelection(start, end)

            val action = ActionManager.getInstance().getAction("Console.Jdbc.Execute")
            if (action != null) {
                ActionManager.getInstance().tryToExecute(
                    action,
                    null, // kein InputEvent, weil der Aufruf programmgesteuert erfolgt
                    editor.component,
                    ActionPlaces.UNKNOWN,
                    true
                )
            }

            // Remove the inserted SQL after execution to keep document clean
            ApplicationManager.getApplication().invokeLater {
                    WriteCommandAction.runWriteCommandAction(project) {
                        document.deleteString(start, document.textLength)
                    }
                editor.selectionModel.removeSelection()
            }

            editor.caretModel.moveToOffset(offset)
        }

        ApplicationManager.getApplication().invokeLater(runnable)
    }
}