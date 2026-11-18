package br.com.lincon.phototool.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.semantics.Role
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
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

// Aero/Aqua identity. Every material, rim and accent comes from AeroTheme.kt; these
// aliases keep the call sites readable and paint-only.
private val Ink = Aero.ink0
private val Stage = Color(0xff04070e)
private val Panel = Aero.ink1
private val Inspector = Aero.ink2
private val Raised = Aero.ink3
private val RaisedHover = Aero.ink4
private val Hairline = Color(0xff26374d)
private val Accent = Aero.sky
private val AccentWash = Color(0xff10304a)
private val Sunken = Color(0xff061020)
private val TextPrimary = Aero.text
private val Muted = Aero.text3
private val Danger = Aero.rose
private val Focus = Aero.sky
private val Mint = Aero.mint
private val Caution = Aero.amber
private val LabelRed = Aero.rose
private val LabelYellow = Aero.amber
private val LabelGreen = Aero.mint
private val CompactShape = RoundedCornerShape(6.dp)
private val CardShape = RoundedCornerShape(7.dp)
private val FlatShape = RoundedCornerShape(0.dp)

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

/** Material buttons paint nothing themselves: the gel brush comes from [aeroGel]. */
@Composable
private fun gelButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color.Transparent,
    contentColor = Ink,
    disabledContainerColor = Color.Transparent,
    disabledContentColor = Ink.copy(alpha = .45f),
)

@Composable
fun PhotoToolTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Aero.sky,
            onPrimary = Ink,
            primaryContainer = Aero.sky3,
            onPrimaryContainer = Aero.text,
            secondary = Aero.mint,
            onSecondary = Ink,
            background = Ink,
            onBackground = TextPrimary,
            surface = Panel,
            onSurface = TextPrimary,
            surfaceVariant = Raised,
            onSurfaceVariant = Muted,
            surfaceContainerHighest = Raised,
            outline = Hairline,
            outlineVariant = Aero.edge,
            error = Danger,
            onError = Ink,
        ),
        typography = aeroTypography(),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(5.dp),
            small = RoundedCornerShape(6.dp),
            medium = CompactShape,
            large = RoundedCornerShape(9.dp),
            extraLarge = RoundedCornerShape(11.dp),
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
    drawRect(Accent, topLeft = Offset(left, top), size = androidx.compose.ui.geometry.Size(right - left, bottom - top), style = Stroke(stroke))
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
    ).forEach { (start, end) -> drawLine(Accent, start, end, strokeWidth = stroke) }
    drawCircle(Accent, radius = size.minDimension * .045f, center = Offset(size.width * .76f, size.height * .50f))
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
            .then(
                when {
                    selected -> Modifier.background(AccentWash).aeroRim(CompactShape, Aero.accentRim)
                    pressed -> Modifier.background(RaisedHover)
                    hovered -> Modifier.background(Aero.card).aeroRim(CompactShape)
                    else -> Modifier
                },
            )
            .then(if (focused) Modifier.border(2.dp, Focus, CompactShape) else Modifier)
            .hoverable(interaction, enabled)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = when { !enabled -> Muted.copy(alpha = .42f); selected -> Accent; else -> TextPrimary }, modifier = Modifier.size(20.dp))
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

/** Zoom where native crops start. Asking for and painting them share this single threshold. */
private const val REGION_START_ZOOM = 4f
private const val BASE_MAX_DETAIL_ZOOM = 8f
private const val HARD_MAX_DETAIL_ZOOM = 64f
private val baseDetailZoomStops = listOf(1f, 1.25f, 1.5f, 2f, 3f, 4f, 6f, 8f)

/** Zoom at which one image pixel maps to one screen pixel for the current Fit layout. */
internal fun detailPixelZoom(viewport: IntSize, image: IntSize): Float {
    if (viewport.width <= 0 || viewport.height <= 0 || image.width <= 0 || image.height <= 0) return BASE_MAX_DETAIL_ZOOM
    val fit = minOf(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
    return (1f / fit).coerceAtLeast(MIN_DETAIL_ZOOM)
}

/**
 * Zoom from which native crops are asked for, kept and painted. One function for all three, because
 * asking and clearing on different thresholds leaves stale crops over the upscaled preview.
 */
internal fun detailRegionZoom(pixelZoom: Float): Float = minOf(pixelZoom, REGION_START_ZOOM)

/** Enough magnification to inspect the actual pixels, never below the previous 8x ceiling. */
internal fun detailMaxZoom(pixelZoom: Float): Float =
    maxOf(BASE_MAX_DETAIL_ZOOM, pixelZoom * 4f).coerceAtMost(HARD_MAX_DETAIL_ZOOM)

internal fun detailZoomStops(pixelZoom: Float): List<Float> {
    val maximum = detailMaxZoom(pixelZoom)
    return (baseDetailZoomStops + listOf(pixelZoom, pixelZoom * 2f, pixelZoom * 4f))
        .filter { it in MIN_DETAIL_ZOOM..maximum }
        .distinct()
        .sorted()
}

/** Nearest-neighbour from the point where pixels start to be inspected; smooth below it. */
internal fun detailFilterQuality(zoom: Float, pixelZoom: Float): FilterQuality =
    if (zoom >= maxOf(2f, pixelZoom * .5f)) FilterQuality.None else FilterQuality.Low

internal data class NativeRegion(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun contains(other: NativeRegion): Boolean =
        other.x >= x && other.y >= y && other.x + other.width <= x + width && other.y + other.height <= y + height
}

internal data class RegionView(val bitmap: ImageBitmap, val x: Int, val y: Int, val width: Int, val height: Int) {
    val region: NativeRegion get() = NativeRegion(x, y, width, height)
}

/** Where a native rectangle lands on screen, with every edge rounded once. */
internal data class RegionPlacement(val x: Int, val y: Int, val width: Int, val height: Int)

/** Native margin fetched around the visible window at 1:1, so a drag has sharp pixels ahead. */
internal const val DETAIL_REGION_MARGIN = 2.6f
/** Native pixels one crop may ask for; this is also the server's bound for a single region. */
internal const val DETAIL_REGION_TILE_PIXELS = 4_000_000L
/** Native pixels one pump may ask for in total, so a wide window becomes a few bounded tiles. */
internal const val DETAIL_REGION_TOTAL_PIXELS = 6_000_000L
/** Two pumps' worth of tiles, so a pan never evicts the set that covers the current window. */
internal const val DETAIL_REGION_BUDGET_PIXELS = 2 * DETAIL_REGION_TOTAL_PIXELS
/** Floor between two crop requests, so a fast drag conflates instead of flooding the decoder. */
internal const val DETAIL_REGION_MIN_SPACING_MS = 60L

/** Native-image rectangle currently visible, with margin for panning, bounded for decoding. */
internal fun visibleNativeRegion(
    viewport: IntSize,
    native: IntSize,
    zoom: Float,
    pan: Offset,
    margin: Float = DETAIL_REGION_MARGIN,
    maximumSide: Int = 2048,
    maximumPixels: Long = 3_000_000L,
    lead: Offset = Offset.Zero,
): NativeRegion? {
    if (viewport.width <= 0 || viewport.height <= 0 || native.width <= 0 || native.height <= 0 || zoom <= 0f) return null
    val fit = minOf(viewport.width.toFloat() / native.width, viewport.height.toFloat() / native.height)
    if (fit <= 0f) return null
    val left = (viewport.width - native.width * fit) / 2f
    val top = (viewport.height - native.height * fit) / 2f
    val center = Offset(viewport.width / 2f, viewport.height / 2f)
    fun sourceAt(x: Float, y: Float): Offset {
        val ux = center.x + (x - center.x - pan.x) / zoom
        val uy = center.y + (y - center.y - pan.y) / zoom
        return Offset((ux - left) / fit, (uy - top) / fit)
    }
    val first = sourceAt(0f, 0f)
    val second = sourceAt(viewport.width.toFloat(), viewport.height.toFloat())
    val visibleWidth = kotlin.math.abs(second.x - first.x)
    val visibleHeight = kotlin.math.abs(second.y - first.y)
    var width = (visibleWidth * margin).coerceIn(1f, native.width.toFloat())
    var height = (visibleHeight * margin).coerceIn(1f, native.height.toFloat())
    if (width > maximumSide) { height *= maximumSide / width; width = maximumSide.toFloat() }
    if (height > maximumSide) { width *= maximumSide / height; height = maximumSide.toFloat() }
    while (width * height > maximumPixels) { width *= .9f; height *= .9f }
    // Lead the drag: bias the margin towards where the view is heading, never beyond half its slack.
    val leadX = ((width - visibleWidth) / 2f).coerceAtLeast(0f) * lead.x.coerceIn(-1f, 1f)
    val leadY = ((height - visibleHeight) / 2f).coerceAtLeast(0f) * lead.y.coerceIn(-1f, 1f)
    val w = width.roundToInt().coerceIn(1, native.width)
    val h = height.roundToInt().coerceIn(1, native.height)
    if (w.toLong() * h > maximumPixels) return null
    // Shift the window instead of shrinking it at the image edges, so the crop still covers the view.
    val x = ((first.x + second.x) / 2f + leadX - w / 2f).roundToInt().coerceIn(0, native.width - w)
    val y = ((first.y + second.y) / 2f + leadY - h / 2f).roundToInt().coerceIn(0, native.height - h)
    return NativeRegion(x, y, w, h)
}

/** What the frame actually shows, without the caps that bound a single crop for decoding. */
internal fun visibleNativeWindow(viewport: IntSize, native: IntSize, zoom: Float, pan: Offset): NativeRegion? =
    visibleNativeRegion(
        viewport, native, zoom, pan, margin = 1f,
        maximumSide = maxOf(native.width, native.height),
        maximumPixels = Long.MAX_VALUE,
    )

private fun ceilingDivide(value: Int, divisor: Int): Int = if (value <= 0) 0 else (value - 1) / divisor + 1

/** Largest tile side a split of [extent] into [parts] produces. */
private fun tileSide(extent: Int, parts: Int): Int = ceilingDivide(extent, parts)

/**
 * Native rectangles that together cover the visible window. One crop used to be the whole answer,
 * capped at 2048 px, so from the region threshold upwards the frame showed sharp pixels in the
 * middle and the upscaled preview around them: a seam crossing the photograph. The window is split
 * into contiguous tiles instead, each one within what the server decodes and the whole set within
 * what one pump may ask for. Windows that still fit stay a single crop with the panning margin.
 *
 * A window larger than [totalPixels] is covered as a whole anyway: coverage wins over the budget,
 * because leaving the excess on the upscaled preview is the visible defect this replaces.
 */
internal fun visibleNativeRegions(
    viewport: IntSize,
    native: IntSize,
    zoom: Float,
    pan: Offset,
    margin: Float = DETAIL_REGION_MARGIN,
    maximumSide: Int = 2048,
    maximumPixels: Long = DETAIL_REGION_TILE_PIXELS,
    totalPixels: Long = DETAIL_REGION_TOTAL_PIXELS,
    lead: Offset = Offset.Zero,
): List<NativeRegion> {
    val visible = visibleNativeWindow(viewport, native, zoom, pan) ?: return emptyList()
    val visiblePixels = visible.width.toLong() * visible.height
    // One crop is only the answer when the window, plus its panning margin, still fits decoding.
    // Checking the window alone was the bug: a window taller than one crop looked covered even
    // though the capped crop could not reach its edges.
    if (visible.width <= maximumSide && visible.height <= maximumSide && visiblePixels <= maximumPixels) {
        val single = visibleNativeRegion(viewport, native, zoom, pan, margin, maximumSide, maximumPixels, lead)
        if (single != null && single.contains(visible)) return listOf(single)
    }
    // Grow the window by the panning margin only as far as one pump budget allows, keeping it
    // centred on what is shown; then split that rectangle into contiguous tiles.
    val growth = kotlin.math.sqrt(maxOf(totalPixels, visiblePixels).toDouble() / visiblePixels).toFloat()
    var allowedWidth = (visible.width * growth).roundToInt().coerceIn(visible.width, native.width)
    var allowedHeight = (visible.height * growth).roundToInt().coerceIn(visible.height, native.height)
    // Rounding both sides up can overshoot the area budget by a few pixels; shave the larger side
    // until the plan is back inside it. The window itself is never shrunk.
    while (allowedWidth.toLong() * allowedHeight > totalPixels && (allowedWidth > visible.width || allowedHeight > visible.height)) {
        if (allowedWidth >= allowedHeight && allowedWidth > visible.width) allowedWidth--
        else if (allowedHeight > visible.height) allowedHeight--
        else allowedWidth--
    }
    val coveredWidth = (visible.width * margin).roundToInt().coerceIn(visible.width, allowedWidth)
    val coveredHeight = (visible.height * margin).roundToInt().coerceIn(visible.height, allowedHeight)
    val left = (visible.x + visible.width / 2 - coveredWidth / 2).coerceIn(0, native.width - coveredWidth)
    val top = (visible.y + visible.height / 2 - coveredHeight / 2).coerceIn(0, native.height - coveredHeight)
    var columns = ceilingDivide(coveredWidth, maximumSide).coerceAtLeast(1)
    var rows = ceilingDivide(coveredHeight, maximumSide).coerceAtLeast(1)
    while (tileSide(coveredWidth, columns).toLong() * tileSide(coveredHeight, rows) > maximumPixels) {
        if (tileSide(coveredWidth, columns) >= tileSide(coveredHeight, rows)) columns++ else rows++
    }
    return buildList(columns * rows) {
        for (row in 0 until rows) {
            val y0 = top + coveredHeight * row / rows
            val y1 = top + coveredHeight * (row + 1) / rows
            for (column in 0 until columns) {
                val x0 = left + coveredWidth * column / columns
                val x1 = left + coveredWidth * (column + 1) / columns
                add(NativeRegion(x0, y0, x1 - x0, y1 - y0))
            }
        }
    }
}

/** First planned tile no retained crop and no failed request already covers. */
internal fun missingNativeRegion(
    tiles: List<NativeRegion>,
    covered: List<NativeRegion>,
    failed: List<NativeRegion>,
): NativeRegion? = tiles.firstOrNull { tile ->
    (covered.asSequence() + failed.asSequence()).none { it.contains(tile) }
}

/** Exact area of [visible] covered by [regions]. Overlap is counted once, so a gap cannot hide. */
internal fun coveredNativeArea(regions: List<NativeRegion>, visible: NativeRegion): Long {
    if (visible.width <= 0 || visible.height <= 0) return 0L
    val windowLeft = visible.x.toLong()
    val windowRight = windowLeft + visible.width
    val windowTop = visible.y.toLong()
    val windowBottom = windowTop + visible.height
    val cuts = buildList {
        add(windowLeft)
        add(windowRight)
        regions.forEach { region ->
            val left = region.x.toLong().coerceIn(windowLeft, windowRight)
            val right = (region.x.toLong() + region.width).coerceIn(windowLeft, windowRight)
            if (right > left) { add(left); add(right) }
        }
    }.distinct().sorted()
    var covered = 0L
    for (index in 0 until cuts.size - 1) {
        val stripLeft = cuts[index]
        val stripRight = cuts[index + 1]
        if (stripRight <= stripLeft) continue
        val spans = regions.mapNotNull { region ->
            val left = region.x.toLong()
            val right = left + region.width
            if (right <= stripLeft || left >= stripRight) return@mapNotNull null
            val top = region.y.toLong().coerceIn(windowTop, windowBottom)
            val bottom = (region.y.toLong() + region.height).coerceIn(windowTop, windowBottom)
            if (bottom > top) top to bottom else null
        }.sortedBy { it.first }
        var stripCovered = 0L
        var currentTop = -1L
        var currentBottom = -1L
        spans.forEach { (top, bottom) ->
            if (bottom <= currentBottom) return@forEach
            if (top > currentBottom) {
                stripCovered += currentBottom - currentTop
                currentTop = top
                currentBottom = bottom
            } else currentBottom = bottom
        }
        if (currentBottom > currentTop) stripCovered += currentBottom - currentTop
        covered += (stripRight - stripLeft) * stripCovered
    }
    return covered
}

/** Fraction of [visible] already covered by retained crops, exactly, never by sampling. */
internal fun regionCoverage(regions: List<NativeRegion>, visible: NativeRegion): Float {
    if (regions.isEmpty() || visible.width <= 0 || visible.height <= 0) return 0f
    val total = visible.width.toLong() * visible.height
    return (coveredNativeArea(regions, visible).toDouble() / total).coerceAtMost(1.0).toFloat()
}

/**
 * Screen rectangle of a native [region] for a fitted [native] in [area]. Edges are rounded once,
 * from the native edge, so two adjacent tiles share the exact same screen column: rounding each
 * width on its own used to leave a one-pixel gap where the upscaled preview showed through as a
 * line across the photograph.
 */
internal fun regionPlacement(region: NativeRegion, native: IntSize, area: IntSize): RegionPlacement {
    if (native.width <= 0 || native.height <= 0 || area.width <= 0 || area.height <= 0) return RegionPlacement(0, 0, 0, 0)
    val factor = minOf(area.width.toFloat() / native.width, area.height.toFloat() / native.height)
    val boxWidth = (native.width * factor).roundToInt().coerceAtLeast(1)
    val boxHeight = (native.height * factor).roundToInt().coerceAtLeast(1)
    val left = ((area.width - boxWidth) / 2f).roundToInt()
    val top = ((area.height - boxHeight) / 2f).roundToInt()
    val x0 = left + (region.x * factor).roundToInt()
    val y0 = top + (region.y * factor).roundToInt()
    val x1 = left + ((region.x + region.width) * factor).roundToInt()
    val y1 = top + ((region.y + region.height) * factor).roundToInt()
    return RegionPlacement(x0, y0, (x1 - x0).coerceAtLeast(1), (y1 - y0).coerceAtLeast(1))
}

/** Newest first, trimmed to a pixel budget that always keeps the newest crop. */
internal fun retainRegions(regions: List<RegionView>, budgetPixels: Long = DETAIL_REGION_BUDGET_PIXELS): List<RegionView> =
    regions.take(retainedRegionCount(regions.map { it.width.toLong() * it.height }, budgetPixels))

/** How many crops, newest first, fit in [budgetPixels]. The newest one always counts. */
internal fun retainedRegionCount(pixelSizes: List<Long>, budgetPixels: Long = DETAIL_REGION_BUDGET_PIXELS): Int {
    var total = 0L
    var kept = 0
    for (pixels in pixelSizes) {
        if (kept > 0 && total + pixels > budgetPixels) break
        total += pixels
        kept++
    }
    return kept
}

/** Direction the view is heading, in [-1, 1] per axis, from the pan moved since the last crop. */
internal fun detailRegionLead(movement: Offset, viewport: IntSize): Offset {
    val reach = minOf(viewport.width, viewport.height).coerceAtLeast(1) * .3f
    val magnitude = kotlin.math.sqrt(movement.x * movement.x + movement.y * movement.y)
    if (magnitude < 1f) return Offset.Zero
    val scale = (magnitude / reach).coerceIn(0f, 1f) * .75f
    return Offset(-movement.x / magnitude * scale, -movement.y / magnitude * scale)
}

internal fun nextDetailZoom(current: Float, direction: Int, stops: List<Float> = baseDetailZoomStops, maximum: Float = BASE_MAX_DETAIL_ZOOM): Float = when {
    direction > 0 -> stops.firstOrNull { it > current + .01f } ?: maximum
    direction < 0 -> stops.lastOrNull { it < current - .01f } ?: MIN_DETAIL_ZOOM
    else -> current.coerceIn(MIN_DETAIL_ZOOM, maximum)
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

data class AppCapabilities(
    val localLibrary: Boolean = true,
    val synchronize: Boolean = true,
    val auxiliary: Boolean = true,
    val calendar: Boolean = true,
    val folders: Boolean = true,
    val batchEditorial: Boolean = true,
) {
    fun supports(section: LibrarySection): Boolean = when (section) {
        LibrarySection.CALENDAR -> calendar
        LibrarySection.FOLDERS -> folders
        else -> true
    }

    companion object {
        val Remote = AppCapabilities(false, false, false, false, false, false)
        val RemoteReadOnly = Remote
    }
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
    val capabilities: AppCapabilities = AppCapabilities(),
    val refresh: () -> Unit = {},
    val changePage: (Int) -> Unit = {},
    val presentDetail: (Int, Boolean) -> Boolean = { _, _ -> false },
)

@Composable
fun PhotoToolApp(state: AppState, callbacks: AppCallbacks, modifier: Modifier = Modifier) = PhotoToolTheme {
    Surface(modifier.fillMaxSize().semantics { contentDescription = "Aplicativo PhotoTool" }, color = Ink) {
        Column(Modifier.fillMaxSize()) {
        state.remote?.let { RemoteRecoveryNotice(it, callbacks) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().aeroAtmosphere()) {
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
                    Modifier.matchParentSize().background(Brush.radialGradient(listOf(Color(0x8c060e1a), Color(0xdb03070d))))
                        .clickable(interactionSource = modalInteraction, indication = null) {}
                        .semantics { contentDescription = "Filtros modais abertos" },
                ) {
                    FilterPanel(state, callbacks, appWidth, appHeight, Modifier.align(Alignment.Center))
                }
            }
        }
        }
        if (state.helpOpen) ShortcutHelp { callbacks.dispatch(Action.ToggleHelp) }
    }
}

@Composable
private fun RemoteRecoveryNotice(remote: RemoteBrowseState, callbacks: AppCallbacks) {
    val message = remote.error
        ?: remote.editorialNotice?.takeIf { remote.editorialNeedsRefresh }
        ?: if (remote.projection == "DEGRADED") "Snapshot degradado. Edição indisponível." else return
    Row(Modifier.fillMaxWidth().aeroFill(FlatShape, Aero.panel, rim = null).aeroEdgeLine(bottom = true).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            message,
            color = Danger,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        AppIconButton(RefreshVectorIcon, "Atualizar snapshot", callbacks.refresh, enabled = !remote.loading && remote.savingPhotoId == null)
    }
}

@Composable
private fun RemotePagination(remote: RemoteBrowseState, callbacks: AppCallbacks) {
    Row(Modifier.fillMaxWidth().aeroFill(FlatShape, Aero.panel, rim = null).aeroEdgeLine(top = true).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        AppIconButton(ChevronLeft, "Página anterior", { callbacks.changePage(-1) }, enabled = !remote.loading && remote.page > 1)
        Text("Página ${remote.page} de ${remote.totalPages}", style = MaterialTheme.typography.labelMedium, color = Muted)
        AppIconButton(ChevronRight, "Próxima página", { callbacks.changePage(1) }, enabled = !remote.loading && remote.page < remote.totalPages)
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
    val menuEntries: @Composable () -> Unit = {
        DropdownMenuItem(
            text = { Text("Recentes") }, leadingIcon = { Icon(ImageSearch, null) },
            onClick = { moreOpen = false; callbacks.dispatch(Action.Navigate(LibrarySection.LATEST)) },
        )
        DropdownMenuItem(
            text = { Text("Ajuda de atalhos") }, leadingIcon = { Icon(HelpOutline, null) },
            onClick = { moreOpen = false; callbacks.dispatch(Action.ToggleHelp) },
        )
        DropdownMenuItem(
            text = { Text("Configurações") }, leadingIcon = { Icon(Settings, null) },
            onClick = { moreOpen = false; callbacks.dispatch(Action.OpenSettings) },
        )
    }
    Column {
    // Keep the same menu items in-root on web, avoiding a transient accessibility owner.
    if (state.remote != null && moreOpen) Column(Modifier.fillMaxWidth().background(Raised)) { menuEntries() }
    Row(
        Modifier.fillMaxWidth().height(60.dp).aeroFill(FlatShape, Aero.window, rim = null).aeroEdgeLine(top = true),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            Triple(LibrarySection.ALL_PHOTOS, PhotoLibrary, "Todas as fotos"),
            Triple(LibrarySection.CALENDAR, CalendarMonth, "Calendário"),
            Triple(LibrarySection.FOLDERS, Folder, "Pastas"),
            Triple(LibrarySection.PICKS, PickFlag, "Escolhidas"),
        ).forEach { (section, icon, label) ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                AppIconButton(icon, label, { callbacks.dispatch(Action.Navigate(section)) }, selected = state.section == section && state.screen != Screen.SETTINGS, enabled = callbacks.capabilities.supports(section))
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            AppIconButton(MoreHoriz, "Mais opções", { moreOpen = !moreOpen }, selected = moreOpen || state.section == LibrarySection.LATEST || state.screen == Screen.SETTINGS)
            if (state.remote == null) DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }, containerColor = Raised) { menuEntries() }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RailIconButton(icon: ImageVector, description: String, onClick: () -> Unit, selected: Boolean = false, enabled: Boolean = true, tooltipEnabled: Boolean = true) {
    // CMP 1.11 Web has a single accessibility semantics owner; transient tooltip windows
    // can leave its mirror stale on dismissal. Keep the labeled button in the root on web.
    if (!tooltipEnabled) {
        AppIconButton(icon, description, onClick, Modifier.fillMaxWidth(), selected = selected, enabled = enabled)
        return
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip(containerColor = Raised, contentColor = TextPrimary) { Text(description) } },
        state = rememberTooltipState(),
    ) {
        AppIconButton(icon, description, onClick, Modifier.fillMaxWidth(), selected = selected, enabled = enabled)
    }
}

@Composable
private fun LibraryRail(state: AppState, callbacks: AppCallbacks) = BoxWithConstraints(
    Modifier.fillMaxHeight().width(58.dp).aeroFill(FlatShape, Aero.panel, rim = null).aeroEdgeLine(end = true),
) {
    val compact = maxHeight < 520.dp
    val railScroll = rememberScrollState()
    LaunchedEffect(compact, state.screen) {
        if (compact && state.screen == Screen.SETTINGS) railScroll.scrollTo(Int.MAX_VALUE)
        else if (compact) railScroll.scrollTo(0)
    }
    val railModifier = if (compact) Modifier.fillMaxSize().verticalScroll(railScroll) else Modifier.fillMaxSize()
    Column(
        railModifier.padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            PhotoToolMark(Modifier.size(29.dp))
        }
        Spacer(Modifier.height(if (compact) 6.dp else 18.dp))
        val entries = listOf(
            Triple(LibrarySection.ALL_PHOTOS, PhotoLibrary, "Todas as fotos"),
            Triple(LibrarySection.CALENDAR, CalendarMonth, "Calendário"),
            Triple(LibrarySection.FOLDERS, Folder, "Pastas"),
            Triple(LibrarySection.PICKS, PickFlag, "Escolhidas"),
            Triple(LibrarySection.LATEST, ImageSearch, "Recentes"),
        )
        entries.forEach { (section, icon, label) ->
            val active = state.section == section && state.screen != Screen.SETTINGS
            RailIconButton(icon, label, { callbacks.dispatch(Action.Navigate(section)) }, selected = active, enabled = callbacks.capabilities.supports(section), tooltipEnabled = state.remote == null)
            Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
        }
        if (compact) Spacer(Modifier.height(6.dp)) else Spacer(Modifier.weight(1f))
        RailIconButton(HelpOutline, "Ajuda de atalhos", { callbacks.dispatch(Action.ToggleHelp) }, tooltipEnabled = state.remote == null)
        Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
        RailIconButton(Settings, "Configurações", { callbacks.dispatch(Action.OpenSettings) }, selected = state.screen == Screen.SETTINGS, tooltipEnabled = state.remote == null)
        Box(
            Modifier.padding(top = if (compact) 4.dp else 10.dp).size(8.dp).background(if (state.writeEnabled) Mint else Caution, RoundedCornerShape(4.dp))
                .semantics { contentDescription = if (state.writeEnabled) "Edição XMP ativada" else "Somente leitura" },
        )
    }
}

@Composable
private fun LibraryToolbar(state: AppState, callbacks: AppCallbacks, searchFocusChanged: (Boolean) -> Unit) = Column(Modifier.fillMaxWidth().aeroFill(FlatShape, Aero.window, rim = null).aeroEdgeLine(bottom = true)) {
    if (state.screen != Screen.GALLERY) {
        Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.screen == Screen.CALENDAR) "Calendário" else "Pastas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            Text(photoCount(state.totalPhotoCount), color = Muted, style = MaterialTheme.typography.labelMedium)
        }
        return@Column
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val toolbarWidth = maxWidth
        val narrow = toolbarWidth < 700.dp
        if (narrow) Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GallerySearch(state, callbacks, Modifier.fillMaxWidth(), searchFocusChanged)
            GalleryToolbarControls(state, callbacks, Modifier.fillMaxWidth(), compact = true, showSummary = true)
        } else Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.widthIn(min = 126.dp, max = 154.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(sectionTitle(state.section), fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(photoCount(state.visiblePhotoCount), color = Muted, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(12.dp))
            GallerySearch(state, callbacks, Modifier.weight(1f).widthIn(min = 220.dp), searchFocusChanged)
            Spacer(Modifier.width(16.dp))
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
    val textColor = if (enabled) TextPrimary else Muted.copy(alpha = .55f)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        interactionSource = interaction,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        cursorBrush = SolidColor(Accent),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor),
        modifier = modifier.height(56.dp)
            .aeroSurface(CompactShape, if (focused) Aero.card else Aero.well, rim = if (focused) Aero.accentRim else Aero.wellRim, rimWidth = if (focused) 1.5.dp else 1.dp)
            .onFocusChanged { focusChanged(it.isFocused) },
        decorationBox = { innerField ->
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(if (focused) 3.dp else 2.dp).fillMaxHeight().background(if (focused) Accent else Hairline.copy(alpha = .52f)))
                leadingIcon?.let { icon ->
                    Box(Modifier.width(38.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = if (focused) Accent else Muted, modifier = Modifier.size(18.dp))
                    }
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().padding(start = if (leadingIcon == null) 14.dp else 4.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(label, color = if (focused) Accent else Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    Box(Modifier.fillMaxWidth().heightIn(min = 18.dp), contentAlignment = Alignment.CenterStart) {
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
    Row(modifier.height(if (showSummary) 52.dp else 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
        if (showSummary) Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(sectionTitle(state.section), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(photoCount(state.visiblePhotoCount), color = Muted, style = MaterialTheme.typography.labelMedium)
        }
        OutlinedButton(
            onClick = { callbacks.dispatch(Action.ToggleFilters) },
            shape = CompactShape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (state.filtersOpen) Accent else TextPrimary),
            border = BorderStroke(1.dp, if (state.filtersOpen) Accent else Hairline),
            contentPadding = PaddingValues(horizontal = if (compact) 12.dp else 15.dp),
            modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "Filtros" },
        ) {
            Icon(Tune, null, Modifier.size(18.dp)); if (!compact) { Spacer(Modifier.width(7.dp)); Text("Filtros") }
        }
        Spacer(Modifier.width(8.dp))
        if (!state.selectionModeActive) AppIconButton(SelectAll, "Selecionar fotografias", { callbacks.dispatch(Action.ToggleSelectionMode) })
        if (!compact) Column(Modifier.padding(horizontal = 12.dp), horizontalAlignment = Alignment.End) {
            Text("ORDEM", color = Accent, style = MaterialTheme.typography.labelMedium)
            Text(if (state.query.sort == PhotoSort.CAPTURE_TIME) "CAPTURA" else "ADIÇÃO", color = Muted, style = MaterialTheme.typography.labelMedium)
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
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
        state.remote?.loading == true -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.semantics { contentDescription = "Carregando fotografias" }) }
        state.remote?.error != null -> StatePanel(ErrorOutline, "Falha de conexão", state.remote.error, "Tentar novamente", callbacks.refresh, requestInitialFocus = false)
        state.library == null -> EmptyLibrary(callbacks)
        state.visiblePhotos.isEmpty() -> EmptyGallery(state, callbacks, requestInitialFocus = !searchFocused)
        else -> Column(Modifier.fillMaxSize()) {
            if (state.selectionModeActive) BatchCommandBar(state, callbacks)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                key(state.remote?.page, state.remote?.generation) { PhotoGrid(state, callbacks, searchFocused) }
            }
            state.remote?.let { RemotePagination(it, callbacks) }
        }
    }
}

@Composable
private fun BatchCommandBar(state: AppState, callbacks: AppCallbacks) = BoxWithConstraints(
    Modifier.fillMaxWidth().aeroFill(FlatShape, Aero.window, rim = null).aeroEdgeLine(bottom = true).semantics { contentDescription = "Batch command bar" },
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
    val canMutate = callbacks.capabilities.batchEditorial && state.writeEnabled && editableCount > 0
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
        Modifier.fillMaxWidth().heightIn(max = (maxHeight * .78f).coerceAtLeast(220.dp)).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selectionLimitReached) "${selected.size} de $visibleCount selecionadas · limite seguro de $MAX_BATCH_PHOTOS atingido" else selectedPhotoCount(selected.size),
                color = Accent, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
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
                colors = ButtonDefaults.textButtonColors(contentColor = Mint),
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
            Text(result.summary, color = if (result.failed > 0) Danger else Mint, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp).semantics { contentDescription = "Resultado do lote ${result.channel}" })
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
        Modifier.fillMaxWidth().aeroSurface(CompactShape, Aero.panel).padding(14.dp)
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Modifier.width(216.dp).heightIn(min = 44.dp).aeroFill(CompactShape, Aero.well, Aero.wellRim).padding(start = 12.dp),
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
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
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
        Modifier.fillMaxWidth().aspectRatio(1.35f).aeroFill(CompactShape, Aero.card, rim = null).clickable(onClick = clicked).semantics { contentDescription = description },
    ) {
        PhotoImage(representative, callbacks, 720, ContentScale.Crop, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x22000000), Color(0xdd08090a)), startY = 80f)))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(count.uppercase(), color = Color(0xffdedfe1), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun FoldersScreen(state: AppState, callbacks: AppCallbacks) {
    val roots = state.photos.filter { it.folder.isNotBlank() }.groupBy { it.folder.substringBefore('/') }.toList().sortedBy { it.first }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(state.library ?: "Biblioteca", color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${roots.size} ${if (roots.size == 1) "PASTA" else "PASTAS"}", color = Muted, style = MaterialTheme.typography.labelMedium)
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
        Modifier.fillMaxWidth().aeroFill(CompactShape, Aero.card).clickable(onClick = clicked).semantics { contentDescription = "Navegar pela pasta $folder, ${photoCount(photos.size)}, intervalo $dateRange" },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.5f)) {
            PhotoImage(representative, callbacks, 720, ContentScale.Crop, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x66080503)), startY = 100f)))
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(photoCount(photos.size).uppercase(), color = Accent, style = MaterialTheme.typography.labelMedium)
            Text(folder, color = TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(dateRange, color = Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            if (nested > 0) Text("$nested ${if (nested == 1) "CAMINHO INTERNO" else "CAMINHOS INTERNOS"}", color = Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1)
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
        modifier.aeroSurface(RoundedCornerShape(10.dp), Aero.panel)
            .verticalScroll(rememberScrollState()).padding(18.dp).semantics { contentDescription = "Painel de filtros" },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Tune, null, tint = Accent, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(9.dp))
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0..5).forEach { rating -> FilterChoice(state.query.minimumStars == rating, if (rating == 0) "Qualquer" else "$rating+", if (rating == 0) StarBorder else Star) { callbacks.dispatch(Action.SetQuery(state.query.copy(minimumStars = rating))) } }
                }
            }
            FilterMode.GPS -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            .then(
                when {
                    selected -> Modifier.background(AccentWash).aeroRim(RoundedCornerShape(6.dp), Aero.accentRim)
                    pressed -> Modifier.background(RaisedHover)
                    hovered -> Modifier.background(Aero.card).aeroRim(RoundedCornerShape(6.dp))
                    else -> Modifier
                },
            )
            .then(if (focused) Modifier.border(2.dp, Focus, RoundedCornerShape(6.dp)) else Modifier)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = clicked)
            .semantics { contentDescription = label; this.selected = selected },
    ) {
        Row(Modifier.align(Alignment.Center).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = if (selected) Accent else Muted, modifier = Modifier.size(17.dp))
            Text(label, color = if (selected) Accent else TextPrimary, style = MaterialTheme.typography.labelLarge)
        }
        if (selected) Canvas(Modifier.matchParentSize()) { drawLine(Accent, Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), strokeWidth = 2.dp.toPx()) }
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
        val columns = maxOf(1, ((maxWidth - 14.dp) / tileSize).toInt())
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
        LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Adaptive(tileSize), state = gridState, contentPadding = PaddingValues(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalItemSpacing = 6.dp,
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
                    .aeroSurface(CardShape, Aero.card, rim = if (selected) Aero.accentRim else Aero.rim)
                    .then(if (focused) Modifier.border(2.dp, Focus, CardShape) else Modifier)
                    .clickable {
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
                    if (selected) ProofSelectionCorners(Accent, Modifier.matchParentSize())
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
                            Modifier.align(Alignment.TopStart).padding(8.dp).size(28.dp).clip(RoundedCornerShape(2.dp))
                                .background(if (selected) Accent else Stage.copy(alpha = .84f)).border(1.dp, if (selected) Accent else TextPrimary, RoundedCornerShape(2.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(if (selected) Check else CheckBoxOutlineBlank, null, tint = if (selected) Ink else TextPrimary, modifier = Modifier.size(17.dp)) }
                    }
                    if (!state.selectionModeActive && !quickOpen && (compactTouchLayout || hovered || cardHasFocus)) {
                        AppIconButton(
                            Tune,
                            "Abrir curadoria rápida de ${photo.displayName}",
                            { quickEditorId = photo.id },
                            Modifier.align(Alignment.TopEnd).padding(8.dp),
                        )
                    }
                    if (photo.writeState != WriteState.IDLE) {
                        val status = when (photo.writeState) { WriteState.SAVING -> "Salvando"; WriteState.PERSISTED -> "Salvo"; WriteState.FAILED -> "Falha"; WriteState.IDLE -> "" }
                        Row(Modifier.align(Alignment.TopCenter).padding(10.dp).clip(CompactShape).background(Color(0xcc000000)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (photo.writeState == WriteState.FAILED) Close else Check, null, tint = if (photo.writeState == WriteState.FAILED) Danger else Mint, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text(status, color = TextPrimary, style = MaterialTheme.typography.labelSmall)
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
                    Modifier.align(Alignment.TopCenter).padding(12.dp).widthIn(max = minOf(460.dp, maxWidth - 24.dp)),
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
    Modifier.fillMaxWidth().height(34.dp).aeroFill(CompactShape, Aero.window, rim = null).aeroEdgeLine(top = true).padding(horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
) {
    Text(photo.filenameNumber ?: "S/N", color = Accent, style = MaterialTheme.typography.labelMedium)
    if (photo.editorial.flag != Flag.UNFLAGGED) Icon(
        if (photo.editorial.flag == Flag.PICK) PickFlag else RejectFlag,
        null,
        tint = if (photo.editorial.flag == Flag.PICK) Mint else Danger,
        modifier = Modifier.size(14.dp),
    )
    if (photo.editorial.rating > 0) {
        Icon(Star, null, tint = Accent, modifier = Modifier.size(12.dp))
        Text(photo.editorial.rating.toString(), color = TextPrimary, style = MaterialTheme.typography.labelMedium)
    }
    photo.editorial.label?.let { Box(Modifier.size(8.dp).background(labelColor(it), RoundedCornerShape(1.dp))) }
    Spacer(Modifier.weight(1f))
    Text(
        photo.mediaFormatLabel,
        color = Muted,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.semantics { contentDescription = "Formato ${photo.mediaFormatLabel}" },
    )
}

private sealed interface PreviewState {
    data object Loading : PreviewState
    data class Loaded(val bitmap: androidx.compose.ui.graphics.ImageBitmap) : PreviewState
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
    preparedBitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
    nativeSize: IntSize? = null,
    regions: List<RegionView> = emptyList(),
    filterQuality: FilterQuality? = null,
) {
    val preview = if (preparedBitmap != null) PreviewState.Loaded(preparedBitmap) else key(
        photo.id, photo.previewIdentity, photo.presentation?.previewUrl, maximumDimension, callbacks.imageLoader,
    ) {
        // produceState retains its old value when only an effect key changes. Never give that
        // old bitmap the new photo's name, including same-id preview version changes.
        val loaded by produceState<PreviewState>(PreviewState.Loading) {
            val bitmap = callbacks.imageLoader.load(photo, maximumDimension)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            // Sync already proved which photographs have a usable preview and recorded the reason
            // for the others in the snapshot, so a request that produces no bitmap keeps the frame
            // in its loading presentation instead of becoming a browsing error.
            value = bitmap?.let(PreviewState::Loaded) ?: PreviewState.Loading
        }
        loaded
    }
    Box(modifier.background(Stage).semantics { contentDescription = "Quadro de ${photo.displayName}" }, contentAlignment = Alignment.Center) {
        when (val current = preview) {
            PreviewState.Loading -> Text("Carregando prévia", color = Muted, modifier = Modifier.semantics { contentDescription = "Carregando prévia de ${photo.displayName}" })
            is PreviewState.Loaded -> {
                SideEffect { previewDimensionsChanged(IntSize(current.bitmap.width, current.bitmap.height)) }
                val state = "Resolução apresentada: ${current.bitmap.width} × ${current.bitmap.height}"
                if (filterQuality != null) {
                    check(scale == ContentScale.Fit) { "Pixel-accurate rendering supports Fit only" }
                    Canvas(Modifier.fillMaxSize().then(imageModifier).semantics {
                        contentDescription = "Prévia de ${photo.displayName}"
                        stateDescription = state
                        role = Role.Image
                    }) {
                        val native = nativeSize ?: IntSize(current.bitmap.width, current.bitmap.height)
                        // Preview and tiles are placed on the same rounding grid, from the native
                        // edges outwards, so a tile can never leave a one-pixel line of preview.
                        val area = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1))
                        val box = regionPlacement(NativeRegion(0, 0, native.width, native.height), native, area)
                        drawImage(
                            image = current.bitmap,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(current.bitmap.width, current.bitmap.height),
                            dstOffset = IntOffset(box.x, box.y),
                            dstSize = IntSize(box.width, box.height),
                            filterQuality = filterQuality,
                        )
                        regions.forEach { nativeRegion ->
                            val placed = regionPlacement(nativeRegion.region, native, area)
                            drawImage(
                                image = nativeRegion.bitmap,
                                srcOffset = IntOffset.Zero,
                                srcSize = IntSize(nativeRegion.bitmap.width, nativeRegion.bitmap.height),
                                dstOffset = IntOffset(placed.x, placed.y),
                                dstSize = IntSize(placed.width, placed.height),
                                filterQuality = FilterQuality.None,
                            )
                        }
                    }
                } else {
                    Image(current.bitmap, contentDescription = "Prévia de ${photo.displayName}", modifier = Modifier.fillMaxSize().then(imageModifier).semantics {
                        stateDescription = state
                    }, contentScale = scale)
                }
            }
        }
        if (photo.issue != null || photo.writeError != null || photo.metadata.status == MetadataStatus.ERROR || photo.editorial.goodError != null) Box(
            Modifier.align(Alignment.BottomEnd).padding(8.dp).size(44.dp).clip(RoundedCornerShape(22.dp)).background(Stage.copy(alpha = .82f))
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
        modifier.aeroSurface(RoundedCornerShape(9.dp), Aero.panel, rim = null).border(1.dp, Danger.copy(alpha = .72f), RoundedCornerShape(9.dp)).padding(14.dp)
            .semantics { contentDescription = "Problema da fotografia ${photo.displayName}" },
        verticalArrangement = Arrangement.spacedBy(10.dp),
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
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
            border = BorderStroke(1.dp, Accent.copy(alpha = .72f)),
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
                    onDragStart = { position ->
                        dragging = true
                        val segmentWidth = trackWidth.toFloat() / items.size
                        dragPosition = (position.x / segmentWidth).coerceIn(0f, items.lastIndex.toFloat())
                    },
                    onDragCancel = { dragging = false },
                    onDragEnd = {
                        val target = dragPosition.roundToInt().coerceIn(items.indices)
                        dragging = false
                        if (target != selectedIndex) currentOnSelect(items[target])
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
        accent = { item -> when (item) { Flag.REJECT -> Danger; Flag.UNFLAGGED -> Muted; Flag.PICK -> Mint } },
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
                        Icon(Star, null, tint = if (value <= gestureValue.roundToInt()) Accent else Muted, modifier = Modifier.size(11.dp))
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
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Hairline, activeTickColor = Ink, inactiveTickColor = Muted),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { contentDescription = description; stateDescription = if (rating == 0) "Sem estrelas" else "$rating estrelas" },
        )
    }
}

private fun canEditPhoto(state: AppState, photo: Photo): Boolean = state.writeEnabled && photo.writable &&
    (state.remote == null || (state.screen == Screen.DETAIL && !state.remote.loading &&
        state.remote.savingPhotoId == null && !state.remote.editorialNeedsRefresh &&
        state.remote.detail?.id == photo.id && photo.editorialRevision != null))

@Composable
private fun QuickControls(photo: Photo, state: AppState, callbacks: AppCallbacks, modifier: Modifier = Modifier) {
    EditorialFlagSelector(photo.editorial.flag, canEditPhoto(state, photo), modifier) { callbacks.mutateFlag(photo, it) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickEditor(photo: Photo, state: AppState, callbacks: AppCallbacks, close: () -> Unit) {
    val closeFocusRequester = remember { FocusRequester() }
    val enabled = canEditPhoto(state, photo)
    LaunchedEffect(photo.id) { closeFocusRequester.requestFocus() }
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Curadoria rápida de ${photo.displayName}" },
        color = Sunken,
        shape = RoundedCornerShape(2.dp),
        border = BorderStroke(1.dp, Accent.copy(alpha = .58f)),
        shadowElevation = 8.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(photo.displayName, color = TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(photo.mediaFormatLabel, color = Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                AppIconButton(Close, "Fechar curadoria rápida", close, Modifier.focusRequester(closeFocusRequester))
            }
            if (state.remote != null) Text("Abra a fotografia para editar o XMP.", color = Muted, style = MaterialTheme.typography.bodySmall)
            EditorialFlagSelector(photo.editorial.flag, enabled) { callbacks.mutateFlag(photo, it) }
            EditorialRatingSlider(photo.editorial.rating, enabled, "Avaliação da curadoria rápida") { callbacks.mutate(photo, photo.editorial.copy(rating = it)) }
            EditorialLabelSelector(photo.editorial.label, enabled) { callbacks.mutate(photo, photo.editorial.copy(label = it)) }
            if (photo.writeState != WriteState.IDLE) {
                val failed = photo.writeState == WriteState.FAILED
                val status = when (photo.writeState) { WriteState.SAVING -> "Salvando no XMP"; WriteState.PERSISTED -> "Salvo e verificado"; WriteState.FAILED -> "Falha ao salvar"; WriteState.IDLE -> "" }
                Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (failed) Close else Check, null, tint = if (failed) Danger else Mint, modifier = Modifier.size(14.dp))
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

private enum class DetailPanel { INSPECTOR, FILMSTRIP }

@Composable
private fun DetailScreen(requestedState: AppState, callbacks: AppCallbacks) {
    val focusRequester = remember { FocusRequester() }
    val presented = remoteDetailPreview(requestedState.remote, callbacks)
    val state = if (presented == null) requestedState else requestedState.copy(
        selectedId = presented.photo.id, selectedIds = setOf(presented.photo.id),
        remote = requestedState.remote?.copy(detail = presented.photo),
    )
    val bitmap = presented?.bitmap
    val photo = state.remote?.detail ?: state.selected
    LaunchedEffect(photo?.id) { focusRequester.requestFocus() }
    BoxWithConstraints(
        Modifier.fillMaxSize().focusRequester(focusRequester).onFocusChanged { callbacks.shortcutScopeFocused(it.hasFocus) }.focusable(),
    ) {
        if (state.remote?.detail == null && (state.remote?.loading == true || state.remote?.error != null)) {
            StatePanel(
                icon = ImageSearch,
                title = if (state.remote.loading) "Carregando fotografia" else "Fotografia indisponível",
                message = state.remote.error ?: "Consultando o snapshot do servidor.",
                actionLabel = "Voltar à biblioteca",
                action = { callbacks.dispatch(Action.CloseDetail) },
                requestInitialFocus = false,
            )
            return@BoxWithConstraints
        }
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
    val previousAvailable = state.remote?.let { it.loading || it.previousId != null } ?: (visibleIndex > 0)
    val nextAvailable = state.remote?.let { it.loading || it.nextId != null } ?: (visibleIndex >= 0 && visibleIndex < state.visiblePhotos.lastIndex)
    // A short viewport is a reason to disclose tools, never to squeeze a phone beside an inspector.
    val compact = maxWidth < 900.dp || maxHeight < 480.dp
    var panel by remember(photo.id, compact) { mutableStateOf<DetailPanel?>(null) }
    var panelDrag by remember(photo.id, compact) { mutableStateOf<Float?>(null) }
    if (!compact) {
        Row(Modifier.fillMaxSize()) {
            DetailImageArea(photo, state, callbacks, Modifier.weight(1f).fillMaxHeight(), previousAvailable, nextAvailable, preparedBitmap = bitmap)
            DetailInspector(photo, state, callbacks, Modifier.width(370.dp).fillMaxHeight())
        }
    } else {
        val haze = rememberHazeState()
        DetailImageArea(
            photo, state, callbacks, Modifier.fillMaxSize().hazeSource(haze), previousAvailable, nextAvailable,
            compact = true,
            preparedBitmap = bitmap,
            controlsInHeader = maxWidth >= 600.dp && maxHeight < 480.dp,
            openInspector = { panel = DetailPanel.INSPECTOR },
            openFilmstrip = { panel = DetailPanel.FILMSTRIP },
            onPanelDrag = { distance, active -> panelDrag = if (active) distance else null },
        )
        CompactDetailPanel(
            panel, photo, state, callbacks, haze, (maxHeight * .86f).coerceAtMost(620.dp),
            close = { panel = null },
            onHidden = { focusRequester.requestFocus() },
            dragDistance = panelDrag,
        )
    }
}
}

// Same-scene panel, like the compact filters. Avoid a platform popup: on Wasm its dismissal
// can leave the underlying accessibility tree hidden even though the photograph is rendered.
@Composable
private fun CompactDetailPanel(
    panel: DetailPanel?, photo: Photo, state: AppState, callbacks: AppCallbacks,
    haze: HazeState, maximumHeight: androidx.compose.ui.unit.Dp,
    close: () -> Unit, onHidden: () -> Unit, dragDistance: Float? = null,
) {
    var retainedPanel by remember(photo.id) { mutableStateOf(DetailPanel.INSPECTOR) }
    val draggedPanel = dragDistance?.let { if (it < 0f) DetailPanel.INSPECTOR else DetailPanel.FILMSTRIP }
    val presentedPanel = panel ?: draggedPanel ?: retainedPanel
    SideEffect { if (panel != null || draggedPanel != null) retainedPanel = presentedPanel }
    val inspector = presentedPanel == DetailPanel.INSPECTOR
    val title = if (inspector) "Curadoria e metadados" else "Miniaturas"
    PhotoDetailPanel(panel != null, !inspector, title, haze, maximumHeight, close, onHidden, dragDistance) {
        val closeFocus = remember { FocusRequester() }
        LaunchedEffect(panel) { if (panel != null) closeFocus.requestFocus() }
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp).heightIn(min = 60.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            AppIconButton(Close, if (inspector) "Fechar curadoria e metadados" else "Fechar miniaturas", close, Modifier.focusRequester(closeFocus))
        }
        if (inspector) DetailInspector(photo, state, callbacks, Modifier.fillMaxWidth().weight(1f), Color.Transparent)
        else Filmstrip(state, callbacks, Color.Transparent)
    }
}

@Composable
private fun DetailImageArea(
    photo: Photo, state: AppState, callbacks: AppCallbacks, modifier: Modifier,
    previousAvailable: Boolean = true, nextAvailable: Boolean = true,
    compact: Boolean = false, controlsInHeader: Boolean = false,
    openInspector: () -> Unit = {}, openFilmstrip: () -> Unit = {},
    onPanelDrag: (Float, Boolean) -> Unit = { _, _ -> },
    preparedBitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
) {
    var problemOpen by remember(photo.id) { mutableStateOf(false) }
    var zoom by remember(photo.id) { mutableFloatStateOf(MIN_DETAIL_ZOOM) }
    var pan by remember(photo.id) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember(photo.id) { mutableStateOf(IntSize.Zero) }
    var imageSize by remember(photo.id) { mutableStateOf(IntSize.Zero) }
    var swipeOffset by remember(photo.id) { mutableFloatStateOf(0f) }
    var swiping by remember(photo.id) { mutableStateOf(false) }
    val currentCallbacks by rememberUpdatedState(callbacks)
    val canGoBack by rememberUpdatedState(previousAvailable)
    val canGoForward by rememberUpdatedState(nextAvailable)
    val currentOpenInspector by rememberUpdatedState(openInspector)
    val currentOpenFilmstrip by rememberUpdatedState(openFilmstrip)
    val currentPanelDrag by rememberUpdatedState(onPanelDrag)
    val swipeTranslation by animateFloatAsState(
        swipeOffset, if (swiping) snap() else spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium),
    )

    val nativeSize = photo.metadata.width?.let { width -> photo.metadata.height?.let { height -> IntSize(width, height) } }
    // 1:1 must follow the native image, not the display-sized bitmap.
    val pixelZoom = detailPixelZoom(viewportSize, nativeSize ?: imageSize)
    val zoomStops = detailZoomStops(pixelZoom)
    val maximumZoom = detailMaxZoom(pixelZoom)
    // One threshold for asking, keeping and painting crops: below it the display bitmap is enough.
    // Asking and clearing used to disagree, which left stale crops painted over the upscaled preview.
    val regionZoom = detailRegionZoom(pixelZoom)
    var regions by remember(photo.id) { mutableStateOf<List<RegionView>>(emptyList()) }
    // One pump per photo: panning never cancels the request in flight. The demand is recomputed
    // after every response, so a continuous drag keeps receiving native crops instead of falling
    // back to the 2048 px display bitmap outside the last crop.
    val regionsActive = preparedBitmap != null && nativeSize != null && zoom + .01f >= regionZoom
    // A crop never outlives the zoom that asked for it.
    LaunchedEffect(photo.id, regionsActive) { if (!regionsActive) regions = emptyList() }
    LaunchedEffect(photo.id, regionsActive, viewportSize, nativeSize, preparedBitmap) {
        if (!regionsActive) return@LaunchedEffect
        var failed = emptyList<NativeRegion>()
        var requestedFrom = pan
        var requestedZoom = zoom
        var issued = false
        while (true) {
            val native = nativeSize
            val restingPan = pan
            val restingZoom = zoom
            // Coverage is judged on the uncapped window: a window taller than one crop would
            // otherwise look covered while a band of the frame still showed the upscaled preview.
            val visible = visibleNativeWindow(viewportSize, native, restingZoom, restingPan) ?: break
            val covered = regions.map(RegionView::region)
            val tiles = if (regionCoverage(covered, visible) >= 1f) emptyList() else visibleNativeRegions(
                viewportSize, native, restingZoom, restingPan, DETAIL_REGION_MARGIN,
                lead = if (requestedZoom == restingZoom) detailRegionLead(Offset(restingPan.x - requestedFrom.x, restingPan.y - requestedFrom.y), viewportSize) else Offset.Zero,
            )
            // Leading edge: ask for the first tile of the window that no crop covers yet. A tile
            // already covered by a retained crop is never asked for again.
            val target = missingNativeRegion(tiles, covered, failed)
            if (target == null) {
                // Nothing new to ask for until the view moves; conflation happens here. A crop that
                // failed is retried after the view moves, never in a loop against the decoder.
                failed = emptyList()
                snapshotFlow { pan to zoom }.first { it.first != restingPan || it.second != restingZoom }
                continue
            }
            requestedFrom = restingPan
            requestedZoom = restingZoom
            if (issued) kotlinx.coroutines.delay(DETAIL_REGION_MIN_SPACING_MS)
            issued = true
            val bitmap = try {
                callbacks.imageLoader.loadRegion(photo, target.x, target.y, target.width, target.height)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (bitmap == null) {
                failed = failed + target
                continue
            }
            // Applied as soon as it lands, mid-drag included, and kept for the rest of the inspection.
            // The budget never evicts the plan that covers the window being looked at.
            val budget = maxOf(DETAIL_REGION_BUDGET_PIXELS, tiles.sumOf { it.width.toLong() * it.height })
            val added = RegionView(bitmap, target.x, target.y, target.width, target.height)
            regions = retainRegions(listOf(added) + regions.filterNot {
                it.x == added.x && it.y == added.y && it.width == added.width && it.height == added.height
            }, budget)
        }
    }
    val effectiveBitmap = preparedBitmap

    fun updateZoom(target: Float, focalPoint: Offset = Offset(viewportSize.width / 2f, viewportSize.height / 2f)) {
        val next = target.coerceIn(MIN_DETAIL_ZOOM, maximumZoom)
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
        if (compact) {
            Row(Modifier.fillMaxWidth().height(52.dp).aeroFill(FlatShape, Aero.window, rim = null).aeroEdgeLine(bottom = true).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIconButton(ArrowBack, "Voltar à biblioteca", { callbacks.dispatch(Action.CloseDetail) })
                Text(photo.displayName, Modifier.weight(1f).padding(horizontal = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                AppIconButton(PhotoLibrary, "Abrir miniaturas", openFilmstrip, enabled = state.visiblePhotos.size > 1)
                AppIconButton(Tune, "Abrir curadoria e metadados", openInspector)
                if (controlsInHeader) {
                    AppIconButton(ChevronLeft, "Fotografia anterior", { callbacks.navigate(-1) }, enabled = previousAvailable)
                    DetailZoomControls(
                        zoom, { updateZoom(nextDetailZoom(zoom, -1, zoomStops, maximumZoom)) }, { updateZoom(nextDetailZoom(zoom, 1, zoomStops, maximumZoom)) },
                        { updateZoom(MIN_DETAIL_ZOOM) }, compact = true, maximumZoom = maximumZoom, pixelZoom = pixelZoom,
                    )
                    AppIconButton(ChevronRight, "Próxima fotografia", { callbacks.navigate(1) }, enabled = nextAvailable)
                }
            }
        } else Box(Modifier.fillMaxWidth().height(60.dp).aeroFill(FlatShape, Aero.window, rim = null).aeroEdgeLine(bottom = true)) {
            AppIconButton(ArrowBack, "Voltar à biblioteca", { callbacks.dispatch(Action.CloseDetail) }, Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
            Column(Modifier.align(Alignment.Center).widthIn(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(photo.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(buildList { add(photo.mediaFormatLabel); photo.metadata.capturedAt?.let { add(formatCapturedAt(it, includeTime = false)) }; if (photo.folder.isNotBlank()) add(photo.folder) }.joinToString(" · "), color = Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            PhotoImage(
                photo = photo,
                callbacks = callbacks,
                maximumDimension = br.com.lincon.phototool.api.DISPLAY_PREVIEW_DIMENSION,
                scale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().clipToBounds().onSizeChanged { viewportSize = it }
                    .pointerInput(photo.id, viewportSize, imageSize, compact) {
                        detectDetailPhotoGestures(
                            isFit = { zoom == MIN_DETAIL_ZOOM },
                            navigationThreshold = maxOf(56.dp.toPx(), size.width * .12f),
                            panelThreshold = maxOf(56.dp.toPx(), size.height * .12f),
                            onVerticalSwipe = if (compact) { direction ->
                                if (direction < 0) currentOpenInspector() else currentOpenFilmstrip()
                            } else null,
                            onPanelDrag = { distance, active -> currentPanelDrag(distance, active) },
                            onDrag = { distance, active ->
                                swiping = active
                                val available = if (distance < 0) canGoForward else canGoBack
                                swipeOffset = if (available) distance else distance / (3f + kotlin.math.abs(distance) / 80.dp.toPx())
                            },
                            onNavigate = { direction ->
                                if (if (direction > 0) canGoForward else canGoBack) currentCallbacks.navigate(direction)
                            },
                            onTransform = { centroid, panChange, zoomChange ->
                                updateZoom(zoom * zoomChange, centroid)
                                pan = constrainDetailPan(pan + panChange, zoom, viewportSize, imageSize)
                            },
                        )
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
                        stateDescription = "Zoom atual: ${(zoom * 100).roundToInt()}%" +
                            if (zoom >= pixelZoom - .01f) " · pixels 1:1" else ""
                    },
                issueClicked = { problemOpen = true },
                imageModifier = Modifier.graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x + swipeTranslation
                    translationY = pan.y
                },
                previewDimensionsChanged = { imageSize = it },
                preparedBitmap = effectiveBitmap,
                nativeSize = nativeSize,
                regions = if (regionsActive) regions else emptyList(),
                filterQuality = detailFilterQuality(zoom, pixelZoom),
            )
            if (!compact) {
                AppIconButton(ChevronLeft, "Fotografia anterior", { callbacks.navigate(-1) }, Modifier.align(Alignment.CenterStart).padding(8.dp).aeroSurface(CompactShape, Aero.panel, Aero.rimStrong), enabled = previousAvailable)
                AppIconButton(ChevronRight, "Próxima fotografia", { callbacks.navigate(1) }, Modifier.align(Alignment.CenterEnd).padding(8.dp).aeroSurface(CompactShape, Aero.panel, Aero.rimStrong), enabled = nextAvailable)
                DetailZoomControls(
                    zoom = zoom,
                    zoomOut = { updateZoom(nextDetailZoom(zoom, -1, zoomStops, maximumZoom)) },
                    zoomIn = { updateZoom(nextDetailZoom(zoom, 1, zoomStops, maximumZoom)) },
                    fit = { updateZoom(MIN_DETAIL_ZOOM) },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
                    maximumZoom = maximumZoom, pixelZoom = pixelZoom,
                )
            }
            if (problemOpen) PhotoProblemPanel(
                photo,
                Modifier.align(Alignment.TopCenter).padding(12.dp).widthIn(max = 460.dp),
                close = { problemOpen = false },
                recover = { callbacks.dispatch(Action.OpenSettings) },
            )
        }
        if (compact && !controlsInHeader) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).aeroFill(FlatShape, Aero.window, rim = null).aeroEdgeLine(top = true).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AppIconButton(ChevronLeft, "Fotografia anterior", { callbacks.navigate(-1) }, enabled = previousAvailable)
                DetailZoomControls(
                    zoom, { updateZoom(nextDetailZoom(zoom, -1, zoomStops, maximumZoom)) }, { updateZoom(nextDetailZoom(zoom, 1, zoomStops, maximumZoom)) },
                    { updateZoom(MIN_DETAIL_ZOOM) }, compact = true, maximumZoom = maximumZoom, pixelZoom = pixelZoom,
                )
                AppIconButton(ChevronRight, "Próxima fotografia", { callbacks.navigate(1) }, enabled = nextAvailable)
            }
        } else if (!compact && state.visiblePhotos.size > 1) Filmstrip(state, callbacks)
    }
}

@Composable
private fun DetailZoomControls(
    zoom: Float, zoomOut: () -> Unit, zoomIn: () -> Unit, fit: () -> Unit,
    modifier: Modifier = Modifier, compact: Boolean = false,
    maximumZoom: Float = BASE_MAX_DETAIL_ZOOM, pixelZoom: Float = BASE_MAX_DETAIL_ZOOM,
) {
    val percentage = (zoom * 100).roundToInt()
    val label = if (kotlin.math.abs(zoom - pixelZoom) <= pixelZoom * .01f) "1:1" else "$percentage%"
    Surface(
        modifier = modifier.aeroSurface(CompactShape, Aero.panel, Aero.rimStrong)
            .semantics { contentDescription = "Controles de zoom"; stateDescription = "Zoom atual: $percentage%" },
        color = Color.Transparent,
        shape = CompactShape,
        border = null,
        shadowElevation = 0.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(ZoomOut, "Diminuir zoom", zoomOut, enabled = zoom > MIN_DETAIL_ZOOM + .01f)
            Box(Modifier.width(64.dp).height(44.dp).semantics { contentDescription = "Zoom atual: $percentage%" }, contentAlignment = Alignment.Center) {
                Text(label, color = TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            }
            AppIconButton(ZoomIn, "Aumentar zoom", zoomIn, enabled = zoom < maximumZoom - .01f)
            AppIconButton(FitScreen, "Ajustar fotografia à janela", fit, enabled = zoom > MIN_DETAIL_ZOOM + .01f)
        }
    }
}

@Composable
private fun Filmstrip(state: AppState, callbacks: AppCallbacks, background: Color = Panel) {
    val visible = state.visiblePhotos
    val current = visible.indexOfFirst { it.id == state.selectedId }.coerceAtLeast(0)
    val start = maxOf(0, current - 8)
    val neighbors = visible.subList(start, minOf(visible.size, current + 9))
    val selectedWithin = (current - start).coerceIn(0, neighbors.lastIndex)
    val filmstripState = rememberLazyListState()
    LaunchedEffect(state.selectedId, selectedWithin) { filmstripState.scrollToItem(selectedWithin) }
    LazyRow(state = filmstripState, modifier = Modifier.fillMaxWidth().height(78.dp).aeroFill(FlatShape, Aero.window, rim = null).aeroEdgeLine(top = true).padding(horizontal = 8.dp, vertical = 6.dp).semantics { contentDescription = "Faixa de fotografias próximas" }, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        lazyItems(neighbors, key = { it.id }) { item ->
            val selected = item.id == state.selectedId
            PhotoImage(item, callbacks, 240, ContentScale.Fit, Modifier.width(96.dp).fillMaxHeight().then(if (selected) Modifier.border(2.dp, Accent) else Modifier).combinedClickable(onClick = { callbacks.dispatch(Action.Select(item.id)) }, onDoubleClick = {}).focusable().semantics { contentDescription = "Filmstrip ${item.displayName}"; this.selected = selected })
        }
    }
}

private enum class InspectorTab { INFO, EDIT, RECIPES }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailInspector(photo: Photo, state: AppState, callbacks: AppCallbacks, modifier: Modifier, background: Color = Inspector) {
    val scope = rememberCoroutineScope()
    var auxiliary by remember(photo.id) { mutableStateOf(AuxiliaryView(status = "Carregando receitas e HDR")) }
    var busy by remember(photo.id) { mutableStateOf(false) }
    var recipesOpen by remember(photo.id) { mutableStateOf(false) }
    var keyword by remember(photo.id) { mutableStateOf("") }
    var keywordError by remember(photo.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(photo.id) { if (callbacks.capabilities.auxiliary) auxiliary = runCatching { callbacks.auxiliary.load(photo) }.getOrElse { AuxiliaryView(error = it.message ?: "Falha ao ler dados auxiliares") } }
    fun runAux(operation: suspend () -> AuxiliaryView) {
        if (busy) return
        busy = true
        scope.launch { auxiliary = runCatching { operation() }.getOrElse { AuxiliaryView(error = it.message ?: "Falha na operação") }; busy = false }
    }
    Column(
        modifier.aeroFill(FlatShape, Aero.panel, rim = null).aeroEdgeLine(start = true).verticalScroll(rememberScrollState()).padding(20.dp)
            .semantics { contentDescription = "Painel de curadoria da fotografia" },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("CURADORIA", color = Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        QuickControls(photo, state, callbacks)
        EditorialRatingSlider(photo.editorial.rating, canEditPhoto(state, photo), "Avaliação editorial") { callbacks.mutate(photo, photo.editorial.copy(rating = it)) }
        EditorialLabelSelector(photo.editorial.label, canEditPhoto(state, photo)) { callbacks.mutate(photo, photo.editorial.copy(label = it)) }
        val status = when (photo.writeState) { WriteState.IDLE -> editorialSummary(photo.editorial); WriteState.SAVING -> "Salvando no XMP adjacente"; WriteState.PERSISTED -> "Alterações salvas e verificadas"; WriteState.FAILED -> photo.writeError ?: "Falha ao salvar no XMP" }
        Row(Modifier.fillMaxWidth().aeroFill(CompactShape, Aero.well, Aero.wellRim).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (photo.writeState == WriteState.FAILED) Close else Check, null, tint = if (photo.writeState == WriteState.FAILED) Danger else Mint, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)); Text(status, color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        HorizontalDivider(color = Hairline)
        if (photo.issue != null || photo.writeError != null || photo.metadata.status == MetadataStatus.ERROR || photo.editorial.goodError != null) {
            Column(
                Modifier.fillMaxWidth().clip(CompactShape).background(Danger.copy(alpha = .10f)).border(1.dp, Danger.copy(alpha = .45f), CompactShape)
                    .padding(12.dp).semantics { contentDescription = "Problema da fotografia ${photo.displayName}" },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(ErrorOutline, null, tint = Danger, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(7.dp))
                    Text(photoProblemText(photo), color = TextPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
                Text(if (state.remote != null) "Recarregue o estado do servidor antes de repetir a alteração." else "Revise o sidecar em uma cópia segura e sincronize novamente.", color = Muted, style = MaterialTheme.typography.labelSmall)
                OutlinedButton(
                    onClick = { if (state.remote != null) callbacks.refresh() else callbacks.dispatch(Action.OpenSettings) },
                    enabled = state.remote?.let { !it.loading && it.savingPhotoId == null } ?: true,
                    shape = CompactShape,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                    border = BorderStroke(1.dp, Accent.copy(alpha = .72f)),
                    modifier = Modifier.heightIn(min = 44.dp),
                ) { Text(if (state.remote != null) "Recarregar estado do servidor" else "Ir às configurações") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(ImageSearch, null, tint = Muted, modifier = Modifier.size(17.dp))
            Text("METADADOS", color = Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        }
        MetadataTable(photo, callbacks)
        HorizontalDivider(color = Hairline)
        Text("PALAVRAS-CHAVE", color = Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            photo.editorial.keywords.forEach { word -> InputChip(selected = true, shape = CompactShape, onClick = { callbacks.mutate(photo, photo.editorial.copy(keywords = photo.editorial.keywords - word)) }, enabled = canEditPhoto(state, photo), label = { Text(word) }, trailingIcon = { Icon(Close, null, Modifier.size(14.dp)) }) }
        }
        val canAddKeyword = keyword.isNotBlank() && canEditPhoto(state, photo)
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
                enabled = canEditPhoto(state, photo),
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
            Spacer(Modifier.width(8.dp))
            AppIconButton(Add, "Adicionar palavra-chave", ::addKeyword, enabled = canAddKeyword)
        }
        keywordError?.let { Text(it, color = Danger, style = MaterialTheme.typography.labelSmall, modifier = Modifier.semantics { contentDescription = "Erro de palavra-chave" }) }
        HorizontalDivider(color = Hairline)
        if (!callbacks.capabilities.auxiliary) {
            Text("Receitas Fuji, HDR e nomes dos arquivos pareados não são expostos pela API atual.", color = Muted, style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        val rafPhoto = photo.rawPath?.substringAfterLast('.', "")?.equals("raf", true) == true
        val recipeLabel = if (rafPhoto) "receitas e HDR" else "receitas Fuji"
        TextButton(onClick = { recipesOpen = !recipesOpen }, shape = CompactShape, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { contentDescription = if (recipesOpen) "Ocultar $recipeLabel" else "Mostrar $recipeLabel" }) {
            Icon(Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (rafPhoto) "Receitas e HDR" else "Receitas Fuji", Modifier.weight(1f)); Icon(if (recipesOpen) ChevronLeft else ChevronRight, null)
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
            Modifier.fillMaxWidth().heightIn(min = 40.dp).semantics(mergeDescendants = true) { contentDescription = "$description: $value" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp).aeroFill(CompactShape, Aero.window, Aero.rimStrong), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Muted, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(12.dp))
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
            Modifier.fillMaxHeight().widthIn(max = 820.dp).verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) { AppIconButton(ArrowBack, "Voltar à galeria", { callbacks.dispatch(Action.CloseSettings) }, Modifier.focusRequester(initialFocusRequester)); Spacer(Modifier.width(10.dp)); Text("Configurações", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
            SettingsCard("BIBLIOTECA") {
                SettingsValue("Pasta", state.library ?: "Não selecionada")
                SettingsValue("Cache", state.cache ?: "Não configurado")
                OutlinedButton(onClick = callbacks.chooseLibrary, shape = CompactShape, enabled = callbacks.capabilities.localLibrary && !state.sync.running, border = BorderStroke(1.dp, Hairline), modifier = Modifier.heightIn(min = 44.dp)) { Icon(Folder, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Escolher pasta da biblioteca") }
            }
            SettingsCard("EDIÇÃO") {
                val readOnlyMode = !state.writeEnabled
                val pendingWrite = state.photos.any { it.writeState == WriteState.SAVING }
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Modo somente leitura", fontWeight = FontWeight.SemiBold)
                        Text(if (readOnlyMode) "Ativado" else "Desativado", color = if (readOnlyMode) Muted else Accent, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = readOnlyMode,
                        onCheckedChange = callbacks.setReadOnlyMode,
                        enabled = callbacks.capabilities.localLibrary && !pendingWrite,
                        modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "Modo somente leitura" },
                    )
                }
                Text(
                    when {
                        !callbacks.capabilities.localLibrary -> "Este candidato web é somente leitura, mesmo se o servidor permitir escrita. Nenhuma edição ou sincronização é enviada."
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
                    val syncTint = when (state.sync.phase) { SyncPhase.FAILED -> Danger; SyncPhase.COMPLETE -> Mint; else -> Accent }
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
                    OutlinedButton(onClick = callbacks.cancelSync, enabled = callbacks.capabilities.synchronize, shape = CompactShape, modifier = Modifier.heightIn(min = 44.dp)) { Icon(Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Cancelar") }
                } else Button(onClick = callbacks.synchronize, shape = CompactShape, enabled = callbacks.capabilities.synchronize && state.library != null, modifier = Modifier.heightIn(min = 44.dp)) { Icon(RefreshVectorIcon, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text(if (state.sync.phase == SyncPhase.FAILED) "Tentar novamente" else "Sincronizar") }
            }
            if (state.remote != null) SettingsCard("CAPACIDADES WEB") {
                Text("Galeria paginada, busca, filtros, escolhidas, recentes, detalhe e previews usam a API. Calendário e navegação hierárquica por pastas ainda não estão integrados; a API oferece apenas facets de pastas limitados. Fuji, HDR, transferências e nomes dos pares RAW/JPEG não são expostos. Atalhos editoriais e escrita permanecem bloqueados.", color = Muted)
            }
            SettingsCard("SEGURANÇA") {
                Text("A sincronização é somente leitura. O PhotoTool nunca altera os bytes de arquivos RAW ou JPEG. O modo de escrita explícito altera apenas XMP adjacente e FP2 editável, após verificações de identidade, topologia, bytes e releitura.", color = Muted)
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, warning: Boolean = false, content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxWidth().aeroFill(CompactShape, if (warning) Aero.panel else Aero.card, rim = if (warning) Aero.rimStrong else Aero.rim).padding(horizontal = 18.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
) {
    HorizontalDivider(color = if (warning) Danger.copy(alpha = .55f) else Hairline)
    Text(title, color = Accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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
            Box(Modifier.size(58.dp).aeroFill(RoundedCornerShape(9.dp), Aero.card, Aero.rimStrong), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Accent, modifier = Modifier.size(28.dp)) }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(message, color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Button(onClick = action, shape = CompactShape, modifier = Modifier.heightIn(min = 44.dp).focusRequester(actionFocusRequester)) { Text(actionLabel) }
        }
    }
}

@Composable
private fun EmptyLibrary(callbacks: AppCallbacks) = StatePanel(PhotoLibrary, "Escolha uma biblioteca de fotos", "O PhotoTool indexa a pasta sem alterar os arquivos originais.", "Escolher pasta", callbacks.chooseLibrary)

@Composable
private fun EmptyGallery(state: AppState, callbacks: AppCallbacks, requestInitialFocus: Boolean = true) = if (state.remote != null && state.remote.libraryTotal == 0) {
    StatePanel(PhotoLibrary, "Nenhuma fotografia indexada", "O servidor não tem um snapshot publicado. Sincronização indisponível neste candidato.", "Atualizar snapshot", callbacks.refresh, requestInitialFocus)
} else if (state.remote == null && state.photos.isEmpty()) {
    StatePanel(PhotoLibrary, "Nenhuma fotografia indexada", "Sincronize a biblioteca para criar um novo snapshot.", "Sincronizar agora", callbacks.synchronize, requestInitialFocus)
} else {
    StatePanel(ImageSearch, "Nenhum resultado", "Nenhuma fotografia corresponde à busca e aos filtros atuais.", "Limpar busca e filtros", { callbacks.dispatch(Action.SetQuery(Query(sort = state.query.sort))) }, requestInitialFocus)
}
@Composable private fun ShortcutHelp(close: () -> Unit) { AlertDialog(onDismissRequest = close, containerColor = Aero.ink2, confirmButton = { TextButton(onClick = close, shape = CompactShape) { Text("Fechar") } }, icon = { Icon(HelpOutline, null) }, title = { Text("Atalhos de teclado") }, text = { Text("Galeria: setas navegam, Enter abre o detalhe e Escape fecha painéis.\nDetalhe: setas esquerda e direita navegam; P, U e X alteram flags; 0 a 5 definem avaliação; N, R, Y e G definem rótulos.\nAtalhos são ignorados enquanto um campo de texto está em foco.") }) }
