package com.ykoellmann.ctexecutor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.ui.Messages

/**
 * Execute from Here - Directly executes SQL at cursor with all dependencies
 * No popup, just analyze → highlight → execute
 */
class ExecuteFromHereAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return

        try {
            // Analyze dependencies
            val analyzer = SqlDependencyAnalyzer(file, editor.caretModel.offset)
            val context = analyzer.analyze()

            if (context == null) {
                Messages.showInfoMessage(
                    editor.component,
                    "Could not find SQL query with CTEs at cursor position.",
                    "Execute from Here"
                )
                return
            }

            // Highlight all required elements
            val highlighters = highlightRanges(editor, context.highlightRanges)

            // Show info about what will be executed
            val message = buildString {
                append("Executing query with ${context.ctes.size} CTE(s):\n")
                context.ctes.forEach { cte ->
                    append("  • ${cte.name}\n")
                }
                append("\nProceed?")
            }

            val result = Messages.showYesNoDialog(
                editor.component,
                message,
                "Execute from Here",
                Messages.getQuestionIcon()
            )

            // Remove highlights
            highlighters.forEach { editor.markupModel.removeHighlighter(it) }

            if (result == Messages.YES) {
                // Execute the SQL
                executeSQL(editor, context.fullSql)
            }

        } catch (ex: Exception) {
            Messages.showErrorDialog(
                editor.component,
                "Error analyzing SQL: ${ex.message}\n\nPlease check the IntelliJ log for details.",
                "Execute from Here Error"
            )
            ex.printStackTrace()
        }
    }

    /**
     * Highlights the given text ranges in the editor
     */
    private fun highlightRanges(editor: Editor, ranges: List<com.intellij.openapi.util.TextRange>): List<RangeHighlighter> {
        val highlighters = mutableListOf<RangeHighlighter>()

        val colorScheme = EditorColorsManager.getInstance().globalScheme
        val attributes = colorScheme.getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES)
        val highlightColor = attributes.backgroundColor

        for (range in ranges) {
            val highlighter = editor.markupModel.addRangeHighlighter(
                range.startOffset,
                range.endOffset,
                HighlighterLayer.SELECTION - 1,
                TextAttributes(null, highlightColor, null, null, 0),
                HighlighterTargetArea.EXACT_RANGE
            )
            highlighters.add(highlighter)
        }

        return highlighters
    }

    /**
     * Executes the SQL using the existing RunAction logic
     */
    private fun executeSQL(editor: Editor, sql: String) {
        val runAction = RunAction()
        runAction.executeSql(editor, sql)
    }
}