package com.ykoellmann.ctexecutor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor

open class CopyAndEditAction(override val title: String = "Copy CTE") : CteAction() {
    
    override fun handleSql(editor: Editor, sql: String) {
        var finalSql = sql.trim(';') + "\n;"
        if (!editor.document.text.trim(' ').endsWith(";")) {
            finalSql = ";\n$finalSql"
        }
        val insertionPoint = insertSql(editor, finalSql)
        val end = insertionPoint + finalSql.length
        editor.caretModel.moveToOffset(end)
    }

    fun insertSql(editor: Editor, sql: String): Int {
        // Remember document length before insertion to remove inserted SQL later
        val insertionPoint = editor.document.textLength
        WriteCommandAction.runWriteCommandAction(editor.project) {
            editor.document.insertString(insertionPoint, "\n$sql")
        }
        return insertionPoint
    }
}