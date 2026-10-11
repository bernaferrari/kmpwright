package com.bernaferrari.kmpwright.core

// --- Auto-wait helper (Feature D) ---

private suspend fun Page.autoWaitLocator(selector: String, timeoutMs: Long = 10_000): Locator {
    val refId = parseRef(selector)
    if (refId != null) {
        // Ref-based: locatorByRef handles snapshot internally
        return locatorByRef(refId)
    }
    val loc = locator(selector)
    loc.waitFor(SelectorState.Attached, timeoutMs)
    return loc
}

// --- Action convenience methods (with auto-wait) ---

suspend fun Page.click(selector: String, options: ClickOptions = ClickOptions()) {
    autoWaitLocator(selector).click(options)
}

suspend fun Page.fill(selector: String, text: String, options: FillOptions = FillOptions()) {
    autoWaitLocator(selector).fill(text, options)
}

suspend fun Page.clear(selector: String) {
    autoWaitLocator(selector).fill("", FillOptions())
}

suspend fun Page.type(selector: String, text: String, options: TypeOptions = TypeOptions()) {
    autoWaitLocator(selector).type(text, options)
}

suspend fun Page.press(selector: String, key: String, options: PressOptions = PressOptions()) {
    autoWaitLocator(selector).press(key, options)
}

suspend fun Page.hover(selector: String) {
    autoWaitLocator(selector).hover()
}

suspend fun Page.highlight(selector: String, options: HighlightOptions = HighlightOptions()) {
    autoWaitLocator(selector).highlight(options)
}

suspend fun Page.focus(selector: String) {
    autoWaitLocator(selector).focus()
}

suspend fun Page.check(selector: String, options: CheckOptions = CheckOptions()) {
    autoWaitLocator(selector).check(options)
}

suspend fun Page.uncheck(selector: String, options: CheckOptions = CheckOptions()) {
    autoWaitLocator(selector).uncheck(options)
}

suspend fun Page.selectOption(selector: String, value: String, options: ActionOptions = ActionOptions()) {
    autoWaitLocator(selector).selectOption(value, options)
}

suspend fun Page.upload(selector: String, files: List<String>) {
    autoWaitLocator(selector).setInputFiles(files)
}

// --- Query convenience methods (with auto-wait) ---

suspend fun Page.text(selector: String): String? =
    autoWaitLocator(selector).textContent()

suspend fun Page.innerText(selector: String): String =
    autoWaitLocator(selector).innerText()

suspend fun Page.html(selector: String): String =
    autoWaitLocator(selector).innerHTML()

suspend fun Page.attr(selector: String, name: String): String? =
    autoWaitLocator(selector).getAttribute(name)

suspend fun Page.value(selector: String): String? =
    autoWaitLocator(selector).inputValue()

suspend fun Page.isVisible(selector: String): Boolean =
    autoWaitLocator(selector).isVisible()

suspend fun Page.isEnabled(selector: String): Boolean =
    autoWaitLocator(selector).isEnabled()

suspend fun Page.isChecked(selector: String): Boolean =
    autoWaitLocator(selector).isChecked()

suspend fun Page.count(selector: String): Int =
    autoWaitLocator(selector).count()

suspend fun Page.waitFor(selector: String, state: SelectorState = SelectorState.Visible, timeoutMs: Long = 30_000) {
    locator(selector).waitFor(state, timeoutMs)
}

// --- Semantic find() overloads ---

/**
 * Unified element lookup that handles all selector types:
 * - `@e1` → locatorByRef()
 * - `e1` → locatorByRef("@e1")
 * - `ref=e1` → locatorByRef("@e1" or "e1")
 * - everything else → locator(selector)
 *
 * Returns [Locator] for chaining: `page.find("button").click()`
 */
suspend fun Page.find(selector: String): Locator {
    val refId = parseRef(selector)
    return if (refId != null) locatorByRef(refId) else locator(selector)
}

// Single-param semantic finds
suspend fun Page.findByText(text: String, exact: Boolean = false): Locator =
    locator(if (exact) "exact_text=$text" else "text=$text")

suspend fun Page.findByRole(role: String): Locator = locator("role=$role")
suspend fun Page.findByLabel(label: String): Locator = locator("label=$label")
suspend fun Page.findByPlaceholder(placeholder: String): Locator = locator("[placeholder=\"${escapeCssString(placeholder)}\"]")
suspend fun Page.findByAlt(alt: String): Locator = locator("alt=$alt")
suspend fun Page.findByTitle(title: String): Locator = locator("title=$title")
suspend fun Page.findByTestId(testId: String): Locator = locator("testid=$testId")

// Multi-param semantic find — dispatches by which params are set
suspend fun Page.find(
    role: String? = null,
    text: String? = null,
    name: String? = null,
    label: String? = null,
    placeholder: String? = null,
    alt: String? = null,
    title: String? = null,
    testId: String? = null,
    exact: Boolean = false
): Locator {
    if (text != null) {
        return if (exact) locator("exact_text=$text") else locator("text=$text")
    }
    if (role != null && name != null) {
        val snap = accessibilitySnapshot(A11ySnapshotOptions(interactiveOnly = false))
        val match = snap.refMap.values.firstOrNull {
            it.role.equals(role, ignoreCase = true) &&
                if (exact) it.name.equals(name, ignoreCase = true)
                else it.name.contains(name, ignoreCase = true)
        }
        return if (match?.cssSelector != null) locator(match.cssSelector)
        else locator("role=$role >> exact_text=$name")
    }
    if (role != null) return locator("role=$role")
    if (label != null) return locator("label=$label")
    if (placeholder != null) return locator("[placeholder=\"${escapeCssString(placeholder)}\"]")
    if (alt != null) return locator("alt=$alt")
    if (title != null) return locator("title=$title")
    if (testId != null) return locator("testid=$testId")
    throw KmpwrightException("find() requires at least one of: role, text, name, label, placeholder, alt, title, testId")
}

// --- snapshot() convenience ---

/**
 * Takes an accessibility snapshot and returns a sorted list of [RefEntry] for easy ref lookup.
 *
 * Usage:
 *   val refs = page.snapshot()
 *   refs.byName("Submit")?.let { page.locator(it.cssSelector!!).click() }
 */
suspend fun Page.snapshot(scopeSelector: String? = null): List<RefEntry> {
    val snap = accessibilitySnapshot(A11ySnapshotOptions(interactiveOnly = true, scopeSelector = scopeSelector))
    return snap.refMap.values.sortedBy { it.refId }
}

/**
 * Find a ref entry by exact name (case-insensitive).
 */
fun List<RefEntry>.byName(name: String): RefEntry? =
    firstOrNull { it.name.equals(name, ignoreCase = true) }

/**
 * Find a ref entry by partial name match (case-insensitive substring).
 */
fun List<RefEntry>.byNameContains(partial: String): RefEntry? =
    firstOrNull { it.name.contains(partial, ignoreCase = true) }

/**
 * Find all ref entries matching a role (case-insensitive).
 */
fun List<RefEntry>.byRole(role: String): List<RefEntry> =
    filter { it.role.equals(role, ignoreCase = true) }

// --- Diff convenience ---

/**
 * Takes a snapshot now and diffs against the named baseline.
 * Updates the baseline after diffing.
 */
suspend fun Page.diffBaseline(name: String): SnapshotDiffResult = diffSnapshot(name)

/**
 * Takes a snapshot now and diffs against the last saved "__baseline__".
 */
suspend fun Page.diffNow(): SnapshotDiffResult = diffSnapshot("__baseline__")

// --- Request filtering convenience ---

/**
 * Collects all request events, optionally filtered by URL pattern (substring match).
 * Mirrors agent-browser's `network requests --filter <pattern>`.
 *
 * Note: this collects events received during the call only (SharedFlow has no replay).
 * For a meaningful list, call this *before* navigation or actions that trigger requests.
 *
 * Usage:
 *   page.requests()           // all requests received during the call
 *   page.requests("/api")     // only requests containing "/api" in the URL
 */
suspend fun Page.requests(filter: String? = null, timeoutMs: Long = 1000): List<RequestEvent> {
    val all = mutableListOf<RequestEvent>()
    try {
        kotlinx.coroutines.withTimeout(timeoutMs) {
            events.request.collect { all.add(it) }
        }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {}
    return if (filter != null) {
        all.filter { it.url.contains(filter, ignoreCase = true) }
    } else {
        all
    }
}

// --- Clipboard convenience ---

/**
 * Reads text from the clipboard.
 * Alias for [Page.clipboardRead] with a shorter name.
 */
suspend fun Page.readClipboard(): String? = clipboardRead()

/**
 * Writes text to the clipboard.
 * Alias for [Page.clipboardWrite] with a shorter name.
 */
suspend fun Page.writeClipboard(text: String) = clipboardWrite(text)

/**
 * Reads from clipboard, then pastes the content into the currently focused element.
 * Equivalent to Ctrl+V in the browser.
 *
 * Usage:
 *   page.writeClipboard("hello")
 *   page.paste()
 */
suspend fun Page.paste() {
    val text = clipboardRead() ?: return
    val escaped = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
    evaluate("(function() { var v = \"$escaped\"; document.activeElement.value = v; document.activeElement.dispatchEvent(new Event('input', { bubbles: true })); })()")
}

// --- Dialog convenience ---

/**
 * Automatically accepts all dialogs (alert, confirm).
 * Returns a [DialogHandler] that should be closed when no longer needed.
 *
 * Usage:
 *   val handler = page.acceptDialogs()
 *   page.click("@e3") // triggers alert
 *   handler.close()
 */
fun Page.acceptDialogs(): DialogHandler = onDialog { event ->
    DialogResponse.Accept
}

/**
 * Automatically dismisses all dialogs (alert, confirm).
 * Returns a [DialogHandler] that should be closed when no longer needed.
 *
 * Usage:
 *   val handler = page.dismissDialogs()
 *   page.click("@e3") // triggers confirm, will be dismissed
 *   handler.close()
 */
fun Page.dismissDialogs(): DialogHandler = onDialog { event ->
    DialogResponse.Dismiss
}

/**
 * Automatically accepts dialogs that match a predicate.
 *
 * Usage:
 *   page.acceptDialogsIf { it.message.contains("Delete") }
 */
fun Page.acceptDialogsIf(predicate: (DialogEvent) -> Boolean): DialogHandler = onDialog { event ->
    if (predicate(event)) DialogResponse.Accept else DialogResponse.Dismiss
}

/**
 * Waits for the next dialog event and returns it.
 * The dialog is automatically dismissed (to avoid blocking the page).
 * Use [acceptDialogs] if you want to accept instead.
 *
 * Usage:
 *   val event = page.waitForDialog()
 *   println("Dialog: ${event.type} — ${event.message}")
 */
suspend fun Page.waitForDialog(timeoutMs: Long = 30_000): DialogEvent {
    // Register a handler that captures the event
    var captured: DialogEvent? = null
    val handler = onDialog { event ->
        captured = event
        DialogResponse.Dismiss
    }
    return try {
        kotlinx.coroutines.withTimeout(timeoutMs) {
            while (captured == null) kotlinx.coroutines.delay(10)
        }
        captured ?: throw KmpwrightException("Timeout waiting for dialog")
    } finally {
        handler.close()
    }
}

// --- Network profile convenience ---

/**
 * Applies a named network profile for throttling.
 * Convenience wrapper over [emulateNetwork].
 *
 * Usage:
 *   page.setNetworkProfile(NetworkProfile.Fast3G)
 *   page.setNetworkProfile(NetworkProfile.Slow3G)
 *   page.setNetworkProfile(NetworkProfile.Offline)
 *   page.setNetworkProfile(NetworkProfile.None) // reset
 */
suspend fun Page.setNetworkProfile(profile: NetworkProfile) {
    emulateNetwork(
        offline = profile == NetworkProfile.Offline,
        latency = profile.latency,
        downloadThroughput = profile.downloadKbps,
        uploadThroughput = profile.uploadKbps
    )
}

// --- Auth profile convenience ---

/**
 * Login using a saved auth profile.
 * If the profile has selectors, fills them and optionally clicks submit.
 * Otherwise, falls back to HTTP credentials.
 *
 * Usage:
 *   page.loginWith("github")
 *   page.loginWith("intranet", password = "decrypt-key")
 */
suspend fun Page.loginWith(profileName: String, password: String? = null) {
    val profile = AuthManager.load(profileName, password)
        ?: throw KmpwrightException("Auth profile not found: $profileName")
    if (profile.usernameSelector != null && profile.passwordSelector != null) {
        goto(profile.url)
        fill(profile.usernameSelector, profile.username)
        fill(profile.passwordSelector, profile.password)
        if (profile.submitSelector != null) click(profile.submitSelector)
    } else {
        // Native HTTP credentials must be configured before the request that
        // can challenge for them. Mobile controllers bind them to this first
        // HTTP origin and never expose them to the page content world.
        setHTTPCredentials(profile.username, profile.password)
        goto(profile.url)
    }
}

/**
 * Save current credentials as a reusable auth profile.
 *
 * Usage:
 *   page.saveAuthProfile("github", url = "https://github.com/login", username = "user", password = "pass")
 *   page.saveAuthProfile("intranet", url = "https://intra.example.com",
 *       username = "admin", password = "secret",
 *       usernameSelector = "#user", passwordSelector = "#pass", submitSelector = "#login")
 */
suspend fun Page.saveAuthProfile(
    name: String,
    url: String,
    username: String,
    password: String,
    usernameSelector: String? = null,
    passwordSelector: String? = null,
    submitSelector: String? = null
) {
    val profile = AuthProfile(name, url, username, password, usernameSelector, passwordSelector, submitSelector)
    AuthManager.save(profile, password = password)
}

// --- Internal helpers ---

/**
 * Escapes a string for safe use inside a CSS attribute selector value.
 * Prevents breaking out of `[attr="..."]` with special characters.
 */
internal fun escapeCssString(str: String): String {
    return str.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("]", "\\]")
}
