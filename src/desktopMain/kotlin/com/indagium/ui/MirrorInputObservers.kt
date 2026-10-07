package com.indagium.ui

import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorFrame
import java.io.Closeable
import java.util.UUID

/** Observes only mirror commands the device accepted; closing a subscription never owns/stops the mirror. */
internal data class MirrorObservedInput(val command: MirrorControlCommand, val frame: MirrorFrame?)

internal object MirrorInputObservers {
    private data class Listener(val serial: String, val callback: (MirrorObservedInput) -> Unit)

    private val listeners = LinkedHashMap<String, Listener>()

    fun observe(serial: String, callback: (MirrorObservedInput) -> Unit): Closeable {
        val id = UUID.randomUUID().toString()
        synchronized(listeners) { listeners[id] = Listener(serial, callback) }
        return Closeable { synchronized(listeners) { listeners.remove(id) } }
    }

    fun publish(serial: String?, command: MirrorControlCommand, frame: MirrorFrame?) {
        if (serial.isNullOrBlank()) return
        val callbacks = synchronized(listeners) { listeners.values.filter { it.serial == serial }.map { it.callback } }
        val event = MirrorObservedInput(command, frame)
        callbacks.forEach { callback -> runCatching { callback(event) } }
    }
}
