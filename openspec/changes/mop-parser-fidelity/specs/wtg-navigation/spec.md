## MODIFIED Requirements

### Requirement: MopData — WTG Parsing (Pass 3)

`MopData.load()` SHALL include a third parsing pass that reads the `transitions[]` array from the static analysis JSON. For each transition, it SHALL resolve `sourceId` and `targetId` to window names using the window ID→name map from Pass 2, and SHALL reduce each to its base activity — the window name truncated at the first `#` (so `MainActivity#OptionsMenu` becomes `MainActivity`). Only `click` type events SHALL be stored. The convenience view SHALL be keyed by the base source activity, and each stored `WtgTransition.targetActivity` SHALL be the base target activity (INV-WTG-04).

This base-activity keying aligns the WTG view with its consumers: the runtime WTG pass queries `getWtgTransitions(newState.getActivity())` with a base activity, and `MopScorer.scoreWtg` tests `activityHasMop(targetActivity)` against `mopActivities`, which is keyed by base activity. Previously the view was keyed by the full window name, so menu- and fragment-sourced transitions (e.g. from `MainActivity#OptionsMenu`) were stored under a key the consumer never queried and were silently dropped; their targets, if `#`-suffixed, also failed the `activityHasMop` test.

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

#### Scenario: Ignore implicit events
- **WHEN** a transition has events `[{type: "implicit_home_event", ...}, {type: "click", widgetName: "search", ...}]`
- **THEN** only the "click" event SHALL be stored

#### Scenario: No transitions section
- **WHEN** the JSON does not contain a `transitions` key
- **THEN** `getWtgTransitions()` SHALL return an empty list for any activity
- **AND** no exception SHALL be thrown
