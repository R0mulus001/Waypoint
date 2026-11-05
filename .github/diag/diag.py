import json, math, os, re, sys, time, urllib.parse, urllib.request, datetime

UA = "Waypoint-diag/1.0 (https://github.com/R0mulus001/Waypoint)"
TOKEN = os.environ.get("MAPILLARY_TOKEN", "").strip()
MIN_YEAR = datetime.date.today().year - 15


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode())
    except Exception as e:
        print("   !! GET failed", url[:110], e)
        return {}
    finally:
        time.sleep(0.4)


def enc(s):
    return urllib.parse.quote(s, safe="")


def year_of(ii):
    t = (((ii.get("extmetadata") or {}).get("DateTimeOriginal") or {}).get("value")) or ""
    m = re.search(r"(?<!\d)(1[89]\d\d|20\d\d)(?!\d)", t)
    if m:
        return int(m.group(1)), "taken"
    ts = ii.get("timestamp", "")[:4]
    return (int(ts), "upload") if ts.isdigit() else (None, "?")


def info(titles):
    out = {}
    for i in range(0, len(titles), 50):
        ch = titles[i:i + 50]
        r = get("https://en.wikipedia.org/w/api.php?action=query&format=json&prop=imageinfo&iiprop=url%7Cmime%7Csize%7Ctimestamp%7Cextmetadata"
                "&iiextmetadatafilter=DateTimeOriginal&iiurlwidth=960&titles=" + enc("|".join(ch)))
        for pg in ((r.get("query") or {}).get("pages") or {}).values():
            ii = (pg.get("imageinfo") or [None])[0]
            if ii:
                out[pg["title"]] = ii
    return out


def place(title):
    print("\n=== " + title)
    r = get("https://en.wikipedia.org/w/api.php?action=query&format=json&redirects=1&prop=coordinates%7Cpageimages%7Cpageprops"
            "&piprop=thumbnail%7Cname&pithumbsize=960&ppprop=wikibase_item&titles=" + enc(title))
    pg = list(((r.get("query") or {}).get("pages") or {}).values())
    if not pg:
        print("   no page"); return
    pg = pg[0]
    c = (pg.get("coordinates") or [{}])[0]
    lat, lng = c.get("lat"), c.get("lon")
    print("   coords", lat, lng, "| lead file:", pg.get("pageimage"), "| qid:", (pg.get("pageprops") or {}).get("wikibase_item"))
    lead = pg.get("pageimage")
    if lead:
        ii = info(["File:" + lead.replace("_", " ")]).get("File:" + lead.replace("_", " "))
        if ii:
            y, how = year_of(ii)
            print("   LEAD  %s (%s %s) %sx%s %s -> %s" % (lead, how, y, ii.get("width"), ii.get("height"), ii.get("mime"),
                  "OK" if y and y >= MIN_YEAR else "REJECTED (old)"))
    qid = (pg.get("pageprops") or {}).get("wikibase_item")
    cat = None
    if qid:
        w = get("https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=sitelinks&sitefilter=commonswiki&ids=" + qid)
        t = (((w.get("entities") or {}).get(qid) or {}).get("sitelinks") or {}).get("commonswiki", {}).get("title", "")
        cat = t[len("Category:"):] if t.startswith("Category:") else None
    print("   commons category:", cat)
    if cat:
        r = get("https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=categorymembers&gcmtype=file&gcmsort=timestamp&gcmdir=desc"
                "&gcmlimit=60&gcmtitle=" + enc("Category:" + cat) + "&prop=imageinfo&iiprop=url%7Cmime%7Csize%7Ctimestamp%7Cextmetadata"
                "&iiextmetadatafilter=DateTimeOriginal&iiurlwidth=960")
        pages = ((r.get("query") or {}).get("pages") or {}).values()
        rows = []
        for p in pages:
            ii = (p.get("imageinfo") or [None])[0]
            if not ii: continue
            y, how = year_of(ii)
            rows.append((ii.get("timestamp"), p["title"], y, how, ii.get("width"), ii.get("height"), ii.get("mime")))
        rows.sort(reverse=True)
        print("   category members returned:", len(rows))
        for row in rows[:10]:
            print("     CAT %s | %s | %s %s | %sx%s %s" % (row[0][:10], row[1][:80], row[3], row[2], row[4], row[5], row[6]))
    if TOKEN and lat:
        mapillary(lat, lng)


def metres(a, b, c, d):
    r = 6371000.0
    dl = math.radians(c - a); dg = math.radians(d - b)
    h = math.sin(dl / 2) ** 2 + math.cos(math.radians(a)) * math.cos(math.radians(c)) * math.sin(dg / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def bearing(a, b, c, d):
    f1 = math.radians(a); f2 = math.radians(c); dl = math.radians(d - b)
    y = math.sin(dl) * math.cos(f2); x = math.cos(f1) * math.sin(f2) - math.sin(f1) * math.cos(f2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def mapillary(lat, lng):
    dlat = 0.00065; dlng = dlat / math.cos(math.radians(lat))
    since = (datetime.date.today().replace(year=datetime.date.today().year - 15)).isoformat() + "T00:00:00Z"
    url = ("https://graph.mapillary.com/images?access_token=" + TOKEN + "&limit=60&fields=id,thumb_1024_url,captured_at,compass_angle,"
           "computed_compass_angle,computed_geometry,geometry,is_pano&start_captured_at=" + since +
           "&bbox=%.6f,%.6f,%.6f,%.6f" % (lng - dlng, lat - dlat, lng + dlng, lat + dlat))
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            data = json.loads(r.read().decode()).get("data") or []
    except Exception as e:
        print("   !! mapillary failed:", e); return
    print("   mapillary raw:", len(data))
    kept = 0
    for o in data:
        g = (o.get("computed_geometry") or o.get("geometry") or {}).get("coordinates")
        if not g: continue
        d = metres(g[1], g[0], lat, lng)
        hd = o.get("computed_compass_angle", o.get("compass_angle"))
        off = abs(((bearing(g[1], g[0], lat, lng) - hd) % 360 + 540) % 360 - 180) if hd is not None else None
        ok = not o.get("is_pano") and 6 <= d <= 75 and off is not None and off <= 35
        if ok: kept += 1
        if ok and kept <= 4:
            print("     MAP keep d=%.0fm off=%.0f taken=%s https://www.mapillary.com/app/?pKey=%s" % (
                d, off, datetime.datetime.utcfromtimestamp(o.get("captured_at", 0) / 1000).date(), o.get("id")))
    print("   mapillary passing filter:", kept)


def route():
    print("\n=== walking route test (Stephansdom -> Karlskirche)")
    for base in ["https://routing.openstreetmap.de/routed-foot/route/v1/foot/", "https://router.project-osrm.org/route/v1/foot/"]:
        u = base + "16.3725,48.2085;16.3718,48.1982?overview=false"
        r = get(u)
        rt = (r.get("routes") or [{}])[0]
        print("   ", base.split("/")[2], "code:", r.get("code"), "distance m:", rt.get("distance"), "duration s:", rt.get("duration"))


if __name__ == "__main__":
    for t in sys.argv[1:]:
        place(t)
    route()
