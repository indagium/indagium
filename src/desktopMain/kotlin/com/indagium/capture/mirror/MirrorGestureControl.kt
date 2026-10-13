package com.indagium.capture.mirror

/** One live control connection and its encoded screen size. scrcpy rejects touch messages with another screen size. */
internal class MirrorGestureControl(val width: Int, val height: Int, val send: (ByteArray) -> Boolean)
