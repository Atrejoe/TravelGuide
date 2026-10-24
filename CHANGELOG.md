# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/). Commit messages follow
[Conventional Commits](https://www.conventionalcommits.org/).

## [Unreleased]

### Added

- Stories in Dutch ([#68](../../issues/68)): pick the language in Settings → Language. Places
  then come from the Dutch Wikipedia and Wikidata, the built-in storyteller tells them in Dutch,
  and three Dutch on-device voices can be downloaded (Pim and Ronnie, Netherlands; Nathalie,
  Flemish), or the phone's own Dutch voice is used. The language defaults to the phone's when
  stories can be told in it; the app's screens stay in English.
- Stories in German, French, Spanish, Italian and Portuguese too ([#68](../../issues/68)), each
  from its own Wikipedia, with two on-device voices per language: Thorsten and Kerstin (German),
  Tom and Siwis (French), Davefx (Spain) and Ald (Mexico), Paola and Riccardo (Italian), Tugão
  (Portugal) and Faber (Brazil).
- Places without an article in the story language are told too, translated
  ([#71](../../issues/71)): the guide also searches the Wikipedia of the country you're in and
  the English one, and translates what it picks from their articles on the phone (ML Kit, free
  and offline after a one-time download of about 30 MB per language). The opening line and
  remarks are written in the story language directly. Can be switched off in Settings → Stories.
- Stories about the wider area when nothing nearby is worth one ([#64](../../issues/64)): after
  two minutes without a story the guide looks three times further, after four minutes nine times
  (up to 10 km), and tells about places out of sight ("A little further afield, about 6 km to
  your left, lies …"). Nearby places go first again as soon as there is one.
- The guide screen shows what the guide is doing ([#65](../../issues/65)): searching Wikipedia,
  reading about a place, retelling it, loading the voice or preparing speech, and between
  stories when the next one can come or when it will look further afield.
- Opt-in crash reports ([#56](../../issues/56)): with *Send crash reports* on in Settings, a crash
  is reported to the developer through Sentry, including crashes in the open model's native code.
  Off by default; reports contain no location, places or stories.

### Changed

- Faster lite voices ([#59](../../issues/59)): Ryan now uses Piper's medium model, which renders
  about five times faster than the high one; download Ryan again once (67 MB instead of 110 MB).
  On phones with fast and slow cores, on-device voices run on the fast cores only, so the slow
  cores no longer hold every step up.

### Fixed

- Shorter pauses between sentences with on-device voices ([#63](../../issues/63)): a story now
  plays as one audio stream, so each sentence follows the previous one without the gap of
  opening and closing the audio output, with just a short breath in between.
- The lite voices (Piper: Norman, Ryan, Alan) crashed the app as soon as a story started
  ([#53](../../issues/53)): their 22 050 Hz audio got a buffer size the phone refuses. A voice
  that can't play now skips the story instead of crashing the app.

## [0.4.0] - 2026-10-20

### Added

- Optionally, a language model retells the stories ([#7](../../issues/7)): pick the storyteller
  in Settings → Stories. The built-in storyteller stays the default.
  - **Gemini Nano** runs on the phone through Android AICore: free, and nothing leaves the phone.
    Settings shows whether the phone offers it and can download it.
  - **Claude** uses your own Anthropic API key, stored encrypted with the Android Keystore. Choose
    the model (Claude Opus 5.5 by default, or Sonnet 5.5 or Haiku 5.5, with a cost per place) and
    a monthly budget.
  - Only the part about the place itself is retold, from its Wikipedia text; the opening line
    saying where it is and the nearby places stay as they are. Retold stories are kept, so a place
    is retold (and paid for) only once. When there's no key, the budget is spent, nothing comes
    back within 20 seconds or anything goes wrong, the built-in story is told.
- **Experimental:** an open model run by the app itself can retell the stories too
  ([#51](../../issues/51)): free, offline and private, on any 64-bit phone. Pick *Open model, on
  this phone* in Settings → Stories, download Qwen3 1.7B, Gemma 3 1B or SmolLM2 1.7B (about 1 GB),
  and use *Measure speed* to see how fast your phone runs it. Runs on llama.cpp, built from source.
- After a crash, Settings → Stories shows what the app was doing and how Android ended it, with a
  button to copy the report.

### Changed

- sherpa-onnx 1.13.8 for the on-device voices; Gradle 9.8.2 and kotlinx-serialization 1.12.0.

### Known issues

- The lite voices (Piper) crash the app when a story starts ([#53](../../issues/53)); fixed in the
  next release. Use a Kokoro voice until then.
- The open model may be too slow on a mid-range phone to retell a story in the 20 seconds it
  gets; you then hear the built-in story. Retelling ahead of time is planned
  ([#54](../../issues/54)).

## [0.3.0] - 2026-10-19

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

- The map in the README screenshots shows real streets instead of an empty grid (#39). A link to
  open the screenshots PR is now also commented on the PR whose merge changed them.
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
