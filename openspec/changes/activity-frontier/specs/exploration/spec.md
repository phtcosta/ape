# exploration — delta: activity-frontier

## Purpose

Register the new non-model action type used by the stagnation-triggered activity launcher. `EVENT_TRIGGER_ACTIVITY` follows the `EVENT_RESTART` template: a first-class step that is not a gesture (no widget target), not a model action, and never a graph edge label.

> Note: the main `exploration` spec carries a second, overlapping enumeration of the non-model constants in **INV-EXPL-05** (`ActionType.isModelAction()` returns `false` for `PHANTOM_CRASH`, `FUZZ`, `EVENT_START`, `EVENT_RESTART`, `EVENT_CLEAN_RESTART`, `EVENT_NOP`, `EVENT_ACTIVATE`) — under a different requirement than the `ActionType Classification` one modified here. That list MUST also gain `EVENT_TRIGGER_ACTIVITY` so it stays exhaustive; this is tracked as a task and applied when the change is synced/archived (INV-EXPL-05 belongs to a requirement this delta does not otherwise modify, so it is not restated as a MODIFIED requirement here).

## MODIFIED Requirements

### Requirement: ActionType Classification

`ActionType` is an enum in `com.android.commands.monkey.ape.model.ActionType` that classifies every action the exploration engine can perform. The `requireTarget()` predicate SHALL return `true` if and only if the action type requires a specific widget node as its target (i.e., the action is a gesture or input directed at a UI element). The `isModelAction()` predicate SHALL return `true` for all `MODEL_*` constants and `false` for all event and phantom constants.

The full set of `MODEL_*` action types and their `requireTarget()` values are:

| ActionType | requireTarget() | Description |
|---|---|---|
| `MODEL_BACK` | `false` | BACK key press; no widget target |
| `MODEL_MENU` | `false` | MENU key press; no widget target |
| `MODEL_CLICK` | `true` | Tap on a widget node |
| `MODEL_LONG_CLICK` | `true` | Long-press on a widget node |
| `MODEL_SCROLL_BOTTOM_UP` | `true` | Scroll-up gesture on a widget |
| `MODEL_SCROLL_TOP_DOWN` | `true` | Scroll-down gesture on a widget |
| `MODEL_SCROLL_LEFT_RIGHT` | `true` | Swipe-right gesture (ViewPager tabs) |
| `MODEL_SCROLL_RIGHT_LEFT` | `true` | Swipe-left gesture (ViewPager tabs) |

Non-model types (`PHANTOM_CRASH`, `FUZZ`, `EVENT_START`, `EVENT_RESTART`, `EVENT_CLEAN_RESTART`, `EVENT_NOP`, `EVENT_ACTIVATE`, `EVENT_TRIGGER_ACTIVITY`) SHALL have `isModelAction()` return `false` and are not used as graph edge labels. `EVENT_TRIGGER_ACTIVITY` is the stagnation-triggered activity launch step (component-triggering spec): `requireTarget()` SHALL return `false` for it, and its event generation dispatches an activity-launch intent instead of a GUI gesture.

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

#### Scenario: predicates on EVENT_TRIGGER_ACTIVITY

- **WHEN** `ActionType.EVENT_TRIGGER_ACTIVITY.requireTarget()` and `ActionType.EVENT_TRIGGER_ACTIVITY.isModelAction()` are called
- **THEN** both SHALL return `false`
