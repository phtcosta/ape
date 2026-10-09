"""Aggregate measure_k.py output. Usage: python3 -I -B summarize_k.py results_k.jsonl"""
import json
import statistics as st
import sys

rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
print("documents", len(rows))
for v in ("K3", "K5", "K10", "D3", "D6"):
    s = [r["sizes"][v] / 1024 for r in rows]
    big = max(rows, key=lambda r: r["sizes"][v])
    print(f"{v}: artifact KB median {st.median(s):.1f} p90 {sorted(s)[int(.9*len(s))]:.1f} max {max(s):.0f} ({big['file'][:35]}) total MB {sum(s)/1024:.1f}")

print("\nTargets at d<=3 in some list before the cut, absent at d<=3 from every list after K=3:")
tot_pre = tot_lost = apps = 0
lost_rows = []
for r in rows:
    pre = set().union(*map(set, r["pre"].values()))
    post = set().union(*map(set, r["post"].values()))
    lost = pre - post
    tot_pre += len(pre)
    tot_lost += len(lost)
    if lost:
        apps += 1
        lost_rows.append((len(lost), len(pre), r["targets"], r["file"][:40]))
print(f"  targets near (d<=3) somewhere: {tot_pre}; invisible after K=3: {tot_lost} ({100*tot_lost/max(tot_pre,1):.1f}%) in {apps} apps")
for x in sorted(lost_rows, reverse=True)[:10]:
    print("   ", x)
# per kind, only widget+handler (the action weight path)
tp = tl = 0
for r in rows:
    pre = set(r["pre"]["widget"]) | set(r["pre"]["handler"])
    post = set(r["post"]["widget"]) | set(r["post"]["handler"])
    tp += len(pre)
    tl += len(pre - post)
print(f"  action path (widget+handler): near {tp}; invisible {tl} ({100*tl/max(tp,1):.1f}%)")
