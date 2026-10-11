package com.bernaferrari.kmpwright.core

import java.io.File

internal actual fun fileWrite(path: String, content: String) {
    val destination = java.nio.file.Paths.get(path).toAbsolutePath().normalize()
    java.nio.file.Files.createDirectories(destination.parent)
    val temporary = java.nio.file.Files.createTempFile(destination.parent, ".kmpwright-state-", ".tmp")
    try {
        java.nio.file.Files.write(temporary, content.toByteArray(Charsets.UTF_8))
        try { java.nio.file.Files.move(temporary, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    } finally { java.nio.file.Files.deleteIfExists(temporary) }
}

internal actual fun fileRead(path: String): String? {
    return try {
        File(path).readText(Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }
}

internal actual fun fileExists(path: String): Boolean = File(path).exists()

internal actual fun fileDelete(path: String): Boolean = File(path).delete()

internal actual fun fileList(dir: String): List<String> {
    val file = File(dir)
    return if (file.exists() && file.isDirectory) {
        file.listFiles()?.map { it.name } ?: emptyList()
    } else {
        emptyList()
    }
}

internal actual fun mkdirs(path: String) {
    File(path).mkdirs()
}

internal actual fun platformHomeDir(): String = System.getProperty("user.home") ?: "/data/local/tmp"
