## Context

<!-- Background, current state, constraints. Reference the proposal and relevant FRs/NFRs from docs/PRD.md if one exists. -->

## Architecture

<!-- High-level component diagram showing how components interact. -->

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `component.function` | What it does | Type | Type |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| FR01: Name | `component.function()` | `test_fr01_*` |
| INV-XX-01 | Enforced by `component.guard()` | `test_inv_xx_01` |

## Goals / Non-Goals

**Goals:**
<!-- What this design aims to achieve -->

**Non-Goals:**
<!-- What is explicitly out of scope -->

## Decisions

<!-- Key technical choices with rationale (why X over Y?). Include alternatives considered for each decision. -->

## API Design

### `function_name(param: type) -> ReturnType`

<!-- Function contract: preconditions, postconditions, error behavior. Include data schemas when applicable. -->

## Data Flow

<!-- How data moves through the components. -->

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `ErrorType` | When it occurs | How handled | How to recover |

## Risks / Trade-offs

<!-- Known limitations. Format: [Risk] -> Mitigation -->

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit | Logic without I/O | Mock dependencies | ~N tests |
| Integration | Component interaction | Real dependencies | ~N tests |

## Open Questions

<!-- Outstanding decisions or unknowns to resolve -->
