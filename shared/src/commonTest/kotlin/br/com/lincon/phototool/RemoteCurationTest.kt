package br.com.lincon.phototool

import br.com.lincon.phototool.api.*
import br.com.lincon.phototool.domain.*
import br.com.lincon.phototool.remote.*
import br.com.lincon.phototool.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteCurationTest {
    @Test fun constructionIsInertAndReadOnlyIsNotElevatedByServerOrActions() = runTest {
        val api = FakeReadApi()
        val store = RemoteCurationStore(api, backgroundScope)
        assertEquals(0, api.calls)
        store.start(); advanceUntilIdle(); runCurrent()
        store.dispatch(Action.Configure("unsafe", "unsafe", true, true))
        store.dispatch(Action.EditorialChanged("one", EditorialState(rating = 5), WriteState.PERSISTED))
        assertEquals(2, api.calls)
        assertEquals(2, store.state.value.photos.size)
        assertFalse(store.state.value.writeEnabled)
        assertFalse(store.state.value.writeAuthorized)
        assertTrue(store.state.value.photos.all { !it.writable && it.authorityPath.isEmpty() && it.sourceIdentity == null })
    }

    @Test fun serverPageIsNotFilteredOrReorderedFromIncompleteSummaries() = runTest {
        val api = FakeReadApi()
        val store = RemoteCurationStore(api, this)
        store.dispatch(Action.SetQuery(Query(search = "-personal -\"São Paulo\"", gps = GpsFilter.PRESENT, sort = PhotoSort.RECENTLY_ADDED)))
        advanceUntilIdle()
        assertEquals(GpsFilter.PRESENT, api.lastQuery.gps)
        assertEquals(listOf("one", "two"), store.state.value.visiblePhotos.map { it.id })
        assertFalse(store.state.value.photos.first().metadata.hasGps, "Summary deliberately lacks GPS; server is authority")
        assertEquals(48, store.state.value.visiblePhotoCount)
        assertEquals("-personal -\"São Paulo\"", api.lastQuery.search)
    }

    @Test fun mapsEveryFilterToTheExistingHttpContractWithoutRewritingSearch() {
        val query = Query("-personal -\"São Paulo\"", "Rua", "2026-01-01", "2026-12-31", Flag.PICK,
            "FUJIFILM X-Pro2", "XF23mmF2", 3, GpsFilter.MISSING, "opaque-folder", PhotoSort.RECENTLY_ADDED)
        assertEquals(mapOf("q" to query.search, "keyword" to "Rua", "fromDate" to "2026-01-01", "toDate" to "2026-12-31",
            "flag" to "PICK", "camera" to "FUJIFILM X-Pro2", "lens" to "XF23mmF2", "minimumStars" to "3", "gps" to "MISSING",
            "folder" to "opaque-folder", "sort" to "RECENTLY_ADDED", "page" to "2", "pageSize" to "24"), query.httpParameters(2))
    }

    @Test fun lateResponseCannotReplaceANewerQuery() = runTest {
        val old = CompletableDeferred<Unit>()
        val api = object : FakeReadApi() {
            override suspend fun page(query: Query, page: Int): PhotoPageDto {
                if (query.search == "old") withContext(NonCancellable) { old.await() }
                return super.page(query, page).copy(items = listOf(summary(query.search)))
            }
        }
        val store = RemoteCurationStore(api, this)
        store.dispatch(Action.SetQuery(Query(search = "old")))
        advanceTimeBy(201); runCurrent()
        store.dispatch(Action.SetQuery(Query(search = "new")))
        advanceTimeBy(201); runCurrent()
        old.complete(Unit); advanceUntilIdle()
        assertEquals("new", store.state.value.photos.single().id)
    }

    @Test fun detailNavigationUsesQueryAwareServerNeighborsBeyondCurrentPage() = runTest {
        val api = FakeReadApi()
        val store = RemoteCurationStore(api, this)
        store.start(); advanceUntilIdle()
        store.dispatch(Action.Select("one")); store.dispatch(Action.OpenDetail); advanceUntilIdle()
        assertTrue(store.presentDetail(store.state.value.remote!!.pendingDetail!!.ticket, true)); advanceUntilIdle()
        assertEquals("one", store.state.value.selected?.id)
        assertEquals(640, store.state.value.selected?.metadata?.width)
        store.navigate(1); advanceUntilIdle()
        assertTrue(store.presentDetail(store.state.value.remote!!.pendingDetail!!.ticket, true)); advanceUntilIdle()
        assertEquals("outside-page", store.state.value.selected?.id)
        assertEquals(listOf("one", "two"), store.state.value.visiblePhotos.map { it.id })
        store.dispatch(Action.CloseDetail)
        assertEquals(Screen.GALLERY, store.state.value.screen)
    }

    @Test fun closingDetailInvalidatesItsInFlightResponse() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = object : FakeReadApi() {
            override suspend fun detail(query: Query, id: String): PhotoDetailDto {
                withContext(NonCancellable) { gate.await() }
                return super.detail(query, id)
            }
        }
        val store = RemoteCurationStore(api, this)
        store.start(); advanceUntilIdle()
        store.dispatch(Action.Select("one")); store.dispatch(Action.OpenDetail); runCurrent()
        store.dispatch(Action.CloseDetail); gate.complete(Unit); advanceUntilIdle()
        assertEquals(Screen.GALLERY, store.state.value.screen)
        assertNull(store.state.value.remote!!.detail)
        assertFalse(store.state.value.remote!!.loading)
    }

    @Test fun failedQueriesHaveAnExplicitErrorAndCanBeRetried() = runTest {
        val api = FakeReadApi()
        val store = RemoteCurationStore(api, this)
        store.start(); advanceUntilIdle()
        api.fail = true
        store.dispatch(Action.SetQuery(Query(search = "new"))); advanceUntilIdle()
        assertNotNull(store.state.value.remote!!.error)
        assertTrue(store.state.value.photos.isEmpty(), "Do not show old results under a different query")
        api.fail = false
        store.refresh(); advanceUntilIdle()
        assertNull(store.state.value.remote!!.error)
        assertEquals("DEGRADED", store.state.value.remote!!.projection)
        assertEquals(2, store.state.value.photos.size)
    }

    @Test fun pagingIsBoundedAndResetsWhenTheQueryChanges() = runTest {
        val store = RemoteCurationStore(FakeReadApi(), this)
        store.start(); advanceUntilIdle()
        store.changePage(-1); assertEquals(1, store.state.value.remote!!.page)
        store.changePage(1); advanceUntilIdle(); assertEquals(2, store.state.value.remote!!.page)
        store.changePage(1); assertEquals(2, store.state.value.remote!!.page)
        store.dispatch(Action.SetQuery(Query(minimumStars = 4))); advanceUntilIdle()
        assertEquals(1, store.state.value.remote!!.page)
    }

    @Test fun deepLinksLoadTheRequestedPhotoAfterReadingTheSnapshot() = runTest {
        val store = RemoteCurationStore(FakeReadApi(), this)
        store.start("outside-page"); advanceUntilIdle()
        assertTrue(store.presentDetail(store.state.value.remote!!.pendingDetail!!.ticket, true)); advanceUntilIdle()
        assertEquals(Screen.DETAIL, store.state.value.screen)
        assertEquals("outside-page", store.state.value.selected?.id)
        assertFalse(store.state.value.remote!!.loading)
    }

    @Test fun aShrinkingSnapshotReturnsToAnExistingPage() = runTest {
        val api = object : FakeReadApi() {
            override suspend fun page(query: Query, page: Int) = super.page(query, page).let {
                if (page == 2) it.copy(total = 2, totalPages = 1, items = emptyList()) else it
            }
        }
        val store = RemoteCurationStore(api, this)
        store.start(); advanceUntilIdle()
        store.changePage(1); advanceUntilIdle()
        assertEquals(1, store.state.value.remote!!.page)
        assertEquals(2, store.state.value.photos.size)
    }

    @Test fun mixedSnapshotGenerationsAreNotPublishedAsSuccess() = runTest {
        val api = object : FakeReadApi() {
            override suspend fun page(query: Query, page: Int) = super.page(query, page).copy(snapshotGeneration = "changed")
        }
        val store = RemoteCurationStore(api, this)
        store.start(); advanceUntilIdle()
        assertNotNull(store.state.value.remote!!.error)
        assertTrue(store.state.value.photos.isEmpty())
    }

    @Test fun previewTargetsAreSameOriginVersionedAndBounded() {
        val photo = summary("one").toPhoto()
        assertTrue(boundedPreviewUrl(photo, 99999).contains("max=4096&v="))
        assertTrue(boundedPreviewUrl(photo, 1).contains("max=128&v="))
        listOf("https://example.com/image.jpg", "//example.com/image.jpg", "/api/v1/photos/other/preview?max=900&v=${"a".repeat(24)}",
            "/api/v1/photos/one/preview?max=900&v=${"a".repeat(24)}&redirect=https://example.com", "file:///secret").forEach { url ->
            assertFailsWith<IllegalArgumentException> { boundedPreviewUrl(photo.copy(presentation = photo.presentation!!.copy(previewUrl = url)), 900) }
        }
        assertFailsWith<IllegalArgumentException> { requirePhotoId("../status") }
        assertFailsWith<IllegalArgumentException> { summary("one").copy(displayName = "/private/photo.jpg").toPhoto() }
    }

    @Test fun presentationDoesNotInventRawPathsOrFileIdentities() {
        val photo = summary("one").copy(format = "RAF + JPEG", displayName = "IMG_1234.RAF", aspectRatio = .75).toPhoto()
        assertEquals("RAF + JPEG", photo.mediaFormatLabel)
        assertEquals("IMG_1234.RAF", photo.displayName)
        assertEquals("1234", photo.filenameNumber)
        assertEquals(.75f, photo.aspectRatio)
        assertNull(photo.rawPath); assertNull(photo.jpegPath); assertNull(photo.previewIdentity)
    }

    private open class FakeReadApi : CurationReadApi {
        var calls = 0
        var lastQuery = Query()
        var fail = false
        override suspend fun status(): SnapshotStatusDto {
            calls++
            return SnapshotStatusDto("generation", 48, ProjectionStateDto.DEGRADED, true, SyncStatusDto())
        }
        override suspend fun page(query: Query, page: Int): PhotoPageDto {
            calls++; lastQuery = query
            if (fail) throw Error("Fail to fetch")
            return PhotoPageDto("generation", page, 24, 48, 2, listOf(summary("one"), summary("two")))
        }
        override suspend fun detail(query: Query, id: String): PhotoDetailDto {
            calls++; lastQuery = query
            return PhotoDetailDto(id, "$id.JPG", "JPEG", 1.5,
                ObservedMetadataDto(null, "Camera", null, null, "Lens", 23.0, 2.0, .01, 400, 1.0, 2.0, 640, 480, "AVAILABLE", null),
                EditorialDto(Flag.PICK, 3, null, listOf("Rua"), "a".repeat(64)), true, null,
                "/api/v1/photos/$id/preview?max=1600&v=${"a".repeat(24)}", null, if (id == "one") "outside-page" else null)
        }
    }

    companion object {
        private fun summary(id: String) = PhotoSummaryDto(id, "$id.JPG", "JPEG", 1.5, null, "Camera", "Lens",
            EditorialSummaryDto(Flag.PICK, 3, null, listOf("Rua")), true,
            "/api/v1/photos/$id/preview?max=900&v=${"a".repeat(24)}")
    }
}
