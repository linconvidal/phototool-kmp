package br.com.lincon.phototool.desktop

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*

private const val MAX_SUMMARY_BYTES = 16 * 1024
private const val GENERATION_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

data class SyncSummary(
    val outcome: String,
    val photos: Int,
    val errors: Int,
    val errorCode: String,
    val durationMillis: Long = 0,
    val added: Int = 0,
    val removed: Int = 0,
    val updated: Int = 0,
    val snapshotGeneration: String = "",
    val snapshotFingerprint: String = "",
)

class SyncSummaryStore(
    cacheDir: Path,
    private val directorySync: (Path) -> Unit = { path -> FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) } },
) {
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
        Regex("""\{"version":3,"outcome":"(success|failed|cancelled)","photos":(\d+),"errors":(\d+),"errorCode":"([a-z0-9-]{0,64})","durationMillis":(\d+),"added":(\d+),"removed":(\d+),"updated":(\d+),"snapshotGeneration":"($GENERATION_PATTERN)","snapshotFingerprint":"([0-9a-f]{64})"}\n?""").matchEntire(text)?.let { match ->
            return SyncSummary(match.groupValues[1], match.groupValues[2].toInt(), match.groupValues[3].toInt(), match.groupValues[4], match.groupValues[5].toLong(), match.groupValues[6].toInt(), match.groupValues[7].toInt(), match.groupValues[8].toInt(), match.groupValues[9], match.groupValues[10])
        }
        Regex("""\{"version":2,"outcome":"(success|failed|cancelled)","photos":(\d+),"errors":(\d+),"errorCode":"([a-z0-9-]{0,64})","durationMillis":(\d+),"added":(\d+),"removed":(\d+)}\n?""").matchEntire(text)?.let { match ->
            return SyncSummary(match.groupValues[1], match.groupValues[2].toInt(), match.groupValues[3].toInt(), match.groupValues[4], match.groupValues[5].toLong(), match.groupValues[6].toInt(), match.groupValues[7].toInt())
        }
        val legacy = Regex("""\{"version":1,"outcome":"(success|failed|cancelled)","photos":(\d+),"errors":(\d+),"errorCode":"([a-z0-9-]{0,64})"}\n?""").matchEntire(text) ?: return null
        return SyncSummary(legacy.groupValues[1], legacy.groupValues[2].toInt(), legacy.groupValues[3].toInt(), legacy.groupValues[4])
    }

    fun save(summary: SyncSummary) {
        require(summary.outcome in setOf("success", "failed", "cancelled") && summary.photos >= 0 && summary.errors >= 0 && summary.durationMillis >= 0 && summary.added >= 0 && summary.removed >= 0 && summary.updated >= 0)
        require(summary.errorCode.length <= 64 && summary.errorCode.all { it in 'a'..'z' || it in '0'..'9' || it == '-' })
        require(summary.snapshotGeneration.matches(Regex(GENERATION_PATTERN)) && summary.snapshotFingerprint.matches(Regex("[0-9a-f]{64}")))
        val bytes = "{\"version\":3,\"outcome\":\"${summary.outcome}\",\"photos\":${summary.photos},\"errors\":${summary.errors},\"errorCode\":\"${summary.errorCode}\",\"durationMillis\":${summary.durationMillis},\"added\":${summary.added},\"removed\":${summary.removed},\"updated\":${summary.updated},\"snapshotGeneration\":\"${summary.snapshotGeneration}\",\"snapshotFingerprint\":\"${summary.snapshotFingerprint}\"}\n".toByteArray()
        require(bytes.size <= MAX_SUMMARY_BYTES)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) require(safeRegular(target)) { "Unsafe sync summary" }
        val temp = Files.createTempFile(directory, ".sync-summary-", ".json")
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { it.write(ByteBuffer.wrap(bytes)); it.force(true) }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) require(safeRegular(target))
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING) }
            directorySync(directory)
        } finally { Files.deleteIfExists(temp) }
    }

    private fun safeRegular(path: Path): Boolean = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && runCatching { (Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1 }.getOrDefault(true)
    private fun escape(value: String) = buildString { value.forEach { character -> when (character) { '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); else -> if (character.code >= 32) append(character) } } }
    private fun unescape(value: String): String = value.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\")
}
