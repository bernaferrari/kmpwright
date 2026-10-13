# KMPwright

<p align="center">
  <img src="docs/assets/kmpwright-opengraph-v3.jpg" alt="KMPwright — browser automation for Kotlin Multiplatform" width="800" />
</p>

**Browser automation for Kotlin Multiplatform.** Write a workflow once and run it
inside Android WebView, iOS WKWebView or JVM Chromium. Use a Playwright-inspired
Kotlin API, or give an AI agent the same browser through compact commands.

[Documentation](docs/README.md) · [Agent guide](website/content/docs/agents/index.mdx) · [Examples](kmpwright-example-shared)

> [!NOTE]
> **0.0.1 alpha.** Maven Central publication is pending. APIs and platform coverage may change.

## Add to your project

In your existing Kotlin Multiplatform module, add **one dependency** to `commonMain`.
Gradle selects the matching platform artifact automatically. Available after
`0.0.1` is published to Maven Central:

```kotlin
repositories { mavenCentral() }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.bernaferrari:kmpwright-core:0.0.1")
        }
    }
}
```

For the optional Android Compose wrapper, add
`implementation("io.github.bernaferrari:kmpwright-compose:0.0.1")` to `androidMain`.
Swift integration is covered in the
[iOS guide](website/content/docs/platforms/ios.mdx).

## One shared workflow

```kotlin
import com.bernaferrari.kmpwright.core.*

suspend fun signIn(page: Page) {
    page.goto("https://example.com/login")
    page.getByLabel("Email").fill("ada@example.com")
    page.getByRole("button", name = "Sign in").click()
    expect(page.locator(".greeting")).toHaveText("Welcome, Ada")
}
```

Locators wait for actionable elements; assertions wait for the expected result.
Use semantic finders, filters and frame scopes to select the element you mean.

### Connect your browser

**Android** — attach to your app’s WebView:

```kotlin
Browser(AndroidPlatformContext(activity, webView)).use { browser ->
    signIn(browser.page())
}
```

**iOS** — attach to your app’s WKWebView from `iosMain`:

```kotlin
Browser(IOSPlatformContext(webView = webView)).use { browser ->
    signIn(browser.page())
}
```

**JVM** — launch an installed Chrome or Chromium:

```kotlin
launchBrowser(executablePath).use { launched ->
    signIn(launched.browser.newPage())
}
```

Run these suspending APIs in a coroutine, on the main thread for mobile WebViews.
You can also attach to an existing Chromium instance or connect to a
[cloud browser](website/content/docs/platforms/jvm.mdx).

## Give an agent the controls

```kotlin
page.runCommand("snapshot -i")
// @e1 [input type="email"]
// @e2 [button] "Sign in"

page.runCommand("fill @e1 ada@example.com")
page.runCommand("click @e2")
```

Expose `runCommand()` as a tool to your LLM SDK. The model reads snapshots and
acts on element references, using the documented
[agent-browser command subset](website/content/docs/agents/commands.mdx).

## More than clicks

| Workflow | Guide |
| --- | --- |
| Find elements, enter text and assert results | [Locators](website/content/docs/guides/locators.mdx) |
| Intercept requests and inspect network history | [Networking](website/content/docs/guides/network.mdx) |
| Capture pages and compare images | [Screenshots](website/content/docs/guides/screenshots.mdx) |
| Save cookies and browser storage | [Session state](website/content/docs/guides/storage.mdx) |
| Record a Chromium workflow | [Recording](website/content/docs/guides/recording.mdx) |

## Platform support

| Platform | Browser | Minimum |
| --- | --- | --- |
| Android | WebView | Android 8 |
| iOS | WKWebView | iOS 15 · arm64 device and simulator |
| JVM | Chromium through CDP | JDK 17 |

Features follow each backend’s abilities. Inspect `page.capabilities()` before
using an optional feature; the [capability matrix](website/content/docs/platforms/capabilities.mdx)
explains partial and unsupported behavior.

## Project and provenance

KMPwright adapts browser behavior from [agent-browser](https://github.com/vercel-labs/agent-browser)
into Kotlin APIs. It is independent of Playwright. The
[upstream comparison](docs/compatibility/upstream-review.md) records the pinned
revision and coverage; upstream source is not included in this repository.

For contributions, see [development and tests](docs/development.md). For artifact
verification, see the [release guide](docs/releasing.md).

[MIT](LICENSE) · [Third-party notices](THIRD_PARTY_NOTICES.md)
