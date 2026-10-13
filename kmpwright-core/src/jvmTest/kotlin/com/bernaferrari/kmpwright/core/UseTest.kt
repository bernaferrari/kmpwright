package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** `use {}` closes its receiver like `Closeable.use` and Ktor's `HttpClient.use`. */
class UseTest {

    private class FakeLaunchedBrowser(private val closeFailure: Throwable? = null) : LaunchedBrowser {
        var closes = 0
        var closedWhileCancelled = false
        override val browser: Browser get() = throw UnsupportedOperationException("not needed")
        override suspend fun close() {
            closes++
            // A suspending close must still run (NonCancellable) when the caller was cancelled.
            yield()
            delay(1)
            closedWhileCancelled = true
            closeFailure?.let { throw it }
        }
    }

    @Test
    fun `use returns the block result and closes`() = runBlocking {
        val launched = FakeLaunchedBrowser()
        val result = launched.use { "done" }
        assertEquals("done", result)
        assertEquals(1, launched.closes)
    }

    @Test
    fun `use closes when the block throws and keeps the original failure`() = runBlocking {
        val launched = FakeLaunchedBrowser(closeFailure = IllegalStateException("close failed"))
        val failure = IllegalArgumentException("block failed")
        try {
            launched.use { throw failure }
            fail("use must rethrow the block failure")
        } catch (error: IllegalArgumentException) {
            assertSame(failure, error)
            assertEquals("close failed", error.suppressed.single().message)
        }
        assertEquals(1, launched.closes)
    }

    @Test
    fun `use throws the close failure when the block succeeded`() = runBlocking {
        val launched = FakeLaunchedBrowser(closeFailure = IllegalStateException("close failed"))
        try {
            launched.use { }
            fail("use must surface the close failure")
        } catch (error: IllegalStateException) {
            assertEquals("close failed", error.message)
        }
    }

    @Test
    fun `use closes when the caller is cancelled`() = runBlocking {
        val launched = FakeLaunchedBrowser()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            launched.use { awaitCancellation() }
        }
        job.cancelAndJoin()
        assertEquals(1, launched.closes)
        assertTrue("close must finish even though the caller was cancelled", launched.closedWhileCancelled)
    }

    @Test
    fun `page use closes the page`() = runBlocking {
        val page = TestPage()
        page.use { assertSame(page, it) }
        try {
            page.goto("https://example.com")
            fail("A closed page must reject navigation")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }
}
