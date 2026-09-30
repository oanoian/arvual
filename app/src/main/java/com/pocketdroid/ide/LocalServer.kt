package com.pocketdroid.ide

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

/**
 * LocalServer — in-app localhost web server (NanoHTTPD, pure Java socket).
 *
 * NOTE ON "kernel sockets": Android apps use the standard Linux TCP/IP stack
 * through normal POSIX sockets (java.net.ServerSocket -> Binder -> kernel).
 * No root or custom kernel module is needed or possible for a normal app;
 * this is exactly how Termux serves localhost too. Binding to 127.0.0.1 is
 * always permitted; binding external interfaces may require user action.
 */
class LocalServer private constructor(
    private val webRoot: File,
    port: Int
) : NanoHTTPD(port) {

    companion object {
        const val PORT = 8037 // fixed so the IDE browser panel can predict URLs

        @Volatile
        private var current: LocalServer? = null

        /** Serve [root] at http://localhost:PORT (replaces any running instance). */
        fun serve(root: File): String {
            stop()
            val s = LocalServer(root, PORT)
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            current = s
            return "http://localhost:$PORT/"
        }

        fun stop() {
            current?.stop()
            current = null
        }

        fun isRunning(): Boolean = current != null

        /** Convenience: static-serve the app's own files dir tree under /files/. */
        fun serveProject(context: Context, projectDir: File): String =
            serve(projectDir)
    }

    override fun serve(session: IHTTPSession): Response {
        var path = session.uri.removePrefix("/")
        if (path.isEmpty()) path = "index.html"
        // Prevent path traversal
        val canonicalRoot = webRoot.canonicalPath
        val target = File(webRoot, path).canonicalFile
        if (!target.canonicalPath.startsWith(canonicalRoot)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden")
        }
        val file = if (target.isDirectory) File(target, "index.html") else target
        if (!file.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found: $path")
        }
        val mime = guessMime(file.name)
        val resp = newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), file.length())
        resp.addHeader("Cache-Control", "no-store")
        return resp
    }

    private fun guessMime(name: String): String = when {
        name.endsWith(".html") || name.endsWith(".htm") -> "text/html; charset=utf-8"
        name.endsWith(".css") -> "text/css"
        name.endsWith(".js") || name.endsWith(".mjs") -> "application/javascript"
        name.endsWith(".json") -> "application/json"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
        name.endsWith(".svg") -> "image/svg+xml"
        name.endsWith(".ico") -> "image/x-icon"
        name.endsWith(".txt") -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }
}
