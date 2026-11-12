package com.ykoellmann.ctexecutor.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.DocumentUtil

/** Temporarily inserted SQL must never reach disk, otherwise it shows up in Local History. */
class SqlExecutorTest : BasePlatformTestCase() {

    private val original = "SELECT 1;"

    private fun insertTemporarily(document: com.intellij.openapi.editor.Document) =
        DocumentUtil.writeInRunUndoTransparentAction { document.insertString(document.textLength, "\nSELECT 2;") }

    private fun diskText(file: com.intellij.openapi.vfs.VirtualFile) = String(file.contentsToByteArray())

    fun testControlAutosaveWritesInsertedSqlToDisk() {
        val psi = myFixture.configureByText("c.sql", original)
        val document = FileDocumentManager.getInstance().getDocument(psi.virtualFile)!!
        insertTemporarily(document)
        FileDocumentManager.getInstance().saveAllDocuments()
        assertTrue(diskText(psi.virtualFile).contains("SELECT 2"))
    }

    fun testVetoedDocumentIsNotSavedWhileSqlIsInserted() {
        val psi = myFixture.configureByText("v.sql", original)
        val document = FileDocumentManager.getInstance().getDocument(psi.virtualFile)!!
        SqlExecutor.unsavable += document
        insertTemporarily(document)
        FileDocumentManager.getInstance().saveAllDocuments()
        FileDocumentManager.getInstance().saveDocument(document)
        assertEquals(original, diskText(psi.virtualFile))
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
        SqlExecutor.unsavable -= document
    }

    fun testExecuteSeamlesslyRestoresDocumentAndReleasesVeto() {
        myFixture.configureByText("e.sql", original)
        val document = myFixture.editor.document
        var savedDuringWindow: String? = null
        // Simulates the autosave that happens while the SQL is inserted (before the delete step runs).
        ApplicationManager.getApplication().invokeLater {
            FileDocumentManager.getInstance().saveAllDocuments()
            savedDuringWindow = diskText(myFixture.file.virtualFile)
        }
        SqlExecutor.executeSqlSeamlessly(myFixture.editor, "SELECT 2;")
        // The delete step is a nested invokeLater, so wait for it instead of draining the queue once.
        PlatformTestUtil.waitWithEventsDispatching("SQL was not removed again", { document.text == original }, 10)
        assertEquals(original, document.text)
        assertTrue(SqlExecutor.unsavable.isEmpty())
        assertEquals(original, savedDuringWindow)
    }
}
