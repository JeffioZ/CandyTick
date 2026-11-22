package io.candy.tick.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Some devices expose app details but do not handle every permission-specific settings intent. */
internal fun Context.openAppSettings(intent: Intent): Boolean = runCatching {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.recoverCatching {
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    startActivity(appDetails.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess
