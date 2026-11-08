package com.chalaiden.app.ussd

import java.io.Closeable
import java.io.IOException

/**
 * One long-lived privileged shell: root's `su`, or Shizuku's shell-level `sh` (see [Backend]). Commands are written to its stdin and the output read back up to a
 * per-command marker, so the shell itself never carries replies in its arguments. The `input text`
 * child it runs does, briefly; only root or the shell user can read another app's process arguments on Android.
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
            val line = reader.readLine() ?: throw IOException("privileged shell closed")
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
        /** Opens a privileged shell through [backend] (root by default), or null if it is unavailable or denied. */
        fun open(backend: Backend = Backend.Root): RootShell? = runCatching {
            val p = when (backend) {
                Backend.Root -> ProcessBuilder("su").redirectErrorStream(true).start()
                Backend.Shizuku -> ShizukuAccess.newShell()
            }
            RootShell(p).also { sh ->
                // Shizuku can't merge stderr into stdout like redirectErrorStream; the shell does it instead.
                val id = sh.exec(if (backend == Backend.Shizuku) "exec 2>&1; id" else "id")
                if (!backend.accepts(id)) { sh.close(); return null }
            }
        }.getOrNull()
    }
}
