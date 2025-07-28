package com.ykoellmann.ctexecutor

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
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import com.intellij.sql.psi.SqlElementTypes
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import javax.swing.JLabel

class CtePopupExecutor(
    private val editor: Editor,
    private val file: PsiFile
) {
    // List to keep track of active highlights in the editor
    private val highlighters = mutableListOf<RangeHighlighter>()

    // Reference to the currently shown popup
    private var popup: JBPopup? = null

    // Data class representing a single CTE entry
    data class CteEntry(
        val name: String,
        val element: PsiElement,
        val index: Int
    )

    /**
     * Returns the full SQL text for the chosen CTE index.
     */
    fun buildSqlForCteIndex(selectedIndex: Int): String {
        val (ctes, _) = extractCtes(file, editor.caretModel.offset)
        val sql = ctes.filter { it.index <= selectedIndex }
            .mapNotNull { getCteSql(it.element, it.index, selectedIndex) }
            .joinToString(" ") + ";"
        return sql
    }

    /**
     * Shows the popup and returns selected CTE index via callback.
     */
    fun show(title: String, onChosen: (selectedIndex: Int) -> Unit) {
        val caretOffset = editor.caretModel.offset
        val (ctes, activeCte) = extractCtes(file, caretOffset)
        if (ctes.isEmpty()) return

        val options = ctes.map { it.name }
        popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(options)
            .setTitle(title)
            .setResizable(true)
            .setMovable(true)
            .setRenderer { list, value, index, isSelected, cellHasFocus ->
                val label = JLabel(value)
                label.border = JBUI.Borders.empty(6, 12) // Padding (vertical=6, horizontal=12)

                // Optional: Styling für Auswahl
                if (isSelected) {
                    label.background = list.selectionBackground
                    label.foreground = list.selectionForeground
                    label.isOpaque = true
                } else {
                    label.background = list.background
                    label.foreground = list.foreground
                    label.isOpaque = true
                }

                label
            }
            .setItemSelectedCallback { name ->
                val temp = ctes.find { it.name == name } ?: return@setItemSelectedCallback
                val rangesToHighlight = ctes.filter { it.index <= temp.index }
                    .mapNotNull { getCteRange(it.element, it.index, temp.index) }
                highlightRanges(rangesToHighlight)
            }
            .setItemChosenCallback { chosenName ->
                val temp = ctes.find { it.name == chosenName } ?: return@setItemChosenCallback
                onChosen(temp.index)
                popup?.cancel()
            }
            .setSelectedValue(activeCte?.name ?: ctes.last().name, true)
            .createPopup()

        popup?.showInBestPositionFor { editor }
        popup?.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                removeAllHighlights()
            }
        })
    }

    /**
     * Highlights the given list of text ranges inside the editor.
     * Clears any existing highlights before applying new ones.
     */
    private fun highlightRanges(ranges: List<TextRange>) {
        removeAllHighlights()
        var colorScheme = EditorColorsManager.getInstance().globalScheme;
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
     * Removes all highlights applied previously.
     */
    private fun removeAllHighlights() {
        for (highlighter in highlighters) {
            editor.markupModel.removeHighlighter(highlighter)
        }
        highlighters.clear()
    }

    /**
     * Determines the PSI elements to include in the highlighted/executed range,
     * depending on the selected CTE index.
     */
    private fun getCteElements(cteElement: PsiElement, index: Int, selectedIndex: Int): List<PsiElement> {
        val children = cteElement.children
        val elements = mutableListOf<PsiElement>()

        // Special case: if first CTE is selected and there are others, include the "WITH" keyword and whitespace
        if (index == 0 && selectedIndex > 0) {
            val parentChildren = cteElement.parent.children
            val startWithIndex = parentChildren.indexOfFirst { it.elementType == SqlElementTypes.SQL_WITH }
            val endWhitespaceIndex = parentChildren.indexOfFirst { it.elementType == SqlElementTypes.WHITE_SPACE }
            elements.addAll(parentChildren.filterIndexed { i, _ -> i in startWithIndex..endWhitespaceIndex })
        }

        when {
            // Selected CTE: include content inside parentheses (body)
            index == selectedIndex -> {
                val startIndex = children.indexOfFirst { it.elementType == SqlElementTypes.SQL_LEFT_PAREN }
                val endIndex = children.indexOfLast { it.elementType == SqlElementTypes.SQL_RIGHT_PAREN }
                elements.addAll(children.filterIndexed { i, _ -> i in (startIndex + 1) until endIndex })
            }
            // Next CTE after selected: include up to the closing parenthesis
            index + 1 == selectedIndex -> {
                val endIndex = children.indexOfLast { it.elementType == SqlElementTypes.SQL_RIGHT_PAREN }
                elements.addAll(children.filterIndexed { i, _ -> i <= endIndex })
            }
            // Other CTEs: include the full element plus its following sibling (usually comma)
            else -> {
                elements.add(cteElement)
                
                var curElement = cteElement
                do {
                    curElement = curElement.nextSibling
                    curElement.let { elements.add(it) }
                }
                while (curElement.elementType != SqlElementTypes.SQL_COMMA)
            }
        }
        return elements
    }

    /**
     * Returns the combined text range covering the elements to highlight for this CTE.
     */
    private fun getCteRange(cteElement: PsiElement, index: Int, selectedIndex: Int): TextRange? {
        val elements = getCteElements(cteElement, index, selectedIndex)
        if (elements.isEmpty()) return null
        val start = elements.minOf { it.textRange.startOffset }
        val end = elements.maxOf { it.textRange.endOffset }
        return TextRange(start, end)
    }

    /**
     * Returns the SQL text content of the elements to execute for this CTE.
     */
    private fun getCteSql(cteElement: PsiElement, index: Int, selectedIndex: Int): String {
        val elements = getCteElements(cteElement, index, selectedIndex)
        return elements.joinToString(" ") { it.text }
    }

    /**
     * Extracts all CTEs from the PSI file and determines which one is currently active
     * based on the caret offset.
     */
    private fun extractCtes(psiFile: PsiFile, caretOffset: Int): Pair<List<CteEntry>, CteEntry?> {
        val elementAtCaret = psiFile.findElementAt(caretOffset) ?: return Pair(emptyList(), null)

        // Traverse parents until finding the WITH clause node
        var withClause = elementAtCaret.parent
        while (withClause != null 
            && !(withClause.node?.elementType == SqlElementTypes.SQL_WITH 
                    || withClause.node?.elementType == SqlElementTypes.SQL_WITH_QUERY_EXPRESSION)) {
            
            withClause = withClause.parent
        }
        
        if (withClause.elementType == SqlElementTypes.SQL_WITH_QUERY_EXPRESSION) {
            withClause = withClause.children.single{ it.elementType == SqlElementTypes.SQL_WITH_CLAUSE }
        }
        
        if (withClause == null) return Pair(emptyList(), null)

        val children = withClause.children
        val ctes = mutableListOf<CteEntry>()
        var activeCte: CteEntry? = null

        var index = 0
        for (child in children) {
            // Identify named CTE definitions by their element type
            if (child.node?.elementType == SqlElementTypes.SQL_NAMED_QUERY_DEFINITION) {
                val range = child.textRange
                val name = child.firstChild.text
                val entry = CteEntry(name, child, index++)
                ctes.add(entry)

                // Mark active CTE if caret lies within its range
                if (range.containsOffset(caretOffset)) {
                    activeCte = entry
                }
            }
        }
        return Pair(ctes, activeCte)
    }
}
