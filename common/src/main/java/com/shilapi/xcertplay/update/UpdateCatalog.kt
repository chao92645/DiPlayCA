package com.shilapi.xcertplay.update

import org.json.JSONArray

/** The published release that a newer build can be downloaded from. */
internal data class UpdateRelease(
    val tagName: String,
    val apkName: String,
    val apkUrl: String,
    val checksumsUrl: String,
)

internal object UpdateCatalog {
    internal const val CHECKSUMS_FILE = "SHA256SUMS.txt"

    /**
     * Returns the newest published release that carries an APK and its checksums, or null.
     *
     * The release list is not guaranteed to be newest-first: Gitee returns its own order and
     * ignores the documented `direction` parameter, so selecting by version number instead of
     * by position keeps the app from silently pinning itself to an older build.
     */
    internal fun parse(json: String): UpdateRelease? {
        val releases = JSONArray(json)
        var newest: UpdateRelease? = null
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft")) continue
            val assets = release.optJSONArray("assets") ?: continue
            var apkName = ""
            var apkUrl = ""
            var checksumsUrl = ""
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (name.endsWith(".apk")) {
                    apkName = name
                    apkUrl = url
                }
                if (name.equals(CHECKSUMS_FILE, ignoreCase = true)) checksumsUrl = url
            }
            if (apkUrl.isEmpty() || checksumsUrl.isEmpty()) continue
            val tagName = release.optString("tag_name")
            if (UpdateVersion.parse(tagName) == null) continue
            if (newest == null || UpdateVersion.isNewer(tagName, newest.tagName)) {
                newest = UpdateRelease(tagName, apkName, apkUrl, checksumsUrl)
            }
        }
        return newest
    }
}
