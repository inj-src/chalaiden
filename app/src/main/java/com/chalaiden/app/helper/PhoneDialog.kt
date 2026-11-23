package com.chalaiden.app.helper

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.chalaiden.app.ussd.Prompt

/**
 * Reads and answers the phone app's USSD dialog through accessibility nodes. Shared by the two ways
 * Chalaiden gets at those nodes: the privileged [UiHelper] (a UiAutomation connection, for root and
 * Shizuku) and the accessibility service ([com.chalaiden.app.ussd.ChalaidenAccessibility]). Both hand in
 * the same two things: the windows on screen and the active window's root.
 *
 * Vendor differences in the dialog's layout belong here, so both methods get the fix at once.
 */
internal class PhoneDialog(
    private val windows: () -> List<AccessibilityWindowInfo>,
    private val active: () -> AccessibilityNodeInfo?,
) {
    /** One look. [message] is bKash's text; [field] the reply box's text ("" for a password box). */
    data class Seen(
        val win: Boolean,
        val progress: Boolean = false,
        val message: String = "",
        val input: Boolean = false,
        val field: String = "",
        val password: Boolean = false,
    )

    /** The phone app's top-most window; OxygenOS stacks USSD dialogs and the previous one can linger. */
    fun root(): AccessibilityNodeInfo? =
        windows().filter { it.root?.packageName?.toString() == PHONE }.maxByOrNull { it.layer }?.root
            ?: active()?.takeIf { it.packageName?.toString() == PHONE }

    fun look(): Seen {
        val root = root() ?: return Seen(win = false)
        val nodes = flatten(root)
        val edit = edit(nodes)
        return Seen(
            win = true,
            progress = nodes.any { n -> n.text?.let { Prompt.isProgress(it.toString()) } == true },
            message = messageOf(nodes),
            input = edit != null,
            field = if (edit?.isPassword == true) "" else edit?.text?.toString().orEmpty(),
            password = edit?.isPassword == true,
        )
    }

    /**
     * Types [text] only into the prompt whose message is [expect] and presses Send: the PIN must never
     * land in another one. Null when it was sent, else why nothing was.
     */
    fun reply(text: String, expect: String): String? {
        val root = root() ?: return "no window"
        val nodes = flatten(root)
        if (messageOf(nodes).trim() != expect) return "prompt changed"
        val edit = edit(nodes) ?: return "no field"
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "set text refused"
        // Read it back before sending; a password field reports no text, so the PIN is trusted as set.
        edit.refresh()
        if (!edit.isPassword && edit.text?.toString() != text) return "text mismatch"
        // Checked again just before Send, in case bKash replaced the prompt while the text went in.
        val now = root()
        if (now == null || messageOf(flatten(now)).trim() != expect) return "prompt changed"
        if (!press(now, "button1")) return "no send button"
        return null
    }

    /** Presses the dialog button android:id/[id]. */
    fun press(id: String): Boolean = press(root(), id)

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

    /**
     * Every window and its nodes, one line each ("id Class "text" edit password click"), for finding
     * where a new phone puts bKash's text. Not used by sessions.
     */
    fun tree(): List<String> = windows().sortedByDescending { it.layer }.flatMap { w ->
        val root = w.root
        listOf("window layer=${w.layer} type=${w.type} pkg=${root?.packageName} title=${w.title}") +
            root?.let(::flatten).orEmpty().map { n ->
                listOfNotNull(
                    "  " + (n.viewIdResourceName ?: "-"),
                    n.className?.toString()?.substringAfterLast('.'),
                    n.text?.let { "\"$it\"" },
                    "edit".takeIf { n.isEditable },
                    "password".takeIf { n.isPassword },
                    "click".takeIf { n.isClickable },
                ).joinToString(" ")
            }
    }

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

    private fun messageOf(nodes: List<AccessibilityNodeInfo>): String =
        (Prompt.MESSAGE_IDS.firstNotNullOfOrNull { id -> nodes.firstOrNull { it.viewIdResourceName == id && !it.text.isNullOrBlank() }?.text }
            ?: nodes.filter { !it.isEditable && it.className?.contains("Button") != true }
                .mapNotNull { it.text }.maxByOrNull { it.length })
            ?.toString().orEmpty()

    companion object {
        /** The app that hosts the USSD dialog on every phone checked so far (stock, OxygenOS, OriginOS). */
        const val PHONE = "com.android.phone"
    }
}
