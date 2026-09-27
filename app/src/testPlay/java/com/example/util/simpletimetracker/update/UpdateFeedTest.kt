package com.example.util.simpletimetracker.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateFeedTest {

    private val digest = "a".repeat(64)

    @Test
    fun `compares release versions without treating equal tags as updates`() {
        assertTrue(UpdateFeed.newerVersion("v1.59.2", "1.59.1"))
        assertFalse(UpdateFeed.newerVersion("v1.59.1", "1.59.1"))
        assertFalse(UpdateFeed.newerVersion("v1.59", "1.59.1"))
        assertFalse(UpdateFeed.newerVersion("v1.59.2-beta", "1.59.1"))
    }

    @Test
    fun `accepts only the expected release asset with a digest`() {
        val release = UpdateFeed.parseRelease(json())
        assertEquals("1.59.2", release.version)
        assertEquals(2048, release.size)
        assertEquals(digest, release.sha256)
    }

    @Test
    fun `rejects an asset served from another repository`() {
        assertThrows(IllegalArgumentException::class.java) {
            UpdateFeed.parseRelease(json().replace("ulugkhujayev/sttbar-android", "someone/other"))
        }
    }

    @Test
    fun `rejects a release without a digest`() {
        assertThrows(Exception::class.java) {
            UpdateFeed.parseRelease(json().replace("sha256:$digest", ""))
        }
    }

    private fun json() = """
        {
          "tag_name": "v1.59.2",
          "assets": [{
            "name": "sttbar-android.apk",
            "browser_download_url": "https://github.com/ulugkhujayev/sttbar-android/releases/download/v1.59.2/sttbar-android.apk",
            "size": 2048,
            "digest": "sha256:$digest"
          }]
        }
    """.trimIndent()
}
