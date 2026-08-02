# Delta Specification: ui-tree (rearch-02-runspec)

## ADDED Requirements

### Requirement: No XPathlet Overlay Input

`GUITreeBuilder` SHALL build GUI trees exclusively from the live accessibility snapshot (plus the flag-gated perception enhancements specified elsewhere in this capability). The user-configurable XPathlet overlay — the static-initializer read of `/sdcard/ape.xpath` into a `List<XPathlet>` and every use of that list — SHALL NOT exist (owner decision D6: no arm uses it, the aperv deployment never pushes the file, and an undeclared device file silently reshaping tree construction is exactly the class of unecho'd behavioral input the run-spec capability eliminates; the main specification never covered the mechanism — this requirement records its removal explicitly). Behavior is byte-identical to the only condition ever deployed: overlay absent, empty rule list.

Note: this removes only the `/sdcard` *overlay* reader. The naming lattice's own XPath machinery (`Namelet` selectors, `Name.toXPath()`) is unrelated and untouched.

#### Scenario: legacy overlay file has no effect

- **WHEN** a legacy `/sdcard/ape.xpath` file exists on the device
- **THEN** `GUITreeBuilder` class initialization SHALL NOT open it
- **AND** tree construction SHALL be identical to a device with no such file

#### Scenario: tree construction reads only the accessibility snapshot

- **WHEN** a GUI tree is built during a run
- **THEN** its structure SHALL derive solely from the `AccessibilityNodeInfo` hierarchy and the in-jar perception logic
- **AND** no filesystem input SHALL participate in tree construction

## MODIFIED Requirements

### Requirement: ViewPager Scroll Direction

Recognition of the AndroidX ViewPager class-name variants as horizontal-scroll containers SHALL be gated by `Config.treeEnhancementsEnabled` (declared by the `scoring-pipeline` capability; default `true`).

When `treeEnhancementsEnabled` is `true` (default), `GUITreeNode.getScrollType()` SHALL recognise all three ViewPager class name variants — legacy support library and both AndroidX variants — as horizontal-scroll containers. The complete set of class names that map to `"horizontal"` for the ViewPager family is:

- `"android.support.v4.view.ViewPager"` (legacy support library)
- `"androidx.viewpager.widget.ViewPager"` (AndroidX)
- `"androidx.viewpager2.widget.ViewPager2"` (AndroidX 2)

`RecyclerView` (`androidx.recyclerview.widget.RecyclerView`) SHALL NOT be added to any explicit horizontal-class list. RecyclerView's scroll orientation is set programmatically via `LayoutManager` and cannot be inferred from class name.

When `treeEnhancementsEnabled` is `false` (the feature absent from the resolved plan — `run-spec` INV-RUN-05), `getScrollType()` SHALL reproduce upstream APE's recognition set, which does not include the AndroidX ViewPager variants. INV-TREE-02 and INV-TREE-03 describe the default (flag-on) behavior.

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

### Requirement: WebView Pruning Correctness

The WebView-pruning **actionable-descendant threshold** (point 3 below) is gated by `Config.treeEnhancementsEnabled` (declared by the `scoring-pipeline` capability; default `true`). The `clearChildren` correctness and DOMException-safety fixes (points 1–2, INV-TREE-10/INV-TREE-12) are **always-on** — a crash/correctness fix, not a perception enhancement — so they hold regardless of the plan. WebView pruning SHALL be structurally correct on both of its halves, and its DOM half SHALL be exception-safe:

1. `GUITreeNode.clearChildren` SHALL remove **all** DOM children in the normal case, iterating until the element is empty. The in-memory prune (`childCount = 0`, `children = null`) SHALL be applied unconditionally, before any DOM mutation.
2. If the DOM element rejects a removal with `org.w3c.dom.DOMException`, `clearChildren` SHALL stop the DOM removal loop, log one line — `[APE-RV] clearChildren DOM prune aborted: <exception message>` — and return normally. The exception SHALL NOT propagate to the caller (INV-TREE-12).
3. `GUITreeBuilder.checkAndRemoveWebView` SHALL compare the `ape.ignoreWebViewThreshold` (default 64) against the count of **actionable** descendants only (as its inline comment always stated), not the total descendant count.

When `treeEnhancementsEnabled` is `false` (the feature absent from the resolved plan — `run-spec` INV-RUN-05), the threshold (point 3) SHALL be compared against the total descendant count (the upstream over-prune). Points 1–2 (the `clearChildren` in-memory prune and DOMException safety, INV-TREE-10/INV-TREE-12) remain active regardless of the flag. INV-TREE-11 describes the flag-gated threshold; INV-TREE-12 is flag-independent (always-on).

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
