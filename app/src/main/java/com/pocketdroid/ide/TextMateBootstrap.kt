package com.pocketdroid.ide

import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import org.eclipse.tm4e.core.registry.IThemeSource
import java.io.InputStream

/**
 * Minimal TextMate bootstrap: a couple of built-in grammars (Kotlin, Java) and
 * a dark theme. Grammar/theme files are bundled in assets/grammars & assets/themes.
 */
object TextMateBootstrap {

    @Volatile
    private var initialized = false

    val languageScopeMap = mapOf(
        "kt" to "source.kotlin",
        "kts" to "source.kotlin",
        "java" to "source.java",
    )

    fun init() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return

            FileProviderRegistry.instance.addFileProvider(object :
                io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry.FileProvider {
                override fun provideInputSteam(path: String): InputStream? {
                    // Grammars reference each other by relative file name (e.g. "kotlin.json").
                    val direct = AssetsBundle.openAsset(path) ?: AssetsBundle.openAsset("grammars/$path")
                    return direct
                }
            })

            GrammarRegistry.instance.loadGrammarsFromGrammarFiles(
                listOf("kotlin.json", "java.json"),
                true,
            )

            ThemeRegistry.instance.loadTheme(
                IThemeSource.fromInputStream(
                    AssetsBundle.openAsset("themes/dark.json"),
                    "dark.json",
                    null,
                )
            )
            ThemeRegistry.instance.selectTheme("dark")

            initialized = true
        }
    }
}
