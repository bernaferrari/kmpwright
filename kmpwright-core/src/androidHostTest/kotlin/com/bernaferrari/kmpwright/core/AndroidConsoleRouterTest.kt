package com.bernaferrari.kmpwright.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AndroidConsoleRouterTest {
    private val nonce = "n0nce"
    private var now = 1_000L
    private val router = AndroidConsoleRouter(nonce) { now }

    private fun sentinel(json: String) = nonce + json

    @Test
    fun `message levels map to Playwright console types`() {
        assertEquals("log", androidConsoleType("LOG"))
        assertEquals("debug", androidConsoleType("DEBUG"))
        assertEquals("info", androidConsoleType("TIP"))
        assertEquals("warning", androidConsoleType("WARNING"))
        assertEquals("error", androidConsoleType("ERROR"))
    }

    @Test
    fun `ordinary messages become console messages with their source`() {
        assertEquals(
            AndroidConsoleRouter.Route.Console(ConsoleMessage("error", "logged", SourceLocation("https://a/x.js", 3, 0))),
            router.route("error", "logged", "https://a/x.js", 3)
        )
    }

    @Test
    fun `sentinels are internal and announce documents and errors`() {
        assertEquals(AndroidConsoleRouter.Route.DocumentStarted, router.route("debug", sentinel("""{"nonce":"n0nce","kind":"document"}"""), "", 1))
        assertEquals(AndroidConsoleRouter.Route.Internal, router.route("debug", sentinel("""{"nonce":"other","kind":"document"}"""), "", 1))
        assertEquals(AndroidConsoleRouter.Route.Internal, router.route("debug", sentinel("garbage"), "", 1))
        val error = router.route("debug", sentinel("""{"nonce":"n0nce","kind":"pageerror","name":"Error","message":"boom","stack":"Error: boom"}"""), "", 1)
        assertEquals(AndroidConsoleRouter.Route.Error(PageError("Error", "boom", "Error: boom", null), internal = true), error)
    }

    @Test
    fun `Chromium's uncaught report is host-only after the hook reported it`() {
        router.route("debug", sentinel("""{"nonce":"n0nce","kind":"pageerror","name":"TypeError","message":"bad"}"""), "", 1)
        assertEquals(AndroidConsoleRouter.Route.HostOnly, router.route("error", "Uncaught TypeError: bad", "https://a/", 2))
        // Consumed once: a second identical report without a hook message is a fallback page error.
        val fallback = router.route("error", "Uncaught TypeError: bad", "https://a/", 2)
        assertEquals(AndroidConsoleRouter.Route.Error(PageError("TypeError", "bad", null, SourceLocation("https://a/", 2, 0)), internal = false), fallback)
    }

    @Test
    fun `uncaught reports without a hook become page errors, rejections included`() {
        val rejection = assertIs<AndroidConsoleRouter.Route.Error>(router.route("error", "Uncaught (in promise) Error: nope", "", 0))
        assertEquals("Error", rejection.error.name)
        assertEquals("nope", rejection.error.message)
        val plain = assertIs<AndroidConsoleRouter.Route.Error>(router.route("error", "Uncaught plain value", "", 0))
        assertEquals(PageError("", "plain value", null, SourceLocation("", 0, 0)), plain.error)
    }

    @Test
    fun `stale hook expectations expire`() {
        router.route("debug", sentinel("""{"nonce":"n0nce","kind":"pageerror","name":"Error","message":"late"}"""), "", 1)
        now += 10_000
        assertIs<AndroidConsoleRouter.Route.Error>(router.route("error", "Uncaught Error: late", "", 0))
    }
}
