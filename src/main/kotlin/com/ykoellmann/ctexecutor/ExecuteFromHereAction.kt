package com.ykoellmann.ctexecutor

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.elementType

/**
 * Execute from Here Action
 * Shows popup with:
 * - Current query at cursor (bottom)
 * - Required CTEs (from bottom to top)
 *
 * Uses recursive dependency analysis to find all required CTEs
 */
class ExecuteFromHereAction : SqlActionBase() {

    override val title: String = "Execute from Here"

    /**
     * Builds popup options in correct order:
     * - Required CTEs (at top, in order)
     * - Current query at cursor (at bottom, pre-selected)
     */
    override fun buildPopupOptions(result: SqlDependencyAnalyzer.AnalysisResult): List<PopupOption> {
        val options = mutableListOf<PopupOption>()

        // Options 1-N: Each required CTE (in normal order)
        for (cte in result.requiredCtes) {
            val sql = buildSqlForCtes(listOf(cte), cte.element)
            val highlightRanges = buildHighlightRangesForSingleCte(cte, result.allCtes)

            options.add(PopupOption(
                displayName = cte.name,
                sql = sql,
                highlightRanges = highlightRanges
            ))
        }

        // Last Option: Current query at cursor (with all dependencies) - AT BOTTOM
        val currentQueryName = if (result.targetQuery.node?.elementType ==
            com.intellij.sql.psi.SqlElementTypes.SQL_NAMED_QUERY_DEFINITION) {
            result.targetQuery.firstChild?.text ?: "Current CTE"
        } else {
            "Current Query"
        }

        options.add(PopupOption(
            displayName = "$currentQueryName (${result.requiredCtes.size} CTE${if (result.requiredCtes.size != 1) "s" else ""})",
            sql = result.fullSql,
            highlightRanges = result.highlightRanges
        ))

        return options
    }

    /**
     * Builds highlight ranges for a single CTE
     * Only highlights the inner SELECT part, not the full CTE definition
     */
    private fun buildHighlightRangesForSingleCte(
        cte: SqlDependencyAnalyzer.CteEntry,
        allCtes: List<SqlDependencyAnalyzer.CteEntry>
    ): List<TextRange> {
        val ranges = mutableListOf<TextRange>()
        val children = cte.element.children

        // Find the inner SELECT (between parentheses)
        val startIndex = children.indexOfFirst {
            it.elementType == com.intellij.sql.psi.SqlElementTypes.SQL_LEFT_PAREN
        }
        val endIndex = children.indexOfLast {
            it.elementType == com.intellij.sql.psi.SqlElementTypes.SQL_RIGHT_PAREN
        }

        if (startIndex >= 0 && endIndex >= 0 && startIndex + 1 < endIndex) {
            // Get elements between parentheses (the actual SELECT)
            val innerElements = children.filterIndexed { i, _ -> i in (startIndex + 1) until endIndex }
            if (innerElements.isNotEmpty()) {
                val start = innerElements.minOf { it.textRange.startOffset }
                val end = innerElements.maxOf { it.textRange.endOffset }
                ranges.add(com.intellij.openapi.util.TextRange(start, end))
            }
        } else {
            // Fallback: highlight full CTE if we can't find parentheses
            ranges.add(cte.element.textRange)
        }

        return ranges
    }

    override fun handleSelectedOption(editor: Editor, option: PopupOption) {
        executeSql(editor, option.sql)
    }

    private fun executeSql(editor: Editor, sql: String) {
        val project = editor.project ?: return
        val document = editor.document

        val runnable = Runnable {
            val insertionPoint = document.textLength

            WriteCommandAction.runWriteCommandAction(project) {
                document.insertString(insertionPoint, "\n$sql")
            }

            val offset = editor.caretModel.offset
            val start = insertionPoint
            val end = insertionPoint + sql.length + 1

            editor.caretModel.moveToOffset(end)
            editor.selectionModel.setSelection(start, end)

            val action = ActionManager.getInstance().getAction("Console.Jdbc.Execute")
            if (action != null) {
                ActionManager.getInstance().tryToExecute(
                    action,
                    null,
                    editor.component,
                    ActionPlaces.UNKNOWN,
                    true
                )
            }

            ApplicationManager.getApplication().invokeLater {
                WriteCommandAction.runWriteCommandAction(project) {
                    document.deleteString(start, document.textLength)
                }
                editor.selectionModel.removeSelection()
            }

            editor.caretModel.moveToOffset(offset)
        }

        ApplicationManager.getApplication().invokeLater(runnable)
    }
}