# Proposal: mop-data-load-oom

## Why

`MopData.readFile` reads the static-analysis JSON into a growing `StringBuilder` (doubling on `append`, transient footprint up to ~4× the file size). On `org.quantumbadger.redreader_117.apk` — whose co-located JSON is 50.6 MB against the Monkey process's ~201 MB heap growth limit — the read throws `OutOfMemoryError` inside the `StatefulAgent` constructor, before step 1, in 3/3 reps of BOTH the cmpds and cmpft runs. The Error propagates uncaught to Monkey's "Internal error" path, so the `sata_mop` arm records 0 steps / 0 coverage / 0 violations for the app while `ape` and `aperv:sata` score ~15% cov_method and 7 unique violations. This silently biased the cmpds arm comparison (a 15%→0 pair against sata_mop) and violates two invariants this worktree already ships: INV-MOP-21 (exactly one `[APE-MOP-DATA]` status line per load — none is emitted) and the spirit of INV-MOP-22 (abort must be the deliberate `StopTestingException`, not a raw OOM crash).

Dataset exposure: 1 JSON >40 MB (redreader, fails), 1 at 20–40 MB (sdmse 23.7 MB, loads today), 9 at 10–20 MB — the doubling spike leaves the 10–25 MB band needlessly close to the edge.

## What Changes

- `readFile` reads the file as a sized `byte[]` (from `File.length()`) and decodes once (`new String(bytes, UTF_8)`) — eliminating the ~4× doubling spike; peak becomes ~3× file size (bytes + chars) held briefly.
- A pre-read budget guard: when `fileSize > budget / PARSE_FOOTPRINT_FACTOR` (division, not `fileSize × 6 > budget`, to avoid multiplication overflow), where `budget` is a static `Runtime.getRuntime().maxMemory()`-based value, `load` rejects immediately with `[APE-MOP-DATA] status=rejected reason=too-large size=<bytes> budget=<bytes>` and returns null — deterministic and fast, no heap thrash. A static max-heap budget (rather than a live free+unallocated reading) makes the reject decision a pure function of file size for a given device config, so a borderline file cannot flip pass/reject across runs with GC state. The factor is a code constant (empirical/conservative, recalibratable from the status-line telemetry), not a config flag.
- Belt-and-suspenders: a single outer `catch (OutOfMemoryError)` wraps the whole load body (guard, read, sentinel, parse, `MopData` construction), drops references, and rejects with `status=rejected reason=oom` instead of propagating.
- Net effect: an oversized JSON produces a deterministic, diagnosable fail-fast — a status line then `requireMopArm` → `StopTestingException`, which at agent-construction time propagates to Monkey's generic `catch (Throwable)` ("Internal error", exit 1), NOT the graceful `getNextEvent` stop path. Versus today's raw OOM crash, the exit-1 surface is the same but the status line now records the cause, so analysis pipelines can exclude/annotate the pair. The app remains excluded from the MOP arm (deliberate INV-MOP-22 semantics — never run mislabeled as pure SATA).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `mop-guidance`: `MopData.load` gains a memory-safety requirement — oversized input is rejected with a status line, never an uncaught `OutOfMemoryError` (new INV-MOP-26; upholds INV-MOP-21/22 for this input class).

## Impact

- **Components**: `MopData.readFile` + `MopData.load` guard. `requireMopArm`/`StatefulAgent` unchanged.
- **Experiments**: redreader's `sata_mop` tasks fail fast with a readable cause; analysis pipelines can exclude/annotate the pair instead of averaging silent zeros. The 10–20 MB band gains real headroom.
- **Risk**: the budget guard could reject a file the device might have loaded — mitigated by deriving the factor from measured org.json overhead and by the OOM catch as backstop; today's behavior for such a file is a hard crash, so rejection is strictly better.
- **Archive ordering**: this change ADDs `Load memory safety` (INV-MOP-26) and its prose upholds INV-MOP-21/22, which exist only in the unarchived `experiment-validity` delta (`MopData — Load Status Line and Fail-Fast`) — the main `mop-guidance` spec has neither. This change MUST therefore be archived AFTER `experiment-validity`, so those references resolve in the main spec at archive time.
- **Out of scope**: streaming parse (would break the JVM test suite — `android.util.JsonReader` is device-only) and producer-side JSON trimming (rv-android repo).
