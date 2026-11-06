"""A/B test of Waypoint's photo choice: A = what the app does now (English names, en.wikipedia),
B = same, plus local names and the local-language Wikipedia article. Writes contact sheets to out/."""
import datetime, io, json, math, os, re, sys, time, unicodedata, urllib.parse, urllib.request
from PIL import Image, ImageDraw, ImageFont

UA = "Waypoint-diag/1.0 (https://github.com/R0mulus001/Waypoint)"
TOKEN = os.environ.get("MAPILLARY_TOKEN", "").strip()
MIN_YEAR = datetime.date.today().year - 15
OUT = "out"
os.makedirs(OUT, exist_ok=True)
_last = [0.0]


def get(url, raw=False):
    gap = _last[0] + 0.4 - time.time()
    if gap > 0:
        time.sleep(gap)
    _last[0] = time.time()
    for k in range(3):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=30) as r:
                b = r.read()
                return b if raw else json.loads(b.decode())
        except Exception as e:
            err = e
            time.sleep(2 + 3 * k)
    print("   !! GET failed", url[:120], err)
    return None if raw else {}


def enc(s):
    return urllib.parse.quote(s, safe="")


def plain(s):
    s = unicodedata.normalize("NFD", s)
    s = re.sub(r"[̀-ͯ]+", "", s).replace("ł", "l").replace("Ł", "L")
    return s.lower()


GENERIC = set("""cafe caffe the and pod bar mleczny books lounge chocolate plac nowy cloth hall pierogarnia krakow wodka restaurant restaurante
gasthaus zum zur der die das wien vienna stalls food ristorante trattoria pizzeria pasticceria milano milan antica terrazza della dei del schwarzen
weissen church palace square museum park street bridge tower gate house garden gardens castle basilica cathedral national city old new saint royal
main great grand mosque synagogue chapel abbey monastery temple theatre theater opera library fortress fort memorial monument statue fountain
building centre center market station lake river island hill tomb cemetery gallery history historical natural science modern contemporary folk
ethnographic archaeological orthodox catholic evangelical lutheran reformed parish holy trinity assumption virgin mary nicholas george michael
peter paul john stephen francis anne barbara""".split())
# Local-language building words, same idea as the English ones (B only).
GENERIC_LOCAL = set("""kirche dom schloss platz palais museum strasse garten brucke turm haus kapelle kloster stift theater oper park markt brunnen
chiesa duomo palazzo piazza castello museo basilica torre ponte giardino teatro galleria fontana santa santo maria biserica palatul piata muzeul
parcul gradina teatrul catedrala manastirea kosciol katedra zamek rynek muzeum ulica brama wieza ogrod teatr bazylika kaplica sfantul sfanta
national nationale nazionale narodowe""".split())

JUNK = re.compile(r"(?<![a-z])(maps?|mapa|plans?|karte|logo|coat[ _]of[ _]arms|wappen|herb|diagram|menu|receipt|flags?|icon|symbol|signature|"
                  r"locator|seal|emblem|stamps?|banknotes?|coins?|chart|graph|poster|book[ _]cover|award|medal|scheme|location|button|arrow|"
                  r"sketch|drawings?|engraving|lithograph)(?![a-z])", re.I)
MAPW = re.compile(r"(stadt|lage|grund|uber|ubersichts|land|welt|orts|bezirks|strassen)(plan|karte|riss)|grundriss|(?<![a-z])atlas(?![a-z])|"
                  r"cartograph|location[ _-]?map|relief[ _-]?map|blank[ _-]?map|outline[ _-]?map", re.I)


def tokens(name, local=False):
    g = GENERIC | (GENERIC_LOCAL if local else set())
    return {t for t in re.split(r"[^a-z0-9]+", plain(name)) if len(t) >= 4 and t not in g}


def matches(title, toks):
    if not toks:
        return False
    t = plain(title)
    t = t + " " + t.replace("oe", "o").replace("ue", "u").replace("ae", "a")
    return any(k in t for k in toks)


def taken(ii):
    v = (((ii.get("extmetadata") or {}).get("DateTimeOriginal") or {}).get("value")) or ""
    m = re.search(r"(?<!\d)(1[89]\d\d|20\d\d)(?!\d)", v)
    return int(m.group(1)) if m else None


def year(ii):
    v = (((ii.get("extmetadata") or {}).get("DateTimeOriginal") or {}).get("value")) or ""
    m = re.search(r"(?<!\d)(1[89]\d\d|20\d\d)(?!\d)", v)
    if m:
        return int(m.group(1))
    ts = (ii.get("timestamp") or "")[:4]
    return int(ts) if ts.isdigit() else None


def usable(title, ii):
    w, h = ii.get("width", 0), ii.get("height", 0)
    y = year(ii)
    return (re.search("jpeg|webp", ii.get("mime", "")) and w >= 600 and h >= 400 and 0.6 <= w / max(h, 1) <= 2.6
            and not JUNK.search(title) and not MAPW.search(plain(title)) and y is not None and y >= MIN_YEAR)


BADCAT = re.compile(r"(?<![a-z])(paintings?|gemalde|dipinti|obrazy|engravings?|etchings?|woodcuts?|lithographs?|prints|drawings?|illustrations?|"
    r"manuscripts?|portraits?|artworks?|frescos?|frescoes|mosaics?|stained glass|tapestr\w*|miniatures|in art|historical images|historical photographs|"
    r"old photographs|postcards|scans?|maps?|plans|floor plans|cartograph\w*|territor\w*|bells?|glocken?|campane|dzwony|reliefs?|details?|"
    r"inscriptions?|plaques?|coins?|stamps?|documents?|signatures?|coats? of arms|heraldry|banners?|flags?|tombs?|epitaphs?|graves?|"
    r"people|politicians|ceremon\w*|press conferences?|logos?|diagrams?|charts?|reconstructions?|models?|3d renders?|protests?|demonstrations?|strikes|parades?|marathons?|concerts?)(?![a-z])")
GOODCAT = re.compile(r"quality images|featured pictures|valued images")


def cats_of(ii):
    return plain((((ii.get("extmetadata") or {}).get("Categories") or {}).get("value")) or "")


def photo_ok(title, ii, strict):
    if not usable(title, ii):
        return False
    return not (strict and BADCAT.search(cats_of(ii).replace("_", " ")))


def why_not(title, ii):
    w, h = ii.get("width", 0), ii.get("height", 0)
    y = year(ii)
    if not re.search("jpeg|webp", ii.get("mime", "")): return "type"
    if w < 600 or h < 400: return "small"
    if not 0.6 <= w / max(h, 1) <= 2.6: return "ratio"
    if JUNK.search(title) or MAPW.search(plain(title)): return "junk"
    if y is None or y < MIN_YEAR: return "old:%s" % y
    return "ok"


IIP = "&prop=imageinfo&iiprop=url%7Cmime%7Csize%7Ctimestamp%7Cextmetadata&iiextmetadatafilter=DateTimeOriginal%7CCategories&iiurlwidth=960"


def file_info(wiki, files):
    out = {}
    files = list(dict.fromkeys(files))
    for i in range(0, len(files), 50):
        r = get("https://%s.wikipedia.org/w/api.php?action=query&format=json%s&titles=%s" % (wiki, IIP, enc("|".join(files[i:i + 50]))))
        q = r.get("query") or {}
        norm = {n["to"]: n["from"] for n in q.get("normalized", [])}
        for pg in (q.get("pages") or {}).values():
            ii = (pg.get("imageinfo") or [None])[0]
            if ii:
                out[norm.get(pg["title"], pg["title"])] = ii
                out[pg["title"]] = ii
    return out


def article(wiki, title):
    """lead file name + all image file names of an article"""
    r = get("https://%s.wikipedia.org/w/api.php?action=query&format=json&redirects=1&prop=pageimages%%7Cpageprops%%7Cimages&imlimit=max"
            "&piprop=name&ppprop=wikibase_item&titles=%s" % (wiki, enc(title)))
    pg = list(((r.get("query") or {}).get("pages") or {}).values())
    if not pg or "missing" in pg[0]:
        return None, [], None
    pg = pg[0]
    imgs = [i["title"] for i in pg.get("images", []) if re.search(r"\.(jpe?g|webp)$", i["title"], re.I) and not JUNK.search(i["title"])]
    return pg.get("pageimage"), imgs[:30], (pg.get("pageprops") or {}).get("wikibase_item")


def wikidata(qid, lang):
    r = get("https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=claims%%7Csitelinks%%7Clabels&languages=%s%%7Cen&ids=%s" % (lang, qid))
    e = (r.get("entities") or {}).get(qid) or {}
    try:
        p373 = e["claims"]["P373"][0]["mainsnak"]["datavalue"]["value"]
    except Exception:
        p373 = ""
    sl = e.get("sitelinks") or {}
    link = (sl.get("commonswiki") or {}).get("title", "")
    cat = p373 or (link[9:] if link.startswith("Category:") else None)
    alias = link if link and not link.startswith("Category:") else ""
    local_title = (sl.get(lang + "wiki") or {}).get("title")
    local_label = ((e.get("labels") or {}).get(lang) or {}).get("value", "")
    try:
        p18 = e["claims"]["P18"][0]["mainsnak"]["datavalue"]["value"]
    except Exception:
        p18 = ""
    return cat, alias, local_title, local_label, p18


def category_photos(cat, toks, mx=6, strict=False):
    r = get("https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=categorymembers&gcmtype=file&gcmsort=timestamp&gcmdir=desc"
            "&gcmlimit=80&gcmtitle=" + enc("Category:" + cat) + IIP)
    rows = []
    for pg in ((r.get("query") or {}).get("pages") or {}).values():
        ii = (pg.get("imageinfo") or [None])[0]
        if not ii:
            continue
        t = pg["title"]
        if not photo_ok(t, ii, strict) or (strict and taken(ii) is None) or max(ii.get("width", 0), ii.get("height", 0)) < 1200:
            continue
        rows.append((matches(t, toks) + (2 if strict and GOODCAT.search(cats_of(ii)) else 0), ii.get("timestamp", ""), ii.get("thumburl") or ii.get("url"), t))
    rows.sort(key=lambda r: (r[0], r[1]), reverse=True)
    return [(r[2], "cat" + ("*" if r[0] else "") + ("Q" if r[0] >= 2 else ""), r[3]) for r in rows[:mx]]


def commons_search(term, limit=14, strict=False):
    r = get("https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=%d&gsrsearch=%s%s"
            % (limit, enc("filetype:bitmap " + term), IIP))
    pages = sorted(((r.get("query") or {}).get("pages") or {}).values(), key=lambda p: p.get("index", 999))
    out = []
    for pg in pages:
        ii = (pg.get("imageinfo") or [None])[0]
        if ii and photo_ok(pg["title"], ii, strict):
            out.append((ii.get("thumburl") or ii.get("url"), pg["title"]))
    return out


def metres(a, b, c, d):
    dl, dg = math.radians(c - a), math.radians(d - b)
    h = math.sin(dl / 2) ** 2 + math.cos(math.radians(a)) * math.cos(math.radians(c)) * math.sin(dg / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(h))


def bearing(a, b, c, d):
    f1, f2, dl = math.radians(a), math.radians(c), math.radians(d - b)
    return (math.degrees(math.atan2(math.sin(dl) * math.cos(f2), math.cos(f1) * math.sin(f2) - math.sin(f1) * math.cos(f2) * math.cos(dl))) + 360) % 360


def mapillary(lat, lng, mx):
    if not TOKEN or mx <= 0:
        return []
    dlat = 0.00065
    dlng = dlat / math.cos(math.radians(lat))
    since = datetime.date.today().replace(year=datetime.date.today().year - 15).isoformat() + "T00:00:00Z"
    r = get("https://graph.mapillary.com/images?access_token=%s&limit=100&fields=id,thumb_1024_url,captured_at,compass_angle,computed_compass_angle,"
            "computed_geometry,geometry,is_pano&start_captured_at=%s&bbox=%.6f,%.6f,%.6f,%.6f" % (TOKEN, since, lng - dlng, lat - dlat, lng + dlng, lat + dlat))
    keep = []
    for o in r.get("data") or []:
        g = (o.get("computed_geometry") or o.get("geometry") or {}).get("coordinates")
        if not g or o.get("is_pano"):
            continue
        d = metres(g[1], g[0], lat, lng)
        hd = o.get("computed_compass_angle", o.get("compass_angle"))
        if hd is None or d < 6 or d > 50:
            continue
        off = abs(((bearing(g[1], g[0], lat, lng) - hd) % 360 + 540) % 360 - 180)
        if off > 25:
            continue
        keep.append((o.get("captured_at", 0), o.get("thumb_1024_url"), "map %.0fm" % d))
    keep.sort(reverse=True)
    return [(k[1], k[2], "") for k in keep[:mx]]


def pick(p, city, variant):
    """Returns list of (url, source, file title) following Photos.kt; variant B adds local names/article."""
    picked, seen, notes = [], set(), []

    def add(url, src, t=""):
        n = plain(urllib.parse.unquote(url.split("?")[0].rsplit("/", 1)[-1].split("px-", 1)[-1])).replace(" ", "_")
        if n and n not in seen:
            seen.add(n)
            picked.append((url, src, t))

    lead, imgs, qid = article("en", p["title"])
    cat = alias = ltitle = llabel = p18 = None
    strict = variant == "C"
    if qid:
        cat, alias, ltitle, llabel, p18 = wikidata(qid, city["lang"])
    toks = tokens(p["title"].split(" (")[0]) | tokens(p["title"].replace("_", " ")) | tokens(cat or "") | tokens(alias or "")
    llead, limgs = None, []
    if variant in "BC":
        toks |= tokens(llabel or "", True) | tokens(ltitle or "", True)
        if ltitle:
            llead, limgs, _ = article(city["lang"], ltitle)
    info = file_info("en", (["File:" + lead.replace("_", " ")] if lead else []) + imgs + (["File:" + p18] if p18 and strict else []))
    rest = []  # (quality, order, url, src, title) for C ranking

    def consider(f, ii, src, need_match=True):
        if not ii or (need_match and not matches(f, toks)) or not photo_ok(f, ii, strict):
            return False
        if strict and need_match and taken(ii) is None:
            return False
        if strict and src not in ("lead", "P18", "LEAD-" + city["lang"]):
            rest.append((1 if GOODCAT.search(cats_of(ii)) else 0, len(rest), ii.get("thumburl"), src + ("Q" if GOODCAT.search(cats_of(ii)) else ""), f))
        else:
            add(ii.get("thumburl"), src, f)
        return True

    if lead:
        ii = info.get("File:" + lead.replace("_", " "))
        if not consider("File:" + lead, ii, "lead", False) and ii:
            notes.append("en lead rejected (%s)" % why_not(lead, ii))
    if strict and p18:
        ii = info.get("File:" + p18)
        if not consider("File:" + p18, ii, "P18", False) and ii:
            notes.append("P18 rejected (%s)" % why_not(p18, ii))
    for f in imgs:
        consider(f, info.get(f), "art")
    if variant in "BC" and ltitle:
        linfo = file_info(city["lang"], (["File:" + llead.replace("_", " ")] if llead else []) + limgs)
        if llead:
            ii = linfo.get("File:" + llead.replace("_", " ")) or next((v for k, v in linfo.items() if k.endswith(":" + llead.replace("_", " "))), None)
            if not consider("File:" + llead, ii, "LEAD-" + city["lang"], False) and ii:
                notes.append("%s lead rejected (%s)" % (city["lang"], why_not(llead, ii)))
        for f in limgs:
            consider(f, linfo.get(f), "art-" + city["lang"])
    rest.sort(key=lambda r: (-r[0], r[1]))
    for r in rest:
        add(r[2], r[3], r[4])
    if len(picked) < 4 and cat:
        for u, s, t in category_photos(cat, toks, strict=strict):
            add(u, s, t)
    if len(picked) < 3:
        if variant in "BC":
            term = (llabel or p["title"].split(" (")[0]) + " " + city["local"]
        else:
            term = p["title"].split(" (")[0] + " " + city["en"]
        for u, t in commons_search(term, strict=strict):
            if matches(t, toks):
                add(u, "search", t)
    wiki = picked[:6]
    room = 0 if len(wiki) >= 4 else min(6 - len(wiki), 2)
    street = mapillary(p["lat"], p["lng"], room)
    final = wiki[:6 - len(street)] + street
    return final, dict(qid=qid, cat=cat, alias=alias, local=ltitle, label=llabel, toks=sorted(toks), notes=notes)


EXCL = re.compile(r"district|borough|neighbourhood|neighborhood|quarter of|metro station|railway station|tram|university|company|football|"
                  r"school|hospital|street in|road in|hotel|embassy|ministry|bank|headquarters|stadium|club", re.I)


FIXED = {
    "vienna": ["St. Stephen's Cathedral, Vienna", "Schönbrunn Palace", "Hofburg", "Belvedere, Vienna", "Karlskirche", "Albertina",
               "Austrian Parliament Building", "Café Central", "Kunsthistorisches Museum", "Vienna State Opera", "Votivkirche", "Hundertwasserhaus"],
    "milan": ["Milan Cathedral", "Basilica of Sant'Ambrogio", "Castello Sforzesco", "Galleria Vittorio Emanuele II", "Santa Maria delle Grazie, Milan",
              "Biblioteca Ambrosiana", "Basilica of Sant'Eustorgio", "Bosco Verticale", "La Scala", "Navigli"],
    "krakow": ["Wawel Castle", "St. Mary's Basilica, Kraków", "Cloth Hall, Kraków", "Kraków Barbican", "Wawel Cathedral", "Collegium Maius",
               "Church of Saints Peter and Paul, Kraków", "Schindler's Factory"],
    "bucharest": ["Palace of the Parliament", "Romanian Athenaeum", "Stavropoleos Monastery", "Arcul de Triumf", "Carol Park",
                  "Cotroceni Palace", "Lipscani", "Revolution Square, Bucharest"],
}


def fixed(city):
    out = []
    for t in FIXED[city["key"]]:
        r = get("https://en.wikipedia.org/w/api.php?action=query&format=json&redirects=1&prop=coordinates&titles=" + enc(t))
        pg = list(((r.get("query") or {}).get("pages") or {}).values())
        c = (pg[0].get("coordinates") or [{}])[0] if pg else {}
        if c:
            out.append(dict(title=pg[0]["title"], lat=c["lat"], lng=c["lon"], desc="", views=0))
    return out


def sights(city, n):
    if os.environ.get("FIXED"):
        return fixed(city)
    g = get("https://en.wikipedia.org/w/api.php?action=query&format=json&list=geosearch&gsnamespace=0&gsradius=10000&gslimit=500&gscoord=%f%%7C%f"
            % (city["lat"], city["lng"]))
    geo = (g.get("query") or {}).get("geosearch") or []
    out = []
    for i in range(0, len(geo), 50):
        ch = geo[i:i + 50]
        r = get("https://en.wikipedia.org/w/api.php?action=query&format=json&prop=pageviews%%7Cdescription%%7Cpageprops&pvipdays=30&ppprop=disambiguation&pageids=%s"
                % "%7C".join(str(x["pageid"]) for x in ch))
        pages = (r.get("query") or {}).get("pages") or {}
        for gs in ch:
            pg = pages.get(str(gs["pageid"])) or {}
            if "disambiguation" in (pg.get("pageprops") or {}):
                continue
            t, d = gs["title"], pg.get("description", "")
            if t.lower() == city["en"].lower() or t.startswith("List of") or not d or EXCL.search(d):
                continue
            v = sum(x or 0 for x in (pg.get("pageviews") or {}).values())
            out.append(dict(title=t, lat=gs["lat"], lng=gs["lon"], desc=d, views=v))
    out.sort(key=lambda s: -s["views"])
    return out[:n]


def thumb(url):
    if not url:
        return None
    u = re.sub(r"/(\d+)px-", "/330px-", url) if "upload.wikimedia.org" in url else url
    b = get(u, raw=True)
    if not b:
        return None
    try:
        im = Image.open(io.BytesIO(b)).convert("RGB")
        im.thumbnail((220, 165))
        return im
    except Exception:
        return None


def sheet(city, rows):
    W, CELL, H = 6 * 230 + 10, 230, 205
    img = Image.new("RGB", (W * 2 + 20, 40 + len(rows) * (H + 30)), "white")
    d = ImageDraw.Draw(img)
    try:
        f = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 14)
        fb = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 16)
    except Exception:
        f = fb = ImageFont.load_default()
    d.text((10, 10), "LEFT " + os.environ.get("LEFT", "A"), fill="black", font=fb)
    d.text((W + 30, 10), "RIGHT " + os.environ.get("RIGHT", "B"), fill="black", font=fb)
    y = 40
    for name, a, b in rows:
        d.text((10, y), name, fill="#b00", font=fb)
        for col, lst in ((0, a), (1, b)):
            x0 = 10 + col * (W + 20)
            for k, (u, src, t) in enumerate(lst):
                im = thumb(u)
                x = x0 + k * CELL
                if im:
                    img.paste(im, (x, y + 22))
                d.text((x, y + 22 + 168), src[:28], fill="#333", font=f)
            if not lst:
                d.text((x0, y + 60), "(no photos)", fill="#888", font=fb)
        y += H + 30
    img.save(os.path.join(OUT, city["key"] + ".jpg"), quality=72)


CITIES = {
    "vienna": dict(key="vienna", en="Vienna", local="Wien", lang="de", lat=48.2083, lng=16.3731),
    "milan": dict(key="milan", en="Milan", local="Milano", lang="it", lat=45.4642, lng=9.1900),
    "krakow": dict(key="krakow", en="Kraków", local="Kraków", lang="pl", lat=50.0614, lng=19.9366),
    "bucharest": dict(key="bucharest", en="Bucharest", local="București", lang="ro", lat=44.4268, lng=26.1025),
}

if __name__ == "__main__":
    n = int(os.environ.get("N", "14"))
    report = []
    for key in sys.argv[1:]:
        city = CITIES[key]
        print("\n######## " + city["en"])
        rows = []
        for p in sights(city, n):
            a, ma = pick(p, city, os.environ.get("LEFT", "A"))
            b, mb = pick(p, city, os.environ.get("RIGHT", "B"))
            print("\n== %s  (%d views)  qid=%s cat=%s local=%s label=%s" % (p["title"], p["views"], ma["qid"], ma["cat"], mb["local"], mb["label"]))
            print("   A:", [s for _, s, _ in a], ma["notes"])
            print("   B:", [s for _, s, _ in b], mb["notes"])
            for u, s, t in b:
                print("      B %-10s %s" % (s, t[:90]))
            report.append(dict(city=key, place=p["title"], A=[[s, t] for _, s, t in a], B=[[s, t] for _, s, t in b], metaA=ma, metaB=mb))
            rows.append((p["title"], a, b))
        sheet(city, rows)
    json.dump(report, open(os.path.join(OUT, "report.json"), "w"), indent=1, ensure_ascii=False)
