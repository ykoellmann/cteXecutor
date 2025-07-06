# cteXecutor
IntelliJ plugin to easily execute Common Table Expressions (CTEs) in SQL files. Select CTEs from a popup, highlight their code, and run or copy the SQL effortlessly.

![Plugin Logo](src/main/resources/META-INF/pluginIcon.svg)
---

## Overview

**cteXecutor** is an IntelliJ IDEA plugin designed to simplify working with SQL Common Table Expressions (CTEs).  
It helps you easily select, highlight, and execute specific CTEs or a chain of CTEs directly from your SQL editor.

---

## Features

- Detects all CTEs in your SQL `WITH` clause.
- Popup chooser to select which CTE(s) you want to execute or copy.
- Highlights the selected CTE(s) inside the editor for better clarity.
- Executes the chosen SQL query in the database console or copys the sql query text.
- Keyboard shortcut support for quick execution.
- Lightweight, minimal UI with focus on developer productivity.

---

## Installation

**JetBrains Marketplace**: Search for "cteXecutor" in the Plugins Marketplace inside IntelliJ IDEA.

## Usage

1. Open a SQL file containing CTEs (WITH clauses).
2. Place the caret anywhere inside or near the CTE.
3. Invoke the action **Run CT-Query** (default shortcut: `Ctrl+R` then `Enter`).
4. Select the desired CTE from the popup.
5. The plugin highlights the relevant SQL and executes the query in the console.
6. The inserted SQL is automatically cleaned up after execution.

---

## Keyboard Shortcut

- Run: `Ctrl+R`, then `Enter` (configurable via IDE keymap settings).
- Copy: `Ctrl+R`, then `C` (configurable via IDE keymap settings).

---


## Contributing
Contributions and suggestions are welcome! Feel free to open issues or pull requests.

## 📄 License

This project is licensed under the **Creative Commons Attribution-NonCommercial 4.0 International (CC BY-NC 4.0)** license.

You are free to use, modify, and share this plugin for personal, educational, and open-source purposes.

**Commercial use is not allowed** without explicit permission.

See the [LICENSE](LICENSE) file for full terms.  
Learn more: [https://creativecommons.org/licenses/by-nc/4.0](https://creativecommons.org/licenses/by-nc/4.0)

## Author
Yannik Köllmann

## Contact
For questions or feedback, open an issue or contact me via GitHub.

Happy querying! 🚀