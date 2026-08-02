## Why

The action-selection policy — the research-relevant hard-precedence ladder — is 141 LOC of textually ordered guarded `if` blocks in `SataAgent.selectNewActionNonnull` (`SataAgent.java:449-589`, V1), with the LLM precondition triplicated verbatim (V2), the "when to use LLM" decision split across two classes (V3), stagnation episode state scattered across three classes (V5), a 286-LOC/19-responsibility `LlmRouter.selectAction` monolith (V6), and a `ScoringPipeline` whose config parameter is decorative (V10). The launcher-cadence interaction under LLM preemption (finding 3.3-1) exists only as a textual accident. The semantics are correct and must be preserved **exactly** (R8, owner decision Q1: hard preemption, never weighted sampling); the structure is what changes.

This change is **stage 3 of 7** of the re-architecture selected in `docs/analise_fable-selecao.md` (rev. 3, Sec. 6.3), gated by the `rearch-01-parity-oracle` goldens.

## What Changes

- New `DecisionPipeline`: an ordered list of named `DecisionStage`s assembled once from the `RunSpec`. Stage result is a sum type `StageResult`: `Select(action, decisionSource)` / `Continue` / `SideEffect(desc)` (attacks the overloaded `null` and the effect-without-return component trigger).
- Stage order, exactly today's verified order (V1): `Budget` (may **Select** the trivial non-null action — Sec. 3.2 correction — or Continue) → `LlmNewState` → `LlmStagnation` → `LlmRandom` (shared precondition in a single helper, killing the V2 triplication) → `MopLauncher` → `ComponentTrigger` (SideEffect) → `SataChain` (fallback; internally preserves the 7 SATA rungs and the `ScoringPipeline` as its scoring sub-pipeline).
- A feature absent from the plan ⇒ its stage is absent from the list (structural purity; generalizes INV-ARCH-03).
- Episode state relocated into owning stages: the stagnation flag (today in `StatefulAgent:128`/`:1436`/`SataAgent:499`) lives in `LlmStagnation`; launcher counters live in `MopLauncher`. The 3.3-1 interaction (LLM preemption does not advance launcher cadence) becomes an explicit, tested decision preserving current behavior.
- `LlmRouter` sliced into JVM-testable units: `LlmClient` (HTTP + breaker), `ScreenshotStep`, `PromptBuilder` (exists), `CoordinateMapper` (snap/boundary/ban), `LlmTelemetry` — orchestrated by the LLM stages. LLM fallback is declared in the plan and realized structurally: the stage returns `Continue` on decline/failure/breaker and the pipeline falls through to the configured remainder.
- `ScoringPipeline` receives real parameter injection from the `RunSpec` (fixes the decorative `cfg`, V10); the "six passes" javadoc drift (finding 3.3-3) is corrected.
- Remaining per-run mutable state (model, graph, MopData, LLM client + breaker, trackers, counters) completes its migration into `RunContext`; nothing consults static `Config` after bootstrap.
- **No semantic change to any preserved behavior**: hard preemption order, budget-gate trivial action, component trigger as side effect, the 7 SATA rungs, epsilon-greedy — all reproduced under the parity oracle.

## Capabilities

### New Capabilities

- `decision-pipeline`: stage contract, `StageResult` sum type, assembly from plan, preemption-order requirements (including the golden test), episode-state ownership, LLM fallback semantics.

### Modified Capabilities

- `action-selection`: the selection ladder requirement is restated as pipeline stages; behavior unchanged (parity-gated).
- `llm-routing`: router decomposition; stage-owned preconditions and episode state; declared fallback.
- `llm-infrastructure`: `LlmClient` (HTTP + circuit breaker) extracted as its own unit.
- `scoring-pipeline`: real config injection from `RunSpec`; no static `Config` reads in passes.
- `component-triggering`: trigger expressed as `SideEffect` stage; semantics unchanged.
- `exploration`: stagnation episode state ownership moves out of `StatefulAgent`.

## Impact

- **Java**: `SataAgent`, `StatefulAgent`, `LlmRouter` (dismantled), `ScoringPipeline` and passes, `RunContext`. New: `DecisionPipeline`, `DecisionStage`, `StageResult`, stage classes.
- **Python/rv-android**: none.
- **Gate**: `rearch-01-parity-oracle` goldens must pass per preset (report Sec. 9.9); preemption golden (Sec. 9.4) and LLM-fallback test (Sec. 9.5) become permanent.
- **Depends on**: `rearch-02-runspec` (stages are assembled from the plan and read injected params).
- Grounding: report Sec. 6.3, Sec. 5.3, Sec. 12 (Q1 hard precedence), verified V1–V6/V10, findings 3.3-1/3.3-3.
