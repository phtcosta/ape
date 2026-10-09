import json,sys
rows=[json.loads(l) for l in open(sys.argv[1]) if l.strip()]
n=e3=e6=0
for r in rows:
    cen=set(r["mop_activities_aug"])&set(r["manifest_activities"])
    for key,(tot,a3,a6) in zip(r["activity_list_keys"],r["lists"]["activity"]):
        if key in cen: n+=1; e3+=a3>3; e6+=a6>3
print("activityDist on launcher census (aug ∩ manifest):",n,">3@d<=3",e3,">3@d<=6",e6)
print("census size total", sum(len(set(r["mop_activities_aug"])&set(r["manifest_activities"])) for r in rows))
print("gap2 per app (blind, flagged):",sorted([(r["flagged_dist_blind"],r["flagged"],r["file"][:40]) for r in rows if r["flagged_dist_blind"]],reverse=True)[:10])
print("apps with blind>0:",sum(1 for r in rows if r["flagged_dist_blind"]))
print("select/drag per app:",sorted([(r["ev_flag"].get("select",0),r["ev_flag"].get("drag",0),r["file"][:40]) for r in rows if r["ev_flag"].get("select") or r["ev_flag"].get("drag")],reverse=True)[:8])
w=sorted([(sum(1 for n,a3,a6 in r["lists"]["widget"] if a3>3),len(r["lists"]["widget"]),r["file"][:40]) for r in rows],reverse=True)[:8]
print("widget lists >3@d<=3 per app:",w)
