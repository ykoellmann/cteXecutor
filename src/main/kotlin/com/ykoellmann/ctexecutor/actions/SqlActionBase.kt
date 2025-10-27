package com.ykoellmann.ctexecutor.actions

import com.intellij.openapi.actionSystem.AnAction
import com.ykoellmann.ctexecutor.analyzer.SqlDependencyAnalyzer
import com.ykoellmann.ctexecutor.analyzer.SqlBuilder
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
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Base class for all SQL actions that show a popup with CTE/Query selection
 * Uses SqlDependencyAnalyzer for PSI analysis and SqlBuilder for SQL/highlighting generation
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
     *
     * Default behavior: Show all CTEs progressively (each includes all CTEs up to it)
     */
    protected open fun buildPopupOptions(result: SqlDependencyAnalyzer.AnalysisResult): List<PopupOption> {
        val options = mutableListOf<PopupOption>()

        // Add options for each CTE (from first to last)
        for (cte in result.allCtes) {
            // Build SQL for this CTE: include all CTEs up to and including this one
            val ctesUpToThis = result.allCtes.filter { it.index <= cte.index }

            // Use SqlBuilder to generate SQL and highlighting
            val buildResult = SqlBuilder.build(
                ctes = ctesUpToThis,
                targetQuery = cte.element,
                allCtes = result.allCtes,
                mode = SqlBuilder.HighlightMode.PROGRESSIVE_CTE
            )

            options.add(PopupOption(
                displayName = cte.name,
                sql = buildResult.sql,
                highlightRanges = buildResult.highlightRanges
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
     * Called when an option is selected from the popup
     * Must be implemented by subclasses
     */
    protected abstract fun handleSelectedOption(editor: Editor, option: PopupOption)
}