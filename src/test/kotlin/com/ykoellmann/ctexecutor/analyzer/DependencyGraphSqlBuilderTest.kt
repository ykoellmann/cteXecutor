package com.ykoellmann.ctexecutor.analyzer

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.ykoellmann.ctexecutor.actions.resolveCaretOffset
import com.ykoellmann.ctexecutor.model.DependencyGraph
import com.ykoellmann.ctexecutor.model.NodeKind
import com.ykoellmann.ctexecutor.testutil.TestQueries

/**
 * Rollout-Schritt 3: DependencyGraphSqlBuilder (Nachfolger von SqlBuilder.kt, HANDOFF.md
 * Abschnitt 4.5) gegen den TestQueries-Korpus abgesichert. Bewusst noch nirgends verdrahtet.
 */
class DependencyGraphSqlBuilderTest : BasePlatformTestCase() {

    private fun buildGraphFor(sql: String, fileName: String = "test.sql"): DependencyGraph {
        val file = myFixture.configureByText(fileName, sql)
        return DependencyGraphBuilder(resolver = PsiResolvingResolver()).build(file)
    }

    fun testCteWithoutDependenciesNeedsNoWithClause() {
        val graph = buildGraphFor(
            """
            WITH raw_orders AS (
                SELECT id FROM orders
            )
            SELECT * FROM raw_orders
            """.trimIndent()
        )
        val cte = graph.allCtesNamed("raw_orders").single()
        val result = DependencyGraphSqlBuilder.build(cte, graph)

        // raw_orders braucht keine andere CTE und ist nicht rekursiv -> gar keine WITH-Klausel noetig,
        // exaktes Altsystem-Verhalten (SqlBuilder.buildSql: `if (dependencies.isNotEmpty())`, das dort
        // fuer den einzigen real erreichten Rendering-Pfad - DEPENDENCIES_WITH_TARGET_INNER - greift).
        assertFalse(result.sql.contains("WITH"))
        assertTrue(result.sql.trim().endsWith("SELECT id FROM orders;"))
    }

    fun testCteWithDependencyGetsWithClauseAndRawBody() {
        val graph = buildGraphFor(
            """
            WITH raw_orders AS (
                SELECT id FROM orders
            ),
            enriched_orders AS (
                SELECT id FROM raw_orders
            )
            SELECT * FROM enriched_orders
            """.trimIndent()
        )
        val cte = graph.allCtesNamed("enriched_orders").single()
        val result = DependencyGraphSqlBuilder.build(cte, graph)

        assertTrue(result.sql.startsWith("WITH\n"))
        assertTrue(result.sql.contains("raw_orders AS ("))
        // Kein "SELECT * FROM name"-Wrapper - der rohe Rumpf der Ziel-CTE wird direkt als
        // Hauptanweisung eingesetzt, exakt wie im Altsystem (SqlBuilder.extractInnerSelect(), von
        // ALLEN 3 bestehenden, veroeffentlichten Actions tatsaechlich genutzt - nicht der
        // "SELECT * FROM name"-Zweig, der dort real unerreichbar ist).
        assertTrue(result.sql.trim().endsWith("SELECT id FROM raw_orders;"))
        assertFalse("kein SELECT * FROM-Wrapper fuer das CTE-Ziel", result.sql.contains("SELECT * FROM enriched_orders"))
        assertFalse("keine unnoetige RECURSIVE-Klausel", result.sql.contains("RECURSIVE"))
    }

    fun testMainQueryAssemblesAllTransitiveDependencies() {
        val graph = buildGraphFor(TestQueries.UNCORRELATED_FROM_SUBQUERY)
        val mainQuery = graph.nodesOf(NodeKind.MAIN_QUERY).single()
        val result = DependencyGraphSqlBuilder.build(mainQuery, graph)

        // Main Query selbst ist keine CTE -> steht als Hauptanweisung, nicht in der WITH-Liste.
        assertTrue(result.sql.contains("orders AS ("))
        assertTrue(result.sql.contains("big_orders"))
        assertTrue(result.sql.trim().endsWith(";"))
    }

    fun testUncorrelatedSubqueryAssemblesOwnDependencyOnly() {
        val graph = buildGraphFor(TestQueries.UNCORRELATED_FROM_SUBQUERY)
        val subquery = graph.nodesOf(NodeKind.SUBQUERY).single { it.element.text.contains("amount > 100") }
        val result = DependencyGraphSqlBuilder.build(subquery, graph)

        assertTrue(result.sql.contains("WITH\norders AS ("))
        assertTrue(result.sql.trim().endsWith("SELECT id, amount FROM orders WHERE amount > 100;"))
    }

    fun testNestedShadowingPicksTheCorrectScopedCte() {
        val graph = buildGraphFor(TestQueries.NESTED_WITH_SHADOWING)
        val innerSubquery = graph.nodesOf(NodeKind.SUBQUERY).single { it.element.text.contains("SELECT * FROM orders") }
        val result = DependencyGraphSqlBuilder.build(innerSubquery, graph)

        assertTrue("muss die INNERE orders-CTE (raw_orders_inner) mitbringen", result.sql.contains("raw_orders_inner"))
        assertFalse("darf NICHT die AEUSSERE orders-CTE (raw_orders_outer) mitbringen", result.sql.contains("raw_orders_outer"))
    }

    fun testRecursiveCteEmitsWithRecursive() {
        val graph = buildGraphFor(TestQueries.WITH_RECURSIVE_SELF_REFERENCE)
        val nums = graph.allCtesNamed("nums").single()
        val result = DependencyGraphSqlBuilder.build(nums, graph)

        assertTrue(result.sql.startsWith("WITH RECURSIVE\n"))
        // Fuer eine REKURSIVE CTE als Ziel MUSS ihre eigene Definition in die WITH-Liste (ihr Rumpf
        // referenziert sich selbst per Namen) - anders als bei einer nicht-rekursiven CTE (siehe
        // testSimpleCteAssemblesWithClauseAndSelect) braucht es hier den "SELECT * FROM name"-Wrapper,
        // sonst waere die Selbstreferenz im rohen Rumpf undefiniert (siehe Kommentar in
        // DependencyGraphSqlBuilder.assemble()).
        assertTrue(result.sql.contains("nums AS ("))
        assertTrue(result.sql.contains("UNION ALL"))
        assertTrue(result.sql.trim().endsWith("SELECT * FROM nums;"))
    }

    fun testRecursiveUnionBranchStandaloneAlsoEmitsWithRecursive() {
        val graph = buildGraphFor(TestQueries.WITH_RECURSIVE_SELF_REFERENCE)
        val recursiveBranch = graph.nodesOf(NodeKind.UNION_BRANCH).single { it.element.text.contains("n + 1") }
        val result = DependencyGraphSqlBuilder.build(recursiveBranch, graph)

        assertTrue("ein Branch, der die rekursive CTE braucht, muss WITH RECURSIVE erzeugen",
            result.sql.startsWith("WITH RECURSIVE\n"))
        assertTrue(result.sql.trim().endsWith("SELECT n + 1 FROM nums WHERE n < 10;"))
    }

    fun testSourceCaretMappingResolvesPositionWithinRawBody() {
        val sql = """
            WITH raw_orders AS (
                SELECT id FROM orders
            ),
            enriched_orders AS (
                SELECT id FROM raw_orders
            )
            SELECT * FROM enriched_orders
        """.trimIndent()
        val graph = buildGraphFor(sql)
        val cte = graph.allCtesNamed("enriched_orders").single()
        val result = DependencyGraphSqlBuilder.build(cte, graph)
        val mapping = result.sourceCaretMapping
        assertNotNull("eine nicht-rekursive CTE mit rohem Rumpf muss eine SourceCaretMapping haben", mapping)

        // Cursor mitten in "raw_orders" innerhalb des Original-Rumpfs von enriched_orders.
        val bodyText = "SELECT id FROM raw_orders"
        val bodyStartInSource = sql.indexOf(bodyText)
        val cursorInSource = bodyStartInSource + bodyText.indexOf("raw_orders")

        val resolved = mapping!!.resolveCaretOffset(cursorInSource)
        assertNotNull(resolved)
        // Die zusammengebaute SQL muss an genau dieser Stelle ebenfalls "raw_orders" stehen haben.
        assertEquals("raw_orders", result.sql.substring(resolved!!, resolved + "raw_orders".length))
    }

    fun testSourceCaretMappingIsNullForRecursiveWrapperTarget() {
        val graph = buildGraphFor(TestQueries.WITH_RECURSIVE_SELF_REFERENCE)
        val nums = graph.allCtesNamed("nums").single()
        val result = DependencyGraphSqlBuilder.build(nums, graph)

        // Der "SELECT * FROM nums"-Wrapper ist kein woertlicher Auszug aus der Original-Datei -
        // keine sinnvolle Cursor-Zuordnung moeglich.
        assertNull(result.sourceCaretMapping)
    }

    fun testUnionBranchStandaloneIsSelfContained() {
        val graph = buildGraphFor(TestQueries.UNION_INSIDE_CTE)
        val branchA = graph.nodesOf(NodeKind.UNION_BRANCH).single { it.element.text.contains("table_a") }
        val result = DependencyGraphSqlBuilder.build(branchA, graph)

        // Der Branch braucht keine externe CTE -> keine WITH-Klausel noetig.
        assertFalse(result.sql.contains("WITH"))
        assertTrue(result.sql.trim().endsWith("SELECT id, 'a' AS src FROM table_a;"))
    }
}
