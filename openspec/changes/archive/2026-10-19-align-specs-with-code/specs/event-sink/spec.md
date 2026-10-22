## Purpose
The event sink is the observation surface of a run: everything the jar records about its own behavior flows through one component, `EventSink`, into one stream — NDJSON records on stdout, captured by the harness into the `.trace` file. The sink emits **one `StepRecord` per step** — envelope once; the decision, every LLM routing attempt, and the outcome grouped inside it — closed and written when the step's outcome resolves at step N+1, the timing of the decision join buffer. It is what the retired `[APE-STEP]`/`[APE-OUTCOME]`/`[APE-LLM-TEL]` `key=value` line family carried, without the analyst-side re-join by `step=` and without unescaped content.

Telemetry is an instrument, not a feature (report Sec. 6.5): it is **always on and identical for every experimental cell**, and no plan key disables it. Its substitute for an arm-level switch is a *provable* neutrality property (R7, INV-SNK-07): the sink observes and never decides — with the sink on or replaced by a no-op, the same seed produces the same action sequence, and that equivalence is a permanent test. The serializer (`ape.runtime.Json`) is hand-written, with zero dependencies and real escaping, so a newline or control character in source data cannot split a record: any content fits the format.

Volume is a first-class concern because trace cost per step is itself a measured experimental variable (0.037–0.052 pp `cov_mop` per lost step). Four levers apply: envelope once per step; defaults omitted (absent field = default); a run-local integer ID table for the trace's longest repeated strings (`ACT`/`STATE` dictionary events); and `run_id` only in `RUN_START`. Compression at rest is the Python side's job (gzip at collection — aperv-tool capability).

The stream also carries out-of-step records: `RUN_START` (emitted by the run-spec capability as the first record), dictionary entries, `MOP_DATA` load status, `PIPELINE` assembly provenance, `LLM_ACK`, and `RUN_END` (reason + counters) as the natural last record. Per owner decision D5 there is **no exit contract**: no consumer validates `RUN_END`, no sentinel logic exists in `tool.py`, and truncated-run detection remains a post-hoc analysis over timestamps. Per owner decision D4, a write-only logcat heartbeat (one `Log.i` line per step) places violation and step in the same file and clock for the analysis-side join, behind a plan flag that defaults on.

A step is a fixed sequence of owners — the guards (Budget, restart), the LLM triggers and coin, the MOP launcher, the MOP coin, and SATA with its scorer chain (design D-B7) — mounted by the plan's behaviour groups (BASE, ADDONS, STATIC, MOP, LLM). The step record says who decided and how: `dec.src` names the owner that executed the step (never the largest boost), `dec.own` the intended owner and why another took the step, and `dec.cfc`, `dec.draw`, `dec.nav`, `dec.inp`, `dec.restart`, `dec.bmc`, `dec.strat`, `dec.rot`, `dec.buf`, `dec.trig` and the scorer field `navf` record the per-scorer counterfactual, the roulette draws, the SATA navigation, the input origin, the restart, the BACK/MENU cap, the SATA path, the display rotation, the abandoned buffer and the component trigger; `dec.ch` includes the channels `budget`, `restart` and `mop_coin`. `RUN_END.counters` carries the nine routing counters (`launches`, `gateMinActions`, `gateBreaker`, `guardBacks`, `bufAbandoned`, `restarts`, `coinLlm`, `coinMop`, `nullRelaunches`), and `RUN_START.variant` echoes `ape.variant`. Four rules bound this surface: completeness (every mechanism that can decide or change a step appears in the trace), purity of every telemetry computation, a clean control (a cell without MOP carries no MOP signal), and `SinkNeutralityTest`, run once per cell preset.

## Invariants

The whole INV-SNK family, INV-SNK-01 to INV-SNK-19, is stated in this section. The introductory sentence that sent INV-SNK-01 to INV-SNK-13 to the archived `2026-08-07-rearch-04-step-ndjson-telemetry` delta is deleted; INV-SNK-05 and INV-SNK-15 to INV-SNK-19 stand as written, and the ids below are added.

- **INV-SNK-01**: Every sink record SHALL occupy exactly one line: the serializer (`ape.runtime.Json`) SHALL NOT emit a raw newline (or any raw control character) inside a record; the record terminator is the single `\n` the sink writes.
- **INV-SNK-02**: The serializer SHALL escape `"` and `\`, SHALL escape every character in U+0000–U+001F (including NUL) as `\uXXXX` or its JSON shorthand, SHALL escape U+2028/U+2029, and SHALL pass other non-ASCII characters through unmodified (the stream is UTF-8). Any string value SHALL survive a serialize→parse round trip unchanged.
- **INV-SNK-03**: Exactly one `StepRecord` SHALL be written per exploration step that reaches action selection (opened by `beginStep`); `s` values are unique and strictly increasing within a run.
- **INV-SNK-04**: Envelope fields (`s`, `t`, `act`, `st`) SHALL appear exactly once per `StepRecord` and on no `llm[]` entry; `run_id` SHALL appear only in `RUN_START`, never on a step record and never in `RUN_END`.
- **INV-SNK-06**: Every dictionary ID (`act`, `st`, `out.target`) referenced by a record SHALL have been defined by an `ACT`/`STATE` record on an earlier line of the same trace: the sink writes the dictionary entry inside the ID lookup, on first sight. IDs are run-local integers with no cross-run meaning.
- **INV-SNK-07**: The sink SHALL be neutral — with `NdjsonSink` or with `NoopSink`, the same seed and inputs SHALL produce the identical action sequence (R7). Every `EventSink` method returns `void`; the sink SHALL NOT read or advance any seeded RNG stream and SHALL NOT expose state consumed by any decision path. This is the recorded substitute for an arm-level telemetry switch, which does not exist.
- **INV-SNK-08**: A `StepRecord` SHALL be closed and written when its outcome resolves during step N+1's graph update, under the same single-shot, reference-equality guard as the decision join buffer. A record whose outcome legitimately never resolves SHALL be closed **without** an `out` member when the next `beginStep` opens a record; the teardown `flushPendingStep` SHALL write a still-pending record with `out:{"resolved":false}`. Maximum loss on sudden process death is one `StepRecord` (plus `RUN_END`).
- **INV-SNK-09**: On every normal termination, `RUN_END` SHALL be the last sink record of the trace (the last teardown step of `StatefulAgent.tearDown`), carrying the termination reason and the run counters. It is write-only: no consumer validation, no task-status coupling, no retry logic exists or may be added on its account (owner decision D5).
- **INV-SNK-10**: When the heartbeat flag is on (`ape.telemetryHeartbeat`, default `true`), exactly one `Log.i` heartbeat line `s=<step> t=<tRelMs>` under the tag `NdjsonSink.HEARTBEAT_TAG` (`ApeRvHb`) SHALL be written per step, and the jar SHALL NOT read logcat under any configuration. A logcat failure disables the heartbeat for the rest of the run and never the record.
- **INV-SNK-11**: Every sink record line SHALL begin with `{`, and the sink SHALL write directly to the `PrintStream` it was constructed with (`System.out` on every production path), never through `Logger`, whose lines all begin with `[APE] `. A trace line is a sink record if and only if it begins with `{`.
- **INV-SNK-12**: A `Throwable` raised inside any sink method SHALL NOT propagate to the caller; after the first such failure the sink SHALL latch itself off, log one warning, and stop emitting for the remainder of the run.
- **INV-SNK-13**: The sink's per-step cost SHALL NOT reduce step throughput below the pre-sink jar's, measured as steps per minute on the same APK, seed and wall-clock budget, with the comparison's own noise established by repeating the reference run. This covers the whole per-step write path as shipped — the stdout record, the logcat heartbeat, prompt-dump escaping — not the stdout write alone. It is a measured gate, not a design argument.
- **INV-SNK-14**: The heartbeat's logcat tag SHALL be present in the capture-side tag allowlist of every consumer expected to read it. On rv-platform it is: `ApeRvHb` is a member of `LogcatManager.default_tags`, the baseline capture tag set (rv-android INV-PLT-21). A heartbeat written under a tag the harness filters out is inert.

## MODIFIED Requirements

### Requirement: StepRecord Schema

The sink SHALL emit one `StepRecord` per exploration step as a single-line JSON object with the following members. Field presence follows INV-SNK-05 (defaults omitted; tri-state exemptions explicit).

**Envelope** (once per record): `s` (step counter), `t` (milliseconds since `RUN_START`; epoch time = `RUN_START.t0 + t`), `act` (integer `ACT` dictionary ID of the current activity), `st` (integer `STATE` dictionary ID of the current abstract state).

**`dec`** (decision): `a` (the full action string, JSON-escaped; the action the step dispatched), `src` (the owner that executed the step, below), `ch` (pick channel label, below), `own` (the routing record, below; every step), `rot` (the display rotation, below; every step). Model-action records additionally carry `pri` (priority) and the non-zero scorer fields `mop`, `mopf`, `wtg`, `navf`, `cov`, `menu`, `form`; `mopx` (`[weighted, total]`, the MOP exposure pair, present exactly when the MOP widget pass is constructed); `mopd` and `mops` (below, the MOP lookup behind `mop`); `hnd` (below, the stamped handler behind the weight); `ret` (below, the targets the step's action retired); `patched` (0/1, present exactly when the action has a resolved target node). The members `cfc`, `draw`, `nav`, `inp`, `restart`, `bmc`, `strat`, `buf` and `trig` are written when their condition below holds. Non-model records carry `a`/`src`/`ch`/`own`/`rot` and the conditional members that apply, and launcher records additionally carry `comp` (below).

**`dec.src` — the executed owner.** `src` is one of `budget`, `restart`, `llm`, `launcher`, `mop`, `sata`, written by the pipeline stage that took the step (`decision-pipeline`, design D-B7). Its meaning changed with `isolate-guidance-groups`: it was the decision source of the largest boost, and it is now the owner that executed the step. A scorer never sets `src`: scorers adjust SATA's candidates, and a step that SATA decides is `sata` whatever the scorers wrote (INV-SNK-15). `RUN_START.build` tells a trace written before this change from one written after it.

**`dec.ch` — the pick channel.** `ch` survives as the channel label of the pick (design D-B14.17): `roulette_greedy`, `roulette_early`, `buffer` and `sata_other` for SATA's paths (`sata_other` covers SATA's other paths only, never a guard step), `budget` for a step the Budget guard takes, `restart` for a step the RESTART guard takes, `launcher` for a launcher step, `llm` for an LLM step, and `mop_coin` for a step the MOP coin takes. The values `short_circuit_unvisited` and `short_circuit_0step` SHALL NOT be produced: the MOP shortcut they named left SATA. `ch` complements `src`, which names the owner, and `strat`, which names the SATA path.

**`dec.own` — who was meant to take the step, and why another did.** Shape: `{"i":<owner>, "trg":"new_state"|"stagnation"|"coin", "uL":<double>, "uM":<double>, "g":<gate>, "fb":<reason>}`. It SHALL be written on every step record (INV-SNK-05 exemption).
- `i`: the intended owner, the first claimer of the step in pipeline order (design D-B14.6): a guard that is due (`budget`, `restart`), a fired LLM trigger or a won LLM coin (`llm`), a due launcher (`launcher`), a won MOP coin (`mop`) — otherwise `sata`. A lost coin, an ineligible MOP coin and a launcher below its cadence claim nothing.
- `trg`: present when `i` or `src` is `llm`: `new_state` or `stagnation` for the two triggers, `coin` for the `p_L` coin. A step makes at most one LLM call (`llm-routing` "Declared LLM Fallback"), so when an LLM stage called, `trg` names the stage that made the step's one call; when no stage called because a gate barred, it names the first LLM stage whose trigger held.
- `uL`: the LLM coin's uniform draw for the step, present exactly when `Feature.LLM` is in the plan. `uM`: the MOP coin's uniform draw, present exactly when `Feature.MOP` is in the plan. Both are drawn at step open from their own streams, before any gate (`decision-pipeline`, design D-B9), so they are present whether or not their coin was consulted.
- `g`: present when `fb` is `gate`: `min_actions` or `breaker`, the gate that barred the LLM.
- `fb`: present exactly when `src` differs from `i`, giving the reason: `gate` (an LLM gate barred the call), `screenshot` (the LLM screenshot could not be captured, so no call was made; design D-B18), `error` (the LLM call failed), `decline` (the engine returned no action for any other reason), `no_match` (the answer was refused at mapping, including `dead_pair`, `boundary` and `degenerate`; `llm-routing` "Declared LLM Fallback"), `ineligible` (the launcher was due and found no eligible candidate).

**`dec.cfc` — the counterfactual per scorer.** Shape: `{"<scorer>":0|1}`, one key per mounted scorer whose term is non-zero on at least one action of the pick's candidate set, among `mop`, `mopf`, `wtg`, `navf`, `cov`, `menu`, `form`. Value `1` when the pick, recomputed with that scorer's field set to `0` on every candidate and with the same recorded draw index, selects another action; `0` when it selects the same action or the recomputation failed. It SHALL be present on the roulette picks (EARLY_STAGE, ε, null handler) and on the least-visited pick, and only there, when at least one key exists. On the least-visited pick the candidate set is the set of tied least-visited actions, and `cfc` is written only when that set has two or more actions, that is, when the X1 tie was consulted (author decision 2026-10-17): with a single least-visited action no scorer can change the pick. It replaces `dec.cf`, which covered only `mop` and `mopf`. It is computed by `ScorerCounterfactual`, the generalization of `MopCounterfactual`'s mould: zero one field, replay the same draw index, compare (INV-SNK-16).

**`dec.draw` — the roulette draw.** Shape: `[idx, total]`: the integer the roulette drew and the bound it was drawn below, so that the pick can be replayed offline. Present on the roulette picks (EARLY_STAGE, ε, null handler).

**`dec.nav` — the SATA extension point that decided a tie.** Shape: `{"x":"X1"|"X3"|"X4", "c":"ape"|"mop", "alt":0|1, "drew":0|1}`. Present when an extension point (`action-selection` X1 least-visited tie, X3 ABA target tie, X4 path to a trivial activity; design D-B12) decided between two or more candidates. `x` names the point, `c` the mounted variant (`mop` when the point's MOP variant is mounted, `ape` otherwise), `drew` is `1` when the decision consumed a draw (X4's `randomPick`). `alt` is `1` exactly when the MOP variant decided by its own rule: at X3, the candidate replaced the incumbent; at X4, the strictly largest end-state density chose the path, without a draw. `alt` is `0` when the MOP variant fell back to the APE rule (X3 kept the incumbent; X4 found the densities equal and drew). X1 has no MOP variant: its record always carries `c:"ape"` and `alt:0`. In a run without `Feature.MOP` no MOP variant is mounted and `alt` is `0`. `alt` is read off the mounted variant's own decision; no unmounted variant is evaluated to compute it.

**`dec.inp` — the origin of the text the action entered.** One of `typed`, `heuristic`, `random`, `form`, `llm` (`heuristic-input`, `form-completion`). Present exactly when the step's input refiner set a node's input text.

**`dec.restart` — the kind of restart.** One of `graph`, `state`, `activity`, `periodic`. Present exactly when `src` is `restart`.

**`dec.bmc` — the BACK/MENU cap in this pick.** Integer: the number of candidates the BACK/MENU cap (`ape.backMenuPickCap`, ADDONS) removed from the pick that selected the action. Omitted when `0`.

**`dec.strat` — the path inside SATA.** One of `buffer`, `back_to_activity`, `aba`, `early_roulette`, `greedy_global`, `greedy_path`, `trivial`, `early_backward`, `backtrack`, `eps_back`, `eps_menu`, `least_visited`, `eps_roulette`, `null`, `saturated`. Present exactly when `src` is `sata`.

**`dec.rot` — the display rotation.** Integer in `-1..3`: the rotation `0..3` the display reported at step open (`llm-infrastructure`), or `-1` when it could not be read. Written on every step record, `0` included (INV-SNK-05 exemption), so the fraction of landscape steps is measurable on every cell.

**`dec.buf` — a buffer abandoned by preemption.** The owner (`budget`, `llm`, `launcher`, `mop`) that took the step while the SATA buffer was non-empty and abandoned it through `abandonBuffer(owner)`, without weakening the planned edge (`action-selection`, P-4, design D-B14.13). A restart step carries no `buf`: the restart starts a new episode, which clears the buffer, also without weakening (`decision-pipeline`). Absent when no buffer was abandoned.

**`dec.navf` — the navigation frontier term.** The non-zero boost written by `NAV_FRONTIER` (STATIC), in its own field. `wtg` is written by the WTG pass (MOP) alone; the two producers no longer share a field, so `dec.wtgsrc` is removed.

**`dec.mopx` — the exposure denominator, which is otherwise lost in this change.** Its first member counts the actions with a positive MOP weight (`mopWeight`): the pair measures the opportunity, not the boost (author, 2026-10-18). The jar prints the Logger line `[APE-RV] MOP boost: state=…, boosted=X/Y, …` once per step (`MopWidgetPass`), which the retired `[APE-STEP]` line accompanied 1:1, and outside `mopx` that `X/Y` is the only record of how many actions on the screen were even *eligible* for MOP steering. Without it, "the MOP scorer fired on 0.4 % of decisions" has no denominator and cannot be separated into "the mechanism had no opportunity" and "the mechanism had one and lost the roulette" — which is the compliance question the whole guidance contrast rests on. The line is **not** among the four families this change's no-information-loss clause covers, and its sibling `[APE-RV] Frontier boost: … boosted=<n>/<total>` **is** normative in the wtg-navigation capability, so leaving it unstated would retire the MOP denominator while preserving the frontier one. It rides the step record rather than the `STATE` dictionary entry: the pair was measured across 25 campaign traces and is **not** constant per abstract state (14 of the 25 show a state with more than one realised pair), so the dictionary encoding that would have made it free is not available. It therefore costs bytes on every guided-arm step and is subject to INV-SNK-13 like any other per-step field.

**`dec.mopd` and `dec.mops` — what the MOP weight rests on** (`llm-coordinate-single-base`, design D23). Both describe the `mop-guidance` lookup (`MopData.lookup`, "Handler Stamp Lookup") that the scoring pass kept for the chosen action — the best of the lookups over the action's containment nodes:

- `mopd` (int): under `ape.mopScoring=distance`, the distance `d` of the nearest unretired target that gave the action its MOP weight, a value in `0..3` (a target at `d ≥ 4` gives no weight). It SHALL be present exactly when the action's MOP weight before the tie split (`mop-guidance` "MopScorer — Priority Boost", the undivided weight the marker and retirement also read) is greater than 0, which is when the record carries `mop` except in the degenerate case of a tie split that rounds to 0 (author, 2026-10-09), and SHALL be emitted when its value is `0` (INV-SNK-05 exemption: `mopd:0` says the handler is itself a target; an absent `mopd` says there is no distance-based weight). Under `ape.mopScoring=flag` it SHALL never be present.
- `mops` (string): `"s"` when the lookup was answered by the node's handler stamp (source `STAMP`), `"i"` when by the resource-id key (source `ID`); absent when the source is `NONE` and on actions with no resolved target. It SHALL be written whenever the chosen action's lookup has source `STAMP` or `ID`, in both scoring modes and whether or not the action carries a weight: a stamp listed with no target (`mop` absent, `mops:"s"`) is the stamp overriding a flagged id key, which is the case the lookup exists for. The lookup recorded is the weight's lookup, the one `MopScorer.weigh` gave the action its weight from; when no containment lookup gives a weight under the active mode (under `distance` a lookup with only a flag, or only retired pairs, gives none), the source recorded is that of the lookup on the action's own target node (author, 2026-10-09).

Both are MOP fields: in a run without `Feature.MOP` no lookup is made and neither is written (INV-SNK-18).

**`dec.hnd` — the stamped handler behind the weight** (design D-B17). The binary class name of the handler stamp that answered the chosen action's weight lookup (`MopData.Lookup.handler`). It SHALL be written exactly when `dec.mops` is `"s"`, and is absent otherwise. It makes measurable whether the coin and SATA spend their picks on list items that share one handler (design D-B17); it changes no decision. It is a MOP field (INV-SNK-18).

**`dec.comp` — whether the launch landed.** Launcher records (`src:"launcher"`) SHALL carry `comp` with the activity-launch outcome, which `MonkeySourceApe` reports through `componentLaunch(...)`: `{"r":<int>}`, the result the platform returns for the launch, and `{"r":<int>,"e":"<enum>"}` when the dispatch failed. The jar already binds a boolean at the dispatch site and already logs a warning on false; the finer result code is discarded one frame below, where `IActivityManager.startActivity`'s `START_*` return value is dropped. Capturing it introduces no retry, no re-dispatch, no extra IPC and no mutation of the round-robin cursor — it records a value the launch already produced. Ordering is not an obstacle even though it was in the retired line family: the step record is closed at step N+1, so the dispatch result exists before the record is written, whereas `[APE-STEP]` was emitted before dispatch. This guards the campaign's strongest mechanism result — roughly 1,075 direct component launches in the guided arm against 0 in the control — for which a refused intent and an accepted one are otherwise the same trace evidence.

**`dec.trig` — a component the trigger fired.** Shape: `{"k":"broadcast"|"service"|"provider","c":"<class>"}`: the kind of component `ComponentTriggerStage` (STATIC, `component-triggering`) dispatched and its class name. It SHALL be written on the record of the step during which the stage fired, whatever that record's `src`: the trigger is a side effect of the step, not a step owner, so it never sets `src`, `ch` or `own.i`. It reaches the open record through its own sink entry, `componentTrigger(kind, className)`, called after the step's `sink.decision(...)` call. Absent on a step on which the trigger did not fire, and on every step of a plan without `COMPONENT_TRIGGER`. `dec.comp` stays the launcher's activity-launch outcome; a component trigger writes no `comp`.

**`llm`** (optional ordered array): one entry per LLM routing attempt made during the step's selection, in occurrence order — completed calls (`call`, `mode`, `tool`, `qwen:[x,y]`, `px:[x,y]`, `result` ∈ `matched|llm_tap|no_match`, optional `reason` ∈ `dead_pair|degenerate|boundary`, optional `repair` (repair-form provenance), `mcls`, `ncls`, `ndist`, `widgets`, `tok:[in,out]`, `ms`, optional `text`, and — when the prompt-dump flag is on (default) — `sys`/`user`/`resp`/`tool_calls`); abandoned attempts (`result:"error"` with `cause` and `detail`); and the once-per-open-episode breaker decline (`result:"breaker_open"` with `trips`). The array holds at most one call entry, completed or abandoned, because a step makes at most one LLM call (`llm-routing` "Declared LLM Fallback"); a breaker decline is not a call.

**`out`** (outcome, attached at step N+1): `new_state` (omitted when false), `target` (STATE ID of the resulting state), `act_changed` (omitted when false). `activity_has_mop` for both the step's and the outcome's activity is carried by the referenced `ACT` dictionary entries, not repeated per record. A record closed without a resolved outcome has no `out` member; a record flushed at teardown has `out:{"resolved":false}`.

**`dec.ret` — targets this step retired** (design D23 of `llm-coordinate-single-base`). Under `ape.mopScoring=distance`, the integer indices, into the artifact's target list (`MOP_DATA.targets`), of the targets whose retirement counter reached `ape.mopRetireAfter` as a consequence of this step's action — the first interaction of its widget in its activity, counted and applied in `StatefulAgent.moveForward` (`mop-guidance` "Target Retirement"). No count is pending and none is discarded: the restart is a guard stage that runs before selection (`decision-pipeline` "Restart Guard Stage"), so no selected action is replaced after selection and no `checkRestart` override takes part. The indices are not known when the decision is recorded, so they SHALL reach the open record through their own sink entry, `EventSink.mopRetired(int[] indices)`, called from `moveForward`, after the step's `sink.decision(...)` call, when the retirement call returns a non-empty array, after the pattern of `mopExposure` and `componentLaunch`, which also add a member to the step's `dec` after the decision call. `StepRecord` writes `dec` when the record closes, so `ret` sits in `dec` and survives a record closed without an `out` member. Each index appears once, in ascending order. The array SHALL be absent when it would be empty, which is every step under `ape.mopScoring=flag`. A target retires once, so an index appears in `dec.ret` at most once per run.

The members that are known only after the decision call (`inp`, `ret`, `buf`, `comp`, `trig`) reach the open record through their own sink entries (`input(...)`, `mopRetired(...)`, `bufferAbandoned(...)`, `componentLaunch(...)`, `componentTrigger(...)`), called after `sink.decision(...)`. Each entry is pure (INV-SNK-16): it adds a member to the open record and reads nothing a decision reads.

Every field of the retired `[APE-STEP]` (both forms), `[APE-OUTCOME]`, and `[APE-LLM-TEL]`/`[APE-LLM-ERROR]` lines SHALL be either present in this schema or derivable by dictionary lookup within the same trace (the design's mapping table is normative for the correspondence), except the retired line's counterfactual flag and `wtgsrc`, which `dec.cfc` and the separate `navf` field replace.

**The scope of that clause is four line families, and other emissions fall outside it.** They are named here so that "additive only" is not read as a guarantee it does not make. `[APE-MOP-DATA]` is outside the clause — which is why its replacement, `MOP_DATA`, is specified field-by-field in the run-level requirement rather than inherited. The `[APE-ARCH] stages=[…]` Logger line is not retired: `DecisionPipeline` still prints it, and the `PIPELINE` record carries the same stage list together with the census the line never carried. `[APE-RV] MOP boost` is outside it too, and is the one emission the clause's absence would have retired silently: it carries the exposure denominator, its sibling `[APE-RV] Frontier boost` *is* normative in the wtg-navigation capability, and it is preserved here deliberately as `dec.mopx` above. (`[APE-LLM-CONFIG-ACK]` and `[APE-LLM-RESPONSE]` are also outside the clause but are not at risk: the former is specified as the `LLM_ACK` record and the latter is mapped onto the `resp`/`tool_calls` fields by the design's mapping table.)

Every `dec` member is telemetry only: none is read by any decision, so they fall under INV-SNK-07 and INV-SNK-16, and `SinkNeutralityTest` stays the gate (INV-SNK-19).

#### Scenario: Model-action step with MOP boost and outcome

- **WHEN** in cell `as_mop` under `ape.mopScoring=flag` step 42 selects a `MODEL_CLICK` through the ε roulette, after the MOP coin lost (`ape.mopCoinPercentage=0.5`, `u_M = 0.8`), with `mopBoost=500` (the action's key not yet tried in the activity), `menuBoost=250`, all other scorer fields 0, the action's lookup answered by its resource-id key, the roulette drawing 731 below 1290, and the executed action produces a transition to a new state at step 43
- **THEN** exactly one record with `s:42` SHALL be written when step 43's graph update resolves the outcome
- **AND** its `dec` SHALL carry `src:"sata"`, `strat:"eps_roulette"`, `ch:"roulette_greedy"`, `draw:[731,1290]`, `cfc` with the keys `mop` and `menu`, `pri`, `mop:500`, `menu:250`, `mops:"i"`, `rot:0` and `own` with `i:"sata"` and a `uM` value, and no `fb`, because the lost MOP coin claimed nothing
- **AND** its `dec` SHALL NOT carry `wtg`, `navf`, `cov`, `form`, `mopf`, `mopd`, `cf` or `wtgsrc`
- **AND** its `out` SHALL carry `new_state:true` and the target state's dictionary ID, and its `dec` no `ret`

#### Scenario: Non-model action record

- **WHEN** the MOP launcher fires an `EVENT_TRIGGER_ACTIVITY` at step 77 and the platform accepts the intent
- **THEN** the `s:77` record's `dec` SHALL carry the action string, `src:"launcher"`, `ch:"launcher"`, `own` with `i:"launcher"`, `rot` and `comp:{"r":0}`, with no `pri`, no scorer fields, no `strat`, and no `mopd` or `mops`
- **AND** the record SHALL be closed without an `out` member (non-model actions produce no transition under their own identity)
- **AND** whether the launched component actually reached the foreground SHALL be derivable within the same trace, without a new field, by comparing the launched class in `dec.a` against the next step record's `act` — the same two-hop discipline the schema already uses for the outcome-side MOP flag

#### Scenario: launch refused by the platform

- **WHEN** the launcher dispatches an intent for a component the system refuses
- **THEN** the record's `dec.comp` SHALL carry the non-success result code and its failure enum
- **AND** the record SHALL NOT differ from an accepted launch in any other field — a refused launch and an accepted one are distinguishable only here

#### Scenario: Tri-state patched preserved

- **WHEN** step A selects a click on a patch-promoted node, step B a click on a natively clickable node, and step C a `MODEL_BACK` with no resolved target
- **THEN** record A SHALL carry `dec.patched:1`, record B SHALL carry `dec.patched:0`, and record C SHALL carry no `patched` member

#### Scenario: LLM call recorded as sub-event of its step

- **WHEN** at step 42 the new-state trigger makes LLM call 3, which matches a widget after 973 ms with 1841/25 tokens
- **THEN** the `s:42` record's `llm` array SHALL contain one entry with `call:3`, `mode:"new-state"` (the engine's mode label, `llm-routing` "LLM Telemetry Logging"), `result:"matched"`, `px`, `tok:[1841,25]`, `ms:973`
- **AND** its `dec` SHALL carry `src:"llm"` and `own` with `i:"llm"` and `trg:"new_state"`
- **AND** no separate per-call record SHALL be written — call, decision, and outcome share one record with no join key

#### Scenario: Abandoned LLM attempt attributed by construction

- **WHEN** in cell `as_llm` at step 55 the LLM coin is won (`uL=0.31`, `p_L=0.9`), the LLM HTTP call times out, and selection falls back to SATA
- **THEN** the `s:55` record's `llm` array SHALL contain an entry with `result:"error"`, `cause:"timeout"`, and a detail message
- **AND** the record's `dec.src` SHALL be `"sata"`, the owner that actually selected, and `dec.own` SHALL be `{"i":"llm","trg":"coin","uL":0.31,"fb":"error"}`, with no `uM`

#### Scenario: distance-mode MOP pick carries its distance and source

- **WHEN** under `ape.mopScoring=distance` step 90 is taken by the MOP coin, which picks a click whose stamped handler class is listed in the artifact's `handlers` with nearest unretired target at `d = 2`, and no other action of the state is at `d = 2`
- **THEN** the `s:90` record's `dec` SHALL carry `src:"mop"`, `ch:"mop_coin"`, `mop:400`, `mopd:2` and `mops:"s"`
- **AND** it SHALL carry no `strat`, no `draw` and no `cfc`, because SATA did not pick

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
- **AND** `ret` SHALL reach the record through `EventSink.mopRetired`, called from `StatefulAgent.moveForward` after the step's `sink.decision(...)` call
- **AND** no later record of the run SHALL carry 4 or 11 in `ret`

#### Scenario: flag mode carries no distance and no retirement

- **WHEN** a run uses `ape.mopScoring=flag`
- **THEN** no step record of the run SHALL carry `dec.mopd` or `dec.ret`
- **AND** a record whose chosen action's lookup has source `STAMP` or `ID` SHALL carry `dec.mops`, whether or not the action has a MOP weight

#### Scenario: restart guard recorded as the owner

- **WHEN** at step 300 the activity-stable restart threshold (200 steps) has passed and the RESTART guard takes the step
- **THEN** the `s:300` record's `dec.a` SHALL be the restart action that was dispatched
- **AND** its `dec` SHALL carry `src:"restart"`, `restart:"activity"` and `own` with `i:"restart"`, and no `strat`

#### Scenario: preemption abandons the buffer on the record

- **WHEN** at step 64 the SATA buffer holds two planned actions and the LLM coin wins and its call matches a widget
- **THEN** the `s:64` record's `dec` SHALL carry `src:"llm"` and `buf:"llm"`

#### Scenario: extension point recorded

- **WHEN** in cell `base` the least-visited pick finds three candidates with equal visit counts and no scorer is mounted, so X1 takes the first of the array
- **THEN** the record's `dec` SHALL carry `strat:"least_visited"` and `nav:{"x":"X1","c":"ape","alt":0,"drew":0}`, and no `cfc`

#### Scenario: BACK/MENU cap and typed text recorded

- **WHEN** at step 18 the BACK/MENU cap removes one `MODEL_BACK` from the ε pick, the pick selects an unfilled `EditText`, and the input refiner fills it with a value from `TypedInputGenerator`
- **THEN** the `s:18` record's `dec` SHALL carry `bmc:1` and `inp:"typed"`

#### Scenario: rotation written on every step

- **WHEN** a run of 150 steps holds the display in portrait for the first 100 and in landscape (rotation 1) for the last 50
- **THEN** the first 100 records SHALL carry `rot:0` and the last 50 `rot:1`
- **AND** a record whose step could not read the rotation SHALL carry `rot:-1`

#### Scenario: component trigger recorded on the step it fired in

- **WHEN** in cell `as` at step 210 `ComponentTriggerStage` wins its coin and dispatches the broadcast receiver `com.foo.BootReceiver`, and SATA takes step 210
- **THEN** the `s:210` record's `dec` SHALL carry `src:"sata"`, `own` with `i:"sata"` and `trig:{"k":"broadcast","c":"com.foo.BootReceiver"}`
- **AND** `trig` SHALL reach the record through `EventSink.componentTrigger("broadcast","com.foo.BootReceiver")`, called after the step's `sink.decision(...)` call
- **AND** the record SHALL carry no `comp`, which only a launcher step writes

### Requirement: Run-Level Records

The sink SHALL carry the out-of-step records in the same NDJSON stream: `RUN_START` (emitted by the run-spec capability as the first record; carries `run_id`, the epoch base `t0`, and the effective plan — including the LLM parameters previously on the retired `[APE-LLM-CONFIG]` manifest), `MOP_DATA` (the load census, below), `PIPELINE` (assembly provenance, below), `LLM_ACK` (`server_model`, at most once, on the first successful chat response), and `RUN_END`.

**`MOP_DATA` — the load census, and the field that has been misread.** The record is written by `StaticArtifactLoader` on every load, which happens if and only if the plan mounts `STATIC` or `MOP` (design D-B14.20); a path given with neither group mounted is inert and produces no record. It describes the input, not a decision, so it is written in the cells `as` and `as_llm` as well, and the clean-control gate excludes it by name (design D-B14.9). The record SHALL carry `status`, optional `reason` on rejection, `formatVersion` (the format of the loaded artifact, `2`; `0` on a rejection, where a version read is by definition not one the jar can vouch for), `sourceDigest` when the load reached one, and on success `package`, `windows`, `widgets`, `flagged`, `droppedNoId`, `wtgEdges`, `handlersUnmatched`, `syntheticLambda`, `recovered`, `mopActivities`, `mopActsAugmented`, `components`, `targets`, `handlers` and `droppedPairs` (malformed distance pairs dropped at load, `mop-guidance` loader). Apart from the three provenance fields and the three Part B counts, this is the set the retired `[APE-MOP-DATA]` line carried, with one deliberate substitution: the line's `transitions` field is **not** restored, and `wtgEdges` replaces it.

The three counts come with wire format 2 of the compact artifact (`mop-guidance`, `static-analysis-entrypoints`). `targets` is the artifact's `targets` value, the length of the target list every distance pair indexes, and therefore the bound of every index in `dec.ret`. `handlers` is the number of classes the artifact's `handlers` table lists. `droppedPairs` is the number of malformed distance pairs the loader dropped; a conforming generator emits none, so a non-zero value is a generator↔jar skew signal. Together with `RUN_END.counters.stampNodes` and `stampHits` they say whether the stamp-first lookup had anything to work with.

That substitution is the point of the requirement, so it is stated rather than left to the field list. The three WTG-family passes — `WtgPass` (MOP), `NavFrontierPass` (STATIC, mounted by `NAV_FRONTIER`) and `MopFrontierPass` (MOP) — gate on `StaticGuiModel.hasWtgData()` as well as on their member feature, and `hasWtgData()` is `!wtgTransitions.isEmpty()` — a **click-only** convenience view, keyed by base activity. What the retired line logged as `transitions=N` was the **flat** list of the producer's transitions — a different structure, and not the one the gate reads. Over the decisive campaign's 40 applications, 11 have zero transitions, **14 have transitions but no click event at all**, and 15 have at least one click edge. Restoring `transitions` would restore the misreading; `wtgEdges` is the number the gate actually reads.

A separate boolean `has_wtg_data` SHALL NOT be emitted: `hasWtgData()` is exactly `wtgEdges > 0`, and a second field would be a second place the same truth is written.

**`PIPELINE` — assembly provenance including what was *not* assembled.** The record SHALL carry `passes` (the enabled scoring passes, in pipeline order, as `ScoringPipeline.passNames()` returns them) and, beside it, `candidates`: every one of the seven candidate passes — `MopWidgetPass`, `MenuGatewayPass`, `WtgPass`, `NavFrontierPass`, `MopFrontierPass`, `CoveragePass`, `FormCompletionPass` (`scoring-pipeline`) — with `{name, enabled}`. It SHALL also carry the decision pipeline's side (`decision-pipeline`, design D-B23): `stages`, the names of the stages as assembled, in pipeline order (the list the `[APE-ARCH] stages=[…]` Logger line echoes); and `stageCandidates`, every one of the nine candidate stages in the fixed order `Budget`, `Restart`, `LlmNewState`, `LlmStagnation`, `LlmCoin`, `MopLauncher`, `ComponentTrigger`, `MopCoin`, `SataChain`, each as `{name, enabled}` — the shape of the pass census. `candidates` SHALL keep its meaning, the scoring-pass census; the stage census is the separate, additive member `stageCandidates`, so a reader of `candidates` is unaffected. The stage side SHALL be a static declaration of candidate stage names, not constructed objects — a feature absent from the plan has no stage (decision-pipeline INV-DP-03), and the census SHALL NOT reintroduce a disabled-stage object to enumerate.

A `reason` field on the pass entries SHALL NOT be emitted. Each gate is the member feature (`RUN_START.features`) and, for the WTG family, `hasWtgData()` (`MOP_DATA.wtgEdges`), so every conjunct is already recoverable from the same trace, and a reason field would encode the order in which each constructor happens to evaluate its conjuncts rather than the cause.

The census is what makes the pass list readable as a data-dependent outcome instead of a configuration echo: in the decisive campaign the WTG family was never constructed in 25 of the 40 applications, with nothing in the trace saying so except the absence of names from a list. Without `candidates`, "the cell turned this off" and "this application's data could not support it" remain indistinguishable.

#### Scenario: MOP data load recorded

- **WHEN** in a cell that mounts `STATIC` or `MOP` the format-2 artifact loads successfully with 12 windows, 340 widgets, 9 targets and 57 handler classes
- **THEN** the trace SHALL contain `{"type":"MOP_DATA","status":"loaded","formatVersion":2,...,"windows":12,"widgets":340,...,"targets":9,"handlers":57}` as its own line
- **AND WHEN** the same path is given in the `base` plan with neither group mounted
- **THEN** no `MOP_DATA` record SHALL be written

#### Scenario: transitions present, click edges absent

- **WHEN** in the `as_mop` cell an application's static JSON yields 29 transitions, none of which carries a `"click"` event
- **THEN** the `MOP_DATA` record SHALL carry `wtgEdges:0` and SHALL carry no `transitions` field
- **AND** the `PIPELINE` record's `candidates` SHALL list `WtgPass`, `NavFrontierPass` and `MopFrontierPass` with `enabled:false`, while `passes` lists only the four that were constructed

#### Scenario: a pass disabled by weight rather than by data

- **WHEN** the application has 7 click edges and the `as_mop` cell sets `ape.mopFrontierWeight=0`
- **THEN** `MOP_DATA.wtgEdges` SHALL be `7`, `PIPELINE.candidates` SHALL carry `MopFrontierPass` with `enabled:false`, and `RUN_START.features` SHALL lack `MOP_FRONTIER`
- **AND** the three facts together SHALL identify the cause without any `reason` field on the record

#### Scenario: the stage census lists every candidate stage

- **WHEN** a run of the `as` cell assembles its decision pipeline (no `LLM`, no `MOP`)
- **THEN** the `PIPELINE` record's `stageCandidates` SHALL list, in this order, `Budget`, `Restart`, `LlmNewState`, `LlmStagnation`, `LlmCoin`, `MopLauncher`, `ComponentTrigger`, `MopCoin`, `SataChain`, each with `{name, enabled}`, with `enabled:false` on `LlmNewState`, `LlmStagnation`, `LlmCoin`, `MopLauncher` and `MopCoin`
- **AND** `stages` SHALL list exactly the names whose `stageCandidates` entry is `enabled:true`, in the same order
- **AND** `candidates` SHALL still be the seven-entry scoring-pass census

#### Scenario: Server model acknowledged once

- **WHEN** the first successful LLM response reports model `qwen3-vl-8b` and 500 further calls succeed
- **THEN** exactly one `LLM_ACK` record with `server_model:"qwen3-vl-8b"` SHALL appear in the trace

#### Scenario: a format-1 artifact is rejected with its format named

- **WHEN** the pushed `.mop.json` declares `formatVersion: 1` in a cell that mounts `STATIC` or `MOP`
- **THEN** the `MOP_DATA` record SHALL carry `status:"rejected"`, `reason:"version-mismatch"` and `formatVersion:0`
- **AND** its census counts, `targets` and `handlers` among them, SHALL be absent or `0`

### Requirement: RUN_END As the Natural Last Record

Teardown SHALL emit `{"type":"RUN_END","reason":...,"detail":...,"steps":N,"t_first_step":...,"t_last_step":...,"counters":{...}}` as its last sink action (after `flushPendingStep` and all other agent teardown steps), where `reason` ∈ `timeout` | `crash` | `unknown` (`RunContext.terminationReason()`), `detail` is present only when the termination recorded one, `steps` is the number of step records written, and `t_first_step`/`t_last_step` are present only when `steps > 0`. The record carries no `run_id` (INV-SNK-04). `counters` holds, in this order: the dictionary counts `acts` and `states`; the three MOP-guidance counters (design D23); the nine routing counters (`RunCounter`, design § Telemetry contract); and, exactly when `LLM` is in the plan, the nested object `llm` with the LLM aggregates of `LlmTelemetry.counters()` (`calls`, `tok_in`, `tok_out`, `ms`, `matched`, `llm_tap`, `no_match`, `dead_pair`, `repaired`, the seven failure-cause counters `timeout`, `http_error`, `conn_error`, `parse_error`, `image_error`, `internal_error`, `screenshot_failed`, and `breaker_trips`), written whole with zeros included.

The MOP-guidance counters:

- `stampNodes`: the number of nodes, over every GUI tree captured in the run, that carried at least one handler stamp (`rvsec.click` or `rvsec.longClick`, `ui-tree` "Handler Stamp Capture"). A node is counted once per capture it appears in.
- `stampHits`: the number of steps whose chosen action's lookup, the one `dec.mops` records, had source `STAMP`: at most one per step. Lookups made by the other readers (the scoring of the actions not chosen, the MOP coin's eligibility, the prompt marker, retirement) are not counted.
- `retired`: the number of targets retired by counting by the end of the run, which equals the number of indices the run's step records carry in `dec.ret`, except for a retirement whose step record was lost. With `ape.mopRetireAfter <= 0` every target reads as retired from the start without being counted, so `retired` is `0` (author, 2026-10-09; review NT-01).

The routing counters:

- `launches`: steps taken by the MOP launcher (`dec.src:"launcher"`).
- `gateMinActions`: steps on which the LLM was the intended owner and `MinActionsGate` barred it (`dec.own.g:"min_actions"`).
- `gateBreaker`: steps on which the LLM was the intended owner and `BreakerGate` barred it (`dec.own.g:"breaker"`).
- `guardBacks`: BACK events injected by the foreign-activity guard and the tree-package guard. A guard BACK is not a step and has no step record, so this counter is its only trace.
- `bufAbandoned`: steps on which a preempting owner abandoned a non-empty SATA buffer (`dec.buf` present).
- `restarts`: steps taken by the RESTART guard (`dec.src:"restart"`).
- `coinLlm`: steps that reached `LlmCoin` with the step's one LLM call unspent and `u_L < p_L` (no guard and no LLM trigger stage took the step), whatever the outcome (design D-B25). A step on which an earlier LLM stage already spent the step's one call SHALL NOT be counted: `LlmCoin` returns `Continue` on it without consulting the coin (`llm-routing` "Declared LLM Fallback").
- `coinMop`: steps on which the MOP coin was won (eligible and `uM < p_M`).
- `nullRelaunches`: null-tree recoveries that stopped the package under test and restarted its main activity (`component-triggering` "Launcher-Owned Null-Tree Recovery", design D-B16). A recovery is not a step and has no step record, so this counter is its only trace.

Every counter outside `counters.llm` SHALL be written on every run, in every cell, so that a zero is a measurement rather than an omission; `counters.llm` is absent on a plan without `LLM`, where no LLM unit exists to count: on an APK instrumented without `--stamp-handlers` `stampNodes` is `0` and every lookup falls back to the resource-id key; on a run without `Feature.MOP` `stampHits`, `retired`, `launches` and `coinMop` are `0` (INV-SNK-18); on a run without `Feature.MOP_LAUNCHER` `nullRelaunches` is `0`; under `ape.mopScoring=flag` `retired` is `0`; on a run without `Feature.LLM` `coinLlm`, `gateMinActions` and `gateBreaker` are `0`. Per owner decision D5 this record is write-only: nothing on the Python side reads or validates it, and its absence changes no task status.

**Why the two timestamps, and why not a consumed-budget field.** `t_first_step` and `t_last_step` are the `t` of the first and last step records, on the same clock as every step. Their span against the run's budget is what separates a run that explored for its whole budget from one that was alive and idle — and this corpus contains the second kind. In the decisive campaign one run is recorded `COMPLETED` with 1,871 s of execution time and emitted **150 step records spanning 446 s**: roughly 1,400 s of its budget produced no exploration at all. A `budget_consumed_ms` field would have reported ~1,800 s for that run and flagged nothing, which is why it is not specified here; the same value is in any case derivable from `t_last_step`, and the harness already records wall-clock execution time per attempt host-side. Eight runs of the campaign have a step span under 1,700 s, seven of them concentrated in two applications — a pattern only these two integers make visible at run level.

The attempt ordinal is deliberately **not** a field. The jar does not know it is a retry, and the harness that does know already writes one task record per attempt, with state, both timestamps and the error message. Round-tripping that value through a plan key into a record the harness then reads back would be indirection with one subscriber. This record stays write-only (INV-SNK-09): these fields make a pre-consolidation liveness check cheap to write on the analysis side; they do not implement one, and no task status may come to depend on them.

#### Scenario: Normal timeout termination

- **WHEN** the run ends by time budget (`StopTestingException`)
- **THEN** the last sink record of the trace SHALL be `RUN_END` with `reason:"timeout"` and the counters, including `stampNodes`, `stampHits`, `retired`, `launches`, `gateMinActions`, `gateBreaker`, `guardBacks`, `bufAbandoned`, `restarts`, `coinLlm`, `coinMop` and `nullRelaunches`
- **AND** the LLM decision ratio SHALL be derivable from the counters (not stored)

#### Scenario: SIGKILL loses RUN_END without consequence

- **WHEN** the harness SIGKILLs the process before teardown runs
- **THEN** the trace legitimately ends without `RUN_END` (and with at most one lost `StepRecord`)
- **AND** no Python-side mechanism SHALL treat this as an error (post-hoc timestamp analysis remains the detection path, D5)

#### Scenario: unstamped APK reports zero stamps

- **WHEN** a MOP cell runs on an APK instrumented without `--stamp-handlers`
- **THEN** `RUN_END.counters` SHALL carry `stampNodes:0` and `stampHits:0`
- **AND** every step record's `dec.mops`, where present, SHALL be `"i"`

#### Scenario: routing counters written in full on the control cell

- **WHEN** a `base` run ends by timeout after 1,900 steps, none taken by a guard, with the foreign-activity guard off
- **THEN** `RUN_END.counters` SHALL carry `launches:0`, `gateMinActions:0`, `gateBreaker:0`, `guardBacks:0`, `bufAbandoned:0`, `restarts:0`, `coinLlm:0`, `coinMop:0` and `nullRelaunches:0`, each written

#### Scenario: coin counters match the step records

- **WHEN** an `as_mop_llm` run with `p_L = 0.9` writes 1,000 step records, 612 of which were taken by no guard and by no LLM trigger stage, reached `LlmCoin` with the step's LLM call unspent and carry `dec.own.uL` below `0.9`, and 140 of which carry `dec.src:"mop"`
- **THEN** `RUN_END.counters.coinLlm` SHALL be `612` and `coinMop` SHALL be `140`

#### Scenario: a step whose call was already spent does not count as a coin win

- **WHEN** on an `as_llm` step `LlmNewState` makes the step's one LLM call, the call yields no action, and the step reaches `LlmCoin` with `u_L < p_L`
- **THEN** `LlmCoin` SHALL return `Continue` without consulting the coin
- **AND** `RUN_END.counters.coinLlm` SHALL NOT count that step

### Requirement: Logcat Heartbeat

When `TelemetryParams.heartbeat` is on (property `ape.telemetryHeartbeat`, default `true`), the sink SHALL write one logcat line per step via `Log.i` — `s=<step> t=<tRelMs>` with a fixed tag — at the same point the step envelope is captured. The heartbeat is write-only (INV-SNK-10): it exists so violations and steps share one file and clock for the analysis-side join; the jar never reads logcat, and no runtime APE↔logcat coupling exists.

**The tag is not a free choice, and the capture side carries it.** rv-platform does not dump the ring buffer after the run; it streams `adb logcat -v threadtime -s <tags>` into the task's logcat file for the run's duration, a strict tag allowlist whose baseline is `LogcatManager.default_tags` — `RVSEC`, `RVSEC-COV`, `ApeRvHb`, `RVSEC-OCC`, `RVSEC-BIND` (rv-android INV-PLT-21). A heartbeat under any other tag is filtered out at the device and **never reaches the file the join reads** — the mechanism would be silently inert. The heartbeat's tag SHALL therefore be the one declared here and present in that allowlist (INV-SNK-14); `clock_logcat_join.py` joins steps and violations on the heartbeat, through the NDJSON reader (`aperv-tool` "Native NDJSON Trace Reader").

**The tag is `ApeRvHb`, a settled cross-repository constant.** `rvsec` declares `TAG_APERV_HEARTBEAT = "ApeRvHb"` in one place and lists it in `LogcatManager.default_tags`, and `clock_logcat_join.py` matches the heartbeat line against that tag. The jar SHALL emit under this exact literal — it is 7 characters, comfortably inside the capture side's 23-character bound — and SHALL declare it in a single constant whose comment names the counterpart declaration site, because the two literals have to be compared by a human and the failure of a mismatch is silent on both sides: the device drops the lines, the capture file looks ordinary, and the join degrades to the fallback it was supposed to retire.

Two facts about the capture side bound the tag. First, the baseline tag set is what the capture uses with the harness's diagnostics flag off; with the flag on, diagnostic tags are added to it. The allowlist is strict either way, and the heartbeat tag is in the baseline, so it is captured in both. Second, the capture side validates tag length at 23 characters, so the declared tag SHALL fit that bound. The tag is named above, and the reason it is stated in the requirement rather than left to implementation is that a wrong choice fails silently rather than loudly.

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

Telemetry SHALL be always-on and identical for every cell — no plan key disables or alters it, so no cell runs telemetry-blind; the recorded substitute for an arm-level switch is INV-SNK-07. The no-op sink exists solely for the neutrality test, which SHALL be a permanent gate: the parity harness run with the real sink and the no-op sink under the same seed SHALL produce identical action sequences. `SinkNeutralityTest` SHALL run that comparison once per cell preset — `base`, `mop`, `as`, `as_mop`, `as_llm`, `as_mop_llm` — each with the artifact and the LLM fake its cell needs, so that the real control (`as`, with the artifact loaded and MOP off) is among the presets compared (INV-SNK-19).

Every computation that this change adds to produce a telemetry value SHALL be pure (INV-SNK-16): no draw from any RNG stream, no call to `State.resolveAction` or any other node resolution, and no effect on a cache that a decision reads. The counterfactuals follow `MopCounterfactual`'s mould, generalized to `ScorerCounterfactual`: set one scorer field to `0` on the pick's candidates, replay the recorded draw index with no new draw, and compare. `dec.nav.alt` is read off the mounted variant's own decision and evaluates no other variant. `dec.own.uL` and `uM` record draws the pipeline makes at step open whether or not they are written (`decision-pipeline`, design D-B9), so recording them consumes nothing.

#### Scenario: Sink on/off equivalence (R7)

- **WHEN** the parity harness replays each of the six cell presets under seed 1234 with `NdjsonSink`, then with `NoopSink`
- **THEN** the two action sequences SHALL be identical for every cell

#### Scenario: All arms carry the same telemetry

- **WHEN** the control cell (`as`) and the full cell (`as_mop_llm`) each run
- **THEN** both traces SHALL contain one `StepRecord` per step with the same schema, each carrying `dec.src`, `dec.own` and `dec.rot`
- **AND** the control's records SHALL differ only by the members whose mechanisms its plan does not mount

#### Scenario: counterfactual consumes no draw

- **WHEN** the ε roulette of step 42 draws once, and `dec.cfc` is computed for `cov` and `form`
- **THEN** the helper stream's position after step 42 SHALL equal its position in the same run with `NoopSink`
- **AND** `State.resolveAction` SHALL be called no more times than in that run

### Requirement: Volume Rules

The sink SHALL apply the four volume levers of owner decision D2: envelope once per step (never per sub-event); defaults omitted per INV-SNK-05; dictionary IDs for activity/state strings; `run_id` only in `RUN_START` (INV-SNK-04). The per-step cost SHALL NOT exceed the retired line family's (~1 stdout write per step; reused accumulator) — a requirement about **wall-clock throughput**, since the run budget is wall-clock (`--running-minutes`) and a lost step is priced at 0.037–0.052 pp `cov_mop`.

That clause SHALL be discharged by measurement, not by argument, because the argument available is incomplete: it counts stdout writes, while the write path also carries a per-step logcat write, prompt dumps on by default with per-character escaping the `key=value` format never performed, and a full-trace gzip on the collection path including the timeout path. Whether those net out below the retired family is an empirical question. Conformance is the measurement of INV-SNK-13, and it is a stage gate: a throughput regression beyond the measured noise blocks the stage rather than being accepted as a cost of better telemetry.

**Two cautions on how the exchange rate is used, so it is not quoted as harder than it is.** The 0.037–0.052 pp `cov_mop` per lost step is an in-sample band from the calibration report, which flags it as having extreme leverage and gives 0.0292 out of sample, with throughput explaining roughly 69 % of the variation. It is the right order of magnitude for deciding whether a per-step field is worth its bytes; it is not a conversion constant, and it prices *steps*, not bytes — the marginal cost per byte of the shipped write path is exactly what INV-SNK-13's measurement establishes and nothing else asserts. The per-step fields beyond the retired family (`mopx`, `comp`, and the members of `isolate-guidance-groups`: `own` and `rot` on every step, `strat` on every SATA step, `cfc`, `draw`, `nav`, `inp`, `restart`, `bmc`, `buf` on a subset) are therefore gated on that measurement together with everything else. `mopx` is present on essentially every MOP-cell step and is the first to drop if the gate binds; `own` and `rot` are kept, because the step's routing and the screen's orientation are what the completeness rule exists to record.

#### Scenario: step throughput does not regress

- **WHEN** one cell runs the same APK, seed and wall-clock budget twice on the pre-change jar and once on the post-change jar
- **THEN** the two pre-change runs SHALL establish the measurement's own variation
- **AND** the post-change steps-per-minute SHALL NOT fall below the pre-change mean by more than that variation
- **AND** a larger drop SHALL block the stage, with the measured numbers recorded either way

#### Scenario: Defaults omitted

- **WHEN** a step's action carries zero for all seven scorer fields (`mop`, `mopf`, `wtg`, `navf`, `cov`, `menu`, `form`), the BACK/MENU cap removed nothing, and its outcome is not a new state
- **THEN** the record SHALL contain none of the seven scorer keys, no `bmc`, no `new_state`, and no `act_changed`
- **AND** it SHALL still contain `own` and `rot`

#### Scenario: Envelope not repeated across sub-events

- **WHEN** step 42 makes its one LLM call and its record's `llm` array holds that call's entry with the prompt dump
- **THEN** `s`, `t`, `act`, `st` SHALL each appear once in the record, and no `llm` entry SHALL carry a step or activity field
