package com.ykoellmann.ctexecutor.actions

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.ykoellmann.ctexecutor.analyzer.DependencyGraphBuilder
import com.ykoellmann.ctexecutor.model.DependencyGraph

/**
 * CTE-Modus (HANDOFF.md Abschnitt 26/27): `ExecuteFromHereAction.computeRows()` baut dieselbe
 * Zeilenliste, die die 3 alten Actions (ExecuteFromHereAction/CopyAction/CopyAndEditAction, jetzt
 * zu 1 Popup mit Enter/E/C-Tastenkuerzeln zusammengefasst) schon zeigten - hier auf `DependencyGraph`
 * umgestellt statt auf dem alten `SqlAnalyzer`/`SqlBuilder`.
 */
class ExecuteFromHereActionTest : BasePlatformTestCase() {

    private fun buildGraphFor(sql: String): DependencyGraph {
        val file = myFixture.configureByText("test.sql", sql)
        return DependencyGraphBuilder().build(file)
    }

    fun testProgressiveRowsPlusFinalRowForChainedCtes() {
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
        val mainQuery = graph.allNodes().single { it.displayName == "Main Query" }
        val rows = ExecuteFromHereAction.computeRows(graph, mainQuery)

        // 2 benoetigte CTEs (progressiv) + 1 finale Zeile = 3 Eintraege, exakt wie im Altsystem.
        assertEquals(3, rows.size)
        assertEquals("raw_orders", rows[0].label)
        assertEquals("enriched_orders", rows[1].label)
        assertEquals("Current Query", rows[2].label)

        // Zeile 0 (raw_orders): keine WITH-Klausel noetig, kein "SELECT * FROM"-Wrapper.
        assertFalse(rows[0].sql.contains("WITH"))
        assertTrue(rows[0].sql.trim().endsWith("SELECT id FROM orders;"))

        // Zeile 1 (enriched_orders): WITH-Klausel enthaelt raw_orders, Hauptanweisung ist der rohe
        // Rumpf von enriched_orders (kein "SELECT * FROM enriched_orders").
        assertTrue(rows[1].sql.contains("raw_orders AS ("))
        assertTrue(rows[1].sql.trim().endsWith("SELECT id FROM raw_orders;"))
        assertFalse(rows[1].sql.contains("SELECT * FROM enriched_orders"))

        // Zeile 2 (aktuelles Statement): komplettes Statement + beide CTEs.
        assertTrue(rows[2].sql.contains("raw_orders AS ("))
        assertTrue(rows[2].sql.contains("enriched_orders AS ("))
        assertTrue(rows[2].sql.trim().endsWith("SELECT * FROM enriched_orders;"))
    }

    fun testCursorInsideCteShowsCteAsCurrentQuery() {
        val sql = """
            WITH raw_orders AS (
                SELECT id FROM orders
            )
            SELECT * FROM raw_orders
        """.trimIndent()
        val graph = buildGraphFor(sql)
        val cte = graph.allCtesNamed("raw_orders").single()
        val rows = ExecuteFromHereAction.computeRows(graph, cte)

        // raw_orders hat selbst keine Abhaengigkeiten -> nur die finale Zeile.
        assertEquals(1, rows.size)
        assertEquals("raw_orders", rows[0].label)
        assertFalse("kein SELECT * FROM-Wrapper", rows[0].sql.contains("SELECT * FROM raw_orders"))
        assertTrue(rows[0].sql.trim().endsWith("SELECT id FROM orders;"))
    }
}
