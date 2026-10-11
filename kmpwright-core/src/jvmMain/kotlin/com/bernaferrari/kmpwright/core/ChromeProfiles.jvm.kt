package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

actual object ChromeProfiles {
    actual suspend fun discoverUserDataDirectory(): String? = withContext(Dispatchers.IO) {
        val home = System.getProperty("user.home")
        val candidates = when {
            System.getProperty("os.name").startsWith("Mac") -> listOf("$home/Library/Application Support/Google/Chrome", "$home/Library/Application Support/Chromium")
            System.getProperty("os.name").startsWith("Windows") -> listOfNotNull(System.getenv("LOCALAPPDATA")?.let { "$it/Google/Chrome/User Data" }, System.getenv("LOCALAPPDATA")?.let { "$it/Chromium/User Data" })
            else -> listOf("${System.getenv("XDG_CONFIG_HOME") ?: "$home/.config"}/google-chrome", "${System.getenv("XDG_CONFIG_HOME") ?: "$home/.config"}/chromium")
        }
        candidates.firstOrNull { Files.isRegularFile(Path.of(it).resolve("Local State"), LinkOption.NOFOLLOW_LINKS) }
    }
    actual suspend fun list(userDataDirectory: String): List<ChromeProfile> = withContext(Dispatchers.IO) {
        val state = Path.of(userDataDirectory).resolve("Local State")
        if (!Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS)) return@withContext emptyList()
        require(Files.size(state) <= 16 * 1024 * 1024) { "Chrome Local State exceeds 16 MiB" }
        val parsed = try { Json.parseToJsonElement(Files.readString(state)) as? JsonObject } catch (_: IllegalArgumentException) { null }
        val cache = ((parsed?.get("profile") as? JsonObject)?.get("info_cache") as? JsonObject) ?: return@withContext emptyList()
        cache.entries.filter { safeProfileName(it.key) }.map { (directory, info) ->
            ChromeProfile(directory, ((info as? JsonObject)?.get("name") as? JsonPrimitive)?.content ?: directory)
        }.sortedBy { it.directory }
    }
    actual suspend fun resolve(source: ChromeProfileSource): ChromeProfile {
        val profiles = list(source.userDataDirectory)
        profiles.firstOrNull { it.directory == source.profile }?.let { return it }
        val names = profiles.filter { it.name.equals(source.profile, ignoreCase = true) }
        require(names.size <= 1) { "Ambiguous Chrome profile name; select its directory instead" }
        names.firstOrNull()?.let { return it }
        profiles.firstOrNull { it.directory.equals(source.profile, ignoreCase = true) }?.let { return it }
        throw IllegalArgumentException("Chrome profile '${source.profile}' not found; available directories: ${profiles.joinToString { it.directory }}")
    }
}

private fun safeProfileName(name: String) = name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }
private val excludedProfileDirectories = setOf("Cache", "Code Cache", "GPUCache", "Service Worker", "blob_storage", "File System", "GCM Store", "optimization_guide", "ShaderCache", "component_crx_cache")

/** Fail incomplete copies rather than silently launching without the requested authentication state. */
internal suspend fun copyChromeProfile(source: ChromeProfileSource, destination: Path): String = withContext(Dispatchers.IO) {
    val profile = ChromeProfiles.resolve(source)
    val root = Path.of(source.userDataDirectory).toAbsolutePath().normalize()
    val from = root.resolve(profile.directory)
    require(Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) { "Chrome profile directory is missing or is a symbolic link" }
    val context = currentCoroutineContext()
    var files = 0
    var bytes = 0L
    val started = System.nanoTime()
    Files.copy(root.resolve("Local State"), destination.resolve("Local State"))
    Files.walkFileTree(from, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            context.ensureActive()
            require(System.nanoTime() - started < 30_000_000_000L) { "Chrome profile snapshot exceeded 30 seconds" }
            if (dir != from && dir.fileName.toString() in excludedProfileDirectories) return FileVisitResult.SKIP_SUBTREE
            Files.createDirectories(destination.resolve(profile.directory).resolve(from.relativize(dir)))
            return FileVisitResult.CONTINUE
        }
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            context.ensureActive()
            if (!attrs.isRegularFile || file.fileName.toString().startsWith("Singleton")) return FileVisitResult.CONTINUE
            files++; bytes += attrs.size()
            require(files <= 20_000 && bytes <= 2L * 1024 * 1024 * 1024) { "Chrome profile snapshot exceeds 20,000 files or 2 GiB" }
            require(System.nanoTime() - started < 30_000_000_000L) { "Chrome profile snapshot exceeded 30 seconds" }
            Files.copy(file, destination.resolve(profile.directory).resolve(from.relativize(file)))
            return FileVisitResult.CONTINUE
        }
    })
    profile.directory
}
