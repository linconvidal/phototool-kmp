package br.com.lincon.phototool.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.YearMonth
import br.com.lincon.phototool.domain.ObservedMetadata
import br.com.lincon.phototool.domain.caseFoldText
import kotlin.io.path.*
import kotlin.test.*

class ArchiveCliTest {
    private lateinit var temporary: Path

    @BeforeTest fun setup() { temporary = Files.createTempDirectory("phototool-archive-test-") }
    @AfterTest fun cleanup() { temporary.toFile().deleteRecursively() }

    @Test fun kimCaptureDateReaderPreservesCivilMonthWithAndWithoutOffset() {
        val media = file(temporary.resolve("capture.JPG"), "fixture")
        fun read(value: String?) = KimCaptureDateReader(object : MediaObservationAdapter {
            override fun observe(bytes: ByteArray, kind: br.com.lincon.phototool.domain.MediaKind) = ObservedMetadata(capturedAt = value)
        }).captureMonth(media)

        assertEquals(YearMonth.of(2024, 3), read("2024-03-01T00:15:00"))
        // Converting this to an Instant/UTC would incorrectly move it to February.
        assertEquals(YearMonth.of(2024, 3), read("2024-03-01T00:15:00+14:00"))
        assertEquals(YearMonth.of(2024, 3), read("2024-03-31T23:45:00-12:00"))
        assertNull(read("2024-02-31T00:00:00+00:00"))
        assertNull(read(null))
        val failing = KimCaptureDateReader(object : MediaObservationAdapter {
            override fun observe(bytes: ByteArray, kind: br.com.lincon.phototool.domain.MediaKind) = ObservedMetadata(status = br.com.lincon.phototool.domain.MetadataStatus.ERROR, errorCode = "decoder-failed")
        })
        assertFailsWith<IllegalStateException> { failing.captureMonth(media) }
    }

    @Test fun verifyUsesSha256RecognizesDuplicateSuffixAndWritesNothing() {
        val card = directory("card")
        val archive = directory("archive")
        file(card.resolve("DSC1.JPG"), "photo-one")
        file(card.resolve("DSC2.RAF"), "photo-two")
        file(archive.resolve("2025.01/DSC1.JPG"), "photo-one")
        file(archive.resolve("2025.02/DSC2__dup7.RAF"), "photo-two")
        file(card.resolve(".DS_Store"), "ignored")
        val beforeCard = treeState(card)
        val beforeArchive = treeState(archive)
        val lines = mutableListOf<String>()

        val exit = cli(lines).run(arrayOf("archive", "verify", "--card", card.toString(), "--archive", archive.toString()))

        assertEquals(0, exit)
        assertTrue(lines.any { it.startsWith("SEGURO PARA APAGAR") })
        assertTrue(lines.any { "quiescente" in it && "FileStore distinto não comprova hardware" in it })
        assertEquals(beforeCard, treeState(card))
        assertEquals(beforeArchive, treeState(archive))
    }

    @Test fun verifyNeverAuthorizesWipeWhenRootsShareFileStore() {
        val card = directory("same-store-card")
        val archive = directory("same-store-archive")
        file(card.resolve("A.JPG"), "same")
        file(archive.resolve("2025.01/A.JPG"), "same")
        val lines = mutableListOf<String>()
        val productionBoundary = ArchiveCli(
            dateReader = CaptureDateReader { null },
            processRunner = FakeRunner(),
            output = lines::add,
        )

        assertEquals(1, productionBoundary.run(arrayOf("archive", "verify", "--card", card.toString(), "--archive", archive.toString())))
        assertTrue(lines.any { "mesmo FileStore/volume" in it })
        assertTrue(lines.any { it.startsWith("Verificação: 1 OK") }, lines.joinToString(" | "))
        assertTrue(lines.any { it.startsWith("NÃO É SEGURO APAGAR") })
        assertFalse(lines.any { it.startsWith("SEGURO PARA APAGAR") })
    }

    @Test fun sizeOnlyNeverProducesSafeToWipeVerdict() {
        val card = directory("card")
        val archive = directory("archive")
        file(card.resolve("A.JPG"), "AAAA")
        file(archive.resolve("2025.01/A.JPG"), "AAAA")
        val lines = mutableListOf<String>()

        val exit = cli(lines).run(arrayOf("archive", "verify", "--card", card.toString(), "--archive", archive.toString(), "--size-only"))

        assertEquals(1, exit)
        assertTrue(lines.any { it.startsWith("INCONCLUSIVO") })
        assertFalse(lines.any { it.startsWith("SEGURO PARA APAGAR") })
    }

    @Test fun verifyFailsClosedForSameSizeDifferentContentAndSymlink() {
        val card = directory("card")
        val archive = directory("archive")
        file(card.resolve("A.JPG"), "AAAA")
        file(archive.resolve("2025.01/A.JPG"), "BBBB")
        val outside = file(temporary.resolve("outside.JPG"), "outside")
        Files.createSymbolicLink(card.resolve("linked.JPG"), outside)
        val lines = mutableListOf<String>()

        val exit = cli(lines).run(arrayOf("archive", "verify", "--card", card.toString(), "--archive", archive.toString()))

        assertEquals(1, exit)
        assertTrue(lines.any { it.startsWith("CONTEÚDO DIVERGENTE") })
        assertTrue(lines.any { it.startsWith("INSEGURO NO CARTÃO") })
    }

    @Test fun invalidAndOverlappingRootsAreUsageErrors() {
        val card = directory("card")
        val nested = directory("card/archive")
        assertEquals(2, cli().run(arrayOf("archive", "verify", "--card", card.toString())))
        assertEquals(2, cli().run(arrayOf("archive", "verify", "--card", card.toString(), "--archive", nested.toString())))
    }

    @Test fun canonicalRootValidationRejectsSymlinkAliasesForEqualityAndNesting() {
        val card = directory("physical/card")
        val nested = directory("physical/card/nested")
        val alias = temporary.resolve("card-alias")
        Files.createSymbolicLink(alias, card)

        assertEquals(2, cli().run(arrayOf("archive", "verify", "--card", alias.toString(), "--archive", card.toString())))
        assertEquals(2, cli().run(arrayOf("archive", "verify", "--card", alias.toString(), "--archive", nested.toString())))
    }

    @Test fun verifyRejectsEmptyCardHardlinkAndChangedIntermediateDirectory() {
        val empty = directory("empty-card")
        val archive = directory("archive")
        val emptyLines = mutableListOf<String>()
        assertEquals(1, cli(emptyLines).run(arrayOf("archive", "verify", "--card", empty.toString(), "--archive", archive.toString())))
        assertTrue(emptyLines.any { "não contém arquivos" in it })
        assertFalse(emptyLines.any { it.startsWith("SEGURO PARA APAGAR") })

        val card = directory("card")
        val original = file(card.resolve("A.JPG"), "same inode")
        Files.createDirectories(archive.resolve("2024.01"))
        Files.createLink(archive.resolve("2024.01/A.JPG"), original)
        val hardlinkLines = mutableListOf<String>()
        assertEquals(1, cli(hardlinkLines).run(arrayOf("archive", "verify", "--card", card.toString(), "--archive", archive.toString())))
        assertTrue(hardlinkLines.any { "hardlink" in it })

        Files.delete(original)
        Files.delete(archive.resolve("2024.01/A.JPG"))
        val media = file(card.resolve("DCIM/B.JPG"), "content")
        file(archive.resolve("2024.01/B.JPG"), "content")
        val displaced = temporary.resolve("displaced-dcim")
        val changedLines = mutableListOf<String>()
        val guarded = ArchiveCli(
            dateReader = CaptureDateReader { null },
            processRunner = FakeRunner(),
            output = changedLines::add,
            beforeVerifyHash = {
                Files.move(media.parent, displaced)
                Files.createDirectory(media.parent)
                file(media.parent.resolve("B.JPG"), "content")
            },
        )
        assertEquals(1, guarded.run(arrayOf("archive", "verify", "--card", card.toString(), "--archive", archive.toString())))
        assertTrue(changedLines.any { it.startsWith("ILEGÍVEL NO CARTÃO") })
        assertFalse(changedLines.any { it.startsWith("SEGURO PARA APAGAR") })
    }

    @Test fun importKeepsOriginalEditsAndSidecarsInMetadataMonth() {
        val source = directory("card")
        val destination = directory("archive")
        directory("archive/2021.11")
        val raw = file(source.resolve("DCIM/DSF1.RAF"), "raw")
        file(source.resolve("DCIM/DSF1-HDR.JPG"), "edit")
        file(source.resolve("DCIM/DSF1.RAF.xmp"), "xmp")
        file(source.resolve("DCIM/CLIP.MOV"), "video")
        file(source.resolve("DCIM/.DS_Store"), "hidden")
        val captureMonth = YearMonth.of(2021, 11)
        val lines = mutableListOf<String>()
        val observedMetadataPaths = mutableListOf<Path>()
        val dateReader = CaptureDateReader {
            observedMetadataPaths.add(it)
            if (it.fileName.toString() == raw.fileName.toString()) captureMonth else YearMonth.of(2026, 1)
        }

        val exit = cli(lines, dateReader).run(arrayOf("archive", "import", "--source", source.toString(), "--destination", destination.toString(), "--skip-videos"))

        assertEquals(0, exit)
        assertTrue(observedMetadataPaths.isNotEmpty())
        assertTrue(observedMetadataPaths.all { !it.normalize().startsWith(source.normalize()) && !it.normalize().startsWith(destination.normalize()) })
        assertTrue(observedMetadataPaths.all { !it.exists() }, "temporários de metadata devem ser removidos")
        assertContentEquals("raw".encodeToByteArray(), destination.resolve("2021.11/DSF1.RAF").readBytes())
        assertContentEquals("edit".encodeToByteArray(), destination.resolve("2021.11/DSF1-HDR.JPG").readBytes())
        assertContentEquals("xmp".encodeToByteArray(), destination.resolve("2021.11/DSF1.RAF.xmp").readBytes())
        assertFalse(destination.resolve("2021.11/CLIP.MOV").exists())
        assertFalse(destination.toFile().walkTopDown().any { it.name == ".DS_Store" })
        assertContentEquals("video".encodeToByteArray(), source.resolve("DCIM/CLIP.MOV").readBytes())
        assertFalse(destination.toFile().walkTopDown().any { ".part-" in it.name })
    }

    @Test fun importNeverOverwritesAndRerunSkipsIdenticalContent() {
        val source = directory("card")
        val destination = directory("archive")
        val sourceFile = file(source.resolve("A.JPG"), "NEW")
        val captureMonth = YearMonth.of(2024, 1)
        file(destination.resolve("2024.01/A.JPG"), "OLD")
        val lines=mutableListOf<String>()
        val archiveCli = cli(lines,dateReader = CaptureDateReader { if (it.fileName == sourceFile.fileName) captureMonth else null })

        assertEquals(0, archiveCli.run(arrayOf("archive", "import", "--source", source.toString(), "--destination", destination.toString())),lines.joinToString(" | "))
        assertEquals("OLD", destination.resolve("2024.01/A.JPG").readText())
        assertEquals("NEW", destination.resolve("2024.01/A__dup1.JPG").readText())
        assertEquals(0, archiveCli.run(arrayOf("archive", "import", "--source", source.toString(), "--destination", destination.toString())))
        assertFalse(destination.resolve("2024.01/A__dup2.JPG").exists())
    }

    @Test fun importCreateNewNeverOverwritesFileCreatedImmediatelyBeforePublication() {
        val source=directory("card-create-new"); val destination=directory("archive-create-new"); directory("archive-create-new/2024.08")
        file(source.resolve("A.JPG"),"source"); var injected=false
        val guarded=ArchiveCli(
            dateReader=CaptureDateReader { YearMonth.of(2024,8) }, processRunner=FakeRunner(), output={},
            beforeCopyPublish={ if (!injected) { injected=true; file(destination.resolve("2024.08/A.JPG"),"concurrent") } },
        )
        assertEquals(0,guarded.run(arrayOf("archive","import","--source",source.toString(),"--destination",destination.toString())))
        assertEquals("concurrent",destination.resolve("2024.08/A.JPG").readText())
        assertEquals("source",destination.resolve("2024.08/A__dup1.JPG").readText())
        assertFalse(destination.toFile().walkTopDown().any { ".part-" in it.name })
    }

    @Test fun simultaneousImportsSerializeAndNeverReplacePublishedEntry() {
        val source=directory("card-concurrent"); val destination=directory("archive-concurrent"); directory("archive-concurrent/2024.09")
        file(source.resolve("A.JPG"),"source")
        val executor=java.util.concurrent.Executors.newFixedThreadPool(2)
        val start=java.util.concurrent.CountDownLatch(1)
        val jobs=(1..2).map { executor.submit<Pair<Int,List<String>>> {
            start.await(5,java.util.concurrent.TimeUnit.SECONDS)
            val lines=mutableListOf<String>()
            cli(lines,dateReader=CaptureDateReader { YearMonth.of(2024,9) })
                .run(arrayOf("archive","import","--source",source.toString(),"--destination",destination.toString())) to lines
        } }
        start.countDown()
        val results=jobs.map { it.get(15,java.util.concurrent.TimeUnit.SECONDS) }
        assertEquals(listOf(0,0),results.map { it.first })
        val summaries=results.map { it.second.single { line -> line.startsWith("Importação:") } }
        assertEquals(1,summaries.count { "1 copiados; 0 idênticos" in it },summaries.joinToString(" | "))
        assertEquals(1,summaries.count { "0 copiados; 1 idênticos" in it },summaries.joinToString(" | "))
        executor.shutdown(); assertTrue(executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(listOf("A.JPG"),destination.resolve("2024.09").listDirectoryEntries().map { it.fileName.toString() })
        assertEquals("source",destination.resolve("2024.09/A.JPG").readText())
    }

    @Test fun crashMarkerPreventsFullLookingPartialFromBeingAcceptedAsSuccess() {
        val source=directory("card-crash-marker"); val destination=directory("archive-crash-marker"); directory("archive-crash-marker/2024.09")
        file(source.resolve("A.JPG"),"source")
        file(destination.resolve("2024.09/A.JPG"),"source")
        file(destination.resolve("2024.09/.A.JPG.phototool-importing"),"phototool-import-v1\n")
        val lines=mutableListOf<String>()
        assertEquals(1,cli(lines,CaptureDateReader { YearMonth.of(2024,9) }).run(arrayOf("archive","import","--source",source.toString(),"--destination",destination.toString())))
        assertTrue(lines.any { "publicação parcial pendente" in it },lines.joinToString(" | "))
        assertFalse(destination.resolve("2024.09/A__dup1.JPG").exists())
    }

    @Test fun archiveStemIdentityUsesUnicodeCasefoldEndToEnd() {
        val source=directory("card-casefold"); val destination=directory("archive-casefold"); directory("archive-casefold/2024.10")
        file(source.resolve("Straße.RAF"),"raw")
        file(source.resolve("STRASSE.RAF.xmp"),"sidecar")
        val reader=CaptureDateReader { path -> if (caseFoldText(path.fileName.toString())=="strasse.raf") YearMonth.of(2024,10) else YearMonth.of(2026,1) }
        assertEquals(0,cli(dateReader=reader).run(arrayOf("archive","import","--source",source.toString(),"--destination",destination.toString())))
        assertTrue(destination.resolve("2024.10/Straße.RAF").exists())
        assertTrue(destination.resolve("2024.10/STRASSE.RAF.xmp").exists(),"casefold-equivalent sidecar stem must inherit the RAW month")

        val verifySource=directory("verify-casefold"); val verifyArchive=directory("verify-casefold-archive")
        file(verifySource.resolve("Straße.JPG"),"same"); file(verifyArchive.resolve("2024.10/STRASSE.JPG"),"same")
        assertEquals(0,cli().run(arrayOf("archive","verify","--card",verifySource.toString(),"--archive",verifyArchive.toString())))
    }

    @Test fun importRejectsSymlinksWithoutPublishingAndCleansIdentityFailure() {
        val source = directory("card")
        val destination = directory("archive")
        val outside = file(temporary.resolve("outside.JPG"), "outside")
        Files.createSymbolicLink(source.resolve("linked.JPG"), outside)
        assertEquals(1, cli().run(arrayOf("archive", "import", "--source", source.toString(), "--destination", destination.toString())))
        assertTrue(destination.listDirectoryEntries().isEmpty())

        Files.delete(source.resolve("linked.JPG"))
        val media = file(source.resolve("A.JPG"), "before")
        directory("archive/2024.04")
        var invoked = false
        val mutating = ArchiveCli(
            dateReader = CaptureDateReader { YearMonth.of(2024, 4) },
            processRunner = FakeRunner(),
            output = {},
            beforeCopyPublish = {
                if (!invoked) {
                    invoked = true
                    media.writeText("after!")
                }
            },
        )
        assertEquals(1, mutating.run(arrayOf("archive", "import", "--source", source.toString(), "--destination", destination.toString())))
        assertFalse(destination.resolve("2024.04/A.JPG").exists())
        assertFalse(destination.toFile().walkTopDown().any { ".part-" in it.name })
    }

    @Test fun importKeepsWritesOnPinnedDirectoryWhenDestinationPathIsSwapped() {
        val source = directory("card")
        val destination = directory("archive")
        val outside = directory("outside-destination")
        val displaced = temporary.resolve("displaced-month")
        file(source.resolve("A.JPG"), "data")
        directory("archive/2024.04")
        var swapped = false
        val guarded = ArchiveCli(
            dateReader = CaptureDateReader { YearMonth.of(2024, 4) },
            processRunner = FakeRunner(),
            output = {},
            beforeCopyPublish = {
                if (!swapped) {
                    swapped = true
                    Files.move(destination.resolve("2024.04"), displaced)
                    Files.createSymbolicLink(destination.resolve("2024.04"), outside)
                }
            },
        )

        assertEquals(1, guarded.run(arrayOf("archive", "import", "--source", source.toString(), "--destination", destination.toString())))
        assertTrue(outside.listDirectoryEntries().isEmpty())
        assertFalse(displaced.resolve("A.JPG").exists())
        assertFalse(displaced.toFile().walkTopDown().any { ".part-" in it.name })
    }

    @Test fun importCleansTemporaryFileWhenStorageFailsBeforePublication() {
        val source = directory("card")
        val destination = directory("archive")
        file(source.resolve("A.JPG"), "data")
        directory("archive/2024.04")
        val failing = ArchiveCli(
            dateReader = CaptureDateReader { YearMonth.of(2024, 4) },
            processRunner = FakeRunner(),
            output = {},
            beforeCopyPublish = { throw java.nio.file.FileSystemException(it.toString(), null, "unidade desconectada") },
        )

        assertEquals(1, failing.run(arrayOf("archive", "import", "--source", source.toString(), "--destination", destination.toString())))
        assertFalse(destination.resolve("2024.04/A.JPG").exists())
        assertFalse(destination.toFile().walkTopDown().any { ".part-" in it.name })
    }

    @Test fun importFallsBackToPlausibleMtime() {
        val source = directory("card")
        val destination = directory("archive")
        val media = file(source.resolve("A.JPG"), "data")
        val undated = file(source.resolve("ORPHAN.XMP"), "metadata")
        Files.setLastModifiedTime(media, FileTime.from(Instant.parse("2020-07-03T00:00:00Z")))
        Files.setLastModifiedTime(undated, FileTime.from(Instant.parse("1900-01-01T00:00:00Z")))
        directory("archive/2020.07")
        directory("archive/Unknown-Date")

        assertEquals(0, cli(dateReader = CaptureDateReader { null }).run(arrayOf("archive", "import", "--source", source.toString(), "--destination", destination.toString())))
        assertTrue(destination.resolve("2020.07/A.JPG").exists())
        assertTrue(destination.resolve("Unknown-Date/ORPHAN.XMP").exists())
    }

    @Test fun importFailsClosedForMissingMonthlyFolderAndSourceHardlinks() {
        val source = directory("card")
        val destination = directory("archive")
        val media = file(source.resolve("A.JPG"), "data")
        val lines = mutableListOf<String>()
        val dated = CaptureDateReader { YearMonth.of(2024, 4) }

        assertEquals(1, cli(lines, dated).run(arrayOf("archive", "import", "--source", source.toString(), "--destination", destination.toString())))
        assertTrue(lines.any { "criação mensal automática foi recusada" in it })
        assertTrue(destination.listDirectoryEntries().isEmpty())

        directory("archive/2024.04")
        val outsideAlias = temporary.resolve("outside-hardlink.JPG")
        Files.createLink(outsideAlias, media)
        val outsideAliasLines = mutableListOf<String>()
        assertEquals(1, cli(outsideAliasLines, dated).run(arrayOf("archive", "import", "--source", source.toString(), "--destination", destination.toString())))
        assertTrue(outsideAliasLines.any { "nlink=2" in it || "hardlink" in it })
        assertTrue(destination.resolve("2024.04").listDirectoryEntries().isEmpty())
        Files.delete(outsideAlias)

        Files.createLink(source.resolve("B.JPG"), media)
        val hardlinkLines = mutableListOf<String>()
        assertEquals(1, cli(hardlinkLines, dated).run(arrayOf("archive", "import", "--source", source.toString(), "--destination", destination.toString())))
        assertTrue(hardlinkLines.any { "hardlink" in it })
        assertTrue(destination.resolve("2024.04").listDirectoryEntries().isEmpty())

        Files.delete(source.resolve("B.JPG"))
        Files.createLink(destination.resolve("2024.04/A.JPG"), media)
        val crossRootLines = mutableListOf<String>()
        assertEquals(1, cli(crossRootLines, dated).run(arrayOf("archive", "import", "--source", source.toString(), "--destination", destination.toString())))
        assertTrue(crossRootLines.any { "hardlink" in it }, crossRootLines.joinToString(" | "))
    }

    @Test fun importGroupsRecognizedDerivativesButNotOrphansOrUnrelatedPrefixesAndPreservesLiteralDupNames() {
        val source = directory("card")
        val destination = directory("archive")
        listOf("2021.01", "2021.02", "2021.03", "2021.04", "2021.05", "2021.06", "2021.07").forEach { directory("archive/$it") }
        val dates = mapOf(
            "A.RAF" to "2021-01-01T12:00:00Z", "A_HDR.JPG" to "2021-02-01T12:00:00Z",
            "B.RAF" to "2021-03-01T12:00:00Z", "B edit.JPG" to "2021-04-01T12:00:00Z",
            "C.RAF" to "2021-05-01T12:00:00Z", "C(edit).JPG" to "2021-06-01T12:00:00Z",
            "ORPHAN-HDR.JPG" to "2021-06-01T12:00:00Z", "UNRELATEDNESS.RAF" to "2021-07-01T12:00:00Z",
            "UNRELATED-edit.JPG" to "2021-04-01T12:00:00Z", "L__dup1.JPG" to "2021-02-01T12:00:00Z",
        )
        dates.keys.forEach { file(source.resolve(it), it) }
        file(destination.resolve("2021.02/L__dup1.JPG"), "existing")
        val reader = CaptureDateReader { path -> YearMonth.parse(dates.getValue(path.fileName.toString()).take(7)) }

        assertEquals(0, cli(dateReader = reader).run(arrayOf("archive", "import", "--source", source.toString(), "--destination", destination.toString())))

        assertTrue(destination.resolve("2021.01/A_HDR.JPG").exists())
        assertTrue(destination.resolve("2021.03/B edit.JPG").exists())
        assertTrue(destination.resolve("2021.05/C(edit).JPG").exists())
        assertTrue(destination.resolve("2021.06/ORPHAN-HDR.JPG").exists())
        assertTrue(destination.resolve("2021.04/UNRELATED-edit.JPG").exists())
        assertTrue(destination.resolve("2021.07/UNRELATEDNESS.RAF").exists())
        assertTrue(destination.resolve("2021.02/L__dup1__dup1.JPG").exists())
    }

    @Test fun verifyDoesNotTreatLiteralDupSuffixAsAnotherFilesGeneratedDuplicate() {
        val card = directory("card")
        val archive = directory("archive")
        file(card.resolve("A__dup1.JPG"), "literal")
        file(archive.resolve("2024.01/A.JPG"), "literal")
        val lines = mutableListOf<String>()

        assertEquals(1, cli(lines).run(arrayOf("archive", "verify", "--card", card.toString(), "--archive", archive.toString())))
        assertTrue(lines.any { it.startsWith("AUSENTE") })
    }

    @Test fun rsyncBlocksDeleteForEmptySourceWithoutStartingAProcess() {
        val source = directory("source")
        val destination = directory("destination")
        file(destination.resolve("keep.RAF"), "important")
        val runner = FakeRunner()

        val exit = cli(processRunner = runner).run(arrayOf("archive", "rsync", "--source", source.toString(), "--destination", destination.toString(), "--delete"))

        assertEquals(1, exit)
        assertTrue(runner.calls.isEmpty())
        assertEquals("important", destination.resolve("keep.RAF").readText())
    }

    @Test fun rsyncDeleteIsPreviewOnlyAndNeverExecutes() {
        val source = directory("source")
        val destination = directory("destination")
        file(source.resolve("A.RAF"), "data")
        val lines = mutableListOf<String>()
        val runner = FakeRunner(dryOutput = "*deleting old.RAF\n>f+++++++++ A.RAF\n")

        val exit = cli(lines, processRunner = runner).run(arrayOf("archive", "rsync", "--source", source.toString(), "--destination", destination.toString(), "--delete", "--checksum", "--exclude-videos"))

        assertEquals(1, exit)
        assertEquals(1, runner.calls.size)
        assertTrue(runner.calls.single().containsAll(listOf("--dry-run", "-i", "--delete", "--checksum", "--exclude=.*")))
        assertTrue(runner.calls.single().any { it == "--exclude=*.[mM][oO][vV]" })
        assertTrue(runner.calls.single().first().startsWith("/"))
        assertTrue(lines.any { "NÃO EXECUTADA" in it })
        assertTrue(lines.any { "toda execução real de rsync" in it })
    }

    @Test fun rsyncWithoutDeleteIsAlsoPreviewOnlyAndNeverOffersConfirmation() {
        val source = directory("source")
        val destination = directory("destination")
        file(source.resolve("A.RAF"), "data")
        val runner = FakeRunner()
        val lines = mutableListOf<String>()

        assertEquals(1, cli(lines, processRunner = runner).run(arrayOf("archive", "rsync", "--source", source.toString(), "--destination", destination.toString(), "--checksum")))
        assertEquals(1, runner.calls.size)
        assertTrue("--dry-run" in runner.calls.single())
        assertTrue(lines.any { "NÃO EXECUTADA" in it })
        assertTrue(lines.none { "--confirm" in it || "concluído" in it })

        val invalidLines = mutableListOf<String>()
        assertEquals(2, cli(invalidLines, processRunner = runner).run(arrayOf("archive", "rsync", "--source", source.toString(), "--destination", destination.toString(), "--confirm", "token")))
        assertEquals(1, runner.calls.size)
        assertTrue(invalidLines.any { "opção desconhecida: --confirm" in it })
    }

    @Test fun rsyncRefusesVisibleSymlinkAndNeverUsesShell() {
        val source = directory("source")
        val destination = directory("destination")
        val outside = file(temporary.resolve("outside.RAF"), "x")
        Files.createSymbolicLink(source.resolve("A.RAF"), outside)
        val runner = FakeRunner()
        assertEquals(1, cli(processRunner = runner).run(arrayOf("archive", "rsync", "--source", source.toString(), "--destination", destination.toString())))
        assertTrue(runner.calls.isEmpty())
    }

    @Test fun rsyncLongDeletePreviewReportsRealTotalAndEveryDeletion() {
        val source = directory("source")
        val destination = directory("destination")
        file(source.resolve("keep.RAF"), "data")
        val preview = (1..250).joinToString("\n", postfix = "\n") { "*deleting old-$it.RAF" }
        val lines = mutableListOf<String>()
        val runner = FakeRunner(preview)

        assertEquals(1, cli(lines, processRunner = runner).run(arrayOf("archive", "rsync", "--source", source.toString(), "--destination", destination.toString(), "--delete")))

        assertTrue(lines.any { "250 alterações, 250 exclusões" in it })
        assertEquals(250, lines.count { it.startsWith("*deleting") })
        assertTrue(lines.any { "PRÉVIA EXTENSA" in it })
        assertEquals(1, runner.calls.size)
    }

    @Test fun systemProcessRunnerKillsLateGrandchildGroupAndJoinsCollectorsOnTimeout() {
        val result = SystemArchiveProcessRunner().run(
            listOf("/bin/sh", "-c", "(sleep 0.05; sleep 30 & grandchild=\$!; echo \$grandchild; wait) & wait"),
            timeoutMillis = 250,
            maximumOutputBytes = 4096,
        )

        assertTrue(result.timedOut)
        val grandchildPid = result.stdout.trim().lineSequence().first().toLong()
        assertFalse(ProcessHandle.of(grandchildPid).map { it.isAlive }.orElse(false), "o neto tardio deve estar comprovadamente encerrado")
    }

    private fun cli(
        lines: MutableList<String> = mutableListOf(),
        dateReader: CaptureDateReader = CaptureDateReader { null },
        processRunner: ArchiveProcessRunner = FakeRunner(),
    ) = ArchiveCli(
        dateReader = dateReader,
        processRunner = processRunner,
        output = lines::add,
        storageIsIndependent = { _, _ -> true },
    )

    private fun directory(relative: String): Path = temporary.resolve(relative).also { Files.createDirectories(it) }
    private fun file(path: Path, content: String): Path = path.also { Files.createDirectories(it.parent); it.writeText(content) }

    private fun treeState(root: Path): Map<String, Pair<Long, String>> = root.toFile().walkTopDown().filter { it.isFile }.associate {
        root.relativize(it.toPath()).toString() to (it.lastModified() to it.readBytes().joinToString("") { byte -> "%02x".format(byte) })
    }

    private class FakeRunner(private val dryOutput: String = ">f+++++++++ A.RAF\n") : ArchiveProcessRunner {
        val calls = mutableListOf<List<String>>()
        private var executed = false
        override fun run(arguments: List<String>, timeoutMillis: Long, maximumOutputBytes: Int): ArchiveProcessResult {
            calls += arguments.toList()
            assertEquals(120_000L, timeoutMillis)
            assertEquals(1024 * 1024, maximumOutputBytes)
            val stdout = if ("--dry-run" in arguments) {
                if (executed) "" else dryOutput
            } else {
                executed = true
                "executado\n"
            }
            return ArchiveProcessResult(0, stdout, "")
        }
    }
}
