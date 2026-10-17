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
2. `ScreenshotStep` determines the device dimensions (`deviceDimensions(tree)`: the display bounds, the tree's root node only if the display throws, the 1080 × 1920 constants last) and captures the screenshot at those dimensions. This pair is the decision's coordinate base: steps 4 and 8 use it and determine none of their own (INV-RTR-21). Null capture → breaker failure recorded via `LlmClient`, `screenshot_failed` counter + the free-text screenshot-failure diagnostic carrying the activity and the failing stage via `LlmTelemetry`, return null. This is the one abandoned attempt that produces **no** `llm[]` sub-event (INV-RTR-20).
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

### Requirement: Config — llmPercentageNoSubstrate seam

`ape.llmPercentageNoSubstrate` (double) SHALL be a plan key, resolved by `RunSpec` with default `-1`, and not a `Config` field. The value `-1` is a sentinel meaning "inherit `p_L`" — it does NOT mean a routing percentage of −1. In the run-spec `Feature` model the key is a sub-parameter owned by the `LLM` feature family with declared neutral value `-1`: every current baseline arm pushes `-1` explicitly (including non-LLM arms), which resolution accepts as an inert key (INV-RUN-05 of `run-spec`); an explicit value `>= 0` on a plan without the `LLM` feature aborts resolution as a missing dependency.

- The `-1` sentinel SHALL be exempt from the `[0.0, 1.0]` clamp applied to `p_L` (INV-RTR-08): clamping would collapse the sentinel to `0.0`. When the configured value is `-1`, it SHALL pass through unclamped, and any other negative value SHALL resolve to the sentinel.
- When the configured value is `>= 0` (on an LLM plan), it SHALL be clamped to `[0.0, 1.0]` at resolution exactly like `p_L`.

This capability still has NO consumer of `ape.llmPercentageNoSubstrate`. The `LlmCoin` stage, its coin `u_L < p_L` (`u_L` drawn from `StepRng.llmCoin` at step open), the routing predicates and all telemetry SHALL be unchanged by it; the coin SHALL continue to use the plan's `p_L` unconditionally. The seam exists so round-2 adaptive routing (F′) can, without a protocol change, read `isWidgetlessSubstrate()` at load and substitute this percentage for widgetless apps.

- **INV-RTR-09**: `ape.llmPercentageNoSubstrate` SHALL have no effect on any routing decision; it SHALL be resolved and exposed only. Its `-1` default SHALL pass through the clamp unchanged; a configured value `>= 0` SHALL be clamped to `[0.0, 1.0]`.

#### Scenario: default sentinel not clamped
- **WHEN** `ape.properties` does not set `ape.llmPercentageNoSubstrate`
- **THEN** the resolved plan's value SHALL equal `-1` (not clamped to `0.0`)

#### Scenario: configured value clamped
- **WHEN** an LLM arm's `ape.properties` sets `ape.llmPercentageNoSubstrate=1.5`
- **THEN** the resolved plan's value SHALL be `1.0`

#### Scenario: no routing behaviour change
- **WHEN** `ape.llmPercentageNoSubstrate=0.7` on an LLM arm with `ape.llmPercentage=0.02` and an app is widgetless
- **THEN** the `LlmCoin` stage SHALL still hold exactly when `u_L < 0.02`; the no-substrate value SHALL NOT be consumed

#### Scenario: explicit sentinel on a non-LLM arm is inert, not an error
- **WHEN** a non-LLM arm's `ape.properties` contains `ape.llmPercentageNoSubstrate=-1` (as every current baseline arm does) and no `ape.llmUrl`
- **THEN** resolution SHALL succeed and the key SHALL be listed as `inert` in the `RUN_START` echo

---

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
- `error`: the attempt was abandoned before mapping (screenshot, image, HTTP, timeout or parse);
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
