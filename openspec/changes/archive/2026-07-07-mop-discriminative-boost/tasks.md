# Tasks — mop-discriminative-boost

Depends on change `mop-parser-fidelity` (#0) — apply that first; it restores the flagged widgets this change discriminates on. Scope: `MopScorer.score`, `Config.mopWeightActivity` removal, and the SATA epsilon-greedy short-circuit. Implements `design.md`; satisfies the MODIFIED `mop-guidance` requirements and the ADDED `action-selection` requirement.

## 1. Remove the uniform activity-level fallback (mop-guidance)

- [x] 1.1 Delete the `if (data.activityHasMop(activity)) return Config.mopWeightActivity;` branch in `MopScorer.score` (`MopScorer.java:51-53`) so unflagged/null widgets return `0`. Keep the `data == null` guard, the direct/transitive branches, and the event-type fallback.
- [x] 1.2 Delete `Config.mopWeightActivity` (`Config.java`) and every reference: the `MopScorer` Javadoc weight list, the `mop-guidance` comment in `MopData`, and any `ape.properties` documentation. No dead references or `# removed` comments (P3).
- [x] 1.3 Update `MopScorerTest`: rewrite the four assertions that currently expect `Config.mopWeightActivity` — `MopScorerTest.java:275` (`score("C","b",d,"longClick")`), `:344` (`score("A","plain",data,null)`), `:345` (`score("A","absent",data,null)`), `:365` (`score("A","inner",data,null)`) — to expect `0`. Keep direct → `500` and transitive → `300` assertions unchanged. After deleting `Config.mopWeightActivity` these references must not survive (P3); the grep gate in 3.2 confirms it.
- [x] 1.4 Run `/sdd-test-run MopScorerTest`

## 2. MOP-target greedy short-circuit (action-selection)

- [x] 2.1 Add the short-circuit in `SataAgent.selectNewActionEpsilonGreedyRandomly` (`:456-488`), after the Back-unvisited and Menu-unvisited checks and before `egreedy()`: among `ENABLED_VALID` actions, if any is unvisited with `getMopBoost() > 0`, return the one with the highest `mopBoost` (tie → highest `priority`) and attribute it via `logActionSelected(action, EPSILON_GREEDY)` (INV-SEL-MOP-01/02).
- [x] 2.2 Add `SataAgent` selection unit tests: unvisited `mopBoost>0` selected ahead of roulette; highest `mopBoost` wins; visited MOP target not force-picked; no-op when no action has `mopBoost>0`.
- [x] 2.3 Run `/sdd-test-run`

## 3. Integration & Verification

- [x] 3.1 `mvn package` builds `target/ape-rv.jar` cleanly.
- [x] 3.2 `grep -rn "mopWeightActivity\|INV-MOP-07"` over `src/` returns nothing (full removal confirmed).
- [x] 3.3 Run `/sdd-qa-lint-fix ape` (no-op: SDD config linter=none)
- [x] 3.4 Run `/sdd-verify ape` (tests pass: 374 run / 0 fail / 15 skipped; lint none)
- [/] 3.5 (running: code-reviewer subagent) Invoke `/sdd-code-reviewer` via Skill tool (focus: fallback removal leaves no dead refs; short-circuit bounded to unvisited mopBoost>0; telemetry attribution).
- [x] 3.6 `openspec validate mop-discriminative-boost --strict` passes.

> Out of scope here: the 19-APK fair-test re-run (`docs/20260622_investigacao_mop.md` §7.5) that measures whether discriminative steering moves coverage/violations — it validates the combined #0+#1+#2 stack and decides the short-circuit-strength open question.

## 4. Verified-defect fixes (2026-07-02 synthesis — adversarially confirmed against this worktree)

> Anchors + premises re-verified against the worktree 2026-07-05 (post group-4 of mop-parser-fidelity): all 4.x anchors current, no substance change. Confirmed live: the epsilon-greedy probe (`selectUnvisitedMopTarget`→`pickBestMopTarget`) is wired at `SataAgent:476` but **shadowed** — `selectNewActionEarlyStageForward` (dispatch step 3, `:420`→`:833`→`selectNewActionEarlyStageForwardGreedy:1024`→`findGreedyActionForward:1026`) consumes unvisited actions via `randomPickWithPriority` (`:1072`) *before* the epsilon-greedy path (step 6, `:435`→`:476`) runs. So 4.1's `:1072` probe is a genuine, uncovered second site, not a duplicate of `:476` (C design.md:103). Co-requisite with form-completion 7.2 remains real: at `:1072` the probe must inherit the submit exclusion, which depends on 7.2's convergent predicate.

- [x] 4.1 C3 shadowing fix: `SataAgent.findGreedyActionForward` now computes the form-submit exclusion once (`FormCompletion.hasUnfilledEditText`/`selectSubmitCandidate` on `next`), filters it from the candidate list, probes `pickBestMopTarget(candidates)` before `randomPickWithPriority(candidates)`, and returns the MOP pick when non-null (INV-SEL-MOP-03). Chain order intact; roulette runs unchanged when no boosted candidate. Ranking/exclusion unit-tested (SataAgentMopShortCircuitTest); the instance-method wiring is device-gated per §7.5 (needs agent+graph).
- [x] 4.2 stateMopDensity fix: signature is now 3-arg `stateMopDensity(State, MopData, int timestamp)` (`MopScorer.java`) — `int` matches the codebase-wide timestamp type (`ModelAction.isResolvedAt(int)`, `Agent.getTimestamp():int`); `long` would force a narrowing cast at `isResolvedAt`. Counts only actions whose RESOLVED widget is MOP-flagged (requireTarget && isValid → `isResolvedAt` → `getResolvedNode` → `extractShortId` → `getWidget` → `isDirectMop`/`isTransitiveMop` on the action's `eventTypeOf`), keeping the `data==null` and `activityHasMop` early-outs (INV-MOP-24). Mirrors the StatefulAgent MOP-boost pass resolution. All 5 call sites updated to pass `getTimestamp()`: `SataAgent.java:707, 719, 957, 960, 969` (anchors drifted +5/−1 from the tasks.md numbers).
- [x] 4.3 Unit tests (MopScorerTest): density JVM-testable early-outs added — null MopData → 0 on a dense state; dense non-MOP-activity screen (10 valid actions) → 0 via the `activityHasMop` early-out. The flagged-vs-total count ("2-of-10 flagged") needs resolved live `GUITreeNode`s and is device-gated (class javadoc, §7.5), as are the `findGreedyActionForward` selection scenarios (covered by SataAgentMopShortCircuitTest / device wiring per 4.1).
- [x] 4.4 Central `mvn test` (both changes together) → 413 tests, 0 failures, 0 errors, 15 skipped. MopScorerTest: 22/22.
