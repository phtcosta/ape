---
name: sdd-qa-lint
description: "Run language-appropriate linter and report structured issues. Use WHEN checking code quality, before code review, or as part of verification. WHEN NOT: use sdd-qa-lint-fix to auto-fix issues."
context: fork
argument-hint: "[path]"
---

# Lint Code: $ARGUMENTS

Run the appropriate linter for the detected project language on the specified path and produce a structured report of issues found.

This skill is read-only. It analyzes code but does not modify any files. For auto-fixing lint issues, use `sdd-qa-lint-fix` instead.

**Invariants:**

- **INV-LINT-01:** This skill SHALL NOT create, modify, or delete any file. All operations are read-only.
- **INV-LINT-02:** The skill SHALL report the exact linter command executed so the developer can reproduce the result manually.
- **INV-LINT-03:** The skill SHALL distinguish between "zero issues found" (status: `"pass"`) and "linter failed to execute" (status: `"error"`).

---

## Input

`$ARGUMENTS` contains the path to analyze. This can be a single file, a directory, or empty (defaults to project root `.`).

Examples:
- `src/` -- lint all source files in a directory
- `src/parser.py` -- lint a single file
- `(empty)` -- lint the project root

---

## Step 1: Detect Language

Invoke `sdd-detection` using the Skill tool to determine the project language and linter.

```
Use the Skill tool:
  skill: "sdd-detection"
  args: "" (or the project root if $ARGUMENTS implies a different project)
```

Capture the detection result. The relevant fields are:
- `language` -- the detected language identifier (e.g., `"python"`, `"typescript"`, `"java"`, `"go"`)
- `linter` -- the detected linter name from the plugin (e.g., `"ruff"`, `"eslint"`, `"checkstyle"`, `"golangci-lint"`)

WHEN sdd-detection returns a `NoLanguageDetected` error
THEN return a `NoLanguageDetected` error (see Error Handling below) and STOP.

---

## Step 2: Map Language to Linter

Use the linter from the sdd-detection result when available. When sdd-detection returns `linter: "unknown"`, use the default linter from this mapping table:

| Language | Default Linter | Check Command | Config Files |
|----------|---------------|---------------|--------------|
| Python | ruff | `ruff check <path>` | `ruff.toml`, `pyproject.toml` (`[tool.ruff]` section) |
| TypeScript | eslint | `eslint <path>` | `.eslintrc.*`, `eslint.config.*` |
| JavaScript | eslint | `eslint <path>` | `.eslintrc.*`, `eslint.config.*` |
| Java | checkstyle | `checkstyle -c <config> <path>` | `checkstyle.xml` |
| Go | golangci-lint | `golangci-lint run <path>` | `.golangci.yml`, `.golangci.yaml` |

If the detected language is not in this table and sdd-detection returned `linter: "unknown"`, report a `LinterNotConfigured` error:
```
Error: LinterNotConfigured
Message: "No default linter mapping for language '<language>'. Configure a linter in the project's plugin config."
```

---

## Step 3: Check for Config Files

Before running the linter, check whether the project has a linter configuration file. Use the Read tool or Bash to check for the config files listed in the mapping table above.

WHEN a config file exists
THEN use it in the linter command (e.g., `checkstyle -c checkstyle.xml <path>`).

WHEN no config file exists
THEN run the linter with its default settings.

For each language, the config file search:

**Python (ruff):**
1. Check for `ruff.toml` in project root
2. Check for `[tool.ruff]` section in `pyproject.toml`
3. If neither exists, use defaults

**TypeScript/JavaScript (eslint):**
1. Check for `.eslintrc.js`, `.eslintrc.cjs`, `.eslintrc.json`, `.eslintrc.yml` in project root
2. Check for `eslint.config.js`, `eslint.config.mjs`, `eslint.config.cjs` in project root
3. If neither exists, use defaults

**Java (checkstyle):**
1. Check for `checkstyle.xml` in project root
2. Check for `config/checkstyle/checkstyle.xml`
3. If neither exists, use `/google_checks.xml` (checkstyle built-in)

**Go (golangci-lint):**
1. Check for `.golangci.yml` or `.golangci.yaml` in project root
2. If neither exists, use defaults

---

## Step 4: Run Linter

Run the linter command using the Bash tool. Capture both stdout and stderr.

Construct the command based on the detected language:

**Python:**
```bash
ruff check <path>
```

**TypeScript/JavaScript:**
```bash
eslint <path>
```

**Java:**
```bash
checkstyle -c <config_path> <path>
```
Where `<config_path>` is the detected config file or `/google_checks.xml` if none found.

**Go:**
```bash
golangci-lint run <path>
```

Record the exact command executed — this is required by INV-LINT-02.

### Interpreting the Result

WHEN the linter exits with code 0 and produces no issue output
THEN set `status: "pass"` and `issueCount: 0`

WHEN the linter exits with a non-zero code and produces structured issue output
THEN set `status: "warn"` and parse the issues

WHEN the linter command fails to execute (command not found, crash, permission error)
THEN set `status: "error"` and check if the linter is installed (see Error Handling)

The distinction between "no issues" and "linter failure" is critical (INV-LINT-03):
- Exit code 0 with no issues = `status: "pass"`
- Exit code 1 with parseable issue output = `status: "warn"`
- Command not found or unparseable error = `status: "error"`

---

## Step 5: Parse Output

Parse the linter output into structured issues. Each linter has a different output format.

**Python (ruff):** Default output format is `<file>:<line>:<column>: <rule> <message>`.

**TypeScript/JavaScript (eslint):** Default output includes file path, line, column, rule, severity, and message. Use `--format json` if available for easier parsing.

**Java (checkstyle):** Default output format is `[<severity>] <file>:<line>:<column>: <message> [<rule>]`.

**Go (golangci-lint):** Default output format is `<file>:<line>:<column>: <message> (<linter>)`.

For each issue parsed, extract:

| Field | Description |
|-------|-------------|
| `file` | Relative path to the file |
| `line` | Line number |
| `column` | Column number (0 if unavailable) |
| `rule` | Rule identifier (e.g., `E501`, `no-unused-vars`) |
| `severity` | `"error"` or `"warning"` based on the linter's classification |
| `message` | Human-readable description of the issue |

---

## Error Handling

### LinterNotInstalled

WHEN the linter command is not found (e.g., `command not found` in stderr)
THEN return:

```
Error: LinterNotInstalled
Linter: "<linter_name>"
Language: "<language>"
Message: "The linter '<linter_name>' is not installed or not in PATH."
```

Include an installation suggestion based on the linter:

| Linter | Installation Command |
|--------|---------------------|
| ruff | `pip install ruff` or `uv pip install ruff` |
| eslint | `npm install -g eslint` or `npm install --save-dev eslint` |
| checkstyle | Download from https://checkstyle.org or use build tool plugin |
| golangci-lint | `go install github.com/golangci-lint/golangci-lint/cmd/golangci-lint@latest` |

Set `status: "error"` in the output.

### NoLanguageDetected

WHEN sdd-detection returns a `NoLanguageDetected` error
THEN return:

```
Error: NoLanguageDetected
Message: "Could not detect the project language. sdd-detection found no recognized language."
Suggestion: "Configure the project language in `.sdd/sdd-config.yaml` or verify the project root path."
```

Set `status: "error"` in the output.

---

## Output Format

Return the following structured report:

```markdown
# Lint Report

**Linter**: <linter_name>
**Language**: <language>
**Path**: <analyzed_path>
**Command**: `<exact_command_executed>`
**Status**: <pass|warn|error>

## Summary

| Metric | Value |
|--------|-------|
| Total issues | <issueCount> |
| Errors | <error_count> |
| Warnings | <warning_count> |

## Issues

| # | File | Line | Col | Rule | Severity | Message |
|---|------|------|-----|------|----------|---------|
| 1 | path/to/file.ext | 10 | 5 | E501 | error | line too long |
| 2 | path/to/file.ext | 20 | 1 | W291 | warning | trailing whitespace |
| ... | ... | ... | ... | ... | ... | ... |
```

### Status Values

| Status | Meaning |
|--------|---------|
| `pass` | Linter ran and found zero issues |
| `warn` | Linter ran and found one or more issues |
| `error` | Linter failed to execute (not installed, config error, crash) |

### When Status is "pass"

Still include the full header with linter name, language, path, and the exact command executed (INV-LINT-02). The Issues table is omitted or shown empty.

```markdown
# Lint Report

**Linter**: ruff
**Language**: python
**Path**: src/
**Command**: `ruff check src/`
**Status**: pass

No issues found.
```

### When Status is "error"

Include the error details instead of the Issues table:

```markdown
# Lint Report

**Linter**: <linter_name or "unknown">
**Language**: <language or "unknown">
**Path**: <analyzed_path>
**Command**: `<attempted_command or "N/A">`
**Status**: error

## Error

<error_type>: <error_message>
<suggestion if applicable>
```

---

## Constraints

- This skill is read-only. It SHALL NOT create, write, modify, or delete any file (INV-LINT-01).
- This skill invokes `sdd-detection` via the Skill tool. It SHALL NOT use the Task tool.
- Do not install linters or run package managers. If a linter is missing, report the absence.
- Do not prompt the user for input. Process `$ARGUMENTS` and return the report.
- When the Issues table would exceed 100 rows, truncate to the first 100 issues sorted by severity (errors first, then warnings) and note the truncation in the summary.
