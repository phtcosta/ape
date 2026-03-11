---
name: sdd-analyze-dead-code
description: "Dead code detection: identify unreferenced classes, methods, functions, imports, and variables with confidence levels."
context: fork
user-invocable: false
---

# Dead Code Analysis: $ARGUMENTS

> **Scope**: Analyze a directory or file path for unused code (unreferenced symbols).
> Language-agnostic: uses Grep-based reference counting that works for any language.
> Do NOT use for: removing code (this skill is read-only), single file structure (use `sdd-analyze-file`).

## Invariants

- **INV-DC-01**: Output format is identical regardless of operating mode (Full, Lite, Minimal).
- **INV-DC-02**: Strictly read-only. SHALL NOT delete, modify, or create any source file.
- **INV-DC-03**: Leaf node. SHALL NOT invoke the Skill tool or Task tool.
- **INV-DC-04**: When `.sdd/sdd-config.yaml` is missing or has no mode field, default to Minimal mode.
- **INV-DC-05**: Entry points and framework lifecycle methods SHALL be excluded from dead code candidates.

## Steps

### Step 1: Validate Target Path

1. Parse `$ARGUMENTS` to extract the target path (first argument).
2. Use Glob to verify the path exists:
   - If it is a file: scope analysis to that single file.
   - If it is a directory: scope analysis to all source files under that directory.
   - If the path does not exist: output a `PathNotFound` error and STOP.

### Step 2: Detect Operating Mode

Read `.sdd/sdd-config.yaml` using the Read tool (path relative to repository root).

- If the file does not exist: mode = `minimal` (INV-DC-04).
- If the file exists: look for `sdd.infrastructure.mode`.
  - `full`, `lite`, or `minimal`: use that value.
  - Absent or unrecognized: mode = `minimal` (INV-DC-04).

Also extract `sdd.project.language` if present; store as `<language>` (used in Step 3).

### Step 3: Load Plugin Exclusions (INV-DC-05)

Build the exclusion list for entry points and framework lifecycle methods.

**Universal entry points** (always excluded):
- `main`
- `__main__`
- `public static void main`

**Plugin-specific exclusions**:

WHEN `<language>` is non-null (extracted from `.sdd/sdd-config.yaml` in Step 2)
THEN read `plugins/<language>/config.json` using the Read tool
AND extract the `analysis` section if present:
- `analysis.entryPoints`: add each value to the entry point exclusion list.
- `analysis.frameworkLifecycle`: add each value to the lifecycle exclusion list. Methods annotated with any of these decorators/annotations are excluded from dead code candidates.

WHEN `<language>` is null or the plugin file does not exist or has no `analysis` field
THEN use only the universal entry points listed above.

**Test file exclusion patterns** (files matching these are excluded from dead code candidates, but still scanned as reference sources):
- `test_*`
- `*_test.*`
- `*Test.*`
- `*Spec.*`
- Files under directories named `test/`, `tests/`, `__tests__/`, `spec/`

### Step 4: Discover Source Files

Use Glob to find all source files within the target scope.

WHEN the target is a directory
THEN use the plugin's `source.patterns` (from `plugins/<language>/config.json`) if available
AND exclude paths matching the plugin's `source.exclude` patterns if available
AND if no plugin is available, use a broad glob: `**/*.{java,py,ts,tsx,js,jsx,kt,go,rs,rb,c,cpp,h,hpp,cs,swift,scala}`

WHEN the target is a single file
THEN use only that file.

If no source files are found: output a `NoSourceFiles` error and STOP.

### Step 5: Mode-Specific Analysis

Proceed to the step matching the detected mode.

#### Step 5A: Full Mode (Neo4j Graph)

##### Graph Validation (Full mode only)

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

1. **Query for isolated components**:
   Call `mcp__sdd__query_cypher` to find components with zero incoming dependency edges. A component is a dead code candidate when no other component has an IMPORTS, USES, EXTENDS, or IMPLEMENTS edge pointing to it.

2. **Apply exclusions** (INV-DC-05):
   Remove from the candidate list any symbol that matches an entry point, framework lifecycle method, or is defined in a test file.

3. **Assign confidence** (see Step 6).

4. If the MCP call fails or returns an error: log "SDD MCP server unavailable. Falling back to Minimal mode." and proceed to Step 5C.

#### Step 5B: Lite Mode (MCP Memory Cache)

1. **Get current file hash**:
   ```bash
   sha256sum <target_path> | cut -d' ' -f1
   ```
   If `sha256sum` is not available, fall back to `shasum -a 256 <target_path> | cut -d' ' -f1`.
   Store as `<file_hash>`.

2. **Check cache**:
   Call `mcp__memory__search_nodes` with query: `sdd-analyze-dead-code:<target_path>:<file_hash>`

   - **Cache hit** (entity found with matching hash): extract the `report` observation. Output it directly and STOP.
   - **Cache miss**: proceed to Step 5C (Minimal analysis).

3. **After Minimal analysis completes**: cache the result.
   Call `mcp__memory__create_entities` with:
   ```
   Entity name: "sdd-analyze-dead-code:<target_path>:<file_hash>"
   Entity type: "sdd-analysis-cache"
   Observations:
     - "target: <target_path>"
     - "file_hash: <file_hash>"
     - "timestamp: <ISO 8601>"
     - "report: <full markdown report>"
   ```
   If MCP Memory is unavailable, skip caching and output the report.

#### Step 5C: Minimal Mode (Grep-Based Reference Counting)

This is the core analysis logic. It uses only Read, Glob, and Grep tools.

1. **Extract exported symbols from each source file**:

   For each source file found in Step 4, read its content using Read and extract:
   - **Classes/structs/interfaces**: top-level type declarations.
   - **Functions/methods**: public or exported function and method names. For languages with visibility modifiers, include only public/exported symbols. For languages without explicit visibility (Python, JS), include all top-level definitions.
   - **Constants/enums**: exported constant names.

   Record for each symbol: `name`, `type` (class/function/method/constant/import), `file`, `line number`.

2. **Count references across the project**:

   For each extracted symbol, use Grep to search for the symbol name across all source files in the project scope:

   ```
   Grep: pattern="\b<symbol_name>\b", path=<project_root>, output_mode="count"
   ```

   To reduce tool calls, batch symbols where possible: process symbols from the same file together, and skip symbols shorter than 3 characters (too many false positives).

3. **Determine dead code candidates**:

   A symbol is a dead code candidate when:
   - It has zero references outside its defining file, OR
   - Its only references are in the defining file (self-references like recursive calls or class-internal usage).

   To check this: compare the total reference count with references found only in the defining file. If they are equal, the symbol has no external references.

4. **Apply exclusions** (INV-DC-05):
   Remove from candidates any symbol matching an entry point, framework lifecycle annotation/decorator, or defined in a test file (per the lists built in Step 3).

5. **Assign confidence** (see Step 6).

### Step 6: Confidence Assignment

For each dead code candidate, assign a confidence level:

**high**:
- Zero references outside the defining file.
- Not an entry point (INV-DC-05).
- Not annotated with a framework lifecycle decorator/annotation.
- Not in a test file.

**medium**:
- Referenced only from test files (test-only usage).
- Exactly one external reference (single caller — may indicate near-dead code).
- Symbol appears in a string literal or comment (possible dynamic dispatch).

**low**:
- Two or three external references but all from closely related files (potential coupling).
- Symbol name matches a common interface method or callback pattern.
- Language uses dynamic dispatch heavily (e.g., Python `getattr`, JavaScript bracket notation) and the symbol could be invoked dynamically.

Record the `reason` field for each candidate explaining why it was flagged and the confidence rationale.

### Step 7: Build Exclusions Table

For each symbol excluded via INV-DC-05, record it in the exclusions table with the exclusion reason:
- "Entry point" for universal or plugin-defined entry points.
- "Framework lifecycle" for lifecycle annotations/decorators.
- "Test fixture" for symbols in test files.

### Step 8: Format Output

Format the report using the structure below. This format is mandatory for all modes (INV-DC-01).

```markdown
# Dead Code Analysis Report

**Target**: <absolute path>
**Mode**: <full|lite|minimal>
**Timestamp**: <ISO 8601>

## Candidates

| # | Type | Name | File | Line | Confidence | Reason | Refs Found |
|---|------|------|------|------|------------|--------|------------|
| 1 | Function | unused_helper | src/utils.py | 42 | high | Zero external references | 0 |
| 2 | Class | OldParser | src/parser.py | 15 | medium | Test-only usage | 1 (test) |

## Exclusions

| Symbol | File | Reason |
|--------|------|--------|
| main | src/App.java | Entry point |
| onCreate | src/Activity.java | Framework lifecycle |

## Summary

Found X dead code candidates: Y high confidence, Z medium, W low.
(One paragraph describing the scope analyzed, methodology used, and key findings. Mention mode used and any fallbacks that occurred.)
```

WHEN there are zero candidates
THEN the Candidates table SHALL contain a single row: `| - | - | - | - | - | - | No dead code candidates found | - |`
AND the Summary SHALL state: "Found 0 dead code candidates. All exported symbols have external references."

## Error Handling

| Condition | Error | Behavior |
|-----------|-------|----------|
| Target path does not exist | `PathNotFound` | Output: "Error: Path not found: `<path>`. Verify the path and try again." STOP. |
| No analyzable source files found | `NoSourceFiles` | Output: "Error: No source files found in `<path>`. Verify the path contains source code." STOP. |
| Config file missing or invalid | `ConfigMissing` | Default to Minimal mode (INV-DC-04). Proceed. |
| MCP server unavailable (Full mode) | - | Log fallback reason. Run Minimal mode analysis. |
| MCP Memory unavailable (Lite mode) | - | Skip cache read/write. Run Minimal mode analysis. |

## Constraints

- This skill receives a target path as `$ARGUMENTS`.
- This skill SHALL NOT modify, delete, or create any file (INV-DC-02).
- This skill SHALL NOT invoke the Skill tool or Task tool (INV-DC-03).
- This skill operates within a forked context (`context: fork`).
- Analysis uses reference counting only. No code execution, no test running, no linting tool invocation.
- Symbols shorter than 3 characters are skipped to avoid false positives from common variable names.
