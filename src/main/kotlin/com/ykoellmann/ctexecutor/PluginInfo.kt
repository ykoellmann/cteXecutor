package com.ykoellmann.ctexecutor

object PluginInfo {
    const val VERSION = "4.0.0"

    const val MARKETPLACE_REVIEWS_URL = "https://plugins.jetbrains.com/plugin/27835-ctexecutor/reviews"

    /**
     * Version for which the notification should be shown.
     * Only update this when you want users to see the notification again.
     * For bugfix releases, you can keep this the same to avoid re-showing notifications.
     */
    const val NOTIFICATION_VERSION = "4.0.0"

    const val NOTIFICATION_CONTENT = """
        <b>cteXecutor Update $NOTIFICATION_VERSION</b><br><br>

        <b>New: Navigate Dependency Graph</b><br>
        A second action that shows what the statement at the caret depends on and what depends on it,
        with the same execute, edit, and copy shortcuts as Execute from Here.<br><br>

        <b>Execute from Here rewritten:</b><br>
        Dependencies and the current statement are now shown as one list you can move through with
        the arrow keys or number keys, instead of a flat popup.<br><br>

        <b>Change:</b><br>
        The separate Copy CT-Query SQL and Edit and Run SQL actions have been removed. Their
        functionality is now available as E (insert into editor) and C (copy to clipboard) inside
        both popups. Execute from Here falls back to the standard execute shortcut if code is
        already selected. Navigate Dependency Graph moved to Ctrl+# then D.
    """

    const val CHANGE_NOTES = """
        <h3>Version 4.0.0</h3>
        <ul>
            <li><strong>NEW:</strong> Navigate Dependency Graph action, showing dependencies and consumers of the statement at the caret</li>
            <li><strong>CHANGED:</strong> Execute from Here now shows the current statement and its needed CTEs as a single navigable list</li>
            <li><strong>CHANGED:</strong> Copy CT-Query SQL and Edit and Run SQL removed as separate actions; their functionality is now available as E and C shortcuts inside both popups</li>
            <li><strong>CHANGED:</strong> Navigate Dependency Graph shortcut moved to Ctrl+# then D</li>
            <li><strong>CHANGED:</strong> Execute from Here no longer has a Ctrl+# then Space alternate shortcut; Ctrl+# then Enter remains</li>
            <li><strong>IMPROVED:</strong> Execute from Here now falls back to the standard execute shortcut if code is already selected, instead of showing the dependency popup</li>
            <li><strong>IMPROVED:</strong> Dependency and correlation analysis now uses PSI reference resolving instead of name matching, covering nested WITH clauses, WITH RECURSIVE, and uncorrelated subqueries</li>
            <li><strong>FIXED:</strong> Editing a statement now places the cursor at its original position within the inserted SQL where possible, instead of always at the end</li>
            <li><strong>FIXED:</strong> Dependencies in Navigate Dependency Graph are now ordered by their position in the file, closest to the current statement shown nearest to it</li>
            <li><strong>FIXED:</strong> Navigating the dependency graph from a keyboard shortcut no longer risks a threading assertion failure</li>
        </ul>
        <h3>Version 3.0.2</h3>
        <ul>
            <li><strong>UPDATED:</strong>Upgrade necessary version</li>
        </ul>
        <h3>Version 3.0.1</h3>
        <ul>
            <li><strong>FIXED:</strong> Replace deprecated ActionUtil.performActionDumbAwareWithCallbacks with ActionUtil.performAction</li>
        </ul>
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
        Execute a single CTE, subquery, or UNION branch directly from the editor in DataGrip and
        IntelliJ-based IDEs, without manually assembling the surrounding WITH clause.<br><br>
        Execute from Here resolves the CTEs a statement depends on and lets you run, edit, or copy
        any step in that chain. Navigate Dependency Graph shows what a statement depends on and what
        depends on it.
    """
}