# Follow-ups outside this change

## rv-android (task 5.3)

- `openspec/specs/aperv/spec.md:760` stays correct in structure: `ape.llmPercentageNoSubstrate` is
  still an `LLM_RANDOM` sub-parameter with neutral `-1`. Only its meaning changes there:
  `-1` = opaque routing off, `>= 0` = on (`0` = new-state/stagnation only on opaque steps, `> 0` also
  the `LlmRandom` rate on opaque steps). No code change: `tool.py:198` already maps the key, and
  `tests/migration/test_jar_tables.py:86-87` (type `DOUBLE`, default `-1.0`) and
  `tests/test_runspec.py:65` (`inert` on a non-LLM plan) still hold.

## Baseline golden (task 0.1)

`golden-e93dea86.txt` holds the `digest`, `features`, `inert` keys and `params` jar `e93dea86`
resolved for the `llm` and `llm_mop` presets with `ape.llmUrl` set and
`ape.llmPercentageNoSubstrate=-1` (agent `sata`). `RunSpecResolveTest.sentinelPlanDigestUnchanged`
pins them.

## From the E5c investigation (2026-09-27)

- **Next in-loop experiment (Study 03 session).** E5c ran every container off r1, off r2, on r1,
  on r2, and coverage falls with position; seeds are unpaired and within-arm SD is 5.9 pp per task
  over 2 reps. The next comparison should interleave or randomize arm order per container and use
  at least 3 reps. `dec.dyn` lets it split opaque steps into routed and not routed on both arms.
- **Boundary recount.** E5c's `reason:"boundary"` also counts `type_text` and other null answers.
  Recount shatteredpixeldungeon's 245 by cause (band vs other) from the `llm[]` sub-events before
  any change to `ape.llmBoundaryTopPct`/`ape.llmBoundaryBottomPct` on dynamic steps.
- **APE action extraction, not the LLM gate.** Two false-opaque screens seen on the way:
  flyingcarpet's About dialog has a scrollable `android:id/scrollView` that the compressed tree
  drops (not focusable), so its scroll action is lost; AnyMemo's CardPlayer settings dialog has
  SeekBars that yield no model action. Both are candidates for a separate change.
- **rv-android spec row.** `openspec/specs/aperv/spec.md:760` should also say that `>= 0` routes
  only opaque steps whose tree has a dynamic region.
