<!-- This change touches 3 source files and is two independent observability items.
     Group 1 (decision_source attribution) and Group 2 (UICoverage dump) are independent
     and MAY run in parallel. Group 3 integrates the dump at teardown and depends on Group 2.
     Group 4 verifies everything. Critical path: 2 -> 3 -> 4. -->

## 1. Decision-source attribution (item #3, capability `action-selection`)

- [ ] 1.1 In `SataAgent.logActionSelected(action, type)` (`SataAgent.java:218`), replace the unconditional `setDecisionSource(SATA)` with the boost-attribution rule: only when `action` is a `ModelAction` AND `type ∈ {EARLY_STAGE, EPSILON_GREEDY}` AND `max(mopBoost, wtgBoost, menuBoost, coverageBoost) > 0`, set `decisionSource` to the largest-boost mechanism; tie precedence `MOP > WTG > Menu > Coverage`; otherwise keep `SATA`.
- [ ] 1.2 Add a present-tense code comment (P4) documenting that the rule reports the largest contributing boost on a priority-consuming branch, not a counterfactual decisiveness claim. Do NOT add a config flag; do NOT reintroduce `llmMaxCalls`.
- [ ] 1.3 Confirm `StatefulAgent.resolveNewAction()` emit path (`:1266-1272`) is unchanged and still emits exactly one `[APE-STEP]` line per finalized action (INV-SEL-04).
- [ ] 1.4 Run `/sdd-doc-code src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java`

## 2. UICoverage teardown dump (item #4, capability `ui-coverage`)

- [ ] 2.1 Add a read-only `dump(...)` method to `UICoverageTracker` (`UICoverageTracker.java`) that, for each `State` in `stateData`, computes `discovered`, `interacted` (distinct widgets with count > 0), `gap = 1 - D/W` (`1.0` when `W == 0`), and a `byType` breakdown derived from the `"<xpath>|<TYPE>"` / `"<TYPE>"` key convention owned by `widgetId()` (`:215`).
- [ ] 2.2 Accept a `mopReach` predicate over `State` so the dump stays decoupled from `MopData`; emit one line per tracked state in the format `[APE-RV] UICOV state=<stateKey> discovered=<W> interacted=<D> gap=<1-D/W> byType=... mopReach=<0|1>`.
- [ ] 2.3 Ensure the dump mutates nothing — no register/record/evict, no change to `stateData`/`activityRollup`/counts (INV-COV-07).
- [ ] 2.4 (Optional) Emit one `UICOV` line at LRU eviction so states evicted before teardown are reported once.
- [ ] 2.5 Run `/sdd-doc-code src/main/java/com/android/commands/monkey/ape/utils/UICoverageTracker.java`

## 3. Teardown integration

- [ ] 3.1 In `SataAgent.tearDown()` (`SataAgent.java:234`), invoke the dump, supplying `state -> _mopData != null && _mopData.activityHasMop(state.getActivity())` (`MopData.java:649`) for `mopReach`.
- [ ] 3.2 Confirm the dump runs exactly once per tracked state at teardown and does not interfere with the existing `printCounters()` output.
- [ ] 3.3 Run `/sdd-verify ape`

## 4. Verification

- [ ] 4.1 Build: `mvn package` produces `target/ape-rv.jar` with no new dependencies.
- [ ] 4.2 Device run on `test-apks/cryptoapp.apk` with `ape.mopDataPath` set: grep `[APE-STEP]` and confirm `decision_source=MOP` appears on MOP-boosted `EARLY_STAGE`/`EPSILON_GREEDY` steps, stays `SATA` on `USE_BUFFER` and unboosted steps, and tie precedence is `MOP>WTG>Menu>Coverage`.
- [ ] 4.3 Device run: confirm exactly one `[APE-RV] UICOV` line per tracked state at teardown, with `gap` matching discovered/interacted and `mopReach` matching `activityHasMop`; confirm `getTotalInteractions()` is unchanged across the dump (INV-COV-07).
- [ ] 4.4 Run `/sdd-qa-lint-fix ape`
- [ ] 4.5 Run `/sdd-verify ape`
- [ ] 4.6 Invoke `/sdd-code-reviewer` via Skill tool
