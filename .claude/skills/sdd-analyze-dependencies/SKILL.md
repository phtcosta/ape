---
name: sdd-analyze-dependencies
description: "Dependency graph analysis: circular dependencies, coupling metrics, and layer violations."
context: fork
user-invocable: false
---

# Analyze Dependencies: $ARGUMENTS

> **Scope**: Maps dependency relationships between components, detects circular dependencies, identifies high-coupling, and flags layer violations.
> Language-agnostic: works for any language by reading import/use statements and building an adjacency list.
> Do NOT use for: fixing dependencies (use `sdd-refactor`), single-file analysis (use `sdd-analyze-file`).

## Invariants

- **INV-AD-01**: Output format is identical regardless of operating mode (Full, Lite, Minimal).
- **INV-AD-02**: Strictly read-only. SHALL NOT modify any file.
- **INV-AD-03**: Leaf node. SHALL NOT invoke the Skill tool or Task tool.
- **INV-AD-04**: When `.sdd/sdd-config.yaml` is missing or has no mode field, default to Minimal mode.
- **INV-AD-05**: Circular dependency detection SHALL report ALL cycles found, not just the first one.

## Steps

### Step 1: Validate Target Path

1. Parse `$ARGUMENTS` to extract the target path (first argument). If empty, default to the repository root (project-wide analysis).
2. Determine scope:
   - If the path points to a single file: scope = `file`
   - If the path points to a directory: scope = `directory`
   - If the path is the repository root or empty: scope = `project`
3. Verify the path exists using Read (for files) or Glob (for directories).
   - If the path does not exist: output `PathNotFound` error and STOP.

### Step 2: Detect Operating Mode

Read `.sdd/sdd-config.yaml` using the Read tool (path relative to repository root).

- If the file does not exist: mode = `minimal` (INV-AD-04).
- If the file exists: look for the field `sdd.infrastructure.mode`.
  - If the field is present and its value is `full`, `lite`, or `minimal`: use that value.
  - If the field is absent or has an unrecognized value: mode = `minimal` (INV-AD-04).

Proceed to the step matching the detected mode.

### Step 3A: Full Mode Analysis

Full mode queries the SDD MCP server for dependency graph data.

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

1. **Query dependency graph**:
   Call `mcp__sdd__get_dependencies` with the target path as the component identifier. Request relationships of types: IMPORTS, USES, EXTENDS, IMPLEMENTS.
   - If the MCP call fails or returns empty: log "SDD MCP server unavailable. Falling back to Minimal mode." Proceed to Step 3C.

2. **Build adjacency list from MCP results**:
   For each relationship returned, record:
   - `from`: source component name
   - `to`: target component name
   - `type`: relationship type (IMPORTS, USES, EXTENDS, IMPLEMENTS)
   - `file`: file where the relationship is declared
   - `line`: line number of the declaration

3. **Compute component metrics**:
   For each component, count:
   - Incoming edges (other components that depend on it)
   - Outgoing edges (components it depends on)
   - Total = incoming + outgoing

4. Proceed to Step 4 (Cycle Detection).

### Step 3B: Lite Mode Analysis (MCP Memory Cache)

Lite mode caches analysis results in MCP Memory, keyed by target path and directory content hash.

1. **Get current directory hash**:
   ```bash
   find $ARGUMENTS -type f | sort | xargs wc -c | sha256sum | cut -d' ' -f1
   ```
   If `sha256sum` is not available, fall back to piping to `shasum -a 256 | cut -d' ' -f1`.
   Store the result as `<dir_hash>`.

2. **Check cache**:
   Call `mcp__memory__search_nodes` with query: `sdd-analyze-dependencies:$ARGUMENTS:<dir_hash>`

   - **Cache hit** (entity found with matching hash): Extract the `report` observation. Output it directly and STOP.
   - **Cache miss** (no entity or hash mismatch): Continue to step 3.

3. **Perform Minimal mode analysis**:
   Execute the same logic as Step 3C below to produce the dependency data.

4. **Cache the result**:
   Call `mcp__memory__create_entities` with:
   ```
   Entity name: "sdd-analyze-dependencies:$ARGUMENTS:<dir_hash>"
   Entity type: "sdd-analysis-cache"
   Observations:
     - "target: $ARGUMENTS"
     - "dir_hash: <dir_hash>"
     - "timestamp: <ISO 8601>"
     - "report: <full markdown report>"
   ```
   If MCP Memory is unavailable, skip caching and output the report.

5. Proceed to Step 6 (Output).

### Step 3C: Minimal Mode Analysis

Minimal mode builds a dependency map from source files using only Read, Glob, and Grep.

#### 3C.1: Discover Source Files

Use Glob to find source files under the target path. Use common source file extensions:

| Extensions | Languages |
|-----------|-----------|
| `*.java` | Java |
| `*.py` | Python |
| `*.ts`, `*.tsx` | TypeScript |
| `*.js`, `*.jsx` | JavaScript |
| `*.kt`, `*.kts` | Kotlin |
| `*.go` | Go |
| `*.rs` | Rust |
| `*.rb` | Ruby |
| `*.cs` | C# |
| `*.scala` | Scala |
| `*.swift` | Swift |
| `*.cbl`, `*.cob` | COBOL |

Exclude test files, build output, and dependency directories:
- `**/node_modules/**`, `**/target/**`, `**/build/**`, `**/dist/**`, `**/.venv/**`, `**/venv/**`, `**/__pycache__/**`, `**/*.egg-info/**`

If no source files are found: output `NoSourceFiles` error and STOP.

#### 3C.2: Extract Import Statements

For each source file found, use Read to extract its content. Identify import statements based on file extension:

| Language | Import patterns |
|----------|----------------|
| Java | `import <package>.<Class>;`, `import static <package>.<method>;` |
| Python | `import <module>`, `from <module> import <name>` |
| TypeScript/JavaScript | `import ... from '<module>'`, `require('<module>')` |
| Kotlin | `import <package>.<Class>` |
| Go | `import "<package>"`, `import ( ... )` |
| Rust | `use <crate>::<module>`, `mod <module>` |
| C# | `using <namespace>;` |

For each import, determine whether it references an internal project file (resolvable within the source tree) or an external dependency. Only internal dependencies form the adjacency list.

#### 3C.3: Build Adjacency List

Create a directed graph where:
- Each node is a source file (or the component it defines, e.g., a class name extracted from the file)
- Each edge represents an import/dependency from one component to another
- Each edge records:
  - `from`: the importing file/component
  - `to`: the imported file/component
  - `type`: `IMPORTS` (default for static analysis)
  - `file`: the file where the import appears
  - `line`: the line number of the import statement

#### 3C.4: Identify Usage Patterns

Use Grep to find usage patterns beyond import statements:
- Class instantiations: `new ClassName(`, `ClassName.builder(`, `ClassName.of(`
- Static method calls: `ClassName.methodName(`
- Inheritance: `extends ClassName`, `implements InterfaceName`, `: BaseClass`

For each usage found, add an edge with the appropriate type:
- Instantiation/static calls: type = `USES`
- `extends`: type = `EXTENDS`
- `implements`: type = `IMPLEMENTS`

#### 3C.5: Compute Component Metrics

For each component in the adjacency list, count:
- **Incoming edges**: other components that depend on it (fan-in)
- **Outgoing edges**: components it depends on (fan-out)
- **Total**: incoming + outgoing

Proceed to Step 4 (Cycle Detection).

### Step 4: Detect Circular Dependencies

Perform cycle detection on the adjacency list built in Step 3A or 3C.

Algorithm — DFS-based cycle detection:
1. Maintain three sets: `unvisited`, `in_progress`, `visited`.
2. Start all nodes in `unvisited`.
3. For each node in `unvisited`:
   a. Move it to `in_progress`.
   b. For each outgoing neighbor:
      - If the neighbor is in `in_progress`: a cycle is found. Record the cycle path by tracing back through the DFS stack.
      - If the neighbor is in `unvisited`: recurse.
   c. Move the node to `visited`.
4. Collect ALL cycles found (INV-AD-05).

Report each cycle as a sequence: `A -> B -> C -> A`.

### Step 5: Detect Layer Violations

Layer violations occur when a lower-layer component depends on a higher-layer component.

#### 5.1: Load Layer Definitions

1. Read `sdd.project.language` from `.sdd/sdd-config.yaml`.
   - If not available: attempt to infer from the majority language of discovered source files.
2. Read `plugins/<language>/config.json` using the Read tool.
3. Check for the `layers` field in the plugin config.
   - If the `layers` field does not exist: skip layer violation detection. Note in output: "Layer violation detection skipped: no layer definitions available for <language>."
   - If the `layers` field exists: proceed.

#### 5.2: Define Layer Ordering

The layer hierarchy is determined by the order of keys in the `layers` object. The first key listed is the highest layer (closest to user), the last key listed is the lowest layer (closest to infrastructure). Each plugin defines its own ordering.

For example, if the Java plugin defines layers as `controller, service, repository, model, config`, then:
- `controller` is the highest layer (index 0)
- `config` is the lowest layer (index 4)
- A component in `repository` (index 2) importing from `controller` (index 0) is a violation — lower layer depending on higher layer.

#### 5.3: Classify Components

For each component in the adjacency list, determine its layer using three classification rules from the plugin config, checked in this order:

1. **Package/directory matching**: Check if the component's file path contains any of the `packages` values for a layer. For example, if the file path contains `/repository/` or `/dao/`, classify as `repository`.
2. **Name suffix matching**: Check if the component name ends with any of the `nameSuffixes` for a layer. For example, `UserRepository` matches the `repository` layer.
3. **Annotation/decorator matching**: Check if the component's source contains any of the `annotations` (Java) or `decorators` (Python/TypeScript) for a layer.

If a component matches multiple layers, use the first match in the order above (package > name suffix > annotation).

If a component matches no layer, leave it unclassified. Unclassified components do not participate in layer violation detection.

#### 5.4: Check Violations

For each edge in the adjacency list:
- If both `from` and `to` are classified into layers:
  - If `from` has a higher layer index (lower layer) than `to` (higher layer): flag as a violation.
  - Record: `from` component, `from` layer, `to` component, `to` layer, file, line.

Crosscutting layers (if defined, e.g., `crosscutting`, `config`) are exempt from violation detection — they may import from any layer.

### Step 6: Identify High-Coupling Components

A component has high coupling when its total edge count (incoming + outgoing) exceeds 10.

For each component in the adjacency list:
- If `total >= 11`: add to the high-coupling list with its incoming, outgoing, and total counts, and mark status as `HIGH`.
- If `total >= 7 and total <= 10`: mark status as `WARNING` (approaching threshold).

### Step 7: Format Output

Format the analysis result using the report structure below. This format is mandatory for all modes (INV-AD-01).

```markdown
# Dependency Analysis Report

**Target**: <path analyzed>
**Mode**: <full|lite|minimal>
**Scope**: <file|directory|project>
**Timestamp**: <ISO 8601 timestamp>

## Components
| Component | File | Incoming | Outgoing | Total |
|-----------|------|----------|----------|-------|
| <name> | <file path> | <count> | <count> | <count> |

## Relationships
| From | To | Type | File | Line |
|------|----|------|------|------|
| <source> | <target> | <IMPORTS/USES/EXTENDS/IMPLEMENTS> | <file> | <line> |

## Circular Dependencies
(list each cycle as: A -> B -> C -> A)
(if none: "No circular dependencies detected.")

## High Coupling
| Component | Incoming | Outgoing | Total | Status |
|-----------|----------|----------|-------|--------|
| <name> | <count> | <count> | <count> | HIGH/WARNING |
(if none: "No high-coupling components detected.")

## Layer Violations
| From (Layer) | To (Layer) | File | Line |
|--------------|------------|------|------|
| <component (layer)> | <component (layer)> | <file> | <line> |
(if none: "No layer violations detected.")
(if skipped: "Layer violation detection skipped: no layer definitions available for <language>.")

## Metrics
- Total components: <count>
- Total relationships: <count>
- Circular dependencies: <count>
- High coupling components: <count>
- Layer violations: <count>
- Average coupling: <total edges / component count, rounded to 1 decimal>
- Max coupling: <highest total for any single component>

## Summary
(One paragraph: dependency landscape overview, critical findings — cycles, high coupling, violations. Mention mode used and any fallbacks that occurred.)
```

### Step 8: Cache Result (Lite Mode Only)

If the operating mode is `lite` and the analysis was not served from cache, cache the formatted report as described in Step 3B.4. If MCP Memory is unavailable, skip caching.

## Error Handling

| Condition | Behavior |
|-----------|----------|
| `PathNotFound` — target does not exist | Output error: "Error: Path not found: `<path>`. Verify the path and try again." STOP. |
| `NoSourceFiles` — no analyzable files | Output error: "Error: No analyzable source files found under `<path>`." STOP. |
| `ConfigMissing` — `.sdd/sdd-config.yaml` absent | Default to Minimal mode (INV-AD-04). Proceed normally. |
| MCP unavailable (Full mode) | Log: "SDD MCP server unavailable. Falling back to Minimal mode." Run Minimal analysis. |
| MCP Memory unavailable (Lite mode) | Log: "MCP Memory unavailable. Skipping cache." Run Minimal analysis without caching. |
| Plugin config not found | Skip layer violation detection. Note in Layer Violations section. |
| No `layers` field in plugin | Skip layer violation detection. Note in Layer Violations section. |

## Constraints

- This skill receives a target path (file, directory, or empty for project-wide) as `$ARGUMENTS`.
- This skill SHALL NOT modify any file (INV-AD-02).
- This skill SHALL NOT invoke the Skill tool or Task tool (INV-AD-03).
- This skill operates within a forked context (`context: fork`).
- Analysis is structural only. No execution, no test running, no build tool invocation.
