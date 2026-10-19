## Purpose

The static analysis produced by rv-android contains a Window Transition Graph (WTG): which click events on which
widgets navigate from one window to another. The host-side generator (rv-android `derive_mop_artifact.py`) reduces
it to the `wtg` map of the compact artifact (wire format 2): per base source activity, the click edges
`{widget, target}`, both ends already base activities and exact duplicates removed. At load,
`StaticArtifactLoader` transcribes that map into the WTG view of `StaticGuiModel`, the static provider it splits
from the artifact (`guidance-groups`). Only `click` events are kept — implicit events (home, rotate, power, back)
are not controllable exploration actions. MenuItem clicks ARE included: they represent 24-83% of click transitions
in real apps (cryptoapp: 4/17, ApkTrack: 5/6), and MenuItem widget names (e.g., `menu_item_cipher`) match the short
id extracted from `GUITreeNode.getResourceID()` at runtime.

The WTG turns guidance from reactive (boost widgets in the current state) to proactive (navigate toward an
activity worth reaching). Three consumers read it, in two groups (design D-A1, D-B1, P-1):

- the STATIC member `NAV_FRONTIER` (key `ape.frontierBoostWeight`, default 200), computed by `NavFrontierPass`,
  boosts a widget whose static transition leads to an activity the run has not visited. It depends on `STATIC`,
  not on `MOP`, reads no `MopData`, and writes its own field `navFrontierBoost`, rendered `dec.navf`;
- the MOP member `WTG` (key `ape.mopWeightWtg`, default 200), computed by `WtgPass` through
  `MopScorer.scoreWtg`, boosts a widget whose static transition leads to a MOP-reachable activity. It is the only
  writer of `wtgBoost`, rendered `dec.wtg`;
- the MOP member `MOP_FRONTIER` (`MopFrontierPass`) and the OPTIONSMENU-gateway precompute.

The artifact is loaded, and `StaticGuiModel` built, whenever `STATIC` or `MOP` is in the plan (P-1, D-B14.20), since
MOP reads both providers; in the `mop` cell the WTG is therefore loaded although `STATIC` is off.

## Data Structures

### WtgTransition

A data class (`StaticGuiModel.WtgTransition`) representing one click edge from one activity to another:

```java
public static class WtgTransition {
    public final String widgetName;      // resource name (e.g., "menu_item_cipher", "buttonCipher")
    public final String targetActivity;  // base target activity (e.g., "com.example.CipherActivity")
}
```

Stored in `StaticGuiModel` as `Map<String, List<WtgTransition>>` keyed by base source activity, and queried through
`StaticGuiModel.wtgTransitions(activity)` (an empty list for an activity without edges) and
`StaticGuiModel.hasWtgData()`.

## Data Contracts

### Input
- `wtg: JSON object` — the WTG map of the compact artifact, wire format 2 (source: `ape.staticDataPath` file on
  device, derived by rv-android `derive_mop_artifact.py`)
  - key: base source activity
  - value: array of click edges
    - `widget: String` — resource name of the triggering widget (may be empty)
    - `target: String` — base target activity
- `ape.frontierBoostWeight: int` — `NAV_FRONTIER` weight (STATIC; POSITIVE, default 200)
- `ape.mopWeightWtg: int` — `WTG` weight (MOP; POSITIVE, default 200)

### Output
- `wtgBoost: int` — `WtgPass`'s priority boost for a widget leading to a MOP-reachable activity (`dec.wtg`)
- `navFrontierBoost: int` — `NavFrontierPass`'s priority boost for a widget leading to an unvisited activity
  (`dec.navf`)
- `StaticGuiModel.wtgTransitions(activity): List<WtgTransition>` — the edges from the given base activity
  (consumers: `NavFrontierPass`, `WtgPass`/`MopScorer.scoreWtg`, `MopFrontierPass`, the OPTIONSMENU-gateway
  precompute)

### Side-Effects
- **[Memory]**: the WTG view is held by `StaticGuiModel` for the run.

### Error
- None. When `wtg` is absent or empty, `hasWtgData()` is false, the WTG passes are not constructed, and
  `MopScorer.scoreWtg` returns 0 for every widget.

## Invariants

- **INV-WTG-01**: Only events with `type` equal to `"click"` SHALL be parsed from `transitions[].events[]`. Implicit events (implicit_home_event, implicit_back_event, implicit_rotate_event, implicit_power_event, implicit_on_activity_result, implicit_on_activity_newintent) SHALL be ignored.
- **INV-WTG-02**: `MopScorer.scoreWtg()` SHALL return 0 when `MopData` or `StaticGuiModel` is absent, when WTG data is absent, when the widget has no matching WTG transition, or when the injected `ape.mopWeightWtg` is 0; with `WTG` absent from the plan (`MOP` off, or `ape.mopWeightWtg <= 0`, which the POSITIVE rule reads as off and records as inert, design D-B22) its caller `WtgPass` is not constructed. The weight SHALL NOT be read from `Config`. (See also INV-MOP-06.)
- **INV-WTG-03**: Window IDs from `transitions[]` SHALL be resolved to activity names via the `windows[].id` → `windows[].name` mapping built during the existing Pass 2 (windows parsing).
- **INV-WTG-04**: The WTG convenience view SHALL be keyed by the base source activity (window name truncated at the first `#`), and each stored `WtgTransition.targetActivity` SHALL be the base target activity.
- **INV-WTG-05**: Every consumer of the WTG view — `NavFrontierPass`, `WtgPass` with `MopScorer.scoreWtg`, `MopFrontierPass` and the OPTIONSMENU-gateway precompute — SHALL query `StaticGuiModel.wtgTransitions` by base activity; no consumer SHALL key by a `#`-suffixed window name.
- **INV-WTG-08**: `NAV_FRONTIER` SHALL NOT depend on `MOP`, and `NavFrontierPass` SHALL read no `MopData`: its term for an action SHALL be identical in a cell with and without `MOP` for the same screen and model.

## Requirements

### Requirement: MopData — WTG Parsing (Pass 3)

The WTG view SHALL be served by `StaticGuiModel` (`hasWtgData()`, `getWtgTransitions(activity)`), built by `StaticArtifactLoader` from the artifact's wire `wtg` map (`sourceBaseActivity → [{widget, target}]`, `mop-guidance` "MopData — Static Analysis JSON Loader" item 8). `MopData` holds no WTG view; MOP consumers query `StaticGuiModel`. The resolution of window ids to window names, the reduction of each to its base activity — the window name truncated at the first `#` (so `MainActivity#OptionsMenu` becomes `MainActivity`) — the restriction to `click` events and the deduplication are performed **host-side by the generator** (INV-WTG-01, INV-WTG-03, INV-WTG-04); the jar parses no `transitions[]`. The view SHALL be keyed by the base source activity, and each stored `WtgTransition.targetActivity` SHALL be the base target activity (INV-WTG-04). Every consumer of this view SHALL query it by base activity (INV-WTG-05).

This base-activity keying aligns the view with its consumers: `NavFrontierPass` and `WtgPass` query `getWtgTransitions(newState.getActivity())` with a base activity, and `MopScorer.scoreWtg` tests `MopData.activityHasMop(targetActivity)` against the selected MOP-activity set, which is keyed by base activity.

The OPTIONSMENU-gateway precompute (`MopData.precomputeMopOptionsMenus`, which backs `activityHasMopOptionsMenu` and `MopScorer.scoreOpenMenu`) is a MOP consumer of this same view and SHALL query `StaticGuiModel` by the base activity. Because the source key is collapsed to the base activity, the gateway test ("does a menu item navigate to a MOP activity?") is widened to "does any base-activity click edge navigate to a MOP activity?". This is a deliberate over-approximation: it never misses a real menu gateway and, at worst, applies the open-menu boost on an activity whose MOP path is also reachable by a non-menu widget.

#### Scenario: Parse click transitions
- **WHEN** the producer's document contains a transition `{sourceId: 1231, targetId: 1170, events: [{type: "click", widgetName: "settings", widgetClass: "android.view.MenuItem"}]}`, window 1231 is "com.example.MainActivity" and window 1170 is "com.example.SettingsActivity", so the artifact carries `wtg["com.example.MainActivity"] = [{widget: "settings", target: "com.example.SettingsActivity"}]`
- **THEN** `StaticGuiModel.getWtgTransitions("com.example.MainActivity")` SHALL contain a WtgTransition with widgetName="settings", targetActivity="com.example.SettingsActivity"

#### Scenario: Menu-sourced transition keyed by base activity
- **WHEN** the producer's transition from window "com.example.MainActivity#OptionsMenu" via `click` on `menu_item_message_digest` to "com.example.MessageDigestActivity" is derived into the artifact
- **THEN** `StaticGuiModel.getWtgTransitions("com.example.MainActivity")` SHALL contain a WtgTransition with widgetName="menu_item_message_digest", targetActivity="com.example.MessageDigestActivity"
- **AND** `getWtgTransitions("com.example.MainActivity#OptionsMenu")` SHALL return an empty list (the suffixed key is not used)

#### Scenario: Suffixed target reduced to base for MOP lookup
- **WHEN** a transition's target window name is "com.example.CipherActivity#Dialog" and "com.example.CipherActivity" has MOP-reachable methods
- **THEN** the stored `WtgTransition.targetActivity` SHALL be "com.example.CipherActivity"
- **AND** in a cell with `MOP`, `MopScorer.scoreWtg` evaluating that transition SHALL find `activityHasMop("com.example.CipherActivity") == true`

#### Scenario: OPTIONSMENU gateway recomputed under base-activity keying
- **WHEN** window "com.example.MainActivity#OptionsMenu" has a `click` transition whose target reduces to base activity "com.example.CipherActivity" and that activity has MOP-reachable methods
- **THEN** `precomputeMopOptionsMenus` SHALL query `StaticGuiModel` with the base source activity "com.example.MainActivity" and qualify it as a MOP-bearing OPTIONSMENU gateway
- **AND** `activityHasMopOptionsMenu("com.example.MainActivity")` SHALL return true

#### Scenario: Ignore implicit events
- **WHEN** a producer transition has events `[{type: "implicit_home_event", ...}, {type: "click", widgetName: "search", ...}]`
- **THEN** only the "click" event SHALL reach the artifact and `StaticGuiModel`

#### Scenario: No transitions section
- **WHEN** the artifact's `wtg` map is empty
- **THEN** `StaticGuiModel.getWtgTransitions()` SHALL return an empty list for any activity and `hasWtgData()` SHALL be `false`
- **AND** no exception SHALL be thrown, and `NavFrontierPass`, `WtgPass` and `MopFrontierPass` SHALL not be constructed

### Requirement: MopScorer — WTG Priority Boost

`MopScorer.scoreWtg`, whose parameters carry the activity, the short widget id, the `StaticGuiModel` WTG view, the `MopData` and the injected weight, SHALL return a priority boost when the widget matches a WTG transition leading to a MOP-reachable activity. The WTG view is read from `StaticGuiModel` ("MopData — WTG Parsing (Pass 3)"); the MOP test from `MopData`. The match is performed by comparing the action's `GUITreeNode.getResourceID()` short form against `WtgTransition.widgetName`. If the target activity is in the selected MOP-activity set (`data.activityHasMop(targetActivity) == true`), the boost is `weight`, the plan key `ape.mopWeightWtg` (default 200) of the MOP member `WTG`, injected by `WtgPass` from `ScoringParams` (`mop-guidance` "Config Flag for WTG Weight"); it is never read from `Config`. The only caller is `WtgPass`, a MOP scorer constructed only when `WTG` is in the plan and `StaticGuiModel.hasWtgData()` holds (`mop-guidance` "WTG Scoring Pass in adjustActionsByGUITree"), so the term exists only in cells with `MOP` (INV-WTG-02).

#### Scenario: Widget leads to MOP activity
- **WHEN** in a cell with `MOP` `scoreWtg` is called for activity "com.example.MainActivity" and widget "settings" with weight 200
- **AND** `StaticGuiModel`'s WTG shows "settings" click leads to "com.example.SettingsActivity"
- **AND** "com.example.SettingsActivity" is in the selected MOP-activity set
- **THEN** the returned boost SHALL be the injected `ape.mopWeightWtg` (default: 200)

#### Scenario: Widget leads to non-MOP activity
- **WHEN** the target activity is not in the selected MOP-activity set
- **THEN** the returned boost SHALL be 0

#### Scenario: No WTG match for widget
- **WHEN** the widget has no matching WTG transition in `StaticGuiModel`
- **THEN** the returned boost SHALL be 0

### Requirement: WTG Frontier Boost for Unvisited Activities

The frontier SHALL be the STATIC member feature `NAV_FRONTIER`, activated by `ape.frontierBoostWeight` (POSITIVE, neutral 0, jar default 200), which depends on `STATIC` and **not** on `MOP` (D-A1). `Feature.FRONTIER` and `FrontierPass` are renamed `NAV_FRONTIER` and `NavFrontierPass` (P3, no alias). When `NAV_FRONTIER` is in the plan and `StaticGuiModel.hasWtgData()` holds, `NavFrontierPass` SHALL add `frontierBoostWeight` to the priority of every target-requiring, valid, resolved action whose matched WTG transition (`StaticGuiModel.getWtgTransitions(activity)`, the same resource-id match as `WtgPass`) targets an activity that is currently unvisited — `Graph.getActivityNode(targetActivity) == null` at scoring time. It SHALL read no `MopData` and SHALL not test MOP reachability (INV-WTG-08).

Under rule R1 (`scoring-pipeline`) the pass computes its term without reading priority or another scorer's field, writes it to its own field `navFrontierBoost`, and adds it to the priority (`action.setPriority(action.getPriority() + frontierBoostWeight)` — the steering mechanism, since `navFrontierBoost` is telemetry-only and never enters `getPriority()`). The term is visible in the step record as `dec.navf` (`event-sink`) and its causal effect at a weighted pick as `dec.cfc.navf`. It SHALL NOT write `wtgBoost`: `wtgBoost` is written only by the MOP pass `WtgPass`. The frontier term stacks with the MOP-reach WTG term when the same transition target is both MOP-reachable and unvisited, in a separate field.

The unvisited check SHALL be evaluated live on every scoring pass: once the target activity has been visited, subsequent passes SHALL NOT apply the frontier term for it. When at least one action receives the frontier term in a pass, the agent SHALL log `[APE-RV] Frontier boost: state=<activity>#<stateKey>, boosted=<n>/<total>, maxBoost=<b>`.

**One producer per field.** Before this change both passes accumulated into `wtgBoost`, and a `dec.wtgsrc` stamp (`wtg` | `frontier` | `both`) was needed to tell them apart at equal weights. With `navFrontierBoost` the two producers are separate fields, so `dec.wtgsrc` and `ModelAction`'s stamp SHALL NOT exist (P3).

- **INV-WTG-06**: The frontier term SHALL only ever be applied to actions whose WTG transition target has no `ActivityNode` in the model at scoring time, and SHALL be added to the priority (the steering mechanism) AND written to `navFrontierBoost`. Because `navFrontierBoost` is telemetry-only, writing the field alone would leave priority — and therefore roulette weight and the X1 scorer sum's effect — unchanged; the priority increment is mandatory for the term to steer.
- **INV-WTG-07**: With `NAV_FRONTIER` absent from the plan (`ape.frontierBoostWeight = 0`, or `STATIC` off), no action SHALL receive the frontier term and `navFrontierBoost` SHALL be 0 everywhere. With both the MOP-reach and the frontier terms applicable, the action's priority gain SHALL be `mopWeightWtg + frontierBoostWeight`, plus `mopFrontierWeight` when `MOP_FRONTIER` also applies (`scoring-pipeline` "MopFrontierPass"), with `wtgBoost == mopWeightWtg`, `navFrontierBoost == frontierBoostWeight` and, in that case, `mopFrontierBoost == mopFrontierWeight`.

#### Scenario: widget leading to an unvisited activity is boosted
- **WHEN** in the `as` cell widget W's WTG transition targets `com.x.DetailActivity`, which has no `ActivityNode` in the graph, and `ape.frontierBoostWeight = 200`
- **THEN** W's action priority SHALL be increased by 200 and its `navFrontierBoost` SHALL be 200
- **AND** one `[APE-RV] Frontier boost:` line SHALL be logged for the pass

#### Scenario: boost disappears after the target is visited
- **WHEN** `com.x.DetailActivity` gains an `ActivityNode` (it was visited) and the same state is re-scored
- **THEN** W's action SHALL NOT receive the frontier term in that pass

#### Scenario: stacking with the MOP-reach WTG boost
- **WHEN** in the `as_mop` cell the transition target is both MOP-bearing (`activityHasMop == true`) and unvisited, with `mopWeightWtg = 200`, `frontierBoostWeight = 200` and `mopFrontierWeight = 200` (the cell's values)
- **THEN** the action SHALL receive the three terms (+600 priority in total), with `wtgBoost` 200, `navFrontierBoost` 200 and `mopFrontierBoost` 200
- **AND** the step record SHALL carry `dec.wtg:200`, `dec.navf:200` and `dec.mopf:200`, and no `dec.wtgsrc`

#### Scenario: the two producers are distinguishable at equal weights
- **WHEN** `mopWeightWtg = 200` and `frontierBoostWeight = 200`, and on one step only the frontier term applies while on another only the MOP-reach term applies
- **THEN** the first record SHALL carry `dec.navf:200` and no `dec.wtg`, and the second `dec.wtg:200` and no `dec.navf`
- **AND** the producers SHALL be told apart by their fields, so no stamp is needed

#### Scenario: disabled
- **WHEN** `ape.frontierBoostWeight = 0`, or the cell has no `STATIC` (`base`, `mop`)
- **THEN** `NAV_FRONTIER` SHALL be absent from the plan, `NavFrontierPass` SHALL not be constructed, and no frontier log line SHALL be written
- **AND** in the `mop` cell the MOP-reach `WtgPass` SHALL still run, because it is a MOP member
