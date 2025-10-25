package com.ykoellmann.ctexecutor

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor

/**
 * Run CT-Query Action
 * Shows popup with all CTEs, executes selected one with dependencies
 */
class RunAction : SqlActionBase() {

    override val title: String = "Execute CTE"

    override fun handleSelectedOption(editor: Editor, option: PopupOption) {
        executeSql(editor, option.sql)
    }

    /**
     * Inserts the given SQL into the document, selects it, executes it,
     * and deletes it afterwards.
     */
    fun executeSql(editor: Editor, sql: String) {
        val project = editor.project ?: return
        val document = editor.document

        val runnable = Runnable {
            val insertionPoint = document.textLength

            // Insert SQL
            WriteCommandAction.runWriteCommandAction(project) {
                document.insertString(insertionPoint, "\n$sql")
            }

            val offset = editor.caretModel.offset
            val start = insertionPoint
            val end = insertionPoint + sql.length + 1

            editor.caretModel.moveToOffset(end)
            editor.selectionModel.setSelection(start, end)

            // Execute
            val action = ActionManager.getInstance().getAction("Console.Jdbc.Execute")
            if (action != null) {
                ActionManager.getInstance().tryToExecute(
                    action,
                    null,
                    editor.component,
                    ActionPlaces.UNKNOWN,
                    true
                )
            }

            // Remove the inserted SQL after execution
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