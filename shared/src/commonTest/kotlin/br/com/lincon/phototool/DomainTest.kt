package br.com.lincon.phototool

import br.com.lincon.phototool.domain.*
import br.com.lincon.phototool.state.*
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
    @Test fun keywordNormalizationIsNfcBoundedAndExact() {
        assertEquals("São Paulo|Centro",normalizeKeyword("  Sa\u0303o   Paulo | Centro "))
        assertFails { normalizeKeyword("bad,keyword") }; assertFails { normalizeKeyword("a||b") }
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
        var state=AppState(photos=listOf(photo("a"),photo("b")),query=Query(search="fuji"))
        state=reduce(state,Action.Select("b")); state=reduce(state,Action.OpenDetail)
        assertEquals(Screen.DETAIL,state.screen); assertEquals("fuji",state.query.search); assertEquals("b",state.selectedId)
        state=reduce(state,Action.PublishSnapshot(listOf(photo("a")))); assertNull(state.selectedId)
    }
    private fun photo(id:String,date:String?=null,camera:String?=null,lens:String?=null,flag:Flag=Flag.UNFLAGGED,rating:Int=0,keywords:List<String> = emptyList(),gps:Boolean=false)=Photo(id,"",id,"$id.xmp",jpegPath="$id.jpg",metadata=ObservedMetadata(capturedAt=date,camera=camera,lens=lens,latitude=if(gps)1.0 else null,longitude=if(gps)2.0 else null),editorial=EditorialState(flag,rating,null,keywords))
}
