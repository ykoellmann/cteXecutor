package com.ykoellmann.ctexecutor.analyzer

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import com.intellij.sql.psi.SqlElementTypes

/**
 * Central SQL Dependency Analyzer
 * Analyzes SQL structure and recursively resolves all CTE dependencies
 *
 * This analyzer only performs PSI analysis - it does NOT generate SQL or highlighting.
 * Use SqlBuilder to generate SQL and highlighting from the analysis results.
 */
class SqlDependencyAnalyzer(
    private val file: PsiFile,
    private val caretOffset: Int
) {
    /**
     * Analysis result containing only PSI elements
     * Use SqlBuilder to generate SQL and highlighting from these elements
     */
    data class AnalysisResult(
        val allCtes: List<CteEntry>,           // All CTEs in the WITH clause
        val requiredCtes: List<CteEntry>,      // CTEs needed for target query
        val targetQuery: PsiElement,           // The query element at cursor
        val withClause: PsiElement?            // The WITH clause element (for context)
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
     * Returns analysis result with PSI elements only (no SQL/highlighting generation)
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

        return AnalysisResult(allCtes, requiredCtes, targetQuery, withClause)
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

}