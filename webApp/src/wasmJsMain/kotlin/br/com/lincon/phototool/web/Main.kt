package br.com.lincon.phototool.web

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.window.ComposeViewport
import br.com.lincon.phototool.remote.RemoteCurationStore
import br.com.lincon.phototool.state.Action
import br.com.lincon.phototool.state.Screen
import br.com.lincon.phototool.ui.AppCallbacks
import br.com.lincon.phototool.ui.AppCapabilities
import br.com.lincon.phototool.ui.PhotoToolApp
import br.com.lincon.phototool.ui.LocalPhotoPanelPreferences
import br.com.lincon.phototool.ui.PhotoPanelPreferences
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.events.Event

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    document.getElementById("startup")?.remove()
    ComposeViewport(viewportContainerId = "webApp") {
        val scope = rememberCoroutineScope()
        val client = remember { browserHttpClient() }
        val store = remember {
            RemoteCurationStore(BrowserCurationApi(client), scope).also {
                if (window.innerWidth < 600) it.dispatch(Action.SetThumbnailSize(150))
            }
        }
        val previews = remember { HttpPreviewLoader(client, scope) }
        val state by store.state.collectAsState()
        var textFocused by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { store.start(requestedPhoto()) }
        LaunchedEffect(state.screen, state.selectedId, state.remote?.loading) {
            if (state.remote?.loading == false && state.remote?.error == null) {
                val photoId = state.selectedId.takeIf { state.screen == Screen.DETAIL }
                if (requestedPhoto() != photoId) updatePhotoLocation(photoId)
            }
        }
        DisposableEffect(client) {
            val back: (Event) -> Unit = {
                requestedPhoto()?.let(store::openPhoto) ?: store.dispatch(Action.CloseDetail)
            }
            window.addEventListener("popstate", back)
            onDispose { window.removeEventListener("popstate", back); client.close() }
        }
        // The host supplies preferences; all material and motion remain in shared Compose.
        CompositionLocalProvider(LocalPhotoPanelPreferences provides browserPanelPreferences()) {
        // This is the production desktop UI, not a web-specific copy of its screens.
        PhotoToolApp(
            state,
            AppCallbacks(
                dispatch = store::dispatch,
                imageLoader = previews,
                capabilities = AppCapabilities.Remote,
                mutate = store::mutate,
                mutateFlag = store::mutateFlag,
                refresh = store::refresh,
                changePage = store::changePage,
                navigate = store::navigate,
                presentDetail = store::presentDetail,
                textFieldFocused = { textFocused = it },
                openMap = { latitude, longitude ->
                    window.open("https://www.openstreetmap.org/?mlat=$latitude&mlon=$longitude#map=15/$latitude/$longitude", "_blank", "noopener,noreferrer")
                },
            ),
            // Shared panels get first refusal (e.g. Escape closes a panel, not the photograph).
            Modifier.onKeyEvent { event ->
                if (textFocused || event.type != KeyEventType.KeyDown) false
                else when {
                    event.key == Key.Escape && state.filtersOpen -> { store.dispatch(Action.ToggleFilters); true }
                    event.key == Key.Escape && state.screen == Screen.DETAIL -> { store.dispatch(Action.CloseDetail); true }
                    event.key == Key.DirectionLeft && state.screen == Screen.DETAIL -> { store.navigate(-1); true }
                    event.key == Key.DirectionRight && state.screen == Screen.DETAIL -> { store.navigate(1); true }
                    else -> false
                }
            },
        )
        }
    }
}

@Composable
private fun browserPanelPreferences(): PhotoPanelPreferences {
    val motion = remember { window.matchMedia("(prefers-reduced-motion: reduce)") }
    val transparency = remember { window.matchMedia("(prefers-reduced-transparency: reduce)") }
    val contrast = remember { window.matchMedia("(prefers-contrast: more)") }
    fun current() = PhotoPanelPreferences(motion.matches, transparency.matches, contrast.matches)
    var preferences by remember { mutableStateOf(current()) }
    DisposableEffect(motion, transparency, contrast) {
        val changed: (Event) -> Unit = { preferences = current() }
        val queries = listOf(motion, transparency, contrast)
        queries.forEach { it.addEventListener("change", changed) }
        onDispose { queries.forEach { it.removeEventListener("change", changed) } }
    }
    return preferences
}

private fun requestedPhoto(): String? = locationPhoto()?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun locationPhoto(): String? = js("new URL(location.href).searchParams.get('photo')")

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun updatePhotoLocation(id: String?): Unit = js("""{
    const url = new URL(location.href);
    const push = id !== null && !url.searchParams.has('photo');
    if (id === null) url.searchParams.delete('photo'); else url.searchParams.set('photo', id);
    history[push ? 'pushState' : 'replaceState'](null, '', url);
}""")
