package br.com.lincon.phototool.desktop

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.util.UUID

/**
 * A library root pinned by a directory descriptor. Every write-mode lookup and rename is
 * relative to SecureDirectoryStream handles and every directory component is opened with
 * NOFOLLOW_LINKS. Hosts without that facility are deliberately read-only.
 */
internal class SecureLibraryBoundary(root: Path, writeRequired: Boolean) : AutoCloseable {
    private val rootStream: DirectoryStream<Path> = Files.newDirectoryStream(root.toRealPath())
    private val secureRoot = rootStream as? SecureDirectoryStream<Path>

    init {
        if (writeRequired && secureRoot == null) {
            rootStream.close()
            throw IllegalStateException("Secure descriptor-relative library access is unavailable")
        }
    }

    val writeAvailable: Boolean get() = secureRoot != null

    data class Entry(val name: String, val attributes: BasicFileAttributes)

    fun list(parent: String): List<Entry> = withDirectory(parent) { directory ->
        directory.mapNotNull { visible ->
            val name = visible.fileName.toString()
            attributes(directory, name)?.let { Entry(name, it) }
        }
    }

    fun exists(relative: String): Boolean = withParent(relative) { directory, name -> attributes(directory, name) != null }

    fun attributes(relative: String): BasicFileAttributes? = withParent(relative) { directory, name -> attributes(directory, name) }

    fun read(relative: String, maximum: Int): ByteArray = withParent(relative) { directory, name ->
        val attrs = attributes(directory, name) ?: throw NoSuchFileException(relative)
        require(attrs.isRegularFile && attrs.size() in 1..maximum.toLong()) { "Unsafe or oversized library file" }
        directory.newByteChannel(Path.of(name), setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            val output = ByteArray(attrs.size().toInt())
            var offset = 0
            while (offset < output.size) {
                val read = channel.read(ByteBuffer.wrap(output, offset, output.size - offset))
                if (read < 0) break
                offset += read
            }
            require(offset == output.size && channel.read(ByteBuffer.allocate(1)) < 0) { "Library file changed during read" }
            output
        }
    }

    fun publish(relative: String, bytes: ByteArray, previousBytes: ByteArray?, artifactExtension: String) =
        withParent(relative) { directory, name ->
            val temp = ".$name.${UUID.randomUUID()}.tmp"
            createAndSync(directory, temp, bytes)
            var previous: String? = null
            try {
                if (previousBytes == null) {
                    require(attributes(directory, name) == null) { "Authority appeared concurrently" }
                    directory.move(Path.of(temp), directory, Path.of(name))
                    return@withParent
                }
                require(readFrom(directory, name, previousBytes.size.coerceAtLeast(1) + 1).contentEquals(previousBytes)) { "Authority changed concurrently" }
                previous = uniqueArtifact(directory, name, "previous", artifactExtension)
                directory.move(Path.of(name), directory, Path.of(previous))
                if (!readFrom(directory, previous, previousBytes.size.coerceAtLeast(1) + 1).contentEquals(previousBytes)) {
                    val conflict = uniqueArtifact(directory, name, "conflict", artifactExtension)
                    directory.move(Path.of(previous), directory, Path.of(conflict))
                    previous = null
                    restore(directory, conflict, name, previousBytes)
                    error("Authority changed during replacement; recovery artifact retained")
                }
                try {
                    directory.move(Path.of(temp), directory, Path.of(name))
                } catch (failure: Exception) {
                    restore(directory, previous, name, previousBytes)
                    throw failure
                }
            } finally {
                runCatching { directory.deleteFile(Path.of(temp)) }
            }
        }

    private fun restore(directory: SecureDirectoryStream<Path>, preserved: String, canonical: String, bytes: ByteArray) {
        if (attributes(directory, canonical) != null) return
        require(readFrom(directory, preserved, bytes.size.coerceAtLeast(1) + 1).contentEquals(bytes))
        val restore = ".$canonical.restore.${UUID.randomUUID()}.tmp"
        createAndSync(directory, restore, bytes)
        try { directory.move(Path.of(restore), directory, Path.of(canonical)) }
        finally { runCatching { directory.deleteFile(Path.of(restore)) } }
    }

    private fun uniqueArtifact(directory: SecureDirectoryStream<Path>, canonical: String, kind: String, extension: String): String {
        repeat(100) {
            val candidate = ".$canonical.$kind.${UUID.randomUUID()}.$extension"
            if (attributes(directory, candidate) == null) return candidate
        }
        error("Could not reserve recovery artifact")
    }

    private fun createAndSync(directory: SecureDirectoryStream<Path>, name: String, bytes: ByteArray) {
        directory.newByteChannel(
            Path.of(name),
            setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
            *emptyArray<FileAttribute<*>>(),
        ).use { channel ->
            var buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            (channel as? java.nio.channels.FileChannel)?.force(true)
        }
    }

    private fun readFrom(directory: SecureDirectoryStream<Path>, name: String, maximum: Int): ByteArray {
        val attrs = attributes(directory, name) ?: throw NoSuchFileException(name)
        require(attrs.isRegularFile && attrs.size() in 1 until maximum.toLong())
        return directory.newByteChannel(Path.of(name), setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            val bytes = ByteArray(attrs.size().toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = channel.read(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                if (count < 0) break
                offset += count
            }
            require(offset == bytes.size)
            bytes
        }
    }

    private fun attributes(directory: SecureDirectoryStream<Path>, name: String): BasicFileAttributes? = try {
        directory.getFileAttributeView(Path.of(name), BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?.readAttributes()
    } catch (_: NoSuchFileException) { null }

    private fun <T> withParent(relative: String, block: (SecureDirectoryStream<Path>, String) -> T): T {
        val parts = validatedParts(relative)
        require(parts.isNotEmpty())
        return withDirectory(parts.dropLast(1).joinToString("/")) { block(it, parts.last()) }
    }

    private fun <T> withDirectory(relative: String, block: (SecureDirectoryStream<Path>) -> T): T {
        val root = secureRoot ?: throw IllegalStateException("Secure descriptor-relative library access is unavailable")
        val opened = mutableListOf<SecureDirectoryStream<Path>>()
        var current = root.newDirectoryStream(Path.of("."), LinkOption.NOFOLLOW_LINKS).also(opened::add)
        try {
            validatedParts(relative).forEach { part ->
                current = current.newDirectoryStream(Path.of(part), LinkOption.NOFOLLOW_LINKS)
                opened += current
            }
            return block(current)
        } finally { opened.asReversed().forEach { runCatching { it.close() } } }
    }

    private fun validatedParts(relative: String): List<String> {
        if (relative.isEmpty()) return emptyList()
        val path = Path.of(relative)
        require(!path.isAbsolute && path.normalize() == path && path.none { it.toString() in setOf("", ".", "..") }) { "Invalid library-relative path" }
        return path.map { it.toString() }
    }

    override fun close() = rootStream.close()
}
