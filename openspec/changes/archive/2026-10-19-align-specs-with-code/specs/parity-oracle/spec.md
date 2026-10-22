## Data Contracts

### Input

- `ScenarioScript` — hand-written deterministic synthetic app: ordered screens (widget lists
  materialized as `ModelAction`s with `Name` targets), a transition table, per-step LLM script
  entries, per-step scripted agent-side state (`_isNewState`, `graphStableCounter`), and the
  declared inputs nothing below the entry point assigns (a widget's `mopWeight`, the seeded
  cadence counter, an exhausted activity) (source: test tree, committed)
- `preset injection profile` — one profile per cell preset; the cell's resolved plan decides which
  mechanisms the scaffold mounts (`StaticGuiModel`, `MopData`, the scripted LLM (`ScriptedLlm`),
  the gates, the MOP coin, the X1/X3/X4 variants) (source: `OracleScaffold`)
- `seed: long` — seeds every RNG stream of the run: the agent stream (`getRandom()`, via the
  harness subclass override), the helper stream (`RandomHelper.seed`), and the four `StepRng`
  streams derived from it (`llmCoin`, `mopCoin`, `componentCoin`, the fuzz toss) (source: capture test)
- `MopData fixture` — `src/test/resources/cryptoapp.apk.mop.json` (wire format 2), loaded through
  the production `StaticArtifactLoader` for the cells that contain STATIC or MOP
- `-Dape.oracle.regenerate=true` — the only trigger for capture (write) mode
  (source: developer command line, never CI)

## Invariants

- **INV-ORA-01**: The oracle SHALL live entirely in the test tree, with exactly one carve-out:
  `SataAgent.egreedy()` SHALL read the agent's RNG through the overridable `ApeAgent.getRandom()`
  instead of `ape.getRandom()` directly. That single line is behavior-identical in production —
  `getRandom()` is defined as `return ape.getRandom()` — and it is the only thing standing between
  the harness and both epsilon-greedy legs, because the harness cannot supply an `ape`:
  `MonkeySourceApe` does not class-load off device (`MonkeySourceApeForeignGuardTest`), and
  stubbing `android.*` toward it does not converge (verified 2026-08-03: `IUiAutomationConnection` →
  `RemoteException` → `Build$VERSION`, the last of which would require inventing an SDK level the
  production code branches on). Apart from that line the oracle SHALL NOT modify any file under
  `src/main/java/`, SHALL NOT add production seams, and SHALL NOT place a class on the test
  classpath that shadows a production class. Source stubs under `src/test/java/android/**` (the
  established `Rect.java` pattern) are permitted for `android.*` framework types only.
- **INV-ORA-02**: Capture and replay SHALL be deterministic: the same harness, seed, preset,
  and scenario SHALL produce an identical decision-record sequence on every execution. Every
  seeded stream — the agent stream (`getRandom()`), the helper stream (`RandomHelper`) and the four
  `StepRng` streams (`llmCoin`, `mopCoin`, `componentCoin`, `fuzz`, derived by the installed run
  context from the plan's seed) — SHALL be seeded from the scenario's declared seed.
- **INV-ORA-03**: No oracle code path SHALL perform network I/O, capture a screenshot, or
  depend on wall-clock time. In particular the scripted LLM (`ScriptedLlm`) SHALL never invoke the
  real `LlmEngine.selectAction` pipeline and SHALL never drive `LlmCircuitBreaker` transitions
  (`System.currentTimeMillis()`-based, `LlmCircuitBreaker.java:54,77,95`): the scaffold replaces
  each LLM stage's `BreakerGate` with the hook's scripted verdict.
- **INV-ORA-07** (retired): it froze the goldens while `rearch-02-runspec` and `rearch-03-decision-pipeline` were in
  flight; both are archived, and the goldens were since recaptured on purpose (INV-ORA-08). The standing rule for
  restructuring changes is "Golden Comparison as the Migration Gate".

## MODIFIED Requirements

### Requirement: Preemption-Order Golden

The oracle SHALL include a preemption golden, captured in the `as_mop_llm` cell, built on synthetic states that simultaneously qualify the LLM stages, the MOP launcher (cadence reached, census candidate available), the MOP coin (an eligible weighted action on screen), and the SATA chain. It SHALL assert the precedence order of the `DecisionPipeline`: BUDGET, RESTART, LLM_NEW_STATE, LLM_STAGNATION, LLM_COIN, MOP_LAUNCHER, COMPONENT_TRIGGER, MOP_COIN, SATA_CHAIN. An accepted LLM result preempts the launcher; the launcher, when it fires, preempts the MOP coin; a won MOP coin preempts the SATA chain; the SATA chain is the fallback. The budget block precedes them all and SHALL be pinned **at its gate**: a scenario declaring the current activity's budget exhausted SHALL show the block entered and falling through, and a scenario declaring no exhaustion SHALL show it not entered at all. Its trivial-action return is outside the capture boundary — see Purpose of the main spec for why reaching it would widen the boundary rather than close a gap in it — and SHALL NOT be claimed by any golden. A due restart is selected by the RESTART guard ahead of every LLM stage.
The component-trigger stage sits between the launcher and the MOP coin and is a side effect that never returns an action (design D-B14.5). Its dispatch is device-bound (`android.content.Intent`), so `OracleSataAgent` SHALL override `triggerComponent()` as a recorded no-op: it dispatches nothing and records the tuple the round-robin cursor selected. The coin SHALL still draw from `StepRng.componentCoin` on every step, exactly as in production, so the oracle shifts no agent, helper or other coin draw. The step on which the trigger fires SHALL carry `dec.trig:{"k":"broadcast"|"service"|"provider","c":"<class>"}` (design decision A7), and the golden sets of the four `as*` cells, where the STATIC block mounts the trigger at `ape.componentPercentage=0.03`, SHALL contain those `dec.trig` members.

The preemption golden SHALL additionally pin:

1. **Global cadence** (INV-ORA-05, replacing finding 3.3-1): on a step taken by an LLM stage, the launcher's cadence counter SHALL advance; on the first later step that reaches `MOP_LAUNCHER` with the counter at or above the cadence, the launcher SHALL fire.
2. **Stagnation single-shot burn**: when the stagnation stage's trigger holds and its gates admit the call, the stage's `firedThisEpisode` flag SHALL become `true` regardless of the verdict — an LLM decline still burns the episode's shot.
3. **Counter reset on accept only**: `graphStableCounter` SHALL be reset to 0 by a stagnation accept and SHALL be left unchanged by a stagnation decline.
4. **Stage order**: on a step where several LLM stages would route, the new-state stage SHALL be consulted before stagnation, and stagnation before the coin; at most one of them calls the model on a step (design decision A3).

#### Scenario: LLM accept preempts a launcher that was due to fire

- **WHEN** a step's state qualifies the new-state LLM stage (scripted accept) AND the cadence counter is one below `ape.activityTriggerStagnationStep` with an unvisited census candidate available
- **THEN** the selected action SHALL be the LLM-accepted `ModelAction`
- **AND** the cadence counter SHALL have advanced by one during that step, reaching the cadence
- **AND** the next step that reaches `MOP_LAUNCHER` SHALL fire the launcher

#### Scenario: LLM decline falls through to the launcher

- **WHEN** the same simultaneous-qualification state runs with every scripted LLM verdict set to decline and the cadence reached
- **THEN** the selected action SHALL be the launcher's `EVENT_TRIGGER_ACTIVITY`
- **AND** the step's record SHALL carry `llm:"declined"`

#### Scenario: Stagnation decline burns the single shot

- **WHEN** the stagnation stage routes and the scripted verdict is decline, and a later step in the same episode would qualify the stagnation stage again
- **THEN** the first step SHALL set the `LlmStagnationStage`'s `firedThisEpisode` flag to `true` with `graphStableCounter` unchanged
- **AND** the later step SHALL NOT consult the stagnation stage's gate, because the stage's own trigger (`stagnationMidpointReached`, which reads `firedThisEpisode`) no longer holds

#### Scenario: Budget exhaustion is pinned at the gate, not at the trivial return

- **WHEN** a scenario declares the current activity's budget exhausted and the same step qualifies the LLM stages, with no trivial navigation action available — and none can be, since the trivial-activity path search walks graph edges the harness never records
- **THEN** the step SHALL fall through the budget block to the stages below it, and the golden SHALL record the LLM stage's outcome for that step
- **AND** the trivial-action return SHALL be documented as outside the capture boundary, so its absence from every golden reads as a stated exclusion rather than as untested behavior

#### Scenario: the MOP coin follows the launcher and precedes SATA

- **WHEN** a step with every LLM verdict declined, the cadence not reached, and an eligible weighted action on screen runs with `ape.mopCoinPercentage=1.0`
- **THEN** the selected action SHALL be the MOP coin's pick, the action with the largest `mopWeight`
- **AND WHEN** the same step runs with the cadence reached
- **THEN** the selected action SHALL be the launcher's `EVENT_TRIGGER_ACTIVITY`

#### Scenario: the component trigger is a recorded no-op that decides nothing

- **WHEN** an `as` cell step reaches `COMPONENT_TRIGGER` with `u_C = 0.01 < 0.03` and the cursor selects the provider tuple of `androidx.startup.InitializationProvider`, the fixture's one provider
- **THEN** `OracleSataAgent.triggerComponent()` SHALL dispatch no intent and the step's record SHALL carry `"trig":{"k":"provider","c":"androidx.startup.InitializationProvider"}`
- **AND** the step's action SHALL be selected by the `MOP_COIN` or `SATA_CHAIN` stage, and the next step's `u_C` SHALL be the next value of `StepRng.componentCoin`

### Requirement: Deterministic LLM Stubbing

LLM-cell goldens SHALL be captured with the scripted LLM `ScriptedLlm` (test tree) and never a live LLM server. The scaffold (`OracleScaffold.installScriptedLlm`) SHALL rebuild each assembled LLM stage's `LlmCall` with two substitutions and nothing else: the stage's `BreakerGate` is replaced by a gate answering the hook's per-step scripted verdict (`ScriptedLlm.gateFor(hook)`, hooks `new-state`, `stagnation`, `random`), and the engine is replaced by `ScriptedLlm`'s inner `ScriptedEngine extends LlmEngine`, which overrides `selectAction` with scripted results: **accept** (a `ModelAction` chosen from the passed action list by a deterministic selector named in the script), **decline** (null), or **timeout** (null, recorded distinctly as provenance). The plan's `MinActionsGate` and the step's shared `StepRouting` (one LLM call per step) are kept. The stage's own trigger is not scripted and is honored: the new-state stage still requires `_isNewState`, and the stagnation stage still evaluates its midpoint predicate against its own `firedThisEpisode` flag before any gate is consulted. The LLM coin is not scripted: it is decided by the seeded `u_L` draw and the plan's `p_L`. The stub SHALL NOT perform HTTP, SHALL NOT capture screenshots, and SHALL NOT record breaker transitions; scripts SHALL NOT model breaker-open episodes (out of oracle scope — wall-clock dependent). Script exhaustion, or a scripted consultation the agent never performed, SHALL fail the test.

#### Scenario: Accept returns a deterministic member of the offered actions

- **WHEN** a scripted accept names selector "first-unvisited-targeted" and the step offers a stable action list
- **THEN** `selectAction` SHALL return that exact `ModelAction` on every capture and replay
- **AND** no network connection SHALL be attempted at any point

#### Scenario: Decline and timeout share the fall-through, differ in provenance

- **WHEN** one step is scripted decline and another timeout
- **THEN** both steps SHALL fall through to the stages below the LLM stages
- **AND** their golden records SHALL carry `llm:"declined"` and `llm:"timeout"` respectively

#### Scenario: Unconsumed script entry fails loudly

- **WHEN** the script declares a routed consultation at step 5 but a declared gate bars it (for example the minimum-actions gate, on a screen with two actions under `ape.llmGateMinActions=3`)
- **THEN** the harness SHALL fail the test naming the unconsumed entry
- **AND** SHALL NOT silently continue to the next entry
