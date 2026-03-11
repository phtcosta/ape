---
name: sdd-methodology
description: "SDD development principles (P1-P4) and workflow track definitions with selection criteria. Foundation knowledge base for orchestrators and agents."
user-invocable: false
---

# sdd-methodology

Distilled reference for the SDD Toolkit's development principles and workflow tracks. Provides P1-P4 enforcement guidance and track selection criteria. Invoked by orchestrators and agents that need to apply SDD methodology during their work.

If `$ARGUMENTS` contains a task description, include a track recommendation based on the decision tree in the Track Selection section.

## SDD Development Principles

These four principles are non-negotiable and govern all code, comments, documentation, and specs.

### P1: Simplicity

Minimum complexity for the current task. Three similar lines are preferable to a premature abstraction. Direct call is preferable to indirection with one subscriber. No speculative features, no validation for impossible scenarios, no helpers for one-time operations.

**Correct behavior**: A utility function called from exactly one place is inlined at that call site.

**Violation**: Creating a helper class for an operation that only occurs once, or adding an abstract base class when each subclass has different logic.

### P2: Human-Readable Documentation

All docs (specs, proposals, design docs) must be narrative and self-contained. Explain *why*, not just *what*. Use WHEN/THEN/AND format with concrete values in scenarios. When behavior has a non-obvious reason, explain it inline.

**Correct behavior**: A developer reading only `docs/SDD.md` understands the full SDD methodology without needing to read any code or other document.

**Violation**: A spec that says "see implementation in src/parser.ts for details" -- if the reader needs to open source files to understand the spec, the spec is incomplete.

### P3: No Backward Compatibility

Dead or superseded code is deleted entirely. No adapters, shims, wrappers, `# removed` comments, or `_unused` renames. All changes must be complete: update all callers, verify no dangling references, one commit = one consistent state.

**Correct behavior**: When renaming a function, delete the old name. Update all callers. Grep to confirm zero references to the old name remain.

**Violation**: Keeping a re-export alias (`export { newName as oldName }`) or a deprecation wrapper that forwards calls from the old API to the new one.

### P4: Current-State Comments

Comments describe what the code does *now*. No migration history ("migrated from X", "replaces old Y"). No promotional language ("modern", "elegant", "advanced", "cutting-edge", "sophisticated"). Names describe function, not lineage (`process_tasks` not `process_tasks_v2`).

**Correct behavior**: A comment that says "Parses Python files using tree-sitter and extracts function signatures."

**Violation**: A comment that says "Migrated from rv-android" or "This is a modern approach to parsing."

## Workflow Tracks

The SDD Toolkit provides three workflow tracks. Track selection depends on whether the change requires design decisions -- not on file count.

### Full SDD

- **When**: Design decisions needed AND multi-module or architectural implications
- **Schema**: `sdd-full`
- **Phases**: Explore -> Propose -> Design -> Implement -> Verify -> Archive (6 phases)
- **Artifacts**: proposal -> specs -> design -> tasks (step by step, with user review at each phase)

Use Full SDD when the change introduces new behavior that crosses module boundaries, affects the system's architecture, or requires choosing between non-trivial alternatives.

### Fast-Forward SDD

- **When**: Design decisions needed AND single module with clear requirements
- **Schema**: `sdd-full`
- **Phases**: Explore -> FF -> Implement -> Close (4 phases)
- **Artifacts**: proposal -> specs -> design -> tasks (auto-generated in a single FF phase)

Use Fast-Forward when design decisions exist but the scope is contained to a single module and the requirements are well-understood. The FF phase generates proposal, specs, design, and tasks in one pass.

### Quick Path

- **When**: No design decisions -- mechanical task with clear plan
- **Schema**: `sdd-quick-path`
- **Phases**: Analyze -> Plan -> Execute+Verify (3 phases)
- **Artifacts**: plan -> tasks

Use Quick Path when the answer to "what should I do?" is obvious and the only question is "where are all the places I need to touch?" Bug fixes, cleanups, documentation updates, and refactors without new behavior all belong here.

## Track Selection

Follow this decision tree to select the correct track:

```
1. Does the change require design decisions?
   (choices between alternatives that affect behavior, interface, or architecture)

   NO  --> Quick Path
   YES --> continue to question 2

2. Does it cross module boundaries or have architectural implications?

   YES --> Full SDD
   NO  --> Fast-Forward SDD
```

Additional selection guidance:

| Question | Track |
|----------|-------|
| Introduces new behavior that must be documented in specs? | Full or FF SDD |
| Crosses module boundaries with architectural implications? | Full SDD |
| Single-module change with spec implications? | FF SDD |
| Removes/refactors without adding new documented behavior? | Quick Path |
| Bug fix, cleanup, or documentation update? | Quick Path |
| Requirements are crystal clear and the task is mechanical? | Quick Path |

**Anti-pattern**: Do not select Full SDD or FF SDD based on file count alone. A change that touches 45 files but is purely mechanical (removing dead references, updating config arrays, cleaning documentation) is Quick Path. The number of files determines whether to use subagent orchestration, not which workflow track to follow.

**When in doubt**: Start with Quick Path. Escalate to a higher track if the change turns out to need design decisions.

## Enforcement Checklist

Use these checks to verify P1-P4 compliance before completing any task.

### P1: Simplicity checks

- [ ] Every abstraction (class, interface, wrapper) has 3+ concrete use cases that exist today
- [ ] Count indirection layers: if a call passes through more than one intermediate layer before reaching real logic, justify each layer
- [ ] No speculative features: every code path is exercised by a current use case
- [ ] Composition over inheritance, flat over nested
- [ ] Validation only at system boundaries (user input, external APIs)

### P2: Human-Readable Documentation checks

- [ ] Can a developer understand each spec without opening any source file?
- [ ] Do scenarios use WHEN/THEN/AND format with concrete values?
- [ ] Are non-obvious behaviors explained inline with their reason?
- [ ] Are artifacts self-contained (no "see X for details" without reproducing the relevant content)?

### P3: No Backward Compatibility checks

- [ ] `grep` for any removed or renamed symbols -- zero remaining references in the codebase
- [ ] No adapters, shims, wrappers, re-exports, or aliases for old names
- [ ] No `# removed`, `# deprecated`, `_unused`, or commented-out blocks
- [ ] After the commit, the codebase is in a consistent state

### P4: Current-State Comments checks

- [ ] `grep -rE 'modern|elegant|sophisticated|advanced|cutting-edge' .` returns zero results
- [ ] No migration history in comments ("migrated from X", "replaces old Y", "was previously Z")
- [ ] Names describe function, not lineage (no `_v2`, `_new`, `_old` suffixes)
- [ ] Comments describe current behavior only
