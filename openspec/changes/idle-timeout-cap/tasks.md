# Tasks — idle-timeout-cap

## 1. Config flag

- [x] 1.1 Add `Config.maxIdleTimeoutMs` (`ape.maxIdleTimeoutMs`, `Config.getLong`, default `10000`) near the throttle flags (`Config.java` ~:92, beside `defaultGUIThrottle`) with a current-state comment noting the default equals the former `1000 * 10` literal (P4)

## 2. Wiring

- [x] 2.1 `MonkeySourceApe.java:480`: replace `mUiAutomation.waitForIdle(1000, 1000 * 10)` with `mUiAutomation.waitForIdle(1000, Config.maxIdleTimeoutMs)` — the `getRootInActiveWindow()` capture at `:484` stays unconditional (best-effort wait, unchanged)
- [x] 2.2 Replace the hardcoded `>= 10` seconds "window stuck animating" break threshold with `>= Config.maxIdleTimeoutMs / 1000` in **both** slow-capture retry loops — `StatefulAgent.java:576` (`refreshNewState`) and `:640` (`checkAndRefreshNewState`) — so neither break can diverge from the ceiling (both compare the same `getRootInActiveWindowSlow` duration; `refreshNewState`'s is load-bearing, `checkAndRefreshNewState`'s has extra exits but is coupled by construction)

## 3. Unit tests (JVM)

- [x] 3.1 `Config.maxIdleTimeoutMs` default is `10000` and the derived break threshold `maxIdleTimeoutMs / 1000` is `10` — asserting byte-identity with the pre-change literals (INV-EXPL-23, INV-EXPL-25)
- [x] 3.2 Run the new/updated test via `mvn test -Dtest=<test class>`

## 4. Verification

- [x] 4.1 Full suite: `mvn test` (0 failures/errors)
- [x] 4.2 `openspec validate idle-timeout-cap --strict`
- [ ] 4.3 Device smoke — deferred to cmpft3 (`rvsec/rv-android/docs/20260707_cmpft3.md`): with `ape.maxIdleTimeoutMs` lowered (e.g. `2000`) applied identically to every arm, time spent in `getRootInActiveWindowSlow` drops on a known idle-drain app (e.g. `com.dede.android_eggs`) and the tree is still captured (no missing-state regression). No interactive emulator run required here.
