# Travel Guide

An Android audio companion that tells you about the places you travel through: their history,
landscape, nature and culture. It speaks in a calm, deep narrator voice that runs entirely on
your phone.

> **Status: proof of concept.** The guide works end to end (location → stories → voice), but
> expect rough edges. See the [roadmap](#roadmap).

## Screenshots

_Screenshots follow once the first APK has been tested on a device (tracked in the roadmap)._

## What it does

- **Narrates your surroundings** from geotagged Wikipedia articles: towns you enter, rivers you
  cross, castles on the hill.
- **Looks ahead.** It predicts where you will be in the next few minutes. With a planned route
  it follows that route; otherwise it extends your current heading. It tells you about what is
  *coming up* rather than what you just passed, and says on which side it is.
- **Adapts to how you travel.** The speed decides the detail level:

  | Mode | Looks for | Search radius | Look-ahead | Story length |
  |---|---|---|---|---|
  | Walking | buildings, landmarks, streets | 1 km | 2 min | up to 6 sentences |
  | Cycling | landmarks, villages | 2.5 km | 3 min | up to 5 sentences |
  | Car | towns, rivers, hills | 10 km | 5 min | up to 4 sentences |
  | Train | towns, regions, landscape | 10 km | 5 min | up to 3 sentences, "out of the left-hand window" |
  | Plane | regions, mountains, lakes | 10 km | 10 min | up to 2 sentences |

  The mode is detected from your speed (with hysteresis against GPS glitches). You can also pick
  it yourself, because car and train speeds overlap.
- **Soothing on-device voice.** Neural Piper voices run with
  [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). They are free and work offline, and the
  pitch is lowered slightly for a deep narrator tone. Any Android system voice works too.
- **Pauses when it should.** Phone and VoIP calls and navigation prompts (e.g. Google Maps
  saying "turn left") pause the guide, and it resumes the sentence afterwards. Music is ducked
  while the guide speaks.
- **Pause from anywhere.** Use the media controls in the notification shade, the lock screen,
  Bluetooth/headset buttons or the **Quick Settings tile**.
- **No paid AI at runtime.** Stories come from Wikipedia text with hand-written phrasing, so
  there are no API costs. Bring-your-own-key LLM storytelling is on the roadmap as an option.

## Install

Download the latest APK:

- **Stable**: the [latest release](../../releases/latest).
- **Development**: the [*preview* pre-release](../../releases/tag/preview), rebuilt from every
  app change on `main`.
- **A pull request**: the APK artifact on that PR's *Android* workflow run.

Enable "Install unknown apps" for your browser or file manager, open the APK, start the app and
tap **Start guiding**. On first use, open **Settings → Narrator** and download a voice (~64 MB).

## How it works

```
GPS fixes ─▶ MotionTracker ─▶ travel mode, speed, heading
                                │
               RouteExtrapolator (planned route or dead reckoning)
                                │  predicted path
Wikipedia geosearch ─▶ PoiScorer (prominence × interest × geometry × mode weights)
                                │  best place
                     NarrationComposer (opener + cleaned article sentences)
                                │
                  Narrator (Piper via sherpa-onnx, or system TTS) ─▶ 🔊
```

| Module | Contents |
|---|---|
| `core/` | Pure Kotlin, no Android: geo math, motion detection, route prediction, scoring, Wikipedia client, narration text. Fast JVM unit tests. |
| `app/` | Android app (Kotlin, Jetpack Compose): foreground service, location, audio focus, media session, notification, Quick Settings tile, voice download and playback. |

## Develop

Requirements: JDK 17+, Android SDK (API 35) for the app.

```bash
# Core logic only; needs no Android SDK
./gradlew :core:test -PcoreOnly=true

# Full app
scripts/fetch-sherpa-onnx.sh      # downloads the TTS runtime into app/libs (once)
./gradlew :app:assembleDebug
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the workflow (feature branches, Conventional Commits,
changelog, CI).

### CI

- **CI** runs the JVM core tests on each PR (about a minute, no Android SDK).
- **Android** builds and lints the app when app-related files change. PRs get an APK artifact,
  `main` updates the `preview` pre-release, and `v*` tags attach the APK to their release.

## Roadmap

Planned features are tracked as [issues](../../issues), such as voice choice and
Dutch narration, interest selection, navigation-app routes, start suggestions, optional BYO-key
LLM storytelling and climate facts.

## Credits

- Place information: [Wikipedia](https://www.wikipedia.org/) contributors, under
  [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). The app links every story to
  its source article.
- Speech: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache 2.0) with
  [Piper](https://github.com/rhasspy/piper) voices (see each voice's model card for its license).

## License

[Apache License 2.0](LICENSE). Wikipedia content and voice models keep their own licenses (see
Credits).
