package br.com.lincon.phototool.desktop

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

/**
 * A library root pinned by a directory descriptor. Every write-mode lookup and rename is
 * relative to SecureDirectoryStream handles and every directory component is opened with
 * NOFOLLOW_LINKS. Hosts without that facility are deliberately read-only.
 */
internal class SecureLibraryBoundary(
    root: Path,
    writeRequired: Boolean,
    private val authorityDisplaced: (() -> Unit)? = null,
    private val directorySyncObserver: ((String) -> Unit)? = null,
    private val readOpenedObserver: ((String) -> Unit)? = null,
    private val readOpeningObserver: ((String) -> Unit)? = null,
) : AutoCloseable {
    private val realRoot = root.toRealPath()
    private val rootStream: DirectoryStream<Path> = Files.newDirectoryStream(realRoot)
    private val secureRoot = rootStream as? SecureDirectoryStream<Path>
    private val directoryChannels = Collections.synchronizedMap(IdentityHashMap<SecureDirectoryStream<Path>, FileChannel>())
    init {
        try {
            if (writeRequired && secureRoot == null) {
                throw IllegalStateException("Secure descriptor-relative library access is unavailable")
            }
            secureRoot?.let(::pinDirectoryChannel)
        } catch (failure: Throwable) {
            runCatching { rootStream.close() }
            throw failure
        }
    }

    val writeAvailable: Boolean get() = secureRoot != null

    data class Entry(val name: String, val attributes: BasicFileAttributes)

    data class ExpectedEntry(
        val fileKey: String,
        val size: Long,
        val modifiedMillis: Long,
        val hardLinks: Int,
    )

    data class ReadExpectation(
        val fileKey: String,
        val size: Long,
        val modifiedMillis: Long,
    )

    fun list(parent: String): List<Entry> = withDirectory(parent) { directory ->
        directory.mapNotNull { visible ->
            val name = visible.fileName.toString()
            attributes(directory, name)?.let { Entry(name, it) }
        }
    }

    fun exists(relative: String): Boolean = withParent(relative) { directory, name, _ -> attributes(directory, name) != null }

    fun attributes(relative: String): BasicFileAttributes? = withParent(relative) { directory, name, _ -> attributes(directory, name) }

    fun read(relative: String, maximum: Int): ByteArray = withParent(relative) { directory, name, _ ->
        val attrs = attributes(directory, name) ?: throw NoSuchFileException(relative)
        require(attrs.isRegularFile && attrs.size() in 1..maximum.toLong()) { "Unsafe or oversized library file" }
        val expected = readExpectation(attrs)
        val first = readAttestedBytes(directory, name, relative, expected)
        val second = readAttestedBytes(directory, name, relative, expected)
        require(first.contentEquals(second)) { "Library file content was not stable across independent reads" }
        first
    }

    /**
     * Returns the bytes of a large media file from a single attested pass. The channel
     * is still pinned by descriptor, shared-locked, witness-checked and identity-checked
     * before and after the read, so a second full byte pass would only add I/O.
     */
    fun readOnceAttested(relative: String, maximum: Int): ByteArray = withParent(relative) { directory, name, _ ->
        val attrs = attributes(directory, name) ?: throw NoSuchFileException(relative)
        require(attrs.isRegularFile && attrs.size() in 1..maximum.toLong()) { "Unsafe or oversized library file" }
        val expected = readExpectation(attrs)
        readAttestedBytes(directory, name, relative, expected)
    }

    /**
     * Copies a path-dependent media input through a descriptor-relative handle into an
     * outside-library temporary. A second descriptor-relative pass must hash to the same
     * value before the copy is returned, so a path exchange cannot poison Kim/ImageIO.
     */
    fun copyVerifiedTo(
        relative: String,
        maximum: Long,
        expected: ReadExpectation,
        destination: Path,
    ): ByteArray = withParent(relative) { directory, name, _ ->
        require(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
        require(attributes(directory, name)?.let(::readExpectation) == expected) { "Media identity changed before secure copy" }
        require(expected.size in 1..maximum) { "Unsafe or oversized media file" }
        try {
            val copiedDigest = FileChannel.open(
                destination,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { output ->
                val digest = copyAndDigest(directory, name, relative, expected, output)
                output.force(true)
                digest
            }
            val confirmedDigest = digest(directory, name, relative, expected)
            require(copiedDigest.contentEquals(confirmedDigest)) { "Media content changed during secure copy" }
            require(Files.size(destination) == expected.size) { "Secure media copy is incomplete" }
            copiedDigest
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(destination) }
            throw failure
        }
    }

    fun readExpectation(relative: String): ReadExpectation? = attributes(relative)?.let(::readExpectation)

    fun expectation(relative: String): ExpectedEntry? {
        val attrs = attributes(relative) ?: return null
        val links = hardLinkCount(relative, attrs)
        return ExpectedEntry(attrs.fileKey()?.toString() ?: error("Stable authority identity unavailable"), attrs.size(), attrs.lastModifiedTime().toMillis(), links)
    }

    fun publish(
        relative: String,
        bytes: ByteArray,
        previousBytes: ByteArray?,
        artifactExtension: String,
        expectedEntry: ExpectedEntry? = null,
        beforeCommit: (() -> Unit)? = null,
    ) {
        withParent(relative) { directory, name, parent ->
            val temp = ".$name.${UUID.randomUUID()}.tmp"
            createAndSync(directory, temp, bytes)
            val installedEntry = captureEntry(directory, temp, parent, bytes)
            var previous: String? = null
            var recoverySource: String? = null
            var installationMoved = false
            try {
                if (previousBytes == null) {
                    try {
                        require(expectedEntry == null && attributes(directory, name) == null) { "Authority appeared concurrently" }
                        beforeCommit?.invoke()
                        require(attributes(directory, name) == null) { "Authority appeared during final validation" }
                        directory.move(Path.of(temp), directory, Path.of(name))
                        installationMoved = true
                        require(matchesInstalled(directory, name, parent, installedEntry, bytes)) { "Installed authority identity changed before fsync" }
                        syncDirectory(directory, "temp-to-authority")
                    } catch (failure: Exception) {
                        recoverFailedInstallation(directory, name, parent, installationMoved, installedEntry, bytes, null, null, failure)
                        throw failure
                    }
                    return@withParent
                }
                require(expectedEntry != null) { "Expected authority identity is required" }
                require(matchesExpectation(directory, name, parent, expectedEntry)) { "Authority identity or topology changed concurrently" }
                require(readFrom(directory, name, previousBytes.size.coerceAtLeast(1) + 1).contentEquals(previousBytes)) { "Authority changed concurrently" }
                previous = uniqueArtifact(directory, name, "previous", artifactExtension)
                directory.move(Path.of(name), directory, Path.of(previous))
                recoverySource = previous
                try {
                    syncDirectory(directory, "authority-to-previous")
                    authorityDisplaced?.invoke()
                    if (!matchesExpectation(directory, previous, parent, expectedEntry) || !readFrom(directory, previous, previousBytes.size.coerceAtLeast(1) + 1).contentEquals(previousBytes)) {
                        val conflict = uniqueArtifact(directory, name, "conflict", artifactExtension)
                        directory.move(Path.of(previous), directory, Path.of(conflict))
                        recoverySource = conflict
                        previous = null
                        syncDirectory(directory, "previous-to-conflict")
                        restore(directory, conflict, name, previousBytes)
                        error("Authority changed during replacement; recovery artifact retained")
                    }
                    require(attributes(directory, name) == null) { "Concurrent authority appeared before installation" }
                    beforeCommit?.invoke()
                    require(attributes(directory, name) == null) { "Concurrent authority appeared during final validation" }
                    directory.move(Path.of(temp), directory, Path.of(name))
                    installationMoved = true
                    require(matchesInstalled(directory, name, parent, installedEntry, bytes)) { "Installed authority identity changed before fsync" }
                    syncDirectory(directory, "temp-to-authority")
                } catch (failure: Exception) {
                    recoverFailedInstallation(directory, name, parent, installationMoved, installedEntry, bytes, recoverySource, previousBytes, failure)
                    throw failure
                }
            } finally {
                if (attributes(directory, temp) != null) {
                    directory.deleteFile(Path.of(temp))
                    syncDirectory(directory, "temp-cleanup")
                }
            }
        }
    }

    private fun captureEntry(
        directory: SecureDirectoryStream<Path>,
        name: String,
        parent: String,
        bytes: ByteArray,
    ): ExpectedEntry {
        val attrs = attributes(directory, name) ?: error("Installed entry disappeared")
        val captured = ExpectedEntry(
            attrs.fileKey()?.toString() ?: error("Stable installed identity unavailable"),
            attrs.size(),
            attrs.lastModifiedTime().toMillis(),
            hardLinkCount(parent, name, attrs),
        )
        require(captured.hardLinks == 1 && readFrom(directory, name, bytes.size.coerceAtLeast(1) + 1).contentEquals(bytes)) {
            "Installed entry could not be verified"
        }
        return captured
    }

    private fun matchesInstalled(
        directory: SecureDirectoryStream<Path>,
        name: String,
        parent: String,
        installed: ExpectedEntry,
        bytes: ByteArray,
    ): Boolean = matchesExpectation(directory, name, parent, installed) &&
        runCatching { readFrom(directory, name, bytes.size.coerceAtLeast(1) + 1).contentEquals(bytes) }.getOrDefault(false)

    /** Roll back only the exact entry moved from our staged temporary; concurrent authorities win. */
    private fun recoverFailedInstallation(
        directory: SecureDirectoryStream<Path>,
        canonical: String,
        parent: String,
        installationMoved: Boolean,
        installed: ExpectedEntry,
        installedBytes: ByteArray,
        preserved: String?,
        previousBytes: ByteArray?,
        originalFailure: Exception,
    ) {
        val canonicalPresent = try {
            attributes(directory, canonical) != null
        } catch (recoveryFailure: Throwable) {
            originalFailure.addSuppressed(recoveryFailure)
            return
        }
        var removedInstalledAuthority = false
        if (installationMoved && canonicalPresent) {
            if (!matchesInstalled(directory, canonical, parent, installed, installedBytes)) return
            try {
                directory.deleteFile(Path.of(canonical))
                removedInstalledAuthority = true
            } catch (recoveryFailure: Throwable) {
                originalFailure.addSuppressed(recoveryFailure)
            }
        } else if (canonicalPresent) {
            return
        }
        if (removedInstalledAuthority) {
            try {
                syncDirectory(directory, "failed-authority-removal")
            } catch (recoveryFailure: Throwable) {
                originalFailure.addSuppressed(recoveryFailure)
            }
        }
        val authorityStillAbsent = try {
            attributes(directory, canonical) == null
        } catch (recoveryFailure: Throwable) {
            originalFailure.addSuppressed(recoveryFailure)
            false
        }
        if (authorityStillAbsent && preserved != null && previousBytes != null) {
            try {
                restore(directory, preserved, canonical, previousBytes)
            } catch (recoveryFailure: Throwable) {
                originalFailure.addSuppressed(recoveryFailure)
            }
        }
    }

    private fun restore(directory: SecureDirectoryStream<Path>, preserved: String, canonical: String, bytes: ByteArray) {
        if (attributes(directory, canonical) != null) return
        require(readFrom(directory, preserved, bytes.size.coerceAtLeast(1) + 1).contentEquals(bytes))
        val restore = ".$canonical.restore.${UUID.randomUUID()}.tmp"
        createAndSync(directory, restore, bytes)
        try {
            directory.move(Path.of(restore), directory, Path.of(canonical))
            syncDirectory(directory, "restore-to-authority")
        } finally {
            if (attributes(directory, restore) != null) {
                directory.deleteFile(Path.of(restore))
                syncDirectory(directory, "restore-cleanup")
            }
        }
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
            (channel as? FileChannel ?: error("File fsync is unavailable")).force(true)
        }
    }

    private fun matchesExpectation(
        directory: SecureDirectoryStream<Path>,
        name: String,
        parent: String,
        expected: ExpectedEntry,
    ): Boolean {
        val attrs = attributes(directory, name) ?: return false
        if (!attrs.isRegularFile || attrs.fileKey()?.toString() != expected.fileKey || attrs.size() != expected.size ||
            attrs.lastModifiedTime().toMillis() != expected.modifiedMillis) return false
        return hardLinkCount(parent, name, attrs) == expected.hardLinks && expected.hardLinks == 1
    }

    private fun hardLinkCount(relative: String, attrs: BasicFileAttributes): Int {
        val parent = Path.of(relative).parent?.toString()?.replace('\\', '/') ?: ""
        return hardLinkCount(parent, Path.of(relative).fileName.toString(), attrs)
    }

    private fun hardLinkCount(parent: String, name: String, descriptorAttrs: BasicFileAttributes): Int {
        val path = realRoot.resolve(parent).resolve(name).normalize()
        val pathAttrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(pathAttrs.fileKey()?.toString() == descriptorAttrs.fileKey()?.toString()) { "Authority path no longer names the pinned entry" }
        return (Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as? Number)?.toInt()
            ?: error("Hard-link topology is unavailable")
    }

    /** Force the already-open descriptor-relative directory handle; never reopen a path. */
    private fun syncDirectory(directory: SecureDirectoryStream<Path>, transition: String) {
        directorySyncObserver?.invoke(transition)
        val channel = directoryChannels[directory] ?: error("Pinned directory fsync handle is unavailable")
        channel.force(true)
        val pinned = directory.getFileAttributeView(BasicFileAttributeView::class.java)?.readAttributes()
            ?: error("Pinned directory identity unavailable after fsync")
        require(pinned.isDirectory && pinned.fileKey() != null) { "Pinned directory identity changed during fsync" }
    }

    private fun pinDirectoryChannel(directory: SecureDirectoryStream<Path>): FileChannel {
        val before = directory.getFileAttributeView(BasicFileAttributeView::class.java)?.readAttributes()
            ?: error("Pinned directory identity unavailable")
        require(before.isDirectory && before.fileKey() != null)
        var channel: FileChannel? = null
        try {
            channel = directory.newByteChannel(
                Path.of("."),
                setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
            ) as? FileChannel ?: error("Directory fsync handle is unavailable")
            val after = directory.getFileAttributeView(BasicFileAttributeView::class.java)?.readAttributes()
                ?: error("Pinned directory identity unavailable")
            require(after.fileKey()?.toString() == before.fileKey()?.toString()) {
                "Directory identity changed while pinning fsync handle"
            }
            directoryChannels[directory] = channel
            return channel
        } catch (failure: Throwable) {
            runCatching { channel?.close() }
            throw failure
        }
    }

    private fun readExpectation(attributes: BasicFileAttributes): ReadExpectation = ReadExpectation(
        attributes.fileKey()?.toString() ?: error("Stable file identity unavailable"),
        attributes.size(),
        attributes.lastModifiedTime().toMillis(),
    )

    private fun copyAndDigest(
        directory: SecureDirectoryStream<Path>,
        name: String,
        relative: String,
        expected: ReadExpectation,
        output: FileChannel,
    ): ByteArray = withAttestedReadChannel(directory, name, relative, expected) { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocateDirect(1024 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= expected.size)
            buffer.flip()
            digest.update(buffer.asReadOnlyBuffer())
            while (buffer.hasRemaining()) output.write(buffer)
            buffer.clear()
        }
        require(total == expected.size)
        digest.digest()
    }

    private fun digest(
        directory: SecureDirectoryStream<Path>,
        name: String,
        relative: String,
        expected: ReadExpectation,
    ): ByteArray = withAttestedReadChannel(directory, name, relative, expected) { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocateDirect(1024 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= expected.size)
            buffer.flip(); digest.update(buffer); buffer.clear()
        }
        require(total == expected.size)
        digest.digest()
    }

    private fun readAttestedBytes(
        directory: SecureDirectoryStream<Path>,
        name: String,
        relative: String,
        expected: ReadExpectation,
    ): ByteArray = withAttestedReadChannel(directory, name, relative, expected) { channel ->
        val bytes = ByteArray(expected.size.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val count = channel.read(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            if (count < 0) break
            offset += count
        }
        require(offset == bytes.size && channel.read(ByteBuffer.allocate(1)) < 0) { "Library file changed during read" }
        bytes
    }

    /**
     * Ties the opened channel to the descriptor-relative directory entry by holding a
     * shared lock on it and probing a second opening. The JVM reports an overlapping lock
     * only when both channels name the same underlying file; a successful witness lock,
     * unsupported locking, or any attribute drift fails closed.
     */
    private fun <T> withAttestedReadChannel(
        directory: SecureDirectoryStream<Path>,
        name: String,
        relative: String,
        expected: ReadExpectation,
        block: (FileChannel) -> T,
    ): T {
        require(attributes(directory, name)?.let(::readExpectation) == expected) { "Library entry changed before opening" }
        readOpeningObserver?.invoke(relative)
        val input = directory.newByteChannel(Path.of(name), setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) as? FileChannel
            ?: error("Attested file reads require FileChannel")
        input.use { opened ->
            var heldLock: FileLock? = null
            try {
                heldLock = opened.tryLock(0L, Long.MAX_VALUE, true)
                    ?: error("Shared file locking is unavailable for attested reads")
                readOpenedObserver?.invoke(relative)
                require(attributes(directory, name)?.let(::readExpectation) == expected) { "Library entry changed after opening" }
                val witness = directory.newByteChannel(Path.of(name), setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) as? FileChannel
                    ?: error("Attested witness reads require FileChannel")
                witness.use { second ->
                    var witnessLock: FileLock? = null
                    var sameEntry = false
                    try {
                        witnessLock = second.tryLock(0L, Long.MAX_VALUE, true)
                        if (witnessLock == null) error("Witness file locking is unavailable")
                    } catch (_: OverlappingFileLockException) {
                        sameEntry = true
                    } finally {
                        runCatching { witnessLock?.release() }
                    }
                    require(sameEntry) { "Opened channel does not match the observed library entry" }
                }
                require(attributes(directory, name)?.let(::readExpectation) == expected) { "Library entry changed during attestation" }
                return block(opened).also {
                    require(attributes(directory, name)?.let(::readExpectation) == expected) { "Library entry changed during read" }
                }
            } finally {
                runCatching { heldLock?.release() }
            }
        }
    }

    private fun readFrom(directory: SecureDirectoryStream<Path>, name: String, maximum: Int): ByteArray {
        val attrs = attributes(directory, name) ?: throw NoSuchFileException(name)
        require(attrs.isRegularFile && attrs.size() in 1 until maximum.toLong())
        val expected = readExpectation(attrs)
        val first = readAttestedBytes(directory, name, name, expected)
        val second = readAttestedBytes(directory, name, name, expected)
        require(first.contentEquals(second)) { "Library authority was not stable across independent reads" }
        return first
    }

    private fun attributes(directory: SecureDirectoryStream<Path>, name: String): BasicFileAttributes? = try {
        directory.getFileAttributeView(Path.of(name), BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?.readAttributes()
    } catch (_: NoSuchFileException) { null }

    private fun <T> withParent(relative: String, block: (SecureDirectoryStream<Path>, String, String) -> T): T {
        val parts = validatedParts(relative)
        require(parts.isNotEmpty())
        val parent = parts.dropLast(1).joinToString("/")
        return withDirectory(parent) { block(it, parts.last(), parent) }
    }

    private fun <T> withDirectory(relative: String, block: (SecureDirectoryStream<Path>) -> T): T {
        val root = secureRoot ?: throw IllegalStateException("Secure descriptor-relative library access is unavailable")
        val opened = mutableListOf<SecureDirectoryStream<Path>>()
        try {
            var current = root.newDirectoryStream(Path.of("."), LinkOption.NOFOLLOW_LINKS).also(opened::add)
            pinDirectoryChannel(current)
            validatedParts(relative).forEach { part ->
                current = current.newDirectoryStream(Path.of(part), LinkOption.NOFOLLOW_LINKS)
                opened += current
                pinDirectoryChannel(current)
            }
            return block(current)
        } finally {
            opened.asReversed().forEach { stream ->
                runCatching { directoryChannels.remove(stream)?.close() }
                runCatching { stream.close() }
            }
        }
    }

    private fun validatedParts(relative: String): List<String> {
        if (relative.isEmpty()) return emptyList()
        val path = Path.of(relative)
        require(!path.isAbsolute && path.normalize() == path && path.none { it.toString() in setOf("", ".", "..") }) { "Invalid library-relative path" }
        return path.map { it.toString() }
    }

    override fun close() {
        runCatching { directoryChannels.remove(secureRoot)?.close() }
        rootStream.close()
    }
}
