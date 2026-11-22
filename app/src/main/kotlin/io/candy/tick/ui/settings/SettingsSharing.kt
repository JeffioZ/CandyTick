package io.candy.tick.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.candy.tick.data.CacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Leave room in Binder's shared transaction buffer for the chooser and other in-flight calls.
internal const val MAX_SHARED_TEXT_CHARS = 32_000

internal suspend fun shareSettingsText(context: Context, text: String, backup: Boolean) {
    val send = Intent(Intent.ACTION_SEND)
    if (text.length <= MAX_SHARED_TEXT_CHARS) {
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_TEXT, text)
    } else {
        val uri = withContext(Dispatchers.IO) {
            val file = CacheManager.createShareFile(context, backup)
            file.writeText(text)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        send.type = if (backup) "application/json" else "text/plain"
        send.putExtra(Intent.EXTRA_STREAM, uri)
        send.clipData = ClipData.newRawUri(null, uri)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
}
