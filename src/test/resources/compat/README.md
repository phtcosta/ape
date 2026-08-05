# Python-contract compat fixtures (`rearch-02-runspec`, task 6.1)

Each `.properties` file here is a **byte-for-byte capture of what `aperv-tool` actually pushes to
`/data/local/tmp/ape.properties`** for one experiment arm. They exist so `RunSpecCompatTest` can
prove that the jar's `RunSpec.resolve` accepts what the deployment really sends — not what the
arm dictionaries look like when read by eye.

## Source pin

Regenerating or interpreting these files requires knowing exactly which Python they came from:

| | |
|---|---|
| Source file | `rv-android/modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py` |
| `sha256` | `aba920ea68f17bb4ea0c346a48bc4a1f63f7a2cc67df7a80df0f32e3d0c93ae8` |
| `rvsec` commit | `d8f1df0a251482d283f9f5a7f3a4628f26a29b09` (2026-08-04) |
| Branch | `rearch-counterparts` |
| Captured | 2026-08-04 |

That commit is the `gh93` counterpart (`rvsec#93`), which retired `ape_pure_mode` from the arm
surface. **These fixtures are only valid against a `tool.py` at or after it** — every one of them
would otherwise carry `ape.apePureMode`, which this stage's jar aborts on as a retired key.

## Two things these fixtures are NOT, as of 2026-08-05

**They no longer match the `tool.py` they are pinned to.** `sha256sum` the source file above: it
now reads `30d24ae5…`, not the pinned `aba920ea…`. The drift is `gh95`'s and it is structural, not
cosmetic — `cc9eebcf` made the pushed properties **preset-first**, so what the deployment sends
today is `ape.preset=<name>` plus one line per override delta, where these files carry the fully
expanded flag vector. Two of them (`ape_pure.properties`, `sata_mop_widget.properties`) name arms
that `4ed6ab1c` retired and that `get_variants()` no longer returns at all. Regenerating them is
`gh95`'s deliverable, in the change that decided the new format; nothing in `rearch-04` can
capture a format that change is still landing.

**One line was removed from them by hand, and it is the only one.**
`rearch-04-step-ndjson-telemetry` deletes `ape.stepTelemetryEnabled` from the jar (telemetry is
always on and owned by no feature — event-sink INV-SNK-07), so a fixture stating it would abort as
an unknown key and take `RunSpecCompatTest` red with it. The line was removed rather than the whole
file recaptured, because recapturing would mean adopting the preset-first format above and doing
`gh95`'s migration inside this change. This is a deliberate, bounded exception to "captured, not
transcribed": nothing was added or reworded, and the removed key is one the deployment had already
stopped pushing — `4ed6ab1c` dropped `step_telemetry_enabled` from the arm variants before this
change touched the jar, which is the same order `gh93` established for `ape_pure_mode`.

**What that means when one of these tests goes red.** The first question is still
`sha256sum tool.py`, and the answer is already known to be "different". Until `gh95` regenerates
them, these files prove something weaker than their name suggests: that the jar accepts the *shape*
of arm the deployment used to send, not the one it sends now. Read a failure accordingly.

**Why the pin matters.** Without it, a future `RunSpecCompatTest` failure is undiagnosable: the
reader cannot tell whether the jar regressed or whether the Python side moved on and the fixtures
went stale. With it, the first step is always `sha256sum tool.py` — if it differs from the value
above, regenerate before debugging the jar.

## How they were captured

By **executing** `ApeRVTool._push_properties` with only `_push_file_to_device` stubbed out, then
saving the temp file it wrote. They were deliberately *not* transcribed from the variant dicts,
because `_push_properties` is not a pretty-printer for those dicts — it applies three
transformations that a hand-written fixture reliably gets wrong:

1. It writes only keys present in **both** `_tool_config` and `APERV_PROPERTY_MAPPING`, so
   Python-only orchestration keys (`strategy`, `mop_data`) never appear.
2. It serializes Python `bool` as lowercase `true`/`false`. The `isinstance(value, bool)` branch
   precedes numeric handling deliberately — `bool` is an `int` subclass, and without that ordering
   the flags would serialize as `True`/`False` and fail the jar's strict boolean parse.
3. It **prepends** `ape.mopDataPath=/data/local/tmp/static_analysis.json`, and only when the MOP
   JSON was actually pushed — which is why that key leads the two MOP fixtures and is absent from
   the other three.

## The fixtures

| Fixture | Arm | Keys | `mopDataPath` | Shape |
|---|---|---|---|---|
| `sata.properties` | `sata` | 18 | no | The campaign baseline: 17 arm-defining flags + throttle |
| `sata_mop_widget.properties` | `sata_mop_widget` | 23 | **yes** | `sata` + the 4 MOP substrate weights |
| `sata_llm.properties` | `sata_llm` | 26 | no | `sata` + the 8 `_LLM_FLAGS` sampling keys |
| `sata_mop_llm.properties` | `sata_mop_llm` | 31 | **yes** | `sata` + MOP + LLM |
| `ape_pure.properties` | `ape_pure` | 18 | no | Same 18 keys as `sata`, 12 flags at their off values |

`ape_pure` is the interesting one: it is **structurally pure**, not pure by a jar-side switch. All
17 arm-defining flags are stated explicitly at their off values, so the original-APE baseline is
auditable from `ape.properties` alone without trusting the jar to force RV off. That is why
retiring `ape.apePureMode` does not break the arm.

Two keys are **deployment-specific** and therefore appear here but not in the corresponding jar
preset (design D-3): `ape.mopDataPath` (where the static-analysis JSON landed on this device) and
`ape.llmUrl` (which SGLang endpoint this deployment talks to). `PresetsTest` adds exactly these two
back when it asserts preset ≡ fixture.

`ape.llmPercentageNoSubstrate=-1` appears on **all five** fixtures, including the three with no LLM.
`-1` is that key's declared neutral value, so on a non-LLM plan it is inert rather than an abort —
the inert-neutral rule in design D-2. The non-LLM fixtures exercise it.

## Not goldens

These are **not** parity-oracle goldens and are not covered by INV-ORA-07. The goldens live in
`../goldens/` and are frozen through stage 3; these fixtures may be regenerated whenever `tool.py`
changes, provided the pin above is updated in the same commit.

## Regenerating

Run against an rv-android checkout that carries the counterpart, capturing the new sha:

```bash
cd <ws>/rvsec/rv-android
sha256sum modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py   # update the pin above
uv run python <script> <ws>/ape-rearch/src/test/resources/compat
```

The generator is ~60 lines and lives in the task log for `rearch-02-runspec` group 6; it is not
vendored here because it must import `aperv_tool` from the *other* repo's workspace venv.
