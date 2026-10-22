# Delta: MOP Guidance (gh-tbd-base-equals-ape)

## MODIFIED Requirements

### Requirement: Target Retirement

Under `ape.mopScoring=distance`, the agent SHALL keep one counter per target, sized by `MopData`'s `targets` (`MopRetirement`). `MopRetirement` is created once per run by `StatefulAgent` after the artifact loads when `MOP` is in the plan; it is null when `MOP` is absent or the mode is `flag`.

Retirement SHALL compose on `MopInteractionLedger` ("MopInteractionLedger — Executions per Activity and Key") and keep no first-interaction record of its own. In `StatefulAgent.moveForward`, for the model action about to be executed, the agent SHALL first read `MopInteractionLedger.tried(activity, xpath|ActionType)`; if the key is not yet there, this is a **first interaction**. It SHALL then record the execution, whether first or not (every execution adds one, design D-B17); only a first interaction counts targets. Novelty is per (widget, action type) and per activity, so a long-click on a widget already clicked in the activity is a first interaction.

On a first interaction, the agent SHALL take the **weight's lookup** for the action's event (`MopScorer.eventTypeOf`): among the action's containment lookups ("Parent/child widget granularity reconciliation"), the one holding the action's smallest unretired distance, ties going to the earlier node in containment order (the node itself, its ancestors, then its descendants). It is the lookup behind `dec.mopd`/`dec.mops` (`event-sink`) and the MOP coin's target count, so what gives an action its weight is what retires it. Only an action the score treats as MOP retires anything: when the action's weight before the tie split is 0, nothing is counted. Otherwise the agent SHALL call `MopRetirement.onFirstInteraction(weightLookup)`, which adds one to the counter of each target index in the lookup's `dist` whose pair lies at `d*`, the lookup's smallest unretired distance, each index once, and returns the indices whose counter reached `ape.mopRetireAfter` (default 3) on this call. Those indices are the step's `dec.ret` (`EventSink.mopRetired`, `event-sink`).

The count is applied at `moveForward` without a pending step, and only for an action that is executed. On the stage path (the pipeline assembled `RESTART`, `decision-pipeline` "Restart Guard Stage") the restart is a guard placed before selection, so no selected action is replaced after selection; a step the `RESTART` guard takes selects the restart action, which has no target and counts nothing. On the post-selection path (no `RESTART` stage, as in the `mop` cell, `decision-pipeline` "Post-Selection Restart") an action that `checkRestart` replaces by a start action is not executed: `StatefulAgent.updateStateInternal` resets the trace instead of calling `moveForward`, so the replaced action records no ledger key, counts no target and retires nothing, and the step carries no `dec.ret`. No `checkRestart` override applies or discards a count in either path.

A target whose counter is at least `ape.mopRetireAfter` is **retired**. The key has no lower bound and resolution does not reject any integer: with `ape.mopRetireAfter <= 0` every target reads as retired from the start, so under `distance` every weight is 0, the MOP coin is never eligible and the run explores without distance guidance, no `dec.ret` is written and `RUN_END.retired` stays 0. A retired target SHALL NOT count toward an action's distance in "MopScorer — Priority Boost", toward the coin's eligibility and ranking, toward the prompt marker (`llm-prompt` "MOP Marker Annotation"), or toward the launcher order (`component-triggering`). `MopRetirement.nearest(int[][] dist, int dmax)` returns the smallest `d` over the unretired pairs with `d ≤ dmax`, or `Integer.MAX_VALUE` when there is none.

Known limits, kept as decided (D19 of `llm-coordinate-single-base`):
- an off-tree LLM tap (`MODEL_LLM_TAP`) has no node, so it never counts and records no ledger key;
- the key comes from `Name.toXPath()`, so a refinement that renames a widget makes its next interaction a first one again;
- **batch retirement** (recorded, not changed; author 2026-10-18): one first interaction counts every target at `d*` of its lookup, so three touches of widgets whose generic handler reaches many targets at one distance retire the whole set together. In `e03igg` the 16 steps that retired 5 or more targets hold 240 of 721 retirements and 170 of the 234 targets never reached in their task (owncloud: 84 in one step from the login screen); a target retiring alone had been reached already in 80 % of cases.

Only the targets at `d*` are counted: widget and handler lists carry every target at `d ≤ 3`, and counting a whole list would retire up to several hundred targets through one interaction.

#### Scenario: first interaction counts each target of the node's own lookup once
- **WHEN** under `distance` the agent executes a click on a node whose own lookup has `dist = [[16,3],[17,3],[18,3]]`, no containment node carries a nearer unretired pair, and the ledger has no entry for the widget's key in its activity
- **THEN** the counters of targets 16, 17 and 18 SHALL each increase by exactly 1, and the ledger SHALL then hold the key
- **AND** no target of another containment node's lookup SHALL be counted

#### Scenario: only the targets at the weight's distance count
- **WHEN** under `distance` the agent executes the first interaction of a click whose weight's lookup carries `[[4,1],[7,2],[9,3]]`, no target retired
- **THEN** the counter of target 4 SHALL increase by exactly 1, and the counters of targets 7 and 9 SHALL NOT change
- **AND WHEN** target 4 is retired and another widget's first interaction has a weight's lookup carrying `[[4,1],[7,2],[11,2]]`
- **THEN** `d*` SHALL be 2 and the counters of targets 7 and 11 SHALL each increase by exactly 1, while target 4's SHALL NOT change

#### Scenario: a weight won through containment retires its own targets
- **WHEN** under `distance` the agent executes the first interaction of a click on a clickable `LinearLayout` with no resource id and no stamp, whose child `TextView` resolves through the id key to `dist = [[7,2]]`, so the action's weight (400) comes from the child
- **THEN** the counter of target 7 SHALL increase by exactly 1
- **AND** `dec.mopd` of that step SHALL be 2, from the same lookup

#### Scenario: a first interaction with weight 0 counts nothing
- **WHEN** under `distance` the agent executes the first interaction of a click whose containment lookups carry only `[[5,4],[9,6]]`, so the action's weight before the tie split is 0
- **THEN** no counter SHALL change and the step SHALL carry no `dec.ret`
- **AND** the ledger SHALL still record the key

#### Scenario: an action a restart replaces counts nothing
- **WHEN** under `distance` in a plan whose pipeline assembled `RESTART` a restart is due at step 40, so the `RESTART` guard takes the step before any selection, while the state holds an untried widget whose weight's lookup carries `[[22,2]]`
- **THEN** no counter SHALL change, the ledger SHALL NOT record that widget's key, and the step SHALL carry `dec.src:"restart"` and no `dec.ret`
- **AND WHEN** in the `mop` cell under `distance` the MOP coin selects that widget's action at step 40 and a post-selection restart replaces it by a start action
- **THEN** no counter SHALL change, the ledger SHALL NOT record the widget's key, and the step SHALL carry `dec.src:"restart"` and no `dec.ret`
- **AND WHEN** at a later step no restart is due and that widget's action is selected and executed
- **THEN** target 22's counter SHALL increase by exactly 1

#### Scenario: second interaction of the same widget in the same activity does not count
- **WHEN** under `distance` the same widget is executed again in the same activity, so `MopInteractionLedger.tried` returns true
- **THEN** no counter SHALL change

#### Scenario: a target retires at the third count
- **WHEN** under `distance` with `ape.mopRetireAfter=3`, target 22 has counter 2, and a first interaction of another widget whose lookup carries `[22,2]` is executed
- **THEN** target 22's counter SHALL become 3 and `onFirstInteraction` SHALL return `[22]`
- **AND** the step's record SHALL carry `dec.ret` with index 22, and `retired(22)` SHALL be true for the rest of the run

#### Scenario: retired target ignored by distance scoring
- **WHEN** under `distance` an action's lookup carries `dist = [[4,1],[7,2]]` and target 4 is retired
- **THEN** the action's distance SHALL be 2 and its weight before the tie split SHALL be `ape.mopWeightD2` (400)
- **AND WHEN** target 7 is also retired
- **THEN** the action's weight SHALL be 0 and it SHALL NOT be eligible for the MOP coin

#### Scenario: no counting under flag
- **WHEN** `ape.mopScoring=flag` and the agent executes the first interaction of a widget whose lookup carries pairs
- **THEN** no `MopRetirement` SHALL exist, no counter SHALL change, and no step record SHALL carry `dec.ret`
- **AND** the ledger SHALL still record the key, because the coin reads it in both modes

#### Scenario: an off-tree LLM tap never counts
- **WHEN** under `distance` the executed action is a `MODEL_LLM_TAP` at a pixel that lies inside a widget whose lookup carries pairs
- **THEN** no counter SHALL change and no ledger key SHALL be recorded, because the tap has no resolved node to look up

### Requirement: MopInteractionLedger — Executions per Activity and Key

`MopInteractionLedger` (`agent/MopInteractionLedger`) SHALL be a MOP-owned count of the executions of each (activity, key) pair, where key is `xpath|ActionType` (the convention of `UICoverageTracker.widgetId`, `UICoverageTracker.java:240-250`). Its interface is `int count(String activity, String key)`, `boolean tried(String activity, String key)` (`count > 0`) and `void record(String activity, String key)`, which adds one (design D-B17). It is constructed with the coin's budget k and answers `belowBudget(activity, key)` (`count < k`), so the coin needs no parameter of its own. It exists once per run when `MOP` is in the plan, in both scoring modes, and is absent otherwise. It reaches the stages through `StageCollaborators.mopLedger()` (`decision-pipeline`). No scoring pass reads it.

The agent SHALL record into the ledger, in `StatefulAgent.moveForward`, every executed model action that has a resolved target, whatever owner selected it (SATA, the LLM, the coin). A `MODEL_LLM_TAP` off the tree has no node and records nothing. An action that a post-selection restart replaces by a start action (`decision-pipeline` "Post-Selection Restart", a plan whose pipeline did not assemble `RESTART`, such as the `mop` cell) is not executed and never reaches `moveForward`, so it records nothing, whichever owner selected it; it stays marked visited in the graph, as in APE, which the ledger does not read. Recording a key already present adds one to its count. The ledger is independent of `UICoverageTracker`, which belongs to the ADDONS coverage scorer: a cell without ADDONS still has a ledger when it has `MOP`.

The MOP coin reads `belowBudget` for eligibility, against `ape.mopCoinLedgerMaxInteractions` (k, `MOP_COIN`, int, default 1, `< 1` clamped to 1 at resolution, as the other plan clamps); target retirement reads it to decide whether an execution is a first interaction ("Target Retirement"), reading before `record` writes. Neither keeps a first-interaction record of its own (INV-MOP-40). Because the coin's own pick is recorded when executed, a key can be taken by the coin at most k times per run (once at the default), which is the property `ape.mopTargetPickCap` used to bound. Retirement reads only `tried`, so k does not change what retires.

#### Scenario: every execution adds one to its key's count
- **WHEN** a cell with `MOP` executes a `MODEL_CLICK` on widget `/a/b` in activity `A`, twice in two different states of `A`
- **THEN** after the first execution `tried("A", "/a/b|MODEL_CLICK")` SHALL be true
- **AND** the second execution SHALL add one to the key's count and SHALL NOT be a first interaction

#### Scenario: with k = 3 the coin may take a key three times; retirement counts once
- **WHEN** `ape.mopCoinLedgerMaxInteractions=3` and widget `/a/b` (`mopWeight=500`, weight's lookup `[[22,1]]`) in activity `A` was executed once, by SATA
- **THEN** the action on `/a/b` SHALL still be eligible for the coin in `A` until the ledger counts 3 executions of its key, whatever their owners
- **AND** target 22's counter SHALL have increased by exactly 1 over those executions

#### Scenario: action type and activity are part of the key
- **WHEN** widget `/a/b` was clicked in activity `A`
- **THEN** `tried("A", "/a/b|MODEL_LONG_CLICK")` SHALL be false and `tried("B", "/a/b|MODEL_CLICK")` SHALL be false

#### Scenario: no ledger without MOP
- **WHEN** the `as` cell runs
- **THEN** no `MopInteractionLedger` SHALL be constructed and `StageCollaborators.mopLedger()` SHALL not be reached by any mounted stage

#### Scenario: an action replaced after selection records nothing
- **WHEN** in the `mop` cell SATA selects a `MODEL_CLICK` on widget `/a/b` in activity `A` whose key the ledger does not hold, and a post-selection restart replaces it by a start action
- **THEN** `tried("A", "/a/b|MODEL_CLICK")` SHALL still be false after the step
- **AND** the action on `/a/b` SHALL remain eligible for the MOP coin in `A` at the default `ape.mopCoinLedgerMaxInteractions=1`
