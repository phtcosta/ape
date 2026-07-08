## Purpose

The static analysis JSON produced by rv-android contains three sections: `windows[]`, `reachability[]`, and `transitions[]`. APE-RV's `MopData` currently parses only `windows[]` and `reachability[]` to build a widget→MOP reachability map. The `transitions[]` section — a Window Transition Graph (WTG) mapping which click events on which widgets navigate from one window to another — is completely ignored.

The rv-agent project uses BFS on this WTG to find navigation paths from the current activity to activities containing MOP-reachable methods. This change ports the concept to APE-RV by adding a third parsing pass to `MopData` and a WTG scoring method to `MopScorer`. The WTG score boosts the priority of widgets whose click, according to the static WTG, leads to an activity with MOP-reachable methods. This turns APE-RV's MOP guidance from purely reactive (boost widgets in the current state) to proactive (navigate toward MOP-rich activities).

The WTG data in the JSON has the structure: `transitions[].sourceId` and `targetId` reference `windows[].id`, and `transitions[].events[]` describe which widgets trigger each transition (type, widgetName, widgetClass). Only `click` type events are considered — implicit events (home, rotate, power, back) are excluded because they are not controllable exploration actions. MenuItem clicks ARE included: empirical data shows they represent 24-83% of click transitions in real apps (cryptoapp: 4/17, ApkTrack: 5/6). MenuItem `widgetName` values (e.g., `menu_item_cipher`) match the `shortId` extracted from `GUITreeNode.getResourceID()` at runtime.

## Data Structures

### WtgTransition

A simple data class representing one click event that navigates from one activity to another:

```java
public class WtgTransition {
    public final String widgetName;      // resource name (e.g., "menu_item_cipher", "buttonCipher")
    public final String widgetClass;     // widget class (e.g., "android.view.MenuItem", "android.widget.Button")
    public final String targetActivity;  // resolved activity name (e.g., "com.example.CipherActivity")
}
```

Stored in `MopData` as: `Map<String, List<WtgTransition>>` keyed by source activity name. Populated during Pass 3.

## Data Contracts

### Input
- `transitions[]: JSON array` — WTG transitions from static analysis JSON (source: `ape.mopDataPath` file on device)
  - `sourceId: int` — window ID of the source screen
  - `targetId: int` — window ID of the target screen
  - `events[]: JSON array` — triggering events
    - `type: String` — event type ("click", "implicit_home_event", etc.)
    - `widgetName: String` — resource name of the triggering widget (may be empty)
    - `widgetClass: String` — class name of the widget

### Output
- `wtgScore: int` — priority boost for a widget leading to a MOP-reachable activity (consumer: `adjustActionsByGUITree()`)
- `getWtgTransitions(activityName): List<WtgTransition>` — transitions from the given activity (consumer: SATA trivial activity selection)

### Side-Effects
- **[Memory]**: WTG graph structures stored in MopData alongside existing widget/reachability maps.

### Error
- None. When `transitions[]` is absent or empty, WTG scoring returns 0 for all widgets.

## Invariants

- **INV-WTG-01**: Only events with `type` equal to `"click"` SHALL be parsed from `transitions[].events[]`. Implicit events (implicit_home_event, implicit_back_event, implicit_rotate_event, implicit_power_event, implicit_on_activity_result, implicit_on_activity_newintent) SHALL be ignored.
- **INV-WTG-02**: `MopScorer.scoreWtg()` SHALL return 0 when `MopData` is null, when WTG data is absent, when the widget has no matching WTG transition, or when `Config.mopWeightWtg` is 0. (See also INV-MOP-06 for the Config.mopWeightWtg=0 condition.)
- **INV-WTG-03**: Window IDs from `transitions[]` SHALL be resolved to activity names via the `windows[].id` → `windows[].name` mapping built during the existing Pass 2 (windows parsing).
- **INV-WTG-04**: The WTG convenience view SHALL be keyed by the base source activity (window name truncated at the first `#`), and each stored `WtgTransition.targetActivity` SHALL be the base target activity.
- **INV-WTG-05**: Every consumer of the WTG view — the runtime WTG pass, `MopScorer.scoreWtg`, and the OPTIONSMENU-gateway precompute — SHALL query it by base activity; no consumer SHALL key by a `#`-suffixed window name.
## Requirements
### Requirement: MopData — WTG Parsing (Pass 3)

`MopData.load()` SHALL include a third parsing pass that reads the `transitions[]` array from the static analysis JSON. For each transition, it SHALL resolve `sourceId` and `targetId` to window names using the window ID→name map from Pass 2, and SHALL reduce each to its base activity — the window name truncated at the first `#` (so `MainActivity#OptionsMenu` becomes `MainActivity`). Only `click` type events SHALL be stored. The convenience view SHALL be keyed by the base source activity, and each stored `WtgTransition.targetActivity` SHALL be the base target activity (INV-WTG-04). Every consumer of this view — the runtime WTG pass, `MopScorer.scoreWtg`, and the OPTIONSMENU-gateway precompute — SHALL query it by base activity; no consumer SHALL key by a `#`-suffixed window name (INV-WTG-05).

This base-activity keying aligns the WTG view with its consumers: the runtime WTG pass queries `getWtgTransitions(newState.getActivity())` with a base activity, and `MopScorer.scoreWtg` tests `activityHasMop(targetActivity)` against `mopActivities`, which is keyed by base activity. Previously the view was keyed by the full window name, so menu- and fragment-sourced transitions (e.g. from `MainActivity#OptionsMenu`) were stored under a key the consumer never queried and were silently dropped; their targets, if `#`-suffixed, also failed the `activityHasMop` test.

The OPTIONSMENU-gateway precompute (`MopData.precomputeMopOptionsMenus`, which backs `activityHasMopOptionsMenu` and `MopScorer.scoreOpenMenu`) is a second consumer of this same view and SHALL query it by the base activity. Because the source key is now collapsed to the base activity, the gateway test ("does a menu item navigate to a MOP activity?") is widened to "does any base-activity click edge navigate to a MOP activity?". This is a deliberate over-approximation: it never misses a real menu gateway and, at worst, applies the open-menu boost on an activity whose MOP path is also reachable by a non-menu widget. It SHALL NOT query the `#`-suffixed window name, which no longer exists as a key.

#### Scenario: Parse click transitions
- **WHEN** the JSON contains a transition `{sourceId: 1231, targetId: 1170, events: [{type: "click", widgetName: "settings", widgetClass: "android.view.MenuItem"}]}` and window 1231 is "com.example.MainActivity" and window 1170 is "com.example.SettingsActivity"
- **THEN** `getWtgTransitions("com.example.MainActivity")` SHALL contain a WtgTransition with widgetName="settings", targetActivity="com.example.SettingsActivity"

#### Scenario: Menu-sourced transition keyed by base activity
- **WHEN** the JSON contains a transition `{sourceId: 1382, targetId: 1397, events: [{type: "click", widgetName: "menu_item_message_digest", widgetClass: "android.view.MenuItem"}]}` and window 1382 is "com.example.MainActivity#OptionsMenu" and window 1397 is "com.example.MessageDigestActivity"
- **THEN** `getWtgTransitions("com.example.MainActivity")` SHALL contain a WtgTransition with widgetName="menu_item_message_digest", targetActivity="com.example.MessageDigestActivity"
- **AND** `getWtgTransitions("com.example.MainActivity#OptionsMenu")` SHALL return an empty list (the suffixed key is no longer used)

#### Scenario: Suffixed target reduced to base for MOP lookup
- **WHEN** a transition's target window name is "com.example.CipherActivity#Dialog" and "com.example.CipherActivity" has MOP-reachable methods
- **THEN** the stored `WtgTransition.targetActivity` SHALL be "com.example.CipherActivity"
- **AND** `MopScorer.scoreWtg` evaluating that transition SHALL find `activityHasMop("com.example.CipherActivity") == true`

#### Scenario: OPTIONSMENU gateway recomputed under base-activity keying
- **WHEN** window "com.example.MainActivity#OptionsMenu" has a `click` transition whose target reduces to base activity "com.example.CipherActivity" and that activity has MOP-reachable methods
- **THEN** `precomputeMopOptionsMenus` SHALL query the base source activity "com.example.MainActivity" and qualify it as a MOP-bearing OPTIONSMENU gateway
- **AND** `activityHasMopOptionsMenu("com.example.MainActivity")` SHALL return true

#### Scenario: Ignore implicit events
- **WHEN** a transition has events `[{type: "implicit_home_event", ...}, {type: "click", widgetName: "search", ...}]`
- **THEN** only the "click" event SHALL be stored

#### Scenario: No transitions section
- **WHEN** the JSON does not contain a `transitions` key
- **THEN** `getWtgTransitions()` SHALL return an empty list for any activity
- **AND** no exception SHALL be thrown

### Requirement: MopScorer — WTG Priority Boost

`MopScorer.scoreWtg(String activity, String shortWidgetId, MopData data)` SHALL return a priority boost when the widget matches a WTG transition leading to a MOP-reachable activity. The match is performed by comparing the action's `GUITreeNode.getResourceID()` short form against `WtgTransition.widgetName`. If the target activity has MOP methods (`data.activityHasMop(targetActivity) == true` — this method already exists in `MopData` from the mop-guidance spec, INV-MOP-04), the boost is `Config.mopWeightWtg`.

#### Scenario: Widget leads to MOP activity
- **WHEN** `scoreWtg("com.example.MainActivity", "settings", data)` is called
- **AND** WTG shows "settings" click leads to "com.example.SettingsActivity"
- **AND** "com.example.SettingsActivity" has MOP methods
- **THEN** the returned boost SHALL be `Config.mopWeightWtg` (default: 200)

#### Scenario: Widget leads to non-MOP activity
- **WHEN** the target activity has no MOP methods
- **THEN** the returned boost SHALL be 0

#### Scenario: No WTG match for widget
- **WHEN** the widget has no matching WTG transition
- **THEN** the returned boost SHALL be 0

### Requirement: WTG Frontier Boost for Unvisited Activities

When `ape.frontierBoostWeight > 0` and WTG data is present, the WTG scoring pass in `StatefulAgent.adjustActionsByGUITree` SHALL add `Config.frontierBoostWeight` to the priority of every action whose matched WTG transition (same resource-id matching as the existing MOP-reach boost) targets an activity that is currently unvisited — `Graph.getActivityNode(targetActivity) == null` at scoring time. The frontier term SHALL be applied as a `setPriority` increment (`action.setPriority(action.getPriority() + frontierBoostWeight)` — this is the steering mechanism, since `wtgBoost` is a telemetry-only field that never enters `getPriority()`) AND recorded in the action's existing `wtgBoost` field via read-modify-write accumulation (`action.setWtgBoost(action.getWtgBoost() + frontierBoostWeight)`), the same field the MOP-reach WTG boost uses — mirroring the existing WTG-MOP pass, which does both a `setPriority` increment and a `setWtgBoost` write (`StatefulAgent.java:1457-1458`). Recording it in `wtgBoost` (rather than only bumping priority) is what makes the frontier gain detectable downstream and visible in the `[APE-STEP] ... wtg=` telemetry field. Because Lever A needs the transition target (which `MopScorer.scoreWtg`'s `int` return hides, and which only fires when MOP-reachable), the frontier term SHALL NOT ride `scoreWtg`; it SHALL use its own `MopData.getWtgTransitions(activity)` lookup with the same `widgetName`/resource-id match, checking `Graph.getActivityNode(WtgTransition.targetActivity) == null`. The frontier term is independent of MOP reachability and SHALL stack with the existing `Config.mopWeightWtg` boost when the same transition target is both MOP-reachable and unvisited.

The unvisited check SHALL be evaluated live on every scoring pass: once the target activity has been visited, subsequent passes SHALL NOT apply the frontier term for it. With `ape.frontierBoostWeight = 0` the pass SHALL be byte-identical to the frontier term being absent. When at least one action receives the frontier term in a pass, the agent SHALL log `[APE-RV] Frontier boost: state=<activity>#<stateKey>, boosted=<n>/<total>, maxBoost=<b>`.

- **INV-WTG-06**: The frontier term SHALL only ever be applied to actions whose WTG transition target has no `ActivityNode` in the model at scoring time, and SHALL be applied as a `setPriority` increment (the steering mechanism) AND recorded in the action's `wtgBoost` field via read-modify-write accumulation (`setWtgBoost(getWtgBoost() + frontierBoostWeight)`), mirroring the existing WTG-MOP pass. Because `wtgBoost` is telemetry-only (never read by `getPriority()`), applying the term through `wtgBoost` alone would leave priority — and therefore roulette weight — unchanged; the `setPriority` increment is mandatory for the boost to steer.
- **INV-WTG-07**: With `ape.frontierBoostWeight = 0`, scoring SHALL be identical to the pre-change WTG pass. With both boosts applicable, the action's priority gain from the WTG pass SHALL be `mopWeightWtg + frontierBoostWeight` (two `setPriority` increments), and its `wtgBoost` telemetry field SHALL likewise equal `mopWeightWtg + frontierBoostWeight` (accumulated by read-modify-write, not overwritten).

#### Scenario: widget leading to an unvisited activity is boosted
- **WHEN** widget W's WTG transition targets `com.x.DetailActivity`, which has no `ActivityNode` in the graph, and `ape.frontierBoostWeight = 200`
- **THEN** W's action priority SHALL be increased by 200 in the scoring pass
- **AND** one `[APE-RV] Frontier boost:` line SHALL be logged for the pass

#### Scenario: boost disappears after the target is visited
- **WHEN** `com.x.DetailActivity` gains an `ActivityNode` (it was visited) and the same state is re-scored
- **THEN** W's action SHALL NOT receive the frontier term in that pass

#### Scenario: stacking with the MOP-reach WTG boost
- **WHEN** the transition target is both MOP-reachable (`activityHasMop == true`) and unvisited, with `mopWeightWtg = 200` (the default) and `frontierBoostWeight = 200`
- **THEN** the action SHALL receive both terms (+400 total from the WTG pass, accumulated into `wtgBoost`)

#### Scenario: disabled
- **WHEN** `ape.frontierBoostWeight = 0`
- **THEN** the WTG pass SHALL behave exactly as specified before this change, with no frontier log lines

