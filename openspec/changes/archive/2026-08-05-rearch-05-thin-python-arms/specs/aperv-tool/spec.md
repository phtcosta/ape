## Purpose

Delta spec for the `aperv-tool` capability — stage 5 of the re-architecture ("thin Python arms").

With presets resident in the jar since `rearch-02-runspec` (fail-fast validation + level-0 `RUN_START` echo), the Python side becomes thin: **an arm is a preset name plus an explicit dict of override deltas.** The jar is the sole authority on what a preset means; the Python side remains the sole authority on the experimental matrix.

**This delta was rewritten on 2026-08-04, and the rewrite is mostly a subtraction.** Its predecessor restated the full arm matrix in this repository — 27 frozen names enumerated one by one, the properties-writer output contract, the mapping reduction, and a one-time regeneration migration check — and minted `INV-APV-40`…`INV-APV-44` to govern them. Three things were wrong with that:

1. **The roster was stale.** rv-android's `gh95-thin-python-arms` had already reduced the matrix to eight names with 21 retirements; the enumeration here still described 27 survivors and two retirements. Nineteen of the arms this delta named as frozen no longer exist.
2. **The invariant namespace is not this repository's.** `INV-APV-*` belongs to rv-android's `aperv` capability; this mirror's own namespace is `INV-APERV-*`. `gh95` mints the same five IDs with divergent content — its `INV-APV-42` reads *"the eight surviving variant names are frozen"* against this delta's *"the 27 surviving variant names are frozen"*. Two definitions of one ID in two repositories is worse than either being wrong, because nothing marks the ambiguity for a reader.
3. **Enumerating the matrix here is the defect, not the stale numbers.** Correcting 27 to 8 would reset the clock rather than stop it: the next retirement would falsify the enumeration again, silently, exactly as this one did.

So this delta **mints no `INV-APV-*` invariant**, restates no arm roster, and adds no requirement about the properties writer, the mapping, or the migration check — all of which are `gh95`'s and are delivered. What it does instead is state the **contract** the two repositories meet on, and hand the roster back to the repository that owns it. A spec that names no arm cannot go stale when an arm is retired.

The capability's four standing invariants (`INV-APERV-01`…`04` — registry key, device JAR path, configure-before-execute, timeout ownership) are untouched. They have a genuine ape-side subject: `INV-APERV-02` asserts the device JAR path against `pom.xml`'s `mvn install` target, which lives in this repository.

**On the scenario headers below, which name arms that this delta's own text says it will not name.** `openspec archive` pairs scenarios **by name** inside a `MODIFIED` block and cannot tell a rename from a deletion, so a name the main spec carries and this block does not aborts the sync; `REMOVED` + `ADDED` of one requirement is rejected outright, and `RENAMED` rewrites only a requirement's header, so a scenario name cannot be re-anchored. All four of this capability's main-spec scenario names are therefore kept verbatim, carrying this change's bodies. Two of them (`Default variant resolved`, `sata_mop variant is wired (replaces Phase 4 placeholder)`) are exactly the roster vocabulary this delta retires, and their bodies now say that the roster is not asserted here and that `mop_data` is an orchestration key — which is the substance, stated under a name that has become a historical label. The mismatch is the tool's cost and is paid once, here, rather than by dropping a scenario.

**Scope of the roster prohibition, stated so it does not contradict a requirement this delta leaves alone.** The prohibition below binds the `Tool Variants` requirement — where a roster would be a *copy* of rv-android's. It is not a ban on the string `sata_mop` appearing anywhere in the capability: `execute_tool_specific_logic() Flow`, which this delta does not modify, names variants as the trigger for a behaviour (`mop_data == "static_analysis"` ⇒ push the artifact), and that is a statement about the flow, not an enumeration anyone must keep current. Written as "this specification" the sentence would have been false about its own capability the moment it synced.

## MODIFIED Requirements

### Requirement: Tool Variants

`ApeRVTool.get_variants()` SHALL return a mapping of frozen variant names to variant definitions, each of which SHALL consist of:

- `preset: str` — the name of a **jar-resident** preset (`rearch-02-runspec`: `aperv`, `mop`, `llm`, `llm_mop`), written to `ape.properties` as the `ape.preset` line. The jar, not Python, defines what a preset means; Python SHALL NOT mirror preset contents.
- `overrides: Dict[str, Any]` — only the deltas that distinguish this variant from its preset. A variant identical to its preset SHALL carry an empty dict. Ablations SHALL be expressed as named override sets, never as new presets.
- Python-only orchestration keys at top level — `strategy` (the `--ape` CLI flag), `mop_data`, `seed`, and the B3 pairing keys `expected_jar_git_sha`/`expected_jar_sha256` — which SHALL NOT be written to `ape.properties`.

No variant SHALL carry a full property expansion.

**The roster is not held in this repository.** Which variants exist, their frozen names, their preset assignments and their override deltas are owned by rv-android's `aperv` capability (`rv-android/openspec/specs/aperv/spec.md`) and maintained through that repository's own OpenSpec workflow. This requirement SHALL NOT enumerate variant names, and a reader needing the current roster SHALL consult that spec rather than this one.

This is a deliberate constraint on where the roster may be written, not an oversight. A variant name is the resume-identity key and the consolidation column key of the frozen corpus; it is retired and consolidated by campaign decisions that happen in rv-android. An enumeration maintained here can only be a copy, and a copy that drifts silently is worse than a pointer that is occasionally inconvenient — this requirement previously held such a copy, and it was wrong in two different eras before anyone noticed.

#### Scenario: Variant is preset plus deltas

- **WHEN** any variant returned by `get_variants()` is read
- **THEN** it SHALL carry a `preset` key naming a jar-resident preset and an `overrides` dict
- **AND** it SHALL NOT carry a full expansion of `ape.*` property keys

#### Scenario: Variant identical to its preset

- **WHEN** a variant whose configuration matches its preset exactly is read
- **THEN** its `overrides` dict SHALL be empty rather than restating the preset's keys

#### Scenario: Orchestration keys stay out of the properties file

- **WHEN** `ape.properties` is generated for any variant
- **THEN** `strategy`, `mop_data`, `seed`, `expected_jar_git_sha` and `expected_jar_sha256` SHALL NOT appear in it

#### Scenario: Default variant resolved

- **WHEN** this requirement is read for the list of available variants — including which name is the
  default and what it resolves to
- **THEN** it SHALL NOT contain one, and SHALL direct the reader to rv-android's `aperv` capability

#### Scenario: sata_mop variant is wired (replaces Phase 4 placeholder)

- **WHEN** a variant that needs the compacted static-analysis artifact is read
- **THEN** it SHALL carry `mop_data` as a top-level Python-only orchestration key, alongside its
  `preset` and `overrides`
- **AND** `mop_data` SHALL NOT appear in the generated `ape.properties`, which names the artifact
  through `ape.mopDataPath` instead
- **AND** which variants set it SHALL NOT be asserted here (see the preceding scenario)

---

### Requirement: configure() Method

`ApeRVTool.configure(config)` SHALL store the resolved variant configuration in `self._tool_config`. It SHALL validate that `config["strategy"]` is one of `["sata", "random"]`, that `config["preset"]` is present and non-empty, and that `config.get("overrides", {})` is a dict. If any check fails, it SHALL raise `ConfigurationError` before any device interaction.

The whitelist SHALL shrink from `["sata", "random", "bfs", "dfs"]` — the deletion `rearch-02-runspec` delegates to this stage. `bfs` and `dfs` were never agent types: `ApeAgent.createAgent` (`src/main/java/com/android/commands/monkey/ape/agent/ApeAgent.java:68-96`) recognizes exactly `sata`, `random` and `replay`, with every other value previously falling through silently to `new SataAgent` (verified V9). Accepting them Python-side would let a run pass local validation and abort on the device, reintroducing the silent-degradation class stage 2 exists to remove. `replay` is legal in the jar but is NOT accepted here: it requires `--ape-replay <log>`, which this tool never passes.

#### Scenario: Valid strategy configured

- **WHEN** `configure({"strategy": "sata", "preset": "mop", "overrides": {}})` is called
- **THEN** `self._tool_config["preset"]` SHALL equal `"mop"`
- **AND** no exception SHALL be raised

#### Scenario: Missing preset raises ConfigurationError

- **WHEN** `configure({"strategy": "sata"})` is called
- **THEN** `ConfigurationError` SHALL be raised naming the missing `preset` key

#### Scenario: Invalid strategy raises ConfigurationError

- **WHEN** `configure({"strategy": "bfs", "preset": "aperv"})` or `configure({"strategy": "dfs", "preset": "aperv"})` is called
- **THEN** `ConfigurationError` SHALL be raised before any device interaction
- **AND** the run SHALL NOT reach the jar, where it would abort as an unknown `--ape` value

## Notes

### Disposition of `Arm-Defining Flag Completeness (FR20)` — cross-repo, not a delta operation here

This requirement does **not** live in this repository's `aperv-tool` capability; it is held in rv-android's own spec (`rv-android/openspec/specs/aperv/spec.md`). A `REMOVED` block here would name a requirement absent from `openspec/specs/aperv-tool/spec.md` and would sync to nothing — `openspec validate --strict` cannot detect that — so the disposition is recorded as a note, and the removal is executed in rv-android's own OpenSpec workflow by `gh95`.

**Reason**: its executable form was the INV-APV-13/14/15/17/26/27 guard family plus the `ARM_DEFINING_KEYS`/`LLM_ARM_KEYS`/`_ARM_DEFINING_EXEMPT` constants — a self-referential check that validated Python constants against other Python constants and never touched the binary that runs (verified V20). With arms expressed as preset + overrides, the enforced property dissolves: an arm's identity is its preset (jar-resolved, fail-fast validated) plus its override deltas, there is no expansion left to keep complete, and a missing or misspelled key aborts the run in the jar instead of passing silently.

**Substitute recorded**: (a) `gh95`'s one-time regeneration migration check (`INV-APV-44`) proves the re-expression preserved the calibrated grid; (b) level-0 echo provenance — every trace begins with `RUN_START` carrying the effective plan, digest and jar version, so "which arm ran this task" is answerable from the trace alone, post-hoc. Per owner decision D1, **no runtime validation replaces the guards**. The kill-switch defence-in-depth rows are substituted by structural purity in the jar (stage 2): a plan without a feature contains no feature, and the echo proves it.

### A finding worth carrying, from `gh95`'s contact with the running code

`gh95`'s `INV-APV-44` requires the regeneration diff to compare **typed values using each key's declared `ValueType`, not property text**, because the `aperv` preset writes `ape.llmPercentageNoSubstrate=-1` where the declared default is `-1.0`. A textual comparison would fail every arm on formatting alone. The predecessor of this delta specified the same check without saying what it compared on, and would have been written and then debugged. It is recorded here because it is the kind of defect only proximity to the code surfaces — which is the argument for the ownership split this delta makes explicit.
