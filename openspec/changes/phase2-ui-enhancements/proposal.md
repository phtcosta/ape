## Why

APE's GUI exploration misses two systematic coverage opportunities in modern Android apps: (1) it does not recognise AndroidX `ViewPager`/`ViewPager2` class names, so tab-based carousels are never scrolled horizontally; and (2) there is no `MODEL_MENU` action type, so the `OptionsMenu` (three-dot overflow menu) is only pressed randomly during fuzzing, never tracked in the state model or guaranteed to be explored. Both gaps reduce activity and method coverage on apps built with the AndroidX toolkit — the majority of apps in rv-android's experiment corpus.

See issue tracker: Phase 2 of the APE-RV pre-plan (`docs/plans/20260311_pre_plan.md`).

## What Changes

- **`GUITreeNode.getScrollType()`**: Add two AndroidX class names to the horizontal-scroll detection block: `androidx.viewpager.widget.ViewPager` and `androidx.viewpager2.widget.ViewPager2`. No other changes to scroll detection logic.
- **`ActionType` enum**: Add `MODEL_MENU` (after `MODEL_BACK`). Must return `false` from `requireTarget()` and `true` from `isModelAction()`.
- **`State`**: Add `menuAction` field (type `ModelAction(this, ActionType.MODEL_MENU)`) constructed alongside `backAction`; expose via `getMenuAction()`.
- **`MonkeySourceApe`**: Add `case MODEL_MENU` in `generateEventsForActionInternal()` (calls `generateKeyMenuEvent()`); add `MODEL_MENU` to `validateResolvedAction()` as always-valid (same logic as `MODEL_BACK`).
- **`GUITreeNode.resetActions()`**: Add `MODEL_MENU` to the explicit block-list that prevents widget nodes from being assigned non-widget action types.
- **`SataAgent`**: After the existing `MODEL_BACK` unvisited-high-priority check, add an equivalent check for `MODEL_MENU`.

## Capabilities

### Modified Capabilities

- `ui-tree`: `GUITreeNode.getScrollType()` extended with `androidx.viewpager.widget.ViewPager` and `androidx.viewpager2.widget.ViewPager2` as horizontal-scroll class names; `GUITreeNode.resetActions()` blocklist extended to reject `MODEL_MENU`.
- `exploration`: `ActionType` enum extended with `MODEL_MENU`; `State` gains `menuAction` field and `getMenuAction()` accessor; `MonkeySourceApe` dispatch and validation updated; `SataAgent` unvisited-priority logic updated.

## Impact

- **FR02** (from `docs/PRD.md`): AndroidX UI coverage — directly addressed by both sub-tasks.
- **Files modified** (5): `GUITreeNode.java`, `ActionType.java`, `State.java`, `MonkeySourceApe.java`, `SataAgent.java`.
- **No new dependencies** — reuses existing scroll event infrastructure (`MODEL_SCROLL_LEFT_RIGHT`/`MODEL_SCROLL_RIGHT_LEFT`) and existing key-event infrastructure (`generateKeyMenuEvent()`).
- **Ordinal risk**: Inserting `MODEL_MENU` between `MODEL_BACK` and `MODEL_CLICK` shifts ordinals of all `MODEL_CLICK..MODEL_SCROLL_RIGHT_LEFT`. Must verify `requireTarget()` ordinal range after insertion to ensure the target-required action window remains correct.
- **`KEYCODE_MENU` on menuless devices**: On devices without a hardware MENU button, `MODEL_MENU` will be tried once per state, produce no visible result, become marked as visited, and contribute negligible overhead.
