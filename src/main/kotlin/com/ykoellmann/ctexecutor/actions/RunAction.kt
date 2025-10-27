package com.ykoellmann.ctexecutor.actions

import com.intellij.openapi.editor.Editor

/**
 * Run CT-Query Action
 * Shows popup with all CTEs, executes selected one with dependencies
 */
class RunAction : SqlActionBase() {

    override val title: String = "Execute CTE"

    override fun handleSelectedOption(editor: Editor, option: PopupOption) {
        SqlExecutor.executeSqlSeamlessly(editor, option.sql)
    }
}