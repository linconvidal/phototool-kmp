package br.com.lincon.phototool.ui

import androidx.compose.ui.input.pointer.PointerEvent
import org.w3c.dom.events.Event

// Compose 1.11 maps both pointerup and pointercancel to Release. Inspect the native event
// already carried by Compose; no DOM listener, JS recognizer, or navigation bridge.
internal actual fun PointerEvent.isCanceledContact(): Boolean =
    (nativeEvent as? Event)?.type == "pointercancel"
