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

# The same story in every language the catalog has voices for (see StoryLanguage in core).
STORIES = {
    "en": [
        "Look to your left. About two kilometres away lies Utrecht.",
        "It is a city and municipality in the Netherlands.",
        "Ever wondered about the name?",
        'The name Utrecht is derived from the Latin Ultraiectum, meaning "beyond the ford", referring to a crossing of the Rhine.',
        "Let's go back in time.",
        "The city dates back to the Roman fort of Traiectum, built around 50 AD.",
        "A tornado in 1674 destroyed the nave of the cathedral, which was never rebuilt.",
    ],
    "nl": [
        "Kijk naar links. Op zo'n twee kilometer ligt Utrecht.",
        "Het is een stad en gemeente in Nederland.",
        "Ooit afgevraagd waar de naam vandaan komt?",
        'De naam Utrecht komt van het Latijnse Ultraiectum, "voorbij de voorde", een oversteekplaats in de Rijn.',
        "Laten we teruggaan in de tijd.",
        "De stad gaat terug op het Romeinse fort Traiectum, gebouwd rond het jaar 50.",
        "In 1674 verwoestte een tornado het schip van de Domkerk, dat nooit werd herbouwd.",
    ],
    "de": [
        "Schauen Sie nach links. Etwa zwei Kilometer entfernt liegt Utrecht.",
        "Utrecht ist eine Stadt und Gemeinde in den Niederlanden.",
        "Woher kommt eigentlich der Name?",
        "Der Name Utrecht geht auf das lateinische Ultraiectum zurück, jenseits der Furt, eine Stelle, an der man den Rhein überquerte.",
        "Gehen wir zurück in der Zeit.",
        "Die Stadt geht auf das römische Kastell Traiectum zurück, erbaut um das Jahr 50.",
        "Ein Tornado zerstörte 1674 das Kirchenschiff des Doms, das nie wieder aufgebaut wurde.",
    ],
    "fr": [
        "Regardez sur votre gauche. À environ deux kilomètres se trouve Utrecht.",
        "C'est une ville et une commune des Pays-Bas.",
        "D'où vient ce nom ?",
        "Le nom Utrecht vient du latin Ultraiectum, au-delà du gué, un passage sur le Rhin.",
        "Remontons le temps.",
        "La ville remonte au fort romain de Traiectum, construit vers l'an 50.",
        "En 1674, une tornade détruisit la nef de la cathédrale, qui ne fut jamais reconstruite.",
    ],
    "es": [
        "Mire a su izquierda. A unos dos kilómetros se encuentra Utrecht.",
        "Es una ciudad y municipio de los Países Bajos.",
        "¿Se ha preguntado de dónde viene el nombre?",
        "El nombre Utrecht procede del latín Ultraiectum, más allá del vado, un paso para cruzar el Rin.",
        "Viajemos atrás en el tiempo.",
        "La ciudad se remonta al fuerte romano de Traiectum, construido hacia el año 50.",
        "En 1674 un tornado destruyó la nave de la catedral, que nunca se reconstruyó.",
    ],
    "it": [
        "Guardi alla sua sinistra. A circa due chilometri si trova Utrecht.",
        "È una città e un comune dei Paesi Bassi.",
        "Si è mai chiesto da dove viene il nome?",
        "Il nome Utrecht deriva dal latino Ultraiectum, oltre il guado, un punto in cui si attraversava il Reno.",
        "Torniamo indietro nel tempo.",
        "La città risale al forte romano di Traiectum, costruito intorno all'anno 50.",
        "Nel 1674 un tornado distrusse la navata del duomo, che non fu mai ricostruita.",
    ],
    "pt": [
        "Olhe para a esquerda. A cerca de dois quilómetros fica Utrecht.",
        "É uma cidade e município dos Países Baixos.",
        "Já se perguntou de onde vem o nome?",
        "O nome Utrecht vem do latim Ultraiectum, para lá do vau, uma passagem para atravessar o Reno.",
        "Vamos recuar no tempo.",
        "A cidade remonta ao forte romano de Traiectum, construído por volta do ano 50.",
        "Em 1674, um tornado destruiu a nave da catedral, que nunca foi reconstruída.",
    ],
}


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
        r'(?:,\s*speakerId = (\d+))?'
        r'(?:,\s*lang = "(\w+)")?'
        r'(?:,\s*espeakLang = "([\w-]+)")?',
        re.S,
    )
    for vid, name, desc, pkg_id, engine, pkg_ref, sid, lang, espeak in pattern.findall(source):
        if pkg_ref:
            pkg_id, engine = packages[pkg_ref]
        voices.append(dict(
            id=vid, name=name, description=desc, pkg=pkg_id, engine=engine, sid=int(sid or 0), lang=lang or "en", espeak=espeak,
        ))
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
                model=str(model), voices=str(voices), tokens=str(tokens), data_dir=str(data), lang=voice["espeak"]
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
    lines = ["| Voice | Language | Engine | Package | Speaker | Real-time factor (CI CPU) |", "|---|---|---|---|---|---|"]
    failures = []
    for voice in read_catalog():
        samples, rate, spent = [], 0, 0.0
        try:
            root = fetch(voice["pkg"], models)
            # The multilingual Kokoro model needs one engine per language it reads.
            key = (voice["pkg"], voice["espeak"])
            tts = engines.get(key) or engines.setdefault(key, engine_for(voice, root))
            for sentence in STORIES[voice["lang"]]:
                start = time.time()
                audio = tts.generate(sentence, sid=voice["sid"], speed=0.95)
                spent += time.time() - start
                rate = audio.sample_rate
                samples.extend(audio.samples)
                samples.extend([0.0] * int(rate * 0.25))  # the app's pause between sentences
        except Exception as e:  # keep going so the other voices still get samples
            print(f"{voice['id']} FAILED: {e}", flush=True)
            failures.append(voice["id"])
            lines.append(f"| {voice['name']} | {voice['lang']} | {voice['engine'].title()} | `{voice['pkg']}` | {voice['sid']} | **failed** |")
            continue
        mp3 = out / f"{voice['name']}-{voice['id']}.mp3"
        write_mp3(mp3, samples, rate)
        rtf = spent / (len(samples) / rate)
        lines.append(f"| {voice['name']} | {voice['lang']} | {voice['engine'].title()} | `{voice['pkg']}` | {voice['sid']} | {rtf:.2f} |")
        print(f"{voice['id']}: {mp3.name}, RTF {rtf:.2f}", flush=True)
    # Every Kokoro speaker announcing its number, to double-check the speaker ids in the catalog.
    kokoro = next((v for v in read_catalog() if v["engine"] == "KOKORO"), None)
    key = kokoro and (kokoro["pkg"], kokoro["espeak"])
    if key in engines:
        tts = engines[key]
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
