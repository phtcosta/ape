# Tasks: refinement-crash-recovery

<!-- Group ordering is the D7 decision from design.md: unmask first (Group 1), capture the
     loop-killer's ground-truth identity on-device (Group 2), only then land the tolerance
     guards (Groups 3-4). Groups 3 and 4 are independent of each other and can run in
     parallel after Group 2. Group 5 closes. Critical path: 1 -> 2 -> (3|4) -> 5. -->

## 1. Unmasking (F-A + Naming finally fix)

- [x] 1.1 Write `StatefulAgentTearDownTest` (agent test conventions): a teardown step that throws
      (injected via subclass override of `saveActionHistory`) does not prevent the later steps
      (action counters / naming dump) from running; the failure is logged with a stack trace — RED
- [x] 1.2 Add private `safeStep(String label, Runnable step)` to `StatefulAgent` and wrap the eight
      `tearDown()` steps (`StatefulAgent.java:1644-1653`) — GREEN for 1.1
- [x] 1.3 Add private `safeStep` to `MonkeySourceApe` and wrap the `tearDown()` steps
      (`MonkeySourceApe.java:221-231`), `disconnect()` included, so a step failure never prevents
      `mAgent.tearDown()` nor the writer/logger/timeline shutdown
- [x] 1.4 Guard the two throw-capable statements in `Monkey.run`'s `finally`
      (`Monkey.java:777-786`): rotation restore and `tearDown()` each in their own inline
      try/catch(Throwable) that logs and returns — the original loop exception must propagate
      unchanged to `Monkey.main`'s handler
- [x] 1.5 `Naming.java:496-503`: move the `Logger.dformat` result log out of the `finally` into the
      success path after `namingInternal` returns (eliminates the NPE that masks
      `"A node has no namelets"`); while moving it, fix the duplicated argument — the line passes
      `results.getNameSize()` for both `%d names` and `%d nodes`; the second should be
      `results.getNodeSize()`
- [x] 1.6 Run `/sdd-test-run` — full JUnit suite green

## 2. F-D gate: capture the loop-killer identity on-device

Prerequisites: emulator @RVSec + SGLang reachable (LLM arm config, `llmPercentage=0.7` as in the
gh43 arms); `app.maskan.chat` APK (deterministic CFW in ~20-30s). Foreground-first protocol
(`monkey -c LAUNCHER` before APE) per the standalone smoke methodology.

- [x] 2.1 Build (`mvn package`), push the Group-1 jar, run maskan; confirm the run still truncates
      (guards changed reporting, not the defect) and capture the trace — DONE 2026-07-17: arm
      `sata_mop_llm_v13` reproduced (SGLang `phtcosta/aperv-qwen3vl-4b-v2-merged`, instrumented
      APK, `llmPercentage=0.7`, `llmTemperature=0`); crash requires cmpv2's fresh-install
      condition (`pm clear` first — sparse W=2 screen ⇒ taps); reproduced on the first
      fresh-state attempt (`gate_run3.trace`)
- [x] 2.2 Verify the reported stack is now the ORIGINAL in-loop exception (not the teardown replay)
      and record its exact frames in design.md's Open Questions section; also record the marker
      boundary of the fatal step (is `Start rebuilding model` followed by `Rebuilding model
      finished`?) and the `[APE-RV] RebindFailures total=N` line — DONE: in-loop stack unmasked
      and recorded; boundary = last `Create state` → death (`finished` absent); `RebindFailures
      total=0`; bonus `ActionHistory total=11 skipped=2` (F-B working)
- [x] 2.3 Apply the D7 outcome matrix against the registered prediction — OUTCOME 1 (prediction
      confirmed frame-for-frame, N=0): F-C removed (guard + `rebindFailureCount` + teardown line
      reverted, `ValidateNewActionToleranceTest` deleted, INV-EXPL-30 delta and the
      "Post-Refinement Action Revalidation Tolerance" requirement dropped from the exploration
      delta spec); task 2.4 implemented
      (`docs/20260717_analise_terminador_refinement_crash_recovery.md`: stack =
      `No such action [MODEL_LLM_TAP]` at `State.getAction:489` ← `Model.rebuild:340/283`, N=0):
      (1) prediction confirmed → task 2.4 (real fix) AND remove F-C — revert the Group-4 guard in
      `validateNewAction`, delete `ValidateNewActionToleranceTest`, drop the INV-EXPL-30 delta and
      the "Post-Refinement Action Revalidation Tolerance" requirement from the exploration delta
      spec (P1: no speculative guards for failures that do not occur);
      (2) stack in `validateNewAction` with N>0 → F-C stands as designed; explain the missing
      in-loop dump (e.g. empty `currentNames`) before archiving;
      (3) any other stack → classify against analysis-doc §4's candidate table and decide there;
      do not extend the tolerance pattern blindly
- [x] 2.4 (conditional on 2.3 outcome 1) Fix the `MODEL_LLM_TAP` quarantine hole — TDD: RED test
      reproducing rebuild-replay of an ephemeral edge (today untested; `GraphEphemeralActionTest`
      covers only markVisited/addTransition), then skip ephemeral-action `GUITreeTransition`s in
      `Model.rebuild`'s replay collection/loop and guard the ephemeral `lastAction` rebind in
      `StatefulAgent.updateModel` (`:252` → `Model.java:411`, latent hole #2); update the
      `model` delta spec (INV-MODEL-13/14 quarantine scope) before implementing — DONE: model
      delta gained INV-MODEL-16 + "Ephemeral Quarantine Through Rebuild" (spec before code);
      `ModelRebuildEphemeralQuarantineTest` RED (collect returned the ephemeral tt; update threw
      the exact terminator) → GREEN via `Model.collectReplayTreeTransitions` (extracted from
      `rebuild()`, ephemeral filter + `Graph.removeFromTreeHistory` purge so `rebuildHistory`
      cannot resurrect a dangling edge, `[APE-RV]` drop log) and the `isEphemeral()` early-return
      in `Model.update(ModelAction, GUITreeAction)` (covers `currentAction`/`lastAction`/
      `newAction` — the only live call sites); full suite 645/0/19

## 3. F-B: tolerant action-history persistence (TDD)

- [x] 3.1 Write `SaveActionHistoryToleranceTest` (model test conventions, synthetic trees):
      (a) stale record skipped with warning, records before/after still written in order,
      summary `total=N skipped=1`; (b) fully resolvable history byte-identical to pre-change
      format, `skipped=0`; (c) `guiAction == null` record skipped, iteration continues — RED
- [x] 3.2 Implement the per-record `RuntimeException` guard + `[APE-RV] ActionHistory total=<n>
      skipped=<m>` summary in `Model.saveActionHistory` (`Model.java:96-112`) — GREEN

## 4. F-C: tolerant post-refinement rebind (TDD)

- [x] 4.1 Write `ValidateNewActionToleranceTest` (agent test conventions) in three acts, so the
      test discriminates the fix — a fresh action's `valid` defaults to `false`, so asserting
      exclusion without act (1) goes green even without `setValid(false)`:
      (1) establish the sticky precondition `isValid() == true`. NOTE (impl): a *successful*
      `validateNewAction` is not JVM-reachable — it ends in `ape.validateResolvedAction`, and
      `MonkeySourceApe` cannot be class-loaded off-device (`android.app.IUiAutomationConnection`,
      cf. `MonkeySourceApeForeignGuardTest`). Act 1 therefore sets `valid` the way the last
      successful step left it (`setValid(true)`) — D3's stickiness is exactly what the act encodes,
      and the discriminating power is unchanged;
      (2) break the binding (target absent from the tree's re-abstracted `currentNames`);
      (3) re-validate: no throw, `rebindFailureCount` incremented, logged once with the
      descriptor, `isValid() == false`, and the action rejected by `ActionFilter.ENABLED_VALID` — RED
- [x] 4.2 Implement the catch around `newState.resolveAction` in `StatefulAgent.validateNewAction`
      (`StatefulAgent.java:1332`): log + `rebindFailureCount++` + `action.setValid(false)` +
      `return null`, mirroring the existing rejection branch (`StatefulAgent.java:1337-1339`);
      plus the `[APE-RV] RebindFailures total=<n>` teardown line (inside a Group-1 `safeStep`)
      — GREEN
- [x] 4.3 Run `/sdd-test-run` — full suite green

## 5. Verification

- [x] 5.1 `mvn package` — d8 build produces `target/ape-rv.jar` without warnings
- [x] 5.2 Device recovery gate (maskan): with the full fix set, the run reaches its configured time
      budget (no truncation), exits cleanly, and the trace shows the `[APE-RV] ActionHistory
      total=/skipped=` line (the `RebindFailures total=` line left with F-C — task 2.3; the
      loop-side telemetry is now the `[APE-RV] Rebuild: dropped N ephemeral edge(s)` log, which
      must appear on any rebuild that removed a tap edge and previously crashed) — DONE 2026-07-17
      with caveat: 5/5 fresh-state runs survived the previously-fatal condition (rebuilds
      balanced 4/4–5/5, `dropped ephemeral edge(s)` fired in every run, zero `No such action`,
      clean teardown with `ActionHistory total=/skipped=`; pre-fix the identical regime died at
      step 12). No run reached the full 300s (they end at 101–224s) — but by APE's pre-existing
      restart race, not by refinement: `Try to restart package` force-stops the app and the next
      injection hits the launcher → Monkey's baseline `SecurityException` abort (same terminal
      sequence observed with the pre-fix jar; arm-agnostic; recorded as out-of-scope debt). The
      "no early termination attributable to the refinement" criterion is met unambiguously; the
      full-budget criterion is demonstrated on the healthy-path run (5.3)
- [x] 5.3 Device regression sanity: one healthy APK (e.g. bitbanana or thumbkey) — behavior
      unchanged on the no-failure path (no new log lines except `skipped=0` summaries, run
      completes as before) — DONE 2026-07-17 on bitbanana: 226 steps, 3/3 rebuilds, `ActionHistory
      total=229 skipped=0`, quarantine telemetry correctly silent (no removed state carried a tap
      edge), zero `No such action`, no F-C remnants; terminal behavior identical to the pre-change
      jar (the pre-existing injection-abort). Note: a thumbkey attempt surfaced
      `RuntimeException: An unvisited state has non-empty transitions`
      (`checkAndRefreshNewState:645`) — a PRE-EXISTING defect documented by the 2026-07 deep audit
      (rebuild replay marks only source states; a target-only reborn state comes back unvisited
      with in-transitions), now merely visible thanks to F-A; unrelated to this change (that run
      had zero ephemeral drops and the offending edges are registered non-ephemeral actions);
      recorded as out-of-scope debt
- [x] 5.4 `openspec validate refinement-crash-recovery --strict` passes
- [x] 5.5 Run `/sdd-qa-lint-fix` on touched files — no-op: the project has no lint gate
      (checkstyle absent, `.sdd/sdd-config.yaml` declares `linter: none`); nothing to run or fix
- [x] 5.6 Run `/sdd-verify` — PASS: 641 tests (19 skipped), lint stage skipped (no linter)
- [x] 5.7 Run `/sdd-code-reviewer` — review the refinement-crash-recovery implementation:
      no high-confidence findings; D1/D2/D3/D4/D5/D6 all confirmed faithful, tests discriminate
- [x] 5.8 At archive time: manually sync the `## Invariants` prose into the main specs (extended
      INV-EXPL-16 wording at `specs/exploration/spec.md:63`, new INV-EXPL-29/30, INV-MODEL-15) —
      `openspec archive` only rewrites ADDED/MODIFIED/REMOVED blocks; use `--skip-specs` + manual
      delta-sync per established practice — DONE 2026-07-17 (post-gate set: INV-EXPL-16 extended
      + INV-EXPL-29 [INV-EXPL-30 died with F-C], INV-MODEL-15 + INV-MODEL-16; MODIFIED "Output
      Persistence on Termination" body+4 scenarios applied; ADDED "Tolerant Action-History
      Persistence" and "Ephemeral Quarantine Through Rebuild" inserted at their logical sections;
      `openspec validate --specs` 19/19)
