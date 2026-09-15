package dev.anilbeesetti.nextplayer.feature.network.download

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.URLUtil
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import dev.anilbeesetti.nextplayer.core.ui.R

class DownloadRequestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = extractUrl(intent)
        if (url == null || !isValidHttpUrl(url)) {
            Toast.makeText(this, R.string.invalid_download_url, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val fileName = URLUtil.guessFileName(url, null, null)
        AlertDialog.Builder(this)
            .setTitle(R.string.download_with_player)
            .setMessage(getString(R.string.download_confirmation, fileName))
            .setPositiveButton(R.string.download) { _, _ ->
                DownloadManagerClient(this).enqueue(url)
                    .onSuccess {
                        Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
                    }
                    .onFailure {
                        Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
                    }
                finish()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun extractUrl(intent: Intent): String? {
        val directCandidates = listOfNotNull(
            intent.dataString,
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString(),
            IntentCompat.getParcelableExtra(
                intent,
                Intent.EXTRA_STREAM,
                Uri::class.java,
            )?.toString(),
        )
        val clipCandidates = buildList {
            val clipData = intent.clipData ?: return@buildList
            repeat(clipData.itemCount) { index ->
                val item = clipData.getItemAt(index)
                item.uri?.toString()?.let(::add)
                item.text?.toString()?.let(::add)
            }
        }
        return findHttpUrl(directCandidates + clipCandidates)
    }
}

internal fun findHttpUrl(candidates: Iterable<String>): String? =
    candidates.firstNotNullOfOrNull { value -> HTTP_URL.find(value)?.value }

private val HTTP_URL = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
