<!-- This repo (phtcosta/ape) has no rv-*/sdd-* skill layer. Validation is manual:
     JUnit (`mvn test`) for off-device logic, `unzip`/`strings` over the dex for the
     build stamp, and a device run for the banner ordering. Small change (~5 files):
     pom.xml, BuildInfo template, MopData accessor, StatefulAgent banner, tests. -->

## 1. Build Stamp (generated constant)

- [ ] 1.1 Add `<aperv.mop.schema>` to `pom.xml` `<properties>` (schema version this jar consumes; confirm literal at apply time)
- [ ] 1.2 Add `git-commit-id-maven-plugin` to `pom.xml` (bind to `initialize`; `failOnNoGitDirectory=false`; sentinel `unknown`; `dateFormatTimeZone=UTC`) exposing `${git.commit.id.abbrev}` and `${git.build.time}`
- [ ] 1.3 Add `templating-maven-plugin` to `pom.xml` (`filter-sources` at `generate-sources`)
- [ ] 1.4 Create template `src/main/java-templates/com/android/commands/monkey/ape/utils/BuildInfo.java` with `GIT_SHA` / `JAR_BUILT` / `SCHEMA` constants and a private constructor
- [ ] 1.5 `mvn clean package`; assert `target/generated-sources/.../BuildInfo.java` is filtered (no literal `${...}` placeholders remain) and `target/ape-rv.jar` builds

## 2. Build-Stamp Verification (INV-BUILD-09, INV-BUILD-06)

- [ ] 2.1 Add JUnit `BuildInfoTest`: `GIT_SHA`/`JAR_BUILT`/`SCHEMA` non-null; `SCHEMA` equals the declared `${aperv.mop.schema}` value
- [ ] 2.2 Verify the stamp is in the dex: `unzip -p target/ape-rv.jar classes.dex | strings | grep <short-sha>` returns the build sha (INV-BUILD-09)
- [ ] 2.3 Verify no resource regression: `unzip -l target/ape-rv.jar` lists no provenance `.properties`; `unzip -l target/ape-rv.jar | grep .java` is empty (INV-BUILD-06)

## 3. Session Banner (INV-BUILD-10, INV-BUILD-11)

- [ ] 3.1 Add `public int getWidgetCount()` to `MopData` returning `countWidgets(widgetData)` (reuses the existing helper)
- [ ] 3.2 Add `MopData.getWidgetCount()` unit test on a loaded fixture (asserts the same count as the `MopData: loaded N widgets` log)
- [ ] 3.3 Emit the `[APE-BUILD]` banner in the `StatefulAgent` constructor immediately after `MopData.load` (`StatefulAgent.java:162`), via `Logger.iprintln`, with `git_sha`/`jar_built`/`schema`/`mopDataPath`/`mopLoaded`/`mopWidgetCount`
- [ ] 3.4 Confirm the banner reads `mopLoaded=false` / `mopWidgetCount=0` when `_mopData == null`

## 4. Integration & Verification

- [ ] 4.1 Device run (`sata` mode, MOP enabled): confirm exactly one `[APE-BUILD]` line appears before any `[APE-RV] MOP boost` line (INV-BUILD-10) with all six fields (INV-BUILD-11)
- [ ] 4.2 Device run with MOP disabled (`mopDataPath` unset): confirm `[APE-BUILD]` still emitted with `mopLoaded=false`
- [ ] 4.3 `mvn test` green (full suite, no regression)
- [ ] 4.4 Update `CLAUDE.md` (note the `[APE-BUILD]` banner + build stamp) and run `openspec verify`
