# Tasks: refinement-crash-recovery

<!-- Group ordering is the D7 decision from design.md: unmask first (Group 1), capture the
     loop-killer's ground-truth identity on-device (Group 2), only then land the tolerance
     guards (Groups 3-4). Groups 3 and 4 are independent of each other and can run in
     parallel after Group 2. Group 5 closes. Critical path: 1 -> 2 -> (3|4) -> 5. -->

## 1. Unmasking (F-A + Naming finally fix)

- [ ] 1.1 Write `StatefulAgentTearDownTest` (agent test conventions): a teardown step that throws
      (injected via subclass override of `saveActionHistory`) does not prevent the later steps
      (action counters / naming dump) from running; the failure is logged with a stack trace — RED
- [ ] 1.2 Add private `safeStep(String label, Runnable step)` to `StatefulAgent` and wrap the eight
      `tearDown()` steps (`StatefulAgent.java:1644-1653`) — GREEN for 1.1
- [ ] 1.3 Add private `safeStep` to `MonkeySourceApe` and wrap the `tearDown()` steps
      (`MonkeySourceApe.java:221-231`), `disconnect()` included, so a step failure never prevents
      `mAgent.tearDown()` nor the writer/logger/timeline shutdown
- [ ] 1.4 Guard the two throw-capable statements in `Monkey.run`'s `finally`
      (`Monkey.java:777-786`): rotation restore and `tearDown()` each in their own inline
      try/catch(Throwable) that logs and returns — the original loop exception must propagate
      unchanged to `Monkey.main`'s handler
- [ ] 1.5 `Naming.java:496-503`: move the `Logger.dformat` result log out of the `finally` into the
      success path after `namingInternal` returns (eliminates the NPE that masks
      `"A node has no namelets"`); while moving it, fix the duplicated argument — the line passes
      `results.getNameSize()` for both `%d names` and `%d nodes`; the second should be
      `results.getNodeSize()`
- [ ] 1.6 Run `/sdd-test-run` — full JUnit suite green

## 2. F-D gate: capture the loop-killer identity on-device

Prerequisites: emulator @RVSec + SGLang reachable (LLM arm config, `llmPercentage=0.7` as in the
gh43 arms); `app.maskan.chat` APK (deterministic CFW in ~20-30s). Foreground-first protocol
(`monkey -c LAUNCHER` before APE) per the standalone smoke methodology.

- [ ] 2.1 Build (`mvn package`), push the Group-1 jar, run maskan; confirm the run still truncates
      (guards changed reporting, not the defect) and capture the trace
- [ ] 2.2 Verify the reported stack is now the ORIGINAL in-loop exception (not the teardown replay)
      and record its exact frames in design.md's Open Questions section
- [ ] 2.3 If the stack does NOT land in `validateNewAction` → `State.resolveAction` → `pickNodes`:
      extend Group 4's tolerance target to the actual site and update the exploration delta spec
      (INV-EXPL-30 wording) before implementing

## 3. F-B: tolerant action-history persistence (TDD)

- [ ] 3.1 Write `SaveActionHistoryToleranceTest` (model test conventions, synthetic trees):
      (a) stale record skipped with warning, records before/after still written in order,
      summary `total=N skipped=1`; (b) fully resolvable history byte-identical to pre-change
      format, `skipped=0`; (c) `guiAction == null` record skipped, iteration continues — RED
- [ ] 3.2 Implement the per-record `RuntimeException` guard + `[APE-RV] ActionHistory total=<n>
      skipped=<m>` summary in `Model.saveActionHistory` (`Model.java:96-112`) — GREEN

## 4. F-C: tolerant post-refinement rebind (TDD)

- [ ] 4.1 Write `ValidateNewActionToleranceTest` (agent test conventions) in three acts, so the
      test discriminates the fix — a fresh action's `valid` defaults to `false`, so asserting
      exclusion without act (1) goes green even without `setValid(false)`:
      (1) resolve the action successfully once (`isValid() == true`, the sticky precondition);
      (2) break the binding (target absent from the tree's re-abstracted `currentNames`);
      (3) re-validate: no throw, `rebindFailureCount` incremented, logged once with the
      descriptor, `isValid() == false`, and the action rejected by `ActionFilter.ENABLED_VALID` — RED
- [ ] 4.2 Implement the catch around `newState.resolveAction` in `StatefulAgent.validateNewAction`
      (`StatefulAgent.java:1332`): log + `rebindFailureCount++` + `action.setValid(false)` +
      `return null`, mirroring the existing rejection branch (`StatefulAgent.java:1337-1339`);
      plus the `[APE-RV] RebindFailures total=<n>` teardown line (inside a Group-1 `safeStep`)
      — GREEN
- [ ] 4.3 Run `/sdd-test-run` — full suite green

## 5. Verification

- [ ] 5.1 `mvn package` — d8 build produces `target/ape-rv.jar` without warnings
- [ ] 5.2 Device recovery gate (maskan): with the full fix set, the run reaches its configured time
      budget (no truncation), exits cleanly, and the trace shows the `[APE-RV] ActionHistory
      total=/skipped=` and `RebindFailures total=` lines
- [ ] 5.3 Device regression sanity: one healthy APK (e.g. bitbanana or thumbkey) — behavior
      unchanged on the no-failure path (no new log lines except `skipped=0` summaries, run
      completes as before)
- [ ] 5.4 `openspec validate refinement-crash-recovery --strict` passes
- [ ] 5.5 Run `/sdd-qa-lint-fix` on touched files
- [ ] 5.6 Run `/sdd-verify`
- [ ] 5.7 Run `/sdd-code-reviewer` — review the refinement-crash-recovery implementation
- [ ] 5.8 At archive time: manually sync the `## Invariants` prose into the main specs (extended
      INV-EXPL-16 wording at `specs/exploration/spec.md:63`, new INV-EXPL-29/30, INV-MODEL-15) —
      `openspec archive` only rewrites ADDED/MODIFIED/REMOVED blocks; use `--skip-specs` + manual
      delta-sync per established practice
