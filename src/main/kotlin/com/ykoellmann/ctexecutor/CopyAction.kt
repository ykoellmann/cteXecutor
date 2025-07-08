package com.ykoellmann.ctexecutor

import com.intellij.openapi.editor.Editor
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

class CopyAction(override val title: String = "Copy CTE") : CteAction() {

    override fun handleSql(editor: Editor, sql: String) {
        copyToClipboard(sql)
    }

    private fun copyToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val selection = StringSelection(text)
        clipboard.setContents(selection, selection)
    }
}