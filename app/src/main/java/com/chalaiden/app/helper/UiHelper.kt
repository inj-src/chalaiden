package com.chalaiden.app.helper

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.os.HandlerThread
import android.os.Looper
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

    private fun handle(ui: UiAutomation, req: JSONObject): JSONObject {
        val dialog = PhoneDialog(windows = { ui.windows }, active = { ui.rootInActiveWindow })
        return when (req.getString("op")) {
            "look" -> dialog.look().let { s ->
                JSONObject().put("win", s.win).put("progress", s.progress).put("message", s.message)
                    .put("input", s.input).put("field", s.field).put("password", s.password)
            }
            "reply" -> dialog.reply(req.getString("text"), req.getString("expect"))
                .let { why -> JSONObject().put("ok", why == null).apply { if (why != null) put("why", why) } }
            "press" -> JSONObject().put("ok", dialog.press(req.getString("id")))
            else -> JSONObject().put("ok", false).put("why", "unknown op")
        }
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
