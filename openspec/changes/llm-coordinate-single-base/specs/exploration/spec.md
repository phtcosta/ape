# Delta: Exploration (llm-coordinate-single-base)

## Purpose

`StatefulAgent` records the action history the LLM prompt shows. Until this change it normalized each executed widget's centre to [0,1000) itself, dividing by `AndroidDevice.getDisplayBounds()`, while the element list of the same prompt divided by the active window's root node. It also recorded an off-tree `MODEL_LLM_TAP` and a `MODEL_MENU` as `click` with no node, which rendered as `click @(0,0)`: a click at the screen's corner that never happened, shown in 24.2 % of E6 arm-4 prompts (31,466 of 130,266) and 22.5 % of E5b `v13_A` prompts (2,157 of 9,586). Nor did it clamp: a widget centred below the display was recorded at y ≥ 1000, outside the space the model answers in (2,860 entries in 2,182 E6 prompts).

This delta moves the normalization out of the agent. The entry carries the executed widget's pixel bounds, and `ApePromptBuilder` renders them at build time with the decision's dimensions and the same visible-centre rule as the element list (`llm-prompt` "Action History", INV-PRM-06). Entries for actions without a resolved node record what ran.

## MODIFIED Requirements

### Requirement: StatefulAgent — Action History Ring Buffer

`StatefulAgent` SHALL maintain a ring buffer of the last 5 executed actions with their results. After each action is executed and the new state is determined, an `ActionHistoryEntry` SHALL be appended to the buffer.

**`ActionHistoryEntry`** is a simple data class:

| Field | Type | Description |
|-------|------|-------------|
| `actionType` | `String` | "click", "long_click", "type_text", "back", "menu" |
| `widgetClass` | `String` | Widget class simple name (e.g., "Button", "EditText"), null for back, menu and an off-tree LLM tap |
| `widgetText` | `String` | Widget text or content-description, truncated to 50 chars |
| `bounds` | `Rect` | The executed widget's bounds in screen **pixels**: a copy of the resolved node's `getBoundsInScreen()`; for an off-tree `MODEL_LLM_TAP`, the one-pixel rectangle `[x,y][x+1,y+1]` at `LlmTapAction.getPixelX()`/`getPixelY()`; null for back and menu |
| `typedText` | `String` | For type_text: the text that was typed; null otherwise |
| `result` | `String` | "same", "new screen", "previous screen" |

`StatefulAgent` SHALL NOT normalize and SHALL NOT read `AndroidDevice.getDisplayBounds()` for the history. `ApePromptBuilder` derives the displayed coordinate at build time (the centre of the bounds' part inside the display) with the dimensions the decision was captured at (`llm-prompt` "Action History"), so the list and the history of one prompt share one base.

An action with no resolved node SHALL record what ran: a `MODEL_LLM_TAP` records `click`, or `long_click` when `LlmTapAction.isLongClick()` is true, with the one-pixel rectangle at the tap's own pixel, and no class or text; a `MODEL_MENU` press records `menu` with no bounds. A node action whose bounds cannot be read records null bounds, never a `(0,0)` placeholder.

**Result determination**: After `updateStateInternal()` resolves the new state:
- If `newState == _lastState` → `"same"`
- If `newState == _stateBeforeLast` → `"previous screen"`
- Else → `"new screen"`

The buffer is passed to `ApePromptBuilder.build()` via `LlmEngine.selectAction()` as the `recentActions` parameter.

#### Scenario: Action history populated after action

- **WHEN** a click action on Button "Login" is executed
- **AND** the resulting state is different from the previous state and from the state before that
- **THEN** an `ActionHistoryEntry` SHALL be added with `actionType="click"`, `widgetClass="Button"`, `widgetText="Login"`, `result="new screen"`

#### Scenario: Ring buffer evicts oldest entry

- **WHEN** the action history buffer has 5 entries
- **AND** a new action is executed
- **THEN** the oldest entry SHALL be removed
- **AND** the new entry SHALL be appended
- **AND** the buffer size SHALL remain 5

#### Scenario: Action returns to previous screen

- **WHEN** a back action is executed
- **AND** the resulting state equals `_stateBeforeLast`
- **THEN** the `ActionHistoryEntry.result` SHALL be `"previous screen"`

#### Scenario: entry carries the pixel bounds
- **WHEN** a click on a widget with bounds `[0,105][1080,252]` is executed
- **THEN** the entry SHALL carry the bounds `[0,105][1080,252]`, a copy rather than the node's own `Rect`
- **AND** no display dimension SHALL have been read to produce it

#### Scenario: off-tree LLM tap recorded at its pixel
- **WHEN** a `MODEL_LLM_TAP` at pixel `(600, 900)` with `isLongClick()` false is executed
- **THEN** the entry SHALL carry `actionType="click"`, the bounds `[600,900][601,901]`, and no widget class

#### Scenario: menu press recorded as menu
- **WHEN** a `MODEL_MENU` action is executed
- **THEN** the entry SHALL carry `actionType="menu"`, no bounds and no widget class

---
