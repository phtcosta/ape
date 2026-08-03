# Preliminary architecture study: re-architecting APE-RV

- **Model:** opencode/laguna-s-2.1-free (report authored for the analysis requested by `docs/20260801_prompt_rearquitetura_aperv.md`)
- **Date:** 2026-08-01
- **Commit analysed:** `5dcf225976b26ce78d8b31dd88d7f858dad29d43` (`git rev-parse HEAD`, branch `master`)
- **Method:** 5 focused verification subagents + direct spot-checks of the evidence dossier (§3–§10), the four §4 documents, the thesis (`doutorado-tese`), the phase-2 paper (`ase-journal`), and the Python consumer (`rv-android/.../tool.py`). I read the full dossier, the four §4 documents, and the primary sources myself and verified every claim the argument leans on. **Where the dossier's line numbers have drifted from HEAD I cite the verified current lines and flag the drift.** Two dossier claims are refuted in place (§A3-T3 the duplicated-stagnation-predicate; §A4 the off-screen-action line citation); one dossier *count* is wrong (§A2 the Config census is **112**, not 117). The only prior pass at this exact brief is `docs/analise_deepseek-v4.md`; my three candidates below are deliberately organised on **different principles** (log-is-truth vs policy-exits-jar vs process-is-disposable), not on renamed versions of pipeline/capability/two-lineage.

---

## Part A — Diagnosis

### A1. What APE-RV is today, structurally

The aspirational layering (Monkey → Agent → Model) is a fiction. The real layering is six strata, of which only four are named and one is external:

1. **The AOSP shell.** `com.android.commands.monkey.Monkey` (`Monkey.java`) enters via `app_process`, owns arg parsing (`Monkey.java:921-931`), the crash/ANR controller (`Monkey.java:274-386` base, `:388-498` APE override), the stop condition (`Monkey.java:1291-1302` — one check per loop iteration, **non-preemptive**: a 15 s LLM call overshoots the deadline), and the isolated teardown `finally` (`Monkey.java:777-799`, `catch (Throwable)` at `:785` and `:795`). This is the best-engineered part of the system (INV-EXPL-16/29).
2. **The production loop.** `MonkeySourceApe.generateEvents` (`MonkeySourceApe.java:822-914`, 93 LOC, brace-depth 8) is a perception-and-dispatch funnel: refetch loop, foreign-activity guard (`:847-864`), tree-package guard (`:866-883`, documented fail-open `continue`), null-info fallback (`:890-899`), then a single `updateState` that yields one `Action`, expanded into a batch of low-level `MonkeyEvent`s.
3. **The agent.** `ApeAgent → StatefulAgent → SataAgent/RandomAgent/ReplayAgent`. `StatefulAgent` (1904 LOC, 111 methods, 166 `if`s) is where RV was bolted: MOP load (`:185-187`, fail-fast on bad `mopDataPath`), LLM router construction (`:194`), the scoring pipeline (`:208`), three telemetry channels, and the 9-step isolated teardown (`:1802-1814`, `safeStep` at `:1793-1800`). `SataAgent.selectNewActionNonnull` (`SataAgent.java:449-589`, **141 LOC**) is the decision epicentre.
4. **The scoring pipeline.** `ape/agent/scoring/` — 7 passes in fixed order (`MopWidget → MenuGateway → WTG → Frontier → MopFrontier → Coverage → FormCompletion`), assembled once at `ScoringPipeline.fromConfig` (`ScoringPipeline.java:51-61`), applied at the single injection point `StatefulAgent.adjustActionsByGUITree` (`:1631`). This is the one genuinely well-scoped RV seam.
5. **The model.** `ape/model/` + `ape/naming/`, *the upstream core whose data model the fork welded RV concepts into.* Three welds, none reversible by configuration (`ActionType.java:36-48,70-73`): `EVENT_TRIGGER_ACTIVITY` / `MODEL_MENU` / `MODEL_LLM_TAP` inserted into the enum (shifting `MODEL_BACK` 7→8, `MODEL_CLICK` 8→11), `requireTarget()`/`isModelAction()` are ordinal-range checks, and `Action.isEphemeral()` returns `MODEL_LLM_TAP` (`Action.java:135-136`) consulted at **five ungated** sites (`Model.java:315,384,423`; `Graph.java:452,590`) — so "ephemeral edge" is now graph semantics, not a toggle. `ModelAction` carries `DecisionSource` (11 values, `ModelAction.java:42-44`) + `PickChannel` (8 values, `:57-77`) + **6** boost fields (`:98-107`) as permanent object state — only their *emission* to the log is gated.
6. **The de-facto mode system.** It does not live in the jar. It lives in Python: `rvsec/rv-android/modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py` — 52 key-pair mappings (`:75-162`), 18 arm-defining keys (`:171-192`), composable dict-spread blocks (`:240-336`), 26 arms in `get_variants()` (`:453-659`), all guarded by 83 pytest functions.

The fork's complexity is overwhelmingly additive and concentrated: against upstream `8f51b99`, 0 files removed / 22 upstream files modified / 32 new main files (`docs/20260708_arquitetura_separacao_aperv.md:9-10`), and the additions land in exactly the decision path (`SataAgent`), the lifecycle path (`StatefulAgent`), the dispatch path (`MonkeySourceApe`), the LLM path (`LlmRouter`), and the configuration surface (`Config`). The spaghetti is **not inherited** — upstream APE's own `SataAgent.selectNewAction` was a clean chain of `selectNewActionX()` calls; the fork's `selectNewActionNonnull` is a **rewrite** (verified: 141 LOC, 7 copy-paste blocks, 3 LLM hooks, 1 launcher, 1 side-effect trigger, no flags).

### A2. The mode/feature control surface and why it is where it is

Two orthogonal, unconnected axes, verified:

- **The CLI axis.** `--ape <type>` → `Config.get("ape.agentType")` → `ApeAgent.createAgent` (`ApeAgent.java:68-96`), a chain of four `if`s over a raw `String`: `null`/`"sata"` → `SataAgent` (`:78-83`), `"random"` → `RandomAgent` (`:84-86`), `"replay"` → `ReplayAgent` (`:87-93`, `System.exit(1)` at `:91` when the log is missing), anything else → **silent `SataAgent` fallback** (`:95`, no log). `--ape mop` and `--ape llm` today run plain SATA with no log. The usage text advertises two types (`Monkey.java:1598`); the factory accepts three; nothing validates.
- **The property axis.** **112** `public static` fields (107 `final` + 5 non-final), loaded once in a static block (`Config.java:32-44`) that runs *before* the first field initializer (first field at line 49), from `System.getProperties()` + `/data/local/tmp/ape.properties` + `/sdcard/ape.properties`. Unknown keys are silently ignored; malformed numerics are swallowed by **empty `catch {}` blocks** (`getInteger` `:453-454`, `getLong` `:465-466`, `getDouble` `:477-478`) with no log. The `apePureMode` kill-switch is a pre-initialiser Properties overwrite (`Config.java:41-43`) driving three string-literal registries: `rvForcedOffValues()` (27 keys, `:343-364`), `rvUnsetKeys()` (2 keys, `:366-370`), `rvExemptReasons()` (21 keys, `:372-398`).

> **DRIFT vs. dossier §3.2:** the dossier states "112 public static final + 5 ad-hoc keys = 117". Verified count is **107 final + 5 non-final = 112 total**. The dossier double-counts the 5 non-final fields as both `final` and `ad-hoc`, over-counting by 5. (`ApePureModeKillSwitchTest.everyConfigFieldIsClassified:107-132` reflects over **all** public-static fields and would pass with 112; the test's correctness is unaffected, only the census in §3.2 is wrong.)

**Why it is where it is — the historical argument.** This is a fork that grew by accretion under experimental deadlines, and each piece has a reason:

1. The fork inherited APE's frozen `--ape` CLI and kept it stable because `rv-platform`'s launch contract (`adb shell app_process … Monkey -p <pkg> --running-minutes N --ape sata`) was frozen early (subsystem-rv-experiment.md:497), and any CLI change is a cross-repo change. New behaviour entered as **flags**, not CLI args.
2. Each experiment arm needed a property combination; the natural home for "arm definitions" was the Python layer that already computed the task matrix — so the mode concept colonised `tool.py`, not Java. **The mode concept was never given a home in Java.**
3. The 2026-07-08 design (`docs/20260708_arquitetura_separacao_aperv.md`) addressed *scoring only*: it extracted the inline scoring blocks into the `ScoringPass` pipeline and added a kill-switch, and explicitly rejected flags-only and an RV-agent-by-inheritance. It was correct for its scope; routing/precedence, perception, the input chain, object lifetime, the log, and the process lifecycle were left where they were. The consequence is in `§3.12`: the sixteen `telemetry-proof-llm-efficacy` items landed with **zero new Config flags** (`git diff Config.java` is empty across the 18 commits) and were absorbed into `selectNewActionNonnull` (141 LOC) and `LlmRouter.selectAction` (327-612, 385 LOC) — "the two worst methods absorb new behaviour, as they always do."
4. Each new mechanism was bolted in at the point where a `return` could be inserted, because that was the only extension point that existed. Precedence became statement order.

So the surface is a *sediment*: CLI axis (inherited), property axis (grown), Python arm axis (colonised). The 26 arms and the 112 flags have no representation in code; they are prose in comments (`Config.java:164,216,239,243,270,308`) and dicts in `tool.py`.

### A3. The architectural tensions (eight), each traced to a driver

**T1 — Mode has no home in Java.** Grep of `src/main/java` for `Mode`, `Arm`, `Variant` (as types, not strings) returns nothing. The only "mode" is the telemetry label String in `LlmRouter.selectAction:332` (`new-state`/`stagnation`/`random` from `SataAgent.java:483,501,511`), which *selects nothing* — it is a label written after the fact. The five proposed modes of §2.2 are undefined as software. → **Driver D2.**

**T2 — Precedence of independent subsystems is encoded as statement order in one 141-LOC method.** `SataAgent.selectNewActionNonnull` (`SataAgent.java:449-589`) is a linear sequence of flag-guarded blocks, each with its own `return` — verified by the subagent. Order: logging block (`:450-462`) → activity-budget gate (`:468-477`, documented fall-through) → **LLM new-state** (`:480-487`) → **LLM stagnation** (`:493-506`) → **LLM random** (`:508-515`) → **MOP launcher** (`:522-545`) → **component trigger** (`:546-551`, side-effect, no return) → the SATA chain — the pattern `resolved = selectNewActionX(); if (resolved != null) { logActionSelected(resolved, TYPE); return resolved; }` copy-pasted **7 times** (`:553-587`), then `throw new BadStateException` (`:588`). The triplicated precondition `actionBufferSize() == 0 && newState.getActions().size() > 2 && _llmRouter != null` is repeated at `:480-481`, `:493-494`, `:508-509`. → **Driver D4** (this is the "espaguetizado" the owner means).

**T3 — The "when to use the LLM" decision is split across classes — and a dossier claim is wrong.** The *predicates* are single-sourced in `LlmRouter`: `shouldRouteNewState` (`:232-237`), `shouldRouteStagnation` (`:249-255`), `shouldRouteRandom` (`:276-281`), all of shape `... && Config.<flag> && breakerAllows()`; the shared midpoint helper `stagnationMidpointReached` (`:267-270`) is `return !firedThisEpisode && graphStableCounter >= threshold / 2;` — **single source, `>=`, not exact equality**. **The dossier's §3.12 claim ("the `threshold/2` condition is duplicated at `SataAgent.java:436` and `LlmRouter.java:225`") is refuted at HEAD**: grep for `threshold / 2` across `src/main` returns exactly two hits, both in `LlmRouter` — one is a javadoc at `:261`, the actual code is at `:269`; there is **no** such condition in `SataAgent` (line 436 is the budget-exhaustion fallback). The subagent confirms the line citations "436" and "225" are stale; the real call site is `SataAgent.java:495`. What *is* split across three classes is the **state and its consumption**: the single-shot flag `stagnationHookFired` is declared in `StatefulAgent.java:128`, re-armed in `StatefulAgent.java:1436` (on a new edge), and burned in `SataAgent.java:499`; the counter `graphStableCounter` is mutated in `StatefulAgent.onVisitStateTransition` (`:1439`). So the sharpening is: the *decision logic* is centralised, but the *state it reads is owned by three classes*, and the three-LLM-hooks ordering plus the launcher ordering still lives in SataAgent's textual order. → **Drivers D4, D7.**

**T4 — Baseline fidelity is a flag, and the flag is a near-miss that understates its own drift.** `apePureMode=true` produces a *near*-pure APE. The kill-switch is *declaratively* complete — `ApePureModeKillSwitchTest.everyConfigFieldIsClassified:107-132` reflects over every `public static` field and forces any unclassified failure — but it inspects **field classification, not code-path behaviour**, so it cannot see code-level divergence. The subagent verified **at least seventeen** fork changes with no flag survive it, of which **three are genuinely behavioural** (the D1-critical ones) and the rest are crash/correctness or infra:

| Site | Behaviour | D1-relevant? |
|---|---|---|
| `RandomHelper.java:25-35` + `Monkey.java:731` | seedable `Random` replacing `ThreadLocalRandom` | infra (seed reproducibility — arm-neutral) |
| `Naming.java:421-432` | `containsNamelet` binarySearch fix (`== -1` → `>= 0`) | **YES — alters abstraction** |
| `NamingFactory.java:280,1180` | `an.getStates()` → `state.getGUITrees()` (per-tree refinement gate) | **YES — alters refinement condition** |
| `GUITree.java:281-296` | `indexOfName` binarySearch normalization | correctness |
| `Graph.java:619-621` / `:1316-1321` | two visit-count fixes | correctness |
| `Model.java:104-114` | action-history `RuntimeException` tolerance (INV-MODEL-15) | correctness/resilience |
| `StringCache.java:108-118` | empty-list fallback | input gen |
| `ApeFuzzer.java:178` | `6 + (count << 1)` precedence fix | correctness |
| `ApeFuzzer.java:197` | pinch/zoom dispatch (upstream computed the array and never enqueued it) | **YES — pure arm now executes pinch/zoom** |
| `ApePinchOrZoomEvent.java:41-46` | `points.length < 6` guard | correctness |
| `Monkey.java:778-801` | teardown restructured into isolated try/catch | correctness/resilience |
| `GUITreeNode.java:586-603` | DOM-prune rewrite (`clearChildren`) | correctness |
| `MonkeySourceApe.java:403-410,450-456` | off-screen action drop | correctness |
| `MonkeySourceApe.java:1317-1333` | `waitForActivity` cycle-cap relaunch | correctness |
| `AndroidDevice.java:436-443` | `broadcastIntent` catch broadening | correctness |
| `GUITreeBuilder.java:640` | `setIsPassword` population | correctness |

> **DRIFT vs. deepseek `analise_deepseek-v4.md:53`:** deepseek cites `MonkeySourceApe.java:144-152` for the off-screen action drop. At HEAD `:144-152` is the `UiAutomation`/`HandlerThread` init — the drop actually lives in `generateClickEventAt` (`:403-410, 450-456`). The substance (it exists, ungated) holds; only the citation drifted. `ApePureModeAlwaysOnExceptionsTest.java:11-24` admits **only 2** of these (pinch sizing + seed handling); the remaining 15 are **not** asserted against by any test. This is the precise shape of T4: the kill-switch's reflective completeness is satisfied while behavioural divergence survives because it is *code*, not *config*.

**T5 — The arm layer lives in Python with no contract; drift fails silently.** Verified in full by the subagent. The split-brain is precise: Python owns *which named combinations exist* (26 arms), key translation (52 pairs, of which `mop_weight_activity` is dead — `tool.py:95` maps to a key deleted from `Config.java`), value serialisation, and the hard timeout; Java owns *flag semantics*, defaults, clamps, and the kill-switch list. Drift fails silently and in two directions simultaneously: (a) `ape.*` renames/deletions are ignored by `Config` while all 83 pytest guards stay green (the gh71 precedent — a stale jar made the MOP boost fire in 0 of 147,153 evaluations); (b) the kill-switch is duplicated — `_APE_PURE_ARM_FLAGS` (18 keys, `tool.py:264-283`) vs `Config.rvForcedOffValues` (27 keys) — and the two **disagree on 9 keys** Java forces but Python omits (`coverageBoostWeight`, `componentPercentage`, `mopWeight*`, `mopTargetPickCap`, `activityStableRestartThreshold`, `llmOnNewState`, `llmOnStagnation`) plus 1 key Python sets but Java exempts (`llm_percentage_no_substrate=-1`). VALUES cross the DSL as untyped strings (`rv_experiment/__main__.py:214`) so `@heuristic_input=False` writes the literal `False`; `_push_properties` (`:907-908`) converts Python `bool` but not string `"False"`. No retry/restart in the plugin; a missing static-analysis JSON is only a warning and the arm silently runs as SATA (`tool.py:1092-1096`); a non-zero exit is only `logger.debug`-ed (`tool.py:1121-1125`) — so a jar OOM at second 120 of a 600 s budget is recorded as a **completed** task (the truncation bug, `docs/20260716_investigacao_truncamento_600s_llm_tap.md:305`; ~45% of one LLM-arm campaign truncated this way). → **Drivers D2, D8, D9.**

**T6 — Nobody owns a GUITree, so memory is unbounded and pressure is unhandled.** Verified: three *independent* unbounded retention roots (fixing one is insufficient). (i) `Model.actionHistory` (`Model.java:136-137`, the code's own `// TODO: may be the cause of OOM`) — one `ActionRecord` per step forever (`Model.java:170`), each strong-referencing `Action` + `GUITreeAction` → `GUITree` → `GUITreeNode` subtree, and via `GUITree.currentState` (`GUITree.java:71`) transitively the whole `State`; the history **keeps alive every tree ever visited, including states already removed from the graph**. (ii) `Graph.treeTransitionHistory` (`Graph.java:117-118`) + `entryGUITrees`/`cleanEntryGUITrees` (`:107-110`) — one entry per transition / one `GUITree` per restart (restarts fire every 100–300 steps, `Config.java:73-74`). (iii) The **never-cleared static** `GUITreeBuilder.namingToGUITreeNodeCache` (`:693`; the caches are at `:670-671,693`) — `release()` (`:707-715`) clears the other two caches but **not** this one, so every `GUITreeNode` ever named under a non-current naming is pinned forever (verified: the only writer is the hot `getNodeName` at `:699`). Stacked with eternal interners `NameManager.names`/`nameList` (`NameManager.java:27-28`), `StringCache.stringDict` (`StringCache.java:32`), and `AbstractNamingManager.treeToNaming` (`AbstractNamingManager.java:43`, shallow-copied on `clone()` at `:103` which **does** have a live caller at `StateNamingManager.java:72`). `maxStatesPerActivity=10`/`maxGUITreesPerState=20` (`Config.java:124-125`) are **not** memory caps — they only suppress refinement (`NamingFactory.java:276-283,1176-1183`); nothing frees a `GUITree` (except the unstable-screen refetch path, `State.removeLastLastGUITree`, `State.java:556-560`). The only LRU is `UICoverageTracker` (`UICoverageTracker.java:59-69`, `coverageMaxStates`). There is **no `catch (OutOfMemoryError)` in the exploration loop**: the only one in `src/main/java` is `MopData.java:328` (inside `MopData.load`, the static JSON parse — verified: a grep for `OutOfMemoryError` returns MopData `:192,195,328` plus a doc comment at `ScreenshotCapture.java:59`; nothing in the loop). On OOM the `finally` runs `saveGraph` and re-OOMs on `oos.writeObject(model)` (`StatefulAgent.java:1866`) — a guaranteed secondary crash. `GUITree.releaseData()` (`GUITree.java:340-344`) frees only the DOM `Document` + `AccessibilityNodeInfo`; the `GUITreeNode` structure survives. → **Driver D6.**

**T7 — The log is stdout with no run id, no sink, no schema, and the baseline is blind by construction.** `Logger` (`Logger.java:22`): compile-time `private static final boolean debug = false`; writes to `System.out` only (no level, no sink, no timestamps, no run id, no thread id). Eleven structured channels exist (occurrence counts from `src/main`): `[APE-RV]` (54), `[APE-STEP]` (21), `[APE-OUTCOME]` (7), `[APE-MOP-DATA]` (8), `[APE-ARCH]` (8), `[APE-LLM-ERROR]` (6), `[APE-LLM-TEL]` (3), `[APE-LLM-CONFIG-ACK]` (3), `[APE-LLM-PROMPT]` (2), `[APE-LLM-CONFIG]` (1), `[APE-LLM-RESPONSE]` (1). Joinability is deliberate but fragile: `[APE-STEP]` ↔ `[APE-OUTCOME]` ↔ `[APE-LLM-TEL]` join on `step=` via the buffered decision at `StatefulAgent.java:117-124,1512-1513`, and `clock=` epoch-millis (`StatefulAgent.java:1496`): the code stamps wall-clock **specifically** to allow offline joins with logcat without coupling APE to logcat (comment `:1390-1392`). But the format is space-separated `key=value` with **no escaping** (only `text="..."` in `[APE-LLM-TEL]` `Logger.java`-style); no JSON anywhere. And `INV-ARCH-01` forces `[APE-STEP]`/`[APE-OUTCOME]` off under `apePureMode` (`Config.java:346`), so **the baseline arm emits zero mechanism telemetry by construction** — the architectural tension between parity and observability (INV-ARCH-01) that the report must confront. There is no per-run echo of effective MOP weights/paths (the LLM has `[APE-LLM-CONFIG]`; MOP does not). The transport is lossy: stdout through adb, NUL-contaminated (the 2026-07-24 calibration parser must read with `errors="replace"`; plain `grep` is unsafe), truncated when adb drops, gone on SIGKILL. **The thesis-blocking gap:** no key ties an RVSEC violation event in `.logcat` to the APE step in `.trace` (the `clock=` field exists but is never consumed cross-channel today), no `first_seen_step` per dedup key, no per-run echo of effective MOP config. → **Drivers D7, D9** (one problem seen twice).

**T8 — Resilience is split-brain, and the jar loses at the boundary.** The Python layer already provides timeout-as-success (`RVToolTimeoutError` as *expected*; subsystem-rv-experiment.md:497) and crash-safe resume at *task* granularity (`TaskStorage` atomic `tasks.json`, `:100-113,481-499`), with a +15 s grace (`tool.py:988-991`). But a jar process that dies at second 120 of 600 s yields a task the Python layer records as *completed* (exit code only `logger.debug`-ed, `tool.py:1121-1125`). In-process: teardown isolation is excellent (INV-EXPL-16/29: 2 + 6 + 9 isolated steps in `Monkey.run` `:777-799`, `MonkeySourceApe.safeStep`:225-232`, `StatefulAgent.safeStep:1793-1800`), and the `BadStateException` ladder is sound (`ApeAgent.java:353-375`: one refetch retry, `stopTopActivity` after 10 consecutive, `StopTestingException` after 100, caught at `MonkeySourceApe.getNextEvent:1429-1432`). But **resume is impossible by construction**: `saveGraph` writes a `Model` (`StatefulAgent.java:1866`, `oos.writeObject(model)`) while `readGraph` casts to a `Graph` (`Graph.java:1168`, `return (Graph) ois.readObject();`) — a guaranteed `ClassCastException` swallowed by the silent catch (`Graph.java:1169-1172` → "Fail to load graph" → `return new Graph()` at `:1173`) (INV-EXPL-03); nothing is persisted except at teardown; there is no shutdown hook; the deadline check is non-preemptive (`Monkey.java:1298-1299`); and on a long run `saveGraph` is itself an OOM site. The coverage dump is now hoisted *before* `saveGraph` (`:1807`, issue #16) so 333/338 teardown dumps survive — but the model/graph resume stays broken. → **Driver D8.**

**T9 — Testability was achieved by working around the design, not through it.** The Android stub jars are deliberately excluded from the test classpath (`pom.xml:84-104`), so any class touching real `android.*` is untestable on the JVM; the 19 skips (6 LLM, 9 Android-runtime, 3 PointF+device, 1 AccessibilityNodeInfo) are its footprint. Global mutable state is unresettable: `AndroidDevice`'s 7 `public static` service handles (`AndroidDevice.java:61-77`), `Config` frozen at class-load (5 fields demoted from `final` as a workaround, `Config.java:149,151,153,165,245`), `NameManager`/`StringCache`/`GUITreeBuilder` static caches leaking across tests, `GUITree`'s non-deterministic counter (`GUITree.java:61`). Pure logic is extracted into package-private "seams" each with a confessional comment (`Config.clampLlmPercentageNoSubstrate` `:315`, `State.beatsLeastVisited` `:165-167`, `Model.collectReplayTreeTransitions:300-311`). → **Driver D5.**

**Causal structure — which tensions are one problem.** T1, T2, T3 and T5 are the **same problem**: *the mode/precedence concept has no representation in Java, so every new mechanism must encode itself as a guarded `return` inside `selectNewActionNonnull`, and its on/off switch must live in a Python dict.* T4 is the *consequence* of that same absence at the baseline boundary (no structural place to say "this is APE", so it is a flag + a registry that inspects fields, not paths). T7 is the same problem at the telemetry boundary (no run identity, so no way to tie a step to an outcome or a violation). T6 and T8 are the two tensions that are *not* the same problem — they are independent debts (ownership/retention; lifecycle/resume) that would still exist if the mode problem vanished. T9 is the price the codebase pays for the architecture that T1–T5 produced (static singletons + frozen Config are what make the tests need seams). **The thesis: three-quarters of the pain is one problem — "the mode/precedence concept has no home in Java" — and two genuine, separable debts (memory ownership T6; process lifecycle/resume T8) that the same re-architecture should and must absorb, because they are the two places where a *process boundary* already exists (Python supervises the jar) but is not exploited.**

### A4. What the current design gets right and must not be lost

1. **The teardown isolation invariants.** INV-EXPL-16 (`Monkey.java:777-799`) and INV-EXPL-29 (`MonkeySourceApe.java:225-232`, `StatefulAgent.java:1793-1800`) are the best-engineered part: every step in the tear is wrapped so a throwing step costs only its own artifact, and nothing in the `finally` may throw. A `safeStep` discipline that survives a redesign is the single most valuable invariant.
2. **Fail-fast on MOP load.** A set-but-unloadable `mopDataPath` aborts via `StopTestingException` (`StatefulAgent.java:185-187,221-228`, INV-MOP-22) so an arm is never silently mislabelled. (The corollary gap — an *absent* path is silently non-MOP at the Python layer, `tool.py:1092-1096` — is T5's problem, not this invariant's.)
3. **The `step=`/`clock=` join design.** The buffered-decision discipline (`StatefulAgent.java:117-124,1512-1513`) and the epoch-millis `clock=` on every step line (`StatefulAgent.java:1496`) are what made the 2026-07-24 calibration report reconstructible from raw traces alone (880/880 parsed, 39,341 call records reconciled digit-for-digit). Any observability redesign must preserve this at minimum — the report is the acceptance test.
4. **The `ScoringPass` no-op-when-disabled contract (INV-ARCH-02)** and its single injection point (`StatefulAgent.java:1631`) — the one clean RV seam, even if its `fromConfig` parameter is decorative (`ScoringPipeline.java:48-49`).
5. **The arm-explicitness pytest guards (INV-APV-14)** — even though they validate a Python constant against itself, the *discipline* (every non-exempt arm sets every arm-defining key) is scientifically valuable; whatever replaces the Python arm layer must keep this property *loud*, not self-referential.
6. **The seeding/reproducibility work** (`RandomHelper.seed`, paired designs) — arm-neutral and thesis-required (frozen metric definitions, seed-matched runs).
7. **`apePureMode`'s reflective completeness** — whatever replaces the kill-switch must keep the *property* "a future RV flag cannot be added without registering itself in the baseline policy, enforced by a test" (`ApePureModeKillSwitchTest.java:107-132`).
8. **The teardown-ordering fix is now a structural invariant.** Hoisting the coverage dump ahead of `saveGraph` (`:1807`) recovered 333/338 lost dumps; the lesson is that *ordering* is the mechanism, not a shutdown hook (the A10 design note in §3.12 withdraws the hook precisely because the stdout sink is closed before it could run).

### Part A summary (the diagnostic core, by driver)

| Driver | Current situation | The real problem |
|---|---|---|
| D1 baseline fidelity | one `apePureMode` flag + a reflective test | fidelity is asserted by a *field* test over a *code*-level divergence the test cannot see |
| D2 mode concept | 3 CLI strings × 112 flags, all unconnected; 26 arms in Python | no `Mode`/`Arm`/`Variant` type in Java; precedence = statement order |
| D3 features | scattered read-sites; decorative cfg | no declared dependencies/conflicts; F10≈F2+MOP unenforced |
| D4 spaghetti | `selectNewActionNonnull` 141 LOC | four subsystems' precedence = textual order of guarded returns |
| D5 testability | seams + static singletons; Android stubs excluded | decision path untestable on JVM; no device abstraction |
| D6 memory | 3 unbounded retainer roots; no prune trigger | no owner of a GUITree; OOM has no in-loop handler |
| D7 traceability | 11 free-form stdout channels; NUL-unsafe; baseline blind | no run id, no file sink, no violation↔step join, no MOP config echo |
| D8 resilience | excellent teardown; broken resume; silent truncation | jar owns lifecycle but Python owns completion — and mis-counts deaths |
| D9 impact | `decision_source` + per-step boosts; arms hand-written | no generated arm matrix; counterfactual is 1-step myopic; attribution≠causation |
| D10 simplicity | 502-line Config; kill-switch registries | complexity is concentrated, but additive, not collapsing |

---

## Part B — Design space

The three candidates are carved from this space. This section maps the axes so the reader sees what each candidate *chooses* and what it *rejects*.

### B1. Where the mode definition lives

| Option | Trade-off | Reference |
|---|---|---|
| A. Java enum + factory | matches a `switch`; explodes when mode is two-dimensional (base × guidance) or parametrised; hard-codes a closed set | — |
| B. Python dicts in `tool.py` (**today**) | no contract, no validation, drift silent; but zero migration cost since the task matrix already lives there | `tool.py:240-336` |
| C. A **shared declarative artifact** both sides read | one source of truth; the boundary carries files anyway; needs a format + validator + versioning/checksum; the hard problem is who validates and when | LLVM `-passes=` strings |
| **D. Authority moves to the process boundary (my candidates)** | mode is not "a thing in Java or Python" — it is a function of *which process boundary* owns the decision | Fastbot2 client/server split (§B8) |

A is rejected by the §2.2 open questions. B is the status quo and the root of T1/T2/T5. C is the obvious fix the owner will read about next; I adopt it as a *component* of candidates but never as the single idea. D is the move my candidates make: stop asking "where should the mode object live" and ask "what process should *be* the mode."

### B2. How features are represented

| Option | Trade-off | Reference |
|---|---|---|
| Flags with scattered read-sites (**today**) | zero ceremony, impossible to see the composition | `Config.java` (112 fields, ~200 read-sites) |
| `ScoringPass` pipeline (scoring only) | clean for scoring; passes still read static `Config`; nothing covers perceive/route/pick/input | `ape/agent/scoring/` |
| Capabilities with declared deps/conflicts | right vocabulary for "F10 needs F2 and MOP"; machine-checkable; risk of a framework that outlives the features | ARES plugin strategies |
| **Feature = a projection over an event stream (my C1)** | the feature's effect is a derived view of the log, not a flag read at an injection point | event-sourcing |
| **Feature = a clause in the policy program (my C2)** | dependency/conflict is textual inclusion/exclusion in the program | — |

### B3. How decisions are dispatched

| Option | Trade-off | Reference |
|---|---|---|---|
| Guarded `return`s in textual order (**today**) | precedence = statement order — T2 | `SataAgent.java:449-589` |
| Pipeline / pass list with a spec | same as CoR but data-driven; the pipeline becomes the mode | LLVM PassBuilder |
| Data-driven tables (condition → action) | elegant for the SATA chain; loses per-block reasoning | — |
| **A policy program drives the executor (my C2)** | precedence is the program text; the jar only interprets | Fastbot2 server picks the event |
| **Decision is the next event in the log (my C1)** | the "dispatch" is replay of a recorded decision; the jar re-derives state from what already happened | event sourcing |

### B4. How state is owned and bounded

| Option | Trade-off | Reference |
|---|---|---|
| Unbounded object graph (**today**) | T6; the known OOM | `Model.actionHistory:136-137` |
| Eviction by policy at each root | keeps the graph; needs ownership rules; fixes 3 roots independently | `UICoverageTracker:59-69` is the only precedent |
| **Append-only durable log + evictable projections** | memory = O(active projection), not O(steps); resume = replay; cost = a per-step journal write (charged at ~0.037–0.052 pp cov_mop/step, §3.11) | Fastbot2 model reload every run |
| **Executor holds nothing across steps (my C2)** | the jar is stateless across decisions; all state lives in the policy/program | — |

### B5. How observability is produced

| Option | Trade-off | Reference |
|---|---|---|
| Free-form stdout `key=value` (**today**) | NUL-unsafe, no run id, baseline blind | `Logger.java:22` |
| Typed, versioned event stream | joins become trivial; needs escaping discipline; cost/step must stay ≪1 s | the `[APE-STEP]` seed already exists |
| **The event stream IS the trace (my C1)** | no join problem — there is one log; the violation↔step join is a wall-clock match against the log | Linux tracepoints |
| **Policy/program echoed as provenance (my C2)** | the policy text is the run's provenance; each step logs "this directive did X" | — |

### B6. How failure is contained

| Option | Trade-off | Reference |
|---|---|---|
| In-process step isolation (**today**) | INV-EXPL-16/29; no catch of `Error`; resume impossible | `Monkey.java:777-799` |
| In-process + journal (checkpointed state) | resume within the same process; the timeout-clock question must be answered by a trace marker | — |
| External supervision (Python relaunches the jar) | already half-present; a restart is one sample only if the trace says so | `TaskStorage`; ARES episodes |
| **Process is disposable; supervisor owns the clock (my C3)** | OOM/crash = restart, not recovery; the timeout clock lives in the supervisor, not the dead process | Fastbot2 multi-device collaboration |

### B7. Where the experiment matrix is defined

| Option | Trade-off | Reference |
|---|---|---|
| Hand-written Python dicts (**today**) | explicit but self-validated; drift silent | `tool.py:453-659` |
| Generated from a feature manifest | arms = presets over a capability model; preserves the 26 existing arms | — |
| **Policy variants selected by the supervisor (my C2/C3)** | an arm is a policy file; the supervisor declares which policy to run; drift is a file-missing error, not a silent key ignore | Fastbot2 reuses the probabilistic model across runs |

### B8. Prior art — how comparable tools solve the same problems

Ground-truth from the literature (`Fastbot2`, TSE/ASE 2022; `Stoat`, ASE 2017; `DroidBot`, ICSE-C 2017; `Humanoid`, ICSE 2019; APE upstream, ICSE 2019; `GPTDroid`/`DroidAgent` LLM explorers):

- **Fastbot2 is the decisive precedent** for two of my three candidates. Its architecture *splits the client from the server*: the client (it reuses **Ape's** GUI-tree dumping and action execution — `fastbot2.pdf §2.4`) is a faithful executor; the server (Go, with an RL Q-table) decides which event to execute and "supports multi-device collaboration mode (multiple clients … share the same probabilistic model and the RL agent)." Two lessons: (1) the *executor-should-be-dumb* split is already a proven pattern in this very lineage (APE → Fastbot2), and (2) the model/policy is **persisted and reloaded across runs** ("reusing the knowledge of event-activity transitions from previous runs" — the exact capability C1/C3 make structural). Fastbot2's client being Ape-derived is the strongest argument that *splitting execution from policy is a conservative move for this stack*, not a reinvention.
- **Stoat** keeps a GUI state-machine and persists it; its weakness on the ByteDance eval ("Stoat only generated ~300 events in 1 hour … kept querying the GUI tree and only generated the next event until the current page became stable") is a case study in *what not to do with a blocking perception loop* — directly relevant to the LLM's blocking cost (§3.11).
- **DroidBot** parameterises DFS/BFS/random with *weights* — a small, named, file-readable policy; the closest existing analogue to "mode as a named preset over parameters."
- **ARES** uses *episodes* and restart-based recovery as a first-class unit — the precedent for C3's "process is disposable, supervisor owns the clock."

### B introduction — how my candidates differ from `analise_deepseek-v4.md`

Deepseek's three were **pipeline-as-stage, capability-matrix, two-lineage**. Mine are **log-is-truth, policy-exits-jar, process-is-disposable**. The difference is the unit of architecture: deepseek asks "where do I put the decision code *inside* the system"; my candidates ask "which boundary should *own* the decision, the state, the lifecycle." Each of mine is, in a different way, an argument that the right place for a lot of today's tangled Java is *not in the jar*.

---

## Part C — Three candidate architectures

### Candidate X1 — **EventSourced Explorer** ("the run is a log; the model is a view")

**Organising principle.** The entire run is one append-only, durable **event log**; every fact the explorer ever produces is an event; the `Model`/`Graph`/`State`/coverage/trees are **read-model projections** rebuilt from the log, discardable and rebuildable at any time. Mode is a *projection-set / replay-filter*: the `ape` mode replays only upstream event types and drops RV projections; `mop` adds the MOP-scoring projection; `llm` adds the LLM-coordination projection. Precedence is not statement order — it is the **deterministic ordering of event types in the log**, fixed by the producer's stage sequence. This is structurally different from both deepseek's pipeline (the pipeline is the *producer* of events; the log is the *authority*) and its capability matrix (the projections are not "capabilities enabled by a flag" — they are *derivations* that may or may not be materialised, and an arm that skips a projection still has the event that would have triggered it, available for later re-analysis).

Why it is comfortable to propose *here*: this stack already produces a trace-like artifact (stdout through adb). X1 makes that literal — the artifact stops being "stdout that someone parses later" and starts being "the system's source of truth, written as it runs."

The data flowing through: `StepInput` (perception snapshot: tree, counters, mopData, rng-state) → `Stage` produces a `Decision` (action + decision-source + boosts) → appended as `StepEvent{v,clock,decision}` → projections update. The jar holds **no authoritative model**; at startup it replays the log tail (last N events) to rebuild working projections, or starts empty.

**Module/package structure.**

```
ape.event                       // the new core
  EventLog                      // append-only, flush per event (within the +15s grace)
  EventType                     // STEP, PERCEPTION, DECISION, TRANSITION, MOP_HIT, … (versioned)
  Projection                    // interface: on(Event) -> unit; rebuildFrom(log)
  ProjectionManager             // owns which projections are active for the mode
  StepEvent/TransitionEvent/…   // the concrete event shapes
ape.projection                  // the read models
  stateGraph/   (replaces Model/Graph as authority)
  coverage/
  mop/
  llm/
ape.pipeline                    // the producer (generalised ScoringPass + routing)
  Stage                         // like ScoringPass but for the whole step; run(ctx)->Decision|noop
  StepContext
```

The sketch:

```java
public final class EventLog {
    private final Path file;                 // flushed per append; survives SIGKILL
    void append(Event e) { write(e); if (e.type.stepBoundary) flush(); }
    <T extends Projection> T project(T seed, Predicate<EventType> keep) { replay(seed, keep); }
}

public interface Stage {
    String name();
    boolean applies(StepContext ctx);
    Decision run(StepContext ctx);           // Decision = Action + decisionSource + boosts + cfAction
}

// Mode = which projections are materialised during replay.
// ape  = {UpstreamState, UpstreamNaming}
// mop  = ape ∪ {MopScoring, Frontier}
// llm  = aperv ∪ {LlmCoordination}
```

**How the five modes (and a sixth) are expressed.** A mode is a *projection set* declared in a `modes.yaml` that lists which projection classes are active and which event types are emitted. `ape` = the upstream projection set only, and `StepEvent`s that carry RV-only payload fields are still *logged* (so the pure build emits full step telemetry — fixing INV-ARCH-01 structurally: the baseline is observable because observability is a projection, not the first flag turned off) but **ignored** by the pure projections, which is exactly upstream behaviour. `aperv`, `mop`, `llm`, `llm_mop` add projections cumulatively (monotone chain). A sixth (`mop_act_frontier`) = `mop` + `{Frontier:MopFrontier}` projection — a one-line manifest edit, no code. The manifest is the single source of truth, shipped in the jar **and** read by `tool.py` (drift becomes loud: an unknown projection name = startup failure + pytest failure).

**Answers to the §2.2 open questions.**
- *Does `mop` include `aperv`?* **Yes, by construction** — the projection set is cumulative (`mop ⊇ aperv` projections). The monotone chain isolates exactly one mechanism per mode step, which is what the phase-2/p-3 comparison needs. The counter-factual `mop_on_ape` is expressible (project-set = `ape ∪ MopScoring` but without aperv's `Frontier`/`Coverage`/`Form` projections) precisely because projections are independent of the event stream — but it is not a default preset, protecting statistical power.
- *Widget vs frontier?* A *parameterised family of projections*, not modes. `MopScoring` has a `widgetCensus` parameter (A′ axis); `Frontier` vs `MopFrontier` are separate projection classes that may both be active — arm count stays additive.
- *`llm` fallback two-dimensional?* The LLM projection is independent of the base projection set; its fallback is the next `Stage` in the pipeline (the projection simply emits no `Decision` and the pipeline falls through) — exactly today's semantics, but now visible: the log shows the `LlmDecision` event with `result=null/accepted` and the following `SataDecision` event.
- *Is "mode" the right primitive?* **No** — the primitive is the projection set; "mode" is a named preset, echoed as `[APE-MODE] projections=…` at startup.

**Driver satisfaction.**
- **D1:** `ape` = upstream projections + a pipeline that only emits upstream `Stage`s; the RV stages still *run* (they are cheap no-ops on an empty MOP/LLM context) but their *projections* are inactive, so the observed `Model`/naming/coverage is byte-for-byte the upstream projection. The ~16 always-on behavioural fixes (T4): those that change the *event stream* (`ApeFuzzer` dispatch, naming-binarySearch, refinement gate) are **accepted as "APE + documented bugfixes" with an oracle-equivalence test** (same app, same seed, compare the projected state graph against a captured trace of upstream `8f51b99`, per `docs/20260708_arquitetura_separacao_aperv.md§7.1`); the crash/correctness fixes are not behaviour.
- **D2:** Modes are projection-set names in `modes.yaml` — declarable, discoverable (jar `--list-modes`), validatable (unknown → hard error), loggable, diff-able, testable. The silent `sata` fallback becomes a loud `"unknown mode; valid: ape,aperv,…"`.
- **D3:** A projection *declares* its dependencies (e.g. `mopFrontier` requires `mopData` event types); `ProjectionManager` validates the closure at startup. No decorative cfg — projections receive their parameters as constructor args from the manifest.
- **D4:** `selectNewActionNonnull`'s 141 LOC collapses to `pipeline.run(ctx)` + `log.append(Decision)` — precedence is the pipeline's data-driven order, not statement order.
- **D5:** `Stage` and `Projection` are pure functions over `StepContext`; a test feeds a `StepContext` with stub projections and asserts the `Decision`, on the JVM, no Android runtime. `Pipeline.run` + fixed seed is deterministic.
- **D6:** The strong half: the authoritative store is the append-only log (disk, not heap), so memory is O(active projections), not O(steps). The three unbounded roots (action history, tree-transition history, the never-cleared cache) become *one* log plus evictable projections — eviction is now legal because state is re-derivable. Honest weakness: X1 does not, by itself, reduce the *log* size (D6's other half — disk); it buys bounded *memory* and *resume*, at the cost of a per-step write.
- **D7:** The log *is* the trace — no join problem, no NUL-through-logcat (the jar writes a real file, `aperv` pulls it; see §D4), versioned event schema with escaped fields, a per-run `run_id` emitted as the first event. `INV-ARCH-01` is fixed structurally: every mode emits full `[APE-STEP]`-equivalent events (obs. is a projection), and the pure mode's projections simply don't consume the RV-only ones. The violation↔step join is a wall-clock match of an RVSEC violation timestamp against the log's `clock=` (or, better, a monotonic `seq=` field — the log is the single sequence authority).
- **D8:** Recovery = replay from the last checkpoint mark in the log. The deadline check becomes *preemptive* (a stage can short-circuit on `clock > deadline` because the deadline is data in the `StepContext`, not a field read once per loop). OOM no longer loses the run — the log is flushed and the supervisor restarts from the last mark.
- **D9:** Ablation arms are *replay filters*: "mop off" = replay with the `MopScoring` projection disabled — the same log, different projection. `decision_source`, `cf_action`/`cf_changed`, and per-run effective MOP weights are all **events** (the MOP equivalent of `[APE-LLM-CONFIG]`).
- **D10:** One new concept (`Projection`), one new indirection (`EventLog`), and the *deletion* of the teardown-only `saveGraph`/`sataModel.obj` and the action-history retainer.

**Migration path.** Stage A (both C1 and C2 need this first): goldens — capture today's `ape-rv.jar` action sequences on seeded runs. Stage B1 (no behaviour change): introduce `EventLog` writing one event per step *in addition to* the existing stdout, and prove the event stream re-derives today's `Model` by the end of the run (parity test on the projection). Stage B2: make `Stage` a real interface and route the decision through it with the existing order preserved (goldens stay green). Stage C: make the projections real and drop the in-memory authoritative graph; the jar now *projects*, it does not *hold*. Stage D (cross-repo): `tool.py` reads `modes.yaml`, pulls the device-side log at teardown, and the Python analysis layer joins violations against `seq=`/`clock=` instead of guessing. Blast radius: the decision path + persistence + the Python collector; `ape.properties` shrinks (fewer flags as behaviour moves to event-type emission). Effort: high — 4–6 weeks for one maintainer, but stageable and parity-gated.

**Honest weakness.** (1) The risk is the §8 failure mode of "adds an indirection layer on top of the current design" — X1 is only justified if the *authority shift* actually removes code elsewhere; a half-eaten log that the old graph still mirrors is net negative. (2) It does not reduce *disk* (D6's other half — the 3.5 GB/880-tasks extrapolation) unless the log is compact and compressed; a naive per-step text log could *grow* storage. (3) X1 still leaves the *Python arm layer* as hand-written unless Stage D is done — it fixes the jar-side mode problem but not the cross-repo one in isolation. (4) It is the largest rewrite of the three; on this codebase a rewrite risks orphaning the in-flight `telemetry-proof-llm-efficacy` change, whose `cf_action`/`cf_changed` fields would need to become events. It should be the choice only if the owner judges memory/observability/traceability to be the binding constraint, not simplicity.

**Complexity budget.** New concepts: 2 (`Event`, `Projection`). New indirections: 1 (`ProjectionManager` dispatch). LOC delta: roughly neutral at steady state (−deletes the action-history retainer, the saveGraph/serialisation path, the teardown-only persistence; +adds the event schema and ~7 projections). Newcomer's learning: understand that the log is authoritative and projections are derived views; everything else is the producer stages, named in one file.

---

### Candidate X2 — **PolicyAsExecutor** ("the jar is a dumb, faithful executor of an external policy program")

**Organising principle.** The fork's central disease is that the jar is *both the person who decides and the person who does*. X2 surgically separates them: the jar becomes a minimal, deterministic, **replayable executor** of an external **policy program**; a policy program is a small, versionable, human-readable artefact (a JSON/Lisp-like S-expression sequence of `when <perception> then <directive>`, or simply a list of `(step-index → directive)` directives) that lives next to `ape.properties` and is read at startup. **Mode = policy program.** The LLM is promoted from a *hook inside* `selectNewActionNonnull` to a *policy-authoring* input: it writes directives the executor then runs deterministically (or the policy embeds the LLM as a `consult` directive). This is the Fastbot2 client/server split, localised to a file boundary instead of a network one — and Fastbot2 proves the pattern is native to this stack because its client *is* Ape.

Why it is comfortable to propose: it does not fight the Android/`app_process`/d8 constraints (no framework, no server at runtime); the "program" is tiny text; and it turns the owner's "a new mode should be cheap to add" into a literal file edit. Why it is **uncomfortable**: it asks the owner to give up *control of the decision logic* inside the jar — the jar stops being "APE-RV the explorer" and becomes "APE-RV the executor." That is the point.

The data flowing through: `Perception` (tree, counters, mopData, rng-state) → `PolicyEngine.step(perception)` → a `Directive` (click, long-click, back, menu, type, launch, `consult-llm`, `score-by`, `noop`) → `Executor.execute(directive, perception)` → `Effect` logged. The jar holds *no mode logic* — only a tiny interpreter over directives and a small set of concrete executors.

**Module/package structure.**

```
ape.policy                          // the new seam
  PolicyProgram                    // parse + validate a directive list; loud on unknown directive
  PolicyEngine                     // step(ctx) -> Directive (the ONLY "decision" in the jar)
  Directive                        // the instruction vocabulary
  directive/
    Click, LongClick, Back, Menu, Type, Launch, Fuzz, ...      // concrete executors (no branching)
    ConsultLlm, ScoreBy, Counterfactual                    // RV directives embedded as primitives
ape.executor                      // the faithful runner (today's MonkeySourceApe dispatch)
```

The sketch:

```java
// The policy file (one of them, per mode):
// [ {:when [:new-state] :then [:consult-llm {:on "new-state"}]}
//   {:when [:mop-screen] :then [:score-by :mop-widget]}
//   {:then [:sata-fallback]} ]

public final class PolicyEngine {
    private final List<PolicyRule> rules;            // parsed once from the policy file
    public Directive decide(Perception p) {
        for (PolicyRule r : rules) if (r.matches(p)) return r.then();
        return Directive.NOOP;                         // never silent: logged as a decision with source=policy-default
    }
}

// The jar's decision point collapses to one line:
Directive d = policyEngine.decide(perception);
Action a = executor.execute(d, perception);
eventLog.append(StepEvent.builder().directive(d).action(a).build());
```

The five modes + a sixth as policy files:
- `ape.json` — rules: `sata-fallback` only (the original chain, now expressed as named directives); no `consult-llm`, no `score-by :mop-*`, no `launch`. This is the *pure* arm and it is pure by the file it reads, not by a kill-switch.
- `aperv.json` — `ape.json` + `score-by :coverage`/`:form`/`:wtg` directives + `model-menu` + `typed-input`.
- `mop.json` — `aperv.json` + `score-by :mop-widget :mop-frontier :menu-gateway` + `launch`.
- `llm.json` — `aperv.json` + `consult-llm` at the top.
- `llm_mop.json` — `mop.json` + `consult-llm`.
- Sixth: `mop_on_ape.json` — `ape.json` + MOP scoring directives only (expressible, not default).

**Answers to the §2.2 open questions.**
- *Does `mop` include `aperv`?* **It is a manifest convention, not a hard rule** — `mop.json` *imports* `aperv.json`'s directive set, so the default is stacked; but `mop_on_ape.json` is a valid, versionable file that imports `ape.json` instead. The "stacked" answer is policy, not physics.
- *Widget vs frontier?* Separate `score-by` directive kinds, each independently includable — a parameterised family inside the policy, not a mode.
- *`llm` fallback?* The `consult-llm` directive's executor returns `Directive.NOOP` on failure/circuit-break/decline, and the policy's next matching rule (the `sata-fallback` catch-all) fires. Two-dimensional mode = two axes of imports in the policy file, explicit and reviewable.
- *Is "mode" the right primitive?* **No** — the primitive is the policy program; "mode" is a named file. The five names are CLI sugar that selects `ape.json`…`llm_mop.json`.

**Driver satisfaction.**
- **D1:** `ape.json` contains *only* the original SATA directives — there is literally no MOP/LLM code path in the executor's `ape.json` execution. The ~16 always-on fixes (T4) are *separated*: the three behavioural ones become part of the `sata-fallback` directive's spec (documented + oracle-tested, since the oracle now compares *directive execution* against upstream, a cleaner contract than "flags off"); the crash/correctness fixes stay in the executor, gated off by the policy for pure mode. **This is the only candidate where `ape` is structurally pure, not flag-asserted.**
- **D2:** Mode = a file name. Unknown → hard error at startup (`PolicyProgram.parse` throws on unknown directive). Modes are diff-able (git diff of JSON), versioned, and the jar prints `[APE-POLICY] file=… sha256=… directives=[…]` — full provenance, echoable by `tool.py`.
- **D3:** A directive *is* a feature; dependencies are textual inclusion (`mop.json` imports `aperv.json`); conflicts are enforced by the validator (e.g. `score-by :mop-widget` requires `consult-mop-data` to be present, or the parser rejects).
- **D4:** The `if`-ladder is *deleted* — `SataAgent.selectNewActionNonnull` becomes `executor.execute(policyEngine.decide(perception), perception)`. Precedence is the policy's rule order.
- **D5:** The executor's `directive/Click.execute(...)` is a pure function of `(directive, perception)` — trivially unit-testable, no Android runtime for the decision path. The policy engine is pure logic over `Perception`.
- **D6:** The executor holds **no** accumulating history — `actionHistory` becomes a projection/event stream (the log again), so memory is bounded by `Perception` (one tree) not by run length. Honest weakness: the policy files themselves are small, but a *policy that embeds the LLM* still pays the ~0.95 steps/call cost (§3.11); X2 doesn't reduce that, it relocates *where* the call is decided.
- **D7:** Every step logs the `Directive` it executed and the `Effect` it produced — `[APE-STEP] directive=score-by:mop-widget action=click decision_source=POLICY seq=N`. The policy file's sha256 + the directive sequence is the run's complete provenance (the MOP equivalent of `[APE-LLM-CONFIG]`). The violation↔step join is `seq=` + `clock=` (already emitted) against logcat. Pure mode emits full step telemetry because the *executor* logs unconditionally (INV-ARCH-01 fixed structurally: observability is not a flag in the executor).
- **D8:** The executor is stateless across decisions; a crash loses only the current `Perception` (one tree, re-fetchable); the supervisor restarts from the last logged `seq`. Because the jar no longer *owns* the run length (the policy and the log do), the timeout-clock question moves to the supervisor (see C3) cleanly.
- **D9:** Ablation is a policy-edit: drop the `consult-llm` rule to get the LLM-off arm. The `cf_action`/`cf_changed` counterfactual (issue #16) becomes a policy-level construct: a `[cf]` rule variant that re-runs the decision with scoring boosted to 0 — defined *in the policy*, testable in isolation.
- **D10:** One new concept (`Directive`), one new indirection (`PolicyEngine`), and the *deletion* of ~141 LOC of guarded returns + the `Config` read-sites for the dispatched flags (they move into the policy file). Complexity shifts from *conditional code* to *policy text*, which the owner wanted.

**Migration path.** Stage A: goldens + a trivial policy interpreter. Stage B: extract `selectNewActionNonnull`'s blocks into `directive/*` executors, with a default policy file that reproduces today's textual order exactly (goldens green). Stage C: make the LLM and MOP launcher *directives* the policy calls, rather than hooks inside the method — this is the behavioural move. Stage D (cross-repo): `tool.py` emits a `policy=<file>` property (or the policy file itself) instead of ~18 arm-defining flags; the 26 arms become 26 policy files (or 26 named policy presets importing shared directive sets); pytest guards validate directive names against the jar's `--list-directives` dry-run. Blast radius: `SataAgent`/`StatefulAgent` decision path collapses; `tool.py` trading flags for policy files; `Config` sheds the arm-defining flags. Effort: medium-high — 3–4 weeks, but the parity story is *stronger* (the oracle compares directive execution, not flag states).

**Honest weakness.** (1) The §8 failure mode here is "externalising the control flow into a DSL nobody can read" — mitigated by keeping the DSL tiny (≤12 directive kinds) and the rule set ≤20 lines per mode. (2) It does not, by itself, solve memory (D6) or resilience (D8) — it *enables* the log-based solutions of X1/X3 by removing the jar's ownership of state and lifecycle, but the policy program cannot flush a log that doesn't exist. (3) It is the most culturally disruptive: engineers used to debugging `SataAgent.java:392` will now debug a JSON file — the owner must genuinely want "decisions outside the jar." (4) Some behaviour today is not cleanly a directive (the budget fall-through in `:468-477`, the side-effect-only component trigger `:546-551`); these become `directive` kinds that "emit and continue" rather than "emit and short-circuit," which is a real semantic shift that must be preserved by test.

**Complexity budget.** New concepts: 2 (`Directive`, `PolicyProgram`). New indirections: 1 (`PolicyEngine` interpreter). LOC delta: net-negative in the hotspots (−150 in `SataAgent`, −60 in `Config` read-sites, +90 in `ape.policy`). Newcomer's learning: read the policy file for the mode; read `directive/*` for what each directive does; the decision path is one method.

---

### Candidate X3 — **SupervisedWorker** ("the jar is a restartable, checkpointed worker; the supervisor owns the run")

**Organising principle.** The owner's D8 driver ("the only way a run ends is the timeout") is already a *project-wide law at the Python layer* (`RVToolTimeoutError` expected, `TaskStorage` crash-safe at task granularity, +15 s grace `tool.py:988-991`). The jar, by contrast, *reinvents* lifecycle management it cannot actually win (a `finally` that re-OOMs, a deadline it cannot enforce non-preemptively, a saveGraph whose resume is broken by design). X3's move is to **stop making the jar a long-lived process at all**: the jar becomes a short-lived, **checkpointable worker** that exists only to advance a durable checkpoint one step at a time; an external **supervisor** (extend the existing Python layer, not a new one) owns the timeout clock, crash-restart, retry semantics, and completion accounting. Mode is a *checkpoint/resume profile*: which projections to hydrate, which to materialise. This is structurally different from X1 (X1 solves this *inside* the jar by making the log authoritative; X3 solves it *outside* the jar by making the process disposable) and from X2 (X2 solves it by removing in-jar decisions; X3 solves it by removing in-jar *lifecycle*).

Why it is comfortable to propose: it *uses* the split that already physically exists (Python supervises the jar today) instead of fighting it. Why it is **uncomfortable**: it asks the owner to stop treating the jar as the unit of a "run" and start treating the *supervisor + checkpoint* as the unit — and to own the fact that 45% of one campaign's LLM arms truncated silently as "completed" (`tool.py:1121-1125`), a bug that is *cross-repo* and therefore cannot be fixed by jar work alone.

The data flowing through: the worker holds a `Checkpoint` (last N step summaries + compact model + run clock + seq); each step it pulls `Perception`, runs the current pipeline/stage decision (reused from today's `selectNewActionNonnull` — X3 does not redesign the decision, it redesigns the *lifecycle around* it), appends a `StepEvent` to a device-side journal, advances the checkpoint, and exits. The supervisor launches the worker with a `resume=` token; on a non-zero exit that is *not* `RVToolTimeoutError`-equivalent, it relaunches from the last checkpoint and records the death in the trace.

**Module/package structure.**

```
ape.checkpoint                  // the new core responsibility
  Checkpoint                    // compact, serialisable: seq, runId, lastClock, compactGraph, perMode flags
  Journal                       // append-only device-side step log (flush per step within grace)
  Worker                        // main(): load checkpoint? run N steps? checkpoint? exit
ape.lifecycle                    // supervisor contract (also surfaces in rv-android)
  ExitCode                      // OK, TIMEOUT, TRUNCATED_BY_DEATH, OOM
  ResumeToken
```

The sketch (worker main):

```java
public final class Worker {
    public static void main(String[] a) {
        Checkpoint ck = Checkpoint.resumeOrEmpty(args.resumeToken());
        Journal log = new Journal(ck.runId);
        for (int i = 0; i < ck.budgetRemainingMillis(); i++) {   // NOT a loop-count, a clock budget
            StepEvent e = runOneStep(ck.state(), log);
            log.append(e);
            ck = ck.advance(e);                                  // cheap, compact
        }
        ck.persist();                                            // tiny: no Model graph serialisation
        log.flush();
        System.exit(ExitCode.OK);
    }
}
```

**How the five modes (and a sixth) are expressed.** Modes are checkpoint profiles in `modes.yaml`: which projections to hydrate at resume, which event types the worker emits. `ape` = hydrate only upstream projections; `mop` = +MOP; etc. A sixth (`mop_act_frontier`) = a one-line profile tweak. Crucially, the *policy/program* inside the worker can still be X2's directive program — X3 is composable with X1/X2; the three are not mutually exclusive architectures, they are different answers to "what should the jar stop owning."

**Answers to the §2.2 open questions.**
- *Does `mop` include `aperv`?* **Yes** — a profile is cumulative by construction; `mop` hydrates `aperv`'s projections then MOP's.
- *Widget vs frontier?* Projection selection in the profile (parameterised family, not modes).
- *`llm` fallback?* The LLM is a `consult` directive whose failure yields a `StepEvent` with `decision_source=LLM, result=declined`; the worker falls through to the next directive (SATA). Two-dimensional = two profile imports.
- *Is "mode" the right primitive?* **No** — the primitive is the profile (checkpoint policy); "mode" is a named profile.

**Driver satisfaction.**
- **D1:** The `ape` profile hydrates only upstream projections and the worker emits only upstream events — structural purity by profile, not by a flag that inspects fields. The always-on fixes (T4) are classified into "behavioural" (documented + oracle-tested against the pure profile's event stream) and "crash-correctness" (kept, they run in every profile). The oracle test now compares *event streams*, which is a stronger contract than today's flag-state comparison.
- **D2:** Mode = profile name; `--list-profiles` validates; unknown → hard error. The profile is logged as `[APE-MODE] profile=… run_id=…`.
- **D3:** Projections are the feature unit; profile declares them; dependency closure validated at startup (same as X1).
- **D4:** Unchanged by itself — X3 is orthogonal to the decision ladder. It would be paired with X1 or X2 for D4.
- **D5:** The worker is a pure step function over `(Checkpoint, Perception) → (Checkpoint', StepEvent)` — trivially testable without a device; the supervisor's restart logic is pure logic over `ExitCode` + trace markers — testable on the JVM.
- **D6:** Bounded by construction: each step appends one compact event and advances a compact checkpoint; `Checkpoint.advance` is O(1); the worker holds O(1) projections per step, not the whole graph. The 3.5 GB/880-task problem becomes a disk-budget problem in Python (the supervisor owns retention), where it can be solved with compression + rotation policy.
- **D7:** The journal *is* the trace (one event per step, escaped, versioned, run_id, monotonic seq). The pure profile emits full step telemetry ( INV-ARCH-01 fixed structurally: observability is a projection the pure profile *does* materialise — step events are cheap and universal; only the RV *projections* are off).
- **D8:** **The strong half.** Crash → the worker exits non-zero with `ExitCode.TRUNCATED_BY_DEATH` (the `finally`/`saveGraph` path is simplified to a journal flush, which cannot re-OOM on a giant model because the model is compact now); the supervisor relaunches from the last checkpoint and writes a `restart=` marker in the trace. The timeout clock lives in the supervisor (`budgetRemainingMillis` is a *budget token*, not the wall clock the dead process was reading), so a restarted run's time accounting is explicit: the trace records `restart seq=1234 reason=oom grace_used_ms=142`. This fixes the `tool.py:1121-1125` bug *by design*: a death is no longer silent-completion, it is a restartable event with an exit code.
- **D9:** Profile = a selectable set of projections; ablation = toggle a projection in the profile; the counterfactual is a second profile variant. The per-run effective config is the profile's checksum.
- **D10:** Adds a `Checkpoint`/`Journal`/`Worker`/`ExitCode` set — but *deletes* `StatefulAgent.saveGraph`/`:1866` serialization, `Graph.readGraph`'s broken cast path, `Monkey.java`'s deadline check and abort suppression, the +15 s grace hack (checkpoint makes restart cheap), and the jar's entire teardown-isolation burden (INV-EXPL-16/29 stays, but now there is only one "step" to isolate).

**Migration path.** This is the most invasive because it redefines the process boundary. Stage A: goldens + a journal-only shim (write a device-side event per step *now*, for parity). Stage B: extract the teardown into `safeStep("journal", …)` that flushes the journal, and make `saveGraph` write a *compact* `Checkpoint` (not the whole `Model`), proving the worker can resume from `Checkpoint.resumeOrEmpty`. Stage C: the supervisor (Python) gains a `--resume <token>` argument and an `ExitCode` decoder; a death is no longer "completed" — `tool.py:1121-1125` is rewritten to treat `ExitCode.TRUNCATED_BY_DEATH` as failed-and-retryable. Stage D: the worker's loop becomes `budgetRemainingMillis`-driven, not loop-count-driven; the +15 s grace shrinks to the journal-flush window. Blast radius: the jar's main loop, `StatefulAgent.tearDown`, `Graph.readGraph`/`saveGraph`, the Python `execute_tool_specific_logic` (the completion contract changes), and the 83 pytest guards (they encode the old "any non-zero = crash detected" assumption). Effort: high — 4–5 weeks, and it is the *cross-repo* change that makes it risky.

**Honest weakness.** (1) It is the most couter-actually-culturally-disruptive: it asks the owner to treat the jar process — hitherto the unit of a "run" — as disposable, and to push the timeout clock into Python. (2) The `Resume`/checkpoint format is a new persistent schema; every version skew must handle it (mitigated by versioning the checkpoint, but that is itself a long-lived commitment). (3) It does *not* by itself fix the decision spaghetti (D4) — it must be paired with X1 or X2 for that; alone, X3 is a lifecycle band-aid. (4) The "truncated = failed-and-retryable" contract change to `tool.py` is a *statistical* claim: a restarted run must be counted as one sample (else the phase-3 grid's N changes), which the thesis's frozen design may not tolerate without a protocol amendment. (5) It re-opens the "what is a run" question that §3.10 already answers at task granularity — the owner must decide whether sub-task restarts are in-scope.

**Complexity budget.** New concepts: 3 (`Checkpoint`, `Journal`, `ExitCode`). New indirections: 1 (supervisor/worker protocol). LOC delta: net-negative in the long run (deletes `saveGraph`/`readGraph`/`Monkey` deadline/abort machinery) but the one-time cost is a checkpoint serializer + a Python supervisor rewrite. Newcomer's learning: the worker is a step loop that checkpoints; the supervisor owns the clock and the retries.

---

## Part B — Design space (synthesis, referenced by the candidates)

Already mapped above in §B1–§B8. The candidates choose:

- **X1** chooses: authority = durable log (B4-D, B5-D, B6-D, B7-D), mode = projection set (B1-D), decisions via stages that *produce* events (B3-D), features as projection classes (B2-D). Rejects B1-A/B/C (the mode object, including a shared YAML, is not the unit — the *projection set* is), and rejects making the jar own a long-lived process (B6's "external supervision" is a Python-side concern that X1 can coexist with but does not require).
- **X2** chooses: authority = policy program (B3-D, B5-D), mode = named file (B1-D), features as directives (B2-D), executor stateless (B4-D "executor holds nothing"). Rejects B1-A/B/C (the mode object in Java is the failure), rejects "flags scattered" (B2-today), rejects "the jar decides" (B3-today).
- **X3** chooses: authority = checkpoint + journal at the process boundary (B6-D, B4-D, B7-D), mode = profile (B1-D), worker as a step loop (B3-compatible). Rejects B1-A/B/C (mode as in-jar object), rejects "the jar owns its lifecycle" (B6-today), rejects "saveGraph is fine" (the resume mismatch).

All three share two commitments the dossier forces on any serious proposal: (i) **a shared declarative mode/projection/profile manifest read by both the jar and Python** (addresses T5's silent drift — the manifest is the single source of truth, and unknown names fail loudly on both sides); and (ii) **the `[APE-STEP]`-equivalence telemetry for the pure mode** (fixes INV-ARCH-01 structurally — observability is no longer the first thing the baseline turns off, it is a universal projection/directive-log/profile option).

---

## Part D — Comparison and recommendation

### Scoring table (weights are mine; the owner's to change — I weight D1, D7, D9 highest because this is the thesis instrument, and D10 because the owner asked for it explicitly, and I weight D5/D8 as inseparable from the cross-repo reality)

| Driver | Weight | X1 EventSourced | X2 PolicyAsExecutor | X3 SupervisedWorker |
|---|---|---|---|---|
| D1 baseline fidelity | 0.13 | 3 (pure projections + oracle; still asserted, not structural) | **5** (structural — mode = file) | **5** (structural — profile) |
| D2 mode first-class | 0.11 | 4 (projection set; --list-modes) | **5** (policy file is the mode) | 4 (profile; —list-profiles) |
| D3 feature management | 0.10 | 3 (projection deps) | **5** (directive deps/conflicts in the program) | 3 (projection-profile deps) |
| D4 spaghetti | 0.10 | 4 (stages produce events; ladder deleted) | **5** (ladder deleted → interpreter) | 2 (untouched; needs X1/X2 pairing) |
| D5 testability | 0.08 | 4 (stages/projections pure) | 4 (directive executors pure) | **5** (worker step fn + supervisor pure) |
| D6 memory | 0.09 | **5** (log-is-authority; O(projections)) | 4 (executor stateless; needs journal) | 4 (compact checkpoint; supervisor owns retention) |
| D7 traceability | 0.13 | 4 (log IS trace; run_id; seq=) | 4 (directive log; policy sha) | **5** (journal + restart markers + exit codes) |
| D8 resilience | 0.10 | 4 (replay from mark; preemptive deadline) | 3 (executor stateless; needs X3 for recovery) | **5** (restartable worker; clock in supervisor) |
| D9 impact measurement | 0.10 | 4 (projection ablation; generated arms from manifests) | **5** (policy ablation; counterfactual as a rule) | 4 (profile toggle; generated arms) |
| D10 simplicity | 0.06 | 3 (1 new concept + log discipline) | 4 (2 concepts, but deletes more LOC) | 2 (3 concepts + cross-repo contract change) |
| **Weighted total** | 1.00 | **3.95** | **4.42** | 3.67 |

Weights and arithmetic: D1 0.13·3 + D2 0.11·4 + D3 0.10·3 + D4 0.10·4 + D5 0.08·4 + D6 0.09·5 + D7 0.13·4 + D8 0.10·4 + D9 0.10·4 + D10 0.06·3 = 3.95. X2: 0.13·5+0.11·5+0.10·5+0.10·5+0.08·4+0.09·4+0.13·4+0.10·3+0.10·5+0.06·4 = 4.42. X3: 0.13·5+0.11·4+0.10·3+0.10·2+0.08·5+0.09·4+0.13·5+0.10·5+0.10·4+0.06·2 = 3.67.

X2 wins because it **structuralises the two facts the dossier keeps pointing at**: the mode concept has no home in Java (D2/D4), and the control surface is split-brain between Java and Python (T5). X1 is the closest second and is the right answer if memory/resumability is the binding constraint; X3 is the right answer if process-level resilience is the binding constraint — but X3 is also the one that *requires* X1 or X2 to do anything about the actual decision spaghetti, so alone it is a band-aid.

### Recommendation

**Adopt X2 (PolicyAsExecutor) as the backbone, and adopt X1's append-only journal as the execution record on top of it — X2 + X1, in two stages.** Do not adopt X3 now; adopt its *supervisor contract* (exit codes + restart markers) as a mandatory discipline of Stage A's journal.

Concretely, the decomposition into independently valuable steps:

1. **Stage α (both X1 and X2): goldens + oracle.** Capture today's `ape-rv.jar` *event stream* (per-step perception + decision + effect, with `seq=`/`clock=`/`decision_source`) on seeded runs (cryptoapp + a handful of dataset apps), and upstream `8f51b99`'s. This is the safety net for everything else and the D1 acceptance test — and it is *cheaper* than today's "action-sequence" oracle because a step log is what both X1 and X2 produce anyway. Independent of all architecture.
2. **Stage β (X2): extract the ladder into directives.** One `Directive` per current guarded block in `selectNewActionNonnull`; a default policy file whose rule order reproduces today's textual precedence exactly; goldens stay green. Deletes the 7-copy-paste and the triplicated precondition. Independently valuable (D4).
3. **Stage γ (X2 + X1): the journal as the trace.** The executor writes a device-side, flushed-per-step, escaped, `run_id`/`seq=`-stamped event log *in addition to* stdout; prove the log re-derives today's `Model` (parity test on the projection). This is the D7/D6/D9 foundation and immediately unblocks the `telemetry-proof-llm-efficacy` verification (task 17.4) — the counterfactual and `cf_action` become events, not log fields.
4. **Stage δ (X2): the policy manifest.** `modes.yaml` listing the five presets + the 26 existing arms as named policy files; `tool.py` reads it, validates directive names against the jar's `--list-directives` dry-run, and the 83 pytest guards become manifest-vs-jar guards (drift loud). Fixes T5.
5. **Stage ε (D8, cross-repo): the supervisor contract.** The worker exits with a real `ExitCode` (`OK`/`TIMEOUT`/`TRUNCATED_BY_DEATH`/`OOM`); the journal flush is the last thing in `finally`; `tool.py:1121-1125` is rewritten so a death is failed-and-retryable, not silently-completed; trace markers `run-start`/`restart`/`grace-begin`/`run-end` make the timeout clock explicit. This is the D8 fix and it is *cross-repo* — it cannot hide in the jar.

A team can stop after Stage β (the mode/spaghetti problem is solved) or after Stage γ (observability + memory) without the others being wasted — each stage leaves the system runnable and comparable. Treat full X3 (process-is-disposable) as a *contingency*: only if Stage β's oracle test exposes unfixable behavioural drift, or if Phase 3's campaign shows run-level crashes (not step-level) are the dominant cost, does the supervisor-worker split stop being "nice to have" and start being "required."

---

## Part E — Risks, unknowns, and next steps

### What I could not determine from the code and would need to measure

1. **Per-step cost of a flushed journal on the device.** X1/X2's strongest promise is bounded memory via a durable log; X1's honest weakness is that it may *grow* disk. The D6/D7 trade is charged at §3.11's measured ~0.95 steps/call ≈ 0.037–0.052 pp cov_mop/step — but that figure is for a *blocking LLM call*, not for a journal write. A micro-benchmark (write one escaped line to a file per `Monkey` loop iteration, measure steps/task on cryptoapp at 300 s) is the gate before committing. Expected: sub-100 µs, i.e. negligible; but on `app_process` Dalvik startup I would rather measure than assume.
2. **Heap high-water mark by root over a 600 s real run.** The relative share of `Model.actionHistory` vs `treeTransitionHistory` vs `namingToGUITreeNodeCache` in a real OOM is not measured anywhere (only `ApeAgent.java:448-454 printMemoryUsage`, observational). Which of T6's three roots to fix first is a D6 cost-model question that needs a 600 s instrumented run with per-phase heap samples. X1 bets the log fixes it; if sampling shows one root dominates, X1's generality may be overkill.
3. **The real global distribution of run terminations across the phase-2 grid.** The ~45% LLM-arm truncation rate is from one arm (`docs/20260716_investigative_truncamento_600s_llm_tap.md`); the distribution of "completed" tasks with partial traces across all 21,681 is not consolidated. X3's supervisor contract needs this baseline to be priced (how often does a restart actually fire, and at what step?).
4. **Whether the 3 behavioural always-on fixes (T4) are detectable by the oracle test.** The oracle compares action sequences (or event streams); `Naming.containsNamelet`'s binarySearch change and `NamingFactory`'s per-tree gate could produce *different* abstract states without producing *different* actions on short apps — i.e. the divergence might be invisible to a short-run oracle and only visible on large apps. A 600 s oracle run on a MOP-bearing app is the real test.
5. **Fastbot2's client/server split as a local file:** Fastbot2 externalises the policy to a *network* server (Go process). X2 proposes externalising it to a *file*. The gap (network latency, multi-device sharing) is real for ByteDance's use, but APE-RV runs one device per container (`experimento-20260706` is 16 containers, one emulator each) — so the file-boundary version is *at least* as expressive for this project. The measurement needed: confirm no arm currently needs the server to be *stateful across steps* (today's LLM router is stateless-per-call; the MOP scorer is pure).

### Cheap experiments that would discriminate before committing

1. **Oracle equivalence test** — upstream `8f51b99` jar vs today's `--ape apePureMode=true`, same app + seed, compare the *per-step* `seq`/`clock`/`decision_source` trace. Decides D1's comfort level (whether the ~16 fixes need a TwoLineage-level split or can be documented + tested). ~1 day. **Also the acceptance test for X2's purity claim.**
2. **A "directive skeleton" pilot** — stub the 7 SATA blocks as `Directive` kinds behind a default-order policy file, no behaviour change, goldens green. Measures the extraction cost and confirms the `sata:*` directives capture the whole chain (X2 feasibility). ~2–3 days.
3. **A "journal shim" pilot** — write one escaped, `seq=`/`run_id`-stamped line to `/data/local/tmp/<run_id>.journal` per step, alongside stdout; prove it re-derives the model and survives a SIGKILL mid-run (kill -9 the process, restart from the journal). This is the joint feasibility test for X1 and X3. ~3–5 days.
4. **A manifest-by-hand** — write `modes.yaml` for the existing 26 arms using only directive/projection names, no code. Measures whether the feature model *fits* (do the 26 arms decompose cleanly onto a small directive set?) before any Java changes. ~0.5 day; may sink X2 cheaply.

### Questions needing the owner's decision (one line each)

1. **Is "APE + documented bugfixes" (with the oracle test as defence) acceptable as the `ape` baseline, or must `ape` be structurally pure (→ X2/X3's file/profile)?** This is the D1 question that splits all three candidates.
2. **Is the monotone stacked family `ape ⊂ aperv ⊂ mop ⊂ llm_mop` the preset policy?** All three candidates express it naturally; only X2 additionally allows `mop_on_ape` cheaply.
3. **May the jar write a device-side journal file (a real sink), or must stdout remain the only transport?** This is the X1/X3 make-or-break — the §3.12 A10 design note left it out of scope explicitly.
4. **Is a restarted run still one sample, and does the timeout clock keep running across a restart?** This is the X3 statistical question; it is also a thesis-protocol amendment, not a code question.
5. **For D9, is within-run `decision_source` attribution (plus the `cf_action`/`cf_changed` counterfactual) a *credible* impact estimate, or must Phase 3 pre-declare a statistical ablation design (Plackett-Burman) that the manifest must feed?** Decides whether D9's "generated arms" is needed or "policy ablation" suffices.
6. **What is the acceptable per-run storage budget for Phase 3?** The 3.5 GB / 880-task extrapolation (§3.11) is the real D6/D7 constraint; it gates whether an append-only journal is affordable.

### What this analysis might be wrong about, and what evidence would change its conclusion

- **The causal thesis (T1–T5 are one problem).** If the heap-sampling experiment (E1) shows OOM is dominated by a *single* root (e.g. the never-cleared `namingToGUITreeNodeCache` at `GUITreeBuilder.java:693`), the D6 fix collapses to one targeted prune and X1's generality is overkill — the ordering should then be: fix the prune, *then* X2. X1's priority depends entirely on whether memory is the binding constraint at campaign scale.
- **X2's fit.** I assume the 26 arms decompose cleanly onto a small directive set (`sata-fallback`, `score-by`, `consult-llm`, `launch`, `model-menu`, `typed-input`, `fuzz`). The manifest-by-hand experiment (E4) could falsify this; if the 26 arms do not decompose, X2's "policy file = mode" collapses and C1's StepPipeline (the deepseek candidate) becomes the pragmatic fallback — the directive set grows into a stage list.
- **The "stacked modes" default.** I argue `mop ⊇ aperv` exploration; if the owner's ablation design instead requires isolating the MOP mechanism on a *bare* base (a `mop_on_ape` arm as a first-class, not exotic, preset), X2's file-per-mode is the only candidate that makes that cheap without a combinatorial explosion.
- **X3's cost.** I priced X3 as "the cross-repo change that makes it risky." If the owner decides the truncation bug (`tool.py:1121-1125`) is already being fixed for other reasons and the supervisor plumbing already exists, X3's marginal cost drops and it should be adopted *first* (it is the cleanest fix for D8 and the 45%-truncation datapoint). But if Python-side changes are politically expensive, X3 is the wrong bet — X2+X1 fix D8's *jar*-side half (journal + exit codes) without the process-redefinition.
- **Per-step overhead assumptions.** Every proposal that adds per-step work (journal, directive dispatch) is charged against the measured ~0.95 steps per blocking call; if the journal/dispatch pilots (E1, E3) show sub-100 µs per step (as I expect on `app_process` Dalvik for a single file append), the D6/D7 budget opens up far more than I assumed, and X1's append-only log becomes not just affordable but free.

### Note on the `analise_deepseek-v4.md` pass

That document is an excellent, rigorous study and reaches a broadly similar conclusion (StepPipeline + manifest, staged). My disagreement is structural, not factual: deepseek keeps the *jar as the unit of a run* and asks "how do I organise the jar's internals." My candidates ask "what should the jar stop owning." X2 (PolicyAsExecutor) is, I think, the single idea in this report a thesis committee has not yet seen: the move that makes `ape` *structurally* pure by making the jar a faithful executor of a policy file that the *experiment layer writes*. That is the one worth the owner's serious argument.
