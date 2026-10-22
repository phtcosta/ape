## MODIFIED Requirements

### Requirement: Namelet Binary-Search Not-Found Contract

`Naming.select()` locates a `Namelet` in the sorted namelet array through `Naming.containsNamelet(sorted, n)`, which calls `Collections.binarySearch(sorted, n, comparator)`. `binarySearch` returns `-(insertionPoint) - 1` for a missing element, so a missing element whose insertion point is not 0 yields a value below `-1`. The membership test depends on `Feature.APE_FIXES`, read through `RunContext.current().spec().has(Feature.APE_FIXES)`, at this single site:

- With the member (the four ADDONS cells), a negative return value SHALL be treated as "absent" regardless of its magnitude: `containsNamelet` SHALL return `binarySearch(...) >= 0`.
- Without the member (`base`, `mop`), `containsNamelet` SHALL return `binarySearch(...) != -1`, which is upstream APE's `== -1 → break` test written as a membership test: only a result of exactly `-1` reads as absent, so a missing namelet whose insertion point is greater than 0 reads as present.

In both cases the result is used only as a boolean; no namelet is read at the returned index. `Naming.containsNamelet` is the only `binarySearch` site in `ape.naming`. INV-NAME-13 is scoped by the member: with `APE_FIXES` in the plan, every `binarySearch` not-found test in `ape.naming` SHALL use `index < 0` (membership `index >= 0`); without it, `containsNamelet` SHALL reproduce upstream APE's test (absent iff the result equals `-1`); in every cell no code path SHALL index an array or list with a negative binary-search result.

#### Scenario: absent namelet with non-zero insertion point
- **WHEN** `Naming.select()` searches for a namelet that is absent and whose insertion point in the sorted array is greater than 0 (binarySearch returns ≤ -2), with `APE_FIXES` in the plan (the `as` cell)
- **THEN** the namelet SHALL be treated as absent
- **AND** no namelet SHALL be selected from the negative index

#### Scenario: present namelet unchanged
- **WHEN** the namelet exists in the array (binarySearch returns ≥ 0), in any of the six cells
- **THEN** `containsNamelet` SHALL return `true`
- **AND** `Naming.select()` SHALL continue to the namelet's parent

#### Scenario: absent namelet with non-zero insertion point reads as present without the member
- **WHEN** `Naming.select()` searches for a namelet that is absent with insertion point 2 (binarySearch returns -3), with `APE_FIXES` absent (the `base` or `mop` cell)
- **THEN** `containsNamelet` SHALL return `true`, as upstream APE's `== -1` test does
- **AND** `Naming.select()` SHALL continue to the namelet's parent instead of breaking

#### Scenario: absent namelet at insertion point 0 reads as absent in every cell
- **WHEN** `Naming.select()` searches for a namelet that is absent with insertion point 0 (binarySearch returns -1)
- **THEN** `containsNamelet` SHALL return `false` with and without `APE_FIXES`

---

### Requirement: Per-State GUITree Cap Enforcement

The two refinement guards in `NamingFactory` that implement the `ape.maxGUITreesPerState` contract (declared in this capability's Data Contracts, default 20) — one on the non-determinism resolution path, one on the state-abstraction path, each directly after the `maxStatesPerActivity` check — SHALL choose the tested count by `Feature.APE_FIXES`, read through `RunContext.current().spec().has(Feature.APE_FIXES)`:

- With the member (the four ADDONS cells), the guard SHALL test the state's own GUITree count: `state.getGUITrees().size() > maxGUITreesPerState`.
- Without the member (`base`, `mop`), the guard SHALL test upstream APE's count: `an.getStates().size() > maxGUITreesPerState`, where `an` is the state's `ActivityNode`, obtained at each site the way upstream APE obtains it there. Any activity whose state count exceeds `maxGUITreesPerState` has already returned on the preceding `maxStatesPerActivity` check whenever `maxStatesPerActivity <= maxGUITreesPerState` (the defaults 10 and 20), so in `base` and `mop` the per-state GUITree cap does not fire and states accumulate GUITrees as in upstream APE.

The threshold, the early return and the log line are the same in both cases: the guard logs `Already too many GUI trees %d in the state %s.` with `state.getGUITrees().size()`, as upstream APE does. INV-NAME-14 is scoped by the member: with `APE_FIXES` in the plan, no refinement-guard condition SHALL compare a quantity different from the one its threshold names and its log message reports; without it, these two guards compare `an.getStates().size()` while their log line reports `state.getGUITrees().size()`, as upstream APE does, and they are the one declared exception. The Data Contracts entry `ape.maxGUITreesPerState` describes the cap with the member.

#### Scenario: over-cap state suppresses refinement
- **WHEN** a state holds 21 GUITrees with `ape.maxGUITreesPerState=20`, its activity holds 3 states, and `APE_FIXES` is in the plan (the `as_mop` cell)
- **THEN** the refinement guard SHALL fire (refinement suppressed for that state)
- **AND** the log line SHALL report the state's GUITree count

#### Scenario: under-cap state refines normally
- **WHEN** a state holds 5 GUITrees and its activity is under `maxStatesPerActivity`, in any of the six cells
- **THEN** neither guard SHALL fire
- **AND** refinement SHALL proceed

#### Scenario: over-cap state is not capped without the member
- **WHEN** a state holds 21 GUITrees with `ape.maxGUITreesPerState=20`, its activity holds 3 states, and `APE_FIXES` is absent (the `base` or `mop` cell)
- **THEN** the guard SHALL test `an.getStates().size()` (3 > 20 is false) and SHALL NOT fire
- **AND** refinement SHALL proceed for that state, at both `NamingFactory` guard sites
