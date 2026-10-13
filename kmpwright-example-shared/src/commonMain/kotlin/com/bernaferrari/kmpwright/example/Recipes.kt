package com.bernaferrari.kmpwright.example

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.ui.graphics.vector.ImageVector
import com.bernaferrari.kmpwright.core.SnapshotOptions
import com.bernaferrari.kmpwright.core.KmpwrightException
import com.bernaferrari.kmpwright.core.Page
import com.bernaferrari.kmpwright.core.StorageState
import com.bernaferrari.kmpwright.core.ScreenshotOptions
import com.bernaferrari.kmpwright.core.ScreenshotResult
import com.bernaferrari.kmpwright.core.SetContentOptions
import com.bernaferrari.kmpwright.core.TypeOptions
import com.bernaferrari.kmpwright.core.check
import com.bernaferrari.kmpwright.core.click
import com.bernaferrari.kmpwright.core.expect
import com.bernaferrari.kmpwright.core.fill
import com.bernaferrari.kmpwright.core.runCommand
import com.bernaferrari.kmpwright.core.selectOption

/** The session captured by the "state" recipe between its steps. */
private var savedSession: StorageState? = null

/** What a step reports back to the timeline. */
class StepOutput(val text: String? = null, val image: ScreenshotResult? = null)

class RecipeStep(val label: String, val run: suspend (Page) -> StepOutput?)

class Recipe(
    val id: String,
    val title: String,
    val summary: String,
    val icon: ImageVector,
    /** The code shown on screen. Keep it identical to what [steps] execute. */
    val code: String,
    val steps: List<RecipeStep>,
)

private fun step(label: String, run: suspend (Page) -> Any?) = RecipeStep(label) { page ->
    when (val value = run(page)) {
        null, Unit -> null
        is StepOutput -> value
        is ScreenshotResult -> StepOutput(image = value)
        else -> StepOutput(value.toString())
    }
}

private suspend fun Page.load(html: String) = setContent(html, SetContentOptions())

internal val recipes: List<Recipe> = listOf(
    Recipe(
        id = "agent",
        title = "Agent loop",
        summary = "Snapshot, pick a ref, act — the agent-browser workflow.",
        icon = Icons.Default.AutoAwesome,
        code = """
            page.runCommand("snapshot -i")
            // @e1 [input type="search"] placeholder="Search"   ← refs come from the snapshot
            page.runCommand("fill @e1 snapshot")
            page.runCommand("press Enter @e1")
            page.runCommand("get text #results")
        """.trimIndent(),
        steps = listOf(
            step("Load the playground") { it.load(DemoSite.playground) },
            step("snapshot -i") { page ->
                page.runCommand("snapshot -i").output.lines().take(6).joinToString("\n") + "\n…"
            },
            step("fill the search box by ref") { page ->
                val snapshot = page.snapshot(SnapshotOptions(interactive = true))
                val search = snapshot.refs.values.firstOrNull { it.name.equals("Search", ignoreCase = true) && it.role != "button" }
                    ?: throw KmpwrightException("No search box in the snapshot")
                page.runCommand("fill ${search.ref} snapshot")
                page.runCommand("press Enter ${search.ref}").output
            },
            step("get text #results") { page -> page.runCommand("get text #results").output },
        ),
    ),
    Recipe(
        id = "quick-start",
        title = "Click and assert",
        summary = "Locators, clicks and web-first assertions.",
        icon = Icons.Default.TouchApp,
        code = """
            page.setContent(html)
            val plus = page.locator("#increment")
            plus.click()
            plus.click()
            expect(page.locator("#count")).toHaveText("2")
        """.trimIndent(),
        steps = listOf(
            step("Load the playground") { it.load(DemoSite.playground) },
            step("Click “+” twice") { page ->
                val plus = page.locator("#increment")
                plus.click(); plus.click()
            },
            step("Expect the count to be 2") { page ->
                expect(page.locator("#count")).toHaveText("2", 5_000)
                "count = 2 ✓"
            },
        ),
    ),
    Recipe(
        id = "form",
        title = "Fill a form",
        summary = "Labels, selects, checkboxes and waiting for the result.",
        icon = Icons.Default.EditNote,
        code = """
            page.fill("#full-name", "Ada Lovelace")
            page.fill("#email", "ada@example.com")
            page.selectOption("#plan", "pro")
            page.check("#terms")
            page.click("#signup button[type=submit]")
            page.waitForText("Welcome, Ada Lovelace")
        """.trimIndent(),
        steps = listOf(
            step("Load the playground") { it.load(DemoSite.playground) },
            step("Fill name and email") { page ->
                page.fill("#full-name", "Ada Lovelace")
                page.fill("#email", "ada@example.com")
            },
            step("Choose the Pro plan") { page -> page.selectOption("#plan", "pro") },
            step("Accept the terms") { page -> page.check("#terms") },
            step("Submit") { page -> page.click("#signup button[type=submit]") },
            step("Wait for the welcome message") { page ->
                page.waitForText("Welcome, Ada Lovelace", 5_000)
                page.locator("#signup-status").innerText()
            },
        ),
    ),
    Recipe(
        id = "keyboard",
        title = "Type like a person",
        summary = "Real key events with a delay between keystrokes.",
        icon = Icons.Default.Keyboard,
        code = """
            page.locator("#query").click()
            page.keyboard.type("network", TypeOptions(delayMs = 70))
            page.keyboard.press("Enter")
            expect(page.locator("#results li")).toHaveCount(1)
        """.trimIndent(),
        steps = listOf(
            step("Load the playground") { it.load(DemoSite.playground) },
            step("Focus the search box") { page -> page.locator("#query").click() },
            step("Type “network”") { page -> page.keyboard.type("network", TypeOptions(delayMs = 70)) },
            step("Press Enter") { page -> page.keyboard.press("Enter") },
            step("Expect one result") { page ->
                expect(page.locator("#results li")).toHaveCount(1, 5_000)
                page.locator("#results li").first().innerText()
            },
        ),
    ),
    Recipe(
        id = "screenshot",
        title = "Full-page screenshot",
        summary = "Capture beyond the viewport, stitched into one image.",
        icon = Icons.Default.PhotoCamera,
        code = """
            page.setContent(article)
            val shot = page.screenshot(ScreenshotOptions(fullPage = true))
            // shot.bytes is a PNG: save it, diff it, or send it to a model
        """.trimIndent(),
        steps = listOf(
            step("Load a long article") { it.load(DemoSite.article) },
            step("Capture the full page") { page ->
                val shot = page.screenshot(ScreenshotOptions(fullPage = true))
                StepOutput("${shot.width} × ${shot.height} px · ${shot.bytes.size / 1024} KB", shot)
            },
        ),
    ),
    Recipe(
        id = "network",
        title = "Mock the network",
        summary = "Answer API calls from Kotlin with page.route.",
        icon = Icons.Default.CloudSync,
        code = """
            page.route("**/quote") {
                it.fulfill(
                    contentType = "application/json",
                    headers = mapOf("Access-Control-Allow-Origin" to "*"),
                    body = ""${'"'}{"text":"Talk is cheap…","author":"Linus"}""${'"'},
                )
            }
            page.setContent(quotePage)
            expect(page.locator("#quote")).toContainText("Talk is cheap")
        """.trimIndent(),
        steps = listOf(
            step("Route **/quote to a fake response") { page ->
                page.unroute("**/quote")
                page.route("**/quote") { route ->
                    route.fulfill(
                        contentType = "application/json",
                        headers = mapOf("Access-Control-Allow-Origin" to "*"),
                        body = """{"text":"Talk is cheap. Show me the code.","author":"Linus Torvalds"}""",
                    )
                }
            },
            step("Load a page that fetches it") { it.load(DemoSite.quote) },
            step("Expect the mocked quote") { page ->
                try {
                    expect(page.locator("#quote")).toContainText("Talk is cheap", 5_000)
                    page.locator("#quote").innerText()
                } finally {
                    page.unroute("**/quote")
                }
            },
        ),
    ),
    Recipe(
        id = "state",
        title = "Save and restore a session",
        summary = "Cookies and storage, snapshotted and put back.",
        icon = Icons.Default.Save,
        code = """
            page.localStorage.set("theme", "dark")
            val session = page.storageState()
            page.localStorage.clear()
            page.setStorageState(session)
            page.localStorage.get("theme") // "dark"
        """.trimIndent(),
        steps = listOf(
            step("Open a real origin") { page ->
                val result = page.goto("https://example.com")
                if (!result.ok) throw KmpwrightException(result.error ?: "Could not open example.com")
                result.url
            },
            step("Write local storage") { page -> page.localStorage.set("theme", "dark") },
            step("Save the session") { page ->
                savedSession = page.storageState()
                "${savedSession?.cookies?.size ?: 0} cookies, ${savedSession?.localStorage?.size ?: 0} storage entries"
            },
            step("Clear storage") { page ->
                page.localStorage.clear()
                "theme = ${page.localStorage.get("theme")}"
            },
            step("Restore the session") { page ->
                page.setStorageState(savedSession ?: throw KmpwrightException("Save the session first"))
                "theme = ${page.localStorage.get("theme")}"
            },
        ),
    ),
    Recipe(
        id = "read",
        title = "Read for an LLM",
        summary = "Fetch a page as Markdown, no rendering needed.",
        icon = Icons.AutoMirrored.Filled.Article,
        code = """
            val doc = page.read("https://example.com")
            doc.content // Markdown, ready for a prompt
        """.trimIndent(),
        steps = listOf(
            step("Read example.com as Markdown") { page ->
                val doc = page.read("https://example.com")
                doc.content.trim().take(600)
            },
        ),
    ),
    Recipe(
        id = "audit",
        title = "Accessibility audit",
        summary = "Run axe-core inside the page and list violations.",
        icon = Icons.Default.Accessibility,
        code = """
            val report = page.accessibilityAudit()
            report.violations.forEach { println("${'$'}{it.impact}: ${'$'}{it.help}") }
        """.trimIndent(),
        steps = listOf(
            step("Load the playground") { it.load(DemoSite.playground) },
            step("Run axe-core") { page ->
                val report = page.accessibilityAudit()
                buildString {
                    append("${report.counts.violations} violations · ${report.counts.passes} passes")
                    report.violations.take(4).forEach { append("\n• ${it.impact}: ${it.help}") }
                }
            },
        ),
    ),
)
