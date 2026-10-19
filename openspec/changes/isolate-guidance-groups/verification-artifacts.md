# Verification of the change artifacts — isolate-guidance-groups

Session of 2026-10-17 (evening), before `/opsx:apply`. Scope: the change artifacts at `558549a5` checked against
each other, the plan (`docs/plans/20261017_isolamento_grupos_aperv.md`), the author's decisions (D-A*, D-B*), the
code at `eea2b1a9`, and the rv-android sister change (`224a5a44`, contract v3.1).

Evidence: `[verified]` = read by the session with `file:line`; `[reported]` = from a read-only subagent and not
re-read. Only `[verified]` findings were edited. "Author" = needs (and got) an `AskUserQuestion` answer;
the answers are in § Author decisions of this session. Paths without a prefix are relative to the change
directory; code paths to `src/main/java/com/android/commands/monkey/ape/`.

## Author decisions of this session (2026-10-17)

| # | Question | Decision |
|---|---|---|
| A1 | Main specs that contradict the change with no delta | Write MODIFIED deltas for all of them, `aperv-tool` included; preamble sections are hand-synced at archive (task 13.4) |
| A2 | `dec.nav.alt` | `alt = 1` iff the MOP variant decided by its own rule (X3: it replaced the incumbent; X4: the density chose the path, no draw); X1 always `alt:0` |
| A3 | LLM calls per step | **At most one LLM call per step.** Once an LLM stage has called, the later LLM stages return `Continue` without calling (the author: otherwise a step can spend ~2 s and still have no LLM action) |
| A4 | `dec.ch` for the guards | two new channels, `budget` and `restart`; `sata_other` is SATA only again |
| A5 | X1/X3/X4 class names; frontier field | `<Variant><Interface>`: `ScorerSumLeastVisitedTie`, `ApeAbaTargetTie`/`MopAbaTargetTie`, `ApeTrivialPathPick`/`MopTrivialPathPick`; field `navFrontierBoost` |
| A6 | `Feature.ACTIVITY_TRIGGER` | renamed `MOP_LAUNCHER` (the feature only mounts `MopLauncherStage`); the key `ape.activityTriggerEnabled` is unchanged |
| A7 | Component-trigger telemetry | new member `dec.trig:{"k":"broadcast"\|"service"\|"provider","c":"<class>"}` on the record of the step on which the trigger fired; `dec.comp` stays the launcher's launch outcome |
| A8 | v17 without MOP | the visit part is not MOP and stays in every cell: without MOP the block is `NAVIGATION:` + `NEW state.`/`Visited Nx.`; with MOP the text is today's `MOP NAVIGATION:` block, unchanged. `UICoverageTracker` is untouched by this change `[verified]` |
| A9 | Wave-1 file collisions | enlarge wave 0 (enums and `navFrontierBoost` in `ModelAction`, the component renames, `StepContext` accessors, `RunContext`, `Config` twins, the static-read guard test) |
| A10 | Remaining `Config` reads | `doFuzzing`/`fuzzingRate`/`checkRestart` read the plan (D-B6 extended, no behaviour change). **Correction after the answer:** the question said `DYNAMIC_EPSILON` reads `Config.minEpsilon`/`maxEpsilon` — a `[reported]` claim I relayed unverified. In fact `ape.minEpsilon`/`ape.maxEpsilon` are already `DYNAMIC_EPSILON` plan keys (`KeyOwnership.java:242-243`) read through the plan (`SataAgent.java:1214`); only their `Config` twins (`Config.java:218-219`) are dead. Declaring them "BASE `Config` constants" would have retired two keys, so the artifacts keep them as plan keys and delete the twins (no behaviour or key change) |

## Findings

Severity: **B** blocker · **M** major · **m** minor. "Fix" = what was done (or proposed, for the sister).

### A. Plan ↔ change

| id | artifact | where | defect | sev | evidence | fix | author |
|---|---|---|---|---|---|---|---|
| V01 | proposal | `proposal.md:80-82` | the step sequence omits the launcher and the component trigger | m | [verified] | sequence completed | no |
| V02 | proposal | `proposal.md:90` | own streams list omits the component stream | m | [verified] | added | no |
| V03 | proposal | `proposal.md:127` | "new keys (… X1 variant)" — X1 has no key (D-B4, D-A18 Q-5) | m | [verified] | removed | no |
| V04 | design, specs | `design.md:118`, `wtg-navigation:9`, `mop-guidance:9,155` | "format 2 unchanged / derive untouched" contradicts D-B13's additive `data` member | m | [verified] | "unchanged apart from the additive intent-filter `data` member (D-B13)" | no |
| V05 | proposal | `proposal.md:151-155` | Impact cites `agent/MopCounterfactual` (it is `model/`) and omits ~15 touched files | m | [verified] | Impact list rewritten | no |
| V06 | design | `design.md:5,199` | "D-B1…D-B12" although D-B13/D-B14 exist; D-B13 sits before D-B12 | m | [verified] | wording fixed; D-B12 moved before D-B13 (D-B14 item numbers unchanged) | no |
| V07 | plan | plan §4.7 P-6 | P-6 asks the keyless bug fixes to be listed "in design.md"; they are listed in `guidance-groups` (`:303-313`) only | m | [verified] | design § Context points at that list | no |

Checked and consistent `[verified]`: D-A5/D-A17 triggers kept with no coin; D-A6 coin wins over the buffer; D-A16
global cadence; X2 out (Q-4); no scorer vector (Q-13); D-A9 code files only (`git show --stat f993081f 3b35073a`:
`ScreenshotCapture.java`, `AndroidDevice.java`, `ScreenshotCaptureStageTest.java` + `CLAUDE.md` and the
`llm-opaque-screen` tasks, which are not ported); `git diff --stat e93dea86 eea2b1a9` on both files is empty.

### B. design.md internal consistency

| id | where | defect | sev | evidence | fix | author |
|---|---|---|---|---|---|---|
| V08 | `design.md:312` vs `:364` | D-B13 lists four `data` lists, D-B14.11 five (`pathPrefixes`) | m | [verified] | D-B13 aligned on D-B14.11 | no |
| V09 | `design.md:485` vs `:547` | `dec.rot` "int 0..3" vs `-1` when unreadable | m | [verified] | `-1..3` | no |
| V10 | `design.md:347` | D-B14.5 names `triggerMopComponent` (renamed by D-B14.15) and states an `OracleSataAgent` override that does not exist (`OracleSataAgent.java:44-58` overrides only `getRandom()`) | M | [verified] | rewritten as an obligation (task 11.2) with `triggerComponent`; side effect recorded as `dec.trig` (A7) | A7 |
| V11 | `design.md:527-529` | Data Flow loads "when STATIC_DATA is in the plan" (D-B14.20: iff STATIC or MOP) and hands `StaticGuiModel` "to STATIC consumers" only; MOP members (`WtgPass`, `MopFrontierPass`, `MopScorer`, the launcher) read the WTG and the components too (plan P-1: "MOP lê os dois") | M | [verified] | fixed | no |
| V12 | `design.md:99`, `:261-267`, `:515` | step 3 omits the component coin; stream named `component` vs `componentCoin()` | m | [verified] | unified on `componentCoin` | no |
| V13 | `design.md:472-487` | telemetry table lacks `dec.ch` (D-B14.17) and the guard channels; `dec.trig` absent | m | [verified] | rows added (A4, A7) | A4, A7 |
| V14 | `design.md:574` | clean-control list lacks `mopReach`, `ACT.mop`, `ch=mop_coin`, MOP keys of `cfc`, `nav.c=mop`, the MOP counters | m | [verified] | completed (D-B14.9/21) | no |
| V15 | `design.md:352-353` | D-B14.6 `own.i` omits the guards | m | [verified] | guards added | no |
| V16 | `design.md:505` | X4 is `Path pick(List<Path>)`; the code type is `Subsequence` (`SataAgent.java:1339`) and the action-selection delta says so | m | [verified] | `Subsequence` | no |
| V17 | `design.md:250-258` | D-B8 is silent on several LLM calls in one step; today up to three are possible (`LlmNewStateStage.java:72-77` returns `Continue` after a failed call) | M | [verified] | at most one call per step (A3); D-B8, D-B14.14 | A3 |

Checked and consistent `[verified]`: `COMPONENT_TRIGGER` under STATIC in the four `as*` cells and absent from
`base`/`mop`; `mopWeightOpenMenu` absent from the MOP block; `MENU_GATEWAY` absent from `mop`, present in
`as_mop`/`as_mop_llm`; pipeline order in Architecture = D-B7 = tasks 7.1. `STATIC_DATA` is in `features` when the
path is inert (guidance-groups/run-spec give `[FUZZING, STATIC_DATA]`); the key goes to `inert` (V40).

### C. Specs ↔ design

| id | delta | where | defect | sev | evidence | fix | author |
|---|---|---|---|---|---|---|---|
| V18 | event-sink | `:17-21` | INV-SNK-14 collides with the heartbeat invariant of `archive/2026-08-07-rearch-04-step-ndjson-telemetry` (`:53`), cited by `specs/aperv-tool/spec.md:266` | M | [verified] | new invariants renumbered INV-SNK-15…19 across the change | no |
| V19 | wtg-navigation | `:86`, `:97-99` | `as_mop` total +400 vs scoring-pipeline `:250-252` +600 (MopFrontierPass also applies) | M | [verified] | +600 with the three fields; INV-WTG-07 qualified | no |
| V20 | action-selection, event-sink, mop-guidance | `action-selection:54,136`; `event-sink:46`; `mop-guidance:541` | `dec.nav.alt` defined two incompatible ways | M | [verified] | A2 | A2 |
| V21 | decision-pipeline | `:213`, `:280-281` | `dec.own.i` = the selecting stage, against event-sink/llm-routing/D-B14.6 (first claimer) | M | [verified] | first claimer | no |
| V22 | llm-routing, decision-pipeline, exploration | `llm-routing:233-240` | several LLM calls per step undefined | M | [verified] | A3 + scenario | A3 |
| V23 | llm-routing (main) | `specs/llm-routing/spec.md:139,488,504` | "Action Selection Pipeline" passes `MopContext` from `ctx.mopData()`; "llmPercentage clamping" and "NoSubstrate seam" name `shouldRouteRandom()`; no delta | M | [verified] | MODIFIED blocks | A1 |
| V24 | component-triggering | `:242`, `:264` | "intent-filter data no longer exists on the wire" / "no jar code inspected an intent-filter" — false after D-B13 | M | [verified] | scoped to the activity launcher | no |
| V25 | event-sink, component-triggering, decision-pipeline | `event-sink:31,73`, `decision-pipeline:301` | the component trigger's side effect has no field; `dec.comp` is the launcher's outcome (`MonkeySourceApe.java:980`) | M | [verified] | `dec.trig` (A7) | A7 |
| V26 | parity-oracle | `:109` | stage "excluded from the decision records … device-bound" vs D-B14.5 | M | [verified] | override + recorded `dec.trig` | no |
| V27 | guidance-groups | `:403` | "no `MopData` SHALL be constructed" vs INV-GRP-05 and D-B14.21 | M | [verified] | "`getMopData()` SHALL be null" | no |
| V28 | guidance-groups, event-sink | INV-GRP-07 `:119-123`; clean-control `:412` | INV-GRP-07 narrower than the clean-control invariant; `mopReach` missing in both | M | [reported] → checked | INV-GRP-07 points at INV-SNK-18; `mopReach` added | no |
| V29 | run-spec | `:108` | D-B14.4 (`ape.llmUrl` with LLM off aborts) has no scenario | M | [verified] | scenario added | no |
| V30 | mop-guidance | — | D-B14.21 (`getMopData()` null iff MOP) is in no delta (`grep mopReach` empty) | M | [verified] | requirement text + scenario | no |
| V31 | exploration, llm-routing | `exploration:276`, `llm-routing:146` | restart "on the next step" vs decision-pipeline (same step, stage RESTART) | m | [reported] | same step | no |
| V32 | exploration | `:70-73` | threshold 100 "reaches 100" vs "exceeds" (decision-pipeline uses 101) | m | [reported] | 101 | no |
| V33 | action-selection, event-sink | `action-selection:168,252`; `event-sink:35` | no `ch` for budget/restart | m | [verified] | A4 | A4 |
| V34 | exploration | `:294` | DYNAMIC_EPSILON params from `Config` | m | [reported] — **false**: they are plan keys (`KeyOwnership.java:242-243`, `SataAgent.java:1214`) [verified] | spec states the plan keys; dead `Config` twins deleted (task 1.8) | A10 |
| V35 | scoring-pipeline | `:51,55` | `MopWidgetPass` mounted by the group root, not a member | m | [reported] | wording | no |
| V36 | decision-pipeline, scoring-pipeline | `dp:210`, `sp:97`, `dp:368-369` | references to another change's D14/D1 unnamed; duplicate assertion | m | [reported] | fixed | no |
| V37 | run-spec | Invariants | INV-RUN-08 (single seeded stream) not amended for `StepRng`; `ape.variant` not in the INV-RUN-04 digest exclusions | m | [reported] | amended | no |
| V38 | parity-oracle, event-sink | `po:11-13,35-36,98`; `es:18` | "three `StepRng` streams" (there are four) | m | [reported] | four | no |
| V39 | guidance-groups | `:96-98`, `:147` | INV-GRP-01 forgets the BASE root `FUZZING` | m | [reported] | exempted | no |
| V40 | guidance-groups, run-spec | `gg:347-355,373-375`; `rs:327` | read-site table omits `FORM_COMPLETION`; inert `ape.staticDataPath` qualifies for `params` and `inert` | m | [reported] | row added; `inert` only | no |
| V41 | component-triggering | `:269`, `:242,252` | "same `[APE-STEP]` line" (retired); INV-CT-13 dangling (pre-existing) | m | [reported] | "step record"; INV-CT-13 reference dropped | no |
| V42 | heuristic-input | `:22` | STATIC typed input calls `MopScorer.containmentNodes` | m | [reported] | stated as a tree-only helper | no |
| V43 | mop-guidance | `:38` | "first decider after the guards" — launcher and component trigger come first | m | [reported] | fixed | no |
| V44 | llm-prompt | `:288`, `:318-321`, `:396` | v17 without MOP drops the whole block, visits included | M | [verified] | A8 | A8 |
| V45 | event-sink | `:86` | `coinLlm` ambiguous | m | [reported] | "steps that reached `LlmCoin` with `u_L < p_L`" | no |
| V46 | llm-infrastructure | `:93-94,165` | breaker at `ape.llmBreakerFailures=0` undefined | m | [reported] | not mounted = never consulted | no |
| V47 | event-sink, llm-routing | `es:42`; `lr:242-244` | `fb=no_match` worded differently; sentence about a design value `coin` that does not exist | m | [reported] | aligned; sentence deleted | no |
| V48 | llm-prompt (main) | "Element List of the v13 and v17 Variants" | "`mop` null" | m | [reported] | MODIFIED (A1) | A1 |
| V49 | all | `tasks.md:97,109-110`; `mop-guidance:130` | `navfBoost`/`navFrontierBoost`; `MopAbaTie`/`MopAbaTargetTie`; `ScorerSumTie` | m | [verified] | A5 | A5 |
| V50 | all | `DecisionPipeline.java:72` | `Feature.ACTIVITY_TRIGGER` mounts only the launcher; `MopParams.activityTriggerEnabled()` has no caller (`RunSpec.java:974-975`) | m | [verified] | A6 | A6 |

**Main specs with no delta** (A1) `[verified]`:

| id | main spec | requirement | stale text | fix |
|---|---|---|---|---|
| V51 | ui-coverage | Coverage Dump (`:218-250`) | `mopReach=0` when `_mopData` is null | MODIFIED: omitted without MOP (D-B14.21) |
| V52 | activity-budget | Activate activityStableRestartThreshold (`:108`); Config Flags (`:121`); Budget Check (`:62`) | `Config` default 200; `Config.activityBudgetEnabled` | MODIFIED: ADDONS members, MAX_VALUE when absent |
| V53 | ui-tree | ViewPager Scroll Direction (`:77`); WebView Pruning Correctness (`:193`) | `Config.treeEnhancementsEnabled` default true | MODIFIED: `spec.has(TREE_ENHANCEMENTS)` |
| V54 | static-analysis-entrypoints | the projection requirement (`:189`) | intent filters `actions`+`categories` only | MODIFIED: the additive `data` member |
| V55 | exploration | Priority-Based Action Selection (`:343`); Configuration Loading (`:488`); Config Flags for Dynamic Epsilon (`:800`) | `Config.mopDataPath`; threshold 200; `Config.dynamicEpsilon` | MODIFIED |
| V56 | aperv-tool | Tool Variants (`:32,70`); configure() (`:83-94`); execute flow (`:130-190`) | old presets, `ape.mopDataPath` | MODIFIED: cells, `ape.staticDataPath`, push rules (mirrors the sister change) |
| V57 | several | Purpose / Data Contracts / Invariants preambles: action-selection `:10`; component-triggering `:7,18`; mop-guidance `:5,896-897`; parity-oracle `:19,36-43,63`; run-spec `:6`; wtg-navigation `:28`; exploration INV-EXPL-06, INV-EPS-02; ui-tree INV-TREE-02/11 | not synced by archive | task 13.4 names them for the hand-sync |

Checked and consistent: OpenSpec mechanics on all 16 deltas (MODIFIED headers equal the main headers; every
main scenario title kept; REMOVED blocks carry Reason and Migration; every requirement has a concrete scenario)
`[reported]`, with `openspec validate --strict` green `[verified]`. Cross-spec agreement on buffer abandonment,
the restart guard and retirement, and the component trigger as a STATIC member `[reported]`. No stale vocabulary
in ADDED/MODIFIED text `[reported]`.

### D. Specs/design ↔ code

| id | where | defect | sev | evidence | fix | author |
|---|---|---|---|---|---|---|
| V58 | design, tasks | `ApeAgent.java:236-253` → `:235-253`; `MonkeySourceApe.java:877` → `:876`; `8f51b99c` path is `src/com/…/utils/Config.java`; test `ApeAgentInputTest` is `ApeAgentLlmInputTest` | m | [reported] | corrected | no |
| V59 | tasks 9.1 | `ApePromptBuilder.java:283` (v17 "no navigation or MOP target"), `:763` ("monitored operations"), helper call sites `:371,:416,:740,:798,:863` not listed | M | [verified] `:283` | listed | no |
| V60 | tasks 8.7 | `StatefulAgent.java:1445` `getMopData().getPackageName()` in `dispatchTrigger` — NPE in `as` | M | [verified] | task 8.7 reads `StaticGuiModel` | no |
| V61 | tasks 7.7 | `ComponentTriggerStage.java:69` `ctx.mopData().hasComponents()`; `DecisionPipeline.java:178` `spec.mop().componentPercentage()` | M | [verified] | task 7.7 | no |
| V62 | tasks 5.x | `MopData.java:304` `spec.mop().strictPackageMatch()` (moved to STATIC_DATA by D-B14.3) | M | [verified] | task 5.1 | no |
| V63 | tasks 7.5 | `MopLauncherStage.java:138-157` reads activities/main/package from `MopData`, which F2 moves to `StaticGuiModel` | M | [verified] | task 7.5 | no |
| V64 | tasks 8.7.1 | `StatefulAgent.java:1363` emits the name-only tuple only when `targetMethods` is non-empty (a MOP signal, against D-B14.15) | M | [verified] | named | no |
| V65 | tasks | renames `mopComponentTargetCount` (`StageCollaborators.java:172`), `requireMopArm` (message names `ape.mopDataPath`); provider tuples `:1374` | m | [verified] `:172` | wave 0 | no |
| V66 | tasks 1.5 | `RunSpec.java:800` returns `backMenuPickCap` without a `has()` gate; `BACK_MENU_CAP` absent must read 0 | M | [reported] | 1.5.5 | no |
| V67 | tasks wave 0 | `StaticConfigReadGuardTest.java:139-151` pins `modelMenuEnabled` as the one accepted residue (1.8.1 breaks it) and bans `RunContext.current()` in `agent/pipeline`, `agent/scoring`, `llm`; `LadderConfigGuard`, `ConfigTest:100` read the twins | M | [verified] `:139-151` | wave 0 task | A9 |
| V68 | tasks wave 0 | `Config` twins of retired/renamed keys (`mopDataPath`, `mopTargetPickCap`, `leastVisitedPriorityTiebreak`, `frontierBoostWeight`) and of `doFuzzing`/`fuzzingRate`/`checkRestart` | M | [reported] | wave 0 task | A9, A10 |
| V69 | tasks | `DecisionSource` (owner vocabulary) and `PickChannel` (`ModelAction.java:50-55`) change in no task; `ModelAction` is F2's, but F3/F4/F5 need it | M | [verified] | wave 0 (A9) | A9 |
| V70 | tasks 3.8 | deleting `MopCounterfactual` forces edits in `SataAgent` (4 sites) and `StateTest` | m | [reported] | stated | no |
| V71 | tasks wave 1 | `StageCollaborators` is implemented by `SataAgent` (`:67`, F3), `StepContext` by `StatefulAgent` (`:88`, F5); `RunContext` (`:155-166`: `LlmClient`, `LlmEngine`, `ApePromptBuilder`) has no owner; the breaker is built in `LlmClient.java:57`; typed-input helper (`ApeAgent.java:236-250`) claimed by F2 and F5 | M | [verified] | wave 0 (A9); ownership lists fixed | A9 |
| V72 | tasks 11.2 | no task for the `OracleSataAgent.triggerComponent` no-op; `ParityOracle{Aperv,Mop,Llm,LlmMop}Test` and the pipeline tests that name old presets not listed | M | [verified] | 11.2 | no |
| V73 | tasks 2.4 | `NdjsonSinkTest` (~35 `decision(...)` calls) and `LlmTelemetryTest` (~12) not named | m | [reported] | named | no |

Every other citation of § Current state and of the tasks holds `[reported]` (list in the subagent report).
`ACTIVITY_TRIGGER` mounts only the launcher `[verified]` (`DecisionPipeline.java:72`); the pool takes receivers,
services and providers only.

### E. tasks.md ↔ design/specs

| id | where | defect | sev | evidence | fix | author |
|---|---|---|---|---|---|---|
| V74 | `tasks.md:209` | 11.9 installs into the shared `rvsec` tree (`/home/pedro/…/rvsec`, on `modules` with other sessions' work) instead of the worktree root the sister expects | M | [verified] | `-Drvsec_home=/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/worktrees-aperv/isolate-guidance-groups` | no |
| V75 | `tasks.md:193` | 10.2 "the renamed `static_data_path`" — the sister writes `ape.staticDataPath` as a fixed line, not a mapping entry | m | [verified] | reworded | no |
| V76 | tasks 7.x | `dec.own` is "always" written, but only 7.3 (LLM mounted) writes it | M | [verified] | 7.1 writes it on every step | no |
| V77 | tasks 7.x | `coinLlm`, `gateMinActions`, `gateBreaker`, `ch=mop_coin` have no task | m | [verified] | added to 7.2/7.3/7.6 | no |
| V78 | tasks 1.8/1.10 | after 1.8 the addon sites read the plan: a test that relied on a `Config` default (e.g. `modelMenuEnabled=true`) with no preset changes behaviour; the old presets need `addons`/`static`/`mop` keys | M | [verified] | 1.4/1.10 say so | no |
| V79 | `tasks.md:205` | 11.5 clean-control list lacks `mopReach`, `ACT.mop`, `ch=mop_coin`, MOP `cfc` keys, `nav.c=mop`, MOP counters | m | [verified] | completed | no |
| V80 | `tasks.md:20` | `TYPED_FUZZ` cited as D-B13 | m | [verified] | D-B14.3 | no |

Checked `[verified]`: wave-2 gates present (11.4 sink neutrality per cell, 11.5 clean control + RNG neutrality,
11.6 base parity + cell fixtures, 11.8 `mvn clean test`, 11.9 install); final Verification group 13 present;
group 10 cites the rv-android fronts.

### F. APE ↔ rv-android sister change (`224a5a44`)

Aligned `[reported]`: six variants with `default` = `base`; artifact iff STATIC or MOP; `broadcast_catalog` on the
four `as*` cells; `ape.preset` + `ape.variant` fixed lines; `llm_url` only on `*_llm`; retired keys never emitted;
new keys mapped; `mopWeightOpenMenu` override-only; reader names and shapes; derive `data` = D-B14.11; task ids
3.10, 5.6, 11.6, 11.7, 11.9, 12.1.

| id | sister | defect | sev | action |
|---|---|---|---|---|
| V81 | `design.md:266`, `spec/aperv:59` | `fb` enum includes `coin`, which the jar never writes | m | message to `aperv-tool` |
| V82 | `campaign-analysis spec:46-61` | clean-control gate drops `ACT.mop` and the `RUN_END` MOP counters | m | message |
| V83 | `tasks.md:113` (S.4) | "UICOV `mopReach` … open question" — resolved by D-B14.21 | m | message |
| V84 | spec/aperv:7, design:4, :203 | contract labelled v1/v3/v3.1; "ten new members" (eleven) | m | message |
| V85 | `design.md:338-341` | records without `dec`: closed by INV-SEL-05 (every record carries `src`/`ch`/`own`) | m | message |
| V86 | — | contract diff of this session (A2, A3, A4, A6, A7, `rot` −1..3) | — | sent as v3.2 |

## Re-validation

`openspec validate isolate-guidance-groups --strict` after every fix of this session: **valid** (21 deltas: the 16 of `558549a5` plus `ui-coverage`, `activity-budget`, `ui-tree`, `static-analysis-entrypoints` and `aperv-tool`). The contract diff v3.1 → v3.2 was sent to `aperv-tool` (V86).
