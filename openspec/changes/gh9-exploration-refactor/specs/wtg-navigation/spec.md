## Purpose

The static analysis JSON produced by rv-android contains three sections: `windows[]`, `reachability[]`, and `transitions[]`. APE-RV's `MopData` currently parses only `windows[]` and `reachability[]` to build a widget→MOP reachability map. The `transitions[]` section — a Window Transition Graph (WTG) mapping which click events on which widgets navigate from one window to another — is completely ignored.

The rv-agent project uses BFS on this WTG to find navigation paths from the current activity to activities containing MOP-reachable methods. This change ports the concept to APE-RV by adding a third parsing pass to `MopData` and a WTG scoring method to `MopScorer`. The WTG score boosts the priority of widgets whose click, according to the static WTG, leads to an activity with MOP-reachable methods. This turns APE-RV's MOP guidance from purely reactive (boost widgets in the current state) to proactive (navigate toward MOP-rich activities).

The WTG data in the JSON has the structure: `transitions[].sourceId` and `targetId` reference `windows[].id`, and `transitions[].events[]` describe which widgets trigger each transition (type, widgetName, widgetClass). Only `click` type events are considered — implicit events (home, rotate, power, back) are excluded because they are not controllable exploration actions. MenuItem clicks ARE included: empirical data shows they represent 24-83% of click transitions in real apps (cryptoapp: 4/17, ApkTrack: 5/6). MenuItem `widgetName` values (e.g., `menu_item_cipher`) match the `shortId` extracted from `GUITreeNode.getResourceID()` at runtime.

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

## ADDED Requirements

### Requirement: MopData — WTG Parsing (Pass 3)

`MopData.load()` SHALL include a third parsing pass that reads the `transitions[]` array from the static analysis JSON. For each transition, it SHALL resolve `sourceId` and `targetId` to activity names using the window ID→name map from Pass 2. Only `click` type events SHALL be stored. The result is a map: `activityName → List<WtgTransition>` where `WtgTransition` contains `widgetName`, `widgetClass`, and `targetActivity`.

#### Scenario: Parse click transitions
- **WHEN** the JSON contains a transition `{sourceId: 1231, targetId: 1170, events: [{type: "click", widgetName: "settings", widgetClass: "android.view.MenuItem"}]}` and window 1231 is "com.example.MainActivity" and window 1170 is "com.example.SettingsActivity"
- **THEN** `getWtgTransitions("com.example.MainActivity")` SHALL contain a WtgTransition with widgetName="settings", targetActivity="com.example.SettingsActivity"

#### Scenario: Parse MenuItem click transitions
- **WHEN** the JSON contains a transition `{sourceId: 1382, targetId: 1397, events: [{type: "click", widgetName: "menu_item_message_digest", widgetClass: "android.view.MenuItem"}]}` and window 1382 is "com.example.MainActivity#OptionsMenu" and window 1397 is "com.example.MessageDigestActivity"
- **THEN** `getWtgTransitions("com.example.MainActivity#OptionsMenu")` SHALL contain a WtgTransition with widgetName="menu_item_message_digest", targetActivity="com.example.MessageDigestActivity"

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
