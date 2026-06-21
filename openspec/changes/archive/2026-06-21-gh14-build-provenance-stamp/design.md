## Context

APE-RV ships as a single Dalvik JAR (`target/ape-rv.jar`) built by `mvn package`: `maven-compiler-plugin` produces `.class` files, `maven-jar-plugin` (prepare-package) bundles them into `ape-rv-classes.jar`, and `exec-maven-plugin` runs `d8` (package) to produce `ape-rv.jar` containing only `classes.dex` (`pom.xml:111-154`). Nothing in that dex identifies the source revision. This change adds a build-time provenance stamp and a session-start banner, per proposal `gh14-build-provenance-stamp` and issue phtcosta/ape#14.

Two hard constraints shape the design. First, the stamp must be a compiled constant, not a packaged resource: `d8` dexes only `.class` entries, so a `.properties` resource bundled into `ape-rv-classes.jar` is absent from `ape-rv.jar` and reads back `null` on device (INV-BUILD-01, INV-BUILD-06). Second, the banner must be emitted where MOP load state is already known — the `StatefulAgent` constructor assigns `this._mopData = MopData.load(Config.mopDataPath)` at `StatefulAgent.java:162` — and before any MOP-scoring log line (`[APE-RV] MOP boost`, `StatefulAgent.java:1372`).

## Architecture

```
mvn package
  ├─ git-commit-id-maven-plugin (initialize)   .git → ${git.commit.id.abbrev}, ${git.build.time}
  ├─ templating-maven-plugin   (generate-sources)
  │     src/main/java-templates/.../BuildInfo.java  ──filter──▶  target/generated-sources/.../BuildInfo.java
  │        (${git.commit.id.abbrev}, ${git.build.time}, ${aperv.mop.schema})
  ├─ maven-compiler-plugin     BuildInfo.java + sources → target/classes/**.class
  ├─ maven-jar-plugin          target/classes → ape-rv-classes.jar
  └─ exec-maven-plugin (d8)    ape-rv-classes.jar → ape-rv.jar (classes.dex, incl. BuildInfo)

runtime: StatefulAgent(ctor)
  this._mopData = MopData.load(...)         // :162
  logBuildBanner(_mopData)                  // [APE-BUILD] ... (new, after :162, before scoring)
```

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `git-commit-id-maven-plugin` | Capture short sha + build time from `.git` into Maven properties | `.git` | `${git.commit.id.abbrev}`, `${git.build.time}` |
| `templating-maven-plugin` | Filter `BuildInfo.java` template into generated-sources | template + properties | `BuildInfo.java` |
| `ape.utils.BuildInfo` | Hold provenance constants as compiled bytecode | (none) | `GIT_SHA`, `JAR_BUILT`, `SCHEMA` |
| `MopData.getWidgetCount()` | Expose widget count for the banner (reuses `countWidgets`) | `this.widgetData` | `int` |
| `StatefulAgent.logBuildBanner()` | Emit the single `[APE-BUILD]` line at session start | `BuildInfo`, `Config.mopDataPath`, `_mopData` | log line |

## Mapping: Spec -> Implementation -> Test

| Requirement / Invariant | Implementation | Test |
|-------------------------|---------------|------|
| Build Provenance Stamp / INV-BUILD-09 | `pom.xml` (git-commit-id + templating plugins, `${aperv.mop.schema}`), `src/main/java-templates/.../BuildInfo.java` | `BuildInfoTest` (constants non-null, `SCHEMA` equals declared) + `unzip -p ape-rv.jar classes.dex \| strings \| grep <sha>` device/CI check |
| Stamp not a resource / INV-BUILD-06 preserved | constant compiled to `.class`; no resource added | `unzip -l ape-rv.jar` lists no provenance `.properties`, no `.java` |
| Session Provenance Banner / INV-BUILD-10 | `StatefulAgent.logBuildBanner()` called after `:162`, before `:1372` | Device/manual: `[APE-BUILD]` precedes `[APE-RV] MOP boost` in trace |
| Banner fields / INV-BUILD-11 | banner format string; `MopData.getWidgetCount()` | `MopDataTest.getWidgetCount` on a loaded fixture; device check of field set |

## Goals / Non-Goals

**Goals:**
- A dex-surviving provenance constant carrying git sha, build timestamp, schema version.
- One `[APE-BUILD]` banner at session start, before any scoring log, reporting stamp + MOP load state.
- The B-1 freshness gate can read the banner to detect a skewed jar.

**Non-Goals:**
- Reproducible builds (the timestamp is intentionally build-time, not source-deterministic).
- Changing exploration, scoring, or `MopData` parsing behavior.
- Coupling to `gh13-mopdata-schema-v2`; the schema constant is declared independently.
- Merging the existing `MopData: loaded …` log into the banner (keeps the two concerns separate — P1).

## Decisions

**D1 — Generated Java constant, not a resource.** `d8` dexes only `.class` entries (INV-BUILD-01/06), so a `build-info.properties` resource never reaches the device and `getResourceAsStream` returns `null`. A compiled constant survives `javac` → `d8`. Alternative (resource + `getResourceAsStream`) rejected: provably `null` on device.

**D2 — `git-commit-id-maven-plugin` + `templating-maven-plugin`.** `git-commit-id-maven-plugin` reads `.git` directly (no SCM URL, offline-friendly) and exposes `${git.commit.id.abbrev}` and `${git.build.time}`; `templating-maven-plugin` filters a template source into `target/generated-sources/java-templates` (auto-added to compile roots) at `generate-sources`, before `compile`. Alternative `buildnumber-maven-plugin` rejected: needs `<scm>` configuration and is heavier for the same output. Manual `git rev-parse` via `exec` rejected: more wiring, no property model. `failOnNoGitDirectory=false` makes builds outside a checkout fall back to a sentinel rather than fail.

**D3 — Schema version as a single POM property.** `<aperv.mop.schema>` in `pom.xml` `<properties>`, filtered into `BuildInfo.SCHEMA`. One declared place, independent of `gh13`. The value names the JSON-MOP static-analysis schema this jar consumes (e.g. `gh60-target`).

**D4 — Banner in the `StatefulAgent` constructor after `:162`.** `mopLoaded`/`mopWidgetCount` are only knowable after `MopData.load`. Emitting there satisfies "before any MOP-scoring log" because scoring logs come later in the agent loop (`:1372`). `mopWidgetCount` reuses the existing `countWidgets` helper via a new public `MopData.getWidgetCount()`.

**D5 — Single-line `Logger.iprintln` banner.** Same logging idiom as the existing `MopData: loaded …` line, so the banner lands in the same trace stream.

## API Design

### `ape.utils.BuildInfo` (generated)

```java
public final class BuildInfo {
    public static final String GIT_SHA  = "${git.commit.id.abbrev}";  // or "unknown"
    public static final String JAR_BUILT = "${git.build.time}";        // ISO-8601
    public static final String SCHEMA   = "${aperv.mop.schema}";
    private BuildInfo() {}
}
```

Postcondition: all three constants are non-null after build. `GIT_SHA` equals `HEAD`'s short hash in a checkout, or the sentinel `unknown` otherwise.

### `MopData.getWidgetCount(): int`

Precondition: instance constructed (loaded or `forTest`). Postcondition: returns `countWidgets(this.widgetData)` (0 for an empty map). No I/O, no failure mode.

### `StatefulAgent.logBuildBanner(MopData mop)` (private)

Emits exactly one line:
```
[APE-BUILD] git_sha=<GIT_SHA> jar_built=<JAR_BUILT> schema=<SCHEMA> mopDataPath=<Config.mopDataPath> mopLoaded=<mop != null> mopWidgetCount=<mop != null ? mop.getWidgetCount() : 0>
```
Called once from the constructor immediately after `:162`. No exceptions.

## Data Flow

Build: `.git` → git-commit-id properties → template filter → `BuildInfo.java` → `.class` → `classes.dex`. Runtime: `BuildInfo` constants + `Config.mopDataPath` + `_mopData` → `logBuildBanner` → agent log → (downstream) B-1 freshness gate parses `[APE-BUILD]`.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| No `.git` directory | build outside a checkout | `failOnNoGitDirectory=false`; sentinel `unknown` | banner reports `git_sha=unknown` |
| `d8` not on PATH | build env | existing build error (INV-BUILD pre-existing) | install build-tools 28+ |
| `_mopData == null` | MOP disabled / load failed | banner emits `mopLoaded=false mopWidgetCount=0` | none needed (expected path) |

## Risks / Trade-offs

- **Non-reproducible timestamp** → acceptable: the goal is provenance/skew-detection, not bit-reproducible builds. Pin `dateFormatTimeZone=UTC` for stable formatting.
- **New build plugins increase build surface** → both are standard, widely used Maven plugins; they only add a generated source and properties, touching no existing INV-BUILD path. Must verify `unzip -l ape-rv.jar` still satisfies INV-BUILD-06 (no `.java`/resources).
- **Banner on every session adds one log line** → negligible; single `iprintln`, no toggle needed (P1).
- **`getWidgetCount()` widens `MopData` API** → minimal: one accessor reusing the existing private `countWidgets`.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit | `BuildInfo` constants non-null; `SCHEMA` equals declared value; `MopData.getWidgetCount()` on a fixture | JUnit on JVM (`mvn test`) | ~3 |
| Build | provenance constant present in dex; no provenance resource; INV-BUILD-06 holds | `unzip`/`strings` over `ape-rv.jar` after `mvn package` | ~2 checks |
| Device | `[APE-BUILD]` emitted once, before `[APE-RV] MOP boost`, with all six fields | manual device run (no automated Android suite in this repo) | 1 |

## Open Questions

- Exact `SCHEMA` literal value to declare (e.g. `gh60-target`) — to confirm at apply time against the JSON the parser currently consumes. Does not affect the design.
