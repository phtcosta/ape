# Design: rearch-05-thin-python-arms

## Context

Stage 5 of 7 of the re-architecture selected in `docs/analise_fable-selecao.md` (rev. 3). Depends on `rearch-02-runspec` (archived 2026-08-04): presets resident in the jar (`Presets.resolve`), total fail-fast validation, and the level-0 `RUN_START` echo (owner decision D1).

**This design was rewritten on 2026-08-04.** Its predecessor planned the whole stage — both repositories — from the ape side, and its factual base had gone stale: it described 29 arms reducing to 27, a 52-pair mapping, and `_APE_PURE_ARM_FLAGS`/`ARM_DEFINING_KEYS`/`LLM_ARM_KEYS` awaiting deletion. The rv-android counterpart `gh95-thin-python-arms` had already shipped a different and larger reduction, and the constants were already gone.

State verified directly in `rv-android/modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py` on 2026-08-04 — read from the module source, not from `gh95`'s prose — and **re-read on 2026-08-05** (task 1.1), which is the discipline this design exists to impose on itself:

- **8 variant names / 7 distinct configurations**: `default` (bound to `sata`), `sata`, `sata_mop`, `sata_llm`, `sata_mop_llm`, `mop_on_llm_off`, `mop_off_llm_off`, `mop_on_llm_70`. Unchanged.
- **`APERV_PROPERTY_MAPPING` = 50 pairs** in the module's working tree. Unchanged — but the file is being edited by an active `gh94` session, and the committed `HEAD` of `rearch-counterparts` carries **51**, an in-flight edit removing one pair. The count is in motion on that side; re-read it at implementation time rather than trusting either figure here. Nothing in this change's task list depends on it.
- `ARM_DEFINING_KEYS`, `LLM_ARM_KEYS`, `_CAL_LLM_COMMON`, `mop_weight_activity`, `ape_pure_mode`, `sata_mop_widget`, `ape_pure`, `sata_mop_activity`, `cal_a1`…`cal_a9`: **absent**. Unchanged, and `_APE_PURE_ARM_FLAGS` with them.
- `bfs` and `sata_mop_act_frontier` survive only as comments recording their retirement — `bfs` at the strategy whitelist (*"absent deliberately: they were never agent types"*), `sata_mop_act_frontier` at `mop_on_llm_off` (*"Arm 1 absorbs the retired sata_mop_act_frontier"*).
- `gh95` at **56/57**; its group 7 is closed but for the owner sign-off (7.6). **Task 7.1 is green** — the regeneration diff this change's group 2 is gated on (D3) — and **7.7 is ticked**, so the counterpart obligation is closed from the rv-android side.

**The Context block is a snapshot with a date on it, not a contract**, and the 2026-08-05 re-read is what makes that claim checkable rather than decorative: every item above was confirmed against the module source again, and the one number that moved is flagged as moving rather than silently refreshed.

Owner decisions that constrain this design (report Sec. 12 — final, do not reopen):

- **D1**: the echo is level 0, definitive. **No automatic echo-vs-intent validation, ever.**
- The comparability rule: arms' effective configurations must be diff-identical before/after re-expression; any intentional divergence is a declared new arm, never a silent edit. Enforced by `gh95`'s `INV-APV-44`.

## Goals / Non-Goals

**Goals:**

- Retire stage 2's transitional Python-contract scaffolding from the ape repository, in the stage that invalidates it.
- Strip stage 2's transitional framing from the one `run-spec` requirement written in it, keeping the standing behaviour.
- Correct the ape-side `aperv-tool` mirror so it states the preset + overrides *contract* and stops enumerating an arm roster it cannot keep current.
- Leave a record of *why* the predecessor drifted, so stages 6 and 7 do not repeat the shape.

**Non-Goals:**

- **No jar changes.** No `src/main` file is touched; the deployed binary is byte-identical.
- **No rv-android edits.** The matrix, the properties writer, the mapping reduction, the guard retirement and the migration check are `gh95`'s, and are done.
- **No re-planning of `gh95`.** Where a fact about the Python side is needed, this design points at it.
- No new arms, no re-tuning, no `RUN_START` parser, no echo-vs-intent check (D1).

## Decisions

### D1: Ownership is declared, not inferred — and the ape mirror asserts the contract, not the matrix

The predecessor's real defect was structural, not arithmetic. Two repositories held two task lists for one body of work; only one sat next to the code; the other drifted and nothing detected it. Restating the correct roster in the ape repository would reset that clock rather than stop it — the next retirement would falsify it again, silently, exactly as this one did.

So the ape-side `aperv-tool` capability stops naming arms. Its `Tool Variants` requirement becomes a statement of shape:

- a variant names a jar-resident preset (`ape.preset`), carries an `overrides` dict of deltas, and keeps Python-only orchestration keys at top level;
- the jar is the sole authority on what a preset *means*;
- **the roster — which arms exist, their frozen names, their deltas — is rv-android's**, held in `openspec/specs/aperv/spec.md` and maintained through that repository's own OpenSpec workflow.

A spec that names no arm cannot go stale when an arm is retired. What the ape repository legitimately keeps in this capability is what binds the two sides mechanically and would break if it moved: the registry key (`INV-APERV-01`), the device JAR path that must match `pom.xml`'s `mvn install` target (`INV-APERV-02`), the configure-before-execute ordering (`INV-APERV-03`), and timeout ownership (`INV-APERV-04`).

Alternative considered: delete the ape-side `aperv-tool` mirror outright as duplication (P3). Rejected — those four invariants have a genuine ape-side subject (the jar path is asserted against `pom.xml`, which lives here), and deleting the capability to fix two drifted requirements would discard the three that never drifted.

### D2: The `INV-APV-*` namespace is rv-android's, and this change mints none of it

The predecessor minted `INV-APV-40`…`INV-APV-44` in the ape repository. `INV-APV-*` is rv-android's `aperv` capability namespace; the ape mirror's own namespace is `INV-APERV-*`. `gh95` mints the same five IDs, and their content conflicts:

| ID | predecessor (ape) | `gh95` (rv-android) |
|---|---|---|
| `INV-APV-42` | "the **27** surviving variant names are frozen… the two retired names are the only removals" | "the **eight** surviving variant names are frozen… the **21** retired names" |
| `INV-APV-44` | diff empty against the baseline | diff empty **on typed values using each key's declared `ValueType`, not on property text** |

Two definitions of one invariant ID, in two repositories, is worse than either being wrong: a reader resolving `INV-APV-42` gets a different requirement depending on which tree they are standing in, with nothing marking the ambiguity. This change deletes all five from the ape side. They are `gh95`'s.

`gh95`'s `INV-APV-44` refinement is worth recording as a finding rather than a footnote, because it is the kind of thing only contact with the running code produces: the `aperv` preset writes `ape.llmPercentageNoSubstrate=-1` where the declared default is `-1.0`, so a textual comparison of generated properties would fail **every** arm on formatting alone. The predecessor's version of the check, comparing effective configurations without saying on what, would have been written and then debugged. It was not a gap anyone could have reasoned to from the ape side.

### D3: Stage 2's transitional surface dies here, and this stage is the only place it can

Stage 2 had a real constraint: deploy the new resolver against an **unchanged** `tool.py`. It met that constraint honestly and recorded it in two places — `RunSpecCompatTest` plus per-arm fixtures reproducing `_push_properties`' output for the four campaign arms, and a `run-spec` requirement (`Explicit-Key Resolution When No Preset Is Named`) written entirely in transitional voice: *"the case for the entire current Python deployment, which this change does not touch"*, with a scenario literally named *zero Python changes verified*.

Stage 2 also declared the death: its design says stage 5 replaces the fixtures with the real contract, and its task 6.3 says the pin holds *"until stage 5"*. Nothing had executed that.

**Why now, and why this is no longer conditional.** The predecessor deferred the deletion behind its own group 2 (*"task 2.1 rewrites `_push_properties`"*). That rewrite has happened — in `gh95`, already merged into that repository's working tree. So the precondition is met and the fixtures are, as of today, a byte-for-byte copy of a deployment that no longer exists. A test that pins a superseded shape is exactly the frozen-copy-of-itself pattern D1 and the guard retirement reject everywhere else in this stage; keeping a preset-fixture guard while retiring the arm guards would be incoherent.

**What replaces them, and what does not need replacing.** The fixtures approximated one property: that a preset resolves to the same effective plan the arm used to produce. That property is proved directly by `gh95`'s regeneration diff (`INV-APV-44`), which compares typed effective configurations against the jar's real resolution rather than against a captured copy of the old Python output — and which is one-time by design. `PresetsTest` survives as a test of the preset contract: `Presets.resolve(name)` returns its declared base vector, explicit keys override it, and the merged result passes the same validation as an explicit plan — asserted against the preset definitions, never against a captured copy of `tool.py`'s output. The retired-key coverage the `ape_pure` fixture carried (`ape.apePureMode`, `ape.mopWeightActivity`) moves to `RunSpecAbortTest`, whose subject is the retired-key list itself and which was never transitional.

**Ordering.** The net is replaced before it is removed, not after: `gh95`'s regeneration diff has already run green across its migration, and its final full diff is that change's task 7.1. The two tests cover the same property; the diff covers it against the jar's real resolution.

### D4: What survives in the `run-spec` requirement

The no-preset case is not a compatibility affordance and does not disappear: a jar launched with no properties file must still resolve from its own defaults, which is what makes a bare standalone run valid (`rearch-02` design `:166`). The delta keeps that behaviour and drops the paragraph describing the Python deployment that used to depend on it, along with the two transitional scenarios. This is unchanged from the predecessor, which was correct here.

## Mapping: Spec → Implementation → Test

| Requirement | Implementation | Test |
|---|---|---|
| `Tool Variants` (MODIFIED: contract, roster delegated) | none in this repo — the shape is rv-android's `get_variants()` | `gh95`'s structural tests; nothing ape-side asserts the roster by design (D1) |
| `configure() Method` (MODIFIED: whitelist) | none in this repo — rv-android's `configure()` | `gh95`'s `TestConfigure` |
| `Explicit-Key Resolution When No Preset Is Named` (MODIFIED: de-framed) | `RunSpec` resolution, unchanged | existing `RunSpecTest` no-preset cases; the two transitional scenarios deleted |
| preset contract (replacing fixture equivalence) | `Presets.resolve` / `KeyOwnership`, unchanged | rewritten `PresetsTest` assertions |
| retired-key coverage | `RunSpec` retired-key list, unchanged | `RunSpecAbortTest` (receives `ape.apePureMode`, `ape.mopWeightActivity`) |

## Data Flow

Unchanged by this stage. Recorded for the reader: experiment YAML selects a variant → `configure()` validates → `execute_tool_specific_logic()` pushes jar, broadcast catalog, compacted static-analysis JSON, and `ape.properties` as `ape.preset` + `ape.mopDataPath` + one line per override → jar resolves the preset, applies overrides, fail-fast validates, echoes `RUN_START` as the trace's first line. The Python side reads **nothing** back (D1).

## Error Handling

| Error | Source | Strategy |
|---|---|---|
| `PresetsTest`/`RunSpecAbortTest` red after the swap | ape test tree | the replacement is wrong, not the contract — fix the test; the jar is untouched by this stage, so a red suite here cannot be a jar regression |
| `gh95` final diff (task 7.1) non-empty | rv-android | that change's divergence protocol: re-expression bug, or owner-approved divergence under a new arm name. Not resolvable from this repository |
| A stage-2 fixture turns out to cover something `gh95`'s diff does not | ape test tree | do not delete it; record the gap and route it to `gh95` before proceeding (task 2.4 exists to find this) |

## Risks / Trade-offs

- [Deleting stage 2's fixtures removes a safety net] → the net is replaced before it is removed (D3): `gh95`'s typed regeneration diff proves the same property against the jar's real resolution. Task 2.4 exists to confirm that claim concretely rather than assert it.
- [The ape mirror stops naming arms, so a reader of the ape repo cannot see the roster] → accepted, and it is the point (D1). The roster is one `openspec/specs/aperv/spec.md` away in rv-android, and a pointer that is occasionally inconvenient beats a copy that is silently wrong. The predecessor is the evidence.
- [`gh95` could still change before it closes] → it is at 37/44 with only verification pending, and its remaining tasks are lint/verify/review/docs/sign-off. Task 1.2 re-reads the module source at apply time rather than trusting this design's snapshot — which is precisely the discipline whose absence produced the rewrite.
- [The deployed jar predates stage 2, so no campaign can run the new arms yet] → out of scope here and recorded in the proposal: rebuilding and deploying is an owner decision, and the empirical proof belongs to `gh97-rearch-ab-gate`. This stage changes no jar behaviour, so it neither helps nor blocks that.
- [Stages 6 and 7 repeat the predecessor's shape] → the roadmap entry written by task 3.2 names the failure mode explicitly; `rearch-07`'s counterpart `gh96` is already at 49/55 with the ape side at 0/45, which is the same asymmetry at an earlier point.

## Testing Strategy

| Layer | Scope |
|---|---|
| Unit (rewritten) | `PresetsTest` — preset contract asserted against the preset definitions, not against captured `tool.py` output |
| Unit (extended) | `RunSpecAbortTest` — receives the retired-key coverage the deleted fixtures carried |
| Unit (deleted) | `RunSpecCompatTest` + the five per-arm fixture properties files |
| Untouched | everything else; the full `mvn test` suite is the gate (task 2.5) |

## Open Questions

None. The predecessor's Open Question 1 (`ape_pure`'s preset name) was resolved by the `rearch-02` design and then settled in fact by `gh95`, which retired the variant. Its Open Question 2 (post-stage-4 key set) is `gh95`'s mapping sweep, already performed — it found no dead entry among the surviving 50 beyond the one it deleted.
