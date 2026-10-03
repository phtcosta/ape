# Delta: LLM Prompt (llm-coordinate-single-base)

## Purpose

The prompt promises the model a single coordinate space. The system message says every coordinate is in [0,1000). The element list's `@(x,y)` is a widget's centre in that space. The action history uses the same notation, and the model's answer is read back in it. For that promise to hold, three things must agree on what 1000 means: the image the model sees, the numbers the prompt shows, and the conversion of the answer back to a pixel. The image is captured at the display dimensions `ScreenshotStep.deviceDimensions` returns, and the answer is multiplied by those same dimensions. The prompt is the only place that used anything else.

Until this change, the element list divided by the right and bottom edges of the **active window's root node**, falling back to a fixed 1080 × 1920. The history divided by the display. On the campaign emulator the display reports 1080 × 1794 (`Display.getSize` excludes the navigation bar). The root node of a full-screen activity reports `[0,0][1080,1920]`, because the DecorView extends behind the navigation bar. A dialog, popup or keyboard-resized window reports its own rectangle, such as `[28,690][1052,1166]`. The list was therefore in a different space from the image on nearly every screen. The same widget could appear as `@(500,92)` in the list and `@(500,99)` in the history of one prompt. A model that copied a listed coordinate tapped above the widget's centre by 6.56 % of the centre's distance from the top of the screen on full-screen windows (about 12 px at the top, 106 px at the bottom), and hundreds of pixels away on dialogs. The Study 03 settings audit measured 1.3 % to 6.0 % of E6 arm-4 decisions sent to a different target this way (`rvsec-study03-replication-package/experiments/E6-campaign/settings-audit.md` §4).

`uiautomator dump`, the tool used to inspect screens offline, clips every node to `Display.getSize`. The view behind the navigation bar shows up as `[0,0][0,0]` and the root as `[0,0][1080,1794]`, which made the two bases look identical in every dump anyone looked at. The tree the jar builds is not clipped. The root node is the bounds of one window, not the size of the screen, and it is not a valid divisor for a coordinate shown next to a full-display screenshot.

This delta states where the dimensions come from, routes every [0,1000) value the prompt shows through one primitive (`CoordinateNormalizer.toNormalized`, `llm-infrastructure`), and moves the history's normalization from the moment an action is recorded to the moment the prompt is built, so the list and the history cannot diverge. The image, the system message, the variants' layouts and the answer mapping are unchanged.

## Invariants

- **INV-PRM-06**: Every [0,1000) coordinate in one prompt (element-list entries in every variant that lists coordinates, and every action-history entry) SHALL be computed by `CoordinateNormalizer.toNormalized` from a pixel position and the `{width, height}` that `ScreenshotStep.deviceDimensions` returned for the same decision, the pair the screenshot was captured at and the answer is mapped with. No other divisor SHALL reach the prompt. In particular, the root node's bounds and any constant fallback inside `ApePromptBuilder` SHALL NOT be a source.

## ADDED Requirements

### Requirement: Single Coordinate Base

`ApePromptBuilder.build` SHALL receive the device dimensions as arguments (`int deviceWidth, int deviceHeight`) from `LlmEngine`, which obtained them from `ScreenshotStep.deviceDimensions(tree)` in step 2 of the Action Selection Pipeline (`llm-routing`). The builder SHALL NOT determine dimensions itself, and SHALL NOT read the `GUITree` for that purpose. Dimensions were the tree's only use in `build`, so the `GUITree` parameter SHALL NOT exist (P3).

Every [0,1000) coordinate the builder renders SHALL be produced by `CoordinateNormalizer.toNormalized(pixelX, pixelY, deviceWidth, deviceHeight)`. `ApePromptBuilder` SHALL contain no inline normalization arithmetic: the shared line formatter (variants `ape_current`, `ape_reasoning`, `compact_v1`), the `v13` builder, the `v17` builder, and the history formatter SHALL all call that one primitive. A widget whose centre lies outside the display (for example, below y=1794 behind the navigation bar) is clamped to 999 on that axis by the primitive. It is still listed.

The base is the display rather than the root node because the display is what the model sees and what its answer is multiplied by. A coordinate in any other space is a different number for the same point.

#### Scenario: full-screen window whose root extends behind the navigation bar
- **WHEN** `build` is called with `deviceWidth=1080`, `deviceHeight=1794`
- **AND** the current tree's root node reports bounds `[0,0][1080,1920]`
- **AND** an action's widget has bounds `[0,105][1080,252]` (centre `(540,178)`)
- **THEN** that widget's line SHALL render `@(500,99)` (`178/1794`), not `@(500,92)` (`178/1920`)

#### Scenario: dialog window
- **WHEN** `build` is called with `deviceWidth=1080`, `deviceHeight=1794`
- **AND** the active window is a dialog whose root node reports `[28,690][1052,1166]`
- **AND** an action's widget has bounds `[745,1081][897,1207]` (centre `(821,1144)`)
- **THEN** that widget's line SHALL render `@(760,637)`
- **AND** SHALL NOT render a coordinate computed against `1052 × 1166`

#### Scenario: same widget, same coordinate in list and history
- **WHEN** a widget with centre `(540,178)` appears in the element list
- **AND** the most recent history entry is a click on that same widget
- **AND** `build` is called with `deviceWidth=1080`, `deviceHeight=1794`
- **THEN** both the list line and the history line SHALL render `@(500,99)`

#### Scenario: round trip lands inside the widget
- **WHEN** a listed widget's `@(x,y)` is fed back unchanged as the model's answer
- **AND** `CoordinateNormalizer.normalize(x, y, deviceWidth, deviceHeight)` converts it to a pixel
- **THEN** the pixel SHALL lie inside the widget's bounds for every widget at least 4 px wide and 4 px tall whose centre lies inside the display, on any display whose width and height are below 2000 px (the forward and inverse conversions both truncate, so the round trip loses less than `dimension/1000 + 1` px, which is at most 2 px on such a display)

#### Scenario: widget centre below the display edge
- **WHEN** `build` is called with `deviceHeight=1794` and a widget's centre is at y=1857
- **THEN** its line SHALL render y=999
- **AND** the action SHALL still appear in the list

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
- `@(<normX>,<normY>)` is the center of the widget's bounds (`Rect.centerX()`, `Rect.centerY()` of `getBoundsInScreen()`) converted to Qwen3-VL [0,1000) normalized space by `CoordinateNormalizer.toNormalized(centerX, centerY, deviceWidth, deviceHeight)`: `normX = clamp((int)(centerX * 1000.0 / deviceWidth), 0, 999)`, and likewise for `normY`. `deviceWidth` and `deviceHeight` are the display dimensions `build` received, the ones the screenshot was captured at and the answer is mapped with ("Single Coordinate Base"). They are never the root node's bounds. This is what makes the list the same coordinate space the LLM responds in. Omitted if node is not resolved.
- `<MOP_MARKER>` is `[DM]` (direct monitored), `[M]` (transitive monitored), or omitted if no MOP match
- `(v:<N>)` is the action's visited count in compact form

The list SHALL be preceded by a compact header: `Screen "<ActivitySimpleName>":`.

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
- **AND** the action SHALL still appear in the list with its index, type, and visited count

#### Scenario: Widget text truncation

- **WHEN** a widget's text is `"This is a very long label that exceeds fifty characters in total length"`
- **THEN** the displayed text SHALL be the first 47 characters followed by an ellipsis, 50 characters in total: `"This is a very long label that exceeds fifty ch..."`

#### Scenario: multi-line widget text flattened

- **WHEN** a widget's text is `"Sign\nIn"`
- **THEN** the element line SHALL render `"Sign In"` on one physical line

### Requirement: Action History

The text content SHALL include a section showing the last 3-5 executed actions with their results, positioned after the widget list and before the exploration context. This section prevents the LLM from repeatedly suggesting the same action.

The format SHALL be compact with coordinates in [0,1000) normalized space, computed exactly as the widget list computes them. Each `ActionHistoryEntry` SHALL carry the executed widget's **pixel** centre (`centerX`, `centerY`; `Rect.centerX()`/`Rect.centerY()` of the resolved node's `getBoundsInScreen()`, recorded by `StatefulAgent.recordActionHistory` when the action executes), not a pre-normalized pair. `ApePromptBuilder` SHALL normalize it at build time with `CoordinateNormalizer.toNormalized` and the dimensions `build` received. `StatefulAgent` SHALL NOT normalize and SHALL NOT read the display for the history. Normalizing at build time is what guarantees one base per prompt (INV-PRM-06): the list and the history are computed by the same call with the same divisor, so a widget listed and clicked in the same prompt shows one coordinate in both.
```
Recent:
- <action_type> @(<normX>,<normY>) <WidgetClass> "<text>" → <result>
```

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
- **THEN** the entry SHALL carry `centerX=540`, `centerY=178`
- **AND** when `build` is later called with `deviceWidth=1080`, `deviceHeight=1794`, the entry SHALL render `- click @(500,99) ...`

#### Scenario: No action history (first steps)

- **WHEN** `build()` is called with an empty `recentActions` list
- **THEN** the "Recent:" section SHALL be omitted entirely

#### Scenario: type_text in history

- **WHEN** a recent action was type_text on an EditText "Domain"
- **THEN** the entry SHALL be: `- type_text @(500,487) "google.com" → same`

