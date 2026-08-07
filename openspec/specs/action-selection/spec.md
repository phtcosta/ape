## Purpose

APE-RV's `State.greedyPickLeastVisited()` selects the action with the lowest `visitedCount`, ignoring `priority` entirely. Since `SataAgent.egreedy()` routes to the greedy path 85-98% of the time (epsilon range 0.02-0.15), all priority boosts computed in `adjustActionsByGUITree()` — MOP (+500/+300/+100), WTG (+200), and coverage boost — are effectively ignored in the vast majority of action selections. Only the random path (2-15% of decisions) uses `randomlyPickAction()`, which performs priority-weighted sampling.

This delta spec adds priority as a tiebreaker to `greedyPickLeastVisited()`. When multiple actions share the same minimum `visitedCount`, the action with the highest `priority` is selected. This makes all priority boosts (MOP, WTG, coverage) influence the greedy path without changing the fundamental least-visited strategy.

## Data Contracts

### Input
- `ape.mopTargetPickCap: int` — maximum deterministic MOP picks per (widget, action type, activity) key per run (default 3; <= 0 = unlimited). Read once at startup like the `mopWeight*` flags.

### Side-Effects
- **[Trace]**: `[APE-RV] MOP target capped: activity=<activity> widget=<xpath> picks=<n>` — logged once per key, on the deterministic pick that reaches the cap (`picks == cap` after increment).
## Requirements
### Requirement: State.greedyPickLeastVisited() — Priority Tiebreaker

`State.greedyPickLeastVisited(ActionFilter filter, boolean priorityTiebreak)` SHALL select the action with the lowest `visitedCount`. The tiebreak among actions sharing that lowest count is decided by the `priorityTiebreak` **argument**, supplied by the `SataChain` call site from the resolved plan (`leastVisitedPriorityTiebreak`, declared by the `scoring-pipeline` capability, default `true`). `State` SHALL NOT read `Config.leastVisitedPriorityTiebreak`: after this change the value travels as a parameter, and a residual static read would silently override the argument.

- When the argument is `true` (default): among actions sharing the same lowest `visitedCount`, the one with the highest `priority` SHALL be selected. This is the seam through which every priority boost (MOP, WTG, coverage, frontier, menu, form) becomes a *chosen* action rather than a number — the passes raise `priority`, and this call converts it into a pick (INV-SEL-01: priority is only a tiebreaker, never an override).
- When the argument is `false` (the feature absent from the resolved plan — `run-spec` INV-RUN-05): ties among the lowest-`visitedCount` actions SHALL be broken by array order (the first such action encountered wins), reproducing upstream APE. No RV priority boost SHALL influence the greedy pick.

Because this is the conversion point for the whole boost mechanism, the two branches SHALL be pinned by a paired unit test over one fixed action set — same visit counts, differing priorities, called once with each argument value, asserting different picks. Structural checks cannot substitute for it: with a wrong argument every stage still assembles, every pass still runs, every boost is still computed, and only the chosen action changes.

INV-SEL-01 and INV-SEL-02 describe the `priorityTiebreak=true` behavior.

#### Scenario: Single least-visited action
- **WHEN** actions have visitedCounts [0, 3, 5]
- **THEN** the action with visitedCount=0 SHALL be selected (unchanged behavior, independent of the flag)

#### Scenario: Tie broken by priority (flag on)
- **WHEN** `greedyPickLeastVisited` is called with `priorityTiebreak=true` and actions have visitedCounts [2, 2, 5] and priorities [32, 532, 52]
- **THEN** the action with visitedCount=2 and priority=532 SHALL be selected
- **AND** the MOP boost (+500) on that action effectively influenced the greedy selection

#### Scenario: All actions have same visitedCount (flag on)
- **WHEN** `greedyPickLeastVisited` is called with `priorityTiebreak=true` and all 10 actions have visitedCount=0 and priorities [32, 32, 232, 32, 532, 32, 32, 32, 32, 32]
- **THEN** the action with priority=532 (MOP-boosted) SHALL be selected

#### Scenario: the same action set picks differently under the two argument values
- **WHEN** one fixed action set with visitedCounts [2, 2, 5] and priorities [32, 532, 52] is passed to `greedyPickLeastVisited` twice, with `priorityTiebreak=true` and then `false`
- **THEN** the two calls SHALL return different actions (priority=532 and priority=32 respectively)
- **AND** this pairing SHALL exist as a permanent unit test, since no golden, assembly check or pass test observes this decision

#### Scenario: Tie with equal priorities
- **WHEN** actions have visitedCounts [1, 1, 3] and priorities [52, 52, 32]
- **THEN** either of the two tied actions MAY be selected (implementation picks the first encountered)

#### Scenario: Tie broken by array order when the flag is off
- **WHEN** `greedyPickLeastVisited` is called with `priorityTiebreak=false` and actions have visitedCounts [2, 2, 5] and priorities [32, 532, 52]
- **THEN** the first action with visitedCount=2 in array order SHALL be selected (priority=32), NOT the priority=532 action
- **AND** no RV priority boost SHALL influence the greedy pick (upstream APE behavior)

### Requirement: Per-action decision-source telemetry

For every action returned by `selectNewActionNonnull()`, `StatefulAgent.resolveNewAction()` SHALL record one `StepRecord` decision section (event-sink capability), after the action is finalized and before it is executed. Recording is unconditional — no configuration key gates it, and every experimental arm records identically. The record SHALL attribute the action to a `decision_source` (`dec.src`) and a pick channel (`dec.ch`), and include the non-zero per-mechanism boosts.

To attribute LLM and other early-return paths that bypass `logActionSelected`, `ModelAction` SHALL carry a `decisionSource` provenance field set at the point of selection (by the selecting `DecisionPipeline` stage, per the decision-pipeline capability). The field SHALL be populated on every return path: SATA strategies, the three LLM hooks (new-state, stagnation, random), the budget-exhausted trivial path, and the null path.

For SATA-chain selections, the `decisionSource` SHALL be set by a boost-attribution rule scoped to the **selection sub-paths that actually consume priority**, not to whole `SataEventType` branches. Attribution applies ONLY when the action is a `ModelAction` chosen by: (a) a priority roulette — `State.randomlyPickAction` in the epsilon-greedy random branch or `RandomHelper.randomPickWithPriority` over the EARLY_STAGE unvisited candidates — or (b) a boost-based deterministic pick — the MOP short-circuit (`selectUnvisitedMopTarget`) or the EARLY_STAGE MOP preference (`pickBestMopTarget`). When attribution applies AND the action carries at least one boost greater than 0 among `getMopBoost()`/`getMopFrontierBoost()`/`getWtgBoost()`/`getMenuBoost()`/`getCoverageBoost()`/`getFormBoost()`, `decisionSource` SHALL be set to the mechanism holding the largest boost. Ties on the largest boost SHALL be resolved by the fixed precedence `MOP > MopFrontier > WTG > Menu > Form > Coverage`. In all other cases `decisionSource` SHALL remain `SATA` — in particular on the sub-paths that select for reasons other than priority (graph-navigation and shortest-path picks, the Back-/Menu-unvisited short-circuits, and `greedyPickLeastVisited`, where priority is only a tie-break).

This attribution reports which mechanism most contributed to the chosen action on a sub-path that actually consumed priority. It SHALL NOT be interpreted as a counterfactual claim that the boost changed the selection outcome — the counterfactual claim is exactly what `dec.cf` carries, on the four MOP-sensitive pick channels only.

The `decision_source` enum SHALL be: `SATA`, `MOP`, `MopFrontier`, `Coverage`, `LLM`, `Fuzz`, `Menu`, `WTG`, `Component`, `Budget`, `Form`.

The record's envelope SHALL carry the step number `s` — the agent exploration timestamp (`getTimestamp()`) at selection time, incremented exactly once per agent step, hence unique within a run — and the time `t` in milliseconds since `RUN_START` (whose record carries the epoch base; the device wall clock remains recoverable as `t0 + t`, preserving offline temporal joins with externally collected artifacts without any APE↔logcat coupling — the heartbeat aside, APE SHALL NOT read from logcat). There is no join key: the outcome attaches to the same record (scoring-pipeline capability).

- **INV-SEL-04**: Exactly one `StepRecord` SHALL be recorded per finally-selected action, covering every selection path including the LLM early-returns and budget/trivial early-returns, in every arm. The record SHALL carry a `dec.src` from the fixed enum, never a free-form string. The boost-attribution rule SHALL only change which enum value is carried; it SHALL NOT add, remove, or duplicate records, and SHALL NOT modify any boost field.

**Where the retired rendering's assertions went.** Five of the scenarios below keep the header the
pre-change requirement gave them and carry a body this change contradicts, because what they assert
is what this change removes. Recorded rather than dropped, since the reader's question is where the
claim lives now:

| Scenario header | What it asserted | Where the claim is now |
|---|---|---|
| `[APE-STEP] carries the MOP-screen bit` | `activity_has_mop=0\|1` on every step line | the `ACT` dictionary entry's `mop` member, emitted once per activity and **on no step record** (`event-sink :: Dictionary Events and Run-Local IDs`, scenario `activity_has_mop recorded once`). A per-step copy of a per-activity constant is exactly what the volume levers exist to remove; the join `dec`→`act`→`ACT.mop` recovers it per step |
| `MOP-off arm always reports activity_has_mop consistent with MopData` | every line carries `activity_has_mop=0` when `MopData` is null | the same `ACT.mop` member, which is `0` for every activity of a run with no `MopData`. The arm-level property is unchanged; it is read once per activity instead of once per step |
| `click on a patch-fabricated widget is marked` | `patched=1` on the line | `dec.patched`, whose tri-state (1 / 0 / absent) is asserted by `event-sink :: StepRecord Schema`, scenario `Tri-state patched preserved`. The field is exempt from the defaults-omitted rule precisely so `0` stays distinguishable from "no target" |
| `targetless actions omit the patch bit` | no `patched` field for `MODEL_BACK`/`MODEL_MENU`/`MODEL_LLM_TAP` | the third state of that same tri-state — absence of the `dec.patched` member |
| `widget text with a newline stays on one line` | the emitter flattens `\n`/`\r` before writing the line | the serializer, which escapes them by construction (`event-sink` INV-SNK-02). No pre-flattening is required for well-formedness any more; a record physically cannot carry a raw newline |
| `No [APE-STEP] lines when telemetry is disabled` | zero lines with the gate off, provenance still populated | **nothing, deliberately** — the gate is deleted with its key. There is no disabled case to assert, and the scenario now asserts that: `ape.stepTelemetryEnabled` aborts plan resolution as an unknown key |

#### Scenario: SATA-selected action attributed

- **WHEN** `resolveNewAction()` finalizes an action chosen by the SATA epsilon-greedy strategy with all boosts equal to 0
- **THEN** a single `StepRecord` SHALL be recorded with `dec.src:"SATA"`
- **AND** its envelope SHALL include `s`, `t`, `act` and `st`, and its `dec` SHALL carry `ch` and omit all boost fields (defaults-omitted, INV-SNK-05)

#### Scenario: Stage-stamped provenance equals the StageResult label

- **WHEN** any stage returns `StageResult.select(action, label)` for a `ModelAction`
- **THEN** `label` SHALL equal `action.getDecisionSource().name()`
- **AND** the recorded `dec.src` SHALL equal the same value — one datum, stamped by the selecting stage and carried to the record without a second vocabulary (`decision-pipeline` INV-DP-04)

#### Scenario: MOP-boosted action from the EARLY_STAGE roulette attributed to MOP

- **WHEN** the EARLY_STAGE unvisited roulette (or the MOP preference probing it) picks a `ModelAction` whose boosts are `mop=500`, all others 0
- **THEN** the action's `decisionSource` SHALL be `MOP` and the record SHALL carry `dec.src:"MOP"` and `dec.mop:500`

#### Scenario: MOP-frontier-driven pick attributed to MopFrontier, not WTG

- **WHEN** a roulette pick carries boosts `mop=0, mopf=200, wtg=0, menu=0, cov=100, form=0`
- **THEN** the action's `decisionSource` SHALL be `MopFrontier` and the record SHALL carry `dec.src:"MopFrontier"`
- **AND** `dec.mopf:200` SHALL be carried with `dec.wtg` omitted — the two producers stay de-aliased in the record, which is the property `wtg=` reporting the WTG family only was introduced for

#### Scenario: Tie precedence MOP>MopFrontier>WTG>Menu>Form>Coverage

- **WHEN** a roulette pick carries boosts `mop=300, mopf=300`
- **THEN** the record SHALL carry `dec.src:"MOP"`
- **AND** when the tie is instead `mopf=300, wtg=300`, `dec.src` SHALL be `"MopFrontier"`

#### Scenario: click on a patch-fabricated widget is marked

- **WHEN** `patchGUITree` sets `clickable=true` on a child that the `AccessibilityNodeInfo` reported as non-clickable, and a later step selects the `MODEL_CLICK` derived from it
- **THEN** that step's record SHALL carry `dec.patched:1`
- **AND** a `MODEL_CLICK` on a node whose clickability came from the `AccessibilityNodeInfo` SHALL carry `dec.patched:0` — emitted, not omitted, because the field is exempt from the defaults-omitted rule
- **AND** the interpretation rules are unchanged: the bit records node provenance, not action causality, and offline analysis SHALL condition on `MODEL_CLICK` before reading it causally

#### Scenario: targetless actions omit the patch bit

- **WHEN** the selected action is `MODEL_BACK`, `MODEL_MENU` or `MODEL_LLM_TAP`
- **THEN** the record SHALL carry no `dec.patched` member — the third state of the tri-state, consistent with the other target-derived fields

#### Scenario: [APE-STEP] carries the MOP-screen bit

- **WHEN** `MopData` is present and the current activity is in the pre-computed MOP-activity set
- **THEN** the activity's `ACT` dictionary entry SHALL carry `mop:1`, and the step record SHALL carry no `activity_has_mop` member of its own
- **AND** an activity outside the set SHALL have `mop:0` on its entry — the bit is recorded once per activity and reached from a step via its `act` ID

#### Scenario: MOP-off arm always reports activity_has_mop consistent with MopData

- **WHEN** a run executes with `MopData` null
- **THEN** every `ACT` entry of that run SHALL carry `mop:0`
- **AND** no step record SHALL carry a MOP-screen bit of its own, in this arm or any other

#### Scenario: pick_channel discriminates short-circuit from roulette

- **WHEN** one step is selected by the unvisited-MOP short-circuit and a later step by the epsilon-greedy roulette
- **THEN** the first record SHALL carry `dec.ch:"short_circuit_unvisited"`
- **AND** the second SHALL carry `dec.ch:"roulette_greedy"`
- **AND** both MAY carry `dec.src:"MOP"` (channel and source are independent axes)

#### Scenario: Budget stage early-return attributed

- **WHEN** the `Budget` stage selects the trivial-activity action on an exhausted budget
- **THEN** that action's `decisionSource` SHALL be `Budget` and its channel `sata_other`
- **AND** exactly one record SHALL be recorded for it, carrying `dec.src:"Budget"` and `dec.ch:"sata_other"`

#### Scenario: Launcher step attributed Component

- **WHEN** the `MopLauncher` stage selects an `EVENT_TRIGGER_ACTIVITY` action
- **THEN** the record SHALL carry `dec.src:"Component"` and `dec.ch:"launcher"` (non-model branch, source derived from the action)
- **AND** it SHALL additionally carry `dec.comp` — evidence of what the launch did, which the retired line could not hold because it was written before dispatch while the record closes at step N+1 (`component-triggering` INV-CT-07)

#### Scenario: widget text with a newline stays on one line

- **WHEN** the selected action's resolved node text is `"Sign\nIn"`
- **THEN** the record's `dec.a` SHALL carry the text with the newline escaped, and the record SHALL occupy exactly one physical line
- **AND** well-formedness SHALL NOT depend on any pre-flattening by the emitter: the serializer escapes `\n`, `\r` and every character below U+0020 by construction (`event-sink` INV-SNK-02)

#### Scenario: Boosted action in a priority-blind branch stays SATA

- **WHEN** a `ModelAction` carrying `mop=500` is selected by a branch that does not consume priority (e.g. `greedyPickLeastVisited`, visit-count minimum)
- **THEN** the record SHALL carry `dec.src:"SATA"` with `dec.mop:500` still visible as a boost field

#### Scenario: LLM early-return attributed with its channel

- **WHEN** the new-state LLM hook returns a non-null action, bypassing `logActionSelected`
- **THEN** that action's `decisionSource` SHALL be `LLM` and exactly one record SHALL carry `dec.src:"LLM"` and `dec.ch:"llm"` for it, with the call's `llm[]` sub-event in the same record

#### Scenario: Every step is attributable when telemetry is enabled

- **WHEN** any run completes, under any preset
- **THEN** every executed action SHALL have exactly one `StepRecord`
- **AND** no selection path SHALL produce zero or more than one record for a single action
- **AND** every record SHALL carry exactly one `dec.ch`, and the MOP-screen bit SHALL be reachable for it through its `act` ID
- **AND** the scenario's precondition is now vacuous and is kept only so the archive can pair the name: there is no enabled case because there is no gate

#### Scenario: No [APE-STEP] lines when telemetry is disabled

- **WHEN** `ape.properties` sets `ape.stepTelemetryEnabled=false`
- **THEN** plan resolution SHALL abort with an unknown-key diagnostic before the first event — the key is deleted, not defaulted, so no run can execute in the state this scenario describes
- **AND** the pre-change guarantee it protected (provenance still populated with recording off) is subsumed: `decisionSource` is populated on every selection path unconditionally, and recording is unconditional too

### Requirement: MOP-target greedy short-circuit in epsilon-greedy selection

When `SataAgent.selectNewActionEpsilonGreedyRandomly()` runs and a valid, enabled, unvisited action carries a discriminative MOP boost (`ModelAction.getMopBoost() > 0`), the agent SHALL select that action before the epsilon-greedy / roulette step. The check SHALL run after the existing Back-unvisited and Menu-unvisited short-circuits (`SataAgent.java:456-488`) and before `egreedy()`. Among multiple eligible actions, the agent SHALL choose the one with the highest `mopBoost`, breaking ties by highest `priority`. The selected action SHALL be attributed through `logActionSelected(action, EPSILON_GREEDY)` so the per-step telemetry remains consistent.

This gives the discriminative `+500`/`+300` boost a deterministic path to the monitored widget, which priority-weighted roulette reaches only probabilistically. It is bounded to unvisited targets so it fires at most once per MOP-reachable widget and does not override the least-visited strategy for already-visited actions. The discriminative `mopBoost` is produced only after the activity-level fallback is removed (see `mop-guidance` "MopScorer — Priority Boost"); a uniform boost would make this branch select an arbitrary widget, which is why this change and the fallback removal ship together.

- **INV-SEL-MOP-01**: The short-circuit SHALL only select actions that are valid, enabled, unvisited, and have `mopBoost > 0`. A visited MOP target SHALL NOT be force-selected by this branch.
- **INV-SEL-MOP-02**: When no eligible unvisited MOP-boosted action exists, the branch SHALL be a no-op and selection SHALL proceed identically to the pre-change behavior.

#### Scenario: Unvisited MOP target selected ahead of roulette
- **WHEN** the current state has an unvisited valid action with `mopBoost=500` alongside other unvisited non-MOP actions
- **THEN** `selectNewActionEpsilonGreedyRandomly()` SHALL return the `mopBoost=500` action
- **AND** it SHALL be attributed via `logActionSelected(action, EPSILON_GREEDY)`

#### Scenario: Highest mopBoost wins among MOP targets
- **WHEN** two unvisited valid actions have `mopBoost=500` and `mopBoost=300`
- **THEN** the `mopBoost=500` action SHALL be selected

#### Scenario: Visited MOP target not force-selected
- **WHEN** the only action with `mopBoost>0` is already visited
- **THEN** the short-circuit SHALL NOT select it
- **AND** selection SHALL proceed to the existing epsilon-greedy / roulette path

#### Scenario: No MOP target is a no-op
- **WHEN** no action in the state has `mopBoost>0`
- **THEN** the short-circuit SHALL be a no-op
- **AND** selection SHALL proceed identically to the pre-change behavior

---

### Requirement: MOP preference in the EARLY_STAGE unvisited roulette

The deterministic MOP preference SHALL also apply where unvisited actions are actually consumed: in `SataAgent.findGreedyActionForward` (`SataAgent.java:1066-1102`), before the `RandomHelper.randomPickWithPriority(actions)` roulette over the unvisited greedy candidates, the agent SHALL first probe the candidate list with `pickBestMopTarget` and return its pick when non-null. The form-completion submit exclusion (INV-FORM-06) applies here exactly as in the epsilon-greedy short-circuit.

Verified motivation: the epsilon-greedy short-circuit alone is shadowed — the selection chain (`selectNewActionNonnull`, `SataAgent.java:409-439`) runs EARLY_STAGE forward before epsilon-greedy, and `getGreedyActions` collects precisely the enabled/valid/unvisited actions, so an unvisited MOP target is typically consumed by the EARLY_STAGE roulette (boost-weighted but probabilistic) before the deterministic branch is ever reached. Trace mining measured EARLY_STAGE at 57.6% of decisions with the short-circuit operating in under 1% of them. Adding the preference inside `findGreedyActionForward` (rather than hoisting the short-circuit above EARLY_STAGE) keeps the SATA chain order intact — ABA, back-tracking, and trivial-path bookkeeping are unaffected; only the pick among already-collected unvisited candidates becomes deterministic when a MOP target is present.

- **INV-SEL-MOP-03**: In every selection path that draws from a set of unvisited candidate actions by priority (EARLY_STAGE forward roulette, epsilon-greedy short-circuit), an eligible unvisited `mopBoost > 0` action SHALL win deterministically over non-MOP candidates, subject to the form-completion submit exclusion.

#### Scenario: MOP target wins the EARLY_STAGE roulette deterministically
- **WHEN** `findGreedyActionForward` collects three unvisited candidates, one with `mopBoost=500` and two without
- **THEN** the `mopBoost=500` action SHALL be returned without consulting the roulette

#### Scenario: no MOP candidate leaves the roulette unchanged
- **WHEN** no collected candidate has `mopBoost > 0`
- **THEN** `randomPickWithPriority` SHALL run exactly as before

#### Scenario: excluded submit falls through to the roulette
- **WHEN** the only `mopBoost>0` candidate is the form submit and the form-completion context holds
- **THEN** `pickBestMopTarget` SHALL skip it (INV-FORM-06)
- **AND** the roulette SHALL run over the remaining candidates (submit excluded)

---

### Requirement: MOP target revisit cap

The agent SHALL count deterministic MOP picks per physical widget and action type across the whole run, keyed by `target.toXPath() + "|" + actionType + "|" + activity` — independent of the abstract state the action belongs to. MOP boosts are event-type-specific (a widget's click and long-click carry independent boosts — `MopScorer.eventTypeOf`), so the cap is per (widget, action type, activity); the key matches the existing `UICoverageTracker.widgetId` convention (xpath|type) scoped by activity. Both deterministic MOP selection paths (the epsilon-greedy path through `selectUnvisitedMopTarget` and the EARLY_STAGE path around `pickBestMopTarget`) SHALL exclude a key whose count has reached `ape.mopTargetPickCap` from the deterministic override, exactly as if its `mopBoost` were 0 for short-circuit purposes — the candidate set is filtered at the two instance call sites; the static picker itself remains pure. The count is incremented on every action the deterministic paths select. Ineligibility SHALL NOT alter the action's `mopBoost` value itself: the boost continues to participate in priority-weighted roulette selection.

With `ape.mopTargetPickCap` <= 0 (zero or negative) the cap SHALL be disabled and both sites SHALL behave exactly as specified by `mop-discriminative-boost`.

When a deterministic pick increments a key's count to exactly `ape.mopTargetPickCap` (the pick that reaches the cap), the agent SHALL log `[APE-RV] MOP target capped: activity=<activity> widget=<xpath> picks=<n>` — once per key per run. Because capped keys are filtered before the next pick, this reach-the-cap event is the only occasion to log.

- **INV-SEL-MOP-04**: No (widget XPath + action type + activity) key SHALL be selected by the deterministic MOP sites more than `ape.mopTargetPickCap` times per run when the cap is positive.
- **INV-SEL-MOP-05**: With `ape.mopTargetPickCap` <= 0, action selection SHALL be identical to the uncapped specification with no cap-related side effects (no counter updates, no cap log lines).

#### Scenario: fourth pick of the same widget falls through to the roulette
- **WHEN** `ape.mopTargetPickCap=3` and the deterministic MOP sites have already selected actions of the same type targeting widget X (same XPath, same action type, same activity, across three different abstract states) — the third pick having logged `[APE-RV] MOP target capped` once as it reached the cap
- **THEN** on the next state where such an action targeting X is the only `mopBoost>0` candidate, `pickBestMopTarget` SHALL return null
- **AND** selection SHALL proceed to the roulette with X's `mopBoost` still in its priority

#### Scenario: distinct widgets are counted independently
- **WHEN** widget X has reached the cap and widget Y (different XPath) carries `mopBoost=300` unvisited
- **THEN** the deterministic sites SHALL still select Y

#### Scenario: cap disabled
- **WHEN** `ape.mopTargetPickCap=0`
- **THEN** the deterministic sites SHALL ignore pick counts entirely

### Requirement: BACK/MENU discretionary pick cap

The agent SHALL count discretionary BACK/MENU picks per run, keyed by `activity + "|" + actionType.name()` — independent of the abstract state the action belongs to, so refinement-minted sibling states cannot re-arm the count. A discretionary pick is any BACK/MENU action returned by the two EARLY_STAGE greedy phases (`findGreedyActionForward`, `findGreedyActionBackward`) or by `selectNewActionEpsilonGreedyRandomly` (the BACK unvisited short-circuit, the MENU unvisited short-circuit, the least-visited scan, or the priority roulette). When a key's count has reached `ape.backMenuPickCap`, that action type SHALL be excluded from all six discretionary channels for that activity: the EARLY_STAGE backward direct unvisited-BACK pick skips it (falling through to backtrack), the EARLY_STAGE forward roulette drops it from its `randomPickWithPriority` candidates, the epsilon-greedy short-circuits skip it, and a stable wrapped `ActionFilter` removes the type from the candidate set shared by `greedyPickLeastVisited` and `randomlyPickAction`.

Navigation-essential sites SHALL NOT consult the cap: `selectNewActionBackToActivity`, `backToTrivialActivity`, `checkBackTrack`, and `handleNullAction` keep emitting BACK without limit, preserving the agent's ability to navigate back, backtrack to unsaturated states, and escape action-less screens (the gh9 stuck-loop guard).

With `ape.backMenuPickCap <= 0` the cap SHALL be disabled: selection behavior is identical to the uncapped specification, with no counter updates and no cap log lines.

When a discretionary pick increments a key's count to exactly `ape.backMenuPickCap`, the agent SHALL log `[APE-RV] BACK/MENU capped: activity=<activity> type=<type> picks=<n>` — once per key per run (capped keys are filtered from subsequent discretionary picks, so the reach-the-cap event cannot recur).

- **INV-SEL-NAV-01**: No (activity, BACK/MENU action type) key SHALL be selected by the discretionary channels (the two EARLY_STAGE greedy phases and the epsilon-greedy short-circuits/least-visited/roulette) more than `ape.backMenuPickCap` times per run when the cap is positive.
- **INV-SEL-NAV-02**: With `ape.backMenuPickCap <= 0`, action selection SHALL be identical to the uncapped specification with no cap-related side effects.
- **INV-SEL-NAV-03**: The navigation-essential BACK sites SHALL remain unbounded regardless of the cap state.
- **INV-SEL-NAV-04**: The wrapped filter SHALL be stable within one roulette invocation (identical include decisions across the counting and picking passes).
- **INV-SEL-NAV-05**: The two EARLY_STAGE greedy phases SHALL consult the same `activity + "|" + actionType` count as the epsilon-greedy channels: the `findGreedyActionBackward` direct unvisited-BACK pick SHALL be skipped when the BACK key is capped, and capped target-less BACK/MENU SHALL be excluded from the `findGreedyActionForward` roulette candidates; a BACK/MENU winning either EARLY_STAGE phase SHALL count toward the cap.

#### Scenario: fourth discretionary MENU pick is filtered
- **WHEN** `ape.backMenuPickCap=3` and the discretionary channels have already returned the MENU action of activity `com.x.A` three times (across any mix of sibling states, the third pick having logged `[APE-RV] BACK/MENU capped` once)
- **THEN** on the next `selectNewActionEpsilonGreedyRandomly` invocation in `com.x.A`, the MENU short-circuit SHALL NOT fire even if the state's MENU action is unvisited
- **AND** the least-visited scan and the roulette SHALL NOT include the MENU action among their candidates

#### Scenario: EARLY_STAGE backward — capped BACK is not directly picked
- **WHEN** `ape.backMenuPickCap=3`, the BACK key of activity `com.x.A` has reached the cap, and `findGreedyActionBackward` runs on a state of `com.x.A` whose BACK action is unvisited (`ENABLED_VALID_UNVISITED.include(back)` is true)
- **THEN** the direct unvisited-BACK pick (`SataAgent.java:1278-1283`) SHALL NOT fire
- **AND** the method SHALL fall through to the backtrack path instead

#### Scenario: EARLY_STAGE forward — capped BACK/MENU is excluded from the roulette candidates
- **WHEN** `ape.backMenuPickCap=3`, the MENU key of activity `com.x.A` has reached the cap, and `findGreedyActionForward` runs on a state of `com.x.A` whose greedy candidates include the MENU action
- **THEN** the MENU action SHALL be excluded from the `randomPickWithPriority` candidate list
- **AND** a BACK or MENU action still below the cap that wins the roulette SHALL be recorded against its `activity + "|" + actionType` key (and SHALL log `[APE-RV] BACK/MENU capped` if the record reaches the cap)

#### Scenario: refinement does not re-arm the count
- **WHEN** a capped BACK key exists for activity `com.x.A` and NamingFactory refinement mints a new sibling state of the same activity whose fresh BACK action is unvisited
- **THEN** the BACK short-circuit SHALL NOT fire for the new sibling (the key is activity-scoped, not state-scoped)

#### Scenario: navigation BACK is never capped
- **WHEN** the BACK key of activity `com.x.A` is capped and the agent needs to return to the target activity via `selectNewActionBackToActivity` (or reaches `handleNullAction` with BACK as the only valid action)
- **THEN** BACK SHALL be emitted normally by those sites

#### Scenario: cap disabled
- **WHEN** `ape.backMenuPickCap=0` (or negative)
- **THEN** all discretionary channels SHALL behave exactly as before this change, with no counter updates and no cap log lines

### Requirement: Per-step counterfactual attribution

When the step's action was picked by one of the four MOP-sensitive channels (`short_circuit_unvisited`, `short_circuit_0step`, `roulette_greedy`, `roulette_early`), the `StepRecord`'s decision section SHALL additionally carry `dec.cf` with the counterfactual action and whether it differs from the factual pick: the action the same channel would have selected with `mopBoost` and `mopFrontierBoost` zeroed on every candidate (all other boosts and the candidate set unchanged). The counterfactual action SHALL use the same action rendering as `dec.action`. Records from other channels (LLM, launcher, buffer, `sata_other`) SHALL NOT carry `dec.cf` — MOP boosts do not participate in those picks. `dec.cf` is exempt from the defaults-omitted rule: its absence is itself information, so it is emitted whenever defined (`event-sink` INV-SNK-05). The `stepTelemetryEnabled` gate of the pre-change requirement is deleted with the key: the counterfactual is recorded unconditionally, in every arm.

Channel semantics:
- The two short-circuits exist only because of MOP boosts: their counterfactual is the pick of the channel they short-circuit (the roulette/least-visited fall-through with MOP weights zeroed), and the changed flag is set whenever the short-circuit fired on a target the fall-through would not have picked.
- The two roulettes recompute the weighted pick over the same candidates with per-action priority reduced by that action's `mopBoost + mopFrontierBoost`.

**RNG stream isolation (hard constraint).** The counterfactual recomputation SHALL NOT advance or otherwise perturb the seeded RNG stream that drives selection (INV-EXPL-14 — the seeded run's action sequence must be bit-identical with the counterfactual computation on or off). The roulette counterfactual SHALL reuse the factual pick's recorded draw (as a fraction of total weight) rather than drawing again; the short-circuit counterfactuals are deterministic and draw nothing. A dedicated test SHALL assert sequence identity under a fixed seed with the counterfactual enabled and disabled — perturbing the stream would be a silent bias of the exact class the calibration autopsy catalogued. This is a distinct property from sink neutrality (`event-sink` INV-SNK-07) and both tests SHALL exist.

**Honest caveat (interpretation rule, part of this contract):** the counterfactual is 1-step myopic. It establishes the *divergence point* — "did the MOP boost change THIS pick?" — not the cumulative trajectory effect, which only an arm-level contrast (MOP-off arm, rv-android side) can measure. Offline analysis SHALL NOT sum the changed flag into a claim about end-of-run coverage.

**Failure containment:** if the recomputation fails for any reason, the record SHALL carry the counterfactual as unchanged and selection SHALL be unaffected (the factual pick was already made before the counterfactual runs).

#### Scenario: MOP short-circuit divergence recorded

- **WHEN** the unvisited-MOP short-circuit selects action A (`mopBoost=500`) and the fall-through roulette with MOP weights zeroed would have picked action B
- **THEN** the record SHALL carry `dec.ch:"short_circuit_unvisited"` and a `dec.cf` naming B as the counterfactual action with the changed flag set

#### Scenario: roulette pick unchanged by MOP weights

- **WHEN** the epsilon-greedy roulette picks action C and the recomputation with `mopBoost`/`mopFrontierBoost` zeroed — replaying the same recorded draw fraction — also picks C
- **THEN** `dec.cf` SHALL report the counterfactual action as C with the changed flag clear

#### Scenario: MOP-off arm is counterfactually inert

- **WHEN** a run executes with all MOP weights zero (MOP-off arm)
- **THEN** every emitted `dec.cf` SHALL have the changed flag clear
- **AND** any set flag in such a run SHALL be treated as a defect in the counterfactual implementation (smoke-gate invariant)

#### Scenario: seeded sequence identical with counterfactual on and off

- **WHEN** two runs execute with the same seed, APK, and configuration, one with the counterfactual computation enabled and one with it disabled
- **THEN** the sequence of selected actions SHALL be identical (the live RNG stream consumed exactly the same draws)

#### Scenario: non-MOP channels carry no counterfactual fields

- **WHEN** a step is picked by the LLM stage or the buffer
- **THEN** its `StepRecord` SHALL NOT contain a `dec.cf` member

## Invariants

- **INV-SEL-01**: When `Config.leastVisitedPriorityTiebreak` is `true` (default), `greedyPickLeastVisited()` SHALL always prefer the action with the lowest `visitedCount`, regardless of priority; priority is ONLY a tiebreaker, never an override. When the flag is `false` (the `ape_pure` arm), ties among the lowest-`visitedCount` actions are broken by array order and no RV priority boost influences the pick (upstream APE) — scoped by `rv-scoring-pipeline`.
- **INV-SEL-02**: When all actions have distinct `visitedCount` values, the behavior SHALL be identical to the pre-change implementation. (Flag-independent: with distinct counts there is no tie, so `leastVisitedPriorityTiebreak` does not apply.)
- **INV-SEL-03**: The method SHALL remain O(n) — single pass over actions, no sorting.
- **INV-SEL-04**: When `Config.stepTelemetryEnabled` is `true` (default), exactly one `[APE-STEP]` line SHALL be emitted per finally-selected action, covering every selection path including the LLM early-returns and budget/trivial early-returns. The line SHALL carry a `decision_source` from a fixed enum, never a free-form string. The boost-attribution rule SHALL only change which enum value is carried; it SHALL NOT add, remove, or duplicate `[APE-STEP]` lines, and SHALL NOT modify any boost field. When the flag is `false` (the `ape_pure` arm), zero `[APE-STEP]` lines are emitted while `decisionSource` is still populated internally — scoped by `rv-scoring-pipeline`.
- **INV-SEL-05**: When `stepTelemetryEnabled` is `true`, every `[APE-STEP]` line SHALL carry exactly one `pick_channel` field whose value is a member of the fixed enum (`short_circuit_unvisited`, `short_circuit_0step`, `roulette_greedy`, `roulette_early`, `launcher`, `llm`, `buffer`, `sata_other`) — never a free-form string; the channel is provenance set at the pick site, covering every early-return path.
- **INV-SEL-06**: On every emitted `[APE-STEP]` line, `activity_has_mop` SHALL equal `MopData.activityHasMop(<current activity>)` when `MopData` is non-null, and `0` whenever `MopData` is null. The field is 0/1, never absent when the line is emitted.
- **INV-SEL-07**: Every `[APE-STEP]` line SHALL occupy exactly one physical line; no widget-derived text SHALL introduce an unescaped `\n`/`\r` into the line.
- **INV-SEL-08**: Counterfactual fields (`cf_action`, `cf_changed`) SHALL appear on exactly the lines whose `pick_channel` is one of the four MOP-sensitive channels, and on no others. In a run where every MOP weight is zero, every emitted `cf_changed` SHALL be `0`.
- **INV-SEL-09**: The counterfactual recomputation SHALL consume zero draws from the live seeded RNG stream: for a fixed seed, APK, and configuration, the selected-action sequence SHALL be identical with the counterfactual computation enabled or disabled.
- **INV-SEL-10**: Every emitted `[APE-STEP]` line for an action with a resolved target SHALL carry exactly one `patched=0|1` field reporting whether that target's clickability was written by `patchGUITree`; lines for targetless actions SHALL omit it. The bit SHALL be set at the patch sites and never inferred at emission time from post-patch attributes, which are indistinguishable from native ones by construction. `patched=1` records that the patch *wrote* the node's clickability, in either direction — it is set both where the patch grants clickability to a child and where it removes it from the parent — so a causal reading ("this action exists only because of the patch") holds for `MODEL_CLICK` and SHALL NOT be extended to other action types on a patched node.
