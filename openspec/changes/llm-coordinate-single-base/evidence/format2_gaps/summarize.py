"""Aggregate measure.py output. Usage: python3 -I -B summarize.py results.jsonl"""
import json
import statistics as st
import sys

rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
print("documents", len(rows))


def pct(xs, q):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(q * len(xs)))]


jb = [r["json_bytes"] / 2**20 for r in rows]
ab = [r["artifact_bytes"] / 2**10 for r in rows]
rss = [r["peak_rss_mb"] for r in rows]
sec = [r["seconds"] for r in rows]
print("json MB median/p90/max", round(st.median(jb), 1), round(pct(jb, .9), 1), round(max(jb), 1))
print("artifact KB median/p90/max", round(st.median(ab), 1), round(pct(ab, .9), 1), round(max(ab), 1),
      max(rows, key=lambda r: r["artifact_bytes"])["file"])
print("peak RSS MB median/p90/max", st.median(rss), pct(rss, .9), max(rss))
print("seconds median/p90/max", st.median(sec), pct(sec, .9), max(sec))
big = [r for r in rows if r["json_bytes"] > 2**30]
for r in sorted(rows, key=lambda r: -r["json_bytes"])[:4]:
    print("  ", r["file"], r["json_bytes"] // 2**20, "MB ->", r["artifact_bytes"] // 1024, "KB,",
          r["peak_rss_mb"], "MB RSS,", r["seconds"], "s, ratio",
          round(r["peak_rss_mb"] * 2**20 / r["json_bytes"], 2))
ratios = [r["peak_rss_mb"] * 2**20 / r["json_bytes"] for r in rows if r["json_bytes"] > 100 * 2**20]
print("RSS/JSON ratio over docs >100MB: median", round(st.median(ratios), 2), "range",
      round(min(ratios), 2), round(max(ratios), 2))
print("complete flag:", {str(k): sum(1 for r in rows if r["complete"] == k) for k in {r["complete"] for r in rows}})
print("targets==0:", sum(1 for r in rows if r["targets"] == 0))

print("\n== gap 1: lists before the K=3 cut ==")
for kind in ("activity", "widget", "handler"):
    L = [l for r in rows for l in r["lists"][kind]]
    gt3_le3 = sum(1 for n, a3, a6 in L if a3 > 3)
    gt3_le6 = sum(1 for n, a3, a6 in L if a6 > 3)
    gt3 = sum(1 for n, a3, a6 in L if n > 3)
    apps = sum(1 for r in rows if any(a3 > 3 for n, a3, a6 in r["lists"][kind]))
    print(f"{kind}: lists {len(L)}; >3 targets total {gt3}; >3 at d<=3 {gt3_le3}; >3 at d<=6 {gt3_le6}; apps with a >3@d<=3 list {apps}")
    if L:
        a3s = sorted(a3 for n, a3, a6 in L if a3 > 3)
        if a3s:
            print("   targets at d<=3 when >3: median", st.median(a3s), "p90", pct(a3s, .9), "max", max(a3s))
# activityDist restricted to launcher-eligible keys (manifest activities)
# (lists[] are in sorted key order for activity, same as activity_list_keys)
n = e3 = e6 = 0
for r in rows:
    man = set(r["manifest_activities"])
    for key, (tot, a3, a6) in zip(r["activity_list_keys"], r["lists"]["activity"]):
        if key in man:
            n += 1
            e3 += a3 > 3
            e6 += a6 > 3
print(f"activityDist on manifest activities: {n}; >3 at d<=3 {e3}; >3 at d<=6 {e6}")

print("\n== gap 2: flagged widgets whose pairs sit only under events the jar does not read ==")
fl = sum(r["flagged"] for r in rows)
print("flagged widgets", fl, "in", sum(1 for r in rows if r["flagged"]), "apps")
print("pairs only under unread events (itemselected counted as READ):", sum(r["flagged_dist_blind"] for r in rows))
print("same, itemselected counted as unread (reported view):", sum(r["flagged_dist_blind_incl_itemselected"] for r in rows))
print("flags only under unread events:", sum(r["flagged_only_unread_events"] for r in rows))
evf, evd = {}, {}
for r in rows:
    for e, c in r["ev_flag"].items():
        evf[e] = evf.get(e, 0) + c
    for e, c in r["ev_dist"].items():
        evd[e] = evd.get(e, 0) + c
print("flagged events:", dict(sorted(evf.items(), key=lambda x: -x[1])))
print("dist events:", dict(sorted(evd.items(), key=lambda x: -x[1])))

print("\n== gap 3: WTG ==")
nowtg = [r for r in rows if r["wtg_edges"] == 0]
notrans = [r for r in nowtg if r["transitions"] == 0]
implicit = [r for r in nowtg if r["transitions"] > 0 and all(t.startswith("implicit") for t in r["transition_types"])]
other = [r for r in nowtg if r["transitions"] > 0 and r not in implicit]
print("wtgEdges==0:", len(nowtg), "; of them no transitions:", len(notrans), "; implicit only:", len(implicit), "; other:", len(other))
for r in other[:5]:
    print("   other:", r["file"], r["transition_types"])
print("orphan dialogs: total", sum(r["orphan_dialogs"] for r in rows), "in", sum(1 for r in rows if r["orphan_dialogs"]), "apps;",
      "in no-WTG apps", sum(r["orphan_dialogs"] for r in nowtg))
print("complete among no-transition docs:", {str(k): sum(1 for r in notrans if r["complete"] == k) for k in {r["complete"] for r in notrans}})

print("\n== gap 4: apps with no emitted widget ==")
now = [r for r in rows if r["widgets"] == 0]
print("no widget:", len(now), "; handlers total", sum(r["handlers"] for r in rows), "with dist", sum(r["handlers_with_dist"] for r in rows))
print("no-widget apps: handlers", sum(r["handlers"] for r in now), "with dist", sum(r["handlers_with_dist"] for r in now),
      "; with activityDist", sum(1 for r in now if r["activity_dist_keys"]), "; with neither", sum(1 for r in now if not r["handlers_with_dist"] and not r["activity_dist_keys"]))
print("no-widget apps with targets==0:", sum(1 for r in now if r["targets"] == 0))
