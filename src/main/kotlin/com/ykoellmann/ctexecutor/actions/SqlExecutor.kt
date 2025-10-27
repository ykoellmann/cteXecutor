package com.ykoellmann.ctexecutor.actions

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.util.DocumentUtil

/**
 * Utility for seamlessly executing SQL without adding to undo history
 */
object SqlExecutor {

    /**
     * Executes SQL seamlessly without undo history.
     *
     * This function:
     * 1. Inserts SQL at the end of the document (without undo history)
     * 2. Selects the inserted text
     * 3. Triggers the SQL execute action
     * 4. Removes the inserted SQL after execution (without undo history)
     * 5. Restores the original cursor position
     *
     * @param editor The editor to execute SQL in
     * @param sql The SQL to execute
     */
    fun executeSqlSeamlessly(editor: Editor, sql: String) {
        val project = editor.project ?: return
        val document = editor.document
        val originalOffset = editor.caretModel.offset
        val insertionPoint = document.textLength

        // 1. Insert SQL without undo history
        DocumentUtil.writeInRunUndoTransparentAction {
            document.insertString(insertionPoint, "\n$sql")
        }

        // 2. Select the inserted text
        val start = insertionPoint + 1
        val end = document.textLength
        editor.caretModel.moveToOffset(end)
        editor.selectionModel.setSelection(start, end)

        // 3. Execute the SQL in the next EDT cycle
        ApplicationManager.getApplication().invokeLater {
            val actionManager = ActionManager.getInstance()

            // Try different execute actions in order of preference
            val executeAction = actionManager.getAction("Console.Jdbc.Execute")
                ?: actionManager.getAction("Console.Execute")
                ?: actionManager.getAction("Console.Execute.Multiline")

            if (executeAction != null) {
                val dataContext = DataManager.getInstance()
                    .getDataContext(editor.contentComponent)

                // Create the event first
                val event = AnActionEvent.createEvent(
                    executeAction,
                    dataContext,
                    null,
                    ActionPlaces.UNKNOWN,
                    ActionUiKind.NONE,
                    null
                )

                // Then invoke the action directly
                executeAction.actionPerformed(event)

                // 4. Remove the inserted SQL after execution (without undo history)
                ApplicationManager.getApplication().invokeLater {
                    DocumentUtil.writeInRunUndoTransparentAction {
                        if (document.textLength >= insertionPoint) {
                            document.deleteString(insertionPoint, document.textLength)
                        }
                    }

                    // 5. Restore original state
                    editor.selectionModel.removeSelection()
                    editor.caretModel.moveToOffset(originalOffset)
                }
            }
        }
    }
}