# Tasks — mop-discriminative-boost

Depends on change `mop-parser-fidelity` (#0) — apply that first; it restores the flagged widgets this change discriminates on. Scope: `MopScorer.score`, `Config.mopWeightActivity` removal, and the SATA epsilon-greedy short-circuit. Implements `design.md`; satisfies the MODIFIED `mop-guidance` requirements and the ADDED `action-selection` requirement.

## 1. Remove the uniform activity-level fallback (mop-guidance)

- [ ] 1.1 Delete the `if (data.activityHasMop(activity)) return Config.mopWeightActivity;` branch in `MopScorer.score` (`MopScorer.java:51-53`) so unflagged/null widgets return `0`. Keep the `data == null` guard, the direct/transitive branches, and the event-type fallback.
- [ ] 1.2 Delete `Config.mopWeightActivity` (`Config.java`) and every reference: the `MopScorer` Javadoc weight list, the `mop-guidance` comment in `MopData`, and any `ape.properties` documentation. No dead references or `# removed` comments (P3).
- [ ] 1.3 Update `MopScorerTest`: rewrite the four assertions that currently expect `Config.mopWeightActivity` — `MopScorerTest.java:275` (`score("C","b",d,"longClick")`), `:344` (`score("A","plain",data,null)`), `:345` (`score("A","absent",data,null)`), `:365` (`score("A","inner",data,null)`) — to expect `0`. Keep direct → `500` and transitive → `300` assertions unchanged. After deleting `Config.mopWeightActivity` these references must not survive (P3); the grep gate in 3.2 confirms it.
- [ ] 1.4 Run `/sdd-test-run MopScorerTest`

## 2. MOP-target greedy short-circuit (action-selection)

- [ ] 2.1 Add the short-circuit in `SataAgent.selectNewActionEpsilonGreedyRandomly` (`:414-435`), after the Back-unvisited and Menu-unvisited checks and before `egreedy()`: among `ENABLED_VALID` actions, if any is unvisited with `getMopBoost() > 0`, return the one with the highest `mopBoost` (tie → highest `priority`) and attribute it via `logActionSelected(action, EPSILON_GREEDY)` (INV-SEL-MOP-01/02).
- [ ] 2.2 Add `SataAgent` selection unit tests: unvisited `mopBoost>0` selected ahead of roulette; highest `mopBoost` wins; visited MOP target not force-picked; no-op when no action has `mopBoost>0`.
- [ ] 2.3 Run `/sdd-test-run`

## 3. Integration & Verification

- [ ] 3.1 `mvn package` builds `target/ape-rv.jar` cleanly.
- [ ] 3.2 `grep -rn "mopWeightActivity\|INV-MOP-07"` over `src/` returns nothing (full removal confirmed).
- [ ] 3.3 Run `/sdd-qa-lint-fix ape`
- [ ] 3.4 Run `/sdd-verify ape`
- [ ] 3.5 Invoke `/sdd-code-reviewer` via Skill tool (focus: fallback removal leaves no dead refs; short-circuit bounded to unvisited mopBoost>0; telemetry attribution).
- [ ] 3.6 `openspec validate mop-discriminative-boost --strict` passes.

> Out of scope here: the 19-APK fair-test re-run (`docs/20260622_investigacao_mop.md` §7.5) that measures whether discriminative steering moves coverage/violations — it validates the combined #0+#1+#2 stack and decides the short-circuit-strength open question.
