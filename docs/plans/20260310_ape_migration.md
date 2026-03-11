# APE Modernization Plan: Java 11 + d8 + Makefile

**Date**: 2026-03-10

## Context

APE currently declares `source/target="1.7"` in `build.xml` and uses `dx` for Dalvik conversion. The current development environment and Docker containers use **Java 25** and **build-tools 35.0.1**, which neither includes `dx` nor supports `--source/--target 1.7`. The build is **currently broken**. This migration is a prerequisite for bringing rvsmart features into APE.

**Chosen approach**: Java 11 + d8 + **Makefile** (replacing Ant) + `android.jar` from SDK API 29.

---

## Critical Files

| File | Change |
|---|---|
| `Makefile` | **New** — replaces `build.xml` |
| `build.xml` | Remove (or keep with deprecated notice) |
| `framework/android-29.jar` | **New** — copy of `android.jar` from SDK API 29 |
| `framework/classes-full-debug.jar` | Remove (was API 23, superseded) |
| `dalvik_stub/classes.jar` | Evaluate: remove if compilation works without it |
| `CLAUDE.md` | Update build section |

---

## Implementation Steps

### 1. Verify environment
```bash
java -version                                          # Expected: Java 25
which d8                                               # Expected: path in build-tools/35.0.1
ls $ANDROID_HOME/platforms/android-29/android.jar     # Expected: file exists
ant compile 2>&1 | head -5                             # Expected: fails (source 1.7 invalid)
```

### 2. Copy android.jar from SDK API 29
```bash
cp $ANDROID_HOME/platforms/android-29/android.jar framework/android-29.jar
```

APE uses reflection (`ApeAPIAdapter`) for hidden APIs — they don't need to be on the compile-time classpath, only available at runtime. The public `android.jar` for API 29 is sufficient for compilation.

### 3. Create Makefile

```makefile
JAVAC   := javac
D8      := d8
JAR_CMD := jar

SRC_DIR := src
BIN_DIR := bin
JAR_OUT := ape.jar

FRAMEWORK_JAR := framework/android-29.jar
CLASSPATH     := $(FRAMEWORK_JAR)

SOURCES := $(shell find $(SRC_DIR) -name "*.java")

.PHONY: all compile assemble clean

all: assemble

compile: $(BIN_DIR)/.compiled

$(BIN_DIR)/.compiled: $(SOURCES)
	mkdir -p $(BIN_DIR)
	$(JAVAC) --release 11 -cp $(CLASSPATH) -d $(BIN_DIR) $(SOURCES)
	@touch $(BIN_DIR)/.compiled

assemble: compile
	$(D8) --output $(BIN_DIR) $(shell find $(BIN_DIR) -name "*.class")
	$(JAR_CMD) cf $(JAR_OUT) -C $(BIN_DIR) classes.dex
	@echo "Built: $(JAR_OUT)"

clean:
	rm -rf $(BIN_DIR) $(JAR_OUT)
```

> - `--release 11`: Java 11 bytecode with stdlib APIs cross-checked against JDK 11
> - `d8` accepts Java 8–17 bytecode without extra flags
> - Final JAR contains only `classes.dex` — the format expected by `app_process`

### 4. Fix compilation errors (if any)

With Java 11 + `android.jar` API 29, a few errors may appear:
- **Missing class/method**: check if a reflection path already exists in `ApeAPIAdapter.java`; if not, add one
- **Missing compile-time stub**: if `dalvik_stub/classes.jar` is still needed for some class, add it back to the Makefile `CLASSPATH`

### 5. Test the build
```bash
make clean
make          # compile + assemble → ape.jar
ls -lh ape.jar
unzip -p ape.jar | file -   # should report "Dalvik dex file"
```

### 6. Test on emulator
```bash
adb push ape.jar /data/local/tmp/
python ape.py -p <apk_package> --running-minutes 1
# Verify in logs that states are being built
```

### 7. Update CLAUDE.md
Replace `ant` commands with `make` in the build section.

---

## End-to-End Verification

1. `make` → no errors, `ape.jar` produced
2. `unzip -p ape.jar | file -` → "Dalvik dex file version 035"
3. `python ape.py -p <apk>` → APE runs 60s+ without crashing
4. Logs show state transitions being recorded

---

## Risks and Mitigations

| Risk | Mitigation |
|---|---|
| `android.jar` API 29 lacks hidden APIs needed at compile time | APE uses reflection; if any non-public class is needed at compile-time, add `dalvik_stub/classes.jar` back to CLASSPATH |
| d8 rejects Java 11 bytecode | d8 supports Java 8–17; Java 11 is safe |
| API signature changes between API 23 and API 29 | Fix individually; `ApeAPIAdapter` already handles variation via reflection |

---

## Out of Scope (future work)

- Java 17/21
- Code modernization (lambdas, streams, records)
- Migrating Ant to Gradle
- Bringing rvsmart features into APE
