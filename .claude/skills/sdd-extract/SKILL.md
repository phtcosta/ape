---
name: sdd-extract
description: "Populate the Neo4j knowledge graph by extracting project structure from source code. Use WHEN setting up Full mode or refreshing the graph after code changes. WHEN NOT: in Lite or Minimal mode (no Neo4j available)."
context: fork
argument-hint: "[project-path] [--incremental | --full]"
---

# Extract Project: $ARGUMENTS

> **Scope**: Populate the Neo4j knowledge graph by parsing source files and creating nodes for units, callables, members, and patterns.
> Language-agnostic: reads language from config or auto-detects from file extensions.
> Do NOT use for: querying the graph (use analysis skills), Lite or Minimal mode (no Neo4j).

## Invariants

- **INV-EX-01**: Leaf node. SHALL NOT invoke the Skill tool or Task tool. Calls MCP tools directly.
- **INV-EX-02**: SHALL only run when `sdd.infrastructure.mode` is `full`. Any other mode -> warn and exit.
- **INV-EX-03**: SHALL report structured extraction statistics regardless of whether all files parsed successfully.
- **INV-EX-04**: When `.sdd/sdd-config.yaml` is missing -> warn "No config found" and exit.

## Tool Restrictions

You SHALL NOT use the Skill tool or Task tool. This is a leaf skill that operates directly with MCP tools and the Read/Bash tools.

## Steps

### Step 1: Read Configuration

1. Read `.sdd/sdd-config.yaml` using the Read tool (path relative to repository root).

2. **Config missing** (INV-EX-04):
   - If the file does not exist, output the following message and STOP:
     ```
     Warning: No config found — cannot determine operating mode.
     Run /sdd-setup to configure the project before extraction.
     ```

3. **Check operating mode** (INV-EX-02):
   - Read `sdd.infrastructure.mode` from the config.
   - If mode is NOT `full`, output the following message and STOP:
     ```
     Warning: Extraction requires Full mode (Neo4j). Current mode: <mode>.
     Re-run /sdd-setup with mode=full to enable extraction.
     ```

### Step 2: Determine Language

1. Read `sdd.project.language` from the config.
2. If the field is missing or its value is `auto`: use `language = "auto"`.
3. Otherwise: use the configured language value (e.g., `java`, `cobol`).

### Step 3: Determine Project Path and Extraction Mode

1. Parse `$ARGUMENTS` for the project path and optional flags:
   - `--incremental`: Force incremental extraction (only re-parse changed files).
   - `--full`: Force full extraction (re-parse all files).
   - Any other token: treat as the project path.
2. If no project path token is found: default to `.` (current directory).
3. Determine extraction mode:
   - If `--incremental` flag is present: use `incremental = True`.
   - If `--full` flag is present: use `incremental = False`.
   - If neither flag is present: query the Project node for `last_commit` using:
     ```
     mcp__sdd__query_cypher(cypher="MATCH (p:Project {path: $path}) RETURN p.last_commit as last_commit", params={"path": "<resolved project path>"})
     ```
     - If `last_commit` exists (prior extraction): default to `incremental = True` and print:
       ```
       Prior extraction found. Running incremental extraction (use --full to re-extract all files).
       ```
     - If no `last_commit`: use `incremental = False` (full extraction).

### Step 4: Invoke Extraction

Call the MCP server's extraction tool:

```
mcp__sdd__extract_project(
  project_path = <path from Step 3>,
  language = <language from Step 2>,
  detect_patterns = True,
  incremental = <incremental from Step 3>
)
```

Capture the result for reporting.

### Step 5: Report Results (INV-EX-03)

Format the extraction results using this report structure. This report SHALL be produced regardless of whether some files failed to parse.

```markdown
# Extraction Report

**Project**: <project path>
**Mode**: full (<incremental or full extraction>)
**Language**: <language used>
**Timestamp**: <ISO 8601 timestamp>

## Statistics
| Metric | Count |
|--------|-------|
| Files processed | <files_processed from result> |
| Files failed | <files_failed from result> |
| Files skipped (unchanged) | <files_skipped from result> |
| Files removed (deleted) | <files_removed from result> |
| Units extracted | <total_units from result> |
| Callables extracted | <total_callables from result> |
| Members extracted | <total_members from result> |
| Patterns detected | <total_patterns from result> |

## Graph Validation

### Node Counts
| Label | Count |
|-------|-------|
| Project | <validation.node_counts["Project"]> |
| Unit | <validation.node_counts["Unit"]> |
| Callable | <validation.node_counts["Callable"]> |
| Member | <validation.node_counts["Member"]> |
| Container | <validation.node_counts["Container"]> |
| Pattern | <validation.node_counts["Pattern"]> |
| **Total** | <validation.total_nodes> |

### Relationship Counts
| Type | Count |
|------|-------|
| CONTAINS | <validation.relationship_counts["CONTAINS"]> |
| EXTENDS | <validation.relationship_counts["EXTENDS"]> |
| IMPLEMENTS | <validation.relationship_counts["IMPLEMENTS"]> |
| IMPORTS | <validation.relationship_counts["IMPORTS"]> |
| HAS_PATTERN | <validation.relationship_counts["HAS_PATTERN"]> |
| **Total** | <validation.total_relationships> |

### Warnings
- <warning message>

(If no warnings: "No warnings — graph looks healthy.")

## Errors (if any)
- <file path>: <error message>

(If no errors: "No errors.")

## Next Steps
- Run analysis skills to query the populated graph
- Re-run `/sdd-extract` after significant code changes
```

Read the statistics from the MCP tool result:
- `files_processed`: number of files successfully parsed
- `files_failed`: number of files that failed to parse
- `files_skipped`: number of unchanged files not re-parsed (incremental mode only)
- `files_removed`: number of Unit nodes deleted for removed files (incremental mode only)
- `total_units`: total Unit nodes created
- `total_callables`: total Callable nodes created
- `total_members`: total Member nodes created
- `total_patterns`: total Pattern nodes detected

Read errors from the `errors` array in the result. Each entry has `file` and `error` fields.

When `files_skipped` and `files_removed` are both 0 (full extraction), omit those rows from the table.

Read graph validation from the `validation` key in the result:
- `validation.node_counts`: dict mapping label names to counts
- `validation.relationship_counts`: dict mapping relationship type names to counts
- `validation.total_nodes`: sum of all node counts
- `validation.total_relationships`: sum of all relationship counts
- `validation.warnings`: list of warning strings (empty if graph is healthy)

## Error Handling

| Condition | Behavior |
|-----------|----------|
| Config file missing | Output: "Warning: No config found -- cannot determine operating mode." STOP (INV-EX-04). |
| Mode is not `full` | Output: "Warning: Extraction requires Full mode (Neo4j). Current mode: <mode>." STOP (INV-EX-02). |
| Neo4j unreachable | Report the connection error from the MCP tool response. Include the error in the report. |
| Individual parse failures | List each failed file with its error in the Errors section. Continue reporting statistics for files that succeeded (INV-EX-03). |
| MCP tool returns error status | Report the error. If it indicates Neo4j connectivity issues, suggest checking that the database is running (`/sdd-setup` or `docker compose up`). |

## Constraints

- This skill receives an optional project path as `$ARGUMENTS`.
- This skill SHALL NOT invoke the Skill tool or Task tool (INV-EX-01).
- This skill SHALL only run when mode is `full` (INV-EX-02).
- This skill SHALL report structured statistics regardless of parse failures (INV-EX-03).
- This skill operates within a forked context (`context: fork`).
