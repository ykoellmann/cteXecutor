package com.ykoellmann.ctexecutor

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import com.intellij.sql.psi.SqlElementTypes

/**
 * Recursive SQL Dependency Analyzer
 * Analyzes SQL from cursor position and recursively resolves all dependencies
 */
class SqlDependencyAnalyzer(
    private val file: PsiFile,
    private val caretOffset: Int
) {
    data class SqlContext(
        val ctes: List<CteInfo>,
        val targetQuery: PsiElement,
        val fullSql: String,
        val highlightRanges: List<TextRange> // Text ranges to highlight (like CtePopupExecutor)
    )

    data class CteInfo(
        val name: String,
        val element: PsiElement,
        val index: Int
    )

    /**
     * Main analysis entry point - finds dependencies recursively
     */
    fun analyze(): SqlContext? {
        val elementAtCaret = file.findElementAt(caretOffset) ?: return null

        // Find the WITH clause that contains all CTEs
        val withClause = findWithClause(elementAtCaret) ?: return null

        // Extract all available CTEs
        val allCtes = extractAllCtes(withClause)
        if (allCtes.isEmpty()) return null

        // Find the exact query element at cursor (FIRST match, not outermost!)
        val targetQuery = findTargetQueryAtCursor(elementAtCaret, withClause)

        // Recursively find all dependencies
        val requiredCteNames = findAllDependenciesRecursive(targetQuery, allCtes.map { it.name }.toSet())

        // Get the CTEs in correct order
        val requiredCtes = allCtes.filter { it.name in requiredCteNames }

        // Build SQL
        val sql = buildSql(requiredCtes, targetQuery)

        // Build highlight ranges (like CtePopupExecutor does it)
        val highlightRanges = buildHighlightRanges(requiredCtes, targetQuery)

        return SqlContext(requiredCtes, targetQuery, sql, highlightRanges)
    }

    /**
     * Finds the WITH clause containing the cursor
     * Same logic as CtePopupExecutor
     */
    private fun findWithClause(element: PsiElement): PsiElement? {
        var current = element.parent

        while (current != null) {
            val nodeType = current.node?.elementType

            when (nodeType) {
                SqlElementTypes.SQL_WITH_CLAUSE -> return current
                SqlElementTypes.SQL_WITH_QUERY_EXPRESSION -> {
                    // Get the actual WITH_CLAUSE child
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
    private fun extractAllCtes(withClause: PsiElement): List<CteInfo> {
        val ctes = mutableListOf<CteInfo>()
        var index = 0

        for (child in withClause.children) {
            if (child.node?.elementType == SqlElementTypes.SQL_NAMED_QUERY_DEFINITION) {
                val name = child.firstChild?.text ?: continue
                ctes.add(CteInfo(name, child, index++))
            }
        }

        return ctes
    }

    /**
     * Finds the exact query element at cursor position.
     * IMPORTANT: Returns the FIRST (innermost) meaningful query element, not the outermost!
     *
     * Example:
     * WITH y AS (...)
     * SELECT *, (SELECT 1 FROM y) FROM y
     *              ^cursor here
     *
     * Should return the inner "SELECT 1 FROM y", NOT the outer SELECT!
     */
    private fun findTargetQueryAtCursor(elementAtCaret: PsiElement, withClause: PsiElement): PsiElement {
        var current: PsiElement? = elementAtCaret

        while (current != null) {
            val nodeType = current.node?.elementType

            when (nodeType) {
                // Found a SELECT statement - STOP HERE! This is our target
                SqlElementTypes.SQL_SELECT_STATEMENT -> {
                    return current
                }
                // Found a query expression - could be a subquery, STOP HERE
                SqlElementTypes.SQL_QUERY_EXPRESSION -> {
                    return current
                }
                // Found a CTE definition - STOP HERE
                SqlElementTypes.SQL_NAMED_QUERY_DEFINITION -> {
                    return current
                }
                // Don't go past the WITH clause
                SqlElementTypes.SQL_WITH_CLAUSE -> {
                    break
                }
            }

            current = current.parent
        }

        // Fallback: return the main query after WITH clause
        return findMainQueryAfterWith(withClause) ?: withClause.parent ?: withClause
    }

    /**
     * Finds the main SELECT query after the WITH clause
     */
    private fun findMainQueryAfterWith(withClause: PsiElement): PsiElement? {
        val parent = withClause.parent ?: return null

        // Look for SELECT_STATEMENT siblings after the WITH clause
        var sibling: PsiElement? = withClause.nextSibling
        while (sibling != null) {
            if (sibling.elementType == SqlElementTypes.SQL_SELECT_STATEMENT) {
                return sibling
            }
            sibling = sibling.nextSibling
        }

        // Or check children of parent
        for (child in parent.children) {
            if (child != withClause && child.elementType == SqlElementTypes.SQL_SELECT_STATEMENT) {
                return child
            }
        }

        return null
    }

    /**
     * RECURSIVE: Finds all CTE dependencies for a given query element
     *
     * Algorithm:
     * 1. Find all table references in the current element
     * 2. For each reference that is a CTE name:
     *    - Add it to dependencies
     *    - Recursively analyze that CTE's body
     * 3. Return all dependencies (direct + transitive)
     */
    private fun findAllDependenciesRecursive(
        element: PsiElement,
        availableCtenames: Set<String>,
        visited: MutableSet<String> = mutableSetOf()
    ): Set<String> {
        val dependencies = mutableSetOf<String>()

        // Find all table/CTE references in this element
        val references = findTableReferences(element)

        for (ref in references) {
            // Check if this reference is actually a CTE
            if (ref in availableCtenames && ref !in visited) {
                dependencies.add(ref)
                visited.add(ref)

                // Find the CTE element and recursively analyze it
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
     * Finds all table/CTE references in an element (recursively searches the tree)
     */
    private fun findTableReferences(element: PsiElement): Set<String> {
        val references = mutableSetOf<String>()

        fun collectReferences(e: PsiElement) {
            val nodeType = e.node?.elementType

            // Look for table references in FROM clauses
            if (nodeType == SqlElementTypes.SQL_FROM_CLAUSE ||
                nodeType == SqlElementTypes.SQL_TABLE_REFERENCE) {

                // Find identifiers within
                for (child in e.children) {
                    collectIdentifiersRecursive(child, references)
                }
            }

            // Also check JOIN clauses
            if (nodeType == SqlElementTypes.SQL_JOIN_EXPRESSION) {
                collectIdentifiersRecursive(e, references)
            }

            // Recursively process children
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
     * Builds highlight ranges like CtePopupExecutor does
     * Uses the same logic to include WITH keyword, commas, etc.
     */
    private fun buildHighlightRanges(ctes: List<CteInfo>, targetQuery: PsiElement): List<TextRange> {
        val ranges = mutableListOf<TextRange>()

        if (ctes.isEmpty()) {
            // No CTEs, just highlight the target query
            ranges.add(targetQuery.textRange)
            return ranges
        }

        // Check if target is a CTE itself or something else (like main query/subselect)
        val targetIsCte = targetQuery.node?.elementType == SqlElementTypes.SQL_NAMED_QUERY_DEFINITION

        if (targetIsCte) {
            // Target is a CTE - use maxIndex logic like CtePopupExecutor
            val maxIndex = ctes.maxOfOrNull { it.index } ?: -1

            for (cte in ctes) {
                val cteRanges = getCteHighlightRanges(cte.element, cte.index, maxIndex)
                ranges.addAll(cteRanges)
            }
        } else {
            // Target is NOT a CTE (main query or subselect)
            // All required CTEs should be highlighted COMPLETELY (not just inner parts)
            // They are all "previous" CTEs, so we treat them all the same

            for ((idx, cte) in ctes.withIndex()) {
                if (idx == 0) {
                    // First CTE: include WITH keyword and the full CTE
                    val parentChildren = cte.element.parent.children
                    val startWithIndex = parentChildren.indexOfFirst { it.elementType == SqlElementTypes.SQL_WITH }
                    val endIndex = cte.element.textRange.endOffset

                    if (startWithIndex >= 0) {
                        val start = parentChildren[startWithIndex].textRange.startOffset
                        ranges.add(TextRange(start, endIndex))
                    } else {
                        ranges.add(cte.element.textRange)
                    }

                    // Add comma after first CTE if there are more
                    if (ctes.size > 1) {
                        var curElement = cte.element.nextSibling
                        while (curElement != null && curElement.elementType != SqlElementTypes.SQL_COMMA) {
                            curElement = curElement.nextSibling
                        }
                        if (curElement != null) {
                            ranges.add(curElement.textRange)
                        }
                    }
                } else {
                    // Other CTEs: full element + comma
                    ranges.add(cte.element.textRange)

                    // Add comma if not last
                    if (idx < ctes.size - 1) {
                        var curElement = cte.element.nextSibling
                        while (curElement != null && curElement.elementType != SqlElementTypes.SQL_COMMA) {
                            curElement = curElement.nextSibling
                        }
                        if (curElement != null) {
                            ranges.add(curElement.textRange)
                        }
                    }
                }
            }
        }

        // Add target query range
        ranges.add(targetQuery.textRange)

        return ranges
    }

    /**
     * Gets highlight ranges for a CTE (adapted from CtePopupExecutor.getCteElements)
     */
    private fun getCteHighlightRanges(cteElement: PsiElement, index: Int, selectedIndex: Int): List<TextRange> {
        val children = cteElement.children
        val elements = mutableListOf<PsiElement>()

        // Special case: if first CTE is selected and there are others, include the "WITH" keyword
        if (index == 0 && selectedIndex > 0) {
            val parentChildren = cteElement.parent.children
            val startWithIndex = parentChildren.indexOfFirst { it.elementType == SqlElementTypes.SQL_WITH }
            val endWhitespaceIndex = parentChildren.indexOfFirst { it.elementType == SqlElementTypes.WHITE_SPACE }
            if (startWithIndex >= 0 && endWhitespaceIndex >= 0) {
                elements.addAll(parentChildren.filterIndexed { i, _ -> i in startWithIndex..endWhitespaceIndex })
            }
        }

        when {
            // Selected CTE: include content inside parentheses (body)
            index == selectedIndex -> {
                val startIndex = children.indexOfFirst { it.elementType == SqlElementTypes.SQL_LEFT_PAREN }
                val endIndex = children.indexOfLast { it.elementType == SqlElementTypes.SQL_RIGHT_PAREN }
                if (startIndex >= 0 && endIndex >= 0) {
                    elements.addAll(children.filterIndexed { i, _ -> i in (startIndex + 1) until endIndex })
                }
            }
            // Next CTE after selected: include up to the closing parenthesis
            index + 1 == selectedIndex -> {
                val endIndex = children.indexOfLast { it.elementType == SqlElementTypes.SQL_RIGHT_PAREN }
                if (endIndex >= 0) {
                    elements.addAll(children.filterIndexed { i, _ -> i <= endIndex })
                }
            }
            // Other CTEs: include the full element plus its following comma
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

        // Convert elements to text ranges
        if (elements.isEmpty()) {
            return listOf(cteElement.textRange)
        }

        val start = elements.minOf { it.textRange.startOffset }
        val end = elements.maxOf { it.textRange.endOffset }
        return listOf(TextRange(start, end))
    }

    /**
     * Builds the executable SQL from required CTEs and target query
     */
    private fun buildSql(ctes: List<CteInfo>, targetQuery: PsiElement): String {
        val parts = mutableListOf<String>()

        if (ctes.isNotEmpty()) {
            parts.add("WITH")
            parts.add(ctes.joinToString(",\n") { it.element.text })
        }

        // Extract the actual query text
        val queryText = when (targetQuery.node?.elementType) {
            SqlElementTypes.SQL_NAMED_QUERY_DEFINITION -> {
                // If targeting a CTE, select from it
                val cteName = targetQuery.firstChild?.text ?: ""
                "SELECT * FROM $cteName"
            }
            SqlElementTypes.SQL_SELECT_STATEMENT -> {
                targetQuery.text
            }
            else -> targetQuery.text
        }

        parts.add(queryText)

        val sql = parts.joinToString("\n")
        return if (sql.trim().endsWith(";")) sql else "$sql;"
    }
}