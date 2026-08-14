package br.com.lincon.phototool.desktop

import br.com.lincon.phototool.domain.*
import br.com.lincon.phototool.state.SyncStatus
import java.nio.file.*
import java.util.concurrent.atomic.AtomicBoolean
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
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

    @Test fun malformedFailsClosed() {
        val root=createTempDirectory(); root.resolve("A.RAF").writeBytes(byteArrayOf(9)); val xmp=root.resolve("A.xmp"); xmp.writeText("<bad")
        val photo=Photo("a","","A","A.xmp",rawPath="A.RAF",sourceIdentity=identity(root,root.resolve("A.RAF"))); val before=xmp.readBytes()
        assertFailsWith<XmpException>{ XmpSidecarStore(root,true).mutate(photo,EditorialState(rating=3)) }
        assertContentEquals(before,xmp.readBytes())
    }

    @Test fun scannerIgnoresHiddenAndSymlinkAndMarksAmbiguity() {
        val root=createTempDirectory(); root.resolve("A.CR2").writeBytes(byteArrayOf(1)); root.resolve("a.DNG").writeBytes(byteArrayOf(2)); root.resolve(".hidden").createDirectory().resolve("B.JPG").writeBytes(byteArrayOf(3))
        val result=LibraryScanner(object:MediaObservationAdapter{override fun observe(path:Path,kind:MediaKind)=ObservedMetadata(width=3,height=2)}).scan(root,AtomicBoolean()){ _:SyncStatus-> }
        assertEquals(2,result.photos.size); assertTrue(result.photos.all { !it.writable })
    }

    @Test fun cachePublishesCompleteSnapshotAtomically() {
        val root=createTempDirectory(); val cacheDir=createTempDirectory(); val cache=PhotoCache(cacheDir,root)
        assertTrue(cache.load().isEmpty()); root.resolve("A.JPG").writeBytes(byteArrayOf(1)); val photo=scanPhoto(root).copy(editorial=EditorialState(rating=4)); cache.publish(listOf(photo))
        assertEquals(4,cache.load().single().editorial.rating); assertTrue(cacheDir.resolve("phototool.sqlite3").exists())
    }

    @Test fun fujiFp2EditableFp3ReadOnlyAndOpaqueBytesPreserved() {
        val fixture=Paths.get("..","fixtures","xpro2-editable.FP2").readBytes(); val document=FujiProfileDocument(fixture,"FP2"); assertTrue(document.recipe().editable)
        val updated=document.update(mapOf("ExposureBias" to "P1P00")); assertContains(updated.toString(Charsets.UTF_8),"<ExposureBias>P1P00</ExposureBias>"); assertContains(updated.toString(Charsets.UTF_8),"<FixtureOpaque>preserve-me</FixtureOpaque>")
        val fp3=Paths.get("..","fixtures","xpro2-rendered.FP3").readBytes(); assertFalse(FujiProfileDocument(fp3,"FP3").recipe().editable); assertFails { FujiProfileDocument(fp3,"FP3").update(mapOf("Color" to "1")) }
    }

    private fun scanPhoto(root:Path):Photo = LibraryScanner(object:MediaObservationAdapter { override fun observe(path:Path,kind:MediaKind)=ObservedMetadata(status=MetadataStatus.MISSING) }).scan(root,AtomicBoolean()){ }.photos.single()

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
        ImageIO.read(thumbnail.toFile()).also { assertTrue(maxOf(it.width,it.height)<=512); assertTrue(it.width.toLong()*it.height<=20_000_000) }
        jpeg.writeBytes(jpeg.readBytes()+byteArrayOf(0))
        assertFailsWith<PreviewException> { PreviewStore(root,cache).thumbnail(photo,512) }
    }

    @Test fun cacheRoundTripsExpandedMetadataIdentityAndNormalizedKeywords() {
        val root=createTempDirectory(); val media=root.resolve("A.JPG"); media.writeBytes(byteArrayOf(1)); val cache=PhotoCache(createTempDirectory(),root)
        val id=identity(root,media)
        val photo=scanPhoto(root).copy(
            metadata=ObservedMetadata("2025-01-01T10:00:00Z","Camera","Make","Model","Lens",35.0,2.8,.01,200,-23.0,-46.0,6000,4000,MetadataStatus.AVAILABLE),
            editorial=EditorialState(Flag.PICK,4,ColorLabel.RED,listOf("São Paulo","Brasil|Sudeste")))
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
        assertFailsWith<XmpException> { XmpSidecarStore(root,true).read(photo) }
    }

    @Test fun syncSummaryIsBoundedAtomicAndRejectsLinks() {
        val cache=createTempDirectory(); val store=SyncSummaryStore(cache); val summary=SyncSummary("success",12,1,"done")
        store.save(summary); assertEquals(summary,store.load())
        val target=cache.resolve("sync-summary.json"); target.deleteExisting(); Files.createSymbolicLink(target,cache.resolve("missing"))
        assertFails { store.save(summary) }
    }

    @Test fun xmpChangedWritePreservesUtf16BomAndEncoding() {
        val root=createTempDirectory(); val raw=root.resolve("U.CR2"); raw.writeBytes(byteArrayOf(1)); val sidecar=root.resolve("U.xmp")
        val text="<?xml version=\"1.0\" encoding=\"UTF-16\"?>\r\n<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmlns:xmpDM=\"http://ns.adobe.com/xmp/1.0/DynamicMedia/\"/></rdf:RDF></x:xmpmeta>"
        sidecar.writeBytes(byteArrayOf(0xFF.toByte(),0xFE.toByte())+text.toByteArray(Charsets.UTF_16LE))
        val photo=Photo("u","","U","U.xmp",rawPath="U.CR2",sourceIdentity=identity(root,raw)); XmpSidecarStore(root,true).mutate(photo,EditorialState(rating=2))
        val bytes=sidecar.readBytes(); assertContentEquals(byteArrayOf(0xFF.toByte(),0xFE.toByte()),bytes.take(2).toByteArray()); val written=bytes.drop(2).toByteArray().toString(Charsets.UTF_16LE)
        assertContains(written,"encoding=\"UTF-16\""); assertContains(written,"\r\n"); assertEquals(2,XmpSidecarStore(root,false).read(photo).rating)
    }

    @Test fun immutableFlightRejectsTwoLibrarySyncRace() {
        val first=createTempDirectory(); val second=createTempDirectory()
        assertTrue(immutableFlightMayPublish(first,first)); assertFalse(immutableFlightMayPublish(first,second))
    }

    @Test fun uppercaseXmpAuthorityIsPreservedWithoutDuplicateCreation() {
        val root=createTempDirectory(); val raw=root.resolve("IMG.CR2"); raw.writeBytes(byteArrayOf(1)); root.resolve("IMG.XMP").writeText(baseXmp())
        val photo=LibraryScanner(object:MediaObservationAdapter { override fun observe(path:Path,kind:MediaKind)=ObservedMetadata() }).scan(root,AtomicBoolean()){ }.photos.single()
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

    @Test fun thumbnailCacheHitDoesNotMutatePathMetadata() {
        val root=createTempDirectory(); val cache=createTempDirectory(); val jpeg=root.resolve("H.JPG"); val image=BufferedImage(320,200,BufferedImage.TYPE_INT_RGB); ImageIO.write(image,"jpeg",jpeg.toFile())
        val photo=LibraryScanner().scan(root,AtomicBoolean()){ }.photos.single(); val store=PreviewStore(root,cache); val target=store.thumbnail(photo,512)
        val pinned=java.nio.file.attribute.FileTime.fromMillis(1_234_567_000L); Files.setLastModifiedTime(target,pinned)
        val before=Files.readAttributes(target,java.nio.file.attribute.BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        assertEquals(target,store.thumbnail(photo,512))
        val after=Files.readAttributes(target,java.nio.file.attribute.BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        assertEquals(before.lastModifiedTime(),after.lastModifiedTime()); assertEquals(before.size(),after.size()); assertEquals(before.fileKey(),after.fileKey())
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
}

private inline fun <T : java.awt.Graphics2D, R> T.use(block:(T)->R):R=try{block(this)}finally{dispose()}
