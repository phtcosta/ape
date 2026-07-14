## ADDED Requirements

### Requirement: GUITree.contains Not-Found Contract

`GUITree.contains(GUITreeNode)` locates the node's `Name` in the sorted `currentNames` array via `Arrays.binarySearch` before comparing the resident node. A negative return value SHALL be treated as "absent" (`index < 0`), never tested with `index == -1`. `Model.update` calls `tree.contains(node)` on every step precisely to discard stale nodes gracefully; the previous `== -1` test turned a not-found with insertion point ≠ 0 into a negative array index (`ArrayIndexOutOfBoundsException`), converting a graceful guard into a run-aborting crash. The four sibling lookups in `GUITree` already use `index < 0`; `contains` SHALL match them.

#### Scenario: stale node absent with non-zero insertion point
- **WHEN** `GUITree.contains(node)` is called for a node whose `Name` is absent from `currentNames` and whose insertion point is greater than 0 (binarySearch returns ≤ -2)
- **THEN** `contains` SHALL return false
- **AND** no exception SHALL be thrown

#### Scenario: present node unchanged
- **WHEN** the node's `Name` is found (binarySearch returns ≥ 0)
- **THEN** behavior SHALL be identical to the previous implementation

## Invariants

- **INV-TREE-08**: `GUITree.contains` SHALL never index `currentNodes` with a negative binary-search result; a negative result always yields `false`.
