package br.com.lincon.phototool.domain

enum class MediaKind { RAW, JPEG }
enum class Flag { PICK, UNFLAGGED, REJECT }
enum class ColorLabel { RED, YELLOW, GREEN }
enum class GpsFilter { ANY, PRESENT, MISSING }
enum class WriteState { IDLE, SAVING, PERSISTED, FAILED }
enum class MetadataStatus { MISSING, AVAILABLE, PARTIAL, ERROR }
enum class PhotoSort { CAPTURE_TIME, RECENTLY_ADDED }

data class EditorialState(
    val flag: Flag = Flag.UNFLAGGED,
    val rating: Int = 0,
    val label: ColorLabel? = null,
    val keywords: List<String> = emptyList(),
    /** Independent observed xmpDM:good value. Flag authority remains xmpDM:pick. */
    val good: Boolean? = null,
    /** Bounded diagnostic for an unreadable xmpDM:good value; other editorial fields remain usable. */
    val goodError: String? = null,
)

data class ObservedMetadata(
    val capturedAt: String? = null,
    /** Display value retained for cache compatibility. */
    val camera: String? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val lens: String? = null,
    val focalLength: Double? = null,
    val aperture: Double? = null,
    val exposureSeconds: Double? = null,
    val iso: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val status: MetadataStatus = MetadataStatus.MISSING,
    val errorCode: String? = null,
) {
    val hasGps: Boolean get() = latitude != null && longitude != null
    val cameraDisplay: String? get() = camera ?: listOfNotNull(cameraMake, cameraModel).joinToString(" ").ifBlank { null }
}

data class MediaIdentity(
    val path: String,
    val fileKey: String,
    val size: Long,
    val modifiedMillis: Long,
) {
    val isComplete: Boolean get() = path.isNotBlank() && fileKey.isNotBlank() && size >= 0 && modifiedMillis >= 0
}

private val FilenameNumberPattern = Regex("""\d+""")

data class Photo(
    val id: String,
    val folder: String,
    val stem: String,
    val authorityPath: String,
    val rawPath: String? = null,
    val jpegPath: String? = null,
    val previewPath: String? = jpegPath ?: rawPath,
    val sourceIdentity: MediaIdentity? = null,
    val previewIdentity: MediaIdentity? = null,
    val metadata: ObservedMetadata = ObservedMetadata(),
    val editorial: EditorialState = EditorialState(),
    val writable: Boolean = true,
    val issue: String? = null,
    val writeState: WriteState = WriteState.IDLE,
    val writeError: String? = null,
) {
    val displayName: String get() = (rawPath ?: jpegPath ?: stem).substringAfterLast('/')
    val filenameNumber: String? get() = FilenameNumberPattern.findAll(stem).lastOrNull()?.value
    val rawFormat: String? get() = rawPath?.substringAfterLast('.', "")?.uppercase()?.ifBlank { "RAW" }
    val mediaFormatLabel: String get() = when {
        rawPath != null && jpegPath != null -> "${rawFormat ?: "RAW"} + JPEG"
        rawPath != null -> rawFormat ?: "RAW"
        else -> "JPEG"
    }
    val aspectRatio: Float get() = if ((metadata.width ?: 0) > 0 && (metadata.height ?: 0) > 0)
        metadata.width!!.toFloat() / metadata.height!! else 1.5f
}

data class Query(
    val search: String = "",
    val keyword: String? = null,
    val fromDate: String? = null,
    val toDate: String? = null,
    val flag: Flag? = null,
    val camera: String? = null,
    val lens: String? = null,
    val minimumStars: Int = 0,
    val gps: GpsFilter = GpsFilter.ANY,
    /** Library-relative folder. Descendants are included. */
    val folder: String? = null,
    val sort: PhotoSort = PhotoSort.CAPTURE_TIME,
)

const val MAX_BATCH_PHOTOS = 100

sealed interface BatchEdit {
    data class SetFlag(val flag: Flag) : BatchEdit
    data class SetRating(val rating: Int) : BatchEdit
    data class SetLabel(val label: ColorLabel?) : BatchEdit
    data class AddKeyword(val keyword: String) : BatchEdit
    data class RemoveKeyword(val keyword: String) : BatchEdit
    data object ClearKeywords : BatchEdit
}

fun BatchEdit.applyTo(editorial: EditorialState): EditorialState = when (this) {
    is BatchEdit.SetFlag -> editorial.copy(flag = flag)
    is BatchEdit.SetRating -> editorial.copy(rating = rating.coerceIn(0, 5))
    is BatchEdit.SetLabel -> editorial.copy(label = label)
    is BatchEdit.AddKeyword -> {
        val normalized = normalizeFlatKeyword(keyword)
        val retained = editorial.keywords.filterNot { keywordCasefold(it) == keywordCasefold(normalized) }
        editorial.copy(keywords = retained + normalized)
    }
    is BatchEdit.RemoveKeyword -> editorial.copy(keywords = editorial.keywords.filterNot { keywordCasefold(it) == keywordCasefold(keyword) })
    BatchEdit.ClearKeywords -> editorial.copy(keywords = emptyList())
}

data class MediaCandidate(val directory: String, val filename: String, val kind: MediaKind) {
    val stem: String get() = filename.substringBeforeLast('.', filename)
}

data class PairingResult(
    val folder: String,
    val stem: String,
    val raw: String?,
    val jpeg: String?,
    val xmp: String?,
    val writable: Boolean,
    val issue: String? = null,
)
