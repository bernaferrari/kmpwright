package com.bernaferrari.kmpwright.core

/** Current-document activity is distinct from request history, which may finish after a navigation. */
internal class CdpNetworkActivity {
    private data class Document(val frame: String, val loader: String)
    private val documents = mutableMapOf<String, Document>()
    private val active = mutableSetOf<String>()

    @Synchronized fun started(request: Request, frameId: String?, loaderId: String?) {
        val id = request.id
        if (!frameId.isNullOrEmpty() && !loaderId.isNullOrEmpty()) documents[id] = Document(frameId, loaderId)
        // Browser chrome and long-lived event streams do not determine document readiness.
        val favicon = request.resourceType == "other" &&
            runCatching { java.net.URI(request.url).path == "/favicon.ico" }.getOrDefault(false)
        if (request.resourceType != "eventsource" && !favicon) active.add(id) else active.remove(id)
    }

    @Synchronized fun finished(id: String) {
        active.remove(id)
        documents.remove(id)
    }

    @Synchronized fun documentCommitted(frameId: String?, loaderId: String?) {
        if (frameId.isNullOrEmpty() || loaderId.isNullOrEmpty()) return
        documents.forEach { (id, document) ->
            if (document.frame == frameId && document.loader != loaderId) active.remove(id)
        }
    }

    @Synchronized fun frameDetached(frameId: String?) {
        documents.forEach { (id, document) -> if (document.frame == frameId) active.remove(id) }
    }

    @Synchronized fun count(): Int = active.size

    @Synchronized fun clear() {
        active.clear()
        documents.clear()
    }
}
