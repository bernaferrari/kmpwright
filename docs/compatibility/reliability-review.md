# Reliability repair ledger

Baseline: public revision `9bd5ac745df83f84d21a2d5a1bb64eda61fce060`.
The external review supplied on 2026-10-11 is the repair specification. Earlier
859 JVM / 39 iOS / 55 Android results are historical checkpoints, not evidence
that the contracts below were correct. A checked item needs a regression at the
behavioral boundary. Native host checks must be distinguished from mocks.

The original checklist preserves the baseline review. The dated follow-up below
records the latest repairs and their behavioral evidence.

## Core contracts

- [ ] Locator identity: filters, indexed descendants, waits, assertions, boxes,
      screenshots, uploads, nested text, frames and strict selection.
      Initial real Chromium reproduction: hasText returned 2 instead of 1.
      Shared query stages now pass LocatorIdentityRegressionTest; wider suite pending.
- [ ] Persistent navigation events and document helper lifetime; link/form/script
      navigation, URL/title tracking, close/cancellation/concurrent deadlines.
- [ ] Default iOS network bridge: normalize default ports, Fetch URL/body
      semantics, terminal errors/timeouts, 204/205/304 null bodies, XHR.
- [ ] Android keyboard typing/insertText through the actual WebView editor;
      input, textarea and contenteditable device contracts.
- [ ] Cookies: numeric/session expiry, URL/domain/path identity, targeted Android
      deletion, iOS HttpOnly, validation before destructive state restoration.
- [ ] JVM context pages share target/session initialization and rollback.
- [ ] Live policies on existing/derived locators; eval/DOM extraction entry points
      and diffUrls obey the configured controls.
- [ ] Independent browser-farm leases cannot inherit storage state, init scripts,
      jobs, policies or any origin's storage.
- [ ] Routing replacement/removal, custom headers and OOPIF interception.
- [ ] Callback errors and close/disconnect complete every pending operation.

## Additional contracts

- [ ] Mobile storage getter expressions and restoring empty storage.
- [ ] Continuous 500ms NetworkIdle with a caller-owned deadline.
- [ ] Screenshot format/quality/background/scale forwarding or explicit rejection.
- [ ] Bounded, paginated Android PDFs and accurate iOS option support.
- [ ] Actual metrics or explicitly unavailable values; safely quoted script/style IDs.
- [ ] Compose uses configuration and exposes core automation with correct ownership,
      remember keys, controller replacement and WebView destruction.
- [ ] Cancellation releases held keys/touches; multi-touch configuration and mouse modifiers.
- [ ] InputMode and iOS gesture capability declarations match behavior.
- [ ] Viewport/user agent and data clearing await completion across backends.
- [ ] Tracing errors/EOF/base64 and browser-wide ownership.
- [ ] Connection health, provider reconnection cleanup and context ownership.
- [ ] Download ownership/context/path and protocol failures.
- [ ] HTTPS remote discovery retains scheme/path/query; remote uploads explicit limitation or transfer.
- [ ] Borrowed Android WebViews restore host clients.
- [ ] iOS custom-scheme cancellation and closed connection state.

## CI and presentation

- [ ] Install ripgrep before documentation verification on Ubuntu.
- [ ] Linux Compose publication uses a generated task; independent consumers pass.
- [ ] Android instrumentation green with usable failure reports and logcat.
- [ ] All relevant JVM/native/build/release checks green for the repaired revision.
- [ ] README demo shows a completed workflow; retain alpha and platform limits.

Keep KMPwright as the brand. Freeze feature expansion while repairing these
contracts. Do not mark the entire review solved from mock/string tests alone.

## Physical Android verification — 2026-10-12

The 57-test instrumentation suite passed on a Samsung SM-S931B running
Android 17 (API 37), with Android System WebView 154.0.8037.106.
This exercises real inputs, textareas and contenteditable typing, full-page
screenshots, and physical A4 PDF dimensions with multiple pages.

The first phone run exposed fractional CSS scroll coordinates being truncated
during PDF restoration. Capture now saves and restores the renderer's exact CSS
coordinates, and the original failing assertion passes without a tolerance.
Capture tile positioning also retains fractional CSS coordinates to reach the
final physical pixels. The navigation and event tests now trigger navigation and
assert the result, instead of waiting for timeouts and unconditionally passing. These results do
not establish completion of the remaining review contracts or green CI.

## Follow-up review repairs — 2026-10-13

The ten findings from the latest review have been repaired:

| Contract | Result | Regression boundary |
| --- | --- | --- |
| Strict frame owners | Ambiguous owners fail rather than selecting the first frame; scoped and nested frame paths retain their scope. | Real Chromium, hosted WKWebView, Android WebView |
| Storage restore refusal | Cookie, storage, navigation and origin checks run before destructive mutation; confirmation callbacks run once per action. | Shared policy/controller contracts |
| Context data clearing | JVM contexts retain visited origins after pages close and clear them through a temporary target in that context. | Real Chromium, including another context remaining untouched |
| Closing pending waits | Closing a page promptly ends navigation and event waiters. | Coroutine lifetime contract |
| Remote uploads | Both locator and chooser uploads require an explicit shared-filesystem opt-in for remote browsers; clearing remains available. | Locator and chooser contracts |
| Keyboard cancellation | Mobile delayed key presses release keys in bounded, cancellation-safe cleanup. | Shared contract plus native Android and iOS documents |
| Cancelled wheel events | iOS respects `preventDefault()` instead of scrolling anyway. | Hosted WKWebView |
| Touch identity and cancellation | iOS gestures preserve identifiers and original targets, serialize gestures, and send cancellation on interruption. | Hosted WKWebView |
| Closed browser/context creation | Mobile browsers and iOS contexts reject new pages after teardown; creation and teardown are ordered on the main thread. | Hosted WKWebView and Android WebView |
| Route header replacement | Android continuation replaces the request header map, so omitted headers are removed. | Android WebView against a local HTTP server |

Verification also exposed and repaired three wider correctness issues:

- A CDP request that starts in a parent renderer and finishes in a child keeps
  one logical identity, leaves the pending set, and reads its body from the
  correct session. Protocol events and real cross-process frames exercise this.
- Video encoding uses local monotonic frame-arrival times, and startup work does
  not consume the normal encoder-lag budget. A distant capture timestamp no
  longer freezes the recording; actual encoded contact sheets are checked.
- Frame input retries preserve the ancestor blocker at the deadline and do not
  send a click through it. The regression failed before this repair.

Android PDF capture now reads renderer scroll positions and rounds the maximum
extent inward; its focused emulator contract passes. Assertion mock fixtures
were aligned with the executed text/value expressions, and a nested-frame
fixture now waits safely for loading documents. Their assertions remain intact.

Artifact verification additionally caught platform-only regex options in common
metadata and architecture-dependent Foundation challenge constants in iOS
metadata. The shared regex code now uses portable equivalents, and native
challenge values use type-inferred numeric conversion. The independent Kotlin
consumer explicitly compiles the new remote-upload configuration field.

The landing page shows a runnable local form workflow, links to source setup,
and retains alpha and platform limits. Installation starts with source builds;
remote uploads and restore preflight are documented. The website has its own
type, lint and production-build CI job.

The complete JVM run passed 947 tests with zero failures, errors or skips,
including real Chromium contracts. The website's locked dependency install,
type checking, lint and production build also passed. Native and artifact
consumer results are recorded in the final validation checkpoint.

Final validation also passed all 85 iOS app-host tests, 56 bare simulator
contracts, 67 Android instrumentation tests, ABI checking, Compose lint, Android
example compilation and the shared iOS release framework. Local Maven
publications compiled independent Kotlin/Android consumers; both arm64
XCFramework slices, the deterministic archive/checksum and the artifact-only
Swift consumer passed. The final JVM rerun after the metadata fixes again passed
947 tests. Live vendor sessions and Linux NSS browser trust remain separate
runtime verification limits; these local results do not claim a GitHub CI run.

## GitHub environment follow-up — 2026-10-13

Run 38345184373 passed iOS, website and release-validation. Linux exposed an
older navigation command issuing after newer preparation, and target-close
acknowledgement preceding actual target removal. Command generations now
supersede stale preparation and replies (including HTML replacement); explicit
target closure awaits disappearance. The deterministic ordering regression
failed before the fix. All 948 JVM tests and ABI validation then passed locally.

The Android CI emulator had no permission to use `/dev/kvm`, so the runner
disabled acceleration and eventually timed out. CI now enables KVM, compiles
APKs before booting, uses the same direct instrumentation runner validated
locally, and bounds execution so logcat/screenshots can be uploaded on failure.
JVM exceptions and request-history failure context are retained in reports;
browser tests run after compilation/lint to avoid CPU contention.


The accelerated Android runner passed all 67 instrumentation tests in run
38349442939. Its multiline diagnostic handler was moved into a standalone shell
script because the emulator action executes each input line separately. Wrapper
probes verify success, failure and timeout exit codes and device-scoped capture.

Linux's remaining failure was an abandoned favicon request in the network-idle
count after timeout recovery. Current-document activity is now tracked separately
from complete request history. Document replacement and real frame detachment
retire old activity; renderer swaps keep the live request. Browser favicon
requests and EventSource streams do not block idle, while an explicit fetch of
the favicon URL still does. This follows the lifecycle and idle exclusions in
[Playwright's frame implementation](https://github.com/microsoft/playwright/blob/main/packages/playwright-core/src/server/frames.ts).
The regression failed before the repair; focused Chromium and protocol tests
then passed. Request completion remains observable after document replacement.

The landing page now pairs highlighted Kotlin calls with an interactive browser
illustration and four-step progress, gives developers and agent builders distinct
starting points, and links capabilities and native setup. The illustration is
clearly labelled; the complete Kotlin example is separately runnable. Responsive
layout, all example tabs, replay, type checking, lint and a production build were
verified.

The final local run passed all 950 JVM tests with zero failures, errors or skips,
and Kotlin ABI validation passed. Documentation/upstream metadata checks and the
final website type, lint and production build also passed.
## App review repairs — 2026-10-15

Baseline for this pass: `5b3107a`. This pass repairs the later app review;
the counts above belong to their recorded revisions.

- Storage restoration checks the actual document origin in the same JavaScript
  turn as its writes, so redirects cannot receive another origin's saved data.
  State enumeration and restoration preserve literal `__proto__` keys; empty
  maps clear both storage areas. Chromium redirect and hosted WKWebView tests
  execute these operations in real documents.
- Actions bound bridge work and input delays to the caller's deadline. Pointer
  and keyboard releases survive cancellation; request/response waiters end on
  page closure. Android editing keys use the same renderer selection as shortcuts.
- Cookie reads explicitly identify the CDP browser context. The original
  truncation claim was incorrect: the old constant already called
  `Network.getAllCookies`. Multi-origin/off-path cookie round trips and independent
  context isolation now have real Chromium coverage.
- The browser backend rejects unobservable network-idle waits and restores its
  owned dialog, history, focus, scrolling and diagnostic hooks on iframe disposal.
- Native recipes clean up routes on every exit and report cancellation accurately.
  Ref overlays follow direct document/layout changes and measure refs in batches;
  console entries, text and decoded screenshot history have retention limits.
  The iOS native shell explicitly closes its shared session.
- The live demo keeps retryable suggestions when refs are hidden and gives its
  controls 44-pixel touch targets. Desktop/mobile browser contracts run in CI.
- iOS document tests initialize a visible app host without caching startup failure.
  Captures tolerate WebKit's one-pixel rounding and avoid scrolling visible clips.
  Full ABI validation runs on macOS, where all supported targets are available;
  Linux continues through its actual browser tests.

Verification: 942 JVM tests (with Chromium and the React fixture), 78 Android
emulator tests, 98 app-hosted iOS tests (including
executing WKWebView documents), 62 bare iOS tests and six desktop/mobile browser
contracts passed. The SwiftUI example build, complete Kotlin ABI check, website
build/types/lint and documentation/upstream metadata checks passed. Maven Central
`0.0.1` remains the previously published immutable artifact; these source repairs
require a subsequent library release.

A follow-up deadline regression reproduced successful multi-step actions after
expiry and waits that outlived their budget while a bridge reply was pending.
All locator action entry points now include policy callbacks, runtime setup,
waits, reads and screenshot preparation in their operation deadline. The focused
regressions failed before this repair; the complete 942-test JVM suite then passed.
CI compile-checks the private sample's debug framework; release framework and
artifact-only Swift consumer checks remain enabled for the library.
