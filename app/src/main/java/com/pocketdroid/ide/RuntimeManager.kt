package com.pocketdroid.ide

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads and manages on-device toolchain runtimes (Termux-style GPL
 * packages from the F-Droid Termux repo). No root, no kernel access needed:
 * each runtime is a self-contained set of ELF executables + shared libs that
 * run as normal sandboxed processes under this app's UID.
 *
 *   Layout:
 *     files/runtimes/python/     -> full CPython interpreter tree
 *     files/runtimes/nodejs/     -> node + npm + corepack
 *     files/bin/                 -> launcher shims (python, pip, node, npm...)
 *
 * Everything else (busybox, git) comes from /system/bin or launcher shims.
 */
object RuntimeManager {

    const val REPO_BASE = "https://packages.termux.dev/apt/termux-main/pool/main"

    data class RuntimeInfo(
        val name: String,          // "python" | "nodejs"
        val label: String,         // display
        val url: String,           // .deb download
        val sizeHintMB: Int,       // approximate
        val deps: List<String> = emptyList(), // other runtime names required first
    )

    /**
     * Known-good Termux package URLs (arm64-v8a). If a URL 404s after a repo
     * update, only that runtime fails to install and can be retried with an
     * updated constant here. python/node debs each contain their own libs +
     * stdlib/npm tree, so no separate dependency downloads are needed.
     */
    val available: List<RuntimeInfo> = listOf(
        RuntimeInfo(
            "python", "Python 3.12 (+pip, stdlib)",
            "$REPO_BASE/p/python-python3_3.12.7-1_aarch64.deb", 30,
        ),
        RuntimeInfo(
            "nodejs", "Node.js 20 LTS (+npm)",
            "$REPO_BASE/n/nodejs_20.16.0-2_aarch64.deb", 45,
        ),
    )

    fun installed(context: Context): Set<String> {
        val out = HashSet<String>()
        val root = rootDir(context)
        if (File(root, "python/bin/python3.12").canExecute() ||
            File(root, "python/bin/python").canExecute()) out += "python"
        if (File(root, "nodejs/bin/node").canExecute()) out += "nodejs"
        return out
    }

    fun rootDir(context: Context): File =
        File(context.filesDir, "runtimes").apply { mkdirs() }

    fun binDir(context: Context): File =
        File(context.filesDir, "bin").apply { mkdirs() }

    private val downloading = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()

    fun isBusy(): Boolean = downloading.get()

    /**
     * Install one runtime. [onProgress] receives (percent 0..100, message).
     * Callbacks arrive on the executor thread; caller must marshal to UI.
     */
    fun install(context: Context, name: String, onProgress: (Int, String) -> Unit) {
        check(downloading.compareAndSet(false, true)) { "install already running" }
        executor.submit {
            try {
                doInstall(context, name, onProgress)
            } catch (e: Exception) {
                onProgress(-1, "FAILED: ${e.message}")
            } finally {
                downloading.set(false)
            }
        }
    }

    private fun doInstall(context: Context, name: String, prog: (Int, String) -> Unit) {
        val info = available.first { it.name == name }
        prog(0, "Fetching ${info.label} (~${info.sizeHintMB} MB) ...")
        fetchAndExtract(context, info, prog)
        writeLaunchers(context)
        prog(100, "${info.label} installed.")
    }

    private fun fetchAndExtract(context: Context, info: RuntimeInfo, prog: (Int, String) -> Unit) {
        val cache = File(context.cacheDir, "dl").apply { mkdirs() }
        val deb = File(cache, "${info.name}.deb")
        httpDownload(info.url, deb) { pct -> prog(pct, "Downloading ${info.name} $pct%") }
        val dest = File(rootDir(context), info.name)
        dest.mkdirs()
        extractDeb(deb, dest, prog)
        deb.delete()
    }

    private fun httpDownload(url: String, out: File, onPct: (Int) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 30_000
            instanceFollowRedirects = true
        }
        conn.setRequestProperty("User-Agent", "PocketDroidIDE/1.0")
        val code = conn.responseCode
        if (code != 200) throw IllegalStateException("HTTP $code for $url")
        val total = conn.contentLength.coerceAtLeast(1)
        conn.inputStream.use { input ->
            out.outputStream().use { os ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    os.write(buf, 0, n); done += n
                    onPct(((done * 100) / total).toInt().coerceIn(0, 100))
                }
            }
        }
        conn.disconnect()
    }

    /**
     * A .deb is an `ar` archive containing data.tar.{xz,gz,zst}. Android's
     * /system/bin does NOT ship ar/tar, so we use Toybox (present on every
     * API 23+ device) twice: `toybox ar` to split the deb, then `toybox tar`
     * with the right compression flag.
     */
    private fun extractDeb(deb: File, dest: File, prog: (Int, String) -> Unit) {
        val work = File(deb.parentFile, "x_${deb.nameWithoutExtension}")
        work.deleteRecursively(); work.mkdirs()
        runTool(work, "toybox ar x ${deb.absolutePath}", "toybox ar failed")
        val dataTar = work.listFiles()?.firstOrNull { it.name.startsWith("data.tar") }
            ?: throw IllegalStateException("data.tar not found inside ${deb.name}")
        prog(95, "Extracting ${dataTar.name} ...")
        val flags = when {
            dataTar.name.endsWith(".xz") -> "-xJf"
            dataTar.name.endsWith(".gz") -> "-xzf"
            dataTar.name.endsWith(".zst") -> "--zstd -xf"
            else -> "-xf"
        }
        runTool(dest, "toybox tar $flags ${dataTar.absolutePath}", "tar extraction failed")
        work.deleteRecursively()
        // Fix permissions on every executable the tar dropped
        dest.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(dest).path
            if (rel.startsWith("bin/") || rel.startsWith("libexec/") ||
                (rel.startsWith("lib/") && f.extension == "so") ||
                f.name.endsWith(".so") || f.name.contains(".so.")) {
                f.setExecutable(true, false)
            }
        }
    }

    private fun runTool(dir: File, cmdLine: String, errMsg: String) {
        val pb = ProcessBuilder("/system/bin/sh", "-c", cmdLine).directory(dir)
        pb.environment()["PATH"] = "/system/bin:/vendor/bin"
        val p = pb.start()
        val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        val rc = p.waitFor()
        if (rc != 0) throw IllegalStateException("$errMsg (rc=$rc): ${err.take(200)}")
    }

    /**
     * Launcher shims in files/bin so plain `python`, `pip`, `node`, `npm`
     * work from the terminal. Termux binaries find their libs via bundled
     * RPATH ($ORIGIN/../lib); we additionally export LD_LIBRARY_PATH and
     * PYTHONHOME/NODE_PATH as belt-and-braces.
     */
    fun writeLaunchers(context: Context) {
        runCatching {
            com.pocketdroid.ide.core.CoreBridge.publish(
                com.pocketdroid.ide.core.CoreBridge.Event.RuntimeStatus("launchers", "written"))
        }
        val rt = rootDir(context)
        val bin = binDir(context)
        val py = "${rt.absolutePath}/python"
        val nd = "${rt.absolutePath}/nodejs"
        val shims = mapOf(
            "python" to """
                #!/system/bin/sh
                export LD_LIBRARY_PATH="$py/lib:${'$'}{LD_LIBRARY_PATH:-}"
                export PYTHONHOME="$py"
                exec "$py/bin/python3.12" "\$@"
            """.trimIndent(),
            "python3" to """
                #!/system/bin/sh
                exec "${bin.absolutePath}/python" "\$@"
            """.trimIndent(),
            "pip" to """
                #!/system/bin/sh
                exec "${bin.absolutePath}/python" -m pip "\$@"
            """.trimIndent(),
            "pip3" to """
                #!/system/bin/sh
                exec "${bin.absolutePath}/pip" "\$@"
            """.trimIndent(),
            "node" to """
                #!/system/bin/sh
                export LD_LIBRARY_PATH="$nd/lib:${'$'}{LD_LIBRARY_PATH:-}"
                exec "$nd/bin/node" "\$@"
            """.trimIndent(),
            "npm" to """
                #!/system/bin/sh
                export NODE_PATH="$nd/lib/node_modules"
                exec "$nd/bin/node" "$nd/lib/node_modules/npm/bin/npm-cli.js" "\$@"
            """.trimIndent(),
            "npx" to """
                #!/system/bin/sh
                exec "$nd/bin/node" "$nd/lib/node_modules/npm/bin/npx-cli.js" "\$@"
            """.trimIndent(),
        )
        for ((name, body) in shims) {
            val f = File(bin, name)
            f.writeText(body + "\n")
            f.setExecutable(true, false)
        }
    }
}
