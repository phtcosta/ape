#!/usr/bin/env python3
"""Task 17.2 trace check for llm-coordinate-single-base Part B (format-2 artifact, stamp, distance).

Used on the standalone cryptoapp runs and on the rv-experiment runs of smoke_17_2_run_aperv.py.

RUN
  python3 -I -B smoke_17_2_check.py <artifact.mop.json> distance=<trace> flag=<trace>

The MOP_DATA targets and handlers are checked against the artifact passed as the first argument.

A trace line is a sink record iff it begins with '{'; Logger lines begin with '[APE] '. Each trace
may be plain or gzipped. For each run it prints MOP_DATA, RUN_END counters, the decision-member
census (mopd, mops, ret, mop), the per-widget MOP picks, the launcher sequence, and the 17.2 checks
for the mode the run's RUN_START names.
"""
import collections
import gzip
import json
import re
import sys

RE_RID = re.compile(r"resource-id=[\w.]+:id/(\w+)")
RE_TRIGGER = re.compile(r"\[APE-RV\] Triggering activity: (\S+)")


def lines(path):
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            yield line.rstrip("\n")


def nearest(pairs, retired=()):
    ds = [d for i, d in pairs if i not in retired]
    return min(ds) if ds else None


def check(mode_label, path, artifact):
    records, triggers = [], []
    for line in lines(path):
        if line.startswith("{"):
            records.append(json.loads(line))
        else:
            m = RE_TRIGGER.search(line)
            if m:
                triggers.append(m.group(1))
    start = next(r for r in records if r.get("type") == "RUN_START")
    end = next((r for r in records if r.get("type") == "RUN_END"), None)
    mop_data = next(r for r in records if r.get("type") == "MOP_DATA")
    steps = [r for r in records if "dec" in r]
    mode = start["params"].get("ape.mopScoring", "flag")
    print("=" * 72)
    print("run %s: %s" % (mode_label, path))
    print("  RUN_START run_id=%s seed=%s preset=%s mopScoring=%s build=%s"
          % (start["run_id"], start["seed"], start["preset"], mode, start["build"]))
    print("  inert=%s" % start.get("inert"))
    print("  MOP_DATA %s" % {k: mop_data.get(k) for k in
                             ("status", "formatVersion", "targets", "handlers", "droppedPairs",
                              "flagged", "mopActivities", "sourceDigest")})
    print("  RUN_END %s" % (None if end is None else
                            {"reason": end.get("reason"), "steps": end.get("steps"),
                             "counters": end.get("counters")}))
    print("  step records: %d (with out: %d)" % (len(steps), sum("out" in r for r in steps)))

    census = collections.Counter()
    mopd = collections.Counter()
    mops = collections.Counter()
    srcs = collections.Counter()
    rets = []
    per_widget = collections.defaultdict(collections.Counter)
    for r in steps:
        dec = r["dec"]
        srcs[dec.get("src")] += 1
        for key in ("mop", "mopd", "mops", "ret", "mopBoost"):
            if key in dec:
                census[key] += 1
        if "mopd" in dec:
            mopd[dec["mopd"]] += 1
        if "mops" in dec:
            mops[dec["mops"]] += 1
        if "ret" in dec:
            rets.append((r["s"], dec["ret"]))
        m = RE_RID.search(dec.get("a", ""))
        if m and ("mops" in dec or "mop" in dec):
            action = dec["a"].split("@", 2)[1][:16] if "@" in dec["a"] else "?"
            key = "%s %s" % (m.group(1), re.sub(r"class=.*", "", action))
            per_widget[key]["picks"] += 1
            per_widget[key]["mops=" + str(dec.get("mops"))] += 1
            if "mop" in dec:
                per_widget[key]["mop=%s" % dec["mop"]] += 1
            if "mopd" in dec:
                per_widget[key]["mopd=%s" % dec["mopd"]] += 1
    print("  dec.src: %s" % dict(srcs.most_common()))
    print("  members present: %s" % dict(census))
    print("  mopd values: %s   mops values: %s" % (dict(mopd), dict(mops)))
    print("  ret steps: %s" % rets)
    print("  MOP picks by widget:")
    for key in sorted(per_widget):
        print("    %-55s %s" % (key, dict(per_widget[key])))

    print("  launcher sequence (%d): %s" % (len(triggers),
                                            [t.rsplit(".", 1)[-1] for t in triggers]))
    retired = set(i for _, ret in rets for i in ret)
    order = sorted(((nearest(p) if nearest(p) is not None else 99, a.rsplit(".", 1)[-1])
                    for a, p in artifact["activityDist"].items()))
    print("  activityDist order (static, none retired): %s" % order)
    print("  activityDist nearest at run end (99 = all retired; retired %s): %s" % (
        sorted(retired), sorted((nearest(p, retired) if nearest(p, retired) is not None else 99,
                                 a.rsplit(".", 1)[-1])
                                for a, p in artifact["activityDist"].items())))

    want_targets, want_handlers = artifact["targets"], len(artifact["handlers"])
    checks = [("MOP_DATA formatVersion 2", mop_data.get("formatVersion") == 2),
              ("MOP_DATA targets %d (artifact)" % want_targets,
               mop_data.get("targets") == want_targets),
              ("MOP_DATA handlers %d (artifact)" % want_handlers,
               mop_data.get("handlers") == want_handlers),
              ("MOP_DATA droppedPairs 0", mop_data.get("droppedPairs") == 0),
              ("RUN_END present", end is not None)]
    if mode == "distance":
        counters = (end or {}).get("counters", {})
        checks += [("RUN_END stampNodes > 0", counters.get("stampNodes", 0) > 0),
                   ("dec.mops s on a pick", mops.get("s", 0) > 0),
                   ("dec.mopd on MOP picks", census["mopd"] > 0
                    and all("mopd" in r["dec"] for r in steps if r["dec"].get("mop", 0) > 0)),
                   ("at least one dec.ret", len(rets) > 0)]
    else:
        checks += [("no dec.mopd", census["mopd"] == 0), ("no dec.ret", len(rets) == 0)]
    for name, ok in checks:
        print("  [%s] %s" % ("PASS" if ok else "FAIL", name))


def main():
    with open(sys.argv[1], encoding="utf-8") as handle:
        artifact = json.load(handle)
    for arg in sys.argv[2:]:
        label, _, path = arg.partition("=")
        check(label, path, artifact)


if __name__ == "__main__":
    main()
