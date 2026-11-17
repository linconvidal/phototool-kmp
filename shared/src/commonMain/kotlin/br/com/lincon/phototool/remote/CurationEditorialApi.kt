package br.com.lincon.phototool.remote

import br.com.lincon.phototool.api.*
import br.com.lincon.phototool.domain.*

/** Optional transport capability. Permission still comes from session/status and each photo. */
interface CurationEditorialApi : CurationReadApi {
    suspend fun session(): SessionDto
    suspend fun mutate(id: String, revision: String, command: EditorialCommand, csrfToken: String): MutationResultDto
}

class EditorialRequestFailed(val error: ApiErrorDto) : Exception(error.code)

internal fun editorialIntent(before: EditorialState, desired: EditorialState): EditorialCommand? {
    val changes = listOf(before.flag != desired.flag, before.rating != desired.rating,
        before.label != desired.label, before.keywords != desired.keywords)
    require(changes.count { it } <= 1 && before.good == desired.good && before.goodError == desired.goodError)
    return when {
        changes[0] -> EditorialCommand.SetFlag(desired.flag)
        changes[1] -> EditorialCommand.SetRating(desired.rating.also { require(it in 0..5) })
        changes[2] -> EditorialCommand.SetLabel(desired.label)
        changes[3] -> {
            if (desired.keywords.size == before.keywords.size + 1 && desired.keywords.dropLast(1) == before.keywords) {
                EditorialCommand.AddKeyword(normalizeFlatKeyword(desired.keywords.last()))
            } else {
                val removed = before.keywords.filterNot { it in desired.keywords }
                require(removed.size == 1 && before.keywords.filterNot { it == removed.single() } == desired.keywords)
                EditorialCommand.RemoveKeyword(removed.single())
            }
        }
        else -> null
    }
}

internal fun EditorialDto.toEditorialState(): EditorialState {
    require(revision.matches(Regex("[0-9a-f]{64}")) && rating in 0..5)
    return EditorialState(flag, rating, label, keywords)
}

internal fun editorialFailureMessage(error: ApiErrorDto?): String = when {
    error?.xmpPersisted == true -> "XMP salvo, mas a projeção falhou. Edição bloqueada; não repita a alteração."
    error?.code == "editorial-revision-conflict" -> "A fotografia foi alterada por outra sessão. Atualize e revise antes de editar."
    error?.code == "write-disabled" -> "O servidor está em modo somente leitura."
    error?.code in setOf("csrf-invalid", "origin-invalid") -> "O servidor recusou a autorização. Atualize antes de tentar novamente."
    error?.code in setOf("projection-degraded", "projection-busy", "sync-in-progress") -> "O servidor bloqueou a edição. Atualize para conferir o estado."
    error?.code in setOf("invalid-input", "xmp-write-failed") -> "O servidor não confirmou a gravação segura. Atualize e revise a fotografia."
    else -> "Gravação não confirmada. Pode ter sido aplicada; atualize e confira antes de repetir."
}
