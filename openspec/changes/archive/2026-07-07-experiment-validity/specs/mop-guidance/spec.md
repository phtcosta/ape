## ADDED Requirements

### Requirement: MopData — Load Status Line and Fail-Fast

`MopData.load` SHALL emit exactly one `[APE-MOP-DATA]` status line per invocation, on both outcomes:

- success: `[APE-MOP-DATA] status=loaded package=<pkg> windows=<n> widgets=<n> flagged=<n> droppedNoId=<n> transitions=<n>`
- failure: `[APE-MOP-DATA] status=rejected reason=<file-missing|parse-error|incomplete|package-mismatch|...>`

The line goes to the standard `Logger` output (the `.trace` stream). It SHALL NOT be written to logcat: the rv-platform logcat parser owns that channel and foreign lines are forbidden.

When `Config.mopDataPath` is set and `MopData.load` returns `null`, `StatefulAgent` SHALL abort the run (throw `StopTestingException`) instead of continuing as pure SATA. An operator who sets `ape.mopDataPath` has declared the run a MOP-arm run; silently executing it as `sata` mislabels the arm — the failure class that invalidated the earlier build-skew experiment round. When `Config.mopDataPath` is unset, behavior is unchanged (MOP scoring disabled, no status line required beyond the absence of a load).

#### Scenario: successful load emits counters
- **WHEN** `MopData.load` parses a complete JSON with 5 windows, 51 widgets, 12 flagged, 3 dropped for missing ids, and 35 transitions
- **THEN** one `[APE-MOP-DATA] status=loaded ...` line SHALL be emitted carrying those counters

#### Scenario: rejected load names the reason
- **WHEN** the JSON at `mopDataPath` lacks `complete=true`
- **THEN** one `[APE-MOP-DATA] status=rejected reason=incomplete` line SHALL be emitted
- **AND** `load` SHALL return `null`

#### Scenario: fail-fast when the MOP arm cannot arm
- **WHEN** `ape.mopDataPath` is set and `MopData.load` returns `null`
- **THEN** `StatefulAgent` SHALL throw `StopTestingException` during setup
- **AND** the run SHALL NOT proceed as pure SATA

#### Scenario: unset path keeps SATA behavior
- **WHEN** `ape.mopDataPath` is not set
- **THEN** `_mopData` SHALL be `null` and exploration SHALL proceed with MOP scoring disabled, as today

## MODIFIED Requirements

### Requirement: MopData — Package / MainActivity Sanity Check

`MopData.load(String path, String expectedPackage, String expectedMainActivity)` SHALL compare the parsed `package` / `mainActivity` against the optional expected values. When `expectedPackage` non-null and not equal to the parsed value, OR `expectedMainActivity` non-null and not equal to the parsed value, a `WARN` log line naming both pairs SHALL be emitted. When `Config.mopStrictPackageMatch=true` and either comparison mismatches, `MopData.load` SHALL return `null`. Default behavior (`mopStrictPackageMatch=false`) is warn-only — the parsed `MopData` is still returned.

The production call site (`StatefulAgent` constructor) SHALL pass the runtime package name and main activity of the app under test, making the sanity check reachable in production. The single-argument `MopData.load(path)` overload is deleted (P3): it delegated `load(path, null, null)`, which silently bypassed the check, and it has no remaining callers.

#### Scenario: Default warn-only on mismatch
- **WHEN** `Config.mopStrictPackageMatch=false` AND `MopData.load(path, "x.y.z.OTHER", null)` is called on a JSON with `package="x.y.z"`
- **THEN** the returned `MopData` SHALL be non-null
- **AND** a `WARN` log line SHALL be emitted

#### Scenario: Strict-mode rejection on mismatch
- **WHEN** `Config.mopStrictPackageMatch=true` AND `MopData.load(path, "x.y.z.OTHER", null)` is called on a JSON with `package="x.y.z"`
- **THEN** the returned `MopData` SHALL be `null`
- **AND** a `WARN` log line SHALL be emitted

#### Scenario: Null expected values bypass the check
- **WHEN** `MopData.load(path, null, null)` is called
- **THEN** no sanity-check WARN log SHALL be emitted (regardless of `mopStrictPackageMatch`)
- **AND** the parsed `MopData` SHALL be returned

#### Scenario: Production call site passes runtime identity
- **WHEN** `StatefulAgent` loads MOP data for an app whose runtime package is `br.unb.cic.cryptoapp`
- **THEN** it SHALL call `MopData.load(Config.mopDataPath, "br.unb.cic.cryptoapp", <mainActivity>)`
- **AND** a JSON produced for a different APK SHALL trigger the sanity-check WARN (and rejection under `mopStrictPackageMatch=true`)

## Invariants

- **INV-MOP-21**: Every `MopData.load` invocation SHALL emit exactly one `[APE-MOP-DATA]` status line, never to logcat.
- **INV-MOP-22**: A run with `ape.mopDataPath` set SHALL either have non-null `_mopData` or abort; it SHALL never run as pure SATA.
