# Changelog

## [Unreleased]

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
