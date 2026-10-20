# Voice research

_Last updated: October 2026_

The goal is a soothing, natural narrator, in the spirit of a deep, warm documentary voice
(we do not imitate any real person's voice). The constraints: it must work on Android, be free
to run, work offline, and optionally let users plug in a paid voice service with their own key.

## Why the first version sounded robotic

1. **Piper "medium" voices** are fast and small, but noticeably synthetic.
2. **Pitch shifting.** The app lowered the pitch to 0.9 with `PlaybackParams`, and Android does
   that by time-stretching, which adds a metallic edge. Pitch now defaults to 1.0 and is only
   applied when the user changes it.
3. **Silent fallback.** If the chosen voice was not downloaded or failed to load, the app quietly
   used the phone's system voice, which is often a female voice. The app now shows which voice
   is actually speaking and why.
4. **System voices that aren't installed.** Engines such as Google's list voices whose data still
   has to be downloaded. Choosing one gives you the default voice instead, so they are hidden now.

## Options compared

### On-device (free, offline) via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)

| Model | Naturalness | Download | Speed on phones | Male voices | License |
|---|---|---|---|---|---|
| **Kokoro v0.19 int8** | Very good: natural prosody and breathing | 99 MB (all 11 speakers) | Real time on recent mid/high-end phones; slower than real time on budget phones | George, Lewis (British), Michael, Adam (American) | Apache 2.0 |
| Kokoro v1.x multi-lang | Very good; more voices | 140 MB (int8) – 348 MB | As above | More, but needs extra lexicons | Apache 2.0 |
| Piper high | Fair | 110 MB (the fp16 export crashes on some sentences) | Five times slower than medium; slower than real time on mid-range phones | Ryan (until 0.5.0) | MIT / per-voice |
| Piper medium | Fair to robotic | 34 MB (fp16), 67 MB (fp32) | Very fast | Norman, Alan, Ryan, Joe, … | MIT / per-voice |
| Kitten TTS | Fair; expressive for its size | ~25 MB | Slow for its size | Several | Apache 2.0 |
| Supertonic | Good, multilingual | Varies | Fast | Several | License unclear (check before use) |

In CI (2 CPU threads) Kokoro takes 0.5–1.1 s per second of audio, and Piper medium 0.03–0.05 s.
Measured on an x86 CPU with sherpa-onnx 1.13.8, the Piper variants compare as follows (seconds per
second of audio, 1 / 2 / 4 threads):

| Piper model | fp32 | fp16 | int8 |
|---|---|---|---|
| Norman medium | 0.11 / 0.09 / 0.07 | 0.12 / 0.07 / 0.04 | 0.32 / 0.26 / 0.27 |
| Ryan medium | 0.09 / 0.06 / 0.05 | doesn't load | 0.28 / 0.22 / 0.23 |
| Ryan high | 0.70 / 0.37 / 0.24 | 0.68 / 0.40 / 0.20 | 1.90 / 1.66 / 1.59 |

The int8 exports are smaller but about three times slower, and barely gain from more threads.
Other speed figures are indicative: a Kokoro app built on sherpa-onnx reports about 20–40 s of
generation per minute of audio on a Snapdragon 8 Gen 3, and 60–90 s on mid-range phones. The app
synthesises the next sentence while the current one plays, which hides most of that.

### Android system voices (free)

Quality depends on the phone. Google's "high quality" voices are decent and some are male, but
there is no API to ask for a gender, and many voices need a separate download first.

### Cloud voices (bring your own API key, paid per use)

| Service | Quality | Approximate price | Notes |
|---|---|---|---|
| ElevenLabs | Best-in-class narrators; voice design lets you describe "a deep, warm, older narrator" | ~$40–100 per million characters | Free tier ~20k characters/month |
| OpenAI `gpt-4o-mini-tts` | Very good, **steerable by instructions** ("speak like a calm documentary narrator") | ~$0.015 per minute of audio | Same key could later power LLM storytelling |
| Google Cloud Chirp 3 HD | Very good | ~$30 per million characters | 1M characters/month free |
| Azure Neural (HD) | Very good, many styles | ~$15–22 per million characters | |

An hour of guiding is roughly 20–30 minutes of speech, about 25k characters. At $30 per million
characters that is under $1 per hour.

## Decision

- **Default: Kokoro "George"** (deep, warm British storyteller), with Lewis, Michael and Adam as
  alternatives. One download serves all four.
- **Lite: Piper Norman, Ryan and Alan** (medium) for older phones.
- **System voices** remain available.
- **Next:** bring-your-own-key cloud voices (OpenAI first, for its instruction-steerable delivery,
  then ElevenLabs). Tracked in issue #2.

To hear every voice, run the *Voice samples* workflow (or look at the `voice-samples`
pre-release). It renders the same short story with each voice using the app's settings.

## Sources

- [sherpa-onnx TTS model releases](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models)
- [Kokoro 1.0 in sherpa-onnx (speaker ids)](https://github.com/k2-fsa/sherpa-onnx/pull/1795)
- [VoxSherpa TTS, Kokoro on Android with sherpa-onnx (speed estimates)](https://github.com/CodeBySonu95/VoxSherpa-TTS)
- [On-device TTS comparison 2026 (Picovoice)](https://picovoice.ai/blog/on-device-tts/)
- [Best local TTS models 2026](https://www.yaps.ai/blog/best-local-tts-models)
- [TTS API cost calculator (prices as of Oct 2026)](https://inventivehq.com/tools/developer/tts-stt-api-cost-calculator)
- [Comparing TTS pricing across providers 2026](https://gradium.ai/content/how-to-compare-tts-pricing-across-providers-2026)
