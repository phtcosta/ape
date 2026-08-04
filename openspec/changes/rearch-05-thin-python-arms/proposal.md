## Why

The meaning of experimental modes lived in Python: `tool.py` hardcoded the arm matrix over a property mapping, with pytest guards that validated Python constants against Python constants (INV-APV-14) and a kill-switch list duplicated from — and divergent with — the jar's — the split-brain verified as V20. With presets resident in the jar since `rearch-02-runspec`, the Python side becomes thin: an arm is a preset name plus explicit overrides.

This change is **stage 5 of 7** of the re-architecture selected in `docs/analise_fable-selecao.md` (rev. 3, Sec. 5.1, Sec. 10).

**This proposal was rewritten on 2026-08-04 because its predecessor described a plan that no longer matches reality, and had not matched it for some time.** It was authored against a pre-`gh95` tree and asserted, as the frozen constraint of every task group, that *27 arms survive and two (`ape_pure`, `bfs`) are retired*. The rv-android counterpart `gh95-thin-python-arms` — at 37/44, with only final verification pending — shipped something materially different: **eight names carrying seven distinct configurations, with 21 names retired.** The divergence was not a difference of opinion between two open plans. It was an artifact describing code that had already been rewritten underneath it. Verified directly in `modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py` on 2026-08-04 rather than taken from `gh95`'s prose.

## What the correction is

| | predecessor artifacts (ape) | `tool.py`, verified 2026-08-04 |
|---|---|---|
| surviving arms | 27 | **8 names / 7 configurations** |
| retired arms | 2 (`ape_pure`, `bfs`) | **21** |
| `APERV_PROPERTY_MAPPING` | 52 pairs | **50** |
| dead keys to delete | 2 | **1** — `ape_pure_mode` left with `gh93` (archived 2026-08-04) |

The survivors are `default`→`sata`, `sata_mop`, `sata_llm`, `sata_mop_llm`, `mop_on_llm_off`, `mop_off_llm_off`, `mop_on_llm_70`. The 21 retirements fall in three kinds `gh95` keeps apart, and the kinds matter more than the count: *never distinct* (`ape_pure`, `bfs`, and `sata_mop_widget` — one object under two names), *name consolidated* (`sata_mop_act_frontier`, byte-identical to `mop_on_llm_off`), and *finished campaign* (the six gh43 prompt arms, `cal_a1`…`cal_a9`, `sata_mop_activity`, `random`). Retirement ends the ability to launch new runs under a name; it does not touch recorded results, which are frozen artifacts.

Nineteen of the arms the predecessor's groups 3–5 instructed an implementer to *re-express* are arms `gh95` **deleted**. Whole task groups had lost their subject: `cal_a1`…`cal_a9` and their H1/H2/H3 hypothesis comments, the six frozen gh43 arms, the `sata_mop`/`sata_mop_widget` binding. The constants those tasks were to delete (`ARM_DEFINING_KEYS`, `LLM_ARM_KEYS`, `_CAL_LLM_COMMON`, `_APE_PURE_ARM_FLAGS`) are already gone. Task 1.3 still named `gh88-cal-llm-control` as *"the live blocker — do not start group 3 until it lands"*; `gh88` was archived 2026-08-03.

## The root cause, and what this change does about it

The predecessor carried 52 tasks, of which groups 1–9 (47 tasks) drove rv-android work from the ape repository. `gh95` states it holds *roughly 95% of this stage*, and the predecessor's own Impact section already agreed: *"the ape repo is edited only to delete stage 2's transitional test scaffolding."* Two repositories held two task lists for one body of work, and only one of them was next to the code. That is why the drift went unnoticed, and it is the defect this rewrite fixes — not merely the numbers.

**Ownership is therefore made explicit rather than left implicit.** rv-android owns the experimental matrix; this change owns what happens inside the ape repository. Concretely:

- **The arm matrix, the properties writer, the mapping, the guard retirement and the regeneration migration check belong to `gh95`.** This change does not restate them, does not re-plan them, and does not carry tasks for them. Where the ape repository must know a fact about them, it points at `gh95` rather than copying it — a copy is what drifted.
- **The predecessor minted `INV-APV-40`…`INV-APV-44` in the ape repository.** That is rv-android's invariant namespace (`INV-APV-*` = aperv; the ape mirror's own namespace is `INV-APERV-*`, currently 01–04), and `gh95` mints the same five IDs with divergent content — its `INV-APV-42` reads *"the eight surviving variant names are frozen"* against the predecessor's *"the 27 surviving variant names are frozen"*. Two repositories were writing conflicting definitions of the same invariant ID. **This change mints none of them**; the five are `gh95`'s.
- **The ape-side `aperv-tool` capability stops enumerating the matrix.** Its `Tool Variants` requirement is a stale mirror of a much older era — it lists five variants including `bfs`, and mandates a `throttle_ms` key on all of them — and it was stale long before this stage. Restating the matrix correctly would only reset the clock on the same failure. It is restated as the **contract** instead: a variant names a jar-resident preset and carries override deltas, and the roster itself is rv-android's. A spec that names no arm cannot drift when an arm is retired.

## What Changes

- **The rv-android side is recorded as delivered by `gh95`, not re-planned here.** This change's remaining rv-android obligation is coordination: confirm `gh95` closes, and confirm its group 7 marks the reserved counterpart task satisfied.
- **Stage 2's transitional scaffolding is retired in the ape repository** — the one edit that was always genuinely ape-side. `RunSpecCompatTest`, `PresetsTest`'s fixture-equivalence assertions and the per-arm fixtures pinned the jar against the *pre-change* `_push_properties` output so stage 2 could ship without touching Python. `gh95` rewrote that output, so those pins now freeze a deployment that no longer exists. They are deleted and replaced by tests of the preset contract itself. The retired-key coverage the fixtures also carried (`ape.apePureMode`, `ape.mopWeightActivity`) moves to `RunSpecAbortTest`, whose subject is the retired-key list and which was never transitional.
- **The `run-spec` requirement written in stage 2's transitional voice loses its framing**, keeping only the behaviour that was never transitional: no preset ⇒ resolve from the explicit keys and the jar defaults. This is unchanged from the predecessor and remains correct.
- **The ape-side `aperv-tool` mirror is corrected to the contract form** described above, and its stale `configure()` whitelist is brought to `["sata", "random"]`.
- **The jar is not modified.** No `src/main` file is touched, no preset value moves, the deployed binary is byte-identical. Deleting a test edits the ape repo without changing the jar, and that scope is stated here rather than discovered at apply time.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `aperv-tool`: `Tool Variants` restated as the preset + overrides **contract** with the arm roster delegated to rv-android; `configure()`'s strategy whitelist shrunk to `["sata", "random"]`. No `INV-APV-*` invariant is minted or amended here. The disposition of `Arm-Defining Flag Completeness (FR20)` — which lives in rv-android's spec, not this one — is recorded as a note with its substitute.
- `run-spec`: `Explicit-Key Resolution When No Preset Is Named` loses stage 2's transitional framing (the "current Python deployment" description and the "zero Python changes verified" scenario, both falsified by `gh95`) and keeps only the standing behaviour.

## Impact

- **Java**: test tree only — `RunSpecCompatTest` and the per-arm fixtures deleted, `PresetsTest`'s fixture-equivalence assertions replaced by preset-contract tests, retired-key coverage moved to `RunSpecAbortTest`. `mvn test` is the gate. **No jar change.**
- **Python/rv-android**: none by this change. Delivered by `gh95`.
- **Depends on**: `rearch-02-runspec` (archived 2026-08-04) for `Presets`, `KeyOwnership` and fail-fast resolution; `gh95` for the Python side.
- **Deployment note carried from `gh95`**: the `ape-rv.jar` deployed in `modules/aperv-tool` predates stage 2, so it ignores `ape.preset` as an unknown key and every re-expressed arm would collapse to jar defaults. Rebuilding and deploying it is a precondition for any campaign run of the new arms — an owner decision, and the empirical proof belongs to `gh97-rearch-ab-gate`.
- **Comparability**: arm names are frozen throughout; the migration's empty-diff proof is `gh95`'s (its `INV-APV-44`), and this change relies on it rather than reproducing it.
- Grounding: report Sec. 5.1, 6.6, Sec. 12 D1, verified V20/V21, finding 3.3-7.
