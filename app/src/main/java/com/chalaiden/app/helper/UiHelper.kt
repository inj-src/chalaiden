package com.chalaiden.app.helper

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.os.Bundle
import android.os.HandlerThread
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.chalaiden.app.ussd.Prompt
import org.json.JSONObject
import kotlin.system.exitProcess

/**
 * Privileged helper, started as root or as Shizuku's shell user by [com.chalaiden.app.ussd.UssdDriver]
 * with `app_process` for the length of one session. It holds a single UiAutomation connection (what
 * `uiautomator dump` opens and closes on every call, at ~2 s each), so reading the phone's USSD dialog
 * takes milliseconds and answers go in with node actions instead of injected keys.
 *
 * Nothing is registered as an Accessibility service: the connection exists only while this process
 * runs, and the process exits when the app closes its stdin.
 *
 * Protocol: one JSON object per line on stdin, one JSON reply per line on stdout.
 *   {"op":"look"}                  -> {"win":bool, "progress":bool, "message":str, "input":bool, "field":str, "password":bool}
 *   {"op":"reply","text":str,"expect":str} -> {"ok":bool, "why":str?}   only if the message is still
 *                                  [expect]: set the field, check it, press Send
 *   {"op":"press","id":"button2"}  -> {"ok":bool}
 * The first line written is {"ready":true} or {"ready":false,"why":str}.
 */
object UiHelper {
    private const val PHONE = "com.android.phone"

    @JvmStatic
    fun main(args: Array<String>) {
        // The accessibility client posts to the main looper, which a bare app_process doesn't have.
        Looper.prepareMainLooper()
        val ui = try {
            connect()
        } catch (e: Throwable) {
            emit(JSONObject().put("ready", false).put("why", e.toString()))
            exitProcess(1)
        }
        emit(JSONObject().put("ready", true))
        Thread {
            val input = System.`in`.bufferedReader()
            while (true) {
                val line = input.readLine() ?: break
                val reply = runCatching { handle(ui, JSONObject(line)) }
                    .getOrElse { JSONObject().put("ok", false).put("why", it.toString()) }
                emit(reply)
            }
            // stdin closed: the app is done (or gone). Drop the connection with the process.
            runCatching { UiAutomation::class.java.getMethod("disconnect").invoke(ui) }
            exitProcess(0)
        }.start()
        Looper.loop()
    }

    private fun emit(o: JSONObject) {
        System.out.println(o.toString())
        System.out.flush()
    }

    private fun handle(ui: UiAutomation, req: JSONObject): JSONObject = when (req.getString("op")) {
        "look" -> look(ui)
        "reply" -> reply(ui, req.getString("text"), req.getString("expect"))
        "press" -> JSONObject().put("ok", press(phoneRoot(ui), req.getString("id")))
        else -> JSONObject().put("ok", false).put("why", "unknown op")
    }

    // --- reading ---

    /** The phone app's top-most window; OxygenOS stacks USSD dialogs and the previous one can linger. */
    private fun phoneRoot(ui: UiAutomation): AccessibilityNodeInfo? =
        ui.windows.filter { it.root?.packageName?.toString() == PHONE }.maxByOrNull { it.layer }?.root
            ?: ui.rootInActiveWindow?.takeIf { it.packageName?.toString() == PHONE }

    private fun flatten(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let(stack::add)
        }
        return out
    }

    private fun edit(nodes: List<AccessibilityNodeInfo>) =
        nodes.firstOrNull { it.isEditable || it.className?.toString() == "android.widget.EditText" }

    private fun look(ui: UiAutomation): JSONObject {
        val root = phoneRoot(ui) ?: return JSONObject().put("win", false)
        val nodes = flatten(root)
        val edit = edit(nodes)
        val progress = nodes.any { n -> n.text?.let { Prompt.isProgress(it.toString()) } == true }
        return JSONObject()
            .put("win", true)
            .put("progress", progress)
            .put("message", messageOf(nodes))
            .put("input", edit != null)
            .put("field", if (edit?.isPassword == true) "" else edit?.text?.toString().orEmpty())
            .put("password", edit?.isPassword == true)
    }

    private fun messageOf(nodes: List<AccessibilityNodeInfo>): String =
        (Prompt.MESSAGE_IDS.firstNotNullOfOrNull { id -> nodes.firstOrNull { it.viewIdResourceName == id && !it.text.isNullOrBlank() }?.text }
            ?: nodes.filter { !it.isEditable && it.className?.contains("Button") != true }
                .mapNotNull { it.text }.maxByOrNull { it.length })
            ?.toString().orEmpty()

    // --- acting ---

    /** Types [text] only into the prompt whose message is [expect]: the PIN must never land in another one. */
    private fun reply(ui: UiAutomation, text: String, expect: String): JSONObject {
        val root = phoneRoot(ui) ?: return JSONObject().put("ok", false).put("why", "no window")
        val nodes = flatten(root)
        if (messageOf(nodes).trim() != expect) return JSONObject().put("ok", false).put("why", "prompt changed")
        val edit = edit(nodes) ?: return JSONObject().put("ok", false).put("why", "no field")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return JSONObject().put("ok", false).put("why", "set text refused")
        }
        // Read it back before sending; a password field reports no text, so the PIN is trusted as set.
        edit.refresh()
        if (!edit.isPassword && edit.text?.toString() != text) {
            return JSONObject().put("ok", false).put("why", "text mismatch")
        }
        // Checked again just before Send, in case bKash replaced the prompt while the text went in.
        val now = phoneRoot(ui)
        if (now == null || messageOf(flatten(now)).trim() != expect) return JSONObject().put("ok", false).put("why", "prompt changed")
        if (!press(now, "button1")) return JSONObject().put("ok", false).put("why", "no send button")
        return JSONObject().put("ok", true)
    }

    private fun press(root: AccessibilityNodeInfo?, id: String): Boolean {
        root ?: return false
        val full = "android:id/$id"
        // vivo's OriginOS dialog answers the id lookup with nothing (V2445A, Android 15, 2026-11-12),
        // though its buttons carry the id; walking the tree finds them.
        val button = root.findAccessibilityNodeInfosByViewId(full).firstOrNull { it.isClickable }
            ?: flatten(root).firstOrNull { it.viewIdResourceName == full && it.isClickable }
            ?: return false
        return button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // --- connecting (hidden APIs; app_process has no hidden-API restrictions) ---

    private fun connect(): UiAutomation {
        val thread = HandlerThread("chalaiden-ui").apply { start() }
        val connection = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance()
        val iface = Class.forName("android.app.IUiAutomationConnection")
        val ui = runCatching {
            UiAutomation::class.java.getDeclaredConstructor(Looper::class.java, iface).newInstance(thread.looper, connection)
        }.getOrElse {
            UiAutomation::class.java.getDeclaredConstructor(Context::class.java, iface).newInstance(systemContext(), connection)
        }
        val cls = UiAutomation::class.java
        runCatching { cls.getMethod("connectWithTimeout", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType).invoke(ui, 0, 5_000L) }
            .recoverCatching { cls.getMethod("connect", Int::class.javaPrimitiveType).invoke(ui, 0) }
            .recoverCatching { cls.getMethod("connect").invoke(ui) }
            .getOrThrow()
        ui.serviceInfo = ui.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        return ui
    }

    private fun systemContext(): Context {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("systemMain").invoke(null)
        return at.getMethod("getSystemContext").invoke(thread) as Context
    }
}
