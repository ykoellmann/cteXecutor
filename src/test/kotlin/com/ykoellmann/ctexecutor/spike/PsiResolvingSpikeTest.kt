package com.ykoellmann.ctexecutor.spike

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.elementType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlElementTypes
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.ykoellmann.ctexecutor.testutil.TestQueries

/**
 * PSI-Resolving-Spike (Phasenplan Schritt 0, siehe claude/rewrite-analyse-und-plan.md Abschnitt 5 & 10.1).
 *
 * Klaert vier Fragen, von denen das gesamte neue Datenmodell (Abschnitt 8/9/10 im Dokument) abhaengt:
 *
 *  1) Loest IntelliJs SQL-PSI eine Tabellen-Referenz ueberhaupt zur echten CTE-Deklaration auf,
 *     statt nur Text-Matching (heutiges collectTableRefs in SqlAnalyzer.kt)?
 *  2) Loest es bei zwei gleichnamigen CTEs in verschachtelten WITH-Klauseln korrekt zur
 *     NAEHEREN (inneren) Deklaration auf? (Shadowing, Abschnitt 9.1/9.2)
 *  3) Liegt bei einer korrelierten Subquery die aufgeloeste Deklaration einer aeusseren Referenz
 *     AUSSERHALB der TextRange der Subquery selbst? (Waere der Korrelations-Erkennungs-Mechanismus
 *     aus Abschnitt 8.3/10.3 — DependencyGraphBuilder.discoverScope soll das nutzen.)
 *  4) Loest die Selbstreferenz in einer WITH-RECURSIVE-CTE auf ihre eigene Deklaration auf?
 *     (Abschnitt 9.3, DependencyNode.isRecursive)
 *
 * WICHTIG: kein Assert auf das Ergebnis selbst — wir wissen die Antwort noch nicht. Jeder Test
 * druckt, was er findet. Einmal laufen lassen (Gutter-Icon oder Gradle), Konsolen-Output pruefen
 * und mit den ERWARTUNG-Kommentaren vergleichen. Das Ergebnis entscheidet, ob TableReferenceResolver
 * (Abschnitt 8.2) als PsiResolvingResolver gebaut werden kann, oder ob der Fallback aus 10.3 noetig ist.
 */
class PsiResolvingSpikeTest : BasePlatformTestCase() {

    fun testSimpleCteReferenceResolves() {
        myFixture.configureByText(
            "spike1.sql",
            """
            WITH raw_orders AS (
                SELECT id FROM orders
            )
            SELECT * FROM raw_ord<caret>ers
            """.trimIndent()
        )

        val reference = myFixture.getReferenceAtCaretPosition()
        println("SPIKE 1 - reference: $reference")

        val resolved = reference?.resolve()
        println("SPIKE 1 - resolved: $resolved")
        println("SPIKE 1 - resolved.text: ${resolved?.text}")
        println("SPIKE 1 - resolved.elementType: ${resolved?.elementType}")
        // ERWARTUNG, falls PSI-Resolving nutzbar ist: resolved zeigt auf die
        // SQL_NAMED_QUERY_DEFINITION (oder deren Identifier-Kind) von raw_orders.
        // Falls resolved == null: reines PSI-Resolving reicht hier nicht, Fallback noetig.
    }

    fun testShadowingInNestedWith() {
        myFixture.configureByText(
            "spike2.sql",
            """
            WITH orders AS (
                SELECT id FROM raw_orders_outer
            )
            SELECT * FROM (
                WITH orders AS (
                    SELECT id FROM raw_orders_inner
                )
                SELECT * FROM ord<caret>ers
            ) x
            """.trimIndent()
        )

        val reference = myFixture.getReferenceAtCaretPosition()
        val resolved = reference?.resolve()
        println("SPIKE 2 - resolved: $resolved")
        println("SPIKE 2 - resolved.text: ${resolved?.text}")
        val resolvesToInner = resolved?.text?.contains("raw_orders_inner") == true
        println("SPIKE 2 - loest zur INNEREN orders-CTE auf (korrektes Shadowing)? $resolvesToInner")
        if (reference is PsiPolyVariantReference) {
            val variants = reference.multiResolve(false)
            println("SPIKE 2 - multiResolve() liefert ${variants.size} Kandidat(en):")
            variants.forEach { println("SPIKE 2 -   candidate: ${it.element}, text=${it.element?.text?.take(60)}, isValidResult=${it.isValidResult}") }
        } else {
            println("SPIKE 2 - reference ist KEIN PsiPolyVariantReference (Klasse: ${reference?.javaClass})")
        }
        // ERWARTUNG: true. Wenn false (loest zur aeusseren auf) oder resolved == null,
        // ist Shadowing per PSI-Resolving NICHT automatisch geloest -> eigener Scope-Check noetig,
        // scope-qualifizierte NamedNodeId (Abschnitt 8.1) muesste den Unterschied dann selbst tragen.
    }

    fun testCorrelatedSubqueryResolvesOutsideItsOwnRange() {
        val file = myFixture.configureByText(
            "spike3.sql",
            """
            WITH orders AS (
                SELECT id, customer_id FROM raw_orders
            )
            SELECT *
            FROM orders o
            WHERE EXISTS (
                SELECT 1 FROM customers c WHERE c.id = <caret>o.customer_id
            )
            """.trimIndent()
        )

        val reference = myFixture.getReferenceAtCaretPosition()
        val resolved = reference?.resolve()
        println("SPIKE 3 - resolved: $resolved, text: ${resolved?.text}")

        val subquery = findInnermostSelect(file, myFixture.caretOffset)
        println("SPIKE 3 - subquery range: ${subquery?.textRange}")
        println("SPIKE 3 - resolved range: ${resolved?.textRange}")

        val liegtAusserhalb = resolved != null && subquery != null && !subquery.textRange.contains(resolved.textRange)
        println("SPIKE 3 - Aufloesung liegt AUSSERHALB der Subquery (== Korrelation erkennbar)? $liegtAusserhalb")
        // ERWARTUNG: true. Wenn true, ist "liegt die Aufloesung ausserhalb der eigenen TextRange"
        // ein brauchbarer, generischer Korrelations-Test fuer DependencyGraphBuilder.discoverScope
        // (Abschnitt 8.3) - genau der Mechanismus, den Abschnitt 10.3 noch offen liess.
        //
        // KORREKTUR (siehe testDumpPsiTreeForCorrelatedSubquery): findInnermostSelect() sucht nach
        // SQL_SELECT_STATEMENT, aber dieser Elementtyp existiert nur EINMAL pro Datei (die aeusserste
        // WITH-Query). Eine geklammerte Subquery (auch die EXISTS-Subquery hier) ist ein
        // SQL_QUERY_EXPRESSION, kein eigenes SQL_SELECT_STATEMENT. Der obige Wert ist deshalb falsch
        // (subquery == die ganze WITH-Query). Korrekt via SQL_QUERY_EXPRESSION:
        val correctSubquery = findInnermostQueryExpression(file, myFixture.caretOffset)
        println("SPIKE 3 - korrekte Subquery-Range (SQL_QUERY_EXPRESSION): ${correctSubquery?.textRange}")
        val liegtAusserhalbKorrekt = resolved != null && correctSubquery != null &&
            !correctSubquery.textRange.contains(resolved.textRange)
        println("SPIKE 3 - KORREKT: Aufloesung liegt ausserhalb der Subquery? $liegtAusserhalbKorrekt")
    }

    private fun findInnermostQueryExpression(root: PsiFile, offset: Int): PsiElement? {
        var current: PsiElement? = root.findElementAt(offset)
        while (current != null) {
            if (current.elementType == SqlElementTypes.SQL_QUERY_EXPRESSION) return current
            current = current.parent
        }
        return null
    }

    fun testRecursiveCteSelfReference() {
        myFixture.configureByText(
            "spike4.sql",
            """
            WITH RECURSIVE nums AS (
                SELECT 1 AS n
                UNION ALL
                SELECT n + 1 FROM nu<caret>ms WHERE n < 10
            )
            SELECT * FROM nums
            """.trimIndent()
        )

        val reference = myFixture.getReferenceAtCaretPosition()
        val resolved = reference?.resolve()
        println("SPIKE 4 - resolved: $resolved")
        println("SPIKE 4 - resolved.text startet mit 'nums'? ${resolved?.text?.startsWith("nums")}")
        // ERWARTUNG: resolved zeigt auf die eigene WITH-RECURSIVE-Definition (Selbstschleife).
        // Wichtig fuer Abschnitt 9.3 / DependencyGraph.transitiveDependencies() (Visited-Set-Schutz
        // gegen Endlosrekursion ist im Modell schon vorgesehen, unabhaengig vom Spike-Ergebnis).
    }

    // --- Diagnose-Tests, eingefuegt nachdem testSimpleCteReferenceResolves() null zurueckgab ---
    // Zeigen, WARUM es null war: falsches File-Type/Parsing, oder Element ohne PsiReference,
    // oder Reference vorhanden aber liefert null bei resolve(). Erst danach koennen wir
    // entscheiden, ob Abschnitt 10.3 (Fallback) noetig ist, statt aus einem Nullwert zu raten.

    fun testWhatFileTypeIsRegisteredForSqlExtension() {
        val ftm = FileTypeManager.getInstance()
        val byName = ftm.getFileTypeByFileName("probe.sql")
        println("SPIKE FILETYPE - getFileTypeByFileName(\"probe.sql\") = $byName (class ${byName.javaClass})")
        println("SPIKE FILETYPE - isBinary = ${byName.isBinary}")
        val registered = ftm.registeredFileTypes.map { it.name }.sorted()
        println("SPIKE FILETYPE - alle registrierten FileType-Namen: $registered")
    }

    fun testDumpPsiTreeForSimpleWith() {
        val file = myFixture.configureByText(
            "dump.sql",
            """
            WITH raw_orders AS (
                SELECT id FROM orders
            )
            SELECT * FROM raw_orders
            """.trimIndent()
        )
        println("SPIKE DUMP - file.fileType = ${file.fileType}")
        println("SPIKE DUMP - file.language = ${file.language}")
        println("SPIKE DUMP - PSI-Baum:")
        dumpPsiTree(file, 0)
    }

    fun testReferencesOnMainQueryTableReference() {
        val file = myFixture.configureByText(
            "refs.sql",
            """
            WITH raw_orders AS (
                SELECT id FROM orders
            )
            SELECT * FROM raw_orders
            """.trimIndent()
        )

        val tableRef = PsiTreeUtil.findChildrenOfType(file, PsiElement::class.java)
            .firstOrNull { it.elementType == SqlElementTypes.SQL_TABLE_REFERENCE }
        println("SPIKE REFS - tableRef element: $tableRef, text: ${tableRef?.text}")
        println("SPIKE REFS - tableRef.references: ${tableRef?.references?.toList()}")
        tableRef?.references?.forEach { ref ->
            println("SPIKE REFS -   ref class=${ref.javaClass}, rangeInElement=${ref.rangeInElement}, resolve()=${ref.resolve()}")
        }

        val identifier = tableRef?.children?.firstOrNull { it.elementType == SqlElementTypes.SQL_IDENTIFIER }
        println("SPIKE REFS - identifier element: $identifier, text: ${identifier?.text}")
        println("SPIKE REFS - identifier.references: ${identifier?.references?.toList()}")
        identifier?.references?.forEach { ref ->
            println("SPIKE REFS -   ref class=${ref.javaClass}, rangeInElement=${ref.rangeInElement}, resolve()=${ref.resolve()}")
        }
    }

    fun testDumpPsiTreeForCorrelatedSubquery() {
        val file = myFixture.configureByText(
            "spike3dump.sql",
            """
            WITH orders AS (
                SELECT id, customer_id FROM raw_orders
            )
            SELECT *
            FROM orders o
            WHERE EXISTS (
                SELECT 1 FROM customers c WHERE c.id = o.customer_id
            )
            """.trimIndent()
        )
        println("SPIKE DUMP3 - PSI-Baum:")
        dumpPsiTree(file, 0)
    }

    fun testDumpPsiTreeForUnionInsideCte() {
        val file = myFixture.configureByText(
            "spikeunion.sql",
            TestQueries.UNION_INSIDE_CTE
        )
        println("SPIKE DUMP-UNION - PSI-Baum:")
        dumpPsiTree(file, 0)
    }

    fun testDumpPsiTreeForNestedWithShadowing() {
        val file = myFixture.configureByText(
            "spikenested.sql",
            TestQueries.NESTED_WITH_SHADOWING
        )
        println("SPIKE DUMP-NESTED - PSI-Baum:")
        dumpPsiTree(file, 0)
    }

    fun testDumpPsiTreeForRecursive() {
        val file = myFixture.configureByText(
            "spikerecursive.sql",
            TestQueries.WITH_RECURSIVE_SELF_REFERENCE
        )
        println("SPIKE DUMP-RECURSIVE - PSI-Baum:")
        dumpPsiTree(file, 0)
    }

    fun testDumpPsiTreeForLateral() {
        val file = myFixture.configureByText(
            "spikelateral.sql",
            TestQueries.LATERAL_JOIN_POSTGRES
        )
        println("SPIKE DUMP-LATERAL - PSI-Baum:")
        dumpPsiTree(file, 0)
    }

    fun testDumpPsiTreeForUncorrelatedFromSubquery() {
        val file = myFixture.configureByText(
            "spikeuncorrelated.sql",
            TestQueries.UNCORRELATED_FROM_SUBQUERY
        )
        println("SPIKE DUMP-UNCORRELATED - PSI-Baum:")
        dumpPsiTree(file, 0)
    }

    fun testDumpPsiTreeForQualifiedReferenceCollision() {
        val file = myFixture.configureByText(
            "spikequalified.sql",
            TestQueries.QUALIFIED_REFERENCE_COLLISION
        )
        println("SPIKE DUMP-QUALIFIED - PSI-Baum:")
        dumpPsiTree(file, 0)
    }

    private fun dumpPsiTree(element: PsiElement, depth: Int) {
        val indent = "  ".repeat(depth)
        val typeLabel = element.elementType?.toString() ?: element.javaClass.simpleName
        val textPreview = element.text.replace("\n", "\\n").let { if (it.length > 40) it.take(40) + "..." else it }
        println("$indent$typeLabel \"$textPreview\"")
        for (child in element.children) {
            dumpPsiTree(child, depth + 1)
        }
    }

    private fun findInnermostSelect(root: PsiFile, offset: Int): PsiElement? {
        var current: PsiElement? = root.findElementAt(offset)
        while (current != null) {
            if (current.elementType == SqlElementTypes.SQL_SELECT_STATEMENT) return current
            current = current.parent
        }
        return null
    }
}
