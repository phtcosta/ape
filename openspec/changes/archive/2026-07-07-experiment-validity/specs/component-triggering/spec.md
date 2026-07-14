## ADDED Requirements

### Requirement: ComponentName Package Derivation

`StatefulAgent.dispatchTrigger` SHALL build the `ComponentName` for every trigger kind (broadcast, service, activity) using the app package parsed from the static-analysis JSON (`MopData.getPackageName()`, sourced from the JSON `package` field), paired with the component's fully-qualified class name. It SHALL NOT derive the package by truncating the class name at its last dot: that heuristic yields the enclosing Java namespace, which differs from the app package for any component declared in a subpackage (e.g. class `br.unb.app.receivers.MyReceiver` in app package `br.unb.app`), producing an invalid `ComponentName` and a silently failed trigger.

#### Scenario: subpackaged receiver gets a valid ComponentName
- **WHEN** the JSON declares `package="br.unb.app"` and a reachable receiver class `br.unb.app.receivers.MyReceiver` is triggered
- **THEN** the `ComponentName` SHALL be `("br.unb.app", "br.unb.app.receivers.MyReceiver")`

#### Scenario: top-level component unchanged
- **WHEN** the component class lives directly in the app package (`br.unb.app.MainReceiver`)
- **THEN** the `ComponentName` SHALL be `("br.unb.app", "br.unb.app.MainReceiver")` (same result as before)

## Invariants

- **INV-CT-04**: The package component of every trigger `ComponentName` SHALL equal `MopData.getPackageName()`; no trigger path SHALL derive it from the component class name.
