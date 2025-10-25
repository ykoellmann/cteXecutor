package com.ykoellmann.ctexecutor

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import com.intellij.sql.psi.SqlElementTypes

/**
 * Central SQL Dependency Analyzer
 * Analyzes SQL structure and recursively resolves all CTE dependencies
 */
class SqlDependencyAnalyzer(
    private val file: PsiFile,
    private val caretOffset: Int
) {
    /**
     * Complete analysis result with all information needed for execution and highlighting
     */
    data class AnalysisResult(
        val allCtes: List<CteEntry>,           // All CTEs in the WITH clause
        val requiredCtes: List<CteEntry>,      // CTEs needed for target query
        val targetQuery: PsiElement,           // The query element at cursor
        val fullSql: String,                   // Complete executable SQL
        val highlightRanges: List<TextRange>  // Ranges to highlight
    )

    /**
     * Represents a single CTE with all its information
     */
    data class CteEntry(
        val name: String,
        val element: PsiElement,
        val index: Int
    )

    /**
     * Main analysis entry point
     * Returns complete analysis result or null if no CTEs found
     */
    fun analyze(): AnalysisResult? {
        val elementAtCaret = file.findElementAt(caretOffset) ?: return null

        // Find the WITH clause
        val withClause = findWithClause(elementAtCaret) ?: return null

        // Extract all CTEs
        val allCtes = extractAllCtes(withClause)
        if (allCtes.isEmpty()) return null

        // Find target query at cursor (innermost, not outermost!)
        val targetQuery = findTargetQueryAtCursor(elementAtCaret, withClause)

        // Recursively find all required dependencies
        val requiredCteNames = findAllDependenciesRecursive(targetQuery, allCtes.map { it.name }.toSet())

        // Filter to only required CTEs, in order
        val requiredCtes = allCtes.filter { it.name in requiredCteNames }

        // Build SQL
        val sql = buildSql(requiredCtes, targetQuery)

        // Build highlight ranges
        val highlightRanges = buildHighlightRanges(requiredCtes, targetQuery, allCtes)

        return AnalysisResult(allCtes, requiredCtes, targetQuery, sql, highlightRanges)
    }

    /**
     * Finds the WITH clause containing the cursor
     */
    private fun findWithClause(element: PsiElement): PsiElement? {
        var current = element.parent

        while (current != null) {
            val nodeType = current.node?.elementType

            when (nodeType) {
                SqlElementTypes.SQL_WITH_CLAUSE -> return current
                SqlElementTypes.SQL_WITH_QUERY_EXPRESSION -> {
                    return current.children.firstOrNull {
                        it.elementType == SqlElementTypes.SQL_WITH_CLAUSE
                    }
                }
            }

            current = current.parent
        }

        return null
    }

    /**
     * Extracts all CTE definitions from WITH clause
     */
    private fun extractAllCtes(withClause: PsiElement): List<CteEntry> {
        val ctes = mutableListOf<CteEntry>()
        var index = 0

        for (child in withClause.children) {
            if (child.node?.elementType == SqlElementTypes.SQL_NAMED_QUERY_DEFINITION) {
                val name = child.firstChild?.text ?: continue
                ctes.add(CteEntry(name, child, index++))
            }
        }

        return ctes
    }

    /**
     * Finds the exact query element at cursor position.
     * Returns the FIRST (innermost) match, not the outermost!
     */
    private fun findTargetQueryAtCursor(elementAtCaret: PsiElement, withClause: PsiElement): PsiElement {
        var current: PsiElement? = elementAtCaret

        while (current != null) {
            val nodeType = current.node?.elementType

            when (nodeType) {
                SqlElementTypes.SQL_SELECT_STATEMENT -> return current
                SqlElementTypes.SQL_QUERY_EXPRESSION -> return current
                SqlElementTypes.SQL_NAMED_QUERY_DEFINITION -> return current
                SqlElementTypes.SQL_WITH_CLAUSE -> break
            }

            current = current.parent
        }

        // Fallback: main query after WITH
        return findMainQueryAfterWith(withClause) ?: withClause.parent ?: withClause
    }

    /**
     * Finds the main SELECT query after the WITH clause
     */
    private fun findMainQueryAfterWith(withClause: PsiElement): PsiElement? {
        val parent = withClause.parent ?: return null

        var sibling: PsiElement? = withClause.nextSibling
        while (sibling != null) {
            if (sibling.elementType == SqlElementTypes.SQL_SELECT_STATEMENT) {
                return sibling
            }
            sibling = sibling.nextSibling
        }

        for (child in parent.children) {
            if (child != withClause && child.elementType == SqlElementTypes.SQL_SELECT_STATEMENT) {
                return child
            }
        }

        return null
    }

    /**
     * RECURSIVE: Finds all CTE dependencies for a given query element
     */
    private fun findAllDependenciesRecursive(
        element: PsiElement,
        availableCtenames: Set<String>,
        visited: MutableSet<String> = mutableSetOf()
    ): Set<String> {
        val dependencies = mutableSetOf<String>()
        val references = findTableReferences(element)

        for (ref in references) {
            if (ref in availableCtenames && ref !in visited) {
                dependencies.add(ref)
                visited.add(ref)

                val cteElement = findCteByName(ref)
                if (cteElement != null) {
                    val transitiveDeps = findAllDependenciesRecursive(cteElement, availableCtenames, visited)
                    dependencies.addAll(transitiveDeps)
                }
            }
        }

        return dependencies
    }

    /**
     * Finds all table/CTE references in an element
     */
    private fun findTableReferences(element: PsiElement): Set<String> {
        val references = mutableSetOf<String>()

        fun collectReferences(e: PsiElement) {
            val nodeType = e.node?.elementType

            if (nodeType == SqlElementTypes.SQL_FROM_CLAUSE ||
                nodeType == SqlElementTypes.SQL_TABLE_REFERENCE ||
                nodeType == SqlElementTypes.SQL_JOIN_EXPRESSION) {
                collectIdentifiersRecursive(e, references)
            }

            for (child in e.children) {
                collectReferences(child)
            }
        }

        collectReferences(element)
        return references
    }

    /**
     * Collects identifier names recursively
     */
    private fun collectIdentifiersRecursive(element: PsiElement, result: MutableSet<String>) {
        if (element.elementType == SqlElementTypes.SQL_IDENTIFIER) {
            result.add(element.text)
        }

        for (child in element.children) {
            collectIdentifiersRecursive(child, result)
        }
    }

    /**
     * Finds a CTE element by its name
     */
    private fun findCteByName(name: String): PsiElement? {
        val elementAtCaret = file.findElementAt(caretOffset) ?: return null
        val withClause = findWithClause(elementAtCaret) ?: return null

        for (child in withClause.children) {
            if (child.node?.elementType == SqlElementTypes.SQL_NAMED_QUERY_DEFINITION) {
                if (child.firstChild?.text == name) {
                    return child
                }
            }
        }

        return null
    }

    /**
     * Builds highlight ranges
     * Logic depends on whether target is a CTE or not
     */
    private fun buildHighlightRanges(
        requiredCtes: List<CteEntry>,
        targetQuery: PsiElement,
        allCtes: List<CteEntry>
    ): List<TextRange> {
        val ranges = mutableListOf<TextRange>()

        if (requiredCtes.isEmpty()) {
            ranges.add(targetQuery.textRange)
            return ranges
        }

        val targetIsCte = targetQuery.node?.elementType == SqlElementTypes.SQL_NAMED_QUERY_DEFINITION

        if (targetIsCte) {
            // Target is a CTE - use CtePopupExecutor logic
            val targetCte = requiredCtes.find { it.element == targetQuery }
            val maxIndex = targetCte?.index ?: requiredCtes.maxOfOrNull { it.index } ?: -1

            for (cte in requiredCtes) {
                val cteRanges = getCteHighlightRanges(cte.element, cte.index, maxIndex, allCtes)
                ranges.addAll(cteRanges)
            }
        } else {
            // Target is NOT a CTE (subselect or main query) - highlight all CTEs completely
            for ((idx, cte) in requiredCtes.withIndex()) {
                if (idx == 0) {
                    // First CTE: include WITH keyword
                    val parentChildren = cte.element.parent.children
                    val startWithIndex = parentChildren.indexOfFirst { it.elementType == SqlElementTypes.SQL_WITH }

                    if (startWithIndex >= 0) {
                        val start = parentChildren[startWithIndex].textRange.startOffset
                        val end = cte.element.textRange.endOffset
                        ranges.add(TextRange(start, end))
                    } else {
                        ranges.add(cte.element.textRange)
                    }

                    // Add comma if more CTEs follow
                    if (requiredCtes.size > 1) {
                        addCommaRange(cte.element, ranges)
                    }
                } else {
                    // Other CTEs: full element + comma
                    ranges.add(cte.element.textRange)
                    if (idx < requiredCtes.size - 1) {
                        addCommaRange(cte.element, ranges)
                    }
                }
            }
        }

        // Add target query range
        ranges.add(targetQuery.textRange)

        return ranges
    }

    /**
     * Gets highlight ranges for a CTE (from CtePopupExecutor logic)
     */
    private fun getCteHighlightRanges(
        cteElement: PsiElement,
        index: Int,
        selectedIndex: Int,
        allCtes: List<CteEntry>
    ): List<TextRange> {
        val children = cteElement.children
        val elements = mutableListOf<PsiElement>()

        // First CTE with others: include WITH keyword
        if (index == 0 && selectedIndex > 0) {
            val parentChildren = cteElement.parent.children
            val startWithIndex = parentChildren.indexOfFirst { it.elementType == SqlElementTypes.SQL_WITH }
            val endWhitespaceIndex = parentChildren.indexOfFirst { it.elementType == SqlElementTypes.WHITE_SPACE }
            if (startWithIndex >= 0 && endWhitespaceIndex >= 0) {
                elements.addAll(parentChildren.filterIndexed { i, _ -> i in startWithIndex..endWhitespaceIndex })
            }
        }

        when {
            // Selected CTE: only inner content (body)
            index == selectedIndex -> {
                val startIndex = children.indexOfFirst { it.elementType == SqlElementTypes.SQL_LEFT_PAREN }
                val endIndex = children.indexOfLast { it.elementType == SqlElementTypes.SQL_RIGHT_PAREN }
                if (startIndex >= 0 && endIndex >= 0) {
                    elements.addAll(children.filterIndexed { i, _ -> i in (startIndex + 1) until endIndex })
                }
            }
            // Next CTE after selected: up to closing paren
            index + 1 == selectedIndex -> {
                val endIndex = children.indexOfLast { it.elementType == SqlElementTypes.SQL_RIGHT_PAREN }
                if (endIndex >= 0) {
                    elements.addAll(children.filterIndexed { i, _ -> i <= endIndex })
                }
            }
            // Other CTEs: full element + comma
            else -> {
                elements.add(cteElement)
                var curElement = cteElement.nextSibling
                while (curElement != null && curElement.elementType != SqlElementTypes.SQL_COMMA) {
                    curElement = curElement.nextSibling
                }
                if (curElement != null) {
                    elements.add(curElement)
                }
            }
        }

        if (elements.isEmpty()) {
            return listOf(cteElement.textRange)
        }

        val start = elements.minOf { it.textRange.startOffset }
        val end = elements.maxOf { it.textRange.endOffset }
        return listOf(TextRange(start, end))
    }

    /**
     * Helper to add comma range after a CTE
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

    /**
     * Builds the executable SQL
     */
    private fun buildSql(ctes: List<CteEntry>, targetQuery: PsiElement): String {
        val parts = mutableListOf<String>()

        if (ctes.isNotEmpty()) {
            parts.add("WITH")
            parts.add(ctes.joinToString(",\n") { it.element.text })
        }

        val queryText = when (targetQuery.node?.elementType) {
            SqlElementTypes.SQL_NAMED_QUERY_DEFINITION -> {
                val cteName = targetQuery.firstChild?.text ?: ""
                "SELECT * FROM $cteName"
            }
            else -> targetQuery.text
        }

        parts.add(queryText)

        val sql = parts.joinToString("\n")
        return if (sql.trim().endsWith(";")) sql else "$sql;"
    }
}