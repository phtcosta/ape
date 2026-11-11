## ADDED Requirements

### Requirement: Distance-Pair Cut at Load

When the plan carries `MOP` and `ape.mopScoring=distance`, `StaticArtifactLoader` SHALL drop, while it reads the artifact, every well-formed pair with `d > c` from the widget `dist` lists (every event) and the handler `dist` lists, c being the ladder length (`run-spec` "Distance Ladder Key"). It SHALL NOT drop any `activityDist` pair. A list the cut empties reads as no pair. Flags, `mop` keys, handler membership and activity sets are not touched.

#### Scenario: Activity pair beyond the cut
- **WHEN** under `distance` with `ape.mopWeights=500,400,300` (c = 3) the artifact's `activityDist` gives activity `A` the pairs `[[0,5]]`
- **THEN** the loaded `activityDist` of `A` SHALL still be `[[0,5]]`
- **AND** with `ape.mopLauncherDmax=6` the launcher SHALL still find `A` eligible and order it at distance 5

#### Scenario: Deeper artifact under the default cut
- **WHEN** an artifact whose widget and handler lists carry pairs up to `d = 10` is loaded under `distance` with c = 3
- **AND** a second artifact, equal except that its widget and handler lists keep only the pairs with `d ≤ 3`, is loaded the same way
- **THEN** both loads SHALL yield the same `MopData`: the same pairs for every widget, event, handler and activity, the same click aggregates, the same flags and activity sets
- **AND** the two `MOP_DATA` records SHALL differ in `cutPairs` only, which counts the dropped pairs of the first artifact and is 0 for the second

#### Scenario: Ladder longer than the artifact's depth
- **WHEN** under `distance` with `ape.mopWeights=500,400,300,300,300,300` (c = 6) an artifact whose widget pairs reach only `d = 3` is loaded
- **THEN** no pair SHALL be cut and `cutPairs` SHALL be 0

#### Scenario: Cut below the generator's depth
- **WHEN** under `distance` with `ape.mopWeights=500,400` (c = 2) a widget's `click` list is `[[1,1],[2,2],[3,3]]`
- **THEN** the loaded list SHALL be `[[1,1],[2,2]]` and `cutPairs` SHALL count 1

#### Scenario: Stamped handler emptied by the cut still wins
- **WHEN** under `distance` with c = 2 a node's stamp names a handler listed in `handlers` whose only `click` pair is `[4,3]`
- **THEN** the loaded handler SHALL have no `click` pair, `lookup` SHALL still return source `STAMP` (INV-MOP-36), and a click on the node SHALL weigh 0

### Requirement: Cut Pairs Counted Apart From Malformed Pairs

The pair reader SHALL first check that a pair is well formed and count a malformed one in `droppedPairs`. It SHALL apply the cut only to a well-formed pair, and count a cut pair in `cutPairs` ("MopData — Load Status Line and Fail-Fast"). No pair SHALL be counted in both.

#### Scenario: Malformed and cut pairs counted apart
- **WHEN** under `distance` with c = 3 a widget's `click` list is `[[0,1],[1,7],[2,-1],[99,2]]` and `targets` is 27
- **THEN** the loaded list SHALL be `[[0,1]]`
- **AND** `droppedPairs` SHALL count 2 (`[2,-1]`, a negative distance, and `[99,2]`, an index out of range) and `cutPairs` SHALL count 1 (`[1,7]`)

### Requirement: Cut Applied Before the Merges

The cut SHALL be applied to each wire list as it is read, before the shortId-collision merge (INV-MOP-19) and before the click aggregate (`MopData.Widget.clickAggregate()`) is built. Both merges keep the minimum distance per target, so the result equals cutting the merged lists.

#### Scenario: Click aggregate over cut lists
- **WHEN** under `distance` with c = 2 an unstamped widget has no `click` key, `dist.drag = [[5,3]]` and `dist.select = [[5,1],[6,4]]`
- **THEN** its click aggregate SHALL be `[[5,1]]`, the same as the aggregate of the uncut lists cut at 2

### Requirement: No Cut Outside the Distance Mode

Under `ape.mopScoring=flag`, and in a cell without `MOP` (an `as` cell loads the artifact for `STATIC` only), the pair reader SHALL NOT cut, and `cutPairs` SHALL be 0. *(Author, 2026-11-03: the acceptance contract scopes `ape.mopWeights` to `distance` and is silent on `flag`; `flag` reads no widget or handler distance, so a cut would change only the load record.)*

#### Scenario: No cut under flag
- **WHEN** under `flag` an artifact whose widget lists carry pairs at `d = 7` is loaded with any `ape.mopWeights`
- **THEN** every well-formed pair SHALL be kept and `cutPairs` SHALL be 0

### Requirement: Readers Unchanged on the Remaining Pairs

On the pairs that survive the cut, the scorer, the tie split, the MOP coin's eligibility and ranking (its `d + 1` window included), retirement at `d*`, the prompt marker and `stateMopDensity` SHALL behave exactly as in `bc4bc1b8`.

#### Scenario: E7 artifact at the default ladder
- **WHEN** an artifact derived at the generator's default depth (widget and handler pairs at `d ≤ 3`) is loaded under `distance` with `ape.mopWeights` at its default
- **THEN** no pair SHALL be cut, `cutPairs` SHALL be 0, and the loaded `MopData` SHALL equal the one `bc4bc1b8` loads from the same file

## MODIFIED Requirements

### Requirement: MopScorer — Priority Boost

`MopScorer.score(MopContext, State, ModelAction)` SHALL return an integer priority boost for one valid, target-requiring action (INV-MOP-02), computed from `MopData.lookup` (INV-MOP-36) over the action's containment nodes ("Parent/child widget granularity reconciliation") for the action's event (`MopScorer.eventTypeOf`). The boost is additive: `MopWidgetPass`, the MOP group's widget scorer, mounted only when `MOP` is in the plan (`scoring-pipeline` "Scorer Ownership by Group"), adds it to `ModelAction.priority`, never replacing it (INV-MOP-03), and stores it as the action's `mopBoost`. The mode is the plan key `ape.mopScoring` (`run-spec`), `flag` or `distance`, default `flag` (design D18); the `MopContext`, built from `spec().mop()`, carries the data, the mode, the weights and the run's retirement (design D17, Plumbing). No weight is read from `Config`.

**`flag` mode.** The boost comes from the largest flag weight over the containment lookups, ties to the earlier lookup in containment order; this is the strongest flag (direct > transitive > none) whenever `mopWeightDirect >= mopWeightTransitive`, as with the defaults:

| Best containment lookup | Boost |
|-----------|-------|
| direct flag for the event | `+mopWeightDirect` (500) |
| transitive flag for the event (and not direct) | `+mopWeightTransitive` (300) |
| no flag on any containment node, or `NONE` everywhere | `0` |

On an unstamped APK every lookup takes the id key, so `flag` reproduces the E6 weights. `MopRetirement` is null under `flag`, and distances are not read.

**`distance` mode.** Let `d` be the smallest distance over the pairs of all the action's containment lookups whose target is not retired ("Target Retirement"). The weights come from the ladder `ape.mopWeights` (`run-spec` "Distance Ladder Key"; default `500,400,300`), a list of c non-negative integers, with c, its length, the cut. The **level** of `d` is `ℓ = max(d, 1)`, so `d = 0` and `d = 1` share level 1. The weight SHALL be element ℓ of the ladder (counting from 1) when `ℓ ≤ c`, and `0` when `ℓ > c` or when no unretired pair exists (`d = Integer.MAX_VALUE`). At the default this is 500 for `d ≤ 1`, 400 for `d = 2`, 300 for `d = 3` and 0 from `d = 4` on, the weights of `bc4bc1b8`. The loader has already dropped every widget and handler pair beyond c ("Distance-Pair Cut at Load"), so a lookup never offers the scorer a pair at `ℓ > c`. The rule is still total. Flags are not read. `score` returns this weight before the tie split. `MopScorer.distanceWeight(MopContext, int d)` is the one mapping from a distance to a weight.

**Tie split (`distance` only).** `MopWidgetPass.apply` SHALL first compute the weight and `d` of every scored action of the state. Ties are by weight value, not by exact `d` and not by level, as in `bc4bc1b8`. The tied actions are those whose undivided weight equals the state's best weight, the largest positive undivided weight among the state's scored actions. When there are `n > 1` of them, each SHALL receive `max(W / n, W_next)`, with `W` that best weight, `n` the number of tied actions, and integer division. `W_next` is the weight of the level after W's level. W's level is the first ℓ in `1..c` whose ladder element equals W. The level after it weighs element `ℓ + 1` when `ℓ < c`, and `0` when `ℓ = c` (`MopScorer.nextLevelWeight`). At the default, `W_next` is 400 for the `d ≤ 1` level, 300 for `d = 2` and 0 for `d = 3`, as in `bc4bc1b8`. On a strictly decreasing ladder, ties by value are ties by level. On a ladder with a plateau (equal neighbouring elements), actions at different levels can tie, and `W_next = W` there, so nothing is split: under `300,300,300` four actions tied at level 1 each receive 300. Under a single-level ladder `500`, `W_next` is 0, so four tied actions each receive 125. The floor is the author's decision of 2026-10-07: without it, two actions at `d ≤ 1` would get 250 each and fall below a single action at `d = 2` (400). Actions at a worse level keep their undivided weight. The split value is what is added to the priority and stored as `mopBoost`. The undivided weight SHALL be stored beside it as the action's `mopWeight`, which the MOP coin ranks by ("MOP Coin Decider", design D-B10) and which is never part of the scorer sum (`scoring-pipeline`), and reset with `mopBoost`. Under `flag` `mopWeight` equals `mopBoost`. The levels are found by weight value, so the floor assumes a non-increasing ladder, as the default and every ladder the calibration sends are. A ladder with equal or increasing elements is accepted, not rejected (author, 2026-10-09, review NT-02; confirmed for the list key 2026-11-02). Its split stays defined by the rule above. For example, under `300,500` the best weight 500 sits at level 2 = c, so its `W_next` is 0. When the best weight sits at a level whose successor weighs more, `W_next` exceeds it and each tied action receives more than its undivided weight, as `bc4bc1b8` gives with `D1 = 300`, `D2 = 500` (author, 2026-11-03, review WR-01).

**Every weighted action is boosted, tried or not** (design D-B19, reverted 2026-10-19). The pass SHALL NOT read `MopInteractionLedger`: whether an action's (activity, `xpath|ActionType`) key was already executed changes neither its boost nor the tie split.

Under rule R1 (`scoring-pipeline` "Scorer Independence (Rule R1)") the scorer reads neither the action's priority nor another scorer's field. `mopWeightDirect`/`mopWeightTransitive` are inert under `distance`, and `ape.mopWeights` under `flag`; `RUN_START.inert` reports them (`run-spec`). The menu-gateway, WTG and MOP-frontier passes are the same in both modes. WTG and the MOP frontier read `activityHasMop`; the menu gateway reads `activityHasMopOptionsMenu`, which load computes from `optionsMenus` and the WTG edges of `StaticGuiModel` into the selected MOP set. Neither changes meaning with the mode. The STATIC `NavFrontierPass` reads no MOP datum (`wtg-navigation`).

The scorer SHALL NOT apply any activity-level fallback. A resolved-but-unflagged widget and a `NONE` lookup — including on a MOP-bearing activity — both score `0`. `MopData.activityHasMop(activity)` remains a predicate consumed by WTG scoring, the MOP frontier and `stateMopDensity`, and is not a branch of `MopScorer.score`.

#### Scenario: a tried key keeps its boost
- **WHEN** under `distance` a state of activity `A` holds X and Y, both at `d ≤ 1` (undivided weight 500), and the ledger holds X's key for `A`
- **THEN** X and Y SHALL both be tied, each with `mopBoost` `max(500/2, 400)` = 400 added to its priority, and `mopWeight` 500
- **AND** the step's `dec.mopx` SHALL be `[2, n]` (`event-sink`)

#### Scenario: Direct MOP-reachable widget
- **WHEN** under `flag` the best containment lookup for a click action carries `{direct=true, transitive=true}`
- **THEN** the returned boost SHALL be `mopWeightDirect` (500)

#### Scenario: Transitive only
- **WHEN** under `flag` the best containment lookup carries `{direct=false, transitive=true}`
- **THEN** the returned boost SHALL be `mopWeightTransitive` (300)

#### Scenario: Resolved-but-unflagged widget scores zero
- **WHEN** under `flag` every containment lookup resolves through the id key to a widget with no flag for the event, or through a listed stamp whose record is `none`
- **AND** `data.activityHasMop(activity)` returns `true`
- **THEN** the returned boost SHALL be `0`

#### Scenario: Widget null scores zero
- **WHEN** every containment lookup is `NONE` AND `data.activityHasMop(activity)` returns `true`
- **THEN** the returned boost SHALL be `0` in both modes

#### Scenario: No match
- **WHEN** no containment lookup carries a flag or a pair AND the activity has no MOP association
- **THEN** the returned boost SHALL be `0` in both modes

#### Scenario: Distance weights by nearest unretired target
- **WHEN** under `distance` with default weights, an action's containment lookups carry, as nearest unretired pair, `d = 0`, `1`, `2`, `3` or `4`, and no other action of the state has a positive weight
- **THEN** its boost SHALL be 500, 500, 400, 300 or 0 respectively
- **AND** an action whose lookups carry no pair SHALL score 0

#### Scenario: Weight split among the actions tied at the state's best distance
- **WHEN** under `distance` with default weights, a state has two actions at `d = 1`, one action at `d = 3`, and the rest at no pair
- **THEN** each of the two `d = 1` actions SHALL receive a `mopBoost` of 400 (`max(500 / 2, 400)`: the floor at the `d = 2` weight)
- **AND** the two `d = 1` actions SHALL keep a `mopWeight` of 500, the value the MOP coin ranks by
- **AND** the `d = 3` action SHALL receive 300, undivided
- **AND WHEN** three actions share the best `d = 2`
- **THEN** each SHALL receive 300 (`max(400 / 3, 300)`)
- **AND WHEN** four actions share the best `d = 3`
- **THEN** each SHALL receive 75 (`max(300 / 4, 0)`)

#### Scenario: d = 0 and d = 1 are one tie level
- **WHEN** under `distance` with default weights, a state has two actions at `d = 0`, one action at `d = 1`, and no other action with a positive weight
- **THEN** all three SHALL be tied at level D1, since each has the undivided weight 500
- **AND** each SHALL receive a `mopBoost` of 400 (`max(500 / 3, 400)`), the `d = 0` actions included

#### Scenario: flag mode reproduces the E6 mark on an unstamped APK
- **WHEN** under `flag` the format-2 cryptoapp fixture is loaded in a cell with `MOP` and the tree carries no stamp
- **THEN** a click on `buttonGenerateHash`, `btn_cipher_encrypt` or `executeButton` SHALL score 300, as in E6
- **AND WHEN** the same run is under `distance`
- **THEN** the same three clicks, alone on their screens, SHALL score 400 (`d = 2`), 0 (`d = 4`) and 300 (`d = 3`)

#### Scenario: Distance zero
- **WHEN** under `distance` with `ape.mopWeights=500,400,300` an action's nearest unretired pair has `d = 0`
- **THEN** `distanceWeight` SHALL return 500, the weight of level 1

#### Scenario: Beyond the cut
- **WHEN** under `distance` with `ape.mopWeights=500,400,300` `distanceWeight` is asked for `d = 4`
- **THEN** it SHALL return 0

#### Scenario: Above the old cut
- **WHEN** under `distance` with `ape.mopWeights=500,400,300,300,300,300` an action's nearest unretired pair has `d = 5`
- **THEN** its weight before the tie split SHALL be 300
- **AND** at `d = 7` its weight SHALL be 0

#### Scenario: Flat ladder does not split
- **WHEN** under `distance` with `ape.mopWeights=300,300,300` four actions of a state tie at level 1 (each `mopWeight` 300) and no other action weighs more
- **THEN** each SHALL receive a `mopBoost` of 300 (`max(300 / 4, 300)`)

#### Scenario: Single-level ladder splits fully
- **WHEN** under `distance` with `ape.mopWeights=500` four actions of a state tie at level 1
- **THEN** each SHALL receive a `mopBoost` of 125 (`max(500 / 4, 0)`)
- **AND** an action whose nearest unretired pair is at `d = 2` SHALL weigh 0

#### Scenario: Increasing ladder raises the share
- **WHEN** under `distance` with `ape.mopWeights=300,500` two actions of a state tie at level 1 (each `mopWeight` 300) and no other action weighs more
- **THEN** each SHALL receive a `mopBoost` of 500 (`max(300 / 2, 500)`), above its undivided weight
- **AND** each SHALL keep a `mopWeight` of 300

#### Scenario: Default ladder equals the E7 jar
- **WHEN** `ape.mopWeights` is at its default and `distanceWeight` and `splitTies` are evaluated over every `d` in `0..10` and `Integer.MAX_VALUE` and over every tie size `n` in `1..20` at each level
- **THEN** every result SHALL equal that of frozen copies of the `bc4bc1b8` `distanceWeight`, `splitTies` and `nextLevelWeight` built from `500`, `400`, `300`

### Requirement: MopData — Load Status Line and Fail-Fast

`StaticArtifactLoader.load` SHALL emit exactly one load status record per invocation with a non-null path, on both outcomes. The record is the out-of-step `MOP_DATA` NDJSON record of the event sink (`EventSink.mopData`), which stays the census of the whole artifact load (both providers):

- success: `status` `loaded`, with the fields `formatVersion`, `sourceDigest` (omitted when the artifact has no `source.digest`), `package`, `windows`, `widgets`, `flagged`, `droppedNoId`, `wtgEdges`, `handlersUnmatched`, `syntheticLambda`, `recovered`, `mopActivities`, `mopActsAugmented`, `components`, `targets`, `handlers`, `droppedPairs`, `cutPairs`. `windows`, `widgets`, `flagged`, `droppedNoId`, `wtgEdges`, `handlersUnmatched`, `syntheticLambda` and `recovered` are the artifact's generator-computed `stats` echo (host facts, echoed not recomputed; `stats.orphanDialogs` is not echoed). `components` counts the loaded receivers, services, activities and providers. `targets` is the artifact's target count, `handlers` the number of classes in the handler table, `droppedPairs` the number of malformed distance pairs dropped at load ("MopData — Static Analysis JSON Loader"), and `cutPairs` the number of well-formed widget and handler pairs dropped by the cut at load ("Distance-Pair Cut at Load"). A pair is counted in at most one of the two: `droppedPairs` > 0 signals a malformed artifact, `cutPairs` > 0 an artifact deeper than the ladder
- failure: `status` `rejected` with `reason` one of `file-missing`, `parse-error`, `version-mismatch`, `package-mismatch`

`mopActsAugmented` SHALL be `|mopActivitiesAugmented \ mopActivities|` over the two wire sets — what the augmented source would contribute — and SHALL NOT vary with `MOP_ACTIVITY_SOURCE`. `mopActivities` remains the size of the **selected** set, so the two fields answer different questions on purpose.

`windows` is in the list deliberately: it survives inside the artifact as `stats.windows`. `transitions` is the single deliberate omission, superseded by `wtgEdges`. The record gains fields across changes and never loses one.

The record goes to the `.trace` stream, written by the sink directly to `System.out` and never through `Logger` (INV-SNK-11). It SHALL NOT be written to logcat: the rv-platform logcat parser owns that channel and foreign lines are forbidden.

Fail-fast composition:

1. **Plan level** (`run-spec`, `guidance-groups`): a plan that mounts `STATIC` or `MOP` without `ape.staticDataPath` is invalid and aborts at plan validation (`MISSING_DEPENDENCY`), before agent construction.
2. **Artifact level** (this requirement): when the plan mounts `STATIC` or `MOP` and `StaticArtifactLoader.load` returns no provider — for any reject reason, including a format-1 artifact — `StatefulAgent` SHALL abort the run (throw `StopTestingException`, `StatefulAgent.requireMopArm`) instead of continuing without the group (INV-MOP-22).
3. **Host level** (`aperv-tool` in rv-android): a cell whose full JSON is missing or whose derivation fails raises before launch, and the host cache keys on the format as well as `source.digest`.

When `ape.staticDataPath` is unset, or when it is set and neither `STATIC` nor `MOP` is mounted (the path is then inert, design D-B14.20), nothing is loaded and no status record is written.

#### Scenario: successful load emits counters
- **WHEN** `StaticArtifactLoader.load` parses a format-2 artifact derived from a full JSON whose SHA-256 is `d`, with 30 widgets, 3 flagged, 0 dropped, 16 WTG edges, `targets: 27` and 2 handler classes
- **THEN** one `MOP_DATA` record with `status` `loaded` SHALL be emitted carrying `formatVersion:2`, the artifact's `source.digest` (`sha256:d`) as `sourceDigest`, those counters, `targets:27`, `handlers:2`, `droppedPairs:0` and `cutPairs:0`

#### Scenario: transitions present, click edges absent
- **WHEN** the artifact's `wtg` map is empty because the producer's document carried transitions but none of them a `click` event
- **THEN** the `MOP_DATA` record SHALL carry `wtgEdges:0`
- **AND** it SHALL carry **no** `transitions` field
- **AND** the jar SHALL have no other number to confuse it with: raw transitions do not exist on the wire (INV-MOP-35)

#### Scenario: rejected load names the reason
- **WHEN** the file at `ape.staticDataPath` is not a format-2 compact artifact (a full JSON, a format-1 artifact, or a document without `formatVersion`)
- **THEN** one `MOP_DATA` record with `status` `rejected` and `reason` `version-mismatch` SHALL be emitted
- **AND** `load` SHALL return no provider

#### Scenario: fail-fast when the MOP arm cannot arm
- **WHEN** the `as_mop` cell sets `ape.staticDataPath` and `StaticArtifactLoader.load` returns no provider
- **THEN** `StatefulAgent` SHALL throw `StopTestingException` during setup
- **AND** the run SHALL NOT proceed without its STATIC and MOP groups

#### Scenario: unset path keeps SATA behavior
- **WHEN** the `base` cell runs, with no `ape.staticDataPath`
- **THEN** no provider SHALL exist and exploration SHALL proceed with no STATIC or MOP consumer, no `MopRetirement` and no `MopInteractionLedger`

### Requirement: MOP Coin Decider

`MopCoinStage` (feature `MOP_COIN`, a MOP member activated by `ape.mopCoinPercentage`, POSITIVE, neutral `0`, jar default `1.0`, clamped to `[0,1]` at resolution — no abort on a value) SHALL be the MOP group's decider. It sits in the decision pipeline after `MOP_LAUNCHER` and `COMPONENT_TRIGGER` and before `SATA_CHAIN` (`decision-pipeline`), so with the LLM mounted it decides only the steps the LLM leaves — the `1 − p_L` steps and the steps a gate, an error or a decline returns — and with the LLM off it is the first decider after the guards and the launcher (the component trigger is a side effect, not a decider; plan D-A10). A step makes at most one LLM call (design decision A3): once an LLM stage has called, the later LLM stages return `Continue` without calling, so the MOP coin is reached after at most one call.

**Eligibility.** The stage is eligible on a step when the new state's `getActions()` holds at least one action that is valid, enabled and target-requiring, and that meets the three conditions of design D-B10:

1. its undivided weight `ModelAction.getMopWeight()` is positive — under `distance`, the weight of an unretired target ("MopScorer — Priority Boost", "Target Retirement"); under `flag`, the direct/transitive mark;
2. `MopInteractionLedger.count(activity, xpath|ActionType)` is below `ape.mopCoinLedgerMaxInteractions` (default 1, so `tried` is false; "MopInteractionLedger — Executions per Activity and Key", design D-B17);
3. when `FORM_COMPLETION` is in the plan, it is not the submit candidate the form-completion context excludes (INV-FORM-06).

Visit count is not a condition: an action visited in the abstract state but never tried in the activity is eligible, and one tried in another state of the same activity is not, at the default k = 1 (with k > 1, until its key counts k executions).

**The coin.** The stage reads `u_M`, drawn at step open from the MOP coin's own stream (`StepRng.mopCoin()`, `decision-pipeline`, P-3). When the step is eligible and `u_M < p_M`, the stage SHALL select; otherwise it continues to the SATA chain. With `p_M = 1.0` the stage selects on every eligible step it reaches.

**Ranking.** Among the eligible actions, the stage SHALL take, each key consulted only to break a tie on the keys before it:

1. the largest `mopWeight` (undivided: the tie split of `distance` is not read, so three actions at `d ≤ 1` split to 400 each do not tie with one at `d = 2`);
2. under `ape.mopScoring=distance` only, the larger number of distinct unretired targets within `d + 1` over the pairs of the same lookup that produced the weight, `d` being the distance behind that weight (`MopCoinStage.targetCount`, `MopRetirement.countWithin(retirement, lookup.dist, d + 1)`). The window reads only the pairs that survived the cut at load ("Distance-Pair Cut at Load"), so at `d = c` it never counts a target at `c + 1`;
3. the first in `State.getActions()` order.

Priority SHALL NOT be a key, so no ADDONS or STATIC scorer can steer the coin (rule R1, `scoring-pipeline`).

**Record.** A step the coin takes SHALL carry `dec.src:"mop"`, the coin's draw as `dec.own.uM`, and the lookup fields of the chosen action (`dec.mopd`, `dec.mops`, `dec.hnd`). The stage commits its pick when the agent keeps it (`onSelectionKept`, `decision-pipeline` "Episode-State Ownership and Reset"): then, when the SATA buffer is non-empty, it abandons it without weakening and the record carries `dec.buf:"mop"` (`action-selection` "Buffer Abandonment Without Weakening"), and `RUN_END.counters.coinMop` counts the step. A pick that a post-selection restart replaces (a plan without `APE_FIXES`, such as the `mop` cell) counts no `coinMop` and leaves the buffer to the restart's new episode, which weakens the planned edge as in APE (`decision-pipeline` "Buffer Abandonment by a Preempting Owner"). On the stage path every pick is kept. `dec.own.uM` is written on every step while `MOP` is mounted (`event-sink`).

#### Scenario: eligible step taken at the default dose
- **WHEN** the `mop` cell runs under `distance` with `p_M = 1.0`, the state of activity `A` holds X (`mopWeight=500`, untried in `A`) and Y (`mopWeight=0`), and no earlier stage took the step
- **THEN** the stage SHALL select X, and the record SHALL carry `dec.src:"mop"`

#### Scenario: a pick replaced by a post-selection restart is not counted
- **WHEN** in the `mop` cell the stage selects X with a planned transition in the SATA buffer, and a periodic restart then replaces X after selection
- **THEN** `coinMop` SHALL NOT count the step, the record SHALL carry no `dec.buf`, and the restart's new episode SHALL weaken the planned edge

#### Scenario: largest weight wins, priority ignored
- **WHEN** two eligible actions carry `mopWeight` 400 (priority 900, raised by `coverageBoost`) and 500 (priority 532)
- **THEN** the stage SHALL select the `mopWeight=500` action

#### Scenario: target count breaks a weight tie under distance
- **WHEN** `ape.mopScoring=distance` and two eligible actions both carry `mopWeight=400` from a nearest unretired target at `d = 2`, X's lookup with unretired targets at distances `[2, 3, 3]` and Y's at `[2, 5]`, Y first in `getActions()`
- **THEN** the stage SHALL select X

#### Scenario: equal on every key, the first in action order wins
- **WHEN** `ape.mopScoring=flag` and two eligible actions both carry `mopWeight=500`, X before Y in `getActions()`
- **THEN** the stage SHALL select X

#### Scenario: an action tried in the activity is not eligible
- **WHEN** at the default `ape.mopCoinLedgerMaxInteractions=1` the only action with `mopWeight > 0` targets widget W, whose key `xpath|MODEL_CLICK` the ledger holds for activity `A` from an execution in another state of `A`
- **THEN** the step SHALL NOT be eligible and SHALL continue to the SATA chain, with no `dec.src:"mop"`

#### Scenario: an unvisited-but-tried action and a visited-but-untried action
- **WHEN** at the default `ape.mopCoinLedgerMaxInteractions=1` action X is unvisited in the current abstract state but its key is in the ledger for the activity, and action Y has `visitedCount=2` in this state but its key is not in the ledger (both `mopWeight=300`)
- **THEN** only Y SHALL be eligible

#### Scenario: excluded submit is skipped when form completion is mounted
- **WHEN** the `as_mop` cell runs, the only eligible-by-weight action is the form submit, and the form-completion context holds
- **THEN** the step SHALL NOT be eligible
- **AND WHEN** the `mop` cell (no `FORM_COMPLETION`) meets the same screen
- **THEN** the submit SHALL be eligible and taken

#### Scenario: the coin loses
- **WHEN** `ape.mopCoinPercentage=0.4`, the step is eligible and `u_M = 0.7`
- **THEN** the stage SHALL continue to the SATA chain, the record SHALL carry `dec.src:"sata"` and `dec.own.uM:0.7`

#### Scenario: a zero dose does not mount the stage and shifts no draw
- **WHEN** `ape.mopCoinPercentage=0` in a cell with `MOP`
- **THEN** `MOP_COIN` SHALL be absent from the plan's `features` and `MopCoinStage` SHALL NOT be mounted
- **AND** the sequence of SATA's choices SHALL equal that of the same cell with `p_M = 1.0` on every step where the coin would not have been eligible, because `u_M` is drawn from its own stream (`RngNeutralityTest`)

#### Scenario: the LLM coin comes first
- **WHEN** the `as_mop_llm` cell runs with `p_L = 0.9`, the step is eligible for the MOP coin, `u_L = 0.95` and `u_M = 0.1`
- **THEN** the LLM coin SHALL lose, the MOP coin SHALL take the step, and the record SHALL carry `dec.src:"mop"`
- **AND WHEN** `u_L = 0.3` and the LLM call returns a usable action
- **THEN** the LLM SHALL take the step and the MOP coin SHALL NOT be reached
- **AND WHEN** `u_L = 0.3`, the new-state stage has already called the model on the step and its answer was declined
- **THEN** the LLM coin SHALL return `Continue` without a second call, and the MOP coin SHALL take the step with `u_M = 0.1`

#### Scenario: Window boundary
- **WHEN** under `distance` with `ape.mopWeights=500,400,300,300,300` (c = 5) an eligible action's weight lookup has, as nearest unretired pair, `[0,1]` (`d = 1`), and further unretired pairs `[1,2]` (`d + 1`) and `[2,3]` (`d + 2`)
- **THEN** its second key SHALL be 2, counting targets 0 and 1 and not target 2
- **AND** against an action of equal `mopWeight` whose lookup is `[[5,1],[6,1],[7,3]]` (second key 2), the first in `getActions()` order SHALL win

#### Scenario: Retired target excluded
- **WHEN** under `distance` an eligible action is scored at `d = 1` through an unretired pair `[0,1]`, and the only other pair within `d + 1` of its weight lookup, `[1,2]`, names a retired target
- **THEN** its second key SHALL be 1
- **AND WHEN** every target of the lookup is retired, as `[[1,1]]` with target 1 retired
- **THEN** the second key (`MopCoinStage.targetCount`) SHALL be 0, because no unretired pair gives the action a distance, and the action, weighing 0, SHALL NOT be eligible

#### Scenario: Window never passes the cut
- **WHEN** under `distance` with `ape.mopWeights=500,400,300` (c = 3) an artifact gives a widget's `click` list `[[0,3],[1,3],[2,4],[3,4]]`, and an action on that widget is scored at `d = 3`
- **THEN** the loader SHALL keep `[[0,3],[1,3]]` and count 2 in `cutPairs`
- **AND** the action's second key SHALL be 2, as in `bc4bc1b8`, whose artifacts carried no widget or handler pair beyond 3

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

Only the targets at `d*` are counted: widget and handler lists carry every target at `d ≤ c`, the ladder length ("Distance-Pair Cut at Load"), and counting a whole list would retire up to several hundred targets through one interaction.

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
- **THEN** the action's distance SHALL be 2 and its weight before the tie split SHALL be level 2 of `ape.mopWeights` (400 at the default)
- **AND WHEN** target 7 is also retired
- **THEN** the action's weight SHALL be 0 and it SHALL NOT be eligible for the MOP coin

#### Scenario: no counting under flag
- **WHEN** `ape.mopScoring=flag` and the agent executes the first interaction of a widget whose lookup carries pairs
- **THEN** no `MopRetirement` SHALL exist, no counter SHALL change, and no step record SHALL carry `dec.ret`
- **AND** the ledger SHALL still record the key, because the coin reads it in both modes

#### Scenario: an off-tree LLM tap never counts
- **WHEN** under `distance` the executed action is a `MODEL_LLM_TAP` at a pixel that lies inside a widget whose lookup carries pairs
- **THEN** no counter SHALL change and no ledger key SHALL be recorded, because the tap has no resolved node to look up

### Requirement: Handler Stamp Lookup

`MopData` SHALL answer, for one node and one event, what MOP knows about the action, through `MopData.Lookup MopData.lookup(String activity, GUITreeNode node, String eventType)` (design D17, API Design). A `Lookup` carries the direct flag, the transitive flag, the distance pairs `int[][] dist` (each `[i, d]`, possibly empty) and the source, one of `STAMP`, `ID` or `NONE`. `lookup` SHALL never return null (a null node yields `NONE`) and SHALL be pure: it reads the artifact and the node, never the retirement state.

The result SHALL be computed in this order:

1. **Stamp.** If `eventType` normalizes to `click` or `longClick`, the node's stamp for that event (`GUITreeNode.getHandlerClick()` or `getHandlerLongClick()`, `ui-tree` "Handler Stamp Capture") is non-null, and the artifact's `handlers` map lists that class (`MopData.getHandler`), the result SHALL be that class's record for the event, with source `STAMP`. This holds when the record says the class reaches nothing (`mop` `none`, no `dist`): the result then has no flag and no pair, and the id key SHALL NOT be consulted. An event missing from a listed class's record reads as `none` with no pairs.
2. **Id key.** Otherwise, if the widget of `(activity, shortId)` resolves for the node's short id (`MopData.extractShortId`, the package-private `MopData.getWidget`), the result SHALL carry that widget's flags for the event, with the per-event fallback to the aggregate when the event has no key in the widget's `mop` map (`MopData.MopEntry.isDirectMop`/`isTransitiveMop`, INV-MOP-14), and the widget's `dist` pairs under the event's key (none when the key is absent), except for `click`: when the widget's `mop` map has no `click` key, a click SHALL read the widget's click aggregate (`MopData.Widget.clickAggregate()`), the merge by the minimum distance per target of the pairs of every event but `longclick`, `itemlongclick`, `implicitcreatecontextmenu` and `scroll` (design D17 as amended 2026-10-09), with source `ID`. The click aggregate takes the flags' condition (key absence; an explicit `click` key, `none` included, blocks it) but not their scope: `longClick`, `itemSelected`, `scroll` and a null event read their own pairs alone, so a long-click on a widget with no long-click listener gets no distance weight. The aggregate does not apply to step 1: a stamp names the handler of one event.
3. **Nothing.** Otherwise the result SHALL be `NONE`, with no flag and no pair.

A stamp naming a class that `handlers` does not list SHALL fall back to step 2. That covers the dispatchers gh121 records for library-routed handlers: `androidx.appcompat.app.AppCompatViewInflater$DeclaredOnClickListener` for XML `android:onClick`, and toolbar, menu and `SearchView` classes (gh121 design, Risks `:238` at HEAD, `:239` in the rv-android working tree: "the consumer must keep the resource-id key as fallback"). A listed class wins over the id key even when the id key is flagged, because the stamp names the handler that runs, while the id key carries GATOR's attribution, which spreads one handler over many widgets.

The events `itemSelected` and `scroll`, and a null event, SHALL skip step 1: the instrumenter stamps only click and long-click handlers. A Compose node's stamp is the class of the app's handler lambda (the `onClick`/`onLongClick` field of the modifier node behind the semantics action, or the action lambda's own class when no modifier node is reached; gh121, rv-android `instrumentation` INV-INS-178), and that class's `java.lang.Object invoke()`, or `java.lang.Object invoke(java.lang.Object)` for a toggleable's `onValueChange`, fills both `click` and `longclick` in its `handlers` record (`static-analysis-entrypoints`), so the stamp answers whichever event the node's key names.

On an APK instrumented without `--stamp-handlers`, every stamp is null, every lookup goes through step 2, and the result equals the E6 per-event flag of the id key.

#### Scenario: listed stamp wins over the id key
- **WHEN** a node in `br.unb.cic.cryptoapp.generated.CryptographyActivity` carries `handlerClick = "br.unb.cic.cryptoapp.generated.CryptographyActivity$$ExternalSyntheticLambda0"`, and `handlers` lists that class with `mop: {click: transitive}` and `dist: {click: [[16,3],[17,3],[18,3]]}`
- **THEN** `lookup(activity, node, "click")` SHALL return `transitive = true`, `direct = false`, `dist = [[16,3],[17,3],[18,3]]` and source `STAMP`
- **AND** this SHALL hold whatever the node's resource id is, including none

#### Scenario: listed stamp with none wins over a flagged id key
- **WHEN** a node's `handlerClick` names class `com.x.Foo$1`, `handlers["com.x.Foo$1"]` is `{mop: {click: none}}` with no `dist`, and the node's resource id resolves to a widget whose `mop` is `{click: direct}`
- **THEN** `lookup(activity, node, "click")` SHALL return no flag, no pair and source `STAMP`
- **AND** under `ape.mopScoring=flag` the action SHALL score `0`, not `mopWeightDirect`

#### Scenario: unlisted stamp falls back to the id key
- **WHEN** the `buttonGenerateHash` node of `MessageDigestActivity` (declared with XML `android:onClick="generateHash"`) carries `handlerClick = "androidx.appcompat.app.AppCompatViewInflater$DeclaredOnClickListener"`, which `handlers` does not list, and the widget `buttonGenerateHash` has `mop: {click: transitive}` and `dist: {click: [[22,2]]}`
- **THEN** `lookup(activity, node, "click")` SHALL return `transitive = true`, `dist = [[22,2]]` and source `ID`

#### Scenario: no stamp uses the id key
- **WHEN** a node carries no stamp (both fields null) and its resource id resolves to a widget with `mop: {click: transitive}`
- **THEN** `lookup(activity, node, "click")` SHALL return `transitive = true` with source `ID`
- **AND WHEN** the resource id resolves to no widget
- **THEN** the result SHALL be `NONE`, with no flag and no pair

#### Scenario: itemSelected and scroll ignore stamps
- **WHEN** a Spinner node carries `handlerClick = "com.x.Foo$1"`, `handlers` lists `com.x.Foo$1` with `mop: {click: direct}`, and the node's resource id resolves to a widget with `mop: {itemselected: transitive}`
- **THEN** `lookup(activity, node, "itemSelected")` SHALL return `transitive = true`, `direct = false` with source `ID`
- **AND** `lookup(activity, node, "scroll")` SHALL not consult `handlers` either

#### Scenario: a click on a widget flagged under another event reads its aggregate pairs
- **WHEN** an unstamped node's widget has `mop: {drag: transitive}` and `dist: {drag: [[9,2]]}` (a touch listener; GATOR's `drag`), and no `click` key
- **THEN** `lookup(activity, node, "click")` SHALL return `transitive = true`, `dist = [[9,2]]` and source `ID`
- **AND** under `ape.mopScoring=distance` a click on it SHALL weigh level 2 of `ape.mopWeights` (400 at the default) before the tie split, as under `flag` it weighs `ape.mopWeightTransitive`
- **AND** `lookup(activity, node, "longClick")` SHALL return `transitive = true` (the flags' aggregate fallback) and no pair, so a long-click on it weighs 0 under `distance`

#### Scenario: the click aggregate leaves out long-press events
- **WHEN** an unstamped node's widget has `mop: {longclick: transitive, select: transitive}` and `dist: {longclick: [[3,1]], select: [[9,2]]}`, and no `click` key
- **THEN** `lookup(activity, node, "click")` SHALL return `dist = [[9,2]]`, without target 3

#### Scenario: an explicit event key blocks the aggregate pairs
- **WHEN** an unstamped node's widget has `mop: {click: none, select: transitive}` and `dist: {select: [[9,2]]}`
- **THEN** `lookup(activity, node, "click")` SHALL return no flag and no pair, with source `ID`, as the flags read `click` alone

#### Scenario: a Compose invoke class answers click and longClick
- **WHEN** a Compose node with no resource id carries `handlerClick = "com.x.ui.ScreenKt$Body$1$1"` and `handlerLongClick = null`, and `handlers` lists `com.x.ui.ScreenKt$Body$1$1` with `mop: {click: transitive, longclick: transitive}` and `dist: {click: [[3,2]], longclick: [[3,2]]}` (its `invoke()` fills both events)
- **THEN** `lookup(activity, node, "click")` SHALL return `transitive = true`, `dist = [[3,2]]` and source `STAMP`
- **AND WHEN** another Compose node carries the same class under `handlerLongClick`
- **THEN** `lookup(activity, thatNode, "longClick")` SHALL return the same flags and pairs with source `STAMP`

### Requirement: MopScorer — MOP-Flagged State Density

`MopScorer.stateMopDensity(State, MopContext, int timestamp)` SHALL return `0` when the context is null or the state's activity does not satisfy `activityHasMop(activity)` (cheap early-out, no widget resolution). Otherwise it SHALL return `1 + count`, where `count` counts the valid, target-requiring actions resolved at `timestamp` whose resolved node's own lookup, `MopData.lookup(activity, node, eventTypeOf(action))` with no containment walk (INV-MOP-36, design D17), counts under the scoring mode (`MopScorer.countsTowardDensity`):

- under `flag`, the lookup reports a direct or transitive flag;
- under `distance`, the lookup alone gives a positive weight: its nearest unretired pair has a positive ladder weight (`MopScorer.best` over that one lookup; at the default, a pair at `d ≤ 3`).

In the scenarios below, "MOP-flagged" reads as "counts under the mode".

The `+1` term is the **activity-substrate floor**: a state on a MOP-bearing activity that carries no MOP-flagged widget (the A′-contributed case) SHALL rank above a state on a non-MOP activity (`1 > 0`) in the MOP navigation tie-breaks, while any widget-flagged state still ranks strictly above an activity-floor-only state (`1 + count > 1` for `count ≥ 1`). The floor is a cross-state ranking signal only; it SHALL NOT feed any per-candidate boost.

Its only callers SHALL be the MOP variants of the SATA extension points X3 and X4 ("Navigation Tie-Breaks as MOP Variants"), which exist only when `MOP` is in the plan. `SataAgent` SHALL contain no call guarded by `getMopData() != null` (INV-MOP-41). The callers pass the current GUITree timestamp via `getTimestamp()`.

#### Scenario: MOP-flagged widgets counted above the floor, unflagged ignored
- **WHEN** a state in a MOP activity has 10 valid targeted actions of which 2 resolve to MOP-flagged nodes
- **THEN** `stateMopDensity` SHALL return 3 (floor 1 + count 2)

#### Scenario: A′-only activity ranks above non-MOP activity
- **WHEN** state A is on an activity with `activityHasMop==true` contributed solely by the A′ sources (zero MOP-flagged nodes resolve), and state B is on an activity with `activityHasMop==false`
- **THEN** `stateMopDensity(A) == 1` and `stateMopDensity(B) == 0` (the X3/X4 MOP variants prefer A)

#### Scenario: widget evidence outranks activity-floor-only evidence
- **WHEN** state A has exactly 1 MOP-flagged resolved action and state B is on a MOP activity with none
- **THEN** `stateMopDensity(A) == 2` and `stateMopDensity(B) == 1` (widget-flagged state preferred)

#### Scenario: dense non-MOP screen scores below sparse MOP screen
- **WHEN** state A has 12 valid actions, none MOP-flagged, on a non-MOP activity, and state B has 3 valid actions, one MOP-flagged, in a MOP activity
- **THEN** `stateMopDensity(A) == 0` and `stateMopDensity(B) == 2` (the X4 MOP variant prefers the path ending in B)

#### Scenario: non-MOP activity unchanged
- **WHEN** the state's activity has no MOP-reachable methods
- **THEN** `stateMopDensity` SHALL return 0 without resolving any node

#### Scenario: under distance a flagged node with no near target does not count
- **WHEN** under `ape.mopScoring=distance` a state of a MOP activity has two valid targeted actions, one whose own lookup is transitive-flagged with pairs `[[5,4]]` and one whose own lookup carries the unretired pair `[[7,2]]`
- **THEN** `stateMopDensity` SHALL return 2 (floor 1 + the `d = 2` action), the flagged action at `d = 4` having weight 0

