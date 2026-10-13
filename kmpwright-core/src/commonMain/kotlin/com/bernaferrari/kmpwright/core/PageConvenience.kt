package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.flow.first
import kotlin.coroutines.cancellation.CancellationException

// Selector-taking conveniences, like Playwright's `page.click(selector)` family. Each one is
// `page.locator(selector).<action>(...)` with the same named parameters as the [Locator] method;
// selectors also accept snapshot refs (`@e3`, `e3`, `ref=e3`).

/** Clicks the element matching [selector] (Playwright `page.click`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.click(
    selector: String,
    button: MouseButton = MouseButton.Left,
    clickCount: Int = 1,
    delayMs: Long = 0,
    position: Position? = null,
    force: Boolean = false,
    timeoutMs: Long? = null
): Unit = locator(selector).click(button, clickCount, delayMs, position, force, timeoutMs)

/** Double-clicks the element matching [selector] (Playwright `page.dblclick`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.dblclick(
    selector: String,
    button: MouseButton = MouseButton.Left,
    delayMs: Long = 0,
    position: Position? = null,
    force: Boolean = false,
    timeoutMs: Long? = null
): Unit = locator(selector).dblclick(button, delayMs, position, force, timeoutMs)

/** Taps the element matching [selector] (Playwright `page.tap`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.tap(selector: String, force: Boolean = false, modifiers: Int = 0, timeoutMs: Long? = null): Unit =
    locator(selector).tap(force, modifiers, timeoutMs)

/** Fills the element matching [selector] (Playwright `page.fill`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.fill(selector: String, value: String, force: Boolean = false, timeoutMs: Long? = null): Unit =
    locator(selector).fill(value, force, timeoutMs)

/** Clears the element matching [selector] (Playwright `locator.clear`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.clear(selector: String, force: Boolean = false, timeoutMs: Long? = null): Unit =
    locator(selector).clear(force, timeoutMs)

/** Types [text] key by key into the element matching [selector] (Playwright `page.type`, agent-browser `type`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.type(selector: String, text: String, delayMs: Long = 0, timeoutMs: Long? = null): Unit =
    locator(selector).pressSequentially(text, delayMs, timeoutMs)

/** Presses [key] on the element matching [selector] (Playwright `page.press`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.press(selector: String, key: String, delayMs: Long = 0, timeoutMs: Long? = null): Unit =
    locator(selector).press(key, delayMs, timeoutMs)

/** Hovers the element matching [selector] (Playwright `page.hover`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.hover(selector: String, force: Boolean = false, timeoutMs: Long? = null): Unit =
    locator(selector).hover(force, timeoutMs)

/** Focuses the element matching [selector] (Playwright `page.focus`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.focus(selector: String, timeoutMs: Long? = null): Unit =
    locator(selector).focus(timeoutMs)

/** Highlights the element matching [selector] (agent-browser `highlight`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.highlight(
    selector: String,
    color: String = "rgba(255, 0, 0, 0.3)",
    borderColor: String = "red",
    durationMs: Long = 3000
): Unit = locator(selector).highlight(color, borderColor, durationMs)

/** Checks the element matching [selector] (Playwright `page.check`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.check(selector: String, force: Boolean = false, timeoutMs: Long? = null): Unit =
    locator(selector).check(force, timeoutMs)

/** Unchecks the element matching [selector] (Playwright `page.uncheck`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.uncheck(selector: String, force: Boolean = false, timeoutMs: Long? = null): Unit =
    locator(selector).uncheck(force, timeoutMs)

/** Checks or unchecks the element matching [selector] (Playwright `page.setChecked`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.setChecked(selector: String, checked: Boolean, force: Boolean = false, timeoutMs: Long? = null): Unit =
    locator(selector).setChecked(checked, force, timeoutMs)

/** Selects an option of the `<select>` matching [selector] (Playwright `page.selectOption`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.selectOption(selector: String, value: String, force: Boolean = false, timeoutMs: Long? = null): Unit =
    locator(selector).selectOption(value, force, timeoutMs)

/** Selects options of the `<select>` matching [selector] (Playwright `page.selectOption`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.selectOption(selector: String, values: List<String>, force: Boolean = false, timeoutMs: Long? = null): Unit =
    locator(selector).selectOption(values, force, timeoutMs)

/** Sets the files of the file input matching [selector] (Playwright `page.setInputFiles`, agent-browser `upload`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.setInputFiles(selector: String, files: List<String>, force: Boolean = false, timeoutMs: Long? = null): Unit =
    locator(selector).setInputFiles(files, force, timeoutMs)

/** Drags [source] onto [target] (Playwright `page.dragAndDrop`, agent-browser `drag`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.dragAndDrop(
    source: String,
    target: String,
    sourcePosition: Position? = null,
    targetPosition: Position? = null,
    timeoutMs: Long? = null
): Unit = locator(source).dragTo(locator(target), sourcePosition, targetPosition, timeoutMs)

/** Dispatches a DOM event on the element matching [selector] (Playwright `page.dispatchEvent`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.dispatchEvent(selector: String, type: String, timeoutMs: Long? = null): Unit =
    locator(selector).dispatchEvent(type, timeoutMs)

// --- Queries (wait for the element to be attached first, like Playwright) ---

private suspend fun Page.attached(selector: String, timeoutMs: Long?): Locator =
    locator(selector).also { it.waitFor(SelectorState.Attached, timeoutMs) }

/** `textContent` of the element matching [selector] (Playwright `page.textContent`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.textContent(selector: String, timeoutMs: Long? = null): String? =
    attached(selector, timeoutMs).textContent()

/** `innerText` of the element matching [selector] (Playwright `page.innerText`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.innerText(selector: String, timeoutMs: Long? = null): String =
    attached(selector, timeoutMs).innerText()

/** `innerHTML` of the element matching [selector] (Playwright `page.innerHTML`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.innerHTML(selector: String, timeoutMs: Long? = null): String =
    attached(selector, timeoutMs).innerHTML()

/** Attribute [name] of the element matching [selector] (Playwright `page.getAttribute`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.getAttribute(selector: String, name: String, timeoutMs: Long? = null): String? =
    attached(selector, timeoutMs).getAttribute(name)

/** Value of the input matching [selector] (Playwright `page.inputValue`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.inputValue(selector: String, timeoutMs: Long? = null): String? =
    attached(selector, timeoutMs).inputValue()

/** Whether the element matching [selector] is visible; false when nothing matches (no waiting). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.isVisible(selector: String): Boolean = locator(selector).isVisible()

/** Whether the element matching [selector] is hidden or missing (no waiting). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.isHidden(selector: String): Boolean = locator(selector).isHidden()

/** Whether the element matching [selector] is enabled; throws when nothing matches. */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.isEnabled(selector: String): Boolean = locator(selector).isEnabled()

/** Whether the element matching [selector] is disabled; throws when nothing matches. */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.isDisabled(selector: String): Boolean = locator(selector).isDisabled()

/** Whether the element matching [selector] is checked; throws when nothing matches. */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.isChecked(selector: String): Boolean = locator(selector).isChecked()

/** Whether the element matching [selector] is editable; throws when nothing matches. */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.isEditable(selector: String): Boolean = locator(selector).isEditable()

/** Number of elements matching [selector] (agent-browser `get count`; no waiting). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.count(selector: String): Int = locator(selector).count()

/** Waits for the element matching [selector] to reach [state] (Playwright `page.waitForSelector`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.waitForSelector(selector: String, state: SelectorState = SelectorState.Visible, timeoutMs: Long? = null): Unit =
    locator(selector).waitFor(state, timeoutMs)

// --- Clipboard and dialogs ---

/** Inserts the clipboard text into the focused element through the page keyboard (agent-browser `clipboard paste`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.paste() {
    val text = clipboard.readText() ?: return
    keyboard.insertText(text)
}

/** Accepts every dialog until the returned handle is closed. */
public fun Page.acceptDialogs(): DialogHandler = onDialog { DialogResponse.Accept }

/** Dismisses every dialog until the returned handle is closed. */
public fun Page.dismissDialogs(): DialogHandler = onDialog { DialogResponse.Dismiss }

/** Accepts dialogs matching [predicate] and dismisses the rest until the handle is closed. */
public fun Page.acceptDialogsIf(predicate: (DialogEvent) -> Boolean): DialogHandler =
    onDialog { event -> if (predicate(event)) DialogResponse.Accept else DialogResponse.Dismiss }

/**
 * Waits for the next dialog and returns it (Playwright `page.waitForEvent('dialog')`). While it
 * waits it counts as a dialog listener on every backend, so the dialog is delivered to automation
 * even on Android (where dialogs otherwise go to the host WebChromeClient). The dialog is answered
 * by the newest [Page.onDialog] handler, or dismissed when there is none; register a handler first
 * to accept it. Prefer `waitForDialog { click(...) }`, which cannot miss it.
 * @throws KmpwrightTimeoutException when no dialog opens within [timeoutMs] (default [TimeoutConfig.default]).
 */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.waitForDialog(timeoutMs: Long? = null): DialogEvent {
    val timeout = kmpwrightConfig.defaultTimeout(timeoutMs)
    val wait: suspend () -> DialogEvent = {
        withKmpwrightTimeout(timeout, { "Timeout ${timeout}ms exceeded waiting for a dialog" }) { events.dialog.first() }
    }
    return (this as? BasePage)?.awaitingDialog(wait) ?: wait()
}

/** The configuration of a KMPwright page, or the defaults for other [Page] implementations. */
internal val Page.kmpwrightConfig: KmpwrightConfig get() = (this as? BasePage)?.pageConfig ?: KmpwrightConfig()
