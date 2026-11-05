package org.luma.music

import org.json.JSONArray
import org.json.JSONObject

object UpdatePolicy {
    fun dailyCheckDue(now: Long, previous: Long): Boolean =
        NativeCore.call(
            "daily_check_due",
            JSONObject().put("now", now).put("previous", previous).toString(),
        ) as Boolean

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
        NativeCore.call(
            "upgrade_validate",
            JSONObject()
                .put("installedPackage", installedPackage)
                .put("candidatePackage", candidatePackage)
                .put("installedVersion", installedVersion)
                .put("candidateVersion", candidateVersion)
                .put("minSdk", minSdk)
                .put("deviceSdk", deviceSdk)
                .put("before", JSONArray(installedSigners.toList()))
                .put("after", JSONArray(candidateSigners.toList()))
                .toString(),
        )
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
