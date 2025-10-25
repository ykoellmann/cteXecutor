package com.ykoellmann.ctexecutor.actions

import com.intellij.openapi.editor.Editor
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Copy CT-Query SQL Action
 * Shows popup with all CTEs, copies selected one's SQL to clipboard
 */
class CopyAction : SqlActionBase() {

    override val title: String = "Copy CTE"

    override fun handleSelectedOption(editor: Editor, option: PopupOption) {
        copyToClipboard(option.sql)
    }

    private fun copyToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val selection = StringSelection(text)
        clipboard.setContents(selection, selection)
    }
}