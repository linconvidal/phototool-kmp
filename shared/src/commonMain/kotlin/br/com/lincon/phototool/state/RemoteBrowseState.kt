package br.com.lincon.phototool.state

import br.com.lincon.phototool.domain.Photo

/** One bounded server-ordered page, not a locally complete library. */
data class RemoteBrowseState(
    val page: Int = 1,
    val totalPages: Int = 0,
    val total: Int = 0,
    val libraryTotal: Int = 0,
    val generation: String? = null,
    val projection: String = "Carregando",
    val loading: Boolean = true,
    val error: String? = null,
    val detail: Photo? = null,
    val previousId: String? = null,
    val nextId: String? = null,
    val requestedId: String? = null,
    val pendingDetail: PendingRemoteDetail? = null,
    val preparePhotos: List<Photo> = emptyList(),
    val savingPhotoId: String? = null,
    val editorialNotice: String? = null,
    val editorialNeedsRefresh: Boolean = false,
)

/** Metadata is staged until the shared viewer has decoded its matching preview. */
data class PendingRemoteDetail(val ticket: Int, val photo: Photo, val previousId: String?, val nextId: String?)
