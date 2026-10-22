## Purpose

LLM routing integrates the LLM infrastructure into APE-RV's exploration loop at the decision points where SATA's deterministic behavior limits exploration diversity. Rather than replacing SATA or competing with MOP priority scoring, an LLM stage operates as a punctual override: when its trigger holds and its gates admit, it captures a screenshot, sends it to the LLM with the current widget list and action history, and directly selects a `ModelAction` to execute. When the LLM is unavailable, times out, returns an unparseable response or matches nothing, the engine returns null and the step falls through to the next stage of the pipeline (ultimately SATA).

The LLM group (`ape.llm`, which requires `ape.llmUrl`) has three members, each a stage of the decision pipeline (`decision-pipeline`), in this order:

1. **New-state** (`LLM_NEW_STATE`, `ape.llmOnNewState`, stage `LlmNewState`): fires on the first visit to each newly discovered state, with no coin. The `isNewState` flag is captured in `StatefulAgent.updateStateInternal()` **before** `Graph.markVisited()` to ensure accurate first-visit detection. This is the highest-value intervention point because the LLM sees a screen for the first time and can identify the most promising action using visual understanding that SATA lacks.

2. **Stagnation** (`LLM_STAGNATION`, `ape.llmOnStagnation`, stage `LlmStagnation`): fires once per stagnation episode, at the first step where `graphStableCounter >= ape.graphStableRestartThreshold / 2` with the episode flag armed (INV-RTR-19), with no coin. The flag burns when the stage calls the LLM and re-arms on the transitions that reset the counter. The restart at the full threshold is unchanged: the hook does NOT modify the restart mechanism.

3. **Coin** (`LLM_COIN`, `ape.llmPercentage` = `p_L`, stage `LlmCoin`): fires on any other step with `u_L < p_L`, where `u_L` is drawn at every step open, before any gate, from the coin's own stream (`StepRng`, `decision-pipeline` INV-DP-13). `p_L <= 0` leaves the member out of the plan. The dose is "every new state + `p_L` on the other steps". `ape.llmPercentageNoSubstrate` is a retired key.

Once a trigger or the coin holds, the only conditions that keep a stage from calling are the mounted gate chain (design D-B8): `MinActionsGate(ape.llmGateMinActions)` (default 3; `0` unmounts it) and then `BreakerGate` (`ape.llmBreakerFailures`, `ape.llmBreakerOpenMs`). A non-empty SATA buffer is not a gate: an LLM step preempts it, and the buffer is abandoned without weakening (D-A6). A step makes at most one LLM call (D-B14.14): once a stage has called, whatever the result, the later LLM stages of the step return `Continue`. Every LLM-intended step records in `dec.own` the intended owner, the trigger, the barring gate and the fallback reason.

The LLM infrastructure (`LlmClient` with its `SglangClient` and `LlmCircuitBreaker`, `ScreenshotStep`, `ApePromptBuilder`, `ToolCallParser`, `CoordinateMapper`, `LlmTelemetry`) sits behind one `LlmEngine`, constructed once by `RunContext` if and only if `LLM` is in the plan, and used for the whole run; its parameters come from the plan (`RunContext.current().spec().llm()`). There is no call budget limit — only timeout controls execution duration.

---

## Data Contracts

### Input

- `GUITree tree` — current screen's accessibility tree (source: `MonkeySourceApe.nextEventImpl()`)
- `State state` — current abstract state with visit count, activity, and actions (source: `Model`)
- `List<ModelAction> actions` — valid actions on current state, already priority-ranked by SATA+MOP (source: `StatefulAgent.adjustActionsByGUITree()`)
- `MopContext mop` — the run's MOP context (the loaded `MopData`, the scoring mode, both modes' weights and the run's `MopRetirement`), null if and only if `MOP` is not in the plan, even when the artifact was loaded for STATIC (source: `StatefulAgent._mopContext`, bound to the engine by `LlmEngine.bindMopContext`; design D17, D-B14.21)
- `StaticGuiModel staticModel` — the static GUI model the STATIC prompt contribution reads, bound by `LlmEngine.bindStaticModel`; without `STATIC` no STATIC contribution is mounted
- `boolean isNewState` — whether this is the first visit to the current state, captured before `markVisited()` (source: `StatefulAgent._isNewState`)
- `int graphStableCounter` — consecutive steps without graph growth (source: `StatefulAgent.checkStable()`)
- `List<ActionHistoryEntry> recentActions` — last 3-5 executed actions with results (source: `StatefulAgent` ring buffer)

### Output

- `ModelAction` — matched action from the input actions list, ready to execute (for type_text actions, `resolvedNode.setInputText(text)` is already called before returning)
- `null` — LLM pipeline failed or no matching action found; the stage records the fallback reason (`dec.own.fb`) and the step falls through to the next stage. A gate that bars (breaker open, too few actions) prevents the call altogether and is recorded as `dec.own.g`

### Side-Effects

- **Logging**: `[APE-RV] LLM` prefixed log entries for routing decisions, action selections, and fallbacks
- **Circuit breaker state**: success/failure recorded after each LLM attempt, affecting future routing decisions
- **Call counter**: `totalCalls` (`LlmTelemetry`) incremented at each `selectAction()` invocation for telemetry (no budget limit)
- **Network**: HTTP request to SGLang server (via `SglangClient`)
- **Device framebuffer**: screenshot capture (via `ScreenshotCapture`)

### Error

- All errors are handled internally by `LlmEngine.selectAction()` — no exceptions propagate to the pipeline.
- Network errors and unparseable responses SHALL trigger `LlmCircuitBreaker.recordFailure()`. A null screenshot SHALL NOT: it counts `screenshot_failed`, records `dec.own.fb="screenshot"`, and the step falls through as any other fallback (design D-B18, INV-RTR-27). The attempt still spends the step's one LLM call (INV-RTR-26).

---

## Invariants

- **INV-RTR-01**: `LlmEngine` (with `LlmClient`, `LlmTelemetry` and `CoordinateMapper`) SHALL be constructed, once per run by `RunContext`, if and only if `LLM` is in the resolved plan. `LLM` requires `ape.llmUrl`: `ape.llm=true` without it aborts plan resolution. Without `LLM` no LLM stage is mounted and `RunContext.llmEngine()` is null.
- **INV-RTR-02**: `LlmEngine.selectAction()` SHALL never throw an exception to the caller. All failures SHALL result in a null return with a warning log.
- **INV-RTR-03**: The `ModelAction` returned by `selectAction()` MUST be either (a) a member of the input `actions` list — a widget action matched by coordinate — or (b) a synthesized `LlmTapAction` of type `MODEL_LLM_TAP` constructed for the off-tree case. In case (b) the action is intentionally NOT a member of `actions`; it is a targetless action carrying the LLM pixel coordinate, mirroring how `EVENT_TRIGGER_ACTIVITY` (activity-frontier) returns a synthesized action not drawn from the state's action set. When even the off-tree construction does not apply (degenerate coordinate, boundary reject, `type_text`, or `back` with no back action), `selectAction()` returns null and the LLM coordinates are logged for telemetry.
- **INV-RTR-04**: LLM routing SHALL NOT modify `ModelAction.priority` values. LLM selects an action directly; it does not boost priorities. The MOP scoring pass runs independently before LLM routing.
- **INV-RTR-05**: The LLM members are independent: removing one SHALL NOT affect the others. Each is gated by its own member feature — `LLM_NEW_STATE` (`ape.llmOnNewState`), `LLM_STAGNATION` (`ape.llmOnStagnation`), `LLM_COIN` (`ape.llmPercentage`).
- **INV-RTR-06**: `LlmEngine.selectAction()` SHALL explicitly null out its large intermediate objects (`pngBytes`, `base64`, `messages`) in a `finally` block to prevent memory pressure from accumulated screenshots.
- **INV-RTR-07**: `totalCalls` SHALL be incremented at the start of each `selectAction()` invocation, counting all attempts regardless of success or failure. The circuit breaker independently limits consecutive failures. There is no call budget limit.
- **INV-RTR-08**: `ape.llmPercentage` SHALL be clamped to the closed interval `[0.0, 1.0]` at plan resolution (`RunSpec`), before any reader sees it. A configured value `< 0` SHALL become `0.0`; a value `> 1` SHALL become `1.0`. A resolved value `<= 0` leaves `LLM_COIN` out of the plan.
- **INV-RTR-10**: No dedicated LLM configuration line SHALL exist. The sampling parameters sent by `SglangClient.buildRequestBody` SHALL be the plan's resolved values (`spec().llm()`), fixed at `LlmClient` construction; `RUN_START.params` echoes each one that differs from its jar default, and the build stamp recovers the rest. When `LLM` is not in the plan no LLM component is constructed (INV-RTR-01).
- **INV-RTR-11**: The sum `timeoutCount + httpErrorCount + connErrorCount + parseErrorCount + imageErrorCount + internalErrorCount + screenshotFailedCount` SHALL equal the number of `selectAction()` attempts abandoned before the mapping step — the attempts that return null **without** appending a completed-mapping `llm[]` sub-event (`result` `matched`, `llm_tap` or `no_match`). Each such attempt appends one `llm[]` sub-event with `result:"error"` and its `cause` (`LlmTelemetry.imageFailed`, `transportFailed`, `parseFailed`, `internalError`), except the screenshot failure, which appends none (INV-RTR-20). Exactly one cause counter SHALL be incremented per abandoned attempt (none uncounted, none double-counted). `no_match` outcomes also return null from `selectAction()` but append a completed-mapping sub-event and are counted by `noMatchCount` only; they are outside this sum.
- **INV-RTR-12**: The `LLM_ACK` record SHALL be written at most once per run, only after a successful `chat()` response: `LlmTelemetry.acknowledge` calls the sink's `llmAck`, which latches after the first record. A run with zero successful responses SHALL write no `LLM_ACK` (its absence, combined with the failure-cause counters in `RUN_END.counters.llm`, is itself diagnostic).
- **INV-RTR-13**: When a routed decision's `ParsedAction` carries a repair-form label other than `none` (`llm-infrastructure` INV-LLM-09), the decision's `llm[]` sub-event SHALL carry `repair:"<form>"` and `LlmTelemetry`'s `repairedCount` (`RUN_END.counters.llm.repaired`) SHALL be incremented exactly once for that decision. A decision whose label is `none` SHALL NOT carry the `repair` field and SHALL NOT increment `repairedCount`. `repair` is additive telemetry on a mapped decision: it never suppresses an `error` sub-event and never alters the `parse` cause attribution, which is recorded only when `ToolCallParser.parse()` returns null.
- **INV-RTR-14**: The status-bar band fraction, the snap-tolerance floor and the request `max_tokens` SHALL be read from the plan's `LlmParams` (`ape.llmBoundaryTopPct`, `ape.llmSnapTolerancePx`, `ape.llmMaxTokens`, owned by `LLM` in `KeyOwnership`), injected once into `CoordinateMapper` and `LlmClient` at construction, with defaults `0.035` / `50` / `1024`. The tolerance floor and `max_tokens` defaults reproduce the formerly hard-coded constants; the band is the single status-bar band of INV-RTR-22, and `ape.llmBoundaryBottomPct` is a retired key.
- **INV-RTR-15**: A dead pair — `(stateKey, pixelX, pixelY)` for `llm_tap`, `(stateKey, Name.toXPath(), eventType)` for `matched` — SHALL never be returned by `selectAction()` again within the same run once dead per the death rule (**five** executions with `new_state=false`, the same threshold for every widget class the ban covers; a `new_state=true` execution neither counts toward nor resets the accumulated count). A `matched` pair whose resolved target is input-capable per `ApePromptBuilder.INPUT_CLASS_NAMES` SHALL never become dead at any strike count, and SHALL never be entered into the record; the exemption keys on the widget, not the event type, and has no counterpart on the `llm_tap` side, where no widget class exists. The ban anchor SHALL be the stable pair, never a list index. The `matched` anchor is `Name`-level and therefore abstract: one dead pair may cover several physical nodes, and ban counts SHALL NOT be reported as widget counts. The record is per-run and in-memory.
- **INV-RTR-16**: A dead-pair ban SHALL NOT invoke `breaker.recordFailure()` and SHALL NOT contribute to opening the circuit breaker; the banned decision records pipeline success and returns null through the `no_match` path with `reason=dead_pair`.
- **INV-RTR-17**: The tool name the model called SHALL constrain the matched `ActionType` in both matching passes: a `click` answer SHALL only ever return a `MODEL_CLICK` widget action (or an off-tree `MODEL_LLM_TAP`); a `type_text` answer SHALL only ever return an input-capable widget action; `long_click` MAY fall back from `MODEL_LONG_CLICK` to `MODEL_CLICK`.
- **INV-RTR-18**: The snap fallback SHALL measure point-to-rectangle (edge) distance with clamped `dx`/`dy`; a point inside a candidate's bounds has distance 0, and a point within tolerance of a widget's edge SHALL be snappable regardless of the widget's aspect ratio. The tolerance formula and its configuration keys are unchanged.
- **INV-RTR-19**: The stagnation LLM hook SHALL fire exactly once per stagnation episode: it fires at the first check where `graphStableCounter >= graphStableRestartThreshold / 2` with the episode flag armed, sets the flag, and the flag re-arms only when `graphStableCounter` resets to 0.
- **INV-RTR-20**: Every routing attempt abandoned at screenshot capture SHALL print exactly one Logger line `[APE-RV] LLM screenshot capture failed, skipping LLM step activity=<activity> detail=<stage>` (`LlmTelemetry.screenshotFailed`), carrying the foreground activity and the failing capture stage (`unknown` when the capture named none), and SHALL append no `llm[]` sub-event; `screenshotFailedCount` remains its only cause counter (the INV-RTR-11 partition is unchanged).
- **INV-RTR-21**: Within one `LlmEngine.selectAction` call, the `{width, height}` returned by `ScreenshotStep.deviceDimensions` in step 2 SHALL be the only device dimensions used: by `ScreenshotStep.capture` (step 2), by `ApePromptBuilder.build` (step 4), and by `CoordinateMapper.toPixels` and `CoordinateMapper.map` (step 8). No step SHALL determine dimensions of its own.
- **INV-RTR-22**: The boundary reject SHALL be expressed in the display base of INV-RTR-21. It SHALL reject exactly the answers whose pixel lies in the status-bar band (`pixelY < deviceHeight * ape.llmBoundaryTopPct`) and, in step 8 before the answer is mapped, the answers outside `[0,999]` on either axis whose tool is not `back` (their clamped pixel is still computed by `toPixels`, for telemetry only, and is never matched or tapped). No lower band SHALL exist. A reject SHALL surface as `result:"no_match"` with `reason:"degenerate"` for `(0,0)` and `reason:"boundary"` otherwise; the reason set is unchanged.
- **INV-RTR-23**: The conditions under which an LLM stage, once its trigger or coin holds, does not call the LLM SHALL be exactly the mounted `LlmGate` chain. That chain is `MinActionsGate(ape.llmGateMinActions)` and then `BreakerGate`, evaluated in this order with short-circuit. A gate whose parameter is `0` SHALL NOT be mounted. `MinActionsGate(3)` admits exactly the states with `getActions().size() >= 3`, which equals the former `> 2`. The SATA action buffer SHALL NOT be a condition of any LLM stage.
- **INV-RTR-24**: The `LlmCoin` stage's trigger SHALL be `u_L < p_L`, where `u_L` was drawn at step open from `StepRng`'s `llmCoin` stream (`decision-pipeline` INV-DP-13). The `LlmNewState` and `LlmStagnation` triggers SHALL consume no coin.
- **INV-RTR-25**: On every step on which an LLM stage's trigger or coin held, `dec.own` SHALL record, whichever owner finally took the step: `i="llm"`; the trigger `trg`; the barring gate `g`, when one barred; the fallback reason `fb`, when the LLM did not take the step.
- **INV-RTR-26**: A step SHALL make at most one LLM call. Once an LLM stage of the step has called the LLM, whatever the result, every later LLM stage of that step SHALL return `Continue` without evaluating its gates and without calling. A stage whose trigger did not hold, or whose gate barred, has made no call.
- **INV-RTR-27**: Network errors and unparseable responses SHALL trigger `LlmCircuitBreaker.recordFailure()`. A null screenshot SHALL NOT: it counts `screenshot_failed`, records `dec.own.fb="screenshot"`, and the step falls through as any other fallback (design D-B18). The attempt still spends the step's one LLM call (INV-RTR-26), so no later LLM stage of the step captures again.

---

## Requirements

### Requirement: New-State LLM Mode

When the plan carries `LLM_NEW_STATE`, the `LlmNewState` stage SHALL be assembled after the `Budget` and
`Restart` guards and ahead of the stagnation, coin, launcher, trigger, MOP-coin and SATA stages.
`decide()` SHALL perform these steps in order:
1. **Trigger.** Check `_isNewState`, which is captured before `markVisited()` (the capture is unchanged). When it
   is false, return `Continue` and record nothing.
2. **Gates.** Evaluate the shared gate chain (INV-RTR-23) on the step's `StepContext`. When a gate refuses,
   return `Continue` and record `dec.own` with `i="llm"`, `trg="new_state"`, `g=<gate name>` and `fb="gate"`.
3. **Call.** When every gate admits, invoke `LlmEngine.selectAction(tree, state, actions, recentActions, "new-state")`
   through the shared `LlmCall.call`. The engine's mode label is `new-state`; `dec.own.trg` is `new_state`.
4. **Result.** On a non-null result:
   - stamp the owner `llm` and the channel `llm` through `LlmAcceptance.accept`, and resolve a synthesized `MODEL_LLM_TAP` against the state (unchanged);
   - when the buffer is non-empty, abandon it without weakening (`decision-pipeline` INV-DP-15);
   - return `Select`.

   Otherwise return `Continue`, with `dec.own.fb` set to the reason.

The action buffer SHALL NOT be consulted as a condition. The gate chain exists in exactly one place, the
composition root. All three LLM stages consult the same chain instance.

The stage SHALL run after `adjustActionsByGUITree()` has assigned priorities (unchanged, INV-EXPL-11) and before any SATA rung.

#### Scenario: First visit to new state with LLM enabled
- **WHEN** the pipeline reaches `LlmNewState` on a first-visit state with 3+ actions, with the buffer empty or not
- **AND** the breaker allows and `LlmEngine.selectAction(...)` returns a non-null `ModelAction`
- **THEN** the stage SHALL return `Select` with `dec.src="llm"` and `dec.own.trg="new_state"` (later stages not evaluated)

#### Scenario: First visit but circuit breaker open
- **WHEN** the state is new, it has 5 actions, and `BreakerGate` refuses because `LlmClient.allows()` returns false
- **THEN** the stage SHALL return `Continue`, no HTTP call SHALL be made, and `dec.own` SHALL carry `g="breaker"` and `fb="gate"`

#### Scenario: Revisit of known state
- **WHEN** `_isNewState` is `false`
- **THEN** the stage SHALL return `Continue` regardless of other conditions, and SHALL evaluate no gate

#### Scenario: LLM returns null on new state
- **WHEN** `LlmEngine.selectAction(...)` returns `null`
- **THEN** the stage SHALL return `Continue` and the remaining pipeline SHALL decide the step

#### Scenario: precondition evaluated in one place
- **WHEN** the three LLM stages evaluate their gates on a step
- **THEN** all three SHALL consult the same `LlmGate` chain that the composition root mounted from the plan (`MinActionsGate`, then `BreakerGate`)
- **AND** no stage SHALL carry its own copy of a gate expression, and none SHALL test the action buffer

### Requirement: Stagnation LLM Mode

When the plan carries `LLM_STAGNATION`, the `LlmStagnation` stage SHALL be assembled after `LlmNewState` and
before `LlmCoin`. The stage SHALL fire at most once per stagnation episode. The trigger is
`graphStableCounter >= threshold / 2` AND the stage's per-episode fired flag is still armed (the pure predicate
`stagnationMidpointReached`, owned by the stage). The trigger consumes no coin (D-A17). When the trigger holds, the
shared gate chain (INV-RTR-23) is evaluated. A refusal returns `Continue` and records `dec.own` with
`trg="stagnation"`, the gate and `fb="gate"`. The flag stays armed, because no call was made. The fired flag SHALL
be **owned by the stage** and SHALL be:

- burned inside `decide()` whenever the gates admit and the LLM is called, whatever the LLM answers. A null result
  is a failed attempt, not an unused one. If stagnation persists, the restart at the full threshold follows;
- re-armed by the stage's `onStateTransition(edge)` hook on `NEW_ACTION`/`NEW_ACTION_TARGET` edges. A new edge
  ends the episode; it is the same event that resets `graphStableCounter`.

On an accepted answer the stage abandons a non-empty buffer without weakening (`decision-pipeline` INV-DP-15).

`graphStableCounter` itself remains shared exploration state owned by the agent; the stability restart consumes
it independently. On an accepted escape the stage SHALL reset it to 0 through the `StepContext`'s single declared
write method. The restart mechanism is a guard: step 2's `onGraphStable()` raises the flag at
`counter > threshold`, and the `Restart` stage, which runs ahead of the LLM stages, selects the restart in the same
step's pipeline (`decision-pipeline` "Restart Guard Stage").

#### Scenario: LLM provides escape action at or past the stagnation midpoint
- **WHEN** `graphStableCounter` reaches `threshold / 2` (or any greater value with the flag armed), the state has 3+ actions, and the breaker allows
- **THEN** the stage SHALL burn the flag and invoke `LlmEngine.selectAction(tree, state, actions, recentActions, "stagnation")`
- **AND** on a non-null result it SHALL reset `graphStableCounter` to 0 and return `Select` with `dec.src="llm"`

#### Scenario: hook fires once per episode even when the LLM fails
- **WHEN** the trigger fires, the gates admit, and the LLM returns null
- **THEN** the stage SHALL return `Continue` with the flag burned
- **AND** the stage SHALL NOT fire again until a new edge re-arms it
- **AND** if `graphStableCounter` eventually exceeds the full threshold, `requestRestart()` SHALL be called and the `Restart` stage SHALL select the restart (existing behavior, now a guard)

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

### Requirement: Action Selection Pipeline

`LlmEngine.selectAction(GUITree tree, State state, List<ModelAction> actions, List<ApePromptBuilder.ActionHistoryEntry> recentActions, String mode)` SHALL run the LLM decision pipeline over the decomposed units and return `ModelAction` or `null`. Each step below is owned by one LLM unit ("LLM Unit Lifecycle and Ownership"). The prompt shares the decision's coordinate base (step 4), and step 8 refuses an answer outside the answer space and applies one boundary band, the status bar (INV-RTR-22). The call's `mode` is the label the calling stage passes: `new-state`, `stagnation` or `random`.

**The argument list carries no MOP context (design D-B1).** The engine receives no `MopContext`, and no LLM stage reads `ctx.mopData()` to build a prompt. The MOP and static signals reach the prompt as the mounted prompt contributions (`llm-prompt` "Prompt Built From Group Contributions"): the composition root builds `StaticPromptContribution` iff `Feature.STATIC` and `MopPromptContribution` iff `Feature.MOP` is in the plan, and hands that list to the engine's `ApePromptBuilder` when the run's LLM units are constructed (see `LLM Unit Lifecycle and Ownership`); the MOP contribution holds the run's MOP context itself. `recentActions` remains a per-step, agent-owned value that step 4 below hands to `ApePromptBuilder.build(...)`. The engine is constructed once per run and owned by `RunContext`, while the per-step view belongs to the stages — so for the engine to source it itself it would have to hold a `StepContext` or the agent, and design D2 exists to prevent exactly that. The calling stage passes it from `ctx.actionHistory()`, unchanged.

1. `LlmTelemetry` counts the attempt (`totalCalls++`, per INV-RTR-07).
2. `ScreenshotStep` determines the device dimensions (`deviceDimensions(tree)`: the display bounds, the tree's root node only if the display throws, the 1080 × 1920 constants last) and captures the screenshot at those dimensions. This pair is the decision's coordinate base: steps 4 and 8 use it and determine none of their own (INV-RTR-21). Null capture → `screenshot_failed` counter + the free-text screenshot-failure diagnostic carrying the activity and the failing stage via `LlmTelemetry`, fallback reason `screenshot`, return null. The attempt spends the step's one call (INV-RTR-26). A null capture SHALL NOT record a breaker failure: it neither increments the failure count nor reopens a `HALF_OPEN` breaker, because the server was never asked and nothing about the model's health was learned (design D-B18). The breaker records failures only for transport (step 5) and parse (step 7). This is the one abandoned attempt that produces **no** `llm[]` sub-event (INV-RTR-20).
3. `ScreenshotStep` resizes and base64-encodes. Null → `image` cause as an `llm[]` sub-event with `result:"error"`, return null.
4. `ApePromptBuilder.build(deviceWidth, deviceHeight, state, actions, base64, recentActions)` builds the messages from the base prompt and the mounted contributions, with the step-2 dimensions, so every [0,1000) coordinate in the prompt is in the space of the image and of the step-8 mapping (`llm-prompt` INV-PRM-06). The tree is not passed to the builder; `LlmTelemetry` stages the prompt via `llmDump`, which rides the next sub-event as `sys`/`user` when the prompt-dump flag is on (default on).
5. `LlmClient.chat(messages, hasInputField)` — `LlmClient` hands `SglangClient.chat` the tools schema selected by the same `hasInputField` predicate the prompt used (INV-LLM-11). Null → breaker failure, cause read once from the client's error seam (INV-LLM-08), cause counters + an `llm[]` sub-event with `result:"error"` and that `cause` via `LlmTelemetry`, return null.
6. `LlmTelemetry` emits the once-per-run `LLM_ACK` record on the first successful response (INV-RTR-12) and stages the response text, which rides the sub-event as `resp`/`tool_calls` under the same prompt-dump flag.
7. `ToolCallParser.parse(response)` — including the raw-arguments repair pipeline for native tool-call malformations (`SglangClient.ToolCall.rawArguments` → Level 1 → shared `parseJsonString`), surfacing through the existing `repair=` field (INV-LLM-10, INV-RTR-13). Null → `parse` cause (client seam NOT consulted), return null.
8. If the answer's tool is not `back` and `CoordinateMapper.inAnswerSpace(x, y)` is false (either coordinate outside `[0,999]`), the answer SHALL NOT be mapped: the match is null and the outcome is `no_match` with `reason:"boundary"` (INV-RTR-22). Its clamped pixel is still computed by `toPixels`, for the sub-event's `px` and the nearest-widget fields only. Otherwise `CoordinateMapper` converts the answer to a pixel with the step-2 dimensions, applies the status-bar boundary band, maps to a `ModelAction` (containment → snap tolerance → off-tree `LlmTapAction` synthesis → `fixTextEdit` conversion → back/long-click preference), and applies the dead-pair ban check (a banned answer is a refused answer: same caller-visible path as `no_match`, breaker still records success — INV-RTR-15/16).
9. `LlmClient` records breaker success; `LlmEngine.classify` classifies the outcome (`matched`/`llm_tap`/`no_match` + `reason`); `LlmTelemetry` accounts tokens/latency, receives the nearest-widget fields, and appends one `llm[]` sub-event to the current step's record with the same field set as before (renamed per the `LLM Telemetry Logging` mapping; the step, activity and variant keys are dropped as the parent record's envelope or run-constant).
10. The engine returns the mapped action or null. It SHALL never throw (INV-RTR-02): an unexpected exception is the `internal` cause. Large temporaries SHALL be nulled in a `finally` block (INV-RTR-06).

**type_text handling**: unchanged — when the match is an input-capable widget and text is present, `setInputText(text)` is applied before returning.

**Known defect preserved, deliberately.** "Unchanged" here includes a defect measured at 28 of 1,233 LLM responses (2.3%): a `type_text` answer can execute a `MODEL_LONG_CLICK`. The containment pass restricts the candidate's `ActionType` only when the tool is `"click"` (the containment pass of `CoordinateMapper.map`), and `fixTextEdit` returns the match untouched for any tool that is neither `click` nor `long_click`, so the long-click preference can win on a `type_text` answer. This stage is behavior-neutral by contract (parity-gated, R8): `CoordinateMapper` SHALL reproduce this path exactly, defect included. The fix is **out of scope here** and belongs to a separate change against `CoordinateMapper`, whose slicing is precisely what makes it testable in a JVM unit. Recording it is mandatory: a silently inherited defect in a newly extracted unit is indistinguishable from a slicing regression when the parity oracle later disagrees.

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
- **WHEN** `ScreenshotStep` returns null (secure window), three times in a row, with `ape.llmBreakerFailures=3`
- **THEN** the engine SHALL return null each time with no HTTP request made, and `screenshot_failed` SHALL be counted, with its free-text diagnostic and no `llm[]` sub-event
- **AND** the breaker SHALL record no failure and stay `CLOSED`, and each step's `dec.own.fb` SHALL be `screenshot`
- **AND WHEN** the breaker is `HALF_OPEN` and the probe's capture returns null
- **THEN** the breaker SHALL stay `HALF_OPEN`, and the next attempt whose capture succeeds SHALL be the probe
- **AND** the header is kept because `openspec archive` matches scenarios by name; the body replaces the pre-change behaviour (design D-B18)

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

`CoordinateMapper.map(int pixelX, int pixelY, String actionType, String text, List<ModelAction> actions, State state, int deviceWidth, int deviceHeight)` SHALL map LLM output coordinates to a `ModelAction` for the current state, returning a matched widget action, a synthesized off-tree `LlmTapAction`, or null.

**Boundary reject**: Before coordinate matching, if `pixelY < deviceHeight * ape.llmBoundaryTopPct` (default `0.035`: the 63 px status bar of the campaign AVD over its 1794 px display, and any degenerate `(0,0)` emission), `CoordinateMapper.map` SHALL return null and print the Logger line `[APE-RV] LLM coordinate rejected (boundary): pixelY=<y> deviceHeight=<h>`. This prevents the LLM from tapping system UI, and no off-tree tap is synthesized for these coordinates. `deviceHeight` is the display height `ScreenshotStep.deviceDimensions` returned (INV-RTR-21), which on Android excludes the navigation bar, and `normalize` clamps every answer to `[0, deviceHeight − 1]`. There is therefore no lower band: the bottom of the display is app content (bottom navigation, footer buttons, snackbar actions), and an answer that points outside the image is refused before it is mapped ("Action Selection Pipeline" step 8, `CoordinateMapper.inAnswerSpace`). The top fraction is configuration-exposed (J1b) for devices of another density (INV-RTR-22). The former `ape.llmBoundaryBottomPct` was a fraction written for a 1920-high screen whose bottom 6 % was the navigation bar; on the display it rejected y ∈ (1686, 1794), and it is retired.

**ActionType filter (both matching passes)**: the tool the model called SHALL constrain the `ActionType` of the matched action, in the containment pass AND the Euclidean fallback:
- `"click"` SHALL match only `MODEL_CLICK` actions (measured defect this closes: a `click` answer executed CLICK only 80.9% of the time — the rest matched long-clicks, scrolls, or other types sharing the widget's bounds);
- `"long_click"` SHALL prefer `MODEL_LONG_CLICK`; when no `MODEL_LONG_CLICK` matches, `MODEL_CLICK` on the same coordinates MAY be returned as fallback (unchanged);
- `"type_text"` SHALL consider only actions targeting input-capable widgets (EditText, SearchView, AutoCompleteTextView) (unchanged).

**Special action types**:
- If `actionType` equals `"back"`, the state's `backAction` SHALL be returned directly without coordinate matching.
- If `actionType` equals `"type_text"` and a match is found, the caller SHALL call `action.getResolvedNode().setInputText(text)` to inject the LLM-provided text into APE's existing input event generation pipeline.

**fixTextEdit (input-widget click conversion)**: when `actionType` is `"click"` or `"long_click"` and the matched widget (containment or snap) is input-capable (EditText, SearchView, AutoCompleteTextView), `CoordinateMapper.map` SHALL NOT return the bare click. It SHALL convert the decision into a text-entry action on that widget: the target comes from the LLM coordinate (the *where*), and the text is generated by APE's existing typed-input generation path — the same generator a SATA-selected input action uses (the *what*); no second LLM call is made. This removes the bare click on input widgets from the LLM's effective action space (banning by subtraction — the mechanism that outperforms prompt instruction), attacking the measured `type_text≈0` collapse. EditText is the widget class with the model's best grounding (93.1%), so the *where* is trustworthy; only the *what* was missing. The input-capable set used here is the same one that exempts these widgets from the dead-pair ban ("Deterministic Dead-Pair Ban") — a decision that resolves to an input widget is transformed rather than refused, and is never withdrawn from the action space by repetition.

**Bounds containment (primary matching strategy)**: For each action passing the ActionType filter where `action.requireTarget() == true` AND `action.isValid() == true` AND `action.getResolvedNode() != null`, check if `(pixelX, pixelY)` falls within the node's `getBoundsInScreen()` rectangle. If exactly one action's bounds contain the point, return that action. If multiple actions' bounds contain the point, return the one with the smallest area (most specific widget).

**Edge-based distance (fallback matching)**: If no action's bounds contain the point, compute the **point-to-rectangle distance** from `(pixelX, pixelY)` to each candidate's resolved bounds: `dx = max(bounds.left − pixelX, 0, pixelX − bounds.right)`, `dy = max(bounds.top − pixelY, 0, pixelY − bounds.bottom)`, `dist = hypot(dx, dy)` (zero when the point is inside). Return the action with the minimum distance if that distance is within the proportional tolerance `max(ape.llmSnapTolerancePx, min(nodeWidth, nodeHeight) / 2)` pixels (floor default `50`, configuration-exposed per J1b; read from the plan, `spec().llm().integer("ape.llmSnapTolerancePx")`, with no `Config` field, design D-B24). This replaces centre-distance, whose geometry punished elongated widgets: on a 1080×150 bar, only points within ~75 px of the **centre** could snap, leaving ~450 px of the bar's own edge unsnappable — a tap 20 px outside a wide widget failed while being visually on target. The `ape.llmSnapTolerancePx` default is unchanged; raising it (to ~150) is an rv-android configuration decision gated on the dead-pair ban.

**Off-tree coordinate tap (dynamic element)**: If no action's bounds contain the point AND no action is within edge-distance tolerance AND `actionType` is `"click"` or `"long_click"`, `CoordinateMapper.map` SHALL return `new LlmTapAction(state, pixelX, pixelY, "long_click".equals(actionType))` — a targetless `MODEL_LLM_TAP` action carrying the LLM coordinate. Because the answer-space check, the clamp in `normalize` and the boundary reject run first, a coordinate reaching this point is inside the display and non-degenerate. For any other `actionType` (e.g. `type_text`), `CoordinateMapper.map` SHALL return null. This is the mechanism by which APE acts on elements invisible to UIAutomator (game canvas, custom view, Compose-without-semantics).

#### Scenario: LLM says "back"

- **WHEN** `CoordinateMapper.map(0, 0, "back", null, actions)` is called
- **THEN** `state.getBackAction()` SHALL be returned
- **AND** no coordinate matching SHALL be performed

#### Scenario: click answer only matches MODEL_CLICK

- **WHEN** `CoordinateMapper.map(200, 230, "click", null, actions)` is called
- **AND** the only action whose bounds contain the point is a `MODEL_LONG_CLICK`
- **THEN** that action SHALL NOT be returned by the containment pass
- **AND** matching SHALL proceed to the edge-distance fallback over `MODEL_CLICK` candidates (or off-tree synthesis)

#### Scenario: Click coordinates inside button bounds

- **WHEN** `CoordinateMapper.map(200, 230, "click", null, actions)` is called
- **AND** MODEL_CLICK action A has resolved node bounds `[100, 200, 300, 250]` (contains point)
- **AND** MODEL_CLICK action B has resolved node bounds `[0, 0, 480, 800]` (also contains point, but larger)
- **THEN** action A SHALL be returned (smallest area containing the point)

#### Scenario: edge snap on an elongated bar

- **WHEN** `CoordinateMapper.map(540, 180, "click", null, actions)` is called
- **AND** a MODEL_CLICK bar has bounds `[0, 200, 1080, 350]`, whose centre is `(540, 275)` — the point is 20 px above the bar's top edge but 95 px from its centre
- **THEN** the point-to-rectangle distance SHALL be 20 (dx=0, dy=20)
- **AND** with tolerance `max(50, 75) = 75` the bar SHALL be snapped and returned
- **AND** the retired centre-distance rule would have rejected it (95 > 75), which is the regression this locks

#### Scenario: click on an EditText becomes text entry

- **WHEN** `CoordinateMapper.map(225, 325, "click", null, actions)` is called
- **AND** the containing widget is an EditText
- **THEN** the bare click SHALL NOT be returned
- **AND** the decision SHALL become a text-entry action on that EditText whose text comes from APE's typed-input generation path

#### Scenario: type_text targets EditText

- **WHEN** `CoordinateMapper.map(225, 325, "type_text", "user@example.com", actions)` is called
- **AND** action C is an EditText at bounds `[50, 300, 400, 350]` (contains point)
- **THEN** action C SHALL be returned
- **AND** the caller SHALL call `action.getResolvedNode().setInputText("user@example.com")`

#### Scenario: long_click prefers MODEL_LONG_CLICK

- **WHEN** `CoordinateMapper.map(200, 230, "long_click", null, actions)` is called
- **AND** the same widget offers MODEL_LONG_CLICK and MODEL_CLICK actions containing the point
- **THEN** the MODEL_LONG_CLICK action SHALL be returned

#### Scenario: Off-tree click builds an LlmTapAction

- **WHEN** `CoordinateMapper.map(600, 900, "click", null, actions, state, 1080, 1794)` is called
- **AND** no MODEL_CLICK action contains the point and none is within edge-distance tolerance
- **THEN** `CoordinateMapper.map` SHALL return a new `LlmTapAction` of type `MODEL_LLM_TAP` with `pixelX=600`, `pixelY=900`, `longClick=false`

#### Scenario: Off-tree type_text stays no_match

- **WHEN** `CoordinateMapper.map(600, 900, "type_text", "hello", actions, state, 1080, 1794)` is called
- **AND** no input-capable widget contains or is near the point
- **THEN** `CoordinateMapper.map` SHALL return null (no off-tree tap is synthesized for text input)

#### Scenario: Boundary reject — status bar

- **WHEN** `CoordinateMapper.map(540, 40, "click", null, actions, state, 1080, 1794)` is called
- **AND** `pixelY (40) < deviceHeight * ape.llmBoundaryTopPct (62.79 at the 0.035 default)`
- **THEN** `CoordinateMapper.map` SHALL return null
- **AND** no `LlmTapAction` SHALL be constructed

#### Scenario: toolbar below the status bar is not rejected

- **WHEN** `CoordinateMapper.map(540, 80, "click", null, actions, state, 1080, 1794)` is called
- **AND** a MODEL_CLICK toolbar action has bounds `[0,63][1080,210]`
- **THEN** the boundary check SHALL pass (80 ≥ 62.79)
- **AND** the toolbar action SHALL be returned

#### Scenario: Boundary reject — navigation bar

- **WHEN** `CoordinateMapper.map(135, 1720, "click", null, actions, state, 1080, 1794)` is called
- **AND** a MODEL_CLICK bottom-navigation item has bounds `[0,1647][270,1794]`
- **THEN** no boundary reject SHALL occur: the navigation bar lies below the 1794 px display, outside every pixel `normalize` can produce, so there is no lower band to reject it with
- **AND** the bottom-navigation item SHALL be returned (under the retired `0.94` band, `1720 > 1686.36` was rejected)

#### Scenario: Band configurable without rebuild

- **WHEN** `ape.properties` contains `ape.llmBoundaryTopPct=0.05`
- **THEN** a `pixelY=80` click on a 1080x1794 device SHALL be rejected (80 < 89.7) without any code change

### Requirement: Server Model Acknowledgement

`LlmTelemetry.acknowledge` SHALL emit, through the sink's `llmAck`, one `{"type":"LLM_ACK","server_model":"<model>"}` record (event-sink capability) after the first successful `chat()` response of the run, carrying the model identifier reported by the server in the response envelope (`unknown` when the response omits the field). The `RUN_START` plan echo records the model the run *requested*; `LLM_ACK` records what the server *actually served* — the pair proves an arm talked to the intended model without inspecting the server. At most one `LLM_ACK` per run (the sink latches after the first); a run with zero successful responses emits none (its absence, with the failure-cause counters, is itself diagnostic).

#### Scenario: Server model acknowledged once

- **WHEN** the first successful response reports `qwen3-vl-8b` and 200 further calls succeed
- **THEN** exactly one `LLM_ACK` record SHALL appear in the trace, carrying `server_model:"qwen3-vl-8b"`, after the first successful response
- **AND** no `[APE-LLM-CONFIG-ACK]` line SHALL be emitted

#### Scenario: No acknowledgement without a successful response

- **WHEN** every `chat()` call of a run fails (server down)
- **THEN** zero `LLM_ACK` records SHALL appear in the trace
- **AND** the absence SHALL be read together with the `RUN_END` failure-cause counters, which say why — the pre-change line's absence said only that it never happened

### Requirement: LLM Telemetry Logging

`LlmTelemetry` SHALL record structured per-attempt telemetry as `llm[]` sub-events on the current step's `StepRecord` (event-sink capability), through the sink's `llmCall`, `llmError` and `llmDump`, and aggregate counters reported in `RUN_END.counters.llm`. The breaker-decline sub-event is written by `LlmClient.allows()` through the sink's `llmBreakerOpen`.

**Per-attempt sub-event** (one array entry per routing attempt, in occurrence order within the step):

| Field | Values |
|-------|--------|
| `call` | running call counter for the run |
| `mode` | the engine's mode label as the calling stage passes it to `LlmCall.call`: `new-state` (with a hyphen), `stagnation`, `random`. This is not the `dec.own.trg` vocabulary (`new_state`, `stagnation`, `coin`); the trace carries both as written |
| `tool` | the parsed tool name (`click`, `long_click`, `type_text`, `back`) |
| `qwen` / `px` | `[x,y]` model-space and mapped pixel coordinates |
| `result` | `matched`, `llm_tap`, `no_match` for completed mappings; `error` for attempts abandoned before the mapping step; `breaker_open` for the once-per-open-episode breaker decline |
| `reason` | only on `no_match`: `dead_pair`, `degenerate`, or `boundary` |
| `repair` | only when the decision's `ParsedAction` carries a repair-form label other than `none` (per `llm-infrastructure` INV-LLM-09): `missing_y`, `array_xy`, `quoted_xy`, or `int_scan`. Absent on a clean parse |
| `mcls` / `ncls` / `ndist` / `widgets` | matched class, nearest class, nearest distance, candidate widget count |
| `tok` | `[prompt_tokens, completion_tokens]` (0 if unavailable) |
| `ms` | wall-clock milliseconds for the full pipeline (screenshot → response) |
| `text` | only for text-entry decisions: the typed text (JSON-escaped by the serializer) |
| `cause` / `detail` | only on `result:"error"`: exactly one named cause per abandoned attempt — `timeout`, `http_<status>`, `connection`, `parse`, `image`, `internal` — attributed at the failure point (client seam `getLastErrorCause()` consulted only when `chat()` returned null; attributed by `LlmEngine` otherwise). The screenshot-capture failure keeps its counter (`screenshot_failed`) and its free-text Logger line (INV-RTR-20), with no `error` sub-event |
| `trips` | only on `result:"breaker_open"`: the breaker trip count; written by `LlmClient.allows()` at the first breaker decline of each open episode only, using a side-effect-free breaker state query (`isOpen()`), never a second `shouldAttempt()` |
| `sys` / `user` / `resp` / `tool_calls` | prompt and response dumps, present when the prompt-dump flag is on (default on — recordability parity with the retired unconditional `[APE-LLM-PROMPT]`/`[APE-LLM-RESPONSE]` lines) |

No sub-event carries a step, activity, or variant field: the step and activity are the parent record's envelope, and the prompt variant is run-constant in the `RUN_START` plan echo. The `repair` field remains orthogonal to `result` (a repaired call yields a normal outcome plus `repair=<form>`).

**Counters**: the per-cause failure counters (partitioning the retired `null`), `matched`/`llm_tap`/`no_match`/`dead_pair`/`repaired`, token and latency totals, and `breaker_trips` SHALL be maintained by `LlmTelemetry` and exposed through `counters()`, which teardown hands to the `RUN_END` emitter; they are written as the nested object `RUN_END.counters.llm`, present exactly when `LLM` is in the plan. The decisions denominator (`matched + llm_tap + no_match + Σ failure causes`) is derived by consumers; no ratio is stored.

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
- **AND** `RUN_END.counters.llm` SHALL report `no_match:3` and `dead_pair:2` — the overlay counter that makes bucket D, the falsification gate of the ban, countable from the run's last record alone

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
- **AND** `RUN_END.counters.llm` SHALL report `timeout:1` and `http_error:1`

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
- **AND** `screenshotFailedCount` SHALL be incremented, `breaker.recordFailure()` SHALL NOT be called, and each of the three steps SHALL carry `dec.own.fb:"screenshot"` (design D-B18: the breaker counts transport and parse failures only)
- **AND** `RUN_END.counters.llm` SHALL report `parse_error:1` and `screenshot_failed:3`
- **AND** the screenshot failures SHALL have produced no `error` sub-event — the one abandoned attempt that does not, deliberately (INV-RTR-20)

#### Scenario: Stagnation mode triggered

- **WHEN** `LlmStagnationStage.stagnationMidpointReached` holds at `graphStableCounter = 150` with `graphStableRestartThreshold = 200` and the episode flag armed, and the resulting attempt completes
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

#### Scenario: mode label as the stage passes it

- **WHEN** the new-state stage makes a call that maps to a widget
- **THEN** the call's sub-event SHALL carry `mode:"new-state"`
- **AND** the step's `dec.own.trg` SHALL be `"new_state"`
- **AND WHEN** the coin stage makes a call
- **THEN** the sub-event SHALL carry `mode:"random"` and `dec.own.trg` SHALL be `"coin"`

### Requirement: Probabilistic LLM Routing

When the plan carries `LLM_COIN` (LLM ∧ `p_L = ape.llmPercentage > 0`), the `LlmCoin` stage SHALL be assembled
after `LlmStagnation` and before `MopLauncher`. It is renamed from `LlmRandom` (feature `LLM_RANDOM`). Its trigger
SHALL be `u_L < p_L` (INV-RTR-24). When the coin holds, the shared gate chain is evaluated (INV-RTR-23). When every
gate admits, the engine is invoked. When the coin does not hold, the stage returns `Continue`: a lost coin claims
nothing, so `dec.own.i` is left to the owner that claims the step next (design D-B14.6), and `uL` is still written.

**Which stream the coin comes from.** `u_L` SHALL come from `StepRng`'s `llmCoin` stream, which is derived from
the run seed. It SHALL be drawn at step open on every step of a plan carrying `LLM`, before any stage runs,
whether or not the step reaches this stage (`decision-pipeline` INV-DP-13). It SHALL NOT come from the agent's
generator (`ape.getRandom()`) or from `RunContext.rng()`. The draw therefore no longer depends on any gate or on
an earlier stage, and mounting, unmounting or reparameterizing a gate shifts neither the coin nor SATA's draws.
This changes the random sequence relative to `eea2b1a9`, where the coin came from the agent stream after the
precondition. The design declares it, and the parity goldens are recaptured (`decision-pipeline` "Migration
Parity Gate").

When the stage takes the step while the buffer is non-empty, it abandons the buffer without weakening
(`decision-pipeline` INV-DP-15).

When the stage fires and the engine returns a non-null action, the telemetry mode label SHALL be `"random"`.
`dec.own.trg` is `"coin"`.

#### Scenario: Default 2% routing
- **WHEN** `p_L` is `0.02`, no earlier stage selected this step, `u_L` is below 0.02, the state has 3+ actions, and the breaker allows
- **THEN** the stage SHALL invoke the engine with mode `"random"`

#### Scenario: Disabled
- **WHEN** `ape.llmPercentage` is `0.0` on a plan carrying `LLM`
- **THEN** the `LlmCoin` stage SHALL NOT be assembled
- **AND** `u_L` SHALL still be drawn at every step open, so that a change of `p_L` never shifts another draw

#### Scenario: Priority order preserved
- **WHEN** `_isNewState` is `true` and the new-state mode is enabled, with `p_L = 0.7`
- **THEN** the `LlmNewState` stage SHALL decide the step (hard preemption)
- **AND** at most one LLM call SHALL be made for that step

#### Scenario: draw order preserved under the same seed
- **WHEN** two runs share a cell, a seed and fixtures, and differ only in `ape.llmGateMinActions` (`3` against `0`)
- **THEN** the sequence of `u_L` values SHALL be identical in both runs, step by step
- **AND** SATA's choices SHALL be identical on every step that both runs hand to `SataChain`

#### Scenario: High percentage (70%)
- **WHEN** `p_L` is `0.7`, and neither the new-state nor the stagnation stage decided the step
- **THEN** the `LlmCoin` stage's coin SHALL hold on approximately 70 % of the steps that reach it
- **AND** the rate SHALL be the plan's value applied to a single draw, never a per-stage rescaling: the coin is `u_L < p_L` with one `u_L` per step

---

### Requirement: Config — llmPercentage clamping

`p_L`, the value of `ape.llmPercentage`, SHALL be clamped to `[0.0, 1.0]` once, at plan resolution (`RunSpec`, `run-spec` "Total Fail-Fast Validation": a documented value clamp, not an abort, INV-RUN-11), and not in `Config`. The `LlmCoin` stage SHALL read `p_L` from the resolved plan, and `RUN_START` echoes the clamped value. This rejects malformed probabilities: a value `> 1.0` would otherwise make the coin `u_L < p_L` hold on every step (since every `u_L` drawn from `StepRng.llmCoin` at step open is below `1.0`), and a negative value is meaningless.

#### Scenario: Value above 1 is clamped
- **WHEN** `ape.properties` sets `ape.llm=true` and `ape.llmPercentage=1.5`
- **THEN** the resolved plan's `p_L` SHALL be `1.0`, and `RUN_START.params` SHALL carry `ape.llmPercentage` as `1.0`

#### Scenario: Negative value is clamped
- **WHEN** `ape.properties` sets `ape.llm=true` and `ape.llmPercentage=-0.2`
- **THEN** the resolved plan's `p_L` SHALL be `0.0`, so no `LlmCoin` stage SHALL be assembled

#### Scenario: In-range value unchanged
- **WHEN** `ape.properties` sets `ape.llm=true` and `ape.llmPercentage=0.7`
- **THEN** the resolved plan's `p_L` SHALL be `0.7`

---

### Requirement: Deterministic Dead-Pair Ban

Restated over the `rearch-03-decision-pipeline` text (which moved the record and the check onto `CoordinateMapper`). This stage changes only the *rendering*: the ban's refusal and its counter stop being a `[APE-LLM-TEL]` line and a summary line, and become an `llm[]` sub-event and a `RUN_END` counter. The mechanism, the keys, the k=5 death rule, the input-capable exemption and every measured justification are untouched.

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
- **The table's shares are measured without the input-capable exemption, so 27.5% is now an upper bound.** Exempting a class can only remove pairs from the refused block, never add to it, so the shipped rule refuses *at most* 27.5% and the sub-30% criterion is met a fortiori. **The exact post-exemption share was not re-derived from the corpus** — the bound is what the criterion needs, and the sweep was not re-run for a number that could only move in the safe direction. A reader wanting the realized share must take it from the decisive run's own telemetry, where the `dead_pair` counter in `RUN_END.counters.llm` reports it directly.
- **k=5 raises the floor under every class the exception list named except `EditText`, which keeps its exemption by name.** The list this rule replaces would have granted `Switch`/`CheckBox`/`RadioButton` a threshold of k=2; k=5 grants them 5, so each is protected at least as well without a class test. Those classes are not rare — at k=1 they account for 81 of 2,586 banned pairs (3.1%) in the calibration corpus, with `Spinner` and `CheckedTextView` adding another 25 — so this is dominance by arithmetic, not by rarity. `EditText` is the one member the arithmetic does not cover: the list exempted it outright, and no finite k reproduces an exemption, which is why it survives as the input-capable carve-out above.
- The uniform threshold also reaches cases no class-name enumeration can: N-ary selection widgets (`Spinner`, where one coordinate opens many options — 10 banned pairs at k=1, dropping to 2 by k=3 and fewer still at k=5), Material/AppCompat subclasses whose simple names differ from the base classes (`SwitchCompat`, `MaterialCheckBox` — the corpus does show AndroidX simple names such as `LinearLayoutCompat`, `FloatingActionButton` and `CardView` reaching the tree, so the divergence is real), and Compose trees that expose no meaningful widget class name at all. The input-capable exemption is a class-name test and inherits exactly this limitation: an input widget whose class name is not one of the four is banned like any other.

**Scope limit (stated because it bounds what this mechanism can be credited with):** the ban check runs *after* `CoordinateMapper.map` returns — the screenshot, the HTTP call and the parse have already completed. The ban changes which action executes; it does NOT reduce the LLM arm's per-step latency cost. In the calibration corpus that cost is 35% of a 300 s run's budget, and it is the dominant reason the LLM arm executes 0.622× the steps and discovers 0.729× the distinct states of the algorithmic arm on the same wall clock. This mechanism raises yield per decision; it does not address throughput, and it is not expected to close that gap on its own.

**Outcome feedback:** `StatefulAgent` SHALL report the outcome of each executed LLM-originated decision to `CoordinateMapper` — reached through `RunContext`'s LLM units, via `recordLlmOutcome(...)` — at the point where `new_state` is computed for the step record's `out` section, using the same single-shot, reference-equality-guarded buffered-decision discipline that closes the record (`event-sink` INV-SNK-08). The key material and strike semantics are unchanged by the decomposition; only the owner of the record moves. Only LLM-originated decisions feed the ban record; SATA-selected actions are never banned.

**Ban check site and behavior:** the check lives in `CoordinateMapper`, at the end of its mapping step inside `LlmEngine.selectAction()` (step 8 of the Action Selection Pipeline): once a `matched` or `llm_tap` result is mapped and before it is handed back, `CoordinateMapper` SHALL compute the result's ban key; when the key is dead, the engine SHALL return null — the declared fallback, the same caller-visible path as `no_match`, decided by the remainder of the assembled pipeline (`decision-pipeline` INV-DP-11). The banned decision SHALL be recorded as an `llm[]` sub-event on the current step's record carrying `result:"no_match"` and `reason:"dead_pair"` (`event-sink` capability), and SHALL increment both `noMatchCount` and the `dead_pair` overlay counter reported in `RUN_END.counters.llm`. The ban SHALL NOT record a breaker failure through `LlmClient` — the LLM pipeline succeeded; only its answer was refused — so a ban streak can never open the circuit breaker.

**Memory scope:** the ban record is per-run and in-memory only; no persistence, no cross-run state.

**Falsification gate (protocol, recorded here because it defines B1's success criterion):** bucket D (dead-pair repeats) MUST fall to ≈0 in the decisive-run telemetry BEFORE any new-state gain is credited to this mechanism; if bucket D ≈ 0 and new states do not rise, the ban is judged ineffective.

#### Scenario: repeated dead llm_tap is banned

- **WHEN** an `llm_tap` at `(500, 499)` on state S1 executed earlier in the run and its recorded outcome had `new_state=false`
- **AND** a later `LlmEngine.selectAction()` on state S1 resolves to an `llm_tap` at the same `(500, 499)`
- **THEN** the engine SHALL return null and the remainder of the pipeline SHALL decide the step
- **AND** the step's `llm[]` sub-event SHALL carry `result:"no_match"` and `reason:"dead_pair"`
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
- `ApePromptBuilder` — variant from `LlmParams`; `build` takes the decision's display dimensions instead of the `GUITree` (`llm-prompt` "Single Coordinate Base").
- `ToolCallParser` — unchanged.
- `CoordinateMapper` — coordinate normalization, the status-bar boundary band (`llmBoundaryTopPct`) and the answer-space check, snap tolerance floor (`llmSnapTolerancePx`), back/long-click preference, `fixTextEdit`, and the dead-pair ban record.
- `LlmTelemetry` — all LLM counters, and the sink calls that carry them (`llmCall`, `llmError`, `llmDump`, `llmAck`); it exposes the counters through `counters()` for `RUN_END.counters.llm` and prints no summary. The breaker-decline sink call `llmBreakerOpen` is made by `LlmClient.allows()`, which owns the open-episode latch.
- `LlmEngine` — the thin orchestrator of the Action Selection Pipeline over the units above.

The `max_tokens` value SHALL be read once from `LlmParams` and shared by the request body and the `RUN_START` effective-plan echo, so what the trace reports is what the wire carried. No manifest line SHALL be emitted at `LlmClient` construction: every field it carried (model, temperature, top_p, top_k, max_tokens, timeout_ms, prompt_variant, llm_percentage, on_new_state, on_stagnation, stagnation_threshold, url) is in the echo, which states the whole resolved plan rather than the LLM slice of it.

**Per-request tool schema:** the two schema constants (with and without `type_text`) SHALL be built once at `LlmClient` construction, and `LlmClient.chat(messages, hasInputField)` SHALL pass `SglangClient.chat` the one selected by the same `hasInputField` predicate the system message uses (prompt/wire coherence, INV-LLM-11).

When the plan does NOT carry the LLM feature, none of these units SHALL be constructed and no LLM stage SHALL exist in the decision pipeline (feature absent = stage absent, `decision-pipeline` INV-DP-03); `RunContext.llmEngine()` is then null (INV-RTR-01).

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

### Requirement: Declared LLM Fallback

The plan declares the LLM fallback through the groups it mounts. The decision pipeline realizes it structurally:
an LLM stage returns `Continue` and the rest of the assembled pipeline decides the step (`decision-pipeline`
INV-DP-11). The rest is the following:
- with MOP: the launcher, the component trigger (when STATIC mounts it), the MOP coin and SATA;
- without MOP: the component trigger (when STATIC mounts it) and SATA.

This covers every lost coin, gate refusal, decline, failure, refused answer and breaker denial. There SHALL be no
in-step retry, no substitute selection inside an LLM stage, and no exception path. The `dec.src` of a
step decided by the fallback SHALL be the deciding stage's owner, never `llm`.

Each such step on which an LLM trigger fired or the coin was won SHALL record in `dec.own` the intended owner
`i="llm"` and the trigger `trg` (`new_state`, `stagnation` or `coin`); a lost coin claims nothing and records no
`i="llm"` (design D-B14.6).

**At most one LLM call per step** (INV-RTR-26, design D-B8, D-B14.14). Once an LLM stage has made a call on a step,
whatever its result (`error`, `no_match`, `decline`), every later LLM stage of that step SHALL return `Continue`
without evaluating its gates and without calling the LLM, and the step goes to the next non-LLM stage of the
assembled pipeline. A later stage skipped this way keeps its own state as if it had not been reached: the stagnation
stage's fired flag stays armed. A stage whose trigger did not hold, or whose trigger held and a gate barred, has made
no call and does not count; the next LLM stage is evaluated as usual. A step therefore spends at most one LLM round
trip, and its `llm[]` holds at most one call entry. `dec.own.trg` names the stage that made the step's one call; when
no stage called, it names the first LLM stage whose trigger held. The record also carries the fallback reason `fb`:
- `gate`: a gate refused, named in `g` (`min_actions` or `breaker`);
- `screenshot`: the screenshot could not be captured, so no request reached the server (design D-B18); the attempt is the step's one call, and `trg` names the stage that made it;
- `error`: the attempt was abandoned before mapping (image, HTTP, timeout or parse);
- `no_match`: the answer was refused at mapping (including `dead_pair`, `boundary` and `degenerate`);
- `decline`: the engine returned no action for any other reason.

Each gate refusal adds one to `RUN_END.counters.gateMinActions` or `gateBreaker`, once per step.

#### Scenario: timeout falls back to the declared base
- **WHEN** an `as_mop_llm` run's coin holds, its LLM call times out, and the launcher's counter is at or past its cadence with an eligible candidate
- **THEN** the step SHALL be decided by the `MopLauncher` stage with `dec.src="launcher"`
- **AND** `dec.own` SHALL carry `i="llm"`, `trg="coin"`, `fb="error"`

#### Scenario: decline on an aperv-based LLM arm falls to SATA
- **WHEN** an `as_llm` (no MOP) run's LLM answer is refused (dead pair) on a step
- **THEN** the step SHALL be decided by the `SataChain` stage
- **AND** the refused answer SHALL still be telemetered as `result=no_match reason=dead_pair`, with `dec.own.fb="no_match"`

#### Scenario: a failed new-state call is the step's only LLM call
- **WHEN** in cell `as_llm` with `p_L = 0.9` step 31 reaches a new state with 5 actions, its `u_L` is `0.12` (so `u_L < p_L`), the gates admit, and the new-state call times out
- **THEN** `LlmStagnation` and `LlmCoin` SHALL return `Continue` without calling the LLM, and the step SHALL go to the next non-LLM stage of the pipeline (`ComponentTrigger`, then `SataChain`)
- **AND** the `s:31` record's `llm` array SHALL hold exactly one entry, the completed attempt with `result:"error"` and `cause:"timeout"`
- **AND** `dec.own` SHALL be `{"i":"llm","trg":"new_state","uL":0.12,"fb":"error"}` and `dec.src` SHALL be `"sata"`

---

### Requirement: Declared LLM Gates

`LlmGate` SHALL be an interface, `String name()` and `boolean admits(StepContext ctx)`. A gate SHALL be pure: it
does not draw from any random stream and does not write the step record. `BreakerGate` is the exception to the
second clause: it consults the breaker once, which may move it from OPEN to HALF_OPEN. Two implementations exist:

| Gate | `name()` | Admits when | Plan key (owner `LLM`) | Default | Mounted when |
|---|---|---|---|---|---|
| `MinActionsGate(min)` | `min_actions` | `newState.getActions().size() >= min` | `ape.llmGateMinActions` | `3` | key `> 0` |
| `BreakerGate` | `breaker` | `LlmClient.allows()` returns true (one `shouldAttempt()`) | `ape.llmBreakerFailures` | `3` | key `> 0` |

The composition root SHALL build one chain from the plan, in the order `MinActionsGate`, then `BreakerGate`.
All three LLM stages SHALL share that chain, and it SHALL be evaluated with short-circuit, so that a breaker
refusal is unambiguously breaker-caused. A gate whose parameter is `0` SHALL NOT be mounted. Both keys appear in
`RUN_START`. `ape.llmBreakerOpenMs` (default `60000`) parameterizes the breaker, not the gate's mounting
(`llm-infrastructure`).

The javadoc of `MinActionsGate` SHALL state the rule the code applies: the gate refuses a state with **fewer than
`min`** actions, so the default refuses two actions or fewer. The former `LlmGate` javadoc said "three actions or
fewer" while the code refused `<= 2`. The SATA action buffer (former G1) is not a gate, and Budget (former G4) is
the `Budget` guard (`decision-pipeline`).

#### Scenario: default gates reproduce the former thresholds
- **WHEN** the plan leaves `ape.llmGateMinActions` and `ape.llmBreakerFailures` at their defaults and a coin step's state has exactly 2 actions
- **THEN** `MinActionsGate(3)` SHALL refuse, `BreakerGate` SHALL NOT be consulted, and `dec.own.g` SHALL be `"min_actions"`
- **AND** with 3 actions on the state, `MinActionsGate(3)` SHALL admit

#### Scenario: a gate at zero is not mounted
- **WHEN** the plan sets `ape.llmGateMinActions=0`
- **THEN** the chain SHALL be `[BreakerGate]` alone, and an LLM stage SHALL call the LLM on a state with 1 action when its trigger holds and the breaker allows
