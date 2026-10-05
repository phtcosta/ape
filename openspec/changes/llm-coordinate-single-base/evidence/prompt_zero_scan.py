#!/usr/bin/env python3
"""Where `@(0,0)` appears in the LLM prompts of the APE-RV LLM arm (jar e93dea86).

WHAT THIS MEASURES
  Every `llm[].user` prompt dump of the trace, split into its element list (`UI elements:` for v13,
  `ELEMENTS:` for v17) and its `Recent:` history section:
    * list lines rendering `@(0,0)`: at e93dea86 a line whose action has no resolved node writes
      `@(0,0)` (normX/normY default to 0 and are always appended), and so does a resolved node whose
      centre lies off-screen at negative coordinates (clamped to 0);
    * history entries `- <type> @(0,0)`, split by whether they carry a widget class: an off-tree
      MODEL_LLM_TAP and a MODEL_MENU are recorded by StatefulAgent.recordActionHistory as a class-less
      `click` at (0,0);
    * answers whose `qwen` is (0,0), by tool and outcome, and whether the prompt they answered showed
      a `@(0,0)` in the list or the history.

INPUTS
  Plain APE trace files (`*.trace`); NDJSON step records are the lines starting with `{"s"`.
  The E6 arm-4 trace of com.ds.avare_404.apk was overwritten on 2026-10-05 by a run with no LLM
  answers and is excluded, as in coordinate_counterfactual.py.

RUN
  python3 prompt_zero_scan.py          # E6 arm 4
  python3 prompt_zero_scan.py e5b      # E5b v13_A
Reads only; prints to stdout. Deterministic (files sorted, counters merged in file order).
"""

import collections
import glob
import json
import multiprocessing
import os
import re
import sys

E6_ROOT = ("/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/"
           "rvsec-study03-replication-package/data/raw/e6-campaign")
E6_GLOB = "e6_0?/e6_0?/*/*__aperv:e6_mop_on_llm_90.trace"
E5B_ROOT = ("/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/"
            "rvsec/rv-android/data/results")
E5B_GLOB = "e5bm1_*/e5bm1_*/*/*e5b_m1_v13_A.trace"
EXCLUDE_APKS = {("/e6-campaign/", "com.ds.avare_404.apk")}

RE_HIST = re.compile(r"^- (\w+) @\(0,0\)(.*?)(?: → .*)?$")


def split_prompt(user):
    """(list block, history block) of one prompt dump."""
    if "ELEMENTS:" in user:                       # v17: `Recent: ...` line precedes the list
        head, lst = user.split("ELEMENTS:", 1)
        return lst, head
    lst = user.split("UI elements:", 1)[1] if "UI elements:" in user else user
    if "\nRecent" in lst:
        lst, hist = lst.split("\nRecent", 1)
        return lst, hist
    return lst, ""


def scan(path):
    C = collections.Counter()
    apk = os.path.basename(os.path.dirname(path))
    examples = []
    with open(path, "rt", errors="replace") as fh:
        for line in fh:
            if not line.startswith('{"s"'):
                continue
            try:
                rec = json.loads(line)
            except ValueError:
                C["bad_json"] += 1
                continue
            for call in rec.get("llm") or []:
                user = call.get("user")
                if not user:
                    continue
                C["prompts"] += 1
                lst, hist = split_prompt(user)
                lines = lst.split("\n")
                C["list_lines"] += sum(1 for l in lines if " @(" in l)
                zero = [l.strip() for l in lines if "@(0,0)" in l]
                if zero:
                    C["prompts_with_list00"] += 1
                    C["list00_lines"] += len(zero)
                    if len(examples) < 3:
                        examples.append((apk, rec.get("s"), zero[0]))
                h00 = 0
                for l in hist.split("\n"):
                    m = RE_HIST.match(l.strip())
                    if m:
                        h00 += 1
                        C[("hist00", m.group(1), "class" if m.group(2).strip() else "noclass")] += 1
                if "@(0,0)" in hist:
                    C["prompts_with_hist00"] += 1
                C["hist00_entries"] += hist.count("@(0,0)")
                q = call.get("qwen")
                if q and list(q) == [0, 0]:
                    C[("ans00", str(call.get("tool")), "%s/%s" % (call.get("result"), call.get("reason")),
                       "list00" if zero else "-", "hist00" if h00 else "-")] += 1
    return path, apk, C, examples


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "e5b":
        root, pattern = E5B_ROOT, E5B_GLOB
    else:
        root, pattern = E6_ROOT, E6_GLOB
    files = sorted(glob.glob(os.path.join(root, pattern)))
    excluded = [f for f in files if any(r in f and a in f for r, a in EXCLUDE_APKS)]
    files = [f for f in files if f not in excluded]
    print("excluded (overwritten in place):", [os.path.relpath(f, root) for f in excluded])
    print("# inputs:", os.path.join(root, pattern))
    T = collections.Counter()
    apks_list00 = set()
    examples = []
    with multiprocessing.Pool(min(32, os.cpu_count() or 1)) as pool:
        for path, apk, C, ex in pool.imap(scan, files):
            T += C
            examples += ex
            if C["list00_lines"]:
                apks_list00.add(apk)
    p = T["prompts"]
    print("files: %d; prompts: %d; list lines with a coordinate: %d" % (len(files), p, T["list_lines"]))
    print("list @(0,0): lines %d in %d prompts, %d APKs" % (T["list00_lines"], T["prompts_with_list00"],
                                                            len(apks_list00)))
    for apk, s, l in examples:
        print("  e.g. %s step %s: %s" % (apk, s, l))
    print("history @(0,0): entries %d in %d prompts (%.1f%%)" % (
        T["hist00_entries"], T["prompts_with_hist00"], 100.0 * T["prompts_with_hist00"] / max(p, 1)))
    for k in sorted(k for k in T if isinstance(k, tuple) and k[0] == "hist00"):
        print("  %-10s %-8s %d" % (k[1], k[2], T[k]))
    print("answers with qwen (0,0) (tool, outcome, prompt showed @(0,0) in list / history):")
    for k in sorted(k for k in T if isinstance(k, tuple) and k[0] == "ans00"):
        print("  %-10s %-24s %-7s %-7s %d" % (k[1], k[2], k[3], k[4], T[k]))


if __name__ == "__main__":
    main()
