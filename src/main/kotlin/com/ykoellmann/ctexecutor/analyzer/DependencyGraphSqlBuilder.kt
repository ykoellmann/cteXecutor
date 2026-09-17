package com.ykoellmann.ctexecutor.analyzer

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.ykoellmann.ctexecutor.model.DependencyGraph
import com.ykoellmann.ctexecutor.model.DependencyNode
import com.ykoellmann.ctexecutor.model.NodeKind

/**
 * Nachfolger von SqlBuilder.kt (HANDOFF.md Abschnitt 4.5) fuer das neue DependencyGraph-Modell.
 * Baut aus einem Zielknoten + seinen transitiven Abhaengigkeiten eigenstaendig ausfuehrbares SQL.
 *
 * Inzwischen die einzige SQL-Zusammenbau-Quelle im Plugin (HANDOFF.md Abschnitt 26): `SqlActionBase`
 * (und damit alle 3 veroeffentlichten Actions) nutzt diese Klasse statt des alten `SqlBuilder.kt`.
 *
 * Da korrelierte Konstrukte laut Auswahlbarkeits-Regel (Abschnitt 5) gar nicht erst zu einem
 * DependencyNode werden (siehe DependencyGraphBuilder.discoverSubqueryCandidates/isCorrelated),
 * muss diese Schicht selbst KEINEN Korrelations-Sonderfall mehr behandeln: alles, was ein
 * DependencyNode ist, ist per Konstruktion vollstaendig eigenstaendig ausfuehrbar.
 */
object DependencyGraphSqlBuilder {

    data class BuildResult(
        val sql: String,
        val highlightRanges: List<TextRange>,
        val sourceCaretMapping: SourceCaretMapping? = null
    )

    /**
     * Erlaubt einem Aufrufer, einen absoluten Cursor-Offset in der ORIGINAL-Datei auf die
     * entsprechende Position im zusammengebauten [BuildResult.sql] umzurechnen: liegt der Original-
     * Offset innerhalb von [sourceElement]s TextRange, ist die passende Position im SQL-String
     * `offsetInSql + (originalOffset - sourceElement.textRange.startOffset)`. `null` in
     * [BuildResult.sourceCaretMapping], wenn die Hauptanweisung kein wortwoertlicher Auszug aus der
     * Original-Datei ist (z.B. der `SELECT * FROM name`-Wrapper fuer rekursive CTE-Ziele, siehe
     * [assemble]) - dort gibt es keine sinnvolle 1:1-Zuordnung.
     */
    data class SourceCaretMapping(val sourceElement: PsiElement, val offsetInSql: Int)

    fun build(node: DependencyNode, graph: DependencyGraph): BuildResult {
        // Positions-Sortierung statt eines expliziten Index (wie im alten CteEntry.index): eine CTE
        // kann nur auf CTEs verweisen, die im Text vor ihr stehen (bzw. in einer sie umschliessenden
        // WITH-Klausel) - Sortierung nach TextRange.startOffset ergibt deshalb durchgaengig eine
        // gueltige Deklarationsreihenfolge fuer die zusammengebaute WITH-Klausel.
        val dependencies = graph.transitiveDependencies(node.id)
            .sortedBy { it.element.textRange.startOffset }
        return assemble(node, dependencies)
    }

    /**
     * Wie [build], aber mit einer explizit vorgegebenen CTE-Liste statt der ueber den Graphen
     * berechneten transitiven Abhaengigkeiten von [target] selbst.
     *
     * Grund: `SqlActionBase` zeigt "progressive" Popup-Optionen entlang der KUMULATIVEN Praefix-Liste
     * aller fuer den urspruenglichen Cursor-Knoten benoetigten CTEs (Schritt i bekommt die ersten i+1
     * CTEs dieser einen, gemeinsamen Liste in seine WITH-Klausel) - NICHT entlang der individuellen,
     * eigenen Abhaengigkeiten jeder einzelnen Zwischen-CTE. Das ist exakt das Verhalten des Altsystems
     * (`SqlActionBase.buildPopupOptions()`, `ctesUpToHere = requiredCtes.subList(0, i + 1)`), siehe
     * HANDOFF.md Abschnitt 26. [cteDefinitions] enthaelt [target] selbst NICHT, auch wenn [target]
     * eine CTE ist - [assemble] fuegt sie bei Bedarf selbst hinzu (wie in [build]).
     */
    fun buildWithExplicitDependencies(target: DependencyNode, cteDefinitions: List<DependencyNode>): BuildResult =
        assemble(target, cteDefinitions)

    private fun assemble(node: DependencyNode, dependencies: List<DependencyNode>): BuildResult {
        // Ist der Zielknoten selbst eine CTE, gehoert ihre eigene Definition mit in die WITH-Liste.
        // Fuer alle anderen Knotenarten (SUBQUERY/UNION_BRANCH/MAIN_QUERY/LATERAL) ist der Knoten
        // selbst kein WITH-Listeneintrag, sondern die abschliessende Hauptanweisung.
        val cteDefinitions = if (node.kind == NodeKind.CTE) dependencies + node else dependencies
        val needsRecursive = cteDefinitions.any { it.isRecursive }

        // Eine REKURSIVE CTE als Ziel muss ihre eigene "name AS (...)"-Definition immer mit in die
        // WITH-Liste bekommen - ihr Rumpf referenziert sich selbst per Namen, roher Rumpf ohne
        // Wrapper waere hier invalides SQL (der self-reference fehlte jede Definition). Das war ein
        // echter, bisher unentdeckter Bug im Altsystem (SqlBuilder extrahierte fuer JEDES CTE-Ziel
        // immer den rohen Rumpf, auch fuer rekursive - dort aber nie manifest geworden, weil kein
        // realer Testfall eine rekursive CTE als "aktuelles Ziel" ausgefuehrt hat). Fuer jede andere
        // CTE (nicht rekursiv) bleibt der rohe Rumpf ohne Wrapper - exaktes Altsystem-Verhalten
        // (SqlBuilder.extractInnerSelect(), von SqlActionBase ueber alle 3 bestehenden Actions
        // tatsaechlich genutzt, siehe HANDOFF.md Abschnitt 26).
        val useNameWrapperForTarget = node.kind == NodeKind.CTE && node.isRecursive
        val needsWithClause = if (node.kind == NodeKind.CTE) {
            dependencies.isNotEmpty() || useNameWrapperForTarget
        } else {
            cteDefinitions.isNotEmpty()
        }

        val parts = mutableListOf<String>()
        val highlightRanges = mutableListOf<TextRange>()

        if (needsWithClause) {
            parts.add(if (needsRecursive) "WITH RECURSIVE" else "WITH")
            parts.add(cteDefinitions.joinToString(",\n") { it.element.text })
            // Nur die reinen Abhaengigkeits-CTEs komplett hervorheben - ist der Zielknoten selbst eine
            // CTE (dann der letzte Eintrag dieser Liste), wird gleich unten stattdessen nur ihr Rumpf
            // separat hervorgehoben (wie im Altsystem: SqlBuilder.buildDependenciesWithTargetInnerRanges
            // zeigt Dependencies komplett, das Ziel nur innen).
            val pureDependencies = if (node.kind == NodeKind.CTE) cteDefinitions.dropLast(1) else cteDefinitions
            pureDependencies.forEach { highlightRanges.add(it.element.textRange) }
        }

        // `findBodySlot` ist dieselbe Funktion, die DependencyGraphBuilder schon fuer die
        // CTE-Erkennung nutzt - eine einzige Quelle fuer "was ist der Rumpf einer CTE".
        val targetBody = if (node.kind == NodeKind.CTE && !useNameWrapperForTarget) findBodySlot(node.element) else null
        val mainStatement = when {
            targetBody != null -> targetBody.text
            useNameWrapperForTarget -> "SELECT * FROM ${node.displayName}"
            else -> node.element.text
        }
        // Offset, an dem `mainStatement` im zusammengebauten SQL-String beginnen wird - VOR dem
        // Hinzufuegen berechnet, damit er nicht durch das Hinzufuegen selbst verfaelscht wird.
        val mainStatementOffset = if (parts.isEmpty()) 0 else parts.joinToString("\n").length + 1
        parts.add(mainStatement)
        highlightRanges.add(targetBody?.textRange ?: node.element.textRange)

        val sql = parts.joinToString("\n").let { if (it.trim().endsWith(";")) it else "$it;" }

        // Nur wenn die Hauptanweisung ein woertlicher Auszug aus der Original-Datei ist (roher
        // CTE-Rumpf oder das raw `.text` eines Nicht-CTE-Knotens) gibt es eine sinnvolle Cursor-
        // Zuordnung - beim `SELECT * FROM name`-Wrapper (rekursive CTE) nicht.
        val sourceCaretMapping = when {
            targetBody != null -> SourceCaretMapping(targetBody, mainStatementOffset)
            node.kind != NodeKind.CTE -> SourceCaretMapping(node.element, mainStatementOffset)
            else -> null
        }
        return BuildResult(sql, highlightRanges, sourceCaretMapping)
    }
}
