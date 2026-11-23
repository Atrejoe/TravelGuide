# Privacy policy

Travel Guide is an Android app that tells you about the places around you while you travel.
This page says what it does with your data. It applies to the app as published on Google Play
and on [GitHub](https://github.com/Atrejoe/TravelGuide/releases).

*Last updated: 22 November 2026.*

## In short

- The app has **no account, no ads and no analytics**, and the developer runs no server: nothing
  you do in the app is sent to the developer.
- To find places, the app sends **your position** to public map and encyclopedia services
  (Wikipedia, Wikidata, OpenStreetMap). It doesn't send who you are.
- Stories are written on your phone by default. Only when **you** turn on a cloud storyteller or
  voice and enter **your own key** for it does text about the place go to that service.
- **Crash reports are off** until you turn them on, and contain no location or place names.

## Location

The app uses your precise location **only while the guide runs**: from the moment you start it
until you stop it, shown by its notification. It doesn't ask for location in the background.

Your position is sent to:

| Service | What for | What it receives |
| --- | --- | --- |
| [Wikipedia](https://foundation.wikimedia.org/wiki/Policy:Privacy_policy) and [Wikidata](https://foundation.wikimedia.org/wiki/Policy:Privacy_policy) | finding articles about places near you, and their details | a point and a search radius |
| [OpenStreetMap](https://osmfoundation.org/wiki/Privacy_Policy) map tiles | the map on the guide screen | the map squares around you |
| [Overpass API](https://overpass-api.de/) (OpenStreetMap data) | buildings and places Wikipedia lacks, and railway lines when you travel by train | a point and a search radius |
| [OpenStreetMap Nominatim](https://osmfoundation.org/wiki/Privacy_Policy) | which country and town you are in, for the language of the places and stories about the town; and, when your navigation app shows where you are heading, where that place is | a point; the name of the place you are heading for |
| [routing.openstreetmap.de](https://routing.openstreetmap.de/) (FOSSGIS) | the road ahead, when your navigation app shows where you are heading | your position and the place you are heading for |

Like any website, these services also see your phone's IP address and the app's name and version.
Their own privacy policies (linked above) apply to what they receive.

## Routes

When you share or import a route (from a navigation app, a Google Maps or Komoot link, or a GPX
file), the app may contact:

- **Google Maps** (only for a short `maps.app.goo.gl` link): to follow the link to the full
  route, without loading the page.
- **[Komoot](https://www.komoot.com/privacy)**: to load a Komoot tour you shared.
- **OpenStreetMap Nominatim**: to find the places a route names.
- **routing.openstreetmap.de**: to work out the roads between the route's points.

A route is kept on your phone until you clear it.

## Navigation notifications (optional)

If you allow it under **Settings → Navigation**, the app reads the turn-by-turn notifications of
your navigation app (such as Google Maps or Waze), to tell stories about places along the road
ahead and to pause for directions. Notifications from other apps are skipped without reading their
content. The notifications themselves are not stored or sent anywhere; only the name of the place
you are heading for is looked up, as described under Location. See
[navigation.md](navigation.md).

## Stories and voices

By default, stories are put together and spoken **on your phone**: by the built-in storyteller,
Gemini Nano (Google's on-device model, where the phone has it) or an open model you download, and
by the phone's own voices or downloaded neural voices.

You can choose cloud services instead. They are off until you choose them and enter your **own**
API key, which is kept encrypted on your phone (with a key from Android's keystore) and sent only
to that service:

| Service | What it receives |
| --- | --- |
| [Anthropic Claude](https://www.anthropic.com/legal/privacy) (retelling stories) | the place's name and Wikipedia text, which way and how far it is from you (for example "left · 300 m"), how you travel, your chosen interests and the story's language. **Not** your position. |
| [ElevenLabs](https://elevenlabs.io/privacy-policy) (voice) | the text to be spoken |

What these services do with it is covered by their own policies and your agreement with them.
They charge your account for what you use.

## Downloads

Voices, the open storyteller models and translation models are downloaded when you choose them:
from [GitHub](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement)
(voices), [Hugging Face](https://huggingface.co/privacy) (open models) and Google (ML Kit translation
models and Gemini Nano, through Google Play services). These downloads send no data about you
beyond what any download does (your IP address).

## Updates (installs from GitHub)

If you installed the app from GitHub, Google Play can't update it, so once a day the app asks
GitHub (`api.github.com`) for the list of its releases, to tell you when there is a newer one.
GitHub sees your IP address and the app's name and version, nothing else. Turn it off under
**Settings → Updates**. Installed from Google Play, the app never checks: Play updates it.

## Crash reports (optional)

Off by default. If you turn on **Settings → Send crash reports**, a crash sends a report to
[Sentry](https://sentry.io/privacy/): the error and where in the app it happened, what the app was
doing (for example "running the open model"), the app version and the phone model and Android
version, and a random ID that Sentry makes for the installation (to tell one phone's crashes from
many phones'). Reports contain **no location, no place names, no screenshots** and nothing that
identifies you. Turn it off at any time.

## On your phone

The app keeps its settings, the stories you heard (title, time and where the place is) and
downloaded voices and models on your phone. If Android backup is on, Android may back up the
settings and history to your Google account; your API keys can't be restored from a backup on
another phone and have to be entered again there. Uninstalling the app, or clearing its storage,
deletes all of it.

## Children

The app is not directed at children.

## Changes

Changes to this policy are published on this page, with a new date at the top, and in the app's
[changelog](../CHANGELOG.md).

## Contact

Questions about privacy: open an issue at
[github.com/Atrejoe/TravelGuide/issues](https://github.com/Atrejoe/TravelGuide/issues), or use the
contact address on the app's Google Play page.
