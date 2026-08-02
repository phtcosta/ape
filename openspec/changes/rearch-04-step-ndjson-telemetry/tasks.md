# Tasks: rearch-04-step-ndjson-telemetry

<!-- Subagent dispatch hints:
     - Group 1 (Serializer) must complete first — everything else builds on JsonBuf.
     - Group 2 (Sink core) depends on 1. Groups 3–6 depend on 2 and are largely sequential
       within the jar (they touch StatefulAgent/LlmRouter together).
     - Group 8 (Python converter) is independent of Groups 3–7 once the schema (Group 2)
       is frozen — it can run in parallel from that point.
     - Group 7 (legacy deletion) must run after Groups 3–5 (the new producers replace the
       old emitters in the same commits or later, never before).
     - Critical path: 1 → 2 → 3 → 4 → 7 → 9.
     - Gates (roadmap): neutrality test (Sec. 9.8), calibration-report regeneration
       (Sec. 9.11), round-trip/one-line tests (Sec. 9.12). -->

## 1. JSON Serializer (escaping by construction)

- [ ] 1.1 Implement `ape.telemetry.JsonBuf` (~80 LOC, zero deps): reused `StringBuilder`, `beginObject/endObject/beginArray/endArray/name/value` API, escaping per INV-SNK-02 (`"`/`\`, U+0000–U+001F incl. NUL, U+2028/U+2029; non-ASCII passthrough), `toLine()` never containing a raw newline
- [ ] 1.2 Add permanent round-trip tests (Sec. 9.12): newline, quotes, backslash, NUL, spaces, non-ASCII values serialize→parse (`org.json`, test classpath only) byte-identically
- [ ] 1.3 Add the permanent one-line-per-record test: adversarial inputs never produce a raw `\n` in the output (INV-SNK-01)
- [ ] 1.4 Run `/sdd-doc-code src/main/java/com/android/commands/monkey/ape/telemetry/JsonBuf.java`
- [ ] 1.5 Run `/sdd-test-run ape.telemetry`

## 2. EventSink Core (StepRecord, lifecycle, dictionaries)

- [ ] 2.1 Define the `EventSink` interface (all methods `void`, primitives/strings in, nothing out — INV-SNK-07 by shape) and `NoopSink`
- [ ] 2.2 Implement `NdjsonSink`: single pending `StepRecord` accumulator (reused object), `beginStep` (closes an unresolved predecessor without `out`), `decision`/`decisionNonModel`, `llm[]` append, `outcome` (close + one `System.out` write, never through `Logger` — INV-SNK-11), `flushPendingStep` (`out:{"resolved":false}`)
- [ ] 2.3 Implement the run-local ID tables: `ACT` (`{"type":"ACT","id","name","mop"}`) and `STATE` (`{"type":"STATE","id","key","act"}`) records emitted on first sight, definition-before-reference (INV-SNK-06)
- [ ] 2.4 Implement volume rules: defaults omitted; tri-state exemptions for `dec.patched` and `dec.cf` (INV-SNK-05); envelope once (INV-SNK-04)
- [ ] 2.5 Implement the failure latch: no sink method propagates a `Throwable`; first failure disables emission for the run with one warning (INV-SNK-12)
- [ ] 2.6 Unit tests: lifecycle (outcome joins its step at N+1; legitimate no-`out` closure; flush encoding), dictionary ordering, defaults omission, tri-state `patched`, record-per-step count (INV-SNK-03), every record line starts with `{`
- [ ] 2.7 Run `/sdd-doc-code src/main/java/com/android/commands/monkey/ape/telemetry/NdjsonSink.java`
- [ ] 2.8 Run `/sdd-test-run ape.telemetry`

## 3. Wire the Producers (decision, LLM, outcome)

- [ ] 3.1 `StatefulAgent.resolveNewAction`: replace both `[APE-STEP]` emitters with `sink.beginStep(...)` + `sink.decision(...)`/`decisionNonModel(...)`, mapping every legacy field per the design table (boosts, `pick_channel`, `patched` tri-state via `patchedField` logic, `cf` via `counterfactualFields` logic); keep the join-buffer writes exactly as they are
- [ ] 3.2 `StatefulAgent.updateGraph`: replace the `[APE-OUTCOME]` emitter with `sink.outcome(...)` under the identical non-null-transition + reference-equality + single-shot guards; keep the dead-pair feedback call unchanged; keep the refinement remap of the buffered action
- [ ] 3.3 `LlmRouter` (or the rearch-03 LLM stages): replace `[APE-LLM-TEL]` with `sink.llmCall(...)` (all 15+ fields per the mapping table; `variant`/`activity`/`step` dropped as run-constant/derivable), `[APE-LLM-ERROR]` with `sink.llmError(cause, detail)`, the breaker-episode line with `sink.llmBreakerOpen(trips)`; prompt/response dumps become `sys`/`user`/`resp` fields behind the plan flag (default on)
- [ ] 3.4 `MopData.load`: replace the `[APE-MOP-DATA]` lines with `sink.mopData(...)`; `ScoringPipeline`: replace `[APE-ARCH] passes=[...]` with `sink.pipeline(...)`; `LlmRouter`: replace `[APE-LLM-CONFIG-ACK]` with `sink.llmAck(serverModel)`
- [ ] 3.5 Delete the `[APE-LLM-CONFIG]` manifest emitter (subsumed by `RUN_START`); verify with the rearch-02 artifacts that the effective-plan echo carries every retired manifest field (model, temperature, top_p, top_k, max_tokens, timeout_ms, prompt_variant, llm_percentage, on_new_state, on_stagnation, stagnation_threshold, url) and `t0`
- [ ] 3.6 Unit tests: field parity of `llmCall` vs the mapping table; error/breaker sub-events land on the current step's record; a selection retry appends to the same `llm[]`
- [ ] 3.7 Run `/sdd-test-run ape`

## 4. RUN_END, flushPendingStep, teardown wiring

- [ ] 4.1 Replace `LlmRouter.printSummary()` with a counters accessor (`RunCounters` value object: 17 LLM counters + sink record/dictionary counts)
- [ ] 4.2 `StatefulAgent.tearDown`: add `safeStep("flushPendingStep", ...)` as the **first** step and `safeStep("runEnd", ...)` as the **last** step; plumb the termination reason (`timeout` on `StopTestingException` path, `crash` otherwise, `unknown` fallback) into `runEnd`
- [ ] 4.3 Preserve teardown isolation: INV-EXPL-16/29 untouched — every new step inside `safeStep`; a failing step skips nothing after it
- [ ] 4.4 Unit tests: teardown order (`flushPendingStep` first, `runEnd` last — Sec. 9.7 jar-side test); `RUN_END` carries reason + counters; a failing naming dump still yields `RUN_END`; non-LLM plan omits the LLM counter block
- [ ] 4.5 Run `/sdd-test-run ape.agent`

## 5. Logcat Heartbeat (D4)

- [ ] 5.1 Add `TelemetryParams.heartbeat` (`ape.telemetryHeartbeat`, default `true`) to the plan (coordinate with rearch-02's `RunSpec`)
- [ ] 5.2 Emit `Log.i` heartbeat `s=<N> t=<tRelMs>` at the `beginStep` site when the flag is on; write-only (INV-SNK-10); guard swallowed by the sink latch
- [ ] 5.3 Unit test the guard logic (Android `Log` itself stays device-covered); test that heartbeat off produces byte-identical trace records under the same seed

## 6. Neutrality (R7 — the INV-ARCH-01 substitute)

- [ ] 6.1 Wire sink selection into the plan (`NdjsonSink` always in presets; `NoopSink` reachable only by the test configuration)
- [ ] 6.2 Implement the permanent neutrality test on the rearch-01 parity harness: same seed, `NdjsonSink` vs `NoopSink` ⇒ identical action sequence (Sec. 9.8, INV-SNK-07)
- [ ] 6.3 Run `/sdd-verify ape` (checkpoint: `mvn test` green before the deletion group)

## 7. Legacy Output Deletion (report Sec. 6.6)

- [ ] 7.1 Delete `StatefulAgent.saveGraph()`'s dot/vis/per-state branches (`sataGraph.dot`, `sataGraph.vis.js`, `step-*.txt`) and, after a caller audit, the now-dead `Graph.printDot`/`Graph.printVis`/`State.saveState` and `ModelAction` vis-only JSON helpers
- [ ] 7.2 Delete `saveActionHistory()` and `Model.saveActionHistory` (`action-history.log`)
- [ ] 7.3 Delete the `produce.log`/`consume.log` writers in `MonkeySourceApe` (constructor `PrintWriter`s, the five `ApeRRFormatter.log*` call sites, the two teardown close steps) and the `sataTimeline.vis.js` export (`toVisTimeline`); **keep** `ApeRRFormatter.readActions`/`parseRect` for `ReplayAgent`
- [ ] 7.4 Delete Config flags `saveDotGraph`, `saveVisGraph`, `saveStates`, `stepTelemetryEnabled` (and their `ape.properties` keys — unknown after this change, so plan validation rejects them) — coordinate with rearch-02's flag roster
- [ ] 7.5 Grep-verify zero remaining emitters of `[APE-STEP]`, `[APE-OUTCOME]`, `[APE-LLM-TEL]`, `[APE-LLM-ERROR]`, `[APE-LLM-PROMPT]`, `[APE-LLM-RESPONSE]`, `[APE-LLM-CONFIG]`, `[APE-LLM-CONFIG-ACK]`, `[APE-MOP-DATA]`, and `[APE-ARCH] passes` in `src/main`; update stale comments referencing them (P4)
- [ ] 7.6 Run `/sdd-qa-lint-fix src/main/java` (bulk-edit cleanup)
- [ ] 7.7 Run `/sdd-test-run ape` and `mvn package` (jar builds green with deletions)

## 8. Python: Converter + gzip at Collection (rv-android, D5-minimal)

- [ ] 8.1 Implement `trace_ndjson.py` in `aperv_tool/tools/aperv/`: NDJSON→legacy converter (dictionary expansion, default re-emission, `activity_has_mop` re-derivation, `clock` re-expansion via `t0`, `llm[]` → per-call `[APE-LLM-TEL]`/`[APE-LLM-ERROR]` lines keyed by `s`, `RUN_END.counters` → `LLM Summary`/`Decision ratio` lines, `RUN_START` → `[APE-LLM-CONFIG]` line) — mechanical, write-only, marked temporary
- [ ] 8.2 Wire `tool.py` post-run collection: gzip raw capture to `<trace>.ndjson.gz`, then convert into `task.result.trace_file`; both non-fatal (WARNING + raw trace left in place); **run on the timeout path too** (before re-raising `RVToolTimeoutError`); `_check_empty_trace` unchanged; zero validation/status logic (D5)
- [ ] 8.3 pytest: golden NDJSON fixture → expected legacy lines (field-for-field vs the design mapping table); converter failure is non-fatal; gzip round-trips; explicitly assert no code path reads `RUN_END` for control flow
- [ ] 8.4 Run `/sdd-test-run aperv_tool`

## 9. Acceptance and Verification

- [ ] 9.1 Produce a sample new-format trace (smoke run via rv-platform — never manual emulator management) covering MOP boosts, LLM calls (incl. an error and a `no_match reason=dead_pair`), a flushed final step, and `RUN_END`
- [ ] 9.2 Acceptance Sec. 9.11: regenerate the 2026-07-24 calibration report tables from the sample trace (via the converter + existing parsers, and via a native NDJSON parse) — every quantity the report consumed must be recoverable; file any gap as a schema fix, not an analysis workaround
- [ ] 9.3 Confirm gates: neutrality test green (Sec. 9.8), round-trip/one-line tests green (Sec. 9.12), `RUN_END`-last test green (Sec. 9.7)
- [ ] 9.4 `mvn test` (full suite) and `mvn package`; `mvn install -Drvsec_home=<path>` to refresh the aperv-tool jar
- [ ] 9.5 Update `CLAUDE.md` (telemetry section: NDJSON sink, removed flags/outputs, heartbeat key) — current-state wording only (P4)
- [ ] 9.6 Run `/sdd-qa-lint-fix src/main/java`
- [ ] 9.7 Run `/sdd-verify ape`
- [ ] 9.8 Run `/sdd-code-reviewer`
