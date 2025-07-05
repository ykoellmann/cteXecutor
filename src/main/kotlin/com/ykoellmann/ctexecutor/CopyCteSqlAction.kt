package com.ykoellmann.ctexecutor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

class CopyCteSqlAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val executor = CtePopupExecutor(editor, file)
        executor.show("Copy CTE") { selectedIndex ->
            val sql = executor.buildSqlForCteIndex(selectedIndex)
            copyToClipboard(sql)
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val selection = StringSelection(text)
        clipboard.setContents(selection, selection)
    }
}