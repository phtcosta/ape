"""Measure the format-2 artifact gaps over the round-A gh120 documents (read only).

Usage: python3 -I -B measure.py <derive module dir> <documents dir> <out jsonl>
Each document is derived in a fresh child process exactly as tool.py does it
(read bytes, digest, json.loads, derive, serialize), with _cut_pairs wrapped to
record every list before the K cut. Nothing is written next to the documents.
"""
import json
import multiprocessing as mp
import os
import resource
import sys
import time

READ_EVENTS = {"click", "longclick", "itemselected", "scroll"}  # MopScorer.eventTypeOf


def bucket(minima):
    ds = sorted(minima.values())
    return [len(ds), sum(1 for d in ds if d <= 3), sum(1 for d in ds if d <= 6)]


def work(args):
    mod_dir, path = args
    sys.path.insert(0, mod_dir)
    import derive_mop_artifact as dm

    lists = {"widget": [], "activity": [], "handler": []}
    ctx = {"where": None, "key": None}
    real_cut = dm._cut_pairs

    def cut(minima):
        lists[ctx["where"]].append(bucket(minima))
        return real_cut(minima)

    def wrap(name, where):
        real = getattr(dm, name)

        def inner(*a, **k):
            ctx["where"] = where
            return real(*a, **k)

        setattr(dm, name, inner)

    dm._cut_pairs = cut
    wrap("_emit_widgets", "widget")
    wrap("_emit_activity_dist", "activity")
    wrap("_emit_handlers", "handler")

    t0 = time.time()
    with open(path, "rb") as f:
        raw = f.read()
    digest = dm.digest_of(raw)
    doc = json.loads(raw)
    n_transitions = len(doc.get("transitions") or [])
    trans_types = {}
    for t in doc.get("transitions") or []:
        if isinstance(t, dict):
            for ev in t.get("events") or []:
                et = str(ev.get("type") if isinstance(ev, dict) else "?")
                trans_types[et] = trans_types.get(et, 0) + 1
    manifest_acts = [a.get("className") or a.get("name")
                     for a in (doc.get("components") or {}).get("activities") or []
                     if isinstance(a, dict)]
    complete = doc.get("complete")
    art = dm.derive(doc, os.path.basename(path), digest)
    payload = dm.serialize_canonical(art)
    secs = time.time() - t0
    del doc, raw

    # Widgets: flagged ones and the events their flags and pairs sit under
    flagged = 0
    blind = 0  # flagged, pairs only under events the jar never asks for distance
    blind_itemsel = 0  # same but counting itemselected as unread (the reported view)
    flag_only_unread = 0  # every flagged event outside READ_EVENTS
    ev_flag = {}
    ev_dist = {}
    widgets = 0
    for acts in art["widgets"].values():
        for w in acts.values():
            widgets += 1
            mop = w.get("mop") or {}
            fl = [e for e, v in mop.items() if v != "none"]
            dist = w.get("dist") or {}
            for e in fl:
                ev_flag[e] = ev_flag.get(e, 0) + 1
            for e in dist:
                ev_dist[e] = ev_dist.get(e, 0) + 1
            if not fl:
                continue
            flagged += 1
            if fl and all(e not in READ_EVENTS for e in fl):
                flag_only_unread += 1
            if dist and not any(e in READ_EVENTS for e in dist):
                blind += 1
            if dist and not any(e in READ_EVENTS - {"itemselected"} for e in dist):
                blind_itemsel += 1

    act_keys = set(art["activityDist"])
    rss_kb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return {
        "file": os.path.basename(path),
        "json_bytes": os.path.getsize(path),
        "artifact_bytes": len(payload),
        "seconds": round(secs, 2),
        "peak_rss_mb": round(rss_kb / 1024, 1),
        "complete": complete,
        "targets": art["targets"],
        "widgets": widgets,
        "flagged": flagged,
        "flagged_dist_blind": blind,
        "flagged_dist_blind_incl_itemselected": blind_itemsel,
        "flagged_only_unread_events": flag_only_unread,
        "ev_flag": ev_flag,
        "ev_dist": ev_dist,
        "handlers": len(art["handlers"]),
        "handlers_with_dist": sum(1 for h in art["handlers"].values() if "dist" in h),
        "wtg_edges": art["stats"]["wtgEdges"],
        "transitions": n_transitions,
        "transition_types": trans_types,
        "orphan_dialogs": art["stats"]["orphanDialogs"],
        "activity_dist_keys": len(act_keys),
        "activity_dist_manifest_keys": len(act_keys & set(manifest_acts)),
        "lists": lists,
        "activity_list_keys": sorted(act_keys),
        "manifest_activities": manifest_acts,
        "mop_activities_aug": art["mopActivitiesAugmented"],
    }


def main(mod_dir, docs_dir, out):
    files = sorted((os.path.join(docs_dir, f) for f in os.listdir(docs_dir)
                    if f.endswith(".apk.json")), key=os.path.getsize, reverse=True)
    done = set()
    if os.path.exists(out):
        with open(out) as f:
            done = {json.loads(l)["file"] for l in f if l.strip()}
    todo = [(mod_dir, p) for p in files if os.path.basename(p) not in done]
    with mp.get_context("spawn").Pool(6, maxtasksperchild=1) as pool, open(out, "a") as o:
        for r in pool.imap_unordered(work, todo):
            o.write(json.dumps(r) + "\n")
            o.flush()
            print(r["file"], r["json_bytes"] // 2**20, "MB", r["seconds"], "s",
                  r["peak_rss_mb"], "MB", flush=True)


if __name__ == "__main__":
    main(*sys.argv[1:4])
