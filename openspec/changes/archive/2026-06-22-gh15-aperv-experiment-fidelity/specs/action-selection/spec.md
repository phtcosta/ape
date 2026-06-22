## Purpose

APE-RV's existing boost logs (`[APE-RV] MOP boost`, `menu`, `WTG`, `Coverage`) are aggregates emitted during the scoring pass; none attributes the **finally selected** action to a decision source. The normal SATA chain logs its strategy via `logActionSelected`, but the LLM early-returns in `SataAgent.selectNewActionNonnull()` (`:317,:328,:339,:348`) bypass it entirely, so LLM-selected actions have no per-action provenance. This delta adds a single `[APE-STEP]` line per selected action carrying its decision source, enabling per-action attribution of MOP/LLM/Coverage influence in experiment analysis without heuristic log parsing.

## Invariants

- **INV-SEL-04**: Exactly one `[APE-STEP]` line SHALL be emitted per finally-selected action, covering every selection path including the LLM early-returns and budget/trivial early-returns. The line SHALL carry a `decision_source` from a fixed enum, never a free-form string.

## ADDED Requirements

### Requirement: Per-action decision-source telemetry

`StatefulAgent.resolveNewAction()` SHALL emit one structured `[APE-STEP]` log line for the action returned by `selectNewActionNonnull()` (`StatefulAgent.java:1259`), after the action is finalized and before it is executed. The line SHALL attribute the action to a `decision_source` and include the per-mechanism boosts that applied.

To attribute LLM and other early-return paths that bypass `logActionSelected` (`SataAgent.java:317,328,339,348`), `ModelAction` SHALL carry a `decisionSource` provenance field set at the point of selection. The field SHALL be populated on every return path: SATA strategies, the three LLM hooks (new-state, stagnation, random), the budget-exhausted trivial path, and the null path.

The `decision_source` enum SHALL be: `SATA`, `MOP`, `Coverage`, `LLM`, `Fuzz`, `Menu`, `WTG`, `Component`, `Budget`.

#### Scenario: SATA-selected action attributed
- **WHEN** `resolveNewAction()` finalizes an action chosen by the SATA epsilon-greedy strategy
- **THEN** a single `[APE-STEP]` line SHALL be emitted with `decision_source=SATA`
- **AND** the line SHALL include `step#`, `state`, `action`, and per-mechanism boosts

#### Scenario: LLM early-return attributed
- **WHEN** the new-state LLM hook returns a non-null action at `SataAgent.java:328` (bypassing `logActionSelected`)
- **THEN** that action's `decisionSource` SHALL be `LLM`
- **AND** exactly one `[APE-STEP]` line SHALL be emitted for it with `decision_source=LLM`

#### Scenario: Every step is attributable
- **WHEN** a run completes
- **THEN** every executed action SHALL have exactly one corresponding `[APE-STEP]` line
- **AND** no selection path SHALL produce zero or more than one line for a single action
