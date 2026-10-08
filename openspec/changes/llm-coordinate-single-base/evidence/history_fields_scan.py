#!/usr/bin/env python3
"""How the action history names the widget it clicked, against the element list (jar e93dea86).

WHAT THIS MEASURES (added 2026-10-07, design D13)
  The `exploration` spec has said since gh6 that an `ActionHistoryEntry`'s `widgetClass` is the
  widget's class *simple name* and its `widgetText` the widget's text *or content-description*.
  `StatefulAgent.recordActionHistory` stores `node.getClassName()` (the full name) and
  `node.getText()` only. This scan counts, over every `llm[].user` prompt dump that shows a
  `Recent:` history:
    * entries of a node action (`click`/`long_click` with a class) whose class is a full package
      name, and the prompts that show at least one;
    * of those, entries that carry no text;
    * for each text-less entry, whether the element list of the same prompt holds a line for the
      same widget, and what identifier the list showed for it. "Same widget" is estimated: same
      simple class, same x, and a list y within 2 of the history y rescaled from the display base
      (1794, the history's divisor) to the full-window root base (1920, the list's divisor on a
      full-screen window). Dialogs, popups and widgets no longer on screen do not match, so the
      matched counts are a lower bound. A list identifier that is not `id=...` is the widget's
      text or content-description; for an entry with no text that is the content-description,
      unless the text changed between selection and the prompt;
    * `type_text` entries, which carry no class and no widget text (pending improvement P3).

INPUTS
  The same traces as prompt_zero_scan.py; the overwritten E6 avare trace is excluded.

RUN
  python3 history_fields_scan.py          # E6 arm 4
  python3 history_fields_scan.py e5b      # E5b v13_A
Reads only; prints to stdout. Deterministic (files sorted).
"""

import collections
import glob
import json
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

# - click @(x,y) android.widget.Button "text" → result
RE_NODE = re.compile(r'^- (click|long_click) @\((-?\d+),(-?\d+)\) (\S+)(?: "(.*)")?(?: → .*)?$')
RE_TYPE = re.compile(r'^- type_text @\(')
# v13 list line:   12. Button "ident" @(x,y) ...
RE_LIST = re.compile(r'^\s+\d+\. (\S+)(?: "(.*?)")? @\((-?\d+),(-?\d+)\)')


def blocks(user):
    """(list block, history block) of a v13 prompt dump; history may be empty."""
    lst = user.split("UI elements:", 1)[1] if "UI elements:" in user else ""
    if "\nRecent:\n" in lst:
        lst, hist = lst.split("\nRecent:\n", 1)
        return lst, hist.split("\n\n", 1)[0]
    return lst, ""


def scan(path, C):
    with open(path, "rt", errors="replace") as fh:
        for line in fh:
            if not line.startswith('{"s"'):
                continue
            try:
                rec = json.loads(line)
            except ValueError:
                continue
            for call in rec.get("llm") or []:
                user = call.get("user")
                if not user:
                    continue
                C["prompts"] += 1
                lst, hist = blocks(user)
                if not hist:
                    continue
                C["prompts_with_history"] += 1
                listed = []
                for l in lst.split("\n"):
                    m = RE_LIST.match(l)
                    if m:
                        listed.append((m.group(1), m.group(2) or "", int(m.group(3)), int(m.group(4))))
                seen = set()
                for l in hist.split("\n"):
                    if RE_TYPE.match(l):
                        C["type_text_entries"] += 1
                        seen.add("type_text")
                        continue
                    m = RE_NODE.match(l)
                    if not m:
                        continue
                    cls, text = m.group(4), m.group(5) or ""
                    x, y = int(m.group(2)), int(m.group(3))
                    C["node_entries"] += 1
                    if "." in cls:
                        C["node_entries_full_class"] += 1
                        seen.add("full_class")
                    if text:
                        continue
                    C["node_entries_no_text"] += 1
                    seen.add("no_text")
                    simple = cls.rsplit(".", 1)[-1]
                    y_root = int(y * 1794 / 1920)
                    cands = [c for c in listed
                             if c[0] == simple and c[2] == x and abs(c[3] - y_root) <= 2]
                    if not cands:
                        C["no_text_unmatched"] += 1
                        continue
                    ident = cands[0][1]
                    if not ident:
                        C["no_text_list_empty"] += 1
                    elif ident.startswith("id="):
                        C["no_text_list_id"] += 1
                    else:
                        C["no_text_list_label"] += 1
                        seen.add("label_lost")
                for k in seen:
                    C["prompts_" + k] += 1


def main():
    e5b = len(sys.argv) > 1 and sys.argv[1] == "e5b"
    root, pat = (E5B_ROOT, E5B_GLOB) if e5b else (E6_ROOT, E6_GLOB)
    files = sorted(glob.glob(os.path.join(root, pat)))
    excluded = [f for f in files
                if any(a in f and b in f for a, b in EXCLUDE_APKS)]
    files = [f for f in files if f not in excluded]
    print("excluded (overwritten in place):", [os.path.relpath(f, root) for f in excluded])
    print("# inputs:", os.path.join(root, pat))
    C = collections.Counter()
    for f in files:
        scan(f, C)
    P = C["prompts"]

    def pct(n, d):
        return f"{n / d:.1%}" if d else "n/a"

    print(f"files: {len(files)}; prompts: {P}; with a Recent: history: {C['prompts_with_history']}")
    print(f"node-action history entries (click/long_click with a class): {C['node_entries']}")
    print(f"  full package class: {C['node_entries_full_class']} "
          f"({pct(C['node_entries_full_class'], C['node_entries'])}); "
          f"prompts showing one: {C['prompts_full_class']} ({pct(C['prompts_full_class'], P)})")
    print(f"  without text: {C['node_entries_no_text']} "
          f"({pct(C['node_entries_no_text'], C['node_entries'])}); "
          f"prompts showing one: {C['prompts_no_text']} ({pct(C['prompts_no_text'], P)})")
    print("  text-less entries matched to a line of the same prompt's list (lower bound):")
    print(f"    list shows text/content-description: {C['no_text_list_label']}; "
          f"prompts: {C['prompts_label_lost']} ({pct(C['prompts_label_lost'], P)})")
    print(f"    list shows id=: {C['no_text_list_id']}")
    print(f"    list shows no identifier: {C['no_text_list_empty']}")
    print(f"    unmatched (dialog, popup, widget gone, base other than full window): "
          f"{C['no_text_unmatched']}")
    print(f"type_text entries (no class, no widget text): {C['type_text_entries']}; "
          f"prompts showing one: {C['prompts_type_text']} ({pct(C['prompts_type_text'], P)})")


if __name__ == "__main__":
    main()
