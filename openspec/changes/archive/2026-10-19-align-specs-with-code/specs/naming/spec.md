## Purpose

Android GUI testing faces a fundamental scalability problem: every distinct pixel arrangement, widget tree, or transient animation could be treated as a unique state, causing the state space to explode combinatorially. APE solves this with a *naming system* — a formal abstraction function that maps each concrete `GUITree` (a full DOM snapshot of the Android UI, captured via AccessibilityNodeInfo) to a compact abstract `State`. Two GUITrees that produce the same abstract representation under the current `Naming` are treated as one state in the exploration model, collapsing what would otherwise be thousands of ephemeral variants into a tractable model graph.

The core innovation is that abstraction is not fixed at design time. APE implements a CEGAR (Counterexample-Guided Abstraction Refinement) loop: the system starts with a deliberately coarse abstraction, detects when that coarseness introduces *non-determinism* (i.e., the same abstract state exhibits contradictory behaviour across separate visits), and automatically refines the abstraction to split the offending state into finer-grained states. Refinement is conservative — it produces a strictly finer `Naming` — and terminates when the finest possible abstraction (`AncestorNamer` over all attributes) is reached or when configuration limits are hit.

The abstraction is defined by a `Naming`, which is a set of `Namelet` rules. Each `Namelet` binds an XPath selector (identifying which widgets in the tree to match) to a `Namer` strategy (defining how to compute an abstract name for each matched widget). The `Namer` interface has a `naming(GUITreeNode) -> Name` method. When `Naming.naming(GUITree)` is called, it applies every `Namelet` to the tree's DOM, assigns a `Name` to each widget, and returns a `NamingResult` mapping each abstract `Name` to the set of `GUITreeNode`s that share that name. The `StateKey` is derived from the ordered array of `Name` values produced by the current `Naming`; two `GUITree`s with identical `Name` arrays map to the same abstract `State`.

The `Namer` implementations form a lattice ordered by discriminating power. The coarsest element is `EmptyNamer`, which returns the same empty `Name` (`""`) for every widget — meaning all widgets collapse into one group. The finest reachable element (without `useAncestorNamer`) is `parentTypeTextIndexNamer`, which considers the parent's attributes, the widget's class, its text/content-description, and its sibling index. When `useAncestorNamer=true`, the full-path `AncestorNamer` becomes the lattice top. This partial order is encoded in `NamerLattice`, which maps every `EnumSet<NamerType>` key to the corresponding `Namer` singleton and precomputes upper (finer), lower (coarser), and incomparable sets for each element. `NamerType` is an enum with values `TYPE`, `INDEX`, `PARENT`, `TEXT`, `ANCESTOR`; a `Namer`'s discriminating power is the cardinality and composition of its `NamerType` set.

Non-determinism is detected by `NamingFactory.resolveNonDeterminism()`, which is called by the exploration loop whenever a `StateTransition` is observed to have multiple distinct target states (`StateTransition`s diverging from the same action). For each pair of diverging transitions, the system computes a `GUITreeWidgetDiffer` to identify widgets that differ, then calls `refine()`. When `actionRefinementFirst=true` (the default) it tries action refinement first (splitting the *actions* within a state) and state refinement (splitting the *source* state) only if action refinement found no result; when `false` it tries state refinement first and action refinement only if state refinement found none. The chosen `RefinementResult` selects a finer `Namer` for the XPath selector of the discriminating widget, produces a new child `Naming` via `Naming.extend()` or `Naming.replaceLast()`, and then rebuilds the exploration model with `Model.rebuild()`, re-abstracting all affected `GUITree`s under the new `Naming`.

The `NamingManager` interface (with implementations `ActivityNamingManager`, `StateNamingManager`, and `MonolithicNamingManager`) mediates between the `NamingFactory` and the rest of the system. It maintains a per-tree `treeToNaming` map so that each `GUITree` is associated with exactly one current `Naming`, and provides `getNaming(GUITree)` as the single access point for the rest of the code. The plan key `ape.evolveModel` (default `true`, read as `RunContext.current().spec().exploration().evolveModel()`) enables CEGAR. The gate sits in the agent, not in `NamingFactory`: when it is `false`, `StatefulAgent.checkNonDeterministicTransitions` and `StatefulAgent.preEvolveModel` return before doing anything, so `Model.resolveNonDeterministicTransitions` (and through it `NamingFactory.resolveNonDeterminism`), `stateAbstraction` and `actionRefinement` are never invoked and the base `Naming` is used throughout. `NamingFactory` itself never reads the key. The refinement tuning flags (`actionRefinementFirst`, `maxStatesPerActivity`, `maxGUITreesPerState`, `useAncestorNamer`, `activityManagerType`) are `ape.utils.Config` fields.


## Data Contracts

### Input

- `tree: GUITree` — a concrete UI snapshot captured after each action; its `Document` (W3C DOM) is the primary input to `Naming.naming()`; its `ActivityName: ComponentName` is used by `ActivityNamingManager` to select the per-activity `Naming`
- `naming: Naming` — the current abstraction level; selected from the `NamingManager` by activity or globally
- `nst: StateTransition` — the newly observed non-deterministic transition; carries `source: State`, `target: State`, and `action: ModelAction`; passed to `NamingFactory.resolveNonDeterminism()` to trigger CEGAR
- `ape.evolveModel: boolean` (default `true`) — plan key (`KeyOwnership` base key, read through `spec().exploration().evolveModel()`); permits refinement, gated in `StatefulAgent` (see Purpose)
- `ape.actionRefinementFirst: boolean` (default `true`, `Config` field) — when `true`, action refinement is attempted before state refinement
- `ape.maxStatesPerActivity: int` (default `10`) — upper bound on abstract states per activity before refinement is suppressed
- `ape.maxGUITreesPerState: int` (default `20`) — upper bound on stored GUITrees per state before refinement is suppressed
- `ape.useAncestorNamer: boolean` (default `true`) — includes `AncestorNamer` variants in the lattice, making full-path discrimination available
- `ape.activityManagerType: String` (default `"state"`) — selects the `NamingManager` implementation: `"activity"` selects `ActivityNamingManager` (one `Naming` per activity); any other value (including the default `"state"`) selects `StateNamingManager` (one `Naming` per state). `MonolithicNamingManager` is only used when constructed explicitly (e.g., in replay scenarios).

### Side-Effects

- **[NamingManager.treeToNaming]**: `updateNaming(GUITree, Naming)` writes the new `Naming` into the per-tree dictionary and increments `NamingManager.version`; the exploration loop uses this version to detect when the model needs rebuilding
- **[GUITreeNode.xpathName / tempXPathName]**: `Naming.naming(tree, updateNodeName=true)` writes `Name` values back into each `GUITreeNode`; `tempXPathName` is a scratch field cleared after each naming pass; `xpathName` is the persisted value used by `ParentNamer` and `AncestorNamer` when processing children
- **[Naming.treeToNamingResult]**: results of `naming()` are cached per `GUITree` inside the `Naming` instance; cache entries are evicted via `Naming.release(GUITree)` when the tree is discarded from the model
- **[NamingFactory.NDActionBlacklist]**: after a refinement attempt that found no result, an action with 3 or more outgoing state transitions (`outStateTransitions.size() >= 3`) is added to this blacklist and excluded from future refinement attempts
- **[NamingFactory.guiTreeNamingBlaclist]** (the field's spelling in code): records which `(GUITree, Naming)` pairs have already been attempted for refinement to prevent cycles

### Error

- `IllegalStateException("A node has no namelets.")` — thrown during `Naming.namingInternal()` when a DOM element matches no `Namelet`; indicates a corrupt or unexpected tree structure; the bad tree is saved to `/sdcard/badtree.xml`
- `IllegalStateException("A node has no namelet.")` — thrown when `Namelet` selection among multiple candidates returns `null`; should not occur if `Naming` is well-formed
- `IllegalStateException("Parent name should not be null.")` — thrown by `ParentNamer.naming()` when a parent node's `tempXPathName` and `xpathName` are both `null`; indicates a top-down traversal was not performed before accessing child names
- `IllegalArgumentException("Extended child namer should refine to parent namer.")` — thrown by `Naming.extend()` when the candidate `Namelet`'s `Namer` does not satisfy `refinesTo()` with respect to the parent `Namelet`'s `Namer`; enforces the monotonicity of refinement
- `IllegalArgumentException("Incomplete lattice")` — thrown by `NamerLattice` constructor if either the bottom (`noneOf()`) or top (`allOf()`) element is absent, or if the complement of any element is missing; the lattice MUST be complete at construction time

## Invariants

- **INV-NAME-08**: When `ape.evolveModel=false`, `StatefulAgent.checkNonDeterministicTransitions` and `StatefulAgent.preEvolveModel` MUST return before any refinement, so `NamingFactory.resolveNonDeterminism()`, `stateAbstraction()` and `actionRefinement()` are never invoked. No `Naming.extend()`, `Naming.replaceLast()`, or `NamingManager.updateNaming()` call MAY occur during the session.

## MODIFIED Requirements

### Requirement: Model Rebuild After Refinement

When a `RefinementResult` is accepted, the model MUST be fully reconstructed under the updated `Naming`. All `GUITree`s previously assigned to the split state MUST be re-abstracted, potentially producing new `State` nodes in the model graph.

#### Scenario: affected trees are re-abstracted under new naming

- **WHEN** `NamingFactory.rebuild(model, rr)` is called with a non-null `RefinementResult` `rr`,
- **THEN** for every `GUITree` `t` in the affected set, `nm.updateNaming(t, rr.updatedNaming)` MUST be called,
- **AND** `model.rebuild()` MUST be invoked, which recomputes all `StateKey`s and reconstructs the `State` and `StateTransition` graph,
- **AND** `NamingManager.version` MUST be strictly greater after `rebuild()` than before.

#### Scenario: post-rebuild consistency check

- **WHEN** `NamingFactory.rebuild()` completes and debug mode is active (`debug=true` in `NamingFactory`),
- **THEN** for every affected `GUITree` `t`, `t.getCurrentNaming()` MUST equal `nm.getNaming(t)` MUST equal `nm.getNaming(t, activityName, document)`,
- **AND** any inconsistency MUST dump the naming manager (`nm.dump()`) and throw an `IllegalStateException` with no message.
- **AND** with `debug=false` (the compiled value of `NamingFactory.debug`) the check MUST NOT run.

### Requirement: evolveModel=false Freezes the Naming

When `ape.evolveModel=false`, the system MUST behave as a static abstraction tool. The gate is in the agent: `StatefulAgent.checkNonDeterministicTransitions` and `StatefulAgent.preEvolveModel` return at once, so no refinement entry point (`resolveNonDeterministicTransitions`, `stateAbstraction`, `actionRefinement`) is called. The base `Naming` (constructed at startup) MUST be used for all `GUITree`s throughout the entire testing session. No refinement MUST occur regardless of how many non-deterministic events are observed.

#### Scenario: non-determinism observed but not refined

- **WHEN** `ape.evolveModel=false` is set in the plan (`ape.properties` or the command line),
- **AND** action `A` from state `S` leads to two distinct target trees `t1` and `t2` with different action sets on two separate visits,
- **THEN** `StatefulAgent.checkNonDeterministicTransitions` MUST return without calling `Model.resolveNonDeterministicTransitions`, so `NamingFactory.resolveNonDeterminism` is never invoked and the agent's `model` remains the same object,
- **AND** `NamingManager.version` MUST remain unchanged,
- **AND** no `Naming.extend()`, `Naming.replaceLast()`, or `NamingManager.updateNaming()` call MUST occur during the session.
