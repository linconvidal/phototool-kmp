package br.com.lincon.phototool.domain

private val rawExtensions = setOf("cr2", "cr3", "dng", "raf")
private val jpegExtensions = setOf("jpg", "jpeg")

fun classifyMedia(filename: String): MediaKind? = when (filename.substringAfterLast('.', "").lowercase()) {
    in rawExtensions -> MediaKind.RAW
    in jpegExtensions -> MediaKind.JPEG
    else -> null
}

fun pairCandidates(
    candidates: List<MediaCandidate>,
    ambiguousSidecarStems: Set<Pair<String, String>> = emptySet(),
    canonicalSidecars: Map<Pair<String, String>, List<String>> = emptyMap(),
): List<PairingResult> = candidates.groupBy { it.directory to it.stem.lowercase() }.flatMap { (key, matches) ->
    val raws = matches.filter { it.kind == MediaKind.RAW }.sortedBy { it.filename.lowercase() }
    val jpegs = matches.filter { it.kind == MediaKind.JPEG }.sortedBy { it.filename.lowercase() }
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
}.sortedWith(compareBy<PairingResult>({ it.folder.lowercase() }, { it.stem.lowercase() }, { it.raw.orEmpty().lowercase() }, { it.jpeg.orEmpty().lowercase() }))

fun filterAndOrder(photos: List<Photo>, query: Query): List<Photo> {
    val needle = query.search.trim().lowercase()
    val keywordResult = query.keyword?.let { runCatching { keywordCasefold(it) } }
    if (keywordResult?.isFailure == true) return emptyList()
    val keyword = keywordResult?.getOrNull()
    return photos.asSequence().filter { photo ->
        val metadata = photo.metadata
        (needle.isEmpty() || listOf(photo.displayName, metadata.cameraDisplay.orEmpty(), metadata.cameraMake.orEmpty(), metadata.cameraModel.orEmpty(), metadata.lens.orEmpty()).any { needle in it.lowercase() }) &&
            (keyword == null || photo.editorial.keywords.any { keywordCasefold(it) == keyword }) &&
            (query.fromDate == null || metadata.capturedAt?.take(10)?.let { it >= query.fromDate } == true) &&
            (query.toDate == null || metadata.capturedAt?.take(10)?.let { it <= query.toDate } == true) &&
            (query.flag == null || photo.editorial.flag == query.flag) &&
            (query.camera == null || metadata.camera == query.camera) &&
            (query.lens == null || metadata.lens == query.lens) &&
            photo.editorial.rating >= query.minimumStars &&
            when (query.gps) { GpsFilter.ANY -> true; GpsFilter.PRESENT -> metadata.hasGps; GpsFilter.MISSING -> !metadata.hasGps }
    }.sortedWith(compareByDescending<Photo> { it.metadata.capturedAt != null }
        .thenByDescending { it.metadata.capturedAt }
        .thenBy { it.folder.lowercase() }
        .thenBy { it.stem.lowercase() }
        .thenBy { it.id }).toList()
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

fun keywordCasefold(input: String): String = normalizeKeyword(input).lowercase()

expect fun normalizeNfc(input: String): String
