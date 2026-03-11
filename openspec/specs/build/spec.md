# Specification: Build Infrastructure

> **Status: Current state (legacy Ant build).** This specification describes the Ant+`dx`+Java 1.7 build system that is currently functional in the repository. Phase 1 of the APE-RV roadmap (PRD FR01–FR03) replaces this system with Maven+`d8`+Java 11, producing `ape-rv.jar` instead of `ape.jar`. A separate Phase 1 specification will document the Maven target. This document remains the authoritative reference for the legacy build until Phase 1 is complete.

## Purpose

APE is a model-based Android GUI testing tool that runs on Android devices as a Dalvik process. Its 140 Java source files are compiled to standard JVM bytecode and then converted to Dalvik bytecode (`.dex`) so the resulting JAR can be deployed to an Android device via ADB and launched with `app_process`. The output JAR, `ape.jar`, must contain `classes.dex` in Dalvik format — not standard JVM `.class` files — because the Android runtime loads Dalvik bytecode from the `CLASSPATH` environment variable when `app_process` starts.

The build system is Apache Ant (`build.xml`). The `ant compile` target compiles all Java source files under `src/` to `.class` files in `bin/`, using Java 1.7 source and target compatibility. The `ant assemble` target then invokes the Android SDK `dx` tool (`dx --dex --output=ape.jar bin/`) to convert the compiled `.class` files to Dalvik bytecode, producing `ape.jar` in the repository root.

Two vendored JARs are required at compile time and must not be replaced: `framework/classes-full-debug.jar` exposes `@hide`-annotated Android APIs (such as `com.android.internal.view.IInputMethodManager`, `android.app.ActivityManagerNative`, `android.app.UiAutomationConnection`) that `ApeAPIAdapter.java` imports but that are absent from the public `android.jar`. `dalvik_stub/classes.jar` provides Dalvik-specific stubs. Both are compile-time-only dependencies; they MUST NOT appear in `ape.jar` because the real implementations live in the Android runtime on the device.

## Data Contracts

### Input

- `src/com/android/commands/monkey/**/*.java`: `Java source` — 140 Java source files. Entry point: `com.android.commands.monkey.Monkey`. Source: repository working tree.
- `framework/classes-full-debug.jar`: `JAR` — Android framework JAR exposing `@hide` APIs. Required at compile time only. Source: vendored in repository at `framework/classes-full-debug.jar`.
- `dalvik_stub/classes.jar`: `JAR` — Dalvik stub classes required at compile time only. Source: vendored in repository at `dalvik_stub/classes.jar`.
- `dx`: `executable` — Android SDK DEX compiler (from build-tools). Converts JVM `.class` files to Dalvik bytecode. Source: Android SDK installation on host, available on `PATH`.

### Output

- `ape.jar`: `Dalvik JAR` — ZIP archive containing `classes.dex` (Dalvik bytecode). Destination: Android device via `adb push /data/local/tmp/`.
- `bin/`: `directory` — Intermediate `.class` files produced by `ant compile`. Not deployed to any device.

### Side-Effects

- **Compilation**: `ant compile` writes `.class` files under `bin/`. These are intermediate artifacts.
- **DEX conversion**: `ant assemble` invokes `dx --dex --output=ape.jar bin/`, which reads all `.class` files from `bin/` and writes `classes.dex` inside `ape.jar`.
- **Clean**: `ant clean` removes `bin/` and `ape.jar`.

### Error

- `BUILD FAILED (dx not found)` — `dx` is not on `PATH`. The Ant build fails with a process-not-found error during the `assemble` target. Resolution: install Android SDK build-tools containing `dx` and add to `PATH`.
- `BUILD FAILED (compilation error)` — A Java source file fails compilation. Most commonly caused by removing `framework/classes-full-debug.jar` or `dalvik_stub/classes.jar` from the classpath, causing unresolvable symbol errors in `ApeAPIAdapter.java`.
- `BUILD FAILED (Java version incompatible)` — Build invoked with Java < 7 or with `--source`/`--target` values that `javac` rejects.

## Invariants

- **INV-BUILD-01**: The output `ape.jar` MUST contain `classes.dex`. Verification: `unzip -l ape.jar` MUST list `classes.dex`.

- **INV-BUILD-02**: The `classes.dex` entry inside `ape.jar` MUST be valid Dalvik bytecode. Verification: `unzip -p ape.jar classes.dex | file -` MUST return output containing the string `"Dalvik dex file"`.

- **INV-BUILD-03**: `framework/classes-full-debug.jar` MUST be on the `javac` classpath during `ant compile`. It MUST NOT appear in `ape.jar`. Verification: `unzip -l ape.jar` MUST NOT list any entry from `framework/classes-full-debug.jar`.

- **INV-BUILD-04**: `dalvik_stub/classes.jar` MUST be on the `javac` classpath during `ant compile`. It MUST NOT appear in `ape.jar`. Verification: `unzip -l ape.jar` MUST NOT list any entry from `dalvik_stub/classes.jar`.

- **INV-BUILD-05**: The Ant `compile` target MUST use Java 1.7 source and target compatibility (`source="1.7" target="1.7"` in the `javac` task). Verification: `grep -A5 'javac' build.xml` MUST show `source="1.7"` and `target="1.7"`.

- **INV-BUILD-06**: `ape.jar` MUST NOT contain any `.java` source files. Only `.dex` bytecode is permitted in the output JAR. Verification: `unzip -l ape.jar` MUST NOT list any entry with a `.java` extension.

## Requirements

### Requirement: Ant Compile Target

The `ant compile` target MUST compile all Java source files under `src/` using `javac` with Java 1.7 source/target compatibility. Both `framework/classes-full-debug.jar` and `dalvik_stub/classes.jar` MUST be included on the `javac` classpath. Compiled `.class` files MUST be written to `bin/`.

#### Scenario: Successful compilation from clean state

- **WHEN** `ant compile` is executed with Java 7+ on PATH and both vendored JARs present
- **THEN** the target MUST exit with code 0
- **AND** `bin/com/android/commands/monkey/Monkey.class` MUST exist
- **AND** `bin/com/android/commands/monkey/ApeAPIAdapter.class` MUST exist

#### Scenario: Compilation fails when framework JAR is missing

- **WHEN** `ant compile` is executed and `framework/classes-full-debug.jar` does not exist
- **THEN** the build MUST fail with an unresolvable symbol error in `ApeAPIAdapter.java`
- **AND** no `.class` files MUST be produced in `bin/`

---

### Requirement: Ant Assemble Target (Dalvik Conversion)

The `ant assemble` target MUST invoke `dx --dex --output=ape.jar bin/` to convert the compiled `.class` files to Dalvik bytecode and produce `ape.jar` in the repository root. The `dx` tool MUST be available on `PATH`.

#### Scenario: Successful assemble from compiled classes

- **WHEN** `ant assemble` is executed after a successful `ant compile` and `dx` is on PATH
- **THEN** the target MUST exit with code 0
- **AND** `ape.jar` MUST exist in the repository root
- **AND** `unzip -p ape.jar classes.dex | file -` MUST return output containing `"Dalvik dex file"`

#### Scenario: Full build with ant assemble

- **WHEN** `ant assemble` is executed in the repository root with Java 7+ and `dx` on PATH
- **THEN** both `ant compile` and the DEX conversion MUST succeed
- **AND** `ape.jar` MUST be deployable via `adb push ape.jar /data/local/tmp/`

#### Scenario: dx not on PATH causes build failure

- **WHEN** `ant assemble` is executed and `dx` is not present on PATH
- **THEN** the build MUST fail with a non-zero exit code
- **AND** `ape.jar` MUST NOT be created

---

### Requirement: Output JAR Validation

The produced `ape.jar` MUST be verifiable as a valid Dalvik JAR deployable to Android devices.

#### Scenario: JAR is deployable via ADB

- **WHEN** `adb push ape.jar /data/local/tmp/ape.jar` is executed against a connected Android device
- **THEN** the push MUST succeed with exit code 0
- **AND** the device MUST be able to invoke the tool with:
  `CLASSPATH=/data/local/tmp/ape.jar app_process /system/bin com.android.commands.monkey.Monkey --help`

---

### Requirement: Clean Target

The `ant clean` target MUST remove all build artifacts: the `bin/` directory and `ape.jar`. After `ant clean`, the repository MUST be in the same state as a fresh checkout (excluding vendored JARs and source files).

#### Scenario: Clean removes build artifacts

- **WHEN** `ant clean` is executed after a successful `ant assemble`
- **THEN** the `bin/` directory MUST NOT exist
- **AND** `ape.jar` MUST NOT exist in the repository root
