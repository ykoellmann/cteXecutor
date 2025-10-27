package com.ykoellmann.ctexecutor.analyzer

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import com.intellij.sql.psi.SqlElementTypes

/**
 * Central SQL and Highlighting Generator
 * Builds SQL and highlight ranges from PSI elements
 */
object SqlBuilder {

    /**
     * Result of SQL/Highlighting generation
     */
    data class BuildResult(
        val sql: String,
        val highlightRanges: List<TextRange>
    )

    /**
     * Highlighting mode determines how CTEs are highlighted
     */
    enum class HighlightMode {
        /**
         * Highlights full CTE definitions
         * Used for copying or showing all dependencies
         */
        FULL_CTE,

        /**
         * Highlights only the inner SELECT of target CTE
         * Used for progressive CTE selection (RunAction)
         */
        PROGRESSIVE_CTE,

        /**
         * Highlights CTE body for single selection
         * Used for ExecuteFromHereAction individual CTEs
         */
        SINGLE_CTE_INNER,

        /**
         * Highlights full dependency CTEs + inner SELECT of target CTE
         * Used for ExecuteFromHereAction when showing dependencies with target
         * Dependencies are shown completely, target CTE only shows inner SELECT
         */
        DEPENDENCIES_WITH_TARGET_INNER
    }

    /**
     * Builds SQL and highlighting from PSI elements
     *
     * @param ctes The CTEs to include (in order)
     * @param targetQuery The target query element
     * @param allCtes All available CTEs (for context)
     * @param mode Highlighting mode
     * @return BuildResult with SQL and highlight ranges
     */
    fun build(
        ctes: List<SqlDependencyAnalyzer.CteEntry>,
        targetQuery: PsiElement,
        allCtes: List<SqlDependencyAnalyzer.CteEntry> = ctes,
        mode: HighlightMode = HighlightMode.FULL_CTE
    ): BuildResult {
        val sql = buildSql(ctes, targetQuery, mode)
        val highlightRanges = buildHighlightRanges(ctes, targetQuery, allCtes, mode)
        return BuildResult(sql, highlightRanges)
    }

    /**
     * Builds executable SQL from CTEs and target query
     */
    private fun buildSql(
        ctes: List<SqlDependencyAnalyzer.CteEntry>,
        targetQuery: PsiElement,
        mode: HighlightMode
    ): String {
        val parts = mutableListOf<String>()

        // For DEPENDENCIES_WITH_TARGET_INNER mode:
        // - All CTEs except the last are dependencies (go into WITH clause)
        // - The last CTE's inner SELECT is extracted and used as the main query
        if (mode == HighlightMode.DEPENDENCIES_WITH_TARGET_INNER &&
            ctes.isNotEmpty() &&
            targetQuery.node?.elementType == SqlElementTypes.SQL_NAMED_QUERY_DEFINITION) {

            // Dependencies: all CTEs except the target
            val dependencies = ctes.dropLast(1)

            if (dependencies.isNotEmpty()) {
                parts.add("WITH")
                parts.add(dependencies.joinToString(",\n") { it.element.text })
            }

            // Extract inner SELECT from target CTE
            val innerSelect = extractInnerSelect(targetQuery)
            if (innerSelect != null) {
                parts.add(innerSelect)
            } else {
                // Fallback: use SELECT * FROM if we can't extract inner SELECT
                val cteName = targetQuery.firstChild?.text ?: ""
                parts.add("SELECT * FROM $cteName")
            }
        } else {
            // Standard modes
            // Add WITH clause if CTEs present
            if (ctes.isNotEmpty()) {
                parts.add("WITH")
                parts.add(ctes.joinToString(",\n") { it.element.text })
            }

            // Add query part
            val queryText = when (targetQuery.node?.elementType) {
                SqlElementTypes.SQL_NAMED_QUERY_DEFINITION -> {
                    // Target is a CTE - select from it
                    val cteName = targetQuery.firstChild?.text ?: ""
                    "SELECT * FROM $cteName"
                }
                else -> {
                    // Target is a SELECT or other query - use as is
                    targetQuery.text
                }
            }

            parts.add(queryText)
        }

        val sql = parts.joinToString("\n")
        return if (sql.trim().endsWith(";")) sql else "$sql;"
    }

    /**
     * Extracts the inner SELECT from a CTE definition (content between parentheses)
     */
    private fun extractInnerSelect(cteElement: PsiElement): String? {
        val children = cteElement.children

        val startIndex = children.indexOfFirst { it.elementType == SqlElementTypes.SQL_LEFT_PAREN }
        val endIndex = children.indexOfLast { it.elementType == SqlElementTypes.SQL_RIGHT_PAREN }

        if (startIndex >= 0 && endIndex >= 0 && startIndex + 1 < endIndex) {
            val innerElements = children.filterIndexed { i, _ -> i in (startIndex + 1) until endIndex }
            if (innerElements.isNotEmpty()) {
                return innerElements.joinToString("") { it.text }
            }
        }

        return null
    }

    /**
     * Builds highlight ranges based on mode
     */
    private fun buildHighlightRanges(
        ctes: List<SqlDependencyAnalyzer.CteEntry>,
        targetQuery: PsiElement,
        allCtes: List<SqlDependencyAnalyzer.CteEntry>,
        mode: HighlightMode
    ): List<TextRange> {
        if (ctes.isEmpty()) {
            return listOf(targetQuery.textRange)
        }

        return when (mode) {
            HighlightMode.FULL_CTE -> buildFullCteRanges(ctes, targetQuery)
            HighlightMode.PROGRESSIVE_CTE -> buildProgressiveCteRanges(ctes, targetQuery, allCtes)
            HighlightMode.SINGLE_CTE_INNER -> buildSingleCteInnerRanges(ctes)
            HighlightMode.DEPENDENCIES_WITH_TARGET_INNER -> buildDependenciesWithTargetInnerRanges(ctes, targetQuery)
        }
    }

    /**
     * Builds ranges that highlight full CTE definitions
     */
    private fun buildFullCteRanges(
        ctes: List<SqlDependencyAnalyzer.CteEntry>,
        targetQuery: PsiElement
    ): List<TextRange> {
        val ranges = mutableListOf<TextRange>()

        for ((idx, cte) in ctes.withIndex()) {
            if (idx == 0) {
                // First CTE: include WITH keyword if available
                val withKeyword = findWithKeyword(cte.element)
                if (withKeyword != null) {
                    ranges.add(TextRange(withKeyword.textRange.startOffset, cte.element.textRange.endOffset))
                } else {
                    ranges.add(cte.element.textRange)
                }

                // Add comma after first CTE if more follow
                if (ctes.size > 1) {
                    addCommaRange(cte.element, ranges)
                }
            } else {
                // Other CTEs: full element
                ranges.add(cte.element.textRange)
                // Add comma if not last
                if (idx < ctes.size - 1) {
                    addCommaRange(cte.element, ranges)
                }
            }
        }

        // Add target query range
        ranges.add(targetQuery.textRange)

        return ranges
    }

    /**
     * Builds ranges for progressive CTE highlighting
     * Shows CTEs progressively up to a selected one
     */
    private fun buildProgressiveCteRanges(
        ctes: List<SqlDependencyAnalyzer.CteEntry>,
        targetQuery: PsiElement,
        allCtes: List<SqlDependencyAnalyzer.CteEntry>
    ): List<TextRange> {
        val ranges = mutableListOf<TextRange>()

        // Find target CTE if target is a CTE
        val targetCte = if (targetQuery.node?.elementType == SqlElementTypes.SQL_NAMED_QUERY_DEFINITION) {
            ctes.find { it.element == targetQuery }
        } else {
            null
        }

        val maxIndex = targetCte?.index ?: ctes.maxOfOrNull { it.index } ?: -1

        for ((idx, cte) in ctes.withIndex()) {
            val children = cte.element.children

            // First CTE: include WITH keyword
            if (idx == 0 && ctes.size > 1) {
                val withKeyword = findWithKeyword(cte.element)
                if (withKeyword != null) {
                    val whitespace = withKeyword.nextSibling
                    if (whitespace != null && whitespace.elementType == SqlElementTypes.WHITE_SPACE) {
                        ranges.add(TextRange(withKeyword.textRange.startOffset, whitespace.textRange.endOffset))
                    } else {
                        ranges.add(withKeyword.textRange)
                    }
                }
            }

            when {
                // Target CTE: only inner part (between parentheses)
                cte.index == maxIndex -> {
                    val innerRange = getInnerCteRange(cte.element)
                    if (innerRange != null) {
                        ranges.add(innerRange)
                    } else {
                        ranges.add(cte.element.textRange)
                    }
                }
                // CTE right before target: up to closing paren
                cte.index + 1 == maxIndex -> {
                    val endIndex = children.indexOfLast { it.elementType == SqlElementTypes.SQL_RIGHT_PAREN }
                    if (endIndex >= 0) {
                        val elements = children.filterIndexed { i, _ -> i <= endIndex }
                        val start = elements.minOf { it.textRange.startOffset }
                        val end = elements.maxOf { it.textRange.endOffset }
                        ranges.add(TextRange(start, end))
                    } else {
                        ranges.add(cte.element.textRange)
                    }
                }
                // Other CTEs: full element + comma
                else -> {
                    ranges.add(cte.element.textRange)
                    addCommaRange(cte.element, ranges)
                }
            }
        }

        return ranges
    }

    /**
     * Builds ranges for single CTE inner content
     */
    private fun buildSingleCteInnerRanges(
        ctes: List<SqlDependencyAnalyzer.CteEntry>
    ): List<TextRange> {
        val ranges = mutableListOf<TextRange>()

        for (cte in ctes) {
            val innerRange = getInnerCteRange(cte.element)
            if (innerRange != null) {
                ranges.add(innerRange)
            } else {
                ranges.add(cte.element.textRange)
            }
        }

        return ranges
    }

    /**
     * Gets the inner range of a CTE (content between parentheses)
     */
    private fun getInnerCteRange(cteElement: PsiElement): TextRange? {
        val children = cteElement.children

        val startIndex = children.indexOfFirst { it.elementType == SqlElementTypes.SQL_LEFT_PAREN }
        val endIndex = children.indexOfLast { it.elementType == SqlElementTypes.SQL_RIGHT_PAREN }

        if (startIndex >= 0 && endIndex >= 0 && startIndex + 1 < endIndex) {
            val innerElements = children.filterIndexed { i, _ -> i in (startIndex + 1) until endIndex }
            if (innerElements.isNotEmpty()) {
                val start = innerElements.minOf { it.textRange.startOffset }
                val end = innerElements.maxOf { it.textRange.endOffset }
                return TextRange(start, end)
            }
        }

        return null
    }

    /**
     * Builds ranges for dependencies (full) + target CTE (inner only)
     * Used for ExecuteFromHereAction when showing CTEs with dependencies
     */
    private fun buildDependenciesWithTargetInnerRanges(
        ctes: List<SqlDependencyAnalyzer.CteEntry>,
        targetQuery: PsiElement
    ): List<TextRange> {
        val ranges = mutableListOf<TextRange>()

        if (ctes.isEmpty()) {
            return ranges
        }

        // Dependencies: all CTEs except the last (show completely)
        val dependencies = ctes.dropLast(1)

        for ((idx, cte) in dependencies.withIndex()) {
            if (idx == 0) {
                // First CTE: include WITH keyword if available
                val withKeyword = findWithKeyword(cte.element)
                if (withKeyword != null) {
                    ranges.add(TextRange(withKeyword.textRange.startOffset, cte.element.textRange.endOffset))
                } else {
                    ranges.add(cte.element.textRange)
                }

                // Add comma after first CTE if more follow
                if (dependencies.size > 1) {
                    addCommaRange(cte.element, ranges)
                }
            } else {
                // Other dependency CTEs: full element
                ranges.add(cte.element.textRange)
                // Add comma if not last dependency
                if (idx < dependencies.size - 1) {
                    addCommaRange(cte.element, ranges)
                }
            }
        }

        // Target CTE: only inner SELECT (if it's a CTE)
        if (targetQuery.node?.elementType == SqlElementTypes.SQL_NAMED_QUERY_DEFINITION) {
            val innerRange = getInnerCteRange(targetQuery)
            if (innerRange != null) {
                ranges.add(innerRange)
            } else {
                // Fallback: full element if we can't find inner range
                ranges.add(targetQuery.textRange)
            }
        } else {
            // Not a CTE, just add the full range
            ranges.add(targetQuery.textRange)
        }

        return ranges
    }

    /**
     * Finds the WITH keyword before a CTE
     */
    private fun findWithKeyword(cteElement: PsiElement): PsiElement? {
        val parentChildren = cteElement.parent?.children ?: return null
        return parentChildren.firstOrNull { it.elementType == SqlElementTypes.SQL_WITH }
    }

    /**
     * Adds the comma range after a CTE element
     */
    private fun addCommaRange(cteElement: PsiElement, ranges: MutableList<TextRange>) {
        var curElement = cteElement.nextSibling
        while (curElement != null && curElement.elementType != SqlElementTypes.SQL_COMMA) {
            curElement = curElement.nextSibling
        }
        if (curElement != null) {
            ranges.add(curElement.textRange)
        }
    }
}
