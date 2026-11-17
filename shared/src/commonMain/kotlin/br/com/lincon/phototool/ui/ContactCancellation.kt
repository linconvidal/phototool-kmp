package br.com.lincon.phototool.ui

import androidx.compose.ui.input.pointer.PointerEvent

/** Platform cancellation classification only; recognition stays in the shared Compose handler. */
internal expect fun PointerEvent.isCanceledContact(): Boolean
