# Change Plan: Phase 1 — Build Migration (Maven + d8 + Java 11)

**Date**: 2026-03-11
**Track**: Quick Path
**Priority**: High
**Issue**: phtcosta/ape#1
**PRD Reference**: FR01 (Maven build), FR02 (Java 11 compatibility), FR03 (d8 + ape-rv.jar output)
**Domains**: Build infrastructure, documentation

## 1. Context

The current build system (Apache Ant + `dx` + Java 1.7) is broken on modern Android SDK
toolchains. Android build-tools 35+ removed `dx`; Java 25 rejects `--source 1.7 /
--target 1.7` javac flags. As a workaround, the repository ships a pre-compiled `ape.jar`,
but this means the source cannot be rebuilt by anyone with a current SDK.

The fix replaces Ant with Maven and `dx` with `d8` (the modern DEX compiler, available in
build-tools 28+). Java source/target compatibility is raised from 1.7 to 11. The output
artifact is renamed `ape-rv.jar` to distinguish the enhanced fork from the original APE.

Two compile-time JARs must be kept as-is:
- `framework/classes-full-debug.jar` — exposes `@hide` Android APIs (`ApeAPIAdapter.java`
  imports `com.android.internal.*`, `android.app.UiAutomationConnection`, etc.) absent from
  the public `android.jar`
- `dalvik_stub/classes.jar` — Dalvik-specific stubs required at compile time

Both JARs must appear on the `javac` classpath but MUST NOT be bundled into `ape-rv.jar`
(the real implementations live in the Android runtime on-device).

Design decisions are fully resolved in `docs/plans/20260311_pre_plan.md`. This is a
mechanical implementation task.

## 2. Scope

**Group A — Maven build file** (new `pom.xml`): core deliverable; all other groups depend on it compiling successfully.

**Group A2 — Migrate source directory**: move `src/com/` → `src/main/java/com/` (standard Maven layout). Required for `pom.xml` to work without a `<sourceDirectory>` override; improves IDE support and Maven plugin compatibility. Depends on Group A (pom.xml must use standard layout after move).

**Group B — Deprecate legacy build** (`build.xml`): add notice; no functional changes.

**Group C — Documentation** (`CLAUDE.md`, `openspec/config.yaml`, `docs/PRD.md`): update
build commands, metadata, and PRD status. Independent of A and B; can run in parallel.

**Group D — Delta spec** (`openspec/changes/phase1-build-migration/` + `openspec/specs/build/`):
write the new `build/spec.md` describing the Maven build. Depends on A (must verify actual
behavior before spec is written). Synced to main specs on `/opsx:archive`.

## 3. File Inventory

| File | Action | Detail |
|------|--------|--------|
| `pom.xml` | **Create** | Maven POM: `javac --release 11`, `exec-maven-plugin` invokes `d8`, output `ape-rv.jar` in `target/`. Standard `src/main/java/` layout — no `<sourceDirectory>` override. See §3.1. |
| `src/com/` → `src/main/java/com/` | **Move** (git mv) | Migrate 140 Java source files to standard Maven directory layout. `src/` remains as parent but is otherwise empty. |
| `build.xml` | **Edit** — top of file | Insert XML comment deprecation notice before `<project>` tag |
| `CLAUDE.md` | **Edit** — Build Commands section | Replace `ant compile/assemble/clean` with `mvn compile/package/clean`; update JAR name `ape.jar` → `ape-rv.jar` |
| `openspec/config.yaml` | **Edit** — `context` block | Update `Stack` line: `Java 7+ → Java 11`; `Build` line: `ant … → mvn package → ape-rv.jar` |
| `docs/PRD.md` | **Edit** — Phase 1 status | Update FR01, FR02, FR03 status from NOT STARTED to IMPLEMENTED |
| `openspec/changes/phase1-build-migration/specs/build/spec.md` | **Create** | Delta spec: Maven build, INV-BUILD-01 through INV-BUILD-07 updated for new system |

### 3.1 pom.xml Design

```xml
<groupId>com.android.commands.monkey</groupId>
<artifactId>ape-rv</artifactId>
<version>1.0.0-SNAPSHOT</version>
<packaging>jar</packaging>

<!-- Java 11 -->
<maven.compiler.release>11</maven.compiler.release>

<!-- Compile-time only JARs (system scope — local paths) -->
<dependency>framework/classes-full-debug.jar — scope: system, provided>
<dependency>dalvik_stub/classes.jar — scope: system, provided>

<!-- exec-maven-plugin: invoke d8 after compile phase -->
<plugin>exec-maven-plugin
  <phase>package</phase>
  <goal>exec</goal>
  <executable>d8</executable>
  <arguments>
    --output target/ape-rv.jar
    --min-api 23
    target/classes/**/*.class   (via glob or file list)
  </arguments>
</plugin>

<!-- Skip default jar plugin (we produce ape-rv.jar via d8, not jar:jar) -->
<plugin>maven-jar-plugin — skip=true</plugin>
```

Key constraints:
- `system` scope JARs require absolute `<systemPath>` — use `${project.basedir}/framework/classes-full-debug.jar`
- `d8` must be on PATH (Android SDK build-tools); document in CLAUDE.md prerequisites
- `--min-api 23` (Android 6.0 Marshmallow — minimum supported by APE per CLAUDE.md)
- The `exec-maven-plugin` must list `.class` files explicitly or via a directory — d8 does not accept a JAR of `.class` files; it needs the `.class` files directly or a list

Alternative approach if d8 glob doesn't work: collect `.class` files using `find` via a shell exec, or use `maven-antrun-plugin` to invoke d8 with `<fileset>`.

## 4. Execution Order

```
Group A (pom.xml) ──→ Group A2 (src/main/java/) ──→ Group D (delta spec)
Group B (build.xml deprecation) ─ independent ──→ (parallel with A/A2)
Group C (docs) ──────────────────────────────────→ (parallel with A/A2/B)
```

Group A2 depends on Group A (pom.xml must be created first, then remove `<sourceDirectory>` override after move). Group D must run after A2 (`mvn clean package` must succeed with standard layout before spec invariants can be verified). Groups B and C are independent.

No subagent dispatch needed — total files: 7 (+140 moved), single repository, sequential execution is sufficient.

## 5. Acceptance Criteria

### Build

- [ ] `mvn clean package` exits with code 0 (Java 11 + d8 on PATH)
- [ ] `target/ape-rv.jar` exists after `mvn package`
- [ ] `unzip -l target/ape-rv.jar` lists `classes.dex`
- [ ] `unzip -p target/ape-rv.jar classes.dex | file -` returns output containing `"Dalvik dex file"`
- [ ] `unzip -l target/ape-rv.jar | grep -E "framework|dalvik_stub"` returns empty (vendored JARs not bundled)
- [ ] `unzip -l target/ape-rv.jar | grep ".java"` returns empty (no source files bundled)
- [ ] `mvn clean` removes `target/` directory

### Source directory migration

- [ ] `src/main/java/com/android/commands/monkey/` exists with all 140 `.java` files
- [ ] `src/com/` no longer exists
- [ ] `pom.xml` does NOT contain `<sourceDirectory>` override

### Legacy build

- [ ] `build.xml` still present with deprecation notice at top

### Documentation

- [ ] `CLAUDE.md` Build Commands section shows `mvn compile`, `mvn package`, `mvn clean`
- [ ] `openspec/config.yaml` Stack/Build lines reference Java 11 and `mvn package → ape-rv.jar`
- [ ] `docs/PRD.md` FR01–FR03 marked as implemented

### Delta spec

- [ ] `openspec/changes/phase1-build-migration/specs/build/spec.md` exists and documents Maven build with updated invariants

### Device validation (standalone, optional — requires AVD `@RVSec`)

- [ ] `scripts/run_emulator.sh` starts emulator `@RVSec` successfully
- [ ] `adb push target/ape-rv.jar /data/local/tmp/` succeeds
- [ ] `adb install test-apks/cryptoapp.apk` succeeds
- [ ] `adb shell CLASSPATH=/data/local/tmp/ape-rv.jar app_process /system/bin com.android.commands.monkey.Monkey -p br.unb.cic.cryptoapp --running-minutes 1 --ape sata` runs without `ClassNotFoundException`

Test APK: `test-apks/cryptoapp.apk` (instrumented, from rv-android `cli_experiment_20260305_155802_9bd8c909`).
Static analysis JSON for Phase 3: `test-apks/cryptoapp.apk.json` (`ape.mopDataPath`).
