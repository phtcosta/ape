## Purpose

The MOP scorer boosts widget actions that reach monitored operations. Three residual defects keep it from steering faithfully: (B4) the activity-level fallback is unreachable when a widget resolves but carries no MOP flag, because `score()` returns 0 first; (B3) static analysis may flag a parent container's resource id while the runtime clicks a child id, so the exact two-level lookup misses; (B6) the consumer emits `eventType` in camelCase while the producer emits snake_case, a latent mismatch masked today by the OR-aggregate fallback. This delta fixes the activity fallback ordering, adds parent/child reconciliation, and consumer-side `eventType` normalization.

## Invariants

- **INV-MOP-07**: The activity-level fallback (`+mopWeightActivity`) SHALL be reachable both when widget resolution returns `null` AND when it returns a widget whose MOP flags are all false. A resolved-but-unflagged widget SHALL NOT short-circuit to `0` before the activity check.
- **INV-MOP-08**: `eventType` comparison in the scorer SHALL be normalization-invariant: a producer `snake_case` token and the consumer `camelCase` token for the same event SHALL compare equal.

## MODIFIED Requirements

### Requirement: MopScorer — Priority Boost

`MopScorer.score(String activity, String shortId, MopData data)` SHALL return an integer priority boost according to the following scale. The boost is additive: it is added to the existing `ModelAction.priority`, never replacing it.

| Condition | Boost |
|-----------|-------|
| `data.getWidget(activity, shortId).directMop == true` | `+mopWeightDirect` (500) |
| `data.getWidget(activity, shortId).transitiveMop == true` (and not direct) | `+mopWeightTransitive` (300) |
| widget is `null` OR resolves with all MOP flags false, AND `data.activityHasMop(activity) == true` | `+mopWeightActivity` (100) |
| no widget MOP flag AND `data.activityHasMop(activity) == false` | `0` |

The activity-level fallback SHALL be evaluated **after** the widget flags and SHALL be reachable for a resolved-but-unflagged widget (B4 fix at `MopScorer.java:48` vs `:50-51`). The early `return 0` for a resolved-but-unflagged widget is removed.

#### Scenario: Direct MOP-reachable widget
- **WHEN** `data.getWidget(...)` returns flags `{directMop=true, transitiveMop=true}`
- **THEN** the returned boost SHALL be `mopWeightDirect` (500)

#### Scenario: Transitive only
- **WHEN** `data.getWidget(...)` returns flags `{directMop=false, transitiveMop=true}`
- **THEN** the returned boost SHALL be `mopWeightTransitive` (300)

#### Scenario: Resolved-but-unflagged widget falls back to activity
- **WHEN** `data.getWidget(activity, shortId)` returns a non-null widget with `directMop=false` AND `transitiveMop=false`
- **AND** `data.activityHasMop(activity)` returns `true`
- **THEN** the returned boost SHALL be `mopWeightActivity` (100)
- **AND** the scorer SHALL NOT return `0` before the activity check

#### Scenario: Widget null falls back to activity
- **WHEN** `data.getWidget(activity, shortId)` returns `null` AND `data.activityHasMop(activity)` returns `true`
- **THEN** the returned boost SHALL be `mopWeightActivity` (100)

#### Scenario: No match
- **WHEN** the widget carries no MOP flag AND the activity has no MOP association
- **THEN** the returned boost SHALL be `0`

## ADDED Requirements

### Requirement: Parent/child widget granularity reconciliation

When the exact widget lookup `widgetData.get(activity).get(shortId)` (`MopData.java:620-623`) returns no match, the scorer SHALL attempt to reconcile parent/child granularity using the GUI tree node: it SHALL try the resource ids of the node's ancestors and direct descendants (bounded depth ≤ 2) before declaring no-match. This addresses the case where static analysis flags a container id (e.g., `CardView`) while the runtime resolves a child id (e.g., the inner `LinearLayout`), or vice versa.

The reconciliation requires access to the GUI node, which `MopScorer.score()` does not currently receive. How the node reaches the lookup (a `score()` signature change versus a caller-side resolution in `StatefulAgent.java:1364`) is settled in design.md. The depth bound and a hit-rate log SHALL guard against over-boost (a root container marked by a single MOP child).

#### Scenario: Static flags parent, runtime resolves child
- **WHEN** the exact lookup for the clicked child id returns no match
- **AND** an ancestor within depth 2 has a MOP-flagged widget entry
- **THEN** the scorer SHALL use the ancestor's MOP flags for the boost

#### Scenario: Depth bound respected
- **WHEN** a MOP-flagged ancestor exists only at depth 3
- **THEN** the reconciliation SHALL NOT match it (bound is depth ≤ 2)
- **AND** the scorer SHALL fall through to the activity-level check or `0`

### Requirement: eventType normalization in the consumer

The scorer SHALL normalize `eventType` tokens to a single canonical form before comparison so that a producer `snake_case` token (e.g., `long_click`, `item_selected`) and the consumer `camelCase` token (e.g., `longClick`, `itemSelected`) for the same event compare equal (B6). Normalization SHALL be performed in the consumer (`MopData`/`MopScorer`); the producer and the gh13 JSON schema are NOT changed.

#### Scenario: snake_case producer token matches camelCase consumer token
- **WHEN** the JSON listener `eventType` is `long_click`
- **AND** the consumer compares it against `longClick`
- **THEN** the two SHALL compare equal after normalization
