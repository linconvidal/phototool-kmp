package br.com.lincon.phototool.api

import br.com.lincon.phototool.domain.ColorLabel
import br.com.lincon.phototool.domain.Flag
import kotlinx.serialization.Serializable

const val DEFAULT_PAGE_SIZE = 24
const val MAX_PAGE_SIZE = 60
const val MIN_PREVIEW_DIMENSION = 128
const val MAX_PREVIEW_DIMENSION_API = 4096
const val DISPLAY_PREVIEW_DIMENSION = 2048
const val MAX_ORIGINAL_JPEG_BYTES = 64 * 1024 * 1024
const val MAX_ORIGINAL_JPEG_PIXELS = 64_000_000L
const val MAX_ORIGINAL_JPEG_DIMENSION = 16_384

@Serializable
data class SessionDto(val csrfToken: String, val writeEnabled: Boolean)

@Serializable
data class SnapshotStatusDto(
    val snapshotGeneration: String?,
    val photoCount: Int,
    val projectionState: ProjectionStateDto,
    val writeEnabled: Boolean,
    val sync: SyncStatusDto,
)

@Serializable
enum class ProjectionStateDto { EMPTY, READY, DEGRADED }

@Serializable
data class SyncStatusDto(
    val flightId: String? = null,
    val phase: String = "IDLE",
    val running: Boolean = false,
    val photos: Int = 0,
    val totalPhotos: Int? = null,
    val errors: Int = 0,
    val message: String = "Nenhuma sincronização executada",
    val completedAt: String? = null,
)

@Serializable
data class SyncCommandDto(val flightId: String, val started: Boolean, val status: SyncStatusDto)

@Serializable
data class EditorialDto(
    val flag: Flag,
    val rating: Int,
    val label: ColorLabel?,
    val keywords: List<String>,
    val revision: String,
)

@Serializable
data class EditorialSummaryDto(val flag: Flag, val rating: Int, val label: ColorLabel?, val keywords: List<String>)

@Serializable
data class PhotoSummaryDto(
    val id: String,
    val displayName: String,
    val format: String,
    val aspectRatio: Double,
    val capturedAt: String?,
    val camera: String?,
    val lens: String?,
    val editorial: EditorialSummaryDto,
    val writable: Boolean,
    val previewUrl: String,
)

@Serializable
data class PhotoPageDto(
    val snapshotGeneration: String?,
    val page: Int,
    val pageSize: Int,
    val total: Int,
    val totalPages: Int,
    val items: List<PhotoSummaryDto>,
)

@Serializable
data class ObservedMetadataDto(
    val capturedAt: String?,
    val camera: String?,
    val cameraMake: String?,
    val cameraModel: String?,
    val lens: String?,
    val focalLength: Double?,
    val aperture: Double?,
    val exposureSeconds: Double?,
    val iso: Int?,
    val latitude: Double?,
    val longitude: Double?,
    val width: Int?,
    val height: Int?,
    val status: String,
    val errorCode: String?,
)

@Serializable
data class PhotoDetailDto(
    val id: String,
    val displayName: String,
    val format: String,
    val aspectRatio: Double,
    val metadata: ObservedMetadataDto,
    val editorial: EditorialDto,
    val writable: Boolean,
    val issueCode: String?,
    val previewUrl: String,
    val previousId: String?,
    val nextId: String?,
)

@Serializable
data class FacetOptionDto(val value: String, val label: String, val count: Int)

@Serializable
data class FilterFacetsDto(
    val keywords: List<FacetOptionDto>,
    val cameras: List<FacetOptionDto>,
    val lenses: List<FacetOptionDto>,
    val folders: List<FacetOptionDto>,
    val truncated: Boolean,
)

@Serializable
data class ExpectedRevisionRequest(val expectedEditorialRevision: String)
@Serializable
data class FlagMutationRequest(val value: Flag, val expectedEditorialRevision: String)
@Serializable
data class RatingMutationRequest(val value: Int, val expectedEditorialRevision: String)
@Serializable
data class LabelMutationRequest(val value: ColorLabel?, val expectedEditorialRevision: String)
@Serializable
data class KeywordMutationRequest(val keyword: String, val expectedEditorialRevision: String)

@Serializable
data class MutationResultDto(
    val photoId: String,
    val editorial: EditorialDto,
    val xmpPersisted: Boolean,
    val projectionUpdated: Boolean,
    val projectionState: ProjectionStateDto,
)

@Serializable
data class ApiErrorDto(
    val code: String,
    val message: String,
    val xmpPersisted: Boolean? = null,
    val projectionUpdated: Boolean? = null,
    val editorial: EditorialDto? = null,
)
