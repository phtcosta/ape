---
name: sdd-verify
description: "Unified verification gate: runs a 3-stage pipeline (tests, lint, complexity) appropriate to the detected language and reports structured pass/fail results."
argument-hint: "[module-path]"
---

# sdd-verify

Runs a 3-stage verification pipeline against the specified module or directory: tests, lint, and complexity analysis. Each stage uses language-appropriate tools determined by project configuration or auto-detection. The overall result is "pass" only when both tests and lint succeed; complexity is informational and does not affect the overall verdict.

This skill is a Tier 1 composite skill (pipeline) that runs inline (shared context). It coordinates other skills via the Skill tool and runs external tools via Bash.

**Invariants:**

- **INV-VER-01:** The tests stage MUST always run. It is not optional and cannot be skipped.
- **INV-VER-02:** The lint stage MUST always run when a linter is available for the detected language.
- **INV-VER-03:** Overall result SHALL be "fail" if tests fail OR lint has errors. Lint warnings alone do not cause failure.
- **INV-VER-04:** Results MUST be structured per-stage, not collapsed into a single summary.

---

## Input

`$ARGUMENTS` contains the module or directory path to verify.

Examples:
- `mcp-server/src/sdd_mcp` -- verify a specific module
- `src/` -- verify the src directory
- `.` -- verify the entire project from the current directory
- `(empty)` -- defaults to the current working directory

---

## Language Detection

Before running the pipeline, determine the project language. This drives tool selection for lint and complexity stages.

### Step 1: Check sdd-config-reader for configured language

Invoke sdd-config-reader (via Skill tool) with the project root path derived from `$ARGUMENTS`:

```
Skill: sdd-config-reader <project-root>
```

If the returned `language` field is non-null, use it as the detected language. Proceed to the verification pipeline.

### Step 2: Fall back to sdd-detection

If sdd-config-reader returns `language: null`, invoke sdd-detection (via Skill tool):

```
Skill: sdd-detection <project-root>
```

Use the `language` field from the sdd-detection result. Also capture `linter` and `testFramework` if available from the detection result, as they provide tool hints.

### Step 3: No language detected

WHEN both sdd-config-reader and sdd-detection fail to identify a language
THEN still run the tests stage (INV-VER-01)
AND skip lint and complexity stages with status `"skipped"` and reason `"No language detected"`
AND note the skip in the summary

---

## Verification Pipeline

Run the three stages in order. Each stage produces a structured result.

### Stage 1: Tests (mandatory)

This stage MUST always execute (INV-VER-01).

Invoke the `sdd-test-run` skill via the Skill tool, passing `$ARGUMENTS` as the test path:

```
Skill: sdd-test-run <$ARGUMENTS>
```

Capture the test results from sdd-test-run output. Extract:
- Number of tests passed
- Number of tests failed
- Number of tests skipped
- Any error details or failure messages

Determine the stage status:
- `"pass"` -- all tests passed (zero failures)
- `"fail"` -- one or more tests failed
- `"error"` -- test execution itself failed (e.g., command not found, build error)

Record the result:

```yaml
tests:
  status: "pass" | "fail" | "error"
  passed: <number>
  failed: <number>
  skipped: <number>
  details: "<failure messages or error details, empty string if pass>"
```

### Stage 2: Lint (mandatory when linter available)

This stage MUST execute when a linter is available for the detected language (INV-VER-02).

Select the linter based on the detected language (see Language Tool Selection below). Run it via Bash against the target path.

**Running the linter:**

Execute the linter command via Bash. Capture both stdout/stderr and exit code.

```bash
# Example for Python
ruff check <$ARGUMENTS> 2>&1; echo "EXIT_CODE:$?"
```

**Interpreting results:**

- Parse the linter output for error count and warning count.
- Errors cause `status: "fail"`. Warnings alone produce `status: "pass"` (INV-VER-03).
- If the linter binary is not found, set `status: "skipped"` with reason `"<linter> not installed"`.

Record the result:

```yaml
lint:
  status: "pass" | "fail" | "skipped"
  issueCount: <number of errors + warnings>
  details: "<linter output summary or skip reason>"
```

**When no linter exists for the language** (e.g., COBOL):

```yaml
lint:
  status: "skipped"
  issueCount: 0
  details: "No linter available for <language>"
```

### Stage 3: Complexity (optional)

Run the complexity analysis tool if one is available for the detected language. This stage is informational: its result does not affect the overall verdict.

Select the complexity tool based on the detected language (see Language Tool Selection below). Run it via Bash.

```bash
# Example for Python
radon cc <$ARGUMENTS> -n C 2>&1; echo "EXIT_CODE:$?"
```

**Interpreting results:**

- Count functions/methods with cyclomatic complexity above the language-specific threshold.
- Report these as "high complexity" items.

Record the result:

```yaml
complexity:
  status: "pass" | "warn" | "skipped"
  highComplexityCount: <number>
  details: "<list of high-complexity items or skip reason>"
```

**When no complexity tool exists for the language or the tool is not installed:**

```yaml
complexity: null
```

Set to `null` (not an object with `"skipped"` status) when no complexity tool is defined for the language. Use `status: "skipped"` only when the tool is defined but not installed.

---

## Language Tool Selection

Select the linter and complexity tool based on the detected language:

| Language | Linter | Linter Command | Complexity Tool | Complexity Command | Threshold |
|----------|--------|----------------|----------------|--------------------|-----------|
| Python | ruff | `ruff check <path>` | radon | `radon cc <path> -n C` | CC > 10 |
| TypeScript | eslint | `npx eslint <path>` | complexity-report | `npx cr <path>` (optional) | -- |
| JavaScript | eslint | `npx eslint <path>` | complexity-report | `npx cr <path>` (optional) | -- |
| Java | checkstyle | `checkstyle -c /google_checks.xml <path>` | none | -- | -- |
| Go | golangci-lint | `golangci-lint run <path>` | none | -- | -- |
| COBOL | none | -- | none | -- | -- |
| Other | none | -- | none | -- | -- |

**Tool availability check:**

Before running a tool, verify it is installed:

```bash
command -v <tool> 2>/dev/null && echo "AVAILABLE" || echo "MISSING"
```

For npx-based tools (eslint, cr), check for the package in node_modules or attempt `npx --no-install`:

```bash
npx --no-install <tool> --version 2>/dev/null && echo "AVAILABLE" || echo "MISSING"
```

WHEN a tool is defined for the language but not installed
THEN skip that stage with a note indicating which tool is missing
AND do not attempt to install it

---

## Mode-Aware Behavior

Determine the operating mode from sdd-config-reader output (already obtained during language detection).

### Full Mode

- The tests and lint stages run via their respective tools (Skill tool for tests, Bash for lint) regardless of mode.
- MAY query `mcp__sdd__*` tools to retrieve pre-computed complexity data from the knowledge graph instead of running radon/cr locally.
- If MCP complexity data is available, use it for the complexity stage result. If not available, fall back to running the complexity tool via Bash.

### Lite Mode

- All three stages run via Bash and Skill tool. No MCP calls.
- Complexity stage runs the local tool if available.

### Minimal Mode

- All three stages run via Bash and Skill tool. No MCP calls.
- Identical to Lite mode for this skill's purposes.

The core pipeline (tests -> lint -> complexity) is the same in all modes. The only difference is whether Full mode can shortcut the complexity stage using pre-computed MCP data.

---

## Result Aggregation

After all three stages complete, compute the overall result.

### Overall status

```
overall = "pass"  WHEN tests.status == "pass" AND lint.status IN ("pass", "skipped")
overall = "fail"  WHEN tests.status IN ("fail", "error") OR lint.status == "fail"
```

Key rules:
- Tests failing always causes overall failure (INV-VER-01, INV-VER-03).
- Lint errors cause overall failure (INV-VER-03).
- Lint warnings do not cause overall failure.
- Lint skipped (no linter available or not installed) does not cause failure.
- Complexity does not affect overall status regardless of its result.

### Summary line

Generate a one-line summary:

```
PASS: 42 tests, 0 lint issues, 2 high-complexity
FAIL: 40 passed / 2 failed, 3 lint errors, 0 high-complexity
PASS: 15 tests, lint skipped (no linter for cobol)
FAIL: test execution error (pytest not found)
```

---

## Output Format

Return the following structured result (INV-VER-04):

```yaml
overall: "pass" | "fail"

tests:
  status: "pass" | "fail" | "error"
  passed: <number>
  failed: <number>
  skipped: <number>
  details: "<string>"

lint:
  status: "pass" | "fail" | "skipped"
  issueCount: <number>
  details: "<string>"

complexity:                        # null when no tool is defined for the language
  status: "pass" | "warn" | "skipped"
  highComplexityCount: <number>
  details: "<string>"

summary: "<one-line summary>"
```

### Example: All stages pass

```yaml
overall: "pass"

tests:
  status: "pass"
  passed: 42
  failed: 0
  skipped: 3
  details: ""

lint:
  status: "pass"
  issueCount: 0
  details: "ruff: no issues found"

complexity:
  status: "warn"
  highComplexityCount: 2
  details: "src/parser.py:parse_expression (CC=12), src/resolver.py:resolve_deps (CC=14)"

summary: "PASS: 42 tests, 0 lint issues, 2 high-complexity"
```

### Example: Tests fail

```yaml
overall: "fail"

tests:
  status: "fail"
  passed: 38
  failed: 4
  skipped: 0
  details: "test_parser.py::test_nested_expr FAILED, test_parser.py::test_empty_input FAILED, ..."

lint:
  status: "pass"
  issueCount: 2
  details: "ruff: 2 warnings (W291, W292)"

complexity:
  status: "pass"
  highComplexityCount: 0
  details: "radon: no functions above CC threshold"

summary: "FAIL: 38 passed / 4 failed, 2 lint warnings, 0 high-complexity"
```

### Example: No linter available (COBOL)

```yaml
overall: "pass"

tests:
  status: "pass"
  passed: 8
  failed: 0
  skipped: 0
  details: ""

lint:
  status: "skipped"
  issueCount: 0
  details: "No linter available for cobol"

complexity: null

summary: "PASS: 8 tests, lint skipped (no linter for cobol)"
```

### Example: Linter not installed

```yaml
overall: "pass"

tests:
  status: "pass"
  passed: 20
  failed: 0
  skipped: 1
  details: ""

lint:
  status: "skipped"
  issueCount: 0
  details: "ruff not installed"

complexity: null

summary: "PASS: 20 tests, lint skipped (ruff not installed)"
```

---

## Constraints

- This skill does not install dependencies. Missing tools are skipped with a note.
- Tests are always invoked via the Skill tool (`sdd-test-run`), not directly via Bash.
- Lint and complexity tools are invoked via Bash.
- Do not prompt the user during pipeline execution. Run all stages and present the final structured result.
- Do not modify any source files. This skill is read-only with respect to project code.
