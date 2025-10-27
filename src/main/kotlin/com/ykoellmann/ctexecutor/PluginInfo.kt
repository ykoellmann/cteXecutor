package com.ykoellmann.ctexecutor

object PluginInfo {
    const val VERSION = "2.0.1"

    const val NOTIFICATION_CONTENT = """
        <b>cteXecutor Update $VERSION</b><br><br>
        
        <b>🎉 New Feature:</b><br>
        • <b>Execute From Here:</b> Execute not only CTEs but also any subselects in CTEs or in the final SQL that have other CTEs as dependencies<br>
        • Execute queries starting from any subselect with all required dependencies included<br><br>
        
        <b>⚡ Shortcut Changes:</b><br>
        • <b>Run CT-Query:</b> Ctrl+# → <s>Enter</s> <b>Space</b> (changed)<br>
        • <b>Execute From Here:</b> Ctrl+# → <b>Enter</b> (new - took over the previous Run shortcut)<br>
        • <b>Copy CT-Query:</b> Ctrl+# → C (unchanged)<br>
        • <b>Edit and Run SQL:</b> Ctrl+# → W (unchanged)<br><br>
        
        <b>🔧 Improvements:</b><br>
        • Enhanced subselect detection and dependency resolution<br>
        • More flexible query execution options
    """

    const val CHANGE_NOTES = """
        <h3>Version 2.0.0</h3>
        <ul>
            <li><strong>NEW:</strong> "Execute from Here" action with smart dependency resolution</li>
            <li>Execute SQL from anywhere - inside CTEs, subqueries, or main queries</li>
            <li>Automatically detects and includes all required CTEs and dependencies</li>
            <li>Interactive popup showing execution options and affected elements</li>
            <li>Keyboard shortcut: Ctrl+# then Enter</li>
            <li><strong>CHANGED:</strong> "Run CT-Query" shortcut changed from Ctrl+# Enter to Ctrl+# Space</li>
        </ul>
        <h3>Version 1.1.3</h3>
        <ul>
            <li>Added "Copy and Edit SQL" action, allowing you to append WHERE clauses or other modifications at the end of CTE queries</li>
            <li>Refactored code structure to reduce duplication</li>
            <li>Renamed actions for improved clarity and usability</li>
        </ul>
    """

    const val DESCRIPTION = """
        Execute and manage Common Table Expressions (CTEs) easily in DataGrip and IntelliJ-based IDEs.<br>
        Highlights CTEs and allows selective execution of composed queries.<br>
        <strong>NEW:</strong> Execute from Here - run SQL from anywhere with automatic dependency resolution!<br>
        <em>Efficient, intuitive, and developer-friendly.</em>
    """
}