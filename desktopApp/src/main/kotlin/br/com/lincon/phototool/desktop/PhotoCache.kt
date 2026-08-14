package br.com.lincon.phototool.desktop

import br.com.lincon.phototool.domain.*
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.sql.Connection
import java.sql.DriverManager

private const val CACHE_SCHEMA = "2"
private const val MAX_CACHE_PHOTOS = 100_000
private const val MAX_CACHE_KEYWORDS = 1_000_000
private const val MAX_CACHED_MEDIA_BYTES = 1024L * 1024 * 1024

class PhotoCache(private val cacheDir: Path, private val library: Path) {
    private val realLibrary = library.toRealPath()
    private val live: Path
    private val libraryIdentity: String

    init {
        val projected = cacheDir.toAbsolutePath().normalize()
        require(!projected.startsWith(realLibrary) && !realLibrary.startsWith(projected)) { "Cache must be outside the library" }
        createSecureDirectories(projected)
        require(!Files.isSymbolicLink(projected)) { "Cache directory may not be a link" }
        val realCache = projected.toRealPath()
        require(!realCache.startsWith(realLibrary) && !realLibrary.startsWith(realCache)) { "Cache must be outside the library" }
        live = realCache.resolve("phototool.sqlite3")
        val attrs = Files.readAttributes(realLibrary, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        libraryIdentity = "${realLibrary}\u0000${attrs.fileKey()}"
    }

    fun load(): List<Photo> {
        if (sqliteAuxiliaries(live).any { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }) return emptyList()
        if (!safeRegular(live)) return emptyList()
        return runCatching { loadSnapshot(live) }.getOrElse { emptyList() }
    }

    private fun loadSnapshot(snapshot: Path): List<Photo> =
            connect(snapshot, readOnly = true).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA quick_check").use { check(it.next() && it.getString(1) == "ok") { "Invalid cache" } }
                    statement.executeQuery("PRAGMA foreign_key_check").use { check(!it.next()) { "Invalid cache relationships" } }
                    validateSchema(connection)
                    validateRowBounds(connection)
                    val meta = statement.executeQuery("SELECT key,value FROM meta").use { rows -> buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) } }
                    check(meta.keys == setOf("library", "schema", "state") && meta["library"] == libraryIdentity && meta["schema"] == CACHE_SCHEMA && meta["state"] == "complete") { "Wrong or incomplete cache" }
                    val keywords = connection.createStatement().use { keywordStatement ->
                        keywordStatement.executeQuery("SELECT photo_id,keyword,keyword_fold,ordinal FROM keywords ORDER BY photo_id,ordinal").use { rows ->
                            buildMap<String, MutableList<String>> { while (rows.next()) {
                                val photoId=rows.getString(1); val keyword=rows.getString(2); val values=getOrPut(photoId) { mutableListOf() }
                                check(rows.getInt(4) == values.size && normalizeKeyword(keyword) == keyword && rows.getString(3) == keywordCasefold(keyword))
                                values.add(keyword)
                            } }
                        }
                    }
                    statement.executeQuery("SELECT * FROM photos ORDER BY id").use { rows -> buildList { while (rows.next()) add(validateCachedPhoto(rowToPhoto(rows, keywords[rows.getString("id")].orEmpty()))) } }
                }
            }

    fun publish(photos: List<Photo>) {
        require(photos.size <= MAX_CACHE_PHOTOS && photos.sumOf { it.editorial.keywords.size.toLong() } <= MAX_CACHE_KEYWORDS && photos.map { it.id }.distinct().size == photos.size)
        rejectUnsafeLive()
        val staging = Files.createTempFile(live.parent, ".phototool-", ".staging")
        try {
            connect(staging).use { connection ->
                connection.autoCommit = false
                connection.createStatement().use {
                    it.execute("PRAGMA journal_mode=DELETE")
                    it.executeUpdate("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
                    it.executeUpdate("""CREATE TABLE photos(
                        id TEXT PRIMARY KEY,folder TEXT NOT NULL,stem TEXT NOT NULL,authority TEXT NOT NULL,raw TEXT,jpeg TEXT,preview TEXT,
                        source_key TEXT,source_size INTEGER,source_mtime INTEGER,preview_key TEXT,preview_size INTEGER,preview_mtime INTEGER,
                        captured TEXT,camera TEXT,camera_make TEXT,camera_model TEXT,lens TEXT,focal REAL,aperture REAL,exposure REAL,iso INTEGER,
                        width INTEGER,height INTEGER,lat REAL,lon REAL,metadata_status TEXT NOT NULL,metadata_error TEXT,
                        flag TEXT NOT NULL,rating INTEGER NOT NULL,label TEXT,writable INTEGER NOT NULL,issue TEXT)""")
                    it.executeUpdate("CREATE TABLE keywords(photo_id TEXT NOT NULL,keyword TEXT NOT NULL,keyword_fold TEXT NOT NULL,ordinal INTEGER NOT NULL,PRIMARY KEY(photo_id,keyword_fold),FOREIGN KEY(photo_id) REFERENCES photos(id) ON DELETE CASCADE)")
                    it.executeUpdate("CREATE INDEX photos_capture ON photos(captured DESC,id)")
                    it.executeUpdate("CREATE INDEX photos_facets ON photos(camera,lens,flag,rating)")
                    it.executeUpdate("CREATE INDEX keywords_exact ON keywords(keyword_fold)")
                }
                connection.prepareStatement("INSERT INTO meta VALUES(?,?)").use { insert ->
                    mapOf("library" to libraryIdentity, "schema" to CACHE_SCHEMA, "state" to "complete").forEach { (key, value) -> insert.setString(1, key); insert.setString(2, value); insert.addBatch() }
                    insert.executeBatch()
                }
                connection.prepareStatement("INSERT INTO photos VALUES(${List(33) { "?" }.joinToString()})").use { insert ->
                    photos.forEach { photo -> bindPhoto(insert, photo); insert.addBatch() }
                    insert.executeBatch()
                }
                connection.prepareStatement("INSERT INTO keywords VALUES(?,?,?,?)").use { insert ->
                    photos.forEach { photo -> photo.editorial.keywords.forEachIndexed { ordinal, keyword ->
                        insert.setString(1, photo.id); insert.setString(2, keyword); insert.setString(3, keywordCasefold(keyword)); insert.setInt(4, ordinal); insert.addBatch()
                    } }
                    insert.executeBatch()
                }
                connection.commit()
                connection.createStatement().use { statement -> statement.executeQuery("PRAGMA quick_check").use { check(it.next() && it.getString(1) == "ok") } }
            }
            forceFile(staging)
            check(loadSnapshot(staging).size == photos.size) { "Staged snapshot failed strict validation" }
            rejectUnsafeLive()
            try { Files.move(staging, live, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (error: AtomicMoveNotSupportedException) { throw IllegalStateException("Atomic cache publication is unavailable", error) }
            forceDirectory(live.parent)
        } finally {
            Files.deleteIfExists(staging)
            sqliteAuxiliaries(staging).forEach { Files.deleteIfExists(it) }
        }
    }

    fun updateEditorial(id: String, state: EditorialState) {
        rejectUnsafeLive()
        if (!safeRegular(live)) return
        connect(live).use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("UPDATE photos SET flag=?,rating=?,label=? WHERE id=?").use {
                it.setString(1, state.flag.name); it.setInt(2, state.rating); it.setString(3, state.label?.name); it.setString(4, id)
                check(it.executeUpdate() == 1) { "Photo is absent from cache" }
            }
            connection.prepareStatement("DELETE FROM keywords WHERE photo_id=?").use { it.setString(1, id); it.executeUpdate() }
            connection.prepareStatement("INSERT INTO keywords VALUES(?,?,?,?)").use { insert -> state.keywords.forEachIndexed { ordinal, keyword ->
                insert.setString(1, id); insert.setString(2, keyword); insert.setString(3, keywordCasefold(keyword)); insert.setInt(4, ordinal); insert.addBatch()
            }; insert.executeBatch() }
            connection.commit()
        }
        forceFile(live)
    }

    private fun bindPhoto(insert: java.sql.PreparedStatement, photo: Photo) {
        val m = photo.metadata
        val values = listOf<Any?>(photo.id, photo.folder, photo.stem, photo.authorityPath, photo.rawPath, photo.jpegPath, photo.previewPath,
            photo.sourceIdentity?.fileKey, photo.sourceIdentity?.size, photo.sourceIdentity?.modifiedMillis,
            photo.previewIdentity?.fileKey, photo.previewIdentity?.size, photo.previewIdentity?.modifiedMillis,
            m.capturedAt, m.cameraDisplay, m.cameraMake, m.cameraModel, m.lens, m.focalLength, m.aperture, m.exposureSeconds, m.iso,
            m.width, m.height, m.latitude, m.longitude, m.status.name, m.errorCode,
            photo.editorial.flag.name, photo.editorial.rating, photo.editorial.label?.name, if (photo.writable) 1 else 0, photo.issue)
        check(values.size == 33)
        values.forEachIndexed { index, value -> insert.setObject(index + 1, value) }
        // The schema currently has 33 columns. Keep this assertion adjacent to binding.
    }

    private fun rowToPhoto(r: java.sql.ResultSet, keywords: List<String>) = Photo(
        id = r.getString("id"), folder = r.getString("folder"), stem = r.getString("stem"), authorityPath = r.getString("authority"),
        rawPath = r.getString("raw"), jpegPath = r.getString("jpeg"), previewPath = r.getString("preview"),
        sourceIdentity = identity(r, "source", r.getString("raw") ?: r.getString("jpeg")),
        previewIdentity = identity(r, "preview", r.getString("preview")),
        metadata = ObservedMetadata(
            capturedAt = r.getString("captured"), camera = r.getString("camera"), cameraMake = r.getString("camera_make"), cameraModel = r.getString("camera_model"), lens = r.getString("lens"),
            focalLength = r.number("focal"), aperture = r.number("aperture"), exposureSeconds = r.number("exposure"), iso = r.intOrNull("iso"),
            width = r.intOrNull("width"), height = r.intOrNull("height"), latitude = r.number("lat"), longitude = r.number("lon"),
            status = MetadataStatus.valueOf(r.getString("metadata_status")), errorCode = r.getString("metadata_error"),
        ),
        editorial = EditorialState(Flag.valueOf(r.getString("flag")), r.getInt("rating"), r.getString("label")?.let(ColorLabel::valueOf), keywords),
        writable = r.getInt("writable") == 1, issue = r.getString("issue"),
    )

    private fun identity(r: java.sql.ResultSet, prefix: String, path: String?): MediaIdentity? {
        val key = r.getString("${prefix}_key") ?: return null
        return MediaIdentity(path ?: return null, key, r.getLong("${prefix}_size"), r.getLong("${prefix}_mtime"))
    }

    private fun java.sql.ResultSet.number(column: String): Double? = getObject(column)?.let { (it as Number).toDouble() }
    private fun java.sql.ResultSet.intOrNull(column: String): Int? = getObject(column)?.let { (it as Number).toInt() }

    private fun validateSchema(connection: Connection) {
        data class Column(val name: String, val type: String, val notNull: Boolean = false, val primaryKey: Int = 0)
        fun text(name: String, notNull: Boolean = false, primaryKey: Int = 0) = Column(name, "TEXT", notNull, primaryKey)
        fun integer(name: String, notNull: Boolean = false) = Column(name, "INTEGER", notNull)
        fun real(name: String) = Column(name, "REAL")
        val expected = mapOf(
            "meta" to listOf(text("key", primaryKey = 1), text("value", notNull = true)),
            "photos" to listOf(
                text("id", primaryKey = 1), text("folder", true), text("stem", true), text("authority", true), text("raw"), text("jpeg"), text("preview"),
                text("source_key"), integer("source_size"), integer("source_mtime"), text("preview_key"), integer("preview_size"), integer("preview_mtime"),
                text("captured"), text("camera"), text("camera_make"), text("camera_model"), text("lens"), real("focal"), real("aperture"), real("exposure"), integer("iso"),
                integer("width"), integer("height"), real("lat"), real("lon"), text("metadata_status", true), text("metadata_error"),
                text("flag", true), integer("rating", true), text("label"), integer("writable", true), text("issue"),
            ),
            "keywords" to listOf(text("photo_id", true, 1), text("keyword", true), text("keyword_fold", true, 2), integer("ordinal", true)),
        )
        val tables = connection.createStatement().use { statement -> statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } } }
        check(tables == expected.keys)
        expected.forEach { (table, columns) ->
            val actual = connection.createStatement().use { statement -> statement.executeQuery("PRAGMA table_info($table)").use { rows -> buildList {
                while (rows.next()) {
                    check(rows.getInt("cid") == size && rows.getString("dflt_value") == null)
                    add(Column(rows.getString("name"), rows.getString("type").uppercase(), rows.getInt("notnull") == 1, rows.getInt("pk")))
                }
            } } }
            check(actual == columns)
        }

        val foreignKeys = connection.createStatement().use { statement -> statement.executeQuery("PRAGMA foreign_key_list(keywords)").use { rows -> buildList {
            while (rows.next()) add(listOf(rows.getString("table"), rows.getString("from"), rows.getString("to"), rows.getString("on_update"), rows.getString("on_delete"), rows.getString("match")))
        } } }
        check(foreignKeys == listOf(listOf("photos", "photo_id", "id", "NO ACTION", "CASCADE", "NONE")))
        listOf("meta", "photos").forEach { table ->
            connection.createStatement().use { statement -> statement.executeQuery("PRAGMA foreign_key_list($table)").use { check(!it.next()) } }
        }

        val expectedIndexes = mapOf(
            "photos_capture" to ("photos" to listOf("captured" to true, "id" to false)),
            "photos_facets" to ("photos" to listOf("camera" to false, "lens" to false, "flag" to false, "rating" to false)),
            "keywords_exact" to ("keywords" to listOf("keyword_fold" to false)),
        )
        val userIndexes = connection.createStatement().use { statement -> statement.executeQuery("SELECT name,tbl_name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_autoindex_%'").use { rows -> buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) } } }
        check(userIndexes == expectedIndexes.mapValues { it.value.first })
        expectedIndexes.forEach { (name, definition) ->
            val (table, columns) = definition
            val listed = connection.createStatement().use { statement -> statement.executeQuery("PRAGMA index_list($table)").use { rows -> buildList {
                while (rows.next()) if (rows.getString("name") == name) add(listOf(rows.getInt("unique"), rows.getString("origin"), rows.getInt("partial")))
            } } }
            check(listed == listOf(listOf(0, "c", 0)))
            val actual = connection.createStatement().use { statement -> statement.executeQuery("PRAGMA index_xinfo($name)").use { rows -> buildList {
                while (rows.next()) if (rows.getInt("key") == 1) {
                    check(rows.getInt("seqno") == size && rows.getString("coll") == "BINARY")
                    add(rows.getString("name") to (rows.getInt("desc") == 1))
                }
            } } }
            check(actual == columns)
        }
    }

    private fun validateRowBounds(connection: Connection) {
        fun count(sql: String): Long = connection.createStatement().use { statement -> statement.executeQuery(sql).use { rows -> check(rows.next()); rows.getLong(1) } }
        check(count("SELECT COUNT(*) FROM photos") in 0..MAX_CACHE_PHOTOS.toLong()) { "Cache exceeds supported photo bound" }
        check(count("SELECT COUNT(*) FROM keywords") in 0..MAX_CACHE_KEYWORDS.toLong()) { "Cache exceeds supported keyword bound" }
        check(count("SELECT COUNT(*) FROM keywords k LEFT JOIN photos p ON p.id=k.photo_id WHERE p.id IS NULL") == 0L) { "Cache contains orphan keywords" }
        check(count("SELECT COUNT(*) FROM (SELECT photo_id,COUNT(*) AS n,MIN(ordinal) AS first,MAX(ordinal) AS last,COUNT(DISTINCT ordinal) AS distinct_n FROM keywords GROUP BY photo_id HAVING n>256 OR first<>0 OR last<>n-1 OR distinct_n<>n)") == 0L) { "Cache keyword ordinals are invalid" }

        fun validText(column: String, maximum: Int, nullable: Boolean = true, allowNul: Boolean = false): String {
            val presence = if (nullable) "$column IS NULL OR " else ""
            val nul = if (allowNul) "" else " AND instr($column,char(0))=0"
            val length = if (allowNul) "length(CAST($column AS BLOB))" else "length($column)"
            return "($presence(typeof($column)='text' AND $length<=$maximum$nul))"
        }
        val photoText = listOf(
            validText("id", 24, false), validText("folder", 4096, false), validText("stem", 255, false), validText("authority", 4096, false),
            validText("raw", 4096), validText("jpeg", 4096), validText("preview", 4096), validText("source_key", 512), validText("preview_key", 512),
            validText("captured", 64), validText("camera", 512), validText("camera_make", 512), validText("camera_model", 512), validText("lens", 512),
            validText("metadata_status", 16, false), validText("metadata_error", 160), validText("flag", 16, false), validText("label", 16), validText("issue", 256),
        ).joinToString(" AND ")
        val numeric = """
            typeof(source_size)='integer' AND source_size BETWEEN 1 AND $MAX_CACHED_MEDIA_BYTES AND
            typeof(source_mtime)='integer' AND source_mtime>=0 AND
            typeof(preview_size)='integer' AND preview_size BETWEEN 1 AND $MAX_CACHED_MEDIA_BYTES AND
            typeof(preview_mtime)='integer' AND preview_mtime>=0 AND
            typeof(rating)='integer' AND rating BETWEEN 0 AND 5 AND typeof(writable)='integer' AND writable IN (0,1) AND
            (focal IS NULL OR (typeof(focal) IN ('real','integer') AND focal>0 AND focal<=100000)) AND
            (aperture IS NULL OR (typeof(aperture) IN ('real','integer') AND aperture>0 AND aperture<=1024)) AND
            (exposure IS NULL OR (typeof(exposure) IN ('real','integer') AND exposure>0 AND exposure<=86400)) AND
            (iso IS NULL OR (typeof(iso)='integer' AND iso BETWEEN 1 AND 10000000)) AND
            (width IS NULL OR (typeof(width)='integer' AND width BETWEEN 1 AND 100000)) AND
            (height IS NULL OR (typeof(height)='integer' AND height BETWEEN 1 AND 100000)) AND
            (width IS NULL OR height IS NULL OR width*height<=1000000000) AND
            ((lat IS NULL AND lon IS NULL) OR (typeof(lat) IN ('real','integer') AND typeof(lon) IN ('real','integer') AND lat BETWEEN -90 AND 90 AND lon BETWEEN -180 AND 180))
        """.trimIndent().replace('\n', ' ')
        val domains = "metadata_status IN ('MISSING','AVAILABLE','PARTIAL','ERROR') AND flag IN ('PICK','UNFLAGGED','REJECT') AND (label IS NULL OR label IN ('RED','YELLOW','GREEN'))"
        check(count("SELECT COUNT(*) FROM photos WHERE NOT ($photoText AND $numeric AND $domains)") == 0L) { "Cache photo field is outside its domain" }
        val keywordText = listOf(validText("photo_id", 24, false), validText("keyword", 160, false), validText("keyword_fold", 160, false)).joinToString(" AND ")
        check(count("SELECT COUNT(*) FROM keywords WHERE NOT ($keywordText AND typeof(ordinal)='integer' AND ordinal BETWEEN 0 AND 255)") == 0L) { "Cache keyword field is outside its domain" }
        check(count("SELECT COUNT(*) FROM meta WHERE NOT (${validText("key", 16, false)} AND ${validText("value", 8192, false, allowNul = true)})") == 0L) { "Cache metadata field is outside its domain" }
        check(count("SELECT COUNT(*) FROM meta") == 3L)
    }

    private fun validateCachedPhoto(photo: Photo): Photo {
        fun relative(value: String): Path {
            val path = Path.of(value)
            check(!path.isAbsolute && path.normalize() == path && path.none { it.toString() in setOf("", ".", "..") })
            return path
        }
        check(photo.id.matches(Regex("[0-9a-f]{24}")))
        check(photo.folder.length <= 4096 && photo.stem.isNotBlank() && photo.stem.length <= 255)
        val raw = photo.rawPath?.also { check(classifyMedia(relative(it).fileName.toString()) == MediaKind.RAW) }
        val jpeg = photo.jpegPath?.also { check(classifyMedia(relative(it).fileName.toString()) == MediaKind.JPEG) }
        check(raw != null || jpeg != null)
        val source = raw ?: jpeg!!
        val sourcePath = relative(source)
        val authority = relative(photo.authorityPath)
        check(authority.parent == sourcePath.parent && authority.fileName.toString().substringAfterLast('.', "").equals("xmp", true))
        check(authority.fileName.toString().substringBeforeLast('.').equals(sourcePath.fileName.toString().substringBeforeLast('.'), true))
        raw?.let { check(relative(it).parent == sourcePath.parent && relative(it).fileName.toString().substringBeforeLast('.').equals(photo.stem, true)) }
        jpeg?.let { check(relative(it).parent == sourcePath.parent && relative(it).fileName.toString().substringBeforeLast('.').equals(photo.stem, true)) }
        check((sourcePath.parent?.toString()?.replace('\\', '/') ?: "") == photo.folder)
        check(photo.previewPath == (jpeg ?: raw))
        val sourceIdentity = photo.sourceIdentity
        val previewIdentity = photo.previewIdentity
        check(sourceIdentity?.isComplete == true && sourceIdentity.path == source)
        check(previewIdentity?.isComplete == true && previewIdentity.path == photo.previewPath)
        validateIdentity(sourceIdentity)
        validateIdentity(previewIdentity)
        check(photo.id == stableId("$source\u0000${photo.authorityPath}"))
        check(photo.editorial.rating in 0..5 && photo.editorial.keywords.size <= 256)
        photo.editorial.keywords.forEach { check(normalizeKeyword(it) == it) }
        val metadataError = photo.metadata.errorCode
        val issue = photo.issue
        check(metadataError == null || (metadataError.length <= 160 && '/' !in metadataError && '\\' !in metadataError))
        check(issue == null || issue.length <= 256)
        val directory = realLibrary.resolve(photo.folder).normalize()
        check(directory.startsWith(realLibrary) && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory))
        val names = Files.list(directory).use { stream -> stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }.map { it.fileName.toString() }.toList() }
        val foldedStem = photo.stem.lowercase()
        val raws = names.filter { classifyMedia(it) == MediaKind.RAW && it.substringBeforeLast('.').lowercase() == foldedStem }.toSet()
        val jpegs = names.filter { classifyMedia(it) == MediaKind.JPEG && it.substringBeforeLast('.').lowercase() == foldedStem }.toSet()
        val indexedRaws = setOfNotNull(raw?.let { Path.of(it).fileName.toString() })
        val indexedJpegs = setOfNotNull(jpeg?.let { Path.of(it).fileName.toString() })
        val canonicals = names.filter { it.substringAfterLast('.', "").equals("xmp", true) && it.substringBeforeLast('.').lowercase() == foldedStem }
        val legacy = names.any { val inner = it.substringBeforeLast('.', ""); it.substringAfterLast('.', "").equals("xmp", true) && classifyMedia(inner) != null && inner.substringBeforeLast('.').lowercase() == foldedStem }
        val topologyWritable = raws == indexedRaws && jpegs == indexedJpegs && canonicals.size <= 1 && (canonicals.isEmpty() || canonicals.single() == authority.fileName.toString()) && !legacy
        check(!photo.writable || topologyWritable)
        return photo
    }

    private fun validateIdentity(identity: MediaIdentity) {
        val path = realLibrary.resolve(identity.path).normalize()
        check(path.startsWith(realLibrary) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val key = attrs.fileKey()?.toString() ?: throw IllegalStateException("Stable cache identity unavailable")
        check(key == identity.fileKey && attrs.size() == identity.size && attrs.lastModifiedTime().toMillis() == identity.modifiedMillis)
    }

    private fun stableId(value: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(value.lowercase().toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    private fun connect(path: Path, readOnly: Boolean = false): Connection {
        val url = if (readOnly) "jdbc:sqlite:file:${path.toAbsolutePath()}?mode=ro&nofollow=true" else "jdbc:sqlite:${path.toAbsolutePath()}"
        return DriverManager.getConnection(url).also { it.createStatement().use { statement -> statement.execute("PRAGMA foreign_keys=ON"); statement.execute("PRAGMA busy_timeout=5000") } }
    }

    private fun rejectUnsafeLive() {
        if (sqliteAuxiliaries(live).any { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }) error("Live SQLite auxiliary files are forbidden")
        if (Files.exists(live, LinkOption.NOFOLLOW_LINKS) && !safeRegular(live)) error("Cache snapshot is linked or not a regular file")
    }

    private fun sqliteAuxiliaries(path: Path) = listOf("-journal", "-wal", "-shm").map { Path.of(path.toString() + it) }
    private fun safeRegular(path: Path): Boolean = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && runCatching { (Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1 }.getOrDefault(false)
    private fun forceFile(path: Path) = FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    private fun forceDirectory(path: Path) { runCatching { FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) } } }

    private fun createSecureDirectories(path: Path) {
        var current = path.root ?: error("Cache path must be absolute")
        path.forEach { part ->
            current = current.resolve(part)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) require(Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(current)) { "Cache path contains a link" }
            else Files.createDirectory(current)
        }
    }
}
