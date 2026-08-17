package br.com.lincon.phototool

import br.com.lincon.phototool.domain.*
import br.com.lincon.phototool.state.*
import br.com.lincon.phototool.ui.constrainDetailPan
import br.com.lincon.phototool.ui.focalDetailPan
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.*

class DomainTest {
    @Test fun pairsOnlyExactCaseInsensitiveStemsInSameDirectory() {
        val result = pairCandidates(listOf(
            MediaCandidate("2025.01","IMG_1.CR3",MediaKind.RAW), MediaCandidate("2025.01","img_1.JPG",MediaKind.JPEG),
            MediaCandidate("2025.01","IMG_1-Edit.JPG",MediaKind.JPEG), MediaCandidate("2025.02","IMG_1.JPG",MediaKind.JPEG),
        ))
        assertEquals(3,result.size); assertNotNull(result.single { it.folder=="2025.01"&&it.stem.equals("IMG_1",true) }.raw)
        assertNull(result.single { it.stem=="IMG_1-Edit" }.raw)
    }
    @Test fun ambiguitiesAreNotWritable() {
        val result=pairCandidates(listOf(MediaCandidate("","A.CR2",MediaKind.RAW),MediaCandidate("","a.DNG",MediaKind.RAW)))
        assertEquals(2,result.size); assertTrue(result.all { !it.writable }); assertTrue(result.all { "RAW" in it.issue!! })
    }
    @Test fun filtersComposeAndRejectedRemainVisible() {
        val photos=listOf(photo("a","2025-02-01T00:00:00","Fuji","23",Flag.REJECT,5,listOf("São Paulo"),true),photo("b",null,"Canon","50",Flag.PICK,2,emptyList(),false))
        assertEquals(listOf("a","b"),filterAndOrder(photos,Query()).map { it.id })
        assertEquals(listOf("a"),filterAndOrder(photos,Query(search="fuji",keyword="são paulo",minimumStars=4,gps=GpsFilter.PRESENT)).map { it.id })
    }
    @Test fun negativeSearchTermsExcludeAcrossVisibleMetadata() {
        val personal = photo("personal",camera="Fuji",keywords=listOf("Personal")).copy(folder="2025/Família")
        val work = photo("work",camera="Fuji",keywords=listOf("Trabalho")).copy(folder="2025/Cardano")
        val city = photo("city",camera="Canon",keywords=listOf("São Paulo", "Street")).copy(folder="2025/Pessoal")
        assertEquals(listOf("work"), filterAndOrder(listOf(personal,work,city), Query(search="fuji -personal")).map { it.id })
        assertEquals(listOf("personal","work"), filterAndOrder(listOf(personal,work,city), Query(search="-canon")).map { it.id }.sorted())
        assertEquals(listOf("personal","work"), filterAndOrder(listOf(personal,work,city), Query(search="-\"São Paulo\"")).map { it.id }.sorted())
        assertEquals(SearchTerms(listOf("fuji"),listOf("personal","são paulo")), parseSearchTerms("Fuji -personal -\"São Paulo\""))
    }
    @Test fun keywordNormalizationIsNfcBoundedAndExact() {
        assertEquals("São Paulo|Centro",normalizeKeyword("  Sa\u0303o   Paulo | Centro "))
        assertFails { normalizeKeyword("bad,keyword") }; assertFails { normalizeKeyword("a||b") }
    }
    @Test fun unicodeCaseFoldMatchesSharpSFinalSigmaAndCommonLigatures() {
        assertEquals(keywordCasefold("Straße"), keywordCasefold("STRASSE"))
        assertEquals(keywordCasefold("ẞ"), keywordCasefold("ss"))
        assertEquals(keywordCasefold("ΟΣ"), keywordCasefold("οσ"))
        assertEquals(keywordCasefold("ος"), keywordCasefold("οσ"))
        assertEquals(keywordCasefold("oﬃce ﬆreet"), keywordCasefold("OFFICE STREET"))
        val paired = pairCandidates(listOf(
            MediaCandidate("", "Straße.RAF", MediaKind.RAW),
            MediaCandidate("", "STRASSE.JPG", MediaKind.JPEG),
        ))
        assertEquals(1, paired.size)
        assertEquals("Straße.RAF", paired.single().raw)
        assertEquals("STRASSE.JPG", paired.single().jpeg)
    }
    @Test fun invalidFilterInputReturnsNoMatchesWithoutThrowing() {
        val photos=listOf(photo("a"))
        assertTrue(filterAndOrder(photos,Query(keyword="bad,keyword")).isEmpty())
        assertTrue(filterAndOrder(photos,Query(keyword="a||b")).isEmpty())
        assertTrue(filterAndOrder(photos,Query(keyword="x".repeat(161))).isEmpty())
    }
    @Test fun equalDateOrderUsesFolderStemThenId() {
        val date="2025-01-01T00:00:00Z"
        val photos=listOf(photo("z",date).copy(folder="b",stem="a"),photo("y",date).copy(folder="a",stem="z"),photo("x",date).copy(folder="a",stem="a"))
        assertEquals(listOf("x","y","z"),filterAndOrder(photos,Query()).map { it.id })
    }
    @Test fun reducerPreservesQueryContextAndSelection() {
        var state=AppState(photos=listOf(photo("a"),photo("b")),query=Query(search="b"))
        state=reduce(state,Action.Select("b")); state=reduce(state,Action.OpenDetail)
        assertEquals(Screen.DETAIL,state.screen); assertEquals("b",state.query.search); assertEquals("b",state.selectedId)
        state=reduce(state,Action.PublishSnapshot(listOf(photo("a")))); assertNull(state.selectedId); assertEquals(Screen.GALLERY,state.screen)
    }
    @Test fun openingAndCancellingFiltersPreservesGallerySelection() {
        var state=reduce(AppState(photos=listOf(photo("a"))),Action.Select("a"))
        state=reduce(state,Action.ToggleFilters); assertTrue(state.filtersOpen); assertEquals("a",state.selectedId)
        state=reduce(state,Action.ToggleFilters); assertFalse(state.filtersOpen); assertEquals("a",state.selectedId)
    }
    @Test fun freshStateKeepsFiltersClosedAndQueryPrunesHiddenSelection() {
        assertFalse(AppState().filtersOpen)
        val photos=listOf(photo("a",camera="Fuji"),photo("b",camera="Canon"))
        var state=reduce(AppState(photos=photos),Action.Select("a"))
        state=reduce(state,Action.SetQuery(Query(search="Canon")))
        assertNull(state.selectedId); assertTrue(state.selectionIds.isEmpty())
        state=reduce(state,Action.OpenDetail); assertEquals(Screen.GALLERY,state.screen)
    }
    @Test fun folderAndRecentlyAddedQueriesAreRealAndDeterministic() {
        val old=photo("old").copy(folder="Trips/Italy",sourceIdentity=MediaIdentity("old.jpg","1",1,10))
        val recent=photo("recent").copy(folder="Trips/Brazil",sourceIdentity=MediaIdentity("recent.jpg","2",1,20))
        assertEquals(listOf("old","recent"),filterAndOrder(listOf(recent,old),Query(folder="Trips")).map { it.id }.sorted())
        assertEquals(listOf("recent","old"),filterAndOrder(listOf(old,recent),Query(sort=PhotoSort.RECENTLY_ADDED)).map { it.id })
    }
    @Test fun calendarMonthBoundsHandleThirtyDayMonthsAndLeapYears() {
        assertEquals("2025-06-01" to "2025-06-30",monthDateBounds(2025,6))
        assertEquals("2024-02-01" to "2024-02-29",monthDateBounds(2024,2))
        assertEquals("2100-02-01" to "2100-02-28",monthDateBounds(2100,2))
    }
    @Test fun gregorianDatesAreCanonicalValidatedAndProtectReducer() {
        assertEquals("2024-02-29", parseGregorianDate("2024-02-29").toString())
        assertNull(parseGregorianDate("2023-02-29")); assertNull(parseGregorianDate("2025-2-01")); assertNull(captureGregorianDate("2025-13-01T00:00:00Z"))
        assertEquals("2025-01-01",captureGregorianDate("2025-01-01T00:01:02.123456789+14:00").toString())
        assertEquals("2024-12-31",captureGregorianDate("2024-12-31T23:59:59-12:00").toString())
        assertEquals("2025-01-01",captureGregorianDate("2025-01-01T00:01:02").toString())
        listOf("2025-01-01garbage","2025-01-01T24:00:00Z","2025-01-01T00:60:00Z","2025-01-01T00:00:60Z","2025-01-01T00:00:00+14:01","2025-01-01T00:00:00.1234567890Z").forEach { assertNull(captureGregorianDate(it),it) }
        assertNotNull(queryDateError("2025-03-01", "2025-02-28"))
        val original=AppState(photos=listOf(photo("a","2025-02-01T00:00:00Z")),query=Query(search="kept"))
        assertEquals(original,reduce(original,Action.SetQuery(Query(fromDate="2025-02-30"))))
        assertEquals(original,reduce(original,Action.BrowseDate("2025-02-30","2025-02-30")))
    }
    @Test fun invalidCaptureDatesNeverEnterCalendarFiltering() {
        val valid=photo("valid","2025-02-01T00:00:00Z")
        val invalid=photo("invalid","2025-02-30T00:00:00Z")
        assertEquals(listOf("valid"),filterAndOrder(listOf(valid,invalid),Query(fromDate="2025-02-01",toDate="2025-02-28")).map { it.id })
    }
    @Test fun selectionToggleExtendSelectVisibleAndSnapshotPruningPreservePrimary() {
        val photos=listOf("a","b","c","d","e").map(::photo)
        var state=reduce(AppState(photos=photos),Action.Select("b"))
        state=reduce(state,Action.Select("d",SelectionMode.EXTEND))
        assertTrue(state.selectionModeActive); assertEquals("d",state.selectedId); assertEquals(setOf("b","c","d"),state.selectionIds)
        state=reduce(state,Action.Select("c",SelectionMode.EXTEND))
        assertEquals("c",state.selectedId); assertEquals(setOf("b","c"),state.selectionIds)
        state=reduce(state,Action.Select("e",SelectionMode.TOGGLE))
        assertEquals("c",state.selectedId); assertEquals(setOf("b","c","e"),state.selectionIds)
        state=reduce(state,Action.SelectVisible); assertEquals(5,state.selectionIds.size)
        state=reduce(state,Action.PublishSnapshot(photos.take(2))); assertEquals(setOf("a","b"),state.selectionIds); assertEquals("a",state.selectedId)
        state=reduce(state,Action.ClearSelection); assertTrue(state.selectionModeActive); assertTrue(state.selectionIds.isEmpty()); assertNull(state.selectedId)
        state=reduce(state,Action.ToggleSelectionMode); assertFalse(state.selectionModeActive)
        state=reduce(state,Action.ToggleSelectionMode); assertTrue(state.selectionModeActive)
    }
    @Test fun picksSectionIsStructuralAndCannotBeClearedByFilterActions() {
        val pick=photo("pick",flag=Flag.PICK)
        val reject=photo("reject",flag=Flag.REJECT)
        var state=reduce(AppState(photos=listOf(pick,reject)),Action.Navigate(LibrarySection.PICKS))
        assertNull(state.query.flag); assertEquals(listOf("pick"),state.visiblePhotos.map { it.id })
        state=reduce(state,Action.SetQuery(Query(flag=Flag.REJECT)))
        assertNull(state.query.flag); assertEquals(listOf("pick"),state.visiblePhotos.map { it.id })
        state=reduce(state,Action.SetQuery(Query()))
        assertEquals(listOf("pick"),state.visiblePhotos.map { it.id })
    }
    @Test fun everySelectionPathHonorsLimitAndExtendShrinks() {
        val photos=(0 until MAX_BATCH_PHOTOS+20).map { photo(it.toString().padStart(4,'0')) }
        var state=reduce(AppState(photos=photos),Action.SelectVisible)
        assertEquals(MAX_BATCH_PHOTOS,state.selectionIds.size)
        val rejected=photos.last().id
        state=reduce(state,Action.Select(rejected,SelectionMode.TOGGLE))
        assertEquals(MAX_BATCH_PHOTOS,state.selectionIds.size); assertFalse(rejected in state.selectionIds)
        state=reduce(AppState(photos=photos),Action.Select(photos.first().id))
        state=reduce(state,Action.Select(photos.last().id,SelectionMode.EXTEND))
        assertEquals(MAX_BATCH_PHOTOS,state.selectionIds.size); assertEquals(photos[MAX_BATCH_PHOTOS-1].id,state.selectedId)
        state=reduce(state,Action.Select(photos[2].id,SelectionMode.EXTEND))
        assertEquals(3,state.selectionIds.size); assertEquals(photos[2].id,state.selectedId)
    }
    @Test fun cameraFilterUsesTheSameDerivedLabelShownToTheUser() {
        val fallback = photo("fallback").copy(metadata = ObservedMetadata(cameraMake = "FUJIFILM", cameraModel = "X-Pro2"))
        assertEquals("FUJIFILM X-Pro2", fallback.metadata.cameraDisplay)
        assertEquals(listOf("fallback"), filterAndOrder(listOf(fallback), Query(camera = "FUJIFILM X-Pro2")).map { it.id })
        assertTrue(filterAndOrder(listOf(fallback), Query(camera = "X-Pro2")).isEmpty())
    }
    @Test fun focalZoomKeepsCursorAnchorAfterExistingPanAndClampsBothAxes() {
        val viewport=IntSize(1000,800)
        assertEquals(Offset(0f,0f),focalDetailPan(Offset(100f,0f),2f,4f,Offset(700f,400f),viewport))
        assertEquals(Offset(500f,100f),constrainDetailPan(Offset(999f,999f),2f,viewport,IntSize(2000,1000)))
        assertEquals(Offset(0f,400f),constrainDetailPan(Offset(999f,999f),2f,viewport,IntSize(1000,2000)))
        assertEquals(Offset.Zero,constrainDetailPan(Offset(20f,20f),1f,viewport,IntSize(2000,1000)))
    }
    @Test fun mediaFormatLabelsDistinguishRawPairsAndJpegAuthority() {
        assertEquals("JPEG", photo("jpeg").mediaFormatLabel)
        assertEquals("RAF", photo("raf").copy(rawPath="2025/A.RAF",jpegPath=null).mediaFormatLabel)
        assertEquals("CR3 + JPEG", photo("pair").copy(rawPath="2025/A.CR3",jpegPath="2025/A.JPG").mediaFormatLabel)
    }
    @Test fun proofNumberComesFromTheActualFilenameStem() {
        assertEquals("0568", photo("first").copy(stem="_MG_0568").filenameNumber)
        assertEquals("7992", photo("second").copy(stem="DSCF7992-edit").filenameNumber)
        assertNull(photo("scan").copy(stem="scan-final").filenameNumber)
    }
    @Test fun persistedStatusClearsOnlyForTheSameConfirmedEditorialState() {
        val savedEditorial = EditorialState(flag=Flag.PICK, rating=4)
        val saved = photo("saved").copy(editorial=savedEditorial, writeState=WriteState.PERSISTED)
        val cleared = reduce(AppState(photos=listOf(saved)), Action.ClearPersistedStatus(saved.id, savedEditorial)).photos.single()
        assertEquals(WriteState.IDLE, cleared.writeState)
        val saving = saved.copy(writeState=WriteState.SAVING, editorial=savedEditorial.copy(rating=5))
        val preserved = reduce(AppState(photos=listOf(saving)), Action.ClearPersistedStatus(saving.id, savedEditorial)).photos.single()
        assertEquals(WriteState.SAVING, preserved.writeState)
        assertEquals(5, preserved.editorial.rating)
    }
    @Test fun batchEditsAreBoundedNormalizedAndFieldSpecific() {
        val base=EditorialState(Flag.PICK,3,ColorLabel.RED,listOf("Travel"))
        assertEquals(5,BatchEdit.SetRating(8).applyTo(base).rating)
        assertEquals(listOf("Travel","São Paulo"),BatchEdit.AddKeyword(" Sa\u0303o  Paulo ").applyTo(base).keywords)
        assertTrue(BatchEdit.ClearKeywords.applyTo(base).keywords.isEmpty())
        assertEquals(Flag.PICK,BatchEdit.SetLabel(ColorLabel.GREEN).applyTo(base).flag)
        assertFails { BatchEdit.AddKeyword("Lugar|Centro").applyTo(base) }
        assertEquals("Lugar|Centro", normalizeKeyword("Lugar|Centro")); assertFails { normalizeFlatKeyword("Lugar|Centro") }
    }
    private fun photo(id:String,date:String?=null,camera:String?=null,lens:String?=null,flag:Flag=Flag.UNFLAGGED,rating:Int=0,keywords:List<String> = emptyList(),gps:Boolean=false)=Photo(id,"",id,"$id.xmp",jpegPath="$id.jpg",metadata=ObservedMetadata(capturedAt=date,camera=camera,lens=lens,latitude=if(gps)1.0 else null,longitude=if(gps)2.0 else null),editorial=EditorialState(flag,rating,null,keywords))
}
