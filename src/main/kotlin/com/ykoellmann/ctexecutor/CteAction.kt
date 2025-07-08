package com.ykoellmann.ctexecutor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor

abstract class CteAction : AnAction() {
    
    abstract val title: String
    
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val executor = CtePopupExecutor(editor, file)
        executor.show(title) { selectedIndex ->
            val sql = executor.buildSqlForCteIndex(selectedIndex)
            handleSql(editor, sql)
        }
    }
    
    abstract fun handleSql(editor: Editor, sql: String)
}