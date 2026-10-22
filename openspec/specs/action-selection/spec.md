## Purpose

Action selection is SATA's choice of the step's action once the decision pipeline hands the step to `SataChain`. SATA is not where MOP guidance acts: the MOP coin of `mop-guidance` ("MOP Coin Decider") is a pipeline owner placed before SATA, and SATA has no MOP shortcut, EARLY_STAGE MOP preference or revisit cap (design D-B4, D-B10, D-B12). `State.greedyPickLeastVisited()` selects the action with the lowest `visitedCount`; among the actions sharing it, the least-visited tie is rule R2 — the larger sum of the candidate's scorer fields, otherwise the first of the array — so with zero scorers mounted it is upstream APE's pick. Priority is read only where APE reads it (the priority roulettes).

Three extension points, X1 (least-visited tie), X3 (ABA target tie) and X4 (path to a trivial activity), are Strategies whose default is APE's behaviour, including its RNG consumption; the composition root mounts the MOP variants of X3 and X4 only when `MOP` is in the plan. An owner that takes a step over a non-empty SATA buffer (Budget, the LLM, the launcher, the MOP coin) abandons it without weakening the graph, a restart clears it through a new episode, also without weakening (design D-B14.13), and only a real path failure weakens an edge. The BACK/MENU cap is the ADDONS member feature `BACK_MENU_CAP`.

Each step records `dec.src`, the owner that executed it; `dec.strat`, the SATA path; `dec.cfc`, a per-scorer counterfactual at the roulettes and the least-visited pick; and `dec.draw`, `dec.nav`, `dec.bmc`. The shapes of these `dec` members are owned by `event-sink`, the owner order by `decision-pipeline`, the coin by `mop-guidance`.

## Data Contracts

### Input
- `ape.backMenuPickCap: int` — the `BACK_MENU_CAP` activation key (ADDONS member, POSITIVE, neutral `0`, jar default `3`), read through the plan.

### Side-Effects
- **[Trace]**: `[APE-RV] BACK/MENU capped: activity=<activity> type=<type> picks=<n>` — logged once per key, on the discretionary pick that reaches the cap.
- **[Telemetry]**: `dec.src`, `dec.ch`, `dec.strat`, `dec.cfc`, `dec.draw`, `dec.nav`, `dec.bmc`, `dec.buf` on the step record (shapes owned by `event-sink`).

## Requirements

### Requirement: State.greedyPickLeastVisited() — Scorer-Sum Tie (X1/R2)

`State.greedyPickLeastVisited(ActionFilter filter, LeastVisitedTie tie)` (and its overload with an `excluded`
action, the form-submit guard) SHALL select the action with the lowest `visitedCount`. `State.leastVisited` collects,
in array order, the actions sharing that lowest count; `State.pickLeastVisited` returns the single candidate
without consulting the strategy, and otherwise hands the candidates to the X1 strategy (`LeastVisitedTie`, "SATA
Navigation Extension Points (X1, X3, X4)"), which applies **rule R2** (plan P-5, design D-B4): the action whose
**scorer sum** is strictly the largest wins; on equal sums the first in array order wins. The scorer sum of an
action is the sum of its scorer fields as written by the mounted scoring passes (`mopBoost`, `mopFrontierBoost`,
`wtgBoost`, `navFrontierBoost`, `menuBoost`, `coverageBoost`, `formBoost`; `scoring-pipeline` "Scorer Ownership by
Group"), computed by `ScorerSumLeastVisitedTie.scorerSum`. The X1 strategy is `ScorerSumLeastVisitedTie`. The pick
SHALL NOT read `priority`: priority includes APE's base priority per action type, which differs between actions
with no scorer at all, and comparing it changed upstream's choice in every cell.

With zero scorers mounted every sum is 0, so the first of the array wins and the pick equals upstream APE (`model/State.java` at `8f51b99c`). With scorers mounted, a scorer term decides only a visit-count tie; it never overrides a lower visit count (INV-SEL-01). `ape.leastVisitedPriorityTiebreak` and `Feature.LEAST_VISITED_TIEBREAK` are retired ("replaced by the scorer-sum tie (X1/R2), which equals APE with zero scorers"); a properties file that sets the key aborts resolution with `retired_key` (`run-spec`). `State` reads no configuration for this pick.

Because the pick is a conversion point for every scorer, it SHALL be pinned by a paired unit test over one fixed action set — same visit counts, same base priorities, called once with scorer fields set and once with every scorer field 0, asserting different picks.

The pick writes `dec.nav` with `x:"X1"` when it resolved a tie, and the step's record carries `dec.cfc` over the scorers with a non-zero term among the tied candidates ("Per-step counterfactual attribution").

The scenario headers below that name a priority tie or a flag are kept because `openspec archive` matches scenarios
by name; no flag exists and priority is not read.

#### Scenario: Single least-visited action
- **WHEN** actions have visitedCounts [0, 3, 5]
- **THEN** the action with visitedCount=0 SHALL be selected, whatever the scorer fields, and X1 SHALL NOT be consulted

#### Scenario: Tie broken by priority (flag on)
- **WHEN** actions have visitedCounts [2, 2, 5], scorer sums [0, 500, 0] (the second carries `mopBoost=500`) and priorities [32, 532, 52]
- **THEN** the action with visitedCount=2 and scorer sum 500 SHALL be selected
- **AND** the decision SHALL come from the scorer sum, not from the priority: the flag this header names is retired and R2 holds in every cell
- **AND** the record SHALL carry `dec.nav:{"x":"X1","c":"ape","alt":0,"drew":0}`: X1 has no MOP variant, so `alt` is `0`, and the scorer's effect is read from `dec.cfc`

#### Scenario: All actions have same visitedCount (flag on)
- **WHEN** all 10 actions have visitedCount=0 and scorer sums [0, 0, 200, 0, 500, 0, 0, 0, 0, 0]
- **THEN** the action with scorer sum 500 SHALL be selected

#### Scenario: the same action set picks differently under the two argument values
- **WHEN** one fixed action set with visitedCounts [2, 2, 5] and base priorities [32, 32, 52] is passed to `greedyPickLeastVisited` twice, first with the second action carrying `coverageBoost=100` (scorer sums [0, 100, 0]) and then with every scorer field 0
- **THEN** the two calls SHALL return different actions (the second action, then the first)
- **AND** this pairing SHALL exist as a permanent unit test, since no golden, assembly check or pass test observes this decision

#### Scenario: Tie with equal priorities
- **WHEN** actions have visitedCounts [1, 1, 3], scorer sums [200, 200, 0] and priorities [252, 252, 32]
- **THEN** the first of the two tied actions SHALL be selected, and the record SHALL carry `dec.nav` with `alt:0`

#### Scenario: Tie broken by array order when the flag is off
- **WHEN** the `base` cell (no scorer mounted) calls `greedyPickLeastVisited` on actions with visitedCounts [2, 2, 5] and base priorities [32, 532, 52]
- **THEN** the first action with visitedCount=2 in array order SHALL be selected (priority=32), NOT the priority=532 action
- **AND** the pick SHALL equal upstream APE's; this is the case the retired addon G got wrong, because it compared the whole priority

### Requirement: Per-action decision-source telemetry

For every action returned by `selectNewActionNonnull()`, `StatefulAgent.resolveNewAction()` SHALL record one `StepRecord` decision section (event-sink capability), after the action is finalized and before it is executed. Recording is unconditional — no configuration key gates it, and every cell records identically. The record SHALL attribute the action to the **owner that executed the step** (`dec.src`), carry its pick channel (`dec.ch`), the owner record `dec.own` (`decision-pipeline`), and the non-zero scorer fields. Exactly one record exists per finally-selected action (INV-SEL-04).

`ModelAction` SHALL carry a `decisionSource` provenance field set at the point of selection by the selecting `DecisionPipeline` stage (decision-pipeline capability). The field SHALL be populated on every return path: the guards (`budget`, `restart`), the three LLM stages, the launcher, the MOP coin, the SATA chain and the null path. Its value set is the owner enum (`ModelAction.DecisionSource.owner()`):

`budget`, `restart`, `llm`, `launcher`, `mop`, `sata`.

`dec.src` is not a largest-boost attribution. The boost-attribution rule, its precedence `MOP > MopFrontier > WTG > Menu > Form > Coverage` and the values `MOP`, `MopFrontier`, `Coverage`, `Fuzz`, `Menu`, `WTG`, `Component`, `Form` SHALL NOT exist: a scorer's term is visible in its own field (`dec.mop`, `dec.mopf`, `dec.wtg`, `dec.navf`, `dec.menu`, `dec.cov`, `dec.form`) and its causal effect in `dec.cfc` ("Per-step counterfactual attribution"). A step owned by the MOP coin carries `dec.src:"mop"`; a step SATA chose carries `dec.src:"sata"` whatever its boosts.

When `dec.src` is `sata`, the record SHALL carry `dec.strat`, the SATA path that chose the action, from the `event-sink` enum (`buffer`, `back_to_activity`, `aba`, `early_roulette`, `greedy_global`, `greedy_path`, `trivial`, `early_backward`, `backtrack`, `eps_back`, `eps_menu`, `least_visited`, `eps_roulette`, `null`, `saturated`). It refines what the channel `sata_other` lumped together. A roulette pick additionally carries `dec.draw` (`[idx,total]`) for offline replay.

`dec.ch` is the pick channel (`ModelAction.PickChannel`, design D-B14.17): `roulette_greedy`, `roulette_early`, `launcher`, `llm`, `buffer`, `sata_other` for the SATA and LLM/launcher paths, and three values naming the owners that are not SATA paths: a step the MOP coin takes carries `mop_coin`, a step the `Budget` guard takes carries `budget`, and a step the `Restart` guard takes carries `restart`. `sata_other` is produced only by SATA paths. The values `short_circuit_unvisited` and `short_circuit_0step` are not produced.

The record's envelope SHALL carry the step number `s` — the agent exploration timestamp (`getTimestamp()`) at selection time, incremented exactly once per agent step, hence unique within a run — and the time `t` in milliseconds since `RUN_START` (whose record carries the epoch base; the device wall clock remains recoverable as `t0 + t`, preserving offline temporal joins with externally collected artifacts without any APE↔logcat coupling — the heartbeat aside, APE SHALL NOT read from logcat). There is no join key: the outcome attaches to the same record (scoring-pipeline capability).

**Where the retired rendering's assertions went.** Several scenarios below keep the header an earlier requirement gave them and carry a body that contradicts it, because what they asserted no longer exists. Recorded rather than dropped, since the reader's question is where the claim lives now:

| Scenario header | What it asserted | Where the claim is now |
|---|---|---|
| `[APE-STEP] carries the MOP-screen bit` | `activity_has_mop=0\|1` on every step line | the `ACT` dictionary entry's `mop` member, emitted once per activity and **on no step record** (`event-sink :: Dictionary Events and Run-Local IDs`) |
| `MOP-off arm always reports activity_has_mop consistent with MopData` | every line carries `activity_has_mop=0` when `MopData` is null | the same `ACT.mop` member, which is written only when `MOP` is mounted and is absent from every `ACT` entry of a cell without `MOP` (design D-B14.9) |
| `click on a patch-fabricated widget is marked` | `patched=1` on the line | `dec.patched`, whose tri-state (1 / 0 / absent) is asserted by `event-sink :: StepRecord Schema` |
| `targetless actions omit the patch bit` | no `patched` field for `MODEL_BACK`/`MODEL_MENU`/`MODEL_LLM_TAP` | the third state of that same tri-state |
| `widget text with a newline stays on one line` | the emitter flattens `\n`/`\r` | the serializer, which escapes them by construction (`event-sink` INV-SNK-02) |
| `No [APE-STEP] lines when telemetry is disabled` | zero lines with the gate off | **nothing, deliberately** — the key is deleted and aborts plan resolution as an unknown key |
| `MOP-boosted action from the EARLY_STAGE roulette attributed to MOP`, `MOP-frontier-driven pick attributed to MopFrontier, not WTG`, `Tie precedence MOP>MopFrontier>WTG>Menu>Form>Coverage` | the largest-boost attribution of `dec.src` | `dec.src` names the owner; the scorer term stays in its field, its effect in `dec.cfc` |
| `pick_channel discriminates short-circuit from roulette` | the shortcut channels | the MOP coin is an owner: `dec.src:"mop"` |
| `Every step is attributable when telemetry is enabled` | one record per action when the gate is on | INV-SEL-04, with no gate |

#### Scenario: SATA-selected action attributed

- **WHEN** `resolveNewAction()` finalizes an action chosen by the SATA epsilon-greedy roulette with every scorer field 0
- **THEN** a single `StepRecord` SHALL be recorded with `dec.src:"sata"` and `dec.strat:"eps_roulette"`
- **AND** its envelope SHALL include `s`, `t`, `act` and `st`, and its `dec` SHALL carry `ch`, `draw` and `own`, and omit all scorer fields (defaults-omitted, INV-SNK-05)

#### Scenario: Stage-stamped provenance equals the StageResult label

- **WHEN** any stage returns `StageResult.select(action, label)` for a `ModelAction`
- **THEN** `label` SHALL equal the owner value of `action.getDecisionSource()`
- **AND** the recorded `dec.src` SHALL equal the same value — one datum, stamped by the selecting stage and carried to the record without a second vocabulary (`decision-pipeline` INV-DP-04)

#### Scenario: MOP-boosted action from the EARLY_STAGE roulette attributed to MOP

- **WHEN** the EARLY_STAGE unvisited roulette picks a `ModelAction` whose scorer fields are `mop=500`, all others 0
- **THEN** the record SHALL carry `dec.src:"sata"`, `dec.strat:"early_roulette"` and `dec.mop:500`
- **AND** whether the MOP term changed the pick SHALL be carried by `dec.cfc.mop`, not by `dec.src`

#### Scenario: MOP-frontier-driven pick attributed to MopFrontier, not WTG

- **WHEN** a roulette pick carries scorer fields `mop=0, mopf=200, wtg=0, navf=0, menu=0, cov=100, form=0`
- **THEN** the record SHALL carry `dec.src:"sata"`, `dec.mopf:200` and `dec.cov:100`, with `dec.wtg` and `dec.navf` omitted
- **AND** the producers stay de-aliased in the record, one field per scorer (`scoring-pipeline` "Scorer Independence (Rule R1)")

#### Scenario: Tie precedence MOP>MopFrontier>WTG>Menu>Form>Coverage

- **WHEN** a roulette pick carries scorer fields `mop=300, mopf=300`
- **THEN** the record SHALL carry `dec.src:"sata"`, `dec.mop:300` and `dec.mopf:300`
- **AND** no precedence between scorers SHALL exist, because `dec.src` does not name a scorer

#### Scenario: click on a patch-fabricated widget is marked

- **WHEN** `patchGUITree` sets `clickable=true` on a child that the `AccessibilityNodeInfo` reported as non-clickable, and a later step selects the `MODEL_CLICK` derived from it
- **THEN** that step's record SHALL carry `dec.patched:1`
- **AND** a `MODEL_CLICK` on a node whose clickability came from the `AccessibilityNodeInfo` SHALL carry `dec.patched:0` — emitted, not omitted, because the field is exempt from the defaults-omitted rule
- **AND** the interpretation rules are unchanged: the bit records node provenance, not action causality, and offline analysis SHALL condition on `MODEL_CLICK` before reading it causally

#### Scenario: targetless actions omit the patch bit

- **WHEN** the selected action is `MODEL_BACK`, `MODEL_MENU` or `MODEL_LLM_TAP`
- **THEN** the record SHALL carry no `dec.patched` member — the third state of the tri-state, consistent with the other target-derived fields

#### Scenario: [APE-STEP] carries the MOP-screen bit

- **WHEN** the cell mounts `MOP` and the current activity is in the selected MOP-activity set
- **THEN** the activity's `ACT` dictionary entry SHALL carry `mop:1`, and the step record SHALL carry no `activity_has_mop` member of its own
- **AND** an activity outside the set SHALL have `mop:0` on its entry — the bit is recorded once per activity and reached from a step via its `act` ID

#### Scenario: MOP-off arm always reports activity_has_mop consistent with MopData

- **WHEN** a cell without `MOP` runs (`base`, `as`, `as_llm`), so no `MopData` is handed to any consumer
- **THEN** no `ACT` entry of that run SHALL carry a `mop` member (design D-B14.9; `event-sink` clean-control gate)
- **AND** no step record SHALL carry a MOP-screen bit of its own, in this cell or any other

#### Scenario: pick_channel discriminates short-circuit from roulette

- **WHEN** one step is taken by the MOP coin and a later step by the epsilon-greedy roulette
- **THEN** the first record SHALL carry `dec.src:"mop"`, `dec.ch:"mop_coin"` and no `dec.strat`
- **AND** the second SHALL carry `dec.src:"sata"`, `dec.ch:"roulette_greedy"` and `dec.strat:"eps_roulette"`
- **AND** no record SHALL carry `dec.ch:"short_circuit_unvisited"` or `dec.ch:"short_circuit_0step"`

#### Scenario: Budget stage early-return attributed

- **WHEN** the `BUDGET` guard selects the trivial-activity action on an exhausted budget
- **THEN** that action's `decisionSource` SHALL be `budget`
- **AND** exactly one record SHALL be recorded for it, carrying `dec.src:"budget"`, `dec.ch:"budget"` and no `dec.strat`
- **AND WHEN** the `Restart` guard selects a start action on a later step
- **THEN** its record SHALL carry `dec.src:"restart"`, `dec.ch:"restart"`, `dec.restart` and no `dec.strat`

#### Scenario: Launcher step attributed Component

- **WHEN** the `MopLauncher` stage (feature `MOP_LAUNCHER`) selects an `EVENT_TRIGGER_ACTIVITY` action
- **THEN** the record SHALL carry `dec.src:"launcher"` and `dec.ch:"launcher"`
- **AND** it SHALL additionally carry `dec.comp` — evidence of what the launch did, written when the record closes at step N+1 (`component-triggering` INV-CT-07)

#### Scenario: widget text with a newline stays on one line

- **WHEN** the selected action's resolved node text is `"Sign\nIn"`
- **THEN** the record's `dec.a` SHALL carry the text with the newline escaped, and the record SHALL occupy exactly one physical line
- **AND** well-formedness SHALL NOT depend on any pre-flattening by the emitter: the serializer escapes `\n`, `\r` and every character below U+0020 by construction (`event-sink` INV-SNK-02)

#### Scenario: Boosted action in a priority-blind branch stays SATA

- **WHEN** a `ModelAction` carrying `mop=500` is selected by a branch that does not consume scorer terms (e.g. a shortest-path navigation pick)
- **THEN** the record SHALL carry `dec.src:"sata"`, the branch's `dec.strat`, and `dec.mop:500` still visible as a scorer field

#### Scenario: LLM early-return attributed with its channel

- **WHEN** the new-state LLM stage returns a non-null action
- **THEN** that action's `decisionSource` SHALL be `llm` and exactly one record SHALL carry `dec.src:"llm"`, `dec.ch:"llm"` and `dec.own.trg:"new_state"`, with the call's `llm[]` sub-event in the same record

#### Scenario: Every step is attributable when telemetry is enabled

- **WHEN** any run completes, under any cell preset
- **THEN** every executed action SHALL have exactly one `StepRecord`
- **AND** no selection path SHALL produce zero or more than one record for a single action
- **AND** every record SHALL carry exactly one `dec.src`, exactly one `dec.ch` and one `dec.own`, and, in a cell with `MOP`, the MOP-screen bit SHALL be reachable for it through its `act` ID
- **AND** the scenario's precondition is vacuous and is kept only so the archive can pair the name: there is no enabled case because there is no gate

#### Scenario: No [APE-STEP] lines when telemetry is disabled

- **WHEN** `ape.properties` sets `ape.stepTelemetryEnabled=false`
- **THEN** plan resolution SHALL abort with an unknown-key diagnostic before the first event — the key is deleted, not defaulted, so no run can execute in the state this scenario describes
- **AND** `decisionSource` is populated on every selection path unconditionally, and recording is unconditional too

### Requirement: BACK/MENU discretionary pick cap

The BACK/MENU cap is the ADDONS member feature `BACK_MENU_CAP` (design D-B5), activated by `ape.backMenuPickCap` (POSITIVE, neutral `0`, jar default `3`, depends on `ADDONS`). When the feature is absent from the resolved plan — `ADDONS` off, or `ape.backMenuPickCap <= 0` — no counter exists and selection is upstream's. `SataAgent` SHALL read the cap from the plan, never from `Config`.

With the feature present, the agent SHALL count discretionary BACK/MENU picks per run, keyed by `activity + "|" + actionType.name()` — independent of the abstract state the action belongs to, so refinement-minted sibling states cannot re-arm the count. A discretionary pick is any BACK/MENU action returned by the two EARLY_STAGE greedy phases (`findGreedyActionForward`, `findGreedyActionBackward`) or by `selectNewActionEpsilonGreedyRandomly` (the BACK unvisited short-circuit, the MENU unvisited short-circuit, the least-visited scan, or the priority roulette). When a key's count has reached `ape.backMenuPickCap`, that action type SHALL be excluded from all six discretionary channels for that activity: the EARLY_STAGE backward direct unvisited-BACK pick skips it (falling through to backtrack), the EARLY_STAGE forward roulette drops it from its `randomPickWithPriority` candidates, the epsilon-greedy short-circuits skip it, and a stable wrapped `ActionFilter` removes the type from the candidate set shared by `greedyPickLeastVisited` and `randomlyPickAction`.

Navigation-essential sites SHALL NOT consult the cap: `selectNewActionBackToActivity`, `backToTrivialActivity`, `checkBackTrack`, and `handleNullAction` keep emitting BACK without limit, preserving the agent's ability to navigate back, backtrack to unsaturated states, and escape action-less screens (the gh9 stuck-loop guard).

When a discretionary pick increments a key's count to exactly `ape.backMenuPickCap`, the agent SHALL log `[APE-RV] BACK/MENU capped: activity=<activity> type=<type> picks=<n>` — once per key per run (capped keys are filtered from subsequent discretionary picks, so the reach-the-cap event cannot recur).

Whenever the cap removes candidates from the pick that chooses the step's action, the step's record SHALL carry `dec.bmc` with the number removed (`event-sink`); the field is omitted when it is 0 (INV-SEL-15). The count is computed from the filter's include decisions, without a draw.

- **INV-SEL-NAV-01**: No (activity, BACK/MENU action type) key SHALL be selected by the discretionary channels (the two EARLY_STAGE greedy phases and the epsilon-greedy short-circuits/least-visited/roulette) more than `ape.backMenuPickCap` times per run when `BACK_MENU_CAP` is in the plan.
- **INV-SEL-NAV-02**: With `BACK_MENU_CAP` absent from the plan, action selection SHALL be identical to the uncapped specification with no cap-related side effects (no counter, no log line, no `dec.bmc`).
- **INV-SEL-NAV-03**: The navigation-essential BACK sites SHALL remain unbounded regardless of the cap state.
- **INV-SEL-NAV-04**: The wrapped filter SHALL be stable within one roulette invocation (identical include decisions across the counting and picking passes).
- **INV-SEL-NAV-05**: The two EARLY_STAGE greedy phases SHALL consult the same `activity + "|" + actionType` count as the epsilon-greedy channels: the `findGreedyActionBackward` direct unvisited-BACK pick SHALL be skipped when the BACK key is capped, and capped target-less BACK/MENU SHALL be excluded from the `findGreedyActionForward` roulette candidates; a BACK/MENU winning either EARLY_STAGE phase SHALL count toward the cap.

#### Scenario: fourth discretionary MENU pick is filtered
- **WHEN** `ape.backMenuPickCap=3` under the `as` cell and the discretionary channels have already returned the MENU action of activity `com.x.A` three times (across any mix of sibling states, the third pick having logged `[APE-RV] BACK/MENU capped` once)
- **THEN** on the next `selectNewActionEpsilonGreedyRandomly` invocation in `com.x.A`, the MENU short-circuit SHALL NOT fire even if the state's MENU action is unvisited
- **AND** the least-visited scan and the roulette SHALL NOT include the MENU action among their candidates, and the step's record SHALL carry `dec.bmc:1`

#### Scenario: EARLY_STAGE backward — capped BACK is not directly picked
- **WHEN** `ape.backMenuPickCap=3`, the BACK key of activity `com.x.A` has reached the cap, and `findGreedyActionBackward` runs on a state of `com.x.A` whose BACK action is unvisited (`ENABLED_VALID_UNVISITED.include(back)` is true)
- **THEN** the direct unvisited-BACK pick SHALL NOT fire
- **AND** the method SHALL fall through to the backtrack path instead

#### Scenario: EARLY_STAGE forward — capped BACK/MENU is excluded from the roulette candidates
- **WHEN** `ape.backMenuPickCap=3`, the MENU key of activity `com.x.A` has reached the cap, and `findGreedyActionForward` runs on a state of `com.x.A` whose greedy candidates include the MENU action
- **THEN** the MENU action SHALL be excluded from the `randomPickWithPriority` candidate list, and a step chosen by that roulette SHALL carry `dec.bmc:1`
- **AND** a BACK or MENU action still below the cap that wins the roulette SHALL be recorded against its `activity + "|" + actionType` key (and SHALL log `[APE-RV] BACK/MENU capped` if the record reaches the cap)

#### Scenario: refinement does not re-arm the count
- **WHEN** a capped BACK key exists for activity `com.x.A` and NamingFactory refinement mints a new sibling state of the same activity whose fresh BACK action is unvisited
- **THEN** the BACK short-circuit SHALL NOT fire for the new sibling (the key is activity-scoped, not state-scoped)

#### Scenario: navigation BACK is never capped
- **WHEN** the BACK key of activity `com.x.A` is capped and the agent needs to return to the target activity via `selectNewActionBackToActivity` (or reaches `handleNullAction` with BACK as the only valid action)
- **THEN** BACK SHALL be emitted normally by those sites, with no `dec.bmc`

#### Scenario: cap disabled
- **WHEN** `ape.backMenuPickCap=0` (or negative), or the cell has no `ADDONS` (`base`, `mop`)
- **THEN** `BACK_MENU_CAP` SHALL be absent from the plan's `features`, and every discretionary channel SHALL behave as upstream, with no counter updates, no cap log lines and no `dec.bmc`

### Requirement: Per-step counterfactual attribution

When the step's action was chosen by a priority roulette that records `dec.draw` (the EARLY_STAGE unvisited roulette, the epsilon-greedy roulette `State.randomlyPickAction`, and the roulette of the null-action handler) or by the X1 least-visited pick, the `StepRecord`'s decision section SHALL additionally carry `dec.cfc`, a per-scorer counterfactual (plan §5, design § Data Flow 4): `{"<scorer>":0|1}`, one key for each **mounted** scorer whose field is non-zero on at least one candidate of that pick, keyed by the scorer's record name (`mop`, `mopf`, `wtg`, `navf`, `cov`, `menu`, `form`). The value is `1` when the same pick, recomputed with that scorer's field zeroed on every candidate (the other fields and the candidate set unchanged), would have chosen a different action, and `0` otherwise. `dec.cf` is not written (P3).

Records from other picks (the guards, the LLM, the launcher, the MOP coin, the buffer, the navigation paths) SHALL NOT carry `dec.cfc`: no scorer term takes part in them. A pick with no mounted scorer term among its candidates carries no `dec.cfc`. Because the absence of a key is information, `dec.cfc` is exempt from the defaults-omitted rule for its `0` values (`event-sink` INV-SNK-05).

Recomputation per pick type:
- A roulette recomputes the weighted pick over the same candidates with each candidate's weight reduced by the zeroed scorer's term, replaying the factual pick's recorded draw as a fraction of total weight.
- The X1 pick recomputes R2 over the same tied candidates with the zeroed scorer's field removed from every sum (`ScorerSumLeastVisitedTie.pickWithout`); it is deterministic.

The recomputation lives in `model/ScorerCounterfactual`.

**RNG stream isolation (hard constraint).** The counterfactual recomputation SHALL NOT advance or otherwise perturb any seeded stream (the agent stream, the helper stream, or the own streams of `decision-pipeline`), SHALL call no `resolveAction`, and SHALL touch no cache (INV-SEL-09). A dedicated test SHALL assert sequence identity under a fixed seed with the counterfactual enabled and disabled. This is a distinct property from sink neutrality (`event-sink` INV-SNK-07) and both tests SHALL exist.

**Honest caveat (interpretation rule, part of this contract):** the counterfactual is 1-step myopic. It establishes the *divergence point* — "did this scorer change THIS pick?" — not the cumulative trajectory effect, which only a cell-level contrast can measure. Offline analysis SHALL NOT sum the changed flags into a claim about end-of-run coverage.

**Failure containment:** if the recomputation fails for any reason, the scorer's value SHALL be recorded as `0` and selection SHALL be unaffected (the factual pick was already made before the counterfactual runs).

#### Scenario: MOP short-circuit divergence recorded

- **WHEN** the MOP coin selects action A (`mopWeight=500`) in a cell with `MOP`
- **THEN** the record SHALL carry `dec.src:"mop"` and no `dec.cfc`, because the coin is an owner and not a weighted pick
- **AND** the divergence the shortcut counterfactual used to report is read from the owner: the step would have gone to SATA without the coin

#### Scenario: roulette pick unchanged by MOP weights

- **WHEN** the epsilon-greedy roulette picks action C among candidates carrying `mop` and `cov` terms, and the recomputations with `mop` zeroed and with `cov` zeroed — each replaying the same recorded draw fraction — both also pick C
- **THEN** the record SHALL carry `dec.cfc:{"mop":0,"cov":0}`

#### Scenario: MOP-off arm is counterfactually inert

- **WHEN** a cell without `MOP` runs (`base`, `as`, `as_llm`)
- **THEN** no `dec.cfc` SHALL carry the key `mop`, `mopf`, `wtg` or `menu`
- **AND** any such key in such a run SHALL be treated as a defect of the clean-control gate (`event-sink`, `CleanControlTest`)

#### Scenario: seeded sequence identical with counterfactual on and off

- **WHEN** two runs execute with the same seed, APK, and configuration, one with the counterfactual computation enabled and one with it disabled
- **THEN** the sequence of selected actions SHALL be identical (every seeded stream consumed exactly the same draws)

#### Scenario: non-MOP channels carry no counterfactual fields

- **WHEN** a step is picked by the LLM stage, the MOP coin, the launcher or the buffer
- **THEN** its `StepRecord` SHALL contain neither a `dec.cfc` nor a `dec.cf` member

### Requirement: SATA Navigation Extension Points (X1, X3, X4)

`SataAgent` SHALL take three tie-breaks through Strategy interfaces, each mounted once by the composition root (`SataAgent.mountExtensionPoints`, called from the `SataAgent` constructor) from the resolved plan. There is no plan key for any of them.

```java
interface LeastVisitedTie { ModelAction pick(List<ModelAction> leastVisited); }      // X1
interface AbaTargetTie    { boolean replaces(State incumbent, State candidate); }    // X3
interface TrivialPathPick { Subsequence pick(List<Subsequence> paths); }             // X4
```

The implementations are named `<Variant><Interface>` (design D-B12): X1 `ScorerSumLeastVisitedTie`; X3
`ApeAbaTargetTie` and `MopAbaTargetTie`; X4 `ApeTrivialPathPick` and `MopTrivialPathPick`.

| Point | Site | APE default (mounted without `MOP`) | MOP variant (mounted with `MOP`) |
|---|---|---|---|
| X1 | the visit-count tie of `State.greedyPickLeastVisited` | `ScorerSumLeastVisitedTie`: rule R2 ("State.greedyPickLeastVisited() — Scorer-Sum Tie (X1/R2)"); draws nothing | none: X1 has one strategy in every cell |
| X3 | the equal-visit-count tie between two greedy targets of the ABA path, in both the same-activity branch (state visit counts) and the cross-activity branch (activity visit counts) | `ApeAbaTargetTie`: never replace the incumbent on a tie; draws nothing | `MopAbaTargetTie`: replace when `stateMopDensity(candidate) > stateMopDensity(incumbent)` (`mop-guidance` "Navigation Tie-Breaks as MOP Variants"); draws nothing |
| X4 | the choice among the shortest paths to a trivial activity | `ApeTrivialPathPick`: always `RandomHelper.randomPick(paths)`, one draw, also for a single path (upstream `SataAgent` at `8f51b99c`) | `MopTrivialPathPick`: the first path whose end state holds the strictly largest density; `randomPick` when every density is equal |

The defaults reproduce upstream APE including RNG consumption, so a cell without `MOP` shifts no draw at these sites (INV-SEL-11). The selection of a variant SHALL happen only at mount time; no use site SHALL branch on MOP availability (INV-SEL-12).

Whenever an extension point is consulted with two or more candidates, the step's record SHALL carry `dec.nav` (`event-sink`) as `{"x":"X1"|"X3"|"X4", "c":"ape"|"mop", "alt":0|1, "drew":0|1}`: `x` the point; `c` the mounted strategy (`ape` for X1 in every cell); `drew` is `1` when the point consumed a draw. `alt` is `1` if and only if the MOP variant decided by its own rule: for X3, `MopAbaTargetTie` replaced the incumbent; for X4, `MopTrivialPathPick` chose the path by the strictly largest density, without a draw. Otherwise `alt` is `0`: X1 always writes `alt:0` with `c:"ape"`, whatever the scorer sum did (its effect is in `dec.cfc`), and in a cell without `MOP` every `dec.nav` carries `alt:0`. A point consulted with a single candidate writes no `dec.nav`; in particular a single path at X4 still calls `randomPick` once (INV-SEL-11) but records no `dec.nav`. A tie resolved inside a step whose action comes from a later strategy (an ABA probe that is not taken) MAY be overwritten by the last tie of the same step; at most one `dec.nav` member exists per record. The computation of `dec.nav` SHALL be pure.

The rule holds whatever owner takes the step. X4 is also consulted by the `Budget` guard, whose `selectNewActionForTrivialActivity()` search runs through `SataAgent.budgetNavigation()`: when that search ties two or more paths and the guard returns the action, the step's record SHALL carry the X4 `dec.nav` of that choice, handed from the step's trace to the sink with nothing else of the trace (design D-B21). A budget step SHALL still carry no `dec.strat`, although the search stamps one in the trace (`activity-budget`). Handing the `nav` over is pure telemetry: no draw and no decision moves.

#### Scenario: X3 default never replaces on a tie
- **WHEN** a cell without `MOP` runs the ABA scan and two greedy targets of the same activity both have visit count 4, the incumbent found first
- **THEN** the incumbent SHALL stay the target and no draw SHALL be consumed
- **AND** the record SHALL carry `dec.nav:{"x":"X3","c":"ape","alt":0,"drew":0}`

#### Scenario: X3 MOP variant prefers the denser target
- **WHEN** a cell with `MOP` runs the same scan and the candidate's `stateMopDensity` is 3 while the incumbent's is 1
- **THEN** the candidate SHALL replace the incumbent
- **AND** the record SHALL carry `dec.nav:{"x":"X3","c":"mop","alt":1,"drew":0}`

#### Scenario: X4 default always draws
- **WHEN** a cell without `MOP` finds two shortest paths to a trivial activity, end-state densities 0 and 2 as the MOP data would compute them
- **THEN** the path SHALL be chosen by `RandomHelper.randomPick`, consuming one helper-stream draw
- **AND** the record SHALL carry `dec.nav:{"x":"X4","c":"ape","alt":0,"drew":1}`
- **AND WHEN** a single path is found
- **THEN** `randomPick` SHALL still be called once, as upstream does, and the record SHALL carry no `dec.nav`, because the point was consulted with one candidate

#### Scenario: X4 MOP variant takes the densest path without drawing
- **WHEN** a cell with `MOP` finds three shortest paths whose end-state densities are 0, 2, 2
- **THEN** the second path SHALL be chosen, no draw SHALL be consumed, and the record SHALL carry `dec.nav:{"x":"X4","c":"mop","alt":1,"drew":0}`
- **AND WHEN** the densities are 1, 1, 1
- **THEN** `randomPick` SHALL choose and the record SHALL carry `dec.nav:{"x":"X4","c":"mop","alt":0,"drew":1}`

#### Scenario: a budget step records its X4 tie
- **WHEN** in the `as` cell the `Budget` guard takes a step and its trivial-activity search found two shortest paths, chosen by `randomPick`
- **THEN** the record SHALL carry `dec.src:"budget"`, `dec.ch:"budget"` and `dec.nav:{"x":"X4","c":"ape","alt":0,"drew":1}`
- **AND** it SHALL carry no `dec.strat`
- **AND** the helper-stream draws and the chosen action SHALL be those of the same step without the `dec.nav` hand-off

#### Scenario: the base cell reproduces upstream at the three points
- **WHEN** the `base` cell and upstream APE `8f51b99c` run the same parity fixture with the same seed through an ABA scan, a least-visited tie and a trivial-activity path choice
- **THEN** the three choices and the helper-stream position after them SHALL be identical (`parity-oracle`)

### Requirement: Buffer Abandonment Without Weakening

`StatefulAgent` SHALL offer two distinct ways of emptying the SATA action buffer:

- `clearBuffer()` — a **real path failure**: the buffered path no longer matches the observed state or action (the mismatch checks of `StatefulAgent.selectNewActionFromBuffer`), and the other upstream sites that call it (`refillBuffer`, `resetTrace`, `onActivityStopped`). It SHALL keep upstream behaviour: weaken the next planned edge with `Graph.weakenStateTransition`, then clear the buffer.
- `abandonBuffer(String owner)` — a **preemption**: an owner placed before SATA in the pipeline (`budget`, `llm`, `launcher`, `mop`) takes a step while the buffer is non-empty. It SHALL clear the buffer **without** weakening any edge (plan P-4, D-A6, design D-B14.13), record `dec.buf` with the owner on the step's record and increment `RUN_END.counters.bufAbandoned`.

A restart step does not go through `abandonBuffer`: `StatefulAgent.restartAction()` empties the buffer before the new episode's `resetTrace` runs, so no edge is weakened (design D-B14.13). It records no `dec.buf` and does not count in `bufAbandoned`.

The LLM stages, the launcher and the MOP coin reach `abandonBuffer` through the `StageCollaborators::abandonBuffer` reference injected at assembly; the `Budget` guard reaches it through `budgetNavigation()` (`decision-pipeline`). An empty buffer is a no-op for both operations, with no `dec.buf` and no counter change.

#### Scenario: LLM coin preempts a buffered path
- **WHEN** the buffer holds a 3-edge path A→B→C→D, the step is in state A, and the LLM coin takes the step
- **THEN** the buffer SHALL be empty after the step, the strength of edge A→B SHALL be unchanged, and the record SHALL carry `dec.src:"llm"` and `dec.buf:"llm"`
- **AND** `RUN_END.counters.bufAbandoned` SHALL be one larger

#### Scenario: MOP coin preempts a buffered path
- **WHEN** the buffer is non-empty and the MOP coin takes the step
- **THEN** the record SHALL carry `dec.src:"mop"` and `dec.buf:"mop"`, and no edge SHALL be weakened

#### Scenario: Budget guard preempts a buffered path
- **WHEN** the buffer is non-empty and the `Budget` guard takes the step
- **THEN** the record SHALL carry `dec.src:"budget"` and `dec.buf:"budget"`, and no edge SHALL be weakened

#### Scenario: a restart clears the buffer through a new episode
- **WHEN** the buffer is non-empty and the `Restart` guard takes the step
- **THEN** the buffer SHALL be empty after the step, no edge SHALL be weakened, and the record SHALL carry `dec.src:"restart"` and no `dec.buf`

#### Scenario: a real path failure still weakens
- **WHEN** the buffer's next edge is A→B with action `a`, and the agent observes state E instead of A
- **THEN** `clearBuffer()` SHALL weaken edge A→B before clearing, as upstream APE does
- **AND** the record SHALL carry no `dec.buf`

## Invariants

- **INV-SEL-01**: `greedyPickLeastVisited()` SHALL always prefer the action with the lowest `visitedCount`; among the actions sharing it, the X1 strategy decides by rule R2 (the larger scorer sum, otherwise array order). Priority SHALL NOT be read by the pick. With zero scorers mounted, the pick SHALL equal upstream APE's.
- **INV-SEL-02**: When all actions have distinct `visitedCount` values, the pick SHALL be the minimum and the X1 strategy SHALL NOT be consulted.
- **INV-SEL-03**: The method SHALL remain O(n) — single pass over actions, no sorting.
- **INV-SEL-04**: Exactly one `StepRecord` SHALL be recorded per finally-selected action, covering every selection
  path including the guards, the LLM stages, the launcher and the MOP coin, in every cell. Recording is
  unconditional: no plan key gates it. The record SHALL carry a `dec.src` from the owner enum, never a free-form
  string, and recording SHALL NOT modify any scorer field.
- **INV-SEL-05**: Every record SHALL carry exactly one `dec.src` from the owner enum `budget`, `restart`, `llm`, `launcher`, `mop`, `sata`, and every record with `dec.src:"sata"` SHALL carry exactly one `dec.strat` from the fixed enum of `event-sink`. Every record SHALL carry one `dec.ch`; a step the MOP coin takes carries `dec.ch:"mop_coin"`, a step the `Budget` guard takes `dec.ch:"budget"` and a step the `Restart` guard takes `dec.ch:"restart"`, and the channel values `short_circuit_unvisited` and `short_circuit_0step` SHALL NOT be produced (design D-B14.17).
- **INV-SEL-06**: When `MOP` is in the plan, every `ACT` dictionary entry SHALL carry `mop` equal to
  `MopData.activityHasMop(<activity>)` as `0|1` (`StatefulAgent.activityHasMop`, handed to
  `NdjsonSink.beginStep`). When `MOP` is not in the plan, no `ACT` entry SHALL carry `mop`. No step record SHALL
  carry an `activity_has_mop` member: a step reaches the bit through its `act` id.
- **INV-SEL-07**: Every `StepRecord` SHALL occupy exactly one physical line; no widget-derived text SHALL introduce an
  unescaped `\n` or `\r` into it. The property is the serializer's (`ape.runtime.Json`, `event-sink` INV-SNK-02).
- **INV-SEL-08**: `dec.cfc` SHALL appear only on records whose pick was a priority roulette that writes `dec.draw` or the X1 least-visited pick, and only over mounted scorers with a non-zero term in that pick's candidate set. In a cell without MOP, `dec.cfc` SHALL carry none of the keys `mop`, `mopf`, `wtg`, `menu`.
- **INV-SEL-09**: The counterfactual recomputation of every scorer SHALL consume zero draws from any seeded stream, call no `resolveAction` and touch no cache.
- **INV-SEL-10**: For an action with a resolved target node, the step record SHALL carry `dec.patched` as `0|1`,
  reporting whether that node's clickability was written by `patchGUITree` (`GUITreeNode.isPatchedClickable()`,
  read by `StatefulAgent.patchedValue`); for a targetless action (`MODEL_BACK`, `MODEL_MENU`, `MODEL_LLM_TAP`) or an
  action with no resolved node, `dec.patched` SHALL be absent. `dec.patched:0` is written, not omitted (exempt from
  the defaults-omitted rule). The bit SHALL be set at the patch sites in `GUITreeBuilder` and never inferred at
  emission time from post-patch attributes, which are indistinguishable from native ones by construction. `1`
  records that the patch *wrote* the node's clickability, in either direction — where it grants clickability to a
  child and where it removes it from the parent — so a causal reading ("this action exists only because of the
  patch") holds for `MODEL_CLICK` and SHALL NOT be extended to other action types on a patched node.
- **INV-SEL-11**: Each extension point's APE default SHALL consume exactly the RNG draws upstream APE consumes at that site: X1 none, X3 none, X4 one `RandomHelper.randomPick` per pick.
- **INV-SEL-12**: The MOP variants of X3 and X4 SHALL be mounted if and only if `MOP` is in the resolved plan. No SATA code path SHALL test `getMopData() != null` to choose a tie-break.
- **INV-SEL-13**: A buffer abandoned by a preempting owner SHALL leave every edge strength of the graph unchanged; only a real failure of the buffered path weakens an edge.
- **INV-SEL-14**: `dec.src` SHALL be the owner that executed the step and SHALL NOT be derived from boost values.
- **INV-SEL-15**: `dec.bmc` SHALL equal the number of candidates the BACK/MENU cap removed from the pick that chose the step's action, and SHALL be absent when it is 0 or when `BACK_MENU_CAP` is not in the plan.

