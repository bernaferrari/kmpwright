package com.bernaferrari.kmpwright.core

import com.bernaferrari.kmpwright.core.ios.toByteArrayPinned
import com.bernaferrari.kmpwright.core.ios.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class)
internal actual fun fileWrite(path: String, content: String) {
    val fileManager = NSFileManager.defaultManager
    val dir = path.substringBeforeLast("/", missingDelimiterValue = "")
    if (dir.isNotEmpty() && !fileManager.fileExistsAtPath(dir)) {
        fileManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    }
    val data = content.encodeToByteArray().toNSData()
    check(data.writeToFile(path, atomically = true)) { "Could not write state file: $path" }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun fileRead(path: String): String? {
    val fileManager = NSFileManager.defaultManager
    if (!fileManager.fileExistsAtPath(path)) return null
    return try {
        val data = fileManager.contentsAtPath(path) ?: return null
        data.toByteArrayPinned().decodeToString()
    } catch (_: Exception) {
        null
    }
}

internal actual fun fileExists(path: String): Boolean {
    return NSFileManager.defaultManager.fileExistsAtPath(path)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun fileDelete(path: String): Boolean {
    return NSFileManager.defaultManager.removeItemAtPath(path, error = null)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun fileList(dir: String): List<String> {
    val fileManager = NSFileManager.defaultManager
    if (!fileManager.fileExistsAtPath(dir)) return emptyList()
    val contents = fileManager.contentsOfDirectoryAtPath(dir, error = null) ?: return emptyList()
    return contents.mapNotNull { it as? String }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun mkdirs(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(
        path, withIntermediateDirectories = true, attributes = null, error = null
    )
}

internal actual fun platformHomeDir(): String = NSHomeDirectory()
