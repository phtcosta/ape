## Purpose

APE-RV is a model-based Android GUI testing tool that runs on Android devices as a Dalvik
process. Its Java sources (the files under `src/main/java/`, plus the `BuildInfo.java`
template under `src/main/java-templates/`) are compiled to JVM bytecode and then
converted to Dalvik bytecode (`.dex`) so the resulting JAR can be deployed to an Android device
via ADB and launched with `app_process`.

The build system is Apache Maven (`pom.xml`). At `initialize`, `git-commit-id-maven-plugin`
reads the short commit sha and the build time (UTC) of the working tree; at `generate-sources`,
`templating-maven-plugin` filters `src/main/java-templates/**/BuildInfo.java` with them into
`target/generated-sources/java-templates`, so `BuildInfo.GIT_SHA` and `BuildInfo.JAR_BUILT` are
compiled constants (`unknown` when there is no `.git`). The `mvn compile` phase compiles the
sources under `src/main/java/` and the filtered template to `.class` files in
`target/classes/`, using Java 11 release compatibility. The `mvn package` phase first packages
the compiled classes into an intermediate JAR (`target/ape-rv-classes.jar`) via
`maven-jar-plugin`, then invokes `d8` to convert that intermediate JAR to Dalvik bytecode,
producing `target/ape-rv.jar` containing `classes.dex`. `mvn install` additionally copies
`target/ape-rv.jar` into the rv-android `aperv-tool` module.

Two vendored JARs are required at compile time and must not be bundled into the output:
`framework/classes-full-debug.jar` exposes `@hide`-annotated Android APIs (such as
`com.android.internal.view.IInputMethodManager`, `android.app.ActivityManagerNative`,
`android.app.UiAutomationConnection`) that `ApeAPIAdapter.java` imports but are absent from the
public `android.jar`. `dalvik_stub/classes.jar` provides Dalvik-specific stubs. Both are
declared with `system` scope in `pom.xml` so they appear on the `javac` classpath but are
excluded from the packaged output, and `maven-surefire-plugin` excludes both from the test
runtime classpath (`classpathDependencyExcludes`), so unit tests run on the JVM against the
test-scope `org.json:json` and test-side stubs rather than the Android stubs.

## Data Contracts

### Input

- `src/main/java/com/android/commands/monkey/**/*.java`: `Java source` — the hand-written sources (226 files at `23c772c1`; no hard count is part of the contract). Entry point: `com.android.commands.monkey.Monkey`. Source: repository working tree.
- `src/main/java-templates/com/android/commands/monkey/ape/runtime/BuildInfo.java`: `Java template` — filtered with `${git.commit.id.abbrev}` and `${git.build.time}` into `target/generated-sources/java-templates` before compilation. Source: repository working tree.
- `.git`: `directory` (optional) — read by `git-commit-id-maven-plugin` (`failOnNoGitDirectory=false`); when absent, the `pom.xml` sentinels `unknown` are used.
- `framework/classes-full-debug.jar`: `JAR` — Android framework JAR exposing `@hide` APIs. Required at compile time only (system scope). Source: vendored in repository.
- `dalvik_stub/classes.jar`: `JAR` — Dalvik stub classes required at compile time only (system scope). Source: vendored in repository.
- `d8`: `executable` — Android SDK DEX compiler (from build-tools 28+). Converts JVM bytecode to Dalvik bytecode. Source: Android SDK installation on host, available on `PATH`.
- `rvsec_home`: `Maven property` — root of the rvsec workspace, used only by `mvn install` (default `${user.home}/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec`; override with `-Drvsec_home=<path>`).

### Side-Effects

- **Provenance stamp**: `git-commit-id-maven-plugin` 9.0.1 (bound to `initialize`, goal `revision`) sets `git.commit.id.abbrev` and `git.build.time` (format `yyyy-MM-dd'T'HH:mm:ss'Z'`, UTC); it writes no `git.properties` file (`generateGitPropertiesFile=false`), so the jar keeps a single `classes.dex` entry. `templating-maven-plugin` 3.1.0 (goal `filter-sources`) writes the filtered `BuildInfo.java` under `target/generated-sources/java-templates` and adds it to the compile roots.
- **Compilation**: `mvn compile` writes `.class` files to `target/classes/`.
- **Test**: `maven-surefire-plugin` 3.2.5 runs the JUnit 4 suite with `com.android:dalvik-stub` and `com.android:framework-full-debug` excluded from the test classpath.
- **Package (jar)**: `maven-jar-plugin` (bound to `prepare-package`) writes `target/ape-rv-classes.jar`.
- **Package (dex)**: `exec-maven-plugin` invokes `d8 --output target/ape-rv.jar --min-api 23 target/ape-rv-classes.jar`, producing `target/ape-rv.jar`.
- **Install**: `maven-resources-plugin` (execution `copy-jar-to-aperv-tool`, bound to `install`) copies `target/ape-rv.jar`, overwriting, to `${rvsec_home}/rv-android/modules/aperv-tool/src/aperv_tool/tools/aperv/ape-rv.jar`.
- **Clean**: `mvn clean` removes `target/`.

## Invariants

- **INV-BUILD-08**: Hand-written Java source files MUST reside in `src/main/java/`; the only other compile root is `target/generated-sources/java-templates`, generated by `templating-maven-plugin` from `src/main/java-templates/`. The `pom.xml` MUST NOT contain a `<sourceDirectory>` override. Verification: `grep -r sourceDirectory pom.xml` MUST return empty.

## MODIFIED Requirements

### Requirement: Maven Compile Target

The `mvn compile` phase MUST compile all Java source files under `src/main/java/`, together with
the `BuildInfo.java` filtered from `src/main/java-templates/`, to `.class` files in
`target/classes/`, using Java 11 release compatibility.

#### Scenario: Successful compilation from clean state

- **WHEN** `mvn compile` is executed with JDK 11+ on PATH and both vendored JARs present
- **THEN** the target MUST exit with code 0
- **AND** `target/classes/com/android/commands/monkey/Monkey.class` MUST exist
- **AND** `target/classes/com/android/commands/monkey/ApeAPIAdapter.class` MUST exist
- **AND** `target/classes/com/android/commands/monkey/ape/runtime/BuildInfo.class` MUST exist, with `GIT_SHA` set to the tree's short sha, or to `unknown` when the tree has no `.git`
