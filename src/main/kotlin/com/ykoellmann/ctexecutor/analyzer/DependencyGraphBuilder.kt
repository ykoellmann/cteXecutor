package com.ykoellmann.ctexecutor.analyzer

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.elementType
import com.intellij.sql.psi.SqlElementTypes
import com.ykoellmann.ctexecutor.model.*

/**
 * Roher, noch nicht vernetzter Knoten waehrend des Graph-Aufbaus. Wird erst nach vollstaendiger
 * Entdeckung aller Knoten (discoverScope) zu einem verdrahteten DependencyNode (mit dependencyIds/
 * consumerIds) aufgeloest, weil computeDependencyIds Sichtbarkeit auf ALLE anderen Knoten braucht
 * (Shadowing-Tiebreak, siehe discoverScope).
 */
private data class RawNode(
    val kind: NodeKind,
    val displayName: String,
    val element: PsiElement,
    val isRecursive: Boolean = false
) {
    fun toDependencyNode(id: NodeId, dependencyIds: List<NodeId>): DependencyNode =
        DependencyNode(
            id = id,
            kind = kind,
            displayName = displayName,
            element = element,
            isRecursive = isRecursive,
            dependencyIds = dependencyIds
        )
}

class DependencyGraphBuilder(
    private val resolver: TableReferenceResolver = PsiResolvingResolver()
) {
    fun build(root: PsiElement): DependencyGraph {
        val raw = mutableMapOf<NodeId, RawNode>()
        discoverScope(root, parentScope = null, raw)

        val depCache = mutableMapOf<NodeId, List<NodeId>>()
        fun dependenciesOf(id: NodeId): List<NodeId> =
            depCache.getOrPut(id) { computeDependencyIds(id, raw[id]!!, raw, ::dependenciesOf) }

        val withDeps = raw.mapValues { (id, node) -> node.toDependencyNode(id, dependenciesOf(id)) }
        val consumersById = mutableMapOf<NodeId, MutableList<NodeId>>()
        withDeps.forEach { (id, node) ->
            node.dependencyIds.filter { it != id }   // Selbstschleife (RECURSIVE) nicht als "Konsument von sich selbst" zaehlen
                .forEach { depId -> consumersById.getOrPut(depId) { mutableListOf() }.add(id) }
        }
        return DependencyGraph(withDeps.mapValues { (id, n) -> n.copy(consumerIds = consumersById[id].orEmpty()) })
    }

    // ---------------------------------------------------------------------------------------
    // Entdeckung: findet ALLE Knoten in einem Durchlauf. `parentScope` wird aktuell nicht
    // benoetigt (Shadowing wird nicht waehrend der Entdeckung, sondern strukturell erst in
    // computeDependencyIds() pro Referenzstelle aufgeloest, siehe dort) - bleibt Teil der
    // Signatur, weil die ueffentliche build()-Schnittstelle/das im Handoff skizzierte Geruest
    // sie vorsieht.
    // ---------------------------------------------------------------------------------------
    private fun discoverScope(root: PsiElement, parentScope: NodeId?, raw: MutableMap<NodeId, RawNode>) {
        findAllWithClauses(root).forEach { withClause -> discoverWithClause(withClause, raw) }
        discoverSubqueryCandidates(root, raw)
    }

    private fun findAllWithClauses(root: PsiElement): List<PsiElement> {
        val result = mutableListOf<PsiElement>()
        fun walk(element: PsiElement) {
            if (element.elementType == SqlElementTypes.SQL_WITH_CLAUSE) result.add(element)
            element.children.forEach(::walk)
        }
        walk(root)
        return result
    }

    private fun isRecursiveWithClause(withClause: PsiElement): Boolean =
        withClause.children.any { it.text.equals("RECURSIVE", ignoreCase = true) }

    /** True fuer die WITH-Klausel, die direkt unter dem Datei-Wurzel-SELECT haengt (kein umschliessender Kontext). */
    private fun isTopLevelWithClause(withClause: PsiElement): Boolean =
        withClause.parent?.parent?.parent is PsiFile

    private fun discoverWithClause(withClause: PsiElement, raw: MutableMap<NodeId, RawNode>) {
        val scopeId = AnonymousNodeId(withClause)
        val recursiveClause = isRecursiveWithClause(withClause)

        withClause.children
            .filter { it.elementType == SqlElementTypes.SQL_NAMED_QUERY_DEFINITION }
            .forEach { cteElement ->
                val name = extractIdentifierName(cteElement) ?: return@forEach
                val cteId = NamedNodeId(name.lowercase(), scopeId)
                val bodySlot = findBodySlot(cteElement) ?: return@forEach
                val selfReferenced = recursiveClause && collectSelfReference(bodySlot, name)

                if (bodySlot.elementType == SqlElementTypes.SQL_UNION_EXPRESSION) {
                    registerUnionBranches(bodySlot, raw)
                }
                raw[cteId] = RawNode(NodeKind.CTE, name, cteElement, selfReferenced)
            }

        // Anschliessende Query dieser WITH-Klausel (das, was "WITH ... <hier>" ausfuehrt).
        // Nur die oberste WITH-Klausel wird als MAIN_QUERY behandelt - alles andere (verschachtelte
        // WITH-Klauseln in einer Subquery-Position) ist selbst wieder ein Subquery-Kandidat und wird
        // von discoverSubqueryCandidates() ueber seine SQL_WITH_QUERY_EXPRESSION-Huelle erfasst.
        if (isTopLevelWithClause(withClause)) {
            val trailingSlot = withClause.parent?.children
                ?.firstOrNull { it != withClause && it !is PsiWhiteSpace }
                ?: return
            if (trailingSlot.elementType == SqlElementTypes.SQL_UNION_EXPRESSION) {
                registerUnionBranches(trailingSlot, raw)
            }
            raw[AnonymousNodeId(trailingSlot)] = RawNode(NodeKind.MAIN_QUERY, "Main Query", trailingSlot)
        }
    }

    private fun registerUnionBranches(unionExpression: PsiElement, raw: MutableMap<NodeId, RawNode>) {
        unionExpression.children
            .filter { it.elementType == SqlElementTypes.SQL_QUERY_EXPRESSION }
            .forEach { branch ->
                raw[AnonymousNodeId(branch)] = RawNode(NodeKind.UNION_BRANCH, "UNION branch (${lineOf(branch)})", branch)
            }
    }

    /**
     * Findet alle unkorrelierten Subquery-/LATERAL-Kandidaten im Baum. Ausgeschlossen sind Bereiche,
     * die bereits einem anderen Knoten gehoeren (CTE-Rumpf, UNION-Branch, Main-Query-Slot) - deren
     * Content gehoert dem jeweiligen Besitzer, nicht der umschliessenden Subquery.
     */
    private fun discoverSubqueryCandidates(root: PsiElement, raw: MutableMap<NodeId, RawNode>) {
        fun walk(element: PsiElement) {
            // Nur die REGISTRIERUNG als eigener Knoten wird uebersprungen, wenn dieses exakte Element
            // schon einem Knoten gehoert (CTE-Rumpf, UNION-Branch, Main-Query-Slot) - der Rekursions-
            // abstieg in seine Kinder laeuft trotzdem weiter, sonst wuerden Subqueries, die INNERHALB
            // eines bereits vergebenen Knotens liegen (z.B. eine Subquery innerhalb der Main-Query),
            // nie entdeckt.
            val alreadyOwned = raw.containsKey(AnonymousNodeId(element))

            val isCandidateSlot =
                !alreadyOwned &&
                    element.elementType == SqlElementTypes.SQL_QUERY_EXPRESSION &&
                    element.parent?.elementType != SqlElementTypes.SQL_NAMED_QUERY_DEFINITION &&
                    element.parent?.elementType != SqlElementTypes.SQL_UNION_EXPRESSION

            if (isCandidateSlot) {
                val correlated = isCorrelated(element)
                if (!correlated) {
                    val lateral = isLateralPosition(element)
                    raw[AnonymousNodeId(element)] = RawNode(
                        kind = if (lateral) NodeKind.LATERAL else NodeKind.SUBQUERY,
                        displayName = if (lateral) "LATERAL Subquery (${lineOf(element)})" else "Subquery (${lineOf(element)})",
                        element = element
                    )
                    // Korrelierte/unkorrelierte Kinder dieses jetzt eigenstaendigen Knotens werden im
                    // naechsten discoverSubqueryCandidates()-Rekursionsschritt separat entdeckt.
                }
                // korreliert: kein eigener Knoten - Referenzen werden beim naechsten uebergeordneten
                // Aufruf von computeDependencyIds() automatisch mit erfasst, weil hier kein Owned-Node
                // entsteht, der die Kind-Elemente aus dem Walk der Eltern ausschliessen wuerde.
            }

            element.children.forEach(::walk)
        }
        walk(root)
    }

    /**
     * Korrelations-Check (Spike-Test 3, siehe HANDOFF Abschnitt 6): fuer jede Tabellenreferenz
     * innerhalb von [candidate] wird per PSI-Reference-Resolving geprueft, ob sich ihre Deklaration
     * ausserhalb der TextRange von [candidate] befindet. Trifft das auf irgendeine Referenz zu, ist
     * die Subquery korreliert und bekommt keinen eigenen Knoten (Auswahlbarkeits-Regel, Abschnitt 5).
     */
    private fun isCorrelated(candidate: PsiElement): Boolean {
        // Nur TABELLEN-/ALIAS-Ebene-Referenzen sind fuer Korrelation relevant: SQL_TABLE_REFERENCE
        // (ein Tabellen-/CTE-Name in FROM) und SQL_REFERENCE (der Alias-Qualifizierer `o` in `o.spalte`,
        // per PSI-Baum-Dump verifiziert - der Alias-Bezug haengt an diesem inneren Blatt, nicht am
        // umschliessenden SQL_COLUMN_REFERENCE). Ausdruecklich NICHT jede beliebige Spaltenreferenz
        // (SQL_COLUMN_REFERENCE) pruefen: eine Spalte wie `amount` in `SELECT amount FROM orders`
        // loest ganz normal zur Spaltendefinition der referenzierten CTE/Tabelle auf, die textlich
        // immer ausserhalb der eigenen Subquery liegt (die CTE steht ja davor) - das waere sonst ein
        // False Positive bei JEDER Subquery, die ueberhaupt eine Spalte selektiert.
        //
        // Aufloesungen auf eine SQL_NAMED_QUERY_DEFINITION (= eine CTE) zaehlen NICHT als Korrelation,
        // selbst wenn sie ausserhalb der eigenen TextRange liegen: eine CTE-Referenz ist eine legitime,
        // eigenstaendig aufloesbare Abhaengigkeit (wird in computeDependencyIds() zu einer dependencyId),
        // keine Korrelation zu einer umschliessenden Zeile. Korrelation liegt nur vor, wenn die
        // Aufloesung auf etwas ausserhalb der eigenen Range zeigt, das KEINE CTE ist (z.B. ein
        // Tabellen-Alias einer umschliessenden FROM-Klausel, siehe Spike-Test 3).
        val range = candidate.textRange
        var correlated = false
        fun walk(element: PsiElement) {
            if (correlated) return
            if (element.elementType == SqlElementTypes.SQL_TABLE_REFERENCE || element.elementType == SqlElementTypes.SQL_REFERENCE) {
                for (reference in element.references) {
                    val resolved = reference.resolve()
                    // resolved.textRange ist null, wenn die Referenz auf etwas AUSSERHALB dieser
                    // Datei aufloest (z.B. eine echte Tabelle/Spalte aus einer verbundenen
                    // Datenbank-Quelle statt einer CTE) - das ist eine ganz normale externe
                    // Referenz, keine Korrelation zu einer umschliessenden Zeile in DIESEM Skript.
                    val resolvedRange = resolved?.takeIf { it.isValid }?.textRange
                    if (resolved != null &&
                        resolved.elementType != SqlElementTypes.SQL_NAMED_QUERY_DEFINITION &&
                        resolvedRange != null &&
                        !range.contains(resolvedRange)
                    ) {
                        correlated = true
                        return
                    }
                }
            }
            element.children.forEach(::walk)
        }
        walk(candidate)
        return correlated
    }

    private fun isLateralPosition(candidate: PsiElement): Boolean {
        var current: PsiElement? = candidate.parent
        while (current != null && current.elementType != SqlElementTypes.SQL_FROM_CLAUSE) {
            if (current.elementType == SqlElementTypes.SQL_EXPLICIT_TABLE_EXPRESSION &&
                current.children.any { it.text.equals("LATERAL", ignoreCase = true) }
            ) return true
            current = current.parent
        }
        return false
    }


    private fun extractIdentifierName(cteElement: PsiElement): String? =
        cteElement.children.firstOrNull { it.elementType == SqlElementTypes.SQL_IDENTIFIER }?.text

    private fun collectSelfReference(bodySlot: PsiElement, cteName: String): Boolean {
        val refs = mutableSetOf<String>()
        collectTableRefs(bodySlot, refs)
        return refs.any { it.equals(cteName, ignoreCase = true) }
    }

    private fun lineOf(element: PsiElement): String {
        val document = element.containingFile?.viewProvider?.document
        val line = document?.getLineNumber(element.textRange.startOffset)?.plus(1)
        return if (line != null) "line $line" else "?"
    }

    // ---------------------------------------------------------------------------------------
    // Abhaengigkeiten: fuer jeden Knoten werden die direkt referenzierten CTE-Namen ermittelt und
    // ueber den Shadowing-Tiebreak (naechste umschliessende WITH-Klausel relativ zur Referenzstelle,
    // Spike-Test 2) zur richtigen scope-qualifizierten NamedNodeId aufgeloest. Bewusst NICHT ueber
    // resolver.collectReferences()/PsiReference.resolve() fuer CTE-Namen: Spike-Test 2 zeigt, dass
    // PSI-Resolving bei Namenskollisionen zwischen Scopes eine echte Mehrdeutigkeit liefert
    // (multiResolve() = 2 Kandidaten), .resolve() liefert dabei nur zufaellig den ersten Kandidaten
    // der Liste - kein verlaesslicher proximitaetsbasierter Tiebreak. `resolver` bleibt Parameter/
    // Erweiterungspunkt (z.B. fuer eine robustere Referenz-Erkennung in dialektspezifischen Faellen),
    // wird fuer den CTE-Shadowing-Fall aber nicht herangezogen.
    // ---------------------------------------------------------------------------------------
    private fun computeDependencyIds(
        id: NodeId,
        node: RawNode,
        allNodes: Map<NodeId, RawNode>,
        dependenciesOf: (NodeId) -> List<NodeId>
    ): List<NodeId> {
        val cteNamesByScope: Map<NodeId, Set<String>> = allNodes.keys
            .filterIsInstance<NamedNodeId>()
            .groupBy({ it.scope as NodeId }, { it.name })
            .mapValues { it.value.toSet() }

        val idByElement: Map<PsiElement, NodeId> = allNodes.entries.associate { (id, n) -> n.element to id }

        val result = mutableSetOf<NodeId>()

        // Ein SUBQUERY-/UNION_BRANCH-/CTE-Knoten, der TEXTLICH innerhalb von [node] liegt (z.B. eine
        // unkorrelierte FROM-Subquery innerhalb der Main-Query), gehoert einem ANDEREN Knoten - dessen
        // Referenzen werden deshalb nicht hier erneut aufgeloest (kein doppeltes Attribuieren). Seine
        // bereits berechneten Abhaengigkeiten werden aber uebernommen: [node]s zusammengebauter SQL-
        // Text enthaelt den fremden Knoten WOERTLICH (siehe DependencyGraphSqlBuilder), braucht also
        // exakt dieselben externen CTEs wie dieser.
        fun ownedElsewhereId(element: PsiElement): NodeId? =
            if (element == node.element) null else idByElement[element]

        fun walk(element: PsiElement) {
            ownedElsewhereId(element)?.let { nestedId ->
                result.addAll(dependenciesOf(nestedId))
                return
            }
            if (element.elementType == SqlElementTypes.SQL_TABLE_REFERENCE) {
                // Qualifizierte Referenzen (`public.orders`) haben einen "."-Token als direktes Kind -
                // CTE-Namen werden per SQL-Standard immer UNQUALIFIZIERT referenziert, ein qualifizierter
                // Name kann also nie eine CTE meinen (Testfall QUALIFIED_REFERENCE_COLLISION).
                val isQualified = element.children.any { it.text == "." }
                val name = if (isQualified) null else element.children
                    .firstOrNull { it.elementType == SqlElementTypes.SQL_IDENTIFIER }
                    ?.text
                    ?.lowercase()
                if (name != null) {
                    resolveByShadowing(element, name, cteNamesByScope)?.let { result.add(it) }
                }
            }
            element.children.forEach(::walk)
        }
        walk(node.element)
        return result.toList()
    }

    /**
     * Laeuft von [reference] aus die Elternkette nach oben und prueft an jeder Ebene zwei Faelle, in
     * denen eine WITH-Klausel "in Scope" ist: (a) die Referenz liegt SELBST innerhalb einer
     * SQL_WITH_CLAUSE (z.B. eine CTE referenziert eine andere CTE derselben oder einer aeusseren
     * WITH-Klausel), oder (b) die aktuelle Ebene hat eine SQL_WITH_CLAUSE als GESCHWISTER (die
     * anschliessende Query einer WITH-Klausel - `WITH x AS (...) SELECT ...` - ist selbst kein
     * Nachfahre der WITH_CLAUSE, sondern ihr Geschwisterkind unter SQL_WITH_QUERY_EXPRESSION).
     * Die naechste (innerste) passende WITH-Klausel gewinnt - Shadowing, strukturell statt ueber
     * PSI-Resolving geloest (siehe computeDependencyIds oben).
     */
    private fun resolveByShadowing(
        reference: PsiElement,
        name: String,
        cteNamesByScope: Map<NodeId, Set<String>>
    ): NodeId? {
        fun matchIn(withClause: PsiElement): NodeId? {
            val scopeId = AnonymousNodeId(withClause)
            return if (cteNamesByScope[scopeId]?.contains(name) == true) NamedNodeId(name, scopeId) else null
        }

        var current: PsiElement? = reference
        while (current != null) {
            if (current.elementType == SqlElementTypes.SQL_WITH_CLAUSE) {
                matchIn(current)?.let { return it }
            }
            val parent = current.parent
            parent?.children
                ?.filter { it.elementType == SqlElementTypes.SQL_WITH_CLAUSE && it != current }
                ?.forEach { sibling -> matchIn(sibling)?.let { return it } }
            current = parent
        }
        return null
    }
}

/**
 * Content zwischen den Klammern eines CTE-Rumpfes: eine SQL_QUERY_EXPRESSION oder SQL_UNION_EXPRESSION.
 * Top-level (nicht Teil der Klasse) und `internal`, damit DependencyGraphSqlBuilder denselben Rumpf
 * fuer die Ausfuehrung extrahieren kann, den DependencyGraphBuilder schon fuer die Entdeckung nutzt -
 * eine einzige Quelle fuer "was ist der Rumpf einer CTE", statt einer zweiten, potenziell
 * abweichenden Implementierung (genau das war der Fehler bei SqlBuilder vs. SqlAnalyzer im Altsystem,
 * siehe Diskussion in claude/HANDOFF.md).
 */
internal fun findBodySlot(cteElement: PsiElement): PsiElement? {
    val children = cteElement.children
    val leftIdx = children.indexOfFirst { it.elementType == SqlElementTypes.SQL_LEFT_PAREN }
    val rightIdx = children.indexOfLast { it.elementType == SqlElementTypes.SQL_RIGHT_PAREN }
    if (leftIdx < 0 || rightIdx <= leftIdx) return null
    return children.toList().subList(leftIdx + 1, rightIdx).firstOrNull { it !is PsiWhiteSpace }
}
