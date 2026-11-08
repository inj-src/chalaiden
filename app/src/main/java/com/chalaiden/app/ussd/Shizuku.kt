package com.chalaiden.app.ussd

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import rikka.shizuku.Shizuku

/**
 * What runs Chalaiden's privileged shell. Both give the shell the driver needs (UI Automation for the
 * helper, `input`, `ime`, `appops`); only how it starts differs.
 */
enum class Backend {
    /** `su`, as root (uid 0). */
    Root,

    /**
     * Shizuku's server, started over Wireless Debugging: the shell user (uid 2000), or root when Shizuku
     * itself was started with root. No root needed, but it has to be started again after every reboot.
     */
    Shizuku;

    /** True when `id` output shows this backend's rights. */
    internal fun accepts(id: String) = when (this) {
        Root -> id.contains("uid=0")
        Shizuku -> id.contains("uid=0") || id.contains("uid=2000(")
    }
}

/** Shizuku state and permission, as Chalaiden's setup needs them. */
object ShizukuAccess {
    const val PACKAGE = "moe.shizuku.privileged.api"
    const val DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
    private const val REQUEST_CODE = 0x247

    enum class Status { NotInstalled, NotRunning, Outdated, NeedsPermission, Denied, Ready }

    fun status(context: Context): Status {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!running) return if (installed(context)) Status.NotRunning else Status.NotInstalled
        return runCatching {
            when {
                Shizuku.isPreV11() -> Status.Outdated
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> Status.Ready
                // Shizuku reports "rationale" once the user has said no and asked not to be asked again.
                Shizuku.shouldShowRequestPermissionRationale() -> Status.Denied
                else -> Status.NeedsPermission
            }
        }.getOrDefault(Status.NotRunning) // the binder died between the ping and the checks
    }

    fun isReady(context: Context) = status(context) == Status.Ready

    private fun installed(context: Context) =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)

    /** Asks Shizuku to allow Chalaiden; Shizuku shows its own dialog. [onResult] runs on the main thread. */
    fun request(onResult: (Boolean) -> Unit) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }.onFailure {
            Shizuku.removeRequestPermissionResultListener(listener)
            onResult(false)
        }
    }

    /**
     * Calls [onChange] (on the main thread) whenever Shizuku starts or stops; once straight away if it is
     * already running. Returns the call that stops watching.
     */
    fun watch(onChange: () -> Unit): () -> Unit {
        val received = Shizuku.OnBinderReceivedListener { onChange() }
        val dead = Shizuku.OnBinderDeadListener { onChange() }
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        return {
            Shizuku.removeBinderReceivedListener(received)
            Shizuku.removeBinderDeadListener(dead)
        }
    }

    /** Opens the Shizuku app (to start it or allow Chalaiden), or its download page when it isn't installed. */
    /** Shizuku's page on Google Play; its website on phones without the Play Store. */
    fun openStore(context: Context) {
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(store) }.onFailure { runCatching { context.startActivity(web) } }
    }

    fun openApp(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL))
        runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /**
     * A `sh` running in Shizuku's server, with its stdin and stdout piped to Chalaiden. The launcher is
     * private in API 13 (Shizuku prefers bound services), but a plain shell is all the driver needs, and
     * it is the same shell that `su` gives the root path. Kept from R8 in proguard-rules.pro.
     */
    internal fun newShell(): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        ).apply { isAccessible = true }
        return method.invoke(null, arrayOf("sh"), null, null) as Process
    }
}
