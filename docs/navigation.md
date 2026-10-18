# Following routes from navigation apps

When the guide knows your route, it tells you about the places along that route instead of
guessing the way ahead from your heading. The map draws the route as a solid line, and the
status card says where it came from ("Route: Komoot tour 'Langs de Vecht' · 42 km").

None of the big navigation apps publishes the route you are navigating through a public API.
Travel Guide therefore uses free mechanisms:

1. **Following the navigation app live** (optional notification access): the next instruction
   ("in 300 m turn left onto …") is read from the app's notification and the forecast follows the
   road through that turn. Nothing to share or import. See
   [Following navigation live](#following-navigation-live).
2. **Sharing** a route or a route file with Travel Guide, which gives the whole route. It takes
   precedence over the live instructions and is the fallback without notification access.

Without either, the forecast follows the road you're on when driving or cycling, and otherwise
guesses straight on from your heading.

No API keys or paid services are involved. All parsing happens in the `core` module
(`core/src/main/kotlin/.../core/nav/`), which has unit tests with recorded responses.

## Sharing a route

Use the app's **Share** button and pick **Follow route in Travel Guide**. You can share the
route before pressing **Start guiding**: the route is kept, also across restarts, until you clear
it (status card → **Clear**, or **Settings → Navigation → Clear route**).

### Google Maps

1. Plan directions in Google Maps.
2. Tap **⋮ → Share directions** (or share the link from the browser).

Travel Guide accepts:

- `https://www.google.com/maps/dir/?api=1&origin=…&destination=…&waypoints=a|b&travelmode=…`
- `https://www.google.com/maps/dir/<stop>/<stop>/…`, optionally followed by an `@lat,lon,zoom`
  segment (the map view, ignored) and `data=…` (from which the travel mode is read)
- the older `maps.google.com/maps?saddr=…&daddr=…+to:…` form
- short links (`https://maps.app.goo.gl/…`, `goo.gl/maps/…`), which are resolved by following
  their HTTP redirects
- any text around the link, e.g. "Directions to Utrecht https://maps.app.goo.gl/…"

**Limits.** Google Maps links only contain the stops, not the route. Travel Guide:

- uses coordinates directly and looks up place names with
  [Nominatim](https://nominatim.org/) (OpenStreetMap). Following its
  [usage policy](https://operations.osmfoundation.org/policies/nominatim/), requests carry a
  descriptive User-Agent and are limited to one per second;
- **recomputes** the route with the free [OSRM](https://project-osrm.org/) servers run by
  FOSSGIS (`routing.openstreetmap.de`, car, bike and foot profiles). The profile follows the
  Google travel mode, or else the guide's travel mode. Public transport is approximated by road.

So the route can differ from the one Google Maps shows. A route that starts at "Your location"
needs your position: start guiding first, or share a route with a fixed start. Links to a single
place (not directions) are not routes; the app says so.

### Komoot

Share a tour from the Komoot app or website. Recognised links:
`https://www.komoot.com/tour/<id>`, with or without a locale (`/nl-nl/tour/<id>`), on
`komoot.com` or `komoot.de`, with or without `?share_token=…`.

The coordinates come from `https://api.komoot.de/v007/tours/<id>/coordinates` (plus the share
token when present). **This endpoint is unofficial**: it is what Komoot's own web app uses, it is
not documented, and it may change or stop working at any time.

Private tours, and Komoot's generated suggestions ("smart tours"), can't be read this way. Then
open the tour in Komoot, **export it as a GPX file**, and share that file with Travel Guide.

### Garmin

Garmin Connect and Garmin Explore course links require a Garmin login, so Travel Guide can't
read them; it recognises them and asks for a file instead. Export the course from Garmin
Connect (course → **⋯ → Export GPX**, or **Export FIT**) or Garmin Explore, then share the file
or import it in **Settings → Navigation → Import GPX file**.

FIT course files are read by a small built-in reader (no Garmin SDK): it takes the positions of
`record` messages, or of `course_point` messages when there are none (semicircles × 180 / 2³¹).

### GPX files (any app)

GPX files from any app work: Komoot, Garmin, OsmAnd, Strava, route planners. Travel Guide reads
the track (`trk/trkseg/trkpt`) first, then the route (`rte/rtept`), and as a last resort the
waypoints (`wpt`) in file order. The file's `creator` tells Komoot, Garmin and OsmAnd exports
apart. Dense recordings are simplified (Douglas–Peucker, 10 m tolerance), which keeps the route
light without changing its shape. External XML entities are never loaded.

Open a GPX file from a file manager, share it, or use **Settings → Navigation → Import GPX file**.

## Navigation notifications (optional)

With notification access, Travel Guide watches the notifications of these apps only:

| App | Package |
|---|---|
| Google Maps | `com.google.android.apps.maps` |
| Komoot | `de.komoot.android` |
| Garmin (Connect, Explore, Drive, Messenger; best effort) | `com.garmin.android.apps.*` |
| OsmAnd | `net.osmand`, `net.osmand.plus` |
| Waze | `com.waze` |

When one of them shows an ongoing navigation notification, the status card shows
"Navigating with Google Maps: 200 m · Turn left onto …", and the forecast follows its directions
(see [Following navigation live](#following-navigation-live)). When that notification has been
gone for 15 seconds, and the current route came from the same app, the route is dropped and the
guide goes back to following the road or guessing the way ahead.

**Privacy.** Notification access is optional and off until you enable it in
**Settings → Navigation → Enable navigation notifications**. Notifications from other apps are
skipped without reading their content, and nothing is stored or sent anywhere. On Android 13 and
later, apps installed from an APK may need **App info → ⋮ → Allow restricted settings** before
the switch can be turned on.

## Following navigation live

With notification access, the forecast follows the navigation app's directions while you drive,
cycle or walk, without sharing a route. The map draws it as a nearly solid line labelled
"Following Google Maps directions", and the status card says the same.

1. **Reading the instruction** (`core/.../nav/Maneuver.kt`). From the notification's title and
   text (not the sub-text, which holds the trip summary) the guide reads:
   - the distance to the maneuver: `300 m`, `1.2 km`, `1,2 km`, `0.5 mi`, `500 ft`, `100 yd`;
   - the turn, from keywords in English, Dutch and German: left/right, slight, sharp, keep
     left/right, straight on, U-turn, motorway exit, roundabout with its exit number ("take the
     2nd exit", "neem de 2e afslag", "die 3. Ausfahrt"). Examples: "Turn left", "Sla linksaf",
     "Houd rechts aan", "Flauwe bocht naar links", "Links abbiegen", "Links halten";
   - the signposted place: "toward(s) X", "richting X", "Richtung X".

   Street names are **not** read from the grammar. The whole instruction is compared with the
   `name`, `ref` ("A2" also matches "A 2", but not "A20") and `destination` tags of the roads
   nearby, which works in any language.
2. **Roads** (`core/.../road/`). Roads come from OpenStreetMap through the Overpass API, with their
   name, number, one-way and destination tags. Main roads (motorway to secondary) are fetched in
   a 5 km radius. Side streets (tertiary, residential…) are fetched only where turns matter: while
   navigating below about 70 km/h, or by bike (plus cycleways), and only in a 1 km radius. Roads
   are fetched again only after moving away from that area or after 30 minutes. Requests are at
   least 45 seconds apart, and after a failure the wait doubles up to 15 minutes.
3. **Following the road and the turn** (`RoadGraph`). The guide finds the road you're on: within
   25 m, running within 35° of your heading, and open in that direction. It follows that road to
   the maneuver distance, counted down from where the instruction came in, with a margin of
   ±20% (at least 40 m). At the junction there it takes the branch the instruction names. If no
   branch is named, it takes the branch in the instruction's direction, the slip road for an
   exit, or the counted (or named) exit of a roundabout. Beyond the turn it keeps following the
   road. If the turn can't be placed on a junction, the forecast stops where the turn is.
4. **Towards a place** (optional, conservative). When the instruction names a place ("towards
   Utrecht"), the guide looks it up with Nominatim (within about 100 km, once per name, at most
   one request per second) and routes there with OSRM, at most once a minute. That route is used
   only while you're within 40 m of it and it passes through the next turn the same way the road
   path does.
5. **When it stops.** The live instruction is dropped when the app's navigation notification
   goes away, or after 2 minutes without an update.

**Order of preference** for the forecast: a shared or imported route, then live navigation, then
the railway (by train), then the road you're on (driving or cycling; drawn dashed, "Following the
road"), and finally straight on from your heading.

**Limits.** Notifications only tell the next turn, so the forecast beyond that turn follows the
road (or the route towards the signposted place). Apps word and update their notifications in
their own way and may change them at any time; text that isn't understood is ignored and the
forecast falls back to following the road. Which roundabout exit to take is only known when the
instruction gives its number or the exit road's name. Roads missing from OpenStreetMap, or a GPS
position more than 25 m from the road, also mean falling back to a guess.

## Network use

Importing contacts only the services needed for that link: Komoot's API for Komoot tours,
Nominatim and OSRM for Google Maps links, and Google's short-link server to resolve
`maps.app.goo.gl` links. Files are processed on the phone.

Following the road uses the Overpass API (see above for how often). Following navigation live
adds, only for instructions that name a place, one Nominatim lookup per place name and an OSRM
route at most once a minute.

## Ideas for later

- Re-route when you leave the route (needs your destination and a routing request).
- Komoot and Garmin accounts with OAuth, if they ever offer it to free apps.
- Android Auto / navigation SDK integrations, should one become available without fees.
