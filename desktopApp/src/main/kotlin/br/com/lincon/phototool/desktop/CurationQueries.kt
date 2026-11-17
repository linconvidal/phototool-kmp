package br.com.lincon.phototool.desktop

import br.com.lincon.phototool.api.*

import br.com.lincon.phototool.domain.Photo
import br.com.lincon.phototool.domain.Query
import br.com.lincon.phototool.domain.filterAndOrder
import br.com.lincon.phototool.domain.normalizeKeyword
import br.com.lincon.phototool.domain.queryDateError
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.ceil

internal const val MAX_FACET_OPTIONS = 500

class InvalidQueryException(message: String) : IllegalArgumentException(message)
class PhotoNotFoundException : NoSuchElementException("Photo not found")

class CurationQueryService {
    fun page(photos: List<Photo>, generation: String?, input: PhotoQueryInput): PhotoPageDto {
        val ordered = ordered(photos, input)
        val offset = (input.page.toLong() - 1L) * input.pageSize
        val items = if (offset >= ordered.size) emptyList() else ordered.drop(offset.toInt()).take(input.pageSize).map(::summary)
        val totalPages = if (ordered.isEmpty()) 0 else ceil(ordered.size.toDouble() / input.pageSize).toInt()
        return PhotoPageDto(generation, input.page, input.pageSize, ordered.size, totalPages, items)
    }

    fun detail(photos: List<Photo>, input: PhotoQueryInput, photoId: String): PhotoDetailDto {
        requireOpaquePhotoId(photoId)
        val ordered = ordered(photos, input)
        val index = ordered.indexOfFirst { it.id == photoId }
        if (index < 0) throw PhotoNotFoundException()
        val photo = ordered[index]
        val metadata = photo.metadata
        return PhotoDetailDto(
            id = photo.id,
            displayName = photo.displayName,
            format = photo.mediaFormatLabel,
            aspectRatio = photo.aspectRatio.toDouble(),
            metadata = ObservedMetadataDto(
                capturedAt = metadata.capturedAt,
                camera = metadata.cameraDisplay,
                cameraMake = metadata.cameraMake,
                cameraModel = metadata.cameraModel,
                lens = metadata.lens,
                focalLength = metadata.focalLength,
                aperture = metadata.aperture,
                exposureSeconds = metadata.exposureSeconds,
                iso = metadata.iso,
                latitude = metadata.latitude,
                longitude = metadata.longitude,
                width = metadata.width,
                height = metadata.height,
                status = metadata.status.name,
                errorCode = metadata.errorCode,
            ),
            editorial = photo.editorial.toDto(),
            writable = photo.writable,
            issueCode = photo.issue?.let { "photo-not-writable" },
            previewUrl = previewUrl(photo, 1600),
            previousId = ordered.getOrNull(index - 1)?.id,
            nextId = ordered.getOrNull(index + 1)?.id,
        )
    }

    fun facets(photos: List<Photo>): FilterFacetsDto {
        data class FacetAccumulator(val label: String, var count: Int)
        data class BoundedOptions(val values: List<FacetOptionDto>, val truncated: Boolean)

        fun options(values: Sequence<String>, fold: (String) -> String = { it.lowercase() }): BoundedOptions {
            val accumulated = linkedMapOf<String, FacetAccumulator>()
            var truncated = false
            values.forEach { value ->
                val key = fold(value)
                val current = accumulated[key]
                if (current != null) current.count++
                else if (accumulated.size < MAX_FACET_OPTIONS) accumulated[key] = FacetAccumulator(value, 1)
                else truncated = true
            }
            return BoundedOptions(
                accumulated.values.sortedBy { it.label.lowercase() }.map { FacetOptionDto(it.label, it.label, it.count) },
                truncated,
            )
        }

        val keywords = options(photos.asSequence().flatMap { it.editorial.keywords.asSequence() }, ::editorialFold)
        val cameras = options(photos.asSequence().mapNotNull { it.metadata.cameraDisplay })
        val lenses = options(photos.asSequence().mapNotNull { it.metadata.lens })
        val folderCounts = linkedMapOf<String, Int>()
        var foldersTruncated = false
        photos.forEach { photo ->
            if (photo.folder in folderCounts) folderCounts[photo.folder] = folderCounts.getValue(photo.folder) + 1
            else if (folderCounts.size < MAX_FACET_OPTIONS) folderCounts[photo.folder] = 1
            else foldersTruncated = true
        }
        val folders = folderCounts.entries.sortedBy { it.key.lowercase() }
        val duplicateLabels = folders.groupingBy { folderLabel(it.key) }.eachCount()
        val labelIndexes = mutableMapOf<String, Int>()
        val folderOptions = folders.map { entry ->
            val base = folderLabel(entry.key)
            val occurrence = labelIndexes.merge(base, 1, Int::plus) ?: 1
            val label = if ((duplicateLabels[base] ?: 0) > 1) "$base ($occurrence)" else base
            FacetOptionDto(folderId(entry.key), label, entry.value)
        }
        return FilterFacetsDto(
            keywords = keywords.values,
            cameras = cameras.values,
            lenses = lenses.values,
            folders = folderOptions,
            truncated = keywords.truncated || cameras.truncated || lenses.truncated || foldersTruncated,
        )
    }

    fun photo(photos: List<Photo>, photoId: String): Photo {
        requireOpaquePhotoId(photoId)
        return photos.firstOrNull { it.id == photoId } ?: throw PhotoNotFoundException()
    }

    fun previewUrl(photo: Photo, maximumDimension: Int): String {
        val bounded = maximumDimension.coerceIn(MIN_PREVIEW_DIMENSION, MAX_PREVIEW_DIMENSION_API)
        return "/api/v1/photos/${photo.id}/preview?max=$bounded&v=${previewVersion(photo)}"
    }

    private fun ordered(photos: List<Photo>, input: PhotoQueryInput): List<Photo> {
        validate(input)
        val folder = input.folderId?.let { requested ->
            photos.map { it.folder }.distinct().firstOrNull { folderId(it) == requested }
                ?: throw InvalidQueryException("Unknown folder filter")
        }
        val keyword = input.keyword?.let { requested ->
            runCatching { normalizeKeyword(requested) }.getOrElse { throw InvalidQueryException("Invalid keyword filter") }
        }
        return filterAndOrder(
            photos,
            Query(
                search = input.search,
                keyword = keyword,
                fromDate = input.fromDate,
                toDate = input.toDate,
                flag = input.flag,
                camera = input.camera,
                lens = input.lens,
                minimumStars = input.minimumStars,
                gps = input.gps,
                folder = folder,
                sort = input.sort,
            ),
        )
    }

    private fun validate(input: PhotoQueryInput) {
        if (input.page !in 1..100_000) throw InvalidQueryException("Page is outside the supported range")
        if (input.pageSize !in 1..MAX_PAGE_SIZE) throw InvalidQueryException("Page size is outside the supported range")
        if (input.search.length > 512) throw InvalidQueryException("Search is too long")
        if (input.minimumStars !in 0..5) throw InvalidQueryException("Minimum stars must be between 0 and 5")
        if (listOf(input.keyword, input.camera, input.lens).any { (it?.length ?: 0) > 512 }) throw InvalidQueryException("Filter value is too long")
        if (listOf(input.fromDate, input.toDate).any { (it?.length ?: 0) > 10 }) throw InvalidQueryException("Date filter is too long")
        if (input.folderId?.matches(Regex("[0-9a-f]{24}")) == false) throw InvalidQueryException("Folder filter is invalid")
        queryDateError(input.fromDate, input.toDate)?.let { throw InvalidQueryException(it) }
    }

    private fun summary(photo: Photo) = PhotoSummaryDto(
        id = photo.id,
        displayName = photo.displayName,
        format = photo.mediaFormatLabel,
        aspectRatio = photo.aspectRatio.toDouble(),
        capturedAt = photo.metadata.capturedAt,
        camera = photo.metadata.cameraDisplay,
        lens = photo.metadata.lens,
        editorial = photo.editorial.toSummaryDto(),
        writable = photo.writable,
        previewUrl = previewUrl(photo, 900),
    )

    private fun requireOpaquePhotoId(photoId: String) {
        if (!photoId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) throw PhotoNotFoundException()
    }
}

fun editorialRevision(editorial: br.com.lincon.phototool.domain.EditorialState): String = sha256(
    listOf(
        editorial.flag.name,
        editorial.rating.toString(),
        editorial.label?.name.orEmpty(),
        editorial.good?.toString().orEmpty(),
        editorial.goodError.orEmpty(),
        editorial.keywords.joinToString("\u0001"),
    ).joinToString("\u0000"),
)

internal fun previewVersion(photo: Photo): String {
    val identity = photo.previewIdentity ?: return sha256("missing:${photo.id}").take(24)
    return sha256("${photo.id}\u0000${identity.fileKey}\u0000${identity.size}\u0000${identity.modifiedMillis}").take(24)
}

internal fun folderId(folder: String): String = sha256("folder\u0000$folder").take(24)
private fun folderLabel(folder: String): String = folder.substringAfterLast('/').ifBlank { "Biblioteca" }
private fun editorialFold(value: String): String = value.lowercase()
private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
