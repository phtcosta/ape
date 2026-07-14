# Design — idle-timeout-cap

## Context

`getRootInActiveWindowSlow` (`MonkeySourceApe.java:478-485`) waits for the UI to go idle before snapshotting:
```
mUiAutomation.waitForIdle(1000, 1000 * 10);   // quiet-period 1 s, global timeout 10 s
```
On `TimeoutException` the wait is caught and `getRootInActiveWindow()` runs unconditionally — the wait never blocks the capture, it only settles animations. The `1000 * 10` global ceiling is a hardcoded upstream constant. Callers of the slow variant: `StatefulAgent.refreshNewState():548` and `checkAndRefreshNewState():597` (each inside a retry=5 loop) and `ApeAgent:346` (BadStateException recovery). The main capture path (`MonkeySourceApe:790`) uses the fast variant with no idle wait, so it is unaffected.

Two identical couplings exist: both `refreshNewState` (`StatefulAgent.java:576`) and `checkAndRefreshNewState` (`StatefulAgent.java:640`) break their retry loops when a single `getRootInActiveWindowSlow` capture took `>= 10` seconds (a hardcoded literal used as a "window stuck animating" signal). Both measure `end - begin` around the same slow-capture call. If the ceiling is lowered but these literals are not, the breaks stop firing and the loops' semantics drift — so both are derived from the flag. (`refreshNewState`'s break is load-bearing — its only time-based exit; `checkAndRefreshNewState` also has retry/early-exit paths — but INV-EXPL-25 keeps the ceiling and *every* such break coupled by construction regardless of blast radius.)

The cmpft2 audit measured this wait as the largest aggregate budget drain of any single mechanism (10,668 s over 34,119 fetches; worst run 216,711 ms = 72% of budget). No other open change touches it.

## Architecture

One flag, three literals. `ape.maxIdleTimeoutMs` (default 10000) replaces the `1000 * 10` global-timeout literal at the call site and, divided by 1000, replaces the `>= 10` seconds break threshold at **both** retry-loop sites (`refreshNewState:576`, `checkAndRefreshNewState:640`). No new methods, no seam, no decision logic.

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `Config.maxIdleTimeoutMs` | flag `ape.maxIdleTimeoutMs` (default 10000) | properties | long (ms) |
| `getRootInActiveWindowSlow` idle wait | best-effort settle, then capture unconditionally | flag | `waitForIdle(1000, maxIdleTimeoutMs)` |
| `refreshNewState` + `checkAndRefreshNewState` break thresholds | "window stuck animating" retry break (both slow-capture retry loops) | flag | `>= maxIdleTimeoutMs / 1000` seconds |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| INV-EXPL-23 (default preserves behavior) | default `10000` == `1000 * 10`; `10000 / 1000` == `10` | JVM assertion on the flag constant + derived value |
| INV-EXPL-24 (wait stays best-effort) | capture line `:484` unchanged — still unconditional after the wait | reasoning invariant over the unchanged control flow (device-observable in cmpft3) |
| INV-EXPL-25 (break derives from same flag) | both `:576` and `:640` literals → `Config.maxIdleTimeoutMs / 1000` | JVM assertion that the derived seconds match the ms flag |

## Decisions

1. **Parametrize the global timeout, not the quiet period.** `waitForIdle(idleTimeout, globalTimeout)` — the first arg (1 s of quiet before "idle") is a settling-quality knob left untouched; the second (10 s ceiling) is what caps wall-clock cost. Only the ceiling is the budget drain, so only it is parametrized. Default keeps both bytes identical (`1000, 10000`).
2. **Single flag, not one per literal.** The `:576` and `:640` break thresholds are semantically the same 10 s ceiling expressed in seconds (both compare the same `getRootInActiveWindowSlow` duration) — deriving them (`/ 1000`) from the one flag keeps all three literals coupled by construction, so lowering the ceiling cannot silently disable either break (P1 — no gratuitous second flag). Both retry-loop breaks are covered even though only `refreshNewState`'s is load-bearing: an identical literal left hardcoded is exactly the silent divergence INV-EXPL-25 exists to prevent, and at the default it is byte-identical, so coupling it carries no behavioral risk.
3. **`long` ms, default 10000.** Matches the neighboring throttle flags (`defaultGUIThrottle`, `swipeDuration` — all `Config.getLong`, `MonkeySourceApe.java` throttles in ms). `10000 / 1000 == 10` is exact integer division, so the derived break threshold is exact at the default.
4. **No lower-bound clamp.** cmpft3 owns the value; a clamp would be speculative policy. A value of 0 is a legal `waitForIdle` global timeout (returns immediately after the quiet check) and the capture still proceeds — the best-effort contract holds at any value (P3 — no defensive scaffolding for a knob the experiment controls).

## Testing Strategy

| Layer | What to test | How |
|-------|-------------|-----|
| Unit (JVM) | `Config.maxIdleTimeoutMs` default is `10000`; the derived break-seconds (`maxIdleTimeoutMs / 1000`) equal `10` at the default — i.e. the default is byte-identical to the pre-change literals | plain JUnit on the `Config` constant (the flag plumbing and the derivation are the JVM-testable parts) |
| Device smoke | on a cmpft2 idle-drain app (e.g. `com.dede.android_eggs`), a lowered `ape.maxIdleTimeoutMs` reduces time-in-`getRootInActiveWindowSlow` and the tree is still captured (no missing-state regression) | deferred to cmpft3 (`rvsec/rv-android/docs/20260707_cmpft3.md`) — `mUiAutomation` is Android-runtime only |

## Open Questions

- None. The default is fixed by the byte-identity requirement; the lowered value is cmpft3's parameter, not this change's.
