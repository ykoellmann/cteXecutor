package com.ykoellmann.ctexecutor.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.TextRange
import com.intellij.util.ui.JBUI
import com.ykoellmann.ctexecutor.analyzer.DependencyGraphBuilder
import com.ykoellmann.ctexecutor.analyzer.DependencyGraphSqlBuilder
import com.ykoellmann.ctexecutor.model.DependencyGraph
import com.ykoellmann.ctexecutor.model.DependencyNode
import java.awt.BorderLayout
import java.awt.Font
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.KeyStroke
import javax.swing.UIManager

/**
 * "Graph-Modus" (HANDOFF.md Abschnitt 7/14-22/27) - die zweite von nur noch 2 verbleibenden Actions
 * (neben `ExecuteFromHereAction`, dem "CTE-Modus"). Verdrahtet auf
 * DependencyGraphBuilder/DependencyGraph/DependencyGraphSqlBuilder, Ausfuehrung weiterhin ueber
 * `SqlExecutor.executeSqlSeamlessly` (Paste + native DB-Konsole, unveraendert).
 *
 * Unterstuetzt dieselben 3 Interaktionen auf der aktuell fokussierten Zeile wie der CTE-Modus:
 * Enter (ausfuehren, nur auf der CURRENT-Zeile sinnvoll/erlaubt), E (in den Editor einfuegen), C
 * (in die Zwischenablage kopieren) - siehe SqlPopupSupport.kt.
 *
 * UI-Aufbau: DREI visuell getrennte Zeilen-Gruppen (Dependencies/Current/Consumers), durch echte
 * native `JSeparator`s getrennt - aber bewusst OHNE `JList`/dessen eingebautes Selektionsmodell.
 * Grund: mit 3 unabhaengigen `JList`s hatte jede ihr eigenes Selektionsmodell/ihren eigenen Fokus-
 * Indikator, wodurch mehrere Zeilen GLEICHZEITIG optisch markiert wirken konnten - das Popup soll
 * sich aber wie EINE einzige Liste verhalten (genau eine markierte Zeile insgesamt, unabhaengig
 * davon, in welcher Sektion sie liegt). Deshalb: einfache `JPanel`-Zeilen (`RowView`) + EIN
 * gemeinsamer, manuell verwalteter Selektionszustand (`selectedIndex`) + Tastatur-Handling auf dem
 * GESAMTEN Popup-Inhalt (`content`), nicht pro Zeile/Liste. Das umgeht auch JBLists eingebautes
 * Type-Ahead/Speed-Search, das Zifferntasten sonst fuer die eigene "spring zu Eintrag mit diesem
 * Anfangsbuchstaben"-Suche abgefangen und NIE beim eigentlichen Zifferntasten-Shortcut ankommen
 * liess (das war der Grund, warum die Zifferntasten-Navigation zuvor nicht funktionierte).
 *
 * Sicherheitsprinzip (Abschnitt 2): Springen != Ausfuehren. Eine Zeile anklicken/mit Enter
 * bestaetigen navigiert bei Dependency-/Consumer-Zeilen nur; nur die aktuelle Zeile fuehrt aus.
 */
class NavigateDependencyGraphAction : AnAction() {

    private enum class RowKind { DEPENDENCY, CURRENT, CONSUMER }

    private data class Row(
        val node: DependencyNode,
        val kind: RowKind,
        val number: Int?,
        val sql: String,
        val highlightRanges: List<TextRange>,
        val sourceCaretMapping: DependencyGraphSqlBuilder.SourceCaretMapping? = null
    )

    /** Eine gerenderte Zeile (Panel + Label) mit manuell umschaltbarem Selektions-Stil. */
    private class RowView(val row: Row) {
        val label = JLabel()
        val panel = JPanel(BorderLayout())

        init {
            panel.isOpaque = true
            panel.border = JBUI.Borders.empty(4, 8, 4, 8)
            panel.add(label, BorderLayout.WEST)
            if (row.kind == RowKind.CURRENT) {
                label.font = label.font.deriveFont(Font.BOLD)
                // Gruener Run-Pfeil statt eines Text-Markers vor der aktuellen Zeile.
                label.icon = AllIcons.Actions.Execute
                label.iconTextGap = 6
            }
            label.text = displayTextFor(row)
            setSelected(false)
        }

        fun setSelected(selected: Boolean) {
            panel.background = UIManager.getColor(if (selected) "List.selectionBackground" else "List.background")
            label.foreground = UIManager.getColor(if (selected) "List.selectionForeground" else "List.foreground")
            panel.repaint()
        }

        companion object {
            private fun displayTextFor(row: Row): String {
                // V1a (siehe claude/rewrite-analyse-und-plan.md Abschnitt 7): kein Pfeil-Symbol mehr
                // vor Dependency-/Consumer-Zeilen - die Sektionszugehoerigkeit wird bereits durch den
                // Icon-Header ueber dem jeweiligen Block angezeigt (siehe sectionHeader()), ein Pfeil
                // pro Zeile waere doppelt kodierte Information. Die aktuelle Zeile bekommt stattdessen
                // den gruenen Run-Pfeil als echtes Icon (siehe RowView.init), kein Text-Marker hier.
                val number = row.number?.let { "$it " } ?: ""
                val prefix = "$number${row.node.displayName}: "

                val sqlPreview = row.sql.trim().replace(Regex("\\s+"), " ")
                val maxDisplayLength = 60
                val availableLength = (maxDisplayLength - prefix.length).coerceAtLeast(0)
                return if (sqlPreview.length > availableLength) {
                    "$prefix${sqlPreview.take(availableLength)}..."
                } else {
                    "$prefix$sqlPreview"
                }
            }
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val offset = editor.caretModel.offset
        // PSI-Zugriff (Graph-Aufbau, nodeAt()) braucht eine Read-Action - anders als bei einer
        // regulaeren AnAction.actionPerformed-Ausfuehrung durch die Platform ist das hier NICHT
        // automatisch garantiert, siehe navigateAndReopen()/showPopupFor() fuer den eigentlichen
        // Grund (dort wird es aus einem rohen Swing-Tastenkuerzel heraus aufgerufen).
        val (graph, start) = runReadAction {
            val g = DependencyGraphBuilder().build(file)
            g to g.nodeAt(offset)
        }
        start ?: return

        showPopupFor(graph, start, editor, offset)
    }

    private fun buildRows(graph: DependencyGraph, node: DependencyNode): Triple<List<Row>, Row, List<Row>> {
        val consumers = node.consumerIds.map(graph::resolve)
        // Selbstschleife (rekursive CTE) wird nicht als eigene, navigierbare Zeile gezeigt -
        // "springe zu dir selbst" waere kein sinnvoller Navigationsschritt.
        //
        // Positions-sortiert (nach der TEXTPOSITION der jeweiligen Dependency-Definition), NICHT in
        // der Reihenfolge von node.dependencyIds (das ist nur die Entdeckungsreihenfolge der
        // Referenzen im Text von `node` selbst, hat nichts mit der Position der Dependency-CTEs zu
        // tun und fuehrte zu einer effektiv zufaelligen/oft "falschrum" wirkenden Nummerierung).
        // Aufsteigend sortiert: die zuerst im Dokument definierte (am weitesten "oben"/entferntesten)
        // Dependency landet oben im Dependency-Block mit der HOECHSTEN Nummer, die zuletzt/am
        // naechsten zur aktuellen Zeile definierte landet direkt darueber mit der NIEDRIGSTEN Nummer.
        val dependencies = node.dependencyIds
            .filter { it != node.id }
            .map(graph::resolve)
            .sortedBy { it.element.textRange.startOffset }

        fun toRow(n: DependencyNode, kind: RowKind, number: Int?): Row {
            val result = DependencyGraphSqlBuilder.build(n, graph)
            return Row(n, kind, number, result.sql, result.highlightRanges, result.sourceCaretMapping)
        }

        // Nummerierung radiert von der aktuellen Zeile nach aussen (HANDOFF.md Abschnitt 7): unten
        // (Konsumenten) aufsteigend ab 1 direkt unter dem aktuellen Eintrag, oben (Abhaengigkeiten)
        // fortlaufend danach - der Eintrag direkt ueber dem aktuellen bekommt die niedrigste Zahl der
        // oberen Sektion, der am weitesten entfernte die hoechste.
        val dependencyRows = dependencies.mapIndexed { i, dep ->
            toRow(dep, RowKind.DEPENDENCY, consumers.size + dependencies.size - i)
        }
        val consumerRows = consumers.mapIndexed { i, consumer -> toRow(consumer, RowKind.CONSUMER, i + 1) }
        val currentRow = toRow(node, RowKind.CURRENT, null)

        return Triple(dependencyRows, currentRow, consumerRows)
    }

    private fun showPopupFor(graph: DependencyGraph, node: DependencyNode, editor: Editor, originalCaretOffset: Int) {
        // buildRows() greift auf PSI zu (TextRange, .text, Referenz-Resolving via
        // DependencyGraphSqlBuilder) - beim ersten Aufbau ueber actionPerformed() unproblematisch,
        // aber ueber navigateAndReopen() wird das aus einem rohen Swing-Tastenkuerzel/Klick heraus
        // aufgerufen (kein automatischer Read-Action-Kontext), siehe Klassen-Doc-Kommentar.
        val (dependencyRows, currentRow, consumerRows) = runReadAction { buildRows(graph, node) }
        // Anzeige-/Navigationsreihenfolge von oben nach unten - UP/DOWN bewegt sich entlang dieser
        // einen gemeinsamen Liste, unabhaengig von der visuellen Gruppierung in 3 Sektionen.
        val orderedRows = dependencyRows + currentRow + consumerRows

        // Bewusst LOKAL statt eines geteilten Instanzfelds: eine Navigation schliesst dieses Popup
        // und oeffnet sofort ein neues (siehe navigateAndReopen). Waeren die Highlighter in einem
        // ueber Popup-Instanzen geteilten Zustand, koennte das verzoegerte onClosed des ALTEN Popups
        // die frisch gesetzten Highlights des NEUEN Popups wieder loeschen (Race Condition).
        val highlighters = mutableListOf<RangeHighlighter>()

        fun applyHighlights(ranges: List<TextRange>) {
            highlighters.forEach { editor.markupModel.removeHighlighter(it) }
            highlighters.clear()
            val colorScheme = EditorColorsManager.getInstance().globalScheme
            val highlightColor = colorScheme.getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES).backgroundColor
            for (range in ranges) {
                highlighters.add(
                    editor.markupModel.addRangeHighlighter(
                        range.startOffset,
                        range.endOffset,
                        HighlighterLayer.SELECTION - 1,
                        TextAttributes(null, highlightColor, null, null, 0),
                        HighlighterTargetArea.EXACT_RANGE
                    )
                )
            }
        }

        lateinit var popupRef: JBPopup
        lateinit var choose: (Row) -> Unit
        val rowViews = orderedRows.map { RowView(it) }
        var selectedIndex = orderedRows.indexOf(currentRow)

        fun select(index: Int) {
            if (index < 0 || index >= orderedRows.size || index == selectedIndex) return
            rowViews[selectedIndex].setSelected(false)
            selectedIndex = index
            rowViews[selectedIndex].setSelected(true)
            applyHighlights(orderedRows[selectedIndex].highlightRanges)
        }

        rowViews.forEach { view ->
            view.panel.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    select(orderedRows.indexOf(view.row))
                    choose(view.row)
                }
            })
        }

        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.border = JBUI.Borders.empty(4)
        content.isFocusable = true

        val dependencyViews = rowViews.subList(0, dependencyRows.size)
        val currentView = rowViews[dependencyRows.size]
        val consumerViews = rowViews.subList(dependencyRows.size + 1, rowViews.size)

        if (dependencyViews.isNotEmpty()) {
            content.add(sectionHeader(AllIcons.Hierarchy.Supertypes, "Dependencies"))
            dependencyViews.forEach { content.add(it.panel) }
            content.add(JSeparator())
        }
        content.add(currentView.panel)
        if (consumerViews.isNotEmpty()) {
            content.add(JSeparator())
            content.add(sectionHeader(AllIcons.Hierarchy.Subtypes, "Used by"))
            consumerViews.forEach { content.add(it.panel) }
        }
        content.add(JSeparator())
        content.add(buildShortcutFooter())

        // Tastatur-Handling auf dem GESAMTEN Popup-Inhalt statt pro Zeile - siehe Klassen-
        // Doc-Kommentar fuer den Grund.
        content.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "navigateUp")
        content.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "navigateDown")
        content.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "chooseCurrent")
        content.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_E, 0), "edit")
        content.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, 0), "copy")
        content.actionMap.put("navigateUp", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = select(selectedIndex - 1)
        })
        content.actionMap.put("navigateDown", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = select(selectedIndex + 1)
        })
        content.actionMap.put("chooseCurrent", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                choose(orderedRows[selectedIndex])
            }
        })
        content.actionMap.put("edit", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                val row = orderedRows[selectedIndex]
                // Cursor relativ zur urspruenglichen Position im Original-SQL platzieren, falls diese
                // Zeile genau der Bereich ist, in dem der Cursor beim Aufruf der Action stand - sonst
                // (z.B. eine Dependency-/Consumer-Zeile oder nach einem Sprung) ans Ende des SQL.
                val caretOffset = runReadAction { row.sourceCaretMapping?.resolveCaretOffset(originalCaretOffset) }
                insertSqlIntoEditor(editor, row.sql, caretOffset)
                popupRef.cancel()
            }
        })
        content.actionMap.put("copy", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                copySqlToClipboard(orderedRows[selectedIndex].sql)
                popupRef.cancel()
            }
        })
        (1..9).forEach { n ->
            orderedRows.firstOrNull { it.number == n }?.let { row ->
                val actionKey = "jumpTo$n"
                content.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_0 + n, 0), actionKey)
                content.actionMap.put(actionKey, object : AbstractAction() {
                    override fun actionPerformed(e: ActionEvent) = choose(row)
                })
            }
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, content)
            .setTitle("Dependency Graph")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setCancelKeyEnabled(true)
            .createPopup()
        popupRef = popup

        choose = { row ->
            if (row.kind == RowKind.CURRENT) {
                SqlExecutor.executeSqlSeamlessly(editor, row.sql)
                popupRef.cancel()
            } else {
                popupRef.cancel()
                navigateAndReopen(graph, row.node, editor, originalCaretOffset)
            }
        }

        rowViews[selectedIndex].setSelected(true)
        applyHighlights(orderedRows[selectedIndex].highlightRanges)

        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                highlighters.forEach { editor.markupModel.removeHighlighter(it) }
                highlighters.clear()
            }
        })
        popup.showInBestPositionFor(editor)
    }

    /** Springen != Ausfuehren: bewegt nur den Cursor und zeigt (statt eines zweiten Popups daneben) ein neues Popup an derselben Stelle. */
    private fun navigateAndReopen(graph: DependencyGraph, node: DependencyNode, editor: Editor, originalCaretOffset: Int) {
        val targetOffset = runReadAction { node.element.textRange.startOffset }
        editor.caretModel.moveToOffset(targetOffset)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        showPopupFor(graph, node, editor, originalCaretOffset)
    }

    /**
     * V1a-Sektions-Header (claude/rewrite-analyse-und-plan.md Abschnitt 7): echtes IntelliJ-Icon +
     * Label ueber dem jeweiligen Block statt eines Pfeil-Symbols pro Zeile. Rein dekorativ/nicht
     * interaktiv - nur bei nicht-leerer Sektion eingefuegt (siehe Aufrufstellen in showPopupFor()).
     */
    private fun sectionHeader(icon: javax.swing.Icon, text: String): JPanel {
        val header = JPanel(BorderLayout())
        header.isOpaque = false
        header.border = JBUI.Borders.empty(4, 8, 2, 8)
        val label = JLabel(text, icon, JLabel.LEFT)
        label.foreground = UIManager.getColor("Label.disabledForeground") ?: UIManager.getColor("Label.foreground")
        label.font = label.font.deriveFont(Font.BOLD, label.font.size2D - 1f)
        header.add(label, BorderLayout.WEST)
        return header
    }
}
