<!-- Subagent dispatch hints:
     - Group 1 (Wave 0, shared skeleton) runs first and is committed alone; after it, ape/runtime/ is closed to Groups 2–5.
     - Groups 2 (A1), 3 (A2), 4 (A3), 5 (A4) are independent and run in parallel. Each touches only the files named
       in its heading (main and test). No group runs git commands; the orchestrator commits each group by path.
     - Group 6 (integration) runs after all of them: golden check with the old seed, then item 13 (RunContext seed),
       then the deliberate regeneration, the smoke, and verification.
     - Critical path: 1 -> 3 -> 6. Paths below are relative to src/main/java/com/android/commands/monkey/ (main) and
       src/test/java/com/android/commands/monkey/ (test). -->

## 1. Wave 0 — shared skeleton (ape/runtime/, fixtures, backup/)

- [ ] 1.1 `ape/runtime/Feature.java`: add the ADDONS member `APE_FIXES("ape.apeFixes", Rule.TRUE, "false")` with a one-line javadoc stating what it gates (items 3, 4, 6, 9, 10, 11, 12 per design D1), add it to the ADDONS `dependsOn` loop, and update the class javadoc where it lists ADDONS members or describes what BASE carries
- [ ] 1.2 `ape/runtime/KeyOwnership.java`: `feature(Feature.APE_FIXES, "ape.apeFixes", ValueType.BOOLEAN, "true")`; change the default of `ape.takeScreenshotForNewState` to `"true"` (design D12)
- [ ] 1.3 `ape/runtime/Presets.java`: `block.put("ape.apeFixes", "true")` in `addonsBlock()`
- [ ] 1.4 Tests in `ape/runtime/` (`CellPresetTest`, `PresetsTest`, `KeyOwnershipTotalityTest`, `FeatureDerivationTest`, `RunSpecResolveTest` as needed): the four ADDONS cells carry `APE_FIXES`, `base` and `mop` do not; `ape.apeFixes=true` without `ape.addons` aborts with `missing_dependency`; `ape.apeFixes=false` without ADDONS is inert; `ape.takeScreenshotForNewState` default is `true`
- [ ] 1.5 Regenerate `src/test/resources/cells/*.run_start.json` with `mvn test -Dtest=CellRunStartFixtureTest -Dape.oracle.regenerate=true`; the four ADDONS fixtures gain `APE_FIXES` in `features` and `ape.apeFixes` in `params`; all six `digest` values change, because the digest covers the base defaults and `ape.takeScreenshotForNewState` changed; no other member moves
- [ ] 1.6 Create the local `backup/` folder (gitignored, `.gitignore:13`) with a short `README.md`; nothing is committed there (design D13)
- [ ] 1.7 `mvn clean test`: all tests pass; if the new-state default makes a JVM test reach `MonkeySourceApe.takeScreenshot`, fix the test's plan, not the default
- [ ] 1.8 Orchestrator commits Group 1 by path; `ape/runtime/` is closed from here until 6.3

## 2. A1 — model and naming (items 3, 4, 6): `ape/naming/NamingFactory.java`, `ape/model/Graph.java`, `ape/naming/Naming.java`

- [ ] 2.1 `NamingFactory`: at both `maxGUITreesPerState` guards the tested count is `has(APE_FIXES) ? state.getGUITrees().size() : an.getStates().size()`, with the activity node obtained as APE obtains it at that site; log line unchanged (design D2)
- [ ] 2.2 `Graph.markVisited(State, int)`: count the activity visit unless `rebuilding && has(APE_FIXES)` (design D3)
- [ ] 2.3 `Naming.containsNamelet`: `>= 0` with the member, `!= -1` without it (design D4)
- [ ] 2.4 Tests: `ape/naming/NamingSelectTest` (absent namelet with insertion point > 0: absent with the member, present without it), `ape/model/RebuildCountTest` (activity visit count across a rebuild, on/off), a `NamingFactory` guard test (state with 21 trees and an activity with ≤ 20 states: refinement suppressed on, allowed off) — plans built with `TestRunSpecs` with and without ADDONS
- [ ] 2.5 Run `/sdd-test-run` on the touched test classes

## 3. A2 — action delivery (item 9, 10, 11, 12 toss): `ape/agent/ApeAgent.java`, `ape/agent/StatefulAgent.java`, `ape/agent/SataAgent.java`, `ape/agent/pipeline/DecisionPipeline.java`, `MonkeySourceApe.java`, and `ape/telemetry/NdjsonSink.java` only if 3.5 needs it

- [ ] 3.1 `DecisionPipeline.Candidate.RESTART`: gate `Feature.APE_FIXES`, condition `checkRestart() && has(APE_FIXES)`; update the enum and class javadoc that call RESTART "the one candidate gated by a BASE key" (design D5)
- [ ] 3.2 `ApeAgent`: delete `restartIsAGuard()`; add `protected boolean restartStageMounted()` (`false`); rename today's `restartAction()` body to `public Action startRestart()`; rewrite `checkRestart(origin)` per design D6 (no-op when the stage is mounted or `ape.checkRestart` is off; else `restartDue()` and `startRestart()`); `updateStateWrapper` keeps one path `checkInput(checkFuzzing(updateStateInternal(...)))` and clears the latch at step start iff the stage is not mounted; update javadocs to current state
- [ ] 3.3 `SataAgent`: override `restartStageMounted()` with whether its assembled pipeline contains `RESTART`; `StageCollaborators.restartAction()` stays and is implemented in `StatefulAgent` as `actionBuffer.clear(); return startRestart();`
- [ ] 3.4 `StatefulAgent.updateStateInternal`: after `markVisited(action)`, `action = checkRestart(action)`; record history and `moveForward()` only for the kept selected model action, else `resetTrace()` (design D6); update the `recordMopInteraction` javadoc that says no selected action is replaced
- [ ] 3.5 `StatefulAgent`: on a replacement write `sink().decisionNonModel(start, RESTART owner, RESTART channel)` and clear `lastDecisionAction`; if `NdjsonSink` keeps fields of the first `decision` call, make the second call replace it (design D6)
- [ ] 3.6 `MonkeySourceApe.generateClickEventAt(Rect, long, ClickPoint)` (node path): null visible bounds → display bounds with `"Error to fetch bounds."` when the member is off; the explicit-bounds overload keeps dropping null bounds in every cell (`MODEL_LLM_TAP`) (design D7)
- [ ] 3.7 `MonkeySourceApe.checkAppActivity`: bound `has(APE_FIXES) && ++waitForActivityCycles > 100` (design D8)
- [ ] 3.8 `MonkeySourceApe`: the fuzz toss expression of design D9
- [ ] 3.9 Tests: `ape/agent/RestartGuardTest` and `ape/agent/pipeline/RestartStageTest`/`DecisionPipelineFromSpecTest` (Restart assembled in ADDONS plans, absent in base/mop plans); new `ape/agent/PostSelectionRestartTest`: with the stage unmounted, a due periodic restart replaces a SATA pick, the pick is marked visited, the planned edge is weakened, no ledger/retirement/coverage/history entry is recorded, the step record carries `src`/`ch` `restart`, `dec.restart` and one `restarts`; `disableRestart()` during selection vetoes the periodic restart of the same step
- [ ] 3.10 Run `/sdd-test-run` on the touched test classes

## 4. A3 — fuzzing (item 12 pinch): `ape/events/ApeFuzzer.java`, `ape/agent/StepRng.java`

- [ ] 4.1 `ApeFuzzer.generatePinchOrZoomEvent`: same draws in both cases; append the event only with the member (design D9)
- [ ] 4.2 `StepRng`: javadoc of `fuzzToss` states the stream serves the toss only with `APE_FIXES`; no code change unless a test needs it
- [ ] 4.3 Tests: an `ape/events` fuzzer test (pinch branch appends one event on, none off, identical `RandomHelper` position after the call in both); `ape/agent/StepRngTest` unchanged or adjusted; `ape/oracle/RngNeutralityTest` cases that assert fuzz-stream isolation run on an ADDONS plan
- [ ] 4.4 Run `/sdd-test-run` on the touched test classes

## 5. A4 — WebView prune (item 8): `ape/tree/GUITreeNode.java`, `ape/tree/GUITreeWebViewPruneTest.java`

- [ ] 5.1 `GUITreeNode.clearChildren`: `while (domNode.getFirstChild() != null) domNode.removeChild(domNode.getFirstChild());`; delete the `try`/`catch (DOMException)`, the abort log and imports that become unused; javadoc to current state (design D10)
- [ ] 5.2 `GUITreeWebViewPruneTest`: the DOM element has zero children after `clearChildren` on the JVM DOM, with no fake DOM; delete any fake-DOM helper that becomes unused
- [ ] 5.3 Run `/sdd-test-run` on the touched test class

## 6. Integration and verification

- [ ] 6.1 `mvn clean test` with Groups 1–5 and the old `RandomHelper` seed: the oracle goldens of `as`, `as_mop`, `as_llm`, `as_mop_llm` SHALL be unchanged; any golden that moves in `base`/`mop` is listed with the item that moves it (3, 4, 6 or 9) for the record
- [ ] 6.2 Orchestrator commits Groups 2–5 by path
- [ ] 6.3 Item 13: `ape/runtime/RunContext.java` seeds `RandomHelper.seed(seed ^ RANDOM_HELPER)` (design D11), comment to current state; `RunContextTest` case: `RandomHelper` and `new Random(seed)` produce different first draws for the same seed
- [ ] 6.4 Regenerate the goldens deliberately (`-Dape.oracle.regenerate=true`, INV-ORA-04), `mvn clean test` green; commit 6.3–6.4 by path
- [ ] 6.5 Update `CLAUDE.md` (groups table: ADDONS members; *Behavior groups and cells*: what BASE carries; restart text; test count) and the `Feature.java`/`guidance-groups` cross-references that 1.1 did not cover
- [ ] 6.6 `mvn package`; smoke on the RVSec AVD per CLAUDE.md "Standalone testing": one short `base` run and one `as` run on cryptoapp; check `RUN_START` (features, `ape.apeFixes`), no `clearChildren DOM prune aborted` line, `PIPELINE` stages (no `Restart` in `base`), `dec.src=restart` records in `base` if a restart occurs, new-state PNGs on `/sdcard`
- [ ] 6.7 Run `/sdd-qa-lint-fix src/main/java/com/android/commands/monkey`
- [ ] 6.8 Run `/sdd-verify src/main/java/com/android/commands/monkey`
- [ ] 6.9 Invoke `/sdd-code-reviewer` via Skill tool
- [ ] 6.10 Run `openspec validate gh-tbd-base-equals-ape --strict`; report worktree, branch and SHAs to mini-experimento
- [ ] 6.11 At archive, sync by hand the main-spec text that archive does not carry (memory `openspec-archive-drops-delta-purpose`): guidance-groups Purpose (what `base` carries; restart guard and fuzz stream are `APE_FIXES`); run-spec Data Contracts (the `RandomHelper` seeding lines) and INV-RUN-08; naming INV-NAME-13, INV-NAME-14 and the `ape.maxGUITreesPerState` Data Contracts line; model INV-MODEL-11; ui-tree INV-TREE-10, INV-TREE-12 and the Purpose sentence on DOMException safety; exploration and decision-pipeline invariants named in their deltas' bodies
