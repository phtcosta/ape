## ADDED Requirements

### Requirement: Per-State GUITree Cap Enforcement

The refinement guards in `NamingFactory` that implement the `ape.maxGUITreesPerState` contract (declared in this capability's Data Contracts, default 20) SHALL test the state's own GUITree count (`state.getGUITrees().size() > maxGUITreesPerState`). Both guard sites previously tested `an.getStates().size()` — a copy-paste of the `maxStatesPerActivity` check on the preceding line — so the per-state GUITree cap never engaged: the condition was unreachable (any activity exceeding it had already returned on the previous check), states accumulated GUITrees without bound (a known OOM contributor), and the guards' own log messages ("Already too many GUI trees", printing `state.getGUITrees().size()`) described a check that was not being made.

#### Scenario: over-cap state suppresses refinement
- **WHEN** a state holds 21 GUITrees with `ape.maxGUITreesPerState=20`
- **THEN** the refinement guard SHALL fire (refinement suppressed for that state)
- **AND** the log line SHALL report the state's GUITree count

#### Scenario: under-cap state refines normally
- **WHEN** a state holds 5 GUITrees and its activity is under `maxStatesPerActivity`
- **THEN** refinement SHALL proceed as before

## Invariants

- **INV-NAME-14**: No refinement-guard condition SHALL compare a quantity different from the one its threshold names and its log message reports.
