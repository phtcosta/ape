## MODIFIED Requirements

### Requirement: Per-action decision-source telemetry

`StatefulAgent.resolveNewAction()` SHALL emit one structured `[APE-STEP]` log line for the action returned by `selectNewActionNonnull()` (`StatefulAgent.java:1259`), after the action is finalized and before it is executed. The line SHALL attribute the action to a `decision_source` and include the per-mechanism boosts that applied.

To attribute LLM and other early-return paths that bypass `logActionSelected` (`SataAgent.java:317,328,339,348`), `ModelAction` SHALL carry a `decisionSource` provenance field set at the point of selection. The field SHALL be populated on every return path: SATA strategies, the three LLM hooks (new-state, stagnation, random), the budget-exhausted trivial path, and the null path.

For SATA-chain selections, the `decisionSource` SHALL be set by a boost-attribution rule scoped to the **selection sub-paths that actually consume priority**, not to whole `SataEventType` branches. Attribution applies ONLY when the action is a `ModelAction` chosen by: (a) a priority roulette — `State.randomlyPickAction` in the epsilon-greedy random branch (`SataAgent.java:487`) or `RandomHelper.randomPickWithPriority` over the EARLY_STAGE unvisited candidates (`SataAgent.java:1072`) — or (b) a boost-based deterministic pick — the MOP short-circuit (`selectUnvisitedMopTarget`) or the EARLY_STAGE MOP preference (`pickBestMopTarget` in `findGreedyActionForward`). When attribution applies AND the action carries at least one boost greater than 0 among `getMopBoost()`/`getWtgBoost()`/`getMenuBoost()`/`getCoverageBoost()`/`getFormBoost()`, `decisionSource` SHALL be set to the mechanism holding the largest boost. Ties on the largest boost SHALL be resolved by the fixed precedence `MOP > WTG > Menu > Form > Coverage`. In all other cases `decisionSource` SHALL remain `SATA` — in particular on the sub-paths that select for reasons other than priority even though they live inside `EARLY_STAGE`/`EPSILON_GREEDY`: graph-navigation and shortest-path picks (`SataAgent.java:1087,1099`, backward `:1110-1127`), the Back-/Menu-unvisited short-circuits (`:461,468`), and `greedyPickLeastVisited` (`:484`, minimum visit count; priority is only its tie-break). (The previous branch-level rule labeled those picks by their incidental largest boost — e.g. every new state with an unvisited MODEL_MENU carrying `menuBoost=250` emitted `decision_source=Menu` — systematically inflating mechanism shares in exactly the metric the §7.5 experiment reads.)

This attribution reports which mechanism most contributed to the chosen action on a sub-path that actually consumed priority. It SHALL NOT be interpreted as a counterfactual claim that the boost changed the selection outcome.

The `decision_source` enum SHALL be: `SATA`, `MOP`, `Coverage`, `LLM`, `Fuzz`, `Menu`, `WTG`, `Component`, `Budget`, `Form`. A pick driven by the form-completion boost (`getFormBoost()` largest) SHALL be attributed `Form` — previously `formBoost` was invisible to attribution and form-driven picks were labeled `Coverage` or `SATA`, making the form-completion change's selection influence unmeasurable.

The `[APE-STEP]` line SHALL also carry a `clock=<epochMillis>` field (device wall clock at emission), enabling offline temporal joins between the `.trace` and externally collected artifacts (e.g. logcat-based violation timestamps) without any APE↔logcat coupling — APE SHALL NOT read from or write to logcat.

#### Scenario: SATA-selected action attributed
- **WHEN** `resolveNewAction()` finalizes an action chosen by the SATA epsilon-greedy strategy with all boosts equal to 0
- **THEN** a single `[APE-STEP]` line SHALL be emitted with `decision_source=SATA`
- **AND** the line SHALL include `step#`, `state`, `action`, and per-mechanism boosts

#### Scenario: MOP-boosted action from the EARLY_STAGE roulette attributed to MOP
- **WHEN** the EARLY_STAGE unvisited roulette (or the MOP preference probing it) picks a `ModelAction` whose boosts are `mop=500, wtg=0, menu=0, coverage=0, form=0`
- **THEN** the action's `decisionSource` SHALL be `MOP`
- **AND** the emitted `[APE-STEP]` line SHALL report `decision_source=MOP`

#### Scenario: Largest boost wins on the epsilon-greedy roulette
- **WHEN** `randomlyPickAction` picks a `ModelAction` whose boosts are `mop=0, wtg=200, menu=0, coverage=100, form=0`
- **THEN** the action's `decisionSource` SHALL be `WTG`
- **AND** the Coverage boost SHALL NOT change the attribution because it is smaller

#### Scenario: Tie precedence MOP>WTG>Menu>Form>Coverage
- **WHEN** a roulette pick carries boosts `mop=300, wtg=300, menu=0, coverage=0, form=0`
- **THEN** the action's `decisionSource` SHALL be `MOP`
- **AND** when the tie is instead `wtg=300, menu=300` the `decisionSource` SHALL be `WTG`

#### Scenario: Form-driven pick attributed to Form
- **WHEN** a roulette pick carries boosts `mop=0, wtg=0, menu=0, coverage=100, form=150`
- **THEN** the action's `decisionSource` SHALL be `Form`

#### Scenario: Boosted action in a priority-blind branch stays SATA
- **WHEN** `logActionSelected(action, USE_BUFFER)` is called for a `ModelAction` whose boosts are `mop=500, wtg=0, menu=0, coverage=0`
- **THEN** the action's `decisionSource` SHALL be `SATA`
- **AND** the boost SHALL NOT be attributed because the `USE_BUFFER` branch does not consume priority

#### Scenario: Menu-unvisited short-circuit pick stays SATA
- **WHEN** the Menu-unvisited short-circuit (`SataAgent.java:468`) selects an unvisited MODEL_MENU carrying `menuBoost=250`
- **THEN** the action's `decisionSource` SHALL be `SATA` (the pick was made because the menu was unvisited, not because of its boost)

#### Scenario: Least-visited pick stays SATA
- **WHEN** `greedyPickLeastVisited` returns a `ModelAction` carrying `mop=500` (visit-count minimum, priority used only as tie-break)
- **THEN** the action's `decisionSource` SHALL be `SATA`

#### Scenario: EARLY_STAGE navigation pick stays SATA
- **WHEN** an EARLY_STAGE shortest-path/graph-navigation sub-path (`SataAgent.java:1087,1099`) returns a boosted `ModelAction`
- **THEN** the action's `decisionSource` SHALL be `SATA`

#### Scenario: Unboosted action on a priority-consuming sub-path stays SATA
- **WHEN** a roulette pick carries boosts all equal to 0
- **THEN** the action's `decisionSource` SHALL be `SATA`

#### Scenario: [APE-STEP] carries wall clock
- **WHEN** any `[APE-STEP]` line is emitted
- **THEN** it SHALL include a `clock=<epochMillis>` field

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
