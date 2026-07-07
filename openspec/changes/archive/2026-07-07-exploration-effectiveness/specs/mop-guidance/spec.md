## ADDED Requirements

### Requirement: Typed-Input Widget Resolution via Containment

When `ApeAgent.generateInputText` resolves the static-analysis widget for an `EditText` (to read its `inputType`/`hint` for type-aware generation), the lookup SHALL apply the same parent/child containment-reconciliation policy used by the MOP scoring pass (±2-level ancestor/descendant id probe, as specified in "Parent/child widget granularity reconciliation"): the node's own short id is tried first, then the containment walk. Exact-id-only lookup misses whenever static analysis flags a container id while runtime resolves a child id (or vice versa) — the granularity mismatch that motivated containment in the scorer — so typed generation rarely activated and most inputs fell back to the legacy generator. When no widget resolves (or `inputType`/`hint` are empty), the existing fallback to the legacy generator is unchanged.

#### Scenario: child id resolves the container's typed metadata
- **WHEN** the static JSON flags widget `login_form` with `inputType="textEmailAddress"` and the runtime EditText resolves to child id `login_form_field`, one containment level below
- **THEN** `generateInputText` SHALL resolve the `login_form` widget via the containment walk
- **AND** the generated value SHALL be email-shaped

#### Scenario: no static widget still falls back
- **WHEN** neither the node's id nor its containment neighborhood matches a static widget
- **THEN** the legacy generator SHALL be used, as before

## Invariants

- **INV-MOP-23**: Static-widget resolution for typed input SHALL use the same containment policy as MOP boost resolution; the two paths SHALL NOT diverge in matching rules.
