package br.com.lincon.phototool.desktop

import br.com.lincon.phototool.domain.Photo
import br.com.lincon.phototool.domain.classifyMedia
import br.com.lincon.phototool.ui.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

const val CLASSIC_CHROME_PROFILE = "Camera CLASSIC CHROME"
const val CLASSIC_CHROME_DIGEST = "A534F9C5F32E988C21A687A4FD5FA5BB"
private const val MAX_FUJI_BYTES = 64 * 1024
private val EXPOSURES = (-9..9).map(::exposureToken).toSet()
private val FUJI_FIELDS = listOf("Editable", "ExposureBias", "DynamicRange", "FilmSimulation", "GrainEffect", "WBShootCond", "WhiteBalance", "WBShiftR", "WBShiftB", "HighlightTone", "ShadowTone", "Color", "Sharpness", "NoisReduction", "LensModulationOpt")
private val FUJI_EDITABLE_FIELDS = FUJI_FIELDS.toSet() - "Editable"

data class FujiRecipe(
    val kind: String,
    val editable: Boolean,
    val device: String,
    val exposureBias: String,
    val dynamicRange: Int,
    val filmSimulation: String,
    val grainEffect: String,
    val shootingCondition: Boolean,
    val whiteBalance: String,
    val wbShiftR: Int,
    val wbShiftB: Int,
    val highlightTone: Int,
    val shadowTone: Int,
    val color: Int,
    val sharpness: Int,
    val noiseReduction: Int,
    val lensModulation: Boolean,
    val version: String,
)

class FujiProfileDocument(private val original: ByteArray, private val kind: String) {
    private val bom = original.startsWith(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
    private val text: String
    private val values: Map<String, String>

    init {
        require(original.size in 1..MAX_FUJI_BYTES && original.none { it == 0.toByte() })
        text = original.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        require("<!DOCTYPE" !in text.uppercase())
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val document = factory.newDocumentBuilder().parse(original.inputStream())
        require(document.documentElement.nodeName == "ConversionProfile" && document.documentElement.getAttribute("application") == "XRFC")
        val groups = document.getElementsByTagName("PropertyGroup")
        require(groups.length == 1 && (groups.item(0) as org.w3c.dom.Element).getAttribute("device") == "X-Pro2")
        values = FUJI_FIELDS.associateWith { field ->
            val nodes = document.getElementsByTagName(field)
            require(nodes.length == 1 && nodes.item(0).childNodes.length <= 1 && (nodes.item(0) as org.w3c.dom.Element).attributes.length == 0) { "Ambiguous Fuji field $field" }
            val matches = Regex("<$field>([^<]*)</$field>").findAll(text).toList()
            require(matches.size == 1) { "Ambiguous serialized Fuji field $field" }
            matches.single().groupValues[1]
        }
        validate(values)
    }

    fun recipe(): FujiRecipe = FujiRecipe(
        kind = kind.lowercase(),
        editable = kind.equals("FP2", true) && values.getValue("Editable") == "TRUE",
        device = "X-Pro2",
        exposureBias = values.getValue("ExposureBias"),
        dynamicRange = values.getValue("DynamicRange").toInt(),
        filmSimulation = values.getValue("FilmSimulation"),
        grainEffect = values.getValue("GrainEffect"),
        shootingCondition = values.getValue("WBShootCond") == "ON",
        whiteBalance = values.getValue("WhiteBalance"),
        wbShiftR = values.getValue("WBShiftR").toInt(),
        wbShiftB = values.getValue("WBShiftB").toInt(),
        highlightTone = values.getValue("HighlightTone").toInt(),
        shadowTone = values.getValue("ShadowTone").toInt(),
        color = values.getValue("Color").toInt(),
        sharpness = values.getValue("Sharpness").toInt(),
        noiseReduction = values.getValue("NoisReduction").toInt(),
        lensModulation = values.getValue("LensModulationOpt") == "ON",
        version = digest(original).take(16),
    )

    fun update(updates: Map<String, String>): ByteArray {
        require(kind.equals("FP2", true) && recipe().editable) { "FP3 is read-only and FP2 must be editable" }
        require(updates.isNotEmpty() && updates.keys.all { it in FUJI_EDITABLE_FIELDS })
        val candidate = values + updates
        validate(candidate)
        var result = text
        updates.forEach { (key, value) -> if (values[key] != value) result = Regex("(<$key>)([^<]*)(</$key>)").replace(result) { "${it.groupValues[1]}$value${it.groupValues[3]}" } }
        val encoded = result.toByteArray(Charsets.UTF_8)
        return if (bom) byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + encoded else encoded
    }

    private fun validate(values: Map<String, String>) {
        require(values["Editable"] in setOf("TRUE", "FALSE"))
        require(values["ExposureBias"] in EXPOSURES)
        require(values["DynamicRange"] in setOf("100", "200", "400"))
        require(values["FilmSimulation"] in setOf("Classic", "NEGAStd", "Astia"))
        require(values["GrainEffect"] in setOf("OFF", "WEAK", "STRONG"))
        require(values["WBShootCond"] in setOf("ON", "OFF"))
        require(values["LensModulationOpt"] in setOf("ON", "OFF"))
        listOf("WBShiftR", "WBShiftB").forEach { require(values[it]?.toIntOrNull() in -9..9) }
        listOf("HighlightTone", "ShadowTone", "Color", "Sharpness", "NoisReduction").forEach { require(values[it]?.toIntOrNull() in -4..4) }
        require(values.getValue("WhiteBalance").length <= 64 && values.getValue("WhiteBalance").none { it.code < 32 || it == '<' })
    }
}

class FujiProfileStore(private val root: Path, private val writeEnabled: Boolean) : AutoCloseable {
    private val realRoot = root.toRealPath()
    private val secure = if (writeEnabled) SecureLibraryBoundary(realRoot, true) else null
    private val locks = ConcurrentHashMap<String, Any>()

    fun read(photo: Photo): FujiRecipe? {
        val profiles = locate(photo)
        val selected = profiles.first ?: profiles.second ?: return null
        return FujiProfileDocument(readBounded(selected), selected.extension).recipe()
    }

    fun mutate(photo: Photo, updates: Map<String, String>): FujiRecipe {
        check(writeEnabled && photo.writable)
        return synchronized(locks.computeIfAbsent(photo.id) { Any() }) {
            val fp2 = locate(photo).first ?: error("An existing exact-stem FP2 profile is required")
            val relativeFp2 = realRoot.relativize(fp2).toString().replace('\\', '/')
            val original = secure!!.read(relativeFp2, MAX_FUJI_BYTES)
            val document = FujiProfileDocument(original, "FP2")
            val updated = document.update(updates)
            if (updated.contentEquals(original)) return@synchronized document.recipe()
            FujiProfileDocument(updated, "FP2")
            if (!secure.read(relativeFp2, MAX_FUJI_BYTES).contentEquals(original)) error("FP2 changed concurrently")
            secure.publish(relativeFp2, updated, original, "fp2")
            val installed = secure.read(relativeFp2, MAX_FUJI_BYTES)
            check(installed.contentEquals(updated)) { "Installed FP2 changed before readback" }
            FujiProfileDocument(installed, "FP2").recipe()
        }
    }

    private fun locate(photo: Photo): Pair<Path?, Path?> {
        val raw = photo.rawPath?.takeIf { Path.of(it).extension.equals("raf", true) } ?: return null to null
        validatePhotoIdentity(photo)
        val source = realRoot.resolve(raw).normalize()
        require(source.startsWith(realRoot))
        val stem = source.fileName.toString().substringBeforeLast('.').lowercase()
        val parentRelative = Path.of(raw).parent?.toString()?.replace('\\', '/') ?: ""
        val entryNames: List<String>
        val names: List<String>
        if (secure != null) {
            val entries = secure.list(parentRelative)
            entryNames = entries.map { it.name }
            names = entries.filter { it.attributes.isRegularFile }.map { it.name }
        } else {
            val entries = Files.list(source.parent).use { it.toList() }
            entryNames = entries.map { it.fileName.toString() }
            names = entries.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }.map { it.fileName.toString() }
        }
        fun candidates(extension: String) = names.filter { it.substringBeforeLast('.').lowercase() == stem && it.substringAfterLast('.', "").equals(extension, true) }
        val fp2 = candidates("fp2")
        val fp3 = candidates("fp3")
        require(fp2.size <= 1 && fp3.size <= 1) { "Ambiguous Fuji profile topology" }
        if (fp2.isEmpty() && entryNames.any { isRecoveryArtifact(it, "$stem.fp2") }) error("FP2 recovery artifact requires manual recovery")
        if (secure != null) {
            val rawNames = names.filter { classifyMedia(it) == br.com.lincon.phototool.domain.MediaKind.RAW && it.substringBeforeLast('.').lowercase() == stem }.toSet()
            val jpegNames = names.filter { classifyMedia(it) == br.com.lincon.phototool.domain.MediaKind.JPEG && it.substringBeforeLast('.').lowercase() == stem }.toSet()
            check(rawNames == setOf(Path.of(photo.rawPath!!).fileName.toString()) && jpegNames == setOfNotNull(photo.jpegPath?.let { Path.of(it).fileName.toString() })) { "Media topology changed" }
            val xmps = names.filter { it.substringBeforeLast('.').lowercase() == stem && it.substringAfterLast('.', "").equals("xmp", true) }
            val legacyXmp = names.any { val inner = it.substringBeforeLast('.', ""); it.substringAfterLast('.', "").equals("xmp", true) && classifyMedia(inner) != null && inner.substringBeforeLast('.').lowercase() == stem }
            val expectedXmp = Path.of(photo.authorityPath).fileName.toString()
            check(xmps.size <= 1 && (xmps.isEmpty() || xmps.single() == expectedXmp) && !legacyXmp) { "XMP topology changed" }
            if (xmps.isEmpty()) check(entryNames.none { isRecoveryArtifact(it, expectedXmp) }) { "XMP recovery artifact requires manual recovery" }
        }
        return fp2.singleOrNull()?.let(source.parent::resolve) to fp3.singleOrNull()?.let(source.parent::resolve)
    }

    private fun validatePhotoIdentity(photo: Photo) {
        val expected = photo.sourceIdentity ?: error("Missing media identity")
        val attrs = secure?.attributes(expected.path) ?: Files.readAttributes(realRoot.resolve(expected.path).normalize(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val key = attrs.fileKey()?.toString() ?: error("Stable media identity unavailable")
        check(attrs.isRegularFile && key == expected.fileKey && attrs.size() == expected.size && attrs.lastModifiedTime().toMillis() == expected.modifiedMillis) { "Media identity is stale" }
    }

    override fun close() { secure?.close() }

    private fun hardLinks(path: Path): Int = (Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt()

    private fun readBounded(path: Path): ByteArray {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && hardLinks(path) == 1)
        require(Files.size(path) in 1..MAX_FUJI_BYTES.toLong())
        return Files.readAllBytes(path)
    }


}

data class HdrSettings(val enabled: Boolean, val maxValue: String? = null, val controls: Map<String, Int> = emptyMap())
private val hdrControls = setOf("SDRBrightness", "SDRContrast", "SDRClarity", "SDRHighlights", "SDRShadows", "SDRWhites", "SDRBlend")
fun hdrUpdates(settings: HdrSettings): Map<String, String?> = if (!settings.enabled) buildMap { put("HDREditMode", "0"); put("HDRMaxValue", null); hdrControls.forEach { put(it, null) } } else buildMap {
    put("HDREditMode", "1")
    put("HDRMaxValue", settings.maxValue?.let { BigDecimal(it).also { number -> require(number in BigDecimal.ONE..BigDecimal("4")) }.setScale(2).toPlainString() } ?: "4.00")
    hdrControls.forEach { key -> put(key, (settings.controls[key] ?: 0).also { require(it in -100..100) }.toString()) }
}

fun evidencedXmpTransfer(recipe: FujiRecipe): Map<String, String> = buildMap {
    put("Exposure2012", fujiExposureToXmp(recipe.exposureBias))
    if (recipe.filmSimulation == "Classic") { put("CameraProfile", CLASSIC_CHROME_PROFILE); put("CameraProfileDigest", CLASSIC_CHROME_DIGEST) }
    if (recipe.shootingCondition && recipe.whiteBalance == "INVALID") put("WhiteBalance", "As Shot")
}

fun evidencedFujiTransfer(settings: DevelopSettings): Map<String, String> = buildMap {
    settings.exposure?.let { put("ExposureBias", xmpExposureToFuji(it)) }
    if (settings.cameraProfile == CLASSIC_CHROME_PROFILE && settings.cameraProfileDigest == CLASSIC_CHROME_DIGEST) put("FilmSimulation", "Classic")
    if (settings.whiteBalance == "As Shot") { put("WBShootCond", "ON"); put("WhiteBalance", "INVALID") }
    require(isNotEmpty()) { "XMP has no evidenced Fuji-compatible fields" }
}

class DesktopAuxiliaryActions(private val xmp: XmpSidecarStore, private val fuji: FujiProfileStore) {
    fun callbacks() = AuxiliaryActions(
        load = { photo -> view(photo) },
        updateFuji = { photo, updates -> fuji.mutate(photo, updates); view(photo, "FP2 persisted after readback") },
        updateHdr = { photo, hdr -> xmp.mutateDevelop(photo, DevelopSettings(hdr.enabled, hdr.maximum, hdr.controls)); view(photo, "HDR persisted after readback") },
        transferFujiToXmp = { photo -> val recipe = fuji.read(photo) ?: error("No Fuji profile"); xmp.mutateDevelopProperties(photo, evidencedXmpTransfer(recipe)); view(photo, "Evidenced Fuji fields transferred to XMP") },
        transferXmpToFuji = { photo -> fuji.mutate(photo, evidencedFujiTransfer(xmp.readDevelop(photo))); view(photo, "Evidenced XMP fields transferred to FP2") },
    )

    private fun view(photo: Photo, status: String = "Recipe and HDR read") = AuxiliaryView(
        fuji = fuji.read(photo)?.let { FujiRecipeView(it.kind, it.editable, it.exposureBias, it.dynamicRange, it.filmSimulation, it.grainEffect, it.whiteBalance, it.wbShiftR, it.wbShiftB, it.highlightTone, it.shadowTone, it.color, it.sharpness, it.noiseReduction, it.lensModulation) },
        hdr = xmp.readDevelop(photo).let { HdrView(it.hdrEnabled, it.hdrMaximum, it.controls) },
        status = status,
    )
}

private fun exposureToken(thirds: Int): String {
    if (thirds == 0) return "0"
    val absolute = kotlin.math.abs(thirds)
    return "${if (thirds > 0) 'P' else 'M'}${absolute / 3}P${listOf("00", "33", "67")[absolute % 3]}"
}
private fun fujiExposureToXmp(value: String): String {
    if (value == "0") return "0.00"
    val match = Regex("([PM])(\\d)P(00|33|67)").matchEntire(value) ?: error("Unsupported exposure")
    val decimal = BigDecimal(match.groupValues[2]) + when (match.groupValues[3]) { "33" -> BigDecimal(".33"); "67" -> BigDecimal(".67"); else -> BigDecimal.ZERO }
    return (if (match.groupValues[1] == "M") decimal.negate() else decimal).let { if (it.signum() > 0) "+${it.setScale(2)}" else it.setScale(2).toString() }
}
private fun xmpExposureToFuji(value: String): String {
    val thirds = BigDecimal(value).multiply(BigDecimal(3)).setScale(0, RoundingMode.HALF_UP).toInt()
    require(thirds in -9..9)
    return exposureToken(thirds)
}
private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
private val Path.extension get() = fileName.toString().substringAfterLast('.', "")
