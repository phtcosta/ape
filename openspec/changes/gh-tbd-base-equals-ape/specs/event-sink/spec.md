# Delta: Event Sink (gh-tbd-base-equals-ape)

## MODIFIED Requirements

### Requirement: StepRecord Schema

The sink SHALL emit one `StepRecord` per exploration step as a single-line JSON object with the following members. Field presence follows INV-SNK-05 (defaults omitted; tri-state exemptions explicit).

**Envelope** (once per record): `s` (step counter), `t` (milliseconds since `RUN_START`; epoch time = `RUN_START.t0 + t`), `act` (integer `ACT` dictionary ID of the current activity), `st` (integer `STATE` dictionary ID of the current abstract state).

**`dec`** (decision): `a` (the full action string, JSON-escaped; the action the step dispatched), `src` (the owner that executed the step, below), `ch` (pick channel label, below), `own` (the routing record, below; every step), `rot` (the display rotation, below; every step). Model-action records additionally carry `pri` (priority) and the non-zero scorer fields `mop`, `mopf`, `wtg`, `navf`, `cov`, `menu`, `form`; `mopx` (`[weighted, total]`, the MOP exposure pair, present exactly when the MOP widget pass is constructed); `mopd` and `mops` (below, the MOP lookup behind `mop`); `hnd` (below, the stamped handler behind the weight); `ret` (below, the targets the step's action retired); `patched` (0/1, present exactly when the action has a resolved target node). The members `cfc`, `draw`, `nav`, `inp`, `restart`, `bmc`, `strat`, `buf` and `trig` are written when their condition below holds. Non-model records carry `a`/`src`/`ch`/`own`/`rot` and the conditional members that apply, and launcher records additionally carry `comp` (below).

**`dec.src` — the executed owner.** `src` is one of `budget`, `restart`, `llm`, `launcher`, `mop`, `sata`, written by the pipeline stage that took the step (`decision-pipeline`, design D-B7), or `restart` when a post-selection restart replaced the selected action (below). Its meaning changed with `isolate-guidance-groups`: it was the decision source of the largest boost, and it is now the owner that executed the step. A scorer never sets `src`: scorers adjust SATA's candidates, and a step that SATA decides is `sata` whatever the scorers wrote (INV-SNK-15). `RUN_START.build` tells a trace written before this change from one written after it.

**`dec.ch` — the pick channel.** `ch` survives as the channel label of the pick (design D-B14.17): `roulette_greedy`, `roulette_early`, `buffer` and `sata_other` for SATA's paths (`sata_other` covers SATA's other paths only, never a guard step), `budget` for a step the Budget guard takes, `restart` for a step the RESTART guard takes or a post-selection restart takes, `launcher` for a launcher step, `llm` for an LLM step, and `mop_coin` for a step the MOP coin takes. The values `short_circuit_unvisited` and `short_circuit_0step` SHALL NOT be produced: the MOP shortcut they named left SATA. `ch` complements `src`, which names the owner, and `strat`, which names the SATA path.

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

**A post-selection restart is recorded as the step's executed owner.** In a run whose pipeline did not assemble the `Restart` stage (`decision-pipeline` "Post-Selection Restart": no `APE_FIXES`, as in the `base` and `mop` cells, or an agent without a pipeline), `ApeAgent.checkRestart` may replace the selected action, of any owner, by a start action after selection. The step's record SHALL then be the record a step the `Restart` stage takes writes: `dec.src:"restart"`, `dec.ch:"restart"`, `dec.a` naming the start action, `dec.restart` naming the kind, `dec.own` with `i:"restart"` and the step-open coin draws of the mounted groups (`uL`, `uM`), and one count in `RUN_END.counters.restarts`. `StatefulAgent` writes it through `EventSink.decisionNonModel(start, "restart", "restart")` after the replaced decision's `decision(...)` call, and that second call SHALL replace the first: no field of the replaced decision (`pri`, the scorer fields, `mopx`, `mopd`, `mops`, `hnd`, `patched`, `cfc`, `draw`, `nav`, `strat`, `bmc`, `buf`, `own.trg`, `own.g`, `own.fb`) SHALL remain on the record. The LLM attempts made on the step before the replacement SHALL stay in `llm[]`. The record closes as a non-model record closes, without an `out` member when no outcome resolves under the start action's identity.

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

**`dec.ret` — targets this step retired** (design D23 of `llm-coordinate-single-base`). Under `ape.mopScoring=distance`, the integer indices, into the artifact's target list (`MOP_DATA.targets`), of the targets whose retirement counter reached `ape.mopRetireAfter` as a consequence of this step's action — the first interaction of its widget in its activity, counted and applied in `StatefulAgent.moveForward` (`mop-guidance` "Target Retirement"). No count is pending and none is discarded: on the stage path the restart is a guard that runs before selection (`decision-pipeline` "Restart Guard Stage"), and on the post-selection path an action a restart replaces never reaches `moveForward` (`decision-pipeline` "Post-Selection Restart"), so in neither path does a `checkRestart` override take part, and a restart step carries no `ret`. The indices are not known when the decision is recorded, so they SHALL reach the open record through their own sink entry, `EventSink.mopRetired(int[] indices)`, called from `moveForward`, after the step's `sink.decision(...)` call, when the retirement call returns a non-empty array, after the pattern of `mopExposure` and `componentLaunch`, which also add a member to the step's `dec` after the decision call. `StepRecord` writes `dec` when the record closes, so `ret` sits in `dec` and survives a record closed without an `out` member. Each index appears once, in ascending order. The array SHALL be absent when it would be empty, which is every step under `ape.mopScoring=flag`. A target retires once, so an index appears in `dec.ret` at most once per run.

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

#### Scenario: post-selection restart recorded as the owner

- **WHEN** in the `mop` cell at step 140 the MOP coin selects a click (`mopWeight=500`, `u_M=0.2`) and a periodic restart is due, so `checkRestart` replaces the click by an `EVENT_RESTART` start action
- **THEN** the `s:140` record's `dec` SHALL carry `src:"restart"`, `ch:"restart"`, `restart:"periodic"`, an `a` naming the start action, and `own` with `i:"restart"` and `uM:0.2`
- **AND** it SHALL carry no `mop`, `mopd`, `mops`, `mopx`, `patched` or `ret`, and no `own.fb`
- **AND** `RUN_END.counters.restarts` SHALL count the step once

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

#### Scenario: LLM attempts survive a post-selection restart

- **WHEN** a plan with `LLM` and without `APE_FIXES` makes an LLM call on step 75 that matches a widget, and a stability restart then replaces the LLM's action after selection
- **THEN** the `s:75` record's `llm` array SHALL still hold that call's entry with `result:"matched"`
- **AND** its `dec` SHALL carry `src:"restart"` and `ch:"restart"`, and no `own.trg`

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
- `restarts`: steps whose executed owner is a restart (`dec.src:"restart"`), whether the RESTART guard took the step or a post-selection restart replaced its selected action (`decision-pipeline` "Post-Selection Restart"); each such step counts once.
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

- **WHEN** a `base` run ends by timeout after 1,900 steps, none taken by a guard and none replaced by a post-selection restart, with the foreign-activity guard off
- **THEN** `RUN_END.counters` SHALL carry `launches:0`, `gateMinActions:0`, `gateBreaker:0`, `guardBacks:0`, `bufAbandoned:0`, `restarts:0`, `coinLlm:0`, `coinMop:0` and `nullRelaunches:0`, each written

#### Scenario: coin counters match the step records

- **WHEN** an `as_mop_llm` run with `p_L = 0.9` writes 1,000 step records, 612 of which were taken by no guard and by no LLM trigger stage, reached `LlmCoin` with the step's LLM call unspent and carry `dec.own.uL` below `0.9`, and 140 of which carry `dec.src:"mop"`
- **THEN** `RUN_END.counters.coinLlm` SHALL be `612` and `coinMop` SHALL be `140`

#### Scenario: a step whose call was already spent does not count as a coin win

- **WHEN** on an `as_llm` step `LlmNewState` makes the step's one LLM call, the call yields no action, and the step reaches `LlmCoin` with `u_L < p_L`
- **THEN** `LlmCoin` SHALL return `Continue` without consulting the coin
- **AND** `RUN_END.counters.coinLlm` SHALL NOT count that step

#### Scenario: post-selection restarts are counted

- **WHEN** a `base` run ends by timeout and 9 of its step records carry `dec.src:"restart"`, each a SATA pick that a post-selection restart replaced
- **THEN** `RUN_END.counters.restarts` SHALL be `9`
