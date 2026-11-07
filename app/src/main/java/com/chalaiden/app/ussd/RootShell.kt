package com.chalaiden.app.ussd

import java.io.Closeable
import java.io.IOException

/**
 * One long-lived root shell. Commands are written to its stdin and the output read back up to a
 * per-command marker, so the shell itself never carries replies in its arguments. The `input text`
 * child it runs does, briefly; only root can read another app's process arguments on Android.
 * Calls are serialised, so several threads may share one shell.
 */
class RootShell private constructor(private val proc: Process) : Closeable {
    private val writer = proc.outputStream.bufferedWriter()
    private val reader = proc.inputStream.bufferedReader()

    /**
     * Runs [cmd] and returns everything it printed (stdout+stderr), without the marker line. Throws if
     * the shell dies first, so a command that never ran (restoring the keyboards) can't pass for done.
     */
    @Synchronized fun exec(cmd: String): String {
        val marker = "__CU_${System.nanoTime()}__"
        writer.write(cmd)
        writer.write("\necho $marker\n")
        writer.flush()
        val out = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: throw IOException("root shell closed")
            // Output without a final newline (uiautomator's XML) gets the marker glued to its last line.
            if (line.endsWith(marker)) {
                out.append(line.removeSuffix(marker))
                break
            }
            out.append(line).append('\n')
        }
        return out.toString()
    }

    /**
     * Not synchronized: it must not wait for a running [exec] (a ~2 s dump, or a hung `su`), because
     * the UI thread calls it. Killing the process ends that read, which then throws.
     */
    override fun close() {
        proc.destroy()
    }

    companion object {
        /** Opens a root shell, or null if root is unavailable or denied. */
        fun open(): RootShell? = runCatching {
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            RootShell(p).also { if (!it.exec("id").contains("uid=0")) { it.close(); return null } }
        }.getOrNull()

        /** Quick check used by setup and readiness, without holding a shell open. */
        fun isAvailable(): Boolean = open()?.use { true } ?: false
    }
}
