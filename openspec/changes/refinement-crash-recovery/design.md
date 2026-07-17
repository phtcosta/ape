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
is destroyed. The crash path is 2019 upstream code; the trigger regime (LLM coordinate taps
navigating into repeating-row substrates) is new.

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

No new components. Failure-path hardening at six sites in four existing classes:

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

StatefulAgent.checkNonDeterministicTransitions
  └─ validateAllNewActions → validateNewAction
       └─ [guard] State.resolveAction rebind failure → action invalidated (setValid(false))  ← F-C

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
| `StatefulAgent.validateNewAction` | Post-refinement rebind, tolerant | `ModelAction`, latest `GUITree` | resolved action, or `null` with the action invalidated (`setValid(false)`) and counted |
| `Naming.naming` | Per-step naming with non-masking diagnostics | `GUITree` | `NamingResult` |

## Mapping: Spec -> Implementation -> Test

| Requirement / Invariant | Implementation | Test |
|-------------|---------------|------|
| INV-EXPL-16 (no masking) | guards in `Monkey.run` finally | device smoke (maskan): reported stack = in-loop exception |
| INV-EXPL-29 (step isolation) | `safeStep` in `MonkeySourceApe.tearDown`, `StatefulAgent.tearDown` | `StatefulAgentTearDownTest`: failing step → later steps still run |
| INV-EXPL-30 (tolerant rebind) | catch in `validateNewAction` | `ValidateNewActionToleranceTest`: rebind failure → action invalidated, excluded by filters, no throw |
| Tolerant Action-History Persistence + INV-MODEL-15 | per-record guard in `Model.saveActionHistory` | `SaveActionHistoryToleranceTest`: stale record skipped, file complete, `skipped=1` |
| Naming finally fix | log moved inside try in `Naming.naming` | `NamingDiagnosticsTest` (or code-inspection task if not unit-reachable) |
| F-D loop-killer identity | maskan run with F-A build | device smoke gate: logged in-loop stack matches F-C site |

## Goals / Non-Goals

**Goals:**
- The exception reported for a failed run is the exception that ended the loop.
- A teardown-step failure costs only that step's artifact, never the rest.
- A naming refinement can no longer terminate a run (loop side) or abort history persistence
  (teardown side).
- Telemetry (`skipped=N`, rebind-failure count) quantifies refinement-induced data loss per run.

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

**D4 — Guard placement for the loop side: `validateNewAction` (`StatefulAgent.java:1332`).**
Line 1332 is the **only** `State.resolveAction` call site in `StatefulAgent` — the guard is
exhaustive by construction. (`resolveNewAction` and `adjustActionsByGUITree` never call
`State.resolveAction`; `StatefulAgent.java:1454` is a priority computation over
`GUITree.getNodes`, reached only for actions already resolved at the current timestamp, so it
cannot fire the rebind failure.) `handleNullAction`'s `validatedActionFilter` and both
`RandomAgent` selection paths also funnel through `validateNewAction`, so no second guard site
exists. The F-D maskan gate remains the ground truth on the terminator's identity: if the unmasked
stack lands elsewhere (e.g. `markVisited` on a removed state), extend the pattern there (D7).

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
(the masking destroyed it). Tasks sequence: implement F-A alone → build → run maskan → the logged
in-loop stack is ground truth → verify it lands in `validateNewAction` (expected) → implement
F-B/F-C → re-run maskan → full budget reached. If the stack lands elsewhere (e.g.
`adjustActionsByGUITree`, `markVisited` on a removed state), extend the same tolerance pattern to
that site and update the delta spec before archiving.

## API Design

### `Model.saveActionHistory(File file, List<ActionRecord> actionHistory) -> void`

- Precondition: none beyond non-null arguments (unchanged).
- Postcondition: `action-history.log` written; every record either logged in order or skipped with
  a warning; final summary `[APE-RV] ActionHistory total=<n> skipped=<m>`; never throws.
- Error behavior: per-record `RuntimeException` → skip + warn (descriptor + message);
  `IOException` → existing log-and-return.

### `StatefulAgent.validateNewAction(ModelAction action) -> ModelAction`

- Actual signature: one argument; the state is implicit via the `newState` field. Returns the
  resolved action, or `null` on rejection/failure. `validateAllNewActions` discards the return
  value, so the side effects (`resolveAt`, `setValid`) are the entire contract on that path.
- Postcondition: action resolved and `setValid(true)`, or **marked invalid** (`setValid(false)`)
  and thereby excluded from the candidate set, with
  `[APE-RV] Rebind failure: <descriptor> (<message>)` logged once and `rebindFailureCount`
  incremented. Never throws for rebind failures. The catch covers `IllegalStateException` from
  `State.resolveAction` — `Cannot find widget` (expected) and the rarer
  `Empty GUI tree history` (`State.java:390`); the logged message attributes the variant.
- `rebindFailureCount` printed at teardown (`[APE-RV] RebindFailures total=<n>`).

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
| `IllegalStateException: Cannot find widget` | `GUITree.pickNodes` during `validateNewAction` (post-refinement) | catch at `validateNewAction`; action invalidated (`setValid(false)`); count | exploration continues same step |
| `IllegalStateException: Cannot find widget` / `GUI action should not be null` | `ActionRecord.resolveModelAction` during teardown | per-record skip + warn + count | remaining records written |
| `IllegalStateException: Already disconnected!` | `MonkeySourceApe.disconnect()` | `safeStep` log + continue | model still persisted |
| Any `Throwable` in a teardown step | `StatefulAgent.tearDown` steps | `safeStep` log + continue | later steps run |
| Binder failure on rotation restore | `MonkeyRotationEvent.injectEvent` in `Monkey.run` finally | inline catch + log | teardown still runs |
| Original loop exception | exploration loop | propagates untouched to `Monkey.main` handler | reported stack = root cause |
| NPE on null `results` | `Naming.naming` finally | eliminated (log moved to success path) | original exception propagates |

## Risks / Trade-offs

- [Over-broad catching hides a real defect] → every guard logs the full stack trace under a
  greppable `[APE-RV]` tag; counters surface volumes; F-D's maskan gate verifies the unmasked
  stack before the tolerance guards land.
- [Invalidated actions accumulate after heavy refinement, shrinking the candidate set] → with the
  explicit `setValid(false)` this shrinkage is real, not hypothetical: the action leaves every
  filter's candidate set until a future `validateNewAction` re-resolves it successfully
  (`setValid(true)` — self-healing on the next successful rebind). Accepted: it is the correct
  semantics, and `rebindFailureCount` makes it measurable per run; forensics showed survivors with
  134–152 refinements and zero rebind failures, so failures are rare per refinement. Degenerate
  worst case (every action invalid) falls through to `handleNullAction` → `BadStateException`, the
  pre-existing no-action path.
- [Skipped history records bias replay logs] → `skipped=N` in the summary makes the loss explicit;
  a skipped record was previously a crashed run (no log at all).
- [`Runnable`-based `safeStep` can't wrap checked-exception steps] → all current steps throw only
  unchecked exceptions; if a checked-throwing step appears, the helper gains a throwing functional
  interface then (not speculatively — P1).

## Testing Strategy

| Layer | Scope | Vehicle |
|-------|-------|---------|
| Unit | `Model.saveActionHistory` tolerance (skip, order, summary, null guiAction) | new `SaveActionHistoryToleranceTest` (synthetic trees, existing model-test conventions) |
| Unit | `validateNewAction` tolerance + counter | new `ValidateNewActionToleranceTest` (agent-test harness conventions) |
| Unit | teardown step isolation | new `StatefulAgentTearDownTest` (failing step injected via subclass override) |
| Integration (JVM) | full suite regression | `mvn test` (636 tests green before archive) |
| Device | F-A unmasking + F-D identity + end-to-end recovery | maskan smoke: pre-fix truncation reproduced, post-fix full budget, logged in-loop stack captured |

## Open Questions

- Identity of the in-loop terminator (resolved by the F-D maskan gate; expected:
  `validateNewAction` → `State.resolveAction:388` → `pickNodes` at `:397`).
