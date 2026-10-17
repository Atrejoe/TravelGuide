# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/). Commit messages follow
[Conventional Commits](https://www.conventionalcommits.org/).

## [Unreleased]

### Added

- Proof of concept of the travel guide:
  - Platform-independent `core` module: travel-mode detection from speed, route prediction
    (dead reckoning or planned route), place scoring per travel mode, a Wikipedia geosearch
    client and template-based narration.
  - Android app with a foreground guide service, on-device neural voices (Piper via sherpa-onnx)
    with system-TTS fallback, adjustable pitch and rate, and a manual travel-mode override.
  - Pauses for phone calls and navigation prompts; pause and resume from the notification,
    the media controls and a Quick Settings tile.
  - CI that keeps costs down: JVM-only tests on PRs, and APK builds only when needed.
