# Tasks: experiment-validity

## 1. Hot-path correctness (binarySearch contracts)

- [x] 1.1 `GUITree.contains`: guard changed to `index < 0` (INV-TREE-08); the binarySearch+guard is extracted into JVM-testable `GUITree.indexOfName(Name[], Name)` (normalizes absent → -1), since `GUITreeNode` holds an `AccessibilityNodeInfo` field that blocks field access in the JVM test classpath
- [x] 1.2 `Naming.select`: guard changed to `< 0` (INV-NAME-13); extracted into static `Naming.containsNamelet(List<Namelet>, Namelet)` (the `comparator` is static, depth/exprStr-based) so it is JVM-testable without a `Naming` instance
- [x] 1.3 `GUITreeContainsTest`: `indexOfName` maps absent keys with insertion point 0 / mid / after-all all to -1 (no AIOOBE), and returns the hit index for a present key (4 tests)
- [x] 1.4 `NamingSelectTest`: `containsNamelet` returns false for absent namelets with insertion point 0 / mid / after-all, true for a present namelet (4 tests)

## 2. Count-preserving rebuild

- [x] 2.1 `Graph.rebuildHistory`: deleted the dead `firstVisitTimestamp`/`lastVisitTimestamp` self-assignments and the unconditional `edge.visitedCount++`; the loop now only reconstructs the ordered `stateTransitionHistory` list (markVisited during transition re-add already counts each edge once)
- [x] 2.2 DESIGN DEVIATION (approved): instead of "reset ActivityNode counters" (unsound — replay is partial, so reset+recount undercounts activities with non-replayed transitions), **preserve** them. Added `Graph.rebuilding` flag (`setRebuilding`) bracketing the replay in `Model.rebuild`; `Graph.markVisited(State)` skips the activity propagation while rebuilding. Activity counts are abstraction-invariant + survive `ActivityNode.removeState`, so they are already complete pre-replay. `model/spec.md` corrected to match (preserve, not reset); state-level source re-marking documented as a deferred, out-of-scope concern (INV-MODEL-11 scoped to edge+activity)
- [x] 2.3 `RebuildCountTest`: via the real `Graph.markVisited(State)` + `setRebuilding` path (full `Model.rebuild` needs the Android runtime) — activity count preserved across replay while the state counter still bumps; activity count identical after 3 rebuilds (2 tests)

## 3. Seeded reproducibility

- [x] 3.1 `RandomHelper`: replaced the `ThreadLocalRandom.current()` backing with a static `java.util.Random` field + static `seed(long)` (INV-EXPL-14)
- [x] 3.2 Seeded in `Monkey` at the APE branch (not the `MonkeySourceApe` ctor): `RandomHelper.seed(mSeed)` right before `new MonkeySourceApe(...)`. `mSeed` is only in scope in `Monkey`; the ctor receives the already-built `Random`, from which the seed is unrecoverable — so this is the correct minimal site
- [x] 3.3 `RandomHelperSeedTest`: two `seed(42)` runs yield identical mixed draw sequences (`nextInt`/`toss`/`nextLong`/`nextBoolean`/`randomPick`); `seed(43)` diverges (2 tests)

## 4. Run lifecycle

- [x] 4.1 `Monkey.java`: moved the APE `tearDown()` into the existing `finally` (after the rotation restore), so model/coverage/trace flush runs even when `runMonkeyCycles` throws (INV-EXPL-16); the exception still propagates after teardown
- [x] 4.2 `MonkeySourceApe.checkAppActivity`: added `waitForActivityCycles` counter — `> 100` consecutive waits → log `[APE-RV] waitForActivity exceeded 100 cycles, relaunching`, clear wait flags, `clearEvent()` + `startRandomMainApp()`; counter reset whenever an allowed package is foreground (INV-EXPL-15). No JVM test (Android-runtime path); covered by 4.3
- [x] 4.3 Device validation (RVSec AVD) — DEFERRED: induce a crash mid-run, confirm `sataModel.obj`/coverage dump exist; wedge the foreground (launch another app), confirm relaunch line and continued exploration; confirm cold start stays below the 100-cycle threshold (design OQ1) — DONE via cmpft/cmpft2 device experiments (rvsec/rv-android/docs/20260707_relatorio_cmpft2.md; 657/657 tasks completed with teardown dumps, 0 wedged runs)

## 5. MOP load gate

- [x] 5.1 Delete the 1-arg `MopData.load(String)` overload (P3; no callers after 5.2) — NOTE: spec.md:38 "no remaining callers" was false; 49 test call sites migrated to `load(path, null, null)` (spec.md:50-53 sanctions null-args) across MopDataTest/ComponentInfoTest/MopScorerTest
- [x] 5.2 `StatefulAgent` constructor: call `MopData.load(Config.mopDataPath, ape.getMainApp().getPackageName(), ape.getMainApp().getClassName())` (added `MonkeySourceApe.getMainApp()` getter); fail-fast via extracted `StatefulAgent.requireMopArm(loaded, path)` — throws `StopTestingException` on null with `mopDataPath` set (INV-MOP-22)
- [x] 5.3 `MopData.load`: emits exactly one `[APE-MOP-DATA]` status line per invocation — `status=loaded package=.. windows=.. widgets=.. flagged=.. droppedNoId=.. transitions=..` or `status=rejected reason=<file-missing|parse-error|incomplete|package-mismatch>` (INV-MOP-21; Logger.iprintln/iformat → System.out=.trace, never logcat). Null path stays silent (spec.md:12 exemption)
- [x] 5.4 `MopDataTest`: status-line assertions for success + each rejection reason + null-path-no-line; fail-fast asserted in `StatefulAgentTriggerTest` (testable static `requireMopArm`, since the ctor needs the Android runtime)
- [x] 5.5 `StatefulAgent.dispatchTrigger`: builds `ComponentName(_mopData.getPackageName(), c.className)`; substring derivation deleted (INV-CT-04)
- [x] 5.6 Covered-by-construction: `dispatchTrigger` now passes `_mopData.getPackageName()` literally — nothing derives a package from the class name, so a subpackaged `className` cannot change the package. `ComponentName` is a compile-only Android stub (not JVM-runnable), so no vacuous string-helper test was added (P1)

## 6. Verification

- [x] 6.1 Run /sdd-test-run ape (full `mvn test` suite green) — DONE: SDD gate suite run 2026-07-06 across the change set (shared, single run); mvn test 477 green (0 fail, 19 skip) per cmpft2 build
- [x] 6.2 Run /sdd-qa-lint-fix ape — DONE: SDD gate suite run 2026-07-06 across the set (no-op: .sdd linter=none, checkstyle not installed)
- [x] 6.3 Run /sdd-verify ape — DONE: SDD gate suite run 2026-07-06 across the set (PASS: 477 tests, 0 fail; lint none)
- [x] 6.4 Run /sdd-code-reviewer — DONE: SDD gate suite run 2026-07-06 across the set; forensic 6-agent audit + review pass, no code bugs
