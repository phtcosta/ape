---
name: sdd-detection
description: "Detect target project language, build system, test framework, and linter by analyzing file structure and plugin configs."
user-invocable: false
---

# sdd-detection

Detect the target project's programming language, build tool, test framework, and linter. This skill uses two detection layers: plugin-based detection (primary) and file-extension heuristic (fallback). It produces a structured result that other skills consume for language-aware operations.

This skill is read-only. It SHALL NOT create, modify, or delete any file.

**Invariants:**
- **INV-DET-01**: This skill SHALL NOT modify any files.
- **INV-DET-02**: Plugin-based detection SHALL take precedence over file-extension heuristic.
- **INV-DET-03**: When no language is detected, return a clear error instead of a guess.

## Input

`$ARGUMENTS` contains an optional project root path. When omitted, use the current working directory as the project root.

Examples:
- `(empty)` -- detect from cwd
- `/home/user/my-project` -- detect from specified path

## Plugin-Based Detection (Primary)

This layer reads plugin configuration files and matches them against the target project. A plugin match produces `confidence: "high"`.

### Step 1: List available plugins

List directories under `plugins/` (relative to the SDD Toolkit root, not the target project). Each subdirectory represents a language plugin.

Expected structure:
```
plugins/
  java/config.json
  python/config.json
  typescript/config.json
  cobol/config.json
```

### Step 2: Read each plugin's config.json

Each `config.json` contains a `detection` object with the following fields:

```json
{
  "language": "java",
  "displayName": "Java",
  "detection": {
    "extensions": [".java"],
    "buildFiles": ["pom.xml", "build.gradle", "build.gradle.kts"],
    "markers": ["src/main/java"]
  },
  "build": {
    "test": "mvn test",
    "lint": "checkstyle"
  }
}
```

Key fields for detection:
- `detection.extensions` -- file extensions associated with this language
- `detection.buildFiles` -- build system marker files (e.g., `pom.xml`, `pyproject.toml`, `tsconfig.json`)
- `detection.markers` -- additional directory/file markers that confirm the language
- `build.test` -- test command (extract framework name from this)
- `build.lint` -- linter command (extract linter name from this)

### Step 3: Match build markers against target project root

For each plugin, check whether any file listed in `detection.buildFiles` exists in the target project root directory.

```
FOR each plugin in plugins/:
  Read plugins/<language>/config.json
  FOR each buildFile in detection.buildFiles:
    Check if <project_root>/<buildFile> exists
    IF exists: record this plugin as a match, count matched markers
```

WHEN at least one plugin has a matching build file
THEN use that plugin's data to populate the result
AND set `confidence: "high"`

### Step 4: Determine build tool from matched marker

Map the matched build file to the build tool name:

| Build file | Build tool |
|---|---|
| `pom.xml` | maven |
| `build.gradle` or `build.gradle.kts` | gradle |
| `pyproject.toml` | pip/uv |
| `setup.py` or `setup.cfg` | pip |
| `requirements.txt` | pip |
| `tsconfig.json` or `package.json` | npm |
| `*.jcl` or `*.JCL` | jcl |

### Step 5: Extract test framework and linter

Read the `build` section of the matched plugin's config.json:

- **testFramework**: Derive from the `build.test` field. For example, `"mvn test"` implies `junit`; `"pytest"` implies `pytest`; `"vitest run"` implies `vitest`.
- **linter**: Derive from the `build.lint` field. For example, `"ruff check ."` implies `ruff`; `"eslint ."` implies `eslint`.

If the `build` section does not include `lint` or `test`, set the corresponding output field to `"unknown"`.

## File-Extension Fallback (Secondary)

When no plugin matches any build marker, fall back to file-extension counting. This layer produces `confidence: "medium"`.

### Step 1: Glob for source files

Collect all files in the target project root, excluding these directories:
- `node_modules`
- `.git`
- `build`
- `dist`
- `target`
- `__pycache__`

Use a recursive listing with these exclusions applied.

### Step 2: Count extensions

Tally file extensions across all collected files. Group extensions by language using the plugin `detection.extensions` mappings as a reference:

| Extensions | Language |
|---|---|
| `.java` | java |
| `.py` | python |
| `.ts`, `.tsx` | typescript |
| `.js`, `.jsx` | javascript |
| `.go` | go |
| `.rs` | rust |
| `.cbl`, `.cob`, `.CBL`, `.COB` | cobol |
| `.rb` | ruby |
| `.cs` | csharp |
| `.kt`, `.kts` | kotlin |
| `.swift` | swift |
| `.c`, `.h` | c |
| `.cpp`, `.hpp`, `.cc` | cpp |

### Step 3: Determine dominant language

Select the language with the highest file count. This becomes the detected language.

WHEN a dominant language is found via extension counting
THEN set `confidence: "medium"`
AND set `pluginUsed: null`
AND set `buildTool`, `testFramework`, `linter` to `"unknown"` (no plugin data available)

## Multiple Language Handling

When multiple plugins match (i.e., more than one plugin has build files present in the target project):

1. Count the number of matched `detection.buildFiles` for each plugin.
2. Select the plugin with the most matched build markers.
3. If tied, select the plugin whose `detection.extensions` match the most source files in the project.

This ensures monorepo-style projects with multiple build systems resolve to the primary language. Note the secondary language(s) in the output so callers are aware of multi-language projects.

## Error Handling

### NoLanguageDetected

WHEN no plugin matches any build marker
AND the file-extension fallback finds no recognized source files (or the project directory is empty)
THEN return the following error:

```
Error: NoLanguageDetected
Message: "Could not detect the project language. No plugin build markers matched and no recognized source file extensions were found."
Suggestion: "Configure the project language manually using sdd-config-reader, or verify that the project root path is correct."
```

Do not guess a language. An explicit error is preferable to an incorrect detection.

## Output Format

Return a structured result with these fields:

```yaml
language: "java"          # detected language identifier
buildTool: "maven"        # detected build tool
testFramework: "junit"    # detected test framework
linter: "checkstyle"      # detected linter
confidence: "high"        # "high" (plugin match) or "medium" (extension heuristic)
pluginUsed: "java"        # plugin name if matched, null if heuristic
```

### Field definitions

| Field | Type | Description |
|---|---|---|
| `language` | string | Language identifier, lowercase (e.g., `"java"`, `"python"`, `"typescript"`, `"cobol"`) |
| `buildTool` | string | Build tool name (e.g., `"gradle"`, `"maven"`, `"pip/uv"`, `"npm"`, `"go"`) or `"unknown"` |
| `testFramework` | string | Test framework name (e.g., `"junit"`, `"pytest"`, `"vitest"`, `"go test"`) or `"unknown"` |
| `linter` | string | Linter name (e.g., `"checkstyle"`, `"ruff"`, `"eslint"`, `"golangci-lint"`) or `"unknown"` |
| `confidence` | string | `"high"` when a plugin matched, `"medium"` when determined by file-extension heuristic |
| `pluginUsed` | string or null | Name of the matched plugin directory, or `null` if no plugin matched |
| `secondaryLanguages` | string[] or null | Other detected languages when multiple plugins match, or `null` if only one language detected |

### Example: Plugin match (high confidence)

Given a project with `pom.xml` at root and the java plugin at `plugins/java/config.json`:

```yaml
language: "java"
buildTool: "maven"
testFramework: "junit"
linter: "checkstyle"
confidence: "high"
pluginUsed: "java"
```

### Example: Heuristic match (medium confidence)

Given a project with 45 `.go` files, 3 `.md` files, and no matching plugin:

```yaml
language: "go"
buildTool: "unknown"
testFramework: "unknown"
linter: "unknown"
confidence: "medium"
pluginUsed: null
```
