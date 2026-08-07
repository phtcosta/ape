# llm-routing Delta Specification

## Purpose

Delta for `rearch-04-step-ndjson-telemetry`: LLM telemetry stops being its own stdout line family and becomes **sub-events of the step record** (event-sink capability). The `[APE-LLM-TEL]` and `[APE-LLM-ERROR]` lines are retired — each routing attempt appends one entry to the current step's `llm[]` array, so the call↔decision↔outcome join exists by construction instead of by a shared `step=` key. The aggregate summary lines are retired in favor of `RUN_END` counters; the `[APE-LLM-CONFIG]` manifest is subsumed by the `RUN_START` effective-plan echo (run-spec capability); the server-model acknowledgement becomes the `LLM_ACK` record. All counters, fields, and semantics (cause partition, repair provenance, dead-pair overlay, breaker-episode latch) are preserved — only the encoding changes. The `mopWeight*`-style behavior of the router is untouched: telemetry never decides (INV-SNK-07).

`Deterministic Dead-Pair Ban` is restated here for the same reason and no other. It is the one requirement outside this capability's telemetry set that names the retired renderings directly — its outcome-feedback clause is anchored on the `[APE-OUTCOME]` line, and its ban-check clause and one scenario are anchored on `[APE-LLM-TEL]`. Left alone it would sync to the main spec demanding line formats that no longer exist anywhere, since nothing rewrites the trace back into the old family. The data is unaffected: the refusal survives as `result:"no_match"` with `reason:"dead_pair"` in the step's `llm[]` sub-event, and the overlay counter as `RUN_END.counters.llm.dead_pair`. The restatement is layered over `rearch-03-decision-pipeline`'s, which had already moved the record and the check onto `CoordinateMapper`.

## Invariants

Disposition of the capability's top-level `INV-RTR-*` block. These four invariants live in a
top-level `## Invariants` section of `openspec/specs/llm-routing/spec.md`, outside any requirement,
so they are dispositioned here rather than by a requirement operation — the same treatment the
`action-selection` delta gives its `INV-SEL-*` block. The other `INV-RTR-*` entries are untouched by
this change.

- **INV-RTR-10** — **retired with its subject.** It normatizes the `[APE-LLM-CONFIG]` manifest: once
  per run, at construction, reporting the effective sampling parameters rather than the
  `ape.properties` map. The manifest is subsumed by the `RUN_START` effective-plan echo (the
  `Effective LLM Config Manifest` REMOVED entry below carries the full reason). The invariant needs
  its own disposition because it sits outside that requirement and would otherwise survive it. The
  property it protected — *the trace reports what was actually sent, not what was configured* —
  survives in the echo, which is derived from the resolved plan the units are constructed from.
- **INV-RTR-11** — **re-anchored; the partition is unchanged.** The sum
  `timeout + http + conn + parse + image + internal + screenshot_failed` still equals the number of
  attempts abandoned before the mapping step, still exactly one cause per abandoned attempt, and
  `no_match` is still outside the sum. Only the discriminator is restated: an abandoned attempt is
  one that returns null **without appending a completed-call entry** to the step's `llm[]` array,
  where it previously was one that returned null without emitting an `[APE-LLM-TEL]` line. The
  counters themselves are reported in `RUN_END.counters.llm`.
- **INV-RTR-12** — **re-anchored.** At most one server-model acknowledgement per run, only after a
  successful `chat()`, and a run with zero successful responses emits none — its absence, with the
  failure-cause counters, still diagnostic. The rendering becomes the `LLM_ACK` record
  (`Server Model Acknowledgement` below).
- **INV-RTR-20** — **re-anchored onto the line that survives, and this is the one clause of the four
  where the code moved before the spec did.** Every routing attempt abandoned at screenshot capture
  SHALL still be counted under `screenshotFailedCount` as its only cause counter, SHALL still
  produce no completed-call sub-event, and SHALL still carry the foreground activity — but on the
  free-text `[APE-RV] LLM screenshot capture failed, skipping LLM step activity=<a> detail=<stage>`
  line, not on an `[APE-LLM-ERROR] cause=screenshot` line. That fold was made by
  `rearch-03-decision-pipeline` and is stated in this change's `LLM Telemetry Logging` restatement
  ("keeps its counter and its existing free-text line, with no `error` sub-event"), so the
  invariant was the last place still demanding the old rendering. It is the failure this capability
  cares most about attributing: 147 capture failures concentrated in 4 FLAG_SECURE APKs, co-located
  with 100% of the 57 breaker trips, which is why the activity is on the line at all.

## MODIFIED Requirements

### Requirement: LLM Telemetry Logging

`LlmRouter` SHALL record structured per-attempt telemetry as `llm[]` sub-events on the current step's `StepRecord` (event-sink capability) and aggregate counters reported in `RUN_END`.

**Per-attempt sub-event** (one array entry per routing attempt, in occurrence order within the step):

| Field | Values |
|-------|--------|
| `call` | running call counter for the run |
| `mode` | `new_state`, `stagnation`, `random` |
| `tool` | the parsed tool name (`click`, `long_click`, `type_text`, `back`) |
| `qwen` / `px` | `[x,y]` model-space and mapped pixel coordinates |
| `result` | `matched`, `llm_tap`, `no_match` for completed mappings; `error` for attempts abandoned before the mapping step; `breaker_open` for the once-per-open-episode breaker decline |
| `reason` | only on `no_match`: `dead_pair`, `degenerate`, or `boundary` |
| `repair` | only when the decision's `ParsedAction` carries a repair-form label other than `none` (per `llm-infrastructure` INV-LLM-09): `missing_y`, `array_xy`, `quoted_xy`, or `int_scan`. Absent on a clean parse |
| `mcls` / `ncls` / `ndist` / `widgets` | matched class, nearest class, nearest distance, candidate widget count |
| `tok` | `[prompt_tokens, completion_tokens]` (0 if unavailable) |
| `ms` | wall-clock milliseconds for the full pipeline (screenshot → response) |
| `text` | only for text-entry decisions: the typed text (JSON-escaped by the serializer) |
| `cause` / `detail` | only on `result:"error"`: exactly one named cause per abandoned attempt — `timeout`, `http_<status>`, `connection`, `parse`, `image`, `internal` — attributed at the failure point (client seam `getLastErrorCause()` consulted only when `chat()` returned null; router-attributed otherwise). The screenshot-capture failure keeps its counter (`screenshot_failed`) and its existing free-text line, with no `error` sub-event |
| `trips` | only on `result:"breaker_open"`: the breaker trip count; emitted at the first routing-predicate decline of each open episode only, using a side-effect-free breaker state query (`isOpen()`), never a second `shouldAttempt()` |
| `sys` / `user` / `resp` / `tool_calls` | prompt and response dumps, present when the prompt-dump flag is on (default on — recordability parity with the retired unconditional `[APE-LLM-PROMPT]`/`[APE-LLM-RESPONSE]` lines) |

No sub-event carries a step, activity, or variant field: the step and activity are the parent record's envelope, and the prompt variant is run-constant in the `RUN_START` plan echo. The `repair` field remains orthogonal to `result` (a repaired call yields a normal outcome plus `repair=<form>`).

**Counters**: the per-cause failure counters (partitioning the retired `null`), `matched`/`llm_tap`/`no_match`/`dead_pair`/`repaired`, token and latency totals, and `breaker_trips` SHALL be maintained exactly as before and exposed via an accessor consumed by the `RUN_END` emitter. The decisions denominator (`matched + llm_tap + no_match + Σ failure causes`) is derived by consumers; no ratio is stored.

**Where the retired renderings' assertions went.** Four scenarios below keep the header the pre-change
requirement gave them and carry a body this change contradicts, because the thing they asserted is
the thing this change removes. They are recorded here rather than dropped, since the reader's
question is not whether the old line is gone but where its claim lives now:

| Scenario header | What it asserted | Where the claim is now |
|---|---|---|
| `LLM call telemetry joins step and outcome on one key` | `[APE-LLM-TEL] step=N` ↔ `[APE-STEP] step=N` ↔ `[APE-OUTCOME] step=N` | **nowhere, deliberately** — the call, the decision and the outcome are members of one `StepRecord`, so there is no key to join on. The property the join existed to guarantee (a call is attributable to the step whose selection it interrupted) is now structural |
| `Screenshot failure emits an attributable error line` | `[APE-LLM-ERROR] cause=screenshot activity=… detail=<stage>` | the surviving free-text line, which keeps the activity and the capture stage verbatim (`LlmTelemetry.screenshotFailed`, INV-RTR-20), plus `screenshot_failed` in `RUN_END`. It is the one abandoned attempt with no `llm[]` sub-event |
| `Stagnation mode triggered` | `[APE-RV] LLM mode=stagnation, state=…` | the sub-event's `mode` field. The line the scenario names was never emitted by any shipped router — `mode` has always been a *field* of the per-attempt telemetry — and the trigger condition it describes is asserted by `Stagnation LLM Mode`, which this change does not modify |
| `widget text with a newline does not break the prompt log` | the prompt builder flattens `\n`/`\r` before the dump is emitted | two places, and it is stronger in both: the builder still flattens (`llm-prompt :: multi-line widget text flattened`, unmodified by this change), and the serializer now escapes by construction (`event-sink` INV-SNK-02), so no widget text can put a raw newline in a record |

#### Scenario: Matched widget logged

- **WHEN** the new-state hook routes to the LLM at step 42 and the call maps to a widget `ModelAction` of type `MODEL_CLICK`
- **THEN** the `s:42` record's `llm` array SHALL contain the entry with `result:"matched"`, its coordinates, `tok`, and `ms`
- **AND** no `[APE-LLM-TEL]` line SHALL be emitted

#### Scenario: LLM call telemetry joins step and outcome on one key

- **WHEN** the new-state hook routes to the LLM at step 42, the call succeeds and the mapped action executes producing a recorded transition
- **THEN** the call's sub-event, the decision (`dec`) and the outcome (`out`) SHALL all be members of the single `s:42` record
- **AND** no join key SHALL exist or be needed — the pre-change `step=` field is absent from the sub-event because the step is the parent record's envelope (`event-sink` INV-SNK-04)

#### Scenario: dead-pair ban visible in TEL and summary

- **WHEN** two decisions in a run are banned by the dead-pair ban and one is discarded for a boundary coordinate
- **THEN** the two banned decisions' sub-events SHALL carry `result:"no_match"` with `reason:"dead_pair"`
- **AND** `RUN_END` SHALL report `no_match:3` and `dead_pair:2` — the overlay counter that makes bucket D, the falsification gate of the ban, countable from the run's last record alone

#### Scenario: Repaired tool call logged and counted

- **WHEN** at step 61 the model returns `{"name": "click", "arguments": {"x": "500, 527}}`, `ToolCallParser` recovers it via the quoted-collapsed-XY fix, and the coordinate resolves to a widget
- **THEN** the step-61 sub-event SHALL carry `result:"matched"` and `repair:"quoted_xy"`
- **AND** the run counters SHALL count the decision under both `matched` and `repaired`
- **AND** no `error` sub-event with `cause:"parse"` SHALL exist for step 61

#### Scenario: no_match reason separated

- **WHEN** one decision is discarded for a `(0,0)` coordinate, another for a navigation-band coordinate, and a third by the dead-pair ban
- **THEN** their sub-events SHALL carry `reason:"degenerate"`, `reason:"boundary"`, and `reason:"dead_pair"` respectively, each with `result:"no_match"`

#### Scenario: Timeout and HTTP failure discriminated

- **WHEN** one attempt times out and a later attempt gets HTTP 500
- **THEN** the first step's record SHALL carry `{"result":"error","cause":"timeout",...}` and increment `timeoutCount`
- **AND** the second's SHALL carry `{"result":"error","cause":"http_500",...}` and increment `httpErrorCount`
- **AND** `RUN_END` counters SHALL report `timeout:1` and `http_error:1`

#### Scenario: Router-side parse failure attributed without the client seam

- **WHEN** `chat()` returns a non-null response but `ToolCallParser.parse()` extracts no tool call even after the quoted-collapsed-XY fix and last-resort integer extraction
- **THEN** a sub-event with `result:"error"` and `cause:"parse"` SHALL be appended and `parseErrorCount` incremented
- **AND** `getLastErrorCause()` SHALL NOT be consulted (the HTTP call succeeded; its value is stale)

#### Scenario: Circuit breaker event logged once per open episode

- **WHEN** the breaker trips to OPEN with 2 trips and predicates decline 5 calls during the same open window
- **THEN** exactly one sub-event `{"result":"breaker_open","trips":2}` SHALL be recorded, on the step of the first decline
- **AND** no LLM HTTP call SHALL be made while the breaker is OPEN

#### Scenario: Screenshot failure emits an attributable error line

- **WHEN** `ScreenshotCapture.capture()` returns null on a FLAG_SECURE window while activity `org.fedorahosted.freeotp.MainActivity` is foreground, in a run that ends after 5 attempts of which 3 were abandoned at capture and 1 failed at parse
- **THEN** the failure SHALL be attributable from the surviving free-text line, which carries `activity=org.fedorahosted.freeotp.MainActivity` and `detail=<stage>` read from the `ScreenshotCapture` seam
- **AND** `screenshotFailedCount` SHALL be incremented and `breaker.recordFailure()` called (unchanged breaker semantics)
- **AND** `RUN_END` counters SHALL report `parse_error:1 screenshot_failed:3`
- **AND** the screenshot failures SHALL have produced no `error` sub-event — the one abandoned attempt that does not, deliberately (INV-RTR-20)

#### Scenario: Stagnation mode triggered

- **WHEN** `shouldRouteStagnation(150)` is called with `graphStableRestartThreshold = 200` and the episode flag armed, and the resulting attempt completes
- **THEN** the attempt's sub-event SHALL carry `mode:"stagnation"`
- **AND** no free-text mode line SHALL be emitted — the pre-change scenario named `[APE-RV] LLM mode=stagnation, state=…`, which no shipped router ever wrote; the trigger condition itself is asserted by `Stagnation LLM Mode`, unmodified by this change

#### Scenario: widget text with a newline does not break the prompt log

- **WHEN** an element's widget text contains `"line1\nline2"` and the prompt-dump flag is on
- **THEN** the sub-event's `user` field SHALL contain `"line1 line2"` for that element (flattened by the prompt builder, `llm-prompt` capability, unchanged)
- **AND** the record SHALL occupy exactly one physical line regardless of what the text contains, because the serializer escapes `\n`, `\r` and every control character by construction (`event-sink` INV-SNK-02) — the flattening is no longer what makes the dump parseable, only what keeps one element on one line within it

#### Scenario: Prompt dump follows the flag

- **WHEN** the prompt-dump flag is at its default (on)
- **THEN** each completed call's sub-event SHALL carry `sys`/`user`/`resp` with the prompt and response text JSON-escaped
- **AND** with the flag off, the same sub-event SHALL omit those fields and nothing else changes

### Requirement: Server Model Acknowledgement

The router SHALL emit one `{"type":"LLM_ACK","server_model":"<model>"}` record (event-sink capability) after the first successful `chat()` response of the run, carrying the model identifier reported by the server in the response envelope (`unknown` when the response omits the field). The `RUN_START` plan echo records the model the run *requested*; `LLM_ACK` records what the server *actually served* — the pair proves an arm talked to the intended model without inspecting the server. At most one `LLM_ACK` per run; a run with zero successful responses emits none (its absence, with the failure-cause counters, is itself diagnostic).

#### Scenario: Server model acknowledged once

- **WHEN** the first successful response reports `qwen3-vl-8b` and 200 further calls succeed
- **THEN** exactly one `LLM_ACK` record SHALL appear in the trace, carrying `server_model:"qwen3-vl-8b"`, after the first successful response
- **AND** no `[APE-LLM-CONFIG-ACK]` line SHALL be emitted

#### Scenario: No acknowledgement without a successful response

- **WHEN** every `chat()` call of a run fails (server down)
- **THEN** zero `LLM_ACK` records SHALL appear in the trace
- **AND** the absence SHALL be read together with the `RUN_END` failure-cause counters, which say why — the pre-change line's absence said only that it never happened

### Requirement: Deterministic Dead-Pair Ban

Restated over the `rearch-03-decision-pipeline` text (which moved the record and the check onto `CoordinateMapper`). This stage changes only the *rendering*: the ban's refusal and its counter stop being a `[APE-LLM-TEL]` line and a summary line, and become an `llm[]` sub-event and a `RUN_END` counter. The mechanism, the keys, the k=5 death rule, the input-capable exemption and every measured justification are untouched.

`CoordinateMapper` SHALL maintain a per-run, in-memory record of **dead pairs** — LLM decisions that already executed in this run without producing a new state — and SHALL refuse to return a result that resolves to a dead pair. Measured motivation: 25.6% of LLM calls (10,081/39,341) re-emit an already-executed (state, coordinate) pair, and those repeats produced **0 new states in 10,081 attempts** (Wilson CI [0.00–0.04]); the anti-repetition prompt instruction exists and is ignored. Banning by subtraction (removing the option) is the externally validated mechanism (Guardian: 36% repetition persists under instruction); the detection is deterministic in the harness — the model is never asked to self-reflect.

**Ban keys** (the anchor is always the stable pair, NEVER a list index):
- an `llm_tap` result is keyed by `(state.getStateKey(), pixelX, pixelY)` — exact emitted-coordinate equality;
- a `matched` result is keyed by `(state.getStateKey(), widget stable id, eventType)`, where the widget stable id is **the action `Name`'s XPath** (`Name.toXPath()`) — the same widget identity used by `UICoverageTracker.widgetId` and by the MOP revisit cap. It is **not** a per-node identity: `GUITreeNode` exposes no XPath of its own, and a `Name` is an abstraction that may resolve to several nodes.

**Granularity of the `matched` key, stated because it bounds what a ban means.** Since the key is `Name`-level, **banning one pair withdraws that action from every node the `Name` resolves to in that state**. Measured on the calibration corpus: 16.3% of targeted steps (23,441 of 144,174) resolve more than one node. This is accepted rather than avoided — a node-derived alternative such as `(activity, className, resourceId, actionType)` is coarser still, colliding on at least 18.3% of anchors and on 36.3% of those whose `resourceId` is empty, which is 57.6% of clicks — but it constrains interpretation: a dead-pair count is a count of **abstract** pairs, and SHALL NOT be reported as a count of physical widgets withdrawn.

**Death rule (k=5, with input-capable widgets exempt):** a pair becomes dead after **five** executions whose recorded outcome has `new_state=false`. The threshold is uniform across every widget class the ban covers — there is no per-class *threshold* variation. An execution with `new_state=true` does not count toward death, and does not decrement or reset the accumulated count either: the counter only ever grows, and it counts unproductive executions of that exact pair.

**Input-capable targets are exempt at any strike count.** A `matched` pair whose resolved target is input-capable SHALL never become dead, however many unproductive executions it accumulates. Input-capable means the widget class is one of `android.widget.EditText`, `android.widget.AutoCompleteTextView`, `android.widget.SearchView`, `androidx.appcompat.widget.SearchView` — the set the prompt builder already uses to decide whether to offer `type_text` (`ApePromptBuilder.INPUT_CLASS_NAMES`). The ban SHALL NOT carry a second, independently-maintained list of input classes: one definition of "input-capable" serves the prompt, the fixTextEdit conversion, and this exemption.

The exemption is realized by **not recording strikes** for an input-capable target, rather than by filtering at the ban check. The consequences are deliberate and are what the scenarios assert: an exempt pair never enters the dead-pair record at all, so its ban key can never be consulted and found dead; and because the exemption keys on the *widget*, not the event type, it covers the text-entry action that the fixTextEdit conversion ("Coordinate Mapping") produces from a click on that same widget — the two mechanisms act on one widget set, not two.

**The exemption applies to `matched` results only, and this is a property of the evidence, not an oversight.** An `llm_tap` result is an off-tree tap with no matched widget: it carries `matched_class=none` in 1,033 of 1,033 corpus occurrences, so no widget class is knowable at its ban key. No class-based exemption of any design can reach the `llm_tap` half of the ban.

Why five, and what the exemption costs — measured on the 84 `cal_a1` runs of the calibration corpus (`experimento-cal/iter0`), which is the same 70% LLM configuration the decisive run's LLM arm uses.

**The sweep must be read on the keys the ban actually uses.** An earlier reading of this measurement keyed *both* result types by `(state, pixel)`, because the recorded trace does not carry the widget XPath. Only `llm_tap` uses that key, and `llm_tap` is 15.9% of the decision stream; the other 84.1% is `matched`, which ships with the looser `Name`-level key above and therefore bans more. Both columns below come from the same replay:

Every share below is computed against a denominator of **6,500 LLM decisions** — the reconstructed decision stream of the 84 `cal_a1` traces (80 main runs at `timeout=300` plus 4 smoke runs at `timeout=90`). Main-only the denominator is 6,440, which moves the k=5 share to 27.8% and leaves it inside the ceiling.

| k | refused, `(state,pixel)` key | share | **refused, shipped keys** | **share** | new states lost |
|---|---|---|---|---|---|
| 1 | 3,161 | 48.6% | 3,847 | **59.2%** | 44 → 80 |
| 2 | 2,283 | 35.1% | 2,975 | **45.8%** | 16 → 37 |
| 3 | 1,813 | 27.9% | 2,441 | **37.6%** | 8 → 24 |
| **5** | 1,305 | 20.1% | **1,788** | **27.5%** | 5 → **9** |
| 12 | 604 | 9.3% | 881 | 13.6% | 1 → 2 |

- The refused block is nearly unproductive at every threshold, so the death rule is not a close call on evidence; the choice of k is about how much of the arm's decision stream the ban is allowed to take over.
- k=1 maximizes raw net gain but refuses **59.2%** of the LLM's answers under the shipped keys. At that rate the LLM arm's behavior is substantially the SATA fallback's, and the arm stops being interpretable as "the LLM exploring" — which is the only thing the decisive run exists to measure. **The threshold is an experimental-validity choice, not only an optimization**, and the criterion it enforces is *refusal below 30%*. Under the shipped keys k=3 sits at 37.6% and fails that criterion; **k=5 sits at 27.5% and meets it**, at the cost of 9 new states lost instead of 8.
- **The table's shares are measured without the input-capable exemption, so 27.5% is now an upper bound.** Exempting a class can only remove pairs from the refused block, never add to it, so the shipped rule refuses *at most* 27.5% and the sub-30% criterion is met a fortiori. **The exact post-exemption share was not re-derived from the corpus** — the bound is what the criterion needs, and the sweep was not re-run for a number that could only move in the safe direction. A reader wanting the realized share must take it from the decisive run's own telemetry, where the `dead_pair` counter in `RUN_END.counters.llm` reports it directly.
- **k=5 raises the floor under every class the exception list named except `EditText`, which keeps its exemption by name.** The list this rule replaces would have granted `Switch`/`CheckBox`/`RadioButton` a threshold of k=2; k=5 grants them 5, so each is protected at least as well without a class test. Those classes are not rare — at k=1 they account for 81 of 2,586 banned pairs (3.1%) in the calibration corpus, with `Spinner` and `CheckedTextView` adding another 25 — so this is dominance by arithmetic, not by rarity. `EditText` is the one member the arithmetic does not cover: the list exempted it outright, and no finite k reproduces an exemption, which is why it survives as the input-capable carve-out above.
- The uniform threshold also reaches cases no class-name enumeration can: N-ary selection widgets (`Spinner`, where one coordinate opens many options — 10 banned pairs at k=1, dropping to 2 by k=3 and fewer still at k=5), Material/AppCompat subclasses whose simple names differ from the base classes (`SwitchCompat`, `MaterialCheckBox` — the corpus does show AndroidX simple names such as `LinearLayoutCompat`, `FloatingActionButton` and `CardView` reaching the tree, so the divergence is real), and Compose trees that expose no meaningful widget class name at all. The input-capable exemption is a class-name test and inherits exactly this limitation: an input widget whose class name is not one of the four is banned like any other.

**Scope limit (stated because it bounds what this mechanism can be credited with):** the ban check runs *after* `mapToModelAction` returns — the screenshot, the HTTP call and the parse have already completed. The ban changes which action executes; it does NOT reduce the LLM arm's per-step latency cost. In the calibration corpus that cost is 35% of a 300 s run's budget, and it is the dominant reason the LLM arm executes 0.622× the steps and discovers 0.729× the distinct states of the algorithmic arm on the same wall clock. This mechanism raises yield per decision; it does not address throughput, and it is not expected to close that gap on its own.

**Outcome feedback:** `StatefulAgent` SHALL report the outcome of each executed LLM-originated decision to `CoordinateMapper` — reached through `RunContext`'s LLM units, via `recordLlmOutcome(...)` — at the point where `new_state` is computed for the step record's `out` section, using the same single-shot, reference-equality-guarded buffered-decision discipline that closes the record (`event-sink` INV-SNK-08). The key material and strike semantics are unchanged by the decomposition; only the owner of the record moves. Only LLM-originated decisions feed the ban record; SATA-selected actions are never banned.

**Ban check site and behavior:** the check lives in `CoordinateMapper`, at the end of its mapping step inside `LlmEngine.selectAction()` (step 8 of the Action Selection Pipeline): once a `matched` or `llm_tap` result is mapped and before it is handed back, `CoordinateMapper` SHALL compute the result's ban key; when the key is dead, the engine SHALL return null — the declared fallback, the same caller-visible path as `no_match`, decided by the remainder of the assembled pipeline (`decision-pipeline` INV-DP-11). The banned decision SHALL be recorded as an `llm[]` sub-event on the current step's record carrying `result:"no_match"` and `reason:"dead_pair"` (`event-sink` capability), and SHALL increment both `noMatchCount` and the `dead_pair` overlay counter reported in `RUN_END.counters.llm`. The ban SHALL NOT record a breaker failure through `LlmClient` — the LLM pipeline succeeded; only its answer was refused — so a ban streak can never open the circuit breaker.

**Memory scope:** the ban record is per-run and in-memory only; no persistence, no cross-run state.

**Falsification gate (protocol, recorded here because it defines B1's success criterion):** bucket D (dead-pair repeats) MUST fall to ≈0 in the decisive-run telemetry BEFORE any new-state gain is credited to this mechanism; if bucket D ≈ 0 and new states do not rise, the ban is judged ineffective.

#### Scenario: repeated dead llm_tap is banned

- **WHEN** an `llm_tap` at `(500, 499)` on state S1 executed earlier in the run and its recorded outcome had `new_state=false`
- **AND** a later `LlmEngine.selectAction()` on state S1 resolves to an `llm_tap` at the same `(500, 499)`
- **THEN** the engine SHALL return null and the remainder of the pipeline SHALL decide the step
- **AND** the step's `llm[]` sub-event SHALL carry `result:"no_match"` and `reason:"dead_pair"`
- **AND** no breaker failure SHALL be recorded through `LlmClient` for this decision

#### Scenario: pair survives four dead executions and dies on the fifth

- **WHEN** a `matched` click on Button "Help" (state S2) executes four times with `new_state=false`
- **THEN** the pair (S2, that action `Name`'s XPath, click) SHALL NOT yet be dead, and a fifth LLM answer resolving to it SHALL be returned normally
- **AND** after that fifth execution also records `new_state=false` the pair SHALL be dead
- **AND** the next LLM answer resolving to it SHALL be banned

#### Scenario: threshold is uniform across the widget classes the ban covers

- **WHEN** one pair targets a `Switch`, another a `Spinner`, and another a plain `Button`, and each executes five times with `new_state=false`
- **THEN** all three pairs SHALL be dead at the same threshold
- **AND** no covered widget class SHALL receive a threshold other than five

#### Scenario: an input-capable target is never dead

- **WHEN** a `matched` pair targets an `android.widget.EditText` and executes six times with `new_state=false` — one more than the threshold
- **THEN** the pair SHALL NOT be dead, and a subsequent LLM answer resolving to it SHALL be returned normally
- **AND** the pair SHALL NOT appear in the dead-pair record at all, because no strike was ever recorded for it
- **AND** the same SHALL hold for `android.widget.AutoCompleteTextView`, `android.widget.SearchView` and `androidx.appcompat.widget.SearchView`

#### Scenario: the exemption follows the widget through the fixTextEdit conversion

- **WHEN** an LLM `click` resolves to a `SearchView` and the fixTextEdit conversion ("Coordinate Mapping") turns it into a text-entry action on that widget
- **THEN** the resulting text-entry decision SHALL also be exempt, because the exemption keys on the target widget rather than on the event type
- **AND** repeated unproductive executions of it SHALL never produce `reason=dead_pair`

#### Scenario: an off-tree tap cannot be exempted

- **WHEN** an `llm_tap` result is banned after five unproductive executions at the same coordinate on the same state
- **THEN** the ban SHALL apply regardless of what is rendered at that coordinate
- **AND** no input-capable exemption SHALL be consulted, because an `llm_tap` has no matched widget and therefore no widget class

#### Scenario: a ban withdraws the action from every node the Name resolves to

- **WHEN** a `matched` pair dies whose action `Name` resolves to three nodes in that state
- **THEN** subsequent LLM answers resolving to that `Name` and event type SHALL be banned regardless of which of the three nodes the coordinate falls in
- **AND** the ban count SHALL be reported as one dead pair, never as three widgets withdrawn

#### Scenario: productive execution does not count toward death

- **WHEN** a pair executes with `new_state=false`, then with `new_state=true`, then with `new_state=false`
- **THEN** the pair's accumulated dead-execution count SHALL be 2 (the productive execution neither counts nor resets the count)
- **AND** the pair SHALL NOT yet be dead

#### Scenario: ban is per-run

- **WHEN** a new run starts on the same APK
- **THEN** the ban record SHALL be empty

### Requirement: LLM Unit Lifecycle and Ownership

When the plan carries the LLM feature, `RunContext` SHALL construct and own the LLM units exactly once at bootstrap, wired from the plan's `LlmParams` (never from static `Config`):

- `LlmClient` — transport + circuit breaker as one unit (`llm-infrastructure` capability): base URL, model, `temperature`, `top_p`, `top_k`, `max_tokens` (default `1024`), `timeout_ms`.
- `ScreenshotStep` — `ScreenshotCapture` + `ImageProcessor` + device-dimension determination.
- `ApePromptBuilder` — unchanged, variant from `LlmParams`.
- `ToolCallParser` — unchanged.
- `CoordinateMapper` — coordinate normalization, boundary bands (`llmBoundaryTopPct`/`llmBoundaryBottomPct`), snap tolerance floor (`llmSnapTolerancePx`), back/long-click preference, `fixTextEdit`, and the dead-pair ban record.
- `LlmTelemetry` — all LLM counters and latches, and the sink calls that carry them (`llmCall`, `llmError`, `llmBreakerOpen`, `llmDump`, `llmAck`); it exposes the counters through an accessor for `RUN_END` and prints no summary.
- `LlmEngine` — the thin orchestrator of the Action Selection Pipeline over the units above.

The `max_tokens` value SHALL be read once from `LlmParams` and shared by the request body and the `RUN_START` effective-plan echo, so what the trace reports is what the wire carried. No manifest line SHALL be emitted at `LlmClient` construction: every field it carried (model, temperature, top_p, top_k, max_tokens, timeout_ms, prompt_variant, llm_percentage, on_new_state, on_stagnation, stagnation_threshold, url) is in the echo, which states the whole resolved plan rather than the LLM slice of it.

**Per-request tool schema:** the two schema constants (with and without `type_text`) SHALL be built once at construction and the appropriate one passed to each `chat()` invocation by the same `hasInputField` predicate the system message uses (prompt/wire coherence, INV-LLM-11) — unchanged behavior, now owned by `LlmClient`/`LlmEngine`.

When the plan does NOT carry the LLM feature, none of these units SHALL be constructed and no LLM stage SHALL exist in the decision pipeline (feature absent = stage absent, `decision-pipeline` INV-DP-03; replaces the null `_llmRouter` convention of INV-RTR-01).

All unit references SHALL be final and reused for the entire run; all unit state (counters, latches, ban record, breaker state) is per-run and dies with the process.

#### Scenario: LLM feature in the plan constructs the units once
- **WHEN** the resolved plan carries the LLM feature with `url=http://10.0.2.2:30000/v1`
- **THEN** `RunContext` SHALL construct `LlmClient`, `ScreenshotStep`, `ApePromptBuilder`, `ToolCallParser`, `CoordinateMapper`, `LlmTelemetry`, and `LlmEngine` exactly once
- **AND** the effective values SHALL appear in the `RUN_START` echo, with no manifest line emitted

#### Scenario: LLM feature absent constructs nothing
- **WHEN** the resolved plan does not carry the LLM feature
- **THEN** no LLM unit SHALL be constructed and no LLM stage SHALL be assembled
- **AND** zero LLM-related trace lines SHALL be emitted for the run

#### Scenario: units read the plan, not Config
- **WHEN** any LLM unit needs a sampling, timeout, boundary, or tolerance parameter during the run
- **THEN** it SHALL read the value injected from `LlmParams` at construction
- **AND** no LLM unit SHALL read static `Config` after bootstrap

### Requirement: Action Selection Pipeline

`LlmEngine.selectAction(GUITree tree, State state, List<ModelAction> actions, MopData mopData, List<ApePromptBuilder.ActionHistoryEntry> recentActions, String mode, int step)` SHALL run the LLM decision pipeline over the decomposed units and return `ModelAction` or `null`. The step semantics are **unchanged** from the pre-decomposition `LlmRouter.selectAction`; only the owner of each step changes:

**The argument list is the pre-decomposition one, and it stays that way for a reason worth stating.** `mopData` and `recentActions` are per-step, agent-owned values that step 4 below hands to `ApePromptBuilder.build(...)`, which cannot build a prompt without them. The engine is constructed once per run and owned by `RunContext` (see `LLM Unit Lifecycle and Ownership`), while the per-step view belongs to the stages — so for the engine to source them itself it would have to hold a `StepContext` or the agent, and design D2 exists to prevent exactly that. The calling stage passes them from `ctx.mopData()` and `ctx.actionHistory()`, unchanged.

1. `LlmTelemetry` counts the attempt (`totalCalls++`, per INV-RTR-07).
2. `ScreenshotStep` determines device dimensions and captures the screenshot. Null capture → breaker failure recorded via `LlmClient`, `screenshot_failed` counter + the free-text screenshot-failure diagnostic carrying the activity and the failing stage via `LlmTelemetry`, return null. This is the one abandoned attempt that produces **no** `llm[]` sub-event (INV-RTR-20).
3. `ScreenshotStep` resizes and base64-encodes. Null → `image` cause as an `llm[]` sub-event with `result:"error"`, return null.
4. `ApePromptBuilder.build(...)` builds the messages; `LlmTelemetry` stages the prompt via `llmDump`, which rides the next sub-event as `sys`/`user` when the prompt-dump flag is on (default on).
5. `LlmClient.chat(messages, tools)` — the tools schema chosen by the same `hasInputField` predicate the prompt used (INV-LLM-11). Null → breaker failure, cause read once from the client's error seam (INV-LLM-08), cause counters + an `llm[]` sub-event with `result:"error"` and that `cause` via `LlmTelemetry`, return null.
6. `LlmTelemetry` emits the once-per-run `LLM_ACK` record on the first successful response (INV-RTR-12) and stages the response text, which rides the sub-event as `resp`/`tool_calls` under the same prompt-dump flag.
7. `ToolCallParser.parse(response)` — including the raw-arguments repair pipeline for native tool-call malformations (`SglangClient.ToolCall.rawArguments` → Level 1 → shared `parseJsonString`), surfacing through the existing `repair=` field (INV-LLM-10, INV-RTR-14). Null → `parse` cause (client seam NOT consulted), return null.
8. `CoordinateMapper` normalizes coordinates, applies the boundary bands, maps to a `ModelAction` (containment → snap tolerance → off-tree `LlmTapAction` synthesis → `fixTextEdit` conversion → back/long-click preference), and applies the dead-pair ban check (a banned answer is a refused answer: same caller-visible path as `no_match`, breaker still records success — INV-RTR-15/16).
9. `LlmClient` records breaker success; `LlmTelemetry` accounts tokens/latency, classifies the outcome (`matched`/`llm_tap`/`no_match` + `reason`), computes/receives the nearest-widget fields, and appends one `llm[]` sub-event to the current step's record with the same field set as before (renamed per the `LLM Telemetry Logging` mapping; the step, activity and variant keys are dropped as the parent record's envelope or run-constant).
10. The engine returns the mapped action or null. It SHALL never throw (INV-RTR-02): an unexpected exception is the `internal` cause. Large temporaries SHALL be nulled in a `finally` block (INV-RTR-06).

**type_text handling**: unchanged — when the match is an input-capable widget and text is present, `setInputText(text)` is applied before returning.

**Known defect preserved, deliberately.** "Unchanged" here includes a defect measured at 28 of 1,233 LLM responses (2.3%): a `type_text` answer can execute a `MODEL_LONG_CLICK`. The containment pass restricts the candidate's `ActionType` only when the tool is `"click"` (`LlmRouter.java:689`), and `fixTextEdit` returns the match untouched for any tool that is neither `click` nor `long_click` (`:807`), so the long-click preference can win on a `type_text` answer. This stage is behavior-neutral by contract (parity-gated, R8): `CoordinateMapper` SHALL reproduce this path exactly, defect included. The fix is **out of scope here** and belongs to a separate change against `CoordinateMapper`, whose slicing is precisely what makes it testable in a JVM unit. Recording it is mandatory: a silently inherited defect in a newly extracted unit is indistinguishable from a slicing regression when the parity oracle later disagrees.

The dead-pair outcome feedback SHALL continue to flow from the join-buffer site in `StatefulAgent` — the point where `new_state` is computed for the step record's `out` section — into the ban record, now `CoordinateMapper.recordLlmOutcome(...)` reached through `RunContext`'s LLM units, with unchanged key material and strike semantics.

#### Scenario: Full pipeline success
- **WHEN** `selectAction()` is called with a valid GUITree and the server is responsive, and the LLM returns `click` at coordinates mapping into a widget's bounds
- **THEN** that `ModelAction` SHALL be returned, the step's `llm[]` sub-event SHALL carry `result:"matched"`, and the breaker SHALL record success

#### Scenario: Screenshot capture fails trips the breaker
- **WHEN** `ScreenshotStep` returns null (secure window)
- **THEN** the engine SHALL return null with no HTTP request made
- **AND** the breaker SHALL record a failure and `screenshot_failed` SHALL be counted, with its free-text diagnostic and no `llm[]` sub-event

#### Scenario: Repaired native tool call keeps the repair telemetry
- **WHEN** the model returns a malformed native `tool_calls` arguments string that `ToolCallParser` recovers via the raw-arguments repair pipeline and the coordinate resolves to a widget
- **THEN** the sub-event SHALL carry `repair:"<form>"` and the decision SHALL count under both `matched` and `repaired` (INV-LLM-10, INV-RTR-14 unchanged)

#### Scenario: banned result is refused at step 10, not failed
- **WHEN** the mapped action's ban key has reached the strike threshold
- **THEN** the engine SHALL return null with a `result:"no_match"`, `reason:"dead_pair"` sub-event
- **AND** `LlmClient` SHALL still record success (a refused answer is not a pipeline failure)
- **AND** the check SHALL run inside step 8 above — after the mapping, before the return — so a banned decision is a refused answer rather than a failed pipeline

#### Scenario: Off-tree element becomes a coordinate tap
- **WHEN** the pipeline succeeds with a `click` at in-bounds pixel `(600, 900)` on a 1080x1794 device
- **AND** `CoordinateMapper` finds no widget containing the point and none within the snap tolerance
- **THEN** `selectAction()` SHALL return an `LlmTapAction` of type `MODEL_LLM_TAP` carrying `(600, 900)`
- **AND** the sub-event SHALL classify the outcome `llm_tap`, not `no_match` (the synthesis is a decision, and `Coordinate-to-ModelAction Mapping` owns the unit-level rule this end-to-end path exercises)

#### Scenario: no_match reason is always one of three
- **WHEN** any decision in a run ends as `result:"no_match"`
- **THEN** its sub-event SHALL carry exactly one `reason` from `degenerate`, `boundary`, `dead_pair`
- **AND** the closure SHALL hold across the decomposition: `CoordinateMapper` produces the first two and the ban check the third, and `LlmTelemetry` SHALL have no fourth reason to record

#### Scenario: Engine never throws
- **WHEN** any unexpected exception occurs inside the engine
- **THEN** the engine SHALL catch it, count `internal`, append a sub-event with `result:"error"` and `cause:"internal"`, and return null

## REMOVED Requirements

### Requirement: Effective LLM Config Manifest

**Reason**: subsumed by the `RUN_START` effective-plan echo (run-spec capability, rearch-02): every field of the retired `[APE-LLM-CONFIG]` line (model, temperature, top_p, top_k, max_tokens, timeout_ms, prompt_variant, llm_percentage, on_new_state, on_stagnation, stagnation_threshold, url) is carried by the effective plan in the first trace record. The manifest emitter in the router constructor is deleted outright — no shim, no dual emission (P3). INV-RTR-10 is retired with it; the self-description property it provided is now a property of `RUN_START`.
