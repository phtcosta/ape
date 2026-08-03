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

**`dec`** (decision): `a` (the full action string, JSON-escaped), `src` (decision source, supplied by the selecting pipeline stage), `ch` (pick channel label). Model-action records additionally carry `pri` (priority) and the non-zero per-mechanism boosts `mop`, `mopf`, `wtg`, `cov`, `menu`, `form`; `patched` (0/1, present exactly when the action has a resolved target node); `cf` (present exactly on the four MOP-sensitive pick channels: `{"changed":0}` when the counterfactual pick equals the factual pick or recomputation failed, `{"changed":1,"a":"<action>"}` when it diverges). Non-model records carry only `a`/`src`/`ch`.

**`llm`** (optional ordered array): one entry per LLM routing attempt made during the step's selection, in occurrence order — completed calls (`call`, `mode`, `tool`, `qwen:[x,y]`, `px:[x,y]`, `result` ∈ `matched|llm_tap|no_match`, optional `reason` ∈ `dead_pair|degenerate|boundary`, optional `repair` (repair-form provenance), `mcls`, `ncls`, `ndist`, `widgets`, `tok:[in,out]`, `ms`, optional `text`, and — when the prompt-dump flag is on (default) — `sys`/`user`/`resp`/`tool_calls`); abandoned attempts (`result:"error"` with `cause` and `detail`); and the once-per-open-episode breaker decline (`result:"breaker_open"` with `trips`). A selection retry within the same step appends to the same array.

**`out`** (outcome, attached at step N+1): `new_state` (omitted when false), `target` (STATE ID of the resulting state), `act_changed` (omitted when false). `activity_has_mop` for both the step's and the outcome's activity is carried by the referenced `ACT` dictionary entries, not repeated per record. A record closed without a resolved outcome has no `out` member; a record flushed at teardown has `out:{"resolved":false}`.

Every field of the retired `[APE-STEP]` (both forms), `[APE-OUTCOME]`, and `[APE-LLM-TEL]`/`[APE-LLM-ERROR]` lines SHALL be either present in this schema or derivable by dictionary lookup within the same trace (the design's mapping table is normative for the correspondence).

#### Scenario: Model-action step with MOP boost and outcome

- **WHEN** step 42 selects a `MODEL_CLICK` via the greedy roulette with `mopBoost=500`, `menuBoost=250`, all other boosts 0, and the executed action produces a transition to a new state at step 43
- **THEN** exactly one record with `s:42` SHALL be written when step 43's graph update resolves the outcome
- **AND** its `dec` SHALL carry `src`, `ch:"roulette_greedy"`, `pri`, `mop:500`, `menu:250` and SHALL NOT carry `wtg`, `cov`, `form`, or `mopf`
- **AND** its `out` SHALL carry `new_state:true` and the target state's dictionary ID

#### Scenario: Non-model action record

- **WHEN** the stagnation launcher fires an `EVENT_TRIGGER_ACTIVITY` at step 77
- **THEN** the `s:77` record's `dec` SHALL carry the action string, `src:"Component"`, and `ch:"launcher"`, with no `pri` and no boost fields
- **AND** the record SHALL be closed without an `out` member (non-model actions produce no transition under their own identity)

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

#### Scenario: Hostile widget text round-trips

- **WHEN** an action string contains a newline, a double quote, a backslash, a NUL, and non-ASCII text (e.g. `"Salvar\n\"opção\" \\fim"`)
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

The sink SHALL carry the out-of-step records in the same NDJSON stream: `RUN_START` (emitted by the run-spec capability as the first record; carries `run_id`, the epoch base `t0`, and the effective plan — including the LLM parameters previously on the retired `[APE-LLM-CONFIG]` manifest), `MOP_DATA` (load status: `status`, optional `reason`, and on success `package`/`windows`/`widgets`), `PIPELINE` (assembly provenance: stage and pass names in order), `LLM_ACK` (`server_model`, at most once, on the first successful chat response), and `RUN_END`.

#### Scenario: MOP data load recorded

- **WHEN** the static-analysis JSON loads successfully with 12 windows and 340 widgets
- **THEN** the trace SHALL contain `{"type":"MOP_DATA","status":"loaded",...,"windows":12,"widgets":340}` as its own line

#### Scenario: Server model acknowledged once

- **WHEN** the first successful LLM response reports model `qwen3-vl-8b` and 500 further calls succeed
- **THEN** exactly one `LLM_ACK` record with `server_model:"qwen3-vl-8b"` SHALL appear in the trace

### Requirement: RUN_END As the Natural Last Record

Teardown SHALL emit `{"type":"RUN_END","reason":...,"steps":N,"counters":{...}}` as its last sink action (after `flushPendingStep` and all other agent teardown steps), where `reason` ∈ `timeout` | `crash` | `unknown` and `counters` includes the LLM aggregate counters previously on the retired `[APE-RV] LLM Summary` line (`calls`, `tok_in`, `tok_out`, `ms`, `matched`, `llm_tap`, `no_match`, `dead_pair`, `repaired`, the seven failure-cause counters, `breaker_trips`) plus record/dictionary counts. Per owner decision D5 this record is write-only: nothing on the Python side reads or validates it, and its absence changes no task status.

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

Because the capture is a live stream with the buffer cleared at start, ring-buffer eviction is not the risk; volume still is, in two forms worth stating: the heartbeat competes for the shared main buffer with everything else the device logs, and Android's "chatty" throttling drops lines from high-rate tags. At the measured step rates — median 209 steps per 300 s task, 446 at the maximum, so roughly 1,250–2,700 steps in an 1800 s campaign run — the heartbeat is on the order of 10⁻¹ MB per run at ~100 bytes per line. That is small in absolute terms and large relative to a default 256 KB buffer, which is why the measurement is required rather than assumed (see the per-step cost requirement).

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
