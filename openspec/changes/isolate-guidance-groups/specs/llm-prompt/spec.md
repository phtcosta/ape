# Delta: LLM Prompt (isolate-guidance-groups)

## Purpose

`isolate-guidance-groups` mounts APE-RV's behaviour groups by plan declaration (design D-B1). The prompt was not built that way: `ApePromptBuilder` wrote MOP instructions into every system message whether or not MOP data was loaded (`ApePromptBuilder.java:223`, `:245`, `:262`, `:281`, `:292`), and it decided markers, the `N/M MOP.` count, the `MOP NAVIGATION` block and the static widget metadata by testing `mop != null` (plan §2.3). An LLM cell without MOP therefore told the model to prioritise `[DM]/[M]` elements it would never see, and the dependency of the LLM on MOP (D-A3) was implicit.

This delta builds the prompt from the contributions of the mounted groups (plan §4.6, D-A3, D-A18 Q-8): MOP contributes the markers, the count, the `MOP NAVIGATION` section and the MOP sentences of the system message; STATIC contributes the widget metadata. Without a group its contribution does not exist. In a cell with MOP and LLM the LLM reads the MOP signal, as in E6 (D-A18 Q-8); in a cell with LLM and no MOP, no MOP instruction reaches the model. The MOP-free wording of each sentence is the existing sentence with its MOP clause deleted.

## Invariants

- **INV-PRM-04** (amended by `isolate-guidance-groups`): MOP annotations SHALL only appear when `Feature.MOP` is in the plan. Without it, no `[DM]` or `[M]` markers SHALL appear in the widget list.
- **INV-PRM-07**: A prompt built in a run whose plan does not contain `Feature.MOP` SHALL contain none of `[DM]`, `[M]`, `MOP`, `monitored operations` in any text the builder or a contribution writes: the system message, the line markers and suffixes, the exploration context and every section. Text read from the screen (widget text, content-descriptions, resource ids) is not covered.
- **INV-PRM-08**: `ApePromptBuilder` SHALL NOT test whether MOP or static data is present (`mop != null`, `getMopData() != null`, a group feature) to decide what to render. It SHALL render the base prompt and append, at each extension point, what the mounted `PromptContribution`s return, in a fixed order: STATIC before MOP.
- **INV-PRM-09**: The static widget metadata suffix SHALL come only from the STATIC contribution, which reads `StaticGuiModel`; no MOP contribution and no `MopData` read SHALL produce it.

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
- **AND** it SHALL contain the DIALOG HANDLING, PRIORITY, and RULES sections
- **AND** it SHALL declare the `click`, `long_click`, and `back` tool schemas
- **AND** it SHALL specify that coordinates use [0, 1000) normalized space
- **AND** it SHALL explain the meaning of [DM] and [M] markers exactly when `Feature.MOP` is in the plan, and SHALL contain neither `[DM]` nor `[M]` otherwise

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

A line therefore carries a marker if and only if the score treats one of its actions as MOP; an arm that zeroes the MOP weights shows no marker. (rv-android's `mop_off_llm_off` zeroes them but runs without the LLM, so it has no prompt; no current arm zeroes the weights with the LLM on.) The two never disagree on the reason, because they use the same lookup and the same containment. Under `distance`, a target retires after `ape.mopRetireAfter` first interactions (design D19). A retired target stops counting toward the marker, so a line whose only targets have retired loses its marker from the next prompt on. Under `flag`, nothing retires.

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

### Requirement: Exploration Context in Prompt

The text content SHALL include a compact one-line exploration context after the action history (or after the widget list if history is omitted):

- New state with MOP: `NEW state. <N>/<M> MOP.`
- Revisited state with MOP: `Visited <N>x. <N>/<M> MOP.`
- New state without MOP: `NEW state.`
- Revisited state without MOP: `Visited <N>x.`

The visit part (`NEW state.` / `Visited <N>x.`) is the builder's own. The count ` <N>/<M> MOP.` is the MOP contribution's context line (`MopPromptContribution.contextLine`), appended exactly when `Feature.MOP` is in the plan.

`<M>` is the number of target-requiring actions. `<N>` is the number of those actions that have a resolved node and are MOP under the marker's per-action test ("MOP Marker Annotation", design D22): with a positive MOP weight under the run's `ape.mopScoring` mode. The test uses the D17 lookup with containment and, under `distance`, ignores retired targets. Both counts are per action, not per line, in every variant that shows the context: the default branch, `visual_only`, and `v17`'s `MOP NAVIGATION` block. The "with MOP" forms appear exactly when `Feature.MOP` is in the plan; the builder does not test for MOP data. In `v17`, the `MOP NAVIGATION:` block is the MOP contribution's section: with `Feature.MOP` it carries the visit part and the count, and without it the block, its heading included, is not rendered ("Prompt Built From Group Contributions").

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

#### Scenario: v17 without MOP has no MOP NAVIGATION block

- **WHEN** variant `v17` builds a prompt in cell `as_llm` for a new state
- **THEN** the user text SHALL NOT contain `MOP NAVIGATION:` nor `MOP.`
- **AND** its last line SHALL be `Select action. Prioritize navigation to new screens.`

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

## ADDED Requirements

### Requirement: Prompt Built From Group Contributions

`ApePromptBuilder` SHALL build the prompt from a base and from the contributions of the mounted groups. A contribution implements `llm/PromptContribution`:

```java
interface PromptContribution {
    default String systemSentence(String variant) { return ""; }
    default String lineMarker(ModelAction a) { return ""; }
    default String lineDetail(ModelAction a) { return ""; }
    default String contextLine(State s, List<ModelAction> actions) { return ""; }
    default String section(State s, List<ModelAction> actions) { return ""; }
}
```

The composition root mounts at most two contributions, from the plan, in this order:

- `StaticPromptContribution`, when `Feature.STATIC` is in the plan. It contributes `lineDetail`: the widget metadata suffix (`prompt`, `spinnerMode`, `contentDescription`, `tooltipText`, `entries`, `inputType`, `hint`, each only when non-empty, with the existing caps) of the `v13` and `v17` lines, read from `StaticGuiModel` (INV-PRM-09).
- `MopPromptContribution`, when `Feature.MOP` is in the plan. It contributes `systemSentence` (the MOP clauses of the system messages, table below), `lineMarker` (`[DM]`/`[M]`, "MOP Marker Annotation"), `contextLine` (` <N>/<M> MOP.`, "Exploration Context in Prompt") and `section` (the `v17` `MOP NAVIGATION:` block and the MOP clause of its closing instruction).

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
| `v17` (user text) | `MOP NAVIGATION:` block with the exploration context | block absent |
| `v17` (user text, last line) | `Select action. Prioritize elements reaching monitored operations, then navigation to new screens.` | `Select action. Prioritize navigation to new screens.` |
| `visual_only` | no MOP text | no MOP text |

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
