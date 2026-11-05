package org.luma.music

import org.json.JSONObject

/** Rust operations; offline policy calls do not create a network client. */
object NativeCore {
    fun call(operation: String, argument: String): Any? {
        val reply = JSONObject(Native.core(operation, argument))
        require(reply.optBoolean("ok")) { reply.optString("error", "Ошибка Rust-ядра") }
        return reply.opt("data").takeUnless { it == JSONObject.NULL }
    }
}
