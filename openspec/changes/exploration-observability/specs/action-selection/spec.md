## MODIFIED Requirements

### Requirement: Per-action decision-source telemetry

`StatefulAgent.resolveNewAction()` SHALL emit one structured `[APE-STEP]` log line for the action returned by `selectNewActionNonnull()` (`StatefulAgent.java:1259`), after the action is finalized and before it is executed. The line SHALL attribute the action to a `decision_source` and include the per-mechanism boosts that applied.

To attribute LLM and other early-return paths that bypass `logActionSelected` (`SataAgent.java:317,328,339,348`), `ModelAction` SHALL carry a `decisionSource` provenance field set at the point of selection. The field SHALL be populated on every return path: SATA strategies, the three LLM hooks (new-state, stagnation, random), the budget-exhausted trivial path, and the null path.

For SATA-chain selections that pass through `SataAgent.logActionSelected(action, type)` (`SataAgent.java:218`), the `decisionSource` SHALL be set by a boost-attribution rule rather than unconditionally `SATA`. The rule applies ONLY when `action` is a `ModelAction` AND `type` is one of the priority-consuming SATA branches `{EARLY_STAGE, EPSILON_GREEDY}` (the only branches that read `priority`; the other branches ignore it). When the rule applies AND the action carries at least one boost greater than 0 among `getMopBoost()`/`getWtgBoost()`/`getMenuBoost()`/`getCoverageBoost()`, `decisionSource` SHALL be set to the mechanism holding the largest boost. Ties on the largest boost SHALL be resolved by the fixed precedence `MOP > WTG > Menu > Coverage`. In all other cases — non-`ModelAction`, a priority-blind branch (e.g. `USE_BUFFER`, `TRIVIAL_ACTIVITY`, `SATURATED_STATE`, `NULL`), or all boosts equal to 0 — `decisionSource` SHALL remain `SATA`.

This attribution reports which mechanism most contributed to the chosen action on a branch that actually consumed priority. It SHALL NOT be interpreted as a counterfactual claim that the boost changed the selection outcome.

The `decision_source` enum SHALL be: `SATA`, `MOP`, `Coverage`, `LLM`, `Fuzz`, `Menu`, `WTG`, `Component`, `Budget`.

#### Scenario: SATA-selected action attributed
- **WHEN** `resolveNewAction()` finalizes an action chosen by the SATA epsilon-greedy strategy with all boosts equal to 0
- **THEN** a single `[APE-STEP]` line SHALL be emitted with `decision_source=SATA`
- **AND** the line SHALL include `step#`, `state`, `action`, and per-mechanism boosts

#### Scenario: MOP-boosted action in EARLY_STAGE attributed to MOP
- **WHEN** `logActionSelected(action, EARLY_STAGE)` is called for a `ModelAction` whose boosts are `mop=500, wtg=0, menu=0, coverage=0`
- **THEN** the action's `decisionSource` SHALL be `MOP`
- **AND** the emitted `[APE-STEP]` line SHALL report `decision_source=MOP`

#### Scenario: Largest boost wins in EPSILON_GREEDY
- **WHEN** `logActionSelected(action, EPSILON_GREEDY)` is called for a `ModelAction` whose boosts are `mop=0, wtg=200, menu=0, coverage=100`
- **THEN** the action's `decisionSource` SHALL be `WTG`
- **AND** the Coverage boost SHALL NOT change the attribution because it is smaller

#### Scenario: Tie precedence MOP>WTG>Menu>Coverage
- **WHEN** `logActionSelected(action, EPSILON_GREEDY)` is called for a `ModelAction` whose boosts are `mop=300, wtg=300, menu=0, coverage=0`
- **THEN** the action's `decisionSource` SHALL be `MOP`
- **AND** when the tie is instead `wtg=300, menu=300` the `decisionSource` SHALL be `WTG`

#### Scenario: Boosted action in a priority-blind branch stays SATA
- **WHEN** `logActionSelected(action, USE_BUFFER)` is called for a `ModelAction` whose boosts are `mop=500, wtg=0, menu=0, coverage=0`
- **THEN** the action's `decisionSource` SHALL be `SATA`
- **AND** the boost SHALL NOT be attributed because the `USE_BUFFER` branch does not consume priority

#### Scenario: Unboosted action in a priority-consuming branch stays SATA
- **WHEN** `logActionSelected(action, EARLY_STAGE)` is called for a `ModelAction` whose boosts are all 0
- **THEN** the action's `decisionSource` SHALL be `SATA`

#### Scenario: LLM early-return attributed
- **WHEN** the new-state LLM hook returns a non-null action at `SataAgent.java:328` (bypassing `logActionSelected`)
- **THEN** that action's `decisionSource` SHALL be `LLM`
- **AND** exactly one `[APE-STEP]` line SHALL be emitted for it with `decision_source=LLM`

#### Scenario: Every step is attributable
- **WHEN** a run completes
- **THEN** every executed action SHALL have exactly one corresponding `[APE-STEP]` line
- **AND** no selection path SHALL produce zero or more than one line for a single action

## Invariants

- **INV-SEL-04**: Exactly one `[APE-STEP]` line SHALL be emitted per finally-selected action, covering every selection path including the LLM early-returns and budget/trivial early-returns. The line SHALL carry a `decision_source` from a fixed enum, never a free-form string. The boost-attribution rule in `logActionSelected` SHALL only change which enum value is carried; it SHALL NOT add, remove, or duplicate `[APE-STEP]` lines, and SHALL NOT modify any boost field.
