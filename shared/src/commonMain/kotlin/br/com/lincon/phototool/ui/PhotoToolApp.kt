package br.com.lincon.phototool.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.lincon.phototool.domain.*
import br.com.lincon.phototool.state.*
import kotlinx.coroutines.launch

private val Ink = Color(0xff0c0d0f)
private val Panel = Color(0xff15171a)
private val Raised = Color(0xff202328)
private val Amber = Color(0xffd8a657)
private val Muted = Color(0xff9a9da3)
private val Danger = Color(0xffc85b5b)

@Composable
fun PhotoToolTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Amber, background = Ink, surface = Panel, surfaceVariant = Raised, error = Danger),
        typography = Typography(),
        content = content,
    )
}

data class AppCallbacks(
    val dispatch: (Action) -> Unit,
    val chooseLibrary: () -> Unit = {},
    val synchronize: () -> Unit = {},
    val cancelSync: () -> Unit = {},
    val mutate: (Photo, EditorialState) -> Unit = { _, _ -> },
    val imageLoader: PlatformImageLoader = PlatformImageLoader.None,
    val auxiliary: AuxiliaryActions = AuxiliaryActions(),
    val navigate: (Int) -> Unit = {},
    val openMap: (Double, Double) -> Unit = { _, _ -> },
    val textFieldFocused: (Boolean) -> Unit = {},
    val shortcutScopeFocused: (Boolean) -> Unit = {},
    val galleryColumnsChanged: (Int) -> Unit = {},
)

@Composable
fun PhotoToolApp(state: AppState, callbacks: AppCallbacks, modifier: Modifier = Modifier) = PhotoToolTheme {
    Surface(modifier.fillMaxSize(), color = Ink) {
        Column {
            TopBar(state, callbacks)
            when (state.screen) {
                Screen.GALLERY -> GalleryScreen(state, callbacks)
                Screen.DETAIL -> DetailScreen(state, callbacks)
                Screen.SETTINGS -> SettingsScreen(state, callbacks)
            }
        }
        if (state.helpOpen) ShortcutHelp { callbacks.dispatch(Action.ToggleHelp) }
    }
}

@Composable
private fun TopBar(state: AppState, callbacks: AppCallbacks) = BoxWithConstraints(Modifier.fillMaxWidth().background(Panel)) {
    val compact = maxWidth < 600.dp
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PHOTOTOOL", Modifier.weight(1f), fontWeight = FontWeight.Bold, color = Amber)
            TextButton(onClick = { callbacks.dispatch(Action.ToggleHelp) }, modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "Keyboard shortcut help" }) { Text("?") }
            TextButton(onClick = { callbacks.dispatch(Action.OpenSettings) }, modifier = Modifier.heightIn(min = 44.dp)) { Text(if (compact) "⚙" else "Settings") }
            if (!compact) Box(Modifier.padding(start = 8.dp).background(if (state.writeEnabled) Color(0xff254c35) else Raised, RoundedCornerShape(4.dp)).padding(8.dp, 4.dp)) {
                Text(if (state.writeEnabled) "XMP WRITE" else "READ ONLY", color = if (state.writeEnabled) Color(0xff8ed0a3) else Muted)
            }
        }
        if (state.screen == Screen.GALLERY) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.query.search,
                onValueChange = { callbacks.dispatch(Action.SetQuery(state.query.copy(search = it))) },
                placeholder = { Text("Filename, camera, lens") },
                singleLine = true,
                modifier = Modifier.weight(1f).heightIn(min = 44.dp).onFocusChanged { callbacks.textFieldFocused(it.isFocused) }.semantics { contentDescription = "Search photographs" },
            )
            TextButton(onClick = { callbacks.dispatch(Action.ToggleFilters) }, modifier = Modifier.heightIn(min = 44.dp)) { Text("Filters") }
        }
        if (compact) Text(if (state.writeEnabled) "XMP WRITE" else "READ ONLY", color = if (state.writeEnabled) Color(0xff8ed0a3) else Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun GalleryScreen(state: AppState, callbacks: AppCallbacks) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val availableWidth = maxWidth
    val wide = availableWidth >= 980.dp
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            if (state.filtersOpen) FilterRail(state, callbacks)
            GalleryBody(state, callbacks, Modifier.weight(1f))
            state.selected?.let { Inspector(it, state, callbacks, Modifier.width(350.dp).fillMaxHeight()) }
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            GalleryBody(state, callbacks, Modifier.fillMaxSize())
            if (state.filtersOpen) FilterRail(state, callbacks, Modifier.align(Alignment.CenterStart).widthIn(max = availableWidth).fillMaxHeight().border(1.dp, Raised))
        }
        state.selected?.let { photo ->
            Dialog(
                onDismissRequest = { callbacks.dispatch(Action.ClearSelection) },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.CenterEnd) {
                    Inspector(photo, state, callbacks, Modifier.widthIn(max = minOf(350.dp, availableWidth)).fillMaxHeight().border(1.dp, Raised), dismissible = true, modal = true)
                }
            }
        }
    }
}

@Composable
private fun GalleryBody(state: AppState, callbacks: AppCallbacks, modifier: Modifier) = Box(modifier.fillMaxHeight()) {
    when {
        state.library == null -> EmptyLibrary(callbacks)
        state.visiblePhotos.isEmpty() -> EmptyGallery(state)
        else -> PhotoGrid(state, callbacks)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterRail(state: AppState, callbacks: AppCallbacks, modifier: Modifier = Modifier) {
    Column(modifier.width(264.dp).fillMaxHeight().background(Panel).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("FACETS", Modifier.weight(1f), color = Muted, fontWeight = FontWeight.Bold); TextButton(onClick = { callbacks.dispatch(Action.ToggleFilters) }, modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).semantics { contentDescription = "Close filters" }) { Text("×") } }
        Text("Flag")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf(null, Flag.PICK, Flag.UNFLAGGED, Flag.REJECT).forEach { flag -> FilterChip(selected = state.query.flag == flag, onClick = { callbacks.dispatch(Action.SetQuery(state.query.copy(flag = flag))) }, label = { Text(flag?.name ?: "Any") }, modifier = Modifier.heightIn(min = 44.dp)) } }
        Text("Minimum rating")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { (0..5).forEach { rating -> FilterChip(selected = state.query.minimumStars == rating, onClick = { callbacks.dispatch(Action.SetQuery(state.query.copy(minimumStars = rating))) }, label = { Text(rating.toString()) }, modifier = Modifier.heightIn(min = 44.dp)) } }
        Text("GPS")
        GpsFilter.entries.forEach { gps -> FilterChip(selected = state.query.gps == gps, onClick = { callbacks.dispatch(Action.SetQuery(state.query.copy(gps = gps))) }, label = { Text(gps.name.lowercase().replaceFirstChar { it.uppercase() }) }, modifier = Modifier.heightIn(min = 44.dp)) }
        FilterField("Exact keyword", state.query.keyword.orEmpty(), callbacks) { callbacks.dispatch(Action.SetQuery(state.query.copy(keyword = it.ifBlank { null }))) }
        FilterField("Exact camera", state.query.camera.orEmpty(), callbacks) { callbacks.dispatch(Action.SetQuery(state.query.copy(camera = it.ifBlank { null }))) }
        FilterField("Exact lens", state.query.lens.orEmpty(), callbacks) { callbacks.dispatch(Action.SetQuery(state.query.copy(lens = it.ifBlank { null }))) }
        FilterField("From YYYY-MM-DD", state.query.fromDate.orEmpty(), callbacks) { callbacks.dispatch(Action.SetQuery(state.query.copy(fromDate = it.ifBlank { null }))) }
        FilterField("To YYYY-MM-DD", state.query.toDate.orEmpty(), callbacks) { callbacks.dispatch(Action.SetQuery(state.query.copy(toDate = it.ifBlank { null }))) }
    }
}

@Composable
private fun FilterField(label: String, value: String, callbacks: AppCallbacks, changed: (String) -> Unit) = OutlinedTextField(
    value, changed, label = { Text(label) }, singleLine = true,
    modifier = Modifier.fillMaxWidth().onFocusChanged { callbacks.textFieldFocused(it.isFocused) },
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoGrid(state: AppState, callbacks: AppCallbacks) {
    BoxWithConstraints {
        val visible = state.visiblePhotos
        val columns = maxOf(1, ((maxWidth - 14.dp) / 220.dp).toInt())
        val gridState = rememberLazyStaggeredGridState()
        val focusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
        val selectedIndex = visible.indexOfFirst { it.id == state.selectedId }
        val selectedRequester = state.selectedId?.let(focusRequesters::get)
        SideEffect { callbacks.galleryColumnsChanged(columns) }
        LaunchedEffect(state.selectedId, selectedIndex) {
            if (selectedIndex >= 0 && gridState.layoutInfo.visibleItemsInfo.none { it.index == selectedIndex }) gridState.scrollToItem(selectedIndex)
        }
        LaunchedEffect(state.selectedId, selectedRequester) { selectedRequester?.requestFocus() }
        LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Adaptive(210.dp), state = gridState, contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalItemSpacing = 10.dp,
            modifier = Modifier.fillMaxSize().onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed || event.isShiftPressed) return@onKeyEvent false
                val current = visible.indexOfFirst { it.id == state.selectedId }
                val delta = when (event.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; Key.DirectionUp -> -columns; Key.DirectionDown -> columns; else -> 0 }
                when {
                    delta != 0 && visible.isNotEmpty() -> {
                        val next = (if (current < 0) 0 else current + delta).coerceIn(0, visible.lastIndex)
                        callbacks.dispatch(Action.Select(visible[next].id)); true
                    }
                    event.key == Key.Enter && state.selectedId != null -> { callbacks.dispatch(Action.OpenDetail); true }
                    else -> false
                }
            }.focusable().onFocusChanged { callbacks.shortcutScopeFocused(it.hasFocus) }.semantics { contentDescription = "Gallery photograph viewport" }) {
        items(visible, key = { it.id }) { photo ->
            val selected = state.selectedId == photo.id
            var focused by remember { mutableStateOf(false) }
            val focusRequester = remember(photo.id) { FocusRequester() }
            DisposableEffect(photo.id, focusRequester) {
                focusRequesters[photo.id] = focusRequester
                onDispose { if (focusRequesters[photo.id] === focusRequester) focusRequesters.remove(photo.id) }
            }
            Column(
                Modifier.fillMaxWidth().focusRequester(focusRequester).border(if (selected || focused) 3.dp else 1.dp, if (selected) Amber else if (focused) Color.White else Raised, RoundedCornerShape(6.dp))
                    .background(Raised, RoundedCornerShape(6.dp)).combinedClickable(
                        onClick = { callbacks.dispatch(Action.Select(photo.id)) },
                        onDoubleClick = { callbacks.dispatch(Action.Select(photo.id)); callbacks.dispatch(Action.OpenDetail) },
                    ).onFocusChanged { focused = it.isFocused; callbacks.shortcutScopeFocused(it.isFocused) }.focusable().semantics { contentDescription = "Photograph ${photo.displayName}"; this.selected = selected },
            ) {
                PhotoImage(photo, callbacks, 640, ContentScale.Crop, Modifier.fillMaxWidth().aspectRatio(photo.aspectRatio.coerceIn(.5f, 2.5f)).alpha(if (photo.editorial.flag == Flag.REJECT) .45f else 1f))
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(photo.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (photo.editorial.flag != Flag.UNFLAGGED) Text(if (photo.editorial.flag == Flag.PICK) "P" else "X", color = if (photo.editorial.flag == Flag.PICK) Amber else Danger)
                    if (photo.editorial.rating > 0) Text(" ${photo.editorial.rating}★", color = Amber)
                }
                if (selected) QuickControls(photo, state, callbacks)
            }
        }
        }
    }
}

private sealed interface PreviewState {
    data object Loading : PreviewState
    data class Loaded(val bitmap: androidx.compose.ui.graphics.ImageBitmap) : PreviewState
    data object Unavailable : PreviewState
}

@Composable
private fun PhotoImage(photo: Photo, callbacks: AppCallbacks, maximumDimension: Int, scale: ContentScale, modifier: Modifier) {
    val preview by produceState<PreviewState>(PreviewState.Loading, photo.id, photo.previewIdentity, maximumDimension) {
        value = callbacks.imageLoader.load(photo, maximumDimension)?.let(PreviewState::Loaded) ?: PreviewState.Unavailable
    }
    Box(modifier.background(Color(0xff27292d)), contentAlignment = Alignment.Center) {
        when (val current = preview) {
            PreviewState.Loading -> Text("Loading preview", color = Muted, modifier = Modifier.semantics { contentDescription = "Loading preview for ${photo.displayName}" })
            is PreviewState.Loaded -> Image(current.bitmap, contentDescription = "Preview of ${photo.displayName}", modifier = Modifier.fillMaxSize(), contentScale = scale)
            PreviewState.Unavailable -> Text("Preview unavailable", color = Muted, modifier = Modifier.semantics { contentDescription = "Preview unavailable for ${photo.displayName}" })
        }
        if (photo.issue != null || photo.metadata.status == MetadataStatus.ERROR) Text("!", color = Danger, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).semantics { contentDescription = "Photograph has an indexing or metadata error" })
    }
}

@Composable
private fun QuickControls(photo: Photo, state: AppState, callbacks: AppCallbacks) {
    Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf(Flag.PICK to "P", Flag.UNFLAGGED to "U", Flag.REJECT to "X").forEach { (flag, label) ->
            TextButton(enabled = state.writeEnabled && photo.writable, onClick = { callbacks.mutate(photo, photo.editorial.copy(flag = flag)) }, modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).semantics { contentDescription = "Set flag ${flag.name.lowercase()}"; selected = photo.editorial.flag == flag }) { Text(label) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Inspector(photo: Photo, state: AppState, callbacks: AppCallbacks, modifier: Modifier, dismissible: Boolean = false, modal: Boolean = false) {
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var auxiliary by remember(photo.id) { mutableStateOf(AuxiliaryView(status = "Loading recipe and HDR")) }
    var busy by remember(photo.id) { mutableStateOf(false) }
    LaunchedEffect(photo.id) { auxiliary = runCatching { callbacks.auxiliary.load(photo) }.getOrElse { AuxiliaryView(error = it.message ?: "Auxiliary read failed") } }
    LaunchedEffect(photo.id, modal) {
        if (modal) {
            callbacks.shortcutScopeFocused(false)
            focusRequester.requestFocus()
        }
    }
    fun runAux(operation: suspend () -> AuxiliaryView) {
        if (busy) return
        busy = true
        scope.launch { auxiliary = runCatching { operation() }.getOrElse { AuxiliaryView(error = it.message ?: "Operation failed") }; busy = false }
    }
    val modalKeyboard = if (modal) Modifier.focusRequester(focusRequester).onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { callbacks.dispatch(Action.ClearSelection); true } else false
    }.focusable() else Modifier
    Column(modifier.then(modalKeyboard).background(Panel).verticalScroll(rememberScrollState()).padding(18.dp).semantics { contentDescription = "Photo inspector" }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("INSPECTOR", Modifier.weight(1f), color = Muted, fontWeight = FontWeight.Bold)
            if (dismissible) TextButton(onClick = { callbacks.dispatch(Action.ClearSelection) }, modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).semantics { contentDescription = "Close photo inspector and clear selection" }) { Text("×") }
        }
        Text(photo.displayName, style = MaterialTheme.typography.titleMedium)
        photo.issue?.let { Text(it, color = Danger) }
        Text(if (photo.writable && state.writeEnabled) "Adjacent XMP editing enabled" else "Editing unavailable", color = Muted)
        Text("Flag"); QuickControls(photo, state, callbacks)
        Text("Rating")
        Row(Modifier.horizontalScroll(rememberScrollState())) { (0..5).forEach { rating -> TextButton(enabled = state.writeEnabled && photo.writable, onClick = { callbacks.mutate(photo, photo.editorial.copy(rating = rating)) }, modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).semantics { contentDescription = "Set rating to $rating stars"; selected = photo.editorial.rating == rating }) { Text(if (rating == 0) "0" else "$rating★") } } }
        Text("Label")
        Row { listOf<ColorLabel?>(null, ColorLabel.RED, ColorLabel.YELLOW, ColorLabel.GREEN).forEach { label -> TextButton(enabled = state.writeEnabled && photo.writable, onClick = { callbacks.mutate(photo, photo.editorial.copy(label = label)) }, modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).semantics { contentDescription = when (label) { null -> "No color label"; ColorLabel.RED -> "Red color label"; ColorLabel.YELLOW -> "Yellow color label"; ColorLabel.GREEN -> "Green color label" }; selected = photo.editorial.label == label }) { Text(label?.name?.take(1) ?: "N") } } }
        HorizontalDivider(); Text("Keywords")
        var keywordDraft by remember(photo.id) { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(keywordDraft, { keywordDraft = it }, singleLine = true, modifier = Modifier.weight(1f).onFocusChanged { callbacks.textFieldFocused(it.isFocused) }, label = { Text("New keyword") })
            TextButton(enabled = state.writeEnabled && photo.writable && keywordDraft.isNotBlank(), onClick = { runCatching { normalizeKeyword(keywordDraft) }.onSuccess { normalized -> callbacks.mutate(photo, photo.editorial.copy(keywords = photo.editorial.keywords + normalized)); keywordDraft = "" } }, modifier = Modifier.heightIn(min = 44.dp)) { Text("Add") }
        }
        photo.editorial.keywords.forEach { keyword -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(keyword, Modifier.weight(1f)); TextButton(enabled = state.writeEnabled && photo.writable, onClick = { callbacks.mutate(photo, photo.editorial.copy(keywords = photo.editorial.keywords - keyword)) }, modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).semantics { contentDescription = "Remove keyword $keyword" }) { Text("×") } } }
        HorizontalDivider(); ObservedMetadataPanel(photo, callbacks)
        HorizontalDivider(); FujiPanel(photo, state, auxiliary, busy, ::runAux, callbacks)
        HorizontalDivider(); HdrPanel(photo, state, auxiliary, busy, ::runAux, callbacks)
        auxiliary.error?.let { Text(it, color = Danger) } ?: Text(auxiliary.status, color = Muted)
        Text(when (photo.writeState) { WriteState.IDLE -> ""; WriteState.SAVING -> "Saving editorial changes"; WriteState.PERSISTED -> "Editorial changes persisted after readback"; WriteState.FAILED -> "Editorial write failed" }, color = if (photo.writeState == WriteState.FAILED) Danger else Muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "Editorial write status: ${photo.writeState.name.lowercase()}" })
        if (state.screen == Screen.GALLERY) Button(onClick = { callbacks.dispatch(Action.OpenDetail) }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Text("Open detail") }
    }
}

@Composable
private fun ObservedMetadataPanel(photo: Photo, callbacks: AppCallbacks) = photo.metadata.run {
    Text("OBSERVED METADATA", color = Muted, fontWeight = FontWeight.Bold)
    Text(cameraDisplay ?: "Camera unknown"); cameraMake?.let { Text("Make  $it") }; cameraModel?.let { Text("Model  $it") }
    Text(lens ?: "Lens unknown"); Text(capturedAt ?: "Capture date unknown")
    val exposureText = buildList { focalLength?.let { add("${it} mm") }; aperture?.let { add("f/$it") }; exposureSeconds?.let { add("${it} s") }; iso?.let { add("ISO $it") } }.joinToString("  ")
    if (exposureText.isNotEmpty()) Text(exposureText)
    if (width != null && height != null) Text("$width × $height")
    Text("Metadata ${status.name.lowercase()}${errorCode?.let { ": $it" }.orEmpty()}", color = if (status == MetadataStatus.ERROR) Danger else Muted)
    if (hasGps) OutlinedButton(onClick = { callbacks.openMap(latitude!!, longitude!!) }, modifier = Modifier.heightIn(min = 44.dp)) { Text("Open ${latitude}, ${longitude} in OSM") }
}

@Composable
private fun FujiPanel(photo: Photo, state: AppState, auxiliary: AuxiliaryView, busy: Boolean, runAux: (suspend () -> AuxiliaryView) -> Unit, callbacks: AppCallbacks) {
    Text("FUJI RECIPE", color = Muted, fontWeight = FontWeight.Bold)
    val recipe = auxiliary.fuji
    if (recipe == null) { Text("No exact-stem FP2 or FP3 profile", color = Muted); return }
    Text("${recipe.kind.uppercase()}  ${if (recipe.editable) "editable" else "read only"}")
    Text("${recipe.filmSimulation}  DR${recipe.dynamicRange}  ${recipe.grainEffect}")
    Text("Exposure ${recipe.exposureBias}  WB ${recipe.whiteBalance}  R${recipe.wbShiftR} B${recipe.wbShiftB}")
    Text("Highlight ${recipe.highlightTone}  Shadow ${recipe.shadowTone}  Color ${recipe.color}")
    Text("Sharpness ${recipe.sharpness}  NR ${recipe.noiseReduction}  LMO ${if (recipe.lensModulation) "ON" else "OFF"}")
    val enabled = state.writeEnabled && photo.writable && recipe.editable && !busy
    Row(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { runAux { callbacks.auxiliary.updateFuji(photo, mapOf("ExposureBias" to nextExposure(recipe.exposureBias, -1))) } }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text("Exposure −") }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = { runAux { callbacks.auxiliary.updateFuji(photo, mapOf("ExposureBias" to nextExposure(recipe.exposureBias, 1))) } }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text("Exposure +") }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        listOf(100, 200, 400).forEach { value -> OutlinedButton(onClick = { runAux { callbacks.auxiliary.updateFuji(photo, mapOf("DynamicRange" to value.toString())) } }, enabled = enabled && recipe.dynamicRange != value, modifier = Modifier.heightIn(min = 44.dp)) { Text("DR$value") } }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        listOf("Classic", "NEGAStd", "Astia").forEach { value -> OutlinedButton(onClick = { runAux { callbacks.auxiliary.updateFuji(photo, mapOf("FilmSimulation" to value)) } }, enabled = enabled && recipe.filmSimulation != value, modifier = Modifier.heightIn(min = 44.dp)) { Text(value) } }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        listOf("OFF", "WEAK", "STRONG").forEach { value -> OutlinedButton(onClick = { runAux { callbacks.auxiliary.updateFuji(photo, mapOf("GrainEffect" to value)) } }, enabled = enabled && recipe.grainEffect != value, modifier = Modifier.heightIn(min = 44.dp)) { Text("Grain $value") } }
    }
    FujiIntegerControl("WB R", "WBShiftR", recipe.wbShiftR, -9, 9, photo, enabled, runAux, callbacks)
    FujiIntegerControl("WB B", "WBShiftB", recipe.wbShiftB, -9, 9, photo, enabled, runAux, callbacks)
    FujiIntegerControl("Highlight", "HighlightTone", recipe.highlightTone, -4, 4, photo, enabled, runAux, callbacks)
    FujiIntegerControl("Shadow", "ShadowTone", recipe.shadowTone, -4, 4, photo, enabled, runAux, callbacks)
    FujiIntegerControl("Color", "Color", recipe.color, -4, 4, photo, enabled, runAux, callbacks)
    FujiIntegerControl("Sharpness", "Sharpness", recipe.sharpness, -4, 4, photo, enabled, runAux, callbacks)
    FujiIntegerControl("Noise reduction", "NoisReduction", recipe.noiseReduction, -4, 4, photo, enabled, runAux, callbacks)
    OutlinedButton(onClick = { runAux { callbacks.auxiliary.updateFuji(photo, mapOf("LensModulationOpt" to if (recipe.lensModulation) "OFF" else "ON")) } }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Text("Lens modulation ${if (recipe.lensModulation) "ON" else "OFF"}") }
    Row(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { runAux { callbacks.auxiliary.transferFujiToXmp(photo) } }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text("Fuji → XMP") }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = { runAux { callbacks.auxiliary.transferXmpToFuji(photo) } }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text("XMP → Fuji") }
    }
}

@Composable
private fun FujiIntegerControl(label: String, field: String, value: Int, minimum: Int, maximum: Int, photo: Photo, enabled: Boolean, runAux: (suspend () -> AuxiliaryView) -> Unit, callbacks: AppCallbacks) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$label  $value", Modifier.weight(1f))
        OutlinedButton(onClick = { runAux { callbacks.auxiliary.updateFuji(photo, mapOf(field to (value - 1).toString())) } }, enabled = enabled && value > minimum, modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp)) { Text("−") }
        Spacer(Modifier.width(4.dp))
        OutlinedButton(onClick = { runAux { callbacks.auxiliary.updateFuji(photo, mapOf(field to (value + 1).toString())) } }, enabled = enabled && value < maximum, modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp)) { Text("+") }
    }
}

private val exposureValues = (-9..9).map { thirds -> if (thirds == 0) "0" else { val sign = if (thirds > 0) "P" else "M"; val absolute = kotlin.math.abs(thirds); val whole = absolute / 3; val fraction = listOf("00", "33", "67")[absolute % 3]; "$sign${whole}P$fraction" } }
private fun nextExposure(value: String, delta: Int): String { val index = exposureValues.indexOf(value).takeIf { it >= 0 } ?: 9; return exposureValues[(index + delta).coerceIn(0, exposureValues.lastIndex)] }

@Composable
private fun HdrPanel(photo: Photo, state: AppState, auxiliary: AuxiliaryView, busy: Boolean, runAux: (suspend () -> AuxiliaryView) -> Unit, callbacks: AppCallbacks) {
    Text("LIGHTROOM HDR", color = Muted, fontWeight = FontWeight.Bold)
    val hdr = auxiliary.hdr
    Row(verticalAlignment = Alignment.CenterVertically) { Text(if (hdr.enabled) "Enabled" else "Disabled", Modifier.weight(1f)); Switch(hdr.enabled, { enabled -> runAux { callbacks.auxiliary.updateHdr(photo, if (enabled) HdrView(true, hdr.maximum, hdr.controls) else HdrView(false)) } }, enabled = state.writeEnabled && photo.writable && !busy) }
    if (hdr.enabled) {
        Text("HDR maximum ${hdr.maximum}")
        listOf("SDRBrightness", "SDRContrast", "SDRClarity", "SDRHighlights", "SDRShadows", "SDRWhites", "SDRBlend").forEach { name ->
            val value = hdr.controls[name] ?: 0
            Text("$name  $value")
            Slider(value.toFloat(), { changed -> runAux { callbacks.auxiliary.updateHdr(photo, hdr.copy(controls = hdr.controls + (name to changed.toInt()))) } }, valueRange = -100f..100f, enabled = state.writeEnabled && photo.writable && !busy, modifier = Modifier.semantics { contentDescription = name })
        }
    }
}

@Composable
private fun DetailScreen(state: AppState, callbacks: AppCallbacks) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val photo = state.selected ?: return@BoxWithConstraints
    val visibleIndex = state.visiblePhotos.indexOfFirst { it.id == photo.id }
    val previousAvailable = visibleIndex > 0
    val nextAvailable = visibleIndex >= 0 && visibleIndex < state.visiblePhotos.lastIndex
    if (maxWidth >= 900.dp) {
        Row(Modifier.fillMaxSize()) {
            DetailImageArea(photo, callbacks, Modifier.weight(1f).fillMaxHeight(), previousAvailable, nextAvailable)
            Inspector(photo, state, callbacks, Modifier.width(350.dp).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            DetailImageArea(photo, callbacks, Modifier.weight(1f).fillMaxWidth(), previousAvailable, nextAvailable)
            Inspector(photo, state, callbacks, Modifier.fillMaxWidth().heightIn(max = 390.dp))
        }
    }
}

@Composable
private fun DetailImageArea(photo: Photo, callbacks: AppCallbacks, modifier: Modifier, previousAvailable: Boolean = true, nextAvailable: Boolean = true) = Column(modifier.background(Ink).padding(16.dp).focusable().onFocusChanged { callbacks.shortcutScopeFocused(it.hasFocus) }.semantics { contentDescription = "Detail photograph viewport" }) {
    TextButton(onClick = { callbacks.dispatch(Action.CloseDetail) }, modifier = Modifier.heightIn(min = 44.dp)) { Text("‹ Gallery") }
    PhotoImage(photo, callbacks, 2048, ContentScale.Fit, Modifier.weight(1f).fillMaxWidth().background(Color.Black))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { callbacks.navigate(-1) }, enabled = previousAvailable, modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "Previous photograph" }) { Text("Previous") }
        Text(photo.displayName, modifier = Modifier.align(Alignment.CenterVertically))
        TextButton(onClick = { callbacks.navigate(1) }, enabled = nextAvailable, modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "Next photograph" }) { Text("Next") }
    }
}

@Composable
private fun SettingsScreen(state: AppState, callbacks: AppCallbacks) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = { callbacks.dispatch(Action.CloseSettings) }, modifier = Modifier.heightIn(min = 44.dp)) { Text("‹ Gallery") }; Text("SETTINGS", style = MaterialTheme.typography.titleLarge) }
        Text("Library", color = Muted); Text(state.library ?: "Not selected")
        Button(onClick = callbacks.chooseLibrary, enabled = !state.sync.running, modifier = Modifier.heightIn(min = 44.dp)) { Text("Choose library folder") }
        Text("Cache", color = Muted); Text(state.cache ?: "Not configured")
        Text("Mode", color = Muted); Text(if (state.writeEnabled) "XMP sidecar writes enabled by launch argument" else "Read only")
        HorizontalDivider(); Text("Synchronization", style = MaterialTheme.typography.titleMedium)
        Text("${state.sync.phase}: ${state.sync.message}")
        Text("Directories ${state.sync.directories}  Files ${state.sync.files}  Photos ${state.sync.photos}  Errors ${state.sync.errors}", color = Muted)
        if (state.sync.running) { LinearProgressIndicator(Modifier.fillMaxWidth()); OutlinedButton(onClick = callbacks.cancelSync, modifier = Modifier.heightIn(min = 44.dp)) { Text("Cancel") } }
        else Button(onClick = callbacks.synchronize, enabled = state.library != null, modifier = Modifier.heightIn(min = 44.dp)) { Text("Synchronize") }
        HorizontalDivider(); Text("Safety", style = MaterialTheme.typography.titleMedium)
        Text("Synchronization is read-only. PhotoTool never changes RAW or JPEG bytes. Explicit write mode changes adjacent XMP and editable FP2 only after identity, topology, byte, and readback checks.", color = Muted)
    }
}

@Composable private fun EmptyLibrary(callbacks: AppCallbacks) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("Choose a photo library to begin", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(12.dp)); Button(onClick = callbacks.chooseLibrary, modifier = Modifier.heightIn(min = 44.dp)) { Text("Choose folder") } } }
@Composable private fun EmptyGallery(state: AppState) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (state.photos.isEmpty()) "No indexed photographs. Synchronize from Settings." else "No photographs match these filters.", color = Muted) }
@Composable private fun ShortcutHelp(close: () -> Unit) { AlertDialog(onDismissRequest = close, confirmButton = { TextButton(onClick = close) { Text("Close") } }, title = { Text("Keyboard shortcuts") }, text = { Text("Gallery: arrows select, Enter opens detail, Escape closes panels.\nDetail: ←/→ previous/next, P/U/X flags, 0-5 ratings, N/R/Y/G labels.\nShortcuts ignore focused text fields and modifier combinations.") }) }
