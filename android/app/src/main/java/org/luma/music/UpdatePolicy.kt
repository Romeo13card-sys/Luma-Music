package org.luma.music

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

object UpdatePolicy {
    fun dailyCheckDue(now: Long, previous: Long): Boolean =
        previous <= 0 || now - previous !in 0 until 24L * 60 * 60 * 1000

    const val MAX_APK = 100L * 1024 * 1024

    fun baseVersionComparison(candidate: String, installed: String): Int? =
        (NativeCore.call(
                "version_compare",
                JSONObject().put("candidate", candidate).put("installed", installed).toString(),
            ) as? Number)
            ?.toInt()

    fun upgrade(
        installedPackage: String,
        candidatePackage: String,
        installedVersion: Long,
        candidateVersion: Long,
        minSdk: Int,
        deviceSdk: Int,
        installedSigners: Set<String>,
        candidateSigners: Set<String>,
    ) {
        require(candidatePackage == installedPackage) { "APK принадлежит другому приложению" }
        require(candidateVersion > installedVersion) {
            "Установлена такая же или более новая версия"
        }
        require(minSdk <= deviceSdk) { "APK требует более новую Android" }
        require(installedSigners.isNotEmpty() && installedSigners == candidateSigners) {
            "Подпись отличается от установленной версии. Обновление поверх неё невозможно без исходного ключа."
        }
    }

    fun trusted(raw: String): Boolean =
        try {
            val uri = URI(raw)
            uri.scheme == "https" &&
                uri.userInfo == null &&
                uri.port == -1 &&
                uri.host in
                    setOf(
                        "api.github.com",
                        "github.com",
                        "release-assets.githubusercontent.com",
                        "objects.githubusercontent.com",
                        "github-releases.githubusercontent.com",
                        "github-cloud.s3.amazonaws.com",
                    )
        } catch (_: Exception) {
            false
        }

    fun checksum(text: String, name: String): String =
        NativeCore.call("checksum", JSONObject().put("text", text).put("name", name).toString())
            as String

    fun apk(assets: JSONArray, abis: List<String>): JSONObject =
        NativeCore.call(
            "asset_select",
            JSONObject().put("assets", assets).put("abis", JSONArray(abis)).toString(),
        ) as JSONObject
}
