# SDD Usage Workflow

Self-contained guide for using the SDD (Spec-Driven Development) process on any project where the SDD Toolkit is installed. This is the primary reference for both Claude Code and human developers after running `setup.sh`.

**Key principle**: The workflow is fluid, not rigid. Following OpenSpec's principle: "no phase gates, work on what makes sense." Artifact dependencies are enablers, not gates.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [What is SDD?](#2-what-is-sdd)
3. [Getting Started](#3-getting-started)
4. [Track Selection](#4-track-selection)
5. [Full SDD Walkthrough](#5-full-sdd-walkthrough)
6. [Fast-Forward Walkthrough](#6-fast-forward-walkthrough)
7. [Quick Path Walkthrough](#7-quick-path-walkthrough)
8. [Skill Reference](#8-skill-reference)
9. [Writing Specifications](#9-writing-specifications)
10. [Subagent Orchestration](#10-subagent-orchestration)
11. [Common Workflows](#11-common-workflows)

---

## 1. Prerequisites

Tools required to use the SDD Toolkit, grouped by when they are needed.

### Required (All Modes)

| Tool | Purpose | Install |
|------|---------|---------|
| **Claude CLI** (`claude`) | Claude Code agent that executes skills | [claude.ai/code](https://claude.ai/code) |
| **SDD Toolkit repository** | Source of skills, agents, schemas, and MCP server | Must remain at install path — the path is baked into `.mcp.json` |

### Required for Full Mode

| Tool | Purpose | Install |
|------|---------|---------|
| **Docker** | Runs Neo4j knowledge graph container | [docker.com](https://docs.docker.com/get-docker/) |
| **Python 3.11+** | Runs the MCP server | `python3 --version` to check |
| **uv** | Python package manager for MCP server | `curl -LsSf https://astral.sh/uv/install.sh \| sh` |

### Required for OpenSpec Workflow

| Tool | Purpose | Install |
|------|---------|---------|
| **openspec CLI** | Manages change artifacts (proposal, specs, design, tasks) | `npm install -g @fission-ai/openspec` |

### Required for MCP Tools

| Tool | Purpose | Install |
|------|---------|---------|
| **Node.js / npx** | Runs MCP servers (sequential-thinking, memory, context7) | [nodejs.org](https://nodejs.org/) |

Skills still function without MCP tools, but MCP-dependent features (persistent memory, structured thinking, documentation lookup) are unavailable.

### Optional

| Tool | Purpose | Notes |
|------|---------|-------|
| **Git** | Required for `/sdd-debug-regression`, `/sdd-retrospective`, `/sdd-release` | The core SDD workflow (specs, changes, skills) works without git |

---

## 2. What is SDD?

Spec-Driven Development is a software development approach in which structured specifications precede and guide code generation by AI coding agents. Instead of ad-hoc prompts, the developer writes a specification capturing requirements, constraints, and expected behavior. The AI agent uses this specification as persistent context for implementation.

The SDD Toolkit follows **spec-anchored SDD**: specifications persist and document the system, but code remains the maintained artifact. The OpenSpec framework provides the process layer (what to build and why); sdd-* skills provide the execution layer (how to build it).

---

## 3. Getting Started

After running `setup.sh`, your project has:

- **Skills**: `sdd-*` skills in `.claude/skills/` (analysis, implementation, documentation, testing)
- **Schemas**: OpenSpec schemas in `openspec/schemas/` defining workflow phases
- **Configuration**: `.sdd/sdd-config.yaml` with operating mode and project metadata
- **Templates**: Spec, design, and PRD templates in `docs/templates/`
- **This guide**: `docs/SDD-WORKFLOW.md`

To start your first change:

1. Read this guide (you are here)
2. Run `/sdd-onboarder` to get a structural overview of your project
3. Optionally create a PRD from `docs/templates/prd-template.md`
4. Write initial specs in `openspec/specs/`
5. Start your first change with `/opsx:new`

---

## 4. Track Selection

The SDD Toolkit uses three workflow tracks. Track selection depends on whether the change requires design decisions — not on file count.

```
Does the task require design decisions?
│
├── No  → Quick Path
│         (mechanical changes, clear plan, no ambiguity)
│
└── Yes → Does it affect multiple modules or architecture?
          │
          ├── Yes → Full SDD
          │         (multi-module, architectural decisions, complex scope)
          │
          └── No  → Fast-Forward SDD
                    (single module, clear requirements, design needed)
```

### Track Comparison

| Track | When | Phases | Entry Point |
|-------|------|--------|-------------|
| **Full SDD** | Design decisions + multi-module/architectural | Explore → Propose → Specs → Design → Tasks → Implement → Verify → Archive | `/opsx:new` |
| **Fast-Forward** | Design decisions + single module, clear requirements | Explore → FF (all artifacts) → Implement → Verify → Archive | `/opsx:ff` |
| **Quick Path** | No design decisions, mechanical/clear plan | Analyze → Plan → Execute + Verify | `/opsx:new` with `sdd-quick-path` schema |

### Decision Examples

| Task | Track | Why |
|------|-------|-----|
| "Add user authentication with OAuth" | Full SDD | Multiple modules affected, requires design decisions about session management, token storage, middleware |
| "Add a caching layer to the API" | Fast-Forward | Design decisions needed (cache strategy, invalidation), but scope is contained to one module |
| "Rename all instances of `oldFunction` to `newFunction`" | Quick Path | No design decisions — purely mechanical text replacement |
| "Update all dependencies to latest versions" | Quick Path | Mechanical execution with clear success criteria |
| "Add dark mode support" | Full SDD | Affects UI components across the system, requires theme architecture decisions |
| "Fix the null pointer in `processOrder()`" | Quick Path | Root cause is known, fix is localized |

---

## 5. Full SDD Walkthrough

The Full SDD track is for changes that require design decisions and affect multiple modules. It produces a complete set of artifacts before implementation begins.

### Phase 1: Explore

**Skill**: `/opsx:explore`
**Produces**: Understanding of the problem space, requirements, constraints

Use this phase to think through the change before committing to an approach. Explore is a thinking partner — it helps you investigate problems, clarify requirements, and consider alternatives. No artifacts are created.

### Phase 2: Create Change + Artifacts

**Skill**: `/opsx:new` (creates the change and first artifact — the proposal)

Then use `/opsx:continue` repeatedly to create each subsequent artifact:

| Artifact | What It Contains |
|----------|-----------------|
| **Proposal** (`proposal.md`) | Why this change exists, what changes, impact assessment |
| **Specs** (`specs/**/*.md`) | Delta specifications — what behavior is added/modified/removed |
| **Design** (`design.md`) | Architecture, API design, data flow, decisions with rationale |
| **Tasks** (`tasks.md`) | Ordered implementation checklist with verification steps |

Each artifact builds on the previous ones. The proposal frames the change, specs define the behavior, design explains how to implement it, and tasks break it into executable steps.

### Phase 3: Implement

**Skill**: `/opsx:apply`
**Input**: All artifacts from Phase 2

Work through tasks in `tasks.md` sequentially. Mark each task complete (`- [x]`) as you go. Use component skills directly during implementation:

- `/sdd-test-run` — run tests after each group
- `/sdd-qa-lint-fix` — auto-fix formatting after bulk edits
- `/sdd-verify` — full verification at checkpoints

Do NOT use orchestrator skills (`/sdd-feature`, `/sdd-tdd`, `/sdd-refactor`) during `/opsx:apply` — the artifacts already cover the analysis and planning that orchestrators would redo.

### Phase 4: Verify

**Skill**: `/opsx:verify`
**Input**: Implementation + artifacts

Validates that the implementation matches the change artifacts. Checks that all tasks are complete, specs are satisfied, and no regressions were introduced.

### Phase 5: Archive

**Skill**: `/opsx:archive`

Finalizes the change: syncs delta specs to main specs, moves the change directory to the archive, and cleans up.

### Full SDD Summary

```
/opsx:explore          → Think through the problem
/opsx:new              → Create change + proposal
/opsx:continue         → Create specs (repeat for each spec)
/opsx:continue         → Create design
/opsx:continue         → Create tasks
/opsx:apply            → Implement tasks
/opsx:verify           → Validate implementation
/opsx:sync             → Sync delta specs to main (optional, before archive)
/opsx:archive          → Finalize and archive
```

---

## 6. Fast-Forward Walkthrough

The Fast-Forward track is for changes where design decisions are needed but requirements are clear and scope is contained. It generates all artifacts in a single invocation.

### Phase 1: Explore (Optional)

**Skill**: `/opsx:explore`

Same as Full SDD — think through the problem if needed.

### Phase 2: Fast-Forward

**Skill**: `/opsx:ff`
**Produces**: All artifacts at once (proposal, specs, design, tasks)

The FF skill generates the complete artifact set in one invocation. This is faster than creating each artifact individually but requires that requirements are well-understood upfront.

### Phase 3: Implement

**Skill**: `/opsx:apply`

Same as Full SDD Phase 3 — work through tasks, use component skills.

### Phase 4: Verify + Archive

**Skills**: `/opsx:verify` → `/opsx:archive`

Same as Full SDD Phases 4-5.

### Fast-Forward Summary

```
/opsx:explore          → Think through the problem (optional)
/opsx:ff               → Generate all artifacts at once
/opsx:apply            → Implement tasks
/opsx:verify           → Validate implementation
/opsx:archive          → Finalize and archive
```

---

## 7. Quick Path Walkthrough

The Quick Path is for mechanical changes with no design decisions. It uses a simplified schema with fewer artifacts.

### Phase 1: Create Change

**Skill**: `/opsx:new` (specify `sdd-quick-path` schema)
**Produces**: Plan document with task breakdown

### Phase 2: Execute + Verify

**Skill**: `/opsx:apply`

Work through tasks. The plan document serves as both design and task list.

### Phase 3: Archive

**Skill**: `/opsx:archive`

### Quick Path Summary

```
/opsx:new --schema sdd-quick-path   → Create change + plan
/opsx:apply                          → Execute tasks
/opsx:archive                        → Finalize
```

---

## 8. Skill Reference

All skills organized by function. Invoke with `/skill-name` in Claude Code.

### OpenSpec Skills (Process Layer)

These skills manage the change lifecycle. They invoke sdd-* skills during implementation, but sdd-* skills never invoke OpenSpec skills.

| Skill | Command | Purpose |
|-------|---------|---------|
| Explore | `/opsx:explore` | Thinking partner for ideas, investigation, requirements |
| New Change | `/opsx:new` | Create a new change with proposal |
| Fast-Forward | `/opsx:ff` | Generate all artifacts at once |
| Continue | `/opsx:continue` | Create the next artifact in sequence |
| Apply | `/opsx:apply` | Execute tasks from tasks.md |
| Verify | `/opsx:verify` | Validate implementation against specs |
| Sync Specs | `/opsx:sync` | Sync delta specs to main specs |
| Archive | `/opsx:archive` | Archive a completed change |
| Bulk Archive | `/opsx:bulk-archive` | Archive multiple changes at once |
| Onboard | `/opsx:onboard` | Guided walkthrough of the full workflow |

### Orchestrator Skills (Tier 1 — Workflow)

Multi-phase workflows with user checkpoints. Use these for standalone tasks outside the OpenSpec flow.

| Skill | Command | When to Use |
|-------|---------|-------------|
| Feature | `/sdd-feature` | New features or capabilities |
| TDD | `/sdd-tdd` | Bug fixes or test-first features |
| Refactor | `/sdd-refactor` | Restructure code without changing behavior |
| Cleanup | `/sdd-cleanup` | Remove dead/unused code |
| Documenter | `/sdd-documenter` | Generate full project documentation |
| Tester | `/sdd-tester` | Test strategy, coverage analysis, test generation |
| Migrator | `/sdd-migrator` | Version upgrades, framework migrations |
| Onboarder | `/sdd-onboarder` | Onboard to a new codebase |

### Orchestrator Skills (Tier 1 — Composite)

Pipelines with no user checkpoints. Can be used both standalone and within workflows.

| Skill | Command | When to Use |
|-------|---------|-------------|
| Verify | `/sdd-verify` | 3-stage verification: tests + lint + complexity |
| Code Reviewer | `/sdd-code-reviewer` | Multi-dimension code review |
| Planning | `/sdd-planning` | Task breakdown and risk assessment |
| Architect | `/sdd-architect` | Architectural analysis |
| Security | `/sdd-security` | OWASP-based security analysis |

### Analysis Skills (Tier 2)

Read-only analysis skills. All are mode-aware (Full/Lite/Minimal).

| Skill | Command | Purpose |
|-------|---------|---------|
| Analyze Module | `/sdd-analyze-module [path]` | Module architecture: layers, components, patterns |
| Analyze File | `/sdd-analyze-file [path]` | Single file structural analysis |
| Analyze Complexity | `/sdd-analyze-complexity [path]` | Complexity hotspots: cyclomatic complexity, nesting, coupling |
| Analyze Dependencies | `/sdd-analyze-dependencies [path]` | Dependency graph: circular deps, coupling, layer violations |
| Analyze Dead Code | `/sdd-analyze-dead-code [path]` | Unreferenced classes, methods, imports |
| Impact Analyzer | `/sdd-impact-analyzer [target]` | Reverse dependencies, risk assessment, affected tests |

### Support Skills (Tier 3)

| Skill | Command | Purpose |
|-------|---------|---------|
| Test Run | `/sdd-test-run [path]` | Run test suite, report pass/fail/skip |
| Test Add | `/sdd-test-add [path]` | Generate tests for untested code |
| Debug Regression | `/sdd-debug-regression [test]` | Investigate test regressions via git history (requires git) |
| QA Lint | `/sdd-qa-lint [path]` | Run linter, report issues |
| QA Lint Fix | `/sdd-qa-lint-fix [path]` | Auto-fix lint issues |
| Doc Architecture | `/sdd-doc-architecture [path]` | Architecture documentation |
| Doc ADR | `/sdd-doc-adr [title]` | Architecture Decision Records |
| Doc NFR | `/sdd-doc-nfr [path]` | Non-functional requirements mapping |
| Doc Code | `/sdd-doc-code [path]` | Code-level documentation (docstrings) |
| Doc README | `/sdd-doc-readme [path]` | Project README generation |
| Doc CLAUDE.md | `/sdd-doc-generate-claude-md [path]` | CLAUDE.md generation |
| Docs Sync | `/sdd-docs-sync [path]` | Check documentation freshness |
| Detection | `/sdd-detection` | Detect language, build system, test framework |
| Config Reader | `/sdd-config-reader` | Read `.sdd/sdd-config.yaml` |
| Methodology | `/sdd-methodology` | SDD principles and track definitions |
| Extract | `/sdd-extract [path]` | Populate Neo4j graph (Full mode only) |
| Setup | `/sdd-setup [target]` | Install SDD Toolkit on a project |
| Risk | `/sdd-risk [target]` | Technical risk assessment |
| Retrospective | `/sdd-retrospective [scope]` | Data-driven retrospective from git history (requires git) |
| Release | `/sdd-release` | Version, changelog, git tag (requires git) |

---

## 9. Writing Specifications

Specifications in SDD use precise keywords from RFC 2119 and structured scenario formats.

### RFC 2119 Keywords

| Keyword | Meaning |
|---------|---------|
| **SHALL** / **SHALL NOT** | Absolute requirement or prohibition. The implementation must conform. |
| **MUST** / **MUST NOT** | Same force as SHALL. Used for invariants and hard constraints. |
| **SHOULD** / **SHOULD NOT** | Recommended but may be ignored with documented justification. |
| **MAY** | Truly optional. Implementations are free to include or omit. |

### Scenario Format: WHEN / THEN / AND

Scenarios describe behavior with concrete values, not abstract descriptions.

**Example:**

```
WHEN a user submits a login form with email "user@example.com" and password "correct-password"
THEN the system SHALL return HTTP 200
AND the response body SHALL contain a JWT token with expiry of 3600 seconds
```

**Anti-pattern (too abstract):**

```
WHEN a user logs in
THEN the system should return a success response with a token
```

### Invariants: INV-XX-NN

Invariants are verifiable assertions that must hold at all times. They are prefixed with `INV-<DOMAIN>-<NN>`.

**Example:**

```
INV-AUTH-01: The authentication service SHALL reject tokens older than 3600 seconds.
INV-AUTH-02: Password hashes SHALL use bcrypt with a cost factor of at least 12.
```

### Specification Structure

Use the spec template at `docs/templates/spec-template.md`. A specification includes:

- **Purpose**: What the capability does and why it exists
- **Data Contracts**: Input, output, side-effects, errors
- **Invariants**: Testable assertions that must always hold
- **Requirements**: Behavioral requirements with WHEN/THEN/AND scenarios

---

## 10. Subagent Orchestration

For large changes, Claude Code can dispatch parallel subagents to work on independent task groups simultaneously.

### When to Use Subagent Dispatch

Use subagent dispatch when a change touches **20+ files** across **3+ independent groups**. Below this threshold, sequential execution is simpler.

### How It Works

Add HTML comment hints at the top of `tasks.md` to guide dispatch:

```html
<!-- Subagent dispatch hints:
     - Group 1 must complete first — Groups 2, 3 depend on it.
     - Group 2 and Group 3 are independent and can run in parallel.
     - Group 4 (Verification) must run after Groups 1-3.
     - Critical path: 1 → 2/3 (parallel) → 4. -->
```

During `/opsx:apply`, the hints inform whether to dispatch parallel agents (via the Task tool) or execute sequentially. Each subagent receives:

- The task group to implement
- Read access to all artifacts (proposal, specs, design)
- Write access to the codebase

### Constraints

- Subagents cannot spawn other subagents (Claude Code limitation)
- Subagents share the filesystem — coordinate writes to avoid conflicts
- Use verification tasks after parallel groups to catch integration issues

---

## 11. Common Workflows

### Bug Fix

**Recommended track**: Quick Path (if root cause is known) or Full SDD (if investigation is needed)

**Quick Path sequence**:
1. `/opsx:new --schema sdd-quick-path` — create change with plan
2. `/opsx:apply` — fix the bug, add regression test
3. `/sdd-verify` — run tests + lint
4. `/opsx:archive` — finalize

**Standalone (no OpenSpec)**:
1. `/sdd-tdd` — test-first bug fix with RED-GREEN-REFACTOR cycle

### New Feature

**Recommended track**: Full SDD (multi-module) or Fast-Forward (single module)

**Full SDD sequence**:
1. `/opsx:explore` — investigate requirements
2. `/opsx:new` → `/opsx:continue` (x3-4) — create all artifacts
3. `/opsx:apply` — implement tasks
4. `/opsx:verify` → `/opsx:archive` — validate and finalize

**Standalone (no OpenSpec)**:
1. `/sdd-feature` — full feature lifecycle

### Refactoring

**Recommended track**: Quick Path (mechanical refactor) or Full SDD (architectural restructuring)

**Quick Path sequence**:
1. `/opsx:new --schema sdd-quick-path` — plan the refactoring
2. `/opsx:apply` — execute changes
3. `/sdd-verify` — verify behavior is preserved
4. `/opsx:archive` — finalize

**Standalone (no OpenSpec)**:
1. `/sdd-refactor` — safe refactoring with pre/post verification

### Documentation Update

**Recommended track**: Quick Path

**Sequence**:
1. `/sdd-docs-sync` — check which docs are stale
2. `/sdd-documenter` — regenerate documentation
3. `/sdd-verify` — verify consistency

### Migration

**Recommended track**: Full SDD

**Sequence**:
1. `/opsx:new` → `/opsx:continue` (x3-4) — plan the migration in artifacts
2. `/opsx:apply` — incremental execution with verification at each step
3. `/opsx:verify` → `/opsx:archive` — validate and finalize

**Standalone (no OpenSpec)**:
1. `/sdd-migrator` — coordinated migration with halt-on-failure
