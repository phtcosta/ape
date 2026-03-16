## Purpose

`ApePromptBuilder` bridges APE-RV's internal data structures (GUITree, State, ModelAction, MopData) and the LLM's multimodal input format. It constructs a prompt that gives the LLM a screenshot of the current screen, a structured list of available actions with their widget types, visited counts, and MOP reachability annotations, recent action history with results, and an instruction to select the most promising action for exploration.

The prompt design follows a principle from the rvsmart experiments: the LLM performs best when it receives both visual (screenshot) and structured (widget list) information. The screenshot lets the LLM understand the visual layout and identify UI elements that the accessibility tree may miss — including **dynamic elements** (WebView content, custom-drawn views, canvas elements, dynamically loaded content) that do not appear in the UIAutomator dump. The widget list provides precise bounds, action types, visited counts, and MOP markers that the LLM cannot infer from pixels alone. Combining both reduces coordinate errors (from ~70% with image-only to ~84% with image+text) and enables MOP-aware reasoning.

A key advantage of the LLM integration is the ability to perform **semantic clicks on elements invisible to UIAutomator**. While SATA can only interact with widgets present in the accessibility tree, the LLM sees the screenshot and can identify and click on visual elements that have no corresponding accessibility node — buttons inside WebViews, custom-rendered UI, dynamically injected content, etc. When the LLM targets such an element, `mapToModelAction()` may return null (no matching ModelAction), but a future enhancement could inject a raw coordinate click for these cases.

The inclusion of recent action history (last 3-5 actions with results) prevents the LLM from being "amnesic" — without history, the LLM may repeatedly suggest the same action. This is a consensus finding from the state of the art (GPTDroid, VisionDroid, rvsmart V17, LLM-Explorer all include some form of action memory).

The prompt uses Qwen3-VL's normalized coordinate space [0, 1000) for both input widget positions and LLM response coordinates. The `CoordinateNormalizer` converts between this space and device pixels. Reference: https://github.com/QwenLM/Qwen3-VL/issues/1486

---

## Data Contracts

### Input

- `GUITree tree` — current screen's accessibility tree with `GUITreeNode` hierarchy (source: `MonkeySourceApe`)
- `State state` — current abstract state with activity name and visit count (source: `Model`)
- `List<ModelAction> actions` — valid actions on current state, each optionally resolved to a `GUITreeNode` (source: `StatefulAgent`)
- `MopData mopData` — static analysis reachability data for monitored operations, may be null (source: `StatefulAgent._mopData`). When available, provides per-widget reachability to operations monitored by RV specifications (e.g., JCA cryptographic API specs, Iterator protocol specs).
- `String base64Image` — JPEG screenshot encoded as base64 (source: `ImageProcessor`)
- `List<ActionHistoryEntry> recentActions` — last 3-5 executed actions with their results (source: `StatefulAgent` action history ring buffer); each entry contains the action description (type, widget index, widget text) and result (e.g., "same screen", "new screen", "new screen, then BACK"). May be empty on first steps.

### Output

- `List<SglangClient.Message>` — ordered message list: [system message, user message with image + text]

### Side-Effects

- None (pure data transformation)

### Error

- No exceptions. If input data is incomplete (e.g., null MopData, actions with unresolved nodes, empty history), the prompt builder SHALL produce a valid prompt with reduced information (omitting MOP markers, bounds, or history section as needed).

---

## Invariants

- **INV-PRM-01**: `ApePromptBuilder.build()` SHALL always return a non-null, non-empty message list containing exactly 2 messages (system + user).
- **INV-PRM-02**: The user message SHALL always contain exactly 2 content parts: one image (`ContentPart.imageUrl`) and one text (`ContentPart.text`), in that order.
- **INV-PRM-03**: The widget list in the text content SHALL include all actions from the input list, in the same order. Each target action SHALL include its visited count and center coordinates in [0,1000) normalized space. No actions SHALL be filtered out or reordered.
- **INV-PRM-04**: MOP annotations SHALL only appear when `mopData` is non-null. When `mopData` is null, no `[DM]` or `[M]` markers SHALL appear in the widget list.

---

## ADDED Requirements

### Requirement: System Message

`ApePromptBuilder` SHALL generate a system message with role `"system"`. The system message SHALL be compact (~120 tokens), modeled on rvsmart V13 which proved effective in experiments. Verbose reasoning steps (V17-style, ~300 tokens) did not show significant improvement and waste tokens/latency.

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

**Dynamic tool schema**: The `type_text` tool SHALL be included in the system message only when the current widget list contains at least one input-capable widget (EditText, SearchView, AutoCompleteTextView). When no input widgets are present, `type_text` is omitted from the tool list. This reduces LLM confusion and prevents wasted calls on inapplicable tools. The check is performed by `ApePromptBuilder.build()` when generating the system message.

Key design choices:
- **Compact format**: ~120 tokens saves ~300 tokens/call vs verbose V17. At 85 calls/session this saves ~25K tokens and ~0.5-1s latency per call.
- **[DM]/[M] notation**: Compact markers matching rvsmart V17 (DM = direct monitored, M = transitive monitored)
- **Coordinate convention**: Explicit [0, 1000) normalized space matching Qwen3-VL's native output format
- **JSON response format**: `{"name": "<action>", "arguments": {<args>}}` — compatible with ToolCallParser's 3-level fallback
- **Dialog handling**: Single line, covers permission and error dialogs
- **type_text**: Included when input fields are available; hints for semantically valid text (email, password, domain, etc.)
- **Dynamic tool schema**: type_text omitted when no input widgets on screen, reducing token waste

#### Scenario: System message content

- **WHEN** `build()` is called with any valid inputs
- **THEN** the first message SHALL have role `"system"`
- **AND** it SHALL contain the DIALOG HANDLING, PRIORITY, and RULES sections
- **AND** it SHALL declare the `click`, `long_click`, and `back` tool schemas
- **AND** it SHALL specify that coordinates use [0, 1000) normalized space
- **AND** it SHALL explain the meaning of [DM] and [M] markers

#### Scenario: Dynamic tool schema — type_text included

- **WHEN** `build()` is called and the actions list contains at least one action targeting an EditText, SearchView, or AutoCompleteTextView
- **THEN** the system message SHALL include `type_text(x, y, text)` in the tool list
- **AND** the RULES section SHALL include text input hints

#### Scenario: Dynamic tool schema — type_text omitted

- **WHEN** `build()` is called and no action in the list targets an input-capable widget
- **THEN** `type_text` SHALL NOT appear in the tool list
- **AND** the system message SHALL list only `click`, `long_click`, and `back`

---

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

Where:
- `<index>` is the 0-based position in the actions list
- `<WidgetClass>` is the widget's Android class simple name (e.g., `Button`, `EditText`, `ImageView`)
- `<text>` is the widget's text or content-description, truncated to 50 characters; omitted if empty
- `@(<normX>,<normY>)` is the center of the widget's bounds converted to Qwen3-VL [0,1000) normalized space: `normX = (int)((centerPixelX / deviceWidth) * 1000)`, `normY = (int)((centerPixelY / deviceHeight) * 1000)`. This is the SAME coordinate space the LLM responds in — critical for consistency (follows rvagent design, avoids rvsmart's device-pixel mismatch). Omitted if node is not resolved.
- `<MOP_MARKER>` is `[DM]` (direct monitored), `[M]` (transitive monitored), or omitted if no MOP match
- `(v:<N>)` is the action's visited count in compact form

The list SHALL be preceded by a compact header: `Screen "<ActivitySimpleName>":`.

#### Scenario: Mixed action list with MOP data and visited counts

- **WHEN** `build()` is called with a state on `com.example.MainActivity` on a 1080x1920 device
- **AND** actions include BACK, MENU, a Button "Encrypt" with directMop (visited 0 times, device center 200,225), an EditText "Password" (visited 3 times, device center 225,325), and a TextView "Help" with transitiveMop (visited 1 time, device center 250,420)
- **AND** `mopData` is non-null
- **THEN** the text content SHALL contain:
  ```
  Screen "MainActivity":
  [0] BACK (key)
  [1] MENU (key)
  [2] Button "Encrypt" @(185,117) [DM] (v:0)
  [3] EditText "Password" @(208,169) (v:3)
  [4] TextView "Help" @(231,219) [M] (v:1)
  ```

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
- **THEN** the displayed text SHALL be truncated to 50 characters: `"This is a very long label that exceeds fifty char..."`

---

### Requirement: MOP Marker Annotation

When `mopData` is non-null, `ApePromptBuilder` SHALL annotate each target action with its MOP reachability level by querying `mopData.getWidget(activity, shortResourceId)` and `mopData.activityHasMop(activity)`.

| MopData Result | Marker | Meaning |
|----------------|--------|---------|
| `directMop == true` | `[DM]` | Widget directly reaches a monitored operation |
| `transitiveMop == true` (and not direct) | `[M]` | Widget transitively reaches a monitored operation |
| Widget not found, activity has MOP | (no marker) | Activity-level info not shown per-widget |
| No match | (no marker) | No monitored operations reachable |

The short resource ID SHALL be extracted from `GUITreeNode.getResourceID()` using the same transform as `MopData`: if the ID contains `":id/"`, take the substring after `":id/"`; otherwise use empty string.

Note: "Monitored operations" refers to operations being monitored by runtime verification specifications. The specification sets vary by experiment — they may be cryptographic API misuse specs (JCA), generic protocol specs (e.g., Iterator hasNext/next), or other RV specification sets. The LLM does not need to know which specification set is active; it only needs to know that [DM]/[M] widgets lead to code paths under monitoring.

#### Scenario: Direct MOP widget

- **WHEN** widget `btn_encrypt` has `directMop=true` in MopData for activity `com.example.MainActivity`
- **THEN** the action line SHALL contain `[DM]`

#### Scenario: Transitive MOP widget

- **WHEN** widget `btn_settings` has `transitiveMop=true` (and `directMop=false`) in MopData
- **THEN** the action line SHALL contain `[M]`

---

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

The format SHALL be compact with coordinates in [0,1000) normalized space (same as widget list):
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

#### Scenario: No action history (first steps)

- **WHEN** `build()` is called with an empty `recentActions` list
- **THEN** the "Recent:" section SHALL be omitted entirely

#### Scenario: type_text in history

- **WHEN** a recent action was type_text on an EditText "Domain"
- **THEN** the entry SHALL be: `- type_text @(500,487) "google.com" → same`

---

### Requirement: Exploration Context in Prompt

The text content SHALL include a compact one-line exploration context after the action history (or after the widget list if history is omitted):

- New state with MOP: `NEW state. <N>/<M> MOP.`
- Revisited state with MOP: `Visited <N>x. <N>/<M> MOP.`
- New state without MOP: `NEW state.`
- Revisited state without MOP: `Visited <N>x.`

#### Scenario: New state with MOP data

- **WHEN** the state is being visited for the first time
- **AND** 3 out of 8 actions have MOP markers
- **THEN** the text SHALL contain: `NEW state. 3/8 MOP.`

#### Scenario: Revisited state without MOP

- **WHEN** the state has been visited 5 times
- **AND** `mopData` is null
- **THEN** the text SHALL contain: `Visited 5x.`
