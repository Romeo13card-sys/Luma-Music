package org.luma.music

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

class GitHubUpdater(private val context: Context) {
    data class Release(
        val tag: String,
        val notes: String,
        val asset: JSONObject,
        val sums: JSONObject,
    )

    fun check(includeTest: Boolean): Release? {
        val current =
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        val data =
            NativeCore.call(
                "update_check",
                JSONObject()
                    .put("current", current)
                    .put("includeTest", includeTest)
                    .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                    .toString(),
            ) as? JSONObject ?: return null
        return Release(
            data.getString("tag"),
            data.getString("notes"),
            data.getJSONObject("asset"),
            data.getJSONObject("sums"),
        )
    }

    fun download(release: Release, progress: (Int) -> Unit): File {
        val size = release.asset.getLong("size")
        val partial = File(context.filesDir, "updates/update.part")
        val folder = File(context.filesDir, "updates")
        val savedMetadata = runCatching { JSONObject(File(folder, "update.json").readText()) }.getOrNull()
        val sameAsset = savedMetadata?.optString("tag") == release.tag &&
            savedMetadata.optLong("assetId", -1L) == release.asset.optLong("id", -2L) &&
            savedMetadata.optString("name") == release.asset.optString("name") &&
            savedMetadata.optLong("size", -1L) == size
        val savedBytes = if (sameAsset && partial.isFile) partial.length().coerceIn(0, size) else 0L
        val remainingBytes = (size - savedBytes).coerceAtLeast(0)
        require(
            size in 1..UpdatePolicy.MAX_APK &&
                context.filesDir.usableSpace > remainingBytes + 1024 * 1024
        ) {
            "Недостаточно места или недопустимый размер APK"
        }
        val data =
            JSONObject()
                .put("filesDir", context.filesDir.absolutePath)
                .put(
                    "release",
                    JSONObject()
                        .put("tag", release.tag)
                        .put("asset", release.asset)
                        .put("sums", release.sums),
                )
        val result =
            JSONObject(
                Native.download(
                    data.toString(),
                    Native.Progress { percent ->
                        progress(percent)
                        !Thread.currentThread().isInterrupted
                    },
                )
            )
        require(result.optBoolean("ok")) { result.optString("error", "Ошибка загрузки") }
        val file = File(result.getString("data"))
        try {
            validate(file)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        return file
    }

    /** Cheap UI check; cryptographic verification is deferred to the background before install. */
    fun hasSavedUpdate(): Boolean {
        val folder = File(context.filesDir, "updates")
        val file = File(folder, "update.apk")
        if (!file.isFile || file.length() !in 1..UpdatePolicy.MAX_APK) return false
        return runCatching {
            val metadata = JSONObject(File(folder, "update.json").readText())
            val expectedSize = metadata.getLong("size")
            val expectedHash = metadata.getString("sha256")
            expectedSize == file.length() && expectedHash.matches(Regex("[a-fA-F0-9]{64}"))
        }.getOrDefault(false)
    }

    /** Returns progress for an interrupted APK without hashing or reading its contents. */
    fun partialDownloadInfo(): String? {
        val folder = File(context.filesDir, "updates")
        val partial = File(folder, "update.part")
        if (!partial.isFile) return null
        return runCatching {
            val metadata = JSONObject(File(folder, "update.json").readText())
            val size = metadata.getLong("size")
            val downloaded = partial.length()
            val tag = metadata.getString("tag")
            val hash = metadata.getString("sha256")
            require(size in 1..UpdatePolicy.MAX_APK && downloaded in 1 until size)
            require(tag.isNotBlank() && hash.matches(Regex("[a-fA-F0-9]{64}")))
            val mib = 1024.0 * 1024.0
            val done = String.format(java.util.Locale.ROOT, "%.1f", downloaded / mib)
            val total = String.format(java.util.Locale.ROOT, "%.1f", size / mib)
            "$tag · $done / $total МиБ"
        }.getOrNull()
    }

    /** A fully downloaded GitHub APK is kept in app-private files across restarts. */
    fun savedUpdate(): File? {
        val folder = File(context.filesDir, "updates")
        val file = File(folder, "update.apk")
        if (!file.isFile) return null
        return runCatching {
            val expected = JSONObject(File(folder, "update.json").readText()).getString("sha256")
            require(expected.matches(Regex("[a-fA-F0-9]{64}"))) { "Нет контрольной суммы GitHub APK" }
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(32768)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(actual.equals(expected, ignoreCase = true)) { "Сохранённый APK повреждён или изменён" }
            validate(file)
            file
        }.getOrNull()
    }

    fun validate(file: File) {
        require(file.isFile && file.length() in 1..UpdatePolicy.MAX_APK) { "Некорректный APK" }
        val manager = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val installed = manager.getPackageInfo(context.packageName, flags)
        val archive =
            manager.getPackageArchiveInfo(file.absolutePath, flags)
                ?: error("Не удалось прочитать APK")
        val before =
            installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        val after =
            archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        UpdatePolicy.upgrade(
            installed.packageName,
            archive.packageName,
            installed.longVersionCode,
            archive.longVersionCode,
            archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE,
            Build.VERSION.SDK_INT,
            before,
            after,
        )
    }
}
