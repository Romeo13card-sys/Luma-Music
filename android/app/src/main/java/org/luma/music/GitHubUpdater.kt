package org.luma.music

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
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
        require(
            size in 1..UpdatePolicy.MAX_APK && context.cacheDir.usableSpace > size * 2 + 1024 * 1024
        ) {
            "Недостаточно места или недопустимый размер APK"
        }
        val data =
            JSONObject()
                .put("cacheDir", context.cacheDir.absolutePath)
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
