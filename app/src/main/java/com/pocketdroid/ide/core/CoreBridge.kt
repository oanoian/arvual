package com.pocketdroid.ide.core

import android.content.Context
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ---- Architecture note (the "socket to the kernel", honestly) ----------------
 * Android apps never talk to the kernel directly. Every system call goes through
 * Bionic libc -> syscall instruction, inside the app's own sandboxed UID with
 * SELinux enforcing. What Termux calls its "terminal" is exactly this: normal
 * POSIX process/socket APIs from userspace. There is no privileged channel and
 * none is needed.
 *
 * This CoreBridge is our equivalent of that channel, but as an in-app socket
 * abstraction: a single control plane that every subsystem (terminal, runtimes,
 * language servers, deploy server) connects through. It gives us:
 *   - one place defining the sandbox environment (env, cwd, limits)
 *   - event bus between layers (UI subscribes, engines publish)
 *   - lifecycle ownership so nothing leaks processes when the app dies
 */
object CoreBridge {

    /** One sandbox definition — everything a spawned process needs. */
    data class Sandbox(
        val home: File,
        val tmp: File,
        val cwd: File,
        val pathDirs: List<String>,
        val extraEnv: Map<String, String>,
        val memLimitMb: Int,      // advisory; enforced by ProcessHandle killer
        val timeoutSec: Long,
    )

    sealed class Event {
        data class TerminalOutput(val stream: String, val text: String) : Event()
        data class ProcessStarted(val id: Int, val cmd: String) : Event()
        data class ProcessFinished(val id: Int, val exit: Int) : Event()
        data class RuntimeStatus(val name: String, val state: String) : Event()
        data class ServerStatus(val url: String?, val root: String?) : Event()
        data class Diagnostic(val file: String, val line: Int, val col: Int,
                             val severity: String, val message: String) : Event()
    }

    private val listeners = CopyOnWriteArrayList<(Event) -> Unit>()
    lateinit var sandbox: Sandbox
        private set

    fun init(context: Context) {
        val files = context.filesDir
        val home = File(files, "home").apply { mkdirs() }
        val tmp = context.cacheDir
        val projects = File(context.getExternalFilesDir(null), "Projects")
            .apply { mkdirs() }
        val rtRoot = File(files, "runtimes")
        sandbox = Sandbox(
            home = home,
            tmp = tmp,
            cwd = projects,
            pathDirs = buildList {
                add(File(files, "bin").absolutePath)                 // runtime shims
                add(File(rtRoot, "python/bin").absolutePath)
                add(File(rtRoot, "nodejs/bin").absolutePath)
                add("/system/bin"); add("/vendor/bin")
            },
            extraEnv = mapOf(
                "PYTHONHOME" to File(rtRoot, "python").absolutePath,
                "NODE_PATH" to File(rtRoot, "nodejs/lib/node_modules").absolutePath,
                "LD_LIBRARY_PATH" to listOf(
                    File(rtRoot, "python/lib"), File(rtRoot, "nodejs/lib"),
                ).filter { it.exists() }.joinToString(":")
                    .ifBlank { "/system/lib64" },
                "LANG" to "en_US.UTF-8",
                "TERM" to "xterm-256color",
            ),
            memLimitMb = 512,
            timeoutSec = 60,
        )
    }

    fun envMap(): MutableMap<String, String> = mutableMapOf(
        "HOME" to sandbox.home.absolutePath,
        "TMPDIR" to sandbox.tmp.absolutePath,
        "PATH" to sandbox.pathDirs.joinToString(":"),
    ).apply { putAll(sandbox.extraEnv) }

    /** Publish an event on the control-plane bus. Thread-safe. */
    fun publish(e: Event) = listeners.forEach { runCatching { it(e) } }

    /** Subscribe; returns a token to unsubscribe. */
    fun subscribe(fn: (Event) -> Unit): () -> Unit {
        listeners += fn
        return { listeners -= fn }
    }
}
