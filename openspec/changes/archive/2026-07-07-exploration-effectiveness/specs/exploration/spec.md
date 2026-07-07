## ADDED Requirements

### Requirement: Per-Step Debug Artifact Defaults

`ape.takeScreenshotForEveryStep` and `ape.saveGUITreeToXmlEveryStep` SHALL default to `false`. Both are debug aids: the experiment pipeline consumes neither (coverage comes from logcat, the trace from stdout; the aperv-tool pulls no per-step PNG/XML), and the LLM path captures its own screenshots on demand via `ScreenshotCapture`, independent of these flags. Per-step PNG + XML writes are pure I/O overhead on the exploration loop (estimated 20-40% of step throughput on an emulator). Debug runs re-enable either flag via `ape.properties`.

#### Scenario: defaults are off
- **WHEN** `ape.properties` contains neither key
- **THEN** `Config.takeScreenshotForEveryStep` SHALL be `false`
- **AND** `Config.saveGUITreeToXmlEveryStep` SHALL be `false`
- **AND** no per-step PNG or XML SHALL be written

#### Scenario: debug re-enable
- **WHEN** `ape.properties` contains `ape.saveGUITreeToXmlEveryStep=true`
- **THEN** a `step-N.xml` SHALL be written each step, as before

#### Scenario: LLM screenshots unaffected
- **WHEN** `ape.takeScreenshotForEveryStep=false` and the LLM routes a step
- **THEN** `ScreenshotCapture` SHALL still capture its on-demand screenshot for the prompt

### Requirement: Fuzz Gesture Emission

Every branch of the fuzzer's default gesture switch (drag, pinch/zoom, click) SHALL append exactly one event to the output list. `generatePinchOrZoomEvent` SHALL end with `events.add(new ApePinchOrZoomEvent(points))` (previously the gesture was built and discarded — the `events` parameter was never used, so ~1/3 of default-branch fuzz iterations emitted nothing).

Before appending, `generatePinchOrZoomEvent` SHALL size the `points` array to exactly the number of entries it writes and emit no `null` entry. The current allocation `new PointF[4 + count << 1]` evaluates by Java precedence to `(4 + count) << 1` = `8 + 2·count`, while only `6 + 2·count` entries are written — leaving two trailing `null` slots that the constructor would dereference. The `ApePinchOrZoomEvent` constructor SHALL validate the array length (reject arrays shorter than 6 entries — 1 count slot + 1 duration slot + 2 coordinates × (count+1) pointers with count ≥ 0 gives the minimum valid payload of 6) BEFORE dereferencing any element, so a malformed array is rejected rather than throwing `NullPointerException`.

#### Scenario: pinch/zoom branch emits
- **WHEN** the fuzzer's gesture switch selects the pinch/zoom branch
- **THEN** exactly one `ApePinchOrZoomEvent` SHALL be appended to the event list
- **AND** its points array SHALL have at least 6 entries

#### Scenario: emitted points array has no null entries
- **WHEN** the pinch/zoom branch builds its `points` array for any `count ≥ 0`
- **THEN** the array passed to `ApePinchOrZoomEvent` SHALL have length equal to the number of written entries
- **AND** it SHALL contain no `null` element and construction SHALL NOT throw `NullPointerException`

#### Scenario: short payload rejected
- **WHEN** an `ApePinchOrZoomEvent` is constructed with 5 points
- **THEN** the constructor SHALL reject it before dereferencing any element

### Requirement: Off-Screen Action Handling

When `generateClickEventAt` finds that the target node's bounds do not intersect the visible screen (`getVisibleBounds` returns null), it SHALL NOT emit any touch event and SHALL log one `[APE-RV] off-screen action dropped: <action>` line. It SHALL NOT substitute the display bounds and click the screen center: that behavior executed an unrelated click while the model credited the original action, creating false edges (260 occurrences measured across 17/1513 baseline runs). The invalid-bounds branch (`!bounds.contains(p)`) keeps its no-event behavior and gains the same log line. Visit/coverage crediting of the dropped action is unchanged in this change (the markVisited-before-event-generation reorder is a separate, deferred item); the log line makes the wasted-step frequency measurable.

#### Scenario: off-screen node produces no event
- **WHEN** a MODEL_CLICK resolves to a node whose bounds do not intersect the visible screen
- **THEN** no touch event SHALL be enqueued
- **AND** one `[APE-RV] off-screen action dropped` line SHALL be emitted
- **AND** no click SHALL be delivered to the screen center

#### Scenario: on-screen node unchanged
- **WHEN** the node's bounds intersect the visible screen
- **THEN** click generation SHALL be identical to the previous implementation

## MODIFIED Requirements

### Requirement: Configuration Loading

All numeric and boolean tuning parameters MUST be loaded from `ape.properties` at process startup. The `Config` class in `com.android.commands.monkey.ape.utils.Config` loads properties from `/data/local/tmp/ape.properties` first, then overlays `/sdcard/ape.properties` if present. System properties (set via `-D` on the command line) are also honoured. All `Config` fields are `public static final` and are resolved once at class-loading time; they MUST NOT change for the lifetime of the process. The table below lists the configuration keys relevant to the exploration engine with their types and defaults:

| Key | Type | Default | Description |
|---|---|---|---|
| `ape.graphStableRestartThreshold` | int | 100 | Steps without graph growth before forced restart |
| `ape.stateStableRestartThreshold` | int | 50 | Steps in same state before forced restart |
| `ape.activityStableRestartThreshold` | int | `Integer.MAX_VALUE` | Steps in same activity before forced restart |
| `ape.evolveModel` | boolean | true | Enable CEGAR naming refinement |
| `ape.doFuzzing` | boolean | true | Enable random fuzzing injection |
| `ape.fuzzingRate` | double | 0.02 | Probability of fuzzing per step |
| `ape.fuzzingActivityVisitThreshold` | int | 10 | Minimum activity visits before fuzzing activates |
| `ape.defaultEpsilon` | double | 0.05 | Epsilon for SataAgent epsilon-greedy |
| `ape.saveObjModel` | boolean | true | Save serialised graph on termination |
| `ape.saveDotGraph` | boolean | false | Save Graphviz DOT graph on termination |
| `ape.saveVisGraph` | boolean | true | Save vis.js JSON visualisation on termination |
| `ape.takeScreenshot` | boolean | true | Save screenshots |
| `ape.saveGUITreeToXmlEveryStep` | boolean | false | Save GUITree XML per step (default flipped to `false` by this change — see "Per-Step Debug Artifact Defaults" / INV-EXPL-17) |
| `ape.defaultGUIThrottle` | long (ms) | 200 | Delay between injected events |
| `ape.trivialActivityRankThreshold` | int | 3 | Minimum activity count before trivial-activity logic activates |

#### Scenario: Property file present on device

- **WHEN** `/data/local/tmp/ape.properties` contains `ape.graphStableRestartThreshold=200`
- **THEN** `Config.graphStableRestartThreshold` SHALL equal `200` for the entire session
- **AND** the default value of `100` SHALL NOT be used

#### Scenario: No property file present

- **WHEN** neither `/data/local/tmp/ape.properties` nor `/sdcard/ape.properties` exists
- **THEN** all `Config` fields SHALL take their hardcoded default values as listed above

## Invariants

- **INV-EXPL-17**: Per-step debug artifacts (PNG/XML) SHALL be written only when explicitly enabled via `ape.properties`.
- **INV-EXPL-18**: Every fuzz gesture branch SHALL emit exactly one event per invocation.
- **INV-EXPL-19**: No touch event SHALL ever be delivered to coordinates derived from bounds other than the resolved node's own bounds.
