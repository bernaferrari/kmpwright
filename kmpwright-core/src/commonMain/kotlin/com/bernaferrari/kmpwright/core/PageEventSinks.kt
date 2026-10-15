package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Mutable sinks behind the read-only [PageEvents] view. Emission never suspends; slow collectors lose the oldest events. */
internal class PageEventSinks : PageEvents {
    private fun <T> sink(capacity: Int) = MutableSharedFlow<T>(extraBufferCapacity = capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val consoleSink = sink<ConsoleMessage>(256)
    val navigationSink = sink<NavigationEvent>(64)
    val loadStateSink = sink<LoadState>(16)
    val dialogSink = sink<DialogEvent>(16)
    val requestSink = sink<Request>(256)
    val responseSink = sink<Response>(256)
    val downloadSink = sink<Download>(32)
    val fileChooserSink = sink<FileChooser>(16)
    val pageErrorSink = sink<PageError>(64)
    override val console: SharedFlow<ConsoleMessage> = consoleSink.asSharedFlow()
    override val navigation: SharedFlow<NavigationEvent> = navigationSink.asSharedFlow()
    override val loadState: SharedFlow<LoadState> = loadStateSink.asSharedFlow()
    override val dialog: SharedFlow<DialogEvent> = dialogSink.asSharedFlow()
    override val request: SharedFlow<Request> = requestSink.asSharedFlow()
    override val response: SharedFlow<Response> = responseSink.asSharedFlow()
    override val download: SharedFlow<Download> = downloadSink.asSharedFlow()
    override val fileChooser: SharedFlow<FileChooser> = fileChooserSink.asSharedFlow()
    override val pageError: SharedFlow<PageError> = pageErrorSink.asSharedFlow()
}
