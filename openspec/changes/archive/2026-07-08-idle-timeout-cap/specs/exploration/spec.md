# exploration — delta: idle-timeout-cap

## Purpose

Bound the wall-clock cost of the slow tree-capture idle wait without changing default behavior. Today `getRootInActiveWindowSlow` (`MonkeySourceApe.java:478-485`) waits up to a hardcoded 10 s global timeout (`waitForIdle(1000, 1000 * 10)`) for the UI to go idle before snapshotting; on timeout the wait is caught and the tree is captured anyway. The cmpft2 audit measured this as the largest single aggregate drain on the exploration budget (10,668 s over 34,119 fetches; worst run 216,711 ms = 72% of its 300 s budget). This delta makes the ceiling a config flag whose default is byte-identical to the current constant, so a time-boxed experiment can lower it uniformly across arms.

## Data Contracts

### Input
- `ape.maxIdleTimeoutMs: long` — default `10000`; the global timeout (ms) of the UiAutomation idle wait in `getRootInActiveWindowSlow`, and (÷1000) the seconds threshold of the "window stuck animating" retry breaks in both slow-capture retry loops (`refreshNewState` and `checkAndRefreshNewState`). The default preserves current behavior exactly.

## ADDED Requirements

### Requirement: Parametrized Idle-Wait Ceiling in Slow Tree Capture

The global timeout of the UiAutomation idle wait in `getRootInActiveWindowSlow` SHALL be `ape.maxIdleTimeoutMs` (default `10000` ms). The idle wait SHALL remain best-effort: on timeout, or with any flag value, the source SHALL still call `getRootInActiveWindow()` unconditionally and return the resulting tree — the wait SHALL NOT gate whether a tree is captured. The quiet-period argument of the idle wait (the first `waitForIdle` parameter, 1000 ms) SHALL NOT be changed by this flag.

The "window stuck animating" break threshold of **both** slow-capture retry loops (`refreshNewState` and `checkAndRefreshNewState`, which compare the same `getRootInActiveWindowSlow` duration) SHALL be derived from the same flag as `maxIdleTimeoutMs / 1000` seconds, so that lowering the ceiling keeps the breaks firing. With `ape.maxIdleTimeoutMs` at its default `10000`, the idle-wait global timeout SHALL be `10000` ms and each break threshold SHALL be `10` seconds — byte-identical to the pre-change literals (`1000 * 10` and `>= 10`).

- **INV-EXPL-23**: With `ape.maxIdleTimeoutMs` at its default `10000`, slow tree capture and both retry-loop breaks SHALL behave identically to the pre-change implementation (the global timeout is `10000` ms and the break threshold is `10` s).
- **INV-EXPL-24**: The idle wait SHALL remain best-effort at every flag value — `getRootInActiveWindow()` SHALL be invoked unconditionally after the wait, so a tree SHALL always be captured regardless of the timeout.
- **INV-EXPL-25**: Every "window stuck animating" retry-loop break threshold (in `refreshNewState` and `checkAndRefreshNewState`) SHALL be derived from `ape.maxIdleTimeoutMs` (as `maxIdleTimeoutMs / 1000` seconds), never from an independent literal, so the ceiling and the breaks cannot diverge.

#### Scenario: default preserves current behavior
- **WHEN** `ape.maxIdleTimeoutMs` is at its default `10000` and `getRootInActiveWindowSlow` runs
- **THEN** the UiAutomation idle wait SHALL use a `10000` ms global timeout, both retry-loop breaks (`refreshNewState`, `checkAndRefreshNewState`) SHALL fire at `>= 10` seconds, and the observable behavior SHALL be identical to the pre-change implementation

#### Scenario: lowered ceiling caps the wait and capture still proceeds
- **WHEN** `ape.maxIdleTimeoutMs` is lowered (e.g. to `2000`) and a screen has not gone idle within that window
- **THEN** the idle wait SHALL return after at most `2000` ms, `getRootInActiveWindow()` SHALL still be called and its tree returned (no capture is skipped), and both retry-loop breaks SHALL fire at `>= 2` seconds
