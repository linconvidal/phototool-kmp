package br.com.lincon.phototool.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag as FilledFlagVector
import androidx.compose.material.icons.filled.Star as FilledStarVector
import androidx.compose.material.icons.outlined.Add as AddVector
import androidx.compose.material.icons.outlined.ArrowBack as ArrowBackVector
import androidx.compose.material.icons.outlined.AspectRatio as AspectRatioVector
import androidx.compose.material.icons.outlined.CalendarMonth as CalendarMonthVector
import androidx.compose.material.icons.outlined.CameraAlt as CameraAltVector
import androidx.compose.material.icons.outlined.Check as CheckVector
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank as CheckBoxVector
import androidx.compose.material.icons.outlined.ChevronLeft as ChevronLeftVector
import androidx.compose.material.icons.outlined.ChevronRight as ChevronRightVector
import androidx.compose.material.icons.outlined.Clear as ClearVector
import androidx.compose.material.icons.outlined.Close as CloseVector
import androidx.compose.material.icons.outlined.DoneAll as DoneAllVector
import androidx.compose.material.icons.outlined.ErrorOutline as ErrorOutlineVector
import androidx.compose.material.icons.outlined.Flag as FlagVector
import androidx.compose.material.icons.outlined.FitScreen as FitScreenVector
import androidx.compose.material.icons.outlined.Folder as FolderVector
import androidx.compose.material.icons.outlined.HelpOutline as HelpOutlineVector
import androidx.compose.material.icons.outlined.ImageSearch as ImageSearchVector
import androidx.compose.material.icons.outlined.Lens as LensVector
import androidx.compose.material.icons.outlined.LocationOn as LocationOnVector
import androidx.compose.material.icons.outlined.MoreHoriz as MoreHorizVector
import androidx.compose.material.icons.outlined.PhotoLibrary as PhotoLibraryVector
import androidx.compose.material.icons.outlined.Refresh as RefreshVector
import androidx.compose.material.icons.outlined.Remove as RemoveVector
import androidx.compose.material.icons.outlined.Search as SearchVector
import androidx.compose.material.icons.outlined.SelectAll as SelectAllVector
import androidx.compose.material.icons.outlined.Settings as SettingsVector
import androidx.compose.material.icons.outlined.ShutterSpeed as ShutterSpeedVector
import androidx.compose.material.icons.outlined.StarBorder as StarBorderVector
import androidx.compose.material.icons.outlined.Tag as TagVector
import androidx.compose.material.icons.outlined.Tune as TuneVector
import androidx.compose.material.icons.outlined.ZoomIn as ZoomInVector
import androidx.compose.material.icons.outlined.ZoomOut as ZoomOutVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*

import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.lincon.phototool.domain.*
import br.com.lincon.phototool.state.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val Ink = Color(0xff100d0a)
private val Stage = Color(0xff070504)
private val Panel = Color(0xff17120e)
private val Inspector = Color(0xff1d1711)
private val Raised = Color(0xff261e17)
private val RaisedHover = Color(0xff33271d)
private val Hairline = Color(0xff4a3a2b)
private val Amber = Color(0xffd7a33c)
private val AmberWash = Color(0xff352817)
private val ProofPaper = Color(0xff1b1510)
private val TextPrimary = Color(0xfff1e8db)
private val Muted = Color(0xffb5a895)
private val Danger = Color(0xffd46e59)
private val Focus = Color(0xfff1ca6a)
private val PickGreen = Color(0xff91aa7f)
private val LabelRed = Color(0xffcc695d)
private val LabelYellow = Color(0xffd1aa4b)
private val LabelGreen = Color(0xff88a17e)
private val CompactShape = RoundedCornerShape(4.dp)
private val ProofAnnotationStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 15.sp, letterSpacing = .4.sp, fontWeight = FontWeight.Medium)

private val Add: ImageVector get() = Icons.Outlined.AddVector
private val ArrowBack: ImageVector get() = Icons.Outlined.ArrowBackVector
private val AspectRatio: ImageVector get() = Icons.Outlined.AspectRatioVector
private val CalendarMonth: ImageVector get() = Icons.Outlined.CalendarMonthVector
private val CameraAlt: ImageVector get() = Icons.Outlined.CameraAltVector
private val Check: ImageVector get() = Icons.Outlined.CheckVector
private val CheckBoxOutlineBlank: ImageVector get() = Icons.Outlined.CheckBoxVector
private val ChevronLeft: ImageVector get() = Icons.Outlined.ChevronLeftVector
private val ChevronRight: ImageVector get() = Icons.Outlined.ChevronRightVector
private val Clear: ImageVector get() = Icons.Outlined.ClearVector
private val Close: ImageVector get() = Icons.Outlined.CloseVector
private val DoneAll: ImageVector get() = Icons.Outlined.DoneAllVector
private val ErrorOutline: ImageVector get() = Icons.Outlined.ErrorOutlineVector
private val FilledFlag: ImageVector get() = Icons.Filled.FilledFlagVector
private val FlagIcon: ImageVector get() = Icons.Outlined.FlagVector
private val PickFlag: ImageVector by lazy {
    ImageVector.Builder("PickFlag", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(14.4f, 6f); lineTo(14f, 4f); lineTo(5f, 4f); lineTo(5f, 21f); lineTo(7f, 21f); lineTo(7f, 14f); lineTo(12.6f, 14f); lineTo(13f, 16f); lineTo(20f, 16f); lineTo(20f, 6f); close()
            moveTo(8.6f, 9.7f); lineTo(9.7f, 8.6f); lineTo(11.3f, 10.2f); lineTo(14.7f, 6.8f); lineTo(15.8f, 7.9f); lineTo(11.3f, 12.4f); close()
        }
    }.build()
}
private val RejectFlag: ImageVector by lazy {
    ImageVector.Builder("RejectFlag", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(14.4f, 6f); lineTo(14f, 4f); lineTo(5f, 4f); lineTo(5f, 21f); lineTo(7f, 21f); lineTo(7f, 14f); lineTo(12.6f, 14f); lineTo(13f, 16f); lineTo(20f, 16f); lineTo(20f, 6f); close()
            moveTo(9f, 8f); lineTo(10.1f, 6.9f); lineTo(12.4f, 9.2f); lineTo(14.7f, 6.9f); lineTo(15.8f, 8f); lineTo(13.5f, 10.3f); lineTo(15.8f, 12.6f); lineTo(14.7f, 13.7f); lineTo(12.4f, 11.4f); lineTo(10.1f, 13.7f); lineTo(9f, 12.6f); lineTo(11.3f, 10.3f); close()
        }
    }.build()
}
private val FitScreen: ImageVector get() = Icons.Outlined.FitScreenVector
private val Folder: ImageVector get() = Icons.Outlined.FolderVector
private val HelpOutline: ImageVector get() = Icons.Outlined.HelpOutlineVector
private val ImageSearch: ImageVector get() = Icons.Outlined.ImageSearchVector
private val Lens: ImageVector get() = Icons.Outlined.LensVector
private val LocationOn: ImageVector get() = Icons.Outlined.LocationOnVector
private val MoreHoriz: ImageVector get() = Icons.Outlined.MoreHorizVector
private val PhotoLibrary: ImageVector get() = Icons.Outlined.PhotoLibraryVector
private val RefreshVectorIcon: ImageVector get() = Icons.Outlined.RefreshVector
private val Remove: ImageVector get() = Icons.Outlined.RemoveVector
private val Search: ImageVector get() = Icons.Outlined.SearchVector
private val SelectAll: ImageVector get() = Icons.Outlined.SelectAllVector
private val Settings: ImageVector get() = Icons.Outlined.SettingsVector
private val ShutterSpeed: ImageVector get() = Icons.Outlined.ShutterSpeedVector
private val Star: ImageVector get() = Icons.Filled.FilledStarVector
private val StarBorder: ImageVector get() = Icons.Outlined.StarBorderVector
private val Tag: ImageVector get() = Icons.Outlined.TagVector
private val Tune: ImageVector get() = Icons.Outlined.TuneVector
private val ZoomIn: ImageVector get() = Icons.Outlined.ZoomInVector
private val ZoomOut: ImageVector get() = Icons.Outlined.ZoomOutVector

@Composable
fun PhotoToolTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Amber,
            onPrimary = Ink,
            background = Ink,
            onBackground = TextPrimary,
            surface = Panel,
            onSurface = TextPrimary,
            surfaceVariant = Raised,
            onSurfaceVariant = Muted,
            outline = Hairline,
            error = Danger,
        ),
        typography = Typography(
            bodyLarge = Typography().bodyLarge.copy(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp),
            bodyMedium = Typography().bodyMedium.copy(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = Typography().bodySmall.copy(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp),
            labelLarge = Typography().labelLarge.copy(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            labelMedium = Typography().labelMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = .45.sp),
            labelSmall = Typography().labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = .35.sp),
            titleMedium = Typography().titleMedium.copy(fontFamily = FontFamily.SansSerif, fontSize = 18.sp, letterSpacing = (-.2).sp, fontWeight = FontWeight.SemiBold),
            titleLarge = Typography().titleLarge.copy(fontFamily = FontFamily.SansSerif, fontSize = 23.sp, letterSpacing = (-.35).sp, fontWeight = FontWeight.SemiBold),
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(3.dp),
            small = RoundedCornerShape(5.dp),
            medium = CompactShape,
            large = RoundedCornerShape(7.dp),
            extraLarge = RoundedCornerShape(5.dp),
        ),
        content = content,
    )
}

@Composable
private fun PhotoToolMark(modifier: Modifier = Modifier) = Canvas(modifier.semantics { contentDescription = "PhotoTool" }) {
    val stroke = 1.6.dp.toPx()
    val left = size.width * .12f
    val top = size.height * .22f
    val right = size.width * .88f
    val bottom = size.height * .78f
    drawRect(Amber, topLeft = Offset(left, top), size = androidx.compose.ui.geometry.Size(right - left, bottom - top), style = Stroke(stroke))
    val corner = size.minDimension * .16f
    listOf(
        Offset(left + stroke, top + corner) to Offset(left + stroke, top + stroke),
        Offset(left + stroke, top + stroke) to Offset(left + corner, top + stroke),
        Offset(right - corner, top + stroke) to Offset(right - stroke, top + stroke),
        Offset(right - stroke, top + stroke) to Offset(right - stroke, top + corner),
        Offset(left + stroke, bottom - corner) to Offset(left + stroke, bottom - stroke),
        Offset(left + stroke, bottom - stroke) to Offset(left + corner, bottom - stroke),
        Offset(right - corner, bottom - stroke) to Offset(right - stroke, bottom - stroke),
        Offset(right - stroke, bottom - stroke) to Offset(right - stroke, bottom - corner),
    ).forEach { (start, end) -> drawLine(Amber, start, end, strokeWidth = stroke) }
    drawCircle(Amber, radius = size.minDimension * .045f, center = Offset(size.width * .76f, size.height * .50f))
}

@Composable
private fun AppIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(CompactShape)
            .background(when { selected -> AmberWash; pressed -> RaisedHover; hovered -> Raised; else -> Color.Transparent })
            .then(when { focused -> Modifier.border(2.dp, Focus, CompactShape); selected -> Modifier.border(1.dp, Amber.copy(alpha = .66f), CompactShape); hovered -> Modifier.border(1.dp, Hairline, CompactShape); else -> Modifier })
            .hoverable(interaction, enabled)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = when { !enabled -> Muted.copy(alpha = .42f); selected -> Amber; else -> TextPrimary }, modifier = Modifier.size(20.dp))
    }
}

private fun labelColor(label: ColorLabel?): Color = when (label) {
    ColorLabel.RED -> LabelRed
    ColorLabel.YELLOW -> LabelYellow
    ColorLabel.GREEN -> LabelGreen
    null -> Muted
}

enum class SpatialDirection { LEFT, RIGHT, UP, DOWN }

data class SpatialBounds(val id: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    fun translated(deltaY: Float) = copy(top = top + deltaY, bottom = bottom + deltaY)
}

fun spatialNeighbor(current: SpatialBounds, candidates: Collection<SpatialBounds>, direction: SpatialDirection): String? {
    fun intervalGap(firstStart: Float, firstEnd: Float, secondStart: Float, secondEnd: Float): Float = when {
        firstEnd < secondStart -> secondStart - firstEnd
        secondEnd < firstStart -> firstStart - secondEnd
        else -> 0f
    }

    return candidates.asSequence().filter { candidate ->
        candidate.id != current.id && when (direction) {
            SpatialDirection.LEFT -> candidate.centerX < current.centerX
            SpatialDirection.RIGHT -> candidate.centerX > current.centerX
            SpatialDirection.UP -> candidate.centerY < current.centerY
            SpatialDirection.DOWN -> candidate.centerY > current.centerY
        }
    }.map { candidate ->
        val primaryGap = when (direction) {
            SpatialDirection.LEFT -> (current.left - candidate.right).coerceAtLeast(0f)
            SpatialDirection.RIGHT -> (candidate.left - current.right).coerceAtLeast(0f)
            SpatialDirection.UP -> (current.top - candidate.bottom).coerceAtLeast(0f)
            SpatialDirection.DOWN -> (candidate.top - current.bottom).coerceAtLeast(0f)
        }
        val perpendicularGap = when (direction) {
            SpatialDirection.LEFT, SpatialDirection.RIGHT -> intervalGap(current.top, current.bottom, candidate.top, candidate.bottom)
            SpatialDirection.UP, SpatialDirection.DOWN -> intervalGap(current.left, current.right, candidate.left, candidate.right)
        }
        val centerDistance = when (direction) {
            SpatialDirection.LEFT, SpatialDirection.RIGHT -> kotlin.math.abs(candidate.centerY - current.centerY)
            SpatialDirection.UP, SpatialDirection.DOWN -> kotlin.math.abs(candidate.centerX - current.centerX)
        }
        Triple(candidate.id, primaryGap + perpendicularGap * 4f, centerDistance)
    }.minWithOrNull(compareBy<Triple<String, Float, Float>> { it.second }.thenBy { it.third }.thenBy { it.first })?.first
}

/** Bounded packed-layout estimate used only when the next card is not composed yet. */
fun estimatedMasonryNeighbor(photos: List<Photo>, currentId: String, columns: Int, direction: SpatialDirection): String? {
    if (photos.isEmpty() || columns <= 0) return null
    val heights = FloatArray(columns)
    val bounds = photos.map { photo ->
        val lane = heights.indices.minWithOrNull(compareBy<Int> { heights[it] }.thenBy { it }) ?: 0
        val left = lane * 1.03f
        val top = heights[lane]
        val height = 1f / photo.aspectRatio.coerceIn(1f / 3f, 6f) + .22f
        heights[lane] = top + height + .03f
        SpatialBounds(photo.id, left, top, left + 1f, top + height)
    }
    val current = bounds.firstOrNull { it.id == currentId } ?: return null
    return spatialNeighbor(current, bounds, direction)
}

private const val MIN_DETAIL_ZOOM = 1f
private const val MAX_DETAIL_ZOOM = 8f
private val detailZoomStops = listOf(1f, 1.25f, 1.5f, 2f, 3f, 4f, 6f, 8f)

internal fun nextDetailZoom(current: Float, direction: Int): Float = when {
    direction > 0 -> detailZoomStops.firstOrNull { it > current + .01f } ?: MAX_DETAIL_ZOOM
    direction < 0 -> detailZoomStops.lastOrNull { it < current - .01f } ?: MIN_DETAIL_ZOOM
    else -> current.coerceIn(MIN_DETAIL_ZOOM, MAX_DETAIL_ZOOM)
}

internal fun focalDetailPan(pan: Offset, currentZoom: Float, targetZoom: Float, focalPoint: Offset, viewport: IntSize): Offset {
    if (currentZoom <= 0f || viewport.width <= 0 || viewport.height <= 0) return pan
    val ratio = targetZoom / currentZoom
    val center = Offset(viewport.width / 2f, viewport.height / 2f)
    return pan * ratio + (focalPoint - center) * (1f - ratio)
}

internal fun constrainDetailPan(offset: Offset, zoom: Float, viewport: IntSize, image: IntSize): Offset {
    if (zoom <= MIN_DETAIL_ZOOM || viewport.width <= 0 || viewport.height <= 0 || image.width <= 0 || image.height <= 0) return Offset.Zero
    val fitScale = minOf(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
    val fittedWidth = image.width * fitScale
    val fittedHeight = image.height * fitScale
    val maximumX = ((fittedWidth * zoom - viewport.width) / 2f).coerceAtLeast(0f)
    val maximumY = ((fittedHeight * zoom - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-maximumX, maximumX), offset.y.coerceIn(-maximumY, maximumY))
}

data class AppCallbacks(
    val dispatch: (Action) -> Unit,
    val chooseLibrary: () -> Unit = {},
    val synchronize: () -> Unit = {},
    val cancelSync: () -> Unit = {},
    val setReadOnlyMode: (Boolean) -> Unit = {},
    val mutate: (Photo, EditorialState) -> Unit = { _, _ -> },
    val mutateFlag: (Photo, Flag) -> Unit = { photo, flag -> mutate(photo, photo.editorial.copy(flag = flag)) },
    val batchMutate: (List<Photo>, BatchEdit) -> Unit = { _, _ -> },
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
    Surface(modifier.fillMaxSize().semantics { contentDescription = "Aplicativo PhotoTool" }, color = Ink) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Panel.copy(alpha = .34f), Ink, Stage)))) {
            val appWidth = maxWidth
            val appHeight = maxHeight
            val phonePortrait = appWidth < 600.dp
            when {
                state.screen == Screen.DETAIL -> DetailScreen(state, callbacks)
                phonePortrait -> Column {
                    LibraryWorkspace(state, callbacks, Modifier.weight(1f), inlineFilters = false)
                    CompactNavigationBar(state, callbacks)
                }
                else -> Row {
                    LibraryRail(state, callbacks)
                    LibraryWorkspace(state, callbacks, Modifier.weight(1f), inlineFilters = true)
                }
            }
            if (phonePortrait && state.screen == Screen.GALLERY && state.filtersOpen) {
                val modalInteraction = remember { MutableInteractionSource() }
                Box(
                    Modifier.matchParentSize().background(Color.Black.copy(alpha = .76f))
                        .clickable(interactionSource = modalInteraction, indication = null) {}
                        .semantics { contentDescription = "Filtros modais abertos" },
                ) {
                    FilterPanel(state, callbacks, appWidth, appHeight, Modifier.align(Alignment.Center))
                }
            }
        }
        if (state.helpOpen) ShortcutHelp { callbacks.dispatch(Action.ToggleHelp) }
    }
}

@Composable
private fun LibraryWorkspace(state: AppState, callbacks: AppCallbacks, modifier: Modifier = Modifier, inlineFilters: Boolean) {
    var gallerySearchFocused by remember { mutableStateOf(false) }
    Column(modifier) {
        if (state.screen != Screen.SETTINGS) LibraryToolbar(state, callbacks) { gallerySearchFocused = it }
        when (state.screen) {
        Screen.GALLERY -> GalleryScreen(state, callbacks, inlineFilters, gallerySearchFocused)
        Screen.CALENDAR -> CalendarScreen(state, callbacks)
        Screen.FOLDERS -> FoldersScreen(state, callbacks)
        Screen.SETTINGS -> SettingsScreen(state, callbacks)
        Screen.DETAIL -> Unit
        }
    }
}

@Composable
private fun CompactNavigationBar(state: AppState, callbacks: AppCallbacks) {
    var moreOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(Panel).border(1.dp, Hairline),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            Triple(LibrarySection.ALL_PHOTOS, PhotoLibrary, "Todas as fotos"),
            Triple(LibrarySection.CALENDAR, CalendarMonth, "Calendário"),
            Triple(LibrarySection.FOLDERS, Folder, "Pastas"),
            Triple(LibrarySection.PICKS, PickFlag, "Escolhidas"),
        ).forEach { (section, icon, label) ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                AppIconButton(icon, label, { callbacks.dispatch(Action.Navigate(section)) }, selected = state.section == section && state.screen != Screen.SETTINGS)
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            AppIconButton(MoreHoriz, "Mais opções", { moreOpen = true }, selected = moreOpen || state.section == LibrarySection.LATEST || state.screen == Screen.SETTINGS)
            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }, containerColor = Raised) {
                DropdownMenuItem(
                    text = { Text("Recentes") },
                    leadingIcon = { Icon(ImageSearch, null) },
                    onClick = { moreOpen = false; callbacks.dispatch(Action.Navigate(LibrarySection.LATEST)) },
                )
                DropdownMenuItem(
                    text = { Text("Ajuda de atalhos") },
                    leadingIcon = { Icon(HelpOutline, null) },
                    onClick = { moreOpen = false; callbacks.dispatch(Action.ToggleHelp) },
                )
                DropdownMenuItem(
                    text = { Text("Configurações") },
                    leadingIcon = { Icon(Settings, null) },
                    onClick = { moreOpen = false; callbacks.dispatch(Action.OpenSettings) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RailIconButton(icon: ImageVector, description: String, onClick: () -> Unit, selected: Boolean = false) = TooltipBox(
    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
    tooltip = { PlainTooltip(containerColor = Raised, contentColor = TextPrimary) { Text(description) } },
    state = rememberTooltipState(),
) {
    AppIconButton(icon, description, onClick, Modifier.fillMaxWidth(), selected = selected)
}

@Composable
private fun LibraryRail(state: AppState, callbacks: AppCallbacks) = BoxWithConstraints(
    Modifier.fillMaxHeight().width(58.dp).background(Panel).border(width = 1.dp, color = Hairline.copy(alpha = .55f)),
) {
    val compact = maxHeight < 520.dp
    val railScroll = rememberScrollState()
    LaunchedEffect(compact, state.screen) {
        if (compact && state.screen == Screen.SETTINGS) railScroll.scrollTo(Int.MAX_VALUE)
        else if (compact) railScroll.scrollTo(0)
    }
    val railModifier = if (compact) Modifier.fillMaxSize().verticalScroll(railScroll) else Modifier.fillMaxSize()
    Column(
        railModifier.padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            PhotoToolMark(Modifier.size(29.dp))
        }
        Spacer(Modifier.height(if (compact) 5.dp else 16.dp))
        val entries = listOf(
            Triple(LibrarySection.ALL_PHOTOS, PhotoLibrary, "Todas as fotos"),
            Triple(LibrarySection.CALENDAR, CalendarMonth, "Calendário"),
            Triple(LibrarySection.FOLDERS, Folder, "Pastas"),
            Triple(LibrarySection.PICKS, PickFlag, "Escolhidas"),
            Triple(LibrarySection.LATEST, ImageSearch, "Recentes"),
        )
        entries.forEach { (section, icon, label) ->
            val active = state.section == section && state.screen != Screen.SETTINGS
            RailIconButton(icon, label, { callbacks.dispatch(Action.Navigate(section)) }, selected = active)
            Spacer(Modifier.height(if (compact) 1.dp else 3.dp))
        }
        if (compact) Spacer(Modifier.height(5.dp)) else Spacer(Modifier.weight(1f))
        RailIconButton(HelpOutline, "Ajuda de atalhos", { callbacks.dispatch(Action.ToggleHelp) })
        Spacer(Modifier.height(if (compact) 1.dp else 3.dp))
        RailIconButton(Settings, "Configurações", { callbacks.dispatch(Action.OpenSettings) }, selected = state.screen == Screen.SETTINGS)
        Box(
            Modifier.padding(top = if (compact) 4.dp else 8.dp).size(8.dp).background(if (state.writeEnabled) PickGreen else Muted, RoundedCornerShape(4.dp))
                .semantics { contentDescription = if (state.writeEnabled) "Edição XMP ativada" else "Somente leitura" },
        )
    }
}

@Composable
private fun LibraryToolbar(state: AppState, callbacks: AppCallbacks, searchFocusChanged: (Boolean) -> Unit) = Column(Modifier.fillMaxWidth().background(Panel)) {
    if (state.screen != Screen.GALLERY) {
        Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.screen == Screen.CALENDAR) "Calendário" else "Pastas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            Text(photoCount(state.photos.size), color = Muted, style = ProofAnnotationStyle)
        }
        return@Column
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val toolbarWidth = maxWidth
        val narrow = toolbarWidth < 700.dp
        if (narrow) Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
            GallerySearch(state, callbacks, Modifier.fillMaxWidth(), searchFocusChanged)
            GalleryToolbarControls(state, callbacks, Modifier.fillMaxWidth(), compact = true, showSummary = true)
        } else Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.widthIn(min = 126.dp, max = 154.dp)) {
                Text(sectionTitle(state.section), fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(photoCount(state.visiblePhotos.size), color = Muted, style = ProofAnnotationStyle)
            }
            Spacer(Modifier.width(10.dp))
            GallerySearch(state, callbacks, Modifier.widthIn(min = 180.dp, max = 420.dp).weight(1f), searchFocusChanged)
            GalleryToolbarControls(state, callbacks, compact = toolbarWidth < 900.dp)
        }
    }
    ActiveFilters(state, callbacks)
}

@Composable
private fun ProofTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    focusChanged: (Boolean) -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor = when {
        !enabled -> Hairline.copy(alpha = .42f)
        focused -> Amber
        else -> Hairline.copy(alpha = .78f)
    }
    val textColor = if (enabled) TextPrimary else Muted.copy(alpha = .55f)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        interactionSource = interaction,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        cursorBrush = SolidColor(Amber),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor, fontFamily = FontFamily.Monospace, letterSpacing = .15.sp),
        modifier = modifier.height(56.dp).clip(RoundedCornerShape(2.dp))
            .background(if (focused) ProofPaper else Stage)
            .border(if (focused) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(2.dp))
            .onFocusChanged { focusChanged(it.isFocused) },
        decorationBox = { innerField ->
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(if (focused) 3.dp else 2.dp).fillMaxHeight().background(if (focused) Amber else Hairline.copy(alpha = .52f)))
                leadingIcon?.let { icon ->
                    Box(Modifier.width(40.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = if (focused) Amber else Muted, modifier = Modifier.size(18.dp))
                    }
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().padding(start = if (leadingIcon == null) 12.dp else 2.dp, end = 12.dp, top = 6.dp, bottom = 5.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(label, color = if (focused) Amber else Muted, style = ProofAnnotationStyle.copy(fontSize = 11.sp, lineHeight = 12.sp, letterSpacing = .55.sp), maxLines = 1)
                    Box(Modifier.fillMaxWidth().heightIn(min = 20.dp), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(placeholder, color = Muted.copy(alpha = if (enabled) .72f else .42f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        innerField()
                    }
                }
            }
        },
    )
}

@Composable
private fun GallerySearch(state: AppState, callbacks: AppCallbacks, modifier: Modifier, focusChanged: (Boolean) -> Unit) {
    ProofTextField(
        value = state.query.search,
        onValueChange = { callbacks.dispatch(Action.SetQuery(state.query.copy(search = it))) },
        label = "BUSCA",
        placeholder = "nome · câmera · lente · -termo exclui",
        leadingIcon = Search,
        modifier = modifier.semantics { contentDescription = "Pesquisar fotografias" },
        focusChanged = {
            focusChanged(it)
            callbacks.textFieldFocused(it)
        },
    )
}

@Composable
private fun GalleryToolbarControls(state: AppState, callbacks: AppCallbacks, modifier: Modifier = Modifier, compact: Boolean, showSummary: Boolean = false) {
    Row(modifier.height(if (showSummary) 52.dp else 46.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
        if (showSummary) Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(sectionTitle(state.section), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(photoCount(state.visiblePhotos.size), color = Muted, style = ProofAnnotationStyle)
        }
        OutlinedButton(
            onClick = { callbacks.dispatch(Action.ToggleFilters) },
            shape = CompactShape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (state.filtersOpen) Amber else TextPrimary),
            border = BorderStroke(1.dp, if (state.filtersOpen) Amber else Hairline),
            contentPadding = PaddingValues(horizontal = if (compact) 12.dp else 15.dp),
            modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "Filtros" },
        ) {
            Icon(Tune, null, Modifier.size(18.dp)); if (!compact) { Spacer(Modifier.width(7.dp)); Text("Filtros") }
        }
        Spacer(Modifier.width(5.dp))
        if (!state.selectionModeActive) AppIconButton(SelectAll, "Selecionar fotografias", { callbacks.dispatch(Action.ToggleSelectionMode) })
        if (!compact) Column(Modifier.padding(horizontal = 8.dp), horizontalAlignment = Alignment.End) {
            Text("ORDEM", color = Amber, style = ProofAnnotationStyle)
            Text(if (state.query.sort == PhotoSort.CAPTURE_TIME) "CAPTURA" else "ADIÇÃO", color = Muted, style = ProofAnnotationStyle)
        }
        AppIconButton(Remove, "Miniaturas menores", { callbacks.dispatch(Action.SetThumbnailSize(state.thumbnailSize - 20)) })
        AppIconButton(Add, "Miniaturas maiores", { callbacks.dispatch(Action.SetThumbnailSize(state.thumbnailSize + 20)) })
    }
}

private fun photoCount(count: Int): String = "$count ${if (count == 1) "foto" else "fotos"}"
private fun selectedPhotoCount(count: Int): String = "$count ${if (count == 1) "selecionada" else "selecionadas"}"
private fun editablePhotoCount(count: Int): String = "$count ${if (count == 1) "editável" else "editáveis"}"

private fun sectionTitle(section: LibrarySection): String = when (section) {
    LibrarySection.ALL_PHOTOS -> "Todas as fotos"
    LibrarySection.CALENDAR -> "Calendário"
    LibrarySection.FOLDERS -> "Pastas"
    LibrarySection.PICKS -> "Escolhidas"
    LibrarySection.LATEST -> "Recentes"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveFilters(state: AppState, callbacks: AppCallbacks) {
    val chips = buildList<Pair<String, Query.() -> Query>> {
        state.query.folder?.let { add("Pasta: ${it.substringAfterLast('/')}" to { copy(folder = null) }) }
        if (state.query.fromDate != null || state.query.toDate != null) {
            val label = when {
                state.query.fromDate != null && state.query.toDate != null -> "${state.query.fromDate} a ${state.query.toDate}"
                state.query.fromDate != null -> "Desde ${state.query.fromDate}"
                else -> "Até ${state.query.toDate}"
            }
            add(label to { copy(fromDate = null, toDate = null) })
        }
        state.query.flag?.let { add("Flag: ${when (it) { Flag.PICK -> "escolhida"; Flag.UNFLAGGED -> "sem flag"; Flag.REJECT -> "rejeitada" }}" to { copy(flag = null) }) }
        if (state.query.minimumStars > 0) add("${state.query.minimumStars}+ estrelas" to { copy(minimumStars = 0) })
        state.query.keyword?.let { add("Palavra-chave: $it" to { copy(keyword = null) }) }
        state.query.camera?.let { add("Câmera: $it" to { copy(camera = null) }) }
        state.query.lens?.let { add("Lente: $it" to { copy(lens = null) }) }
        if (state.query.gps != GpsFilter.ANY) add("GPS: ${if (state.query.gps == GpsFilter.PRESENT) "presente" else "ausente"}" to { copy(gps = GpsFilter.ANY) })
    }
    if (chips.isEmpty()) return
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { (label, transform) -> InputChip(
            selected = true,
            shape = RoundedCornerShape(2.dp),
            onClick = { callbacks.dispatch(Action.SetQuery(transform(state.query))) },
            label = { Text(label) },
            trailingIcon = { Icon(Close, null, modifier = Modifier.size(15.dp)) },
            colors = InputChipDefaults.inputChipColors(selectedContainerColor = Raised, selectedLabelColor = TextPrimary, selectedTrailingIconColor = Muted),
            border = InputChipDefaults.inputChipBorder(enabled = true, selected = true, selectedBorderColor = Hairline),
            modifier = Modifier.semantics { contentDescription = "Remover filtro $label" },
        ) }
        TextButton(
            onClick = { callbacks.dispatch(Action.SetQuery(Query(search = state.query.search, sort = state.query.sort))) },
            shape = CompactShape,
            modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "Limpar todos os filtros ativos" },
        ) { Icon(Clear, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Limpar tudo") }
    }
}

@Composable
private fun GalleryScreen(state: AppState, callbacks: AppCallbacks, inlineFilters: Boolean, searchFocused: Boolean) = BoxWithConstraints(Modifier.fillMaxSize()) {
    GalleryBody(state, callbacks, searchFocused, Modifier.fillMaxSize())
    if (inlineFilters && state.filtersOpen) FilterPanel(state, callbacks, maxWidth, maxHeight, Modifier.align(Alignment.TopEnd))
}

@Composable
private fun FilterPanel(state: AppState, callbacks: AppCallbacks, availableWidth: androidx.compose.ui.unit.Dp, availableHeight: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    var draft by remember(state.filtersOpen) { mutableStateOf(state.query) }
    val draftCallbacks = callbacks.copy(dispatch = { action -> if (action is Action.SetQuery) draft = action.query else callbacks.dispatch(action) })
    val width = if (availableWidth >= 680.dp) minOf(620.dp, availableWidth - 16.dp) else (availableWidth - 16.dp).coerceAtLeast(280.dp)
    FilterRail(
        state.copy(query = draft),
        draftCallbacks,
        modifier.padding(8.dp).width(width).heightIn(max = (availableHeight - 16.dp).coerceAtLeast(260.dp))
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { callbacks.dispatch(Action.ToggleFilters); true } else false
            },
        initialFocusRequester = focusRequester,
        apply = {
            callbacks.dispatch(Action.SetQuery(draft))
            callbacks.dispatch(Action.ToggleFilters)
        },
    )
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun GalleryBody(state: AppState, callbacks: AppCallbacks, searchFocused: Boolean, modifier: Modifier) = Box(modifier.fillMaxHeight()) {
    when {
        state.library == null -> EmptyLibrary(callbacks)
        state.visiblePhotos.isEmpty() -> EmptyGallery(state, callbacks, requestInitialFocus = !searchFocused)
        else -> Column(Modifier.fillMaxSize()) {
            if (state.selectionModeActive) BatchCommandBar(state, callbacks)
            Box(Modifier.weight(1f).fillMaxWidth()) { PhotoGrid(state, callbacks, searchFocused) }
        }
    }
}

@Composable
private fun BatchCommandBar(state: AppState, callbacks: AppCallbacks) = BoxWithConstraints(
    Modifier.fillMaxWidth().background(Raised).semantics { contentDescription = "Batch command bar" },
) {
    var keyword by remember { mutableStateOf("") }
    var keywordError by remember { mutableStateOf<String?>(null) }
    var auxiliaryResult by remember { mutableStateOf<AuxiliaryBatchResult?>(null) }
    var auxiliaryBusy by remember { mutableStateOf(false) }
    var recipesExpanded by remember { mutableStateOf(false) }
    var hdrMaximum by remember { mutableStateOf(4) }
    var hdrControls by remember { mutableStateOf(batchHdrControlNames.associateWith { 0 }) }
    var editorialNotice by remember { mutableStateOf<String?>(null) }
    val batchScope = rememberCoroutineScope()
    val selected = state.selectedPhotos.take(MAX_BATCH_PHOTOS)
    val editableCount = selected.count { it.writable }
    val ignoredCount = selected.size - editableCount
    val visibleCount = state.visiblePhotos.size
    val selectionLimitReached = selected.size == MAX_BATCH_PHOTOS && visibleCount > MAX_BATCH_PHOTOS
    val canMutate = state.writeEnabled && editableCount > 0
    val compact = maxWidth < 1050.dp
    fun requestEditorial(edit: BatchEdit) {
        callbacks.batchMutate(selected, edit)
        editorialNotice = "Edição solicitada para ${editablePhotoCount(editableCount)}; confirme o resultado nos indicadores de cada fotografia."
    }
    fun requestAuxiliary(edit: AuxiliaryBatchEdit) {
        batchScope.launch {
            auxiliaryBusy = true
            auxiliaryResult = runCatching { callbacks.auxiliary.batchUpdate(selected, edit) }.getOrElse {
                val channel = if (edit is AuxiliaryBatchEdit.SetHdr) "HDR em RAF" else "Fuji FP2"
                AuxiliaryBatchResult(channel, selected.size, 0, 0, selected.size, selected.map { photo -> AuxiliaryBatchItemResult(photo.id, channel, AuxiliaryBatchOutcome.FAILED, "operation-failed") })
            }
            auxiliaryBusy = false
        }
    }
    val applyLabel: (String) -> Unit = { choice ->
        requestEditorial(BatchEdit.SetLabel(when (choice) { "Vermelho" -> ColorLabel.RED; "Amarelo" -> ColorLabel.YELLOW; "Verde" -> ColorLabel.GREEN; else -> null }))
    }
    val keywordActions: @Composable RowScope.() -> Unit = {
        ProofTextField(
            value = keyword,
            onValueChange = { keyword = it },
            label = "LOTE",
            placeholder = "Palavra-chave",
            leadingIcon = Tag,
            enabled = canMutate,
            focusChanged = callbacks.textFieldFocused,
            modifier = Modifier.weight(1f).widthIn(min = 150.dp).semantics { contentDescription = "Palavra-chave em lote" },
        )
        BatchKeywordMenu(
            enabled = canMutate,
            keywordEnabled = keyword.isNotBlank(),
            add = { runCatching { normalizeFlatKeyword(keyword) }.onSuccess { requestEditorial(BatchEdit.AddKeyword(it)); keyword = ""; keywordError = null }.onFailure { keywordError = "Use uma palavra-chave plana, sem |." } },
            remove = { runCatching { normalizeKeyword(keyword) }.onSuccess { requestEditorial(BatchEdit.RemoveKeyword(it)); keyword = "" } },
            clear = { requestEditorial(BatchEdit.ClearKeywords) },
        )
    }
    Column(
        Modifier.fillMaxWidth().heightIn(max = (maxHeight * .78f).coerceAtLeast(220.dp)).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selectionLimitReached) "${selected.size} de $visibleCount selecionadas · limite seguro de $MAX_BATCH_PHOTOS atingido" else selectedPhotoCount(selected.size),
                color = Amber, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            AppIconButton(SelectAll, "Selecionar todas as fotografias visíveis", { callbacks.dispatch(Action.SelectVisible) })
            AppIconButton(Clear, "Limpar seleção", { callbacks.dispatch(Action.ClearSelection) })
            AppIconButton(DoneAll, "Concluir seleção", { callbacks.dispatch(Action.ToggleSelectionMode) })
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                enabled = canMutate,
                onClick = { requestEditorial(BatchEdit.SetFlag(Flag.PICK)) },
                shape = CompactShape,
                colors = ButtonDefaults.textButtonColors(contentColor = PickGreen),
                modifier = Modifier.height(44.dp),
            ) { Icon(PickFlag, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("Escolher") }
            BatchChoiceMenu("Flag", listOf("Sem flag", "Rejeitar"), canMutate) { choice -> requestEditorial(BatchEdit.SetFlag(if (choice == "Rejeitar") Flag.REJECT else Flag.UNFLAGGED)) }
            BatchChoiceMenu("Avaliação", (0..5).map { it.toString() }, canMutate) { requestEditorial(BatchEdit.SetRating(it.toInt())) }
            BatchChoiceMenu("Rótulo", listOf("Nenhum", "Vermelho", "Amarelo", "Verde"), canMutate, applyLabel)
            TextButton(
                enabled = canMutate && !auxiliaryBusy,
                onClick = { recipesExpanded = !recipesExpanded },
                shape = CompactShape,
                colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
                modifier = Modifier.height(44.dp).semantics { contentDescription = if (recipesExpanded) "Ocultar controles Fuji e HDR em lote" else "Mostrar controles Fuji e HDR em lote" },
            ) { Text("Fuji/HDR"); Spacer(Modifier.width(4.dp)); Icon(if (recipesExpanded) ChevronLeft else ChevronRight, null, Modifier.size(16.dp)) }
            if (!compact) {
                Spacer(Modifier.width(4.dp))
                keywordActions()
            }
        }
        if (compact) Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) { keywordActions() }
        if (recipesExpanded) BatchRecipePanel(
            enabled = canMutate && !auxiliaryBusy,
            hdrMaximum = hdrMaximum,
            hdrControls = hdrControls,
            changeMaximum = { hdrMaximum = it.coerceIn(1, 4) },
            changeControl = { name, value -> hdrControls = hdrControls + (name to value.coerceIn(-100, 100)) },
            submit = ::requestAuxiliary,
        )
        if (ignoredCount > 0) Text("${editablePhotoCount(editableCount)} · $ignoredCount ${if (ignoredCount == 1) "será ignorada" else "serão ignoradas"}", color = Danger, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp))
        keywordError?.let { Text(it, color = Danger, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp)) }
        editorialNotice?.let { Text(it, color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp).semantics { contentDescription = "Resultado solicitado do lote editorial" }) }
        auxiliaryResult?.let { result ->
            Text(result.summary, color = if (result.failed > 0) Danger else PickGreen, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp).semantics { contentDescription = "Resultado do lote ${result.channel}" })
            val names = state.photos.associateBy({ it.id }, { it.displayName })
            result.items.take(MAX_SENSITIVE_BATCH_PHOTOS).forEach { item ->
                val outcome = when (item.outcome) {
                    AuxiliaryBatchOutcome.SUCCEEDED -> "salva"
                    AuxiliaryBatchOutcome.IGNORED -> "ignorada"
                    AuxiliaryBatchOutcome.FAILED -> "falhou"
                }
                val safeError = item.errorCode?.takeIf { it.length <= 80 && it.matches(Regex("[a-z0-9-]+")) }
                    ?.let(::humanizeBatchError)?.let { " · $it" }.orEmpty()
                Text(
                    "${names[item.photoId]?.take(96) ?: "Fotografia"} · $outcome$safeError",
                    color = if (item.outcome == AuxiliaryBatchOutcome.FAILED) Danger else Muted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp).semantics { contentDescription = "Resultado ${names[item.photoId]?.take(96) ?: "Fotografia"}: $outcome$safeError" },
                )
            }
        }
        if (!canMutate) Text(if (!state.writeEnabled) "Somente leitura" else "Nenhuma fotografia selecionada pode ser editada", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp))
    }
}

private fun humanizeBatchError(code: String): String = when (code) {
    "xmp-validation-failed" -> "XMP inválido; revise o sidecar adjacente e sincronize novamente"
    "security-boundary-failed" -> "a biblioteca mudou; sincronize novamente antes de repetir"
    "profile-validation-failed" -> "receita incompatível; revise os controles e repita"
    "profile-state-changed" -> "a receita mudou; sincronize novamente antes de repetir"
    else -> "não foi possível concluir; sincronize novamente e tente outra vez"
}

private val batchHdrControlNames = listOf("SDRBrightness", "SDRContrast", "SDRClarity", "SDRHighlights", "SDRShadows", "SDRWhites", "SDRBlend")
private fun batchHdrLabel(name: String): String = when (name) {
    "SDRBrightness" -> "Brilho"
    "SDRContrast" -> "Contraste"
    "SDRClarity" -> "Claridade"
    "SDRHighlights" -> "Realces"
    "SDRShadows" -> "Sombras"
    "SDRWhites" -> "Brancos"
    else -> "Mistura"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatchRecipePanel(
    enabled: Boolean,
    hdrMaximum: Int,
    hdrControls: Map<String, Int>,
    changeMaximum: (Int) -> Unit,
    changeControl: (String, Int) -> Unit,
    submit: (AuxiliaryBatchEdit) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(CompactShape).background(Stage).border(1.dp, Hairline, CompactShape).padding(10.dp)
            .semantics { contentDescription = "Controles Fuji e HDR em lote" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("FUJI FP2 · ALTERAÇÕES EXPLÍCITAS", color = Muted, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
        Text("Cada comando altera somente o campo escolhido; os demais valores FP2 são preservados.", color = Muted, style = MaterialTheme.typography.labelSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BatchChoiceMenu("Simulação", listOf("Classic Chrome", "PRO Neg. Std", "Astia"), enabled) { choice ->
                val value = when (choice) { "Classic Chrome" -> "Classic"; "PRO Neg. Std" -> "NEGAStd"; else -> "Astia" }
                submit(AuxiliaryBatchEdit.UpdateFuji(mapOf("FilmSimulation" to value), "Simulação $choice"))
            }
            BatchChoiceMenu("Exposição", exposureValues, enabled) { value ->
                submit(AuxiliaryBatchEdit.UpdateFuji(mapOf("ExposureBias" to value), "Exposição $value"))
            }
            BatchChoiceMenu("Alcance dinâmico", listOf("DR100", "DR200", "DR400"), enabled) { value ->
                submit(AuxiliaryBatchEdit.UpdateFuji(mapOf("DynamicRange" to value.removePrefix("DR")), value))
            }
        }
        HorizontalDivider(color = Hairline)
        Text("HDR LIGHTROOM · BLOCO COMPLETO", color = Muted, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
        Text("Máximo de 1 a 4 e sete controles SDR. Definir grava o bloco exibido; limpar remove o bloco HDR gerenciado.", color = Muted, style = MaterialTheme.typography.labelSmall)
        BatchNumberControl("Máximo HDR", hdrMaximum, 1, 4, enabled, changeMaximum)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            batchHdrControlNames.forEach { name ->
                BatchNumberControl(batchHdrLabel(name), hdrControls[name] ?: 0, -100, 100, enabled) { changeControl(name, it) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Button(
                onClick = { submit(AuxiliaryBatchEdit.SetHdr(true, "$hdrMaximum.00", hdrControls)) },
                shape = CompactShape,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "Definir HDR configurado em lote" },
            ) { Text("Definir HDR") }
            OutlinedButton(
                onClick = { submit(AuxiliaryBatchEdit.SetHdr(false)) },
                shape = CompactShape,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "Limpar HDR em lote" },
            ) { Text("Limpar HDR") }
        }
    }
}

@Composable
private fun BatchNumberControl(label: String, value: Int, minimum: Int, maximum: Int, enabled: Boolean, changed: (Int) -> Unit) {
    Row(
        Modifier.width(210.dp).heightIn(min = 44.dp).clip(CompactShape).background(Raised).padding(start = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$label  $value", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        AppIconButton(Remove, "Diminuir $label em lote", { changed(value - 1) }, enabled = enabled && value > minimum)
        AppIconButton(Add, "Aumentar $label em lote", { changed(value + 1) }, enabled = enabled && value < maximum)
    }
}

@Composable
private fun BatchChoiceMenu(label: String, choices: List<String>, enabled: Boolean, selected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(enabled = enabled, onClick = { expanded = true }, shape = CompactShape, colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary), modifier = Modifier.height(44.dp).semantics { contentDescription = "Menu $label em lote" }) { Text(label); Spacer(Modifier.width(4.dp)); Icon(ChevronRight, null, Modifier.size(16.dp)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(choice) }, onClick = { expanded = false; selected(choice) }, modifier = Modifier.heightIn(min = 44.dp)) }
        }
    }
}

@Composable
private fun BatchKeywordMenu(enabled: Boolean, keywordEnabled: Boolean, add: () -> Unit, remove: () -> Unit, clear: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(enabled = enabled, onClick = { expanded = true }, shape = CompactShape, colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary), modifier = Modifier.height(44.dp).semantics { contentDescription = "Ações de palavra-chave em lote" }) { Text("Ações"); Spacer(Modifier.width(4.dp)); Icon(ChevronRight, null, Modifier.size(16.dp)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Adicionar palavra-chave") }, leadingIcon = { Icon(Add, null) }, enabled = enabled && keywordEnabled, onClick = { expanded = false; add() }, modifier = Modifier.heightIn(min = 44.dp))
            DropdownMenuItem(text = { Text("Remover palavra-chave") }, leadingIcon = { Icon(Remove, null) }, enabled = enabled && keywordEnabled, onClick = { expanded = false; remove() }, modifier = Modifier.heightIn(min = 44.dp))
            DropdownMenuItem(text = { Text("Limpar palavras-chave") }, leadingIcon = { Icon(Clear, null) }, enabled = enabled, onClick = { expanded = false; clear() }, modifier = Modifier.heightIn(min = 44.dp))
        }
    }
}

@Composable
private fun CalendarScreen(state: AppState, callbacks: AppCallbacks) {
    val dated = state.photos.mapNotNull { photo -> captureGregorianDate(photo.metadata.capturedAt)?.toString()?.let { it to photo } }
    val years = dated.groupBy({ it.first.take(4) }, { it.second }).toList().sortedByDescending { it.first }
    var selectedYear by remember(state.photos) { mutableStateOf<String?>(null) }
    var selectedMonth by remember(state.photos) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectedMonth != null) TextButton(onClick = { selectedMonth = null }, shape = CompactShape, modifier = Modifier.height(44.dp).semantics { contentDescription = "Voltar para meses de $selectedYear" }) { Icon(ArrowBack, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Meses de $selectedYear") }
            else if (selectedYear != null) TextButton(onClick = { selectedYear = null }, shape = CompactShape, modifier = Modifier.height(44.dp).semantics { contentDescription = "Voltar para todos os anos" }) { Icon(ArrowBack, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Todos os anos") }
            else Text("Data de captura", color = Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
            Spacer(Modifier.weight(1f))
            selectedYear?.let { year -> Text(listOfNotNull(year, selectedMonth?.takeLast(2)?.toIntOrNull()?.let(::monthName)).joinToString(" · "), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 10.dp)) }
        }
        if (dated.isEmpty()) EmptyGallery(state, callbacks) else {
            val yearPhotos = selectedYear?.let { year -> years.firstOrNull { it.first == year }?.second.orEmpty() }
            val groups = when {
                selectedMonth != null -> yearPhotos.orEmpty().filter { captureGregorianDate(it.metadata.capturedAt)!!.toString().startsWith(selectedMonth!!) }.groupBy { captureGregorianDate(it.metadata.capturedAt)!!.toString() }.toList().sortedByDescending { it.first }
                selectedYear != null -> yearPhotos.orEmpty().groupBy { captureGregorianDate(it.metadata.capturedAt)!!.toString().take(7) }.toList().sortedByDescending { it.first }
                else -> null
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (selectedYear == null) 260.dp else 230.dp),
                contentPadding = PaddingValues(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (groups == null) gridItems(years, key = { it.first }) { (year, photos) ->
                    CalendarCoverCard(year, photoCount(photos.size), photos.maxBy { it.metadata.capturedAt.orEmpty() }, callbacks, "Navegar pelo ano $year, ${photoCount(photos.size)}") { selectedYear = year }
                } else gridItems(groups, key = { it.first }) { (period, photos) ->
                    if (selectedMonth == null) {
                        val number = period.takeLast(2).toInt()
                        CalendarCoverCard(monthName(number), photoCount(photos.size), photos.maxBy { it.metadata.capturedAt.orEmpty() }, callbacks, "Navegar por ${monthName(number)} de ${selectedYear}, ${photoCount(photos.size)}") { selectedMonth = period }
                    } else {
                        val day = period.takeLast(2).toInt()
                        CalendarCoverCard(day.toString(), photoCount(photos.size), photos.maxBy { it.metadata.capturedAt.orEmpty() }, callbacks, "Navegar pelo dia $day de ${monthName(period.substring(5, 7).toInt())} de ${selectedYear}, ${photoCount(photos.size)}") { callbacks.dispatch(Action.BrowseDate(period, period)) }
                    }
                }
            }
        }
    }
}

private fun monthName(month: Int) = listOf("Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho", "Julho", "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro").getOrElse(month - 1) { "Mês $month" }

@Composable
private fun CalendarCoverCard(title: String, count: String, representative: Photo, callbacks: AppCallbacks, description: String, clicked: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(1.35f).background(Raised).clickable(onClick = clicked).semantics { contentDescription = description },
    ) {
        PhotoImage(representative, callbacks, 720, ContentScale.Crop, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x22000000), Color(0xdd08090a)), startY = 80f)))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(count.uppercase(), color = Color(0xffdedfe1), style = ProofAnnotationStyle)
        }
    }
}

@Composable
private fun FoldersScreen(state: AppState, callbacks: AppCallbacks) {
    val roots = state.photos.filter { it.folder.isNotBlank() }.groupBy { it.folder.substringBefore('/') }.toList().sortedBy { it.first }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(state.library ?: "Biblioteca", color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${roots.size} ${if (roots.size == 1) "PASTA" else "PASTAS"}", color = Muted, style = ProofAnnotationStyle)
        }
        if (roots.isEmpty()) EmptyGallery(state, callbacks) else LazyVerticalGrid(
            columns = GridCells.Adaptive(210.dp),
            contentPadding = PaddingValues(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            gridItems(roots, key = { it.first }) { (folder, photos) ->
                FolderCoverCard(folder, photos, callbacks) { callbacks.dispatch(Action.BrowseFolder(folder)) }
            }
        }
    }
}

@Composable
private fun FolderCoverCard(folder: String, photos: List<Photo>, callbacks: AppCallbacks, clicked: () -> Unit) {
    val representative = photos.maxBy { it.metadata.capturedAt.orEmpty() }
    val dates = photos.mapNotNull { captureGregorianDate(it.metadata.capturedAt)?.toString() }.sorted()
    val dateRange = when {
        dates.isEmpty() -> "Data de captura indisponível"
        dates.first() == dates.last() -> formatCompactDate(dates.first())
        else -> "${formatCompactDate(dates.first())}–${formatCompactDate(dates.last())}"
    }
    val nested = photos.map { it.folder }.distinct().count { it != folder }
    Column(
        Modifier.fillMaxWidth().background(ProofPaper).border(1.dp, Hairline.copy(alpha = .5f)).clickable(onClick = clicked).semantics { contentDescription = "Navegar pela pasta $folder, ${photoCount(photos.size)}, intervalo $dateRange" },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.5f)) {
            PhotoImage(representative, callbacks, 720, ContentScale.Crop, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x66080503)), startY = 100f)))
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(photoCount(photos.size).uppercase(), color = Amber, style = ProofAnnotationStyle)
            Text(folder, color = TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(dateRange, color = Muted, style = ProofAnnotationStyle, maxLines = 1)
            if (nested > 0) Text("$nested ${if (nested == 1) "CAMINHO INTERNO" else "CAMINHOS INTERNOS"}", color = Muted, style = ProofAnnotationStyle, maxLines = 1)
        }
    }
}

private fun formatCompactDate(value: String): String {
    val year = value.take(4)
    val month = value.substring(5, 7).toIntOrNull() ?: return value
    val day = value.substring(8, 10).toIntOrNull() ?: return value
    return "${day.toString().padStart(2, '0')}/${month.toString().padStart(2, '0')}/$year"
}

private fun formatShortDate(value: String): String {
    val year = value.take(4)
    val month = value.substring(5, 7).toIntOrNull() ?: return value
    val day = value.substring(8, 10).toIntOrNull() ?: return value
    return "$day de ${monthName(month).lowercase()} de $year"
}

private fun formatCapturedAt(value: String, includeTime: Boolean = true): String {
    val civilDate = captureGregorianDate(value)?.toString() ?: return "Data inválida"
    val date = formatShortDate(civilDate)
    val time = value.substringAfter('T', "").take(8).takeIf { it.matches(Regex("\\d{2}:\\d{2}:\\d{2}")) }
    return if (includeTime && time != null) "$date às $time" else date
}

private fun formatDecimal(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterRail(state: AppState, callbacks: AppCallbacks, modifier: Modifier = Modifier, initialFocusRequester: FocusRequester, apply: () -> Unit) {
    var mode by remember { mutableStateOf(FilterMode.EDITORIAL) }
    val dateError = queryDateError(state.query.fromDate, state.query.toDate)
    Column(
        modifier.clip(RoundedCornerShape(2.dp)).background(Inspector.copy(alpha = .99f)).border(1.dp, Hairline, RoundedCornerShape(2.dp))
            .verticalScroll(rememberScrollState()).padding(16.dp).semantics { contentDescription = "Painel de filtros" },
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Tune, null, tint = Amber, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) { Text("Filtros", fontWeight = FontWeight.SemiBold); Text("Refinar catálogo", color = Muted, style = MaterialTheme.typography.bodySmall) }
            AppIconButton(Close, "Fechar filtros sem aplicar", { callbacks.dispatch(Action.ToggleFilters) })
        }
        HorizontalDivider(color = Hairline)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterMode.entries.forEach { item ->
                FilterChoice(item == mode, item.label, item.icon) { mode = item }
            }
        }
        when (mode) {
            FilterMode.EDITORIAL -> {
                Text("Estado editorial", color = Muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(
                        Triple<Flag?, String, ImageVector>(null, "Todos", PhotoLibrary),
                        Triple(Flag.PICK, "Escolhida", PickFlag),
                        Triple(Flag.UNFLAGGED, "Sem flag", FlagIcon),
                        Triple(Flag.REJECT, "Rejeitada", RejectFlag),
                    ).forEach { (flag, label, icon) ->
                        FilterChoice(state.query.flag == flag, label, icon) { callbacks.dispatch(Action.SetQuery(state.query.copy(flag = flag))) }
                    }
                }
            }
            FilterMode.STARS -> {
                Text("Estrelas mínimas", color = Muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    (0..5).forEach { rating -> FilterChoice(state.query.minimumStars == rating, if (rating == 0) "Qualquer" else "$rating+", if (rating == 0) StarBorder else Star) { callbacks.dispatch(Action.SetQuery(state.query.copy(minimumStars = rating))) } }
                }
            }
            FilterMode.GPS -> FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                GpsFilter.entries.forEach { gps -> FilterChoice(state.query.gps == gps, when (gps) { GpsFilter.ANY -> "Qualquer"; GpsFilter.PRESENT -> "Com GPS"; GpsFilter.MISSING -> "Sem GPS" }, LocationOn) { callbacks.dispatch(Action.SetQuery(state.query.copy(gps = gps))) } }
            }
            FilterMode.KEYWORDS -> FilterField("Palavra-chave exata", state.query.keyword.orEmpty(), callbacks, icon = Tag) { callbacks.dispatch(Action.SetQuery(state.query.copy(keyword = it.ifBlank { null }))) }
            FilterMode.DATE -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterField("De: AAAA-MM-DD", state.query.fromDate.orEmpty(), callbacks, Modifier.weight(1f)) { callbacks.dispatch(Action.SetQuery(state.query.copy(fromDate = it.ifBlank { null }))) }
                    FilterField("Até: AAAA-MM-DD", state.query.toDate.orEmpty(), callbacks, Modifier.weight(1f)) { callbacks.dispatch(Action.SetQuery(state.query.copy(toDate = it.ifBlank { null }))) }
                }
                dateError?.let { Text(it, color = Danger, style = MaterialTheme.typography.labelSmall, modifier = Modifier.semantics { contentDescription = "Erro de intervalo de datas" }) }
            }
            FilterMode.EQUIPMENT -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterField("Câmera exata", state.query.camera.orEmpty(), callbacks, icon = CameraAlt) { callbacks.dispatch(Action.SetQuery(state.query.copy(camera = it.ifBlank { null }))) }
                FilterField("Lente exata", state.query.lens.orEmpty(), callbacks, icon = Lens) { callbacks.dispatch(Action.SetQuery(state.query.copy(lens = it.ifBlank { null }))) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = apply, enabled = dateError == null, shape = CompactShape, modifier = Modifier.weight(1f).heightIn(min = 46.dp).focusRequester(initialFocusRequester).semantics { contentDescription = "Aplicar filtros" }) { Icon(Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Aplicar", maxLines = 1) }
            OutlinedButton(onClick = { callbacks.dispatch(Action.SetQuery(Query(search = state.query.search, sort = state.query.sort))) }, shape = CompactShape, modifier = Modifier.height(46.dp).semantics { contentDescription = "Limpar filtros" }) { Icon(Clear, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Limpar") }
        }
    }
}

private enum class FilterMode(val label: String, val icon: ImageVector) {
    EDITORIAL("Editorial", FilledFlag),
    STARS("Estrelas", StarBorder),
    GPS("GPS", LocationOn),
    KEYWORDS("Palavras", Tag),
    DATE("Data", CalendarMonth),
    EQUIPMENT("Equip.", CameraAlt),
}

@Composable
private fun FilterChoice(selected: Boolean, label: String, icon: ImageVector, modifier: Modifier = Modifier, clicked: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(2.dp))
            .onFocusChanged { focused = it.isFocused }
            .background(when { selected -> AmberWash; pressed -> RaisedHover; hovered -> Raised; else -> Color.Transparent })
            .then(if (focused) Modifier.border(2.dp, Focus, RoundedCornerShape(2.dp)) else Modifier)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = clicked)
            .semantics { contentDescription = label; this.selected = selected },
    ) {
        Row(Modifier.align(Alignment.Center).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, null, tint = if (selected) Amber else Muted, modifier = Modifier.size(17.dp))
            Text(label, color = if (selected) Amber else TextPrimary, style = MaterialTheme.typography.labelLarge)
        }
        if (selected) Canvas(Modifier.matchParentSize()) { drawLine(Amber, Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), strokeWidth = 2.dp.toPx()) }
    }
}

@Composable
private fun FilterField(label: String, value: String, callbacks: AppCallbacks, modifier: Modifier = Modifier, icon: ImageVector? = null, changed: (String) -> Unit) = ProofTextField(
    value = value,
    onValueChange = changed,
    label = label.uppercase(),
    placeholder = label,
    leadingIcon = icon,
    focusChanged = callbacks.textFieldFocused,
    modifier = modifier.fillMaxWidth().semantics { contentDescription = "Filtro $label" },
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoGrid(state: AppState, callbacks: AppCallbacks, searchFocused: Boolean) {
    var issuePhotoId by remember { mutableStateOf<String?>(null) }
    BoxWithConstraints {
        val visible = state.visiblePhotos
        val compactTouchLayout = maxWidth < 600.dp
        val tileSize = state.thumbnailSize.dp
        val columns = maxOf(1, ((maxWidth - 4.dp) / tileSize).toInt())
        val gridState = rememberLazyStaggeredGridState()
        val focusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
        val navigationScope = rememberCoroutineScope()
        val selectedIndex = visible.indexOfFirst { it.id == state.selectedId }
        var quickEditorId by remember { mutableStateOf<String?>(null) }
        val selectedRequester = state.selectedId?.let(focusRequesters::get)
        SideEffect { callbacks.galleryColumnsChanged(columns) }
        LaunchedEffect(state.selectedId, selectedIndex) {
            if (selectedIndex >= 0 && gridState.layoutInfo.visibleItemsInfo.none { it.index == selectedIndex }) gridState.requestScrollToItem(selectedIndex)
        }
        LaunchedEffect(state.selectedId, selectedRequester) {
            if (!searchFocused) selectedRequester?.requestFocus()
        }
        val gridFocusRequester = remember { FocusRequester() }
        fun closeQuickEditor(restoreFocus: Boolean = true) {
            val closingId = quickEditorId
            quickEditorId = null
            if (restoreFocus) navigationScope.launch {
                withFrameNanos { }
                closingId?.let(focusRequesters::get)?.requestFocus() ?: gridFocusRequester.requestFocus()
            }
        }
        LaunchedEffect(Unit) {
            if (!searchFocused) gridFocusRequester.requestFocus()
        }
        LaunchedEffect(gridState.isScrollInProgress) { if (gridState.isScrollInProgress && quickEditorId != null) closeQuickEditor() }
        fun renderedBounds(): List<SpatialBounds> = gridState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
            visible.getOrNull(item.index)?.let { photo ->
                SpatialBounds(photo.id, item.offset.x.toFloat(), item.offset.y.toFloat(), (item.offset.x + item.size.width).toFloat(), (item.offset.y + item.size.height).toFloat())
            }
        }
        fun moveSelection(direction: SpatialDirection, extend: Boolean): Boolean {
            if (visible.isEmpty()) return false
            val currentId = state.selectedId
            if (currentId == null) {
                callbacks.dispatch(Action.Select(visible.first().id, if (extend) SelectionMode.EXTEND else SelectionMode.REPLACE))
                return true
            }
            val rendered = renderedBounds()
            val currentBounds = rendered.firstOrNull { it.id == currentId }
            val direct = currentBounds?.let { spatialNeighbor(it, rendered, direction) }
            if (direct != null) {
                callbacks.dispatch(Action.Select(direct, if (extend) SelectionMode.EXTEND else SelectionMode.REPLACE))
                return true
            }
            if (direction == SpatialDirection.LEFT || direction == SpatialDirection.RIGHT) return false
            val estimated = estimatedMasonryNeighbor(visible, currentId, columns, direction)
            if (estimated != null) {
                callbacks.dispatch(Action.Select(estimated, if (extend) SelectionMode.EXTEND else SelectionMode.REPLACE))
                return true
            }
            val startingBounds = currentBounds ?: return false
            navigationScope.launch navigation@{
                val step = if (direction == SpatialDirection.DOWN) columns else -columns
                var probeIndex = selectedIndex
                // Probe bounded indexed regions, then choose from their rendered
                // geometry in the current lane. This reaches a card below a very
                // tall item without assuming fixed card heights.
                repeat(visible.size.coerceAtLeast(1)) {
                    val nextProbe = (probeIndex + step).coerceIn(0, visible.lastIndex)
                    if (nextProbe == probeIndex) return@navigation
                    probeIndex = nextProbe
                    gridState.scrollToItem(probeIndex)
                    withFrameNanos { }
                    val nowRendered = renderedBounds()
                    val directAfterScroll = spatialNeighbor(startingBounds, nowRendered, direction)
                    val sameLaneAfterExit = nowRendered
                        .filter { it.id != currentId && it.right > startingBounds.left && it.left < startingBounds.right }
                        .let { lane -> if (direction == SpatialDirection.DOWN) lane.minByOrNull { it.top } else lane.maxByOrNull { it.bottom } }
                        ?.id
                    val next = directAfterScroll ?: sameLaneAfterExit
                    if (next != null) {
                        callbacks.dispatch(Action.Select(next, if (extend) SelectionMode.EXTEND else SelectionMode.REPLACE))
                        return@navigation
                    }
                }
            }
            return true
        }
        LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Adaptive(tileSize), state = gridState, contentPadding = PaddingValues(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalItemSpacing = 4.dp,
            modifier = Modifier.fillMaxSize().onPreviewKeyEvent galleryKeys@{ event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && quickEditorId != null) { closeQuickEditor(); return@galleryKeys true }
                if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return@galleryKeys false
                val direction = when (event.key) { Key.DirectionLeft -> SpatialDirection.LEFT; Key.DirectionRight -> SpatialDirection.RIGHT; Key.DirectionUp -> SpatialDirection.UP; Key.DirectionDown -> SpatialDirection.DOWN; else -> null }
                when {
                    direction != null -> moveSelection(direction, event.isShiftPressed)
                    event.key == Key.Enter && state.selectedId != null -> { callbacks.dispatch(Action.OpenDetail); true }
                    else -> false
                }
            }.focusRequester(gridFocusRequester).focusable().onFocusChanged { callbacks.shortcutScopeFocused(it.hasFocus) }.semantics { contentDescription = "Galeria de fotografias" }) {
        staggeredItems(visible, key = { photo -> photo.id }) { photo ->
            val selected = photo.id in state.selectionIds
            var focused by remember(photo.id) { mutableStateOf(false) }
            var cardHasFocus by remember(photo.id) { mutableStateOf(false) }
            val quickOpen = quickEditorId == photo.id
            val hoverInteraction = remember(photo.id) { MutableInteractionSource() }
            val hovered by hoverInteraction.collectIsHoveredAsState()
            val focusRequester = remember(photo.id) { FocusRequester() }
            DisposableEffect(photo.id, focusRequester) {
                focusRequesters[photo.id] = focusRequester
                onDispose {
                    if (focusRequesters[photo.id] === focusRequester) focusRequesters.remove(photo.id)
                }
            }
            DisposableEffect(photo.id, quickOpen) {
                onDispose { if (quickOpen && quickEditorId == photo.id) closeQuickEditor() }
            }
            Column(
                Modifier.fillMaxWidth().focusRequester(focusRequester)
                    .onPreviewKeyEvent cardKeys@{ event ->
                        if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return@cardKeys false
                        val direction = when (event.key) { Key.DirectionLeft -> SpatialDirection.LEFT; Key.DirectionRight -> SpatialDirection.RIGHT; Key.DirectionUp -> SpatialDirection.UP; Key.DirectionDown -> SpatialDirection.DOWN; else -> null }
                        direction?.let { moveSelection(it, event.isShiftPressed) } ?: false
                    }
                    .onFocusChanged { focused = it.isFocused; cardHasFocus = it.hasFocus; callbacks.shortcutScopeFocused(it.hasFocus) }
                    .hoverable(hoverInteraction)
                    .clip(RoundedCornerShape(1.dp))
                    .then(if (focused) Modifier.border(2.dp, Focus, RoundedCornerShape(1.dp)) else Modifier)
                    .background(ProofPaper).clickable {
                        if (state.selectionModeActive) callbacks.dispatch(Action.Select(photo.id, SelectionMode.TOGGLE))
                        else { callbacks.dispatch(Action.Select(photo.id)); callbacks.dispatch(Action.OpenDetail) }
                    }.semantics { contentDescription = "Fotografia ${photo.displayName}"; stateDescription = "Formato ${photo.mediaFormatLabel}"; this.selected = selected },
            ) {
                Box(Modifier.fillMaxWidth()) {
                    PhotoImage(
                        photo, callbacks, 1024, ContentScale.Fit,
                        Modifier.fillMaxWidth()
                            .then(if (quickOpen) Modifier.heightIn(min = 280.dp) else Modifier.aspectRatio(photo.aspectRatio.coerceIn(1f / 3f, 6f)))
                            .alpha(if (photo.editorial.flag == Flag.REJECT) .34f else 1f),
                        issueClicked = { issuePhotoId = photo.id },
                    )
                    if (selected) ProofSelectionCorners(Amber, Modifier.matchParentSize())
                    if (quickOpen) {
                        Box(
                            Modifier.matchParentSize().background(Stage.copy(alpha = .74f)).clickable { closeQuickEditor() }
                                .semantics { contentDescription = "Área de curadoria rápida sobre ${photo.displayName}" },
                            contentAlignment = Alignment.Center,
                        ) {
                            QuickEditor(photo, state, callbacks, close = ::closeQuickEditor)
                        }
                    }
                    if (state.selectionModeActive) {
                        Box(
                            Modifier.align(Alignment.TopStart).padding(7.dp).size(28.dp).clip(RoundedCornerShape(2.dp))
                                .background(if (selected) Amber else Stage.copy(alpha = .84f)).border(1.dp, if (selected) Amber else TextPrimary, RoundedCornerShape(2.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(if (selected) Check else CheckBoxOutlineBlank, null, tint = if (selected) Ink else TextPrimary, modifier = Modifier.size(17.dp)) }
                    }
                    if (!state.selectionModeActive && !quickOpen && (compactTouchLayout || hovered || cardHasFocus)) {
                        AppIconButton(
                            Tune,
                            "Abrir curadoria rápida de ${photo.displayName}",
                            { quickEditorId = photo.id },
                            Modifier.align(Alignment.TopEnd).padding(6.dp),
                        )
                    }
                    if (photo.writeState != WriteState.IDLE) {
                        val status = when (photo.writeState) { WriteState.SAVING -> "Salvando"; WriteState.PERSISTED -> "Salvo"; WriteState.FAILED -> "Falha"; WriteState.IDLE -> "" }
                        Row(Modifier.align(Alignment.TopCenter).padding(8.dp).clip(CompactShape).background(Color(0xcc000000)).padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (photo.writeState == WriteState.FAILED) Close else Check, null, tint = if (photo.writeState == WriteState.FAILED) Danger else PickGreen, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text(status, color = TextPrimary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                ProofAnnotation(photo)
            }
        }
        }
        issuePhotoId?.let { id ->
            state.photos.firstOrNull { it.id == id }?.let { photo ->
                PhotoProblemPanel(
                    photo,
                    Modifier.align(Alignment.TopCenter).padding(10.dp).widthIn(max = minOf(460.dp, maxWidth - 20.dp)),
                    close = { issuePhotoId = null },
                    recover = { callbacks.dispatch(Action.OpenSettings) },
                )
            }
        }
    }
}

@Composable
private fun ProofSelectionCorners(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val inset = 4.dp.toPx()
    val length = 15.dp.toPx().coerceAtMost(size.minDimension * .22f)
    val stroke = 2.dp.toPx()
    drawLine(color, Offset(inset, inset), Offset(inset + length, inset), stroke)
    drawLine(color, Offset(inset, inset), Offset(inset, inset + length), stroke)
    drawLine(color, Offset(size.width - inset - length, inset), Offset(size.width - inset, inset), stroke)
    drawLine(color, Offset(size.width - inset, inset), Offset(size.width - inset, inset + length), stroke)
    drawLine(color, Offset(inset, size.height - inset), Offset(inset + length, size.height - inset), stroke)
    drawLine(color, Offset(inset, size.height - inset - length), Offset(inset, size.height - inset), stroke)
    drawLine(color, Offset(size.width - inset - length, size.height - inset), Offset(size.width - inset, size.height - inset), stroke)
    drawLine(color, Offset(size.width - inset, size.height - inset - length), Offset(size.width - inset, size.height - inset), stroke)
}

@Composable
private fun ProofAnnotation(photo: Photo) = Row(
    Modifier.fillMaxWidth().height(32.dp).background(ProofPaper).border(1.dp, Hairline.copy(alpha = .48f)).padding(horizontal = 7.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
) {
    Text(photo.filenameNumber ?: "S/N", color = Amber, style = ProofAnnotationStyle)
    if (photo.editorial.flag != Flag.UNFLAGGED) Icon(
        if (photo.editorial.flag == Flag.PICK) PickFlag else RejectFlag,
        null,
        tint = if (photo.editorial.flag == Flag.PICK) PickGreen else Danger,
        modifier = Modifier.size(14.dp),
    )
    if (photo.editorial.rating > 0) {
        Icon(Star, null, tint = Amber, modifier = Modifier.size(12.dp))
        Text(photo.editorial.rating.toString(), color = TextPrimary, style = ProofAnnotationStyle)
    }
    photo.editorial.label?.let { Box(Modifier.size(8.dp).background(labelColor(it), RoundedCornerShape(1.dp))) }
    Spacer(Modifier.weight(1f))
    Text(
        photo.mediaFormatLabel,
        color = Muted,
        style = ProofAnnotationStyle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.semantics { contentDescription = "Formato ${photo.mediaFormatLabel}" },
    )
}

private sealed interface PreviewState {
    data object Loading : PreviewState
    data class Loaded(val bitmap: androidx.compose.ui.graphics.ImageBitmap) : PreviewState
    data object Unavailable : PreviewState
}

@Composable
private fun PhotoImage(
    photo: Photo,
    callbacks: AppCallbacks,
    maximumDimension: Int,
    scale: ContentScale,
    modifier: Modifier,
    issueClicked: (() -> Unit)? = null,
    imageModifier: Modifier = Modifier,
    previewDimensionsChanged: (IntSize) -> Unit = {},
) {
    val preview by produceState<PreviewState>(PreviewState.Loading, photo.id, photo.previewIdentity, maximumDimension) {
        value = callbacks.imageLoader.load(photo, maximumDimension)?.let(PreviewState::Loaded) ?: PreviewState.Unavailable
    }
    Box(modifier.background(Stage).semantics { contentDescription = "Quadro de ${photo.displayName}" }, contentAlignment = Alignment.Center) {
        when (val current = preview) {
            PreviewState.Loading -> Text("Carregando prévia", color = Muted, modifier = Modifier.semantics { contentDescription = "Carregando prévia de ${photo.displayName}" })
            is PreviewState.Loaded -> {
                SideEffect { previewDimensionsChanged(IntSize(current.bitmap.width, current.bitmap.height)) }
                Image(current.bitmap, contentDescription = "Prévia de ${photo.displayName}", modifier = Modifier.fillMaxSize().then(imageModifier), contentScale = scale)
            }
            PreviewState.Unavailable -> Text("Prévia indisponível", color = Muted, modifier = Modifier.semantics { contentDescription = "Prévia indisponível para ${photo.displayName}" })
        }
        if (photo.issue != null || photo.writeError != null || photo.metadata.status == MetadataStatus.ERROR || photo.editorial.goodError != null) Box(
            Modifier.align(Alignment.BottomEnd).padding(7.dp).size(44.dp).clip(RoundedCornerShape(22.dp)).background(Stage.copy(alpha = .82f))
                .border(1.dp, Danger.copy(alpha = .82f), RoundedCornerShape(22.dp))
                .then(if (issueClicked != null) Modifier.clickable(onClick = issueClicked) else Modifier)
                .semantics { contentDescription = "Exibir problema de ${photo.displayName}"; stateDescription = photoProblemText(photo) },
            contentAlignment = Alignment.Center,
        ) { Icon(ErrorOutline, null, tint = Danger, modifier = Modifier.size(18.dp)) }
    }
}

private fun photoProblemText(photo: Photo): String = when {
    photo.writeError != null -> photo.writeError.take(220)
    photo.issue?.contains("ambig", ignoreCase = true) == true -> "Há arquivos ou sidecars ambíguos com o mesmo nome. A edição foi bloqueada para proteger a biblioteca."
    photo.issue?.contains("xmp", ignoreCase = true) == true -> "O sidecar XMP não pôde ser lido com segurança. A fotografia permanece somente leitura até a revisão do arquivo."
    photo.issue != null -> "A topologia desta fotografia não é segura para edição automática. Os arquivos de mídia não foram alterados."
    photo.editorial.goodError != null -> "O campo legado xmpDM:good não pôde ser interpretado. Flag, avaliação, rótulo e palavras-chave continuam disponíveis; o valor original foi preservado."
    photo.metadata.status == MetadataStatus.ERROR -> "Os metadados técnicos não puderam ser lidos. A fotografia continua disponível para visualização."
    else -> "Nenhum problema ativo."
}

@Composable
private fun PhotoProblemPanel(photo: Photo, modifier: Modifier = Modifier, close: () -> Unit, recover: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(photo.id) { focusRequester.requestFocus() }
    Column(
        modifier.clip(RoundedCornerShape(7.dp)).background(Inspector).border(1.dp, Danger.copy(alpha = .72f), RoundedCornerShape(7.dp)).padding(12.dp)
            .semantics { contentDescription = "Problema da fotografia ${photo.displayName}" },
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ErrorOutline, null, tint = Danger, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(7.dp))
            Text("Edição protegida", color = TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            AppIconButton(Close, "Fechar explicação do problema", close, Modifier.focusRequester(focusRequester))
        }
        Text(photo.displayName, color = Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(photoProblemText(photo), color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
        Text("Revise o sidecar em uma cópia segura e sincronize novamente. O PhotoTool não abre caminhos locais a partir deste diagnóstico.", color = Muted, style = MaterialTheme.typography.labelSmall)
        OutlinedButton(
            onClick = recover,
            shape = CompactShape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            border = BorderStroke(1.dp, Amber.copy(alpha = .72f)),
            modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "Ir às configurações para sincronizar" },
        ) {
            Icon(Settings, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Ir às configurações")
        }
    }
}

@Composable
private fun <T> MagneticSelector(
    items: List<T>,
    selected: T,
    enabled: Boolean,
    description: String,
    itemDescription: (T) -> String,
    accent: (T) -> Color,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
    content: @Composable (T, Boolean) -> Unit,
) {
    val selectedIndex = items.indexOf(selected).coerceAtLeast(0)
    var trackWidth by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember(selectedIndex) { mutableFloatStateOf(selectedIndex.toFloat()) }
    val settledPosition by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = .78f, stiffness = Spring.StiffnessMediumLow),
        label = "$description magnet",
    )
    val currentOnSelect by rememberUpdatedState(onSelect)
    val visualPosition = if (dragging) dragPosition else settledPosition
    Box(
        modifier.fillMaxWidth().height(44.dp).clip(CompactShape).background(Stage).border(1.dp, Hairline, CompactShape)
            .onSizeChanged { trackWidth = it.width }
            .pointerInput(enabled, selectedIndex, trackWidth, items.size) {
                if (!enabled || trackWidth <= 0) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; dragPosition = selectedIndex.toFloat() },
                    onDragCancel = { dragging = false },
                    onDragEnd = {
                        val target = dragPosition.roundToInt().coerceIn(items.indices)
                        dragging = false
                        currentOnSelect(items[target])
                    },
                ) { change, amount ->
                    change.consume()
                    val segmentWidth = trackWidth.toFloat() / items.size
                    dragPosition = (dragPosition + amount / segmentWidth).coerceIn(0f, items.lastIndex.toFloat())
                }
            }
            .alpha(if (enabled) 1f else .42f)
            .semantics { contentDescription = description; stateDescription = itemDescription(selected) },
    ) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(1f / items.size)
                .graphicsLayer { translationX = size.width * visualPosition }
                .clip(CompactShape).background(accent(selected).copy(alpha = .18f))
                .border(1.dp, accent(selected).copy(alpha = .78f), CompactShape),
        )
        Row(Modifier.fillMaxSize()) {
            items.forEach { item ->
                val isSelected = item == selected
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                val pressed by interaction.collectIsPressedAsState()
                var focused by remember { mutableStateOf(false) }
                Box(
                    Modifier.weight(1f).fillMaxHeight().onFocusChanged { focused = it.isFocused }
                        .clip(CompactShape)
                        .background(if (hovered || pressed) Color.White.copy(alpha = if (pressed) .10f else .06f) else Color.Transparent)
                        .border(if (focused) 2.dp else 0.dp, if (focused) Focus else Color.Transparent, CompactShape)
                        .hoverable(interaction, enabled)
                        .clickable(interactionSource = interaction, indication = null, enabled = enabled) { currentOnSelect(item) }
                        .semantics { contentDescription = itemDescription(item); this.selected = isSelected },
                    contentAlignment = Alignment.Center,
                ) {
                    CompositionLocalProvider(LocalContentColor provides if (isSelected) accent(item) else TextPrimary) { content(item, isSelected) }
                }
            }
        }
    }
}

@Composable
private fun EditorialFlagSelector(flag: Flag, enabled: Boolean, modifier: Modifier = Modifier, onSelect: (Flag) -> Unit) {
    val items = listOf(Flag.REJECT, Flag.UNFLAGGED, Flag.PICK)
    MagneticSelector(
        items = items,
        selected = flag,
        enabled = enabled,
        description = "Estado editorial",
        itemDescription = { item -> when (item) { Flag.REJECT -> "Marcar como rejeitada"; Flag.UNFLAGGED -> "Remover flag"; Flag.PICK -> "Marcar como escolhida" } },
        accent = { item -> when (item) { Flag.REJECT -> Danger; Flag.UNFLAGGED -> Muted; Flag.PICK -> PickGreen } },
        modifier = modifier,
        onSelect = onSelect,
    ) { item, _ -> Icon(when (item) { Flag.REJECT -> RejectFlag; Flag.UNFLAGGED -> FlagIcon; Flag.PICK -> PickFlag }, null, Modifier.size(19.dp)) }
}

@Composable
private fun EditorialLabelSelector(label: ColorLabel?, enabled: Boolean, modifier: Modifier = Modifier, onSelect: (ColorLabel?) -> Unit) {
    val items = listOf<ColorLabel?>(null, ColorLabel.RED, ColorLabel.YELLOW, ColorLabel.GREEN)
    MagneticSelector(
        items = items,
        selected = label,
        enabled = enabled,
        description = "Rótulo cromático",
        itemDescription = { item -> when (item) { null -> "Remover rótulo de cor"; ColorLabel.RED -> "Rótulo vermelho"; ColorLabel.YELLOW -> "Rótulo amarelo"; ColorLabel.GREEN -> "Rótulo verde" } },
        accent = { item -> if (item == null) Muted else labelColor(item) },
        modifier = modifier,
        onSelect = onSelect,
    ) { item, _ ->
        Box(
            Modifier.size(18.dp).background(if (item == null) Color.Transparent else labelColor(item), RoundedCornerShape(9.dp))
                .border(1.5.dp, if (item == null) Muted else TextPrimary.copy(alpha = .82f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) { if (item == null) Icon(Clear, null, Modifier.size(13.dp)) }
    }
}

@Composable
private fun EditorialRatingSlider(rating: Int, enabled: Boolean, description: String, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    var gestureValue by remember(rating) { mutableFloatStateOf(rating.toFloat()) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
            (0..5).forEach { value ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (value == 0) Icon(Clear, null, tint = Muted, modifier = Modifier.size(12.dp))
                    else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        Icon(Star, null, tint = if (value <= gestureValue.roundToInt()) Amber else Muted, modifier = Modifier.size(11.dp))
                        Text(value.toString(), color = if (value == gestureValue.roundToInt()) TextPrimary else Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Slider(
            value = gestureValue,
            onValueChange = { gestureValue = it.roundToInt().coerceIn(0, 5).toFloat() },
            onValueChangeFinished = {
                val endpoint = gestureValue.roundToInt().coerceIn(0, 5)
                if (endpoint != rating) onSelect(endpoint)
            },
            valueRange = 0f..5f,
            steps = 4,
            enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Hairline, activeTickColor = Ink, inactiveTickColor = Muted),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { contentDescription = description; stateDescription = if (rating == 0) "Sem estrelas" else "$rating estrelas" },
        )
    }
}

@Composable
private fun QuickControls(photo: Photo, state: AppState, callbacks: AppCallbacks, modifier: Modifier = Modifier) {
    EditorialFlagSelector(photo.editorial.flag, state.writeEnabled && photo.writable, modifier) { callbacks.mutateFlag(photo, it) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickEditor(photo: Photo, state: AppState, callbacks: AppCallbacks, close: () -> Unit) {
    val closeFocusRequester = remember { FocusRequester() }
    val enabled = state.writeEnabled && photo.writable
    LaunchedEffect(photo.id) { closeFocusRequester.requestFocus() }
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Curadoria rápida de ${photo.displayName}" },
        color = ProofPaper,
        shape = RoundedCornerShape(2.dp),
        border = BorderStroke(1.dp, Amber.copy(alpha = .58f)),
        shadowElevation = 8.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(photo.displayName, color = TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(photo.mediaFormatLabel, color = Muted, style = ProofAnnotationStyle, maxLines = 1)
                AppIconButton(Close, "Fechar curadoria rápida", close, Modifier.focusRequester(closeFocusRequester))
            }
            EditorialFlagSelector(photo.editorial.flag, enabled) { callbacks.mutateFlag(photo, it) }
            EditorialRatingSlider(photo.editorial.rating, enabled, "Avaliação da curadoria rápida") { callbacks.mutate(photo, photo.editorial.copy(rating = it)) }
            EditorialLabelSelector(photo.editorial.label, enabled) { callbacks.mutate(photo, photo.editorial.copy(label = it)) }
            if (photo.writeState != WriteState.IDLE) {
                val failed = photo.writeState == WriteState.FAILED
                val status = when (photo.writeState) { WriteState.SAVING -> "Salvando no XMP"; WriteState.PERSISTED -> "Salvo e verificado"; WriteState.FAILED -> "Falha ao salvar"; WriteState.IDLE -> "" }
                Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (failed) Close else Check, null, tint = if (failed) Danger else PickGreen, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp)); Text(status, color = if (failed) Danger else Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun editorialSummary(editorial: EditorialState): String {
    val flag = when (editorial.flag) { Flag.PICK -> "escolhida"; Flag.UNFLAGGED -> "sem flag"; Flag.REJECT -> "rejeitada" }
    val rating = if (editorial.rating == 0) "sem estrelas" else "${editorial.rating} estrelas"
    val label = when (editorial.label) { null -> "sem cor"; ColorLabel.RED -> "cor vermelha"; ColorLabel.YELLOW -> "cor amarela"; ColorLabel.GREEN -> "cor verde" }
    return "$flag, $rating, $label"
}

@Composable
private fun FujiPanel(photo: Photo, state: AppState, auxiliary: AuxiliaryView, busy: Boolean, runAux: (suspend () -> AuxiliaryView) -> Unit, callbacks: AppCallbacks) {
    Text("RECEITA FUJI", color = Muted, fontWeight = FontWeight.Bold)
    val recipe = auxiliary.fuji
    if (recipe == null) { Text("Nenhum perfil FP2 ou FP3 com nome correspondente", color = Muted); return }
    Text("${recipe.kind.uppercase()} · ${if (recipe.editable) "editável" else "somente leitura"}")
    Text("${recipe.filmSimulation}  DR${recipe.dynamicRange}  ${recipe.grainEffect}")
    Text("Exposição ${recipe.exposureBias}  Balanço ${recipe.whiteBalance}  R${recipe.wbShiftR} B${recipe.wbShiftB}")
    Text("Realces ${recipe.highlightTone}  Sombras ${recipe.shadowTone}  Cor ${recipe.color}")
    Text("Nitidez ${recipe.sharpness}  Redução de ruído ${recipe.noiseReduction}  Modulação ${if (recipe.lensModulation) "ativada" else "desativada"}")
    val enabled = state.writeEnabled && photo.writable && recipe.editable && !busy
    Row(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { runAux { callbacks.auxiliary.updateFuji(photo, mapOf("ExposureBias" to nextExposure(recipe.exposureBias, -1))) } }, shape = CompactShape, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 44.dp).semantics { contentDescription = "Diminuir exposição Fuji" }) { Icon(Remove, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("Exposição") }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = { runAux { callbacks.auxiliary.updateFuji(photo, mapOf("ExposureBias" to nextExposure(recipe.exposureBias, 1))) } }, shape = CompactShape, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 44.dp).semantics { contentDescription = "Aumentar exposição Fuji" }) { Icon(Add, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("Exposição") }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        listOf(100, 200, 400).forEach { value -> OutlinedButton(onClick = { runAux { callbacks.auxiliary.updateFuji(photo, mapOf("DynamicRange" to value.toString())) } }, shape = CompactShape, enabled = enabled && recipe.dynamicRange != value, modifier = Modifier.heightIn(min = 44.dp)) { Text("DR$value") } }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        listOf("Classic", "NEGAStd", "Astia").forEach { value -> OutlinedButton(onClick = { runAux { callbacks.auxiliary.updateFuji(photo, mapOf("FilmSimulation" to value)) } }, shape = CompactShape, enabled = enabled && recipe.filmSimulation != value, modifier = Modifier.heightIn(min = 44.dp)) { Text(value) } }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        listOf("OFF", "WEAK", "STRONG").forEach { value -> OutlinedButton(onClick = { runAux { callbacks.auxiliary.updateFuji(photo, mapOf("GrainEffect" to value)) } }, shape = CompactShape, enabled = enabled && recipe.grainEffect != value, modifier = Modifier.heightIn(min = 44.dp)) { Text("Granulação ${when (value) { "OFF" -> "desligada"; "WEAK" -> "fraca"; else -> "forte" }}") } }
    }
    FujiIntegerControl("Balanço R", "WBShiftR", recipe.wbShiftR, -9, 9, photo, enabled, runAux, callbacks)
    FujiIntegerControl("Balanço B", "WBShiftB", recipe.wbShiftB, -9, 9, photo, enabled, runAux, callbacks)
    FujiIntegerControl("Realces", "HighlightTone", recipe.highlightTone, -4, 4, photo, enabled, runAux, callbacks)
    FujiIntegerControl("Sombras", "ShadowTone", recipe.shadowTone, -4, 4, photo, enabled, runAux, callbacks)
    FujiIntegerControl("Cor", "Color", recipe.color, -4, 4, photo, enabled, runAux, callbacks)
    FujiIntegerControl("Nitidez", "Sharpness", recipe.sharpness, -4, 4, photo, enabled, runAux, callbacks)
    FujiIntegerControl("Redução de ruído", "NoisReduction", recipe.noiseReduction, -4, 4, photo, enabled, runAux, callbacks)
    OutlinedButton(onClick = { runAux { callbacks.auxiliary.updateFuji(photo, mapOf("LensModulationOpt" to if (recipe.lensModulation) "OFF" else "ON")) } }, shape = CompactShape, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Text("Modulação de lente ${if (recipe.lensModulation) "ativada" else "desativada"}") }
    Row(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { runAux { callbacks.auxiliary.transferFujiToXmp(photo) } }, shape = CompactShape, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text("Fuji → XMP") }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = { runAux { callbacks.auxiliary.transferXmpToFuji(photo) } }, shape = CompactShape, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text("XMP → Fuji") }
    }
}

@Composable
private fun FujiIntegerControl(label: String, field: String, value: Int, minimum: Int, maximum: Int, photo: Photo, enabled: Boolean, runAux: (suspend () -> AuxiliaryView) -> Unit, callbacks: AppCallbacks) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$label  $value", Modifier.weight(1f))
        OutlinedButton(onClick = { runAux { callbacks.auxiliary.updateFuji(photo, mapOf(field to (value - 1).toString())) } }, shape = CompactShape, enabled = enabled && value > minimum, modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).semantics { contentDescription = "Diminuir $label" }) { Icon(Remove, null) }
        Spacer(Modifier.width(4.dp))
        OutlinedButton(onClick = { runAux { callbacks.auxiliary.updateFuji(photo, mapOf(field to (value + 1).toString())) } }, shape = CompactShape, enabled = enabled && value < maximum, modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).semantics { contentDescription = "Aumentar $label" }) { Icon(Add, null) }
    }
}

private val exposureValues = (-9..9).map { thirds -> if (thirds == 0) "0" else { val sign = if (thirds > 0) "P" else "M"; val absolute = kotlin.math.abs(thirds); val whole = absolute / 3; val fraction = listOf("00", "33", "67")[absolute % 3]; "$sign${whole}P$fraction" } }
private fun nextExposure(value: String, delta: Int): String { val index = exposureValues.indexOf(value).takeIf { it >= 0 } ?: 9; return exposureValues[(index + delta).coerceIn(0, exposureValues.lastIndex)] }

@Composable
private fun HdrPanel(photo: Photo, state: AppState, auxiliary: AuxiliaryView, busy: Boolean, runAux: (suspend () -> AuxiliaryView) -> Unit, callbacks: AppCallbacks) {
    Text("HDR DO LIGHTROOM", color = Muted, fontWeight = FontWeight.Bold)
    val hdr = auxiliary.hdr
    Row(verticalAlignment = Alignment.CenterVertically) { Text(if (hdr.enabled) "Ativado" else "Desativado", Modifier.weight(1f)); Switch(hdr.enabled, { enabled -> runAux { callbacks.auxiliary.updateHdr(photo, if (enabled) HdrView(true, hdr.maximum, hdr.controls) else HdrView(false)) } }, enabled = state.writeEnabled && photo.writable && !busy) }
    if (hdr.enabled) {
        val maximum = hdr.maximum.substringBefore('.').toIntOrNull()?.coerceIn(1, 4) ?: 4
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Máximo HDR  $maximum", Modifier.weight(1f))
            AppIconButton(Remove, "Diminuir máximo HDR", { runAux { callbacks.auxiliary.updateHdr(photo, hdr.copy(maximum = "${maximum - 1}.00")) } }, enabled = state.writeEnabled && photo.writable && !busy && maximum > 1)
            AppIconButton(Add, "Aumentar máximo HDR", { runAux { callbacks.auxiliary.updateHdr(photo, hdr.copy(maximum = "${maximum + 1}.00")) } }, enabled = state.writeEnabled && photo.writable && !busy && maximum < 4)
        }
        listOf("SDRBrightness", "SDRContrast", "SDRClarity", "SDRHighlights", "SDRShadows", "SDRWhites", "SDRBlend").forEach { name ->
            val label = when (name) { "SDRBrightness" -> "Brilho SDR"; "SDRContrast" -> "Contraste SDR"; "SDRClarity" -> "Claridade SDR"; "SDRHighlights" -> "Realces SDR"; "SDRShadows" -> "Sombras SDR"; "SDRWhites" -> "Brancos SDR"; else -> "Mistura SDR" }
            HdrControlSlider(photo, name, label, hdr, state.writeEnabled && photo.writable && !busy, runAux, callbacks)
        }
    }
}

@Composable
private fun HdrControlSlider(photo: Photo, name: String, label: String, hdr: HdrView, enabled: Boolean, runAux: (suspend () -> AuxiliaryView) -> Unit, callbacks: AppCallbacks) {
    val persisted = hdr.controls[name] ?: 0
    var gestureValue by remember(photo.id, name, persisted) { mutableFloatStateOf(persisted.toFloat()) }
    Text("$label  ${gestureValue.toInt()}")
    Slider(
        value = gestureValue,
        onValueChange = { gestureValue = it.coerceIn(-100f, 100f) },
        onValueChangeFinished = {
            val endpoint = gestureValue.toInt().coerceIn(-100, 100)
            if (endpoint != persisted) runAux { callbacks.auxiliary.updateHdr(photo, hdr.copy(controls = hdr.controls + (name to endpoint))) }
        },
        valueRange = -100f..100f,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = label },
    )
}

@Composable
private fun DetailScreen(state: AppState, callbacks: AppCallbacks) {
    val focusRequester = remember { FocusRequester() }
    val photo = state.selected
    LaunchedEffect(photo?.id) { focusRequester.requestFocus() }
    BoxWithConstraints(
        Modifier.fillMaxSize().focusRequester(focusRequester).onFocusChanged { callbacks.shortcutScopeFocused(it.hasFocus) }.focusable(),
    ) {
        if (photo == null) {
            StatePanel(
                icon = ErrorOutline,
                title = "Fotografia indisponível",
                message = "A fotografia aberta não faz mais parte do snapshot atual.",
                actionLabel = "Voltar à biblioteca",
                action = { callbacks.dispatch(Action.CloseDetail) },
            )
            return@BoxWithConstraints
        }
    val visibleIndex = state.visiblePhotos.indexOfFirst { it.id == photo.id }
    val previousAvailable = visibleIndex > 0
    val nextAvailable = visibleIndex >= 0 && visibleIndex < state.visiblePhotos.lastIndex
    val horizontalInspector = maxWidth >= 900.dp || maxHeight < 640.dp
    val inspectorWidth = if (maxWidth < 900.dp) 290.dp else 370.dp
    if (horizontalInspector) {
        Row(Modifier.fillMaxSize()) {
            DetailImageArea(photo, state, callbacks, Modifier.weight(1f).fillMaxHeight(), previousAvailable, nextAvailable)
            DetailInspector(photo, state, callbacks, Modifier.width(inspectorWidth).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            DetailImageArea(photo, state, callbacks, Modifier.weight(1f).fillMaxWidth(), previousAvailable, nextAvailable)
            DetailInspector(photo, state, callbacks, Modifier.fillMaxWidth().heightIn(max = 340.dp))
        }
    }
}
}

@Composable
private fun DetailImageArea(photo: Photo, state: AppState, callbacks: AppCallbacks, modifier: Modifier, previousAvailable: Boolean = true, nextAvailable: Boolean = true) {
    var problemOpen by remember(photo.id) { mutableStateOf(false) }
    var zoom by remember(photo.id) { mutableFloatStateOf(MIN_DETAIL_ZOOM) }
    var pan by remember(photo.id) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember(photo.id) { mutableStateOf(IntSize.Zero) }
    var imageSize by remember(photo.id) { mutableStateOf(IntSize.Zero) }

    fun updateZoom(target: Float, focalPoint: Offset = Offset(viewportSize.width / 2f, viewportSize.height / 2f)) {
        val next = target.coerceIn(MIN_DETAIL_ZOOM, MAX_DETAIL_ZOOM)
        if (next == MIN_DETAIL_ZOOM) {
            zoom = next
            pan = Offset.Zero
            return
        }
        val anchoredPan = focalDetailPan(pan, zoom, next, focalPoint, viewportSize)
        zoom = next
        pan = constrainDetailPan(anchoredPan, next, viewportSize, imageSize)
    }

    LaunchedEffect(viewportSize, imageSize) { pan = constrainDetailPan(pan, zoom, viewportSize, imageSize) }
    Column(
        modifier.background(Stage).focusable().onFocusChanged { callbacks.shortcutScopeFocused(it.hasFocus) }.semantics { contentDescription = "Visualizador da fotografia" },
    ) {
        Box(Modifier.fillMaxWidth().height(58.dp).background(Panel)) {
            AppIconButton(ArrowBack, "Voltar à biblioteca", { callbacks.dispatch(Action.CloseDetail) }, Modifier.align(Alignment.CenterStart).padding(start = 7.dp))
            Column(Modifier.align(Alignment.Center).widthIn(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(photo.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(buildList { add(photo.mediaFormatLabel); photo.metadata.capturedAt?.let { add(formatCapturedAt(it, includeTime = false)) }; if (photo.folder.isNotBlank()) add(photo.folder) }.joinToString(" · "), color = Muted, style = ProofAnnotationStyle, maxLines = 1)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            PhotoImage(
                photo = photo,
                callbacks = callbacks,
                maximumDimension = 4096,
                scale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().clipToBounds().onSizeChanged { viewportSize = it }
                    .pointerInput(photo.id, viewportSize, imageSize) {
                        detectTransformGestures { centroid, panChange, zoomChange, _ ->
                            updateZoom(zoom * zoomChange, centroid)
                            pan = constrainDetailPan(pan + panChange, zoom, viewportSize, imageSize)
                        }
                    }
                    .pointerInput(photo.id, viewportSize, imageSize) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type != PointerEventType.Scroll || event.changes.any { it.isConsumed }) continue
                                val change = event.changes.firstOrNull() ?: continue
                                val delta = change.scrollDelta.y
                                if (delta == 0f) continue
                                updateZoom(zoom * if (delta < 0f) 1.18f else 1f / 1.18f, change.position)
                                change.consume()
                            }
                        }
                    }
                    .semantics {
                        contentDescription = "Área ampliável de ${photo.displayName}"
                        stateDescription = "Zoom atual: ${(zoom * 100).roundToInt()}%"
                    },
                issueClicked = { problemOpen = true },
                imageModifier = Modifier.graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                },
                previewDimensionsChanged = { imageSize = it },
            )
            AppIconButton(ChevronLeft, "Fotografia anterior", { callbacks.navigate(-1) }, Modifier.align(Alignment.CenterStart).padding(8.dp).background(Panel.copy(alpha = .78f), CompactShape), enabled = previousAvailable)
            AppIconButton(ChevronRight, "Próxima fotografia", { callbacks.navigate(1) }, Modifier.align(Alignment.CenterEnd).padding(8.dp).background(Panel.copy(alpha = .78f), CompactShape), enabled = nextAvailable)
            DetailZoomControls(
                zoom = zoom,
                zoomOut = { updateZoom(nextDetailZoom(zoom, -1)) },
                zoomIn = { updateZoom(nextDetailZoom(zoom, 1)) },
                fit = { updateZoom(MIN_DETAIL_ZOOM) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
            )
            if (problemOpen) PhotoProblemPanel(
                photo,
                Modifier.align(Alignment.TopCenter).padding(12.dp).widthIn(max = 460.dp),
                close = { problemOpen = false },
                recover = { callbacks.dispatch(Action.OpenSettings) },
            )
        }
        if (state.visiblePhotos.size > 1) Filmstrip(state, callbacks)
    }
}

@Composable
private fun DetailZoomControls(zoom: Float, zoomOut: () -> Unit, zoomIn: () -> Unit, fit: () -> Unit, modifier: Modifier = Modifier) {
    val percentage = (zoom * 100).roundToInt()
    Surface(
        modifier = modifier.semantics { contentDescription = "Controles de zoom"; stateDescription = "Zoom atual: $percentage%" },
        color = Panel.copy(alpha = .90f),
        shape = CompactShape,
        border = BorderStroke(1.dp, Hairline),
        shadowElevation = 5.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(ZoomOut, "Diminuir zoom", zoomOut, enabled = zoom > MIN_DETAIL_ZOOM + .01f)
            Box(Modifier.width(62.dp).height(44.dp).semantics { contentDescription = "Zoom atual: $percentage%" }, contentAlignment = Alignment.Center) {
                Text("$percentage%", color = TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            }
            AppIconButton(ZoomIn, "Aumentar zoom", zoomIn, enabled = zoom < MAX_DETAIL_ZOOM - .01f)
            AppIconButton(FitScreen, "Ajustar fotografia à janela", fit, enabled = zoom > MIN_DETAIL_ZOOM + .01f)
        }
    }
}

@Composable
private fun Filmstrip(state: AppState, callbacks: AppCallbacks) {
    val visible = state.visiblePhotos
    val current = visible.indexOfFirst { it.id == state.selectedId }.coerceAtLeast(0)
    val start = maxOf(0, current - 8)
    val neighbors = visible.subList(start, minOf(visible.size, current + 9))
    val selectedWithin = (current - start).coerceIn(0, neighbors.lastIndex)
    val filmstripState = rememberLazyListState()
    LaunchedEffect(state.selectedId, selectedWithin) { filmstripState.scrollToItem(selectedWithin) }
    LazyRow(state = filmstripState, modifier = Modifier.fillMaxWidth().height(74.dp).background(Panel).padding(horizontal = 7.dp, vertical = 5.dp).semantics { contentDescription = "Faixa de fotografias próximas" }, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        lazyItems(neighbors, key = { it.id }) { item ->
            val selected = item.id == state.selectedId
            PhotoImage(item, callbacks, 240, ContentScale.Fit, Modifier.width(96.dp).fillMaxHeight().then(if (selected) Modifier.border(2.dp, Amber) else Modifier).combinedClickable(onClick = { callbacks.dispatch(Action.Select(item.id)) }, onDoubleClick = {}).focusable().semantics { contentDescription = "Filmstrip ${item.displayName}"; this.selected = selected })
        }
    }
}

private enum class InspectorTab { INFO, EDIT, RECIPES }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailInspector(photo: Photo, state: AppState, callbacks: AppCallbacks, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var auxiliary by remember(photo.id) { mutableStateOf(AuxiliaryView(status = "Carregando receitas e HDR")) }
    var busy by remember(photo.id) { mutableStateOf(false) }
    var recipesOpen by remember(photo.id) { mutableStateOf(false) }
    var keyword by remember(photo.id) { mutableStateOf("") }
    var keywordError by remember(photo.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(photo.id) { auxiliary = runCatching { callbacks.auxiliary.load(photo) }.getOrElse { AuxiliaryView(error = it.message ?: "Falha ao ler dados auxiliares") } }
    fun runAux(operation: suspend () -> AuxiliaryView) {
        if (busy) return
        busy = true
        scope.launch { auxiliary = runCatching { operation() }.getOrElse { AuxiliaryView(error = it.message ?: "Falha na operação") }; busy = false }
    }
    Column(
        modifier.background(Inspector).verticalScroll(rememberScrollState()).padding(18.dp)
            .semantics { contentDescription = "Painel de curadoria da fotografia" },
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Text("CURADORIA", color = Amber, fontWeight = FontWeight.Bold, style = ProofAnnotationStyle)
        QuickControls(photo, state, callbacks)
        EditorialRatingSlider(photo.editorial.rating, state.writeEnabled && photo.writable, "Avaliação editorial") { callbacks.mutate(photo, photo.editorial.copy(rating = it)) }
        EditorialLabelSelector(photo.editorial.label, state.writeEnabled && photo.writable) { callbacks.mutate(photo, photo.editorial.copy(label = it)) }
        val status = when (photo.writeState) { WriteState.IDLE -> editorialSummary(photo.editorial); WriteState.SAVING -> "Salvando no XMP adjacente"; WriteState.PERSISTED -> "Alterações salvas e verificadas"; WriteState.FAILED -> photo.writeError ?: "Falha ao salvar no XMP" }
        Row(Modifier.fillMaxWidth().clip(CompactShape).background(Stage).padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (photo.writeState == WriteState.FAILED) Close else Check, null, tint = if (photo.writeState == WriteState.FAILED) Danger else PickGreen, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)); Text(status, color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        HorizontalDivider(color = Hairline)
        if (photo.issue != null || photo.writeError != null || photo.metadata.status == MetadataStatus.ERROR || photo.editorial.goodError != null) {
            Column(
                Modifier.fillMaxWidth().clip(CompactShape).background(Danger.copy(alpha = .10f)).border(1.dp, Danger.copy(alpha = .45f), CompactShape)
                    .padding(10.dp).semantics { contentDescription = "Problema da fotografia ${photo.displayName}" },
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(ErrorOutline, null, tint = Danger, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(7.dp))
                    Text(photoProblemText(photo), color = TextPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
                Text("Revise o sidecar em uma cópia segura e sincronize novamente.", color = Muted, style = MaterialTheme.typography.labelSmall)
                OutlinedButton(
                    onClick = { callbacks.dispatch(Action.OpenSettings) },
                    shape = CompactShape,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                    border = BorderStroke(1.dp, Amber.copy(alpha = .72f)),
                    modifier = Modifier.heightIn(min = 44.dp),
                ) { Text("Ir às configurações") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(ImageSearch, null, tint = Muted, modifier = Modifier.size(17.dp))
            Text("METADADOS", color = Amber, fontWeight = FontWeight.Bold, style = ProofAnnotationStyle)
        }
        MetadataTable(photo, callbacks)
        HorizontalDivider(color = Hairline)
        Text("PALAVRAS-CHAVE", color = Amber, fontWeight = FontWeight.Bold, style = ProofAnnotationStyle)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            photo.editorial.keywords.forEach { word -> InputChip(selected = true, shape = CompactShape, onClick = { callbacks.mutate(photo, photo.editorial.copy(keywords = photo.editorial.keywords - word)) }, enabled = state.writeEnabled && photo.writable, label = { Text(word) }, trailingIcon = { Icon(Close, null, Modifier.size(14.dp)) }) }
        }
        val canAddKeyword = keyword.isNotBlank() && state.writeEnabled && photo.writable
        fun addKeyword() {
            if (!canAddKeyword) return
            runCatching { normalizeFlatKeyword(keyword) }
                .onSuccess { normalized -> callbacks.mutate(photo, photo.editorial.copy(keywords = photo.editorial.keywords + normalized)); keyword = ""; keywordError = null }
                .onFailure { keywordError = "Use uma palavra-chave plana, sem |." }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProofTextField(
                value = keyword,
                onValueChange = { keyword = it },
                label = "PALAVRA-CHAVE",
                placeholder = "Palavra-chave",
                leadingIcon = Tag,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addKeyword() }),
                focusChanged = callbacks.textFieldFocused,
                modifier = Modifier.weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) { addKeyword(); true } else false
                    }
                    .semantics { contentDescription = "Nova palavra-chave" },
            )
            AppIconButton(Add, "Adicionar palavra-chave", ::addKeyword, enabled = canAddKeyword)
        }
        keywordError?.let { Text(it, color = Danger, style = MaterialTheme.typography.labelSmall, modifier = Modifier.semantics { contentDescription = "Erro de palavra-chave" }) }
        HorizontalDivider(color = Hairline)
        val rafPhoto = photo.rawPath?.substringAfterLast('.', "")?.equals("raf", true) == true
        val recipeLabel = if (rafPhoto) "receitas e HDR" else "receitas Fuji"
        TextButton(onClick = { recipesOpen = !recipesOpen }, shape = CompactShape, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { contentDescription = if (recipesOpen) "Ocultar $recipeLabel" else "Mostrar $recipeLabel" }) {
            Icon(Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text(if (rafPhoto) "Receitas e HDR" else "Receitas Fuji", Modifier.weight(1f)); Icon(if (recipesOpen) ChevronLeft else ChevronRight, null)
        }
        if (recipesOpen) {
            FujiPanel(photo, state, auxiliary, busy, ::runAux, callbacks)
            if (photo.rawPath?.substringAfterLast('.', "")?.equals("raf", true) == true) {
                HorizontalDivider(color = Hairline)
                HdrPanel(photo, state, auxiliary, busy, ::runAux, callbacks)
            }
            auxiliary.error?.let { Text(it, color = Danger) } ?: Text(auxiliary.status, color = Muted)
        }
    }
}

@Composable
private fun MetadataTable(photo: Photo, callbacks: AppCallbacks) = photo.metadata.run {
    val rows = buildList<Triple<ImageVector, String, String>> {
        add(Triple(CameraAlt, "Câmera", cameraDisplay ?: "Não informada"))
        add(Triple(Lens, "Lente", lens ?: "Não informada"))
        add(Triple(CalendarMonth, "Capturada", capturedAt?.let { formatCapturedAt(it) } ?: "Não informada"))
        val exposure = buildList { focalLength?.let { add("${formatDecimal(it)} mm") }; aperture?.let { add("f/${formatDecimal(it)}") }; exposureSeconds?.let { add(formatExposureTime(it)) }; iso?.let { add("ISO $it") } }.joinToString("  ")
        if (exposure.isNotEmpty()) add(Triple(ShutterSpeed, "Exposição", exposure))
        if (width != null && height != null) add(Triple(AspectRatio, "Dimensões", "$width × $height"))
        add(Triple(Tag, "Formato", photo.mediaFormatLabel))
        photo.rawPath?.let { add(Triple(ImageSearch, "Arquivo RAW", it.substringAfterLast('/'))) }
        photo.jpegPath?.let { add(Triple(PhotoLibrary, "Arquivo JPEG", it.substringAfterLast('/'))) }
    }
    rows.forEach { (icon, description, value) ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 38.dp).semantics(mergeDescendants = true) { contentDescription = "$description: $value" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(30.dp).clip(CompactShape).background(Stage).border(1.dp, Hairline, CompactShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Muted, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(value, color = TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    if (hasGps) OutlinedButton(onClick = { callbacks.openMap(latitude!!, longitude!!) }, shape = CompactShape, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Icon(LocationOn, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Abrir localização no OSM") }
}

private fun formatExposureTime(seconds: Double): String {
    if (seconds > 0.0 && seconds < 1.0) {
        val denominator = kotlin.math.round(1.0 / seconds).toInt().coerceAtLeast(1)
        return "1/$denominator s"
    }
    val value = if (seconds % 1.0 == 0.0) seconds.toInt().toString() else seconds.toString()
    return "$value s"
}

@Composable
private fun SettingsScreen(state: AppState, callbacks: AppCallbacks) {
    val initialFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { initialFocusRequester.requestFocus() }
    Box(Modifier.fillMaxSize().onFocusChanged { callbacks.shortcutScopeFocused(it.hasFocus) }, contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.fillMaxHeight().widthIn(max = 820.dp).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) { AppIconButton(ArrowBack, "Voltar à galeria", { callbacks.dispatch(Action.CloseSettings) }, Modifier.focusRequester(initialFocusRequester)); Spacer(Modifier.width(10.dp)); Text("Configurações", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
            SettingsCard("BIBLIOTECA") {
                SettingsValue("Pasta", state.library ?: "Não selecionada")
                SettingsValue("Cache", state.cache ?: "Não configurado")
                OutlinedButton(onClick = callbacks.chooseLibrary, shape = CompactShape, enabled = !state.sync.running, border = BorderStroke(1.dp, Hairline), modifier = Modifier.heightIn(min = 44.dp)) { Icon(Folder, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Escolher pasta da biblioteca") }
            }
            SettingsCard("EDIÇÃO") {
                val readOnlyMode = !state.writeEnabled
                val pendingWrite = state.photos.any { it.writeState == WriteState.SAVING }
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Modo somente leitura", fontWeight = FontWeight.SemiBold)
                        Text(if (readOnlyMode) "Ativado" else "Desativado", color = if (readOnlyMode) Muted else Amber, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = readOnlyMode,
                        onCheckedChange = callbacks.setReadOnlyMode,
                        enabled = !pendingWrite,
                        modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "Modo somente leitura" },
                    )
                }
                Text(
                    when {
                        pendingWrite -> "Aguarde a conclusão da edição em andamento antes de alterar o modo."
                        readOnlyMode -> "Novas alterações em XMP e FP2 estão bloqueadas. Esta preferência vale para as próximas aberturas."
                        else -> "A escrita está liberada apenas para XMP adjacente e FP2 editável. RAW e JPEG permanecem imutáveis. Esta preferência vale para as próximas aberturas."
                    },
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SettingsCard("SINCRONIZAÇÃO", warning = state.sync.phase == SyncPhase.FAILED) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val syncIcon = when (state.sync.phase) { SyncPhase.FAILED -> ErrorOutline; SyncPhase.COMPLETE -> Check; else -> RefreshVectorIcon }
                    val syncTint = when (state.sync.phase) { SyncPhase.FAILED -> Danger; SyncPhase.COMPLETE -> PickGreen; else -> Amber }
                    Icon(syncIcon, null, tint = syncTint, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(syncPhaseLabel(state.sync.phase), fontWeight = FontWeight.SemiBold)
                }
                if (showSyncMessage(state.sync.phase, state.sync.message)) Text(state.sync.message, color = if (state.sync.phase == SyncPhase.FAILED) Danger else TextPrimary)
                Text("Diretórios ${state.sync.directories} · Arquivos ${state.sync.files} · Fotos ${state.sync.photos} · Erros ${state.sync.errors}", color = Muted, style = MaterialTheme.typography.bodySmall)
                if (!state.sync.running && state.sync.durationMillis != null) Text(
                    "Duração ${formatDuration(state.sync.durationMillis)}" + if (state.sync.added != null && state.sync.removed != null && state.sync.updated != null) " · +${state.sync.added} / −${state.sync.removed} / ${state.sync.updated} atualizadas" else "",
                    color = Muted, style = MaterialTheme.typography.bodySmall,
                )
                if (state.sync.running && state.sync.currentItem.isNotBlank()) Text("Processando ${state.sync.currentItem}", color = TextPrimary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.sync.running) {
                    val total = state.sync.totalPhotos
                    if (total != null && total > 0) LinearProgressIndicator(progress = { (state.sync.photos.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(onClick = callbacks.cancelSync, shape = CompactShape, modifier = Modifier.heightIn(min = 44.dp)) { Icon(Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Cancelar") }
                } else Button(onClick = callbacks.synchronize, shape = CompactShape, enabled = state.library != null, modifier = Modifier.heightIn(min = 44.dp)) { Icon(RefreshVectorIcon, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text(if (state.sync.phase == SyncPhase.FAILED) "Tentar novamente" else "Sincronizar") }
            }
            SettingsCard("SEGURANÇA") {
                Text("A sincronização é somente leitura. O PhotoTool nunca altera os bytes de arquivos RAW ou JPEG. O modo de escrita explícito altera apenas XMP adjacente e FP2 editável, após verificações de identidade, topologia, bytes e releitura.", color = Muted)
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, warning: Boolean = false, content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxWidth().background(if (warning) Danger.copy(alpha = .06f) else Color.Transparent).padding(horizontal = 16.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
) {
    HorizontalDivider(color = if (warning) Danger.copy(alpha = .55f) else Hairline)
    Text(title, color = Amber, style = ProofAnnotationStyle, fontWeight = FontWeight.Bold)
    content()
}

@Composable
private fun SettingsValue(label: String, value: String) = Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
    Text(value, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

private fun syncPhaseLabel(phase: SyncPhase): String = when (phase) {
    SyncPhase.IDLE -> "Aguardando"
    SyncPhase.DISCOVERING -> "Descobrindo arquivos"
    SyncPhase.METADATA -> "Lendo metadados"
    SyncPhase.INDEXING -> "Lendo ajustes editoriais"
    SyncPhase.PUBLISHING -> "Publicando snapshot"
    SyncPhase.COMPLETE -> "Concluída"
    SyncPhase.CANCELLED -> "Cancelada"
    SyncPhase.FAILED -> "Falha"
}

private fun showSyncMessage(phase: SyncPhase, message: String): Boolean {
    val normalized = message.trim().trimEnd('.').lowercase()
    val label = syncPhaseLabel(phase).lowercase()
    return normalized.isNotEmpty() && normalized != label && normalized != "sincronização $label"
}

private fun formatDuration(millis: Long): String = if (millis < 60_000) "${millis / 1000}s" else "${millis / 60_000}min ${millis % 60_000 / 1000}s"

@Composable
private fun StatePanel(icon: ImageVector, title: String, message: String, actionLabel: String, action: () -> Unit, requestInitialFocus: Boolean = true) {
    val actionFocusRequester = remember { FocusRequester() }
    LaunchedEffect(actionLabel) { if (requestInitialFocus) actionFocusRequester.requestFocus() }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 440.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(58.dp).clip(RoundedCornerShape(7.dp)).background(Raised), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Amber, modifier = Modifier.size(28.dp)) }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(message, color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Button(onClick = action, shape = CompactShape, modifier = Modifier.heightIn(min = 44.dp).focusRequester(actionFocusRequester)) { Text(actionLabel) }
        }
    }
}

@Composable
private fun EmptyLibrary(callbacks: AppCallbacks) = StatePanel(PhotoLibrary, "Escolha uma biblioteca de fotos", "O PhotoTool indexa a pasta sem alterar os arquivos originais.", "Escolher pasta", callbacks.chooseLibrary)

@Composable
private fun EmptyGallery(state: AppState, callbacks: AppCallbacks, requestInitialFocus: Boolean = true) = if (state.photos.isEmpty()) {
    StatePanel(PhotoLibrary, "Nenhuma fotografia indexada", "Sincronize a biblioteca para criar um novo snapshot.", "Sincronizar agora", callbacks.synchronize, requestInitialFocus)
} else {
    StatePanel(ImageSearch, "Nenhum resultado", "Nenhuma fotografia corresponde à busca e aos filtros atuais.", "Limpar busca e filtros", { callbacks.dispatch(Action.SetQuery(Query(sort = state.query.sort))) }, requestInitialFocus)
}
@Composable private fun ShortcutHelp(close: () -> Unit) { AlertDialog(onDismissRequest = close, confirmButton = { TextButton(onClick = close, shape = CompactShape) { Text("Fechar") } }, icon = { Icon(HelpOutline, null) }, title = { Text("Atalhos de teclado") }, text = { Text("Galeria: setas navegam, Enter abre o detalhe e Escape fecha painéis.\nDetalhe: setas esquerda e direita navegam; P, U e X alteram flags; 0 a 5 definem avaliação; N, R, Y e G definem rótulos.\nAtalhos são ignorados enquanto um campo de texto está em foco.") }) }
