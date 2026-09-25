## Purpose

This delta extends LLM routing to the screens the accessibility tree does not describe. On a game canvas, a camera preview or any custom-drawn surface the abstract state offers no widget action — only `MODEL_BACK` and `MODEL_MENU` — and the shared precondition `LlmGate.allows` has so far required more than two actions, so the LLM was never consulted there. Yet the LLM is the one decision-maker that sees the screen (it receives the screenshot), and the off-tree coordinate tap `MODEL_LLM_TAP` is the one action that needs no widget. On the Study 03 E5 campaign this closed the gate on 100 % of the steps of two LibGDX games (12 of 360 tasks with zero LLM calls), making the LLM arm identical to the arm without LLM on those applications.

The change introduces one run-time fact, the **opaque step** — the step's state offers at least one action and none of them requires a target — and gives the formerly unread `ape.llmPercentageNoSubstrate` its consumer. The key stays where the plan already declares it, a sub-parameter of `LLM_RANDOM` with neutral value `-1`; no feature is added. **Opaque routing is on** when the plan carries `LLM_RANDOM` and the key is `>= 0`. With it on, the shared precondition also holds on opaque steps, for all three LLM stages, and `LlmRandom` uses the key's value as its rate there. With it off — the `-1` every existing arm pushes — the precondition, the stages, the agent generator's draw sequence, and the plan itself (effective parameters, `features`, `digest`) are exactly those of jar `e93dea86`. Keeping the key's owner is what makes the last clause hold: `RunSpec.planValues` includes the keys of every active feature, so on every LLM arm `ape.llmPercentageNoSubstrate=-1` is already inside the plan digest; moving it to a new feature absent at `-1` would drop it from the digest of every LLM arm even with the mechanism off.

Nothing downstream of the gate changes. With no widget on the screen, `CoordinateMapper.map` finds no containment and no snap candidate and synthesizes an `LlmTapAction` for a `click`/`long_click` answer — the mechanism already exercised on widget-rich screens (`llm_tap` is 11.3 % of E5's calls). The prompt already renders an empty element list as `(no elements)`. The dead-pair ban already keys a tap on its exact coordinate.

Three consequences are stated rather than corrected here. The boundary bands of `CoordinateMapper.map` (`ape.llmBoundaryTopPct` 0.05, `ape.llmBoundaryBottomPct` 0.94) reject a coordinate in the top 5 % or bottom 6 % of the screen before any tap is built, on opaque steps as on any other, so a game control in those bands cannot be reached; relaxing them on opaque steps would let taps land on the system navigation bar and needs a replacement for the degenerate-`(0,0)` rejection the top band performs, so it is left to a measured follow-up — the `llm[]` sub-events already carry `reason:"boundary"` and the step carries `dec.opaque`, so the loss is countable per run. On an opaque screen the abstract state does not change when the canvas does, so `LlmNewState` fires at most once per opaque state and every tap is recorded as unproductive; and each tap at a new coordinate is a new ephemeral action whose `NEW_ACTION` edge resets `graphStableCounter`, delaying SATA's forced restart. The unchanging state would need a pixel-based state signal, which changes the abstraction; the counter reset is left as it is on widget screens and made measurable by the `restarts` counter (event-sink).

## Invariants

- **INV-RTR-21**: A step is **opaque** exactly when its state's `getActions()` is non-empty and no element of it returns `requireTarget() == true`. (Non-emptiness matches `CoordinateMapper.map`, which returns null for an empty action list before the tap path; a `State` always constructs `MODEL_BACK`, so the case is defensive, but a call whose answer is discarded by construction must not be made.) The predicate SHALL read the abstract state only — no widget class name, no static-analysis datum, no screenshot — and SHALL be a pure static method of `LlmGate` so the gate and the telemetry evaluate the same definition.
- **INV-RTR-22**: When opaque routing is off (the plan lacks `LLM_RANDOM`, or `ape.llmPercentageNoSubstrate` is `-1`), `LlmGate.allows` SHALL return exactly `actionBufferSize() == 0 && getActions().size() > 2`, and every LLM stage SHALL consume the agent generator's draws at exactly the points it did before this change: for the same seed and fixtures, the sequence of `nextDouble()` draws SHALL be identical to the pre-change build.
- **INV-RTR-23**: When opaque routing is on, `LlmGate.allows` SHALL return `actionBufferSize() == 0 && (getActions().size() > 2 || opaque)`. A non-empty action buffer SHALL close the gate on an opaque step as on any other.
- **INV-RTR-24**: `LlmRandom` SHALL draw a coin on a step only when the gate allows it and the step's rate is strictly positive; the step's rate is the `ape.llmPercentageNoSubstrate` value on an opaque step with opaque routing on, and `ape.llmPercentage` otherwise.
- **INV-RTR-25**: `ape.llmPercentageNoSubstrate` SHALL remain a sub-parameter of `LLM_RANDOM` with neutral value `-1`, so that for any key set with the value `-1` the resolved `features`, effective parameters and plan `digest` equal those jar `e93dea86` resolves from the same keys.

## MODIFIED Requirements

### Requirement: New-State LLM Mode

When the plan enables the LLM new-state mode, the `LlmNewState` stage SHALL be assembled into the decision pipeline ahead of the stagnation, random, launcher, trigger, and SATA stages. Its `decide()` SHALL: (1) check the shared LLM precondition through the single `LlmGate` helper (the precondition exists in exactly one place; the pre-change triplication is deleted) — action buffer empty AND either the state has more than 2 actions or, when opaque routing is on, the step is opaque (INV-RTR-21/22/23); (2) check the new-state trigger — `_isNewState` captured before `markVisited()` (unchanged capture semantics) — and the `LlmClient.allows()` breaker gate; (3) when all hold, invoke `LlmEngine.selectAction(..., "new-state", step)`; (4) on a non-null result, stamp `DecisionSource.LLM`/`PickChannel.LLM` (and resolve a synthesized `MODEL_LLM_TAP` against the state, unchanged) and return `Select`; otherwise return `Continue`.

The stage SHALL run after `adjustActionsByGUITree()` has assigned priorities (unchanged, INV-EXPL-11) and before any SATA rung.

Whether opaque routing is on SHALL be injected into the stage at assembly (INV-DP-12); the stage SHALL NOT read `Config` or the `RunSpec` in `decide()`.

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
- **THEN** all three SHALL consult the same `LlmGate` helper (buffer-empty ∧ (actions > 2 ∨ (opaque-routing ∧ opaque)))
- **AND** no stage SHALL carry its own copy of the precondition expression

#### Scenario: first visit to an opaque state with opaque routing on
- **WHEN** opaque routing is on (`ape.llmPercentage=0.3`, `ape.llmPercentageNoSubstrate=0.3`) and the pipeline reaches `LlmNewState` on a first-visit state whose actions are `[MODEL_BACK, MODEL_MENU]`, with the buffer empty and the breaker allowing
- **AND** the model answers `click` at a point no widget contains
- **THEN** the stage SHALL invoke the engine with mode `"new-state"` and return `Select` with the `LlmTapAction` the engine synthesized, resolved against the state

#### Scenario: first visit to an opaque state with opaque routing off
- **WHEN** opaque routing is off (`ape.llmPercentageNoSubstrate=-1`) and the state's actions are `[MODEL_BACK, MODEL_MENU]`
- **THEN** the stage SHALL return `Continue` without calling the engine, exactly as before this change

---

### Requirement: Probabilistic LLM Routing

When the plan enables probabilistic routing (`llm.percentage > 0`), the `LlmRandom` stage SHALL be assembled after `LlmStagnation` and before `MopLauncher`. Its trigger SHALL be `random.nextDouble() < rate`, evaluated only after the shared `LlmGate` precondition holds and only when `rate > 0`, followed by the `LlmClient.allows()` breaker gate — the same conjunct order and short-circuiting as before the restructuring, so the seeded draw sequence is unchanged (`decision-pipeline` INV-DP-10). When `llm.percentage` is `0.0` the stage SHALL NOT exist (no draw is ever consumed — identical to the pre-change short-circuit).

**The step's rate.** With opaque routing off the rate is `llm.percentage` on every step, and since that rate is the stage's positive assembly condition the `rate > 0` conjunct is always true — which is what keeps the draw sequence of such a plan identical to the pre-change build (INV-RTR-22). With opaque routing on the rate is the `ape.llmPercentageNoSubstrate` value on an opaque step and `llm.percentage` on every other step (INV-RTR-24). A zero opaque rate draws no coin on opaque steps: that value means the new-state and stagnation stages may consult the LLM on opaque screens while random routing does not. Both rates SHALL be injected at assembly (INV-DP-12).

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
- **WHEN** two builds (pre-change and post-change) run the same preset, seed, and fixtures with `ape.llmPercentageNoSubstrate=-1`, over a fixture sequence that includes states whose actions are `[MODEL_BACK, MODEL_MENU]`
- **THEN** the sequence of `nextDouble()` draws consumed by probabilistic routing SHALL be identical

#### Scenario: High percentage (70%)
- **WHEN** `llm.percentage` is `0.7`, neither the new-state nor the stagnation stage decided the step, and the precondition holds
- **THEN** the `LlmRandom` stage's trigger SHALL hold on approximately 70 % of the steps that reach it
- **AND** the rate SHALL be the plan's value applied to a single draw, never a per-stage rescaling: the stage is assembled with `percentage` and evaluates `nextDouble() < percentage` once per reached step

#### Scenario: opaque step uses the opaque rate
- **WHEN** the plan carries `llm.percentage = 0.3` and `ape.llmPercentageNoSubstrate = 0.9`, the buffer is empty, and the step's state offers `[MODEL_BACK, MODEL_MENU]`
- **THEN** the stage SHALL draw one coin and fire when it is below `0.9`
- **AND** on the next step, whose state offers three widget actions, it SHALL draw one coin and fire when it is below `0.3`

#### Scenario: zero opaque rate draws no coin
- **WHEN** the plan carries `llm.percentage = 0.3` and `ape.llmPercentageNoSubstrate = 0`, and the step is opaque with the buffer empty
- **THEN** the stage SHALL return `Continue` without drawing from the agent generator

## REMOVED Requirements

### Requirement: Config — llmPercentageNoSubstrate seam

**Reason**: The seam gets its consumer. `ape.llmPercentageNoSubstrate` is no longer an exposed-only value whose `-1` means "inherit `llmPercentage`": it stays a sub-parameter of `LLM_RANDOM` with neutral `-1`, now meaning "opaque routing off", and is read through the `RunSpec` (see "Opaque-Screen LLM Routing"). INV-RTR-09 ("no effect on any routing decision") is withdrawn with it. `Config.llmPercentageNoSubstrate` and `Config.clampLlmPercentageNoSubstrate` SHALL be deleted, together with the `ConfigTest` clamp cases and the `LlmRandomStageTest` no-consumer guard; the clamp behavior they tested (a real negative collapses to `-1`, a value above `1` clamps to `1`) is kept by `RunSpec`'s normalization and re-stated in the new requirement.

**Migration**: none needed on the harness side. Every existing arm pushes `-1`, which remains the neutral value and now means "mechanism off" — the behavior those arms already had, because nothing read the key.

## ADDED Requirements

### Requirement: Opaque-Screen LLM Routing

**Opaque routing** SHALL let the three LLM stages consult the model on **opaque** steps — steps whose state offers at least one action and none requiring a target (INV-RTR-21) — which the shared precondition otherwise closes by size. It is on exactly when the plan carries `LLM_RANDOM` and `ape.llmPercentageNoSubstrate >= 0`; the key's value is the `LlmRandom` rate on opaque steps (INV-RTR-24). No `Feature` is added: the key remains a sub-parameter of `LLM_RANDOM` (INV-RTR-25).

**Key semantics.** `RunSpec` SHALL normalize the key as before: a real negative collapses to the sentinel `-1`, a value above `1` clamps to `1`. `-1` SHALL mean "opaque routing off" — neither the gate, the stages, the draw sequence nor the plan digest differ from jar `e93dea86` (INV-RTR-22, INV-RTR-25). A value in `[0, 1]` on a plan carrying `LLM_RANDOM` SHALL turn it on, and is visible in `RUN_START.params` (the key is inside the plan because its owner is active). The existing run-spec rules are sufficient and unchanged: on a plan without `LLM_RANDOM` the key is accepted only at `-1` (reported `inert`), and any other value aborts with `missing_dependency` naming `LLM_RANDOM` (INV-RUN-05). Consequently opaque routing requires `ape.llmPercentage > 0`; `ape.llmPercentageNoSubstrate=0` is the setting that opens the gate for the new-state and stagnation stages without random routing on opaque steps.

**The predicate.** `LlmGate.isOpaque(State)` SHALL return `true` when `state.getActions()` is non-empty and none of its elements has `requireTarget() == true`. It deliberately does not identify the kind of surface: a LibGDX canvas, a camera preview and a screen whose only widget is non-actionable are all opaque, and so is a splash or loading screen whose tree is empty. The last case costs LLM calls bounded by the rate and by the single new-state call per state; it is accepted rather than filtered, because every class-based or static-analysis-based filter misclassifies (a GL surface is commonly reported to accessibility under a generic view class, and the static widgetless-substrate flag described applications, not screens). The predicate is not specific to games: on E5, `com.tananaev.passportreader_22` spends 79 % of its steps on such states.

**What the LLM can do on an opaque step.** The engine SHALL be invoked unchanged. `CoordinateMapper.map` SHALL return an `LlmTapAction` for a `click`/`long_click` answer inside the boundary bands (no widget contains or snaps to the point), the targetless `MODEL_BACK` for a `back` answer, and null for `type_text` — the existing rules of "Coordinate-to-ModelAction Mapping", which this requirement does not modify.

**Declared limitation — boundary bands.** The bands (`ape.llmBoundaryTopPct` 0.05, `ape.llmBoundaryBottomPct` 0.94) SHALL apply on opaque steps unchanged: an answer in the top 5 % or bottom 6 % of the screen is rejected as `no_match`/`boundary` before a tap is built, which excludes game controls placed there. They are not relaxed here because they also keep taps off the system bars and catch the degenerate `(0,0)` emission; the loss is countable from the trace as `reason:"boundary"` sub-events on `dec.opaque` steps, which is the evidence a follow-up needs.

**Data Contracts**

- Input: `ape.llmPercentageNoSubstrate: double` from the plan (`-1` or `[0, 1]` after normalization); `StepContext.newState().getActions()`; `StepContext.actionBufferSize()`.
- Output: the gate's boolean; `LlmRandom`'s per-step rate; the `dec.opaque` flag on the step record (event-sink).
- Side-effects: LLM calls and agent-generator draws on opaque steps, only when opaque routing is on.
- Error: none added; a non-neutral value on a plan without `LLM_RANDOM` aborts through the existing sub-parameter rule.

#### Scenario: game canvas consulted with opaque routing on
- **WHEN** the plan is `llm` with `ape.llmPercentage=0.7` and `ape.llmPercentageNoSubstrate=0.7`, and every state of the run is `AndroidLauncher@…[W=1][A=2]` with actions `[MODEL_BACK, MODEL_MENU]`
- **THEN** `LlmNewState` SHALL be consulted on the first visit, `LlmStagnation` at each episode's midpoint, and `LlmRandom` on roughly 70 % of the remaining buffer-empty steps
- **AND** each `click` answer inside the boundary bands SHALL be dispatched as an `LlmTapAction` at the answered pixel, counted under `llm_tap`

#### Scenario: opaque routing off preserves the pre-change run and plan
- **WHEN** the same run is repeated with `ape.llmPercentageNoSubstrate=-1` and the same seed
- **THEN** no LLM call SHALL be made on any `[MODEL_BACK, MODEL_MENU]` state
- **AND** the action sequence SHALL equal the pre-change build's
- **AND** `RUN_START.features`, `RUN_START.params` and the plan `digest` SHALL equal those of jar `e93dea86` for the same keys

#### Scenario: value without random routing
- **WHEN** an LLM arm sets `ape.llmPercentage=0` and `ape.llmPercentageNoSubstrate=0.5`
- **THEN** resolution SHALL abort with `missing_dependency` naming `LLM_RANDOM` (existing sub-parameter rule, unchanged)

#### Scenario: opaque predicate ignores non-actionable widgets
- **WHEN** a state's tree has one widget that yields no model action, and its actions are `[MODEL_BACK, MODEL_MENU]`
- **THEN** `LlmGate.isOpaque` SHALL return `true`
- **AND** WHEN a state offers `[MODEL_CLICK(button), MODEL_BACK]` with `ape.modelMenuEnabled=false`
- **THEN** `LlmGate.isOpaque` SHALL return `false`, and the gate SHALL stay closed by size as before

#### Scenario: empty action list is not opaque
- **WHEN** a state's `getActions()` is empty
- **THEN** `LlmGate.isOpaque` SHALL return `false` and no LLM stage SHALL call the engine on it

#### Scenario: tap in the bottom band is rejected on an opaque step
- **WHEN** opaque routing is on, the step is opaque, and the model answers `click` at `pixelY = 0.97 × deviceHeight`
- **THEN** the engine SHALL return null with `result:"no_match"`, `reason:"boundary"`, and the step record SHALL carry `dec.opaque:1`

#### Scenario: buffered navigation still closes the gate
- **WHEN** opaque routing is on, the step is opaque, and the action buffer holds two pending actions
- **THEN** all three LLM stages SHALL return `Continue` and the `SataChain` buffer rung SHALL decide
