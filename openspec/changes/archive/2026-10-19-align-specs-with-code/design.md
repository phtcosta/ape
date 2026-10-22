## Context

The audit of 2026-10-19 compared every concrete claim in the 24 main specs with `src/main/java` on `master`
`23c772c1`. Four reports, each row with spec line, claim, code reality (file:line), class and checked/inferred, are
the input of this change; they live in the session scratchpad and are summarised in the per-capability tables of
`tasks.md`. Classes: (a) stale spec, code is right; (b) code looks wrong; (c) structural; (d) wording.

Constraints set by the author:
- **Specs only.** No edit to `src/`, tests, `CLAUDE.md` or rvsec. Items whose fix would need a code change stay open
  (below), and the spec describes the code as it is.
- Mechanism: an OpenSpec change (this one), not direct edits.
- Duplicate invariant ids are renumbered.

`openspec archive` applies `## ADDED/MODIFIED/REMOVED/RENAMED Requirements` but not a delta's preamble, so
preamble edits are hand-synced at archive under the rules of D4, and checked with the requirement-block identity
script (tasks group 6).

## Architecture

No runtime component changes. The work is a set of delta specs, one per modified capability, under
`openspec/changes/align-specs-with-code/specs/<capability>/spec.md`.

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| delta spec | requirement ops + preamble edits for one capability | main spec, audit rows, code | `specs/<cap>/spec.md` |
| archive | applies requirement ops | delta specs | main specs |
| hand-sync | applies preamble edits (D4) | delta preambles | main spec preambles |

## Mapping: Spec -> Implementation -> Test

Not applicable: no implementation changes. Every restated claim cites the code site it was read from
(`file:line` at `23c772c1`, or a class/method name where a line number would rot).

## Goals / Non-Goals

**Goals:**
- Every concrete claim (names, records, keys, defaults, ordering, ownership) in the main specs matches the code.
- Every invariant id is defined once, in one place, and every cited local id is defined.
- Requirement titles name what their body specifies.

**Non-Goals:**
- Any code, test, comment or CLAUDE.md edit.
- The 203 "Requirement text is very long" validation warnings.
- The review/verify follow-ups of `isolate-guidance-groups` (WR-01..03, W6–W9, S1–S10).
- rv-android specs and the ids they own.

## Decisions

### D1 — The code is the reference; describe it, do not prescribe it
Where spec and code disagree, the delta restates the requirement to what the code does. When the code looks wrong,
the spec still describes it and the question is recorded under Open Questions. Two items were classed (b) and
re-checked: the LLM mode labels (OQ1) and the `System.getProperties()` default chain in `Config` (OQ2). Both are
described as they are.

### D2 — Retired mechanisms: REMOVED, not reworded
A requirement whose subject does not exist (e.g. exploration "BFS and DFS Traversal Modes", the
static-analysis equivalence gate as a live SHALL) is REMOVED with a Reason. Alternative considered: keep as
"future"; rejected, specs document current behaviour.

### D3 — Titles naming a retired mechanism: RENAMED
`RENAMED` (FROM:/TO:) is used, and has precedent in the archive (`2026-07-14-mop-census-launcher`,
`2026-08-04-rearch-02-runspec`). A rename and a MODIFIED of the same requirement use the new name in MODIFIED.
Scenario headers that only name a retired mechanism are kept where an archived scenario must match (a MODIFIED
delta cannot drop a main-spec scenario title); the body is rewritten.

### D4 — Preamble edits (hand-synced at archive)
A delta carries only the preamble sections it changes:
- `## Purpose`: the full replacement text, describing the current state without change narrative.
- `## Data Contracts`: the full replacement of each changed subsection.
- `## Invariants`: only the invariants that change. An amended or restored invariant is given in full; a new one
  in full; a retired one as `- **INV-X-NN** (retired): <reason>`, and is deleted from the main spec.
At archive the hand-sync applies exactly these, and nothing else in the preamble.

### D5 — Duplicate ids
- **INV-CT-18**: the Invariants-section meaning ("`SystemBroadcastCatalog` loaded iff `COMPONENT_TRIGGER`") keeps
  the id; the null-tree relaunch invariant inside the requirement becomes **INV-CT-19**.
- **INV-CT-15**: the one defined in the Invariants section is amended to the current text (with the D-B15 clause);
  the copy inside the launcher requirement becomes a reference ("see INV-CT-15"), not a definition.
- **INV-SEL-04**: the stale definition in the action-selection requirement (`[APE-STEP]`,
  `Config.stepTelemetryEnabled`) is deleted; the Invariants-section definition stands.
- **INV-RUN-07 / INV-RUN-08**: the coverage paragraph after the run-spec invariants keeps its text but stops using
  the definition form (`- **INV-…**`), so each id has one definition.

### D6 — Ids cited but never defined in a main spec
- **Local id, mechanism exists** (e.g. INV-DP-01…07/09/11/12, INV-MOP-10/11/13/14/27/35, INV-MODEL-18…20,
  INV-TREE-13, INV-SNK-01/02/04/06…11/13/14, INV-RTR-13/14, INV-LLM-09/10, INV-APERV-05…07, INV-CT-05…08/10/12,
  INV-ARCH-01/06): restore the definition into the owning spec's Invariants, taken from the archived delta that
  introduced it and restated against the code.
- **Local id, mechanism gone or superseded** (e.g. INV-DP-10 by INV-DP-13, INV-MOP-09, INV-MOP-31,
  INV-ARCH-06 dissolved per INV-RUN-05): remove the citation, or replace it by the id that holds today.
- **Foreign id** (rv-android / GATOR prefixes such as INV-APV, INV-INS, INV-ANA, INV-PLT, and INV-DRV if the
  generator spec owns it): not redefined; annotated once with its owning repository.
The owning spec of a restored id is the one whose prefix it carries.

### D7 — Removed log lines
Invariants and requirements that require an `[APE-STEP]`, `[APE-LLM-TEL]`, `[APE-LLM-ERROR]`,
`[APE-LLM-CONFIG-ACK]` or `[APE-MOP-DATA]` line are restated against the NDJSON record that carries the same fact
(`StepRecord` fields, `LLM_ACK`, `MOP_DATA`, `RUN_END.counters`) or the `[APE-RV]` Logger line that the code
actually prints. Historical or negative mentions ("is no longer written") stay.

### D8 — `openspec/specs/README.md`
Brought up to the 24 capabilities with one-line descriptions. Edited directly at apply (not an OpenSpec spec).

## API Design

None.

## Data Flow

Audit reports → delta specs (per capability) → `openspec validate align-specs-with-code --strict` → author review →
archive (requirements) + hand-sync (preambles) → identity check → `openspec validate --specs --strict` counts.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| MODIFIED header mismatch | archive | headers copied verbatim from main spec | fix header, re-validate |
| MODIFIED drops a scenario | strict validate / archive | keep every main-spec scenario title, rewrite bodies | restore title |
| Preamble edit lost at archive | archive does not apply preambles | D4 hand-sync + identity check | re-sync from delta |
| Hand-sync touches a requirement block | human/agent error | identity check before/after (requirements must equal the archive output) | revert block |

## Risks / Trade-offs

- [A restated claim is itself wrong] → every restatement cites the code site it was read from; I re-check a sample
  of each agent's deltas before presenting.
- [Large delta volume (≈ 20 files)] → split by capability group; validate after each group.
- [Source comments cite renumbered ids] → listed in OQ3; left for a code change.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Validation | delta format, header matching | `openspec validate align-specs-with-code --strict` | 1 |
| Archive check | requirement blocks equal archive output after hand-sync | `reqs.py` identity script | 24 specs |
| Spec validation | errors and warnings by level | `openspec validate --specs --strict --json` | errors 0; warnings reported |
| Code | unchanged | `git diff --stat -- src/` empty | — |

## Open Questions

Items that would need a code (or non-spec) change. Left open by the author's decision; the specs describe the code.

- **OQ1 — LLM mode labels.** The LLM sub-event `mode` is written `"new-state"`, `"stagnation"`, `"random"`
  (`LlmNewStateStage:57`, `LlmStagnationStage:100`, `LlmCoinStage:65`), while `dec.own.trg` is `new_state`,
  `stagnation`, `coin` (`StepRouting`). Unify in code, or keep both vocabularies.
- **OQ2 — `Config` system-property chain.** `Config` loads its `Properties` over `System.getProperties()`
  (`Config.java:30`, present since `84bb8289`, inherited from upstream). A JVM `-D` could reach a `Config`-read key
  without RunSpec validation or echo. BASE behaviour; the run-spec text is qualified, not the code.
- **OQ3 — Source comments and Javadoc.** `RunSpecException.Reason.UNKNOWN_PRESET` says four presets (six);
  `MonkeySourceApe` cites INV-CT-18 for the null-tree relaunch (now INV-CT-19); the `generateActivityTriggerEvent`
  Javadoc says the package comes from `MopData.getPackageName()`; `ApeAgent` cites "ApeAgent:322" for `getRandom`;
  the test helper `ScriptedLlm` cites the retired INV-DP-10.
- **OQ4 — CLAUDE.md.** `org.json` is used by 17 `src/main` files (not test-only); target retirement is applied in
  `StatefulAgent.moveForward` (`recordMopInteraction`), not "once the action survives `checkRestart`".
- **OQ5 — Launcher package check.** `MopLauncherStage.isEligible` has no same-package test; candidates come from
  the artifact's MOP census. Add a check, or keep the census as the only filter.
- **OQ6 — Review follow-ups** of `isolate-guidance-groups` (WR-01..03, W6–W9, S1–S10) and the aegis
  `AssignIconsActivity` launch crash: unchanged, out of scope.
