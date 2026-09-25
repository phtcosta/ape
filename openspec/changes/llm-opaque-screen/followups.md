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
