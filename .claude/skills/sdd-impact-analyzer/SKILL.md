---
name: sdd-impact-analyzer
description: "Change impact analysis: trace reverse dependencies, assess risk, identify affected tests."
context: fork
user-invocable: false
---

# Impact Analysis: $ARGUMENTS

> **Scope**: Assess the ripple effects of changing a file, class, or function by tracing reverse dependency chains.
> Language-agnostic: uses Grep-based reference search and mode-aware graph queries.
> Do NOT use for: making changes, forward dependency analysis only (use `sdd-analyze-dependencies`), single file structure (use `sdd-analyze-file`).

## Invariants

- **INV-IA-01**: Output format is identical regardless of operating mode (Full, Lite, Minimal).
- **INV-IA-02**: Strictly read-only. SHALL NOT modify any file.
- **INV-IA-03**: Leaf node. SHALL NOT invoke the Skill tool or Task tool.
- **INV-IA-04**: When `.sdd/sdd-config.yaml` is missing or has no mode field, default to Minimal mode.
- **INV-IA-05**: Maximum traversal depth is 5 in Full mode, 2-3 in Minimal mode.
- **INV-IA-06**: Direct dependents (depth 1) and transitive dependents (depth > 1) are separate lists with no overlap.

## Steps

### Step 1: Parse Target

1. Extract the target from `$ARGUMENTS`. The target may be:
   - A file path (e.g., `src/auth/login.ts`)
   - A symbol name (e.g., `AuthService`, `processPayment`)
   - A module name (e.g., `auth`, `payment-gateway`)

2. Validate the target exists:
   - If the target looks like a file path: read the file using the Read tool. If it does not exist, report `TargetNotFound` and STOP.
   - If the target is a symbol or module name: use Grep to search the project for definitions (class declarations, function definitions, module exports). If zero matches: report `TargetNotFound` and STOP.

3. Check for ambiguity:
   - If the target name matches definitions in multiple unrelated files (e.g., two different classes named `Handler` in separate modules), report `AmbiguousTarget` with the list of matches and STOP. Do NOT produce a partial analysis.
   - If the target matches multiple definitions in the same file or within an obvious hierarchy (e.g., a class and its constructor), proceed with all of them as the target scope.

### Step 2: Detect Operating Mode

Read `.sdd/sdd-config.yaml` using the Read tool (path relative to repository root).

- If the file does not exist: mode = `minimal` (INV-IA-04).
- If the file exists: look for the field `sdd.infrastructure.mode`.
  - If the field is present and its value is `full`, `lite`, or `minimal`: use that value.
  - If the field is absent or has an unrecognized value: mode = `minimal` (INV-IA-04).

Proceed to the step matching the detected mode.

### Step 3A: Full Mode Analysis

Full mode uses the SDD MCP server to traverse the knowledge graph for reverse dependencies.

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

1. **Query reverse dependencies**:
   Call `mcp__sdd__query_cypher` requesting incoming edges for the target component. Traverse relationship types: `IMPORTS`, `USES`, `EXTENDS`, `IMPLEMENTS`. Set maximum traversal depth to 5 (INV-IA-05).

2. **Separate results by depth** (INV-IA-06):
   - **Direct dependents**: Components at depth 1 (they directly reference the target).
   - **Transitive dependents**: Components at depth 2-5 (they depend on a direct dependent).
   Ensure no component appears in both lists.

3. **Identify affected tests**:
   From the full set of dependents (direct + transitive), identify files matching test patterns:
   - File names containing `test`, `spec`, or `_test` (e.g., `test_auth.py`, `AuthService.spec.ts`, `auth_test.go`).
   - Files under directories named `test`, `tests`, `__tests__`, `spec`.

4. **Fallback**: If the MCP call fails or returns empty, log: "SDD MCP server unavailable. Falling back to Minimal mode." Then execute Step 3C.

5. Proceed to Step 4 (Risk Assessment).

### Step 3B: Lite Mode Analysis (MCP Memory Cache)

Lite mode caches analysis results in MCP Memory, keyed by target and file content hash.

1. **Get current file hash**:
   ```bash
   sha256sum $ARGUMENTS | cut -d' ' -f1
   ```
   If `sha256sum` is not available, fall back to `shasum -a 256 $ARGUMENTS | cut -d' ' -f1`.
   Store the result as `<file_hash>`.

2. **Check cache**:
   Call `mcp__memory__search_nodes` with query: `sdd-impact-analyzer:$ARGUMENTS:<file_hash>`

   - **Cache hit** (entity found with matching hash): Extract the `report` observation. Output it directly and STOP.
   - **Cache miss** (no entity or hash mismatch): Continue to step 3.

3. **Perform Minimal mode analysis**:
   Execute the same logic as Step 3C to produce the dependency data and risk assessment.

4. **Cache the result**:
   Call `mcp__memory__create_entities` with:
   ```
   Entity name: "sdd-impact-analyzer:$ARGUMENTS:<file_hash>"
   Entity type: "sdd-analysis-cache"
   Observations:
     - "target: $ARGUMENTS"
     - "file_hash: <file_hash>"
     - "timestamp: <ISO 8601>"
     - "report: <full markdown report>"
   ```
   If MCP Memory is unavailable, skip caching and output the report.

5. Proceed to Step 5 (Format Output).

### Step 3C: Minimal Mode Analysis

Minimal mode traces reverse dependencies using Grep. Maximum depth is 2-3 hops (INV-IA-05).

#### 3C.1: Find Direct Dependents (Depth 1)

Determine the search terms for the target:
- If the target is a file: extract the file name without extension, and search for import/require/include statements referencing it.
- If the target is a symbol: search for references to that symbol name.

Use Grep to search the project for files that reference the target. Exclude non-source directories (`node_modules`, `.git`, `build`, `dist`, `target`, `__pycache__`, `.venv`, `vendor`).

For each match, record:
- The file path of the dependent.
- The line number and content of the reference.
- The relationship type (import, function call, class extension, interface implementation) based on the line content.

These files form the **direct dependents** list.

#### 3C.2: Find Transitive Dependents (Depth 2-3)

For each direct dependent found in 3C.1:
1. Extract the file name or exported symbols from that dependent.
2. Use Grep to find files that reference the dependent (depth 2).
3. If depth 2 yields results, repeat once more for depth 3 (optional, stop if result set grows beyond 30 files to avoid explosion).

Record each transitive dependent with its depth and the chain of references leading to it.

Ensure no file appears in both the direct and transitive lists (INV-IA-06). If a file is found at multiple depths, keep it at the shallowest depth only.

#### 3C.3: Identify Affected Tests

From the combined set of dependents (direct + transitive) plus the target itself, identify test files:
- Files with names matching patterns: `*test*`, `*spec*`, `*_test.*`, `test_*.*`.
- Files located under `test/`, `tests/`, `__tests__/`, `spec/` directories.

Additionally, search for test files that directly reference the target but were not captured as dependents (test files that import the target for testing).

### Step 4: Risk Assessment

Calculate the risk assessment from the dependency data gathered in Step 3A, 3B, or 3C.

#### 4.1: Impact Scope

Classify by total number of affected components (direct + transitive dependents):

| Count | Classification |
|-------|---------------|
| 0 | `isolated` |
| 1-5 | `moderate` |
| 6-15 | `broad` |
| > 15 | `critical` |

#### 4.2: Test Coverage

Evaluate whether affected components have corresponding test files:

| Condition | Classification |
|-----------|---------------|
| All affected components have at least one test file covering them | `covered` |
| Some affected components have test files, others do not | `partial` |
| No affected components have test files | `uncovered` |

To determine coverage: for each affected component file, check whether a test file exists that references it (by name or import). A component is "covered" if at least one such test file exists.

#### 4.3: Recommendations

Generate action items based on the risk assessment:
- For `isolated` scope: note that the change is low-risk and can proceed.
- For `moderate` scope: list the affected components and recommend reviewing each.
- For `broad` scope: recommend incremental changes, testing after each step.
- For `critical` scope: recommend breaking the change into smaller parts, consider backward-compatible approaches.
- For `uncovered` or `partial` test coverage: recommend writing tests for uncovered components before making the change.

### Step 5: Format Output

Format the analysis result using the report structure below. This format is mandatory for all modes (INV-IA-01).

```markdown
# Impact Analysis Report

**Target**: <component/file/symbol>
**Mode**: <full|lite|minimal>
**Timestamp**: <ISO 8601>

## Direct Dependents
| Component | File | Relationship | Line |
|-----------|------|-------------|------|
| <name> | <path> | <IMPORTS/USES/EXTENDS/IMPLEMENTS> | <line number> |

**Direct dependents**: X files

## Transitive Dependents
| Component | File | Depth | Chain |
|-----------|------|-------|-------|
| <name> | <path> | <2-5> | <target -> dep1 -> dep2> |

**Transitive dependents**: Y files

## Affected Tests
| Test File | Coverage Type |
|-----------|---------------|
| <path> | <unit/integration/e2e> |

**Tests to run**: Z test files

## Risk Assessment
| Factor | Value |
|--------|-------|
| Impact Scope | <isolated/moderate/broad/critical> |
| Affected Components | <total count> |
| Test Coverage | <covered/partial/uncovered> |

## Recommendations
1. <specific action item based on risk assessment>
2. <additional action item if applicable>

## Summary
<One paragraph: what was analyzed, key findings, risk level, mode used, any fallbacks that occurred.>
```

When a table has no rows (e.g., zero transitive dependents), keep the table header and add a single row: `| (none) | - | - | - |`.

## Error Handling

| Condition | Behavior |
|-----------|----------|
| `TargetNotFound` | Output: "Error: Target not found: `<target>`. Verify the path or symbol name and try again." STOP. |
| `AmbiguousTarget` | Output: "Error: Ambiguous target `<target>`. Multiple matches found:" followed by the list of matching files/symbols. "Provide a more specific target to disambiguate." STOP. Do NOT produce partial analysis. |
| `ConfigMissing` | Default to Minimal mode (INV-IA-04). Proceed normally. |
| MCP unavailable (Full mode) | Log: "SDD MCP server unavailable. Falling back to Minimal mode." Run Minimal analysis. |
| MCP Memory unavailable (Lite mode) | Log: "MCP Memory unavailable. Skipping cache." Run Minimal analysis without caching. |

## Constraints

- This skill receives a target (file path, symbol, or module name) as `$ARGUMENTS`.
- This skill SHALL NOT modify any file (INV-IA-02).
- This skill SHALL NOT invoke the Skill tool or Task tool (INV-IA-03).
- This skill operates within a forked context (`context: fork`).
- Analysis is structural only. No execution, no test running, no linting tool invocation.
- Maximum output: the formatted report. No commentary beyond what the report structure specifies.
