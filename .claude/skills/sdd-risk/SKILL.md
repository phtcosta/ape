---
name: sdd-risk
description: "Assess technical risks in the project or a proposed change with severity ratings and evidence. Use WHEN evaluating risk before merging, planning sprints, or reviewing architecture. WHEN NOT: for code quality issues (use sdd-qa-lint)."
context: fork
argument-hint: "[scope|path]"
---

# Risk Assessment: $ARGUMENTS

Assess technical risks in the project or a proposed change. Identify complexity hotspots, dependency vulnerabilities, test coverage gaps, architectural risks, and process smells. Produce a structured risk report with severity ratings and evidence.

This skill is a Tier 3 support component. It runs as a forked subagent and is read-only.

**Invariants:**

- **INV-RISK-01:** This skill SHALL NOT modify any files. All analysis is read-only.
- **INV-RISK-02:** Every risk MUST include specific evidence (file names, metric values, or commit references). No risk SHALL be reported without data support.
- **INV-RISK-03:** The severity rating MUST follow the rubric defined in the Severity Rubric section.
- **INV-RISK-04:** Leaf node constraint. This skill SHALL NOT use the Skill tool or Task tool.

---

## Input

`$ARGUMENTS` contains the scope for analysis. It can be:

- **Empty or `.`** -- project-wide assessment across all categories
- **A file or directory path** (e.g., `src/parser.py`, `src/`) -- project-wide assessment scoped to that path
- **A change description** (e.g., `"adding Redis caching layer"`) -- change-specific assessment

---

## Scope Detection

Determine which assessment mode to use based on `$ARGUMENTS`.

### Step 1: Classify Input

**If `$ARGUMENTS` is empty, `.`, or the project root:**
- Set `scope: "project"`
- Proceed to Project-Wide Assessment

**If `$ARGUMENTS` is a valid file or directory path (check with Glob or Read):**
- Set `scope: "project"` (scoped to that path)
- Proceed to Project-Wide Assessment, restricting file searches to the given path

**If the path does not exist as a file or directory:**
- Treat `$ARGUMENTS` as a change description
- Set `scope: "change:<$ARGUMENTS>"`
- Proceed to Change-Specific Assessment

### Error: ScopeNotFound

WHEN `$ARGUMENTS` looks like a path (contains `/` or `.`) but does not exist as a file or directory
THEN return:
```
Error: ScopeNotFound
Message: "Target path '<path>' does not exist."
```
AND STOP

---

## Project-Wide Assessment

Analyze the project (or scoped path) across five risk categories. Use file-level heuristics -- line counts, pattern matching, git history -- not AST parsing.

For each category, collect findings and assign risk entries with IDs starting from RISK-001.

### Category 1: Complexity

Identify files that are excessively large or contain long functions.

**Detection steps:**

1. Use Glob to find source files under the target path:
   - `**/*.py`, `**/*.java`, `**/*.ts`, `**/*.tsx`, `**/*.js`, `**/*.jsx`
   - `**/*.go`, `**/*.rs`, `**/*.kt`, `**/*.kts`, `**/*.swift`
   - `**/*.c`, `**/*.cpp`, `**/*.h`, `**/*.hpp`, `**/*.cc`
   - `**/*.cs`, `**/*.rb`, `**/*.scala`
   - Exclude: `node_modules`, `.git`, `build`, `dist`, `target`, `__pycache__`, `vendor`, `.venv`, `venv`

2. For each source file, count total lines using Bash:
   ```bash
   wc -l <file>
   ```

3. Flag files exceeding **1000 lines** as complexity risks.

4. For flagged files, use Grep to estimate long functions. Search for function declarations and check if any span **more than 300 lines** by examining line numbers of consecutive function boundaries.

**Severity assignment:**
- Files >1500 lines or functions >500 lines: **high** (risk of incorrect behavior due to untestable code)
- Files >1000 lines or functions >300 lines: **medium** (maintainability concern)

**Evidence:** cite the file path and line count.

**Mitigation:** suggest decomposing the file or extracting functions.

### Category 2: Dependencies

Identify outdated or potentially risky dependencies by reading lock files and manifests.

**Detection steps:**

1. Use Glob to find dependency files:
   - `**/package-lock.json`, `**/package.json`
   - `**/poetry.lock`, `**/pyproject.toml`, `**/requirements.txt`
   - `**/go.sum`, `**/go.mod`
   - `**/Cargo.lock`, `**/Cargo.toml`
   - `**/Gemfile.lock`
   - `**/pom.xml`, `**/build.gradle`, `**/build.gradle.kts`
   - Limit search to the first match per type (do not descend into `node_modules`).

2. For lock files that contain timestamps or version metadata, check for dependencies not updated in **12+ months**:
   - For `package-lock.json`: read the file and look for `resolved` URLs with old date-based versions or check `modified` fields if present.
   - For `poetry.lock`: read and look for packages where the version is significantly behind the latest pattern.
   - For `go.sum`: read and check module versions against date-stamped pseudo-versions older than 12 months.

3. If lock file analysis is not conclusive, use git log to check when dependency files were last modified:
   ```bash
   git log -1 --format="%ai" -- <lock-file>
   ```
   Flag if the lock file has not been updated in 12+ months.

4. Count total direct dependencies from manifest files (e.g., `dependencies` and `devDependencies` in `package.json`). Flag projects with an unusually high dependency count (>100 direct dependencies).

**Severity assignment:**
- Dependencies with known patterns of security issues or lock file untouched for 18+ months: **high**
- Lock file untouched for 12-18 months or very high dependency count: **medium**
- Minor staleness (6-12 months): **low**

**Evidence:** cite the lock file path, last update date, and specific package names when possible.

**Mitigation:** suggest running dependency updates and auditing for vulnerabilities.

### Category 3: Coverage

Identify source modules that have no corresponding test files.

**Detection steps:**

1. Use Glob to find source files (same patterns as Category 1).

2. Use Glob to find test files:
   - `**/test_*.py`, `**/*_test.py`, `**/tests/**/*.py`
   - `**/*.test.ts`, `**/*.spec.ts`, `**/*.test.js`, `**/*.spec.js`, `**/*.test.tsx`, `**/*.spec.tsx`
   - `**/*_test.go`
   - `**/test/**/*.java`, `**/*Test.java`, `**/*Spec.java`
   - `**/*_test.rs`
   - `**/*_test.rb`, `**/spec/**/*.rb`
   - `**/*Test.kt`, `**/*Spec.kt`

3. For each source file, check if a corresponding test file exists. Match by:
   - Name convention: `src/parser.py` should have `tests/test_parser.py` or `test_parser.py`
   - Directory convention: `src/foo/bar.ts` should have `src/foo/bar.test.ts` or `__tests__/bar.test.ts`

4. Flag source files with no corresponding test file.

5. Cap at reporting **20 untested modules**. If more exist, note the total count and list only the largest files.

**Severity assignment:**
- Untested files that are in critical paths (names containing `auth`, `security`, `payment`, `data`, `persist`, `migrate`, `core`): **high**
- Other untested files >200 lines: **medium**
- Small untested utility files (<200 lines): **low**

**Evidence:** cite the source file path and note that no matching test file was found.

**Mitigation:** suggest adding tests (e.g., "Add test coverage for `<file>`").

### Category 4: Architecture

Identify files with high fan-in (imported by many other files) that also change frequently.

**Detection steps:**

1. Use Grep to count how many files import each source file. For each source file, search for its module name or relative path in import statements across the codebase:
   ```
   Grep for the filename stem (e.g., "parser") in import patterns across source files.
   ```
   Count the number of distinct files that import it. This is the **fan-in** count.

2. Use git log to measure **churn** (number of commits that changed each file in the last 6 months):
   ```bash
   git log --since="6 months ago" --name-only --pretty=format: -- <path> | sort | uniq -c | sort -rn | head -20
   ```

3. Flag files where fan-in >= 5 AND churn >= 10 commits in 6 months. These are high-risk architectural hotspots: many dependents and frequent changes.

4. Also check for **circular imports** by sampling the top 10 highest fan-in files:
   - Read each file's imports
   - Check if any imported file also imports the original file
   - Flag circular pairs found

**Severity assignment:**
- Circular dependencies: **medium** (maintainability concern)
- High fan-in (>=10) with high churn (>=15 commits): **high** (risk of breaking dependents)
- High fan-in (>=5) with moderate churn (>=10 commits): **medium**

**Evidence:** cite the file path, fan-in count, churn count, and any circular dependency pairs.

**Mitigation:** suggest stabilizing the interface, extracting a stable API, or breaking circular dependencies.

### Category 5: Process

Identify process smells from git history.

**Detection steps:**

1. **High churn without test changes:** Compare source file churn to test file churn over the last 3 months:
   ```bash
   git log --since="3 months ago" --name-only --pretty=format: | sort | uniq -c | sort -rn | head -30
   ```
   For the top 10 highest-churn source files, check if their corresponding test files also appear in the churn list. Flag source files changed 5+ times with zero or minimal test file changes.

2. **Large commits without description:** Check recent commits for large changesets with minimal commit messages:
   ```bash
   git log --since="3 months ago" --pretty=format:"%H %s" --shortstat | head -60
   ```
   Flag commits that change 10+ files with commit messages shorter than 10 characters.

**Severity assignment:**
- Source files changed 10+ times with no test changes: **medium**
- Source files changed 5-9 times with no test changes: **low**
- Large undescribed commits: **low**

**Evidence:** cite the file paths, change counts, and commit SHAs.

**Mitigation:** suggest adding test coverage for high-churn files and improving commit practices.

---

## Change-Specific Assessment

When `$ARGUMENTS` describes a change rather than a path, assess risks introduced by that change.

### Step 1: Identify Changed Files

Attempt to find changed files relevant to the described change:

1. Check for uncommitted changes:
   ```bash
   git diff --name-only
   git diff --cached --name-only
   ```

2. Check recent commits for changes matching the description:
   ```bash
   git log --oneline -20
   ```

3. If a branch comparison is relevant:
   ```bash
   git diff --name-only main...HEAD
   ```

If no changed files can be identified, report this limitation and assess based on the description alone.

### Step 2: Analyze Change Risks

For each changed file, evaluate:

**New dependencies:**
- Check if any new import statements reference modules not previously used in the project.
- Use Grep to search for the imported module name in other project files. If it appears nowhere else, it is a new dependency.
- Severity: **medium** for new external dependencies, **low** for new internal dependencies.

**Complexity delta:**
- If a file grew significantly (use `git diff --stat`), flag the growth.
- Files that crossed the 1000-line threshold: **medium**.
- Files that grew by more than 50% of their original size: **medium**.

**Test coverage for changed code:**
- For each changed source file, check if a corresponding test file was also changed.
- Flag source files changed without corresponding test changes.
- Severity: **medium** for files in critical paths, **low** otherwise.

**API surface changes:**
- Check if changed files export public functions, classes, or interfaces that other files import.
- Use Grep to find consumers of the changed file. If consumers exist and the file's exports changed, flag as a risk.
- Severity: **high** if many consumers (>=5), **medium** if few consumers (1-4).

---

## Severity Rubric

Apply this rubric consistently across all categories (INV-RISK-03):

| Severity | Criteria | Examples |
|----------|----------|----------|
| **Critical** | Risk of data loss, system outage, or security vulnerability | Untested data persistence code, known vulnerable dependency, no error handling in critical path |
| **High** | Risk of incorrect behavior or broken functionality | High fan-in file with high churn, untested critical-path module, API change with many consumers |
| **Medium** | Risk of reduced maintainability or developer productivity | Large files, circular dependencies, stale dependencies, missing tests for non-critical code |
| **Low** | Informational -- worth noting but not actionable immediately | Minor code smells, small untested utilities, slightly stale dependencies |

---

## Output Format

Return the following structured report (INV-RISK-02: every risk includes evidence).

```markdown
# Risk Assessment Report

**Scope**: <"project" or "change:<description>">
**Target**: <path or change description>
**Timestamp**: <ISO 8601>

## Risks

### RISK-001: <title>
- **Category**: <complexity|dependency|coverage|architecture|process>
- **Severity**: <critical|high|medium|low>
- **Description**: <what the risk is>
- **Evidence**: <specific file names, metric values, commit SHAs>
- **Mitigation**: <suggested remediation>

### RISK-002: <title>
...

## Summary

| Severity | Count |
|----------|-------|
| Critical | X |
| High | Y |
| Medium | Z |
| Low | W |

| Category | Count |
|----------|-------|
| Complexity | A |
| Dependency | B |
| Coverage | C |
| Architecture | D |
| Process | E |

## Top Risks

The top 3-5 risks requiring attention:

1. **RISK-NNN**: <one-line summary with severity>
2. **RISK-NNN**: <one-line summary with severity>
3. **RISK-NNN**: <one-line summary with severity>

## Observations

<One paragraph summarizing patterns across the findings. Note which areas of the codebase carry the most risk and whether risks are concentrated in specific categories or spread across multiple.>
```

---

## Constraints

- This skill is read-only. It SHALL NOT create, write, or modify any file (INV-RISK-01).
- This skill is a leaf node. It SHALL NOT invoke the Skill tool or Task tool (INV-RISK-04).
- Do not prompt the user for input. Run non-interactively.
- Do not install dependencies or run package managers.
- When the project has many source files (100+), prioritize analysis of the largest files and highest-churn files. Cap detailed analysis at 100 files per category. Note any cap in the report.
- When more than 30 risks are found, include only risks with severity medium or above in the full listing. Report the total count of low-severity risks in the summary.
- Use only Bash (for git commands and `wc`), Read, Grep, and Glob tools.
