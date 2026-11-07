#!/usr/bin/env python3
"""
Builds Waypoint's ready-made data for one city, on GitHub Actions (no time limit, unlike the phone):

  places   sights, museums and parks (Wikipedia + Wikidata), places to eat and drink (OpenStreetMap)
  texts    the Wikipedia intro of each sight
  photos   5-10 per sight/museum (up to 10 for parks), up to 5 per place to eat (outside + inside),
           only photos taken in the last 15 years, from Wikimedia Commons, Mapillary and Flickr (if a key is set),
           every one checked by an image model (CLIP): people, maps, paintings, documents, close-ups are rejected.

Output: <out>/<key>/city.json, <out>/<key>/photos/*.webp, <out>/<key>/review/*.jpg (contact sheets for checking).
The phone app turns city.json into a guide with the same code it uses for guides it builds itself.
"""
import argparse
import io
import json
import math
import os
import re
import sys
import time
import unicodedata
from datetime import date, datetime, timezone

import requests
from PIL import Image, ImageDraw, ImageFont, ImageOps

UA = "WaypointDataBuilder/1.0 (personal city-guide app; https://github.com/R0mulus001/Waypoint)"
S = requests.Session()
S.headers["User-Agent"] = UA
MIN_YEAR = date.today().year - 15
WAITS = [2, 5, 10, 20, 40, 60, 90]


def log(*a):
    print(*a, flush=True)


def _req(method, url, params=None, data=None, raw=False, timeout=90):
    last = None
    for attempt, wait in enumerate(WAITS):
        try:
            r = S.request(method, url, params=params, data=data, timeout=timeout)
            if r.status_code == 429 or r.status_code >= 500:
                ra = r.headers.get("Retry-After", "")
                w = int(ra) if ra.isdigit() else wait
                log(f"  busy {r.status_code} {url[:70]} wait {w}s")
                time.sleep(min(w, 120))
                last = RuntimeError(f"HTTP {r.status_code}")
                continue
            if r.status_code == 404 and raw:
                return None
            r.raise_for_status()
            return r.content if raw else r.json()
        except (requests.ConnectionError, requests.Timeout, requests.exceptions.ChunkedEncodingError) as e:
            last = e
            log(f"  network {type(e).__name__} {url[:70]} wait {wait}s")
            time.sleep(wait)
    raise RuntimeError(f"failed after retries: {url} ({last})")


def get(url, params=None, raw=False, timeout=90):
    return _req("GET", url, params=params, raw=raw, timeout=timeout)


def post(url, data, timeout=200):
    return _req("POST", url, data=data, timeout=timeout)


_last_api = [0.0]


def wapi(host, **params):
    """MediaWiki API (Wikipedia, Commons, Wikidata), at most ~6 requests a second, as Wikimedia asks of bots."""
    gap = _last_api[0] + 0.15 - time.time()
    if gap > 0:
        time.sleep(gap)
    _last_api[0] = time.time()
    params = {"format": "json", "formatversion": "2", **params}
    return get(f"https://{host}/w/api.php", params=params)


def wapi_all(host, cont_keys=None, **params):
    """Follows 'continue' and merges the page lists (images, pageviews arrive in parts)."""
    pages, extra = {}, {}
    cont = {}
    for _ in range(30):
        r = wapi(host, **params, **cont)
        q = r.get("query", {})
        for k in ("normalized", "redirects"):
            extra.setdefault(k, []).extend(q.get(k, []))
        for p in q.get("pages", []):
            key = p.get("title")
            have = pages.get(key)
            if have is None:
                pages[key] = p
            else:
                for k, v in p.items():
                    if isinstance(v, list) and isinstance(have.get(k), list):
                        have[k].extend(v)
                    elif isinstance(v, dict) and isinstance(have.get(k), dict):
                        have[k].update(v)
                    elif k not in have:
                        have[k] = v
        if "continue" not in r:
            break
        cont = r["continue"]
    return pages, extra


def resolve(extra, title):
    for k in ("normalized", "redirects"):
        for o in extra.get(k, []):
            if o.get("from") == title:
                title = o.get("to")
    return title


# ---------- text helpers (same rules as the app) ----------

def plain(s):
    s = unicodedata.normalize("NFD", s)
    s = re.sub(r"\p{M}+" if False else r"[̀-ͯ]+", "", s)
    return s.replace("ł", "l").replace("Ł", "L").replace("ø", "o").replace("Ø", "O").replace("æ", "ae").replace("å", "a").lower()


def slug(s):
    s = unicodedata.normalize("NFD", s)
    s = re.sub(r"[̀-ͯ]+", "", s).replace("ł", "l").lower()
    return re.sub(r"[^a-z0-9]+", "-", s).strip("-")


def km(a, b, c, d):
    r = 6371.0
    dl, dn = math.radians(c - a), math.radians(d - b)
    h = math.sin(dl / 2) ** 2 + math.cos(math.radians(a)) * math.cos(math.radians(c)) * math.sin(dn / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def bearing(a, b, c, d):
    f1, f2, dl = math.radians(a), math.radians(c), math.radians(d - b)
    y = math.sin(dl) * math.cos(f2)
    x = math.cos(f1) * math.sin(f2) - math.sin(f1) * math.cos(f2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


I = re.IGNORECASE
EXCLUDE = re.compile(
    r"station|railway|metro|subway|airport|school|gymnasium|university|college|academy|hospital|clinic|company|corporation|\bbank\b|hotel|"
    r"embassy|consulate|ministry|\bcourt\b|prison|stadium|football|sports|\bclub\b|skyscraper|office building|apartment|residential|"
    r"shopping cent|\bmall\b|cinema|radio|television|newspaper|publisher|institute|agency|organi[sz]ation|association|political party|"
    r"police|power station|factory|motorway|highway|list of|demolished|\bband\b|album|\bfilm\b|novel|electoral|constituency|diocese|"
    r"regime|government|dictatorship|\bwar\b|battle|revolution|earthquake|history of|historical period|\bera\b|empire|dynasty|treaty|"
    r"festival|election|protest|disaster|siege|uprising|movement|\bevent\b|culture of|cuisine|language|dialect|ethnic|tradition|military|"
    r"basketball|handball|volleyball|rugby|competition|tournament|championship|stock exchange|murder|assassination|killing|"
    r"regicide|\battack\b|bombing|massacre|incident|\bcrash\b|\bfire of\b|riot|scandal|monarchy|kingdom|republic|principality|"
    r"duchy|sovereign state|historical state|former country|country in|union of", I)
AREA = re.compile(r"^(the )?(capital|city|town|village|municipality|commune|district|borough|neighbou?rhood|quarter|county|region|province|"
                  r"suburb|parish|street|road|avenue|area|human settlement|locality|administrative)", I)
CLOSED = re.compile(r"delivery|take ?away|\(closed|fechad|geschlossen|chiuso|ferm[ée]|cerrad|zamkni|uzavřen|stängt|lukket|suljettu", I)
DEFUNCT = re.compile(r"\b1\d{3}\s*[–-]\s*(1\d{3}|20[01]\d|202[0-5])\b\)?\s*$|\(\s*1\d{3}\s*[–-]\s*(1\d{3}|20[0-2]\d)\s*\)")
MUSEUM = re.compile(r"museum|gallery|galerie|exhibition|art collection", I)
PARK = re.compile(r"park|garden|hill|mound|forest|lake|beach|viewpoint|botanical|zoo|river|island|mountain|lookout|promenade|waterfront", I)
OUT = re.compile(r"square|bridge|monument|statue|fountain|gate|memorial|column|obelisk|piazza|plaza|platz|market", I)
EVENT_T = re.compile(r"^(the )?(congress|council|concordat|conference|treaty|peace|siege|battle|synod|diet|convention|declaration|"
                     r"final act|union|compromise|settlement|accord|agreement|pact|alliance|summit|truce|armistice|capitulation|partition|sack|fire|flood|"
                     r"plague|riots?|uprising|revolution|massacre|bombing|negotiations|talks|award|protocol|charter|edict|decree|statute)s? of\b", I)
EVENT_D = re.compile(r"ecumenical|church council|\bsynod\b|\bconcordat\b|\bagreement\b|\baccord\b|\bcongress\b(?! cent)|"
                     r"\bconference\b(?! cent| hall| venue)|negotiations|peace talks|\bpact\b|\blegislature\b|legislative body", I)
TYPE_WORDS = set("""cathedral church basilica chapel palace castle museum gallery square street house building theatre theater opera garden
gardens park bridge tower gate monument memorial statue fountain column market hall library abbey monastery synagogue mosque national
imperial royal saint state city central great grand former museo palais palazzo chiesa kirche platz piazza schloss biserica catedrala
muzeul palatul parcul strada plaza iglesia eglise musee place kirke slot slott torv have""".split())


def not_a_place(title, desc=""):
    return bool(EVENT_T.search(title.replace("_", " ")) or EVENT_D.search(desc))


def stems(name):
    return {w[:4] for w in re.split(r"[^a-z0-9]+", slug(name.split(" (")[0])) if len(w) >= 5 and w not in TYPE_WORDS}


def same_sight(a, b):
    return km(a["lat"], a["lng"], b["lat"], b["lng"]) <= 0.15 and stems(a["title"]) & stems(b["title"])


def dedupe(items):
    kept = []
    for x in items:
        if not any(same_sight(k, x) for k in kept):
            kept.append(x)
    return kept


# ---------- sights ----------

def ring(lat, lng, r_km, n):
    out = []
    for i in range(n):
        b = math.radians(360 / n * i)
        dlat = r_km / 111.0 * math.cos(b)
        dlng = r_km / (111.0 * math.cos(math.radians(lat))) * math.sin(b)
        out.append((lat + dlat, lng + dlng))
    return out


def find_sights(lat, lng, city_title):
    titles = {}
    # Wikipedia's map search returns at most 500 articles per point, and in a big city those are all within ~1.5 km
    # (streets, offices, statues). So the city is searched from 13 points, not one.
    for (la, lo) in [(lat, lng)] + ring(lat, lng, 2.5, 6) + ring(lat, lng, 5.0, 6):
        r = wapi("en.wikipedia.org", action="query", list="geosearch", gsnamespace=0, gsradius=10000, gslimit=500, gscoord=f"{la}|{lo}")
        for g in r["query"]["geosearch"]:
            titles[g["title"]] = (g["lat"], g["lon"])
    log(f"  map search: {len(titles)} articles")
    # Wikidata knows what each place is: every museum, gallery, park, garden, palace, castle, church and attraction
    # within 9 km that has an English article, even the ones the map search missed.
    q = f"""
SELECT DISTINCT ?article ?coord WHERE {{
  SERVICE wikibase:around {{ ?item wdt:P625 ?coord . bd:serviceParam wikibase:center "Point({lng} {lat})"^^geo:wktLiteral .
    bd:serviceParam wikibase:radius "9" . }}
  ?item wdt:P31 ?cls .
  VALUES ?cls {{ wd:Q33506 wd:Q207694 wd:Q1007870 wd:Q17431399 wd:Q2087181 wd:Q588140 wd:Q1970365 wd:Q636400 wd:Q22698 wd:Q1107656
    wd:Q167346 wd:Q43501 wd:Q2416723 wd:Q570116 wd:Q16560 wd:Q23413 wd:Q16970 wd:Q2977 wd:Q4989906 wd:Q12280 wd:Q174782 wd:Q1440300
    wd:Q24398318 wd:Q12518 wd:Q15243209 wd:Q1076486 wd:Q53060 wd:Q3305213 wd:Q24354 wd:Q153562 wd:Q1329623 wd:Q811979 wd:Q839954 }}
  ?article schema:about ?item ; schema:isPartOf <https://en.wikipedia.org/> .
}}"""
    try:
        r = post("https://query.wikidata.org/sparql", {"query": q, "format": "json"})
        n = 0
        for b in r["results"]["bindings"]:
            t = requests.utils.unquote(b["article"]["value"].rsplit("/wiki/", 1)[1]).replace("_", " ")
            m = re.match(r"Point\(([-\d.]+) ([-\d.]+)\)", b["coord"]["value"])
            if m and t not in titles:
                titles[t] = (float(m.group(2)), float(m.group(1)))
                n += 1
        log(f"  Wikidata: {n} more places")
    except Exception as e:
        log(f"  Wikidata search failed: {e}")

    city = city_title.replace("_", " ").lower()
    out = []
    names = sorted(titles)
    for i in range(0, len(names), 50):
        chunk = names[i:i + 50]
        pages, extra = wapi_all("en.wikipedia.org", action="query", redirects=1, prop="pageviews|description|pageprops",
                                pvipdays=30, ppprop="disambiguation|wikibase_item", titles="|".join(chunk))
        for asked in chunk:
            pg = pages.get(resolve(extra, asked))
            if not pg or pg.get("missing"):
                continue
            if "disambiguation" in pg.get("pageprops", {}):
                continue
            title = pg["title"]
            if title.lower() == city or title.startswith("List of"):
                continue
            desc = pg.get("description", "")
            if not desc and not MUSEUM.search(title):
                continue
            is_museum = bool(MUSEUM.search(desc) or MUSEUM.search(title))
            if not is_museum and (EXCLUDE.search(desc) or AREA.search(desc)):
                continue
            if DEFUNCT.search(desc):
                continue
            if not is_museum and not_a_place(title, desc):
                continue
            views = sum(v for v in (pg.get("pageviews") or {}).values() if v)
            if views <= 0:
                continue
            cat = "museum" if is_museum else ("park" if PARK.search(desc) or PARK.search(title) else "sight")
            la, lo = titles[asked]
            if km(lat, lng, la, lo) > 9:
                continue
            out.append({"pageId": pg["pageid"], "title": title, "lat": la, "lng": lo, "desc": desc, "views": views, "cat": cat,
                        "out": cat == "park" or bool(OUT.search(desc) or OUT.search(title)),
                        "qid": pg.get("pageprops", {}).get("wikibase_item")})
        log(f"  checked {min(i + 50, len(names))}/{len(names)}")
    out.sort(key=lambda s: -s["views"])
    out = dedupe(out)
    # Enough of each kind: the best-known sights, and museums and parks of their own (they have fewer views).
    pick = [s for s in out if s["cat"] == "sight"][:40] + [s for s in out if s["cat"] == "museum"][:14] + [s for s in out if s["cat"] == "park"][:10]
    pick.sort(key=lambda s: -s["views"])
    log(f"  sights: {sum(s['cat'] == 'sight' for s in pick)}, museums: {sum(s['cat'] == 'museum' for s in pick)}, parks: {sum(s['cat'] == 'park' for s in pick)}")
    return pick


# ---------- places to eat ----------

OVERPASS = ["https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter", "https://overpass.kumi.systems/api/interpreter"]


def overpass(q):
    for attempt in range(3):
        for url in OVERPASS:
            try:
                r = S.post(url, data={"data": q}, timeout=200)
                if r.status_code == 200 and r.text.lstrip().startswith("{"):
                    return r.json()
                log(f"  overpass {url.split('/')[2]}: HTTP {r.status_code}")
            except Exception as e:
                log(f"  overpass {url.split('/')[2]}: {type(e).__name__}")
        time.sleep(30 * (attempt + 1))
    raise RuntimeError("overpass unavailable")


def find_food(lat, lng):
    q = f'[out:json][timeout:180];nwr["amenity"~"^(restaurant|cafe|bar|pub)$"]["name"](around:2200,{lat},{lng});out tags center 5000;'
    els = overpass(q)["elements"]
    alls, seen = [], set()
    for e in els:
        t = e.get("tags", {})
        if CLOSED.search(t.get("name", "")) or "disused:amenity" in t or t.get("takeaway") == "only" or t.get("delivery") == "only":
            continue
        la = e.get("lat", e.get("center", {}).get("lat"))
        lo = e.get("lon", e.get("center", {}).get("lon"))
        if la is None:
            continue
        am = t.get("amenity")
        kind = {"restaurant": "food", "cafe": "coffee"}.get(am, "drinks")
        cuisine = [c.strip().replace("_", " ") for c in t.get("cuisine", "").split(";") if c.strip()][:2]
        what = {"restaurant": "restaurant", "cafe": "café", "pub": "pub"}.get(am, "bar")
        desc = (" and ".join(cuisine)[:1].upper() + " and ".join(cuisine)[1:] + f" {what}.") if cuisine else what[:1].upper() + what[1:] + " in the centre."
        oh = t.get("opening_hours", "")
        if oh and len(oh) < 60:
            desc += f" Hours: {oh}."
        d = km(lat, lng, la, lo)
        score = (6 if ("wikidata" in t or "wikipedia" in t) else 0) + (3 if ("website" in t or "contact:website" in t) else 0) + \
                (2 if cuisine else 0) + (2 if "opening_hours" in t else 0) + (2 if ("image" in t or "wikimedia_commons" in t) else 0) - int(d * 3)
        n = t["name"].lower()
        if n in seen:
            continue
        seen.add(n)
        alls.append({"id": "o" + e["type"][0] + str(e["id"]), "name": t["name"], "lat": la, "lng": lo, "kind": kind, "desc": desc, "score": score,
                     "tags": {k: t[k] for k in ("wikidata", "wikipedia", "image", "wikimedia_commons", "website") if k in t}})

    def top(k, n):
        return sorted([a for a in alls if a["kind"] == k], key=lambda a: -a["score"])[:n]
    out = top("food", 25) + top("coffee", 15) + top("drinks", 10)
    log(f"  places to eat: {len(els)} on the map, kept {len(out)}")
    return out


# ---------- texts ----------

def texts(sights):
    info = {}
    for i in range(0, len(sights), 20):
        chunk = sights[i:i + 20]
        pages, extra = wapi_all("en.wikipedia.org", action="query", redirects=1, prop="extracts|info", exintro=1, explaintext=1,
                                exsentences=3, exlimit=20, inprop="url", titles="|".join(s["title"] for s in chunk))
        for s in chunk:
            pg = pages.get(resolve(extra, s["title"]))
            if pg and pg.get("extract"):
                info["w%d" % s["pageId"]] = {"extract": pg["extract"].strip(), "page": pg.get("fullurl", "")}
    return info


# ---------- photo candidates ----------

JUNK = re.compile(r"(?<![a-z])(maps?|mapa|plans?|karte|kort|logo|coat[ _]of[ _]arms|wappen|herb|diagram|menu|receipt|flags?|icon|symbol|"
                  r"signature|locator|seal|emblem|stamps?|banknotes?|coins?|chart|graph|poster|book[ _]cover|award|medal|scheme|location|button|arrow|"
                  r"sketch|drawings?|engraving|lithograph|portrait|selfie)(?![a-z])", I)
MAP_WORDS = re.compile(r"(stadt|lage|grund|uber|ubersichts|land|welt|orts|bezirks|strassen|stra(ss|ß)en)(plan|karte|riss)|grundriss|"
                       r"(?<![a-z])atlas(?![a-z])|cartograph|location[ _-]?map|relief[ _-]?map|blank[ _-]?map|outline[ _-]?map", I)
NOT_PHOTO = re.compile(r"(?<![a-z])(paintings?|gemalde|dipinti|obrazy|engravings?|etchings?|woodcuts?|lithographs?|prints|drawings?|"
                       r"illustrations?|manuscripts?|portraits?|artworks?|frescos?|frescoes|mosaics?|stained glass|tapestr[a-z]*|miniatures|in art|"
                       r"historical images|historical photographs|old photographs|postcards|scans?|maps?|plans|floor plans|cartograph[a-z]*|territor[a-z]*|"
                       r"bells?|glocken?|campane|dzwony|reliefs?|details?|inscriptions?|plaques?|coins?|stamps?|documents?|signatures?|coats? of arms|"
                       r"heraldry|banners?|flags?|tombs?|epitaphs?|graves?|people|politicians|ceremon[a-z]*|press conferences?|logos?|diagrams?|charts?|"
                       r"reconstructions?|models?|3d renders?|protests?|demonstrations?|strikes|parades?|marathons?|concerts?|men|women|"
                       r"musicians|actors|actresses|singers|writers|athletes|sportspeople|employees|visitors|selfies|events|weddings|award ceremonies)(?![a-z])")
GOOD = re.compile(r"quality images|featured pictures|valued images")
PHOTO_EXT = re.compile(r"\.(jpe?g|webp)$", I)
INTERIOR_CAT = re.compile(r"interior|inside|exterior|views? of|facade|façade|at night|from the air|aerial|exhibitions?|galler(y|ies)|halls?|rooms?", I)

GENERIC = set("""cafe caffe the and pod bar books lounge chocolate plac nowy cloth hall restaurant restaurante gasthaus zum zur der die das
stalls food ristorante trattoria pizzeria pasticceria antica terrazza della dei del church palace square museum park street bridge tower gate
house garden gardens castle basilica cathedral national city old new saint royal main great grand mosque synagogue chapel abbey monastery
temple theatre theater opera library fortress fort memorial monument statue fountain building centre center market station lake river
island hill tomb cemetery gallery history historical natural science modern contemporary folk ethnographic archaeological orthodox catholic
evangelical lutheran reformed parish holy trinity assumption virgin mary nicholas george michael peter paul john stephen francis anne
barbara kirke slot slott torv have kirken museet haven kobenhavn copenhagen danish denmark danmark""".split())


def tokens(name):
    return {w for w in re.split(r"[^a-z0-9]+", plain(name)) if len(w) >= 4 and w not in GENERIC}


def matches(title, toks):
    if not toks:
        return False
    t = plain(title)
    t = t + " " + t.replace("oe", "o").replace("ue", "u").replace("ae", "a").replace("aa", "a")
    return any(k in t for k in toks)


def taken_year(ii):
    v = ii.get("extmetadata", {}).get("DateTimeOriginal", {}).get("value", "")
    m = re.search(r"(?<!\d)(1[89]\d\d|20\d\d)(?!\d)", v)
    return int(m.group(1)) if m else None


def cats(ii):
    return plain(ii.get("extmetadata", {}).get("Categories", {}).get("value", "")).replace("_", " ")


def file_info(files):
    """Commons metadata of files: size, type, date taken, categories, a 1280 px link. Only files fit to show."""
    out = {}
    files = list(dict.fromkeys(files))
    for i in range(0, len(files), 50):
        chunk = files[i:i + 50]
        pages, extra = wapi_all("commons.wikimedia.org", action="query", prop="imageinfo",
                                iiprop="url|mime|size|timestamp|extmetadata",
                                iiextmetadatafilter="DateTimeOriginal|Categories|Artist|LicenseShortName", iiurlwidth=1280,
                                titles="|".join(chunk))
        for f in chunk:
            pg = pages.get(resolve(extra, f))
            if not pg or not pg.get("imageinfo"):
                continue
            ii = pg["imageinfo"][0]
            w, h = ii.get("width", 0), ii.get("height", 0)
            year = taken_year(ii) or (int(ii["timestamp"][:4]) if ii.get("timestamp") else None)
            reason = None
            if not re.search("jpeg|webp", ii.get("mime", "")):
                reason = "not a photo file"
            elif w < 800 or h < 500 or not (0.55 <= w / h <= 2.6):
                reason = "too small or odd shape"
            elif JUNK.search(f) or MAP_WORDS.search(plain(f)):
                reason = "name says map/logo/drawing"
            elif year is None or year < MIN_YEAR:
                reason = f"older than 15 years ({year})"
            elif NOT_PHOTO.search(cats(ii)):
                reason = "category says not a photo of the place"
            if reason:
                continue
            em = ii.get("extmetadata", {})
            artist = re.sub(r"<[^>]+>", "", em.get("Artist", {}).get("value", "")).strip()[:80]
            out[f] = {"url": ii.get("thumburl") or ii["url"], "page": ii.get("descriptionurl", ""), "good": bool(GOOD.search(cats(ii))),
                      "dated": taken_year(ii) is not None, "year": year, "credit": f"{artist} / {em.get('LicenseShortName', {}).get('value', '')}".strip(" /"),
                      "big": max(w, h)}
    return out


def ftitle(name):
    return "File:" + name.replace("_", " ").removeprefix("File:")


def commons_category(cat, limit=120):
    r = wapi("commons.wikimedia.org", action="query", list="categorymembers", cmtitle="Category:" + cat, cmtype="file|subcat",
             cmlimit=500, cmsort="timestamp", cmdir="desc")
    files, subs = [], []
    for m in r.get("query", {}).get("categorymembers", []):
        if m["ns"] == 6 and PHOTO_EXT.search(m["title"]):
            files.append(m["title"])
        elif m["ns"] == 14:
            subs.append(m["title"].removeprefix("Category:"))
    return files[:limit], subs


def commons_search(term, limit=30):
    r = wapi("commons.wikimedia.org", action="query", list="search", srnamespace=6, srlimit=limit, srsearch=f"filetype:bitmap {term}")
    return [h["title"] for h in r.get("query", {}).get("search", []) if PHOTO_EXT.search(h["title"])]


def wikidata(qids, lang):
    out = {}
    qids = [q for q in dict.fromkeys(qids) if q]
    for i in range(0, len(qids), 50):
        chunk = qids[i:i + 50]
        r = wapi("www.wikidata.org", action="wbgetentities", props="claims|sitelinks|labels", ids="|".join(chunk),
                 sitefilter="commonswiki" + (f"|{lang}wiki" if lang else ""), languages=lang or "en")
        for q, e in r.get("entities", {}).items():
            def claim(p):
                try:
                    return e["claims"][p][0]["mainsnak"]["datavalue"]["value"]
                except (KeyError, IndexError, TypeError):
                    return None
            sl = e.get("sitelinks", {})
            link = sl.get("commonswiki", {}).get("title", "")
            cat = claim("P373") or (link.removeprefix("Category:") if link.startswith("Category:") else None)
            out[q] = {"cat": cat, "photo": claim("P18"), "interior": claim("P5775"),
                      "local": sl.get(f"{lang}wiki", {}).get("title") if lang else None,
                      "label": e.get("labels", {}).get(lang or "en", {}).get("value", "")}
    return out


def article_images(host, titles):
    """Lead picture and other pictures of several articles on one Wikipedia."""
    res = {}
    for i in range(0, len(titles), 20):
        chunk = titles[i:i + 20]
        pages, extra = wapi_all(host, action="query", redirects=1, prop="pageimages|images", piprop="name", imlimit="max", titles="|".join(chunk))
        for t in chunk:
            pg = pages.get(resolve(extra, t)) or {}
            imgs = [x["title"] for x in pg.get("images", []) if PHOTO_EXT.search(x["title"]) and not JUNK.search(x["title"])]
            # Wikipedias in other languages call the file namespace differently ("Fil:", "Datei:"): Commons wants "File:".
            imgs = [ftitle(x.split(":", 1)[1]) for x in imgs]
            lead = pg.get("pageimage")
            res[t] = (ftitle(lead) if lead else None, imgs[:40])
    return res


MAPILLARY = os.environ.get("MAPILLARY_TOKEN", "").strip()
FLICKR = os.environ.get("FLICKR_KEY", "").strip()


def mapillary(lat, lng, food, maxn):
    if not MAPILLARY:
        return []
    d = 0.0007
    dl = d / math.cos(math.radians(lat))
    since = f"{MIN_YEAR}-01-01T00:00:00Z"
    try:
        r = get("https://graph.mapillary.com/images", params={
            "access_token": MAPILLARY, "limit": 100, "start_captured_at": since,
            "fields": "id,thumb_2048_url,captured_at,compass_angle,computed_compass_angle,computed_geometry,geometry,is_pano",
            "bbox": f"{lng - dl:.6f},{lat - d:.6f},{lng + dl:.6f},{lat + d:.6f}"})
    except Exception as e:
        log(f"    mapillary failed: {e}")
        return []
    shots = []
    for o in r.get("data", []):
        if o.get("is_pano") or not o.get("thumb_2048_url"):
            continue
        g = (o.get("computed_geometry") or o.get("geometry") or {}).get("coordinates")
        if not g:
            continue
        dist = km(g[1], g[0], lat, lng) * 1000
        if dist < 6 or dist > (30 if food else 60):
            continue
        hd = o.get("computed_compass_angle", o.get("compass_angle"))
        if hd is None:
            continue
        off = abs(((bearing(g[1], g[0], lat, lng) - hd) % 360 + 540) % 360 - 180)
        if off > 25:
            continue
        shots.append({"url": o["thumb_2048_url"], "lat": g[1], "lng": g[0], "t": o.get("captured_at", 0), "dist": dist,
                      "page": f"https://www.mapillary.com/app/?pKey={o['id']}", "credit": "Mapillary / CC BY-SA 4.0"})
    picked = []
    for s in sorted(shots, key=lambda s: (int(s["dist"] / 20), -s["t"])):
        if len(picked) < maxn and all(km(p["lat"], p["lng"], s["lat"], s["lng"]) * 1000 > 12 for p in picked):
            picked.append(s)
    return picked


def flickr(name, lat, lng, food, maxn=12):
    """Flickr photos with an open licence (CC BY, CC BY-SA, CC0, public domain), taken near the place in the last 15 years."""
    if not FLICKR:
        return []
    try:
        r = get("https://api.flickr.com/services/rest/", params={
            "method": "flickr.photos.search", "api_key": FLICKR, "format": "json", "nojsoncallback": 1, "text": name,
            "lat": lat, "lon": lng, "radius": 0.1 if food else 0.3, "radius_units": "km", "license": "4,5,7,9,10",
            "min_taken_date": f"{MIN_YEAR}-01-01", "content_types": 0, "media": "photos", "sort": "relevance", "per_page": 30,
            "extras": "url_l,url_c,owner_name,license,date_taken"})
    except Exception as e:
        log(f"    flickr failed: {e}")
        return []
    toks = tokens(name)
    out = []
    for p in r.get("photos", {}).get("photo", []):
        u = p.get("url_l") or p.get("url_c")
        if not u or (toks and not matches(p.get("title", "") + " " + name, toks)):
            continue
        out.append({"url": u, "page": f"https://www.flickr.com/photos/{p['owner']}/{p['id']}", "credit": f"{p.get('ownername', '')} / Flickr CC"})
    return out[:maxn]


# ---------- CLIP check ----------

class Judge:
    """CLIP (OpenAI ViT-B/32): says how much a picture looks like a photo of the place vs. people, maps, paintings..."""

    NEG = ["a portrait photo of a person", "a photo of a group of people posing", "a selfie", "a photo of a person giving a speech",
           "a photo of a crowd at a concert or event", "a map", "a floor plan or diagram", "a painting", "a drawing or illustration",
           "a scan of a document or text", "a logo or graphic", "a close-up photo of food on a plate", "a photo of a car",
           "an old black and white photograph", "a screenshot", "a close-up photo of a small object", "a photo of a sports match",
           "a photo of a book cover", "a photo of a dog or cat", "a blurry photo"]
    POS = {
        "sight": ["a photo of a famous landmark", "a photo of a historic building", "a photo of a church interior", "a photo of a palace",
                  "a photo of a castle", "a photo of a city square", "a photo of a monument in a city", "a photo of a bridge over water",
                  "a photo of a tower", "a photo of a harbour with boats", "a photo of a street with old buildings", "a photo of a statue in a city"],
        "museum": ["a photo of a museum building", "a photo of a museum exhibition hall", "a photo of artworks displayed in a museum gallery",
                   "a photo of a historic building", "a photo of a museum interior with visitors walking", "a photo of exhibits in glass cases"],
        "park": ["a photo of a park with trees and paths", "a photo of a garden with flowers", "a photo of a lake in a park",
                 "a scenic view over a city", "a photo of an amusement park", "a photo of a zoo", "a photo of a green lawn with trees",
                 "a photo of a palace garden", "a photo of a beach"],
        "food": ["a photo of a restaurant storefront on a street", "a photo of a restaurant interior with tables", "a photo of a café interior",
                 "a photo of a bar interior", "a photo of a café terrace with tables outside", "a photo of a shop front on a street",
                 "a photo of a building facade"],
    }
    INSIDE = ["a photo of a restaurant interior with tables", "a photo of a café interior", "a photo of a bar interior"]

    def __init__(self):
        import torch
        from transformers import CLIPModel, CLIPProcessor
        self.torch = torch
        self.model = CLIPModel.from_pretrained("openai/clip-vit-base-patch32").eval()
        self.proc = CLIPProcessor.from_pretrained("openai/clip-vit-base-patch32")
        self._text = {}

    def text(self, prompts):
        key = tuple(prompts)
        if key not in self._text:
            with self.torch.no_grad():
                t = self.proc(text=list(prompts), return_tensors="pt", padding=True)
                e = self.model.get_text_features(**t)
                self._text[key] = e / e.norm(dim=-1, keepdim=True)
        return self._text[key]

    def image(self, img):
        with self.torch.no_grad():
            x = self.proc(images=img, return_tensors="pt")
            e = self.model.get_image_features(**x)
            return (e / e.norm(dim=-1, keepdim=True))[0]

    def score(self, emb, cat, name, desc):
        pos = list(self.POS[cat]) + [f"a photo of {name}"]
        neg = list(self.NEG)
        if re.search(r"statue|sculpture|mermaid|monument", name + " " + desc, I):
            neg = [n for n in neg if "small object" not in n]
        prompts = pos + neg
        logits = 100.0 * (self.text(prompts) @ emb)
        p = logits.softmax(dim=0).tolist()
        ps = sum(p[:len(pos)])
        worst = max(range(len(neg)), key=lambda i: p[len(pos) + i])
        inside = 0.0
        if cat == "food":
            inside = sum(p[pos.index(x)] for x in self.INSIDE) / max(ps, 1e-6)
        return ps, neg[worst], inside


def thumb(img):
    t = img.copy()
    t.thumbnail((150, 150))
    return t


def fetch_image(url):
    b = get(url, raw=True, timeout=60)
    if not b or len(b) < 4000:
        return None
    img = Image.open(io.BytesIO(b))
    img = ImageOps.exif_transpose(img).convert("RGB")
    return img


def save_webp(img, path, long_side=1280, q=72):
    w, h = img.size
    s = long_side / max(w, h)
    if s < 1:
        img = img.resize((round(w * s), round(h * s)), Image.LANCZOS)
    img.save(path, "WEBP", quality=q, method=5)


# ---------- review sheets ----------

def sheet(rows, path, title):
    """rows: [(label, [(PIL thumb, caption)])] -> one JPEG to look at."""
    TH = 150
    W = 160 + 10 * (TH + 6)
    H = 40 + len(rows) * (TH + 26)
    im = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(im)
    try:
        f = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 12)
        fb = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 16)
    except Exception:
        f = fb = ImageFont.load_default()
    d.text((8, 10), title, fill="black", font=fb)
    y = 40
    for label, thumbs in rows:
        words, line, lines = label.split(), "", []
        for w_ in words:
            if len(line + " " + w_) > 22:
                lines.append(line)
                line = w_
            else:
                line = (line + " " + w_).strip()
        lines.append(line)
        d.text((6, y + 4), "\n".join(lines[:7]), fill="black", font=f)
        x = 160
        for t, cap in thumbs[:10]:
            t = t.copy()
            t.thumbnail((TH, TH))
            im.paste(t, (x, y))
            d.text((x, y + TH + 2), cap[:24], fill="#a00" if cap.startswith("X") else "#060", font=f)
            x += TH + 6
        y += TH + 26
    im.save(path, "JPEG", quality=80)


# ---------- main ----------

LANG = {"AL": "sq", "AD": "ca", "AT": "de", "BY": "be", "BE": "nl", "BA": "bs", "BG": "bg", "HR": "hr", "CY": "el", "CZ": "cs", "DK": "da",
        "EE": "et", "FI": "fi", "FR": "fr", "DE": "de", "GR": "el", "HU": "hu", "IS": "is", "IT": "it", "XK": "sq", "LV": "lv", "LI": "de",
        "LT": "lt", "LU": "fr", "MD": "ro", "MC": "fr", "ME": "sr", "NL": "nl", "MK": "mk", "NO": "no", "PL": "pl", "PT": "pt", "RO": "ro",
        "RU": "ru", "SM": "it", "RS": "sr", "SK": "sk", "SI": "sl", "ES": "es", "SE": "sv", "CH": "de", "TR": "tr", "UA": "uk", "VA": "it"}


def locate(name, wiki, cc):
    r = wapi("en.wikipedia.org", action="query", redirects=1, prop="coordinates", titles=wiki or name)
    for p in r["query"].get("pages", []):
        if p.get("coordinates"):
            return p["coordinates"][0]["lat"], p["coordinates"][0]["lon"]
    a = get("https://nominatim.openstreetmap.org/search", params={"format": "json", "limit": 1, "countrycodes": cc.lower(), "q": name})
    return float(a[0]["lat"]), float(a[0]["lon"])


def gather_sight_candidates(sights, lang, city_name):
    """Candidate photo files per sight, best sources first."""
    qinfo = wikidata([s["qid"] for s in sights], lang)
    en = article_images("en.wikipedia.org", [s["title"] for s in sights])
    local_titles = [qinfo[s["qid"]]["local"] for s in sights if s.get("qid") in qinfo and qinfo[s["qid"]].get("local")]
    loc = article_images(f"{lang}.wikipedia.org", local_titles) if lang and local_titles else {}
    cands = {}
    for s in sights:
        it = qinfo.get(s.get("qid"), {})
        toks = tokens(s["title"]) | tokens(it.get("label", "")) | tokens(it.get("local") or "") | tokens(it.get("cat") or "")
        order = []  # (file title, source, must match name)
        lead, imgs = en.get(s["title"], (None, []))
        if lead:
            order.append((lead, "lead", False))
        if it.get("photo"):
            order.append((ftitle(it["photo"]), "wikidata", False))
        if it.get("interior"):
            order.append((ftitle(it["interior"]), "wikidata-interior", False))
        llead, limgs = loc.get(it.get("local"), (None, [])) if it.get("local") else (None, [])
        if llead:
            order.append((llead, "local-lead", False))
        for f in imgs + limgs:
            order.append((f, "article", True))
        if it.get("cat"):
            try:
                files, subs = commons_category(it["cat"])
                for f in files:
                    order.append((f, "category", False))
                for sub in [x for x in subs if INTERIOR_CAT.search(x) or matches(x, toks)][:4]:
                    sf, _ = commons_category(sub, 40)
                    for f in sf:
                        order.append((f, "subcategory", False))
            except Exception as e:
                log(f"    category {it['cat']}: {e}")
        cands[s["pageId"]] = {"order": order, "toks": toks, "item": it}
    return cands


def choose_photos(judge, place, files_order, extra, cat, maxn, review):
    """files_order: [(url, meta)] already filtered by metadata. Returns kept [(img, meta)]."""
    kept, embs, rejected = [], [], []
    tried = 0
    for url, meta in files_order:
        if len(kept) >= maxn or tried >= maxn * 3 + 6:
            break
        tried += 1
        try:
            img = fetch_image(url)
        except Exception as e:
            log(f"    image failed: {e}")
            continue
        if img is None:
            continue
        emb = judge.image(img)
        ps, worst, inside = judge.score(emb, cat, place["name"], place.get("desc", ""))
        if ps < 0.5:
            rejected.append((thumb(img), f"X {worst[2:18]} {ps:.2f}"))
            continue
        if any(float(emb @ e) > 0.93 for e in embs):
            rejected.append((thumb(img), "X duplicate"))
            continue
        embs.append(emb)
        kept.append((img, {**meta, "score": round(ps, 3), "inside": round(inside, 3)}))
        time.sleep(0.1)
    if cat == "food" and kept:
        # Outside and inside shots mixed: best outside first, then alternating.
        ins = [k for k in kept if k[1]["inside"] >= 0.5]
        outs = [k for k in kept if k[1]["inside"] < 0.5]
        mixed = []
        while ins or outs:
            if outs:
                mixed.append(outs.pop(0))
            if ins:
                mixed.append(ins.pop(0))
        kept = mixed
    review.append((place["name"], [(thumb(k[0]), f"{k[1]['src']} {k[1]['score']:.2f}") for k in kept] + rejected[:max(0, 10 - len(kept))]))
    return kept


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--country", required=True)
    ap.add_argument("--city", required=True)
    ap.add_argument("--wiki", default="")
    ap.add_argument("--out", default="out")
    ap.add_argument("--no-photos", action="store_true")
    a = ap.parse_args()
    cc = a.country.upper()
    lang = LANG.get(cc)
    if lang in ("en",):
        lang = None
    key = cc.lower() + "-" + slug(a.city)
    base = os.path.join(a.out, key)
    os.makedirs(os.path.join(base, "photos"), exist_ok=True)
    os.makedirs(os.path.join(base, "review"), exist_ok=True)
    t0 = time.time()
    log(f"== {a.city} ({cc}) -> {key}")
    lat, lng = locate(a.city, a.wiki, cc)
    log(f"  centre {lat:.5f},{lng:.5f}")
    sights = find_sights(lat, lng, a.wiki or a.city)
    eats = find_food(lat, lng)
    info = texts(sights)
    log(f"  texts: {len(info)}")

    photos = {}
    if not a.no_photos:
        judge = Judge()
        review = []
        cands = gather_sight_candidates(sights, lang, a.city)
        all_files = [f for c in cands.values() for f, _, _ in c["order"]]
        meta = file_info(all_files)
        log(f"  sight photo files: {len(set(all_files))} found, {len(meta)} pass the checks (date, size, type, categories)")
        for i, s in enumerate(sights):
            pid = "w%d" % s["pageId"]
            c = cands[s["pageId"]]
            order, seen = [], set()
            good_first = sorted(
                [(f, src) for f, src, must in c["order"] if f in meta and (not must or matches(f, c["toks"]))],
                key=lambda x: (0 if x[1] in ("lead", "wikidata", "local-lead", "wikidata-interior") else (1 if meta[x[0]]["good"] else 2)))
            for f, src in good_first:
                if f in seen:
                    continue
                seen.add(f)
                m = meta[f]
                if src in ("category", "subcategory") and (not m["dated"] or m["big"] < 1200):
                    continue
                order.append((m["url"], {"src": src, "page": m["page"], "credit": m["credit"]}))
            maxn = 10
            if len(order) < 8:
                for f in commons_search(f"{(c['item'].get('label') or s['title'].split(' (')[0])} {a.city}", 30):
                    if f not in seen and matches(f, c["toks"]):
                        seen.add(f)
                        order.append((f, None))
                need = [f for f, m in order if m is None]
                if need:
                    mi = file_info(need)
                    order = [(mi[f]["url"], {"src": "search", "page": mi[f]["page"], "credit": mi[f]["credit"]}) if m is None else (f, m)
                             for f, m in order if m is not None or f in mi]
            if len(order) < 6:
                for sh in mapillary(s["lat"], s["lng"], False, 3):
                    order.append((sh["url"], {"src": "mapillary", "page": sh["page"], "credit": sh["credit"]}))
            for fl in flickr(s["title"].split(" (")[0] + " " + a.city, s["lat"], s["lng"], False, 8) if len(order) < 10 else []:
                order.append((fl["url"], {"src": "flickr", "page": fl["page"], "credit": fl["credit"]}))
            place = {"name": s["title"].split(" (")[0], "desc": s["desc"]}
            kept = choose_photos(judge, place, order, None, s["cat"], maxn, review)
            photos[pid] = []
            for k, (img, m) in enumerate(kept):
                fn = f"{pid}_{k}.webp"
                save_webp(img, os.path.join(base, "photos", fn))
                photos[pid].append({"file": fn, "page": m["page"], "credit": m["credit"]})
            log(f"  [{i + 1}/{len(sights)}] {s['title']} ({s['cat']}): {len(order)} candidates, kept {len(kept)}")

        food_review = []
        for i, e in enumerate(eats):
            toks = tokens(e["name"])
            order = []
            t = e["tags"]
            files = []
            if t.get("wikimedia_commons", "").startswith("File:"):
                files.append(t["wikimedia_commons"])
            if t.get("image", "").startswith("File:"):
                files.append(t["image"])
            if t.get("wikidata"):
                it = wikidata([t["wikidata"]], lang).get(t["wikidata"], {})
                if it.get("photo"):
                    files.append(ftitle(it["photo"]))
                if it.get("interior"):
                    files.append(ftitle(it["interior"]))
                if it.get("cat"):
                    try:
                        files += commons_category(it["cat"], 30)[0]
                    except Exception:
                        pass
            if toks:
                files += [f for f in commons_search(f"{e['name']} {a.city}", 20) if matches(f, toks)]
            mi = file_info(files) if files else {}
            for f in dict.fromkeys(files):
                if f in mi:
                    order.append((mi[f]["url"], {"src": "commons", "page": mi[f]["page"], "credit": mi[f]["credit"]}))
            for fl in flickr(e["name"], e["lat"], e["lng"], True, 8):
                order.append((fl["url"], {"src": "flickr", "page": fl["page"], "credit": fl["credit"]}))
            for sh in mapillary(e["lat"], e["lng"], True, 3):
                order.append((sh["url"], {"src": "mapillary", "page": sh["page"], "credit": sh["credit"]}))
            kept = choose_photos(judge, {"name": e["name"], "desc": e["desc"]}, order, None, "food", 5, food_review)
            photos[e["id"]] = []
            for k, (img, m) in enumerate(kept):
                fn = f"{e['id']}_{k}.webp"
                save_webp(img, os.path.join(base, "photos", fn), long_side=1280)
                photos[e["id"]].append({"file": fn, "page": m["page"], "credit": m["credit"]})
            log(f"  [{i + 1}/{len(eats)}] {e['name']} ({e['kind']}): {len(order)} candidates, kept {len(kept)}")

        for n in range(0, len(review), 12):
            sheet(review[n:n + 12], os.path.join(base, "review", f"sights_{n // 12:02d}.jpg"), f"{a.city}: sights {n + 1}-{n + len(review[n:n + 12])} (green = kept, red X = rejected)")
        for n in range(0, len(food_review), 12):
            sheet(food_review[n:n + 12], os.path.join(base, "review", f"food_{n // 12:02d}.jpg"), f"{a.city}: places to eat {n + 1}-{n + len(food_review[n:n + 12])}")

    for e in eats:
        e.pop("tags", None)
    for s in sights:
        s.pop("qid", None)
    out = {"key": key, "name": a.city, "country": cc, "centerLat": lat, "centerLng": lng,
           "built": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
           "sights": sights, "eats": eats, "info": info, "photos": photos}
    with open(os.path.join(base, "city.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    n = sum(len(v) for v in photos.values())
    size = sum(os.path.getsize(os.path.join(base, "photos", x)) for x in os.listdir(os.path.join(base, "photos")))
    log(f"== done {key}: {len(sights)} sights, {len(eats)} places to eat, {n} photos, {size / 1e6:.1f} MB, {time.time() - t0:.0f}s")


if __name__ == "__main__":
    main()
