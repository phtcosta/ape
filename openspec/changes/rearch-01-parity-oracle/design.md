# Design: rearch-01-parity-oracle

## Context

Source of record: `docs/analise_fable-selecao.md` rev. 3 (Sec. 9.4, 9.9, 10 stage 1) and
`docs/plans/20260802_rearchitecture_roadmap.md`. This change builds the **parity oracle** —
the golden-capture harness that pins the current action-selection behavior of the jar so that
`rearch-02-runspec` and `rearch-03-decision-pipeline` can be validated against it. It is pure
test infrastructure: **zero changes to `src/main/java/`**, no `ape.properties` surface change,
no Python change.

Current state, verified at `5dcf225` (file:line):

1. **The precedence ladder is textual order.** `SataAgent.selectNewActionNonnull()`
   (`SataAgent.java:449-589`): logging `:450-462` → budget `:468-477` (returns a trivial
   action or falls through) → LLM new-state `:480-487` → LLM stagnation `:493-506` → LLM
   random `:508-515` → MOP launcher `:516-545` → component trigger `:547-551` (side-effect,
   no return) → SATA chain with the `resolved = …; if != null { log; return; }` pattern 7×
   `:552-587` → `throw new BadStateException` `:588` (report V1).
2. **The LLM precondition is triplicated** — `actionBufferSize() == 0 &&
   newState.getActions().size() > 2 && _llmRouter != null` at `:480-481`, `:493-494`,
   `:508-509` (V2), each followed by a router predicate (`shouldRouteNewState(_isNewState)`,
   `shouldRouteStagnation(graphStableCounter, stagnationHookFired)`, `shouldRouteRandom()`).
3. **Finding 3.3-1**: `_stepsSinceLauncherFiring++` at `SataAgent.java:522` sits *after* the
   three LLM blocks, whose accepted results return at `:485`, `:504`, `:513` via
   `acceptLlmResult` (`:425`). A step preempted by the LLM therefore does **not** advance the
   launcher cadence counter. This is undocumented current behavior; the oracle must capture it
   so stage 3 turns it into a decided, tested behavior.
4. **Episode state effects at the ladder**: the stagnation hook burns its single shot
   (`stagnationHookFired = true`, `:499`) *whatever the LLM answers*, and resets
   `graphStableCounter = 0` only on accept (`:503`). Both are stage-3 migration hazards
   (report V5) and must be pinned.
5. **Seeding**: `Monkey.java:697` seeds `mRandom = new Random(mSeed)` and `:731` seeds
   `RandomHelper.seed(mSeed)` — two streams from one seed. Agents draw from **both**:
   `RandomHelper` statics, and `ape.getRandom()` (`ApeAgent.java:322-323`, e.g. the
   component-trigger draw at `SataAgent.java:548`). **Verified 2026-08-03 (spike finding
   1.4-a)**: the agent-side draws are *not* uniform in how they reach that stream. Most go
   through the overridable `getRandom()` — the epsilon-greedy roulette (`:681`), the
   component trigger (`:548`), `handleNullAction` (`StatefulAgent.java:1695,1706,1711`) — but
   `egreedy()` reads `ape.getRandom()` **directly** at `:1330`, bypassing it. That one line is
   what this change edits in production (D5, INV-ORA-01).
6. **LLM seams**: `_llmRouter` is a protected field (`StatefulAgent.java:162`), constructed at
   `:194` only when `Config.llmUrl != null`. `LlmRouter(java.util.Random)`
   (`LlmRouter.java:123-161`) performs no I/O and constructs on the JVM
   (`LlmRouterTest.java:47`), but `selectAction()` (`:327`) touches
   `AndroidDevice.getDisplayBounds()` (`:341`) and `ScreenshotCapture` (`:362`) — device-only.
   The `shouldRoute*` predicates (`:232-281`) are pure Config+state logic plus
   `breakerAllows()` (`:292-302`); `LlmCircuitBreaker` uses `System.currentTimeMillis()`
   (`LlmCircuitBreaker.java:54,77,95`) — **wall-clock**, a determinism hazard.
7. **Config is a static-final wall** (`Config.java:30-44`): `configurations = new
   Properties(System.getProperties())` with `/data/local/tmp/ape.properties` +
   `/sdcard/ape.properties` layered in; ~140 `public static final` fields resolved once at
   class load. On a dev box neither file exists, so **JVM tests run on jar defaults plus
   system properties set before `Config` class-init**. `Config.set` cannot move a resolved
   `static final` (`ConfigTest.java:133`).
8. **Test infrastructure that already exists** (all in `src/test/java`, 785 `@Test`, 13
   `@Ignore` for Android runtime): surefire **excludes both vendored Android jars from the
   test runtime classpath** (`pom.xml:95-113`) — Android classes are compile-only and
   `NoClassDefFoundError` at runtime; the sanctioned workaround is a source stub
   (`src/test/java/android/graphics/Rect.java`). `PipelineParityTest.java:56-116`
   Unsafe-allocates a real `SataAgent`, injects `newState`/`timestamp`/`_coverageTracker`/
   `scoringContext`/`scoringPipeline` and successfully calls `adjustActionsByGUITree()` on the
   JVM — the closest precedent to this change. `MopData` loads real JSON fixtures on the JVM
   (`MopDataTest.java:229-239`, `src/test/resources/cryptoapp.apk.gh60-fresh.json`; org.json
   by design, `MopData.java:49-51`) and has `MopData.forTest(...)` (`:122,128`).
   `RandomHelperSeedTest` seeds the global stream. **No test calls
   `selectNewActionNonnull()` today, and no LLM HTTP stub exists** — tests script raw JSON
   strings into `parseResponse`, never a server.
9. **Hard JVM blockers for the full loop**: `MonkeySourceApe` cannot be class-loaded
   (`MonkeySourceApeForeignGuardTest.java:19-21` — its `UiAutomation` field);
   `StatefulAgent`'s constructor calls `ape.getMainApp()` (`ComponentName`) and
   `updateStateInternal` (`StatefulAgent.java:742`) requires `AccessibilityNodeInfo`. Deep
   SATA-chain branches reach `AndroidDevice.getFocusedStack()` (`SataAgent.java:1270,1510`).

Constraints: P1 (no framework — a driver, a stub, a file format); P3 (nothing shimmed in
production; the harness adapts to production, never the reverse); R7/R8 semantics of the
report; owner decisions D1–D6 untouched (this change interacts with none of them).

## Architecture

Everything lives in `src/test/java` and `src/test/resources`. New package:
`com.android.commands.monkey.ape.oracle`.

```text
ScenarioScript (synthetic app: ordered screens, widgets, transition table)
      │
      ▼
OracleDriver ── builds synthetic State/Graph via OracleScaffold (Unsafe + field injection,
      │         generalized from PipelineParityTest)
      │ per step:
      │   1. inject newState/timestamp per the script (newGUITree stays null — finding 2.1-a)
      │   2. call agent.selectNewActionNonnull()          ← the system under test (unchanged)
      │   3. record DecisionRecord {step, actionType, target, decisionSource, pickChannel, llm}
      │   4. apply scripted post-step bookkeeping (markVisited, timestamp++, scripted
      │      graphStableCounter), advance the script
      ▼
GoldenFile (NDJSON, org.json)
      ├── capture mode (explicit flag): write src/test/resources/goldens/<preset>/<scenario>.ndjson
      └── compare mode (default, mvn test): replay and assert record-by-record,
          first divergence reported as {preset, scenario, step, field, golden, actual}
```

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `OracleScaffold` | Unsafe allocation + hierarchy-walking field injection; synthetic `StateKey`/`State` builders and their graph registration; the injection profile that mirrors `StatefulAgent`'s constructor assignments | preset profile | wired `SataAgent` |
| `OracleSataAgent extends SataAgent` | test subclass overriding `getRandom()` (protected, `ApeAgent.java:322`) with a seeded `Random` — pins the second RNG stream; no other override | seed | agent under test |
| `ScenarioScript` | deterministic synthetic app: screens (widget lists → `ModelAction`s with `TestName` targets), transition table, per-step LLM script entries | hand-written per scenario | states + expectations |
| `ScriptedLlmRouter extends LlmRouter` | deterministic LLM: scripted routing verdicts and scripted `selectAction` results (accept/decline/timeout); never network, never screenshot, never breaker transitions | script | `ModelAction`/null |
| `OracleDriver` | the step loop: inject state, invoke `selectNewActionNonnull()`, record, apply scripted bookkeeping | agent + script | `List<DecisionRecord>` |
| `DecisionRecord` / `GoldenFile` | one NDJSON record per step; header record with preset/scenario/seed/fixture; writer (capture) and comparator (replay) | records | golden file / assertion |
| Per-preset capture tests (`ParityOracle{Aperv,Mop,Llm,LlmMop}Test`) | run the preset's scenarios in compare mode; capture mode behind the regeneration flag | goldens | pass/fail |
| `PreemptionGoldenTest` | the synthetic simultaneous-qualification state; asserts the hard order and the 3.3-1 cadence behavior | preemption scenario | pass/fail |
| `src/test/resources/goldens/README.md` | the regeneration procedure (deliberate act, never CI) | — | docs |

## Mapping: Spec -> Implementation -> Test

| Requirement / Invariant | Implementation | Test |
|-------------|---------------|------|
| Per-preset golden capture | `OracleDriver` + preset injection profiles | `ParityOracle{Aperv,Mop,Llm,LlmMop}Test` (compare mode is the test) |
| Golden file format | `DecisionRecord`, `GoldenFile` | `GoldenFileTest` (round-trip, comparator, first-divergence report) |
| Preemption-order golden incl. 3.3-1 | preemption `ScenarioScript` + direct field assertions | `PreemptionGoldenTest` |
| Deterministic LLM stubbing | `ScriptedLlmRouter` | `ScriptedLlmRouterTest` (no network, honors agent-side arguments, script exhaustion fails loudly) |
| Deliberate regeneration | capture mode behind `-Dape.oracle.regenerate`, goldens README | `GoldenFileTest` (default mode never writes) + review |
| INV-ORA-02 determinism | `OracleScaffold` seeding (both streams) | double-capture identity test in `OracleDriverTest` |
| INV-ORA-01 test-tree confinement | change discipline | `git diff --stat src/main/java` shows exactly the one-line `egreedy()` seam and nothing else, at verify |
| INV-ORA-01 seam stays overridable | `SataAgent.egreedy()` calling `getRandom()` | `EgreedySeamTest` (a subclass override intercepts the coin flip; the ladder completes with a null `ape`) |

## Goals / Non-Goals

**Goals:**
- Pin the current per-preset decision sequences (`aperv`, `mop`, `llm`, `llm_mop`) as
  committed golden files, reproducible under fixed seeds on the plain JVM (`mvn test`).
- Pin the hard preemption order of `selectNewActionNonnull` — budget-trivial > LLM new-state >
  LLM stagnation > LLM random > MOP launcher > SATA chain — on a synthetic state that qualifies
  all capturable mechanisms simultaneously (report Sec. 9.4). The component-trigger block, which
  sits between the launcher and the chain, is excluded (D6, finding 2.1-c) and costs no
  precedence coverage: it returns nothing, so no ordering it participates in is observable.
- Capture finding 3.3-1 (LLM preemption does not advance `_stepsSinceLauncherFiring`) and the
  stagnation-episode effects (single-shot burn on any answer; counter reset on accept) as
  **current** behavior.
- Give stages 2–3 a mechanical gate: their diffs land only with these tests green against the
  unmodified goldens (report Sec. 9.9).

**Non-Goals:**
- **No device-trace equivalence.** The goldens are harness-relative: they pin the decision
  logic under the harness's synthetic states and scripted LLM, not the byte sequence of a real
  device run. Real-device behavior additionally involves GUITree building, naming/refinement,
  throttling, and event injection — all off-JVM.
- No capture of naming/refinement, model evolution (`resolveNonDeterministicTransitions`),
  restart/teardown paths, fuzzing, or event generation.
- No coverage of SATA-chain branches that reach `AndroidDevice`
  (`getFocusedStack`, `SataAgent.java:1270,1510`) — scenarios steer clear; see Decision D6.
- **No coverage of the component-trigger block** (`SataAgent.java:547-551`): it is disabled in
  every arm of the phase-2 grid, unreachable under the committed fixture, and device-bound at
  dispatch. Owner decision of 2026-08-03 on finding 2.1-c; the three reasons are in D6 and in
  the spec's Purpose.
- No statistical/distributional oracle, no production seams, no CI golden regeneration, no
  changes to `openspec/specs/` main capabilities.

## Decisions

### D1 — Capture level: `selectNewActionNonnull()` on synthetic states, not the device loop

The full loop (`updateStateInternal`, `StatefulAgent.java:742`) is not drivable off-device:
it requires `AccessibilityNodeInfo`, and the agent constructors require an un-class-loadable
`MonkeySourceApe`. The largest capturable slice is the **precedence ladder itself** — which is
exactly the object stages 2–3 restructure. The harness Unsafe-allocates the agent
(`PipelineParityTest` precedent, proven at HEAD) and injects the fields the ladder reads;
each step's `State` is synthetic (Unsafe-built `StateKey`/`State` with `ModelAction`s carrying
local `TestName` targets — the established idiom of `StateTest`/`LlmRouterDeadPairTest`).
Alternative considered: driving `resolveNewAction()` (`StatefulAgent.java:1475`) to also pin
telemetry emission — rejected: it drags in emission plumbing that stage 4 will deliberately
replace, and the migration gate for stages 2–3 is the *selection*, not the line format.
`adjustActionsByGUITree()` scoring parity is already locked by `PipelineParityTest` and stays
where it is.

### D2 — Preset realization: injection profile + jar-default Config, asserted per preset

Because the constructor never runs, a preset is realized by the **injection profile** — the
combination of fields the harness sets, mirroring `StatefulAgent.java:179-208`:

| Preset | `_mopData` | `_llmRouter` | other |
|---|---|---|---|
| `aperv` | `null` | `null` | `_budgetTracker` per `Config.activityBudgetEnabled` (default true); `scoringPipeline = ScoringPipeline.fromConfig(null, ctx)` |
| `mop` | `MopData.load(<fixture>)` | `null` | same |
| `llm` | `null` | `ScriptedLlmRouter` | same |
| `llm_mop` | `MopData.load(<fixture>)` | `ScriptedLlmRouter` | same |

The MOP fixture is `src/test/resources/cryptoapp.apk.gh60-fresh.json` (already loaded on the
JVM by `MopDataTest`). Config statics the ladder reads (`activityBudgetEnabled`,
`activityTriggerEnabled`, `activityTriggerStagnationStep`, `activityTriggerMaxPerRun`,
`componentPercentage`, `graphStableRestartThreshold`, epsilon and scoring weights) stay at
**jar defaults**, and each preset test asserts the values it depends on in a guard test, so a
future default change breaks the guard, not silently the golden. Where a preset's arm sets a
key the ladder reads to a non-default value (notably `llmPercentage=0.7` in the decisive-run
LLM arms), the value is **not** reproduced via Config: the routing verdict is scripted (D3),
which removes the dependence entirely. Alternative considered: one forked surefire execution
per preset with `systemPropertyVariables` — deferred; it buys nothing while the scripted
router owns the only preset-divergent ladder-read key, and it stays available (documented in
the goldens README) if a future preset diverges on a non-LLM key.

### D3 — LLM stub: scripted routing verdicts AND scripted results, honoring agent-side arguments

`ScriptedLlmRouter extends LlmRouter` (constructor is JVM-safe; `Config.llmUrl` may stay null
— the stub never uses the client). It overrides:

- `shouldRouteNewState(isNewState)` → `script.routeNewState(step) && isNewState` — the
  agent-side argument is honored so the `_isNewState` contract stays exercised;
- `shouldRouteStagnation(counter, fired)` → `script.routeStagnation(step) && !fired` — honors
  the single-shot flag so the burn semantics at `SataAgent.java:499` manifest in the golden;
- `shouldRouteRandom()` → `script.routeRandom(step)` — replaces the `random.nextDouble() <
  Config.llmPercentage` draw;
- `selectAction(...)` → scripted verdict: **accept** (returns a `ModelAction` chosen from the
  passed `actions` list by a deterministic selector named in the script), **decline** (returns
  null), **timeout** (returns null; recorded distinctly in the `DecisionRecord`).

Rationale: (i) the real `selectAction` is device-bound from its first steps (screenshot); (ii)
the real `shouldRouteRandom` consumes an RNG draw and the real breaker consumes wall-clock —
both are determinism hazards the goldens must not depend on; (iii) the predicates' internal
logic is already unit-covered (`LlmRouterTest`, `LlmCircuitBreakerTest`) — what the oracle
must pin is the **ladder**: hook order, preconditions, and the state effects of each verdict.
Consequences, stated honestly: at the selection level, decline and timeout are the same
observable (null → fall-through); they are distinguished only as provenance in the golden.
Breaker transitions are **out of oracle scope** (`breakerAllows()` is private and the stub
records nothing into it) — scripts never simulate a breaker-open episode. Alternative
considered: stub at the HTTP layer (fake server / scripted `SglangClient`) — rejected:
`SglangClient` is a `private final` field with no seam (`LlmRouter.java:53,128`), adding one
would touch production, and the screenshot step would still block the path before HTTP.

### D4 — Golden format: NDJSON, one file per preset×scenario, header + one record per step

`src/test/resources/goldens/<preset>/<scenario>.ndjson`. First record is a header:
`{"kind":"header","preset":"llm_mop","scenario":"baseline","seed":42,"fixture":"cryptoapp.apk.gh60-fresh.json","capturedAt":"<commit>"}`.
Each subsequent record:

```json
{"step":7,"actionType":"MODEL_CLICK","target":"//*[@resource-id='btn_ok']",
 "decisionSource":"MOP","pickChannel":"short_circuit_unvisited","llm":"declined"}
```

There is no `componentTrigger` field: the block that would have set it is out of scope (D6,
finding 2.1-c).

- `actionType`: the `ActionType` name; launcher steps record `EVENT_TRIGGER_ACTIVITY` plus the
  candidate class name in `target`.
- `target`: the action's `Name.toXPath()` (the identity convention of `UICoverageTracker.widgetId`
  and the dead-pair ban); absent for targetless actions.
- `decisionSource`/`pickChannel`: read from the returned `ModelAction`'s provenance fields
  (set at the pick sites); absent for non-`ModelAction` returns.
- `llm`: `accepted | declined | timeout | not_routed`; absent for presets without the router.

org.json (already a test dependency) for read/write; one line per record, written with the
same serializer that reads them (round-trip tested). NDJSON over a single JSON array because
divergence reporting is per-line and diffs are reviewable per step. Chosen over Java
serialization (opaque, brittle across stage-3 class moves — the golden must outlive the
classes that produced it) and over reusing `[APE-STEP]` text lines (stage 4 deletes that
format; the oracle must not couple to it).

### D5 — Determinism: both RNG streams pinned; capture == replay by construction

`RandomHelper.seed(seed)` in `@Before` (per `RandomHelperSeedTest`) pins the static stream —
which is also what the EARLY_STAGE roulette draws from (`RandomHelper.randomPickWithPriorityRecorded`,
`SataAgent.java:1645`), the rung a fresh state actually selects through.
`OracleSataAgent.getRandom()` returns a per-run `new Random(seed)` and pins the agent-side
stream without needing a `MonkeySourceApe` (the field `ape` stays null). What that override
reaches, verified in the tree rather than assumed (spike finding 1.4-a): the epsilon-greedy
roulette (`SataAgent.java:681`), the epsilon-greedy coin flip (`:1330`, once the seam below is
in place), and `handleNullAction` (`StatefulAgent.java:1695,1706,1711`). The component-trigger
draw (`:548`) is **not** among them, correcting the earlier reading of this list: that draw sits
behind `Config.componentPercentage > 0`, which is false under the jar defaults this harness runs
on, so the conjunction short-circuits before it (finding 2.1-c). Nothing is lost — a draw that
never happens cannot desynchronize the stream.

**It did not reach the epsilon-greedy coin flip, and that is why this change touches production
once.** `egreedy()` reads `ape.getRandom()` directly (`SataAgent.java:1330`) instead of the
`getRandom()` every other draw goes through, so the prescribed wiring threw
`NullPointerException: ... because "this.ape" is null` the first time the chain fell past
EARLY_STAGE — taking with it *both* legs of the rung, the least-visited pick and the roulette.
The harness cannot answer this with an `ape`: `MonkeySourceApe` does not class-load off device,
and stubbing `android.*` toward it did not converge in three steps, the last of which would have
required inventing a `Build.VERSION.SDK_INT` the production code branches on. Owner decision of
2026-08-03: change the line to `getRandom()`, in this stage. It is behavior-identical in
production (`ApeAgent.getRandom()` is literally `return ape.getRandom()`) and it follows what
this codebase already does for testability — `State.beatsLeastVisited`,
`ForeignActivityGuard.shouldModel` and `SataAgent.requiresSynthesizedResolution` are all pure
seams extracted for exactly this reason. The carve-out is recorded in INV-ORA-01 and guarded by
a test that fails if the seam is ever re-inlined. The alternative — leaving the rung outside the
boundary — was rejected because `State.greedyPickLeastVisited` sits behind that flip, and the
artifact-vs-code verification (AC7) names it the highest-leverage unguarded surface of stage 3:
an oracle that cannot reach it would be green by construction across exactly the change most
likely to break it. The
double-capture identity test (same scaffold, seed, scenario, preset run twice ⇒ identical
record lists) is part of the suite, so nondeterminism in the harness itself is a test failure,
not a mystery diff. `StringCache` state is snapshot/restored if a scenario ever reaches input
generation (`StringCacheTest` convention).

### D6 — Capture boundary: loud failure, no silent skip, no production shadowing

Scenarios must keep the chain within JVM-safe rungs; a scenario that drives selection into a
device-reaching branch fails with `NoClassDefFoundError` at capture time and is **redesigned**
— never `@Ignore`d into a hole, and never "fixed" by placing a test-classpath class that
shadows a production class (`AndroidDevice` and friends are production code; shadowing them
would silently change the system under test). Missing `android.*` **framework** types needed
by otherwise-pure paths MAY be added as source stubs under `src/test/java/android/**` — the
established `Rect.java` pattern; framework stubs mimic AOSP semantics and carry a javadoc note.
The design accepts that some SATA-chain rungs (`selectNewActionBackToActivity` restart paths,
trivial-activity navigation reaching `getFocusedStack`) stay uncaptured; the spec names the
boundary and task 1 verifies exactly which rungs the scenarios exercise.

**Where the boundary actually fell** (task-1 spike, 2026-08-03). `selectNewActionBackToActivity`
is safe *because of its own guard*: it returns at `SataAgent.java:1267-1269` while
`backToActivity` is null, before touching `AndroidDevice.getFocusedStack()`. That field is
therefore a scenario-level control, and a scenario that sets it leaves the boundary — the
scaffold documents it as such. Buffer, both early-stage rungs, trivial-activity (an empty graph
has fewer activity nodes than `trivialActivityRankThreshold`) and `handleNullAction` all execute
unmodified; epsilon-greedy executes once the D5 seam is in place. Two further constraints the
spike established:

- **1.1-a — the ladder cannot be entered reflectively.** `Class.getDeclaredMethod` runs
  `getDeclaredMethods0`, which resolves *every* declared signature of the class, and
  `SataAgent.onActivityBlocked(ComponentName)` makes that die with `NoClassDefFoundError:
  android/content/ComponentName`. A compiled call from a subclass resolves only the invoked
  signature. `OracleSataAgent` is therefore not merely the RNG seam — it is the only entry point
  into `selectNewActionNonnull()`, and its `ladder()` accessor is load-bearing.
- **1.2-b — action validity and priority are scripted preconditions.** The oracle enters below
  `adjustActionsByGUITree()`, so nothing has assigned either: `valid` defaults to false and
  `ENABLED_VALID` excludes it, while `State.countActionPriority` throws `IllegalStateException`
  on any included action whose priority is ≤ 0. The script declares both per action, which is
  also precisely what keeps the goldens independent of every scoring weight (the boundary the
  capture requirement draws in the spec).

**Three more constraints the group-2 scaffold established** (2026-08-03), all recorded in
`OracleScaffold`'s javadoc ledger:

- **2.1-a — there is no GUITree builder, and none is needed.** Within the ladder `newGUITree`
  has exactly one reader: it is passed to `_llmRouter.selectAction`, which the scripted router
  overrides (D3). Building a real one is also impossible off device — a `GUITree` is assembled
  from `AccessibilityNodeInfo`. The field therefore stays null for every preset, and the cost is
  stated rather than hidden: nothing in the goldens exercises tree-derived behavior, which is
  already a Non-Goal.
- **2.1-b — synthetic states are registered into the graph by mirroring
  `Graph.getOrCreateState`, not by calling it.** That production path builds its `State` through
  `new State(stateKey)`, whose `buildActions` calls `NamerFactory.decodeActions`, which throws
  `IllegalArgumentException` on any `Name` that is not an `ActionPatchName` — and the harness's
  names are local xpath-identity stubs, the `StateTest`/`LlmRouterDeadPairTest` idiom. The
  scaffold performs the same registration steps (`keyToState`/`idToState`, `addActivity`,
  `addActions`) directly on the graph's fields. This is load-bearing, not cosmetic: without an
  `ActivityNode`, `Graph.markVisited(State,int)` NPEs, and without the action inventory,
  `Graph.markVisited(ModelAction,int)` throws its sanity check — both of which the driver's
  bookkeeping calls (D7).
- **2.1-c — the component-trigger block is outside the boundary**, by owner decision. It never
  fires: `ape.componentPercentage` is set by no arm of the phase-2 grid (the key exists only in
  `aperv-tool`'s mapping, `tool.py:101`, and is absent from the 18 `ARM_DEFINING_KEYS` and both
  arm-flag dicts), so every arm runs on the jar default `0.0`. Even with the gate forced open the
  committed fixture yields zero tuples — no receivers, no services, and its one provider has
  `reachesTarget=false`, filtered at `StatefulAgent.java:1246` — so `triggerMopComponent()`
  returns at `:1266` leaving `componentTriggerIndex` untouched; and had a tuple existed, dispatch
  is device-bound (`android.content.Intent`, `AndroidDevice.executeCommandAndWaitFor`, whose
  wrapper catches `Exception` and not the `NoClassDefFoundError` actually thrown). Excluding it
  costs no parity: the block returns nothing, and its short-circuiting conjunction consumes no
  RNG draw, so it can shift neither a decision nor the agent stream. Capturing it anyway would
  have cost a forked surefire execution, a new fixture, and a dispatch override — three
  concessions for a block no experiment runs.

### D7 — Post-step bookkeeping is scripted, minimal, and explicit

Between steps the production loop performs model/graph bookkeeping the ladder observes
(visited marks, timestamp, `graphStableCounter`, `_isNewState`). The driver replays a minimal,
explicit subset: `graph.markVisited(state, t)` / `markVisited(action, t)`, `timestamp`
increment, scripted `graphStableCounter` values (the counter's real update logic lives in
`updateStateInternal`, off-JVM — the script sets it per step so stagnation scenarios are
expressible), and `_isNewState` per the script. The exact injected-field and bookkeeping list
is enumerated by the task-1 spike and frozen in `OracleScaffold` javadoc; anything the ladder
reads that the driver fakes is listed there — the honesty ledger of the harness.

### D8 — Regeneration is a deliberate, documented act

Default `mvn test` **only compares**; the comparator never writes. Capture mode requires
`-Dape.oracle.regenerate=true` and rewrites the golden files in the working tree, which then
show up in `git status` and must be committed with a message stating *why the behavior
legitimately changed*. The procedure (when regeneration is legitimate, how to run it, what to
review in the diff, who decides) lives in `src/test/resources/goldens/README.md`. CI and the
standard build never set the flag. During stages 2–3 the goldens and scenario scripts are
frozen; only the injection scaffold may be adapted to renamed/moved fields (the goldens must
outlive the class structure — that is their entire point).

## API Design

### `OracleDriver.run(SataAgent agent, ScenarioScript script) -> List<DecisionRecord>`

Preconditions: agent wired by `OracleScaffold` for a preset; both RNG streams seeded; script
non-empty. Postconditions: one `DecisionRecord` per scripted step, in order; the agent's
episode fields (`stagnationHookFired`, `_stepsSinceLauncherFiring`) reflect the run. Errors:
any exception from `selectNewActionNonnull` aborts the run and fails the test (including
`BadStateException` — a scenario with no available action is a scenario bug).

### `GoldenFile.compare(Path golden, List<DecisionRecord> actual) -> void (assert)`

Preconditions: golden exists (a missing golden fails with the regeneration instructions, it
does not auto-capture). Postconditions: pass ⇔ record-by-record equality including count.
Error report: `preset, scenario, step, field, goldenValue, actualValue` for the **first**
divergent field, plus a trailing count of total divergent records.

### `ScriptedLlmRouter` script entry (per step)

`{routeNewState, routeStagnation, routeRandom: boolean; verdict: ACCEPT(selector) | DECLINE |
TIMEOUT}` — script exhaustion or an un-consulted mandatory entry fails the test loudly
(a scenario that expects an LLM consultation that never happens is a divergence, not a skip).

## Data Flow

Scenario script → scaffold builds preset-wired agent + synthetic states → driver loop
(inject → select → record → bookkeeping) → `List<DecisionRecord>` → capture mode: NDJSON
golden written under `src/test/resources/goldens/`; compare mode: `GoldenFile.compare`
asserts against the committed golden. Stages 2–3 change production code, rerun `mvn test`,
and the same goldens gate the diff.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `NoClassDefFoundError: android.*` | scenario reached a device-only branch | test fails loudly | redesign the scenario or add a framework source stub (never shadow production classes) |
| Golden file missing | compare mode | fail with regeneration instructions | run capture mode deliberately, review diff, commit |
| Divergence | compare mode after a production change | first-divergence report | stage 2/3: fix the migration; only a *decided* behavior change regenerates |
| Script exhaustion / unconsumed LLM entry | `ScriptedLlmRouter` | fail loudly | fix the scenario script |
| `BadStateException` | ladder found no action | fail the run | scenario must always leave an available action |
| Harness nondeterminism | double-capture identity test | fails the suite | fix the harness before trusting any golden |

## Risks / Trade-offs

- [Goldens are harness-relative, not device-trace-relative] → stated as a Non-Goal and in the
  spec's Purpose; the gate they provide is "same decisions from the same inputs through the
  restructured code", which is precisely the stage-2/3 regression class (report Sec. 9.9).
  Device-level validation stays with the existing smoke path via rv-platform.
- [Unsafe + reflection injection is brittle against stage-3 renames] → deliberate: the
  *scaffold* is the adaptation layer and may change; goldens and scenario scripts are frozen.
  The scaffold's injected-field list is documented in one place (D7) so a rename is a
  one-file fix.
- [Scripted predicates bypass real `shouldRoute*` internals] → those internals are already
  unit-covered; the oracle pins the ladder. The agent-side arguments (`isNewState`, `fired`)
  are honored by the stub so the agent-side contracts still manifest (D3).
- [Breaker and `llmPercentage` behavior not in goldens] → out of scope by design (wall-clock
  and RNG-draw hazards); named in the spec so nobody reads the goldens as covering them.
- [Deep SATA rungs uncaptured] → boundary named in spec; the preemption golden plus per-preset
  scenarios cover every *returning* mechanism above the SATA chain — the component-trigger block
  is the one exclusion and returns nothing (finding 2.1-c) — and the chain's buffer/early-stage/
  epsilon-greedy rungs, which is where stage 3's restructuring risk concentrates (V1).
- [Golden churn if jar defaults change under this change's feet] → per-preset Config guard
  assertions (D2) fail before the golden mysteriously diverges.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit | `GoldenFile` round-trip/comparator, `ScriptedLlmRouter` semantics, scaffold builders | plain JUnit | ~15 tests |
| Harness integrity | double-capture identity; Config guard per preset | JUnit | ~6 tests |
| Golden (the product) | 4 preset scenario sets + preemption golden (incl. 3.3-1, single-shot burn, counter-reset-on-accept, launcher-cadence fire) | compare mode under `mvn test` | ~10 scenarios |

## Open Questions

**None blocking.** Two items are resolved by the task-1 spike rather than here, by
construction: (i) the exact injected-field + bookkeeping list for driving
`selectNewActionNonnull` multi-step (D7 freezes it in scaffold javadoc); (ii) which SATA-chain
rungs the baseline scenarios exercise without hitting the device boundary (D6). Neither
changes the architecture; both change only scenario design.
