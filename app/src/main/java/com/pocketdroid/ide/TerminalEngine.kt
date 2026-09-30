package com.pocketdroid.ide

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * App-space "virtual device" terminal. No root, no kernel access: everything
 * runs as a normal sandboxed Linux process tree under this app's UID, inside
 * the app-private workspace. Commands are executed with ProcessBuilder using a
 * small busybox-like shell (system /system/bin/sh) plus bundled tools later.
 *
 *   Filesystem layout (all under getExternalFilesDir or files dir):
 *     /data/data/com.pocketdroid.ide/files/rootfs      -> tool root (proot-style, optional)
 *     /sdcard/Android/data/com.pocketdroid.ide/files/Projects  -> user projects (cwd default)
 */
object TerminalEngine {

    data class ExecResult(val stdout: String, val stderr: String, val exitCode: Int)

    private val executor = Executors.newSingleThreadExecutor()

    fun shellEnv(context: Context, cwd: File): MutableMap<String, String> {
        val home = File(context.filesDir, "home").apply { mkdirs() }
        cwd.mkdirs()
        val rtRoot = File(context.filesDir, "runtimes")
        val ld = listOf(
            File(rtRoot, "python/lib"),
            File(rtRoot, "nodejs/lib"),
        ).filter { it.exists() }.joinToString(":")
        return mutableMapOf(
            "HOME" to home.absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath,
            // App bin dir first so installed runtimes (python/node/npm) win
            // over anything in /system/bin.
            "PATH" to "${File(context.filesDir, "bin").absolutePath}:/system/bin:/vendor/bin",
            "LD_LIBRARY_PATH" to ld,
            "PYTHONHOME" to File(rtRoot, "python").absolutePath,
            "NODE_PATH" to File(rtRoot, "nodejs/lib/node_modules").absolutePath,
            "LANG" to "en_US.UTF-8",
            "ANDROID_DATA" to context.dataDir.absolutePath,
        )
    }

    /** Run one command line synchronously off the main thread. */
    fun exec(context: Context, cwd: File, commandLine: String): ExecResult =
        executor.submit<ExecResult> { runBlocking(context, cwd, commandLine) }.get()

    /** Long-running commands (pip install, npm install) get 10 minutes. */
    fun execLong(context: Context, cwd: File, commandLine: String): ExecResult =
        executor.submit<ExecResult> { runBlocking(context, cwd, commandLine, 600) }.get()

    private fun runBlocking(
        context: Context, cwd: File, commandLine: String, timeoutSec: Long = 60,
    ): ExecResult {
        if (commandLine.isBlank()) return ExecResult("", "", 0)
        if (commandLine.trim() == "exit") return ExecResult("", "", -2)
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", commandLine)
                .directory(cwd)
                .redirectErrorStream(false)
            pb.environment().putAll(shellEnv(context, cwd))
            val p = pb.start()
            val out = StringBuilder()
            val err = StringBuilder()
            val t1 = Thread {
                BufferedReader(InputStreamReader(p.inputStream)).forEachLine { out.appendLine(it) }
            }
            val t2 = Thread {
                BufferedReader(InputStreamReader(p.errorStream)).forEachLine { err.appendLine(it) }
            }
            t1.start(); t2.start()
            val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                p.destroy()
                return ExecResult(out.toString(), err.toString() + "\n[timed out after ${timeoutSec}s]", 124)
            }
            t1.join(2000); t2.join(2000)
            ExecResult(out.toString(), err.toString(), p.exitValue())
        } catch (e: Exception) {
            ExecResult("", "${e.javaClass.simpleName}: ${e.message}", 127)
        }
    }
}
