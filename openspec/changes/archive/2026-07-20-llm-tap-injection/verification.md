# Verification: llm-tap-injection

**Date:** 2026-07-20
**JVM suite:** 651 tests, 0 failures, 0 errors, 19 skipped (baseline 645/0/19 + 6 new).
**Build:** `mvn package` → `target/ape-rv.jar` (268,551 bytes, d8 OK).

## On-device gate (fresh-install LLM-tap regime)

Setup: emulator-5554 (RVSec), SGLang `phtcosta/aperv-qwen3vl-4b-v2-merged` @ localhost:30000
(health OK), `pm clear com.dessalines.thumbkey.debug` + foreground-first `monkey -c LAUNCHER`,
`ape.properties`: `llmUrl=http://10.0.2.2:30000/v1, llmPercentage=0.7, llmTemperature=0,
llmPromptVariant=v13, defaultGUIThrottle=200`. APE `--running-minutes 2 --ape sata`.

Run summary: 104 steps, LLM Summary `calls=75 matched=67 llm_tap=7 no_match=1 null=0
screenshot_failed=0 breaker_trips=0`.

### Gate A1 (tap injects) — PASS

- 7 `MODEL_LLM_TAP` executions; **1 injected** (no drop line), **6 dropped** via the
  `getVisibleBounds==null` branch.
- Injected tap, step 93 (trace `gate_run.trace:9219`): action
  `MODEL_LLM_TAP...@(540,957)` on state `...@533724406@Naming[0]@[W=5]` — no
  `off-screen action dropped` line — and the step-94 edge records
  `Source: ...@533724406...[W=5][A=8]` → `Target: ...@-516771663...[W=7][A=8]`: **the tap
  changed the screen** (pre-fix: 16,625/16,625 campaign taps dropped, 0 injected ever).
- Drop lines now show the non-degenerate rect (`Rect(916, 1628 - 917, 1629)`), proving the new
  `toInjectableRect()` dispatch is live; `Invalid bounds` count is 0 (the empty-rect death branch
  is gone).
- The 6 remaining drops are all at `y ≥ 1487` — coordinates below the app window's root-node
  bounds (thumbkey's IME/keyboard band). This is the pre-existing guard-bounds semantics
  (root-node visible bounds, not display bounds), flagged in design.md as a non-goal/risk:
  legitimate protection for off-window taps, but it excludes cross-window targets (IME). Residual
  debt, measured: 6/7 in this app class (keyboard app = worst case).

### Gate A2 (no stale-ephemeral terminator) — PASS

- Full 2-minute run, exit 0, `IllegalStateException` count 0, `Internal error` count 0.
- No refinement coincided with a pending ephemeral tap in this run (`stale ephemeral edge
  dropped` count 0 — the coincidence is rare, 1/1086 in cmpm); the invariant is carried by
  `GraphEphemeralActionTest.staleEphemeralActionIsSkippedAtTransitionRecording`, which reproduces
  the exact cmpm crash (RED: `IllegalStateException` from `Graph.addTransition`; GREEN: null +
  logged skip), plus the two control tests (fresh tap still records; non-ephemeral mismatch still
  throws).

## Consumer null-tolerance check (task 2.3)

`checkNonDeterministicTransitions` early-returns on null `currentStateTransition`
(`StatefulAgent.java:756`). The buffer path (`StatefulAgent.java:468`,
`currentStateTransition.isSameActivity()`) is unreachable with the new null: reaching it requires
`expectedCurrentAction.equals(currentAction)` (line 458) to pass, impossible for an ephemeral tap
(never buffered; a stale-ephemeral step fails the equality and clears the buffer). Pre-existing
null assignments (lines 525/1280) are unchanged.
