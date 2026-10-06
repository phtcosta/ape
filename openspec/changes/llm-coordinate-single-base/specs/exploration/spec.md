# Delta: Exploration (llm-coordinate-single-base)

## Purpose

`StatefulAgent` records the action history the LLM prompt shows. Until this change it normalized each executed widget's centre to [0,1000) itself, dividing by `AndroidDevice.getDisplayBounds()`, while the element list of the same prompt divided by the active window's root node. It also recorded an off-tree `MODEL_LLM_TAP` and a `MODEL_MENU` as `click` with no node, which rendered as `click @(0,0)`: a click at the screen's corner that never happened, shown in 24.2 % of E6 arm-4 prompts (31,466 of 130,266) and 22.5 % of E5b `v13_A` prompts (2,157 of 9,586). Nor did it clamp: a widget centred below the display was recorded at y ≥ 1000, outside the space the model answers in (2,860 entries in 2,182 E6 prompts).

This delta moves the normalization out of the agent. The entry carries the executed widget's pixel bounds, and `ApePromptBuilder` renders them at build time with the decision's dimensions and the same visible-centre rule as the element list (`llm-prompt` "Action History", INV-PRM-06). Entries for actions without a resolved node record what ran. The requirement also corrects a stale name: the buffer reaches the builder through `LlmEngine.selectAction()`, not `LlmRouter.selectAction()`.

The entry's result also moves to the action it describes. `recordActionHistory` runs right after the next action is selected, before it is dispatched, and it stored in that action's entry the comparison of `newState` with `_lastState` (`StatefulAgent.java:828-829, 850-853, 1848-1854`): the outcome of the action before, since `newState` is the screen the previous action produced. No later step corrected it. Every entry therefore showed its predecessor's outcome, and the most recent action's own outcome appeared nowhere: after a click that opened a new screen and a click that did nothing, the prompt read `→ new screen` for both. This requirement has said since gh6 that the entry is appended after its action executes, and its scenarios pair a click with that click's outcome; the code never did. The comparison is unchanged. The entry is appended without a result when its action is selected, and the next state update fills the result before any prompt is built (design D12).

## MODIFIED Requirements

### Requirement: StatefulAgent — Action History Ring Buffer

`StatefulAgent` SHALL maintain a ring buffer of the last 5 selected model actions with their results. When a model action is selected, before it is dispatched, an `ActionHistoryEntry` SHALL be appended to the buffer with an empty result. The entry's result SHALL be the outcome of that entry's own action, filled by the next state update before any LLM stage of that step runs, so that no prompt pairs an action with another action's outcome.

**`ActionHistoryEntry`** is a simple data class:

| Field | Type | Description |
|-------|------|-------------|
| `actionType` | `String` | "click", "long_click", "type_text", "back", "menu" |
| `widgetClass` | `String` | Widget class simple name (e.g., "Button", "EditText"), null for back, menu and an off-tree LLM tap |
| `widgetText` | `String` | Widget text or content-description, truncated to 50 chars |
| `bounds` | `Rect` | The executed widget's bounds in screen **pixels**: a copy of the resolved node's `getBoundsInScreen()`; for an off-tree `MODEL_LLM_TAP`, the one-pixel rectangle `[x,y][x+1,y+1]` at `LlmTapAction.getPixelX()`/`getPixelY()`; null for back and menu |
| `typedText` | `String` | For type_text: the text that was typed; null otherwise |
| `result` | `String` | "same", "new screen", "previous screen"; empty until the next state update fills it |

`StatefulAgent` SHALL NOT normalize and SHALL NOT read `AndroidDevice.getDisplayBounds()` for the history. `ApePromptBuilder` derives the displayed coordinate at build time (the centre of the bounds' part inside the display) with the dimensions the decision was captured at (`llm-prompt` "Action History"), so the list and the history of one prompt share one base.

An action with no resolved node SHALL record what ran: a `MODEL_LLM_TAP` records `click`, or `long_click` when `LlmTapAction.isLongClick()` is true, with the one-pixel rectangle at the tap's own pixel, and no class or text; a `MODEL_MENU` press records `menu` with no bounds. A node action whose bounds cannot be read records null bounds, never a `(0,0)` placeholder.

**Result determination**: In `updateStateInternal()`, after the new state is resolved and `_lastState`/`_stateBeforeLast` have moved, and before `resolveNewAction()` runs, the last entry of the buffer, if its result is still empty, receives the outcome of its action. At that point `newState` is the screen that action produced, `_lastState` the state it was taken from, and `_stateBeforeLast` the state before that:
- If `newState == _lastState` → `"same"`
- If `newState == _stateBeforeLast` → `"previous screen"`
- Else → `"new screen"`

The entry is immutable: the agent replaces it with a copy carrying the result. A filled result is never overwritten. When the step after an entry's action selects no model action, nothing is appended, and the next update finds the last entry already filled, so an outcome is never assigned to an action it does not belong to.

The buffer is passed to `ApePromptBuilder.build()` via `LlmEngine.selectAction()` as the `recentActions` parameter.

#### Scenario: Action history populated after action

- **WHEN** a click action on Button "Login" is selected
- **THEN** an `ActionHistoryEntry` SHALL be added with `actionType="click"`, `widgetClass="Button"`, `widgetText="Login"` and an empty result
- **AND WHEN** the next state update resolves a state different from the state the click was taken from and from the state before that
- **THEN** that entry's result SHALL be `"new screen"` before any LLM stage of that step runs

#### Scenario: Ring buffer evicts oldest entry

- **WHEN** the action history buffer has 5 entries
- **AND** a new model action is selected
- **THEN** the oldest entry SHALL be removed
- **AND** the new entry SHALL be appended
- **AND** the buffer size SHALL remain 5

#### Scenario: Action returns to previous screen

- **WHEN** a back action is selected from state S2, which was reached from state S1
- **AND** the next state update resolves S1
- **THEN** the back action's `ActionHistoryEntry.result` SHALL be `"previous screen"`

#### Scenario: each entry carries its own action's outcome
- **WHEN** click A1 is selected from state S1 and the next update resolves a new state S2
- **AND** click A2 is selected from S2 and the next update resolves S2 again
- **THEN** the prompt built at that update SHALL show `A1 → new screen` and `A2 → same`
- **AND** A2's entry SHALL NOT read `new screen`, the outcome of A1

#### Scenario: a step without a model action does not shift an outcome
- **WHEN** click A1 is selected and the next update fills its result
- **AND** that step selects an action that is not a model action, so no entry is appended
- **AND** the update after it resolves a different state
- **THEN** A1's result SHALL keep the value filled first

#### Scenario: entry carries the pixel bounds
- **WHEN** a click on a widget with bounds `[0,105][1080,252]` is selected
- **THEN** the entry SHALL carry the bounds `[0,105][1080,252]`, a copy rather than the node's own `Rect`
- **AND** no display dimension SHALL have been read to produce it

#### Scenario: off-tree LLM tap recorded at its pixel
- **WHEN** a `MODEL_LLM_TAP` at pixel `(600, 900)` with `isLongClick()` false is selected
- **THEN** the entry SHALL carry `actionType="click"`, the bounds `[600,900][601,901]`, and no widget class

#### Scenario: menu press recorded as menu
- **WHEN** a `MODEL_MENU` action is selected
- **THEN** the entry SHALL carry `actionType="menu"`, no bounds and no widget class

---
