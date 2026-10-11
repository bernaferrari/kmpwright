package com.bernaferrari.kmpwright.core

interface Locator {
    suspend fun click(options: ClickOptions = ClickOptions())
    suspend fun dblclick(options: ClickOptions = ClickOptions())
    suspend fun tap(options: TapOptions = TapOptions())
    suspend fun fill(text: String, options: FillOptions = FillOptions())
    suspend fun type(text: String, options: TypeOptions = TypeOptions())
    suspend fun press(key: String, options: PressOptions = PressOptions())
    suspend fun hover()

    suspend fun scrollIntoViewIfNeeded(options: ScrollIntoViewOptions = ScrollIntoViewOptions())
    suspend fun scrollDown(distance: Double = 0.0)
    suspend fun scrollUp(distance: Double = 0.0)
    suspend fun focus()

    suspend fun check(options: CheckOptions = CheckOptions())
    suspend fun uncheck(options: CheckOptions = CheckOptions())
    suspend fun selectOption(value: String, options: ActionOptions = ActionOptions())
    suspend fun selectOption(values: List<String>, options: ActionOptions = ActionOptions())

    suspend fun setInputFiles(files: List<String>, options: ActionOptions = ActionOptions())
    suspend fun clearInputFiles(options: ActionOptions = ActionOptions())

    suspend fun dragTo(target: Locator, options: DragOptions = DragOptions())

    suspend fun textContent(): String?
    suspend fun innerText(): String
    suspend fun innerHTML(): String
    suspend fun getAttribute(name: String): String?
    suspend fun inputValue(): String?
    suspend fun computedStyles(): Map<String, String>
    suspend fun count(): Int

    suspend fun isVisible(): Boolean
    suspend fun isEnabled(): Boolean
    suspend fun isChecked(): Boolean
    suspend fun isFocused(): Boolean
    suspend fun isEditable(): Boolean

    suspend fun boundingBox(): Rectangle?
    suspend fun screenshot(options: ScreenshotOptions = ScreenshotOptions()): ScreenshotResult

    /**
     * Highlights the element with a colored overlay for visual debugging.
     * Automatically removes the highlight after [durationMs] milliseconds.
     */
    suspend fun highlight(options: HighlightOptions = HighlightOptions())

    suspend fun waitFor(state: SelectorState = SelectorState.Visible, timeoutMs: Long = 30_000)

    fun first(): Locator
    fun nth(index: Int): Locator
    fun last(): Locator
    fun locator(selector: String): Locator
    fun filter(options: FilterOptions = FilterOptions()): Locator
    fun frameLocator(selector: String): FrameLocator

    fun expect(): KmpwrightExpect
}

data class ClickOptions(
    val timeoutMs: Long = 30_000,
    val button: MouseButton = MouseButton.LEFT,
    val clickCount: Int = 1,
    val delayMs: Long = 0,
    val force: Boolean = false
)

data class FillOptions(
    val timeoutMs: Long = 30_000,
    val force: Boolean = false
)

data class ActionOptions(
    val timeoutMs: Long = 30_000,
    val force: Boolean = false
)

data class CheckOptions(
    val timeoutMs: Long = 30_000,
    val force: Boolean = false
)

data class ScrollIntoViewOptions(
    val timeoutMs: Long = 30_000,
    val block: ScrollPosition = ScrollPosition.Center,
    val inline: ScrollPosition = ScrollPosition.Nearest
)

data class FilterOptions(
    val hasText: String? = null,
    val has: String? = null,
    val hasNotText: String? = null,
    val hasNot: String? = null,
    val nth: Int? = null
)

data class Position(
    val x: Double,
    val y: Double
)

enum class ScrollPosition {
    Start,
    Center,
    End,
    Nearest
}

enum class SelectorState {
    Attached,
    Detached,
    Visible,
    Hidden
}

enum class ScrollDirection {
    Up,
    Down,
    Left,
    Right
}

interface FrameLocator {
    fun locator(selector: String): Locator
    fun frameLocator(selector: String): FrameLocator

    /**
     * Returns the name attribute of the iframe, or null.
     */
    suspend fun name(): String?

    /**
     * Returns the URL of the iframe's src attribute, or null.
     */
    suspend fun url(): String?

    /**
     * Returns the owner element's bounding box.
     */
    suspend fun boundingBox(): Rectangle?
}

data class HighlightOptions(
    /** CSS color for the overlay (default: "rgba(255, 0, 0, 0.3)"). */
    val color: String = "rgba(255, 0, 0, 0.3)",
    /** Border color (default: "red"). */
    val borderColor: String = "red",
    /** Duration in ms before auto-removing the highlight (0 = keep until page navigation). */
    val durationMs: Long = 3000
)
