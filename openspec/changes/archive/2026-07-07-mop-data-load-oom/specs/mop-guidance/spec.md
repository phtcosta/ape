## Purpose

Memory safety for `MopData.load`. The loader reads the whole static-analysis JSON into memory because the Android-bundled org.json offers only `JSONTokener(String)`. The read path (`readFile`) used a growing `StringBuilder`, whose doubling makes the transient footprint up to ~4× the file size; with the Monkey heap capped near 201 MB, a 50 MB production JSON (redreader) threw `OutOfMemoryError` inside the agent constructor in 3/3 reps of two independent experiment runs. The Error escaped `load` entirely: no `[APE-MOP-DATA]` status line was emitted (violating INV-MOP-21), `requireMopArm` never ran, and the run died as a raw Monkey "Internal error" with 0 steps — zeroing the app for the MOP arm while the other arms scored normally.

This delta bounds the loader: a sized single-allocation read replaces the doubling builder, an upfront budget check rejects files that cannot fit the parse footprint, and `OutOfMemoryError` from read/parse is converted into the same rejection contract every other load failure already uses (`status=rejected` + null return → INV-MOP-22 abort via `StopTestingException`). Oversized input becomes a deterministic, diagnosable rejection instead of a crash.

## Data Contracts

### Input
- `ape.mopDataPath: String` — device-local JSON path (existing).

### Side-Effects
- **[Trace]**: `[APE-MOP-DATA] status=rejected reason=too-large size=<bytes> budget=<bytes>` — file exceeds the parse budget (pre-read).
- **[Trace]**: `[APE-MOP-DATA] status=rejected reason=oom` — read/parse ran out of memory despite the guard (backstop).

### Error
- Never propagates `OutOfMemoryError` or `IOException` to the caller; all failures return null after emitting exactly one status line (INV-MOP-21).

## ADDED Requirements

### Requirement: Load memory safety

`MopData.readFile` SHALL allocate the read buffer once, sized from `File.length()`, and decode in a single `new String(bytes, UTF_8)` — it SHALL NOT grow a `StringBuilder` incrementally over the file.

Before reading, `MopData.load` SHALL reject the file when its size times a parse-footprint factor (code constant, sized for the org.json DOM) exceeds a budget derived from the process's maximum heap (`Runtime.getRuntime().maxMemory()`). The comparison SHALL be computed without multiplication overflow (e.g. `fileSize > budget / factor`). A static max-heap budget — rather than a live free-plus-unallocated reading — makes the reject decision a pure function of file size for a given device config, so a borderline file cannot flip pass/reject across runs with GC state. When the budget is exceeded, `load` SHALL emit `[APE-MOP-DATA] status=rejected reason=too-large size=<bytes> budget=<bytes>` and return null without reading the file.

If `OutOfMemoryError` is nonetheless thrown anywhere in the load body — read, sentinel check, `JSONObject` construction, typed parsing, or `MopData` construction — a single outer catch SHALL contain it: `load` releases its local references, emits `[APE-MOP-DATA] status=rejected reason=oom`, and returns null. The Error SHALL NOT propagate (INV-MOP-26). The null return flows into the existing `requireMopArm` contract: with `ape.mopDataPath` set, the run fails fast via `StopTestingException` (INV-MOP-22). This is a deterministic, diagnosable fail-fast, not a graceful stop — the throw occurs at agent-construction time, so it propagates to Monkey's generic `catch (Throwable)` ("Internal error", exit 1) rather than the graceful `getNextEvent` stop path; the status line emitted first is what makes the run excludable/annotatable by analysis pipelines.

- **INV-MOP-26**: `MopData.load` SHALL NOT propagate `OutOfMemoryError` to its caller, from any phase of the load body; every failure path emits exactly one `[APE-MOP-DATA] status=rejected` line and returns null. (`IOException`/`JSONException` are already contained by the existing inner catches per INV-MOP-01; INV-MOP-26 does not widen coverage to all throwables.)

#### Scenario: oversized file rejected before read
- **WHEN** the JSON at `ape.mopDataPath` is 50 MB and the available heap budget is below the parse footprint for 50 MB
- **THEN** `load` SHALL return null without reading the file
- **AND** exactly one `[APE-MOP-DATA] status=rejected reason=too-large` line SHALL be emitted
- **AND** the subsequent `requireMopArm` SHALL throw `StopTestingException` (INV-MOP-22)

#### Scenario: OOM during parse is contained
- **WHEN** the budget check passes but any phase of the load body (`JSONObject` construction, typed parsing, or `MopData` construction) exhausts the heap
- **THEN** the single outer catch SHALL contain the `OutOfMemoryError` and `load` SHALL return null
- **AND** emit `[APE-MOP-DATA] status=rejected reason=oom`

#### Scenario: normal file unaffected
- **WHEN** the JSON is 2 MB and the budget check passes
- **THEN** `load` SHALL parse and return `MopData` exactly as before, emitting `status=loaded`
