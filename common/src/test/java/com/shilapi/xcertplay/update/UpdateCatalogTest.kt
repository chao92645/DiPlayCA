package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UpdateCatalogTest {
    private val releaseJson = """
        [
          {
            "tag_name": "v0.2.15",
            "draft": true,
            "assets": [
              {"name": "DiPlay-0.2.15.apk", "browser_download_url": "https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.15/DiPlay-0.2.15.apk"},
              {"name": "SHA256SUMS.txt", "browser_download_url": "https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.15/SHA256SUMS.txt"}
            ]
          },
          {
            "tag_name": "v0.2.14",
            "draft": false,
            "prerelease": true,
            "assets": [
              {"name": "DiPlay-0.2.14.apk", "browser_download_url": "https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.14/DiPlay-0.2.14.apk"},
              {"name": "SHA256SUMS.txt", "browser_download_url": "https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.14/SHA256SUMS.txt"}
            ]
          }
        ]
    """.trimIndent()

    @Test
    fun skipsDraftsAndReturnsTheFirstPublishedRelease() {
        val release = UpdateCatalog.parse(releaseJson)
        assertEquals("v0.2.14", release?.tagName)
        assertEquals("DiPlay-0.2.14.apk", release?.apkName)
        assertEquals(
            "https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.14/DiPlay-0.2.14.apk",
            release?.apkUrl,
        )
        assertEquals(
            "https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.14/SHA256SUMS.txt",
            release?.checksumsUrl,
        )
    }

    @Test
    fun returnsNullWithoutAnApkAsset() {
        val json = """
            [
              {"tag_name": "v0.2.14", "draft": false, "assets": [
                {"name": "source.zip", "browser_download_url": "https://example.com/source.zip"}
              ]}
            ]
        """.trimIndent()
        assertNull(UpdateCatalog.parse(json))
    }

    @Test
    fun returnsNullWithoutChecksums() {
        val json = """
            [
              {"tag_name": "v0.2.14", "draft": false, "assets": [
                {"name": "DiPlay-0.2.14.apk", "browser_download_url": "https://example.com/DiPlay-0.2.14.apk"}
              ]}
            ]
        """.trimIndent()
        assertNull(UpdateCatalog.parse(json))
    }

    @Test
    fun returnsNullForAnEmptyReleaseList() {
        assertNull(UpdateCatalog.parse("[]"))
    }

    @Test
    fun picksTheNewestReleaseWhateverTheOrder() {
        // Oldest first, as Gitee can return them, to prove the newest is chosen by version.
        val json = """
            [
              {"tag_name": "DiPlay-CA-0.2.19", "draft": false, "assets": [
                {"name": "DiPlay-CA-0.2.19.apk", "browser_download_url": "https://gitee.com/aschao/DiPlayCA/releases/download/DiPlay-CA-0.2.19/DiPlay-CA-0.2.19.apk"},
                {"name": "SHA256SUMS.txt", "browser_download_url": "https://gitee.com/aschao/DiPlayCA/releases/download/DiPlay-CA-0.2.19/SHA256SUMS.txt"}
              ]},
              {"tag_name": "DiPlay-CA-0.2.20", "draft": false, "assets": [
                {"name": "DiPlay-CA-0.2.20.apk", "browser_download_url": "https://gitee.com/aschao/DiPlayCA/releases/download/DiPlay-CA-0.2.20/DiPlay-CA-0.2.20.apk"},
                {"name": "SHA256SUMS.txt", "browser_download_url": "https://gitee.com/aschao/DiPlayCA/releases/download/DiPlay-CA-0.2.20/SHA256SUMS.txt"}
              ]}
            ]
        """.trimIndent()
        assertEquals("DiPlay-CA-0.2.20", UpdateCatalog.parse(json)?.tagName)
    }
}
