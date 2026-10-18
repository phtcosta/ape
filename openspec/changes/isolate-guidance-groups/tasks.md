<!-- Subagent dispatch hints (plan §10, D-A14):
     - Wave 0 (groups 1–3) is serial, one agent, contracts only: it must complete and stay green before any front starts.
     - Wave 1 (groups 4–9) = fronts F1–F6, one agent and one git worktree per front (`isolation: "worktree"`), all in
       parallel. File ownership is disjoint: a front edits only the files listed under its group. A front that needs
       another front's behaviour codes against the wave-0 interface, never against the other front's files.
     - F7 (group 10) is the rv-android sister change, owned by the `aperv-tool` session, on branch
       `isolate-guidance-groups` in rv-android; it runs in parallel with wave 1.
     - Wave 2 (group 11) integrates: merge the six worktrees into `isolate-guidance-groups`, then the gates.
     - Wave 3 (group 12) is the device smoke. Group 13 is the amendment after `e03igg` (contracts, then fronts F8–F12 in parallel, then integration). Group 14 is the final verification.
     - Critical path: 1 → 2 → 3 → {4..9 ∥ 10} → 11 → 12 → 13 → 14.
     - ~45 files in APE-RV — use subagent orchestration (6 parallel dispatches in wave 1).
     - Paths are relative to src/main/java/com/android/commands/monkey/ape/ unless they start with src/ or openspec/.
     - Run `mvn clean` before any test baseline (stale classes in target/ fake errors). Baseline at eea2b1a9:
       `mvn test` = 1361 tests, 24 skipped.
     - Commits: only on branch isolate-guidance-groups, per wave, by explicit path, with no co-author trailer. -->

## 1. Wave 0 — run-plan contracts (serial)

- [x] 1.1 Baseline: `mvn clean test` on `isolate-guidance-groups` at `eea2b1a9`; record the count (expect 1361 / 24 skipped)
- [x] 1.2 `runtime/Feature`: add the roots `ADDONS` (`ape.addons`), `STATIC_DATA` (`ape.staticDataPath`, PRESENT) and `LLM` (`ape.llm`, owning `ape.llmUrl` with no neutral); turn `MOP` into a `ape.mop` root that requires `STATIC_DATA`; add `STATIC` (`ape.static`, requires `STATIC_DATA`), `BACK_MENU_CAP`, `ACTIVITY_STABLE_RESTART` and `MOP_COIN`; rename `FRONTIER` → `NAV_FRONTIER` (requires `STATIC`, D-A1), `LLM_RANDOM` → `LLM_COIN` and `ACTIVITY_TRIGGER` → `MOP_LAUNCHER` (key `ape.activityTriggerEnabled` unchanged, D-B11); move `COMPONENT_TRIGGER` (D-B13) and `TYPED_FUZZ` (D-B14.3) under `STATIC`; make every addon member require `ADDONS`; delete `LEAST_VISITED_TIEBREAK` (design D-B1, D-B4, D-B5, D-B13)
- [x] 1.3 `runtime/KeyOwnership`: add the new keys with their types and defaults (`ape.addons`, `ape.static`, `ape.mop`, `ape.llm`, `ape.staticDataPath`, `ape.variant` as resolver, `ape.mopCoinPercentage`, `ape.llmGateMinActions`, `ape.llmBreakerFailures`, `ape.llmBreakerOpenMs`); move `ape.backMenuPickCap` and `ape.activityStableRestartThreshold` from BASE to their features; retire `ape.mopDataPath`, `ape.leastVisitedPriorityTiebreak` and `ape.mopTargetPickCap`, each with the reason given in design.md
- [x] 1.4 `runtime/Presets`: the four group blocks and the six cell presets `base`, `mop`, `as`, `as_mop`, `as_llm` and `as_mop_llm` (design § Key contract). Keep the four old presets for now, each given the group keys (`addons`, `static`, `mop`, `llm`) that make it resolve to its current behaviour, so the existing goldens stay green; group 11 deletes them. A test that builds a plan with no preset and relied on a `Config` default of an addon (e.g. `modelMenuEnabled=true`) states `ape.addons=true` explicitly after 1.8; list each such test in the commit
- [x] 1.5 `runtime/RunSpec`:
  - [x] 1.5.1 add the `StaticParams` record (data path, strict package match, `frontierBoostWeight`, `fuzzInputTyped`, `componentPercentage`), present iff `STATIC_DATA` is in the plan;
  - [x] 1.5.2 move `mopCoinPercentage` into `MopParams`, and the gate and breaker keys into `LlmParams`; `ape.mopStrictPackageMatch` moves to `STATIC_DATA` (D-B14.3); `ape.staticDataPath` with neither `STATIC` nor `MOP` mounted is inert (D-B14.20);
  - [x] 1.5.3 clamp `p_M` to [0,1];
  - [x] 1.5.4 resolve `ape.variant`;
  - [x] 1.5.5 a member's params accessor returns the neutral value when the member is absent: `activityStableRestartThreshold()` returns `Integer.MAX_VALUE` without `ACTIVITY_STABLE_RESTART`, `backMenuPickCap()` returns 0 without `BACK_MENU_CAP` (today `RunSpec.java:800` has no `has()` gate);
  - [x] 1.5.6 delete the dead accessor `MopParams.activityTriggerEnabled()` (`RunSpec.java:974-975`, no caller); `componentPercentage` and `strictPackageMatch` are read from `StaticParams` only
- [x] 1.6 `runtime/RunSpecEcho`: write `variant` in `RUN_START` (omitted when null; outside the digest); `FORMAT_VERSION` = 2 (D-B14.22)
- [x] 1.7 Mechanical rename `ape.mopDataPath` → `ape.staticDataPath` across `src/main` and `src/test` (`StatefulAgent.java:204` reads `spec.staticData().dataPath()`); MOP reads stay under `spec.mop()`
- [x] 1.8 D-B6, every addon read site reads the plan and no longer `Config`:
  - [x] 1.8.1 `model/State.java:78` (`MODEL_MENU`)
  - [x] 1.8.2 `tree/GUITreeNode.java:218,594` and `tree/GUITreeBuilder.java:444` (`TREE_ENHANCEMENTS`)
  - [x] 1.8.3 `agent/ApeAgent.java:252` (`HEURISTIC_INPUT`)
  - [x] 1.8.4 `MonkeySourceApe.java:818,837` (the two guards)
  - [x] 1.8.5 `utils/MopData.java:207` (`MOP_ACTIVITY_SOURCE`)
  - [x] 1.8.6 BASE keys the plan owns (D-B6, verification A10): `MonkeySourceApe.java:876` `Config.doFuzzing`/`fuzzingRate` (`FUZZING`) and `agent/ApeAgent.java:280` `Config.checkRestart`
  
  Delete the `Config` twins these reads leave dead, and those of the retired or renamed keys (`mopDataPath` `Config.java:121`, `mopTargetPickCap` `:153`, `leastVisitedPriorityTiebreak` `:249`, `frontierBoostWeight` `:216`), P3. `ape.minEpsilon`/`ape.maxEpsilon` stay `DYNAMIC_EPSILON` keys, already read through the plan (`SataAgent.java:1214`); their twins `Config.java:218-219` are deleted too. Update their test readers (`oracle/LadderConfigGuard.java:57,62,63,84`, `utils/ConfigTest.java:100`) and `agent/pipeline/StaticConfigReadGuardTest` (its single accepted residue `modelMenuEnabled`, pinned at `:139-151`, goes to zero occurrences)
- [x] 1.9 Tests:
  - [x] 1.9.1 `FeatureDerivationTest`: group off → members left out; explicit member key without its group → `MISSING_DEPENDENCY`
  - [x] 1.9.2 `PresetsTest` and `CellPresetTest` (new): the six cells resolve to the expected `features` of design.md
  - [x] 1.9.3 `RunSpecAbortTest`: the three retired keys abort with their reason; `ape.llm=true` without a URL aborts
  - [x] 1.9.4 `RunSpecEchoTest`: `variant` is echoed and is not in the digest
  - [x] 1.9.5 `KeyOwnershipTotalityTest` stays green
  - [x] 1.9.6 `GroupReadSiteTest` (new): with `base`, every addon site in 1.8 reads off
- [x] 1.10 Run `/sdd-test-run` (`mvn test`): green, except tests that pin the retired keys, which are rewritten here

## 2. Wave 0 — telemetry contracts (serial)

- [x] 2.1 `telemetry/EventSink` and `NoopSink`: add the methods `owner`, `cfc`, `draw`, `nav`, `input`, `restart`, `backMenuCapped`, `strategy`, `rotation`, `bufferAbandoned` and `componentTrigger` (`dec.trig`, verification A7), plus the counter increments (design § Telemetry contract); remove the `cf` and `wtgSource` arguments from `decision(...)` and add `navf`
- [x] 2.2 `telemetry/StepRecord`:
  - [x] 2.2.1 the members `own`, `cfc`, `draw`, `nav`, `inp`, `restart`, `bmc`, `strat`, `rot`, `buf`, `navf` and `trig`, in the shapes of design.md;
  - [x] 2.2.2 `own` and `rot` are always written (exempt from omit-default, documented beside `mopd`);
  - [x] 2.2.3 `dec.cf` and `dec.wtgsrc` are removed;
  - [x] 2.2.4 `reset()` clears the new members
- [x] 2.3 `telemetry/NdjsonSink` and `telemetry/RunCounters`: route the new calls; `RUN_END.counters` gains `launches`, `gateMinActions`, `gateBreaker`, `guardBacks`, `bufAbandoned`, `restarts`, `coinLlm` and `coinMop`, written in full
- [x] 2.4 Update every current caller of the changed `decision(...)` signature mechanically (`StatefulAgent.recordModelDecision` passes `navf`, and nothing for `cf`/`wtgsrc`; the test callers `NdjsonSinkTest` (~35 calls) and `LlmTelemetryTest` (~12)). No other behaviour changes
- [x] 2.5 Tests: `StepRecordTest` round-trips every new member through `org.json`; omit-default cases; the one-line invariant holds with hostile strings in `own.g`/`fb`
- [x] 2.6 Run `/sdd-test-run`

## 3. Wave 0 — interfaces and shared primitives (serial)

- [x] 3.1 `agent/pipeline/LlmGate` becomes the interface `{ String name(); boolean admits(StepContext ctx); }`. Its acceptance helper moves to `agent/pipeline/LlmAcceptance`, and its callers follow
- [x] 3.2 `agent/pipeline/DecisionStage` gains `default void onStepOpen(StepContext ctx) {}`. `StageCollaborators` gains `restartDue()`, `restartAction()`, `abandonBuffer(String owner)` and `mopLedger()`, as default methods that throw `UnsupportedOperationException` until F5 implements them in `StatefulAgent` (`SataAgent` implements `StageCollaborators`, `:67`, and inherits them). Rename `triggerMopComponent`/`mopComponentTargetCount` → `triggerComponent`/`componentTargetCount` everywhere (`StageCollaborators.java:172,180`, `DecisionPipeline.java:180`, `StatefulAgent.java:1389,1409`, `PipelineFixture.java:166`, `DecisionPipelineFromSpecTest.java:151`, `StatefulAgentTriggerTest`), and `requireMopArm` with its message naming `ape.mopDataPath`
- [x] 3.2b `agent/pipeline/StepContext` gains `staticModel()` and `stepRng()`; `StatefulAgent` (`:88`) implements them (the static model delegates as in 3.5; `stepRng()` returns the instance of 3.7). `runtime/RunContext` builds `StepRng` from the run seed. No pipeline or `llm/` class calls `RunContext.current()` (the static-read guard test)
- [x] 3.3 `agent/sata/LeastVisitedTie`, `agent/sata/AbaTargetTie`, `agent/sata/TrivialPathPick`: the interfaces of X1, X3 and X4 (signatures in design.md)
- [x] 3.4 `llm/PromptContribution`: the interface with default-empty methods
- [x] 3.5 `utils/StaticGuiModel`: the public read-only API, with method signatures for the WTG, widget metadata, package/main activity and components with intent filters, including `data`. The bodies delegate to the current `MopData` until F2 moves the parsing
- [x] 3.6 `agent/MopInteractionLedger`: complete, a small pure class with `tried(activity, key)` and `record(activity, key)`, plus `MopInteractionLedgerTest`
- [x] 3.7 `agent/StepRng`: complete. Streams `llmCoin`, `mopCoin`, `componentCoin` and `fuzz` derive from the run seed; `StepRngTest` checks determinism per seed and that the streams are independent
- [x] 3.8 `model/ScorerCounterfactual`: generalize `MopCounterfactual` to zero one named scorer field (pure, with no RNG, no `resolveAction` and no cache effect); delete `MopCounterfactual` (P3) and move its callers (`SataAgent` `:584`, `:611`, `:1511`, `:1528`; `StateTest`); `ScorerCounterfactualTest` absorbs `MopCounterfactualTest`
- [x] 3.8b `model/ModelAction`, additive only so the old goldens stay green: the `navFrontierBoost` field with its accessors; the `DecisionSource` owner values `budget`, `restart`, `llm`, `launcher`, `mop`, `sata`; the `PickChannel` values `mop_coin`, `budget`, `restart`. Each wave-1 front sets them in its own files; the obsolete values (`SHORT_CIRCUIT_*`, the boost-attributed sources) are deleted in 11.2
- [x] 3.9 Run `/sdd-doc-code` on each new file in 3.3–3.8b
- [x] 3.10 Run `/sdd-test-run`; `mvn clean test` green. **Commit wave 0** (explicit paths; ask the author) and **send the `aperv-tool` session the confirmation that the key contract is frozen at this commit**, with its SHA

## 4. Wave 1 · F1 — rotation (plan §2.4, D-A9)

Owns: `llm/ScreenshotCapture.java`, `AndroidDevice.java`, `MonkeySourceApe.java`,
`src/test/.../llm/ScreenshotCaptureStageTest.java`.

- [x] 4.1 Port the code of `f993081f` into `llm/ScreenshotCapture.java` (`git show f993081f -- src/main/java/com/android/commands/monkey/ape/llm/ScreenshotCapture.java`): `displayRotation(int)` clamps to 0..3, and the SurfaceControl path passes `displayRotation(AndroidDevice.getRotation())`. Bring the test hunk of `ScreenshotCaptureStageTest.java`. **Do not** port `CLAUDE.md` or `openspec/changes/llm-opaque-screen/tasks.md`; never merge `llm-opaque-screen`
- [x] 4.2 Port the code of `3b35073a` into `AndroidDevice.java` and its javadoc line in `ScreenshotCapture.java`: `getRotation()` reads `DisplayManagerGlobal.getInstance().getRealDisplay(DEFAULT_DISPLAY).getRotation()`, and `catch (Exception)` returns `-1`
- [x] 4.3 `MonkeySourceApe`:
  - [x] 4.3.1 write `dec.rot` every step through `sink().rotation(AndroidDevice.getRotation())`;
  - [x] 4.3.2 the fuzz toss (`:876`) draws from `StepRng.fuzzToss`;
  - [x] 4.3.3 the foreign/tree guard BACKs increment `guardBacks`
- [x] 4.4 Tests: `displayRotation` clamp cases (`-1`, `0..3`, `4`), and that a guard BACK increments its counter
- [x] 4.5 Run `/sdd-test-run`

## 5. Wave 1 · F2 — data split and scorers (P-1, D-A1, D-B13, R1)

Owns: `utils/MopData.java`, `utils/StaticGuiModel.java`, `utils/StaticArtifactLoader.java` (new),
`utils/ComponentInfo.java`, `utils/MopScorer.java`, `utils/MopContext.java`, `utils/TypedInputGenerator.java`,
`agent/scoring/*`, `model/ModelAction.java` (after 3.8b, only the boost fields), their tests,
`src/test/resources/{cryptoapp.apk.mop.json,synthetic-activity-selection.mop.json}`.

- [x] 5.1 `StaticArtifactLoader` parses format 2 once and builds `StaticGuiModel`: WTG, widget metadata (idName, hint, inputType, entries, prompt, spinnerMode, contentDescription, tooltipText), package, main activity, and every component with its intent filters, including the new optional `data` member. It also builds `MopData`: per-event MOP maps, distances, handlers, `activityDist`, the MOP activity set, OPTIONSMENU gateways, `reachesMop` per component, and `targetCount`. `MOP_DATA` stays the load census. The strict package check (`MopData.java:304`) reads `spec.staticData().strictPackageMatch()` (D-B14.3)
- [x] 5.2 `StaticGuiModel` replaces the delegation of 3.5 with its own state. `MopData` drops the WTG, the widget metadata and the components it no longer owns. `MopData.recomputeMopOptionsMenus` reads the WTG through `StaticGuiModel`
- [x] 5.3 `agent/scoring/FrontierPass` → `NavFrontierPass`: it reads `StaticGuiModel` only, writes `ModelAction.navFrontierBoost` (added in 3.8b), and is mounted iff `NAV_FRONTIER`. `ModelAction` loses `wtgSource`/`markWtgSource` (P3)
- [x] 5.4 `ScoringPipeline.fromParams`: each pass is mounted by its feature (`COVERAGE_BOOST`, `FORM_COMPLETION`, `NAV_FRONTIER`, `WTG`, `MOP_FRONTIER`, `MENU_GATEWAY`, `MOP`), not by `data != null`. R1 audit: every pass computes its term without reading `getPriority()` or any other group's state, and adds it to its own field. `MenuGatewayPass` no longer reads the BACK/MENU cap (`menuPickEligible`, D-B14.19). `ScoringPassIndependenceTest` (new) checks that the chain order does not change any field
- [x] 5.5 `TypedInputGenerator` reads `StaticGuiModel` (its lookup helper in `ApeAgent.java:235-250` is F5's, 8.5); `MopScorer` and `MopContext` read `MopData` for targets and `StaticGuiModel` for WTG edges
- [x] 5.6 Re-derive the two fixtures with the `data` member, once F7 has landed the derive change, through `openspec/changes/archive/2026-10-09-llm-coordinate-single-base/evidence/derive_fixtures.py <rv-android root>` (`python3 -I -B`). Until then, add a hand-written `data` case to a parser unit test only
- [x] 5.7 Tests: `StaticGuiModelTest` (new; `data` parsed, absent `data` = none); `MopDataTest` updated; `NavFrontierPassTest` (no `MopData` needed)
- [x] 5.8 Run `/sdd-doc-code` on `utils/StaticGuiModel.java` and `utils/StaticArtifactLoader.java`, then `/sdd-test-run`

## 6. Wave 1 · F3 — SATA and extension points (§4.3, D-A7, D-B4, D-B10, D-B12)

Owns: `agent/SataAgent.java`, `model/State.java`, `agent/sata/*` (implementations), their tests
(`SataAgent*Test`, `State*Test`, `SataExtensionPointTest` new).

- [x] 6.1 X1, `agent/sata/ScorerSumLeastVisitedTie`: `State.greedyPickLeastVisited` delegates its tie to `LeastVisitedTie`. R2 compares the sum of the scorer fields (`mop`, `mopf`, `wtg`, `navf`, `cov`, `menu`, `form`); with zero scorers the incumbent stays. Delete `beatsLeastVisited`'s priority flag and its reads (`SataAgent:586,594`)
- [x] 6.2 X3, `agent/sata/ApeAbaTargetTie` (never replace on a tie, no draw) and `agent/sata/MopAbaTargetTie`, used in `selectNewActionEarlyStageForABAInternal` (`:1081-1097`). X4, `agent/sata/ApeTrivialPathPick` (always `RandomHelper.randomPick`) and `agent/sata/MopTrivialPathPick` (density, with equal densities falling back to `randomPick`), used at `:1339-1367`. The `SataAgent` constructor mounts the MOP variants iff `MOP` is in the plan; the `getMopData() != null` tests at these sites are deleted
- [x] 6.3 Delete the MOP shortcut from `SataAgent`: `selectUnvisitedMopTarget` (`:574`), the 0-step pick (`:1500`), `pickCappedMopTarget`, `pickBestMopTarget`, `mopTargetPicks` and the MOP preference in the EARLY_STAGE roulette. The ranking moves to F4's `MopCoinStage`
- [x] 6.4 Telemetry at the SATA sites, through the wave-0 sink methods:
  - [x] 6.4.1 `dec.strat` for every path of the selection table (design.md), replacing the `sata_other` collapse;
  - [x] 6.4.2 `dec.draw` at the EARLY_STAGE, ε and null-handler roulettes;
  - [x] 6.4.3 `dec.nav` when X1, X3 or X4 decided a tie;
  - [x] 6.4.4 `dec.bmc` from the BACK/MENU cap filters;
  - [x] 6.4.5 `dec.cfc` at the roulettes and the least-visited pick, through `ScorerCounterfactual`, for each mounted scorer with a non-zero field in the candidate set
- [x] 6.5 The submit exclusion (`:557-560`, `:1477-1487`) applies only when `FORM_COMPLETION` is in the plan (P-6). The BACK/MENU cap is read through `BACK_MENU_CAP`. `dec.ch` keeps its channels, without `SHORT_CIRCUIT_*` (D-B14.17). Delete `attributeByLargestBoost` (`:317-348`): `dec.src` is the owner the pipeline names (`sata` for this agent's own picks)
- [x] 6.5b `dumpCoverage` (`:369-372`): `mopReach` is written only when `MOP` is mounted, and is omitted otherwise (D-B14.21)
- [x] 6.6 Tests (`SataExtensionPointTest`):
  - [x] 6.6.1 X1 with zero scorers equals upstream's first-of-array;
  - [x] 6.6.2 X3 APE keeps the incumbent;
  - [x] 6.6.3 X4 APE consumes exactly one helper draw when there is more than one path;
  - [x] 6.6.4 the MOP variants decide as specified;
  - [x] 6.6.5 the shortcut is gone (no `SHORT_CIRCUIT_*` channel)
- [x] 6.7 Run `/sdd-test-run`

## 7. Wave 1 · F4 — decision, coins, gates, guards (§4.2, §4.4, §4.5; D-A4–D-A6, D-A10, D-A15–D-A17, D-B7–D-B11)

Owns: `agent/pipeline/*`, `llm/LlmCircuitBreaker.java`, `llm/LlmClient.java`, their tests.

- [x] 7.1 `DecisionPipeline.Candidate` order: `BUDGET`, `RESTART`, `LLM_NEW_STATE`, `LLM_STAGNATION`, `LLM_COIN`, `MOP_LAUNCHER`, `COMPONENT_TRIGGER`, `MOP_COIN`, `SATA_CHAIN`. Each candidate is mounted by its feature (`MOP_LAUNCHER` by the renamed feature); `onStepOpen` is broadcast to every mounted stage at step open. The pipeline writes `dec.src` (the selecting stage's owner) and `dec.own` on **every** step, whatever is mounted (`i` = the first claimer, D-B14.6); the guard stages set `dec.ch` `budget`/`restart` (D-B14.17) and the owner values of 3.8b (`BudgetStage:74-76`, `LlmGate:65`, `MopLauncherStage:160`)
- [x] 7.2 `MinActionsGate` (admits `size() >= min`) and `BreakerGate`, composed from `LlmParams` (a gate with parameter 0 is not mounted) and shared by the three LLM stages; a step lost to a gate counts `gateMinActions`/`gateBreaker`. The buffer condition is deleted from the LLM path. **At most one LLM call per step** (D-B8): once an LLM stage has called, the later LLM stages return `Continue` without calling. `LlmCircuitBreaker` and `LlmClient` (`:57`, today the default constructor) read `ape.llmBreakerFailures` and `ape.llmBreakerOpenMs` from `LlmParams`
- [x] 7.3 `LlmRandomStage` → `LlmCoinStage`: it reads `u_L` from `StepRng` (drawn at step open), checks the gates, then calls the engine. Whenever the LLM is mounted it records the LLM part of `dec.own` (`trg`, `uL`, the gate that barred, the fallback reason); a won coin counts `coinLlm`. The new-state and stagnation stages are unchanged except for using the gate chain and recording `trg` (D-A17)
- [x] 7.4 `RestartStage` (guard), mounted iff `ape.checkRestart`: when `collaborators.restartDue()`, it selects `collaborators.restartAction()` with `src=restart` and `dec.restart` set to the kind
- [x] 7.5 `MopLauncherStage`: `stepsSinceFiring` advances in `onStepOpen`, and the stage fires when the count is at or past the cadence and the stage is reached (D-B11). It reads the activity list, main activity and package through `ctx.staticModel()` and the distances/MOP activities through `MopData` (today `:138-157` reads all of them from `MopData`). Each launch increments `launches`
- [x] 7.6 `MopCoinStage` (new), as in D-B10: eligibility, `u_M < p_M`, then the ordering weight → unretired target count (distance) → `getActions()` order, and the submit exclusion when `FORM_COMPLETION` is mounted. It records `dec.own`, `dec.mopd`, `dec.mops` and `dec.ch:mop_coin`, and `coinMop`. `MopInteractionLedger` comes through `collaborators.mopLedger()`
- [x] 7.7 `ComponentTriggerStage`: the coin comes from `StepRng.componentCoin`, and the stage is mounted by `COMPONENT_TRIGGER` (now STATIC). It reads its pool through `ctx.staticModel()`, not `ctx.mopData().hasComponents()` (`:69`, null in `as`), and `DecisionPipeline` passes `spec.staticData().componentPercentage()` (today `spec.mop()`, `:178`)
- [x] 7.8 Every preempting stage (the three LLM stages, the launcher, the MOP coin, and Budget, D-B14.13) calls `collaborators.abandonBuffer(owner)` before it selects, whenever the buffer is non-empty
- [x] 7.9 Tests:
  - [x] 7.9.1 `LlmGateTest`: MinActions at 2/3/4 actions and the breaker; one LLM call per step (a failed new-state call is not followed by a coin call);
  - [x] 7.9.2 `LlmCoinStageTest`: the coin is drawn every step even when a gate bars;
  - [x] 7.9.3 `MopCoinStageTest`: eligibility, ordering, `p_M=0`, submit exclusion;
  - [x] 7.9.4 `MopLauncherStageTest`: LLM steps advance the cadence;
  - [x] 7.9.5 `RestartStageTest`;
  - [x] 7.9.6 `PipelineParityTest`: order and mounting per cell
- [x] 7.10 Run `/sdd-doc-code` on each new stage and gate, then `/sdd-test-run`

## 8. Wave 1 · F5 — agent (P-2, P-4, P-6, D-B10, D-B13)

Owns: `agent/StatefulAgent.java`, `agent/ApeAgent.java`, `agent/FormCompletion.java`, their tests
(`StatefulAgentTriggerTest`, `ApeAgentLlmInputTest`, `FormCompletionTest`, …).

- [x] 8.1 Restart as a guard: implement `restartDue()` / `restartAction()` (stability flags from step 2; periodic threshold with the previous step's `disableRestart`), and skip the post-selection `checkRestart` wrapping in `ApeAgent.java:363` for agents that have a pipeline. `RandomAgent` and `ReplayAgent` keep it (D-B14.12). The `disableRestart` latch is read and cleared by `RESTART` (D-B14.16). The restart kind feeds `dec.restart`, and `restarts` counts each one
- [x] 8.2 `abandonBuffer(owner)` clears the buffer **without** `weakenStateTransition`, records `dec.buf`, and counts `bufAbandoned`. A real path failure keeps `clearBuffer` with weakening
- [x] 8.3 Target retirement composes on `MopInteractionLedger` (`moveForward`, `pendingRetirement`). `mopLedger()` exposes the ledger; it exists iff `MOP` is mounted
- [x] 8.4 `recordModelDecision`: `dec.src` is the executed owner (the setter at `:1264` uses the 3.8b values; the `PickChannel` switch at `:1300-1301` loses `SHORT_CIRCUIT_*`); the `navf` field; no `cf`. The `ACT.mop` dictionary bit is written only when `MOP` is mounted (D-B14.9)
- [x] 8.5 `dec.inp` at the text refiner (`ApeAgent.java:199-200`, `generateInputText`), with the origins `typed`, `heuristic`, `random`, `form` and `llm`. Typed text reads `StaticGuiModel` through the wave-0 API when `TYPED_FUZZ` is mounted; the lookup helper (`ApeAgent.java:235-250`) is this front's
- [x] 8.6 Addon F (P-6): the deterministic fill (`ApeAgent.java:199`, `StatefulAgent.java:305`) applies only when `FORM_COMPLETION` is mounted. The submit candidate (`agent/FormCompletion.java`) is chosen by lone button or submit word, without reading `mopBoost` (D-B14.10)
- [x] 8.7 Component triggering (D-B13):
  - [x] 8.7.1 `buildTriggerTuples` reads `StaticGuiModel` components without the `reachesTarget` filter (`:1355`, `:1374`), with a name-only tuple for each filterless service whatever its `targetMethods` (today `:1363` requires them, D-B14.15); provider tuples are kept;
  - [x] 8.7.2 `dispatchTrigger` sets data/type from the filter's `data` (`paths[0]`, falling back to `pathPrefixes[0]`, D-B14.11), takes the package from `StaticGuiModel` (today `getMopData().getPackageName()`, `:1445`, null in `as`), and records `sink().componentTrigger(kind, className)` (`dec.trig`);
  - [x] 8.7.3 the log line drops `reachesTarget=true`;
  - [x] 8.7.4 `SystemBroadcastCatalog` loads iff `COMPONENT_TRIGGER` is mounted (`:218`)
- [x] 8.8 The loader call site (`:204-206`) uses `StaticArtifactLoader` iff `STATIC` or `MOP` is mounted (D-B14.20), and hands each provider only to the mounted consumers
- [x] 8.9 Tests:
  - [x] 8.9.1 `BufferAbandonTest`: preemption leaves the edge weights unchanged, and a path failure still weakens;
  - [x] 8.9.2 restart guard;
  - [x] 8.9.3 `dec.inp` origins;
  - [x] 8.9.4 form fill off without `FORM_COMPLETION`;
  - [x] 8.9.5 trigger intent data/type for scheme-only, MIME-only and both
- [x] 8.10 Run `/sdd-test-run`

## 9. Wave 1 · F6 — prompt by contributions (§4.6, D-A3, D-A18 Q-8)

Owns: `llm/ApePromptBuilder.java`, `llm/MopPromptContribution.java` (new), `llm/StaticPromptContribution.java`
(new), `llm/LlmEngine.java`, the LLM composition block of `runtime/RunContext.java` (`:155-166`, after 3.2b),
their tests.

- [x] 9.1 `MopPromptContribution`: the `[DM]`/`[M]` marker (`:472-504`), the `N/M MOP.` count and the `MOP NAVIGATION` section (`:619-653`, `:830-832`), and the MOP sentences of every system prompt (`:223`, `:245`, `:262`, `:281`, `:283`, `:292`, and the user-text closing lines `:763` and `:835`), through the helper call sites `:371`, `:416`, `:740`, `:798`, `:863`. The v17 exploration block keeps its visit part in every cell: `MOP NAVIGATION:` with MOP (unchanged), `NAVIGATION:` without (verification A8). `StaticPromptContribution`: the widget metadata (`:515-541`, `:748`, `:818`), read from `StaticGuiModel`
- [x] 9.2 `ApePromptBuilder` assembles its text from the list of mounted contributions. It never tests `mop != null`, and the MOP-free wording of each sentence is the one in the llm-prompt delta. `RunContext` (`:164`) builds the builder with the contributions of the mounted groups (MOP iff `MOP`, STATIC iff `STATIC`), and `LlmEngine.selectAction` stops taking a `MopContext` from the stages (F4 drops the `ctx.mopContext()` argument at `LlmRandomStage:85-86`, `LlmNewStateStage:74-75`, `LlmStagnationStage:111-112`, against the signature fixed in 3.4)
- [x] 9.3 Tests:
  - [x] 9.3.1 with no contribution, no `[DM]`, `[M]`, `MOP` or `monitored` token appears in any variant's system or user text;
  - [x] 9.3.2 with MOP, the prompt equals today's for v13 and v17 on the existing fixtures;
  - [x] 9.3.3 with STATIC alone, the metadata appears and the markers do not;
  - [x] 9.3.4 v17 without MOP carries `NAVIGATION:` and `NEW state.`/`Visited Nx.`, and the `SCREEN … elements tested | visits:` line
- [x] 9.4 Run `/sdd-doc-code` on both new contributions, then `/sdd-test-run`

## 10. Wave 1 · F7 — rv-android sister change (owned by the `aperv-tool` session)

Change `isolate-guidance-groups` in rv-android (worktree
`/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/worktrees-aperv/isolate-guidance-groups/rv-android`).
Cross-reference: its `tasks.md` (wave 0 group 0; wave 1 fronts T, D, R, A, M, W; wave 2 group I; wave 3 group S; wave 4 group C).

- [ ] 10.1 The six variants = cells, with the ten old variants deleted and no alias (D-A13, P-7) — rv-android T
- [ ] 10.2 `APERV_PROPERTY_MAPPING` (P-8): the new keys; the four missing keys; the retired keys removed; `ape.staticDataPath` is a fixed line, not a mapping entry — rv-android T
- [ ] 10.3 (rv-android T) Push rule: the artifact goes iff STATIC or MOP; `system-broadcast.json` iff `COMPONENT_TRIGGER`; `ape.variant` is always passed
- [ ] 10.4 (rv-android D.1–D.4; D.4 sends the SHA that unblocks 5.6 and 11.7) The derive keeps the reduced intent-filter `data` member (`schemes`, `hosts`, `paths`, `pathPrefixes`, `mimeTypes`; D-B13, D-B14.11), and the artifact carries every component regardless of `reachesTarget`
- [ ] 10.5 (rv-android R, A) `analysis/trace_ndjson.py` (P-9): the new `dec` members, `mopd`/`mops`/`ret`, `RUN_END`, and the documentation of `decision_source` and `Counterfactual`
- [ ] 10.6 (rv-android M.4 offline, waits on 11.6; S.2–S.4 on device, with 12.1) Gate: each variant's `RUN_START` matches the fixture of 11.6. rv-android I.2 re-checks against the 3.10 SHA; S.1 receives the jar from 11.9

## 11. Wave 2 — integration (serial)

- [x] 11.1 Merge the six wave-1 worktrees into `isolate-guidance-groups`, in the order F1, F2, F6, F5, F3, F4, resolving only import or signature seams
- [x] 11.2 Delete the four old presets (`aperv`, old `mop`, `llm`, `llm_mop`), their goldens and their `OracleScaffold.Preset` values. `OracleScaffold` takes a cell instead. Rewrite the tests that name them: `ParityOracle{Aperv,Mop,Llm,LlmMop}Test`, `PresetsTest`, `RunSpec*Test`, `FeatureDerivationTest`, `DecisionPipelineFromSpecTest`, `HardPreemptionTest`, `PipelineFixture`, `StageProvenanceLabelTest`, `EgreedySeamTest`, `OracleDriverTest`, `OracleScaffoldTest`, `PreemptionGoldenTest`, `ScriptedLlmTest`, `SinkNeutralityTest`. `OracleSataAgent` overrides `triggerComponent()` as a recorded no-op (D-B14.5; dispatch needs a device). Delete the obsolete enum values of 3.8b (`SHORT_CIRCUIT_*`, the boost-attributed `DecisionSource` values) and `CounterfactualFieldTest`'s cases for them
- [x] 11.3 Capture new goldens on purpose, one per cell (`src/test/resources/goldens/<cell>/baseline.ndjson`, plus the preemption golden), through the deliberate-regeneration path; review the diffs
- [x] 11.4 `SinkNeutralityTest`: one case per cell, including `as`, the real control; the new sink calls are reached
- [x] 11.5 `CleanControlTest` (new): replays of `base`, `as` and `as_llm` carry no `mop`, `mopf`, `wtg`, `menu`, `mopd`, `mops`, `ret`, `mopx`, `own.uM`, MOP key of `cfc`, `nav.c=mop`, `src=mop`, `src=launcher`, `ch=mop_coin`, `ACT.mop` or UICOV `mopReach`, and their `coinMop`, `launches` and `retired` are 0. `RngNeutralityTest` (new): unmounting `MinActionsGate`, or the MOP coin at `p_M=0`, leaves SATA's choice sequence identical on a fixed script
- [x] 11.6 Base parity: the `base` cell matches upstream's X1/X3/X4 behaviour on the oracle fixtures (`ParityOracleBaseTest`). Write the cell `RUN_START` fixtures `src/test/resources/cells/<cell>.run_start.json`, with volatile fields masked, and send them to `aperv-tool` (F7 10.6)
- [x] 11.7 Re-derive the APE fixtures with F7's derive (5.6), if not done
- [x] 11.8 `mvn clean test` green; then `mvn package`. Record the jar's sha256 and `BuildInfo.GIT_SHA`
- [x] 11.9 `mvn install -Drvsec_home=/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/worktrees-aperv/isolate-guidance-groups` (the worktree root, as the sister's S.1 expects; never the shared `rvsec` tree, which is on `modules` with other sessions' work). The F7 module tests pass against this jar
- [x] 11.10 Update `CLAUDE.md`: groups, keys, cells, telemetry members, rotation fix, removed presets (P4, current state)
- [x] 11.11 **Commit wave 2** (explicit paths; ask the author)

## 12. Wave 3 — device smoke (the mini experiment; author decision 2026-10-17)

The smoke no longer runs on `cryptoapp`: it is the `mini-experimento` session's short round and mini experiment on
the Study 03 v2 corpus (instrumented APKs + compact `.apk.json`, image `phtcosta/rvandroid:0.9.5` with this jar and the
`aperv-tool` sources bind-mounted). That session runs it; the evidence comes from it and is recorded here.

- [x] 12.1 Hand `mini-experimento` (and `aperv-tool`) the jar of 11.9 (path, sha256, `GIT_SHA`) and the fixtures of 11.6. From its short round, record in `openspec/changes/isolate-guidance-groups/evidence.md` that each cell's `RUN_START` (`preset`, `variant`, `features`, `params`, `inert`, `digest`) matches the cell fixture and that the new `dec` members appear
- [x] 12.2 Rotation on a real landscape APK (author decision 2026-10-17): in the short round, one run of `retrowars` on `as_mop_llm` with `ape.llmGateMinActions=0` (a gate at 0 is not mounted, 7.2), because its opaque screen offers only BACK/MENU and the default gate never admits a call. `dec.rot` is 1 or 3 on the LLM steps, and the LLM sub-event's screenshot is in the displayed orientation (width > height, upright). The experiment itself keeps the default gate. Record it in `evidence.md`. Letting the LLM act on opaque screens (`DynamicRegionGate`) stays out of this change
- [ ] 12.3 Hand the jar sha256 and `GIT_SHA` to the Study 03 session as well. Q-9 and Q-10 are the author's

## 13. Amendment after `e03igg` (author decisions 2026-10-18; design D-B15…D-B19)

Any code here changes the jar `e03igg` measured (`a2fde6c`): new jar, goldens moved only where a decision moves
them, and a new smoke whose scope is the author's. BASE stays upstream APE: a cell without the owning group keeps
its behaviour; its trace changes only by `RUN_END.counters.nullRelaunches:0`.

<!-- Dispatch (author 2026-10-18: plan for parallelism), same discipline as waves 0–2:
     - 13.1–13.3 are serial, one agent: the evidence and the contracts every front codes against. They end green
       (`mvn clean test`) and the contract diff goes to `aperv-tool` at 13.3, so the rv-android side starts in
       parallel with the fronts.
     - 13.4–13.8 are fronts F8–F12, one agent and one worktree each (`isolation: "worktree"`), all in parallel.
       File ownership is disjoint; a front edits only the files its task names (and their tests).
     - 13.9–13.11 are serial: merge F8, F9, F11, F12, F10 (in this order; F10 and F12 both touch MOP scoring/selection, F10 last), gates, package, install.
     - Critical path: 13.1 → 13.2 → 13.3 → {13.4 ∥ 13.5 ∥ 13.6 ∥ 13.7 ∥ 13.8} → 13.9 → 13.10 → 13.11 → 14. -->

### 13.A Contracts (serial)

- [x] 13.1 Record in `evidence.md` the `e03igg` measurements behind D-B15…D-B19 (launcher firings and eligibility, the visited-relaunch opportunity, wrong-process kills by arm, retirement distribution and batch retirement, the ledger replay, `mini-experimento`'s retirement × reach and re-tap yield) with `[verified]`/`[reported]`
- [x] 13.2 Contracts, with their unit tests: `runtime/{Feature,KeyOwnership,RunSpec}` the `MOP_COIN` key `ape.mopCoinLedgerMaxInteractions` (int, default 1, `< 1` clamped to 1 in `RunSpec.clamp`, like the other plan clamps), accessor on `MopParams`, inert without `MOP_COIN`; the retired key `ape.mopTargetPickCap` gets the reason "replaced by `ape.mopCoinLedgerMaxInteractions`, the MOP coin's budget of picks per activity and widget (default 1)" (`KeyOwnership`); `telemetry/{RunCounter,RunCounters,NdjsonSink}` `NULL_RELAUNCHES` → `RUN_END.counters.nullRelaunches`, written in every cell; `telemetry/{EventSink,StepRecord,NdjsonSink,NoopSink}` the `dec.hnd` member (one more argument of the decision call, written iff `mops` is `s`), call site passing `null` until F10; `llm/LlmEngine.FALLBACK_SCREENSHOT = "screenshot"` (constant only); `utils/MopData.Lookup.handler` (the stamped class, set in `lookup` on the `STAMP` branch, null otherwise); `agent/scoring/ScoringContext.getMopLedger()` (the run's ledger, null without `MOP`; a `default` method returning null, so the five test contexts compile unchanged), provided by `StatefulAgent`. Recapture on purpose only the fixtures and assertions these move (the goldens hold no `RUN_END` and no digest: `nullRelaunches:0` moves `NdjsonSinkTest`/`CleanControlTest` assertions; `RUN_START.params` omits defaults, so only the digest of `cells/{mop,as_mop,as_mop_llm}.run_start.json` moves); `mvn clean test` green
- [x] 13.3 Send `aperv-tool` the contract v3.3 diff (one key, `fb:"screenshot"`, `dec.hnd`, `nullRelaunches`, the new retired-key reason, `dec.mopx` unchanged) for its mapping and trace reader, and the regenerated `cells/*.run_start.json`

### 13.B Fronts (parallel, one worktree each)

- [x] 13.4 F8 — `agent/pipeline/MopLauncherStage` (D-B15): the set of launched activities (episode state, INV-DP-07), filled at selection (before the launch counter increments), and the walks see launched activities as visited; eligibility gains "never launched in this run" in both modes; no other file. Tests: a launched activity that stays unvisited is not selected again under `flag` and under `distance`; with no other eligible activity the firing is `ineligible` and consumes no budget; the launch result is never read (INV-CT-14)
- [x] 13.5 F9 — `../MonkeySourceApe` (D-B16): with `MOP_LAUNCHER` in the plan, the null-tree recovery calls `mAgent.onActivityStopped()` and `restartPackage(getMainApp(), false, "null tree")` (not `randomlyPickMainApp`) instead of `stopTopActivity()`, counts `NULL_RELAUNCHES`, and queues `NOP` instead of `ACTIVATE` on that step; without it the path is upstream's, byte for byte. Tests: the recovery choice by plan (a pure helper), the counter by cell
- [x] 13.6 F10 — `agent/{StatefulAgent,MopInteractionLedger}`, `agent/pipeline/MopCoinStage` (D-B17): `MopInteractionLedger` counts executions (`count`/`tried`/`belowBudget`/`record`) and is constructed with k (`spec.mop()`'s accessor, StatefulAgent); `recordMopInteraction` records every execution and counts retirement only on a key's first; the coin is eligible while `belowBudget` (`count < k`), with no change to `DecisionPipeline`; the decision call passes `Lookup.handler` as `dec.hnd`. Tests: k = 3 lets the coin take a key three times whatever the owners; retirement counts once; k = 1 reproduces D-B10 (this front's goldens unchanged)
- [x] 13.7 F11 — `llm/LlmEngine` (D-B18): a null capture counts `screenshot_failed`, sets `FALLBACK_SCREENSHOT` and returns null without `recordFailure`. Tests: the breaker's failure count and state unchanged by capture failures; a `HALF_OPEN` probe not reopened by one; `dec.own.fb:"screenshot"` on the step
- [x] 13.8 F12 — `agent/scoring/MopWidgetPass` (D-B19): an action whose key `tried` holds gets no boost and leaves the tie split; its `mopWeight` is still stored; `mopExposure` keeps counting the weighted actions, tried or not (author 2026-10-18), while the log line's `boosted` counts the boosted ones. Tests: the scenario "a tried key gets no boost and leaves the split" in both modes; an untried action is boosted as before

### 13.C Integration (serial)

- [x] 13.9 Merge F8, F9, F11, F12, F10 into `isolate-guidance-groups`, resolving only import or signature seams; `CleanControlTest` adds `hnd`; recapture on purpose only the goldens the fronts move (expected: none from D-B19 or D-B18, since the oracle enters below `adjustActionsByGUITree` (INV-ARCH-12) and `ScriptedLlm` takes no screenshot; at most a MOP golden whose fixture launches an activity that does not open (D-B15); review every diff); `mvn clean test` green
- [x] 13.10 Update `CLAUDE.md` (P4: the launcher's once-per-run rule, the launcher-owned recovery, the boost only for untried keys, the coin key `ape.mopCoinLedgerMaxInteractions`, `dec.hnd`, `fb:"screenshot"`, `nullRelaunches`)
- [x] 13.11 `mvn package` + `mvn install` to the aperv worktree; record sha256 and `GIT_SHA` in `evidence.md`; send them to `mini-experimento` and `aperv-tool`; ask the author before the commit

## 14. Verification

- [ ] 14.1 Run `/sdd-qa-lint-fix` on `src/main/java/com/android/commands/monkey/ape`; delete the dead `Config.activityTriggerStagnationStep`/`activityTriggerMaxPerRun` (`Config.java:127-133`, readers only in `ConfigTest`; the plan owns both, P3; author 2026-10-18)
- [ ] 14.2 Run `/sdd-verify`
- [ ] 14.3 Invoke `/sdd-code-reviewer` via the Skill tool
- [ ] 14.4 Run `/sdd-docs-sync` (`CLAUDE.md`, `openspec/specs` Purpose sections). At archive, hand-sync the sections `openspec archive` does not apply (verification V57): the Purpose, Data Contracts and Invariants preambles of action-selection (`:10`), component-triggering (`:7`, `:18`), mop-guidance (`:5`, `:896-897`), parity-oracle (`:19`, `:36-43`, `:63`), run-spec (`:6`), wtg-navigation (`:28`), exploration (INV-EXPL-06, INV-EPS-02), llm-routing (`:35` and the *Error* bullet `:43`, which INV-RTR-27 replaces), ui-tree (INV-TREE-02, INV-TREE-11) and every delta's Invariants block
- [ ] 14.5 `openspec validate isolate-guidance-groups --strict`; `/opsx:verify`
- [ ] 14.6 Merge into `master` **only** on the author's explicit word after the mini experiment (D-A19); no push without it
