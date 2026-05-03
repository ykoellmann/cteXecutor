# Changelog

## [Unreleased]

## [3.0.1] - 2026-05-03
### fix
- Replace deprecated `ActionUtil.performActionDumbAwareWithCallbacks` with `ActionUtil.performAction`

## [3.0.0] - 2026-05-02
### feat
- Execute from Here now correctly detects cursor position inside UNION branches and executes only the selected branch with its required dependencies
- Execute from Here now detects cursor inside inline subselects, such as derived tables in JOIN clauses, and offers them as independent execution targets
- Execute from Here is now the primary CTE execution action and also handles the previous CT-query execution flow
- Execute from Here now supports both Ctrl+# then Enter and Ctrl+# then Space shortcuts

### fix
- Cursor positioned on a closing bracket or whitespace at a CTE boundary now correctly resolves to the enclosing CTE instead of falling through to the outer query
- CTE name extraction now uses the first SQL identifier child instead of the first child node, fixing edge cases with column-list CTEs
- Dependency analysis for UNION CTEs now covers all branches instead of only the first one
- Execute from Here final option now extracts the CTE body as the main query instead of generating SELECT * FROM cte_name, matching the behavior of the intermediate options
- CTE name matching is now case-insensitive

### break
- Removed the separate Run CT-Query action in favor of the unified Execute from Here action

### chore
- Upgrade Kotlin Gradle plugin from 2.1.21 to 2.2.20
- Upgrade IntelliJ Platform Gradle plugin from 2.3.0 to 2.10.5
- Upgrade target IntelliJ database platform from DB 2025.1.3 to DB 2026.1.2
- Upgrade Gradle wrapper from 8.12.1 to 8.14.2
- Configure Kotlin JVM toolchain using `jvmToolchain(21)`

## [2.0.1] - 2026-05-02
### fix
- Fix problems with ExecuteFromHere where execution of dependencies did not properly work
- Fix problem of execution being part of the undo/redo process of IDE, causing issues
- Upgrade necessary version

## [2.0.0] - 2025-01-01
### feat
- "Execute from Here" action with smart dependency resolution
- Execute SQL from anywhere — inside CTEs, subqueries, or main queries
- Automatically detects and includes all required CTEs and dependencies
- Interactive popup showing execution options and affected elements
- Keyboard shortcut: Ctrl+# then Enter
### break
- "Run CT-Query" shortcut changed from Ctrl+# Enter to Ctrl+# Space

## [1.1.3] - 2024-01-01
### feat
- Added "Copy and Edit SQL" action for appending WHERE clauses or other modifications
- Renamed actions for improved clarity and usability
### fix
- Refactored code structure to reduce duplication
