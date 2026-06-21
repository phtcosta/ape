# Delta Specification: Build Infrastructure — Provenance Stamp

## Purpose

APE-RV is deployed as a single `ape-rv.jar` (a Dalvik JAR built by `mvn package` via `d8`) that is pushed to a device and launched with `app_process`. Once on the device, nothing in the running process identifies which source revision the jar was built from: the dex contains only Android runtime version strings, and there is no `BuildConfig`, `git.properties`, or APE-RV version constant. A stale jar — for example a legacy binary baked into a Docker image, or a working-tree jar that drifted from the committed one — runs indistinguishably from a fresh build. A build-skew of exactly this kind invalidated a full experiment and stayed invisible until the dex was disassembled by hand.

This capability adds **build provenance** to APE-RV. At build time the jar is stamped with the source `git sha`, a build `timestamp`, and a `schema` version (the JSON-MOP static-analysis schema the jar is written to consume). The stamp is a **generated Java constant**, not a packaged resource: `d8` converts only `.class` entries to `classes.dex` (INV-BUILD-01, INV-BUILD-06), so a `.properties` resource bundled into the intermediate jar is dropped from `ape-rv.jar` and `getResourceAsStream` returns `null` on device. A constant compiled to bytecode survives `javac` → `d8` and is readable at runtime.

At session start the agent emits a single `[APE-BUILD]` banner that prints the stamp together with the MOP load state for this run (`mopDataPath`, whether MOP data loaded, and the loaded widget count). The banner is the human- and machine-readable provenance marker: a legacy jar shows a stale `git_sha`/`schema`, exposing the skew automatically, and the `build-ship-integrity` consumer (B-1, repo `PAMunb/rvsec`) reads it to assert the shipped jar matches the intended build ref. The banner is emitted from the `StatefulAgent` constructor immediately after `MopData.load` — so MOP load state is known — and before any MOP-scoring log line (`[APE-RV] MOP boost`), so provenance always precedes the first scoring evidence in a trace.

This change adds new behavior to the build and to session startup; it does not modify any existing build requirement and does not change exploration or MOP scoring.

## Data Contracts

### Input
- `git sha: String` — short commit hash of the working tree at build time, captured by the Maven build (source: git, via build plugin).
- `build timestamp: String` — ISO-8601 instant of the `mvn package` run (source: Maven build).
- `schema version: String` — the JSON-MOP static-analysis schema version this jar consumes (source: a single declared constant in the build configuration / generated source).
- `Config.mopDataPath: String` — device path to the static-analysis JSON, or null when MOP scoring is disabled (source: `ape.properties`, read at runtime).
- `MopData` (loaded or null) — the result of `MopData.load(Config.mopDataPath)` (source: runtime load at `StatefulAgent` construction).

### Output
- `BuildInfo` constants: `GIT_SHA`, `JAR_BUILT`, `SCHEMA` — generated Java constants compiled into `classes.dex` (consumer: the `[APE-BUILD]` banner and any provenance reader).
- `[APE-BUILD]` banner: a single log line with `git_sha`, `jar_built`, `schema`, `mopDataPath`, `mopLoaded`, `mopWidgetCount` (consumer: experiment traces, the B-1 freshness gate).

### Side-Effects
- **Build**: a Maven plugin generates `BuildInfo.java` onto the compile source path before `mvn compile`; the constant is packaged into the intermediate jar and dexed into `ape-rv.jar`.
- **Runtime**: the `StatefulAgent` constructor writes the `[APE-BUILD]` line to the agent log once per session.

### Error
- `git sha unavailable` — the build runs outside a git checkout (e.g. an exported tarball). The build SHALL substitute a sentinel value (such as `unknown`) rather than fail the build; the banner then reports the sentinel.

## Invariants

- **INV-BUILD-09**: The packaged `ape-rv.jar` SHALL contain the generated provenance constant as Dalvik bytecode (not as a resource). The constant SHALL carry the build-time git sha, build timestamp, and schema version. Verification: the constant's class is present in `classes.dex` and its `GIT_SHA` value matches the source revision the jar was built from; a `.properties` resource SHALL NOT be relied upon (it would be absent from the dex per INV-BUILD-06).
- **INV-BUILD-10**: The `[APE-BUILD]` banner SHALL be emitted exactly once per session, from the `StatefulAgent` constructor after `MopData.load`, and SHALL precede any MOP-scoring log line (`[APE-RV] MOP boost`) in the session trace.
- **INV-BUILD-11**: The `[APE-BUILD]` banner SHALL include all six fields `git_sha`, `jar_built`, `schema`, `mopDataPath`, `mopLoaded`, `mopWidgetCount`. When MOP data is not loaded (`mopDataPath` null or load returns null), `mopLoaded` SHALL be `false` and `mopWidgetCount` SHALL be `0`.

## ADDED Requirements

### Requirement: Build Provenance Stamp

The build SHALL embed the source git sha, build timestamp, and JSON-MOP schema version into `ape-rv.jar` as a generated Java constant that survives `javac` → `d8` conversion. The stamp SHALL NOT be implemented as a packaged resource, because `d8` dexes only `.class` entries and a resource is dropped from the output jar (INV-BUILD-01, INV-BUILD-06). The schema version SHALL be a single declared constant in the build configuration, independent of any open schema-evolution change.

#### Scenario: Generated constant is present in the packaged dex

- **WHEN** `mvn clean package` is executed in a git checkout with `d8` on PATH
- **THEN** the command MUST exit with code 0
- **AND** the generated provenance class MUST be present inside `target/ape-rv.jar` `classes.dex`
- **AND** its `GIT_SHA` constant MUST equal the short hash of `HEAD`
- **AND** its `SCHEMA` constant MUST equal the declared schema version

#### Scenario: Build outside a git checkout falls back to a sentinel

- **WHEN** `mvn package` is executed where git metadata is unavailable
- **THEN** the build MUST still exit with code 0
- **AND** the generated `GIT_SHA` constant MUST be a sentinel value (such as `unknown`)

#### Scenario: Stamp does not introduce a resource into the dex

- **WHEN** `mvn package` completes
- **THEN** `unzip -l target/ape-rv.jar` MUST NOT list any `.properties` provenance resource
- **AND** `unzip -l target/ape-rv.jar | grep .java` MUST return empty (INV-BUILD-06 preserved)

---

### Requirement: Session Provenance Banner

At session start the agent SHALL emit a single `[APE-BUILD]` banner reporting the build stamp and the MOP load state for the run. The banner SHALL be emitted from the `StatefulAgent` constructor immediately after `MopData.load(Config.mopDataPath)` and before any MOP-scoring log line, so that provenance always precedes the first scoring evidence in a trace. The banner SHALL read the stamp from the generated constant, `mopDataPath` from `Config`, and `mopLoaded`/`mopWidgetCount` from the loaded `MopData` (reusing the widget-count helper used by `MopData`'s own load log).

#### Scenario: Banner emitted with MOP data loaded

- **WHEN** a `StatefulAgent` is constructed with `Config.mopDataPath` set to a JSON that loads successfully with N widgets
- **THEN** exactly one `[APE-BUILD]` line MUST be emitted before any `[APE-RV] MOP boost` line
- **AND** the line MUST contain `git_sha`, `jar_built`, `schema`, `mopDataPath`, `mopLoaded=true`, and `mopWidgetCount=N`

#### Scenario: Banner emitted when MOP scoring is disabled

- **WHEN** a `StatefulAgent` is constructed with `Config.mopDataPath` null
- **THEN** exactly one `[APE-BUILD]` line MUST be emitted
- **AND** the line MUST contain `mopLoaded=false` and `mopWidgetCount=0`

#### Scenario: Legacy jar exposes skew

- **WHEN** a jar built from an older revision runs a session
- **THEN** the `[APE-BUILD]` line MUST report that revision's `git_sha` and `schema`
- **AND** the reported values MUST differ from the intended build ref, making the skew visible without dex inspection
