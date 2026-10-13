package com.bernaferrari.kmpwright.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * One encrypted file format on every platform: this fixture was produced outside KMPwright (Python
 * hashlib PBKDF2 + OpenSSL AES-256-CBC + HMAC-SHA256, following [ENCRYPTION_FORMAT_PREFIX]'s spec)
 * and must decrypt identically on JVM, Android host and iOS, which also proves their writers agree.
 */
class EncryptedStateGoldenTest {
    private val password = "golden-fixture-password"
    private val fixture = "WW2:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh+Lx62mbReg0c83ItomlhruQxvWQ85SGkhj9ALEL06/uXjv/U+gcaohCwrePFrJzMfe3rkUVPpo8CC/CoD4/73SJT//raIq+dn2xp94VFfM4ALsJpL9kG1RC6KH0G1TkMIRUqkiWs6UmyJPPmFJbcXe62GlPaH1wwJxglH6TL8Pnp0wSxDSJ6vS6pQ2x3de8n2nw7WWcEEFwQZmez2aOhKFlV7N7LkDnIkAaOeDJ+VCDoFE+4JaxGMdCfxe+UvIJzTZO3smEhfos6n5DumKm7pLhARZbxDeD6URM75iYFB7oGf3NFZcggOaf8KX8OhgV/nlRsNgUwr0ctv3wpeWw3Nec0bWKi2My6j+TZbc8MvNeHDbADw8OzYhq+nHausH0+w4wXhSj7fc8VEchz5s+08Gjl8HxoXxeKtdkghpKp8BHo+GiHfSfYL5s2/LUVaAEeHluy62boHm0NPTc/QbZe5yPVyEG/v1sxkuRl2IMxLFBRfT/HG6vXB/ipn8y/7R/1Xpsb0zIuQ0lTKsn7QL2rvFqDY5W7D0wUnbCwrSdB0B5Ut9J4jP7vsvXK1Qkvpd1BVH6JfpNtcoZG51/ksKdTeimuOmsmEeDgH5iDOAncNfcw=="

    @Test
    fun goldenStorageStateDecryptsOnEveryPlatform() {
        val json = SessionManager.decryptOrThrow(fixture, password, "golden fixture")
        val state = requireNotNull(SessionManager.deserialize(json))
        assertEquals("https://example.com/app", state.url)
        assertEquals("dark", state.localStorage["theme"])
        assertEquals("1", state.sessionStorage["tab"])
        val cookie = state.cookies.single()
        assertEquals("sid", cookie.name)
        assertEquals("/a/b", cookie.path)
        assertTrue(cookie.hostOnly && cookie.secure && cookie.httpOnly)
    }

    @Test
    fun wrongPasswordAndForeignFormatsFailLoudly() {
        val wrong = assertFailsWith<KmpwrightException> { SessionManager.decryptOrThrow(fixture, "nope", "golden fixture") }
        assertTrue(wrong.message.orEmpty().contains("wrong password"))
        val legacy = assertFailsWith<KmpwrightException> { SessionManager.decryptOrThrow("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", password, "old file") }
        assertTrue(legacy.message.orEmpty().contains(ENCRYPTION_FORMAT_PREFIX))
    }

    @Test
    fun platformWriterProducesTheSharedFormat() {
        val encrypted = SessionManager.encrypt("{\"name\":\"x\"}", password)
        assertTrue(encrypted.startsWith(ENCRYPTION_FORMAT_PREFIX))
        assertEquals("{\"name\":\"x\"}", SessionManager.decryptOrThrow(encrypted, password, "round trip"))
    }
}
