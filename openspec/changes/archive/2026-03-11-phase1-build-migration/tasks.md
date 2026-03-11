## 1. Create pom.xml (Group A — core deliverable)

- [x] 1.1 Confirm `d8` is on PATH: `d8 --version` (requires Android SDK build-tools 28+)
- [x] 1.2 Create `pom.xml` with: `maven-compiler-plugin:3.11.0` with `<release>11</release>`, `framework/classes-full-debug.jar` and `dalvik_stub/classes.jar` as `system`-scoped dependencies, `maven-jar-plugin` bound to `prepare-package` outputting `ape-rv-classes.jar`, `exec-maven-plugin` bound to `package` invoking `d8 --output target/ape-rv.jar --min-api 23 target/ape-rv-classes.jar`
- [x] 1.3 Run `mvn clean package` — must exit code 0
- [x] 1.4 Verify `unzip -l target/ape-rv.jar` lists `classes.dex` and no `.java` files
- [x] 1.5 Verify `unzip -p target/ape-rv.jar classes.dex | file -` returns `"Dalvik dex file"`
- [x] 1.6 Verify `unzip -l target/ape-rv.jar | grep -E "framework|dalvik_stub"` returns empty
- [x] 1.7 Verify `mvn clean` removes `target/`

## 2. Migrate source directory to standard Maven layout (Group A2 — after Group 1)

- [x] 2.1 Run `git mv src/com src/main/java/com` — moves 140 Java files to `src/main/java/com/`
- [x] 2.2 Remove `<sourceDirectory>src</sourceDirectory>` override from `pom.xml` (Maven uses `src/main/java/` by default)
- [x] 2.3 Run `mvn clean package` — must still exit code 0 with standard layout
- [x] 2.4 Verify `unzip -p target/ape-rv.jar classes.dex | file -` still returns `"Dalvik dex file"`
- [x] 2.5 Verify `find src/com -type f 2>/dev/null | wc -l` returns 0 (old path gone)

## 3. Deprecate build.xml (Group B — independent)

- [x] 3.1 Insert XML comment deprecation notice at the top of `build.xml` (before `<project>`): `<!-- DEPRECATED: replaced by pom.xml (Maven + d8 + Java 11). See Phase 1 — phtcosta/ape#1. -->`

## 4. Documentation (Group C — independent)

- [x] 4.1 Update `CLAUDE.md` Build Commands section: replace `ant compile/assemble/clean` with `mvn compile`, `mvn package`, `mvn clean`; update prerequisites (Java 11+, Maven, d8 in PATH); update output artifact name `ape.jar` → `target/ape-rv.jar`
- [x] 4.2 Update `openspec/config.yaml` `context` block: `Stack` line `Java 7+` → `Java 11`; `Build` line `ant compile / ant assemble → ape.jar` → `mvn package → target/ape-rv.jar`
- [x] 4.3 Update `docs/PRD.md` Phase 1 rows: set FR01 (Maven build), FR02 (Java 11), FR03 (d8 + ape-rv.jar) status to implemented

## 5. Delta spec (Group D — after Group 2 succeeds)

- [x] 5.1 Create `openspec/changes/phase1-build-migration/specs/build/spec.md` — describe Maven build system: Purpose, Data Contracts (input: same Java sources in `src/main/java/` + vendored JARs; output: `target/ape-rv.jar` with `classes.dex`; tool: `d8`), updated Invariants INV-BUILD-01 through INV-BUILD-08 (INV-BUILD-05: Java 11 instead of 1.7; INV-BUILD-07: `mvn clean` removes `target/`; INV-BUILD-08: sources in `src/main/java/`), Requirements: `mvn package`, `mvn compile`, `mvn clean` scenarios

## 6. Verification

- [x] 6.1 Verify all acceptance criteria from `plan.md` §5 (build, source migration, legacy build, documentation, delta spec)
- [x] 6.2 **Standalone device test (optional — requires AVD `@RVSec`)**: `scripts/run_emulator.sh` → `adb push target/ape-rv.jar /data/local/tmp/` → `adb install test-apks/cryptoapp.apk` → `adb shell CLASSPATH=/data/local/tmp/ape-rv.jar app_process /system/bin com.android.commands.monkey.Monkey -p br.unb.cic.cryptoapp --running-minutes 1 --ape sata`
- [x] 6.3 Run `/opsx:sync phase1-build-migration` — sync delta `specs/build/spec.md` to `openspec/specs/build/spec.md`
