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

Move the *Unreleased* entries under a new version heading in `CHANGELOG.md`, merge, then tag:

```bash
git tag v0.2.0 && git push origin v0.2.0
```

The *Android* workflow builds the APK and attaches it to the GitHub release.

## Keeping CI fast

- Put logic in `core/` whenever possible. It runs on the plain JVM and is tested in about a
  minute without the Android SDK.
- The Android build and lint run only when app-related files change.
- Prefer JVM tests (or screenshot tests that run on the JVM) over emulator tests.

## Dependency updates

[Dependabot](.github/dependabot.yml) opens grouped pull requests once a month: one for the
Android Gradle plugin, AndroidX and Compose, one for Kotlin and kotlinx, one for the other Gradle
dependencies, and one for GitHub Actions. The *Android* workflow builds and lints each Gradle
update. Before merging a Kotlin update, check that the Android Gradle plugin in use is supported
by that Kotlin version ([compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html#apply-the-plugin));
the Compose compiler plugin always uses the `kotlin` version from `gradle/libs.versions.toml`.

## Repository settings (one-time, by a maintainer)

These can't be set from code, so set them in the GitHub UI:

- **Branch protection** (Settings → Branches → Add rule for `main`): require a pull request
  before merging, require the status check **Core unit tests**, and disallow force pushes.
- **Merge button** (Settings → General): allow squash merging only, and use the PR title as the
  default commit message.
- **Project board** (Projects → New project → *Board*): link this repository and add the
  roadmap issues. Use the columns *Backlog / Next / In progress / Done*.
