package com.bernaferrari.kmpwright.core

/** Milliseconds since the Unix epoch on the current platform. */
public expect fun currentTimeMillis(): Long
internal expect fun secureRandomBytes(size: Int): ByteArray

/**
 * The modifier `ControlOrMeta` resolves to (Playwright semantics): `Meta` where the browser runs on an
 * Apple platform (macOS Chromium, iOS WebKit), `Control` elsewhere (Android, Linux, Windows).
 */
internal expect fun controlOrMetaModifier(): String
