---
name: sdd-config-reader
description: "Read .sdd/sdd-config.yaml and determine the operating mode (full, lite, or minimal). Single source of truth for mode detection."
user-invocable: false
---

# sdd-config-reader

Reads `.sdd/sdd-config.yaml` from the project root and determines the SDD operating mode. Returns structured configuration data that other skills use to decide how to query for information (MCP server, MCP Memory, or direct file reading).

This skill is a foundation-tier component. It runs inline (shared context) and is called by other skills, never directly by users.

**Invariants:**

- **INV-CFG-01:** Missing `.sdd/sdd-config.yaml` SHALL result in mode `"minimal"`.
- **INV-CFG-02:** File exists but `sdd.infrastructure.mode` is absent SHALL result in mode `"minimal"`.
- **INV-CFG-03:** Only `"full"`, `"lite"`, `"minimal"` are accepted values. Any other value SHALL produce an `InvalidConfig` error and fall back to `"minimal"`.
- **INV-CFG-04:** This skill MUST NOT create, write, or modify `.sdd/sdd-config.yaml` or any other file.

---

## Input

`$ARGUMENTS` contains an optional project root path. When omitted, use the current working directory.

The config file path is: `<project-root>/.sdd/sdd-config.yaml`

---

## Mode Detection

### Step 1: Locate Config File

Determine the project root:
- If `$ARGUMENTS` is provided and non-empty, use it as the project root.
- If `$ARGUMENTS` is empty or absent, use the current working directory.

Check whether `.sdd/sdd-config.yaml` exists relative to the project root:

```bash
test -f "<project-root>/.sdd/sdd-config.yaml" && echo "EXISTS" || echo "MISSING"
```

If the file is missing:
- Set `configExists: false`
- Set `configPath: null`
- Set `mode: "minimal"` (INV-CFG-01)
- Set `language: null`
- Set `projectName: null`
- Skip to **Output Format** and return the result.

### Step 2: Read and Parse

Read the file using the Read tool:

```
Read: <project-root>/.sdd/sdd-config.yaml
```

Parse the contents as YAML. The expected schema is:

```yaml
sdd:
  project:
    name: "my-project"        # optional
    language: "java"           # optional
  infrastructure:
    mode: "full"               # required: full | lite | minimal
```

If the file contains invalid YAML (syntax errors, unparseable content):
- Report an `InvalidConfig` error with the parse failure details.
- Fall back: set `mode: "minimal"`, `language: null`, `projectName: null`.
- Set `configExists: true` and `configPath` to the file path.
- Skip to **Output Format** and return the result.

### Step 3: Determine Mode

Extract the value at `sdd.infrastructure.mode`.

**If `sdd.infrastructure.mode` is absent** (the `sdd` key is missing, `infrastructure` is missing, or `mode` is missing):
- Set `mode: "minimal"` (INV-CFG-02).

**If `sdd.infrastructure.mode` is present**, normalize to lowercase and check:

| Value | Result |
|-------|--------|
| `"full"` | `mode: "full"` |
| `"lite"` | `mode: "lite"` |
| `"minimal"` | `mode: "minimal"` |
| Anything else | `InvalidConfig` error: `"Unrecognized mode: '<value>'. Expected full, lite, or minimal."` Fall back to `mode: "minimal"` (INV-CFG-03). |

Set `configExists: true` and `configPath` to the absolute path of the config file.

---

## How Callers Use the Mode

The returned mode determines how calling skills query for structural and project data.

### Full Mode (Neo4j)

- Call `mcp__sdd__*` tools for structural data (class hierarchies, call graphs, dependency analysis).
- Enrich with Claude code reading (Read/Glob/Grep) where graph data is insufficient or when the MCP server does not cover the needed query.

### Lite Mode (MCP Memory)

- Call `mcp__memory__search_nodes(...)` for cached analysis results.
- If cache miss (no matching nodes found): fall through to Minimal mode behavior, then cache the results back via `mcp__memory__create_entities(...)` for future queries.

### Minimal Mode (Claude reading)

- Read source files directly via Read, Glob, and Grep tools.
- Produce structured output from direct file analysis.
- No persistence between sessions.

---

## Additional Fields

After determining the mode, extract these optional fields from the parsed config:

### `sdd.project.name`

- If the key path `sdd.project.name` exists and has a non-empty string value, set `projectName` to that value.
- Otherwise, set `projectName: null`.

### `sdd.project.language`

- If the key path `sdd.project.language` exists and has a non-empty string value, set `language` to that value.
- Otherwise, set `language: null`.

These fields are informational. Missing values are not errors.

---

## Error Handling

### InvalidConfig Error

An `InvalidConfig` error occurs in two situations:

**1. Invalid YAML syntax:**

WHEN the file `.sdd/sdd-config.yaml` exists but contains invalid YAML
THEN report `InvalidConfig` with the parse error details
AND fall back to `mode: "minimal"`, `language: null`, `projectName: null`

**2. Unrecognized mode value:**

WHEN `sdd.infrastructure.mode` contains a value other than `"full"`, `"lite"`, or `"minimal"`
THEN report `InvalidConfig` with message: `"Unrecognized mode: '<value>'. Expected full, lite, or minimal."`
AND fall back to `mode: "minimal"`
AND still extract `language` and `projectName` if available

In both cases, include the error information in the output so the calling skill can decide whether to surface it to the user.

---

## Output Format

Return the following structured result:

```yaml
mode: "full" | "lite" | "minimal"
language: "<string>" | null
projectName: "<string>" | null
configPath: "<absolute-path>" | null
configExists: true | false
error: null | { type: "InvalidConfig", message: "<details>" }
```

### Examples

**Config file found with all fields:**

```yaml
mode: "full"
language: "java"
projectName: "my-service"
configPath: "/home/user/project/.sdd/sdd-config.yaml"
configExists: true
error: null
```

**Config file missing (INV-CFG-01):**

```yaml
mode: "minimal"
language: null
projectName: null
configPath: null
configExists: false
error: null
```

**Config file exists but mode field absent (INV-CFG-02):**

```yaml
mode: "minimal"
language: "python"
projectName: "data-pipeline"
configPath: "/home/user/project/.sdd/sdd-config.yaml"
configExists: true
error: null
```

**Config file exists with unrecognized mode (INV-CFG-03):**

```yaml
mode: "minimal"
language: "kotlin"
projectName: "app"
configPath: "/home/user/project/.sdd/sdd-config.yaml"
configExists: true
error:
  type: "InvalidConfig"
  message: "Unrecognized mode: 'hybrid'. Expected full, lite, or minimal."
```

**Config file exists but invalid YAML:**

```yaml
mode: "minimal"
language: null
projectName: null
configPath: "/home/user/project/.sdd/sdd-config.yaml"
configExists: true
error:
  type: "InvalidConfig"
  message: "Failed to parse .sdd/sdd-config.yaml: <parse error details>"
```

---

## Constraints

- This skill is read-only. It MUST NOT create, write, or modify any file (INV-CFG-04).
- Do not prompt the user for input. This skill runs non-interactively as a subroutine for other skills.
- Do not install dependencies or run package managers.
- Return the structured output and nothing else. No commentary, no suggestions, no next steps.
