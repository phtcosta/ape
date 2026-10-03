# Delta: LLM Routing (llm-coordinate-single-base)

## Purpose

The Action Selection Pipeline determines the device dimensions once per decision, in step 2, and uses them to capture the screenshot and, in step 8, to turn the model's [0,1000) answer back into a pixel. Step 4, building the prompt, did not use them: `ApePromptBuilder` derived its own dimensions from the right and bottom edges of the active window's root node. On the campaign emulator that is 1920 for a full-screen activity and the window's own rectangle for a dialog, against a display of 1794. A coordinate the model copied from the prompt was therefore in a different space from the one its answer was read in (`llm-prompt` delta of this change).

The pipeline's steps and their owners are unchanged. This delta makes explicit what the step list left implicit: one pair of dimensions is determined per decision, and the capture, the prompt and the mapping all use that pair. The `GUITree` remains an argument of `selectAction` because `ScreenshotStep.deviceDimensions` consults it as a last-resort dimension source when the display refuses. It is no longer handed to the prompt builder.

## Invariants

- **INV-RTR-21**: Within one `LlmEngine.selectAction` call, the `{width, height}` returned by `ScreenshotStep.deviceDimensions` in step 2 SHALL be the only device dimensions used: by `ScreenshotStep.capture` (step 2), by `ApePromptBuilder.build` (step 4), and by `CoordinateMapper.toPixels` and `CoordinateMapper.map` (step 8). No step SHALL determine dimensions of its own.

## MODIFIED Requirements

### Requirement: Action Selection Pipeline

`LlmEngine.selectAction(GUITree tree, State state, List<ModelAction> actions, MopData mopData, List<ApePromptBuilder.ActionHistoryEntry> recentActions, String mode, int step)` SHALL run the LLM decision pipeline over the decomposed units and return `ModelAction` or `null`. The step semantics are those of the pre-decomposition `LlmRouter.selectAction`, with one exception: the prompt shares the decision's coordinate base (step 4) instead of deriving dimensions from the tree's root node. Otherwise only the owner of each step changes:

**The argument list is the pre-decomposition one, and it stays that way for a reason worth stating.** `mopData` and `recentActions` are per-step, agent-owned values that step 4 below hands to `ApePromptBuilder.build(...)`, which cannot build a prompt without them. The engine is constructed once per run and owned by `RunContext` (see `LLM Unit Lifecycle and Ownership`), while the per-step view belongs to the stages — so for the engine to source them itself it would have to hold a `StepContext` or the agent, and design D2 exists to prevent exactly that. The calling stage passes them from `ctx.mopData()` and `ctx.actionHistory()`, unchanged.

1. `LlmTelemetry` counts the attempt (`totalCalls++`, per INV-RTR-07).
2. `ScreenshotStep` determines the device dimensions (`deviceDimensions(tree)`: the display bounds, the tree's root node only if the display throws, the 1080 × 1920 constants last) and captures the screenshot at those dimensions. This pair is the decision's coordinate base: steps 4 and 8 use it and determine none of their own (INV-RTR-21). Null capture → breaker failure recorded via `LlmClient`, `screenshot_failed` counter + the free-text screenshot-failure diagnostic carrying the activity and the failing stage via `LlmTelemetry`, return null. This is the one abandoned attempt that produces **no** `llm[]` sub-event (INV-RTR-20).
3. `ScreenshotStep` resizes and base64-encodes. Null → `image` cause as an `llm[]` sub-event with `result:"error"`, return null.
4. `ApePromptBuilder.build(deviceWidth, deviceHeight, state, actions, mopData, base64, recentActions)` builds the messages with the step-2 dimensions, so every [0,1000) coordinate in the prompt is in the space of the image and of the step-8 mapping (`llm-prompt` INV-PRM-06). The tree is not passed to the builder; `LlmTelemetry` stages the prompt via `llmDump`, which rides the next sub-event as `sys`/`user` when the prompt-dump flag is on (default on).
5. `LlmClient.chat(messages, tools)` — the tools schema chosen by the same `hasInputField` predicate the prompt used (INV-LLM-11). Null → breaker failure, cause read once from the client's error seam (INV-LLM-08), cause counters + an `llm[]` sub-event with `result:"error"` and that `cause` via `LlmTelemetry`, return null.
6. `LlmTelemetry` emits the once-per-run `LLM_ACK` record on the first successful response (INV-RTR-12) and stages the response text, which rides the sub-event as `resp`/`tool_calls` under the same prompt-dump flag.
7. `ToolCallParser.parse(response)` — including the raw-arguments repair pipeline for native tool-call malformations (`SglangClient.ToolCall.rawArguments` → Level 1 → shared `parseJsonString`), surfacing through the existing `repair=` field (INV-LLM-10, INV-RTR-14). Null → `parse` cause (client seam NOT consulted), return null.
8. `CoordinateMapper` converts the answer to a pixel with the step-2 dimensions, applies the boundary bands, maps to a `ModelAction` (containment → snap tolerance → off-tree `LlmTapAction` synthesis → `fixTextEdit` conversion → back/long-click preference), and applies the dead-pair ban check (a banned answer is a refused answer: same caller-visible path as `no_match`, breaker still records success — INV-RTR-15/16).
9. `LlmClient` records breaker success; `LlmTelemetry` accounts tokens/latency, classifies the outcome (`matched`/`llm_tap`/`no_match` + `reason`), computes/receives the nearest-widget fields, and appends one `llm[]` sub-event to the current step's record with the same field set as before (renamed per the `LLM Telemetry Logging` mapping; the step, activity and variant keys are dropped as the parent record's envelope or run-constant).
10. The engine returns the mapped action or null. It SHALL never throw (INV-RTR-02): an unexpected exception is the `internal` cause. Large temporaries SHALL be nulled in a `finally` block (INV-RTR-06).

**type_text handling**: unchanged — when the match is an input-capable widget and text is present, `setInputText(text)` is applied before returning.

**Known defect preserved, deliberately.** "Unchanged" here includes a defect measured at 28 of 1,233 LLM responses (2.3%): a `type_text` answer can execute a `MODEL_LONG_CLICK`. The containment pass restricts the candidate's `ActionType` only when the tool is `"click"` (`LlmRouter.java:689`), and `fixTextEdit` returns the match untouched for any tool that is neither `click` nor `long_click` (`:807`), so the long-click preference can win on a `type_text` answer. This stage is behavior-neutral by contract (parity-gated, R8): `CoordinateMapper` SHALL reproduce this path exactly, defect included. The fix is **out of scope here** and belongs to a separate change against `CoordinateMapper`, whose slicing is precisely what makes it testable in a JVM unit. Recording it is mandatory: a silently inherited defect in a newly extracted unit is indistinguishable from a slicing regression when the parity oracle later disagrees.

The dead-pair outcome feedback SHALL continue to flow from the join-buffer site in `StatefulAgent` — the point where `new_state` is computed for the step record's `out` section — into the ban record, now `CoordinateMapper.recordLlmOutcome(...)` reached through `RunContext`'s LLM units, with unchanged key material and strike semantics.

#### Scenario: Full pipeline success
- **WHEN** `selectAction()` is called with a valid GUITree and the server is responsive, and the LLM returns `click` at coordinates mapping into a widget's bounds
- **THEN** that `ModelAction` SHALL be returned, the step's `llm[]` sub-event SHALL carry `result:"matched"`, and the breaker SHALL record success

#### Scenario: one coordinate base per decision
- **WHEN** `selectAction()` runs on a device whose display reports 1080 × 1794
- **AND** the tree's root node reports `[0,0][1080,1920]`
- **THEN** `ScreenshotStep.capture`, `ApePromptBuilder.build` and `CoordinateMapper.toPixels` SHALL each receive `1080, 1794`
- **AND** a model answer equal to a listed widget's `@(x,y)` SHALL map to a pixel inside that widget's bounds (for widgets at least 4 px on each axis whose centre lies inside the display)

#### Scenario: Screenshot capture fails trips the breaker
- **WHEN** `ScreenshotStep` returns null (secure window)
- **THEN** the engine SHALL return null with no HTTP request made
- **AND** the breaker SHALL record a failure and `screenshot_failed` SHALL be counted, with its free-text diagnostic and no `llm[]` sub-event

#### Scenario: Repaired native tool call keeps the repair telemetry
- **WHEN** the model returns a malformed native `tool_calls` arguments string that `ToolCallParser` recovers via the raw-arguments repair pipeline and the coordinate resolves to a widget
- **THEN** the sub-event SHALL carry `repair:"<form>"` and the decision SHALL count under both `matched` and `repaired` (INV-LLM-10, INV-RTR-14 unchanged)

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
