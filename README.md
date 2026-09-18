# cteXecutor

cteXecutor is a plugin for DataGrip and other IntelliJ-based IDEs that lets you execute a single
Common Table Expression (CTE), subquery, or UNION branch directly from the editor, without manually
assembling the surrounding WITH clause.

## Features

### Execute from Here

Place the caret anywhere in a SQL file, inside a CTE, a subquery, a UNION branch, or the main
query, and invoke Execute from Here (`Ctrl+#` then `Enter`). The plugin resolves which CTEs the
current statement depends on, in the correct order, and shows them as a single list ending with the
current statement. If code is already selected when you invoke it, that selection runs directly
instead, the same as the IDE's standard execute shortcut.

From that list:

- Enter executes the selected statement, together with its dependencies, in the database console
- E inserts the selected statement into the editor
- C copies the selected statement to the clipboard

### Navigate Dependency Graph

Invoke Navigate Dependency Graph (`Ctrl+#` then `D`) to see what the statement at the caret depends
on and what depends on it. Selecting a dependency or consumer moves the caret there and refreshes
the list around the new position. The same Enter, E, and C shortcuts apply to whichever row is
focused.

## Usage

1. Open a SQL file containing one or more CTEs
2. Place the caret inside the CTE, subquery, or query you want to work with
3. Invoke Execute from Here or Navigate Dependency Graph
4. Select a row to preview it, or use the shortcuts above to execute, edit, or copy it

The inserted SQL used for execution is removed from the document again afterward; it never becomes
part of the file or the undo history.

## Example

```sql
WITH
  sales AS (SELECT * FROM orders WHERE year = 2024),
  revenue AS (
    SELECT product_id, SUM(amount) AS total
    FROM sales
    GROUP BY product_id
  )
SELECT * FROM revenue WHERE total > 1000;
```

Placing the caret inside `revenue` and invoking Execute from Here shows two rows: `sales` and the
current statement. Executing the current statement runs it together with the `sales` CTE it needs,
without touching `revenue`'s own WITH clause manually.

## Installation

1. Open IntelliJ IDEA or DataGrip
2. Go to Settings, then Plugins, then Marketplace
3. Search for cteXecutor
4. Install and restart the IDE

## Contributing

Bug reports and feature requests are welcome as GitHub issues.

## Support

If cteXecutor is useful to you, a review on the
[JetBrains Marketplace](https://plugins.jetbrains.com/plugin/27835-ctexecutor/reviews) helps other
people find it.
