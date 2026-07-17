# Design: refinement-crash-recovery

## Context

Diagnosis: `docs/20260716_investigacao_truncamento_600s_llm_tap.md`. At cmpv2 (LLM arm, 600s),
~45% of runs die early. The chain: a non-deterministic transition triggers a naming refinement
(`StatefulAgent.checkNonDeterministicTransitions`, `StatefulAgent.java:752-768`) → the model
rebuild removes states (including the agent's current state) → an uncaught exception ends the
exploration loop → `Monkey.run`'s `finally` (`Monkey.java:775-786`) calls
`MonkeySourceApe.tearDown()`, whose `saveActionHistory` replay throws
`IllegalStateException: Cannot find widget` (`ActionRecord.resolveModelAction`, `Model.java:87` →
`GUITree.pickNodes`, `GUITree.java:168`; `saveActionHistory`'s own `catch (IOException)` at
`Model.java:108` does not intercept it) — and, by Java `finally` semantics, this second exception **replaces** the
original loop exception. The reported stack is the teardown occurrence; the loop-killer's identity
is destroyed. The masking/teardown replay path is 2019 upstream code; the trigger regime (LLM
coordinate taps navigating into repeating-row substrates) is new.

Follow-up analysis (`docs/20260717_analise_terminador_refinement_crash_recovery.md`) narrowed the
in-loop terminator's location and predicted its identity before the F-D gate: all 9 fatal cmpv2
traces die **inside `Model.rebuild()`** (transition re-adding phase — `Start rebuilding model`
present, `Rebuilding model finished` absent, 9/9; healthy controls balanced), i.e. before
`updateModel`/`validateAllNewActions` ever run on the fatal step. The predicted killer is a
quarantine hole of `MODEL_LLM_TAP`: the rebuild replay re-anchors the tap's ephemeral edge via
`State.getAction(MODEL_LLM_TAP)` (`Model.java:283→340` → `State.java:489`), which throws
`IllegalStateException("No such action [MODEL_LLM_TAP]")` with no widget dump — LLM-arm-exclusive,
matching every proven fact (0/2715 CFW without LLM; refinement volume does not predict death;
maskan's early determinism). See Open Questions for the registered prediction and its alternatives.

Fix nomenclature note (three vocabularies in play): the *report's* F-C is
invalidate-records-at-refinement-time — rejected here as alternative (b) of D3, where it is called
"F4"; the *report's* F-D is "fix the real in-loop terminator" (a code fix — per the report, the one
that eliminates truncation — now with a predicted address, see above); *this design's* F-C is the
tolerant rebind guard in `validateNewAction`, and *this design's* F-D is the maskan gate that
captures the terminator's identity. The design initially bet that its F-C site coincided with the
report's F-D terminator; the follow-up analysis refuted that bet (prediction: the guard never
fires — `RebindFailures total=0`).

An exception-masking sweep found four HIGH sites on the main path:

1. `Monkey.java:777-786` — `finally` with two throw-capable statements; the rotation restore runs
   *before* teardown, so a binder failure also skips teardown entirely.
2. `MonkeySourceApe.tearDown` (`MonkeySourceApe.java:221-231`) — `disconnect()` is step 1 and can
   throw `IllegalStateException("Already disconnected!")`; a throw skips the agent teardown that
   persists the model.
3. `StatefulAgent.tearDown` (`StatefulAgent.java:1644-1653`) — eight sequential steps, none
   guarded; a throw at any step skips all later persistence/diagnostics.
4. `Naming.java:496-503` — `finally` logs `results.getNameSize()` while `results` is null whenever
   `namingInternal` threw (e.g. `"A node has no namelets"`, `Naming.java:517`); the NPE replaces
   the original exception on the per-step naming path.

Constraints: Java 11; no new dependencies; the JUnit suite runs on the JVM (no Android runtime), so
`Monkey.run` itself is validated on-device; `app.maskan.chat` reproduces the crash
deterministically in ~20–30s and serves as the live fixture.

## Architecture

No new components. Failure-path hardening at six sites in five existing classes (post-gate
architecture — F-C was removed on D7 outcome 1 and replaced by the quarantine fix):

```
Monkey.run
  └─ finally
       ├─ [guard] rotation restore (injectEvent)        ← F-A
       └─ [guard] MonkeySourceApe.tearDown()            ← F-A
            ├─ [step] disconnect()                      ← step isolation
            ├─ [step] mAgent.tearDown()  = StatefulAgent.tearDown
            │     ├─ [step] LLM summary … printCounters (8 steps)   ← step isolation
            │     ├─ saveGraph()          (existing IOException guards kept)
            │     └─ saveActionHistory() → Model.saveActionHistory
            │           └─ [per-record guard] ActionRecord.resolveModelAction   ← F-B
            └─ [step] writers / loggers / vis timeline

Model.rebuild (the proven in-loop terminator, D7 outcome 1)
  ├─ collectReplayTreeTransitions: ephemeral edges skipped + purged from
  │  Graph.treeTransitionHistory (rebuildHistory cannot resurrect them)         ← quarantine fix
  └─ Model.update(ModelAction, GUITreeAction): ephemeral reference returned
     unchanged (payload-bound), covering updateModel's re-anchors               ← quarantine fix

Naming.naming
  └─ finally log moved into the success path                                    ← masking fix
```

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `Monkey.run` finally | Restore rotation, flush APE state, never mask | in-flight `Throwable` | original exception propagates |
| `MonkeySourceApe.tearDown` | Step-isolated source teardown | — | all steps attempted |
| `StatefulAgent.tearDown` | Step-isolated agent teardown | — | all steps attempted |
| `Model.saveActionHistory` | Persist history, skip unresolvable records | `List<ActionRecord>` | `action-history.log` + `total/skipped` summary |
| `Model.collectReplayTreeTransitions` | Rebuild replay collection, ephemeral-free (INV-MODEL-16) | removed `StateTransition`s | non-ephemeral `GUITreeTransition`s; ephemeral ones purged from tree history |
| `Model.update(ModelAction, GUITreeAction)` | Post-rebuild re-anchor, ephemeral-safe | agent action reference | re-anchored action, or the ephemeral reference unchanged |
| `Naming.naming` | Per-step naming with non-masking diagnostics | `GUITree` | `NamingResult` |

## Mapping: Spec -> Implementation -> Test

| Requirement / Invariant | Implementation | Test |
|-------------|---------------|------|
| INV-EXPL-16 (no masking) | guards in `Monkey.run` finally | device smoke (maskan): reported stack = in-loop exception |
| INV-EXPL-29 (step isolation) | `safeStep` in `MonkeySourceApe.tearDown`, `StatefulAgent.tearDown` | `StatefulAgentTearDownTest`: failing step → later steps still run |
| INV-MODEL-16 (ephemeral quarantine through rebuild) | `Model.collectReplayTreeTransitions` filter + `Graph.removeFromTreeHistory` + ephemeral early-return in `Model.update` | `ModelRebuildEphemeralQuarantineTest`: ephemeral edge skipped+purged, ephemeral reference unchanged, non-ephemeral behavior pinned |
| Tolerant Action-History Persistence + INV-MODEL-15 | per-record guard in `Model.saveActionHistory` | `SaveActionHistoryToleranceTest`: stale record skipped, file complete, `skipped=1` |
| Naming finally fix | log moved inside try in `Naming.naming` | `NamingDiagnosticsTest` (or code-inspection task if not unit-reachable) |
| F-D loop-killer identity | maskan run with F-A build | device smoke gate: logged in-loop stack compared against the registered prediction (D7 / Open Questions) |

## Goals / Non-Goals

**Goals:**
- The exception reported for a failed run is the exception that ended the loop.
- A teardown-step failure costs only that step's artifact, never the rest.
- A naming refinement can no longer terminate a run (loop side) or abort history persistence
  (teardown side).
- Telemetry (`skipped=N`, ephemeral-drop count at rebuild) quantifies refinement-induced data
  loss per run.

**Non-Goals:**
- Re-resolving stale descriptors against the *current* naming (report's F2) — rejected below (D3).
- Fixing MED/LOW sweep findings off the main path (LLM side-path swallows, offline loaders).
- The rv-android exit-code decoupling fix (other repository).
- Changing refinement/rebuild semantics (`Model.rebuild` counters etc. stay untouched).
- Freshness checks at dispatch (`MonkeySourceApe.java:927-928` and `ApeAgent.checkInput` read
  `getResolvedNode()` without `isResolvedAt`) — defence-in-depth against the whole
  stale-resolution class, recorded as debt and deferred (P1: no speculative guards without
  evidence; the Group-2 maskan gate may supply it).

## Decisions

**D1 — Guard the `finally` in place; do not use `addSuppressed`.** Once no teardown statement can
throw out of the `finally`, the original exception propagates naturally to `Monkey.main`'s
`catch (Throwable)` (`Monkey.java:616-621`), which already prints it. `addSuppressed` would require
restructuring `run()` to capture and rethrow; guarding two statements is smaller and equivalent.
Alternative rejected: logging the in-flight exception in a new `catch` before the `finally` —
redundant once masking is impossible.

**D2 — Step isolation via a private `safeStep(String label, Runnable step)` helper, duplicated in
`MonkeySourceApe` and `StatefulAgent`.** Each teardown statement becomes
`safeStep("saveGraph", this::saveGraph)`. The helper catches `Throwable`, logs the label and the
full stack trace. Duplicating ~6 lines in two classes beats a new cross-cutting utility class
(P1); `Monkey.run` gets two inline try/catch blocks instead (AOSP-derived file, keep it plain).

**D3 — F-C is *tolerate*, not *re-resolve* and not *drop-at-refinement*.** Alternatives:
(a) re-resolve stale descriptors against the current naming after each refinement (report's F2) —
medium risk, touches rebuild, and cannot always succeed (the refined naming may genuinely split
the old descriptor into several); (b) invalidate history records at refinement time (report's F4)
— loses records that would still resolve fine (most do; the failing record is the exception, not
the rule); (c) tolerate failures at the two consumption points (revalidation, persistence), count
and report them. Chosen: (c) — minimal, cannot lose data that was resolvable, and converts an
availability bug into telemetry.

On the loop side the failing action MUST be **explicitly invalidated** (`setValid(false)`),
mirroring the existing rejection branch (`StatefulAgent.java:1337-1339`). Merely leaving it
unresolved does NOT exclude it from selection, for four verified reasons: (1) the `valid` flag is
sticky across steps — its only writers are `StatefulAgent.java:1334/1338`, there is no per-step
reset; (2) actions survive a model rebuild by `StateKey` identity (`Graph.getOrCreateState`,
`Model.update`) carrying `valid=true` from the last successful resolve; (3) no `ActionFilter`
consults resolution state — every filter gates on `isValid()` alone; (4) a failed
`State.resolveAction` throws before `resolveAt` runs, so `resolvedNode`/`resolvedGUITreeAction`
retain the **previous step's** values (stale, non-null). Rejected sub-alternative — leave
unresolved and trust the filters: a sticky-valid action stays selectable (SataAgent's
least-visited/roulette return it without re-validation; an unresolved action is typically
least-visited, making it an attractor) and dispatchable (`MonkeySourceApe.java:927-928` reads
`getResolvedNode()` with no freshness check), producing taps at obsolete coordinates and feeding
stale nodes into LLM prompts (`ApePromptBuilder.java:794`) — trading an honest crash for silent
corruption of exactly the data cmpv2 measures.

*Gate postscript (2026-07-17):* the gate returned outcome 1 (`RebindFailures total=0`, terminator
upstream inside `Model.rebuild`), so the revalidation-point half of (c) guarded a failure that
does not occur and was removed per P1; the persistence half (F-B) stands and fired on the gate
run itself (`skipped=2`). The loop side is instead fixed at the true site by the ephemeral
quarantine extension (INV-MODEL-16, task 2.4).

**D4 — Guard placement for the loop side: `validateNewAction` (`StatefulAgent.java:1332`).**
Line 1332 is the **only** `State.resolveAction` call site in `StatefulAgent` — the guard is
exhaustive by construction. The guard covers `resolveAction` and *nothing else*: the throttle
argument (`getThrottleForNewAction`) is computed into a local **before** the `try`, because it
throws `IllegalStateException("Oops")` (`StatefulAgent.java:1524`) when the action does not belong
to `newState` — a model-integrity violation, not a rebind failure. (The 2026-07-17 analysis
refuted "Oops" as the terminator for the cmpv2 arm: every `validateNewAction` funnel in `SataAgent`
delivers actions of `newState` itself — `validateAllNewActions`, `handleNullAction`'s
`validatedActionFilter`, and the buffer path returns directly at `SataAgent.java:491` without
validation, its `check != action` reference test at `StatefulAgent.java:503` bailing to `null`;
zero `Oops` occurrences in 9/9 fatal traces. The keep-it-outside-the-try reasoning stands as gate
hygiene regardless.) Leaving it inside the `try` (verified empirically)
reclassifies that bug as a stale binding, invalidates the action and lets the run continue, i.e.
the fix would blind its own gate. Pinned by `ValidateNewActionToleranceTest`
`testModelIntegrityViolationIsNotReclassifiedAsRebindFailure`. (`resolveNewAction` and `adjustActionsByGUITree` never call
`State.resolveAction`; `StatefulAgent.java:1454` is a priority computation over
`GUITree.getNodes`, reached only for actions already resolved at the current timestamp, so it
cannot fire the rebind failure.) `handleNullAction`'s `validatedActionFilter` and both
`RandomAgent` selection paths also funnel through `validateNewAction`, so no second guard site
exists. The F-D maskan gate remains the ground truth on the terminator's identity — but the
2026-07-17 analysis predicts the terminator sits entirely **upstream** of this guard (inside
`Model.rebuild`, before `validateAllNewActions` runs on the fatal step), so the expected gate
outcome is `RebindFailures total=0` with a stack that never passes through `validateNewAction`.
In that outcome this guard is a speculative guard for a failure that does not occur and MUST be
removed, not extended (P1) — see D7 and task 2.3. *Gate postscript (2026-07-17): outcome 1
confirmed; the guard, its counter and its test were removed (task 2.3). This decision's analysis
of the call-site topology remains valid history.* Structurally, the guarded failure is also
near-impossible in-loop: a `newState` action's target belongs to the `StateKey`, which is derived
from the same `Name`s as the state's trees — both sides are re-derived together on rebuild. A
frozen-`Name`-against-renamed-tree mismatch (the actual `Cannot find widget` precondition) exists
only in `ActionRecord` replay, i.e. the teardown path covered by F-B.

**D5 — Per-record guard catches `RuntimeException`, not `Throwable`.** The known failure modes
are `IllegalStateException` thrown from `ActionRecord.resolveModelAction` (`GUI action should not
be null`, `Model.java:81`) and from `GUITree.pickNodes` reached via `Model.java:87` (`Cannot find
widget`, `GUITree.java:140/154/168/186`). The existing `catch (IOException)` in
`saveActionHistory` (`Model.java:108`) does **not** intercept them — they escape the method today
and abort teardown step 4, which is what makes the per-record guard necessary; catching
`Throwable` in a data-writing loop could swallow OOM/StackOverflow that must surface. Teardown
step guards do catch `Throwable` — there, completing the remaining steps outweighs surfacing the
error type precisely, and every catch logs the full stack.

**D6 — Naming fix: move the `Logger.dformat` from `finally` into the success path** (after
`namingInternal` returns). The log line is a per-step diagnostic about the produced result; when
there is no result there is nothing to log. Null-guarding inside `finally` was rejected: it keeps
throw-capable formatting on the failure path for no benefit. While moving the line, fix its
duplicated argument: it currently passes `results.getNameSize()` for both `%d names` and
`%d nodes`; the second argument should be `results.getNodeSize()`.

**D7 — F-D ordering: unmask first, then confirm.** The in-loop terminator's identity is inference
(the masking destroyed it); the gate turns it into ground truth. (Process note: the original
sequencing — F-A alone → gate → only then F-B/F-C — was not honoured; Groups 3/4 were implemented
before the gate ran. The 2026-07-17 analysis was the corrective step.) Gate protocol: build the
current jar → run maskan → read the logged in-loop stack and `RebindFailures total=N` → also record
whether the fatal step's `Start rebuilding model` is followed by `Rebuilding model finished`
(marker boundary). Registered prediction (before the gate): the stack is
`IllegalStateException: No such action [MODEL_LLM_TAP]` at `State.getAction` (`State.java:489`) ←
`Model.rebuild` (`Model.java:340`, replay loop at `:283`) ← `NamingFactory.rebuild:251` ←
`resolveNonDeterminism:157` ← `resolveNonDeterministicTransitions:365` ←
`checkNonDeterministicTransitions:765`, with `N=0`. Outcomes: (1) prediction confirmed → remove
F-C and its INV-EXPL-30 delta; implement the real fix — exclude ephemeral-action
`GUITreeTransition`s from the rebuild replay and guard the ephemeral `lastAction` rebind in
`updateModel` (`StatefulAgent.java:252` → `Model.java:411`, latent hole #2) — with a
rebuild+tap-edge test (none exists today; `GraphEphemeralActionTest` covers only
markVisited/addTransition). (2) Stack lands in `validateNewAction` with `N>0` → F-C confirmed as
designed (this requires explaining the absent in-loop dump — e.g. empty `currentNames`). (3) Stack
lands elsewhere → map it against the candidate table in
`docs/20260717_analise_terminador_refinement_crash_recovery.md` §4 and decide there; do not extend
the tolerance pattern blindly.

## API Design

### `Model.saveActionHistory(File file, List<ActionRecord> actionHistory) -> void`

- Precondition: none beyond non-null arguments (unchanged).
- Postcondition: `action-history.log` written; every record either logged in order or skipped with
  a warning; final summary `[APE-RV] ActionHistory total=<n> skipped=<m>`; never throws.
- Error behavior: per-record `RuntimeException` → skip + warn (descriptor + message);
  `IOException` → existing log-and-return.

### `Model.collectReplayTreeTransitions(Collection<StateTransition>) -> List<GUITreeTransition>`

- Package-private; extracted from `rebuild()` (the collection step formerly inlined at the top of
  the replay block) so the contract is JVM-testable.
- Postcondition: returns the removed edges' `GUITreeTransition`s **excluding** those of ephemeral
  edges (`StateTransition.getAction().isEphemeral()`); each excluded edge's tree transitions are
  removed from `Graph.treeTransitionHistory` (via `Graph.removeFromTreeHistory`) so the
  post-replay `rebuildHistory()` cannot re-insert the removed edge as a dangling reference into
  `stateTransitionHistory` (which feeds `fillTransitionsByHistory` path reconstruction). Drops are
  logged once per rebuild: `[APE-RV] Rebuild: dropped <n> ephemeral edge(s) from replay
  (INV-MODEL-16)`.
- Non-ephemeral edges: order and content identical to the pre-change inline loop.

### `Model.update(ModelAction action, GUITreeAction guiAction) -> ModelAction`

- New precondition branch: if `action.isEphemeral()`, returns `action` unchanged — an ephemeral
  action's identity is its payload (INV-MODEL-13), never `State.getActions()` membership, so the
  membership lookup (`state.getAction(type)`, the exact terminator signature) is a category error
  for it. Covers all three of `StatefulAgent.updateModel`'s re-anchors
  (`currentAction`/`lastAction`/`newAction`) — the only live call sites. The action-history loop
  in `updateModel` was already protected by its `requireTarget()` filter.
- Non-ephemeral behavior unchanged.

*(Removed at the gate: the F-C `validateNewAction` guard contract — `rebindFailureCount`, the
`[APE-RV] Rebind failure`/`RebindFailures total=` lines — per D7 outcome 1 / task 2.3.)*

### `safeStep(String label, Runnable step) -> void` (private, `MonkeySourceApe` and `StatefulAgent`)

- Runs `step`; on `Throwable` logs `[APE-RV] tearDown step failed: <label>` + stack trace, returns
  normally.

## Data Flow

Failure telemetry flows one way: guarded site → `Logger` (trace file) → per-run counters →
teardown summary lines → rv-android trace parsing (field-based `key=value`, consistent with the
existing `LLM Summary` line format).

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `IllegalStateException: No such action [MODEL_LLM_TAP]` | `Model.rebuild` replay / `updateModel` re-anchor of an ephemeral edge (the proven terminator) | prevented structurally: ephemeral edges excluded from replay + purged from tree history; ephemeral references returned unchanged (INV-MODEL-16) | rebuild completes; exploration continues |
| `IllegalStateException: Cannot find widget` / `GUI action should not be null` | `ActionRecord.resolveModelAction` during teardown | per-record skip + warn + count | remaining records written |
| `IllegalStateException: Already disconnected!` | `MonkeySourceApe.disconnect()` | `safeStep` log + continue | model still persisted |
| Any `Throwable` in a teardown step | `StatefulAgent.tearDown` steps | `safeStep` log + continue | later steps run |
| Binder failure on rotation restore | `MonkeyRotationEvent.injectEvent` in `Monkey.run` finally | inline catch + log | teardown still runs |
| Original loop exception | exploration loop | propagates untouched to `Monkey.main` handler | reported stack = root cause |
| NPE on null `results` | `Naming.naming` finally | eliminated (log moved to success path) | original exception propagates |

## Risks / Trade-offs

- [Over-broad catching hides a real defect] → every guard logs the full stack trace under a
  greppable `[APE-RV]` tag; counters surface volumes; F-D's maskan gate verified the unmasked
  stack before the real fix landed (outcome 1, frame-for-frame).
- [Dropping ephemeral edges at rebuild loses observational data] → accepted and correct: the
  edge's endpoint states were removed by the refinement; an observational edge with no
  model-membership identity cannot be re-anchored (that lookup IS the crash). The drop count is
  logged per rebuild; the tap's history record still persists via F-B's tolerant
  `saveActionHistory` (targetless records resolve without membership).
- [Skipped history records bias replay logs] → `skipped=N` in the summary makes the loss explicit;
  a skipped record was previously a crashed run (no log at all).
- [`Runnable`-based `safeStep` can't wrap checked-exception steps] → all current steps throw only
  unchecked exceptions; if a checked-throwing step appears, the helper gains a throwing functional
  interface then (not speculatively — P1).

## Testing Strategy

| Layer | Scope | Vehicle |
|-------|-------|---------|
| Unit | `Model.saveActionHistory` tolerance (skip, order, summary, null guiAction) | new `SaveActionHistoryToleranceTest` (synthetic trees, existing model-test conventions) |
| Unit | ephemeral quarantine through rebuild (INV-MODEL-16): replay collection skip + history purge, ephemeral re-anchor unchanged, non-ephemeral behavior pinned, terminator signature pinned | new `ModelRebuildEphemeralQuarantineTest` (Unsafe-allocation + stdout-capture conventions; replaces the removed `ValidateNewActionToleranceTest`) |
| Unit | teardown step isolation | new `StatefulAgentTearDownTest` (failing step injected via subclass override) |
| Integration (JVM) | full suite regression | `mvn test` (645 tests green, 19 skipped) |
| Device | F-A unmasking + F-D identity + end-to-end recovery | maskan smoke: pre-fix truncation reproduced, post-fix full budget, logged in-loop stack captured |

## Open Questions

- Identity of the in-loop terminator — **RESOLVED by the F-D maskan gate (2026-07-17): the
  registered prediction confirmed frame-for-frame (D7 outcome 1).** The original expectation
  (`validateNewAction` → `State.resolveAction:388` → `pickNodes:397`) had already been
  **withdrawn** — it was the one candidate the forensic report had excluded by positive evidence
  (unconditional `printGUITree()` before every `Cannot find widget` throw; zero in-loop dumps in
  9/9 fatal traces, while the teardown dump is present in 9/9, proving the mechanism observable),
  and it is structurally near-impossible in-loop (D4). Registered prediction
  (`docs/20260717_analise_terminador_refinement_crash_recovery.md`, before the gate):
  `IllegalStateException: No such action [MODEL_LLM_TAP]` thrown inside `Model.rebuild`'s
  transition-replay (`Model.java:283→340` → `State.java:489`) — a `MODEL_LLM_TAP` quarantine hole
  (`Graph.remove` collects ephemeral edges with no `isEphemeral` filter, `Graph.java:1225/1240`),
  consistent with the 9/9 marker boundary (death between the last `Create state` and the absent
  `Readding transitions finished`) and the only candidate exclusive to the LLM arm. Expected
  telemetry: `RebindFailures total=0`. Alternatives and the full candidate table: analysis doc §4
  and D7's three outcomes.

  **Gate ground truth** (standalone maskan, Group-1 jar, `sata_mop_llm_v13` arm config
  reproduced — SGLang `phtcosta/aperv-qwen3vl-4b-v2-merged`, `llmPercentage=0.7`,
  `llmTemperature=0`, instrumented APK from the cmpv2 dataset; trace `gate_run3.trace`,
  session scratchpad):

  - In-loop stack, unmasked by F-A and reported by `Monkey.main`'s handler (`Internal error`):
    `IllegalStateException: No such action [MODEL_LLM_TAP]` at `State.getAction(State.java:489)`
    ← `Model.rebuild(Model.java:340)` ← `Model.rebuild(Model.java:283)` ←
    `NamingFactory.rebuild(NamingFactory.java:251)` ←
    `NamingFactory.resolveNonDeterminism(NamingFactory.java:157)` ←
    `AbstractNamingManager.resolveNonDeterminism(AbstractNamingManager.java:57)` ←
    `Model.resolveNonDeterministicTransitions(Model.java:365)` ←
    `StatefulAgent.checkNonDeterministicTransitions(StatefulAgent.java:765)` ←
    `updateStateInternal:722` ← `updateStateWrapper` ← `generateEvents` ← `Monkey.run:776` ←
    `Monkey.main:614`. Identical to the prediction except one elided intermediate frame
    (`AbstractNamingManager:57`).
  - Telemetry: `[APE-RV] RebindFailures total=0` (N=0 — the F-C guard never fired) and
    `[APE-RV] ActionHistory total=11 skipped=2` (F-B skipped two genuinely stale records that
    would previously have crashed teardown).
  - Marker boundary: fatal step 12 — `Find a new naming` → `Start rebuilding model` →
    `> Removing state g0s0 …` with 4 transitions, two of them the ephemeral empty-action edges
    `(g0s0,,g0s0)` → 11 × `Create state` → death. `Readding transitions finished`,
    `Rebuilding model finished` and `Model has been refined, reset stateful` all absent —
    exactly the 9/9 cmpv2 boundary.
  - Reproduction requires cmpv2's fresh-install condition: `pm clear` before the run puts maskan
    on the sparse W=2 screen where LLM calls become coordinate taps (`llm_tap=9, matched=1`);
    a warm app profile yields rich trees (`matched=77/96`), six survived rebuilds and no crash
    in 285 steps. Runs are unseeded; fresh-state attempt reproduced on the first try.
  - Consequence applied (task 2.3, outcome 1): F-C removed (guard, test, INV-EXPL-30 delta,
    exploration-delta requirement); task 2.4 implements the real fix (ephemeral quarantine
    extended to the rebuild replay and the `updateModel` rebind).
