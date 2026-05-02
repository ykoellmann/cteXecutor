# Changelog

## [Unreleased]
### feat
- Execute from Here now correctly detects cursor position inside UNION branches and executes only the selected branch with its required dependencies
- Execute from Here now detects cursor inside inline subselects (e.g. derived tables in JOIN) and offers them as independent execution targets
### fix
- Cursor positioned on a closing bracket or whitespace at a CTE boundary now correctly resolves to the enclosing CTE instead of falling through to the outer query
- CTE name extraction now uses the first SQL identifier child instead of the first child node, fixing edge cases with column-list CTEs
- Dependency analysis for UNION CTEs now covers all branches instead of only the first one
- Execute from Here final option now extracts the CTE body as the main query instead of generating SELECT * FROM cte_name, matching the behavior of the intermediate options
- CTE name matching is now case-insensitive

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
