---
name: sdd-doc-readme
description: "Generate or update project README with installation, usage, and architecture sections. Use WHEN creating a new README or refreshing an existing one. WHEN NOT: for CLAUDE.md use sdd-doc-generate-claude-md."
context: fork
argument-hint: "['generate' or 'update']"
---

# README Generator: $ARGUMENTS

Generate or update a project README.md file by analyzing the project's configuration files, source structure, and existing documentation. The README covers what a developer needs to get started: what the project is, how to install it, how to use it, and how the codebase is organized.

This skill is a Tier 3 support component. It runs in a forked context (isolated from the main conversation) and writes to `README.md` at the project root.

**Invariants:**

- **INV-README-01:** The skill MUST NOT delete or overwrite custom narrative sections in an existing README during update. Only sections with auto-detectable content (installation commands, architecture diagrams derived from code) MAY be refreshed.
- **INV-README-02:** Installation instructions MUST be derived from actual project configuration (package.json scripts, pyproject.toml dependencies, Makefile targets), not fabricated.
- **INV-README-03:** The generated README MUST NOT contain promotional language (P4 compliance). No "modern", "elegant", "sophisticated", "advanced", or "cutting-edge". Descriptions SHALL be factual and concise.
- **INV-README-04:** The skill MUST NOT include placeholder Lorem Ipsum text. Sections that cannot be auto-generated SHALL be marked with `<!-- TODO: ... -->` comments describing what to add.
- **INV-README-05:** This skill MUST NOT invoke other skills via the Skill tool or Task tool. It operates using Read, Glob, Grep, Write, and Edit tools only.

---

## Input

`$ARGUMENTS` contains an optional directive. If empty, the skill auto-detects the mode.

| Value | Meaning |
|-------|---------|
| `generate` | Create a README from scratch |
| `update` | Refresh an existing README while preserving custom sections |
| `<section>` (e.g., `installation`, `usage`) | Update only the specified section |
| _(empty)_ | Auto-detect: use "update" if `README.md` exists, "generate" if it does not |

---

## Phase 1: Project Analysis

Read the project's configuration files and source structure to collect metadata for the README.

### Step 1: Detect Package Manager Configuration

Check for the following configuration files at the project root. Read the first one found (in priority order). If multiple exist, read all of them -- they may describe different aspects of the project.

| File | Language/Ecosystem | Fields to Extract |
|------|-------------------|-------------------|
| `package.json` | Node.js / JavaScript / TypeScript | `name`, `description`, `version`, `scripts`, `dependencies`, `devDependencies`, `engines` |
| `pyproject.toml` | Python | `[project].name`, `[project].description`, `[project].version`, `[project].requires-python`, `[project].dependencies`, `[project.scripts]` |
| `Cargo.toml` | Rust | `[package].name`, `[package].description`, `[package].version`, `[package].edition`, `[dependencies]` |
| `go.mod` | Go | module name, Go version |
| `pom.xml` | Java (Maven) | `<groupId>`, `<artifactId>`, `<version>`, `<description>`, `<properties>` |
| `build.gradle` / `build.gradle.kts` | Java / Kotlin (Gradle) | `group`, `version`, plugins, dependencies |
| `Makefile` | Any | Targets (especially `install`, `build`, `test`, `run`) |
| `CMakeLists.txt` | C / C++ | `project()` name and version |
| `mix.exs` | Elixir | project name, version, deps |

### Step 2: Detect Project Structure

Use Glob to understand the project layout:

- Source directories: `src/`, `lib/`, `app/`, `cmd/`, `pkg/`, `internal/`
- Test directories: `tests/`, `test/`, `__tests__/`, `spec/`
- Documentation: `docs/`, `doc/`
- Entry points: `main.*`, `index.*`, `app.*`, `cli.*`, `__main__.py`
- Configuration: `.env.example`, `docker-compose.yml`, `Dockerfile`
- CI/CD: `.github/workflows/`, `.gitlab-ci.yml`, `Jenkinsfile`

### Step 3: Read Existing Documentation

Check for and read (if present):

- `LICENSE` or `LICENSE.md` -- extract license type
- `CLAUDE.md` -- extract project description and build commands
- `CONTRIBUTING.md` -- note its existence for linking
- Existing `README.md` -- needed for update mode

### Step 4: Detect CLI Entry Points

Search for CLI commands or entry points:

- **Python**: `[project.scripts]` in `pyproject.toml`, `console_scripts` in `setup.cfg`/`setup.py`
- **Node.js**: `"bin"` in `package.json`, `"scripts.start"` in `package.json`
- **Go**: `cmd/` directory, `main.go` files
- **Rust**: `src/main.rs`, `[[bin]]` entries in `Cargo.toml`
- **Java**: Classes with `public static void main`, Spring Boot main classes

---

## Phase 2: Generate Mode

WHEN `$ARGUMENTS` is "generate" or no README.md exists, create a new README from scratch.

### Template

Generate the README using the following 7-section structure. Populate each section from the data collected in Phase 1. If data for a section is unavailable, use a `<!-- TODO: ... -->` marker (INV-README-04).

````markdown
# <Project Name>

<One-line description from package configuration. If no description field exists, derive from project structure and purpose.>

## Prerequisites

<Runtime and toolchain requirements derived from configuration files.>

Examples per ecosystem:
- Python: "Python >= 3.10" (from `requires-python`)
- Node.js: "Node.js >= 18" (from `engines.node`)
- Rust: "Rust >= 1.70" (from `rust-version` or `edition`)
- Go: "Go >= 1.21" (from `go.mod`)
- Java: "JDK >= 17" (from `<maven.compiler.source>` or Gradle `sourceCompatibility`)

If no version constraints are found:
```
<!-- TODO: Add prerequisite versions (e.g., runtime version, required tools) -->
```

## Installation

<Step-by-step installation commands derived from package manager configuration (INV-README-02).>

Examples per ecosystem:
```bash
# Python (pip)
pip install -e .

# Python (uv)
uv sync

# Node.js
npm install

# Rust
cargo build

# Go
go build ./...

# Java (Maven)
mvn install

# Java (Gradle)
./gradlew build
```

If a Makefile exists with an `install` target:
```bash
make install
```

If no package manager is detected:
```
<!-- TODO: Add installation instructions -->
```

## Usage

<Usage examples derived from entry points and CLI commands found in Phase 1.>

Include:
- CLI commands (if entry points were detected)
- Library import examples (if the project is a library)
- Configuration examples (if `.env.example` or config templates exist)

If no entry points are detected:
```
<!-- TODO: Add usage examples showing how to run or import this project -->
```

## Architecture

<High-level overview of the project's component structure.>

Describe the directory layout and the purpose of each top-level directory.
For projects with clear module boundaries, list the modules and their roles.

Example format:
```
<project>/
  src/           # Source code
    core/        # Core logic
    api/         # API endpoints
  tests/         # Test suite
  docs/          # Documentation
```

Do NOT generate detailed class diagrams or internal implementation details.
Keep this section at the "I need to know where things are" level.

## Testing

<How to run tests, derived from test framework configuration.>

Examples per ecosystem:
```bash
# Python
pytest
# or: uv run pytest

# Node.js
npm test

# Rust
cargo test

# Go
go test ./...

# Java (Maven)
mvn test

# Java (Gradle)
./gradlew test
```

If a Makefile exists with a `test` target:
```bash
make test
```

If no test framework is detected:
```
<!-- TODO: Add test instructions -->
```

## License

<License type derived from LICENSE file or package configuration field.>

If a LICENSE file exists, identify the license type (MIT, Apache-2.0, GPL, etc.) and state it.
If a `license` field exists in package configuration, use it.
If neither exists:
```
<!-- TODO: Add license information -->
```
````

### Section Ordering

The 7 sections above MUST appear in the order listed. Additional sections (e.g., Configuration, Contributing, Deployment) MAY be added after Testing and before License if the project data supports them.

---

## Phase 3: Update Mode

WHEN `$ARGUMENTS` is "update" or specifies a section name, refresh an existing README while preserving custom content (INV-README-01).

### Step 1: Read Existing README

Read the current `README.md` and parse its section structure. Identify each section by its heading (lines starting with `##`).

### Step 2: Classify Sections

Classify each section as either **refreshable** or **custom**:

| Section | Classification | Refresh Source |
|---------|---------------|----------------|
| Prerequisites | Refreshable | Package config version constraints |
| Installation | Refreshable | Package config install commands |
| Testing | Refreshable | Test framework config |
| License | Refreshable | LICENSE file |
| Architecture | Refreshable | Project directory structure |
| Usage | Partially refreshable | CLI entry points (preserve narrative examples) |
| Any unlisted section | Custom | Preserve unchanged |

### Step 3: Merge Updates

For each refreshable section:

1. Re-derive the content from the current project configuration (same logic as Phase 2).
2. Replace the section content with the updated version.
3. Preserve any inline comments or notes the developer added within the section that are clearly custom (e.g., paragraphs explaining context, caveats, or non-standard setup).

For custom sections:

1. Preserve the section heading and all content unchanged.
2. Maintain the section's position relative to other sections.

### Step 4: Apply Changes

Use the Edit tool to update `README.md` with the merged content. If a specific section was requested via `$ARGUMENTS`, update only that section.

---

## Phase 4: Minimal Metadata Handling

WHEN no package manager configuration is found (NoProjectMetadata scenario):

1. Use the project root directory name as the project title.
2. Scan for source files to determine the primary language (by file extension frequency).
3. Generate a minimal README with all 7 sections, using `<!-- TODO: ... -->` markers for sections that cannot be populated.
4. The Architecture section CAN still be generated from directory structure analysis.
5. Report to the user that no package configuration was found and suggest which sections need manual completion.

---

## Output

After generating or updating the README, report the result:

```markdown
## README: $MODE

### File
- **Path**: README.md
- **Mode**: <generate | update | update (section: <name>)>

### Sections
| Section | Status |
|---------|--------|
| Title/Description | <generated | updated | preserved | TODO> |
| Prerequisites | <generated | updated | preserved | TODO> |
| Installation | <generated | updated | preserved | TODO> |
| Usage | <generated | updated | preserved | TODO> |
| Architecture | <generated | updated | preserved | TODO> |
| Testing | <generated | updated | preserved | TODO> |
| License | <generated | updated | preserved | TODO> |
| <custom sections> | preserved |

### Notes
- <any warnings, e.g., "No package configuration found", "3 custom sections preserved">
```

---

## Error Handling

### NoProjectMetadata

WHEN the project has no recognized package manager configuration AND no recognizable source files
THEN report:

```
Warning: NoProjectMetadata
No package manager configuration (package.json, pyproject.toml, Cargo.toml, go.mod, pom.xml,
build.gradle) or source files found in the project root.

Generated a minimal README with TODO markers for manual completion.
```

This is NOT a fatal error. The skill generates the minimal README and continues (see Phase 4).

---

## Constraints

- This skill SHALL NOT invoke other skills via the Skill tool or Task tool (INV-README-05).
- This skill SHALL NOT contain promotional language in generated content (INV-README-03). No "modern", "elegant", "sophisticated", "advanced", "cutting-edge", or similar terms.
- This skill SHALL NOT use Lorem Ipsum or filler text (INV-README-04). Use `<!-- TODO: ... -->` markers instead.
- Installation commands MUST be derived from actual project configuration (INV-README-02). Do not fabricate commands.
- Custom sections in an existing README MUST be preserved during update (INV-README-01).
- Language is English only. Tone is professional and objective.
- Do not install dependencies or run package managers.
- Do not include internal implementation details in the Architecture section. Keep it at the directory/component level.
