---
name: release-openlog
description: Prepare, publish, and verify a versioned openLog desktop release. Use when asked to bump an openLog version, create a release commit/tag, merge it to master, push GitHub and GitLab, trigger the GitHub four-platform build, or verify published release assets.
---

# Release openLog

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

## 3. Commit and publish

- Commit the version bump and release changes on the current release branch.
- Fetch both remote `master` branches. Fast-forward local `master` to the release commit only when both remotes are at the expected ancestor; otherwise stop and resolve the divergence deliberately.
- Create an annotated `vX.Y.Z` tag on the final `master` commit.
- Push `master` and the tag to both `origin` and `gitlab`.

## 4. Verify GitHub release

- The tag push triggers `.github/workflows/build.yml`; monitor the `Build Distributions` run with `gh run list` and `gh run view`.
- Require successful `verify`, then all packaging matrix jobs: Linux x64 `.deb`, Linux arm64 `.deb`, Windows x64 `.msi`, and macOS arm64 `.dmg`; finally require successful `release`.
- Confirm `gh release view vX.Y.Z` reports a non-draft release with those four assets. Include links to the workflow run and release in the final report.

## Release recovery

- If CI fails, retrieve the exact failed log with `gh run view <run-id> --log-failed`, fix the source issue, rerun `./gradlew build`, and create a corrective commit on `master`.
- Do not silently move a published tag. Explain the need to force-update the tag and obtain user confirmation unless the user’s release request explicitly authorizes retrying the same version/tag.
- After an authorized tag move, force-push only that tag to both remotes, verify a new tag-triggered GitHub run starts, and repeat the release verification.
