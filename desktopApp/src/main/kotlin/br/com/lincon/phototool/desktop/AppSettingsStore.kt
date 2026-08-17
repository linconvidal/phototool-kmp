package br.com.lincon.phototool.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Persists the in-app write preference inside the cache directory. The preference is the
 * only source of truth for whether XMP/FP2 editing is enabled; CLI flags only seed the
 * initial value on the first run, before any preference has been saved.
 */
internal class AppSettingsStore(private val cacheDir: Path) {
    private val file = cacheDir.resolve("settings.properties")

    fun loadWriteEnabled(): Boolean? = runCatching {
        if (!Files.isRegularFile(file)) return null
        val props = java.util.Properties()
        Files.newInputStream(file).use { props.load(it) }
        props.getProperty("writeEnabled")?.toBooleanStrictOrNull()
    }.getOrNull()

    fun saveWriteEnabled(enabled: Boolean) = runCatching {
        Files.createDirectories(cacheDir)
        val props = java.util.Properties().apply { setProperty("writeEnabled", enabled.toString()) }
        Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use {
            props.store(it, "phototool-kmp preferences")
        }
    }
}
