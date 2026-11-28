package com.chalaiden.app.share

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Share Chalaiden: the installed app goes to another phone as an APK, best through Quick Share, which
 * copies it over Bluetooth and Wi-Fi Direct with no internet. Its "Use QR code" button pairs the phones.
 */
object ShareApp {
    private const val APK = "application/vnd.android.package-archive"

    /**
     * Quick Share's screen for sending files, or null when this phone has none. Found among the apps that take
     * an APK rather than by name: its class has moved between versions of Play Services.
     */
    fun quickShare(context: Context): ComponentName? =
        runCatching { context.packageManager.queryIntentActivities(Intent(Intent.ACTION_SEND).setType(APK), 0) }
            .getOrDefault(emptyList())
            .map { it.activityInfo }
            .firstOrNull { it.packageName == "com.google.android.gms" && it.name.contains(".nearby.sharing.") }
            ?.let { ComponentName(it.packageName, it.name) }

    /**
     * The installed app, copied into the cache under a name people recognise; null if it can't be. Android keeps
     * the installed file where other apps can't open it, so the copy is what's handed over. Kept while it matches
     * the installed one, so a second share doesn't copy 20 MB again.
     */
    suspend fun copy(context: Context): File? = withContext(Dispatchers.IO) {
        val info = context.applicationInfo
        // Installed in parts (from a bundle): the base alone wouldn't install on the other phone.
        if (!info.splitSourceDirs.isNullOrEmpty()) return@withContext null
        runCatching {
            val source = File(info.sourceDir)
            val out = File(File(context.cacheDir, "share").apply { mkdirs() }, "Chalaiden.apk")
            if (!(out.length() == source.length() && out.lastModified() >= source.lastModified())) {
                val part = File(out.parentFile, "Chalaiden.apk.part")
                source.inputStream().use { input -> part.outputStream().use { input.copyTo(it) } }
                if (!part.renameTo(out)) error("couldn't rename")
            }
            out
        }.getOrNull()
    }

    /** Hands [file] to [target] (Quick Share), or to Android's share sheet when there is none or it won't open. */
    fun send(context: Context, file: File, target: ComponentName?) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(APK)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("Chalaiden.apk", uri) }
        if (target != null && runCatching { context.startActivity(Intent(send).setComponent(target)) }.isSuccess) return
        context.startActivity(Intent.createChooser(send, "Share Chalaiden"))
    }
}
