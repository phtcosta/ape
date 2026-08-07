# event-sink Specification

## Purpose

The event sink is the observation surface of a run: everything the jar records about its own behavior flows through one component, `EventSink`, into one stream — NDJSON records on stdout, captured by the harness into the `.trace` file exactly as today. The sink replaces the `[APE-STEP]`/`[APE-OUTCOME]`/`[APE-LLM-TEL]` `key=value` line family and its analyst-side re-join by `step=`: instead of three unescaped lines per step that repeat the envelope and the defaults, the sink emits **one `StepRecord` per step** — envelope once; the decision, every LLM routing attempt, and the outcome grouped inside it — closed and written when the step's outcome resolves at step N+1, the same timing the existing decision join buffer already encodes.

Telemetry is an instrument, not a feature (report Sec. 6.5): it is **always on and identical for every experimental arm**. This deliberately dissolves INV-ARCH-01 (the baseline arm's telemetry blindness); the substitute is a *provable* neutrality property (R7): the sink observes and never decides — with the sink on or off, the same seed produces the same action sequence, and that equivalence is a permanent test. The serializer is hand-written (~80 LOC, zero dependencies) with real escaping, so the newline-in-source-data failure class that the #16 fix patched *at the origin* dies by construction: any content fits the format.

Volume is a first-class concern because trace cost per step is itself a measured experimental variable (0.037–0.052 pp `cov_mop` per lost step) and the current trace grows ~3.5 GB per 880 tasks. Four levers, all enabled by grouping: envelope once per step; defaults omitted (absent field = default); a run-local integer ID table for the trace's longest repeated strings (`ACT`/`STATE` dictionary events); and `run_id` only in the border records `RUN_START`/`RUN_END`. Compression at rest is the Python side's job (gzip at collection — aperv-tool capability).

The stream also carries out-of-step records: `RUN_START` (emitted by the run-spec capability as the first record), dictionary entries, `MOP_DATA` load status, `PIPELINE` assembly provenance, `LLM_ACK`, and `RUN_END` (reason + counters) as the natural last record. Per owner decision D5 there is **no exit contract**: no consumer validates `RUN_END`, no sentinel logic exists in `tool.py`, and truncated-run detection remains a post-hoc analysis over timestamps. Per owner decision D4, a write-only logcat heartbeat (one `Log.i` line per step) places violation and step in the same file and clock for the analysis-side join, behind a plan flag that defaults on.

## Data Contracts

### Input

- `step: int`, `tRelMs: long` — exploration step counter and milliseconds since `RUN_START` (whose record carries the epoch base `t0`)
- `activity: String`, `stateKey: String`, `activityHasMop: boolean` — envelope material, interned into dictionary IDs by the sink
- decision material: action string, `decision_source`, `pick_channel`, priority, per-mechanism boosts, `patched` tri-state, counterfactual result — from the selection path
- LLM sub-event material: call#, mode, tool, model/pixel coordinates, result/reason/repair, matched/nearest class, distance, widget count, token counts, latency, typed text, prompt/response dumps — from the LLM routing path
- outcome material: `new_state`, target state key, `activity_changed` — from `updateGraph` at step N+1
- run-level material: MOP data load status, pipeline assembly, LLM server acknowledgement, termination reason + counters

### Output

- NDJSON records on `System.out`, one line each, interleaved with (and mechanically separable from) free-text `[APE] ` diagnostic lines; captured by the harness into the `.trace`
- one `Log.i` heartbeat line per step in logcat (`s=<N> t=<tRelMs>`) when the heartbeat flag is on

### Side-Effects

- **[stdout]**: ~1 write per step (the closed record) plus one write per first-sighted dictionary entry and per run-level record
- **[logcat]**: one heartbeat line per step (flag-gated, write-only; the jar never reads logcat)

### Error

- Sink-internal `Throwable` — never propagated to the caller; the sink latches disabled for the remainder of the run and logs one warning

## Invariants

- **INV-SNK-01**: Every sink record SHALL occupy exactly one line: the serializer SHALL NOT emit a raw newline (or any raw control character) inside a record; the record terminator is the single `\n` written by the sink.
- **INV-SNK-02**: The serializer SHALL escape `"` and `\`, SHALL escape every character in U+0000–U+001F (including NUL) as `\uXXXX` or its JSON shorthand, SHALL escape U+2028/U+2029, and SHALL pass other non-ASCII characters through unmodified (the stream is UTF-8). Any string value SHALL survive a serialize→parse round trip byte-identically.
- **INV-SNK-03**: Exactly one `StepRecord` SHALL be written per exploration step that reaches action selection; `s` values are unique and strictly increasing within a run.
- **INV-SNK-04**: Envelope fields (`s`, `t`, `act`, `st`) SHALL appear exactly once per `StepRecord`; `run_id` SHALL appear only in `RUN_START` and `RUN_END`, never on step records.
- **INV-SNK-05**: A field whose value equals its documented default (boost `0`, boolean `false`, empty `llm` list) SHALL be omitted; absence = default. Fields whose *absence is itself information* are exempt and SHALL be emitted explicitly whenever defined: `dec.patched` (tri-state: absent = no resolved target; `0` and `1` both emitted) and `dec.cf` (present exactly on the four MOP-sensitive pick channels).
- **INV-SNK-06**: Every dictionary ID (`act`, `st`, `out.target`) referenced by a record SHALL have been defined by an `ACT`/`STATE` record on an earlier line of the same trace. IDs are run-local integers with no cross-run meaning.
- **INV-SNK-07** (substitute for the dissolved INV-ARCH-01): The sink SHALL be neutral — with the sink enabled or replaced by a no-op, the same seed and inputs SHALL produce the identical action sequence (R7). All `EventSink` methods return `void`; the sink SHALL NOT read or advance the seeded RNG and SHALL NOT expose state consumed by any decision path.
- **INV-SNK-08**: A `StepRecord` SHALL be closed and written when its outcome resolves during step N+1's graph update, under the same single-shot, reference-equality guard as the decision join buffer. A record whose outcome legitimately never resolves SHALL be closed **without** an `out` member; the teardown flush SHALL write a still-pending record with `out:{"resolved":false}`. Maximum loss on sudden process death is one `StepRecord` (plus `RUN_END`).
- **INV-SNK-09**: On every normal termination, `RUN_END` SHALL be the last sink record of the trace, carrying the termination reason and the run counters. It is write-only: no consumer validation, no task-status coupling, no retry logic exists or may be added on its account (owner decision D5).
- **INV-SNK-10**: When the heartbeat flag is on (default), exactly one `Log.i` heartbeat line SHALL be written per step, and the jar SHALL NOT read logcat under any configuration.
- **INV-SNK-11**: Every sink record line SHALL begin with `{`, and the sink SHALL write directly to `System.out`, never through `Logger` (whose lines all begin with `[APE] `). A trace line is a sink record if and only if it begins with `{`.
- **INV-SNK-12**: A `Throwable` raised inside any sink method SHALL NOT propagate to the caller; after the first such failure the sink SHALL stop emitting for the remainder of the run.
- **INV-SNK-13**: The sink's per-step cost SHALL NOT reduce step throughput below the pre-change jar's, measured as steps per minute on the same APK, seed and wall-clock budget, with the comparison's own noise established by repeating the pre-change run. This covers the whole per-step write path as shipped — the stdout record, the logcat heartbeat, prompt-dump escaping — not the stdout write alone. It is a measured gate on this stage, not a design argument.
- **INV-SNK-14**: The heartbeat's logcat tag SHALL be present in the capture-side tag allowlist of every consumer expected to read it. A heartbeat written under a tag the harness filters out is inert, and no analysis-side simplification (in particular deleting `clock_logcat_join.py`'s offset reconstruction) SHALL land before a captured run demonstrates the lines are present.

## ADDED Requirements

### Requirement: StepRecord Schema

The sink SHALL emit one `StepRecord` per exploration step as a single-line JSON object with the following members. Field presence follows INV-SNK-05 (defaults omitted; tri-state exemptions explicit).

**Envelope** (once per record): `s` (step counter), `t` (milliseconds since `RUN_START`; epoch time = `RUN_START.t0 + t`), `act` (integer `ACT` dictionary ID of the current activity), `st` (integer `STATE` dictionary ID of the current abstract state).

**`dec`** (decision): `a` (the full action string, JSON-escaped), `src` (decision source, supplied by the selecting pipeline stage), `ch` (pick channel label). Model-action records additionally carry `pri` (priority) and the non-zero per-mechanism boosts `mop`, `mopf`, `wtg`, `cov`, `menu`, `form`; `wtgsrc` (∈ `wtg` | `frontier` | `both`, present exactly when `wtg` is non-zero); `mopx` (`[boosted, total]`, the MOP exposure pair, present exactly when the MOP widget pass is constructed); `patched` (0/1, present exactly when the action has a resolved target node); `cf` (present exactly on the four MOP-sensitive pick channels: `{"changed":0}` when the counterfactual pick equals the factual pick or recomputation failed, `{"changed":1,"a":"<action>"}` when it diverges). Non-model records carry only `a`/`src`/`ch`, except component-trigger records, which additionally carry `comp` (below).

**`dec.wtgsrc` — de-aliasing the `wtg` field.** `wtg` is a sum of two producers: the WTG pass writes it and the frontier pass read-modify-writes on top of it, so the emitted value cannot be attributed to either. Today the ambiguity is not theoretical — both weights are configured at 200 in the decisive campaign, so `wtg=200` is ambiguous across 10,231 steps and only the 91 steps realising 400 prove both fired. Each write site knows its own identity at compile time, so `wtgsrc` is set where the boost is written and requires no accessor on the scoring context and no threading through the pipeline. **This is a telemetry-only split: the decision SHALL keep summing the same number into the same field.** Note that the parity oracle cannot guard this — its entry point is below `adjustActionsByGUITree()`, and its own spec forbids citing it for scoring weights — so the gate this field faces is INV-SNK-07's shape argument (the boost fields are telemetry-only and never enter `getPriority()`) plus INV-SNK-13's measurement, not the goldens.

**`dec.mopx` — the exposure denominator, which is otherwise lost in this change.** The jar emits `[APE-RV] MOP boost: state=…, boosted=X/Y, …` once per step today, 1:1 with `[APE-STEP]`, and `X/Y` is the only record of how many actions on the screen were even *eligible* for MOP steering. Without it, "the MOP scorer fired on 0.4 % of decisions" has no denominator and cannot be separated into "the mechanism had no opportunity" and "the mechanism had one and lost the roulette" — which is the compliance question the whole guidance contrast rests on. The line is **not** among the four families this change's no-information-loss clause covers, and its sibling `[APE-RV] Frontier boost: … boosted=<n>/<total>` **is** normative in the wtg-navigation capability, so leaving it unstated would retire the MOP denominator while preserving the frontier one. It rides the step record rather than the `STATE` dictionary entry: the pair was measured across 25 campaign traces and is **not** constant per abstract state (14 of the 25 show a state with more than one realised pair), so the dictionary encoding that would have made it free is not available. It therefore costs bytes on every guided-arm step and is subject to INV-SNK-13 like any other per-step field.

**`dec.comp` — whether the launch landed.** Component-trigger records SHALL carry `comp` with the dispatch outcome: `{"r":<int>}`, the result the platform returns for the launch, and `{"r":<int>,"e":"<enum>"}` when the dispatch failed. The jar already binds a boolean at the dispatch site and already logs a warning on false; the finer result code is discarded one frame below, where `IActivityManager.startActivity`'s `START_*` return value is dropped. Capturing it introduces no retry, no re-dispatch, no extra IPC and no mutation of the round-robin cursor — it records a value the launch already produced. Ordering is not an obstacle in this change even though it was in the retired line family: the step record is closed at step N+1, so the dispatch result exists before the record is written, whereas `[APE-STEP]` was emitted before dispatch. This guards the campaign's strongest mechanism result — roughly 1,075 direct component launches in the guided arm against 0 in the control — for which a refused intent and an accepted one are currently the same trace evidence.

**`llm`** (optional ordered array): one entry per LLM routing attempt made during the step's selection, in occurrence order — completed calls (`call`, `mode`, `tool`, `qwen:[x,y]`, `px:[x,y]`, `result` ∈ `matched|llm_tap|no_match`, optional `reason` ∈ `dead_pair|degenerate|boundary`, optional `repair` (repair-form provenance), `mcls`, `ncls`, `ndist`, `widgets`, `tok:[in,out]`, `ms`, optional `text`, and — when the prompt-dump flag is on (default) — `sys`/`user`/`resp`/`tool_calls`); abandoned attempts (`result:"error"` with `cause` and `detail`); and the once-per-open-episode breaker decline (`result:"breaker_open"` with `trips`). A selection retry within the same step appends to the same array.

**`out`** (outcome, attached at step N+1): `new_state` (omitted when false), `target` (STATE ID of the resulting state), `act_changed` (omitted when false). `activity_has_mop` for both the step's and the outcome's activity is carried by the referenced `ACT` dictionary entries, not repeated per record. A record closed without a resolved outcome has no `out` member; a record flushed at teardown has `out:{"resolved":false}`.

Every field of the retired `[APE-STEP]` (both forms), `[APE-OUTCOME]`, and `[APE-LLM-TEL]`/`[APE-LLM-ERROR]` lines SHALL be either present in this schema or derivable by dictionary lookup within the same trace (the design's mapping table is normative for the correspondence).

**The scope of that clause is four line families, and other emissions fall outside it.** They are named here so that "additive only" is not read as a guarantee it does not make. `[APE-MOP-DATA]` and `[APE-ARCH]` are outside the clause — which is why their replacements are specified field-by-field in the run-level requirement rather than inherited, and why the census would otherwise have been lost by omission rather than by decision. `[APE-RV] MOP boost` is outside it too, and is the one emission the clause's absence would have retired silently: it carries the exposure denominator, its sibling `[APE-RV] Frontier boost` *is* normative in the wtg-navigation capability, and it is preserved here deliberately as `dec.mopx` above. (`[APE-LLM-CONFIG-ACK]` and `[APE-LLM-RESPONSE]` are also outside the clause but are not at risk: the former is specified as the `LLM_ACK` record and the latter is mapped onto the `resp`/`tool_calls` fields by the design's mapping table.)

#### Scenario: Model-action step with MOP boost and outcome

- **WHEN** step 42 selects a `MODEL_CLICK` via the greedy roulette with `mopBoost=500`, `menuBoost=250`, all other boosts 0, and the executed action produces a transition to a new state at step 43
- **THEN** exactly one record with `s:42` SHALL be written when step 43's graph update resolves the outcome
- **AND** its `dec` SHALL carry `src`, `ch:"roulette_greedy"`, `pri`, `mop:500`, `menu:250` and SHALL NOT carry `wtg`, `cov`, `form`, or `mopf`
- **AND** its `out` SHALL carry `new_state:true` and the target state's dictionary ID

#### Scenario: Non-model action record

- **WHEN** the stagnation launcher fires an `EVENT_TRIGGER_ACTIVITY` at step 77 and the platform accepts the intent
- **THEN** the `s:77` record's `dec` SHALL carry the action string, `src:"Component"`, `ch:"launcher"` and `comp:{"r":0}`, with no `pri` and no boost fields
- **AND** the record SHALL be closed without an `out` member (non-model actions produce no transition under their own identity)
- **AND** whether the launched component actually reached the foreground SHALL be derivable within the same trace, without a new field, by comparing the launched class in `dec.a` against the next step record's `act` — the same two-hop discipline the schema already uses for the outcome-side MOP flag

#### Scenario: launch refused by the platform

- **WHEN** the launcher dispatches an intent for a component the system refuses
- **THEN** the record's `dec.comp` SHALL carry the non-success result code and its failure enum
- **AND** the record SHALL NOT differ from an accepted launch in any other field — a refused launch and an accepted one are distinguishable only here, and were indistinguishable in the retired line family

#### Scenario: Tri-state patched preserved

- **WHEN** step A selects a click on a patch-promoted node, step B a click on a natively clickable node, and step C a `MODEL_BACK` with no resolved target
- **THEN** record A SHALL carry `dec.patched:1`, record B SHALL carry `dec.patched:0`, and record C SHALL carry no `patched` member

#### Scenario: LLM call recorded as sub-event of its step

- **WHEN** at step 42 the new-state hook makes LLM call 3, which matches a widget after 973 ms with 1841/25 tokens
- **THEN** the `s:42` record's `llm` array SHALL contain one entry with `call:3`, `mode:"new_state"`, `result:"matched"`, `px`, `tok:[1841,25]`, `ms:973`
- **AND** no separate per-call record SHALL be written — call, decision, and outcome share one record with no join key

#### Scenario: Abandoned LLM attempt attributed by construction

- **WHEN** at step 55 the LLM HTTP call times out and selection falls back to SATA
- **THEN** the `s:55` record's `llm` array SHALL contain an entry with `result:"error"`, `cause:"timeout"`, and a detail message
- **AND** the record's `dec.src` SHALL reflect the SATA fallback that actually selected

### Requirement: StepRecord Lifecycle

The sink SHALL keep at most one pending `StepRecord`. `beginStep` opens it at selection start (so LLM sub-events preceding decision finalization land in it) and closes a still-pending predecessor without an `out` member. The outcome closure at step N+1 SHALL occur at the same point and under the same single-shot, reference-equality buffered-decision guard that governed the retired `[APE-OUTCOME]` emission. Teardown SHALL invoke `flushPendingStep` as an isolated teardown step, writing any pending record with `out:{"resolved":false}`.

#### Scenario: Outcome joins its step at N+1

- **WHEN** step 10's action executes and step 11's graph update records the transition with the buffered decision reference-equal to the current action
- **THEN** the `s:10` record SHALL be written exactly once, during step 11's update, with its `out` attached

#### Scenario: Legitimate outcome absence

- **WHEN** step 20's selected action is discarded by a restart before any transition is recorded, and step 21 begins
- **THEN** the `s:20` record SHALL be written without an `out` member — distinguishable from a flushed record (`out.resolved:false`) and from a resolved one

#### Scenario: In-flight step flushed at teardown

- **WHEN** the run's time budget expires while step 199's outcome is still unresolved
- **THEN** `flushPendingStep` SHALL write the `s:199` record with `out:{"resolved":false}` before `RUN_END` is emitted

### Requirement: JSON Serializer With Escaping By Construction

The sink SHALL serialize records with a self-contained serializer (no runtime dependency) that satisfies INV-SNK-01/02, writing into a reused buffer (~1 allocation-free write per step at steady state). `org.json` (or equivalent) MAY be used in tests as the round-trip parser, never in the emission path.

**This requirement is not precautionary — the failure it prevents is present in the corpus it will replace.** In the decisive campaign, 74 `[APE-STEP]` lines across 18 runs are physically split over two file lines because a widget's `text=` attribute contains a raw newline, concentrated in two applications (22 such lines in one run of `net.pfiers.osmfocus`, 31 across six runs of `com.rastislavkish.vscan`); two further lines lose `cf_changed` the same way. The rate is 0.013 % of 576,739 steps, which is small, and what it silently drops from those steps is `decision_source` — the single field an attribution analysis reads. Escaping at the origin, rather than flattening the source data as the current fix does, is what makes the rate structurally zero instead of small.

#### Scenario: Hostile widget text round-trips

- **WHEN** an action string contains a newline, a double quote, a backslash, a NUL, and non-ASCII text (e.g. `"Salvar\n\"opção\"\u0000\\fim"`)
- **THEN** the emitted record SHALL remain one line, SHALL parse as valid JSON, and the parsed value SHALL equal the original string

#### Scenario: One-line invariant is a permanent test

- **WHEN** the serializer test suite runs
- **THEN** it SHALL assert, for adversarial inputs including control characters and U+2028/U+2029, that no emitted record contains a raw newline

### Requirement: Dictionary Events and Run-Local IDs

On first sight of an activity or abstract state, the sink SHALL emit a dictionary record — `{"type":"ACT","id":N,"name":"<activity>","mop":0|1}` or `{"type":"STATE","id":N,"key":"<stateKey>","act":<actId>}` — on its own line before any record references the ID (INV-SNK-06). `mop` on the `ACT` entry records `activity_has_mop`; consumers derive the step-side value from `act` and the outcome-side value from `out.target → STATE.act → ACT.mop`.

#### Scenario: Definition precedes reference

- **WHEN** step 5 is the first visit to `com.foo/.SettingsActivity` in state `S9`
- **THEN** the trace SHALL contain the `ACT` and `STATE` records for them on lines earlier than the `s:5` record that references their IDs

#### Scenario: activity_has_mop recorded once

- **WHEN** an activity with MOP reachability is visited 200 times in a run
- **THEN** `mop:1` SHALL appear once, on its `ACT` entry, and on no step record

### Requirement: Run-Level Records

The sink SHALL carry the out-of-step records in the same NDJSON stream: `RUN_START` (emitted by the run-spec capability as the first record; carries `run_id`, the epoch base `t0`, and the effective plan — including the LLM parameters previously on the retired `[APE-LLM-CONFIG]` manifest), `MOP_DATA` (the load census, below), `PIPELINE` (assembly provenance, below), `LLM_ACK` (`server_model`, at most once, on the first successful chat response), and `RUN_END`.

**`MOP_DATA` — the load census, and the field that has been misread.** The record SHALL carry `status`, optional `reason` on rejection, and on success `package`, `windows`, `widgets`, `flagged`, `droppedNoId`, `wtgEdges`, `handlersUnmatched`, `syntheticLambda`, `recovered`, `mopActivities`, `mopActsAugmented`. This is the set the retired `[APE-MOP-DATA]` line carried, with one deliberate substitution: the line's `transitions` field is **not** restored, and `wtgEdges` replaces it.

That substitution is the point of the requirement, so it is stated rather than left to the field list. The three frontier passes gate on `MopData.hasWtgData()`, which is `!wtgTransitions.isEmpty()` — a **click-only** convenience view, keyed by base activity, populated only for transitions carrying a `"click"` event whose source and target windows both resolve with non-null names. What the retired line logged as `transitions=N` is `transitions.size()`, the **flat** list — a different structure, and not the one the gate reads. The consequence is measured, not hypothesised: over the decisive campaign's 40 applications, 11 have zero transitions, **14 have transitions but no click event at all** (reporting `transitions` between 9 and 29 while the whole frontier family is disabled), and 15 have at least one click edge. Two applications from that campaign make the trap concrete — `info.metadude.android.datenspuren.schedule` reports `transitions=29` with `WtgPass`/`FrontierPass`/`MopFrontierPass` all absent, while `com.starry.greenstash` reports `transitions=24` with all three constructed. Restoring `transitions` would restore the misreading; `wtgEdges` is the number the gate actually reads, and it is emitted under the same name the stage-7 artifact already uses, so the field does not change identity across the window.

A separate boolean `has_wtg_data` SHALL NOT be emitted: a per-activity edge list is created only at the moment an edge is added to it, so `hasWtgData()` is exactly `wtgEdges > 0` and a second field would be a second place the same truth is written.

**`PIPELINE` — assembly provenance including what was *not* assembled.** The record SHALL carry `passes` (the enabled scoring passes, in pipeline order — unchanged, and still exactly what the `[APE-ARCH]` line reports) and, beside it, `candidates`: every candidate pass with `{name, enabled}`. The stage side SHALL be a static declaration of candidate stage names, not constructed objects — a feature absent from the plan has no stage (decision-pipeline INV-DP-03), and the census SHALL NOT reintroduce a disabled-stage object to enumerate.

A `reason` field on the pass entries SHALL NOT be emitted. The three gates are `mopData != null && mopData.hasWtgData() && <weight>`, and every conjunct is already recoverable from the same trace: `MOP_DATA.status` gives the first, `MOP_DATA.wtgEdges` the second, `RUN_START.params` the third. A reason field would encode the order in which each constructor happens to evaluate its conjuncts — and that order is not uniform across the three passes — rather than the cause.

The census is what makes the pass list readable as a data-dependent outcome instead of a configuration echo. In the decisive campaign the `[APE-ARCH] passes=` line takes exactly three values across all 360 runs, and the split is 45/75 in every arm: **the WTG/frontier family is never constructed in 25 of the 40 applications — 62.5 % of the corpus** — with nothing in the trace saying so except the absence of three names from a list no analyst was reading as an outcome. Without `candidates`, "the arm turned this off" and "this application's data could not support it" remain indistinguishable.

#### Scenario: MOP data load recorded

- **WHEN** the static-analysis JSON loads successfully with 12 windows and 340 widgets
- **THEN** the trace SHALL contain `{"type":"MOP_DATA","status":"loaded",...,"windows":12,"widgets":340}` as its own line

#### Scenario: transitions present, click edges absent

- **WHEN** an application's static JSON yields 29 transitions, none of which carries a `"click"` event
- **THEN** the `MOP_DATA` record SHALL carry `wtgEdges:0` and SHALL carry no `transitions` field
- **AND** the `PIPELINE` record's `candidates` SHALL list `WtgPass`, `FrontierPass` and `MopFrontierPass` with `enabled:false`, while `passes` lists only the four that were constructed

#### Scenario: a pass disabled by weight rather than by data

- **WHEN** the application has 7 click edges and the arm sets `ape.mopFrontierWeight=0`
- **THEN** `MOP_DATA.wtgEdges` SHALL be `7`, `PIPELINE.candidates` SHALL carry `MopFrontierPass` with `enabled:false`, and `RUN_START.params` SHALL carry the zero weight
- **AND** the three facts together SHALL identify the cause without any `reason` field on the record

#### Scenario: Server model acknowledged once

- **WHEN** the first successful LLM response reports model `qwen3-vl-8b` and 500 further calls succeed
- **THEN** exactly one `LLM_ACK` record with `server_model:"qwen3-vl-8b"` SHALL appear in the trace

### Requirement: RUN_END As the Natural Last Record

Teardown SHALL emit `{"type":"RUN_END","reason":...,"steps":N,"t_first_step":...,"t_last_step":...,"counters":{...}}` as its last sink action (after `flushPendingStep` and all other agent teardown steps), where `reason` ∈ `timeout` | `crash` | `unknown` and `counters` includes the LLM aggregate counters previously on the retired `[APE-RV] LLM Summary` line (`calls`, `tok_in`, `tok_out`, `ms`, `matched`, `llm_tap`, `no_match`, `dead_pair`, `repaired`, the seven failure-cause counters, `breaker_trips`) plus record/dictionary counts. Per owner decision D5 this record is write-only: nothing on the Python side reads or validates it, and its absence changes no task status.

**Why the two timestamps, and why not a consumed-budget field.** `t_first_step` and `t_last_step` are the `t` of the first and last step records, on the same clock as every step. Their span against the run's budget is what separates a run that explored for its whole budget from one that was alive and idle — and this corpus contains the second kind. In the decisive campaign one run is recorded `COMPLETED` with 1,871 s of execution time and emitted **150 step records spanning 446 s**: roughly 1,400 s of its budget produced no exploration at all. A `budget_consumed_ms` field would have reported ~1,800 s for that run and flagged nothing, which is why it is not specified here; the same value is in any case derivable from `t_last_step`, and the harness already records wall-clock execution time per attempt host-side. Eight runs of the campaign have a step span under 1,700 s, seven of them concentrated in two applications — a pattern only these two integers make visible at run level.

The attempt ordinal is deliberately **not** a field. The jar does not know it is a retry, and the harness that does know already writes one task record per attempt, with state, both timestamps and the error message. Round-tripping that value through a plan key into a record the harness then reads back would be indirection with one subscriber. This record stays write-only (INV-SNK-09): these fields make a pre-consolidation liveness check cheap to write on the analysis side; they do not implement one, and no task status may come to depend on them.

#### Scenario: Normal timeout termination

- **WHEN** the run ends by time budget (`StopTestingException`)
- **THEN** the last sink record of the trace SHALL be `RUN_END` with `reason:"timeout"` and the counters
- **AND** the LLM decision ratio SHALL be derivable from the counters (not stored)

#### Scenario: SIGKILL loses RUN_END without consequence

- **WHEN** the harness SIGKILLs the process before teardown runs
- **THEN** the trace legitimately ends without `RUN_END` (and with at most one lost `StepRecord`)
- **AND** no Python-side mechanism SHALL treat this as an error (post-hoc timestamp analysis remains the detection path, D5)

### Requirement: Logcat Heartbeat

When `TelemetryParams.heartbeat` is on (property `ape.telemetryHeartbeat`, default `true`), the sink SHALL write one logcat line per step via `Log.i` — `s=<step> t=<tRelMs>` with a fixed tag — at the same point the step envelope is captured. The heartbeat is write-only (INV-SNK-10): it exists so violations and steps share one file and clock for the analysis-side join; the jar never reads logcat, and no runtime APE↔logcat coupling exists.

**The tag is not a free choice, and the capture side must be changed with it.** rv-platform does not dump the ring buffer after the run; it streams `adb logcat -v <format> -s RVSEC:V RVSEC-COV:V` into the task's logcat file for the run's duration, a strict tag allowlist (`LogcatManager.default_tags`; the flag-off command is pinned byte-identical by that repo's INV-PLT-21). A heartbeat under any other tag is filtered out at the device and **never reaches the file the join reads** — the mechanism would be silently inert, exactly the failure mode this change exists to end elsewhere. The heartbeat's tag SHALL therefore be declared here and added to the capture allowlist by the counterpart change in `rvsec` before the heartbeat is credited with anything; until then `clock_logcat_join.py` keeps needing the UTC-offset reconstruction that this change deletes on the strength of the heartbeat.

**The tag is `ApeRvHb`, and it is now a settled cross-repository constant rather than a deferred choice.** The counterpart change landed it first: `rvsec` declares `TAG_APERV_HEARTBEAT = "ApeRvHb"` in one place and appends it to `LogcatManager.default_tags` after the two verification tags, and `clock_logcat_join.py` already matches the heartbeat line against that tag. The jar SHALL emit under this exact literal — it is 7 characters, comfortably inside the capture side's 23-character bound — and SHALL declare it in a single constant whose comment names the counterpart declaration site, because the two literals have to be compared by a human and the failure of a mismatch is silent on both sides: the device drops the lines, the capture file looks ordinary, and the join degrades to the fallback it was supposed to retire.

Two facts about the capture side, verified against the campaign, bound how that must be done. First, the two-tag command above is the *baseline*: with the harness's diagnostics flag on — as the decisive campaign ran it — the allowlist is six entries (`RVSEC:V RVSEC-COV:V AndroidRuntime:E art:E dalvikvm:E ActivityManager:W`), confirmed by the tags actually present in a captured file. The allowlist is strict either way; the point is that the command this requirement must be checked against is whichever one the run was launched with, not the default. Second, the capture side validates tag length at 23 characters, so the declared tag SHALL fit that bound. The tag is named above, and the reason it is stated in the requirement rather than left to implementation is that a wrong choice fails silently rather than loudly.

Because the capture is a live stream with the buffer cleared at start, ring-buffer eviction is not the risk; volume still is, in two forms worth stating: the heartbeat competes for the shared main buffer with everything else the device logs, and Android's "chatty" throttling drops lines from high-rate tags. The step rates to size it against are the measured ones at the budget that matters. At 300 s the median is 209 steps and the maximum 446; **the linear extrapolation of those figures to 1800 s does not hold** — measured over the decisive campaign's 360 runs at 1800 s, the median is 1,603 steps overall and 1,912 / 1,954 / 1,095 in the algorithmic, control and LLM arms respectively, with a range of 150–3,158. So the per-run heartbeat is on the order of 10⁻¹ MB at ~100 bytes per line, but the non-LLM arms sit roughly a third above the extrapolated band and the LLM arm below it. That is small in absolute terms and large relative to a default 256 KB buffer, which is why the measurement is required rather than assumed (see the per-step cost requirement).

#### Scenario: heartbeat reaches the captured file

- **WHEN** a run executes with the heartbeat on and rv-platform captures logcat with its tag allowlist
- **THEN** the heartbeat lines SHALL be present in the task's logcat file
- **AND** if the heartbeat's tag is absent from the allowlist, the join SHALL fail loudly at analysis time rather than silently producing an unjoined result

#### Scenario: Heartbeat on by default

- **WHEN** a run executes 150 steps with the flag at its default
- **THEN** logcat SHALL contain 150 heartbeat lines whose `s`/`t` values match the trace's step records

#### Scenario: Heartbeat disabled

- **WHEN** `ape.telemetryHeartbeat=false`
- **THEN** no heartbeat lines SHALL be written and the trace records SHALL be byte-identical to a heartbeat-on run with the same seed

### Requirement: Telemetry Neutrality

Telemetry SHALL be always-on and identical for every experimental arm — no arm-level flag disables or alters it (this deliberately dissolves INV-ARCH-01; the recorded substitute is INV-SNK-07). The no-op sink exists solely for the neutrality test, which SHALL be a permanent gate: the parity harness run with the real sink and the no-op sink under the same seed SHALL produce identical action sequences.

#### Scenario: Sink on/off equivalence (R7)

- **WHEN** the parity harness replays a preset under seed 1234 with `NdjsonSink`, then with `NoopSink`
- **THEN** the two action sequences SHALL be identical

#### Scenario: All arms carry the same telemetry

- **WHEN** the minimal control preset (`aperv`) and the full `llm_mop` preset each run
- **THEN** both traces SHALL contain one `StepRecord` per step with the same schema — the control arm is no longer blind

### Requirement: Volume Rules

The sink SHALL apply the four volume levers of owner decision D2: envelope once per step (never per sub-event); defaults omitted per INV-SNK-05; dictionary IDs for activity/state strings; `run_id` only in border records. The per-step cost SHALL NOT exceed the retired line family's (~1 stdout write per step; reused accumulator) — a requirement about **wall-clock throughput**, since the run budget is wall-clock (`--running-minutes`) and this change's own proposal prices a lost step at 0.037–0.052 pp `cov_mop`.

That clause SHALL be discharged by measurement, not by argument, because the argument available is incomplete: it counts stdout writes and this change also adds a per-step logcat write, keeps prompt dumps on by default while giving them per-character escaping the `key=value` format never performed, and adds a full-trace gzip on the collection path including the timeout path. Whether those net out below the retired family is an empirical question. Conformance is the measurement of INV-SNK-13, and it is a stage gate: a throughput regression beyond the measured noise blocks the stage rather than being accepted as a cost of better telemetry.

**Two cautions on how the exchange rate is used, so it is not quoted as harder than it is.** The 0.037–0.052 pp `cov_mop` per lost step is an in-sample band from the calibration report, which flags it as having extreme leverage and gives 0.0292 out of sample, with throughput explaining roughly 69 % of the variation. It is the right order of magnitude for deciding whether a per-step field is worth its bytes; it is not a conversion constant, and it prices *steps*, not bytes — the marginal cost per byte of the shipped write path is exactly what INV-SNK-13's measurement establishes and nothing else in this change asserts. The three per-step fields this change adds beyond the retired family (`wtgsrc`, `mopx`, `comp`) are therefore gated on that measurement together with everything else, and `mopx` — the only one that is present on essentially every guided-arm step rather than on a sparse subset — is the one to drop first if the gate binds.

#### Scenario: step throughput does not regress

- **WHEN** one arm runs the same APK, seed and wall-clock budget twice on the pre-change jar and once on the post-change jar
- **THEN** the two pre-change runs SHALL establish the measurement's own variation
- **AND** the post-change steps-per-minute SHALL NOT fall below the pre-change mean by more than that variation
- **AND** a larger drop SHALL block the stage, with the measured numbers recorded either way

#### Scenario: Defaults omitted

- **WHEN** a step's action carries zero for all six boost fields and its outcome is not a new state
- **THEN** the record SHALL contain none of the six boost keys, no `new_state`, and no `act_changed`

#### Scenario: Envelope not repeated across sub-events

- **WHEN** a step performs three LLM attempts
- **THEN** `s`, `t`, `act`, `st` SHALL each appear once in the record, and no `llm` entry SHALL carry a step or activity field
