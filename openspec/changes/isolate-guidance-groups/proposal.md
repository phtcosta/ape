## Why

**Label: MEASUREMENT.** This change alters what an APE-RV arm measures; nothing it produces enters an experiment
without the author's decision.

Study 03's campaign E6 v2 compared four arms on 163 APKs. C1 (MOP distance guidance × the original `ape.jar`) was
supported (IRR 1.16); C2 (guidance × "APE-RV unguided") was not resolvable (IRR 1.05). The paper cannot attribute
the gain to guidance or to the rest of the fork, because the behaviour groups of APE-RV cannot be switched on and
off independently (plan `docs/plans/20261017_isolamento_grupos_aperv.md` §1–§2):

- **The "unguided" arm still consumed static-analysis and MOP data**: the MOP-activity tie-break inside SATA
  navigation (`agent/SataAgent.java:1080-1098`, `:1342-1364`), the WTG frontier +200
  (`agent/scoring/FrontierPass.java:34-36`), and artifact-typed text input (`agent/ApeAgent.java:236-252`)
  (plan §2.1). `Feature.FRONTIER` depends on `Feature.MOP` (`runtime/Feature.java:194-196`) although the frontier
  reads no MOP data, and without the artifact the frontier and typed input fall together, so no "full tool without
  specs" configuration exists.
- **The groups talk through one shared number**, action priority: MOP passes and the addons add into it, SATA rules
  read it, so MOP depends on addons to act and `dec.src` is "largest boost", not cause (plan §1.3, §2.7).
- **The LLM dose is set by hidden gates**: 0.9 configured → 69 % of steps called → ~45 % decided (plan §2.5).
- **MOP guidance barely decides**: when a weighted action is on screen it is taken only 10 % above chance
  (plan §2.6).
- **Bug**: the LLM screenshot is captured at rotation 0, so landscape screens reach the model rotated and cropped
  (`llm/ScreenshotCapture.java:79-81`; `AndroidDevice.getRotation()` calls an API absent on API 30) (plan §2.4).

### Where the problems were first detected

The same list, with what each document says, is in plan §2.0 and in `design.md` § Context.

**The "unguided" arm (arm 2) is not MOP-free and consumes static-analysis data:**
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/docs/posthoc/e6-fork/report.md`
  §8.2 (`:262-272`, the table of differences active in arm 2, including the "+200 static-analysis navigation"
  and "MOP-density tiebreak" rows), §8.3 (`:274-281`, the tie-break as a recorded design property, 10 of 489
  tasks), §8.4 (`:283-288`, the earlier "ape_pure ≡ ape" evidence on another jar)
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/docs/handoff/20261017_e6_article_liaison_prompt.md:169-180`
  ("Two open findings about E6's jar": "Arm 2 is not MOP-free" and "Screenshot rotation")
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/docs/handoff/20261015_ape_fork_prompt_1.md`
  (`:26`, `:54`, `:94`, `:222`: the arm-2 keys and the tie-break as a recorded decision)
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/openspec/changes/archive/2026-10-15-e6-campaign/protocol.md`
  `:109-118` (§2 "The arms": arm 2 keeps the MOP document, the source components and `frontier_boost_weight` 200)
  and `:893-897` (§12 "Construct — guidance": admits only the tie-break)
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/doutorado-tese/docs/estudo-03/escrita/esqueleto/s4/s4-5.md`
  (the "Braço 2" and "Desempate" trace, the A/A erratum, decision DF-9)
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec/rv-android/docs/20260714_cmpma.md:214-265`
  (the earlier `ape` × `aperv:ape_pure` × `aperv:sata` comparison cited by report §8.4; not read by the
  exploration session)

**The LLM screenshot is captured at rotation 0:**
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/openspec/changes/archive/2026-09-28-e5c-opaque-screen/decision.md:164-168`
  (declared for E6; it also held for every E5 and E5b call)
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/HISTORY.md:474`
  (the E5c entry) and `:785-797` (the gate-15 verifier artifact with `back` in landscape; not the image bug)
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/openspec/changes/archive/2026-10-15-e6-campaign/protocol.md:610`
  and `:926` ("Landscape screens are captured at rotation 0.")
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/docs/tmp/e6-fork/dynamic-apks/plan_v3b.md:486-495`
  (change 22: the V3b smoke met the bug again on beatgame and excluded the landscape APKs)
- `/home/pedro/.claude/projects/-pedro-desenvolvimento-workspaces-workspaces-doutorado-workspace-rv-rvsec-study03-replication-package/memory/v3b-pending-2026-10-16.md`
  (item 1, open; the author's word to fix it in APE-RV came on 2026-10-17, D-A9)
- APE-RV branch `llm-opaque-screen` (abandoned, never merged): `openspec/changes/llm-opaque-screen/design.md` D13
  (`1b41c865`), `openspec/changes/llm-opaque-screen/evidence.md` (verified on device on retrowars, 2026-09-27),
  the fix `f993081f` + `3b35073a` and the device re-test `59d19f0c`

**The LLM dose and the MOP decision share:**
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/experiments/E6-campaign/results/mechanism/llm-arm/funil_passos_llm.out`
  (the arm-4 step funnel, 294,774 steps: 69.0 % called)
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/doutorado-tese/docs/estudo-03/escrita/esqueleto/20261015_fatos_e6v2.json`
  (facts `mech-llm-answer-*`, `expl-x54-*`, `rq-sq9-guidance-arm3-share-steps-cf-changed`; `[reported]`)

## What Changes

- **Five behaviour groups, mounted by declaration** (plan §4.1, D-A2, D-A18 Q-1): BASE (the APE algorithm plus the
  declared bug fixes), ADDONS (the fork's own improvements A–N), STATIC (consumers of the static GUI model), MOP
  (consumers of the spec targets), LLM. One plan key per group; ADDONS, STATIC, MOP and LLM switch independently.
  Without the static-analysis JSON every STATIC and MOP feature is off by declared dependency; switching such a
  group on without the JSON stays a plan error (`MISSING_DEPENDENCY`).
- **The data is split at load** (P-1): the loader reads wire format 2 (unchanged apart from D-B13's additive
  intent-filter `data` member) and yields two read-only providers, `StaticGuiModel` (WTG + widget metadata) and `MopData` (targets,
  distances, handlers, MOP activities). STATIC reads only the first; MOP reads both.
- **BREAKING**: `Feature.FRONTIER` is renamed `NAV_FRONTIER` and depends on the static provider, not on MOP (D-A1).
  Typed input reads the static provider.
- **The step becomes a fixed sequence of owners** (plan §4.2): guards (foreign/tree) → model update → exploration
  guards (Budget, restart; P-2) → LLM triggers and **LLM coin** → MOP launcher → component trigger (side effect)
  → **MOP coin** → SATA with the scorer chain → refiners (input text) → Monkey events. Each step records its
  owner in `dec.src` and the intended owner in `dec.own`. At most one LLM call is made per step.
- **LLM coin with declared gates** (D-A4, D-A6, D-A17, §4.4): `LlmGate { name(); admits(ctx); }` with
  `MinActionsGate(3)` and `BreakerGate`, composed from the plan. The SATA buffer is no longer a gate: the coin wins
  over a non-empty buffer, which is abandoned **without** `weakenStateTransition` (P-4, `dec.buf`). The new-state
  and stagnation triggers stay as today, without the coin. The `LlmGate` javadoc is corrected.
- **MOP coin** (D-A10, D-A15): after the LLM (or first, with the LLM off). Eligible when the screen holds an action
  with an unretired target weight not yet tried in this activity; picks the highest weight; `p_M` configurable,
  default 1.0. **BREAKING**: the MOP shortcut leaves `SataAgent` and becomes this decider.
- **Own RNG streams** (P-3): the LLM coin, the MOP coin, the component coin and fuzzing draw from streams derived
  from the run seed, every step, before any gate.
- **Scorers are additive and independent** (R1, §4.3): each writes its own field and never reads the current
  priority. SATA reads weights only where APE already does (roulettes, least-visited tie). Extension points carry
  the APE default including RNG consumption: X1 least-visited tie (R2: largest scorer sum; with zero scorers this
  is APE's first-of-array), X3 ABA target tie (APE: never replace on a tie; MOP variant: MOP activity), X4 path to
  a trivial activity (APE: always `randomPick`; MOP variant: largest density) (D-A7). X2 is out (D-A18 Q-4).
- **MOP launcher with global cadence** (D-A8, D-A16): the counter advances on every step; the launch happens on
  the first step that reaches the MOP/SATA branch; never before the LLM.
- **The prompt is built from the contributions of the mounted groups** (§4.6, D-A3, D-A18 Q-8): MOP contributes
  markers, count, `MOP NAVIGATION` and the MOP system-prompt sentences; STATIC contributes widget metadata. Without
  the group the section does not exist; no MOP instruction appears without MOP.
- **Component triggering moves to STATIC and is switched on** (D-B13, author 2026-10-17). It is off since gh15
  A-3 (`a8f2fa6d`) and was never set by a variant. Now it reads no MOP data, its dose is 0.03 in the STATIC block,
  its coin has its own stream, and the intent carries the intent filter's `data`/`type`. Until now the derive
  dropped that block (`derive_mop_artifact.py:1497-1503`) and the loader had no reader for it
  (`utils/MopData.java:745`).
- **No-key changes declared** (P-6): deterministic form fill and submit exclusion (addon F) become governed by
  `formCompletionEnabled`; the keyless bug fixes are listed in BASE.
- **Telemetry** (§5): new `dec` fields `own`, `cfc` (replaces `cf`), `draw`, `nav`, `inp`, `restart`, `bmc`,
  `strat`, `rot`, `buf`, `navf` and `trig`; `wtgsrc` removed; `dec.src` becomes the executed owner and `dec.ch`
  gains `mop_coin`, `budget` and `restart`; new `RUN_END` counters; the variant name in `RUN_START`, whose `v`
  becomes 2. Every new computation is pure (no RNG, no
  `resolveAction`, no cache effect). Completeness rule: every mechanism that can decide or change a step appears in
  the trace.
- **Rotation fix** (D-A9): port only the code files of `f993081f` and `3b35073a` from the abandoned branch
  `llm-opaque-screen` (never merged), plus `dec.rot` on every step.
- **BREAKING**: the presets are replaced by six cell presets, compositions of groups: `base`, `mop`, `as`, `as_mop`,
  `as_llm`, `as_mop_llm` (plan §6.2). The current presets (`aperv`, `mop`, `llm`, `llm_mop`) are removed (P3).
- Gates: `SinkNeutralityTest` with one preset per cell including the real control; a clean-control gate (a cell
  without MOP has every MOP field absent or 0); an RNG-neutrality test; new parity goldens captured on purpose.

## Capabilities

### New Capabilities
- `guidance-groups`: the five groups, their data dependencies, the one-key-per-group mounting rule, the split
  providers, and the six cell presets.

### Modified Capabilities
- `run-spec`: group keys, cell presets, `NAV_FRONTIER`, `MOP_LAUNCHER`, new keys (`p_M`, gate and breaker
  parameters), retired keys, variant name in `RUN_START`.
- `decision-pipeline`: the step sequence of owners, guards before the LLM coin, the MOP coin, the launcher cadence,
  own RNG streams.
- `llm-routing`: the coin, declared gates, buffer preemption, triggers' interaction with the coin.
- `action-selection`: the MOP shortcut removed from SATA, extension points X1/X3/X4 with APE defaults, buffer
  abandonment without weakening.
- `scoring-pipeline`: rule R1, scorer ownership by group, `NAV_FRONTIER` separated from `WtgPass` in telemetry.
- `mop-guidance`: MOP coin decider, launcher cadence, navigation tie-breaks as MOP variants, reads via `MopData`
  only for targets.
- `wtg-navigation`: the frontier reads `StaticGuiModel`, depends on STATIC, not MOP.
- `llm-prompt`: prompt built from group contributions; no MOP sentences without MOP.
- `llm-infrastructure`: screenshot captured at the display rotation.
- `event-sink`: the new `dec` fields, `RUN_END` counters, completeness and purity rules, neutrality gates.
- `heuristic-input`: typed input reads the static provider; `dec.inp`.
- `form-completion`: addon F governed by `formCompletionEnabled`.
- `parity-oracle`: new goldens for the `base` cell and the X1–X4 defaults.
- `component-triggering`: the trigger is a STATIC member at 0.03 with its own coin stream, its pool reads no MOP
  data, and the intent carries the filter's `data`/`type`; the launcher's global cadence.
- `exploration`: LLM hooks without the buffer gate, the restart as a guard, the fuzz stream, and the addon read
  sites (MODEL_MENU, the guards) reading the plan.
- `ui-coverage`: the UICOV `mopReach` token is written only when MOP is mounted.
- `activity-budget`: the budget and the activity-stable restart become ADDONS members read through the plan.
- `ui-tree`: the AndroidX/WebView enhancements read `TREE_ENHANCEMENTS` from the plan.
- `static-analysis-entrypoints`: the derived artifact's intent filters gain the additive `data` member.
- `aperv-tool`: the six cell variants and their push rules (mirror of the rv-android sister change).

## Impact

- **Code** (paths under `src/main/java/com/android/commands/monkey/ape/`): `runtime/{Feature,KeyOwnership,Presets,
  RunSpec,RunSpecEcho,RunContext}`, `telemetry/{EventSink,StepRecord,NdjsonSink,NoopSink,RunCounters}`,
  `agent/{SataAgent,StatefulAgent,ApeAgent,FormCompletion}`, `model/{State,ModelAction,MopCounterfactual}`,
  `agent/pipeline/*`, `agent/scoring/*`, `llm/{ApePromptBuilder,ScreenshotCapture,LlmClient,LlmCircuitBreaker,
  LlmEngine}`, `AndroidDevice`, `../MonkeySourceApe`, `tree/{GUITreeNode,GUITreeBuilder}`,
  `utils/{MopData,MopScorer,MopContext,TypedInputGenerator,ComponentInfo,Config}`; new `utils/StaticGuiModel`,
  `utils/StaticArtifactLoader`, `agent/StepRng`, `agent/MopInteractionLedger`, `model/ScorerCounterfactual`,
  `MopCoinStage`, `RestartStage`, gate, extension-point and prompt-contribution classes; their tests, the oracle
  scaffold and the goldens.
- **Wire format**: the artifact stays `formatVersion: 2`. `derive_mop_artifact.py` gains one additive member, the
  reduced `data` block of each component intent filter (D-B13, rv-android sister change); everything else in the
  derive is unchanged.
- **Cross-repository**: a sister change on branch `isolate-guidance-groups` in rv-android (`modules/aperv-tool`,
  owned by the `aperv-tool` session, plan §9): six variants = cells, the ten current variants deleted without alias
  (D-A13), the property mapping (P-8), the trace reader (P-9). It lands **together with** the first jar that
  changes the keys; a mismatched pair aborts at `RunSpec` resolution, which is the intended guard (§9.3).
- **Comparability**: a new tool; E6 is not reused as an arm; the parity oracle needs new goldens. The RNG sequence
  changes relative to `eea2b1a9` (P-3, X3/X4 defaults).
- **Branch**: all work on `isolate-guidance-groups` (D-A19); merged into `master` only after good results and the
  author's explicit decision.
