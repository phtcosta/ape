---
name: sdd-analyze-complexity
description: "Complexity hotspot identification: cyclomatic complexity, nesting depth, method length, coupling."
context: fork
user-invocable: false
---

# Analyze Complexity: $ARGUMENTS

Identify complexity hotspots in the target path by measuring cyclomatic complexity, nesting depth, method/function length, and coupling. Produce a ranked report with severity classifications.

This skill is a Tier 2 analysis component. It runs as a forked subagent, is read-only, and is called by orchestrator skills, never directly by users.

**Invariants:**

- **INV-AC-01:** Output format SHALL be identical regardless of operating mode (Full, Lite, or Minimal).
- **INV-AC-02:** This skill is read-only. It SHALL NOT create, modify, or delete any file.
- **INV-AC-03:** Leaf node constraint. This skill SHALL NOT use the Skill tool or Task tool.
- **INV-AC-04:** When `.sdd/sdd-config.yaml` is missing or unreadable, default to Minimal mode.
- **INV-AC-05:** Hotspots list MUST be sorted descending by complexity score.

---

## Input

`$ARGUMENTS` contains the target path to analyze. This can be a single file, a directory, or a project root.

Examples:
- `src/parser.py` -- analyze a single file
- `src/` -- analyze all source files in a directory
- `.` -- analyze the entire project from the current directory
- `(empty)` -- defaults to the current working directory

---

## Mode Detection

Determine the operating mode before running analysis. This drives how data is collected.

### Step 1: Read Config

Read `.sdd/sdd-config.yaml` from the project root using the Read tool.

**If the file does not exist** or cannot be parsed:
- Set `mode: "minimal"` (INV-AC-04).
- Proceed to Minimal Mode.

**If the file exists**, extract `sdd.infrastructure.mode`:

| Value | Action |
|-------|--------|
| `"full"` | Proceed to Full Mode |
| `"lite"` | Proceed to Lite Mode |
| `"minimal"` | Proceed to Minimal Mode |
| Missing or unrecognized | Set `mode: "minimal"` (INV-AC-04), proceed to Minimal Mode |

---

## Full Mode

Query the MCP server for pre-computed complexity data.

### Graph Validation (Full mode only)

Before querying Neo4j, validate the graph state:

1. **Check graph population**:
   - Run: `mcp__sdd__query_cypher("MATCH (p:Project) RETURN count(p) AS cnt")`
   - If `cnt == 0`:
     - **WARN**: "Full mode is configured but the Neo4j graph is empty. Run `/sdd-extract` to populate it. Falling back to Minimal mode."
     - Skip all Neo4j queries and proceed with Minimal mode behavior instead.

2. **Check staleness** (only if graph is populated):
   - Get extraction timestamp: `mcp__sdd__query_cypher("MATCH (p:Project) RETURN p.extracted_at AS ts ORDER BY ts DESC LIMIT 1")`
   - Get latest commit: `git log -1 --format=%cI` (via Bash tool)
   - If commit timestamp > extracted_at:
     - **WARN**: "Neo4j graph may be stale (extracted: <ts>, latest commit: <commit_ts>). Consider running `/sdd-extract` to refresh."
     - Proceed with Full mode queries (stale data is better than no data).

### Step 1: Query MCP

```
Use mcp__sdd__query_cypher with a Cypher query to retrieve components
under the target path with complexity metrics:
- cyclomatic complexity
- nesting depth
- line count
- coupling (import count)
```

Example query structure:
```
MATCH (c:Component)
WHERE c.path STARTS WITH '<target_path>'
RETURN c.name, c.path, c.line, c.cyclomatic_complexity, c.nesting_depth, c.line_count, c.coupling
ORDER BY c.cyclomatic_complexity DESC
```

### Step 2: Handle MCP Failure

WHEN the MCP server is unavailable or returns an error
THEN fall back to Minimal Mode
AND note the fallback in the report summary

### Step 3: Build Report

If MCP returned data, compute severity for each component using the thresholds defined below (Severity Thresholds section). Sort by complexity score descending (INV-AC-05). Proceed to Output Format.

---

## Lite Mode

Use MCP Memory for caching to avoid redundant analysis.

### Step 1: Get File Hash

```bash
sha256sum <target_path> | cut -d' ' -f1
```
If `sha256sum` is not available, fall back to `shasum -a 256 <target_path> | cut -d' ' -f1`.
Capture the hash as `<file_hash>`.

### Step 2: Check Cache

```
Use mcp__memory__search_nodes with query: "sdd-analyze-complexity:<target_path>:<file_hash>"
```

**Cache hit** (entity found with matching file hash):
- Extract the cached analysis result from the entity's observations.
- Return the cached result. STOP.

**Cache miss** (entity not found or hash differs):
- Proceed to Step 3.

### Step 3: Run Minimal Analysis

Execute the Minimal Mode analysis (below). After obtaining results, cache them.

### Step 4: Cache Results

```
Use mcp__memory__create_entities:
  Entity name: "sdd-analyze-complexity:<target_path>:<file_hash>"
  Entity type: "sdd-analysis-cache"
  Observations:
    - "target: <target_path>"
    - "file_hash: <file_hash>"
    - "timestamp: <ISO 8601>"
    - "report: <full analysis report>"
```

If caching fails, still return the report. Caching failure is not a user-facing error.

---

## Minimal Mode

Read source files directly and estimate complexity metrics from code structure.

### Step 1: Locate Source Files

Determine if `$ARGUMENTS` is a file or directory.

**If a file**: analyze that single file.

**If a directory**: use Glob to find source files. Include common source extensions:
- `**/*.py`, `**/*.java`, `**/*.ts`, `**/*.tsx`, `**/*.js`, `**/*.jsx`
- `**/*.go`, `**/*.rs`, `**/*.kt`, `**/*.kts`, `**/*.swift`
- `**/*.c`, `**/*.cpp`, `**/*.h`, `**/*.hpp`, `**/*.cc`
- `**/*.cs`, `**/*.rb`, `**/*.scala`

Exclude non-source directories: `node_modules`, `.git`, `build`, `dist`, `target`, `__pycache__`, `vendor`, `.venv`, `venv`.

WHEN the target path does not exist
THEN return a `PathNotFound` error and STOP

WHEN no source files are found
THEN return a `NoSourceFiles` error and STOP

### Step 2: Analyze Each File

For each source file, read its contents using the Read tool and compute the following metrics for each method/function found in the file.

#### Identifying Methods/Functions

Detect function and method boundaries by looking for language-appropriate patterns:
- **Python**: lines matching `def <name>(` or `async def <name>(`
- **Java/Kotlin/C#/Scala**: lines with access modifiers followed by return type and name, or `fun` keyword
- **TypeScript/JavaScript**: `function <name>(`, `<name> = (`, `<name>(` inside class bodies, arrow functions assigned to variables
- **Go**: `func <name>(` or `func (<receiver>) <name>(`
- **Rust**: `fn <name>(`
- **Ruby**: `def <name>`
- **C/C++**: return type followed by name and `(`
- **Swift**: `func <name>(`

For each identified function/method, analyze the code from its declaration to its closing boundary.

#### Metric 1: Cyclomatic Complexity (Estimated)

Count decision points within each function body. Each of the following adds 1 to the base complexity of 1:

| Token/Pattern | Contributes |
|---------------|-------------|
| `if` | +1 |
| `elif` / `else if` | +1 |
| `else` | +1 |
| `for` | +1 |
| `while` | +1 |
| `try` | +1 |
| `except` / `catch` | +1 |
| `switch` | +1 |
| `case` (each case label) | +1 |
| `and` / `&&` | +1 |
| `or` / `\|\|` | +1 |
| `?` (ternary operator) | +1 |

Count only keyword/operator occurrences that appear in code context (not inside string literals or comments). When in doubt about string/comment boundaries, count the occurrence — overestimation is acceptable for hotspot identification.

The cyclomatic complexity for a function = 1 + (count of decision points).

#### Metric 2: Nesting Depth

Measure the maximum nesting depth within each function.

**For indentation-based languages** (Python, Ruby):
- Determine the base indentation of the function body.
- Find the maximum indentation level relative to the base.
- Each indentation step (typically 4 spaces or 1 tab) counts as one nesting level.

**For brace-based languages** (Java, TypeScript, Go, Rust, C/C++, C#, Kotlin, Swift, Scala):
- Count the maximum depth of nested `{` braces within the function body.
- Each opening `{` that is not immediately closed on the same line increases depth.

#### Metric 3: Lines per Function

Count the number of lines from the function declaration to its closing boundary (inclusive). Blank lines and comment-only lines are included in the count.

#### Metric 4: Coupling (Imports per File)

Count the number of distinct import/include statements at the file level (not per function):

| Language | Pattern |
|----------|---------|
| Python | `import <module>`, `from <module> import` |
| Java/Kotlin/Scala | `import <package>` |
| TypeScript/JavaScript | `import ... from`, `require(...)` |
| Go | entries inside `import (...)` block |
| Rust | `use <path>` |
| C/C++ | `#include` |
| C# | `using <namespace>` |
| Ruby | `require`, `require_relative` |
| Swift | `import <module>` |

Count each distinct import statement once. Group imports (e.g., Go's `import (...)` block) count each line inside the block as one import.

### Step 3: Compute Complexity Score

For each function/method, compute a composite complexity score:

```
complexity_score = cyclomatic_complexity + (nesting_depth * 2) + (lines / 10)
```

This formula weights cyclomatic complexity as the primary factor, penalizes deep nesting (multiplied by 2), and accounts for function length (divided by 10 to normalize).

### Step 4: Classify Severity

Apply the hardcoded severity thresholds to each component's complexity score:

| Severity | Score Range |
|----------|-------------|
| Critical | > 40 |
| High | 21-40 |
| Medium | 11-20 |
| Low | <= 10 |

### Step 5: Sort and Rank

Sort all analyzed functions/methods by `complexity_score` descending (INV-AC-05). Assign rank numbers starting from 1.

---

## Severity Thresholds

These thresholds are hardcoded (D5). They apply in all operating modes.

| Severity | Score Range | Interpretation |
|----------|-------------|----------------|
| Critical | > 40 | Function requires immediate refactoring; likely contains multiple intertwined concerns |
| High | 21-40 | Function is difficult to test and maintain; refactoring recommended |
| Medium | 11-20 | Function has moderate complexity; monitor for growth |
| Low | <= 10 | Function has acceptable complexity |

---

## Error Handling

### PathNotFound

WHEN the target path specified in `$ARGUMENTS` does not exist
THEN return:
```
Error: PathNotFound
Message: "Target path '<path>' does not exist."
```
AND STOP

### NoSourceFiles

WHEN the target path exists but contains no recognizable source files
THEN return:
```
Error: NoSourceFiles
Message: "No analyzable source files found in '<path>'."
```
AND STOP

### ConfigMissing

WHEN `.sdd/sdd-config.yaml` does not exist or is unreadable
THEN default to Minimal mode (INV-AC-04)
AND proceed with analysis (this is not a fatal error)

---

## Output Format

Return the following report (INV-AC-01: same format regardless of mode).

```markdown
# Complexity Analysis Report

**Target**: <path>
**Mode**: <full|lite|minimal>
**Scope**: <file|directory|project>
**Timestamp**: <ISO 8601>

## Hotspots

| # | Component | File | Line | Complexity | Nesting | Lines | Coupling | Severity |
|---|-----------|------|------|-----------|---------|-------|----------|----------|
| 1 | function_name | path/to/file.ext | 42 | 35 | 6 | 120 | 15 | High |
| 2 | another_func | path/to/other.ext | 10 | 22 | 4 | 85 | 8 | High |
| ... | ... | ... | ... | ... | ... | ... | ... | ... |

## Thresholds

| Severity | Score Range |
|----------|-------------|
| Critical | > 40 |
| High | 21-40 |
| Medium | 11-20 |
| Low | <= 10 |

## Metrics Summary

- Total components analyzed: X
- Hotspots found: Y (Z critical, W high)

## Summary

<One paragraph highlighting the top hotspots by name and severity, with brief observations about patterns (e.g., "complexity concentrated in parsing module" or "deep nesting in error handling paths"). If no hotspots above Medium, state that the codebase has acceptable complexity.>
```

### Field Definitions

| Column | Description |
|--------|-------------|
| # | Rank by complexity score, descending (INV-AC-05) |
| Component | Function or method name |
| File | Relative path to the source file |
| Line | Line number where the function/method starts |
| Complexity | Estimated cyclomatic complexity |
| Nesting | Maximum nesting depth within the function |
| Lines | Total lines in the function |
| Coupling | Number of distinct imports in the containing file |
| Severity | Classification based on composite complexity score |

### Scope Values

| Value | Condition |
|-------|-----------|
| `file` | `$ARGUMENTS` is a single file |
| `directory` | `$ARGUMENTS` is a directory (not the project root) |
| `project` | `$ARGUMENTS` is `.` or empty or the project root |

---

## Constraints

- This skill is read-only. It SHALL NOT create, write, or modify any file (INV-AC-02).
- This skill is a leaf node. It SHALL NOT invoke the Skill tool or Task tool (INV-AC-03).
- Do not prompt the user for input. This skill runs non-interactively as a subroutine for orchestrator skills.
- Do not install dependencies or run package managers.
- When analyzing large directories (50+ files), prioritize files with higher apparent complexity (larger files first) and cap analysis at 100 files. Note the cap in the summary if applied.
- The hotspots table includes all analyzed functions, sorted descending by complexity score. When the table would exceed 50 rows, include only functions with severity Medium or above, and note the filter in the summary.
