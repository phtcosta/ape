"""Dedupe identical (activity, option set) items and cap items per app. Writes data/{split}_bal.jsonl."""
import json, random
from collections import defaultdict
CAP = {"train": 600, "val": 300, "test": 300}
rng = random.Random(20260929)
for sp, cap in CAP.items():
    per, seen = defaultdict(list), set()
    for line in open(f"data/{sp}.jsonl"):
        r = json.loads(line)
        k = (r["app"], r["state"]["activity"], tuple(sorted(r["questions"]["next"]["criteria"].values())))
        if k in seen:
            continue
        seen.add(k)
        per[r["app"]].append(line)
    out = []
    for app, rows in per.items():
        rng.shuffle(rows)
        out += rows[:cap]
    rng.shuffle(out)
    open(f"data/{sp}_bal.jsonl", "w").writelines(out)
    print(sp, "items", len(out), "apps", len(per))
