package com.bernaferrari.kmpwright.core

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject

internal enum class CdpEndpointScope {
    Browser,
    Page
}

internal interface CdpSessionTransport {
    suspend fun send(method: String, params: Map<String, Any?> = emptyMap()): CdpResponse
    suspend fun sendSession(sessionId: String, method: String, params: Map<String, Any?> = emptyMap()): CdpResponse
}

internal interface CdpEventTransport : CdpSessionTransport {
    val connectionState: kotlinx.coroutines.flow.StateFlow<Boolean>? get() = null
    fun onEvent(
        method: String,
        sessionId: String? = null,
        listener: (JsonObject) -> Unit
    ): CdpEventSubscription
}

internal fun interface CdpEventSubscription {
    fun cancel()
}

internal data class CdpPageSession(
    val scope: CdpEndpointScope,
    val targetId: String? = null,
    val sessionId: String? = null,
    val ownsTarget: Boolean = true,
    val browserContextId: String? = null
)

internal suspend fun detectCdpEndpointScope(connection: CdpConnection): CdpEndpointScope =
    detectCdpEndpointScope(CdpConnectionTransport(connection))

internal suspend fun detectCdpEndpointScope(transport: CdpSessionTransport): CdpEndpointScope {
    val result = transport.send(CdpMethods.TARGET_GET_TARGETS)
    return if (result.error == null) CdpEndpointScope.Browser else CdpEndpointScope.Page
}

internal suspend fun createCdpPageSession(
    connection: CdpConnection,
    scope: CdpEndpointScope,
    newWindow: Boolean = false,
    browserContextId: String? = null
): CdpPageSession = createCdpPageSession(CdpConnectionTransport(connection), scope, newWindow, browserContextId)

internal suspend fun createCdpPageSession(
    transport: CdpSessionTransport,
    scope: CdpEndpointScope,
    newWindow: Boolean = false,
    browserContextId: String? = null
): CdpPageSession {
    return when (scope) {
        CdpEndpointScope.Page -> {
            require(browserContextId == null) { "Browser contexts require a browser-scoped endpoint" }
            require(!newWindow) { "New windows require a browser-scoped endpoint" }
            enableCdpDomains(transport, sessionId = null)
            CdpPageSession(scope = CdpEndpointScope.Page)
        }
        CdpEndpointScope.Browser -> {
            val targetResult = transport.send(
                CdpMethods.TARGET_CREATE_TARGET,
                buildMap {
                    put("url", "about:blank")
                    if (newWindow) put("newWindow", true)
                    browserContextId?.let { put("browserContextId", it) }
                }
            )
            throwIfCdpError(targetResult, CdpMethods.TARGET_CREATE_TARGET)
            val targetId = targetResult.result?.get("targetId")?.jsonPrimitive?.content
                ?: throw KmpwrightException("CDP target creation did not return a targetId.")

            try {
                val session = attachCdpPageSession(transport, targetId, ownsTarget = true)
                enableCdpDomains(transport, session.sessionId)
                session.copy(browserContextId = browserContextId)
            } catch (error: Throwable) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    kotlinx.coroutines.withTimeoutOrNull(1_000) { runCatching {
                        transport.send(CdpMethods.TARGET_CLOSE_TARGET, mapOf("targetId" to targetId))
                    } }
                }
                throw error
            }
        }
    }
}

/** Attachment does not enable renderer domains until the caller installs dialog listeners. */
internal suspend fun attachCdpPageSession(transport: CdpSessionTransport, targetId: String,
    ownsTarget: Boolean = false): CdpPageSession {
    val response = transport.send(CdpMethods.TARGET_ATTACH_TO_TARGET, mapOf("targetId" to targetId, "flatten" to true))
    throwIfCdpError(response, CdpMethods.TARGET_ATTACH_TO_TARGET)
    val sessionId = response.result?.get("sessionId")?.jsonPrimitive?.content
        ?: throw KmpwrightException("CDP target attach did not return a sessionId.")
    return CdpPageSession(CdpEndpointScope.Browser, targetId, sessionId, ownsTarget)
}

internal suspend fun closeCdpPageSession(connection: CdpConnection, session: CdpPageSession) {
    closeCdpPageSession(CdpConnectionTransport(connection), session)
}

internal suspend fun closeCdpPageSession(transport: CdpSessionTransport, session: CdpPageSession) {
    if (session.scope != CdpEndpointScope.Browser || session.targetId == null) {
        return
    }

    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        kotlinx.coroutines.withTimeoutOrNull(1_000) { runCatching {
            if (session.ownsTarget) transport.send(CdpMethods.TARGET_CLOSE_TARGET, mapOf("targetId" to session.targetId))
            else session.sessionId?.let { transport.send("Target.detachFromTarget", mapOf("sessionId" to it)) }
        } }
    }
}

internal suspend fun enableCdpDomains(transport: CdpSessionTransport, sessionId: String?) {
    sendSessionCommand(transport, sessionId, CdpMethods.PAGE_ENABLE)
    sendSessionCommand(transport, sessionId, CdpMethods.RUNTIME_ENABLE)
    sendSessionCommand(transport, sessionId, CdpMethods.NETWORK_ENABLE)
}

private suspend fun sendSessionCommand(
    transport: CdpSessionTransport,
    sessionId: String?,
    method: String,
    params: Map<String, Any?> = emptyMap()
) {
    val response = if (sessionId == null) {
        transport.send(method, params)
    } else {
        transport.sendSession(sessionId, method, params)
    }
    throwIfCdpError(response, method)
}

private fun throwIfCdpError(response: CdpResponse, method: String) {
    response.error?.let {
        throw KmpwrightException("CDP error for $method (${it.code}): ${it.message}")
    }
}

internal class CdpConnectionTransport(
    private val connection: CdpConnection
) : CdpSessionTransport {
    override suspend fun send(method: String, params: Map<String, Any?>): CdpResponse =
        connection.send(method, params)

    override suspend fun sendSession(
        sessionId: String,
        method: String,
        params: Map<String, Any?>
    ): CdpResponse = connection.sendSession(sessionId, method, params)
}
