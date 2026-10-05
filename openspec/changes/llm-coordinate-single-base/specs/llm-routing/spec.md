# Delta: LLM Routing (llm-coordinate-single-base)

## Purpose

The Action Selection Pipeline determines the device dimensions once per decision, in step 2, and uses them to capture the screenshot and, in step 8, to turn the model's [0,1000) answer back into a pixel. Step 4, building the prompt, did not use them: `ApePromptBuilder` derived its own dimensions from the right and bottom edges of the active window's root node. On the campaign emulator that is 1920 for a full-screen activity and the window's own rectangle for a dialog, against a display of 1794. A coordinate the model copied from the prompt was therefore in a different space from the one its answer was read in (`llm-prompt` delta of this change).

The pipeline's steps and their owners are unchanged. This delta makes explicit what the step list left implicit: one pair of dimensions is determined per decision, and the capture, the prompt and the mapping all use that pair. The `GUITree` remains an argument of `selectAction` because `ScreenshotStep.deviceDimensions` consults it as a last-resort dimension source when the display refuses. It is no longer handed to the prompt builder.

The boundary reject in step 8 had the same blind spot. It is written as fractions of `deviceHeight`, and its bottom fraction (`0.94`, "navigation bar") was chosen for a 1920-high screen. `deviceHeight` is the display, 1794 on the campaign emulator, which already excludes the navigation bar. On the display, the bottom 6 % is app content: a target-requiring widget sits there in 32.8 % of E6 states. This delta restates the band in the base the pipeline actually uses: one top band for the status bar, no bottom band. An answer outside [0,999] is refused before it is mapped, because the clamp in `normalize` would otherwise turn it into a tap at the screen's edge; its clamped pixel is computed for telemetry only.

## Invariants

- **INV-RTR-21**: Within one `LlmEngine.selectAction` call, the `{width, height}` returned by `ScreenshotStep.deviceDimensions` in step 2 SHALL be the only device dimensions used: by `ScreenshotStep.capture` (step 2), by `ApePromptBuilder.build` (step 4), and by `CoordinateMapper.toPixels` and `CoordinateMapper.map` (step 8). No step SHALL determine dimensions of its own.
- **INV-RTR-22**: The boundary reject SHALL be expressed in the display base of INV-RTR-21. It SHALL reject exactly the answers whose pixel lies in the status-bar band (`pixelY < deviceHeight * ape.llmBoundaryTopPct`) and, in step 8 before the answer is mapped, the answers outside `[0,999]` on either axis whose tool is not `back` (their clamped pixel is still computed by `toPixels`, for telemetry only, and is never matched or tapped). No lower band SHALL exist. A reject SHALL surface as `result:"no_match"` with `reason:"degenerate"` for `(0,0)` and `reason:"boundary"` otherwise; the reason set is unchanged. This supersedes INV-RTR-14's clause that the band defaults reproduce the hard-coded `0.05`/`0.94`.

## MODIFIED Requirements

### Requirement: Action Selection Pipeline

`LlmEngine.selectAction(GUITree tree, State state, List<ModelAction> actions, MopData mopData, List<ApePromptBuilder.ActionHistoryEntry> recentActions, String mode)` SHALL run the LLM decision pipeline over the decomposed units and return `ModelAction` or `null`. The step semantics are those of the pre-decomposition `LlmRouter.selectAction`, with two exceptions: the prompt shares the decision's coordinate base (step 4) instead of deriving dimensions from the tree's root node, and step 8 refuses an answer outside the answer space and applies one boundary band, the status bar, instead of two (INV-RTR-22). Otherwise only the owner of each step changes:

**The argument list is the pre-decomposition one, and it stays that way for a reason worth stating.** `mopData` and `recentActions` are per-step, agent-owned values that step 4 below hands to `ApePromptBuilder.build(...)`, which cannot build a prompt without them. The engine is constructed once per run and owned by `RunContext` (see `LLM Unit Lifecycle and Ownership`), while the per-step view belongs to the stages — so for the engine to source them itself it would have to hold a `StepContext` or the agent, and design D2 exists to prevent exactly that. The calling stage passes them from `ctx.mopData()` and `ctx.actionHistory()`, unchanged.

1. `LlmTelemetry` counts the attempt (`totalCalls++`, per INV-RTR-07).
2. `ScreenshotStep` determines the device dimensions (`deviceDimensions(tree)`: the display bounds, the tree's root node only if the display throws, the 1080 × 1920 constants last) and captures the screenshot at those dimensions. This pair is the decision's coordinate base: steps 4 and 8 use it and determine none of their own (INV-RTR-21). Null capture → breaker failure recorded via `LlmClient`, `screenshot_failed` counter + the free-text screenshot-failure diagnostic carrying the activity and the failing stage via `LlmTelemetry`, return null. This is the one abandoned attempt that produces **no** `llm[]` sub-event (INV-RTR-20).
3. `ScreenshotStep` resizes and base64-encodes. Null → `image` cause as an `llm[]` sub-event with `result:"error"`, return null.
4. `ApePromptBuilder.build(deviceWidth, deviceHeight, state, actions, mopData, base64, recentActions)` builds the messages with the step-2 dimensions, so every [0,1000) coordinate in the prompt is in the space of the image and of the step-8 mapping (`llm-prompt` INV-PRM-06). The tree is not passed to the builder; `LlmTelemetry` stages the prompt via `llmDump`, which rides the next sub-event as `sys`/`user` when the prompt-dump flag is on (default on).
5. `LlmClient.chat(messages, tools)` — the tools schema chosen by the same `hasInputField` predicate the prompt used (INV-LLM-11). Null → breaker failure, cause read once from the client's error seam (INV-LLM-08), cause counters + an `llm[]` sub-event with `result:"error"` and that `cause` via `LlmTelemetry`, return null.
6. `LlmTelemetry` emits the once-per-run `LLM_ACK` record on the first successful response (INV-RTR-12) and stages the response text, which rides the sub-event as `resp`/`tool_calls` under the same prompt-dump flag.
7. `ToolCallParser.parse(response)` — including the raw-arguments repair pipeline for native tool-call malformations (`SglangClient.ToolCall.rawArguments` → Level 1 → shared `parseJsonString`), surfacing through the existing `repair=` field (INV-LLM-10, INV-RTR-13). Null → `parse` cause (client seam NOT consulted), return null.
8. If the answer's tool is not `back` and `CoordinateMapper.inAnswerSpace(x, y)` is false (either coordinate outside `[0,999]`), the answer SHALL NOT be mapped: the match is null and the outcome is `no_match` with `reason:"boundary"` (INV-RTR-22). Its clamped pixel is still computed by `toPixels`, for the sub-event's `px` and the nearest-widget fields only. Otherwise `CoordinateMapper` converts the answer to a pixel with the step-2 dimensions, applies the status-bar boundary band, maps to a `ModelAction` (containment → snap tolerance → off-tree `LlmTapAction` synthesis → `fixTextEdit` conversion → back/long-click preference), and applies the dead-pair ban check (a banned answer is a refused answer: same caller-visible path as `no_match`, breaker still records success — INV-RTR-15/16).
9. `LlmClient` records breaker success; `LlmEngine.classify` classifies the outcome (`matched`/`llm_tap`/`no_match` + `reason`); `LlmTelemetry` accounts tokens/latency, receives the nearest-widget fields, and appends one `llm[]` sub-event to the current step's record with the same field set as before (renamed per the `LLM Telemetry Logging` mapping; the step, activity and variant keys are dropped as the parent record's envelope or run-constant).
10. The engine returns the mapped action or null. It SHALL never throw (INV-RTR-02): an unexpected exception is the `internal` cause. Large temporaries SHALL be nulled in a `finally` block (INV-RTR-06).

**type_text handling**: unchanged — when the match is an input-capable widget and text is present, `setInputText(text)` is applied before returning.

**Known defect preserved, deliberately.** "Unchanged" here includes a defect measured at 28 of 1,233 LLM responses (2.3%): a `type_text` answer can execute a `MODEL_LONG_CLICK`. The containment pass restricts the candidate's `ActionType` only when the tool is `"click"` (`CoordinateMapper.java:160` at `e93dea86`), and `fixTextEdit` returns the match untouched for any tool that is neither `click` nor `long_click` (`:274`), so the long-click preference can win on a `type_text` answer. This stage is behavior-neutral by contract (parity-gated, R8): `CoordinateMapper` SHALL reproduce this path exactly, defect included. The fix is **out of scope here** and belongs to a separate change against `CoordinateMapper`, whose slicing is precisely what makes it testable in a JVM unit. Recording it is mandatory: a silently inherited defect in a newly extracted unit is indistinguishable from a slicing regression when the parity oracle later disagrees.

The dead-pair outcome feedback SHALL continue to flow from the join-buffer site in `StatefulAgent` — the point where `new_state` is computed for the step record's `out` section — into the ban record, now `CoordinateMapper.recordLlmOutcome(...)` reached through `RunContext`'s LLM units, with unchanged key material and strike semantics.

#### Scenario: Full pipeline success
- **WHEN** `selectAction()` is called with a valid GUITree and the server is responsive, and the LLM returns `click` at coordinates mapping into a widget's bounds
- **THEN** that `ModelAction` SHALL be returned, the step's `llm[]` sub-event SHALL carry `result:"matched"`, and the breaker SHALL record success

#### Scenario: one coordinate base per decision
- **WHEN** `selectAction()` runs on a device whose display reports 1080 × 1794
- **AND** the tree's root node reports `[0,0][1080,1920]`
- **THEN** `ScreenshotStep.capture`, `ApePromptBuilder.build` and `CoordinateMapper.toPixels` SHALL each receive `1080, 1794`
- **AND** a model answer equal to a listed widget's `@(x,y)` SHALL map to a pixel inside that widget's visible part (for widgets whose visible part is at least 4 px on each axis)

#### Scenario: answer outside the answer space is refused before mapping
- **WHEN** the model answers `click` at `(540, 1040)` on a 1080 × 1794 display
- **THEN** `CoordinateMapper.map` SHALL NOT be called for it, and its clamped pixel SHALL only be recorded
- **AND** the step's `llm[]` sub-event SHALL carry `result:"no_match"` and `reason:"boundary"`
- **AND** no widget at the screen's bottom edge SHALL be selected

#### Scenario: back answer is never refused for its coordinates
- **WHEN** the model answers `back` with coordinates `(0, 1500)` or with none
- **THEN** the answer-space check SHALL NOT apply
- **AND** the state's back action SHALL be returned as before

#### Scenario: Screenshot capture fails trips the breaker
- **WHEN** `ScreenshotStep` returns null (secure window)
- **THEN** the engine SHALL return null with no HTTP request made
- **AND** the breaker SHALL record a failure and `screenshot_failed` SHALL be counted, with its free-text diagnostic and no `llm[]` sub-event

#### Scenario: Repaired native tool call keeps the repair telemetry
- **WHEN** the model returns a malformed native `tool_calls` arguments string that `ToolCallParser` recovers via the raw-arguments repair pipeline and the coordinate resolves to a widget
- **THEN** the sub-event SHALL carry `repair:"<form>"` and the decision SHALL count under both `matched` and `repaired` (INV-LLM-10, INV-RTR-13 unchanged)

#### Scenario: banned result is refused at step 10, not failed
- **WHEN** the mapped action's ban key has reached the strike threshold
- **THEN** the engine SHALL return null with a `result:"no_match"`, `reason:"dead_pair"` sub-event
- **AND** `LlmClient` SHALL still record success (a refused answer is not a pipeline failure)
- **AND** the check SHALL run inside step 8 above — after the mapping, before the return — so a banned decision is a refused answer rather than a failed pipeline

#### Scenario: Off-tree element becomes a coordinate tap
- **WHEN** the pipeline succeeds with a `click` at in-bounds pixel `(600, 900)` on a 1080x1794 device
- **AND** `CoordinateMapper` finds no widget containing the point and none within the snap tolerance
- **THEN** `selectAction()` SHALL return an `LlmTapAction` of type `MODEL_LLM_TAP` carrying `(600, 900)`
- **AND** the sub-event SHALL classify the outcome `llm_tap`, not `no_match` (the synthesis is a decision, and `Coordinate-to-ModelAction Mapping` owns the unit-level rule this end-to-end path exercises)

#### Scenario: no_match reason is always one of three
- **WHEN** any decision in a run ends as `result:"no_match"`
- **THEN** its sub-event SHALL carry exactly one `reason` from `degenerate`, `boundary`, `dead_pair`
- **AND** the closure SHALL hold across the decomposition: `CoordinateMapper` produces the first two and the ban check the third, and `LlmTelemetry` SHALL have no fourth reason to record

#### Scenario: Engine never throws
- **WHEN** any unexpected exception occurs inside the engine
- **THEN** the engine SHALL catch it, count `internal`, append a sub-event with `result:"error"` and `cause:"internal"`, and return null

### Requirement: Coordinate-to-ModelAction Mapping

`LlmRouter.mapToModelAction(int pixelX, int pixelY, String actionType, String text, List<ModelAction> actions, State state, int deviceWidth, int deviceHeight)` SHALL map LLM output coordinates to a `ModelAction` for the current state, returning a matched widget action, a synthesized off-tree `LlmTapAction`, or null.

**Boundary reject**: Before coordinate matching, if `pixelY < deviceHeight * ape.llmBoundaryTopPct` (default `0.035`: the 63 px status bar of the campaign AVD over its 1794 px display, and any degenerate `(0,0)` emission), `mapToModelAction` SHALL return null and log the boundary reject. This prevents the LLM from tapping system UI, and no off-tree tap is synthesized for these coordinates. `deviceHeight` is the display height `ScreenshotStep.deviceDimensions` returned (INV-RTR-21), which on Android excludes the navigation bar, and `normalize` clamps every answer to `[0, deviceHeight − 1]`. There is therefore no lower band: the bottom of the display is app content (bottom navigation, footer buttons, snackbar actions), and an answer that points outside the image is refused before it is mapped ("Action Selection Pipeline" step 8, `CoordinateMapper.inAnswerSpace`). The top fraction is configuration-exposed (J1b) for devices of another density (INV-RTR-22). The former `ape.llmBoundaryBottomPct` was a fraction written for a 1920-high screen whose bottom 6 % was the navigation bar; on the display it rejected y ∈ (1686, 1794), and it is retired.

**ActionType filter (both matching passes)**: the tool the model called SHALL constrain the `ActionType` of the matched action, in the containment pass AND the Euclidean fallback:
- `"click"` SHALL match only `MODEL_CLICK` actions (measured defect this closes: a `click` answer executed CLICK only 80.9% of the time — the rest matched long-clicks, scrolls, or other types sharing the widget's bounds);
- `"long_click"` SHALL prefer `MODEL_LONG_CLICK`; when no `MODEL_LONG_CLICK` matches, `MODEL_CLICK` on the same coordinates MAY be returned as fallback (unchanged);
- `"type_text"` SHALL consider only actions targeting input-capable widgets (EditText, SearchView, AutoCompleteTextView) (unchanged).

**Special action types**:
- If `actionType` equals `"back"`, the state's `backAction` SHALL be returned directly without coordinate matching.
- If `actionType` equals `"type_text"` and a match is found, the caller SHALL call `action.getResolvedNode().setInputText(text)` to inject the LLM-provided text into APE's existing input event generation pipeline.

**fixTextEdit (input-widget click conversion)**: when `actionType` is `"click"` or `"long_click"` and the matched widget (containment or snap) is input-capable (EditText, SearchView, AutoCompleteTextView), `mapToModelAction` SHALL NOT return the bare click. It SHALL convert the decision into a text-entry action on that widget: the target comes from the LLM coordinate (the *where*), and the text is generated by APE's existing typed-input generation path — the same generator a SATA-selected input action uses (the *what*); no second LLM call is made. This removes the bare click on input widgets from the LLM's effective action space (banning by subtraction — the mechanism that outperforms prompt instruction), attacking the measured `type_text≈0` collapse. EditText is the widget class with the model's best grounding (93.1%), so the *where* is trustworthy; only the *what* was missing. The input-capable set used here is the same one that exempts these widgets from the dead-pair ban ("Deterministic Dead-Pair Ban") — a decision that resolves to an input widget is transformed rather than refused, and is never withdrawn from the action space by repetition.

**Bounds containment (primary matching strategy)**: For each action passing the ActionType filter where `action.requireTarget() == true` AND `action.isValid() == true` AND `action.getResolvedNode() != null`, check if `(pixelX, pixelY)` falls within the node's `getBoundsInScreen()` rectangle. If exactly one action's bounds contain the point, return that action. If multiple actions' bounds contain the point, return the one with the smallest area (most specific widget).

**Edge-based distance (fallback matching)**: If no action's bounds contain the point, compute the **point-to-rectangle distance** from `(pixelX, pixelY)` to each candidate's resolved bounds: `dx = max(bounds.left − pixelX, 0, pixelX − bounds.right)`, `dy = max(bounds.top − pixelY, 0, pixelY − bounds.bottom)`, `dist = hypot(dx, dy)` (zero when the point is inside). Return the action with the minimum distance if that distance is within the proportional tolerance `max(Config.llmSnapTolerancePx, min(nodeWidth, nodeHeight) / 2)` pixels (floor default `50`, configuration-exposed per J1b). This replaces centre-distance, whose geometry punished elongated widgets: on a 1080×150 bar, only points within ~75 px of the **centre** could snap, leaving ~450 px of the bar's own edge unsnappable — a tap 20 px outside a wide widget failed while being visually on target. The `ape.llmSnapTolerancePx` default is unchanged; raising it (to ~150) is an rv-android configuration decision gated on the dead-pair ban.

**Off-tree coordinate tap (dynamic element)**: If no action's bounds contain the point AND no action is within edge-distance tolerance AND `actionType` is `"click"` or `"long_click"`, `mapToModelAction` SHALL return `new LlmTapAction(state, pixelX, pixelY, "long_click".equals(actionType))` — a targetless `MODEL_LLM_TAP` action carrying the LLM coordinate. Because the answer-space check, the clamp in `normalize` and the boundary reject run first, a coordinate reaching this point is inside the display and non-degenerate. For any other `actionType` (e.g. `type_text`), `mapToModelAction` SHALL return null. This is the mechanism by which APE acts on elements invisible to UIAutomator (game canvas, custom view, Compose-without-semantics).

#### Scenario: LLM says "back"

- **WHEN** `mapToModelAction(0, 0, "back", null, actions)` is called
- **THEN** `state.getBackAction()` SHALL be returned
- **AND** no coordinate matching SHALL be performed

#### Scenario: click answer only matches MODEL_CLICK

- **WHEN** `mapToModelAction(200, 230, "click", null, actions)` is called
- **AND** the only action whose bounds contain the point is a `MODEL_LONG_CLICK`
- **THEN** that action SHALL NOT be returned by the containment pass
- **AND** matching SHALL proceed to the edge-distance fallback over `MODEL_CLICK` candidates (or off-tree synthesis)

#### Scenario: Click coordinates inside button bounds

- **WHEN** `mapToModelAction(200, 230, "click", null, actions)` is called
- **AND** MODEL_CLICK action A has resolved node bounds `[100, 200, 300, 250]` (contains point)
- **AND** MODEL_CLICK action B has resolved node bounds `[0, 0, 480, 800]` (also contains point, but larger)
- **THEN** action A SHALL be returned (smallest area containing the point)

#### Scenario: edge snap on an elongated bar

- **WHEN** `mapToModelAction(540, 180, "click", null, actions)` is called
- **AND** a MODEL_CLICK bar has bounds `[0, 200, 1080, 350]`, whose centre is `(540, 275)` — the point is 20 px above the bar's top edge but 95 px from its centre
- **THEN** the point-to-rectangle distance SHALL be 20 (dx=0, dy=20)
- **AND** with tolerance `max(50, 75) = 75` the bar SHALL be snapped and returned
- **AND** the retired centre-distance rule would have rejected it (95 > 75), which is the regression this locks

#### Scenario: click on an EditText becomes text entry

- **WHEN** `mapToModelAction(225, 325, "click", null, actions)` is called
- **AND** the containing widget is an EditText
- **THEN** the bare click SHALL NOT be returned
- **AND** the decision SHALL become a text-entry action on that EditText whose text comes from APE's typed-input generation path

#### Scenario: type_text targets EditText

- **WHEN** `mapToModelAction(225, 325, "type_text", "user@example.com", actions)` is called
- **AND** action C is an EditText at bounds `[50, 300, 400, 350]` (contains point)
- **THEN** action C SHALL be returned
- **AND** the caller SHALL call `action.getResolvedNode().setInputText("user@example.com")`

#### Scenario: long_click prefers MODEL_LONG_CLICK

- **WHEN** `mapToModelAction(200, 230, "long_click", null, actions)` is called
- **AND** the same widget offers MODEL_LONG_CLICK and MODEL_CLICK actions containing the point
- **THEN** the MODEL_LONG_CLICK action SHALL be returned

#### Scenario: Off-tree click builds an LlmTapAction

- **WHEN** `mapToModelAction(600, 900, "click", null, actions, state, 1080, 1794)` is called
- **AND** no MODEL_CLICK action contains the point and none is within edge-distance tolerance
- **THEN** `mapToModelAction` SHALL return a new `LlmTapAction` of type `MODEL_LLM_TAP` with `pixelX=600`, `pixelY=900`, `longClick=false`

#### Scenario: Off-tree type_text stays no_match

- **WHEN** `mapToModelAction(600, 900, "type_text", "hello", actions, state, 1080, 1794)` is called
- **AND** no input-capable widget contains or is near the point
- **THEN** `mapToModelAction` SHALL return null (no off-tree tap is synthesized for text input)

#### Scenario: Boundary reject — status bar

- **WHEN** `mapToModelAction(540, 40, "click", null, actions, state, 1080, 1794)` is called
- **AND** `pixelY (40) < deviceHeight * ape.llmBoundaryTopPct (62.79 at the 0.035 default)`
- **THEN** `mapToModelAction` SHALL return null
- **AND** no `LlmTapAction` SHALL be constructed

#### Scenario: toolbar below the status bar is not rejected

- **WHEN** `mapToModelAction(540, 80, "click", null, actions, state, 1080, 1794)` is called
- **AND** a MODEL_CLICK toolbar action has bounds `[0,63][1080,210]`
- **THEN** the boundary check SHALL pass (80 ≥ 62.79)
- **AND** the toolbar action SHALL be returned

#### Scenario: Boundary reject — navigation bar

- **WHEN** `mapToModelAction(135, 1720, "click", null, actions, state, 1080, 1794)` is called
- **AND** a MODEL_CLICK bottom-navigation item has bounds `[0,1647][270,1794]`
- **THEN** no boundary reject SHALL occur: the navigation bar lies below the 1794 px display, outside every pixel `normalize` can produce, so there is no lower band to reject it with
- **AND** the bottom-navigation item SHALL be returned (under the retired `0.94` band, `1720 > 1686.36` was rejected)

#### Scenario: Band configurable without rebuild

- **WHEN** `ape.properties` contains `ape.llmBoundaryTopPct=0.05`
- **THEN** a `pixelY=80` click on a 1080x1794 device SHALL be rejected (80 < 89.7) without any code change

### Requirement: LLM Telemetry Logging

`LlmRouter` SHALL record structured per-attempt telemetry as `llm[]` sub-events on the current step's `StepRecord` (event-sink capability) and aggregate counters reported in `RUN_END`.

**Per-attempt sub-event** (one array entry per routing attempt, in occurrence order within the step):

| Field | Values |
|-------|--------|
| `call` | running call counter for the run |
| `mode` | `new_state`, `stagnation`, `random` |
| `tool` | the parsed tool name (`click`, `long_click`, `type_text`, `back`) |
| `qwen` / `px` | `[x,y]` model-space and mapped pixel coordinates |
| `result` | `matched`, `llm_tap`, `no_match` for completed mappings; `error` for attempts abandoned before the mapping step; `breaker_open` for the once-per-open-episode breaker decline |
| `reason` | only on `no_match`: `dead_pair`, `degenerate`, or `boundary` |
| `repair` | only when the decision's `ParsedAction` carries a repair-form label other than `none` (per `llm-infrastructure` INV-LLM-09): `missing_y`, `array_xy`, `quoted_xy`, or `int_scan`. Absent on a clean parse |
| `mcls` / `ncls` / `ndist` / `widgets` | matched class, nearest class, nearest distance, candidate widget count |
| `tok` | `[prompt_tokens, completion_tokens]` (0 if unavailable) |
| `ms` | wall-clock milliseconds for the full pipeline (screenshot → response) |
| `text` | only for text-entry decisions: the typed text (JSON-escaped by the serializer) |
| `cause` / `detail` | only on `result:"error"`: exactly one named cause per abandoned attempt — `timeout`, `http_<status>`, `connection`, `parse`, `image`, `internal` — attributed at the failure point (client seam `getLastErrorCause()` consulted only when `chat()` returned null; router-attributed otherwise). The screenshot-capture failure keeps its counter (`screenshot_failed`) and its existing free-text line, with no `error` sub-event |
| `trips` | only on `result:"breaker_open"`: the breaker trip count; emitted at the first routing-predicate decline of each open episode only, using a side-effect-free breaker state query (`isOpen()`), never a second `shouldAttempt()` |
| `sys` / `user` / `resp` / `tool_calls` | prompt and response dumps, present when the prompt-dump flag is on (default on — recordability parity with the retired unconditional `[APE-LLM-PROMPT]`/`[APE-LLM-RESPONSE]` lines) |

No sub-event carries a step, activity, or variant field: the step and activity are the parent record's envelope, and the prompt variant is run-constant in the `RUN_START` plan echo. The `repair` field remains orthogonal to `result` (a repaired call yields a normal outcome plus `repair=<form>`).

**Counters**: the per-cause failure counters (partitioning the retired `null`), `matched`/`llm_tap`/`no_match`/`dead_pair`/`repaired`, token and latency totals, and `breaker_trips` SHALL be maintained exactly as before and exposed via an accessor consumed by the `RUN_END` emitter. The decisions denominator (`matched + llm_tap + no_match + Σ failure causes`) is derived by consumers; no ratio is stored.

**Where the retired renderings' assertions went.** Four scenarios below keep the header the pre-change
requirement gave them and carry a body this change contradicts, because the thing they asserted is
the thing this change removes. They are recorded here rather than dropped, since the reader's
question is not whether the old line is gone but where its claim lives now:

| Scenario header | What it asserted | Where the claim is now |
|---|---|---|
| `LLM call telemetry joins step and outcome on one key` | `[APE-LLM-TEL] step=N` ↔ `[APE-STEP] step=N` ↔ `[APE-OUTCOME] step=N` | **nowhere, deliberately** — the call, the decision and the outcome are members of one `StepRecord`, so there is no key to join on. The property the join existed to guarantee (a call is attributable to the step whose selection it interrupted) is now structural |
| `Screenshot failure emits an attributable error line` | `[APE-LLM-ERROR] cause=screenshot activity=… detail=<stage>` | the surviving free-text line, which keeps the activity and the capture stage verbatim (`LlmTelemetry.screenshotFailed`, INV-RTR-20), plus `screenshot_failed` in `RUN_END`. It is the one abandoned attempt with no `llm[]` sub-event |
| `Stagnation mode triggered` | `[APE-RV] LLM mode=stagnation, state=…` | the sub-event's `mode` field. The line the scenario names was never emitted by any shipped router — `mode` has always been a *field* of the per-attempt telemetry — and the trigger condition it describes is asserted by `Stagnation LLM Mode`, which this change does not modify |
| `widget text with a newline does not break the prompt log` | the prompt builder flattens `\n`/`\r` before the dump is emitted | two places, and it is stronger in both: the builder still flattens (`llm-prompt :: multi-line widget text flattened`, unmodified by this change), and the serializer now escapes by construction (`event-sink` INV-SNK-02), so no widget text can put a raw newline in a record |

#### Scenario: Matched widget logged

- **WHEN** the new-state hook routes to the LLM at step 42 and the call maps to a widget `ModelAction` of type `MODEL_CLICK`
- **THEN** the `s:42` record's `llm` array SHALL contain the entry with `result:"matched"`, its coordinates, `tok`, and `ms`
- **AND** no `[APE-LLM-TEL]` line SHALL be emitted

#### Scenario: LLM call telemetry joins step and outcome on one key

- **WHEN** the new-state hook routes to the LLM at step 42, the call succeeds and the mapped action executes producing a recorded transition
- **THEN** the call's sub-event, the decision (`dec`) and the outcome (`out`) SHALL all be members of the single `s:42` record
- **AND** no join key SHALL exist or be needed — the pre-change `step=` field is absent from the sub-event because the step is the parent record's envelope (`event-sink` INV-SNK-04)

#### Scenario: dead-pair ban visible in TEL and summary

- **WHEN** two decisions in a run are banned by the dead-pair ban and one is discarded for a boundary coordinate
- **THEN** the two banned decisions' sub-events SHALL carry `result:"no_match"` with `reason:"dead_pair"`
- **AND** `RUN_END` SHALL report `no_match:3` and `dead_pair:2` — the overlay counter that makes bucket D, the falsification gate of the ban, countable from the run's last record alone

#### Scenario: Repaired tool call logged and counted

- **WHEN** at step 61 the model returns `{"name": "click", "arguments": {"x": "500, 527}}`, `ToolCallParser` recovers it via the quoted-collapsed-XY fix, and the coordinate resolves to a widget
- **THEN** the step-61 sub-event SHALL carry `result:"matched"` and `repair:"quoted_xy"`
- **AND** the run counters SHALL count the decision under both `matched` and `repaired`
- **AND** no `error` sub-event with `cause:"parse"` SHALL exist for step 61

#### Scenario: no_match reason separated

- **WHEN** one decision is discarded for a `(0,0)` coordinate, another for a status-bar coordinate, and a third by the dead-pair ban
- **THEN** their sub-events SHALL carry `reason:"degenerate"`, `reason:"boundary"`, and `reason:"dead_pair"` respectively, each with `result:"no_match"`

#### Scenario: Timeout and HTTP failure discriminated

- **WHEN** one attempt times out and a later attempt gets HTTP 500
- **THEN** the first step's record SHALL carry `{"result":"error","cause":"timeout",...}` and increment `timeoutCount`
- **AND** the second's SHALL carry `{"result":"error","cause":"http_500",...}` and increment `httpErrorCount`
- **AND** `RUN_END` counters SHALL report `timeout:1` and `http_error:1`

#### Scenario: Router-side parse failure attributed without the client seam

- **WHEN** `chat()` returns a non-null response but `ToolCallParser.parse()` extracts no tool call even after the quoted-collapsed-XY fix and last-resort integer extraction
- **THEN** a sub-event with `result:"error"` and `cause:"parse"` SHALL be appended and `parseErrorCount` incremented
- **AND** `getLastErrorCause()` SHALL NOT be consulted (the HTTP call succeeded; its value is stale)

#### Scenario: Circuit breaker event logged once per open episode

- **WHEN** the breaker trips to OPEN with 2 trips and predicates decline 5 calls during the same open window
- **THEN** exactly one sub-event `{"result":"breaker_open","trips":2}` SHALL be recorded, on the step of the first decline
- **AND** no LLM HTTP call SHALL be made while the breaker is OPEN

#### Scenario: Screenshot failure emits an attributable error line

- **WHEN** `ScreenshotCapture.capture()` returns null on a FLAG_SECURE window while activity `org.fedorahosted.freeotp.MainActivity` is foreground, in a run that ends after 5 attempts of which 3 were abandoned at capture and 1 failed at parse
- **THEN** the failure SHALL be attributable from the surviving free-text line, which carries `activity=org.fedorahosted.freeotp.MainActivity` and `detail=<stage>` read from the `ScreenshotCapture` seam
- **AND** `screenshotFailedCount` SHALL be incremented and `breaker.recordFailure()` called (unchanged breaker semantics)
- **AND** `RUN_END` counters SHALL report `parse_error:1 screenshot_failed:3`
- **AND** the screenshot failures SHALL have produced no `error` sub-event — the one abandoned attempt that does not, deliberately (INV-RTR-20)

#### Scenario: Stagnation mode triggered

- **WHEN** `shouldRouteStagnation(150)` is called with `graphStableRestartThreshold = 200` and the episode flag armed, and the resulting attempt completes
- **THEN** the attempt's sub-event SHALL carry `mode:"stagnation"`
- **AND** no free-text mode line SHALL be emitted — the pre-change scenario named `[APE-RV] LLM mode=stagnation, state=…`, which no shipped router ever wrote; the trigger condition itself is asserted by `Stagnation LLM Mode`, unmodified by this change

#### Scenario: widget text with a newline does not break the prompt log

- **WHEN** an element's widget text contains `"line1\nline2"` and the prompt-dump flag is on
- **THEN** the sub-event's `user` field SHALL contain `"line1 line2"` for that element (flattened by the prompt builder, `llm-prompt` capability, unchanged)
- **AND** the record SHALL occupy exactly one physical line regardless of what the text contains, because the serializer escapes `\n`, `\r` and every control character by construction (`event-sink` INV-SNK-02) — the flattening is no longer what makes the dump parseable, only what keeps one element on one line within it

#### Scenario: Prompt dump follows the flag

- **WHEN** the prompt-dump flag is at its default (on)
- **THEN** each completed call's sub-event SHALL carry `sys`/`user`/`resp` with the prompt and response text JSON-escaped
- **AND** with the flag off, the same sub-event SHALL omit those fields and nothing else changes

### Requirement: LLM Unit Lifecycle and Ownership

When the plan carries the LLM feature, `RunContext` SHALL construct and own the LLM units exactly once at bootstrap, wired from the plan's `LlmParams` (never from static `Config`):

- `LlmClient` — transport + circuit breaker as one unit (`llm-infrastructure` capability): base URL, model, `temperature`, `top_p`, `top_k`, `max_tokens` (default `1024`), `timeout_ms`.
- `ScreenshotStep` — `ScreenshotCapture` + `ImageProcessor` + device-dimension determination.
- `ApePromptBuilder` — unchanged, variant from `LlmParams`.
- `ToolCallParser` — unchanged.
- `CoordinateMapper` — coordinate normalization, the status-bar boundary band (`llmBoundaryTopPct`) and the answer-space check, snap tolerance floor (`llmSnapTolerancePx`), back/long-click preference, `fixTextEdit`, and the dead-pair ban record.
- `LlmTelemetry` — all LLM counters and latches, and the sink calls that carry them (`llmCall`, `llmError`, `llmBreakerOpen`, `llmDump`, `llmAck`); it exposes the counters through an accessor for `RUN_END` and prints no summary.
- `LlmEngine` — the thin orchestrator of the Action Selection Pipeline over the units above.

The `max_tokens` value SHALL be read once from `LlmParams` and shared by the request body and the `RUN_START` effective-plan echo, so what the trace reports is what the wire carried. No manifest line SHALL be emitted at `LlmClient` construction: every field it carried (model, temperature, top_p, top_k, max_tokens, timeout_ms, prompt_variant, llm_percentage, on_new_state, on_stagnation, stagnation_threshold, url) is in the echo, which states the whole resolved plan rather than the LLM slice of it.

**Per-request tool schema:** the two schema constants (with and without `type_text`) SHALL be built once at construction and the appropriate one passed to each `chat()` invocation by the same `hasInputField` predicate the system message uses (prompt/wire coherence, INV-LLM-11) — unchanged behavior, now owned by `LlmClient`/`LlmEngine`.

When the plan does NOT carry the LLM feature, none of these units SHALL be constructed and no LLM stage SHALL exist in the decision pipeline (feature absent = stage absent, `decision-pipeline` INV-DP-03; replaces the null `_llmRouter` convention of INV-RTR-01).

All unit references SHALL be final and reused for the entire run; all unit state (counters, latches, ban record, breaker state) is per-run and dies with the process.

#### Scenario: LLM feature in the plan constructs the units once
- **WHEN** the resolved plan carries the LLM feature with `url=http://10.0.2.2:30000/v1`
- **THEN** `RunContext` SHALL construct `LlmClient`, `ScreenshotStep`, `ApePromptBuilder`, `ToolCallParser`, `CoordinateMapper`, `LlmTelemetry`, and `LlmEngine` exactly once
- **AND** the effective values SHALL appear in the `RUN_START` echo, with no manifest line emitted

#### Scenario: LLM feature absent constructs nothing
- **WHEN** the resolved plan does not carry the LLM feature
- **THEN** no LLM unit SHALL be constructed and no LLM stage SHALL be assembled
- **AND** zero LLM-related trace lines SHALL be emitted for the run

#### Scenario: units read the plan, not Config
- **WHEN** any LLM unit needs a sampling, timeout, boundary, or tolerance parameter during the run
- **THEN** it SHALL read the value injected from `LlmParams` at construction
- **AND** no LLM unit SHALL read static `Config` after bootstrap
