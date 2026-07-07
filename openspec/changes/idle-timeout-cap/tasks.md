# Tasks — idle-timeout-cap

## 1. Config flag

- [ ] 1.1 Add `Config.maxIdleTimeoutMs` (`ape.maxIdleTimeoutMs`, `Config.getLong`, default `10000`) near the throttle flags (`Config.java` ~:92, beside `defaultGUIThrottle`) with a current-state comment noting the default equals the former `1000 * 10` literal (P4)

## 2. Wiring

- [ ] 2.1 `MonkeySourceApe.java:480`: replace `mUiAutomation.waitForIdle(1000, 1000 * 10)` with `mUiAutomation.waitForIdle(1000, Config.maxIdleTimeoutMs)` — the `getRootInActiveWindow()` capture at `:484` stays unconditional (best-effort wait, unchanged)
- [ ] 2.2 `StatefulAgent.java:576`: replace the hardcoded `>= 10` seconds break threshold with `>= Config.maxIdleTimeoutMs / 1000` so the "window stuck animating" break stays coupled to the ceiling

## 3. Unit tests (JVM)

- [ ] 3.1 `Config.maxIdleTimeoutMs` default is `10000` and the derived break threshold `maxIdleTimeoutMs / 1000` is `10` — asserting byte-identity with the pre-change literals (INV-EXPL-23, INV-EXPL-25)
- [ ] 3.2 Run the new/updated test via `mvn test -Dtest=<test class>`

## 4. Verification

- [ ] 4.1 Full suite: `mvn test` (0 failures/errors)
- [ ] 4.2 `openspec validate idle-timeout-cap --strict`
- [ ] 4.3 Device smoke — deferred to cmpft3 (`rvsec/rv-android/docs/20260707_cmpft3.md`): with `ape.maxIdleTimeoutMs` lowered (e.g. `2000`) applied identically to every arm, time spent in `getRootInActiveWindowSlow` drops on a known idle-drain app (e.g. `com.dede.android_eggs`) and the tree is still captured (no missing-state regression). No interactive emulator run required here.
