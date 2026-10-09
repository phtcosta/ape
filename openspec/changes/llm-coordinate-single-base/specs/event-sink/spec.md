# Delta: Event Sink (llm-coordinate-single-base)

## Purpose

Part B of `llm-coordinate-single-base` changes what the MOP guidance reads and how it weighs it: a node's handler stamp is looked up before its resource-id key (`mop-guidance` "Handler Stamp Lookup", design D16–D17), a new scoring mode `ape.mopScoring=distance` weighs an action by its nearest unretired target (D18), and targets retire after `ape.mopRetireAfter` first interactions (D19). None of that is visible in today's trace. A step record carries the boost (`dec.mop`), but not the distance behind it, nor whether the lookup came from the stamp or from the id key, and nothing records a retirement. The run-level records do not say how many targets and handler classes the artifact carried or whether the APK was stamped at all. Without those, the first run of the `distance` arm could not tell a guide that worked from a guide that never had a stamp to read, and the open risks the design names (Compose coverage, boundary targets weighing as direct ones) could not be measured.

This delta adds three step-record fields and six run-level counts (design D23):

- `dec.mopd`, the distance behind the chosen action's MOP weight under `distance`; `dec.mops`, the lookup's source; `dec.ret`, the targets the step's action retired. Each is present only when it has something to say, so steps whose chosen action has no MOP entry pay no bytes.
- `MOP_DATA.targets`, `MOP_DATA.handlers` and `MOP_DATA.droppedPairs`, beside the `formatVersion` the record already carries.
- `RUN_END.counters.stampNodes`, `stampHits` and `retired`.

The `ACT` dictionary bit `mop` (`NdjsonSink:540-551`) keeps reading `activityHasMop`, which keeps its format-1 meaning apart from the generator's source-3 constructor exclusion (`static-analysis-entrypoints`). The rewritten `MOP_DATA` field list also records the three fields the sink already writes and the main spec does not list (`formatVersion`, `sourceDigest`, `components`; `NdjsonSink.java:369-404`).

## Invariants

- **INV-SNK-05** (amended; this text replaces the rule's text at archive, task 9.2. The main `event-sink` spec cites INV-SNK-05 without stating it; its text lives in the archived `2026-08-07-rearch-04-step-ndjson-telemetry` delta, so the archive adds this text to the main spec's `## Invariants`): A field whose value equals its documented default (boost `0`, boolean `false`, empty `llm` list, empty `dec.ret`) SHALL be omitted; absence = default. Fields whose *absence is itself information* are exempt and SHALL be emitted explicitly whenever defined: `dec.patched` (tri-state: absent = no resolved target; `0` and `1` both emitted), `dec.cf` (present exactly on the four MOP-sensitive pick channels), and `dec.mopd` (absent = the chosen action has no MOP weight under `ape.mopScoring=distance`; `0` is emitted, because it means the action's handler is itself a target).

## MODIFIED Requirements

### Requirement: StepRecord Schema

The sink SHALL emit one `StepRecord` per exploration step as a single-line JSON object with the following members. Field presence follows INV-SNK-05 (defaults omitted; tri-state exemptions explicit).

**Envelope** (once per record): `s` (step counter), `t` (milliseconds since `RUN_START`; epoch time = `RUN_START.t0 + t`), `act` (integer `ACT` dictionary ID of the current activity), `st` (integer `STATE` dictionary ID of the current abstract state).

**`dec`** (decision): `a` (the full action string, JSON-escaped), `src` (decision source, supplied by the selecting pipeline stage), `ch` (pick channel label). Model-action records additionally carry `pri` (priority) and the non-zero per-mechanism boosts `mop`, `mopf`, `wtg`, `cov`, `menu`, `form`; `wtgsrc` (∈ `wtg` | `frontier` | `both`, present exactly when `wtg` is non-zero); `mopx` (`[boosted, total]`, the MOP exposure pair, present exactly when the MOP widget pass is constructed); `mopd` and `mops` (below, the MOP lookup behind `mop`); `ret` (below, the targets the step's action retired); `patched` (0/1, present exactly when the action has a resolved target node); `cf` (present exactly on the four MOP-sensitive pick channels: `{"changed":0}` when the counterfactual pick equals the factual pick or recomputation failed, `{"changed":1,"a":"<action>"}` when it diverges). Non-model records carry only `a`/`src`/`ch`, except component-trigger records, which additionally carry `comp` (below).

**`dec.wtgsrc` — de-aliasing the `wtg` field.** `wtg` is a sum of two producers: the WTG pass writes it and the frontier pass read-modify-writes on top of it, so the emitted value cannot be attributed to either. Today the ambiguity is not theoretical — both weights are configured at 200 in the decisive campaign, so `wtg=200` is ambiguous across 10,231 steps and only the 91 steps realising 400 prove both fired. Each write site knows its own identity at compile time, so `wtgsrc` is set where the boost is written and requires no accessor on the scoring context and no threading through the pipeline. **This is a telemetry-only split: the decision SHALL keep summing the same number into the same field.** Note that the parity oracle cannot guard this — its entry point is below `adjustActionsByGUITree()`, and its own spec forbids citing it for scoring weights — so the gate this field faces is INV-SNK-07's shape argument (the boost fields are telemetry-only and never enter `getPriority()`) plus INV-SNK-13's measurement, not the goldens.

**`dec.mopx` — the exposure denominator, which is otherwise lost in this change.** The jar emits `[APE-RV] MOP boost: state=…, boosted=X/Y, …` once per step today, 1:1 with `[APE-STEP]`, and `X/Y` is the only record of how many actions on the screen were even *eligible* for MOP steering. Without it, "the MOP scorer fired on 0.4 % of decisions" has no denominator and cannot be separated into "the mechanism had no opportunity" and "the mechanism had one and lost the roulette" — which is the compliance question the whole guidance contrast rests on. The line is **not** among the four families this change's no-information-loss clause covers, and its sibling `[APE-RV] Frontier boost: … boosted=<n>/<total>` **is** normative in the wtg-navigation capability, so leaving it unstated would retire the MOP denominator while preserving the frontier one. It rides the step record rather than the `STATE` dictionary entry: the pair was measured across 25 campaign traces and is **not** constant per abstract state (14 of the 25 show a state with more than one realised pair), so the dictionary encoding that would have made it free is not available. It therefore costs bytes on every guided-arm step and is subject to INV-SNK-13 like any other per-step field.

**`dec.mopd` and `dec.mops` — what the MOP weight rests on** (`llm-coordinate-single-base`, design D23). Both describe the `mop-guidance` lookup (`MopData.lookup`, "Handler Stamp Lookup") that the scoring pass kept for the chosen action — the best of the lookups over the action's containment nodes:

- `mopd` (int): under `ape.mopScoring=distance`, the distance `d` of the nearest unretired target that gave the action its MOP weight, a value in `0..3` (a target at `d ≥ 4` gives no weight). It SHALL be present exactly when the action's MOP weight before the tie split (`mop-guidance` "MopScorer — Priority Boost", the undivided weight the marker and retirement also read) is greater than 0, which is when the record carries `mop` except in the degenerate case of a tie split that rounds to 0 (author, 2026-10-09), and SHALL be emitted when its value is `0` (INV-SNK-05 exemption: `mopd:0` says the handler is itself a target; an absent `mopd` says there is no distance-based weight). Under `ape.mopScoring=flag` it SHALL never be present.
- `mops` (string): `"s"` when the lookup was answered by the node's handler stamp (source `STAMP`), `"i"` when by the resource-id key (source `ID`); absent when the source is `NONE` and on actions with no resolved target. It SHALL be written whenever the chosen action's lookup has source `STAMP` or `ID`, in both scoring modes and whether or not the action carries a weight: a stamp listed with no target (`mop` absent, `mops:"s"`) is the stamp overriding a flagged id key, which is the case the lookup exists for. The lookup recorded is the weight's lookup, the one `MopScorer.weigh` gave the action its weight from; when no containment lookup gives a weight under the active mode (under `distance` a lookup with only a flag, or only retired pairs, gives none), the source recorded is that of the lookup on the action's own target node (author, 2026-10-09).

**`dec.comp` — whether the launch landed.** Component-trigger records SHALL carry `comp` with the dispatch outcome: `{"r":<int>}`, the result the platform returns for the launch, and `{"r":<int>,"e":"<enum>"}` when the dispatch failed. The jar already binds a boolean at the dispatch site and already logs a warning on false; the finer result code is discarded one frame below, where `IActivityManager.startActivity`'s `START_*` return value is dropped. Capturing it introduces no retry, no re-dispatch, no extra IPC and no mutation of the round-robin cursor — it records a value the launch already produced. Ordering is not an obstacle in this change even though it was in the retired line family: the step record is closed at step N+1, so the dispatch result exists before the record is written, whereas `[APE-STEP]` was emitted before dispatch. This guards the campaign's strongest mechanism result — roughly 1,075 direct component launches in the guided arm against 0 in the control — for which a refused intent and an accepted one are currently the same trace evidence.

**`llm`** (optional ordered array): one entry per LLM routing attempt made during the step's selection, in occurrence order — completed calls (`call`, `mode`, `tool`, `qwen:[x,y]`, `px:[x,y]`, `result` ∈ `matched|llm_tap|no_match`, optional `reason` ∈ `dead_pair|degenerate|boundary`, optional `repair` (repair-form provenance), `mcls`, `ncls`, `ndist`, `widgets`, `tok:[in,out]`, `ms`, optional `text`, and — when the prompt-dump flag is on (default) — `sys`/`user`/`resp`/`tool_calls`); abandoned attempts (`result:"error"` with `cause` and `detail`); and the once-per-open-episode breaker decline (`result:"breaker_open"` with `trips`). A selection retry within the same step appends to the same array.

**`out`** (outcome, attached at step N+1): `new_state` (omitted when false), `target` (STATE ID of the resulting state), `act_changed` (omitted when false). `activity_has_mop` for both the step's and the outcome's activity is carried by the referenced `ACT` dictionary entries, not repeated per record. A record closed without a resolved outcome has no `out` member; a record flushed at teardown has `out:{"resolved":false}`.

**`dec.ret` — targets this step retired** (design D23). Under `ape.mopScoring=distance`, the integer indices, into the artifact's target list (`MOP_DATA.targets`), of the targets whose retirement counter reached `ape.mopRetireAfter` as a consequence of this step's action — the first interaction of its widget in its activity, counted when the action is selected, in `StatefulAgent.moveForward` (`mop-guidance` "Target Retirement"). They are not known when the decision is recorded: `sink.decision(...)` runs inside `resolveNewAction` (`StatefulAgent.java:1581-1590`, reached from `:850`), before `moveForward` (`:854`). They SHALL reach the open record through their own sink entry, `EventSink.mopRetired(int[] indices)`, called from `moveForward` when the retirement call returns a non-empty array, after the pattern of `mopExposure` and `componentLaunch`, which also add a member to the step's `dec` after the decision call. `StepRecord` writes `dec` when the record closes, so `ret` sits in `dec` and survives a record closed without an `out` member. Each index appears once, in ascending order. The array SHALL be absent when it would be empty, which is every step under `ape.mopScoring=flag`. A target retires once, so an index appears in `dec.ret` at most once per run.

Every field of the retired `[APE-STEP]` (both forms), `[APE-OUTCOME]`, and `[APE-LLM-TEL]`/`[APE-LLM-ERROR]` lines SHALL be either present in this schema or derivable by dictionary lookup within the same trace (the design's mapping table is normative for the correspondence).

**The scope of that clause is four line families, and other emissions fall outside it.** They are named here so that "additive only" is not read as a guarantee it does not make. `[APE-MOP-DATA]` and `[APE-ARCH]` are outside the clause — which is why their replacements are specified field-by-field in the run-level requirement rather than inherited, and why the census would otherwise have been lost by omission rather than by decision. `[APE-RV] MOP boost` is outside it too, and is the one emission the clause's absence would have retired silently: it carries the exposure denominator, its sibling `[APE-RV] Frontier boost` *is* normative in the wtg-navigation capability, and it is preserved here deliberately as `dec.mopx` above. (`[APE-LLM-CONFIG-ACK]` and `[APE-LLM-RESPONSE]` are also outside the clause but are not at risk: the former is specified as the `LLM_ACK` record and the latter is mapped onto the `resp`/`tool_calls` fields by the design's mapping table.)

The three Part B fields are telemetry only: none is read by any decision, so they fall under INV-SNK-07 like every other field, and `SinkNeutralityTest` stays the gate.

#### Scenario: Model-action step with MOP boost and outcome

- **WHEN** step 42 selects a `MODEL_CLICK` via the greedy roulette with `mopBoost=500`, `menuBoost=250`, all other boosts 0, under `ape.mopScoring=flag`, the action's lookup answered by its resource-id key, and the executed action produces a transition to a new state at step 43
- **THEN** exactly one record with `s:42` SHALL be written when step 43's graph update resolves the outcome
- **AND** its `dec` SHALL carry `src`, `ch:"roulette_greedy"`, `pri`, `mop:500`, `menu:250`, `mops:"i"` and SHALL NOT carry `wtg`, `cov`, `form`, `mopf` or `mopd`
- **AND** its `out` SHALL carry `new_state:true` and the target state's dictionary ID, and its `dec` no `ret`

#### Scenario: Non-model action record

- **WHEN** the stagnation launcher fires an `EVENT_TRIGGER_ACTIVITY` at step 77 and the platform accepts the intent
- **THEN** the `s:77` record's `dec` SHALL carry the action string, `src:"Component"`, `ch:"launcher"` and `comp:{"r":0}`, with no `pri`, no boost fields, and no `mopd` or `mops`
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

#### Scenario: distance-mode MOP pick carries its distance and source

- **WHEN** under `ape.mopScoring=distance` step 90 selects through the MOP shortcut a click whose stamped handler class is listed in the artifact's `handlers` with nearest unretired target at `d = 2`, and no other action of the state is at `d = 2`
- **THEN** the `s:90` record's `dec` SHALL carry `mop:400`, `mopd:2` and `mops:"s"`

#### Scenario: distance zero is written, not omitted

- **WHEN** under `ape.mopScoring=distance` the chosen action's handler is itself a target, so its weight comes from `d = 0`
- **THEN** its record SHALL carry `mopd:0`
- **AND** a record whose action has no MOP weight SHALL carry no `mopd` member

#### Scenario: a stamp that reaches nothing is recorded as the source

- **WHEN** the chosen click's stamped class is listed in `handlers` with `mop` `none`, while the node's resource-id key is flagged direct in the artifact
- **THEN** the record SHALL carry `mops:"s"` and no `mop` and no `mopd`

#### Scenario: retirement recorded on the step that caused it

- **WHEN** under `ape.mopScoring=distance` with `ape.mopRetireAfter=3` step 120's click is the first interaction of its widget in its activity, and its weight's lookup's targets 4 and 11 reach a count of 3 with it
- **THEN** the `s:120` record's `dec` SHALL carry `ret:[4,11]`
- **AND** `ret` SHALL reach the record through `EventSink.mopRetired` called from `moveForward`, after the step's `sink.decision(...)` call
- **AND** no later record of the run SHALL carry 4 or 11 in `ret`

#### Scenario: flag mode carries no distance and no retirement

- **WHEN** a run uses `ape.mopScoring=flag`
- **THEN** no step record of the run SHALL carry `dec.mopd` or `dec.ret`
- **AND** a record whose chosen action's lookup has source `STAMP` or `ID` SHALL carry `dec.mops`, whether or not the action has a MOP weight

### Requirement: Run-Level Records

The sink SHALL carry the out-of-step records in the same NDJSON stream: `RUN_START` (emitted by the run-spec capability as the first record; carries `run_id`, the epoch base `t0`, and the effective plan — including the LLM parameters previously on the retired `[APE-LLM-CONFIG]` manifest), `MOP_DATA` (the load census, below), `PIPELINE` (assembly provenance, below), `LLM_ACK` (`server_model`, at most once, on the first successful chat response), and `RUN_END`.

**`MOP_DATA` — the load census, and the field that has been misread.** The record SHALL carry `status`, optional `reason` on rejection, `formatVersion` (the format of the loaded artifact, `2` after this change; `0` on a rejection, where a version read is by definition not one the jar can vouch for, `MopData.java:748-753`), `sourceDigest` when the load reached one, and on success `package`, `windows`, `widgets`, `flagged`, `droppedNoId`, `wtgEdges`, `handlersUnmatched`, `syntheticLambda`, `recovered`, `mopActivities`, `mopActsAugmented`, `components`, `targets`, `handlers` and `droppedPairs` (malformed distance pairs dropped at load, `mop-guidance` loader). Apart from the three provenance fields and the three Part B counts, this is the set the retired `[APE-MOP-DATA]` line carried, with one deliberate substitution: the line's `transitions` field is **not** restored, and `wtgEdges` replaces it.

The three counts come with wire format 2 of the compact artifact (`mop-guidance`, `static-analysis-entrypoints`; design D15, D23). `targets` is the artifact's `targets` value, the length of the target list every distance pair indexes, and therefore the bound of every index in `dec.ret`. `handlers` is the number of classes the artifact's `handlers` table lists. `droppedPairs` is the number of malformed distance pairs the loader dropped; a conforming generator emits none, so a non-zero value is a generator↔jar skew signal. Together with `RUN_END.counters.stampNodes` and `stampHits` they say whether the stamp-first lookup had anything to work with: a run whose artifact lists handler classes but whose APK carried no stamp falls back to the resource-id key on every lookup, which is the E6 behavior.

That substitution is the point of the requirement, so it is stated rather than left to the field list. The three frontier passes gate on `MopData.hasWtgData()`, which is `!wtgTransitions.isEmpty()` — a **click-only** convenience view, keyed by base activity, populated only for transitions carrying a `"click"` event whose source and target windows both resolve with non-null names. What the retired line logged as `transitions=N` is `transitions.size()`, the **flat** list — a different structure, and not the one the gate reads. The consequence is measured, not hypothesised: over the decisive campaign's 40 applications, 11 have zero transitions, **14 have transitions but no click event at all** (reporting `transitions` between 9 and 29 while the whole frontier family is disabled), and 15 have at least one click edge. Two applications from that campaign make the trap concrete — `info.metadude.android.datenspuren.schedule` reports `transitions=29` with `WtgPass`/`FrontierPass`/`MopFrontierPass` all absent, while `com.starry.greenstash` reports `transitions=24` with all three constructed. Restoring `transitions` would restore the misreading; `wtgEdges` is the number the gate actually reads, and it is emitted under the same name the stage-7 artifact already uses, so the field does not change identity across the window.

A separate boolean `has_wtg_data` SHALL NOT be emitted: a per-activity edge list is created only at the moment an edge is added to it, so `hasWtgData()` is exactly `wtgEdges > 0` and a second field would be a second place the same truth is written.

**`PIPELINE` — assembly provenance including what was *not* assembled.** The record SHALL carry `passes` (the enabled scoring passes, in pipeline order — unchanged, and still exactly what the `[APE-ARCH]` line reports) and, beside it, `candidates`: every candidate pass with `{name, enabled}`. The stage side SHALL be a static declaration of candidate stage names, not constructed objects — a feature absent from the plan has no stage (decision-pipeline INV-DP-03), and the census SHALL NOT reintroduce a disabled-stage object to enumerate.

A `reason` field on the pass entries SHALL NOT be emitted. The three gates are `mopData != null && mopData.hasWtgData() && <weight>`, and every conjunct is already recoverable from the same trace: `MOP_DATA.status` gives the first, `MOP_DATA.wtgEdges` the second, `RUN_START.params` the third. A reason field would encode the order in which each constructor happens to evaluate its conjuncts — and that order is not uniform across the three passes — rather than the cause.

The census is what makes the pass list readable as a data-dependent outcome instead of a configuration echo. In the decisive campaign the `[APE-ARCH] passes=` line takes exactly three values across all 360 runs, and the split is 45/75 in every arm: **the WTG/frontier family is never constructed in 25 of the 40 applications — 62.5 % of the corpus** — with nothing in the trace saying so except the absence of three names from a list no analyst was reading as an outcome. Without `candidates`, "the arm turned this off" and "this application's data could not support it" remain indistinguishable.

#### Scenario: MOP data load recorded

- **WHEN** the format-2 artifact loads successfully with 12 windows, 340 widgets, 9 targets and 57 handler classes
- **THEN** the trace SHALL contain `{"type":"MOP_DATA","status":"loaded","formatVersion":2,...,"windows":12,"widgets":340,...,"targets":9,"handlers":57}` as its own line

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

#### Scenario: a format-1 artifact is rejected with its format named

- **WHEN** the pushed `.mop.json` declares `formatVersion: 1`
- **THEN** the `MOP_DATA` record SHALL carry `status:"rejected"`, `reason:"version-mismatch"` and `formatVersion:0`
- **AND** its census counts, `targets` and `handlers` among them, SHALL be absent or `0`

### Requirement: RUN_END As the Natural Last Record

Teardown SHALL emit `{"type":"RUN_END","reason":...,"steps":N,"t_first_step":...,"t_last_step":...,"counters":{...}}` as its last sink action (after `flushPendingStep` and all other agent teardown steps), where `reason` ∈ `timeout` | `crash` | `unknown` and `counters` includes the LLM aggregate counters previously on the retired `[APE-RV] LLM Summary` line (`calls`, `tok_in`, `tok_out`, `ms`, `matched`, `llm_tap`, `no_match`, `dead_pair`, `repaired`, the seven failure-cause counters, `breaker_trips`), the record/dictionary counts, and the three MOP-guidance counters of `llm-coordinate-single-base` (design D23):

- `stampNodes`: the number of nodes, over every GUI tree captured in the run, that carried at least one handler stamp (`rvsec.click` or `rvsec.longClick`, `ui-tree` "Handler Stamp Capture"). A node is counted once per capture it appears in.
- `stampHits`: the number of steps whose chosen action's lookup, the one `dec.mops` records, had source `STAMP`: at most one per step. Lookups made by the other readers (the scoring of the actions not chosen, the shortcut ranking, the prompt marker, retirement) are not counted.
- `retired`: the number of targets retired by the end of the run, which equals the number of indices the run's step records carry in `dec.ret`, except for a retirement whose step record was lost.

The three counters SHALL be written on every run, in every arm, so that a zero is a measurement rather than an omission: on an APK instrumented without `--stamp-handlers` `stampNodes` is `0` and every lookup falls back to the resource-id key; on a run without MOP data `stampHits` and `retired` are `0`; under `ape.mopScoring=flag` `retired` is `0`. Per owner decision D5 this record is write-only: nothing on the Python side reads or validates it, and its absence changes no task status.

**Why the two timestamps, and why not a consumed-budget field.** `t_first_step` and `t_last_step` are the `t` of the first and last step records, on the same clock as every step. Their span against the run's budget is what separates a run that explored for its whole budget from one that was alive and idle — and this corpus contains the second kind. In the decisive campaign one run is recorded `COMPLETED` with 1,871 s of execution time and emitted **150 step records spanning 446 s**: roughly 1,400 s of its budget produced no exploration at all. A `budget_consumed_ms` field would have reported ~1,800 s for that run and flagged nothing, which is why it is not specified here; the same value is in any case derivable from `t_last_step`, and the harness already records wall-clock execution time per attempt host-side. Eight runs of the campaign have a step span under 1,700 s, seven of them concentrated in two applications — a pattern only these two integers make visible at run level.

The attempt ordinal is deliberately **not** a field. The jar does not know it is a retry, and the harness that does know already writes one task record per attempt, with state, both timestamps and the error message. Round-tripping that value through a plan key into a record the harness then reads back would be indirection with one subscriber. This record stays write-only (INV-SNK-09): these fields make a pre-consolidation liveness check cheap to write on the analysis side; they do not implement one, and no task status may come to depend on them.

#### Scenario: Normal timeout termination

- **WHEN** the run ends by time budget (`StopTestingException`)
- **THEN** the last sink record of the trace SHALL be `RUN_END` with `reason:"timeout"` and the counters, including `stampNodes`, `stampHits` and `retired`
- **AND** the LLM decision ratio SHALL be derivable from the counters (not stored)

#### Scenario: SIGKILL loses RUN_END without consequence

- **WHEN** the harness SIGKILLs the process before teardown runs
- **THEN** the trace legitimately ends without `RUN_END` (and with at most one lost `StepRecord`)
- **AND** no Python-side mechanism SHALL treat this as an error (post-hoc timestamp analysis remains the detection path, D5)

#### Scenario: unstamped APK reports zero stamps

- **WHEN** a MOP arm runs on an APK instrumented without `--stamp-handlers`
- **THEN** `RUN_END.counters` SHALL carry `stampNodes:0` and `stampHits:0`
- **AND** every step record's `dec.mops`, where present, SHALL be `"i"`
