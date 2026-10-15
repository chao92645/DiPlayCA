package com.shilapi.xcertplay.update

import java.net.URL
import java.net.URLConnection

internal object UpdateReleaseLookup {
    /**
     * Gitee first, because it is reachable from mainland China without a mirror; GitHub is kept
     * as a fallback. Only a failed lookup falls through - a successful lookup that simply has no
     * newer build returns null immediately, so a reachable Gitee never pays for a second request.
     */
    private val LOOKUP_URLS = listOf(
        UpdateClient.GITEE_RELEASES_URL,
        UpdateClient.RELEASES_URL,
    )

    fun latest(
        installedVersion: String,
        userAgent: String,
        openConnection: (URL) -> URLConnection = { it.openConnection() },
    ): UpdateRelease? {
        var lastFailure: Throwable? = null
        for (url in LOOKUP_URLS) {
            val json = try {
                UpdateClient.fetchText(url, "application/vnd.github+json", userAgent, openConnection)
            } catch (failure: Throwable) {
                lastFailure = failure
                continue
            }
            val release = UpdateCatalog.parse(json) ?: continue
            return release.takeIf { UpdateVersion.isNewer(it.tagName, installedVersion) }
        }
        lastFailure?.let { throw it }
        return null
    }
}
