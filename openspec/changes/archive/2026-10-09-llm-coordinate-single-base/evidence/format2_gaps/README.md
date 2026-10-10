# Gaps of the format-2 MOP artifact — measurement of 2026-10-09

Backs the amendments of 2026-10-09 to design D15 (per-key cut), D17 (click aggregate) and D19 (retirement at `d*`), and tasks group 18.

## Input and method

- **Documents:** the 163 gh120 `.apk.json` of the Study 03 v2 static analysis, round A:
  `rvsec-study03-replication-package/data/raw/e6-static-analysis/final/`.
  38 of them carry no `"complete"`.
- **Derive:** rv-android `modules/aperv-tool/src/aperv_tool/tools/aperv/derive_mop_artifact.py`, working tree at `c7da2cec`/`2e61d4b5`.
  It is imported unchanged. The scripts wrap `_cut_pairs` and the three `_emit_*` functions at run time to observe the lists before the cut, and they edit no file.
- **Process:** each document runs in a fresh process, as `tool.py` does it: read bytes, `digest_of`, `json.loads`, `derive`, `serialize_canonical`.
  `ru_maxrss` is that process's peak.
  Nothing was written next to the documents.
- **Commands:**
  - `python3 -I -B <script> <derive dir> <final dir> <out.jsonl>`;
  - then `python3 -I -B summarize.py results.jsonl`, `extra.py results.jsonl` and `summarize_k.py results_k.jsonl`.
  - `measure_k.py` was run with a 3,000 MB bound, so its results cover 162 documents (sdmse left out).
  - `measure_click.py` holds the final click rule.

## Files

| File | What it measures |
|---|---|
| `measure.py` | Derive cost; pre-cut list widths (activity, widget, handler) at `d ≤ 3` and at `d ≤ 6`; events of the flags and of the pairs; WTG; widgets and handlers. Output `results.jsonl.gz`, summary `summary.txt` (`summarize.py` + `extra.py`) |
| `measure_k.py` | Artifact size under K = 3 / 5 / 10 and under the `d ≤ 3` / `d ≤ 6` cuts; targets near (`d ≤ 3`) before the cut that vanish after K = 3. Output `results_k.jsonl.gz`, summary `summary_k.txt` |
| `measure_click.py` | What the jar's id-key lookup gives for a click and for a long-click: weight under `flag` with no pair for the event, and what the click aggregate (final rule) or a full mirror (long-click) would give. Output `results_click.jsonl.gz`, summary `summary_click.txt` |

## Results

- **Derive cost:**
  - artifact: median 34 KB, p90 224 KB, largest 1.97 MB (bravedns);
  - peak RSS: median 42 MB, p90 1.27 GB; sdmse 36.4 GB in 161 s; wikipedia 14.6 GB in 49 s;
  - RSS is about 4.1× the JSON size, and the documents are indented.
- **K = 3:**
  - 9,158 of the 27,671 targets that lie at `d ≤ 3` of some list before the cut lie at `d ≤ 3` of no list after it (98 apps).
  - On the widget and handler lists, the ones that weigh actions, it is 4,024 of 21,485 (18.7 %).
  - Artifact size under each cut, over 162 documents:

    | Cut | Median | Largest | Total |
    |---|---|---|---|
    | K = 3 | 34 KB | 1.97 MB | 14.6 MB |
    | `d ≤ 3` | 34 KB | 1.9 MB | 15.2 MB |
    | `d ≤ 6` | 42 KB | 13 MB | 60 MB |

  - Widget and handler lists hold a median of 1 target at `d ≤ 3`, 7 at the 90th percentile and 798 at most.
- **Click:**
  - 5,826 widgets (36 apps) weigh under `flag` and have no `click` pair.
  - Under the final rule (no `click` key; aggregate over every event but `longclick`, `itemlongclick`, `implicitcreatecontextmenu` and `scroll`), 2,608 of them have pairs, 2,095 of those at `d ≤ 3`, in 22 apps.
- **Long-click under a full mirror:**
  - 30,041 widgets weigh under `flag` and have no `longclick` pair.
  - 26,764 of them have no `longclick` key and carry pairs under other events, 22,716 of those at `d ≤ 3`. This is the reason the fallback was restricted to `click`.
- **WTG:**
  - 108 artifacts have no click edge: 38 have no transition at all (exactly the documents without `"complete"`), and 70 have only implicit or non-click transitions.
  - There are 729 orphan dialogs in 79 apps.
- **Widgets:**
  - 76 artifacts carry no widget.
  - The 163 artifacts carry 62,100 handlers, 24,195 of them with a pair.
  - 7 of the widget-less apps have neither a handler pair nor an `activityDist` entry.
