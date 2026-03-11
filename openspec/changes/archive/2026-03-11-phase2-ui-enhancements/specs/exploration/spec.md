## Purpose

This delta extends the Exploration specification to cover the `MODEL_MENU` action introduced by Phase 2 UI Enhancements.

**Problem**: Android's `KEYCODE_MENU` event opens the three-dot overflow menu (`OptionsMenu`) present in many applications. Before this change, APE's only mechanism for pressing the menu key was the random fuzzing path (`doFuzzing=true`, `fuzzingRate=0.02`), which fires at a 2% probability and is not tracked in the state model. Applications that expose significant functionality exclusively through the `OptionsMenu` are therefore underexplored: the menu is pressed rarely, opportunistically, and without any record of whether it has been visited from the current state.

**Solution**: Add `MODEL_MENU` as a first-class `ActionType` constant. Like `MODEL_BACK`, it operates at the device level (no widget target), is present in every `State`, and is tracked in the exploration graph. This guarantees that the `OptionsMenu` is explored at least once from every state where it has not been visited. The menu items exposed after the press are captured as regular clickable widgets in the resulting `GUITree`, so no special post-press handling is required.

**Ordinal range invariant**: `requireTarget()` currently uses an ordinal range check: constants between `MODEL_CLICK` and `MODEL_SCROLL_RIGHT_LEFT` (inclusive) require a target. `MODEL_MENU` is inserted immediately after `MODEL_BACK` (before `MODEL_CLICK`), so it falls outside that range and `requireTarget()` returns `false` without any change to the range boundaries.

---

## MODIFIED Requirements

### Requirement: ActionType Classification

`ActionType` is an enum in `com.android.commands.monkey.ape.model.ActionType` that classifies every action the exploration engine can perform. The `requireTarget()` predicate SHALL return `true` if and only if the action type requires a specific widget node as its target. The `isModelAction()` predicate SHALL return `true` for all `MODEL_*` constants and `false` for all event and phantom constants.

The full set of `MODEL_*` action types and their `requireTarget()` values are:

| ActionType | requireTarget() | Description |
|---|---|---|
| `MODEL_BACK` | `false` | BACK key press; no widget target |
| `MODEL_MENU` | `false` | MENU key press; no widget target; **added** |
| `MODEL_CLICK` | `true` | Tap on a widget node |
| `MODEL_LONG_CLICK` | `true` | Long-press on a widget node |
| `MODEL_SCROLL_BOTTOM_UP` | `true` | Scroll-up gesture on a widget |
| `MODEL_SCROLL_TOP_DOWN` | `true` | Scroll-down gesture on a widget |
| `MODEL_SCROLL_LEFT_RIGHT` | `true` | Swipe-right gesture (ViewPager tabs) |
| `MODEL_SCROLL_RIGHT_LEFT` | `true` | Swipe-left gesture (ViewPager tabs) |

Non-model types (`PHANTOM_CRASH`, `FUZZ`, `EVENT_START`, `EVENT_RESTART`, `EVENT_CLEAN_RESTART`, `EVENT_NOP`, `EVENT_ACTIVATE`) SHALL have `isModelAction()` return `false` and are not used as graph edge labels.

#### Scenario: requireTarget() on BACK
- **WHEN** `ActionType.MODEL_BACK.requireTarget()` is called
- **THEN** the return value SHALL be `false`

#### Scenario: requireTarget() on MENU
- **WHEN** `ActionType.MODEL_MENU.requireTarget()` is called
- **THEN** the return value SHALL be `false`

#### Scenario: requireTarget() on CLICK
- **WHEN** `ActionType.MODEL_CLICK.requireTarget()` is called
- **THEN** the return value SHALL be `true`

#### Scenario: isModelAction() on MODEL_MENU
- **WHEN** `ActionType.MODEL_MENU.isModelAction()` is called
- **THEN** the return value SHALL be `true`

#### Scenario: isModelAction() on EVENT_RESTART
- **WHEN** `ActionType.EVENT_RESTART.isModelAction()` is called
- **THEN** the return value SHALL be `false`

---

### Requirement: OptionsMenu Systematic Exploration (MODEL_MENU)

Every `State` object SHALL hold a `menuAction` field of type `ModelAction(this, ActionType.MODEL_MENU)`, initialised in the `State` constructor immediately after `backAction`. The field SHALL be exposed via `State.getMenuAction()`. This mirrors the `backAction` / `getBackAction()` pattern exactly.

`MonkeySourceApe.generateEventsForActionInternal()` SHALL handle `MODEL_MENU` in its switch statement by calling `generateKeyMenuEvent()`. No target widget node is required or inspected.

`MonkeySourceApe.validateResolvedAction()` SHALL return `true` for `MODEL_MENU` without calling any widget validator (same pattern as `MODEL_BACK`).

#### Scenario: State constructor initialises menuAction
- **WHEN** a new `State` is constructed for any `StateKey`
- **THEN** `state.getMenuAction()` MUST return a non-null `ModelAction` whose `getType()` returns `ActionType.MODEL_MENU`
- **AND** the `menuAction` MUST be included in the actions array returned by `state.getActions()`

#### Scenario: MODEL_MENU event generation
- **WHEN** `MonkeySourceApe.generateEventsForActionInternal()` is called with a `ModelAction` whose type is `MODEL_MENU`
- **THEN** `generateKeyMenuEvent()` SHALL be called
- **AND** no target `GUITreeNode` SHALL be required or consulted

#### Scenario: MODEL_MENU validation always passes
- **WHEN** `MonkeySourceApe.validateResolvedAction()` is called with a `ModelAction` of type `MODEL_MENU`
- **THEN** the method SHALL return `true`
- **AND** no widget validator (`validateClickAction`, `validateScrollAction`) SHALL be invoked

---

### Requirement: SataAgent — Unvisited Action Priority

`SataAgent` is the default and primary exploration strategy. Its core heuristic is to exhaustively visit all unvisited actions before re-visiting known actions. An action is considered unvisited when `ModelAction.isUnvisited()` returns `true`. Among unvisited actions in the current state, `SataAgent` MUST check `backAction` first, then `menuAction`, then widget-targeted actions. Only when all actions in the current state have been visited does the agent fall back to the epsilon-greedy path.

The `menuAction` check is added to `SataAgent.selectNewActionEpsilonGreedyRandomly()` immediately after the existing `backAction` unvisited check and before the epsilon-greedy fallback.

#### Scenario: State has unvisited BACK action
- **WHEN** `SataAgent.selectNewActionEpsilonGreedyRandomly()` is called
- **AND** the current `State`'s `backAction` passes `ActionFilter.ENABLED_VALID` and `ModelAction.isUnvisited()` returns `true`
- **THEN** the BACK action SHALL be returned immediately, before any MENU or widget action is considered

#### Scenario: State has visited BACK but unvisited MENU action
- **WHEN** `SataAgent.selectNewActionEpsilonGreedyRandomly()` is called
- **AND** the current `State`'s `backAction` has already been visited
- **AND** the current `State`'s `menuAction` passes `ActionFilter.ENABLED_VALID` and `ModelAction.isUnvisited()` returns `true`
- **THEN** the MENU action SHALL be returned immediately, before any widget action or epsilon-greedy selection

#### Scenario: State has visited BACK but unvisited widget actions
- **WHEN** `SataAgent.selectNewActionEpsilonGreedyRandomly()` is called
- **AND** the current `State`'s `backAction` has already been visited
- **AND** the current `State`'s `menuAction` has already been visited
- **AND** at least one widget-targeted action in the current state is unvisited and passes `ActionFilter.ENABLED_VALID`
- **THEN** the agent SHALL apply the epsilon-greedy decision: with probability `1 - ape.defaultEpsilon` (default `0.95`) the least-visited valid action SHALL be returned; with probability `ape.defaultEpsilon` (default `0.05`) a random valid action SHALL be returned

#### Scenario: All actions in current state are visited
- **WHEN** every `ModelAction` in the current `State` (including `backAction` and `menuAction`) has been visited at least once
- **AND** no buffer path is available
- **THEN** `SataAgent` SHALL fall through to the epsilon-greedy rule over all valid actions in the current state

---

## MODIFIED Invariants

- **INV-EXPL-02**: `ActionType.MODEL_BACK.requireTarget()` and `ActionType.MODEL_MENU.requireTarget()` SHALL both return `false`. Neither `MODEL_BACK` nor `MODEL_MENU` requires a widget target; both map directly to Android key events. *(Previously specified MODEL_BACK only.)*
- **INV-EXPL-04**: `ActionType.MODEL_CLICK.requireTarget()`, `MODEL_LONG_CLICK.requireTarget()`, `MODEL_SCROLL_BOTTOM_UP.requireTarget()`, `MODEL_SCROLL_TOP_DOWN.requireTarget()`, `MODEL_SCROLL_LEFT_RIGHT.requireTarget()`, and `MODEL_SCROLL_RIGHT_LEFT.requireTarget()` SHALL each return `true`. *(Unchanged — MODEL_MENU is not in this list.)*
- **INV-EXPL-05**: `ActionType.isModelAction()` SHALL return `true` for all `MODEL_*` enum constants (`MODEL_BACK`, `MODEL_MENU`, `MODEL_CLICK`, `MODEL_LONG_CLICK`, `MODEL_SCROLL_BOTTOM_UP`, `MODEL_SCROLL_TOP_DOWN`, `MODEL_SCROLL_LEFT_RIGHT`, `MODEL_SCROLL_RIGHT_LEFT`) and `false` for all other constants. *(Updated to include MODEL_MENU.)*
- **INV-EXPL-06**: Every `State` object SHALL have non-null `backAction` and `menuAction` fields, each holding a `ModelAction` of their respective types. Both fields are initialised in the `State` constructor and MUST NOT be set to null at any point. *(Updated to include menuAction.)*

New invariant:

- **INV-EXPL-13**: `MODEL_MENU` SHALL be positioned in the `ActionType` enum after `MODEL_BACK` and before `MODEL_CLICK`. This placement ensures that `requireTarget()`'s ordinal range check (`MODEL_CLICK` through `MODEL_SCROLL_RIGHT_LEFT`) continues to correctly identify target-requiring actions without any change to the range boundaries.
