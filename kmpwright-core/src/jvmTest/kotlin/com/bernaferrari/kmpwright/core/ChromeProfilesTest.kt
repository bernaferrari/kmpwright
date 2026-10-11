package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ChromeProfilesTest {
    @Test fun `profile matching is deterministic and rejects ambiguous display names`() = runBlocking<Unit> {
        val source = Files.createTempDirectory("kmpwright-profile-source-")
        try {
            Files.writeString(source.resolve("Local State"), """{"profile":{"info_cache":{"Profile 2":{"name":"Work"},"Default":{"name":"Work"},"../escape":{"name":"Invalid"}}}}""")
            assertEquals(listOf("Default", "Profile 2"), ChromeProfiles.list(source.toString()).map { it.directory })
            assertEquals("Default", ChromeProfiles.resolve(ChromeProfileSource(source.toString(), "default")).directory)
            try { ChromeProfiles.resolve(ChromeProfileSource(source.toString(), "work")); fail("Ambiguous profile accepted") }
            catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("Ambiguous")) }
        } finally { source.toFile().deleteRecursively() }
    }
    @Test fun `snapshot preserves source data and skips caches locks and symbolic links`() = runBlocking<Unit> {
        val source = Files.createTempDirectory("kmpwright-profile-source-")
        val destination = Files.createTempDirectory("kmpwright-profile-copy-")
        try {
            Files.writeString(source.resolve("Local State"), """{"profile":{"info_cache":{"Default":{"name":"Personal"}}}}""")
            val profile = Files.createDirectories(source.resolve("Default"))
            Files.writeString(profile.resolve("Cookies"), "authentication fixture")
            Files.writeString(profile.resolve("SingletonLock"), "host owned")
            Files.createDirectories(profile.resolve("Cache")); Files.writeString(profile.resolve("Cache/large"), "skip")
            Files.createSymbolicLink(profile.resolve("outside"), source)
            assertEquals("Default", copyChromeProfile(ChromeProfileSource(source.toString(), "personal"), destination))
            assertEquals("authentication fixture", Files.readString(destination.resolve("Default/Cookies")))
            assertFalse(Files.exists(destination.resolve("Default/Cache")))
            assertFalse(Files.exists(destination.resolve("Default/SingletonLock")))
            assertFalse(Files.exists(destination.resolve("Default/outside")))
            Files.writeString(destination.resolve("Default/Cookies"), "changed copy")
            assertEquals("authentication fixture", Files.readString(profile.resolve("Cookies")))
        } finally { destination.toFile().deleteRecursively(); source.toFile().deleteRecursively() }
    }
    @Test fun `malformed local state returns no profiles and missing selection fails explicitly`() = runBlocking<Unit> {
        val source = Files.createTempDirectory("kmpwright-profile-source-")
        try {
            Files.writeString(source.resolve("Local State"), "malformed")
            assertTrue(ChromeProfiles.list(source.toString()).isEmpty())
            try { ChromeProfiles.resolve(ChromeProfileSource(source.toString(), "Default")); fail("Missing profile accepted") }
            catch (_: IllegalArgumentException) { }
        } finally { source.toFile().deleteRecursively() }
    }
}
