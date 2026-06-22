<!-- Dispatch hints (~10 source files; subagent orchestration OPTIONAL, not required):
     - Groups 1 (A-3) and 2 (A-6) are independent, single-file-ish, can go first/parallel.
     - Groups 3 (A-2), 4 (A-5), 5 (A-4) ALL touch StatefulAgent.java — do NOT parallelize across
       agents without coordination; prefer sequential edits to StatefulAgent (3 -> 4 -> 5) or one agent.
     - Group 6 integrates/validates — runs last.
     - ape has NO sdd-*/rv-* skills: verification is JUnit (where it exists) + a real-device run. -->

<!-- Source paths: all under src/main/java/com/android/commands/monkey/ape/ —
     utils/{Config,MopScorer,MopData,UICoverageTracker}.java, agent/{SataAgent,StatefulAgent}.java,
     model/{ModelAction,StateKey}.java, llm/{LlmRouter,ScreenshotCapture}.java, naming/Name.java.
     Tests under src/test/java/com/android/commands/monkey/ape/utils/{Config,MopScorer,MopData}Test.java. -->

## 1. A-3 — Decouple component triggering

- [x] 1.1 `Config.java:169-170`: change `componentPercentage` default to literal `0.0` (remove the `mopDataPath != null ? 0.05 : 0.0` ternary); update the inline comment to current-state (no "defaults to 0.05 when mopDataPath set")
- [x] 1.2 Add/adjust JUnit in `ConfigTest`: default is `0.0` with and without `mopDataPath`; explicit `ape.componentPercentage=0.10` yields `0.10`
- [x] 1.3 `mvn -Dtest=ConfigTest test` passes

## 2. A-6 — LLM throughput and secure windows

- [x] 2.1 `LlmRouter.java:245-249`: on null screenshot, call `breaker.recordFailure()` before `return null` (parity with the IOException/parse branches)
- [x] 2.2 `Config.java:153`: clamp `llmPercentage` to `[0,1]` at load (`< 0 -> 0.0`, `> 1 -> 1.0`)
- [x] 2.3 Remove the stale `llmMaxCalls` line from `CLAUDE.md:133` (no occurrence in source; do NOT reintroduce a call budget)
- [x] 2.4 JUnit: `llmPercentage` clamp default asserted (`ConfigTest.testLlmPercentage_defaultInRangeUnchanged`, value `0.02`). The out-of-range clamp cases (1.5→1.0, -0.2→0.0) are NOT unit-tested: `Config.llmPercentage` is `static final`, captured once at class load, so they cannot be re-evaluated in the same JVM (decision: default-only, boundaries guaranteed by the `Math.max/min` clamp). The null-screenshot breaker path is NOT unit-tested: it lives inside `selectAction()`, which loads `AndroidDevice`→`android.os.RemoteException` (absent from the JVM test classpath → `NoClassDefFoundError`, an `Error` the internal try/catch does not catch); Mockito is also unusable here (byte-buddy cannot subclass `ScreenshotCapture` against the Android stub jar on Java 25). Covered instead by the on-device gate 6.3c. See note in `LlmRouterTest`.
- [x] 2.5 `mvn test` for the affected test classes passes (`ConfigTest`, `LlmRouterTest`)

## 3. A-2 — MOP scorer correctness

- [x] 3.1 `MopScorer.java:40-54` (B4): reorder so the activity fallback (`+mopWeightActivity`) is reachable for a resolved-but-unflagged widget; remove the early `return 0` in the `w != null` branch
- [x] 3.2 `MopData`/`MopScorer` (B6): add `eventType` normalization (snake_case ⇄ camelCase → canonical token) applied before comparison. Done in `MopData.normalizeEventType` (lowercase + strip separators), applied on BOTH the map-building side (`deriveWidgetMopFlags`/`orInto`) and the query side (`Widget.isDirectMop`/`isTransitiveMop`). Producer/JSON unchanged.
- [x] 3.3 `StatefulAgent.adjustActionsByGUITree()` near `:1364` (B3): build the candidate-id set `{shortId} ∪ ancestorIds(≤2) ∪ childIds(≤2)` from the GUI node, score each via the existing `score()`, take the max; add a hit-rate log (design D2 — no `score()` signature change). Done as private `mopBoostWithContainment`; the MOP-boost log line gains a `containment=N` hit-rate field.
- [x] 3.4 JUnit `MopScorerTest`/`MopDataTest`: resolved-but-unflagged → +100 (`testScoreResolvedButUnflaggedFallsBackToActivity`); eventType snake⇄camel equal (`testEventTypeNormalizationSnakeCamelEqual`); B3 foundation (container-vs-child id, `testScoreByContainerVsChildId`). NOTE: the full B3 tree traversal (`mopBoostWithContainment`) needs a live `GUITreeNode` tree and is not JVM-unit-testable (same constraint as the other StatefulAgent runtime logic, cf. `StatefulAgentTriggerTest` §22) — covered by the device gate 6.3. Also updated existing test 17.3 to the post-B4 value (`mopWeightActivity` instead of `0`) per INV-MOP-07.
- [x] 3.5 `mvn -Dtest=MopScorerTest,MopDataTest test` passes (also ran `StatefulAgentTriggerTest`: 63 tests green)

## 4. A-5 — Step decision logging

- [x] 4.1 `ModelAction`: add a `decisionSource` enum field (`SATA|MOP|Coverage|LLM|Fuzz|Menu|WTG|Component|Budget`). Also added per-mechanism boost fields (`mopBoost`/`wtgBoost`/`coverageBoost`/`menuBoost` + `resetBoosts()`) to back the `[APE-STEP]` per-mechanism content (spec scenario).
- [x] 4.2 `SataAgent.selectNewActionNonnull()`: `decisionSource` set on every return path. SATA chain centralized in `logActionSelected` (covers all SATA strategy returns incl. `:275` SATURATED_STATE); the budget early-return (`:317`) sets `Budget`; the three LLM hooks (`:328/:339/:348`) set `LLM`. Component triggering (`:354`) has no return path (side-effect only).
- [x] 4.3 `StatefulAgent.resolveNewAction()` after `:1259`: emits one `[APE-STEP]` line (`step#`=getTimestamp(), `activity`, `state`, `action`, `decision_source`, `priority`, and per-mechanism `mop`/`wtg`/`coverage`/`menu` boosts) for the finalized action; non-model actions get a boost-free line. Boosts are captured per action in `adjustActionsByGUITree` (reset each pass).
- [x] 4.4 JUnit `ModelActionTest` (default `SATA`, set/get, enum of 9 sources, `resetBoosts`). The end-to-end one-`[APE-STEP]`-per-action emission lives in `StatefulAgent.resolveNewAction` (needs the live agent/tree) and is device-validated (6.3b), consistent with the repo's StatefulAgent-runtime testing convention.
- [x] 4.5 `mvn test` passes for affected classes (`ModelActionTest`, `StatefulAgentTriggerTest`, `DynamicEpsilonTest`, `StateTest`, `MopScorerTest`, `MopDataTest`: 83 green)

## 5. A-4 — Faithful UI coverage

- [x] 5.1 `UICoverageTracker.widgetId`: target-action key now `toXPath()+"|"+type.name()` (INV-COV-06); non-target stays keyed by type.
- [x] 5.2 `UICoverageTracker.stateData` is a bounded access-ordered `LinkedHashMap` (`removeEldestEntry` past `Config.coverageMaxStates`, default 2000); on eviction `foldIntoRollup` merges counts (by max) into a per-Activity `activityRollup` (INV-COV-05). Bound configurable via `ape.coverageMaxStates`.
- [x] 5.3 `getActivityCoverageGap(String)` reports per-Activity coverage by aggregating live fragments + the rollup (collapses naming fragments). Class javadoc updated to current state (key format, bounding, rollup) per P4. Per-state `getCoverageGap` stays fragment-level for steering.
- [x] 5.4 JUnit `UICoverageTrackerTest`: distinct action types on same target are separate (`testDistinctActionTypesOnSameTargetAreSeparate`); per-Activity aggregation across fragments (`testActivityCoverageAggregatesFragments`); eviction past the bound preserves the rollup (`testBoundedStateDataPreservesRollupOnEviction`, floods > bound states). Updated existing tests that encoded the pre-INV-COV-06 bare-xpath key to the `|TYPE` form. Device gate 6.3d covers the live-run memory-bounded / coverage-not-zeroed behavior.
- [x] 5.5 `mvn test` passes for affected classes (`UICoverageTrackerTest` 27, `ConfigTest` 12: 39 green)

## 6. Integration & Verification (manual — no sdd-*/rv-* skills in ape)

- [x] 6.1 `mvn package` BUILD SUCCESS (produces `target/ape-rv.jar` via d8). Verified: BUILD SUCCESS, `target/ape-rv.jar` (248 KB) contains `classes.dex`, no `.java` (INV-BUILD-01/06).
- [x] 6.2 Full JUnit suite green: `mvn test`. Verified: 356 tests, 0 failures, 0 errors, 15 skipped (Android-runtime + live-LLM gated).
- [x] 6.3 Device run via `rv-experiment run --tools aperv:sata_mop` on cryptoapp (jca, 120s, `results/gh15_e2e`, 1/1 successful). Confirmed: **(a)** 0 `[APE-RV] Triggering` in trace+logcat (no explicit `ape.componentPercentage`) → A-3; **(b)** 136 `[APE-STEP]` lines = 136 distinct step numbers, 0 dups/gaps, all attributed with per-mechanism boosts → A-5; **(d)** `containment=N` on every MOP-boost line, `stateData` bounded (no eviction at 2 states; eviction path JUnit-covered), 0 OOM, coverage CSV populated (50% act, not zeroed) → A-4. **(c)** the FLAG_SECURE→LLM-breaker gate is N/A to this run (cryptoapp is not secure-window; `sata_mop` has no LLM) — optional, needs a secure-window app + an LLM variant + a live SGLang server.
- [x] 6.4 Manual code review of the diff against P1–P4. Verified across all 9 changed files: minimal, current-state comments with invariant refs, no dead code/shims/lineage. (Pre-existing gh13 "Backward-compatible 3-arg score" comment at `MopScorer.java:22` is outside the gh15 diff.)
- [x] 6.5 `grep` for dangling references: old `componentPercentage` ternary gone; `llmMaxCalls` 0 occurrences in src/CLAUDE.md/properties; early `return 0` replaced by fall-through. Consistent state.
