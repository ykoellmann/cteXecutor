package com.ykoellmann.ctexecutor

object PluginInfo {
    const val VERSION = "3.0.0"

    /**
     * Version for which the notification should be shown.
     * Only update this when you want users to see the notification again.
     * For bugfix releases, you can keep this the same to avoid re-showing notifications.
     */
    const val NOTIFICATION_VERSION = "3.0.0"

    const val NOTIFICATION_CONTENT = """
        <b>cteXecutor Update $NOTIFICATION_VERSION</b><br><br>
        
        <b>Execute from Here Improvements:</b><br>
        • Execute from Here now correctly detects cursor position inside UNION branches<br>
        • Inline subselects, such as derived tables in JOIN clauses, can now be executed as independent targets<br>
        • CTE dependency analysis is more accurate, including UNION branches and case-insensitive CTE references<br><br>
        
        <b>Fixes:</b><br>
        • Cursor positions on closing brackets or CTE-boundary whitespace now resolve to the correct enclosing CTE<br>
        • CTEs with column lists are detected more reliably<br>
        • Final CTE execution now uses the CTE body directly instead of generating SELECT * FROM cte_name<br><br>
        
        <b>⚠️ Change:</b><br>
        • The separate Run CT-Query action has been removed in favor of the unified Execute from Here action
    """

    const val CHANGE_NOTES = """
        <h3>Version 3.0.0</h3>
        <ul>
            <li><strong>NEW:</strong> Execute from Here now correctly detects cursor position inside UNION branches and executes only the selected branch with its required dependencies</li>
            <li><strong>NEW:</strong> Execute from Here now detects cursor inside inline subselects, such as derived tables in JOIN clauses, and offers them as independent execution targets</li>
            <li><strong>IMPROVED:</strong> Execute from Here is now the primary CTE execution action and also handles the previous CT-query execution flow</li>
            <li><strong>FIXED:</strong> Cursor positions on closing brackets or whitespace at CTE boundaries now resolve to the enclosing CTE instead of falling through to the outer query</li>
            <li><strong>FIXED:</strong> CTE name extraction now handles column-list CTEs more reliably</li>
            <li><strong>FIXED:</strong> Dependency analysis for UNION CTEs now covers all branches instead of only the first one</li>
            <li><strong>FIXED:</strong> Execute from Here final option now extracts the CTE body as the main query instead of generating SELECT * FROM cte_name</li>
            <li><strong>FIXED:</strong> CTE name matching is now case-insensitive</li>
            <li><strong>CHANGED:</strong> Removed the separate Run CT-Query action in favor of the unified Execute from Here action</li>
            <li><strong>UPDATED:</strong> Upgraded Kotlin, IntelliJ Platform Gradle plugin, target IntelliJ database platform, and Gradle wrapper versions</li>
        </ul>
        <h3>Version 2.0.1</h3>
        <ul>
            <li>Fix problems of new ExecuteFromHere, where execution of dependencies did not properly work</li>
            <li>Fix problem of execution being part of the do and undo process of IDE, which lead to problems</li>
            <li>Upgrade necessary version</li>
        </ul>
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