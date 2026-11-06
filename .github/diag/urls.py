import json, urllib.request, time
UA = "Waypoint/1.1 (personal Android city guide; https://github.com/R0mulus001/Waypoint)"
for name, a, b in [("Bucharest -> Belvedere", (26.1025, 44.4268), (16.3809, 48.1915)), ("Cluj -> Milan Cathedral", (23.5899, 46.7712), (9.1919, 45.4641)),
                   ("Iasi -> Wawel", (27.5766, 47.1585), (19.9355, 50.0541))]:
    u = "https://routing.openstreetmap.de/routed-car/route/v1/driving/%f,%f;%f,%f?overview=false&alternatives=false" % (a + b)
    t = time.time()
    r = json.load(urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": UA}), timeout=30))
    rt = r["routes"][0]
    print("ROUTE", name, r["code"], round(rt["distance"] / 1000), "km", round(rt["duration"] / 3600, 1), "h", "(%.1fs)" % (time.time() - t))
