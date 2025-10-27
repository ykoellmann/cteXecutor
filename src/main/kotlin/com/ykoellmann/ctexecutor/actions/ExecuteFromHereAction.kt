package com.ykoellmann.ctexecutor.actions

import com.ykoellmann.ctexecutor.analyzer.SqlDependencyAnalyzer
import com.ykoellmann.ctexecutor.analyzer.SqlBuilder
import com.intellij.openapi.editor.Editor
import com.intellij.sql.psi.SqlElementTypes

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

        // Options 1-N: Each required CTE WITH ALL ITS DEPENDENCIES
        for (i in result.requiredCtes.indices) {
            val cte = result.requiredCtes[i]

            // Get all CTEs up to and including this one (dependencies in order)
            val ctesUpToHere = result.requiredCtes.subList(0, i + 1)

            // Use SqlBuilder to generate SQL and highlighting
            // DEPENDENCIES_WITH_TARGET_INNER mode:
            // - Dependencies (all except target) are shown completely with WITH, names, parentheses
            // - Target CTE shows only inner SELECT
            // - SQL generated: WITH [dependencies] + [target's inner SELECT]
            val buildResult = SqlBuilder.build(
                ctes = ctesUpToHere,
                targetQuery = cte.element,
                allCtes = result.allCtes,
                mode = SqlBuilder.HighlightMode.DEPENDENCIES_WITH_TARGET_INNER
            )

            options.add(PopupOption(
                displayName = buildDisplayName(cte, ctesUpToHere.size - 1),
                sql = buildResult.sql,
                highlightRanges = buildResult.highlightRanges
            ))
        }

        // Last Option: Current query at cursor (with all dependencies) - AT BOTTOM
        val currentQueryName = if (result.targetQuery.node?.elementType == SqlElementTypes.SQL_NAMED_QUERY_DEFINITION) {
            result.targetQuery.firstChild?.text ?: "Current CTE"
        } else {
            "Current Query"
        }

        // Use SqlBuilder for the full query with all dependencies
        val fullBuildResult = SqlBuilder.build(
            ctes = result.requiredCtes,
            targetQuery = result.targetQuery,
            allCtes = result.allCtes,
            mode = SqlBuilder.HighlightMode.FULL_CTE
        )

        options.add(PopupOption(
            displayName = "$currentQueryName (${result.requiredCtes.size} CTE${if (result.requiredCtes.size != 1) "s" else ""})",
            sql = fullBuildResult.sql,
            highlightRanges = fullBuildResult.highlightRanges
        ))

        return options
    }

    /**
     * Builds display name showing CTE name and number of dependencies
     */
    private fun buildDisplayName(cte: SqlDependencyAnalyzer.CteEntry, dependencyCount: Int): String {
        return if (dependencyCount > 0) {
            "${cte.name} (+ $dependencyCount CTE${if (dependencyCount != 1) "s" else ""})"
        } else {
            cte.name
        }
    }

    override fun handleSelectedOption(editor: Editor, option: PopupOption) {
        SqlExecutor.executeSqlSeamlessly(editor, option.sql)
    }
}
