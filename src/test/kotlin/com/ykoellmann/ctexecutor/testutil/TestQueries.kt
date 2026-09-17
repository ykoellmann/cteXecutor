package com.ykoellmann.ctexecutor.testutil

/**
 * Testquery-Korpus fuer Schritt 2 des Rollouts (DependencyGraphBuilder.discoverScope /
 * computeDependencyIds gegen echte, absichtlich knifflige Queries entwickeln).
 * Siehe claude/rewrite-analyse-und-plan.md Abschnitt 10.2 — dieser Korpus war dort als fehlend
 * markiert. Jede Konstante ist bewusst auf EIN Problem aus Abschnitt 9 zugeschnitten, nicht als
 * realistisches Gesamtbeispiel gedacht.
 *
 * Bewusst noch keine JUnit-Tests hier drin — nur Fixtures. Die eigentlichen discoverScope-Tests
 * entstehen erst in Rollout-Schritt 2, sobald die TODOs in DependencyGraphBuilder ausgefuellt werden.
 */
object TestQueries {

    /** Zwei CTEs mit gleichem Namen in unterschiedlichen WITH-Ebenen (Abschnitt 9.1 / 9.2). */
    val NESTED_WITH_SHADOWING = """
        WITH orders AS (
            SELECT id FROM raw_orders_outer
        )
        SELECT *
        FROM (
            WITH orders AS (
                SELECT id FROM raw_orders_inner
            )
            SELECT * FROM orders
        ) x
    """.trimIndent()

    /** Rekursive CTE mit Selbstreferenz im rekursiven Teil (Abschnitt 9.3). */
    val WITH_RECURSIVE_SELF_REFERENCE = """
        WITH RECURSIVE nums AS (
            SELECT 1 AS n
            UNION ALL
            SELECT n + 1 FROM nums WHERE n < 10
        )
        SELECT * FROM nums
    """.trimIndent()

    /** UNION innerhalb einer CTE — muss als mehrere UNION_BRANCH-Knoten erkannt werden. */
    val UNION_INSIDE_CTE = """
        WITH combined AS (
            SELECT id, 'a' AS src FROM table_a
            UNION ALL
            SELECT id, 'b' AS src FROM table_b
        )
        SELECT * FROM combined
    """.trimIndent()

    /** LATERAL JOIN — praktisch immer korreliert, darf laut 9.1/6a keinen eigenen Knoten bekommen. */
    val LATERAL_JOIN_POSTGRES = """
        WITH orders AS (
            SELECT id, customer_id FROM raw_orders
        )
        SELECT o.id, top_item.name
        FROM orders o,
        LATERAL (
            SELECT name FROM order_items oi WHERE oi.order_id = o.id ORDER BY oi.amount DESC LIMIT 1
        ) top_item
    """.trimIndent()

    /** Korrelierte WHERE-Subquery OHNE LATERAL-Keyword — muss trotzdem als korreliert erkannt werden. */
    val CORRELATED_WHERE_SUBQUERY = """
        WITH orders AS (
            SELECT id, customer_id FROM raw_orders
        )
        SELECT *
        FROM orders o
        WHERE EXISTS (
            SELECT 1 FROM customers c WHERE c.id = o.customer_id
        )
    """.trimIndent()

    /** Unkorrelierte Subquery in FROM — MUSS als eigener, ausfuehrbarer SUBQUERY-Knoten erscheinen. */
    val UNCORRELATED_FROM_SUBQUERY = """
        WITH orders AS (
            SELECT id, amount FROM raw_orders
        )
        SELECT *
        FROM (
            SELECT id, amount FROM orders WHERE amount > 100
        ) big_orders
    """.trimIndent()

    /** Qualifizierte Referenz kollidiert mit unqualifiziertem CTE-Namen (Abschnitt 9.1, String-Matching-Fallstrick). */
    val QUALIFIED_REFERENCE_COLLISION = """
        WITH orders AS (
            SELECT id FROM raw_orders
        )
        SELECT * FROM public.orders
    """.trimIndent()
}
