---
description: Bump this toolkit's version, sync every consumer's pin, and refresh every consumer's libs-repo to only the latest version. No commits.
---

Arguments: `$ARGUMENTS` — an explicit version (e.g. `0.2.16`), or empty to bump the patch number.

Run this from **lunula** — the repo whose version is being bumped. Do the steps, then stop. **Never commit or push** — not here, and not in any consumer.

## The consumers

Wherever a step below says *each consumer*, it means every row of this table. Paths are relative to this repo (`lunula/main`):

| Consumer | Path from here |
|---|---|
| lunamux | `../../lunamux/main` |
| lunicle | `../../lunicle/main` |
| lunapin | `../../lunapin/main` |

Each one owns three things this command depends on: a `lunula = "…"` pin in `gradle/libs.versions.toml`, a committed `libs-repo/`, and a `refreshLunula` task in its `build.gradle.kts`. If a consumer is missing any of them, or its checkout is not on disk, **say so and stop** rather than bumping the rest.

Do not quietly skip one. A consumer left on the old pin still builds for anybody who has a sibling lunula checkout — the composite build in its `settings.gradle.kts` silently wins over the pin — and fails for everybody who does not: CI, a fresh clone, a release build. That failure is invisible on the machine that did the bump, which is exactly how lunamux sat on 0.2.67 while its merged code needed 0.2.68.

## Steps

1. Read the current version from `build.gradle.kts` here (the `allprojects { version = "..." }` line). The new version is `$ARGUMENTS` if given, otherwise the current version with its patch bumped by 1.
2. Set that new version in `build.gradle.kts`.
3. Set `lunula = "<new version>"` in **each consumer's** `gradle/libs.versions.toml`.
4. Publish. The direction is **consumer-pulls**: each consumer owns a `refreshLunula` task that invokes this build with that consumer's own libs-repo as the publish target. So run it from **each consumer**, never from here:
   - `./gradlew refreshLunula` from `../../lunamux/main`
   - `./gradlew refreshLunula` from `../../lunicle/main`
   - `./gradlew refreshLunula` from `../../lunapin/main`

   Do **not** run `./gradlew publishAllToLibsRepo` here on its own. With no `-Plunula.publishTarget` it publishes into the throwaway `build/local-libs-repo` in this repo, so it reports success while leaving every consumer on the old version (see the comment at the top of `build.gradle.kts`). Standing in the toolkit makes that the tempting command — it is the wrong one.
5. Clean **each consumer's** libs-repo so it holds only the new version. In that consumer's `libs-repo/se/soderbjorn/lunula/`, for every `lunula-*` module, delete every version subdirectory except the new version's:
   ```
   find <consumer>/libs-repo/se/soderbjorn/lunula -mindepth 2 -maxdepth 2 -type d ! -name "<new version>" -exec rm -rf {} +
   ```
6. Report the old → new version, and confirm **per consumer** that its pin reads the new version and its libs-repo holds only that version. Name every consumer you touched; if you stopped early on one, say which and why.

Everything is left uncommitted, here and in every consumer, for a human to review and commit.

## Adding a consumer

Add a row to the table and a bullet to step 4 — the other steps are already written against *each consumer*. Check first that it really has the pin, the `libs-repo/` and the `refreshLunula` task, because every step here assumes all three.
