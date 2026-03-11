---
name: sdd-test-run
description: "Run the project's test suite using the detected test framework and report structured results with pass/fail/skip counts."
user-invocable: false
---

# sdd-test-run

Runs the target project's test suite using the appropriate test framework, parses the output, and returns structured results. This skill detects the test framework from project configuration files, executes tests, and reports pass/fail/skip counts with failure details.

This skill is a Tier 3 support component. It runs inline (shared context) and is called by other skills, never directly by users. It is NOT mode-aware -- tests run against actual code regardless of the SDD operating mode.

**Invariants:**

- **INV-TR-01:** MUST report the actual test command executed in the output `command` field.
- **INV-TR-02:** Test failures (assertion failures, expected-vs-actual mismatches) SHALL produce `status: "fail"` with details. Test failures SHALL NOT produce `status: "error"`.
- **INV-TR-03:** `status: "error"` is reserved for infrastructure problems: framework not installed, command not found, execution crash before tests run.
- **INV-TR-04:** This skill MUST NOT create, write, or modify any source file.

---

## Input

`$ARGUMENTS` contains an optional module or directory path to test. When omitted, run the full test suite from the project root.

Examples:
- `(empty)` -- run full test suite from project root
- `src/auth` -- run tests scoped to the `src/auth` directory
- `tests/unit` -- run tests scoped to the `tests/unit` directory

---

## Framework Detection

Detect the test framework by checking project configuration files in priority order. Stop at the first match.

### Priority 1: Python (pytest) via pyproject.toml

Read `pyproject.toml` in the project root. Check for either of these TOML sections:

- `[tool.pytest]`
- `[tool.pytest.ini_options]`

```bash
test -f pyproject.toml && grep -qE '^\[tool\.pytest' pyproject.toml && echo "MATCH" || echo "NO_MATCH"
```

WHEN `pyproject.toml` contains `[tool.pytest]` or `[tool.pytest.ini_options]`
THEN set `framework: "pytest"`

### Priority 2: Python (pytest) via setup.cfg

Read `setup.cfg` in the project root. Check for:

- `[tool:pytest]`

```bash
test -f setup.cfg && grep -qE '^\[tool:pytest\]' setup.cfg && echo "MATCH" || echo "NO_MATCH"
```

WHEN `setup.cfg` contains `[tool:pytest]`
THEN set `framework: "pytest"`

### Priority 3: Java/Kotlin (JUnit) via Gradle

Check for `build.gradle` or `build.gradle.kts` in the project root.

```bash
test -f build.gradle -o -f build.gradle.kts && echo "MATCH" || echo "NO_MATCH"
```

WHEN `build.gradle` or `build.gradle.kts` exists
THEN set `framework: "junit"` with runner `gradle`

### Priority 4: Java (JUnit) via Maven

Check for `pom.xml` in the project root.

```bash
test -f pom.xml && echo "MATCH" || echo "NO_MATCH"
```

WHEN `pom.xml` exists
THEN set `framework: "junit"` with runner `maven`

### Priority 5: JavaScript/TypeScript with configured test script

Read `package.json` in the project root. Check for a `scripts.test` entry.

```bash
test -f package.json && cat package.json | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('scripts',{}).get('test',''))" 2>/dev/null
```

WHEN `package.json` exists AND `scripts.test` is defined and non-empty
THEN use `npm test` as the command
AND derive the framework name from the script content:
- Contains `jest` -> `framework: "jest"`
- Contains `vitest` -> `framework: "vitest"`
- Contains `mocha` -> `framework: "mocha"`
- Otherwise -> `framework: "npm-test"` (generic)

### Priority 6: JavaScript/TypeScript with test framework in devDependencies

WHEN `package.json` exists AND `scripts.test` is absent or empty
THEN check `devDependencies` and `dependencies` for test framework packages:

| Package | Framework | Command |
|---------|-----------|---------|
| `jest` | jest | `npx jest` |
| `vitest` | vitest | `npx vitest run` |
| `mocha` | mocha | `npx mocha` |

Select the first match in the order listed above.

### Priority 7: Go

Check for `go.mod` in the project root.

```bash
test -f go.mod && echo "MATCH" || echo "NO_MATCH"
```

WHEN `go.mod` exists
THEN set `framework: "go test"`

### Priority 8: Fallback via sdd-detection

WHEN none of the above checks match
THEN invoke the `sdd-detection` skill to detect the project language and test framework.

Use the `testFramework` and `build.test` values from the sdd-detection result to determine the test command.

WHEN sdd-detection returns `testFramework: "unknown"` or fails
THEN return a `NoTestFramework` error (see Error Handling).

---

## Test Command Construction

### Full Suite (no $ARGUMENTS)

| Framework | Command |
|-----------|---------|
| pytest | `pytest -v` |
| junit (gradle) | `./gradlew test` |
| junit (maven) | `mvn test` |
| npm-test | `npm test` |
| jest | `npx jest` |
| vitest | `npx vitest run` |
| mocha | `npx mocha` |
| go test | `go test ./...` |

### Scoped Execution (when $ARGUMENTS provides a path)

| Framework | Command |
|-----------|---------|
| pytest | `pytest <path> -v` |
| junit (gradle) | `./gradlew test --tests "<path>"` |
| junit (maven) | `mvn test -pl <path>` |
| jest | `npx jest <path>` |
| vitest | `npx vitest run <path>` |
| mocha | `npx mocha "<path>/**/*.test.*"` |
| go test | `go test ./<path>/...` |

Where `<path>` is the value from `$ARGUMENTS`.

---

## Test Execution

Run the constructed command using Bash. Capture both stdout and stderr.

```bash
<constructed-command> 2>&1
```

Record the exit code:
- Exit code 0 typically means all tests passed.
- Non-zero exit code may mean test failures OR execution errors. Distinguish by parsing the output.

Record the command exactly as executed for the `command` output field (INV-TR-01).

---

## Result Parsing

Parse the test output to extract pass/fail/skip counts and failure details. Each framework has a distinct output format.

### pytest

Look for the summary line near the end of output:

```
= X passed, Y failed, Z skipped in N.NNs =
```

Not all parts are always present. Examples:
- `= 5 passed in 1.23s =` -> passed: 5, failed: 0, skipped: 0, duration: "1.23s"
- `= 3 passed, 2 failed, 1 skipped in 4.56s =` -> passed: 3, failed: 2, skipped: 1, duration: "4.56s"
- `= 2 failed in 0.89s =` -> passed: 0, failed: 2, skipped: 0, duration: "0.89s"

For failure details, extract FAILED test names and their assertion messages from the `FAILURES` section.

### JUnit (Gradle)

Look for the summary line:

```
X tests completed, Y failed, Z skipped
```

Or the `BUILD SUCCESSFUL` / `BUILD FAILED` indicators combined with the test report.

Duration: extract from `BUILD SUCCESSFUL in Ns` or `BUILD FAILED in Ns`.

For failure details, extract test names and assertion errors from the Gradle output.

### JUnit (Maven)

Look for the Surefire summary:

```
Tests run: X, Failures: Y, Errors: Z, Skipped: W
```

- `passed` = Tests run - Failures - Errors - Skipped
- `failed` = Failures + Errors
- `skipped` = Skipped

Duration: extract from `Total time: N.NNN s`.

For failure details, extract test names and stack traces from the `Failed tests:` section.

### Jest

Look for the summary lines:

```
Tests:       X failed, Y skipped, Z passed, W total
Time:        N.NNNs
```

Parse the `Tests:` line for counts and `Time:` line for duration.

For failure details, extract the `FAIL` test file paths and assertion error messages.

### Vitest

Look for the summary:

```
Tests  X failed | Y passed (Z)
Duration  N.NNs
```

Parse accordingly. For failure details, extract the `FAIL` entries.

### Mocha

Look for the summary:

```
  X passing (Ns)
  Y failing
  Z pending
```

Map `passing` -> passed, `failing` -> failed, `pending` -> skipped.

For failure details, extract numbered failure descriptions.

### Go test

Look for the summary line:

```
ok      package/path    N.NNNs
FAIL    package/path    N.NNNs
```

Count `ok` lines for passed packages and `FAIL` lines for failed packages. Individual test counts can be derived from `--- PASS:` and `--- FAIL:` lines.

Duration: sum durations from individual package lines, or use the total elapsed time.

For failure details, extract `--- FAIL:` test names and their output.

### Parsing Fallback

WHEN the output does not match any known format
THEN determine status from the exit code:
- Exit code 0 -> `status: "pass"`, set counts to `passed: -1, failed: 0, skipped: 0` (indicates unknown count)
- Non-zero exit code -> check output for keywords like "FAIL", "FAILED", "Error", "error"
  - If test-failure keywords found -> `status: "fail"`
  - If infrastructure-error keywords found (e.g., "command not found", "No module named", "Cannot find module") -> `status: "error"`

---

## Error Handling

### NoTestFramework

WHEN no test framework is detected (all priority checks fail, including sdd-detection fallback)
THEN return:

```yaml
status: "error"
framework: "none"
passed: 0
failed: 0
skipped: 0
duration: "0s"
failureDetails: ""
command: ""
error:
  type: "NoTestFramework"
  message: "No test framework detected. Checked for: pyproject.toml (pytest), setup.cfg (pytest), build.gradle (junit), pom.xml (junit), package.json (jest/vitest/mocha), go.mod (go test)."
```

This is an infrastructure error (no framework), not a test failure. It uses `status: "error"` (INV-TR-03).

### TestExecutionError

WHEN the test command fails due to infrastructure problems (not test assertion failures)
THEN return `status: "error"` with details.

Infrastructure problems include:
- Test framework binary not found (`command not found`, `No such file or directory`)
- Missing dependencies (`No module named ...`, `Cannot find module ...`)
- Build failure before tests run (`BUILD FAILED` with compilation errors)
- Permission denied
- Out of memory or timeout

```yaml
status: "error"
framework: "<detected-framework>"
passed: 0
failed: 0
skipped: 0
duration: "0s"
failureDetails: "<stderr or relevant output>"
command: "<the command that was executed>"
error:
  type: "TestExecutionError"
  message: "<description of what went wrong>"
```

### Distinguishing "fail" from "error"

The distinction between `status: "fail"` and `status: "error"` is critical (INV-TR-02, INV-TR-03):

| Situation | Status | Reason |
|-----------|--------|--------|
| 3 tests pass, 2 tests fail with assertion errors | `"fail"` | Tests ran and produced results |
| `pytest` command not found | `"error"` | Infrastructure: framework not installed |
| Compilation error prevents tests from running | `"error"` | Infrastructure: code does not compile |
| All tests pass | `"pass"` | Tests ran and all passed |
| No test files found by the framework | `"pass"` | Framework ran, found nothing to fail (0 passed, 0 failed) |
| `npm test` exits non-zero with assertion failures | `"fail"` | Tests ran and produced failures |

---

## Output Format

Return a structured result with these fields:

```yaml
status: "pass"              # "pass", "fail", or "error"
framework: "pytest"         # detected framework name
passed: 42                  # number of passing tests
failed: 0                   # number of failing tests
skipped: 3                  # number of skipped tests
duration: "4.2s"            # execution duration
failureDetails: ""          # failure descriptions (empty if all pass)
command: "pytest -v"        # the actual command executed (INV-TR-01)
```

### Field Definitions

| Field | Type | Description |
|-------|------|-------------|
| `status` | string | `"pass"` (all tests passed), `"fail"` (test assertions failed), or `"error"` (infrastructure problem) |
| `framework` | string | Detected framework: `"pytest"`, `"junit"`, `"jest"`, `"vitest"`, `"mocha"`, `"go test"`, `"npm-test"`, or `"none"` |
| `passed` | number | Count of passing tests. `-1` if count could not be parsed. |
| `failed` | number | Count of failing tests. |
| `skipped` | number | Count of skipped/pending tests. |
| `duration` | string | Execution time as reported by the framework (e.g., `"4.2s"`, `"12.34s"`). `"0s"` if not available. |
| `failureDetails` | string | Concatenated failure descriptions: test names and assertion messages. Empty string if all tests pass. |
| `command` | string | The exact command that was executed. Empty string if no command was run (e.g., `NoTestFramework`). |
| `error` | object or null | Error details when `status` is `"error"`. Contains `type` (`"NoTestFramework"` or `"TestExecutionError"`) and `message`. `null` when tests ran. |

### Example: All tests pass (pytest)

```yaml
status: "pass"
framework: "pytest"
passed: 15
failed: 0
skipped: 2
duration: "3.45s"
failureDetails: ""
command: "pytest -v"
```

### Example: Some tests fail (jest)

```yaml
status: "fail"
framework: "jest"
passed: 20
failed: 3
skipped: 0
duration: "8.12s"
failureDetails: "FAIL src/auth/login.test.ts: Expected status 200, received 401\nFAIL src/api/users.test.ts: TypeError: Cannot read property 'id' of undefined\nFAIL src/utils/format.test.ts: Expected '2024-01-01' to equal '01/01/2024'"
command: "npx jest"
```

### Example: Scoped execution (go test)

```yaml
status: "pass"
framework: "go test"
passed: 8
failed: 0
skipped: 1
duration: "1.23s"
failureDetails: ""
command: "go test ./internal/auth/..."
```

### Example: Framework not found (error)

```yaml
status: "error"
framework: "pytest"
passed: 0
failed: 0
skipped: 0
duration: "0s"
failureDetails: "bash: pytest: command not found"
command: "pytest -v"
error:
  type: "TestExecutionError"
  message: "pytest is not installed or not in PATH."
```

---

## Constraints

- This skill is read-only with respect to source code. It MUST NOT create, write, or modify any source file (INV-TR-04). It only executes test commands.
- Do not prompt the user for input. This skill runs non-interactively as a subroutine for other skills.
- Do not install dependencies or run package managers to install test frameworks. If the framework is missing, report a `TestExecutionError`.
- Return the structured output and nothing else. No commentary, no suggestions, no next steps.
