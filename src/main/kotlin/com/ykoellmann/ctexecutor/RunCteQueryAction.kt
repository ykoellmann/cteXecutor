package com.ykoellmann.ctexecutor

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor

class RunCteQueryAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val executor = CtePopupExecutor(editor, file)
        executor.show("Execute CTE") { selectedIndex ->
            val sql = executor.buildSqlForCteIndex(selectedIndex)
            executeSql(editor, sql)
        }
    }

    private fun executeSql(editor: Editor, sql: String) {
        val project = editor.project ?: return
        val document = editor.document

        ApplicationManager.getApplication().invokeLater {
            val offset = editor.caretModel.offset
            val insertionPoint = document.textLength
            WriteCommandAction.runWriteCommandAction(project) {
                document.insertString(insertionPoint, "\n$sql")
            }

            val start = insertionPoint + 1
            val end = insertionPoint + sql.length + 1

            editor.caretModel.moveToOffset(end)
            editor.selectionModel.setSelection(start, end)

            val dataContext = DataManager.getInstance().getDataContext(editor.component)
            val action = ActionManager.getInstance().getAction("Console.Jdbc.Execute")
            if (action != null) {
                val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, dataContext)
                action.actionPerformed(event)
            }

            ApplicationManager.getApplication().invokeLater {
                if (end <= document.textLength) {
                    WriteCommandAction.runWriteCommandAction(project) {
                        document.deleteString(start, end)
                    }
                }
                editor.selectionModel.removeSelection()
            }

            editor.caretModel.moveToOffset(offset)
        }
    }
}