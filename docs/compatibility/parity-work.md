# Browser feature parity work

Requested 2026-10-11 against upstream `d9570915f4dd6504dee4070379d2c4f79d81dbb2`.
The owner confirmed: **all browser features through Kotlin APIs and examples**.
The CLI, daemon, MCP server, executable plugins, chat gateway, upstream release
packaging, and upstream installation commands are excluded. Existing platform
constraints must be represented accurately by capabilities and tested errors.
No completed status below is inferred from an API merely existing.

## Work checklist

- [x] Refresh stable dependencies, examples, publication fixtures, wrapper and CI actions.
- [x] Remove Intel iOS simulator targets at the owner's request.
- [x] Retain one Android test activity, isolate WebViews per test, show count/name progress.
- [x] Preserve caller cancellation in CDP transport deadlines.
- [x] Correct Android screenshot final-tile overscroll and density-independent assertions.
- [x] Verify dependency checkpoint: updated iOS native tests, frameworks, Maven and Swift consumers (new parity features receive subsequent checks).
- [x] Native locator actions, shadow/label hit testing and dialog interruption.
- [x] Browser-computed accessibility roles/names and scoped snapshot behavior.
- [x] Cross-origin/process frame discovery, session routing, nested scopes and input coordinates.
- [x] Session setup inheritance: headers, credentials, routes, emulation and init scripts.
- [x] Pre-navigation init-script registration and removal by identifier (Chrome and physical Android verified; WebKit compiled).
- [x] WebMCP discovery, bounded summaries, invocation/result/cancellation and navigation invalidation.
- [x] Bundled axe accessibility audits with selector errors and per-frame reports.
- [x] Web Vitals, React tree/render/Suspense inspection and router-aware navigation.
- [x] HTTP Markdown negotiation, HTML fallback and llms.txt traversal (real HTTP/Chrome fixtures; portable compile and content contracts).
- [x] Persistent bounded request history/detail and backend-appropriate HAR bodies.
- [x] Browser endpoint/window/tab attachment and discarded-tab handling.
- [x] Browser video recording, cursor, contact sheets and screencast delivery.
- [x] Upload/file chooser, profiles/auth state and remaining backend capability gaps.
- [x] Portable examples, real-browser regression fixtures and compatibility metadata for each port.
- [x] Reconcile every runtime commit in upstream-review.md with implementation/test evidence.

## Verification checkpoint

After dependency upgrades: 802 JVM tests including isolated real Chrome passed;
Android Compose lint and both Android examples built. The latest shared-host
Android suite passed 50/50. The upgraded dependencies passed 29 iOS contracts, both arm64 framework slices, Swift consumption, and local Maven consumers. Later API additions receive fresh verification.

The complete original commit inventory is in upstream-changes.json. Review
entries are updated only after corresponding behavior has been implemented and
verified. Unsupported platform behavior is not renamed to “supported” to make
the table look complete.

## Added browser contracts

- JVM locators dispatch native mouse/touch/keyboard input; the Chrome fixture checks trusted events, editable fields, Unicode typing, overlays and stale references.
- CDP keyboard input omits null optional fields and supports modifier chords with bounded cancellation cleanup.
- Document-start scripts register through CDP, AndroidX WebKit and WKUserScript. Removal preserves unrelated registrations; Android now passes 51 physical tests.
- Tabs opened from a page inherit successful headers, credentials, routes, emulation and init-script setup before navigation. Setup order is retained; failed setup closes the new page. Chrome verifies inherited init scripts and viewport.
- Script-result decoding is backend-aware: literal strings `null`/`undefined` retain their value. Page evaluation propagates JavaScript errors.
- After these additions, all 802 JVM tests including real Chrome passed; Android and iOS core compiled and the Android example built.

- Chrome accessibility snapshots now use browser-computed roles, names and state metadata with the existing stable DOM ref registry. Open shadow roots participate in snapshots and ref actions. Real Chrome verifies slider/spinbutton roles, ARIA name traversal and shadow actions. This historical checkpoint used DOM hierarchy. The final implementation now traverses native Chromium AX relationships and supplies role locators; scoped aria-owns regression contracts pass.
- JVM dialogs are subscribed and handled asynchronously, including handlers lasting beyond 100 ms and automatic dismissal. Mobile controllers now use asynchronous completion callbacks; platform verification is running.
- Local Maven publication and independent Kotlin/Android artifact consumers passed with upgraded dependencies and init-script APIs.

- WebMCP has a typed Kotlin API and a CDP runtime. Six protocol tests cover early replies, caller cancellation, deadlines, navigation invalidation, origins and resource bounds. Real Chrome discovery and invocation against a local tool also passed. Cross-process frame aggregation and complete initial child-frame discovery remain pending.
- Shared native changes passed all 29 iOS simulator contracts. Android's new asynchronous-dialog test compiled, but execution awaits a connected device (the previous 51 physical tests passed).

- Web Vitals and router navigation passed the 809-test JVM suite with real Chrome, compiled on Android/iOS, and built both Android examples. The collector preserves page-owned globals and removes observers/listeners.
- WKUserScript SDK ownership now passes a new iOS contract (30 total); disposal retains host scripts. The standalone WebKit test executable did not load HTML, so document execution remains app-host validation.
- Accessibility audits bundle axe-core 4.14.0 with MPL notices and reproducible registry-integrity verification. Isolated frame merging, scopes, shadow targets and page-owned loader isolation are under expanded real Chrome regression validation.

- The expanded full suite passed all 809 JVM tests with real Chrome, both Android examples, and iOS core compilation. CDP audits cover root/same-process/OOPIF frames; frame locators verify cross-process trusted click, Unicode fill, file upload and element screenshots. Target discovery is refreshed before attachment to handle process swaps.

- React APIs passed a local React 19.3 application fixture, including Flight-first registration, component inspection, state-change render profiling, Suspense transitions and cleanup. The full suite then passed 811 JVM tests, 30 iOS contracts and both Android examples; the current Android instrumentation APK compiled. Nested OOPIF frames and inherited overlay checks passed expanded real Chrome checks. WebMCP observes tools before navigation and invokes tools across frame sessions; explicit cross-origin `allow="tools"` is required by Chromium.

- HTTP reader/network history checkpoint passed 828 JVM tests with real Chrome/React, 37 iOS contracts and Android/shared compilation. History retains bounded request hops across navigation, and CDP bodies are available without HAR capture. Redirect/query resolution received additional contract cases. Suspending page/browser `close()` now awaits owned React cleanup; canceled native mouse actions release acquired buttons, preserving caller-held buttons. Android before-unload callbacks use the same asynchronous dialog path as confirms.

- Role/screencast checkpoint passed 833 JVM tests with real Chrome/React, 37 iOS contracts and Android/shared compilation. Role queries use browser AX metadata in root/shadow/nested OOPIF scopes, including dynamically appearing progress bars. Frame ownership tests traverse 150 replacement targets without exhausting attachments. Screencast collectors use independent sessions and bounded latest-frame delivery; tests verify start failures, first-frame cancellation and page-close cleanup. Capabilities now advertise all new browser API groups.

- All 54 current Android instrumentation tests passed in 89 seconds on an isolated API 36 arm64 emulator, using one activity for the class. This covers new asynchronous dialogs, document-start scripts, vitals and router navigation. The owner's existing emulator remained untouched; the temporary test instance/data were removed afterward.

## Browser checkpoint — 2026-10-11

859 JVM tests and 39 iOS simulator contracts passed, with no skipped tests.
The JVM run includes real Chrome/React, native AX hierarchy/scopes, root/nested
OOPIF input and chooser upload, frame request bodies and HAR export, trusted
cursor pixels in encoded MP4, 60 FPS WebM, launch/profile reuse and copied profiles,
private HTTP certificate trust, hostname rejection, cancellation and cleanup.
Both Android examples, Compose lint and fresh instrumentation APKs built.
The shared examples expose HAR capture and storage-state snapshots alongside the
reader, network history, React, axe, vitals and router controls.

The checkbox completion records the Kotlin adaptation within each advertised
backend capability; it does not imply that native WebViews expose Chromium's
process APIs. Remaining verification limits: live Darwin document execution,
Linux NSS browser trust and host-dependent WebGPU adapters. CI installs certutil
and exercises the Linux private-CA browser path. Experimental Lightpanda/Obscura
engines and excluded CLI/daemon products are not additional KMP backends.

Final native packaging checkpoint: both arm64 XCFramework slices, deterministic
archive/checksum and artifact-only Swift consumer passed. Fresh Android execution
passed 55/55 in 70 seconds; its temporary emulator/data were removed afterward.

Final publication checkpoint: local publications for all targets and independent
Kotlin/Android consumers passed. Public coroutine Flow and JSON signature types
are exported as API dependencies and compiled by the independent Kotlin consumer.
The release-version propagation contract and publication-candidate secret/local-file
checks also passed. See [validation-checkpoint.json](validation-checkpoint.json)
for the retained test totals and runtime verification limits.

The final shared Compose iOS release framework passed after the new HAR/session
example controls. The last JVM checkpoint includes the already-detached iframe
cleanup regression (859 tests, no failures or skips).

## Reliability checkpoint — 2026-10-13

The full JVM suite passed 947 tests without failures or skips, including actual
Chromium and React documents. All 85 iOS tests passed inside an app host, which
executes the WKWebView document contracts that the older simulator checkpoint
could not exercise. Android passed all 67 instrumentation tests on the task's
arm64 emulator in 245.57 seconds, with activity reuse within each class.

The latest review's ten findings and the additional request migration, recording
clock and frame-input deadline repairs are described in
[reliability-review.md](reliability-review.md). ABI validation, Compose lint,
the Android example and the website's type/lint/production checks passed.
The current artifact-consumer results and remaining runtime limits are retained
in [validation-checkpoint.json](validation-checkpoint.json).
