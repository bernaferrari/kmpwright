package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CdpInputDevicesTest {

    @Test fun `cancelling long tap and downAndUp releases input`() = kotlinx.coroutines.test.runTest {
        val bridge = RecordingCdpInputBridge()
        val touch = CdpTouch(bridge)
        val tap = launch { touch.longTap(10.0, 20.0, LongTapOptions(durationMs = 60_000)) }
        runCurrent()
        tap.cancel(); tap.join()
        assertEquals("touchEnd", bridge.commands.last().params["type"])
        val keyboard = CdpKeyboard(bridge)
        val key = launch { keyboard.downAndUp("Control", TypeOptions(delayMs = 60_000)) }
        runCurrent()
        key.cancel(); key.join()
        assertEquals("keyUp", bridge.commands.last().params["type"])
        assertEquals(emptySet<String>(), keyboard.modifiers())
    }

    @Test fun `pinch increases touch capacity after tap`() = runBlocking {
        val bridge = RecordingCdpInputBridge()
        val touch = CdpTouch(bridge)
        touch.tap(10.0, 20.0)
        touch.pinch(10.0, 20.0, 2.0)
        assertEquals(listOf(1, 2), bridge.commands.filter { it.method == CdpMethods.EMULATION_SET_TOUCH_EMULATION_ENABLED }.map { it.params["maxTouchPoints"] })
    }

    @Test
    fun `base page reuses cached input devices`() {
        val page = object : BasePage(MockWebViewController(), KmpwrightConfig()) {
            override fun createPlatformLocator(selector: String): BaseLocator =
                TestLocator(selector, controller)

            override fun createTouch(): Touch = object : Touch {
                override suspend fun tap(x: Double, y: Double, options: TapOptions) {}
                override suspend fun longTap(x: Double, y: Double, options: LongTapOptions) {}
                override suspend fun scroll(x: Double, y: Double, deltaX: Double, deltaY: Double, options: ScrollOptions) {}
                override suspend fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, options: SwipeOptions) {}
                override suspend fun pinch(x: Double, y: Double, scale: Double, options: PinchOptions) {}
            }

            override fun createMouse(): Mouse = object : Mouse {
                override suspend fun move(x: Double, y: Double, options: MoveOptions) {}
                override suspend fun click(x: Double, y: Double, options: MouseClickOptions) {}
                override suspend fun dblclick(x: Double, y: Double, options: MouseClickOptions) {}
                override suspend fun tripleclick(x: Double, y: Double, options: MouseClickOptions) {}
                override suspend fun down(options: MouseDownOptions) {}
                override suspend fun up(options: MouseUpOptions) {}
                override suspend fun wheel(deltaX: Double, deltaY: Double, options: WheelOptions) {}
                override suspend fun drag(startX: Double, startY: Double, endX: Double, endY: Double, options: DragOptions) {}
            }

            override fun createKeyboard(): Keyboard = object : Keyboard {
                override suspend fun insertText(text: String) {}
                override suspend fun type(text: String, options: TypeOptions) {}
                override suspend fun down(key: String) {}
                override suspend fun up(key: String) {}
                override suspend fun press(key: String, options: PressOptions) {}
                override suspend fun downAndUp(key: String, options: TypeOptions) {}
                override fun isModifierPressed(key: String): Boolean = false
                override fun modifiers(): Set<String> = emptySet()
            }
        }

        assertSame(page.mouse, page.mouse)
        assertSame(page.keyboard, page.keyboard)
        assertSame(page.touch, page.touch)
    }

    @Test
    fun `mouse click dispatches native CDP events`() = runBlocking {
        val bridge = RecordingCdpInputBridge()
        val mouse = CdpMouse(bridge)

        mouse.click(40.0, 24.0)

        assertEquals(
            listOf(
                CdpMethods.INPUT_DISPATCH_MOUSE_EVENT,
                CdpMethods.INPUT_DISPATCH_MOUSE_EVENT,
                CdpMethods.INPUT_DISPATCH_MOUSE_EVENT
            ),
            bridge.commands.map { it.method }
        )
        assertEquals("mouseMoved", bridge.commands[0].params["type"])
        assertEquals("mousePressed", bridge.commands[1].params["type"])
        assertEquals("mouseReleased", bridge.commands[2].params["type"])
        assertEquals("left", bridge.commands[1].params["button"])
    }

    @Test
    fun `keyboard tracks modifiers and uses insertText fallback`() = runBlocking {
        val bridge = RecordingCdpInputBridge()
        val keyboard = CdpKeyboard(bridge)

        keyboard.down("Shift")
        keyboard.press("A")
        keyboard.type("🙂")
        keyboard.up("Shift")

        assertEquals(CdpMethods.INPUT_DISPATCH_KEY_EVENT, bridge.commands[0].method)
        assertEquals(8, bridge.commands[0].params["modifiers"])
        assertEquals(CdpMethods.INPUT_DISPATCH_KEY_EVENT, bridge.commands[1].method)
        assertEquals(8, bridge.commands[1].params["modifiers"])
        assertEquals(CdpMethods.INPUT_INSERT_TEXT, bridge.commands[3].method)
        assertEquals("🙂", bridge.commands[3].params["text"])
    }

    @Test
    fun `keyboard sends shifted text and key metadata when shift is held`() = runBlocking {
        val bridge = RecordingCdpInputBridge()
        val keyboard = CdpKeyboard(bridge)

        keyboard.down("Shift")
        keyboard.press("1")

        val keyDown = bridge.commands[1]
        assertEquals("!", keyDown.params["key"])
        assertEquals("Digit1", keyDown.params["code"])
        assertEquals(49, keyDown.params["windowsVirtualKeyCode"])
        assertEquals("!", keyDown.params["text"])
        assertEquals(8, keyDown.params["modifiers"])
    }

    @Test
    fun `canceled click releases its button and preserves caller held buttons`() = kotlinx.coroutines.test.runTest {
        val bridge = RecordingCdpInputBridge()
        val mouse = CdpMouse(bridge)
        mouse.down(MouseDownOptions(button = MouseButton.RIGHT))
        val job = launch { mouse.click(1.0, 2.0, MouseClickOptions(delayMs = 10_000)) }
        runCurrent()
        job.cancel(); job.join()
        val release = bridge.commands.last()
        assertEquals("mouseReleased", release.params["type"])
        assertEquals("left", release.params["button"])
        assertEquals(2, release.params["buttons"])
        mouse.move(3.0, 4.0)
        assertEquals(2, bridge.commands.last().params["buttons"])
        mouse.up(MouseUpOptions(button = MouseButton.RIGHT))
    }

    @Test
    fun `failed drag releases its acquired button`() = runBlocking {
        val commands = mutableListOf<Map<String, Any?>>()
        val bridge = object : CdpInputBridge {
            override val inputState = CdpInputState()
            override suspend fun sendCdpCommand(method: String, params: Map<String, Any?>): CdpResponse {
                commands += params
                if (params["type"] == "mouseMoved" && commands.size > 2) throw IllegalStateException("move failed")
                return CdpResponse(commands.size)
            }
        }
        try { CdpMouse(bridge).drag(1.0, 2.0, 100.0, 200.0); error("Expected movement failure") }
        catch (_: IllegalStateException) { assertEquals("mouseReleased", commands.last()["type"]) }
    }

    @Test
    fun `touch tap enables touch emulation and dispatches touch events`() = runBlocking {
        val bridge = RecordingCdpInputBridge()
        val touch = CdpTouch(bridge)

        touch.tap(12.0, 18.0)

        assertEquals(CdpMethods.EMULATION_SET_TOUCH_EMULATION_ENABLED, bridge.commands[0].method)
        assertEquals(CdpMethods.INPUT_DISPATCH_TOUCH_EVENT, bridge.commands[1].method)
        assertEquals("touchStart", bridge.commands[1].params["type"])
        assertEquals(CdpMethods.INPUT_DISPATCH_TOUCH_EVENT, bridge.commands[2].method)
        assertEquals("touchEnd", bridge.commands[2].params["type"])
    }
}

private class RecordingCdpInputBridge : CdpInputBridge {
    override val inputState = CdpInputState()
    val commands = mutableListOf<RecordedCdpCommand>()

    override suspend fun sendCdpCommand(method: String, params: Map<String, Any?>): CdpResponse {
        commands += RecordedCdpCommand(method, params)
        return CdpResponse(id = commands.size)
    }
}

private data class RecordedCdpCommand(
    val method: String,
    val params: Map<String, Any?>
)
