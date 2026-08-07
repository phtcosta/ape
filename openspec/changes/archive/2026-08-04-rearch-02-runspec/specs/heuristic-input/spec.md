# Delta Specification: heuristic-input (rearch-02-runspec)

## Invariants

- **INV-INP-07** (ADDED): Every random draw in input-string generation (`StringCache.nextString()`, `RandomHelper.nextFormattedString()`, the `randomFormattedStringProp` toss) SHALL come from the seeded run RNG (`RandomHelper`, seeded from `-s` and owned by `RunContext`). `ThreadLocalRandom`, `Math.random`, and unseeded `Random` instances SHALL NOT appear anywhere in the input-generation path. Consequence: given the same seed and the same sequence of `nextString()` calls over the same cache contents, the generated strings are identical across runs.
- **INV-INP-06** unchanged in meaning: `StringCache.nextString()` never throws; an empty cache yields a formatted random string (now drawn from the seeded stream).

## MODIFIED Requirements

### Requirement: StringCache Empty-Cache Behavior

`StringCache.nextString()` SHALL check for an empty cache **before** drawing a random index, and SHALL return `RandomHelper.nextFormattedString()` when the cache is empty. The cache is populated **exclusively from text observed on screen during the run** (`cacheString(s, addToList=true)` call sites): the `/sdcard/ape.strings` seeding file, its static-initializer reader, and the reader's `RuntimeException`-on-failure path no longer exist (owner decision D6 — no arm uses the file and the aperv deployment never pushes it; the input was an unecho'd behavioral side channel and, worse, an unreadable file crashed the process during class initialization). `maxStringListSize` is therefore `Config.maxStringListSize` alone, with no file-derived contribution.

When the cache is non-empty, the selection index SHALL be drawn from the seeded run RNG (`RandomHelper`), not from `ThreadLocalRandom` — this closes the last unseeded decision source (verified V23) and brings input-string selection under INV-EXPL-14 (seeded reproducibility). On a text-sparse screen — typically a login form, exactly where input matters most — the cache is genuinely empty and the empty-check-first order prevents the historical `nextInt(0)` `IllegalArgumentException` on the GENERIC input path.

#### Scenario: empty cache returns a fallback string

- **WHEN** `nextString()` is called with an empty cache
- **THEN** it SHALL return a non-null formatted random string
- **AND** no exception SHALL be thrown

#### Scenario: populated cache unchanged

- **WHEN** the cache holds at least one string
- **THEN** `nextString()` SHALL return one of the cached strings, as before — what this change alters is where the index comes from, not what the method returns (next scenario)

#### Scenario: populated cache draws from the seeded stream

- **WHEN** the cache holds at least one string
- **THEN** `nextString()` SHALL select the index via the seeded `RandomHelper` stream
- **AND** two runs with the same seed and the same cache contents SHALL select the same strings in the same order

#### Scenario: legacy strings file has no effect

- **WHEN** a legacy `/sdcard/ape.strings` file exists on the device (readable or not)
- **THEN** class initialization SHALL NOT read it, SHALL NOT throw, and the cache SHALL start empty
