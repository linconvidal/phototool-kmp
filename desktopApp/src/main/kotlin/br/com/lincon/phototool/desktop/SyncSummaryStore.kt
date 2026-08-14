package br.com.lincon.phototool.desktop

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*

private const val MAX_SUMMARY_BYTES = 16 * 1024

data class SyncSummary(val outcome: String, val photos: Int, val errors: Int, val errorCode: String)

class SyncSummaryStore(cacheDir: Path) {
    private val directory = cacheDir.toAbsolutePath().normalize()
    private val target: Path

    init {
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory))
        target = directory.resolve("sync-summary.json")
    }

    fun load(): SyncSummary? {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null
        require(safeRegular(target) && Files.size(target) <= MAX_SUMMARY_BYTES)
        val text = Files.readString(target)
        val match = Regex("""\{"version":1,"outcome":"(success|failed|cancelled)","photos":(\d+),"errors":(\d+),"errorCode":"([a-z0-9-]{0,64})"}\n?""").matchEntire(text) ?: return null
        return SyncSummary(match.groupValues[1], match.groupValues[2].toInt(), match.groupValues[3].toInt(), match.groupValues[4])
    }

    fun save(summary: SyncSummary) {
        require(summary.outcome in setOf("success", "failed", "cancelled") && summary.photos >= 0 && summary.errors >= 0)
        require(summary.errorCode.length <= 64 && summary.errorCode.all { it in 'a'..'z' || it in '0'..'9' || it == '-' })
        val bytes = "{\"version\":1,\"outcome\":\"${summary.outcome}\",\"photos\":${summary.photos},\"errors\":${summary.errors},\"errorCode\":\"${summary.errorCode}\"}\n".toByteArray()
        require(bytes.size <= MAX_SUMMARY_BYTES)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) require(safeRegular(target)) { "Unsafe sync summary" }
        val temp = Files.createTempFile(directory, ".sync-summary-", ".json")
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { it.write(ByteBuffer.wrap(bytes)); it.force(true) }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) require(safeRegular(target))
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING) }
            runCatching { FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) } }
        } finally { Files.deleteIfExists(temp) }
    }

    private fun safeRegular(path: Path): Boolean = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && runCatching { (Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1 }.getOrDefault(true)
    private fun escape(value: String) = buildString { value.forEach { character -> when (character) { '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); else -> if (character.code >= 32) append(character) } } }
    private fun unescape(value: String): String = value.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\")
}
