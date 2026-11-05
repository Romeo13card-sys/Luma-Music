package org.luma.music

object Native {
    init {
        System.loadLibrary("yamusic_core")
    }

    fun interface Progress {
        fun onProgress(percent: Int): Boolean
    }

    external fun download(argument: String, progress: Progress): String

    external fun core(operation: String, argument: String): String

    external fun request(token: String, operation: String, argument: String): String
}
