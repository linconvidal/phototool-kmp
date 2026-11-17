package br.com.lincon.phototool.ui

import androidx.compose.ui.input.pointer.PointerEvent

internal actual fun PointerEvent.isCanceledContact(): Boolean = false
