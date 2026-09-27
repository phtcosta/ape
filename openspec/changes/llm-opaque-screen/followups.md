# Follow-ups outside this change

## rv-android (task 5.3)

- `openspec/specs/aperv/spec.md:760` stays correct in structure: `ape.llmPercentageNoSubstrate` is
  still an `LLM_RANDOM` sub-parameter with neutral `-1`. Only its meaning changes there:
  `-1` = opaque routing off, `>= 0` = on (`0` = new-state/stagnation only on opaque steps whose tree
  holds a dynamic region, `> 0` also the `LlmRandom` rate on those steps; see the last item below). No code change: `tool.py:198` already maps the key, and
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
  Task 13 adds one clean data point: on shatteredpixeldungeon's title screen all 53 answers were
  `click` at y ≈ 980 (0.98 h) and all 53 were rejected by the bottom band, not by a null answer
  (`evidence.md`, device validation). The answered point is the "Enter the Dungeon" button, a real
  target wholly inside the bottom band; relaxing the band on opaque ∧ `dyn` steps was left out of
  this change (owner's scope decision) and is the open question for E5d.
- **APE action extraction, not the LLM gate.** Two false-opaque screens seen on the way:
  flyingcarpet's About dialog has a scrollable `android:id/scrollView` that the compressed tree
  drops (not focusable), so its scroll action is lost; AnyMemo's CardPlayer settings dialog has
  SeekBars that yield no model action. Both are candidates for a separate change.
- **rv-android spec row.** `openspec/specs/aperv/spec.md:760` should also say that `>= 0` routes
  only opaque steps whose tree has a dynamic region.

## From the second revision (D11, D12)

- **Boundary bands against the system bars actually present, on every step (D11 alternative B).**
  The bands are measured against `Display.getSize()`, which already excludes the navigation bar, so
  on ordinary screens the bottom band removes the app's own footer: smokingtracker's bottom tabs
  (y = 1720) and urlchecker's Back/Next (y = 1705) sit in it on the 1080×1794 frame
  (`evidence.md`). Applying the bottom band only when the app's frame reaches the physical height
  is the general rule; it changes every LLM arm already measured (E5b, E5c), so it waits for a
  campaign that can absorb that.
- **Screenshot fallback frame.** `ScreenshotCapture.captureViaSurfaceControl` crops to
  `Rect(0, 0, w, h)` from `Display.getSize()`, but the fallback `captureViaUiAutomation` does not
  crop: its bitmap is the full physical screen, while the mapping still uses `Display.getSize()`,
  so the model's normalized answer would be scaled onto a shorter frame than the image it saw. The
  fallback usually fails from `app_process` (it needs `InstrumentationRegistry`), so the case has
  not been observed; cropping it to the same frame, or mapping against the image's own size,
  would align the two.
- **What D12 leaves.** A tap answer with no recoverable coordinate (an empty `arguments`, a single
  number) still parses as `0` on the missing axis. Making the parser return no action for it
  would fix the degenerate answer at the root, but a parse failure counts against the circuit
  breaker on every arm; the mapper's zero-axis rejection covers it on opaque dynamic steps and the
  top band on the others (a zero `x` alone still becomes a tap on the left edge there).
