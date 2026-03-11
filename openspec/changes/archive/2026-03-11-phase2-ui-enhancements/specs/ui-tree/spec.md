## Purpose

This delta extends the UI-Tree specification to cover two changes introduced by Phase 2 UI Enhancements.

**AndroidX ViewPager detection**: The `getScrollType()` method in `GUITreeNode` previously recognised only `android.support.v4.view.ViewPager` (the legacy support library name) as a horizontal-scroll container. Applications built with AndroidX — the overwhelming majority of apps published after 2019 — use `androidx.viewpager.widget.ViewPager` and `androidx.viewpager2.widget.ViewPager2`. These class names were absent from the detection list, causing horizontal scroll actions (`MODEL_SCROLL_LEFT_RIGHT`, `MODEL_SCROLL_RIGHT_LEFT`) to be omitted for tab carousels and paged layouts in modern apps.

**MODEL_MENU exclusion from widget nodes**: The addition of `MODEL_MENU` to the `ActionType` enum (see exploration delta spec) requires a corresponding update to `GUITreeNode.resetActions()`. That method enforces a blocklist of action types that must never be assigned to widget nodes — types that operate at the device level rather than the widget level. `MODEL_BACK` is already on this list; `MODEL_MENU` must be added with identical treatment: assigning `MODEL_MENU` to a widget node would corrupt the per-node action set, so `resetActions()` must throw `IllegalStateException` if `MODEL_MENU` appears in the input array.

---

## MODIFIED Requirements

### Requirement: ViewPager Scroll Direction

`GUITreeNode.getScrollType()` SHALL recognise all three ViewPager class name variants — legacy support library and both AndroidX variants — as horizontal-scroll containers. The complete set of class names that map to `"horizontal"` for the ViewPager family is:

- `"android.support.v4.view.ViewPager"` (legacy; already present)
- `"androidx.viewpager.widget.ViewPager"` (AndroidX; **added**)
- `"androidx.viewpager2.widget.ViewPager2"` (AndroidX 2; **added**)

`RecyclerView` (`androidx.recyclerview.widget.RecyclerView`) SHALL NOT be added to any explicit horizontal-class list. RecyclerView's scroll orientation is set programmatically via `LayoutManager` and cannot be inferred from class name.

#### Scenario: Legacy support ViewPager node is recognised as horizontal
- **WHEN** a `GUITreeNode` has `className` equal to `"android.support.v4.view.ViewPager"` and `isScrollable()` returns `true`
- **THEN** `getScrollType()` MUST return `"horizontal"`

#### Scenario: AndroidX ViewPager node is recognised as horizontal
- **WHEN** a `GUITreeNode` has `className` equal to `"androidx.viewpager.widget.ViewPager"` and `isScrollable()` returns `true`
- **THEN** `getScrollType()` MUST return `"horizontal"`

#### Scenario: AndroidX ViewPager2 node is recognised as horizontal
- **WHEN** a `GUITreeNode` has `className` equal to `"androidx.viewpager2.widget.ViewPager2"` and `isScrollable()` returns `true`
- **THEN** `getScrollType()` MUST return `"horizontal"`

#### Scenario: RecyclerView is NOT assigned horizontal scroll direction by class name
- **WHEN** a `GUITreeNode` has `className` equal to `"androidx.recyclerview.widget.RecyclerView"` and `isScrollable()` returns `true`
- **THEN** `getScrollType()` MUST NOT return `"horizontal"` based solely on the class name
- **AND** the actual return value MUST reflect the runtime-set scroll direction (vertical by default, or as encoded in the `scrollable` bitmask field)

---

### Requirement: MODEL_BACK and MODEL_MENU Exclusion from Widget Nodes

`GUITreeNode.resetActions()` enforces a blocklist of `ActionType` values that operate at the device level and MUST NOT be assigned to individual widget nodes. These are `MODEL_BACK` and `MODEL_MENU`: both are issued to the Android device without targeting a specific UI element. Assigning either to a widget node would produce an invalid per-node action set.

When `resetActions()` encounters `MODEL_BACK` or `MODEL_MENU` in its input array it MUST throw `IllegalStateException` immediately, before modifying any flags on the receiver. The node's `clickable`, `longClickable`, and `scrollable` flags MUST remain unchanged after the exception.

The following types also remain on the blocklist and continue to throw `IllegalStateException`: `EVENT_START`, `EVENT_RESTART`, `EVENT_CLEAN_RESTART`, `FUZZ`, `EVENT_ACTIVATE`.

#### Scenario: resetActions() rejects MODEL_BACK
- **WHEN** `GUITreeNode.resetActions()` is called with an array containing `MODEL_BACK`
- **THEN** the method MUST throw an `IllegalStateException`
- **AND** the node's action flags MUST NOT be modified

#### Scenario: resetActions() rejects MODEL_MENU
- **WHEN** `GUITreeNode.resetActions()` is called with an array containing `MODEL_MENU`
- **THEN** the method MUST throw an `IllegalStateException`
- **AND** the node's `clickable`, `longClickable`, and `scrollable` flags MUST NOT be modified

---

## MODIFIED Invariants

- **INV-TREE-02**: `GUITreeNode.getScrollType()` MUST return `"horizontal"` when `className` equals `"android.support.v4.view.ViewPager"`, `"androidx.viewpager.widget.ViewPager"`, or `"androidx.viewpager2.widget.ViewPager2"` and `isScrollable()` returns `true`. *(Previously covered only the legacy class name.)*
- **INV-TREE-04**: `GUITreeNode.resetActions()` MUST NOT assign `MODEL_BACK` or `MODEL_MENU` to any widget node; the method MUST throw `IllegalStateException` if either appears in the input array. *(Previously specified MODEL_BACK only.)*

---

## MODIFIED Data Contracts

### Input

- **`GUITreeNode.resetActions(ActionType[])`**: The array MUST NOT contain `MODEL_BACK`, `MODEL_MENU`, `EVENT_START`, `EVENT_RESTART`, `EVENT_CLEAN_RESTART`, `FUZZ`, or `EVENT_ACTIVATE`. *(Previously did not list `MODEL_MENU`.)*
