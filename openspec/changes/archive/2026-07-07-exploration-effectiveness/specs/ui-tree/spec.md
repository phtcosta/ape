## ADDED Requirements

### Requirement: isPassword Capture

`GUITreeBuilder.fillNode` SHALL copy `AccessibilityNodeInfo.isPassword()` into the `GUITreeNode` (`setIsPassword`), alongside the other per-node properties it already captures. The heuristic-input capability's category detection lists `node.isPassword()` as its priority-1 PASSWORD signal; without the capture the flag is constant `false` and that rule is unreachable, so real password fields fall through to keyword matching or GENERIC values, authentication flows fail, and code paths behind login (typical MOP-bearing handlers in crypto apps) are never reached. The XML serialization of the node reflects the captured value.

#### Scenario: password field captured
- **WHEN** a `GUITreeNode` is built from an `AccessibilityNodeInfo` whose `isPassword()` returns true
- **THEN** `node.isPassword()` SHALL return true
- **AND** the node's XML serialization SHALL carry `password="true"`

#### Scenario: non-password field unchanged
- **WHEN** the accessibility node's `isPassword()` returns false
- **THEN** `node.isPassword()` SHALL return false

### Requirement: WebView Pruning Correctness

WebView pruning SHALL be structurally correct on both of its halves:

1. `GUITreeNode.clearChildren` SHALL remove **all** DOM children. The previous forward-index iteration over the live `NodeList` skipped every other node while removing, leaving ~half the children in the DOM as phantom nodes that naming later turned into clickable widgets and actions on content that had been pruned from the in-memory model.
2. `GUITreeBuilder.checkAndRemoveWebView` SHALL compare the `ape.ignoreWebViewThreshold` (default 64) against the count of **actionable** descendants only (as its inline comment always stated), not the total descendant count. Counting all descendants made virtually every real WebView exceed the bar on non-actionable nodes, so legitimate web content was discarded and never explored.

#### Scenario: clearChildren empties the DOM
- **WHEN** `clearChildren` is called on a node with 10 DOM children
- **THEN** the node SHALL have 0 DOM children afterwards

#### Scenario: content-heavy but action-sparse WebView kept
- **WHEN** a WebView subtree has 100 non-actionable descendants and 10 actionable ones, with `ape.ignoreWebViewThreshold=64`
- **THEN** the WebView SHALL be kept (10 ≤ 64)

#### Scenario: action-heavy WebView still pruned
- **WHEN** a WebView subtree has 80 actionable descendants, with the default threshold
- **THEN** the WebView SHALL be pruned, and after pruning no phantom child SHALL remain in the DOM

## Invariants

- **INV-TREE-09**: Every `GUITreeNode` built from an accessibility node SHALL carry that node's `isPassword()` value.
- **INV-TREE-10**: After `clearChildren`, the DOM element SHALL have zero children.
- **INV-TREE-11**: The WebView-removal threshold SHALL be evaluated over actionable descendants only.
