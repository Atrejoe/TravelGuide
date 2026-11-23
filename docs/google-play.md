# Publishing on Google Play

A checklist for putting Travel Guide on Google Play, with the answers this app needs for Play
Console's forms. Play's rules change; when a form asks something this page doesn't cover, go by
Play Console's own help.

## 1. Developer account

- Create it at [play.google.com/console](https://play.google.com/console): a one-time fee and an
  identity check.
- A **personal** account has to run a **closed test with at least 12 testers for 14 days in a row**
  before it can publish to everyone. Start this early. An organisation account (with a D-U-N-S
  number) doesn't need it.

## 2. The app bundle

Play only takes an app bundle (`.aab`). Every stable release (a `v*` tag) attaches
`travelguide-<version>.aab` to its GitHub release, next to the APK; a manual run of the *Android*
workflow has both as run artifacts. Both are signed with the stable key (see
[CONTRIBUTING.md](../CONTRIBUTING.md#signing)).

- The **application ID** is `io.github.atrejoe.travelguide`. It can never change once published.
- The **versionCode** comes from the clock (minutes since 2026-01-01), so every upload is higher
  than the one before, as Play requires.
- The bundle carries the native debug symbols, so Play's crash reports name the functions.

### Play App Signing: keep the stable key

Play signs the APKs it delivers with the *app signing key*. Choose to **use the existing stable
key** (Play Console → *Test and release → App integrity → App signing*, "Use a different key" /
"Export and upload a key from Java keystore"): Play gives you its encryption key and the PEPK tool
command, which uploads the stable key encrypted. Then an app installed from Play and an APK from
GitHub are signed alike, and each installs over the other.

Installs from GitHub look for newer releases on GitHub once a day and link to the release page.
An install from Play never does (the app checks which store installed it), as Play's policy
forbids apps from Play updating themselves another way.

If Play generates a new key instead, people who installed an APK from GitHub have to uninstall it
before installing from Play (their stories and settings are lost).

The bundle you upload is signed with the *upload key*. The simplest is to register the stable key
as the upload key too; Play also allows a separate upload key, which can be reset if it leaks.

## 3. Policy forms

### Privacy policy

URL: <https://github.com/Atrejoe/TravelGuide/blob/main/docs/privacy.md> (or the same page through
GitHub Pages).

### Data safety

Every connection uses HTTPS. The developer runs no server and keeps no user data, so there is
nothing to delete on request beyond uninstalling. Suggested answers:

| Data type | Collected | Shared | Optional | Processed ephemerally | Purpose |
| --- | --- | --- | --- | --- | --- |
| Location → **Precise location** | yes | yes: Wikipedia/Wikidata, OpenStreetMap services | no (the guide needs it) | yes | App functionality |
| App info and performance → **Crash logs**, **Diagnostics** | yes | no (Sentry processes them for the developer) | yes, off by default | no | Analytics |
| App activity → **Other user-generated content** (story reports: a comment, the reported story) | yes | no (Sentry processes them for the developer) | yes, only when you report | no | App functionality |
| Device or other IDs | yes (Sentry's random installation ID) | no | yes, with crash reports or a story report | no | Analytics |

Not collected: personal info, financial info, messages, photos, audio, files, contacts, calendar,
web history, health. Notifications read for navigation never leave the phone. Text sent to Claude
or ElevenLabs is about the place, not about you, and goes there only with the user's own key; it
is listed in the privacy policy.

### Permissions

- **Location** (precise, only while the guide runs; no background location): no declaration form.
  Android asks for it when you first press Start, so the request comes with what it is for.
- **Foreground service types** (Play Console → *App content → Foreground service permissions*):
  - `location`: the guide follows your position while it tells stories, with its notification
    showing; it starts when you press Start and stops when you press Stop.
  - `mediaPlayback`: it speaks the stories as audio that keeps playing with the screen off, and is
    controlled from the notification, headphones and the car.

  Each needs a short video of the feature in use: start the guide, show the notification, lock the
  phone while a story plays.
- **Notification access** (`NotificationListenerService`, optional, off by default): used only to
  follow a navigation app's turn-by-turn directions. Notifications from other apps are skipped
  unread and nothing is stored or sent ([navigation.md](navigation.md)). Expect review to ask about
  it; this is the explanation.

### Other forms

- **Ads**: none. **Target audience**: 13 and over, not designed for children. **News app**: no.
- **Content rating** questionnaire: reference app, no violence, no user-to-user communication, no
  purchases. Stories come from Wikipedia and may mention history (wars, deaths) in factual terms.
- **AI-generated content**: stories can be retold by an AI model (Claude, Gemini Nano or an open
  model). Play's policy asks for a way to report offensive generated content without leaving the
  app: every story has a **Report** link, which sends the reason, an optional comment and the
  story to the developer through Sentry (Sentry → *User Feedback*). It needs a build with the
  Sentry DSN, as every CI build with the repository's secrets has.

## 4. Android Auto

Opt in under Play Console → *Test and release → Advanced settings → Form factors → Android Auto*.
Google then reviews the app against its quality rules for car media apps. The app is a media app
(`MediaBrowserService`), shows its stories in one browsable tab and is controlled with the car's
own buttons, as those rules ask. Upload a car screenshot: `docs/screenshots/car-browse.png` is a
real one from the car emulator in CI.

## 5. Store listing

- App name, a short description (80 characters) and a full description, also in the app's other
  languages (Dutch, German, French, Spanish, Italian, Portuguese).
- Icon 512 × 512 px, feature graphic 1024 × 500 px.
- Phone screenshots: `docs/screenshots` has them, kept up to date by CI.
- Category: Travel & Local. Contact e-mail (required, shown publicly).
