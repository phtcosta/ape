# Throughput gate evidence — INV-SNK-13 (task 9.1a)

Measured 2026-08-05, 13:31–13:50 BRT, on the owner's workstation (64 cores, load 3–5 throughout,
no build or test suite running concurrently). Three `rv-experiment` runs, executed by the assistant
under the owner's session-17 authorisation — *"ok rv-experiment … voce controla as execucoes,
trocando o jar antes de executar cada uma"* — which is the route the rv-android `CLAUDE.md` rule
prescribes: `rv-experiment` owns the whole emulator lifecycle and no emulator was ever managed by
hand.

A throughput gate with three numbers and no file is a claim; this file is the record, so that a
later session can verify a device run it did not perform.

## Verdict

**PASS, with a stated limit.** The post-change jar produced **more** steps than either pre-change
run — there is no regression to attribute, so the gate does not bind and no field needs dropping.
The limit is that this arm exercised none of the three per-step fields the spec singles out
(§ "What this measurement did not price").

## The three numbers

| Run | Jar | sha256 | Steps | Counted how |
|---|---|---|---|---|
| `r04_9_1a_pre` rep 1 | pre-change (E3 article) | `386ce08d…` | **310** | `[APE-STEP]` lines |
| `r04_9_1a_pre` rep 2 | pre-change (E3 article) | `386ce08d…` | **306** | `[APE-STEP]` lines |
| `r04_9_1a_post` rep 1 | post-change (stage 4) | `5cebabc5…` | **335** | NDJSON `StepRecord`s |

- Pre-change mean **308.0**; the spread between the two pre-change runs — the measurement's own
  variation — is **4 steps** (1.3 %). The gate's floor is therefore **304**.
- Post-change **335**, which is **+27 steps (+8.77 %) above the pre-change mean**. It does not fall
  below the floor; it does not fall below the mean at all.

The gate is stated as steps per wall-clock, and it holds on either denominator:

| Run | steps/min at the nominal budget (300 s) | steps/min over `execution_time_seconds` |
|---|---|---|
| pre rep 1 | 62.0 | 51.1 (364 s) |
| pre rep 2 | 61.2 | 52.3 (351 s) |
| **post** | **67.0** | **56.9** (353 s) |

The second column is the check that nothing anomalous (long boot, install retry) inflated one run's
real denominator. It does not change the verdict: the post-change run leads on both.

## Why the counts are trustworthy

**Each format was counted with its own counter.** The two jars write different formats, and a
single counter silently returns zero on the other one — `TraceReader` over a legacy trace yields no
steps, which would have read as an infinite speed-up. Both counters were run over all three traces
and each trace was classified by which counter fired: the two pre-change traces are `legacy`
(310/306 `[APE-STEP]`, 0 NDJSON) and the post-change trace is `ndjson` (335 NDJSON, 0 `[APE-STEP]`).
A zero here is always visibly a zero-of-the-wrong-counter, never a measurement.

**The post-change count has three independent witnesses that agree on 335:**

1. 335 NDJSON `StepRecord`s parsed from the trace;
2. `RUN_END.steps` = 335, which the sink writes itself;
3. 335 `ApeRvHb` lines in the `.logcat`, running `s=1 t=3437` through `s=335 t=297673` — the last
   heartbeat at 297.7 s of the 300 s budget, so the budget was consumed rather than cut short.

The pre-change runs have only the first witness, because that jar emits no heartbeat: their
`.logcat` files are 117 bytes of logcat banner and nothing else. That absence is itself confirmation
that the format cut is real and that the right jar was deployed for each phase.

**Jar identity was established by digest, not by `build.sha`.** Both phases recomputed the sha256 of
the deployed file immediately before launching, and the digest was written into the run log's first
lines. A worktree build stamps `build.sha` with `../ape`'s master commit — the post-change
`RUN_START` here carries `{'sha': 'c638142', 'time': '2026-08-05T05:08:15Z'}`, which identifies
nothing about which jar ran.

**The control held.** All three runs carry `tool_config.parameters == {'seed': '42'}` in
`tasks.json`, and `seed` reaches the device as `-s 42` on the Monkey command line
(`aperv-tool/.../tool.py:116` puts it in `APERV_ORCHESTRATION_KEYS`; `:1042-1044` appends the flag).
The wall-clock budget is identical by construction: `running_minutes = max(1, 300 // 60)` = 5 min in
all three. Same APK (`cryptoapp.apk`, the only one in `apks_examples/`), same arm (`aperv:default`),
runs back to back on a quiet machine.

The seed check the task's plan prescribed as its second witness — the `:Monkey: seed=<n>` line
printed to stdout — **is not available**: the trace contains no `:Monkey:` line at all, in either
format. The config record plus the code path above is what stands in its place.

## Volume, as a supporting observation

Not a gate, but it is the same measurement and it answers the Volume Rules requirement's empirical
question in the same direction:

| Run | trace bytes | bytes/step | lines/step |
|---|---|---|---|
| pre rep 1 | 2,409,954 | 7,774 | 65.0 |
| pre rep 2 | 2,397,588 | 7,835 | 65.7 |
| **post** | 1,852,735 | **5,531** | **54.0** |

The post-change trace is **~29 % smaller per step** while producing more steps. These are
whole-trace figures — the trace carries APE's other output besides step records — so this prices the
per-step cost of the file as a whole, which is what the wall-clock budget actually pays for.

## What this measurement did not price

The arm was `aperv:default` against the **uninstrumented** `apks_examples/cryptoapp.apk`, with
`--skip-monitors --skip-instrument --skip-static`. A field census over all 335 post-change records
shows `dec` carrying `a`, `src`, `ch` and `pri` on every step, `patched` on 329, `cf` on 14 and
`cov` on 7 — and **none** of `dec.wtgsrc`, `dec.mopx` or `dec.comp`, the three per-step fields this
change adds beyond the retired line family. There were no LLM calls and therefore no prompt dumps.

So what the gate priced is the **base** per-step write path: the once-per-step envelope, the
activity/state dictionaries, defaults omitted, the per-step logcat heartbeat, and the full-trace
gzip on the collection path (`*.trace.ndjson.gz` was produced on every run). That path is faster
than the family it retires, decisively.

What it did **not** price is the marginal cost of the three added fields — and `dec.mopx` in
particular, which the spec describes as present on essentially every *guided-arm* step and names as
the first field to drop if the gate binds. Pricing those needs a guided arm, which needs MOP data,
which needs the static-analysis JSON this run deliberately skipped. The scenario as written asks for
"one arm" and this discharges it; the caution's full scope is owed to a guided-arm repeat, which the
`gh97` campaign runs on the post-change jar anyway. Recording this as owed rather than rounding it
up is the point of the sentence.

## Provenance

- Results: `rv-android/results/r04_9_1a_pre/`, `rv-android/results/r04_9_1a_post/` (branch
  `rearch-counterparts`; `results/` is gitignored, so these paths are the only copy).
- Task ids — pre rep 1 `4cf84639-84d0-4afc-b74c-639aeb9d178a`, pre rep 2
  `82d5f089-ff37-4be7-8cec-19c3066bd031`, post `62784241-8e7d-4e23-b3f0-fe6b95a84c7f`.
- Run logs with the per-phase digest and load average: session scratchpad
  `r04_9_1a_pre.log`, `r04_9_1a_post.log`.
- The control jar is preserved at `backup/gh97-prechange-jar/ape-rv.jar` and was never modified; the
  stage-4 jar was preserved at `backup/rearch-04-9.1a/ape-rv.jar.stage4` before the swap and is
  redeployed at `modules/aperv-tool/src/aperv_tool/tools/aperv/ape-rv.jar`, verified `5cebabc5…`,
  because `gh97` depends on it being there.
- An earlier attempt at 13:28 was stopped by the owner ~40 s in and is kept, unused, at
  `backup/rearch-04-9.1a/aborted_r04_9_1a_pre_1328/`. Nothing from it enters the numbers above.
