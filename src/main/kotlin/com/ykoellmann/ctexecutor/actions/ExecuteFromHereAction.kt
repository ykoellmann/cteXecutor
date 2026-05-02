package com.ykoellmann.ctexecutor.actions

import com.intellij.openapi.editor.Editor

class ExecuteFromHereAction : SqlActionBase() {
    override val title: String = "Execute from Here"
    override fun handleSelectedOption(editor: Editor, option: PopupOption) {
        SqlExecutor.executeSqlSeamlessly(editor, option.sql)
    }
}
