package org.luma.music

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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

    private val repository = "Romeo13card-sys/Luma-Music-Releases"

    private fun connection(raw: String, binary: Boolean): HttpURLConnection {
        var next = raw
        repeat(6) {
            require(UpdatePolicy.trusted(next)) { "Недоверенный адрес обновления" }
            val url = URL(next)
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty(
                "Accept",
                if (binary) "application/octet-stream" else "application/vnd.github+json",
            )
            c.setRequestProperty("User-Agent", "Luma-Music-Updater")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val code = c.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val target = c.getHeaderField("Location") ?: error("Пустой адрес перенаправления")
                next = URL(url, target).toString()
                c.disconnect()
            } else {
                if (code != 200) {
                    c.disconnect()
                    error(
                        when (code) {
                            401,
                            403,
                            404 ->
                                "Публичный каталог выпусков недоступен. Возможно, он ещё не создан или GitHub ограничил запросы. Токен пользователя не требуется."
                            429 -> "GitHub ограничил запросы. Попробуйте позже."
                            else -> "GitHub вернул HTTP $code"
                        }
                    )
                }
                return c
            }
        }
        error("Слишком много перенаправлений")
    }

    private fun text(url: String, binary: Boolean = false, limit: Int = 2 * 1024 * 1024): String {
        val c = connection(url, binary)
        return try {
            c.inputStream.use { input ->
                val data = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(data.size() + n <= limit) { "Ответ сервера слишком большой" }
                    data.write(buffer, 0, n)
                }
                val bytes = data.toByteArray()
                require(bytes.size <= limit) { "Ответ сервера слишком большой" }
                String(bytes, Charsets.UTF_8)
            }
        } finally {
            c.disconnect()
        }
    }

    private fun assetUrl(asset: JSONObject): String {
        val id = asset.optLong("id")
        require(id > 0) { "Некорректный ID файла GitHub" }
        return "https://api.github.com/repos/$repository/releases/assets/$id"
    }

    fun check(includeTest: Boolean): Release? {
        val list = JSONArray(text("https://api.github.com/repos/$repository/releases?per_page=20"))
        val current =
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        for (i in 0 until list.length()) {
            val release = list.getJSONObject(i)
            if (release.optBoolean("draft") || (!includeTest && release.optBoolean("prerelease")))
                continue
            val tag = release.getString("tag_name")
            val comparison = UpdatePolicy.baseVersionComparison(tag, current) ?: continue
            if (comparison < 0) continue
            if (tag.removePrefix("v") == current || tag.removePrefix("v").startsWith("$current."))
                return null
            val assets = release.getJSONArray("assets")
            val apk = UpdatePolicy.apk(assets, Build.SUPPORTED_ABIS.toList())
            val sums =
                (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .singleOrNull { it.optString("name") == "SHA256SUMS.txt" }
                    ?: error("В выпуске нет контрольных сумм")
            return Release(tag, release.optString("body").take(3000), apk, sums)
        }
        return null
    }

    fun download(release: Release, progress: (Int) -> Unit): File {
        val name = release.asset.getString("name")
        val expected = UpdatePolicy.checksum(text(assetUrl(release.sums), true, 128 * 1024), name)
        val size = release.asset.getLong("size")
        require(
            size in 1..UpdatePolicy.MAX_APK && context.cacheDir.usableSpace > size * 2 + 1024 * 1024
        ) {
            "Недостаточно места или недопустимый размер APK"
        }
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File(folder, "update.part")
        val ready = File(folder, "update.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        val c = connection(assetUrl(release.asset), true)
        try {
            c.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(32768)
                    var count = 0L
                    var last = -1
                    while (true) {
                        if (Thread.currentThread().isInterrupted) error("Загрузка отменена")
                        val length = input.read(buffer)
                        if (length < 0) break
                        count += length
                        require(count <= size && count <= UpdatePolicy.MAX_APK) {
                            "Размер загрузки превышен"
                        }
                        output.write(buffer, 0, length)
                        digest.update(buffer, 0, length)
                        val percent = (count * 100 / size).toInt()
                        if (percent != last) {
                            last = percent
                            progress(percent)
                        }
                    }
                    require(count == size) { "APK загружен не полностью" }
                }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) } == expected) {
                "SHA-256 APK не совпадает"
            }
            validate(partial)
            if (ready.exists()) check(ready.delete()) { "Не удалось заменить прошлое обновление" }
            check(partial.renameTo(ready)) { "Не удалось сохранить APK" }
            return ready
        } finally {
            c.disconnect()
            partial.delete()
        }
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
