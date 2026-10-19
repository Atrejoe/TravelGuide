# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/). Commit messages follow
[Conventional Commits](https://www.conventionalcommits.org/).

## [Unreleased]

### Fixed

- The map no longer jumps in size when guiding starts, and route lines and dots stay inside it
  (#33). The map's space is reserved as soon as guiding starts, the legend keeps a fixed height,
  and the map opens on your position instead of sliding there.
- Train travel is recognised from the railway you're on, not only from speed. Trains below
  145 km/h used to be shown as a car (#18). Railway lines come from OpenStreetMap (Overpass API),
  fetched only when moving fast and after moving a few kilometres.
- Shorter wait before a story and fewer pauses between sentences with on-device voices (#26).
  Articles of the next stories are fetched ahead of time, the voice is warmed up when it loads,
  and the whole story is rendered in the background as soon as it starts, with long sentences cut
  at commas so the first words come sooner. When a voice is too slow for the phone, the guide
  screen says so, and the voice preview in Settings shows how fast it renders. The preview no
  longer loads the voice on every tap: Settings loads the selected voice in the background, and
  changing pitch or rate no longer reloads it. While a preview plays, its button stops it.

### Added

- Every merge to `main` is tagged and published as an alpha pre-release, `v0.3.0-alpha.N`, with
  its APK (#36). This replaces the rolling *preview* pre-release.
- Screenshots in the README, rendered on the JVM after every merge; changed ones are pushed to a
  branch with a one-click PR link, so pull requests never wait for them (#36). GitHub Actions are
  pinned to commit SHAs, kept current by Renovate.
- On foot, the guide also tells about the town you enter and the buildings and institutions you
  walk past ([#35](../../issues/35)): town hall, churches, museums, libraries, universities,
  courthouses, theatres, stations, castles, mills, monuments and listed buildings, including ones
  without a Wikipedia article. They come from OpenStreetMap (Overpass API) and Wikidata: when a
  place was built or founded, by which architect, in which style, whether it is a listed monument
  (such as a Rijksmonument), whom it is named after and who uses it today. These facts become a
  short spoken story. A town gets its history-first Wikipedia story once per visit (found with
  Nominatim). A place that a Wikipedia article already covers is told only once. Places are only
  fetched while walking, within 500 m, again after moving on, with back-off on errors. You can
  switch this off in Settings → Stories. All sources are free and need no API key.
- A small play button on every story in the timeline plays it again (#31). A story being told is
  interrupted and carries on afterwards from where it was cut off; the button on the current story
  starts it over. Then the guide goes on as usual.
- Follow routes from navigation apps ([#5](../../issues/5)): share Google Maps directions, a Komoot
  tour link or a GPX/FIT file (e.g. a Garmin course export) with the app, or import a file in
  Settings → Navigation. Stories and the map then follow that route; the status card and the map
  legend say where it came from. Google Maps routes are recomputed with OSRM (and Nominatim for
  place names) because Google offers no route data; Komoot tours use an unofficial endpoint.
- Optional navigation notification access: shows when Google Maps, Komoot, Garmin, OsmAnd or Waze
  is navigating, with the next instruction, and drops a shared route when that navigation ends.
- Follow the navigation app live, without sharing a route or importing a file
  ([#28](../../issues/28)). With notification access, the next instruction ("in 300 m turn left
  onto …", also in Dutch and German) is read and the forecast follows the road through that
  turn. The road is matched by its OpenStreetMap name or number, so it works in any language.
  "Towards …" places may be routed to with OSRM. The map shows it as "Following Google Maps
  directions".
- When driving or cycling, the forecast follows the road you're on instead of a straight line
  ("Following the road"). Roads come from OpenStreetMap (Overpass API); side streets are only
  fetched while navigating or cycling, in a small radius.
- By train, the route forecast follows the railway through bends and junctions instead of a
  straight line, so upcoming places, their side and arrival times match the track (#19). The map
  shows it as "Following the railway".
- Map at the top of the guide screen showing where you are, the places in the current story and
  the upcoming ones. The road ahead is drawn as a solid line when it comes from navigation, and
  as a dashed line labelled "guessed" when it is estimated from your movement.
- In automatic travel mode, the detected mode is shown ("Auto · Cycling", "Cycling (detected)").

- Timeline on the guide screen: upcoming stories with their expected time on top, then the
  current story, then earlier ones, joined by a line on the left.
- Stories combine places along the route: up to two nearby places, or places the article
  mentions, are woven into one story ("Which brings us to …").

### Changed

- History first: historic places such as castles, forts, abbeys and battle sites are favoured.
  Plain municipalities are de-emphasised, and town stories draw on their history instead of
  administrative facts.
- Places we reach soon take precedence over equally interesting ones further ahead.
- The app now targets Android 17 (API 37) and is built with Android Gradle plugin 9.4 (built-in
  Kotlin), Gradle 9.7 and Kotlin 2.4, with current AndroidX, Compose and kotlinx libraries.
  Renovate proposes grouped dependency, GitHub Actions and sherpa-onnx updates monthly.
- CI builds the release variant, signed with one stable key, and every build gets a higher
  version code, so newer builds install over older ones (#15). Builds made before this change
  have to be uninstalled once. Version names now start from `appVersion` in `gradle.properties`,
  and tag releases use their `CHANGELOG.md` section as release notes.

### Fixed

- A planned route with long straight segments counted as "off route" between its points.

- Places beyond the predicted route were measured as off to the side, which gave wrong arrival
  times.
- A newer test build couldn't be installed over an older one, because every CI build was signed
  with a different random key. Testers had to uninstall and lose their voices and settings (#15).

## [0.2.0] - 2026-10-17

### Added

- Natural on-device voices from Kokoro (George, Lewis, Michael, Adam), sharing one 99 MB download,
  plus lighter Piper voices (Norman, Ryan, Alan).
- More engaging stories: the guide reads the full article and picks the best anecdotes, such as
  the origin of the name, legends, history and culture, instead of population figures. Varied
  openers, short spoken transitions and closing remarks.
- The app shows which voice is actually speaking, and why when it differs from your choice.
- *Voice samples* workflow that renders every voice for side-by-side listening.
- [Voice research](docs/voice-research.md) comparing on-device and cloud voices.

### Changed

- The next sentence is synthesised while the current one plays, with a short pause between
  sentences.
- Pitch now defaults to 1.0. Lowering it made voices sound metallic.

### Fixed

- A chosen on-device voice that was missing or failed to load silently fell back to the system
  voice, which often sounded like a robotic female voice.
- System voices that are listed but not installed are no longer offered.
- Sentences ending in a regnal number ("Henry V.") are now split correctly.


## [0.1.0] - 2026-10-17

### Added

- Proof of concept of the travel guide:
  - Platform-independent `core` module: travel-mode detection from speed, route prediction
    (dead reckoning or planned route), place scoring per travel mode, a Wikipedia geosearch
    client and template-based narration.
  - Android app with a foreground guide service, on-device neural voices (Piper via sherpa-onnx)
    with system-TTS fallback, adjustable pitch and rate, and a manual travel-mode override.
  - Pauses for phone calls and navigation prompts; pause and resume from the notification,
    the media controls and a Quick Settings tile.
  - CI: JVM core tests on every PR; Android build and lint on app changes, with APKs on PRs,
    a rolling `preview` pre-release from `main`, and release assets for tags.
- Apache 2.0 license.
