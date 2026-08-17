package br.com.lincon.phototool.domain

private val rawExtensions = setOf("cr2", "cr3", "dng", "raf")
private val jpegExtensions = setOf("jpg", "jpeg")

data class GregorianDate(val year: Int, val month: Int, val day: Int) : Comparable<GregorianDate> {
    override fun compareTo(other: GregorianDate): Int = compareValuesBy(this, other, GregorianDate::year, GregorianDate::month, GregorianDate::day)
    override fun toString(): String = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}

fun parseGregorianDate(value: String): GregorianDate? {
    if (!value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return null
    val year = value.substring(0, 4).toInt()
    val month = value.substring(5, 7).toInt()
    val day = value.substring(8, 10).toInt()
    if (year !in 1..9999 || month !in 1..12) return null
    val lastDay = monthDateBounds(year, month).second.takeLast(2).toInt()
    return day.takeIf { it in 1..lastDay }?.let { GregorianDate(year, month, it) }
}

private val canonicalCapture = Regex("""(\d{4}-\d{2}-\d{2})(?:T(\d{2}):(\d{2}):(\d{2})(\.\d{1,9})?(Z|[+-]\d{2}:\d{2})?)?""")

/**
 * Validates the complete persisted capture value before exposing its civil date.
 * Accepted values are ISO civil dates, or ISO local date-times with optional
 * fractional seconds and an optional explicit offset. No timezone is invented.
 */
fun captureGregorianDate(capturedAt: String?): GregorianDate? {
    val match = capturedAt?.let(canonicalCapture::matchEntire) ?: return null
    val date = parseGregorianDate(match.groupValues[1]) ?: return null
    if (match.groupValues[2].isEmpty()) return date
    val hour = match.groupValues[2].toInt()
    val minute = match.groupValues[3].toInt()
    val second = match.groupValues[4].toInt()
    if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
    val offset = match.groupValues[6]
    if (offset.isNotEmpty() && offset != "Z") {
        val offsetHours = offset.substring(1, 3).toInt()
        val offsetMinutes = offset.substring(4, 6).toInt()
        if (offsetHours !in 0..14 || offsetMinutes !in 0..59 || (offsetHours == 14 && offsetMinutes != 0)) return null
    }
    return date
}

fun queryDateError(fromDate: String?, toDate: String?): String? {
    val from = fromDate?.let(::parseGregorianDate)
    val to = toDate?.let(::parseGregorianDate)
    if (fromDate != null && from == null) return "Data inicial inválida. Use AAAA-MM-DD."
    if (toDate != null && to == null) return "Data final inválida. Use AAAA-MM-DD."
    return if (from != null && to != null && from > to) "A data inicial não pode ser posterior à data final." else null
}

fun classifyMedia(filename: String): MediaKind? = when (filename.substringAfterLast('.', "").lowercase()) {
    in rawExtensions -> MediaKind.RAW
    in jpegExtensions -> MediaKind.JPEG
    else -> null
}

fun pairCandidates(
    candidates: List<MediaCandidate>,
    ambiguousSidecarStems: Set<Pair<String, String>> = emptySet(),
    canonicalSidecars: Map<Pair<String, String>, List<String>> = emptyMap(),
): List<PairingResult> = candidates.groupBy { it.directory to caseFoldText(it.stem) }.flatMap { (key, matches) ->
    val raws = matches.filter { it.kind == MediaKind.RAW }.sortedBy { caseFoldText(it.filename) }
    val jpegs = matches.filter { it.kind == MediaKind.JPEG }.sortedBy { caseFoldText(it.filename) }
    val sidecars = canonicalSidecars[key].orEmpty().distinct()
    val issue = when {
        raws.size > 1 -> "Multiple exact-stem RAW files"
        jpegs.size > 1 -> "Multiple exact-stem JPEG files"
        sidecars.size > 1 || key in ambiguousSidecarStems -> "Ambiguous XMP sidecar authority"
        else -> null
    }
    val topologyAmbiguous = raws.size > 1 || jpegs.size > 1
    if (topologyAmbiguous) {
        (raws + jpegs).map { candidate -> PairingResult(
            folder = key.first,
            stem = candidate.stem,
            raw = candidate.filename.takeIf { candidate.kind == MediaKind.RAW },
            jpeg = candidate.filename.takeIf { candidate.kind == MediaKind.JPEG },
            xmp = sidecars.singleOrNull(),
            writable = false,
            issue = issue,
        ) }
    } else listOf(PairingResult(
        folder = key.first,
        stem = matches.first().stem,
        raw = raws.singleOrNull()?.filename,
        jpeg = jpegs.singleOrNull()?.filename,
        xmp = sidecars.singleOrNull(),
        writable = issue == null,
        issue = issue,
    ))
}.sortedWith(compareBy<PairingResult>({ caseFoldText(it.folder) }, { caseFoldText(it.stem) }, { caseFoldText(it.raw.orEmpty()) }, { caseFoldText(it.jpeg.orEmpty()) }))

data class SearchTerms(val included: List<String>, val excluded: List<String>)

private val searchTermPattern = Regex("""(-?)"([^"]+)"|(\S+)""")

/**
 * Parses space-separated search terms. A leading hyphen excludes a term;
 * quoted phrases are kept together, including the `-"phrase"` form.
 */
fun parseSearchTerms(input: String): SearchTerms {
    val included = mutableListOf<String>()
    val excluded = mutableListOf<String>()
    searchTermPattern.findAll(input.trim()).forEach { match ->
        val quoted = match.groups[2]?.value
        val raw = quoted ?: match.groups[3]?.value.orEmpty()
        val negative = match.groups[1]?.value == "-" || (quoted == null && raw.startsWith('-') && raw.length > 1)
        val value = if (negative && quoted == null) raw.drop(1) else raw
        val folded = caseFoldText(value.trim())
        if (folded.isEmpty()) return@forEach
        if (negative) excluded += folded else included += folded
    }
    return SearchTerms(included.distinct(), excluded.distinct())
}

fun filterAndOrder(photos: List<Photo>, query: Query): List<Photo> {
    if (queryDateError(query.fromDate, query.toDate) != null) return emptyList()
    val search = parseSearchTerms(query.search)
    val keywordResult = query.keyword?.let { runCatching { keywordCasefold(it) } }
    if (keywordResult?.isFailure == true) return emptyList()
    val keyword = keywordResult?.getOrNull()
    return photos.asSequence().filter { photo ->
        val metadata = photo.metadata
        val searchable = listOf(photo.displayName, photo.folder, metadata.cameraDisplay.orEmpty(), metadata.cameraMake.orEmpty(), metadata.cameraModel.orEmpty(), metadata.lens.orEmpty())
            .plus(photo.editorial.keywords).map(::caseFoldText)
        search.included.all { term -> searchable.any { term in it } } &&
            search.excluded.none { term -> searchable.any { term in it } } &&
            (keyword == null || photo.editorial.keywords.any { keywordCasefold(it) == keyword }) &&
            (query.fromDate == null || captureGregorianDate(metadata.capturedAt)?.let { it >= parseGregorianDate(query.fromDate)!! } == true) &&
            (query.toDate == null || captureGregorianDate(metadata.capturedAt)?.let { it <= parseGregorianDate(query.toDate)!! } == true) &&
            (query.flag == null || photo.editorial.flag == query.flag) &&
            (query.camera == null || metadata.cameraDisplay == query.camera) &&
            (query.lens == null || metadata.lens == query.lens) &&
            (query.folder == null || photo.folder == query.folder || photo.folder.startsWith(query.folder.trimEnd('/') + "/")) &&
            photo.editorial.rating >= query.minimumStars &&
            when (query.gps) { GpsFilter.ANY -> true; GpsFilter.PRESENT -> metadata.hasGps; GpsFilter.MISSING -> !metadata.hasGps }
    }.sortedWith(when (query.sort) {
        PhotoSort.CAPTURE_TIME -> compareByDescending<Photo> { it.metadata.capturedAt != null }
            .thenByDescending { it.metadata.capturedAt }
            .thenBy { caseFoldText(it.folder) }.thenBy { caseFoldText(it.stem) }.thenBy { it.id }
        PhotoSort.RECENTLY_ADDED -> compareByDescending<Photo> { it.sourceIdentity?.modifiedMillis ?: Long.MIN_VALUE }
            .thenBy { caseFoldText(it.folder) }.thenBy { caseFoldText(it.stem) }.thenBy { it.id }
    }).toList()
}

fun monthDateBounds(year: Int, month: Int): Pair<String, String> {
    require(year in 1..9999) { "Year is out of range" }
    require(month in 1..12) { "Month is out of range" }
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val lastDay = when (month) {
        2 -> if (leap) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    val prefix = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}"
    return "$prefix-01" to "$prefix-${lastDay.toString().padStart(2, '0')}"
}

fun normalizeKeyword(input: String): String {
    require(input.length <= 160) { "Keyword is too long" }
    require(input.none { it.code < 32 || it == ',' || it == ';' }) { "Keyword contains unsupported characters" }
    val normalized = normalizeNfc(input.trim()).split('|').joinToString("|") { level ->
        level.trim().split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ").also { require(it.isNotEmpty()) { "Keyword hierarchy contains an empty level" } }
    }
    require(normalized.isNotEmpty()) { "Keyword cannot be empty" }
    return normalized
}

fun normalizeFlatKeyword(input: String): String = normalizeKeyword(input).also { require('|' !in it) { "New keywords must be flat" } }

/**
 * Locale-independent Unicode caseless key for filenames and search text.
 *
 * Kotlin's [String.lowercase] performs Unicode lowercasing, not case folding:
 * it leaves sharp-s and compatibility ligatures expanded differently and may
 * produce the positional final sigma. These explicit full-fold expansions are
 * the ones relevant to user-visible filenames/keywords and match Python's
 * `str.casefold()` for the covered characters.
 */
fun caseFoldText(input: String): String = buildString(input.length) {
    input.lowercase().forEach { character ->
        append(
            when (character) {
                'ß' -> "ss"
                'ς' -> "σ"
                '\uFB00' -> "ff"
                '\uFB01' -> "fi"
                '\uFB02' -> "fl"
                '\uFB03' -> "ffi"
                '\uFB04' -> "ffl"
                '\uFB05', '\uFB06' -> "st"
                else -> character.toString()
            },
        )
    }
}

fun keywordCasefold(input: String): String = caseFoldText(normalizeKeyword(input))

expect fun normalizeNfc(input: String): String
