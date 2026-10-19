---
name: release-indagium
description: Prepare, publish, and verify a versioned Indagium desktop release, including the "what changed" release notes. Use when asked to bump an Indagium version, write release notes, create a release commit/tag, merge it to master, push GitHub and GitLab, trigger the GitHub four-platform build, or verify published release assets.
---

# Release Indagium

Follow this workflow for a release. Treat pushes, tags, and release publication as external state changes: obtain explicit user authorization before performing them.

## 1. Preflight

- Read `CLAUDE.md` and `.github/workflows/build.yml` before changing version or release state.
- Inspect `git status --short --branch`, remotes, current branch, the latest version tag, and the remote `master` tips. Preserve unrelated working-tree changes.
- Confirm both required remotes (`origin` for GitHub and `gitlab`) exist. Stop if a release would overwrite or merge unknown remote work.

## 2. Version and validation

- Update `app.version` in `gradle.properties`.
- In the same commit, update the README version badge and the `git tag vX.Y.Z` examples in both `README.md` and `CLAUDE.md`.
- Run `./gradlew build`; it is the release gate and includes desktop tests, Detekt, and KtLint. Use the IDEA build integration as an additional check when it responds.
- Fix all local validation failures before committing. Do not tag a build that has not passed the full gate.

## 3. Release notes

Every release gets hand-written notes on what changed. The GitHub workflow only adds the download table and an automatic commit list, which is not enough for users.

- **Collect the changes.** Find the previous release tag (`git describe --tags --abbrev=0 --match 'v*'`). Read `git log --no-merges <prevTag>..HEAD` with full commit bodies, and look at the diff where a commit message is too thin to say what a user would notice.
- **Write `docs/releases/X.Y.Z.md`** in the style of the existing files (`docs/releases/1.8.10.md`, `1.8.7.md`):
  - A `# Indagium X.Y.Z` title, then a one-sentence summary of the release's main additions.
  - `##` sections grouped by user-visible area (for example "Notes", "Voice dictation", "AI test suites", "Capture"). Each section describes what users can now do, or what behaves differently, in plain present-tense prose. Name the real UI labels and shortcuts.
  - A final `## Fixes` section with one short bullet per user-visible fix, describing the symptom that is gone (for example "The notes panel no longer jumps when you click into a note").
  - Leave out internal-only work: refactors, lint, tests, CI and build tweaks, and docs-only changes. Include an internal fix only through its user-visible effect (a deadlock fix becomes "no longer freezes when…").
  - Call out anything platform-specific, anything that needs user action (permissions, migrations, new settings), and format or compatibility changes.
  - Link the User Guide or website section when a feature has one.
  - Never mention customer names, real log content, internal ticket numbers, or other confidential details.
- **Get approval before committing.** Show the user the draft notes and wait for approval or edits, because they are published verbatim.

## 4. Commit and publish

- Commit the version bump, the release notes file and any other release changes together on the current release branch.
- Fetch both remote `master` branches. Fast-forward local `master` to the release commit only when both remotes are at the expected ancestor; otherwise stop and resolve the divergence deliberately.
- Create an annotated `vX.Y.Z` tag on the final `master` commit.
- Push `master` and the tag to both `origin` and `gitlab`.

## 5. Verify GitHub release

- The tag push triggers `.github/workflows/build.yml`; monitor the `Build Distributions` run with `gh run list` and `gh run view`.
- Require successful `verify`, then all packaging matrix jobs: Linux x64 `.deb`, Linux arm64 `.deb`, Windows x64 `.msi`, and macOS arm64 `.dmg`; finally require successful `release`.
- Confirm `gh release view vX.Y.Z` reports a non-draft release with those four assets.
- **Publish the notes.** Put the release notes at the top of the GitHub release, above the workflow's download table and commit list:
  - Save the current body: `gh release view vX.Y.Z --json body -q .body > <scratch>/generated.md`.
  - Build `<scratch>/release-body.md` from `docs/releases/X.Y.Z.md` without its `# Indagium X.Y.Z` title line (the release already shows the version), then a blank line, then `generated.md`.
  - Apply it with `gh release edit vX.Y.Z --notes-file <scratch>/release-body.md`.
  - Check with `gh release view vX.Y.Z` that the notes come first and the download table and **Full Changelog** link are still there.
- Include links to the workflow run and the release in the final report.

## Release recovery

- If CI fails, retrieve the exact failed log with `gh run view <run-id> --log-failed`, fix the source issue, rerun `./gradlew build`, and create a corrective commit on `master`.
- Do not silently move a published tag. Explain the need to force-update the tag and obtain user confirmation unless the user’s release request explicitly authorizes retrying the same version/tag.
- After an authorized tag move, force-push only that tag to both remotes, verify a new tag-triggered GitHub run starts, and repeat the release verification. A re-run recreates the release body, so publish the notes again afterwards.
- To correct published notes, fix `docs/releases/X.Y.Z.md` on `master` and apply them again with `gh release edit`. Never move a tag only to change notes.
