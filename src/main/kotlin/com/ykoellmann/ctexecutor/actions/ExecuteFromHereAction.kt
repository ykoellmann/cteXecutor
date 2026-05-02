package com.ykoellmann.ctexecutor.actions

import com.ykoellmann.ctexecutor.analyzer.SqlAnalyzer
import com.ykoellmann.ctexecutor.analyzer.SqlBuilder
import com.intellij.openapi.editor.Editor

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
    override fun buildPopupOptions(context: SqlAnalyzer.SqlContext): List<PopupOption> {
        val options = mutableListOf<PopupOption>()

        for (i in context.requiredCtes.indices) {
            val cte = context.requiredCtes[i]
            val ctesUpToHere = context.requiredCtes.subList(0, i + 1)
            val buildResult = SqlBuilder.build(
                ctes = ctesUpToHere,
                targetQuery = cte.element,
                allCtes = context.allCtes,
                mode = SqlBuilder.HighlightMode.DEPENDENCIES_WITH_TARGET_INNER
            )
            options.add(PopupOption(
                displayName = buildDisplayName(cte, ctesUpToHere.size - 1),
                sql = buildResult.sql,
                highlightRanges = buildResult.highlightRanges
            ))
        }

        val activeCte = (context.cursorScope as? SqlAnalyzer.CursorScope.InsideCte)?.cte
        val currentQueryName = activeCte?.name ?: "Current Query"

        // When inside a CTE: extract its inner body as the final query (deps go into WITH clause).
        // When in the main query: use FULL_CTE which preserves the SELECT as-is.
        val (finalCtes, finalTarget, finalMode) = if (activeCte != null) {
            Triple(
                context.requiredCtes + listOf(activeCte),
                activeCte.element,
                SqlBuilder.HighlightMode.DEPENDENCIES_WITH_TARGET_INNER
            )
        } else {
            Triple(
                context.requiredCtes,
                context.targetQuery,
                SqlBuilder.HighlightMode.FULL_CTE
            )
        }

        val fullBuildResult = SqlBuilder.build(
            ctes = finalCtes,
            targetQuery = finalTarget,
            allCtes = context.allCtes,
            mode = finalMode
        )
        options.add(PopupOption(
            displayName = "$currentQueryName (${context.requiredCtes.size} CTE${if (context.requiredCtes.size != 1) "s" else ""})",
            sql = fullBuildResult.sql,
            highlightRanges = fullBuildResult.highlightRanges
        ))

        return options
    }

    private fun buildDisplayName(cte: SqlAnalyzer.CteEntry, dependencyCount: Int): String =
        if (dependencyCount > 0) "${cte.name} (+ $dependencyCount CTE${if (dependencyCount != 1) "s" else ""})"
        else cte.name

    override fun handleSelectedOption(editor: Editor, option: PopupOption) {
        SqlExecutor.executeSqlSeamlessly(editor, option.sql)
    }
}
