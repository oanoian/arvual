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
        return mutableMapOf(
            "HOME" to home.absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath,
            "PATH" to "/system/bin:/vendor/bin:${File(context.applicationInfo.dataDir, "bin").absolutePath}",
            "LANG" to "en_US.UTF-8",
            "ANDROID_DATA" to context.dataDir.absolutePath,
        )
    }

    /** Run one command line synchronously off the main thread. */
    fun exec(context: Context, cwd: File, commandLine: String): ExecResult =
        executor.submit<ExecResult> { runBlocking(context, cwd, commandLine) }.get()

    private fun runBlocking(context: Context, cwd: File, commandLine: String): ExecResult {
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
            val finished = p.waitFor(60, TimeUnit.SECONDS)
            if (!finished) {
                p.destroy()
                return ExecResult(out.toString(), err.toString() + "\n[timed out after 60s]", 124)
            }
            t1.join(2000); t2.join(2000)
            ExecResult(out.toString(), err.toString(), p.exitValue())
        } catch (e: Exception) {
            ExecResult("", "${e.javaClass.simpleName}: ${e.message}", 127)
        }
    }
}
