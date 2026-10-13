package com.bernaferrari.kmpwright.core

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Contract tests for the pinned, public agent-browser compatibility boundary.
 *
 * These tests intentionally do not execute or inspect agent-browser internals. The
 * shell verifier checks the untracked upstream boundary; this test checks the manifest and the compiled
 * Kmpwright public API that it names.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentBrowserCompatibilityTest {

    @Before
    fun setUpMainDispatcher() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun `manifest is versioned and pinned to an upstream comparison commit`() {
        val root = manifestPath()
        val manifest = readManifest(root)
        assertEquals(1, manifest["manifestVersion"]!!.jsonPrimitive.content.toInt())
        val upstream = manifest["upstream"]!!.jsonObject
        assertEquals(".upstream/agent-browser", upstream["localCheckoutPath"]!!.jsonPrimitive.content)
        val commit = upstream["comparedCommit"]!!.jsonPrimitive.content
        assertTrue(commit.matches(Regex("[0-9a-f]{40}")))
        assertTrue(upstream["workingTreeIsIgnored"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf("README.md", "agent-browser.schema.json"), upstream["publicSources"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `every manifest API reference resolves against compiled public classes`() {
        val commands = readManifest(manifestPath())["commands"]!!.jsonArray
        val options = readManifest(manifestPath())["globalOptions"]!!.jsonArray
        (commands + options).forEach { raw ->
            val entry = raw.jsonObject
            assertEntry(entry, isCommand = entry["command"] != null)
        }
    }

    private fun assertEntry(entry: JsonObject, isCommand: Boolean) {
        val name = (entry[if (isCommand) "command" else "option"] ?: entry["name"])!!
            .jsonPrimitive.content
        val status = entry["status"]!!.jsonPrimitive.content
        assertTrue("unknown status $status", status in setOf("supported", "partial", "unsupported"))
        assertFalse("blank semantics for $name", isCommand && entry["semantics"]?.jsonPrimitive?.content.orEmpty().isBlank())
        val refs = entry["apiRefs"]?.jsonArray ?: JsonArray(emptyList())
        if (status != "unsupported") {
            assertTrue("$name needs an API reference", refs.isNotEmpty())
        }
        refs.forEach { refRaw ->
            val ref = refRaw.jsonObject
            val owner = Class.forName(ref["owner"]!!.jsonPrimitive.content)
            val member = ref["member"]!!.jsonPrimitive.content
            // Refs name Kotlin members (`title`, `webMcp`, `all`), never JVM getters (`getTitle`).
            val kind = owner.getAnnotation(Metadata::class.java)?.kind
            val names = if (kind == 1) owner.kotlin.members.map { it.name } else owner.methods.map { it.name }
            assertTrue("${owner.name}.$member is not a Kotlin member name", member in names)
        }
        entry["options"]?.jsonArray?.forEach { optionRaw ->
            assertEntry(optionRaw.jsonObject, isCommand = false)
        }
    }

    @Test
    fun `core command boundary performs navigation and locator interaction`() = testBlocking {
        val controller = MockWebViewController()
        val page = TestPage(controller)

        val navigation = page.goto("https://example.test")
        assertEquals("https://example.test", navigation.url)
        assertEquals("https://example.test", page.url.value)

        page.locator("button.submit").click()
        assertTrue(controller.executedScripts.any { it.contains("H.perform(el, 'click'") })

        val boundary = page.createContentBoundary()
        val bounded = boundary.wrap("untrusted page text")
        assertTrue(boundary.isBounded(bounded))
        assertEquals("untrusted page text", boundary.unwrap(bounded))
    }

    private fun readManifest(path: Path): JsonObject = Json.parseToJsonElement(Files.readString(path)).jsonObject

    private fun manifestPath(): Path {
        var directory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        repeat(6) {
            val candidate = directory.resolve("compatibility/agent-browser-compatibility.json")
            if (Files.isRegularFile(candidate)) return candidate
            directory = directory.parent ?: return@repeat
        }
        error("Could not locate compatibility/agent-browser-compatibility.json from ${System.getProperty("user.dir")}")
    }
}
