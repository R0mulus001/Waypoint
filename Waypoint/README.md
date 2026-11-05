# Waypoint (Android, native)

Europe-wide city guide. Pick a country, then a city (or search any city): the app builds a full guide on the phone
from Wikipedia and OpenStreetMap, asks how long you're staying, then gives Nearby, Day plans and Visited, with the
country's flag and colours. No cities are built in. Kotlin / Jetpack Compose.

Design ("Atlas & Route"): tokens in `Look.kt` (one meaning per colour; the accent comes from the country's flag and is
adjusted to 4.5:1), shared components in `Kit.kt`, vector flags and the flag + pin city tile in `FlagArt.kt`. Titles use
Barlow Semi Condensed (fetched at build time), body text the system font. Day plans draw the route line.

- Each city has its own database on the phone (`city_<key>.db`): visited places, day plans, notes and stars.
  Photos and Wikipedia text are saved in `files/photos/<key>/`; "Download all" keeps going in the background
  until every place is saved, so the guide works offline.
- App id `com.laur.waypointapp`, so it installs next to Waypoint beta (`com.laur.waypoint`).

Built by GitHub Actions (workflow "Build Waypoint APK") into the "Waypoint (latest)" release.
Signing: `app/waypoint.p12` (PKCS12, AES-256); its password is only in the `SIGNING_PASSWORD` Actions secret.
