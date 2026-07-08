# exploration — delta: rv-scoring-pipeline

## Purpose

Gate the fork's options-menu action behind the new `modelMenuEnabled` parity flag so the `ape_pure` arm can reproduce upstream APE, which has no model-level MENU action. The gate is applied at the **selection surface**: when `modelMenuEnabled` is false, the `menuAction` is excluded from `State.getActions()` and never selected, but the field itself remains constructed and non-null. `INV-EXPL-06` (every `State` has a non-null `menuAction` field) is therefore untouched — only the action's presence in the selectable set is gated. The `modelMenuEnabled` flag is declared by the `scoring-pipeline` capability; this delta only wires its gate into the MODEL_MENU requirement.

> Note: `INV-EXPL-06` remains literally true under this delta (the field stays non-null and constructed). No global invariant is modified. The gate touches only `State.getActions()` membership and selection.

## MODIFIED Requirements

### Requirement: OptionsMenu Systematic Exploration (MODEL_MENU)

Every `State` object SHALL hold a `menuAction` field of type `ModelAction(this, ActionType.MODEL_MENU)`, initialised in the `State` constructor immediately after `backAction`. The field SHALL be exposed via `State.getMenuAction()` and SHALL be non-null for the life of the state. This mirrors the `backAction` / `getBackAction()` pattern exactly.

Inclusion of the `menuAction` in the state's **selectable** action set is gated by `Config.modelMenuEnabled` (declared by the `scoring-pipeline` capability; default `true`). When `modelMenuEnabled` is `true` (default), the `menuAction` SHALL be included in the array returned by `State.getActions()`, exactly as before this change. When `modelMenuEnabled` is `false` (the `ape_pure` arm), the `menuAction` SHALL NOT be included in `State.getActions()` and the agent SHALL never select `MODEL_MENU`; the field SHALL still be constructed and returned by `State.getMenuAction()` (so `INV-EXPL-06` holds). This reproduces upstream APE, which has no model-level options-menu action.

`MonkeySourceApe.generateEventsForActionInternal()` SHALL handle `MODEL_MENU` in its switch statement by calling `generateKeyMenuEvent()`. No target widget node is required or inspected.

`MonkeySourceApe.validateResolvedAction()` SHALL return `true` for `MODEL_MENU` without calling any widget validator (same pattern as `MODEL_BACK`).

#### Scenario: State constructor initialises menuAction
- **WHEN** a new `State` is constructed for any `StateKey`
- **THEN** `state.getMenuAction()` MUST return a non-null `ModelAction` whose `getType()` returns `ActionType.MODEL_MENU`
- **AND** when `Config.modelMenuEnabled` is `true` (default) the `menuAction` MUST be included in the actions array returned by `state.getActions()`

#### Scenario: MODEL_MENU excluded from selection when modelMenuEnabled is false
- **WHEN** `Config.modelMenuEnabled` is `false` and a new `State` is constructed
- **THEN** `state.getMenuAction()` MUST still return a non-null `ModelAction` of type `MODEL_MENU`
- **AND** `state.getActions()` MUST NOT contain the `menuAction`
- **AND** the agent MUST never select `MODEL_MENU` for that state

#### Scenario: MODEL_MENU event generation
- **WHEN** `MonkeySourceApe.generateEventsForActionInternal()` is called with a `ModelAction` whose type is `MODEL_MENU`
- **THEN** `generateKeyMenuEvent()` SHALL be called
- **AND** no target `GUITreeNode` SHALL be required or consulted

#### Scenario: MODEL_MENU validation always passes
- **WHEN** `MonkeySourceApe.validateResolvedAction()` is called with a `ModelAction` of type `MODEL_MENU`
- **THEN** the method SHALL return `true`
- **AND** no widget validator (`validateClickAction`, `validateScrollAction`) SHALL be invoked
