# Tasks — rearch-03-decision-pipeline

**Worktree** (decided 2026-08-03): all 7 stages are implemented in a single git worktree on branch `rearch` (`git worktree add ../ape-rearch -b rearch`), merged into `master` only after stage 7. Setup, what the worktree inherits, and the `mvn install` caveat: `docs/20260803_procedimento_worktree_rearch.md`. The goldens this change gates on are the ones committed by `rearch-01` on that branch, and they are never regenerated here (procedure doc §5).

> **Standing gate.** This change PRESUPPOSES `rearch-02-runspec` is applied (`RunSpec`/`RunContext`/`Feature` exist, `Config` is demoted, silent fallbacks and `/sdcard` readers are gone) and `rearch-01-parity-oracle` is green. **After every extraction task group below, the full per-preset golden suite (`aperv`, `mop`, `llm`, `llm_mop`) MUST pass before the next group begins** — the oracle is the merge gate, not a final check (INV-DP-09). Semantic preservation is the whole game: no task changes what any step decides.
>
> Line anchors reference HEAD `5dcf225` and will shift as extraction proceeds — treat `:NNN` as "find this block".

## 1. StageResult + DecisionStage + pipeline skeleton

- [x] 1.1 Create `StageResult` (single final class, private ctor, `Kind` enum `SELECT|CONTINUE|SIDE_EFFECT`, static factories `select(Action, String)`/`continueChain()` singleton/`sideEffect(String)`, wrong-variant accessors throw `IllegalStateException`) — design D1
- [x] 1.2 Create `DecisionStage` interface (`name()`, `decide(StepContext)`, default no-op `onStateTransition(StateTransition)`) and `StepContext` (read surface per design; single write method `resetGraphStableCounter()`) — design D2
- [x] 1.3 Create `DecisionPipeline` (`fromSpec(RunSpec, RunContext)` assembly + `[APE-ARCH] stages=[...]` echo; `decide(StepContext)` loop: first `SELECT` wins, `SIDE_EFFECT` recorded and continues, terminal stage never `CONTINUE`s) — INV-DP-01/02/05/06
- [x] 1.4 Unit tests: `StageResult` totality and accessor contract; pipeline loop preemption/side-effect/terminal semantics with stub stages; assembly echo content
- [x] 1.5 Run `/sdd-doc-code` on the three new files
- [x] 1.6 Run `/sdd-test-run` (new tests green; suite untouched otherwise)

## 2. Extract the stages one by one (goldens after EACH task)

> Wire the pipeline into `selectNewActionNonnull()` incrementally: extracted blocks become stages, and the blocks not yet extracted stay inline on the agent, reached through the roster's terminal stage (`InlineLadderStage`, design D14) so that every interim roster still decides every step through `decide()`. The logging prologue (`:450-462`) stays in the method. Every predicate moves **verbatim** (conjunct order, short-circuits, conditional RNG draws — INV-DP-10).

- [x] 2.1 `BudgetStage` — extract `:468-477`; Select trivial (`DecisionSource.Budget`, `PickChannel.SATA_OTHER`, the `:474` return) or Continue (null-trivial and non-exhausted paths); assembled only when the plan carries the activity-budget feature. Unit test both paths; **goldens green**
- [x] 2.2 `LlmGate` shared precondition helper (buffer-empty ∧ actions > 2) + `LlmNewState` stage — extract `:480-487`, accept step from `acceptLlmResult` (`:425-432`, incl. the `MODEL_LLM_TAP` resolution guard). Unit test precondition/trigger/accept/decline; **goldens green** (stubbed LLM scripts)
- [x] 2.3 `LlmStagnation` stage — extract `:493-506`; move `stagnationHookFired` from `StatefulAgent:128` into the stage; wire the agent's `onVisitStateTransition` to forward edges to `stage.onStateTransition` (re-arm at the former `:1436` site, which is deleted); counter reset on accepted escape via `StepContext` (`:503` parity). Unit tests: burn-on-fire (null and non-null), re-arm-on-new-edge, `>=` midpoint, no re-arm on escape reset; **goldens green**
- [x] 2.4 `LlmRandom` stage — extract `:508-515`; coin drawn only after `LlmGate`, before breaker, seeded RNG from `RunContext`. Unit test draw ordering; **goldens green**
- [x] 2.5 `MopLauncherStage` — extract `:522-545`; move `_stepsSinceLauncherFiring`/`_triggerRoundRobinIndex`/`_activityTriggerLaunchCount` (`SataAgent:756-768`) into the stage; `shouldFireLauncher`/`selectTriggerCandidate` pure seams move along, fed injected cadence/cap params (the 3 `Config` reads at `:523-525` die). Unit tests: cadence equality + reset, budget consumed only on launch (INV-CT-12), round-robin persistence; **goldens green**
- [x] 2.6 `ComponentTriggerStage` — extract `:547-551` returning `SideEffect`; move the component round-robin cursor from `StatefulAgent`; `triggerMopComponent()` dispatch helpers stay put and are called by the stage. Unit test coin/guard/side-effect-continues; **goldens green**
- [x] 2.7 `SataChainStage` — extract `:552-588` as the terminal stage; collapse the 7× copied `resolved`/`if`/`return` pattern into an ordered rung table (supplier + `SataEventType`) walked by one loop (design D12); same order, logging, provenance stamping, `BadStateException` at exhaustion. Unit tests: rung order, exhaustion throw; **goldens green**
- [x] 2.8 Delete the now-empty inline ladder: `selectNewActionNonnull()` = prologue + `pipeline.decide(stepContext)`; verify the method body carries no residual guard or counter. **Full golden suite green per preset**
- [x] 2.9 Run `/sdd-doc-code` on the stage classes; run `/sdd-test-run`

## 3. Permanent architectural tests (promoted from rearch-01 fixtures)

- [x] 3.1 Permanent preemption golden (report Sec. 9.4): synthetic step qualifying LLM + launcher-firing-point + component-coin + SATA simultaneously ⇒ order confirmed (`LLM` wins; no trigger; no rung)
- [x] 3.2 Permanent 3.3-1 pin (INV-DP-08): LLM-preempted step leaves the launcher cadence counter unchanged; next fall-through step advances it by exactly 1
- [x] 3.3 Permanent structural-fallback test (report Sec. 9.5): decline/timeout/breaker-open ⇒ step decided by the configured remainder with correct non-`LLM` `decision_source`; breaker-open line once per episode. **Split across two seams, because the oracle cannot carry all three conditions**: `ScriptedLlmRouter` overrides the routing predicates outright, so no override reaches the breaker and no script can model a breaker-open episode — that override is what satisfies INV-ORA-03, and INV-ORA-07 freezes the goldens depending on it. Decline and timeout are already pinned there (`goldens/llm/baseline.ndjson` steps 1–2, both falling through to `decisionSource=SATA`). What this task adds is (i) the composition over an assembled pipeline, on two plans so that "the configured remainder" is `SataChain`/`SATA` in one and `MopLauncher`/`Component` in the other, and (ii) the breaker-open latch, which had no test at all, asserted where the latch lives
- [x] 3.4 Feature-absent = stage-absent assembly matrix per preset (INV-DP-03), asserted against the rosters the shipped presets actually produce: `aperv` ⇒ `[Budget, SataChain]`; `mop` ⇒ `[Budget, SataChain]` — its four keys are *scoring* weights, it inherits `ape.activityTriggerEnabled=false`, and no preset states `ape.componentPercentage`, so the substrate is present and no MOP stage is assembled; `llm` and `llm_mop` ⇒ `[Budget, LlmNewState, LlmStagnation, LlmRandom, SataChain]` — including the random stage neither preset mentions, because `ape.llmPercentage` resolves to its jar default of `0.02` (`KeyOwnership.java:216`) and `Feature`'s `"0"` is the neutral value, not the default; echo line asserted

## 4. LlmRouter decomposition (design D7)

- [x] 4.1 `LlmClient` — compose `SglangClient` + `LlmCircuitBreaker`; move `breakerAllows()` (`LlmRouter:292-302`) as `allows()` with the open-episode latch; move the `[APE-LLM-CONFIG]` manifest emission and tools-schema constants; construct from `LlmParams` in `RunContext`
- [ ] 4.2 `ScreenshotStep` — device-dimension probing (`:337-353`) + capture (`:362-385`) + encode (`:388-395`), failure-stage seam intact
- [ ] 4.3 `CoordinateMapper` — `CoordinateNormalizer` wrap, boundary bands, `mapToModelAction` + `fixTextEdit`, dead-pair ban (`banKey`/`isDeadPair`/strikes/`recordLlmOutcome`), nearest-widget calc; snap/boundary params injected (`llmSnapTolerancePx`, `llmBoundaryTopPct/BottomPct`)
- [ ] 4.4 `LlmTelemetry` — all counters/latches (`LlmRouter:68-112`), `[APE-LLM-TEL]`/`[APE-LLM-ERROR]`/`[APE-LLM-PROMPT]`/`[APE-LLM-RESPONSE]`/ACK, `printSummary`; teardown call site updated
- [ ] 4.5 `LlmEngine` — the thin 9-step orchestration replacing `selectAction` (`:327-612`): never-throws + finally-nulling preserved (INV-RTR-02/06); repair pipeline untouched (INV-LLM-10/INV-RTR-14 — native `tool_calls` malformations still flow `rawArguments` → `ToolCallParser` Level 1 → shared `parseJsonString`, surfacing as `repair=`)
- [ ] 4.6 Point the three LLM stages at `LlmEngine`; route the `[APE-OUTCOME]`-site dead-pair feedback (`StatefulAgent:1041`) to `CoordinateMapper` via `RunContext`
- [ ] 4.7 **Delete `LlmRouter`** (P3 — no facade); migrate its **67** unit tests to the owning units with assertion content unchanged (repair-path and telemetry-field tests must pass unmodified). Write the per-file destination map before moving anything, so no file is dropped for having no obvious home:

  | Test file | `@Test` | Destination unit |
  |---|---:|---|
  | `LlmRouterTest` | 32 | split across `LlmEngine` (orchestration, never-throws, outcome classification) and `LlmClient` (transport, breaker). The 32nd is task 3.3's `breakerOpenIsLoggedOncePerOpenEpisode`, which pins the open-episode latch this task's 4.1 moves into `LlmClient.allows()`; it goes to `LlmClient` with the rest of the breaker tests |
  | `LlmRouterDeadPairTest` | 12 | `CoordinateMapper` (ban key, strikes, `recordLlmOutcome`) |
  | `LlmRouterCoordinateMappingTest` | 11 | `CoordinateMapper` (containment, snap, boundary bands) |
  | `LlmRouterMappingTest` | 5 | `CoordinateMapper` (`mapToModelAction`, `fixTextEdit`) |
  | `LlmRouterToolSchemaTest` | 4 | `LlmClient` (wire schema constants/builders) |
  | `LlmRouterTelemetryTest` | 3 | `LlmTelemetry` (counters, lines, ACK latch) |
  | `CoordinateMapIntegrationTest`, `SglangClientTest` | — | keep in place; update their `LlmRouter` references to the new units |
- [ ] 4.8 Run `/sdd-doc-code` on the new llm units; run `/sdd-test-run`; **goldens green** (LLM presets, stubbed)

## 5. ScoringPipeline real injection (V10 + finding 3.3-3)

- [ ] 5.1 Create `ScoringParams` (RunSpec-derived: `coverageBoostWeight`, `formCompletionEnabled`, `frontierBoostWeight`, `mopFrontierWeight`, `mopWeightWtg`, `mopWeightDirect`, `mopWeightTransitive`, `mopWeightOpenMenu`)
- [ ] 5.1a `ScoringParamsDefaultsTest`: assert the eight fields of a default-plan-derived `ScoringParams` against the jar defaults, as literals in the test. This is the **only** drift guard for scoring defaults in the whole rearch set — the goldens never execute scoring (`StatefulAgent.java:1475-1478`), the pass unit tests supply their own params by INV-ARCH-11, the `RUN_START` echo is write-only (D1), and `rearch-01`'s per-preset `Config` guard covers only the values the ladder reads
- [ ] 5.2 Replace `ScoringPipeline.fromConfig(Config, ScoringContext)` with `fromParams(ScoringParams, ScoringContext)` (P3 — the decorative `cfg` path is deleted); update the sole caller (`StatefulAgent:208`) to pass real params
- [ ] 5.2a Record the candidate census inside `fromParams`, where the full candidate list is still in scope: `{name, enabled}` per candidate pass, handed to the sink as `PIPELINE.candidates` (stage 4). `passes` keeps exactly today's content — the census is a sibling member, never a widening of that list (INV-ARCH-04). **No `disabledReason()` on the `ScoringPass` interface**: the three gate conjuncts are already in the trace (`MOP_DATA.status`, `MOP_DATA.wtgEdges`, `RUN_START.params`), and the three passes do not order their conjuncts uniformly, so a "first failing conjunct" would record source order rather than cause. On the stage side, the decision pipeline's candidate list is a static declaration of names — never a constructed no-op stage (INV-DP-03)
- [ ] 5.3 Parameterize the passes (ctor injection; `isEnabled()` logic unchanged) and `MopScorer` (weights as parameters; static `Config` reads deleted)
- [ ] 5.4 Fix the class javadoc: seven-pass roster (`MopWidget → MenuGateway → WTG → Frontier → MopFrontier → Coverage → FormCompletion`), "six passes" wording removed (finding 3.3-3); P4 current-state comments
- [ ] 5.5 Migrate pass/scorer unit tests to explicit params (no `Config` mutation in tests); injection-contrast test (same context, two params ⇒ different assembly); **goldens green**
- [ ] 5.5a Migrate `PipelineParityTest` (`src/test/java/.../agent/PipelineParityTest.java`, 5 tests) — it builds the pipeline through `ScoringPipeline.fromConfig(null, ctx)` at `:112` and `:148`, the entry point task 5.2 deletes, so this change breaks its compilation and no other task in any of the seven changes names the file. It is also the file `rearch-01` delegates scoring parity to ("`adjustActionsByGUITree()` scoring parity is already locked by `PipelineParityTest` and stays where it is"), which makes deleting it the wrong reflex. Rebuild both call sites on `fromParams(ScoringParams, ctx)` with explicit params, and while there replace the tautological weight assertions (`assertEquals(..., Config.mopWeightOpenMenu, menu.getMenuBoost())` compares the constant to itself and passes under any value) with the literals `ScoringParamsDefaultsTest` pins

## 6. Static-Config-read sweep (design D9, INV-DP-12)

- [ ] 6.1 `SataAgent`: replace the 25 `Config.` reads with `ExplorationParams`/stage-injected params via `StepContext` (epsilon family, `backMenuPickCap`, `mopTargetPickCap`, `trivialActivityRankThreshold`, `doBackToTrivialActivity`, `useActionDiffer`, `fillTransitionsByHistory`, `fallbackToGraphTransition`)
- [ ] 6.2 `StatefulAgent` decision-relevant reads (restart thresholds, budget params, `evolveModel`, throttles) → `ExplorationParams` via `RunContext`; telemetry/save flags → `TelemetryParams` at their emission sites
- [ ] 6.3 `State.greedyPickLeastVisited`: take the tiebreak flag as a parameter from the SataChain call site; document the `modelMenuEnabled` structural residue at its read site (design D9 — the one accepted exception, with rationale comment). **Add the paired test this seam has never had**: same action set, same visit counts, differing priorities, called once with `true` and once with `false` — the two calls SHALL pick different actions. This is where priority boosts turn into chosen actions (`SataAgent.java:670`; `action-selection`: "This makes all priority boosts (MOP, WTG, coverage) influence the greedy path"), so an argument wired wrong here degrades MOP guidance while every stage still reports the same structure
- [ ] 6.4 `ApePromptBuilder` variant → `LlmParams`
- [ ] 6.5 Grep-guard test: zero `Config.` references in the pipeline/stage/llm-unit/scoring packages (allowlist: none). `State` lives outside those packages, so guard it explicitly rather than exempting it: assert zero `Config.leastVisitedPriorityTiebreak` reads anywhere in `src/main` after 6.3 — a leftover static read would silently keep the old behavior while the injected parameter travels unused. `Config.modelMenuEnabled` in `State` is the one allowed remaining read (design D9 residue) and is asserted to be exactly one occurrence
- [ ] 6.6 Run `/sdd-qa-lint-fix` over the touched modules; **goldens green**

## 7. Wiring completion and cleanup

- [ ] 7.1 `RunContext` owns the assembled `DecisionPipeline` and the LLM units; transition-event forwarding wired exactly once per visited edge (order: agent counters first, then stages)
- [ ] 7.2 Verify `StageResult.select` labels equal stamped `ModelAction` provenance on every stage (unit assertion per stage; INV-DP-04) — the stage-4 telemetry handle
- [ ] 7.3 Delete dead residue: former inline-block comments, the triplicated precondition text, unused imports; P4 pass over touched files (no migration-history comments)
- [ ] 7.4 Run `/sdd-verify` on the module

## 8. Verification

- [ ] 8.1 Full suite: `mvn test` — 0 failures/errors; migrated LLM/scoring tests green with unchanged assertion content
- [ ] 8.2 Full per-preset golden suite green (`aperv`, `mop`, `llm`, `llm_mop`) — INV-DP-09, the acceptance gate
- [ ] 8.3 Permanent tests (3.1–3.4) in the default `mvn test` run, not a separate profile
- [ ] 8.4 `mvn package` green → `target/ape-rv.jar`; on-device smoke via rv-platform at the next scheduled rebuild (no manual emulator management)
- [ ] 8.5 `openspec validate rearch-03-decision-pipeline --strict`
- [ ] 8.6 Cross-change check: if `telemetry-proof-llm-efficacy` is still unarchived, reconcile its deltas for `Stagnation LLM Mode` / `Per-action decision-source telemetry` / `LlmRouter Lifecycle` with this change's (this change's text already incorporates its content — archive order affects diff noise only; verify no requirement text is lost at whichever archive runs second)
- [ ] 8.7 Run `/sdd-qa-lint-fix` → `/sdd-verify` → `/sdd-code-reviewer` (final gate)
- [ ] 8.8 [skill: superpowers:verification-before-completion] confirm goldens + permanent tests + build output before claiming behavior preservation
