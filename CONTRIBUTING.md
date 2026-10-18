# Contributing

## Workflow

1. Every change starts from an issue on the project board.
2. Create a feature branch from `main`: `feat/<short-name>`, `fix/<short-name>`, `docs/<short-name>`.
3. Commit using [Conventional Commits](https://www.conventionalcommits.org/):
   `feat(core): weigh places by user interests`, `fix(app): resume after a call`.
   Use a scope of `core`, `app`, `ci` or `docs` where it helps.
4. Add user-facing changes to `CHANGELOG.md` under **Unreleased**.
5. Open a pull request. App changes get a downloadable APK on the PR's *Android* workflow run.
6. Squash-merge into `main` with a Conventional Commit title.

## Releases

The base version lives in `gradle.properties` (`appVersion`). To release, move the *Unreleased*
entries under a new version heading (`## [0.3.0] - YYYY-MM-DD`) in `CHANGELOG.md`, merge, then tag:

```bash
git tag v0.3.0 && git push origin v0.3.0
```

The *Android* workflow builds the APK, creates the GitHub release with that version's
`CHANGELOG.md` section as release notes (or generated notes if there is none) and attaches the
APK. Then bump `appVersion` for the next version.

### Versions

- **versionName**: `appVersion` plus `-prN.run` for pull requests, `-dev.run` for `main`, or the
  tag without the `v` (`v0.3.0` → `0.3.0`). Local builds use `appVersion`.
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

## Keeping CI fast

- Put logic in `core/` whenever possible. It runs on the plain JVM and is tested in about a
  minute without the Android SDK.
- The Android build and lint run only when app-related files change.
- Prefer JVM tests (or screenshot tests that run on the JVM) over emulator tests.

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
