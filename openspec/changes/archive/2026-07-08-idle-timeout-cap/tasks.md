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
- [x] 4.3 Device-smoke characterization via cmpft3 (`rvsec/rv-android/docs/20260708_analise_efeito_changes_cmpft3.md` §2, §10, §11). Verdict **INCONCLUSIVE / NOT-TESTABLE (harness gap) — real validation carried to gh74**. The code refactor is complete and unit-verified (tasks 2.1/2.2/3.1/3.2 green), but the ceiling was never lowered: `maxIdleTimeoutMs` ran at its compiled default **10000**, and `10000/1000 == 10` is byte-identical to the pre-change literals, so cmpft3 exercised no reduced cap. Empirical confirmation (654 runs/cohort): `getRootInActiveWindowSlow` distribution within noise (n 34,251 vs 34,245; max single call 11,192 ms vs 10,915 ms; calls >10s 112 vs 90 — cmpft3 slightly worse), and the 200s+ idle pathology on `com.dede.android_eggs_76` is still present (206,439 ms vs 216,711 ms), proving the cap was not lowered. **Root cause = harness gap**: `modules/aperv-tool/.../tool.py`'s `APERV_PROPERTY_MAPPING` never emits `max_idle_timeout_ms`. Real validation is BLOCKED until gh74 (`PAMunb/rvsec#74`) adds the mapping and re-runs with a lowered ceiling (e.g. 2000). NOTE for the user: §11 recommends **RE-TEST or DROP** for this change — a keep/drop decision that is open independent of archiving.
