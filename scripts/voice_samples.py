#!/usr/bin/env python3
"""Renders an audition sample for every on-device voice in the app's catalog.

Reads the catalog from VoiceCatalog.kt (single source of truth), downloads each model package,
synthesises the same short story with sherpa-onnx using the same configuration as the app, and
writes one MP3 per voice plus a summary with synthesis speed. Used by the voice-samples workflow.

Usage: voice_samples.py <models-dir> <output-dir>
"""
import re
import sys
import tarfile
import time
import urllib.request
from pathlib import Path

import sherpa_onnx
import soundfile as sf

CATALOG = Path(__file__).resolve().parent.parent / "app/src/main/kotlin/io/github/atrejoe/travelguide/voice/VoiceCatalog.kt"
URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/{}.tar.bz2"

STORY = [
    "Look to your left. About two kilometres away lies Utrecht.",
    "It is a city and municipality in the Netherlands.",
    "Ever wondered about the name?",
    'The name Utrecht is derived from the Latin Ultraiectum, meaning "beyond the ford", referring to a crossing of the Rhine.',
    "Let's go back in time.",
    "The city dates back to the Roman fort of Traiectum, built around 50 AD.",
    "A tornado in 1674 destroyed the nave of the cathedral, which was never rebuilt.",
]


def read_catalog():
    # Drop line comments so they cannot break the patterns below.
    source = re.sub(r"//[^\n]*", "", CATALOG.read_text())
    packages = {
        name: (pkg_id, engine)
        for name, pkg_id, engine in re.findall(
            r'val (\w+) = VoicePackage\("([^"]+)", VoiceEngine\.(\w+)', source
        )
    }
    voices = []
    pattern = re.compile(
        r'NeuralVoice\(\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*'
        r'(?:VoicePackage\("([^"]+)", VoiceEngine\.(\w+), \d+\)|(\w+))'
        r'(?:,\s*speakerId = (\d+))?',
        re.S,
    )
    for vid, name, desc, pkg_id, engine, pkg_ref, sid in pattern.findall(source):
        if pkg_ref:
            pkg_id, engine = packages[pkg_ref]
        voices.append(dict(id=vid, name=name, description=desc, pkg=pkg_id, engine=engine, sid=int(sid or 0)))
    declared = len(re.findall(r"\bNeuralVoice\(\s*\"", source))
    if not voices or len(voices) != declared:
        sys.exit(f"Parsed {len(voices)} of {declared} voices in VoiceCatalog.kt; update the parser in voice_samples.py")
    return voices


def fetch(pkg_id, models):
    target = models / pkg_id
    if not target.exists():
        archive = models / f"{pkg_id}.tar.bz2"
        print(f"Downloading {pkg_id}", flush=True)
        urllib.request.urlretrieve(URL.format(pkg_id), archive)
        with tarfile.open(archive) as tar:
            tar.extractall(models, filter="data")
        archive.unlink()
    return target


def find(root):
    files = [p for p in root.rglob("*") if not p.name.startswith(".")]
    model = max((p for p in files if p.suffix == ".onnx"), key=lambda p: p.stat().st_size)
    tokens = next(p for p in files if p.name == "tokens.txt")
    data = next(p for p in files if p.is_dir() and p.name == "espeak-ng-data")
    voices = next((p for p in files if p.name == "voices.bin"), None)
    return model, tokens, data, voices


def engine_for(voice, root):
    model, tokens, data, voices = find(root)
    if voice["engine"] == "KOKORO":
        model_config = sherpa_onnx.OfflineTtsModelConfig(
            kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
                model=str(model), voices=str(voices), tokens=str(tokens), data_dir=str(data)
            ),
            num_threads=2,
        )
    else:
        model_config = sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(model), tokens=str(tokens), data_dir=str(data)),
            num_threads=2,
        )
    return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=model_config))


def write_mp3(path, samples, rate):
    # libsndfile (bundled with the soundfile wheel) encodes MP3 itself; no ffmpeg needed.
    sf.write(path, samples, samplerate=rate, format="MP3")


def main():
    models, out = Path(sys.argv[1]), Path(sys.argv[2])
    models.mkdir(parents=True, exist_ok=True)
    out.mkdir(parents=True, exist_ok=True)
    engines = {}
    lines = ["| Voice | Engine | Package | Speaker | Real-time factor (CI CPU) |", "|---|---|---|---|---|"]
    failures = []
    for voice in read_catalog():
        samples, rate, spent = [], 0, 0.0
        try:
            root = fetch(voice["pkg"], models)
            tts = engines.get(voice["pkg"]) or engines.setdefault(voice["pkg"], engine_for(voice, root))
            for sentence in STORY:
                start = time.time()
                audio = tts.generate(sentence, sid=voice["sid"], speed=0.95)
                spent += time.time() - start
                rate = audio.sample_rate
                samples.extend(audio.samples)
                samples.extend([0.0] * int(rate * 0.25))  # the app's pause between sentences
        except Exception as e:  # keep going so the other voices still get samples
            print(f"{voice['id']} FAILED: {e}", flush=True)
            failures.append(voice["id"])
            lines.append(f"| {voice['name']} | {voice['engine'].title()} | `{voice['pkg']}` | {voice['sid']} | **failed** |")
            continue
        mp3 = out / f"{voice['name']}-{voice['id']}.mp3"
        write_mp3(mp3, samples, rate)
        rtf = spent / (len(samples) / rate)
        lines.append(f"| {voice['name']} | {voice['engine'].title()} | `{voice['pkg']}` | {voice['sid']} | {rtf:.2f} |")
        print(f"{voice['id']}: {mp3.name}, RTF {rtf:.2f}", flush=True)
    # Every Kokoro speaker announcing its number, to double-check the speaker ids in the catalog.
    kokoro = next((v for v in read_catalog() if v["engine"] == "KOKORO"), None)
    if kokoro and kokoro["pkg"] in engines:
        tts = engines[kokoro["pkg"]]
        roll = []
        for sid in range(tts.num_speakers):
            audio = tts.generate(f"This is speaker number {sid}.", sid=sid, speed=1.0)
            roll.extend(audio.samples)
            roll.extend([0.0] * int(audio.sample_rate * 0.4))
        write_mp3(out / "kokoro-all-speakers.mp3", roll, audio.sample_rate)
        lines.append(f"\nKokoro package has {tts.num_speakers} speakers; `kokoro-all-speakers.mp3` lets you check the ids.")
    (out / "summary.md").write_text("\n".join(lines) + "\n")
    if failures:
        sys.exit(f"Voices failed to synthesise: {', '.join(failures)}")


if __name__ == "__main__":
    main()
