## ADDED Requirements

### Requirement: Namelet Binary-Search Not-Found Contract

`Naming.select()` locates a `Namelet` in the sorted namelet array via `Collections.binarySearch`. A negative return value SHALL be treated as "absent" regardless of its magnitude: `binarySearch` returns `-(insertionPoint) - 1`, so a missing element whose insertion point is not 0 yields a value below `-1`. The not-found test SHALL be `index < 0`, never `index == -1`. (Previously the `== -1` test let a missing namelet with insertion point ≠ 0 pass as "present", selecting a namelet whose refinement chain does not apply — a silently wrong abstraction, not a crash.)

#### Scenario: absent namelet with non-zero insertion point
- **WHEN** `Naming.select()` searches for a namelet that is absent and whose insertion point in the sorted array is greater than 0 (binarySearch returns ≤ -2)
- **THEN** the namelet SHALL be treated as absent
- **AND** no namelet SHALL be selected from the negative index

#### Scenario: present namelet unchanged
- **WHEN** the namelet exists in the array (binarySearch returns ≥ 0)
- **THEN** selection behavior SHALL be identical to the previous implementation

## Invariants

- **INV-NAME-13**: Every `binarySearch` not-found test in `ape.naming` SHALL use `index < 0`; no code path SHALL index an array or list with a negative binary-search result.
