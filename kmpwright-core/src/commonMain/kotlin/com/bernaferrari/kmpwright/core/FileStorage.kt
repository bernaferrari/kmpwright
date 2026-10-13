package com.bernaferrari.kmpwright.core

/**
 * Platform-specific file I/O for persistent state and auth profiles.
 * expect/actual declarations for KMP support.
 */
internal expect fun fileWrite(path: String, content: String)
internal expect fun fileRead(path: String): String?
internal expect fun fileExists(path: String): Boolean
internal expect fun fileDelete(path: String): Boolean
internal expect fun fileList(dir: String): List<String>
internal expect fun mkdirs(path: String)

/**
 * Returns the user home directory. Platform-specific.
 */
internal expect fun platformHomeDir(): String

/** Dispatcher for blocking file I/O (`Dispatchers.IO`; the browser has no blocking files). */
internal expect val fileDispatcher: kotlinx.coroutines.CoroutineDispatcher
