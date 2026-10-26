---
name: kmpwright
description: Write Kotlin code that automates web pages with KMPwright (package com.bernaferrari.kmpwright.core), the Kotlin Multiplatform browser automation library with a Playwright-style API and agent-browser commands over Android WebView, iOS WKWebView and JVM Chromium (CDP). Use when a project depends on kmpwright-core or kmpwright-compose, when the user wants to automate or test an in-app WebView from Kotlin, or when building an LLM agent that drives a WebView with snapshots and @eN refs (page.runCommand, page.snapshot).
---

# KMPwright

KMPwright is Playwright for Kotlin Multiplatform plus agent-browser for your app's WebView.
One `Page` API runs on Android `WebView`, iOS `WKWebView` and Chromium over CDP on the JVM.
Everything is in package `com.bernaferrari.kmpwright.core`.

Full docs: https://kmpwright.dev/docs (append `.md` to any page URL for Markdown; all pages: https://kmpwright.dev/llms-full.txt).

## Install

Alpha, not yet on Maven Central. From a checkout of the repository:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies { implementation(project(":kmpwright-core")) }
        // Optional Android Compose wrapper:
        androidMain.dependencies { implementation(project(":kmpwright-compose")) }
    }
}
```

Planned coordinates: `io.github.bernaferrari:kmpwright-core:0.0.2` and `io.github.bernaferrari:kmpwright-compose:0.0.2`.

## Get a Page

```kotlin
// Android (create on the main thread)
val browser = Browser(AndroidPlatformContext(activity, webView))
val page = browser.page()

// Android Compose
val controller = rememberKmpwrightController()
KmpwrightWebView(controller, startUrl = "https://example.com")
val page = controller.page // null until the WebView is attached

// iOS (iosMain)
val browser = Browser(IOSPlatformContext(webView = wkWebView))
val page = browser.page()

// JVM: launch Chromium
launchBrowser("/path/to/chrome").use { launched ->   // closes the process when done
    val page = launched.browser.newPage()
}

// JVM: connect to an existing CDP endpoint
val browser = Browser(JvmPlatformContext(), KmpwrightConfig(cdp = CdpConfig(websocketUrl = "ws://…")))
```

Close with `browser.close()` (suspend), `browser.use { … }` / `page.use { … }` (always closes), or `browser.dispose()` (non-suspending, for `onDispose`/`onDestroy`).

## Core API cheat sheet

```kotlin
// Navigation
page.goto("https://example.com")                 // NavigationResult(url, status, ok, error)
page.goBack(); page.goForward(); page.reload()
page.waitForURL("**/done")
page.waitForLoadState(LoadState.NetworkIdle)
page.url.value; page.title.value                 // StateFlow<String?>

// Locators (lazy, re-resolving, strict: an action on a selector matching 2+ elements throws)
page.locator("#email")                           // CSS by default
page.locator("text=Save"); page.locator("role=button[name=\"Save\"]")
page.getByRole("button", name = "Save")
page.getByText("Welcome"); page.getByLabel("Email"); page.getByPlaceholder("Search")
page.getByTestId("submit"); page.frameLocator("iframe").locator("button")
val items = page.locator("li")
items.first(); items.last(); items.nth(2); items.filter(hasText = "Done")

// Actions (auto-wait for visible, enabled, stable, receives events)
val email = page.locator("#email")
email.fill("a@b.com"); email.clear(); email.pressSequentially("abc")
val save = page.locator("#save")
save.click(); save.dblclick(); save.tap(); save.hover()
val agree = page.locator("#agree")
agree.check(); agree.uncheck(); agree.setChecked(true)
page.locator("select").selectOption("BR")
page.locator("#q").press("Enter"); page.keyboard.press("Control+A")
page.locator("#a").dragTo(page.locator("#b"))
page.scroll(ScrollDirection.Down, 300.0); page.scrollToBottom()
// Selector conveniences exist too: page.click("#save"), page.fill("#q", "x"), page.textContent("h1")

// Reading
val h1 = page.locator("h1")
email.inputValue(); h1.innerText(); h1.textContent(); h1.getAttribute("title")
items.count(); save.isVisible(); agree.isChecked(); save.boundingBox()
page.evaluate("document.title").value           // JsResult(value: String, type: String)
page.content()

// Assertions (retry until timeout)
expect(page.locator("#count")).toHaveText("2")
expect(page.locator("#err")).not.toBeVisible()
expect(page).toHaveURL(Regex(".*/done"))

// Screenshots
val shot = page.screenshot(fullPage = true) // ScreenshotResult(bytes, width, height, annotations)

// Network
page.route("**/api/*") { route -> route.fulfill(json = """{"ok":true}""") }
val response = page.waitForResponse("**/api/items") { click("#load") }

// State
page.cookies(); page.addCookies(listOf(Cookie("k", "v", "example.com"))); page.clearCookies()
page.localStorage.set("k", "v"); page.localStorage.entries()
```

## Agent workflow (snapshot → ref → act)

```kotlin
val snap = page.snapshot(interactive = true)
println(snap.tree)                  // agent-browser format, one element per line:
// @e1 [heading] "Sign up" [level=1]
// @e2 [input type="email"] placeholder="Email"
// @e3 [button] "Create account" [disabled]
snap.refs["@e3"]                    // RefEntry(ref, role, name, cssSelector)
page.locator("@e3").click()         // "@e3", "e3" and "ref=e3" all work
```

Or the agent-browser command line, which is what you give an LLM as a single tool:

```kotlin
page.runCommand("snapshot -i")              // AgentCommandResult(command, output, snapshot, screenshot)
page.runCommand("fill @e2 \"hello world\"")
page.runCommand("click @e3")
page.runCommand("press Enter")
page.runCommand("screenshot --annotate")    // result.screenshot!!.bytes is a PNG
AgentCommands.HELP                          // use as the tool description
```

One tool, name `browser`, one string param `command`:

```kotlin
suspend fun Page.browserTool(command: String): String = try {
    runCommand(command).output
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    "Error: ${e.message}"   // messages are written for the model; feed them back
}
```

Commands: `open <url>`, `back`, `forward`, `reload`, `snapshot [-i] [-u] [-c] [-d n] [-s css] [-C]`,
`screenshot [--full] [--annotate]`, `click|dblclick|tap|hover|focus|check|uncheck|highlight <target>`,
`fill <target> <text>`, `type <target> <text>`, `press <key> [target]`, `select <target> <value>`,
`drag <src> <dst>`, `scroll <up|down|left|right|top|bottom> [px]`, `scrollintoview <target>`,
`get text|html|value|count|box|title|url [target]`, `get attr <target> <name>`,
`is visible|enabled|checked|focused|editable <target>`, `wait <ms|target>`, `wait --text|--load|--fn …`,
`eval <js>`, `console`, `errors`, `cookies [clear]`, `storage <local|session> [clear]`.

Safety for agents:

```kotlin
page.setAllowedDomains(listOf("example.com", "*.example.com"))
page.setActionPolicy(ActionPolicy(confirmActions = setOf(ActionType.Navigate), deniedActions = setOf(ActionType.Evaluate)))
page.setPolicyConfirmCallback { action, url -> askUser(action, url) }
Browser(context, KmpwrightConfig(allowEval = false))   // disables eval and wait --fn
page.createContentBoundary().wrap(text)                // mark untrusted page text for the model
```

## Gotchas

- **Everything is `suspend`.** Call from a coroutine. Do not `runBlocking` on the main thread.
- **Main thread on mobile.** Create `Browser` (and the WebView) on the Android main thread; the constructor throws otherwise. On iOS, WebKit work hops to the main queue, so never block main while waiting on KMPwright.
- **`timeoutMs: Long? = null` means "use the config default"**, not "no timeout". Defaults come from `KmpwrightConfig(timeouts = TimeoutConfig(default, navigation, action, waitForSelector))`, all 30 000 ms. Timeouts throw `KmpwrightTimeoutException` (a `KmpwrightException`).
- **Refs belong to the latest snapshot.** A new `snapshot()` (or `screenshot(annotate = true)`, which snapshots) replaces the registered refs; refs not in it stop resolving. The same element keeps the same ref across snapshots of one document. Navigation invalidates all refs. Nothing re-snapshots implicitly: re-snapshot after actions that change the page.
- **Stale refs:** actions wait until the action timeout, then throw `Element @e3 was not found…`; `get text`/`get value` return empty and `is visible` returns false without throwing.
- **`runCommand` quoting:** quotes group words; backslash escapes the next character even inside single quotes. `wait --timeout` defaults to 10 000 ms; put the target first (`wait @e3 --timeout 5000`).
- **Strict selectors:** actions on a selector that matches several elements throw a strict mode violation. Use refs, `first()`/`nth()`, or a more specific selector.
- **Capabilities differ per backend.** Check `page.capabilities()` (`backend`, `routeInterception`, `domainFiltering`, `pdf`, `tracing`, `tabs`, …, plus `notes`) or `page.requireCapabilities(PageCapability.RouteInterception)`. Tracing, profiling, video, screencast, `newWindow` and `targets()` are CDP (JVM) only; PDF is `Partial` on mobile. File upload (`setInputFiles`) is not supported on mobile WebViews. `domainFiltering` is `Partial` on Android.
- **Snapshots differ per backend:** JVM uses Chromium's accessibility tree; WebViews compute roles and names from the DOM.
- **Old names do not exist.** Use Playwright names: `goBack` (not `back`), `getByRole` (not `findByRole`), `pressSequentially`, `waitForSelector`, `setAllowedDomains`, `snapshot(interactive = true)`, `diffSnapshot`. Options are named parameters (`click(button = MouseButton.Right, force = true)`), not option classes; enum constants are PascalCase (`MouseButton.Left`, `ScreenshotFormat.Png`).

## Docs

- Build an agent: https://kmpwright.dev/docs/agents
- Commands (`runCommand`): https://kmpwright.dev/docs/agents/commands
- Snapshots and refs: https://kmpwright.dev/docs/agents/snapshots
- LLM tools: https://kmpwright.dev/docs/agents/llm-tools
- Locators: https://kmpwright.dev/docs/guides/locators
- Page reference: https://kmpwright.dev/docs/reference/page
- Platform capabilities: https://kmpwright.dev/docs/platforms/capabilities
