# Delta: LLM Prompt (llm-coordinate-single-base)

## Purpose

The prompt promises the model a single coordinate space. The system message says every coordinate is in [0,1000). The element list's `@(x,y)` is a widget's centre in that space. The action history uses the same notation, and the model's answer is read back in it. For that promise to hold, three things must agree on what 1000 means: the image the model sees, the numbers the prompt shows, and the conversion of the answer back to a pixel. The image is captured at the display dimensions `ScreenshotStep.deviceDimensions` returns, and the answer is multiplied by those same dimensions. The prompt is the only place that used anything else.

Until this change, the element list divided by the right and bottom edges of the **active window's root node**, falling back to a fixed 1080 × 1920. The history divided by the display. On the campaign emulator the display reports 1080 × 1794 (`Display.getSize` excludes the navigation bar). The root node of a full-screen activity reports `[0,0][1080,1920]`, because the DecorView extends behind the navigation bar. A dialog, popup or keyboard-resized window reports its own rectangle, such as `[28,690][1052,1166]`. The list was therefore in a different space from the image on nearly every screen. The same widget could appear as `@(500,92)` in the list and `@(500,99)` in the history of one prompt. A model that copied a listed coordinate tapped above the widget's centre by 6.56 % of the centre's distance from the top of the screen on full-screen windows (about 12 px at the top, 106 px at the bottom), and hundreds of pixels away on dialogs. The Study 03 settings audit measured 1.3 % to 6.0 % of E6 arm-4 decisions sent to a different target this way (`rvsec-study03-replication-package/experiments/E6-campaign/settings-audit.md` §4).

`uiautomator dump`, the tool used to inspect screens offline, clips every node to `Display.getSize`. The view behind the navigation bar shows up as `[0,0][0,0]` and the root as `[0,0][1080,1794]`, which made the two bases look identical in every dump anyone looked at. The tree the jar builds is not clipped. The root node is the bounds of one window, not the size of the screen, and it is not a valid divisor for a coordinate shown next to a full-display screenshot.

The history had two more defects in the same code. It did not clamp: a widget centred below the display was shown at y ≥ 1000, one left of or above it at a negative value, outside the space the model answers in (2,860 entries in 2,182 E6 arm-4 prompts; 10 of the 19 E6 answers with `qwen` y ≥ 1000 copy one, `evidence/prompt_zero_scan_e6.out`). And an off-tree LLM tap and a menu press have no resolved node, and both were recorded as `click @(0,0)`, a click at the screen's corner that never happened. Such an entry appeared in 24.2 % of E6 arm-4 prompts (31,466 of 130,266) and 22.5 % of E5b `v13_A` prompts (2,157 of 9,586); the model copied it twice, both times as a `degenerate` answer. `v17` also rendered its history inline from the raw fields, outside the shared formatter, which is how `back@(0,0)` appears in its prompts.

This delta states where the dimensions come from, routes every [0,1000) value the prompt shows through one primitive (`CoordinateNormalizer.toNormalized`, `llm-infrastructure`), moves the history's normalization from the moment an action is recorded to the moment the prompt is built, so the list and the history cannot diverge, and makes every history entry record the action that ran, rendered by one formatter in every variant. A listed coordinate is the centre of the part of the widget that is on screen, the point the jar taps for that widget, and a widget with no part on screen lists none. The `v13` and `v17` element lists also stop repeating a widget once per action and drop an ordinal nothing reads. The image, the system message, the variants' layouts (apart from how history entries without a node render, and the `v13`/`v17` element lists) and the answer mapping are unchanged.

## Invariants

- **INV-PRM-06**: Every [0,1000) coordinate in one prompt (element-list entries in every variant that lists coordinates, and every action-history entry) SHALL be computed by `CoordinateNormalizer.toNormalized` from a pixel position inside the display, the centre of the widget's visible part ("Single Coordinate Base"), and the `{width, height}` that `ScreenshotStep.deviceDimensions` returned for the same decision, the pair the screenshot was captured at and the answer is mapped with. No other divisor SHALL reach the prompt. In particular, the root node's bounds and any constant fallback inside `ApePromptBuilder` SHALL NOT be a source.

## ADDED Requirements

### Requirement: Single Coordinate Base

`ApePromptBuilder.build` SHALL receive the device dimensions as arguments (`int deviceWidth, int deviceHeight`) from `LlmEngine`, which obtained them from `ScreenshotStep.deviceDimensions(tree)` in step 2 of the Action Selection Pipeline (`llm-routing`). The builder SHALL NOT determine dimensions itself, and SHALL NOT read the `GUITree` for that purpose. Dimensions were the tree's only use in `build`, so the `GUITree` parameter SHALL NOT exist (P3).

Every [0,1000) coordinate the builder renders SHALL be produced by `CoordinateNormalizer.toNormalized(pixelX, pixelY, deviceWidth, deviceHeight)`. `ApePromptBuilder` SHALL contain no inline normalization arithmetic: the shared line formatter (variants `ape_current`, `ape_reasoning`, `compact_v1`), the `v13` builder, the `v17` builder, and the history formatter SHALL all call that one primitive.

The pixel a widget is listed at SHALL be the centre of its **visible part**: its `getBoundsInScreen()` intersected with the display rectangle `[0, deviceWidth) × [0, deviceHeight)` (`Rect.intersect`), then `Rect.centerX()`/`Rect.centerY()`. For a widget wholly on screen that is the centre of its bounds. For a widget cut by the display edge (a list item half behind the navigation bar, an off-canvas menu partly slid in) it is the centre of the part the model can see in the screenshot. This is the point the jar itself taps for a node action: `MonkeySourceApe.generateClickEventAt` taps the centre of the node's bounds intersected with the visible window, and drops an action whose node does not intersect it (`exploration` "Off-Screen Action Handling"; the code labels the drop INV-EXPL-19). A widget whose visible part is empty, i.e. entirely off screen, has no pixel the model can point at and that the jar would tap, so its line SHALL omit `@(x,y)`, exactly as for an unresolved node below. Its line still appears: whether off-screen actions belong in the list at all is the separate list-curation question. One helper computes this pixel for the list sites and the history formatter alike, so a widget and the history entry of a click on it show one coordinate.

At `e93dea86` the list used the centre of the whole bounds, clamped to the display edge by the arithmetic. In E6 arm 4, 23,080 listed lines (2.1 %, in 3.9 % of prompts) belonged to a node entirely off screen, and 58,765 (5.3 %) to a node cut by the display edge (`evidence/dup_identity_e6.out`). Of 53,352 copied answers, 227 copied an off-screen entry: they mapped to another widget (103), tapped off-tree at the edge (83), were rejected by the band (17), or snapped to the intended node (24), an action the dispatcher then drops as off-screen; 128 of the 227 copied an action APE had already marked invalid. Holding the copied entry fixed, the share of copies that map to their widget is 87.1 % under this rule against 87.2 % with the clamped centre, the difference being those 24 snaps and one copy of an edge-cut entry that now maps to a neighbour (`evidence/coordinate_counterfactual.out`, scenarios S3 and S4). The 24 snaps exist only because the bottom band is gone (`llm-routing` INV-RTR-22): the nodes lie in the strip behind the navigation bar, which APE marks valid because it overlaps the 1920-high root, and an answer clamped to y = 1793 snaps to them. As run, the band rejected 191 of the 227 off-screen copies, and only 3 reached their widget (`evidence/coordinate_counterfactual.out`, transitions S0 → S4). A dropped action is still marked visited and still counts a dead-pair strike, so listing no coordinate for these nodes removes wasted steps rather than costing hits.

A list line whose action has no resolved node, or whose node's bounds cannot be read, has no pixel to normalize. At every list site (the shared line formatter, `v13`, `v17`) such a line SHALL omit `@(x,y)` entirely and SHALL NOT render `@(0,0)`. Until this change all three sites wrote `@(0,0)` in that case, contradicting the "Unresolved action node" scenario of "Widget List Generation", which no test exercised. E6 and E5b contain no such line (every listed action of the current state is resolved against the latest tree by `StatefulAgent.validateAllNewActions`), so the repair changes no measured prompt.

The base is the display rather than the root node because the display is what the model sees and what its answer is multiplied by. A coordinate in any other space is a different number for the same point.

#### Scenario: full-screen window whose root extends behind the navigation bar
- **WHEN** `build` is called with `deviceWidth=1080`, `deviceHeight=1794`
- **AND** the current tree's root node reports bounds `[0,0][1080,1920]`
- **AND** an action's widget has bounds `[0,105][1080,252]` (centre `(540,178)`)
- **THEN** that widget's line SHALL render `@(500,99)` (`178/1794`), not `@(500,92)` (`178/1920`)

#### Scenario: dialog window
- **WHEN** `build` is called with `deviceWidth=1080`, `deviceHeight=1794`
- **AND** the active window is a dialog whose root node reports `[28,690][1052,1166]`
- **AND** an action's widget has bounds `[745,1040][897,1140]` (centre `(821,1090)`)
- **THEN** that widget's line SHALL render `@(760,607)`
- **AND** SHALL NOT render a coordinate computed against `1052 × 1166`

#### Scenario: same widget, same coordinate in list and history
- **WHEN** a widget with centre `(540,178)` appears in the element list
- **AND** the most recent history entry is a click on that same widget
- **AND** `build` is called with `deviceWidth=1080`, `deviceHeight=1794`
- **THEN** both the list line and the history line SHALL render `@(500,99)`

#### Scenario: round trip lands inside the widget
- **WHEN** a listed widget's `@(x,y)` is fed back unchanged as the model's answer
- **AND** `CoordinateNormalizer.normalize(x, y, deviceWidth, deviceHeight)` converts it to a pixel
- **THEN** the pixel SHALL lie inside the widget's visible part for every widget whose visible part is at least 4 px wide and 4 px tall, on any display whose width and height are below 2000 px (the forward and inverse conversions both truncate, so the round trip loses less than `dimension/1000 + 1` px, which is at most 2 px on such a display)

#### Scenario: widget cut by the display edge is listed at its visible centre
- **WHEN** `build` is called with `deviceWidth=1080`, `deviceHeight=1794`
- **AND** a widget has bounds `[0,1740][1080,1974]` (whole centre `(540,1857)`, below the display)
- **THEN** its line SHALL render `@(500,984)`: the visible part is `[0,1740][1080,1794]`, centred at `(540,1767)`
- **AND** SHALL NOT render `@(500,999)`, the clamped whole centre

#### Scenario: widget entirely off screen renders no coordinate
- **WHEN** `build` is called with `deviceWidth=1080`, `deviceHeight=1794`
- **AND** a resolved widget has bounds `[0,1800][1080,1900]`, which do not intersect the display
- **THEN** its line SHALL contain no `@(`
- **AND** the line SHALL still appear in the list

#### Scenario: unresolved node renders no coordinate
- **WHEN** a target-requiring action's `getResolvedNode()` returns null
- **AND** any coordinate-bearing variant (`ape_current`, `ape_reasoning`, `compact_v1`, `v13`, `v17`) builds the prompt
- **THEN** that action's line SHALL contain no `@(`
- **AND** the line SHALL still appear in the list

### Requirement: Element List of the v13 and v17 Variants

The `v13` (`UI elements:`) and `v17` (`ELEMENTS:`) element lists SHALL render **one line per widget**, not one line per action. A widget is the resolved `GUITreeNode` of a target-requiring action, compared by identity. Lines SHALL appear in the order of each widget's first target-requiring action in `actions`. An action whose node is not resolved SHALL keep a line of its own, with no `@(x,y)` ("Single Coordinate Base").

No line SHALL carry an ordinal prefix (`1. `, `2. `, …). Each line starts, after its indentation, with the widget's class. The rest of the line is unchanged: identifier, `@(x,y)` per INV-PRM-06, MOP marker, static metadata, and in `v17` the status tag. In `v17`, the status tag SHALL be computed from the sum of `getVisitedCount()` over the widget's target-requiring actions.

The `v17` screen-info line SHALL count widgets on both sides of its ratio: the numerator is the number of listed widgets whose summed visit count is above 0, the denominator is the number of listed widgets, and the label reads `elements tested` instead of `actions tested` (`SCREEN: <Activity> | <tested>/<widgets> elements tested | visits: <N>`). The `MOP NAVIGATION` block that follows is produced by `buildExplorationContext`, which the default-branch variants share and which still counts target-requiring actions; it is unchanged.

Every field of a line describes the widget, so one line per action wrote identical copies of it. In E6, 25.3 % of lines (279,463) shared their resolved node's bounds with an earlier line. Identity is the stricter criterion: 274,830 of those lines (24.9 % of all lines) also share the target `Name`, i.e. are the same widget, and collapse. The other 4,633 (in 4,434 prompts) are distinct nodes with identical bounds, such as a container and its single child, and keep a line each. Conversely, 17,865 lines share a `Name` with an earlier line but resolved, by alias sampling, to a different node; they also keep a line each, since the mapper matches each action against its own resolved node. No tool, parser or mapper reads the ordinal: the answer is a coordinate, matched against the state's actions, never against list lines. The `ModelAction` list, the mapping, SATA and the exploration model are unaffected, because none of them reads the prompt text.

#### Scenario: widget with a click and a long-click is listed once
- **WHEN** `actions` holds a `MODEL_CLICK` and a `MODEL_LONG_CLICK` whose resolved node is the same EditText `"Email"` centred at `(540,538)`
- **AND** variant `v13` builds the prompt with `deviceWidth=1080`, `deviceHeight=1794`
- **THEN** the element list SHALL contain exactly one line for that EditText: `  EditText "Email" @(500,299)`

#### Scenario: two scroll directions on one widget are listed once
- **WHEN** `actions` holds `MODEL_SCROLL_BOTTOM_UP` and `MODEL_SCROLL_TOP_DOWN` on the same ScrollView
- **THEN** the element list SHALL contain one line for that ScrollView

#### Scenario: no ordinal prefix
- **WHEN** variant `v13` or `v17` renders a list of three widgets
- **THEN** no line of the list SHALL begin, after its indentation, with a digit
- **AND** the lines SHALL follow the order of each widget's first action in `actions`

#### Scenario: v17 status tag counts the widget's visits
- **WHEN** a widget's `MODEL_CLICK` has been visited 2 times and its `MODEL_LONG_CLICK` 1 time
- **THEN** its `v17` line SHALL carry `[TESTED-3x]`

#### Scenario: v17 screen line counts widgets on both sides
- **WHEN** `actions` holds a `MODEL_CLICK` (visited 1 time) and a `MODEL_LONG_CLICK` (visited 0 times) on one Button, and a `MODEL_CLICK` (visited 0 times) on one TextView
- **AND** variant `v17` builds the prompt
- **THEN** the screen-info line SHALL read `1/2 elements tested`

#### Scenario: model actions unchanged
- **WHEN** the list collapses a widget's two actions into one line
- **THEN** `CoordinateMapper.map` SHALL still receive both actions in `actions`
- **AND** a `long_click` answer at that widget's coordinate SHALL still match its `MODEL_LONG_CLICK`

## MODIFIED Requirements

### Requirement: Widget List Generation

The text content of the user message SHALL contain a structured list of all available actions on the current state. Each action SHALL be formatted as one line with the following pattern:

**Non-target actions** (MODEL_BACK, MODEL_MENU):
```
[<index>] <ACTION_TYPE> (key press)
```

**Target actions** (MODEL_CLICK, MODEL_LONG_CLICK, MODEL_SCROLL_*):
```
[<index>] <WidgetClass> "<text>" @(<normX>,<normY>) <MOP_MARKER> (v:<N>)
```

For input-capable widgets (EditText, SearchView, AutoCompleteTextView) with a non-null hint:
```
[<index>] <WidgetClass> "<text>" hint="<hint>" @(<normX>,<normY>) <MOP_MARKER> (v:<N>)
```

Where:
- `<index>` is the 0-based position in the actions list
- `<WidgetClass>` is the widget's Android class simple name (e.g., `Button`, `EditText`, `ImageView`)
- `<text>` is the widget's **identifier text**, resolved by fallback: the widget's text; else its content-description; else its short resource-id (the `":id/"` suffix, rendered as `id=<shortId>`). Truncated to 50 characters; embedded `\n`/`\r` flattened to spaces, so the element list stays one physical line per action — the property the prompt format itself depends on. It is no longer a trace concern: the prompt and response dumps travel as JSON-escaped `sys`/`user`/`resp` fields of the step record's `llm[]` sub-event, where any character is safe by construction (`event-sink` INV-SNK-02). Only when text, content-description, AND resource-id are all empty is the identifier omitted. Measured motivation: 35.8% of grounding tests rendered elements with no identifier at all — the model hit 33.1% on identifier-less lines vs 71.4% with an identifier, and ImageView (0/210 hits) is the canonical victim: icon buttons routinely carry a content-description or resource-id but no text, and the previous rendering gave the model nothing to anchor the coordinates to.
- `hint="<hint>"` is the widget's hint text, included only for input-capable widgets when `GUITreeNode.getHint()` is non-null and non-empty; truncated to 30 characters.
- `@(<normX>,<normY>)` is the center of the widget's **visible part** (`getBoundsInScreen()` intersected with `[0, deviceWidth) × [0, deviceHeight)`, then `Rect.centerX()`, `Rect.centerY()`; "Single Coordinate Base") converted to Qwen3-VL [0,1000) normalized space by `CoordinateNormalizer.toNormalized(centerX, centerY, deviceWidth, deviceHeight)`: `normX = clamp((int)(centerX * 1000.0 / deviceWidth), 0, 999)`, and likewise for `normY`. `deviceWidth` and `deviceHeight` are the display dimensions `build` received, the ones the screenshot was captured at and the answer is mapped with ("Single Coordinate Base"). They are never the root node's bounds. This is what makes the list the same coordinate space the LLM responds in. Omitted if node is not resolved, or if its bounds do not intersect the display.
- `<MOP_MARKER>` is `[DM]` (direct monitored), `[M]` (transitive monitored), or omitted if no MOP match
- `(v:<N>)` is the action's visited count in compact form

The list SHALL be preceded by a compact header: `Screen "<ActivitySimpleName>":`.

_Scope (`llm-coordinate-single-base`):_ the one-line-per-action format above, with its `[<index>]` prefix, is the list of the shared line formatter, used by the variants `ape_current`, `ape_reasoning` and `compact_v1`, which keep it unchanged. The `v13` and `v17` lists are governed by "Element List of the v13 and v17 Variants" (one line per widget, no ordinal). The `@(<normX>,<normY>)` rule and the omission for an unresolved node below apply to every coordinate-bearing variant.

#### Scenario: Mixed action list with MOP data and visited counts

- **WHEN** `build()` is called with a state on `com.example.MainActivity`, `deviceWidth=1080` and `deviceHeight=1920`
- **AND** actions include BACK, MENU, a Button "Encrypt" with directMop (visited 0 times, device center 200,225), an EditText "Password" with hint "Enter password" (visited 3 times, device center 225,325), and a TextView "Help" with transitiveMop (visited 1 time, device center 250,420)
- **AND** `mopData` is non-null
- **THEN** the text content SHALL contain:
  ```
  Screen "MainActivity":
  [0] BACK (key)
  [1] MENU (key)
  [2] Button "Encrypt" @(185,117) [DM] (v:0)
  [3] EditText "Password" hint="Enter password" @(208,169) (v:3)
  [4] TextView "Help" @(231,218) [M] (v:1)
  ```

#### Scenario: ImageView with only a content-description gets an identifier

- **WHEN** an ImageView action's node has empty text and content-description `"Add account"`
- **THEN** its line SHALL render `ImageView "Add account" @(...)`

#### Scenario: widget with only a resource-id gets an identifier

- **WHEN** an ImageView action's node has empty text, empty content-description, and resource-id `com.example:id/fab_add`
- **THEN** its line SHALL render the identifier `id=fab_add`
- **AND** the line SHALL NOT render an empty `""`

#### Scenario: No MOP data (static analysis unavailable)

- **WHEN** `build()` is called with `mopData` equal to null
- **THEN** no `[DM]` or `[M]` markers SHALL appear in any action line
- **AND** visited counts and normalized coordinates SHALL still be present

#### Scenario: Unresolved action node

- **WHEN** an action has `getResolvedNode()` returning null
- **THEN** the `@(x,y)` coordinates SHALL be omitted from that action's line
- **AND** the action SHALL still appear in the list, with its index, type and visited count in the shared line formatter's variants (the `v13`/`v17` lines carry neither index nor visited count)

#### Scenario: Widget text truncation

- **WHEN** a widget's text is `"This is a very long label that exceeds fifty characters in total length"`
- **THEN** the displayed text SHALL be the first 47 characters followed by an ellipsis, 50 characters in total: `"This is a very long label that exceeds fifty ch..."`

#### Scenario: multi-line widget text flattened

- **WHEN** a widget's text is `"Sign\nIn"`
- **THEN** the element line SHALL render `"Sign In"` on one physical line

### Requirement: Action History

The text content SHALL include a section showing the last 3-5 executed actions with their results, positioned after the widget list and before the exploration context. This section prevents the LLM from repeatedly suggesting the same action.

The format SHALL be compact with coordinates in [0,1000) normalized space, computed exactly as the widget list computes them. Each `ActionHistoryEntry` SHALL carry the executed widget's **pixel bounds** (`bounds`, a copy of the resolved node's `getBoundsInScreen()`, recorded by `StatefulAgent.recordActionHistory` when the action executes), not a pre-normalized pair. `ApePromptBuilder` SHALL render it at build time with the same visible-centre rule and the same `CoordinateNormalizer.toNormalized` call as the list, with the dimensions `build` received; an entry whose bounds are null or do not intersect the display renders without coordinates, so no entry shows a value outside [0,999]. `StatefulAgent` SHALL NOT normalize and SHALL NOT read the display for the history. Normalizing at build time is what guarantees one base per prompt (INV-PRM-06): the list and the history are computed by the same call with the same divisor, so a widget listed and clicked in the same prompt shows one coordinate in both.

```
Recent:
- <action_type> @(<normX>,<normY>) <WidgetClass> "<text>" → <result>
```

An entry for an action with no resolved node SHALL record what ran:
- an off-tree `MODEL_LLM_TAP` records `click`, or `long_click` when `LlmTapAction.isLongClick()` is true, with a one-pixel rectangle at the tap's own pixel (`[x,y][x+1,y+1]` from `getPixelX()`/`getPixelY()`, whose visible centre is `(x,y)`) as its bounds, and no class or text;
- a `MODEL_MENU` press records `menu` with no bounds and, like `back`, is rendered without coordinates.

No entry SHALL render `@(0,0)` because its action had no node.

`formatHistoryEntry` SHALL be the only place an entry becomes text. Every variant that shows a history renders through it: the default branch (`ape_current`, `ape_reasoning`, `compact_v1`), `v13`, `visual_only`, and `v17`. `v17` uses the formatter's compact form, one token per entry joined by `, `: `<action_type>@(<normX>,<normY>)` for an entry with coordinates, and `<action_type>` alone for `back` and `menu`. The position stated above (after the widget list) holds for every variant except `v17`, whose compact `Recent:` line precedes `ELEMENTS:`, as it did before this change.

Where `<result>` is a brief outcome: `same`, `new screen`, `previous screen`. Result is determined by comparing states: if `newState == lastState` → "same"; if `newState == stateBeforeLast` → "previous screen"; else → "new screen".

For `type_text` actions, include the typed text: `- type_text @(x,y) "typed text" → result`.

If `recentActions` is empty (first steps), this section SHALL be omitted entirely. Maximum 5 entries.

#### Scenario: Action history with balanced detail

- **WHEN** `build()` is called with 4 recent actions
- **THEN** the text SHALL contain:
  ```
  Recent:
  - click @(208,169) EditText "Password" → same
  - type_text @(208,169) "test@mail.com" → same
  - click @(185,117) Button "Encrypt" → new screen
  - back → previous screen
  ```

#### Scenario: history entry normalized at build time
- **WHEN** `recordActionHistory` records a click on a widget with bounds `[0,105][1080,252]`
- **THEN** the entry SHALL carry the bounds `[0,105][1080,252]`
- **AND** when `build` is later called with `deviceWidth=1080`, `deviceHeight=1794`, the entry SHALL render `- click @(500,99) ...`

#### Scenario: No action history (first steps)

- **WHEN** `build()` is called with an empty `recentActions` list
- **THEN** the "Recent:" section SHALL be omitted entirely

#### Scenario: off-tree LLM tap in history
- **WHEN** the most recent executed action is a `MODEL_LLM_TAP` at pixel `(600, 900)`, not long
- **AND** `build` is called with `deviceWidth=1080`, `deviceHeight=1794`
- **THEN** its entry SHALL render `- click @(555,501) → <result>`
- **AND** SHALL NOT render `@(0,0)`

#### Scenario: menu press in history
- **WHEN** the most recent executed action is a `MODEL_MENU` press
- **THEN** its entry SHALL render `- menu → <result>`, with no coordinates

#### Scenario: v17 history goes through the formatter
- **WHEN** variant `v17` builds a prompt whose history holds a click on a widget centred at `(540,178)` followed by a `back`
- **AND** `build` is called with `deviceWidth=1080`, `deviceHeight=1794`
- **THEN** its history line SHALL be `Recent: click@(500,99), back`
- **AND** SHALL NOT contain `back@(0,0)`

#### Scenario: type_text in history

- **WHEN** a recent action was type_text on an EditText "Domain"
- **THEN** the entry SHALL be: `- type_text @(500,487) "google.com" → same`

---
