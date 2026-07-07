# Tasks — activity-frontier

## 1. Config and ActionType

- [ ] 1.1 Add `Config.frontierBoostWeight` (`ape.frontierBoostWeight`, default 200; 0 = off) next to `mopWeightWtg`, with a current-state comment (P4)
- [ ] 1.2 Repurpose `Config.activityTriggerEnabled`: default flips to true, comment rewritten to the stagnation-launcher contract (P4 — no gh11 history; the outdated-evidence rationale lives in the proposal)
- [ ] 1.3 Add `ActionType.EVENT_TRIGGER_ACTIVITY` in the `EVENT_*` group (alongside `EVENT_ACTIVATE`, **before** `MODEL_BACK`) — NOT inside the `MODEL_*` block, since `requireTarget()`/`isModelAction()` are ordinal-range checks (`ActionType.java:46-63`; range guarded by INV-EXPL-13) and a MODEL-block placement would flip both predicates `true`. Verify `requireTarget()=false`, `isModelAction()=false` with predicate unit tests

## 2. Lever A — frontier term in the WTG pass

- [ ] 2.1 Add the frontier term to the WTG scoring pass in `StatefulAgent.adjustActionsByGUITree` using its **own** `MopData.getWtgTransitions(activity)` lookup with the same `widgetName`/resource-id match as the MOP boost (do NOT ride `MopScorer.scoreWtg`'s `int` return — it hides the target and fires only when MOP-reachable): on a WTG widget match, if `Graph.getActivityNode(WtgTransition.targetActivity) == null`, add `frontierBoostWeight` **into the action's `wtgBoost` field via `setWtgBoost`** (accumulating with any MOP-reach boost so combined `wtgBoost = mopWeightWtg + frontierBoostWeight`; visible in `[APE-STEP] wtg=`, exemptable by `wtgBoost > 0` downstream)
- [ ] 2.2 Emit `[APE-RV] Frontier boost: state=<a>#<key>, boosted=<n>/<total>, maxBoost=<b>` once per pass when `n > 0`
- [ ] 2.3 Scoring-pass unit tests: unvisited target boosted, visited target not, weight 0 = byte-identical, stacking sum accumulated into `wtgBoost` (INV-WTG-06/-07)

## 3. Lever B — stagnation launcher

- [ ] 3.1 Pure seams: `selectTriggerCandidate(activities, visitedActivities, mainActivity, rrIndex)` (exported ∧ permission==null ∧ !isMain ∧ unvisited; round-robin) and `buildDeepLinkUri(activity)` (first ACTION_VIEW filter with schemes → `scheme://host+path`; null otherwise)
- [ ] 3.2 `ActivityTriggerAction extends Action` carrying className + optional deep-link URI. Note: the `Action` base has **no** `DecisionSource`/`setDecisionSource` (those live only on `ModelAction`, `ModelAction.java:42-44,58,199`), so the action does NOT and cannot carry `DecisionSource.Component` itself — attribution is done in `resolveNewAction` (task 3.6). Expose whatever the else-branch needs to identify this as `Component` (e.g. the `EVENT_TRIGGER_ACTIVITY` type is sufficient)
- [ ] 3.3 Launcher block in `SataAgent.selectNewActionNonnull` after the LLM hooks: fire only at `graphStableCounter == graphStableRestartThreshold / 2`, reset counter on success, fall through when no candidate (INV-CT-05/-06)
- [ ] 3.4 Dispatch case in `MonkeySourceApe` event generation — in the real dispatch switch `generateEventsForActionInternal` (`case EVENT_RESTART` at `MonkeySourceApe.java:841`, NOT the `validateResolvedAction` switch at `:618`): explicit component intent `ComponentName(MopData.getPackageName(), className)` (package from `MopData.getPackageName()`, never from the class name — main-spec INV-CT-04) with `FLAG_ACTIVITY_NEW_TASK`, or `ACTION_VIEW` + URI when deep-link present, via `AndroidDevice.startActivity`; WARNING on failure, run continues
- [ ] 3.5 Delete the `activityTriggerEnabled` branch from `buildTriggerTuples` (`StatefulAgent.java:1038-1040`) so activities leave the probabilistic pool unconditionally — P3; update the trigger-tuple comment (P4)
- [ ] 3.6 **Teach `resolveNewAction`'s non-model else-branch (`StatefulAgent.java:1308-1315`) to read the decision source from the action instead of hardcoding `ModelAction.DecisionSource.SATA`** — attribute `EVENT_TRIGGER_ACTIVITY` as `Component` (special-case the type, or read a source `ActivityTriggerAction` exposes). This is REQUIRED for INV-CT-07: without it every launch's `[APE-STEP]` line emits `SATA`, and `decision_source=Component` (a currently-dead enum value) is never produced. Add a decision-source test asserting the launcher path emits exactly one `[APE-STEP]` with `decision_source=Component` (INV-CT-07 / INV-SEL-04)

## 4. Unit tests (JVM)

- [ ] 4.1 Candidate matrix: exported/permission/main/visited combinations, round-robin wrap, fire-time (not build-time) visited check (INV-CT-06)
- [ ] 4.2 Deep-link URI building: scheme-only, scheme+host, scheme+host+path, VIEW-less filters → null, empty schemes → null (INV-CT-07 dispatch precondition)
- [ ] 4.3 Gate predicate: fires only at the equality point; disabled flag → never; no candidate → no counter reset (INV-CT-05/-08)
- [ ] 4.4 Fix `StatefulAgentTriggerTest` for unconditional activity-exclusion (`buildTriggerTuples` no longer has the `activityTriggerEnabled` branch): (a) **test 19.6 `testActivityTriggerDisabledExcludesActivitiesFromTupleList` (`:108-133`)** — its second half sets `activityTriggerEnabled=true` and asserts `assertTrue("activity present when enabled", hasActivity)`, which becomes a HARD FAILURE once the branch is gone; delete that enabled→present half (keep the disabled→absent assertion, now unconditional), or delete the test; (b) **test 19.2 `testTriggerSkipsNonExportedActivities` (`:53-66`)** — stays green but becomes VACUOUS (activities are never in the pool regardless of `exported`); either update it to assert activities are always excluded, or annotate it as intentionally-vacuous/remove it
- [ ] 4.5 Update `INV-EXPL-05`'s non-model enumeration in the **main** `exploration` spec to include `EVENT_TRIGGER_ACTIVITY` (it lives under a requirement this delta does not otherwise MODIFY, so it is flagged here and applied at sync/archive time — see the exploration delta note); keep it consistent with the modified `ActionType Classification` list
- [ ] 4.6 Run the new/updated test classes

## 5. Verification

- [ ] 5.1 Full suite: `mvn test` (0 failures/errors)
- [ ] 5.2 `openspec validate activity-frontier --strict`
- [ ] 5.3 Device smoke (rebuilt jar): deep app from the depth cohort (e.g. `dev.ukanth.ufirewall_20260301.apk`, 4/32 activities in cmpft2) — `Frontier boost` lines present; on stagnation, one `[APE-STEP] decision_source=Component` + `Triggering activity` dispatch line; cov_act rises vs the cmpft2 trace of the same APK; no crash storm; a simple 1-activity app emits neither
