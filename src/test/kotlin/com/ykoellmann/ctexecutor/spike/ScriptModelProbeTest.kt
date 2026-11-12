package com.ykoellmann.ctexecutor.spike

import com.intellij.psi.PsiFileFactory
import com.intellij.sql.psi.SqlPsiFacade
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.database.script.ScriptModel

/** Probe: can a ScriptModel be built from an in-memory PsiFile that is NOT the editor's document? */
class ScriptModelProbeTest : BasePlatformTestCase() {
    fun testModelFromDetachedFile() {
        val real = myFixture.configureByText("real.sql", "SELECT 'real';")
        val sql = "WITH a AS (SELECT 1 AS x) SELECT * FROM a;"
        val detached = PsiFileFactory.getInstance(project).createFileFromText("tmp.sql", real.language, sql, false, true)
        println("PROBE language=${real.language} detachedVirtualFile=${detached.virtualFile} original=${detached.originalFile.virtualFile}")
        val model: ScriptModel<*> = SqlPsiFacade.getInstance(project).createScriptModel(detached)
        val stmts = model.statements().toList()
        println("PROBE statements=${stmts.size} model.virtualFile=${model.virtualFile} range=${model.textRange}")
        stmts.forEach { println("PROBE query=[${it.query()}] range=${it.range()} offset=${it.rangeOffset()}") }
        assertEquals(1, stmts.size)
        assertEquals(sql.trimEnd(';'), stmts[0].query().trim().trimEnd(';'))
    }
}
