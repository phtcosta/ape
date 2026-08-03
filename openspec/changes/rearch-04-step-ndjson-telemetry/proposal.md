## Why

Telemetry today is unescaped `key=value` stdout with no `run_id` anywhere in `src/main` (V15): a newline in source data breaks the line format (the #16 fix flattened newlines *at the origin* — fixing the data to fit a fragile format), the analyst re-joins `[APE-STEP]`/`[APE-OUTCOME]`/`[APE-LLM-TEL]` lines by `step=`, every line repeats defaults (`mop=0 wtg=0 …`) and long activity/state strings, and the trace grows ~3.5 GB per 880 tasks — while cost per step is itself a measured experimental variable (0.037–0.052 pp `cov_mop` per lost step). The baseline arm is blind by construction (INV-ARCH-01), which the owner has decided to dissolve deliberately in favor of universal, provably neutral telemetry.

This change is **stage 4 of 7** of the re-architecture selected in `docs/analise_fable-selecao.md` (rev. 3, Sec. 6.5; owner decisions D2/D4/D5).

## What Changes

- New `EventSink` emitting **one NDJSON record per step** (`StepRecord`): envelope once; decision, LLM calls, and effects appended as an ordered internal list; closed and written (one line, line-buffered) when the outcome resolves at step N+1 — the same timing as the existing join buffer (`StatefulAgent:117-124`), which becomes the record accumulator. Teardown gains `safeStep("flushPendingStep")` writing the in-flight step with `out:{"resolved":false}`; maximum loss on SIGKILL = 1 step.
- Hand-written JSON serializer (~80 lines, zero dependencies) with real escaping — quotes, backslash, control chars, NUL — plus permanent tests: round-trip and the one-line-per-record invariant (report Sec. 9.12).
- Volume reduction: envelope 1×/step; defaults omitted (absent field = default); run-local ID table for repeated strings (`ACT`/`STATE` dictionary events referenced by integer); `run_id` only in `RUN_START`/`RUN_END`. Estimated 3–5× raw reduction; gzip at collection (Python side) for storage at rest.
- Out-of-step records in the same NDJSON: `RUN_START` (from stage 2), dictionary events, `MOP_DATA` load status, and `RUN_END` (reason + counters) as the natural last record. **Owner decision D5: no exit contract** — no sentinel validation in `tool.py`, no task-status changes, no retry logic; truncated-run detection stays post-hoc via timestamps.
- The re-encoding is the moment to fix what the old renderings got wrong, so four increments ride it, each justified by a measurement over the decisive campaign rather than by anticipation. `MOP_DATA` carries the full load census with `wtgEdges` — the number the three frontier passes actually gate on — replacing `transitions`, which is a different structure and has been read as the gate for months; `PIPELINE` carries the *candidate* passes with their enabled flag, without which "the arm turned it off" and "this application's data could not support it" stay indistinguishable, and the family is in fact never constructed in 25 of the 40 applications; `RUN_END` carries the first and last step timestamps, which separate a run that explored for its budget from one that was alive and idle (the campaign contains a `COMPLETED` run with 150 steps in 446 s of 1,871 s); and three per-step fields — `dec.wtgsrc`, `dec.mopx`, `dec.comp` — de-alias a summed boost field whose two producers are configured at the same weight, preserve the MOP exposure denominator that no requirement currently protects, and record whether a component launch was accepted. The per-step three are gated on the INV-SNK-13 measurement like everything else on that path.
- Logcat heartbeat (owner decision D4): one write-only line per step (`s=N t=...`) via `Log.i`, behind a flag, **default on** — placing violation and step in the same file/clock, immunizing the analysis-side wall-clock join against clock skew. The violation↔step join itself stays in the Python analysis pipeline (no runtime APE↔logcat coupling).
- **BREAKING**: telemetry becomes always-on and identical for all arms — deliberately dissolves INV-ARCH-01. Neutrality is testable: sink on/off, same seed ⇒ same decisions (R7).
- **BREAKING** removal of legacy outputs (report Sec. 6.6): `sataGraph.vis.js`, `sataGraph.dot`, per-state `step-*.txt`, `action-history.log`, `sataTimeline.vis.js`, `produce.log`/`consume.log`, and the `[APE-STEP]`-family `key=value` format itself.
- Transport unchanged: stdout captured into the `.trace` as today — and the `.trace` *is* the NDJSON, never rewritten into anything else. There is no NDJSON→legacy converter: writing the fragile `key=value` family back over the primary artifact would invert which file is authoritative and re-import the escaping defect this change exists to remove. The analysis side gains a native NDJSON reader, and `clock_logcat_join.py` — the one production parser of `[APE-STEP]` — migrates onto it here.
- The archived legacy corpus keeps its own frozen readers (`scripts/cmpm_stratify.py`, `experimento-cal/scripts/*`, `calibracao/*`, …). They are not compatibility shims: they read a dataset that is finished, and are deliberately left untouched.
- Acceptance (report Sec. 9.11): the 2026-07-24 calibration report must be regenerable from the new trace — easier, since the ~39k LLM calls hang off the right step by construction. With no converter, the native reader is what satisfies this gate, so it is a deliverable of this stage rather than a later one.

## Capabilities

### New Capabilities

- `event-sink`: StepRecord schema and lifecycle, serializer escaping guarantees, ID-table events, `RUN_END`, heartbeat flag, neutrality requirement, volume rules.

### Modified Capabilities

- `exploration`: legacy output writers removed from the termination path; teardown gains `flushPendingStep` and `RUN_END` emission (INV-EXPL-16/29 mechanism untouched; INV-EXPL-17 artifact defaults unaffected).
- `action-selection`: the `[APE-STEP]` per-action telemetry requirement (INV-SEL-04) is restated as the `StepRecord` envelope + `dec` section; the `stepTelemetryEnabled` gate dies.
- `scoring-pipeline`: the `[APE-OUTCOME]` attribution requirement (INV-ARCH-08/09) is restated as the `out` section, join by construction; **INV-ARCH-01 is dissolved here** (verified location: `openspec/specs/scoring-pipeline/spec.md`, not `exploration`), substitute recorded (universal neutral telemetry + neutrality test R7).
- `llm-routing`: LLM telemetry becomes sub-events of the step record (`[APE-LLM-TEL]`/`[APE-LLM-ERROR]` retired; `[APE-LLM-CONFIG]` subsumed by `RUN_START`; summary counters fold into `RUN_END`). `Deterministic Dead-Pair Ban` is re-anchored with them: it is the one requirement outside the telemetry set that normatizes the retired renderings by name, and its data survives untouched as `result:"no_match" reason:"dead_pair"` in the `llm[]` sub-event.
- `model`: `Tolerant Action-History Persistence` is removed — its whole subject is `Model.saveActionHistory`, which this change deletes along with `action-history.log`. Teardown robustness stays with the `safeStep` isolation (INV-EXPL-16/29) that always carried it.
- `wtg-navigation`, `llm-prompt`: one sentence each, re-anchored off the retired renderings. The frontier boost's observability moves from `[APE-STEP] ... wtg=` to `dec.wtg`; the widget-list newline flattening keeps its prompt-side reason and drops its `[APE-LLM-PROMPT]` one. No mechanism changes in either.
- `aperv-tool`: trace collection gzips at pull and leaves the NDJSON `.trace` intact; a native NDJSON reader replaces the `[APE-STEP]` regex in `clock_logcat_join.py`, whose UTC-offset reconstruction dies with the D4 heartbeat. No task-status or validation changes (D5).

## Impact

- **Java**: new `EventSink`/`StepRecord`/serializer; `StatefulAgent` join buffer repurposed; `Logger` usage retired from the step path; teardown chain (+1 safeStep, isolation INV-EXPL-16/29 untouched); legacy output writers deleted.
- **Python/rv-android**: gzip at collection; the analysis pipeline gains the native NDJSON reader and `clock_logcat_join.py` migrates onto it (a net simplification — the offset reconstruction is deleted). The frozen-corpus scripts are not touched.
- **Depends on**: `rearch-02-runspec` (`RUN_START`, flags in plan), `rearch-03-decision-pipeline` (decision sub-events carry `decision_source` from stages).
- **Frozen metrics untouched** (R9): primary outcomes still come from logcat/instrumented APK.
- Grounding: report Sec. 6.5, 6.6, Sec. 12 D2/D4/D5, verified V15/V16/V17, Sec. 9.7/9.8/9.11/9.12.
