# Tasks: rearch-01-parity-oracle

Test infrastructure only: every task writes under `src/test/java`, `src/test/resources`, or
this change's artifacts. **No task touches `src/main/java/`** (INV-ORA-01) — a stage-1 review
gate, not a hope. Group order is dependency order; `mvn test` is the checkpoint after every
group.

## 1. Investigation spike — drive the ladder once on the JVM

- [ ] 1.1 Spike test (temporary, refined into group 2): Unsafe-allocate a `SataAgent` via the
      `PipelineParityTest.java:56-116` scaffolding, inject the fields
      `selectNewActionNonnull()` reads (`newState`, `newGUITree`, `timestamp`, `model`,
      `_coverageTracker`, `_budgetTracker`, `_mopData=null`, `_llmRouter=null`,
      `scoringContext`, `scoringPipeline`, `epsilon`, `actionCounters`, graph wiring), call it
      once on a synthetic 3-action state, and assert a non-null action returns
- [ ] 1.2 Enumerate the definitive injected-field + post-step bookkeeping list (design D7):
      which `markVisited`/timestamp/`graphStableCounter`/`_isNewState` updates the driver must
      replay for multi-step runs; record the list in the spike's javadoc for transfer to
      `OracleScaffold`
- [ ] 1.3 Map the JVM-safe SATA-chain rungs (design D6): confirm which of the seven rungs
      (`selectNewActionFromBuffer` … `handleNullAction`) execute without
      `NoClassDefFoundError` on synthetic states, and which branches (trivial-activity /
      restart paths reaching `AndroidDevice.getFocusedStack`, `SataAgent.java:1270,1510`)
      scenarios must steer around; record in the spike javadoc
- [ ] 1.4 Verify the second RNG stream seam: a `SataAgent` test subclass overriding
      `getRandom()` (`ApeAgent.java:322`) reaches the epsilon-greedy draw
      (`SataAgent.java:1330`) and the component-trigger draw (`:548`) without a
      `MonkeySourceApe`
- [ ] 1.5 Checkpoint: `mvn test` green with the spike in place

## 2. Oracle scaffold and synthetic fixtures

- [ ] 2.1 Create `src/test/java/com/android/commands/monkey/ape/oracle/OracleScaffold.java`:
      generalized `allocate()`/`setField()` (hierarchy-walking), synthetic
      `StateKey`/`State`/`GUITree` builders with `TestName`-targeted `ModelAction`s, and the
      per-preset injection profiles (design D2 table); javadoc carries the frozen
      injected-field + bookkeeping ledger from tasks 1.2/1.3
- [ ] 2.2 Create `OracleSataAgent extends SataAgent` overriding `getRandom()` with a
      per-run seeded `Random`; no other override
- [ ] 2.3 Create `ScenarioScript`: ordered screens, transition table, per-step scripted
      `_isNewState`/`graphStableCounter`, per-step LLM entries (routing verdicts + result
      verdict), and scenario metadata (name, seed)
- [ ] 2.4 Unit tests for the scaffold: synthetic state builds with N targeted actions; both
      RNG streams seeded from one declared seed (`RandomHelper.seed` + agent override);
      injection profile for each of the four presets wires the expected field combination
- [ ] 2.5 Checkpoint: `mvn test` green

## 3. Scripted LLM router

- [ ] 3.1 Create `ScriptedLlmRouter extends LlmRouter` (constructor is JVM-safe,
      `LlmRouter.java:123-161`): overrides `shouldRouteNewState` (honors `isNewState`),
      `shouldRouteStagnation` (honors `firedThisEpisode`), `shouldRouteRandom`, and
      `selectAction` (accept-by-named-selector / decline / timeout); never HTTP, never
      screenshot, never breaker transitions (INV-ORA-03)
- [ ] 3.2 Script bookkeeping: consumed-entry tracking; exhaustion and unconsumed mandatory
      entries throw with the entry named (spec "Unconsumed script entry fails loudly")
- [ ] 3.3 `ScriptedLlmRouterTest`: accept returns the deterministic member of the offered
      list; decline/timeout both return null and are distinguishable by provenance;
      `firedThisEpisode=true` suppresses a scripted stagnation route; no network syscall
      (constructor + all overrides run with `Config.llmUrl` unset)
- [ ] 3.4 Checkpoint: `mvn test` green

## 4. Golden format: DecisionRecord, GoldenFile, comparator

- [ ] 4.1 Create `DecisionRecord` (step, actionType, target, decisionSource, pickChannel,
      llm, componentTrigger; absent-not-null field semantics) and NDJSON
      serialization via org.json — one physical line per record; header record with
      preset/scenario/seed/fixture/capturedAt
- [ ] 4.2 Create `GoldenFile`: reader, writer (capture mode gated on
      `-Dape.oracle.regenerate=true`), and `compare()` with first-divergence report
      (preset, scenario, step, field, golden, actual + divergent-record count) (INV-ORA-06)
- [ ] 4.3 Missing-golden behavior: compare mode fails with regeneration instructions, never
      auto-captures (spec "Missing golden never auto-captures")
- [ ] 4.4 `GoldenFileTest`: round-trip fidelity; one-line-per-record; comparator catches a
      changed field, a missing record, and an extra record at the right step; default mode
      writes nothing under `src/test/resources/goldens/` (INV-ORA-04)
- [ ] 4.5 Checkpoint: `mvn test` green

## 5. OracleDriver and harness-integrity tests

- [ ] 5.1 Create `OracleDriver.run(agent, script)`: per step — inject scripted state, invoke
      `selectNewActionNonnull()`, build the `DecisionRecord` (target via `Name.toXPath()`;
      launcher steps as `EVENT_TRIGGER_ACTIVITY` + candidate class; component side-effect
      flag), apply the bookkeeping ledger, advance
- [ ] 5.2 `OracleDriverTest`: double-capture identity (same seed twice ⇒ identical record
      lists, INV-ORA-02); `BadStateException` fails the run; a scenario reaching a device-only
      branch surfaces `NoClassDefFoundError` (assert the failure is loud, not swallowed)
- [ ] 5.3 Checkpoint: `mvn test` green

## 6. Per-preset baseline scenarios and goldens

- [ ] 6.1 Write the `aperv` baseline scenario (SATA chain fall-through: buffer, early-stage,
      epsilon-greedy rungs; budget both outcomes) in `ParityOracleApervTest`, with the
      preset's Config guard assertions (design D2); capture and commit
      `goldens/aperv/baseline.ndjson`
- [ ] 6.2 Write the `mop` baseline scenario (MOP-boosted picks, launcher cadence fire,
      component-trigger side-effect) in `ParityOracleMopTest` against
      `cryptoapp.apk.gh60-fresh.json` via the production `MopData.load` path; capture and
      commit `goldens/mop/baseline.ndjson`
- [ ] 6.3 Write the `llm` baseline scenario (all three hooks; accept, decline, and timeout
      verdicts; `not_routed` steps) in `ParityOracleLlmTest`; capture and commit
      `goldens/llm/baseline.ndjson`
- [ ] 6.4 Write the `llm_mop` baseline scenario (LLM verdicts interleaved with MOP launcher
      and MOP-boosted SATA picks) in `ParityOracleLlmMopTest`; capture and commit
      `goldens/llm_mop/baseline.ndjson`
- [ ] 6.5 Re-run each preset test in compare mode against its committed golden — all green
      (spec "aperv preset golden compares green at HEAD" and siblings)
- [ ] 6.6 Checkpoint: `mvn test` green (full suite, goldens included)

## 7. Preemption golden

- [ ] 7.1 Write the simultaneous-qualification scenario (`llm_mop` profile): budget both
      outcomes; LLM accept preempting a due launcher; LLM decline falling through to the
      launcher; component trigger as side-effect; SATA fallback — in `PreemptionGoldenTest`;
      capture and commit `goldens/llm_mop/preemption.ndjson`
- [ ] 7.2 Direct field assertions alongside the golden: finding 3.3-1 —
      `_stepsSinceLauncherFiring` unchanged across an LLM-accepted step and cadence resuming
      from the pre-preemption value (INV-ORA-05); stagnation single-shot burn on decline
      (`stagnationHookFired` true, `graphStableCounter` unchanged); counter reset on accept
      only; hook consultation order new-state → stagnation → random
- [ ] 7.3 Checkpoint: `mvn test` green

## 8. Regeneration procedure and docs

- [ ] 8.1 Write `src/test/resources/goldens/README.md`: when regeneration is legitimate
      (a decided behavior change — never a red comparison one wants green), the exact command,
      what to review in the diff, commit-message requirement (state the decision), the CI
      prohibition (INV-ORA-04), and the stage-2/3 freeze (INV-ORA-07) with the
      forked-surefire escape hatch for future non-default-Config presets (design D2)
- [ ] 8.2 Run `/sdd-doc-code` on the new oracle classes (`OracleScaffold`, `OracleDriver`,
      `ScriptedLlmRouter`, `GoldenFile`) — javadoc must carry the honesty ledger (D7) and the
      capture boundary (D6)

## 9. Verification

- [ ] 9.1 `git diff --stat src/main/java` is empty for this change (INV-ORA-01); no test
      class shadows a production class (only `android.*` source stubs, if any were added)
- [ ] 9.2 Full `mvn test` green twice in a row (flake check for the determinism claim); the
      13 pre-existing `@Ignore` skips unchanged
- [ ] 9.3 Grep the oracle package for forbidden dependencies: no `HttpURLConnection`, no
      `ScreenshotCapture.capture`, no `System.currentTimeMillis` in any capture/compare path
      (INV-ORA-03)
- [ ] 9.4 `openspec status --change rearch-01-parity-oracle` shows 4/4;
      `openspec validate rearch-01-parity-oracle` clean; artifacts coherent with the
      implemented state
- [ ] 9.5 Run `/sdd-code-reviewer` via Skill tool on the oracle package
