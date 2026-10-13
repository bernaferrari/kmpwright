package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith

class SafetyContractTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setup() = Dispatchers.setMain(Dispatchers.Unconfined)

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `policy protects non-locator page actions`() = runBlocking {
        val page = TestPage(MockWebViewController())
        page.setActionPolicy(
            ActionPolicy(
                deniedActions = setOf(
                    ActionType.Navigate,
                    ActionType.Evaluate,
                    ActionType.SetCookie,
                    ActionType.SetContent,
                    ActionType.AddScriptTag,
                    ActionType.AddStyleTag,
                    ActionType.Screenshot
                )
            )
        )
        assertFailsWith<KmpwrightException> { page.goto("https://example.com") }
        assertFailsWith<KmpwrightException> { page.evaluate("1 + 1") }
        assertFailsWith<KmpwrightException> { page.addCookies(listOf(Cookie("a", "b", "example.com"))) }
        assertFailsWith<KmpwrightException> { page.setContent("<p>unsafe</p>") }
        assertFailsWith<KmpwrightException> { page.addScriptTag(content = "1") }
        assertFailsWith<KmpwrightException> { page.addStyleTag(content = "body{}") }
        assertFailsWith<KmpwrightException> { page.screenshot() }
        Unit
    }

    @Test
    fun `configuration enforces origins evaluation and extraction`() = runBlocking {
        val page = TestPage(
            MockWebViewController(),
            config = KmpwrightConfig(
                allowedOriginRules = setOf("https://trusted.example"),
                allowEval = false,
                allowDomExtraction = false
            )
        )
        assertFailsWith<KmpwrightException> { page.goto("https://untrusted.example") }
        assertFailsWith<KmpwrightException> { page.evaluate("1 + 1") }
        assertFailsWith<KmpwrightException> { page.content() }
        assertFailsWith<KmpwrightException> { page.snapshot() }
        Unit
    }

    @Test
    fun `encryption requires an explicit password`() {
        assertFailsWith<IllegalArgumentException> { SessionManager.encrypt("secret") }
    }
}
