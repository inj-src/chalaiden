package com.chalaiden.app.ussd

import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One look at the phone app's USSD window. [raw] is backend-specific (the dump XML for [DumpScreen]). */
internal data class Look(val dialog: Dialog?, val field: String?, val password: Boolean, val raw: String? = null)

/** How [UssdDriver] reads and answers the phone's USSD dialog. */
internal interface UssdScreen : Closeable {
    /** Milliseconds between looks; a cheap look can afford to poll often. */
    val pollMillis: Long

    /** The phone USSD window as it is now, or null when none is showing. */
    fun look(): Look?

    /**
     * Puts [text] in the dialog's field and sends it, but only while the prompt showing is [expect].
     * False if nothing was sent: no field, another prompt, or the text couldn't be checked.
     */
    fun reply(text: String, expect: String, seen: Look?): Boolean

    /** Presses the dialog button android:id/[id]. False if there is no such button. */
    fun press(id: String): Boolean

    /** Last resort when a dialog has no buttons at all. */
    fun back()
}

/**
 * Fast path: [com.chalaiden.app.helper.UiHelper] running as root (or Shizuku's shell user) for the session. A look takes a few
 * milliseconds and answers go in as node actions, so neither the cover nor the keyboard matters.
 */
internal class HelperScreen private constructor(private val proc: Process) : UssdScreen {
    override val pollMillis = 120L

    private val writer = proc.outputStream.bufferedWriter()
    private val lines = LinkedBlockingQueue<String>()

    init {
        Thread({
            runCatching { proc.inputStream.bufferedReader().forEachLine { lines.put(it) } }
            lines.put(EOF)
        }, "chalaiden-helper-out").apply { isDaemon = true }.start()
    }

    /** One request, one reply. A helper that stops answering ends the session rather than hanging it. */
    @Synchronized private fun call(req: JSONObject): JSONObject {
        writer.write(req.toString())
        writer.newLine()
        writer.flush()
        val line = lines.poll(REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (line == null || line == EOF) throw IllegalStateException("helper stopped answering")
        return JSONObject(line)
    }

    override fun look(): Look? {
        val o = call(JSONObject().put("op", "look"))
        if (!o.optBoolean("win")) return null
        val field = if (o.optBoolean("input")) o.optString("field") else null
        val password = o.optBoolean("password")
        if (o.optBoolean("progress")) return Look(Dialog.Progress, field, password)
        val text = o.optString("message").trim()
        val dialog = when {
            text.isEmpty() -> null
            field != null -> Dialog.Prompt(text)
            else -> Dialog.Notice(text)
        }
        return Look(dialog, field, password)
    }

    override fun reply(text: String, expect: String, seen: Look?): Boolean =
        call(JSONObject().put("op", "reply").put("text", text).put("expect", expect)).optBoolean("ok")

    override fun press(id: String): Boolean = call(JSONObject().put("op", "press").put("id", id)).optBoolean("ok")

    override fun back() = Unit // every USSD dialog has buttons; never send BACK blind

    override fun close() {
        // Closing stdin makes the helper drop its connection and exit.
        runCatching { writer.close() }
        proc.destroy()
    }

    companion object {
        private const val EOF = "\u0000eof"
        private const val REPLY_TIMEOUT_SECONDS = 5L
        private const val READY_TIMEOUT_SECONDS = 8L

        /** Starts the helper from Chalaiden's own APK through [backend]. Null if the shell, the helper or its connection fails. */
        fun open(apkPath: String, backend: Backend = Backend.Root): HelperScreen? = runCatching {
            val p = when (backend) {
                Backend.Root -> ProcessBuilder("su").start()
                Backend.Shizuku -> ShizukuAccess.newShell()
            }
            val screen = HelperScreen(p)
            screen.writer.write("exec env CLASSPATH='$apkPath' app_process /system/bin com.chalaiden.app.helper.UiHelper 2>/dev/null\n")
            screen.writer.flush()
            val ready = screen.lines.poll(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (ready == null || ready == EOF || !JSONObject(ready).optBoolean("ready")) {
                screen.close()
                return null
            }
            screen
        }.getOrNull()
    }
}

/**
 * Slow fallback: `uiautomator dump` (~2 s a look) and key events. Key events go to the focused
 * window, which stays the phone dialog because the cover is not focusable.
 */
internal class DumpScreen(private val sh: RootShell) : UssdScreen {
    override val pollMillis = 300L

    override fun look(): Look? {
        if (!phoneFocused()) return null
        val xml = sh.exec(DUMP)
        if (!xml.contains(PHONE)) return null
        val field = field(xml)
        return Look(parse(xml, field != null), field?.text, field?.password == true, raw = xml)
    }

    override fun reply(text: String, expect: String, seen: Look?): Boolean {
        // Never type into anything but the phone's USSD field showing [expect]: this may be the PIN.
        // [seen] is the look the poll loop just read this prompt from; the PIN pad has none, so it looks again.
        val xml = seen?.raw ?: sh.exec(DUMP)
        if (promptOf(xml) != expect) return false
        val before = field(xml) ?: return false
        if (before.text.isNotEmpty() && !before.password) clear(before.text.length)
        if (!type(text)) return false
        repeat(2) { attempt ->
            val after = sh.exec(DUMP)
            if (promptOf(after) != expect) return false
            val now = field(after) ?: return false
            if (now.password || now.text == text) return send()
            if (attempt == 0) {
                clear(now.text.length)
                if (!type(text)) return false
            }
        }
        // The field never showed the text: don't send a guess.
        return false
    }

    /** Key events go to the focused window; only type while that is the phone dialog. */
    private fun type(text: String): Boolean {
        if (!phoneFocused()) return false
        sh.exec("input text ${shellArg(text)}")
        return true
    }

    private fun send(): Boolean {
        if (!phoneFocused()) return false
        sh.exec("input keyevent KEYCODE_ENTER")
        return true
    }

    private fun promptOf(xml: String) = (parse(xml, hasInput = true) as? Dialog.Prompt)?.text?.takeIf { xml.contains(PHONE) }

    private fun clear(length: Int) {
        sh.exec("input keyevent KEYCODE_MOVE_END; input keyevent ${"KEYCODE_DEL ".repeat(length + 2)}")
    }

    /** TAB until the button has focus, then ENTER. */
    override fun press(id: String): Boolean {
        repeat(4) {
            val xml = sh.exec(DUMP)
            if (!xml.contains(PHONE)) return true // already gone
            val nodes = nodes(xml)
            if (nodes.none { attr(it, "resource-id")?.endsWith(":id/$id") == true }) return false
            val focused = nodes.firstOrNull { attr(it, "focused") == "true" }?.let { attr(it, "resource-id") }
            if (focused?.endsWith(":id/$id") == true) {
                sh.exec("input keyevent KEYCODE_ENTER")
                return true
            }
            sh.exec("input keyevent KEYCODE_TAB")
        }
        return false
    }

    /** BACK only while the phone dialog has focus, so it can never land in Chalaiden or another app. */
    override fun back() {
        if (phoneFocused()) sh.exec("input keyevent 4")
    }

    override fun close() = Unit // the shell belongs to the driver

    private fun phoneFocused() = sh.exec(FOCUS).contains(PHONE)

    private data class Field(val text: String, val password: Boolean)

    private val nodeRe = Regex("""<node[^>]*?/>|<node[^>]*?>""")
    private fun nodes(xml: String) = nodeRe.findAll(xml).map { it.value }.toList()
    private fun attr(node: String, name: String) = Regex("""\b$name="([^"]*)"""").find(node)?.groupValues?.get(1)

    private fun field(xml: String): Field? {
        if (!xml.contains(PHONE)) return null
        val node = nodes(xml).firstOrNull {
            attr(it, "resource-id")?.endsWith(":id/input_field") == true || attr(it, "class") == "android.widget.EditText"
        } ?: return null
        return Field(unescape(attr(node, "text").orEmpty()), attr(node, "password") == "true")
    }

    private fun parse(xml: String, hasInput: Boolean): Dialog? {
        val nodes = nodes(xml)
        if (nodes.any { attr(it, "text")?.let(Prompt::isProgress) == true }) return Dialog.Progress
        val message = Prompt.MESSAGE_IDS.firstNotNullOfOrNull { id -> nodes.firstOrNull { attr(it, "resource-id") == id }?.let { attr(it, "text") }?.takeIf { it.isNotBlank() } }
            ?: nodes.filter { attr(it, "class")?.contains("Button") != true && attr(it, "class") != "android.widget.EditText" }
                .mapNotNull { attr(it, "text") }.filter { it.isNotBlank() }.maxByOrNull { it.length }
        val text = unescape(message.orEmpty()).trim()
        if (text.isEmpty()) return null
        return if (hasInput) Dialog.Prompt(text) else Dialog.Notice(text)
    }

    /** `input text` treats spaces specially; replies only ever contain letters, digits and spaces. */
    private fun shellArg(text: String) = text.replace(" ", "%s")

    private fun unescape(s: String) = s
        .replace("&#10;", "\n").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")

    private companion object {
        const val PHONE = "com.android.phone"
        // Deleted right after reading: the dump holds the confirmation details.
        const val DUMP = "uiautomator dump /data/local/tmp/cu.xml >/dev/null 2>&1 && cat /data/local/tmp/cu.xml; rm -f /data/local/tmp/cu.xml"
        // Every display's focus, not just the first listed: vivo lists an extra display first whose
        // focus is always null (V2445A, Android 15), so the first line never names the phone.
        const val FOCUS = "dumpsys window 2>/dev/null | grep mCurrentFocus"
    }
}
