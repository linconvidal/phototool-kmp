package br.com.lincon.phototool.desktop

import br.com.lincon.phototool.api.*

import br.com.lincon.phototool.domain.ColorLabel
import br.com.lincon.phototool.domain.EditorialCommand
import br.com.lincon.phototool.domain.EditorialState
import br.com.lincon.phototool.domain.Flag
import br.com.lincon.phototool.domain.GpsFilter
import br.com.lincon.phototool.domain.MediaIdentity
import br.com.lincon.phototool.domain.MediaKind
import br.com.lincon.phototool.domain.MetadataStatus
import br.com.lincon.phototool.domain.ObservedMetadata
import br.com.lincon.phototool.domain.Photo
import br.com.lincon.phototool.domain.Query
import br.com.lincon.phototool.domain.filterAndOrder
import br.com.lincon.phototool.state.SyncPhase
import br.com.lincon.phototool.state.SyncStatus
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MobileCurationTest {
    @Test fun startupUsesStructurallyValidSnapshotWithoutCallingScannerOrReadingMedia() {
        val root = createTempDirectory("mobile-cache-library")
        val media = root.resolve("A.JPG").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val cacheDirectory = createTempDirectory("mobile-cache")
        val authoringCache = PhotoCache(cacheDirectory, root)
        val indexed = scanPhoto(root)
        authoringCache.publish(listOf(indexed))
        media.writeBytes(byteArrayOf(9, 9, 9, 9))
        assertTrue(authoringCache.load().isEmpty(), "strict desktop load must still reject stale media")

        val scans = AtomicInteger()
        val cacheOnly = PhotoCache(cacheDirectory, root, validateLibraryDescendantsOnOpen = false)
        val projection = PhotoCacheProjection(cacheOnly)
        val authority = MemoryEditorialAuthority(listOf(indexed))
        val application = application(
            projection = projection,
            authority = authority,
            scanner = CurationScanner { _, _ -> scans.incrementAndGet(); ScanResult(emptyList(), 0) },
        )
        try {
            assertEquals(0, scans.get())
            assertEquals(1, application.status().photoCount)
            assertEquals(ProjectionStateDto.READY, application.status().projectionState)
        } finally { application.close() }
    }

    @Test fun synchronizationIsExplicitAndSingleFlight() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scans = AtomicInteger()
        val refreshed = samplePhoto("fresh")
        val projection = MemoryProjection(listOf(samplePhoto("old")))
        val application = application(
            projection = projection,
            scanner = CurationScanner { _, progress ->
                scans.incrementAndGet()
                progress(SyncStatus(SyncPhase.METADATA, photos = 0, running = true, totalPhotos = 1, message = "Lendo"))
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                ScanResult(listOf(refreshed), 0)
            },
        )
        try {
            assertEquals(0, scans.get())
            val first = application.requestSync()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = application.requestSync()
            assertTrue(first.started)
            assertFalse(second.started)
            assertEquals(first.flightId, second.flightId)
            assertEquals(1, scans.get())
            release.countDown()
            application.awaitIdle()
            assertEquals("COMPLETE", application.status().sync.phase)
            assertEquals(1, application.status().photoCount)
        } finally { release.countDown(); application.close() }
    }

    @Test fun paginationIsBoundedStableAndRejectsUnsupportedLimits() {
        val photos = (1..75).map { index -> samplePhoto("p$index", capturedAt = "2025-01-${(index % 28 + 1).toString().padStart(2, '0')}T10:00:00") }
        val application = application(MemoryProjection(photos))
        try {
            val first = application.page(PhotoQueryInput(page = 1, pageSize = 20))
            val second = application.page(PhotoQueryInput(page = 2, pageSize = 20))
            assertEquals(75, first.total)
            assertEquals(20, first.items.size)
            assertEquals(20, second.items.size)
            assertTrue(first.items.map { it.id }.toSet().intersect(second.items.map { it.id }.toSet()).isEmpty())
            assertFailsWith<InvalidQueryException> { application.page(PhotoQueryInput(pageSize = MAX_PAGE_SIZE + 1)) }
            assertFailsWith<InvalidQueryException> { application.page(PhotoQueryInput(page = 0)) }
        } finally { application.close() }
    }

    @Test fun negativeTermsAndQuotedPhrasesMatchSharedRulesExactly() {
        val photos = listOf(
            samplePhoto("keep", camera = "FUJIFILM X-Pro2", keywords = listOf("rua")),
            samplePhoto("personal", camera = "FUJIFILM X-Pro2", keywords = listOf("personal")),
            samplePhoto("place", camera = "FUJIFILM X-Pro2", keywords = listOf("São Paulo")),
            samplePhoto("canon", camera = "Canon R5", keywords = listOf("rua")),
        )
        val search = "fuji -personal -\"São Paulo\""
        val expected = filterAndOrder(photos, Query(search = search)).map { it.id }
        val application = application(MemoryProjection(photos))
        try {
            assertEquals(expected, application.page(PhotoQueryInput(search = search)).items.map { it.id })
            assertEquals(listOf("keep"), expected)
        } finally { application.close() }
    }

    @Test fun combinedKeywordDateFlagCameraLensRatingGpsAndFolderFiltersCompose() {
        val match = samplePhoto(
            id = "match", folder = "2025/Street", capturedAt = "2025-06-10T10:00:00-03:00", camera = "FUJIFILM X-Pro2",
            lens = "XF23mmF2", keywords = listOf("Rua"), editorial = EditorialState(Flag.PICK, 4, keywords = listOf("Rua")), gps = true,
        )
        val misses = listOf(
            match.copy(id = "rating", editorial = match.editorial.copy(rating = 2)),
            match.copy(id = "gps", metadata = match.metadata.copy(latitude = null, longitude = null)),
            match.copy(id = "folder", folder = "2024/Other"),
            match.copy(id = "date", metadata = match.metadata.copy(capturedAt = "2024-01-01T10:00:00")),
        )
        val application = application(MemoryProjection(listOf(match) + misses))
        try {
            val input = PhotoQueryInput(
                keyword = "Rua", fromDate = "2025-06-01", toDate = "2025-06-30", flag = Flag.PICK,
                camera = "FUJIFILM X-Pro2", lens = "XF23mmF2", minimumStars = 3, gps = GpsFilter.PRESENT,
                folderId = folderId("2025/Street"),
            )
            assertEquals(listOf("match"), application.page(input).items.map { it.id })
        } finally { application.close() }
    }

    @Test fun serializedDtoKeysUseAnExplicitPathFreeAllowlist() {
        val photo = samplePhoto("dto", folder = "private/folder")
        val application = application(MemoryProjection(listOf(photo)))
        try {
            val codec = Json { encodeDefaults = true; explicitNulls = true }
            val page = codec.parseToJsonElement(codec.encodeToString(application.page(PhotoQueryInput()))).jsonObject
            val detail = codec.parseToJsonElement(codec.encodeToString(application.detail(PhotoQueryInput(), photo.id))).jsonObject
            assertEquals(setOf("snapshotGeneration", "page", "pageSize", "total", "totalPages", "items"), page.keys)
            assertEquals(setOf("id", "displayName", "format", "aspectRatio", "metadata", "editorial", "writable", "issueCode", "previewUrl", "previousId", "nextId"), detail.keys)
            val forbidden = setOf("path", "authority", "raw", "jpeg", "fileKey", "inode", "cachePath", "folder")
            assertTrue(allKeys(page).none { it in forbidden })
            assertTrue(allKeys(detail).none { it in forbidden })
            assertFalse(codec.encodeToString(application.detail(PhotoQueryInput(), photo.id)).contains("private/folder"))
        } finally { application.close() }
    }

    @Test fun httpPreviewHasBoundedDimensionAndConditionalEtag() = testApplication {
        val photo = samplePhoto("preview")
        val previewFile = createTempDirectory().resolve("bounded.jpg")
        ImageIO.write(BufferedImage(20, 12, BufferedImage.TYPE_INT_RGB), "jpeg", previewFile.toFile())
        val previewBytes = previewFile.readBytes()
        val service = application(
            MemoryProjection(listOf(photo)),
            previews = object : PreviewAuthority {
                override fun preview(photo: Photo, maximumDimension: Int) = PreviewPayload(previewBytes, "fixture-etag")
                override fun close() = Unit
            },
        )
        application { mobileCurationModule(service, HttpSecurityConfiguration(setOf("localhost"), csrfToken = "csrf")) }
        try {
            val first = client.get("/api/v1/photos/preview/preview?max=640") { header(HttpHeaders.Host, "localhost") }
            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals("\"fixture-etag\"", first.headers[HttpHeaders.ETag])
            assertTrue(first.headers[HttpHeaders.ContentType]?.startsWith("image/jpeg") == true)
            val cached = client.get("/api/v1/photos/preview/preview?max=640") {
                header(HttpHeaders.Host, "localhost")
                header(HttpHeaders.IfNoneMatch, "\"fixture-etag\"")
            }
            assertEquals(HttpStatusCode.NotModified, cached.status)
            val oversized = client.get("/api/v1/photos/preview/preview?max=4097") { header(HttpHeaders.Host, "localhost") }
            assertEquals(HttpStatusCode.BadRequest, oversized.status)
        } finally { service.close() }
    }

    @Test fun previewFailuresDistinguishStaleAndUnsupportedWithoutHttp500() = testApplication {
        val photos=listOf(samplePhoto("stale"),samplePhoto("broken-tiff"))
        val service=application(
            MemoryProjection(photos),
            previews=object:PreviewAuthority {
                override fun preview(photo:Photo,maximumDimension:Int):PreviewPayload = when(photo.id) {
                    "stale" -> throw PreviewException(PreviewFailure.STALE_SOURCE,"changed")
                    else -> throw PreviewException(PreviewFailure.UNSUPPORTED_MEDIA,"invalid TIFF")
                }
                override fun close()=Unit
            },
        )
        application { mobileCurationModule(service,HttpSecurityConfiguration(setOf("localhost"),csrfToken="csrf")) }
        try {
            val stale=client.get("/api/v1/photos/stale/preview") { header(HttpHeaders.Host,"localhost") }
            assertEquals(HttpStatusCode.Conflict,stale.status); assertTrue(stale.bodyAsText().contains("stale-preview"))
            val malformed=client.get("/api/v1/photos/broken-tiff/preview") { header(HttpHeaders.Host,"localhost") }
            assertEquals(HttpStatusCode.UnsupportedMediaType,malformed.status); assertTrue(malformed.bodyAsText().contains("preview-unsupported"))
            assertNotEquals(HttpStatusCode.InternalServerError,malformed.status)
        } finally { service.close() }
    }

    @Test fun httpMutationsRequireAllowedHostSameOriginCsrfAndCurrentRevision() = testApplication {
        val photo = samplePhoto("secure")
        val projection = MemoryProjection(listOf(photo))
        val authority = MemoryEditorialAuthority(listOf(photo))
        val service = application(projection, authority, writeEnabled = true)
        application { mobileCurationModule(service, HttpSecurityConfiguration(setOf("localhost"), csrfToken = "csrf-token")) }
        val body = """{"value":4,"expectedEditorialRevision":"${editorialRevision(photo.editorial)}"}"""
        try {
            val wrongHost = client.post("/api/v1/photos/secure/rating") {
                header(HttpHeaders.Host, "evil.example")
                header(HttpHeaders.Origin, "http://evil.example")
                header("X-PhotoTool-CSRF", "csrf-token")
                contentType(ContentType.Application.Json); setBody(body)
            }
            assertEquals(421, wrongHost.status.value)
            val noCsrf = mutationRequest("/api/v1/photos/secure/rating", body, csrf = null)
            assertEquals(HttpStatusCode.Forbidden, noCsrf.status)
            val wrongOrigin = mutationRequest("/api/v1/photos/secure/rating", body, origin = "http://other")
            assertEquals(HttpStatusCode.Forbidden, wrongOrigin.status)
            val saved = mutationRequest("/api/v1/photos/secure/rating", body)
            assertEquals(HttpStatusCode.OK, saved.status)
            val stale = mutationRequest("/api/v1/photos/secure/rating", body)
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertEquals(4, authority.current(photo.id).rating)
        } finally { service.close() }
    }

    @Test fun chunkedMutationBodyIsRejectedBeforeUnboundedBuffering() = testApplication {
        val photo = samplePhoto("bounded-body")
        val service = application(MemoryProjection(listOf(photo)), writeEnabled = true)
        application { mobileCurationModule(service, HttpSecurityConfiguration(setOf("localhost"), csrfToken = "csrf-token")) }
        try {
            val oversized = ByteArray(8_192) { 'x'.code.toByte() }
            val response = client.post("/api/v1/photos/bounded-body/rating") {
                header(HttpHeaders.Host, "localhost")
                header(HttpHeaders.Origin, "http://localhost")
                header("X-PhotoTool-CSRF", "csrf-token")
                setBody(object : OutgoingContent.WriteChannelContent() {
                    override val contentType: ContentType = ContentType.Application.Json
                    override suspend fun writeTo(channel: ByteWriteChannel) = channel.writeFully(oversized)
                })
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            val syncResponse = client.post("/api/v1/sync") {
                header(HttpHeaders.Host, "localhost")
                header(HttpHeaders.Origin, "http://localhost")
                header("X-PhotoTool-CSRF", "csrf-token")
                setBody(object : OutgoingContent.WriteChannelContent() {
                    override suspend fun writeTo(channel: ByteWriteChannel) = channel.writeFully(oversized)
                })
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, syncResponse.status)
        } finally { service.close() }
    }

    @Test fun flagRatingLabelAndKeywordMutationsUseXmpReadbackAndNeverChangeRawOrJpeg() {
        val root = createTempDirectory("mobile-write-library")
        val raw = root.resolve("IMG.DNG").also { it.writeBytes(byteArrayOf(1, 3, 5, 7)) }
        val jpeg = root.resolve("IMG.JPG").also { ImageIO.write(BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "jpeg", it.toFile()) }
        val beforeRaw = sha256(raw)
        val beforeJpeg = sha256(jpeg)
        val indexed = scanPhoto(root)
        val cache = PhotoCache(createTempDirectory("mobile-write-cache"), root)
        cache.publish(listOf(indexed))
        val projection = PhotoCacheProjection(cache)
        val xmp = XmpEditorialAuthority(XmpSidecarStore(root, true))
        val application = CurationApplicationService(
            projection,
            EditorialPersistenceService(xmp, projection),
            NoPreviewAuthority,
            CurationScanner { _, _ -> error("scan must remain explicit") },
            writeEnabled = true,
        )
        try {
            var revision = application.detail(PhotoQueryInput(), indexed.id).editorial.revision
            assertEquals(indexed.editorial.rating, application.page(PhotoQueryInput()).items.single().editorial.rating)
            revision = application.mutate(indexed.id, revision, EditorialCommand.SetFlag(Flag.PICK)).editorial.revision
            revision = application.mutate(indexed.id, revision, EditorialCommand.SetRating(5)).editorial.revision
            revision = application.mutate(indexed.id, revision, EditorialCommand.SetLabel(ColorLabel.GREEN)).editorial.revision
            val added = application.mutate(indexed.id, revision, EditorialCommand.AddKeyword("  móvel   2026 "))
            assertEquals(listOf("móvel 2026"), added.editorial.keywords)
            val readback = XmpSidecarStore(root, false).use { it.read(indexed) }
            assertEquals(Flag.PICK, readback.flag)
            assertEquals(5, readback.rating)
            assertEquals(ColorLabel.GREEN, readback.label)
            assertEquals(listOf("móvel 2026"), readback.keywords)
            val removed = application.mutate(indexed.id, added.editorial.revision, EditorialCommand.RemoveKeyword("móvel 2026"))
            assertTrue(removed.editorial.keywords.isEmpty())
            assertTrue(XmpSidecarStore(root, false).use { it.read(indexed) }.keywords.isEmpty())
            assertEquals(removed.editorial, application.detail(PhotoQueryInput(), indexed.id).editorial)
            assertEquals(removed.editorial.copy(revision = editorialRevision(cache.loadCacheOnly().single().editorial)), cache.loadCacheOnly().single().editorial.toDto())
            val expandedFold = "ß".repeat(160)
            val expanded = application.mutate(indexed.id, removed.editorial.revision, EditorialCommand.AddKeyword(expandedFold))
            assertEquals(expandedFold, cache.loadCacheOnly().single().editorial.keywords.single())
            assertEquals(expanded.editorial, application.detail(PhotoQueryInput(), indexed.id).editorial)
            assertEquals(beforeRaw, sha256(raw))
            assertEquals(beforeJpeg, sha256(jpeg))
        } finally { application.close(); xmp.close() }
    }

    @Test fun invalidCommandDoesNotLeaveProjectionDegraded() {
        val photo = samplePhoto("invalid-command")
        val projection = MemoryProjection(listOf(photo))
        val service = application(projection, writeEnabled = true)
        try {
            assertFailsWith<IllegalArgumentException> {
                service.mutate(photo.id, editorialRevision(photo.editorial), EditorialCommand.SetRating(6))
            }
            assertFalse(projection.projectionDegraded())
            assertEquals(ProjectionStateDto.READY, service.status().projectionState)
        } finally { service.close() }
    }

    @Test fun staleRevisionReturnsAuthoritativeConflictWithoutWriting() {
        val photo = samplePhoto("conflict", editorial = EditorialState(rating = 2))
        val authority = MemoryEditorialAuthority(listOf(photo))
        val application = application(MemoryProjection(listOf(photo)), authority, writeEnabled = true)
        try {
            val error = assertFailsWith<EditorialRevisionConflictException> {
                application.mutate(photo.id, editorialRevision(EditorialState()), EditorialCommand.SetRating(5))
            }
            assertEquals(2, error.authoritative.rating)
            assertEquals(2, application.detail(PhotoQueryInput(), photo.id).editorial.rating)
            assertEquals(0, authority.mutations.get())
        } finally { application.close() }
    }

    @Test fun xmpSuccessAndProjectionFailureAreReportedSeparatelyAndBlockFurtherMutations() {
        val photo = samplePhoto("partial")
        val projection = MemoryProjection(listOf(photo)).apply { failUpdates = true }
        val authority = MemoryEditorialAuthority(listOf(photo))
        val application = application(projection, authority, writeEnabled = true)
        try {
            val partial = assertFailsWith<PartialProjectionException> {
                application.mutate(photo.id, editorialRevision(photo.editorial), EditorialCommand.SetRating(3))
            }
            assertTrue(partial.result.xmpPersisted)
            assertFalse(partial.result.projectionUpdated)
            assertEquals(3, partial.result.editorial.rating)
            assertEquals(ProjectionStateDto.DEGRADED, application.status().projectionState)
            assertFailsWith<ProjectionDegradedException> {
                application.mutate(photo.id, partial.result.editorial.revision, EditorialCommand.SetRating(4))
            }
            assertEquals(1, authority.mutations.get())
        } finally { application.close() }

        val restarted = application(projection, authority, writeEnabled = true)
        try {
            assertEquals(ProjectionStateDto.DEGRADED, restarted.status().projectionState)
            assertFailsWith<ProjectionDegradedException> {
                restarted.mutate(photo.id, editorialRevision(authority.current(photo.id)), EditorialCommand.SetRating(4))
            }
        } finally { restarted.close() }
    }

    @Test fun externalCacheGenerationRefreshesServerSnapshot() {
        val root = createTempDirectory("mobile-external-generation-library")
        root.resolve("A.JPG").writeBytes(byteArrayOf(1))
        root.resolve("B.JPG").writeBytes(byteArrayOf(2))
        val cacheDirectory = createTempDirectory("mobile-external-generation-cache")
        val first = PhotoCache(cacheDirectory, root)
        val indexed = LibraryScanner(object : MediaObservationAdapter {
            override fun observe(bytes: ByteArray, kind: MediaKind) = ObservedMetadata(width = 40, height = 30, status = MetadataStatus.AVAILABLE)
        }).scan(root, AtomicBoolean()) { }.photos
        first.publish(indexed.take(1))
        val application = application(PhotoCacheProjection(first), MemoryEditorialAuthority(indexed))
        val before = application.status().snapshotGeneration
        val second = PhotoCache(cacheDirectory, root, validateLibraryDescendantsOnOpen = false)
        second.withExclusiveProjection { second.publish(indexed) }
        try {
            val after = application.status()
            assertNotEquals(before, after.snapshotGeneration)
            assertEquals(2, after.photoCount)
        } finally { application.close() }
    }

    @Test fun transientMarkerFromAnotherWriterRecoversWithoutExplicitRepair() {
        val photo = samplePhoto("transient-marker")
        val projection = MemoryProjection(listOf(photo)).apply { markProjectionDegraded() }
        val service = application(projection, writeEnabled = true)
        try {
            assertEquals(ProjectionStateDto.DEGRADED, service.status().projectionState)
            projection.clearProjectionDegraded()
            assertEquals(ProjectionStateDto.READY, service.status().projectionState)
        } finally { service.close() }
    }

    @Test fun exactXmpByteRevisionRejectsUnmanagedChangeBeforeEditorialMutation() {
        val root = createTempDirectory("mobile-exact-revision-library")
        val jpeg = root.resolve("A.JPG")
        ImageIO.write(BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "jpeg", jpeg.toFile())
        val indexed = scanPhoto(root)
        val cache = PhotoCache(createTempDirectory("mobile-exact-revision-cache"), root)
        cache.publish(listOf(indexed))
        val projection = PhotoCacheProjection(cache)
        val store = XmpSidecarStore(root, true)
        val authority = XmpEditorialAuthority(store)
        val application = CurationApplicationService(
            projection,
            EditorialPersistenceService(authority, projection),
            NoPreviewAuthority,
            CurationScanner { _, _ -> error("unexpected scan") },
            writeEnabled = true,
        )
        try {
            val before = application.detail(PhotoQueryInput(), indexed.id).editorial
            store.mutateDevelopProperties(indexed, mapOf("Exposure2012" to "1.00"))
            val conflict = assertFailsWith<EditorialRevisionConflictException> {
                application.mutate(indexed.id, before.revision, EditorialCommand.SetRating(4))
            }
            assertNotEquals(before.revision, conflict.authoritative.revision)
            assertEquals(0, store.read(indexed).rating)
        } finally { application.close(); store.close() }
    }

    @Test fun desktopPersistencePathMarksProjectionBeforeXmpAndKeepsGateAfterCacheFailure() {
        val photo = samplePhoto("desktop-partial")
        val projection = MemoryProjection(listOf(photo)).apply { failUpdates = true }
        val authority = MemoryEditorialAuthority(listOf(photo))
        val persistence = EditorialPersistenceService(authority, projection)
        val failure = assertFailsWith<ProjectionUpdateAfterXmpException> {
            persistence.persistAndProject(photo, photo.editorial.copy(rating = 4))
        }
        assertEquals(4, failure.authoritative.rating)
        assertTrue(projection.projectionDegraded())
        assertEquals(1, authority.mutations.get())
    }

    @Test fun desktopPatchPreservesAuthoritativeFieldsChangedByAnotherWriter() {
        val base = EditorialState(keywords = listOf("base"))
        val stalePhoto = samplePhoto("desktop-merge", editorial = base)
        val authoritative = base.copy(label = ColorLabel.GREEN, keywords = listOf("base", "external"))
        val authority = MemoryEditorialAuthority(listOf(stalePhoto.copy(editorial = authoritative)))
        val projection = MemoryProjection(listOf(stalePhoto.copy(editorial = authoritative)))
        val persisted = EditorialPersistenceService(authority, projection).persistAndProject(
            stalePhoto,
            base.copy(rating = 4, keywords = listOf("base", "mobile")),
        )
        assertEquals(4, persisted.rating)
        assertEquals(ColorLabel.GREEN, persisted.label)
        assertEquals(listOf("base", "external", "mobile"), persisted.keywords)
    }

    @Test fun cacheOnlyStartupRefusesToCreateAnUnprovenCacheDirectory() {
        val root = createTempDirectory("mobile-existing-library")
        val absentCache = createTempDirectory("mobile-cache-parent").resolve("absent")
        assertFailsWith<IllegalArgumentException> {
            PhotoCache(absentCache, root, validateLibraryDescendantsOnOpen = false)
        }
        assertFalse(Files.exists(absentCache, LinkOption.NOFOLLOW_LINKS))
    }

    @Test fun projectionFileLockExcludesAnotherLocalCacheWriter() {
        val root = createTempDirectory("mobile-locked-library")
        root.resolve("A.JPG").writeBytes(byteArrayOf(1, 2, 3))
        val cacheDirectory = createTempDirectory("mobile-locked-cache")
        val initial = PhotoCache(cacheDirectory, root)
        initial.publish(listOf(scanPhoto(root)))
        val first = PhotoCache(cacheDirectory, root, validateLibraryDescendantsOnOpen = false)
        val second = PhotoCache(cacheDirectory, root, validateLibraryDescendantsOnOpen = false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit {
            first.withExclusiveProjection {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFailsWith<ProjectionBusyException> { second.withExclusiveProjection { Unit } }
            assertFailsWith<ProjectionBusyException> { second.withSharedProjection { Unit } }
        } finally {
            release.countDown()
            future.get(5, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }

    @Test fun synchronizationMarksDegradedBeforePublishingIncompleteXmpReadback() {
        val photo = samplePhoto("sync-xmp-error")
        val projection = MemoryProjection(listOf(photo))
        val authority = MemoryEditorialAuthority(listOf(photo)).apply { failReads = true }
        val application = application(
            projection,
            authority,
            scanner = CurationScanner { _, _ -> ScanResult(listOf(photo), 0) },
        )
        try {
            application.requestSync()
            application.awaitIdle()
            assertTrue(projection.degradedAtPublish)
            assertEquals(ProjectionStateDto.DEGRADED, application.status().projectionState)
        } finally { application.close() }
    }

    @Test fun durableProjectionMarkerSurvivesCacheReopenAndSuccessfulSyncRepairsIt() {
        val root = createTempDirectory("mobile-degraded-library")
        root.resolve("A.JPG").writeBytes(byteArrayOf(1, 2, 3))
        val cacheDirectory = createTempDirectory("mobile-degraded-cache")
        val cache = PhotoCache(cacheDirectory, root)
        val indexed = scanPhoto(root)
        cache.publish(listOf(indexed))
        cache.markProjectionDegraded()

        val reopened = PhotoCache(cacheDirectory, root, validateLibraryDescendantsOnOpen = false)
        val projection = PhotoCacheProjection(reopened)
        val authority = MemoryEditorialAuthority(listOf(indexed)).apply { failReads = true }
        val application = application(
            projection,
            authority,
            scanner = CurationScanner { _, _ -> ScanResult(listOf(indexed), 0) },
            writeEnabled = true,
        )
        try {
            assertEquals(ProjectionStateDto.DEGRADED, application.status().projectionState)
            application.requestSync()
            application.awaitIdle()
            assertEquals("COMPLETE", application.status().sync.phase)
            assertTrue(projection.projectionDegraded())
            assertEquals(ProjectionStateDto.DEGRADED, application.status().projectionState)
            authority.failReads = false
            application.requestSync()
            application.awaitIdle()
            assertEquals(ProjectionStateDto.READY, application.status().projectionState)
            assertFalse(reopened.projectionDegraded())
        } finally { application.close() }
    }

    @Test fun facetsAreBoundedAndCountKeywordOccurrences() {
        val shared = (1..3).map { samplePhoto("shared-$it", keywords = listOf("rua")) }
        val many = (1..(MAX_FACET_OPTIONS + 20)).map { samplePhoto("facet-$it", keywords = listOf("keyword-$it")) }
        val application = application(MemoryProjection(shared + many))
        try {
            val facets = application.facets()
            assertTrue(facets.truncated)
            assertTrue(facets.keywords.size <= MAX_FACET_OPTIONS)
            assertEquals(3, facets.keywords.single { it.value == "rua" }.count)
        } finally { application.close() }
    }

    @Test fun synchronizationFailureDoesNotExposeFilesystemMessage() {
        val application = application(
            MemoryProjection(listOf(samplePhoto("safe-status"))),
            scanner = CurationScanner { _, _ -> error("/private/library/secret.JPG could not be read") },
        )
        try {
            application.requestSync()
            application.awaitIdle()
            val status = application.status()
            assertEquals("FAILED", status.sync.phase)
            assertFalse(status.sync.message.contains("/private"))
            val encoded = Json.encodeToString(status)
            assertFalse(encoded.contains("secret.JPG"))
        } finally { application.close() }
    }

    private fun application(
        projection: SnapshotProjection,
        authority: MemoryEditorialAuthority = MemoryEditorialAuthority(projection.loadCacheOnly()),
        scanner: CurationScanner = CurationScanner { _, _ -> error("unexpected scan") },
        previews: PreviewAuthority = NoPreviewAuthority,
        writeEnabled: Boolean = false,
    ): CurationApplicationService = CurationApplicationService(
        projection,
        EditorialPersistenceService(authority, projection),
        previews,
        scanner,
        writeEnabled,
    )

    private fun samplePhoto(
        id: String,
        folder: String = "2025",
        capturedAt: String = "2025-06-10T10:00:00",
        camera: String = "FUJIFILM X-Pro2",
        lens: String = "XF23mmF2",
        keywords: List<String> = emptyList(),
        editorial: EditorialState = EditorialState(keywords = keywords),
        gps: Boolean = false,
    ): Photo = Photo(
        id = id,
        folder = folder,
        stem = id,
        authorityPath = "$folder/$id.xmp",
        jpegPath = "$folder/$id.JPG",
        previewPath = "$folder/$id.JPG",
        sourceIdentity = MediaIdentity("$folder/$id.JPG", "key-$id", 100, 100),
        previewIdentity = MediaIdentity("$folder/$id.JPG", "key-$id", 100, 100),
        metadata = ObservedMetadata(
            capturedAt = capturedAt,
            camera = camera,
            lens = lens,
            latitude = if (gps) -23.5 else null,
            longitude = if (gps) -46.6 else null,
            width = 6000,
            height = 4000,
            status = MetadataStatus.AVAILABLE,
        ),
        editorial = editorial,
    )

    private fun scanPhoto(root: Path): Photo = LibraryScanner(object : MediaObservationAdapter {
        override fun observe(bytes: ByteArray, kind: MediaKind) = ObservedMetadata(width = 40, height = 30, status = MetadataStatus.AVAILABLE)
    }).scan(root, AtomicBoolean()) { }.photos.single()

    private fun allKeys(element: JsonElement): Set<String> = buildSet {
        when (element) {
            is JsonObject -> element.forEach { (key, value) -> add(key); addAll(allKeys(value)) }
            is JsonArray -> element.forEach { addAll(allKeys(it)) }
            else -> Unit
        }
    }

    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256").digest(path.readBytes()).joinToString("") { "%02x".format(it) }
}

private class MemoryProjection(initial: List<Photo>) : SnapshotProjection {
    private val exclusive = Any()
    @Volatile private var photos = initial
    @Volatile private var generation = "fixture-generation"
    @Volatile private var degraded = false
    @Volatile var failUpdates = false
    @Volatile var degradedAtPublish = false

    override fun loadCacheOnly(): List<Photo> = photos
    override fun generation(): String = generation
    override fun publish(photos: List<Photo>) {
        degradedAtPublish = degraded
        this.photos = photos
        generation = "fixture-generation-${System.nanoTime()}"
    }
    override fun updateEditorial(photoId: String, editorial: EditorialState) {
        if (failUpdates) error("simulated projection failure")
        photos = photos.map { if (it.id == photoId) it.copy(editorial = editorial) else it }
        generation = "fixture-generation-${System.nanoTime()}"
    }
    override fun projectionDegraded(): Boolean = degraded
    override fun markProjectionDegraded() { degraded = true }
    override fun clearProjectionDegraded() { degraded = false }
    override fun <T> withExclusiveProjection(block: () -> T): T = synchronized(exclusive, block)
    override fun <T> withSharedProjection(block: () -> T): T = synchronized(exclusive, block)
}

private class MemoryEditorialAuthority(photos: List<Photo>) : EditorialAuthority {
    private val values = ConcurrentHashMap(photos.associate { it.id to it.editorial })
    private val locks = ConcurrentHashMap<String, Any>()
    val mutations = AtomicInteger()
    @Volatile var failReads = false

    override fun read(photo: Photo): EditorialState = readSnapshot(photo).state
    override fun readSnapshot(photo: Photo): AuthorityEditorialSnapshot {
        if (failReads) error("simulated XMP read failure")
        val state = values[photo.id] ?: photo.editorial
        return AuthorityEditorialSnapshot(state, editorialRevision(state))
    }
    override fun mutateCurrent(
        photo: Photo,
        expectedRevision: String?,
        canonicalizeFlag: Boolean,
        transform: (EditorialState) -> EditorialState,
    ): CurrentEditorialMutation = synchronized(locks.computeIfAbsent(photo.id) { Any() }) {
        val current = values[photo.id] ?: photo.editorial
        val currentSnapshot = AuthorityEditorialSnapshot(current, editorialRevision(current))
        if (expectedRevision != null && currentSnapshot.revision != expectedRevision) {
            CurrentEditorialMutation.Conflict(currentSnapshot)
        } else {
            val desired = transform(current)
            mutations.incrementAndGet()
            values[photo.id] = desired
            CurrentEditorialMutation.Persisted(AuthorityEditorialSnapshot(desired, editorialRevision(desired)))
        }
    }
    fun current(photoId: String): EditorialState = checkNotNull(values[photoId])
    override fun close() = Unit
}

private object NoPreviewAuthority : PreviewAuthority {
    override fun preview(photo: Photo, maximumDimension: Int): PreviewPayload = error("preview not configured")
    override fun close() = Unit
}

private suspend fun io.ktor.server.testing.ApplicationTestBuilder.mutationRequest(
    url: String,
    body: String,
    origin: String = "http://localhost",
    csrf: String? = "csrf-token",
) = client.post(url) {
    header(HttpHeaders.Host, "localhost")
    header(HttpHeaders.Origin, origin)
    csrf?.let { header("X-PhotoTool-CSRF", it) }
    contentType(ContentType.Application.Json)
    setBody(body)
}
