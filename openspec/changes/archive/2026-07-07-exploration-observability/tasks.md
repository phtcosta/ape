<!-- This change touches 3 source files and is two independent observability items.
     Group 1 (decision_source attribution) and Group 2 (UICoverage dump) are independent
     and MAY run in parallel. Group 3 integrates the dump at teardown and depends on Group 2.
     Group 4 verifies everything. Critical path: 2 -> 3 -> 4. -->

## 1. Decision-source attribution (item #3, capability `action-selection`)

- [x] 1.1 **(SUPERSEDED by §5.1/5.2 — the branch-level rule below was the initial implementation; the authoritative target is the sub-path attribution in Group 5, which adds `Form` and re-scopes to priority-consuming pick sites. Left checked to record the delivered branch-level pass; the current-spec behavior is NOT done until §5 lands.)** In `SataAgent.logActionSelected(action, type)` (`SataAgent.java:218`), replace the unconditional `setDecisionSource(SATA)` with the boost-attribution rule: only when `action` is a `ModelAction` AND `type ∈ {EARLY_STAGE, EPSILON_GREEDY}` AND `max(mopBoost, wtgBoost, menuBoost, coverageBoost) > 0`, set `decisionSource` to the largest-boost mechanism; tie precedence `MOP > WTG > Menu > Coverage`; otherwise keep `SATA`.
- [x] 1.2 Add a present-tense code comment (P4) documenting that the rule reports the largest contributing boost on a priority-consuming branch, not a counterfactual decisiveness claim. Do NOT add a config flag; do NOT reintroduce `llmMaxCalls`.
- [x] 1.3 Confirm `StatefulAgent.resolveNewAction()` emit path (`:1266-1272`) is unchanged and still emits exactly one `[APE-STEP]` line per finalized action (INV-SEL-04).
- [x] 1.4 (satisfied inline: comprehensive javadoc/comments written) Run `/sdd-doc-code src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java`

## 2. UICoverage teardown dump (item #4, capability `ui-coverage`)

- [x] 2.1 Add a read-only `dump(...)` method to `UICoverageTracker` (`UICoverageTracker.java`) that, for each `State` in `stateData`, computes `discovered`, `interacted` (distinct widgets with count > 0), `gap = 1 - D/W` (`1.0` when `W == 0`), and a `byType` breakdown derived from the `"<xpath>|<TYPE>"` / `"<TYPE>"` key convention owned by `widgetId()` (`:215`).
- [x] 2.2 Accept a `mopReach` predicate over `State` so the dump stays decoupled from `MopData`; emit one line per tracked state in the format `[APE-RV] UICOV state=<stateKey> discovered=<W> interacted=<D> gap=<1-D/W> byType=... mopReach=<0|1>`.
- [x] 2.3 Ensure the dump mutates nothing — no register/record/evict, no change to `stateData`/`activityRollup`/counts (INV-COV-07).
- [~] 2.4 (Optional — SKIPPED: teardown-only by design, avoids mid-run noise; P1) Emit one `UICOV` line at LRU eviction so states evicted before teardown are reported once.
- [x] 2.5 (satisfied inline: comprehensive javadoc written) Run `/sdd-doc-code src/main/java/com/android/commands/monkey/ape/utils/UICoverageTracker.java`

## 3. Teardown integration

- [x] 3.1 In `SataAgent.tearDown()` (`SataAgent.java:234`), invoke the dump, supplying `state -> _mopData != null && _mopData.activityHasMop(state.getActivity())` (`MopData.java:649`) for `mopReach`.
- [x] 3.2 Confirm the dump runs exactly once per tracked state at teardown and does not interfere with the existing `printCounters()` output.
- [x] 3.3 Run `/sdd-verify ape`

## 4. Verification

- [x] 4.1 Build: `mvn package` produces `target/ape-rv.jar` with no new dependencies.
- [x] 4.2 [DEFERRED to §6 end-to-end / emulator] Device run on `test-apks/cryptoapp.apk` with `ape.mopDataPath` set: grep `[APE-STEP]` and confirm `decision_source=MOP` appears on MOP-boosted `EARLY_STAGE`/`EPSILON_GREEDY` steps, stays `SATA` on `USE_BUFFER` and unboosted steps, and tie precedence is `MOP>WTG>Menu>Form>Coverage` (§5.2). — DONE via cmpft/cmpft2 device experiments (rvsec/rv-android/docs/20260707_relatorio_cmpft2.md)
- [x] 4.3 [DEFERRED to §6 end-to-end / emulator] Device run: confirm exactly one `[APE-RV] UICOV` line per tracked state at teardown, with `gap` matching discovered/interacted and `mopReach` matching `activityHasMop`; confirm `getTotalInteractions()` is unchanged across the dump (INV-COV-07). — DONE via cmpft/cmpft2 device experiments (rvsec/rv-android/docs/20260707_relatorio_cmpft2.md)
- [x] 4.4 Run `/sdd-qa-lint-fix ape` (no-op: SDD config linter=none, checkstyle not installed)
- [x] 4.5 Run `/sdd-verify ape`
- [/] 4.6 (running: code-reviewer subagent) Invoke `/sdd-code-reviewer` via Skill tool

## 5. Verified-attribution fixes (2026-07-02 synthesis — adversarially confirmed against this worktree)

<!-- The implemented attributeDecisionSource (SataAgent.java:240-263) applies at branch level
     (EARLY_STAGE/EPSILON_GREEDY), mislabeling non-priority sub-paths, ignores formBoost,
     and has zero unit tests. These tasks realign it with the updated delta spec. -->

- [x] 5.1 Scope attribution to priority-consuming sub-paths: attribute at the pick sites — `randomlyPickAction` (`SataAgent.java:495`), `randomPickWithPriority` over EARLY_STAGE candidates (`:1106`), `selectUnvisitedMopTarget` (`:484`), and the EARLY_STAGE MOP preference (`pickBestMopTarget` in `findGreedyActionForward`, `:1099`) — and return `SATA` for graph-navigation/shortest-path (`:1121,1133`, backward `:1146,1158`, ABA `:741`), Back/Menu-unvisited (`:461,468`), and `greedyPickLeastVisited` (`:492`) picks. `logActionSelected` no longer boost-attributes; it sets `SATA` only for the non-priority branch types (EARLY_STAGE/EPSILON_GREEDY own their source at the pick sites)
- [x] 5.2 Add `Form` to `ModelAction.DecisionSource` (`ModelAction.java:42-44`, appended so existing ordinals are stable) and include `getFormBoost()` in the largest-boost rule (`attributeByLargestBoost`) with tie precedence `MOP > WTG > Menu > Form > Coverage`
- [x] 5.3 Add `clock=<epochMillis>` (`System.currentTimeMillis()`, distinct from the `step` counter) to both `[APE-STEP]` lines (`StatefulAgent.resolveNewAction`, ModelAction + non-model branches); Logger/.trace only, never logcat
- [x] 5.4 Unit tests for the attribution rule (`SataAgentDecisionSourceTest`): roulette pick attributed by largest boost; Form wins when formBoost largest; tie precedence `MOP>WTG>Menu>Form>Coverage`; no-boost/negative → SATA. Bumped `ModelActionTest` `values().length` 9→10 and added `Form` spot-check. Menu-unvisited/least-visited/navigation stay-SATA wiring is graph/device-gated (documented in the test javadoc; validated on the emulator per §4.2); INV-SEL-04 single-line guarantee unchanged
- [x] 5.5 LLM router stats: split screenshot-capture failures out of the aggregate `null=` counter into a dedicated `screenshot_failed=` field in the end-of-run stats line (`LlmRouter.java:575-585`), so per-app FLAG_SECURE degradation to SATA is countable post-hoc
- [x] 5.6 Run `/sdd-test-run ape` (central `mvn test`: 453 tests, 0 fail, 0 err, 19 skipped)
