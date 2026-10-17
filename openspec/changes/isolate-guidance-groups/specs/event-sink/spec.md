# Delta: Event Sink (isolate-guidance-groups)

## Purpose

`isolate-guidance-groups` splits APE-RV into five behaviour groups (BASE, ADDONS, STATIC, MOP, LLM) mounted by plan declaration, and turns each step into a fixed sequence of owners: the guards (Budget, restart), the LLM triggers and coin, the MOP launcher, the MOP coin, and SATA with its scorer chain (design § Architecture, D-B7). Until this change the step record carried only the winner: `dec.src` was the largest boost rather than the cause, the counterfactual `dec.cf` zeroed only the MOP boosts, and the roulette draws, the restart, the BACK/MENU cap, the typed text, the ABA tie and the path inside SATA left no trace (plan §2.7). A cell without MOP could not be shown to carry no MOP signal.

This delta states the telemetry contract of the change (design § Telemetry contract, plan §5):

- new `dec` members `own`, `cfc`, `draw`, `nav`, `inp`, `restart`, `bmc`, `strat`, `rot`, `buf`, `trig` and the scorer field `navf`; new `dec.ch` channels `budget`, `restart` and `mop_coin`; `dec.src` names the owner that executed the step; `dec.cf` and `dec.wtgsrc` are removed;
- new `RUN_END.counters`: `launches`, `gateMinActions`, `gateBreaker`, `guardBacks`, `bufAbandoned`, `restarts`, `coinLlm`, `coinMop`;
- `RUN_START.variant`, from `ape.variant`;
- a completeness rule (every mechanism that can decide or change a step appears in the trace), a purity rule for every new computation, a clean-control gate, and `SinkNeutralityTest` run with one preset per cell.

## Invariants

- **INV-SNK-05** (amended by `isolate-guidance-groups`, which removes `dec.cf` and adds `dec.own` and `dec.rot` to the exemptions): A field whose value equals its documented default (boost `0`, boolean `false`, empty `llm` list, empty `dec.ret`, `dec.bmc` `0`) SHALL be omitted; absence = default. Fields whose *absence is itself information* are exempt and SHALL be emitted explicitly whenever defined: `dec.patched` (tri-state: absent = no resolved target; `0` and `1` both emitted), `dec.mopd` (absent = the chosen action has no MOP weight under `ape.mopScoring=distance`; `0` is emitted, because it means the action's handler is itself a target), `dec.own` (written on every step record), and `dec.rot` (written on every step record, a value in `-1..3`; `0` is emitted).
- **INV-SNK-15**: `dec.src` SHALL name the owner that executed the step, one of `budget`, `restart`, `llm`, `launcher`, `mop`, `sata`, and `dec.a` SHALL be the action that the step dispatched. `dec.own.i` SHALL name the intended owner; when `dec.own.i` differs from `dec.src`, `dec.own.fb` SHALL be present and name the reason.
- **INV-SNK-16**: Every computation that produces a telemetry value of this change (`own`, `cfc`, `draw`, `nav`, `inp`, `restart`, `bmc`, `strat`, `rot`, `buf`, `trig`, the new counters) SHALL be pure: it SHALL NOT draw from any RNG stream (agent, helper, or the four `StepRng` streams `llmCoin`, `mopCoin`, `componentCoin` and `fuzz`), SHALL NOT call `State.resolveAction` or any other method that resolves or re-resolves a node, and SHALL NOT read or write a cache whose content a decision later reads. Counterfactuals SHALL replay a recorded draw index, never draw again (the `MopCounterfactual` mould).
- **INV-SNK-17** (completeness): Every mechanism that can decide or change a step SHALL appear in the trace — in `RUN_START`, in the step record's `dec`, or in `RUN_END.counters` — with the fact that it acted. The mapping is the table in "Telemetry Completeness".
- **INV-SNK-18** (clean control): In a run whose plan does not contain `Feature.MOP` (cells `base`, `as`, `as_llm`), no `ACT` dictionary entry SHALL carry a `mop` member, no step record SHALL carry `dec.mop`, `dec.mopf`, `dec.wtg`, `dec.menu`, `dec.mopd`, `dec.mops`, `dec.ret`, `dec.mopx` or `dec.own.uM`, no `dec.cfc` key `mop`, `mopf`, `wtg` or `menu` (the MOP scorers), no `dec.nav.c` equal to `"mop"`, no `dec.ch` equal to `"mop_coin"` or `"launcher"`, and no `dec.src` or `dec.own.i` equal to `"mop"` or `"launcher"`; no `[APE-RV] UICOV` coverage-dump line SHALL carry `mopReach`, which is omitted when `MOP` is not mounted (design D-B14.21); `RUN_END.counters.coinMop`, `launches`, `stampHits` and `retired` SHALL be `0`.
- **INV-SNK-19**: `SinkNeutralityTest` SHALL run once per cell preset (`base`, `mop`, `as`, `as_mop`, `as_llm`, `as_mop_llm`), each with the data and dependencies that cell needs, and SHALL find identical action sequences with `NdjsonSink` and `NoopSink` under the same seed in every cell.

## MODIFIED Requirements

### Requirement: StepRecord Schema

The sink SHALL emit one `StepRecord` per exploration step as a single-line JSON object with the following members. Field presence follows INV-SNK-05 (defaults omitted; tri-state exemptions explicit).

**Envelope** (once per record): `s` (step counter), `t` (milliseconds since `RUN_START`; epoch time = `RUN_START.t0 + t`), `act` (integer `ACT` dictionary ID of the current activity), `st` (integer `STATE` dictionary ID of the current abstract state).

**`dec`** (decision): `a` (the full action string, JSON-escaped; the action the step dispatched), `src` (the owner that executed the step, below), `ch` (pick channel label, below), `own` (the routing record, below; every step), `rot` (the display rotation, below; every step). Model-action records additionally carry `pri` (priority) and the non-zero scorer fields `mop`, `mopf`, `wtg`, `navf`, `cov`, `menu`, `form`; `mopx` (`[boosted, total]`, the MOP exposure pair, present exactly when the MOP widget pass is constructed); `mopd` and `mops` (below, the MOP lookup behind `mop`); `ret` (below, the targets the step's action retired); `patched` (0/1, present exactly when the action has a resolved target node). The members `cfc`, `draw`, `nav`, `inp`, `restart`, `bmc`, `strat`, `buf` and `trig` are written when their condition below holds. Non-model records carry `a`/`src`/`ch`/`own`/`rot` and the conditional members that apply, and launcher records additionally carry `comp` (below).

**`dec.src` — the executed owner.** `src` is one of `budget`, `restart`, `llm`, `launcher`, `mop`, `sata`, written by the pipeline stage that took the step (`decision-pipeline`, design D-B7). Its meaning changed with `isolate-guidance-groups`: it was the decision source of the largest boost, and it is now the owner that executed the step. A scorer never sets `src`: scorers adjust SATA's candidates, and a step that SATA decides is `sata` whatever the scorers wrote (INV-SNK-15). `RUN_START.build` tells a trace written before this change from one written after it.

**`dec.ch` — the pick channel.** `ch` survives as the channel label of the pick (design D-B14.17): `roulette_greedy`, `roulette_early`, `buffer` and `sata_other` for SATA's paths (`sata_other` covers SATA's other paths only, never a guard step), `budget` for a step the Budget guard takes, `restart` for a step the RESTART guard takes, `launcher` for a launcher step, `llm` for an LLM step, and `mop_coin` for a step the MOP coin takes. The values `short_circuit_unvisited` and `short_circuit_0step` SHALL NOT be produced: the MOP shortcut they named left SATA. `ch` complements `src`, which names the owner, and `strat`, which names the SATA path.

**`dec.own` — who was meant to take the step, and why another did.** Shape: `{"i":<owner>, "trg":"new_state"|"stagnation"|"coin", "uL":<double>, "uM":<double>, "g":<gate>, "fb":<reason>}`. It SHALL be written on every step record (INV-SNK-05 exemption).
- `i`: the intended owner, the first claimer of the step in pipeline order (design D-B14.6): a guard that is due (`budget`, `restart`), a fired LLM trigger or a won LLM coin (`llm`), a due launcher (`launcher`), a won MOP coin (`mop`) — otherwise `sata`. A lost coin, an ineligible MOP coin and a launcher below its cadence claim nothing.
- `trg`: present when `i` or `src` is `llm`: `new_state` or `stagnation` for the two triggers, `coin` for the `p_L` coin. A step makes at most one LLM call (`llm-routing` "Declared LLM Fallback"), so when an LLM stage called, `trg` names the stage that made the step's one call; when no stage called because a gate barred, it names the first LLM stage whose trigger held.
- `uL`: the LLM coin's uniform draw for the step, present exactly when `Feature.LLM` is in the plan. `uM`: the MOP coin's uniform draw, present exactly when `Feature.MOP` is in the plan. Both are drawn at step open from their own streams, before any gate (`decision-pipeline`, design D-B9), so they are present whether or not their coin was consulted.
- `g`: present when `fb` is `gate`: `min_actions` or `breaker`, the gate that barred the LLM.
- `fb`: present exactly when `src` differs from `i`, giving the reason: `gate` (an LLM gate barred the call), `error` (the LLM call failed), `decline` (the model answered without an action the router can take), `no_match` (the answer was refused at mapping, including `dead_pair`, `boundary` and `degenerate`; `llm-routing` "Declared LLM Fallback"), `ineligible` (the launcher was due and found no eligible candidate).

**`dec.cfc` — the counterfactual per scorer.** Shape: `{"<scorer>":0|1}`, one key per mounted scorer whose term is non-zero on at least one action of the pick's candidate set, among `mop`, `mopf`, `wtg`, `navf`, `cov`, `menu`, `form`. Value `1` when the pick, recomputed with that scorer's field set to `0` on every candidate and with the same recorded draw index, selects another action; `0` when it selects the same action or the recomputation failed. It SHALL be present on the roulette picks (EARLY_STAGE, ε, null handler) and on the least-visited pick, and only there, when at least one key exists. It replaces `dec.cf`, which covered only `mop` and `mopf`. It is computed by `ScorerCounterfactual`, the generalization of `MopCounterfactual`'s mould: zero one field, replay the same draw index, compare (INV-SNK-16).

**`dec.draw` — the roulette draw.** Shape: `[idx, total]`: the integer the roulette drew and the bound it was drawn below, so that the pick can be replayed offline. Present on the roulette picks (EARLY_STAGE, ε, null handler).

**`dec.nav` — the SATA extension point that decided a tie.** Shape: `{"x":"X1"|"X3"|"X4", "c":"ape"|"mop", "alt":0|1, "drew":0|1}`. Present when an extension point (`action-selection` X1 least-visited tie, X3 ABA target tie, X4 path to a trivial activity; design D-B12) decided between two or more candidates. `x` names the point, `c` the mounted variant (`mop` when the point's MOP variant is mounted, `ape` otherwise), `drew` is `1` when the decision consumed a draw (X4's `randomPick`). `alt` is `1` exactly when the MOP variant decided by its own rule: at X3, the candidate replaced the incumbent; at X4, the strictly largest end-state density chose the path, without a draw. `alt` is `0` when the MOP variant fell back to the APE rule (X3 kept the incumbent; X4 found the densities equal and drew). X1 has no MOP variant: its record always carries `c:"ape"` and `alt:0`. In a run without `Feature.MOP` no MOP variant is mounted and `alt` is `0`. `alt` is read off the mounted variant's own decision; no unmounted variant is evaluated to compute it.

**`dec.inp` — the origin of the text the action entered.** One of `typed`, `heuristic`, `random`, `form`, `llm` (`heuristic-input`, `form-completion`). Present exactly when the step's input refiner set a node's input text.

**`dec.restart` — the kind of restart.** One of `graph`, `state`, `activity`, `periodic`. Present exactly when `src` is `restart`.

**`dec.bmc` — the BACK/MENU cap in this pick.** Integer: the number of candidates the BACK/MENU cap (`ape.backMenuPickCap`, ADDONS) removed from the pick that selected the action. Omitted when `0`.

**`dec.strat` — the path inside SATA.** One of `buffer`, `back_to_activity`, `aba`, `early_forward`, `early_roulette`, `greedy_global`, `greedy_path`, `trivial`, `early_backward`, `backtrack`, `eps_back`, `eps_menu`, `least_visited`, `eps_roulette`, `null`, `saturated`. Present exactly when `src` is `sata`.

**`dec.rot` — the display rotation.** Integer in `-1..3`: the rotation `0..3` the display reported at step open (`llm-infrastructure`), or `-1` when it could not be read. Written on every step record, `0` included (INV-SNK-05 exemption), so the fraction of landscape steps is measurable on every cell.

**`dec.buf` — a buffer abandoned by preemption.** The owner (`budget`, `llm`, `launcher`, `mop`) that took the step while the SATA buffer was non-empty and abandoned it through `abandonBuffer(owner)`, without weakening the planned edge (`action-selection`, P-4, design D-B14.13). A restart step carries no `buf`: the restart starts a new episode, which clears the buffer, also without weakening (`decision-pipeline`). Absent when no buffer was abandoned.

**`dec.navf` — the navigation frontier term.** The non-zero boost written by `NAV_FRONTIER` (STATIC), in its own field. `wtg` is written by the WTG pass (MOP) alone; the two producers no longer share a field, so `dec.wtgsrc` is removed.

**`dec.mopx` — the exposure denominator, which is otherwise lost in this change.** The jar emits `[APE-RV] MOP boost: state=…, boosted=X/Y, …` once per step today, 1:1 with `[APE-STEP]`, and `X/Y` is the only record of how many actions on the screen were even *eligible* for MOP steering. Without it, "the MOP scorer fired on 0.4 % of decisions" has no denominator and cannot be separated into "the mechanism had no opportunity" and "the mechanism had one and lost the roulette" — which is the compliance question the whole guidance contrast rests on. The line is **not** among the four families this change's no-information-loss clause covers, and its sibling `[APE-RV] Frontier boost: … boosted=<n>/<total>` **is** normative in the wtg-navigation capability, so leaving it unstated would retire the MOP denominator while preserving the frontier one. It rides the step record rather than the `STATE` dictionary entry: the pair was measured across 25 campaign traces and is **not** constant per abstract state (14 of the 25 show a state with more than one realised pair), so the dictionary encoding that would have made it free is not available. It therefore costs bytes on every guided-arm step and is subject to INV-SNK-13 like any other per-step field.

**`dec.mopd` and `dec.mops` — what the MOP weight rests on** (`llm-coordinate-single-base`, design D23). Both describe the `mop-guidance` lookup (`MopData.lookup`, "Handler Stamp Lookup") that the scoring pass kept for the chosen action — the best of the lookups over the action's containment nodes:

- `mopd` (int): under `ape.mopScoring=distance`, the distance `d` of the nearest unretired target that gave the action its MOP weight, a value in `0..3` (a target at `d ≥ 4` gives no weight). It SHALL be present exactly when the action's MOP weight before the tie split (`mop-guidance` "MopScorer — Priority Boost", the undivided weight the marker and retirement also read) is greater than 0, which is when the record carries `mop` except in the degenerate case of a tie split that rounds to 0 (author, 2026-10-09), and SHALL be emitted when its value is `0` (INV-SNK-05 exemption: `mopd:0` says the handler is itself a target; an absent `mopd` says there is no distance-based weight). Under `ape.mopScoring=flag` it SHALL never be present.
- `mops` (string): `"s"` when the lookup was answered by the node's handler stamp (source `STAMP`), `"i"` when by the resource-id key (source `ID`); absent when the source is `NONE` and on actions with no resolved target. It SHALL be written whenever the chosen action's lookup has source `STAMP` or `ID`, in both scoring modes and whether or not the action carries a weight: a stamp listed with no target (`mop` absent, `mops:"s"`) is the stamp overriding a flagged id key, which is the case the lookup exists for. The lookup recorded is the weight's lookup, the one `MopScorer.weigh` gave the action its weight from; when no containment lookup gives a weight under the active mode (under `distance` a lookup with only a flag, or only retired pairs, gives none), the source recorded is that of the lookup on the action's own target node (author, 2026-10-09).

Both are MOP fields: in a run without `Feature.MOP` no lookup is made and neither is written (INV-SNK-18).

**`dec.comp` — whether the launch landed.** Launcher records (`src:"launcher"`) SHALL carry `comp` with the activity-launch outcome, which `MonkeySourceApe` reports through `componentLaunch(...)`: `{"r":<int>}`, the result the platform returns for the launch, and `{"r":<int>,"e":"<enum>"}` when the dispatch failed. The jar already binds a boolean at the dispatch site and already logs a warning on false; the finer result code is discarded one frame below, where `IActivityManager.startActivity`'s `START_*` return value is dropped. Capturing it introduces no retry, no re-dispatch, no extra IPC and no mutation of the round-robin cursor — it records a value the launch already produced. Ordering is not an obstacle even though it was in the retired line family: the step record is closed at step N+1, so the dispatch result exists before the record is written, whereas `[APE-STEP]` was emitted before dispatch. This guards the campaign's strongest mechanism result — roughly 1,075 direct component launches in the guided arm against 0 in the control — for which a refused intent and an accepted one are otherwise the same trace evidence.

**`dec.trig` — a component the trigger fired.** Shape: `{"k":"broadcast"|"service"|"provider","c":"<class>"}`: the kind of component `ComponentTriggerStage` (STATIC, `component-triggering`) dispatched and its class name. It SHALL be written on the record of the step during which the stage fired, whatever that record's `src`: the trigger is a side effect of the step, not a step owner, so it never sets `src`, `ch` or `own.i`. It reaches the open record through its own sink entry, `componentTrigger(kind, className)`, called after the step's `sink.decision(...)` call. Absent on a step on which the trigger did not fire, and on every step of a plan without `COMPONENT_TRIGGER`. `dec.comp` stays the launcher's activity-launch outcome; a component trigger writes no `comp`.

**`llm`** (optional ordered array): one entry per LLM routing attempt made during the step's selection, in occurrence order — completed calls (`call`, `mode`, `tool`, `qwen:[x,y]`, `px:[x,y]`, `result` ∈ `matched|llm_tap|no_match`, optional `reason` ∈ `dead_pair|degenerate|boundary`, optional `repair` (repair-form provenance), `mcls`, `ncls`, `ndist`, `widgets`, `tok:[in,out]`, `ms`, optional `text`, and — when the prompt-dump flag is on (default) — `sys`/`user`/`resp`/`tool_calls`); abandoned attempts (`result:"error"` with `cause` and `detail`); and the once-per-open-episode breaker decline (`result:"breaker_open"` with `trips`). The array holds at most one call entry, completed or abandoned, because a step makes at most one LLM call (`llm-routing` "Declared LLM Fallback"); a breaker decline is not a call.

**`out`** (outcome, attached at step N+1): `new_state` (omitted when false), `target` (STATE ID of the resulting state), `act_changed` (omitted when false). `activity_has_mop` for both the step's and the outcome's activity is carried by the referenced `ACT` dictionary entries, not repeated per record. A record closed without a resolved outcome has no `out` member; a record flushed at teardown has `out:{"resolved":false}`.

**`dec.ret` — targets this step retired** (design D23 of `llm-coordinate-single-base`). Under `ape.mopScoring=distance`, the integer indices, into the artifact's target list (`MOP_DATA.targets`), of the targets whose retirement counter reached `ape.mopRetireAfter` as a consequence of this step's action — the first interaction of its widget in its activity, counted and applied in `StatefulAgent.moveForward` (`mop-guidance` "Target Retirement"). No count is pending and none is discarded: the restart is a guard stage that runs before selection (`decision-pipeline` "Restart Guard Stage"), so no selected action is replaced after selection and no `checkRestart` override takes part. The indices are not known when the decision is recorded, so they SHALL reach the open record through their own sink entry, `EventSink.mopRetired(int[] indices)`, called from `moveForward`, after the step's `sink.decision(...)` call, when the retirement call returns a non-empty array, after the pattern of `mopExposure` and `componentLaunch`, which also add a member to the step's `dec` after the decision call. `StepRecord` writes `dec` when the record closes, so `ret` sits in `dec` and survives a record closed without an `out` member. Each index appears once, in ascending order. The array SHALL be absent when it would be empty, which is every step under `ape.mopScoring=flag`. A target retires once, so an index appears in `dec.ret` at most once per run.

The members that are known only after the decision call (`inp`, `ret`, `buf`, `comp`, `trig`) reach the open record through their own sink entries (`input(...)`, `mopRetired(...)`, `bufferAbandoned(...)`, `componentLaunch(...)`, `componentTrigger(...)`), called after `sink.decision(...)`. Each entry is pure (INV-SNK-16): it adds a member to the open record and reads nothing a decision reads.

Every field of the retired `[APE-STEP]` (both forms), `[APE-OUTCOME]`, and `[APE-LLM-TEL]`/`[APE-LLM-ERROR]` lines SHALL be either present in this schema or derivable by dictionary lookup within the same trace (the design's mapping table is normative for the correspondence), except the retired line's counterfactual flag and `wtgsrc`, which `dec.cfc` and the separate `navf` field replace.

**The scope of that clause is four line families, and other emissions fall outside it.** They are named here so that "additive only" is not read as a guarantee it does not make. `[APE-MOP-DATA]` and `[APE-ARCH]` are outside the clause — which is why their replacements are specified field-by-field in the run-level requirement rather than inherited, and why the census would otherwise have been lost by omission rather than by decision. `[APE-RV] MOP boost` is outside it too, and is the one emission the clause's absence would have retired silently: it carries the exposure denominator, its sibling `[APE-RV] Frontier boost` *is* normative in the wtg-navigation capability, and it is preserved here deliberately as `dec.mopx` above. (`[APE-LLM-CONFIG-ACK]` and `[APE-LLM-RESPONSE]` are also outside the clause but are not at risk: the former is specified as the `LLM_ACK` record and the latter is mapped onto the `resp`/`tool_calls` fields by the design's mapping table.)

Every `dec` member is telemetry only: none is read by any decision, so they fall under INV-SNK-07 and INV-SNK-16, and `SinkNeutralityTest` stays the gate (INV-SNK-19).

#### Scenario: Model-action step with MOP boost and outcome

- **WHEN** in cell `as_mop` under `ape.mopScoring=flag` step 42 selects a `MODEL_CLICK` through the ε roulette, after the MOP coin found no eligible action (the action's widget already had its first interaction in this activity), with `mopBoost=500`, `menuBoost=250`, all other scorer fields 0, the action's lookup answered by its resource-id key, the roulette drawing 731 below 1290, and the executed action produces a transition to a new state at step 43
- **THEN** exactly one record with `s:42` SHALL be written when step 43's graph update resolves the outcome
- **AND** its `dec` SHALL carry `src:"sata"`, `strat:"eps_roulette"`, `ch:"roulette_greedy"`, `draw:[731,1290]`, `cfc` with the keys `mop` and `menu`, `pri`, `mop:500`, `menu:250`, `mops:"i"`, `rot:0` and `own` with `i:"sata"` and a `uM` value, and no `fb`, because the ineligible MOP coin claimed nothing
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
- **THEN** the `s:42` record's `llm` array SHALL contain one entry with `call:3`, `mode:"new_state"`, `result:"matched"`, `px`, `tok:[1841,25]`, `ms:973`
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

### Requirement: StepRecord Lifecycle

The sink SHALL keep at most one pending `StepRecord`. `beginStep` opens it at selection start (so LLM sub-events preceding decision finalization land in it) and closes a still-pending predecessor without an `out` member. The outcome closure at step N+1 SHALL occur at the same point and under the same single-shot, reference-equality buffered-decision guard that governed the retired `[APE-OUTCOME]` emission. Teardown SHALL invoke `flushPendingStep` as an isolated teardown step, writing any pending record with `out:{"resolved":false}`.

#### Scenario: Outcome joins its step at N+1

- **WHEN** step 10's action executes and step 11's graph update records the transition with the buffered decision reference-equal to the current action
- **THEN** the `s:10` record SHALL be written exactly once, during step 11's update, with its `out` attached

#### Scenario: Legitimate outcome absence

- **WHEN** step 20 is taken by the RESTART guard (`dec.src:"restart"`), whose restart action produces no transition under its own identity, and step 21 begins
- **THEN** the `s:20` record SHALL be written without an `out` member — distinguishable from a flushed record (`out.resolved:false`) and from a resolved one

#### Scenario: In-flight step flushed at teardown

- **WHEN** the run's time budget expires while step 199's outcome is still unresolved
- **THEN** `flushPendingStep` SHALL write the `s:199` record with `out:{"resolved":false}` before `RUN_END` is emitted

### Requirement: Dictionary Events and Run-Local IDs

On first sight of an activity or abstract state, the sink SHALL emit a dictionary record — `{"type":"ACT","id":N,"name":"<activity>"}` (with `"mop":0|1` when `Feature.MOP` is in the plan) or `{"type":"STATE","id":N,"key":"<stateKey>","act":<actId>}` — on its own line before any record references the ID (INV-SNK-06). `mop` on the `ACT` entry records `activity_has_mop` over the selected MOP-activity set; consumers derive the step-side value from `act` and the outcome-side value from `out.target → STATE.act → ACT.mop`. The `mop` member SHALL be written only when `MOP` is mounted (design D-B14.9): in a cell without `MOP` no `MopData` is handed to any consumer, the bit has no source, and its absence is what the clean-control gate checks (INV-SNK-18).

#### Scenario: Definition precedes reference

- **WHEN** step 5 is the first visit to `com.foo/.SettingsActivity` in state `S9`
- **THEN** the trace SHALL contain the `ACT` and `STATE` records for them on lines earlier than the `s:5` record that references their IDs

#### Scenario: activity_has_mop recorded once

- **WHEN** in a cell with `MOP` an activity with MOP reachability is visited 200 times in a run
- **THEN** `mop:1` SHALL appear once, on its `ACT` entry, and on no step record
- **AND WHEN** the same activity is visited in a cell without `MOP` (`as`, which loads the artifact for STATIC)
- **THEN** its `ACT` entry SHALL carry no `mop` member

### Requirement: Run-Level Records

The sink SHALL carry the out-of-step records in the same NDJSON stream: `RUN_START` (emitted by the run-spec capability as the first record; carries `run_id`, the epoch base `t0`, and the effective plan — including the LLM parameters previously on the retired `[APE-LLM-CONFIG]` manifest), `MOP_DATA` (the load census, below), `PIPELINE` (assembly provenance, below), `LLM_ACK` (`server_model`, at most once, on the first successful chat response), and `RUN_END`.

**`MOP_DATA` — the load census, and the field that has been misread.** The record is written by `StaticArtifactLoader` on every load, which happens if and only if the plan mounts `STATIC` or `MOP` (design D-B14.20); a path given with neither group mounted is inert and produces no record. It describes the input, not a decision, so it is written in the cells `as` and `as_llm` as well, and the clean-control gate excludes it by name (design D-B14.9). The record SHALL carry `status`, optional `reason` on rejection, `formatVersion` (the format of the loaded artifact, `2`; `0` on a rejection, where a version read is by definition not one the jar can vouch for), `sourceDigest` when the load reached one, and on success `package`, `windows`, `widgets`, `flagged`, `droppedNoId`, `wtgEdges`, `handlersUnmatched`, `syntheticLambda`, `recovered`, `mopActivities`, `mopActsAugmented`, `components`, `targets`, `handlers` and `droppedPairs` (malformed distance pairs dropped at load, `mop-guidance` loader). Apart from the three provenance fields and the three Part B counts, this is the set the retired `[APE-MOP-DATA]` line carried, with one deliberate substitution: the line's `transitions` field is **not** restored, and `wtgEdges` replaces it.

The three counts come with wire format 2 of the compact artifact (`mop-guidance`, `static-analysis-entrypoints`). `targets` is the artifact's `targets` value, the length of the target list every distance pair indexes, and therefore the bound of every index in `dec.ret`. `handlers` is the number of classes the artifact's `handlers` table lists. `droppedPairs` is the number of malformed distance pairs the loader dropped; a conforming generator emits none, so a non-zero value is a generator↔jar skew signal. Together with `RUN_END.counters.stampNodes` and `stampHits` they say whether the stamp-first lookup had anything to work with.

That substitution is the point of the requirement, so it is stated rather than left to the field list. The three WTG-family passes — `WtgPass` (MOP), `NavFrontierPass` (STATIC, mounted by `NAV_FRONTIER`) and `MopFrontierPass` (MOP) — gate on `StaticGuiModel.hasWtgData()` as well as on their member feature, and `hasWtgData()` is `!wtgTransitions.isEmpty()` — a **click-only** convenience view, keyed by base activity. What the retired line logged as `transitions=N` was the **flat** list of the producer's transitions — a different structure, and not the one the gate reads. Over the decisive campaign's 40 applications, 11 have zero transitions, **14 have transitions but no click event at all**, and 15 have at least one click edge. Restoring `transitions` would restore the misreading; `wtgEdges` is the number the gate actually reads.

A separate boolean `has_wtg_data` SHALL NOT be emitted: `hasWtgData()` is exactly `wtgEdges > 0`, and a second field would be a second place the same truth is written.

**`PIPELINE` — assembly provenance including what was *not* assembled.** The record SHALL carry `passes` (the enabled scoring passes, in pipeline order, exactly what the `[APE-ARCH]` line reports) and, beside it, `candidates`: every one of the seven candidate passes — `MopWidgetPass`, `MenuGatewayPass`, `WtgPass`, `NavFrontierPass`, `MopFrontierPass`, `CoveragePass`, `FormCompletionPass` (`scoring-pipeline`) — with `{name, enabled}`. It SHALL also carry `stages` and the stage `candidates` of the decision pipeline, the fixed order `Budget`, `Restart`, `LlmNewState`, `LlmStagnation`, `LlmCoin`, `MopLauncher`, `ComponentTrigger`, `MopCoin`, `SataChain` (`decision-pipeline`). The stage side SHALL be a static declaration of candidate stage names, not constructed objects — a feature absent from the plan has no stage (decision-pipeline INV-DP-03), and the census SHALL NOT reintroduce a disabled-stage object to enumerate.

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

#### Scenario: Server model acknowledged once

- **WHEN** the first successful LLM response reports model `qwen3-vl-8b` and 500 further calls succeed
- **THEN** exactly one `LLM_ACK` record with `server_model:"qwen3-vl-8b"` SHALL appear in the trace

#### Scenario: a format-1 artifact is rejected with its format named

- **WHEN** the pushed `.mop.json` declares `formatVersion: 1` in a cell that mounts `STATIC` or `MOP`
- **THEN** the `MOP_DATA` record SHALL carry `status:"rejected"`, `reason:"version-mismatch"` and `formatVersion:0`
- **AND** its census counts, `targets` and `handlers` among them, SHALL be absent or `0`

### Requirement: RUN_END As the Natural Last Record

Teardown SHALL emit `{"type":"RUN_END","reason":...,"steps":N,"t_first_step":...,"t_last_step":...,"counters":{...}}` as its last sink action (after `flushPendingStep` and all other agent teardown steps), where `reason` ∈ `timeout` | `crash` | `unknown` and `counters` includes the LLM aggregate counters previously on the retired `[APE-RV] LLM Summary` line (`calls`, `tok_in`, `tok_out`, `ms`, `matched`, `llm_tap`, `no_match`, `dead_pair`, `repaired`, the seven failure-cause counters, `breaker_trips`), the record/dictionary counts, the three MOP-guidance counters of `llm-coordinate-single-base` (design D23), and the eight routing counters of `isolate-guidance-groups` (design § Telemetry contract).

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
- `coinLlm`: steps that reached `LlmCoin` with `u_L < p_L` (no guard and no LLM trigger stage took the step), whatever the outcome — including a step on which an earlier LLM stage already made the step's one call and `LlmCoin` returned `Continue` without calling (`llm-routing` "Declared LLM Fallback").
- `coinMop`: steps on which the MOP coin was won (eligible and `uM < p_M`).

Every counter SHALL be written on every run, in every cell, so that a zero is a measurement rather than an omission: on an APK instrumented without `--stamp-handlers` `stampNodes` is `0` and every lookup falls back to the resource-id key; on a run without `Feature.MOP` `stampHits`, `retired`, `launches` and `coinMop` are `0` (INV-SNK-18); under `ape.mopScoring=flag` `retired` is `0`; on a run without `Feature.LLM` `coinLlm`, `gateMinActions` and `gateBreaker` are `0`. Per owner decision D5 this record is write-only: nothing on the Python side reads or validates it, and its absence changes no task status.

**Why the two timestamps, and why not a consumed-budget field.** `t_first_step` and `t_last_step` are the `t` of the first and last step records, on the same clock as every step. Their span against the run's budget is what separates a run that explored for its whole budget from one that was alive and idle — and this corpus contains the second kind. In the decisive campaign one run is recorded `COMPLETED` with 1,871 s of execution time and emitted **150 step records spanning 446 s**: roughly 1,400 s of its budget produced no exploration at all. A `budget_consumed_ms` field would have reported ~1,800 s for that run and flagged nothing, which is why it is not specified here; the same value is in any case derivable from `t_last_step`, and the harness already records wall-clock execution time per attempt host-side. Eight runs of the campaign have a step span under 1,700 s, seven of them concentrated in two applications — a pattern only these two integers make visible at run level.

The attempt ordinal is deliberately **not** a field. The jar does not know it is a retry, and the harness that does know already writes one task record per attempt, with state, both timestamps and the error message. Round-tripping that value through a plan key into a record the harness then reads back would be indirection with one subscriber. This record stays write-only (INV-SNK-09): these fields make a pre-consolidation liveness check cheap to write on the analysis side; they do not implement one, and no task status may come to depend on them.

#### Scenario: Normal timeout termination

- **WHEN** the run ends by time budget (`StopTestingException`)
- **THEN** the last sink record of the trace SHALL be `RUN_END` with `reason:"timeout"` and the counters, including `stampNodes`, `stampHits`, `retired`, `launches`, `gateMinActions`, `gateBreaker`, `guardBacks`, `bufAbandoned`, `restarts`, `coinLlm` and `coinMop`
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
- **THEN** `RUN_END.counters` SHALL carry `launches:0`, `gateMinActions:0`, `gateBreaker:0`, `guardBacks:0`, `bufAbandoned:0`, `restarts:0`, `coinLlm:0` and `coinMop:0`, each written

#### Scenario: coin counters match the step records

- **WHEN** an `as_mop_llm` run with `p_L = 0.9` writes 1,000 step records, 612 of which were taken by no guard and by no LLM trigger stage and carry `dec.own.uL` below `0.9`, and 140 of which carry `dec.src:"mop"`
- **THEN** `RUN_END.counters.coinLlm` SHALL be `612` and `coinMop` SHALL be `140`

### Requirement: Telemetry Neutrality

Telemetry SHALL be always-on and identical for every cell — no plan key disables or alters it (this deliberately dissolves INV-ARCH-01; the recorded substitute is INV-SNK-07). The no-op sink exists solely for the neutrality test, which SHALL be a permanent gate: the parity harness run with the real sink and the no-op sink under the same seed SHALL produce identical action sequences. `SinkNeutralityTest` SHALL run that comparison once per cell preset — `base`, `mop`, `as`, `as_mop`, `as_llm`, `as_mop_llm` — each with the artifact and the LLM fake its cell needs, so that the real control (`as`, with the artifact loaded and MOP off) is among the presets compared (INV-SNK-19). The test used to run the retired `aperv` preset without data, which is not the control any campaign runs.

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

The sink SHALL apply the four volume levers of owner decision D2: envelope once per step (never per sub-event); defaults omitted per INV-SNK-05; dictionary IDs for activity/state strings; `run_id` only in border records. The per-step cost SHALL NOT exceed the retired line family's (~1 stdout write per step; reused accumulator) — a requirement about **wall-clock throughput**, since the run budget is wall-clock (`--running-minutes`) and a lost step is priced at 0.037–0.052 pp `cov_mop`.

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

## ADDED Requirements

### Requirement: Telemetry Completeness

Every mechanism that can decide or change a step SHALL appear in the trace, with the fact that it acted and, where it is cheap and pure, the counterfactual (INV-SNK-17). The jar records decisions, never outcomes: coverage, violations and scenes are reconstructed offline and stay out of the trace by design. The value of every scorer for every candidate also stays out, because its volume is N candidates × scorers per step; `dec.cfc` answers the causal question without that volume (plan §5, D-A18 Q-13). The mapping is normative:

| Mechanism | Where it appears |
|---|---|
| groups mounted, doses, gates, launcher cadence, extension-point variants, variant name | `RUN_START` (`params`, `features`, `inert`, digest, `variant`) |
| LLM coin, gate that barred, call, action taken, fallback and reason | `dec.own` + `llm[]` |
| MOP coin, eligibility, action chosen | `dec.own` + `dec.mopd` / `dec.mops` |
| each scorer: the term of the chosen action and whether it changed the choice | scorer fields (`mop` `mopf` `wtg` `navf` `cov` `menu` `form`) + `dec.cfc` |
| the path inside SATA and its draw | `dec.strat` + `dec.draw` |
| ties and path choice (X1, X3, X4) | `dec.nav` (and `dec.cfc` for X1) |
| launcher, Budget, restart | `dec.own` (owner = guard or launcher) + `dec.src` + `dec.ch` (`launcher`, `budget`, `restart`) + `dec.restart` + `RUN_END.counters` (`launches`, `restarts`) |
| component trigger (STATIC) | `dec.trig` on the record of the step during which it fired |
| buffer abandoned by preemption | `dec.buf` + `RUN_END.counters.bufAbandoned` |
| BACK/MENU cap, text origin, display rotation | `dec.bmc`, `dec.inp`, `dec.rot` |
| foreign-activity and tree-package guards | `RUN_END.counters.guardBacks` (a guard BACK is not a step) |

A mechanism added later that can decide or change a step SHALL add its row to this table and its field to the trace in the same change.

#### Scenario: every owner of a run is visible

- **WHEN** an `as_mop_llm` run takes steps through the Budget guard, the restart guard, the LLM coin, the launcher, the MOP coin and SATA
- **THEN** each of those step records SHALL carry `dec.src` equal to `budget`, `restart`, `llm`, `launcher`, `mop` and `sata` respectively
- **AND** `RUN_END.counters.restarts` and `launches` SHALL equal the number of records with `src:"restart"` and `src:"launcher"`

#### Scenario: a gate's loss is visible

- **WHEN** at step 7 the LLM coin is won but the screen has 2 actions and `ape.llmGateMinActions=3`
- **THEN** the `s:7` record's `dec.own` SHALL carry `i:"llm"`, `trg:"coin"`, `g:"min_actions"` and `fb:"gate"`
- **AND** the step SHALL be counted in `RUN_END.counters.gateMinActions`

#### Scenario: guard BACKs counted without a step record

- **WHEN** the foreign-activity guard injects 4 BACK events during a run
- **THEN** no step record SHALL be written for those BACKs
- **AND** `RUN_END.counters.guardBacks` SHALL be `4`

### Requirement: Clean-Control Gate

A run whose plan does not contain `Feature.MOP` SHALL carry no MOP signal in its decisions or in its step telemetry (INV-SNK-18). `CleanControlTest` SHALL replay the cells `base`, `as` and `as_llm` and parse their traces, and SHALL fail when any step record carries one of `dec.mop`, `dec.mopf`, `dec.wtg`, `dec.menu`, `dec.mopd`, `dec.mops`, `dec.ret`, `dec.mopx`, `dec.own.uM`, a `dec.cfc` key `mop`, `mopf`, `wtg` or `menu`, `dec.nav.c:"mop"`, `dec.ch` equal to `"mop_coin"` or `"launcher"`, or `dec.src` / `dec.own.i` equal to `"mop"` or `"launcher"`, or when any `ACT` dictionary entry carries a `mop` member, or when an `[APE-RV] UICOV` coverage-dump line carries `mopReach`, or when `RUN_END.counters.coinMop`, `launches`, `stampHits` or `retired` is non-zero. `mopReach` is written only when `MOP` is mounted, because `getMopData()` is null otherwise (design D-B14.21). `ACT.mop` is written only when `MOP` is mounted ("Dictionary Events and Run-Local IDs", design D-B14.9). The `MOP_DATA` record is excluded from the gate by name: it is the census of the loaded artifact, which the `as` and `as_llm` cells write because they load the artifact for STATIC, and it describes the input, not a decision.

The gate holds by construction rather than by filtering: in such a run no MOP scorer, MOP coin, launcher or MOP extension-point variant is mounted, so no MOP value is computed, and an absent field is the default the volume rules already omit.

#### Scenario: the as cell carries no MOP field

- **WHEN** `CleanControlTest` replays cell `as` on the cryptoapp fixture with the artifact loaded, 300 steps
- **THEN** no step record SHALL carry `mop`, `mopf`, `wtg`, `menu`, `mopd`, `mops`, `ret`, `mopx` or `own.uM`, and no `ACT` entry a `mop` member
- **AND** the trace's `MOP_DATA` record SHALL be present and SHALL NOT fail the gate
- **AND** no step record SHALL carry `src:"mop"` or `src:"launcher"`, nor `ch:"mop_coin"`
- **AND** no `[APE-RV] UICOV` line SHALL carry `mopReach`, and `RUN_END.counters` SHALL carry `coinMop:0` and `launches:0`
- **AND** step records MAY carry `navf`, `cov` and `form`, the STATIC and ADDONS scorers

#### Scenario: the as_llm cell carries the LLM coin but not the MOP coin

- **WHEN** `CleanControlTest` replays cell `as_llm` with an LLM fake
- **THEN** every step record's `dec.own` SHALL carry `uL` and SHALL NOT carry `uM`
- **AND** `RUN_END.counters.coinMop` SHALL be `0`

#### Scenario: the base cell carries no MOP field

- **WHEN** `CleanControlTest` replays cell `base` with no artifact
- **THEN** no step record SHALL carry any field of the gate's list
- **AND** every `dec.nav`, where present, SHALL carry `c:"ape"`

### Requirement: RUN_START Carries the Variant Name

`RUN_START` carries the format version `v`, which is `2` from this change on (`run-spec` "Level-0 RUN_START Echo", design D-B14.22), because `dec.src` changes meaning. `RUN_START` SHALL carry `variant`, the string value of the resolver key `ape.variant`, when the key is set, and SHALL omit it when the key is null. The value names the experimental variant the harness ran (the `aperv-tool` variant of the cell); the jar does not interpret it. It SHALL NOT enter the plan digest, so two runs of the same plan under two variant names share a digest.

#### Scenario: variant echoed

- **WHEN** the harness passes `ape.preset=as_mop` and `ape.variant=as_mop_p10` with `ape.mopCoinPercentage=0.1`
- **THEN** the trace's `RUN_START` SHALL carry `"variant":"as_mop_p10"`

#### Scenario: variant absent

- **WHEN** a standalone run sets no `ape.variant`
- **THEN** `RUN_START` SHALL carry no `variant` member

#### Scenario: variant not in the digest

- **WHEN** two runs resolve the same plan, one with `ape.variant=a` and one with `ape.variant=b`
- **THEN** their `RUN_START` plan digests SHALL be equal
