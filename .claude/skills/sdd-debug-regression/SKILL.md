---
name: sdd-debug-regression
description: "Investigate test regressions by analyzing git history to find the causal commit. Use WHEN a test that previously passed now fails. WHEN NOT: for general debugging unrelated to git history."
context: fork
argument-hint: "[failing-test]"
---

# Debug Regression: $ARGUMENTS

Investigate a test regression by searching git history to find the commit that introduced the failure. Confirm the test currently fails, search backwards through commits to find when it started failing, analyze the causal commit's diff, and produce a structured regression report.

This skill is a Tier 3 support component. It runs as a forked subagent and is invoked by developers, by sdd-tdd during regression investigation, or by the sdd-tester agent.

**Invariants:**

- **INV-DREG-01:** The skill MUST restore the original branch/HEAD after analysis, regardless of whether the analysis succeeded or failed.
- **INV-DREG-02:** The skill MUST NOT modify any source files. Git checkouts are temporary and reverted.
- **INV-DREG-03:** The skill MUST verify the test currently fails before beginning historical analysis. If the test passes, report `TestPassesNow` and stop.
- **INV-DREG-04:** When uncommitted changes exist, the skill MUST stash them before checking out historical commits and restore them after analysis.

---

## Input

`$ARGUMENTS` contains the failing test identifier. This can be a test file path, a specific test name, or a descriptive reference.

Examples:
- `tests/test_parser.py::test_parse_expression` -- a specific pytest test
- `src/parser.test.ts` -- a test file (JavaScript/TypeScript)
- `test_parse_expression in test_parser.py fails with TypeError` -- descriptive reference

---

## Workflow Overview

```
STEP 1: CONFIRM REGRESSION ──────────────────────────────────────►
    │  Run test on HEAD via sdd-test-run; if passes → stop
    ▼
STEP 2: PRESERVE WORKING STATE ──────────────────────────────────►
    │  Record branch, stash uncommitted changes if any
    ▼
STEP 3: DETERMINE STRATEGY ──────────────────────────────────────►
    │  Count recent commits: <10 → linear scan, ≥10 → git bisect
    ▼
STEP 4: EXECUTE SEARCH ──────────────────────────────────────────►
    │  Walk history to find causal commit
    ▼
STEP 5: ANALYZE CAUSAL COMMIT ───────────────────────────────────►
    │  Diff good..bad, identify changed files, generate hypothesis
    ▼
STEP 6: RESTORE WORKING STATE (always executes) ─────────────────►
    │  Checkout original branch, pop stash
    ▼
REPORT ──────────────────────────────────────────────────────────►
```

---

## Step 1: Confirm Regression (INV-DREG-03)

Run the failing test on the current HEAD using the **Skill tool**. Do NOT run tests directly via Bash -- delegate to sdd-test-run.

```
Skill tool: skill="sdd-test-run", args="$ARGUMENTS"
```

Parse the sdd-test-run output:

**WHEN** the test passes (`status: "pass"`):
- Report `TestPassesNow` error (see Error Handling section).
- STOP. Do not proceed to historical analysis.

**WHEN** the test fails (`status: "fail"`):
- Record the test name, error message, and failure details from the output.
- Proceed to Step 2.

**WHEN** the test produces an error (`status: "error"`):
- If the error is `TestNotFound`-like (test file does not exist, no matching test found), report `TestNotFound` error and STOP.
- If the error is an infrastructure problem (framework not installed, build failure), report it and STOP.

---

## Step 2: Preserve Working State (INV-DREG-04)

Before checking out any historical commits, preserve the developer's working state.

### 2a: Record Current Branch

```bash
git rev-parse --abbrev-ref HEAD
```

Store this as `originalBranch`. If the result is `HEAD` (detached HEAD state), store the full SHA instead:

```bash
git rev-parse HEAD
```

### 2b: Check for Uncommitted Changes

```bash
git status --porcelain
```

**WHEN** the output is non-empty (uncommitted changes exist):
- Run `git stash push -m "sdd-debug-regression: auto-stash"`.
- Set `didStash = true`.
- WHEN `git stash` fails (exit code non-zero), report `StashConflict` error and STOP. Do NOT proceed to checkout historical commits with a dirty working tree.

**WHEN** the output is empty:
- Set `didStash = false`.

---

## Step 3: Determine Analysis Strategy

Count the number of recent commits to decide between linear scan and binary search (Design Decision D4).

### 3a: Count Commits

```bash
git rev-list --count HEAD
```

**WHEN** the total commit count is less than 2:
- Report `GitHistoryInsufficient` error.
- Execute Step 6 (restore) before stopping.

### 3b: Determine Search Window

```bash
git log --oneline -50
```

Use a default search window of the last 50 commits (or all commits if fewer than 50 exist).

### 3c: Select Strategy

**WHEN** the search window has fewer than 10 commits:
- Use **linear scan**: check each commit from newest to oldest.

**WHEN** the search window has 10 or more commits:
- Use **git bisect**: binary search for the causal commit.

---

## Step 4: Execute Search

### Linear Scan (fewer than 10 commits)

Walk backwards through the search window, checking out each commit and running the failing test.

For each commit, starting from HEAD~1 and moving backwards:

```bash
git checkout <commit-sha>
```

Then invoke sdd-test-run:

```
Skill tool: skill="sdd-test-run", args="$ARGUMENTS"
```

Track the result for each commit:

| Commit | Result |
|--------|--------|
| HEAD (already tested) | FAIL |
| HEAD~1 | ? |
| HEAD~2 | ? |
| ... | ... |

**WHEN** a commit is found where the test passes:
- Mark that commit as `previousGoodCommit`.
- Mark the next commit (the one after it, towards HEAD) as `causalCommit`.
- Stop scanning.

**WHEN** all commits in the window fail:
- Set `status: "inconclusive"`.
- Note that the regression predates the search window.

### Git Bisect (10 or more commits)

Use `git bisect` for binary search.

```bash
git bisect start
git bisect bad HEAD
git bisect good <oldest-commit-in-window>
```

First, verify the test passes on the oldest commit in the window. Checkout that commit and run the test. If it also fails, expand the window or report inconclusive.

After `git bisect start`, git checks out a midpoint commit. For each midpoint:

1. Run the test via sdd-test-run:
   ```
   Skill tool: skill="sdd-test-run", args="$ARGUMENTS"
   ```

2. Mark the result:
   ```bash
   git bisect good   # if test passes
   git bisect bad    # if test fails
   ```

3. Git checks out the next midpoint. Repeat until bisect identifies the first bad commit.

When bisect completes, it prints:
```
<sha> is the first bad commit
```

Record this as `causalCommit`. The commit immediately before it (its parent) is `previousGoodCommit`.

```bash
git bisect reset
```

This returns to the original HEAD. Still proceed to Step 6 for stash restoration.

---

## Step 5: Analyze Causal Commit

Once the causal commit is identified, analyze the diff to understand what changed.

### 5a: Get Commit Details

```bash
git log -1 --format="%H%n%an%n%aI%n%s" <causalCommit>
```

Extract: SHA, author, date (ISO 8601), and commit message.

### 5b: Get Changed Files

```bash
git diff --name-only <previousGoodCommit>..<causalCommit>
```

Store the list as `changedFiles`.

### 5c: Get Diff

```bash
git diff <previousGoodCommit>..<causalCommit>
```

Review the full diff. Focus on changes that relate to:
- The failing test file itself
- Source files imported or referenced by the failing test
- Shared utilities, configurations, or fixtures used by the test

### 5d: Generate Hypothesis

Construct a `hypothesis` that:
1. References the specific changed files from the diff.
2. Identifies the code changes (added, modified, or removed lines) that relate to the failing test's assertions or dependencies.
3. Explains the connection: how the code change could cause the observed test failure.

The hypothesis is an analytical explanation, not a fix recommendation.

---

## Step 6: Restore Working State (INV-DREG-01)

This step MUST execute regardless of whether the analysis succeeded, failed, or was interrupted by an error. Treat this as a "finally" block -- every exit path that follows Step 2 MUST pass through Step 6.

### 6a: Return to Original Branch

```bash
git checkout <originalBranch>
```

Where `originalBranch` is the value recorded in Step 2a.

WHEN `git checkout` fails:
- Try `git checkout -f <originalBranch>` as a fallback.
- If that also fails, report the error but still attempt stash pop.

### 6b: Restore Stashed Changes

**WHEN** `didStash` is true:

```bash
git stash pop
```

WHEN `git stash pop` fails (conflict):
- Report `StashConflict` in the output alongside any regression findings.
- The stash is preserved (not lost). Inform the user they can recover with `git stash list` and `git stash pop` or `git stash drop`.

### 6c: Verify Restoration

```bash
git rev-parse --abbrev-ref HEAD
```

Confirm this matches `originalBranch`. If it does not, report the discrepancy in the output.

---

## Output Format

Return a structured regression report.

**WHEN** the causal commit is identified (`status: "identified"`):

```yaml
failingTest: "tests/test_parser.py::test_parse_expression"
status: "identified"
causalCommit:
  sha: "def456abc789"
  author: "developer@example.com"
  date: "2026-01-15T10:30:00+00:00"
  message: "Refactor parser expression handling"
previousGoodCommit: "abc123def456"
changedFiles:
  - "src/parser.py"
  - "src/utils.py"
diffSummary: "src/parser.py: changed parse_expression() return type from list to generator; src/utils.py: removed flatten() helper"
hypothesis: "The causal commit changed parse_expression() to return a generator instead of a list. The failing test calls len() on the result, which is not supported by generators. The removal of flatten() in utils.py is unrelated to this test."
commitsAnalyzed: 7
```

**WHEN** the causal commit could not be isolated (`status: "inconclusive"`):

```yaml
failingTest: "tests/test_parser.py::test_parse_expression"
status: "inconclusive"
causalCommit: null
previousGoodCommit: null
changedFiles: []
diffSummary: ""
hypothesis: "The test failed on all 50 commits in the search window. The regression may predate the search window, or the test may have never passed in this branch."
commitsAnalyzed: 50
```

**WHEN** an error prevents analysis (`status: "error"`):

```yaml
failingTest: "tests/test_parser.py::test_parse_expression"
status: "error"
causalCommit: null
previousGoodCommit: null
changedFiles: []
diffSummary: ""
hypothesis: ""
commitsAnalyzed: 0
error:
  type: "TestPassesNow"
  message: "The specified test passes on HEAD. No regression to investigate."
```

### Field Definitions

| Field | Type | Description |
|-------|------|-------------|
| `failingTest` | string | The test identifier that was investigated |
| `status` | string | `"identified"` (causal commit found), `"inconclusive"` (could not isolate), or `"error"` (analysis could not proceed) |
| `causalCommit` | object or null | The commit that introduced the regression: `sha`, `author`, `date`, `message`. Null if not found. |
| `previousGoodCommit` | string or null | SHA of the last commit where the test passed. Null if not found. |
| `changedFiles` | array | Files changed in the causal commit. Empty array if not identified. |
| `diffSummary` | string | Summary of changes in the causal commit relevant to the failing test. |
| `hypothesis` | string | Explanation connecting the code change to the test failure. |
| `commitsAnalyzed` | number | Total commits examined during the search. |

---

## Error Handling

### TestNotFound

WHEN the specified test does not exist (sdd-test-run returns an error indicating the test file or test name was not found)
THEN return:

```yaml
status: "error"
error:
  type: "TestNotFound"
  message: "The specified test '$ARGUMENTS' does not exist or could not be located."
```

AND STOP. Do not proceed to historical analysis.

### TestPassesNow

WHEN the specified test passes on HEAD (INV-DREG-03)
THEN return:

```yaml
status: "error"
error:
  type: "TestPassesNow"
  message: "The specified test passes on HEAD. No regression to investigate."
```

AND STOP. Do not check out any historical commits.

### GitHistoryInsufficient

WHEN the repository has fewer than 2 commits
THEN return:

```yaml
status: "error"
error:
  type: "GitHistoryInsufficient"
  message: "Fewer than 2 commits in the repository. Cannot perform regression analysis."
```

AND execute Step 6 (restore working state) before stopping.

### StashConflict

WHEN uncommitted changes cannot be stashed (Step 2b stash failure)
THEN return:

```yaml
status: "error"
error:
  type: "StashConflict"
  message: "Uncommitted changes could not be stashed. Commit or stash your changes manually before running regression analysis."
```

AND STOP. Do not check out historical commits with a dirty working tree.

WHEN stash pop fails after analysis (Step 6b pop failure)
THEN include the `StashConflict` alongside the regression findings. The stash is preserved and the user can recover it manually.

---

## Dependency: sdd-test-run

This skill invokes `sdd-test-run` via the **Skill tool** for all test execution. It does NOT run test commands directly via Bash.

```
Skill tool: skill="sdd-test-run", args="<test-identifier>"
```

The sdd-test-run skill detects the project's test framework, runs the specified test, and returns structured results (`status`, `passed`, `failed`, `failureDetails`, etc.).

This skill calls sdd-test-run multiple times during analysis (once for HEAD confirmation, then once per commit during the search). Each invocation is a separate Skill tool call.

---

## Constraints

- This skill MUST NOT create, write, or modify any source file (INV-DREG-02). All git checkouts are temporary and reverted.
- This skill invokes sdd-test-run via the Skill tool. It does NOT use the Task tool.
- Every exit path after Step 2 MUST execute Step 6 (restore working state) before returning results (INV-DREG-01).
- Do not install dependencies or run package managers.
- Do not suggest fixes or generate fix code. This skill identifies the causal commit and generates a hypothesis; fixing is a separate concern.
- Do not prompt the user for input during analysis.
- When the search window is exhausted without finding a passing commit, report `status: "inconclusive"` rather than expanding the window indefinitely.
