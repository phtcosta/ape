# APE-RV Specifications

This directory contains the main specifications for APE-RV. Each subdirectory covers one domain of the system. Specifications are written in the SDD format: narrative Purpose, Data Contracts, Invariants (INV-XX-NN), and Requirements with WHEN/THEN/AND scenarios.

## Domain Map

| Domain | Spec | Phase | Status | Description |
|--------|------|-------|--------|-------------|
| [build](build/spec.md) | Build Infrastructure | 1 | **Current** | Maven + d8 build system producing `ape-rv.jar` |
| [exploration](exploration/spec.md) | Exploration Engine | 1–2 | **Current** | Agent interface, 5 strategies, SATA heuristic |
| [model](model/spec.md) | Exploration Model | 1–2 | **Current** | Graph of States, Transitions, ModelActions, ActionType |
| [naming](naming/spec.md) | Naming & Abstraction | 1 | **Current** | Core CEGAR algorithm: Naming lattice, NamingFactory, refinement |
| [ui-tree](ui-tree/spec.md) | UI Tree | 1–2 | **Current** | GUITree, GUITreeNode, scroll detection |
| [mop-guidance](mop-guidance/spec.md) | MOP Guidance | 3 | **Current** | MopData loader, MopScorer, configurable weights (500/300/100) |
| [aperv-tool](aperv-tool/spec.md) | aperv-tool Plugin | 4 | **Current** | Python ApeRVTool for rv-android, 5 variants, JAR resolution |
| [llm-infrastructure](llm-infrastructure/spec.md) | LLM Infrastructure | 6 | **Current** | SglangClient, ScreenshotCapture, ImageProcessor, ToolCallParser, CoordinateNormalizer, LlmCircuitBreaker, LlmException |
| [llm-prompt](llm-prompt/spec.md) | LLM Prompt Builder | 6 | **Current** | ApePromptBuilder: system message, widget list, MOP markers, action history, exploration context |
| [llm-routing](llm-routing/spec.md) | LLM Routing | 6 | **Current** | LlmRouter lifecycle, new-state mode, stagnation mode, action selection pipeline, coordinate mapping, telemetry |

## Spec→PRD Traceability

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
5. Sync delta into main spec with `/opsx:sync`, then archive with `/opsx:archive`

The main spec files in this directory are NEVER edited directly — only via the sync step after a completed change.
