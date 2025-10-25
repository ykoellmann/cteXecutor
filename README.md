# cteXecutor

Execute and manage Common Table Expressions (CTEs) with ease in DataGrip and IntelliJ-based IDEs.

### Reviews are much appreciated! ⭐

---

## Features

### 🎯 CTE Management
- **Detects all CTEs** in your SQL `WITH` clause automatically
- **Interactive popup chooser** to select which CTE(s) you want to execute or copy
- **Visual highlighting** of selected CTE(s) inside the editor for better clarity
- **Smart execution** - executes the chosen SQL query in the database console
- **Copy to clipboard** - quickly copy any CTE query for use elsewhere

### 🚀 NEW: Execute from Here (v2.0.0)
- **Execute from anywhere** - not just CTEs! Works with any subselect in your SQL
- **Automatic dependency resolution** - automatically includes all required CTEs
- **Smart detection** - finds dependencies in CTEs, subqueries, and final SELECT statements
- **Interactive selection** - choose exactly where to start execution from

### ⚡ Developer Productivity
- **Keyboard shortcuts** for lightning-fast workflow
- **Minimal UI** - stays out of your way
- **Automatic cleanup** - inserted SQL is removed after execution
- **Lightweight** - no performance impact

---

## Usage

### Basic CTE Execution

1. Open a SQL file containing CTEs (WITH clauses)
2. Place the caret anywhere inside or near a CTE
3. Invoke **Run CT-Query** (shortcut: `Ctrl+#` then `Space`)
4. Select the desired CTE from the popup
5. The plugin highlights the relevant SQL and executes it in the console
6. The inserted SQL is automatically cleaned up after execution

### Execute from Here (NEW!)

1. Place your caret anywhere in your SQL - inside a CTE, subquery, or main SELECT
2. Invoke **Execute from Here** (shortcut: `Ctrl+#` then `Enter`)
3. The plugin detects all dependencies and shows execution options
4. Select where to execute from
5. All required CTEs are automatically included

### Copy SQL

1. Place the caret in a CTE
2. Invoke **Copy CT-Query SQL** (shortcut: `Ctrl+#` then `C`)
3. The SQL is copied to your clipboard

### Edit and Run

1. Place the caret in a CTE
2. Invoke **Edit and Run SQL** (shortcut: `Ctrl+#` then `W`)
3. Modify the query (e.g., add WHERE clauses)
4. The modified SQL is ready to execute

---

## Examples

### Example 1: Basic CTE Execution
```sql
WITH 
  sales AS (SELECT * FROM orders WHERE year = 2024),
  customers AS (SELECT * FROM users WHERE active = true)
SELECT * FROM sales 
JOIN customers ON sales.user_id = customers.id;
```
- Place caret in `sales` CTE
- Press `Ctrl+#` → `Space`
- Select "sales" from popup
- Executes: `WITH sales AS (...) SELECT * FROM sales`

### Example 2: Execute from Here (NEW!)
```sql
WITH 
  sales AS (SELECT * FROM orders WHERE year = 2024),
  revenue AS (
    SELECT 
      product_id,
      SUM(amount) as total
    FROM sales  -- Place caret here
    GROUP BY product_id
  )
SELECT * FROM revenue WHERE total > 1000;
```
- Place caret in the subquery inside `revenue` CTE
- Press `Ctrl+#` → `Enter`
- Plugin detects that `sales` CTE is needed
- Executes the subquery with all dependencies automatically included!

### Example 3: Complex Dependencies
```sql
WITH 
  base AS (SELECT * FROM data),
  filtered AS (SELECT * FROM base WHERE active = true),
  aggregated AS (SELECT category, COUNT(*) FROM filtered GROUP BY category)
SELECT * FROM aggregated ORDER BY count DESC;
```
- Place caret anywhere in `aggregated`
- Press `Ctrl+#` → `Enter`
- Automatically includes `base` and `filtered` CTEs (all dependencies)
- Clean, dependency-aware execution!

---

## Installation

1. Open IntelliJ IDEA / DataGrip
2. Go to **Settings** → **Plugins** → **Marketplace**
3. Search for **"cteXecutor"**
4. Click **Install**
5. Restart your IDE

---

## Contributing

Found a bug or have a feature request? Please open an issue on GitHub!

---

## Support

If you find this plugin helpful, please:
- ⭐ **Star the repository**
- 📝 **Leave a review** on the JetBrains Marketplace
- 🐛 **Report bugs** to help improve the plugin
- 💡 **Share your ideas** for new features

---

**Made with ❤️ for SQL developers who work with CTEs**