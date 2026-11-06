import json, urllib.request, urllib.parse
UA = "Waypoint-diag/1.0 (https://github.com/R0mulus001/Waypoint)"
for host, t in [("en", "File:Cafe Central in Vienna interior near portraits.JPG"), ("de", "Datei:Cafe Central in Vienna interior near portraits.JPG"),
                ("en", "File:Wien - Schloss Schönbrunn.JPG"), ("de", "File:Wien - Schloss Schönbrunn.JPG")]:
    u = "https://%s.wikipedia.org/w/api.php?action=query&format=json&prop=imageinfo&iiprop=url&iiurlwidth=960&titles=%s" % (host, urllib.parse.quote(t))
    r = json.load(urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": UA})))
    for p in r["query"]["pages"].values():
        print(host, json.dumps(p, ensure_ascii=False))
