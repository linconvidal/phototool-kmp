package br.com.lincon.phototool.ui

import androidx.compose.ui.graphics.ImageBitmap
import br.com.lincon.phototool.domain.MAX_BATCH_PHOTOS
import br.com.lincon.phototool.domain.Photo

/** Platform boundary for bounded, identity-checked local previews. */
fun interface PlatformImageLoader {
    suspend fun load(photo: Photo, maximumDimension: Int): ImageBitmap?

    companion object { val None = PlatformImageLoader { _, _ -> null } }
}

data class FujiRecipeView(
    val kind: String,
    val editable: Boolean,
    val exposureBias: String,
    val dynamicRange: Int,
    val filmSimulation: String,
    val grainEffect: String,
    val whiteBalance: String,
    val wbShiftR: Int,
    val wbShiftB: Int,
    val highlightTone: Int,
    val shadowTone: Int,
    val color: Int,
    val sharpness: Int,
    val noiseReduction: Int,
    val lensModulation: Boolean,
)

data class HdrView(
    val enabled: Boolean = false,
    val maximum: String = "4.00",
    val controls: Map<String, Int> = emptyMap(),
)

data class AuxiliaryView(
    val fuji: FujiRecipeView? = null,
    val hdr: HdrView = HdrView(),
    val status: String = "Ready",
    val error: String? = null,
)

const val MAX_SENSITIVE_BATCH_PHOTOS = MAX_BATCH_PHOTOS

sealed interface AuxiliaryBatchEdit {
    data class SetHdr(
        val enabled: Boolean,
        val maximum: String = "4.00",
        val controls: Map<String, Int> = emptyMap(),
    ) : AuxiliaryBatchEdit
    data class UpdateFuji(val updates: Map<String, String>, val label: String) : AuxiliaryBatchEdit
}

data class AuxiliaryBatchResult(
    val channel: String,
    val requested: Int,
    val succeeded: Int,
    val ignored: Int,
    val failed: Int,
    /** At most MAX_SENSITIVE_BATCH_PHOTOS opaque per-photo results; never paths. */
    val items: List<AuxiliaryBatchItemResult> = emptyList(),
) {
    val summary: String get() = "$channel: $succeeded ${if (succeeded == 1) "salva" else "salvas"} · $ignored ${if (ignored == 1) "ignorada" else "ignoradas"} · $failed ${if (failed == 1) "falhou" else "falharam"}"
}

enum class AuxiliaryBatchOutcome { SUCCEEDED, IGNORED, FAILED }

data class AuxiliaryBatchItemResult(
    val photoId: String,
    val channel: String,
    val outcome: AuxiliaryBatchOutcome,
    val errorCode: String? = null,
)

data class AuxiliaryActions(
    val load: suspend (Photo) -> AuxiliaryView = { AuxiliaryView() },
    val updateFuji: suspend (Photo, Map<String, String>) -> AuxiliaryView = { _, _ -> AuxiliaryView(error = "Unavailable") },
    val updateHdr: suspend (Photo, HdrView) -> AuxiliaryView = { _, _ -> AuxiliaryView(error = "Unavailable") },
    val transferFujiToXmp: suspend (Photo) -> AuxiliaryView = { AuxiliaryView(error = "Unavailable") },
    val transferXmpToFuji: suspend (Photo) -> AuxiliaryView = { AuxiliaryView(error = "Unavailable") },
    val batchUpdate: suspend (List<Photo>, AuxiliaryBatchEdit) -> AuxiliaryBatchResult = { photos, edit -> AuxiliaryBatchResult(if (edit is AuxiliaryBatchEdit.SetHdr) "HDR" else "Fuji FP2", photos.size, 0, photos.size, 0) },
)
