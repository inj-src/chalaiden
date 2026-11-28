package com.chalaiden.app.update

import android.content.Context
import com.chalaiden.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A version published on GitHub: its number, what changed, and where to get it. */
data class Release(val version: String, val notes: String, val pageUrl: String, val apkUrl: String?)

/**
 * Looks for a newer Chalaiden among the project's GitHub releases. It only asks for the latest release;
 * nothing about the phone or its payments goes with it. Offline (Chalaiden's usual case) it fails quietly
 * and the last answer stands.
 *
 * Releases must be tagged with the version (`v0.1.3` or `0.1.3`); an attached `.apk` is linked straight,
 * else the release page.
 */
object Updates {
    private const val LATEST = "https://api.github.com/repos/inj-src/chalaiden/releases/latest"

    /** Checked at most this often on its own; the Updates screen can always check now. */
    private const val EVERY_MILLIS = 6 * 60 * 60 * 1000L

    /** "Not now" on the sheet hides it for this long. */
    const val SNOOZE_MILLIS = 7 * 24 * 60 * 60 * 1000L

    sealed interface State {
        data object Unknown : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data object Failed : State
    }

    private val _state = MutableStateFlow<State>(State.Unknown)
    val state: StateFlow<State> = _state.asStateFlow()

    /** When the last check that reached GitHub finished; 0 before the first. */
    fun checkedAt(context: Context): Long = prefs(context).getLong(CHECKED_AT, 0L)

    /** Hidden until [SNOOZE_MILLIS] after "Not now". */
    fun snoozed(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        now - prefs(context).getLong(DISMISSED_AT, 0L) < SNOOZE_MILLIS

    fun snooze(context: Context) {
        prefs(context).edit().putLong(DISMISSED_AT, System.currentTimeMillis()).apply()
    }

    /**
     * Brings [state] up to date. Without [force], a recent answer is reused from the last check, so
     * opening the app doesn't ask GitHub every time.
     */
    suspend fun check(context: Context, force: Boolean = false) {
        debugRelease?.let { _state.value = State.Available(it); return }
        val sp = prefs(context)
        val cached = sp.getString(LAST, null)?.let(::parse)
        if (!force && System.currentTimeMillis() - sp.getLong(CHECKED_AT, 0L) < EVERY_MILLIS) {
            _state.value = if (cached == null) State.UpToDate else verdict(cached)
            return
        }
        if (_state.value == State.Checking) return
        _state.value = State.Checking
        val json = withContext(Dispatchers.IO) { runCatching { fetch() }.getOrNull() }
        if (json == NONE) {
            // Nothing published yet: this is the latest there is.
            sp.edit().remove(LAST).putLong(CHECKED_AT, System.currentTimeMillis()).apply()
            _state.value = State.UpToDate
            return
        }
        val release = json?.let(::parse)
        if (release == null) {
            // Asked for: say it failed. On its own: keep the last answer, or say nothing.
            _state.value = if (force) State.Failed else verdict(cached)
            return
        }
        sp.edit().putString(LAST, json).putLong(CHECKED_AT, System.currentTimeMillis()).apply()
        _state.value = verdict(release)
    }

    /** What [fetch] answers when the project has no release yet. */
    private const val NONE = ""

    /** Debug builds only: pretend [release] is out and forget any "Not now", to see the sheet. */
    fun debugShow(context: Context, release: Release) {
        prefs(context).edit().remove(DISMISSED_AT).apply()
        debugRelease = release
        _state.value = State.Available(release)
    }

    private var debugRelease: Release? = null

    private fun verdict(release: Release?): State = when {
        release == null -> State.Unknown
        isNewer(release.version, BuildConfig.VERSION_NAME) -> State.Available(release)
        else -> State.UpToDate
    }

    private fun fetch(): String {
        val conn = URL(LATEST).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Chalaiden")
            if (conn.responseCode == 404) return NONE // no release published
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: String): Release? = runCatching {
        val o = JSONObject(json)
        val tag = o.getString("tag_name").trim().removePrefix("v").removePrefix("V")
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val assets = o.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?.optString("browser_download_url")?.takeIf { it.isNotBlank() }
        Release(tag, o.optString("body").trim(), o.getString("html_url"), apk)
    }.getOrNull()

    /** "0.1.10" is newer than "0.1.9"; anything after a dash ("-beta") is ignored. */
    internal fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.trim().toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun prefs(context: Context) = context.getSharedPreferences("chalaiden_updates", Context.MODE_PRIVATE)
    private const val LAST = "last_release"
    private const val CHECKED_AT = "checked_at"
    private const val DISMISSED_AT = "dismissed_at"
}
