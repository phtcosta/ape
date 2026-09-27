## Purpose

This delta adds the three facts the analysis of the opaque-screen change needs and the trace does not carry today.

The first is **which steps were opaque** — decided on a state that offers actions but none requiring a target (`llm-routing` INV-RTR-21). Without it, the steps on which `LLM_OPAQUE_SCREEN` could act are recoverable only by parsing the `[A=n]` suffix of `dec.a` and guessing which of those actions were widget actions, which is what the Study 03 replication session had to do. The flag is emitted on **every arm**, whether or not opaque routing is on, because it describes the screen and not the mechanism: the arm without LLM needs it to be stratified the same way as the arm with it, and telemetry is identical for every arm by requirement.

The second is **which steps' trees held a dynamic region** (`llm-routing` INV-RTR-26) — the other half of the routing condition. An opaque step is routed only when its tree holds one, so without the flag the analysis cannot tell a routed opaque step from an unrouted one on the arm without LLM, and cannot audit the predicate against screenshots or sources. It is emitted on every arm and on every step whose tree holds a region, opaque or not, for the same reason as the first: it describes the screen. It is the predicate's verdict, not a label of what the screen is.

The third is **how many forced restarts the run requested**. An off-tree tap at a new coordinate is a new ephemeral action; its `NEW_ACTION` edge resets `graphStableCounter`, which is what drives SATA's forced restart. On a widget screen this was already the case. On an opaque screen, where the LLM may become the main source of new actions, it can change the restart cadence the arm without LLM has — a side effect this change deliberately does not alter, and therefore must make measurable. Today the count appears only in a free-text `Logger` summary at teardown, which is not sink data.

## Invariants

- **INV-SNK-15**: A step record's `dec` SHALL carry `opaque:1` exactly when `LlmGate.isOpaque` holds for the state the step was decided on, on every arm, and SHALL carry no `opaque` member otherwise. The value SHALL come from the same predicate the gate evaluates, never from a second definition.
- **INV-SNK-17**: A step record's `dec` SHALL carry `dyn:1` exactly when `LlmGate.hasDynamicRegion` holds for the `GUITree` the step was decided on, on every arm, and SHALL carry no `dyn` member otherwise (including when no tree is available). The value SHALL come from the same predicate the gate evaluates.
- **INV-SNK-16**: `RUN_END.counters.restarts` SHALL equal the number of `requestRestart()` calls the agent made during the run, on every arm, and SHALL be written even when zero.

## ADDED Requirements

### Requirement: Opaque Step Marking

The step record's `dec` member SHALL carry `opaque:1` when the state the step was decided on offers actions but none requiring a target, evaluated by `LlmGate.isOpaque(State)` at selection, on every arm (INV-SNK-15). A step on a non-opaque state SHALL carry no `opaque` member (INV-SNK-05: the default is omitted). The flag is telemetry only: it SHALL NOT be read by any decision, and the neutrality gate (sink on/off equivalence) SHALL continue to hold.

The flag rides the step record rather than the `STATE` dictionary entry by decision of this change. A state's action set is fixed once the state exists, so the dictionary would carry the same information for free; the step record is chosen because the analysis joins opaque steps to their `llm[]` sub-events and outcomes within one record, and because opaque steps are a small share of steps on most applications (5.9–6.5 % per arm in E5), which bounds the per-step cost the volume rules measure.

#### Scenario: game canvas step marked on every arm

- **WHEN** step 12 of an `aperv` run and step 12 of an `llm` run are both decided on a state whose actions are `[MODEL_BACK, MODEL_MENU]`
- **THEN** both records' `dec` SHALL carry `opaque:1`
- **AND** only the `llm` run's record MAY carry an `llm` array, and only when opaque routing is on in its plan

#### Scenario: widget step not marked

- **WHEN** a step is decided on a state offering `[MODEL_CLICK(button), MODEL_BACK, MODEL_MENU]`
- **THEN** its `dec` SHALL carry no `opaque` member

#### Scenario: non-model step

- **WHEN** a step's record is written for a non-model action (a component trigger, an activity launch)
- **THEN** `opaque` SHALL follow the same rule for the state the step was decided on, so a trigger fired from a canvas state carries `opaque:1`

### Requirement: Dynamic Region Marking

The step record's `dec` member SHALL carry `dyn:1` when the tree the step was decided on holds a dynamic region, evaluated by `LlmGate.hasDynamicRegion(GUITree)` at selection, on every arm and whether the step is opaque or not (INV-SNK-17). A step without one SHALL carry no `dyn` member (INV-SNK-05). The flag is telemetry only: it SHALL NOT be read by any decision, and the neutrality gate SHALL continue to hold. A context without a tree (the parity oracle) SHALL produce no `dyn` member, so the parity goldens are unchanged.

#### Scenario: game canvas step marked on every arm

- **WHEN** step 12 of an `aperv` run and step 12 of an `llm` run are both decided on retrowars' launcher state, whose tree is a root holding one full-root, focusable, label-less `android.view.View` leaf
- **THEN** both records' `dec` SHALL carry `opaque:1` and `dyn:1`

#### Scenario: opaque dialog not marked

- **WHEN** a step is decided on a stuck progress dialog whose actions are `[MODEL_BACK, MODEL_MENU]`
- **THEN** its `dec` SHALL carry `opaque:1` and no `dyn` member

#### Scenario: region beside widgets

- **WHEN** a step's tree holds a dynamic region and its state also offers widget actions
- **THEN** its `dec` SHALL carry `dyn:1` and no `opaque` member

### Requirement: Restart Counter in RUN_END

`RUN_END.counters` SHALL carry `restarts`, the number of forced restarts the agent requested during the run (`ApeAgent.requestRestart()` calls, from any of the graph-, state- or activity-stability hooks), on every arm, written even when zero (INV-SNK-16). It SHALL sit beside `acts` and `states`, outside the `llm` block, because it is not an LLM counter. Like the rest of `RUN_END` it is write-only (INV-SNK-09): no task status depends on it.

#### Scenario: restarts counted

- **WHEN** a run's stability hooks request three forced restarts before the time budget ends
- **THEN** the trace's `RUN_END.counters` SHALL carry `restarts:3`

#### Scenario: no restart is a measurement

- **WHEN** a run ends by timeout having requested no forced restart
- **THEN** `RUN_END.counters` SHALL carry `restarts:0`, not omit it
