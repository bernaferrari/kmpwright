package com.bernaferrari.kmpwright.core

import com.bernaferrari.kmpwright.core.ios.toByteArrayPinned
import com.bernaferrari.kmpwright.core.ios.toNSData
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSDataWritingFileProtectionComplete
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.writeToFile

/**
 * Session state and auth profiles contain cookies and storage tokens, so
 * files are written atomically with complete data protection: they are
 * unreadable while the device is locked. Directories get the same class so
 * files created inside inherit it.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun fileWrite(path: String, content: String) {
    val dir = path.substringBeforeLast("/", missingDelimiterValue = "")
    if (dir.isNotEmpty()) mkdirs(dir)
    val data = content.encodeToByteArray().toNSData()
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val written = data.writeToFile(
            path,
            options = NSDataWritingAtomic or NSDataWritingFileProtectionComplete,
            error = error.ptr
        )
        if (!written) {
            throw KmpwrightException("Could not write state file $path: ${error.value?.localizedDescription ?: "unknown error"}")
        }
    }
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

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun mkdirs(path: String) {
    val fileManager = NSFileManager.defaultManager
    if (fileManager.fileExistsAtPath(path)) return
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val created = fileManager.createDirectoryAtPath(
            path,
            withIntermediateDirectories = true,
            attributes = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionComplete),
            error = error.ptr
        )
        // Another writer may have created it concurrently.
        if (!created && !fileManager.fileExistsAtPath(path)) {
            throw KmpwrightException("Could not create directory $path: ${error.value?.localizedDescription ?: "unknown error"}")
        }
    }
}

/**
 * The app's Application Support directory. Unlike the container root
 * (`NSHomeDirectory()`), it is writable on devices, private to the app, and
 * not exposed through the Files app.
 */
internal actual fun platformHomeDir(): String =
    NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String
        ?: "${NSHomeDirectory()}/Library/Application Support"
