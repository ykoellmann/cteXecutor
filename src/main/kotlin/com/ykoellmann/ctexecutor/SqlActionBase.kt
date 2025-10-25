package com.ykoellmann.ctexecutor

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
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Base class for all SQL actions that show a popup with CTE/Query selection
 * Uses SqlDependencyAnalyzer for unified analysis
 */
abstract class SqlActionBase : AnAction() {

    private val highlighters = mutableListOf<RangeHighlighter>()
    private var popup: JBPopup? = null

    abstract val title: String

    /**
     * Popup option that can be selected
     */
    data class PopupOption(
        val displayName: String,
        val sql: String,
        val highlightRanges: List<TextRange>
    )

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return

        // Analyze SQL structure
        val analyzer = SqlDependencyAnalyzer(file, editor.caretModel.offset)
        val result = analyzer.analyze() ?: return

        // Build popup options
        val options = buildPopupOptions(result)
        if (options.isEmpty()) return

        // Show popup
        showPopup(editor, options)
    }

    /**
     * Builds the list of options to show in the popup
     * Can be overridden by subclasses for different behavior
     */
    protected open fun buildPopupOptions(result: SqlDependencyAnalyzer.AnalysisResult): List<PopupOption> {
        val options = mutableListOf<PopupOption>()

        // Add options for each CTE (from first to last)
        for (cte in result.allCtes) {
            // Build SQL for this CTE: include all CTEs up to and including this one
            val ctesUpToThis = result.allCtes.filter { it.index <= cte.index }
            val sql = buildSqlForCtes(ctesUpToThis, cte.element)

            // Build highlight ranges for this option
            val highlightRanges = buildHighlightRangesForCte(cte, result.allCtes)

            options.add(PopupOption(
                displayName = cte.name,
                sql = sql,
                highlightRanges = highlightRanges
            ))
        }

        return options
    }

    /**
     * Shows the popup with the given options
     */
    private fun showPopup(editor: Editor, options: List<PopupOption>) {
        val displayNames = options.map { it.displayName }

        popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(displayNames)
            .setTitle(title)
            .setResizable(true)
            .setMovable(true)
            .setRenderer { list, value, _, isSelected, _ ->
                // Find the option to get its SQL
                val option = options.find { it.displayName == value }

                // Outer transparent panel for spacing
                val outerPanel = JPanel(BorderLayout())
                outerPanel.isOpaque = false
                outerPanel.border = JBUI.Borders.empty(2, 4, 2, 4)

                // Inner panel that will hold the label and get colored background
                val innerPanel = JPanel(BorderLayout())
                innerPanel.border = JBUI.Borders.empty(4, 8, 4, 8)

                val label = JLabel()

                if (option != null) {
                    // Clean up SQL: remove newlines, multiple spaces, trim
                    val sqlPreview = option.sql
                        .trim()
                        .replace(Regex("\\s+"), " ") // Replace multiple whitespace with single space

                    // Calculate max length including the name and separator
                    val maxDisplayLength = 50
                    val prefix = "$value: "
                    val availableLength = maxDisplayLength - prefix.length

                    // Format: "name: sql preview..."
                    val displayText = if (sqlPreview.length > availableLength) {
                        "$prefix${sqlPreview.take(availableLength)}..."
                    } else {
                        "$prefix$sqlPreview"
                    }

                    label.text = displayText
                } else {
                    label.text = value
                }

                // Style - only the inner panel gets background
                label.isOpaque = false
                label.foreground = if (isSelected) list.selectionForeground else list.foreground

                innerPanel.background = if (isSelected) list.selectionBackground else list.background
                innerPanel.isOpaque = isSelected

                innerPanel.add(label, BorderLayout.WEST)
                outerPanel.add(innerPanel, BorderLayout.WEST)

                outerPanel
            }
            .setItemSelectedCallback { displayName ->
                val option = options.find { it.displayName == displayName }
                if (option != null) {
                    highlightRanges(editor, option.highlightRanges)
                }
            }
            .setItemChosenCallback { displayName ->
                val option = options.find { it.displayName == displayName }
                if (option != null) {
                    handleSelectedOption(editor, option)
                }
                popup?.cancel()
            }
            .setSelectedValue(displayNames.lastOrNull(), true) // Select last option
            .createPopup()

        popup?.showInBestPositionFor(editor)
        popup?.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                removeAllHighlights(editor)
            }
        })
    }

    /**
     * Highlights the given ranges in the editor
     */
    private fun highlightRanges(editor: Editor, ranges: List<TextRange>) {
        removeAllHighlights(editor)

        val colorScheme = EditorColorsManager.getInstance().globalScheme
        val attributes = colorScheme.getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES)
        val highlightColor = attributes.backgroundColor

        for (range in ranges) {
            val highlighter = editor.markupModel.addRangeHighlighter(
                range.startOffset,
                range.endOffset,
                HighlighterLayer.SELECTION - 1,
                TextAttributes(null, highlightColor, null, null, 0),
                HighlighterTargetArea.EXACT_RANGE
            )
            highlighters.add(highlighter)
        }
    }

    /**
     * Removes all highlights
     */
    private fun removeAllHighlights(editor: Editor) {
        for (highlighter in highlighters) {
            editor.markupModel.removeHighlighter(highlighter)
        }
        highlighters.clear()
    }

    /**
     * Builds SQL for the given CTEs
     */
    protected fun buildSqlForCtes(
        ctes: List<SqlDependencyAnalyzer.CteEntry>,
        targetCte: com.intellij.psi.PsiElement
    ): String {
        val parts = mutableListOf<String>()

        if (ctes.isNotEmpty()) {
            // Build WITH clause
            val cteTexts = mutableListOf<String>()
            for ((idx, cte) in ctes.withIndex()) {
                if (idx < ctes.size - 1) {
                    // Not the selected one: full text
                    cteTexts.add(cte.element.text)
                } else {
                    // Selected CTE: extract inner SELECT
                    val children = cte.element.children
                    val startIndex = children.indexOfFirst {
                        it.elementType == com.intellij.sql.psi.SqlElementTypes.SQL_LEFT_PAREN
                    }
                    val endIndex = children.indexOfLast {
                        it.elementType == com.intellij.sql.psi.SqlElementTypes.SQL_RIGHT_PAREN
                    }

                    if (startIndex >= 0 && endIndex >= 0) {
                        val innerElements = children.filterIndexed { i, _ -> i in (startIndex + 1) until endIndex }
                        cteTexts.add(cte.element.text) // Still add full CTE text
                    } else {
                        cteTexts.add(cte.element.text)
                    }
                }
            }

            parts.add("WITH")
            parts.add(cteTexts.joinToString(",\n"))
        }

        // Add SELECT from the target CTE
        val targetCteName = targetCte.firstChild?.text ?: ""
        parts.add("SELECT * FROM $targetCteName")

        val sql = parts.joinToString("\n")
        return if (sql.trim().endsWith(";")) sql else "$sql;"
    }

    /**
     * Builds highlight ranges for a specific CTE option
     */
    private fun buildHighlightRangesForCte(
        targetCte: SqlDependencyAnalyzer.CteEntry,
        allCtes: List<SqlDependencyAnalyzer.CteEntry>
    ): List<TextRange> {
        val ranges = mutableListOf<TextRange>()

        // Get all CTEs up to and including the target
        val ctesUpToTarget = allCtes.filter { it.index <= targetCte.index }

        for ((idx, cte) in ctesUpToTarget.withIndex()) {
            val children = cte.element.children

            // First CTE: include WITH keyword
            if (idx == 0 && ctesUpToTarget.size > 1) {
                val parentChildren = cte.element.parent.children
                val startWithIndex = parentChildren.indexOfFirst {
                    it.elementType == com.intellij.sql.psi.SqlElementTypes.SQL_WITH
                }
                val endWhitespaceIndex = parentChildren.indexOfFirst {
                    it.elementType == com.intellij.sql.psi.SqlElementTypes.WHITE_SPACE
                }
                if (startWithIndex >= 0 && endWhitespaceIndex >= 0) {
                    val start = parentChildren[startWithIndex].textRange.startOffset
                    val end = parentChildren[endWhitespaceIndex].textRange.endOffset
                    ranges.add(TextRange(start, end))
                }
            }

            when {
                // Target CTE: only inner part
                cte.index == targetCte.index -> {
                    val startIndex = children.indexOfFirst {
                        it.elementType == com.intellij.sql.psi.SqlElementTypes.SQL_LEFT_PAREN
                    }
                    val endIndex = children.indexOfLast {
                        it.elementType == com.intellij.sql.psi.SqlElementTypes.SQL_RIGHT_PAREN
                    }
                    if (startIndex >= 0 && endIndex >= 0) {
                        val innerElements = children.filterIndexed { i, _ -> i in (startIndex + 1) until endIndex }
                        if (innerElements.isNotEmpty()) {
                            val start = innerElements.minOf { it.textRange.startOffset }
                            val end = innerElements.maxOf { it.textRange.endOffset }
                            ranges.add(TextRange(start, end))
                        }
                    }
                }
                // Next CTE after target: up to closing paren
                cte.index + 1 == targetCte.index -> {
                    val endIndex = children.indexOfLast {
                        it.elementType == com.intellij.sql.psi.SqlElementTypes.SQL_RIGHT_PAREN
                    }
                    if (endIndex >= 0) {
                        val elements = children.filterIndexed { i, _ -> i <= endIndex }
                        val start = elements.minOf { it.textRange.startOffset }
                        val end = elements.maxOf { it.textRange.endOffset }
                        ranges.add(TextRange(start, end))
                    }
                }
                // Other CTEs: full element + comma
                else -> {
                    ranges.add(cte.element.textRange)

                    var curElement = cte.element.nextSibling
                    while (curElement != null && curElement.elementType != com.intellij.sql.psi.SqlElementTypes.SQL_COMMA) {
                        curElement = curElement.nextSibling
                    }
                    if (curElement != null) {
                        ranges.add(curElement.textRange)
                    }
                }
            }
        }

        return ranges
    }

    /**
     * Called when an option is selected from the popup
     * Must be implemented by subclasses
     */
    protected abstract fun handleSelectedOption(editor: Editor, option: PopupOption)
}