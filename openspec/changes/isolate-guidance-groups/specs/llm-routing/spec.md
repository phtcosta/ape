# Delta: LLM Routing (isolate-guidance-groups)

## Purpose

In the E6 LLM arm, `p_L = 0.9` was configured and the LLM was called on 69 % of steps. Only about 45 % of steps
were decided by it (plan §2.5). The rest of the dose was lost to rules the plan did not declare:
- G1: a non-empty SATA buffer;
- G2: two actions or fewer, although the `LlmGate` javadoc said "three or fewer";
- G3: the circuit breaker, with constants in code;
- G4: Budget, by order;
- G7: a coin drawn from the agent stream only after the precondition passed.

This delta declares every one of these rules:
- **Gates are a Specification** (D-B8). `LlmGate` becomes an interface, `name()` and `admits(ctx)`. Its two
  implementations are `MinActionsGate(min)` and `BreakerGate`, and each takes its parameter from the plan.
  G4 is the BUDGET guard (`decision-pipeline`).
- **The buffer is not a gate** (D-A6, P-4). An LLM step preempts a non-empty buffer, which is abandoned without
  weakening.
- **The triggers stay as they are** (D-A5, D-A17): new-state on every new state and stagnation once per episode,
  with no coin. The coin `p_L` applies to the other steps. The dose is described as "every new state + `p_L` on the
  other steps".
- **The coin has its own stream** (P-3, `decision-pipeline` INV-DP-13). `u_L` is drawn every step, before any
  gate. `LLM_RANDOM` is renamed `LLM_COIN`.
- **At most one LLM call per step** (D-B8, D-B14.14). Once an LLM stage has called, the later LLM stages of the
  step return `Continue` without calling: a failed call no longer leads to a second or third call on the same step.
- **Every LLM-intended step is recorded** in `dec.own`: the intended owner, the trigger, the gate that barred
  the step and the fallback reason.

## Invariants

- **INV-RTR-23**: The conditions under which an LLM stage, once its trigger or coin holds, does not call the LLM
  SHALL be exactly the mounted `LlmGate` chain. That chain is `MinActionsGate(ape.llmGateMinActions)` and then
  `BreakerGate`, evaluated in this order with short-circuit. A gate whose parameter is `0` SHALL NOT be mounted.
  `MinActionsGate(3)` admits exactly the states with `getActions().size() >= 3`, which equals the former `> 2`.
  The SATA action buffer SHALL NOT be a condition of any LLM stage.
- **INV-RTR-24**: The `LlmCoin` stage's trigger SHALL be `u_L < p_L`, where `u_L` was drawn at step open from
  `StepRng`'s `llmCoin` stream (`decision-pipeline` INV-DP-13). The `LlmNewState` and `LlmStagnation` triggers SHALL
  consume no coin.
- **INV-RTR-25**: On every step on which an LLM stage's trigger or coin held, `dec.own` SHALL record the following,
  whichever owner finally took the step:
  - `i="llm"`;
  - the trigger `trg`;
  - the barring gate `g`, when one barred;
  - the fallback reason `fb`, when the LLM did not take the step.
- **INV-RTR-27** (replaces the main spec's *Error* bullet "Network errors, unparseable responses, AND a null
  screenshot SHALL trigger `LlmCircuitBreaker.recordFailure()`" at archive): Network errors and unparseable
  responses SHALL trigger `LlmCircuitBreaker.recordFailure()`. A null screenshot SHALL NOT: it counts
  `screenshot_failed`, records `dec.own.fb="screenshot"`, and the step falls through as any other fallback
  (design D-B18). The attempt still spends the step's one LLM call (INV-RTR-26), so no later LLM stage of the step
  captures again.
- **INV-RTR-26**: A step SHALL make at most one LLM call. Once an LLM stage of the step has called the LLM, whatever
  the result, every later LLM stage of that step SHALL return `Continue` without evaluating its gates and without
  calling. A stage whose trigger did not hold, or whose gate barred, has made no call.

## ADDED Requirements

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

## MODIFIED Requirements

### Requirement: New-State LLM Mode

When the plan carries `LLM_NEW_STATE`, the `LlmNewState` stage SHALL be assembled after the `Budget` and
`Restart` guards and ahead of the stagnation, coin, launcher, trigger, MOP-coin and SATA stages.
`decide()` SHALL perform these steps in order:
1. **Trigger.** Check `_isNewState`, which is captured before `markVisited()` (the capture is unchanged). When it
   is false, return `Continue` and record nothing.
2. **Gates.** Evaluate the shared gate chain (INV-RTR-23) on the step's `StepContext`. When a gate refuses,
   return `Continue` and record `dec.own` with `i="llm"`, `trg="new_state"`, `g=<gate name>` and `fb="gate"`.
3. **Call.** When every gate admits, invoke `LlmEngine.selectAction(..., "new-state", step)`.
4. **Result.** On a non-null result:
   - stamp the owner `llm` and the channel `llm` through `LlmAcceptance`, the acceptance helper formerly in
     `LlmGate.accept`, and resolve a synthesized `MODEL_LLM_TAP` against the state (unchanged);
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

---

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
- **THEN** the stage SHALL burn the flag and invoke `LlmEngine.selectAction(..., "stagnation", step)`
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

---

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

### Requirement: Action Selection Pipeline

`LlmEngine.selectAction(GUITree tree, State state, List<ModelAction> actions, List<ApePromptBuilder.ActionHistoryEntry> recentActions, String mode)` SHALL run the LLM decision pipeline over the decomposed units and return `ModelAction` or `null`. The step semantics are those of the pre-decomposition `LlmRouter.selectAction`, with two exceptions: the prompt shares the decision's coordinate base (step 4) instead of deriving dimensions from the tree's root node, and step 8 refuses an answer outside the answer space and applies one boundary band, the status bar, instead of two (INV-RTR-22). Otherwise only the owner of each step changes:

**The argument list drops the MOP context (`isolate-guidance-groups`, design D-B1, plan §4.6).** The engine no longer receives a `MopContext`, and no LLM stage reads `ctx.mopData()` to build a prompt. The MOP and static signals reach the prompt as the mounted prompt contributions (`llm-prompt` "Prompt Built From Group Contributions"): the composition root builds `StaticPromptContribution` iff `Feature.STATIC` and `MopPromptContribution` iff `Feature.MOP` is in the plan, and hands that list to the engine's `ApePromptBuilder` when the run's LLM units are constructed (see `LLM Unit Lifecycle and Ownership`); the MOP contribution holds the run's MOP context itself. `recentActions` remains a per-step, agent-owned value that step 4 below hands to `ApePromptBuilder.build(...)`. The engine is constructed once per run and owned by `RunContext`, while the per-step view belongs to the stages — so for the engine to source it itself it would have to hold a `StepContext` or the agent, and design D2 exists to prevent exactly that. The calling stage passes it from `ctx.actionHistory()`, unchanged.

1. `LlmTelemetry` counts the attempt (`totalCalls++`, per INV-RTR-07).
2. `ScreenshotStep` determines the device dimensions (`deviceDimensions(tree)`: the display bounds, the tree's root node only if the display throws, the 1080 × 1920 constants last) and captures the screenshot at those dimensions. This pair is the decision's coordinate base: steps 4 and 8 use it and determine none of their own (INV-RTR-21). Null capture → `screenshot_failed` counter + the free-text screenshot-failure diagnostic carrying the activity and the failing stage via `LlmTelemetry`, fallback reason `screenshot`, return null. The attempt spends the step's one call (INV-RTR-26). A null capture SHALL NOT record a breaker failure: it neither increments the failure count nor reopens a `HALF_OPEN` breaker, because the server was never asked and nothing about the model's health was learned (design D-B18). The breaker records failures only for transport (step 5) and parse (step 7). This is the one abandoned attempt that produces **no** `llm[]` sub-event (INV-RTR-20).
3. `ScreenshotStep` resizes and base64-encodes. Null → `image` cause as an `llm[]` sub-event with `result:"error"`, return null.
4. `ApePromptBuilder.build(deviceWidth, deviceHeight, state, actions, base64, recentActions)` builds the messages from the base prompt and the mounted contributions, with the step-2 dimensions, so every [0,1000) coordinate in the prompt is in the space of the image and of the step-8 mapping (`llm-prompt` INV-PRM-06). The tree is not passed to the builder; `LlmTelemetry` stages the prompt via `llmDump`, which rides the next sub-event as `sys`/`user` when the prompt-dump flag is on (default on).
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
- **AND** `screenshotFailedCount` SHALL be incremented, `breaker.recordFailure()` SHALL NOT be called, and each of the three steps SHALL carry `dec.own.fb:"screenshot"` (design D-B18: the breaker counts transport and parse failures only)
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

**Edge-based distance (fallback matching)**: If no action's bounds contain the point, compute the **point-to-rectangle distance** from `(pixelX, pixelY)` to each candidate's resolved bounds: `dx = max(bounds.left − pixelX, 0, pixelX − bounds.right)`, `dy = max(bounds.top − pixelY, 0, pixelY − bounds.bottom)`, `dist = hypot(dx, dy)` (zero when the point is inside). Return the action with the minimum distance if that distance is within the proportional tolerance `max(ape.llmSnapTolerancePx, min(nodeWidth, nodeHeight) / 2)` pixels (floor default `50`, configuration-exposed per J1b; read from the plan, `spec().llm().integer("ape.llmSnapTolerancePx")`, with no `Config` field, design D-B24). This replaces centre-distance, whose geometry punished elongated widgets: on a 1080×150 bar, only points within ~75 px of the **centre** could snap, leaving ~450 px of the bar's own edge unsnappable — a tap 20 px outside a wide widget failed while being visually on target. The `ape.llmSnapTolerancePx` default is unchanged; raising it (to ~150) is an rv-android configuration decision gated on the dead-pair ban.

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

## REMOVED Requirements

### Requirement: Config — llmPercentageNoSubstrate seam

**Reason**: The key never had a consumer. The widgetless-substrate predicate it waited for (F′) was never derived, so
`Config.llmPercentageNoSubstrate` and the plan key `ape.llmPercentageNoSubstrate` were dead (design D-B24).
INV-RTR-09 goes with it.

**Migration**: `ape.llmPercentageNoSubstrate` is a retired key (`run-spec` "Typed, Total, Fail-Fast Resolution"):
stating it aborts with `retired_key`, so a harness stops pushing it. The LLM coin uses `ape.llmPercentage`
(`p_L`) only, as it always did.
