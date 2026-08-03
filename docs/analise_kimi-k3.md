# Preliminary Architecture Study — Re-architecting APE-RV

**Model:** Kimi K3 (Moonshot AI), exact identifier `openrouter/moonshotai/kimi-k3`
**Date:** 2026-08-01
**Commit analysed:** `5dcf225976b26ce78d8b31dd88d7f858dad29d43` (`git rev-parse HEAD`, branch `master`)
**Upstream baseline read:** `github.com/tianxiaogu/ape @ 8f51b99`, cloned to `/tmp/opencode/ape-upstream` and diffed against this tree (plus the local unmodified checkout at `../ape-original`).

## Method

Six subagents were run in parallel; every load-bearing claim in the prompt's evidence dossier (§3) was re-verified against the code at `5dcf225` before being relied on:

1. **Core hotspots** — `ApeAgent.createAgent`, `SataAgent.selectNewActionNonnull`, `LlmRouter.selectAction`, `MonkeySourceApe.generateEvents`, `StatefulAgent`, `Config`, the scoring pipeline, `DecisionSource`/`PickChannel`, `ActionType` ordinals, `Config.` read-site counts, `Monkey.java` lifecycle. *Result:* substance of the dossier confirmed; ~20 line-number drifts and 8 factual corrections (Appendix Z).
2. **Memory / testability / upstream diff** — object-lifetime graph, the 11 ungated fork changes (each diffed against upstream `8f51b99`), test counts, `pom.xml` stub exclusion, static state. *Result:* confirmed with three count corrections (Graph holds **17** unbounded collections, not 13; **33** new files, not 32; 15 `@Ignore` + 2 `Assume` = 17 skip sites, not ~19).
3. **rv-android execution layer** — `aperv-tool/.../tool.py` (all structures verified verbatim), pytest guards, `rv-platform` registration, `subsystem-rv-experiment.md`, the 2026-07-24 calibration report (all numbers located), truncation docs, DSL parsers, coverage reconstruction. *Result:* confirmed; notable corrections in Appendix Z (e.g. the app-vs-library filter in rv-android is a **substring** containment check at `static_analysis_parser.py:356-361`; the phase-2 paper's *own* prefix test lives in `ase-journal/data-analysis/mneut_scope.py:150-157` — the dossier conflated the two).
4. **Research context** — `doutorado-tese` (DSR declaration `tex/1_Introducao.tex:151,174`, pre-declared phase-3 design `tex/5_Cronograma.tex:18`, cycle table `:35-62`) and `ase-journal` branch `jss-jca` (`constants.tex`, `results-rq*.tex`, `data-analysis/` NB pipeline). *Result:* all numbers verified; several "constants" are prose literals in `results-rq*.tex`, not macros; "76/163 apps" is derived (163 − 87, `constants.tex:91` + `results-rq2.tex:28-29`).
5. **Open change / specs / prior docs** — `openspec/changes/telemetry-proof-llm-efficacy/` (18 commits, +2,674/−158 in `src/`, `Config.java` byte-identical — verified by `git diff`), `docs/20260708_arquitetura_separacao_aperv.md`, all capability specs (19, not 20) and the INV-* definition sites.
6. **Prior art** — local checkouts (DroidBot, DroidBot-GPT, DroidAgent, Stoat, QTesting, SceneDroid, GoalExplorer, ComboDroid, ape-original) inspected with `file:line` evidence; Fastbot2 / Humanoid / ARES / GPTDroid / AOSP Monkey at web level, marked **(web)**.

**Taken on trust (not independently verified):** Fastbot2 `.fbm` persistence and ARES internals (web); ComboDroid decision internals (closed jars); the static-analysis dataset statistics of §3.9 (schema and sizes spot-checked only). Everything else carries a `file:line` citation or is labelled *(inference)*.

## Executive summary

APE-RV's seven visible tensions collapse into **four root causes**: (R1) there is no first-class representation of *what this run is* — hence modes in Python, 112 frozen statics, and precedence encoded as statement order; (R2) nothing *owns* exploration state — hence five independent memory retainers and a teardown-only write; (R3) there is no *event* abstraction — hence telemetry as scattered print side-effects and attribution that stops one step short of causality; (R4) the jar has no explicit *boundary contracts* — with the host process (adb/Python: truncated runs counted as successes) and with upstream (`apePureMode` is a near-pure fiction that silently includes 11 behavioural changes and 3 welded constructs).

Three candidates are developed, differing in organising principle, not in naming:

- **C1 — RunSpec + Decision Pipeline** (decision-centric): make run identity an immutable, validated, echoed object; make the per-step decision an explicit pipeline of typed stages; modes become named presets in a jar-resident registry whose every run echoes its effective configuration.
- **C2 — Event-Sourced Explorer** (data-centric): the authoritative artifact of a run is a single append-only typed event stream written on-device; the model is a fold over events; memory, telemetry, resume and attribution all derive from the stream.
- **C3 — Two-Lane Provenance Split** (packaging-centric): physically separate upstream APE (fossilised lane, own artifact) from the APE-RV composition lane; D1 solved by construction; the kill-switch is deleted.

**Recommendation:** adopt **C1 as the spine** now (best simplicity-adjusted score, stageable by one maintainer while experiments run); adopt **C2's event log as a scoped subsystem** for decision/outcome/run telemetry and the logcat step-heartbeat that closes the violation↔step gap — *without* full event sourcing of the model; and adopt **C3's lane-A build as a verification oracle** for an upstream-equivalence harness, not as a shipped control arm. The work decomposes into seven independently valuable, separately shippable steps (Part D). The three questions this recommendation depended on were answered by the owner on 2026-08-01 and are recorded below; the report was revised in place to reflect them.

## Owner decisions (2026-08-01, post-publication Q&A)

1. **Q1 (D1) — decided: "APE + documented bugfixes" is an acceptable control arm.** C3's lane A is *not* shipped as the control; it is built only as a verification oracle (step 7). Refinement this enables: the four *semantic* ungated deltas (T7) are no longer gated by default — all 11+3 divergences are documented in a divergence register and measured by the oracle (E5); gating or reverting is reserved for any delta whose measured effect is material. This removes the riskiest C1 sub-task (touching the naming core).
2. **Q2 (D7) — decided: the jar may write a write-only step heartbeat to logcat.** The violation↔step join (step 3) proceeds as designed; the recorded constraint "APE never touches logcat" (`docs/20260702_roadmap_mop_fairtest_changes.md`) is clarified to cover reads/outcome-coupling, not write-only markers. The weaker logcat-free fallback is dropped.
3. **Q3 (D2) — rejected: no shared `ape-modes.json` as a cross-repo single source of truth.** The mode authority moves fully jar-side instead: presets are a compiler-linked Java registry selected by a now-total `--ape`; Python keeps full freedom to define arms. Drift is controlled *without a shared artifact* by (i) fail-fast startup validation (unknown preset/key/dependency violation aborts in the first seconds — INV-MOP-22 generalised), (ii) the per-run `[APE-RUNSPEC]` echo, (iii) echo-vs-intent verification in rv-platform result processing, and (iv) an optional CI diff of the jar's `--ape-print-schema` dump against Python's pinned key set. The accepted trade-off: key-level drift is caught at task start (a loud, correctly-accounted failed task) rather than at arm-definition time — detection, not prevention. Sections B1, C1, C2, C3, Part D and Part E were revised accordingly; the candidate ordering and the 7-step decomposition are unchanged except step 5's content.

---

# Part A — Diagnosis

## A1. What APE-RV is today, structurally — the real layering

The documented layering ("Monkey → Agent → Model → Naming") is aspirational. The *real* dependency structure, as measured:

```
L8  [Python, other repo]  rv-experiment → rv-platform → aperv-tool (tool.py)
        owns: arm names (26), key translation (52 pairs), deploy, hard timeout
        ═══════════ subprocess boundary: files + command lines only ═══════════
L7  Telemetry: Logger (stdout, no sink/level/timestamps — Logger.java:22 compile-time
        debug flag) + 11 structured channels, space-separated key=value, no schema
L6  Config: 112 public static fields frozen at class-load (Config.java:32-44);
        apePureMode = Properties overwrite before field init (:36-43,:287);
        string-literal registries (:343-398) with no compiler link to fields
L5  Decision: SataAgent (1762 LOC) / StatefulAgent (1904 LOC)
        precedence = statement order in selectNewActionNonnull (:449-589);
        scoring = ScoringPipeline of 7 passes at one injection point
        (assembly StatefulAgent.java:201-208, apply :1631);
        LLM routing split across SataAgent (ordering/preconditions) +
        LlmRouter (predicates :232-281) + StatefulAgent (re-arm :1436)
L4  Model: Graph (17 unbounded collections, :98-130) / State (treeHistory :56,
        never truncated) / Model (actionHistory :136-137, "may be the cause of OOM");
        ModelAction now carries provenance fields (DecisionSource 11 values :42-44,
        PickChannel 8 values :57-65, boost fields :91-107)
L3  Abstraction: Naming/NamingFactory — upstream CEGAR core, fork-modified in
        behaviour at Naming.java:429-431 and NamingFactory.java:280,1180
L2  Perception: GUITreeBuilder (3 static caches :670,:671,:693 — the per-node one
        never cleared by release() :707-715) → GUITree/GUITreeNode
L1  Device: AndroidDevice — 7 public static mutable service handles (:61-73);
        version differences via reflection (ApeAPIAdapter; startService :456-507)
L0  AOSP Monkey: event queue, IActivityController, deadline loop (:1293-1302),
        CLI parse (:921-932), usage advertises 2 agent types (:1598)
```

Three structural facts follow. **(a) The cross-cutting concerns (config, telemetry, provenance) are not layers — they are horizontal statics read from ~200 sites** (`Config.` appears 286 times in `src/main`; the top five files hold 57/36/27/26/21 combined reads). **(b) The only genuine composition seam that exists is the scoring pipeline** — and its decoupling is partly nominal: pass constructors read static `Config` (`CoveragePass:19-20`, `FormCompletionPass:21-22`, `FrontierPass:33-35`, `WtgPass:27-29`, `MopFrontierPass:36-38`), and the `Config` parameter of `ScoringPipeline.fromConfig` is decorative by its own javadoc (`ScoringPipeline.java:48-49`). **(c) The mode system does not live in the jar at all** — it lives in Python (`tool.py:427-659`), in another language, in another repository, guarded by tests that validate a Python constant against itself (`test_aperv_tool.py:591-600`).

## A2. Why the control surface is where it is — the historical argument

This structure has *reasons*, and a redesign that ignores them will recreate it:

1. **The execution model offers no composition points.** The tool is a Dalvik process launched via `app_process` with the AOSP `Monkey` entry point; there is no framework, no DI container, no lifecycle beyond `main`. The cheapest way to carry a policy into that process is a static field read at class-load — and that is what `Config` is (`Config.java:32-44`). Every subsequent feature took the path of least resistance that the first one established.
2. **Each research phase added its mechanism at the point where its behaviour was needed, under experimental deadline.** MOP guidance needed to bias action choice → guarded blocks in `selectNewActionNonnull`. LLM guidance needed the same → three more guarded blocks in the same method. Telemetry needed provenance → fields welded into `ModelAction` (`:91-107`). The `telemetry-proof-llm-efficacy` change is the purest example: **16 behavioural items, +2,674/−158 in `src/`, and `Config.java` byte-identical** (verified: `git diff c41d735..5dcf225 -- .../Config.java` is empty). Behaviour changed; the control surface did not — because adding a flag is the *expensive* path in this design (flag + kill-switch registration + Python mapping + 26 arm updates), not the cheap one.
3. **The arm system lives in Python because the task matrix lives there.** The unit of work is `(APK, tool, variant, repetition, timeout)` (`subsystem-rv-experiment.md:81-84`); a variant *is* a flag bundle by construction (`rv-tools/.../factory.py:127` merges `{**variant_config, **parameters}`). The jar never needed a "mode" concept because Python always spoke to it in flags. The 2026-07-08 redesign (`docs/20260708_arquitetura_separacao_aperv.md:21-35`) correctly scoped itself to scoring and explicitly chose "one jar, behaviour by flag composition" — it solved the layer it touched and left everything else in place.
4. **The result is a split-brain with no contract at the seam.** Python owns *which combinations exist* and their names; Java owns *what each flag means*, its default, its clamps, and the kill-switch. Only 52 of 117 jar keys are reachable from the experiment layer (`tool.py:75-162`, `:900-901`). Drift fails silently — a renamed property is ignored by `Config`, unvalidated by Python, green under all 83 pytest guards — and has already bitten: the stale committed jar that made the MOP boost fire in 0 of 147,153 evaluations (gh71); the dead `mop_weight_activity` mapping (`tool.py:91-95`, target deleted from `Config.java`); the two stale "inert" comments (`tool.py:152-159`, `:216-219`) for keys that are now live (`Config.java:222-223`).

## A3. The seven architectural tensions — and the four root causes

Each tension is stated with its evidence and traced to its driver(s). The contribution here is the *causal structure*: §A3.8 shows that seven tensions are four problems wearing seven coats.

### T1 — Four policies' precedence exists only as statement order (D2, D3, D4)

`SataAgent.selectNewActionNonnull` (`SataAgent.java:449-589`, 141 LOC, measured) is a linear sequence of flag-guarded blocks, each with its own `return`: logging (:450-462); activity-budget gate with a deliberate documented fall-through (:463-477); LLM new-state hook (:479-487); LLM stagnation hook (:488-506); LLM random hook (:507-515); MOP launcher (:516-545, `shouldFireLauncher` taking 6 arguments, 3 read from `Config` at the call site, :523-525); component trigger (:546-551, side effect, no `return`); the SATA chain — the pattern `resolved = selectNewActionX(); if (resolved != null) { logActionSelected(...); return resolved; }` copy-pasted **7 times** (:553,:558,:563,:568,:573,:578,:583); finally `throw new BadStateException` (:588). The same three-clause LLM precondition is repeated verbatim three times (:480-481, :493-495, :508-509). "LLM before launcher" — a research-relevant policy — is encoded nowhere except as the textual order of these blocks, documented in 5–8-line comments. The newest correctness fix made it worse, not better: the stagnation episode flag is now burned at `SataAgent.java:499`, re-armed at `StatefulAgent.java:1436`, and predicated at `LlmRouter.java:249-255` / `:267-270` — a single decision split across three classes, because it has nowhere structural to live.

### T2 — Static-frozen `Config` is the only composition mechanism (D2, D3, D5)

112 `public static` fields (45 boolean / 45 int / 13 double / 5 String / 4 long — counted), frozen at class-load (`Config.java:32-44`). Consequences, all verified: `Config.set()` (:414-416) only works before the first field read, an *implicit* ordering the CLI parse depends on (`Monkey.java:921-932`); malformed numerics are swallowed by empty `catch {}` blocks (:453-454, :465-466, :477-478) — a typo in an arm's properties file is silently ignored; validation is three ad-hoc clamps (:315-341) plus one inline (:204-205); the closest thing to a manifest is a string-literal registry (`rvForcedOffValues` :343-364 — **27 keys**, counted; `rvUnsetKeys` :366-370; `rvExemptReasons` :372-398 — 21 entries) that the compiler cannot connect to the fields; five fields were demoted from `final` purely to be testable (:149,:151,:153,:165,:245), each with a confessional comment. Testability was achieved *around* the design, not through it — pure seams extracted with comments admitting why (`Config.java:315,325,335,403`; `State.java:160-166`; `Model.java:300-310`).

### T3 — The mode system is split-brained across two languages and two repos (D2, D9)

Verified in full: 52 of 117 jar keys reachable from Python (`tool.py:75-162`); 18 `ARM_DEFINING_KEYS` (`tool.py:171-192`); 26 arms (`tool.py:427-659`); the kill-switch duplicated and disagreeing — Java forces 27 keys (`Config.java:343-364`), Python's `_APE_PURE_ARM_FLAGS` sets 18 (`tool.py:264-283`), with keys each side omits (Java-only: `coverageBoostWeight`, `componentPercentage`, `mopWeight*`, `mopTargetPickCap`, `activityStableRestartThreshold`; Python-only: `llm_percentage_no_substrate=-1`) and **no drift test** — unlike the sibling `rvagent-tool`, whose guard crosses the boundary to the actual runtime consumer (`test_gh77_variants.py:55-72`, Python↔Python there, but structurally what aperv lacks). `INV-APV-14` validates a Python constant against itself (`test_aperv_tool.py:591-600`): add a new arm-defining flag to the jar and all 26 arms silently inherit its default while the guard stays green. Property-surface changes fail silently; only CLI-surface changes fail loudly.

### T4 — Retention-by-accident: nothing owns a `GUITree` (D6)

Five independent retainers, all verified: **(i)** `Model.actionHistory` (`Model.java:136-137`, self-described "may be the cause of OOM") holds `ActionRecord`s (`Model.java:62-76`) with strong refs to `GUITreeAction` → `GUITree` → `GUITreeNode` subtree (`GUITreeAction.java:29-30`; `GUITree.java:66`) — **the history keeps alive every tree ever visited, including states evicted from the graph**; **(ii)** `Graph`'s 17 unbounded collections (`Graph.java:98-130`), including `stateTransitionHistory` (:117), `treeTransitionHistory` (:118), and `entryGUITrees`/`cleanEntryGUITrees` (:109-110) retaining one whole tree per restart, with restarts every 100–300 steps (`Config.java:73-74`; `ApeAgent.java:278-307`); **(iii)** `State.treeHistory` (`State.java:56`) never truncated; **(iv)** unbounded static caches — `GUITreeBuilder.namingToGUITreeNodeCache` (:693) which `release()` (:707-715) never touches (a strictly monotonic leak), `NameManager.names/nameList` (:27-28), `StringCache.stringDict` (:32), and `AbstractNamingManager.treeToNaming` (:43), copied wholesale on every refinement (:103); **(v)** `maxStatesPerActivity=10` / `maxGUITreesPerState=20` are refinement gates, not memory caps — nothing is freed (`NamingFactory.java:276-283, 1176-1183`; the historical wrong-condition guard is recorded in `openspec/specs/naming/spec.md:371`). The only structure with real eviction is `UICoverageTracker` (LRU at 2000, `:59-69`). `GUITree.releaseData()` (`:340-344`) frees only the DOM and `AccessibilityNodeInfo` — the node structure survives. The only `catch (OutOfMemoryError)` in `src/main` is the static-analysis JSON parse (`MopData.java:328`); `ApeAgent.printMemoryUsage()` (`:448-454`) observes but never acts. Disk is all-or-nothing at teardown (`StatefulAgent.saveGraph` :1855-1902); SIGKILL persists nothing. *(Inference, to be measured — Part E:)* at ~2.5 steps/s a 600 s run executes ~1,500 steps; with ~200–500 nodes/tree and interned strings, the retained tree mass plausibly reaches 100–300 MB — the right order of magnitude for the observed OOMs on emulator heaps, but the high-water mark is unmeasured.

### T5 — Telemetry as scattered print side-effects (D7, D9)

`Logger` is 67 lines: no state, no level, no sink, no timestamps, `System.out.format` with a fixed prefix, debug gated by a compile-time constant (`Logger.java:22`). Eleven structured channels coexist; `[APE-RV]` — the highest-volume one (54 sites) — is free-form with no schema. The format is space-separated `key=value` with **no escaping**; only `text="…"` is quoted; `activity`/`state`/`action` are `toString()`s of objects that can contain spaces. No channel emits JSON. Output rides stdout through adb/logcat: lossy (5 of 720 LLM Summary lines missing; 0.07% per-line undercount from buffer truncation; NUL bytes that defeat `grep` — calibration report §1.2-1.3) and gone the moment adb drops. The pure arm emits **zero** mechanism telemetry by construction (`INV-ARCH-01`, `scoring-pipeline/spec.md:110`) — the baseline is the least observable arm, a deliberate parity/observability tension any redesign must confront head-on. And the newest telemetry field, `patched=`, is provenance for a factor no arm can toggle (`design.md:157`) — telemetry describing a substrate invariant, not a variable. **But the same design is also a proven asset**: the `step=` join, the wall-clock `clock=` field (`StatefulAgent.java:1496`, comment `:1484-1487`), and `decision_source` are exactly what let the calibration campaign reconstruct 39,341 call records from 880/880 raw traces with digit-for-digit reconciliation (`20260724_relatorio_calibracao_aperv.md` §1, §A.1). That capability is the acceptance test for any redesign.

### T6 — The lifecycle is split across two runtimes with mismatched failure semantics (D8)

The Python layer already owns crash-safe resume at task granularity (atomic `tasks.json` rewrite per task, resume-skips-COMPLETED — `subsystem-rv-experiment.md:258-281`) and timeout-as-success as a project-wide law (`:110-113`, `:493-499`). Yet the jar/Python seam inverts the failure semantics: a non-zero jar exit is only `logger.debug`-ed (`tool.py:1121-1125`), so **a run that dies at second 120 of 600 is lost as a sample while counted as a success** — and this is not hypothetical: 45% of cmpv2 LLM-arm runs at 600 s truncated this way (n=22, 95% CI ≈ 24–68%, `20260716_cmpv2_truncation_bug.md:330`; the exit-code decoupling is analysed at `:297-307`). Inside the jar: the deadline check is non-preemptive (`Monkey.java:1293-1302`) so a 15 s blocking LLM call overshoots; resume after process death is impossible (`readGraph` returns a `Graph` while `saveGraph` writes a `Model` — INV-EXPL-03, `Graph.java:1166-1174` vs `StatefulAgent.java:1864-1870`; write is teardown-only; load failure only logs); there is no OOM handler in the exploration loop; and a shutdown hook cannot help because the sink is already closed (`design.md:129-143`, withdrawn with empirical confirmation). The +15 s grace (`tool.py:991`) buys flush time after the kill — but only if the process lived to the kill.

### T7 — The pure-APE baseline is a runtime fiction (D1)

`apePureMode=true` produces a *near*-pure APE. Verified against upstream `8f51b99` by direct diff: **11 ungated behavioural changes** survive the kill-switch — the seedable `Random` replacing `ThreadLocalRandom` (`RandomHelper.java:25-36`; `Monkey.java:731`); the `containsNamelet` binarySearch fix (`Naming.java:429-431`) — **alters naming/abstraction behaviour**; the `an.getStates()` → `state.getGUITrees()` refinement-condition change (`NamingFactory.java:280, :1180`) — **alters the refinement condition**; `GUITree.indexOfName` normalisation (`:288-296`); the `markVisited` double-count fix (`Graph.java:619-621`) and the `rebuildHistory` `visitedCount++` removal (`:1310-1324`); the action-history `RuntimeException` tolerance (`Model.java:104-115`); the `StringCache` empty-list fallback (`:107-118`); the `ApeFuzzer` precedence fix (`:178`); the `ApePinchOrZoomEvent` guard (`:41-49`); the `Monkey` teardown restructure (`:777-799`). Plus **three constructs welded into the data model**: `ActionType` ordinals shifted by `EVENT_TRIGGER_ACTIVITY`/`MODEL_MENU`/`MODEL_LLM_TAP` (`:38,:42,:48`) with ordinal-range checks `requireTarget()`/`isModelAction()` (`:56-59,:70-73`); `Action.isEphemeral()` consulted at 5 ungated core sites (`Model.java:315,:384,:423`; `Graph.java:452,:590`); `DecisionSource` + boost fields as permanent object state (`ModelAction.java:91-107`). The acknowledged exception list is 2 items (`ApePureModeAlwaysOnExceptionsTest.java:11-24`); the real list is 11 + 3. Two of the naming changes are **not** crash fixes — they alter the abstraction/refinement semantics that *are* upstream's research contribution, so `INV-ARCH-01`'s equivalence claim ("except for the two documented always-on behaviors", `scoring-pipeline/spec.md:110`) is currently inaccurate.

### A3.8 — Causal structure: seven tensions, four root causes

| Root cause | Tensions it generates | One-line statement |
|---|---|---|
| **R1 — No first-class Run identity** | T1, T2, T3, half of T5 | Nothing in the jar represents *what this run is*: mode, feature set, seed, provenance. Hence precedence-as-text (T1), 112 frozen statics as the only carrier (T2), names in Python and semantics in Java with no shared artifact (T3), and telemetry lines with no run id, no mode stamp, and fields describing factors nobody can toggle (T5). |
| **R2 — No ownership model for exploration state** | T4 | Nobody can answer "who owns a `GUITree`, and when does it die?" — so five independent retainers grow forever and persistence is a teardown afterthought. |
| **R3 — No event abstraction** | rest of T5, D9's attribution ceiling | Decisions, transitions and outcomes are *emitted* as print side-effects at ~60 sites instead of *being* records in one stream. Hence no schema, no versioning, lossy transport, and attribution that stops at per-step correlation (the counterfactual is 1-step myopic *by contract*, `design.md:103`) because there is no causal record to join against. |
| **R4 — No explicit boundary contracts** | T6, T7 | The jar↔host seam (adb stdout, files, exit codes) and the fork↔upstream seam (22 modified files, no separation) are both implicit, unversioned, unverified interfaces. The same shape of problem at two different borders: drift and misreporting fail silently in both directions. |

This collapse matters for candidate design: **a candidate is "genuinely distinct" iff it picks a different root cause as its organising principle.** C1 organises around R1 (identity), C2 around R3 (events), C3 around R4 (the upstream boundary). R2 (memory) is addressed inside each candidate rather than elevated to an organising principle — no plausible architecture of this size makes object ownership the central idea.

## A4. What the current design gets right — and must not lose

1. **Step-isolated teardown** (INV-EXPL-16/29, `exploration/spec.md:63-64`): `Monkey.run`'s `finally` (:777-799) plus `safeStep` chains in `MonkeySourceApe` (:226-249, 6 steps) and `StatefulAgent` (:1793-1814, 9 steps). The best-engineered part of the system; any redesign keeps the invariant *verbatim*.
2. **Fail-fast on MOP load** (INV-MOP-22, `StatefulAgent.java:216-228`; rationale `mop-guidance/spec.md:506`): an arm is never silently mislabelled by a degraded substrate. The same principle — *mislabelled arms are worse than failed arms* — should be generalised, not lost.
3. **The step/clock join and per-step provenance**: `step=` joins `[APE-STEP]`/`[APE-OUTCOME]`/`[APE-LLM-TEL]` through an explicit join buffer (`StatefulAgent.java:117-124`); `clock=` is epoch-millis *by design* for offline temporal joins with logcat-derived artifacts *without coupling APE to logcat* (`:1484-1487`); `decision_source` (11 values), `pick_channel` (8 values), `activity_has_mop`, and an RNG-isolated per-step counterfactual (`counterfactualFields` :1178-1193) are real attribution assets, proven at campaign scale (§3.11 of the dossier; calibration report §1, §A.1).
4. **The arm-explicitness policy** (INV-APV-13/14): even though the guards are self-referential today, the *policy* — every non-exempt arm sets every arm-defining key explicitly — is exactly right and must be preserved with a real cross-boundary target.
5. **The no-op-when-disabled pass contract and the fixed pass order** (INV-ARCH-03, `scoring-pipeline/spec.md:39`): a disabled pass is a strict no-op; precedence is explicit in one ordered list (`ScoringPipeline.java:53-59`). This is the embryo of the decision pipeline proposed below — proof the team can already build and reason about such a structure.
6. **Seeded RNG** (INV-EXPL-14, `RandomHelper.java:25-36`; `Monkey.java:731`): reproducibility and paired designs depend on it; note it is *also* one of the ungated divergences from upstream (T7) — the redesign must make that divergence deliberate, not accidental.
7. **The Python layer's task-granularity laws**: atomic per-task persistence, resume-skips-COMPLETED, timeout-as-success, process-tree kill (`subsystem-rv-experiment.md:258-281, :493-499`). A jar-side redesign should *use* these, not duplicate them.
8. **The kill-switch *property* (not its mechanism)**: "a future RV flag cannot be added without registering itself in the kill-switch", enforced by a test (INV-ARCH-06, `scoring-pipeline/spec.md:111`). Whatever replaces `apePureMode` must keep this property compiler-adjacent, or parity silently decays again.
9. **gh71's lesson**: the Docker image rebuilds `ape-rv.jar` from source precisely to prevent schema drift; no proposal may reintroduce a committed binary as a source of truth (hard constraint 2b).

---

# Part B — The design space

Before proposing: the major axes, the plausible options on each (including ones not chosen), and how comparable tools solve the same problem (evidence in the prior-art brief; local `file:line` citations, web-level claims marked).

## B1. Where does "mode" live?

| Option | Description | Trade-offs |
|---|---|---|
| (a) Python only *(today)* | Arms = flag bundles in `tool.py` | Zero jar cost; split-brain, silent drift, jar unusable standalone (T3) |
| (b) Jar only | `--ape mop|llm|...` extended; Python pushes one string | Single authority in the jar; but the task matrix, repetitions and timeouts are Python's — mode *parameters* (weights, paths) still cross the seam, so the split reappears one level down |
| (c) **Shared declarative artifact** | A versioned `ape-modes.json` (or equivalent) embedded in the jar *and* read by the plugin; arms generated from it; both sides echo/verify its hash | One source of truth crossing a files-only boundary; requires a contract (version, hash, drift test) — but that is precisely what R4 demands |
| (d) Generated code both sides | Manifest → codegen of `Feature` constants (Java) and arm dicts (Python) | Strongest compiler linkage; two code generators to maintain for one maintainer |

Prior art: DroidBot names policies as module constants resolved by a factory (`input_manager.py:67-94`); Stoat reads one flat `CONF.txt` into statics (`ConfigOptions.java:16-93`) — the anti-pattern T2 already exhibits; Fastbot2 selects agents by CLI flag (web). None of the surveyed tools crosses a two-language seam.

**Owner decision (2026-08-01): option (c) rejected** — no shared declarative artifact across the two repositories. The adopted option is a **strengthened (b)**: the jar is the sole mode/feature authority (compiler-linked preset registry, `--ape` made total, fail-fast validation of every key at startup), while Python keeps full freedom to define arms. The split-brain is then closed not by a shared *definition* but by a loud *contract*: every run echoes its effective configuration (`[APE-RUNSPEC]`), rv-platform verifies echo against intent per task, and an optional CI job diffs the jar's self-dumped schema (`--ape-print-schema`) against Python's pinned key set — the jar itself is the schema artifact; there is no third thing to version. The trade, accepted by the owner: drift is caught at task start (detection), not at arm-definition time (prevention).

## B2. How are features represented?

| Option | Description | Trade-offs |
|---|---|---|
| (a) Static flags *(today)* | 112 fields; registries by string | See T2 |
| (b) **Feature registry with metadata** | Each feature = a compiler-linked entry declaring key, type, default, pure-mode value, dependencies, conflicts, telemetry fields | Kills the string-literal registries; the kill-switch becomes data (`Feature.pureValue`) and INV-ARCH-06 becomes an enum-exhaustiveness test; modest boilerplate per feature (~5 lines) |
| (c) Policy/strategy objects | Features = objects composed into pipelines | Right shape for *behaviour*, but without (b) the objects still read statics — that is today's `ScoringPipeline` with its decorative `Config` param (`ScoringPipeline.java:48-49`) |
| (d) Aspects/interceptors | Features as woven advice | No AOP runtime allowed (hard constraint 1); hand-rolled interception is indirection without tooling support |

The honest answer is **(b) for identity + (c) for behaviour**: a feature is a *declaration* (b) that *assembles* policy objects (c). The unit differs by what the feature does: scorers fit the existing `ScoringPass`; perceivers fit a tree-processing hook; routers fit a decision-stage; input generators fit a strategy behind `generateInputText` (`ApeAgent.java:235-252`). Forcing one unit for all four would repeat the mistake that put a side-effecting component trigger into a proposal chain.

## B3. How are decisions dispatched?

| Option | Description | Trade-offs |
|---|---|---|
| (a) Statement order *(today)* | Guarded blocks with `return` | T1 |
| (b) **Decision pipeline** | Ordered list of typed stages, first non-empty proposal wins; assembly from the feature set | Precedence explicit in one place, logged at startup, testable per stage; still linear — fine, because today's precedence *is* linear |
| (c) Proposal-arbiter | All sources propose `(action, weight)`; an arbiter combines (weighted roulette, like AOSP `MonkeySourceRandom`'s FACTOR_* distribution (web)) | Truly parallel policies; but "LLM vetoes launcher" becomes weight tuning — reproducibility of *precedence* is lost, and arm comparability suffers |
| (d) Explicit mode state machine | DroidAgent's `MODE_PLAN/ACT/OBSERVE/REFLECT` + one `step()` dispatcher (`agent.py:35-200`) | Excellent when the *loop itself* changes per mode; APE-RV's loop does not — only proposal sources do |
| (e) Learned policy | QTesting's ε-greedy Q-table (`qlearning_final_coverage_multi.py:322-337`), ARES deep RL (web) | Breaks frozen-metric comparability with phase 2, non-deterministic across framework updates, and replaces the upstream SATA contribution — rejected on scientific grounds, not engineering ones |

DroidBot's greedy policy shows (b) works at production scale in this exact domain: a priority if-chain with bounded counters (`input_policy.py:376-476`). The point of (b) is not the pattern — it is making the order *data* instead of *text*. Essential conditionals with hard-won semantics survive as named stage behaviour: the budget gate's deliberate fall-through becomes an `ADVISORY` verdict; the tree-package guard's fail-open (`MonkeySourceApe.java:881`) stays a guard, not a stage.

## B4. How is exploration state owned and bounded?

| Option | Description | Trade-offs |
|---|---|---|
| (a) Unbounded object graph *(today)* | T4 | OOM is a when, not an if |
| (b) Ownership + explicit eviction | Each retainer gets an owner and a bound: action history → ring buffer + spill; per-state trees → last-K + counts; caches → LRU; entry trees → released on rebuild | Surgical, keeps the object model; five separate fixes to get right; does not help resume |
| (c) **Event-sourced model** | The event log is truth; the graph is a fold; in-memory state is a bounded projection; revisit = re-perceive from screen | Solves memory, resume and telemetry in one move; largest rewrite; refinement semantics (which compare trees) must be preserved under a bounded working set — the riskiest semantic area |
| (d) Compact indexed stores | Replace object graph with id-keyed tables + flyweight strings | Big memory win, big readability loss; touches the naming core (constraint 5) |

Note (c) has a subtle enabler specific to this tool: a revisited state is *on the screen* — identity by naming means a dehydrated state can be re-perceived fresh rather than stored (§C2). None of the surveyed tools bounds its model (DroidBot `utg.py:20-21`, QTesting `:65`, APE upstream all grow forever); the only working bounds in the field are persistence-as-canonical-state (Fastbot2's `.fbm` with periodic overwrite (web); Stoat's `FSM.txt` dump/restore across phases, `AndroidAppFSM.java:490,535`) — which is the folk version of (c).

## B5. How is observability produced?

| Option | Description | Trade-offs |
|---|---|---|
| (a) Print statements *(today)* | T5; proven join design (A4.3) but schema-less, stdout-only |
| (b) **Typed event bus + file sink** | One `ExplorationEvent` hierarchy, versioned, one JSON line per event, appended to a device-side file, pulled at task end; stdout kept for humans | Schema, versioning, loss resistance, `grep`-able JSONL, no NUL bytes; per-step serialisation cost must be measured (target: ≪5 ms/step — the fixed wall-clock budget makes per-step cost an experimental variable, §3.11) |
| (c) Analysis-ready records | Emit exactly the per-call/per-task CSVs the calibration analysis derived by hand | Least general; new questions need new emitters — the opposite of D9's "answer questions not yet formulated" |
| (d) Pull-based | Host polls device state | Couples the harness to jar internals; adb is already the failure-prone link |

DroidAgent emits a per-step structured snapshot plus a full prompt archive (`agent.py:134-135`; `prompt_recorder.py:17-22`); DroidBot emits per-event JSON (`input_event.py:194-219`) but rewrites the whole `utg.js` every transition (`utg.py:89`) — O(n²) I/O, the anti-pattern. (b) is the append-only middle path. **Transport**: today telemetry rides stdout through logcat — lossy by construction (Summary lines lost, 0.07% undercount, NUL contamination). A device-side file pulled by the plugin at task end removes all three failure modes and survives adb drops up to the pull.

**The parity tension (INV-ARCH-01)** resolves by separating *mechanism* from *observation*: the pure arm runs zero RV mechanism but **still emits** the neutral run/decision/outcome events every arm emits. Observability is a property of the harness, not of the arm — the alternative (today) is that the control arm is the least observable arm, which is backwards for a control.

## B6. How is failure contained, and who owns resilience?

The owner asked for "the only legitimate way for a run to end should be the timeout". §3.10 reframes it: that law already exists at the Python layer; the question is the **division of labour**:

| Option | Scientific meaning | Verdict |
|---|---|---|
| In-process containment *(today: `safeStep`, bad-state policy, abort suppression `Monkey.java:1368-1378`)* | Keep a sick run alive within one process | **Keep** — it is the best-engineered part (A4.1) |
| Restart-in-place with checkpoint resume | A resurrected run is *the same sample* with a marked `restart_index`; clock keeps running (the budget is wall-clock) | Optional capability, never silent; defensible only with an explicit resurrection record in the trace; changes the semantics of "one run = one sample" and must be visible to the analysis |
| External supervision + task retry | A dead run is a *failed task*, retried as a *new* repetition | Clean sample semantics; costs wall-clock; already half-implemented (resume-skips-COMPLETED) |
| **Sentinel + exit-code contract** | Jar emits `RUN_END reason=timeout steps=N` and exits 0 on clean timeout, distinct codes otherwise; Python marks any task lacking the sentinel **FAILED/TRUNCATED**, never COMPLETED | Makes truncation loud with ~20 lines on each side; fixes `tool.py:1121-1125`; the minimum honest contract across R4's host boundary |

Fastbot2's `.fbm` auto-load (web) and Stoat's FSM restore (`AndroidAppFSM.java:490`) show resume is valuable *across* runs (model reuse); for the thesis grid the defensible answer is: **the jar owns** clean termination (preemptive deadline awareness — e.g. blocking calls timeboxed against the remaining budget, addressing the `Monkey.java:1293-1302` overshoot), the end-of-run sentinel, distinct exit codes, and in-process containment; **Python owns** supervision, retry, and truncation accounting. The jar should *stop trying to own* resume and crash-proof persistence — one layer up, they already exist and are better placed. A checkpoint/resume capability (C2 gets it nearly free; C1 can add a compact periodic model snapshot) is worth having for *long-budget exploratory* runs, not for the thesis grid.

## B7. Where is the experiment matrix defined?

Hand-written arms (today, 26) buy explicitness but scale linearly in effort and drift. **Generated arms from a feature registry** buy: the arm matrix derived from declared factors (screening designs become mechanical), per-arm provenance stamped in the trace (`[APE-RUNSPEC]` echo), and a real target for INV-APV-14 (validate generated arms against the jar's dumped schema and the per-run echo, not a Python constant against itself). Post-Q3, the generator lives in Python and pins its own copy of the factor table, CI-diffed against the jar's `--ape-print-schema`. Cost: the `{preset, overrides}` shape must be expressive enough for the 26 current arms including the six frozen gh43 exemptions — verify this as step one of any adoption. Prior art: DroidAgent expresses ablation variants as subclasses (`agent.py:86-363`) — the copy-paste anti-pattern; APE-RV already has the right instinct (data-driven arms) on the wrong side of the boundary with no authority to validate against.

## B8. Is "mode" even the right primitive? — the §2.2 question, argued

The dossier asks whether the five names (`ape`, `aperv`, `mop`, `llm`, `llm_mop`) are modes or presets over a feature set. **The evidence says the concept is two-dimensional**: a *base explorer* (`ape` semantics vs `aperv` semantics — the exploration improvements F1–F8 of §3.4) × a *guidance stack* (none / MOP / LLM / MOP+LLM, each itself a parameterised family — widget-level F9–F11 vs frontier-level F12–F13). "Mode" as a flat enumeration collapses the two dimensions and explodes the arm count; "no modes, only features" loses the scientific object the thesis actually compares — an *arm* must be a **named, versioned, checksummed, falsifiable preset** so that a claim can reference it and a reviewer can reconstruct it. The defensible synthesis, adopted by all three candidates in different clothes:

- **Engineering primitive: the feature set** — declared compatibility (F10 needs F2 and MOP, `State.java:305-307` + menu-gateway; F12 is nominally MOP-agnostic yet technically coupled via `MopData.getWtgTransitions()` — a dependency to *declare* and optionally to *break* by splitting the WTG view out of the MOP JSON), declared pure-mode value, declared ordering where load-bearing (LLM-before-launcher).
- **Scientific primitive: the named preset** — `ape`, `aperv`, `mop`, `llm`, `llm_mop` as presets with hashes; `--ape mop` today silently falls through to `SataAgent` (`ApeAgent.java:95`) and must instead **fail loudly** listing valid presets (and the `replay` path's `System.exit(1)` at `:91` should become a usage error with a message).
- **The default the thesis needs**: `mop` = `aperv` base + MOP guidance (not `ape` + MOP). Rationale: the aperv exploration features are not under study — they are the instrument; the control is `ape_pure`, the substrate is `aperv`, and each guidance mechanism ablates *on the substrate it will ship with*. But the ablation design should include one `ape+MOP-only` screening arm to detect base×guidance interaction (cheap: one arm ≈ 1,629 tasks at 181 apps × 3 × 3) rather than assume it away.
- **Widget-level vs frontier-level MOP guidance**: orthogonal *sub-features* of the MOP guidance feature (a parameterised family), not separate modes — expressed as factors in the screening design (B7), where their interaction can be estimated at resolution IV instead of presumed.
- **The `llm` fallback**: a mode *parameter* — `llm = base(aperv) + guidance(LLM)`, with `base ∈ {ape, aperv, mop}` explicit in the preset. Today the fallback is implicit (the SATA chain always runs when the LLM declines — `SataAgent.java:552-587`), which is the right behaviour with the wrong representation: it should be *declared* that an LLM stage's empty proposal falls through to the base explorer's stages.

---

# Part C — Candidate architectures

## Candidate 1 — RunSpec + Decision Pipeline (decision-centric)

### Organising principle

**Make the run's identity and the run's decision the two explicit, named, validated things; everything else hangs off them.** R1 is the root cause this candidate attacks: today nothing in the jar represents *what this run is* (hence modes in Python, frozen statics, precedence-as-text). C1 introduces exactly two new concepts — an immutable `RunSpec` (identity) and an ordered `DecisionStage` pipeline (decision) — and *deletes* the string registries, the kill-switch mechanism, the decorative `Config` parameter, and the guarded-block method.

### Module structure and key abstractions

```
ape/run/      RunSpec, Feature (enum), FeatureSet, RunSpecParser, ModePreset
ape/decision/ DecisionStage (iface), DecisionContext, ActionProposal,
              stages/{BudgetGate, LlmNewStateStage, LlmStagnationStage,
              LlmRandomStage, MopLauncherStage, SataCascadeStage},
              interceptors/{ComponentTriggerInterceptor}
ape/config/   RunSpecLoader (reads ape.properties + CLI overrides; legacy Config adapter)
ape/telemetry/ RunEvent (iface), EventSink (iface), StdoutKeyValueSink, JsonlFileSink
ape/run/       ModePreset — the five named presets, in Java code (jar is the mode authority; Q3)
```

**Feature registry — compiler-linked, metadata-bearing** (replaces `Config`'s statics, the string registries, and the kill-switch mechanism):

```java
// ape/run/Feature.java
public enum Feature {
    // --- exploration base (aperv lane) ---
    TREE_ENHANCEMENTS  (bool("ape.treeEnhancementsEnabled", true ), pure(OFF)),
    MODEL_MENU         (bool("ape.modelMenuEnabled",        true ), pure(OFF)),
    COVERAGE_BOOST     (intg("ape.coverageBoostWeight",     100  ), pure(ZERO)),
    ACTIVITY_BUDGET    (bool("ape.activityBudgetEnabled",   true ), pure(OFF)),
    BACK_MENU_PICK_CAP (intg("ape.backMenuPickCap",         3    ), pure(ZERO)),
    DYNAMIC_EPSILON    (bool("ape.dynamicEpsilon",          true ), pure(OFF)),
    HEURISTIC_INPUT    (bool("ape.heuristicInput",          true ), pure(OFF)),
    FORM_COMPLETION    (bool("ape.formCompletionEnabled",   true ), pure(OFF)),
    FOREIGN_GUARD      (bool("ape.foreignActivityGuard",    true ), pure(OFF)),
    TREE_PACKAGE_GUARD (bool("ape.treePackageGuard",        true ), pure(OFF)),
    // --- MOP guidance family ---
    MOP_SUBSTRATE      (path("ape.mopDataPath",             null ), pure(UNSET)),
    MOP_WIDGET_DIRECT  (intg("ape.mopWeightDirect",         500  ), pure(ZERO), needs(MOP_SUBSTRATE)),
    MOP_WIDGET_TRANS   (intg("ape.mopWeightTransitive",     300  ), pure(ZERO), needs(MOP_SUBSTRATE)),
    MOP_MENU_GATEWAY   (intg("ape.mopWeightOpenMenu",       250  ), pure(ZERO), needs(MOP_SUBSTRATE, MODEL_MENU)),
    MOP_WTG            (intg("ape.mopWeightWtg",            200  ), pure(ZERO), needs(MOP_SUBSTRATE)),
    FRONTIER_BOOST     (intg("ape.frontierBoostWeight",     200  ), pure(ZERO), needs(MOP_SUBSTRATE)), // F12 coupling declared (break later by splitting the WTG view)
    MOP_FRONTIER       (intg("ape.mopFrontierWeight",       0    ), pure(ZERO), needs(MOP_SUBSTRATE)),
    ACTIVITY_TRIGGER   (bool("ape.activityTriggerEnabled",  true ), pure(OFF),  needs(MOP_SUBSTRATE)), // honest: no-op without MopData today
    // --- LLM guidance family ---
    LLM_URL            (str ("ape.llmUrl",                  null ), pure(UNSET)),
    LLM_ON_NEW_STATE   (bool("ape.llmOnNewState",           true ), pure(OFF),  needs(LLM_URL)),
    LLM_ON_STAGNATION  (bool("ape.llmOnStagnation",         true ), pure(OFF),  needs(LLM_URL)),
    LLM_PERCENTAGE     (dbl ("ape.llmPercentage",           0.02 ), pure(ZERO), needs(LLM_URL)),
    // ... the remaining ~90 keys migrate mechanically
}
```

Every entry is compiler-linked (no string can drift from its field), declares its **pure-mode value as data**, and declares **dependencies**. Consequences that fall out for free: `apePureMode` becomes `FeatureSet.pure()` — resolve every feature to its pure value; **INV-ARCH-06 becomes an enum-exhaustiveness test** ("every feature has a pure value" cannot be violated without a compile error); the 5 demoted-from-`final` fields are gone because tests construct `RunSpec`s instead of mutating statics; unknown keys are a **hard error** with a did-you-mean (the parser knows every legal key); the empty `catch {}` blocks disappear because parsing is typed and centralised.

**RunSpec — immutable, validated, echoed:**

```java
// ape/run/RunSpec.java
public final class RunSpec {
    private final FeatureSet values;        // resolved effective configuration
    private final String modeName;          // "ape" | "aperv" | "mop" | "llm" | "llm_mop" | "custom"
    private final String runId;             // host-supplied or generated; on every event line
    private final long   seed;
    private final String specHash;          // sha-1 of the resolved FeatureSet — stamped on every event line
    public <T> T get(Feature<T> f) { ... }
    public String toEchoJson() { ... }      // emitted once as [APE-RUNSPEC] at startup
}
```

Validation at resolve time (fail fast, INV-MOP-22 generalised): unknown key → error; declared dependency violated (`mopWeightOpenMenu > 0` with `modelMenuEnabled=false`) → error listing the chain; `mopDataPath` set but unparseable → `StopTestingException` as today.

**Decision pipeline — the replacement for `selectNewActionNonnull`:**

```java
// ape/decision/DecisionStage.java
public interface DecisionStage {
    String name();                                   // stamped on the step event
    Optional<ActionProposal> propose(DecisionContext ctx);
}

// ape/decision/DecisionAssembly.java — the ONLY place precedence lives
static List<DecisionStage> forSpec(RunSpec spec, AgentServices svc) {
    List<DecisionStage> s = new ArrayList<>();
    if (spec.on(ACTIVITY_BUDGET))  s.add(new BudgetGate(svc.budgets()));          // advisory fall-through = ADVISORY verdict, named
    if (spec.llmEnabled()) {
        if (spec.on(LLM_ON_NEW_STATE))  s.add(new LlmStage(NEW_STATE,  svc.llm()));
        if (spec.on(LLM_ON_STAGNATION)) s.add(new LlmStage(STAGNATION, svc.llm())); // owns its episode flag — one class, not three
        if (spec.get(LLM_PERCENTAGE)>0) s.add(new LlmStage(RANDOM,     svc.llm()));
    }
    if (spec.on(ACTIVITY_TRIGGER)) s.add(new MopLauncherStage(svc.mop(), svc.budgets()));
    s.add(new SataCascadeStage(List.of(            // the 7 channels, each a named sub-stage
        FROM_BUFFER, BACK_TO_ACTIVITY, EARLY_FORWARD, TRIVIAL, EARLY_BACKWARD, EPSILON_GREEDY, NULL_HANDLER)));
    return s;   // logged at startup: [APE-RUNSPEC] stages=[budget, llm:new-state, ..., sata]
}
```

The side-effecting component trigger (`SataAgent.java:546-551`, which fires without returning) is honestly reclassified as a `StepInterceptor.beforeProposal(ctx)` — it was never a proposal source. The 3×-repeated LLM precondition collapses into `LlmStage.eligible(ctx)`. The stagnation episode state lives in `LlmStagnationStage` alone — the three-class split (T1) closes. Scoring **stays** the existing `ScoringPipeline` (the 2026-07-08 asset is kept for the reason it was bought: an ordered, no-op-when-disabled, tested structure) — but passes receive configuration via `ScoringContext` from the `RunSpec`, killing the static reads and making the injected parameter non-decorative. `LlmRouter.selectAction`'s 286 LOC decompose into the pipeline it already is internally (breaker → screenshot → prompt → HTTP → parse → map → telemetry), each step a private method of an `LlmService` whose counters are read by the telemetry layer instead of 14 ad-hoc getters (`LlmRouter.java:942-995`).

**Telemetry (C1-scoped):** a `RunEvent` interface with a small fixed set (`RunStarted(spec)`, `StepDecided`, `OutcomeObserved`, `RunEnded`), two sinks (`StdoutKeyValueSink` — today's exact format, kept for compatibility during migration; `JsonlFileSink` — one JSON object per line to `/data/local/tmp/ape-events-<runId>.jsonl`, pulled by the plugin). Every line carries `run`, `mode`, `spec` (hash), `step`, `clock`. The pure arm emits the same neutral events as every arm — observability decoupled from mechanism (B5). The violation↔step heartbeat and full event model are C2 territory; C1 adds only the minimum (`RunStarted`/`RunEnded` + per-step JSON mirror of the existing step/outcome lines), explicitly staged so C2's richer stream can replace it without rework.

### How the five modes (and a sixth) are expressed

In a jar-resident, compiler-linked registry — the jar is the mode authority (owner decision, Q3):

```java
// ape/run/ModePreset.java
public enum ModePreset {
    APE     ("ape",     FeatureSet.pure()),                             // control: every feature at its pure value
    APERV   ("aperv",   base().guidance()),                             // exploration improvements, no guidance
    MOP     ("mop",     base().guidance(MOP.defaultWeights())),         // aperv + MOP (B8 rationale)
    LLM     ("llm",     base().guidance(LLM.on(NEW_STATE, STAGNATION).pct(0.02))),
    LLM_MOP ("llm_mop", base().guidance(MOP.defaultWeights(), LLM.defaults()));
    public RunSpec resolve(Properties overrides, long seed, String runId) { ... }
}
```

"Mode" **survives** — as a named preset over the feature set (B8), versioned by the jar build itself and hashed as `specHash` (sha-1 of the resolved `FeatureSet`), self-contained in each run's echo rather than in a shared file. `--ape mop` resolves the preset; unknown `--ape` values **fail loudly** with the valid list (today: silent `SataAgent`, `ApeAgent.java:95`); per-flag overrides after the preset are legal and logged (`override ape.mopFrontierWeight=200`), preserving today's flexibility for exploratory runs. **Python keeps full arm-definition freedom** (Q3): an arm thins to `{preset, overrides}` — the plugin stops duplicating semantics (`_APE_PURE_ARM_FLAGS`, `tool.py:264-283`, collapses to `preset: ape`) — and drift is controlled without any shared artifact by four mechanisms: (i) **fail-fast startup validation** — unknown preset, unknown key, or dependency violation aborts the run in its first seconds (INV-MOP-22 generalised), and via step 2's exit-code contract the task is marked FAILED, never silently mislabeled; (ii) the per-run **`[APE-RUNSPEC]` echo** — every arm is fully reconstructible post hoc; (iii) **echo-vs-intent verification** in rv-platform result processing (the jar's echoed effective config is compared to the arm's declared intent; mismatch = task flagged); (iv) an **optional CI diff** of the jar's `--ape-print-schema` dump against Python's pinned key set — the jar itself is the schema artifact. **Hypothetical sixth mode** — e.g. `llm_async` (the calibration report's own suggested lever, §3.11): add a feature `LLM_ASYNC`, one `AsyncLlmStage` whose proposal is a *deferred* `ActionProposal` (explore-with-cached-proposal while inference is in flight — a new stage class, ~150 LOC, registered in `DecisionAssembly`), one registry entry. No existing stage changes. That is the flexibility test D3 demands.

### §2.2 answers in C1's terms

`mop` = `aperv` base + MOP guidance (B8 rationale); the `ape+MOP-only` interaction-check arm is one more preset line. Widget- vs frontier-level = parameters of the `mop` guidance object (sub-features with declared deps), screened as factors (D9 below). `llm` fallback = the preset's `base` field; an LLM stage returning empty falls through the pipeline to the SATA cascade *by construction*, and the fall-through is *logged* (`decision_source=SATA` on that step) rather than invisible. Modes are presets; the feature set is the engineering primitive.

### Drivers D1–D10 in C1

- **D1 (fidelity):** position = **"APE + documented bugfixes"** — confirmed by the owner (Q1). All 11 ungated deltas + 3 welded constructs (T7) move from "two acknowledged exceptions" to a complete **divergence register** shipped in the repo; none is gated by default. Gating the four semantic deltas (`Naming.containsNamelet`, `NamingFactory` guard pair, `GUITree.indexOfName`) behind pure-values that restore upstream semantics is reserved for any delta the oracle shows to have a *material* effect (E5) — the naming core stays untouched unless evidence demands otherwise. The **upstream oracle**: upstream `8f51b99` compiled with the same toolchain (the clone already exists; local `ape-original/` too), run on M APKs × K seeds, comparing *action-sequence distributions* per channel (statistical, not exact — upstream's `ThreadLocalRandom` prevents draw-level pairing, and seeding itself is a registered divergence). The thesis claim becomes: "control arm ≡ upstream modulo documented, enumerated, oracle-measured fixes" — defensible, and cheaper than C3's physical split.
- **D2 (mode first-class):** presets are jar-resident and compiler-linked; `--ape` is one string again, but now total (no silent fallback; `System.exit(1)` at `ApeAgent.java:91` becomes a usage error). Validation timing (owner decision Q3): at task *start*, not task *definition* — an invalid combination fails loudly in the first seconds and is accounted as a failed task (step 2), while the echo + result-processor check makes residual value-level drift detectable per run. Detection, not prevention — the accepted trade for keeping Python's arm freedom.
- **D3 (features):** selectable (preset/override), enablable (per-feature default + pure value), composable with declared deps/conflicts (enforced at resolve), ordering declared exactly once (`DecisionAssembly`).
- **D4 (spaghetti):** statement order → stage list; the 7-copy paste → `SataCascadeStage` sub-channels; guards with hard-won semantics keep their semantics under names (budget `ADVISORY`, tree-guard fail-open). *Deleted:* `selectNewActionNonnull`'s 141 LOC of blocks, the string registries (~60 LOC), the decorative parameter, ~15 of the ~35 explanatory comments (the structure now says what they said).
- **D5 (testability):** `RunSpec` is constructed in tests — no statics to reset, no demoted finals; stages are pure-ish objects tested on the JVM with a fake `DecisionContext`; the device seam (`AndroidDevice`'s 7 static handles, `:61-73`) is narrowed behind `AgentServices` but **not** fully abstracted — a full simulated-GUI CI harness is judged over-engineering for one maintainer (Part E lists it as a rejected cost).
- **D6 (memory):** addressed but not central: action history → bounded ring buffer (last N in memory) with the full record already on disk via the JSONL sink (the event line *is* the history — `ActionRecord`'s strong tree refs die with the buffer); `entryGUITrees` released at rebuild end; per-node cache gets an LRU or weak keys; `State.treeHistory` capped at last-K + counts (refinement working set preserved — this is the one semantic risk, guarded by property tests on recorded runs). Est. −60 to −80% retained heap at 600 s *(inference, to be measured)*. The 550 MB static-analysis corpus: keep on-device parsing for now, but add the **compact derived artifact** option (Python pre-computes the explorer-shaped subset — widget flags, WTG edges, component list — pushing ~1–5 MB instead of 1.5–48 MB; the parser already supports a strict schema, so this is an additive format version, and it kills the `too-large` silent-degradation class at `MopData.java:202-206`).
- **D7 (traceability):** run id + spec hash on every line; `[APE-RUNSPEC]` echo at startup closes the effective-config gap for MOP as `[APE-LLM-CONFIG]` did for LLM; JSONL file sink fixes loss/NUL/grep; `RUN_END` sentinel; schema versioned (`"v":1` per line). The three §2.3 gaps: effective-config echo **closed here**; violation↔step join and `first_seen_step` **not closed** — they need C2's heartbeat (explicitly staged).
- **D8 (resilience):** division of labour per B6: jar owns clean termination (deadline-aware timeboxing of blocking calls against the remaining budget — the `Monkey.java:1293-1302` overshoot), `RUN_END` sentinel, distinct exit codes (0 = clean timeout; 2 = crash; 3 = aborted substrate), in-process containment unchanged. Python: mark task FAILED/TRUNCATED when the sentinel is missing or the exit code non-zero (fixes `tool.py:1121-1125`; ~20 lines + one guard test). Restart-in-place: **rejected for the thesis grid** — a restarted run is *not* the same sample (its early trajectory is missing); task-level retry creates a new repetition, which is statistically clean. Checkpoint resume: not built (C2 has it nearly free; C1 can add a compact periodic snapshot later if long-budget runs need it).
- **D9 (ablation):** the `Feature` registry is the factor table (machine-readable via `--ape-print-schema`); a generator script in Python (pinning its own copy, CI-diffed against the schema dump) emits arm matrices: (i) the 5 presets; (ii) a Plackett–Burman / resolution-IV fractional factorial over the ~10 RV features for main-effect screening (~12–20 arms ≈ 20–33k tasks at 181 × 3 × 3 — phase-2 scale, feasible); (iii) targeted interaction arms (base×MOP, widget×frontier). Within-run attribution (`decision_source`, `cf_changed`) is kept as *covariates and mechanism checks*, explicitly not causal claims (myopia contract, `design.md:103`). Unit of analysis: the run, clustered by app, seeds paired across arms — the existing NB pipeline (`rq1_jca.py:178-219`) is reusable unchanged.
- **D10 (simplicity):** new concepts: 2 (`RunSpec`, `DecisionStage`) + 1 preset registry. New packages: 4. Est. LOC: +900 new / −700 deleted / ~1,200 mechanically touched (mostly `Config.x` → `spec.get(F)` in the five hotspots; the static-import style is deleted outright). A newcomer reads `Feature.java` (the whole control surface, one file), `DecisionAssembly` (the whole precedence, one function), `ModePreset.java` (the whole mode set, one screen). That is the entire conceptual load.

### Migration path (stageable, experiments keep running)

| Stage | Content | Behaviour change? | Cross-repo |
|---|---|---|---|
| 0 | `Feature` enum + `RunSpec` loader; legacy `Config` delegates to it (read-sites untouched); `[APE-RUNSPEC]` echo; unknown keys **warn** | None | None |
| 1 | Decision pipeline extraction in `SataAgent` (stage per existing block; order pinned by a golden-sequence test on recorded seeds); `LlmStagnationStage` absorbs the flag | None (pinned by test) | None |
| 2 | Scoring passes + `LlmRouter` take config from `RunSpec`; static reads deleted; unknown keys now **error** | None | Properties contract documented |
| 3 | Jar-resident `ModePreset` registry + `--ape` made total (loud on unknown); Python arms thinned to `{preset, overrides}`; `[APE-RUNSPEC]` echo verification added to rv-platform result processing; optional CI diff of `--ape-print-schema` vs Python's pinned key set | None for existing arms (each thinned arm must echo the identical effective config it produces today — a hard gate) | **Yes**: `tool.py` + result processor + tests |
| 4 | Exit-code contract + `RUN_END` sentinel + truncation loudness | Task accounting changes (correctly) | **Yes**: `tool.py:1121-1125` |
| 5 | Memory: bounded history, cache eviction, tree-history cap (A/B on 20 APKs first) | Yes — flagged, measured | None |
| 6 | Optional: compact SA artifact; oracle harness (D1) | None | Dataset pipeline |

Rough effort: 4–6 weeks part-time for stages 0–4; stage 5 adds ~2 weeks with its A/B gate. Each stage is independently revertible; comparability with already-collected data is preserved through stage 4 (only task *accounting* changes, catching mislabeled successes); stage 5 is the first that can shift within-run behaviour and is therefore gated on an A/B.

### Honest weaknesses

- **R2 and R3 are mitigated, not solved.** Memory bounds are five surgical fixes that must each be gotten right; the event model is minimal (four event types) — the violation↔step join, the thesis's most valuable telemetry gap, is *staged to C2's subsystem*, and if C2 is never adopted the gap stays closed only by the cruder wall-clock join that exists today.
- **Precedence is still linear.** If a future mechanism needs *weighted* competition between guidance sources (not veto chains), the pipeline must become an arbiter (B3c) — a real change, not a config tweak. The LLM-before-launcher policy is explicit but still a total order.
- **The model layer is untouched** — `Graph`'s 17 collections, the `Model`/`Graph` type mismatch (INV-EXPL-03), and Java serialisation of the whole model remain. C1 is deliberately conservative where the upstream research contribution lives.
- **Drift control is detection, not prevention (Q3's accepted trade).** The echo verification is post-hoc — it catches a mislabeled arm within its first seconds of run; the fail-fast startup validation bounds the exposure to one failed task, correctly accounted — but nothing prevents an invalid arm from being *defined* in Python (the rejected shared manifest would have). The four-part mechanism (startup validation, echo, result-processor check, CI schema diff) is also four places to keep honest.
- **Do not choose C1 if** the owner judges the violation↔step join and bounded memory to be *the* point of the re-architecture (then C2), or if the control arm must be byte-pure upstream with no register of documented fixes (then C3).

## Candidate 2 — Event-Sourced Explorer (data-centric)

### Organising principle

**The authoritative artifact of a run is a single append-only, typed, versioned event stream written on-device; everything else — the exploration model, the telemetry, the resume capability, the attribution chain — is derived from the stream.** C2 attacks R3 (no event abstraction) and R2 (no ownership) at once: if the stream is truth, then in-memory state is a *rebuildable projection* and can be bounded at will, and telemetry stops being a side-effect because the event *is* the record. The organising inversion: today's architecture keeps state and prints about it; C2 records events and folds them into state.

### Module structure and key abstractions

```
ape/core/event/   ExplorationEvent (abstract) + final subclasses:
                  RunStarted, ScreenPerceived, DecisionMade, ActionExecuted,
                  TransitionObserved, Heartbeat, CheckpointMark, RunEnded
ape/core/log/     EventLog (append-only JSONL, batched fsync), EventReader (fold/replay)
ape/core/model/   ModelView (projection: Graph/State built by folding events),
                  StateSummary (dehydrated form), WorkingSet (bounded, evicting)
ape/core/decide/  same DecisionStage pipeline as C1 (orthogonal — C2 composes with it)
ape/core/serde/   hand-rolled JSON writer/reader (no third-party deps — hard constraint 1)
```

```java
// ape/core/event/ExplorationEvent.java  (Java 11: abstract base + final subclasses)
public abstract class ExplorationEvent {
    public int v = 1;                 // schema version, per record
    public String run;                // run id
    public int step;                  // the join key that already works (A4.3)
    public long clock;                // epoch-millis, the join key that already works
    abstract String type();
    abstract void writeJson(StringBuilder out);   // allocation-frugal, no reflection
}

public final class DecisionMade extends ExplorationEvent {
    String mode;                       // preset name + spec hash (from RunStarted, denormalised for grep-ability)
    String activity; boolean activityHasMop;
    String stateId; boolean newState;
    List<Candidate> candidates;        // action id, source, each boost component, priority — per candidate
    String chosen; String decisionSource; String pickChannel;
    String counterfactual;             // cf_action / cf_changed, as today
}
public final class TransitionObserved extends ExplorationEvent {
    String fromState, toState, action; boolean newState, activityChanged;
}
public final class RunEnded extends ExplorationEvent {
    String reason;                     // "timeout" is the only clean value (D8)
    int steps; String terminator; long elapsedMs;
}
```

**EventLog** — `/data/local/tmp/ape-events-<runId>.jsonl`, appended with a small in-memory tail, flushed every 20 events or 5 s and at teardown; pulled by the plugin at task end (becomes *the* telemetry artifact; stdout demoted to human log). A truncated file is still line-wise parseable — the loss mode degrades from "channel interleaved in 4 MB of stdout through a lossy pipe" to "missing tail", which the `RunEnded` sentinel makes *visible*.

**ModelView and the memory inversion** — the decisive difference from C1:

```java
// ape/core/model/ModelView.java
final class ModelView {
    private final WorkingSet states;      // bounded: hot states hydrated, cold states = StateSummary
    void on(ScreenPerceived e)  { ... }   // fold handlers; the ONLY mutators of model state
    void on(DecisionMade e)     { ... }
    void on(TransitionObserved e){ ... }
    static ModelView rebuild(EventReader log, int upToStep) { ... }  // resume = replay
}
```

The working set keeps hydrated only what SATA and refinement actually touch: the current screen's tree, the unvisited-frontier states' action signatures, and the **last-K trees per state** that refinement compares (`NamingFactory` reads `state.getGUITrees()` at `:280,:1180` — K = `maxGUITreesPerState` = 20 caps it exactly, turning today's non-cap into a real one). A dehydrated `StateSummary` keeps naming key, action signature set, visit counts, timestamps — everything except the trees. **The enabler unique to this domain**: a revisited state is physically on the screen — when exploration returns to a dehydrated state, the current `AccessibilityNodeInfo` *is* the tree; identity flows through the naming layer as it already does. The action history disappears as a structure entirely: `DecisionMade`+`ActionExecuted`+`TransitionObserved` in the log *are* the history (deleting `Model.actionHistory` — T4's retainer (i) — and `Graph`'s two history collections — retainer (ii)).

**The violation↔step join (the thesis gap, closed structurally):** every `ScreenPerceived`/`DecisionMade` is *also* mirrored to logcat as a one-line heartbeat: `APE-RV-STEP run=<id> step=N clock=<ms>` (write-only; constraint 3 — metric provenance — is preserved because violation *detection* still comes exclusively from the RVSec monitors; the heartbeat is a clock, not an outcome). Post-hoc, in `rv-platform`'s result processor (`result_processor.py:303-366`, which already re-parses the logcat): a violation event at logcat time `t` belongs to the step interval `(clock_N, clock_{N+1}]` containing it — logcat buffering delay is ~ms against a ~1 s step granularity, so interval assignment is robust *(inference; skew distribution to be measured — Part E)*. From that, `first_seen_step` and time-to-first-violation per dedup key (`log.py:90-113`) become *derived columns*, and "what fraction of newly-found violations is attributable to MOP/LLM/Coverage steps" becomes answerable per run, per app, per origin subset (the 86.78%-in-libraries split, `results-rq3.tex:47-49`) — **without** the explorer self-reporting outcomes. (Owner decision Q2, 2026-08-01: the write-only heartbeat is approved; the recorded constraint "APE never touches logcat" covers read/outcome-coupling, not write-only markers. The weaker logcat-free fallback — today's wall-clock join with an estimated skew bound — is dropped.)

### How the five modes (and a sixth) are expressed

Same jar-resident preset mechanism as C1 (C2 is silent about identity — it *carries* C1's `RunSpec` inside `RunStarted`). What changes is the *locus*: a mode is also visible as **which fold handlers are active** (a pure run folds no MOP/LLM annotations; telemetry is uniform across arms, closing the INV-ARCH-01 parity tension structurally — the pure arm's stream is identical in shape, just shorter in candidate metadata). The hypothetical sixth mode (`llm_async`): *natural* here — an async LLM call is a `GuidanceRequested` event now and a `GuidanceArrived` event later; the decision fold can annotate the proposal onto a subsequent step. Determinism note: replay must re-impose the arrival timing — the stream records it, so the fold stays deterministic. This is the one place C2 is *cheaper* than C1 for async.

### §2.2 answers in C2's terms

As in C1 (jar-resident presets), plus: the `llm` fallback question gets an event-level answer — an LLM decline/circuit-break is a `DecisionMade` with `decisionSource=SATA` and a `guidance=declined` annotation, so the fallback is *measured* per step instead of assumed (today it is invisible unless the LLM telemetry happens to fire). Widget- vs frontier-level MOP are per-candidate boost components in `DecisionMade.candidates` — every step records *all* guidance signals on *all* candidates, so post-hoc one can recompute what any *other* weighting would have chosen (a stronger, still-myopic counterfactual than today's single `cf_action`).

### Drivers D1–D10 in C2

- **D1:** identical position to C1 (documented-fixes baseline + oracle). Orthogonal.
- **D2/D3/D4:** inherited from C1's RunSpec/pipeline (C2 composes with them; pure C2-without-C1 would re-encapsulate flags badly — be honest: **C2 needs C1's identity layer or reproduces its problem**).
- **D5:** the fold handlers and event serde are pure JVM logic — highly testable; replay-based golden tests (record a run on device once, replay N configurations against the fold in CI) give a *simulated-GUI* capability as a by-product, partially answering B5's over-engineering worry from the other direction.
- **D6:** the strongest story. Bounded working set (~est. 20–50 MB regardless of run length, vs unbounded today — *inference, measure*); action history as a structure deleted; per-node cache still needs its own fix (orthogonal); disk: ~1–3 KB/step × ~1,500 steps ≈ 2–5 MB/run JSONL — comparable to today's ~4 MB/run average trace (3.5 GB / 880 tasks), but parseable, greppable, and complete; phase-3-scale storage unchanged (~tens of GB per campaign), now analysis-ready. Per-step serialisation cost: hand-rolled JSON, no reflection, ~1 KB/step — target p99 < 5 ms/step on the emulator; **must be measured before adoption** (the fixed wall-clock budget makes this an experimental variable: at 0.037–0.052 pp of `cov_mop` per step, 5 ms/step over a 300 s run costs ~0.7–1.0 pp — *not* negligible; batching and field discipline matter).
- **D7:** this *is* the D7 candidate. Versioned schema per record; one line per event; loss-resistant (batched fsync + pull; `RunEnded` sentinel; partial files parse); versioned spec echo in `RunStarted`; the calibration-report acceptance test passes — every field it used (`step`, `clock`, `decision_source`, the LLM per-call record, Summary aggregates) exists in the stream, and the Summary becomes a *derived* aggregate instead of a separately-emitted line that can be lost (5/720 missing today).
- **D8:** resume becomes real: `ModelView.rebuild(log)` + derived reseed (`seed ⊕ restartIndex`, marked `restart_index` in the stream) — a resurrected run is one sample *with an explicit resurrection record*, and the analysis can include/exclude/weight it knowingly. For the thesis grid the recommendation stays B6 (sentinel + loud truncation + task retry); C2 simply makes the honest version of restart-in-place *possible* for long-budget exploratory runs. The +15 s grace (`tool.py:991`) finally has something reliable to flush: the tail buffer.
- **D9:** the attribution ceiling lifts one level: per-candidate signals on every step mean the analysis can ask "what would arm X' have done on arm X's trajectory?" — still myopic (the trajectory itself would have changed), but the richest within-run observational data this tool can produce. Ablation arms: as C1 (registry-generated), with the bonus that every arm's stream is schema-identical, so mechanism metrics compare across arms without per-arm parsers.
- **D10:** the weakest story. New concepts: event hierarchy (8 types), log + reader, projection/working-set, dehydration rules, serde discipline. Every future feature must answer "what events do I add, and how do they fold?" — a real, perpetual tax. Est. LOC: +1,800 new / −900 deleted / heavy touch of `Model`, `Graph`, `State` — **the upstream research core**, which constraint 5 says may be restructured only with an explicit argument. The argument: its *behaviour* is pinned by replay-equivalence tests (same events in ⇒ same decisions out, proven against the current build on recorded device runs); without that harness, do not touch it.

### Migration path

Stages 0–2 of C1 first (identity layer is a precondition), then: (i) event hierarchy + JSONL sink *in parallel with* the legacy channels (dual-emit; analysis A/B on one campaign's worth of runs: the calibration report must be reproducible from the stream alone — the acceptance test); (ii) heartbeat + result-processor join in rv-android (first cross-repo deliverable; immediately closes the thesis gap); (iii) working-set dehydration behind a flag, A/B step-rate and OOM incidence; (iv) delete `actionHistory`/history collections and legacy channels; (v) replay/resume as an optional capability. Each stage independently shippable; stage (iii) is the first behavioural one. Effort: 8–12 weeks part-time, with hard gates at (i) and (iii).

### Honest weaknesses

- **It is the biggest hammer.** One maintainer, live experiments, and a thesis deadline that tolerates no slip: the full version of this candidate is the highest-variance option. Its own author would stage it so that (i)+(ii) ship even if (iii)–(v) never do.
- **Per-step cost is a scientific variable, not a micro-optimisation** — if measurement shows >5 ms/step p99 on the emulator, the telemetry *richness* must be trimmed (per-candidate lists are the first to go), which erodes exactly the D9 advantage that justifies the candidate.
- **Replay determinism is a research-grade claim.** RNG draws, wall-clock, and LLM responses all live in the stream; proving fold-equivalence under dehydration is doable but is *work*, and a silent divergence would be worse than today's explicit OOM.
- **Dehydration interacts with the naming/refinement core.** The last-K cap is safe only if refinement never needs tree K+1 — today it caps at 20 by the same number, but the two caps must be *the same constant forever*; that coupling is a new invariant to maintain (and to add to the spec).
- **Do not choose full C2 if** the thesis timeline is the binding constraint; choose its (i)+(ii) slice (the event log + heartbeat) inside C1 instead — that is exactly the recommendation in Part D.

## Candidate 3 — Two-Lane Provenance Split (packaging-centric)

### Organising principle

**What is APE and what is APE-RV are separated physically, not by flags: upstream APE is fossilised into its own build artifact that no fork commit may touch, and APE-RV becomes a composition lane that carries every fork behaviour by construction.** C3 attacks R4's *other* boundary (T7): the fork↔upstream seam, which today is 22 modified files with no separation and a kill-switch that produces a near-pure fiction. The organising inversion: instead of *configuring away* the fork at runtime, *delete the need* — the `ape` arm runs an artifact that is upstream, and the question "is the control really APE?" is answered by a build hash, not by an argument.

### Module structure and key abstractions

```
repo-root/
  third_party/ape-upstream/     # vendored upstream @ 8f51b99, read-only by policy + CI check
    pom.xml                     # own module: javac 11 → d8 → ape-stock.jar
  src/main/java/...             # the fork (today's tree, re-architected per C1)
    pom.xml                     # ape-rv.jar, as today
  divergence-register.md        # every behavioural difference of lane B vs upstream, enumerated
```

Two Maven modules, two artifacts, both built from source in the Docker image (constraint 2b preserved — nothing committed binary). The plugin learns one new trick: the variant declares its artifact (`artifact: stock | rv`), and `_resolve_jar_path()` (`tool.py:698-730`) pushes accordingly. "Mode" for the extremes becomes **artifact identity**; modes *within* lane B (aperv/mop/llm/llm_mop) still need C1's RunSpec+preset registry — C3 does not replace C1, it amputates its hardest requirement.

**The crucial honesty: what actually has to move.** The fork's changes to the 22 upstream files sort into three buckets (all verified by upstream diff in Part A):

1. **Fork features** (SataAgent's +739, StatefulAgent's +506, Config's +285, MonkeySourceApe's +174, etc.) — lane B only; no conflict.
2. **Ungated behavioural deltas** (the 11 of T7) — lane B keeps them; each gets a register entry. Four are semantic (`Naming.java:429-431`, `NamingFactory.java:280,:1180`, `GUITree.java:288-296`) — the register must say plainly: *lane B's abstraction/refinement behaviour diverges from upstream's*, and the oracle harness (below) must bound the divergence's effect size.
3. **Welded constructs** (ActionType ordinals, `isEphemeral` sites, `ModelAction` provenance fields) — lane B only; but they mean lane B can never again diff cleanly against a *future* upstream. The fossil lane is also a frozen one: upstream is dead (`8f51b99`, 2021), so this costs nothing in practice — say it explicitly.

**What gets deleted:** `apePureMode`, `rvForcedOffValues`/`rvUnsetKeys`/`rvExemptReasons` (~70 LOC of string registries), the `ape_pure` Python arm and its `_APE_PURE_ARM_FLAGS` block, INV-ARCH-01/06 as *specs* (replaced by a CI check: `sha256(ape-stock.jar)` pinned + a CI job that fails if any commit touches `third_party/ape-upstream/`; the *property* INV-ARCH-06 bought — future RV flags must register — is preserved by C1's enum, which is compile-time total). Also deleted: the entire T7 problem class — no future change can silently enter the control arm, because the control arm's artifact cannot change.

**The seed-pairing cost (must be confronted):** upstream uses `ThreadLocalRandom` (`RandomHelper.java:25-36` is a fork change) — **lane A cannot be seeded**. Paired-seed designs then either (i) exclude the control arm from pairing (pair within lane-B arms only; control comparisons are distribution-level) — defensible, since the NB pipeline already relies on app clustering and repetitions rather than draw-level pairing; or (ii) patch lane A with *only* the seed change, moving from "byte-pure upstream" to "upstream + seed patch" — at which point C3's entire advantage over C1's documented-fixes baseline reduces to *one* fix's worth of purity. This trade is the owner's to make (Part E, Q1), and it is cheap to decide: option (i) needs no code.

### How the five modes (and a sixth) are expressed

`ape` = lane A artifact, zero properties (or only seed, under option (ii)). `aperv`/`mop`/`llm`/`llm_mop` = lane B artifact + C1 presets. The preset registry gains an `artifact` column; the Python variant gains `artifact: stock` for exactly one arm. A sixth mode = a new lane-B preset (per C1); lane A is not extensible **by design** — that is what it is for.

### §2.2 answers in C3's terms

`mop` = lane-B `aperv` base + MOP (unchanged from B8). The `ape+MOP-only` screening arm is *impossible* in lane A (no MOP there) and is exactly lane B with base features off — which in C3 is just another lane-B preset, losing nothing. `llm` fallback: lane-B base parameter (per C1). The "is mode the right primitive" question dissolves differently: at the coarsest grain, mode is *not even configuration — it is provenance*; at every finer grain, it is presets over features.

### Drivers D1–D10 in C3

- **D1:** the strongest position available without reverting fixes: the control arm is upstream by construction; the thesis writes "APE @ 8f51b99, built with the modern toolchain, build hash pinned"; no kill-switch argument needed. Cost: the unseeded-control trade above, and lane B still owes the divergence register for the *aperv* vs *ape* comparison (the improvements study needs it as much as the guided-vs-unguided one).
- **D2:** partially solved — the two extremes are artifact-selected; within-lane-B modes still need C1 (or revert to flags). C3 alone makes the mode problem *smaller*, not solved.
- **D3/D4:** untouched — lane B still has T1/T2 unless C1 is also done. Be explicit: **C3 is not an alternative to C1; it is an alternative to C1's D1 position.**
- **D5:** lane A is untestable-but-frozen (fine); lane B as C1 if adopted, else as today.
- **D6:** orthogonal; lane B as C1/C2 choices dictate. One bonus: lane A's memory behaviour is upstream's known-OOM behaviour — measured once, documented, no longer the fork's problem to explain.
- **D7:** orthogonal (adopt C1's minimum or C2's slice). One bonus: lane A emits *upstream-format* output, so cross-tool comparability with phase-2's APE runs is literally byte-format compatible.
- **D8:** unchanged from B6's division of labour; applies to both artifacts uniformly (the plugin's contract is artifact-agnostic).
- **D9:** the arm matrix gains a clean column (artifact), and the ablation story for "the aperv improvements themselves" (F1–F8, currently untoggleable as a group except through the pure kill-switch) becomes possible: lane B with base features off vs lane A is *two* distinct baselines answering different questions — "is aperv better than upstream?" (lane A vs lane B) and "does guidance help?" (presets within lane B). Today those are conflated in one `ape_pure` arm.
- **D10:** new concepts: 1 (the lane/artifact split) + the divergence register as a maintained document. Build complexity: +1 module, +1 artifact in Docker, plugin gains artifact selection (~30 lines + tests). The register is a perpetual discipline cost: every lane-B commit that touches the 22-file surface must update it — enforceable by a CI diff-check on the file list (mechanical, no judgement). LOC: −150 deleted from lane B (kill-switch, registries, pure-mode plumbing), +0 in lane A (vendored), +~200 build/CI/plugin.

### Migration path

(i) Vendor upstream + Maven module + d8 profile → `ape-stock.jar` builds in Docker (days; the toolchain is already proven on the same code). (ii) Plugin: artifact selection per variant; the `ape_pure` arm switches to lane A (one-line variant change + guard update). (iii) Equivalence smoke: lane A on 5 APKs reproduces upstream behaviour (it *is* upstream — the smoke is for the toolchain, d8 vs upstream's build). (iv) Divergence register: write the 14 entries (11 deltas + 3 constructs) from this report's Part A; wire the CI file-list check. (v) Delete `apePureMode` et al. from lane B *only after* C1 stage 2 lands (the enum absorbs the pure values, so nothing is lost). Comparability with already-collected `ape_pure` data: the old arm and lane A differ by the 11 deltas — the register says which; for the phase-3 campaign the control arm is simply re-run (it must be anyway — same grid, same timeouts).

### Honest weaknesses

- **It solves one driver superbly and delegates nine.** Adopting C3 alone yields the best control arm in the world attached to the same spaghetti. It is a *complement* that was priced as a candidate because D1 is thesis-critical.
- **The unseeded control** is a real methodological cost if the owner wants paired seeds across *all* arms including the control (Q1, Part E).
- **Two artifacts = two of every deploy/debug confusion** ("which jar produced this trace?" — mitigated by stamping the artifact hash into the trace, which C1's `RunStarted` does anyway).
- **The register can rot.** The CI check makes rot *loud*, not impossible; a register entry that says "semantic divergence in refinement, effect size unknown" is a standing invitation to a reviewer — the oracle harness (C1's D1 element) is still needed to close it, so C3 does not even save that work.
- **Do not choose C3 as a standalone** — it is not one. Choose it if and only if "the control is byte-pure upstream" is a hard thesis requirement *and* the seed-pairing trade is accepted. Otherwise its CI-check + register ideas adopt cleanly into C1 at ~10% of the cost.

---

# Part D — Comparison and recommendation

## Scoring (weights are mine, stated so the owner can change them)

Weights reflect the thesis stake (§2.3): D9 15%, D1 12%, D7 12%, D2 10%, D10 10%, D3 9%, D4 8%, D5 8%, D6 8%, D8 8%. Scores 1–5, justification below the table.

| Driver (weight) | C1 RunSpec+Pipeline | C2 Event-Sourced | C3 Two-Lane |
|---|---|---|---|
| D1 fidelity (12) | 3 — documented-fixes + oracle | 3 — same as C1 | **5** — upstream by construction |
| D2 mode (10) | 4 — jar-owned presets, loud failure, echo verification (Q3: no shared manifest) | 4 — inherits C1's (same Q3 adjustment) | 3 — extremes only |
| D3 features (9) | **5** — declared deps/conflicts/order | 4 — inherits C1's | 2 — delegates |
| D4 spaghetti (8) | **5** — deletes the method | 4 — inherits C1's | 2 — untouched |
| D5 testability (8) | 4 — RunSpec kills statics | **5** — + replay CI | 2–4 (as C1 if combined) |
| D6 memory (8) | 3 — surgical bounds | **5** — structural bound | 2 — orthogonal |
| D7 traceability (12) | 3 — minimum + echo | **5** — schema, join, loss-resistant | 2 — orthogonal |
| D8 resilience (8) | 4 — contract + loud truncation | **5** — + honest resume | 3 — unchanged |
| D9 ablation (15) | 4 — generated arms, screening designs | **5** — + per-candidate signals | 3 — clean baseline split |
| D10 simplicity (10) | **5** — 2 concepts, −700 LOC | 2 — 4 concepts, perpetual tax | 4 — 1 concept, but partial |
| **Weighted total** | **4.05** | **4.03** | **2.85** |

C1 and C2 tie within noise; C3 is not a standalone contender, as its own section concedes. (The Q3 decision costs C1 and C2 one D2 point each — from 4.15/4.13 — and changes nothing in the ordering.) The differentiators are D10 (C1 wins by 3 points × 10%) against D6+D7 (C2 wins by 2+2 points × 20% combined) — i.e., the choice between them is exactly the choice between *simplicity now* and *structural data later*.

## Recommendation

**Adopt C1 as the spine, now. Adopt C2's (i)+(ii) slice — the typed event log and the logcat step-heartbeat — as a scoped subsystem inside C1, gated on a measured per-step cost budget. Adopt C3's lane-A build as a *verification oracle* and CI fossil-check only.** The three decisions of 2026-08-01 settle the recommendation's open dependencies: Q1 makes "APE + documented bugfixes, oracle-verified" the control-arm position (no shipped lane A); Q2 green-lights the heartbeat join; Q3 removes the shared manifest, so mode authority is jar-resident with drift made loud by fail-fast validation plus per-run echo verification. Full event sourcing of the model (C2 iii–v) is explicitly *not* recommended on the current timeline: its marginal value over the slice is resume and the memory bound, both of which have cheaper targeted answers (C1 stage 5; B6's division of labour).

The decisive reasons: (1) **D10 is not vanity here — it is feasibility.** One maintainer, live experiments, and no subsequent DSR cycle to absorb failure (§2.3): the architecture must be right *the first campaign*, and C1 is the only candidate whose every stage is behaviour-preserving until explicitly gated. (2) **The thesis-blocking telemetry gap (violation↔step) does not require event sourcing** — it requires one event stream and one heartbeat, which is 20% of C2 for 80% of its D7/D9 value. (3) **D1's defensibility is an argument plus evidence, not a package layout**: the divergence register + oracle harness *is* the argument; C3's physical split adds a build system, not a proof — while costing seed-pairing on the control arm. (4) C3's fossil-check and register are cheap and valuable — steal them.

## Decomposition into independently valuable steps (the real deliverable)

Each step ships value alone, in this order; none blocks the next on anything but the previous one's interface:

| # | Step | Drivers | Behaviour change | Blast radius |
|---|---|---|---|---|
| 1 | `Feature` enum + `RunSpec` + `[APE-RUNSPEC]` echo + unknown-key error | D2, D3, D5 | None | jar only |
| 2 | Exit-code contract + `RUN_END` sentinel + truncation loudness | D8 | task accounting (correctly) | jar + `tool.py` (~20 LOC) |
| 3 | JSONL event stream (dual-emit) + logcat heartbeat + result-processor join | D7, D9 | additive telemetry | jar + rv-platform result processor |
| 4 | Decision pipeline extraction (`DecisionAssembly` + stages) | D4, D5, D3 | none (golden-sequence pinned) | jar only |
| 5 | Jar-resident `ModePreset` registry + `--ape` made total + Python arms thinned to `{preset, overrides}` + echo verification in result processing | D2, D9 | none (each thinned arm must echo the identical effective config it produces today — hard gate) | both repos + CI |
| 6 | Memory: bounded history, cache eviction, tree-history cap (A/B gated) | D6 | yes — measured first | jar only |
| 7 | Upstream oracle build + divergence register + fossil-check CI | D1 | none | build/CI + docs |

Steps 1–5 are C1; step 3 is the C2 slice; step 7 is the C2/C3 adoption. Steps 2 and 3 close the two *thesis-blocking* holes (truncated runs counted as successes; violations unjoinable to steps) for roughly one week of work combined — they should not wait for any larger decision.

**Ablation design (D9 concretely, anchored to the real cost model):** one arm = 181 apps × 3 reps × 3 timeouts ≈ 1,629 tasks; phase-2's 11-config grid was 21,681. A 2^10 factorial (~1,024 arms) is absurd; a Plackett–Burman / resolution-IV screening over the ~10 RV features (12–20 arms ≈ 20–33k tasks) is phase-2-scale and feasible on the same 16-container infrastructure. Recommend: (i) the 5 presets as the confirmatory grid (thesis headline: `ape` vs `aperv` vs `mop` vs `llm` vs `llm_mop`); (ii) one PB screening campaign for feature main effects (exploratory chapter); (iii) 2–3 targeted interaction arms (base×MOP, widget×frontier, LLM-dose as a *continuous* factor analysed by regression rather than arm count — the calibration campaign already established the dose-response shape, §3.11); (iv) within-run mechanism metrics (`decision_source` mix, `cf_changed`, per-candidate signals if step 3 lands) as covariates, never as causal claims (myopia contract, `design.md:103`); (v) unit of analysis = run, app-clustered, seeds paired across lane-B arms; the frozen NB pipeline (`rq1_jca.py:178-219`, `:245-260`) and frozen metric definitions (constraint 4b) are untouched by every step above. **And the saturation warning is heeded**: with `mop_unique` at 4.12–4.41 across 11 calibration arms (§3.11) and 86.78% of misuses in libraries, the instrument's discriminating power lies in *app-code* unique misuses and MOP coverage on the 76/163 app subset — per-origin, per-subset recording is exactly what step 3's join enables at analysis time; no jar change can manufacture an outcome the apps do not contain, and the report should not pretend otherwise.

---

# Part E — Risks, unknowns, and next steps

## What I could not determine from the code — measure before committing

1. **Heap high-water mark** over a 600 s run on a redreader-class APK (the 48.3 MB JSON case), per arm class. Determines whether step 6 is urgent or optional.
2. **Per-step JSONL serialisation cost** on the emulator (target p99 < 5 ms/step). Above that, trim per-candidate telemetry; the fixed wall-clock budget makes every ms/step a coverage tax (0.037–0.052 pp/step, calibration report §2.2).
3. **Logcat timestamp skew distribution** (violation event vs device wall-clock). If p99 skew ≪ step duration, the heartbeat join is robust; otherwise add a monotonic anchor.
4. **Real distribution of run terminations** per arm (how many COMPLETED tasks lack a clean end — the 45% figure is one LLM-arm campaign; baselines measured 0–1.3%, `20260716_cmpv2_truncation_bug.md:329`). Sizes the silent-invalidity problem step 2 fixes.
5. **Naming-divergence effect size**: the 4 semantic deltas (T7) — run the oracle comparison and bound how much `aperv`-lane abstraction behaviour differs from upstream's. Sizes the D1 argument.

## Cheap experiments that discriminate between candidates

- **Step 3 spike (2–3 days):** JSONL sink + heartbeat on one arm, 20 APKs × 300 s. Measures E2/E3, demonstrates the join end-to-end, and runs the calibration-report acceptance test (rebuild §3.11's per-call table from the stream alone). If this fails, C2 in any form is dead cheaply.
- **Oracle build (2–3 days):** vendored upstream through the Maven/d8 toolchain; 5 APKs smoke. De-risks step 7 and prices C3 honestly.
- **Preset-registry coverage check (1 day):** express all 26 existing arms as `{preset, overrides}` including the six frozen gh43 exemptions. If they cannot be expressed in that shape, step 5's premise is wrong while it is still cheap.
- **Arm-count budget simulation (hours):** PB(12) vs PB(20) vs targeted-interactions-only, costed in container-days against the thesis calendar. Turns D9 from a design preference into a scheduling fact.

## Questions for the owner — status

**Answered 2026-08-01** (consequences folded into the report; see "Owner decisions" at the top):

1. **Q1 (D1):** ~~Is "APE + documented bugfixes, oracle-verified" an acceptable control, or must it be byte-pure upstream at the cost of unpaired seeds on the control arm?~~ → **APE + documented bugfixes is acceptable**; C3's lane A is an oracle, not a shipped arm.
2. **Q2 (D7):** ~~May the jar write a write-only step heartbeat to logcat?~~ → **Yes** — the heartbeat join proceeds as designed; "APE never touches logcat" is clarified as read-coupling only.
3. **Q3 (D2):** ~~Shared `ape-modes.json` as single source of truth?~~ → **No** — mode authority is jar-resident; Python keeps arm freedom; drift is controlled by fail-fast validation + echo verification, not by a shared artifact.

**Still open:**

4. **Q4 (D9):** What is the arm budget for the phase-3 campaign in container-days? *(Determines screening-design size; ~1,629 tasks per arm at 181 apps.)*
5. **Q5 (D8):** For the thesis grid, confirm the position: no restart-in-place ever; truncated runs are failed tasks retried as new repetitions (checkpoint resume reserved for non-thesis exploratory runs).

## What this analysis might be wrong about

- **The per-step cost estimates** (memory shares, serialisation overhead) are inferences from structure, not measurements; E1–E3 could reorder steps 3 and 6.
- **The heartbeat join's robustness** rests on logcat skew being small relative to step duration; a pathological skew distribution would demote the join to estimation, and with it part of D7's value.
- **The linear-precedence assumption**: I judged today's precedence truly linear (veto chain). If a future mechanism needs weighted competition among guidance sources, C1's pipeline is the wrong shape and B3's arbiter should have been chosen; the evidence (every current interaction is a veto) supports linearity, but this is a bet about future research.
- **The drift-control mechanism has four parts** (startup validation, echo, result-processor check, CI schema diff) — Q3 moved D2's authority fully jar-side, but if the optional CI diff is skipped and the result-processor check is never wired, the design degrades gracefully back to today's silence. The two verification pieces are cheap but not optional in spirit.
- **The effect-size irrelevance of the 4 semantic deltas** (D1's "documented fixes" position) is an unmeasured assertion; E5 could convert it from a documentation task into a revert decision. If the oracle shows the deltas materially change exploration, the honest control arm requires reverting them in a lane-B `pure` preset — possible in C1, but the thesis's control paragraph changes.
- Evidence that would change the recommendation: E2 measuring >10 ms/step (drop the event-stream slice to decision/outcome events only); E4 showing truncation is rare outside one LLM arm (demote step 2); the oracle (E5) showing the semantic deltas are material (re-plan the D1 position around gating or reverting the naming deltas).

---

# Appendix Z — Discrepancies found while verifying the dossier (§3)

The dossier was accurate in substance everywhere it mattered. Corrections, for the record:

1. `selectNewActionNonnull` is at `SataAgent.java:449-589` (141 LOC) — §9's `:392-527` is stale; §3.2 correct.
2. The stagnation predicate no longer lives at `SataAgent.java:436`: it moved to `LlmRouter.stagnationMidpointReached` (`LlmRouter.java:267-270`) and is now `>=` (post-fix); `SataAgent`'s static import of `graphStableRestartThreshold` (:22) is dead code.
3. `LlmRouter` telemetry getters: **14** at `:942-995` (dossier: ~12 at :750-793); boundary bands `:657-664`; snap tolerance `:754-759`.
4. `generateEvents` nesting depth measured **6**, not 8.
5. `StatefulAgent`: `safeStep` chain is **9 steps** at `:1793-1814` (dossier: 8 at :1683-1690); `saveGraph` at `:1855-1902`; `_llmRouter` gate at `:194`; `[APE-STEP]` full emit `:1491-1507`, short form `:1520-1528`; `clock=` at `:1496/:1523` with the design comment at `:1484-1487`; the dossier's third step-telemetry site `:1392-1420` does not exist.
6. `rvForcedOffValues` holds **27 keys**, not 26 (15 false + 11 zero + 1 `Integer.MAX_VALUE`).
7. `ModelAction` boost/provenance fields are at `:91-107`; the dossier's `:36-64` is the two enums (`DecisionSource` 11 values :42-44; `PickChannel` 8 values :57-65).
8. `Monkey.java` deadline check is at `:1293-1302` (dossier `:1560-1582` is `nextOptionLong`/`nextArg`); there is no global `catch` around `runMonkeyCycles` — a `finally` with two inner per-step `catch (Throwable)`.
9. `Graph` holds **17** unbounded collection fields (:98-130), not 13; `State.removeLastLastGUITree` at `:556-561`; MODEL_MENU accessor at `:305-307`; GUITreeBuilder caches at `:670,:671,:693`, `release()` `:707-715`.
10. Upstream diff: **33** new files in `src/main`, not 32 (the 33rd is `MopCounterfactual.java`); 0 removed / 22 modified confirmed.
11. Test suite: 74 files / 785 `@Test` confirmed; skips are 15 `@Ignore` + 2 `Assume` sites (dossier ~19). CLAUDE.md's "145 tests, 14 skipped" is stale against the on-disk suite.
12. `Config.` occurrences in `src/main`: 286, not 283; per-file read counts drifted (57/36/27/26/21 combined vs dossier 54/30/26/25/24) — ranking preserved.
13. Capability specs: **19**, not 20. INV-APV-* and INV-PLT-16 are defined in rv-android / rv-platform, not this repo (as the dossier suspected for APV; PLT likewise).
14. rv-android: the app-vs-library filter is a **substring** containment check (`static_analysis_parser.py:356-361`), explicitly not `startswith`; the phase-2 paper's *own* prefix test is `ase-journal/data-analysis/mneut_scope.py:150-157` — the dossier's "Mneut prefix test" conflates the two. The sibling drift test (`test_gh77_variants.py:55-72`) is Python↔Python, not Python↔jar (the structural contrast with aperv stands). `docs/20260716_investigacao_truncamento_600s_llm_tap.md` exists in *this* repo's `docs/` (not in rv-android's, where the 45% figure lives at `20260716_cmpv2_truncation_bug.md:330`). INV-APV-16 asserts dict equality, not identity.
15. ase-journal: IRR/CI/p, χ², coverage numbers (33.06/21.88/8.04/10.63) and library counts (175/84/16/6) are prose literals in `results-rq*.tex`, not `constants.tex` macros; "76/163 apps" is derived (163 − `\directCovNoSupport`=87, `constants.tex:91`).
16. The counterfactual myopia contract is design note **D2** (`design.md:103`), not D9/D11 (D11 is the `patched=` note, `:157`).
17. Scoring-pipeline doc rot (not dossier error): class javadoc still says "six passes" (7 are constructed, `ScoringPipeline.java:53-59`); `StatefulAgent.java:1628` comment omits `MopFrontierPass`; `Monkey.java:1598` usage omits `replay`.

*End of report.*

