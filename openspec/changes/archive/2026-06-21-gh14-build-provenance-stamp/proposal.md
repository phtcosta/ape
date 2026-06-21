## Status — DISCARDED (2026-06-21)

This change was **discarded before implementation** and is archived for traceability. It is
superseded by **B-1 `build-ship-integrity`** (repo `PAMunb/rvsec`, build-from-source).

**Why discarded.** A-1 existed to expose a stale/legacy jar by stamping provenance and printing
an `[APE-BUILD]` banner at session start. The 2026-06-21 measurement over the June comparison run
(169 APKs) showed `[APE-RV] MOP boost` produced `maxBoost=0` in **147,153 of 147,153** evaluations:
MOP never fired. Root cause is purely the **stale shipped jar** (`c5d76943`, 2026-04-20 — pre-gh13),
which reads the legacy `reachesMop` key against gh60 `reachesTarget` JSONs. The correct parser is
**already in source** (HEAD `138a161`, gh13). B-1 fixes this by building the current source inside
the Docker image; once it does, a runtime provenance banner is **redundant** with B-1's build-time
provenance (pinned `APE_REF` + image label). Emitting it would be runtime cost with no unique value
(P1). The provenance check B-1 needed moves into the build itself.

No code was written; no spec was synced (archived with `--skip-specs`). Issue phtcosta/ape#14 closed
as not-planned. Backup of the artifacts lives in the archive.

## Why

The `ape-rv.jar` carries no build stamp — there is no `BuildConfig`, no `git.properties`, and no APE-RV version constant (only Android runtime versions appear in the dex). When a stale jar is deployed (for example, a legacy binary baked into a Docker image), nothing in the running session reveals which source the jar was built from. The build-skew that invalidated a full experiment stayed invisible until the dex was inspected by hand. APE-RV needs build provenance that is visible at session start.

Tracked as GitHub issue phtcosta/ape#14. Part of the 2026-06-21 APE-RV correction plan (`docs/20260621_plano_correcao_aperv_e_modulos_relacionados.md`, §3 A-1). This change provides the provenance marker consumed by `build-ship-integrity` (B-1, repo `PAMunb/rvsec`).

## What Changes

- The build embeds the `git sha` and build `timestamp` into the jar as a **generated Java constant** (`BuildInfo`), produced by Maven templating from the working-tree git state. A `.properties` resource is not viable: `d8` dexes only `.class` entries, so a bundled resource is dropped from `ape-rv.jar` and would read back as `null` on device (consistent with INV-BUILD-01 and INV-BUILD-06).
- The agent emits one `[APE-BUILD]` banner at session start carrying `git_sha`, `jar_built`, `mopDataPath`, `mopLoaded`, and `mopWidgetCount`. It is emitted from the `StatefulAgent` constructor immediately after `MopData.load` (so MOP load state is known) and before any MOP-scoring log line.
- A new build invariant (`INV-BUILD-09`) asserts the generated constant is present in the packaged dex and reflects the source git state.

No exploration behavior changes. No MOP scoring changes.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `build`: adds a requirement that the build embeds git sha and build timestamp into the jar as a dex-surviving Java constant, and that the agent emits an `[APE-BUILD]` provenance banner at session start. Adds `INV-BUILD-09`.

## Impact

- **Build (`pom.xml`)**: new Maven plugin(s) to capture git sha/timestamp and template a generated `BuildInfo.java` source onto the compile path. Must not introduce any artifact that violates existing INV-BUILD-03/04/06 (no vendored JARs, no `.java`/resources in the output dex).
- **Runtime (`ape.agent`)**: the `StatefulAgent` constructor emits the banner after `MopData.load`. Reuses the existing widget-count helper used by `MopData`'s own load log; reads `mopDataPath` from `Config` and MOP load state from the loaded `MopData`.
- **Downstream**: `build-ship-integrity` (phtcosta/ape upstream consumer in `PAMunb/rvsec`) reads the `[APE-BUILD]` banner to assert the shipped jar matches the intended `APE_REF`.
- **Validation**: JUnit for the banner/constant where it runs off-device; device check that `[APE-BUILD]` precedes any MOP-scoring log. No `rv-*` skill layer in this repo.
