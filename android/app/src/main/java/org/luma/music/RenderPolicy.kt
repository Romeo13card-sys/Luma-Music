package org.luma.music

/** Fixed caps: larger RAM never expands caches or allocates proportional buffers. */
data class RenderPolicy(val economy: Boolean) {
    val pageSize
        get() = if (economy) 20 else 40

    val uiIntervalMs
        get() = if (economy) 1000L else 500L

    companion object {
        fun choose(lowRam: Boolean, totalBytes: Long, heapMb: Int) =
            RenderPolicy(lowRam || totalBytes <= 3L * 1024 * 1024 * 1024 || heapMb <= 128)
    }
}
