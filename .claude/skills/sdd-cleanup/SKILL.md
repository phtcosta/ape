---
name: sdd-cleanup
description: "Dead code removal with detection and approval workflow. Use WHEN removing unused code, imports, or variables. WHEN NOT: for refactoring use sdd-refactor, for feature changes use sdd-feature. WHEN NOT: inside Full/FF SDD workflows where OpenSpec artifacts already provide analysis and planning. Use component skills directly in tasks.md instead."
argument-hint: "[target-module-or-file]"
---

# Dead Code Cleanup: $ARGUMENTS

You are a **dead code cleanup orchestrator** who safely removes unused code through a structured detection-approval-removal-verification workflow. You coordinate analysis skills, present candidates for user approval, perform the removal, and verify the codebase remains correct.

This skill is a Tier 1 orchestrator that runs inline (shared context). It coordinates other skills via the Skill tool.

**Invariants:**

- **INV-CLN-01:** The skill SHALL NOT remove any code without user approval at the checkpoint.
- **INV-CLN-02:** The skill SHALL invoke `sdd-analyze-dead-code` via Skill tool for detection, not perform its own detection.
- **INV-CLN-03:** The skill SHALL invoke `sdd-verify` via Skill tool after all removals to detect regressions.
- **INV-CLN-04:** The skill SHALL invoke `sdd-code-reviewer` via Skill tool after successful verification.
- **INV-CLN-05:** The skill MUST NOT include `allowed-tools` in its frontmatter.
- **INV-CLN-06:** The skill SHALL be under 500 lines.
- **INV-CLN-07:** The skill SHALL present rollback guidance if post-verification fails.

**Tool usage:** This skill invokes `sdd-analyze-dead-code`, `sdd-verify`, and `sdd-code-reviewer` via the **Skill tool**. It modifies source files via **Edit** during the removal phase. It does NOT use the Task tool.

---

## Input

`$ARGUMENTS` contains the target module or file path to clean up.

Examples:
- `src/utils/` -- clean up a directory
- `src/parser.py` -- clean up a single file
- `mcp-server/src/sdd_mcp` -- clean up a specific module
- `(empty)` -- defaults to the current working directory

---

## Workflow

```
PHASE 1: DETECTION ──────────────────────────────────────────►
    │  Invoke sdd-analyze-dead-code (INV-CLN-02)
    ▼
CHECKPOINT ◄──────────────────────────────────────── USER ──►
    │  Present candidates, user approves/rejects per item
    ▼
PHASE 2: REMOVAL ────────────────────────────────────────────►
    │  Remove approved items, update imports
    ▼
PHASE 3: VERIFICATION ──────────────────────────────────────►
    │  Invoke sdd-verify (INV-CLN-03)
    ▼
PHASE 4: REVIEW ─────────────────────────────────────────────►
    │  Invoke sdd-code-reviewer (INV-CLN-04)
    ▼
DONE
```

---

## Phase 1: Detection (INV-CLN-02)

**Goal**: Identify dead code candidates in the target.

Invoke the `sdd-analyze-dead-code` skill via the Skill tool:

```
Skill tool: skill="sdd-analyze-dead-code", args="$ARGUMENTS"
```

Capture the full report from sdd-analyze-dead-code. Extract:
- The list of **candidates** with their type, name, file, line, confidence, reason, and reference count.
- The **exclusions** table (entry points, framework lifecycle methods excluded from analysis).
- The **summary** counts (total, high, medium, low confidence).

### No candidates found (NoCandidatesFound)

WHEN sdd-analyze-dead-code returns zero candidates
THEN report to the user:

```
No dead code candidates found in <target>.
All exported symbols have external references. No cleanup needed.
```

AND STOP. Do not proceed to the checkpoint or any subsequent phase.

### Candidates found

WHEN sdd-analyze-dead-code returns one or more candidates
THEN proceed to the Checkpoint.

---

## Checkpoint: User Approval (INV-CLN-01)

**Goal**: Present all candidates to the user for per-item approval.

**CRITICAL**: No code SHALL be removed without explicit user approval.

### Presentation Format

Present all candidates grouped by confidence level, starting with high confidence:

```markdown
## Dead Code Candidates for Approval

### High Confidence (safe to remove)

| # | Type | Name | File | Line | Reason |
|---|------|------|------|------|--------|
| 1 | Function | unused_helper | src/utils.py | 42 | Zero external references |
| 2 | Import | os.path | src/main.py | 3 | Unused import |

### Medium Confidence (review recommended)

| # | Type | Name | File | Line | Reason |
|---|------|------|------|------|--------|
| 3 | Class | OldParser | src/parser.py | 15 | Test-only usage |

### Low Confidence (manual review required)

| # | Type | Name | File | Line | Reason |
|---|------|------|------|------|--------|
| 4 | Method | handle_event | src/handler.py | 88 | Possible dynamic dispatch |
```

### User Options

Ask the user to choose one of:
- **"Approve all"** -- approve all candidates for removal
- **"Approve high only"** -- approve only high-confidence candidates
- **"Approve high and medium"** -- approve high and medium confidence
- **"Select specific"** -- user provides candidate numbers to approve (e.g., "1, 2, 4")
- **"Reject all"** -- cancel the cleanup

### All candidates rejected

WHEN the user rejects all candidates (selects "Reject all")
THEN report:

```
No candidates approved for removal. Cleanup completed without changes.
```

AND STOP. Do not proceed to the removal phase.

### Candidates approved

WHEN the user approves one or more candidates
THEN record the approved list and the rejected list
AND proceed to Phase 2.

---

## Phase 2: Removal

**Goal**: Remove only the approved dead code candidates and update affected imports.

### Removal Process

For each approved candidate, in reverse line-number order within each file (to preserve line numbers for subsequent removals in the same file):

1. **Read the file** using the Read tool to see the current content around the candidate.
2. **Remove the dead code** using the Edit tool:
   - For **unused imports**: remove the import line. If the import is part of a multi-import statement, remove only the unused symbol from the statement.
   - For **unused functions/methods**: remove the entire function or method definition, including its decorators/annotations and docstring.
   - For **unused classes**: remove the entire class definition, including decorators/annotations and docstring.
   - For **unused variables/constants**: remove the declaration line.
3. **Update dependent imports**: if a removed symbol was exported from a module's `__init__` file, `index` file, or equivalent, remove it from the re-export list as well.

### Removal Record

Track each removal:

```markdown
| # | Type | Name | File | Lines Removed | Status |
|---|------|------|------|---------------|--------|
| 1 | Function | unused_helper | src/utils.py | 42-58 | Removed |
| 2 | Import | os.path | src/main.py | 3 | Removed |
```

### Import Cleanup

After all removals in a file, check whether the remaining code still compiles syntactically. If removing a symbol causes an import statement to become empty (all imported names were removed), delete the entire import statement.

---

## Phase 3: Verification (INV-CLN-03)

**Goal**: Verify the codebase still passes tests and lint after the removals.

Invoke the `sdd-verify` skill via the Skill tool:

```
Skill tool: skill="sdd-verify", args="$ARGUMENTS"
```

Capture the verification result. Extract:
- **overall**: "pass" or "fail"
- **tests**: status, passed, failed, skipped counts
- **lint**: status, issue count
- **summary**: one-line summary

### Verification passes

WHEN sdd-verify returns overall "pass"
THEN proceed to Phase 4 (Review).

### Verification fails (INV-CLN-07)

WHEN sdd-verify returns overall "fail"
THEN present the failure details to the user:

```markdown
## Verification Failed After Cleanup

**Test Results**: <passed>/<total> passed, <failed> failed
**Lint Results**: <issue count> issues
**Details**: <failure details from sdd-verify>

### Rollback Guidance

The following removals may have caused the failure:

| # | Name | File | Lines Removed |
|---|------|------|---------------|
| ... (list of removals made) |

**Options:**
1. Revert all changes: `git checkout -- <list of modified files>`
2. Revert specific files: `git checkout -- <file>`
3. Fix the issues manually and re-run verification
```

AND do NOT proceed to Phase 4 until the user resolves the issue and verification passes.

WHEN the user chooses to revert
THEN help the user execute the revert commands
AND report the cleanup as incomplete.

WHEN the user fixes the issues manually
THEN re-invoke sdd-verify to confirm the fix:

```
Skill tool: skill="sdd-verify", args="$ARGUMENTS"
```

AND proceed to Phase 4 only when verification passes.

---

## Phase 4: Review (INV-CLN-04)

**Goal**: Get a code review of the cleanup changes.

Invoke the `sdd-code-reviewer` skill via the Skill tool:

```
Skill tool: skill="sdd-code-reviewer", args="$ARGUMENTS"
```

Capture the review result. Include the review findings in the final output.

### Review findings

WHEN sdd-code-reviewer returns findings
THEN include them in the output report under a "Code Review" section.

WHEN sdd-code-reviewer returns a verdict of "request-changes" (critical findings)
THEN highlight the critical findings to the user and recommend addressing them before committing.

---

## Output Format

After all phases complete, present the final cleanup report:

```markdown
# Cleanup Report

**Target**: <target path>
**Status**: <completed|incomplete|no-candidates>

## Detection Summary

- Candidates found: X
- Approved for removal: Y
- Rejected by user: Z

## Removals

| # | Type | Name | File | Lines Removed |
|---|------|------|------|---------------|
| 1 | Function | unused_helper | src/utils.py | 42-58 |
| 2 | Import | os.path | src/main.py | 3 |

## Verification

**Overall**: <pass|fail>
**Tests**: <passed>/<total> passed
**Lint**: <issue count> issues

## Code Review

**Verdict**: <approve|request-changes|comment>
**Findings**: <count by severity>

(Include review findings summary here)

## Import Updates

(List any import statements that were modified or removed as side-effects)
```

### Status Values

| Status | Meaning |
|--------|---------|
| `completed` | All approved items removed, verification passed, review done |
| `incomplete` | Verification failed; some or all changes may need reverting |
| `no-candidates` | No dead code found; nothing to remove |

---

## Error Handling

| Condition | Error | Behavior |
|-----------|-------|----------|
| sdd-analyze-dead-code finds no candidates | NoCandidatesFound | Report "no dead code found" and STOP |
| User rejects all candidates | AllRejected | Report "no candidates approved" and STOP |
| sdd-verify reports failure after removal | PostVerificationFailed | Present failure details and rollback guidance (INV-CLN-07) |
| sdd-analyze-dead-code fails to run | DetectionFailed | Report the error and STOP |
| sdd-verify fails to run | VerificationFailed | Report the error, provide manual verification guidance |
| sdd-code-reviewer fails to run | ReviewFailed | Report the error, note that cleanup was verified but not reviewed |

---

## Progress Tracking

Report progress at each phase transition:

```
PROGRESS: Phase [X/4] - [Phase Name]
Completed: [list of completed phases]
Current: [current phase]
Remaining: [list of remaining phases]
```

---

## Constraints

- This skill receives a target path as `$ARGUMENTS`.
- This skill invokes `sdd-analyze-dead-code`, `sdd-verify`, and `sdd-code-reviewer` via the Skill tool (INV-CLN-02, INV-CLN-03, INV-CLN-04).
- This skill does NOT use the Task tool.
- This skill modifies source files ONLY during Phase 2 (Removal), and ONLY for approved candidates (INV-CLN-01).
- This skill does NOT perform its own dead code detection. All detection is delegated to sdd-analyze-dead-code.
- This skill is language-agnostic. It relies on sdd-analyze-dead-code for language-specific detection and sdd-verify for language-specific testing and linting.
