# Acceptance Sec. 9.11 — the calibration report is regenerable from the new format (tasks 9.1, 9.2)

Run 2026-08-07, offline, on the six traces `gh97-rearch-ab-gate` task 7.2b left behind. No device
was touched: 9.1's run was delegated to that change's smoke (owner decision 2026-08-05) and what was
owed here was confirming the record exists, not producing a new run.

Reproduce with `acceptance_911.py` beside this file:

```bash
PYTHONPATH=<rv-android>/modules/aperv-tool/src python3 acceptance_911.py
```

## Verdict

**PASS at the format level, with one reader-side gap filed.** Every quantity the 2026-07-24
calibration report consumed is present in a new-format trace, and all but one family is reachable
through `trace_ndjson.TraceReader`, which is the only sanctioned way to read one. The exception is
the `RUN_START` provenance block — the schema carries it, the reader drops it. That is a gap in the
reader, not in the schema, which is why this task's "file any gap as a schema fix" produced no schema
change: there is nothing missing from the wire to add.

## 9.1 — the sample trace exists, and covers more than the delegation promised

`gh97` 7.2b recorded the traces at

```
experimento-rearch-aperv/results_smoke/rearch_aperv_smoke/rearch_aperv_smoke/
  <apk>/<apk>__1__300__aperv:<arm>.trace          (+ .ndjson.gz, + .logcat beside each)
```

Six runs, two APKs × three arms, 300 s, verified present on 2026-08-07. The tree is untracked and
root-owned and is never `git add`ed, which is why this file records the path rather than the data.

All four required clauses are met, and **the two clauses D11 wrote off as un-guaranteeable inside a
reduced-timeout smoke both appeared on device**:

| 9.1 clause | Status | Evidence |
|---|---|---|
| MOP boosts | present | 15 / 17 / 158 / 190 boosted steps on the four MOP arms |
| LLM calls | present | 142 on packagemanager, 92 on freeotpplus (`mop_on_llm_70`) |
| flushed final step | present | `len(steps)` equals `RUN_END.steps` in all six runs |
| `RUN_END` | present | all six, `reason=timeout` |
| LLM **error** (optional) | **present** | `result=breaker_open`, `trips` 1 and 2, freeotpplus `llm_70` |
| `no_match reason=dead_pair` (optional) | **present** | 20 + 17 = **37** across the two `llm_70` runs |

So 9.1 rests on device evidence for every clause instead of leaning on `gh94`'s golden-fixture tests
for the last two. Those tests remain the right home for the schema-level assertion.

## 9.2 — every consumed quantity, mapped and recomputed

The corpus is not the report's — that one is frozen, legacy, and 880 traces of a different
experiment. The claim under test is **recoverability**, not reproduction of the numbers: each
quantity the report consumed is recomputed here from the new format, and reported with the value it
takes on this corpus.

### Report §1.2 markers → new-format sources

| Report marker | Quantity it supplied | New-format source | Status |
|---|---|---|---|
| `[APE-STEP]` | step count | one step record per action | ✅ |
| `[APE-STEP] clock=` | `clock_span_ms` (active span) | `t` (run-relative), `t0` for epoch | ✅ |
| `[APE-STEP] action=` | executed action type | `dec.a` | ✅ |
| `[APE-STEP] decision_source=` | decision source | `dec.src` | ✅ |
| `[APE-OUTCOME] new_state=` | new-state yield | `out.new_state` | ✅ |
| `[APE-OUTCOME] activity_changed=` | activity change | `out.activity_changed` | ✅ |
| — (join of the two) | yield **per source** | same record — no join key to get wrong | ✅ improved |
| `[APE-LLM-TEL] call=` | call ordinal | `llm[].call` | ✅ |
| `[APE-LLM-TEL] mode=` | new-state / random / stagnation | `llm[].mode` | ✅ |
| `[APE-LLM-TEL] result=` | matched / llm_tap / no_match | `llm[].result` | ✅ |
| `[APE-LLM-TEL] tokens_in/out=` | token cost | `llm[].tok` | ✅ |
| `[APE-LLM-TEL] time_ms=` | per-call latency | `llm[].ms` | ✅ |
| `LLM Summary calls/time_ms/matched/no_match` | per-task aggregate | aggregate over `llm[]` | ✅ derived |
| `LLM Summary repaired` | repaired responses | `llm[].repair` (form, not just a flag) | ✅ improved |
| `LLM Summary breaker_trips` | breaker trips | `llm[].trips` on `result=breaker_open` | ✅ |
| `[APE-LLM-ERROR]` | the 152 pre-decision failures | `llm[]` with `result=error`, `cause`/`detail` | ✅ improved |
| `[APE-LLM-CONFIG]` | temperature, prompt_variant, llm_percentage, on_new_state | `RUN_START.params` | ✅ |
| `[APE-LLM-CONFIG]` | top_p, top_k | omitted **when at jar default**, recoverable via `build.sha` | ⚠️ see below |
| `*** INFO *** Select action` | ANC1 (`ape:default`) steps | not applicable — legacy arm, unchanged | n/a |
| `tasks.json` | `execution_time_seconds`, state | unchanged, platform-side | n/a |
| `per_apk_paired.csv` | coverage metrics | unchanged, `coverage.csv` | n/a |

Three of these are marked *improved* rather than merely recovered, and the reason is the same in each
case: attribution that the legacy format left to a join is now structural. An LLM call lives inside
the step record it belongs to, so `[APE-LLM-TEL] step=N` cannot be mis-joined; `repair` carries the
repair *form* rather than a boolean; and a call that failed before producing a decision now emits an
`llm[]` entry instead of only an `[APE-LLM-ERROR]` line with no TEL counterpart — which is exactly
the accounting that cost the report's §1.3 a correction (the 152 double-counted events).

### The report's own correction §13-C1 cannot recur

The report records that its `[APE-STEP]` regex silently dropped `EVENT_TRIGGER_ACTIVITY` steps,
undercounting steps and therefore overstating cost-per-call. In the new format those are ordinary
step records carrying `dec.src="Component"` and `dec.comp`, and this run counts them: 2, 6, 3 and 3
on the four arms that dispatch components, 0 on the two that do not — matching the component-dispatch
count exactly.

The trap that produced the undercount is worth stating once, because it survives into any new
analysis that buckets by action type: `@` means opposite things in the two action shapes.
`g0a0[...]@MODEL_CLICKclass=...` puts the type *after* it; `EVENT_TRIGGER_ACTIVITY@org.example.Foo`
puts the *target* after it. A regex for `@MODEL_[A-Z_]+` matches the first and silently drops the
second. `acceptance_911.py` handles both and asserts no step falls through to `?`.

### Regenerated quantities

| Run | Steps | Span s | ETA | new_state | act_chg | Calls | LLM s | Tokens in/out | Repaired | Trips |
|---|---|---|---|---|---|---|---|---|---|---|
| packagemanager `mop_off_llm_off` | 303 | 295.7 | 0 | 59 | 43 | 0 | 0.0 | — | 0 | 0 |
| packagemanager `mop_on_llm_70` | 234 | 296.3 | 2 | 28 | 22 | 142 | 102.1 | 194.218 / 3.710 | 131 | 0 |
| packagemanager `mop_on_llm_off` | 307 | 296.2 | 6 | 46 | 32 | 0 | 0.0 | — | 0 | 0 |
| freeotpplus `mop_off_llm_off` | 292 | 293.5 | 0 | 8 | 3 | 0 | 0.0 | — | 0 | 0 |
| freeotpplus `mop_on_llm_70` | 227 | 294.6 | 3 | 16 | 14 | 92 | 68.2 | 180.718 / 2.345 | 88 | 2 |
| freeotpplus `mop_on_llm_off` | 273 | 295.6 | 3 | 11 | 11 | 0 | 0.0 | — | 0 | 0 |

**§2.1 dose-response** — the report's central shape reappears on this corpus: the LLM arm spends
102,1 s and 68,2 s of its 300 s budget in blocking inference and executes the fewest steps of the
three arms on both APKs (234 vs 303/307; 227 vs 292/273). **§2.1's clock-delta premise** is
recomputable and holds: the inter-step delta is 1.466 ms when the step made an LLM call against 973 ms
when it did not (packagemanager), and 1.513 ms against 1.160 ms (freeotpplus) — the wall clock has the
inference inside it, which is what makes the reallocation visible.

**§2.3 latency by mode** — new-state calls are the slower kind here too: 797,6 ms (n=28) against
700,1 ms for random on packagemanager; 852,7 ms (n=6) against 751,3 ms on freeotpplus.

**§3.1 yield of new state per decision source** — recomputed per arm, and now exact rather than
joined. On packagemanager `mop_off_llm_off`: Coverage 31,09 % (n=119), SATA 11,36 % (n=176), Budget
28,57 % (n=7). On `mop_on_llm_70`: LLM 16,53 % (n=121), SATA 9,09 % (n=66), Coverage 6,06 % (n=33).

**§6 / §12-bis.8 `no_match` decomposition** — `llm[].reason` carries it directly: `dead_pair` 20/142
(14,1 %) on packagemanager and 17/92 (18,5 %) plus `boundary` 7/92 on freeotpplus. The report's
dominant finding — that a quarter of calls repeat a dead pair at zero yield — is measurable on the
new format without the response-text forensics §6.1 needed, because the reason is a field.

**§6.1 response-path split** — recoverable and finer than the report's: `repair` gives the form, and
this corpus shows `missing_y` 86 and `quoted_xy` 2 of freeotpplus's 92 calls. The prompt/response
dumps (`sys`/`user`/`resp`/`tool_calls`) survive the reader, so the decomposition that gated a change
decision is re-runnable over new traces.

### `clock_logcat_join.py`, the task's second named component

`join_tree()` over the smoke tree joins all six runs and places **27 of 27** violations, each with
`step`, `activity`, `state_key` and `seconds_from_first_step` attached (6 + 6 + 15 on packagemanager;
freeotpplus is not a crypto app and produces none). The join runs entirely on heartbeats and
`RUN_START.t0` — there is no offset reconstruction left to exercise, which is `gh94` 5.5–5.9 landing
as described.

## The gap, filed

**`RUN_START`'s provenance block is on the wire and unreachable through the reader.** The record
carries `features`, `preset`, `agent`, `seed`, `inert`, `corpus_basis`, `digest`, `props_digest`,
`build` and `v`; `RunStart` is constructed from `run_id`, `t0` and `params` and drops the rest.

This is the same class of finding as task 8.1a, which surfaced `MOP_DATA`/`PIPELINE`/`LLM_ACK` after
they were found unreachable, and it has the same shape of fix: those three are kept whole by
`_without_type(record)`, so the omission here is an inconsistency within one reader rather than a
policy.

What it costs, stated precisely rather than dramatically:

- **`features`** is the list a reader is supposed to assert on — `RunSpecEcho` omits any parameter
  sitting at its jar default *because* the active feature set is echoed separately, so asserting on
  `params` keys instead is reading the one surface that was designed to be incomplete.
- **`preset`** is the declared arm name (`llm_mop`, here), which is what the report's §1.3
  arm-attribution check compared against `manifest.json`.
- **`build.sha`** is the documented route to every omitted default — including the `top_p`/`top_k`
  marked ⚠️ above, which were at 0.6/50 and omitted for that reason. Without `build`, the recovery
  route the echo's own javadoc names is closed.

**It is not fatal to arm identification, and the mitigation is already in the reader.** `pipeline`
*is* exposed, and its `stages` distinguish the three arms unambiguously on this corpus:
`[Budget, SataChain]` for `mop_off_llm_off`, `[Budget, MopLauncher, SataChain]` for `mop_on_llm_off`,
and `[Budget, LlmNewState, LlmStagnation, LlmRandom, MopLauncher, SataChain]` for `mop_on_llm_70`. So
the arm is inferable from what was assembled; what is not directly readable is what was *declared*.

**Why this file does not fix it.** The reader lives in `rv-android`
(`modules/aperv-tool/src/aperv_tool/analysis/trace_ndjson.py`), whose changes are being closed in a
separate session; the owner's instruction on 2026-08-07 was to leave that side untouched. The fix is
small and local — carry the remaining `RUN_START` members onto `RunStart`, or expose the record
whole as the three census records already are — and belongs to a Python-side change with a test, not
to a hand-edit from here.

**Why it does not block this change.** D1 says nothing reads the `RUN_START` line for control flow,
and exposing provenance would not alter that — `mop_data`/`pipeline`/`llm_ack` are precedent for
read-only surfacing without creating an exit contract. The acceptance this task states is that the
report's quantities are recoverable from the new trace; they are. One family currently requires
reading the record directly rather than through the reader, and that is recorded here so the next
consumer does not conclude the data was never written.

## What this measurement did not do

- It did **not** reproduce the report's *values*. Different corpus, different apps, three arms
  instead of eleven, n=2 APKs instead of 40. Nothing here confirms or disputes any calibration
  finding; the subject is the format's sufficiency.
- It did **not** exercise the `stagnation` routing mode — no call in either LLM run used it, so that
  one `mode` value is unobserved rather than verified. It is a string field on a path the schema
  fixes, and `gh94`'s fixture tests assert the field, but this corpus does not contain one.
- It did **not** test the ANC1 (`ape:default`) arm, which emits no NDJSON at all. Its step counting
  goes on using the shared `SATA end step [N]` marker.
