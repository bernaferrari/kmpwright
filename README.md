# KMPwright

<p align="center">
  <img src="docs/assets/kmpwright-opengraph-v3.jpg" alt="KMPwright — Browser automation for Kotlin Multiplatform. One Kotlin workflow connects Android, iOS and Chromium browsers." width="1200" />
</p>

**Playwright-style browser automation and [agent-browser](https://agent-browser.dev) commands
for Kotlin Multiplatform.** Drive your app's Android `WebView` or iOS `WKWebView` — or
Chromium on the JVM — from one shared Kotlin API, and hand any of them to an LLM agent.

```kotlin
// Playwright-style
page.goto("https://example.com/login")
page.getByLabel("Email").fill("ada@example.com")
page.getByRole("button", name = "Sign in").click()
expect(page.locator(".greeting")).toHaveText("Welcome, Ada")

// agent-browser-style: one tool an LLM can drive
page.runCommand("snapshot -i")
// @e1 [heading] "Welcome, Ada" [level=1]
// @e2 [link] "Settings"
page.runCommand("click @e2")
```

> [!IMPORTANT]
> **Alpha · 0.1.0.** Public Maven artifacts and release assets have not been
> published yet. Try the examples or build from source. APIs and platform
> coverage may change before the first release.

**[Documentation](website/content/docs/index.mdx)** ·
[Try the demo](#try-the-demo) · [Installation](#installation) ·
[Connect a browser](#connect-a-browser) ·
[Coming from Playwright / agent-browser](#coming-from-playwright--agent-browser) · [Recipes](#recipes) ·
[Platform support](#platform-support) · [Build and test](#build-and-test)

The full documentation — every API, every agent command, per-platform behavior and a
capability matrix — lives in [`website/`](website/README.md) (Fumadocs). It is written for people
and coding agents alike: run `pnpm --dir website dev`, then fetch `/llms.txt`, `/llms-full.txt`
or any page as Markdown. Coding agents can also load the [KMPwright skill](skills/kmpwright/SKILL.md).

## Why KMPwright

- **Agents get a real browser on the device.** `page.snapshot()` returns a compact
  accessibility tree with `@e` refs in agent-browser's exact format; `page.runCommand("click @e3")`
  speaks its command grammar, so one tool call wires an on-device or cloud LLM to the page.
- **Playwright knowledge transfers.** `goto`, `getByRole`, `fill`, `expect(locator)`,
  `route.fulfill`, `storageState()` — same names, same auto-waiting and actionability checks,
  as suspend functions with named arguments.
- **Your WebView, not an emulator farm.** Attach to the `WebView` / `WKWebView` your app
  already shows. Your existing `WebViewClient`, `WebChromeClient` and delegates keep working.
- **Honest about platforms.** Every feature reports `Supported`, `Partial` or `Unsupported`
  per backend through `page.capabilities()`, with notes explaining the gaps.
- **Boundaries for unattended runs.** Allowed origins and domains enforced below JavaScript,
  action policies with confirmation callbacks, eval switches and encrypted storage state.

## Try the demo

**In your browser:** the home page of [kmpwright.dev](https://kmpwright.dev) runs KMPwright itself,
compiled to JavaScript, against a live page. Type `snapshot -i`, then `click @e9`. No install needed.

The sample app (Compose Multiplatform, Material 3 Expressive) is a browser you drive like an
agent. Every interactive element gets a live `@e` badge — tap one to click it — while an
on-device console speaks agent-browser commands. Recipes run real scripts with a step
timeline, and Inspect shows console, network, storage and an axe audit. On iOS 26 it uses the
native Liquid Glass tab bar and navigation.

<p align="center">
  <img src="docs/assets/kmpwright-android-agent.png" alt="Android sample: live @e ref badges over the page and the agent console" height="560" />
  &nbsp;
  <img src="docs/assets/kmpwright-ipad-liquid-glass.png" alt="iPad sample with Liquid Glass tabs: an agent-loop recipe that snapshots the page and acts on refs" height="560" />
</p>

Build requirements: JDK 17, the Android SDK for Android, and macOS with Xcode
for iOS. Run these commands from the repository root.

**Android** — install on a connected device or emulator:

```bash
./gradlew :example-android:installDebug
```

**iOS Simulator** — build the shared framework, then run the Xcode host:

```bash
./gradlew :kmpwright-example-shared:linkDebugFrameworkIosSim
open example-ios/KmpwrightExampleApp.xcodeproj
```

See the [iOS example setup](example-ios/README.md) for Xcode configuration.

## Installation

### From source today

Within this checkout, add the core module to your shared source set:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kmpwright-core"))
        }
    }
}
```

The optional Android Compose wrapper is `project(":kmpwright-compose")`.
For an external project, use the published dependencies once the release is available.

### Published dependencies

After `0.1.0` is published, add Maven Central to your dependency repositories
and use these coordinates:

```kotlin
repositories { mavenCentral() }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.bernaferrari:kmpwright-core:0.1.0")
        }
        androidMain.dependencies {
            // Optional Android Compose WebView wrapper.
            implementation("io.github.bernaferrari:kmpwright-compose:0.1.0")
        }
    }
}
```

The core provides Android, iOS and JVM targets. The Compose wrapper is Android-only.
Swift/Xcode consumers can use the static `KmpwrightCore.xcframework` release asset;
see the [release and integration guide](docs/releasing.md). Apple targets are
arm64 device and arm64 simulator, with iOS 15 as the minimum.

## Connect a browser

Navigation, actions, assertions, `newPage()` and `close()` are suspending APIs;
`dispose()` is the non-suspending best-effort teardown. Run the
examples below in a coroutine; create and operate mobile WebViews on the main thread.
Each example below runs the same shared function:

```kotlin
import com.bernaferrari.kmpwright.core.*

suspend fun checkExample(page: Page): ScreenshotResult {
    page.goto("https://example.com")
    expect(page.locator("h1")).toHaveText("Example Domain")
    return page.screenshot()
}
```

### Android WebView

Attach to a `WebView` your application displays:

```kotlin
Browser(AndroidPlatformContext(activity, webView)).use { browser ->
    checkExample(browser.page())
}
```

### iOS WKWebView

From `iosMain`, attach to your application's `WKWebView`:

```kotlin
Browser(IOSPlatformContext(webView = webView)).use { browser ->
    checkExample(browser.page())
}
```

Your app retains ownership of a supplied WebView. Closing the browser disposes
its automation resources. The [shared example](kmpwright-example-shared/src/commonMain)
shows how to connect page lifetimes to a Compose UI.

### Launch Chromium on JVM

Supply the path to an installed Chrome or Chromium executable. KMPwright launches
it headless by default and owns the process and temporary profile:

```kotlin
suspend fun checkInChromium(executablePath: String): ScreenshotResult =
    launchBrowser(executablePath).use { launched ->
        checkExample(launched.browser.newPage())
    }
```

Use `headless = false` for a visible browser. `launchBrowser` parameters also support persistent
automation profiles, copies of existing Chrome profiles, extensions and a WebGPU
preset. See [launch and profile recipes](docs/browser-features.md#owned-chromium-launch-and-profiles).

### Connect to CDP or a cloud provider

Connect to an existing browser-scoped WebSocket endpoint:

```kotlin
Browser(
    JvmPlatformContext(),
    KmpwrightConfig(cdp = CdpConfig(
        websocketUrl = "ws://localhost:9222/devtools/browser/<id>"
    ))
).use { browser ->
    checkExample(browser.newPage())
}
```

Use `provider = CloudProvider.AutoConnect` to discover an already-running browser
with remote debugging enabled. To create a managed cloud session, select a provider and set its API key in the
process environment:

| Provider | Environment variable |
| --- | --- |
| `CloudProvider.Browserbase` | `BROWSERBASE_API_KEY` |
| `CloudProvider.Browserless` | `BROWSERLESS_API_KEY` |
| `CloudProvider.BrowserUse` | `BROWSER_USE_API_KEY` |
| `CloudProvider.Kernel` | `KERNEL_API_KEY` |

These providers use the same `CdpConfig(provider = …)` entry point.
Browserbase also accepts `BROWSERBASE_PROJECT_ID` when your account requires a project.

## Coming from Playwright / agent-browser

KMPwright follows one naming rule set, so existing knowledge transfers:

- **Playwright JS names and shapes** first: `goto`, `goBack`, `locator`, `getByRole`, `fill`, `pressSequentially`, `expect(locator)`, `route.fulfill`, `storageState()`.
- **Playwright-Java names** where Kotlin can't use the JS name: `route.resume()` for `route.continue()`.
- **agent-browser names** for agent features: `page.snapshot()`, `@e3` refs in `page.locator("@e3")`, `setAllowedDomains`, `runCommand`.
- **Kotlin idioms** only where required: `suspend` instead of Promise, named and default arguments instead of option objects, `StateFlow`/`SharedFlow` (`page.events`) instead of `page.on(...)`, and `timeoutMs: Long?` where `null` means the configured default.

```kotlin
// Playwright (TypeScript)                        // KMPwright (Kotlin)
// await page.getByRole('button', { name: 'Save' }).click();
page.getByRole("button", name = "Save").click()
// await page.route('**/api', r => r.fulfill({ json: '{}' }));
page.route("**/api") { route -> route.fulfill(json = "{}") }
// await expect(page).toHaveURL(/done/);
expect(page).toHaveURL(Regex("done"))
```

## Recipes

The snippets below assume an open `page` and the core imports shown above.

### Locators, input and assertions

```kotlin
page.locator("label=Email").fill("reader@example.com")
page.locator("label=Password").fill(passwordFromSecretStore)
page.locator("button[type=submit]").click()
expect(page.locator(".notice")).toContainText("Welcome")

page.locator("#terms").check()
page.locator("select").selectOption("pro")
page.locator("input").press("Enter")
expect(page.locator(".result")).toHaveCount(5)
```

Use semantic selectors for user-facing controls and CSS or XPath for structure:

| Selector | Example |
| --- | --- |
| Role | `role=button` or `role=button[name="Save"]` (accessible name, case-insensitive substring; add ` s` for exact) |
| Label | `label=Email` |
| Text | `text=Continue` (case-insensitive substring), `text="Continue"` (exact), `text=/contin/i` (regex); `text:Continue` also works |
| Exact text | `exact_text=Submit order` |
| Placeholder | `placeholder=Search` (case-insensitive substring), `placeholder="Search"` (exact), `placeholder=/sea/i` (regex) |
| Image alternative text | `alt=Company logo` (same matching as `placeholder=`) |
| Title | `title=Close` (same matching as `placeholder=`) |
| Test ID | `testid=checkout` (exact, or `testid=/check/` regex) |
| CSS | `.results > article` |
| XPath | `xpath=//main//button` |

The Playwright finders build the same selectors: `getByRole`, `getByText`, `getByLabel`,
`getByPlaceholder`, `getByAltText`, `getByTitle` and `getByTestId` are available on pages,
locators and frame locators. Chain locators or use `.nth(index)` with a zero-based index to
narrow a result. Snapshot references can also be addressed with `page.locator("@e3")`.

### Wait for the result you need

```kotlin
page.goto("https://example.com", waitUntil = LoadState.DOMContentLoaded)
page.locator("h1").waitFor()
```

For your application's state, wait for the relevant text or predicate:

```kotlin
page.waitForText("Saved")
page.waitForFunction("window.appReady === true")
```

Prefer element, text or predicate waits over fixed delays. `NetworkIdle` is
available, but persistent connections can prevent it and it does not establish
that an application has finished updating.

### Screenshots and accessibility

```kotlin
val viewport = page.screenshot()
val fullPage = page.screenshot(fullPage = true)
val chart = page.locator(".chart").screenshot()
val snapshot = page.snapshot(interactive = true)
val annotated = page.screenshot(annotate = true) // labels each @eN ref
val audit = page.accessibilityAudit(tags = setOf("wcag2aa"))
```

`snapshot.tree` uses [agent-browser's snapshot format](https://agent-browser.dev/snapshots),
so prompts written for agent-browser work unchanged. With `interactive = true`
(`snapshot -i`) it is a flat list of refs, with iframe content indented beneath
its iframe; `urls = true` (`-u`) adds link targets:

```text
@e1 [heading] "Example Domain" [level=1]
@e2 [button] "Submit"
@e3 [input type="email"] placeholder="Email"
@e4 [checkbox] "Remember me" [checked]
@e5 [link] "Learn more" url="https://example.com/more"
@e6 [Iframe] "payment-frame"
  @e7 [input] "Card number"
  @e8 [button] "Pay"
```

The full tree (no `-i`) adds unreferenced structure and text, such as
`[paragraph]` and `[StaticText] "…"` lines, indented two spaces per level.
`screenshot --annotate` prints a legend such as `[2] @e2 button "Submit"`.

CDP snapshots use Chromium's accessibility tree. Mobile snapshots use DOM
heuristics; audits report skipped child frames. Full-page capture also differs
by backend—check the [feature guide](docs/browser-features.md) for coverage.

### Read content and inspect requests

```kotlin
// Fetch content without navigating the browser or sharing its cookies.
val document = HttpReader().read("https://example.com")
println(document.content)

page.network.startHar()
page.goto("https://example.com")
val archive = page.network.stopHar()
val recentRequests = page.network.requests(limit = 10)
```

HAR capture works across backends: CDP includes available response bodies,
while mobile WebViews expose limited metadata. Redirects and cache eviction
can make CDP bodies unavailable.

### Record the current page on CDP

```kotlin
page.requireCapabilities(PageCapability.VideoRecording)
page.video.start(
    path = "capture.mp4",
    fps = 30,
    contactSheetPath = "contact.png"
)
page.locator("button").click()
val recording = page.video.stop()
```

Recording requires FFmpeg and supports MP4 or WebM at 1–60 FPS. It preserves the
current page's state and can include cursor feedback and a clean contact sheet.

For React inspection, WebMCP tools, routing, init scripts, login, encrypted storage state,
autosave and private certificate trust, see the [browser feature recipes](docs/browser-features.md).

## Platform support

| Target | Backend | Main differences |
| --- | --- | --- |
| Android 8+ | Android `WebView` | DOM input semantics, limited interception, shared WebView cookie storage; bounded scroll-and-stitch for full-page screenshots |
| iOS 15+ · arm64 | `WKWebView` | DOM input semantics; HTTP routing covers fetch/XHR bridge traffic and optional custom schemes |
| JVM · JDK 17+ | Chromium CDP | Trusted browser input, cross-process frames, browser targets/windows, video, tracing and V8 profiling |

Most APIs live in `commonMain`; their support level can be `Supported`, `Partial`
or `Unsupported`. Require the capabilities a workflow needs before it starts:

```kotlin
page.requireCapabilities(PageCapability.VideoRecording)

// Accept partial routing when it is sufficient for your workflow.
page.requireCapabilities(
    PageCapability.RouteInterception.require(minimum = SupportLevel.Partial)
)
```

For an optional feature, inspect
`page.capabilities().supportLevel(PageCapability.VideoRecording)` first.
Android cannot provide isolated in-process browser contexts. Experimental WebMCP
also depends on the selected Chromium build. See the [compatibility matrix](compatibility/agent-browser-compatibility.json)
for detailed mappings and the [validation record](compatibility/validation-checkpoint.json)
for tested coverage.

## Configure automation boundaries

```kotlin
val config = KmpwrightConfig(
    allowedOriginRules = setOf("https://app.example.com"),
    productionMode = true,
    allowEval = false,
    allowDomExtraction = false,
    enableTraceExport = false
)
```

Use `page.setAllowedDomains(listOf("example.com", "*.static.example.com"))` for
request filtering (enforced below JavaScript on every backend: CDP Fetch interception, an iOS
navigation policy plus a WKContentRuleList, and Android `shouldInterceptRequest`/`shouldOverrideUrlLoading`;
check `page.capabilities().domainFiltering` and its `notes` for the gaps — CDP does not see WebSocket
handshakes, and Android cannot see subresource redirects, WebSockets or service-worker requests), and `page.createContentBoundary()` / `page.boundedSnapshot()`
for marked content extraction. These are workflow controls; page content remains
untrusted, and filtering does not make a WebView a security sandbox.

Storage-state files are encrypted when you pass a password:
`page.storageState().save(path, password = statePassword)`, restored with
`page.setStorageState(StorageState.load(path, password = statePassword))`. The password
parameter has no default; pass `password = null` to opt in to a plaintext file. Keep credentials,
provider keys and session files out of source control. See the
[configuration API](kmpwright-core/src/commonMain/kotlin/com/bernaferrari/kmpwright/core/KmpwrightConfig.kt)
and [feature guide](docs/browser-features.md) for the available options.

## Build and test

```bash
# Build core/examples, run JVM tests and lint; also link iOS frameworks on macOS.
./scripts/ci-build.sh

# Android instrumentation: reuse each class's activity, fresh WebViews, visible test/count progress.
./scripts/run-android-tests.sh DEVICE_SERIAL

# iOS backend contracts and shared example framework (macOS only).
./gradlew :kmpwright-core:iosSimTest :kmpwright-example-shared:linkReleaseFrameworkIosSim
# Run the complete suite in an app host, including actual WKWebView documents.
./scripts/run-ios-webkit-tests.sh

# Documentation and upstream comparison metadata.
./scripts/verify-documentation.sh
./scripts/verify-agent-browser-compatibility.sh
```

Real Chromium tests require `KMPWRIGHT_TEST_CHROME` to point to the browser executable
and FFmpeg on `PATH`. To include the React fixture:

```bash
./scripts/build-react-fixture.sh
export KMPWRIGHT_TEST_CHROME="/path/to/chrome"
export KMPWRIGHT_TEST_REACT_BUNDLE="$PWD/.upstream/react-fixture/app.bundle.js"
./gradlew :kmpwright-core:jvmTest
```

[Dependency versions](docs/dependencies.md) and [release verification](docs/releasing.md) describe the tested toolchain and
artifact checks.

## Project layout

| Module | Purpose |
| --- | --- |
| `kmpwright-core` | Shared browser APIs and Android, iOS and JVM backends |
| `kmpwright-compose` | Android Compose WebView wrapper |
| `kmpwright-example-shared` | Shared Compose Multiplatform demo |
| `example-android` / `example-ios` | Platform hosts for the demo |
| `kmpwright-test` | Android instrumentation harness |
| `compatibility` | Upstream provenance, feature mappings and validation records |

## Upstream and contributing

KMPwright adapts browser behavior from [agent-browser](https://github.com/vercel-labs/agent-browser)
to Kotlin APIs and examples. The [upstream review](compatibility/upstream-review.md)
records the comparison commit, relevant changes and platform limits. The CLI,
daemon, MCP server, executable plugins and chat gateway are outside this library's
scope. Upstream source stays outside version control and is not required to build.

For contributions, keep portable behavior in `commonMain`, add meaningful contract
tests, and document backend differences through capabilities. Run the build and
documentation checks above before opening a change.

## License

[MIT](LICENSE) · Copyright 2026 Bernardo Ferrari.
See [third-party notices](THIRD_PARTY_NOTICES.md) for bundled code and dependencies.
