## Purpose

`ApePromptBuilder` bridges APE-RV's internal data structures (State, ModelAction, the action history, and — through the mounted groups' contributions — `StaticGuiModel` and the run's `MopContext`) and the LLM's multimodal input format. It constructs a prompt that gives the LLM a screenshot of the current screen, a structured list of available actions with their widget types, visited counts (in the variants that show them), MOP markers that state the scoring's verdict on each line's actions (in a cell with MOP), recent action history with each action's own result, and an instruction to select the most promising action for exploration. The `v13` and `v17` lists show one line per widget rather than one per action. The `GUITree` is not an input of `build`: the only thing the builder took from it was the dimensions to normalize by, and it now receives the display dimensions of the decision as arguments.

The prompt is built from the mounted behaviour groups (design D-B1, D-A3): `ApePromptBuilder` renders a base prompt and appends, at fixed extension points, what each mounted `PromptContribution` returns, STATIC before MOP. `RunContext` mounts `StaticPromptContribution` iff `Feature.STATIC` is in the plan and `MopPromptContribution` iff `Feature.MOP` is. MOP contributes the line markers, the `N/M MOP.` count, the `MOP NAVIGATION:` heading of `v17` and the MOP sentences of the system message; STATIC contributes the widget metadata suffix, read from `StaticGuiModel`. Without a group its contribution does not exist, so in a cell with LLM and no MOP no MOP instruction reaches the model, and the MOP-free wording of each sentence is the MOP sentence with its MOP clause deleted. The visit context (`NEW state.` / `Visited <N>x.`) is not MOP and appears in every cell (in `v17` under the neutral heading `NAVIGATION:` when MOP is absent).

The prompt design follows a principle from the rvsmart experiments: the LLM performs best when it receives both visual (screenshot) and structured (widget list) information. The screenshot lets the LLM understand the visual layout and identify UI elements that the accessibility tree may miss — including **dynamic elements** (WebView content, custom-drawn views, canvas elements, dynamically loaded content) that do not appear in the UIAutomator dump. The widget list provides precise bounds, action types, visited counts, and (with MOP) MOP markers that the LLM cannot infer from pixels alone. Combining both reduces coordinate errors (from ~70% with image-only to ~84% with image+text) and, in a cell with MOP, enables MOP-aware reasoning.

A key advantage of the LLM integration is the ability to perform **semantic clicks on elements invisible to UIAutomator**. While SATA can only interact with widgets present in the accessibility tree, the LLM sees the screenshot and can identify and click on visual elements that have no corresponding accessibility node — buttons inside WebViews, custom-rendered UI, dynamically injected content, etc. When the LLM targets such an element, `CoordinateMapper.map` finds no widget action containing or near the point and, for a `click` or `long_click` answer, synthesizes an off-tree `LlmTapAction` of type `MODEL_LLM_TAP` carrying the LLM's pixel, which the jar executes as a coordinate tap (`llm-routing` INV-RTR-03, "Coordinate-to-ModelAction Mapping").

The inclusion of recent action history (last 3-5 actions with results) prevents the LLM from being "amnesic" — without history, the LLM may repeatedly suggest the same action. This is a consensus finding from the state of the art (GPTDroid, VisionDroid, rvsmart V17, LLM-Explorer all include some form of action memory).

The prompt uses Qwen3-VL's normalized coordinate space [0, 1000) for both input widget positions and LLM response coordinates. The `CoordinateNormalizer` converts between this space and device pixels: `toNormalized` from pixel to [0,1000) for every coordinate the prompt shows, `normalize` back to a pixel for the answer. There is a single base per decision: the display dimensions that `ScreenshotStep.deviceDimensions` returns, which are the dimensions the screenshot is captured at and the answer is multiplied by. The element list and the action history both divide by that pair, at build time, never by the active window's root node or a constant ("Single Coordinate Base", INV-PRM-06). A listed coordinate is the centre of the part of the widget that is on screen, the point the jar taps for it; a widget with no part on screen lists none. Reference: https://github.com/QwenLM/Qwen3-VL/issues/1486

## Data Contracts

### Input

- `int deviceWidth`, `int deviceHeight` — the display dimensions of the decision, positive (source: `LlmEngine`, from `ScreenshotStep.deviceDimensions(tree)` in step 2 of the Action Selection Pipeline, `llm-routing`). The screenshot is captured at them and the answer is mapped with them; every [0,1000) coordinate in the prompt is computed from them ("Single Coordinate Base"). `build` takes no `GUITree`.
- `State state` — current abstract state with activity name and visit count (source: `Model`)
- `List<ModelAction> actions` — valid actions on current state, each optionally resolved to a `GUITreeNode` (source: `StatefulAgent`)
- `List<PromptContribution> contributions` — the mounted groups' contributions, given to the `ApePromptBuilder` constructor (source: `RunContext`, from the plan): `StaticPromptContribution` (iff `Feature.STATIC`), bound to the run's `StaticGuiModel`, and `MopPromptContribution` (iff `Feature.MOP`), bound to the run's `MopContext` (the loaded `MopData`, the scoring mode, both modes' weights and the run's `MopRetirement`) through `LlmEngine.bindMopContext`. When bound, the MOP contribution provides per-widget reachability to operations monitored by RV specifications (e.g., JCA cryptographic API specs, Iterator protocol specs); unbound, it contributes nothing. `build` takes no MOP or static argument.
- `String base64Image` — JPEG screenshot encoded as base64 (source: `ImageProcessor`)
- `List<ActionHistoryEntry> recentActions` — last 3-5 selected model actions with their results (source: `StatefulAgent` action history ring buffer, `exploration` "StatefulAgent — Action History Ring Buffer"); each entry carries the action type (`click`, `long_click`, `type_text`, `back`, `menu`), the widget's class simple name and text-or-content-description, the executed widget's **pixel bounds** (`Rect bounds`, not a normalized pair; a one-pixel rectangle at the tap's pixel for an off-tree `MODEL_LLM_TAP`; null for `back` and `menu`), the typed text for `type_text`, and the result of that entry's own action (`same`, `new screen`, `previous screen`; empty until the next state update fills it). The builder normalizes the bounds at build time ("Action History"). May be empty on first steps.

### Output

- `List<SglangClient.Message>` — ordered message list: [system message, user message with image + text]

### Side-Effects

- None (pure data transformation)

### Error

- No exceptions. If input data is incomplete (e.g., an unbound contribution, actions with unresolved nodes, empty history), the prompt builder SHALL produce a valid prompt with reduced information (omitting MOP markers, coordinates, or history section as needed).

---

## Invariants

- **INV-PRM-01**: `ApePromptBuilder.build()` SHALL always return a non-null, non-empty message list containing exactly 2 messages (system + user).
- **INV-PRM-02**: The user message SHALL always contain exactly 2 content parts: one image (`ContentPart.imageUrl`) and one text (`ContentPart.text`), in that order.
- **INV-PRM-03**: The element list of the shared line formatter (`ape_current`, `ape_reasoning`, `compact_v1`) SHALL include all actions from the input list, in the same order, and no action SHALL be filtered out or reordered; each target action's line SHALL include its visited count. The `v13` and `v17` lists SHALL list the target-requiring actions only, one line per widget, in the order of each widget's first action, with no visited count ("Element List of the v13 and v17 Variants"). In every variant, a listed coordinate is the centre of the widget's visible part in [0,1000) normalized space (INV-PRM-06), and it SHALL be omitted for an unresolved node and for a node wholly outside the display ("Single Coordinate Base").
- **INV-PRM-04**: MOP annotations SHALL only appear when `Feature.MOP` is in the plan. Without it, no `[DM]` or `[M]` markers SHALL appear in the widget list.
- **INV-PRM-05**: Every target-action element line whose node carries at least one of text / content-description / resource-id SHALL render a non-empty identifier (fallback order: text → content-description → short resource-id); rendered identifiers SHALL contain no unescaped `\n`/`\r`.
- **INV-PRM-06**: Every [0,1000) coordinate in one prompt (element-list entries in every variant that lists coordinates, and every action-history entry) SHALL be computed by `CoordinateNormalizer.toNormalized` from a pixel position inside the display, the centre of the widget's visible part ("Single Coordinate Base"), and the `{width, height}` that `ScreenshotStep.deviceDimensions` returned for the same decision, the pair the screenshot was captured at and the answer is mapped with. No other divisor SHALL reach the prompt. In particular, the root node's bounds and any constant fallback inside `ApePromptBuilder` SHALL NOT be a source.
- **INV-PRM-07**: A prompt built in a run whose plan does not contain `Feature.MOP` SHALL contain none of `[DM]`, `[M]`, `MOP`, `monitored operations` in any text the builder or a contribution writes: the system message, the line markers and suffixes, the exploration context and every section. Text read from the screen (widget text, content-descriptions, resource ids) is not covered.
- **INV-PRM-08**: `ApePromptBuilder` SHALL NOT test whether MOP or static data is present (`mop != null`, `getMopData() != null`, a group feature) to decide what to render. It SHALL render the base prompt and append, at each extension point, what the mounted `PromptContribution`s return, in a fixed order: STATIC before MOP.
- **INV-PRM-09**: The static widget metadata suffix SHALL come only from the STATIC contribution, which reads `StaticGuiModel`; no MOP contribution and no `MopData` read SHALL produce it.

---

## Requirements

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

### Requirement: MOP Marker Annotation

When `Feature.MOP` is in the plan, the MOP prompt contribution (`MopPromptContribution.lineMarker`, "Prompt Built From Group Contributions") SHALL mark each element-list line with the MOP verdict of the **line's actions** (design D22). In the shared line formatter (`ape_current`, `ape_reasoning`, `compact_v1`), a line has one action, the one it renders. In `v13` and `v17`, a line's actions are all the target-requiring actions of its widget ("Element List of the v13 and v17 Variants", design D10). When `Feature.MOP` is not in the plan, the contribution is not mounted and no line carries a marker (INV-PRM-04); `ApePromptBuilder` does not test for MOP data, it renders what the mounted contributions return.

For each of the line's actions that has a resolved node, the verdict SHALL come from `MopData.lookup(activity, node, event)` (design D17). `event` is `MopScorer.eventTypeOf(action)`. The lookup is applied to the same containment nodes the scoring pass uses: the node itself, up to two ancestors, and its descendants up to two levels down. The best result wins: the strongest flag under `flag`, and the smallest unretired distance under `distance`. The lookup reads the node's handler stamp for the event first, if `handlers` lists the stamp's class. Otherwise it reads the id key `(activity, shortId, event)`, with the per-event fallback to the aggregate flags (INV-MOP-14). The short resource id is `MopData.extractShortId(GUITreeNode.getResourceID())`: the substring after `":id/"`, or the empty string. An action with no resolved node contributes nothing.

The verdict depends on the run's `ape.mopScoring` mode (design D18):

| Line's actions, under the active mode | Marker |
|---|---|
| At least one action is **direct** and has a **positive MOP weight**. Under `flag`: the lookup gives a direct flag for the action's event and `ape.mopWeightDirect > 0`. Under `distance`: the lookup has an unretired target at `d = 0` and `ape.mopWeightD1 > 0`. | `[DM]` |
| Otherwise, at least one action has a **positive MOP weight**: the weight `MopScorer.score` gives it under the active mode is positive (design D18), before the tie split. | `[M]` |
| Otherwise. This covers: lookup source `NONE`; a flag of `none` for the action's event; a stamped handler listed as reaching nothing; every target at `d ≥ 4` or retired; no resolved node. | (no marker) |

A line therefore carries a marker if and only if the score treats one of its actions as MOP; an arm that zeroes the MOP weights shows no marker. The marker follows the undivided weight (`mopWeight`), not the split boost. (rv-android's `mop_off_llm_off` zeroes them but runs without the LLM, so it has no prompt; no current arm zeroes the weights with the LLM on.) The two never disagree on the reason, because they use the same lookup and the same containment. Under `distance`, a target retires after `ape.mopRetireAfter` first interactions (design D19). A retired target stops counting toward the marker, so a line whose only targets have retired loses its marker from the next prompt on. Under `flag`, nothing retires.

The marker SHALL NOT query `activityHasMop`. A MOP-bearing activity does not mark any line by itself. This matches the score, which has had no activity-level fallback since mop-discriminative-boost.

Until this change, the requirement said the marker queried `mopData.getWidget(activity, shortResourceId)` and `mopData.activityHasMop(activity)`. The code never called `activityHasMop`. `buildMopMarker` (`ApePromptBuilder.java:456-469`) read the **aggregate** flags of the node's own id key, which are the OR over all events (`MopData.java:374-375`). It used no event, no containment and no stamp. The score reads the per-event flag with containment (`MopWidgetPass.mopBoostWithContainment`). Review R9 (rv-android `docs/20261006_revisao_analise_plano_guia_mop.md`) lists three cases where they disagreed:
- a container or child boosted through containment showed no marker;
- a widget direct only for long-click showed `[DM]` on its click line, while the click got the transitive weight or nothing;
- an event flagged `none` still showed the marker of another event, while the boost was 0.

With D10's lines joining several actions, and with per-event distances and per-node stamps, a rule tied to one node cannot hold. Even under `flag`, the new rule adds and removes markers in the `v13` prompt beyond Part A's changes.

The vocabulary (`[DM]`, `[M]`) is unchanged. The system-message sentences that cite it are themselves part of the MOP contribution, so a prompt carries the vocabulary's explanation exactly when it can carry a marker ("System Message", "System Message of the v13 Variant").

Note: "Monitored operations" refers to operations being monitored by runtime verification specifications. The specification sets vary by experiment — they may be cryptographic API misuse specs (JCA), generic protocol specs (e.g., Iterator hasNext/next), or other RV specification sets. The LLM does not need to know which specification set is active; it only needs to know that [DM]/[M] widgets lead to code paths under monitoring.

#### Scenario: Direct MOP widget

- **WHEN** `ape.mopScoring=flag`
- **AND** widget `btn_encrypt` has `mop: {"click": "direct"}` in MopData for activity `com.example.MainActivity`, and its node carries no stamp
- **AND** the line's action is a `MODEL_CLICK` on it
- **THEN** the action line SHALL contain `[DM]`

#### Scenario: Transitive MOP widget

- **WHEN** `ape.mopScoring=flag`
- **AND** widget `btn_settings` has `mop: {"click": "transitive"}` in MopData, and its node carries no stamp
- **AND** the line's action is a `MODEL_CLICK` on it
- **THEN** the action line SHALL contain `[M]`

#### Scenario: widget flagged only through containment is marked

- **WHEN** `ape.mopScoring=flag`
- **AND** a `MODEL_CLICK` targets a `TextView` whose resource id `row_label` is not in MopData
- **AND** that node's parent has resource id `item_row`, with `mop: {"click": "direct"}`
- **THEN** the scoring pass gives the action `mopWeightDirect` through containment
- **AND** the `TextView`'s line SHALL contain `[DM]` (at `e93dea86` it had no marker)

#### Scenario: long-click-only widget flagged only for click is not marked

- **WHEN** `ape.mopScoring=flag`
- **AND** widget `btn_delete` has `mop: {"click": "direct", "longClick": "none"}`, and no containment node is flagged
- **AND** the widget's only action is a `MODEL_LONG_CLICK`
- **THEN** its line SHALL carry no marker (at `e93dea86` it showed `[DM]` from the aggregate flag)
- **AND** the action's MOP weight SHALL be 0

#### Scenario: an event flagged none takes no marker from another event

- **WHEN** `ape.mopScoring=flag`, the variant is `ape_current`, and no containment node is flagged
- **AND** widget `btn_a` has `mop: {"click": "none", "longClick": "direct"}`, and its line renders a `MODEL_CLICK`
- **AND** widget `btn_b` has `mop: {"click": "transitive", "longClick": "direct"}`, and its line renders a `MODEL_CLICK`
- **THEN** `btn_a`'s line SHALL carry no marker
- **AND** `btn_b`'s line SHALL carry `[M]`, not `[DM]`

#### Scenario: a stamped handler that reaches nothing removes the marker

- **WHEN** `ape.mopScoring=flag`
- **AND** a node's resource id `btn_save` has `mop: {"click": "direct"}`
- **AND** the node's `rvsec.click` stamp is `com.example.SaveListener`, and `handlers` lists that class with `mop: {"click": "none"}`
- **AND** the line's action is a `MODEL_CLICK` on that node, and no containment node is flagged
- **THEN** the lookup source SHALL be `STAMP`
- **AND** the line SHALL carry no marker, even though the id key is flagged direct

#### Scenario: a stamp naming an unlisted class falls back to the id key

- **WHEN** `ape.mopScoring=flag`
- **AND** a node's `rvsec.click` stamp is `androidx.appcompat.app.AppCompatViewInflater$DeclaredOnClickListener`, which `handlers` does not list
- **AND** its resource id `btn_encrypt` has `mop: {"click": "direct"}`
- **THEN** the line of its `MODEL_CLICK` SHALL contain `[DM]`

#### Scenario: distance mode marks by the nearest unretired target

- **WHEN** `ape.mopScoring=distance`, no target is retired, and no containment node carries a distance
- **AND** three `MODEL_CLICK` lines have click lookups with `dist` `[[4, 0]]`, `[[2, 1]]` and `[[7, 5]]`
- **THEN** the first line SHALL carry `[DM]`
- **AND** the second SHALL carry `[M]`, even though `d = 1` takes the same weight as `d = 0` (`ape.mopWeightD1`)
- **AND** the third SHALL carry no marker

#### Scenario: a line whose only target retired loses its marker

- **WHEN** `ape.mopScoring=distance`
- **AND** a line's only action has the click lookup `dist: [[4, 0]]`, with no other pair through containment
- **AND** the line carries `[DM]` while target 4 is unretired
- **AND** target 4 then retires, because its counter reaches `ape.mopRetireAfter` (design D19)
- **THEN** the next prompt SHALL render that line with no marker

#### Scenario: a collapsed click and long-click line is direct if either action is

- **WHEN** `ape.mopScoring=flag` and variant `v13` builds the prompt
- **AND** an EditText has a `MODEL_CLICK` and a `MODEL_LONG_CLICK` that collapse into one line (design D10)
- **AND** its id key has `mop: {"click": "none", "longClick": "direct"}`
- **THEN** its single line SHALL carry `[DM]`
- **AND** with `mop: {"click": "transitive", "longClick": "none"}` instead, the line SHALL carry `[M]`

#### Scenario: no marker in a cell without MOP

- **WHEN** cell `as_llm` builds a prompt for a screen whose `btn_encrypt` has `mop: {"click": "direct"}` in the loaded artifact
- **THEN** no line SHALL carry `[DM]` or `[M]`, because `Feature.MOP` is not in the plan and the MOP contribution is not mounted

### Requirement: Image Content Part

The user message SHALL include the screenshot as a `ContentPart.imageUrl()` with a base64 data URI in the format `data:image/jpeg;base64,<base64Image>`. This content part SHALL appear before the text content part in the message's content parts list.

The screenshot provides the LLM with visual context that the accessibility tree may miss, including:
- Custom-drawn UI elements not in the UIAutomator dump
- WebView content rendered by JavaScript
- Dynamically loaded images and icons
- Visual layout and spacing that inform which elements are most prominent

#### Scenario: Image data URI format

- **WHEN** `build()` is called with a base64Image string
- **THEN** the first content part of the user message SHALL be an image URL with value `"data:image/jpeg;base64,<base64Image>"`

---

### Requirement: Action History

The text content SHALL include a section showing the last 3-5 executed actions with their results, positioned after the widget list and before the exploration context. This section prevents the LLM from repeatedly suggesting the same action.

The format SHALL be compact with coordinates in [0,1000) normalized space, computed exactly as the widget list computes them:
```
Recent:
- <action_type> @(<normX>,<normY>) <WidgetClass> "<text>" → <result>
```

Where `<result>` is a brief outcome of that entry's own action: `same`, `new screen`, `previous screen`. It compares the state the action produced with the state the action was taken from ("same") and with the state before that ("previous screen"); else "new screen". `StatefulAgent` fills it at the state update that follows the action, before the next prompt is built (`exploration` "StatefulAgent — Action History Ring Buffer"). An entry whose result is empty renders without ` → <result>`, as `formatHistoryEntry` already does for an empty result.

For `type_text` actions, include the typed text: `- type_text @(x,y) "typed text" → result`.

If `recentActions` is empty (first steps), this section SHALL be omitted entirely. Maximum 5 entries.

Each `ActionHistoryEntry` SHALL carry the executed widget's **pixel bounds** (`bounds`, a copy of the resolved node's `getBoundsInScreen()`, recorded by `StatefulAgent.recordActionHistory` when the action is selected, before it is dispatched), not a pre-normalized pair. `ApePromptBuilder` SHALL render it at build time with the same visible-centre rule and the same `CoordinateNormalizer.toNormalized` call as the list, with the dimensions `build` received; an entry whose bounds are null or do not intersect the display renders without coordinates, so no entry shows a value outside [0,999]. `StatefulAgent` SHALL NOT normalize and SHALL NOT read the display for the history. Normalizing at build time is what guarantees one base per prompt (INV-PRM-06): the list and the history are computed by the same call with the same divisor, so a widget listed and clicked in the same prompt shows one coordinate in both.

An entry for an action with no resolved node SHALL record what ran:
- an off-tree `MODEL_LLM_TAP` records `click`, or `long_click` when `LlmTapAction.isLongClick()` is true, with a one-pixel rectangle at the tap's own pixel (`[x,y][x+1,y+1]` from `getPixelX()`/`getPixelY()`, whose visible centre is `(x,y)`) as its bounds, and no class or text;
- a `MODEL_MENU` press records `menu` with no bounds and, like `back`, is rendered without coordinates.

No entry SHALL render `@(0,0)` because its action had no node.

An entry for a node action SHALL name its widget as the element list does. `<WidgetClass>` is the class simple name, produced by the same helper the list uses. `"<text>"` is the widget's text, else its content-description, produced by the first two steps of the list's identifier fallback (INV-PRM-05, "Widget List Generation"); an entry with neither renders no `"<text>"`. The list's third step, `id=<shortId>`, is not applied to the history: that is an improvement pending the author's confirmation (`pending.md` P2). Until this change an entry carried the full class name (`android.widget.Button`) and the text only, against the simple name and the content-description fallback that `exploration` "StatefulAgent — Action History Ring Buffer" has specified since gh6 (design D13).

`formatHistoryEntry` SHALL be the only place an entry becomes text. Every variant that shows a history renders through it: the default branch (`ape_current`, `ape_reasoning`, `compact_v1`), `v13`, `visual_only`, and `v17`. `v17` uses the formatter's compact form, one token per entry joined by `, `: `<action_type>@(<normX>,<normY>)` for an entry with coordinates, and `<action_type>` alone for `back` and `menu`. The position stated above (after the widget list) holds for every variant except `v17`, whose compact `Recent:` line precedes `ELEMENTS:`, as it did before this change.

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

#### Scenario: history entry of a widget cut by or outside the display
- **WHEN** the history holds a click on a widget with bounds `[0,1740][1080,1974]` and a click on a widget with bounds `[0,1800][1080,1900]`
- **AND** `build` is called with `deviceWidth=1080`, `deviceHeight=1794`
- **THEN** the first entry SHALL render `@(500,984)`, the centre of its visible part `[0,1740][1080,1794]`
- **AND** the second entry SHALL render without `@(`
- **AND** no history entry SHALL show a coordinate outside [0,999] (at `e93dea86` these entries read `@(500,1035)` and `@(500,1031)`)

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

#### Scenario: history entry names its widget as the list does
- **WHEN** the most recent executed action is a click on an `android.widget.ImageButton` with empty text, content-description `"Settings"` and bounds `[960,70][1060,170]`
- **AND** `build` is called with `deviceWidth=1080`, `deviceHeight=1794`
- **THEN** its entry SHALL render `- click @(935,66) ImageButton "Settings" → <result>`
- **AND** SHALL NOT render `android.widget.ImageButton`
- **AND** the element list line of the same widget SHALL show the same class and the same `"Settings"`

#### Scenario: v17 history goes through the formatter
- **WHEN** variant `v17` builds a prompt whose history holds a click on a widget centred at `(540,178)` followed by a `back`
- **AND** `build` is called with `deviceWidth=1080`, `deviceHeight=1794`
- **THEN** its history line SHALL be `Recent: click@(500,99), back`
- **AND** SHALL NOT contain `back@(0,0)`

#### Scenario: type_text in history

- **WHEN** a recent action was type_text on an EditText "Domain"
- **THEN** the entry SHALL be: `- type_text @(500,487) "google.com" → same`

### Requirement: Exploration Context in Prompt

The text content SHALL include a compact one-line exploration context after the action history (or after the widget list if history is omitted):

- New state with MOP: `NEW state. <N>/<M> MOP.`
- Revisited state with MOP: `Visited <N>x. <N>/<M> MOP.`
- New state without MOP: `NEW state.`
- Revisited state without MOP: `Visited <N>x.`

The visit part (`NEW state.` / `Visited <N>x.`) is the builder's own: it is not a MOP signal and SHALL be rendered in every cell, with or without `Feature.MOP`. The count ` <N>/<M> MOP.` is the MOP contribution's context line (`MopPromptContribution.contextLine`), appended exactly when `Feature.MOP` is in the plan.

`<M>` is the number of target-requiring actions. `<N>` is the number of those actions that have a resolved node and are MOP under the marker's per-action test ("MOP Marker Annotation", design D22): with a positive MOP weight under the run's `ape.mopScoring` mode. The test uses the D17 lookup with containment and, under `distance`, ignores retired targets. Both counts are per action, not per line, in every variant that shows the context: the default branch, `visual_only`, and `v17`'s `MOP NAVIGATION` block. The "with MOP" forms appear exactly when `Feature.MOP` is in the plan; the builder does not test for MOP data.

In `v17`, the context is a block after the SCREEN line, and the block is rendered in every cell:
- with `Feature.MOP` it is the `MOP NAVIGATION:` block, byte-identical to the text before this change: the heading `MOP NAVIGATION:`, then the visit part followed by the count (e.g. `Visited 2x. 3/8 MOP.`). The heading is the MOP contribution's section ("Prompt Built From Group Contributions");
- without `Feature.MOP` the block keeps the visit part under the neutral heading `NAVIGATION:` (e.g. `NAVIGATION:` then `NEW state.`), with no count. No `MOP` token appears in it.

The SCREEN line (`SCREEN: <Activity> | <tested>/<widgets> elements tested | visits: <N>`) is not MOP and is unchanged in every cell; `UICoverageTracker` is untouched by this change.

Until this change, `<N>` counted actions whose node's own id key had any aggregate flag (`buildExplorationContext`, `ApePromptBuilder.java:567-612`). That test differed from both the score and the marker, in the same three ways listed in "MOP Marker Annotation". With the shared test, a line has a marker if and only if at least one of its actions is counted in `<N>`.

#### Scenario: New state with MOP data

- **WHEN** the state is being visited for the first time
- **AND** `Feature.MOP` is in the plan
- **AND** 3 out of 8 target-requiring actions are MOP under the marker's per-action test
- **THEN** the text SHALL contain: `NEW state. 3/8 MOP.`

#### Scenario: Revisited state without MOP

- **WHEN** the state has been visited 5 times
- **AND** `Feature.MOP` is not in the plan (cell `as_llm`)
- **THEN** the text SHALL contain: `Visited 5x.`

#### Scenario: the count uses the per-event test

- **WHEN** `ape.mopScoring=flag`, the variant is `ape_current`, and the state is new
- **AND** the target-requiring actions are a `MODEL_CLICK` and a `MODEL_LONG_CLICK` on widget `btn_a`, with `mop: {"click": "none", "longClick": "direct"}`, and a `MODEL_CLICK` on an unflagged Button, with no containment node flagged
- **THEN** the text SHALL contain `NEW state. 1/3 MOP.` (at `e93dea86`: `2/3`)

#### Scenario: a retired target leaves the count

- **WHEN** `ape.mopScoring=distance`
- **AND** the only MOP action of a revisited state has the lookup `dist: [[4, 0]]`
- **AND** target 4 has retired
- **THEN** the context SHALL count that action out of `<N>`

#### Scenario: v17 without MOP keeps the visit context under NAVIGATION

- **WHEN** variant `v17` builds a prompt in cell `as_llm` for a new state whose screen lists 4 widgets, none visited
- **THEN** the user text SHALL contain the line `NAVIGATION:` followed by the line `NEW state.`
- **AND** it SHALL NOT contain `MOP NAVIGATION:`, `MOP.` or any other `MOP` token
- **AND** its SCREEN line SHALL end `0/4 elements tested | visits: 0`, as in a cell with `Feature.MOP`
- **AND** its last line SHALL be `Select action. Prioritize navigation to new screens.`

#### Scenario: v17 with MOP keeps today's MOP NAVIGATION block

- **WHEN** variant `v17` builds a prompt in cell `as_mop_llm` for a state visited 2 times where 3 of 8 target-requiring actions are MOP under the marker's per-action test
- **THEN** the user text SHALL contain the line `MOP NAVIGATION:` followed by the line `Visited 2x. 3/8 MOP.`, byte-identical to the block the builder wrote before this change for the same state and actions

### Requirement: Single Coordinate Base

`ApePromptBuilder.build` SHALL receive the device dimensions as arguments (`int deviceWidth, int deviceHeight`) from `LlmEngine`, which obtained them from `ScreenshotStep.deviceDimensions(tree)` in step 2 of the Action Selection Pipeline (`llm-routing`). The builder SHALL NOT determine dimensions itself, and SHALL NOT read the `GUITree` for that purpose. Dimensions were the tree's only use in `build`, so the `GUITree` parameter SHALL NOT exist (SDD-P3).

Every [0,1000) coordinate the builder renders SHALL be produced by `CoordinateNormalizer.toNormalized(pixelX, pixelY, deviceWidth, deviceHeight)`. `ApePromptBuilder` SHALL contain no inline normalization arithmetic: the shared line formatter (variants `ape_current`, `ape_reasoning`, `compact_v1`), the `v13` builder, the `v17` builder, and the history formatter SHALL all call that one primitive.

The pixel a widget is listed at SHALL be the centre of its **visible part**: its `getBoundsInScreen()` intersected with the display rectangle `[0, deviceWidth) × [0, deviceHeight)` (`Rect.intersect`), then `Rect.centerX()`/`Rect.centerY()`. For a widget wholly on screen that is the centre of its bounds. For a widget cut by the display edge (a list item half behind the navigation bar, an off-canvas menu partly slid in) it is the centre of the part the model can see in the screenshot. This is the point the jar itself taps for a node action: `MonkeySourceApe.generateClickEventAt` taps the centre of the node's bounds intersected with the visible window, and drops an action whose node does not intersect it (`exploration` "Off-Screen Action Handling"; the code labels the drop INV-EXPL-19). A widget for which `Rect.intersect` finds no intersection, i.e. one entirely off screen, has no pixel the model can point at and that the jar would tap, so its line SHALL omit `@(x,y)`, exactly as for an unresolved node below. `Rect.intersect` compares edges, not areas: a zero-width or zero-height node lying inside the display intersects it and SHALL be listed at the centre of its line. The dispatcher's visibility test accepts it too, and its actions are invalid anyway, because `MonkeySourceApe.validateBounds` rejects an empty node. Its line still appears: whether off-screen actions belong in the list at all is the separate list-curation question. One helper computes this pixel for the list sites and the history formatter alike, so a widget and the history entry of a click on it show one coordinate.

At `e93dea86` the list used the centre of the whole bounds, clamped to the display edge by the arithmetic. In E6 arm 4, 21,938 listed lines (2.0 %, in 3.6 % of prompts) belonged to a node entirely off screen, and 58,765 (5.3 %) to a node cut by the display edge. Another 1,142 lines belonged to a zero-height node inside the display, which this rule keeps listed with a coordinate (`evidence/dup_identity_e6.out`).

Of 53,352 copied answers, 227 copied an entry with no visible area: 200 nodes entirely off screen and 27 zero-height nodes. All 27, and 101 of the 200, were actions APE had already marked invalid. As run, the boundary bands rejected 191 of the 227 (the bottom band 174, the top band 17 nodes above the display), and 3 reached their widget (`evidence/coordinate_counterfactual.out`, transitions S0 → S4). Under the display base with the re-derived band but the clamped centre (scenario S3), they would map to another widget (103), tap off-tree at the edge (83), be rejected by the top band (17), or snap to the intended node (24). Each of those 24 actions is one the dispatcher then drops as off-screen.

Holding the copied entry fixed, the share of copies that map to their widget is 87.1 % under this rule against 87.2 % with the clamped centre. The difference is those 24 snaps and one copy of an edge-cut entry that now maps to a neighbour (`evidence/coordinate_counterfactual.out`, scenarios S3 and S4, and §7). 21 of the 24 snaps are created by removing the bottom band (`llm-routing` INV-RTR-22). Their nodes lie below the display, in the strip behind the navigation bar, which APE marks valid because the node overlaps the 1920-high root. A copy of their clamped `@(x,999)` reaches them at y = 1792. The other 3 are one landscape node behind the side navigation bar, reached at x = 1792, as they were as run. A dropped action is still marked visited and still counts a dead-pair strike, so listing no coordinate for these nodes removes wasted steps rather than costing hits.

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

### Requirement: System Message of the v13 Variant

When the prompt variant is `v13` and `Feature.MOP` is in the plan, `ApePromptBuilder` SHALL produce exactly this system message, with the `type_text` line present only when "System Message" includes `type_text` (an input-capable widget among the actions):

```
You are an Android UI testing agent. Your task is to explore the app by interacting with UI elements.
DIALOG HANDLING: If you see a permission dialog, click Allow/Accept/OK.
  If you see an error or modal dialog, dismiss it before any other action.
PRIORITY: [DM]/[M] elements > navigation to new screens.
Available actions:
  click(x, y) — tap an element at normalized coordinates [0,1000)
  long_click(x, y) — long press at normalized coordinates
  type_text(x, y, text) — type text into an input field
  back() — press the system back button
RULE: Do not click the same position twice in a row.
Respond with exactly one action as JSON: {"name": "<action>", "arguments": {<args>}}
```

When `Feature.MOP` is not in the plan, the message SHALL be the same text with the MOP clause deleted from the `PRIORITY` line, which then reads:

```
PRIORITY: navigation to new screens.
```

The clause `[DM]/[M] elements > ` is the MOP contribution's system sentence for `v13` (`MopPromptContribution.systemSentence("v13")`); no other line of the `v13` message names MOP.

The `v13` system message SHALL NOT rank elements by how often they were visited, because the `v13` user text shows no visit count. Until this change its `PRIORITY` line ended `> unvisited elements > visited elements.`, a ranking the model could not apply from what the prompt shows; the other variants show visits (`(v:N)` in the shared line formatter, status tags in `v17`). The main spec's "System Message" requirement gives only the `ape_current` text, so this requirement is the first statement of the `v13` text.

#### Scenario: v13 PRIORITY line has no visit ranks
- **WHEN** variant `v13` builds a prompt in a cell with `Feature.MOP` (`as_mop_llm`)
- **THEN** the system message SHALL contain the line `PRIORITY: [DM]/[M] elements > navigation to new screens.`
- **AND** it SHALL NOT contain `unvisited` or `visited`
- **AND** in a cell without `Feature.MOP` (`as_llm`) the line SHALL be `PRIORITY: navigation to new screens.` and the message SHALL contain neither `[DM]` nor `[M]`

#### Scenario: v13 system message offers type_text only with an input widget
- **WHEN** variant `v13` builds a prompt whose actions include no input-capable widget
- **THEN** the system message SHALL NOT contain `type_text`
- **AND** every other line SHALL be as written above

### Requirement: Element List of the v13 and v17 Variants

The `v13` (`UI elements:`) and `v17` (`ELEMENTS:`) element lists SHALL render **one line per widget**, not one line per action. A widget is the pair (target `Name`, resolved-node bounds) of a target-requiring action: two target-requiring actions whose `getTarget()` values are equal and whose resolved nodes' `getBoundsInScreen()` have the same four edges are one widget and share one line. The line is rendered from the resolved node of the widget's first action. Lines SHALL appear in the order of each widget's first target-requiring action in `actions`. An action whose node is not resolved, or whose bounds cannot be read, SHALL keep a line of its own, with no `@(x,y)` ("Single Coordinate Base").

No line SHALL carry an ordinal prefix (`1. `, `2. `, …). Each line starts, after its indentation, with the widget's class. The rest of the line keeps its fields: identifier, `@(x,y)` per INV-PRM-06, MOP marker (only when `Feature.MOP` is in the plan), static metadata (only when `Feature.STATIC` is in the plan), and in `v17` the status tag. The MOP marker is computed from all of the widget's target-requiring actions, not from the first one's node ("MOP Marker Annotation", design D22). In `v17`, the status tag SHALL be computed from the sum of `getVisitedCount()` over the widget's target-requiring actions.

The `v17` screen-info line SHALL count widgets on both sides of its ratio: the numerator is the number of listed widgets whose summed visit count is above 0, the denominator is the number of listed widgets, and the label reads `elements tested` instead of `actions tested` (`SCREEN: <Activity> | <tested>/<widgets> elements tested | visits: <N>`). The navigation block that follows (`MOP NAVIGATION:` with `Feature.MOP`, `NAVIGATION:` without it; "Exploration Context in Prompt") carries the visit part in every cell. Its count, present only with `Feature.MOP`, still counts target-requiring actions, not widgets, and its MOP test is the marker's (design D22).

Every field of a line describes the widget, so one line per action wrote identical copies of it. In E6, 25.3 % of lines (279,463) shared their resolved node's bounds with an earlier line; 274,830 of them (24.9 % of all lines) also share the target `Name` and collapse (`evidence/dup_identity_e6.out`). The other 4,633 (in 4,434 prompts) are distinct `Name`s with identical bounds, such as a container and its single child; they keep a line each, because their class or identifier differs. Conversely, 17,865 lines share a `Name` with an earlier line but resolved, by alias sampling, to a node with other bounds; they keep a line each, because they show another point of the screen.

The key is the pair, not the node's identity. A pair repeat may come from two alias nodes: `State.resolveAction` samples one alias per action, and the listing prints `[RN>1]` for 14,241 of the 274,830 E6 lines that collapse. Two such nodes carry one `Name`, one bounds rectangle and therefore one `@(x,y)`, and the mapper, which matches a coordinate against bounds, cannot tell them apart; grouping by identity would keep both lines, a duplicate the model sees. The pair is also what the trace records for every listed action (`State.printActions` prints the target and the resolved bounds), so an offline analysis can rebuild each line's actions, up to a `Name.toString` collision between two namers, which it cannot do for node identity.

No tool, parser or mapper reads the ordinal: the answer is a coordinate, matched against the state's actions, never against list lines. The `ModelAction` list, the mapping, SATA and the exploration model are unaffected, because none of them reads the prompt text.

#### Scenario: widget with a click and a long-click is listed once
- **WHEN** `actions` holds a `MODEL_CLICK` and a `MODEL_LONG_CLICK` with the same target `Name`, both resolved to an EditText `"Email"` at bounds `[0,480][1080,596]`
- **AND** variant `v13` builds the prompt with `deviceWidth=1080`, `deviceHeight=1794` and `Feature.MOP` absent from the plan
- **THEN** the element list SHALL contain exactly one line for that EditText: `  EditText "Email" @(500,299)`

#### Scenario: two scroll directions on one widget are listed once
- **WHEN** `actions` holds `MODEL_SCROLL_BOTTOM_UP` and `MODEL_SCROLL_TOP_DOWN` with the same target `Name`, resolved to nodes with the same bounds
- **THEN** the element list SHALL contain one line for that ScrollView

#### Scenario: two alias nodes with the same bounds are listed once
- **WHEN** a `MODEL_CLICK` and a `MODEL_LONG_CLICK` share a target `Name` and resolved to two distinct `GUITreeNode`s whose bounds are both `[0,480][1080,596]`
- **THEN** the element list SHALL contain one line for them, rendered from the `MODEL_CLICK`'s node if it comes first in `actions`

#### Scenario: same bounds, different target Names keep a line each
- **WHEN** a `FrameLayout` and its only child `Button "OK"` have the same bounds and different target `Name`s, each with a `MODEL_CLICK`
- **THEN** the element list SHALL contain one line for each of them

#### Scenario: same target Name, different bounds keep a line each
- **WHEN** a `MODEL_CLICK` and a `MODEL_LONG_CLICK` share a target `Name` and resolved to nodes at `[0,480][1080,596]` and `[0,700][1080,816]`
- **THEN** the element list SHALL contain one line for each, at its own `@(x,y)`

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

### Requirement: Prompt Built From Group Contributions

`ApePromptBuilder` SHALL build the prompt from a base and from the contributions of the mounted groups. A contribution implements `llm/PromptContribution`:

```java
interface PromptContribution {
    default String systemSentence(String point) { return ""; }
    default String lineMarker(List<ModelAction> lineActions) { return ""; }
    default String lineDetail(ModelAction a) { return ""; }
    default String contextLine(State s, List<ModelAction> actions) { return ""; }
    default String section(State s, List<ModelAction> actions) { return ""; }
}
```

`systemSentence` is called once per **extension point** of a message, not once per variant: `point` is `"<variant>"` for a variant with a single MOP clause, or `"<variant>.<slot>"` where one message has several insertion points (the `v17` reasoning step, the `ELEMENTS` step's clause, the `PRIORITY` line; the `v13` and `v17` user-text closing clause). `PromptContribution`'s javadoc lists the point keys. A contribution returns the text for that point, and the builder renumbers the steps that follow an absent step. `lineMarker` receives **all the actions a line renders** (one action in the shared line formatter; every target-requiring action of the widget in `v13`/`v17`) and returns the line's single marker, so the contribution, not the builder, ranks verdicts (`[DM]` over `[M]`, "MOP Marker Annotation").

The composition root mounts at most two contributions, from the plan, in this order:

- `StaticPromptContribution`, when `Feature.STATIC` is in the plan. It contributes `lineDetail`: the widget metadata suffix (`prompt`, `spinnerMode`, `contentDescription`, `tooltipText`, `entries`, `inputType`, `hint`, each only when non-empty, with the existing caps) of the `v13` and `v17` lines, read from `StaticGuiModel` (INV-PRM-09).
- `MopPromptContribution`, when `Feature.MOP` is in the plan. It contributes `systemSentence` (the MOP clauses of the system messages, table below), `lineMarker` (`[DM]`/`[M]`, "MOP Marker Annotation"), `contextLine` (` <N>/<M> MOP.`, "Exploration Context in Prompt") and `section` (the `v17` heading `MOP NAVIGATION:`, which takes the place of the builder's neutral heading `NAVIGATION:`). The MOP clauses of the `v13`/`v17` closing instructions are `systemSentence` points (`v13.close`, `v17.close`). The visit part under either heading is the builder's own.

A group that is not in the plan has no contribution, and a contribution's absence removes its text entirely: no empty heading, no placeholder, no MOP sentence. `ApePromptBuilder` holds the list of mounted contributions and never tests for MOP or static data (INV-PRM-08). The LLM reads the MOP signal in a cell with `Feature.MOP` and `Feature.LLM` (D-A18 Q-8).

The MOP text of each variant, and the wording without `Feature.MOP`, is the existing sentence with the MOP clause deleted:

| Variant | With `Feature.MOP` | Without `Feature.MOP` |
|---|---|---|
| `ape_current`, `ape_reasoning` (system) | `PRIORITY: [DM]/[M] elements > unvisited (v:0) > visited.` | `PRIORITY: unvisited (v:0) > visited.` |
| `compact_v1` (system) | `3. Prioritize [DM]/[M] > unvisited (v:0) > visited.` | `3. Prioritize unvisited (v:0) > visited.` |
| `v13` (system) | `PRIORITY: [DM]/[M] elements > navigation to new screens.` | `PRIORITY: navigation to new screens.` |
| `v17` (system, reasoning steps) | `3. MOP CHECK: If [DM] or [M] elements are shown, prioritize them.` | line absent; the steps that follow keep their text and are renumbered `3.`–`5.` |
| `v17` (system, reasoning steps) | `5. ELEMENTS: Select unvisited element if no navigation or MOP target available.` | `4. ELEMENTS: Select unvisited element if no navigation target available.` |
| `v17` (system, PRIORITY) | `- Elements reaching monitored operations ([DM] direct / [M] transitive) > other actions` | line absent |
| `v17` (user text, navigation block) | `MOP NAVIGATION:` heading, then the visit part and the count (`NEW state. <N>/<M> MOP.` / `Visited <N>x. <N>/<M> MOP.`), byte-identical to today | `NAVIGATION:` heading, then the visit part alone (`NEW state.` / `Visited <N>x.`); the visit context is kept |
| `v17` (user text, SCREEN line) | `SCREEN: <Activity> \| <tested>/<widgets> elements tested \| visits: <N>` | unchanged (not MOP) |
| `v17` (user text, last line) | `Select action. Prioritize elements reaching monitored operations, then navigation to new screens.` | `Select action. Prioritize navigation to new screens.` |
| `v13` (user text, last line) | `Choose ONE action to explore new UI states or trigger monitored operations.` | `Choose ONE action to explore new UI states.` |
| `visual_only` (system) | no MOP text | no MOP text (the user text keeps the ` <N>/<M> MOP.` count with `Feature.MOP`, "Exploration Context in Prompt") |

#### Scenario: as_llm prompt carries no MOP text

- **WHEN** variant `v13` builds a prompt in cell `as_llm` (STATIC and LLM mounted, MOP not) for a screen with an EditText whose static metadata has `inputType="textEmailAddress"`
- **THEN** no text the builder or a contribution writes in either message SHALL contain `[DM]`, `[M]`, `MOP` or `monitored operations` (INV-PRM-07)
- **AND** the EditText's line SHALL carry ` inputType="textEmailAddress"` from the STATIC contribution

#### Scenario: as_mop_llm prompt carries the MOP contribution

- **WHEN** variant `v13` builds a prompt in cell `as_mop_llm` for a new state where 2 of 6 target-requiring actions have a positive MOP weight
- **THEN** the system message SHALL contain `PRIORITY: [DM]/[M] elements > navigation to new screens.`
- **AND** the user text SHALL contain `2/6 MOP.` and at least one line with a MOP marker

#### Scenario: v17 system message without MOP

- **WHEN** variant `v17` builds a prompt in cell `as_llm`
- **THEN** its reasoning steps SHALL read `1. SCREEN`, `2. DIALOG`, `3. NAVIGATION`, `4. ELEMENTS: Select unvisited element if no navigation target available.`, `5. ACTION`
- **AND** its PRIORITY list SHALL hold `- Actions leading to NEW screens > same-screen actions` and `- Unvisited (v:0) > visited`, and no line about monitored operations

#### Scenario: metadata comes from STATIC, not MOP

- **WHEN** a plan sets `ape.mop=true`, `ape.llm=true` and `ape.static=false`, and the artifact carries a `hint` for a widget on screen
- **THEN** the widget's `v13` line SHALL carry no metadata suffix
- **AND** it SHALL carry its MOP marker when its actions have a positive MOP weight

#### Scenario: the builder renders contributions without testing data

- **WHEN** `ApePromptBuilder` is constructed with an empty contribution list and called with a `State` whose activity is MOP-reachable in the artifact
- **THEN** the prompt SHALL be the base prompt with no marker, no count, no metadata and the MOP-free sentences
- **AND** no `MopData` or `StaticGuiModel` method SHALL be called during `build()`
