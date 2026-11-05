package dev.romeo.yamusic

object Native {
    init {
        System.loadLibrary("yamusic_core")
    }

    external fun request(token: String, operation: String, argument: String): String
}
