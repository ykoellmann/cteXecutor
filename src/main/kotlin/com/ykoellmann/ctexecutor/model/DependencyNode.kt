package com.ykoellmann.ctexecutor.model

import com.intellij.psi.PsiElement

enum class NodeKind { CTE, SUBQUERY, UNION_BRANCH, LATERAL, MAIN_QUERY }
// LATERAL bleibt nur fuer Display-Zwecke ("LATERAL Subquery" statt "Subquery") und den seltenen
// unkorrelierten Randfall. In aller Regel ist LATERAL korreliert und wird deshalb gar nicht erst
// zu einem eigenen Knoten (siehe DependencyGraphBuilder.discoverScope, Korrelationscheck).

sealed interface NodeId

data class NamedNodeId(val name: String, val scope: NodeId?) : NodeId
// scope = ID des umschliessenden WITH-Knotens (bzw. dessen Traeger-Element), null = oberste Ebene.
// WICHTIG: das ist scope-qualifiziert, NICHT einfach ein String-Name. Zwei CTEs mit gleichem Namen
// in unterschiedlichen, verschachtelten WITH-Klauseln sind unterschiedliche Knoten (Shadowing).
// Durch den PSI-Resolving-Spike bestaetigt: IntelliJs eigenes PSI-Resolving loest bei
// Namenskollisionen NICHT automatisch zur naeheren CTE auf, sondern liefert eine echte
// Mehrdeutigkeit. Die Entscheidung "welche CTE ist gemeint" trifft NICHT der Resolver, sondern
// discoverScope()/computeDependencyIds() strukturell, ueber die naechste umschliessende Scope
// relativ zur Referenzstelle.

data class AnonymousNodeId(val element: PsiElement) : NodeId
// Fuer alles ausser CTEs: PSI-Element-Identitaet als Schluessel.
// ACHTUNG: Gleichheit haengt an einer konkreten PSI-Baum-Instanz. Niemals ueber zwei separate
// build()-Aufrufe hinweg vergleichen oder speichern (auch nicht zwischen zwei Tastendruecken, falls
// das Dokument dazwischen editiert wurde) - sonst stiller, spaeter Bug. Unproblematisch, solange
// strikt "kein Zustand ueber einen build()-Aufruf hinaus" gilt (Neuberechnung statt Cache).

data class DependencyNode(
    val id: NodeId,
    val kind: NodeKind,
    val displayName: String,           // CTE-Name, sonst z.B. "Subquery in FROM (Zeile 9)"
    val element: PsiElement,           // wie heutiges CteEntry.element
    val isRecursive: Boolean = false,  // true bei WITH-RECURSIVE-CTEs mit Selbstreferenz
    val dependencyIds: List<NodeId> = emptyList(),   // "nach oben" im Popup - nur 1 Hop
    val consumerIds: List<NodeId> = emptyList()      // "nach unten" im Popup - durch Invertierung berechnet
)

class DependencyGraph(private val nodesById: Map<NodeId, DependencyNode>) {
    fun resolve(id: NodeId): DependencyNode = nodesById.getValue(id)

    fun allNodes(): Collection<DependencyNode> = nodesById.values

    fun nodesOf(kind: NodeKind): List<DependencyNode> = nodesById.values.filter { it.kind == kind }

    fun allCtesNamed(name: String): List<DependencyNode> =
        nodesById.entries.filter { (id, _) -> id is NamedNodeId && id.name == name.lowercase() }.map { it.value }

    // Generalisiert das heutige locateCursor()/classifyCteScope()/classifyOuterScope()/
    // isDirectCteBodySelect()/isUnionContainer() (zusammen ~60 Zeilen in SqlAnalyzer.kt) auf 3 Zeilen:
    // kleinstes Element, dessen TextRange den Caret-Offset enthaelt, gewinnt.
    fun nodeAt(offset: Int): DependencyNode? =
        nodesById.values
            .filter { it.element.textRange.contains(offset) }
            .minByOrNull { it.element.textRange.length }

    // Transitive Abhaengigkeiten fuer den SQL-Zusammenbau (Nachfolger von SqlBuilder).
    // Faengt die Selbstschleife einer rekursiven CTE (isRecursive) explizit ab (Visited-Set),
    // generalisiert den Schutzmechanismus, den SqlAnalyzer.buildDependencyGraph heute schon hat
    // (dort: `it != cte.name.lowercase()` beim Graph-Bau).
    fun transitiveDependencies(id: NodeId): List<DependencyNode> {
        val visited = mutableSetOf<NodeId>()
        fun walk(current: NodeId) {
            if (!visited.add(current)) return
            resolve(current).dependencyIds.forEach(::walk)
        }
        walk(id)
        visited.remove(id)
        return visited.map(::resolve)
    }
}

fun DependencyNode.dependencies(graph: DependencyGraph): List<DependencyNode> = dependencyIds.map(graph::resolve)
fun DependencyNode.consumers(graph: DependencyGraph): List<DependencyNode> = consumerIds.map(graph::resolve)
