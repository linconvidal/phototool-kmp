package br.com.lincon.phototool.domain

import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCanonicalMapping

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun normalizeNfc(input: String): String = (input as NSString).precomposedStringWithCanonicalMapping
