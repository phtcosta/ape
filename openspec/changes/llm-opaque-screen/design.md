# Design: llm-opaque-screen

## Context

The shared LLM precondition lives in one place, `src/main/java/com/android/commands/monkey/ape/agent/pipeline/LlmGate.java:52-54`:

```java
static boolean allows(StepContext ctx) {
    return ctx.actionBufferSize() == 0 && ctx.newState().getActions().size() > 2;
}
```

All three LLM stages consult it first — `LlmNewStateStage.java:71`, `LlmStagnationStage.java:104`, `LlmRandomStage.java:81`. On a screen the accessibility tree does not describe, the abstract state's action list is `[MODEL_BACK, MODEL_MENU]` (two actions; one with `ape.modelMenuEnabled=false`), so the gate never opens. The Study 03 replication session measured this on E5 (jar `e93dea86`): two LibGDX games receive zero LLM calls on every prompt and model, and their traces hold one abstract state (`AndroidLauncher@…@[W=1][A=2]`) for 600 s of alternating `MODEL_BACK`/`MODEL_MENU`. The proposal carries the numbers.

Everything downstream of the gate already handles a widgetless screen:

- `ApePromptBuilder` renders an empty element list as `(no elements)` (`llm/ApePromptBuilder.java:651`).
- `CoordinateMapper.map` (`llm/CoordinateMapper.java:120-251`) returns `state.getBackAction()` for `back`, finds no containment or snap candidate for `click`/`long_click` and synthesizes `new LlmTapAction(state, x, y, long)`, and returns null for `type_text`.
- `LlmTapAction` (`model/LlmTapAction.java`) is ephemeral (INV-MODEL-14): not in `State.actions`, never re-selected, never refined (`Model.resolveNonDeterministicTransitions` skips it, `model/Model.java:441`), dispatched against the physical display bounds.
- The dead-pair ban keys a tap on `(stateKey, pixelX, pixelY)` (`CoordinateMapper.banKey`).

The key `ape.llmPercentageNoSubstrate` exists end to end with no reader: `Config.java:184` (static field + clamp), `runtime/Feature.java:182` (sub-parameter of `LLM_RANDOM`, neutral `-1`), `runtime/KeyOwnership.java:222`, `runtime/RunSpec.java:267` (normalization), `runtime/Presets.java:126` (every preset pushes `-1`), and rv-android `tool.py:198` (`llm_percentage_no_substrate`). Its spec requirement (INV-RTR-09) says it has no effect, and `LlmRandomStageTest:279-310` guards that.

Constraints: the project's run-plan rules (a feature absent from the plan has no mechanism, INV-RUN-05; stages read parameters injected at assembly, never `Config`, INV-DP-12; fail-fast resolution, INV-RUN-02), the agent-generator draw-sequence discipline (`decision-pipeline` INV-DP-10, `llm-routing` "Probabilistic LLM Routing"), and telemetry identical on every arm (event-sink "Telemetry Neutrality").

## Architecture

```text
RunSpec ──(LLM_RANDOM present ∧ noSubstrate ≥ 0? rate)──► DecisionPipeline.fromSpec
                                                    │ injects boolean opaqueEnabled
                                                    │ (+ opaqueRate for LlmRandom)
                                                    ▼
StepContext ──► LlmNewStateStage ─┐
            ──► LlmStagnationStage├─► LlmGate.allows(ctx, opaqueEnabled)
            ──► LlmRandomStage ───┘        │  bufferEmpty ∧ (actions>2 ∨ (opaqueEnabled ∧ isOpaque(state)))
                   │ rate = opaque ? opaqueRate : percentage; draw only if rate>0
                   ▼
              LlmEngine.selectAction (unchanged) ──► CoordinateMapper.map (unchanged) ──► LlmTapAction

StatefulAgent.resolveNewAction ──► sink.beginStep(..., LlmGate.isOpaque(newState)) ──► dec.opaque:1
ApeAgent.requestRestart ─────────► sink.restartRequested() ──► RUN_END.counters.restarts
```

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `LlmGate.isOpaque(State)` | The opaque predicate (INV-RTR-21) | `State` | `boolean` |
| `LlmGate.allows(StepContext, boolean)` | Shared precondition with the opaque clause | step context, feature flag | `boolean` |
| `LlmNewStateStage` / `LlmStagnationStage` | Pass the injected flag to the gate | `StepContext` | `StageResult` |
| `LlmRandomStage` | Per-step rate choice, zero-rate no-draw | `StepContext` | `StageResult` |
| `DecisionPipeline.fromSpec` | Derives opaque routing (`LLM_RANDOM` in the plan ∧ key `>= 0`) and injects the flag and the rate | `RunSpec` | stages |
| `Feature` / `KeyOwnership` / `RunSpec` | Unchanged: the key stays an `LLM_RANDOM` sub-parameter, neutral `-1` | — | — |
| `EventSink.beginStep(…, boolean opaque)` / `StepRecord` | `dec.opaque` | flag | record field |
| `EventSink.restartRequested()` / `NdjsonSink.runEnd` | `counters.restarts` | calls | record field |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| INV-RTR-21 opaque predicate | `LlmGate.isOpaque` | `LlmGateTest.opaqueWhenNoTargetedAction`, `…notOpaqueWithOneWidgetAction`, `…emptyActionsNotOpaque` |
| INV-RTR-22 off ⇒ pre-change gate and draws | `LlmGate.allows(ctx, false)`; `LlmRandomStage` with `opaqueEnabled=false` | `LlmGateTest.featureOffIsSizeRule`; `LlmRandomStageTest.featureOffDrawSequenceUnchanged` |
| INV-RTR-23 on ⇒ opaque clause, buffer kept | `LlmGate.allows(ctx, true)` | `LlmGateTest.featureOnOpensOpaque`, `…bufferClosesOpaque` |
| INV-RTR-24 rate choice, zero-rate no draw | `LlmRandomStage.decide` | `LlmRandomStageTest.opaqueStepUsesOpaqueRate`, `…zeroOpaqueRateDrawsNothing` |
| New-State LLM Mode (opaque scenarios) | `LlmNewStateStage` | `LlmNewStateStageTest.opaqueFirstVisit{On,Off}` |
| SataAgent — LLM New-State Hook | same | same |
| INV-RTR-25 plan unchanged at `-1` | no change to `Feature`/`KeyOwnership`/`RunSpec` | `RunSpecResolveTest.sentinelPlanDigestUnchanged` (golden `digest`/`features`/`params` captured from `e93dea86` for the `llm` and `llm_mop` presets); `RunSpecAbortTest` existing sub-parameter cases (`0.5` without `LLM_RANDOM` aborts) |
| Boundary bands unchanged on opaque steps (declared limitation) | `CoordinateMapper.map` untouched | `CoordinateMapperOffTreeTapTest`: opaque state, `pixelY = 0.97h` → `no_match`/`boundary` |
| Removal of the seam | delete `Config.llmPercentageNoSubstrate`, `clampLlmPercentageNoSubstrate` | delete `ConfigTest` cases and `LlmRandomStageTest` no-consumer guard |
| INV-SNK-15 `dec.opaque` | `StatefulAgent.resolveNewAction` → `beginStep`; `StepRecord` | `NdjsonSinkTest.opaqueFlag*`; `SinkNeutralityTest` unchanged |
| INV-SNK-16 `restarts` | `ApeAgent.requestRestart` → `sink.restartRequested()`; `NdjsonSink.runEnd` | `NdjsonSinkTest.restartsCounted`, `…restartsZeroWritten` |
| Device behavior | whole path | tasks group 6 (retrowars, on vs off) |

## Goals / Non-Goals

**Goals:**
- Let the LLM act on screens with no widget action, through the existing off-tree tap, when the plan asks for it.
- Keep every existing arm — every plan with `ape.llmPercentageNoSubstrate=-1` — identical in plan, gate, draws and actions to jar `e93dea86`.
- Make opaque steps and forced restarts visible in the trace on every arm.

**Non-Goals:**
- A state signal for opaque screens (screenshot hash or similar). The abstraction is the project's core mechanism; `LlmNewState` therefore fires at most once per opaque state.
- Changing how ephemeral taps reset `graphStableCounter`.
- Identifying the kind of surface (game engine, camera, Compose, WebView).
- Choosing the E6 rate. That is a campaign decision made in rv-android.

## Decisions

**D1 — The predicate is "no action requires a target", read from the abstract state.** Alternatives: (a) `getActions().size() <= 2` — wrong when `ape.modelMenuEnabled=false` (a one-button dialog has two actions and is not opaque); (b) widget class names such as `SurfaceView`/`GLSurfaceView` — rejected by the owner; the class the accessibility node reports for a GL surface is not reliable, and E5's trace does not even record it; (c) static analysis (`isWidgetlessSubstrate`, deleted with the full-JSON parser) — describes applications, not screens, and a Compose app that exposes semantics at run time would be misclassified; (d) "no widget action and visited k times" to exclude splash screens — adds a calibration knob for a cost the rate already bounds. The chosen predicate is the explorer's own knowledge: SATA has nothing but leave or open the menu.

**D2 — Reuse `ape.llmPercentageNoSubstrate`, redefining `-1` from "inherit" to "off".** Alternative: a new boolean `ape.llmOpaqueScreen` and retire the orphan key. Reuse wins because every arm already pushes `-1`, the harness already maps the key and its type/default are pinned by rv-android's tests; the redefinition breaks nothing because nothing read the key. The cost is a semantic amendment recorded in the specs.

**D3 — The key stays a sub-parameter of `LLM_RANDOM`; no feature is added.** An earlier revision made the key the activation key of a new feature `LLM_OPAQUE_SCREEN` so that `RUN_START.features` would state the mechanism. Review by the Study 03 replication session showed it breaks the requirement that matters most: `RunSpec.planValues` (`RunSpec.java:340-354`) keeps the effective values of every **active** feature's keys and `computeDigest` (`:413-429`) hashes them, so today `ape.llmPercentageNoSubstrate=-1` is inside the digest of every LLM arm (its owner `LLM_RANDOM` is active there). Moving it to a feature absent at `-1` would drop it from `planValues` — or, stated explicitly, push it to `inert` (`applyInertRule`, `:306-332`) — and change the digest of every LLM arm with the mechanism off. E5b runs on `e93dea86` to choose the configuration E6 runs on the new jar, so "new jar at `-1`" must equal `e93dea86` in plan as well as in decisions. Keeping the owner achieves that with no code in `runtime/` at all, and the existing sub-parameter rule already gives the right validation: `-1` is inert on a plan without `LLM_RANDOM`, any other value there aborts with `missing_dependency`. The value is still stated in `RUN_START.params` on every LLM arm. The cost: opaque routing needs `ape.llmPercentage > 0`, which every LLM arm has.

**D4 — The coin stays behind the gate; a zero rate draws nothing.** The earlier idea of drawing before the gate would change the draw sequence of every LLM arm even with the feature off. Keeping `gate ∧ rate > 0 ∧ nextDouble() < rate` preserves the pre-change sequence when off (the gate is unchanged and `rate` is the positive assembly rate), and makes `0` mean "no random routing on opaque steps" without consuming draws there.

**D5 — The predicate requires a non-empty action list.** `CoordinateMapper.map` returns null when `actions` is empty (`CoordinateMapper.java:136`), before the tap path, so an LLM call on such a state is discarded by construction. `State` always constructs `MODEL_BACK` (`State.java:63`), so the case should not arise, but the predicate states it rather than relying on that.

**D5b — Boundary bands are not relaxed on opaque steps (declared limitation).** `CoordinateMapper.map` rejects `pixelY < 0.05h` or `> 0.94h` (`CoordinateMapper.java:130-134`, keys `ape.llmBoundaryTopPct`/`ape.llmBoundaryBottomPct`) before building a tap. Game HUD controls often sit in the bottom band, so correct answers there are lost. Alternatives: disable the bands on opaque steps (taps could hit the navigation bar and leave the app, and the top band is also what rejects the degenerate `(0,0)` emission, which would need its own check); per-opaque band keys (two more knobs before any measurement). Chosen: keep the bands and measure — each rejected answer is an `llm[]` sub-event with `reason:"boundary"` on a step marked `dec.opaque:1`, so E5c yields the rate directly. A campaign can already widen the bands for a whole run through the existing plan keys.

**D6 — Opaque steps are marked on every arm, in `dec`.** The fact describes the screen, so the arm without LLM needs it for the same stratification. It could ride the `STATE` dictionary entry at no per-step cost (a state's action set is fixed); the step record is chosen so an analyst joins the flag to `llm[]` and `out` within one line, as agreed when planning. The field is omitted when false, so its cost falls on opaque steps only.

**D7 — Restarts counted by the sink.** `ApeAgent.requestRestart()` is the single funnel for the three stability hooks (graph, state, activity). The sink counts calls, as it already counts `acts` and `states`, and writes `restarts` beside them in `RUN_END.counters`. `RunCounters` stays the LLM telemetry's snapshot and is not widened.

## API Design

### `static boolean LlmGate.isOpaque(State state)`

Pre: `state != null`. Post: `true` iff `state.getActions()` is non-empty and no element has `requireTarget()`. Pure, no allocation beyond iteration; an empty list returns `false`.

### `static boolean LlmGate.allows(StepContext ctx, boolean opaqueEnabled)`

Post: `ctx.actionBufferSize() == 0 && (ctx.newState().getActions().size() > 2 || (opaqueEnabled && isOpaque(ctx.newState())))`. With `opaqueEnabled == false` the result equals the pre-change expression for every input. The one-argument overload is removed (P3); the three stages pass their injected flag.

### Stage constructors

- `LlmNewStateStage(LlmEngine, BooleanSupplier, Consumer<ModelAction>, boolean opaqueEnabled)`
- `LlmStagnationStage(LlmEngine, BooleanSupplier, int restartThreshold, Consumer<ModelAction>, boolean opaqueEnabled)`
- `LlmRandomStage(LlmEngine, BooleanSupplier, double percentage, double opaqueRate, Random, Consumer<ModelAction>)` — `opaqueRate < 0` means the feature is absent; the stage derives `opaqueEnabled = opaqueRate >= 0`.

`LlmRandomStage.decide`:

```text
if !LlmGate.allows(ctx, opaqueEnabled): Continue
rate = (opaqueEnabled && LlmGate.isOpaque(ctx.newState())) ? opaqueRate : percentage
if rate <= 0 || random.nextDouble() >= rate || !breakerAllows: Continue
… unchanged
```

With the feature absent, `rate == percentage > 0` on every step that passes the gate, so the conjunct order and draws are the pre-change ones.

### Plan (no change)

`Feature`, `KeyOwnership` and `RunSpec` are untouched. `DecisionPipeline.fromSpec` computes `double opaqueRate = spec.has(Feature.LLM_RANDOM) ? spec.llm().dbl("ape.llmPercentageNoSubstrate") : -1` and `boolean opaqueEnabled = opaqueRate >= 0`.

### `EventSink.beginStep(int step, long tRelMs, String activity, boolean activityHasMop, String stateKey, boolean opaque)` and `EventSink.restartRequested()`

`NoopSink` implements both as no-ops. `NdjsonSink` stores `opaque` in the pending record (written as `dec.opaque:1` when true) and increments a restart count written in `runEnd`.

## Data Flow

1. `Monkey.run` resolves the plan exactly as before; the key is an `LLM_RANDOM` sub-parameter.
2. `DecisionPipeline.fromSpec` derives opaque routing from `spec.has(LLM_RANDOM)` and the key's value, and passes the flag to the three stage constructors and the rate to `LlmRandom`.
3. Each step, `StatefulAgent.resolveNewAction` opens the record with `LlmGate.isOpaque(newState)`, then the pipeline runs. On an opaque step with the feature on, an LLM stage may call the engine; a `click` answer becomes an `LlmTapAction`, accepted and resolved through the existing `LlmGate.accept` path.
4. The tap is dispatched; the next step's graph update records an ephemeral edge (a `NEW_ACTION` edge the first time a coordinate is tapped, resetting `graphStableCounter`) and feeds `recordLlmOutcome` with `new_state=false` (the abstract state does not change on a canvas).
5. When a stability hook calls `requestRestart()`, the sink counts it; `RUN_END` writes the total.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `>= 0` on a plan without `LLM_RANDOM` (no LLM, or `llmPercentage=0`) | `RunSpec.resolve` (existing sub-parameter rule) | abort `missing_dependency` naming `LLM_RANDOM` | harness sets `llm_percentage > 0` or the key to `-1` |
| Answer in a boundary band on an opaque step | `CoordinateMapper.map` | unchanged: `no_match`/`boundary`, stage returns `Continue` | none; counted for the follow-up (D5b) |
| Engine null on an opaque step (screenshot, transport, parse, `type_text`) | `LlmEngine` | unchanged: stage returns `Continue`, SATA decides | none needed |
| Sink failure writing `opaque`/`restarts` | `NdjsonSink` | unchanged latch: first `Throwable` disables the sink, run continues | none |

## Risks / Trade-offs

- [Splash and loading screens are opaque] → LLM calls there are bounded by the rate and by one new-state call per state; `dec.opaque` lets the analysis separate them. Accepted per D1.
- [Taps at new coordinates reset `graphStableCounter`, delaying SATA's forced restart on canvases where the LLM makes no progress] → Not changed (non-goal); `counters.restarts` measures it per arm. If E6 shows the LLM arm restarting far less on games, a follow-up can treat ephemeral edges on opaque states as `EXISTING` for the counter.
- [Every tap on a canvas counts as unproductive (abstract state unchanged)] → the dead-pair ban only bans an exact coordinate after 5 strikes, so varied coordinates are unaffected; repeated exact coordinates are banned, which is the intended behavior against the known `x∈{499,500}` collapse.
- [Throughput falls on opaque screens: an LLM call costs seconds against the 200 ms throttle] → this is the treatment's cost, not a defect; step counts are not comparable between on and off, and the analysis relies on host-side coverage and violations.
- [Game controls in the boundary bands are unreachable] → declared (D5b); countable per run from `reason:"boundary"` on `dec.opaque` steps.
- [The mechanism is not in `RUN_START.features`] → its value is in `RUN_START.params` on every LLM arm, and the digest separates `-1` from any other value; a reader derives "opaque routing on" from `LLM_RANDOM` ∈ features ∧ value `>= 0`.
- [Mixing jars across a campaign] → E5 stays on `e93dea86`; at `-1` the new jar's plan digest equals `e93dea86`'s (INV-RTR-25), and any other value changes it, so a trace states which regime produced it.
- [rv-android spec row stale (`aperv/spec.md:760`)] → follow-up in that repository; no behavior depends on it.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit | `LlmGate.isOpaque`/`allows` truth table (buffer × size × opaque × flag) | fake `State`/`StepContext` with typed actions | ~8 |
| Unit | `LlmRandomStage` rate choice, zero-rate no-draw, off-neutral draw sequence | counting `Random` over a mixed opaque/non-opaque fixture sequence; compare draw count and positions with the pre-change rule | ~5 |
| Unit | `LlmNewStateStage`/`LlmStagnationStage` on/off on an opaque state | stub engine | ~4 |
| Unit | digest/features/params of `llm` and `llm_mop` plans with `-1` equal to the `e93dea86` golden; existing sub-parameter abort unchanged | `RunSpecResolveTest`, `RunSpecAbortTest` | ~3 |
| Unit | boundary reject on an opaque step | `CoordinateMapperOffTreeTapTest` | ~1 |
| Unit | `dec.opaque` present/absent, `restarts` written including zero | `NdjsonSink` round-trip through `org.json` | ~4 |
| Integration | Sink neutrality with the new field; parity goldens for every preset unchanged | existing `SinkNeutralityTest`, parity oracle | existing |
| Device | retrowars, `llm` preset, same seed: `-1` vs `0.7` — `llm.calls`, `llm_tap`, `restarts`, `dec.opaque` share, host-side coverage and violations | `scripts/run_emulator.sh` + standalone run, or one rv-platform task per value | 2 runs |

## Open Questions

- Should `LlmStagnation` count as its episode trigger the `graphStableCounter` value that taps keep resetting on a canvas? Left as is; revisit with the `restarts` data from the device runs.
- Is the E6 opaque rate equal to `llmPercentage` (same treatment intensity on every screen) or higher (the LLM is the only agent that can act there)? Campaign decision, outside this change.
