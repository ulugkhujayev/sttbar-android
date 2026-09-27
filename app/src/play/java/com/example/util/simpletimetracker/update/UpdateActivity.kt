package com.example.util.simpletimetracker.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import com.example.util.simpletimetracker.BuildConfig
import com.example.util.simpletimetracker.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class UpdateActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var action: Button
    private var downloadedApk: File? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val title = TextView(this).apply {
            text = getString(R.string.update_title)
            textSize = 22f
        }
        val version = TextView(this).apply {
            text = getString(R.string.update_installed_version, BuildConfig.VERSION_NAME)
            textSize = 15f
            setPadding(0, padding / 2, 0, padding / 2)
        }
        status = TextView(this).apply { text = getString(R.string.update_checking) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        action = Button(this).apply { visibility = View.GONE }
        val close = Button(this).apply {
            text = getString(R.string.update_close)
            setOnClickListener { finish() }
        }
        content.addView(title)
        content.addView(version)
        content.addView(status)
        content.addView(progress)
        content.addView(action)
        content.addView(close)
        setContentView(content)
        checkForUpdates()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun checkForUpdates() {
        if (busy) return
        busy = true
        action.visibility = View.GONE
        status.text = getString(R.string.update_checking)
        scope.launch {
            try {
                val found = UpdateFeed.latest()
                if (UpdateFeed.newerVersion(found.version, BuildConfig.VERSION_NAME)) {
                    status.text = getString(R.string.update_available, found.version)
                    action.text = getString(R.string.update_download)
                    action.setOnClickListener { downloadAndInstall(found) }
                } else {
                    status.text = getString(R.string.update_current)
                    showRetry()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = getString(R.string.update_failed, e.message ?: getString(R.string.general_error))
                showRetry()
            } finally {
                busy = false
                action.visibility = View.VISIBLE
            }
        }
    }

    private fun showRetry() {
        action.text = getString(R.string.update_retry)
        action.setOnClickListener { checkForUpdates() }
    }

    private fun downloadAndInstall(found: UpdateRelease) {
        if (busy) return
        busy = true
        action.visibility = View.GONE
        progress.progress = 0
        progress.visibility = View.VISIBLE
        status.text = getString(R.string.update_downloading)
        scope.launch {
            try {
                downloadedApk = UpdateFeed.download(this@UpdateActivity, found) { percent ->
                    withContext(Dispatchers.Main) { progress.progress = percent }
                }
                action.text = getString(R.string.update_install)
                action.setOnClickListener { installDownloadedApk() }
                status.text = getString(R.string.update_ready)
                installDownloadedApk()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = getString(R.string.update_failed, e.message ?: getString(R.string.general_error))
                showRetry()
            } finally {
                progress.visibility = View.GONE
                busy = false
                action.visibility = View.VISIBLE
            }
        }
    }

    private fun installDownloadedApk() {
        val file = downloadedApk ?: return
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            status.text = getString(R.string.update_allow_installs)
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
            )
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }
}
