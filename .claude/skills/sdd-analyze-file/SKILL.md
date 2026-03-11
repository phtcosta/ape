---
name: sdd-analyze-file
description: "Single file structural analysis with mode-aware behavior (Full/Lite/Minimal)."
context: fork
user-invocable: false
---

# Analyze File: $ARGUMENTS

> **Scope**: Structural analysis of ONE source file — structure, complexity, dependencies, concerns.
> Language-agnostic: detects language from file extension.
> Do NOT use for: multiple files (use `sdd-analyze-module`), making changes, running tests.

## Invariants

- **INV-AF-01**: Output format is identical regardless of operating mode (Full, Lite, Minimal).
- **INV-AF-02**: Strictly read-only. SHALL NOT modify any file.
- **INV-AF-03**: Leaf node. SHALL NOT invoke the Skill tool or Task tool.
- **INV-AF-04**: When `.sdd/sdd-config.yaml` is missing or has no mode field, default to Minimal mode.

## Steps

### Step 1: Validate Target File

1. Parse `$ARGUMENTS` to extract the file path (first argument).
2. Read the file via the Read tool.
   - If the file does not exist: output an error message and STOP. No partial analysis.
   - If the file is binary (contains null bytes or is a known binary extension like `.class`, `.jar`, `.o`, `.so`, `.pyc`, `.exe`, `.dll`, `.png`, `.jpg`, `.zip`, `.gz`): output an error message and STOP.
3. Record the file content for analysis.

### Step 2: Detect Language

Determine language from the file extension:

| Extension | Language |
|-----------|----------|
| `.java` | Java |
| `.py` | Python |
| `.ts`, `.tsx` | TypeScript |
| `.js`, `.jsx` | JavaScript |
| `.kt`, `.kts` | Kotlin |
| `.go` | Go |
| `.rs` | Rust |
| `.rb` | Ruby |
| `.c`, `.h` | C |
| `.cpp`, `.hpp`, `.cc` | C++ |
| `.cs` | C# |
| `.swift` | Swift |
| `.scala` | Scala |
| `.cbl`, `.cob` | COBOL |
| `.sh`, `.bash` | Shell |
| `.yaml`, `.yml` | YAML |
| `.xml` | XML |
| `.json` | JSON |
| `.md` | Markdown |

If the extension is not in the table, use `Unknown` and still proceed with best-effort analysis.

### Step 3: Detect Operating Mode

Read `.sdd/sdd-config.yaml` using the Read tool (path relative to repository root).

- If the file does not exist: mode = `minimal` (INV-AF-04).
- If the file exists: look for the field `sdd.infrastructure.mode`.
  - If the field is present and its value is `full`, `lite`, or `minimal`: use that value.
  - If the field is absent or has an unrecognized value: mode = `minimal` (INV-AF-04).

Proceed to the step matching the detected mode.

### Step 4A: Full Mode Analysis

Full mode uses the SDD MCP server for structural data.

#### Graph Validation (Full mode only)

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

1. **Query component data**:
   Call `mcp__sdd__get_component` with the file path as the component identifier.
   - If the MCP call fails or returns empty: fall back to Minimal mode (Step 4C). Log the fallback reason.

2. **Query dependency data**:
   Call `mcp__sdd__get_dependencies` with the file path.
   - If the MCP call fails: continue without dependency graph data; extract dependencies from imports in Step 4C instead.

3. **Enrich with file content**:
   The MCP graph may not capture everything (inline comments, magic values, naming patterns). Use the file content already read in Step 1 to fill gaps:
   - Count lines, methods, nesting depth.
   - Identify concerns not tracked in the graph (long methods, deep nesting, naming issues).

4. Proceed to Step 5 (Output).

### Step 4B: Lite Mode Analysis (MCP Memory Cache)

Lite mode caches analysis results in MCP Memory, keyed by file path and file content hash.

1. **Get current file hash**:
   ```bash
   sha256sum $ARGUMENTS | cut -d' ' -f1
   ```
   If `sha256sum` is not available, fall back to `shasum -a 256 $ARGUMENTS | cut -d' ' -f1`.
   Store the result as `<file_hash>`.

2. **Check cache**:
   Call `mcp__memory__search_nodes` with query: `sdd-analyze-file:$ARGUMENTS:<file_hash>`

   - **Cache hit** (entity found with matching hash): Extract the `report` observation. Output it directly and STOP.
   - **Cache miss** (no entity or hash mismatch): Continue to step 3.

3. **Perform Minimal mode analysis**:
   Execute the same logic as Step 4C below to produce the analysis report.

4. **Cache the result**:
   Call `mcp__memory__create_entities` with:
   ```
   Entity name: "sdd-analyze-file:$ARGUMENTS:<file_hash>"
   Entity type: "sdd-analysis-cache"
   Observations:
     - "target: $ARGUMENTS"
     - "file_hash: <file_hash>"
     - "language: <detected_language>"
     - "timestamp: <ISO 8601>"
     - "report: <full markdown report>"
   ```
   If MCP Memory is unavailable, skip caching and output the report.

5. Proceed to Step 5 (Output).

### Step 4C: Minimal Mode Analysis

Minimal mode performs structural analysis using only the file content read in Step 1. No external services required.

Analyze the file through these dimensions:

#### 4C.1: Structure Extraction

Identify and list:
- **Imports/includes**: All import, require, include, use, using statements. Group by category:
  - Standard library (language built-ins)
  - External (third-party packages/libraries)
  - Internal (project-local imports)
- **Classes/structs/interfaces**: Name, line range, visibility (public/private/protected if applicable).
- **Methods/functions**: Name, line range, parameter count, visibility.
  - For languages with standalone functions (Python, Go, JS), list at module level.
  - For class-based languages (Java, C#), list under their class.
- **Constants/enums**: Name, value (if short).
- **Module-level code**: Any executable statements not inside a function or class.

#### 4C.2: Complexity Estimation

Calculate:
- **Total lines**: Count of non-empty lines.
- **Max method/function length**: Longest method in lines.
- **Max nesting depth**: Deepest level of nested blocks (if/for/while/try/match/switch). Count by indentation change or brace depth.
- **Method count**: Total methods/functions in the file.
- **Parameter counts**: Flag methods with more than 5 parameters.

Thresholds:
| Metric | Threshold | Status when exceeded |
|--------|-----------|---------------------|
| Total lines | 500 | Warning |
| Max method length | 50 lines | Warning |
| Max nesting depth | 4 levels | Warning |
| Parameter count | 5 params | Warning |

#### 4C.3: Dependency Identification

From the imports extracted in 4C.1, group dependencies:
- **Standard library**: Language-provided modules.
- **External**: Third-party packages.
- **Internal**: Project modules (relative imports, same-package imports).

Do NOT perform reverse dependency lookup (what imports this file) in Minimal mode.

#### 4C.4: Concern Flagging

Flag the following concerns when detected:
- **Long methods**: Any method exceeding 50 lines.
- **Deep nesting**: Any block exceeding 4 levels of nesting.
- **High parameter count**: Methods with more than 5 parameters.
- **Large file**: File exceeding 500 non-empty lines.
- **God class**: A class with more than 20 methods (suggests multiple responsibilities).
- **Naming issues**: Single-letter variable names in non-loop contexts, inconsistent naming conventions (mixed camelCase/snake_case in the same file).
- **Magic values**: Hardcoded numeric or string literals used in logic (not in constant definitions).
- **Missing error handling**: Catch-all exception handlers (`catch (Exception e)`, `except:`, bare `rescue`), empty catch blocks.
- **Unused imports**: Imports whose symbols do not appear elsewhere in the file (best-effort check by searching for the imported name).

### Step 5: Format Output

Format the analysis result using the report structure below. This format is mandatory for all modes (INV-AF-01).

```markdown
# File Analysis Report

**Target**: <absolute file path>
**Mode**: <full|lite|minimal>
**Language**: <detected language>
**Timestamp**: <ISO 8601 timestamp>

## Structure

### Imports
(grouped by category: standard library, external, internal)

### Classes
(list with line ranges and method counts; or "No classes" for non-OOP files)

### Functions
(module-level functions with line ranges; or "No standalone functions")

### Constants
(constants and enums; or "None")

## Complexity

| Metric | Value | Threshold | Status |
|--------|-------|-----------|--------|
| Lines | X | 500 | OK/Warning |
| Max method length | Y | 50 | OK/Warning |
| Max nesting depth | Z | 4 | OK/Warning |
| Methods/functions | W | - | - |

## Dependencies

### Standard Library
- (list)

### External
- (list)

### Internal
- (list)

## Concerns
- (each concern as a bullet, with location and severity)
- (if no concerns: "No concerns identified.")

## Summary
(One paragraph: file purpose, structural health, key findings. Mention mode used and any fallbacks that occurred.)
```

## Error Handling

| Condition | Behavior |
|-----------|----------|
| File not found | Output error: "Error: File not found: `<path>`. Verify the path and try again." STOP. |
| Binary file | Output error: "Error: `<path>` is a binary file. Analysis supports source files only." STOP. |
| Config missing | Default to Minimal mode (INV-AF-04). Proceed normally. |
| MCP unavailable (Full mode) | Log: "SDD MCP server unavailable. Falling back to Minimal mode." Run Minimal analysis. |
| MCP Memory unavailable (Lite mode) | Log: "MCP Memory unavailable. Skipping cache." Run Minimal analysis without caching. |
| Unrecognized language | Set language to "Unknown". Proceed with best-effort structural analysis. |

## Constraints

- This skill receives a single file path as `$ARGUMENTS`.
- This skill SHALL NOT modify any file (INV-AF-02).
- This skill SHALL NOT invoke the Skill tool or Task tool (INV-AF-03).
- This skill operates within a forked context (`context: fork`).
- Analysis is structural only. No execution, no test running, no linting tool invocation.
