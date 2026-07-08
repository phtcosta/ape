# ui-tree — delta: rv-scoring-pipeline

## Purpose

Gate the fork's GUITree perception enhancements behind the single `treeEnhancementsEnabled` parity flag (declared by the `scoring-pipeline` capability; default `true`), so the `ape_pure` arm inherits upstream APE's tree perception. The three enhancements — the WebView-prune actionable-count fix, AndroidX actionability, and ViewPager scrollable recognition — change *what the agent sees*, not how it scores; they are one conceptual change and share one flag. When the flag is `false`, `GUITreeBuilder` reproduces upstream perception, including the upstream WebView over-prune. `INV-TREE-02`, `INV-TREE-03`, `INV-TREE-11`, and `INV-TREE-12` describe the default (flag-on) behavior; this delta scopes them in the requirement prose and does not restate the global invariants.

> Note: AndroidX actionability is part of `GUITreeBuilder` node construction and has no dedicated named requirement; it is gated by the same `treeEnhancementsEnabled` flag and is described here for completeness. The two named requirements this delta modifies (`ViewPager Scroll Direction`, `WebView Pruning Correctness`) carry the observable gate scenarios.

## MODIFIED Requirements

### Requirement: ViewPager Scroll Direction

Recognition of the AndroidX ViewPager class-name variants as horizontal-scroll containers SHALL be gated by `Config.treeEnhancementsEnabled` (declared by the `scoring-pipeline` capability; default `true`).

When `treeEnhancementsEnabled` is `true` (default), `GUITreeNode.getScrollType()` SHALL recognise all three ViewPager class name variants — legacy support library and both AndroidX variants — as horizontal-scroll containers. The complete set of class names that map to `"horizontal"` for the ViewPager family is:

- `"android.support.v4.view.ViewPager"` (legacy support library)
- `"androidx.viewpager.widget.ViewPager"` (AndroidX)
- `"androidx.viewpager2.widget.ViewPager2"` (AndroidX 2)

`RecyclerView` (`androidx.recyclerview.widget.RecyclerView`) SHALL NOT be added to any explicit horizontal-class list. RecyclerView's scroll orientation is set programmatically via `LayoutManager` and cannot be inferred from class name.

When `treeEnhancementsEnabled` is `false` (the `ape_pure` arm), `getScrollType()` SHALL reproduce upstream APE's recognition set, which does not include the AndroidX ViewPager variants. INV-TREE-02 and INV-TREE-03 describe the default (flag-on) behavior.

#### Scenario: Legacy support ViewPager node is recognized as horizontal
- **WHEN** a `GUITreeNode` has `className` equal to `"android.support.v4.view.ViewPager"` and `isScrollable()` returns `true`
- **THEN** `getScrollType()` MUST return `"horizontal"`

#### Scenario: AndroidX ViewPager node is recognised as horizontal (flag on)
- **WHEN** `Config.treeEnhancementsEnabled` is `true` and a `GUITreeNode` has `className` equal to `"androidx.viewpager.widget.ViewPager"` and `isScrollable()` returns `true`
- **THEN** `getScrollType()` MUST return `"horizontal"`

#### Scenario: AndroidX ViewPager2 node is recognised as horizontal (flag on)
- **WHEN** `Config.treeEnhancementsEnabled` is `true` and a `GUITreeNode` has `className` equal to `"androidx.viewpager2.widget.ViewPager2"` and `isScrollable()` returns `true`
- **THEN** `getScrollType()` MUST return `"horizontal"`

#### Scenario: AndroidX ViewPager not recognised when the flag is off
- **WHEN** `Config.treeEnhancementsEnabled` is `false` and a `GUITreeNode` has `className` equal to `"androidx.viewpager2.widget.ViewPager2"` and `isScrollable()` returns `true`
- **THEN** `getScrollType()` MUST NOT return `"horizontal"` (upstream perception)

#### Scenario: RecyclerView is NOT assigned horizontal scroll direction by class name
- **WHEN** a `GUITreeNode` has `className` equal to `"androidx.recyclerview.widget.RecyclerView"` and `isScrollable()` returns `true`
- **THEN** `getScrollType()` MUST NOT return `"horizontal"` based solely on the class name
- **AND** the actual return value MUST reflect the runtime-set scroll direction (vertical by default, or as encoded in the scrollable field)

---

### Requirement: WebView Pruning Correctness

The WebView-pruning correctness fixes are gated by `Config.treeEnhancementsEnabled` (declared by the `scoring-pipeline` capability; default `true`). When `treeEnhancementsEnabled` is `true` (default), WebView pruning SHALL be structurally correct on both of its halves, and its DOM half SHALL be exception-safe:

1. `GUITreeNode.clearChildren` SHALL remove **all** DOM children in the normal case, iterating until the element is empty. The in-memory prune (`childCount = 0`, `children = null`) SHALL be applied unconditionally, before any DOM mutation.
2. If the DOM element rejects a removal with `org.w3c.dom.DOMException`, `clearChildren` SHALL stop the DOM removal loop, log one line — `[APE-RV] clearChildren DOM prune aborted: <exception message>` — and return normally. The exception SHALL NOT propagate to the caller (INV-TREE-12).
3. `GUITreeBuilder.checkAndRemoveWebView` SHALL compare the `ape.ignoreWebViewThreshold` (default 64) against the count of **actionable** descendants only (as its inline comment always stated), not the total descendant count.

When `treeEnhancementsEnabled` is `false` (the `ape_pure` arm), WebView pruning SHALL reproduce upstream APE's behavior: the threshold SHALL be compared against the total descendant count (the upstream over-prune), and the `clearChildren` correctness/exception-safety fixes SHALL NOT apply. INV-TREE-11 and INV-TREE-12 describe the default (flag-on) behavior.

#### Scenario: clearChildren empties the DOM (flag on)
- **WHEN** `Config.treeEnhancementsEnabled` is `true` and `clearChildren` is called on a node with 10 DOM children
- **THEN** the node SHALL have 0 DOM children afterwards

#### Scenario: DOM rejection does not abort the run (flag on)
- **WHEN** `Config.treeEnhancementsEnabled` is `true` and `clearChildren` is called on a node whose DOM element throws `DOMException` on the third `removeChild` call
- **THEN** `clearChildren` SHALL return normally (no exception reaches the caller)
- **AND** the node's in-memory child list SHALL be empty (`getChildCount() == 0`)
- **AND** one `[APE-RV] clearChildren DOM prune aborted` line SHALL be logged

#### Scenario: content-heavy but action-sparse WebView kept (flag on)
- **WHEN** `Config.treeEnhancementsEnabled` is `true` and a WebView subtree has 100 non-actionable descendants and 10 actionable ones, with `ape.ignoreWebViewThreshold=64`
- **THEN** the WebView SHALL be kept (10 ≤ 64)

#### Scenario: action-heavy WebView still pruned (flag on)
- **WHEN** `Config.treeEnhancementsEnabled` is `true` and a WebView subtree has 80 actionable descendants, with the default threshold
- **THEN** the WebView SHALL be pruned, and in the normal (non-degraded) case no phantom child SHALL remain in the DOM

#### Scenario: upstream over-prune reproduced when the flag is off
- **WHEN** `Config.treeEnhancementsEnabled` is `false` and a WebView subtree has 100 non-actionable descendants and 10 actionable ones, with `ape.ignoreWebViewThreshold=64`
- **THEN** the threshold SHALL be compared against the total descendant count (110 > 64), reproducing upstream APE's over-prune (the WebView is pruned)
