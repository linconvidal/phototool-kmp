package br.com.lincon.phototool.domain

actual fun normalizeNfc(input: String): String = normalizeBrowserNfc(input)

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun normalizeBrowserNfc(input: String): String = js("input.normalize('NFC')")
