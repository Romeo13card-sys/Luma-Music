package org.luma.music

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

object UpdatePolicy {
    fun dailyCheckDue(now: Long, previous: Long): Boolean =
        previous <= 0 || now - previous !in 0 until 24L * 60 * 60 * 1000

    const val MAX_APK = 100L * 1024 * 1024

    fun baseVersionComparison(candidate: String, installed: String): Int? {
        fun parts(value: String): List<Long>? {
            val match =
                Regex("^v?([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:[-+].*)?$").matchEntire(value)
                    ?: return null
            return match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
        }
        val left = parts(candidate) ?: return null
        val right = parts(installed) ?: return null
        for (i in 0..2) {
            val comparison = left[i].compareTo(right[i])
            if (comparison != 0) return comparison
        }
        return 0
    }

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

    fun checksum(text: String, name: String): String {
        val matches =
            text
                .lineSequence()
                .map { it.trim().split(Regex("\\s+"), limit = 2) }
                .filter { it.size == 2 && it[1].removePrefix("*").removePrefix("./") == name }
                .toList()
        require(matches.size == 1 && matches[0][0].matches(Regex("[a-fA-F0-9]{64}"))) {
            "Отсутствует однозначная SHA-256 для APK"
        }
        return matches[0][0].lowercase()
    }

    fun apk(assets: JSONArray, abis: List<String>): JSONObject {
        val list = (0 until assets.length()).map { assets.getJSONObject(it) }
        for (abi in abis + "universal") {
            val matches =
                list.filter {
                    it.optString("name")
                        .matches(Regex("Luma-Music-[A-Za-z0-9._-]+-${Regex.escape(abi)}\\.apk"))
                }
            if (matches.size == 1) {
                val asset = matches.single()
                val size = asset.optLong("size")
                require(size in 1..MAX_APK) { "Размер APK вне допустимого диапазона" }
                return asset
            }
        }
        error("В выпуске нет APK для архитектуры устройства")
    }
}
