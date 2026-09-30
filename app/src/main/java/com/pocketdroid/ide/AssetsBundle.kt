package com.pocketdroid.ide

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Workspace storage. Everything lives inside the app-private external files
 * directory (no runtime permissions needed on any Android version):
 *
 *   /sdcard/Android/data/com.pocketdroid.ide/files/Projects
 *
 * On a real device this folder is reachable over USB / "Files" apps, and it is
 * exactly where an on-device Gradle build would run later.
 */
object AssetsBundle {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun openAsset(path: String): InputStream? = try {
        appContext.assets.open(path)
    } catch (_: Exception) {
        null
    }

    val projectsRoot: File
        get() {
            val dir = File(appContext.getExternalFilesDir(null), "Projects")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun projectDir(name: String): File {
        val safe = name.trim().replace(Regex("[^A-Za-z0-9_\\- ]"), "_").ifBlank { "untitled" }
        return File(projectsRoot, safe)
    }

    fun listProjects(): List<File> =
        projectsRoot.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    /** Recursively list files under [root], sorted with directories first. */
    fun tree(root: File): List<File> {
        val out = ArrayList<File>()
        fun walk(dir: File, depth: Int) {
            if (depth > 12) return
            val kids = dir.listFiles()?.sortedWith(
                compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
            ) ?: return
            for (f in kids) {
                if (f.name.startsWith(".git")) continue
                out += f
                if (f.isDirectory) walk(f, depth + 1)
            }
        }
        walk(root, 0)
        return out
    }

    fun readText(file: File): String = FileInputStream(file).use { it.readBytes().toString(Charsets.UTF_8) }

    fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}
