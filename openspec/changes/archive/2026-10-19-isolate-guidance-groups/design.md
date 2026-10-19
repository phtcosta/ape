## Context

**Label: MEASUREMENT** (`proposal.md`). Source of every decision: `docs/plans/20261017_isolamento_grupos_aperv.md`
(the plan, in Portuguese). Decisions D-A1…D-A19 are the author's (2026-10-17) and are not re-opened here.
D-B1…D-B14 below are the design decisions of this change: D-B1…D-B4 and D-B13 were decided by the author on 2026-10-17 in
the session that created the change. The author then approved D-B5…D-B14 the same day: the MOP coin's distance-based
ordering without priority and the retirement of `mopTargetPickCap`; the restart guard reading the previous step's
`disableRestart`; the telemetry changes (`dec.src`, `cfc`, `navf`, `v=2`); the two ADDONS×MOP decouplings; Budget
abandoning the buffer without weakening; the breaker keys; the MOP-free prompt wording; and the full component pool.
The verification session of the same evening (`verification-artifacts.md`, author decisions A1–A10) amended
D-B6, D-B8, D-B12, D-B14.5, D-B14.6, D-B14.8, D-B14.14, D-B14.17 and D-B14.18, renamed `ACTIVITY_TRIGGER` to
`MOP_LAUNCHER`, and added `dec.trig`; the key and telemetry contract is v3.2.

Paths are relative to `src/main/java/com/android/commands/monkey/ape/` unless they say otherwise. Facts marked
`[verified]` were read with `file:line` while this change was written; `[reported]` facts come from the
exploration session's subagents (plan §2).

### Where the problems were first detected

**Arm 2 ("APE-RV unguided") is not MOP-free and consumes static-analysis data:**
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/docs/posthoc/e6-fork/report.md`
  §8.2 `:262-272`, §8.3 `:274-281`, §8.4 `:283-288`
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/docs/handoff/20261017_e6_article_liaison_prompt.md:169-180`
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/docs/handoff/20261015_ape_fork_prompt_1.md`
  (`:26`, `:54`, `:94`, `:222`)
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/openspec/changes/archive/2026-10-15-e6-campaign/protocol.md:109-118, :893-897`
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/doutorado-tese/docs/estudo-03/escrita/esqueleto/s4/s4-5.md`
  (arm-2 trace, A/A erratum, DF-9)
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec/rv-android/docs/20260714_cmpma.md:214-265`

**The LLM screenshot is captured at rotation 0:**
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/openspec/changes/archive/2026-09-28-e5c-opaque-screen/decision.md:164-168`
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/HISTORY.md:474`
  (and `:785-797`, a different artifact)
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/openspec/changes/archive/2026-10-15-e6-campaign/protocol.md:610, :926`
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/docs/tmp/e6-fork/dynamic-apks/plan_v3b.md:486-495`
- `/home/pedro/.claude/projects/-pedro-desenvolvimento-workspaces-workspaces-doutorado-workspace-rv-rvsec-study03-replication-package/memory/v3b-pending-2026-10-16.md`
  (item 1)
- branch `llm-opaque-screen`: `openspec/changes/llm-opaque-screen/design.md` D13 (`1b41c865`),
  `openspec/changes/llm-opaque-screen/evidence.md`, fix `f993081f` + `3b35073a`, device re-test `59d19f0c`

**The LLM dose and the MOP decision share:**
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/experiments/E6-campaign/results/mechanism/llm-arm/funil_passos_llm.out`
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/doutorado-tese/docs/estudo-03/escrita/esqueleto/20261015_fatos_e6v2.json`

### Current state

`[verified]` unless marked otherwise:
- **Features.** `runtime/Feature.java` has roots `MOP` (`ape.mopDataPath` PRESENT, `:66`) and `LLM` (`ape.llmUrl`
  PRESENT, `:68`), addon roots (`MODEL_MENU`, `FORM_COMPLETION`, `LEAST_VISITED_TIEBREAK`, `TREE_ENHANCEMENTS`,
  `ACTIVITY_BUDGET`, `DYNAMIC_EPSILON`, `HEURISTIC_INPUT`, `TYPED_FUZZ`, `FOREIGN_ACTIVITY_GUARD`,
  `TREE_PACKAGE_GUARD`, `COVERAGE_BOOST`, `FUZZING`), and a MOP family (`WTG`, `MENU_GATEWAY`, `FRONTIER`,
  `MOP_FRONTIER`, `ACTIVITY_TRIGGER`, `COMPONENT_TRIGGER`, `MOP_ACTIVITY_SOURCE`) that all require `MOP`
  (`:194-197`). `ACTIVITY_TRIGGER` mounts only `MopLauncherStage` (`agent/pipeline/DecisionPipeline.java:72`);
  `MopParams.activityTriggerEnabled()` has no caller (`runtime/RunSpec.java:974-975`). `RunSpec.deriveFeatures` (`:279`) leaves out silently an active-by-default feature whose
  dependency is missing, and raises `MISSING_DEPENDENCY` (`:302`) only when its activation key is explicit.
- **Presets** (`runtime/Presets.java`): `aperv` (`:112-133`), `mop` (`:137-143`), `llm` (`:147-150`),
  `llm_mop` (`:98-101`).
- **Pipeline** (`agent/pipeline/DecisionPipeline.java:61-76`): Budget → LlmNewState → LlmStagnation → LlmRandom
  → MopLauncher → ComponentTrigger → SataChain, mounted by feature. `LlmGate.allows` (`LlmGate.java:52-53`) is
  `actionBufferSize() == 0 && getActions().size() > 2`, its javadoc says "three actions or fewer" (`:40-50`).
  `LlmRandomStage.java:81-82` draws the coin from the agent RNG only after the gate passes. A stage whose call
  returns null continues the chain (`LlmNewStateStage.java:72-77`), so one step can call the LLM up to three times.
  `MopLauncherStage.java:126-130` advances its cadence only on the steps that reach it.
- **Restart** is applied after selection, in `ApeAgent.java:363`
  (`checkInput(checkFuzzing(checkRestart(updateStateInternal(...))))`). By then `recordModelDecision` has
  already written `dec`, so a restart leaves `dec.a` naming the replaced action.
- **The MOP shortcut** has two sites in `SataAgent`. One is in the epsilon rung (`selectUnvisitedMopTarget`,
  `:574`). The other is the EARLY_STAGE 0-step pick (`findGreedyActionForward`, `:1500`). Both go through
  `pickCappedMopTarget` (`:892-915`) and `pickBestMopTarget` (`:656-699`).
- **MOP tie-breaks inside navigation:** the ABA target (`:1081-1085`, `:1093-1097`) and the trivial-activity
  path (`:1339-1367`).
- **Buffer abandonment.** `StatefulAgent.clearBuffer` (`:562-568`) weakens the **next planned** edge before it
  clears the buffer.
- **Scoring passes** (`agent/scoring/ScoringPipeline.java:72-83`) are created in a fixed order and switched on
  by weight or data, not by feature. `FrontierPass` and `WtgPass` both write `wtgBoost`; `wtgsrc` tells them
  apart (`model/ModelAction.java:355-372`).
- **Config reads.** Several addon read sites go through `Config` instead of the plan:
  - `modelMenuEnabled` (`model/State.java:78`);
  - `treeEnhancementsEnabled` (`tree/GUITreeNode.java:218,594`, `tree/GUITreeBuilder.java:444`);
  - `heuristicInput` (`agent/ApeAgent.java:252`);
  - the two guards (`MonkeySourceApe.java:818,837`);
  - `mopActivitySourceComponents` (`utils/MopData.java:207`).
  
  A group switch that only removes the feature from the plan would leave these sites on. BASE keys are read the
  same way: `Config.doFuzzing`/`fuzzingRate` (`MonkeySourceApe.java:876`) and `Config.checkRestart`
  (`agent/ApeAgent.java:280`). The component path reads MOP data at `StatefulAgent.java:1445`
  (`getMopData().getPackageName()`), `agent/pipeline/ComponentTriggerStage.java:69` (`ctx.mopData()`) and
  `agent/pipeline/DecisionPipeline.java:178` (`spec.mop().componentPercentage()`); the launcher reads the
  components from `MopData` (`MopLauncherStage.java:138-157`).
- **Ownership seams.** `StageCollaborators` is implemented by `SataAgent` (`:67`), `StepContext` by
  `StatefulAgent` (`:88`). `RunContext` builds `LlmClient` (whose breaker uses the default constructor,
  `llm/LlmClient.java:57`), `LlmEngine` and `ApePromptBuilder` (`runtime/RunContext.java:155-166`).
  `DecisionSource` and `PickChannel` live in `model/ModelAction.java` (`:50-55`).
- **Keyless bug fixes.** The BASE list (plan §2.2) is in the `guidance-groups` delta ("Declared Consequences of the Cell Composition").
- **RNG.** There are two seeded streams `[verified]`:
  - the agent stream (`ApeAgent.getRandom()`, Monkey's `Random(seed)`): ε-greedy, the epsilon roulette, the
    LLM coin, the component coin;
  - the helper stream (`utils/RandomHelper`, seeded in `RunContext:142`): the EARLY_STAGE roulette,
    `randomPick`, `inputRate`, typed text, the restart thresholds and kind, and fuzzing
    (`MonkeySourceApe.java:877`).
- **Screenshot.** `llm/ScreenshotCapture.java:79-81` passes rotation `0`. `AndroidDevice.getRotation()`
  (`:122-128`) calls `IWindowManager.getRotation()`, which does not exist on API 30. The fix
  `f993081f` + `3b35073a` is on `llm-opaque-screen` only, and neither file has changed since `e93dea86`.
- **Telemetry.** `dec` carries only the winner; `cf` (`model/MopCounterfactual.java`) zeroes only `mopBoost` and
  `mopFrontierBoost`, at four `SataAgent` sites (`:584`, `:611`, `:1511`, `:1528`).

## Architecture

```
step N
 1. capture + safety guards (foreign activity, tree/package)                    [ADDONS M,N; unchanged]
 2. GUITree → Naming → State; graph update; refinement; trackers; restart flags [BASE]
 3. open StepRecord; draw u_L, u_M, the component coin and the fuzz toss       [P-3]
    (own streams)
 4. DecisionPipeline (fixed order, each stage mounted by feature):
      BUDGET          guard  (ADDONS I)                    owner=budget
      RESTART         guard  (BASE; J is ADDONS)           owner=restart
      LLM_NEW_STATE   trigger, no coin                     owner=llm   ┐ LlmGate chain
      LLM_STAGNATION  trigger, once per episode, no coin   owner=llm   │ (MinActions, Breaker)
      LLM_COIN        u_L < p_L                            owner=llm   ┘ error/decline → next
                      (at most one LLM call per step: after a call, later LLM stages skip)
      MOP_LAUNCHER    global cadence, fires here when due  owner=launcher
      COMPONENT       side effect only (STATIC, D-B13)     dec.trig
      MOP_COIN        eligible && u_M < p_M                owner=mop
      SATA_CHAIN      scorer chain → SATA rules + X1/X3/X4 owner=sata
 5. refiners: input text (typed / heuristic / random / form / llm)               dec.inp
 6. Monkey events + fuzzing; outcome resolved during step N+1                    [unchanged]
```

A preempting owner (LLM, launcher, MOP coin) that takes a step while the SATA buffer is non-empty abandons the
buffer **without** weakening (P-4, `dec.buf`). A real path failure inside SATA keeps weakening, as in APE.

```
      artifact (wire format 2; + the additive intent-filter `data` member, D-B13)
                          │  StaticArtifactLoader (iff STATIC or MOP is mounted)
             ┌────────────┴────────────┐
      StaticGuiModel                  MopData
  (WTG, widget metadata,         (targets, dist, handlers,
   package, mainActivity,         activityDist, MOP activities,
   components + intent filters)   reachesMop per component, menu gateways)
   STATIC: NavFrontierPass,             │
   TypedInputGenerator,          MOP: MopWidgetPass, WtgPass, MopFrontierPass,
   ComponentTriggerStage,             MenuGatewayPass, MopCoinStage, MopLauncherStage,
   StaticPromptContribution           MopAbaTargetTie (X3), MopTrivialPathPick (X4),
                                      MopPromptContribution
```

MOP reads both providers (plan P-1): `WtgPass`, `MopFrontierPass`, `MopScorer` and `MopLauncherStage` read the WTG
and the component list from `StaticGuiModel`, so the `mop` cell loads the artifact with STATIC off.

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `runtime/Feature` | groups as roots; members depend on their group; data root | plan keys | `Set<Feature>` |
| `runtime/Presets` | the six cell presets, compositions of group blocks | preset name | key map |
| `runtime/RunSpec` | resolution; new `StaticParams`; `variant` | files + CLI | `RunSpec` |
| `utils/StaticGuiModel` (new) | read-only WTG + widget metadata | parsed artifact | queries |
| `utils/MopData` | read-only MOP targets | parsed artifact | queries |
| `utils/StaticArtifactLoader` (new) | parses format 2 once, yields both providers | path | pair |
| `agent/pipeline/LlmGate` (interface) | Specification: `name()`, `admits(ctx)` | `StepContext` | boolean |
| `agent/pipeline/MinActionsGate`, `BreakerGate` (new) | G2, G3 | ctx | boolean |
| `agent/pipeline/LlmCoinStage` (renamed from `LlmRandomStage`) | the `p_L` coin | `u_L`, gates | Select/Continue |
| `agent/pipeline/RestartStage` (new) | the restart guard | restart due | Select restart |
| `agent/pipeline/MopCoinStage` (new) | MOP coin decider (D-A15) | `u_M`, ledger | Select/Continue |
| `agent/pipeline/MopLauncherStage` | global cadence (D-A16) | every step | Select/Continue |
| `agent/MopInteractionLedger` (new) | executions per (activity, `xpath|type`), and the coin's budget k (D-B17) | moves | queries |
| `agent/sata/LeastVisitedTie` (X1) | `ScorerSumLeastVisitedTie` (R2): larger scorer sum, else first | candidates | action |
| `agent/sata/AbaTargetTie` (X3) | `ApeAbaTargetTie`: keep; `MopAbaTargetTie`: MOP activity | two targets | boolean |
| `agent/sata/TrivialPathPick` (X4) | `ApeTrivialPathPick`: `randomPick`; `MopTrivialPathPick`: density | paths | path |
| `llm/PromptContribution` (interface) | system sentences, line marker, line detail, context | state, actions | strings |
| `telemetry/StepRecord` | new `dec` members | sink calls | NDJSON |
| `agent/StepRng` (new) | four streams derived from the seed | seed | `u_L`, `u_M`, component coin, fuzz |

## Mapping: Spec -> Implementation -> Test

| Requirement / invariant | Implementation | Test |
|-------------|---------------|------|
| guidance-groups: group mounting, INV-GRP-01..03 | `Feature`, `RunSpec.deriveFeatures` | `FeatureDerivationTest`, `GroupMountingTest` (new) |
| guidance-groups: cell presets, INV-GRP-04 | `Presets` | `PresetsTest`, `CellPresetTest` (new) |
| guidance-groups: data split, INV-GRP-05 | `StaticArtifactLoader`, `StaticGuiModel`, `MopData` | `StaticGuiModelTest` (new), `MopDataTest` |
| guidance-groups: plan reads only, INV-GRP-06 | every addon read site | `GroupReadSiteTest` (new) |
| run-spec: keys, retired keys, `variant` | `KeyOwnership`, `RunSpecEcho` | `RunSpecResolveTest`, `RunSpecAbortTest`, `RunSpecEchoTest` |
| decision-pipeline: order, owners, guards | `DecisionPipeline`, `RestartStage` | `PipelineParityTest`, `PreemptionGoldenTest` |
| decision-pipeline: own streams, INV-DP-13 | `StepRng` | `RngNeutralityTest` (new) |
| llm-routing: gates, coin, buffer, triggers | `LlmGate`, gates, `LlmCoinStage` | `LlmGateTest` (new), `LlmCoinStageTest` |
| mop-guidance: MOP coin, ledger, launcher | `MopCoinStage`, `MopInteractionLedger`, `MopLauncherStage` | `MopCoinStageTest` (new), `MopLauncherStageTest` |
| action-selection: X1/X3/X4 defaults | `agent/sata/*`, `State`, `SataAgent` | `SataExtensionPointTest` (new), `ParityOracleBaseTest` (new) |
| action-selection: abandon without weakening | `StatefulAgent.abandonBuffer` | `BufferAbandonTest` (new) |
| scoring-pipeline: R1, scorer per field | `agent/scoring/*` | `ScoringPassIndependenceTest` (new) |
| wtg-navigation: frontier on `StaticGuiModel` | `NavFrontierPass` | `NavFrontierPassTest` |
| llm-prompt: contributions | `ApePromptBuilder`, contributions | `ApePromptBuilderTest`, `PromptContributionTest` (new) |
| llm-infrastructure: rotation | `ScreenshotCapture`, `AndroidDevice` | `ScreenshotCaptureStageTest` |
| event-sink: new fields, completeness, purity | `StepRecord`, `NdjsonSink`, `EventSink` | `StepRecordTest`, `SinkNeutralityTest`, `CleanControlTest` (new) |
| heuristic-input / form-completion | `ApeAgent`, `StatefulAgent`, `SataAgent` | `ApeAgentLlmInputTest`, `FormCompletionTest` |
| ui-coverage, activity-budget, ui-tree | `SataAgent.dumpCoverage`, `StatefulAgent`, `tree/*` | `UICoverageTrackerTest`, `GroupReadSiteTest` |
| static-analysis-entrypoints, aperv-tool | rv-android sister change | its module tests |
| parity-oracle: base goldens | `OracleScaffold` (cells) | `ParityOracle*Test`, goldens per cell |

## Goals / Non-Goals

**Goals:**
- ADDONS, STATIC, MOP and LLM switch on and off independently, explicitly and on the record, by plan
  declaration, with no `if/else` on group membership at the use sites.
- Every step names its owner. The LLM and MOP doses are visible in the trace as coins and gates.
- A cell without MOP carries no MOP signal, either in decisions or in telemetry.
- The LLM screenshot is captured in the orientation the display shows.

**Non-Goals:**
- The MOP path-choice variant X2 (D-A18 Q-4).
- A per-candidate scorer vector (D-A18 Q-13).
- The `DynamicRegionGate` from `llm-opaque-screen` (plan §4.4).
- Any change to the derive or to wire format 2, except the additive `data` member of the component intent
  filters (D-B13).
- Inferring intent extras from the static analysis: deferred by the author on 2026-10-07; the producer emits none.
- Changes to the APE algorithm itself: the visit-count rule stays.
- Which cells the mini experiment runs (Q-9) and whether `ape.jar` is in it (Q-10). Both belong to `$P3`.

## Decisions

Decisions D-A1…D-A19 are taken from the plan as they stand. D-B1…D-B14 are this change's.

**D-B1 — Group keys.** Four plan keys, `ape.addons`, `ape.static`, `ape.mop` and `ape.llm`, all boolean and all
defaulting to `false`, plus one data root, `STATIC_DATA`, activated by `ape.staticDataPath` (PRESENT).
- `STATIC` and `MOP` require `STATIC_DATA`.
- `LLM` owns `ape.llmUrl` with no neutral value, so `ape.llm=true` without a URL is a plan error.
- Every member feature declares its group as a dependency. A member whose group is off is left out silently by
  the existing `deriveFeatures` rule (active-by-default with a missing dependency). A member whose activation
  key is explicit raises `MISSING_DEPENDENCY`, as today.

No `if (group)` appears at a use site: a site reads `spec.has(memberFeature)` or its params record.
- Alternative: one key per member feature only, with no group keys. Rejected, because D-A18 Q-1 wants one key
  per group.
- Alternative: groups as presets only. Rejected, because a preset is not visible in `features`.

**D-B2 — `ape.mopDataPath` is renamed `ape.staticDataPath`** (author, 2026-10-17). The `as` cell reads the
artifact with MOP off, so the old name would lie (P3). `ape.mopDataPath` becomes a retired key: "renamed
ape.staticDataPath: the artifact feeds STATIC and MOP (isolate-guidance-groups)".

**D-B3 — Group blocks reproduce E6** (author, 2026-10-17):
- the ADDONS block is the current `aperv` preset;
- the STATIC block is the frontier at 200 plus typed input;
- the MOP block is arm 3's MOP configuration (`$RVA` `tool.py` variant `mopd_on_llm_off`), plus `p_M = 1.0`;
- the LLM block is arm 4's sampling configuration (`mopd_on_llm_90`), with `p_L = 0.9` in the preset.

The server URL comes from the tool. The tables are in § API Design.

**D-B4 — Addon G is replaced by R2** (author, 2026-10-17). `ape.leastVisitedPriorityTiebreak` and
`Feature.LEAST_VISITED_TIEBREAK` are retired: "replaced by the scorer-sum tie (X1/R2), which equals APE with zero
scorers". X1 has no key.

**D-B5 — The addon keys that were BASE become member features.**
- `ape.backMenuPickCap` (A) becomes `Feature.BACK_MENU_CAP` (POSITIVE, neutral 0, default 3).
- `ape.activityStableRestartThreshold` (J) becomes `Feature.ACTIVITY_STABLE_RESTART` (POSITIVE, neutral 0,
  default 200). When it is absent, the threshold is the upstream `Integer.MAX_VALUE` (`[verified]` `8f51b99c`
  `utils/Config.java:48`).

Both are ADDONS members.

**D-B6 — Every addon read site reads the plan** (INV-GRP-06). The `Config` sites listed in § Current state
switch to `spec.has(Feature.X)`. Otherwise `base` would run with `modelMenuEnabled` true. The BASE keys the plan
owns are read through it too (author, verification A10): `ape.doFuzzing`/`ape.fuzzingRate` (`FUZZING`) and
`ape.checkRestart`; their `Config` twins are deleted, with those of the retired and renamed keys. `ape.minEpsilon`
and `ape.maxEpsilon` stay `DYNAMIC_EPSILON` keys, already read through the plan (`SataAgent.java:1214`); their
`Config` twins (`Config.java:218-219`) are dead and deleted. A member's params accessor returns the
neutral value when the member is absent (`backMenuPickCap()` is 0 without `BACK_MENU_CAP`).

**D-B7 — Owners and the pipeline order** (D-A4, D-A8, D-A16, P-2):

    BUDGET, RESTART, LLM_NEW_STATE, LLM_STAGNATION, LLM_COIN, MOP_LAUNCHER, COMPONENT_TRIGGER, MOP_COIN, SATA_CHAIN

- `RESTART` is a guard stage that selects the restart action when a restart is due. A restart is due when:
  - the flag set by the stability checks of step 2 is up;
  - or the periodic threshold has passed and the previous step's selection did not disable the restart.
- It replaces the post-selection `checkRestart` in `ApeAgent.java:363`, so `dec.a` is the action that ran.

**D-B8 — LLM gates (Specification, §4.4).** `LlmGate` becomes an interface. `MinActionsGate(min)` admits when
`newState.getActions().size() >= min`; the default 3 equals today's `> 2`. `BreakerGate` admits when the client's
breaker allows an attempt.
- The composition root mounts them from the plan in this order: MinActions, then Breaker. A gate whose
  parameter is `0` is not mounted.
- G1 (the buffer) is no longer a gate; G4 (Budget) is the BUDGET guard. All three LLM stages share the chain.
- **At most one LLM call per step** (author, verification A3). Once an LLM stage has called, whatever the result,
  the later LLM stages return `Continue` without calling. A stage whose trigger did not hold or whose gate barred
  has not called. Today a failed new-state call lets stagnation and the coin call again in the same step
  (§ Current state), which can spend several timeouts on one step and still yield no LLM action.
- The acceptance helper that is in `LlmGate.accept` today moves to `LlmAcceptance`.
- New keys: `ape.llmGateMinActions` (3), `ape.llmBreakerFailures` (3) and `ape.llmBreakerOpenMs` (60000), the
  last two replacing the `LlmCircuitBreaker` constants (`llm/LlmCircuitBreaker.java:36-38`).

**D-B9 — Own RNG streams (P-3).** `StepRng` derives three `Random`s from the run seed (`seed ^ k` for a fixed
odd constant per stream: `llmCoin`, `mopCoin`, `fuzz`).
- At step open it draws `u_L` if LLM is mounted, `u_M` if MOP is mounted, and the fuzz toss (always), before any
  stage runs.
- The agent and helper streams keep every other draw. Mounting or unmounting a gate or a coin therefore never
  shifts SATA's draws (INV-DP-13).
- The component coin also gets its own stream, `componentCoin`, drawn every step when `COMPONENT_TRIGGER` is
  mounted (D-B13). `StepRng` therefore holds four streams: `llmCoin`, `mopCoin`, `componentCoin` and `fuzz`.
- `RunContext` builds `StepRng` from the run seed and the agent reaches it through `StepContext`, so no pipeline
  or `llm/` class calls `RunContext.current()` (the static-read guard test).

**D-B10 — The MOP coin (D-A10, D-A15).** `MopCoinStage` is eligible when the screen holds a targeted action
that meets three conditions:
- its undivided `mopWeight` is positive (under `distance`, an unretired target);
- `MopInteractionLedger` holds no first interaction for its (activity, `xpath|ActionType`);
- it is not the submit candidate excluded by form completion, when `FORM_COMPLETION` is mounted.

When eligible and `u_M < p_M`, the stage picks the action with:
1. the largest `mopWeight`;
2. then, under `distance`, the larger unretired target count;
3. then the first in `State.getActions()` order.

Priority is **not** a tie-break, so ADDONS scorers cannot steer the coin (R1 spirit).
- `MopInteractionLedger` is MOP-owned and mode-independent. Target retirement (distance) composes on it instead
  of keeping its own first-interaction record. Since D-B17 it counts executions; the coin reads the count against
  k and retirement reads only whether an execution is a key's first.
- `ape.mopTargetPickCap` is retired: "replaced by `ape.mopCoinLedgerMaxInteractions`, the MOP coin's budget of
  picks per activity and widget (default 1)" (reason amended by D-B17).
- The two shortcut sites and the "MOP preference in the EARLY_STAGE roulette" leave `SataAgent`.

**D-B11 — The launcher's global cadence (D-A16).** `stepsSinceFiring` advances on every step: the stage's
`onStepOpen` hook runs for every mounted stage at step open. The launcher fires on the first step at which
`stepsSinceFiring >= cadence` and the pipeline reaches `MOP_LAUNCHER`, which means that no guard and no LLM
took the step. The cadence key stays `ape.activityTriggerStagnationStep` (50). The launcher has no mode key.
- The feature that mounts it is renamed `ACTIVITY_TRIGGER` → `MOP_LAUNCHER` (author, verification A6), so a
  stage and its feature share a name, as `LLM_COIN` and `MOP_COIN` do. The key `ape.activityTriggerEnabled` is
  unchanged. The dead accessor `MopParams.activityTriggerEnabled()` is deleted (P3).
- The launcher reads the activity list, the main activity and the package from `StaticGuiModel`, and the
  distances and MOP activities from `MopData`.

**D-B12 — Extension points X1, X3, X4 (D-A7, D-A18 Q-5).** Each is a Strategy with the APE default, including
its RNG consumption. The composition root (`SataAgent` constructor) mounts the MOP variant when `MOP` is in the
plan. There is no key. Implementations are named `<Variant><Interface>` (author, verification A5).
- **X1** `ScorerSumLeastVisitedTie`: R2, the larger sum of the candidate's scorer fields, otherwise the incumbent.
  With zero scorers it is upstream's first of the array (`model/State.java:185-190`).
- **X3** `ApeAbaTargetTie` never replaces on a tie and draws nothing. `MopAbaTargetTie` replaces when the
  candidate's activity is in the MOP set and the incumbent's is not (`MopScorer.stateMopDensity` order).
- **X4** `ApeTrivialPathPick` always takes `RandomHelper.randomPick`. `MopTrivialPathPick` takes the first path
  with the strictly largest end-state density, and falls back to `randomPick` when every density is equal.

**D-B13 — Component triggering is a STATIC member, on at 0.03, with the intent filter's `data`** (author,
2026-10-17).

History. `componentPercentage` has defaulted to 0 since gh15 A-3 (`a8f2fa6d`,
`openspec/changes/archive/2026-06-22-gh15-aperv-experiment-fidelity/proposal.md:5,11`). The reason was that it
had been derived from `mopDataPath`, which confounded the `sata`/`sata_mop` arms (502/507 against 0/507
traces). No `aperv-tool` variant ever set it, so it was off in E5 and E6.

With the groups, the confound is gone by construction, and the author places the mechanism in STATIC:
- **Ownership and dose.** `COMPONENT_TRIGGER` requires `STATIC`, not `MOP`. The STATIC block sets
  `componentPercentage=0.03`.
- **The pool reads no MOP data.** The trigger pool is every receiver, service and provider of the static
  manifest that the existing rules admit (permission, same package). The `reachesTarget` filter
  (`StatefulAgent.java:1355,1374`) is removed, and the log line no longer claims `reachesTarget=true`.
- **The coin.** It moves to its own stream, `StepRng.component`, drawn every step (P-3 spirit). Mounting the
  trigger therefore never shifts SATA's draws.
- **The intent carries the filter's `data`.** This is the defect the author recalled. The derive reduces each
  intent filter to `actions`/`categories` (`$RVA/.../derive_mop_artifact.py:1497-1503`), and the loader has no
  reader for `data` (`utils/MopData.java:745`), so a receiver or service that filters on a scheme or MIME type
  never matches. After this change:
  - the derive keeps a reduced `data` member per filter (`schemes`, `hosts`, `paths`, `pathPrefixes`,
    `mimeTypes`; empty lists omitted; D-B14.11);
  - `StaticGuiModel` parses it;
  - `dispatchTrigger` sets `setDataAndType(Uri(scheme://host/path), mimeType)` from the first scheme and the
    first MIME type of the filter that supplied the action. With only a MIME type it calls `setType`; with only
    a scheme it calls `setData`. It takes the package from `StaticGuiModel`, not from `MopData`.
  
  The artifact stays `formatVersion: 2`: the member is additive, and the loader reads its absence as "no data".
- **Extras.** The static analysis emits no intent extras (`[verified]`: no `extras` member in
  `test-apks/cryptoapp.apk.json` components, none in `$RVA/modules/rv-static-analysis/src`). Receivers keep the
  `system-broadcast.json` catalog extras. Inferring extras stays deferred (author, 2026-10-07).
- **The broadcast catalog.** It is loaded iff `COMPONENT_TRIGGER` is mounted, and the tool pushes
  `system-broadcast.json` for the four `as*` cells. It is not pushed for `base` or `mop`.

**D-B14 — Resolutions of points the spec writers flagged.** These are the binding readings.
1. **The MOP block does not state `mopWeightOpenMenu`.** A preset key counts as explicit, so stating it without
   `MODEL_MENU` would abort the `mop` cell with `MISSING_DEPENDENCY`. Its jar default is already 250, so
   `MENU_GATEWAY` mounts in `as_mop`/`as_mop_llm` and is left out silently in `mop`.
2. **The ADDONS block omits `defaultGUIThrottle=200`.** That is a BASE key whose jar default is already 200, and
   upstream's too, so every cell runs at 200.
3. **`StaticParams` fields:** `dataPath`, `strictPackageMatch`, `frontierBoostWeight`, `fuzzInputTyped`,
   `componentPercentage`. `ape.mopStrictPackageMatch` keeps its name and moves to `STATIC_DATA`, because the
   package check is on the static model.
4. **`ape.llmUrl` with LLM off** has no neutral value, so it aborts with `MISSING_DEPENDENCY` (the existing rule).
   The tool passes the URL only for the `*_llm` cells.
5. **The oracle and component dispatch.** The oracle's `OracleSataAgent` SHALL override `triggerComponent()`
   as a recorded no-op (today it overrides only `getRandom()`, `OracleSataAgent.java:57-58`; dispatch needs a
   device). The coin still draws from `StepRng.componentCoin`, and the side effect is recorded as `dec.trig`
   (author, verification A7; `dec.comp` stays the launcher's launch outcome, `MonkeySourceApe.java:980`). A
   component trigger is never a step owner (`src` has no component value); it is a side effect that does not
   decide the step.
6. **`dec.own`:**
   - `i` is the first decider that claimed the step: a guard (`budget`, `restart`), a fired LLM trigger, a won
     LLM coin, a due launcher, a won MOP coin, otherwise `sata`.
   - `fb` is written when `src ≠ i` and gives the reason.
   - `decline` means the model answered without an action the router can take.
7. **`dec.draw`** is `[drawn integer, bound]`, the values needed to replay the roulette offline.
8. **`dec.nav.alt`** is 1 iff the MOP variant decided by its own rule: X3 replaced the incumbent, or X4's
   strictly largest density chose the path without a draw. X1 always writes `alt:0`. So `alt` is 0 in cells
   without MOP (author, verification A2). **`dec.inp`** precedence is `llm` > `form` > the generator
   (`typed`, `heuristic`, `random`).
9. **Clean control and the dictionary.** `ACT.mop` is written only when `MOP` is mounted. `MOP_DATA` stays the
   census of the loaded artifact in every cell that loads one: it describes the input, not a decision, and the
   clean-control gate excludes it by name.
10. **The submit candidate no longer reads `mopBoost`.** Form completion (ADDONS) picks the submit candidate by
    lone button or submit word only. Reading a MOP scorer's field would couple ADDONS to MOP (plan §1.3).
11. **Intent filter `data`.** The derive keeps `schemes`, `hosts`, `paths`, `pathPrefixes` and `mimeTypes`. The
    jar uses `paths[0]`, falling back to `pathPrefixes[0]`. `ports` and `pathPatterns` are not projected,
    because a pattern is not a literal path.
12. **`RESTART` is mounted iff `ape.checkRestart`** (BASE, default true). `RandomAgent` and `ReplayAgent` have
    no pipeline and keep the post-selection `checkRestart` wrapping unchanged. The wrapping is skipped only for an
    agent that has a pipeline.
13. **The buffer under the guards.** A Budget step abandons a non-empty buffer without weakening. A restart
    starts a new episode, which clears the buffer, also without weakening.
14. **`dec.own.trg`** is the stage that made the step's one LLM call (D-B8). `llm[].mode` keeps its existing
    labels (`random` for the coin stage) so the reader's join is unchanged.
15. **The component pool.** It emits a name-only tuple for every filterless service, and none for a filterless
    receiver. The data URI is `scheme://host + path`, with `host` and `path` empty when they are absent.
    The name-only tuple does not depend on `targetMethods` (today `StatefulAgent.java:1363` requires it, a MOP
    signal). `triggerMopComponent()` is renamed `triggerComponent()`, and `mopComponentTargetCount()`
    `componentTargetCount()`.
16. **The `disableRestart` latch** is read and cleared by `RESTART`. A step taken by Budget leaves it for the
    next step.
17. **`dec.ch` survives as the pick channel.** The two `SHORT_CIRCUIT_*` values are gone. A MOP-coin step
    carries `mop_coin`; launcher and LLM keep `launcher` and `llm`; the guard steps carry `budget` and `restart`
    (author, verification A4), so `sata_other` names SATA's other paths only.
18. **X3's MOP variant** follows the `stateMopDensity` order, which also breaks a same-activity tie. In `dec.nav`,
    `alt` follows item 8. X1 always has `c:"ape"`.
    `dec.cfc` is also written at the null-handler pick, which is a priority roulette. The coin's target count is
    the existing "within d+1" count.
19. **`MenuGatewayPass` stops reading the ADDONS BACK/MENU cap** (R1). In `as_mop`, a capped MENU is already
    filtered out of selection by the cap itself.
20. **The artifact is loaded iff `STATIC` or `MOP` is mounted.** A path given with neither group mounted is inert
    and listed in `RUN_START.inert`, so no load, no census and no rejection follow.
21. **`getMopData()` is null iff `MOP` is not mounted**, even when the artifact was loaded for STATIC. So the
    UICOV coverage dump's `mopReach` (`agent/SataAgent.java:369-372`, `dumpCoverage`) is written only when `MOP`
    is mounted, and is omitted otherwise, consistent with `ACT.mop`. The clean-control gate counts `mopReach` as
    a MOP signal.
22. **`RUN_START.v` becomes 2** (`RunSpecEcho.FORMAT_VERSION`): `dec.src` changes meaning, and the reader keys
    its strict mode on `v`.

### Amendment after `e03igg` (author, 2026-10-18)

The full `e03igg` run (163 APKs × `ape`/`aperv:base`/`aperv:mop` × 3 × 300 s, jar `a2fde6c`) and its short round
`e03iggsmk` exposed the defects D1–D2 recorded in `evidence.md`, and the breaker trips of `e03iggsmk` and E6 v2
showed the screenshot failures behind D-B18. The author decided D-B15…D-B19, and reverted D-B19 after `e03igg2`
(2026-10-19). Rule for all five:
**BASE stays upstream APE** — no decision below changes the behaviour of a cell without the group that owns it, so the P0
parity with the original `ape.jar` is untouched. The only trace change in such a cell is the counter of D-B16. D-B15 and D-B16 have no key (author): they are
defect fixes owned by `MOP_LAUNCHER`, active in every cell that mounts it (`mop`, `as_mop`, `as_mop_llm`), and the
"before" is `e03igg` itself. Two related points were examined and left as they are:
- the launcher cadence keys (`ape.activityTriggerStagnationStep`, `ape.activityTriggerMaxPerRun`) already belong to
  `MOP_LAUNCHER` and read alike in every cell with MOP. The cadence is not the binding limit: in the 489 `aperv:mop`
  runs, 1133 firings found no eligible candidate against 906 launches, and 212 runs launched nothing;
- retirement is not early in the typical case. A median of 0 of 171 targets retire per run (mean 0.9 %). In
  `mini-experimento`'s cross with coverage, a target that retires alone in its step had been reached already in
  80 % of cases, a median 21 s before it retired. Targets retired but never reached come from **batch
  retirement**: one first interaction counts every target at `d*` of a broad lookup, so three touches of
  generic-handler widgets retire the whole set at once (owncloud: 84 targets in one step, `d=2`, from the login
  screen). The author decided to record this as a known limit and not change it (2026-10-18). No change to the
  coin's ledger follows from it (D-B17).

**D-B15 — The launcher launches each activity at most once per run (D1a).** `MopLauncherStage` keeps the set of
activities it has launched, and eligibility gains a sixth condition: never launched in this run. A launch that
opens its activity already made it visited, and so ineligible. The new condition therefore only removes an
activity whose launch did not open it — the case of `RecordAudioActivity` (spell4wiki), which crashes on a cold
launch without extras and was chosen in 3 of 4 launches in `mop`. The stage still never reads the launch result
(INV-CT-14 holds as written). The set is the stage's episode state (INV-DP-07) and is filled at selection, when
the stage returns its `Select`.
- Alternative: retire an activity when the next step shows a null tree or a foreground outside the target.
  Rejected: it reads the launch result and needs a failure signal of its own, for the same effect.
- Alternative: a key for N launches per activity. Rejected (P1): there is no case for N > 1.

**D-B16 — With the launcher mounted, the null-tree recovery relaunches the app (D1b).** After more than ten
consecutive null trees, `MonkeySourceApe` calls `stopTopActivity()`, which kills
`getRunningAppProcesses().get(0)` (upstream APE code). After a launch that crashed the app, that process is the IME
or the system launcher. In `e03igg` it was so in 7 runs, all `aperv:mop`, and the runs that stopped before 240 s
are among them. When the plan has `MOP_LAUNCHER`, the recovery instead stops the package under test and starts its
main activity again, through the existing restart path (`restartPackage(getMainApp(), false, "null tree")`; not
`randomlyPickMainApp`, which draws from `mRandom`). Like `stopTopActivity`, it first calls the agent's
`onActivityStopped()`. On that step it does not queue `ACTIVATE` (app switch, then HOME or BACK), which would send
the relaunched app to the background; the step's action is `NOP` (author, 2026-10-18). Without `MOP_LAUNCHER`, the
code path is upstream's, unchanged. The owner is the launcher because the launcher is what produces the crash: an activity
opened out of its normal flow, without extras. `RUN_END.counters.nullRelaunches` counts the relaunches. Like every
routing counter it is written in every cell (`event-sink`: a zero is a measurement), so a cell without the launcher
differs from today's trace only by `nullRelaunches:0` in `RUN_END`, not in behaviour.
- Alternative: fix `stopTopActivity` for every cell. Rejected by the author: BASE must stay equal to `ape`.
- Alternative: an ADDONS member. Rejected: the `mop` cell, where the defect occurs, has no ADDONS.

**D-B17 — The coin may take a key up to `k` times (default 1), and `dec.hnd` records the stamped handler.**
In `e03igg` the coin took 2.8 % of the steps, while a weighted widget was on screen in 26 % of them. A replay of
the ledger from the traces shows that the coin does take first every weighted key that is new: of SATA's 8397
executions of a weighted action, none was a new key. 7349 repeated a key the coin had taken, and 1048 a key
that SATA had touched while it was still unweighted. So the coin runs out of distinct weighted keys, and SATA
keeps re-tapping them through the MOP boost in its priority. The author decided (2026-10-18):
- `ape.mopCoinLedgerMaxInteractions` (int, default 1, owned by `MOP_COIN`). `MopInteractionLedger` counts
  executions instead of marking a first one, and the coin stays eligible for a key while its count is below k.
  The ledger is constructed with k and answers `belowBudget(activity, key)`, so the coin stage takes no new
  parameter (author, 2026-10-18). Default 1 is D-B10. A value below 1 is clamped to 1 at resolution, like the other
  plan clamps (`RunSpec.clamp`, no abort on a parameter value). The retired key `ape.mopTargetPickCap` now names it
  in its reason. Retirement still reads only whether an execution is the key's first (count 0 before the
  record), so k does not change what retires. Every owner's execution counts toward k, as it counts today.
  Expected effect, stated before any run: with k > 1 the coin takes some of the repeats that SATA makes today.
  It does not add distinct keys.
- A state scope and a handler key for the ledger were designed and dropped: they could not be tested in time,
  and the state scope multiplies the coin's eligibility on View apps too (median 15 abstract states in the
  busiest activity of a View-only run).
- `dec.hnd`: the binary class name of the handler stamp that answered the chosen action's weight lookup, written
  exactly when `dec.mops` is `s` (MOP mounted). It makes the list hypothesis (list items sharing one handler)
  measurable. `MopData.Lookup` gains that class (`handler`, null unless `STAMP`), so the field reuses the lookup.

**D-B19 — Reverted after `e03igg2` (author, 2026-10-19): the MOP widget boost goes to every weighted action,
tried or not, as in `a2fde6c`.** The rule decided on 2026-10-18 gave no boost to an action whose (activity,
`xpath|ActionType`) key the `MopInteractionLedger` already held. It came from `e03igg`: SATA executed a weighted
action 8397 times and none was a new key, and `mini-experimento` measured that re-taps yield less than first
touches (a first touch 3.0 × the paired base, a 2nd 1.30 ×, a 3rd 1.16 ×; over all app methods 0.86–0.98 ×)
[reported]. Shipped in jar `a4afd31` (F12 `799ad722`), it was the only behaviour change `e03igg2` measured
[reported, `rvsec/rv-android/docs/20261019_e03igg2.md` §6.6, §9]. SATA's lift on weighted actions fell from 1.03
[0.97; 1.09] to 0.87 [0.82; 0.93] in every guided cell, so SATA picked a weighted action less often than chance.
Over the same tasks the `mop` cell's misuses fell to the level of `ape`: per-APK `mop − ape` −0.001 [−0.042;
+0.042], against +0.043 in `e03igg`; `mop` drift against `e03igg` 0.903 [0.807; 1.01], p = 0.073. The author
decided to revert it (2026-10-19). `MopWidgetPass` splits the ties on the undivided weights and does not read the
ledger. `ScoringContext.getMopLedger()`, which existed only for this rule, is removed (P1). The ledger keeps its
two readers, the coin (D-B17) and retirement. `dec.mopx` keeps its meaning: its first member counts the weighted
actions. With the boost back on every weighted action, that count equals the boosted count, so `share_weighted`
is comparable with `e03igg` again. The parity goldens do not move, as they did not move with the rule
(INV-ARCH-12).
- The other four decisions stand. The author also decided k = 2 as the `mop` default for D-B17, owned by
  `aperv-tool` (its variant mapping); the jar's MOP block and the key's default (1) stay as they are.
- Validation: one campaign with the reverted jar, compared against `e03igg`, with no old-jar arm (author); the
  `base` drift is checked again.

**D-B18 — A screenshot failure is not a breaker failure.** `LlmEngine.selectAction` records a breaker failure when
`ScreenshotCapture.capture` returns null. In `e03iggsmk` this tripped the breaker on bitbanana (17/17 captures
failed, 15 trips) and kept the LLM out for ≈ 600 of 900 s on mensinator, even after the captures worked again. In
E6 v2, nearly every trip in arm 4 was a screenshot failure, and the two that were not were three parse failures
in a row [reported, `rep-pack-e03`]. After this change a null capture counts `screenshot_failed` and returns null
without `recordFailure`: it neither increments the failure count nor reopens a `HALF_OPEN` breaker. The step falls
to the next stage exactly as before, and `dec.own.fb` becomes `screenshot` instead of `error`. The attempt still
spends the step's one LLM call (INV-RTR-26), as the code already does (`LlmCall.call` marks the call before the
engine runs). The breaker counts transport and parse failures only.
- Cost, accepted: an app that never yields a screen (`FLAG_SECURE`) pays one capture attempt per LLM-admitted
  step.

**D-B20 — The budget abandons the buffer it preempts, not the path it plans (author, 2026-10-19; review 14.3
CR-01).** `BudgetStage.decide` called the trivial-activity search first and tested `actionBufferSize() > 0`
after it [verified, `2cfa8ee2`/`6966079f`]. When the search finds a path it ends in `refillBuffer`, whose
`clearBuffer` weakens the preempted SATA buffer's next edge and then fills the buffer with the new path. The
`abandonBuffer("budget")` that followed therefore cleared the path the budget had just planned: every multi-hop
budget navigation lost its tail and was re-planned on the next step (one extra X4 helper-stream draw when two or
more paths tie), the preempted edge was weakened against INV-DP-15, and `dec.buf:"budget"` / `bufAbandoned` were
recorded even when the buffer was empty before the step. On `master` (`eea2b1a9`, before this change) the path
survived and the preempted edge was weakened; only the abandonment after the search is new. The stage's test
drove a fake that never modeled `refillBuffer` (review WR-04).
- The fix: the agent's budget navigation (`SataAgent.budgetNavigation`, a `StageCollaborators` method) detaches
  the buffer without weakening **before** the search, runs the unchanged `selectNewActionForTrivialActivity`,
  and then either restores the detached buffer (no navigation: the stage continues and SATA finds its path
  intact) or, when the search took the step and the detached buffer was non-empty, records `dec.buf:"budget"` and
  `bufAbandoned` (`abandonBuffer`'s record, P1). The planned path stays in the buffer and is followed by
  `SataChain`'s buffer rung on the next steps, as on `master`. `BudgetStage` no longer receives `abandonBuffer`.
- Scope: ADDONS cells only (`as`, `as_mop`, `as_llm`, `as_mop_llm`); `base` and `mop` do not mount `Budget`, so
  BASE and the parity goldens do not move. It changes the jar.
- Tests: a real `SataAgent`-buffer case per outcome (path found with a non-empty buffer, path found with an empty
  buffer, no path), closing WR-04 for the budget. WR-01…WR-03 and the review's notes are recorded in
  `evidence.md` as follow-ups, not fixed here.

**D-B21 — A budget step writes the `dec.nav` of its X4 choice (author, 2026-10-19; verify 14.5 W1).** The X4
trivial-path pick records its `nav` in the step's `SataTrace`. Only `logActionSelected`, on the `SataChain`
path, handed the trace to the sink, and `decide` replaces the trace on the next step. A `Budget` step whose search
tied two or more paths therefore drew from the helper stream with no `dec.nav` [verified, `1ef8e99f`]. This
contradicts action-selection ("whenever an extension point is consulted with two or more candidates, the step's
record SHALL carry `dec.nav`") and predates D-B20. `SataAgent.budgetNavigation` hands the trace's `nav`, and only
its `nav`, to the sink when it returns an action. A budget step still carries no `dec.strat` (activity-budget),
even though the search's `chose(…, "trivial")` stamps one in the trace. The change is pure telemetry: no draw
and no decision moves.

**D-B22 — `WTG` activates on a positive weight (author, 2026-10-19; verify 14.5 W3).** `Feature.WTG` used
`Rule.NONZERO`, the only feature that did, while mop-guidance specifies POSITIVE. A negative
`ape.mopWeightWtg` mounted WTG with a negative boost on a MOP plan, and aborted with `MISSING_DEPENDENCY` on a
plan without MOP. Under POSITIVE a value `<= 0` leaves WTG out of the plan, with no abort, as for every other
weight feature. `Rule.NONZERO` has no other user and is deleted (P3). The cells state 200, so no preset, digest
or golden moves.
- The inert rule (INV-RUN-05) accepted an absent feature's key only at its neutral value, so a `<= 0` weight
  aborted with `MISSING_DEPENDENCY` for every POSITIVE feature (`ape.backMenuPickCap=-1` included, against the
  spec's "`<= 0` = no counter"). Author (2026-10-19): a value that the feature's own activation rule reads as off
  states OFF as much as the neutral value does. It is inert, listed in `RUN_START.inert`, and does not abort
  (`RunSpec.applyInertRule`, `Feature.readsAsOff`). This only removes aborts. A stated ON value for an absent
  mechanism still aborts.

**D-B23 — `PIPELINE` carries the stage census (author, 2026-10-19; verify 14.5 W5).** decision-pipeline and
event-sink require "the full fixed candidate order with, for each, whether it was assembled". The record carried
the stage names as assembled (`stages`) and the scoring-pass census (`candidates`), but no stage census. It gains
`stageCandidates`: every `DecisionPipeline.Candidate`, in the fixed order, as `{name, enabled}`. This is the shape
of the pass census, and `candidates` keeps its meaning (additive, so `aperv-tool`'s `trace_ndjson` reads the
record verbatim). The list is a static declaration taken from the plan, not constructed stage objects
(INV-DP-03).

**D-B24 — `Config` keeps only fields that `src/main` reads (author, 2026-10-19; verify 14.5 W4).** 46 public
`Config` fields had no reader in `src/main/java`, qualified or through `import static`. Their keys are all owned
by the plan (`KeyOwnership`), so the plan reads them. [verified] A scratch copy with all 46 deleted compiles
`src/main` with no error, and no code reflects over `Config`. They are deleted, with the clamp helper that only
`llmPercentageNoSubstrate` used. The tests that asserted them now assert the plan
(`RunContext.current().spec()`, `KeyOwnership.defaultOf`), or are deleted when the plan side is already covered.
`KeyOwnershipTotalityTest.declaredDefaultsMatchTheJarsOwnDefaults` keeps checking the fields that remain. The
plan's own defaults stay pinned by the cell goldens and the `cells/*.run_start.json` digests.
- `ape.llmPercentageNoSubstrate` is retired with them. It is the plan key behind one of the twins, and it never
  had a consumer: the substrate predicate it was waiting for was never derived. It moves to `KeyOwnership`'s
  retired keys, with that reason. A properties file that states it aborts with `retired_key` (an abort on the
  key's name, like every retired key, not on a value). `aperv-tool` is told to drop its `tool.py` mapping.
- Specs: the deltas stop naming the deleted fields. Four main-spec requirements that no delta touched cite them,
  and each gains a MODIFIED delta: exploration "Per-Step Debug Artifact Defaults", llm-routing
  "Coordinate-to-ModelAction Mapping", and ui-coverage "Coverage Boost in Action Scoring — Per-Action" and
  "Config Flag for Coverage Boost". The llm-routing seam requirement is REMOVED. The preamble citations join the
  V57 archive hand-sync.

**D-B25 — `coinLlm` counts the steps on which the LLM coin was won and consulted (author, 2026-10-19; verify
14.5 W2).** event-sink said to count a won coin "including a step on which an earlier LLM stage already made the
step's one call". `LlmCoinStage` does not draw when the call is spent: it returns before the count, and
`LlmGateTest` asserts 0. This design already reads "steps on which each coin was won" (Data Flow). The spec
follows the code. `coinLlm` counts the steps that reach `LlmCoin` with the step's call unspent and `u_L < p_L`.

## API Design

### Key contract v3.2 (wave 0; sent verbatim to the rv-android `aperv-tool` session)

v3.3 (amendment after `e03igg`, 2026-10-18) over v3.2: one `MOP_COIN` key, `ape.mopCoinLedgerMaxInteractions`
(D-B17); `dec.own.fb` gains `screenshot` (D-B18);
`dec.hnd` is new (D-B17); `RUN_END.counters.nullRelaunches` is new (D-B16). `dec.mopx` is unchanged. The retired
key `ape.mopTargetPickCap` has a new reason text. Group blocks and cells are unchanged. `RUN_START.params` omits
defaults, so it changes only when k is set; the plan digest covers every value, so the digest of the three MOP
cells' fixtures changes.

v3.2 (verification, 2026-10-17) over v3.1: `MOP_LAUNCHER` replaces `ACTIVITY_TRIGGER` in `features`; `dec.trig`
is new; `dec.ch` gains `budget` and `restart`; `dec.nav.alt` is defined by the MOP variant's own rule; at most
one LLM call per step; `dec.rot` ranges over −1..3. Keys and group blocks are unchanged.

**Group, data and resolver keys**

| Key | Owner | Type | Jar default | Notes |
|---|---|---|---|---|
| `ape.addons` | `ADDONS` (root) | bool | false | |
| `ape.static` | `STATIC` (requires `STATIC_DATA`) | bool | false | |
| `ape.mop` | `MOP` (requires `STATIC_DATA`) | bool | false | |
| `ape.llm` | `LLM` (root) | bool | false | needs `ape.llmUrl` |
| `ape.staticDataPath` | `STATIC_DATA` (root, PRESENT) | path | null | was `ape.mopDataPath` |
| `ape.variant` | resolver | string | null | echoed as `RUN_START.variant`; not in the digest |
| `ape.preset` | resolver | string | — | `base` `mop` `as` `as_mop` `as_llm` `as_mop_llm` |

**New member keys**

| Key | Owner | Type | Jar default | Notes |
|---|---|---|---|---|
| `ape.mopCoinPercentage` | `MOP_COIN` (MOP), POSITIVE, neutral 0 | double | 1.0 | `p_M`, clamped to [0,1] |
| `ape.llmGateMinActions` | `LLM` | int | 3 | 0 = gate not mounted |
| `ape.llmBreakerFailures` | `LLM` | int | 3 | 0 = gate not mounted |
| `ape.llmBreakerOpenMs` | `LLM` | long | 60000 | |
| `ape.backMenuPickCap` | `BACK_MENU_CAP` (ADDONS) | int | 3 | was BASE |
| `ape.activityStableRestartThreshold` | `ACTIVITY_STABLE_RESTART` (ADDONS) | int | 200 | was BASE; off = `MAX_VALUE` |
| `ape.frontierBoostWeight` | `NAV_FRONTIER` (STATIC) | int | 200 | was `FRONTIER` (MOP) |
| `ape.fuzzInputTyped` | `TYPED_FUZZ` (STATIC) | bool | true | |
| `ape.componentPercentage` | `COMPONENT_TRIGGER` (STATIC) | double | 0.0 | was under MOP; the STATIC block sets 0.03 (D-B13) |
| `ape.mopCoinLedgerMaxInteractions` | `MOP_COIN` (MOP) | int | 1 | D-B17; `< 1` clamped to 1; no block sets it |

Existing addon features gain the `ADDONS` dependency, with their keys unchanged: `MODEL_MENU`,
`FORM_COMPLETION`, `TREE_ENHANCEMENTS`, `ACTIVITY_BUDGET`, `DYNAMIC_EPSILON`, `HEURISTIC_INPUT`,
`FOREIGN_ACTIVITY_GUARD`, `TREE_PACKAGE_GUARD` and `COVERAGE_BOOST`. The MOP family gains the `MOP` dependency,
with its keys unchanged: `WTG`, `MENU_GATEWAY` (which also requires `MODEL_MENU`), `MOP_FRONTIER`,
`MOP_LAUNCHER` (renamed from `ACTIVITY_TRIGGER`, key `ape.activityTriggerEnabled` unchanged) and `MOP_ACTIVITY_SOURCE`. `COMPONENT_TRIGGER` moves to `STATIC` (D-B13). The LLM family is unchanged; `LLM_RANDOM` is
renamed `LLM_COIN`, and its key `ape.llmPercentage` (`p_L`) is unchanged. `FUZZING` is BASE.

**Retired keys:** `ape.mopDataPath`, `ape.leastVisitedPriorityTiebreak` and `ape.mopTargetPickCap`. A properties
file that sets one aborts with `retired_key`.

**Group blocks and cells.** A cell preset is the union of its group blocks; explicit keys override it, as today.

| Block | Keys |
|---|---|
| ADDONS | `addons=true`, `backMenuPickCap=3`, `foreignActivityGuard=true`, `treePackageGuard=true`, `dynamicEpsilon=true`, `heuristicInput=true`, `formCompletionEnabled=true`, `modelMenuEnabled=true`, `treeEnhancementsEnabled=true`, `activityBudgetEnabled=true`, `activityStableRestartThreshold=200`, `coverageBoostWeight=100` |
| STATIC | `static=true`, `frontierBoostWeight=200`, `fuzzInputTyped=true`, `componentPercentage=0.03` |
| MOP | `mop=true`, `mopScoring=distance`, `mopWeightWtg=200`, `mopFrontierWeight=200`, `activityTriggerEnabled=true`, `mopActivitySourceComponents=true`, `mopCoinPercentage=1.0` |
| LLM | `llm=true`, `llmOnNewState=true`, `llmOnStagnation=true`, `llmPercentage=0.9`, `llmPromptVariant=v13`, `llmTemperature=0`, `llmTopP=1.0`, `llmTopK=-1`, `llmSnapTolerancePx=150`, `llmModel=Qwen/Qwen3-VL-4B-Instruct-FP8`, `llmTimeoutMs=15000` |

| Cell (`ape.preset`) | ADDONS | STATIC | MOP | LLM | Tool must pass | Artifact pushed |
|---|:-:|:-:|:-:|:-:|---|:-:|
| `base` | – | – | – | – | `ape.variant` | no |
| `mop` | – | – | ✓ | – | `ape.staticDataPath`, `ape.variant` | yes |
| `as` | ✓ | ✓ | – | – | `ape.staticDataPath`, `ape.variant` | yes |
| `as_mop` | ✓ | ✓ | ✓ | – | `ape.staticDataPath`, `ape.variant` | yes |
| `as_llm` | ✓ | ✓ | – | ✓ | `ape.staticDataPath`, `ape.llmUrl`, `ape.variant` | yes |
| `as_mop_llm` | ✓ | ✓ | ✓ | ✓ | `ape.staticDataPath`, `ape.llmUrl`, `ape.variant` | yes |

Doses (`ape.llmPercentage`, `ape.mopCoinPercentage`, `ape.componentPercentage`) may be overridden per variant.
`system-broadcast.json` is read only by `COMPONENT_TRIGGER` (`StatefulAgent.java:218,1457`), so the tool pushes it
for the four `as*` cells and not for `base` or `mop` (D-B13). In the `mop` cell `MENU_GATEWAY` is left out because `MODEL_MENU` is an addon; this is
declared, not a leak.

Expected `features` per cell:
- `base`: `FUZZING`.
- `mop`: `FUZZING`, `STATIC_DATA`, `MOP`, `WTG`, `MOP_FRONTIER`, `MOP_LAUNCHER`, `MOP_ACTIVITY_SOURCE`,
  `MOP_COIN`.
- `as`: `FUZZING`, `ADDONS`, the nine addon members, `BACK_MENU_CAP`, `ACTIVITY_STABLE_RESTART`, `STATIC_DATA`,
  `STATIC`, `NAV_FRONTIER`, `TYPED_FUZZ`, `COMPONENT_TRIGGER`.
- `as_mop`: `as` + the `mop` MOP features + `MENU_GATEWAY`.
- `as_llm`: `as` + `LLM`, `LLM_NEW_STATE`, `LLM_STAGNATION`, `LLM_COIN`.
- `as_mop_llm`: `as_mop` + the four LLM features.

The exact `RUN_START` per cell is captured as a test fixture in wave 2: `src/test/resources/cells/<cell>.run_start.json`,
with `run_id`, `t0`, `seed`, `build` and `props_digest` masked. The rv-android gate reuses those files.

### Telemetry contract (new `dec` members; all pure)

| Field | Shape | When written |
|---|---|---|
| `dec.src` | owner: `budget` `restart` `llm` `launcher` `mop` `sata` | always; **its meaning changes**: the executed owner, no longer "largest boost" |
| `dec.ch` | pick channel; `short_circuit_*` removed; new `mop_coin`, `budget`, `restart`; `launcher`, `llm` kept; `sata_other` = SATA's other paths only | always |
| `dec.own` | `{"i":owner, "trg":"new_state"\|"stagnation"\|"coin", "uL":double, "uM":double, "g":gate, "fb":reason}` | always; `uL`/`uM` only when that group is mounted; `i` intended owner; `g` the gate that barred (`min_actions`, `breaker`); `fb` ∈ `gate` `error` `screenshot` (the LLM screenshot could not be captured, D-B18) `decline` `no_match` `ineligible` (a due launcher without a candidate); a lost or ineligible coin claims nothing, so it has no `fb` |
| `dec.cfc` | `{"<scorer>":0\|1}` over the mounted scorers with a non-zero term in the candidate set | at the roulettes and the least-visited pick only; replaces `dec.cf` |
| `dec.draw` | `[idx,total]` | the roulette picks (EARLY_STAGE, ε, null handler) |
| `dec.nav` | `{"x":"X1"\|"X3"\|"X4", "c":"ape"\|"mop", "alt":0\|1, "drew":0\|1}` | when an extension point decided a tie; `alt` per D-B14.8 |
| `dec.inp` | `typed` `heuristic` `random` `form` `llm` | when the action entered text |
| `dec.restart` | `graph` `state` `activity` `periodic` | when `src=restart` |
| `dec.bmc` | int | candidates removed by the BACK/MENU cap in this pick; omitted when 0 |
| `dec.strat` | `buffer` `back_to_activity` `aba` `early_roulette` `greedy_global` `greedy_path` `trivial` `early_backward` `backtrack` `eps_back` `eps_menu` `least_visited` `eps_roulette` `null` `saturated` | when `src=sata` |
| `dec.rot` | int −1..3 (−1 = unreadable) | every step; `0` is written |
| `dec.buf` | owner | the owner that abandoned a non-empty buffer |
| `dec.hnd` | stamped handler class | MOP mounted, when the executed action's weight lookup is a stamp (`dec.mops` = `s`); telemetry only, D-B17 |
| `dec.trig` | `{"k":"broadcast"\|"service"\|"provider", "c":"<class>"}` | the step on which `ComponentTriggerStage` fired, whatever `src`; `dec.comp` stays the launcher's launch outcome |
| scorer fields | `mop` `mopf` `wtg` `navf` `cov` `menu` `form` | non-zero; `navf` is new (`NAV_FRONTIER`); `wtgsrc` is removed |

`RUN_END.counters` gains the following, written in full:
- `launches`;
- `gateMinActions` and `gateBreaker` (steps lost by each gate);
- `guardBacks` (foreign or tree guard BACKs);
- `bufAbandoned`;
- `restarts`;
- `coinLlm` and `coinMop` (steps on which each coin was won);
- `nullRelaunches` (null-tree recoveries that relaunched the app, D-B16; 0 without `MOP_LAUNCHER`).

`RUN_START` gains `variant` (from `ape.variant`, omitted when null). `dec.cf` and `dec.wtgsrc` are removed (P3).

### Interfaces fixed in wave 0

```java
interface LlmGate { String name(); boolean admits(StepContext ctx); }
interface LeastVisitedTie { ModelAction pick(List<ModelAction> leastVisited); }          // X1
interface AbaTargetTie   { boolean replaces(State incumbent, State candidate); }       // X3
interface TrivialPathPick { Subsequence pick(List<Subsequence> paths); }               // X4
interface PromptContribution {
    default String systemSentence(String point) { return ""; }      // "<variant>" or "<variant>.<slot>"
    default String lineMarker(List<ModelAction> lineActions) { return ""; } // one marker per line
    default String lineDetail(ModelAction a) { return ""; }
    default String contextLine(State s, List<ModelAction> actions) { return ""; }
    default String section(State s, List<ModelAction> actions) { return ""; }
}
final class StaticGuiModel { /* WTG + widget metadata queries; read-only */ }
final class MopInteractionLedger { int count(String activity, String key); boolean tried(String activity, String key);
    boolean belowBudget(String activity, String key); void record(String activity, String key); }  // D-B17
final class StepRng { double llmCoin(); double mopCoin(); double componentCoin(); boolean fuzzToss(double rate); }
```

- `DecisionStage` gains `default void onStepOpen(StepContext ctx) {}`.
- `StageCollaborators` gains `restartDue()`, `restartAction()`, `abandonBuffer(String owner)` and
  `mopLedger()`; `triggerMopComponent`/`mopComponentTargetCount` become `triggerComponent`/`componentTargetCount`.
  It is implemented by `SataAgent`, which inherits the new methods from `StatefulAgent`.
- `StepContext` (implemented by `StatefulAgent`) gains `staticModel()` and `stepRng()`; `mopData()` is null iff
  `MOP` is not mounted.
- `ModelAction` carries the new `DecisionSource` owner vocabulary, the new `PickChannel` values and the
  `navFrontierBoost` field, so no wave-1 front edits another front's enum.
- `EventSink` gains `owner(...)`, `cfc(...)`, `draw(...)`, `nav(...)`, `input(...)`, `restart(...)`,
  `backMenuCapped(...)`, `strategy(...)`, `rotation(...)`, `bufferAbandoned(...)`, `componentTrigger(...)` and
  the counters. `NoopSink` implements them as empty.

## Data Flow

1. `RunSpec.resolve` yields the features. When `STATIC` or `MOP` is mounted (D-B14.20), `StaticArtifactLoader.load`
   parses the artifact once. It hands `StaticGuiModel` to the STATIC members and to the MOP members that read the
   WTG or the components, and `MopData` to the MOP members only (`getMopData()` is null without `MOP`,
   D-B14.21). `MOP_DATA` stays the load census.
2. At step open `StepRng` draws `u_L`, `u_M`, the component coin and the fuzz toss. `rotation` is read for
   `dec.rot`.
3. The pipeline runs. Each stage that takes the step records the owner. A preempting owner abandons the buffer
   through `abandonBuffer(owner)`.
4. SATA reaches weights only through the roulettes and the X1 tie. The cf of each scorer (`dec.cfc`) is computed
   at those sites by `MopCounterfactual`'s mould, generalized to `ScorerCounterfactual`: zero one field, replay
   the same draw index, compare.
5. The refiner records `dec.inp`. Monkey events are emitted, and the outcome closes the record at N+1.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `ape.mop`/`ape.static` true without `ape.staticDataPath` | resolution | `MISSING_DEPENDENCY` abort (plan error, unchanged rule) | the tool pushes the artifact |
| `ape.llm=true` without `ape.llmUrl` | resolution | `MISSING_DEPENDENCY` | the tool passes the URL |
| retired key set | resolution | `retired_key` with its reason | update `tool.py` (sister change) |
| `p_M`, `p_L` out of [0,1] | resolution | clamp (no new abort on values) | — |
| `ape.mopCoinLedgerMaxInteractions < 1` | resolution | clamp to 1 (D-B17) | — |
| artifact rejected at load | `StaticArtifactLoader` | as today: `MOP_DATA status=rejected`, fail-fast when a group needs it | — |
| rotation unreadable | `AndroidDevice.getRotation` | `-1` → capture at 0, `dec.rot` written as `-1` | — |
| internal sink failure | `NdjsonSink` | latch off, as today | — |

## Risks / Trade-offs

- [The MOP effect may shrink: the coin decides only first interactions (up to k per key since D-B17)] → that is the treatment, measured per
  owner; a smaller effect is a result (plan §8).
- [The restart guard reads the previous step's `disableRestart`, because the selection that sets it now runs
  after the guard] → declared; the restart sequence differs from `eea2b1a9`; new goldens.
- [P-3 and X3/X4 change the random sequence relative to `eea2b1a9`] → the parity goldens are recaptured on
  purpose in wave 2, one set per cell; `RngNeutralityTest` pins the property that matters.
- [Wave 1 fronts change behaviour that old goldens pin] → each front updates the unit tests of its own
  behaviour; the four old preset goldens are deleted with the old presets in wave 2.
- [Scope: ~40 files across both repositories] → waves by file ownership (tasks.md); wave 0 fixes every shared
  contract.
- [Prompt text without MOP is new text in `as_llm`] → it is the MOP-free wording of the same sentences (F6),
  listed in the llm-prompt delta.
- [`dec.src` changes meaning] → the reader documents both jars (P-9); `RUN_START.build` tells them apart.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit | features per cell, retired keys, `variant` echo | `TestRunSpecs` | ~25 |
| Unit | gates, coins, ledger, launcher cadence, X1/X3/X4, prompt contributions, new sink fields | fakes + `StepContext` stubs | ~60 |
| Unit | rotation clamp | `ScreenshotCaptureStageTest` (ported) | 2 |
| Integration | `SinkNeutralityTest` × 6 cells | oracle scaffold per cell | 6 |
| Integration | clean control: cells `base`, `as`, `as_llm` carry no MOP field (`mop`, `mopf`, `wtg`, `menu`, `mopd`, `mops`, `hnd`, `ret`, `mopx`, `own.uM`, the MOP keys of `cfc`, `nav.c=mop`, `src=mop`/`launcher`, `ch=mop_coin`, `ACT.mop`, UICOV `mopReach`; `coinMop`, `launches`, `retired` = 0) | replay + parse | 3 |
| Integration | RNG neutrality: mounting/unmounting a gate or the MOP coin with `p=0` leaves SATA's choices identical | replay pairs | 3 |
| Integration | base parity: `base` cell golden; X1/X3/X4 defaults equal upstream on fixtures | oracle | 4 |
| Device | 6 cells on cryptoapp; landscape APK with LLM | wave 3 | 7 runs |

## Open Questions

None blocking the jar. Q-9 (cells of the mini experiment) and Q-10 (`ape.jar` in it) belong to the `$P3`
experiment.
