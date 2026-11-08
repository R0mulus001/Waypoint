#!/usr/bin/env python3
"""
Packs the built cities of one country for the app:
  <cc>.json            all cities' places and texts (small; the app downloads it when you pick the country)
  <cc>-<city>.zip      the photos of one city (the app downloads it for a trip, then they work offline)
  index.json           what's ready: countries, cities, sizes, versions (merged with the one already published)
"""
import json
import os
import sys
import zipfile
from datetime import date, datetime, timezone

import requests

out, dist, cc = sys.argv[1], sys.argv[2], sys.argv[3].upper()
os.makedirs(dist, exist_ok=True)
old_index = json.load(open(sys.argv[4])) if len(sys.argv) > 4 and os.path.exists(sys.argv[4]) else {"countries": {}}
old_country = json.load(open(sys.argv[5])) if len(sys.argv) > 5 and os.path.exists(sys.argv[5]) else {}

UA = {"User-Agent": "WaypointDataBuilder/1.0 (https://github.com/R0mulus001/Waypoint)"}
holidays = {}
for y in (date.today().year, date.today().year + 1):
    try:
        for h in requests.get(f"https://date.nager.at/api/v3/PublicHolidays/{y}/{cc}", headers=UA, timeout=30).json():
            if h.get("global", True):
                holidays[h["date"]] = h.get("name") or h.get("localName")
    except Exception as e:
        print("holidays", y, e)

cities = dict(old_country.get("cities", {}))
built = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
for key in sorted(os.listdir(out)):
    if not key.startswith(cc.lower() + "-"):
        continue
    d = os.path.join(out, key)
    c = json.load(open(os.path.join(d, "city.json"), encoding="utf-8"))
    zname = f"{key}.zip"
    pd = os.path.join(d, "photos")
    files = sorted(os.listdir(pd)) if os.path.isdir(pd) else []  # a city with no photos has no folder in the artifact
    with zipfile.ZipFile(os.path.join(dist, zname), "w", zipfile.ZIP_STORED) as z:
        for f in files:
            z.write(os.path.join(pd, f), f)
    c["photoPack"] = {"file": zname, "bytes": os.path.getsize(os.path.join(dist, zname)), "count": len(files), "version": c["built"]}
    cities[key] = c
    print(f"{key}: {len(files)} photos, {c['photoPack']['bytes'] / 1e6:.1f} MB")

country = {"country": cc, "version": built, "holidays": holidays, "cities": cities}
cj = os.path.join(dist, f"{cc.lower()}.json")
with open(cj, "w", encoding="utf-8") as f:
    json.dump(country, f, ensure_ascii=False, separators=(",", ":"))
idx = old_index
idx.setdefault("countries", {})[cc] = {
    "file": f"{cc.lower()}.json", "bytes": os.path.getsize(cj), "version": built,
    "cities": {k: {"name": v["name"], "photosBytes": v["photoPack"]["bytes"], "photos": v["photoPack"]["count"]} for k, v in cities.items()}}
idx["version"] = built
with open(os.path.join(dist, "index.json"), "w", encoding="utf-8") as f:
    json.dump(idx, f, ensure_ascii=False, indent=1)
print(f"{cc}: {len(cities)} cities, data {os.path.getsize(cj) / 1e3:.0f} KB")
