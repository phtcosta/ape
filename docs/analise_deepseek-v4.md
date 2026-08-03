# Preliminary architecture study: re-architecting APE-RV

- **Model:** deepseek-v4 (report authored for the analysis requested by `docs/20260801_prompt_rearquitetura_aperv.md`)
- **Date:** 2026-08-01
- **Commit analysed:** `5dcf225976b26ce78d8b31dd88d7f858dad29d43` (`git rev-parse HEAD`, branch `master`)
- **Method:** 7 parallel subagents + direct verification. Coverage: (a) upstream-vs-fork frontier and the "what cannot be turned off" frontier; (b) control-flow and mode-dispatch archaeology in the five hotspot files; (c) memory ownership / object-lifetime graph; (d) observability and experiment-provenance chain incl. the `rv-android` consumer; (e) resilience, process lifecycle and the Android/`app_process` model; (f) thesis (`doutorado-tese`) and phase-2 paper (`ase-journal`); (g) prior-art scan (Monkey, DroidBot, Humanoid, Fastbot2, Stoat, ARES, APE upstream, GPTDroid/DroidAgent/AppAgent/META-GUI/AutoDroid, LLVM PassBuilder). I read the dossier, the four §4 docs, and the primary sources myself and spot-checked every claim the argument leans on. Where the dossier's line numbers had drifted from HEAD I cite the verified current lines and flag the drift; the dossier's substance held in every load-bearing case. One dossier claim was refuted (the duplicated stagnation predicate) and is reported as such in §A3-T3.

---

## Part A — Diagnosis

### A1. What APE-RV is today, structurally

The real layering is not the aspirational one (Monkey → Agent → Model). It is six layers, of which only four have a name:

1. **The AOSP shell.** `com.android.commands.monkey.Monkey` (`Monkey.java`) is the process entry through `app_process`. It owns: argument parsing (`:921-931`), the crash/ANR controller (`:274-386` base, `:388-498` APE override), the stop condition (`:1291-1302`, one check per loop iteration), and the isolated teardown `finally` (`:773-800`). The teardown isolation invariants (INV-EXPL-16/29) live here and in `MonkeySourceApe`/`StatefulAgent`; this is the best-engineered part of the system.
2. **The production loop.** `MonkeySourceApe` (`:822-914` `generateEvents`) is a perception-and-dispatch funnel: refetch loop, foreign-activity guard, tree-package guard, null-info fallback, then a single `updateState` call that produces one `Action`, expanded into a batch of low-level `MonkeyEvent`s. This is where the "guards" live (F7), all of them real `if`s with hard-won semantics (fail-open on `treePackageGuard`, deliberate fall-through on the budget).
3. **The agent.** `ApeAgent` → `StatefulAgent` → `SataAgent`/`RandomAgent`/`ReplayAgent`. `StatefulAgent` is the largest file (1904 LOC, 111 methods, 166 `if`s) and is where RV got bolted in: MOP load (`:185-187`), LLM router construction (`:194`), the scoring pipeline (`:208`), the three telemetry channels, and 9-step isolated teardown (`:1802-1814`). `SataAgent.selectNewActionNonnull` (`:449-589`) is the decision epicentre (§A3-T2).
4. **The scoring pipeline.** `ape/agent/scoring/` — 7 passes in fixed order (`MopWidget → MenuGateway → WTG → Frontier → MopFrontier → Coverage → FormCompletion`), assembled once at `ScoringPipeline.fromConfig` (`ScoringPipeline.java:51-61`), applied at the single injection point `StatefulAgent.adjustActionsByGUITree` (`:1631`). Disabled pass = strict no-op (INV-ARCH-02). This is the one genuinely well-scoped RV abstraction, and the §4 document shows why it was scoped that way.
5. **The model.** `ape/model/` + `ape/naming/` — the upstream core: `Model`, `Graph`, `State`, `Action`, `ActionType`, `ModelAction`, `NamingFactory`. **Crucially, the fork welded RV concepts into this layer**, which is what makes a clean "RV = bolt-on" story impossible:
   - `ActionType.java:38,42,48` — `EVENT_TRIGGER_ACTIVITY`, `MODEL_MENU`, `MODEL_LLM_TAP` inserted into the enum, shifting ordinals; `requireTarget()`/`isModelAction()` are ordinal-range checks (`:56-59,70-73`).
   - `Action.java:135-137` — `isEphemeral()`, consulted at five ungated core sites (`Model.java:315,384,423`; `Graph.java:452,590`); "ephemeral edge" is now graph semantics.
   - `ModelAction.java:42-64` — `DecisionSource` (11 values) and `PickChannel` (8 values) enums + 6 boost fields (`:98-107`) as permanent object state.
   The naming/refinement lattice (the research contribution) is still upstream-intact behaviourally, but it sits next to RV bookkeeping inside the same object graph.
6. **The de-facto mode system.** It does not live here. It lives in Python: `rvsec/rv-android/modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py` — 52 key-pair mappings (`:75-162`), 18 arm-defining keys (`:171-192`), composable dict-spread blocks (`:240-336`), 26 arms in `get_variants()` (`:427-659`), all guarded by 83 pytest functions.

**Inherited vs forked complexity.** Against upstream `8f51b99`: 672 files changed, ~98,337 insertions, ~1,263 deletions — but ~100 of those files are pure renames (Maven relocation `src/…` → `src/main/java/…`, R100). Only 22 upstream files were modified in place, 32 main files are new, and 2 upstream files were replaced (`SataAgent.java`, `Config.java`). The most-modified in-place files are the five hotspots (§3.2). So: **the fork's complexity is overwhelmingly additive and concentrated**, and it is concentrated in exactly the decision path (`SataAgent`), the lifecycle path (`StatefulAgent`), the dispatch path (`MonkeySourceApe`), the LLM path (`LlmRouter`), and the configuration surface (`Config`). The naming/model core is mostly intact. The observed spaghetti is not inherited from APE; APE's `SataAgent.selectNewAction` was a clean chain of `selectNewActionX()` calls. The fork's `selectNewActionNonnull` is a rewrite.

### A2. The mode/feature control surface and why it is where it is

Today the control surface is two orthogonal, unconnected axes:

- **The CLI axis.** `--ape <type>` → `Config.get("ape.agentType")` → `ApeAgent.createAgent` (`:68-96`), a 4-branch `if` over a raw String: `null`/`"sata"` → `SataAgent` (`:78-83`), `"random"` → `RandomAgent` (`:84-86`), `"replay"` → `ReplayAgent` (`:87-93`, `System.exit(1)` when the log is missing, `:91`), anything else → **silent `SataAgent` fallback** (`:95`). `--ape mop` and `--ape llm` today run plain SATA with no log. The usage text advertises two types (`Monkey.java:1598`); the factory accepts three; nothing validates.
- **The property axis.** ~117 `Config` keys, loaded once in a static block (`Config.java:30-44`) from system properties + `/data/local/tmp/ape.properties` + `/sdcard/ape.properties`. Values frozen at class-load; unknown keys silently ignored; malformed numerics swallowed (`:453-454,465-466,477-478`); validation is four ad-hoc clamps; the `apePureMode` kill-switch is a pre-initialiser Properties overwrite (`:36-43` → `forceApePureModeInto` `:403-412`) driving three string-literal registries (`rvForcedOffValues` `:343-364`, `rvUnsetKeys` `:366-370`, `rvExemptReasons` `:372-398`) that the compiler cannot connect to the fields they name.

**Why it is where it is — the historical argument.** This is a fork that grew by accretion under experimental deadlines, and each piece of the current structure has a reason:

1. The fork inherited APE's `--ape` CLI and kept it stable because `rv-platform`'s launch contract (`adb shell app_process … Monkey -p <pkg> --running-minutes N --ape sata`) was frozen early and any CLI change is a cross-repo change. So new behaviour never entered through `--ape`; it entered as *flags*.
2. Each experiment arm needed a property combination; the natural home for "arm definitions" was the Python layer that already computed the task matrix — so the mode system grew up in `tool.py`, not in Java. This is the single most important structural fact: **the mode concept was never given a home in Java, so it colonised Python.**
3. The 2026-07-08 design (`docs/20260708_arquitetura_separacao_aperv.md`) addressed *scoring only*: it extracted the inline scoring blocks into the `ScoringPass` pipeline and added a kill-switch. It explicitly rejected (i) flags-only and (ii) an RV agent by inheritance. It was correct for its scope, but the scope was scoring; routing/precedence, perception, the input chain, object lifetime, the log, and the process lifecycle were left where they were. The dossier's §3.12 shows the consequence: the sixteen telemetry items landed with **zero new Config flags** and were absorbed into `selectNewActionNonnull` (now 141 LOC) and `LlmRouter.selectAction` (286 LOC) — "the two worst methods absorb new behaviour, as they always do."
4. Each new mechanism was bolted in at the point where a `return` could be inserted, because that was the only extension point that existed. The LLM hooks, the launcher, the component trigger, the budget gate — all are `return`s in textual order inside one method. Precedence became statement order because there was no other way to express it.

So the surface is a *sediment*: CLI axis (inherited), property axis (grown), Python arm axis (colonised). The 26 arms and the 117 flags have no representation in code; they are prose in comments (`Config.java:164,216,239,243,270,308`) and dicts in `tool.py`.

### A3. The architectural tensions (eight), each traced to a driver

**T1 — Mode has no home in Java.** There is no `Mode`, no `Arm`, no `Variant` type anywhere in `src/main/java` (verified by grep). The only "mode" is the telemetry label String in `LlmRouter.selectAction` (`:332`, values `new-state`/`stagnation`/`random` from `SataAgent.java:483,501,511`), which selects nothing. The five proposed modes of §2.2 are undefined as software; the two that exist (`ape`/`sata`) are the accidental residue of a 3-branch String switch with a silent catch-all. → **Driver D2.**

**T2 — Precedence of four independent subsystems is encoded as statement order in one 141-LOC method.** `SataAgent.selectNewActionNonnull` (`:449-589`) is a linear sequence of flag-guarded blocks, each ending in `return`: logging (`:450-462`) → budget gate (`:468-477`, deliberate fall-through) → LLM new-state (`:480-487`) → LLM stagnation (`:493-506`) → LLM random (`:508-515`) → MOP-activity launcher (`:522-545`) → component trigger (`:547-551`, side-effect, no return) → the SATA chain — the pattern `resolved = selectNewActionX(); if (resolved != null) { logActionSelected(...); return resolved; }` copy-pasted **7 times** (`:553-587`) → `throw new BadStateException` (`:588`). The precedence LLM-before-launcher-before-SATA is documented only in 5–8-line comments (`:472-474, :488-492, :516-521`). The three-clause LLM precondition (`actionBufferSize()==0 && getActions().size()>2 && _llmRouter != null`) is repeated three times (`:480-481,493-494,508-509`). → **Driver D4** (this is the "spaguetizado" the owner means).

**T3 — The "when to use the LLM" decision is split across classes, and one dossier claim is wrong.** The *predicates* are single-sourced in `LlmRouter` (`shouldRouteNewState` `:232-237`, `shouldRouteStagnation` `:249-255`, `shouldRouteRandom` `:276-281`, all `... && Config.<flag> && breakerAllows()`), and the shared midpoint helper `stagnationMidpointReached` (`:267-270`, `!firedThisEpisode && graphStableCounter >= threshold / 2`) exists in **exactly one place**. The dossier's claim that the `threshold/2` condition is "duplicated at `SataAgent.java:436` and `LlmRouter.java:225`" is **refuted** at HEAD — grep finds no `threshold / 2` in SataAgent or StatefulAgent. What *is* split across three classes is the **state and its consumption**: the single-shot flag `stagnationHookFired` is declared in `StatefulAgent.java:128`, re-armed in `StatefulAgent.java:1436`, and burned in `SataAgent.java:499`; the graph-stability counter is mutated in `StatefulAgent.onVisitStateTransition` (`:1427-1452`). So the correction sharpens the finding rather than dissolving it: the *decision logic* is centralised but the *state it reads is owned by three classes*, and the ordering of the three LLM hooks plus the launcher still lives in SataAgent's textual order. → **Drivers D4, D7.**

**T4 — Baseline fidelity is a flag, not a structure, and the flag is a near-miss.** `apePureMode=true` produces a *near*-pure APE. The kill-switch is complete by reflection (the reflective `ApePureModeKillSwitchTest` forces classification of every public-static `Config` field), but **at least sixteen fork changes survive it with no flag**: the seedable `Random` (`RandomHelper.java:27-36`), the `Naming.containsNamelet` binarySearch fix (`Naming.java:429-431`), the `NamingFactory` per-tree vs per-state refinement gate (`:280,:1180`), the `GUITree.indexOfName` normalisation (`:288-291`), the two `Graph` visit-count fixes (`:619-621,:1316-1324`), the `Model` action-history exception tolerance (`:107-115`), the `StringCache` empty-list fallback (`:112-116`), the `ApeFuzzer` precedence fix (`:178`) **and** its new pinch/zoom dispatch (upstream computed the array and never enqueued it — the fork added `ApeFuzzer.java:197`), the `ApePinchOrZoomEvent` length guard (`:46-48`), the `Monkey` teardown restructure (`:777-799`), the DOM-prune rewrite (`GUITreeNode.java:586-603`), the off-screen action drop (`MonkeySourceApe.java:144-152`), the `waitForActivity` relaunch bound (`:1317-1333`), the `broadcastIntent` catch broadening (`AndroidDevice.java:436-443`), and the `setIsPassword` field population (`GUITreeBuilder.java:640`). The two "always-on exceptions" the repo admits (`ApePureModeAlwaysOnExceptionsTest.java:11-24`) understate this by 14 items. Most of these are crash/correctness fixes, but at least two change *behaviour* (naming/abstraction at `Naming.java:429`, refinement condition at `NamingFactory.java:280`) and one changes what the pure arm actually executes (fuzz pinch/zoom now dispatches). → **Driver D1**, and the D1 decision cannot be made by flag-flipping alone.

**T5 — The arm layer lives in Python with no contract; drift fails silently.** The split-brain is precise: Python owns *which named combinations exist* (26 arms), key translation (52 pairs), value serialisation, and the hard timeout; Java owns *flag semantics*, defaults, clamps, and the kill-switch list. Neither is the single source of truth; the seam carries files and command lines only. Consequences, all verified: an `ape.*` property rename/deletion is silently ignored by `Config` while all 83 pytest guards stay green (the gh71 precedent: a stale jar made the MOP boost fire in 0 of 147,153 evaluations); the kill-switch is duplicated (`_APE_PURE_ARM_FLAGS` `tool.py:264-283` vs `Config.rvForcedOffValues` `Config.java:343-364`) with **no drift test** and the two sets disagree (Python sets `llm_percentage_no_substrate=-1`, Java forces `coverageBoostWeight`/`componentPercentage`/`mopWeight*`/`mopTargetPickCap`/`activityStableRestartThreshold`); INV-APV-14 validates a Python constant against itself; dead keys survive as archaeology (`mop_weight_activity` `tool.py:95` maps to a key deleted from `Config.java`); values cross the DSL as untyped strings (`rv_experiment/__main__.py:214`) so `@heuristic_input=False` writes the Java-invalid literal `False`; a missing static-analysis JSON is only a warning and the arm silently runs with no MOP (`tool.py:1092-1096`). → **Drivers D2, D9.**

**T6 — Nobody owns a GUITree, so memory is unbounded and pressure is unhandled.** There are three *independent* unbounded retention roots (fixing one is insufficient): (i) `Model.actionHistory` (`Model.java:136-137`, the code's own `TODO: may be the cause of OOM`) holds one `ActionRecord` per step forever, each strong-referencing the executed `GUITree`/`GUITreeNode` subtree — and via `GUITree.currentState` (`GUITree.java:71`) transitively the whole `State`; (ii) `Graph.treeTransitionHistory` (`Graph.java:118`) keeps one entry per transition forever; (iii) the **never-cleared static** `GUITreeBuilder.namingToGUITreeNodeCache` (`:693`) — `release()` (`:707-715`) clears the other two caches but not this one, so every `GUITreeNode` ever named under a non-current naming is pinned forever (a strictly monotonic leak). Stacked with eternal interners `NameManager.names`/`nameList` (`NameManager.java:27-28`) and `StringCache.stringDict` (`:32`), plus `AbstractNamingManager.treeToNaming` (`:43`) keying every modelled tree (note: the "copied wholesale on refinement" claim is *refuted* — the copy at `:103` is inside `clone()`, which has no callers; refinement mutates in place). `maxStatesPerActivity=10`/`maxGUITreesPerState=20` are not memory caps — they only suppress refinement (`NamingFactory.java:276-283,1176-1183`); nothing frees a `GUITree` (except the unstable-screen refetch path, `State.removeLastLastGUITree` `:556-561`). The only LRU in the codebase is `UICoverageTracker` (`:59-69`, `coverageMaxStates`). There is **no `catch (OutOfMemoryError)` in the exploration loop**; the only one is `MopData.java:328` (the JSON parse). On OOM the `finally` teardown runs `saveGraph` and re-OOMs on `oos.writeObject(model)` (`StatefulAgent.java:1866`). → **Driver D6.**

**T7 — The log is stdout with no run id, no sink, no schema, and the baseline is blind by construction.** `Logger.java` is 67 lines: `System.out` only, no level, no sink, compile-time `debug=false` (`:22`). Eleven structured channels exist but they are free-form `key=value` with no escaping (only `text="…"` in `[APE-LLM-TEL]` is quoted, `LlmRouter.java:595`); no JSON anywhere. The join design is deliberate and good (`step=` across `[APE-STEP]`/`[APE-OUTCOME]`/`[APE-LLM-TEL]` via the buffered decision at `StatefulAgent.java:117-124,1512-1513`, with `clock=` epoch-millis on every step line `:1496,:1523` for offline wall-clock joins) — but **INV-ARCH-01 forces `[APE-STEP]` and `[APE-OUTCOME]` off under `apePureMode`** (`Config.java:346`), so the baseline arm — the control arm the thesis depends on — emits zero mechanism telemetry. There is no per-run echo of effective MOP weights/paths (the LLM has `[APE-LLM-CONFIG]`; MOP does not). The transport is lossy: stdout through adb, NUL-contaminated (the calibration parser must read with `errors="replace"`), truncated when adb drops, and gone on SIGKILL. And the thesis-blocking gap: **no key ties an RVSEC violation event in the `.logcat` to the APE step in the `.trace` that caused it**, no `first_seen_step` per dedup key exists anywhere, and no consumer implements the `clock=` join the code already provides. → **Drivers D7, D9** (one problem seen twice).

**T8 — Resilience is split-brain, and the jar loses at the boundary.** The Python layer already provides timeout-as-success (`RVToolTimeoutError` as expected), crash-safe resume at *task* granularity (`TaskStorage` atomic `tasks.json`), and a +15 s grace (`tool.py:991`). But a jar process that dies at second 120 of a 600 s budget is recorded as a **completed** task because the exit code is only `logger.debug`-ed (`tool.py:1121-1125`; the truncation bug documented in `rv-android/docs/20260716_cmpv2_truncation_bug.md:305`, ~45% of one LLM-arm campaign truncated this way). In-process: teardown isolation is excellent (INV-EXPL-16/29, 2+6+9 isolated steps), the `BadStateException` ladder is sound (`ApeAgent.java:353-395`, `stopTopActivity` after 11 consecutive, `StopTestingException` after 101 total), but **resume is impossible by construction**: `saveGraph` writes a `Model` (`StatefulAgent.java:1866`) while `readGraph` casts to a `Graph` (`Graph.java:1168`) — a guaranteed `ClassCastException` caught silently (`:1169-1172`) → "Fail to load graph …" → fresh empty graph (INV-EXPL-03); nothing is persisted except at teardown; there is no shutdown hook; the deadline check is non-preemptive (`Monkey.java:1291-1302`) so a 15 s LLM call overshoots it; and a long run's `sataModel.obj` serialisation is itself an OOM site. → **Driver D8.**

**T9 — Testability was achieved by working around the design, not through it.** The Android stub jars are deliberately excluded from the test classpath (`pom.xml:84-104`), so any class touching real `android.*` is untestable on the JVM; the skips (6 LLM, 9 Android-runtime, 3 PointF+device, 1 AccessibilityNodeInfo) are its footprint. Global mutable state is unresettable: `AndroidDevice`'s 7 public static service handles (`AndroidDevice.java:61-77`), `Config` frozen at class-load (5 fields demoted from `final` as a workaround, `Config.java:149,151,153,165,245`), `NameManager`/`StringCache`/`GUITreeBuilder` static caches leaking across tests, `GUITree`'s non-deterministic counter. Pure logic is extracted into package-private "seams" each with a confessional comment (`Config.clampLlmPercentageNoSubstrate` `:315`, `State.beatsLeastVisited` `:165-167`, `Model.collectReplayTreeTransitions` `:300-311`). → **Driver D5.**

**Causal structure — which tensions are one problem.** T1, T2, T3 and T5 are the **same problem**: *the mode/precedence concept has no representation anywhere in the Java code, so every new mechanism must encode itself as a guarded `return` inside `selectNewActionNonnull`, and its on/off switch must live in a Python dict.* T4 is the *consequence* of that same absence at the baseline boundary (no structural place to say "this is APE", so it is a flag + a registry). T7 is the same problem at the telemetry boundary (no run identity, so no way to tie a step to an outcome). T6 and T8 are the two tensions that are *not* the same problem: they are independent debts (ownership/retention; lifecycle/resume) that would still exist even if the mode problem vanished. T9 is the price the codebase pays for the architecture that T1–T5 produced (static singletons + frozen Config are what make the tests need seams). **The thesis: most of the pain is one problem wearing different clothes — "the mode concept has no home" — and two genuine debts (memory ownership, process lifecycle) that the same re-architecture can and should absorb.**

### A4. What the current design gets right and must not be lost

1. **The teardown isolation invariants.** INV-EXPL-16 ("nothing in the `finally` may throw") and INV-EXPL-29 (a throwing step costs only its own artifact) are the best-engineered part of the system: 2 isolated steps in `Monkey.run` (`:781-798`), 6 in `MonkeySourceApe.tearDown` (`:235-249`), 9 in `StatefulAgent.tearDown` (`:1802-1814`). Any redesign must keep this, and preferably extend it to the exploration loop itself.
2. **Fail-fast on MOP load.** A set-but-unloadable `mopDataPath` aborts via `StopTestingException` (INV-MOP-22, `StatefulAgent.java:185-187`) so an arm is never silently mislabelled. (The corollary gap — an *absent* path is silently non-MOP at the Python layer, `tool.py:1092-1096` — is T5's problem, not this invariant's.)
3. **The `step=`/`clock=` join design.** The buffered-decision discipline (`StatefulAgent.java:117-124,1512-1513`) and the epoch-millis `clock=` on every step line are what made the 2026-07-24 calibration report reconstructible from raw traces alone (880/880 parsed, 39,341 call records reconciled digit-for-digit). Any observability redesign must preserve this analysis capability at minimum; the report is the acceptance test.
4. **The `ScoringPass` no-op-when-disabled contract (INV-ARCH-02)** and its single injection point (`StatefulAgent.java:1631`) — the one clean RV seam.
5. **The arm-explicitness pytest guards (INV-APV-14)** — even though they validate a Python constant against itself, the *discipline* (every non-exempt arm sets every arm-defining key) is scientifically valuable and must survive in whatever replaces it.
6. **The seeding/reproducibility work** (`RandomHelper.seed`, paired designs) — arm-neutral and thesis-required (frozen metric definitions, seed-matched runs).
7. **`apePureMode`'s reflective completeness test** — whatever replaces the kill-switch must keep the *property* "a future RV flag cannot be added without registering itself in the baseline policy, enforced by a test" (`docs/20260708_arquitetura_separacao_aperv.md` §5.3; `ApePureModeKillSwitchTest.java:107-132`).

---

## Part B — Design space

For each axis below: the plausible options, their trade-offs, and what comparable tools do. Options I will not choose are marked. This is the space the three candidates of Part C are carved from.

### B1. Where the mode definition lives

| Option | Trade-off | Reference |
|---|---|---|
| A. Java enum + factory (never happened) | Closest to a `switch`; the moment mode is two-dimensional (base × guidance) or parametrised (widget vs frontier), a flat enum explodes; hard-codes a closed set | — |
| B. Python dicts in `tool.py` (**today**) | No contract, no validation, drift silent; but it *is* where the task matrix already lives, so it has zero migration cost | `tool.py:240-336` |
| C. A **shared declarative artifact** both sides read | One source of truth; the boundary carries files anyway, so a text artifact fits the transport; needs a format (YAML/JSON), a validator on both sides, and a versioning/checksum policy; the hard problem is who validates and when | LLVM `-passes=` pipeline strings; DroidBot named policies; pytest plugin entry points |
| D. In the jar, generated arms (manifest → matrix) | Fixes drift at the source; moves arm-definition authority into Java where the semantics live; costs: the Python task matrix must learn arms from the jar (subprocess or shipped file); risk of over-abstraction | WebKit/Chromium feature configs; cargo/CMake feature model |

I will not choose A. All three candidates choose C or D in different flavours (C1: pipeline spec; C2: capability manifest; C3: build-time lineage selection).

### B2. How features are represented

| Option | Trade-off | Reference |
|---|---|---|
| Flags with scattered read-sites (**today**) | Zero ceremony, impossible to see the composition; no dependencies/conflicts; typo-silent | `Config.java` |
| `ScoringPass` pipeline (scoring only, **today**) | Clean for *scoring*; each pass still reads static `Config` (`ScoringPipeline.java:48-49` admits the injected `cfg` is decorative); nothing covers *perceive/route/pick/input* | `ape/agent/scoring/` |
| **Capabilities with declared deps/conflicts** | The right vocabulary for "F10 needs F2 and MOP; F12 is nominally MOP-agnostic yet coupled" (§3.4); machine-checkable composition; risk: a framework that outlives the features | ARES plugin strategies; OSGi/Spring (overkill here); Fastbot2 "framework-model" split |
| Aspects/interceptors | Cleanest for cross-cutting (telemetry, guards) but heavy and out of reach for Java 11 + no-runtime-deps | AOP (rejected: needs bytecode instrumentation) |

### B3. How decisions are dispatched

| Option | Trade-off | Reference |
|---|---|---|
| Guarded `return`s in textual order (**today**) | Precedence = statement order; the T2 problem | `SataAgent.java:449-589` |
| Chain of responsibility | Each handler may handle-or-pass; matches the current `resolved != null` shape exactly; precedence = list order; cheap | Servlet filters |
| **Pipeline / pass list with a spec** | Same as CoR but with a *named, data-driven* order and per-stage enablement — the pipeline becomes the mode; this is the LLVM PassBuilder model and it maps 1:1 onto the existing `ScoringPass` shape | LLVM New Pass Manager (`-passes=` strings, `PassPlugin`) |
| Data-driven tables (condition → action) | Elegant for the SATA chain itself; loses per-block reasoning; poor for side-effectful blocks (component trigger) | — |
| Policy objects with capability-based dispatch | The full C2 model; most expressive, most machinery | Chromium feature policies |

All three candidates replace the `if`-ladder with an ordered, data-driven dispatch; they differ in *what the order is a spec of* (C1: the whole step; C2: capability activations; C3: nothing — the build decides).

### B4. How state is owned and bounded

| Option | Trade-off | Reference |
|---|---|---|
| Unbounded object graph (**today**) | T6; simple; the known OOM | `Model.actionHistory` `:136-137` |
| Eviction by policy (LRU/LFU/age) at each root | Keeps the object graph; needs ownership rules ("who owns a GUITree and when does it die"); fixes 3 roots independently | `UICoverageTracker` `:59-69` is the only precedent |
| **Append-only on-disk log + in-memory index** | Action history becomes a journal; memory = O(step) not O(steps); resume reads the tail; cost: a device-side sink (T7's T8 open door), one write per step, and the +15 s grace must cover flush | Fastbot2 persists the model every 10 min and reloads; Monkey's event queue is the primitive version |
| Compact indexed graph instead of object graph | Smallest footprint; large rewrite; risks the naming core (constraint 5) | Stoat's static FSM |

C1 and C3 keep the object graph with ownership rules; C2 makes ownership a capability-declared contract; only C2-as-journal (see candidate C2's memory model) writes an append-only log, though I recommend the journal as an adoptable piece of any candidate (§D4).

### B5. How observability is produced

| Option | Trade-off | Reference |
|---|---|---|
| Free-form stdout `key=value` (**today**) | NUL-unsafe, unparseable-by-content, no run id, lost on adb drop, baseline blind (INV-ARCH-01) | `Logger.java` |
| **Typed, versioned event stream** (one event per fact, stable schema, run id, wall-clock on every event) | Joins become trivially reliable; versioning makes old traces parseable; needs a schema registry and an escaping discipline (or JSON-lite); cost per step must stay ≪1 s (measured: each blocking step costs ~0.95 exploration steps, §3.11) | Linux kernel tracepoints; `[APE-STEP]` is the seed of this |
| Device-side file sink (journal) + host pull | Survives adb drop and SIGKILL (file flush); survives teardown truncation; but changes the collection contract (Python reads stdout today, `tool.py:1115-1120`) — a cross-repo change | A10's design note in §3.12 explicitly left a device-side sink out of scope — the open door |
| Structured JSON per line | The extreme; heaviest per-step cost; overkill for on-device 0-overhead telemetry | — |

### B6. How failure is contained

| Option | Trade-off | Reference |
|---|---|---|
| In-process step isolation (**today**) | INV-EXPL-16/29; no catch of `Error`; no OOM handling; resume impossible | `Monkey.java:773-800` |
| **In-process + journal (checkpointed state)** | Resume within the same process/task; needs the append-only log of B4; the timeout clock question (does it keep running across a restart?) must be answered with a trace marker | Fastbot2 model reload; DroidAgent `MAX_ACTIONS` reflect→new-task |
| External supervision (Python relaunches the jar) | Already half-present (task-level resume via `TaskStorage`); a restart is *still one sample* only if the trace says so; the Python layer must stop counting truncated runs as success (`tool.py:1121-1125`) | rv-platform `TaskStorage`; ARES episodes |
| Task-level retry (re-run a truncated task) | Cleanest statistically (a truncated sample is re-drawn); costs compute; needs "truncated" to be detectable (exit-code + trace markers) | — |

### B7. Where the experiment matrix is defined

| Option | Trade-off | Reference |
|---|---|---|
| Hand-written Python dicts (**today**) | T5; explicit but self-validated | `tool.py:427-659` |
| **Generated from a feature manifest** | Arms = named presets over a capability/pipeline model; arm count and power become the generator's explicit problem; preserves the 26 existing arms as named presets | C2 candidate; LLVM `-passes=` presets; pytest fixtures |
| Statistical design generator (fractional factorial / Plackett–Burman) | Directly addresses D9's power problem; overkill until the feature set is fixed | — |

---

## Part C — Three candidate architectures

### Candidate C1 — **StepPipeline** ("a step is a pipeline of named stages")

**Organising principle.** The explorer is one step loop, and a step is the execution of an *ordered, named, data-driven pipeline of stages*. Every subsystem — perception, guards, scoring, routing (LLM), launcher, component triggering, the SATA chain, input, telemetry — is a stage in that pipeline. A **mode is a named pipeline specification** (which stages, in which order, with which parameters). Precedence is data (the pipeline's order), not statement order. The existing `ScoringPass` idea is generalised from *scoring* to the *whole decision*, which is the direction the code has been drifting toward anyway (§A2.4).

This candidate's organising idea is: **the decision flow itself is the unit of architecture; everything else is a stage in it.** It is the natural heir to the 2026-07-08 scoring-pipeline decision, extended to routing and perception — but with the important difference that the pipeline is now *declared, named, validated and echoed*, not assembled once in `fromConfig`.

**Module/package structure.**

```
com.android.commands.monkey.ape.step
  StepContext            // per-step data carrier: state, tree, counters, mopData, llm, graph, rng
  StepStage              // interface: name(), applies(StepContext), run(StepContext) -> ModelAction|null
  StepPipeline           // ordered List<StepStage>; run() = for each stage, if non-null return
  StepSpec               // parse + validate a pipeline spec string ("base,coverage,llm:mop")
  StageRegistry          // name -> factory (startup registration, loud on unknown name)
com.android.commands.monkey.ape.stage
  perceive/  (refetch, foreignGuard, treePackageGuard, nullInfo)   // from MonkeySourceApe
  score/     (the existing 7 ScoringPasses, now parameterised)      // from ape/agent/scoring
  route/     (llmNewState, llmStagnation, llmRandom, launcher, componentTrigger)  // from SataAgent
  pick/      (sataBuffer, sataBack, sataEarlyFwd, sataTrivial, sataEarlyBack, sataEpsilon, sataNull)
  input/     (typedInput, heuristicInput, formCompletion)
  telemetry/ (stepEvent, outcomeEvent)                              // from StatefulAgent
```

The sketch:

```java
public interface StepStage {
    String name();
    /** Stages are stateless; retained state lives in named projections reachable from StepContext. */
    boolean applies(StepContext ctx);
    /** Non-null = this stage produced the decision (short-circuit). Null = pass. */
    ModelAction run(StepContext ctx);
}

public final class StepPipeline {
    private final List<StepStage> stages;
    public ModelAction run(StepContext ctx) {
        for (StepStage s : stages) {
            if (!s.applies(ctx)) continue;
            ModelAction decided = s.run(ctx);
            if (decided != null) return decided;
        }
        throw new BadStateException("No stage decided this step");
    }
}

// A mode is a named pipeline spec + parameter values.
public final class StepSpec {
    public static StepSpec parse(String spec) { /* "log,budget,llm:mop,launcher,sata:*" */ }
    public List<StepStage> toStages(StageRegistry reg) { /* throws on unknown name */ }
}
```

The assembly point replaces `SataAgent.selectNewActionNonnull`'s body: it becomes `StepPipeline.run(ctx)` where the pipeline was built once from `StepSpec` at agent construction and the stages are the extracted blocks. `StatefulAgent` keeps the perception/outcome/teardown; `MonkeySourceApe` keeps the event expansion; but the *guards* move into `perceive/` stages so the 93-LOC `generateEvents` (T2's cousin) shrinks to a loop over four guarded stages.

**How the five modes (and a sixth) are expressed.** A mode = a named `StepSpec` + parameter file. `ape` = `spec: log,sata:*` with all RV parameters at RV-off values (via a *generated* off-vector, not the kill-switch registry — see below). `aperv` = `log,budget,score:coverage,score:form,pick:sata:*,input:*`. `mop` = `aperv` + `score:mop*,route:launcher`. `llm` = `aperv` + `route:llm:*`. `llm_mop` = `mop` + `route:llm:*`. A sixth mode (`mop_act_frontier`) = `mop` + `score:mopFrontier,score:wtg` — expressed by *editing the spec string and its parameter values only*, no code. The spec string is the mode's identity; it is echoed at startup as `[APE-STEP-CONFIG] spec=…`.

**Answers to the §2.2 open questions, in this candidate's terms.**

- *Does `mop` include the `aperv` exploration features?* **Yes — by construction.** Modes are *stacked*: `mop` = the `aperv` spec plus MOP stages. This is the defensible default because (i) the contrast chain becomes monotone — `ape → aperv → mop → llm_mop` isolates exactly one mechanism per step, which is what the phase-2/p-3 comparison needs (the control is `ape`, and `mop` vs `aperv` isolates MOP-on-exploration, not MOP-on-bare-APE); and (ii) it matches the thesis's pre-declared design (§2.3), which compares *guided APE* against *unguided APE* — i.e. the best-performing tool with guidance added. A `mop = ape+MOP-only` arm would differ from `aperv` by *two* mechanisms and confound the ablation. The cost: the pure-MOP mechanism is never observed on a bare-APE base — but that is not a mode anyone wants to run, and within-run attribution (D9) recovers it if needed.
- *Widget-level vs frontier-level — separate modes or an axis?* **A parameterised family, not modes.** F9/F11/F12/F13 are all *scoring stages* over the same substrate; making each a mode would multiply the arm matrix by 2^n and burn statistical power for zero scientific gain. The stage parameters (`mopWeightDirect/Transitive/OpenMenu/Wtg`, `frontierBoostWeight`, `mopFrontierWeight`) are the axis; named presets (`mop_widget` vs `mop_act_frontier`) are convenience names over parameter values. This keeps arm count additive, not exponential.
- *`llm` fallback, and is mode two-dimensional?* **Yes, two-dimensional, and the fallback is the base.** In StepPipeline terms the LLM is a *routing stage* placed before the pick stages: when it returns null (declined / failed / circuit-broken), the pipeline simply falls through to the next stage — which is exactly today's behaviour (`acceptLlmResult` returning null → SATA chain). So `llm` is literally "the `aperv` spec with an LLM route stage prepended", and `llm_mop` is "the `mop` spec with it prepended". The fallback base is therefore a *spec parameter*, not a hardcoded choice; `llm-on-ape` (bare base) is expressible as `log,llm:*,sata:*` with RV-off parameter values, at zero new code.
- *Is "mode" the right primitive?* **No — the right primitive is the pipeline spec; "mode" is a named preset over it.** This candidate makes that explicit: there is no `Mode` enum; there is `StepSpec`. The five names are documentation and CLI sugar that map to specs. This dissolves the "mode is two-dimensional" tension rather than modelling it.

**Driver satisfaction.**

- **D1 (baseline fidelity):** The `ape` mode = `log,sata:*` + an RV-off parameter vector. To make this *defensible* (T4), the vector is *generated* from a single list of RV capabilities in the spec (replacing `rvForcedOffValues`/`rvUnsetKeys` with a data table), and the kill-switch's reflective-completeness test is kept as the guard that the list is exhaustive. The ~16 unflagged fixes: each is classified (crash-fix / correctness / behavioural) in a `docs` table; the *behavioural* two (`Naming.java:429`, `NamingFactory.java:280`) are accepted as "APE + documented bugfixes" — the position I recommend — and the *fidelity contract* is an **oracle equivalence test** (same app, same seed, compare the action sequence against a captured golden trace of upstream `8f51b99`, per `docs/20260708_arquitetura_separacao_aperv.md` §7.1's optional item). Fidelity is asserted by test, not by flag.
- **D2 (mode first-class):** Modes are `StepSpec` strings — declarable, discoverable (the registry can list them), validatable (unknown stage = loud error at startup, and the `--ape mop` silent fallback becomes a hard error naming the valid specs), loggable (`[APE-STEP-CONFIG] spec=…`), diff-able, testable.
- **D3 (feature management):** A stage is the unit of feature; dependencies/conflicts are expressed as *spec preconditions* (a stage's `applies()` and its `name()` are documented, and `StepSpec.parse` validates declared dependencies like "MOP stages require `mopDataPath` set"). This is weaker than C2's capability model but cheap and honest.
- **D4 (spaghetti):** `selectNewActionNonnull`'s 141 LOC collapse into a data structure. The 7-copy-paste pattern, the triplicated precondition, and the textual precedence all become pipeline order. Essential conditionals survive as stage preconditions (the budget fall-through, the guard fail-open) — they are not lost, they move.
- **D5 (testability):** Stages are stateless and take a `StepContext`; a stage test constructs a `StepContext` with stub projections and asserts the decision, on the JVM, no Android runtime. `StepPipeline.run` with a fixed pipeline + fixed seed is deterministic. The seams of T9 become unnecessary for the decision path.
- **D6 (memory):** C1 keeps the object graph but fixes T6's *ownership* half: each stage declares the projections it retains; the three unbounded roots get eviction policies (action history → bounded ring + offloaded journal, §D4; `treeTransitionHistory` → bounded; the never-cleared cache → `release()` on state eviction). Honest weakness: C1's memory story is "ownership rules", not a structural solution; it shares the D6 weakness of C3 below.
- **D7 (traceability):** The pipeline's single entry point becomes the natural single telemetry emission point: every stage logs its name, `StepContext.step()` and `clock()` once, so `[APE-STEP]`-equivalent data is produced for *all* modes including `ape` (fixing INV-ARCH-01: the baseline becomes observable without breaking parity, because the pipeline spec itself carries the parity). This is the strongest D7 feature of C1: observability becomes a stage, so it cannot be "the first thing turned off".
- **D8 (resilience):** C1 keeps today's isolation invariants and adds a journal (see §D4) so teardown only flushes; it does not by itself solve the Python-side truncation-counted-as-success defect — that fix lives in `tool.py:1121-1125` and is cross-repo regardless of candidate.
- **D9 (impact measurement):** `decision_source` is already per-step; with modes as specs, the counterfactual becomes cheap: a spec without a stage *is* the ablation arm. The per-run `[APE-STEP-CONFIG]` echo gives D9's "per-run effective configuration" for MOP the way the LLM already has it.
- **D10 (simplicity):** One new concept (stage), one new indirection (pipeline), and the *deletion* of the block-ladder. Complexity budget below.

**Migration path.** Stage 1 (no behaviour change): extract the 7-copy-paste SATA chain into pick stages behind a spec whose default equals today's order; characterise current priorities as goldens (`docs/20260708_arquitetura_separacao_aperv.md` §5.1). Stage 2: extract the LLM hooks and launcher/trigger into route stages; default spec reproduces today's precedence (LLM before launcher before SATA). Stage 3: move `generateEvents`' guards into perceive stages. Stage 4: replace the kill-switch registries with the generated off-vector + oracle test. Stage 5 (cross-repo): `tool.py` emits `ape.pipelineSpec` and the 26 arms become spec names; pytest guards are rewritten to validate spec names against the jar's `StageRegistry` (via a `--list-stages` dry-run). Blast radius: `SataAgent`/`StatefulAgent`/`MonkeySourceApe` restructured internally (behaviour preserved by goldens); `tool.py` mapping grows one key. Drift becomes loud (unknown stage = startup failure). Effort: medium — roughly 2–3 weeks for one maintainer, gated on goldens.

**Honest weakness.** C1 is the least ambitious and the closest to "the current design, but with a DSL" — the §8 failure-mode risk ("pipeline + registry + DSL") is real and must be fought by *subtraction* (the pipeline replaces code, it does not add a layer on top of it). It does not structurally solve memory (D6) or resume (D8); it inherits the object-graph serialisation risk. It leaves the Python/Java split-brain (T5) only half-fixed — the mode moves into Java but the arm matrix stays in Python, still hand-written. It would be the wrong choice if the owner concludes that D1 fidelity cannot be defended without a structurally separate pure artifact (that is C3's argument) or if the memory debts are judged worse than they look (that pulls toward C2).

**Complexity budget (D10).** New concepts: 1 (`StepStage`). New indirections: 1 (`StepPipeline`). LOC delta: net negative in the hotspots (−150 in `SataAgent`, −60 in `MonkeySourceApe`, +150 in `ape/step/`+`ape/stage/`). Newcomer's learning: read `StepSpec.parse` and the stage list; everything else is named and ordered in one place.

---

### Candidate C2 — **CapabilityMatrix** ("the system is a set of capabilities; a mode is an activation vector; the arm matrix is generated")

**Organising principle.** APE-RV is not a pipeline and not a program with modes — it is a **product line of capabilities** (MOP scoring, widget-vs-activity census, WTG, frontier, coverage, form, LLM routing, launcher, guards, input, telemetry), each with declared dependencies, conflicts, a default, and a package-scoped implementation. A **mode is a named activation vector over the capability set**, and the **arm matrix is generated** from the capability model rather than hand-written. The organising idea is **feature-space-first**: you decide what features exist and how they may combine, and everything else — dispatch, precedence, telemetry gating, baseline purity, ablation arms — falls out of that model. This is the product-line / feature-model answer to the owner's driver 4 ("manage features properly") and driver 3 ("a new mode should be cheap to add").

**Module/package structure.**

```
com.android.commands.monkey.ape.capability
  Capability             // name, requires():Set<Name>, conflicts():Set<Name>, defaultOn, package-scope
  CapabilityRegistry     // static: name -> Capability; register() at startup; loud on duplicate/unknown
  Mode                   // name -> Activation (Map<Capability,Value>); validate() against registry
  ModeManifest           // the shared declarative artifact (YAML/JSON) — shipped in the jar AND read by Python
  ActivationInjector     // applies an Activation to the run: builds ScoringPipeline, LlmRouter, guards, etc.
com.android.commands.monkey.ape.capability.imp
  mopWidget / mopActivityCensus / wtg / frontier / mopFrontier / coverage / form
  llmRoute / llmCircuitBreaker / launcher / componentTrigger
  foreignGuard / treePackageGuard / typedInput / heuristicInput
  telemetry / journal
```

The sketch:

```java
public final class Capability {
    public final String name;
    public final Set<String> requires;   // e.g. mopWidget requires { "mopData" }; menuGateway requires { "mopData", "modelMenu" }
    public final Set<String> conflicts;  // e.g. mopWidget conflicts { "mopActivityCensus" }?  (or orthogonal — model decides)
    public final boolean defaultOn;
    public final BiFunction<CapabilityContext,Value,Object> injector; // builds the pass/router/guard
}

// A mode is a named activation vector, validated at startup.
public final class Mode {
    public final String name;
    public final Map<String,Value> activation;   // capability -> value (on/off/weight/cap)
    public static Mode validate(ModeManifest m, String name) {
        Mode raw = m.modes().get(name);
        for (String c : raw.activation().keySet())
            if (!m.registry().contains(c)) throw new UnknownCapabilityException(c);  // loud, not silent
        // dependency closure: transitive requires must be satisfied by the activation
        for (String req : requiredClosure(raw)) if (!raw.activation().containsKey(req))
            throw new MissingDependencyException(req, name);
        return raw;
    }
}
```

The assembly point replaces both `ApeAgent.createAgent` (the if-chain becomes `Mode.validate(manifest, config.agentType).inject()`, with unknown names throwing instead of falling back to `SataAgent`) and `ScoringPipeline.fromConfig` (passes are built by the injector from the activation, and each pass receives its parameters **as arguments, not from static `Config`** — this is the fix for the decorative-`cfg` confession, `ScoringPipeline.java:48-49`).

**The shared declarative artifact.** A single `modes.yaml` (or JSON) is the single source of truth for *both* repositories: it ships inside the jar as a resource **and** is copied to the device next to `ape.properties` **and** is read by `tool.py` to generate arms. The Python side stops hand-writing `_BASELINE_ARM_FLAGS`/`_MOP_SUBSTRATE`/`_LLM_FLAGS` (`tool.py:240-336`): `get_variants()` iterates `modes.yaml`, and the 26 existing arms are re-expressed as named entries. Drift becomes loud on both sides (unknown capability = Java startup failure and pytest failure). A version field + checksum gives the §3.10 "versioned / checksummed / handshaken" answer.

**How the five modes (and a sixth) are expressed.** Five named entries in `modes.yaml`: `ape` = activation with RV capabilities off (defaultOn=false for all RV), `aperv` = exploration capabilities on, `mop` = `aperv` + MOP capabilities, `llm` = `aperv` + LLM, `llm_mop` = both. A sixth (`mop_act_frontier`) = `mop` + `{frontier: on, mopFrontier: weight=…}` — a one-line YAML edit, no code. Dependencies are machine-checked: `menuGateway.requires = {mopData, modelMenu}` encodes F10-needs-F2; `frontier` declared to conflict-with-or-require `mopData` — this candidate *forces* the F12 coupling question (is it MOP-agnostic or not?) to be answered in the manifest, where a test can enforce it.

**Answers to the §2.2 open questions, in this candidate's terms.**

- *Does `mop` include aperv exploration?* **Yes** — the manifest defines `mop` as `aperv` ∪ MOP capabilities; the monotone arm chain `ape ⊂ aperv ⊂ mop ⊂ llm_mop` is the *default preset policy* and the manifest states it explicitly so it is reviewable. (The manifest also *permits* the exotic `mop_on_ape` = `ape` ∪ MOP, but no preset defines it — power is protected by policy, not by code.)
- *Widget vs frontier.* **Orthogonal capability axes** (`mopWidget` and `mopActivityCensus` are the A′ axis; `wtg`/`frontier`/`mopFrontier` are the frontier axis), each independently toggleable and weightable. Mode-status is rejected (arm-count explosion); they are axes within the MOP capability family.
- *`llm` fallback / two-dimensional mode.* The LLM is a capability whose *effect* is "inject a route stage before the base pickers"; its fallback is the base explorer, which in the manifest is expressed as the LLM capability being *independent of the base set* — so `llm` can combine with any base. The manifest's capability graph is how two-dimensionality is expressed without a mode explosion.
- *Is "mode" the right primitive?* **No.** The primitive is the capability set; modes are named, validated presets over it. The five names are a documented convention, and the manifest is the only place where "a mode" exists.

**Driver satisfaction.**

- **D1:** The `ape` activation is generated from the capability model (capabilities have `defaultOn`, and `ape` = everything-RV-off is *derived*, not enumerated by hand) — the reflective-completeness property of the kill-switch is preserved structurally. The ~16 unflagged fixes still need the classify-accept-document decision + oracle test (same position as C1, D1). C2 does not make fidelity structural either; it makes the *off-vector* structural.
- **D2:** Modes are manifest entries: declarable, discoverable (the manifest lists them), validatable (on both sides), loggable (the jar echoes the validated activation as `[APE-MODE] name=… deps=…`), diff-able (YAML diff), testable. `--ape mop` becomes a valid manifest lookup; unknown names fail loudly.
- **D3:** This is C2's home turf: dependencies and conflicts are first-class manifest facts, machine-checked, with the compiler/test catching drift. Feature-vs-policy-vs-strategy-vs-aspect: a capability is the unit; policies are capabilities whose value is a policy object.
- **D4:** The spaghetti dies as in C1 (stages/picks are the implementations the injector builds), plus `ApeAgent.createAgent` and `ScoringPipeline.fromConfig` both become manifest-driven. C2 additionally kills the *other* if-chain: the "is this arm-defining?" pytest logic (INV-APV-14) becomes a manifest check.
- **D5:** Capability injectors are testable in isolation (a capability's unit test builds its injected object with stub projections); `Mode.validate` is pure logic over the manifest — trivially unit-testable, no Android runtime.
- **D6:** Each capability *declares its retained state and its release lifecycle* (the `Capability` descriptor gains `retainedRoots()` and `release(StepContext)`); ownership is a manifest-declared contract. The journal (§D4) is itself a capability, off by default, enabled by the arm that needs resume. This is the strongest structural memory story of the three, though it is declaration + discipline, not a new storage engine.
- **D7:** Telemetry is a capability; the step event is emitted by the telemetry capability's hook on every step for every mode (INV-ARCH-01 fixed as in C1). The `[APE-MODE]` echo and per-run effective-MOP-config echo (fixing the D7 gap that MOP lacks an `[APE-LLM-CONFIG]`-equivalent) are part of the manifest contract.
- **D8:** The journal capability + the Python-side fix (`tool.py:1121-1125` + truncation markers) — same D8 position as C1, plus C2's manifest can declare which arms are resume-enabled.
- **D9:** This is C2's second home turf: **arms are generated from the manifest**, so a new feature's ablation arm appears automatically; the "generated arm matrix from a feature manifest" that §3.10/D9 asks about is precisely this candidate. It also enables the statistical-design generator (B7) later. Power is preserved by keeping axes, not modes.
- **D10:** Costly on the abstraction axis: a manifest format, a registry, an injector, two validators. Mitigated because it *deletes* the hand-written dict-spreads and the two duplicated DSL parsers (`rv_experiment/__main__.py:163-249` and `configuration_factory.py:266-304`) and the kill-switch registries — real subtraction.

**Migration path.** Stage 1: extract the capability descriptor + registry as an empty shell; move `apePureMode`'s three registries into it (behaviour preserved). Stage 2: rewrite `ScoringPipeline.fromConfig` to build from activation (passes still read Config — the injector now *passes* them their parameters; the decorative-cfg problem fixed). Stage 3: `ApeAgent.createAgent` becomes manifest-driven; unknown `--ape` fails loudly. Stage 4 (cross-repo): `modes.yaml` lands; `tool.py`'s dict-spreads are re-expressed as manifest reads; pytest guards validate against the manifest; the 26 arms are frozen as named entries. Blast radius: `Config`/`ScoringPipeline`/`ApeAgent` in the jar; `tool.py` substantially rewritten (1,181 LOC of tests must follow); one new shared artifact. Effort: medium-high — 3–4 weeks, with the cross-repo YAML migration the risky part.

**Honest weakness.** C2's risk is the §8 failure mode: "plugin architecture with a registry" that outlives its usefulness. A manifest is only justified if the capability set is genuinely combinatorial — and much of APE-RV's coupling is *not* combinatorial (the SATA chain's precedence, the model's welded DecisionSource/ephemeral/ActionType, the ordering LLM-before-launcher) — so C2 must be disciplined about which facts go in the manifest (features) and which stay in code (precedence, model semantics). If the owner's real need is only "five named runs", C2 is over-engineered; if the real need is "a family of RQs answered from one campaign", C2's generated arms are exactly right. It would be the wrong choice if simplicity (D10) outranks the feature-management drivers.

**Complexity budget (D10).** New concepts: 3 (`Capability`, `Mode`/manifest, `ActivationInjector`). New indirections: 2 (registry, injector). LOC delta: roughly neutral in Java (−120 from createAgent/fromConfig/registries, +180 capability shell), negative in Python (dict-spreads → manifest reader). Newcomer's learning: the manifest schema + the two validators; the cost is the format, not the code.

---

### Candidate C3 — **TwoLineage** ("purity is a build-time property, not a configuration")

**Organising principle.** The only way to *guarantee* that `ape` is original APE is to build it from code that is original APE. This candidate reorganises the repository so that the mode is a **build-time selection of lineage**: a pristine, vendored, byte-identical upstream source tree (`8f51b99`) plus an RV overlay; `ape` = build from the pristine tree only, `aperv`/`mop`/`llm` = build from the overlay. The organising idea is **separation by construction rather than by configuration**: the RV code is *physically absent* from the pure artifact, so there is nothing to switch off. This is the "strict revert / second build artifact" option of D1, and the "dedicated agent" reading of the owner's driver 1, taken seriously.

This is the uncomfortable candidate: it is the most honest answer to D1 and the most expensive answer to everything else.

**Module/package structure.**

```
upstream/                     // vendored 8f51b99, byte-identical, git-verifiable (no edits, ever)
  src/com/android/commands/monkey/...   // MonkeySourceApe, ApeAgent, StatefulAgent, SataAgent, naming/, model/
overlay/                      // the RV delta + the seam
  seam/                       // the one place upstream is extended: a thin hook surface
  rv/                         // MOP, LLM, coverage, guards, input, telemetry (the fork's 32 new files)
build/                        // mode -> source set selection (maven profile or a pre-build script)
```

The seam is the crux and the whole difficulty. Today the fork *edits 22 upstream files in place* (SataAgent +739/−11, StatefulAgent +506/−9, MonkeySourceApe +174/−29, GUITreeNode +32/−5, etc.). A TwoLineage build cannot have those edits; every one must be re-homed either (a) into the overlay as an *extension seam* (the upstream class stays, the RV behaviour is added via the seam) or (b) accepted into a shared "bugfix layer" that both lineages build (see D1). The seam sketch:

```java
// upstream/…/SataAgent.java (byte-identical upstream):
protected Action selectNewActionNonnull() { … upstream chain … }
// No edit. The overlay hooks here:

// overlay/seam/RvHooks.java — the only seam, one static holder:
public final class RvHooks {
    public static ModelAction afterBudgetBeforeSata(Step ctx) { /* MOP/LLM/launcher via injected capabilities */ }
    // the seam interface is the *minimum* surface the upstream build leaves open
}
```

Which means the fork's in-place edits must be *re-diffed and re-homed* — the expensive operation. The oracle test (same app, same seed) then compares the pure lineage's output against the real upstream jar, and the overlay's output against today's `ape-rv.jar` behaviour, pinning both sides.

**How the five modes (and a sixth) are expressed.** Modes are build profiles: `-Pape` (upstream only), `-Paperv`, `-Pmop`, `-Pllm`, `-Pllm-mop`. A sixth mode = a new profile combining source sets. Runtime flags still exist for the *within-overlay* axes (LLM dose, weights), but the *baseline vs not* distinction is entirely build-time. `--ape` becomes a no-op/legacy alias because there is only one agent per artifact.

**Answers to the §2.2 open questions, in this candidate's terms.**

- *Does `mop` include aperv exploration?* **Yes**, and the answer is *physical*: `mop` builds the overlay, which *is* aperv + MOP. The monotone chain is a property of the overlay layering (aperv ⊂ mop ⊂ llm_mop), not a flag policy. This is the strongest possible version of the "stacked" answer.
- *Widget vs frontier.* Same answer as C1/C2 (parameterised family inside the overlay) — C3 has nothing new to say here, which is itself a weakness (it buys D1 at the cost of not improving feature management).
- *`llm` fallback.* The LLM is overlay code; its fallback is whatever the overlay's base pickers do — again the base explorer. Two-dimensionality is expressed by which overlay modules build in.
- *Is "mode" the right primitive?* **Here, mode-as-build-profile is almost right but still not the primitive**: the primitive is the lineage (upstream vs overlay), and "mode" is the build selection. But note this candidate *cannot* express `mop_on_ape` (pure base + MOP) without adding the MOP modules to the upstream lineage — which would defile it. That inexpressibility is a feature (protects the chain) and a limitation (some exotic arms are impossible).

**Driver satisfaction.**

- **D1 (baseline fidelity):** The strongest possible answer — `ape` is built from byte-identical upstream code, so there is nothing to "restore". Combined with the oracle equivalence test, the thesis's control arm is defensible to a reviewer who checks the diff. **This is the only candidate for which D1 is structural rather than asserted.**
- **D2:** Weakest of the three: "mode" is a build profile, not a runtime concept; discoverability/validatability/diff-ability exist at the build level but there is no runtime representation, and the Python layer still defines arms as property sets on top of whatever jar it was given.
- **D3:** Feature management is unchanged inside the overlay (still flags + capabilities); the gain is only at the baseline boundary.
- **D4:** The spaghetti *within* the overlay is untouched — `selectNewActionNonnull` still exists in the overlay's SataAgent (which is now the fork's SataAgent). C3 solves D1, not D4. (This is the honest failure mode of a D1-first strategy.)
- **D5:** The seam makes the overlay testable against the upstream build as an oracle — a *strong* testability win for parity tests; unit testability of the decision path is unchanged (still needs the work of C1).
- **D6/D7/D8:** Unchanged from today unless the overlay also adopts C1's stage extraction and the journal. C3 contributes nothing to memory, observability, or resilience by itself.
- **D9:** Arms are still property sets in Python; no structural improvement to impact measurement (though the two-artifact story makes the baseline-vs-treated contrast cleaner at the *build* level).
- **D10:** The worst of the three: two source trees to keep coherent, a seam to maintain, and a re-homing of 22 edited files that is essentially a careful re-architecture anyway. The "simplicity" the owner asked for is *not* two lineages.

**Migration path.** This is not stageable cheaply. Stages: (1) vendor upstream `8f51b99` byte-identical into `upstream/` with a git-verify hook; (2) characterise the fork's 22 edited files as a diff and classify every hunk (RV behaviour vs bugfix); (3) build the overlay by *re-applying* the RV hunks against the seam instead of editing upstream — the hard part, roughly a re-implementation of the RV integration against a cleaner boundary; (4) the pure build becomes a Maven profile; (5) the oracle test pins both lineages. Blast radius: the whole Java side; `tool.py` mostly unchanged (same jars, different provenance). Effort: the highest of the three — 6+ weeks for one maintainer, with the seam design the make-or-break. **Comparability with collected data:** the overlay lineage must reproduce today's `ape-rv.jar` behaviour on the goldens before the old jar can be retired, otherwise every past result is orphaned.

**Honest weakness.** C3 is bad at D2/D3/D4/D7/D9/D10 — it buys D1 at the cost of everything else, and the re-homing effort is a de-facto rewrite that the §8 failure modes explicitly warn about ("recommending rewrite from scratch without a staged path"). Its own document is the case study in how a design can be faithful to itself yet aimed at the wrong site (`docs/20260717_analise_terminador_refinement_crash_recovery.md`). It is only the right choice if the oracle-equivalence path of C1/C2 *demonstrably fails* — i.e. if the ~16 unflagged fixes turn out to include behaviour that a thesis committee would judge to break the "untouched APE" claim, and no amount of documentation and testing satisfies that. It should be the contingency, not the plan.

**Complexity budget (D10).** New concepts: 2 (`lineage`, `seam`). New indirections: 1 (the seam holder). LOC delta: negative in the *long* run (deletes the kill-switch entirely, deletes the in-place edits) but the re-homing is a large one-time cost. Newcomer's learning: understand which lineage is authoritative for a given feature, and the seam contract — the steepest of the three.

---

## Part D — Comparison and recommendation

### Scoring table (weights are the owner's to change; I weight D1 and D7/D9 highest because this is a thesis instrument, and D10 because the owner asked for it explicitly)

| Driver | Weight | C1 StepPipeline | C2 CapabilityMatrix | C3 TwoLineage |
|---|---|---|---|---|
| D1 baseline fidelity | 0.15 | 3 (asserted via oracle test) | 3 (off-vector structural, fidelity asserted) | **5** (structural) |
| D2 mode first-class | 0.12 | **5** (spec = mode) | **5** (manifest = mode) | 2 (build profile) |
| D3 feature management | 0.10 | 3 (spec preconditions) | **5** (manifest deps/conflicts) | 2 (unchanged) |
| D4 spaghetti | 0.10 | **5** (pipeline replaces ladder) | 4 (pipeline + injector) | 2 (untouched) |
| D5 testability | 0.08 | **4** (stateless stages) | **4** (pure Mode.validate) | 3 (oracle-only) |
| D6 memory | 0.10 | 3 (ownership rules) | **4** (capability-declared + journal) | 2 (unchanged) |
| D7 traceability | 0.13 | **5** (telemetry = stage, all modes) | **4** (telemetry capability + mode echo) | 2 (unchanged) |
| D8 resilience | 0.10 | 3 (journal + Python fix) | 4 (journal as capability + Python fix) | 2 (unchanged) |
| D9 impact measurement | 0.10 | 4 (spec-less = ablation) | **5** (generated arms) | 2 (unchanged) |
| D10 simplicity | 0.02 | **5** (1 concept) | 2 (manifest machinery) | 1 (two lineages) |
| **Weighted total** | 1.00 | **3.92** | **4.13** | 2.51 |

(Weights sum to 1.00. C1: 0.15·3 + 0.12·5 + 0.10·3 + 0.10·5 + 0.08·4 + 0.10·3 + 0.13·5 + 0.10·3 + 0.10·4 + 0.02·5 = 3.92. C2: 0.15·3 + 0.12·5 + 0.10·5 + 0.10·4 + 0.08·4 + 0.10·4 + 0.13·4 + 0.10·4 + 0.10·5 + 0.02·2 = 4.13. C3: 0.15·5 + 0.12·2 + 0.10·2 + 0.10·2 + 0.08·3 + 0.10·2 + 0.13·2 + 0.10·2 + 0.10·2 + 0.02·1 = 2.51.)

C2 edges C1 by a small margin, driven by D3/D6/D9 (feature management, memory-as-declared, generated arms). C3 wins D1 alone and loses everything else.

### Recommendation

**Adopt C1's StepPipeline as the backbone, and adopt C2's manifest as the arm-definition layer on top of it — the two are complementary, not rival.** C1 fixes the decision flow (D2/D4/D7) with minimal new machinery; C2's *manifest* is then exactly the right home for "what combinations are arms" (D3/D9), because it sits above the pipeline and can be introduced incrementally without the full C2 injector. **Do not choose C3 now** — but keep its oracle equivalence test as a mandatory gate of the C1/C2 fidelity story, and treat full TwoLineage as the contingency if that test exposes unfixable behavioural drift.

Concretely, the decomposition into independently valuable steps:

1. **Stage A (both): goldens + oracle.** Capture today's `ape-rv.jar` action sequences (seeded, cryptoapp + a few dataset apps) and upstream `8f51b99`'s. This is the safety net for everything else and the D1 acceptance test. Independent of all architecture.
2. **Stage B (C1): extract the SATA chain and the route blocks into a pipeline** with a spec whose default reproduces today's precedence exactly. Deletes the 7-copy-paste and the triplicated precondition. Independently valuable (D4).
3. **Stage C (C1): telemetry becomes a stage.** `[APE-STEP]`-equivalent data for *all* modes (fixes INV-ARCH-01 the right way — observability is not the first thing the baseline turns off). Independently valuable (D7) and immediately useful to the in-flight `telemetry-proof-llm-efficacy` verification (task 17.4).
4. **Stage D (C2-lite): a `modes.yaml` manifest** listing the five presets + the existing 26 arms, read by the jar (validated at startup, unknown → hard error) and by `tool.py` (arms generated from it, pytest guards rewritten). Fixes T5's silent drift. Independently valuable (D2/D3/D9) and cheap because the pipeline from Stage B gives the manifest something concrete to name.
5. **Stage E (shared, cross-repo): the journal + the truncation fix.** Append-only action/step journal on device (write per step, flush at teardown, within the +15 s grace), so resume becomes possible and teardown truncation stops losing data; and fix `tool.py:1121-1125` so a truncated run is *not* recorded as success (add trace markers: run-start/run-end/grace-begin). This is the D8/D6 half, mostly Python + a small jar sink.
6. **Stage F (D7's thesis-blocking gap, cross-repo): the violation↔step join.** The jar already emits `clock=` per step; add a per-run `run_id` and a `[APE-CONFIG]` echo of effective MOP weights/paths (the MOP equivalent of `[APE-LLM-CONFIG]`), and implement the *analysis-side* join in the Python result pipeline: map each RVSEC violation's logcat timestamp to the nearest preceding `clock=` step and emit `first_seen_step` per dedup key. No APE↔logcat coupling (the hard invariant from `docs/20260702_roadmap_mop_fairtest_changes.md` §4); pure wall-clock join on the timestamps already emitted.

This staged path keeps the system runnable and comparable at every step (goldens gate each stage), and it lets the owner adopt *only* the stages that earn their keep — e.g. stop after Stage C if the mode problem was the only pain.

---

## Part E — Risks, unknowns, and next steps

### What I could not determine from the code and would need to measure

1. **Heap high-water mark over a 600 s run.** The relative share of `Model.actionHistory` vs `treeTransitionHistory` vs the naming caches in a real OOM is not measured anywhere (only `printMemoryUsage` at `ApeAgent.java:448-454` exists, observational). A 600 s instrumented run with per-phase heap samples would tell us which of T6's three roots to fix first — D6's cost model depends on it.
2. **Per-step telemetry cost.** The event-sink/journal writes are unmeasured; the D6/D7 trade (0.037–0.052 pp of `cov_mop` per step lost) needs a per-step µs budget before committing to a per-step journal.
3. **The real distribution of run terminations.** The truncation rate (~45% in one LLM-arm campaign) and the *global* distribution of "completed" tasks with partial traces across the 21,681-task grid are not consolidated; D8's Python fix needs this baseline.
4. **Whether the 16 unflagged fixes are behaviourally significant on real apps.** The oracle test will answer this, but it does not exist yet; until it runs, the D1 position ("APE + documented bugfixes") is a bet.

### Cheap experiments that would discriminate before committing

1. **Oracle equivalence test** (upstream `8f51b99` jar vs today's `--ape ape_pure`, same app + seed): decides D1's comfort level and whether C3 is ever needed. ~1 day.
2. **Heap sampling** on a 600 s run with the three roots instrumented: decides D6 priorities (which root is the killer). ~0.5 day.
3. **A "Stage C" pilot** — make telemetry a stage and emit `[APE-STEP]` in the pure arm for one run: verifies the INV-ARCH-01 fix is behaviour-preserving (compare action sequence with telemetry off vs on under the same seed). ~0.5 day.
4. **A manifest prototype** — write `modes.yaml` by hand for the existing 26 arms, no code: measures whether the capability/feature model actually *fits* (do the 26 arms decompose cleanly?) before any Java changes. ~0.5 day, and it may sink C2 cheaply.

### Questions needing the owner's decision (answerable in one line each)

1. Is the baseline "APE + documented bugfixes" (with the oracle test as the defence) acceptable, or must `ape` be byte-identical upstream (→ C3)?
2. Is the monotone stacked family `ape ⊂ aperv ⊂ mop ⊂ llm_mop` the preset policy you want, or do you need a `mop_on_ape` arm?
3. May the jar write a device-side telemetry journal (a real file), or must stdout remain the only sink (the §3.12 A10 open door)?
4. Do you want arms *generated* from a manifest (C2) or hand-written presets validated against it (C1-lite)?
5. For D9, is a within-run `decision_source` attribution (plus the counterfactual already built) a *credible* impact estimate, or must phase 3 pre-declare a statistical ablation design (Plackett–Burman) that the manifest must feed?
6. What is the acceptable per-run storage budget for phase 3 (the 3.5 GB/880-task extrapolation is D6/D7's real constraint)?

### What this analysis might be wrong about, and what evidence would change its conclusion

- **The causal thesis (T1–T5 are one problem).** If the oracle test or a manifest prototype shows the mode problem is *not* the dominant pain — e.g. if D6's heap sampling shows OOM is the real killer — then the ordering should invert (journal first, pipeline second). The heap experiment is the discriminating evidence.
- **C2's fit.** I assume the 26 arms decompose cleanly onto a capability model; the manifest prototype (cheap experiment 4) could falsify this, and if it does, C1-with-hand-written-presets becomes the recommendation.
- **The "stacked modes" default.** I argue `mop` ⊇ aperv exploration; if the owner's ablation design instead requires isolating the MOP mechanism on a bare base, the preset policy changes (and the architecture accommodates it either way — only the manifest/preset defaults change, no code).
- **C3's cost.** I estimated 6+ weeks for re-homing 22 files; if the diff turns out smaller than the dossier implies (the fork claims "0 files removed, 22 modified", but two were *replaced*), the TwoLineage cost could be materially lower — worth a weekend diff-audit before dismissing it.
- **Per-step overhead assumptions.** Every proposal that adds per-step work (journal, telemetry-as-stage) is charged against the measured ~0.95 steps per blocking call; if the per-step non-LLM cost turns out negligible in the pilot, the D6/D7 budget opens up more than I assumed.
