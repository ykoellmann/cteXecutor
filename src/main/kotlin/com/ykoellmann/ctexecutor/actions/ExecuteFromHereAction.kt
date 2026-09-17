package com.ykoellmann.ctexecutor.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
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
import com.ykoellmann.ctexecutor.model.NodeKind
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
 * "CTE-Modus": aktuelles Statement + seine benoetigten CTEs, in Definitionsreihenfolge, als EINE
 * navigierbare Liste (HANDOFF.md Abschnitt 27). Nachfolger der alten Kombination aus
 * ExecuteFromHereAction (Ausfuehren), CopyAction und CopyAndEditAction (jetzt E/C-Tastenkuerzel
 * innerhalb dieses einen Popups statt 3 getrennter Actions/Shortcuts, siehe SqlPopupSupport.kt).
 *
 * Anders als der Graph-Modus (NavigateDependencyGraphAction) ist die Zeilenliste hier VOLLSTAENDIG
 * und STATISCH bekannt (die komplette CTE-Kette bis zum aktuellen Statement) - Navigation bewegt
 * deshalb nur einen gemeinsamen `selectedIndex` INNERHALB dieser einen Liste, statt bei jeder
 * Auswahl eine neue Popup-Seite fuer einen neuen Knoten aufzubauen. Der `▶`-Marker (gruener
 * Run-Pfeil) folgt dem `selectedIndex`. Die Nummerierung selbst ist FEST (1 unten bis N oben, einmal
 * beim Popup-Aufbau berechnet) - bewegt sich der Pfeil, wird nur die Nummer DER ZEILE, auf der der
 * Pfeil gerade steht, ausgeblendet (durch das Icon ersetzt); alle anderen Zeilen behalten immer
 * dieselbe Nummer.
 */
class ExecuteFromHereAction : AnAction() {

    internal data class Row(
        val label: String,
        val sql: String,
        val highlightRanges: List<TextRange>,
        val sourceCaretMapping: DependencyGraphSqlBuilder.SourceCaretMapping? = null
    )

    /** Zeile mit aktualisierbarem Icon/Nummer-Sichtbarkeit - die Nummer selbst ist fest, nur ob sie
     * angezeigt wird (isCurrent == false) oder durch das Run-Icon ersetzt wird (isCurrent == true)
     * aendert sich bei Navigation. */
    private class RowView(val row: Row) {
        val label = JLabel()
        val panel = JPanel(BorderLayout())

        init {
            panel.isOpaque = true
            panel.border = JBUI.Borders.empty(4, 8, 4, 8)
            panel.add(label, BorderLayout.WEST)
        }

        fun update(number: Int?, isCurrent: Boolean) {
            panel.background = UIManager.getColor(if (isCurrent) "List.selectionBackground" else "List.background")
            label.foreground = UIManager.getColor(if (isCurrent) "List.selectionForeground" else "List.foreground")
            label.font = label.font.deriveFont(if (isCurrent) Font.BOLD else Font.PLAIN)
            if (isCurrent) {
                label.icon = AllIcons.Actions.Execute
                label.iconTextGap = 6
            } else {
                label.icon = null
            }

            val prefix = buildString {
                number?.let { append("$it ") }
                append("${row.label}: ")
            }
            val sqlPreview = row.sql.trim().replace(Regex("\\s+"), " ")
            val maxDisplayLength = 60
            val availableLength = (maxDisplayLength - prefix.length).coerceAtLeast(0)
            label.text = if (sqlPreview.length > availableLength) {
                "$prefix${sqlPreview.take(availableLength)}..."
            } else {
                "$prefix$sqlPreview"
            }
            panel.repaint()
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val graph = DependencyGraphBuilder().build(file)
        val node = graph.nodeAt(editor.caretModel.offset) ?: return

        val rows = computeRows(graph, node)
        if (rows.isEmpty()) return

        // Standardfokus auf das aktuelle Statement (letzter Eintrag) - entspricht der alten
        // Default-Auswahl (`setSelectedValue(displayNames.lastOrNull(), true)`).
        showPopupFor(rows, rows.size - 1, editor, editor.caretModel.offset)
    }

    companion object {
        /**
         * Baut die vollstaendige, statische Zeilenliste (benoetigte CTEs + aktuelles Statement) -
         * als eigene Funktion herausgezogen, damit sie ohne Editor/Popup unit-testbar ist
         * (siehe ExecuteFromHereActionTest.kt).
         */
        internal fun computeRows(graph: DependencyGraph, node: DependencyNode): List<Row> {
            // Positions-sortiert statt eines expliziten Index (wie im alten CteEntry.index) - eine
            // CTE kann nur auf CTEs verweisen, die im Text vor ihr stehen.
            val requiredCtes = graph.transitiveDependencies(node.id)
                .sortedBy { it.element.textRange.startOffset }

            return requiredCtes.mapIndexed { i, cte ->
                // Bewusst die KUMULATIVE Praefix-Liste (erste i Eintraege der GESAMTEN benoetigten
                // CTE-Liste), nicht die individuellen Abhaengigkeiten von `cte` selbst - exaktes
                // Altsystem-Verhalten, siehe HANDOFF.md Abschnitt 26.
                val ctesUpToHere = requiredCtes.subList(0, i)
                val result = DependencyGraphSqlBuilder.buildWithExplicitDependencies(cte, ctesUpToHere)
                Row(cte.displayName, result.sql, result.highlightRanges, result.sourceCaretMapping)
            } + run {
                val currentQueryName = if (node.kind == NodeKind.CTE) node.displayName else "Current Query"
                val result = DependencyGraphSqlBuilder.build(node, graph)
                Row(currentQueryName, result.sql, result.highlightRanges, result.sourceCaretMapping)
            }
        }
    }

    private fun showPopupFor(rows: List<Row>, initialIndex: Int, editor: Editor, originalCaretOffset: Int) {
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

        var selectedIndex = initialIndex
        val rowViews = rows.map { RowView(it) }

        // FEST: unterste Zeile = 1, nach oben aufsteigend - einmalig berechnet, aendert sich NICHT
        // mit der Navigation (nur ob eine Zeile ihre Nummer zeigt oder durch das Icon ersetzt, s.u.).
        val numberByIndex = rows.indices.associateWith { i -> rows.size - i }
        val indexByNumber = numberByIndex.entries.associate { (index, number) -> number to index }

        fun refreshRow(j: Int) {
            val isCurrent = j == selectedIndex
            rowViews[j].update(if (isCurrent) null else numberByIndex.getValue(j), isCurrent)
        }

        fun select(index: Int) {
            if (index < 0 || index >= rows.size || index == selectedIndex) return
            val previous = selectedIndex
            selectedIndex = index
            refreshRow(previous)
            refreshRow(selectedIndex)
            applyHighlights(rows[selectedIndex].highlightRanges)
        }

        rowViews.forEachIndexed { idx, view ->
            view.panel.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = select(idx)
            })
        }

        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.border = JBUI.Borders.empty(4)
        content.isFocusable = true
        rowViews.forEach { content.add(it.panel) }
        content.add(JSeparator())
        content.add(buildShortcutFooter())

        lateinit var popupRef: JBPopup

        content.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "navigateUp")
        content.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "navigateDown")
        content.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "execute")
        content.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_E, 0), "edit")
        content.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, 0), "copy")
        content.actionMap.put("navigateUp", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = select(selectedIndex - 1)
        })
        content.actionMap.put("navigateDown", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = select(selectedIndex + 1)
        })
        content.actionMap.put("execute", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                SqlExecutor.executeSqlSeamlessly(editor, rows[selectedIndex].sql)
                popupRef.cancel()
            }
        })
        content.actionMap.put("edit", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                val row = rows[selectedIndex]
                // Cursor relativ zur urspruenglichen Position im Original-SQL platzieren, falls diese
                // Zeile genau der Bereich ist, in dem der Cursor stand (siehe resolveCaretOffset) -
                // sonst (z.B. eine weiter oben liegende CTE) einfach ans Ende des eingefuegten SQL.
                val caretOffset = row.sourceCaretMapping?.resolveCaretOffset(originalCaretOffset)
                insertSqlIntoEditor(editor, row.sql, caretOffset)
                popupRef.cancel()
            }
        })
        content.actionMap.put("copy", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                copySqlToClipboard(rows[selectedIndex].sql)
                popupRef.cancel()
            }
        })
        indexByNumber.forEach { (number, index) ->
            if (number !in 1..9) return@forEach
            val actionKey = "jumpTo$number"
            content.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_0 + number, 0), actionKey)
            content.actionMap.put(actionKey, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = select(index)
            })
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, content)
            .setTitle("Execute from Here")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setCancelKeyEnabled(true)
            .createPopup()
        popupRef = popup

        rowViews.indices.forEach(::refreshRow)
        applyHighlights(rows[selectedIndex].highlightRanges)

        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                highlighters.forEach { editor.markupModel.removeHighlighter(it) }
                highlighters.clear()
            }
        })
        popup.showInBestPositionFor(editor)
    }
}
