# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/). Commit messages follow
[Conventional Commits](https://www.conventionalcommits.org/).

## [Unreleased]

### Added

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

### Fixed

- Places beyond the predicted route were measured as off to the side, which gave wrong arrival
  times.

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
