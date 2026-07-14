## MODIFIED Requirements

### Requirement: MopScorer — Priority Boost

`MopScorer.score(String activity, String shortId, MopData data, String eventType)` SHALL return an integer priority boost according to the following scale. The boost is additive: it is added to the existing `ModelAction.priority`, never replacing it. (The `eventType` parameter selects the per-event-type MOP flag and is unchanged by this change.)

| Condition | Boost |
|-----------|-------|
| `data.getWidget(activity, shortId).directMop == true` | `+mopWeightDirect` (500) |
| `data.getWidget(activity, shortId).transitiveMop == true` (and not direct) | `+mopWeightTransitive` (300) |
| widget is `null` OR resolves with all MOP flags false | `0` |

The scorer SHALL NOT apply any activity-level fallback. A resolved-but-unflagged widget and a null widget — including on a MOP-bearing activity — both score `0`. This removes the prior `+mopWeightActivity` (100) fallback, which was applied uniformly to every target widget on a MOP-bearing activity and therefore could not re-rank candidates. The previously-required `INV-MOP-07` (which mandated that fallback) is obsolete and removed; `MopData.activityHasMop(activity)` remains as a predicate consumed by WTG scoring and `stateMopDensity`, but is no longer a branch of `MopScorer.score`.

#### Scenario: Direct MOP-reachable widget
- **WHEN** `data.getWidget(...)` returns flags `{directMop=true, transitiveMop=true}`
- **THEN** the returned boost SHALL be `mopWeightDirect` (500)

#### Scenario: Transitive only
- **WHEN** `data.getWidget(...)` returns flags `{directMop=false, transitiveMop=true}`
- **THEN** the returned boost SHALL be `mopWeightTransitive` (300)

#### Scenario: Resolved-but-unflagged widget scores zero
- **WHEN** `data.getWidget(activity, shortId)` returns a non-null widget with `directMop=false` AND `transitiveMop=false`
- **AND** `data.activityHasMop(activity)` returns `true`
- **THEN** the returned boost SHALL be `0`

#### Scenario: Widget null scores zero
- **WHEN** `data.getWidget(activity, shortId)` returns `null` AND `data.activityHasMop(activity)` returns `true`
- **THEN** the returned boost SHALL be `0`

#### Scenario: No match
- **WHEN** the widget carries no MOP flag AND the activity has no MOP association
- **THEN** the returned boost SHALL be `0`

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
- **AND** the scorer SHALL fall through to `0` (the activity-level fallback is removed by this change)

### Requirement: Config.mopDataPath Flag

`Config.java` SHALL declare `public static final String mopDataPath` loaded via `Config.get("ape.mopDataPath")`. The default value is `null`. When set (via `/data/local/tmp/ape.properties` or `/sdcard/ape.properties`), it points to the static analysis JSON file path on the device.

`Config.java` SHALL also declare the following MOP weight fields with defaults matching the MopScorer boost table:

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `ape.mopWeightDirect` | int | `500` | Boost for direct MOP-reachable widget |
| `ape.mopWeightTransitive` | int | `300` | Boost for transitive MOP-reachable widget |

These weights are configurable via `ape.properties` but the hardcoded defaults SHALL be 500/300. `Config.mopWeightActivity` SHALL NOT exist — the activity-level fallback it parameterised is removed (see "MopScorer — Priority Boost").

#### Scenario: Flag absent
- **WHEN** `ape.properties` does not contain `ape.mopDataPath`
- **THEN** `Config.mopDataPath` SHALL be `null`
- **AND** `StatefulAgent` SHALL initialise `_mopData` to `null`, disabling MOP scoring

#### Scenario: Flag set
- **WHEN** `ape.properties` contains `ape.mopDataPath=/data/local/tmp/static_analysis.json`
- **THEN** `Config.mopDataPath` SHALL equal `"/data/local/tmp/static_analysis.json"`

#### Scenario: Default MOP weights
- **WHEN** `ape.properties` does not contain any `ape.mopWeight*` keys
- **THEN** `Config.mopWeightDirect` SHALL equal `500`
- **AND** `Config.mopWeightTransitive` SHALL equal `300`

#### Scenario: Custom MOP weights override
- **WHEN** `ape.properties` contains `ape.mopWeightDirect=200`
- **THEN** `Config.mopWeightDirect` SHALL equal `200`
- **AND** `Config.mopWeightTransitive` SHALL retain its default (`300`)

## ADDED Requirements

### Requirement: MopScorer — MOP-Flagged State Density

`MopScorer.stateMopDensity(State, MopData, int timestamp)` (`MopScorer.java:101`) SHALL count only actions whose resolved widget is MOP-flagged: for each valid, target-requiring action, resolve the node's short id, look up the widget (same resolution as `score`), and count it when `isDirectMop(eventType)` or `isTransitiveMop(eventType)` holds. The `activityHasMop(activity)` gate remains as a cheap early-out (activities without MOP always return 0).

The method gains an `int timestamp` parameter (previously 2-arg `stateMopDensity(State, MopData)`); `int` matches the codebase-wide timestamp type (`ModelAction.isResolvedAt(int)`, `Agent.getTimestamp():int`) and is required for the per-action `getWidget` resolution. All five call sites (`SataAgent.java:707, 719, 957, 960, 969`) SHALL pass the current GUITree timestamp via `getTimestamp()`.

Previously the method gated on `activityHasMop` and then counted **every** valid targeted action — a widget-count proxy, not a MOP density. Its consumers (`SataAgent` ABA and trivial-activity navigation tiebreakers, `SataAgent.java:702,714,952-964`) therefore steered exploration toward widget-dense screens inside MOP activities rather than toward the screens actually carrying MOP-flagged widgets, diluting the navigation signal the mechanism exists to provide.

#### Scenario: MOP-flagged widgets counted, unflagged ignored
- **WHEN** a state in a MOP activity has 10 valid targeted actions of which 2 resolve to MOP-flagged widgets
- **THEN** `stateMopDensity` SHALL return 2

#### Scenario: dense non-MOP screen scores below sparse MOP screen
- **WHEN** state A has 12 valid actions, none MOP-flagged, and state B has 3 valid actions, one MOP-flagged, both in MOP activities
- **THEN** `stateMopDensity(A) == 0` and `stateMopDensity(B) == 1` (navigation tiebreak prefers B)

#### Scenario: non-MOP activity unchanged
- **WHEN** the state's activity has no MOP-reachable methods
- **THEN** `stateMopDensity` SHALL return 0 without resolving any widget

## Invariants (added)

- **INV-MOP-24**: `stateMopDensity` SHALL count only MOP-flagged resolved widgets; it SHALL never reduce to a total action count.
