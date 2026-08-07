# Design: rearch-03-decision-pipeline

## Context

Source of record: `docs/analise_fable-selecao.md` (rev. 3) — Sec. 6.3 (the `DecisionPipeline` concept), Sec. 5.3 and Sec. 12 Q1 (hard precedence, weighted sampling rejected), Sec. 2 R4/R7/R8 (simplicity, telemetry-neutrality, preemption semantics preserved), verified claims V1–V6/V10, findings 3.3-1 and 3.3-3, architectural tests Sec. 9.4/9.5/9.9. This is **stage 3 of 7** (report Sec. 10); it depends on `rearch-02-runspec` (the `RunSpec`/`RunContext`/`Feature` machinery exists and the `/sdcard` readers, silent agent fallback, and legacy persistence are gone) and is gated by the `rearch-01-parity-oracle` per-preset goldens.

Current state, re-verified against HEAD `5dcf225` (file:line):

1. **The precedence ladder is textual** (V1): `SataAgent.selectNewActionNonnull()` is `SataAgent.java:449-589` (141 LOC). Block order: logging `:450-462` → budget gate `:468-477` (**returns** the trivial action at `:474` when one exists — the "advisory only, no return" reading is wrong, report Sec. 3.2; only the null-trivial path falls through) → LLM new-state `:480-487` → LLM stagnation `:493-506` → LLM random `:508-515` → MOP census launcher `:522-545` (`_stepsSinceLauncherFiring++` at `:522`, then `shouldFireLauncher` with **6 args at the call site `:523-525`, 3 of them read from `Config`**) → component trigger `:547-551` (**side effect without return** — `triggerMopComponent()` then fall-through) → the SATA chain `:552-587` with the `resolved = …; if (resolved != null) { logActionSelected(…); return resolved; }` pattern **copied 7×** (buffer `:553`, back-to-activity `:558`, early-stage forward `:563`, trivial-activity `:568`, early-stage backward `:573`, epsilon-greedy `:578`, null-handler `:583`) → `throw new BadStateException` `:588`.
2. **The LLM precondition is triplicated** (V2): `actionBufferSize() == 0 && newState.getActions().size() > 2 && _llmRouter != null` verbatim at `SataAgent.java:480-481`, `:493-494`, `:508-509`.
3. **The "when to use LLM" decision spans two classes** (V3): trigger predicates in `LlmRouter.java:232-237` (`shouldRouteNewState`), `:249-255` (`shouldRouteStagnation`, delegating to the pure `stagnationMidpointReached` `:267-270`, which uses `>=` plus a fired flag), `:276-281` (`shouldRouteRandom`), `:292-302` (`breakerAllows()` with the open-episode log latch); ordering and preconditions in `SataAgent.java:480-515`.
4. **Stagnation episode state is scattered** (V5): the single-shot flag `stagnationHookFired` is declared in `StatefulAgent.java:128`, re-armed in `StatefulAgent.onVisitStateTransition` `:1436` (NEW_ACTION/NEW_ACTION_TARGET edge), and burned in `SataAgent.java:499` (with the counter reset on success at `:503`). The counter it watches, `graphStableCounter` (`StatefulAgent.java:143`), is **shared** state: it also drives the forced-restart mechanism (`checkStable` `:1049-1055` → `onGraphStable` `:1079-1083` → `requestRestart`).
5. **Launcher episode state lives in `SataAgent` fields**: `_triggerRoundRobinIndex` `:756`, `_activityTriggerLaunchCount` `:762`, `_stepsSinceLauncherFiring` `:768`. Finding 3.3-1: because the three LLM blocks textually precede `:522`, **a step preempted by the LLM never increments `_stepsSinceLauncherFiring`** — the launcher cadence freezes under LLM preemption. This is currently a textual accident; the rearch-01 preemption golden captures it as current behavior, and this change makes it a decided, tested property.
6. **`LlmRouter.selectAction` is a monolith** (V6): `LlmRouter.java:327-612` (286 LOC) — device-dimension probing `:337-353`, screenshot `:362-385`, image encoding `:388-395`, prompt build + prompt logging `:398-412`, HTTP call with per-request tool schema `:417-434`, ACK latch `:439-443`, response logging `:446-448`, tool-call parse `:451-462`, coordinate normalization `:465-466`, mapping `:469-471`, dead-pair ban check `:478-482`, nearest-widget computation `:484-506`, breaker/latency/token accounting `:509-513`, outcome classification incl. `fixTextEdit` text injection `:515-558`, `[APE-LLM-TEL]` emission `:560-597`, catch-all `:601-605`, memory cleanup `:606-611`. Plus, outside `selectAction`: mapping helpers (`mapToModelAction`, `fixTextEdit`), ban record (`banKey`/`isDeadPair`/`recordLlmOutcome`), `printSummary`, the tools-schema builders, and the `[APE-LLM-CONFIG]` manifest in the constructor `:148-160`. The class totals 996 LOC.
7. **`ScoringPipeline`'s config parameter is decorative** (V10): `ScoringPipeline.fromConfig(Config cfg, ScoringContext ctx)` (`ScoringPipeline.java:51-61`) — the javadoc `:48-49` admits `cfg` is "present for signature fidelity"; the sole caller passes `null` (`StatefulAgent.java:198`); four of the passes read `Config` statics directly (`CoveragePass`/`FormCompletionPass`/`FrontierPass`/`WtgPass`, six reads) and `MopScorer` four more (`mopWeightDirect`/`mopWeightTransitive`/`mopWeightWtg`), while the other two weights — `mopWeightOpenMenu` and `MopFrontierPass`'s `mopFrontierWeight` — read the plan through the ambient `RunContext.current()` (`MopScorer:101`, `MopFrontierPass:43`), which is not a static `Config` read and is forbidden by INV-ARCH-11 all the same. Finding 3.3-3: the class javadoc says "**six passes**" (`:14`, `:43`) while `fromConfig` constructs **seven** (`:52-59`, `MopFrontierPass` included).
8. **Decision-source vocabulary today**: `ModelAction.DecisionSource` = `SATA, MOP, MopFrontier, Coverage, LLM, Fuzz, Menu, WTG, Component, Budget, Form` (`ModelAction.java:42-44`); `PickChannel` (`:57-65`). Emission: `[APE-STEP]` `StatefulAgent.java:1493-1506` (model branch) and `:1519-1526` (non-model branch, source derived by `nonModelDecisionSource` `:1142-1145` — `Component` for `EVENT_TRIGGER_ACTIVITY`, else `SATA`); `[APE-OUTCOME]` `:1028-1030` from the join buffer `:117-124`; the LLM dead-pair outcome feedback at `:1041` reads the buffered decision's source.

Constraints: **no behavior change of any preserved semantics** — the rearch-01 goldens per preset are the gate (report Sec. 9.9), and the preemption golden (Sec. 9.4) plus the LLM-fallback test (Sec. 9.5) become permanent tests. R4 (one maintainer, no frameworks), R7 (telemetry never decides), R8/Q1 (hard preemption, never weights). Java 11 — no sealed classes, no records. P1/P3/P4 apply.

Note on spec baselines: the open change `telemetry-proof-llm-efficacy` (50/51, pending device smoke) modifies several of the same main-spec requirements (stagnation `>=` semantics, `pick_channel`, per-request tool schema). The delta specs in this change are written against the **post-sync** text of those requirements (i.e., code truth at `5dcf225`), since that change archives before this one applies.

## Architecture

```text
StatefulAgent / SataAgent (per step)
  selectNewActionNonnull()
    └── DecisionPipeline.decide(StepContext)          ← the ladder, now data
          for stage in stages (fixed order):
            r = stage.decide(ctx)
            Select     → return (action, source)      ← hard preemption: later stages NOT evaluated
            SideEffect → record, continue
            Continue   → next stage

Stages (assembled ONCE from RunSpec; feature absent ⇒ stage absent):
  Budget → LlmNewState → LlmStagnation → LlmRandom → MopLauncher → ComponentTrigger → SataChain

LLM stages share:
  LlmGate (single precondition helper — kills the V2 triplication)
  LlmEngine (the surviving core of LlmRouter.selectAction, now thin sequencing)
    ├── ScreenshotStep    (ScreenshotCapture + ImageProcessor + device dims)
    ├── ApePromptBuilder  (exists, unchanged)
    ├── LlmClient         (SglangClient + LlmCircuitBreaker + breakerAllows gate)
    ├── ToolCallParser    (exists, unchanged — INV-LLM-10 repair path intact)
    ├── CoordinateMapper  (CoordinateNormalizer + mapToModelAction + snap/boundary
    │                      + fixTextEdit + dead-pair ban record/check)
    └── LlmTelemetry      (counters, [APE-LLM-TEL]/[APE-LLM-ERROR]/[APE-LLM-PROMPT]/
                           [APE-LLM-RESPONSE]/ACK latch, summary)

SataChain internally:
  7 rungs as an ordered rung table (supplier + SataEventType) — one loop, same order,
  same logging; ScoringPipeline stays its scoring sub-pipeline, invoked as today from
  StatefulAgent.adjustActionsByGUITree() before selection (INV-EXPL-11), now with REAL
  parameter injection from the RunSpec (ScoringParams), fixing V10.

RunContext owns: model, graph, seeded RNG, MopData, LLM units (incl. breaker), trackers,
the DecisionPipeline itself, and forwards transition events to stages' episode state.
```

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `StageResult` | Sum type: `select(action, decisionSource)` / `continueChain()` / `sideEffect(desc)` | stage decision | tagged value |
| `DecisionStage` | One named precedence rung: `name()`, `decide(StepContext)`; optional `onStateTransition(edge)` hook (default no-op) | `StepContext` | `StageResult` |
| `DecisionPipeline` | Ordered stage list; assembly from `RunSpec`; the decide loop; `[APE-ARCH] stages=[...]` echo | `RunSpec`, `RunContext` | selected `Action` |
| `StepContext` | Per-step view: `newState`, `newGUITree`, whether the state is new, action buffer size, `graphStableCounter` (read/reset), timestamp, `RunContext` accessors (RNG, MopData, graph, trackers) | agent state | — |
| `BudgetStage` | `:468-477` relocated; Select trivial or Continue | budget tracker, activity | `Select`/`Continue` |
| `LlmNewState/LlmStagnation/LlmRandom` | Trigger predicates (from `LlmRouter:232-281`) + episode state + `LlmEngine` call + accept (from `acceptLlmResult` `:425-432`) | `StepContext`, `LlmEngine` | `Select`/`Continue` |
| `MopLauncherStage` | `:522-545` relocated; owns cadence/budget/round-robin counters | `MopParams`, graph, MopData | `Select`/`Continue` |
| `ComponentTriggerStage` | `:547-551` relocated; coin + `triggerMopComponent()` | RNG, MopData | `SideEffect`/`Continue` |
| `SataChainStage` | `:552-588` relocated; rung table; terminal (never Continue) | `StepContext` | `Select` or `BadStateException` |
| `LlmEngine` | The 9-step capture→prompt→client→parse→map→telemetry sequence (from `selectAction` `:327-612`), never throws | tree, state, actions, mode, step | `ModelAction` or null |
| `LlmClient` | HTTP + breaker as one unit: `chat()`, `allows()` (single-consultation + open-latch line), trip counting | messages, tools | `ChatResponse`/null |
| `ScreenshotStep` | capture + resize/encode + device dimensions | device | base64/null |
| `CoordinateMapper` | normalize, boundary bands, containment/snap, `fixTextEdit`, dead-pair ban (record + check), nearest-widget calc | parsed action, actions | `ModelAction`/`LlmTapAction`/null + reason |
| `LlmTelemetry` | All LLM counters/latches and line emission; summary at teardown | events from engine | trace lines |
| `ScoringPipeline.fromParams` | Real injection: passes constructed from `ScoringParams` (RunSpec-derived), no static `Config` reads | `ScoringParams`, `ScoringContext` | pipeline |

## Mapping: Spec → Implementation → Test

| Requirement / Invariant | Implementation | Test |
|-------------|---------------|------|
| INV-DP-01 single assembly + echo | `DecisionPipeline.fromSpec` | JVM unit: assembly matrix per preset; `[APE-ARCH] stages=[...]` content |
| INV-DP-02 hard preemption | decide loop (first Select wins) | Permanent preemption golden (Sec. 9.4): synthetic state qualifying LLM+launcher+component+SATA |
| INV-DP-03 feature absent = stage absent | `fromSpec` gating on `RunSpec.features` | JVM unit: plan without LLM/MOP/budget assembles without those stages |
| INV-DP-04 `StageResult` totality | static factories, non-null contract | JVM unit on the sum type |
| INV-DP-05 SideEffect continues | decide loop | JVM unit: ComponentTrigger fires, SataChain still selects |
| INV-DP-06 terminal SataChain | rung loop + `BadStateException` | JVM unit: all rungs null ⇒ throw (same message) |
| INV-DP-07 episode-state ownership | fields moved per relocation table; `onStateTransition` hooks | JVM unit per stage: arm/burn/re-arm; launcher counters |
| INV-DP-08 (3.3-1) LLM preemption freezes launcher cadence | `MopLauncherStage.decide` increments only when evaluated | Permanent test: preempted step ⇒ counter unchanged; non-preempted ⇒ +1 |
| INV-DP-09 parity per preset | whole change | rearch-01 goldens re-run green per preset (gate) |
| INV-DP-10 RNG draw order preserved | predicates relocated verbatim | Golden equality (draw-sensitive presets: llm, mop with componentPercentage>0) |
| INV-DP-11 structural LLM fallback | LLM stages return Continue on null | Permanent fallback test (Sec. 9.5): decline/timeout/breaker ⇒ remainder decides, correct `decision_source` |
| INV-DP-12 no static Config at decide time | sweep (section f below) | JVM grep-guard test over the pipeline packages + review |
| scoring-pipeline real injection | `ScoringPipeline.fromParams`, parameterized passes, `MopScorer` weights injected | Existing pass tests migrated to injected params; assembly matrix |
| llm-infrastructure `LlmClient` | new class composing `SglangClient` + breaker | JVM unit: single `shouldAttempt` consultation, open-latch line once per episode |
| llm-routing decomposition | `LlmEngine` + units | Existing `LlmRouter` unit tests migrated per unit; repair-path tests (INV-LLM-10/INV-RTR-14) stay green |

## Goals / Non-Goals

**Goals:** make the verified precedence ladder an explicit, inspectable, per-plan-assembled pipeline; kill the V2 triplication; give every piece of episode state exactly one owner; dismantle the V6 monolith into JVM-testable units; make `ScoringPipeline` injection real (V10) and fix the 3.3-3 javadoc; pin finding 3.3-1 and the LLM fallback as permanent tests; complete the migration of decision-path parameters and per-run mutable state into `RunSpec`/`RunContext` — all with **zero observable behavior change** under the parity oracle.

**Non-Goals:**
- No change to what any stage decides — hard preemption order, budget-gate trivial return, side-effect trigger, the 7 SATA rungs, epsilon-greedy, boosts, bans, repair pipeline: all semantics frozen (Q1/R8).
- No weighted sampling, no precedence-as-data editable per arm (rejected, report Sec. 5.3 / Sec. 7).
- No async LLM, no EventBus, no IPC (R4).
- No telemetry format change — `[APE-STEP]`/`[APE-OUTCOME]`/`[APE-LLM-*]` lines are byte-compatible; the NDJSON sink is stage 4 (`rearch-04`). `StageResult`'s `decisionSource` only *positions* the datum for stage 4.
- No memory-retention changes (stage 6) and no static-artifact changes (stage 7).
- No Python/rv-android changes.

## Decisions

### D1 — `StageResult` encoding: tagged final class with static factories (not visitor, not subclass hierarchy)

Java 11 has no sealed interfaces. Three candidate encodings:

1. **Visitor** (`result.accept(new StageResultVisitor<T>{...})`): exhaustive by construction, but adds an interface, a generic parameter, and double dispatch for exactly **one** consumer (the `DecisionPipeline` loop). Ceremony without a second consumer — fails R4.
2. **Open subclass hierarchy** (`abstract StageResult` + 3 public subclasses, `instanceof` in the loop): extensible by anyone, which is exactly wrong — the variant set is closed research semantics.
3. **Single final class, private constructor, static factories, `Kind` enum tag + accessors** — chosen:

```java
public final class StageResult {
    public enum Kind { SELECT, CONTINUE, SIDE_EFFECT }
    public static StageResult select(Action action, String decisionSource) { ... } // both non-null
    public static StageResult continueChain() { return CONTINUE_INSTANCE; }        // singleton
    public static StageResult sideEffect(String description) { ... }
    public Kind kind();
    public Action action();          // throws IllegalStateException unless SELECT
    public String decisionSource();  // throws IllegalStateException unless SELECT
    public String description();     // throws IllegalStateException unless SIDE_EFFECT
}
```

The class is final, the constructor private, the variant set closed at compile time; the accessors throw on wrong-variant reads so misuse fails loudly in tests, not silently at analysis time. A `switch` on `Kind` in one place (the pipeline loop) is the entire consumption surface. This is the smallest encoding that kills the overloaded `null` (the old "null means try the next block *and also* means LLM declined *and also* means no trivial activity") — one maintainer can hold it in his head.

`decisionSource` on `select(...)` is a `String` label (the `ModelAction.DecisionSource` name, or the derived non-model source). For `ModelAction`s the invariant is that the label equals `action.getDecisionSource().name()` — the stage stamps the action's provenance exactly as the current pick sites do (parity for `[APE-STEP]`/`[APE-OUTCOME]`) and mirrors it into the result for the stage-4 telemetry consumer. No second vocabulary is invented.

### D2 — `DecisionStage` interface with a default transition hook

```java
public interface DecisionStage {
    String name();
    StageResult decide(StepContext ctx);
    default void onStateTransition(StateTransition edge) { } // episode-state reset channel
}
```

The hook exists because episode state needs a reset channel that today lives in `StatefulAgent.onVisitStateTransition` (`:1426-1452`). The agent (via `RunContext`) forwards each visited transition to every stage once, immediately after its own counter bookkeeping; a stage without episode state inherits the no-op. Alternative considered: a separate `EpisodeState` registry object on `RunContext` — rejected as a second concept for the same information (R4); the stage *is* the owner, so the stage gets the event.

### D3 — Stage roster, order, and feature gating (assembly from the plan)

Order is **code, not data** (report Sec. 7: precedence-as-data rejected). `DecisionPipeline.fromSpec(RunSpec, RunContext)` appends stages in the fixed order below, skipping any whose gating feature is absent from the plan; assembly happens once, logs one `[APE-ARCH] stages=[...]` line (mirror of the scoring `passes=[...]` line, satisfying R6 "stage order echoed").

| # | Stage | Present when (plan) | Source block | Result forms |
|---|-------|--------------------|--------------|--------------|
| 1 | `Budget` | ACTIVITY_BUDGET feature | `:468-477` | Select(trivial, `Budget`) / Continue |
| 2 | `LlmNewState` | LLM feature ∧ `llm.onNewState` | `:480-487` | Select(action, `LLM`) / Continue |
| 3 | `LlmStagnation` | LLM feature ∧ `llm.onStagnation` | `:493-506` | Select(action, `LLM`) / Continue |
| 4 | `LlmRandom` | LLM feature ∧ `llm.percentage > 0` | `:508-515` | Select(action, `LLM`) / Continue |
| 5 | `MopLauncher` | ACTIVITY_TRIGGER feature (requires MOP) | `:522-545` | Select(trigger, `Component`) / Continue |
| 6 | `ComponentTrigger` | COMPONENT_TRIGGER feature (`componentPercentage > 0`, requires MOP) | `:547-551` | SideEffect / Continue |
| 7 | `SataChain` | always | `:552-588` | Select(action, per-rung source) — terminal |

Gating equivalences with today's guards (parity argument):

- The `_llmRouter != null` conjunct of the triplicated precondition dissolves structurally — the LLM stages exist only when the LLM feature does. The **remaining** precondition (`actionBufferSize() == 0 && newState.getActions().size() > 2`) moves into one helper (`LlmGate`) consulted by all three LLM stages — same evaluation order, same short-circuit.
- `Config.llmOnNewState` / `llmOnStagnation` / `llmPercentage > 0` today gate inside the predicates; as assembly conditions they are evaluated once. Behaviorally identical because these values are run-constant. `llmPercentage > 0` moving from `shouldRouteRandom`'s first conjunct (`:277`) to assembly is draw-neutral: when false, no draw was consumed anyway (short-circuit `&&`).
- `Config.activityBudgetEnabled` gates the budget block today (`:468`); absent feature ⇒ absent stage — the generalized INV-ARCH-03 ("feature absent = stage absent", report Sec. 8).
- The launcher's `enabled && hasMopData` conjuncts of `shouldFireLauncher` (`:781`) become assembly conditions. Nuance: today `_stepsSinceLauncherFiring++` runs even when MopData is null or the trigger is disabled (`:522` precedes the guard) — but in those runs the counter is **unobservable** (it can never cause a firing, is not logged, and dies with the process), so moving the increment inside a stage that then does not exist is parity-safe. The `stepsSinceFiring == cadence` equality, budget cap, candidate walk, and round-robin semantics are unchanged (the pure helpers `shouldFireLauncher` and `selectTriggerCandidate` move with the stage, now fed injected params instead of the 3 `Config` reads at `:523-525`).
- The component trigger's guard `componentPercentage > 0 && getMopData() != null && getMopData().hasComponents()` (`:547-548`): the first two conjuncts become assembly conditions; `hasComponents()` stays a per-stage check (it is data-dependent, not plan-dependent). The coin draw (`getRandom().nextDouble()`) is consumed at exactly the same point in the stream as today — after the data guard, only when the stage is reached (INV-DP-10).

### D4 — RNG draw-order preservation is a first-class parity constraint (INV-DP-10)

Three sites in the ladder consume the seeded stream: `shouldRouteRandom`'s coin (`LlmRouter:278`), the component-trigger coin (`SataAgent:548`), and the SATA rungs (roulettes/epsilon). The stages relocate these predicates **verbatim** — same conjunct order, same short-circuits, same conditional draws — so a given seed produces the identical draw sequence. This is what makes the goldens for the `llm` and component-trigger-bearing presets reproducible; it is asserted by the oracle, not just by review.

### D5 — Episode-state relocation table (design item c)

| State (today) | Declared / mutated at | Moves to | Reset / lifecycle after the move |
|---|---|---|---|
| `stagnationHookFired` | `StatefulAgent.java:128`; burned `SataAgent:499`; re-armed `StatefulAgent:1436` | **`LlmStagnation` stage** (private field) | Burned inside `decide()` when the trigger fires (whatever the LLM answers — the episode's single shot is spent, current comment `:496-498` preserved); re-armed by `onStateTransition(edge)` on NEW_ACTION/NEW_ACTION_TARGET (same event that today resets it at `:1436`) |
| `graphStableCounter` | `StatefulAgent.java:143`; `++`/reset `:1429-1441`; reset by stagnation escape `SataAgent:503` | **stays with the agent's exploration state (RunContext-owned)** — NOT stage-owned | Shared by design: the forced-restart mechanism (`onGraphStable` `:1079` → `requestRestart`) consumes it independently of the LLM. `LlmStagnation` reads it and resets it on an accepted escape through `StepContext` (`resetGraphStableCounter()`), preserving `:503` exactly. Making it stage-owned would give the restart mechanism a cross-stage read — worse coupling than the current shape |
| `_stepsSinceLauncherFiring` | `SataAgent.java:768`; `++` `:522`; reset `:529` | **`MopLauncher` stage** | Incremented at stage entry (`decide()` start), reset at each firing point — which realizes 3.3-1 by construction (D6) |
| `_triggerRoundRobinIndex` | `SataAgent.java:756`; `++` `:537` | **`MopLauncher` stage** | Incremented per firing, persisted across firings (INV-CT-06 wording unchanged) |
| `_activityTriggerLaunchCount` | `SataAgent.java:762`; `++` `:539` | **`MopLauncher` stage** | Incremented only on an actual launch (INV-CT-12 unchanged) |
| Component round-robin cursor (in `StatefulAgent.triggerMopComponent` `:1259` and its tuple index field) | `StatefulAgent` | **`ComponentTrigger` stage** | Same round-robin walk; the trigger dispatch helpers (`dispatchTrigger`, catalog) stay where they are — the stage calls them |
| LLM telemetry counters, ACK latch, breaker-open latch (`LlmRouter:68-112`) | `LlmRouter` fields | **`LlmTelemetry`** (counters, ACK) and **`LlmClient`** (breaker-open latch, trips) | Per-run, die with the process (R1) — unchanged lifecycle, new owner |
| `deadPairStrikes` map (`LlmRouter:106`) + outcome feedback (`recordLlmOutcome` `:~880`) | `LlmRouter` | **`CoordinateMapper`** (the ban is a mapping refusal — check site and record together) | Fed from the `[APE-OUTCOME]` join-buffer site in `StatefulAgent` (`:1041`) exactly as today, through `RunContext`'s LLM units |
| Join buffer `lastDecisionStep`/`lastDecisionAction` (`StatefulAgent:117-124`) | `StatefulAgent` | **stays** | It is outcome-attribution state, not decision state; it becomes the `StepRecord` accumulator in stage 4 — moving it now would be churn |

### D6 — Finding 3.3-1: LLM preemption does not advance the launcher cadence — PRESERVED, by construction, pinned by test

Decision (explicit, as the report requires): the current behavior is **kept**. Under hard preemption, `MopLauncherStage.decide()` simply never runs on a step where an earlier stage Selected; since the cadence counter increments at stage entry, a preempted step does not advance it — structurally identical to today's `:522` never being reached. No compensation, no catch-up. Rationale: (i) parity is the gate — compensating would change firing points in every LLM arm and invalidate the goldens; (ii) the semantics are defensible on their own terms — the cadence counts *SATA-eligible selection passes*, and an LLM-decided step is not one. The permanent test (promoted from the rearch-01 golden): a synthetic step where an LLM stage Selects leaves the launcher counter unchanged; the next non-preempted step increments it by exactly 1.

### D7 — `LlmRouter` decomposition map (design item d)

The 19 responsibilities of V6 map to five units plus two survivors. `LlmRouter` **dies** (P3 — no shim, no delegating facade).

| Responsibility (V6, at `LlmRouter.java`) | Goes to |
|---|---|
| Trigger predicates `shouldRouteNewState/Stagnation/Random` `:232-281` | The three LLM **stages** (each owns its trigger; `stagnationMidpointReached` moves with `LlmStagnation` as its pure seam) |
| `breakerAllows()` single-consultation + open-episode latch `:292-302` | **`LlmClient.allows()`** (breaker and its consultation discipline are one unit) |
| Constructor wiring + `[APE-LLM-CONFIG]` manifest `:123-161` | **`LlmClient`** (transport params + manifest — the manifest reports what the client sends); units constructed once from `RunSpec.llm` into `RunContext` |
| Tools schema constants + builders `:136-137, :169-221` | **`LlmClient`** (wire schema is transport); the per-request selection predicate (`hasInputField`) stays with the engine call site — prompt/wire coherence preserved (INV-LLM-11) |
| Device-dimension probing `:337-353` | **`ScreenshotStep`** |
| Screenshot capture + failure attribution `:362-385` | **`ScreenshotStep`** (capture) + **`LlmTelemetry`** (error line/counter) |
| Image processing `:388-395` | **`ScreenshotStep`** |
| Prompt build `:398` | **`ApePromptBuilder`** (exists, unchanged) |
| Prompt/response logging `:400-412, :446-448` | **`LlmTelemetry`** |
| HTTP call + failure-cause read `:417-434` | **`LlmClient`** (call + `getLastErrorCause()` seam discipline, INV-LLM-08) + **`LlmTelemetry`** (cause counters/lines) |
| ACK latch `:439-443` | **`LlmTelemetry`** |
| Tool-call parse `:451-462` | **`ToolCallParser`** (exists, unchanged; native `tool_calls` malformations keep running the same repair pipeline — `SglangClient.ToolCall.rawArguments` → Level 1 → shared `parseJsonString`, surfacing as `repair=` — INV-LLM-10, INV-RTR-14) |
| Coordinate normalization `:465-466` | **`CoordinateMapper`** (wraps `CoordinateNormalizer`) |
| `mapToModelAction` (containment, snap tolerance, boundary bands, back/long-click preference, `fixTextEdit`) | **`CoordinateMapper`** |
| Dead-pair ban check `:478-482` + record `banKey`/`isDeadPair`/`recordLlmOutcome` | **`CoordinateMapper`** |
| Nearest-widget telemetry calc `:484-506` | **`CoordinateMapper`** (geometry), reported through **`LlmTelemetry`** |
| Breaker outcome recording, token/latency accounting `:509-513` | **`LlmClient`** (breaker) / **`LlmTelemetry`** (accounting) |
| Outcome classification + `[APE-LLM-TEL]` emission `:515-597` | **`LlmEngine`** (classification) + **`LlmTelemetry`** (emission) |
| Catch-all + memory cleanup `:601-611` | **`LlmEngine`** (never-throws contract, INV-RTR-02, and the finally-nulling, INV-RTR-06) |
| `printSummary` + counter getters | **`LlmTelemetry`** |

`LlmEngine` is deliberately thin: the 9-step sequence, mode label, and outcome classification — target well under 100 LOC, every branch delegating to a unit testable in pure JVM.

### D8 — `ScoringPipeline` real injection (V10) and the 3.3-3 javadoc fix

`ScoringPipeline.fromConfig(Config cfg, ScoringContext ctx)` is **replaced** (P3) by `ScoringPipeline.fromParams(ScoringParams params, ScoringContext ctx)`, where `ScoringParams` is the RunSpec-derived value object carrying every weight/gate the passes read today (`coverageBoostWeight`, `formCompletionEnabled`, `frontierBoostWeight`, `mopFrontierWeight`, `mopWeightWtg`, and the `MopScorer` weights `mopWeightDirect`/`mopWeightTransitive`/`mopWeightOpenMenu`). Passes take their parameters by constructor; `MopScorer`'s static reads become parameters supplied by the calling pass. `isEnabled()` decisions are unchanged in logic, now computed from injected values. The sole caller (`StatefulAgent:198`) passes the real params from the `RunSpec` instead of `null`. The class javadoc drops "six passes" for the actual seven-pass roster (`MopWidget → MenuGateway → WTG → Frontier → MopFrontier → Coverage → FormCompletion`) — INV-ARCH-03's fixed order and the `[APE-ARCH] passes=[...]` line are untouched. Assembly point, pass order, boost arithmetic, and provenance writes are byte-identical.

**What checks that, precisely.** The pass unit tests check assembly and arithmetic *given* params; they cannot see a wrong param, because they supply their own. The `rearch-01` goldens cannot see it either: they enter at `selectNewActionNonnull()`, and `adjustActionsByGUITree()` — where the pipeline runs — executes above that entry point in `resolveNewAction()` (`StatefulAgent.java:1537-1538`), so no golden record ever depends on a scoring weight. The `RUN_START` echo prints the resolved weights but is write-only by owner decision D1 (`run-spec` INV-RUN-03), so nothing compares it to anything. And INV-ARCH-11 forbids the test shape that would otherwise catch drift ("Tests SHALL construct `ScoringParams` directly, never mutate `Config`") — correctly, but it means the *mapping* from plan to params is what needs its own guard.

That guard is `ScoringParamsDefaultsTest` (task 5.1a): assert that `ScoringParams` derived from a default plan carries the eight jar-default values, field by field, with the literals spelled out in the test. It is the only thing in the whole rearch set that fails when a scoring default changes silently — the `rearch-01` per-preset `Config` guard is scoped to "the jar-default values **the ladder reads**", which excludes every scoring weight.

### D9 — Static-`Config`-read sweep (design item f)

Grep census at HEAD over the decision path, and each read's destination. The invariant delivered by this change (INV-DP-12): **no code in the decision pipeline (stages, engine, LLM units, scoring passes, `MopScorer`) reads `Config` at decision time** — every behavioral parameter arrives via `RunSpec` injection at assembly.

| Class | Reads (count) | Destination |
|---|---|---|
| `SataAgent` (**16 reads over 11 keys**, re-measured at `1f9fe5e7`) | qualified: `backMenuPickCap`(`:521`,`:785`,`:801`,`:810`,`:1518`), `mopTargetPickCap`(`:832`), `dynamicEpsilon`(`:1142`), `minEpsilon`(2)/`maxEpsilon`(`:1150`); static-imported: `defaultEpsilon`(`:219`), `fillTransitionsByHistory`/`fallbackToGraphTransition`(`:859`), `useActionDiffer`(`:948`), `trivialActivityRankThreshold`(`:1165`), `doBackToTrivialActivity`(`:1308`) | An `ExplorationParams` field assigned in the constructor — assembly, the same moment `decisionPipeline` and `scoringPipeline` are built — and read as a field at each decision site. `mopTargetPickCap` comes from `MopParams` and is zero (cap disabled) when the MOP feature is absent, which is exactly when no MOP target pick exists to cap. `defaultEpsilon` is read by the delegating constructor `SataAgent(ape, graph)` before any field exists, so it stays an assembly read straight off the plan. The launcher pair, `componentPercentage`, `llmPercentage`, `activityBudgetEnabled` and `graphStableRestartThreshold` are **already gone** — groups 1–4 moved them into the stages, which is why this row is smaller than the one it replaces |
| `LlmRouter` (23) | `llmUrl/Model/Temperature/TopP/TopK/MaxTokens/TimeoutMs`, `llmOnNewState`(2), `llmOnStagnation`(2), `llmPercentage`(4), `graphStableRestartThreshold`(2), `llmSnapTolerancePx`, `llmBoundaryTopPct/BottomPct` | `LlmParams` → `LlmClient` ctor (transport), stage assembly (mode gates), `LlmStagnation` (threshold), `CoordinateMapper` ctor (snap/boundary) |
| Scoring passes (6 static + 1 ambient) | `coverageBoostWeight`(2), `frontierBoostWeight`(2), `formCompletionEnabled`, `mopWeightWtg`; plus `MopFrontierPass`'s `mopFrontierWeight` through `RunContext.current()` (`:43`), which a `Config` grep does not find | `ScoringParams` via pass constructors (D8) |
| `MopScorer` (4 static + 1 ambient) | `mopWeightDirect`(`:45`), `mopWeightTransitive`(`:48`), `mopWeightWtg`(`:111`,`:120`); plus `mopWeightOpenMenu` through `RunContext.current()` (`:101`) | parameters supplied by `MopWidgetPass`/`MenuGatewayPass`/`WtgPass` from `ScoringParams` (D8) |
| `StatefulAgent` (**26 reads over 20 keys**, re-measured at `1f9fe5e7`) | qualified: `mopDataPath`(`:178`,`:179`), `activityBudgetEnabled`(`:183`), `activityBaseBudget`/`activityBudgetPerWidget`(`:184`), `maxIdleTimeoutMs`(`:687`,`:759`), `stepTelemetryEnabled`(`:1082`,`:1553`,`:1582`); static-imported: `fuzzingActivityVisitThreshold`(`:455`), `evolveModel`(`:843`,`:897`), `saveGUITreeToXmlEveryStep`(`:994`), `takeScreenshot`(`:1005`,`:1483`), `takeScreenshotForEveryStep`(`:1005`), `activityStableRestartThreshold`(`:1126`), `graphStableRestartThreshold`(`:1136`), `stateStableRestartThreshold`(`:1437`), `takeScreenshotForNewState`(`:1483`), `maxExtraPriorityAliasedActions`(`:1663`), `baseThrottle`(`:1719`), `throttleForUnvisitedAction`(`:1731`), `throttleForActivityTransition`(`:1735`), `maxThrottle`(`:1738`) | All of them, to fields assigned in the constructor. Decision-relevant (restart thresholds, budget params, `evolveModel`, throttles, `fuzzingActivityVisitThreshold`, `maxExtraPriorityAliasedActions`, `maxIdleTimeoutMs`) → `ExplorationParams`; `stepTelemetryEnabled` → `TelemetryParams`, false when the `STEP_TELEMETRY` feature is absent; the save/screenshot flags are base keys and travel with the exploration params. **`mopDataPath` was not already replaced by rearch-02** — both reads are live and load the MOP substrate at `:178`/`:179`; they become `MopParams.dataPath()`, which is null exactly when the MOP feature is absent, and `Feature.MOP` is derived from that key being present, so the equivalence is definitional rather than incidental. `llmUrl` really is gone |
| `State` (2) | `leastVisitedPriorityTiebreak`, `modelMenuEnabled` | `leastVisitedPriorityTiebreak`: passed as a parameter to `greedyPickLeastVisited` from the SataChain call site (small signature change, pure). `modelMenuEnabled`: **documented residue** — it gates which actions a `State` is built with, a plan-frozen *structural* property evaluated during model construction; threading plan state through `Model`/`State`/naming construction is orthogonal to the decision pipeline and stays behavior-identical (the field is load-frozen). Recorded here, revisited when stage 6/7 touch the model layer |
| `ApePromptBuilder` (1) | `llmPromptVariant` | `LlmParams` → builder construction (variant is already run-frozen) |

The 5 non-final "for tests" Config fields died in rearch-02; tests here construct `RunSpec`s / params objects directly.

**Why the first census was wrong, and why the same mistake is available to the guard.** `Config`'s
fields are `public static final`, so a read of one takes two forms in the source: qualified
(`Config.backMenuPickCap`) or bare, behind `import static
com.android.commands.monkey.ape.utils.Config.backMenuPickCap`. Eleven files in `src/main` import
that way, and in `StatefulAgent` the bare form is the **majority** — 16 of its 26 reads. A grep for
`Config\.` therefore reports a number that is neither the qualified count (it also matches the
`import static` lines, which are declarations, not reads) nor the real one. The rows above were
counted with both forms enumerated and the import lines excluded, which is why `SataAgent` falls
from a claimed 25 to 16 and `StatefulAgent` from a claimed ~30 to 26.

There is a third form the same grep cannot see at all: a parameter reached through the ambient
`RunContext.current()`, which group 5 found twice in the scoring path (`MopScorer:101`,
`MopFrontierPass:43`) sitting behind a perfectly clean `Config` grep. INV-DP-12 forbids that read
just as much — it is a plan read at decision time, only routed through a global instead of a static
field. Task 6.5's guard is written against all three forms for this reason.

### D10 — `decision_source` production per stage (design item g)

Vocabulary is **unchanged**: `SATA, MOP, MopFrontier, Coverage, LLM, Fuzz, Menu, WTG, Component, Budget, Form` (`ModelAction.java:42-44`), plus `PickChannel` unchanged. Production sites after the change:

| Stage | `decision_source` on Select | How produced |
|---|---|---|
| `Budget` | `Budget` | stamped on the action (as `:472` today) + mirrored into `StageResult` |
| `LlmNewState`/`LlmStagnation`/`LlmRandom` | `LLM` | the accept step (relocated `acceptLlmResult` `:425-432`: stamp source + `PickChannel.LLM` + synthesized-tap resolution guard) |
| `MopLauncher` | `Component` | non-model action; label derived exactly as `nonModelDecisionSource` (`StatefulAgent:1142-1145`) does for `EVENT_TRIGGER_ACTIVITY` |
| `ComponentTrigger` | — (SideEffect; no step decision) | n/a |
| `SataChain` | `SATA` or `attributeByLargestBoost` result (`MOP/MopFrontier/WTG/Menu/Form/Coverage`) | unchanged rung-site stamping (`SataAgent:237, :293-323, :614, :625, :657, :672, :684, …`) |

Emission is untouched in this change: `[APE-STEP]` (`StatefulAgent:1493-1506`, `:1519-1526`) and `[APE-OUTCOME]` (`:1028-1030`) keep reading the action's provenance. `StageResult.decisionSource` is the forward-looking handle stage 4's `StepRecord` will consume (`dec.src`), guaranteed consistent with the stamped provenance by INV-DP-04's equality clause.

### D11 — LLM fallback as declared plan data, realized structurally (design item e)

`RunSpec.llm` declares the fallback (rearch-02): the remainder of the pipeline **is** the fallback — `mop` base ⇒ MopLauncher/ComponentTrigger/SataChain-with-MOP-scoring; `aperv` base ⇒ SataChain alone. An LLM stage returns `Continue` on: precondition not met, trigger predicate false, breaker disallows, engine null (screenshot/image/HTTP/parse failure, no-match, dead-pair ban, boundary/degenerate reject). No stage ever converts a decline into a throw or a retry. The Sec. 9.5 permanent test scripts decline/timeout/breaker-open against a stubbed `LlmClient` and asserts (i) the selected action comes from the configured remainder and (ii) its `decision_source` is the remainder's, never `LLM`.

### D12 — SataChain internal cleanup: rung table

The 7× copied `resolved = …; if (resolved != null) { logActionSelected(…); return; }` pattern (`:552-587`) collapses inside the stage into an ordered rung list — `(supplier, SataEventType)` pairs walked by one loop that logs and returns on the first non-null. Same order (buffer → back-to-activity → early-forward → trivial → early-backward → epsilon-greedy → null-handler), same `SataEventType` labels, same `BadStateException` after the last rung. This is GLM's surviving idea confined to the stage interior (report Sec. 5.3) — a mechanical de-duplication with zero sampling-semantics change.

### D13 — What group 1 settled that D1–D3 left open (recorded 2026-08-04, after implementation)

D1 and D2 specify the types and not their home, and three further questions only arose once the
skeleton existed. Recorded here so groups 2, 6 and 7 inherit answers instead of re-deciding them.

**The package is `com.android.commands.monkey.ape.agent.pipeline`.** No artifact named one; this is
the parallel of the sibling `com.android.commands.monkey.ape.agent.scoring`, which this change's own
`scoring-pipeline` delta names explicitly. The stage classes of group 2 belong in the same package,
and it is the name task 6.5's grep-guard reads as "the pipeline package".

**`StepContext` is an interface, not a class.** D2 calls it "a thin view over the agent +
`RunContext`", and the members it needs are the agent's protected fields and methods — so the
production implementation is the agent (or its inner class) and nothing is copied. The same device is
already load-bearing one layer down in `ScoringContext`, and it is what lets a stage test in group 2
assert a predicate against a fake with no agent, device or live model.

**Assembly is split: the plan-to-roster mapping is data, stage construction is not.** A static
`Candidate` table carries the seven candidates in fixed order, each with the leaf feature that
assembles it, exposed as `DecisionPipeline.assembledCandidates(RunSpec)`. This is simultaneously the
static candidate census the ADDED assembly requirement demands for stage 4's `PIPELINE` record, and
it makes "which stages does this plan imply" a pure function of the plan — assertable per preset
(task 3.4) without a device or a live stage. Each gate names the **leaf** feature only
(`LLM_NEW_STATE`, not `LLM ∧ LLM_NEW_STATE`): `Feature` declares those dependencies and plan
resolution enforces them, so repeating the root would be a second guard for a fact the plan
guarantees. `fromSpec` adds only the construction step, over this table.

**`fromSpec`'s "never returns an empty pipeline" postcondition holds from task 2.1**, because every
interim roster ends in a terminal stage (see the extraction seam below). `decide()` still throws
`IllegalStateException` on an exhausted roster, which is the enforceable form of INV-DP-06, but no
roster the extraction produces is exhaustible. Group 1 landed everything in task 1.3 except
`fromSpec` itself — no stage class existed to construct — and left that box open by owner decision
rather than relocating its text; it closes with task 2.1.

**INV-DP-04's label equality is asserted, not enforced at the factory.** `StageResult.select` checks
both arguments non-null and stops there. Validating the label against `ModelAction`'s stamped
provenance inside the factory would put a throw in the decision path of a live run, where a
mislabelled stage would abort an experiment instead of failing a test; task 7.2's per-stage assertion
is the intended check, and the error table above lists no factory validation.

**INV-DP-05's "the pipeline SHALL record it" is a per-step accumulator, not a trace line.**
`DecisionPipeline.lastStepSideEffects()` holds what the side-effect stages did on the step being
decided, cleared at each `decide()`. Deliberately not a new `[APE-ARCH]` line: `triggerMopComponent()`
already logs its own dispatch, and a format invented here is one stage 4's `StepRecord` restructures
immediately.

**The transition fan-out lives on the pipeline.** `DecisionPipeline.onStateTransition(edge)` forwards
a visited edge to every assembled stage once, in roster order — the roster is the only thing that
knows the stages, so a per-stage wiring at the agent would have to duplicate it. Task 2.3 hooks the
agent's `onVisitStateTransition` to this method and task 7.1 owns the ordering guarantee (agent
counters first, then stages).

### D14 — What task 2.1 settled: how a stage reaches the agent (recorded 2026-08-04, after implementation)

D2 enumerates what a stage may *read* and stops there, which left the first extraction with a
question no artifact answered: `BudgetStage` needs `selectNewActionForTrivialActivity()` and
`SataChainStage` will need all seven rung methods, and all of them are `protected` on the agent and
none is in `StepContext`'s surface. Recorded here so groups 3–7 inherit the answer — task 6.5's
grep-guard and task 7.1's wiring both depend on where the stages get their collaborators.

**Stages reach the agent through `StageCollaborators`, not through `StepContext`.** The new interface
`com.android.commands.monkey.ape.agent.pipeline.StageCollaborators` names the agent behaviours
assembly binds into stages. The split is the point: `StepContext` is what the step *is* — the state,
the tree, the counters, the seeded stream, read live once per step by every stage — while
`StageCollaborators` is what the agent *does*, the action producers the ladder invoked as protected
methods. Putting the seven rung methods and the trivial-activity search onto `StepContext` is exactly
the god object this design's own risk row commits to preventing.

**Assembly binds; stages hold narrow function objects.** `fromSpec` reduces each collaborator to the
narrowest form its stage needs — a `Supplier<ModelAction>` for `BudgetStage`, the rung table's
(supplier, `SataEventType`) pairs of D12 for the chain — so no stage holds the whole collaborator
surface and a stage unit test supplies a lambda rather than an implementation. This is D12's own
wording taken literally, with `ScoringPass`/`ScoringContext` as the local precedent: collaborators
travel through a seam, behaviour lives in the unit.

**Why an interface rather than the agent class.** `fromSpec` is where INV-DP-01 (one assembly, one
echo) and INV-DP-03 (feature absent = stage absent) actually live. Named against `SataAgent`, those
two properties would only be assertable on a device; named against `StageCollaborators`, a fake makes
the roster a pure function of the plan — which is what `DecisionPipelineFromSpecTest` asserts, and
what task 3.4's per-preset matrix needs in order to assert the echo line rather than only the
candidate list.

**The signature is `DecisionPipeline.fromSpec(RunSpec spec, StageCollaborators collaborators)`; the
`RunContext` parameter is dropped.** D3 sketched assembly as reading the plan and the run context,
but gating is a question about the plan and construction is a question about the agent, and the
stages' run-scoped collaborators arrive per step through `StepContext` — so a context parameter here
is one nothing reads (P1). This paragraph closed by expecting task 7.1 to move the LLM units and the
pipeline itself onto `RunContext`; 7.1 settled it the other way for the pipeline, and **D15** records
why. "Single assembly point" is unaffected either way.

**The extraction seam: `InlineLadderStage`.** Until task 2.7 lands `SataChainStage`, `fromSpec`
assembles `InlineLadderStage` for the `SATA_CHAIN` candidate — a package-private stage delegating to
`StageCollaborators.decideInlineLadder()`, which is the part of the ladder the extraction has not
reached yet, still on `SataAgent` in its original order with its original predicates. Every interim
roster therefore satisfies INV-DP-06, which means `DecisionPipeline.decide` is the live decision path
of real runs and of every golden from task 2.1 onwards rather than only after the last extraction,
and each extraction task reads as one block moving out of that remainder into a stage in front of it.
That is what lets a red golden be attributed to a single move — the property this group's one-task-
one-gate rhythm exists to buy. Both `InlineLadderStage` and `decideInlineLadder()` are replaced by
`SataChainStage` at task 2.7.

**The production `StepContext` implementation is `StatefulAgent` itself**, not a field holding a view.
D13 allowed "the agent (or its inner class)"; the agent won on the harness. `OracleScaffold` allocates
its agent through `Unsafe`, so a field would have to be injected, and its production and harness
constructions could then drift — the way the duplicated `ScoringContext` already can.

**The oracle's injection profile adapted, which INV-ORA-07 permits.** `OracleScaffold.newAgent` also
injects the assembled `decisionPipeline`, built by the same `fromSpec` against the preset's installed
plan and the agent's own producers. No golden and no scenario script changed.

**The LLM half of that profile is larger, and task 4.6 is where it grew.** Once the trigger predicates
sit inside the stages, a script cannot reach a hook by substituting one collaborator: the hook's own
conjunct runs first and the script sits behind it. `OracleScaffold` therefore substitutes, per LLM
stage, the breaker gate — a `BooleanSupplier` the script answers per hook, which is where the verdict
became hook-aware — plus one shared scripted `LlmEngine` that only the hook whose gate answered can
reach, plus, on the probabilistic stage alone, the `Random` the coin draws from. That last one is the
substitution that would have moved four committed baselines had it been omitted: assembly hands the
stage the agent's own generator, the same one the epsilon-greedy rungs draw from, so one extra draw per
step would shift every later draw for a reason that is purely a harness artifact. Overriding the coin's
*verdict* would not have sufficed — a verdict-overridden coin still consumes a draw.

Still no golden and no scenario script changed. What did change is the harness's observation window:
"consulted" now means "the shared precondition passed **and** the stage's own condition held", a
sharper statement than the pre-decomposition one, which only meant the precondition passed. The
hook-order requirement the preemption golden exists for is unaffected — it is pinned on a step where
all three conjuncts hold and all three hooks are consulted in order.

### D15 — What task 7.1 settled: the pipeline stays with the agent, the LLM units are on the context (recorded 2026-08-04, after implementation)

7.1's earlier wording — "`RunContext` owns the assembled `DecisionPipeline` and the LLM units" — was
one claim that had already landed and one the code contradicts, and D14 closed by forward-referencing
the contradicted half as this task's work. Recorded here so nothing re-opens it.

**The LLM half landed at 4.1–4.6 and is unchanged.** `RunContext` builds `LlmClient`,
`CoordinateMapper`, `LlmTelemetry` and `LlmEngine` in its constructor and holds them as final fields,
all four null together on a plan with no LLM feature. That is what lets a stage hold the unit it uses
instead of reaching through the agent for it, and it is what INV-DP-03 means on the LLM side.

**The pipeline stays owned by `SataAgent`, and initialization order is why.** `fromSpec` binds the
agent's action producers as method references (D14), so the pipeline cannot exist before the agent
does; `RunContext` is established before any agent exists, which is precisely what makes
`RunContext.current().spec()` readable inside the agent's constructor. Moving ownership would take one
of two shapes and both cost more than they buy: a two-phase `installPipeline(...)` — a mutable field
with a null window, in the one class whose contract is that a run's context is established once and
never re-established — or an inverted initialization order, which would put the plan behind the agent
that reads it.

**And no consumer wants it there.** The pipeline's only production readers are the agent's `decide`
and `onStateTransition` call sites. Stage 4's `PIPELINE` record needs the stage census, which is
`DecisionPipeline.assembledCandidates(RunSpec)` plus `Candidate.values()` — a pure function of the
plan, needing no instance and no context, which is D13's "the plan-to-roster mapping is data" read
forward. `lastStepSideEffects()` has no production reader at all yet. What a
`RunContext.current().decisionPipeline()` accessor would add is one more ambient read of exactly the
kind INV-DP-12 forbids and task 6.5's guard bans inside `agent/pipeline/**`.

**What 7.1 is, therefore**: an audit of the LLM half, a wording fix here and in `fromSpec`'s javadoc,
and the one thing that was genuinely missing — the assertion that pins transition forwarding. The
parity oracle cannot stand in for it. `OracleScaffold` `Unsafe`-allocates its agent, so
`StatefulAgent`'s `graph.addListener(this)` never runs and the harness's agent is not a
`GraphListener` at all: a regression that stopped forwarding edges, or that forwarded them before the
agent's own counter bookkeeping, would leave every golden green and the gate at 14/14.

## API Design

### `DecisionPipeline.fromSpec(RunSpec spec, StageCollaborators collaborators) -> DecisionPipeline`

Preconditions: spec validated (rearch-02 fail-fast already ran); `collaborators` is the agent whose action producers the assembled stages invoke (D14). Postconditions: stage list fixed for the run, order as D3, one `[APE-ARCH] stages=[...]` line emitted. Never returns an empty pipeline (the terminal stage has no gate).

### `DecisionPipeline.decide(StepContext ctx) -> Action`

Walks stages in order; returns the first `SELECT`'s action; records `SIDE_EFFECT` descriptions (log line today, `StepRecord` in stage 4) and continues; on `CONTINUE` advances. The terminal stage cannot `CONTINUE` (INV-DP-06); `SataChainStage` throws `BadStateException` exactly where `:588` does today. Error behavior: nothing else throws — LLM/engine failures are `Continue` by contract.

### `StepContext`

Read surface: `newState()`, `newGUITree()`, `isNewState()`, `actionBufferSize()`, `graphStableCounter()`, `timestamp()`, `random()`, `mopData()`, `graph()`, `budgetTracker()`, `actionHistory()`. Write surface (minimal, explicit): `resetGraphStableCounter()` (LlmStagnation escape, `:503` parity). Implemented as a thin view over the agent + `RunContext` — no copying, no snapshotting.

`isNewState()` was added at task 2.2, through this gate rather than around it. It is the new-state hook's whole trigger argument (`_isNewState`, set once per step by the agent's state update), so it is per-step data and belongs here; routing it through `StageCollaborators` instead would have put a datum behind a behaviour seam to avoid touching the design, which is the move this enumeration exists to prevent.

### `LlmEngine.selectAction(GUITree, State, List<ModelAction>, String mode, int step) -> ModelAction | null`

Contract identical to today's `LlmRouter.selectAction` (`:327-612`): never throws (INV-RTR-02), nulls large temporaries in finally (INV-RTR-06), increments the attempt counter first (INV-RTR-07), emits the same telemetry lines with the same fields. MopData and action history arrive via the engine's constructor-injected `RunContext` accessors rather than per-call params where run-constant.

## Data Flow

Per step: `MonkeySourceApe` → `StatefulAgent.updateState…` (unchanged) → `SataAgent.selectNewActionNonnull()` → logging prologue (unchanged, `:450-462` stays in the method) → `DecisionPipeline.decide(stepContext)` → selected `Action` → `resolveNewAction()`/`[APE-STEP]` emission (unchanged) → execution → next step's `updateGraph` records the transition → `[APE-OUTCOME]` from the join buffer + LLM dead-pair feedback into `CoordinateMapper` (via RunContext) → `onVisitStateTransition` updates stability counters and forwards the edge to `stage.onStateTransition` hooks (stagnation re-arm).

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| No rung selects an action | `SataChainStage` | `throw BadStateException` (message unchanged, `:588`) | Existing agent-level bad-state recovery, unchanged |
| LLM pipeline failure (screenshot/HTTP/parse/…) | `LlmEngine` | Engine returns null → stage returns `Continue`; cause counters + `[APE-LLM-ERROR]` unchanged | Remainder of the pipeline decides (declared fallback, D11) |
| Breaker OPEN | `LlmClient.allows()` | Stage `Continue`; open-episode line latched once | HALF_OPEN probe per existing breaker semantics |
| Wrong-variant `StageResult` accessor | consumer bug | `IllegalStateException` (fail loud) | Caught by unit tests; cannot occur in the single shipping consumer |
| Trigger dispatch failure (broadcast/service) | `ComponentTriggerStage` | WARNING log, `SideEffect` still recorded, exploration continues (component-triggering spec, unchanged) | none needed |
| Transition hook ordering bug (reset before/after burn) | migration risk | Pinned by per-stage episode unit tests + stagnation-episode golden | revert to table in D5 |

## Risks / Trade-offs

- [Any relocation subtly reorders an RNG draw or a guard conjunct] → predicates move verbatim; INV-DP-10; the per-preset goldens are the merge gate for every extraction task (tasks are ordered one-stage-per-step with the oracle re-run after each).
- [`StepContext` grows into a god-object] → its surface is enumerated in this design; additions require touching the spec (review gate R4).
- [Killing `LlmRouter` breaks its **67** existing unit tests] → counted at HEAD, not estimated: `LlmRouterTest` 32 (31 at the time of counting, plus task 3.3's breaker-latch test), `LlmRouterDeadPairTest` 12, `LlmRouterCoordinateMappingTest` 11, `LlmRouterMappingTest` 5, `LlmRouterToolSchemaTest` 4, `LlmRouterTelemetryTest` 3; `CoordinateMapIntegrationTest` and `SglangClientTest` also reference the class. Tests migrate unit-by-unit with the code they pin, per the per-file destination table in task 4.7 (the responsibility table above maps *code*, which is not the same mapping); the repair-pipeline tests (INV-LLM-10/INV-RTR-14) must pass unmodified in assertion content. **How it went**: 31 of the 67 travelled with their code at 4.3 and 4.4, and 11 more were rewritten into the three stages at 4.6, so 4.7 opens with only `LlmRouterTest` and `LlmRouterToolSchemaTest` still naming the class. Their residue is mostly not migratable — it is either already covered where the code went or vacuous — so the suite total falls at 4.7 rather than holding. Task 4.7 carries the per-test disposition.
- [`graphStableCounter` write access from a stage reintroduces hidden coupling] → the write surface is exactly one method (`resetGraphStableCounter`), documented as the `:503` parity carve-out; no other stage may write agent counters.
- [Scoring-params injection changes a default silently] → **the goldens cannot see this and neither can the pass unit tests** (D8 explains why: scoring runs above the oracle's entry point, and the pass tests supply their own params). `ScoringParamsDefaultsTest` (task 5.1a) is the guard: the eight defaults asserted as literals against a default-plan-derived `ScoringParams`. The `RUN_START` echo records the effective weights for post-hoc auditing but reads nowhere at runtime (D1, level 0), so it is evidence after the fact, not a gate.
- [The `greedyPickLeastVisited` tiebreak parameter changes the greedy path silently] → this is the seam where every priority boost becomes a chosen action (`action-selection`: "This makes all priority boosts (MOP, WTG, coverage) influence the greedy path"), so a wrong argument at the SataChain call site changes exploration without changing any stage's structure. Guarded by the paired unit test of task 6.3 (same actions and visit counts, `true` vs `false`, different pick) plus the extended grep-guard of 6.5, which asserts `State` no longer reads `Config.leastVisitedPriorityTiebreak` at all — otherwise a leftover static read would keep the old behavior while the parameter travels unused.
- [Stage-4 telemetry expectations leak in early] → this change only adds `StageResult.decisionSource` and the `stages=[...]` echo; no line format changes (R7 neutrality unaffected — telemetry still never decides).
- [`telemetry-proof-llm-efficacy` archives after, not before, this change] → its deltas and these are textually disjoint where possible; where the same requirement is touched (stagnation mode, decision-source telemetry) this change's delta already incorporates that change's content, so archive order only affects diff noise, not final text. Flagged in tasks 8.

## Testing Strategy

| Layer | What | How |
|-------|------|-----|
| JVM unit | `StageResult` totality/accessors; assembly matrix per preset (feature-absent=stage-absent, order, echo line); per-stage decide semantics (budget trivial/continue; LLM gate; launcher cadence/budget/round-robin; component coin+side-effect; rung table incl. BadStateException); episode arm/burn/re-arm; `LlmClient.allows()` latch; `CoordinateMapper` snap/boundary/ban; `ScoringParams` injection | `mvn test`, existing suite conventions (reflection fixtures where State/Graph needed) |
| Parity (gate) | Per-preset goldens (`aperv`, `mop`, `llm`, `llm_mop`) reproduce identical decision sequences under identical seeds after **each** extraction step | rearch-01 oracle re-run; merge gate per task group |
| Permanent architectural | Preemption golden incl. 3.3-1 (Sec. 9.4); LLM structural fallback (Sec. 9.5) | promoted from rearch-01 fixtures into the permanent suite |
| Build | `mvn package` green; jar boots on device (smoke via rv-platform when a rebuild ships) | Verification group |

## Open Questions

**None outstanding.** The two discretionary calls made here — `graphStableCounter` stays shared (D5) and `State.modelMenuEnabled` is documented residue of the static-read sweep (D9) — are both parity-neutral and recorded with rationale; neither contradicts an owner decision (D1–D6 of the report concern other stages). Finding 3.3-1's resolution (preserve, pin) follows the report's own framing ("becomes explicit, tested behavior preserving current semantics").
