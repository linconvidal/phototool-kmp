package br.com.lincon.phototool.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.lincon.phototool.resources.Res
import br.com.lincon.phototool.resources.fira_mono_bold
import br.com.lincon.phototool.resources.fira_mono_medium
import br.com.lincon.phototool.resources.fira_mono_regular
import br.com.lincon.phototool.resources.fira_sans_bold
import br.com.lincon.phototool.resources.fira_sans_medium
import br.com.lincon.phototool.resources.fira_sans_regular
import br.com.lincon.phototool.resources.fira_sans_semibold
import org.jetbrains.compose.resources.Font

/**
 * Aero/Aqua design tokens: a top-lit glass material over a cold ink scale.
 *
 * Everything here is paint-only. Surfaces never change size, padding or spacing, so the
 * existing layout stays authoritative and only the material identity moves.
 */
internal object Aero {
    // Ink scale: window, panels, raised chrome.
    val ink0 = Color(0xff050a13)
    val ink1 = Color(0xff0a1524)
    val ink2 = Color(0xff0e1c30)
    val ink3 = Color(0xff16263c)
    val ink4 = Color(0xff1d3350)

    // Text scale.
    val text = Color(0xffe9f3ff)
    val text2 = Color(0xffb7c9dd)
    val text3 = Color(0xff9aafc4)

    // Aqua accents.
    val sky = Color(0xff8ce0ff)
    val sky2 = Color(0xff4bb8f5)
    val sky3 = Color(0xff1f7fd6)
    val mint = Color(0xff63e6c0)
    val amber = Color(0xffffc46b)
    val rose = Color(0xffff7a8a)

    // Hairlines and rims. The top edge reads brighter than the sides, which is what
    // makes a flat panel look like a lit pane instead of a rectangle.
    val hairline = Color(0x1affffff)
    val edge = Color(0x24ffffff)
    val edgeTop = Color(0x75ffffff)

    private fun stops(vararg pairs: Pair<Float, Color>) = Brush.verticalGradient(*pairs)

    /** Window and toolbar chrome: light from above, deep ink below. */
    val window: Brush = stops(
        0f to Color(0xff1a2e49),
        .07f to Color(0xff142639),
        .52f to Color(0xff0c1a2b),
        1f to Color(0xff0a1524),
    )

    /** Panel body: inspector, sidebars, sheets. */
    val panel: Brush = stops(
        0f to Color(0xff152840),
        .06f to Color(0xff102034),
        1f to Color(0xff0a1524),
    )

    /** Card and list surface: slightly lifted from the sheet. */
    val card: Brush = stops(
        0f to Color(0xff1b304c),
        .1f to Color(0xff13253c),
        1f to Color(0xff0b1728),
    )

    /** Sunken well: inputs, log panels, previews of empty state. */
    val well: Brush = stops(
        0f to Color(0xff040d1a),
        .35f to Color(0xff071322),
        1f to Color(0xff0b1a2c),
    )

    /** Aqua gel for the primary action. */
    val gel: Brush = stops(
        0f to Color(0xffb7f0ff),
        .42f to Color(0xff6fcbf8),
        .53f to Color(0xff4fb6f2),
        1f to Color(0xff2a8fdd),
    )

    /** Same gel, drained: disabled buttons keep their shape, not their light. */
    val gelDisabled: Brush = stops(
        0f to Color(0xff2b3f56),
        .52f to Color(0xff22344a),
        1f to Color(0xff1a283a),
    )

    /** Neutral rim: bright top edge fading into the sides. */
    val rim: Brush = stops(
        0f to Color(0x8cffffff),
        .16f to Color(0x33ffffff),
        1f to Color(0x14ffffff),
    )

    /** Rim for raised elements that need a stronger outline. */
    val rimStrong: Brush = stops(
        0f to Color(0xc7ffffff),
        .2f to Color(0x47ffffff),
        1f to Color(0x1fffffff),
    )

    /** Rim for the selected or focused element. */
    val accentRim: Brush = stops(
        0f to Color(0xd9d8f6ff),
        .25f to Color(0xa68ce0ff),
        1f to Color(0x594bb8f5),
    )

    /** Well edge on a dark inset. */
    val wellRim: Brush = stops(
        0f to Color(0x0dffffff),
        .3f to Color(0x1affffff),
        1f to Color(0x26ffffff),
    )
}

/** Base window atmosphere: cold ink with a lit top and a faint mint floor. */
internal fun Modifier.aeroAtmosphere(): Modifier = drawWithCache {
    val skyGlow = Brush.radialGradient(
        colors = listOf(Color(0x1f8ce0ff), Color(0x00000000)),
        center = Offset(size.width * .18f, size.height * -.05f),
        radius = size.width * .85f,
    )
    val floorGlow = Brush.radialGradient(
        colors = listOf(Color(0x1463e6c0), Color(0x00000000)),
        center = Offset(size.width * .92f, size.height * 1.05f),
        radius = size.width * .7f,
    )
    val depth = Brush.verticalGradient(
        listOf(Color(0x00000000), Color(0x66030a14)),
    )
    onDrawBehind {
        drawRect(Aero.ink0)
        drawRect(skyGlow)
        drawRect(floorGlow)
        drawRect(depth)
    }
}

/**
 * Fills the element with an Aero material and draws its rim. Clips children to [shape],
 * which the call site already did before painting a background.
 */
internal fun Modifier.aeroSurface(
    shape: Shape,
    brush: Brush,
    rim: Brush? = Aero.rim,
    rimWidth: Dp = 1.dp,
): Modifier = this
    .clip(shape)
    .background(brush)
    .then(if (rim == null) Modifier else Modifier.border(rimWidth, rim, shape))

/** Same material, without clipping: for surfaces whose children intentionally overflow. */
internal fun Modifier.aeroFill(
    shape: Shape,
    brush: Brush,
    rim: Brush? = Aero.rim,
    rimWidth: Dp = 1.dp,
): Modifier = this
    .background(brush)
    .then(if (rim == null) Modifier else Modifier.border(rimWidth, rim, shape))

/** A single hairline along one edge, without changing the element's size. */
internal fun Modifier.aeroEdgeLine(top: Boolean = false, bottom: Boolean = false, start: Boolean = false, end: Boolean = false, color: Color = Aero.hairline): Modifier =
    drawWithCache {
        val stroke = 1.dp.toPx()
        onDrawBehind {
            if (top) drawLine(color, Offset(0f, stroke / 2f), Offset(size.width, stroke / 2f), stroke)
            if (bottom) drawLine(color, Offset(0f, size.height - stroke / 2f), Offset(size.width, size.height - stroke / 2f), stroke)
            if (start) drawLine(color, Offset(stroke / 2f, 0f), Offset(stroke / 2f, size.height), stroke)
            if (end) drawLine(color, Offset(size.width - stroke / 2f, 0f), Offset(size.width - stroke / 2f, size.height), stroke)
        }
    }

/** Border-only helper for call sites that keep their own background. */
internal fun Modifier.aeroRim(shape: Shape, rim: Brush = Aero.rim, width: Dp = 1.dp): Modifier =
    border(BorderStroke(width, rim), shape)

/** Aqua gel: the primary action. Disabled keeps the shape and loses the light. */
internal fun Modifier.aeroGel(shape: Shape, enabled: Boolean = true): Modifier =
    aeroSurface(shape, if (enabled) Aero.gel else Aero.gelDisabled, rim = if (enabled) Aero.rimStrong else Aero.rim)

/**
 * Spacing rhythm: a single 2 dp grid so paddings, gaps and control heights stop drifting
 * by one or two dp per screen. Containers land on [xl]/[xxl], inner groups on [sm]/[md].
 */
internal object AeroSpace {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 6.dp
    val md = 8.dp
    val lg = 12.dp
    val xl = 16.dp
    val xxl = 20.dp
    val xxxl = 28.dp

    /** Minimum hit target. Never reduced for density. */
    val touch = 44.dp
}

/** Fira Sans carries the Aero/Vista humanist voice; bundled so desktop, web and iOS match. */
@Composable
internal fun aeroSans(): FontFamily = FontFamily(
    Font(Res.font.fira_sans_regular, FontWeight.Normal),
    Font(Res.font.fira_sans_medium, FontWeight.Medium),
    Font(Res.font.fira_sans_semibold, FontWeight.SemiBold),
    Font(Res.font.fira_sans_bold, FontWeight.Bold),
)

/** Fira Mono is reserved for metadata, counters and eyebrow labels. */
@Composable
internal fun aeroMono(): FontFamily = FontFamily(
    Font(Res.font.fira_mono_regular, FontWeight.Normal),
    Font(Res.font.fira_mono_medium, FontWeight.Medium),
    Font(Res.font.fira_mono_bold, FontWeight.Bold),
)

/**
 * Type scale tuned for Fira Sans: its taller x-height reads larger than the default sans,
 * so body sizes step down half a point while titles keep a tight, top-lit rhythm.
 */
@Composable
internal fun aeroTypography(): Typography {
    val sans = aeroSans()
    val mono = aeroMono()
    val base = Typography()
    return Typography(
        titleLarge = base.titleLarge.copy(fontFamily = sans, fontSize = 22.sp, lineHeight = 27.sp, letterSpacing = (-.3).sp, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontFamily = sans, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-.45).sp, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = sans, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-.2).sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontFamily = sans, fontSize = 14.5.sp, lineHeight = 21.sp, letterSpacing = 0.sp),
        bodyMedium = base.bodyMedium.copy(fontFamily = sans, fontSize = 13.5.sp, lineHeight = 19.sp, letterSpacing = 0.sp),
        bodySmall = base.bodySmall.copy(fontFamily = sans, fontSize = 12.5.sp, lineHeight = 17.sp, letterSpacing = 0.sp),
        labelLarge = base.labelLarge.copy(fontFamily = sans, fontSize = 12.5.sp, lineHeight = 16.sp, letterSpacing = .2.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontFamily = mono, fontSize = 11.5.sp, lineHeight = 15.sp, letterSpacing = .5.sp, fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(fontFamily = mono, fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = .7.sp, fontWeight = FontWeight.Medium),
    )
}
