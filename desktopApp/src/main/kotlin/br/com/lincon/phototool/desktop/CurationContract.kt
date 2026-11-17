package br.com.lincon.phototool.desktop

import br.com.lincon.phototool.api.DEFAULT_PAGE_SIZE
import br.com.lincon.phototool.api.EditorialDto
import br.com.lincon.phototool.api.EditorialSummaryDto
import br.com.lincon.phototool.domain.ColorLabel
import br.com.lincon.phototool.domain.EditorialState
import br.com.lincon.phototool.domain.Flag
import br.com.lincon.phototool.domain.GpsFilter
import br.com.lincon.phototool.domain.PhotoSort

// DTOs and editorial intents are shared; filesystem query resolution stays on JVM.
data class PhotoQueryInput(
    val search: String = "",
    val keyword: String? = null,
    val fromDate: String? = null,
    val toDate: String? = null,
    val flag: Flag? = null,
    val camera: String? = null,
    val lens: String? = null,
    val minimumStars: Int = 0,
    val gps: GpsFilter = GpsFilter.ANY,
    val folderId: String? = null,
    val sort: PhotoSort = PhotoSort.CAPTURE_TIME,
    val page: Int = 1,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
)


internal fun EditorialState.toSummaryDto(): EditorialSummaryDto = EditorialSummaryDto(flag, rating, label, keywords)

internal fun EditorialState.toDto(revision: String = editorialRevision(this)): EditorialDto = EditorialDto(
    flag = flag,
    rating = rating,
    label = label,
    keywords = keywords,
    revision = revision,
)
