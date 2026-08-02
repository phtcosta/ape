## ADDED Requirements

### Requirement: GUITree Release Clears Static Naming Caches

`GUITreeBuilder` maintains three static memoization caches over immutable snapshot data: `namingToGUITreeCache` (`Naming → GUITree → StateKey`), `namingToGUITreeNodesCache` (`Naming → GUITree → Object[]`), and the per-node cache `namingToGUITreeNodeCache` (after this change keyed `Naming → GUITree → (GUITreeNode → Name)`). All three memoize pure functions — `State.buildStateKey` and `Naming.getName` are deterministic over a frozen tree — so cache eviction can only cause recomputation of identical values, never a different answer.

`GUITreeBuilder.release(GUITree removed)` SHALL remove, in the same release cycle, every entry of all three caches that is keyed by the released tree — under **every** `Naming`, not only `removed.getCurrentNaming()`. At HEAD, `release()` cleared only the two per-tree caches and only under the current naming, and never touched the per-node cache at all (V12, report `docs/analise_fable-selecao.md` Sec. 3.1): every `(naming, node, Name)` entry, and every per-tree entry created under a refinement-probe naming, lived for the whole run.

The static-cache sweep SHALL run unconditionally, before the existing `currentNaming == null` early return; `naming.release(removed)` keeps its current guard (naming-internal structures are out of this change's scope — report Sec. 6.7 defers them behind profiling).

Release SHALL be the last operation of the release cycle that touches the removed tree: no caller may query `getStateKey`/`getNodeName` for a tree after releasing it, because that re-inserts cache entries for a dead tree. The one existing violator — `checkAndRefreshNewState` computing `isTopNamingEquivalent(removed, last)` after the release (`StatefulAgent.java:689` then `:692`) — computes the equivalence before invoking the release cycle. The computation is pure, so the reorder is decision-neutral.

#### Scenario: release clears the per-node naming cache with its tree

- **WHEN** a `GUITree` with cached `getNodeName` entries (for one or more of its `GUITreeNode`s, under the current naming) is released via `GUITreeBuilder.release`
- **THEN** the per-node cache SHALL contain no entry for any node of that tree
- **AND** the two per-tree caches SHALL contain no entry for that tree

#### Scenario: release clears entries created under a non-current naming

- **WHEN** refinement probing has populated cache entries for a tree under a candidate `Naming` different from `tree.getCurrentNaming()`, and the tree is then released
- **THEN** those entries SHALL also be removed — the sweep covers every naming key present in the caches

#### Scenario: a released tree is never re-cached

- **WHEN** the instability recheck (`checkAndRefreshNewState`) removes a duplicate tree, needs its top-naming state key for the equivalence check, and releases it
- **THEN** the equivalence SHALL be computed before the release cycle runs
- **AND** after `release` returns, no subsequent operation of that cycle SHALL insert a cache entry keyed by the released tree

#### Scenario: cache eviction is recomputation-only

- **WHEN** a cache entry for a **live** tree is absent (never cached, or hypothetically evicted) and `getNodeName`/`getStateKey` is queried again
- **THEN** the recomputed value SHALL be equal to the previously cached value — the caches memoize deterministic functions of frozen snapshot data, so no decision can depend on an entry's presence

## Invariants

- **INV-TREE-13**: After `GUITreeBuilder.release(tree)` returns, no static `GUITreeBuilder` cache SHALL hold an entry keyed by that tree or by any `GUITreeNode` of that tree, under any `Naming`; and no operation of the same release cycle SHALL touch the released tree afterwards (release is the cycle's last use of the tree). Cache clearing MUST be decision-neutral: same seed ⇒ identical action sequence with and without the entries present (verified by the rearch-01 parity goldens).
