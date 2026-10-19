# Delta: MOP Guidance (isolate-guidance-groups)

## Purpose

MOP guidance becomes one of five behaviour groups mounted by declaration (`guidance-groups`, design D-B1). Before this change its consumers were switched on by the presence of `ape.mopDataPath`, and three of them leaked into the "unguided" arm: the MOP tie-break inside SATA navigation, the WTG frontier, and typed input (plan §2.1). The MOP shortcut lived inside `SataAgent`, so its dose depended on SATA's internal order and on the addons that raise priority, and it took a weighted action only about 10 % above chance (plan §2.6).

After this change:

- the artifact path is `ape.staticDataPath` (`STATIC_DATA`), and the loader splits wire format 2, unchanged apart from the additive intent-filter `data` member (D-B13), into `StaticGuiModel` (WTG, widget metadata) and `MopData` (targets, distances, handlers, MOP activities). STATIC reads only the first; MOP reads both (P-1, D-B2);
- the MOP group is switched by `ape.mop`, and every MOP member depends on it;
- the MOP shortcut leaves SATA and becomes the **MOP coin**, a decider placed after the LLM coin: eligible when the screen holds an action with an unretired target weight not yet tried in this activity, it takes the action with the largest weight with probability `p_M` (`ape.mopCoinPercentage`, default 1.0), drawn from its own stream (D-A10, D-A15, D-B10);
- `MopInteractionLedger` counts the executions per (activity, `xpath|ActionType`); the coin (up to `ape.mopCoinLedgerMaxInteractions` per key, default 1), target retirement (first execution only) read it, and `ape.mopTargetPickCap` is retired (D-B17); the MOP widget boost does not read it (D-B19, reverted 2026-10-19);
- the navigation tie-breaks become the MOP variants of the SATA extension points X3 and X4 (`action-selection`, D-A7, D-B12), mounted only with `MOP`;
- typed input and the prompt's widget metadata belong to STATIC and read `StaticGuiModel`.

The launcher's global cadence is specified in `component-triggering` and `decision-pipeline`; the coin's place in the owner order in `decision-pipeline`; its telemetry fields in `event-sink`.

## Invariants

The following replace or extend main-spec invariants at archive:

- **INV-MOP-04** (amended; replaces the main spec's text): When `MOP` is absent from the resolved plan, no MOP scoring pass, no MOP coin, no ledger, no retirement and no MOP navigation variant SHALL be constructed, and no `MopData` SHALL be handed to any consumer: `getMopData()` SHALL return null if and only if `MOP` is not mounted, even when the artifact was loaded for STATIC (design D-B14.21), and the UICOV coverage dump SHALL omit `mopReach`. A cell without `MOP` SHALL behave identically whether or not `ape.staticDataPath` names an artifact, except for the STATIC consumers.
- **INV-MOP-22** (amended): A run whose plan mounts `STATIC` or `MOP` SHALL either obtain both providers from `StaticArtifactLoader.load` or abort; it SHALL never run with a mounted group and no data.
- **INV-MOP-06** (amended): `MopScorer.scoreWtg()` SHALL return 0 when `WTG` is absent from the plan, when `StaticGuiModel` holds no WTG data, when the widget has no matching WTG transition, or when the transition's target is not in the selected MOP-activity set. The weight SHALL be injected from the plan (`ape.mopWeightWtg`); no scorer SHALL read it from `Config`.
- **INV-MOP-23** (amended): Static-widget resolution for typed input and MOP resolution SHALL walk the same containment node set: the node, up to two ancestors, and its descendants two levels down. On each node, MOP resolution applies `MopData.lookup` (stamp, then id key), and typed-input resolution applies `StaticGuiModel.getWidget` (id key). The two paths SHALL NOT diverge in which nodes they visit or in the order they visit them.
- **INV-MOP-33** (amended): The navigation tie-break log SHALL be emitted only by the X4 MOP variant and only on its decisive branch; it SHALL NOT alter which path is selected.
- **INV-MOP-36** (amended): Every reader of MOP flags or MOP distances for a node and an event SHALL obtain them from `MopData.lookup(activity, node, eventType)`: the scoring pass, the MOP coin's ranking, the prompt marker and the `MOP NAVIGATION` count (`llm-prompt`), and the retirement trigger. A stamp SHALL be consulted only for the events `click` and `longClick`, and only when `handlers` lists the stamped class. A listed class SHALL decide the result even when it reaches nothing. Static widget metadata SHALL be read only through `StaticGuiModel.getWidget` (the prompt's metadata and typed input).
- **INV-MOP-37** (amended): Retirement SHALL exist only under `ape.mopScoring=distance`. A target's counter SHALL change only when a model action is executed whose (activity, `xpath|ActionType`) key was not yet in `MopInteractionLedger`, by one for each target of the action's weight's lookup whose pair lies at that lookup's smallest unretired distance `d*`. Counters SHALL never decrease. Under `flag`, no counter exists and no target is retired.
- **INV-MOP-38**: The MOP coin SHALL select only an action that is valid, enabled, target-requiring, has `mopWeight > 0`, whose (activity, `xpath|ActionType`) key has a `MopInteractionLedger` count below `ape.mopCoinLedgerMaxInteractions` (default 1: no entry; design D-B17), and is not the submit candidate excluded by form completion when `FORM_COMPLETION` is mounted. Its ranking SHALL NOT read `priority`.
- **INV-MOP-39**: The MOP coin's draw `u_M` SHALL come from its own stream, drawn once at every step open while `MOP` is mounted, before any stage runs; whether the coin is eligible, wins or is reached SHALL NOT change any draw of the agent or helper stream.
- **INV-MOP-40**: `MopInteractionLedger` SHALL be written only after a model action with a resolved target is executed, SHALL count every such execution of a key, and SHALL never forget a key; the coin and retirement SHALL read the same ledger and keep no first-interaction record of their own.
- **INV-MOP-41**: No MOP data SHALL be read by SATA outside the MOP variants of X3 and X4 and the scorer fields of the mounted MOP passes.

## ADDED Requirements

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
2. under `ape.mopScoring=distance` only, the larger number of distinct unretired targets within `d + 1` over the pairs of the same lookup that produced the weight, `d` being the distance behind that weight;
3. the first in `State.getActions()` order.

Priority SHALL NOT be a key, so no ADDONS or STATIC scorer can steer the coin (rule R1, `scoring-pipeline`).

**Record.** A step the coin takes SHALL carry `dec.src:"mop"`, the coin's draw as `dec.own.uM`, and the lookup fields of the chosen action (`dec.mopd`, `dec.mops`, `dec.hnd`). When the SATA buffer is non-empty, the stage abandons it without weakening and the record carries `dec.buf:"mop"` (`action-selection` "Buffer Abandonment Without Weakening"). `RUN_END.counters.coinMop` counts the steps the coin took. `dec.own.uM` is written on every step while `MOP` is mounted (`event-sink`).

#### Scenario: eligible step taken at the default dose
- **WHEN** the `mop` cell runs under `distance` with `p_M = 1.0`, the state of activity `A` holds X (`mopWeight=500`, untried in `A`) and Y (`mopWeight=0`), and no earlier stage took the step
- **THEN** the stage SHALL select X, and the record SHALL carry `dec.src:"mop"`

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

### Requirement: MopInteractionLedger — Executions per Activity and Key

`MopInteractionLedger` (`agent/MopInteractionLedger`) SHALL be a MOP-owned count of the executions of each (activity, key) pair, where key is `xpath|ActionType` (the convention of `UICoverageTracker.widgetId`, `UICoverageTracker.java:240-250`). Its interface is `int count(String activity, String key)`, `boolean tried(String activity, String key)` (`count > 0`) and `void record(String activity, String key)`, which adds one (design D-B17). It is constructed with the coin's budget k and answers `belowBudget(activity, key)` (`count < k`), so the coin needs no parameter of its own. It exists once per run when `MOP` is in the plan, in both scoring modes, and is absent otherwise. It reaches the stages through `StageCollaborators.mopLedger()` (`decision-pipeline`). No scoring pass reads it.

The agent SHALL record into the ledger, in `StatefulAgent.moveForward`, every executed model action that has a resolved target, whatever owner selected it (SATA, the LLM, the coin). A `MODEL_LLM_TAP` off the tree has no node and records nothing. Recording a key already present adds one to its count. The ledger is independent of `UICoverageTracker`, which belongs to the ADDONS coverage scorer: a cell without ADDONS still has a ledger when it has `MOP`.

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

### Requirement: Lookup Names the Stamped Handler

`MopData.Lookup` SHALL carry, beside `direct`, `transitive`, `dist` and `source` ("Handler Stamp Lookup"), the field `handler`: the binary class name of the stamp that answered the lookup when `source` is `STAMP`, and null for `ID` and `NONE` (design D-B17). It is set by `MopData.lookup` on its stamp branch and read only by telemetry (`dec.hnd`, `event-sink`); no decision reads it.

#### Scenario: a stamped lookup names its handler
- **WHEN** a click on a node stamped `rvsec.click=com.x.ui.ListAdapter$1` is looked up, and `handlers` lists `com.x.ui.ListAdapter$1`
- **THEN** the lookup SHALL have `source` `STAMP` and `handler` `com.x.ui.ListAdapter$1`
- **AND WHEN** the same widget is looked up through its id key
- **THEN** `handler` SHALL be null

### Requirement: Navigation Tie-Breaks as MOP Variants

The MOP group SHALL contribute the MOP variants of two SATA extension points (`action-selection` "SATA Navigation Extension Points (X1, X3, X4)"), mounted by the `SataAgent` composition root when `MOP` is in the plan (D-A7, D-B12):

- **X3, `MopAbaTargetTie`**: on a visit-count tie between the incumbent greedy target and a candidate in the ABA scan, `replaces(incumbent, candidate)` SHALL return true when `MopScorer.stateMopDensity(candidate) > MopScorer.stateMopDensity(incumbent)`, and false otherwise. It draws nothing.
- **X4, `MopTrivialPathPick`**: among the shortest paths to a trivial activity, `pick(paths)` SHALL return the first path whose end state holds the strictly largest `stateMopDensity`; when every end-state density is equal it SHALL return `RandomHelper.randomPick(paths)`. With one path, the densities are trivially equal and `randomPick` is called, as in the APE default.

Both variants read `MopData` through `MopScorer.stateMopDensity` ("MopScorer — MOP-Flagged State Density") and nothing else. In a cell without `MOP` the APE defaults are mounted and no density is computed (INV-MOP-41).

#### Scenario: X3 variant replaces on a denser candidate
- **WHEN** the `mop` cell's ABA scan meets two greedy targets of activity `A` with visit count 2 each, incumbent density 1 and candidate density 3
- **THEN** the candidate SHALL become the target

#### Scenario: X3 variant keeps the incumbent on equal density
- **WHEN** the incumbent and the candidate have visit count 2 and density 2 each
- **THEN** the incumbent SHALL stay, and no draw SHALL be consumed

#### Scenario: X4 variant falls back to the APE draw
- **WHEN** the `mop` cell finds two shortest paths to a trivial activity, both end-state densities 0
- **THEN** `RandomHelper.randomPick` SHALL choose, consuming one helper-stream draw

#### Scenario: no density without MOP
- **WHEN** the `as` cell, whose plan loads the artifact for STATIC but has no `MOP`, reaches the ABA scan and the trivial-path choice
- **THEN** `stateMopDensity` SHALL NOT be called and the APE defaults SHALL decide

## MODIFIED Requirements

### Requirement: MopData — Static Analysis JSON Loader

`StaticArtifactLoader.load(String path, String expectedPackage, String expectedMainActivity)` SHALL parse the **derived compact artifact** (`formatVersion: 2`) produced host-side by `aperv-tool` — wire format 2, unchanged apart from the additive intent-filter `data` member (D-B13; design P-1) — exactly once, and SHALL yield two read-only providers (design § Architecture):

- **`StaticGuiModel`** — the static GUI model: `package`, `mainActivity`, the WTG view, the widget metadata and the manifest components with their intent filters (design D-B13). The STATIC group reads only this provider (`NavFrontierPass`, typed input, the prompt's widget metadata, component triggering).
- **`MopData`** — the spec targets: the target count, the MOP flags and distance pairs of widgets and handlers, `activityDist`, the MOP-activity sets, the per-component `reachesMop` facts and the OPTIONSMENU gateways. The MOP group reads both providers: the MOP members that need the WTG or the components (`WtgPass`, `MopFrontierPass`, `MopScorer`, `MopLauncherStage`) read them from `StaticGuiModel` (plan P-1). `MopLauncherStage` reads the manifest activities, the main activity and the package from `StaticGuiModel`, and the MOP-activity census and `activityDist` from `MopData`.

The agent's `getMopData()` SHALL return null if and only if `MOP` is not mounted, even when the loader ran because `STATIC` is mounted (design D-B14.21): a `MopData` parsed for a STATIC-only cell is not handed to any consumer. No MOP consumer SHALL then hold a `MopData` reference, and the UICOV coverage dump (`SataAgent.dumpCoverage`) SHALL write `mopReach` only when `MOP` is mounted and omit it otherwise, consistent with `ACT.mop` (`event-sink` INV-SNK-18).

The loader runs if and only if the resolved plan mounts `STATIC` or `MOP` (design D-B14.20); both groups require `STATIC_DATA` (`ape.staticDataPath` set, `guidance-groups`). It hands each provider only to the mounted members that consume it. A path given while neither group is mounted is inert: it is listed in `RUN_START.inert` (`run-spec`), and no load, no `MOP_DATA` census and no rejection follow. Neither provider is constructed in a cell without the artifact (`base`). The items below say which provider serves each part of the wire:

1. **Version gate**: the top-level `formatVersion` field is mandatory and SHALL equal `2` (`MopData.SUPPORTED_FORMAT_VERSION`, `MopData.java:149`, gate `:207-213`). A missing value, `1`, or any other value ⇒ `status=rejected reason=version-mismatch`, null return (INV-MOP-34). There is no reader for format 1 (design D15, SDD-P3): a format-1 `.mop.json`, derived before the bump or served by a stale host cache, makes a MOP arm abort (INV-MOP-22). The version gate replaces the `"complete": true` sentinel of the full-JSON era (INV-MOP-09).
2. **Top-level scalars**: `StaticGuiModel.getPackageName()`, `StaticGuiModel.getMainActivity()` from the artifact's `package` / `mainActivity`, and `MopData`'s target count from `targets` (a non-negative integer; the number of entries of the producer's `distanceTargets`, direct targets followed by boundary targets). Every distance pair indexes this count. No target signature and no target kind is on the wire, and the jar does not distinguish boundary targets from direct ones (design D15, "B weighs as C"). A format-2 artifact that lacks `targets` or `handlers`, or whose `targets` is not an integer, SHALL be rejected with `status=rejected reason=parse-error` and a null return, so a MOP arm aborts (INV-MOP-22): the two members are always emitted (`static-analysis-entrypoints`), and their absence is a generator↔jar skew, not an empty table. A `targets` of `0` is valid and loads in both modes: a document without `distanceTargets` (a pre-gh120 `.apk.json`, which no guard stops, design D15) or an empty one derives to it. The run SHALL continue without distance data (under `distance` every weight is 0 and the launcher order has no key), and `MOP_DATA.targets` reports `0`. The jar has no special treatment for it (author, 2026-10-08).
3. **Widgets**: the artifact's `widgets` map (`baseActivity → shortId → widget`) is split by provider. `StaticGuiModel.getWidget(activity, shortId)` serves the consumed metadata fields — `inputType`, `hint`, `prompt`, `spinnerMode`, `contentDescription`, `tooltipText`, `entries` (each null/empty when absent on the wire). `MopData` keeps, under the same key, the MOP flags decoded from the wire `mop` map (normalized eventType → `none|direct|transitive|both`): `directMopByEventType`/`transitiveMopByEventType` are populated with an entry for **every** wire key (including `none` entries — key presence drives the per-event vs aggregate fallback), and the aggregate `directMop`/`transitiveMop` are the OR across the map values. `Widget.isDirectMop`/`isTransitiveMop` and their normalization-and-fallback semantics (INV-MOP-08, INV-MOP-14) are unchanged. A widget additionally carries its distance pairs from the wire `dist` map (normalized eventType → `[[i, d], …]`), stored per normalized event in the order the wire gives them (ascending `d`, then `i`; the generator bounds a widget's or handler's list to the targets at `d ≤ 3` and an `activityDist` entry to three pairs, and the loader reads any length). An absent `dist` means no pair for any event. The widget also keeps its click aggregate, the merge by the minimum distance per target of the pairs of every event but `longclick`, `itemlongclick`, `implicitcreatecontextmenu` and `scroll` (`MopData.mergeByMinimum`), which `lookup` reads for a click when the widget's `mop` map has no `click` key ("Handler Stamp Lookup"). Fields with no production consumer (`id`, `type`, `text`, raw `listeners`) SHALL NOT exist on the wire or in the model.
4. **Activity distances**: the wire `activityDist` map (`baseActivity → [[i, d], …]`) SHALL be loaded as a per-activity pair list in wire order, served to the launcher (`component-triggering`, design D20). An activity absent from the map has no pair.
5. **Handler table**: the wire `handlers` map (binary class name → `{mop: {event: flag}, dist: {event: [[i, d], …]}}`) SHALL be loaded as a per-class record, with the event keys normalized and the flags decoded as for widgets. A listed class with `mop` `none` and no `dist` SHALL be kept: it states that the handler reaches no target, and "Handler Stamp Lookup" relies on it. The table is read only by `MopData.lookup`.
6. **MOP-activity sets** (`MopData`): `mopActivities` and `mopActivitiesAugmented` are read as-is from the wire. `getMopActivities()` / `activityHasMop(activity)` SHALL serve the augmented set when `MOP_ACTIVITY_SOURCE` is in the resolved plan and the widget-derived set otherwise (INV-MOP-27's selection semantics, now between two precomputed sets). Their meaning is format 1's, except that the generator's A′ source 3 no longer counts an activity class's own `<init>`/`<clinit>` (`static-analysis-entrypoints`); the distance data does not change them.
7. **OPTIONSMENU gateways** (`MopData`): `activityHasMopOptionsMenu(activity)` SHALL be computed at load time from the artifact's `optionsMenus[]` records (`{activity, hasFlaggedWidget}`) and `StaticGuiModel`'s WTG view: an activity qualifies iff its record has `hasFlaggedWidget == true` OR any WTG click edge from that activity targets an activity in the *selected* MOP-activity set (same two-condition rule as before, INV-MOP-13).
8. **WTG** (`StaticGuiModel`): `hasWtgData()` / `getWtgTransitions(activity)` serve the wire `wtg` map (`sourceBaseActivity → [{widget, target}]`, already base-activity-keyed and deduplicated host-side per INV-WTG-04). `WtgTransition` carries `widgetName` and `targetActivity`; the `widgetClass` field is deleted (zero production readers).
9. **Components** (`StaticGuiModel`, design D-B13): each entry carries `className`, `componentType` (from the parent array key), `isMain`, `permission` (null when no gate); activities additionally carry `deepLinkUri` (null when absent on the wire); receivers/services carry `intentFilters` (`actions`, `categories` and, when the wire has it, the reduced `data` member `{schemes, hosts, paths, pathPrefixes, mimeTypes}`, empty lists omitted, `ports` and `pathPatterns` not projected (design D-B14.11); an absent `data` reads as "no data") and `hasTargetMethods` (boolean — the only consumed use of the former signature list is an emptiness test); providers additionally carry `authorities`. The wire `reachesMop` (the D7 rename of `reachesTarget`) of each component is kept by `MopData` alone, keyed by class name, for the MOP consumers that need it; `StaticGuiModel` exposes no `reachesMop`. The `data` member is additive inside `formatVersion: 2`. The full D15 `data` block of the gh60 producer (beyond the four reduced lists), `readPermission`/`writePermission`, the `targetMethods` signature list, and `exported` SHALL NOT exist on the wire (they remain in the host-side full JSON). `exported` leaves with them because no jar code path reads it and none may: the launcher's eligibility rule forbids consulting it, so its absence makes that prohibition structural rather than merely stated.

   `MopLauncherStage.buildDeepLinkUri` is deleted with the filter structure it walked: the activity launcher SHALL read `ComponentInfo.deepLinkUri` directly and pass it to `ActivityTriggerAction` unchanged, so `MonkeySourceApe`'s dispatch — `Intent.ACTION_VIEW` + `Uri.parse(uri)` when non-null, explicit component otherwise — keeps its exact behavior with the assembly rule living in the generator (INV-DRV-07).
10. **Sanity check** (`StaticArtifactLoader`, before either provider is handed out): `package`/`mainActivity` comparison per the "MopData — Package / MainActivity Sanity Check" requirement (unchanged semantics).
11. **Stats echo**: the artifact's `stats` block (generator-computed diagnostics: `widgetsTotal`, `flagged`, `droppedFlaggedNoId`, `orphanDialogs`, handler-join counters, `wtgEdges`) is carried through to the load status record without recomputation; it SHALL NOT influence any query result or the load outcome (the discipline of the former INV-MOP-31/32).

**Malformed distance pairs.** A pair, in `dist`, `activityDist` or `handlers`, that is not a two-integer array, whose index lies outside `[0, targets)`, or whose distance is negative, SHALL be dropped at load and counted (`droppedPairs` on the load record, "MopData — Load Status Line and Fail-Fast"). The load SHALL continue on the remaining pairs and SHALL NOT be rejected for it (design, Error Handling). A conforming generator emits no such pair, so a non-zero count is a generator↔jar skew signal.

Unknown JSON keys within `formatVersion: 2` are ignored (INV-MOP-11). This tolerance is not a compatibility affordance: it is the `org.json` DOM's default behavior, the *absence* of a check, so rejecting unknown keys would mean writing new code. Generator and jar ship together, so an unknown key is a skew signal, not a version to accommodate. The file is read once with a single pre-sized allocation and parsed once into an `org.json` DOM. No cross-referencing, flag or distance derivation, dialog re-keying, activity augmentation, or transition processing happens on-device (INV-MOP-35); those are generator requirements (`static-analysis-entrypoints` "Derived compact MOP artifact — projection contents").

**On the scenario headers below.** `openspec archive` matches scenarios by name and cannot tell a rename from a deletion, so every header of the main spec is kept, with its body restated for format 2. Several headers were written about the full-JSON parser and read oddly against a body about the compact reader; they are kept to be matched, not as evidence that anything parses a call graph on the device. The two package-sanity headers belong to the "MopData — Package / MainActivity Sanity Check" requirement, and each body says so.

#### Scenario: Real cryptoapp fixture loads every typed field
- **WHEN** `StaticArtifactLoader.load()` is called on the `cryptoapp.apk.mop.json` fixture, format 2, derived by the rv-android generator (`gh122`) from the gh120 fixture `modules/rv-static-analysis/tests/resources/cryptoapp.apk.json` (task 10.1)
- **THEN** the returned `StaticGuiModel` and `MopData` SHALL both be non-null
- **AND** `StaticGuiModel.getPackageName()=="br.unb.cic.cryptoapp"`, `StaticGuiModel.getMainActivity()=="br.unb.cic.cryptoapp.MainActivity"`, and `MopData`'s target count SHALL be 27 (23 direct targets, then 4 boundary targets, the four activity constructors)
- **AND** the MOP-flagged widgets SHALL be exactly three: `buttonGenerateHash` (MessageDigestActivity), `btn_cipher_encrypt` (CipherActivity), and `executeButton` (CryptographyActivity) — all `transitiveMop==true`, `directMop==false`
- **AND** their `click` pairs SHALL be `[[22,2]]`, none and `[[16,3],[17,3],[18,3]]` respectively: `btn_cipher_encrypt`'s nearest targets, 0 and 1, lie at `d = 4`, beyond the generator's `d ≤ 3` cut, so the widget carries no `dist`, and `executeButton` carries every target of its wrapper at `d ≤ 3`. `executeButton` is flagged through the exact join: under gh120 its wrapper `CryptographyActivity$$ExternalSyntheticLambda0.onClick` is listed in `reachability[]` reaching, because the producer links it to its own `lambda$setupExecuteButton$0$CryptographyActivity(android.view.View)` body, so the class recovery is not consulted (rv-android INV-DRV-09)
- **AND** the handler table SHALL list exactly `br.unb.cic.cryptoapp.cipher.CipherActivity$1` and `br.unb.cic.cryptoapp.generated.CryptographyActivity$$ExternalSyntheticLambda0`, the two classes of the fixture that declare `onClick(android.view.View)`, each with `click` `transitive` and the same pairs as its widget (none for `CipherActivity$1`)
- **AND** `activityHasMop` SHALL be true for `MessageDigestActivity`, `CipherActivity` and `CryptographyActivity`, and false for `MainActivity` (default flag state). With `mopActivitySourceComponents=true` it SHALL still be false for `MainActivity`: under gh120 its only reaching method is its constructor, which reaches boundary target 23, and the A′ source 3 leaves out a class's own `<init>`/`<clinit>` (`static-analysis-entrypoints`), so on this fixture `mopActivitiesAugmented` equals `mopActivities`
- **AND** `activityHasMopOptionsMenu("br.unb.cic.cryptoapp.MainActivity")==true` (gateway via WTG edges to the MOP sub-activities, INV-MOP-13)
- **AND** `StaticGuiModel.getWidget` for the `spinnerMessageDigest` widget SHALL carry its 13 `entries` and its metadata fields
- **AND** `StaticGuiModel`'s `getActivities().size()==4`; `getProviders().size()==1` with `authorities=="br.unb.cic.cryptoapp.androidx-startup"`; `getReceivers().isEmpty()`; `getServices().isEmpty()`; `MopData` SHALL record `reachesMop==false` for every component

#### Scenario: the as cell loads the artifact and exposes no MopData
- **WHEN** the `as` cell runs with `ape.staticDataPath=/data/local/tmp/mop-artifact.json` on `cryptoapp.apk.mop.json`
- **THEN** the loader SHALL parse the artifact once, `StaticGuiModel` SHALL be non-null and `getMopData()` SHALL return null
- **AND** no constructed component SHALL hold a `MopData` reference, and the UICOV coverage dump SHALL carry no `mopReach` member
- **AND WHEN** the `as_mop` cell runs on the same artifact
- **THEN** `getMopData()` SHALL be non-null and the UICOV dump SHALL carry `mopReach`

#### Scenario: Legacy full static-analysis JSON is rejected
- **WHEN** `StaticArtifactLoader.load()` is pointed at a full static-analysis JSON (top-level `windows`/`reachability`, no `formatVersion`)
- **THEN** `load` SHALL return no provider
- **AND** emit exactly one `status=rejected reason=version-mismatch` record
- **AND** with `ape.staticDataPath` set and `STATIC` or `MOP` mounted, the run SHALL abort via `StopTestingException` (INV-MOP-22) — never proceed as pure SATA
- **AND WHEN** `StaticArtifactLoader.load()` is pointed at a format-1 compact artifact (`"formatVersion": 1`)
- **THEN** the outcome SHALL be the same `version-mismatch` rejection and, with `ape.staticDataPath` set and `STATIC` or `MOP` mounted, the same abort (INV-MOP-34)

#### Scenario: Per-event-type reachability maps built
- **WHEN** a wire widget has `"mop": {"click": "both", "scroll": "none"}`
- **THEN** `isDirectMop("click")==true` and `isTransitiveMop("click")==true`; `isDirectMop("scroll")==false` and `isTransitiveMop("scroll")==false` (explicit `none` entry, no aggregate fallback)
- **AND** `isDirectMop("longClick")==true` (key absent ⇒ aggregate fallback; aggregates are the OR of the respective bits over the map)
- **AND** the wire values decode to the two bits positionally (`none`=00, `direct`=10, `transitive`=01, `both`=11)
- **AND** the loader SHALL decode `direct` faithfully rather than rejecting it, even though a conforming generator never emits it: `direct` implies `transitive` at derivation time (INV-DRV-01)
- **AND WHEN** the same widget carries `"dist": {"click": [[2,0],[5,1]]}`
- **THEN** its `click` pairs SHALL be `[[2,0],[5,1]]`, in that order, and its `scroll` pairs SHALL be empty (explicit `none` entry, no aggregate fallback)
- **AND** its `longClick` pairs SHALL be empty: only a click falls back to an aggregate of pairs, and only when the widget has no `click` key

#### Scenario: Flag-selected MOP-activity set
- **WHEN** the artifact carries `mopActivities=["A"]` and `mopActivitiesAugmented=["A","B"]`
- **THEN** with `MOP_ACTIVITY_SOURCE` absent from the plan, `activityHasMop("B")==false` and `getMopActivities()` SHALL equal `{"A"}`
- **AND** with `MOP_ACTIVITY_SOURCE` in the plan, `activityHasMop("B")==true` and `getMopActivities()` SHALL equal `{"A","B"}`
- **AND** the OPTIONSMENU-gateway recompute SHALL use the selected set for its condition-2 test
- **AND** neither set SHALL depend on `activityDist`

#### Scenario: Deep-link dispatch reads the wire field
- **WHEN** the artifact's `components.activities[]` entry for `X` carries `"deepLinkUri": "myapp://detail/x"` and the MOP stagnation launcher selects `X`
- **THEN** the `ActivityTriggerAction` SHALL carry that string verbatim, and the injected intent SHALL be `ACTION_VIEW` with `Uri.parse("myapp://detail/x")` targeted at the package via `setPackage`, the platform resolving the handling activity (INV-CT-07 dispatch unchanged)
- **AND WHEN** the entry omits `deepLinkUri`
- **THEN** the intent SHALL be the explicit-component intent
- **AND** the activity's `deepLinkUri` SHALL be served by `StaticGuiModel`; the reduced intent-filter `data` member is read only for receivers and services (`component-triggering`)

#### Scenario: Absent metadata stays null and costs zero tokens
- **WHEN** a wire widget omits `hint`, `prompt`, `entries`
- **THEN** the corresponding `Widget` fields SHALL be null / empty
- **AND** the STATIC prompt contribution's metadata rendering and `ApeAgent.generateInputText` SHALL behave exactly as they do for null/empty fields (INV-MOP-10 unchanged)

#### Scenario: Unknown keys in a v1 artifact are ignored
- **WHEN** a `formatVersion: 2` artifact carries an additional unknown top-level, per-widget or per-handler key
- **THEN** `load` SHALL succeed and ignore it (INV-MOP-11)
- **AND** a `formatVersion: 1` artifact SHALL NOT reach this tolerance: it is rejected by the version gate before any key is read (INV-MOP-34). The header names v1 only because the archive matches on it

#### Scenario: gh60 D15 component trigger-surface fields parsed
- **WHEN** `StaticArtifactLoader.load()` parses an artifact's `components` block
- **THEN** `StaticGuiModel`'s trigger surface SHALL be exactly `permission` (null when no gate, `hasPermissionGate()` false), `intentFilters` carrying `actions`, `categories` and the optional reduced `data` (`schemes`, `hosts`, `paths`, `pathPrefixes`, `mimeTypes`), `hasTargetMethods` on receivers and services, and `authorities` on providers
- **AND WHEN** a receiver's filter carries `"data": {"schemes": ["content"], "mimeTypes": ["text/plain"]}`
- **THEN** its `IntentFilter` SHALL carry those two lists and empty `hosts`/`paths`/`pathPrefixes`, and a filter without `data` SHALL carry five empty lists
- **AND** the full gh60 `DataSpec`, `ProviderInfo.readPermission`/`writePermission`, the `targetMethods` signature list and `exported` SHALL NOT exist on the wire **or on the model**
- **AND** each component's `reachesMop` SHALL be held by `MopData` only; trigger selection is owned by `component-triggering` (a STATIC member, design D-B13), which reads no MOP data
- **AND** the absence of `exported` SHALL keep the launcher's prohibition on consulting it structural (`component-triggering` INV-CT-06)

#### Scenario: Bug-fix regression — widget transitiveMop derived from gh60 Target keys
- **WHEN** `StaticArtifactLoader.load()` is called on the format-2 cryptoapp fixture
- **THEN** the widget `buttonGenerateHash` in `MessageDigestActivity` SHALL have `transitiveMop==true`, `directMop==false` and `isTransitiveMop("click")==true`
- **AND** `activityHasMop("br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity")==true`
- **AND** under `ape.mopScoring=flag`, a click action on an unstamped `buttonGenerateHash` node SHALL score `mopWeightTransitive` (300), through `MopData.lookup` with source `ID`
- **AND** the gateway SHALL hold: `activityHasMopOptionsMenu("br.unb.cic.cryptoapp.MainActivity")==true`
- **NOTE**: this scenario is the contract that "SATA-MOP is not silently bare APE" on this side. The derivation it used to assert is the generator's (INV-DRV-01) and is tested in rv-android. What the jar can still fail at, and asserts here, is decoding a flag that arrived correct into a boost that fires

#### Scenario: Widget metadata extracted on post-task-11 fixture
- **WHEN** `StaticArtifactLoader.load()` is called on the format-2 cryptoapp fixture
- **THEN** `StaticGuiModel.getWidget("br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity", "editTextMessageDigest")` SHALL carry `inputType=="textPersonName"` and `hint=="Input text ..."`
- **AND** the Spinner widget `spinnerMessageDigest` SHALL carry `entries.size()==13`, the JCA algorithm list
- **AND** `type` and `text` SHALL NOT be asserted, because they do not exist on the wire (INV-MOP-35)

#### Scenario: Top-level package and mainActivity sanity check (default warn-only)
- **WHEN** `StaticArtifactLoader.load(path, "x.y.z.OTHER", null)` is called and the artifact's `package=="x.y.z"`
- **THEN** both providers SHALL be returned and a `WARN` log line SHALL be emitted naming both the expected and the parsed value
- **AND** this claim is owned by the "MopData — Package / MainActivity Sanity Check" requirement (scenario `Default warn-only on mismatch`), whose semantics this change leaves unchanged

#### Scenario: Package mismatch rejected in strict mode
- **WHEN** `ape.mopStrictPackageMatch=true` AND `StaticArtifactLoader.load(path, "x.y.z.OTHER", null)` is called on an artifact with `package=="x.y.z"`
- **THEN** `load` SHALL return no provider, a `WARN` log line SHALL be emitted, and the load record SHALL carry `reason=package-mismatch`
- **AND** the owning requirement is "MopData — Package / MainActivity Sanity Check" (scenario `Strict-mode rejection on mismatch`)

#### Scenario: OPTIONSMENU window with MOP widget triggers activityHasMopOptionsMenu
- **WHEN** the artifact's `optionsMenus[]` carries `{activity: "com.x.A", hasFlaggedWidget: true}`
- **THEN** `activityHasMopOptionsMenu("com.x.A")` SHALL return `true` (condition 1, INV-MOP-13)
- **AND** the qualification SHALL be computed at load from that record, not read from a precomputed gateway set on the wire, because condition 2 depends on the flag-selected activity set

#### Scenario: OPTIONSMENU window without MOP widget does not trigger
- **WHEN** the record for `com.x.B` carries `hasFlaggedWidget: false` AND no WTG click edge from `com.x.B` targets an activity in the selected MOP-activity set
- **THEN** `activityHasMopOptionsMenu("com.x.B")` SHALL return `false`

#### Scenario: OPTIONSMENU gateway — menu item navigates to a MOP activity
- **WHEN** the record for `com.x.C` carries `hasFlaggedWidget: false` but the wire `wtg["com.x.C"]` contains an edge whose `target` is `com.x.CryptoActivity`, and `com.x.CryptoActivity` is in the selected MOP-activity set
- **THEN** `activityHasMopOptionsMenu("com.x.C")` SHALL return `true` (condition 2, the gateway case)
- **AND** the same artifact SHALL yield `false` for `com.x.C` under `mopActivitySourceComponents=false` when `com.x.CryptoActivity` is present only in `mopActivitiesAugmented`

#### Scenario: Multiple listeners to the same handler do not double-count
- **WHEN** a widget's wire `mop` map carries `{"click": "direct"}`
- **THEN** a click action on that widget's unstamped node under `ape.mopScoring=flag` SHALL score `mopWeightDirect` exactly, never a multiple of it
- **AND** the double-count SHALL be unrepresentable: `listeners` do not exist on the wire, the `mop` map holds one value per normalized event type, and the `dist` list holds each target index at most once, so there is no multiplicity for the jar to fold

#### Scenario: Complete-but-empty JSON parses cleanly (gh51-D5 timeout bucket)
- **WHEN** `StaticArtifactLoader.load()` is called on a `formatVersion: 2` artifact with `targets: 0` whose `widgets`, `wtg`, `optionsMenus`, `activityDist`, `handlers`, activity sets and component lists are all empty
- **THEN** the returned `StaticGuiModel` and `MopData` SHALL both be non-null
- **AND** every accessor SHALL return empty (`MopData.getMopActivities()`; `StaticGuiModel`'s `getReceivers()`, `getServices()`, `getActivities()`, `getProviders()`, `getWtgTransitions(any)`) and `StaticGuiModel.hasWtgData()` SHALL be `false`
- **AND** `MopData.lookup(any, any node, any event)` SHALL return `NONE` and every action SHALL score `0` in both scoring modes, without `NullPointerException`
- **AND** there SHALL be no `isComplete()` and no `complete` sentinel: what reaches the device is governed by the generator's preconditions (`static-analysis-entrypoints`)

#### Scenario: Duplicate short id — strongest MOP flag retained
- **WHEN** two widgets of one base activity resolve to the short id `"submit"`, one `direct`-flagged on `click` with pairs `[[3,0]]` and one `transitive`-flagged on `click` with pairs `[[3,1],[5,2]]`
- **THEN** the artifact SHALL carry exactly one entry under `widgets.<activity>.submit`, and `MopData`'s entry for `(activity, "submit")` SHALL carry the `direct` flag
- **AND** its `click` pairs SHALL be `[[3,0],[5,2]]`, the merge by the minimum distance per target (INV-MOP-19 as amended)
- **AND** the resolution SHALL have happened host-side; on this side a JSON object admits one value per key, so the collision is unrepresentable

#### Scenario: Duplicate short id — unflagged does not displace flagged regardless of order
- **WHEN** the same two widgets appear in the reverse order in the producer's document
- **THEN** the artifact, and therefore `MopData`'s entry for `(activity, "submit")` and its pairs, SHALL be identical to the previous scenario's
- **AND WHEN** a third, unflagged widget with no pairs also resolves to `"submit"`, in any position
- **THEN** the entry SHALL keep the `direct` flag and the pairs `[[3,0],[5,2]]`
- **AND** the order-independence SHALL be the generator's, reinforced by INV-DRV-05: two orderings that derived to different artifacts would break byte-level determinism before they reached the jar

#### Scenario: Empty short id not bucketed
- **WHEN** a base activity's only MOP-flagged widget carries an empty `idName`
- **THEN** the artifact SHALL carry no entry under the empty-string key for that activity, and neither `StaticGuiModel.getWidget(activity, "")` nor `MopData` SHALL hold one
- **AND** `activityHasMop(activity)` SHALL still return `true`, and the drop SHALL be counted in `stats.droppedFlaggedNoId`, echoed on the load record as `droppedNoId`
- **AND** a node of that widget that carries a handler stamp listed in `handlers` SHALL still be resolved by `MopData.lookup` with source `STAMP`: the stamp needs no resource id

#### Scenario: Malformed distance pair dropped and counted
- **WHEN** a `formatVersion: 2` artifact with `targets: 27` carries a widget with `"dist": {"click": [[16,3],[27,1],[-1,2]]}`
- **THEN** `load` SHALL succeed, the widget's `click` pairs SHALL be `[[16,3]]`, and the load record SHALL carry `droppedPairs=2`

#### Scenario: Format-2 artifact missing a format-2 member is rejected
- **WHEN** a `formatVersion: 2` artifact has no `targets` member, or no `handlers` member, or carries `"targets": "27"`
- **THEN** `load` SHALL return no provider and emit one `status=rejected reason=parse-error` record
- **AND** with `ape.staticDataPath` set and `STATIC` or `MOP` mounted, the run SHALL abort via `StopTestingException` (INV-MOP-22)

#### Scenario: an artifact with no target runs without distance data
- **WHEN** a `formatVersion: 2` artifact carries `"targets": 0`, under either `ape.mopScoring` value
- **THEN** `load` SHALL return both providers and emit `MOP_DATA` with `targets: 0`
- **AND** the run SHALL proceed, and under `distance` every action SHALL score 0

### Requirement: Config.mopDataPath Flag

The device path of the derived compact artifact SHALL be the plan key `ape.staticDataPath` (design D-B2), owned by the data root feature `STATIC_DATA` (PRESENT, default `null`); it is read through the resolved plan, not through `Config`. When set, it points to the artifact on the device (`aperv-tool` pushes it to `/data/local/tmp/mop-artifact.json`). The full static-analysis JSON is never pushed to the device and no jar code path accepts it (INV-MOP-34). `ape.mopDataPath` is a retired key: a properties file that sets it aborts resolution with `retired_key` and the reason "renamed ape.staticDataPath: the artifact feeds STATIC and MOP (isolate-guidance-groups)" (`run-spec`).

The MOP group is switched by its own key, `ape.mop` (boolean, default `false`), which requires `STATIC_DATA`: the path alone mounts no MOP consumer, and `ape.mop=true` without a path aborts resolution with `MISSING_DEPENDENCY` (`guidance-groups`). The cells `mop`, `as_mop` and `as_mop_llm` set both.

The following MOP weight keys keep their defaults, matching the MopScorer boost table, and belong to the MOP group:

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `ape.mopWeightDirect` | int | `500` | Boost for direct MOP-reachable widget |
| `ape.mopWeightTransitive` | int | `300` | Boost for transitive MOP-reachable widget |

These weights are configurable via `ape.properties` but the defaults SHALL be 500/300. `Config.mopWeightActivity` SHALL NOT exist — the activity-level fallback it parameterised is removed (see "MopScorer — Priority Boost").

#### Scenario: Flag absent
- **WHEN** `ape.properties` does not contain `ape.staticDataPath`
- **THEN** `STATIC_DATA` SHALL be absent from the plan, no artifact SHALL be loaded, and neither `StaticGuiModel` nor `MopData` SHALL exist
- **AND** an explicit `ape.mop=true` SHALL abort resolution with `MISSING_DEPENDENCY`
- **AND WHEN** `ape.properties` contains `ape.mopDataPath=/data/local/tmp/mop-artifact.json`
- **THEN** resolution SHALL abort with `retired_key`

#### Scenario: Flag set
- **WHEN** `ape.properties` contains `ape.staticDataPath=/data/local/tmp/mop-artifact.json` and `ape.mop=true`
- **THEN** the plan SHALL carry `STATIC_DATA` and `MOP`, and the loader SHALL read `"/data/local/tmp/mop-artifact.json"`
- **AND WHEN** the same path is set with `ape.mop=false`
- **THEN** `MOP` SHALL be absent from the plan and no `MopData` SHALL be handed to any consumer
- **AND WHEN** the same path is set with neither `ape.mop` nor `ape.static` true
- **THEN** the artifact SHALL NOT be loaded, no `MOP_DATA` record SHALL be written, and `ape.staticDataPath` SHALL be listed in `RUN_START.inert` (design D-B14.20)

#### Scenario: Default MOP weights
- **WHEN** `ape.properties` does not contain any `ape.mopWeight*` keys
- **THEN** `mopWeightDirect` SHALL equal `500`
- **AND** `mopWeightTransitive` SHALL equal `300`

#### Scenario: Custom MOP weights override
- **WHEN** `ape.properties` contains `ape.mopWeightDirect=200`
- **THEN** `mopWeightDirect` SHALL equal `200`
- **AND** `mopWeightTransitive` SHALL retain its default (`300`)

### Requirement: ApePromptBuilder — Widget Metadata in LLM Context

The widget metadata of the LLM prompt is the STATIC group's prompt contribution (`StaticPromptContribution`, a `PromptContribution`, `llm-prompt`), mounted only when `STATIC` is in the plan. It SHALL surface, in widget descriptions emitted to the LLM, the following fields when non-null/non-empty: `prompt`, `spinnerMode`, `contentDescription`, `tooltipText`, `entries` (rendered as `entries=[v1, v2, …]`, capped to 10 with trailing `, …`), `inputType`, `hint`. The fields are read with `StaticGuiModel.getWidget`, keyed by id, because the static metadata is keyed by id (INV-MOP-36). The contribution reads no `MopData`. Without `STATIC`, no metadata field appears and `ApePromptBuilder` does not test for a provider: the contribution is simply not mounted.

Each field SHALL appear in the form ` <fieldName>="<value>"` (or `entries=[…]` for the list). Per-value 80-char cap with `…` ellipsis on overflow. Newlines (`\n`, `\r`) flattened to single space. Null / empty ⇒ zero tokens (INV-MOP-10).

This requirement governs the metadata fields only. The `[DM]`/`[M]` markers are the MOP group's contribution, and their rule is owned by `llm-prompt` "MOP Marker Annotation".

#### Scenario: Widget metadata appears when fields populated
- **WHEN** the `as_llm` cell renders a widget with `contentDescription="Encrypt button"`, `tooltipText="Tap to encrypt"`, `inputType=""` (Button), other metadata `null`
- **THEN** prompt SHALL contain `contentDescription="Encrypt button"` AND `tooltipText="Tap to encrypt"`
- **AND** prompt SHALL NOT contain `prompt=`, `spinnerMode=`, `entries=`, `inputType=`

#### Scenario: All-null metadata produces no tokens
- **WHEN** widget has all metadata fields null AND empty `entries`, or `STATIC` is absent from the plan
- **THEN** prompt SHALL contain none of `prompt=`, `spinnerMode=`, `contentDescription=`, `tooltipText=`, `entries=`, `inputType=`, `hint=`

#### Scenario: Long metadata truncated at 80 chars
- **WHEN** widget has `contentDescription` of 200 chars
- **THEN** rendered substring SHALL contain at most 80 value chars followed by `…"`

#### Scenario: Spinner entries appear and are capped at 10
- **WHEN** widget has `entries=["MD2","MD5","SHA-1","SHA-256","SHA-512"]`
- **THEN** prompt SHALL contain `entries=[MD2, MD5, SHA-1, SHA-256, SHA-512]`
- **WHEN** widget has 15 entries
- **THEN** rendered substring SHALL contain exactly 10 element tokens followed by `, …`

#### Scenario: Newlines flattened
- **WHEN** widget has `contentDescription="line1\nline2"`
- **THEN** rendered substring SHALL contain `line1 line2` (single-space replacement)

#### Scenario: InputType and hint surfaced for EditText
- **WHEN** widget has `type="android.widget.EditText"`, `inputType="textPassword"`, `hint="Your password"`
- **THEN** prompt SHALL contain `inputType="textPassword"` AND `hint="Your password"`

### Requirement: ApeFuzzer — Type-Aware Input Generation

`ApeFuzzer.generateInputForType(String inputType, String hint, java.util.Random rnd)` SHALL return a domain-correct random string based on `inputType`:

| `inputType` | Output shape |
|---|---|
| contains "Password" | 8–12 char mixed-class: ≥1 letter, ≥1 digit, ≥1 symbol |
| "number" / "numberSigned" / "numberDecimal" | numeric (signed/decimal as applicable) |
| "phone" | matches Brazilian phone template `+55 11 9XXXX-XXXX` (or locale-appropriate) |
| "textEmailAddress" | `<localPart>@example.com` with `localPart` 4–10 lowercase letters |
| "textUri" | `https://example.com/<8 lowercase>` |
| "date" / "time" / "datetime" | ISO 8601 random within the last decade |
| empty/unknown — and `hint` contains "email" (case-insensitive) | email shape |
| empty/unknown — and `hint` contains "senha" / "password" | password shape |
| empty/unknown — and `hint` matches `^\d+$` | numeric shape |
| else | legacy `RandomHelper.randomString` (or equivalent) |

The typed path is the STATIC member feature `TYPED_FUZZ`, activated by `ape.fuzzInputTyped` (default `true`) and depending on `STATIC`. Its caller (`ApeAgent.generateInputText`) SHALL use it only when `TYPED_FUZZ` is in the resolved plan, reading `inputType`/`hint` from `StaticGuiModel`; it reads no `MopData`. When the feature is absent (`STATIC` off, or `ape.fuzzInputTyped=false`), or when the widget is not in the static model, the caller SHALL use the legacy generator (no regression on non-instrumented apps). A step whose text came from the typed path SHALL carry `dec.inp:"typed"` (`event-sink`).

#### Scenario: Password input type produces mixed-class string
- **WHEN** `ApeFuzzer.generateInputForType("textPassword", "", rnd)` is called
- **THEN** the output SHALL be 8 to 12 chars
- **AND** the output SHALL contain at least one letter, one digit, and one symbol

#### Scenario: Number input type produces digits
- **WHEN** `ApeFuzzer.generateInputForType("number", "", rnd)` is called
- **THEN** the output SHALL match `^-?\d+$`

#### Scenario: Phone input type produces template-conformant string
- **WHEN** `ApeFuzzer.generateInputForType("phone", "", rnd)` is called
- **THEN** the output SHALL match a phone-shape regex

#### Scenario: Email input type
- **WHEN** `ApeFuzzer.generateInputForType("textEmailAddress", "", rnd)` is called
- **THEN** the output SHALL match `^[a-z]{4,10}@example\.com$`

#### Scenario: Hint-based fallback to email
- **WHEN** `ApeFuzzer.generateInputForType("", "Your email", rnd)` is called
- **THEN** the output SHALL match the email shape

#### Scenario: Unknown inputType falls back to legacy generator
- **WHEN** `ApeFuzzer.generateInputForType("weird_unknown", "", rnd)` is called
- **THEN** the output SHALL be a non-empty string from the legacy generator
- **AND** the output SHALL NOT match the password / number / phone / email shapes

#### Scenario: fuzzInputTyped=false bypasses typed generation (rollback guard)
- **WHEN** `ape.fuzzInputTyped=false` in a cell with `STATIC`, or the cell has no `STATIC` (`base`, `mop`), and an EditText whose static widget carries `inputType="textPassword"`, `hint="Your password"` receives text
- **THEN** `TYPED_FUZZ` SHALL be absent from the plan, the text SHALL NOT be produced by `generateInputForType`, and it SHALL match the legacy random-string shape
- **AND** in the `mop` cell the typed path SHALL stay off although the artifact is loaded, because typed input is a STATIC member, not a MOP one

### Requirement: MopData — Load Status Line and Fail-Fast

`StaticArtifactLoader.load` SHALL emit exactly one load status record per invocation, on both outcomes. The record is the out-of-step `MOP_DATA` NDJSON record of the event sink, which stays the census of the whole artifact load (both providers):

- success: `status=loaded formatVersion=<n> sourceDigest=<sha256> package=<pkg> windows=<n> widgets=<n> flagged=<n> droppedNoId=<n> wtgEdges=<n> mopActivities=<n> mopActsAugmented=<n> components=<n> handlersUnmatched=<n> syntheticLambda=<n> recovered=<n> targets=<n> handlers=<n> droppedPairs=<n>` — counts sourced from the loaded structures and the artifact's generator-computed `stats` echo (`droppedNoId`, handler-join counters, `orphanDialogs` are host facts, echoed not recomputed). `targets` is the artifact's target count, `handlers` the number of classes in the handler table, and `droppedPairs` the number of malformed distance pairs dropped at load ("MopData — Static Analysis JSON Loader")
- failure: `status=rejected reason=<file-missing|parse-error|version-mismatch|package-mismatch>`

`mopActsAugmented` SHALL be `|mopActivitiesAugmented \ mopActivities|` over the two wire sets — what the augmented source would contribute — and SHALL NOT vary with `MOP_ACTIVITY_SOURCE`. `mopActivities` remains the size of the **selected** set, so the two fields answer different questions on purpose.

`windows` is in the list deliberately: it survives inside the artifact as `stats.windows`. `transitions` is the single deliberate omission, superseded by `wtgEdges`. The record gains fields across changes and never loses one.

The record goes to the `.trace` stream, written by the sink directly to `System.out` and never through `Logger` (INV-SNK-11). It SHALL NOT be written to logcat: the rv-platform logcat parser owns that channel and foreign lines are forbidden.

Fail-fast composition:

1. **Plan level** (`run-spec`, `guidance-groups`): a plan that mounts `STATIC` or `MOP` without `ape.staticDataPath` is invalid and aborts at plan validation (`MISSING_DEPENDENCY`), before agent construction.
2. **Artifact level** (this requirement): when the plan mounts `STATIC` or `MOP` and `StaticArtifactLoader.load` returns no provider — for any reject reason, including a format-1 artifact — `StatefulAgent` SHALL abort the run (throw `StopTestingException`) instead of continuing without the group (INV-MOP-22).
3. **Host level** (`aperv-tool` in rv-android): a cell whose full JSON is missing or whose derivation fails raises before launch, and the host cache keys on the format as well as `source.digest`.

When `ape.staticDataPath` is unset, or when it is set and neither `STATIC` nor `MOP` is mounted (the path is then inert, design D-B14.20), nothing is loaded and no status record is written.

#### Scenario: successful load emits counters
- **WHEN** `StaticArtifactLoader.load` parses a format-2 artifact derived from a full JSON whose SHA-256 is `d`, with 30 widgets, 3 flagged, 0 dropped, 16 WTG edges, `targets: 27` and 2 handler classes
- **THEN** one `status=loaded` record SHALL be emitted carrying `formatVersion=2`, `sourceDigest=d`, those counters, `targets=27`, `handlers=2` and `droppedPairs=0`

#### Scenario: transitions present, click edges absent
- **WHEN** the artifact's `wtg` map is empty because the producer's document carried transitions but none of them a `click` event
- **THEN** the `MOP_DATA` record SHALL carry `wtgEdges:0`
- **AND** it SHALL carry **no** `transitions` field
- **AND** the jar SHALL have no other number to confuse it with: raw transitions do not exist on the wire (INV-MOP-35)

#### Scenario: rejected load names the reason
- **WHEN** the file at `ape.staticDataPath` is not a format-2 compact artifact (a full JSON, a format-1 artifact, or a document without `formatVersion`)
- **THEN** one `status=rejected reason=version-mismatch` record SHALL be emitted
- **AND** `load` SHALL return no provider

#### Scenario: fail-fast when the MOP arm cannot arm
- **WHEN** the `as_mop` cell sets `ape.staticDataPath` and `StaticArtifactLoader.load` returns no provider
- **THEN** `StatefulAgent` SHALL throw `StopTestingException` during setup
- **AND** the run SHALL NOT proceed without its STATIC and MOP groups

#### Scenario: unset path keeps SATA behavior
- **WHEN** the `base` cell runs, with no `ape.staticDataPath`
- **THEN** no provider SHALL exist and exploration SHALL proceed with no STATIC or MOP consumer, no `MopRetirement` and no `MopInteractionLedger`

### Requirement: Typed-Input Widget Resolution via Containment

When `ApeAgent.generateInputText` resolves the static widget for an `EditText` under `TYPED_FUZZ` (to read its `inputType`/`hint` for type-aware generation), the lookup SHALL go through `StaticGuiModel.getWidget` and SHALL apply the same parent/child containment walk used by the MOP scoring pass (±2-level ancestor/descendant id probe, as specified in "Parent/child widget granularity reconciliation"): the node's own short id is tried first, then the containment walk (INV-MOP-23). The walk is shared code; the typed-input path reads no `MopData`, so it works identically in a cell with STATIC and without MOP. When no widget resolves (or `inputType`/`hint` are empty), the fallback to the next text source is unchanged.

#### Scenario: child id resolves the container's typed metadata
- **WHEN** in the `as` cell the static model holds widget `login_form` with `inputType="textEmailAddress"` and the runtime EditText resolves to child id `login_form_field`, one containment level below
- **THEN** `generateInputText` SHALL resolve the `login_form` widget via the containment walk
- **AND** the generated value SHALL be email-shaped and the record SHALL carry `dec.inp:"typed"`

#### Scenario: no static widget still falls back
- **WHEN** neither the node's id nor its containment neighborhood matches a static widget
- **THEN** the typed path SHALL produce nothing and the next text source SHALL be used, as before

### Requirement: MopScorer — MOP-Flagged State Density

`MopScorer.stateMopDensity(State, MopContext, int timestamp)` SHALL return `0` when the state's activity does not satisfy `activityHasMop(activity)` (cheap early-out, no widget resolution). Otherwise it SHALL return `1 + count`, where `count` counts only actions whose resolved node is MOP-flagged: for each valid, target-requiring action, the action is counted when `MopData.lookup(activity, node, eventType)` reports a direct or transitive flag (INV-MOP-36).

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

### Requirement: Navigation MOP-Tiebreak Decision Log

When the X4 MOP variant (`MopTrivialPathPick`) chooses among multiple shortest paths and the `stateMopDensity` comparison is decisive (densities not all equal), it SHALL log exactly one line: `[APE-RV] Nav MOP tiebreak: density=<d> paths=<n>`, where `<d>` is the winning path's end-state density and `<n>` the number of candidate paths, and the step's record SHALL carry `dec.nav` with `x:"X4"`, `c:"mop"`, `alt:1`, `drew:0` (`action-selection`). When all densities are equal (the `randomPick` fallback) no line SHALL be logged and `dec.nav` carries `alt:0`, `drew:1`. The APE default, mounted without `MOP`, never logs this line. The X3 variant does not log (it runs inside a candidate loop; logging there would flood the trace); its decision is visible in `dec.nav`. `dec.nav.alt` SHALL be `1` if and only if the MOP variant decided by its own rule — for X3, it replaced the incumbent; for X4, the density chose the path with no draw — and `0` otherwise (design decision A2). X1 has no MOP variant and always records `alt:0`.

#### Scenario: decisive density logs once
- **WHEN** in a cell with `MOP` 3 candidate paths have end-state densities 0, 0, 2
- **THEN** the path with density 2 SHALL be chosen, one `[APE-RV] Nav MOP tiebreak: density=2 paths=3` line SHALL be logged, and the record SHALL carry `dec.nav:{"x":"X4","c":"mop","alt":1,"drew":0}`

#### Scenario: all-equal densities stay silent
- **WHEN** all candidate paths have equal end-state density, or the cell has no `MOP`
- **THEN** the path SHALL be chosen by `RandomHelper.randomPick` and no tiebreak line SHALL be logged

### Requirement: MopData — Activity-Level MOP Source from Components (A′)

The A′ 3-source union (widget-derived activities ∪ component-flagged activities ∪ reachability-flagged activity classes) SHALL be computed **host-side by the generator** and shipped as the wire field `mopActivitiesAugmented`, alongside the widget-derived `mopActivities` (see `static-analysis-entrypoints` for the derivation rules). On-device, the resolved plan selects which set backs `activityHasMop`/`getMopActivities`:

- `MOP_ACTIVITY_SOURCE` absent from the plan: the widget-derived set, exactly.
- `MOP_ACTIVITY_SOURCE` present: the augmented set.

The selection SHALL happen once at load, from `spec.has(Feature.MOP_ACTIVITY_SOURCE)` passed to the loader; `MopData` SHALL NOT read `Config.mopActivitySourceComponents` (design D-B6, the former read site `utils/MopData.java:207`). Every downstream consumer (launcher census ordering, `stateMopDensity` substrate floor, WTG/MopFrontier target tests, OPTIONSMENU-gateway condition 2, the MOP prompt contribution) reads the selected set.

In the run-spec `Feature` model, `ape.mopActivitySourceComponents` activates `MOP_ACTIVITY_SOURCE`, a MOP member that depends on `MOP` (which requires `STATIC_DATA`). An explicit `true` on a plan without `MOP` aborts resolution as a missing dependency; with `MOP` off and the key at its default, the feature is left out silently. The MOP block of the cell presets sets it `true`.

#### Scenario: flag off preserves widget-only source
- **WHEN** `ape.mopActivitySourceComponents=false` in a cell with `MOP` and the artifact's augmented set contains an activity absent from the widget-derived set
- **THEN** `activityHasMop` for that activity SHALL be `false`
- **AND** the launcher census SHALL NOT include it

#### Scenario: component-level activity added under the flag
- **WHEN** `MOP_ACTIVITY_SOURCE` is in the plan (the `mop` cell preset)
- **THEN** `getMopActivities()` SHALL equal the wire `mopActivitiesAugmented` set
- **AND** the OPTIONSMENU-gateway recompute SHALL test condition 2 against it

#### Scenario: union preserves widget-derived entries
- **WHEN** `MOP_ACTIVITY_SOURCE` is in the plan and activity `com.x.A` is in the artifact's `mopActivities` through a flagged widget
- **THEN** `activityHasMop("com.x.A")` SHALL remain `true` — turning the feature on SHALL never remove an activity
- **AND** the additivity SHALL be a property of the wire: `mopActivities ⊆ mopActivitiesAugmented` holds by construction of the union (INV-DRV-06)

#### Scenario: non-reaching component not added
- **WHEN** `MOP_ACTIVITY_SOURCE` is in the plan and `com.x.Plain` has no flagged widget, `reachesMop==false`, and no reaching method in the producer's `reachability[]`
- **THEN** `activityHasMop("com.x.Plain")` SHALL return `false` under both selections, the activity being in neither wire set

#### Scenario: reachability-method source flags a lambda-gapped activity (source 3)
- **WHEN** `com.x.CryptoActivity` has `components.activities[].reachesTarget==false` and no MOP-flagged widget, BUT its `reachability[]` class (`componentType=="activity"`) carries ≥1 method with `reachesTarget==true`
- **THEN** it SHALL be present in the artifact's `mopActivitiesAugmented` and absent from `mopActivities`, so `activityHasMop("com.x.CryptoActivity")` SHALL be `true` with `MOP_ACTIVITY_SOURCE` in the plan and `false` without it
- **AND** source 3 SHALL remain a distinct source, because the producer's call graph does not traverse D8-desugared lambda edges

#### Scenario: explicit activation without MOP data aborts
- **WHEN** `ape.properties` sets `ape.mopActivitySourceComponents=true` and no `ape.mop=true` (with or without `ape.staticDataPath`)
- **THEN** resolution SHALL abort with a missing-dependency diagnostic naming `MOP_ACTIVITY_SOURCE` and `MOP`
- **AND** the abort SHALL precede any artifact read (the dependency is a plan property, not an artifact property)

### Requirement: Target Retirement

Under `ape.mopScoring=distance`, the agent SHALL keep one counter per target, sized by `MopData`'s `targets` (`MopRetirement`). `MopRetirement` is created once per run by `StatefulAgent` after the artifact loads when `MOP` is in the plan; it is null when `MOP` is absent or the mode is `flag`.

Retirement SHALL compose on `MopInteractionLedger` ("MopInteractionLedger — Executions per Activity and Key") and keep no first-interaction record of its own. In `StatefulAgent.moveForward`, for the model action about to be executed, the agent SHALL first read `MopInteractionLedger.tried(activity, xpath|ActionType)`; if the key is not yet there, this is a **first interaction**. It SHALL then record the execution, whether first or not (every execution adds one, design D-B17); only a first interaction counts targets. Novelty is per (widget, action type) and per activity, so a long-click on a widget already clicked in the activity is a first interaction.

On a first interaction, the agent SHALL take the **weight's lookup** for the action's event (`MopScorer.eventTypeOf`): among the action's containment lookups ("Parent/child widget granularity reconciliation"), the one holding the action's smallest unretired distance, ties going to the earlier node in containment order (the node itself, its ancestors, then its descendants). It is the lookup behind `dec.mopd`/`dec.mops` (`event-sink`) and the MOP coin's target count, so what gives an action its weight is what retires it. Only an action the score treats as MOP retires anything: when the action's weight before the tie split is 0, nothing is counted. Otherwise the agent SHALL call `MopRetirement.onFirstInteraction(weightLookup)`, which adds one to the counter of each target index in the lookup's `dist` whose pair lies at `d*`, the lookup's smallest unretired distance, each index once, and returns the indices whose counter reached `ape.mopRetireAfter` (default 3) on this call. Those indices are the step's `dec.ret` (`EventSink.mopRetired`, `event-sink`).

The count is applied at `moveForward` without a pending step: the restart is a guard stage placed before selection (`decision-pipeline` `RESTART`, design D-B7), so no selected action is replaced after selection and the former `checkRestart` override that discarded a pending count does not exist. A step the `RESTART` guard takes selects the restart action, which has no target and counts nothing.

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
- **WHEN** under `distance` a restart is due at step 40, so the `RESTART` guard takes the step before any selection, while the state holds an untried widget whose weight's lookup carries `[[22,2]]`
- **THEN** no counter SHALL change, the ledger SHALL NOT record that widget's key, and the step SHALL carry `dec.src:"restart"` and no `dec.ret`
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

### Requirement: MopScorer — Priority Boost

`MopScorer.score(MopContext, State, ModelAction)` SHALL return an integer priority boost for one valid, target-requiring action (INV-MOP-02), computed from `MopData.lookup` (INV-MOP-36) over the action's containment nodes ("Parent/child widget granularity reconciliation") for the action's event (`MopScorer.eventTypeOf`). The boost is additive: `MopWidgetPass`, the MOP group's widget scorer, mounted only when `MOP` is in the plan (`scoring-pipeline` "Scorer Ownership by Group"), adds it to `ModelAction.priority`, never replacing it (INV-MOP-03), and stores it as the action's `mopBoost`. The mode is the plan key `ape.mopScoring` (`run-spec`), `flag` or `distance`, default `flag` (design D18); the `MopContext`, built from `spec().mop()`, carries the data, the mode, the weights and the run's retirement (design D17, Plumbing). No weight is read from `Config`.

**`flag` mode.** The boost comes from the largest flag weight over the containment lookups, ties to the earlier lookup in containment order; this is the strongest flag (direct > transitive > none) whenever `mopWeightDirect >= mopWeightTransitive`, as with the defaults:

| Best containment lookup | Boost |
|-----------|-------|
| direct flag for the event | `+mopWeightDirect` (500) |
| transitive flag for the event (and not direct) | `+mopWeightTransitive` (300) |
| no flag on any containment node, or `NONE` everywhere | `0` |

On an unstamped APK every lookup takes the id key, so `flag` reproduces the E6 weights. `MopRetirement` is null under `flag`, and distances are not read.

**`distance` mode.** Let `d` be the smallest distance over the pairs of all the action's containment lookups whose target is not retired ("Target Retirement"). The weight is `ape.mopWeightD1` (500) for `d ≤ 1`, `ape.mopWeightD2` (400) for `d = 2`, `ape.mopWeightD3` (300) for `d = 3`, and `0` for `d ≥ 4` or when no unretired pair exists. Flags are not read. `score` returns this weight before the tie split.

**Tie split (`distance` only).** `MopWidgetPass.apply` SHALL first compute the weight and `d` of every scored action of the state. Ties are by weight level, not by exact `d`: `d = 0` and `d = 1` form one level (weight `ape.mopWeightD1`), and `d = 2` and `d = 3` one level each. The tied actions are those whose undivided weight equals the state's best level, the largest positive undivided weight among the state's scored actions. When there are `n > 1` of them, each SHALL receive `max(W / n, W_next)`, with `W` the weight of that level, `n` the number of tied actions, integer division, and `W_next` the weight of the next level (`ape.mopWeightD2` for the `d ≤ 1` level, `ape.mopWeightD3` for `d = 2`, `0` for `d = 3`). The floor is the author's decision of 2026-10-07: without it, two actions at `d ≤ 1` would get 250 each and fall below a single action at `d = 2` (400). Actions at a worse level keep their undivided weight. The split value is what is added to the priority and stored as `mopBoost`. The undivided weight SHALL be stored beside it as the action's `mopWeight`, which the MOP coin ranks by ("MOP Coin Decider", design D-B10) and which is never part of the scorer sum (`scoring-pipeline`), and reset with `mopBoost`. Under `flag` `mopWeight` equals `mopBoost`. The levels are found by weight value, so the floor assumes `ape.mopWeightD1 >= ape.mopWeightD2 >= ape.mopWeightD3`, as the defaults are: a plan with equal or inverted level weights is accepted, not rejected (author, 2026-10-09; review NT-02).

**Every weighted action is boosted, tried or not** (design D-B19, reverted 2026-10-19). The pass SHALL NOT read `MopInteractionLedger`: whether an action's (activity, `xpath|ActionType`) key was already executed changes neither its boost nor the tie split.

Under rule R1 (`scoring-pipeline` "Scorer Independence (Rule R1)") the scorer reads neither the action's priority nor another scorer's field. `mopWeightDirect`/`mopWeightTransitive` are inert under `distance`, and `mopWeightD1..3` under `flag`; `RUN_START.inert` reports them (`run-spec`). The menu-gateway, WTG and MOP-frontier passes are the same in both modes. WTG and the MOP frontier read `activityHasMop`; the menu gateway reads `activityHasMopOptionsMenu`, which load computes from `optionsMenus` and the WTG edges of `StaticGuiModel` into the selected MOP set. Neither changes meaning with the mode. The STATIC `NavFrontierPass` reads no MOP datum (`wtg-navigation`).

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

### Requirement: Parent/child widget granularity reconciliation

The MOP scoring pass SHALL reconcile parent/child granularity over **nodes**, not over resource ids (design D17). For an action's resolved node it SHALL run `MopData.lookup` on the node itself, on up to two ancestors (`getParent`), and on its descendants down to two levels (children, then grandchildren), and SHALL keep the best result: under `flag` the largest flag weight (the strongest flag, direct > transitive > none, whenever `mopWeightDirect >= mopWeightTransitive`), under `distance` the smallest unretired distance. The node's own lookup is not privileged; the best result wins wherever it comes from. Each containment node is looked up with its own stamp and its own resource id, so a stamped ancestor or descendant contributes its handler's record.

This addresses the case where static analysis flags a container id (e.g., a `CardView`) while the runtime resolves a child (e.g., the inner `LinearLayout`), or vice versa, and the case where the stamped handler sits on a node the action does not target. The depth bound guards against over-boost (a root container marked by a single MOP child). A boost won by a node other than the action's own is counted as a containment hit on the pass's log line, as today.

Containment applies to scoring and to the readers that share its test: the MOP coin's eligibility and ranking ("MOP Coin Decider"), the prompt marker, and the retirement trigger, which counts the targets of the lookup that won the action its weight ("Target Retirement"). The typed-input path of the STATIC group walks the same node set over `StaticGuiModel.getWidget` (INV-MOP-23). Containment SHALL NOT apply to the state MOP density read by the X3/X4 MOP variants, which reads each action's own node (design D17).

#### Scenario: Static flags parent, runtime resolves child
- **WHEN** the action's own node has a `NONE` lookup for its event
- **AND** an ancestor within depth 2 has a lookup with a MOP flag or an unretired pair
- **THEN** the scorer SHALL use that ancestor's lookup for the boost

#### Scenario: Depth bound respected
- **WHEN** the only node with a MOP flag or a pair is an ancestor at depth 3
- **THEN** the reconciliation SHALL NOT match it (bound is depth ≤ 2)
- **AND** the scorer SHALL fall through to `0` (no activity-level fallback)

### Requirement: WTG Scoring Pass in adjustActionsByGUITree

The WTG-MOP term SHALL be computed by `WtgPass`, a MOP scorer of the scoring pipeline (`scoring-pipeline` "Scorer Ownership by Group"), assembled by `ScoringPipeline.fromParams` in the pipeline that `StatefulAgent.adjustActionsByGUITree()` runs. The pass SHALL be constructed if and only if the member feature `WTG` (a MOP member, activated by `ape.mopWeightWtg`) is in the resolved plan and `StaticGuiModel.hasWtgData()` holds; it is absent otherwise, with no per-call test. For each valid, target-requiring, resolved action, the pass SHALL call `MopScorer.scoreWtg` with the action's activity and short widget id, the WTG view of `StaticGuiModel`, the MOP-activity set of `MopData` and the injected weight (`wtg-navigation` "MopScorer — WTG Priority Boost"), write the result to the action's `wtgBoost` and add it to the priority. Under rule R1 it reads neither the priority nor any other scorer's field, so its term does not depend on the MOP widget pass or on any other pass, and `wtgBoost` has no other writer.

#### Scenario: WTG boost applied alongside MOP boost
- **WHEN** in a cell with `MOP` a widget's lookup is direct (MOP widget term +500 under `flag`) AND its WTG transition leads to a MOP activity (WTG term +200)
- **THEN** the total priority gain from the two passes SHALL be +700
- **AND** the action SHALL carry `mopBoost == 500` and `wtgBoost == 200` in separate fields

#### Scenario: No WTG data
- **WHEN** `StaticGuiModel.hasWtgData()` is false, or `WTG` is absent from the plan (no `MOP`, or `ape.mopWeightWtg=0`)
- **THEN** `WtgPass` SHALL NOT be constructed, and no action SHALL carry a `wtgBoost`

### Requirement: Config Flag for WTG Weight

The WTG navigation weight SHALL be the plan key `ape.mopWeightWtg` (int, jar default `200`), the activation key of the MOP member feature `WTG` (POSITIVE, neutral `0`, depends on `MOP`). It is read through the resolved plan and reaches `WtgPass` injected through `ScoringParams`; no scorer SHALL read it from `Config` (`scoring-pipeline` INV-ARCH-11). A value `<= 0` leaves `WTG` out of the plan, with no abort, on a plan with or without `MOP`, so `WtgPass` is not constructed and no negative boost is ever applied (design D-B22). An explicit positive value on a plan without `MOP` aborts resolution with `MISSING_DEPENDENCY` (`run-spec`). The MOP block of the cell presets states `ape.mopWeightWtg=200` (`guidance-groups`).

| Key | Owner | Type | Default | Description |
|-----|-------|------|---------|-------------|
| `ape.mopWeightWtg` | `WTG` (MOP) | int | 200 | WTG navigation boost (`<= 0` = feature absent) |

#### Scenario: Default WTG weight
- **WHEN** a plan with `MOP` does not state `ape.mopWeightWtg`
- **THEN** `WTG` SHALL be in the plan's `features` and `WtgPass` SHALL boost with weight `200` (when `StaticGuiModel.hasWtgData()` holds)

#### Scenario: WTG weight disabled
- **WHEN** a plan with `MOP` states `ape.mopWeightWtg=0`
- **THEN** `WTG` SHALL be absent from the plan's `features` and `WtgPass` SHALL NOT be constructed
- **AND** no action SHALL carry a `wtgBoost`, and no step record a `dec.wtg`
- **AND WHEN** the plan states `ape.mopWeightWtg=-100` instead, with or without `MOP`
- **THEN** `WTG` SHALL likewise be absent, resolution SHALL NOT abort, and no negative `wtgBoost` SHALL be applied (design D-B22)

### Requirement: MopScorer — OPTIONSMENU-Aware Menu Boost

`MopScorer.scoreOpenMenu`, given the activity, the `MopData` and the injected weight, SHALL return that weight when `data.activityHasMopOptionsMenu(activity)` is `true`, else `0`. The weight is the plan key `ape.mopWeightOpenMenu` (default 250), the activation key of the MOP member `MENU_GATEWAY` (which also requires the ADDONS member `MODEL_MENU`), read through `spec().mop()` and injected through `ScoringParams`; no code SHALL read it from `Config`. The lookup SHALL be O(1) over the precomputed `activitiesWithMopOptionsMenu` set (INV-MOP-13). The MOP block of the cell presets does not state the key (design D-B14.1): its jar default mounts `MENU_GATEWAY` where `MODEL_MENU` is present (`as_mop`, `as_mop_llm`) and leaves it out silently in the `mop` cell.

`MenuGatewayPass`, constructed only when `MENU_GATEWAY` is in the plan, SHALL apply this boost to the `MODEL_MENU` action of the current state: it writes `menuBoost` and adds it to the priority. Under rule R1 (`scoring-pipeline`) the pass SHALL NOT read the ADDONS BACK/MENU cap (design D-B14.19): it consults no `menuPickEligible` hook and no pick counter, so its term is the same whether or not `BACK_MENU_CAP` is mounted. A capped MENU is kept out of selection by the cap itself, which removes it from every discretionary pick (`action-selection` "BACK/MENU discretionary pick cap"), so the boost on a capped MENU steers nothing.

#### Scenario: Boost applied when OPTIONSMENU has MOP widget
- **WHEN** `data.activityHasMopOptionsMenu("com.x.A")==true` AND `MopScorer.scoreOpenMenu` is called for `com.x.A` with the injected weight 250
- **THEN** the return value SHALL equal `250`, the injected `ape.mopWeightOpenMenu`

#### Scenario: Zero when no OPTIONSMENU MOP widget
- **WHEN** `data.activityHasMopOptionsMenu("com.x.B")==false`
- **THEN** `MopScorer.scoreOpenMenu` for `com.x.B` SHALL return `0`

#### Scenario: Action-priority pass boosts MODEL_MENU when activity has MOP options menu
- **WHEN** `MenuGatewayPass` runs in the `as_mop` cell on a state whose activity is in `activitiesWithMopOptionsMenu`
- **THEN** the `MODEL_MENU` action's priority SHALL be incremented by the injected `ape.mopWeightOpenMenu` and its `menuBoost` SHALL equal it
- **AND** a `Logger.iformat` line SHALL summarize the boost

#### Scenario: Boost suppressed once the MENU key is capped
- **WHEN** in the `as_mop` cell the activity's MENU pick key has reached `ape.backMenuPickCap` AND `MenuGatewayPass` runs on a state of that activity whose activity is in `activitiesWithMopOptionsMenu`
- **THEN** the `MODEL_MENU` action SHALL still receive the boost (`menuBoost` equal to the injected weight), because the pass reads no cap state (design D-B14.19)
- **AND** the capped `MODEL_MENU` action SHALL NOT be selected by any discretionary pick, since the cap filters it out of them (`action-selection`)
- **AND** the header is kept because `openspec archive` matches scenarios by name: the suppression it names now comes from the cap's filter, not from the boost pass

### Requirement: StatefulAgent — Tuple-Based Component Triggering

`StatefulAgent.triggerComponent()` (renamed from `triggerMopComponent()`, design D-B14.15) SHALL build a round-robin list of `(component, intentFilter, action)` tuples (plus a parallel `(provider, operation)` list) and advance an index per call. It is invoked only by the `ComponentTrigger` stage, which exists only when the STATIC member `COMPONENT_TRIGGER` is in the plan (`component-triggering`, design D-B13). Tuple construction SHALL read `StaticGuiModel` only and no MOP datum (`component-triggering` INV-CT-16):

- Iterate `getReceivers() + getServices()`. Activities SHALL NOT enter the pool under any configuration: they are launched only by the `MopLauncher` stage (`component-triggering` INV-CT-03).
- Admit every receiver and service that the existing admission rules accept (permission gate, same package), with no filter on `reachesMop`/`reachesTarget`, `hasTargetMethods`, distances or handlers.
- For each admitted component, for each `IntentFilter`, for each `action` in the filter, emit one tuple. A Service with no filter action emits exactly one component-name-only tuple (`filter=null, action=null`); a Receiver with no filter action emits none (design D-B14.15).
- For each provider with non-null, non-empty `authorities`, emit three `ProviderTuple` (query, insert, update) in a sub-cycle.

For each invocation:
- Tuple selection: `tupleList[componentTriggerIndex++ % tupleList.size]`.
- Intent construction: `setComponent(StaticGuiModel.getPackageName(), className)`; if `action != null`, `setAction(action)`; for each `category` in `filter.categories`, `addCategory(category)`; when the filter carries a `data` member, the intent's data and type from it (`component-triggering` INV-CT-17): the URI `scheme://host + path` from the first scheme, the first host and the first path, falling back to the first path prefix, `host` and `path` empty when absent (design D-B14.11, D-B14.15).
- Existing `SystemBroadcastCatalog` extra application preserved for receivers AFTER `setAction`, BEFORE `sendBroadcast`; the catalog is loaded iff `COMPONENT_TRIGGER` is mounted.
- Provider invocation: `AndroidDevice.runShell("content " + operation + " --uri content://" + authorities)`. Non-zero exit ⇒ WARN with stderr.
- Logging: `[APE-RV] Triggering <type>: <className> action=<a> categories=<c1,c2> permission=<p>`. The line SHALL NOT carry `reachesTarget`.

#### Scenario: Skips non-reachable components
- **WHEN** `StaticGuiModel` has a same-package receiver with a filter action whose component `MopData` would record `reachesMop=false`
- **THEN** the tuple list SHALL contain that receiver's tuple, because the pool reads no MOP data
- **AND** the header is kept because `openspec archive` matches scenarios by name: the reachability filter it names is removed (design D-B13)

#### Scenario: Skips non-exported activities even when activity triggering is enabled
- **WHEN** the plan carries `MOP_LAUNCHER` (formerly `ACTIVITY_TRIGGER`) and the artifact lists activities
- **THEN** the tuple list SHALL contain no tuple from any activity, exported or not (`exported` is not on the wire)

#### Scenario: Round-robins all intent filter actions
- **WHEN** a receiver has one `IntentFilter` with two actions `["action1", "action2"]`
- **AND** `triggerComponent` is called 4 times in sequence
- **THEN** action1 and action2 SHALL each have been used exactly 2 times

#### Scenario: Provider operations round-robin
- **WHEN** `StaticGuiModel` has a provider with `authorities="com.x.p"`
- **AND** `triggerComponent` is called 6 times in sequence (provider-only fixture)
- **THEN** the shell commands invoked SHALL be in the cyclic order `content query`, `content insert`, `content update`, `content query`, …

#### Scenario: Log line contains expected fields
- **WHEN** a receiver tuple is triggered
- **THEN** the logged line SHALL contain `className=…`, `action=…`, `categories=…` and the permission gate
- **AND** it SHALL NOT contain `reachesTarget`

#### Scenario: activityTriggerEnabled=false excludes activities from the tuple list (rollback guard)
- **WHEN** the artifact carries one activity and one receiver with a filter action, whether `ape.activityTriggerEnabled` is `false` or `true`
- **THEN** the built tuple list SHALL contain ≥1 tuple from the receiver AND zero tuples from the activity
- **AND** `ape.activityTriggerEnabled` SHALL govern only the `MopLauncher` stage (`component-triggering`)

#### Scenario: Empty trigger list returns false without side effects
- **WHEN** `StaticGuiModel` carries components but none yields a tuple (only receivers with empty `intentFilters`, no service, no provider with `authorities`)
- **AND** `triggerComponent()` is invoked
- **THEN** the call SHALL return `false`
- **AND** no shell command / broadcast / service start SHALL be invoked
- **AND** the round-robin counter SHALL NOT advance

#### Scenario: Component with empty intentFilters but non-empty targetMethods emits component-name-only tuple
- **WHEN** `StaticGuiModel` carries a service with `intentFilters=[]`, whatever its `hasTargetMethods`
- **THEN** the tuple list SHALL contain exactly one `TriggerTuple` for this service with `filter=null` and `action=null`
- **AND** the built Intent SHALL have `setComponent(...)` called and SHALL NOT have any `setAction` / `addCategory` calls
- **AND WHEN** a receiver carries `intentFilters=[]`
- **THEN** the tuple list SHALL contain no tuple for it (design D-B14.15)

### Requirement: MopData — Package / MainActivity Sanity Check

`StaticArtifactLoader.load(String path, String expectedPackage, String expectedMainActivity)` SHALL compare the artifact's top-level `package` / `mainActivity` against the optional expected values, before either provider is handed out. When `expectedPackage` non-null and not equal to the parsed value, OR `expectedMainActivity` non-null and not equal to the parsed value, a `WARN` log line naming both pairs SHALL be emitted. The strict mode is the plan key `ape.mopStrictPackageMatch`, which keeps its name and is owned by `STATIC_DATA` (`StaticParams.strictPackageMatch`, design D-B14.3), because the package check is on the static model; it is read through the plan as `spec.staticData().strictPackageMatch()`, never from `spec.mop()` and never from `Config`. When it is `true` and either comparison mismatches, `load` SHALL return no provider (reject reason `package-mismatch`). Default behavior (`false`) is warn-only — both providers are still returned. The generator copies both scalars verbatim from the full JSON, so a mismatch means the wrong artifact was derived or pushed for this APK.

The production call site (`StatefulAgent` constructor, reached only when `STATIC` or `MOP` is mounted) SHALL pass the path from `StaticParams` and the runtime package name and main activity of the app under test, making the sanity check reachable in production. The three-argument form is the sole loader entry point.

#### Scenario: Default warn-only on mismatch
- **WHEN** `ape.mopStrictPackageMatch=false` AND `StaticArtifactLoader.load(path, "x.y.z.OTHER", null)` is called on an artifact with `package="x.y.z"`
- **THEN** both providers SHALL be returned
- **AND** a `WARN` log line SHALL be emitted

#### Scenario: Strict-mode rejection on mismatch
- **WHEN** `ape.mopStrictPackageMatch=true` AND `StaticArtifactLoader.load(path, "x.y.z.OTHER", null)` is called on an artifact with `package="x.y.z"`
- **THEN** no provider SHALL be returned
- **AND** a `WARN` log line SHALL be emitted
- **AND** the load record SHALL carry `reason=package-mismatch`

#### Scenario: Null expected values bypass the check
- **WHEN** `StaticArtifactLoader.load(path, null, null)` is called
- **THEN** no sanity-check WARN log SHALL be emitted (regardless of `ape.mopStrictPackageMatch`)
- **AND** both providers SHALL be returned

#### Scenario: Production call site passes runtime identity
- **WHEN** `StatefulAgent` loads the artifact for an app whose runtime package is `br.unb.cic.cryptoapp`, in a cell that mounts `STATIC` or `MOP`
- **THEN** it SHALL call `StaticArtifactLoader.load(<StaticParams path>, "br.unb.cic.cryptoapp", <mainActivity>)`
- **AND** an artifact derived for a different APK SHALL trigger the sanity-check WARN (and rejection under `ape.mopStrictPackageMatch=true`)
