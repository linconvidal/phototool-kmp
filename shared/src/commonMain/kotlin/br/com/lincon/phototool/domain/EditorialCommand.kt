package br.com.lincon.phototool.domain

/** One editorial intent; applied to authoritative XMP only by the JVM application service. */
sealed interface EditorialCommand {
    data class SetFlag(val value: Flag) : EditorialCommand
    data class SetRating(val value: Int) : EditorialCommand
    data class SetLabel(val value: ColorLabel?) : EditorialCommand
    data class AddKeyword(val value: String) : EditorialCommand
    data class RemoveKeyword(val value: String) : EditorialCommand
}
