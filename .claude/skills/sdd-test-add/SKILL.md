---
name: sdd-test-add
description: "Generate test cases for a code unit following project conventions. Use WHEN adding tests for untested code, new functions, or classes. WHEN NOT: use sdd-test-run to run existing tests."
context: fork
argument-hint: "[code-path]"
---

# Add Tests: $ARGUMENTS

Generate test cases for the specified code unit, following the project's existing test patterns and conventions. This skill reads existing test files to learn naming conventions, assertion style, fixture usage, and directory structure, then creates new test files that match.

This skill is a Tier 3 support component. It runs as a forked subagent. It is user-invocable and also called by orchestrator skills (e.g., sdd-tdd).

**Non-leaf skill**: This skill invokes `sdd-detection` via the Skill tool during framework detection.

**Invariants:**

- **INV-TADD-01**: Generated test files MUST follow the project's existing naming conventions. If no existing tests exist, the skill MUST use framework defaults.
- **INV-TADD-02**: The skill MUST read at least one existing test file (when available) before generating tests, to learn the project's assertion style and structure.
- **INV-TADD-03**: Generated tests MUST be syntactically valid and runnable by the detected test framework without modification.

---

## Input

`$ARGUMENTS` contains the path to the code unit to test. This can be a file path or a descriptive argument.

Examples:
- `src/parser.py` -- generate tests for a Python module
- `src/utils/format.ts` -- generate tests for a TypeScript file
- `src/auth/` -- generate tests for all public units in a directory
- `"add tests for the Parser class in src/parser.py"` -- descriptive form

---

## Step 1: Detect Test Framework

Invoke `sdd-detection` via the Skill tool to identify the project's language and test framework.

```
Skill tool: skill="sdd-detection"
```

Capture the result fields:
- `language` -- programming language (e.g., `"python"`, `"typescript"`, `"java"`)
- `testFramework` -- test framework (e.g., `"pytest"`, `"jest"`, `"junit"`)
- `buildTool` -- build tool (e.g., `"pip/uv"`, `"npm"`, `"gradle"`)

WHEN sdd-detection returns an error or `testFramework: "unknown"`
THEN return a `NoTestFramework` error (see Error Handling) and STOP

---

## Step 2: Verify Target Exists

Confirm the target code unit exists using Glob or Read.

WHEN the path specified in `$ARGUMENTS` does not exist
THEN return a `TargetNotFound` error (see Error Handling) and STOP

---

## Step 3: Learn Project Conventions (INV-TADD-02)

Before generating any tests, learn the project's testing conventions from existing test files.

### 3a: Search for Existing Test Files

Use Glob to find test files with framework-appropriate patterns:

| Framework | Search Patterns |
|-----------|----------------|
| pytest | `**/test_*.py`, `**/*_test.py` |
| junit | `**/*Test.java`, `**/*Tests.java`, `**/*Spec.java` |
| jest | `**/*.test.ts`, `**/*.test.tsx`, `**/*.test.js`, `**/*.spec.ts`, `**/*.spec.js` |
| vitest | `**/*.test.ts`, `**/*.test.tsx`, `**/*.spec.ts` |
| mocha | `**/*.test.js`, `**/*.spec.js`, `**/test/**/*.js` |
| go test | `**/*_test.go` |

Exclude `node_modules`, `.git`, `build`, `dist`, `target`, `__pycache__`, `vendor`, `.venv`.

### 3b: Detect Test File Placement Pattern

Examine where existing test files live relative to source files to determine the placement pattern:

**Mirror pattern**: Test files live in a separate directory tree that mirrors the source tree.
- `src/utils/parser.py` -> `tests/utils/test_parser.py`
- `src/main/java/com/app/Parser.java` -> `src/test/java/com/app/ParserTest.java`

**Co-located pattern**: Test files sit next to the source files they test.
- `src/parser.ts` -> `src/parser.test.ts`
- `src/utils/format.js` -> `src/utils/format.test.js`

To detect: check if any existing test files share a parent directory with source files. If test files are found alongside source files, use co-located. Otherwise, identify the test root directory (e.g., `tests/`, `test/`, `src/test/`) and use mirroring.

WHEN no existing test files are found
THEN record `placement: "unknown"` and determine placement from framework defaults in Step 3d

### 3c: Read Existing Test Files to Extract Conventions

Select 1-3 existing test files (preferring those closest to the target code unit in the directory tree). Read each file using the Read tool and extract:

1. **Naming convention**: How test files and test functions/methods are named.
   - File: `test_*.py` vs `*_test.py` vs `*.test.ts` vs `*Test.java`
   - Function/method: `test_*` vs `it("should ...")` vs `@Test void testX()`

2. **Assertion style**: What assertion functions or patterns are used.
   - Python: plain `assert`, `pytest.raises`, `assertEqual`
   - JavaScript/TypeScript: `expect(...).toBe(...)`, `assert.equal(...)`, `chai.expect`
   - Java: `assertEquals(...)`, `assertThat(...)`, `Assertions.assert*`
   - Go: `t.Errorf(...)`, `assert.Equal(t, ...)` (testify)

3. **Import patterns**: How the code under test is imported.
   - Relative vs absolute imports
   - Specific vs wildcard imports

4. **Fixture/setup usage**: How test setup is handled.
   - Python: `@pytest.fixture`, `setUp()`
   - JavaScript/TypeScript: `beforeEach()`, `beforeAll()`, `describe()` blocks
   - Java: `@Before`, `@BeforeEach`, `@BeforeAll`
   - Go: `TestMain()`, `t.Cleanup()`

5. **Structure**: Class-based vs function-based test organization.

Record these as the `conventions` object for the output.

### 3d: Handle No Existing Tests (INV-TADD-01)

WHEN no existing test files are found (NoExistingTests condition)
THEN use framework defaults:

| Framework | File Naming | Test Naming | Assertion | Placement |
|-----------|-------------|-------------|-----------|-----------|
| pytest | `test_<module>.py` | `test_<behavior>()` | plain `assert` | `tests/` mirror |
| jest | `<module>.test.ts` | `it("should <behavior>")` | `expect().toBe()` | co-located |
| vitest | `<module>.test.ts` | `it("should <behavior>")` | `expect().toBe()` | co-located |
| junit | `<Module>Test.java` | `@Test void test<Behavior>()` | `assertEquals()` | `src/test/java/` mirror |
| go test | `<module>_test.go` | `Test<Behavior>(t *testing.T)` | `t.Errorf()` | co-located |
| mocha | `<module>.test.js` | `it("should <behavior>")` | `assert.equal()` | `test/` mirror |

AND note in the output that default conventions were used

---

## Step 4: Analyze Target Code Unit

Read the target code file(s) using the Read tool. For each file, identify:

1. **Public API surface**: Functions, methods, and classes that are part of the public interface.
   - Python: functions/classes not prefixed with `_`
   - TypeScript/JavaScript: exported functions, classes, constants
   - Java/Kotlin: `public` methods and classes
   - Go: capitalized function/type names

2. **Function signatures**: Parameters, types (if available), return values.

3. **Edge cases**: Conditions where behavior changes (null checks, empty inputs, boundary values, error paths).

4. **Dependencies**: External calls that need mocking (database, network, filesystem, other modules).

---

## Step 5: Plan Test Cases

For each public function/method/class identified in Step 4, plan test cases covering:

### Categories

| Category | Description | Priority |
|----------|-------------|----------|
| Happy path | Expected behavior with valid typical inputs | Required |
| Boundary values | Edges of valid input ranges (min, max, empty, single element) | Required |
| Error cases | Invalid inputs, expected exceptions, error return values | Required |
| Edge cases | Null/nil/undefined, empty collections, special characters | Recommended |
| State transitions | For stateful objects: state changes across method calls | When applicable |

### Test Design Guidelines

- **Equivalence partitioning**: Divide inputs into classes that should produce equivalent behavior. Test one value from each class.
- **Boundary value analysis**: Test at the edges of equivalence classes (min, max, min-1, max+1).
- **Each test case MUST have concrete input/output values** -- no placeholder or abstract assertions.
- **One logical assertion per test** when possible. Multiple assertions are acceptable when verifying a single behavior.

Generate at least one test case per public function (INV-TADD-03).

---

## Step 6: Determine Test File Path

Using the placement pattern detected in Step 3b, determine where to write the test file.

### Mirror Pattern

Map the source path to the test directory:

```
Source: src/utils/parser.<ext>
Test:   tests/utils/test_parser.<ext>     (Python)
Test:   tests/utils/ParserTest.<ext>      (Java)
```

Create intermediate directories if they do not exist.

### Co-located Pattern

Place the test file adjacent to the source:

```
Source: src/utils/parser.ts
Test:   src/utils/parser.test.ts
```

### Naming

Apply the naming convention detected in Step 3c (or defaults from Step 3d):

| Convention | Source File | Test File |
|------------|------------|-----------|
| `test_*` prefix | `parser.py` | `test_parser.py` |
| `*_test` suffix | `parser.py` | `parser_test.py` |
| `.test.*` suffix | `parser.ts` | `parser.test.ts` |
| `.spec.*` suffix | `parser.ts` | `parser.spec.ts` |
| `*Test` suffix | `Parser.java` | `ParserTest.java` |
| `*_test` suffix | `parser.go` | `parser_test.go` |

---

## Step 7: Generate and Write Test Files

Write the test file(s) using Write or Edit tools, following the conventions extracted in Step 3.

The generated test file MUST:
- Use the detected assertion style, import patterns, and fixture usage
- Include all planned test cases from Step 5
- Use descriptive test names that explain the scenario being tested
- Mock external dependencies (network, database, filesystem, other modules)
- Be syntactically valid and runnable without modification (INV-TADD-03)

WHEN the test file already exists
THEN append new test cases to the existing file using Edit (do not overwrite existing tests)
AND set `action: "modified"` in the output

WHEN the test file does not exist
THEN create it using Write
AND set `action: "created"` in the output

---

## Step 8: Verify Generated Tests (Optional)

WHEN the test framework is available and the project can run tests
THEN run the generated tests to verify they are syntactically valid:

```bash
<framework-specific command> <test-file-path>
```

WHEN a generated test has a syntax error or fails to compile
THEN fix the test before reporting completion (INV-TADD-03)

WHEN the test framework is not installed or tests cannot be executed
THEN skip verification and note it in the output

---

## Error Handling

### NoTestFramework

WHEN sdd-detection cannot identify the test framework or returns `testFramework: "unknown"`
THEN return:

```
Error: NoTestFramework
Message: "Could not detect the test framework for this project."
Suggestion: "Configure the test framework in .sdd/sdd-config.yaml or ensure build files (pyproject.toml, package.json, build.gradle, pom.xml, go.mod) are present in the project root."
```

AND STOP

### TargetNotFound

WHEN the specified code unit path does not exist
THEN return:

```
Error: TargetNotFound
Message: "Target path '<path>' does not exist."
Suggestion: "Verify the file path and try again."
```

AND STOP

### NoExistingTests

This is NOT a fatal error. When no existing test files are found:

1. Use framework defaults for conventions (see Step 3d).
2. Note in the output `conventions` that defaults were used.
3. Proceed with test generation.

---

## Output Format

Return the following structured report after generating tests:

```markdown
# Test Generation Report

## Summary

- **Target**: <path to code unit>
- **Framework**: <detected test framework>
- **Language**: <detected language>

## Test Files

| File | Action | Tests |
|------|--------|-------|
| <test-file-path> | created | <count> |
| ... | ... | ... |

**Total tests generated**: <totalTests>

## Conventions Detected

- **Naming**: <file naming pattern, e.g., "test_*.py">
- **Assertions**: <assertion style, e.g., "plain assert">
- **Fixtures**: <fixture pattern, e.g., "@pytest.fixture">
- **Structure**: <class-based or function-based>
- **Placement**: <mirror or co-located>
- **Source**: <"existing tests" or "framework defaults">

## Test Cases

### <test-file-path>

| # | Test Name | Description | Category |
|---|-----------|-------------|----------|
| 1 | <test_name> | <what it verifies> | <happy-path/boundary/error/edge> |
| ... | ... | ... | ... |
```

### Structured Fields (for programmatic consumers)

```yaml
framework: "<test-framework>"
language: "<language>"
targetPath: "<path>"
testFiles:
  - path: "<test-file-path>"
    action: "created"   # or "modified"
    testCount: <number>
totalTests: <number>
conventions:
  naming: "<pattern>"
  assertions: "<style>"
  fixtures: "<pattern>"
  structure: "<class-based or function-based>"
  placement: "<mirror or co-located>"
  source: "<existing tests or framework defaults>"
```

---

## Constraints

- This skill invokes `sdd-detection` via the Skill tool. It does NOT use the Task tool.
- This skill writes test files. It creates new files or appends to existing test files. It does NOT modify source code under test.
- Do not install dependencies or run package managers.
- Do not prompt the user for input. Parse `$ARGUMENTS` directly.
- Keep generated tests focused on the specified code unit. Do not generate tests for transitive dependencies.
- When the target is a directory with many files (10+), prioritize files with more public API surface and cap at 10 files per invocation. Note the cap in the output if applied.
