package org.luma.music

object Native {
    init {
        System.loadLibrary("yamusic_core")
    }

    external fun request(token: String, operation: String, argument: String): String
}
