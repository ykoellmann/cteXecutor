# cteXecutor — Handoff: Dependency-Graph-Rewrite

**Stand: 2026-09-17. Branch `feature/dependency-graph` (von `main`). Nichts hiervon ist committed.**

## 0. Zweck dieses Dokuments

Dies ist ein vollständiger, in sich geschlossener Handoff für eine neue Claude-Code-Session ohne
Zugriff auf die vorherige Planungs-Konversation mit Yannik. Alles, was zum Weiterarbeiten nötig ist,
steht hier — Ziel, Architektur, Entscheidungen samt Begründung, Spike-Ergebnisse, offene Punkte und
der konkrete nächste Auftrag. Bei Widersprüchen zwischen diesem Dokument und Code-Kommentaren im
Repo gilt: dieses Dokument ist aktueller (es wurde zuletzt geschrieben), aber im Zweifel den
tatsächlichen Code-Stand im Repo prüfen, nicht blind diesem Text vertrauen.

Paralleles Dokument im claude.ai-Project "ctexecutor" (`claude/rewrite-analyse-und-plan.md`) enthält
dieselben Inhalte plus den vollen Diskussionsverlauf (Mockup-Iterationen, Rückfragen, Korrekturen).
Dieses Handoff hier ist die destillierte, handlungsorientierte Version davon, direkt im Repo.

## 1. Auftrag & Zielbild

cteXecutor ist ein veröffentlichtes JetBrains/DataGrip-Plugin (IDs: `com.ykoellmann.ctexecutor.ctexecutor`,
aktuell v3.0.x, GitHub `ykoellmann/ctexecutor`). Es lässt Nutzer einzelne CTEs (Common Table Expressions)
aus einer SQL-WITH-Klausel gezielt ausführen: Popup öffnen, CTE wählen, das Plugin baut automatisch die
benötigten Abhängigkeiten (andere CTEs) zusammen, pastet das resultierende SQL ans Dokumentende und
feuert JetBrains' native DB-Konsolen-Execute-Action darauf.

**Ziel des Rewrites:** das Dependency-Modell generalisieren. Nicht mehr nur CTEs, sondern auch
UNION-Branches, Subqueries und LATERAL JOINs sollen als navigierbare Knoten in einem Abhängigkeitsbaum
erscheinen, den man von der Cursor-Position aus in beide Richtungen (Abhängigkeiten / Konsumenten)
durchqueren kann — mit einem neuen Popup (Design ist fertig, siehe Abschnitt 7). Die eigentliche
SQL-Ausführung (Paste-Trick + native DB-Engine) bleibt exakt wie heute; der neue Baum bestimmt nur,
WELCHER SQL-Teil zusammengebaut und ausgeführt wird.

## 2. Harte Leitplanken (nicht verhandelbar ohne Rückfrage an Yannik)

- **Ausführung bleibt Paste + native DB-Konsolen-Action.** Kein eigener DB-Treiber, keine eigene
  Verbindung. `SqlExecutor.kt` bleibt strukturell unverändert (siehe Abschnitt 3).
- **Springen ≠ Ausführen.** Navigation im neuen Popup darf niemals automatisch SQL ausführen — nur der
  explizite Enter-Schritt auf dem aktuell fokussierten Knoten.
- **Additiver, nicht-brechender Rollout.** Die 3 bestehenden, veröffentlichten Actions
  (`ExecuteFromHereAction`, `CopyAction`, `CopyAndEditAction`) und die bestehende "alle CTEs
  anzeigen"-Funktion dürfen bis zum allerletzten Rollout-Schritt nicht verändert werden. Neuer Code
  entsteht parallel, wird erst ganz am Ende scharf geschaltet (Details: Abschnitt 8).
- **Variablen/prozedurale SQL-Konstrukte sind explizit außerhalb des v1-Scopes.** Grund: Live-DB-
  Session-Zustand ist vom Skript aus nicht einsehbar — automatisches Neu-Setzen von Variablen könnte
  manuell gepflegte Werte überschreiben. Nicht wieder aufmachen ohne diese Sicherheitsfrage neu zu
  bewerten.
- **Dialektneutraler Kern.** Immer `com.intellij.sql.psi.SqlElementTypes` nutzen (dialektübergreifende
  PSI-Typen der IntelliJ-Plattform), keine dialektspezifischen Imports im Kern-Datenmodell. Dialekt-
  Sonderfälle (z. B. `LATERAL` vs. `CROSS APPLY`) sind Erweiterungspunkte, nicht Kernlogik.

## 3. Ist-Architektur (aktueller Code im Repo)

**Pipeline:** `SqlActionBase.actionPerformed()` → `SqlAnalyzer.analyze()` → `buildPopupOptions()` →
JBPopup → `handleSelectedOption()` → (je nach Action) Clipboard / Editor-Insert / `SqlExecutor.executeSqlSeamlessly()`.

### `src/main/kotlin/com/ykoellmann/ctexecutor/analyzer/SqlAnalyzer.kt`

Zentrale Analyse-Klasse, nimmt `(file: PsiFile, caretOffset: Int)`.

- `CteEntry(name: String, element: PsiElement, index: Int)` — eine CTE.
- `CursorScope` (sealed class): `InsideCte(cte)`, `InsideSubselect(subselect, containingCte)`,
  `InsideUnionBranch(branch, unionBody, containingCte)`, `InsideMainQuery(element)`.
- `SqlContext(allCtes, dependencyGraph: Map<String, Set<String>>, cursorScope, mainQuery, withClause)`
  mit `getTransitiveDependencies()`, `getMainQueryDependencies()`, `getRequiredCtesForElement()`.
- `analyze()`: findet umschließende `SQL_WITH_CLAUSE` ab Caret-Offset (`findWithClause`), extrahiert
  alle CTEs (`extractAllCtes`, filtert `SQL_NAMED_QUERY_DEFINITION`-Kinder der WITH-Klausel, Name via
  erstem `SQL_IDENTIFIER`-Kind), baut den Dependency-Graph (`buildDependencyGraph`), klassifiziert die
  Cursor-Position (`locateCursor`, läuft die PSI hoch und unterscheidet die vier `CursorScope`-Fälle
  über `isDirectCteBodySelect`/`isUnionContainer`).
- `buildDependencyGraph`/`collectBodyReferences`/`collectTableRefs`: **reines String-Matching, kein
  echtes PSI-Reference-Resolving.** `collectTableRefs` sammelt rekursiv den ersten `SQL_IDENTIFIER`
  jedes `SQL_TABLE_REFERENCE`-Elements im Baum. Selbstreferenzen werden beim Graph-Bau explizit
  rausgefiltert (`it != cte.name.lowercase()`), damit rekursive CTEs heute schon keine Endlosschleife
  auslösen — derselbe Schutz wird im neuen Modell generalisiert (Abschnitt 4.1, `transitiveDependencies`).

### `src/main/kotlin/com/ykoellmann/ctexecutor/analyzer/SqlBuilder.kt`

Reiner Renderer: `BuildResult(sql, highlightRanges)`, `HighlightMode`-Enum. Aus CTE-Liste + Zielelement
→ ausführbarer SQL-String (WITH-Klausel mit benötigten CTEs + Zielquery) + `TextRange`s fürs Highlighting.

### `src/main/kotlin/com/ykoellmann/ctexecutor/actions/`

- `SqlActionBase.kt` — abstrakte Basis, baut aus `SqlContext` eine flache Popup-Options-Liste, rendert
  das `JBPopup`. Bleibt für die bestehenden 3 Actions unverändert.
- `SqlExecutor.kt` — **keine eigene DB-Verbindung.** Pastet SQL ans Dokumentende (undo-transparent),
  selektiert es, feuert synthetisch `Console.Jdbc.Execute`/`Console.Execute`/`Console.Execute.Multiline`,
  löscht den eingefügten Text danach über zwei `invokeLater`-Runden wieder.
- `ExecuteFromHereAction.kt`, `CopyAction.kt`, `CopyAndEditAction.kt` — dünne Subklassen von `SqlActionBase`.

### `src/main/resources/META-INF/plugin.xml`

Plugin-ID `com.ykoellmann.ctexecutor.ctexecutor`. 3 Actions unter `Ctrl+#`-Shortcut-Chords:
`Enter`/`Space` (ExecuteFromHere), `C` (Copy), `W` (CopyAndEdit). **Freie zweite Tasten für eine neue
Action: alles außer diesen drei.**

### `build.gradle.kts`

- Kotlin 2.2.20, `org.jetbrains.intellij.platform` Gradle-Plugin **2.10.5** (veraltet, siehe Abschnitt 10 —
  NICHT auf 2.19.0 heben, das verlangt Gradle ≥ 9.0.0, aktueller Wrapper ist 8.14.2, größere Migration).
- `create("DB", "2026.1.2")`, `sinceBuild = "252"`, JVM 21.
- `testFramework(TestFrameworkType.Platform)`, `bundledPlugin("com.intellij.database")`,
  `testImplementation("junit:junit:4.13.2")` (letzteres wurde in dieser Session ergänzt, siehe Abschnitt 10 —
  ohne das: `Cannot access 'junit.framework.TestCase'`, da `BasePlatformTestCase`/`UsefulTestCase` von
  JUnit-3-`TestCase` erben und `TestFrameworkType.Platform` das nicht automatisch mitbringt).

## 4. Zielarchitektur — Datenmodell & Analyse-Pipeline

**Leitgedanke:** kein Parallel-PSI, kein eigenständiger Baum losgelöst von IntelliJ. Ein schlanker
Index/Overlay über der bestehenden IntelliJ-PSI — exakt das Muster, das `CteEntry` heute schon nutzt
(Knoten = Wrapper um ein `PsiElement` + Metadaten), generalisiert auf mehr Konstrukt-Typen.

### 4.1 `model/DependencyNode.kt` (neu)

```kotlin
package com.ykoellmann.ctexecutor.model

import com.intellij.psi.PsiElement

enum class NodeKind { CTE, SUBQUERY, UNION_BRANCH, LATERAL, MAIN_QUERY }
// LATERAL bleibt nur fuer Display-Zwecke ("LATERAL Subquery" statt "Subquery") und den seltenen
// unkorrelierten Randfall. In aller Regel ist LATERAL korreliert und wird deshalb gar nicht erst
// zu einem eigenen Knoten (siehe 4.3, discoverScope-Korrelationscheck).

sealed interface NodeId
data class NamedNodeId(val name: String, val scope: NodeId?) : NodeId
// scope = ID des umschliessenden WITH-Knotens (bzw. dessen Traeger-Element), null = oberste Ebene.
// WICHTIG: das ist scope-qualifiziert, NICHT einfach ein String-Name. Grund: zwei CTEs mit gleichem
// Namen in unterschiedlichen, verschachtelten WITH-Klauseln sind unterschiedliche Knoten (Shadowing).
// Durch den PSI-Resolving-Spike (Abschnitt 6) bestaetigt: das ist zwingend noetig, nicht nur Vorsicht -
// IntelliJs eigenes PSI-Resolving loest bei Namenskollisionen NICHT automatisch zur naeheren CTE auf,
// sondern liefert eine echte Mehrdeutigkeit (siehe 6, Test 2). Die Entscheidung "welche CTE ist
// gemeint" trifft NICHT der Resolver, sondern discoverScope()/computeDependencyIds() strukturell,
// ueber die naechste umschliessende Scope relativ zur Referenzstelle.

data class AnonymousNodeId(val element: PsiElement) : NodeId
// Fuer alles ausser CTEs: PSI-Element-Identitaet als Schluessel.
// ACHTUNG: Gleichheit haengt an einer konkreten PSI-Baum-Instanz. Niemals ueber zwei separate
// build()-Aufrufe hinweg vergleichen oder speichern (auch nicht zwischen zwei Tastendruecken, falls
// das Dokument dazwischen editiert wurde) - sonst stiller, spaeter Bug. Unproblematisch, solange
// strikt "kein Zustand ueber einen build()-Aufruf hinaus" gilt (siehe 4.4, Neuberechnung statt Cache).

data class DependencyNode(
    val id: NodeId,
    val kind: NodeKind,
    val displayName: String,           // CTE-Name, sonst z.B. "Subquery in FROM (Zeile 9)"
    val element: PsiElement,           // wie heutiges CteEntry.element
    val isRecursive: Boolean = false,  // true bei WITH-RECURSIVE-CTEs mit Selbstreferenz
    val dependencyIds: List<NodeId>,   // "nach oben" im Popup - nur 1 Hop
    val consumerIds: List<NodeId>      // "nach unten" im Popup - durch Invertierung berechnet
)

class DependencyGraph(private val nodesById: Map<NodeId, DependencyNode>) {
    fun resolve(id: NodeId): DependencyNode = nodesById.getValue(id)

    // Generalisiert das heutige locateCursor()/classifyCteScope()/classifyOuterScope()/
    // isDirectCteBodySelect()/isUnionContainer() (zusammen ~60 Zeilen in SqlAnalyzer.kt) auf 3 Zeilen:
    // kleinstes Element, dessen TextRange den Caret-Offset enthaelt, gewinnt.
    fun nodeAt(offset: Int): DependencyNode? =
        nodesById.values
            .filter { it.element.textRange.contains(offset) }
            .minByOrNull { it.element.textRange.length }

    // Transitive Abhaengigkeiten fuer den SQL-Zusammenbau (Nachfolger von SqlBuilder, siehe 4.5).
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
```

### 4.2 `analyzer/TableReferenceResolver.kt` (neu)

Entkoppelt "wie erkennen wir, dass ein Identifier eine Tabellen-/CTE-Referenz ist" vom Rest der
Pipeline. Durch den Spike (Abschnitt 6) bestaetigt: `PsiResolvingResolver` ist die Zielimplementierung.

```kotlin
package com.ykoellmann.ctexecutor.analyzer

import com.intellij.psi.PsiElement

interface TableReferenceResolver {
    fun collectReferences(element: PsiElement): Set<String>
}

class NameMatchingResolver : TableReferenceResolver {
    override fun collectReferences(element: PsiElement): Set<String> {
        val result = mutableSetOf<String>()
        collectTableRefs(element, result)   // bestehende Funktion aus SqlAnalyzer.kt, unveraendert
        return result
    }
}

class PsiResolvingResolver : TableReferenceResolver {
    override fun collectReferences(element: PsiElement): Set<String> {
        // TODO (Rollout-Schritt 2): echtes PSI-Reference-Resolving statt Text-Matching.
        // PsiTreeUtil ueber SQL_TABLE_REFERENCE-Kinder, .references / multiResolve() nutzen.
        // WICHTIG (Spike-Ergebnis, Abschnitt 6): liefert nur Namen zurueck. Die Scope-Zuordnung bei
        // mehreren gleichnamigen CTEs passiert NICHT hier - PSI liefert dort mehrdeutige Kandidaten,
        // kein Autopick -, sondern in DependencyGraphBuilder.discoverScope()/computeDependencyIds().
        TODO()
    }
}
```

`NameMatchingResolver` bleibt als Fallback/Vergleichsimplementierung im Code, falls sich der
PSI-Ansatz gegen komplexere Dialekte (insbesondere DB2-for-i, siehe Abschnitt 5) als unzuverlässig
erweist — der Spike lief nur gegen generisches/ANSI-SQL.

### 4.3 `analyzer/DependencyGraphBuilder.kt` (neu) — Gerüst, zwei Stellen bewusst offen

```kotlin
package com.ykoellmann.ctexecutor.analyzer

import com.intellij.psi.PsiElement
import com.ykoellmann.ctexecutor.model.*

class DependencyGraphBuilder(
    private val resolver: TableReferenceResolver = PsiResolvingResolver()
) {
    fun build(root: PsiElement): DependencyGraph {
        val raw = mutableMapOf<NodeId, RawNode>()
        discoverScope(root, parentScope = null, raw)
        val withDeps = raw.mapValues { (id, node) -> node.toDependencyNode(id, computeDependencyIds(node, raw, resolver)) }
        val consumersById = mutableMapOf<NodeId, MutableList<NodeId>>()
        withDeps.forEach { (id, node) ->
            node.dependencyIds.filter { it != id }   // Selbstschleife (RECURSIVE) nicht als "Konsument von sich selbst" zaehlen
                .forEach { depId -> consumersById.getOrPut(depId) { mutableListOf() }.add(id) }
        }
        return DependencyGraph(withDeps.mapValues { (id, n) -> n.copy(consumerIds = consumersById[id].orEmpty()) })
    }

    // TODO (Rollout-Schritt 2), gegen testutil/TestQueries.kt auszuarbeiten. Muss:
    //  - rekursiv jede WITH-Klausel finden (auch verschachtelt in Subqueries, nicht nur top-level;
    //    Yannik-Entscheidung: v1-Pflicht, siehe Abschnitt 5) -> eigener `scope`-Wert pro gefundener
    //    WITH-Klausel fuer die NamedNodeId.
    //  - RECURSIVE-Keyword pro CTE erkennen -> isRecursive = true, Selbstreferenz wird BEWUSST als
    //    dependencyId auf sich selbst aufgenommen (durch Spike-Test 4 bestaetigt: loest sauber auf).
    //  - UNION-Branches finden (Geschwister-Select im selben Container) -> eigener Knoten, kind = UNION_BRANCH.
    //  - Subqueries/LATERAL in FROM/WHERE/JOIN finden UND SOFORT AUF KORRELATION PRUEFEN:
    //      unkorreliert -> eigener Knoten (kind = SUBQUERY oder LATERAL, LATERAL selten)
    //      korreliert    -> KEIN eigener Knoten. Stattdessen werden die Referenzen dieses Konstrukts
    //                       dem naechsten NICHT-korrelierten Vorfahren zugeschlagen (hochgefaltet).
    //    Korrelations-Check (durch Spike-Test 3 bestaetigt): fuer eine Referenz `ref` innerhalb eines
    //    Subquery-Kandidaten `candidate`: `ref.reference?.resolve()?.textRange` liegt AUSSERHALB von
    //    `candidate.textRange` => korreliert.
    //  - Shadowing-Tiebreak (durch Spike-Test 2 bestaetigt als NOETIG, nicht ueber PSI-Resolving loesbar):
    //    bei mehreren RawNodes mit gleichem Namen in unterschiedlichen Scopes gewinnt der naechste
    //    umschliessende Scope relativ zur Referenzstelle - rein strukturell durch die PSI-Elternkette
    //    bestimmt (von der Referenz aus aufwaerts laufen, erste passende WITH-Klausel gewinnt).
    private fun discoverScope(root: PsiElement, parentScope: NodeId?, raw: MutableMap<NodeId, RawNode>) { TODO() }

    private fun computeDependencyIds(
        node: RawNode,
        allNodes: Map<NodeId, RawNode>,
        resolver: TableReferenceResolver
    ): List<NodeId> { TODO() }
}
```

### 4.4 Neuberechnung statt Cache (v1) — Entscheidung

`DependencyGraphBuilder.build()` läuft bei jedem Popup-Öffnen neu. Für realistische Skriptgrößen
günstig genug, keine persistente Cache-Invalidierung über PSI-Change-Listener nötig. Nicht vorzeitig
optimieren (YAGNI) — erst wenn Neuberechnung tatsächlich spürbar langsam ist.

### 4.5 Nachfolger von `SqlBuilder.kt`

Noch nicht im Detail entworfen (das ist Rollout-Schritt 3, siehe Abschnitt 8). Muss für CTE-Knoten
(heutiges Verhalten) UND für unkorrelierte SUBQUERY-/UNION_BRANCH-Knoten jeweils gültiges,
eigenständig ausführbares SQL erzeugen können. Muss `isRecursive` berücksichtigen (`WITH RECURSIVE`
statt `WITH` erzeugen, sobald eine benötigte CTE rekursiv ist). Da korrelierte Konstrukte gar nicht
erst zu Knoten werden (4.3), muss diese Schicht selbst KEINEN Korrelations-Sonderfall mehr behandeln —
alles, was ein `DependencyNode` ist, ist per Konstruktion vollständig ausführbar.

## 5. Getroffene Design-Entscheidungen (mit Begründung)

- **Ausführung/Scope:** Dependency-Baum entscheidet nur WELCHER Teil läuft, nie WIE (Paste+native
  DB-Engine bleibt). Explizit von Yannik bestätigt.
- **Dialekt-Strategie:** dialektneutraler Kern (generische `SqlElementTypes`), Erweiterungspunkte pro
  Dialekt für Syntax-Sonderfälle. Offenes Risiko: DB2-for-i-PSI-Abdeckung nie verifiziert (Spike lief
  nur gegen generisches SQL).
- **Variablen:** v1 explizit raus, siehe Leitplanken (Abschnitt 2). Sicherheits-, nicht aufwandsgetrieben.
- **LATERAL ist kein eigenständiges Konstrukt.** Syntaktisch nur eine Subquery-Position, die
  Korrelation zu vorherigen FROM-Items explizit erlaubt (Keyword `LATERAL`/`CROSS APPLY`/`OUTER APPLY`).
  Da LATERAL praktisch immer korreliert ist, wird es in aller Regel gar nicht zu einem eigenen Knoten
  (siehe 4.3). Die Klassifizierung "ist es LATERAL" ist rein syntaktisch (Keyword-Check), keine eigene
  Analyse-Pipeline nötig.
- **Auswahlbarkeits-Regel (ersetzt eine frühere Idee mit "isCorrelated"-Flag + UI-Sonderzustand):**
  nur Knoten, die sich zusammen mit ihren Abhängigkeiten zu vollständigem, eigenständig ausführbarem
  SQL zusammenbauen lassen, werden überhaupt als `DependencyNode` erzeugt: CTEs, UNION-Branches
  (ganz oder Teil), unkorrelierte Subqueries. Korrelierte Konstrukte (LATERAL, korrelierte Subqueries)
  bekommen gar keinen Knoten — kein UI-Sonderzustand ("sichtbar, aber Enter deaktiviert") nötig, was im
  Popup auftaucht, ist per Konstruktion ausführbar.
- **Verschachtelte WITH-Klauseln (Subquery mit eigener, lokaler WITH-Klausel): v1-Pflicht.** Yanniks
  explizite Entscheidung, trotz höherem Aufwand für `discoverScope()`. Macht Shadowing real — gelöst
  über scope-qualifizierte `NamedNodeId` (4.1), nicht über PSI-Resolving (Spike zeigt: das würde nicht
  funktionieren, echte Mehrdeutigkeit statt Autopick).
- **WITH RECURSIVE: v1-Pflicht mit Selbstreferenz-Behandlung.** Yanniks explizite Entscheidung.
  `isRecursive`-Flag, `transitiveDependencies()` mit Visited-Set (4.1). SQL-Zusammenbau (4.5) muss noch
  `WITH RECURSIVE` statt `WITH` erzeugen, sobald eine rekursive CTE gebraucht wird — offen.
- **Konsistenz-Hinweis, nicht handlungsrelevant, aber wichtig für Erwartungsmanagement:** v1 enthält
  mit verschachtelten WITH-Klauseln und WITH RECURSIVE zwei der strukturell aufwendigsten SQL-
  Konstrukte überhaupt, während Variablen (einfacher zu bauen, aber aus Sicherheitsgründen raus)
  draußen bleiben. Kein Bug, nur: v1 ist strukturell nicht der einfache erste Wurf.
- **Preview-Branch/EAP-Kanal: nicht nötig.** Additive Rollout-Reihenfolge (Abschnitt 8) hält den
  bestehenden, veröffentlichten Code bis zum letzten Schritt unangetastet — ein normaler Feature-Branch
  reicht.

## 6. PSI-Resolving-Spike — ABGESCHLOSSEN (2026-09-17)

Frage: löst IntelliJs SQL-PSI Tabellen-/CTE-Referenzen zu ihrer echten Deklaration auf (statt nur
Text-Matching wie im heutigen `collectTableRefs`)? Verifiziert manuell in einer echten `runIde`-Sandbox
(automatisierter Unit-Test-Weg war durch ein Test-Sandbox-Problem blockiert, siehe Abschnitt 10) via
Strg+Klick/"Go to Declaration" auf vier absichtlich kniffligen Testqueries.

| # | Frage | Ergebnis |
|---|---|---|
| 1 | Einfache CTE-Referenz löst zur Deklaration auf? | **Ja** |
| 2 | Shadowing (zwei gleichnamige CTEs, verschachtelte WITH) löst automatisch zur inneren auf? | **Nein** — IntelliJ zeigt ein "Choose Declaration"-Popup mit beiden Kandidaten. Echte Mehrdeutigkeit (`PsiPolyVariantReference`), keine automatische Scope-Auflösung. |
| 3 | Korrelierte Subquery: Auflösung liegt außerhalb der eigenen TextRange? | **Ja** — springt zur äußeren `FROM orders o`-Deklaration, klar außerhalb der Subquery-Klammern. |
| 4 | WITH-RECURSIVE-Selbstreferenz löst zur eigenen Definition auf? | **Ja** |

**Einordnung:** "Go to Declaration" ist keine separate Navigations-Heuristik, sondern die direkte
UI-Konsequenz von `PsiReference.resolve()`/`multiResolve()` — derselben API, die
`TableReferenceResolver`/`DependencyGraphBuilder` programmatisch aufrufen werden. Ein korrektes,
eindeutiges Ergebnis im Editor ist direkte Evidenz für korrektes Verhalten im eigenen Code.

Test 2 ist kein Fehlschlag, sondern eine wichtige Präzisierung: PSI-Resolving liefert bei
Namenskollisionen zwischen Scopes mehrere Kandidaten statt einer automatischen Entscheidung. Deshalb:
scope-qualifizierte `NamedNodeId(name, scope)` (4.1) ist zwingend, nicht nur vorsorglich. Shadowing wird
strukturell gelöst (nächster umschließender Scope relativ zur Referenzstelle), nicht über das
Resolving-Ergebnis.

**Konsequenz:** `TableReferenceResolver` wird als `PsiResolvingResolver` gebaut (4.2). Korrelations-Check
über TextRange-Containment (4.3). Shadowing über eigene Scope-Logik (4.1/4.3). Kein Fallback nötig —
Spike war für alles, was PSI-Resolving leisten soll, eindeutig positiv.

Die vier Testqueries liegen bereits als Fixtures vor: `src/test/kotlin/com/ykoellmann/ctexecutor/testutil/TestQueries.kt`
(7 Queries insgesamt, mehr als im Spike genutzt — siehe Abschnitt 12).

## 7. Navigations-UX — finales Popup-Design (v5)

**Richtung:** ▲ Nach oben = Richtung Abhängigkeiten (was der Knoten braucht). ▼ Nach unten = Richtung
Konsumenten (was den Knoten braucht). Abhängigkeitsbasiert, NICHT positionsbasiert — fällt bei CTEs
meist mit der Textposition zusammen, aber nicht bei echter Verschachtelung (die umschließende CTE ist
Konsument einer Subquery, nicht ihre Abhängigkeit, obwohl sie textlich "außen" steht).

**Nummerierung:** radiiert von der aktuellen Zeile nach außen. Die Sektion, die näher an der aktuellen
Zeile andockt, bekommt den niedrigeren Zahlenbereich (meist "unten", da typischerweise weniger direkte
Konsumenten als Abhängigkeiten). Unten aufsteigend ab 1, oben fortlaufend mit höheren Zahlen.

```
┌─ Execute from Here ──────────────────────────┐
│ ▲ NACH OBEN — Abhängigkeiten                  │
│   3  raw_customers                            │
│   2  raw_orders                               │
│                                                │
│ ▶ cte_orders_raw                 [Enter = Run]│
│                                                │
│ ▼ NACH UNTEN — wird benötigt von              │
│   1  cte_orders_summary                       │
│                                                │
│ 1–3 springen · Esc schließen                  │
└────────────────────────────────────────────────┘
```

**Mechanik:** Zahl drücken ODER klicken springt hin (nur Kontext/Vorschau, keine Ausführung). Genau 1
Ziel in einer Sektion → zusätzlich per Pfeiltaste direkt ansprungbar. Ausführen ist ausschließlich Enter
auf dem aktuellen (mittleren) Eintrag — Springen ≠ Ausführen (Sicherheitsprinzip, Abschnitt 2).

**Shortcut für die neue Action: noch offen.** Vorschlag `Ctrl+#` + `N` (Navigate) — bestehende Actions
belegen `Enter`/`Space`/`C`/`W`. Braucht Yanniks Bestätigung, blockiert aber nichts.

**Bestehende "alle CTEs anzeigen"-Aktion bleibt komplett unverändert**, eigener, heutiger Shortcut,
kein Umschalten in diesem neuen Popup.

Mockup-HTML-Dateien (v1–v5, Entwicklungsverlauf) liegen NICHT im Repo (waren nur Chat-Artefakte).
`cte-navigation-popup-v5.html` ist die finale, von Yannik bestätigte Version — bei Bedarf im
claude.ai-Project nachschauen (`claude/rewrite-analyse-und-plan.md`) oder neu bauen, das ASCII-Diagramm
oben ist inhaltlich vollständig.

## 8. Rollout-Plan (additiv, nicht-brechend) & aktueller Stand

0. ~~PSI-Resolving-Spike~~ — **ABGESCHLOSSEN** (Abschnitt 6).
1. ~~Neues Datenmodell + Builder als toter Code~~ — **ABGESCHLOSSEN.** `model/DependencyNode.kt`,
   `analyzer/TableReferenceResolver.kt`, `analyzer/DependencyGraphBuilder.kt` (mit `discoverScope`/
   `computeDependencyIds` noch als `TODO()`) im Repo, nirgends verdrahtet, kompiliert.
2. ~~`discoverScope()`, Korrelationsprüfung, Shadowing-Tiebreak, RECURSIVE-Selbstreferenz gegen
   `testutil/TestQueries.kt` ausarbeiten~~ — **ABGESCHLOSSEN**, siehe Abschnitt 13.
3. ~~Build-Logik für ausführbares SQL pro `NodeKind`~~ — **ABGESCHLOSSEN.**
   `analyzer/DependencyGraphSqlBuilder.kt`, siehe Abschnitt 13. Nachfolger von `SqlBuilder.kt`
   (Abschnitt 4.5), bewusst als eigene Datei, `SqlBuilder.kt` bleibt unverändert.
4. ~~Neue Navigation-Action nach Abschnitt 7 bauen, verdrahtet auf `DependencyGraph`~~ —
   **ABGESCHLOSSEN.** `actions/NavigateDependencyGraphAction.kt` + `plugin.xml`-Eintrag, siehe
   Abschnitt 15.
5. **Parallel validieren (echte Skripte, ggf. `PsiResolvingResolver` gegenprüfen) —
   DAS IST DER NÄCHSTE SCHRITT.** Insbesondere: `runIde`-Sandbox starten und die neue
   Navigation-Action (`Ctrl+#` `N`) manuell an echten, größeren SQL-Skripten durchklicken — bisher
   nur durch Unit-Tests auf Graph-/SQL-Ebene abgesichert, die Popup-UI selbst (Nummerierung,
   Mnemonics, Sektions-Header, Springen-vs-Ausführen) wurde noch nicht in einer laufenden IDE
   gesehen.
6. Alten `CursorScope`/`CteEntry`-Pfad (`SqlAnalyzer.kt`) erst nach validiertem Parallelbetrieb entfernen,
   die 3 bestehenden Actions auf die neue Pipeline konsolidieren.

## 13. Rollout-Schritt 2+3 — Ergebnisse (2026-09-17)

**Test-Sandbox-Problem (Abschnitt 10) gelöst.** Ursache: `bundledPlugin("com.intellij.database")`
allein reicht für den `test`-Gradle-Task nicht — die Test-Sandbox des `intellij-platform`-Gradle-
Plugins lädt Bundled Plugins nur, wenn sie zusätzlich über `testBundledPlugin(...)` als Test-
Abhängigkeit deklariert sind. Zusätzliches Problem: `com.intellij.database` selbst hängt von einem
JSON-Backend-Modul ab (`com.intellij.modules.json`), ohne dessen Test-Bundling meldet der
PluginManager `"has module dependency 'intellij.json.backend' which cannot be loaded or missing"`
und überspringt das DB-Plugin komplett (SQL-Dateien werden dann als `PlainTextFileType` geparst,
kein SQL-PSI). Fix in `build.gradle.kts`: zusätzlich `testBundledPlugin("com.intellij.database")`
und `testBundledPlugin("com.intellij.modules.json")`. `PsiResolvingSpikeTest.kt` läuft jetzt
vollständig automatisiert (14 Tests, u. a. mehrere `testDumpPsiTreeFor*`-Diagnose-Tests gegen den
`TestQueries`-Korpus — nützliche Referenz für die tatsächliche PSI-Struktur jedes Testfalls).

**Wichtige Korrektur zu Abschnitt 6:** Der automatisierte Test (`testShadowingInNestedWith`) zeigt,
dass `PsiReference.resolve()` beim Shadowing-Fall *zufällig* das richtige (innere) Ergebnis lieferte —
`multiResolve()` bestätigt aber echte Mehrdeutigkeit (2 Kandidaten, Reihenfolge nicht garantiert
proximitätsbasiert). Die Kernaussage aus Abschnitt 6 bleibt richtig: Shadowing wird **nicht** über
PSI-Resolving gelöst, sondern strukturell.

**`DependencyGraphBuilder.discoverScope()`/`computeDependencyIds()` sind vollständig ausgearbeitet**
(nicht mehr `TODO()`), abgesichert durch `DependencyGraphBuilderTest.kt` (7 Tests, gesamter
`TestQueries`-Korpus außer `LATERAL_JOIN_POSTGRES`, siehe unten). Wichtigste Design-Erkenntnisse
gegenüber dem Entwurf in Abschnitt 4.3:

- **PSI-Struktur-Fakten** (per `dumpPsiTree`-Diagnose verifiziert, nicht im Entwurf antizipiert):
  Ein CTE-/Subquery-Rumpf ist ein `SQL_QUERY_EXPRESSION` (nicht `SQL_SELECT_STATEMENT` — das
  existiert nur EINMAL pro Datei, für die äußerste `WITH`-Query). UNION wird als
  `SQL_UNION_EXPRESSION` mit direkten `SQL_QUERY_EXPRESSION`-Kindern (den Branches) repräsentiert.
  Eine `WITH`-Klausel liegt in `SQL_WITH_QUERY_EXPRESSION` NEBEN (nicht unter) ihrer anschließenden
  Query — Shadowing-Suche muss deshalb an jeder Ebene sowohl Vorfahren-`SQL_WITH_CLAUSE` als auch
  Geschwister-`SQL_WITH_CLAUSE` prüfen (`resolveByShadowing`). `PsiFile.getParent()` liefert ein
  `PsiDirectory`, nicht `null` — `isTopLevelWithClause` prüft deshalb `is PsiFile`, nicht `== null`.
- **Korrelations-Check verfeinert:** Nur `SQL_TABLE_REFERENCE`/`SQL_REFERENCE` (Tabellen-/Alias-Ebene)
  werden geprüft, nicht jede `SQL_COLUMN_REFERENCE` — eine normale Spalte wie `amount` löst immer
  zur Spaltendefinition der referenzierten CTE auf, die textlich außerhalb der eigenen Subquery liegt;
  das wäre sonst ein False-Positive bei JEDER Subquery, die überhaupt eine Spalte selektiert.
  Zusätzlich zählt eine Auflösung auf eine `SQL_NAMED_QUERY_DEFINITION` (= CTE) nie als Korrelation,
  auch wenn sie außerhalb der eigenen Range liegt — das ist eine legitime Abhängigkeit, keine
  Korrelation zu einer umschließenden Zeile.
- **Qualifizierte Referenzen** (`public.orders`) werden über ein `.`-Token als direktes Kind von
  `SQL_TABLE_REFERENCE` erkannt und von der CTE-Namensauflösung ausgeschlossen (CTE-Namen sind per
  SQL-Standard immer unqualifiziert).
- **Verschachtelte Owned-Nodes bubblen ihre Abhängigkeiten hoch, statt geblockt zu werden:** Trifft
  `computeDependencyIds()` beim Textwalk auf ein Element, das einem ANDEREN Knoten gehört (z. B. eine
  unkorrelierte FROM-Subquery innerhalb der Main-Query, oder ein UNION-Branch innerhalb einer CTE),
  werden nicht einfach dessen Referenzen ignoriert, sondern dessen bereits berechnete `dependencyIds`
  übernommen (rekursiv/memoisiert über eine `dependenciesOf`-Closure in `build()`). Begründung: der
  zusammengebaute SQL-Text des äußeren Knotens enthält den fremden Knoten WÖRTLICH (siehe
  `DependencyGraphSqlBuilder`), braucht also exakt dieselben externen CTEs. Das macht die ursprünglich
  im Entwurf vorgesehenen CTE/MAIN_QUERY-UNION-Sonderfälle überflüssig — sie wurden entfernt
  (generalisierter Mechanismus deckt sie automatisch mit ab).

**`DependencyGraphSqlBuilder.kt` (Nachfolger von `SqlBuilder.kt`, Abschnitt 4.5) ist gebaut**,
abgesichert durch `DependencyGraphSqlBuilderTest.kt` (7 Tests): baut aus einem Zielknoten +
`graph.transitiveDependencies()` eigenständig ausführbares SQL. CTE-Ziele werden selbst Teil der
`WITH`-Liste (`SELECT * FROM <name>` als Hauptanweisung), alle anderen Knotenarten stehen als
Hauptanweisung mit `element.text`. CTEs werden nach `textRange.startOffset` sortiert (Ersatz für das
alte `CteEntry.index` — eine CTE kann nur auf textlich vorangehende CTEs verweisen). `WITH RECURSIVE`
wird erzeugt, sobald irgendeine benötigte CTE `isRecursive` ist — auch wenn ein einzelner UNION-Branch
einer rekursiven CTE isoliert ausgeführt wird (durch Test bestätigt).

**Bewusst nicht (noch) durch Unit-Tests abgedeckt:** `LATERAL_JOIN_POSTGRES` — das Postgres-`LIMIT`
in diesem Testfall verursacht in der generischen SQL-Dialekt-Konfiguration der Testumgebung einen
Parse-Fehler (`ERROR_ELEMENT`, per `testDumpPsiTreeForLateral` sichtbar). Die Testfixtures laufen
aktuell ohne explizite Dialekt-Zuordnung (generisches SQL) — für dialektspezifische Syntax (Postgres-
`LIMIT`, `LATERAL`/`CROSS APPLY`-Varianten) müsste eine Testdatei/ein `SqlDialectMappings`-Override
recherchiert werden. Passt zum bereits in Abschnitt 11 benannten offenen Risiko (DB2-for-i-Dialekt-
Abdeckung nie verifiziert) — hier ist es nicht DB2, sondern generell "jede Nicht-ANSI-Syntax in
Unit-Tests" betroffen. Nicht blockierend für Rollout-Schritt 4 (Navigation-Action), da `isLateralPosition`/
`NodeKind.LATERAL` bereits implementiert sind (nur ihr Verhalten bei tatsächlichem Postgres-`LIMIT`-
Parsing ist unverifiziert) — als offener Punkt für Rollout-Schritt 5 (Validierung) vormerken.

## 14. (historisch) Auftrag für Rollout-Schritt 4 — siehe Abschnitt 15 für das Ergebnis

## 15. Rollout-Schritt 4 — Ergebnis (2026-09-17)

`actions/NavigateDependencyGraphAction.kt`: eigene, additive `AnAction`-Klasse (NICHT von
`SqlActionBase` abgeleitet — das bestehende Popup-Modell dort ist eine flache Liste ohne
Sektionen/Navigation; die neue Popup-UI aus Abschnitt 7 braucht ein eigenes `ListPopupStep` mit
Sektions-Trennern, Mnemonics und "eine Auswahl öffnet die nächste Popup-Seite"-Verhalten).

**Technischer Ansatz:** `BaseListPopupStep<Row>` (`com.intellij.openapi.ui.popup.util`), wobei `Row`
eine sealed interface mit `Dependency`/`Current`/`Consumer`-Varianten ist. Mnemonic-Zahlen werden
NICHT manuell verdrahtet, sondern über `BaseStep`s eingebautes `&`-Präfix-Konzept in `getTextFor()`
(z. B. `"&2  raw_orders"`) — `BaseStep.getMnemonicPos()` findet das `&` automatisch, kein eigener
`getMnemonicNavigationFilter()`-Override nötig. Sektions-Header (▲/▼) über `getSeparatorAbove()`,
per `rows.indexOf(value)` an Index 0 bzw. `dependencies.size + 1` festgemacht.

**Navigation = neue Popup-Seite, kein In-Place-Refresh:** `onChosen()` gibt für Dependency-/
Consumer-Zeilen ein NEUES `BaseListPopupStep` zurück (rekursiver Aufruf von `buildStep()` für den
gewählten Knoten) — IntelliJs `ListPopupImpl` pusht das automatisch als nächste Popup-Seite (Esc
geht eine Seite zurück, kein manuelles Popup-Lifecycle-Management nötig). Vorher wird der Cursor
per `editor.caretModel.moveToOffset(node.element.textRange.startOffset)` +
`scrollingModel.scrollToCaret()` zum Zielknoten bewegt (reine Navigation, keine Ausführung).
Für die `Current`-Zeile (`Enter` = Run) gibt `onChosen()` stattdessen `doFinalStep { ... }` zurück,
das `DependencyGraphSqlBuilder.build(node, graph).sql` an das bestehende
`SqlExecutor.executeSqlSeamlessly()` übergibt (Ausführungsmechanismus unverändert wiederverwendet).

**Nummerierung:** direkt am Doc-Beispiel aus Abschnitt 7 orientiert — Konsumenten 1..N aufsteigend
direkt unter dem aktuellen Eintrag, Abhängigkeiten `N+1..N+M` fortlaufend nach oben (die Abhängigkeit
direkt über dem aktuellen Eintrag bekommt `N+1`, die am weitesten entfernte `N+M`). Selbstschleifen
(rekursive CTE, Konsument/Abhängigkeit ihrer selbst) werden aus der Zeilenliste gefiltert — "springe
zu dir selbst" wäre kein sinnvoller Navigationsschritt.

**Shortcut:** `Ctrl+#` `N` wie in Abschnitt 7 vorgeschlagen, in `plugin.xml` eingetragen — **noch
nicht von Yannik final bestätigt**, blockiert aber nichts (Abschnitt 11).

**plugin.xml:** neuer `<action id="com.ykoellmann.ctexecutor.NavigateDependencyGraphAction">`-Eintrag,
rein additiv, die 3 bestehenden Action-Einträge unverändert.

**Nicht getan (bewusst, siehe Abschnitt 11/Rollout-Schritt 5):**
- Keine manuelle UI-Verifikation in einer laufenden `runIde`-Sandbox — nur durch die bestehenden
  Unit-Tests auf `DependencyGraph`/`DependencyGraphSqlBuilder`-Ebene indirekt abgesichert. Ob die
  Mnemonic-Zahlen/Sektions-Header/Popup-Kaskadierung tatsächlich wie im Abschnitt-7-Mockup aussehen
  und sich bedienen lassen, ist ungeprüft.
- Kein UI-Test/Screenshot-Test für die neue Action (kein etabliertes Testmuster dafür im Repo).
- `SqlAnalyzer.kt`/`SqlBuilder.kt`/die 3 bestehenden Actions unverändert, wie gefordert.

Voller Build (`./gradlew build -x test`) und die komplette Testsuite (28 Tests) laufen grün.

## 16. Bugfix nach manuellem Test durch Yannik (2026-09-17)

Beim ersten manuellen Test des neuen Popups (mit einer echten, verbundenen Datenbank in DataGrip):
`IllegalArgumentException: Argument for @NotNull parameter 'range' of TextRange.contains must not
be null`. Ursache: `DependencyGraphBuilder.isCorrelated()` ruft `reference.resolve()` auf und prüft
`resolved.textRange` — löst die Referenz zu einem ECHTEN Datenbank-Schema-Objekt auf (Tabelle/Spalte
aus der verbundenen DB-Quelle, nicht zu einer CTE), liegt dieses Element ausserhalb der Datei-PSI und
`getTextRange()` liefert `null`. Im Unit-Test unsichtbar, da die Test-Sandbox keine echte DB-Verbindung
hat — `PsiResolvingSpikeTest`/`DependencyGraphBuilderTest` konnten das nicht reproduzieren.

**Fix:** `resolvedRange = resolved?.takeIf { it.isValid }?.textRange` vor dem `contains()`-Aufruf;
eine Auflösung ausserhalb der Datei (`null`-Range) zählt nicht als Korrelation, genau wie eine gar
nicht auflösbare Referenz. Betrifft nur `isCorrelated()` in `DependencyGraphBuilder.kt`.

**Konsequenz für Rollout-Schritt 5:** Das ist ein direkter Hinweis darauf, dass echte manuelle
Validierung gegen verbundene Skripte (nicht nur Unit-Tests) weiterhin nötig ist — ähnliche
`resolve()`-auf-externes-Schema-Objekt-Fälle könnten an anderen Stellen lauern (z.B.
`resolveByShadowing`/`computeDependencyIds` nutzen bewusst KEIN `.resolve()` für CTE-Namen, sind also
nicht betroffen; aber jeder Code, der `.resolve()`/`.references` direkt auf `SQL_TABLE_REFERENCE`/
`SQL_REFERENCE` aufruft, sollte auf `null`-TextRange bzw. externe DB-Auflösung geprüft werden).

## 17. Popup-UI komplett überarbeitet nach Yannik-Feedback (2026-09-17)

Nach dem ersten manuellen Test kam Feedback, das die ursprüngliche `ListPopupStep`/
`BaseListPopupStep`-basierte Umsetzung (Abschnitt 15) an mehreren Punkten verworfen hat:

1. **Highlighting fehlte komplett** — die alte Umsetzung hatte gar keinen Highlighting-Mechanismus.
2. **Navigation öffnete ein zweites Popup NEBEN dem aktuellen**, statt es zu ersetzen — das war die
   eingebaute Kaskadierungs-Mechanik von `ListPopupStep.onChosen()`, die einen zurückgegebenen
   `PopupStep` automatisch als "Unterseite" (mit Zurück-Navigation) daneben öffnet. Nicht gewollt.
3. Kein "Enter = Run"-Text mehr gewollt, keine deutschen Texte (galt auch für Sektions-Header UND
   für generierte `displayName`s wie `"UNION branch (Zeile N)"` in `DependencyGraphBuilder.lineOf()`
   — jetzt `"line N"`).
4. Trennlinie zwischen aktuellem Eintrag und dem Block darüber (Dependencies) fehlte — es gab nur
   eine zwischen aktuellem Eintrag und dem Block darunter (Consumers). Sollte symmetrisch sein.
5. SQL-Vorschau (wie bei den 3 bestehenden Actions: `"name: SELECT ...`") fehlte für alle Zeilen.

**Konsequenz: `NavigateDependencyGraphAction.kt` komplett neu geschrieben**, weg von
`BaseListPopupStep`, hin zu `JBPopupFactory.createPopupChooserBuilder(...)` — demselben Ansatz wie
`SqlActionBase` (die 3 bestehenden Actions). Das ist kein Zufall: dieser Ansatz bringt
Highlighting-on-Selection (`setItemSelectedCallback`) und eine SQL-Vorschau im Label praktisch
kostenlos mit, weil `SqlActionBase` das schon genauso macht.

- **Rows** (`Row(node, kind: DEPENDENCY/CURRENT/CONSUMER, sql, highlightRanges)`) werden VORAB für
  jede Zeile per `DependencyGraphSqlBuilder.build(node, graph)` berechnet — jede Zeile (nicht nur die
  aktuelle) bekommt so ihre eigene lauffähige SQL-Vorschau (erste ~60 Zeichen im Label,
  whitespace-normalisiert, exakt nach dem Muster aus `SqlActionBase.showPopup()`).
- **Highlighting:** `setItemSelectedCallback` ruft ein lokales `applyHighlights(ranges)` auf (gleiche
  Technik wie `SqlActionBase.highlightRanges()` — `RangeHighlighter` über `SEARCH_RESULT_ATTRIBUTES`).
  Bewusst LOKALE (nicht Instanzfeld-)Highlighter-Liste pro `showPopupFor()`-Aufruf, siehe nächster
  Punkt für den Grund.
- **Navigation ersetzt statt zu kaskadieren:** `setItemChosenCallback` schliesst das aktuelle Popup
  (macht `createPopupChooserBuilder` beim Waehlen ohnehin automatisch) und ruft danach
  `navigateAndReopen()` auf, das den Cursor bewegt (`editor.caretModel.moveToOffset` +
  `scrollToCaret`) und **sofort ein NEUES Popup** (`showPopupFor()` rekursiv) an der neuen
  Cursor-Position zeigt — fühlt sich fuer den Nutzer wie ein In-Place-Update an, ist technisch aber
  Schliessen+Neuoeffnen. **Wichtiger Bugfix dabei:** `highlighters` wurde bewusst von einem
  Instanzfeld zu einer LOKALEN Liste pro `showPopupFor()`-Aufruf gemacht — bei einem geteilten
  Instanzfeld könnte das verzögerte `onClosed()` des ALTEN Popups die frisch gesetzten Highlights
  des NEUEN Popups wieder löschen (Race Condition zwischen Schliessen des alten und Rendern des
  neuen Popups).
- **Keine Sektions-Header/Mnemonics mehr** (kein `▲ NACH OBEN — Abhängigkeiten`-Text) — Pfeil-Symbol
  (▲/▶/▼) direkt vor jedem Zeilennamen statt eines separaten Header. Zahlennavigation (1/2/3-Tasten)
  wurde fallen gelassen, da nicht explizit gefordert und `createPopupChooserBuilder` das nicht wie
  `BaseListPopupStep` "for free" mitbringt — Pfeiltasten + Enter + Klick funktionieren weiterhin
  (Standard-JList-Verhalten).
- **Symmetrische Trennlinie:** Die aktuelle Zeile bekommt jetzt IMMER einen `MatteBorder(1,0,1,0,...)`
  (oben UND unten je eine duenne Linie), statt eines `ListSeparator` nur auf einer Seite.
- **Englische Texte:** `DependencyGraphBuilder.lineOf()` liefert jetzt `"line $n"` statt
  `"Zeile $n"` — betrifft die generierten `displayName`s fuer `SUBQUERY`/`UNION_BRANCH`/`LATERAL`.

Voller Build (`./gradlew build -x test`) und alle 28 Unit-Tests laufen weiterhin grün (die
Popup-UI selbst ist durch keinen Unit-Test abgedeckt, siehe Abschnitt 15 — weiterhin nur manuell in
der laufenden IDE pruefbar, das ist der unveraendert offene Rollout-Schritt 5).

## 18. Bugfix nach zweitem manuellen Test (2026-09-17)

`NullPointerException: Cannot invoke "Row.getHighlightRanges()" because "row" is null` beim
Popup-Aufbau — Folge: gar kein Popup mehr sichtbar, UND die zuvor per `applyHighlights()` gesetzten
Highlights blieben verwaist stehen (weil das Popup nie erfolgreich `show()`te und der
`onClosed`-Listener, der sie aufraeumt, folglich nie feuerte — beide gemeldeten Symptome hatten also
dieselbe Ursache).

**Ursache:** `IPopupChooserBuilder.setItemSelectedCallback`/`setItemChosenCallback`/`setRenderer`
nehmen Java-`Consumer`/`ListCellRenderer`-Interfaces entgegen, deren Typparameter aus Kotlin-Sicht
ein Plattformtyp ist (keine erzwungene Non-Null-Annotation). IntelliJs Popup-Framework ruft diese
Callbacks bei leerer/wechselnder Selektion tatsaechlich mit `null` auf - Kotlin liess das anstandslos
durch (Plattformtyp), aber der erste Zugriff auf `row.highlightRanges` warf dann eine echte NPE.

**Fix:** alle drei Lambdas nehmen jetzt explizit `Row?` und guarden auf `null` (`row?.let { ... }`
bzw. frühes `return@setItemChosenCallback`), der Renderer liefert bei `null` ein leeres `JPanel()`.

## 19. Nummerierung wieder hinzugefügt (2026-09-17)

Yannik wies darauf hin, dass beim Ansatzwechsel in Abschnitt 17 (weg von `BaseListPopupStep`) die
Nummerierung/Zifferntasten-Navigation aus dem ursprünglichen Mockup-Design (Abschnitt 7) unbeabsichtigt
verloren gegangen war — das war ein Kollateralschaden des Wechsels, kein bewusster Design-Entscheid,
und wurde nicht klar kommuniziert.

**Nachgebessert:** `Row` hat jetzt wieder ein `number: Int?`-Feld (gleiche Nummerierungslogik wie
zuvor: Konsumenten 1..N aufsteigend ab dem aktuellen Eintrag, Abhängigkeiten `N+1..N+M` fortlaufend
nach oben). Die Zahl erscheint jetzt im Label VOR dem Pfeil-Symbol (`"2 ▲ raw_orders: SELECT ..."`).
Zusätzlich: `IPopupChooserBuilder.registerKeyboardAction(KeyStroke, ActionListener)` registriert für
jede nummerierte Zeile (1-9) einen direkten Zifferntasten-Sprung — das existiert bei
`createPopupChooserBuilder` nicht automatisch (anders als bei `BaseListPopupStep`s `&`-Mnemonic-
Konvention), muss also manuell verdrahtet werden. Technischer Kniff: `registerKeyboardAction` muss
VOR `createPopup()` auf dem Builder aufgerufen werden, der Listener braucht aber eine Referenz auf
das fertige Popup zum Schliessen (`popupRef`, eine `var`, die erst NACH `createPopup()` zugewiesen
wird) sowie auf die gemeinsame `choose(row)`-Auswahllogik (ein `lateinit var`, das die
Navigations-/Ausführungs-Fallunterscheidung buendelt, geteilt zwischen Enter/Klick- und
Zifferntasten-Pfad).

Voller Build und alle 28 Tests laufen weiterhin grün.

## 20. Trennlinien um "Current" nachgebessert (2026-09-17)

Yannik meldete, die horizontalen Trennlinien um die aktuelle Zeile seien nicht sichtbar. Ursache:
`MatteBorder(1, 0, 1, 0, Color(fg, alpha=80))` war vermutlich zu duenn (1px) und zu blass (Alpha-
Blend auf `list.foreground`, keine garantiert kontrastreiche Farbe) um erkennbar zu sein - ausserdem
wurde die alte Padding-Border beim Ersetzen komplett verworfen statt kombiniert.

**Fix (1. Versuch, hat nicht gereicht):** `BorderFactory.createCompoundBorder(MatteBorder(2, 0, 2, 0,
JBColor.border()), JBUI.Borders.empty(2,4,2,4))` - 2px statt 1px, volldeckende Theme-Trennfarbe statt
Alpha-Blend. Laut Yannik immer noch komplett unsichtbar (Padding wurde groesser, aber keine Linien).

**Fix (2. Versuch, das eigentliche Problem):** Vermutung: JBPopups selektierbare Listenzellen (`JBList`
im Popup-Kontext) ueberdecken/ignorieren die `Border` der vom Renderer zurueckgegebenen Komponente
beim Zeichnen der Selektion - eine `Border` ist rein dekorativ um die Component-Bounds herum und wird
offenbar von der internen Selektions-Bemalung "uebermalt". Loesung: KEINE Border mehr fuer die
Trennlinien, sondern ein echtes, eigenstaendiges Kind-Panel mit fixer Groesse
(`JPanel().apply { isOpaque = true; background = JBColor.border(); preferredSize = Dimension(0, 2) }`),
das per `BorderLayout.NORTH`/`SOUTH` um `innerPanel` (dort wo die Selektion gemalt wird) herum
platziert wird (`dividerLine()`-Hilfsfunktion). Ein echtes Panel mit eigenem Layout-Platz kann nicht
von der Selektions-Bemalung eines ANDEREN Panels verdeckt werden.

**Auch das hat nicht gereicht** — Yannik meldete: die Trennlinie war jetzt zwar da, aber INNERHALB
der aktuellen Zeile (statt aussen als eigenstaendige Sektion) und faelschlich schwarz statt in der
Standard-Trennfarbe, nur sichtbar bei Hover/Highlight. Fazit: eine einzelne `JList`/`createPopupChooserBuilder`
mit selbstgemaltem Trennelement innerhalb der Zellen ist der falsche Ansatz fuer dieses UI - zu viel
Kampf gegen die interne Selektions-/Zell-Bemalung von IntelliJs Popup-Listen.

## 21. Komplettumbau auf 3 separate Listen (2026-09-17)

Auf Yanniks expliziten Wunsch ("nutze einzelne Listen, quasi 3 statt einer") komplett neu gebaut:
statt EINER `JList`/`createPopupChooserBuilder`-Instanz mit allen Zeilen jetzt DREI unabhaengige
`com.intellij.ui.components.JBList<Row>`-Instanzen (Dependencies/Current/Consumers), getrennt durch
ECHTE native `javax.swing.JSeparator`s (kein selbstgemaltes Trennelement mehr) - dadurch automatisch
korrekt theme-eingefaerbt, kein "schwarze Balken"-Problem mehr.

**Technischer Aufbau (`showPopupFor`):**
- `JBPopupFactory.createComponentPopupBuilder(content, preferredFocusedComponent)` statt
  `createPopupChooserBuilder` - eigener, selbstgebauter Popup-Inhalt (`content`: `JPanel` mit
  `BoxLayout.Y_AXIS`), kein fertiges Listen-Widget mehr.
- `buildList(rows)`: erzeugt eine `JBList<Row>` nur wenn `rows` nicht leer ist (Dependency-/
  Consumer-Liste faellt bei 0 Eintraegen komplett weg, kein leerer Abschnitt). Current-Liste hat
  immer genau 1 Eintrag.
- **Nur eine Selektion gleichzeitig ueber alle 3 Listen:** jede Liste hat einen eigenen
  `ListSelectionModel` (das ist bei getrennten `JList`s unvermeidbar) - `clearOtherSelections()`
  loescht bei einer Selektionsaenderung in einer Liste explizit die Selektion der anderen beiden, so
  dass optisch immer nur EIN Eintrag insgesamt markiert erscheint (und nur ein Highlight-Set im
  Editor aktiv ist).
- Jede Liste bekommt eigene Klick-/Enter-Bindung (`MouseAdapter`/`InputMap`+`ActionMap` mit
  `"chooseRow"`-Action) statt eines zentralen `setItemChosenCallback` - ruft dieselbe geteilte
  `choose(row)`-Closure auf wie zuvor.
- Zifferntasten-Shortcuts (1-9) jetzt ueber `ComponentPopupBuilder.setKeyboardActions(List<Pair<ActionListener,
  KeyStroke>>)` statt `IPopupChooserBuilder.registerKeyboardAction` (andere API, gleiches Prinzip -
  IntelliJs `Pair.create()` statt Kotlin `Pair`, weil `setKeyboardActions` genau diesen Typ erwartet).
- **Bekannte Einschraenkung:** Pfeiltasten-Navigation ueber die Sektionsgrenze hinweg (z.B. von der
  letzten Dependency-Zeile per Pfeil-runter in die Current-Zeile) funktioniert NICHT automatisch -
  jede der 3 Listen ist ein eigenstaendiges Fokus-/Navigationsziel. Maus-Klick und Zifferntasten
  funktionieren sektionsuebergreifend problemlos. Nicht behoben, da nicht explizit gefordert - bei
  Bedarf per Custom-FocusTraversalPolicy oder Key-Forwarding zwischen den Listen nachruestbar.

Voller Build (`./gradlew build -x test`) und alle 28 Unit-Tests laufen grün. Die Popup-UI selbst
bleibt ungetestet durch Unit-Tests (kein etabliertes UI-Testmuster im Repo) - weiterhin nur manuell
in der laufenden IDE pruefbar.

## 22. Zifferntasten funktionierten nicht + Mehrfach-Highlighting (2026-09-17)

Zwei verbleibende Probleme nach Abschnitt 21: (a) Zifferntasten-Navigation reagierte gar nicht,
(b) weil es 3 unabhaengige `JBList`s mit je eigenem Selektionsmodell waren, konnte visuell PRO Liste
eine Zeile markiert sein - macht bis zu 3 gleichzeitig markierte Zeilen moeglich, obwohl sich das
Popup wie EINE Liste verhalten soll (Yannik: "es muss sich quasi wie vorher wie eine Liste
verhalten"). Vermutete Ursache fuer (a): `JBList`s eingebautes Type-Ahead/Speed-Search faengt
Zifferntasten fuer die eigene "spring zu Eintrag mit diesem Anfangsbuchstaben"-Suche ab, bevor sie
das `ComponentPopupBuilder.setKeyboardActions`-Mapping erreichen.

**Loesung: `JBList` komplett durch einfache `JPanel`-Zeilen ersetzt (`RowView`), EIN gemeinsamer,
manuell verwalteter Selektionszustand (`selectedIndex: Int`) fuer alle 3 Sektionen zusammen, UND
Tastatur-Handling auf dem GESAMTEN Popup-Inhalt (`content.inputMap`/`actionMap`) statt pro Zeile/
Liste.** Damit:
- Es gibt technisch keine mehreren Selektionsmodelle mehr, die auseinanderlaufen koennten - `select(index)`
  schaltet IMMER zuerst die alte, dann die neue Zeile um (`RowView.setSelected(Boolean)` faerbt Panel-
  Hintergrund/Label-Vordergrund manuell via `UIManager.getColor("List.selectionBackground"/...)`).
- UP/DOWN bewegen sich entlang EINER gemeinsamen Reihenfolge (`orderedRows = dependencyRows + currentRow
  + consumerRows`), unabhaengig von der visuellen 3-Sektionen-Gruppierung - fuehlt sich wieder wie
  eine einzige Liste an, trotz optisch getrennter Bloecke.
- Zifferntasten 1-9 sind jetzt InputMap/ActionMap-Bindings auf `content` selbst (kein `JList`
  dazwischen, das sie abfangen koennte) - `choose(row)` wird direkt aufgerufen, kein Selektions-
  Zwischenschritt noetig.
- Maus-Klick auf eine Zeile ruft weiterhin `select(index)` + `choose(row)` auf.
- `JSeparator`s zwischen den 3 Sektionen bleiben (waren bereits in Abschnitt 21 korrekt/nativ
  gerendert - das Problem lag nur an der Mehrfach-Selektion, nicht an den Trennlinien selbst).

Voller Build und alle 28 Unit-Tests laufen grün.

## 24. Popup-Visualdesign "V1a" umgesetzt (2026-09-17)

(Numerierung: Abschnitt 23 unten beschreibt dieselbe V1a-Entscheidung aus einer parallelen
Konversation — Yannik hat den fertigen Plan von dort als Auftrag hierher kopiert. Dieser Abschnitt
24 ist die Umsetzungsbestaetigung dazu, siehe auch Abschnitt 25 fuer die direkt danach von Yannik
angefragte Korrektur (Icon statt Text-Marker).)

Yannik hat in einer separaten Konversation (Claude-Docs-Projekt "ctexecutor", mehrere HTML-Mockups,
V1-V8) das finale visuelle Design fuer die Dependency-/Consumer-Sektionen festgelegt: **V1a** —
echtes IntelliJ-Platform-Icon + Label als Sektions-Header ueber jedem Block statt eines Pfeil-Symbols
pro Zeile. Umsetzung in `NavigateDependencyGraphAction.kt`:

- `AllIcons.Hierarchy.Supertypes` + Label "Dependencies" ueber dem oberen Block,
  `AllIcons.Hierarchy.Subtypes` + Label "Used by" ueber dem unteren Block - neue
  `sectionHeader(icon, text)`-Hilfsfunktion, nur eingefuegt wenn die jeweilige Sektion nicht leer ist
  (dieselbe Bedingung, die schon die `JSeparator`-Platzierung steuert).
- `RowView.displayTextFor()`: `▲`/`▼`-Pfeil-Praefix bei Dependency-/Consumer-Zeilen entfernt (die
  Sektionszugehoerigkeit steht jetzt eindeutig im Header, ein Pfeil pro Zeile waere redundant). Der
  `▶`-Marker vor der aktuellen Zeile bleibt (die gehoert zu keiner der beiden Sektionen).
- Rein additiv/dekorativ: kein Einfluss auf Tastatur-/Klick-Handling, Highlighting, Nummerierung
  oder die 28 bestehenden Unit-Tests (reine UI-Kosmetik in der Popup-Ebene, von den Tests ohnehin
  nicht abgedeckt).

Voller Build und alle 28 Tests laufen weiterhin grün.

## 25. Grüner Run-Pfeil statt Text-Marker (2026-09-17)

Direkt danach Yanniks Folgewunsch: `▶`-Textmarker vor der aktuellen Zeile durch das echte, gruene
IntelliJ-Run-Icon (`AllIcons.Actions.Execute`, dasselbe Icon wie beim nativen "Run"-Gutter-Icon)
ersetzt - `RowView.init` setzt jetzt `label.icon`/`label.iconTextGap` statt eines Text-Praefix in
`displayTextFor()`. Rein kosmetisch, keine Auswirkung auf Tastatur-/Klick-Logik. Voller Build und
alle 28 Tests grün.

## 26. Die 3 bestehenden, veröffentlichten Actions auf DependencyGraph konsolidiert (2026-09-17)

Yannik fragte, ob die 3 bestehenden Actions (`ExecuteFromHereAction`/`CopyAction`/`CopyAndEditAction`,
via `SqlActionBase`) ihr heutiges Verhalten ("aktuelles Statement + benötigte CTEs zur Auswahl")
exakt behalten könnten, aber intern auf `DependencyGraph`/`DependencyGraphBuilder`/
`DependencyGraphSqlBuilder` umgestellt werden, statt auf dem alten `SqlAnalyzer.kt`/`SqlBuilder.kt` -
eine einzige Analyse-Quelle statt zwei parallelen Systemen. Das ist frueher als im Rollout-Plan
vorgesehen (Schritt 6 sollte erst "nach validiertem Parallelbetrieb", also nach Schritt 5, passieren) -
bewusste Entscheidung von Yannik, vorgezogen.

**Wichtiger Fund dabei, noch VOR der Umstellung geklärt:** beim Abgleich "muss die SQL-Erzeugung
exakt gleich bleiben" fiel auf, dass `DependencyGraphSqlBuilder` fuer ein CTE-Ziel `SELECT * FROM
<name>` erzeugte, waehrend das Altsystem (`SqlBuilder.extractInnerSelect()`, ueber `SqlActionBase`
von ALLEN 3 Actions tatsaechlich genutzt) den ROHEN Rumpf zwischen den Klammern direkt einsetzt, nie
einen Wrapper. Das war ein echter Analysefehler meinerseits beim urspruenglichen Bau von
`DependencyGraphSqlBuilder` (Rollout-Schritt 3, Abschnitt 15) - ich hatte mich auf den falschen,
tatsaechlich UNERREICHBAREN `SqlBuilder`-Codepfad (`HighlightMode.FULL_CTE`s `SELECT * FROM
cteName`-Zweig fuer `SQL_NAMED_QUERY_DEFINITION`-Ziele) als Referenz gestuetzt, statt den echten, von
`SqlActionBase` immer genutzten Pfad (`DEPENDENCIES_WITH_TARGET_INNER`) zu pruefen.

**Fix in `DependencyGraphSqlBuilder.assemble()`:** fuer ein CTE-Ziel wird jetzt `findBodySlot()`
(dieselbe Funktion, die `DependencyGraphBuilder` schon fuer die CTE-Erkennung nutzt, dafuer aus der
Klasse herausgezogen zu einer `internal fun` auf Datei-Ebene) genutzt, um den rohen Rumpf zu
extrahieren und direkt als Hauptanweisung einzusetzen - kein Wrapper mehr, exakt wie im Altsystem.

**Dabei zusaetzlich einen ECHTEN, bisher unentdeckten Bug im Altsystem gefunden und (nur im neuen
Code) behoben:** eine REKURSIVE CTE als "aktuelles Ziel" (Cursor direkt in ihr) haette im Altsystem
IMMER invalides SQL erzeugt - der rohe Rumpf referenziert sich selbst per Namen, aber
`SqlBuilder.buildSql()` fuegt fuer das Ziel selbst NIE eine `WITH RECURSIVE name AS (...)`-Definition
ein (nur fuer echte Dependencies via `ctes.dropLast(1)`), der Selbstbezug waere also undefiniert. Nie
aufgefallen, weil vermutlich nie jemand eine rekursive CTE per Cursor-Position direkt ausgefuehrt hat.
Der neue Code prueft das jetzt explizit (`useNameWrapperForTarget = node.kind == CTE &&
node.isRecursive`) und nutzt fuer GENAU diesen Fall doch den `SELECT * FROM name`-Wrapper (mit voller
Selbstdefinition in der WITH-Liste) - fuer jede nicht-rekursive CTE bleibt der rohe Rumpf ohne Wrapper
(exaktes Altsystem-Verhalten). Das ist eine bewusste Verbesserung gegenueber dem Altsystem, keine
Bit-fuer-Bit-Replikation eines Bugs.

**`DependencyGraphSqlBuilder.buildWithExplicitDependencies(target, cteDefinitions)` neu hinzugefuegt:**
noetig, weil `SqlActionBase`s "progressive" Popup-Optionen (eine CTE + die vor ihr benoetigten CTEs)
entlang der KUMULATIVEN Praefix-Liste aller fuer den urspruenglichen Cursor-Knoten benoetigten CTEs
laufen (`ctesUpToHere = requiredCtes.subList(0, i + 1)` im Altsystem) - NICHT entlang der
individuellen, eigenen Abhaengigkeiten jeder einzelnen Zwischen-CTE (was `build(node, graph)` liefern
wuerde). Fuer die FINALE Option (komplettes aktuelles Statement) reicht dagegen das normale `build()`
unveraendert - dessen `graph.transitiveDependencies(node.id)` liefert dort exakt dieselbe Menge wie
das Altsystems `requiredCtes` (+ das Ziel selbst, falls CTE).

**`SqlActionBase.kt` umgeschrieben:** `actionPerformed()` nutzt jetzt `DependencyGraphBuilder().build(file)`
+ `graph.nodeAt(offset)` statt `SqlAnalyzer(file, offset).analyze()`. `buildPopupOptions()` nimmt jetzt
`(graph: DependencyGraph, node: DependencyNode)` statt `SqlAnalyzer.SqlContext` entgegen, baut aber
exakt dieselbe Optionsliste (progressive CTE-Optionen + finale Option). `showPopup()`,
`highlightRanges()`, `removeAllHighlights()`, `PopupOption`, `handleSelectedOption` (abstract) -
alles unveraendert. `CopyAction.kt`/`CopyAndEditAction.kt`/`ExecuteFromHereAction.kt` brauchten KEINE
Aenderung (sie kennen nur `PopupOption`, nie `SqlAnalyzer`/`SqlBuilder` direkt).

**Neue Regressionstests** in `src/test/kotlin/com/ykoellmann/ctexecutor/actions/SqlActionBaseTest.kt`
(2 Tests, ueber einen `protected`-Wrapper in einer Test-Subklasse, da `buildPopupOptions` `protected`
ist): pruefen die komplette Optionsliste (Anzahl, Display-Namen, generiertes SQL je Option) fuer eine
2-CTE-Kette und fuer "Cursor direkt in einer CTE ohne eigene Abhaengigkeiten". Zusammen mit den
aktualisierten `DependencyGraphSqlBuilderTest`-Faellen (jetzt 8 statt 7 - der bisherige
`testSimpleCteAssemblesWithClauseAndSelect`-Test wurde in zwei praezisere Faelle aufgeteilt: mit und
ohne Dependency) sind es jetzt 31 Tests insgesamt, alle grün.

**Bewusst NICHT (noch) getan:**
- `SqlAnalyzer.kt`/`SqlBuilder.kt` NICHT geloescht - sie sind jetzt toter Code (von keiner Produktions-
  Datei mehr referenziert), aber als reversibler Schritt bewusst noch im Repo belassen, bis Yannik das
  manuell in `runIde` verifiziert hat. Loeschen ist ein separater, spaeterer Schritt.
- **Beobachtung, kein Fix:** die neue Engine findet Subqueries/Knoten auch in Skripten OHNE jede
  `WITH`-Klausel (z.B. `SELECT * FROM t WHERE x IN (SELECT y FROM u)` ganz ohne CTEs) - das Altsystem
  zeigte dort NIE ein Popup (`SqlAnalyzer.analyze()` brauchte immer eine umschliessende `WITH`-Klausel,
  sonst `null`). Mit der Umstellung koennen die 3 bestehenden Actions jetzt also in mehr Situationen
  ein Popup zeigen als vorher - eine Fähigkeitserweiterung, keine Verhaltensaenderung im ueberlappenden
  Bereich (wo vorher schon ein Popup kam, kommt exakt dasselbe SQL wie vorher). Nicht als Bug
  behandelt, aber erwaehnenswert falls Yannik das nicht erwartet hat.

Voller Build (`./gradlew build -x test`) und alle 31 Tests laufen grün.

## 27. Von 4 auf 2 Actions reduziert, CTE-Modus auf Graph-Modus-UI umgestellt (2026-09-17)

Direkter Folgeauftrag: statt 4 Actions (`ExecuteFromHereAction`, `CopyAction`, `CopyAndEditAction`,
`NavigateDependencyGraphAction`) nur noch **2**: "CTE-Modus" (`ExecuteFromHereAction`) und
"Graph-Modus" (`NavigateDependencyGraphAction`, unveraendert von Abschnitt 15-25). `CopyAction.kt`
und `CopyAndEditAction.kt` sind **geloescht** (nicht nur unbenutzt) - ihre Funktion (Kopieren/
Einfuegen) ist jetzt E/C-Tastenkuerzel INNERHALB beider verbleibenden Popups, zusammen mit Enter
(ausfuehren). `SqlActionBase.kt` ist ebenfalls **geloescht** (das alte, auf Vererbung/`handleSelectedOption`
basierende Popup-Grundgeruest brauchte es nicht mehr, seit es keine 3 duennen Subklassen mehr gibt).

**Neue geteilte Datei `actions/SqlPopupSupport.kt`:** `copySqlToClipboard()` (vormals
`CopyAction.copyToClipboard()`), `insertSqlIntoEditor()` (vormals `CopyAndEditAction.insertSql()`),
`buildShortcutFooter()` (neues "E to edit · C to copy"-Footer-Panel unter der Zeilenliste). Von
BEIDEN Actions genutzt.

**`ExecuteFromHereAction.kt` komplett neu geschrieben** (CTE-Modus), jetzt selbststaendig statt
`SqlActionBase`-Subklasse, im selben visuellen/interaktiven Stil wie der Graph-Modus (`RowView`-
Panels statt `createPopupChooserBuilder`) - aber mit einem wichtigen Unterschied, der Yanniks
konkrete Vorgabe war:

- Die Zeilenliste (benoetigte CTEs in Definitionsreihenfolge + aktuelles Statement am Ende) ist
  VOLLSTAENDIG und STATISCH bekannt, sobald das Popup aufgeht - anders als im Graph-Modus (wo nur die
  direkten 1-Hop-Nachbarn sichtbar sind und eine Auswahl eine kaskadierte Neuberechnung braucht,
  siehe `navigateAndReopen()`). Deshalb bewegt Navigation hier nur einen gemeinsamen `selectedIndex`
  INNERHALB der einen, bereits vollstaendigen Liste - kein Schliessen/Neuoeffnen des Popups noetig.
- Der gruene Run-Pfeil (`▶`, `AllIcons.Actions.Execute`) folgt `selectedIndex` direkt (statt an
  einer festen Position wie im Graph-Modus zu kleben).
- **Nummerierung wird bei JEDER Navigation komplett neu berechnet**, radiiert von der neuen
  `selectedIndex`-Position nach aussen (unten/danach aufsteigend ab 1, oben/davor fortlaufend danach)
  - exakt Yanniks Beschreibung ("wenn man nach oben geht, geht der Pfeil mit, Nummerierung
    verschwindet an der Stelle und bei dem davor wird die Nummer angezeigt, dementsprechend wieder
    eine absteigende Liste"). `RowView.update(number, isCurrent)` aktualisiert Text/Icon/Farbe einer
  bestehenden Zeile in-place, statt die Liste neu aufzubauen. Zifferntasten 1-9 werden bei JEDEM
  Tastendruck dynamisch aufgeloest (`rowIndexForNumber(n)`, relativ zur AKTUELLEN `selectedIndex`
  berechnet) statt einmalig beim Aufbau fest gebunden zu werden - im Graph-Modus reicht eine feste
  Bindung, weil sich dessen Nummern bei reiner Pfeiltasten-Navigation nicht aendern.
- Enter fuehrt aus (`SqlExecutor.executeSqlSeamlessly`), E fuegt in den Editor ein
  (`insertSqlIntoEditor`), C kopiert in die Zwischenablage (`copySqlToClipboard`) - jeweils auf der
  Zeile am aktuellen `selectedIndex`, nicht zwingend der urspruenglich fokussierten (letzten) Zeile.

**Graph-Modus (`NavigateDependencyGraphAction.kt`) bekam dieselben E/C-Tastenkuerzel + denselben
Footer nachgeruestet** (`content.actionMap.put("edit"/"copy", ...)`, operiert auf
`orderedRows[selectedIndex]`) - seine eigene Nummerierung/Navigation/Sektions-Header (Abschnitt 24)
bleiben unveraendert, da dort (anders als im CTE-Modus) eine Auswahl grundsaetzlich einen neuen
Knoten/eine neue Liste bedeuten kann.

**`plugin.xml`:** `CopyAction`- und `CopyAndEditAction`-Eintraege entfernt (damit auch ihre `C`/`W`-
Shortcuts unter `Ctrl+#`) - nur noch 2 `<action>`-Eintraege. Bestehende Keymap-Bindungen auf die
geloeschten Action-IDs werden dadurch wirkungslos (kein Fehler, nur tote Bindung) - relevant nur,
falls jemand die IDs `com.ykoellmann.ctexecutor.CopyAction`/`CopyAndEditAction` per Custom-Keymap
referenziert hat.

**`SqlActionBaseTest.kt` durch `ExecuteFromHereActionTest.kt` ersetzt** (2 Tests, gegen eine neue
`internal companion fun ExecuteFromHereAction.computeRows(graph, node)` - dieselbe Testabdeckung wie
vorher, nur an die neue, eigenstaendige Klasse angepasst, da `SqlActionBase`/`buildPopupOptions` nicht
mehr existieren). Weiterhin 31 Tests insgesamt, alle grün.

`SqlAnalyzer.kt`/`SqlBuilder.kt` sind jetzt nirgends mehr importiert/referenziert (verifiziert per
`grep`) - reiner toter Code, weiterhin bewusst nicht geloescht (siehe Abschnitt 26).

Voller Build (`./gradlew build -x test`) und alle 31 Tests laufen grün.

## 28. Nummerierung im CTE-Modus korrigiert: fest statt dynamisch (2026-09-17)

Yannik korrigierte Abschnitt 27: die Nummerierung im CTE-Modus sollte NICHT bei jeder Navigation neu
berechnet werden ("radiiert von der aktuellen Position nach aussen", wie ich es zunaechst gebaut
hatte) - sie ist stattdessen FEST: unterste Zeile immer `1`, nach oben aufsteigend, ein einziges Mal
beim Popup-Aufbau berechnet. Das EINZIGE, was sich bei Navigation aendert: die Zeile, auf der der
Pfeil gerade steht, zeigt ihre Nummer nicht (durch das Run-Icon ersetzt) - alle anderen Zeilen
behalten immer dieselbe Nummer, unabhaengig davon, wo der Pfeil gerade ist.

**Fix in `ExecuteFromHereAction.showPopupFor()`:** `numberByIndex`/`indexByNumber`
(`rows.indices.associateWith { i -> rows.size - i }`, invertiert) einmalig vor der Navigation
berechnet, nicht mehr bei jeder Bewegung. `select(index)` aktualisiert jetzt nur noch GENAU 2 Zeilen
(`refreshRow(previous)`, `refreshRow(selectedIndex)`) statt aller - `refreshRow(j)` zeigt die feste
Nummer aus `numberByIndex`, ausser `j == selectedIndex` (dann `null`, Icon statt Nummer).
Zifferntasten-Bindungen (1-9) sind jetzt wieder statisch (einmal ueber `indexByNumber` gebunden,
analog zum Graph-Modus) statt dynamisch pro Tastendruck aufgeloest - weil die Nummer-zu-Zeile-
Zuordnung sich ja nicht mehr aendert.

Voller Build (`./gradlew build -x test`) und alle 31 Tests laufen grün.

## 29. "E" (Editor einfuegen) platziert den Cursor relativ zur Original-Position (2026-09-17)

Yannik wollte: beim Einfuegen mit E soll der Cursor - wenn die bearbeitete Zeile genau der Bereich
ist, in dem der Cursor beim Aufruf der Action urspruenglich stand - RELATIV zu dieser urspruenglichen
Position im eingefuegten SQL landen (nicht am Ende). Fuer jede andere Zeile (z.B. eine weiter oben
liegende CTE, in der der Cursor nie stand) reicht "ans Ende des eingefuegten SQL" wie bisher.

**`DependencyGraphSqlBuilder.BuildResult` bekommt ein neues Feld `sourceCaretMapping:
SourceCaretMapping?`** (`data class SourceCaretMapping(val sourceElement: PsiElement, val
offsetInSql: Int)`). `assemble()` berechnet `offsetInSql` VOR dem Hinzufuegen der Hauptanweisung zu
`parts` (Summe der Laengen der bisherigen Teile + Zeilenumbrueche). `sourceElement` ist das PSI-
Element, dessen `.text` woertlich zur Hauptanweisung wurde (`targetBody` fuer eine nicht-rekursive
CTE, `node.element` fuer alle Nicht-CTE-Knotenarten) - `null`, wenn die Hauptanweisung KEIN
woertlicher Auszug ist (der `SELECT * FROM name`-Wrapper fuer rekursive CTE-Ziele, siehe Abschnitt 26)
- dort gibt es keine sinnvolle 1:1-Zuordnung, Cursor landet dann immer am Ende.

**Neue Erweiterungsfunktion `SourceCaretMapping.resolveCaretOffset(originalCaretOffset): Int?`**
(`SqlPopupSupport.kt`): liegt `originalCaretOffset` (die Cursor-Position beim Aufruf der Action, in
der Original-Datei) innerhalb von `sourceElement.textRange`, liefert sie die passende Position
innerhalb des zusammengebauten SQL-Strings; sonst `null` (die Zeile deckt einen anderen Bereich der
Datei ab als den, wo der Cursor stand).

**`insertSqlIntoEditor()` bekommt einen neuen optionalen Parameter `caretOffsetInSql: Int?`** - wenn
gesetzt, landet der Cursor nach dem Einfuegen an der entsprechenden Position INNERHALB des
eingefuegten SQL statt am Ende. Musste die Offset-Arithmetik der bestehenden Funktion sauber
nachvollziehen (fuehrendes `\n`, optionales `;\n`-Praefix falls das Dokument noch nicht mit `;`
endet, `sql.trim(';')` fuer den eigentlichen Body).

**Beide Actions (`ExecuteFromHereAction`/`NavigateDependencyGraphAction`)**: `Row` bekommt das neue
`sourceCaretMapping`-Feld durchgereicht; `originalCaretOffset` wird einmalig in `actionPerformed()`
festgehalten (bei `NavigateDependencyGraphAction` auch durch `navigateAndReopen()` hindurch
weitergereicht - bleibt ueber mehrere Spruenge hinweg die urspruengliche Position, nicht die des
jeweils neu fokussierten Knotens). Die `edit`-Tastenkuerzel-Aktion berechnet `row.sourceCaretMapping
?.resolveCaretOffset(originalCaretOffset)` und reicht das Ergebnis an `insertSqlIntoEditor()` durch.

**2 neue Tests** in `DependencyGraphSqlBuilderTest.kt` (`testSourceCaretMappingResolvesPositionWithinRawBody`,
`testSourceCaretMappingIsNullForRecursiveWrapperTarget`) - 33 Tests insgesamt, alle grün. Voller Build
laeuft durch.

## 30. Editor-Viewport folgte dem Cursor nach "E" nicht (2026-09-17)

Yannik meldete: die Cursor-Positionierung aus Abschnitt 29 funktioniert, aber die sichtbare
Editor-Ansicht scrollt nicht zur neuen Cursor-Position - `editor.caretModel.moveToOffset(end)` bewegt
den logischen Cursor, aber ohne `scrollingModel.scrollToCaret(...)` bleibt der sichtbare Ausschnitt
unveraendert (Standardverhalten von IntelliJs Editor-API - Cursor-Bewegung und Viewport-Scrolling sind
zwei getrennte Schritte). **Fix:** `editor.scrollingModel.scrollToCaret(ScrollType.CENTER)` direkt
nach `moveToOffset()` in `insertSqlIntoEditor()` ergaenzt (`NavigateDependencyGraphAction.navigateAndReopen()`
hatte das fuer die eigene Cursor-Bewegung schon, `insertSqlIntoEditor()` bisher nicht). Voller Build
und alle 33 Tests laufen weiterhin grün.

## 10. Repo-Zustand jetzt & bekannte Probleme

- Branch `feature/dependency-graph`, von `main`, nicht gepusht.
- Uncommitted: `.gitignore` (Eintrag für `Claude outputs/`, Mockup-HTMLs aus der Planungssession —
  gehören nicht ins Repo), `build.gradle.kts` (siehe unten), `src/test/kotlin/com/ykoellmann/ctexecutor/spike/PsiResolvingSpikeTest.kt`,
  `src/test/kotlin/com/ykoellmann/ctexecutor/testutil/TestQueries.kt`.
- `build.gradle.kts`-Änderung: `testImplementation("junit:junit:4.13.2")` ergänzt (behebt
  `Cannot access 'junit.framework.TestCase'`). Version des `intellij-platform`-Gradle-Plugins bewusst
  bei 2.10.5 belassen (2.19.0 würde Gradle 9 verlangen, siehe Abschnitt 3).
- **Test-Sandbox-Problem (ungelöst, nicht blockierend für Schritt 1):** der `test`-Gradle-Task lädt in
  seiner Sandbox kaum Bundled Plugins — `FileTypeManager.getInstance().registeredFileTypes` zeigte nur
  eine Handvoll Basis-Typen (kein SQL, kein Kotlin, kein Java). `.sql`-Testdateien via
  `myFixture.configureByText()` werden dadurch als `PlainTextFileType` geparst statt als echtes SQL-PSI,
  jeder darauf aufbauende Test schlägt fehl (nicht weil PSI-Resolving nicht geht, sondern weil nie
  echte SQL-PSI-Struktur entsteht). Grund nicht abschließend geklärt — vermutlich ein Bug/eine Lücke im
  veralteten `intellij-platform`-Gradle-Plugin 2.10.5 rund um Plugin-Aktivierung in Test-Sandboxes.
  `PsiResolvingSpikeTest.kt` enthält Diagnose-Tests (`testDumpPsiTreeForSimpleWith`,
  `testWhatFileTypeIsRegisteredForSqlExtension`), die das reproduzieren — als Ausgangspunkt für die
  Fehlersuche in Rollout-Schritt 2 nutzen, nicht neu erfinden.
- `.git/index.lock` tauchte während dieser Session einmal transient auf (wahrscheinlich IntelliJs
  eigener VCS-Hintergrundprozess auf Yanniks laufender IDE) — `git status` funktionierte trotzdem. Falls
  das persistent stört: prüfen, ob ein anderer Prozess (IDE, ein anderes Terminal) gerade eine
  Git-Operation laufen hat, bevor man den Lock manuell entfernt.
- Kein PR, kein Push. Alles lokal auf `pc-yannik`.

## 11. Offene Punkte, die noch menschliche Entscheidung brauchen

- **Shortcut für die neue Navigation-Action** (Vorschlag `Ctrl+#`+`N`, Abschnitt 7) — nicht blockierend,
  kann während Rollout-Schritt 4 final entschieden werden.
- **DB2-for-i-Dialekt-Abdeckung** nie verifiziert (Spike lief nur gegen generisches SQL) — Risiko für
  Abschnitt 5 (Dialekt-Strategie), relevant spätestens bei Rollout-Schritt 5 (Validierung).
- **WITH-RECURSIVE-Syntax beim SQL-Zusammenbau** (`WITH RECURSIVE` statt `WITH` erzeugen) ist als
  Anforderung benannt (4.5), aber noch nicht konkret entworfen — Teil von Rollout-Schritt 3.

## 12. Testquery-Korpus (Referenz)

`src/test/kotlin/com/ykoellmann/ctexecutor/testutil/TestQueries.kt` — 7 Fixtures, angelegt für
Rollout-Schritt 2, jede auf genau ein Problem zugeschnitten (nicht als realistisches Gesamtbeispiel
gedacht): `NESTED_WITH_SHADOWING`, `WITH_RECURSIVE_SELF_REFERENCE`, `UNION_INSIDE_CTE`,
`LATERAL_JOIN_POSTGRES`, `CORRELATED_WHERE_SUBQUERY`, `UNCORRELATED_FROM_SUBQUERY`,
`QUALIFIED_REFERENCE_COLLISION`. Vier davon wurden im Spike (Abschnitt 6) bereits manuell verifiziert
(NESTED_WITH_SHADOWING, WITH_RECURSIVE_SELF_REFERENCE, CORRELATED_WHERE_SUBQUERY, plus die einfache
Referenz aus Test 1). UNION_INSIDE_CTE, LATERAL_JOIN_POSTGRES, UNCORRELATED_FROM_SUBQUERY,
QUALIFIED_REFERENCE_COLLISION sind noch nicht getestet, weder automatisiert noch manuell.

## Anhang: Relevante Ist-Code-Muster (für Generalisierung)

`collectTableRefs` (Datei `analyzer/SqlAnalyzer.kt`, top-level `internal fun`, nicht Teil der Klasse):

```kotlin
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
```

`isDirectCteBodySelect`/`isUnionContainer` (Muster für UNION-Erkennung, in `discoverScope`
weiterzuverwenden):

```kotlin
private fun isDirectCteBodySelect(select: PsiElement, cteElement: PsiElement): Boolean {
    var current = select.parent
    while (current != null && current != cteElement) {
        if (current.elementType == SqlElementTypes.SQL_SELECT_STATEMENT) return false
        current = current.parent
    }
    return current == cteElement
}

private fun isUnionContainer(element: PsiElement): Boolean =
    element.children.count { it.elementType == SqlElementTypes.SQL_SELECT_STATEMENT } > 1
```

## 23. Popup-Header-Redesign — Icons statt Pfeile (entschieden, 2026-09-17, Umsetzung noch offen)

Nach mehreren HTML-Mockup-Iterationen mit Yannik (parallele Konversation, nicht im Repo) ist die
Richtung entschieden: **die Pfeile (▲/▼) vor den Dependency-/Consumer-Zeilen in `RowView` verschwinden
und werden durch je einen Sektions-Header mit echtem IntelliJ-Platform-Icon ersetzt** ("V1a" aus der
Mockup-Serie). Grund: Pfeil-pro-Zeile UND Sektionszugehörigkeit codieren dieselbe Information doppelt
— bei mehr als 2-3 Einträgen pro Richtung nur noch Redundanz. Position (welcher Block) reicht als
Richtungssignal.

**Diese Änderung ist rein additiv innerhalb von `NavigateDependencyGraphAction.kt`, betrifft NICHTS
an `orderedRows`/`selectedIndex`/Tastatur-Handling/Highlighting/Ausführung.** Header-Panels sind reine
Deko, nicht Teil von `orderedRows`, kein Klick-/Tastatur-Handler nötig.

### Icons — final

`com.intellij.icons.AllIcons.Hierarchy.Supertypes` (Dependencies-Header) und
`AllIcons.Hierarchy.Subtypes` (Used-by-Header) — 16×16, dieselben Icons, die IntelliJs eigener
Type-Hierarchy-Umschalter nutzt ("hoch/runter durch einen Graphen navigieren", exakt unsere
Semantik). Verifiziert gegen die echte Icon-Quelle: `platform/util/ui/src/com/intellij/icons/AllIcons.java`,
Branch `idea/252.27397.103` (passend zu `sinceBuild=252`):
https://github.com/JetBrains/intellij-community/blob/idea/252.27397.103/platform/util/ui/src/com/intellij/icons/AllIcons.java#L681-L688
— Feldnamen sind korrekt, das exakte SVG-Aussehen vor dem Einbau selbst gegenpruefen (z. B.
https://intellij-icons.jetbrains.design/, Suche "supertypes"/"subtypes"). Kein manuelles Icon-Theming
noetig — `Icon`-Objekte aus `AllIcons` passen sich automatisch an Hell/Dunkel-Theme an, anders als in
den HTML-Mockups (dort handgezeichnete SVG-Naeherungen).

Verworfen: `AllIcons.Gutter.OverridingMethod`/`OverridenMethod` (semantisch auch passend, aber kleiner/
12×12 und stärker mit "Methoden-Override" statt "Graph-Richtung" assoziiert), Text-Pills, farbige
Randleisten, Badge-Formen — alle in der Mockup-Serie verworfen zugunsten des Header-Ansatzes.

### Zeilenformat — final

`title: SQL`-Format bleibt (Yanniks expliziter Wunsch, wie die 3 bestehenden Actions es heute schon
machen). Die aktuelle Zeile bekommt ihre SQL-Vorschau ebenfalls (war in `RowView` bereits so
implementiert, Abschnitt 17 — keine Änderung nötig, nur in den HTML-Mockups initial fälschlich
weggelassen). `▶`-Marker vor der aktuellen Zeile bleibt (Yannik mag den "Titel am Anfang"-Stil,
kein Grund ihn zu entfernen — die aktuelle Zeile ist eh die einzige in ihrer Sektion).

### Konkrete Codeänderung in `NavigateDependencyGraphAction.kt`

**1) `RowView.displayTextFor()` — Pfeil fuer DEPENDENCY/CONSUMER entfernen:**

```kotlin
private fun displayTextFor(row: Row): String {
    val prefix = when (row.kind) {
        RowKind.CURRENT -> "▶ ${row.node.displayName}: "
        else -> "${row.number} ${row.node.displayName}: "
    }
    val sqlPreview = row.sql.trim().replace(Regex("\\s+"), " ")
    val maxDisplayLength = 60
    val availableLength = (maxDisplayLength - prefix.length).coerceAtLeast(0)
    return if (sqlPreview.length > availableLength) {
        "$prefix${sqlPreview.take(availableLength)}..."
    } else {
        "$prefix$sqlPreview"
    }
}
```

**2) Neue private Hilfsfunktion `sectionHeader()` (auf Klassenebene, z. B. neben `showPopupFor`):**

```kotlin
private fun sectionHeader(text: String, icon: Icon): JPanel {
    val panel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
    panel.isOpaque = false
    panel.border = JBUI.Borders.empty(6, 8, 2, 8)
    panel.add(JLabel(icon))
    val label = JLabel(text)
    label.foreground = UIManager.getColor("Label.disabledForeground")
    label.font = label.font.deriveFont(Font.PLAIN, (label.font.size2D - 1f).coerceAtLeast(10f))
    panel.add(label)
    return panel
}
```

Zusaetzliche Imports: `com.intellij.icons.AllIcons`, `javax.swing.Icon`, `java.awt.FlowLayout`.

**3) `showPopupFor()` — Sektionsaufbau um Header erweitern (ersetzt den bisherigen Block):**

```kotlin
if (dependencyViews.isNotEmpty()) {
    content.add(sectionHeader("Dependencies", AllIcons.Hierarchy.Supertypes))
    dependencyViews.forEach { content.add(it.panel) }
    content.add(JSeparator())
}
content.add(currentView.panel)
if (consumerViews.isNotEmpty()) {
    content.add(JSeparator())
    content.add(sectionHeader("Used by", AllIcons.Hierarchy.Subtypes))
    consumerViews.forEach { content.add(it.panel) }
}
```

Header nur, wenn die jeweilige Sektion nicht leer ist — nutzt exakt dieselbe
`isNotEmpty()`-Bedingung, die schon fuer die `JSeparator`s existiert, keine neue Sonderfall-Logik.

### Nummerierung — Korrektur einer eigenen Fehleinschaetzung

In der Mockup-Diskussion wurde kritisiert, die Nummerierung sei "willkuerlich" (2 oben, 1 unten) und
sollte top-to-bottom in Lesereihenfolge laufen. Das war ein Urteil ohne Kenntnis von Abschnitt 7/15
dieses Dokuments: die Nummerierung "radiiert von der aktuellen Zeile nach aussen" ist eine bewusste,
dokumentierte Entscheidung (naeher an der aktuellen Zeile = niedrigere, schneller erreichbare Zahl),
keine Inkonsistenz. Nach Kenntnis der Begruendung wird diese Kritik zurueckgezogen — bleibt unveraendert
wie in `buildRows()` implementiert.

### Nicht Teil dieser Aenderung

- Keine Aenderung an `orderedRows`, `selectedIndex`, Pfeiltasten-/Zifferntasten-Handling, Highlighting,
  `choose()`, `navigateAndReopen()` — alles aus Abschnitt 21/22 bleibt exakt so.
- Keine Aenderung an `SqlAnalyzer.kt`/`SqlBuilder.kt`/den 3 bestehenden Actions.
- Kein neuer Unit-Test fuer die Popup-UI (weiterhin kein etabliertes Testmuster im Repo, Abschnitt 15) —
  Verifikation weiterhin nur manuell via `runIde`, Teil von Rollout-Schritt 5.

### Naechster Schritt

Die drei Codeaenderungen oben in `NavigateDependencyGraphAction.kt` umsetzen, `./gradlew build -x test`
+ komplette Testsuite gruen halten (reine UI-Kosmetik, sollte keinen der 28 bestehenden Tests
beruehren), danach manuell in `runIde` gegenpruefen (Icon-Rendering, Header-Ausrichtung bei leerer
Dependency- oder Consumer-Sektion, Light- und Dark-Theme).
