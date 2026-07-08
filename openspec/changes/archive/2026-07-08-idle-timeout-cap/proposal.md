# idle-timeout-cap

## Why

The cmpft2 trace audit (657 runs) found the UiAutomation idle wait in slow tree capture is the single largest measurable drain on the exploration budget: `getRootInActiveWindowSlow` (`MonkeySourceApe.java:478-485`) spent 10,668 s total across 34,119 fetches, including 90 hard 10 s timeouts. The worst case — `com.dede.android_eggs` rep2 — burned 216,711 ms (72% of its 300 s budget) across 24 fetches over just 39 steps; gauguin, photoprism, osmtracker, trail_sense, chess, aegis, and wikipedia each lose 50–73 s/run to it.

The ceiling is a hardcoded upstream constant: `mUiAutomation.waitForIdle(1000, 1000 * 10)` — quiet-period 1 s, global timeout 10 s (inherited from the original APE import, not an aperv addition). On timeout it throws `TimeoutException`, which is caught, `printStackTrace`'d (`:481-483`), and then `getRootInActiveWindow()` is called **unconditionally** (`:484`). The tree is therefore always captured; the wait is best-effort animation settling, not a correctness gate. There is no way to lower the ceiling for a time-boxed experiment without recompiling.

## What Changes

- Add a config flag `ape.maxIdleTimeoutMs` (long, default `10000`) that parametrizes the global timeout of the UiAutomation idle wait in `getRootInActiveWindowSlow` (`MonkeySourceApe.java:480`). The default `10000` is byte-identical to the current `1000 * 10` literal — no default-path behavior changes.
- Derive the `refreshNewState` "window stuck animating" break threshold (`StatefulAgent.java:576`, the hardcoded `>= 10` seconds signal) from the same flag (`maxIdleTimeoutMs / 1000`) so the break keeps firing when the ceiling is lowered. With the default this is `10000 / 1000 = 10` — unchanged.
- Rollback / experiment knob only: no new decision logic, no seam. The experiment harness (cmpft3) lowers it (e.g. `2000`) via `ape.properties` applied identically to every arm.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `exploration`: add the idle-timeout-cap requirement — the slow-capture idle-wait ceiling and the coupled refresh break threshold are parametrized by one flag whose default preserves current behavior; the wait remains best-effort (capture always proceeds).

## Impact

- **Code**: `Config.java` (one flag near the throttle flags, ~:92), `MonkeySourceApe.java:480` (one literal → flag), `StatefulAgent.java:576` (one literal → derived flag). Agents' control flow, model, naming, UICoverageTracker untouched.
- **Behavior**: none on the default path (default is byte-identical). When lowered, the idle wait caps sooner and `getRootInActiveWindow()` still runs unconditionally, so the tree is still captured — only the best-effort settling window shrinks.
- **Experiment validity**: the flag is arm-neutral — cmpft3 applies the same `ape.properties` value to every arm; the change itself alters no default-path behavior, so it cannot bias an arm comparison.
- **Archive ordering**: standalone against the main `exploration` spec; no dependency on unarchived deltas.
- **Risk**: a lowered ceiling could truncate settling on a genuinely slow screen, yielding a mid-animation tree — but that tree was captured before this change too (the timeout path already returns `getRootInActiveWindow()`), and `refreshNewState`/`checkAndRefreshNewState` already retry (retry=5) on such captures. Rollback = leave the default.
