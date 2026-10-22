## Purpose

`ApePromptBuilder` bridges APE-RV's internal data structures (State, ModelAction, the action history, and — through the mounted groups' contributions — `StaticGuiModel` and the run's `MopContext`) and the LLM's multimodal input format. It constructs a prompt that gives the LLM a screenshot of the current screen, a structured list of available actions with their widget types, visited counts (in the variants that show them), MOP markers that state the scoring's verdict on each line's actions (in a cell with MOP), recent action history with each action's own result, and an instruction to select the most promising action for exploration. The `v13` and `v17` lists show one line per widget rather than one per action. The `GUITree` is not an input of `build`: the only thing the builder took from it was the dimensions to normalize by, and it now receives the display dimensions of the decision as arguments.

The prompt is built from the mounted behaviour groups (design D-B1, D-A3): `ApePromptBuilder` renders a base prompt and appends, at fixed extension points, what each mounted `PromptContribution` returns, STATIC before MOP. `RunContext` mounts `StaticPromptContribution` iff `Feature.STATIC` is in the plan and `MopPromptContribution` iff `Feature.MOP` is. MOP contributes the line markers, the `N/M MOP.` count, the `MOP NAVIGATION:` heading of `v17` and the MOP sentences of the system message; STATIC contributes the widget metadata suffix, read from `StaticGuiModel`. Without a group its contribution does not exist, so in a cell with LLM and no MOP no MOP instruction reaches the model, and the MOP-free wording of each sentence is the MOP sentence with its MOP clause deleted. The visit context (`NEW state.` / `Visited <N>x.`) is not MOP and appears in every cell (in `v17` under the neutral heading `NAVIGATION:` when MOP is absent).

The prompt design follows a principle from the rvsmart experiments: the LLM performs best when it receives both visual (screenshot) and structured (widget list) information. The screenshot lets the LLM understand the visual layout and identify UI elements that the accessibility tree may miss — including **dynamic elements** (WebView content, custom-drawn views, canvas elements, dynamically loaded content) that do not appear in the UIAutomator dump. The widget list provides precise bounds, action types, visited counts, and (with MOP) MOP markers that the LLM cannot infer from pixels alone. Combining both reduces coordinate errors (from ~70% with image-only to ~84% with image+text) and, in a cell with MOP, enables MOP-aware reasoning.

A key advantage of the LLM integration is the ability to perform **semantic clicks on elements invisible to UIAutomator**. While SATA can only interact with widgets present in the accessibility tree, the LLM sees the screenshot and can identify and click on visual elements that have no corresponding accessibility node — buttons inside WebViews, custom-rendered UI, dynamically injected content, etc. When the LLM targets such an element, `CoordinateMapper.map` finds no widget action containing or near the point and, for a `click` or `long_click` answer, synthesizes an off-tree `LlmTapAction` of type `MODEL_LLM_TAP` carrying the LLM's pixel, which the jar executes as a coordinate tap (`llm-routing` INV-RTR-03, "Coordinate-to-ModelAction Mapping").

The inclusion of recent action history (last 3-5 actions with results) prevents the LLM from being "amnesic" — without history, the LLM may repeatedly suggest the same action. This is a consensus finding from the state of the art (GPTDroid, VisionDroid, rvsmart V17, LLM-Explorer all include some form of action memory).

The prompt uses Qwen3-VL's normalized coordinate space [0, 1000) for both input widget positions and LLM response coordinates. The `CoordinateNormalizer` converts between this space and device pixels: `toNormalized` from pixel to [0,1000) for every coordinate the prompt shows, `normalize` back to a pixel for the answer. There is a single base per decision: the display dimensions that `ScreenshotStep.deviceDimensions` returns, which are the dimensions the screenshot is captured at and the answer is multiplied by. The element list and the action history both divide by that pair, at build time, never by the active window's root node or a constant ("Single Coordinate Base", INV-PRM-06). A listed coordinate is the centre of the part of the widget that is on screen, the point the jar taps for it; a widget with no part on screen lists none. Reference: https://github.com/QwenLM/Qwen3-VL/issues/1486

## MODIFIED Requirements

### Requirement: System Message

`ApePromptBuilder` SHALL generate a system message with role `"system"`. The system message SHALL be compact (~120 tokens), modeled on rvsmart V13 which proved effective in experiments. Verbose reasoning steps (V17-style, ~300 tokens) did not show significant improvement and waste tokens/latency.

With `Feature.MOP` in the plan, the `ape_current` system message is:

```
You are an Android UI testing agent exploring an app.
DIALOG: If permission/error dialog visible, dismiss it first (click Allow/OK).
PRIORITY: [DM]/[M] elements > unvisited (v:0) > visited.
AVOID: status bar (top), navigation bar (bottom).
RULES: Don't click same position twice. Use type_text for input fields with valid data (email: user@example.com, password: Test1234!, domain: example.com, search: relevant term).
Tools (coordinates in [0,1000) normalized space):
  click(x, y) — tap element
  long_click(x, y) — long press element
  type_text(x, y, text) — type into field
  back() — press back
Respond with one JSON: {"name": "<action>", "arguments": {<args>}}
```

Without `Feature.MOP` it is the same text with the MOP clause deleted from the `PRIORITY` line, which then reads:

```
PRIORITY: unvisited (v:0) > visited.
```

The clause `[DM]/[M] elements > ` is the MOP contribution's system sentence for `ape_current` (and for `ape_reasoning`, which shares this message); it is the only MOP text of the message. The MOP-free wording of every variant is listed in "Prompt Built From Group Contributions".

**Dynamic tool schema**: The `type_text` tool SHALL be included in the system message only when the current widget list contains at least one input-capable widget (EditText, SearchView, AutoCompleteTextView). When no input widgets are present, `type_text` is omitted from the tool list. This reduces LLM confusion and prevents wasted calls on inapplicable tools. The check is performed by `ApePromptBuilder.build()` when generating the system message.

Key design choices:
- **Compact format**: ~120 tokens saves ~300 tokens/call vs verbose V17. At 85 calls/session this saves ~25K tokens and ~0.5-1s latency per call.
- **[DM]/[M] notation**: Compact markers matching rvsmart V17 (DM = direct monitored, M = transitive monitored), present only with `Feature.MOP`
- **Coordinate convention**: Explicit [0, 1000) normalized space matching Qwen3-VL's native output format
- **JSON response format**: `{"name": "<action>", "arguments": {<args>}}` — compatible with ToolCallParser's 3-level fallback
- **Dialog handling**: Single line, covers permission and error dialogs
- **type_text**: Included when input fields are available; hints for semantically valid text (email, password, domain, etc.)
- **Dynamic tool schema**: type_text omitted when no input widgets on screen, reducing token waste

#### Scenario: System message content

- **WHEN** `build()` is called with any valid inputs
- **THEN** the first message SHALL have role `"system"`
- **AND** for the variants `ape_current` and `ape_reasoning` it SHALL contain the `DIALOG:`, `PRIORITY:`, `AVOID:` and `RULES:` lines quoted above
- **AND** it SHALL declare the `click`, `long_click`, and `back` tool schemas
- **AND** it SHALL specify that coordinates use [0, 1000) normalized space
- **AND** its `PRIORITY:` line SHALL rank `[DM]/[M] elements` first exactly when `Feature.MOP` is in the plan, and the message SHALL contain neither `[DM]` nor `[M]` otherwise; the message does not define the two markers beyond that ranking

#### Scenario: Dynamic tool schema — type_text included

- **WHEN** `build()` is called and the actions list contains at least one action targeting an EditText, SearchView, or AutoCompleteTextView
- **THEN** the system message SHALL include `type_text(x, y, text)` in the tool list
- **AND** the RULES section SHALL include text input hints

#### Scenario: Dynamic tool schema — type_text omitted

- **WHEN** `build()` is called and no action in the list targets an input-capable widget
- **THEN** `type_text` SHALL NOT appear in the tool list
- **AND** the system message SHALL list only `click`, `long_click`, and `back`

#### Scenario: ape_current PRIORITY line without MOP

- **WHEN** variant `ape_current` builds a prompt in cell `as_llm`
- **THEN** the system message SHALL contain the line `PRIORITY: unvisited (v:0) > visited.`
- **AND** every other line SHALL be as in the message with `Feature.MOP`

### Requirement: Widget List Generation

The text content of the user message SHALL contain a structured list of all available actions on the current state. Each action SHALL be formatted as one line with the following pattern:

**Non-target actions** (MODEL_BACK, MODEL_MENU):
```
[<index>] <ACTION_TYPE> (key)
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
- `@(<normX>,<normY>)` is the center of the widget's **visible part** (`getBoundsInScreen()` intersected with `[0, deviceWidth) × [0, deviceHeight)`, then `Rect.centerX()`, `Rect.centerY()`; "Single Coordinate Base") converted to Qwen3-VL [0,1000) normalized space by `CoordinateNormalizer.toNormalized(centerX, centerY, deviceWidth, deviceHeight)`: `normX = clamp((int)(centerX * 1000.0 / deviceWidth), 0, 999)`, and likewise for `normY`. `deviceWidth` and `deviceHeight` are the display dimensions `build` received, the ones the screenshot was captured at and the answer is mapped with ("Single Coordinate Base"). They are never the root node's bounds. This is what makes the list the same coordinate space the LLM responds in. Omitted if node is not resolved, if its bounds cannot be read, or if `Rect.intersect` finds no intersection with the display.
- `<MOP_MARKER>` is `[DM]` (direct monitored), `[M]` (monitored with a positive MOP weight), or omitted. It is the MOP contribution's line marker: decided per line and per scoring mode (`ape.mopScoring`), from the line's own actions, as "MOP Marker Annotation" defines (design D22), and always omitted when `Feature.MOP` is not in the plan
- `(v:<N>)` is the action's visited count in compact form

The list SHALL be preceded by a compact header: `Screen "<ActivitySimpleName>":`.

_Scope (`llm-coordinate-single-base`):_ the one-line-per-action format above, with its `[<index>]` prefix, is the list of the shared line formatter, used by the variants `ape_current`, `ape_reasoning` and `compact_v1`, which keep it unchanged. The `v13` and `v17` lists are governed by "Element List of the v13 and v17 Variants" (one line per widget, no ordinal). The `@(<normX>,<normY>)` rule above, and the omission for an unresolved node in the "Unresolved action node" scenario below, apply to every coordinate-bearing variant.

#### Scenario: Mixed action list with MOP data and visited counts

- **WHEN** `build()` is called with a state on `com.example.MainActivity`, `deviceWidth=1080` and `deviceHeight=1920`
- **AND** actions include BACK, MENU, a Button "Encrypt" with directMop (visited 0 times, device center 200,225), an EditText "Password" with hint "Enter password" (visited 3 times, device center 225,325), and a TextView "Help" with transitiveMop (visited 1 time, device center 250,420)
- **AND** `Feature.MOP` is in the plan, so the MOP contribution is mounted
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

- **WHEN** `build()` is called in a run whose plan contains neither `Feature.MOP` nor `Feature.STATIC` (no artifact, or an artifact loaded with both groups off), so neither contribution is mounted
- **THEN** no `[DM]` or `[M]` markers SHALL appear in any action line
- **AND** visited counts and normalized coordinates SHALL still be present
- **AND** the same actions SHALL render the same lines, marker aside, as in a run with the MOP contribution mounted

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
