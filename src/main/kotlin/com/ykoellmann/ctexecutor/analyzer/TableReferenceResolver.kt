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
        // WICHTIG (Spike-Ergebnis): liefert nur Namen zurueck. Die Scope-Zuordnung bei mehreren
        // gleichnamigen CTEs passiert NICHT hier - PSI liefert dort mehrdeutige Kandidaten, kein
        // Autopick -, sondern in DependencyGraphBuilder.discoverScope()/computeDependencyIds().
        TODO()
    }
}

// NameMatchingResolver bleibt als Fallback/Vergleichsimplementierung im Code, falls sich der
// PSI-Ansatz gegen komplexere Dialekte (insbesondere DB2-for-i) als unzuverlaessig erweist -
// der Spike lief nur gegen generisches/ANSI-SQL.
