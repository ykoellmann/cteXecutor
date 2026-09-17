package com.ykoellmann.ctexecutor.actions

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.util.ui.JBUI
import com.ykoellmann.ctexecutor.analyzer.DependencyGraphSqlBuilder
import java.awt.BorderLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.UIManager

/**
 * Geteilte Bausteine fuer die 2 verbleibenden Actions (ExecuteFromHereAction = CTE-Modus,
 * NavigateDependencyGraphAction = Graph-Modus). Beide Popups unterstuetzen dieselben 3 Interaktionen
 * auf der aktuell fokussierten Zeile - Enter (ausfuehren), E (in den Editor einfuegen), C (in die
 * Zwischenablage kopieren) - vormals 3 getrennte Actions (ExecuteFromHereAction/CopyAction/
 * CopyAndEditAction), jetzt Tastenkuerzel innerhalb EINES Popups statt 3 getrennter Popups/Shortcuts.
 */

/** Vormals CopyAction.copyToClipboard(). */
internal fun copySqlToClipboard(sql: String) {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    val selection = StringSelection(sql)
    clipboard.setContents(selection, selection)
}

/**
 * Vormals CopyAndEditAction.insertSql(). [caretOffsetInSql], falls angegeben, ist ein Offset
 * relativ zum Anfang von [sql] (siehe DependencyGraphSqlBuilder.SourceCaretMapping) - der Cursor
 * landet dann an der entsprechenden Position INNERHALB des eingefuegten SQL, statt am Ende. Damit
 * kann z.B. beim Bearbeiten des aktuellen Statements der Cursor relativ zu seiner urspruenglichen
 * Position im Original-SQL landen, statt immer ganz ans Ende zu springen.
 */
internal fun insertSqlIntoEditor(editor: Editor, sql: String, caretOffsetInSql: Int? = null) {
    val body = sql.trim(';')
    var finalSql = "$body\n;"
    val needsLeadingSemicolon = !editor.document.text.trim(' ').endsWith(";")
    if (needsLeadingSemicolon) {
        finalSql = ";\n$finalSql"
    }

    val insertionPoint = editor.document.textLength

    WriteCommandAction.runWriteCommandAction(editor.project) {
        editor.document.insertString(insertionPoint, "\n$finalSql")
    }

    val bodyStart = insertionPoint + 1 + (if (needsLeadingSemicolon) 2 else 0)
    val end = if (caretOffsetInSql != null) {
        (bodyStart + caretOffsetInSql).coerceIn(bodyStart, bodyStart + body.length)
    } else {
        insertionPoint + finalSql.length + 1
    }
    editor.caretModel.moveToOffset(end)
    editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
}

/**
 * Rechnet einen absoluten Cursor-Offset in der ORIGINAL-Datei (dort, wo der Nutzer beim Aufruf der
 * Action tatsaechlich stand) auf die passende Position im zusammengebauten SQL um - `null`, wenn der
 * Original-Offset ausserhalb des Bereichs liegt, aus dem diese Zeile ihre Hauptanweisung genommen hat
 * (z.B. eine andere, weiter oben liegende CTE) oder keine woertliche Zuordnung existiert (`SELECT *
 * FROM name`-Wrapper fuer rekursive CTE-Ziele).
 */
internal fun DependencyGraphSqlBuilder.SourceCaretMapping.resolveCaretOffset(originalCaretOffset: Int): Int? {
    val range = sourceElement.textRange
    if (!range.contains(originalCaretOffset)) return null
    return offsetInSql + (originalCaretOffset - range.startOffset)
}

/** Footer-Zeile "E to edit · C to copy" unter der Zeilenliste beider Popups. */
internal fun buildShortcutFooter(): JPanel {
    val footer = JPanel(BorderLayout())
    footer.isOpaque = false
    footer.border = JBUI.Borders.empty(4, 8, 2, 8)
    val label = JLabel("E to edit · C to copy")
    label.foreground = UIManager.getColor("Label.disabledForeground") ?: UIManager.getColor("Label.foreground")
    label.font = label.font.deriveFont(Font.PLAIN, (label.font.size2D - 1f).coerceAtLeast(10f))
    footer.add(label, BorderLayout.WEST)
    return footer
}
