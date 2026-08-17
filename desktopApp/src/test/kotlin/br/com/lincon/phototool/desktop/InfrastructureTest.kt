package br.com.lincon.phototool.desktop

import br.com.lincon.phototool.domain.*
import br.com.lincon.phototool.state.SyncPhase
import br.com.lincon.phototool.state.SyncStatus
import br.com.lincon.phototool.ui.AuxiliaryBatchEdit
import br.com.lincon.phototool.ui.AuxiliaryBatchOutcome
import br.com.lincon.phototool.ui.HdrView
import com.ashampoo.kim.model.TiffOrientation
import java.nio.file.*
import java.nio.channels.FileChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.io.path.*
import kotlin.test.*

class InfrastructureTest {
    @Test fun xmpMappingsNoopUnknownPreservationAndOriginalImmutability() {
        val root=createTempDirectory("phototool-xmp"); val raw=root.resolve("IMG.CR2"); raw.writeBytes(byteArrayOf(1,2,3)); val beforeRaw=raw.readBytes()
        val sidecar=root.resolve("IMG.xmp"); sidecar.writeText("""<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:xmpDM="http://ns.adobe.com/xmp/1.0/DynamicMedia/" xmlns:custom="urn:test" xmpDM:pick="0" custom:keep="yes"><!--keep-comment--></rdf:Description></rdf:RDF></x:xmpmeta>""")
        val photo=Photo("id","","IMG","IMG.xmp",rawPath="IMG.CR2",sourceIdentity=identity(root,raw))
        val store=XmpSidecarStore(root,true); val original=sidecar.readBytes(); val mtime=sidecar.getLastModifiedTime()
        assertEquals(EditorialState(),store.mutate(photo,EditorialState())); assertContentEquals(original,sidecar.readBytes()); assertEquals(mtime,sidecar.getLastModifiedTime())
        val state=store.mutate(photo,EditorialState(Flag.PICK,5,ColorLabel.GREEN,listOf("São Paulo")))
        val written=sidecar.readText(); assertEquals(Flag.PICK,state.flag); assertContains(written,"pick=\"1\""); assertContains(written,"good=\"True\""); assertContains(written,"Rating=\"5\""); assertContains(written,"keep=\"yes\""); assertContains(written,"keep-comment"); assertContentEquals(beforeRaw,raw.readBytes()); assertEquals(1,root.listDirectoryEntries(".IMG.xmp.previous.*.xmp").size)
    }

    @Test fun pickIsAuthoritativeAndUnrelatedEditsPreserveIndependentGoodBytes() {
        val root=createTempDirectory(); val raw=root.resolve("P.CR2"); raw.writeBytes(byteArrayOf(1)); val sidecar=root.resolve("P.xmp")
        sidecar.writeText("""<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:xmpDM="http://ns.adobe.com/xmp/1.0/DynamicMedia/" xmpDM:pick="1" xmpDM:good="False" xmp:Rating="2"/></rdf:RDF></x:xmpmeta>""")
        val photo=Photo("p","","P","P.xmp",rawPath="P.CR2",sourceIdentity=identity(root,raw)); val store=XmpSidecarStore(root,true)
        val contradictory=store.read(photo); assertEquals(Flag.PICK,contradictory.flag); assertEquals(false,contradictory.good)
        val rated=store.mutate(photo,contradictory.copy(rating=3)); assertEquals(Flag.PICK,rated.flag); assertEquals(false,rated.good)
        assertContains(sidecar.readText(),"good=\"False\""); assertContains(sidecar.readText(),"pick=\"1\"")
        val canonical=store.mutate(photo,rated,canonicalizeFlag=true); assertEquals(true,canonical.good); assertContains(sidecar.readText(),"good=\"True\"")
        sidecar.writeText("""<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:xmpDM="http://ns.adobe.com/xmp/1.0/DynamicMedia/" xmpDM:pick="-1" xmp:Rating="2"/></rdf:RDF></x:xmpmeta>""")
        val missingGood=store.read(photo); assertEquals(Flag.REJECT,missingGood.flag); assertNull(missingGood.good)
    }

    @Test fun malformedGoodDoesNotHideEditorialAndIsPreservedByRatingEdit() {
        val root=createTempDirectory(); val raw=root.resolve("G.CR2"); raw.writeBytes(byteArrayOf(1)); val sidecar=root.resolve("G.xmp")
        sidecar.writeText("""<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:xmpDM="http://ns.adobe.com/xmp/1.0/DynamicMedia/" xmpDM:pick="-1" xmpDM:good="legacy"/></rdf:RDF></x:xmpmeta>""")
        val photo=scanPhoto(root); val store=XmpSidecarStore(root,true)
        val observed=store.read(photo); assertEquals(Flag.REJECT,observed.flag); assertNull(observed.good); assertEquals("xmp-good-invalid",observed.goodError)
        val persisted=store.mutate(photo,observed.copy(rating=4)); assertContains(sidecar.readText(),"good=\"legacy\""); assertEquals("xmp-good-invalid",persisted.goodError)
        val cache=PhotoCache(createTempDirectory(),root); cache.publish(listOf(photo.copy(editorial=persisted))); assertEquals("xmp-good-invalid",cache.load().single().editorial.goodError)
    }

    @Test fun xmpReadWaitsAcrossCanonicalDisplacementAndNeverObservesTransientAbsence() {
        val root=createTempDirectory(); val raw=root.resolve("R.CR2"); raw.writeBytes(byteArrayOf(1)); root.resolve("R.xmp").writeText(baseXmp())
        val photo=scanPhoto(root)
        val displaced=CountDownLatch(1); val release=CountDownLatch(1)
        val store=XmpSidecarStore(root,true) { displaced.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)) }
        val readerStore=XmpSidecarStore(root,false)
        val executor=Executors.newFixedThreadPool(3)
        val mutation=executor.submit<EditorialState> { store.mutate(photo,EditorialState(rating=5)) }
        assertTrue(displaced.await(5,TimeUnit.SECONDS))
        val reading=executor.submit<EditorialState> { readerStore.read(photo) }
        val developing=executor.submit<DevelopSettings> { readerStore.readDevelop(photo) }
        Thread.sleep(150); assertFalse(reading.isDone,"read must share the mutation lock while canonical XMP is displaced"); assertFalse(developing.isDone,"readDevelop must share the mutation lock")
        release.countDown(); assertEquals(5,mutation.get(5,TimeUnit.SECONDS).rating); assertEquals(5,reading.get(5,TimeUnit.SECONDS).rating); assertFalse(developing.get(5,TimeUnit.SECONDS).hdrEnabled)
        val cache=PhotoCache(createTempDirectory(),root); cache.publish(listOf(photo.copy(editorial=reading.get(),writable=true)))
        assertTrue(cache.load().single().writable)
        store.close(); readerStore.close(); executor.shutdown(); assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS))
    }

    @Test fun malformedFailsClosed() {
        val root=createTempDirectory(); root.resolve("A.RAF").writeBytes(byteArrayOf(9)); val xmp=root.resolve("A.xmp"); xmp.writeText("<bad")
        val photo=Photo("a","","A","A.xmp",rawPath="A.RAF",sourceIdentity=identity(root,root.resolve("A.RAF"))); val before=xmp.readBytes()
        assertFailsWith<XmpException>{ XmpSidecarStore(root,true).mutate(photo,EditorialState(rating=3)) }
        assertContentEquals(before,xmp.readBytes())
    }

    @Test fun scannerIgnoresHiddenAndSymlinkAndMarksAmbiguity() {
        val root=createTempDirectory(); root.resolve("A.CR2").writeBytes(byteArrayOf(1)); root.resolve("a.DNG").writeBytes(byteArrayOf(2)); root.resolve(".hidden").createDirectory().resolve("B.JPG").writeBytes(byteArrayOf(3))
        val result=LibraryScanner(object:MediaObservationAdapter{override fun observe(bytes: ByteArray, kind: MediaKind)=ObservedMetadata(width=3,height=2)}).scan(root,AtomicBoolean()){ _:SyncStatus-> }
        assertEquals(2,result.photos.size); assertTrue(result.photos.all { !it.writable })
    }

    @Test fun cachePublishesCompleteSnapshotAtomically() {
        val root=createTempDirectory(); val cacheDir=createTempDirectory(); val cache=PhotoCache(cacheDir,root)
        assertTrue(cache.load().isEmpty()); root.resolve("A.JPG").writeBytes(byteArrayOf(1)); val photo=scanPhoto(root).copy(editorial=EditorialState(rating=4)); cache.publish(listOf(photo))
        assertEquals(4,cache.load().single().editorial.rating); assertTrue(cacheDir.resolve("phototool.sqlite3").exists())
    }

    @Test fun cacheRejectsSemanticallyInvalidCaptureDate() {
        val root=createTempDirectory(); root.resolve("A.JPG").writeBytes(byteArrayOf(1)); val cache=PhotoCache(createTempDirectory(),root)
        val invalid=scanPhoto(root).copy(metadata=ObservedMetadata(capturedAt="2025-02-30T10:00:00Z"))
        assertFails { cache.publish(listOf(invalid)) }; assertTrue(cache.load().isEmpty())
    }

    @Test fun scannerAndCacheKeepEmptyAndSparseOverOneGibPhotosAsMetadataErrors() {
        val root=createTempDirectory(); root.resolve("EMPTY.JPG").writeBytes(byteArrayOf())
        FileChannel.open(root.resolve("BIG.DNG"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use { channel ->
            channel.position(1024L*1024*1024); channel.write(java.nio.ByteBuffer.wrap(byteArrayOf(1)))
        }
        val result=LibraryScanner().scan(root,AtomicBoolean()){ }
        assertEquals(2,result.photos.size); assertEquals(2,result.errors)
        assertTrue(result.photos.all { it.metadata.status == MetadataStatus.ERROR && it.metadata.errorCode == "metadata-source-size" })
        val cache=PhotoCache(createTempDirectory(),root); cache.publish(result.photos)
        val loaded=cache.load(); assertEquals(2,loaded.size); assertEquals(setOf(0L,1024L*1024*1024+1),loaded.map { it.sourceIdentity!!.size }.toSet())
    }

    @Test fun exifCivilTimestampPreservesOffsetAndNeverInventsOne() {
        assertEquals("2025-01-01T00:05:06.123+14:00",canonicalExifCapturedAt("2025:01:01 00:05:06","123","+14:00"))
        assertEquals("2024-12-31T23:55:06-12:00",canonicalExifCapturedAt("2024:12:31 23:55:06",null,"-12:00"))
        assertEquals("2025-01-01T00:05:06",canonicalExifCapturedAt("2025:01:01 00:05:06",null,null))
        assertNull(canonicalExifCapturedAt("2025:02:30 00:05:06",null,"+14:00"))
        assertNull(canonicalExifCapturedAt("2025:01:01 00:05:06","bad","+15:00"))
    }

    @Test fun scannerPublishesAuthoritativePhotoTotalAfterPairing() {
        val root=createTempDirectory(); root.resolve("A.JPG").writeBytes(byteArrayOf(1)); root.resolve("B.JPG").writeBytes(byteArrayOf(2)); val statuses=mutableListOf<SyncStatus>()
        LibraryScanner(object:MediaObservationAdapter{override fun observe(bytes: ByteArray, kind: MediaKind)=ObservedMetadata(width=3,height=2)}).scan(root,AtomicBoolean()){ statuses += it }
        val metadata=statuses.filter { it.phase == br.com.lincon.phototool.state.SyncPhase.METADATA }
        assertTrue(metadata.isNotEmpty()); assertTrue(metadata.all { it.totalPhotos == 2 }); assertEquals(2,metadata.last().photos)
    }

    @Test fun completedEditIsReplayedWhenItRacesWithStaleSnapshotPublication() {
        val root=createTempDirectory(); root.resolve("A.JPG").writeBytes(byteArrayOf(1)); val cache=PhotoCache(createTempDirectory(),root)
        val observed=scanPhoto(root).copy(editorial=EditorialState(rating=0)); val ledger=EditorialRevisionLedger(); ledger.seedMissing(listOf(observed))
        val publicationPrepared=CountDownLatch(1); val releasePublication=CountDownLatch(1); val executor=Executors.newSingleThreadExecutor()
        val future=executor.submit<ReconciledPublication> {
            publishReconciledSnapshot(cache,listOf(observed),ledger) {
                publicationPrepared.countDown(); assertTrue(releasePublication.await(5,TimeUnit.SECONDS))
            }
        }
        assertTrue(publicationPrepared.await(5,TimeUnit.SECONDS))
        val confirmed=observed.editorial.copy(rating=5); ledger.record(observed.id,confirmed)
        releasePublication.countDown(); val publication=future.get(5,TimeUnit.SECONDS)
        executor.shutdown(); assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS))
        assertEquals(5,publication.photos.single().editorial.rating)
        assertEquals(5,cache.load().single().editorial.rating)
    }

    @Test fun revisionLedgerRefreshesObservedBaselineButNeverOverwritesConfirmedEdit() {
        val photo=Photo("id","","A","A.xmp",jpegPath="A.JPG",editorial=EditorialState(rating=0)); val ledger=EditorialRevisionLedger()
        ledger.seedMissing(listOf(photo)); ledger.seedMissing(listOf(photo.copy(editorial=EditorialState(rating=3))))
        assertEquals(3,ledger.overlay(listOf(photo)).single().editorial.rating)
        ledger.record(photo.id,EditorialState(rating=5)); ledger.seedMissing(listOf(photo.copy(editorial=EditorialState(rating=1))))
        assertEquals(5,ledger.overlay(listOf(photo)).single().editorial.rating)
    }

    @Test fun committedSnapshotSummaryFailureIsOnlyAWarningAndLastSummaryMapsWithoutScanning() {
        assertNotNull(summaryWarning { error("summary unavailable") })
        assertNull(summaryWarning { })
        val root=createTempDirectory(); root.resolve("A.JPG").writeBytes(byteArrayOf(1)); val cache=PhotoCache(createTempDirectory(),root); val snapshot=listOf(scanPhoto(root)); cache.publish(snapshot)
        val generation=cache.snapshotGeneration()!!; val fingerprint=snapshotFingerprint(snapshot)
        val failed=syncStatusFromSummary(SyncSummary("failed",1,2,"cache-failed",snapshotGeneration=generation,snapshotFingerprint=fingerprint),snapshot,generation)
        assertEquals(br.com.lincon.phototool.state.SyncPhase.FAILED,failed.phase); assertEquals(1,failed.photos)
        val stale=syncStatusFromSummary(SyncSummary("success",1,0,"",added=99,snapshotGeneration="00000000-0000-0000-0000-000000000000",snapshotFingerprint=fingerprint),snapshot,generation)
        assertEquals(br.com.lincon.phototool.state.SyncPhase.COMPLETE,stale.phase); assertNull(stale.added)
    }

    @Test fun snapshotDeltaCountsOneForOneReplacementAndConfirmedUpdates() {
        val a=Photo("a","","A","A.xmp",jpegPath="A.JPG",editorial=EditorialState(rating=1))
        val b=Photo("b","","B","B.xmp",jpegPath="B.JPG")
        val c=Photo("c","","C","C.xmp",jpegPath="C.JPG")
        assertEquals(SnapshotDelta(1,1,1),snapshotDelta(listOf(a,b),listOf(a.copy(editorial=EditorialState(rating=2)),c)))
    }

    @Test fun failedSummarySaveCannotRestoreMetricsFromPreviousCacheGeneration() {
        val root=createTempDirectory(); root.resolve("A.JPG").writeBytes(byteArrayOf(1)); val cacheDir=createTempDirectory(); val cache=PhotoCache(cacheDir,root); val store=SyncSummaryStore(cacheDir)
        val first=listOf(scanPhoto(root)); cache.publish(first)
        val oldSummary=SyncSummary("success",1,0,"",added=7,snapshotGeneration=cache.snapshotGeneration()!!,snapshotFingerprint=snapshotFingerprint(first)); store.save(oldSummary)
        val second=first.map { it.copy(editorial=EditorialState(rating=4)) }; cache.publish(second)
        assertNotNull(summaryWarning { error("simulated summary publication failure") })
        val restored=syncStatusFromSummary(store.load(),cache.load(),cache.snapshotGeneration())
        assertEquals(br.com.lincon.phototool.state.SyncPhase.COMPLETE,restored.phase); assertNull(restored.added); assertContains(restored.message,"não corresponde")
    }

    @Test fun fujiFp2EditableFp3ReadOnlyAndOpaqueBytesPreserved() {
        val fixture=Paths.get("..","fixtures","xpro2-editable.FP2").readBytes(); val document=FujiProfileDocument(fixture,"FP2"); assertTrue(document.recipe().editable)
        val updated=document.update(mapOf("ExposureBias" to "P1P00")); assertContains(updated.toString(Charsets.UTF_8),"<ExposureBias>P1P00</ExposureBias>"); assertContains(updated.toString(Charsets.UTF_8),"<FixtureOpaque>preserve-me</FixtureOpaque>")
        val fp3=Paths.get("..","fixtures","xpro2-rendered.FP3").readBytes(); assertFalse(FujiProfileDocument(fp3,"FP3").recipe().editable); assertFails { FujiProfileDocument(fp3,"FP3").update(mapOf("Color" to "1")) }
    }

    private fun scanPhoto(root:Path):Photo = LibraryScanner(object:MediaObservationAdapter { override fun observe(bytes: ByteArray, kind: MediaKind)=ObservedMetadata(status=MetadataStatus.MISSING) }).scan(root,AtomicBoolean()){ }.photos.single()

    private fun identity(root:Path,path:Path):MediaIdentity {
        val attrs=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        val key=attrs.fileKey()?.toString() ?: Files.getAttribute(path,"unix:ino",LinkOption.NOFOLLOW_LINKS).toString()
        return MediaIdentity(root.relativize(path).toString(),key,attrs.size(),attrs.lastModifiedTime().toMillis())
    }

    @Test fun fujiStoreRequiresExistingFp2PublishesWithReadbackAndKeepsFp3ReadOnly() {
        val root=createTempDirectory(); val raf=root.resolve("IMG_1234.RAF"); raf.writeBytes(byteArrayOf(7,8,9))
        val fp2=root.resolve("IMG_1234.FP2"); fp2.writeBytes(Paths.get("..","fixtures","xpro2-editable.FP2").readBytes())
        val photo=Photo("f","","IMG_1234","IMG_1234.xmp",rawPath="IMG_1234.RAF",sourceIdentity=identity(root,raf))
        val store=FujiProfileStore(root,true); val before=fp2.readBytes(); val mtime=fp2.getLastModifiedTime()
        assertEquals("0",store.mutate(photo,mapOf("ExposureBias" to "0")).exposureBias); assertContentEquals(before,fp2.readBytes()); assertEquals(mtime,fp2.getLastModifiedTime())
        assertEquals("P0P33",store.mutate(photo,mapOf("ExposureBias" to "P0P33")).exposureBias); assertEquals(1,root.listDirectoryEntries(".IMG_1234.FP2.previous.*.fp2").size); assertContains(fp2.readText(),"preserve-me")
        fp2.deleteExisting(); root.listDirectoryEntries(".IMG_1234.FP2.previous.*.fp2").forEach { it.deleteExisting() }; root.resolve("IMG_1234.FP3").writeBytes(Paths.get("..","fixtures","xpro2-rendered.FP3").readBytes())
        assertFalse(store.read(photo)!!.editable); assertFails { store.mutate(photo,mapOf("Color" to "1")) }
    }

    @Test fun hdrAndEvidencedTransferAreExplicit() {
        val on=hdrUpdates(HdrSettings(true)); assertEquals("1",on["HDREditMode"]); assertEquals("4.00",on["HDRMaxValue"]); assertEquals("0",on["SDRBlend"])
        val off=hdrUpdates(HdrSettings(false)); assertEquals("0",off["HDREditMode"]); assertNull(off["HDRMaxValue"])
    }

    @Test fun hdrDomReadWriteNoopAndDisablePreserveOpaqueContent() {
        val root=createTempDirectory(); val raw=root.resolve("H.DNG"); raw.writeBytes(byteArrayOf(4,5,6)); val sidecar=root.resolve("H.xmp")
        sidecar.writeBytes(Paths.get("..","fixtures","lightroom-hdr-on.xmp").readBytes())
        val photo=Photo("h","","H","H.xmp",rawPath="H.DNG",sourceIdentity=identity(root,raw)); val store=XmpSidecarStore(root,true)
        val current=store.readDevelop(photo); assertTrue(current.hdrEnabled); assertEquals(73,current.controls["SDRContrast"])
        val before=sidecar.readBytes(); val mtime=sidecar.getLastModifiedTime(); assertEquals(current,store.mutateDevelop(photo,current)); assertContentEquals(before,sidecar.readBytes()); assertEquals(mtime,sidecar.getLastModifiedTime())
        assertFalse(store.mutateDevelop(photo,DevelopSettings(false)).hdrEnabled); val written=sidecar.readText(); assertContains(written,"preserve-hdr-comment"); assertContains(written,"custom:payload"); assertFalse("HDRMaxValue" in written)
    }

    @Test fun kimMetadataAndBoundedPreviewUseIndexedJpegIdentity() {
        val root=createTempDirectory("phototool-media"); val cache=createTempDirectory("phototool-preview")
        val jpeg=root.resolve("REAL.JPG"); val image=BufferedImage(2400,1600,BufferedImage.TYPE_INT_RGB)
        image.createGraphics().use { it.color=Color(30,90,150); it.fillRect(0,0,image.width,image.height) }
        assertTrue(ImageIO.write(image,"jpeg",jpeg.toFile()))
        val result=LibraryScanner().scan(root,AtomicBoolean()){ }
        val photo=result.photos.single()
        assertEquals(MetadataStatus.AVAILABLE,photo.metadata.status); assertEquals(2400,photo.metadata.width); assertNotNull(photo.sourceIdentity)
        val thumbnail=PreviewStore(root,cache).thumbnail(photo,512)
        ImageIO.read(thumbnail.toFile()).also { assertEquals(512,maxOf(it.width,it.height)); assertTrue(it.width.toLong()*it.height<=20_000_000) }
        jpeg.writeBytes(jpeg.readBytes()+byteArrayOf(0))
        assertFailsWith<PreviewException> { PreviewStore(root,cache).thumbnail(photo,512) }
    }

    @Test fun tiffOrientationIsAppliedBeforePublishingPreviews() {
        val source=BufferedImage(2,3,BufferedImage.TYPE_INT_RGB)
        source.setRGB(0,0,Color.RED.rgb); source.setRGB(1,0,Color.GREEN.rgb)
        source.setRGB(0,1,Color.BLUE.rgb); source.setRGB(1,1,Color.WHITE.rgb)
        source.setRGB(0,2,Color.YELLOW.rgb); source.setRGB(1,2,Color.CYAN.rgb)
        assertSame(source,applyTiffOrientation(source,TiffOrientation.STANDARD))
        val left=applyTiffOrientation(source,TiffOrientation.ROTATE_LEFT)
        assertEquals(3,left.width); assertEquals(2,left.height)
        assertEquals(Color.RED.rgb,left.getRGB(0,1)); assertEquals(Color.GREEN.rgb,left.getRGB(0,0))
        val right=applyTiffOrientation(source,TiffOrientation.ROTATE_RIGHT)
        assertEquals(Color.RED.rgb,right.getRGB(2,0)); assertEquals(Color.GREEN.rgb,right.getRGB(2,1))
        val mirrored=applyTiffOrientation(source,TiffOrientation.MIRROR_HORIZONTAL)
        assertEquals(Color.RED.rgb,mirrored.getRGB(1,0)); assertEquals(Color.GREEN.rgb,mirrored.getRGB(0,0))
        val upsideDown=applyTiffOrientation(source,TiffOrientation.UPSIDE_DOWN)
        assertEquals(Color.RED.rgb,upsideDown.getRGB(1,2)); assertEquals(Color.CYAN.rgb,upsideDown.getRGB(0,0))
        val mirroredVertically=applyTiffOrientation(source,TiffOrientation.MIRROR_VERTICAL)
        assertEquals(Color.RED.rgb,mirroredVertically.getRGB(0,2)); assertEquals(Color.YELLOW.rgb,mirroredVertically.getRGB(0,0))
        val transposed=applyTiffOrientation(source,TiffOrientation.MIRROR_HORIZONTAL_AND_ROTATE_LEFT)
        assertEquals(Color.RED.rgb,transposed.getRGB(0,0)); assertEquals(Color.GREEN.rgb,transposed.getRGB(0,1))
        val transverse=applyTiffOrientation(source,TiffOrientation.MIRROR_HORIZONTAL_AND_ROTATE_RIGHT)
        assertEquals(Color.RED.rgb,transverse.getRGB(2,1)); assertEquals(Color.GREEN.rgb,transverse.getRGB(2,0))
    }

    @Test fun cacheRoundTripsExpandedMetadataIdentityAndNormalizedKeywords() {
        val root=createTempDirectory(); val media=root.resolve("A.JPG"); media.writeBytes(byteArrayOf(1)); val cache=PhotoCache(createTempDirectory(),root)
        val id=identity(root,media)
        val photo=scanPhoto(root).copy(
            metadata=ObservedMetadata("2025-01-01T10:00:00Z","Camera","Make","Model","Lens",35.0,2.8,.01,200,-23.0,-46.0,6000,4000,MetadataStatus.AVAILABLE),
            editorial=EditorialState(Flag.PICK,4,ColorLabel.RED,listOf("São Paulo","Brasil|Sudeste"),good=true))
        cache.publish(listOf(photo)); val loaded=cache.load().single()
        assertEquals(photo.metadata,loaded.metadata); assertEquals(photo.sourceIdentity,loaded.sourceIdentity); assertEquals(photo.editorial,loaded.editorial)
    }

    @Test fun xmpPreservesDeclarationBomCrLfAndRejectsDuplicateManagedProperties() {
        val root=createTempDirectory(); val raw=root.resolve("B.CR2"); raw.writeBytes(byteArrayOf(1,2)); val id=identity(root,raw)
        val xml="<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmlns:xmpDM=\"http://ns.adobe.com/xmp/1.0/DynamicMedia/\" custom=\"opaque\" xmlns:custom=\"urn:test\"><!--opaque--></rdf:Description></rdf:RDF></x:xmpmeta>\r\n"
        val sidecar=root.resolve("B.xmp"); sidecar.writeBytes(byteArrayOf(0xEF.toByte(),0xBB.toByte(),0xBF.toByte())+xml.toByteArray())
        val photo=Photo("b","","B","B.xmp",rawPath="B.CR2",sourceIdentity=id)
        XmpSidecarStore(root,true).mutate(photo,EditorialState(rating=3))
        val bytes=sidecar.readBytes(); assertContentEquals(byteArrayOf(0xEF.toByte(),0xBB.toByte(),0xBF.toByte()),bytes.take(3).toByteArray())
        val written=bytes.drop(3).toByteArray().toString(Charsets.UTF_8); assertTrue(written.startsWith("<?xml")); assertContains(written,"\r\n"); assertContains(written,"opaque")
        sidecar.writeText("""<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:xmpDM="http://ns.adobe.com/xmp/1.0/DynamicMedia/" xmp:Rating="2"><xmp:Rating>2</xmp:Rating></rdf:Description></rdf:RDF></x:xmpmeta>""")
        assertEquals(2,XmpSidecarStore(root,true).read(photo).rating)
        sidecar.writeText(sidecar.readText().replace("<xmp:Rating>2</xmp:Rating>","<xmp:Rating>3</xmp:Rating>"))
        assertFailsWith<XmpException> { XmpSidecarStore(root,true).read(photo) }
    }

    @Test fun syncSummaryIsBoundedAtomicAndRejectsLinks() {
        val cache=createTempDirectory(); val store=SyncSummaryStore(cache); val summary=SyncSummary("success",12,1,"done",2345,3,1,2,"12345678-1234-1234-1234-123456789abc","a".repeat(64))
        store.save(summary); assertEquals(summary,store.load())
        assertFalse(cache.resolve("sync-summary.json").readText().contains("/"))
        val target=cache.resolve("sync-summary.json"); target.deleteExisting(); Files.createSymbolicLink(target,cache.resolve("missing"))
        assertFails { store.save(summary) }
    }


    @Test fun sensitiveBatchReportsHdrAndFujiChannelsAndNeverTouchesRaw() = runBlocking {
        val root=createTempDirectory(); val raw=root.resolve("F.RAF"); raw.writeBytes(byteArrayOf(1,2,3)); val original=raw.readBytes()
        val jpeg=root.resolve("J.JPG"); jpeg.writeBytes(byteArrayOf(4))
        root.resolve("F.FP2").writeBytes(Paths.get("..","fixtures","xpro2-editable.FP2").readBytes())
        val photo=Photo("f","","F","F.xmp",rawPath="F.RAF",sourceIdentity=identity(root,raw),writable=true)
        val ignored=Photo("j","","J","J.xmp",jpegPath="J.JPG",sourceIdentity=identity(root,jpeg),writable=true)
        val bad=photo.copy(id="bad",authorityPath="../unsafe.xmp")
        FujiProfileStore(root,true).use { fuji ->
            val actions=DesktopAuxiliaryActions(XmpSidecarStore(root,true),fuji).callbacks()
            val controls=listOf("SDRBrightness", "SDRContrast", "SDRClarity", "SDRHighlights", "SDRShadows", "SDRWhites", "SDRBlend").mapIndexed { index,name -> name to (index - 3) }.toMap()
            val hdr=actions.batchUpdate(listOf(bad,ignored,photo),AuxiliaryBatchEdit.SetHdr(true,"2.00",controls)); assertEquals(1,hdr.succeeded); assertEquals(1,hdr.ignored); assertEquals(1,hdr.failed); assertEquals(3,hdr.items.size); assertTrue(hdr.items.all { '/' !in it.photoId })
            val beforeFuji=fuji.read(photo)!!
            val recipe=actions.batchUpdate(listOf(photo),AuxiliaryBatchEdit.UpdateFuji(mapOf("FilmSimulation" to "Astia"),"Astia")); assertEquals(1,recipe.succeeded)
            val afterFuji=fuji.read(photo)!!; assertEquals("Astia",afterFuji.filmSimulation); assertEquals(beforeFuji,afterFuji.copy(filmSimulation=beforeFuji.filmSimulation,version=beforeFuji.version))
            val bounded=actions.batchUpdate((0..100).map { ignored.copy(id="j-$it") },AuxiliaryBatchEdit.SetHdr(false)); assertEquals(101,bounded.ignored); assertEquals(0,bounded.succeeded)
        }
        assertContentEquals(original,raw.readBytes()); assertTrue(root.resolve("F.xmp").exists()); assertContains(root.resolve("F.xmp").readText(),"HDRMaxValue=\"2.00\""); assertContains(root.resolve("F.xmp").readText(),"SDRBlend=\"3\""); assertContains(root.resolve("F.FP2").readText(),"Astia")
    }

    @Test fun malformedFujiIsFailedWhileAbsentAndReadOnlyProfilesAreIgnored() = runBlocking {
        val root=createTempDirectory(); val raw=root.resolve("F.RAF"); raw.writeBytes(byteArrayOf(1)); root.resolve("F.FP2").writeText("<bad")
        val malformed=Photo("malformed","","F","F.xmp",rawPath="F.RAF",sourceIdentity=identity(root,raw),writable=true)
        val missingRaw=root.resolve("M.RAF"); missingRaw.writeBytes(byteArrayOf(2)); val missing=Photo("missing","","M","M.xmp",rawPath="M.RAF",sourceIdentity=identity(root,missingRaw),writable=true)
        val readOnlyRaw=root.resolve("N.RAF"); readOnlyRaw.writeBytes(byteArrayOf(3)); root.resolve("N.FP3").writeBytes(Paths.get("..","fixtures","xpro2-rendered.FP3").readBytes())
        val readOnly=Photo("readonly","","N","N.xmp",rawPath="N.RAF",sourceIdentity=identity(root,readOnlyRaw),writable=true)
        FujiProfileStore(root,true).use { fuji ->
            val result=DesktopAuxiliaryActions(XmpSidecarStore(root,true),fuji).callbacks().batchUpdate(listOf(malformed,missing,readOnly),AuxiliaryBatchEdit.UpdateFuji(mapOf("Color" to "1"),"Cor"))
            assertEquals(1,result.failed); assertEquals(2,result.ignored); assertEquals(0,result.succeeded)
            assertEquals(AuxiliaryBatchOutcome.FAILED,result.items.single { it.photoId=="malformed" }.outcome)
            assertNotNull(result.items.single { it.photoId=="malformed" }.errorCode)
        }
        Unit
    }

    @Test fun xmpChangedWritePreservesUtf16BomAndEncoding() {
        val root=createTempDirectory(); val raw=root.resolve("U.CR2"); raw.writeBytes(byteArrayOf(1)); val sidecar=root.resolve("U.xmp")
        val text="<?xml version=\"1.0\" encoding=\"UTF-16\"?>\r\n<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmlns:xmpDM=\"http://ns.adobe.com/xmp/1.0/DynamicMedia/\"/></rdf:RDF></x:xmpmeta>"
        sidecar.writeBytes(byteArrayOf(0xFF.toByte(),0xFE.toByte())+text.toByteArray(Charsets.UTF_16LE))
        val photo=Photo("u","","U","U.xmp",rawPath="U.CR2",sourceIdentity=identity(root,raw)); XmpSidecarStore(root,true).mutate(photo,EditorialState(rating=2))
        val bytes=sidecar.readBytes(); assertContentEquals(byteArrayOf(0xFF.toByte(),0xFE.toByte()),bytes.take(2).toByteArray()); val written=bytes.drop(2).toByteArray().toString(Charsets.UTF_16LE)
        assertContains(written,"encoding=\"UTF-16\""); assertContains(written,"\r\n"); assertEquals(2,XmpSidecarStore(root,false).read(photo).rating)
    }

    @Test fun xmpChangedWritePreservesCrOnlyLineEndings() {
        val root=createTempDirectory(); val raw=root.resolve("C.CR2"); raw.writeBytes(byteArrayOf(1)); val sidecar=root.resolve("C.xmp")
        sidecar.writeText("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmlns:xmpDM=\"http://ns.adobe.com/xmp/1.0/DynamicMedia/\"/></rdf:RDF></x:xmpmeta>\r")
        val photo=Photo("c","","C","C.xmp",rawPath="C.CR2",sourceIdentity=identity(root,raw))
        XmpSidecarStore(root,true).use { it.mutate(photo,EditorialState(rating=3)) }
        val written=sidecar.readText()
        assertContains(written,"\r"); assertFalse('\n' in written); assertEquals(3,XmpSidecarStore(root,false).use { it.read(photo) }.rating)
    }

    @Test fun desktopAuxiliaryCallbacksUseIoDispatcherAndRejectNonRafHdr() = runBlocking {
        val root=createTempDirectory(); val jpeg=root.resolve("A.JPG"); jpeg.writeBytes(byteArrayOf(1))
        val xmp=XmpSidecarStore(root,true); val fuji=FujiProfileStore(root,true)
        try {
            var callbackThread: String?=null
            val callbacks=DesktopAuxiliaryActions(xmp,fuji) { callbackThread=Thread.currentThread().name; true }.callbacks()
            callbacks.batchUpdate(emptyList(),AuxiliaryBatchEdit.SetHdr(false))
            assertNotNull(callbackThread); assertNotEquals(Thread.currentThread().name,callbackThread)
            val jpegPhoto=Photo("jpeg","","A","A.xmp",jpegPath="A.JPG",sourceIdentity=identity(root,jpeg),writable=true)
            assertFailsWith<IllegalArgumentException> { callbacks.updateHdr(jpegPhoto,HdrView(true)) }
            assertFalse(root.resolve("A.xmp").exists())
        } finally { fuji.close(); xmp.close() }
    }

    @Test fun composePreviewLoadDecodesOutsideCallingThread() = runBlocking {
        val root=createTempDirectory(); val cache=createTempDirectory(); val jpeg=root.resolve("I.JPG")
        ImageIO.write(BufferedImage(320,200,BufferedImage.TYPE_INT_RGB),"jpeg",jpeg.toFile())
        val photo=LibraryScanner().scan(root,AtomicBoolean()){ }.photos.single()
        var decodeThread: String?=null
        val caller=Thread.currentThread().name
        assertNotNull(PreviewStore(root,cache) { decodeThread=Thread.currentThread().name }.load(photo,256))
        assertNotNull(decodeThread); assertNotEquals(caller,decodeThread)
    }

    @Test fun onDemandPreviewLoadsUseFourBoundedParallelSlots() = runBlocking {
        val root=createTempDirectory(); val cache=createTempDirectory()
        repeat(5) { index -> ImageIO.write(BufferedImage(640,400,BufferedImage.TYPE_INT_RGB),"jpeg",root.resolve("P$index.JPG").toFile()) }
        val photos=LibraryScanner().scan(root,AtomicBoolean()){ }.photos
        val entered=CountDownLatch(4); val release=CountDownLatch(1); val active=AtomicInteger(); val maximum=AtomicInteger()
        val store=PreviewStore(root,cache) {
            val current=active.incrementAndGet(); maximum.updateAndGet { maxOf(it,current) }; entered.countDown()
            try { check(release.await(10,TimeUnit.SECONDS)) } finally { active.decrementAndGet() }
        }
        try {
            val jobs=photos.map { photo -> async(Dispatchers.IO) { store.load(photo,256) } }
            val fourOverlapped=withContext(Dispatchers.IO) { entered.await(10,TimeUnit.SECONDS) }
            assertTrue(fourOverlapped,"visible previews must not be generated serially"); assertEquals(4,active.get())
            release.countDown()
            assertTrue(jobs.awaitAll().all { it != null }); assertEquals(4,maximum.get())
        } finally { release.countDown(); store.close() }
    }

    @Test fun samePreviewKeyIsGeneratedOnceAcrossOverlappingStores() = runBlocking {
        val root=createTempDirectory(); val cache=createTempDirectory(); val jpeg=root.resolve("ONE.JPG")
        ImageIO.write(BufferedImage(640,400,BufferedImage.TYPE_INT_RGB),"jpeg",jpeg.toFile())
        val photo=LibraryScanner().scan(root,AtomicBoolean()){ }.photos.single()
        val sourceReads=AtomicInteger(); val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val observer={ sourceReads.incrementAndGet(); entered.countDown(); check(release.await(10,TimeUnit.SECONDS)) }
        val first=PreviewStore(root,cache,observer); val second=PreviewStore(root,cache,observer)
        try {
            val jobs=listOf(first,second).map { store -> async(Dispatchers.IO) { store.load(photo,256) } }
            assertTrue(withContext(Dispatchers.IO) { entered.await(10,TimeUnit.SECONDS) }); release.countDown()
            assertTrue(jobs.awaitAll().all { it != null }); assertEquals(1,sourceReads.get())
        } finally { release.countDown(); first.close(); second.close() }
    }

    @Test fun closingPreviewStoreWaitsForActiveOnDemandLoad() = runBlocking {
        val root=createTempDirectory(); val cache=createTempDirectory(); val jpeg=root.resolve("ACTIVE.JPG")
        ImageIO.write(BufferedImage(640,400,BufferedImage.TYPE_INT_RGB),"jpeg",jpeg.toFile())
        val photo=LibraryScanner().scan(root,AtomicBoolean()){ }.photos.single()
        val entered=CountDownLatch(1); val release=CountDownLatch(1); val closed=CountDownLatch(1)
        val store=PreviewStore(root,cache) { entered.countDown(); check(release.await(10,TimeUnit.SECONDS)) }
        var closing: Deferred<Unit>?=null
        try {
            val loading=async(Dispatchers.IO) { store.load(photo,256) }
            assertTrue(withContext(Dispatchers.IO) { entered.await(10,TimeUnit.SECONDS) })
            closing=async(Dispatchers.IO) { store.close(); closed.countDown() }
            assertFalse(withContext(Dispatchers.IO) { closed.await(200,TimeUnit.MILLISECONDS) },"close must not invalidate an active source read")
            release.countDown()
            assertNotNull(loading.await()); assertTrue(withContext(Dispatchers.IO) { closed.await(10,TimeUnit.SECONDS) })
        } finally { release.countDown(); closing?.await() ?: store.close() }
    }

    @Test fun synchronizationPublishesWithoutGeneratingPreviewCache() {
        val root=createTempDirectory(); val cache=createTempDirectory()
        ImageIO.write(BufferedImage(640,400,BufferedImage.TYPE_INT_RGB),"jpeg",root.resolve("ONLY.JPG").toFile())
        val controller=DesktopController(LaunchOptions(root,cache,false,false))
        try {
            controller.awaitBackgroundWork()
            controller.callbacks.synchronize()
            controller.awaitBackgroundWork()
            assertEquals(SyncPhase.COMPLETE,controller.state.sync.phase); assertEquals(1,controller.state.photos.size)
            assertTrue(cache.resolve("previews").listDirectoryEntries().isEmpty(),"synchronization must not generate previews")
        } finally { controller.close() }
    }

    @Test fun immutableFlightRejectsTwoLibrarySyncRace() {
        val first=createTempDirectory(); val second=createTempDirectory()
        assertTrue(immutableFlightMayPublish(first,first)); assertFalse(immutableFlightMayPublish(first,second))
    }

    @Test fun uppercaseXmpAuthorityIsPreservedWithoutDuplicateCreation() {
        val root=createTempDirectory(); val raw=root.resolve("IMG.CR2"); raw.writeBytes(byteArrayOf(1)); root.resolve("IMG.XMP").writeText(baseXmp())
        val photo=LibraryScanner(object:MediaObservationAdapter { override fun observe(bytes: ByteArray, kind: MediaKind)=ObservedMetadata() }).scan(root,AtomicBoolean()){ }.photos.single()
        assertEquals("IMG.XMP",photo.authorityPath)
        XmpSidecarStore(root,true).mutate(photo,EditorialState(rating=2))
        assertTrue(root.resolve("IMG.XMP").exists()); assertFalse(root.resolve("IMG.xmp").exists())
    }

    @Test fun absentCanonicalRecoveryArtifactFailsClosed() {
        val root=createTempDirectory(); val raw=root.resolve("A.CR2"); raw.writeBytes(byteArrayOf(1)); root.resolve(".A.xmp.previous.crash.xmp").writeText(baseXmp())
        val photo=Photo("id","","A","A.xmp",rawPath="A.CR2",sourceIdentity=identity(root,raw))
        assertFailsWith<XmpException> { XmpSidecarStore(root,true).mutate(photo,EditorialState(rating=1)) }
        assertFalse(root.resolve("A.xmp").exists())
    }

    @Test fun absentCanonicalReadsRejectLowercaseAndUppercaseRecoveryArtifacts() {
        listOf(".A.xmp.previous.crash.xmp", ".A.XMP.conflict.crash.xmp").forEach { artifact ->
            val root=createTempDirectory(); val raw=root.resolve("A.CR2"); raw.writeBytes(byteArrayOf(1)); root.resolve(artifact).writeText(baseXmp())
            val photo=Photo("id","","A","A.xmp",rawPath="A.CR2",sourceIdentity=identity(root,raw))
            XmpSidecarStore(root,false).use { store ->
                assertFailsWith<XmpException> { store.read(photo) }
                assertFailsWith<XmpException> { store.readDevelop(photo) }
            }
        }
    }

    @Test fun lowercaseAndUppercaseXmpAuthoritiesBothReadAndWriteInPlace() {
        listOf("lower.xmp", "UPPER.XMP").forEach { authority ->
            val root=createTempDirectory(); val stem=authority.substringBeforeLast('.'); val raw=root.resolve("$stem.CR2"); raw.writeBytes(byteArrayOf(1)); root.resolve(authority).writeText(baseXmp())
            val photo=Photo("id","",stem,authority,rawPath="$stem.CR2",sourceIdentity=identity(root,raw))
            XmpSidecarStore(root,true).use { assertEquals(3,it.mutate(photo,EditorialState(rating=3)).rating) }
            assertEquals(3,XmpSidecarStore(root,false).use { it.read(photo).rating })
            assertEquals(1,root.listDirectoryEntries().count { it.extension.equals("xmp",true) && !it.name.startsWith('.') })
        }
    }

    @Test fun fujiReadRejectsCaseVariantAbsentProfileRecoveryArtifact() {
        val root=createTempDirectory(); val raw=root.resolve("F.RAF"); raw.writeBytes(byteArrayOf(1)); root.resolve(".F.FP2.previous.crash.fp2").writeText("recovery")
        val photo=Photo("id","","F","F.xmp",rawPath="F.RAF",sourceIdentity=identity(root,raw))
        assertFails { FujiProfileStore(root,false).use { it.read(photo) } }
    }

    @Test fun malformedRatingAndHdrFailClosed() {
        val root=createTempDirectory(); val raw=root.resolve("M.DNG"); raw.writeBytes(byteArrayOf(1)); val xmp=root.resolve("M.xmp")
        val photo=Photo("id","","M","M.xmp",rawPath="M.DNG",sourceIdentity=identity(root,raw))
        xmp.writeText(baseXmp().replace("/>"," xmp:Rating=\"bad\"/>").replace("xmlns:xmpDM", "xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmlns:xmpDM"))
        assertFailsWith<XmpException> { XmpSidecarStore(root,false).read(photo) }
        xmp.writeText(baseXmp().replace("/>"," crs:HDREditMode=\"1\" crs:HDRMaxValue=\"bad\"/>").replace("xmlns:xmpDM", "xmlns:crs=\"http://ns.adobe.com/camera-raw-settings/1.0/\" xmlns:xmpDM"))
        assertFailsWith<XmpException> { XmpSidecarStore(root,false).readDevelop(photo) }
    }

    @Test fun flatOnlyKeywordProvenanceSurvivesRatingChange() {
        val root=createTempDirectory(); val raw=root.resolve("K.CR2"); raw.writeBytes(byteArrayOf(1)); val xmp=root.resolve("K.xmp")
        xmp.writeText("""<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:xmpDM="http://ns.adobe.com/xmp/1.0/DynamicMedia/" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:subject><rdf:Bag><rdf:li>Flat only</rdf:li></rdf:Bag></dc:subject></rdf:Description></rdf:RDF></x:xmpmeta>""")
        val photo=Photo("id","","K","K.xmp",rawPath="K.CR2",sourceIdentity=identity(root,raw)); XmpSidecarStore(root,true).mutate(photo,EditorialState(rating=4,keywords=listOf("Flat only")))
        assertContains(xmp.readText(),"dc:subject"); assertFalse("hierarchicalSubject" in xmp.readText())
    }

    @Test fun staleCompleteTopologyBlocksWrite() {
        val root=createTempDirectory(); val raw=root.resolve("T.CR3"); raw.writeBytes(byteArrayOf(1)); val photo=scanPhoto(root)
        root.resolve("T.JPG").writeBytes(byteArrayOf(2))
        assertFailsWith<XmpException> { XmpSidecarStore(root,true).mutate(photo,EditorialState(rating=1)) }
    }

    @Test fun xmpTopologyUsesUnicodeCasefoldForStemIdentity() {
        val root=createTempDirectory(); root.resolve("Straße.CR2").writeBytes(byteArrayOf(1)); val photo=scanPhoto(root)
        root.resolve("STRASSE.DNG").writeBytes(byteArrayOf(2))
        assertFailsWith<XmpException> { XmpSidecarStore(root,true).use { it.mutate(photo,EditorialState(rating=2)) } }
        assertFalse(root.resolve("Straße.xmp").exists())
    }

    @Test fun corruptCacheRowsAndSqliteAuxiliariesAreRejected() {
        val root=createTempDirectory(); root.resolve("C.JPG").writeBytes(byteArrayOf(1)); val cacheDir=createTempDirectory(); val cache=PhotoCache(cacheDir,root); cache.publish(listOf(scanPhoto(root)))
        java.sql.DriverManager.getConnection("jdbc:sqlite:${cacheDir.resolve("phototool.sqlite3")}").use { it.createStatement().executeUpdate("UPDATE photos SET authority='C.xml'") }
        assertTrue(cache.load().isEmpty())
        cacheDir.resolve("phototool.sqlite3-wal").writeBytes(byteArrayOf(1)); assertTrue(cache.load().isEmpty())
    }

    @Test fun hardlinkedThumbnailIsNeverTouchedOrReplaced() {
        val root=createTempDirectory(); val cache=createTempDirectory(); val jpeg=root.resolve("L.JPG"); val image=BufferedImage(320,200,BufferedImage.TYPE_INT_RGB); ImageIO.write(image,"jpeg",jpeg.toFile())
        val photo=LibraryScanner().scan(root,AtomicBoolean()){ }.photos.single(); val store=PreviewStore(root,cache); val target=store.thumbnail(photo,512); target.deleteExisting(); Files.createLink(target,jpeg)
        val before=jpeg.getLastModifiedTime(); assertFailsWith<PreviewException> { store.thumbnail(photo,512) }; assertEquals(before,jpeg.getLastModifiedTime())
    }

    @Test fun thumbnailCacheHitTouchesLruTimeWithoutReplacingEntry() {
        val root=createTempDirectory(); val cache=createTempDirectory(); val jpeg=root.resolve("H.JPG"); val image=BufferedImage(320,200,BufferedImage.TYPE_INT_RGB); ImageIO.write(image,"jpeg",jpeg.toFile())
        val photo=LibraryScanner().scan(root,AtomicBoolean()){ }.photos.single(); val store=PreviewStore(root,cache); val target=store.thumbnail(photo,512)
        val pinned=java.nio.file.attribute.FileTime.fromMillis(1_234_567_000L); Files.setLastModifiedTime(target,pinned)
        val before=Files.readAttributes(target,java.nio.file.attribute.BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        assertEquals(target,store.thumbnail(photo,512))
        val after=Files.readAttributes(target,java.nio.file.attribute.BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        assertTrue(after.lastModifiedTime().toMillis() > before.lastModifiedTime().toMillis()); assertEquals(before.size(),after.size()); assertEquals(before.fileKey(),after.fileKey())
    }

    @Test fun cacheRejectsForgedSchemaForeignKeyIndexOrphanOversizedAndDomainRows() {
        fun fixture(): Triple<Path,PhotoCache,Photo> {
            val root=createTempDirectory(); root.resolve("D.JPG").writeBytes(byteArrayOf(1)); val cache=PhotoCache(createTempDirectory(),root); val photo=scanPhoto(root); cache.publish(listOf(photo)); return Triple(root,cache,photo)
        }
        fun forge(sql: String) {
            val (_,cache,_)=fixture(); val field=PhotoCache::class.java.getDeclaredField("live").apply { isAccessible=true }; val database=field.get(cache) as Path
            java.sql.DriverManager.getConnection("jdbc:sqlite:$database").use { connection -> connection.createStatement().use { statement -> sql.split(';').filter { it.isNotBlank() }.forEach(statement::execute) } }
            assertTrue(cache.load().isEmpty(),"forged cache must be rejected: $sql")
        }
        forge("DROP INDEX photos_capture")
        forge("ALTER TABLE meta RENAME TO old_meta; CREATE TABLE meta(key BLOB PRIMARY KEY,value TEXT NOT NULL); INSERT INTO meta SELECT * FROM old_meta; DROP TABLE old_meta")
        forge("PRAGMA foreign_keys=OFF; INSERT INTO keywords VALUES('000000000000000000000000','orphan','orphan',0)")
        forge("UPDATE photos SET camera='${"x".repeat(513)}'")
        forge("UPDATE photos SET rating=99")
        forge("UPDATE photos SET lat=91,lon=0")
    }

    @Test fun cacheRejectsMissingKeywordForeignKeyDefinition() {
        val root=createTempDirectory(); root.resolve("E.JPG").writeBytes(byteArrayOf(1)); val cacheDir=createTempDirectory(); val cache=PhotoCache(cacheDir,root); cache.publish(listOf(scanPhoto(root)))
        val database=cacheDir.resolve("phototool.sqlite3")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$database").use { connection -> connection.createStatement().use {
            it.executeUpdate("DROP INDEX keywords_exact")
            it.executeUpdate("ALTER TABLE keywords RENAME TO old_keywords")
            it.executeUpdate("CREATE TABLE keywords(photo_id TEXT NOT NULL,keyword TEXT NOT NULL,keyword_fold TEXT NOT NULL,ordinal INTEGER NOT NULL,PRIMARY KEY(photo_id,keyword_fold))")
            it.executeUpdate("INSERT INTO keywords SELECT * FROM old_keywords")
            it.executeUpdate("DROP TABLE old_keywords")
            it.executeUpdate("CREATE INDEX keywords_exact ON keywords(keyword_fold)")
        } }
        assertTrue(cache.load().isEmpty())
    }

    private fun baseXmp()="""<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:xmpDM="http://ns.adobe.com/xmp/1.0/DynamicMedia/"/></rdf:RDF></x:xmpmeta>"""

    @Test fun smokeHarnessPublishesReadOnlyVerificationSnapshot() {
        val root=createTempDirectory(); val cache=createTempDirectory(); val jpeg=root.resolve("S.JPG")
        val image=BufferedImage(320,200,BufferedImage.TYPE_INT_RGB); assertTrue(ImageIO.write(image,"jpeg",jpeg.toFile()))
        main(arrayOf("--smoke","--read-only","--library",root.toString(),"--cache",cache.toString()))
        assertEquals(1,PhotoCache(cache,root).load().size); assertFalse(root.resolve("S.xmp").exists())
    }

    @Test fun smokeHarnessFailsBeforePublishingWhenXmpCannotBeVerified() {
        val root=createTempDirectory(); val cache=createTempDirectory(); val jpeg=root.resolve("B.JPG")
        assertTrue(ImageIO.write(BufferedImage(320,200,BufferedImage.TYPE_INT_RGB),"jpeg",jpeg.toFile()))
        root.resolve("B.xmp").writeText("<malformed")
        assertFails { SmokeHarness.run(LaunchOptions(root,cache,false,true)) }
        assertFalse(cache.resolve("phototool.sqlite3").exists(),"failed verification must not publish a cache snapshot")
    }

    @Test fun securePublicationSyncsEveryRecoverableRenameAndRestoresAfterSyncFailure() {
        val root=createTempDirectory(); val authority=root.resolve("A.xmp"); val original=baseXmp().toByteArray(); authority.writeBytes(original)
        val transitions=mutableListOf<String>()
        SecureLibraryBoundary(root,true,directorySyncObserver={ transitions += it }).use { boundary ->
            boundary.publish("A.xmp",baseXmp().replace("/>"," x=\"1\"/>").toByteArray(),original,"xmp",boundary.expectation("A.xmp"))
        }
        assertTrue("authority-to-previous" in transitions); assertTrue("temp-to-authority" in transitions)

        val stable=authority.readBytes(); var failed=false
        SecureLibraryBoundary(root,true,directorySyncObserver={ transition -> if (!failed && transition=="authority-to-previous") { failed=true; error("simulated directory sync failure") } }).use { boundary ->
            assertFails { boundary.publish("A.xmp",baseXmp().replace("/>"," y=\"2\"/>").toByteArray(),stable,"xmp",boundary.expectation("A.xmp")) }
        }
        assertContentEquals(stable,authority.readBytes(),"a failed directory sync must not leave the authority absent")

        val linked=createTempDirectory().resolve("raced.xmp"); var conflictSyncFailed=false
        val priorArtifacts=root.listDirectoryEntries(".A.xmp.previous.*.xmp").toSet()
        SecureLibraryBoundary(
            root,
            true,
            authorityDisplaced={ Files.createLink(linked,(root.listDirectoryEntries(".A.xmp.previous.*.xmp").toSet()-priorArtifacts).single()) },
            directorySyncObserver={ transition -> if (!conflictSyncFailed && transition=="previous-to-conflict") { conflictSyncFailed=true; error("simulated conflict sync failure") } },
        ).use { boundary ->
            assertFails { boundary.publish("A.xmp",baseXmp().replace("/>"," z=\"3\"/>").toByteArray(),stable,"xmp",boundary.expectation("A.xmp")) }
        }
        assertContentEquals(stable,authority.readBytes(),"conflict transition failure must restore the canonical authority")

        val concurrent=baseXmp().replace("/>"," concurrent=\"yes\"/>").toByteArray()
        SecureLibraryBoundary(root,true,authorityDisplaced={ authority.writeBytes(concurrent) }).use { boundary ->
            assertFails { boundary.publish("A.xmp",baseXmp().replace("/>"," lost=\"no\"/>").toByteArray(),stable,"xmp",boundary.expectation("A.xmp")) }
        }
        assertContentEquals(concurrent,authority.readBytes(),"a concurrent canonical authority detected before installation must never be replaced")
    }

    @Test fun finalPublicationSyncFailureRollsBackCreationReplacementAndPreservesConcurrentAuthority() {
        val createdRoot=createTempDirectory(); val created=createdRoot.resolve("created.xmp"); var creationFailed=false
        SecureLibraryBoundary(createdRoot,true,directorySyncObserver={ transition ->
            if (!creationFailed && transition=="temp-to-authority") { creationFailed=true; error("simulated final sync failure") }
        }).use { boundary ->
            assertFails { boundary.publish("created.xmp",baseXmp().toByteArray(),null,"xmp") }
        }
        assertTrue(creationFailed); assertFalse(created.exists(),"failed creation durability must roll back to absence")

        val replacementRoot=createTempDirectory(); val authority=replacementRoot.resolve("A.xmp")
        val original=baseXmp().toByteArray(); val replacement=baseXmp().replace("/>"," changed=\"yes\"/>").toByteArray(); authority.writeBytes(original)
        var replacementFailed=false
        SecureLibraryBoundary(replacementRoot,true,directorySyncObserver={ transition ->
            if (!replacementFailed && transition=="temp-to-authority") { replacementFailed=true; error("simulated final sync failure") }
        }).use { boundary ->
            assertFails { boundary.publish("A.xmp",replacement,original,"xmp",boundary.expectation("A.xmp")) }
        }
        assertTrue(replacementFailed); assertContentEquals(original,authority.readBytes(),"failed replacement durability must restore the prior authority")

        val concurrentRoot=createTempDirectory(); val concurrentAuthority=concurrentRoot.resolve("B.xmp")
        val concurrent=baseXmp().replace("/>"," concurrent=\"yes\"/>").toByteArray(); var exchanged=false
        SecureLibraryBoundary(concurrentRoot,true,directorySyncObserver={ transition ->
            if (!exchanged && transition=="temp-to-authority") {
                exchanged=true
                Files.move(concurrentAuthority,concurrentRoot.resolve("operation-installed-away.xmp"))
                concurrentAuthority.writeBytes(concurrent)
                error("simulated final sync failure after concurrent replacement")
            }
        }).use { boundary ->
            assertFails { boundary.publish("B.xmp",baseXmp().toByteArray(),null,"xmp") }
        }
        assertTrue(exchanged); assertContentEquals(concurrent,concurrentAuthority.readBytes(),"recovery must never remove a concurrent authority")
    }

    @Test fun removalSyncFailureIsSuppressedWithoutPreventingReplacementOrCreationRollback() {
        val replacementRoot=createTempDirectory(); val authority=replacementRoot.resolve("A.xmp")
        val original=baseXmp().toByteArray(); val replacement=baseXmp().replace("/>"," changed=\"yes\"/>").toByteArray(); authority.writeBytes(original)
        val replacementFailure=SecureLibraryBoundary(replacementRoot,true,directorySyncObserver={ transition ->
            when (transition) {
                "temp-to-authority" -> error("simulated final replacement sync failure")
                "failed-authority-removal" -> error("simulated replacement removal sync failure")
            }
        }).use { boundary ->
            assertFailsWith<IllegalStateException> { boundary.publish("A.xmp",replacement,original,"xmp",boundary.expectation("A.xmp")) }
        }
        assertEquals("simulated final replacement sync failure",replacementFailure.message)
        assertTrue(replacementFailure.suppressed.any { it.message=="simulated replacement removal sync failure" })
        assertContentEquals(original,authority.readBytes(),"removal fsync failure must not prevent restoring the prior authority")
        assertTrue(replacementRoot.listDirectoryEntries(".A.xmp.previous.*.xmp").isNotEmpty(),"the preserved recovery artifact must remain available")

        val creationRoot=createTempDirectory(); val created=creationRoot.resolve("created.xmp")
        val creationFailure=SecureLibraryBoundary(creationRoot,true,directorySyncObserver={ transition ->
            when (transition) {
                "temp-to-authority" -> error("simulated final creation sync failure")
                "failed-authority-removal" -> error("simulated creation removal sync failure")
            }
        }).use { boundary ->
            assertFailsWith<IllegalStateException> { boundary.publish("created.xmp",baseXmp().toByteArray(),null,"xmp") }
        }
        assertEquals("simulated final creation sync failure",creationFailure.message)
        assertTrue(creationFailure.suppressed.any { it.message=="simulated creation removal sync failure" })
        assertFalse(created.exists(),"failed creation must remain absent at runtime even when removal fsync fails")
    }

    @Test fun editorialLocksAreBoundedStableReentrantAndSerializeTheSamePhoto() {
        val root=createTempDirectory().toRealPath(); val photo=Photo("same","","same","same.xmp")
        assertEquals(4096,EditorialPhotoLocks.capacity)
        assertSame(EditorialPhotoLocks.stripeIdentity(root,photo),EditorialPhotoLocks.stripeIdentity(root,photo))
        val identities=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any,Boolean>())
        repeat(10_000) { identities += EditorialPhotoLocks.stripeIdentity(root,photo.copy(id="photo-$it")) }
        assertTrue(identities.size <= EditorialPhotoLocks.capacity,"lock storage must remain bounded by the fixed stripe count")
        assertEquals("nested",EditorialPhotoLocks.withLock(root,photo) { EditorialPhotoLocks.withLock(root,photo) { "nested" } })

        val entered=CountDownLatch(1); val release=CountDownLatch(1); val executor=Executors.newFixedThreadPool(2)
        val first=executor.submit { EditorialPhotoLocks.withLock(root,photo) { entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)) } }
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        val second=executor.submit<String> { EditorialPhotoLocks.withLock(root,photo) { "second" } }
        Thread.sleep(150); assertFalse(second.isDone,"the same library/photo key must serialize concurrent callers")
        release.countDown(); first.get(5,TimeUnit.SECONDS); assertEquals("second",second.get(5,TimeUnit.SECONDS))
        executor.shutdown(); assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS))
    }

    @Test fun xmpAcceptsConsistentDescriptionsAndPreservesUnmanagedLabelAndKeyword() {
        val root=createTempDirectory(); val raw=root.resolve("M.CR2"); raw.writeBytes(byteArrayOf(1)); val xmp=root.resolve("M.xmp")
        val unmanaged="x".repeat(170)
        xmp.writeText("""<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmp:Rating="2" xmp:Label="Purple"><dc:subject><rdf:Bag><rdf:li>$unmanaged</rdf:li></rdf:Bag></dc:subject></rdf:Description><rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="2"/></rdf:RDF></x:xmpmeta>""")
        val photo=Photo("m","","M","M.xmp",rawPath="M.CR2",sourceIdentity=identity(root,raw)); val store=XmpSidecarStore(root,true)
        val observed=store.read(photo); assertEquals(2,observed.rating); assertNull(observed.label); assertTrue(observed.keywords.isEmpty())
        assertEquals(3,store.mutate(photo,observed.copy(rating=3)).rating)
        val preserved=xmp.readText(); assertContains(preserved,"Purple"); assertContains(preserved,unmanaged)
        xmp.writeText(preserved.replaceFirst("xmp:Rating=\"3\"","xmp:Rating=\"4\""))
        assertFailsWith<XmpException> { store.read(photo) }
    }

    @Test fun scannerAndPreviewDiscardMediaChangedDuringRead() {
        val root=createTempDirectory(); val jpeg=root.resolve("R.JPG"); jpeg.writeBytes(byteArrayOf(1,2,3))
        val scanner=LibraryScanner(object:MediaObservationAdapter {
            override fun observe(bytes:ByteArray,kind:MediaKind):ObservedMetadata {
                val replacement=root.resolve("replacement.JPG"); replacement.writeBytes(byteArrayOf(9,8,7,6)); Files.move(replacement,jpeg,StandardCopyOption.REPLACE_EXISTING)
                return ObservedMetadata(width=100,height=100,status=MetadataStatus.AVAILABLE)
            }
        })
        val changed=scanner.scan(root,AtomicBoolean()){ }.photos.single()
        assertEquals("media-changed-during-read",changed.metadata.errorCode); assertFalse(changed.writable)

        val image=BufferedImage(320,200,BufferedImage.TYPE_INT_RGB); ImageIO.write(image,"jpeg",jpeg.toFile())
        val photo=LibraryScanner().scan(root,AtomicBoolean()){ }.photos.single(); val cache=createTempDirectory(); var swapped=false
        val preview=PreviewStore(root,cache) {
            if (!swapped) { swapped=true; val replacement=root.resolve("next.JPG"); ImageIO.write(BufferedImage(321,201,BufferedImage.TYPE_INT_RGB),"jpeg",replacement.toFile()); Files.move(replacement,jpeg,StandardCopyOption.REPLACE_EXISTING) }
        }
        assertFailsWith<PreviewException> { preview.thumbnail(photo,256) }
        assertTrue(cache.resolve("previews").listDirectoryEntries().isEmpty(),"preview failure must remove secure-copy and publication temporaries")
    }

    @Test fun cacheSeparationRejectsPhysicalAliasAndXmpPublishRejectsNewHardlink() {
        val library=createTempDirectory(); val alias=library.parent.resolve("library-alias-${System.nanoTime()}")
        try {
            Files.createSymbolicLink(alias,library)
            assertFails { assertCachePhysicallySeparate(library,alias,true) }
        } finally { Files.deleteIfExists(alias) }

        val raw=library.resolve("H.CR2"); raw.writeBytes(byteArrayOf(1)); val xmp=library.resolve("H.xmp"); xmp.writeText(baseXmp()); val original=xmp.readBytes()
        val photo=Photo("h","","H","H.xmp",rawPath="H.CR2",sourceIdentity=identity(library,raw)); val external=createTempDirectory().resolve("linked.xmp")
        val store=XmpSidecarStore(library,true) {
            val displaced=library.listDirectoryEntries(".H.xmp.previous.*.xmp").single()
            Files.createLink(external,displaced)
        }
        assertFails { store.mutate(photo,EditorialState(rating=4)) }
        assertContentEquals(original,xmp.readBytes()); assertContentEquals(original,external.readBytes())
    }

    @Test fun librarySwitchDrainsInFlightEditAndSameIdCannotContaminateNewRoot() {
        val first=createTempDirectory(); val second=createTempDirectory(); val cache=createTempDirectory()
        first.resolve("S.JPG").writeBytes(byteArrayOf(1)); second.resolve("S.JPG").writeBytes(byteArrayOf(2))
        val observer=object:MediaObservationAdapter { override fun observe(bytes: ByteArray, kind: MediaKind)=ObservedMetadata(status=MetadataStatus.MISSING) }
        val firstPhoto=LibraryScanner(observer).scan(first,AtomicBoolean()){ }.photos.single()
        val secondPhoto=LibraryScanner(observer).scan(second,AtomicBoolean()){ }.photos.single()
        assertEquals(firstPhoto.id,secondPhoto.id,"the adversarial roots must reuse the same relative photo id")
        val controller=DesktopController(LaunchOptions(first,cache,true,false))
        try {
            controller.awaitBackgroundWork()
            controller.callbacks.mutate(firstPhoto,firstPhoto.editorial.copy(rating=5))
            controller.switchLibrary(second)
            controller.awaitBackgroundWork()
            assertEquals(5,XmpSidecarStore(first,false).use { it.read(firstPhoto) }.rating)
            assertFalse(second.resolve("S.xmp").exists())
            assertEquals(second.toRealPath().toString(),controller.state.library)
            assertTrue(controller.state.photos.none { it.id==firstPhoto.id && it.editorial.rating==5 })
        } finally { controller.close() }
    }

    @Test fun replacedLibraryRootBlocksEditorialWriteInsteadOfUsingPinnedOldRoot() {
        val parent=createTempDirectory(); val root=parent.resolve("library").createDirectory(); val cache=createTempDirectory(); root.resolve("R.JPG").writeBytes(byteArrayOf(1))
        val observer=object:MediaObservationAdapter { override fun observe(bytes: ByteArray, kind: MediaKind)=ObservedMetadata(status=MetadataStatus.MISSING) }
        val photo=LibraryScanner(observer).scan(root,AtomicBoolean()){ }.photos.single()
        val controller=DesktopController(LaunchOptions(root,cache,true,false))
        val displaced=parent.resolve("displaced")
        try {
            controller.awaitBackgroundWork()
            Files.move(root,displaced); root.createDirectory(); root.resolve("R.JPG").writeBytes(byteArrayOf(2))
            controller.callbacks.mutate(photo,photo.editorial.copy(rating=4))
            assertFalse(displaced.resolve("R.xmp").exists()); assertFalse(root.resolve("R.xmp").exists())
            assertEquals(br.com.lincon.phototool.state.SyncPhase.FAILED,controller.state.sync.phase)
        } finally { controller.close() }
    }

    @Test fun secureDirectoryFsyncUsesPinnedHandleAfterVisibleRootExchange() {
        val parent=createTempDirectory(); val root=parent.resolve("library").createDirectory(); val displaced=parent.resolve("displaced")
        val original=baseXmp().toByteArray(); root.resolve("A.xmp").writeBytes(original); var exchanged=false
        SecureLibraryBoundary(root,true,directorySyncObserver={ transition ->
            if (!exchanged && transition=="temp-to-authority") {
                exchanged=true
                Files.move(root,displaced)
                root.createDirectory()
            }
        }).use { boundary ->
            boundary.publish("A.xmp",baseXmp().replace("/>"," changed=\"yes\"/>").toByteArray(),original,"xmp",boundary.expectation("A.xmp"))
        }
        assertTrue(exchanged)
        assertFalse(root.resolve("A.xmp").exists(),"fsync must not reopen and write through the exchanged textual root")
        assertContains(displaced.resolve("A.xmp").readText(),"changed=\"yes\"")
    }

    @Test fun descriptorReadRejectsEntryExchangedAfterOpen() {
        val root=createTempDirectory(); val authority=root.resolve("A.xmp"); authority.writeText(baseXmp()); val replacement=root.resolve("replacement.xmp"); replacement.writeText(baseXmp().replace("/>"," other=\"yes\"/>"))
        val old=root.resolve("old.xmp"); var exchanged=false
        SecureLibraryBoundary(root,false,readOpenedObserver={
            if (!exchanged) { exchanged=true; Files.move(authority,old); Files.move(replacement,authority) }
        }).use { boundary ->
            assertFails { boundary.read("A.xmp",16*1024*1024) }
        }
        assertTrue(exchanged); assertContains(authority.readText(),"other=\"yes\"")
    }

    @Test fun runtimeReadOnlyGuardBlocksEveryAuxiliaryWritePath() {
        val root=createTempDirectory(); val raw=root.resolve("A.RAF"); raw.writeBytes(byteArrayOf(1,2,3)); val sidecar=root.resolve("A.xmp"); sidecar.writeText(baseXmp()); val original=sidecar.readBytes()
        val photo=Photo("a","","A","A.xmp",rawPath="A.RAF",sourceIdentity=identity(root,raw),writable=true)
        XmpSidecarStore(root,true).use { xmp ->
            FujiProfileStore(root,true).use { fuji ->
                val actions=DesktopAuxiliaryActions(xmp,fuji) { false }.callbacks()
                assertFailsWith<IllegalStateException> { runBlocking { actions.updateFuji(photo,mapOf("ExposureBias" to "P0P33")) } }
                assertFailsWith<IllegalStateException> { runBlocking { actions.updateHdr(photo,HdrView(true,"2.00")) } }
                assertFailsWith<IllegalStateException> { runBlocking { actions.transferFujiToXmp(photo) } }
                assertFailsWith<IllegalStateException> { runBlocking { actions.transferXmpToFuji(photo) } }
                assertFailsWith<IllegalStateException> { runBlocking { actions.batchUpdate(listOf(photo),br.com.lincon.phototool.ui.AuxiliaryBatchEdit.SetHdr(true,"2.00")) } }
            }
        }
        assertContentEquals(original,sidecar.readBytes())
    }

    @Test fun crossFormatPublicationAbortsWhenSourceConfirmationDiverges() {
        val root=createTempDirectory(); val raw=root.resolve("A.RAF"); raw.writeBytes(byteArrayOf(1))
        val fp2=root.resolve("A.FP2"); fp2.writeBytes(Paths.get("..","fixtures","xpro2-editable.FP2").readBytes())
        val photo=Photo("a","","A","A.xmp",rawPath="A.RAF",sourceIdentity=identity(root,raw))
        XmpSidecarStore(root,true).use { xmp ->
            assertFails { xmp.mutateDevelopProperties(photo,mapOf("Exposure2012" to "1.00")) { false } }
        }
        assertFalse(root.resolve("A.xmp").exists())
        val original=fp2.readBytes()
        FujiProfileStore(root,true).use { fuji ->
            assertFails { fuji.mutate(photo,mapOf("ExposureBias" to "P1P00")) { false } }
        }
        assertContentEquals(original,fp2.readBytes())
    }

    @Test fun mountPointDecoderExpandsOctalEscapes() {
        assertEquals("/media/linconvidal/8BEB9CFB5BCD4471/imgs",decodeMountPoint("/media/linconvidal/8BEB9CFB5BCD4471/imgs"))
        assertEquals("/home/linconvidal/A B",decodeMountPoint("/home/linconvidal/A\\040B"))
        assertEquals("/tmp/x\ty",decodeMountPoint("/tmp/x\\011y"))
        assertEquals("/home/linconvidal/a\\b",decodeMountPoint("/home/linconvidal/a\\134b"))
        assertEquals("plain",decodeMountPoint("plain"))
    }

    @Test fun unlockWatcherParsesLockedHintAndEscapesSessionPath() {
        assertTrue(UnlockWatcher.parseLockedHint("b true\n") == true)
        assertTrue(UnlockWatcher.parseLockedHint("b false\n") == false)
        assertNull(UnlockWatcher.parseLockedHint(""))
        assertNull(UnlockWatcher.parseLockedHint("Failed to get property\n"))
        assertEquals("_32",UnlockWatcher.dbusEscapePathElement("2"))
        assertEquals("session_2ec1",UnlockWatcher.dbusEscapePathElement("session.c1"))
        assertEquals("ab12",UnlockWatcher.dbusEscapePathElement("ab12"))
        assertEquals("user_2dname",UnlockWatcher.dbusEscapePathElement("user-name"))
    }

    @Test fun cachePublicationAndReloadRemainLinearForSharedFolders() {
        val root=createTempDirectory(); val cache=createTempDirectory()
        repeat(40) { index -> ImageIO.write(BufferedImage(64,48,BufferedImage.TYPE_INT_RGB),"jpeg",root.resolve("IMG${index.toString().padStart(3,'0')}.JPG").toFile()) }
        val photos=LibraryScanner().scan(root,AtomicBoolean()){ }.photos
        assertEquals(40,photos.size)
        val photoCache=PhotoCache(cache,root)
        photoCache.publish(photos)
        val reloaded=photoCache.load()
        assertEquals(40,reloaded.size)
        val target=photos.first()
        photoCache.updateEditorial(target.id,EditorialState(flag=Flag.PICK,rating=3))
        assertEquals(Flag.PICK,photoCache.load().single { it.id == target.id }.editorial.flag)
    }

    @Test fun cacheRejectsAliasToLibraryDescendant() {
        val library=createTempDirectory(); val descendant=library.resolve("nested").createDirectory(); val alias=library.parent.resolve("cache-descendant-alias-${System.nanoTime()}")
        try {
            Files.createSymbolicLink(alias,descendant)
            assertFails { assertCachePhysicallySeparate(library,alias,true) }
            assertFails { PhotoCache(alias,library) }
        } finally { Files.deleteIfExists(alias) }
    }

    @Test fun linuxWaylandDefaultsToSoftwareRenderingWithoutOverridingExplicitChoice() {
        assertEquals("SOFTWARE", automaticDesktopRenderApi("Linux", "wayland", "wayland-1", null, null))
        assertEquals("SOFTWARE", automaticDesktopRenderApi("Linux", null, "wayland-1", null, null))
        assertNull(automaticDesktopRenderApi("Linux", "x11", null, null, null))
        assertNull(automaticDesktopRenderApi("Mac OS X", "wayland", "wayland-1", null, null))
        assertNull(automaticDesktopRenderApi("Linux", "wayland", "wayland-1", "OPENGL", null))
        assertNull(automaticDesktopRenderApi("Linux", "wayland", "wayland-1", null, "OPENGL"))
    }

    @Test fun runtimeReadOnlyModeIsInAppControlledWithLaunchFlagAsFirstRunSeed() {
        val cache=createTempDirectory()
        DesktopController(LaunchOptions(null,cache,false,false)).use { controller ->
            assertTrue(controller.state.writeAuthorized,"as lojas são sempre write-capable; a preferência in-app é a única fonte")
            assertFalse(controller.state.writeEnabled)
            controller.callbacks.setReadOnlyMode(false)
            assertTrue(controller.state.writeEnabled,"a interface é a única fonte de verdade para o modo de escrita")
            controller.callbacks.setReadOnlyMode(true)
            assertFalse(controller.state.writeEnabled)
        }
        val seeded=createTempDirectory()
        DesktopController(LaunchOptions(null,seeded,true,false)).use { controller ->
            assertTrue(controller.state.writeEnabled)
        }
    }

    @Test fun writePreferencePersistsAcrossInstancesAndWinsOverLaunchFlags() {
        val cache=createTempDirectory()
        DesktopController(LaunchOptions(null,cache,false,false)).use { controller ->
            assertFalse(controller.state.writeEnabled)
            controller.callbacks.setReadOnlyMode(false)
            assertTrue(controller.state.writeEnabled)
        }
        DesktopController(LaunchOptions(null,cache,false,false)).use { controller ->
            assertTrue(controller.state.writeEnabled,"a preferência salva in-app deve prevalecer sobre a seed somente leitura")
        }
        DesktopController(LaunchOptions(null,cache,true,false)).use { controller ->
            controller.callbacks.setReadOnlyMode(true)
            assertFalse(controller.state.writeEnabled)
        }
        DesktopController(LaunchOptions(null,cache,true,false)).use { controller ->
            assertFalse(controller.state.writeEnabled,"o modo somente leitura salvo in-app deve prevalecer mesmo com --enable-write")
        }
    }

    @Test fun runtimeReadOnlyModeBlocksAuthorizedControllerMutations() {
        val root=createTempDirectory(); val cache=createTempDirectory(); val raw=root.resolve("A.RAF"); raw.writeBytes(byteArrayOf(1,2,3))
        val sidecar=root.resolve("A.xmp"); sidecar.writeText(baseXmp()); val original=sidecar.readBytes()
        val photo=Photo("a","","A","A.xmp",rawPath="A.RAF",sourceIdentity=identity(root,raw),writable=true)
        val controller=DesktopController(LaunchOptions(root,cache,true,false))
        try {
            controller.awaitBackgroundWork()
            controller.callbacks.setReadOnlyMode(true)
            controller.callbacks.mutate(photo,EditorialState(flag=Flag.PICK))
        } finally { controller.close() }
        assertContentEquals(original,sidecar.readBytes())
    }

    @Test fun libraryConfigurationCallbackReturnsWhileIoRunsOffUiThread() {
        val root=createTempDirectory(); val cache=createTempDirectory(); val entered=CountDownLatch(1); val release=CountDownLatch(1); var threadName=""
        val controller=DesktopController(LaunchOptions(null,cache,false,false)) { name -> threadName=name; entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)) }
        try {
            controller.switchLibrary(root)
            assertTrue(entered.await(5,TimeUnit.SECONDS)); assertTrue(controller.state.sync.running)
            assertContains(threadName,"phototool-sync"); assertFalse(javax.swing.SwingUtilities.isEventDispatchThread() && threadName==Thread.currentThread().name)
            release.countDown(); controller.awaitBackgroundWork(); assertEquals(root.toRealPath().toString(),controller.state.library)
        } finally { release.countDown(); controller.close() }
    }

    @Test fun attestedReadRejectsAbaAndFailedVerifiedCopyRemovesDestination() {
        fun exercise(copy:Boolean) {
            val root=createTempDirectory(); val authority=root.resolve("A.xmp"); val original=baseXmp().toByteArray(); authority.writeBytes(original)
            val replacement=root.resolve("replacement.xmp"); replacement.writeBytes(baseXmp().replace("/>"," poisoned=\"yes\"/>").toByteArray())
            val originalAway=root.resolve("original-away.xmp"); val replacementAway=root.resolve("replacement-away.xmp")
            var exchanged=false; var restored=false
            val destination=createTempDirectory().resolve("secure-copy.xmp")
            SecureLibraryBoundary(
                root,false,
                readOpenedObserver={ if (exchanged && !restored) { restored=true; Files.move(authority,replacementAway); Files.move(originalAway,authority) } },
                readOpeningObserver={ if (!exchanged) { exchanged=true; Files.move(authority,originalAway); Files.move(replacement,authority) } },
            ).use { boundary ->
                if (copy) {
                    val expected=boundary.readExpectation("A.xmp")!!
                    assertFails { boundary.copyVerifiedTo("A.xmp",1024*1024,expected,destination) }
                    assertFalse(destination.exists(),"failed verified copy must remove its outside-library temporary")
                } else assertFails { boundary.read("A.xmp",1024*1024) }
            }
            assertTrue(exchanged && restored); assertContentEquals(original,authority.readBytes())
        }
        exercise(false); exercise(true)
    }

    @Test fun crossFormatTransferRevalidatesSourceImmediatelyBeforeFinalMove() = runBlocking {
        val root=createTempDirectory(); val raw=root.resolve("A.RAF"); raw.writeBytes(byteArrayOf(1)); val fp2=root.resolve("A.FP2")
        fp2.writeBytes(Paths.get("..","fixtures","xpro2-editable.FP2").readBytes()); val xmpPath=root.resolve("A.xmp"); xmpPath.writeText(baseXmp()); val originalXmp=xmpPath.readBytes()
        val photo=Photo("a","","A","A.xmp",rawPath="A.RAF",sourceIdentity=identity(root,raw))
        var sourceChanged=false
        XmpSidecarStore(root,true,authorityDisplaced={
            sourceChanged=true
            fp2.writeText(fp2.readText().replace("<ExposureBias>0</ExposureBias>","<ExposureBias>P0P33</ExposureBias>"))
        }).use { xmp ->
            FujiProfileStore(root,true).use { fuji ->
                val actions=DesktopAuxiliaryActions(xmp,fuji).callbacks()
                assertFails { actions.transferFujiToXmp(photo) }
            }
        }
        assertTrue(sourceChanged); assertContentEquals(originalXmp,xmpPath.readBytes(),"target publication must abort and restore its original bytes")
    }

    @Test fun xmpAndFujiStoresShareTheTransferLockForTheWholePublication() {
        val root=createTempDirectory(); val raw=root.resolve("L.RAF"); raw.writeBytes(byteArrayOf(1)); root.resolve("L.FP2").writeBytes(Paths.get("..","fixtures","xpro2-editable.FP2").readBytes()); root.resolve("L.xmp").writeText(baseXmp())
        val photo=Photo("lock","","L","L.xmp",rawPath="L.RAF",sourceIdentity=identity(root,raw)); val displaced=CountDownLatch(1); val release=CountDownLatch(1)
        val xmp=XmpSidecarStore(root,true,authorityDisplaced={ displaced.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)) }); val fuji=FujiProfileStore(root,true)
        val executor=Executors.newFixedThreadPool(2)
        try {
            val actions=DesktopAuxiliaryActions(xmp,fuji).callbacks()
            val transfer=executor.submit { runBlocking { actions.transferFujiToXmp(photo) } }
            assertTrue(displaced.await(5,TimeUnit.SECONDS))
            val sourceMutation=executor.submit<FujiRecipe> { fuji.mutate(photo,mapOf("ExposureBias" to "P0P33")) }
            Thread.sleep(150); assertFalse(sourceMutation.isDone,"FP2 mutation must wait while the XMP transfer target is displaced")
            release.countDown(); transfer.get(5,TimeUnit.SECONDS); assertEquals("P0P33",sourceMutation.get(5,TimeUnit.SECONDS).exposureBias)
        } finally {
            release.countDown(); executor.shutdown(); assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)); fuji.close(); xmp.close()
        }
    }

    @Test fun syncSummaryDirectoryFsyncFailureBecomesVisibleWarning() {
        val directory=createTempDirectory(); val store=SyncSummaryStore(directory) { error("simulated directory fsync failure") }
        val summary=SyncSummary("success",1,0,"",snapshotGeneration="12345678-1234-1234-1234-123456789abc",snapshotFingerprint="a".repeat(64))
        val warning=summaryWarning { store.save(summary) }
        assertNotNull(warning); assertContains(warning,"resumo da sincronização")
        assertTrue(directory.resolve("sync-summary.json").exists(),"the already-published summary remains detectable even though durability was not confirmed")
    }
}

private inline fun <T : java.awt.Graphics2D, R> T.use(block:(T)->R):R=try{block(this)}finally{dispose()}
