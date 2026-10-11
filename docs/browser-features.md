# Browser feature APIs

The implementation and remaining work are tracked in
[parity-work.md](../compatibility/parity-work.md), against the pinned upstream
revision in [upstream-review.md](../compatibility/upstream-review.md).

## Document-start scripts

```kotlin
val id = page.addInitScript("window.startedEarly = true;")
try {
    page.goto("https://example.com")
} finally {
    page.removeInitScript(id)
}
```

Registrations run before page scripts in subsequent documents, including child
frames. Removing a registration affects future documents. CDP, AndroidX WebKit
and iOS WKUserScript implement the registration; Android requires the installed
WebView's document-start feature. The shared Navigation example demonstrates it.

`page.openNewTab(url)` copies successful setup from its source page before
navigation: headers, credentials, routes, emulation and init scripts. Changes
made later to the source page do not retroactively update the new tab.

## Native Chromium input and accessibility

JVM CDP locators dispatch browser input for click, double click, hover, tap,
fill, type, press and drag. Keyboard chords such as `ControlOrMeta+A` are
supported. Shadow roots and associated labels participate in hit testing;
overlays intercept actions unless the caller explicitly requests a forced action.
Mobile locators retain their documented DOM input limitations.

CDP snapshots use Chrome's computed roles, accessible names and states while
retaining Kmpwright's stable element references. Open shadow roots participate
in snapshots. CDP hierarchy follows Chromium AX relationships, including `aria-owns`,
with scoped references and interactive filtering. Mobile snapshots use DOM heuristics.

## WebMCP

```kotlin
val tools = page.webMcp.discover()
val selected = tools.single { it.name == "greet" }
val result = page.webMcp.invoke(
    name = selected.name,
    frameId = selected.frameId,
    input = buildJsonObject { put("name", "Kotlin") }
)
check(result.status == WebMcpInvocationStatus.Completed)
println(result.output)
```

Import JSON builders from `kotlinx.serialization.json`. WebMCP requires a
Chromium browser exposing the experimental
[CDP WebMCP domain](https://chromedevtools.github.io/devtools-protocol/tot/WebMCP/).
The local Chrome regression enables WebMCP and serves a tool on localhost;
the fixture supports `document.modelContext` and the earlier
`navigator.modelContext` registration location.

Use `waitForResult = false` to start a detached invocation, then
`awaitInvocation`, `invocation`, or `cancelInvocation` with the returned id.
Timeout and caller cancellation cancel remote work; navigation invalidates
pending work. `context()` reads a bounded cache without invoking tools or
querying the renderer. Tool annotations and schemas are page-supplied metadata;
the compact context omits them.

Limits follow the pinned upstream: 1 MiB input, 2 MiB output, 256 KiB per tool,
512 tools/2 MiB catalog, 128 invocation records and early replies. Oversized
outputs become a truncated preview with the original byte count. Compact
context contains at most 16 tools/4 KiB, with 160-byte descriptions.
Owned Chromium pages observe tools before navigation and attach iframe sessions
before their scripts run. Real Chrome verifies duplicate tool names across
same-process and cross-process frames, explicit frame selection and invocation.
The [WebMCP permissions policy](https://developer.chrome.com/docs/ai/webmcp)
requires `allow="tools"` on cross-origin iframes; the browser controls tool
exposure. Initial same-process child tools registered before connecting remain
a discovery limitation of the current protocol replay. Native WebViews report
WebMCP as unavailable.

## Web Vitals and SPA navigation

```kotlin
val report = page.webVitals(WebVitalsOptions(settleMs = 3_000))
println(report.metrics) // LCP, CLS, INP, FCP and TTFB when observed
println(report.hydration)
println(report.hydratedComponents)
page.pushState("/account?tab=settings")
```

Web Vitals installs the pinned Google collector before reload/navigation,
returns measured values and ratings, then removes its registration, observers,
listeners and timing wrapper. Missing observations remain null. Set
`reload = false` to inspect the current document; early React timing markers
require collection before application scripts run. React profiling timestamps
include hydration phases and component durations. This is distinct from the
React fiber inspector documented below. The shared Navigation
example demonstrates performance collection and route changes.

`pushState` awaits Next.js's router when available, otherwise updates history
and emits navigation events. It checks the navigation policy and allowed
origins, waits for the requested URL and updates the page URL flow.

## Accessibility audits

```kotlin
val report = page.accessibilityAudit(AccessibilityAuditOptions(
    tags = setOf("wcag2a", "wcag2aa"), selector = "main"
))
for (issue in report.violations) {
    println("${issue.id}: ${issue.help} (${issue.nodeCount} nodes)")
}
check(report.coverage.skippedFrames.isEmpty())
```

The bundled, integrity-checked axe-core 4.14.0 runs locally. CDP audits use
isolated worlds, audit frame partials in DOM order and merge them without page
messaging. Native WebViews audit the selected document and explicitly list
skipped child frames. Reports retain nested shadow and frame selector paths,
return counts for all result categories, and bound node details per rule.
Invalid or missing selectors fail without widening the audit scope. Skipped
frames are coverage gaps; they are not successful audits.

The iOS native executable verifies WKUserScript registration/removal and host
script preservation. Actual WebKit document execution needs an iOS app host;
the standalone simulator test executable did not load a document. The shared
iOS example remains the app-host path for that validation.

## React inspection

```kotlin
page.react.enable() // Register before loading the application.
page.goto("https://your-react-app.example")
val tree = page.react.tree()
val component = tree.first { it.name == "Counter" }
println(page.react.inspect(component.id).text)
println(page.react.suspense())
page.react.startRenderProfile()
page.locator("button").click()
val profile = page.react.stopRenderProfile()
page.react.disable()
```

An already mounted application needs `enable(reload = true)` for its renderer
to discover the hook. The pinned React DevTools 8.0.0 backend runs privately
with React 19.3.0 for its debug-tools dependency. It preserves the page's module
loader and React globals. Renderer selection prefers a DOM renderer with
mounted roots, including applications whose Flight renderer registers first.
The portable promise bridge serializes inspector operations; capture budgets
bound tree operations, component histories and fiber traversal. Frame-rate
statistics use constant memory, and instance tracking uses weak references.
Disable removes future registrations and stops the backend bridge and render
capture. The DevTools hook itself lasts until document replacement, as required
by React's hook descriptor. The real React 19.3 fixture verifies Flight-first renderer registration, tree
and component inspection, state-change profiling, Suspense transitions and cleanup.

CDP frame locators resolve isolated sessions after reloads and process swaps.
Nested same-process frames below an OOPIF retain their ancestor session; native
input maps through iframe borders/scales into the root viewport and checks
ancestor overlays. File inputs use the owning frame's DOM session. Element
screenshots and bounding boxes map to root coordinates. Rotated or 3D iframe
input reports an explicit limitation. Real Chrome verifies nested OOPIF fill,
trusted click, upload, screenshots and overlay interception.


## HTTP reading and network history

`HttpReader().read(url)` fetches readable content without launching a browser.
`page.read()` extracts the current document; `page.read(url)` reads through HTTP
without navigating and applies the page's navigation policy and domain filter.
HTTP reads do not share browser cookies. `ReadOptions` supports raw content,
strict `text/markdown`, heading outlines and section filtering, explicit headers,
and nearest-ancestor `llms.txt` / `llms-full.txt` discovery. Relative links and
redirects are normalized and checked before fetching. Custom headers stay at the
original origin, except `Accept`. Decompressed responses default to a 2 MiB
budget (maximum 16 MiB); truncated UTF-8 content ends at a complete character.
A single deadline covers redirects, negotiation and discovered documents.

```kotlin
val article = HttpReader().read("https://example.com/guide", ReadOptions(outline = true))
val index = HttpReader().read("https://example.com/docs", ReadOptions(llms = LlmsReadMode.Index))
val current = page.read()
val requests = page.network.requests(filter = "/api", limit = 20)
val detail = page.network.detail(requests.first().id)
val body = page.network.responseBody(requests.first().id, maxBytes = 64 * 1024)
```

`page.network` retains at most 500 request hops within a 4 MiB metadata budget,
starting at page attachment and continuing across navigations. IDs remain distinct
across redirects and clears. Bodies are fetched on demand on CDP; cached or
redirect bodies may be unavailable. Native WebViews expose the network events
their SDKs report and explicitly reject body retrieval. History does not imply
complete native WebView subresource visibility. `clear()` releases retained
metadata without restarting collection. The shared Navigation example shows
current-document reads, HTTP reads and recent requests.

Prefer `page.closeAsync()` and `browser.closeAsync()` from coroutines. They await
owned React profiler/bridge and init-script cleanup before releasing backend
ownership. Synchronous native WebView `close()` remains best effort because SDK
JavaScript evaluation is asynchronous; callers retaining the host view can also
explicitly disable React inspection before synchronous teardown. Mouse clicks
and drags release acquired buttons on cancellation while preserving buttons
held by the caller.


## Screencast frames

```kotlin
val capture = scope.launch {
    page.screencast(ScreencastOptions(maxFps = 30, quality = 85, maxWidth = 1280))
        .collect { frame -> /* display or forward frame.bytes as JPEG */ }
}
capture.cancelAndJoin()
```

CDP capture uses a separate session for every collector. JPEG frames arrive when
Chrome paints; `maxFps` limits delivery to 1–60 FPS. All source frames are
acknowledged, while slow consumers receive the latest frame. Cancellation and
page shutdown stop capture, remove listeners and detach only capture-owned
sessions. Native WebViews explicitly reject this API. Video encoding and contact
sheets use the separate recording API below.

Capabilities now cover init scripts, WebMCP, accessibility audits, Web Vitals,
React inspection, HTTP reading, network history and screencasts. Native WebMCP
and screencasts are unsupported; native audits, vitals and network visibility
remain partial. Android document-start support depends on installed WebView.
Chrome role locators consume native AX metadata for implicit roles and shadow
controls. Their waits refresh metadata as the document changes, including frame
scopes. CDP snapshots follow the native AX hierarchy; `includeHidden` falls back to DOM traversal.

## Video recording

```kotlin
page.startRecording(RecordingOptions(
    path = "capture.mp4", // .webm also supported; FFmpeg must be installed
    fps = 30, cursor = true, contactSheetPath = "contact.png"
))
page.locator("button").click()
val video = page.stopRecording()
println("${video.frameCount} frames in ${video.durationMs} ms")
```

The CDP backend records the current page, including its existing state. It preflights
the executable and output container before starting capture; existing files are
preserved unless `overwrite` is explicit. Encoding holds unchanged frames at the
requested 1–60 FPS. Frames, pipe writes and cleanup have bounded budgets; failures
abort temporary output. Output dimensions stay fixed when the viewport changes.
Contact sheets select clean page frames before cursor composition and include the
final frame, with at most 100 cells. Cursor coordinates reflect acknowledged library input and trusted pointer events in the page and accessible child frames. Pointer pixels are composed at the output frame rate, including unchanged pages, without contaminating contact-sheet captures. Recording has no
audio. Closing the page finalizes an active recording. Native WebViews report
`VideoRecording.Unsupported`.

## Existing tabs and new windows

```kotlin
val targets = browser.targetsAsync()
val attached = browser.attachTargetAsync(targets.first().id)
browser.activateTargetAsync(targets.first().id)
attached.closeAsync() // detach; the existing tab remains open
val window = browser.newWindowAsync()
window.goto("https://example.com")
window.closeAsync() // library-created target is owned and closed
// To explicitly close an existing tab: browser.closeTargetAsync(targetId)
```

Target APIs require a browser-scoped CDP endpoint. Discovery includes foreign page
targets; `pages()` lists library-managed page handles. Attachment activates the tab
before renderer-domain setup, allowing Chromium to revive a discarded renderer.
Native WebViews reject target/window APIs explicitly. Mobile tracing/profiling
also reject unsupported operations instead of silently succeeding.

## Intercepting a file chooser

```kotlin
page.setFileChooserInterception()
coroutineScope {
    val chooser = async(start = CoroutineStart.UNDISPATCHED) { page.waitForFileChooser() }
    page.locator("input[type=file]").click()
    page.setFileChooserFiles(chooser.await(), listOf("/absolute/path/report.pdf"))
}
page.setFileChooserInterception(false)
```

CDP chooser handles are opaque and single-use. Navigation and disabled interception
expire pending handles. Root pages and frame sessions resolved through frame
locators are intercepted; observed cross-process frames are intercepted before their scripts resume.
Preexisting unobserved child targets remain a coverage limit. Native WebViews report interception/upload unsupported. Direct locator
uploads remain available through their documented backend capabilities.

## HAR capture

```kotlin
page.startHarCapture()
page.goto("https://example.com")
val harJson = page.stopHarCapture()
```

HAR 1.2 export uses unified page/frame request events. CDP captures available text
and base64 response bodies, including nested cross-process frames; native WebViews
retain the network metadata their SDKs expose. Capture is bounded to 500 entries,
16 MiB of serialized entry data, 32 concurrent body reads and 1 MiB per body. Body
truncation is explicit in `_kmpwrightTruncated`; unavailable bodies retain metadata.

## State autosave and login

```kotlin
val autosave = page.autosaveState(StateAutosaveOptions(
    name = "signed-in", intervalMs = 30_000, password = statePassword
))
// Inspect autosave.error and autosave.lastSavedAt; a write error stops the job.
autosave.stop()
val result = page.login(profile, AuthLoginOptions(navigate = false))
```

Autosave writes immediately, then periodically until stopped or its page closes.
Encrypted saves require an explicit password. File replacement preserves the
previous save when writing fails. Login uses configured selectors or discovers
usable controls in the password form; ambiguous controls require explicit selectors.
It validates origins, document/control identities and form destinations before each
credential. Input events are synthetic. `submitted` means a submit was attempted;
it does not prove authentication succeeded. `navigate=false` fills the open document.

## Launch an owned Chromium process

```kotlin
val launched = launchBrowser(BrowserLaunchOptions(
    executablePath = "/path/to/chrome", headless = true, webGpu = false
))
try {
    val page = launched.browser.newPageAsync()
    page.goto("https://example.com")
} finally { launched.closeAsync() }
```

The JVM launcher owns its process, loopback debugging endpoint and temporary profile.
Caller-provided `userDataDirectory` profiles survive shutdown and can be reused.
Reserved profile/debugging flags cannot be overridden. Startup and process shutdown
are bounded. `webGpu=true` enables upstream's headless preset; Linux requests software
Vulkan/SwiftShader. Hardware/driver/browser support still determines actual GPU
availability. Native WebViews explicitly reject desktop launch. `useSystemKeychain`
is opt-in; custom Chromium arguments are passed as distinct process arguments.

## Owned Chromium launch and profiles

```kotlin
val launched = launchBrowser(BrowserLaunchOptions(
    executablePath = "/path/to/chromium", webGpu = true,
    // A copied existing profile; changes never write back to the source.
    profileSnapshot = ChromeProfileSource("/path/to/chrome/user-data", "Work")
))
try {
    val page = launched.browser.newPageAsync()
    page.goto("https://example.com")
} finally { launched.closeAsync() }
```

`ChromeProfiles.list(directory)` reads Chrome's Local State; `resolve` accepts an
exact directory, an unambiguous display name, or a case-insensitive directory.
`discoverUserDataDirectory` checks conventional platform locations. Snapshot
copies skip caches, locks and symbolic links, fail incomplete copies, and enforce
file/byte/time budgets. Encrypted Chrome cookies still depend on the platform's
keychain. `userDataDirectory` instead selects a writable automation profile that
survives close. Both options are mutually exclusive. Native WebViews reject desktop
launch/profile APIs explicitly.

WebMCP launch features are enabled by default; set `webMcp = false` to omit them.
`extensionDirectories` loads unpacked extensions where the chosen Chromium
build permits it. Modern branded Chrome may require Chrome for Testing or
Chromium. `arguments` accepts additional flags while protecting the owned debug
endpoint/profile. WebGPU availability depends on the host; Linux gets the upstream
Vulkan/SwiftShader preset. Startup, process shutdown and owned temporary directories
have explicit cleanup. Linux shared memory below 256 MiB selects disk-backed storage.

## Private certificate authorities

```kotlin
val document = HttpReader().read("https://internal.example", ReadOptions(
    caCertificatePath = "/path/to/ca.pem", useSystemCa = true
))
```

JVM/Android readers accept bounded PEM bundles or one DER certificate and preserve
hostname verification. Trust applies only to that reader; `useSystemCa = false`
uses only the explicit bundle. Darwin custom reader trust is unsupported; configure
platform trust there. The owned Linux launcher also accepts `caCertificatePath`,
imports it into a temporary NSS store through `certutil`, and cleans it on close.
It requires a temporary browser profile and cannot combine with `ignoreHttpsErrors`.
Linux browser NSS integration is not exercised on the macOS validation host.
