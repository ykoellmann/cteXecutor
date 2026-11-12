package com.ykoellmann.ctexecutor.actions

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.settings.DatabaseSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentSynchronizationVetoer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.sql.psi.SqlPsiFacade
import com.intellij.util.DocumentUtil

/**
 * Utility for seamlessly executing SQL without adding to undo history
 */
object SqlExecutor {

    private val LOG = Logger.getInstance(SqlExecutor::class.java)

    /** Documents with temporarily inserted SQL; [SaveVetoer] keeps them off disk (and out of Local History). EDT only. */
    internal val unsavable = mutableSetOf<Document>()

    class SaveVetoer : FileDocumentSynchronizationVetoer() {
        override fun maySaveDocument(document: Document, isSaveExplicit: Boolean) = document !in unsavable
    }

    /**
     * Fires the native DB-console execute action on whatever is CURRENTLY selected in [editor] -
     * no paste/undo-transparent trick, no dependency resolution. Used when Ctrl+Enter is pressed
     * while the user already has code selected: in that case ExecuteFromHereAction should behave
     * exactly like the standard execute shortcut, not open the CTE-dependency popup.
     */
    fun executeCurrentSelection(editor: Editor) {
        val actionManager = ActionManager.getInstance()
        val executeAction = actionManager.getAction("Console.Jdbc.Execute")
            ?: actionManager.getAction("Console.Execute")
            ?: actionManager.getAction("Console.Execute.Multiline")
            ?: return

        val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
        val event = AnActionEvent.createEvent(
            executeAction,
            dataContext,
            null,
            ActionPlaces.UNKNOWN,
            ActionUiKind.NONE,
            null
        )
        ActionUtil.performAction(executeAction, event)
    }

    /**
     * Runs [sql] on the editor's attached console without touching the editor's document: the SQL is parsed
     * into a ScriptModel from a detached in-memory file and handed to `JdbcConsole.executeQueries`, which
     * reads the statement text from the model (and, unlike the execute action, does not save all documents).
     *
     * Uses internal Database-plugin API (JdbcConsole, JdbcConsoleProvider), so returns false - and the caller
     * falls back to the paste approach - if there is no attached console or that API is missing in this IDE
     * build (LinkageError). shortcut: ConsoleDataRequest creates range markers for the model's ranges in
     * [editor]'s document, so error-position navigation can point to the wrong spot; revisit if that matters.
     */
    private fun executeDirectly(editor: Editor, sql: String): Boolean {
        val project = editor.project ?: return false
        return try {
            val file = editor.virtualFile ?: return false
            val console = JdbcConsoleProvider.getValidConsole(project, file) ?: return false
            val language = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)?.language ?: return false
            val detached = PsiFileFactory.getInstance(project).createFileFromText("cte.sql", language, sql, false, true)
            val model = SqlPsiFacade.getInstance(project).createScriptModel(detached)
            if (model.statements().isEmpty) return false
            if (console.beforeExecuteQueries(model)) {
                console.executeQueries(editor, model, DatabaseSettings.getSettings().execOptions[0])
            }
            true
        } catch (e: LinkageError) {
            LOG.warn("Database plugin API not available, falling back to paste execution", e)
            false
        }
    }

    /**
     * Executes SQL seamlessly without undo history.
     *
     * First tries [executeDirectly]; if that is not possible, falls back to the steps below.
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
        if (executeDirectly(editor, sql)) return
        val document = editor.document
        val originalOffset = editor.caretModel.offset
        val insertionPoint = document.textLength

        // 1. Insert SQL without undo history
        unsavable += document
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

                // Create event using the modern, non-deprecated API
                val event = AnActionEvent.createEvent(
                    executeAction,
                    dataContext,
                    null,  // presentation
                    ActionPlaces.UNKNOWN,
                    ActionUiKind.NONE,
                    null   // inputEvent
                )
                ActionUtil.performAction(executeAction, event)

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
                    unsavable -= document
                }
            } else {
                unsavable -= document
            }
        }
    }
}