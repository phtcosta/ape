# Design: rearch-04-step-ndjson-telemetry

## Context

Stage 4 of 7 of the "Disposable Run Kernel" re-architecture (`docs/analise_fable-selecao.md` rev. 3, Sec. 6.5/6.6; owner decisions D2/D4/D5 — final, do not reopen). Depends on `rearch-02-runspec` (which introduces `RUN_START` and the `RunSpec`/`TelemetryParams` plan) and `rearch-03-decision-pipeline` (whose stages supply `decision_source` and own the LLM call sites).

Current state, verified at HEAD `5dcf225` (file:line):

1. **Step telemetry is unescaped `key=value` on stdout.** The `[APE-STEP]` emitters live in `StatefulAgent.resolveNewAction()` — the 15-field model-action form at `StatefulAgent.java:1491-1507` (`step clock activity state action decision_source priority mop mop_frontier wtg coverage menu form activity_has_mop pick_channel` plus the conditional `patched=` from `patchedField(..)` at `:1159-1164` and `cf_action=`/`cf_changed=` from `counterfactualFields(..)` at `:1178-1193`) and the 8-field non-model form at `:1520-1528`. `[APE-OUTCOME]` (`step decision_source new_state target_state activity_changed activity_has_mop`) is emitted in `updateGraph()` at `:1026-1033`. All three are gated by `Config.stepTelemetryEnabled` (`Config.java:276`, default `true`, member of the `apePureMode` forced-off registry at `:346` — the mechanism of INV-ARCH-01, the baseline arm's telemetry blindness).
2. **The join buffer already exists.** `lastDecisionStep`/`lastDecisionAction` (`StatefulAgent.java:117-124`) buffer the step-N decision because its outcome only resolves during step N+1's `updateGraph()`. Today it re-emits the join key so the analyst can re-join two lines by `step=`; consumers: the `[APE-OUTCOME]` line and the dead-pair ban feedback (`:1024-1045`, single-shot, reference-equality guarded).
3. **LLM telemetry is a third line family.** `[APE-LLM-TEL]` at `LlmRouter.java:567-597` (19 fields: `step variant call mode action qwen pixel result [reason] [repair] matched_class nearest_class nearest_dist widgets activity tokens_in tokens_out time_ms [text]`); `[APE-LLM-ERROR]` at `:392`, `:431`, `:459+`, `:604`; `[APE-LLM-PROMPT]`/`[APE-LLM-RESPONSE]` raw dumps at `:402-412`/`:446-448` (unconditional); `[APE-LLM-CONFIG]` manifest at `:148-160`; `[APE-LLM-CONFIG-ACK]` at `:442`; the aggregate `[APE-RV] LLM Summary` / `Decision ratio` in `printSummary()` at `:907-936` (17 counters). `[APE-MOP-DATA]` load status at `MopData.java:203-332`; `[APE-ARCH] passes=[...]` at `ScoringPipeline.java:36`.
4. **`Logger` is a `System.out` prefixer** (`Logger.java`, 67 LOC): every line it writes starts with `[APE] `. The `.trace` is the host-side `adb` stdout captured by `aperv-tool` (`tool.py:1553-1558`, `open(trace_file, "wb")` around `main_cmd.invoke`). There is no separate pull step — collection *is* the stdout capture; on timeout the harness SIGKILLs `adb` and the file closes (verified in telemetry-proof-llm-efficacy D9).
5. **Legacy file outputs and their writer sites**: `sataGraph.dot` / `sataGraph.vis.js` / per-state `step-<ts>-<id>.txt` in `StatefulAgent.saveGraph()` (`:1855-1902`, gated by `Config.saveDotGraph`/`saveVisGraph`/`saveStates`, `Config.java:63/65/69`, defaults `false/true/true`; the `sataModel.obj` branch at `:1863-1870` is removed by rearch-02); `action-history.log` in `saveActionHistory()` (`:1850-1853` → `Model.saveActionHistory`, `Model.java:97`); `produce.log`/`consume.log` opened in the `MonkeySourceApe` constructor (`:270-273`) and written via `ApeRRFormatter.logProduce/logConsume/startLogAction/endLogAction/logDrop` (`MonkeySourceApe.java:704,710,1214,1218,1437`); `sataTimeline.vis.js` generated from `produce.log` in `MonkeySourceApe.tearDown` (`:245-248`, `ApeRRFormatter.toVisTimeline`).
6. **Teardown** (must stay isolated — INV-EXPL-16/29): `Monkey.run` finally at `Monkey.java:774-799` (individually guarded rotation restore + `MonkeySourceApe.tearDown()`); `MonkeySourceApe.tearDown` 6 safeSteps at `:235-249`; `StatefulAgent.tearDown` 9 safeSteps at `:1802-1814` with `coverageDump` immediately before `saveGraph` (INV-COV-10).
7. **`ReplayAgent` reads the produce-log line format**: `ReplayAgent.java:63` calls `ApeRRFormatter.readActions(logFile)` on the user-supplied `ape.replayLog`. Deleting the *writers* must not delete the reader.
8. **Spec reality check**: INV-ARCH-01 does **not** live in `exploration/spec.md`; it lives in `openspec/specs/scoring-pipeline/spec.md:110` (inside "apePureMode Kill-Switch and Parity"), referenced by INV-ARCH-08 (`:240`) and a scenario (`:253`). The `[APE-STEP]` requirement lives in `action-selection/spec.md` ("Per-action decision-source telemetry", INV-SEL-04); `[APE-OUTCOME]` in `scoring-pipeline/spec.md` ("Per-Step Decision Outcome Attribution", INV-ARCH-08/09). The delta set therefore includes `scoring-pipeline` and `action-selection` in addition to the proposal's `exploration`/`llm-routing`/`aperv-tool`. It also includes three requirements that live outside the telemetry capabilities but normatize the retired renderings by name, and would otherwise sync into the main specs demanding formats nothing emits: `model :: Tolerant Action-History Persistence` (whose subject, `Model.saveActionHistory`, task 7.2 deletes), `wtg-navigation :: WTG Frontier Boost for Unvisited Activities` (justified by the `[APE-STEP] ... wtg=` field), and `llm-prompt :: Widget List Generation` (justified in part by the `[APE-LLM-PROMPT]` dump). `llm-routing :: Deterministic Dead-Pair Ban` is the fourth, and rides this change's existing `llm-routing` delta.

Constraints: R4 (no IPC/async/persistence/frameworks), R7 (telemetry never decides — sink on/off, same seed ⇒ same decisions), R9 (frozen metrics come from logcat/instrumented APK, untouched), D5 (no exit contract: `RUN_END` is only the natural last record; zero Python validation or task-status logic), P1/P3 (hand-written serializer, no dependency; legacy paths deleted, not shimmed).

## Goals / Non-Goals

**Goals:**

- One NDJSON `StepRecord` per step replacing the `[APE-STEP]`/`[APE-OUTCOME]`/`[APE-LLM-TEL]` join — every quantity measured today remains recordable (mapping table below; acceptance = the 2026-07-24 calibration report is regenerable, report Sec. 9.11).
- Escaping by construction: a hand-written JSON serializer (~80 LOC, zero deps) with permanent round-trip and one-line-per-record tests (Sec. 9.12).
- Volume: envelope 1×/step, defaults omitted, `ACT`/`STATE` dictionary events with run-local integer IDs, `run_id` only at the borders; gzip at collection (Python side).
- Universal, provably neutral telemetry for all arms — INV-ARCH-01 deliberately dissolved; substitute = neutrality test (R7, Sec. 9.8).
- `RUN_END` (reason + counters) as the natural last record; `flushPendingStep` in teardown; max loss 1 step on SIGKILL.
- Logcat heartbeat (D4): one `Log.i` line per step, plan flag, default on.
- Deletion of the seven legacy outputs and the `key=value` step family (report Sec. 6.6).
- Minimal Python work: gzip at collection, plus the native NDJSON reader that `clock_logcat_join.py` migrates onto. No format conversion, no validation (D5).

**Non-Goals:**

- No device-side file sink (stdout transport unchanged; a file sink remains a future flag decided by loss measurement).
- No Python-side validation, sentinel checking, task-status or retry changes of any kind (D5 — final).
- No runtime APE↔logcat coupling; the violation↔step join stays in the analysis pipeline.
- No change to the UI-coverage dump format (`UICOV`/`UICOV-ACT` lines) or to any frozen metric provenance (R9).
- No conversion of free-text diagnostic logging (`[APE] ...` lines) to NDJSON — out of scope; the discriminator rule (below) keeps the two streams separable.
- No removal of `ReplayAgent` or the `ApeRRFormatter` *reader* path.

## Architecture

```text
selection (step N)                updateGraph (step N+1)              teardown
──────────────────                ──────────────────────              ────────
DecisionPipeline stages           outcome resolves for step N         flushPendingStep
  │  llmCall/llmError ──►┐          │                                   (out:{"resolved":false})
  ▼                      │          ▼                                 ... other safeSteps ...
resolveNewAction         │        EventSink.outcome(...)              EventSink.runEnd(reason,
  │                      │          │ closes + serializes +             counters)  ── last record
  ▼                      │          │ writes ONE line to stdout       sink flush (one safeStep)
EventSink.beginStep ─────┤          ▼
EventSink.decision  ─────┘        pending record N freed;
  + Log.i heartbeat               record N+1 opens on next beginStep
  (flag, default on)

Out-of-step records (same NDJSON stream, own lines): RUN_START (rearch-02, first record),
ACT/STATE dictionary entries (on first sight), MOP_DATA, PIPELINE, LLM_ACK, RUN_END (last).
Stream discriminator: a trace line is a sink record iff it begins with '{'; every Logger
line begins with "[APE] " and can never collide. The sink writes to System.out directly,
never through Logger.
```

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `ape.runtime.Json.Buf` (**extends** the class `rearch-02` shipped, not a new one) | JSON serialization into a reused `StringBuilder`; full escaping; one-line guarantee | primitive values, strings | one NDJSON line (no raw newline ever) |
| `ape.telemetry.EventSink` (new interface) | Observation surface; all methods `void`, never throw into the loop | step/decision/LLM/outcome data as primitives+strings | NDJSON lines on `System.out` |
| `ape.telemetry.NdjsonSink` (new) | Record lifecycle (open→append→close at N+1), ID tables, run-level records, counters for `RUN_END` | sink calls | trace records |
| `ape.telemetry.NoopSink` (new) | Neutrality-test double; every method a no-op | sink calls | nothing |
| `StatefulAgent` | Repurposes the join buffer as the record-closure guard; calls `beginStep`/`decision`/`outcome`; teardown gains `flushPendingStep` and `runEnd`; legacy writers deleted | existing selection/outcome state | sink calls |
| `LlmRouter` (rearch-03: LLM stages) | Emits `llm[]` sub-events (call, error, breaker-episode) instead of `[APE-LLM-TEL]`/`[APE-LLM-ERROR]`; exposes counters to the sink; `printSummary` deleted | existing call pipeline | sink calls |
| `MonkeySourceApe` | Loses produce/consume loggers and timeline export; teardown chain shrinks | — | — |
| `tool.py` (rv-android) | Post-run: gzip the raw NDJSON alongside the trace. Non-fatal, write-only, no validation (D5); the `.trace` itself is never rewritten | `.trace` (NDJSON) | `<trace>.ndjson.gz` |
| `trace_ndjson.py` (new, rv-android) | Native NDJSON reader for the analysis side: streams records, resolves the `ACT`/`STATE` dictionaries, materializes omitted defaults, yields one joined row per step | `.trace` (NDJSON) | typed step rows |

## StepRecord schema and the old-field → new-schema mapping

This table is the heart of the change: **nothing measured today may become unrecordable.** "Derivable" means recoverable by a pure lookup in the same trace (dictionary entries), which the native reader (D-8) performs mechanically.

Example (one line; wrapped here for reading):

```json
{"s":42,"t":8123,"act":17,"st":231,
 "dec":{"a":"model=CLICK@[...]","src":"MOP_WIDGET","ch":"roulette_greedy","pri":9,"mop":500,"menu":250,
        "patched":0,"cf":{"changed":1,"a":"model=CLICK@[...]"}},
 "llm":[{"call":3,"mode":"new_state","tool":"click","qwen":[500,861],"px":[512,884],
         "result":"matched","mcls":"android.widget.Button","ncls":"android.widget.Button",
         "ndist":4.2,"widgets":23,"tok":[1841,25],"ms":973}],
 "out":{"new_state":true,"target":232}}
```

### Envelope (once per record)

| Legacy field | New location | Notes |
|---|---|---|
| `[APE-STEP] step=` / `[APE-OUTCOME] step=` / `[APE-LLM-TEL] step=` | `s` | one record = one step; the three-way join by `step=` ceases to exist |
| `[APE-STEP] clock=` (epoch ms) | `t` (ms since `RUN_START`) | `RUN_START` carries the epoch base `t0`; epoch = `t0 + t`. Volume lever; the reader re-expands to epoch ms wherever an absolute clock is wanted |
| `[APE-STEP] activity=` / `[APE-LLM-TEL] activity=` | `act` (int) | defined by `{"type":"ACT","id":17,"name":"...","mop":1}` on first sight |
| `[APE-STEP] state=` | `st` (int) | defined by `{"type":"STATE","id":231,"key":"...","act":17}` on first sight |
| `[APE-STEP] activity_has_mop=` / `[APE-OUTCOME] activity_has_mop=` | `ACT` entry field `mop` | static per-activity fact → recorded once on the dictionary entry; outcome-side value = `ACT[STATE[out.target].act].mop` |
| (run id — today absent from every line, V15) | `RUN_START`/`RUN_END` only | D2: intra-file repetition is redundancy; the `.trace` is 1:1 with the task |

### `dec` — decision section (from the `[APE-STEP]` line)

| Legacy field | New location | Notes |
|---|---|---|
| `action=` (full `ModelAction` string) | `dec.a` | now JSON-escaped — the #16 newline-flattening class of fix becomes unnecessary by construction |
| `decision_source=` | `dec.src` | supplied by the rearch-03 stage that selected (`StageResult.Select(action, decisionSource)`) |
| `pick_channel=` | `dec.ch` | same enum labels (`short_circuit_unvisited`, ..., `sata_other`) |
| `priority=` | `dec.pri` | model-action records only, always present there |
| `mop= mop_frontier= wtg= coverage= menu= form=` | `dec.mop dec.mopf dec.wtg dec.cov dec.menu dec.form` | **omitted when 0** (defaults-omitted rule); the reader materializes explicit zeros |
| `patched=` (tri-state: absent / 0 / 1) | `dec.patched` | tri-state preserved: absent = no resolved target (as today); `0` and `1` are both emitted explicitly — **exempt from default omission** because absence is itself information (O4) |
| `cf_action= cf_changed=` (only on the 4 MOP-sensitive channels) | `dec.cf` | present exactly on those channels: `{"changed":0}` when unchanged or recomputation failed (cf action = factual action, as today); `{"changed":1,"a":"<counterfactual action>"}` when divergent (A3) |
| non-model `[APE-STEP]` (8 fields) | record with `dec` = `{a, src, ch}` only | no `pri`/boosts, as today; `dec.src` from `nonModelDecisionSource`, `dec.ch` from `nonModelPickChannel` |
| (nothing today — the two producers of `wtg=` are indistinguishable) | `dec.wtgsrc` ∈ `wtg`\|`frontier`\|`both` | present exactly when `dec.wtg` is non-zero. `wtgBoost` is written by `WtgPass` and read-modify-written by `FrontierPass`; with both weights at 200 the campaign realises `{0,200,400}`, so 10,231 steps at 200 are ambiguous and only 91 at 400 prove both fired. One compile-time constant at each write site — no scoring-context accessor, no pipeline plumbing, and the accumulated value is unchanged |
| `[APE-RV] MOP boost: … boosted=X/Y` (`MopWidgetPass.java:63`) | `dec.mopx:[boosted,total]` | present when the MOP widget pass is constructed. **Outside the no-information-loss clause**, 1:1 with steps today, and the only record of how many on-screen actions were eligible for MOP steering — without it "the scorer fired on 0.4 % of decisions" has no denominator. Cannot ride the `STATE` entry: measured over 25 campaign traces, 14 show a state with more than one realised pair, so it is genuinely per-step and is the first field to drop if INV-SNK-13 binds |
| (nothing today — dispatched and accepted are the same evidence) | `dec.comp:{"r":<int>,"e":"<enum>"}` | component-trigger records only. From the boolean the dispatch site already binds plus the `START_*` code discarded one frame below in `AndroidDevice.startActivity`. No retry, no re-dispatch, no cursor mutation. Ordering is fine here although it was not in the retired family: the record closes at N+1, after dispatch, whereas `[APE-STEP]` was emitted before it |

### `llm[]` — ordered sub-events (from `[APE-LLM-TEL]` / `[APE-LLM-ERROR]` / breaker line)

One entry per routing attempt in the step, in occurrence order (a `BadStateException` selection retry appends to the same step's list — same semantics as today's multiple `step=N` TEL lines).

| Legacy field | New location | Notes |
|---|---|---|
| `variant=` | dropped from the per-call event | run-constant; lives in the `RUN_START` effective plan (rearch-02) |
| `call=` | `call` | running call counter |
| `mode=` | `mode` | `new_state` / `stagnation` / `random` |
| `action=` (tool name) | `tool` | |
| `qwen=(x,y)` / `pixel=(x,y)` | `qwen:[x,y]` / `px:[x,y]` | |
| `result=` | `result` | `matched` / `llm_tap` / `no_match`; plus `error` (replacing `[APE-LLM-ERROR]`) and `breaker_open` (replacing the once-per-episode breaker line, with `trips`) |
| `reason=` (opt) | `reason` | `dead_pair` / `degenerate` / `boundary` |
| `repair=` (opt) | `repair` | repair-form provenance unchanged (INV-RTR-13) |
| `matched_class= nearest_class= nearest_dist= widgets=` | `mcls ncls ndist widgets` | |
| `tokens_in= tokens_out=` | `tok:[in,out]` | |
| `time_ms=` | `ms` | |
| `text="..."` (opt) | `text` | properly escaped now (was quote-fragile) |
| `[APE-LLM-ERROR] step= cause= detail=` | `{"result":"error","cause":...,"detail":...}` entry | attribution to the step is by construction (parent record) — no `step=` key needed |
| `[APE-LLM-PROMPT] system=/user_text=`, `[APE-LLM-RESPONSE] content=/tool_calls=` | `sys`, `user`, `resp`, `tool_calls` fields on the call entry | behind `TelemetryParams.llmPromptDump`, **default on** (today they are unconditional — recordability preserved; the flag is a volume lever the owner may flip later) |

### `out` — outcome section (from the `[APE-OUTCOME]` line)

| Legacy field | New location | Notes |
|---|---|---|
| `step=` | same record | closure at N+1, same timing and same reference-equality guard as the join buffer today |
| `decision_source=` | already `dec.src` | identical by construction (single record) |
| `new_state=` | `out.new_state` | boolean, omitted when `false` |
| `target_state=` | `out.target` | `STATE` id |
| `activity_changed=` | `out.act_changed` | boolean, omitted when `false` |
| `activity_has_mop=` | derivable | via `out.target` → `STATE.act` → `ACT.mop` |
| *absence* of `[APE-OUTCOME]` (restart, non-model action, refinement discard, run end) | record closed **without** an `out` member | the legitimate, informative absence of INV-ARCH-09 is preserved as a distinct encoding from... |
| (run cut mid-step) | `out:{"resolved":false}` | ...the teardown flush of the in-flight record (`flushPendingStep`); SIGKILL before teardown loses at most this 1 record |

### Run-level records (out-of-step, same NDJSON stream)

| Legacy | New record | Notes |
|---|---|---|
| `[APE-LLM-CONFIG]` manifest (`LlmRouter.java:148-160`) | retired → `RUN_START` effective plan | **dependency on rearch-02**: its `RUN_START` must carry the effective LLM params (model, temperature, top_p, top_k, max_tokens, timeout_ms, prompt_variant, llm_percentage, on_new_state, on_stagnation, stagnation_threshold, url) — flagged in rearch-02's artifacts review |
| `[APE-LLM-CONFIG-ACK] server_model=` | `{"type":"LLM_ACK","server_model":...}` | once, on first successful response (INV-RTR-12 semantics unchanged) |
| `[APE-MOP-DATA] status=...` (`MopData.java:203-332`) | `{"type":"MOP_DATA","status":"loaded"\|"rejected","reason":...,"package":...,"windows":...,"widgets":...,"flagged":...,"droppedNoId":...,"wtgEdges":...,"handlersUnmatched":...,"syntheticLambda":...,"recovered":...,"mopActivities":...,"mopActsAugmented":...}` | same statuses/reasons; the **full** load census, since this line is outside the no-information-loss clause and would otherwise lose eight fields by omission rather than by decision. **`wtgEdges` replaces `transitions`**: the three frontier passes gate on the click-only `wtgTransitions` view while the retired line logged `transitions.size()` (the flat list) — 14 of the 40 campaign applications report 9–29 transitions with the whole family disabled. Same field name as the stage-7 artifact, so it does not change identity mid-window. No `has_wtg_data` boolean: it is `wtgEdges > 0` by construction |
| `[APE-ARCH] passes=[...]` (`ScoringPipeline.java:36`) | `{"type":"PIPELINE","stages":[...],"passes":[...],"candidates":[{"name":...,"enabled":...}]}` | assembly provenance; stage list from rearch-03, as a **static name list** — INV-DP-03 forbids a constructed disabled-stage object. `passes` keeps exactly today's content; `candidates` is what makes it readable as a data-dependent outcome, since the WTG/frontier family is never constructed in 25 of 40 applications and nothing else in the trace says so. Emitted from `fromConfig`, where the candidate list is still in scope (the constructor discards it at `:35`). No `reason` field: the gate conjuncts are recoverable from `MOP_DATA.status`, `MOP_DATA.wtgEdges` and `RUN_START.params`, and the three passes do not order their conjuncts uniformly, so a "first failing conjunct" would encode source order rather than cause |
| `[APE-RV] LLM Summary` + `Decision ratio` (`LlmRouter.printSummary`, `:907-936`) | `RUN_END.counters.llm` | all 17 counters (`calls tok_in tok_out ms matched llm_tap no_match dead_pair repaired timeout http_error conn_error parse_error image_error internal_error screenshot_failed breaker_trips`); the ratio is derived, not stored. `printSummary()` deleted; `LlmRouter` exposes a counters accessor |
| (nothing today) | `{"type":"RUN_END","reason":...,"steps":N,"t_first_step":...,"t_last_step":...,"counters":{...}}` | reason ∈ `timeout` (StopTestingException/time budget), `crash` (abnormal Throwable, class name in `detail`), `unknown`. Write-only, D5: **no consumer validates it**. The two timestamps separate a run that explored for its budget from one that was alive and idle — the campaign contains a `COMPLETED` run with 150 steps spanning 446 s of 1,871 s, which a consumed-budget field would have reported as ~1,800 s and flagged as healthy |

## Decisions

### D-1 — One record per step, closed at N+1; the join buffer becomes the accumulator's guard

The outcome of step N only exists during step N+1's `updateGraph()` — the exact fact the join buffer (`StatefulAgent.java:117-124`) already encodes. The sink keeps **one** pending record: `beginStep` opens it (lazily, at selection start, so LLM sub-events occurring before the final action is known still land in it), `decision` fills `dec` when `resolveNewAction` finalizes, `outcome` closes+writes it under the same single-shot, reference-equality guard the `[APE-OUTCOME]` line uses today (`:1024-1045`). If the next `beginStep` arrives with the previous record still pending (non-model action, restart, refinement discard — the cases where today no `[APE-OUTCOME]` is emitted), the pending record is closed **without** an `out` member and written. Nothing is deferred beyond what is already deferred; cost stays ~1 stdout write per step. Alternative considered: write two records (decision at N, outcome at N+1) — rejected: it re-creates the analyst-side join D2 exists to kill.

### D-2 — Hand-written serializer, not `org.json`; one class, extended rather than duplicated

`org.json.JSONObject` is available on-device, but: (i) it is hash-ordered — field order would be non-deterministic across runs, hurting goldens, diffs, and gzip ratio; (ii) its escaping behavior varies across Android releases and is not under test here; (iii) per-record object graphs allocate.

The serializer this decision calls for **already exists**: `rearch-02` shipped `ape.runtime.Json` for the `RUN_START` line, and its javadoc states the contract — this stage grows the writer, not the format. What stage 4 adds to it is the nested `Json.Buf` (the streaming writer; `Json.object(Map)` builds a value tree per call, the wrong shape for one record per step across a run) and the two code points the escape set was missing. It does **not** add a second class: two implementations of how a control character leaves this process is how they drift, and the trace would then have two answers for the same byte depending on which record it sat in (P3). The permanent test that keeps the single format honest is the agreement assertion — the same value tree through `Json.object(Map)` and through `Json.Buf` renders the same line. `Json.Buf` appends into one reused `StringBuilder`, escapes `"` `\` and all of U+0000–U+001F (shorthands `\n \r \t \b \f`, ` ` for NUL, plus U+2028/U+2029 — the JS line separators), passes non-ASCII through (the stream is UTF-8), and **never emits a raw newline** — the record terminator is the single `\n` written by the sink around the record. `org.json` is used only in JVM tests, as the round-trip parser (it is already on the test classpath).

### D-3 — Dictionary events with run-local integer IDs; static facts live on the entry

`activity` and `state` are the longest, most repeated strings in the trace. First sight of each emits `{"type":"ACT","id":N,"name":...,"mop":0|1}` / `{"type":"STATE","id":N,"key":...,"act":actId}` as its own line, *before* any record references the ID (ordering invariant). `activity_has_mop` — a static per-activity fact — moves onto the `ACT` entry instead of repeating on every step and outcome; the reader re-derives both the step-side and the outcome-side value by lookup. IDs are run-local and write-only; no cross-run meaning.

### D-4 — Neutrality by interface shape (R7)

All `EventSink` methods return `void` and take primitives/strings the caller already has; the sink never hands anything back to decision code, never touches the seeded RNG, and holds no reference to `Model`/`Graph`/`RunContext` state — it interns copies (strings, ints) only. `NoopSink` is the test double, and the R7 test runs the rearch-01 parity harness with `NdjsonSink` vs `NoopSink` under the same seed and asserting identical action sequences.

**How the off configuration is constructed, corrected at apply time (2026-08-04, owner-decided).** This section first specified a `TelemetryParams.sink` plan key that "every preset pins on". That contradicts the capability's own Telemetry Neutrality requirement — *"no arm-level flag disables or alters it"* — because a registered key **is** an arm-level flag: presets not stating it does not stop a run from stating it, and `ape.stepTelemetryEnabled` becoming `ape_pure`'s blindness switch is exactly how that goes wrong in this study. So there is no sink key. `RunContext` always constructs `NdjsonSink` on the production path, and `NoopSink` is reachable only through `RunContext.installForTest(spec, sink)`, which no production code path calls. Plan validation then rejects `ape.telemetrySink` as an unknown key, which is the strongest available form of "reachable only by the test configuration": a campaign cannot ask for a blind arm even by mistake.

### D-5 — `RUN_END` and `flushPendingStep` as safeSteps; no exit contract (owner D5)

Teardown chain (all inside the existing `safeStep` isolation — INV-EXPL-16/29 untouched): `flushPendingStep` runs **first** in `StatefulAgent.tearDown` (minimizes loss window; writes the in-flight record with `out:{"resolved":false}`); `runEnd` runs **last** (after counters/naming dumps), so `RUN_END` is the natural last NDJSON record of a normal termination. A SIGKILL before teardown loses at most the pending record and the `RUN_END` — both accepted: truncation detection stays post-hoc by timestamps (D5), and the status quo lost far more (42.3% of runs lost the whole coverage dump before A10). **No Python code reads, validates, or acts on `RUN_END`** — the jar-side test (Sec. 9.7) is the only check.

### D-6 — Heartbeat: `Log.i`, plan flag, default on (owner D4)

One write-only line per step — `Log.i("ApeRvHb", "s=<N> t=<tRel>")` — emitted where the step envelope is captured (the `beginStep` site), behind `TelemetryParams.heartbeat` (property `ape.telemetryHeartbeat`, default `true`).

`ApeRvHb` stopped being this design's proposal and became a cross-repository constant on 2026-08-04: the counterpart change declares `TAG_APERV_HEARTBEAT = "ApeRvHb"` once in `rvsec` and appends it to `LogcatManager.default_tags`, and `clock_logcat_join.py` matches the heartbeat line against it. The jar's constant is therefore not a free choice and its comment SHALL name the counterpart declaration site — a mismatch between the two literals is invisible from either side alone. Verified read-only at the same date: `_align_clocks()` and `alignment_residual_ms` are still present in `clock_logcat_join.py`, which is what INV-SNK-14 requires until a captured run demonstrates the lines arriving. `t` is the same run-relative value the record carries, so the trace↔logcat mapping is exact; the violation↔step join runs in the Python analysis pipeline against the logcat clock — no runtime APE↔logcat coupling, and the jar never reads logcat.

### D-7 — Legacy deletion, with the replay reader preserved

Deleted (writer sites in Context §5): `saveGraph()`'s dot/vis/per-state branches and the `Graph.printDot`/`Graph.printVis`/`State.saveState` methods they call; `saveActionHistory()` + `Model.saveActionHistory`; the produce/consume `PrintWriter`s, their five `ApeRRFormatter.log*` call sites, and the `sataTimeline.vis.js` export (`toVisTimeline`); Config flags `saveDotGraph`, `saveVisGraph`, `saveStates` (and `stepTelemetryEnabled`, whose gate dies with always-on telemetry); the `[APE-STEP]`/`[APE-OUTCOME]`/`[APE-LLM-TEL]`/`[APE-LLM-ERROR]`/`[APE-LLM-PROMPT]`/`[APE-LLM-RESPONSE]`/`[APE-LLM-CONFIG]`/`[APE-LLM-CONFIG-ACK]`/`[APE-MOP-DATA]`/`[APE-ARCH]`-passes emitters. **Kept**: `ApeRRFormatter.readActions`/`parseRect` and everything `ReplayAgent` needs (`ReplayAgent.java:63`) — replay consumes externally supplied logs; only the tool's own production of those logs ends. A caller audit decides whether `formatRect`/`ModelAction` JSON helpers (`ModelAction.java:344`) die with the vis output or stay with the replay/xpath paths.

### D-8 — Python side: gzip alongside, and a native NDJSON reader; no format conversion

After the run completes (post `_check_empty_trace`, which is unchanged — a 0-byte NDJSON trace is still 0 bytes), `tool.py` does exactly one thing: it gzips the raw NDJSON capture to `<trace>.ndjson.gz`. **`task.result.trace_file` is never rewritten** — the `.trace` is the NDJSON, and the NDJSON is the artifact of record. The step is non-fatal (warning on failure, uncompressed trace left in place) and write-only — **no validation, no sentinel check, no status change (D5)**.

There is no NDJSON→legacy converter, and this is the decision rather than an omission. Reconstructing the `key=value` family over the primary artifact would invert which file is authoritative (the `.trace` everyone opens becomes a derived reconstruction while the real data hides in a sidecar `.gz`), would re-impose the unescaped format this change exists to kill — a newline inside a `text=` value breaks the line again, which is exactly the A8 defect class — so the escaping guarantee of INV-SNK-01/02 would hold only where nobody reads, and would defer the volume win indefinitely, since storage would be the legacy-format trace *plus* the compressed NDJSON.

Analysis reads the records natively instead. `trace_ndjson.py` (rv-android, `aperv_tool/analysis/`) is that reader: it streams the trace, resolves the `ACT`/`STATE` dictionaries, materializes the omitted defaults, and yields one typed row per step with its `dec`, `llm[]`, and `out` sections already joined. The single production consumer of the old `[APE-STEP]` family — `clock_logcat_join.py` — migrates onto it in this stage (tasks group 8).

**Migrating that module is a simplification, not a port.** Most of its complexity today reconstructs the device's UTC offset, because the trace stamps `System.currentTimeMillis()` while logcat stamps local time with no year and no zone (three year candidates, rounding to the nearest quarter hour, anchor selection, `alignment_residual_ms`). The D-6 heartbeat puts step and violation in the same file, on the same clock, in the same rendering — the whole offset reconstruction becomes dead code and is deleted along with the regex it fed.

**Carve-out, written here so a future P3 sweep does not delete it.** The 2026-07-24 calibration report and the decisive run stand on **legacy-format** traces, and that corpus will never change again. `clock_logcat_join.py` migrates because it has to read *new* traces; the archived-corpus readers — `scripts/cmpm_stratify.py`, `scripts/analyze_cmpv2_llm.py`, `experimento-cal/scripts/*`, `experimento-20260721/scripts/*`, `calibracao/*` — do not, and stay frozen exactly as they are. They are not compatibility shims for new data: they are the readers of a dataset that is finished. P3 governs superseded *implementation*, not analysis code over frozen data.

Storage after the run: `.trace` = raw NDJSON (the 3–5× reduction the change exists for) plus `<trace>.ndjson.gz` for storage at rest.

### D-9 — Sink failure policy

Sink methods never propagate a `Throwable` into the exploration loop: each public method body is guarded; on the first internal failure the sink latches disabled for the run and logs one `[APE] ` warning. Rationale: a telemetry bug must not alter or kill an experimental run (R7's spirit); the serializer's correctness is carried by its permanent tests, not by runtime defensiveness.

## Mapping: Spec → Implementation → Test

| Requirement / Invariant | Implementation | Test |
|-------------|---------------|------|
| INV-SNK-01 one line per record | `Json.Buf` + `NdjsonSink.write` | permanent JVM unit: no raw `\n` for adversarial inputs |
| INV-SNK-02 escaping round-trip | `Json.Buf.value(String)`, shared with `Json.object(Map)` | JVM round-trip via `org.json` parse (newline, quotes, backslash, NUL, spaces, non-ASCII) — Sec. 9.12 |
| INV-SNK-03 exactly one StepRecord per step | `NdjsonSink` lifecycle | JVM unit: scripted step sequence → record count == step count |
| INV-SNK-04/05 envelope 1×, defaults omitted, tri-state exemptions | record writer | JVM unit: zero boosts absent; `patched` tri-state preserved |
| INV-SNK-06 dictionary-before-reference | `NdjsonSink` ID tables | JVM unit: first-sight ordering |
| INV-SNK-07 neutrality (R7) | interface shape + `NoopSink` | rearch-01 parity harness, sink on/off, same seed ⇒ same action sequence — Sec. 9.8 |
| INV-SNK-08 closure at N+1; `resolved:false` flush; max loss 1 step | `StatefulAgent` wiring + `flushPendingStep` | JVM unit: outcome joins its step; flush writes pending; no-outcome close has no `out` |
| INV-SNK-09 `RUN_END` last on normal termination (jar-side only, D5) | teardown `runEnd` safeStep | JVM unit on teardown order — Sec. 9.7 |
| INV-SNK-10 heartbeat | `Log.i` at `beginStep`, `TelemetryParams.heartbeat` | JVM unit on the guard (Android `Log` gated on device) |
| INV-SNK-11 discriminator (`{` vs `[APE] `) | sink writes bypass `Logger` | JVM unit: every record line starts with `{` |
| Exploration teardown deltas (INV-EXPL-16/29 preserved) | safeStep chain edit | JVM unit: one failing step skips nothing; loop exception preserved |
| llm-routing sub-events | `LlmRouter`/LLM stages → `sink.llmCall/llmError` | JVM unit where mockable; field parity vs mapping table |
| scoring-pipeline outcome delta; INV-ARCH-01 removed | `updateGraph` wiring | neutrality test is the recorded substitute |
| aperv-tool native reader + gzip | `trace_ndjson.py`; `tool.py` post-run gzip | pytest: golden NDJSON → expected typed rows; `clock_logcat_join` over a new-format trace + heartbeat logcat; gzip round-trip; gzip failure is non-fatal |
| Acceptance Sec. 9.11 (blocked on the native reader) | `trace_ndjson.py` + the migrated `clock_logcat_join.py` | regenerate the 2026-07-24 calibration tables from a sample new trace |

## API Design

### `Json.Buf` (nested in the class `rearch-02` shipped)

```java
public final class Json {                       // ape.runtime.Json, zero deps
    public static String object(Map<String, ?> members);   // value tree; RunSpecEcho's entry point

    public static final class Buf {             // streaming writer, reused via reset()
        public Buf reset();
        public Buf beginObject();  public Buf endObject();
        public Buf beginArray();   public Buf endArray();
        public Buf name(String key);
        public Buf value(String s);             // full escaping; null → JSON null
        public Buf value(long n);  public Buf value(double d);  public Buf value(boolean b);
        public String toLine();                 // completed record; contains no '\n'
    }
}
```

Preconditions: balanced begin/end (assertion-checked in tests, not at runtime). Postcondition: `toLine()` output parses as one JSON value and contains no raw control characters.

### `EventSink`

```java
interface EventSink {                    // all void; never throws into the loop
    int ABSENT = -1;                     // the tri-states' third state, and 0 is taken

    void beginStep(int step, long tRelMs, String activity, boolean activityHasMop,
                   String stateKey);     // opens pending record; interns ACT/STATE ids;
                                         // closes a still-pending predecessor without `out`;
                                         // re-entering the same step keeps the open record
    void decision(String action, String decisionSource, String pickChannel,
                  int priority, int mop, int mopFrontier, int wtg, int coverage,
                  int menu, int form, String wtgSource /*null unless wtg != 0*/,
                  int patched /*ABSENT*/, int cfChanged /*ABSENT*/,
                  String cfAction /*null unless changed*/);
    void decisionNonModel(String action, String decisionSource, String pickChannel);
    void mopExposure(int boosted, int total);          // from the MOP widget pass, at its own site
    void componentLaunch(int result, String error);    // after dispatch, which is when it exists
    void llmDump(String system, String user, String response, String toolCalls);
    void llmCall(int call, String mode, String tool, int qwenX, int qwenY, int pixelX,
                 int pixelY, String result, String reason, String repair,
                 String matchedClass, String nearestClass, double nearestDistance,
                 int widgets, int tokensIn, int tokensOut, long ms, String text);
    void llmError(String cause, String detail);
    void llmBreakerOpen(int trips);
    void outcome(boolean newState, String targetStateKey, String targetActivity,
                 boolean targetActivityHasMop, boolean activityChanged);
    void flushPendingStep();             // teardown: writes pending with out:{"resolved":false}
    void mopData(/* status, reason, pkg, windows, widgets, flagged, droppedNoId, wtgEdges,
                    handlersUnmatched, syntheticLambda, recovered, mopActivities,
                    mopActsAugmented */);
    void pipeline(List<String> stages, List<String> passes, Map<String, Boolean> candidates);
    void llmAck(String serverModel);
    void runEnd(String reason, RunCounters counters);  // added by group 4, with RunCounters
}
```

`RunCounters` is a plain value object filled at teardown from `LlmRouter` accessors (which replace `printSummary`) plus step/dictionary counts maintained by the sink itself.

**Four signatures differ from this section's first draft, each because the record the spec mandates could not otherwise be produced.** They are recorded here rather than left to the implementation, since the sketch is what the next group reads.

- `outcome` carries the target's **activity** and its MOP flag, not only the state key. The target state can be seen for the first time at outcome time — that is what a new state *is* — and its `STATE` dictionary entry has to name an activity, because the outcome-side `activity_has_mop` is derived by the reader as `out.target → STATE.act → ACT.mop` (D-3). Without it the derivation breaks for exactly the new states the run exists to find.
- `mopExposure` is its own call rather than two more parameters on `decision`, because the pair is computed inside the MOP widget pass — the site that emits `[APE-RV] MOP boost` today — and threading it out to `resolveNewAction` would add a scoring-context accessor that `dec.wtgsrc` was deliberately designed to avoid.
- `componentLaunch` is its own call because of ordering: the launch result does not exist when the decision is recorded. This is precisely why the retired `[APE-STEP]` line could never carry it, and the reason the record can is that it closes at N+1.
- `llmDump` stages the prompt/response text for the next sub-event instead of riding `llmCall`'s parameter list. The dumps exist before the call's outcome does — the retired `[APE-LLM-PROMPT]`/`[APE-LLM-RESPONSE]` lines preceded their `[APE-LLM-TEL]` line for the same reason — so staging is what lets an attempt abandoned before it maps keep the prompt that produced it.

`decision` also gains `wtgSource` (task 3.1a's stamp, carried on the action like `wtgBoost` itself), and `pipeline` gains the candidate census that the `PIPELINE` record requires. `mopData` takes the full census of the run-level requirement rather than the five fields sketched here.

## Data Flow

1. **Bootstrap** (rearch-02): `RunSpec` resolved; sink constructed from `TelemetryParams`; `RUN_START` written as the first record (carrying `t0` and the effective plan — including the retired `[APE-LLM-CONFIG]` content). `PIPELINE` record at pipeline assembly; `MOP_DATA` at load.
2. **Step N selection**: `beginStep(N, now-t0, activity, hasMop, stateKey)` (+ heartbeat `Log.i`); LLM stages append `llm[]` entries as attempts happen; `decision(...)` fills `dec` when the action is finalized; the join buffer records `(N, action)` exactly as today.
3. **Step N+1 `updateGraph`**: when the buffered decision is reference-equal to `currentAction` and a transition was recorded, `outcome(...)` closes and writes record N (one line). Otherwise record N closes without `out` at the next `beginStep`.
4. **Teardown**: `flushPendingStep` (first agent safeStep) → existing dumps/counters → `runEnd(reason, counters)` (last agent safeStep). Later stdout writes by non-sink teardown steps may follow; `RUN_END` is the last *sink record*, not necessarily the last stdout line.
5. **Collection** (Python): stdout already captured into `.trace`; post-run, gzip raw NDJSON → `<trace>.ndjson.gz`. The `.trace` stays the raw NDJSON — nothing rewrites it, nothing validates it (D5). Analysis reads the records natively (D-8).

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| Sink internal `Throwable` | any `EventSink` method | latch sink disabled; one `[APE] ` warning; method returns normally | run continues untelemetered; run is analyzable from logcat (R9) |
| SIGKILL mid-run | harness timeout | nothing in-process; pending record lost | max loss 1 StepRecord + `RUN_END`; post-hoc timestamps (D5) |
| Teardown step throws | safeStep chain | caught + logged per step (INV-EXPL-16/29) | remaining steps incl. `runEnd` still run |
| gzip failure | `tool.py` post-run | warning; uncompressed `.trace` kept | none needed; the trace is intact either way |
| Malformed record | `trace_ndjson.py`, at **analysis** time — not on the collection path | the reader skips the line and counts it in its own diagnostics | analysis continues; the `.trace` and its `.gz` are never altered |
| Heartbeat `Log.i` throws | logcat write | swallowed inside the sink guard | heartbeat absent for that step; join falls back to wall-clock |

## Risks / Trade-offs

- [Record loss window at SIGKILL] → bounded at 1 step by construction; `flushPendingStep` covers every normal termination; strictly better than the pre-A10 status quo (42.3% lost the whole coverage dump).
- [The one real `[APE-STEP]` parser stops reading new traces] → `clock_logcat_join.py` migrates to the native reader inside this stage (group 8), which shrinks it rather than porting it (D-8); the frozen-corpus readers are untouched by the carve-out. This is the scope this change absorbs by refusing a converter, and the acceptance gate (Sec. 9.11) is what proves it landed.
- [Telemetry cost changes step throughput (a measured variable)] → **measured, not argued** (INV-SNK-13, task 9.1a). The argument in favor is real but partial: ~1 stdout write per step, accumulator reused, dictionary and default omission strictly reduce bytes. What it does not count is the new per-step **logcat** write (D4), the per-character escaping now applied to prompt dumps that stay default-on (U+0000–U+001F, U+2028/U+2029 — work the `key=value` format never did), and the full-trace gzip added on the collection path, timeout path included. Whether those net out below the retired family is empirical, the budget is wall-clock, and this change's own proposal prices a lost step at 0.037–0.052 pp `cov_mop`; so the stage carries a steps-per-minute measurement with its own noise established, and a regression beyond that noise blocks it.
  For campaign arms the accounting is favorable before measurement: `Config.stepTelemetryEnabled` already defaults `true` and only `ape_pure` disabled it (`tool.py:266-284`), so every surviving arm goes from two per-step writes to one and loses the per-event `produce.log`/`consume.log` I/O entirely. The measurement exists to confirm that, and to catch the paths the accounting omits.
- [The heartbeat is written under a tag the capture filters out] → rv-platform streams `adb logcat … -s RVSEC:V RVSEC-COV:V`, a strict allowlist, so a heartbeat under any other tag never reaches the file the join reads and the mechanism is inert while looking healthy. The counterpart change in `rvsec` adds the tag to `LogcatManager.default_tags` (touching that repo's INV-PLT-21 byte-identical-command clause deliberately), and INV-SNK-14 forbids deleting `clock_logcat_join.py`'s offset reconstruction until a captured run shows the lines present.
- [Sink accidentally influences decisions] → interface returns `void` only; `NoopSink` neutrality test is a permanent gate (R7).
- [Deleting produce.log breaks replay] → reader path preserved (D-7); replay consumes externally supplied logs.
- [`RUN_START` (rearch-02) lacks fields this change retires from `[APE-LLM-CONFIG]`] → flagged as an explicit cross-change dependency; verified when rearch-02's artifacts are reviewed and at this change's acceptance test.
- [Two stacked changes edit the same specs] → this change's deltas are written against main specs at `5dcf225`; rearch-02/03 archive first (sequential roadmap order) — any drift is reconciled at apply time, and the scoring-pipeline REMOVED entry records both grounds (mechanism → rearch-02; INV-ARCH-01 → here).

## Testing Strategy

| Layer | What | How |
|-------|------|-----|
| JVM unit (permanent) | `Json.Buf` escaping round-trip, and its agreement with `Json.object(Map)`, + one-line invariant (Sec. 9.12); StepRecord lifecycle, dictionary ordering, tri-state `patched`, defaults omission; teardown order (`flushPendingStep` first, `runEnd` last); discriminator | `mvn test`, existing suite conventions |
| Neutrality (permanent gate) | sink on/off, same seed ⇒ identical action sequence (Sec. 9.8) | rearch-01 parity harness with `NdjsonSink` vs `NoopSink` |
| Python unit | reader golden: NDJSON fixture → expected typed rows (dictionary resolution, defaults materialization, `llm[]` and `out` joined onto their step); `clock_logcat_join` over a new-format trace plus a heartbeat logcat; gzip round-trip and non-fatal failure | pytest in aperv-tool |
| Acceptance | regenerate the 2026-07-24 calibration report tables from a sample new trace (Sec. 9.11) | rv-android side, sample run via rv-platform |
| Throughput (stage gate) | steps per minute, same APK/seed/budget: pre-change jar twice (to establish the measurement's noise) vs post-change jar once — INV-SNK-13 | owner-executed run via rv-platform; numbers recorded in the verification notes either way |
| Heartbeat reachability | a captured run contains the heartbeat lines under the harness's tag allowlist — INV-SNK-14, the precondition for group 8's offset-reconstruction deletion | inspection of the task's logcat file from the same run |
| Build | `mvn package` green; jar into aperv-tool via `mvn install` | verification group |

## Open Questions

None. All owner decisions are final (D2/D4/D5, report Sec. 12); the items this design resolved beyond the proposal are: the tri-state `patched` exemption from default omission, static facts on dictionary entries, `t` as run-relative ms with `t0` in `RUN_START`, prompt/response dumps as flagged sub-event fields (default on), the breaker-episode line as an `llm[]` sub-event, LLM summary counters folded into `RUN_END`, and the Python side reduced to gzip plus a native reader with no format conversion (D-8) — all mechanical consequences of D2/D5 and the recordability acceptance, none reopening an owner decision.
