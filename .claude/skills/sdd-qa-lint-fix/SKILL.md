---
name: sdd-qa-lint-fix
description: "Auto-fix lint issues using the linter's built-in fix mode. Use WHEN lint issues need automated correction. WHEN NOT: use sdd-qa-lint for analysis only."
context: fork
argument-hint: "[path]"
---

# Auto-Fix Lint Issues: $ARGUMENTS

Apply safe auto-fixes for lint issues using the linter's built-in fix mode. The skill identifies issues first (via sdd-qa-lint), applies the linter's safe auto-corrections, then re-analyzes to report what was fixed and what remains.

This skill is a Tier 3 support component. It runs as a forked subagent and modifies source files (only through linter auto-fix commands). It is called by orchestrator skills (sdd-feature, sdd-refactor, sdd-cleanup) or directly by users.

**Invariants:**

- **INV-LFIX-01:** The skill SHALL invoke sdd-qa-lint before applying fixes so issues are identified first.
- **INV-LFIX-02:** The skill SHALL only apply fixes that the linter classifies as safe auto-corrections. No AI-generated or speculative fixes. No manual code edits.
- **INV-LFIX-03:** The skill SHALL re-run sdd-qa-lint after fixing and report remaining issues.
- **INV-LFIX-04:** The skill SHALL report the exact fix command executed for reproducibility.

**Tool usage:** This skill invokes `sdd-qa-lint` via the **Skill tool** (two calls: pre-fix and post-fix). It runs linter fix commands via **Bash**. It does NOT use the Task tool.

---

## Input

`$ARGUMENTS` contains the target path to fix. This can be a single file, a directory, or a project root.

Examples:
- `src/parser.py` -- fix a single file
- `src/` -- fix all source files in a directory
- `.` -- fix the entire project from the current directory
- `(empty)` -- defaults to the current working directory

---

## Phase 1: Pre-Fix Analysis (INV-LFIX-01)

Invoke sdd-qa-lint to identify all lint issues before applying any fixes.

```
Skill tool: skill="sdd-qa-lint", args="$ARGUMENTS"
```

Capture the full output from sdd-qa-lint. Extract:
- The detected **language**
- The **linter** name (e.g., ruff, eslint, checkstyle, golangci-lint)
- The **issue count** and list of issues
- The **status** (pass, warn, error)

### Pre-fix status: "pass"

WHEN sdd-qa-lint returns status "pass" (zero issues)
THEN report that no lint issues were found and no fixes are needed
AND set output status to "fixed" with fixedCount 0 and remainingCount 0
AND STOP

### Pre-fix status: "error"

WHEN sdd-qa-lint returns status "error" (linter failed to run)
THEN propagate the error to the output
AND set output status to "error"
AND STOP

### Pre-fix status: "warn"

WHEN sdd-qa-lint returns status "warn" (issues found)
THEN record the pre-fix issue list and proceed to Phase 2

---

## Phase 2: Apply Linter Fix Command

Map the detected language to the appropriate fix command and execute it. Only the linter's built-in `--fix` flag is used (INV-LFIX-02).

### Fix Command Mapping

| Language | Linter | Fix Command |
|----------|--------|-------------|
| Python | ruff | `ruff check --fix <path>` |
| TypeScript | eslint | `eslint --fix <path>` |
| JavaScript | eslint | `eslint --fix <path>` |
| Java | checkstyle | No auto-fix available |
| Go | golangci-lint | `golangci-lint run --fix <path>` |

### Language has auto-fix support

WHEN the detected language has a fix command in the table above (Python, TypeScript, JavaScript, Go)
THEN run the fix command via Bash
AND capture both stdout and stderr
AND record the exact command executed (INV-LFIX-04)

```bash
# Example for Python:
ruff check --fix <path>

# Example for TypeScript/JavaScript:
eslint --fix <path>

# Example for Go:
golangci-lint run --fix <path>
```

If the linter config file exists in the project (e.g., `ruff.toml`, `pyproject.toml`, `.eslintrc`, `.eslintrc.json`, `.golangci.yml`), the linter uses it automatically. No extra flags are needed.

### Language has no auto-fix (Java/checkstyle)

WHEN the detected language is Java and the linter is checkstyle
THEN skip the fix command
AND set output status to "error"
AND report: "No auto-fix available for checkstyle. Issues require manual correction."
AND include the full pre-fix issue list as remainingIssues
AND STOP

### Fix command fails

WHEN the fix command exits with a non-zero status
THEN check stderr for error details
AND if the error indicates the linter is not installed, return a `LinterNotInstalled` error
AND if the error indicates a different failure, return a `FixFailed` error with the stderr content
AND set output status to "error"
AND STOP

Note: Some linters (e.g., ruff) exit with non-zero status when unfixed issues remain. This is not a failure. A `FixFailed` error applies only when the linter could not execute at all (crash, missing config, permission denied).

### Linter not installed

WHEN the fix command fails because the linter binary is not found
THEN return:
```
Error: LinterNotInstalled
Linter: <linter name>
Message: "<linter> is not installed. Install it to use auto-fix."
```
AND STOP

---

## Phase 3: Post-Fix Analysis (INV-LFIX-03)

Re-invoke sdd-qa-lint to identify issues that remain after the fix.

```
Skill tool: skill="sdd-qa-lint", args="$ARGUMENTS"
```

Capture the post-fix output. Extract the remaining issue count and issue list.

### Compute Fix Results

Compare pre-fix and post-fix issue lists:
- **fixedCount** = pre-fix issueCount - post-fix issueCount
- **remainingCount** = post-fix issueCount
- **fixedIssues** = issues present in pre-fix but absent in post-fix
- **remainingIssues** = issues present in post-fix

### Determine Status

| Condition | Status |
|-----------|--------|
| remainingCount == 0 | "fixed" |
| remainingCount > 0 and fixedCount > 0 | "partial" |
| remainingCount > 0 and fixedCount == 0 | "partial" |

---

## Output Format

Return the following structured report.

```markdown
# Lint Fix Report

**Path**: <path>
**Language**: <detected language>
**Linter**: <linter name>
**Fix Command**: <exact command executed> (INV-LFIX-04)
**Status**: <fixed|partial|error>

## Summary

| Metric | Count |
|--------|-------|
| Pre-fix issues | X |
| Fixed (auto) | Y |
| Remaining (manual) | Z |

## Fixed Issues

| File | Line | Rule | Message |
|------|------|------|---------|
| path/to/file.ext | 42 | E401 | <description of fixed issue> |
| ... | ... | ... | ... |

(If no issues were fixed, state: "No issues were auto-fixed.")

## Remaining Issues (Manual Fix Required)

| File | Line | Rule | Severity | Message |
|------|------|------|----------|---------|
| path/to/file.ext | 55 | C901 | error | <description> |
| ... | ... | ... | ... | ... |

(If no issues remain, state: "All issues resolved by auto-fix.")
```

### Output Fields

| Field | Type | Description |
|-------|------|-------------|
| linter | string | Linter used (ruff, eslint, golangci-lint) |
| language | string | Detected language |
| path | string | Analyzed/fixed path |
| fixedCount | number | Number of issues auto-fixed |
| remainingCount | number | Number of issues requiring manual fix |
| fixedIssues | array | Issues that were auto-fixed |
| remainingIssues | array | Issues that could not be auto-fixed |
| status | string | "fixed" (all resolved), "partial" (some remain), "error" (fix failed) |

---

## Error Handling

### LinterNotInstalled

WHEN the linter binary is not found in the environment
THEN return:
```
Error: LinterNotInstalled
Linter: <expected linter>
Language: <detected language>
Message: "<linter> is not installed. Install it to enable auto-fix for <language> projects."
```
AND STOP

### FixFailed

WHEN the linter's fix mode returns an error (not just unfixed issues)
THEN return:
```
Error: FixFailed
Linter: <linter>
Command: <exact command executed>
Exit Code: <exit code>
Details: <stderr output>
```
AND STOP

---

## Constraints

- This skill modifies source files ONLY through the linter's `--fix` flag. No other file modifications are permitted (INV-LFIX-02).
- This skill does NOT generate code fixes, rewrite logic, or apply any transformation beyond what the linter provides.
- This skill does NOT use the Edit tool or Write tool to modify source files.
- This skill invokes sdd-qa-lint via the Skill tool (not the Task tool).
- Do not install dependencies or run package managers.
- Do not prompt the user for input. This skill runs non-interactively.
