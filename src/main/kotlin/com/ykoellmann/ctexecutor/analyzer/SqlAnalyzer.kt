package com.ykoellmann.ctexecutor.analyzer

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.elementType
import com.intellij.sql.psi.SqlElementTypes

class SqlAnalyzer(
    private val file: PsiFile,
    private val caretOffset: Int
) {

    data class CteEntry(
        val name: String,
        val element: PsiElement,
        val index: Int
    )

    sealed class CursorScope {
        data class InsideCte(val cte: CteEntry) : CursorScope()
        data class InsideSubselect(
            val subselect: PsiElement,
            val containingCte: CteEntry?
        ) : CursorScope()
        data class InsideUnionBranch(
            val branch: PsiElement,
            val unionBody: PsiElement,
            val containingCte: CteEntry?
        ) : CursorScope()
        data class InsideMainQuery(val element: PsiElement) : CursorScope()
    }

    data class SqlContext(
        val allCtes: List<CteEntry>,
        val dependencyGraph: Map<String, Set<String>>,
        val cursorScope: CursorScope,
        val mainQuery: PsiElement?,
        val withClause: PsiElement
    ) {
        /** All transitive dependencies of a CTE in definition order. Does NOT include the CTE itself. */
        fun getTransitiveDependencies(cteName: String): List<CteEntry> {
            val nameKey = cteName.lowercase()
            val visited = mutableSetOf<String>()
            fun dfs(name: String) {
                if (name in visited) return
                visited.add(name)
                dependencyGraph[name]?.forEach { dfs(it) }
            }
            dependencyGraph[nameKey]?.forEach { dfs(it) }
            return allCtes.filter { it.name.lowercase() in visited }
        }

        /** All CTEs transitively required by the main query, in definition order. */
        fun getMainQueryDependencies(): List<CteEntry> {
            val mainQ = mainQuery ?: return emptyList()
            val cteNameSet = allCtes.map { it.name.lowercase() }.toSet()
            val refs = mutableSetOf<String>()
            collectTableRefs(mainQ, refs)
            val visited = mutableSetOf<String>()
            fun dfs(name: String) {
                val key = name.lowercase()
                if (key in visited || key !in cteNameSet) return
                visited.add(key)
                dependencyGraph[key]?.forEach { dfs(it) }
            }
            refs.forEach { dfs(it) }
            return allCtes.filter { it.name.lowercase() in visited }
        }

        /**
         * All CTEs required by an arbitrary PSI element (e.g. a UNION branch or subselect).
         * Unlike getTransitiveDependencies, directly referenced CTEs ARE included because
         * they belong in the WITH clause, not as the main query.
         */
        fun getRequiredCtesForElement(element: PsiElement): List<CteEntry> {
            val cteNameSet = allCtes.map { it.name.lowercase() }.toSet()
            val refs = mutableSetOf<String>()
            collectTableRefs(element, refs)
            val visited = mutableSetOf<String>()
            fun dfs(name: String) {
                val key = name.lowercase()
                if (key in visited || key !in cteNameSet) return
                visited.add(key)
                dependencyGraph[key]?.forEach { dfs(it) }
            }
            refs.forEach { dfs(it) }
            return allCtes.filter { it.name.lowercase() in visited }
        }

        // Backward-compatible accessors used by existing action code

        val targetQuery: PsiElement
            get() = when (val s = cursorScope) {
                is CursorScope.InsideCte -> s.cte.element
                is CursorScope.InsideMainQuery -> s.element
                is CursorScope.InsideSubselect -> s.subselect
                is CursorScope.InsideUnionBranch -> s.branch
            }

        val requiredCtes: List<CteEntry>
            get() = when (val s = cursorScope) {
                is CursorScope.InsideCte -> getTransitiveDependencies(s.cte.name)
                is CursorScope.InsideMainQuery -> getMainQueryDependencies()
                is CursorScope.InsideSubselect -> getRequiredCtesForElement(s.subselect)
                is CursorScope.InsideUnionBranch -> getRequiredCtesForElement(s.branch)
            }
    }

    fun analyze(): SqlContext? {
        val element = findMeaningfulElementAt() ?: return null
        val withClause = findWithClause(element) ?: return null
        val allCtes = extractAllCtes(withClause)
        if (allCtes.isEmpty()) return null
        val mainQuery = findMainQuery(withClause)
        val dependencyGraph = buildDependencyGraph(allCtes)
        val cursorScope = locateCursor(element, withClause, allCtes, mainQuery)
        return SqlContext(allCtes, dependencyGraph, cursorScope, mainQuery, withClause)
    }

    private fun findMeaningfulElementAt(): PsiElement? {
        val exact = file.findElementAt(caretOffset)
        if (exact != null && !isAmbiguousElement(exact)) return exact
        if (caretOffset > 0) {
            val prev = file.findElementAt(caretOffset - 1)
            if (prev != null && !isAmbiguousElement(prev)) return prev
        }
        return exact
    }

    private fun isAmbiguousElement(element: PsiElement): Boolean =
        element is PsiWhiteSpace ||
        element.elementType == SqlElementTypes.SQL_RIGHT_PAREN ||
        element.elementType == SqlElementTypes.SQL_LEFT_PAREN ||
        element.elementType == SqlElementTypes.SQL_COMMA

    private fun findWithClause(element: PsiElement): PsiElement? {
        var current: PsiElement? = element
        while (current != null) {
            when (current.elementType) {
                SqlElementTypes.SQL_WITH_CLAUSE -> return current
                SqlElementTypes.SQL_WITH_QUERY_EXPRESSION ->
                    return current.children.firstOrNull {
                        it.elementType == SqlElementTypes.SQL_WITH_CLAUSE
                    }
            }
            current = current.parent
        }
        return null
    }

    private fun extractAllCtes(withClause: PsiElement): List<CteEntry> {
        var index = 0
        return withClause.children
            .filter { it.elementType == SqlElementTypes.SQL_NAMED_QUERY_DEFINITION }
            .mapNotNull { child ->
                val name = extractCteName(child) ?: return@mapNotNull null
                CteEntry(name, child, index++)
            }
    }

    private fun extractCteName(cteElement: PsiElement): String? =
        cteElement.children.firstOrNull {
            it.elementType == SqlElementTypes.SQL_IDENTIFIER
        }?.text

    private fun findMainQuery(withClause: PsiElement): PsiElement? {
        var sibling = withClause.nextSibling
        while (sibling != null) {
            if (sibling.elementType == SqlElementTypes.SQL_SELECT_STATEMENT ||
                sibling.elementType == SqlElementTypes.SQL_QUERY_EXPRESSION) return sibling
            sibling = sibling.nextSibling
        }
        return withClause.parent?.children
            ?.filter { it != withClause }
            ?.firstOrNull {
                it.elementType == SqlElementTypes.SQL_SELECT_STATEMENT ||
                it.elementType == SqlElementTypes.SQL_QUERY_EXPRESSION
            }
    }

    /**
     * Builds the full dependency graph for all CTEs upfront.
     * Walks all content between each CTE's parentheses, so UNION branches are fully covered.
     * Keys and values are lowercase for case-insensitive resolution.
     * Self-references are excluded (handles recursive CTEs).
     */
    private fun buildDependencyGraph(allCtes: List<CteEntry>): Map<String, Set<String>> {
        val cteNameSet = allCtes.map { it.name.lowercase() }.toSet()
        return allCtes.associate { cte ->
            val refs = collectBodyReferences(cte.element)
            val deps = refs
                .map { it.lowercase() }
                .filter { it in cteNameSet && it != cte.name.lowercase() }
                .toSet()
            cte.name.lowercase() to deps
        }
    }

    /**
     * Collects table references from ALL content between a CTE's parentheses.
     * Iterates every sibling between the parens so UNION branches are all covered.
     */
    private fun collectBodyReferences(cteElement: PsiElement): Set<String> {
        val children = cteElement.children
        val leftIdx = children.indexOfFirst { it.elementType == SqlElementTypes.SQL_LEFT_PAREN }
        val rightIdx = children.indexOfLast { it.elementType == SqlElementTypes.SQL_RIGHT_PAREN }
        if (leftIdx < 0 || rightIdx <= leftIdx) return emptySet()
        val refs = mutableSetOf<String>()
        for (i in (leftIdx + 1) until rightIdx) {
            collectTableRefs(children[i], refs)
        }
        return refs
    }

    /**
     * Determines cursor scope by walking upward from the element.
     * Tracks the innermost SELECT_STATEMENT encountered and classifies it once a
     * CTE boundary or tree root is reached.
     */
    private fun locateCursor(
        element: PsiElement,
        withClause: PsiElement,
        allCtes: List<CteEntry>,
        mainQuery: PsiElement?
    ): CursorScope {
        var current: PsiElement? = element
        var innermostSelect: PsiElement? = null

        while (current != null) {
            when (current.elementType) {
                SqlElementTypes.SQL_SELECT_STATEMENT,
                SqlElementTypes.SQL_QUERY_EXPRESSION -> {
                    if (innermostSelect == null) innermostSelect = current
                }
                SqlElementTypes.SQL_NAMED_QUERY_DEFINITION -> {
                    val name = extractCteName(current)?.lowercase()
                    val cte = allCtes.firstOrNull { it.name.lowercase() == name }
                    if (cte != null) return classifyCteScope(innermostSelect, current, cte)
                }
                SqlElementTypes.SQL_WITH_CLAUSE -> break
            }
            current = current.parent
        }

        return classifyOuterScope(innermostSelect, mainQuery, withClause)
    }

    /** Classifies cursor position when inside a known CTE definition. */
    private fun classifyCteScope(
        innermostSelect: PsiElement?,
        cteElement: PsiElement,
        cte: CteEntry
    ): CursorScope {
        innermostSelect ?: return CursorScope.InsideCte(cte)

        if (!isDirectCteBodySelect(innermostSelect, cteElement)) {
            return CursorScope.InsideSubselect(innermostSelect, cte)
        }

        val parent = innermostSelect.parent ?: return CursorScope.InsideCte(cte)
        if (isUnionContainer(parent)) {
            return CursorScope.InsideUnionBranch(innermostSelect, parent, cte)
        }

        return CursorScope.InsideCte(cte)
    }

    /** Classifies cursor position when outside any CTE definition. */
    private fun classifyOuterScope(
        innermostSelect: PsiElement?,
        mainQuery: PsiElement?,
        withClause: PsiElement
    ): CursorScope {
        if (innermostSelect != null) {
            val parent = innermostSelect.parent
            if (parent != null && isUnionContainer(parent)) {
                return CursorScope.InsideUnionBranch(innermostSelect, parent, null)
            }
            if (innermostSelect != mainQuery) {
                return CursorScope.InsideSubselect(innermostSelect, null)
            }
            return CursorScope.InsideMainQuery(innermostSelect)
        }
        return CursorScope.InsideMainQuery(mainQuery ?: withClause.parent ?: withClause)
    }

    /**
     * Returns true if [select] is a direct body SELECT of [cteElement] — meaning no other
     * SQL_SELECT_STATEMENT exists between [select] and [cteElement] in the parent chain.
     * A UNION branch qualifies as "direct" since it is part of the CTE body.
     * A subselect nested inside a FROM or WHERE clause does not.
     */
    private fun isDirectCteBodySelect(select: PsiElement, cteElement: PsiElement): Boolean {
        var current = select.parent
        while (current != null && current != cteElement) {
            if (current.elementType == SqlElementTypes.SQL_SELECT_STATEMENT) return false
            current = current.parent
        }
        return current == cteElement
    }

    /**
     * Returns true if [element] has more than one SQL_SELECT_STATEMENT direct child,
     * indicating it is a UNION/INTERSECT/EXCEPT container.
     */
    private fun isUnionContainer(element: PsiElement): Boolean =
        element.children.count { it.elementType == SqlElementTypes.SQL_SELECT_STATEMENT } > 1
}

/**
 * Recursively collects the first (unqualified) identifier from each SQL_TABLE_REFERENCE
 * in the given element tree. Covers all FROM, JOIN, subselect, and UNION branches.
 */
internal fun collectTableRefs(element: PsiElement, result: MutableSet<String>) {
    if (element.elementType == SqlElementTypes.SQL_TABLE_REFERENCE) {
        element.children
            .firstOrNull { it.elementType == SqlElementTypes.SQL_IDENTIFIER }
            ?.text?.let { result.add(it) }
    }
    for (child in element.children) {
        collectTableRefs(child, result)
    }
}
