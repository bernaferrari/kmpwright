# Plan 001: Make the public documentation truthful and easy to follow

> **Completed (2026-08-30):** The documentation work is done. Release
> identity, licensing, publication metadata, and release gates are now
> implemented elsewhere in the worktree; this plan's earlier blockers and
> assumptions below are retained as historical execution context. External
> promotion of `0.1.0` has not been claimed.

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report; do not improvise. When done, update this plan's row in
> `plans/README.md`.
>
> **Drift check (run first)**:
> `git diff --stat 38df0f5..HEAD -- README.md KMP_README.md KMP_ANALYSIS.md V1_3_PLAN.md plans/README.md plans/001-make-public-documentation-truthful.md`
> If an in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live files before proceeding. A mismatch
> is a STOP condition. This plan was written against a dirty rename worktree;
> do not discard, reset, or overwrite those existing edits.

## Status

**DONE** — README, implementation notes, historical labels, license link, and
release-aware installation guidance were updated and verified against the live
source tree. The release guide documents the implemented validation,
publication, and XCFramework scripts without claiming an external release.

- **Priority**: P1
- **Effort**: M
- **Risk**: LOW
- **Depends on**: none
- **Category**: docs
- **Planned at**: commit `38df0f5`, 2026-08-29

## Why this matters

The primary README is substantially clearer than the older project documents,
but it still teaches selector forms that the real resolver cannot execute,
offers a non-copyable clone command, and links to active-looking documents that
contradict the current Kotlin Multiplatform tree. A new adopter can therefore
understand the product idea and still fail in the first ten minutes. This plan
makes the documentation concise, current, and deliberately honest about what is
not yet installable or supported; it does not paper over missing release work.

## Historical snapshot (superseded)

The following original analysis and execution instructions are retained for
traceability only. They describe the state before release identity,
publishing, licensing, selector support, and demo changes landed. They are not
current requirements.

## Current state (historical snapshot)

- `README.md` is the public entry point. Preserve its useful platform table,
  transparent pre-release warning, safety section, capability guidance, module
  map, and focused build commands.
- `README.md:49-55` contains `git clone --recurse-submodules <repository-url>`.
  The checkout has no configured Git remote, so there is no verified canonical
  URL to substitute.
- `README.md:68-92` says separate repositories can use a composite build or a
  locally published snapshot. The Gradle tree has no `maven-publish` block,
  project `group`, or published artifact version, so neither route is currently
  documented as a complete, reproducible workflow.
- `README.md:101-108` uses
  `find(role = "button", name = "Sign in", exact = true)`. In
  `PageConvenience.kt:141-149`, a missing named match silently falls back to
  `locator("role=button")`, which may target an unrelated button.
- `README.md:186-197` advertises `exact_text=`, `placeholder=`, `alt=`, and
  `testid=` selector prefixes. `BaseLocator.kt:494-560` implements only
  `role=`, `label=`, `text=`/`text:`, and `xpath=` before falling back to CSS.
  The four advertised prefixes are therefore invalid CSS and resolve to no
  elements.
- `KMP_README.md:11-15` describes obsolete `androidTarget()` configuration;
  the live `kmpwright-core/build.gradle.kts:18-23` uses the Android KMP library
  DSL. `KMP_README.md:105-112` also contains a Swift example that does not
  construct `IOSPlatformContext` or match the suspend API.
- `KMP_ANALYSIS.md:246-270` says the KMP plugin and platform source sets were
  removed, contradicting the live Android, iOS, and JVM targets. It is useful
  project history, not current architecture documentation.
- `V1_3_PLAN.md:88-111` lists routing and request/response bodies as missing,
  although they now exist in `Page.kt` and `RequestResponse.kt`. It is a
  historical plan, not a current roadmap.
- `README.md:445-447` correctly says the repository has MIT intent but no root
  `LICENSE`. Keep that warning until an authorized owner supplies the legal
  copyright line and a real license file is added.

Repository conventions to preserve:

- Public examples use `com.bernaferrari.kmpwright.core.*` and suspending Kotlin
  calls.
- Commands use the renamed `kmpwright-*` Gradle modules.
- The writing style is direct, uses short sections, and states backend limits
  instead of claiming false parity.
- Recent commit subjects are short imperatives (for example,
  `Update Webwright for AGP 9.2`).

## Commands you will need

| Purpose | Command | Expected on success |
| --- | --- | --- |
| Confirm project graph | `./gradlew projects` | exit 0; lists `kmpwright-core`, `kmpwright-compose`, `kmpwright-example-shared`, and `kmpwright-test` |
| JVM contract tests | `./gradlew :kmpwright-core:jvmTest` | exit 0; all tests pass |
| Android/example compile | `./gradlew :kmpwright-core:assembleAndroidMain :kmpwright-compose:lint :example-android:assembleDebug` | exit 0 |
| Reject stale selector claims | `rg -n 'exact_text=|placeholder=|alt=|testid=' README.md KMP_README.md` | exit 1; no matches |
| Reject unsafe first example | `rg -n 'find\(role = "button", name = "Sign in"' README.md` | exit 1; no matches |
| Reject obsolete setup/examples | `rg -n 'androidTarget\(\)|Browser\(context: self\)' KMP_README.md` | exit 1; no matches |
| Formatting check | `git diff --check -- README.md KMP_README.md KMP_ANALYSIS.md V1_3_PLAN.md plans` | exit 0; no whitespace errors |

## Scope

**In scope** (the only files you should modify):

- `README.md`
- `KMP_README.md`
- `KMP_ANALYSIS.md`
- `V1_3_PLAN.md`
- `plans/README.md`
- `plans/001-make-public-documentation-truthful.md`

**Out of scope** (do not touch):

- All Kotlin and Gradle source files. Broken selector behavior, assertion
  semantics, lifecycle handling, and publishing need separate implementation
  plans and tests.
- `.github/workflows/ci.yml`; its stale `:webwright-test` task and branch filter
  are a separate CI defect.
- `.gitmodules` and `agent-browser`; preserve the dirty submodule state.
- Creating `LICENSE` or inventing a copyright owner.
- Inventing Maven coordinates, a release version, a repository URL, badges, user
  counts, benchmarks, platform parity, or support promises.
- Adding generated screenshots, GIFs, binaries, or other marketing assets.

## Git workflow

- Branch: `codex/001-truthful-readme`
- Commit the documentation as one logical unit with subject
  `Clarify Kmpwright setup and support`.
- Do not push or open a pull request unless the operator explicitly asks.
- Do not stage or commit unrelated rename or submodule changes already present
  in the worktree.

## Steps

### Step 1: Preserve the strong README structure and define the audience

Edit `README.md` without rewriting it wholesale. Keep the existing opening,
platform-support table, capability checks, safety guidance, module map, and
build commands. Make the first screen answer, in this order: what Kmpwright is,
who it is currently for, which backends exist, and that it is source-only alpha
software. Keep the table of contents only if every anchor still exists.

Replace the clone placeholder with prose that explains recursive submodules are
required but does not present a fake copy-paste command. State that a canonical
clone URL will be added when the repository is published.

**Verify**: `rg -n '<repository-url>|^## (Why Kmpwright\?|Platform support|Try the example app|Add Kmpwright to a project|Build and test)$' README.md`
→ no `<repository-url>` match; every named heading has exactly one match.

### Step 2: Make installation limitations actionable and exact

In `README.md`'s installation section, distinguish these two cases explicitly:

1. Modules inside this checkout can use `implementation(project(":kmpwright-core"))`
   and, on Android, `implementation(project(":kmpwright-compose"))` as currently
   shown.
2. Separate repositories do not yet have a supported, copy-pasteable dependency
   path because publishing metadata is absent.

Remove the unsupported suggestion to "use a composite build or publish a local
snapshot". Do not replace it with guessed Gradle configuration. Give readers a
clear decision: evaluate the checked-out example now, or wait until publishing
is configured before adopting it as an external dependency.

Keep the root-license warning. Add a short release-readiness checklist containing
only the real blockers: canonical repository URL, clean/reproducible submodule,
publishing coordinates, root `LICENSE`, and working CI gates.

**Verify**: `rg -n 'composite build|local snapshot|group =|maven-publish|<repository-url>' README.md`
→ exit 1; no matches.

### Step 3: Stop teaching broken selector and finder behavior

Replace the first automation's role-and-name `find` call with a selector that the
current resolver actually implements and that is unambiguous in the sample DOM,
such as a stable CSS attribute selector. Do not imply that role-and-name
filtering is safe until the fallback defect is fixed.

In the selector reference, document only the currently implemented special
forms: CSS, `role=`, `label=`, `text=`/`text:`, and `xpath=`. Remove
`exact_text=`, `placeholder=`, `alt=`, and `testid=` from public examples. Add a
one-sentence note that convenience finders for those forms are not release-ready
until DOM-level contract tests pass.

**Verify**:
`rg -n 'exact_text=|placeholder=|alt=|testid=|find\(role = "button", name = "Sign in"' README.md KMP_README.md`
→ exit 1; no matches.

### Step 4: Turn `KMP_README.md` into current implementation notes

Rewrite `KMP_README.md` as a compact current-state companion rather than a
second onboarding guide. It must cover Android `WebView`, iOS `WKWebView`, and
JVM CDP; point to the live source-set names; and defer API usage to the root
README. Remove the non-compiling direct Swift snippet. If direct Swift use has
not been compiled in CI, say it is not yet a documented supported path and link
to the working shared Compose iOS host instead.

Do not restate feature claims that are already represented by runtime
`PageCapabilities`; direct readers to capability checks and the root table.

**Verify**: `rg -n 'Android|iOS|JVM|commonMain|androidMain|iosMain|jvmMain|PageCapabilities' KMP_README.md`
→ each platform/source-set term and `PageCapabilities` appears at least once.

### Step 5: Label historical documents so they cannot masquerade as truth

Add a prominent dated historical-status notice at the top of
`KMP_ANALYSIS.md` explaining that it records an earlier feasibility/rollback
state and must not be used as current architecture documentation. Add an
equivalent completed/superseded notice to `V1_3_PLAN.md`; preserve their body as
history rather than silently rewriting past decisions.

Update `README.md:435` so active documentation links only to the rewritten
current implementation notes. Put historical links under an explicitly labeled
history sentence, or omit them from the onboarding path.

**Verify**: `sed -n '1,12p' KMP_ANALYSIS.md V1_3_PLAN.md`
→ both files show an unambiguous `Historical` or `Superseded` notice and a date
within their first 12 lines.

### Step 6: Validate every retained command and finish the copy pass

Run the project graph, JVM tests, Android artifact/lint, example build, stale
claim checks, and `git diff --check` commands from the table above. Read the
README once from top to bottom and remove duplicated warnings, unexplained
acronyms, and dead links. Do not weaken any safety or platform-limit statement
to make the project sound more complete.

Update the row in `plans/README.md` from `TODO` to `DONE` only after every
command meets its expected result.

**Verify**: `git diff --name-only -- README.md KMP_README.md KMP_ANALYSIS.md V1_3_PLAN.md plans`
→ lists only the six in-scope paths.

## Test plan

- No source tests are created in this documentation-only plan.
- Run `./gradlew :kmpwright-core:jvmTest` to ensure examples still name live API
  concepts and the working tree remains healthy.
- Run the Android artifact/lint/example command to ensure every retained Gradle
  command names real modules and compiles.
- Run every negative `rg` check to prove that known broken selector forms,
  obsolete Gradle setup, unsafe finder example, and fake installation claims are
  gone.
- If a local Markdown link checker is already installed, run it against the four
  documentation files. Do not install a new dependency solely for this plan.

## Completion outcome (current)

- [x] README explains the product, audience, Android/iOS/JVM backends, and
      alpha/unpromoted status.
- [x] Installation guidance distinguishes owner-supplied source evaluation
      from future Maven Central coordinates and does not fake a clone command.
- [x] README documents the tested selector forms and safe role/name
      composition.
- [x] KMP_README describes current source sets and all three backends.
- [x] KMP_ANALYSIS.md and V1_3_PLAN.md are visibly historical/superseded.
- [x] Root MIT LICENSE exists and is linked.
- [x] Release scripts, local publication, XCFramework packaging, and
      independent consumers are documented; external promotion is not claimed.
- [x] Static documentation/link/API verifier and `git diff --check` pass.

The expensive JVM/Android/native release gates were already green in the
release implementation work and were not rerun for this documentation follow-
up.

## STOP conditions (historical)

Stop and report back; do not improvise if:

- Any "Current state" excerpt no longer matches the live worktree.
- The existing uncommitted rename changes would be overwritten or lost.
- A truthful installation guide requires Kotlin/Gradle publishing changes.
- A canonical repository URL or legal owner is still unknown and a step appears
  to require guessing either one.
- Documentation of a selector or Swift API cannot be proven against live source
  or a compiling example.
- A verification fails twice after correcting an obvious documentation typo.
- The work appears to require touching an out-of-scope file.

## Maintenance notes (historical, superseded)

- After the selector resolver and role/name finder are fixed with DOM-level
  tests, re-add those selector forms to the README from the tests' exact syntax.
- After publishing metadata exists, replace the source-only limitation with
  real coordinates and a consumer smoke test. Do not publish coordinates in
  prose before CI proves them.
- After an authorized owner adds `LICENSE`, replace the warning with a normal
  link to the license file.
- After the demo lifecycle is fixed and device-tested, add one concise visual
  proof near the top of the README: preferably a short Android/iOS side-by-side
  clip showing the same shared automation flow. Reviewers should reject a large
  decorative hero that hides installation and platform limits below the fold.
