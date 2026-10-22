# APE-RV Specifications

This directory contains the main specifications for APE-RV. Each subdirectory covers one capability of the system. Specifications are written in the SDD format: narrative Purpose, Data Contracts, Invariants (INV-XX-NN), and Requirements with WHEN/THEN/AND scenarios. They describe the code as it stands on `master`.

## Capability Map

Behavior is organised in five groups — BASE (≈ upstream APE, no key), ADDONS, STATIC, MOP and LLM — see [guidance-groups](guidance-groups/spec.md).

| Capability | Area | Description |
|------------|------|-------------|
| [build](build/spec.md) | Build | Maven + d8 build producing `target/ape-rv.jar`; `BuildInfo` git stamp; install copy to aperv-tool |
| [run-spec](run-spec/spec.md) | Runtime | `RunSpec` plan resolved once at startup: keys, presets (six cells), fail-fast aborts, `RunContext`, `RUN_START` echo |
| [guidance-groups](guidance-groups/spec.md) | Runtime | The five behavior groups, their member `Feature`s, dependencies and the six preset cells |
| [exploration](exploration/spec.md) | Agents | Agent strategies (`sata`, `random`, `replay`), SATA heuristic, epsilon, fuzzing, restart, teardown |
| [decision-pipeline](decision-pipeline/spec.md) | Agents | The per-step owner sequence (`Budget` … `SataChain`), `StepRng` streams, buffer abandonment |
| [action-selection](action-selection/spec.md) | Agents | SATA's pick inside `SataChain`: least-visited X1 tie, roulette, extension points, per-step attribution |
| [scoring-pipeline](scoring-pipeline/spec.md) | Agents | Additive, independent `ScoringPass`es (rule R1) and their assembly from the plan |
| [activity-budget](activity-budget/spec.md) | ADDONS | Per-activity step budget and navigation to a trivial activity when exhausted |
| [form-completion](form-completion/spec.md) | ADDONS | Boost for submit actions once a form's input fields are filled |
| [heuristic-input](heuristic-input/spec.md) | ADDONS | Field-aware text generation for `EditText` widgets |
| [ui-coverage](ui-coverage/spec.md) | ADDONS / all cells | Per-state widget coverage tracking, coverage boost and the coverage dump |
| [wtg-navigation](wtg-navigation/spec.md) | STATIC / MOP | Window Transition Graph view from the static artifact and its scoring passes |
| [static-analysis-entrypoints](static-analysis-entrypoints/spec.md) | STATIC / MOP | What the producer (GATOR, rv-android generator) emits and what reaches the jar (format 2) |
| [component-triggering](component-triggering/spec.md) | STATIC / MOP | Probabilistic receiver/service/provider triggers and the MOP activity launcher |
| [mop-guidance](mop-guidance/spec.md) | MOP | `StaticArtifactLoader`, `MopData`/`MopContext`, `flag`/`distance` scoring, retirement, MOP coin, ledger |
| [llm-infrastructure](llm-infrastructure/spec.md) | LLM | `SglangClient` (vLLM), `LlmClient` + breaker, screenshot, image processing, tool-call parsing |
| [llm-prompt](llm-prompt/spec.md) | LLM | `ApePromptBuilder`: system message, widget list, group prompt contributions, history |
| [llm-routing](llm-routing/spec.md) | LLM | LLM stages (new state, stagnation, coin), gates, `CoordinateMapper`, LLM telemetry |
| [event-sink](event-sink/spec.md) | Telemetry | `EventSink`/`NdjsonSink`: `StepRecord`, `RUN_START`, `PIPELINE`, `MOP_DATA`, `LLM_ACK`, `RUN_END`, heartbeat |
| [parity-oracle](parity-oracle/spec.md) | Testing | Golden decision traces, scripted LLM stub and the neutrality gates |
| [model](model/spec.md) | Core | Exploration graph: `State`, `StateTransition`, `ModelAction`, `ActionType` |
| [naming](naming/spec.md) | Core | CEGAR abstraction: `Naming` lattice, `NamingFactory`, refinement |
| [ui-tree](ui-tree/spec.md) | Core | `GUITree`/`GUITreeNode` capture, scroll types, handler stamps, release |
| [aperv-tool](aperv-tool/spec.md) | Integration | The rv-android `aperv` plugin contract: variants, properties, JAR resolution, NDJSON trace reader |

## Spec→PRD Traceability

`docs/PRD.md` predates most capabilities; only the original ten are traced.

| Spec | PRD Requirements |
|------|-----------------|
| build | FR01, FR02, FR03 |
| exploration | FR04, FR05, FR06, FR07, FR09 |
| model | FR04, FR07 |
| naming | FR04 (core innovation) |
| ui-tree | FR08, FR09 |
| mop-guidance | FR11, FR12, FR13 |
| aperv-tool | FR14, FR15, FR16, FR17 |
| llm-infrastructure | FR18, FR19, FR20 |
| llm-prompt | FR21, FR22 |
| llm-routing | FR23, FR24, FR25 |

## How Specs Are Used

Specs are the source of truth for implementation. Changes to system behavior MUST go through the OpenSpec workflow:

1. Create a change with `/opsx:new` or `/opsx:ff`
2. Write a **delta spec** (what changes relative to the main spec)
3. Implement from the delta spec via `/opsx:apply`
4. Verify with `/opsx:verify`
5. Archive with `/opsx:archive`

`openspec archive` applies a delta's requirement operations (ADDED / MODIFIED / REMOVED / RENAMED) but not its preamble (Purpose, Data Contracts, Invariants). Preamble edits carried by a delta are synced into the main spec by hand at archive time, and the requirement blocks are checked to be identical to the archive's output. Outside that step the main specs are not edited directly.
