package com.bernaferrari.kmpwright.core

import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/** How `llms.txt` is used by [Page.read]. */
@Serializable
public enum class LlmsReadMode { Index, Full }

/** Readable content fetched by [Page.read]. */
@Serializable
public data class ReadResult(val url: String, val finalUrl: String, val status: Int?, val contentType: String,
    val source: String, val truncated: Boolean, val content: String, val headers: Map<String, String> = emptyMap())

/** HTTP reading works without launching a browser. It does not share browser cookies. */
public class HttpReader(private val config: KmpwrightConfig = KmpwrightConfig()) {
    /**
     * Fetches [url] and returns readable text.
     *
     * - [raw] returns the body as fetched; otherwise readable text is extracted, preferring a Markdown
     *   representation when the site offers one. [requireMarkdown] fails unless the result is `text/markdown`.
     * - [llms] reads the site's `llms.txt` index or `llms-full.txt` instead of [url].
     * - [outline] returns only the heading outline; [filter] keeps only the matching sections.
     * - [headers] are sent with every request; [allowedDomains] restricts the hosts that may be
     *   contacted (redirects included); at most [maxBytes] (1 byte..16 MiB) of body are read.
     * - [caCertificatePath] is a PEM file trusted in addition to the system roots (or instead of them
     *   with [useSystemCa] = false); it works on JVM, Android and iOS.
     * - [timeoutMs] null uses [TimeoutConfig.default] of the reader's [KmpwrightConfig].
     *
     * @throws IllegalArgumentException when [timeoutMs] is not positive, [maxBytes] is out of range or
     *   [headers] repeats a name.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun read(
        url: String,
        raw: Boolean = false,
        requireMarkdown: Boolean = false,
        llms: LlmsReadMode? = null,
        outline: Boolean = false,
        filter: String? = null,
        headers: Map<String, String> = emptyMap(),
        allowedDomains: Set<String> = emptySet(),
        maxBytes: Int = 2 * 1024 * 1024,
        caCertificatePath: String? = null,
        useSystemCa: Boolean = true,
        timeoutMs: Long? = null
    ): ReadResult = readChecked(url, ReadOptions(raw, requireMarkdown, llms, outline, filter, timeoutMs, headers,
        allowedDomains, maxBytes, caCertificatePath, useSystemCa)) {}

    internal suspend fun readChecked(rawUrl: String, options: ReadOptions, inheritedCheck: suspend (String) -> Unit): ReadResult {
        if (!config.allowDomExtraction) throw KmpwrightException("HTTP content extraction is disabled by KmpwrightConfig.allowDomExtraction")
        validateOptions(options)
        val timeoutMs = config.defaultTimeout(options.timeoutMs)
        val target = normalizeReaderUrl(rawUrl)
        val client = withContext(Dispatchers.Default) { readerHttpClient(timeoutMs, options.caCertificatePath, options.useSystemCa) }
        val transport = ReaderTransport(client, options.headers, options.maxBytes) { candidate ->
            if (config.allowedOriginRules.isNotEmpty() && config.allowedOriginRules.none { originRuleMatches(it, candidate) }) {
                throw KmpwrightException("HTTP read origin is not allowed: $candidate")
            }
            if (!DomainFilter(options.allowedDomains).isAllowed(Url(candidate).host)) {
                throw KmpwrightException("HTTP read domain is not allowed: ${Url(candidate).host}")
            }
            inheritedCheck(candidate)
        }
        try {
            return withTimeout(timeoutMs) { readDocument(target, options, transport) }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw KmpwrightTimeoutException("Timeout reading HTTP content")
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (isReaderTransportTimeout(error)) throw KmpwrightTimeoutException("Timeout reading HTTP content")
            throw error
        } finally { client.close() }
    }

    private fun validateOptions(options: ReadOptions) {
        require(options.timeoutMs == null || options.timeoutMs > 0) { "Read timeout must be positive" }
        require(options.maxBytes in 1..16 * 1024 * 1024) { "Read body budget must be between 1 byte and 16 MiB" }
        require(options.headers.keys.map(String::lowercase).distinct().size == options.headers.size) { "Duplicate HTTP header names" }
    }

    private suspend fun readDocument(target: String, options: ReadOptions, transport: ReaderTransport): ReadResult {
        if (options.llms != null) {
            val filename = if (options.llms == LlmsReadMode.Index) "llms.txt" else "llms-full.txt"
            val fetch = findLlms(target, filename, options, transport)
                ?: throw KmpwrightException("$filename was not found at an ancestor URL")
            val content = if (options.llms == LlmsReadMode.Index) llmsIndex(fetch, options.filter)
                else options.filter?.let { ReaderContent.filter(fetch.body, it, "No matching llms-full.txt sections") } ?: fetch.body
            return result(target, fetch, if (options.llms == LlmsReadMode.Index) "llms-index" else "llms-full", content)
        }
        val primary = transport.fetch(target)
        if (primary.successful && (options.raw || primary.markdown() || (!options.requireMarkdown && primary.markdownLike()))) {
            return selected(target, primary, options)
        }
        if (!options.raw && options.headers.keys.none { it.equals("Accept", true) }) {
            val fallback = markdownUrl(target)
            if (fallback != null) {
                val markdown = optionalFetch(transport, fallback)
                if (markdown != null && markdown.successful && (markdown.markdown() || (!options.requireMarkdown && (markdown.markdownLike() || markdown.mediaType == "text/plain")))) {
                    return selected(target, markdown, options, "markdown-url")
                }
            }
            val index = findLlms(target, "llms.txt", options.copy(requireMarkdown = false), transport)
            if (index != null) {
                val matching = matchingLink(llmsLinks(index.body, index.finalUrl), target)
                if (matching != null) {
                    val linked = optionalFetch(transport, matching.url)
                    if (linked != null && linked.successful && (!options.requireMarkdown || linked.markdown())) {
                        return selected(target, linked, options, "llms-link")
                    }
                }
            }
        }
        if (!primary.successful) throw KmpwrightException("HTTP read failed with status ${primary.status}")
        return selected(target, primary, options)
    }

    private fun selected(target: String, fetch: ReaderFetch, options: ReadOptions, source: String? = null): ReadResult {
        if (options.requireMarkdown && !fetch.markdown()) throw KmpwrightException("Expected text/markdown, got ${fetch.mediaType.ifEmpty { "unknown content type" }}")
        val content = when {
            options.raw -> fetch.body
            fetch.mediaType in setOf("text/html", "application/xhtml+xml") -> ReaderContent.html(fetch.body, fetch.finalUrl)
            else -> fetch.body
        }
        val kind = source ?: when {
            options.raw -> "raw"
            fetch.markdownLike() -> "markdown"
            fetch.mediaType in setOf("text/html", "application/xhtml+xml") -> "html"
            else -> "text"
        }
        return result(target, fetch, kind + if (options.outline) "-outline" else if (options.filter != null) "-filtered" else "",
            when { options.outline -> ReaderContent.outline(content, options.filter)
                options.filter != null -> ReaderContent.filter(content, options.filter)
                else -> content })
    }

    private fun result(target: String, fetch: ReaderFetch, source: String, content: String) = ReadResult(
        target, fetch.finalUrl, fetch.status, fetch.contentType, source, fetch.truncated, content, fetch.headers)

    private fun ReaderFetch.markdown(): Boolean = mediaType == "text/markdown"
    private fun ReaderFetch.markdownLike(): Boolean = mediaType in setOf("text/markdown", "text/x-markdown", "application/markdown")

    private suspend fun optionalFetch(transport: ReaderTransport, url: String): ReaderFetch? = try { transport.fetch(url) }
        catch (error: Exception) {
            if (error is CancellationException || error is KmpwrightException) throw error
            null
        }

    private suspend fun findLlms(target: String, filename: String, options: ReadOptions, transport: ReaderTransport): ReaderFetch? {
        val parsed = Url(target)
        val segments = parsed.encodedPath.trim('/').split('/').filter(String::isNotEmpty)
        for (size in segments.size downTo 0) {
            val url = URLBuilder(target).apply {
                encodedPath = "/" + (segments.take(size) + filename).joinToString("/")
                parameters.clear(); fragment = ""
            }.buildString()
            val fetch = optionalFetch(transport, url) ?: return null
            if (fetch.successful && fetch.mediaType !in setOf("text/html", "application/xhtml+xml")) {
                if (options.requireMarkdown && !fetch.markdown()) throw KmpwrightException("Expected text/markdown, got ${fetch.mediaType}")
                return fetch
            }
        }
        return null
    }

    private fun markdownUrl(target: String): String? {
        val path = Url(target).encodedPath
        if (path.endsWith(".md")) return null
        return URLBuilder(target).apply { encodedPath = if (path.isEmpty() || path == "/") "/index.md" else path.trimEnd('/') + ".md" }.buildString()
    }

    private data class Link(val title: String, val url: String)
    private val listPattern = Regex("^\\s*(?:[-+*]|\\d+[.)])\\s+(.+)$")
    private val linkPattern = Regex("(?<!!)\\[([^]\\n]+)]\\(([^)\\n]+)\\)")
    private fun llmsLinks(body: String, base: String): List<Link> = body.lineSequence().flatMap { line ->
        val content = listPattern.matchEntire(line)?.groupValues?.get(1) ?: return@flatMap emptySequence()
        linkPattern.findAll(content).mapNotNull { match ->
            val href = match.groupValues[2].trim().substringBefore(' ').trim('<', '>')
            runCatching { Link(match.groupValues[1].trim(), resolveReaderUrl(base, href)) }.getOrNull()
        }
    }.distinctBy { it.url }.toList()

    private fun llmsIndex(fetch: ReaderFetch, filter: String?): String {
        val links = llmsLinks(fetch.body, fetch.finalUrl).filter { filter == null || it.title.contains(filter, true) || it.url.contains(filter, true) }
        if (links.isEmpty()) return if (filter != null) "No matching llms.txt links" else fetch.body.trim()
        return "# llms.txt\n\nSource: ${fetch.finalUrl}\n\n" + links.joinToString("\n") { "- [${it.title}](${it.url})" }
    }

    private fun matchingLink(links: List<Link>, target: String): Link? {
        fun path(value: String): String = Url(value).encodedPath.trimEnd('/').removeSuffix(".md").removeSuffix(".html").removeSuffix("/index")
        fun key(value: String) = canonicalHttpOrigin(value) to path(value)
        links.firstOrNull { key(it.url) == key(target) }?.let { return it }
        val segment = path(target).substringAfterLast('/')
        return links.filter { canonicalHttpOrigin(it.url) == canonicalHttpOrigin(target) &&
            (path(it.url).substringAfterLast('/') == segment || it.title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') == segment) }.singleOrNull()
    }

    internal fun activeHtml(url: String, html: String, options: ReadOptions): ReadResult {
        validateOptions(options)
        if (options.llms != null || options.requireMarkdown) throw KmpwrightException("HTTP Markdown/llms options require an explicit URL")
        val body = boundedUtf8(html, options.maxBytes)
        val fetch = ReaderFetch(url, 200, "text/html", emptyMap(), body, body != html)
        return selected(url, fetch, options, if (options.raw) "active-tab-raw" else "active-tab-html").copy(status = null)
    }
}
