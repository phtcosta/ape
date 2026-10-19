# Delta: UI Tree (isolate-guidance-groups)

## Purpose

Two perception enhancements of the tree — the AndroidX ViewPager recognition and the actionable-descendant WebView threshold — were gated in the main spec by `Config.treeEnhancementsEnabled`, a `Config` read whose default was `true`. `isolate-guidance-groups` makes them members of the ADDONS group (design D-B1, D-B6): the gate is `Feature.TREE_ENHANCEMENTS`, activated by `ape.treeEnhancementsEnabled` and read through `RunContext.current().spec().has(Feature.TREE_ENHANCEMENTS)` (`tree/GUITreeNode.java`, `tree/GUITreeBuilder.java`; `guidance-groups` read-site table). The ADDONS block states `ape.treeEnhancementsEnabled=true`, so the enhancements are on in the four `as*` cells (`as`, `as_mop`, `as_llm`, `as_mop_llm`) and off in `base` and `mop`, where the tree reproduces upstream APE's recognition set and upstream's WebView threshold. The correctness fixes of `clearChildren` (INV-TREE-10, INV-TREE-12) stay always-on in every cell: they are a crash and correctness fix, not a perception enhancement.

## Invariants

The `## Invariants` section of the main spec is not synced by `openspec archive`; it is hand-synced at archive (task 14.4, author decision A1), and these two entries SHALL then read:

- **INV-TREE-02** (amended by `isolate-guidance-groups`): `GUITreeNode.getScrollType()` MUST return `"horizontal"` when `className` equals `"android.support.v4.view.ViewPager"` and `isScrollable()` returns `true`. The two AndroidX variants (`"androidx.viewpager.widget.ViewPager"`, `"androidx.viewpager2.widget.ViewPager2"`) are additionally recognized only when `TREE_ENHANCEMENTS` is in the plan (the `as*` cells); without it (`base`, `mop`) only the legacy support-library class is recognized (upstream perception).
- **INV-TREE-11** (amended by `isolate-guidance-groups`): When `TREE_ENHANCEMENTS` is in the plan, the WebView-removal threshold SHALL be evaluated over actionable descendants only; when it is not (`base`, `mop`), it is evaluated over the total descendant count (upstream over-prune).

## MODIFIED Requirements

### Requirement: ViewPager Scroll Direction

Recognition of the AndroidX ViewPager class-name variants as horizontal-scroll containers SHALL be gated by `Feature.TREE_ENHANCEMENTS`, the ADDONS member activated by `ape.treeEnhancementsEnabled` and read through `RunContext.current().spec().has(Feature.TREE_ENHANCEMENTS)`. No site SHALL read `Config.treeEnhancementsEnabled`. The ADDONS block states `ape.treeEnhancementsEnabled=true`, so the feature is in the plan of the `as`, `as_mop`, `as_llm` and `as_mop_llm` cells and absent from `base` and `mop`.

When `TREE_ENHANCEMENTS` is in the plan, `GUITreeNode.getScrollType()` SHALL recognise all three ViewPager class name variants — legacy support library and both AndroidX variants — as horizontal-scroll containers. The complete set of class names that map to `"horizontal"` for the ViewPager family is:

- `"android.support.v4.view.ViewPager"` (legacy support library)
- `"androidx.viewpager.widget.ViewPager"` (AndroidX)
- `"androidx.viewpager2.widget.ViewPager2"` (AndroidX 2)

`RecyclerView` (`androidx.recyclerview.widget.RecyclerView`) SHALL NOT be added to any explicit horizontal-class list. RecyclerView's scroll orientation is set programmatically via `LayoutManager` and cannot be inferred from class name.

When `TREE_ENHANCEMENTS` is not in the plan (the `base` and `mop` cells, or an `as*` cell with `ape.treeEnhancementsEnabled=false`), `getScrollType()` SHALL reproduce upstream APE's recognition set, which does not include the AndroidX ViewPager variants. INV-TREE-02 and INV-TREE-03 describe the behavior with the feature in the plan.

#### Scenario: Legacy support ViewPager node is recognized as horizontal
- **WHEN** a `GUITreeNode` has `className` equal to `"android.support.v4.view.ViewPager"` and `isScrollable()` returns `true`, in any cell
- **THEN** `getScrollType()` MUST return `"horizontal"`

#### Scenario: AndroidX ViewPager node is recognised as horizontal (flag on)
- **WHEN** the run's preset is `as` (so `TREE_ENHANCEMENTS` is in the plan) and a `GUITreeNode` has `className` equal to `"androidx.viewpager.widget.ViewPager"` and `isScrollable()` returns `true`
- **THEN** `getScrollType()` MUST return `"horizontal"`

#### Scenario: AndroidX ViewPager2 node is recognised as horizontal (flag on)
- **WHEN** the run's preset is `as_mop` (so `TREE_ENHANCEMENTS` is in the plan) and a `GUITreeNode` has `className` equal to `"androidx.viewpager2.widget.ViewPager2"` and `isScrollable()` returns `true`
- **THEN** `getScrollType()` MUST return `"horizontal"`

#### Scenario: AndroidX ViewPager not recognised when the flag is off
- **WHEN** the run's preset is `base` or `mop` (no `ADDONS`, so `TREE_ENHANCEMENTS` is absent) and a `GUITreeNode` has `className` equal to `"androidx.viewpager2.widget.ViewPager2"` and `isScrollable()` returns `true`
- **THEN** `getScrollType()` MUST NOT return `"horizontal"` (upstream perception)

#### Scenario: RecyclerView is NOT assigned horizontal scroll direction by class name
- **WHEN** a `GUITreeNode` has `className` equal to `"androidx.recyclerview.widget.RecyclerView"` and `isScrollable()` returns `true`, in any cell
- **THEN** `getScrollType()` MUST NOT return `"horizontal"` based solely on the class name
- **AND** the actual return value MUST reflect the runtime-set scroll direction (vertical by default, or as encoded in the scrollable field)

### Requirement: WebView Pruning Correctness

The WebView-pruning **actionable-descendant threshold** (point 3 below) is gated by `Feature.TREE_ENHANCEMENTS`, the ADDONS member read through `RunContext.current().spec().has(Feature.TREE_ENHANCEMENTS)`; no site SHALL read `Config.treeEnhancementsEnabled`. The feature is in the plan of the four `as*` cells and absent from `base` and `mop`. The `clearChildren` correctness and DOMException-safety fixes (points 1–2, INV-TREE-10/INV-TREE-12) are **always-on** — a crash/correctness fix, not a perception enhancement — so they hold in every cell, `base` and `mop` included. WebView pruning SHALL be structurally correct on both of its halves, and its DOM half SHALL be exception-safe:

1. `GUITreeNode.clearChildren` SHALL remove **all** DOM children in the normal case, iterating until the element is empty. The in-memory prune (`childCount = 0`, `children = null`) SHALL be applied unconditionally, before any DOM mutation.
2. If the DOM element rejects a removal with `org.w3c.dom.DOMException`, `clearChildren` SHALL stop the DOM removal loop, log one line — `[APE-RV] clearChildren DOM prune aborted: <exception message>` — and return normally. The exception SHALL NOT propagate to the caller (INV-TREE-12).
3. `GUITreeBuilder.checkAndRemoveWebView` SHALL compare the `ape.ignoreWebViewThreshold` (default 64) against the count of **actionable** descendants only (as its inline comment always stated), not the total descendant count.

When `TREE_ENHANCEMENTS` is not in the plan, the threshold (point 3) SHALL be compared against the total descendant count (the upstream over-prune). Points 1–2 remain active regardless of the plan. INV-TREE-11 describes the feature-gated threshold; INV-TREE-12 is plan-independent (always-on).

#### Scenario: clearChildren empties the DOM (flag on)
- **WHEN** `clearChildren` is called on a node with 10 DOM children, in the `as` cell and equally in the `base` cell
- **THEN** the node SHALL have 0 DOM children afterwards in both cells

#### Scenario: DOM rejection does not abort the run (flag on)
- **WHEN** `clearChildren` is called on a node whose DOM element throws `DOMException` on the third `removeChild` call, in any of the six cells
- **THEN** `clearChildren` SHALL return normally (no exception reaches the caller)
- **AND** the node's in-memory child list SHALL be empty (`getChildCount() == 0`)
- **AND** one `[APE-RV] clearChildren DOM prune aborted` line SHALL be logged

#### Scenario: content-heavy but action-sparse WebView kept (flag on)
- **WHEN** the run's preset is `as` (so `TREE_ENHANCEMENTS` is in the plan) and a WebView subtree has 100 non-actionable descendants and 10 actionable ones, with `ape.ignoreWebViewThreshold=64`
- **THEN** the WebView SHALL be kept (10 ≤ 64)

#### Scenario: action-heavy WebView still pruned (flag on)
- **WHEN** the run's preset is `as_mop_llm` (so `TREE_ENHANCEMENTS` is in the plan) and a WebView subtree has 80 actionable descendants, with the default threshold 64
- **THEN** the WebView SHALL be pruned, and in the normal (non-degraded) case no phantom child SHALL remain in the DOM

#### Scenario: upstream over-prune reproduced when the flag is off
- **WHEN** the run's preset is `base` or `mop` (so `TREE_ENHANCEMENTS` is absent) and a WebView subtree has 100 non-actionable descendants and 10 actionable ones, with `ape.ignoreWebViewThreshold=64`
- **THEN** the threshold SHALL be compared against the total descendant count (110 > 64), reproducing upstream APE's over-prune (the WebView is pruned)
