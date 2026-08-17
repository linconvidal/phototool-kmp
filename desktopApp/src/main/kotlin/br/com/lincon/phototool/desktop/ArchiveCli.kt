package br.com.lincon.phototool.desktop

import br.com.lincon.phototool.domain.MediaKind
import br.com.lincon.phototool.domain.caseFoldText
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val HASH_BUFFER_BYTES = 1024 * 1024
private const val PROCESS_OUTPUT_LIMIT = 1024 * 1024
private const val PROCESS_TIMEOUT_MILLIS = 120_000L
private const val RSYNC_REVIEW_LINE_LIMIT = 200
private const val MAX_IMPORT_OBSERVATION_BYTES = 64L * 1024 * 1024 * 1024
private const val IMPORT_MARKER_MAX_BYTES = 256
private const val MAX_CAPTURE_READ_BYTES = 1024L * 1024 * 1024
private const val IMPORT_MARKER_WAIT_MILLIS = 30_000L
private const val IMPORT_MARKER_INITIALIZATION_MILLIS = 1_000L
private const val UNKNOWN_DATE = "Unknown-Date"
private val PHOTO_EXTENSIONS = setOf("raf", "cr2", "cr3", "nef", "arw", "raw", "dng", "rw2", "orf", "pef", "srw", "jpg", "jpeg", "heic", "png", "tif", "tiff")
private val VIDEO_EXTENSIONS = setOf("mov", "mp4", "avi", "m4v")
private val SIDECAR_EXTENSIONS = setOf("xmp", "photo-edit", "fp2", "fp3")

internal data class ArchiveProcessResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean = false, val truncated: Boolean = false)

internal fun interface ArchiveProcessRunner {
    fun run(arguments: List<String>, timeoutMillis: Long, maximumOutputBytes: Int): ArchiveProcessResult
}

internal class SystemArchiveProcessRunner : ArchiveProcessRunner {
    override fun run(arguments: List<String>, timeoutMillis: Long, maximumOutputBytes: Int): ArchiveProcessResult {
        require(arguments.isNotEmpty())
        require(System.getProperty("os.name").lowercase().contains("linux")) { "isolamento comprovável de processos está disponível somente no Linux" }
        val setsid = resolveFixedExecutable(listOf("/usr/bin/setsid", "/bin/setsid"), "setsid")
        val kill = resolveFixedExecutable(listOf("/bin/kill", "/usr/bin/kill"), "kill")
        setsid.revalidate()
        kill.revalidate()
        val process = ProcessBuilder(listOf(setsid.path.toString(), "--wait") + arguments).start()
        val processGroup = process.pid()
        val stdout = BoundedProcessOutput(maximumOutputBytes)
        val stderr = BoundedProcessOutput(maximumOutputBytes)
        val outThread = thread(name = "archive-command-stdout", isDaemon = true) { process.inputStream.use(stdout::copyFrom) }
        val errThread = thread(name = "archive-command-stderr", isDaemon = true) { process.errorStream.use(stderr::copyFrom) }
        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) terminateProcessGroup(processGroup, process, kill)
        else if (processGroupAlive(processGroup, kill)) {
            terminateProcessGroup(processGroup, process, kill)
            throw IllegalStateException("o processo terminou deixando descendentes no grupo isolado")
        }
        runCatching { process.outputStream.close() }
        if (!finished) {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
        outThread.join(5_000)
        errThread.join(5_000)
        check(!outThread.isAlive && !errThread.isAlive) { "não foi possível encerrar os coletores do processo" }
        setsid.revalidate()
        kill.revalidate()
        return ArchiveProcessResult(
            exitCode = if (finished) process.exitValue() else -1,
            stdout = stdout.text(),
            stderr = stderr.text(),
            timedOut = !finished,
            truncated = stdout.truncated || stderr.truncated,
        )
    }

    private fun terminateProcessGroup(group: Long, process: Process, kill: ExecutablePin) {
        signalGroup(kill, "-TERM", group)
        process.waitFor(1_000, TimeUnit.MILLISECONDS)
        if (processGroupAlive(group, kill)) signalGroup(kill, "-KILL", group)
        process.waitFor(4_000, TimeUnit.MILLISECONDS)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (processGroupAlive(group, kill) && System.nanoTime() < deadline) Thread.sleep(10)
        check(!processGroupAlive(group, kill)) { "não foi possível encerrar todo o grupo isolado do processo" }
    }

    private fun signalGroup(kill: ExecutablePin, signal: String, group: Long) {
        kill.revalidate()
        val result = ProcessBuilder(kill.path.toString(), signal, "--", "-$group").start()
        check(result.waitFor(2, TimeUnit.SECONDS) && result.exitValue() == 0) { "não foi possível sinalizar o grupo isolado" }
    }

    private fun processGroupAlive(group: Long, kill: ExecutablePin): Boolean {
        kill.revalidate()
        val result = ProcessBuilder(kill.path.toString(), "-0", "--", "-$group").start()
        check(result.waitFor(2, TimeUnit.SECONDS)) { "não foi possível consultar o grupo isolado" }
        return result.exitValue() == 0
    }

    private class BoundedProcessOutput(private val maximum: Int) {
        private val bytes = ByteArrayOutputStream()
        @Volatile var truncated = false
            private set

        fun copyFrom(input: java.io.InputStream) {
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                synchronized(bytes) {
                    val remaining = maximum - bytes.size()
                    if (remaining > 0) bytes.write(buffer, 0, count.coerceAtMost(remaining))
                    if (count > remaining) truncated = true
                }
            }
        }

        fun text(): String = synchronized(bytes) { bytes.toString(Charsets.UTF_8) }
    }
}

internal fun interface CaptureDateReader {
    /** Returns the civil metadata year/month, or null when metadata is absent or invalid. */
    fun captureMonth(path: Path): YearMonth?
}

internal class KimCaptureDateReader(
    private val observer: MediaObservationAdapter = KimMediaObservationAdapter(),
) : CaptureDateReader {
    override fun captureMonth(path: Path): YearMonth? {
        val kind = when (path.extensionLower()) {
            in setOf("jpg", "jpeg") -> MediaKind.JPEG
            in PHOTO_EXTENSIONS -> MediaKind.RAW
            else -> return null
        }
        val size = Files.size(path)
        if (size <= 0 || size > MAX_CAPTURE_READ_BYTES) {
            throw IllegalStateException("não foi possível ler metadata de captura (metadata-source-size)")
        }
        val observed = observer.observe(Files.readAllBytes(path), kind)
        val civil = br.com.lincon.phototool.domain.captureGregorianDate(observed.capturedAt)
        if (civil == null && observed.status == br.com.lincon.phototool.domain.MetadataStatus.ERROR) {
            throw IllegalStateException("não foi possível ler metadata de captura (${observed.errorCode ?: "metadata-read-failed"})")
        }
        civil ?: return null
        return YearMonth.of(civil.year, civil.month)
    }
}

internal class ArchiveCli(
    private val dateReader: CaptureDateReader = KimCaptureDateReader(),
    private val processRunner: ArchiveProcessRunner = SystemArchiveProcessRunner(),
    private val output: (String) -> Unit = ::println,
    private val beforeCopyPublish: (Path) -> Unit = {},
    private val beforeVerifyHash: () -> Unit = {},
    private val storageIsIndependent: ((Path, Path) -> Boolean)? = null,
) {
    fun run(arguments: Array<String>): Int {
        if (arguments.firstOrNull() != "archive") return 2
        return try {
            when (arguments.getOrNull(1)) {
                "verify" -> runVerify(parseOptions(arguments.drop(2), setOf("--card", "--archive"), setOf("--size-only")))
                "import" -> runImport(parseOptions(arguments.drop(2), setOf("--source", "--destination"), setOf("--skip-videos")))
                "rsync" -> runRsync(parseOptions(arguments.drop(2), setOf("--source", "--destination"), setOf("--exclude-videos", "--checksum", "--delete")))
                else -> usage("Subcomando de arquivo ausente ou inválido")
            }
        } catch (error: CliUsageException) {
            usage(error.message ?: "Uso inválido")
        } catch (error: Exception) {
            output("ERRO: ${boundedMessage(error)}")
            1
        }
    }

    private fun runVerify(options: ParsedOptions): Int {
        val card = options.path("--card")
        val archive = options.path("--archive")
        val roots = validateRoots(card, archive)
        val checksum = "--size-only" !in options.flags
        val rootPins = RootPins(roots.first, roots.second)
        val independentStore = storageIsIndependent?.invoke(roots.first, roots.second) ?: rootPins.areOnDifferentFileStores()
        rootPins.revalidate()
        if (!independentStore) output("BLOQUEADO: cartão e arquivo estão no mesmo FileStore/volume; o diagnóstico continuará, mas não pode autorizar apagar.")
        val result = ArchiveVerifier(output, beforeVerifyHash).verify(roots.first, roots.second, checksum, rootPins)
        output("Verificação: ${result.ok} OK; ${result.missing} ausentes; ${result.mismatch} divergentes; ${result.unreadable} ilegíveis; ${result.unsafe} inseguras.")
        return if (checksum && result.safe && independentStore) {
            output("SEGURO PARA APAGAR — todos os arquivos reais e visíveis do cartão têm cópia SHA-256 idêntica em outro FileStore.")
            output("Mantenha o cartão quiescente e preferencialmente somente leitura até a decisão humana de apagar. FileStore distinto não comprova hardware físico distinto.")
            0
        } else {
            if (!checksum && result.missing == 0 && result.mismatch == 0 && result.unreadable == 0 && result.unsafe == 0) {
                output("INCONCLUSIVO — tamanho não comprova integridade. Não é seguro apagar; repita sem --size-only em volumes distintos.")
            } else if (!independentStore && result.safe) {
                output("NÃO É SEGURO APAGAR — hashes conferem, mas cartão e arquivo pertencem ao mesmo FileStore/volume.")
            } else output("NÃO É SEGURO APAGAR — resolva as divergências e repita a verificação por checksum.")
            1
        }
    }

    private fun runImport(options: ParsedOptions): Int {
        val source = options.path("--source")
        val destination = options.path("--destination")
        val roots = validateRoots(source, destination)
        val result = ArchiveImporter(dateReader, output, beforeCopyPublish).import(roots.first, roots.second, "--skip-videos" in options.flags)
        output("Importação: ${result.copied} copiados; ${result.skipped} idênticos ignorados; ${result.renamed} conflitos preservados; ${result.unsupported} não compatíveis; ${result.failed} falhas.")
        return if (result.failed == 0) 0 else 1
    }

    private fun runRsync(options: ParsedOptions): Int {
        val roots = validateRoots(options.path("--source"), options.path("--destination"))
        val delete = "--delete" in options.flags
        val sourceScan = SecureTree(roots.first).use { it.scan() }
        val destinationScan = SecureTree(roots.second).use { it.scan() }
        if (sourceScan.unsafe.isNotEmpty() || sourceScan.unreadable.isNotEmpty() || destinationScan.unsafe.isNotEmpty() || destinationScan.unreadable.isNotEmpty()) {
            output("RECUSADO: uma raiz contém links, mountpoint/filesystem interno, entrada ilegal ou ilegível.")
            return 1
        }
        if (delete && sourceScan.files.isEmpty()) {
            output("RECUSADO: a origem está vazia e --delete indicaria remoção total do destino. Verifique se a unidade está montada.")
            return 1
        }
        val rootPins = RootPins(roots.first, roots.second)
        val executable = resolveFixedExecutable(listOf("/usr/bin/rsync", "/bin/rsync"), "rsync")
        val base = buildList {
            add(executable.path.toString()); add("-ah"); add("--exclude=.*")
            if ("--checksum" in options.flags) add("--checksum")
            if ("--exclude-videos" in options.flags) addAll(videoExcludes())
            if (delete) add("--delete")
        }
        val sourceArg = roots.first.toString().trimEnd('/') + "/"
        val destinationArg = roots.second.toString().trimEnd('/') + "/"
        val dryArguments = base + listOf("--dry-run", "-i", "--", sourceArg, destinationArg)
        rootPins.revalidate()
        val preview = runBounded(dryArguments, executable)
        rootPins.revalidate()
        output("PRÉVIA DIAGNÓSTICA — NÃO EXECUTADA. Nenhuma opção ou token habilita sincronização textual neste build.")
        outputRsyncPreview(preview.stdout)
        if (preview.stderr.isNotBlank()) output("Avisos da prévia: ${preview.stderr.trim().take(2_000)}")
        output("RECUSADO: toda execução real de rsync, com ou sem --delete, está deliberadamente indisponível porque caminhos textuais mantêm janela TOCTOU.")
        return 1
    }

    private fun runBounded(arguments: List<String>, executable: ExecutablePin): ArchiveProcessResult {
        executable.revalidate()
        val result = try { processRunner.run(arguments, PROCESS_TIMEOUT_MILLIS, PROCESS_OUTPUT_LIMIT) }
        catch (error: Exception) { throw IllegalStateException("não foi possível executar rsync: ${boundedMessage(error)}") }
        check(!result.timedOut) { "rsync excedeu o limite de tempo" }
        check(!result.truncated) { "a saída do rsync excedeu o limite seguro" }
        check(result.exitCode == 0) { "rsync falhou (${result.exitCode}): ${result.stderr.take(500)}" }
        return result
    }

    private fun outputRsyncPreview(text: String) {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        val deletionLines = lines.filter { it.startsWith("*deleting") }
        val ordinary = lines.filterNot { it.startsWith("*deleting") }.take(RSYNC_REVIEW_LINE_LIMIT)
        output("Prévia itemizada: ${lines.size} alterações, ${deletionLines.size} exclusões.")
        ordinary.forEach { output(it.take(500)) }
        deletionLines.forEach { output(it.take(500)) }
        if (lines.size > RSYNC_REVIEW_LINE_LIMIT) output("PRÉVIA EXTENSA: total real ${lines.size}; linhas comuns limitadas a $RSYNC_REVIEW_LINE_LIMIT, todas as exclusões foram exibidas.")
    }

    private fun usage(message: String): Int {
        output("ERRO DE USO: $message")
        output("Uso: archive verify --card CARD --archive ARCHIVE [--size-only]")
        output("     archive import --source CARD --destination ARCHIVE [--skip-videos]")
        output("     archive rsync --source SRC --destination DST [--exclude-videos] [--checksum] [--delete] (somente prévia; nunca executa)")
        return 2
    }
}

private data class ParsedOptions(val values: Map<String, String>, val flags: Set<String>) {
    fun path(name: String): Path = Path.of(values[name] ?: throw CliUsageException("opção obrigatória ausente: $name"))
}

private class CliUsageException(message: String) : IllegalArgumentException(message)

private fun parseOptions(tokens: List<String>, requiredValues: Set<String>, flagsAllowed: Set<String>, optionalValues: Set<String> = emptySet()): ParsedOptions {
    val values = linkedMapOf<String, String>()
    val flags = linkedSetOf<String>()
    var index = 0
    val allValues = requiredValues + optionalValues
    while (index < tokens.size) {
        val token = tokens[index]
        when {
            token in flagsAllowed -> {
                if (!flags.add(token)) throw CliUsageException("opção repetida: $token")
                index++
            }
            token in allValues -> {
                if (token in values) throw CliUsageException("opção repetida: $token")
                val value = tokens.getOrNull(index + 1)?.takeIf { !it.startsWith("--") }
                    ?: throw CliUsageException("valor ausente para $token")
                values[token] = value
                index += 2
            }
            else -> throw CliUsageException("opção desconhecida: $token")
        }
    }
    val missing = requiredValues - values.keys
    if (missing.isNotEmpty()) throw CliUsageException("opção obrigatória ausente: ${missing.sorted().joinToString()}")
    return ParsedOptions(values, flags)
}

private data class StoreIdentity(val store: FileStore, val name: String, val type: String) {
    override fun toString(): String = "$name/$type/${store}"
}
private data class RootIdentity(val path: Path, val key: String, val store: StoreIdentity)

private fun rootIdentity(path: Path): RootIdentity {
    require(Files.isDirectory(path)) { "a raiz deve ser um diretório real e acessível: $path" }
    val real = path.toRealPath()
    require(Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(real)) { "a raiz canônica deve ser um diretório real: $path" }
    val attributes = Files.readAttributes(real, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    val key = attributes.fileKey()?.toString() ?: throw IllegalStateException("identidade estável indisponível para a raiz")
    val store = Files.getFileStore(real)
    return RootIdentity(real, key, StoreIdentity(store, store.name(), store.type()))
}

private class RootPins(first: Path, second: Path) {
    private val identities = listOf(rootIdentity(first), rootIdentity(second))
    fun revalidate() {
        identities.forEach { expected ->
            val actual = rootIdentity(expected.path)
            check(actual.path == expected.path && actual.key == expected.key && actual.store == expected.store) { "a identidade de uma raiz mudou durante a operação" }
        }
    }

    fun requireOpened(index: Int, attributes: BasicFileAttributes) {
        check(attributes.isDirectory && attributes.fileKey()?.toString() == identities[index].key) { "o descritor abriu uma raiz diferente da identidade fixada" }
    }

    fun areOnDifferentFileStores(): Boolean = storesAreDifferent(identities[0], identities[1])
}

private fun validateRoots(first: Path, second: Path): Pair<Path, Path> {
    val identities = try { rootIdentity(first) to rootIdentity(second) }
    catch (error: Exception) { throw CliUsageException("raízes inválidas: ${boundedMessage(error)}") }
    val a = identities.first
    val b = identities.second
    if (samePhysicalEntry(a, b) || physicalAncestors(a.path).any { samePhysicalEntry(it, b) } || physicalAncestors(b.path).any { samePhysicalEntry(it, a) }) {
        throw CliUsageException("as raízes devem ser fisicamente distintas e não sobrepostas")
    }
    return a.path to b.path
}

private fun samePhysicalEntry(first: RootIdentity, second: RootIdentity): Boolean {
    val firstDevice = deviceFromFileKey(first.key)
    val secondDevice = deviceFromFileKey(second.key)
    return if (firstDevice != null && secondDevice != null) firstDevice == secondDevice && first.key == second.key
    else first.store == second.store && first.key == second.key
}

private fun storesAreDifferent(first: RootIdentity, second: RootIdentity): Boolean {
    val firstDevice = deviceFromFileKey(first.key)
    val secondDevice = deviceFromFileKey(second.key)
    return if (firstDevice != null && secondDevice != null) firstDevice != secondDevice else first.store != second.store
}

private fun physicalAncestors(path: Path): Sequence<RootIdentity> = sequence {
    var current = path.parent
    while (current != null) {
        yield(rootIdentity(current))
        current = current.parent
    }
}

private data class ExecutablePin(val path: Path, val key: String, val store: StoreIdentity, val size: Long, val modifiedMillis: Long) {
    fun revalidate() {
        val real = path.toRealPath()
        val attributes = Files.readAttributes(real, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val fileStore = Files.getFileStore(real)
        check(real == path && attributes.isRegularFile && attributes.fileKey()?.toString() == key &&
            StoreIdentity(fileStore, fileStore.name(), fileStore.type()) == store && attributes.size() == size &&
            attributes.lastModifiedTime().toMillis() == modifiedMillis) { "a identidade do executável mudou" }
    }
}

private fun resolveFixedExecutable(candidates: List<String>, name: String): ExecutablePin {
    val path = candidates.asSequence().map(Path::of).firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
        ?: throw IllegalStateException("executável fixo $name não está disponível; nenhum processo foi iniciado")
    return pinExecutable(path, name)
}

private fun pinExecutable(path: Path, name: String): ExecutablePin {
    val real = path.toRealPath()
    val attributes = Files.readAttributes(real, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    require(attributes.isRegularFile && Files.isExecutable(real)) { "executável $name inválido" }
    val store = Files.getFileStore(real)
    val pin = ExecutablePin(real, attributes.fileKey()?.toString() ?: error("identidade estável indisponível para o executável"), StoreIdentity(store, store.name(), store.type()), attributes.size(), attributes.lastModifiedTime().toMillis())
    pin.revalidate()
    return pin
}

private data class FileIdentity(val key: String, val size: Long, val modifiedMillis: Long) {
    val device: String? get() = deviceFromFileKey(key)
}

private fun deviceFromFileKey(key: String): String? = Regex("(?:^|[,(\\s])dev=([^,)]++)").find(key)?.groupValues?.get(1)

private fun checkSameFilesystem(rootDevice: String?, attributes: BasicFileAttributes, display: String) {
    val entryDevice = attributes.fileKey()?.toString()?.let(::deviceFromFileKey)
    check(rootDevice == null || entryDevice == null || entryDevice == rootDevice) { "$display é mountpoint/filesystem interno diferente da raiz" }
}

private fun fileIdentity(path: Path): FileIdentity {
    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) { "arquivo não regular ou link simbólico" }
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    val key = attributes.fileKey()?.toString() ?: throw IllegalStateException("identidade estável indisponível para arquivo")
    check(attributes.isRegularFile)
    return FileIdentity(key, attributes.size(), attributes.lastModifiedTime().toMillis())
}

private data class VerifyResult(val ok: Int, val missing: Int, val mismatch: Int, val unreadable: Int, val unsafe: Int, val safe: Boolean)

private class ArchiveVerifier(
    private val output: (String) -> Unit,
    private val beforeHash: () -> Unit,
) {
    fun verify(card: Path, archive: Path, checksum: Boolean, roots: RootPins): VerifyResult {
        SecureTree(card).use { cardTree ->
            roots.requireOpened(0, cardTree.openedRootAttributes)
            SecureTree(archive).use { archiveTree ->
                roots.requireOpened(1, archiveTree.openedRootAttributes)
                val cardScan = cardTree.scan()
                val archiveScan = archiveTree.scan()
                var ok = 0
                var missing = 0
                var mismatch = 0
                var unreadable = cardScan.unreadable.size + archiveScan.unreadable.size
                var unsafe = cardScan.unsafe.size + archiveScan.unsafe.size
                cardScan.unreadable.forEach { output("ILEGÍVEL NO CARTÃO: $it") }
                archiveScan.unreadable.forEach { output("ILEGÍVEL NO ARQUIVO: $it") }
                cardScan.unsafe.forEach { output("INSEGURO NO CARTÃO: $it") }
                archiveScan.unsafe.forEach { output("INSEGURO NO ARQUIVO: $it") }
                if (cardScan.files.isEmpty()) {
                    unsafe++
                    output("INSEGURO: o cartão não contém arquivos reais e visíveis; veredito de exclusão recusado.")
                }
                beforeHash()
                roots.revalidate()
                for (source in cardScan.files) {
                    val sourceName = source.relative.last()
                    val candidates = archiveScan.files.filter { archiveCandidateName(sourceName, it.relative.last()) }
                    if (candidates.isEmpty()) {
                        missing++
                        output("AUSENTE: ${source.display}")
                        continue
                    }
                    val sameSize = candidates.filter { it.identity.size == source.identity.size }
                    if (sameSize.isEmpty()) {
                        mismatch++
                        output("TAMANHO DIVERGENTE: ${source.display}")
                        continue
                    }
                    val hardlink = sameSize.firstOrNull { it.identity.key == source.identity.key }
                    if (hardlink != null) {
                        unsafe++
                        output("INSEGURO: ${source.display} e ${hardlink.display} são hardlinks do mesmo inode; não constituem cópias independentes.")
                        continue
                    }
                    if (!checksum) {
                        ok++
                        continue
                    }
                    try {
                        roots.revalidate()
                        val sourceHash = cardTree.sha256(source)
                        var matched = false
                        var candidateReadError: Exception? = null
                        for (candidate in sameSize) {
                            try {
                                if (archiveTree.sha256(candidate).contentEquals(sourceHash)) {
                                    matched = true
                                    break
                                }
                            } catch (error: Exception) {
                                candidateReadError = error
                            }
                        }
                        if (matched) ok++ else if (candidateReadError != null) {
                            unreadable++
                            output("ILEGÍVEL NO ARQUIVO para ${source.display}: ${boundedMessage(candidateReadError)}")
                        } else {
                            mismatch++
                            output("CONTEÚDO DIVERGENTE: ${source.display}")
                        }
                    } catch (error: Exception) {
                        unreadable++
                        output("ILEGÍVEL NO CARTÃO: ${source.display} (${boundedMessage(error)})")
                    }
                }
                roots.revalidate()
                cardScan.files.forEach { record ->
                    runCatching { cardTree.validate(record) }.onFailure {
                        unsafe++
                        output("INSEGURO NO CARTÃO ao finalizar: ${record.display} (${boundedMessage(it)})")
                    }
                }
                archiveScan.files.forEach { record ->
                    runCatching { archiveTree.validate(record) }.onFailure {
                        unsafe++
                        output("INSEGURO NO ARQUIVO ao finalizar: ${record.display} (${boundedMessage(it)})")
                    }
                }
                roots.revalidate()
                val safe = cardScan.files.isNotEmpty() && missing == 0 && mismatch == 0 && unreadable == 0 && unsafe == 0
                return VerifyResult(ok, missing, mismatch, unreadable, unsafe, safe)
            }
        }
    }
}

private data class SecureFileRecord(
    val relative: List<String>,
    val identity: FileIdentity,
    val parentKeys: List<String>,
    val linkCount: Long,
) {
    val display: String get() = relative.joinToString("/")
}

private data class SecureScan(
    val files: List<SecureFileRecord>,
    val unreadable: List<String>,
    val unsafe: List<String>,
)

private class SecureTree(root: Path) : AutoCloseable {
    private val rootPath = root.toRealPath()
    private val pinnedRoot = rootIdentity(rootPath)
    private val rootDevice = deviceFromFileKey(pinnedRoot.key)
    private val opened = Files.newDirectoryStream(rootPath)
    private val secureRoot = opened as? SecureDirectoryStream<Path>
        ?: run { opened.close(); throw IllegalStateException("acesso seguro por descritor não está disponível para ${root.fileName}") }

    val openedRootAttributes: BasicFileAttributes
        get() = secureAttributes(secureRoot, ".") ?: error("não foi possível observar a raiz aberta")

    init {
        val openedAttributes = openedRootAttributes
        check(openedAttributes.isDirectory && openedAttributes.fileKey()?.toString() == pinnedRoot.key) { "o descritor abriu uma raiz diferente da identidade fixada" }
    }

    fun scan(): SecureScan {
        val files = mutableListOf<SecureFileRecord>()
        val unreadable = mutableListOf<String>()
        val unsafe = mutableListOf<String>()
        scanDirectory(secureRoot, emptyList(), emptyList(), files, unreadable, unsafe)
        return SecureScan(files.sortedBy { it.display }, unreadable.sorted(), unsafe.sorted())
    }

    private fun scanDirectory(
        directory: SecureDirectoryStream<Path>,
        prefix: List<String>,
        parentKeys: List<String>,
        files: MutableList<SecureFileRecord>,
        unreadable: MutableList<String>,
        unsafe: MutableList<String>,
    ) {
        val entries = try { directory.toList() } catch (error: Exception) {
            unreadable += (prefix.joinToString("/").ifBlank { "." }) + " (${boundedMessage(error)})"
            return
        }
        entries.sortedBy { it.fileName.toString() }.forEach { entry ->
            val name = entry.fileName.toString()
            if (name.startsWith(".")) return@forEach
            val relative = prefix + name
            val display = relative.joinToString("/")
            val attributes = try { secureAttributes(directory, name) } catch (error: Exception) {
                unreadable += "$display (${boundedMessage(error)})"
                return@forEach
            }
            if (attributes == null) {
                unreadable += "$display (a entrada desapareceu)"
                return@forEach
            }
            val entryDevice = attributes.fileKey()?.toString()?.let(::deviceFromFileKey)
            if (rootDevice != null && entryDevice != null && entryDevice != rootDevice) {
                unsafe += "$display (mountpoint/filesystem interno diferente da raiz)"
                return@forEach
            }
            when {
                attributes.isSymbolicLink -> unsafe += "$display (link simbólico)"
                attributes.isRegularFile -> runCatching {
                    val identity = fileIdentity(attributes)
                    val linkCount = stableLinkCount(relative, identity, parentKeys, directory, name)
                    require(linkCount == 1L) { "hardlink detectado (nlink=$linkCount)" }
                    SecureFileRecord(relative, identity, parentKeys, linkCount)
                }.onSuccess(files::add).onFailure { unsafe += "$display (${boundedMessage(it)})" }
                attributes.isDirectory -> {
                    val key = attributes.fileKey()?.toString()
                    if (key == null) {
                        unreadable += "$display (identidade estável indisponível)"
                    } else {
                        try {
                            directory.newDirectoryStream(Path.of(name), LinkOption.NOFOLLOW_LINKS).use { child ->
                                val childAttributes = secureAttributes(child, ".") ?: error("não foi possível observar o diretório aberto")
                                check(childAttributes.fileKey()?.toString() == key) { "o diretório mudou durante a abertura" }
                                scanDirectory(child, relative, parentKeys + key, files, unreadable, unsafe)
                            }
                        } catch (error: Exception) {
                            unreadable += "$display (${boundedMessage(error)})"
                        }
                    }
                }
                else -> unsafe += "$display (entrada não regular)"
            }
        }
    }

    private fun stableLinkCount(relative: List<String>, expected: FileIdentity, parentKeys: List<String>, parent: SecureDirectoryStream<Path>, name: String): Long {
        val textual = relative.fold(rootPath) { current, part -> current.resolve(part) }
        fun validateTextualChain() {
            check(rootIdentity(rootPath) == pinnedRoot) { "a raiz textual mudou ao validar hardlinks" }
            var current = rootPath
            relative.dropLast(1).forEachIndexed { index, part ->
                current = current.resolve(part)
                val attributes = Files.readAttributes(current, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                check(attributes.isDirectory && attributes.fileKey()?.toString() == parentKeys[index]) { "diretório textual intermediário mudou" }
            }
            check(fileIdentity(textual) == expected) { "o caminho textual não corresponde ao descritor fixado" }
        }
        val before = secureAttributes(parent, name)?.let(::fileIdentity) ?: error("a entrada desapareceu")
        check(before == expected) { "a identidade mudou antes de validar hardlinks" }
        validateTextualChain()
        val count = try { (Files.getAttribute(textual, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toLong() }
        catch (error: Exception) { throw IllegalStateException("não foi possível comprovar nlink por atributo unix", error) }
        validateTextualChain()
        val after = secureAttributes(parent, name)?.let(::fileIdentity) ?: error("a entrada desapareceu")
        check(after == expected) { "a identidade mudou ao validar hardlinks" }
        return count
    }

    fun validate(record: SecureFileRecord) = withParent(record) { parent, name -> assertRecordSafe(record, parent, name) }

    fun sha256(record: SecureFileRecord): ByteArray = withParent(record) { parent, name ->
        assertRecordSafe(record, parent, name)
        sha256SecureFile(parent, name, record.identity).also { assertRecordSafe(record, parent, name) }
    }

    fun copyTo(record: SecureFileRecord, output: FileChannel): ByteArray = withParent(record) { parent, name ->
        assertRecordSafe(record, parent, name)
        val digest = MessageDigest.getInstance("SHA-256")
        parent.newByteChannel(Path.of(name), setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { input ->
            val buffer = ByteBuffer.allocateDirect(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                buffer.flip()
                val hashing = buffer.asReadOnlyBuffer()
                digest.update(hashing)
                while (buffer.hasRemaining()) output.write(buffer)
                buffer.clear()
            }
        }
        assertRecordSafe(record, parent, name)
        digest.digest()
    }

    private fun assertRecordSafe(record: SecureFileRecord, parent: SecureDirectoryStream<Path>, name: String) {
        check(secureAttributes(parent, name)?.let(::fileIdentity) == record.identity) { "a identidade do arquivo mudou" }
        check(stableLinkCount(record.relative, record.identity, record.parentKeys, parent, name) == 1L) { "a origem ganhou hardlink durante a operação" }
    }

    private fun <T> withParent(record: SecureFileRecord, block: (SecureDirectoryStream<Path>, String) -> T): T {
        val openedDirectories = mutableListOf<SecureDirectoryStream<Path>>()
        var current = secureRoot
        return try {
            check(rootIdentity(rootPath) == pinnedRoot) { "a identidade textual da raiz mudou" }
            check(secureAttributes(secureRoot, ".")?.fileKey()?.toString() == pinnedRoot.key) { "o descritor da raiz mudou" }
            record.relative.dropLast(1).forEachIndexed { index, part ->
                val attributes = secureAttributes(current, part) ?: error("diretório intermediário desapareceu")
                check(attributes.isDirectory && attributes.fileKey()?.toString() == record.parentKeys[index]) { "diretório intermediário mudou" }
                check(rootDevice == null || attributes.fileKey()?.toString()?.let(::deviceFromFileKey) in setOf(null, rootDevice)) { "filesystem interno diferente da raiz" }
                current = current.newDirectoryStream(Path.of(part), LinkOption.NOFOLLOW_LINKS)
                val openedAttributes = secureAttributes(current, ".") ?: error("diretório intermediário ilegível")
                check(openedAttributes.fileKey()?.toString() == record.parentKeys[index]) { "diretório intermediário mudou durante a abertura" }
                openedDirectories += current
            }
            block(current, record.relative.last())
        } finally {
            openedDirectories.asReversed().forEach { runCatching { it.close() } }
        }
    }

    private fun secureAttributes(directory: SecureDirectoryStream<Path>, name: String): BasicFileAttributes? = try {
        directory.getFileAttributeView(Path.of(name), BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)?.readAttributes()
    } catch (_: NoSuchFileException) { null }

    private fun fileIdentity(attributes: BasicFileAttributes): FileIdentity {
        require(attributes.isRegularFile) { "a entrada não é um arquivo regular" }
        val key = attributes.fileKey()?.toString() ?: error("identidade estável indisponível para arquivo")
        return FileIdentity(key, attributes.size(), attributes.lastModifiedTime().toMillis())
    }

    private fun sha256SecureFile(directory: SecureDirectoryStream<Path>, name: String, expected: FileIdentity): ByteArray {
        check(secureAttributes(directory, name)?.let(::fileIdentity) == expected) { "a identidade do arquivo mudou antes da leitura" }
        val digest = MessageDigest.getInstance("SHA-256")
        directory.newByteChannel(Path.of(name), setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            val buffer = ByteBuffer.allocateDirect(HASH_BUFFER_BYTES)
            while (true) {
                val count = channel.read(buffer)
                if (count < 0) break
                buffer.flip()
                digest.update(buffer)
                buffer.clear()
            }
        }
        check(secureAttributes(directory, name)?.let(::fileIdentity) == expected) { "a identidade do arquivo mudou durante a leitura" }
        return digest.digest()
    }

    override fun close() = opened.close()
}

private data class ImportResult(val copied: Int, val skipped: Int, val renamed: Int, val unsupported: Int, val failed: Int)
private enum class ImportKind { PHOTO, VIDEO, SIDECAR }
private enum class DateSource { EXIF, MTIME, UNKNOWN }
private data class ResolvedDate(val yearMonth: YearMonth?, val source: DateSource) {
    val folder: String get() = yearMonth?.let { "%04d.%02d".format(it.year, it.monthValue) } ?: UNKNOWN_DATE
}
private data class ImportMedia(val record: SecureFileRecord, val kind: ImportKind, val stem: String, val group: Pair<String, String>, val ownDate: ResolvedDate)
private data class ImportItem(val record: SecureFileRecord, val folder: String, val expectedHash: ByteArray)

private class ArchiveImporter(
    private val dateReader: CaptureDateReader,
    private val output: (String) -> Unit,
    private val beforePublish: (Path) -> Unit,
) {
    private companion object { val destinationLocks = java.util.concurrent.ConcurrentHashMap<String, Any>() }

    fun import(sourceRoot: Path, destinationRoot: Path, skipVideos: Boolean): ImportResult =
        synchronized(destinationLocks.computeIfAbsent(destinationRoot.toRealPath().toString()) { Any() }) {
            importLocked(sourceRoot, destinationRoot, skipVideos)
        }

    private fun importLocked(sourceRoot: Path, destinationRoot: Path, skipVideos: Boolean): ImportResult {
        val roots = RootPins(sourceRoot, destinationRoot)
        SecureTree(sourceRoot).use { sourceTree ->
            roots.requireOpened(0, sourceTree.openedRootAttributes)
            val scan = sourceTree.scan()
            if (scan.unsafe.isNotEmpty() || scan.unreadable.isNotEmpty()) {
                scan.unsafe.take(20).forEach { output("ENTRADA INSEGURA: $it") }
                scan.unreadable.take(20).forEach { output("ENTRADA ILEGÍVEL: $it") }
                return ImportResult(0, 0, 0, 0, scan.unsafe.size + scan.unreadable.size)
            }
            val repeatedIdentities = scan.files.groupBy { it.identity.key }.values.filter { it.size > 1 }
            if (repeatedIdentities.isNotEmpty()) {
                repeatedIdentities.forEach { aliases -> output("ENTRADA INSEGURA: hardlinks não são cópias independentes (${aliases.joinToString { it.display }})") }
                return ImportResult(0, 0, 0, 0, repeatedIdentities.sumOf { it.size })
            }
            roots.revalidate()
            val plan = plan(sourceRoot, destinationRoot, sourceTree, scan.files, skipVideos)
            roots.revalidate()
            var copied = 0
            var skipped = 0
            var renamed = 0
            var failed = 0
            Files.newDirectoryStream(destinationRoot).use { openedRoot ->
                val secureRoot = openedRoot as? SecureDirectoryStream<Path>
                    ?: throw IllegalStateException("acesso seguro por descritor não está disponível para o destino")
                val openedDestination = secureAttributes(secureRoot, ".") ?: error("não foi possível observar o destino aberto")
                roots.requireOpened(1, openedDestination)
                val destinationDevice = openedDestination.fileKey()?.toString()?.let(::deviceFromFileKey)
                val missingFolders = plan.items.map { it.folder }.distinct().filter { folderName ->
                    val attributes = secureAttributes(secureRoot, folderName)
                    if (attributes?.isDirectory == true) {
                        checkSameFilesystem(destinationDevice, attributes, folderName)
                        secureRoot.newDirectoryStream(Path.of(folderName), LinkOption.NOFOLLOW_LINKS).use { child ->
                            val openedFolder = secureAttributes(child, ".") ?: error("pasta mensal ilegível")
                            check(openedFolder.fileKey()?.toString() == attributes.fileKey()?.toString()) { "a pasta mensal mudou durante a abertura" }
                        }
                        false
                    } else true
                }
                if (missingFolders.isNotEmpty()) {
                    throw IllegalStateException("pastas mensais ausentes: ${missingFolders.sorted().joinToString()}; criação mensal automática foi recusada porque a JVM não oferece mkdir relativo ao descritor")
                }
                roots.revalidate()
                plan.items.forEach { item ->
                    try {
                        roots.revalidate()
                        val result = withDestinationFolder(item.folder, destinationDevice, roots, secureRoot) { folder, folderKey ->
                            copyConflictSafe(sourceRoot, item.record, item.expectedHash, sourceTree, destinationRoot.resolve(item.folder), item.folder, folderKey, secureRoot, folder, roots)
                        }
                        when (result.first) {
                            "copied" -> { copied++; if (result.second.fileName.toString() != item.record.relative.last()) renamed++ }
                            else -> skipped++
                        }
                    } catch (error: Exception) {
                        failed++
                        output("FALHA: ${item.record.display} (${boundedMessage(error)})")
                    }
                }
            }
            return ImportResult(copied, skipped, renamed, plan.unsupported, failed)
        }
    }

    private data class Plan(val items: List<ImportItem>, val unsupported: Int)

    private fun plan(sourceRoot: Path, destinationRoot: Path, tree: SecureTree, files: List<SecureFileRecord>, skipVideos: Boolean): Plan {
        val media = mutableListOf<ImportMedia>()
        val sidecars = mutableListOf<SecureFileRecord>()
        var unsupported = 0
        fun directoryKey(record: SecureFileRecord): String = record.relative.dropLast(1).joinToString("/")
        fun filename(record: SecureFileRecord): String = record.relative.last()
        fun extension(record: SecureFileRecord): String = filename(record).substringAfterLast('.', "").lowercase()
        val mediaStems = files.filter { extension(it) in PHOTO_EXTENSIONS || (!skipVideos && extension(it) in VIDEO_EXTENSIONS) }
            .groupBy({ directoryKey(it) }, { caseFoldText(filename(it).substringBeforeLast('.')) })
            .mapValues { it.value.toSet() }
        files.forEach { record ->
            val extension = extension(record)
            val kind = when {
                extension in PHOTO_EXTENSIONS -> ImportKind.PHOTO
                extension in VIDEO_EXTENSIONS && !skipVideos -> ImportKind.VIDEO
                extension in VIDEO_EXTENSIONS -> { unsupported++; return@forEach }
                extension in SIDECAR_EXTENSIONS -> { sidecars.add(record); return@forEach }
                else -> { unsupported++; return@forEach }
            }
            val stem = caseFoldText(filename(record).substringBeforeLast('.'))
            val directory = directoryKey(record)
            val groupStem = groupKey(stem, mediaStems[directory].orEmpty())
            media += ImportMedia(record, kind, stem, directory to groupStem, resolveDate(sourceRoot, destinationRoot, tree, record, kind))
        }
        val groupDates = media.filter { it.stem == it.group.second }.groupBy { it.group }.mapValues { (_, members) ->
            members.minWithOrNull(compareBy<ImportMedia>({ if (it.kind == ImportKind.PHOTO) 0 else 1 }, { it.ownDate.source.ordinal }))!!.ownDate
        }
        val planned = media.map { it.record to (groupDates[it.group] ?: it.ownDate).folder }.toMutableList()
        sidecars.forEach { sidecar ->
            val directory = directoryKey(sidecar)
            val stems = buildList {
                val outer = caseFoldText(filename(sidecar).substringBeforeLast('.'))
                add(outer)
                val innerExtension = outer.substringAfterLast('.', "")
                if (innerExtension in PHOTO_EXTENSIONS || innerExtension in VIDEO_EXTENSIONS) add(outer.substringBeforeLast('.'))
            }
            val group = stems.asSequence().map { directory to groupKey(it, mediaStems[directory].orEmpty()) }.firstOrNull { it in groupDates }
            val date = group?.let(groupDates::get) ?: resolveDate(sourceRoot, destinationRoot, tree, sidecar, ImportKind.SIDECAR)
            planned += sidecar to date.folder
        }
        val items = planned.sortedBy { it.first.display }.map { (record, folder) ->
            ImportItem(record, folder, tree.sha256(record))
        }
        return Plan(items, unsupported)
    }

    private fun resolveDate(sourceRoot: Path, destinationRoot: Path, tree: SecureTree, record: SecureFileRecord, kind: ImportKind): ResolvedDate {
        val metadata = if (kind == ImportKind.PHOTO) observeSafeCopy(sourceRoot, destinationRoot, tree, record) else null
        metadata?.let { plausible(it)?.let { value -> return ResolvedDate(value, DateSource.EXIF) } }
        val modified = Instant.ofEpochMilli(record.identity.modifiedMillis).atZone(ZoneId.systemDefault())
        plausible(YearMonth.of(modified.year, modified.monthValue))?.let { return ResolvedDate(it, DateSource.MTIME) }
        return ResolvedDate(null, DateSource.UNKNOWN)
    }

    private fun observeSafeCopy(sourceRoot: Path, destinationRoot: Path, tree: SecureTree, record: SecureFileRecord): YearMonth? {
        require(record.identity.size <= MAX_IMPORT_OBSERVATION_BYTES) { "arquivo excede o limite da cópia segura para metadata" }
        val temporaryDirectory = Files.createTempDirectory("phototool-import-observe-")
        try {
            require(pathIsOutsideRoots(temporaryDirectory, sourceRoot, destinationRoot)) { "o temporário de metadata não ficou fora das raízes" }
            val copy = temporaryDirectory.resolve(record.relative.last())
            val digest = FileChannel.open(copy, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { outputChannel ->
                tree.copyTo(record, outputChannel).also { outputChannel.force(true) }
            }
            val copiedIdentity = fileIdentity(copy)
            check(copiedIdentity.size == record.identity.size && sha256(copy).contentEquals(digest)) { "a cópia segura para metadata divergiu da origem" }
            // A missing/invalid date is an explicit null and may fall back to
            // mtime. Reader failures propagate so an I/O/decoder error is not
            // silently presented as genuinely absent metadata.
            return dateReader.captureMonth(copy)
        } finally {
            temporaryDirectory.toFile().walkBottomUp().forEach { path -> runCatching { Files.deleteIfExists(path.toPath()) } }
            check(!Files.exists(temporaryDirectory, LinkOption.NOFOLLOW_LINKS)) { "não foi possível limpar o temporário de metadata" }
        }
    }

    private fun plausible(value: YearMonth): YearMonth? = value.takeIf { it.year in 1970..(java.time.Year.now().value + 1) }

    private fun <T> withDestinationFolder(
        name: String,
        destinationDevice: String?,
        roots: RootPins,
        secureRoot: SecureDirectoryStream<Path>,
        block: (SecureDirectoryStream<Path>, String) -> T,
    ): T {
        require(name == UNKNOWN_DATE || Regex("\\d{4}\\.\\d{2}").matches(name))
        val relative = Path.of(name)
        val attributes = secureAttributes(secureRoot, name)
        if (attributes == null) {
            roots.revalidate()
            throw IllegalStateException("a pasta $name não existe; criação mensal automática foi recusada porque a JVM não oferece mkdir relativo ao descritor. Crie a pasta real no destino e repita")
        }
        require(attributes.isDirectory) { "pasta de destino insegura" }
        checkSameFilesystem(destinationDevice, attributes, name)
        val key = attributes.fileKey()?.toString() ?: error("identidade estável indisponível para a pasta de destino")
        return secureRoot.newDirectoryStream(relative, LinkOption.NOFOLLOW_LINKS).use { folder ->
            val opened = secureAttributes(folder, ".") ?: error("pasta de destino ilegível")
            check(opened.fileKey()?.toString() == key) { "a pasta de destino mudou durante a abertura" }
            checkSameFilesystem(destinationDevice, opened, name)
            block(folder, key)
        }
    }

    private fun copyConflictSafe(
        sourceRoot: Path,
        source: SecureFileRecord,
        expectedSourceHash: ByteArray,
        secureSource: SecureTree,
        destinationFolder: Path,
        folderName: String,
        folderKey: String,
        secureRoot: SecureDirectoryStream<Path>,
        folder: SecureDirectoryStream<Path>,
        roots: RootPins,
    ): Pair<String, Path> {
        val originalIdentity = source.identity
        check(secureSource.sha256(source).contentEquals(expectedSourceHash)) { "a origem divergiu do manifesto seguro" }
        val sourceName = source.relative.last()
        var duplicate = 0
        while (duplicate < 100_000) {
            val targetName = duplicateName(sourceName, duplicate)
            val markerName = ".$targetName.phototool-importing"
            if (secureAttributes(folder, markerName) != null) {
                awaitActivePublisher(folder, markerName, targetName)
                continue
            }
            val existingName = casefoldEntry(folder, targetName)
            val target = destinationFolder.resolve(existingName ?: targetName)
            val targetAttributes = existingName?.let { secureAttributes(folder, it) }
            if (targetAttributes != null) {
                require(targetAttributes.isRegularFile) { "destino existente inseguro" }
                val targetIdentity = fileIdentity(targetAttributes)
                require(targetIdentity.key != originalIdentity.key || targetIdentity.device != originalIdentity.device) { "origem e destino existente são hardlinks; não são cópias independentes" }
                if (targetIdentity.size == originalIdentity.size && expectedSourceHash.contentEquals(sha256Secure(folder, existingName, targetIdentity))) return "skipped" to target
                duplicate++
                continue
            }
            beforePublish(sourceRoot.resolve(source.relative.joinToString(FileSystems.getDefault().separator)))
            roots.revalidate()
            val currentFolder = secureAttributes(secureRoot, folderName)
            check(currentFolder?.isDirectory == true && currentFolder.fileKey()?.toString() == folderKey) { "a pasta de destino mudou antes da publicação" }
            check(sourceDigestStillMatches(secureSource, source, expectedSourceHash)) { "a origem mudou antes da publicação" }
            if (casefoldEntry(folder, targetName) != null) {
                duplicate++
                continue
            }
            var createdIdentity: FileIdentity? = null
            var markerIdentity: FileIdentity? = null
            var publicationDurable = false
            try {
                val markerBytes = "phototool-import-v2\npid=${ProcessHandle.current().pid()}\ntoken=${UUID.randomUUID()}\n".toByteArray()
                folder.newByteChannel(Path.of(markerName), setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                    val file = channel as? FileChannel ?: error("canal do marcador não permite fsync")
                    val markerBuffer = ByteBuffer.wrap(markerBytes)
                    while (markerBuffer.hasRemaining()) file.write(markerBuffer)
                    file.force(true)
                }
                markerIdentity = secureAttributes(folder, markerName)?.let(::fileIdentity)
                    ?: error("o marcador de publicação desapareceu")
                forcePinnedDirectory(folder)
                val sourceDigest = folder.newByteChannel(
                    Path.of(targetName),
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                ).use { output ->
                    val fileOutput = output as? FileChannel ?: error("canal de destino não permite fsync")
                    val reserved = secureAttributes(folder, targetName)?.let(::fileIdentity)
                        ?: error("não foi possível fixar a identidade do arquivo reservado")
                    createdIdentity = reserved
                    check(reserved.size == 0L) { "o arquivo reservado foi alterado antes da cópia" }
                    secureSource.copyTo(source, fileOutput).also {
                        check(it.contentEquals(expectedSourceHash)) { "a origem divergiu do manifesto durante a cópia" }
                        fileOutput.force(true)
                    }
                }
                val publishedAttributes = secureAttributes(folder, targetName) ?: error("a publicação final desapareceu")
                val published = fileIdentity(publishedAttributes)
                check(published.key == createdIdentity?.key && published.size == originalIdentity.size && sourceDigest.contentEquals(expectedSourceHash) && sha256Secure(folder, targetName, published).contentEquals(sourceDigest)) { "a publicação final falhou na verificação" }
                val stillCurrent = secureAttributes(secureRoot, folderName)
                check(stillCurrent?.fileKey()?.toString() == folderKey) { "a pasta de destino mudou depois da publicação" }
                forcePinnedDirectory(folder)
                roots.revalidate()
                check(secureAttributes(secureRoot, folderName)?.fileKey()?.toString() == folderKey) { "a pasta de destino mudou durante o fsync" }
                check(secureSource.sha256(source).contentEquals(expectedSourceHash)) { "a origem mudou após a publicação" }
                publicationDurable = true
                val durableMarker = markerIdentity
                deleteIfIdentityMatches(folder, markerName, durableMarker)
                markerIdentity = null
                forcePinnedDirectory(folder)
                return "copied" to target
            } catch (error: FileAlreadyExistsException) {
                if (markerIdentity == null) awaitActivePublisher(folder, markerName, targetName)
                continue
            } finally {
                val created = createdIdentity
                var targetRemoved = created == null
                if (created != null) {
                    val current = runCatching { secureAttributes(folder, targetName)?.let(::fileIdentity) }.getOrNull()
                    if (current?.key == created.key && current.size != originalIdentity.size) {
                        targetRemoved = runCatching { folder.deleteFile(Path.of(targetName)); true }.getOrDefault(false)
                    }
                }
                // Keep the marker beside any full but unconfirmed result. A later
                // import then refuses to call it success after a crash/fsync failure.
                val marker = markerIdentity
                if (!publicationDurable && targetRemoved && marker != null) {
                    runCatching { deleteIfIdentityMatches(folder, markerName, marker); forcePinnedDirectory(folder) }
                }
            }
        }
        error("limite de colisões excedido")
    }

    private fun sourceDigestStillMatches(tree: SecureTree, source: SecureFileRecord, expected: ByteArray): Boolean =
        tree.sha256(source).contentEquals(expected)

    private fun forcePinnedDirectory(directory: SecureDirectoryStream<Path>) {
        directory.newByteChannel(Path.of("."), setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            (channel as? FileChannel ?: error("fsync do diretório de destino indisponível")).force(true)
        }
    }

    private fun deleteIfIdentityMatches(directory: SecureDirectoryStream<Path>, name: String, expected: FileIdentity) {
        val current = secureAttributes(directory, name)?.let(::fileIdentity) ?: return
        check(current.key == expected.key) { "a identidade do marcador de publicação mudou" }
        directory.deleteFile(Path.of(name))
    }

    private fun awaitActivePublisher(directory: SecureDirectoryStream<Path>, markerName: String, targetName: String) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(IMPORT_MARKER_WAIT_MILLIS)
        var unresolvedSince: Long? = null
        while (secureAttributes(directory, markerName) != null) {
            when (markerOwnerIsAlive(directory, markerName)) {
                null -> continue
                false -> {
                    val now = System.nanoTime()
                    val since = unresolvedSince ?: now.also { unresolvedSince = it }
                    check(now - since < TimeUnit.MILLISECONDS.toNanos(IMPORT_MARKER_INITIALIZATION_MILLIS)) {
                        "há uma publicação parcial pendente para $targetName; diagnostique e remova manualmente"
                    }
                }
                true -> unresolvedSince = null
            }
            check(System.nanoTime() < deadline) {
                "a publicação concorrente de $targetName não terminou no prazo; nenhuma entrada foi sobrescrita"
            }
            Thread.sleep(20)
        }
    }

    /** Null means the marker disappeared while being inspected; false is stale/invalid. */
    private fun markerOwnerIsAlive(directory: SecureDirectoryStream<Path>, markerName: String): Boolean? {
        val attributes = secureAttributes(directory, markerName) ?: return null
        if (!attributes.isRegularFile || attributes.size() !in 1..IMPORT_MARKER_MAX_BYTES.toLong()) return false
        val expected = fileIdentity(attributes)
        val bytes = try {
            directory.newByteChannel(Path.of(markerName), setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                val output = ByteArray(attributes.size().toInt())
                var offset = 0
                while (offset < output.size) {
                    val count = channel.read(ByteBuffer.wrap(output, offset, output.size - offset))
                    if (count < 0) break
                    offset += count
                }
                if (offset != output.size || channel.read(ByteBuffer.allocate(1)) >= 0) return false
                output
            }
        } catch (_: NoSuchFileException) {
            return null
        }
        if (secureAttributes(directory, markerName)?.let(::fileIdentity) != expected) return false
        val marker = Regex("phototool-import-v2\\npid=([1-9][0-9]{0,18})\\ntoken=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\n")
            .matchEntire(bytes.toString(Charsets.US_ASCII)) ?: return false
        val pid = marker.groupValues[1].toLongOrNull() ?: return false
        return ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }

    private fun casefoldEntry(directory: SecureDirectoryStream<Path>, requested: String): String? {
        val matches = directory.newDirectoryStream(Path.of("."), LinkOption.NOFOLLOW_LINKS).use { view ->
            view.toList().map { it.fileName.toString() }.filter { caseFoldText(it) == caseFoldText(requested) }
        }
        require(matches.size <= 1) { "nomes de destino ambíguos após casefold" }
        return matches.singleOrNull()
    }

    private fun secureAttributes(directory: SecureDirectoryStream<Path>, name: String): BasicFileAttributes? = try {
        directory.getFileAttributeView(Path.of(name), BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)?.readAttributes()
    } catch (_: NoSuchFileException) { null }

    private fun fileIdentity(attributes: BasicFileAttributes): FileIdentity {
        val key = attributes.fileKey()?.toString() ?: error("identidade estável indisponível para arquivo")
        return FileIdentity(key, attributes.size(), attributes.lastModifiedTime().toMillis())
    }

    private fun sha256Secure(directory: SecureDirectoryStream<Path>, name: String, expected: FileIdentity): ByteArray {
        check(secureAttributes(directory, name)?.let(::fileIdentity) == expected) { "a identidade do arquivo mudou antes da leitura" }
        val digest = MessageDigest.getInstance("SHA-256")
        directory.newByteChannel(Path.of(name), setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            val buffer = ByteBuffer.allocateDirect(HASH_BUFFER_BYTES)
            while (true) {
                val count = channel.read(buffer)
                if (count < 0) break
                buffer.flip()
                digest.update(buffer)
                buffer.clear()
            }
        }
        check(secureAttributes(directory, name)?.let(::fileIdentity) == expected) { "a identidade do arquivo mudou durante a leitura" }
        return digest.digest()
    }
}

private fun groupKey(stem: String, mediaStems: Set<String>): String {
    stem.forEachIndexed { index, character ->
        if (character in setOf('-', '_', ' ', '(')) {
            val prefix = stem.substring(0, index)
            if (prefix.isNotEmpty() && prefix in mediaStems) return prefix
        }
    }
    return stem
}

private fun duplicateName(filename: String, duplicate: Int): String {
    if (duplicate == 0) return filename
    val dot = filename.lastIndexOf('.')
    return if (dot <= 0) "${filename}__dup$duplicate" else "${filename.substring(0, dot)}__dup$duplicate${filename.substring(dot)}"
}

private fun archiveCandidateName(sourceFilename: String, candidateFilename: String): Boolean {
    if (caseFoldText(sourceFilename) == caseFoldText(candidateFilename)) return true
    val dot = sourceFilename.lastIndexOf('.')
    val sourceStem = if (dot <= 0) sourceFilename else sourceFilename.substring(0, dot)
    val extension = if (dot <= 0) "" else sourceFilename.substring(dot)
    val candidateDot = candidateFilename.lastIndexOf('.')
    val candidateStem = if (candidateDot <= 0) candidateFilename else candidateFilename.substring(0, candidateDot)
    val candidateExtension = if (candidateDot <= 0) "" else candidateFilename.substring(candidateDot)
    if (caseFoldText(extension) != caseFoldText(candidateExtension)) return false
    val foldedSource = caseFoldText(sourceStem)
    val foldedCandidate = caseFoldText(candidateStem)
    if (!foldedCandidate.startsWith(foldedSource)) return false
    val suffix = foldedCandidate.removePrefix(foldedSource)
    return Regex("__dup[1-9]\\d*").matches(suffix)
}

private fun Path.extensionLower(): String = fileName.toString().substringAfterLast('.', "").lowercase()

private fun videoExcludes(): List<String> = VIDEO_EXTENSIONS.sorted().map { extension ->
    "--exclude=*." + extension.map { character -> if (character.isLetter()) "[${character.lowercaseChar()}${character.uppercaseChar()}]" else character }.joinToString("")
}

private fun boundedMessage(error: Throwable): String = (error.message ?: error.javaClass.simpleName).replace('\n', ' ').replace('\r', ' ').take(500)
private fun forceDirectory(path: Path) { FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) } }

private fun sha256(path: Path): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
        val buffer = ByteBuffer.allocateDirect(HASH_BUFFER_BYTES)
        while (true) {
            val count = channel.read(buffer)
            if (count < 0) break
            buffer.flip()
            digest.update(buffer)
            buffer.clear()
        }
    }
    return digest.digest()
}

private fun pathIsOutsideRoots(path: Path, vararg roots: Path): Boolean {
    val candidate = rootIdentity(path)
    return roots.all { root ->
        val identity = rootIdentity(root)
        !samePhysicalEntry(candidate, identity) &&
            physicalAncestors(candidate.path).none { samePhysicalEntry(it, identity) } &&
            physicalAncestors(identity.path).none { samePhysicalEntry(it, candidate) }
    }
}

internal fun runArchiveCli(arguments: Array<String>): Int = ArchiveCli().run(arguments)
