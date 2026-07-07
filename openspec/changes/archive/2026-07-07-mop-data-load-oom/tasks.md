# Tasks: mop-data-load-oom

## 1. Core

- [x] 1.1 Rewrite `MopData.readFile`: reject when `File.length() > Integer.MAX_VALUE` (too-large, before the `(int)` cast), else sized `byte[]` from `File.length()`, full-read loop, single `new String(bytes, UTF_8)`; update the method comment to the current contract (P4)
- [x] 1.2 Add the pre-read budget guard in `MopData.load`: compare `fileSize > budget / PARSE_FOOTPRINT_FACTOR` (division to avoid multiplication overflow; factor is a `=6` code constant with an empirical/conservative, recalibratable comment) where `budget` is a static `Runtime.getRuntime().maxMemory()`-based value → emit `[APE-MOP-DATA] status=rejected reason=too-large size=<bytes> budget=<bytes>`, return null; add the package-visible `load(..., budgetBytes)` overload as the test seam (public entry computes the budget from `Runtime`)
- [x] 1.3 Wrap the ENTIRE load body (budget guard, read, sentinel check, `JSONObject` construction, typed parsing, `MopData` construction) in a single outer `catch (OutOfMemoryError)`: null local refs, emit `[APE-MOP-DATA] status=rejected reason=oom`, return null (INV-MOP-26); leave the existing inner `IOException`/`JSONException` catches unchanged

## 2. Tests (new `MopDataLoadTest`, reuse existing MopData fixtures)

- [x] 2.1 `readFileExactAllocation`: fixture content read equals expected string (UTF-8, multi-KB fixture)
- [x] 2.2 `oversizedFileRejectedTooLarge`: small injected budget → null, exactly one `status=rejected reason=too-large` line with size and budget, file not parsed
- [x] 2.3 `normalFileLoadsWithinBudget`: standard fixture + generous budget → `status=loaded`, MopData non-null (regression)
- [x] 2.4 Run `mvn test -Dtest=MopDataLoadTest`

## 3. Verification

- [x] 3.1 Full suite: `mvn test` (0 failures/errors — all existing MopData tests green)
- [x] 3.2 `openspec validate mop-data-load-oom --strict`
- [x] 3.3 Device smoke (rebuilt jar): standalone run on `org.quantumbadger.redreader_117.apk` — trace must show `status=rejected reason=too-large` + `StopTestingException` (INV-MOP-22 abort), and no `OutOfMemoryError` stack
