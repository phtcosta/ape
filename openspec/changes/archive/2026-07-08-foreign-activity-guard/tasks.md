# Tasks — foreign-activity-guard

## 1. Config and pure seam

- [x] 1.1 Add `Config.foreignActivityGuard` (`ape.foreignActivityGuard`, default true) with a current-state comment (P4)
- [x] 1.2 Add the static `SYSTEM_INTERACTION_PACKAGES` set (`com.android.packageinstaller`, `com.android.permissioncontroller`, `com.google.android.permissioncontroller` — the Google-image entry is what the RVSec AVD actually shows; the whitelist is guard-local only, it does not grant permissions or change `checkAppActivity`) and the pure static `shouldModel(String pkg, boolean filterAccepts, Set<String> systemWhitelist)` (null pkg → true) to a new **dependency-free `com.android.commands.monkey.ape.ForeignActivityGuard`** class. (They cannot live in `MonkeySourceApe`: that class cannot be JVM-class-loaded off-device — its `UiAutomation` field pulls in `android.app.IUiAutomationConnection`, absent from the test classpath — which would make the seam untestable, defeating the whole point. `MonkeySourceApe.generateEvents` delegates to `ForeignActivityGuard`.)

## 2. Guard wiring (MonkeySourceApe)

- [x] 2.1 Insert the guard block in `generateEvents` inside the `while (repeat-- > 0)` refetch loop, after the `info != null` check and before `updateState`: evaluate `shouldModel` with `topComp.getPackageName()` + `isPackageValid` (mirroring `checkAppActivity`'s predicate); on negative, enqueue one `generateKeyBackEvent()` + throttle and return without modeling; `topComp == null` bypasses the guard
- [x] 2.2 Add the once-per-package log throttle (`Set<String> deflectedPackages`) emitting `[APE-RV] Foreign activity: pkg=<pkg> -> BACK` on first deflection only
- [x] 2.3 Delete the dead `checkPackage(ComponentName, AccessibilityNodeInfo)` (`MonkeySourceApe.java:910-922`, no callers — P3); update the `generateEvents` comment to the current contract (P4)

## 3. Unit tests (JVM, pure seam)

- [x] 3.1 `shouldModel` matrix: in-package accepted; foreign rejected; each of the three whitelist packages accepted (`com.android.packageinstaller`, `com.android.permissioncontroller`, `com.google.android.permissioncontroller`); `com.android.systemui` NOT whitelisted (rejected → guard BACKs out); null pkg accepted (INV-EXPL-20/-21)
- [x] 3.2 Log-throttle semantics: first deflection of a package signals log, repeat does not (plain `Set` contract test alongside the seam)
- [x] 3.3 Run the new test class via `mvn test -Dtest=MonkeySourceApeForeignGuardTest`
- [x] 3.4 Flag-off assertion (INV-EXPL-22): with `Config.foreignActivityGuard=false` the guard block is bypassed — no BACK deflection, no guard log line; event generation is identical to the pre-guard path (the flag gates the whole block, `shouldModel` itself has no flag)

## 4. Verification

- [x] 4.1 Full suite: `mvn test` (0 failures/errors)
- [x] 4.2 `openspec validate foreign-activity-guard --strict`
- [x] 4.3 Device-smoke characterization via cmpft3 (`rvsec/rv-android/docs/20260708_analise_efeito_changes_cmpft3.md` §3, §10). Verdict **PASS (model purity)**. Evidence: `[APE-RV] Foreign activity` fired **22×** (22 runs), 100% deflecting `com.google.android.apps.nexuslauncher`, always post-crash; the launcher was modeled as a `[APE-STEP]` State in cmpft2 (20 runs / 25 steps) → **0 in cmpft3** (100% leak eliminated), with 4 apps causally linking cmpft2-leak↔cmpft3-guard (dede.android_eggs, infomaniak.euria, matedroid, openbible). Corrections vs the original gate text: (a) the winterkongress `NexusLauncherActivity` example is a **renamed in-app package** (runs in-process — correctly NOT deflected); (b) the "~40% foreign-leak" premise is **refuted** — genuine foreign leak is 3.0% of runs (launcher-only), low N by corpus construction (offline F-Droid apps, no cross-app intents); (c) the "no restart storm" clause is bundle-confounded (relaunches/1000 steps −62% but not isolable in the single-arm run). Controlled per-arm validation deferred to the gh74 fair-test (`PAMunb/rvsec#74`).
