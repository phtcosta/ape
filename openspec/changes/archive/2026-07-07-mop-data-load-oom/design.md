# Design: mop-data-load-oom

## Context

`MopData.readFile` (src/main/java/com/android/commands/monkey/ape/utils/MopData.java:829-839) streams the JSON through an 8 KB char buffer into a `StringBuilder`; `load` (MopData.java:161-271) parses the returned String with org.json and already funnels `IOException`/`JSONException`/sentinel/package-mismatch failures into `[APE-MOP-DATA] status=rejected ...` + null. `OutOfMemoryError` is the one failure class that escapes: thrown from `AbstractStringBuilder.ensureCapacityInternal` on a 134 MB array copy (redreader, 50.6 MB JSON, ~201 MB heap growth limit), it bypasses the status contract and kills Monkey in the `StatefulAgent` constructor (StatefulAgent.java:164-166 → `requireMopArm` never runs).

Constraint: streaming parse is out — the Android-bundled org.json has no reader API, `android.util.JsonReader` does not exist on the JVM test runtime, and adding gson to the dex is a new dependency for one app (P1). The fix keeps whole-file parsing but bounds and contains it.

## Architecture

```
MopData.load(path, pkg, mainActivity)
└── [NEW] try { … entire load body … } catch (OutOfMemoryError)   // single OUTER catch
    ├── budget guard: fileSize > budget / PARSE_FOOTPRINT_FACTOR
    │         → status=rejected reason=too-large size=.. budget=..  → return null
    ├── readFile(path)                       // [CHANGED] sized byte[] read + single UTF-8 decode
    ├── sentinel check                       // unchanged (now inside the outer try)
    ├── new JSONObject(new JSONTokener(s))   // unchanged
    ├── typed parsing + new MopData(...)     // unchanged (now inside the outer try)
    └── (OOM anywhere above) → null refs, status=rejected reason=oom → return null
```

A single OUTER `try/catch (OutOfMemoryError)` wraps the ENTIRE load body — the budget guard, `readFile`, the sentinel check, `JSONObject` construction, all typed parsing, and `MopData` construction — so no `OutOfMemoryError` from any phase can escape (INV-MOP-26). The existing inner `IOException`/`JSONException` structure is unchanged; those Errors are the `OutOfMemoryError` catch's concern only.

`budget` is a static `Runtime.getRuntime().maxMemory()`-based value: the reject decision is a pure function of file size for a given device heap config, instead of varying with GC state at the margin — otherwise a borderline file could flip pass/reject across runs depending on live-heap occupancy. `PARSE_FOOTPRINT_FACTOR` is a private static final int (value 6). The factor is empirical and conservative, not a derivation: the "~1× bytes + ~2× String chars + ~3× org.json DOM" breakdown is an approximation, and the single measured datapoint (redreader 50.6 MB OOM at ~201 MB heap) was dominated by the `StringBuilder` doubling spike this fix removes. Recalibrate the constant from the status-line `size`/`budget` telemetry if false rejections appear on loadable files.

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `MopData.readFile` | Sized single-allocation read | path | String |
| `MopData.load` budget guard | Reject oversized files pre-read | file length, runtime heap | status line + null |
| `MopData.load` OOM catch | Single outer catch containing OOM from the whole load body (guard, read, sentinel, parse, construction) | — | status line + null |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| sized read (no doubling) | `readFile`: reject when `f.length() > Integer.MAX_VALUE` (too-large), else `byte[] buf = new byte[(int) f.length()]` + full-read loop + one `new String` | `MopDataLoadTest.readFileExactAllocation` (behavioral: content equality on fixture) |
| budget rejection | guard before `readFile` in `load`, comparing `fileSize > budget / PARSE_FOOTPRINT_FACTOR` (division avoids the multiplication overflow of `fileSize × 6 > budget`) | `MopDataLoadTest.oversizedFileRejectedTooLarge` (small injected budget vs size — via a package-visible overload taking the budget, so the JVM test controls it) |
| INV-MOP-26 (OOM contained) | single outer `catch (OutOfMemoryError)` around the whole load body | enforced by the single outer catch (structure verified in review); its reject/null/`status=rejected reason=oom` contract is exercised by `oversizedFileRejectedTooLarge`, which asserts the same reject-and-status contract via a deterministic (non-OOM) trigger |
| status-line exactness (INV-MOP-21) | one emit per failure path | assertions on captured log in the above tests |

## Goals / Non-Goals

**Goals:**
- No `OutOfMemoryError` ever escapes `MopData.load`.
- Oversized files fail fast, pre-read, with size and budget in the trace.
- The 10–20 MB JSON band loads with real headroom (no doubling spike).

**Non-Goals:**
- Loading redreader's 50 MB JSON (impossible under org.json DOM within the device heap; the app stays excluded from the MOP arm per INV-MOP-22).
- Streaming parser, gson dependency, or producer-side JSON trimming (rv-android repo).
- Changing `requireMopArm` semantics.

## Decisions

1. **Guard derives the budget from `Runtime`, not a config flag.** The threshold is a physical property of the heap, not a tuning knob; a flag would invite miscalibration (P1: no gratuitous flags).
2. **Factor 6 as a code constant** with the derivation in its comment. Conservative for the guard's purpose: a file that fails the check at factor 6 would at best parse into a heap-thrashing, GC-bound load; rejecting is strictly better than the current crash.
3. **Testing the OOM catch**: a real OOM is not safely inducible on the JVM suite. `load` gains a package-visible seam (`load(path, pkg, main, budgetBytes)` overload used by tests; the public entry passes the computed budget). The too-large path is tested exactly (small injected budget); the OOM catch is exercised with a tiny fixture and a test-only reader hook only if trivially achievable — otherwise the catch stays covered by review + the too-large test covering the same reject-and-status contract. No production seams beyond the overload.
4. **Single outer catch around the whole load body** — the `OutOfMemoryError` catch wraps everything from the budget guard through `MopData` construction, not just read+parse. Typed parsing allocates the maps/lists/arrays and the `MopData` object itself, and the sentinel check sits unguarded between the two existing inner try blocks; an OOM in either would otherwise escape and break INV-MOP-26. The catch is scoped to `OutOfMemoryError` only, so it cannot mask logic errors — `IOException`/`JSONException` remain handled by the existing inner catches (INV-MOP-01), which are unchanged.

## Data Flow

Unchanged on success. On failure: guard/catch → status line (stdout trace, never logcat — INV-MOP-21) → null → `requireMopArm` → `StopTestingException`. This is a deterministic, diagnosable fail-fast, not a graceful stop: `requireMopArm` throws at agent-construction time (`StatefulAgent` ctor), before Monkey's `runMonkeyCycles` try/finally, so the `StopTestingException` does NOT reach the graceful `getNextEvent` stop path — it propagates to `Monkey.main`'s generic `catch (Throwable)` ("Internal error", exit 1). The status line emitted first is what makes the run excludable/annotatable by analysis pipelines; the exit-1 surface is acceptable and unchanged by this change (today's raw OOM already exits 1, but with no status line).

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| file > budget | pre-read guard | `status=rejected reason=too-large size= budget=` + null | run aborts via INV-MOP-22; analysis excludes the app pair |
| `OutOfMemoryError` | anywhere in the load body (read, sentinel, `JSONObject` parse, typed parsing, `MopData` construction) | single outer catch, null refs, `status=rejected reason=oom` + null | same |
| `IOException`/`JSONException` | existing paths | unchanged | unchanged |

## Risks / Trade-offs

- [Factor 6 rejects a file the device could have loaded] → such a load would sit at the GC cliff; today it crashes outright. The status line records size+budget so the threshold can be recalibrated from traces if it ever fires on a loadable file.
- [`File.length()` returns 0 for unreadable paths] → guard passes (0 × factor = 0) and the existing `IOException` path handles it, unchanged.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit (JVM) | sized read correctness; too-large rejection + status line; budget passthrough on normal fixture | new `MopDataLoadTest` + existing MopData fixtures stay green | ~3 new |
| Device (E2E) | redreader task: instant abort with `reason=too-large` in trace, no "Internal error" OOM stack | next cmpft-protocol validation run | 1 app |

## Open Questions

None.
