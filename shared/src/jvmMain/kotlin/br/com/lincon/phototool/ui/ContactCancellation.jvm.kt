package br.com.lincon.phototool.ui

import androidx.compose.ui.input.pointer.PointerEvent

// Native Compose cancellation consumes the changes / cancels the pointer-input coroutine.
internal actual fun PointerEvent.isCanceledContact(): Boolean = false
