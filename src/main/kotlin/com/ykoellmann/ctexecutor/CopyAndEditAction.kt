package com.ykoellmann.ctexecutor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor

/**
 * Copy and Edit SQL Action
 * Shows popup with all CTEs, inserts selected one's SQL into editor
 */
class CopyAndEditAction : SqlActionBase() {

    override val title: String = "Copy and Edit CTE"

    override fun handleSelectedOption(editor: Editor, option: PopupOption) {
        insertSql(editor, option.sql)
    }

    private fun insertSql(editor: Editor, sql: String) {
        var finalSql = sql.trim(';') + "\n;"
        if (!editor.document.text.trim(' ').endsWith(";")) {
            finalSql = ";\n$finalSql"
        }

        val insertionPoint = editor.document.textLength

        WriteCommandAction.runWriteCommandAction(editor.project) {
            editor.document.insertString(insertionPoint, "\n$finalSql")
        }

        val end = insertionPoint + finalSql.length + 1
        editor.caretModel.moveToOffset(end)
    }
}