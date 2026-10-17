# Following routes from navigation apps

When the guide knows your route, it tells you about the places along that route instead of
guessing the way ahead from your heading. The map draws the route as a solid line, and the
status card says where it came from ("Route: Komoot tour 'Langs de Vecht' · 42 km").

None of the big navigation apps publishes the route you are navigating through a public API.
Travel Guide therefore uses two free mechanisms:

1. **Sharing** a route or a route file with Travel Guide (the main path).
2. **Optional notification access** to see which app is navigating and when it stops.

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
"Navigating with Google Maps: 200 m · Turn left onto …". When that notification has been gone
for 15 seconds, and the current route came from the same app, the route is dropped and the guide
goes back to guessing the way ahead.

**Limits.** Notifications contain the next instruction, not the route geometry, so they can't
replace sharing the route. Apps may change their notifications at any time; unexpected content
is ignored.

**Privacy.** Notification access is optional and off until you enable it in
**Settings → Navigation → Enable navigation notifications**. Notifications from other apps are
skipped without reading their content, and nothing is stored or sent anywhere. On Android 13 and
later, apps installed from an APK may need **App info → ⋮ → Allow restricted settings** before
the switch can be turned on.

## Network use

Importing contacts only the services needed for that link: Komoot's API for Komoot tours,
Nominatim and OSRM for Google Maps links, and Google's short-link server to resolve
`maps.app.goo.gl` links. Files are processed on the phone.

## Ideas for later

- Re-route when you leave the route (needs your destination and a routing request).
- Komoot and Garmin accounts with OAuth, if they ever offer it to free apps.
- Android Auto / navigation SDK integrations, should one become available without fees.
