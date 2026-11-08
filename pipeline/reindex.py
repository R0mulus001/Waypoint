#!/usr/bin/env python3
"""Rebuilds index.json from every published <cc>.json (fixes entries lost when two runs published at the same time)."""
import glob, json, os, sys
from datetime import datetime, timezone
src, out = sys.argv[1], sys.argv[2]
idx = {"countries": {}}
for f in sorted(glob.glob(os.path.join(src, "??.json"))):
    c = json.load(open(f, encoding="utf-8"))
    cities = c.get("cities", {})
    idx["countries"][c["country"]] = {
        "file": os.path.basename(f), "bytes": os.path.getsize(f), "version": c["version"],
        "cities": {k: {"name": v["name"], "photosBytes": v.get("photoPack", {}).get("bytes", 0), "photos": v.get("photoPack", {}).get("count", 0)}
                   for k, v in cities.items()}}
idx["version"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
json.dump(idx, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(len(idx["countries"]), "countries,", sum(len(v["cities"]) for v in idx["countries"].values()), "cities")
