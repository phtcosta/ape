# Specification: Build Infrastructure

> **Status: Current (Maven + d8 + Java 11).** Replaces the Ant+`dx`+Java 1.7 build system
> documented prior to Phase 1 (phtcosta/ape#1). The legacy `build.xml` is retained with a
> deprecation notice.

## Purpose

APE-RV is a model-based Android GUI testing tool that runs on Android devices as a Dalvik
process. Its 140 Java source files are compiled to JVM bytecode and then converted to Dalvik
bytecode (`.dex`) so the resulting JAR can be deployed to an Android device via ADB and
launched with `app_process`.

The build system is Apache Maven (`pom.xml`). The `mvn compile` phase compiles all Java source
files under `src/main/java/` to `.class` files in `target/classes/`, using Java 11 release
compatibility. The `mvn package` phase first packages the compiled classes into an intermediate
JAR (`target/ape-rv-classes.jar`) via `maven-jar-plugin`, then invokes `d8` to convert that
intermediate JAR to Dalvik bytecode, producing `target/ape-rv.jar` containing `classes.dex`.

Two vendored JARs are required at compile time and must not be bundled into the output:
`framework/classes-full-debug.jar` exposes `@hide`-annotated Android APIs (such as
`com.android.internal.view.IInputMethodManager`, `android.app.ActivityManagerNative`,
`android.app.UiAutomationConnection`) that `ApeAPIAdapter.java` imports but are absent from the
public `android.jar`. `dalvik_stub/classes.jar` provides Dalvik-specific stubs. Both are
declared with `system` scope in `pom.xml` so they appear on the `javac` classpath but are
excluded from the packaged output.

## Data Contracts

### Input

- `src/main/java/com/android/commands/monkey/**/*.java`: `Java source` — 140 Java source files. Entry point: `com.android.commands.monkey.Monkey`. Source: repository working tree.
- `framework/classes-full-debug.jar`: `JAR` — Android framework JAR exposing `@hide` APIs. Required at compile time only (system scope). Source: vendored in repository.
- `dalvik_stub/classes.jar`: `JAR` — Dalvik stub classes required at compile time only (system scope). Source: vendored in repository.
- `d8`: `executable` — Android SDK DEX compiler (from build-tools 28+). Converts JVM bytecode to Dalvik bytecode. Source: Android SDK installation on host, available on `PATH`.

### Output

- `target/ape-rv.jar`: `Dalvik JAR` — ZIP archive containing `classes.dex` (Dalvik bytecode, DEX version 035). Destination: Android device via `adb push /data/local/tmp/`.
- `target/ape-rv-classes.jar`: `JAR` — Intermediate JAR of compiled JVM `.class` files. Input to `d8`. Not deployed to any device.
- `target/classes/`: `directory` — Compiled `.class` files produced by `mvn compile`. Not deployed to any device.

### Side-Effects

- **Compilation**: `mvn compile` writes `.class` files to `target/classes/`.
- **Package (jar)**: `maven-jar-plugin` (bound to `prepare-package`) writes `target/ape-rv-classes.jar`.
- **Package (dex)**: `exec-maven-plugin` invokes `d8 --output target/ape-rv.jar --min-api 23 target/ape-rv-classes.jar`, producing `target/ape-rv.jar`.
- **Clean**: `mvn clean` removes `target/`.

### Error

- `d8: command not found` — `d8` is not on `PATH`. Resolution: install Android SDK build-tools 28+ and add to `PATH` (or set `$ANDROID_HOME`).
- `COMPILATION ERROR` — A Java source file fails compilation. Most commonly caused by removing `framework/classes-full-debug.jar` or `dalvik_stub/classes.jar` from the POM dependencies, causing unresolvable symbol errors in `ApeAPIAdapter.java`.
- `release version N not supported` — Maven compiler plugin invoked with JDK older than 11. Resolution: use JDK 11+.

## Invariants

- **INV-BUILD-01**: The output `target/ape-rv.jar` MUST contain `classes.dex`. Verification: `unzip -l target/ape-rv.jar` MUST list `classes.dex`.

- **INV-BUILD-02**: The `classes.dex` entry inside `target/ape-rv.jar` MUST be valid Dalvik bytecode. Verification: `unzip -p target/ape-rv.jar classes.dex | file -` MUST return output containing `"Dalvik dex file"`.

- **INV-BUILD-03**: `framework/classes-full-debug.jar` MUST be on the `javac` classpath during `mvn compile`. It MUST NOT appear in `target/ape-rv.jar`. Verification: `unzip -l target/ape-rv.jar | grep framework` MUST return empty.

- **INV-BUILD-04**: `dalvik_stub/classes.jar` MUST be on the `javac` classpath during `mvn compile`. It MUST NOT appear in `target/ape-rv.jar`. Verification: `unzip -l target/ape-rv.jar | grep dalvik_stub` MUST return empty.

- **INV-BUILD-05**: The Maven compiler plugin MUST use Java 11 release compatibility (`<release>11</release>` in `maven-compiler-plugin` configuration). Verification: `mvn compile` log MUST show `javac [debug release 11]`.

- **INV-BUILD-06**: `target/ape-rv.jar` MUST NOT contain any `.java` source files. Only `classes.dex` is permitted. Verification: `unzip -l target/ape-rv.jar | grep .java` MUST return empty.

- **INV-BUILD-07**: `mvn clean` MUST remove the `target/` directory. Verification: `mvn clean && ls target` MUST fail with "No such file or directory".

- **INV-BUILD-08**: Java source files MUST reside in `src/main/java/`. The `pom.xml` MUST NOT contain a `<sourceDirectory>` override. Verification: `grep -r sourceDirectory pom.xml` MUST return empty.

## Requirements

### Requirement: Maven Package Target

The `mvn package` target MUST compile all Java source files under `src/main/java/` using Java 11
release compatibility, package them into an intermediate JAR, and invoke `d8` to produce
`target/ape-rv.jar` containing valid Dalvik bytecode.

#### Scenario: Successful build from clean state

- **WHEN** `mvn clean package` is executed with JDK 11+ on PATH, `d8` on PATH, and both vendored JARs present
- **THEN** the command MUST exit with code 0
- **AND** `target/ape-rv.jar` MUST exist
- **AND** `unzip -p target/ape-rv.jar classes.dex | file -` MUST return output containing `"Dalvik dex file"`

#### Scenario: Compilation fails when framework JAR is missing

- **WHEN** `mvn compile` is executed and `framework/classes-full-debug.jar` is absent from the POM
- **THEN** the build MUST fail with an unresolvable symbol error in `ApeAPIAdapter.java`
- **AND** no `target/ape-rv.jar` MUST be produced

---

### Requirement: Maven Compile Target

The `mvn compile` phase MUST compile all 140 Java source files under `src/main/java/` to
`.class` files in `target/classes/`, using Java 11 release compatibility.

#### Scenario: Successful compilation from clean state

- **WHEN** `mvn compile` is executed with JDK 11+ on PATH and both vendored JARs present
- **THEN** the target MUST exit with code 0
- **AND** `target/classes/com/android/commands/monkey/Monkey.class` MUST exist
- **AND** `target/classes/com/android/commands/monkey/ApeAPIAdapter.class` MUST exist

---

### Requirement: Maven Clean Target

The `mvn clean` target MUST remove all build artifacts under `target/`. After `mvn clean`,
the repository MUST be in the same state as a fresh checkout (excluding vendored JARs and source files).

#### Scenario: Clean removes build artifacts

- **WHEN** `mvn clean` is executed after a successful `mvn package`
- **THEN** the `target/` directory MUST NOT exist
