---
name: sdd-planning
description: "Task breakdown and risk assessment with SDD track recommendation. Use WHEN planning a new task or evaluating scope. WHEN NOT: for implementation use sdd-feature or sdd-tdd."
argument-hint: "[task-description]"
context: fork
---

# Task Planning: $ARGUMENTS

Break down a task description into structured sub-tasks with risk assessment and workflow track recommendation. Planning is the first step of any non-trivial development task -- understanding scope, identifying risks, and determining the right level of ceremony before writing code.

This skill is a Tier 1 orchestrator that runs in forked context. It invokes analysis skills via the Skill tool to assess scope and impact, classifies risk, recommends a workflow track (Full SDD, Fast-Forward SDD, or Quick Path), and produces an ordered task breakdown.

**Read-only constraint:** You SHALL NOT use Edit, Write, or Bash tools. This skill produces a planning report only -- it does not modify any files, create code, or execute commands (INV-PLN-01).

**Invariants:**

- **INV-PLN-01:** This skill SHALL NOT modify any source files or create any code artifacts. It is strictly a planning tool.
- **INV-PLN-02:** The skill SHALL produce a workflow track recommendation for every task.
- **INV-PLN-03:** The track recommendation SHALL be based on sdd-methodology criteria: design decisions required? multi-module or architectural? -> track selection.
- **INV-PLN-04:** This skill MUST NOT include `allowed-tools` in its frontmatter. The read-only constraint and Skill tool usage are enforced via textual instruction.
- **INV-PLN-05:** This skill SHALL be under 500 lines.

**Tool usage:** This skill invokes `sdd-analyze-module` and `sdd-impact-analyzer` via the **Skill tool**. It does NOT use the Task tool. It does NOT use Edit, Write, or Bash.

---

## Input

`$ARGUMENTS` contains a description of the task to plan. This can be:

- A feature description (e.g., `"Add JWT authentication to all API endpoints"`)
- A refactoring description (e.g., `"Extract payment processing into a separate module"`)
- A bug fix description (e.g., `"Fix race condition in session management"`)
- A maintenance task (e.g., `"Upgrade database driver from v2 to v3"`)

If `$ARGUMENTS` is empty or too vague to determine scope, report `InsufficientContext` and STOP.

---

## Phase 1: Scope Analysis

Understand what the task affects by invoking analysis skills.

### Step 1.1: Identify Target Modules

From the task description in `$ARGUMENTS`, identify which module(s) or directory(ies) the task is likely to affect. Use the project structure to determine the target path.

If the task description mentions specific files, modules, or components, use those directly. If the description is abstract (e.g., "improve performance"), use Glob and Read to identify the relevant areas of the codebase.

### Step 1.2: Module Analysis

For each identified target module, invoke `sdd-analyze-module` via the Skill tool to understand its architecture.

```
Skill tool: skill="sdd-analyze-module", args="<target-module-path>"
```

From the module analysis, extract:
- Number of source files in the module
- Key components (classes, functions, interfaces)
- Internal structure and patterns
- Module boundaries

If the task affects multiple modules, invoke `sdd-analyze-module` for each one.

### Step 1.3: Impact Analysis

Invoke `sdd-impact-analyzer` via the Skill tool to assess the ripple effects of the planned change.

```
Skill tool: skill="sdd-impact-analyzer", args="<primary-target>"
```

From the impact analysis, extract:
- Direct dependents count and list
- Transitive dependents count
- Impact scope classification (isolated, moderate, broad, critical)
- Affected test files
- Test coverage status (covered, partial, uncovered)

### Step 1.4: Scope Summary

Combine the module analysis and impact analysis into a scope summary:

| Metric | Value |
|--------|-------|
| Target modules | Number of modules affected |
| Files in scope | Total source files across target modules |
| Direct dependents | Files that directly reference the target |
| Transitive dependents | Files indirectly affected |
| Impact scope | isolated / moderate / broad / critical |
| Test coverage | covered / partial / uncovered |

### Error: InsufficientContext

WHEN the task description is too vague to identify any target module or file
THEN return:
```
Error: InsufficientContext
Message: "The task description is too vague to determine scope. Provide more detail about which components, modules, or files are affected."
```
AND STOP

### Error: ModuleNotIdentifiable

WHEN the task description references components that cannot be found in the codebase
THEN return:
```
Error: ModuleNotIdentifiable
Message: "Cannot identify module(s) for the described task. The referenced components were not found in the codebase."
```
AND STOP

---

## Phase 2: Risk Assessment

Classify the overall risk based on the scope analysis from Phase 1.

### Risk Classification

| Level | Criteria | Action |
|-------|----------|--------|
| **Low** | Isolated change, < 5 files affected, no cross-module dependencies | Proceed with minimal ceremony |
| **Medium** | Single-module change, 5-15 files affected, contained impact | Plan contingency, review dependencies |
| **High** | Cross-module change, 15-30 files affected, broad impact | Consider incremental approach, test strategy |
| **Critical** | Architectural change, > 30 files affected, critical impact | Break into smaller changes, full specification |

### Risk Factors

Evaluate each factor and note whether it increases risk:

| Factor | Low Risk | High Risk |
|--------|----------|-----------|
| **Scope** | Single file or isolated utility | Multiple modules, shared interfaces |
| **Dependencies** | No external dependents | Many dependents across modules |
| **Test coverage** | Covered by tests | Partial or uncovered |
| **Complexity** | Simple logic, clear patterns | Complex logic, multiple code paths |
| **Reversibility** | Easy to revert | Difficult to undo (data migrations, API changes) |
| **Novelty** | Well-understood patterns | New technology, unfamiliar domain |

### Risk Mitigations

For each identified risk factor at medium or above, propose a specific mitigation:

- **Scope risk**: Break into smaller, independently deployable changes
- **Dependency risk**: Update dependents incrementally, maintain backward compatibility during transition
- **Coverage risk**: Write tests for affected components before making changes
- **Complexity risk**: Add intermediate validation steps, review with domain experts
- **Reversibility risk**: Design rollback strategy, use feature flags if applicable
- **Novelty risk**: Create a spike or proof-of-concept first

---

## Phase 3: Track Selection (INV-PLN-02, INV-PLN-03)

Determine the workflow track by evaluating two criteria from the SDD methodology.

### Criterion 1: Design Decisions Required?

A task requires design decisions when it involves:
- Architectural choices (new abstractions, structural patterns)
- API contracts (new endpoints, changed interfaces)
- New behavior that must be documented in specs
- Trade-offs between alternatives that affect system behavior

A task does NOT require design decisions when it involves:
- Mechanical changes (rename, reformat, simple bug fix)
- Removing/refactoring without adding new documented behavior
- Bug fixes with clear root cause and fix
- Documentation updates
- Configuration changes

### Criterion 2: Multi-Module or Architectural?

A task is multi-module or architectural when it:
- Crosses module boundaries with structural implications
- Changes shared interfaces consumed by multiple modules
- Introduces new system-wide patterns or conventions
- Affects the dependency graph at an architectural level

### Track Selection Logic

```
IF no design decisions required:
    -> Quick Path

IF design decisions required AND multi-module/architectural:
    -> Full SDD

IF design decisions required AND single-module with clear requirements:
    -> Fast-Forward SDD
```

### Track Details

| Track | When | Schema | Artifacts | Phases |
|-------|------|--------|-----------|--------|
| **Full SDD** | Design decisions + multi-module or architectural | `sdd-full` | proposal -> specs -> design -> tasks | 6 (explore, propose, design, implement, verify, archive) |
| **Fast-Forward SDD** | Design decisions + single module, clear requirements | `sdd-full` | proposal -> specs -> design -> tasks (auto-generated) | 4 (explore, ff, implement, close) |
| **Quick Path** | No design decisions, mechanical task | `sdd-quick-path` | plan -> tasks | 3 (analyze, plan, execute+verify) |

### Track Rationale

The rationale MUST explain:
1. Whether design decisions are needed and why (or why not)
2. Whether the task is single-module or multi-module
3. Why this track is the right level of ceremony for this task

### Anti-Pattern Warning

Do NOT select a track based on file count alone. A change that touches many files but is purely mechanical (remove references, update configs, clean documentation) is Quick Path. The number of files determines whether to use subagent orchestration, not which workflow track to follow.

---

## Phase 4: Task Breakdown

Decompose the work into ordered sub-tasks based on the scope analysis and risk assessment.

### Task Characteristics

| Property | Guideline |
|----------|-----------|
| **Scope** | Single responsibility per task |
| **Testable** | Clear completion criteria |
| **Ordered** | Dependencies between tasks are explicit |
| **Estimated** | Effort estimate for each task |

### Task Template

For each sub-task, provide:

```
### Task N: [Title]
**Description**: What to do
**Files**: Affected files or modules
**Acceptance**: How to verify completion
**Risk**: Low/Medium/High - brief risk note
**Depends on**: Task numbers (or "None")
**Estimate**: Relative effort (small/medium/large)
```

### Dependency Mapping

Define the execution order using dependency notation:

```
Task 1 -> Task 2      (Task 2 depends on Task 1)
Task 3 || Task 4      (Can run in parallel)
```

Identify the **critical path** -- the longest dependency chain that determines minimum completion time.

### Common Task Types

| Type | Example |
|------|---------|
| **Create** | Create new file, class, or function |
| **Modify** | Change existing code |
| **Delete** | Remove deprecated code |
| **Configure** | Update configuration |
| **Test** | Add or modify tests |
| **Document** | Update documentation |
| **Integrate** | Connect components |
| **Verify** | Run tests, lint, review |

---

## Output Format

Return the following structured report.

```markdown
# Planning Report

**Task**: <task description>
**Date**: <YYYY-MM-DD>

## Scope Analysis

### Module Analysis
<Summary of module analysis results from sdd-analyze-module>

### Impact Analysis
<Summary of impact analysis results from sdd-impact-analyzer>

### Scope Summary

| Metric | Value |
|--------|-------|
| Target modules | <count> |
| Files in scope | <count> |
| Direct dependents | <count> |
| Transitive dependents | <count> |
| Impact scope | <isolated/moderate/broad/critical> |
| Test coverage | <covered/partial/uncovered> |

## Risk Assessment

**Overall Risk**: <low/medium/high/critical>

### Risk Factors

| Factor | Level | Notes |
|--------|-------|-------|
| Scope | <low/medium/high> | <details> |
| Dependencies | <low/medium/high> | <details> |
| Test coverage | <low/medium/high> | <details> |
| Complexity | <low/medium/high> | <details> |
| Reversibility | <low/medium/high> | <details> |
| Novelty | <low/medium/high> | <details> |

### Mitigations

1. <specific mitigation for each medium+ risk factor>

## Track Recommendation

**Recommended Track**: <Full SDD / Fast-Forward SDD / Quick Path>

**Rationale**: <explanation of why this track was selected, covering design decisions and scope>

### Next Steps

<What to do after planning, based on the selected track:>
- Full SDD: "Create an OpenSpec change with `openspec new change 'gh<N>-<name>'` and begin with a proposal."
- Fast-Forward SDD: "Create an OpenSpec change and use `/opsx:ff` to auto-generate artifacts."
- Quick Path: "Create an OpenSpec change with `--schema sdd-quick-path` and write a plan document."

## Task Breakdown

### Task 1: [Title]
**Description**: <what to do>
**Files**: <affected files>
**Acceptance**: <verification criteria>
**Risk**: <Low/Medium/High>
**Depends on**: <task numbers or "None">
**Estimate**: <small/medium/large>

### Task 2: [Title]
...

## Dependencies

```
<dependency graph using Task N -> Task M notation>
```

**Critical Path**: <longest dependency chain>

## Summary

<One paragraph summarizing the plan: what will be done, the risk level, the recommended track, and the number of sub-tasks.>
```

---

## Constraints

- This skill is read-only. It SHALL NOT create, write, modify, or delete any file (INV-PLN-01).
- This skill SHALL NOT use Edit, Write, or Bash tools (INV-PLN-01).
- This skill invokes `sdd-analyze-module` and `sdd-impact-analyzer` via the Skill tool only. It does NOT use the Task tool.
- This skill does NOT invoke other Tier 1 orchestrator skills.
- Every planning report SHALL include a track recommendation with rationale (INV-PLN-02, INV-PLN-03).
- Do not prompt the user for input. Run non-interactively using the information in `$ARGUMENTS`.
- When the task description is insufficient, report `InsufficientContext` and STOP.
