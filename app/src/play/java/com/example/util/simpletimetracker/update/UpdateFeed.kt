package com.example.util.simpletimetracker.update

import android.content.Context
import android.os.Build
import com.example.util.simpletimetracker.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class UpdateRelease(
    val version: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String,
)

internal object UpdateFeed {
    private const val REPO = "ulugkhujayev/sttbar-android"
    private const val ASSET = "sttbar-android.apk"
    private const val MAX_APK_BYTES = 100L * 1024 * 1024
    private const val RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val ASSET_URL_PREFIX = "https://github.com/$REPO/releases/download/"

    fun newerVersion(tag: String, current: String): Boolean {
        fun parts(value: String): List<Int>? {
            val clean = value.removePrefix("v")
            if (!clean.matches(Regex("[0-9]+(\\.[0-9]+)*"))) return null
            return clean.split('.').map { it.toIntOrNull() ?: return null }
        }
        val next = parts(tag) ?: return false
        val installed = parts(current) ?: return false
        for (index in 0 until maxOf(next.size, installed.size)) {
            val delta = (next.getOrNull(index) ?: 0).compareTo(installed.getOrNull(index) ?: 0)
            if (delta != 0) return delta > 0
        }
        return false
    }

    fun parseRelease(json: String): UpdateRelease {
        val data = JSONObject(json)
        val version = data.getString("tag_name").removePrefix("v")
        require(version.matches(Regex("[0-9]+(\\.[0-9]+)*"))) { "Invalid release version" }
        val assets = data.getJSONArray("assets")
        val asset = (0 until assets.length())
            .map(assets::getJSONObject)
            .firstOrNull { it.optString("name") == ASSET }
            ?: error("Release has no STTbar APK")
        val url = asset.getString("browser_download_url")
        require(url.startsWith(ASSET_URL_PREFIX)) { "Unexpected download address" }
        val size = asset.getLong("size")
        require(size in 1..MAX_APK_BYTES) { "Invalid APK size" }
        val digest = asset.getString("digest")
        require(digest.matches(Regex("sha256:[0-9a-fA-F]{64}"))) { "Release has no SHA-256 digest" }
        return UpdateRelease(version, url, size, digest.substringAfter(':').lowercase())
    }

    suspend fun latest(): UpdateRelease = withContext(Dispatchers.IO) {
        val connection = (URL(RELEASE_URL).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        try {
            if (connection.responseCode != 200) {
                error("GitHub returned HTTP ${connection.responseCode}")
            }
            parseRelease(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(
        context: Context,
        release: UpdateRelease,
        onProgress: suspend (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "sttbar-updates").apply { mkdirs() }
        val part = File(dir, "sttbar-update-part.apk")
        val apk = File(dir, ASSET)
        part.delete()
        val connection = (URL(release.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
        }
        try {
            if (connection.responseCode != 200) {
                error("Download returned HTTP ${connection.responseCode}")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        require(received <= MAX_APK_BYTES) { "APK is too large" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        onProgress((received * 100 / release.size).coerceAtMost(100).toInt())
                    }
                }
            }
            require(received == release.size) { "APK download is incomplete" }
            val actualDigest = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(actualDigest == release.sha256) { "APK checksum does not match GitHub" }
            @Suppress("DEPRECATION")
            val archive = context.packageManager.getPackageArchiveInfo(part.path, 0)
                ?: error("Downloaded file is not an APK")
            require(archive.packageName == context.packageName) { "APK package does not match this app" }
            val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
            require(code > BuildConfig.VERSION_CODE) { "APK version is not newer" }
            apk.delete()
            require(part.renameTo(apk)) { "Could not save APK" }
            apk
        } finally {
            connection.disconnect()
            part.delete()
        }
    }
}
