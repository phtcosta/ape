## Purpose

This delta introduces `ActionType.MODEL_LLM_TAP`, a targetless model action that carries an LLM-supplied pixel coordinate, and the `LlmTapAction` subclass that holds that coordinate. The action is placed in the enum's ordinal order between `MODEL_MENU` and `MODEL_CLICK`, so it satisfies `isModelAction() == true` (it labels a `StateTransition` edge) and `requireTarget() == false` (it operates on a raw coordinate, not a resolved `GUITreeNode`). This mirrors `MODEL_MENU` / `MODEL_BACK`, whose node-dependent behavior is already guarded throughout `ModelAction` by `!requireTarget()`.

`MODEL_LLM_TAP` exists so the model graph can represent an exploration step taken on an element absent from the `GUITree`. The `LlmRouter` synthesizes an `LlmTapAction` for the off-tree case (see the `llm-routing` capability) and returns it directly, exactly as the activity-frontier mechanism returns a synthesized `EVENT_TRIGGER_ACTIVITY` action not drawn from the state's action set. The resulting edge is labeled `DecisionSource.LLM` and remains traceable as an LLM coordinate tap on a probable dynamic element.

## Invariants

- **INV-MODEL-03** (restated): `ActionType.isModelAction()` MUST return `true` for all **eight** `MODEL_*` values (`MODEL_BACK` through `MODEL_SCROLL_RIGHT_LEFT`, now including `MODEL_LLM_TAP`) and MUST return `false` for all non-model values (`PHANTOM_CRASH`, `FUZZ`, `EVENT_*`). Adding `MODEL_LLM_TAP` inside the `MODEL_*` ordinal range raises the count from seven to eight; the range bounds and the ordinal-comparison body of `isModelAction()` are unchanged.
- **INV-MODEL-12**: `ActionType.MODEL_LLM_TAP` MUST satisfy `requireTarget() == false` and `isModelAction() == true`. This is enforced by its ordinal position (`MODEL_BACK.ordinal() <= MODEL_MENU.ordinal() < MODEL_LLM_TAP.ordinal() < MODEL_CLICK.ordinal() <= MODEL_SCROLL_RIGHT_LEFT.ordinal()`), with no change to the bodies of `requireTarget()` or `isModelAction()`. An `LlmTapAction` MUST be constructable with `target = null` without error.
- **INV-MODEL-13**: `LlmTapAction` identity MUST include its `(pixelX, pixelY, longClick)` payload in addition to the inherited `(state, target = null, type)`. Two taps with identical payloads in the same state MUST be equal with equal `hashCode`; two taps whose coordinate or `longClick` flag differ MUST NOT be equal. Consequently each distinct off-tree tap owns its own `StateTransition` edges, and a differing outcome between two *different* coordinates MUST NOT be reported as non-determinism of a single action.
- **INV-MODEL-14**: An **ephemeral** action — one synthesized per decision and never a member of any `State.actions` (`isEphemeral()`, true exactly for `MODEL_LLM_TAP`) — MUST NOT be registered in the graph's unvisited/visited action inventory, and the graph's visit bookkeeping MUST accept it without error rather than treating it as a violated registration. Any **non**-ephemeral model action reaching that bookkeeping unregistered MUST still raise the existing sanity-check error. An ephemeral action MUST NOT trigger naming refinement: its outcome varies with its payload or with a non-deterministic surface, neither of which any abstraction can resolve.

## MODIFIED Requirements

### Requirement: requireTarget Contract for All Action Types

`ActionType.requireTarget()` MUST return a value consistent with whether the action operates on a specific widget, and this contract MUST be enforced uniformly across all call sites that dispatch actions. `requireTarget()` returns `true` if and only if `ordinal() >= MODEL_CLICK.ordinal() && ordinal() <= MODEL_SCROLL_RIGHT_LEFT.ordinal()`. The targetless model actions `MODEL_BACK`, `MODEL_MENU`, and `MODEL_LLM_TAP` sit below `MODEL_CLICK` in ordinal order and therefore return `false`.

#### Scenario: Widget-target actions require a target

- **WHEN** `actionType.requireTarget()` is called for any of: `MODEL_CLICK`, `MODEL_LONG_CLICK`, `MODEL_SCROLL_TOP_DOWN`, `MODEL_SCROLL_BOTTOM_UP`, `MODEL_SCROLL_LEFT_RIGHT`, `MODEL_SCROLL_RIGHT_LEFT`
- **THEN** it MUST return `true`

#### Scenario: MODEL_BACK is targetless

- **WHEN** `actionType.requireTarget()` is called for `MODEL_BACK`
- **THEN** it MUST return `false`
- **AND** a `ModelAction` of type `MODEL_BACK` MUST be constructable with `target = null` without any error

#### Scenario: MODEL_LLM_TAP is a targetless model action

- **WHEN** `actionType.requireTarget()` is called for `MODEL_LLM_TAP`
- **THEN** it MUST return `false`
- **AND** `ActionType.MODEL_LLM_TAP.isModelAction()` MUST return `true`
- **AND** an `LlmTapAction` MUST be constructable with `target = null` and a pixel coordinate without any error

#### Scenario: Non-model action types do not require a target

- **WHEN** `actionType.requireTarget()` is called for any of: `PHANTOM_CRASH`, `FUZZ`, `EVENT_START`, `EVENT_RESTART`, `EVENT_CLEAN_RESTART`, `EVENT_NOP`, `EVENT_ACTIVATE`, `EVENT_TRIGGER_ACTIVITY`
- **THEN** it MUST return `false`

## ADDED Requirements

### Requirement: LLM Coordinate Tap Action

The model MUST provide `LlmTapAction`, a subclass of `ModelAction` with `actionType = MODEL_LLM_TAP` and `target = null`, carrying an off-tree tap payload: an integer pixel coordinate `(pixelX, pixelY)` and a `longClick` flag. It is constructed only by `LlmRouter` for the off-tree case and returned directly to the agent; it is NOT a member of any `State.actions` array.

Because `MODEL_LLM_TAP.isModelAction()` is `true`, the agent-side action resolution path reads the action's resolved `GUITreeAction` and asserts it is non-null. A synthesized `LlmTapAction`, being absent from `State.actions`, is not resolved by the normal per-state resolution pass. Therefore the agent MUST resolve the synthesized tap against the current state before it is dispatched — populating its `GUITreeAction` via the targetless resolution branch (`target = null`), exactly as `MODEL_MENU` and `MODEL_BACK` are resolved. A widget action returned on the same path is already resolved and MUST NOT be re-resolved.

When executed, the tap dispatches a raw tap at `(pixelX, pixelY)` (long press when `longClick`), and the model records a `StateTransition` from the source state to the observed destination state, keyed on `(source, action, targetState)` per the existing transition machinery.

`LlmTapAction` identity extends the inherited `(state, target = null, type)` with its `(pixelX, pixelY, longClick)` payload (INV-MODEL-13), and `ModelAction.equals` guards `getClass() != obj.getClass()`, so an `LlmTapAction` is never equal to any `MODEL_MENU`, `MODEL_BACK`, or widget action. Two taps at different coordinates are different actions and own their edges separately; repeating the identical tap in the same state reuses that action's edge.

The synthesized tap is **ephemeral** (INV-MODEL-14): it is dispatched once and recorded as an observational edge, and never re-enters exploration. Because it is absent from `State.actions` it is never validated, so it remains invalid and no action filter admits it — it can be neither selected nor traversed as part of a path.

#### Scenario: LlmTapAction is a targetless model action

- **WHEN** `new LlmTapAction(state, 600, 900, false)` is constructed
- **THEN** `action.getType()` MUST equal `ActionType.MODEL_LLM_TAP`
- **AND** `action.getTarget()` MUST be `null`
- **AND** `action.requireTarget()` MUST return `false`
- **AND** `action.getPixelX()` MUST equal `600` AND `action.getPixelY()` MUST equal `900` AND `action.isLongClick()` MUST be `false`

#### Scenario: Synthesized tap is resolved before dispatch

- **WHEN** the agent receives a synthesized `LlmTapAction` from the LLM routing path
- **THEN** the agent MUST resolve it against the current state before dispatch, so its resolved `GUITreeAction` is non-null
- **AND** the action MUST pass through agent-side action resolution (which asserts a non-null `GUITreeAction` for `isModelAction() == true` actions) without error
- **AND** a widget action received on the same path MUST NOT be re-resolved

#### Scenario: Taps at different coordinates are different actions

- **WHEN** two `LlmTapAction`s in the same source state carry different coordinates, or the same coordinate with a differing `longClick` flag
- **THEN** they MUST NOT be equal
- **AND** each MUST key its own `StateTransition` edges, so a differing destination between them MUST NOT be recorded as one action with two outcomes

#### Scenario: Repeating the identical tap reuses its edge

- **WHEN** an `LlmTapAction` with the same `(state, pixelX, pixelY, longClick)` is dispatched twice and both times reaches the same destination state
- **THEN** the two actions MUST be equal with equal `hashCode`
- **AND** the model MUST record a single `StateTransition` for that `(source, action, targetState)` triple, with `hittingCount` incremented on the second traversal

#### Scenario: The ephemeral tap is accepted by graph visit bookkeeping

- **WHEN** the graph marks a synthesized `LlmTapAction` visited, at selection and again when its edge is recorded
- **THEN** it MUST NOT raise the unregistered-action sanity-check error
- **AND** the graph's unvisited and visited action inventories MUST remain unchanged
- **AND** a **non**-ephemeral model action that is not registered MUST still raise that error

#### Scenario: The ephemeral tap does not trigger naming refinement

- **WHEN** a `StateTransition` whose action is ephemeral is reported to non-determinism resolution
- **THEN** the model MUST be left unrefined and unrebuilt
