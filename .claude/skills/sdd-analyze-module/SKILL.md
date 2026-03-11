---
name: sdd-analyze-module
description: "Module architecture analysis: layers, components, relationships, patterns, and cohesion."
context: fork
user-invocable: false
---

# Analyze Module: $ARGUMENTS

Performs architectural analysis of a module (a directory of related source files). Produces a structured report covering layers, components, relationships, patterns, and cohesion.

This skill is read-only. It SHALL NOT create, modify, or delete any project file.

**Invariants:**

- **INV-AM-01**: Output format SHALL be identical regardless of operating mode (full, lite, minimal).
- **INV-AM-02**: This skill is read-only. It SHALL NOT write, create, or delete any project file.
- **INV-AM-03**: Leaf node. This skill SHALL NOT invoke the Skill tool or Task tool.
- **INV-AM-04**: When `.sdd/sdd-config.yaml` is missing or mode is absent, default to `minimal`.
- **INV-AM-05**: Analysis is non-recursive by default. Only files directly inside the target directory are analyzed, not subdirectories.

## Input

`$ARGUMENTS` contains the path to the directory to analyze. The path can be absolute or relative to the current working directory.

Examples:
- `src/main/java/com/example/service`
- `/home/user/project/lib/core`

## Step 0: Validate Target Directory

Check that the target directory exists:

```bash
test -d "$ARGUMENTS" && echo "EXISTS" || echo "NOT_FOUND"
```

WHEN the directory does not exist
THEN return a `DirectoryNotFound` error:
```
Error: DirectoryNotFound
Message: "Directory '$ARGUMENTS' does not exist."
```
AND stop execution.

## Step 1: Detect Operating Mode

Read `.sdd/sdd-config.yaml` using the Read tool.

- If the file does not exist: set `mode = minimal` (INV-AM-04).
- If the file exists but `sdd.infrastructure.mode` is absent: set `mode = minimal` (INV-AM-04).
- If the file exists and `sdd.infrastructure.mode` is present: use its value (`full`, `lite`, or `minimal`).
- If the value is not one of the three recognized modes: set `mode = minimal`.

Also extract `sdd.project.language` if present (used in Step 3 for layer classification).

## Step 2: Execute Mode-Specific Analysis

Branch based on the detected mode. All three modes produce the same output structure (INV-AM-01).

---

### Full Mode

Query the SDD MCP server for structural data about the module.

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

**Step 2F-1: Query graph for module nodes**

```
mcp__sdd__query_cypher with:
  - query: nodes contained within the module path
  - scope: the target directory
```

Extract: component names, types (class/interface/function/module), file locations, line counts.

**Step 2F-2: Query dependencies**

```
mcp__sdd__get_dependencies with:
  - path: the target directory
```

Extract: relationships between components (imports, calls, extends, implements).

**Step 2F-3: Extract layers and containment**

From the graph results, identify:
- Layer assignments (controller, service, repository, model, config, etc.)
- Containment hierarchies (packages, namespaces, modules)

**Fallback**: If any MCP call fails (server unavailable, query error), fall back to Minimal mode analysis for the failed step. Log the fallback reason in the report.

---

### Lite Mode

Check MCP Memory for a cached analysis result.

**Step 2L-1: Get current directory hash**

```bash
find <module_path> -type f | sort | xargs wc -c | sha256sum | cut -d' ' -f1
```
If `sha256sum` is not available, fall back to piping to `shasum -a 256 | cut -d' ' -f1`.
Store the result as `<dir_hash>`.

**Step 2L-2: Search for cached result**

```
mcp__memory__search_nodes with query: "sdd-analyze-module:<module_path>:<dir_hash>"
```

Where `<module_path>` is the normalized path from `$ARGUMENTS` and `<dir_hash>` is from Step 2L-1.

**Cache hit**: If a matching entity is found and its `dir_hash` observation matches the current hash, extract the cached report and return it. Skip to Output.

**Cache miss**: Proceed to Minimal mode analysis (Step 2M). After producing the report, cache it:

```
mcp__memory__create_entities with:
  Entity name: "sdd-analyze-module:<module_path>:<dir_hash>"
  Entity type: "sdd-analysis-cache"
  Observations:
    - "target: <module_path>"
    - "dir_hash: <dir_hash>"
    - "timestamp: <ISO 8601>"
    - "report: <full report content>"
```

If MCP Memory is unavailable, skip caching and return the report.

---

### Minimal Mode

Analyze the module by reading source files directly.

**Step 2M-1: List source files**

Use Glob to list source files in the target directory. Analyze only files directly in the directory (INV-AM-05), not subdirectories:

```
Glob: <target_directory>/*
```

Filter for recognized source file extensions (`.java`, `.py`, `.ts`, `.tsx`, `.js`, `.jsx`, `.go`, `.rs`, `.kt`, `.kts`, `.c`, `.h`, `.cpp`, `.hpp`, `.cc`, `.cs`, `.rb`, `.swift`, `.cbl`, `.cob`).

WHEN no source files are found
THEN return an `EmptyModule` error:
```
Error: EmptyModule
Message: "No source files found in '<target_directory>'."
```
AND stop execution.

**Step 2M-2: Identify languages**

Count source files per language based on file extensions. Record all languages present.

**Step 2M-3: Read file headers and imports**

For each source file, read the first 100 lines using the Read tool (with `limit: 100`). Extract:
- Package/namespace declarations
- Import/require/use statements
- Class/interface/struct/function declarations
- Annotations/decorators
- Line count per file

**Step 2M-4: Classify components into layers**

Layer classification uses a two-tier approach:

**Tier A: Plugin-based classification**

Read `sdd.project.language` from the config (obtained in Step 1). If a language is known:

1. Read `plugins/<language>/config.json` using the Read tool.
2. Check for a `layers` field. The `layers` object maps layer names to classification criteria:

```json
{
  "controller": {
    "annotations": ["@Controller", "@RestController"],
    "nameSuffixes": ["Controller", "Resource", "Endpoint"],
    "packages": ["controller", "web", "rest", "api"]
  }
}
```

3. For each source file, check against each layer's criteria:
   - **`nameSuffixes`**: Does the file name (without extension) end with any listed suffix?
   - **`annotations`**: Does the file contain any listed annotation/decorator?
   - **`packages`**: Is the file located in a directory matching any listed package name, or does its package/namespace declaration contain any listed name?
4. Assign the file to the first matching layer. If multiple layers match, prefer the one with the most matching criteria.

**Tier B: Fallback classification (no plugin or no `layers` field)**

If no plugin matches the language, no `layers` field exists in the plugin config, or no language is configured:

- Classify by directory name patterns found in the file's path (e.g., a file under a `controllers/` directory is likely a controller).
- Classify by import patterns (e.g., a file importing ORM modules is likely a repository/data layer).
- Files that do not match any heuristic are classified as `unclassified`.

Do not hardcode language-specific naming conventions in this fallback. Use only directory structure and import patterns.

**Step 2M-5: Identify relationships**

From the import statements collected in Step 2M-3, identify relationships between components within the module:

- **imports**: Component A imports Component B (both in the module).
- **extends**: Component A extends/inherits from Component B.
- **implements**: Component A implements interface B.

Only report relationships where both endpoints are within the analyzed module.

**Step 2M-6: Identify patterns**

Based on the layer distribution and relationships, identify structural patterns:

| Pattern | Indicators |
|---------|-----------|
| MVC | Controller + Model layers present, with Service or direct controller-model relationships |
| Repository | Repository layer components abstracting data access |
| Facade | A component that aggregates calls to multiple other components |
| Layered | Clear separation into 3+ distinct layers with top-down dependency flow |
| Event-driven | Event/listener/handler components or pub/sub patterns in imports |

Report only patterns with concrete evidence from the analysis.

**Step 2M-7: Assess cohesion**

Evaluate module cohesion based on:

- **High cohesion**: Components share a single responsibility area. Most relationships are internal. Layer distribution is focused (1-2 primary layers).
- **Medium cohesion**: Components relate to the same domain but span multiple responsibilities. Mix of internal and external relationships.
- **Low cohesion**: Components serve unrelated purposes. Many external dependencies relative to internal relationships. Scattered layer distribution.

Provide a brief justification for the assessment.

**Step 2M-8: Identify concerns**

Flag architectural concerns such as:
- Circular dependencies between components
- Layer violations (lower layers depending on higher layers)
- Overly large files (estimate threshold: >500 lines for most languages)
- Single component with excessive incoming or outgoing relationships (>5)
- Mixed responsibilities within a single component

## Step 3: Produce Report

Format the analysis results into the standard report structure (INV-AM-01).

```markdown
# Module Analysis Report

**Target**: <analyzed directory path>
**Mode**: <full|lite|minimal>
**Timestamp**: <ISO 8601 datetime>

## Overview
**Files**: <count> | **Languages**: <list> | **Components**: <count>

## Layers
| Layer | Components | Count |
|-------|-----------|-------|
| <layer name> | <comma-separated component names> | <count> |

## Components
| Component | Type | Layer | File | Lines |
|-----------|------|-------|------|-------|
| <name> | <class/interface/function/module> | <layer> | <file path> | <line count> |

## Relationships
| From | To | Type |
|------|----|------|
| <component> | <component> | <imports/extends/implements> |

## Patterns
<identified structural patterns with brief evidence>

## Cohesion
**Assessment**: <high|medium|low>

<justification paragraph>

## Concerns
- <concern 1>
- <concern 2>

(or "No architectural concerns identified." if none)

## Summary
<one-paragraph summary of the module's architecture, key findings, and overall health>
```

WHEN mode is `lite` and the result was produced from Minimal analysis (cache miss)
THEN cache the report as described in Step 2L-2 before returning.

## Error Handling

### DirectoryNotFound

WHEN `$ARGUMENTS` path does not exist or is not a directory
THEN return:
```
Error: DirectoryNotFound
Message: "Directory '<path>' does not exist."
```

### EmptyModule

WHEN the target directory exists but contains no recognized source files
THEN return:
```
Error: EmptyModule
Message: "No source files found in '<path>'."
```

### ConfigMissing

WHEN `.sdd/sdd-config.yaml` does not exist
THEN default to Minimal mode (INV-AM-04)
AND proceed with analysis (this is not a fatal error).

## Constraints

- Read-only. Do not create, modify, or delete any file (INV-AM-02).
- Leaf node. Do not invoke the Skill tool or Task tool (INV-AM-03).
- Non-recursive by default. Analyze only files directly in the target directory (INV-AM-05).
- Do not prompt the user for input. This skill runs non-interactively as a subroutine.
- Do not install dependencies or run package managers.
