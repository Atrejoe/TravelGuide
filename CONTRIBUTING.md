# Contributing

## Workflow

1. Every change starts from an issue on the project board.
2. Create a feature branch from `main`: `feat/<short-name>`, `fix/<short-name>`, `docs/<short-name>`.
3. Commit using [Conventional Commits](https://www.conventionalcommits.org/):
   `feat(core): weigh places by user interests`, `fix(app): resume after a call`.
   Use a scope of `core`, `app`, `ci` or `docs` where it helps.
4. Add user-facing changes to `CHANGELOG.md` under **Unreleased**.
5. Open a pull request. App changes get a downloadable APK on the PR's *Android* workflow run.
6. Keep the branch current by rebasing it onto `main` (`git rebase origin/main`, then
   `git push --force-with-lease`) whenever `main` moves, rather than merging `main` into it.
7. Squash-merge into `main` with a Conventional Commit title.

## Releases

While in alpha, every merge to `main` is released automatically: the *Android* workflow tags it
`v<appVersion>-alpha.N` (one above the highest existing alpha tag of that version) and publishes a
pre-release with the APK and the PRs merged since the previous tag as notes.

The base version lives in `gradle.properties` (`appVersion`). For a stable release, move the *Unreleased*
entries under a new version heading (`## [0.3.0] - YYYY-MM-DD`) in `CHANGELOG.md`, merge, then tag:

```bash
git tag v0.3.0 && git push origin v0.3.0
```

The *Android* workflow builds the APK, creates the GitHub release with that version's
`CHANGELOG.md` section as release notes (or generated notes if there is none) and attaches the
APK. Then bump `appVersion` for the next version.

### Versions

- **versionName**: `appVersion` plus `-prN.run` for pull requests, `-alpha.N` for merges to
  `main`, `-dev.run` for manual runs, or the tag without the `v` (`v0.3.0` → `0.3.0`). Local
  builds use `appVersion`.
- **versionCode**: CI uses the minutes since 2026-01-01 UTC. Android refuses to install a lower
  versionCode over a higher one, so the code must only go up across all builds, including PRs,
  re-runs and a renamed workflow. Run numbers don't guarantee that; the clock does. Local builds
  use 1.

## Signing

Android only installs an update when it is signed with the same key as the installed app
([#15](../../issues/15)). CI therefore signs every build, debug and release, with one long-lived
key stored in repository secrets, and builds the **release** variant. Without the secrets (for
example on pull requests from forks) it builds the debug variant with a throwaway key and shows a
warning; such builds don't install over other builds.

One-time setup, by a maintainer:

1. Create the key, outside the repository (needs a JDK for `keytool`, and `openssl`):

   ```bash
   scripts/create-signing-key.sh            # default: ~/.travelguide/travelguide-signing.jks
   ```

2. **Back up the keystore file and its password** (e.g. in a password manager). If the key is
   lost, no later build can update installed apps and every user has to uninstall.
3. Add the four repository secrets. The script prints the exact `gh secret set` commands. Without
   the GitHub CLI, add them under **Settings → Secrets and variables → Actions → New repository
   secret**:

   | Secret | Value |
   |---|---|
   | `SIGNING_KEYSTORE_BASE64` | the keystore file, base64-encoded on one line |
   | `SIGNING_KEYSTORE_PASSWORD` | the keystore password |
   | `SIGNING_KEY_ALIAS` | `travelguide` |
   | `SIGNING_KEY_PASSWORD` | the key password (the same as the keystore password) |

4. Re-run the *Android* workflow, or push to `main`. The build log no longer shows the
   "No stable signing key" warning.

**One-time uninstall.** Builds made before the secrets existed are signed with random keys, so
the first stable-signed build can't update them: uninstall the old app once (this removes the
downloaded voices and settings) and install the new build. From then on every build installs
over the previous one and keeps your voices and settings.

To sign local builds with the same key, set `TRAVELGUIDE_KEYSTORE_FILE` (absolute path),
`TRAVELGUIDE_KEYSTORE_PASSWORD`, `TRAVELGUIDE_KEY_ALIAS` and `TRAVELGUIDE_KEY_PASSWORD` as
environment variables or Gradle properties (e.g. in `~/.gradle/gradle.properties`). Never commit
a keystore or password; `*.jks` and `*.keystore` are git-ignored.

## Crash reports (Sentry)

Testers can opt in to crash reports in Settings → *Send crash reports* (issue #56). The switch is
only there in builds with a Sentry DSN; without one the app never reports.

- **Set up once:** create a Sentry project for Android and add these repository secrets.
  - `SENTRY_DSN`: the project's DSN. It is the same for every user and ends up in the APK, so it
    is not a real secret; keeping it in a secret only keeps forks and local builds from reporting.
  - `SENTRY_AUTH_TOKEN` (optional, an organization token): CI then uploads the native debug
    symbols of stable-signed builds, so llama.cpp crashes show function names and lines. The
    organization (`atrejoe-5fd385238`) and project (`travelguide`) are set in
    `.github/workflows/android.yml`.
- **Locally:** pass `-PSENTRY_DSN=…` to Gradle to try it.
- **Privacy:** reports have no personal data, location, place names, screenshots or tracing; what
  the app was doing (e.g. "writing a story with Gemma 3 1B") goes along as a breadcrumb.
- **Reading reports from Claude:** connect Sentry's MCP connector at claude.ai → Customize →
  Connectors, then start a new session.

## Keeping CI fast

- Put logic in `core/` whenever possible. It runs on the plain JVM and is tested in about a
  minute without the Android SDK.
- The Android build and lint run only when app-related files change.
- Prefer JVM tests (or screenshot tests that run on the JVM) over emulator tests.

## Screenshots

The README shows screenshots from `docs/screenshots/`, rendered on the JVM by
`app/src/test/.../ScreenshotTest.kt` (Robolectric and Roborazzi, no emulator) with fixed sample
content. Rendering takes a few minutes, so pull requests don't do it:

1. After every merge to `main`, the *Android* workflow runs `./gradlew :app:recordRoborazziDebug`.
2. When an image changed, it pushes them to the `bot/screenshots` branch and starts the required
   *CI* check on that commit. A **Create the pull request** link appears in the run summary and as
   a comment on the PR that was just merged, so it arrives as a notification.
3. Open that PR and merge it whenever convenient (if one is already open, it's updated instead).
   Its merge isn't tagged or rebuilt.

So `main` gets current screenshots without anyone's PR waiting for them, at the cost of one click.
Workflows deliberately can't open or approve PRs here (**Settings → Actions → General → Allow
GitHub Actions to create and approve pull requests** stays off), so a compromised workflow step
can't get code into `main` on its own. For the same reason every action in the workflows is
pinned to a full commit SHA; Renovate keeps those pins up to date.

The map shows real OpenStreetMap tiles from `app/src/test/resources/screenshot-tiles.zip`, a
dozen tiles around the sample position, committed so the images don't depend on the network or on
map edits. If the sample position or zoom changes, delete the archive: the next manual run of the
*Android* workflow fetches it with `scripts/fetch-screenshot-tiles.sh` and pushes it to
`bot/screenshots-preview`, from where it can be committed.

To look at them locally (needs the Android SDK): `./gradlew :app:recordRoborazziDebug`. To see
how a branch looks, run the *Android* workflow manually on it: the screenshots are uploaded as an
artifact and pushed to the `bot/screenshots-preview` branch. Add a screen by adding a test to
`ScreenshotTest` and the image to the README.

## Dependency updates

[Renovate](renovate.json) opens grouped pull requests once a month: one for the Android Gradle
plugin, AndroidX and Compose, one for Kotlin and kotlinx, one for the other Gradle dependencies
and the Gradle wrapper, one for GitHub Actions, and one for the sherpa-onnx text-to-speech runtime
(`sherpa-onnx.version`). Its *Dependency Dashboard* issue lists pending updates and lets you
trigger one early. The *Android* workflow builds and lints each Gradle and sherpa-onnx update.
Before merging a Kotlin update, check that the Android Gradle plugin in use is supported
by that Kotlin version ([compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html#apply-the-plugin));
the Compose compiler plugin always uses the `kotlin` version from `gradle/libs.versions.toml`.

Renovate runs as a GitHub App: a maintainer installs [Renovate](https://github.com/apps/renovate)
once for this repository (free for public repositories). Validate changes to `renovate.json` with
`npx --package renovate -- renovate-config-validator --strict`.

## Repository settings (one-time, by a maintainer)

These can't be set from code, so set them in the GitHub UI:

- **Branch protection** (Settings → Branches → Add rule for `main`): require a pull request
  before merging, require the status check **Core unit tests**, and disallow force pushes.
- **Merge button** (Settings → General): allow squash merging only, and use the PR title as the
  default commit message.
- **Project board** (Projects → New project → *Board*): link this repository and add the
  roadmap issues. Use the columns *Backlog / Next / In progress / Done*.
