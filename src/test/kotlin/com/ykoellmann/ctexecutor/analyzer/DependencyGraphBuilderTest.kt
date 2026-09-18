package com.ykoellmann.ctexecutor.analyzer

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.ykoellmann.ctexecutor.model.DependencyGraph
import com.ykoellmann.ctexecutor.model.NamedNodeId
import com.ykoellmann.ctexecutor.model.NodeKind
import com.ykoellmann.ctexecutor.testutil.TestQueries

/**
 * Rollout-Schritt 2: DependencyGraphBuilder.discoverScope()/computeDependencyIds() gegen den
 * TestQueries-Korpus (claude/HANDOFF.md Abschnitt 12) abgesichert. Bewusst noch nirgends verdrahtet -
 * SqlAnalyzer.kt/SqlBuilder.kt und die 3 bestehenden Actions bleiben unangetastet.
 */
class DependencyGraphBuilderTest : BasePlatformTestCase() {

    private fun buildGraphFor(sql: String, fileName: String = "test.sql"): DependencyGraph {
        val file = myFixture.configureByText(fileName, sql)
        return DependencyGraphBuilder(resolver = PsiResolvingResolver()).build(file)
    }

    private fun buildGraphForSqlite(sql: String, fileName: String = "test_sqlite.sql"): DependencyGraph {
        val file = myFixture.configureByText(fileName, sql)
        com.intellij.sql.dialects.SqlDialectMappings.getInstance(project)
            .setMapping(file.virtualFile, com.intellij.database.dialects.sqlite.sql.SqliteDialect.INSTANCE)
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        val refreshed = com.intellij.psi.PsiManager.getInstance(project).findFile(file.virtualFile)!!
        return DependencyGraphBuilder(resolver = PsiResolvingResolver()).build(refreshed)
    }

    /**
     * Regressionstest zu Yanniks Bug-Report (2026-09-17): "die einzelnen SQL-Teile vom UNION werden
     * nicht erkannt" bei einer rekursiven SQLite-CTE mit Bracket-Identifiern (`[Name]`). Bestaetigt:
     * das ist KEIN Bug im DependencyGraphBuilder - mit korrekt zugeordnetem SQLite-Dialekt werden
     * beide UNION-ALL-Branches sauber erkannt. Die generische Dialekt-Grammatik (die ohne explizite
     * Zuordnung greift, siehe PsiResolvingSpikeTest) akzeptiert `[...]`-Bracket-Identifier dagegen
     * gar nicht - dann zerfaellt die komplette WITH-Klausel in Parse-Fehler, nicht nur die
     * UNION-Erkennung. Praktisch: die Datei/Konsole muss in DataGrip dem SQLite-Dialekt zugeordnet
     * sein (oder mit einer echten SQLite-Datenquelle verbunden sein).
     */
    fun testEmpChainRecursiveUnionBranchesAreDetectedUnderSqliteDialect() {
        val sql = """
            WITH RECURSIVE
                [EmpChain] AS (SELECT [EmployeeID], [ReportsTo], 0 AS [Depth]
                               FROM [Employees]
                               WHERE [ReportsTo] IS NULL

                               UNION ALL

                               SELECT [Employees].[EmployeeID],
                                      [Employees].[ReportsTo],
                                      [EmpChain].[Depth] + 1
                               FROM [Employees]
                                        JOIN [EmpChain] ON [Employees].[ReportsTo] = [EmpChain].[EmployeeID])
            SELECT * FROM [EmpChain]
        """.trimIndent()
        val graph = buildGraphForSqlite(sql)

        val empChain = graph.allCtesNamed("[EmpChain]").singleOrNull()
        assertNotNull("EmpChain CTE muss erkannt werden", empChain)
        val branches = graph.nodesOf(NodeKind.UNION_BRANCH)
        assertEquals("beide UNION-ALL-Branches muessen als eigene Knoten erkannt werden", 2, branches.size)
    }

    fun testNestedWithShadowingProducesTwoDistinctOrdersNodes() {
        val graph = buildGraphFor(TestQueries.NESTED_WITH_SHADOWING)
        val orderNodes = graph.allCtesNamed("orders")
        assertEquals(2, orderNodes.size)
        assertTrue("die beiden 'orders'-Knoten muessen unterschiedliche NodeIds haben (Shadowing)",
            orderNodes[0].id != orderNodes[1].id)

        val outer = orderNodes.first { it.element.text.contains("raw_orders_outer") }
        val inner = orderNodes.first { it.element.text.contains("raw_orders_inner") }

        // Die innere Subquery ("SELECT * FROM orders" innerhalb der verschachtelten WITH-Klausel)
        // muss zur INNEREN orders-CTE aufloesen, nicht zur aeusseren (Shadowing-Tiebreak).
        val innerSubquery = graph.nodesOf(NodeKind.SUBQUERY).single { it.element.text.contains("SELECT * FROM orders") }
        assertEquals(listOf(inner.id), innerSubquery.dependencyIds)
        assertTrue("darf NICHT von der aeusseren orders-CTE abhaengen", outer.id !in innerSubquery.dependencyIds)
    }

    fun testRecursiveCteSelfReferenceIsFlaggedAndTerminates() {
        val graph = buildGraphFor(TestQueries.WITH_RECURSIVE_SELF_REFERENCE)
        val nums = graph.allCtesNamed("nums").single()
        assertTrue("WITH RECURSIVE + Selbstreferenz muss isRecursive=true setzen", nums.isRecursive)
        assertTrue("Selbstschleife wird bewusst als dependencyId auf sich selbst aufgenommen", nums.id in nums.dependencyIds)

        // transitiveDependencies() darf bei der Selbstschleife nicht in eine Endlosschleife laufen
        // und darf den Knoten selbst nicht in seinen eigenen Abhaengigkeiten auflisten.
        val transitive = graph.transitiveDependencies(nums.id)
        assertFalse(transitive.any { it.id == nums.id })
    }

    fun testUnionInsideCteProducesTwoBranchesAndAggregatesOnCte() {
        val graph = buildGraphFor(TestQueries.UNION_INSIDE_CTE)
        // Nur die Existenz der CTE selbst wird hier gebraucht (die eigentliche Assertion ist die
        // Branch-Erkennung unten) - kein `combined.id`-Konsum-Test, siehe Kommentar unten.
        graph.allCtesNamed("combined").single()
        val branches = graph.nodesOf(NodeKind.UNION_BRANCH)
        assertEquals(2, branches.size)
        assertTrue(branches.any { it.element.text.contains("table_a") })
        assertTrue(branches.any { it.element.text.contains("table_b") })

        // Jeder Branch ist fuer sich navigierbar/ausfuehrbar. Branch und CTE stehen NICHT in einer
        // dependency/consumer-Beziehung zueinander (die Branches sind strukturell TEIL der CTE, keine
        // per Tabellenreferenz erreichte externe Abhaengigkeit) - die CTE aggregiert stattdessen die
        // externen Referenzen ihrer Branches direkt in ihre eigenen dependencyIds (siehe
        // computeDependencyIds' UNION-Sonderfall).
        branches.forEach { branch -> assertTrue(branch.dependencyIds.isEmpty()) }
    }

    fun testCorrelatedWhereSubqueryGetsNoOwnNode() {
        val graph = buildGraphFor(TestQueries.CORRELATED_WHERE_SUBQUERY)
        val correlatedCandidate = graph.allNodes().singleOrNull {
            it.element.text.trim().startsWith("SELECT 1 FROM customers")
        }
        assertNull("eine korrelierte Subquery darf laut Auswahlbarkeits-Regel KEINEN eigenen Knoten bekommen",
            correlatedCandidate)
    }

    fun testUncorrelatedFromSubqueryGetsOwnExecutableNode() {
        val graph = buildGraphFor(TestQueries.UNCORRELATED_FROM_SUBQUERY)
        val subquery = graph.nodesOf(NodeKind.SUBQUERY).singleOrNull {
            it.element.text.contains("amount > 100")
        }
        assertNotNull("eine unkorrelierte FROM-Subquery MUSS als eigener SUBQUERY-Knoten erscheinen", subquery)
        val orders = graph.allCtesNamed("orders").single()
        assertEquals(listOf(orders.id), subquery!!.dependencyIds)
    }

    fun testQualifiedReferenceCollisionDoesNotFalselyDependOnCte() {
        // `FROM public.orders` referenziert eine QUALIFIZIERTE Tabelle, nicht die CTE `orders` -
        // reines String-Matching (heutiges collectTableRefs) waere hier anfaellig fuer Fehltreffer.
        val graph = buildGraphFor(TestQueries.QUALIFIED_REFERENCE_COLLISION)
        val mainQuery = graph.nodesOf(NodeKind.MAIN_QUERY).single()
        assertTrue(
            "public.orders darf NICHT als Abhaengigkeit zur CTE 'orders' fehlinterpretiert werden",
            mainQuery.dependencyIds.none { it is NamedNodeId && it.name == "orders" }
        )
    }

    fun testSimpleMainQueryDependsOnItsCte() {
        val graph = buildGraphFor(
            """
            WITH raw_orders AS (
                SELECT id FROM orders
            )
            SELECT * FROM raw_orders
            """.trimIndent()
        )
        val cte = graph.allCtesNamed("raw_orders").single()
        val mainQuery = graph.nodesOf(NodeKind.MAIN_QUERY).single()
        assertEquals(listOf(cte.id), mainQuery.dependencyIds)
        assertEquals(listOf(mainQuery.id), cte.consumerIds)
    }
}
