package dev.streamer.app.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Short, transient messages for the user (shown as snackbars), e.g. a failed background refresh. */
class UserMessages {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var lastMessage: String? = null
    private var lastAt = 0L

    /** Repeats of the same message within [dedupeMillis] are dropped. */
    fun post(message: String, nowMillis: Long = System.currentTimeMillis(), dedupeMillis: Long = 30_000) {
        synchronized(this) {
            if (message == lastMessage && nowMillis - lastAt < dedupeMillis) return
            lastMessage = message
            lastAt = nowMillis
        }
        _messages.tryEmit(message)
    }
}
