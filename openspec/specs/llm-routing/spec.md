## Purpose

LLM routing integrates the LLM infrastructure into APE-RV's exploration loop at two specific decision points where SATA's deterministic behavior limits exploration diversity. Rather than replacing SATA or competing with MOP priority scoring, the LLM router operates as a punctual override: when triggered, it captures a screenshot, sends it to the LLM with the current widget list and action history, and directly selects a `ModelAction` to execute. When the LLM is unavailable, times out, or returns an unparseable response, the router returns null and SATA takes over transparently.

The two modes target different exploration bottlenecks:

1. **New-state mode** (`Config.llmOnNewState`): fires on the first visit to each newly discovered state. The `isNewState` flag is captured in `StatefulAgent.updateStateInternal()` **before** `Graph.markVisited()` to ensure accurate first-visit detection. This is the highest-value intervention point because the LLM sees a screen for the first time and can identify the most promising action using visual understanding that SATA lacks. Cost: ~50-100 calls per 10-minute run.

2. **Stagnation mode** (`Config.llmOnStagnation`): checked in `SataAgent.selectNewActionNonnull()` when `graphStableCounter` **equals** half the restart threshold (`graphStableRestartThreshold / 2`), providing exactly one LLM attempt per stagnation phase. The equality check (not greater-than) ensures the hook fires once when the counter hits the midpoint, then never again during that phase — no flags or extra state needed. The `graphStableCounter` is a `protected` field in `StatefulAgent`, increments by 1 each step via `checkStable()`, and resets to 0 on new state discovery. Cost: ~5-15 calls per 10-minute run (one per stagnation phase). Note: `StatefulAgent.onGraphStable()` still handles the restart logic at `counter > threshold` — the LLM hook does NOT modify the restart mechanism.

`LlmRouter` owns the lifecycle of all infrastructure components (`SglangClient`, `LlmCircuitBreaker`, `ScreenshotCapture`, `ImageProcessor`, `ToolCallParser`, `CoordinateNormalizer`, `ApePromptBuilder`). It is instantiated once in `StatefulAgent`'s constructor when `Config.llmUrl` is non-null, and the same instance is used for the entire exploration session. There is no call budget limit — only timeout controls execution duration.

---

## Data Contracts

### Input

- `GUITree tree` — current screen's accessibility tree (source: `MonkeySourceApe.nextEventImpl()`)
- `State state` — current abstract state with visit count, activity, and actions (source: `Model`)
- `List<ModelAction> actions` — valid actions on current state, already priority-ranked by SATA+MOP (source: `StatefulAgent.adjustActionsByGUITree()`)
- `MopData mopData` — static analysis reachability data for monitored operations, may be null (source: `StatefulAgent._mopData`)
- `boolean isNewState` — whether this is the first visit to the current state, captured before `markVisited()` (source: `StatefulAgent._isNewState`)
- `int graphStableCounter` — consecutive steps without graph growth (source: `StatefulAgent.checkStable()`)
- `List<ActionHistoryEntry> recentActions` — last 3-5 executed actions with results (source: `StatefulAgent` ring buffer)

### Output

- `ModelAction` — matched action from the input actions list, ready to execute (for type_text actions, `resolvedNode.setInputText(text)` is already called before returning)
- `null` — LLM pipeline failed, no matching action found, or blocked by circuit breaker; caller falls back to SATA

### Side-Effects

- **Logging**: `[APE-RV] LLM` prefixed log entries for routing decisions, action selections, and fallbacks
- **Circuit breaker state**: success/failure recorded after each LLM attempt, affecting future routing decisions
- **Call counter**: `totalCalls` incremented at each `selectAction()` invocation for telemetry (no budget limit)
- **Network**: HTTP request to SGLang server (via `SglangClient`)
- **Device framebuffer**: screenshot capture (via `ScreenshotCapture`)

### Error

- All errors are handled internally by `LlmRouter.selectAction()` — no exceptions propagate to `StatefulAgent`.
- Network errors, unparseable responses, AND a null screenshot SHALL trigger `LlmCircuitBreaker.recordFailure()`. A persistent null-screenshot condition therefore opens the breaker and stops further LLM attempts for the recovery window.

---

## Invariants

- **INV-RTR-01**: `LlmRouter` SHALL only be instantiated when `Config.llmUrl` is non-null. When `Config.llmUrl` is null, `StatefulAgent` SHALL set its `_llmRouter` field to null and all LLM routing checks SHALL be skipped.
- **INV-RTR-02**: `LlmRouter.selectAction()` SHALL never throw an exception to the caller. All failures SHALL result in a null return with a warning log.
- **INV-RTR-03**: The `ModelAction` returned by `selectAction()` MUST be either (a) a member of the input `actions` list — a widget action matched by coordinate — or (b) a synthesized `LlmTapAction` of type `MODEL_LLM_TAP` constructed for the off-tree case. In case (b) the action is intentionally NOT a member of `actions`; it is a targetless action carrying the LLM pixel coordinate, mirroring how `EVENT_TRIGGER_ACTIVITY` (activity-frontier) returns a synthesized action not drawn from the state's action set. When even the off-tree construction does not apply (degenerate coordinate, boundary reject, `type_text`, or `back` with no back action), `selectAction()` returns null and the LLM coordinates are logged for telemetry.
- **INV-RTR-04**: LLM routing SHALL NOT modify `ModelAction.priority` values. LLM selects an action directly; it does not boost priorities. The MOP scoring pass runs independently before LLM routing.
- **INV-RTR-05**: The 2 LLM modes are independent: disabling one mode SHALL NOT affect the other. Each mode is gated by its own Config flag.
- **INV-RTR-06**: `LlmRouter.selectAction()` SHALL explicitly null out large intermediate objects (`pngBytes`, `base64Image`, `messages`) in a `finally` block to prevent memory pressure from accumulated screenshots.
- **INV-RTR-07**: `totalCalls` SHALL be incremented at the start of each `selectAction()` invocation, counting all attempts regardless of success or failure. The circuit breaker independently limits consecutive failures. There is no call budget limit.
- **INV-RTR-08**: `Config.llmPercentage` SHALL be clamped to the closed interval `[0.0, 1.0]` at load time. A configured value `< 0` SHALL become `0.0`; a value `> 1` SHALL become `1.0`.
- **INV-RTR-10**: The `[APE-LLM-CONFIG]` line SHALL be emitted exactly once per run, at `LlmRouter` construction, and SHALL report the effective sampling parameters (the same values placed into the request body by `SglangClient.buildRequestBody`), not the `ape.properties` map contents. When `Config.llmUrl` is null the `LlmRouter` is not constructed (INV-RTR-01) and no `[APE-LLM-CONFIG]` line is emitted.
- **INV-RTR-11**: The sum `timeoutCount + httpErrorCount + connErrorCount + parseErrorCount + imageErrorCount + internalErrorCount + screenshotFailedCount` SHALL equal the number of `selectAction()` attempts abandoned before the mapping step — the attempts that return null **without** emitting an `[APE-LLM-TEL]` line. Exactly one cause counter SHALL be incremented per abandoned attempt (none uncounted, none double-counted). `no_match` outcomes also return null from `selectAction()` but emit an `[APE-LLM-TEL]` line and are counted by `noMatchCount` only; they are outside this sum.
- **INV-RTR-12**: The `[APE-LLM-CONFIG-ACK]` line SHALL be emitted at most once per run, only after a successful `chat()` response. A run with zero successful responses SHALL emit no ACK line (its absence, combined with the failure-cause counters, is itself diagnostic).
- **INV-RTR-15**: A dead pair — `(stateKey, pixelX, pixelY)` for `llm_tap`, `(stateKey, Name.toXPath(), eventType)` for `matched` — SHALL never be returned by `selectAction()` again within the same run once dead per the death rule (**five** executions with `new_state=false`, the same threshold for every widget class the ban covers; a `new_state=true` execution neither counts toward nor resets the accumulated count). A `matched` pair whose resolved target is input-capable per `ApePromptBuilder.INPUT_CLASS_NAMES` SHALL never become dead at any strike count, and SHALL never be entered into the record; the exemption keys on the widget, not the event type, and has no counterpart on the `llm_tap` side, where no widget class exists. The ban anchor SHALL be the stable pair, never a list index. The `matched` anchor is `Name`-level and therefore abstract: one dead pair may cover several physical nodes, and ban counts SHALL NOT be reported as widget counts. The record is per-run and in-memory.
- **INV-RTR-16**: A dead-pair ban SHALL NOT invoke `breaker.recordFailure()` and SHALL NOT contribute to opening the circuit breaker; the banned decision records pipeline success and returns null through the `no_match` path with `reason=dead_pair`.
- **INV-RTR-17**: The tool name the model called SHALL constrain the matched `ActionType` in both matching passes: a `click` answer SHALL only ever return a `MODEL_CLICK` widget action (or an off-tree `MODEL_LLM_TAP`); a `type_text` answer SHALL only ever return an input-capable widget action; `long_click` MAY fall back from `MODEL_LONG_CLICK` to `MODEL_CLICK`.
- **INV-RTR-18**: The snap fallback SHALL measure point-to-rectangle (edge) distance with clamped `dx`/`dy`; a point inside a candidate's bounds has distance 0, and a point within tolerance of a widget's edge SHALL be snappable regardless of the widget's aspect ratio. The tolerance formula and its configuration keys are unchanged.
- **INV-RTR-19**: The stagnation LLM hook SHALL fire exactly once per stagnation episode: it fires at the first check where `graphStableCounter >= graphStableRestartThreshold / 2` with the episode flag armed, sets the flag, and the flag re-arms only when `graphStableCounter` resets to 0.
- **INV-RTR-20**: Every routing attempt abandoned at screenshot capture SHALL emit exactly one `[APE-LLM-ERROR] cause=screenshot` line carrying the foreground activity, and SHALL still emit no `[APE-LLM-TEL]` line; `screenshotFailedCount` remains its only cause counter (the INV-RTR-11 partition is unchanged).

---
## Requirements
### Requirement: New-State LLM Mode

When the plan enables the LLM new-state mode, the `LlmNewState` stage SHALL be assembled into the decision pipeline ahead of the stagnation, random, launcher, trigger, and SATA stages. Its `decide()` SHALL: (1) check the shared LLM precondition — action buffer empty AND the state has more than 2 actions — through the single `LlmGate` helper (the precondition exists in exactly one place; the pre-change triplication is deleted); (2) check the new-state trigger — `_isNewState` captured before `markVisited()` (unchanged capture semantics) — and the `LlmClient.allows()` breaker gate; (3) when all hold, invoke `LlmEngine.selectAction(..., "new-state", step)`; (4) on a non-null result, stamp `DecisionSource.LLM`/`PickChannel.LLM` (and resolve a synthesized `MODEL_LLM_TAP` against the state, unchanged) and return `Select`; otherwise return `Continue`.

The stage SHALL run after `adjustActionsByGUITree()` has assigned priorities (unchanged, INV-EXPL-11) and before any SATA rung.

#### Scenario: First visit to new state with LLM enabled
- **WHEN** the pipeline reaches `LlmNewState` on a first-visit state with buffer empty and 3+ actions
- **AND** the breaker allows and `LlmEngine.selectAction(...)` returns a non-null `ModelAction`
- **THEN** the stage SHALL return `Select` with `decision_source=LLM` (later stages not evaluated)

#### Scenario: First visit but circuit breaker open
- **WHEN** the state is new but `LlmClient.allows()` returns false
- **THEN** the stage SHALL return `Continue` and no HTTP call SHALL be made

#### Scenario: Revisit of known state
- **WHEN** `_isNewState` is `false`
- **THEN** the stage SHALL return `Continue` regardless of other conditions

#### Scenario: LLM returns null on new state
- **WHEN** `LlmEngine.selectAction(...)` returns `null`
- **THEN** the stage SHALL return `Continue` and the remaining pipeline SHALL decide the step

#### Scenario: precondition evaluated in one place
- **WHEN** the three LLM stages evaluate their preconditions on a step
- **THEN** all three SHALL consult the same `LlmGate` helper (buffer-empty ∧ actions > 2)
- **AND** no stage SHALL carry its own copy of the precondition expression

---

### Requirement: Stagnation LLM Mode

When the plan enables the LLM stagnation mode, the `LlmStagnation` stage SHALL be assembled after `LlmNewState` and before `LlmRandom`. The stage SHALL be consulted at most once per stagnation episode: the trigger is `graphStableCounter >= threshold / 2` AND the stage's per-episode fired flag is still armed (the pure predicate `stagnationMidpointReached`, owned by the stage). The fired flag SHALL be **owned by the stage** and SHALL be:

- burned inside `decide()` whenever the trigger fires — whatever the LLM answers (a null result is a failed attempt, not an unused one; the restart at the full threshold follows if stagnation persists);
- re-armed by the stage's `onStateTransition(edge)` hook on `NEW_ACTION`/`NEW_ACTION_TARGET` edges (a new edge ends the episode — the same event that resets `graphStableCounter`).

`graphStableCounter` itself remains agent-owned shared exploration state (the forced-restart mechanism consumes it independently); on an accepted escape the stage SHALL reset it to 0 through the `StepContext`'s single declared write method. The restart mechanism is unchanged: `onGraphStable()` still restarts at `counter > threshold`.

#### Scenario: LLM provides escape action at or past the stagnation midpoint
- **WHEN** `graphStableCounter` reaches `threshold / 2` (or any greater value with the flag armed), the shared precondition holds, and the breaker allows
- **THEN** the stage SHALL burn the flag and invoke `LlmEngine.selectAction(..., "stagnation", step)`
- **AND** on a non-null result it SHALL reset `graphStableCounter` to 0 and return `Select` with `decision_source=LLM`

#### Scenario: hook fires once per episode even when the LLM fails
- **WHEN** the trigger fires and the LLM returns null
- **THEN** the stage SHALL return `Continue` with the flag burned
- **AND** the stage SHALL NOT fire again until a new edge re-arms it
- **AND** if `graphStableCounter` eventually exceeds the full threshold, `requestRestart()` SHALL be called (existing behavior)

#### Scenario: new edge re-arms the episode
- **WHEN** the flag is burned and a later step records a `NEW_ACTION` edge
- **THEN** the stage's `onStateTransition` SHALL re-arm the flag
- **AND** a subsequent stagnation reaching the midpoint SHALL fire again (new episode)

#### Scenario: midpoint skipped by a counter jump still fires
- **WHEN** the counter passes from below the midpoint to above it without equaling `threshold / 2` at any check
- **THEN** the stage SHALL still fire at the first check where `graphStableCounter >= threshold / 2`

#### Scenario: Stagnation mode disabled
- **WHEN** the plan does not enable the stagnation mode
- **THEN** the `LlmStagnation` stage SHALL NOT exist in the pipeline
- **AND** the restart behavior SHALL proceed unchanged

---

### Requirement: Action Selection Pipeline

`LlmEngine.selectAction(GUITree tree, State state, List<ModelAction> actions, MopData mopData, List<ApePromptBuilder.ActionHistoryEntry> recentActions, String mode, int step)` SHALL run the LLM decision pipeline over the decomposed units and return `ModelAction` or `null`. The step semantics are **unchanged** from the pre-decomposition `LlmRouter.selectAction`; only the owner of each step changes:

**The argument list is the pre-decomposition one, and it stays that way for a reason worth stating.** `mopData` and `recentActions` are per-step, agent-owned values that step 4 below hands to `ApePromptBuilder.build(...)`, which cannot build a prompt without them. The engine is constructed once per run and owned by `RunContext` (see `LLM Unit Lifecycle and Ownership`), while the per-step view belongs to the stages — so for the engine to source them itself it would have to hold a `StepContext` or the agent, and design D2 exists to prevent exactly that. The calling stage passes them from `ctx.mopData()` and `ctx.actionHistory()`, unchanged.

1. `LlmTelemetry` counts the attempt (`totalCalls++`, per INV-RTR-07).
2. `ScreenshotStep` determines device dimensions and captures the screenshot. Null capture → breaker failure recorded via `LlmClient`, `screenshot_failed` counter + `[APE-LLM-ERROR] cause=screenshot` with activity and failing stage via `LlmTelemetry`, return null.
3. `ScreenshotStep` resizes and base64-encodes. Null → `image` cause, return null.
4. `ApePromptBuilder.build(...)` builds the messages; `LlmTelemetry` logs `[APE-LLM-PROMPT]`.
5. `LlmClient.chat(messages, tools)` — the tools schema chosen by the same `hasInputField` predicate the prompt used (INV-LLM-11). Null → breaker failure, cause read once from the client's error seam (INV-LLM-08), cause counters + `[APE-LLM-ERROR]` via `LlmTelemetry`, return null.
6. `LlmTelemetry` emits the once-per-run `[APE-LLM-CONFIG-ACK]` on the first successful response (INV-RTR-12) and logs `[APE-LLM-RESPONSE]`.
7. `ToolCallParser.parse(response)` — including the raw-arguments repair pipeline for native tool-call malformations (`SglangClient.ToolCall.rawArguments` → Level 1 → shared `parseJsonString`), surfacing through the existing `repair=` field (INV-LLM-10, INV-RTR-14). Null → `parse` cause (client seam NOT consulted), return null.
8. `CoordinateMapper` normalizes coordinates, applies the boundary bands, maps to a `ModelAction` (containment → snap tolerance → off-tree `LlmTapAction` synthesis → `fixTextEdit` conversion → back/long-click preference), and applies the dead-pair ban check (a banned answer is a refused answer: same caller-visible path as `no_match`, breaker still records success — INV-RTR-15/16).
9. `LlmClient` records breaker success; `LlmTelemetry` accounts tokens/latency, classifies the outcome (`matched`/`llm_tap`/`no_match` + `reason`), computes/receives the nearest-widget fields, and emits `[APE-LLM-TEL]` with the same field set as before.
10. The engine returns the mapped action or null. It SHALL never throw (INV-RTR-02): an unexpected exception is the `internal` cause. Large temporaries SHALL be nulled in a `finally` block (INV-RTR-06).

**type_text handling**: unchanged — when the match is an input-capable widget and text is present, `setInputText(text)` is applied before returning.

**Known defect preserved, deliberately.** "Unchanged" here includes a defect measured at 28 of 1,233 LLM responses (2.3%): a `type_text` answer can execute a `MODEL_LONG_CLICK`. The containment pass restricts the candidate's `ActionType` only when the tool is `"click"` (`LlmRouter.java:689`), and `fixTextEdit` returns the match untouched for any tool that is neither `click` nor `long_click` (`:807`), so the long-click preference can win on a `type_text` answer. This stage is behavior-neutral by contract (parity-gated, R8): `CoordinateMapper` SHALL reproduce this path exactly, defect included. The fix is **out of scope here** and belongs to a separate change against `CoordinateMapper`, whose slicing is precisely what makes it testable in a JVM unit. Recording it is mandatory: a silently inherited defect in a newly extracted unit is indistinguishable from a slicing regression when the parity oracle later disagrees.

The dead-pair outcome feedback SHALL continue to flow from the `[APE-OUTCOME]` join-buffer site in `StatefulAgent` into the ban record — now `CoordinateMapper.recordLlmOutcome(...)` reached through `RunContext`'s LLM units — with unchanged key material and strike semantics.

#### Scenario: Full pipeline success
- **WHEN** `selectAction()` is called with a valid GUITree and the server is responsive, and the LLM returns `click` at coordinates mapping into a widget's bounds
- **THEN** that `ModelAction` SHALL be returned, the telemetry line SHALL carry `result=matched`, and the breaker SHALL record success

#### Scenario: Screenshot capture fails trips the breaker
- **WHEN** `ScreenshotStep` returns null (secure window)
- **THEN** the engine SHALL return null with no HTTP request made
- **AND** the breaker SHALL record a failure and `screenshot_failed` SHALL be counted with its `[APE-LLM-ERROR] cause=screenshot` line

#### Scenario: Repaired native tool call keeps the repair telemetry
- **WHEN** the model returns a malformed native `tool_calls` arguments string that `ToolCallParser` recovers via the raw-arguments repair pipeline and the coordinate resolves to a widget
- **THEN** the `[APE-LLM-TEL]` line SHALL carry `repair=<form>` and the decision SHALL count under both `matched` and `repaired` (INV-LLM-10, INV-RTR-14 unchanged)

#### Scenario: banned result is refused at step 10, not failed
- **WHEN** the mapped action's ban key has reached the strike threshold
- **THEN** the engine SHALL return null with `result=no_match reason=dead_pair` telemetry
- **AND** `LlmClient` SHALL still record success (a refused answer is not a pipeline failure)
- **AND** the check SHALL run inside step 8 above — after the mapping, before the return — so a banned decision is a refused answer rather than a failed pipeline

#### Scenario: Off-tree element becomes a coordinate tap
- **WHEN** the pipeline succeeds with a `click` at in-bounds pixel `(600, 900)` on a 1080x1794 device
- **AND** `CoordinateMapper` finds no widget containing the point and none within the snap tolerance
- **THEN** `selectAction()` SHALL return an `LlmTapAction` of type `MODEL_LLM_TAP` carrying `(600, 900)`
- **AND** the outcome SHALL be classified `llm_tap`, not `no_match` (the synthesis is a decision, and `Coordinate-to-ModelAction Mapping` owns the unit-level rule this end-to-end path exercises)

#### Scenario: no_match reason is always one of three
- **WHEN** any decision in a run ends as `result=no_match`
- **THEN** its `[APE-LLM-TEL]` line SHALL carry exactly one `reason` from `degenerate`, `boundary`, `dead_pair`
- **AND** the closure SHALL hold across the decomposition: `CoordinateMapper` produces the first two and the ban check the third, and `LlmTelemetry` SHALL have no fourth reason to emit

#### Scenario: Engine never throws
- **WHEN** any unexpected exception occurs inside the engine
- **THEN** the engine SHALL catch it, count `internal`, emit `[APE-LLM-ERROR] cause=internal`, and return null

---

### Requirement: Coordinate-to-ModelAction Mapping

`LlmRouter.mapToModelAction(int pixelX, int pixelY, String actionType, String text, List<ModelAction> actions, State state, int deviceWidth, int deviceHeight)` SHALL map LLM output coordinates to a `ModelAction` for the current state, returning a matched widget action, a synthesized off-tree `LlmTapAction`, or null.

**Boundary reject**: Before coordinate matching, if `pixelY < deviceHeight * Config.llmBoundaryTopPct` (default `0.05` — status bar, and any degenerate `(0,0)` emission) or `pixelY > deviceHeight * Config.llmBoundaryBottomPct` (default `0.94` — navigation bar), `mapToModelAction` SHALL return null and log the boundary reject. This prevents the LLM from tapping system UI, and no off-tree tap is synthesized for these coordinates. The band fractions are configuration-exposed (J1b) with defaults reproducing the previous hard-coded `0.05`/`0.94` (INV-RTR-14).

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

**Off-tree coordinate tap (dynamic element)**: If no action's bounds contain the point AND no action is within edge-distance tolerance AND `actionType` is `"click"` or `"long_click"`, `mapToModelAction` SHALL return `new LlmTapAction(state, pixelX, pixelY, "long_click".equals(actionType))` — a targetless `MODEL_LLM_TAP` action carrying the LLM coordinate. Because the boundary reject runs first, a coordinate reaching this point is guaranteed in-bounds and non-degenerate. For any other `actionType` (e.g. `type_text`), `mapToModelAction` SHALL return null. This is the mechanism by which APE acts on elements invisible to UIAutomator (game canvas, custom view, Compose-without-semantics).

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

- **WHEN** `mapToModelAction(540, 50, "click", null, actions, state, 1080, 1920)` is called on a 1080x1920 device
- **AND** `pixelY (50) < deviceHeight * Config.llmBoundaryTopPct (96 at the 0.05 default)`
- **THEN** `mapToModelAction` SHALL return null
- **AND** no `LlmTapAction` SHALL be constructed

#### Scenario: Boundary reject — navigation bar

- **WHEN** `mapToModelAction(540, 1850, "click", null, actions)` is called on a 1080x1920 device
- **AND** `pixelY (1850) > deviceHeight * Config.llmBoundaryBottomPct (1804.8 at the 0.94 default)`
- **THEN** `mapToModelAction` SHALL return null

#### Scenario: Band configurable without rebuild

- **WHEN** `ape.properties` contains `ape.llmBoundaryBottomPct=0.98`
- **THEN** a `pixelY=1850` click on a 1080x1920 device SHALL pass the boundary check (1850 < 1881.6) and proceed to coordinate matching

---

### Requirement: Effective LLM Config Manifest

`LlmRouter` SHALL emit exactly one `[APE-LLM-CONFIG]` line at construction time, recording the **effective** LLM configuration actually in use for the run. The values SHALL be read from the parameters passed to the `SglangClient` / router (the effective values), NOT from the `ape.properties` `configurations` map. The config dump (`Config.printConfigurations`) is insufficient for arm attribution: it echoes the raw property string rather than the effective value (e.g. `llmPercentage` is clamped to `[0,1]` in its field initializer — the dump would show `1.5`, the manifest shows the effective `1.0`), and it runs only at `tearDown()` and is lost when the platform kills the process at the time limit. The manifest line makes each run's `.trace` self-describing from its first seconds, independent of how the run ends.

The line SHALL carry the following fields:

| Field | Source |
|-------|--------|
| `model` | effective model name passed to `SglangClient` |
| `temperature` | effective `temperature` |
| `top_p` | effective `top_p` |
| `top_k` | effective `top_k` |
| `max_tokens` | effective `max_tokens` — `Config.llmMaxTokens` (default `1024`), the same value placed in the request body |
| `timeout_ms` | effective `Config.llmTimeoutMs` passed to `SglangClient` (connect and read timeout; shapes the `timeout` failure-cause rate across arms) |
| `prompt_variant` | `ApePromptBuilder.getPromptVariant()` |
| `llm_percentage` | `Config.llmPercentage` (post-clamp, per INV-RTR-08; the clamp lives in the `Config` field initializer, so any read is post-clamp) |
| `on_new_state` | `Config.llmOnNewState` |
| `on_stagnation` | `Config.llmOnStagnation` |
| `stagnation_threshold` | `Config.graphStableRestartThreshold` (the stagnation hook fires at `threshold / 2`; config-settable, gates stagnation-mode LLM call frequency) |
| `url` | `Config.llmUrl` |

Emission is governed by INV-RTR-10. The field set is unchanged by this change; only the `max_tokens` source moves from a router-hardcoded literal to `Config.llmMaxTokens` (INV-RTR-14 — identical value at default).

#### Scenario: Manifest records defaults not present in ape.properties

- **WHEN** a run starts with `ape.llmUrl` set but `ape.llmTemperature` / `ape.llmTopP` / `ape.llmTopK` / `ape.llmMaxTokens` left at their code defaults
- **THEN** exactly one `[APE-LLM-CONFIG]` line SHALL be emitted
- **AND** it SHALL carry the effective default values (e.g. `temperature=0.3`, `max_tokens=1024`)

#### Scenario: Manifest records effective clamped percentage

- **WHEN** a run starts with `ape.llmPercentage=1.5` in `ape.properties`
- **THEN** the `[APE-LLM-CONFIG]` line SHALL carry `llm_percentage=1.0` (the effective post-clamp value), even though the config dump would echo the raw `1.5`

#### Scenario: Manifest tracks a configured max_tokens

- **WHEN** a run starts with `ape.llmMaxTokens=2048`
- **THEN** the `[APE-LLM-CONFIG]` line SHALL carry `max_tokens=2048`, matching the request body

### Requirement: Server Model Acknowledgement

`LlmRouter` SHALL emit one `[APE-LLM-CONFIG-ACK] server_model=<model>` line after the first successful `chat()` response of the run, carrying the model identifier reported by the server in the response envelope (`unknown` when the response omits the field). The `[APE-LLM-CONFIG]` manifest records the model the run *requested*; the ACK records what the server *actually served* — the pair proves an arm talked to the intended model without inspecting the server. Emission is governed by INV-RTR-12.

#### Scenario: Server model acknowledged once

- **WHEN** the first successful `chat()` response of a run reports `model=qwen3-vl-8b` and 40 further successful calls follow
- **THEN** exactly one `[APE-LLM-CONFIG-ACK] server_model=qwen3-vl-8b` line SHALL be emitted, after the first successful response

#### Scenario: No acknowledgement without a successful response

- **WHEN** every `chat()` call of a run fails (server down)
- **THEN** zero `[APE-LLM-CONFIG-ACK]` lines SHALL be emitted

---

### Requirement: LLM Telemetry Logging

`LlmRouter` SHALL log structured per-decision telemetry on an `[APE-LLM-TEL]` line and an aggregate `[APE-RV] LLM Summary` line.

**Per-decision structured log** (parseable, follows the `[RVTRACK:LLM]` pattern from rvsmart/rvagent):

| Field | Values |
|-------|--------|
| `step` | agent exploration step of the routing attempt — the same value the step's `[APE-STEP]` line carries, supplied by the calling agent (`getTimestamp()` at selection) |
| `mode` | `new-state`, `stagnation`, `random` |
| `result` | `matched`, `llm_tap`, `no_match` |
| `reason` | emitted only on `no_match`, immediately after `result=`: `degenerate` (parsed coordinate `(0,0)`), `boundary` (status/nav band), or `dead_pair` (the mapped result resolved to a banned dead pair — "Deterministic Dead-Pair Ban") |
| `repair` | emitted only when the decision's `ParsedAction` carries a repair-form label other than `none` (per `llm-infrastructure` INV-LLM-09): `missing_y`, `array_xy`, `quoted_xy`, or `int_scan`. Absent on a clean parse. |
| `tokens_in/out` | From `ChatResponse.usage.prompt_tokens` / `completion_tokens` (0 if unavailable) |
| `time_ms` | Wall clock milliseconds for the full pipeline (screenshot → response) |

The `step` field makes the LLM call telemetry joinable to the decision and its outcome on one key: `[APE-LLM-TEL] step=N` ↔ `[APE-STEP] step=N` ↔ `[APE-OUTCOME] step=N` (the latter per the scoring-pipeline capability). The value is passed into `selectAction()` by the caller; `LlmRouter` does not read agent state itself.

| `result` | Meaning |
|----------|---------|
| `matched` | LLM coordinate resolved to a widget in the `GUITree` |
| `llm_tap` | LLM coordinate matched no widget; an off-tree `MODEL_LLM_TAP` was synthesized and dispatched |
| `no_match` | LLM coordinate was discarded; accompanied by `reason=degenerate`, `reason=boundary`, or `reason=dead_pair` |

The `repair` field is orthogonal to `result`: a repaired tool call yields a normal `matched`, `llm_tap`, or `no_match` outcome and additionally carries `repair=<form>`.

Routing attempts abandoned before the mapping step (null screenshot, image-processing failure, HTTP/timeout/connection failure, parse failure, unexpected internal error) do not emit an `[APE-LLM-TEL]` line; they are counted in the aggregate summary only. Each abandoned attempt SHALL emit one `[APE-LLM-ERROR]` line naming its cause — **including the screenshot-capture failure**: the previous silent-screenshot exception is removed, because the silence hid the dominant LLM-arm failure mode (147 screenshot failures concentrated in 4 FLAG_SECURE APKs, co-located with 100% of the 57 breaker trips — the breaker silently disabled the LLM with no attributable line in the trace). The screenshot failure's error line SHALL be `[APE-LLM-ERROR] step=<N> cause=screenshot activity=<current activity> detail=<stage>`, where `<stage>` is read from the `ScreenshotCapture` failure-cause seam (`llm-infrastructure` capability) and `<current activity>` names the foreground activity so per-app FLAG_SECURE degradation is countable offline. The existing `[APE-RV] LLM screenshot capture failed` line MAY remain alongside it.

**Discriminated failure causes:** the previously-collapsed `null` outcome SHALL be attributed to exactly one named cause. `LlmRouter` SHALL maintain a separate counter per cause in place of the single opaque `nullCount`:

| Cause | Meaning | Counter |
|-------|---------|---------|
| `timeout` | connect/read timeout (`SocketTimeoutException`) | `timeoutCount` |
| `http_<status>` | server returned a non-200 HTTP status | `httpErrorCount` |
| `connection` | other I/O failure reaching the server | `connErrorCount` |
| `parse` | response received but unusable: OpenAI envelope not parseable (client-side) or no tool call extractable from a successful response (router-side) | `parseErrorCount` |
| `image` | `ImageProcessor` returned null while preparing the screenshot payload | `imageErrorCount` |
| `internal` | unexpected exception caught by the `selectAction()` catch-all | `internalErrorCount` |
| `screenshot` | `ScreenshotCapture` returned null (e.g. FLAG_SECURE) | `screenshotFailedCount` (unchanged) |

A `parse` failure is the residual after `ToolCallParser` recovery: it is counted only when `parse()` returns null despite the quoted-collapsed-XY fix and last-resort integer extraction (per the `llm-infrastructure` capability). A recovered tool call is not a `parse` failure — it is a successful decision carrying `repair=<form>`.

**Cause attribution SHALL follow the failure point.** When `chat()` returns null, the cause SHALL be read from `SglangClient.getLastErrorCause()` — the only site where that seam MAY be consulted (the client resets it per invocation; at any other site its value belongs to an earlier call and is stale). Failures occurring after `chat()` returns non-null SHALL be attributed by `LlmRouter` directly, without consulting `getLastErrorCause()`: tool-call extraction failure (`ToolCallParser.parse()` returning null) is `parse`, an `ImageProcessor` null result is `image`, and an unexpected exception in the routing pipeline is `internal`. The screenshot cause is attributed at the capture site, with its stage detail read from the `ScreenshotCapture` seam.

At the failure point, `LlmRouter` SHALL emit `[APE-LLM-ERROR] step=<N> cause=<cause> detail=<message>`, where `step` is the same join key carried by `[APE-LLM-TEL]` — a failed attempt is thereby attributable to the step whose selection it interrupted. The `null` return contract (INV-RTR-02) is unchanged; only the attribution is added.

**Prompt-log parseability:** the `[APE-LLM-PROMPT]` lines (system/user_text dumps emitted by the router) SHALL be per-element parseable: widget-derived text interpolated into the prompt's element lines SHALL have `\n`/`\r` flattened to spaces by the prompt builder (the metadata path already does this via `capMeta`; the element-line display text gets the same treatment). The prompt's own intentional multi-line structure is unchanged.

**Additional event logs:**

| Event | Log Format |
|-------|-----------|
| Circuit breaker blocked | `[APE-RV] LLM circuit breaker OPEN, skipping (trips=<N>)` |
| Pipeline step failed | `[APE-LLM-ERROR] step=<N> cause=<cause> detail=<message>` |

The circuit-breaker-blocked line SHALL be emitted at the **first** routing-predicate decline of each open episode (the moment a predicate declines a call because the breaker does not allow the attempt); subsequent declines within the same open episode SHALL NOT re-emit it. The emission check SHALL use a side-effect-free breaker state query (`isOpen()`), never a second `shouldAttempt()` call, and SHALL distinguish breaker-caused declines from predicates returning false for other reasons (mode disabled, coin flip, stagnation not reached).

**Aggregate summary** (printed at `StatefulAgent.tearDown()`):
```
[APE-RV] LLM Summary calls=<N> tokens_in=<N> tokens_out=<N> time_ms=<N> matched=<N> llm_tap=<N> no_match=<N> dead_pair=<N> repaired=<N> timeout=<N> http_error=<N> conn_error=<N> parse_error=<N> image_error=<N> internal_error=<N> screenshot_failed=<N> breaker_trips=<N>
[APE-RV] Decision ratio: LLM=<N>/<total> (<pct>%), SATA=<N>/<total> (<pct>%)
```

The `decisions` denominator for the `[APE-RV] LLM Decision ratio` line SHALL be `matched + llm_tap + no_match + (timeout + http_error + conn_error + parse_error + image_error + internal_error + screenshot_failed)`. The seven cause counters form a partition of the retired `null` count; `dead_pair` and `repaired` are overlay counters, NOT part of the denominator:

- `dead_pair=<N>` counts decisions banned by the dead-pair ban; each is already counted under `no_match`. It is maintained separately so bucket D — the falsification gate of the ban — is countable from the summary line alone.
- `repaired=<N>` (`repairedCount`) counts successful decisions whose tool call required a `ToolCallParser` repair (repair-form label other than `none`); each is already counted under exactly one of `matched` / `llm_tap` / `no_match`.

`llmTapCount` (`llm_tap=<N>`) counts synthesized off-tree taps. `screenshot_failed` counts the routing attempts abandoned because `ScreenshotCapture` returned null; it is a peer cause counter, maintained separately so per-app degradation of the LLM arm to SATA is countable post-hoc from the summary line alone.

#### Scenario: Matched widget logged

- **WHEN** `selectAction()` returns a widget `ModelAction` of type `MODEL_CLICK`
- **THEN** the `[APE-LLM-TEL]` line SHALL carry `result=matched`

#### Scenario: LLM call telemetry joins step and outcome on one key

- **WHEN** the new-state hook routes to the LLM at step 42, the call succeeds and the mapped action executes producing a recorded transition
- **THEN** the `[APE-LLM-TEL]` line SHALL carry `step=42`
- **AND** the `[APE-STEP]` and `[APE-OUTCOME]` lines of the same decision SHALL carry `step=42`

#### Scenario: dead-pair ban visible in TEL and summary

- **WHEN** two decisions in a run are banned by the dead-pair ban and one is discarded for a boundary coordinate
- **THEN** the two banned decisions' `[APE-LLM-TEL]` lines SHALL carry `result=no_match reason=dead_pair`
- **AND** the summary SHALL report `no_match=3 dead_pair=2`

#### Scenario: Screenshot failure emits an attributable error line

- **WHEN** `ScreenshotCapture.capture()` returns null on a FLAG_SECURE window while activity `org.fedorahosted.freeotp.MainActivity` is foreground
- **THEN** one `[APE-LLM-ERROR] step=<N> cause=screenshot activity=org.fedorahosted.freeotp.MainActivity detail=<stage>` line SHALL be emitted
- **AND** `screenshotFailedCount` SHALL be incremented
- **AND** `breaker.recordFailure()` SHALL be called (unchanged breaker semantics)

#### Scenario: Repaired tool call logged and counted

- **WHEN** at step 61 the model returns `{"name": "click", "arguments": {"x": "500, 527}}`, `ToolCallParser` recovers it via the quoted-collapsed-XY fix, and the resulting coordinate resolves to a widget
- **THEN** the `[APE-LLM-TEL]` line SHALL carry `step=61 result=matched repair=quoted_xy`
- **AND** the aggregate summary SHALL count the decision under both `matched=<N>` and `repaired=<N>`

#### Scenario: no_match reason separated

- **WHEN** one decision is discarded for a `(0,0)` coordinate and another for a navigation-band coordinate
- **THEN** the first `[APE-LLM-TEL]` line SHALL carry `result=no_match reason=degenerate`
- **AND** the second SHALL carry `result=no_match reason=boundary`

#### Scenario: Timeout and HTTP failure discriminated

- **WHEN** one routing attempt fails because the read times out and a later attempt fails because the server returns HTTP 500
- **THEN** the first SHALL emit `[APE-LLM-ERROR] cause=timeout ...` and increment `timeoutCount`
- **AND** the second SHALL emit `[APE-LLM-ERROR] cause=http_500 ...` and increment `httpErrorCount`
- **AND** the summary SHALL report `timeout=1 http_error=1`

#### Scenario: Router-side parse failure attributed without the client seam

- **WHEN** `chat()` returns a non-null response but `ToolCallParser.parse()` extracts no tool call even after the quoted-collapsed-XY fix and last-resort integer extraction
- **THEN** `[APE-LLM-ERROR] cause=parse ...` SHALL be emitted and `parseErrorCount` incremented
- **AND** `getLastErrorCause()` SHALL NOT be consulted (the HTTP call succeeded; its value is stale)

#### Scenario: Circuit breaker event logged once per open episode

- **WHEN** the breaker trips to OPEN with 2 trips recorded and routing predicates subsequently decline 5 calls during the same open window
- **THEN** exactly one `[APE-RV] LLM circuit breaker OPEN, skipping (trips=2)` line SHALL be emitted, at the first declined call
- **AND** no LLM HTTP call SHALL be made while the breaker is OPEN

#### Scenario: Stagnation mode triggered

- **WHEN** `shouldRouteStagnation(150)` is called with `graphStableRestartThreshold = 200` and the episode flag armed
- **THEN** a log entry SHALL be emitted: `[APE-RV] LLM mode=stagnation, state=MainActivity#abc123`

#### Scenario: widget text with a newline does not break the prompt log

- **WHEN** an element's widget text contains `"line1\nline2"` and the `[APE-LLM-PROMPT] user_text=` dump is emitted
- **THEN** the element's line in the dump SHALL contain `"line1 line2"` (flattened)
- **AND** the number of element lines SHALL equal the number of elements

### Requirement: Probabilistic LLM Routing

When the plan enables probabilistic routing (`llm.percentage > 0`), the `LlmRandom` stage SHALL be assembled after `LlmStagnation` and before `MopLauncher`. Its trigger SHALL be `random.nextDouble() < percentage`, evaluated only after the shared `LlmGate` precondition holds, followed by the `LlmClient.allows()` breaker gate — the same conjunct order and short-circuiting as before the restructuring, so the seeded draw sequence is unchanged (`decision-pipeline` INV-DP-10). When `llm.percentage` is `0.0` the stage SHALL NOT exist (no draw is ever consumed — identical to the pre-change short-circuit).

**Which stream the coin comes from, stated because the run has two.** The draw SHALL come from the **agent's** generator — `ape.getRandom()`, Monkey's `mRandom`, which is what the pre-decomposition router was constructed with — reached through the stage's collaborator. It SHALL NOT come from `RunContext.rng()`. The two are seeded from one number but they are **different `Random` instances**: `RunContext`'s constructor seeds `RandomHelper` from the same value Monkey's own generator was built from, and `SataAgent` draws from both. Moving the coin from one to the other would therefore shift every later `RandomHelper` draw in an LLM arm — a real change in what a device does, and one the parity goldens cannot see, because the oracle's scripted LLM replaces the coin outright (INV-ORA-03). Behavior-neutrality here is a claim about the draw *sequence*, not only about the seed.

The redundant `percentage > 0` conjunct SHALL be dropped when the trigger moves into the stage: it is the stage's own assembly condition (INV-DP-03), so inside the stage it is necessarily true, and it was already the predicate's first conjunct — a zero rate drew no coin before and assembles no stage now, which is what makes the deletion draw-neutral.

When the stage fires and the engine returns a non-null action, the telemetry mode label SHALL be `"random"`.

#### Scenario: Default 2% routing
- **WHEN** `llm.percentage` is `0.02`, no earlier stage selected this step, the precondition holds, `random.nextDouble()` returns a value < 0.02, and the breaker allows
- **THEN** the stage SHALL invoke the engine with mode `"random"`

#### Scenario: Disabled
- **WHEN** `llm.percentage` is `0.0`
- **THEN** the `LlmRandom` stage SHALL NOT be assembled and no coin SHALL be drawn on any step

#### Scenario: Priority order preserved
- **WHEN** `_isNewState` is `true` and the new-state mode is enabled, with `llm.percentage = 0.7`
- **THEN** the `LlmNewState` stage SHALL decide the step (hard preemption)
- **AND** at most one LLM call SHALL be made for that step

#### Scenario: draw order preserved under the same seed
- **WHEN** two builds (pre-pipeline and pipeline) run the same preset, seed, and fixtures
- **THEN** the sequence of `nextDouble()` draws consumed by probabilistic routing SHALL be identical

#### Scenario: High percentage (70%)
- **WHEN** `llm.percentage` is `0.7`, neither the new-state nor the stagnation stage decided the step, and the precondition holds
- **THEN** the `LlmRandom` stage's trigger SHALL hold on approximately 70 % of the steps that reach it
- **AND** the rate SHALL be the plan's value applied to a single draw, never a per-stage rescaling: the stage is assembled with `percentage` and evaluates `nextDouble() < percentage` once per reached step

---

### Requirement: Config — llmPercentage clamping

`Config.llmPercentage` SHALL be clamped to `[0.0, 1.0]` at load (`Config.java:153`). This rejects malformed probabilities: a value `> 1.0` would otherwise make `shouldRouteRandom()` always fire (since `random.nextDouble() < 1.5` is always true), and a negative value is meaningless.

#### Scenario: Value above 1 is clamped
- **WHEN** `ape.properties` sets `ape.llmPercentage=1.5`
- **THEN** `Config.llmPercentage` SHALL be `1.0`

#### Scenario: Negative value is clamped
- **WHEN** `ape.properties` sets `ape.llmPercentage=-0.2`
- **THEN** `Config.llmPercentage` SHALL be `0.0`

#### Scenario: In-range value unchanged
- **WHEN** `ape.properties` sets `ape.llmPercentage=0.7`
- **THEN** `Config.llmPercentage` SHALL be `0.7`

### Requirement: Config — llmPercentageNoSubstrate seam

`Config.llmPercentageNoSubstrate` (double) SHALL be declared in `Config.java` and loaded via `ape.llmPercentageNoSubstrate`, default `-1`. The value `-1` is a sentinel meaning "inherit `Config.llmPercentage`" — it does NOT mean a routing percentage of −1. In the run-spec `Feature` model the key is a sub-parameter owned by the `LLM` feature with declared neutral value `-1`: every current baseline arm pushes `-1` explicitly (including non-LLM arms), which resolution accepts as an inert key (INV-RUN-05 of `run-spec`); an explicit value `>= 0` on a plan without the `LLM` feature aborts resolution as a missing dependency.

- The `-1` sentinel SHALL be exempt from the `[0.0, 1.0]` clamp applied to `llmPercentage` (INV-RTR-08): clamping would collapse the sentinel to `0.0`. When the configured value is `-1`, it SHALL pass through unclamped.
- When the configured value is `>= 0` (on an LLM plan), it SHALL be clamped to `[0.0, 1.0]` exactly like `llmPercentage`.

This capability still has NO consumer of `llmPercentageNoSubstrate`. `LlmRouter`, `shouldRouteRandom()`, the routing predicates, and all telemetry SHALL be unchanged; routing SHALL continue to use `Config.llmPercentage` unconditionally. The seam exists so round-2 adaptive routing (F′) can, without a protocol change, read `isWidgetlessSubstrate()` at load and substitute this percentage for widgetless apps.

- **INV-RTR-09**: `llmPercentageNoSubstrate` SHALL have no effect on any routing decision; it SHALL be loaded and exposed only. Its `-1` default SHALL pass through the clamp unchanged; a configured value `>= 0` SHALL be clamped to `[0.0, 1.0]`.

#### Scenario: default sentinel not clamped
- **WHEN** `ape.properties` does not set `ape.llmPercentageNoSubstrate`
- **THEN** `Config.llmPercentageNoSubstrate` SHALL equal `-1` (not clamped to `0.0`)

#### Scenario: configured value clamped
- **WHEN** an LLM arm's `ape.properties` sets `ape.llmPercentageNoSubstrate=1.5`
- **THEN** `Config.llmPercentageNoSubstrate` SHALL be `1.0`

#### Scenario: no routing behaviour change
- **WHEN** `ape.llmPercentageNoSubstrate=0.7` on an LLM arm and an app is widgetless
- **THEN** `shouldRouteRandom()` SHALL still use `Config.llmPercentage` (unchanged); the no-substrate value SHALL NOT be consumed

#### Scenario: explicit sentinel on a non-LLM arm is inert, not an error
- **WHEN** a non-LLM arm's `ape.properties` contains `ape.llmPercentageNoSubstrate=-1` (as every current baseline arm does) and no `ape.llmUrl`
- **THEN** resolution SHALL succeed and the key SHALL be listed as `inert` in the `RUN_START` echo

### Requirement: Deterministic Dead-Pair Ban

`CoordinateMapper` SHALL maintain a per-run, in-memory record of **dead pairs** — LLM decisions that already executed in this run without producing a new state — and SHALL refuse to return a result that resolves to a dead pair. Measured motivation: 25.6% of LLM calls (10,081/39,341) re-emit an already-executed (state, coordinate) pair, and those repeats produced **0 new states in 10,081 attempts** (Wilson CI [0.00–0.04]); the anti-repetition prompt instruction exists and is ignored. Banning by subtraction (removing the option) is the externally validated mechanism (Guardian: 36% repetition persists under instruction); the detection is deterministic in the harness — the model is never asked to self-reflect.

**Ban keys** (the anchor is always the stable pair, NEVER a list index):
- an `llm_tap` result is keyed by `(state.getStateKey(), pixelX, pixelY)` — exact emitted-coordinate equality;
- a `matched` result is keyed by `(state.getStateKey(), widget stable id, eventType)`, where the widget stable id is **the action `Name`'s XPath** (`Name.toXPath()`) — the same widget identity used by `UICoverageTracker.widgetId` and by the MOP revisit cap. It is **not** a per-node identity: `GUITreeNode` exposes no XPath of its own, and a `Name` is an abstraction that may resolve to several nodes.

**Granularity of the `matched` key, stated because it bounds what a ban means.** Since the key is `Name`-level, **banning one pair withdraws that action from every node the `Name` resolves to in that state**. Measured on the calibration corpus: 16.3% of targeted steps (23,441 of 144,174) resolve more than one node. This is accepted rather than avoided — a node-derived alternative such as `(activity, className, resourceId, actionType)` is coarser still, colliding on at least 18.3% of anchors and on 36.3% of those whose `resourceId` is empty, which is 57.6% of clicks — but it constrains interpretation: a dead-pair count is a count of **abstract** pairs, and SHALL NOT be reported as a count of physical widgets withdrawn.

**Death rule (k=5, with input-capable widgets exempt):** a pair becomes dead after **five** executions whose recorded outcome has `new_state=false`. The threshold is uniform across every widget class the ban covers — there is no per-class *threshold* variation. An execution with `new_state=true` does not count toward death, and does not decrement or reset the accumulated count either: the counter only ever grows, and it counts unproductive executions of that exact pair.

**Input-capable targets are exempt at any strike count.** A `matched` pair whose resolved target is input-capable SHALL never become dead, however many unproductive executions it accumulates. Input-capable means the widget class is one of `android.widget.EditText`, `android.widget.AutoCompleteTextView`, `android.widget.SearchView`, `androidx.appcompat.widget.SearchView` — the set the prompt builder already uses to decide whether to offer `type_text` (`ApePromptBuilder.INPUT_CLASS_NAMES`). The ban SHALL NOT carry a second, independently-maintained list of input classes: one definition of "input-capable" serves the prompt, the fixTextEdit conversion, and this exemption.

The exemption is realized by **not recording strikes** for an input-capable target, rather than by filtering at the ban check. The consequences are deliberate and are what the scenarios assert: an exempt pair never enters the dead-pair record at all, so its ban key can never be consulted and found dead; and because the exemption keys on the *widget*, not the event type, it covers the text-entry action that the fixTextEdit conversion ("Coordinate Mapping") produces from a click on that same widget — the two mechanisms act on one widget set, not two.

**The exemption applies to `matched` results only, and this is a property of the evidence, not an oversight.** An `llm_tap` result is an off-tree tap with no matched widget: it carries `matched_class=none` in 1,033 of 1,033 corpus occurrences, so no widget class is knowable at its ban key. No class-based exemption of any design can reach the `llm_tap` half of the ban.

Why five, and what the exemption costs — measured on the 84 `cal_a1` runs of the calibration corpus (`experimento-cal/iter0`), which is the same 70% LLM configuration the decisive run's LLM arm uses.

**The sweep must be read on the keys the ban actually uses.** An earlier reading of this measurement keyed *both* result types by `(state, pixel)`, because the recorded trace does not carry the widget XPath. Only `llm_tap` uses that key, and `llm_tap` is 15.9% of the decision stream; the other 84.1% is `matched`, which ships with the looser `Name`-level key above and therefore bans more. Both columns below come from the same replay:

Every share below is computed against a denominator of **6,500 LLM decisions** — the reconstructed decision stream of the 84 `cal_a1` traces (80 main runs at `timeout=300` plus 4 smoke runs at `timeout=90`). Main-only the denominator is 6,440, which moves the k=5 share to 27.8% and leaves it inside the ceiling.

| k | refused, `(state,pixel)` key | share | **refused, shipped keys** | **share** | new states lost |
|---|---|---|---|---|---|
| 1 | 3,161 | 48.6% | 3,847 | **59.2%** | 44 → 80 |
| 2 | 2,283 | 35.1% | 2,975 | **45.8%** | 16 → 37 |
| 3 | 1,813 | 27.9% | 2,441 | **37.6%** | 8 → 24 |
| **5** | 1,305 | 20.1% | **1,788** | **27.5%** | 5 → **9** |
| 12 | 604 | 9.3% | 881 | 13.6% | 1 → 2 |

- The refused block is nearly unproductive at every threshold, so the death rule is not a close call on evidence; the choice of k is about how much of the arm's decision stream the ban is allowed to take over.
- k=1 maximizes raw net gain but refuses **59.2%** of the LLM's answers under the shipped keys. At that rate the LLM arm's behavior is substantially the SATA fallback's, and the arm stops being interpretable as "the LLM exploring" — which is the only thing the decisive run exists to measure. **The threshold is an experimental-validity choice, not only an optimization**, and the criterion it enforces is *refusal below 30%*. Under the shipped keys k=3 sits at 37.6% and fails that criterion; **k=5 sits at 27.5% and meets it**, at the cost of 9 new states lost instead of 8.
- **The table's shares are measured without the input-capable exemption, so 27.5% is now an upper bound.** Exempting a class can only remove pairs from the refused block, never add to it, so the shipped rule refuses *at most* 27.5% and the sub-30% criterion is met a fortiori. **The exact post-exemption share was not re-derived from the corpus** — the bound is what the criterion needs, and the sweep was not re-run for a number that could only move in the safe direction. A reader wanting the realized share must take it from the decisive run's own telemetry, where the `dead_pair` summary counter reports it directly.
- **k=5 raises the floor under every class the exception list named except `EditText`, which keeps its exemption by name.** The list this rule replaces would have granted `Switch`/`CheckBox`/`RadioButton` a threshold of k=2; k=5 grants them 5, so each is protected at least as well without a class test. Those classes are not rare — at k=1 they account for 81 of 2,586 banned pairs (3.1%) in the calibration corpus, with `Spinner` and `CheckedTextView` adding another 25 — so this is dominance by arithmetic, not by rarity. `EditText` is the one member the arithmetic does not cover: the list exempted it outright, and no finite k reproduces an exemption, which is why it survives as the input-capable carve-out above.
- The uniform threshold also reaches cases no class-name enumeration can: N-ary selection widgets (`Spinner`, where one coordinate opens many options — 10 banned pairs at k=1, dropping to 2 by k=3 and fewer still at k=5), Material/AppCompat subclasses whose simple names differ from the base classes (`SwitchCompat`, `MaterialCheckBox` — the corpus does show AndroidX simple names such as `LinearLayoutCompat`, `FloatingActionButton` and `CardView` reaching the tree, so the divergence is real), and Compose trees that expose no meaningful widget class name at all. The input-capable exemption is a class-name test and inherits exactly this limitation: an input widget whose class name is not one of the four is banned like any other.

**Scope limit (stated because it bounds what this mechanism can be credited with):** the ban check runs *after* `mapToModelAction` returns — the screenshot, the HTTP call and the parse have already completed. The ban changes which action executes; it does NOT reduce the LLM arm's per-step latency cost. In the calibration corpus that cost is 35% of a 300 s run's budget, and it is the dominant reason the LLM arm executes 0.622× the steps and discovers 0.729× the distinct states of the algorithmic arm on the same wall clock. This mechanism raises yield per decision; it does not address throughput, and it is not expected to close that gap on its own.

**Outcome feedback:** `StatefulAgent` SHALL report the outcome of each executed LLM-originated decision to `CoordinateMapper` — reached through `RunContext`'s LLM units, via `recordLlmOutcome(...)` — at the point where `new_state` is computed for the `[APE-OUTCOME]` line, using the same single-shot buffered-decision discipline that guards `[APE-OUTCOME]` emission. The key material and strike semantics are unchanged by the decomposition; only the owner of the record moves. Only LLM-originated decisions feed the ban record; SATA-selected actions are never banned.

**Ban check site and behavior:** the check lives in `CoordinateMapper`, at the end of its mapping step inside `LlmEngine.selectAction()` (step 8 of the Action Selection Pipeline): once a `matched` or `llm_tap` result is mapped and before it is handed back, `CoordinateMapper` SHALL compute the result's ban key; when the key is dead, the engine SHALL return null — the declared fallback, the same caller-visible path as `no_match`, decided by the remainder of the assembled pipeline (`decision-pipeline` INV-DP-11). `LlmTelemetry` SHALL emit `[APE-LLM-TEL] result=no_match reason=dead_pair` for the banned decision and increment both `noMatchCount` and a `dead_pair` overlay counter reported on the summary line. The ban SHALL NOT record a breaker failure through `LlmClient` — the LLM pipeline succeeded; only its answer was refused — so a ban streak can never open the circuit breaker.

**Memory scope:** the ban record is per-run and in-memory only; no persistence, no cross-run state.

**Falsification gate (protocol, recorded here because it defines B1's success criterion):** bucket D (dead-pair repeats) MUST fall to ≈0 in the decisive-run telemetry BEFORE any new-state gain is credited to this mechanism; if bucket D ≈ 0 and new states do not rise, the ban is judged ineffective.

#### Scenario: repeated dead llm_tap is banned

- **WHEN** an `llm_tap` at `(500, 499)` on state S1 executed earlier in the run and its recorded outcome had `new_state=false`
- **AND** a later `LlmEngine.selectAction()` on state S1 resolves to an `llm_tap` at the same `(500, 499)`
- **THEN** the engine SHALL return null and the remainder of the pipeline SHALL decide the step
- **AND** the `[APE-LLM-TEL]` line SHALL carry `result=no_match reason=dead_pair`
- **AND** no breaker failure SHALL be recorded through `LlmClient` for this decision

#### Scenario: pair survives four dead executions and dies on the fifth

- **WHEN** a `matched` click on Button "Help" (state S2) executes four times with `new_state=false`
- **THEN** the pair (S2, that action `Name`'s XPath, click) SHALL NOT yet be dead, and a fifth LLM answer resolving to it SHALL be returned normally
- **AND** after that fifth execution also records `new_state=false` the pair SHALL be dead
- **AND** the next LLM answer resolving to it SHALL be banned

#### Scenario: threshold is uniform across the widget classes the ban covers

- **WHEN** one pair targets a `Switch`, another a `Spinner`, and another a plain `Button`, and each executes five times with `new_state=false`
- **THEN** all three pairs SHALL be dead at the same threshold
- **AND** no covered widget class SHALL receive a threshold other than five

#### Scenario: an input-capable target is never dead

- **WHEN** a `matched` pair targets an `android.widget.EditText` and executes six times with `new_state=false` — one more than the threshold
- **THEN** the pair SHALL NOT be dead, and a subsequent LLM answer resolving to it SHALL be returned normally
- **AND** the pair SHALL NOT appear in the dead-pair record at all, because no strike was ever recorded for it
- **AND** the same SHALL hold for `android.widget.AutoCompleteTextView`, `android.widget.SearchView` and `androidx.appcompat.widget.SearchView`

#### Scenario: the exemption follows the widget through the fixTextEdit conversion

- **WHEN** an LLM `click` resolves to a `SearchView` and the fixTextEdit conversion ("Coordinate Mapping") turns it into a text-entry action on that widget
- **THEN** the resulting text-entry decision SHALL also be exempt, because the exemption keys on the target widget rather than on the event type
- **AND** repeated unproductive executions of it SHALL never produce `reason=dead_pair`

#### Scenario: an off-tree tap cannot be exempted

- **WHEN** an `llm_tap` result is banned after five unproductive executions at the same coordinate on the same state
- **THEN** the ban SHALL apply regardless of what is rendered at that coordinate
- **AND** no input-capable exemption SHALL be consulted, because an `llm_tap` has no matched widget and therefore no widget class

#### Scenario: a ban withdraws the action from every node the Name resolves to

- **WHEN** a `matched` pair dies whose action `Name` resolves to three nodes in that state
- **THEN** subsequent LLM answers resolving to that `Name` and event type SHALL be banned regardless of which of the three nodes the coordinate falls in
- **AND** the ban count SHALL be reported as one dead pair, never as three widgets withdrawn

#### Scenario: productive execution does not count toward death

- **WHEN** a pair executes with `new_state=false`, then with `new_state=true`, then with `new_state=false`
- **THEN** the pair's accumulated dead-execution count SHALL be 2 (the productive execution neither counts nor resets the count)
- **AND** the pair SHALL NOT yet be dead

#### Scenario: ban is per-run

- **WHEN** a new run starts on the same APK
- **THEN** the ban record SHALL be empty

### Requirement: LLM Unit Lifecycle and Ownership

When the plan carries the LLM feature, `RunContext` SHALL construct and own the LLM units exactly once at bootstrap, wired from the plan's `LlmParams` (never from static `Config`):

- `LlmClient` — transport + circuit breaker as one unit (`llm-infrastructure` capability): base URL, model, `temperature`, `top_p`, `top_k`, `max_tokens` (default `1024`), `timeout_ms`.
- `ScreenshotStep` — `ScreenshotCapture` + `ImageProcessor` + device-dimension determination.
- `ApePromptBuilder` — unchanged, variant from `LlmParams`.
- `ToolCallParser` — unchanged.
- `CoordinateMapper` — coordinate normalization, boundary bands (`llmBoundaryTopPct`/`llmBoundaryBottomPct`), snap tolerance floor (`llmSnapTolerancePx`), back/long-click preference, `fixTextEdit`, and the dead-pair ban record.
- `LlmTelemetry` — all LLM counters, latches, and `[APE-LLM-*]` line emission; prints the summary at teardown.
- `LlmEngine` — the thin orchestrator of the Action Selection Pipeline over the units above.

The `max_tokens` value SHALL be read once from `LlmParams` and shared by the request body and the `[APE-LLM-CONFIG]` manifest, so the manifest always reports the value actually sent. The manifest line SHALL be emitted once at `LlmClient` construction, with the same fields as before the restructuring.

**Per-request tool schema:** the two schema constants (with and without `type_text`) SHALL be built once at construction and the appropriate one passed to each `chat()` invocation by the same `hasInputField` predicate the system message uses (prompt/wire coherence, INV-LLM-11) — unchanged behavior, now owned by `LlmClient`/`LlmEngine`.

When the plan does NOT carry the LLM feature, none of these units SHALL be constructed and no LLM stage SHALL exist in the decision pipeline (feature absent = stage absent, `decision-pipeline` INV-DP-03; replaces the null `_llmRouter` convention of INV-RTR-01).

All unit references SHALL be final and reused for the entire run; all unit state (counters, latches, ban record, breaker state) is per-run and dies with the process.

#### Scenario: LLM feature in the plan constructs the units once
- **WHEN** the resolved plan carries the LLM feature with `url=http://10.0.2.2:30000/v1`
- **THEN** `RunContext` SHALL construct `LlmClient`, `ScreenshotStep`, `ApePromptBuilder`, `ToolCallParser`, `CoordinateMapper`, `LlmTelemetry`, and `LlmEngine` exactly once
- **AND** one `[APE-LLM-CONFIG]` manifest line SHALL be emitted with the effective values

#### Scenario: LLM feature absent constructs nothing
- **WHEN** the resolved plan does not carry the LLM feature
- **THEN** no LLM unit SHALL be constructed and no LLM stage SHALL be assembled
- **AND** zero LLM-related trace lines SHALL be emitted for the run

#### Scenario: units read the plan, not Config
- **WHEN** any LLM unit needs a sampling, timeout, boundary, or tolerance parameter during the run
- **THEN** it SHALL read the value injected from `LlmParams` at construction
- **AND** no LLM unit SHALL read static `Config` after bootstrap

---

### Requirement: Declared LLM Fallback

The LLM fallback SHALL be declared by the plan (the configured base: `mop` or `aperv`) and realized structurally by the decision pipeline: an LLM stage returns `Continue` on every decline, failure, refused answer, or breaker denial, and the remainder of the assembled pipeline decides the step (`decision-pipeline` INV-DP-11). There SHALL be no in-step retry, no substitute selection inside an LLM stage, and no exception path. The `decision_source` of a fallback-decided step SHALL be the deciding stage's source, never `LLM`.

#### Scenario: timeout falls back to the declared base
- **WHEN** an `llm_mop` run's LLM call times out at a step where the launcher is at its firing point with an eligible candidate
- **THEN** the step SHALL be decided by the `MopLauncher` stage with `decision_source=Component`

#### Scenario: decline on an aperv-based LLM arm falls to SATA
- **WHEN** an `llm` (aperv-base) run's LLM answer is refused (dead pair) on a step
- **THEN** the step SHALL be decided by the `SataChain` stage
- **AND** the refused answer SHALL still be telemetered as `result=no_match reason=dead_pair`

