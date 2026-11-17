package br.com.lincon.phototool.remote

import br.com.lincon.phototool.api.*
import br.com.lincon.phototool.domain.*

/** Read transport; optional editorial transport never exposes sync or write-mode configuration. */
interface CurationReadApi {
    suspend fun status(): SnapshotStatusDto
    suspend fun page(query: Query, page: Int): PhotoPageDto
    suspend fun detail(query: Query, id: String): PhotoDetailDto
}

fun Query.httpParameters(page: Int = 1): Map<String, String> = buildMap {
    put("q", search)
    keyword?.let { put("keyword", it) }
    fromDate?.let { put("fromDate", it) }
    toDate?.let { put("toDate", it) }
    flag?.let { put("flag", it.name) }
    camera?.let { put("camera", it) }
    lens?.let { put("lens", it) }
    put("minimumStars", minimumStars.toString())
    put("gps", gps.name)
    folder?.let { put("folder", it) }
    put("sort", sort.name)
    put("page", page.toString())
    put("pageSize", DEFAULT_PAGE_SIZE.toString())
}

fun requirePhotoId(id: String): String = id.also { require(it.matches(Regex("[A-Za-z0-9_-]{1,64}"))) }

/** Do not accept URLs, traversal, redirects to external hosts, or arbitrary same-origin resources. */
fun boundedPreviewUrl(photo: Photo, maximumDimension: Int): String {
    val id = requirePhotoId(photo.id)
    val url = requireNotNull(photo.presentation).previewUrl
    val match = Regex("^/api/v1/photos/$id/preview\\?max=[0-9]+&v=([0-9a-f]{24,64})$").matchEntire(url)
    requireNotNull(match) { "Invalid preview URL" }
    return "/api/v1/photos/$id/preview?max=${maximumDimension.coerceIn(MIN_PREVIEW_DIMENSION, MAX_PREVIEW_DIMENSION_API)}&v=${match.groupValues[1]}"
}

/** Accept only the JPEG paired with this exact validated photo/preview identity. */
fun originalJpegUrl(photo: Photo): String? {
    val format = photo.mediaFormatLabel
    if (format != "JPEG" && !format.endsWith(" + JPEG")) return null
    return boundedPreviewUrl(photo, 1600).replace("/preview?max=1600&v=", "/original?v=")
}

/** Native-resolution region of the displayed JPEG, validated against the same preview identity. */
fun regionUrl(photo: Photo, x: Int, y: Int, width: Int, height: Int): String? {
    val format = photo.mediaFormatLabel
    if (format != "JPEG" && !format.endsWith(" + JPEG")) return null
    val base = boundedPreviewUrl(photo, 1600)
    val version = base.substringAfter("&v=", "")
    if (version.isBlank()) return null
    return "/api/v1/photos/${photo.id}/region?x=$x&y=$y&w=$width&h=$height&v=$version"
}

fun PhotoSummaryDto.toPhoto(): Photo = Photo(
    id = requirePhotoId(id), folder = "", stem = displayName.substringBeforeLast('.'), authorityPath = "",
    metadata = ObservedMetadata(capturedAt = capturedAt, camera = camera, lens = lens),
    editorial = EditorialState(editorial.flag, editorial.rating, editorial.label, editorial.keywords),
    // Summaries carry no expected revision. Open the detail before offering editorial actions.
    writable = false,
    presentation = presentation(id, displayName, format, aspectRatio, previewUrl),
)

fun PhotoDetailDto.toPhoto(): Photo = Photo(
    id = requirePhotoId(id), folder = "", stem = displayName.substringBeforeLast('.'), authorityPath = "",
    metadata = metadata.run { ObservedMetadata(
        capturedAt, camera, cameraMake, cameraModel, lens, focalLength, aperture, exposureSeconds, iso,
        latitude, longitude, width, height, MetadataStatus.entries.firstOrNull { it.name == status } ?: MetadataStatus.MISSING, errorCode,
    ) },
    editorial = EditorialState(editorial.flag, editorial.rating, editorial.label, editorial.keywords),
    writable = writable && editorial.revision.matches(Regex("[0-9a-f]{64}")), issue = issueCode,
    presentation = presentation(id, displayName, format, aspectRatio, previewUrl),
    editorialRevision = editorial.revision.takeIf { it.matches(Regex("[0-9a-f]{64}")) },
)

private fun presentation(id: String, name: String, format: String, ratio: Double, url: String): PhotoPresentation {
    require(name.isNotBlank() && '/' !in name && '\\' !in name && name.length <= 512)
    require(ratio.isFinite() && ratio > 0)
    val display = PhotoPresentation(name, format, ratio.toFloat(), url)
    boundedPreviewUrl(Photo(id, "", name, "", writable = false, presentation = display), 900)
    return display
}
