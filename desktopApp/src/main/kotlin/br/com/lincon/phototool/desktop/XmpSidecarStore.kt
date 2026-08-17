package br.com.lincon.phototool.desktop

import br.com.lincon.phototool.domain.*
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.nio.charset.Charset
import java.nio.file.*
import java.security.MessageDigest
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
private const val XMP = "http://ns.adobe.com/xap/1.0/"
private const val XMP_DM = "http://ns.adobe.com/xmp/1.0/DynamicMedia/"
private const val DC = "http://purl.org/dc/elements/1.1/"
private const val LR = "http://ns.adobe.com/lightroom/1.0/"
private const val CRS = "http://ns.adobe.com/camera-raw-settings/1.0/"
private const val MAX_XMP_BYTES = 16 * 1024 * 1024

private val HDR_CONTROLS = listOf("SDRBrightness", "SDRContrast", "SDRClarity", "SDRHighlights", "SDRShadows", "SDRWhites", "SDRBlend")
private val DEVELOP_FIELDS = setOf("Exposure2012", "CameraProfile", "CameraProfileDigest", "WhiteBalance", "HDREditMode", "HDRMaxValue") + HDR_CONTROLS

data class DevelopSettings(
    val hdrEnabled: Boolean = false,
    val hdrMaximum: String = "4.00",
    val controls: Map<String, Int> = emptyMap(),
    val exposure: String? = null,
    val cameraProfile: String? = null,
    val cameraProfileDigest: String? = null,
    val whiteBalance: String? = null,
)

class XmpException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
internal data class XmpDevelopSnapshot(val settings: DevelopSettings, val contentDigest: String)

/**
 * Adjacent sidecar store. Java NIO has no portable rename-exchange primitive, so
 * replacement uses a conservative same-directory fallback: fsync a sibling temp,
 * atomically displace the expected original to a unique previous name, compare the
 * displaced bytes again, and install descriptor-relatively. A raced displacement is copied
 * to a unique conflict artifact and restored as canonical before failing closed.
 */
class XmpSidecarStore internal constructor(
    private val root: Path,
    private val writeEnabled: Boolean,
    authorityDisplaced: (() -> Unit)? = null,
) : AutoCloseable {
    private val realRoot = root.toRealPath()
    private val secure = SecureLibraryBoundary(realRoot, writeEnabled, authorityDisplaced)

    fun read(photo: Photo): EditorialState = readWithSharedListings(photo, null)

    internal fun readWithSharedListings(
        photo: Photo,
        sharedListings: MutableMap<String, List<SecureLibraryBoundary.Entry>>?,
    ): EditorialState = withPhotoLock(photo) {
        validatePinnedMedia(photo)
        val sidecar = sidecar(photo)
        validateReadableTopology(photo, sidecar, sharedListings)
        if (!secure.exists(photo.authorityPath)) EditorialState()
        else parse(secure.read(photo.authorityPath, MAX_XMP_BYTES)).state()
    }

    fun readDevelop(photo: Photo): DevelopSettings = withPhotoLock(photo) {
        validatePinnedMedia(photo)
        val sidecar = sidecar(photo)
        validateReadableTopology(photo, sidecar)
        if (!secure.exists(photo.authorityPath)) DevelopSettings()
        else parse(secure.read(photo.authorityPath, MAX_XMP_BYTES)).developState()
    }

    internal fun readDevelopSnapshot(photo: Photo): XmpDevelopSnapshot = withPhotoLock(photo) {
        validatePinnedMedia(photo)
        val sidecar = sidecar(photo)
        validateReadableTopology(photo, sidecar)
        val bytes = if (secure.exists(photo.authorityPath)) secure.read(photo.authorityPath, MAX_XMP_BYTES) else baseXmp()
        XmpDevelopSnapshot(parse(bytes).developState(), digestHex(bytes))
    }

    internal fun confirmDevelopSnapshot(photo: Photo, snapshot: XmpDevelopSnapshot): Boolean = withPhotoLock(photo) {
        validatePinnedMedia(photo)
        validateReadableTopology(photo, sidecar(photo))
        val bytes = if (secure.exists(photo.authorityPath)) secure.read(photo.authorityPath, MAX_XMP_BYTES) else baseXmp()
        digestHex(bytes) == snapshot.contentDigest
    }

    fun mutate(photo: Photo, desired: EditorialState, canonicalizeFlag: Boolean = false): EditorialState {
        val normalized = desired.copy(keywords = desired.keywords.map(::normalizeKeyword).distinctBy(::keywordCasefold))
        require(normalized.rating in 0..5 && normalized.keywords.size <= 256)
        return mutateDocument(photo) { document ->
            val current = document.state()
            document.applyEditorial(current, normalized, canonicalizeFlag)
        }.state()
    }

    fun mutateDevelop(photo: Photo, desired: DevelopSettings): DevelopSettings {
        val updates = if (!desired.hdrEnabled) buildMap<String, String?> {
            put("HDREditMode", "0"); put("HDRMaxValue", null); HDR_CONTROLS.forEach { put(it, null) }
        } else buildMap {
            put("HDREditMode", "1")
            val maximum = desired.hdrMaximum.toBigDecimalOrNull() ?: throw XmpException("HDR maximum must be numeric")
            require(maximum >= java.math.BigDecimal.ONE && maximum <= java.math.BigDecimal("4"))
            put("HDRMaxValue", maximum.setScale(2).toPlainString())
            HDR_CONTROLS.forEach { name -> put(name, (desired.controls[name] ?: 0).also { require(it in -100..100) }.toString()) }
        }
        return mutateProperties(photo, updates).developState()
    }

    fun mutateDevelopProperties(
        photo: Photo,
        updates: Map<String, String?>,
        sourceStillCurrent: (() -> Boolean)? = null,
    ): DevelopSettings = mutateProperties(photo, updates, sourceStillCurrent).developState()

    private fun mutateProperties(photo: Photo, updates: Map<String, String?>, sourceStillCurrent: (() -> Boolean)? = null): XmpDocument {
        require(updates.isNotEmpty() && updates.keys.all { it in DEVELOP_FIELDS })
        return mutateDocument(photo, sourceStillCurrent) { document -> document.applyDevelop(updates) }
    }

    private fun mutateDocument(photo: Photo, sourceStillCurrent: (() -> Boolean)? = null, update: (XmpDocument) -> Boolean): XmpDocument {
        check(writeEnabled) { "Launch with --enable-write to permit XMP changes" }
        check(photo.writable) { photo.issue ?: "Ambiguous photo is not writable" }
        val sidecar = sidecar(photo)
        return withPhotoLock(photo) {
            validateTopology(photo, sidecar)
            val relative = photo.authorityPath
            val existed = secure.exists(relative)
            val expectedEntry = if (existed) secure.expectation(relative) ?: throw XmpException("XMP identity disappeared") else null
            val original = if (existed) secure.read(relative, MAX_XMP_BYTES) else baseXmp()
            val originalDigest = digest(original)
            val document = parse(original)
            document.state()
            document.developState()
            if (!update(document)) return@withPhotoLock document
            val updated = serialize(document)
            check(updated.size in 1..MAX_XMP_BYTES)
            // Parse and validate every managed property before touching the directory.
            val expected = parse(updated)
            expected.state()
            expected.developState()
            validateTopology(photo, sidecar)
            if (existed && digest(secure.read(relative, MAX_XMP_BYTES)) != originalDigest) throw XmpException("XMP changed concurrently")
            if (!existed && secure.exists(relative)) throw XmpException("XMP appeared concurrently")
            secure.publish(
                relative,
                updated,
                if (existed) original else null,
                "xmp",
                expectedEntry,
                beforeCommit = {
                    check(sourceStillCurrent?.invoke() != false) { "Transfer source changed before XMP publication" }
                },
            )
            val installed = parse(secure.read(relative, MAX_XMP_BYTES))
            installed.state()
            installed.developState()
            installed
        }
    }

    internal fun <T> withPhotoLock(photo: Photo, block: () -> T): T =
        EditorialPhotoLocks.withLock(realRoot, photo, block)

    private fun sidecar(photo: Photo): Path {
        val resolved = realRoot.resolve(photo.authorityPath).normalize()
        if (!resolved.startsWith(realRoot) || resolved.parent == null) throw XmpException("Sidecar escapes library")
        var parent = resolved.parent
        while (parent != realRoot) {
            if (Files.isSymbolicLink(parent)) throw XmpException("Symlinked sidecar parent")
            parent = parent.parent ?: throw XmpException("Sidecar escapes library")
        }
        return resolved
    }

    private fun validatePinnedMedia(photo: Photo) {
        val expected = photo.sourceIdentity ?: throw XmpException("Missing indexed media identity")
        val attrs = secure.attributes(expected.path) ?: throw XmpException("Media authority changed")
        val key = attrs.fileKey()?.toString() ?: throw XmpException("Stable media identity unavailable")
        if (!attrs.isRegularFile || key != expected.fileKey || attrs.size() != expected.size || attrs.lastModifiedTime().toMillis() != expected.modifiedMillis) throw XmpException("Media identity is stale")
    }

    private fun validateTopology(photo: Photo, sidecar: Path) {
        val boundary = secure
        validatePinnedMedia(photo)
        val authorityRelative = photo.rawPath ?: photo.jpegPath ?: throw XmpException("Missing media authority")
        val authorityName = Path.of(authorityRelative).fileName.toString()
        val stem = caseFoldText(authorityName.substringBeforeLast('.'))
        val parent = Path.of(authorityRelative).parent?.toString()?.replace('\\', '/') ?: ""
        val entries = boundary.list(parent)
        val entryNames = entries.map { it.name }
        val regularNames = entries.filter { it.attributes.isRegularFile }.map { it.name }
        val raws = regularNames.filter { classifyMedia(it) == MediaKind.RAW && caseFoldText(it.substringBeforeLast('.')) == stem }.toSet()
        val jpegs = regularNames.filter { classifyMedia(it) == MediaKind.JPEG && caseFoldText(it.substringBeforeLast('.')) == stem }.toSet()
        val indexedRaws = setOfNotNull(photo.rawPath?.let { Path.of(it).fileName.toString() })
        val indexedJpegs = setOfNotNull(photo.jpegPath?.let { Path.of(it).fileName.toString() })
        if (raws != indexedRaws || jpegs != indexedJpegs) throw XmpException("Media topology changed")
        val canonical = regularNames.filter { caseFoldText(it.substringBeforeLast('.', it)) == stem && it.substringAfterLast('.', "").equals("xmp", true) }
        val legacy = regularNames.filter { name ->
            val inner = name.substringBeforeLast('.', "")
            classifyMedia(inner) != null && caseFoldText(inner.substringBeforeLast('.')) == stem && name.substringAfterLast('.', "").equals("xmp", true)
        }
        val expectedName = Path.of(photo.authorityPath).fileName.toString()
        if (canonical.size > 1 || legacy.isNotEmpty() || (canonical.isNotEmpty() && canonical.single() != expectedName)) throw XmpException("XMP authority is ambiguous")
        if (canonical.isEmpty() && entryNames.any { isRecoveryArtifact(it, expectedName) }) throw XmpException("XMP recovery artifact requires manual recovery")
        boundary.attributes(photo.authorityPath)?.let {
            if (!it.isRegularFile || hardLinkCount(sidecar) != 1) throw XmpException("Unsafe XMP sidecar")
        }
    }

    private fun validateReadableTopology(
        photo: Photo,
        sidecar: Path,
        sharedListings: MutableMap<String, List<SecureLibraryBoundary.Entry>>? = null,
    ) {
        val expectedName = Path.of(photo.authorityPath).fileName.toString()
        val stem = caseFoldText(expectedName.substringBeforeLast('.'))
        val parent = Path.of(photo.authorityPath).parent?.toString()?.replace('\\', '/') ?: ""
        val entries = sharedListings?.getOrPut(parent) { secure.list(parent) } ?: secure.list(parent)
        val entryNames = entries.map { it.name }
        val regularNames = entries.filter { it.attributes.isRegularFile }.map { it.name }
        val canonical = regularNames.filter { caseFoldText(it.substringBeforeLast('.', it)) == stem && it.substringAfterLast('.', "").equals("xmp", true) }
        val legacy = regularNames.filter { name ->
            val inner = name.substringBeforeLast('.', "")
            classifyMedia(inner) != null && caseFoldText(inner.substringBeforeLast('.')) == stem && name.substringAfterLast('.', "").equals("xmp", true)
        }
        if (canonical.size > 1 || legacy.isNotEmpty() || (canonical.isNotEmpty() && canonical.single() != expectedName)) throw XmpException("XMP authority is ambiguous")
        if (canonical.isEmpty() && entryNames.any { isRecoveryArtifact(it, expectedName) }) throw XmpException("XMP recovery artifact requires manual recovery")
    }

    private fun hardLinkCount(path: Path): Int = runCatching { (Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() }
        .getOrElse { throw XmpException("Hard-link topology is unavailable", it) }


    override fun close() { secure.close() }

    private fun parse(bytes: ByteArray): XmpDocument {
        val style = XmlStyle.detect(bytes)
        if (style.decode(bytes).contains("<!DOCTYPE", true)) throw XmpException("DOCTYPE is forbidden")
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isIgnoringComments = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            }
            val input = org.xml.sax.InputSource(java.io.StringReader(style.decode(bytes))).apply {
                encoding = style.encodingName
            }
            XmpDocument(factory.newDocumentBuilder().parse(input), style)
        } catch (error: Exception) { if (error is XmpException) throw error else throw XmpException("Malformed XMP", error) }
    }

    private fun serialize(document: XmpDocument): ByteArray {
        val output = java.io.StringWriter()
        val transformer = TransformerFactory.newInstance().apply {
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
        }.newTransformer()
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, if (document.style.declaration) "no" else "yes")
        transformer.setOutputProperty(OutputKeys.ENCODING, document.style.encodingName)
        transformer.setOutputProperty(OutputKeys.INDENT, "no")
        transformer.transform(DOMSource(document.document), StreamResult(output))
        var text = output.toString().replace("\r\n", "\n").replace("\r", "\n")
        if (document.style.declaration) {
            val end = text.indexOf("?>")
            if (end >= 0 && text.getOrNull(end + 2) != '\n') {
                text = text.substring(0, end + 2) + "\n" + text.substring(end + 2)
            }
        }
        if (document.style.newline != "\n") text = text.replace("\n", document.style.newline)
        val encoded = text.toByteArray(document.style.charset)
        return if (document.style.bom.isNotEmpty()) document.style.bom + encoded else encoded
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).contentToString()
    private fun digestHex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun baseXmp() = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="$RDF"><rdf:Description rdf:about="" xmlns:xmp="$XMP" xmlns:xmpDM="$XMP_DM"/></rdf:RDF></x:xmpmeta>""".toByteArray()
}

private data class XmlStyle(val charset: Charset, val encodingName: String, val declaration: Boolean, val bom: ByteArray, val newline: String) {
    fun decode(bytes: ByteArray): String = bytes.drop(if (bom.isNotEmpty() && bytes.startsWithBytes(bom)) bom.size else 0).toByteArray().toString(charset)
    companion object {
        fun detect(bytes: ByteArray): XmlStyle {
            val bom = when {
                bytes.startsWithBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
                bytes.startsWithBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
                bytes.startsWithBytes(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
                else -> byteArrayOf()
            }
            val initial = when {
                bom.contentEquals(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) -> Charsets.UTF_16LE
                bom.contentEquals(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) -> Charsets.UTF_16BE
                else -> Charsets.UTF_8
            }
            val probe = bytes.drop(bom.size).toByteArray().toString(initial)
            val declaration = probe.trimStart().startsWith("<?xml")
            val encoding = Regex("<\\?xml[^>]*encoding=[\"']([^\"']+)", RegexOption.IGNORE_CASE).find(probe)?.groupValues?.get(1)
            val charset = runCatching {
                if (initial in setOf(Charsets.UTF_16LE, Charsets.UTF_16BE) && encoding?.uppercase()?.startsWith("UTF-16") == true) initial
                else encoding?.let(Charset::forName) ?: initial
            }.getOrElse { throw XmpException("Unsupported XMP encoding") }
            val newline = when {
                "\r\n" in probe -> "\r\n"
                '\r' in probe -> "\r"
                else -> "\n"
            }
            return XmlStyle(charset, encoding ?: charset.name(), declaration, bom, newline)
        }
    }
}

private class XmpDocument(val document: Document, val style: XmlStyle) {
    private val descriptions: List<Element> get() {
        val descriptions = document.getElementsByTagNameNS(RDF, "Description")
        if (descriptions.length == 0) throw XmpException("XMP must contain rdf:Description")
        return buildList { repeat(descriptions.length) { add(descriptions.item(it) as Element) } }
    }
    private val description: Element get() = descriptions.first()

    private fun references(namespace: String, local: String): List<Pair<Element, Boolean>> = buildList {
        descriptions.forEach { description -> if (description.hasAttributeNS(namespace, local)) add(description to true) }
        val elements = document.getElementsByTagNameNS(namespace, local)
        repeat(elements.length) { add(elements.item(it) as Element to false) }
    }

    private fun rawValues(namespace: String, local: String): List<String> = references(namespace, local).map { (owner, attribute) ->
        if (attribute) owner.getAttributeNS(namespace, local) else owner.textContent.orEmpty()
    }

    private fun value(namespace: String, local: String): String? {
        val values = rawValues(namespace, local)
        if (values.distinct().size > 1) throw XmpException("Conflicting managed XMP $local values")
        return values.firstOrNull()
    }

    fun state(): EditorialState {
        val pickRaw = value(XMP_DM, "pick")
        val pick = pickRaw?.takeIf { it.length <= 64 && it.matches(Regex("[+-]?[0-9]+")) }?.toIntOrNull()
            ?: if (pickRaw == null) null else throw XmpException("Unsupported pick value")
        val flag = when (pick) { null, 0 -> Flag.UNFLAGGED; 1 -> Flag.PICK; -1 -> Flag.REJECT; else -> throw XmpException("Unsupported pick value") }
        val goodValues = rawValues(XMP_DM, "good")
        val managedGood = goodValues.mapNotNull { raw -> when (raw.lowercase()) { "true" -> true; "false" -> false; else -> null } }.distinct()
        if (managedGood.size > 1) throw XmpException("Conflicting managed XMP good values")
        val good = managedGood.singleOrNull()
        val goodError = if (goodValues.any { it.lowercase() !in setOf("true", "false") }) "xmp-good-invalid" else null
        val ratingRaw = value(XMP, "Rating")
        val rating = ratingRaw?.toIntOrNull() ?: if (ratingRaw == null) 0 else throw XmpException("Malformed rating")
        if (rating !in 0..5) throw XmpException("Unsupported rating")
        val labels = rawValues(XMP, "Label").mapNotNull { raw -> when (raw) { "Red" -> ColorLabel.RED; "Yellow" -> ColorLabel.YELLOW; "Green" -> ColorLabel.GREEN; else -> null } }.distinct()
        if (labels.size > 1) throw XmpException("Conflicting managed XMP Label values")
        val label = labels.singleOrNull()
        return EditorialState(flag, rating, label, keywordValues(), good, goodError)
    }

    fun developState(): DevelopSettings {
        val mode = value(CRS, "HDREditMode")
        if (mode !in setOf(null, "0", "1")) throw XmpException("Unsupported HDR mode")
        val maximumRaw = value(CRS, "HDRMaxValue")
        val maximumNumber = maximumRaw?.toBigDecimalOrNull() ?: if (maximumRaw == null) null else throw XmpException("Malformed HDR maximum")
        val maximum = maximumNumber?.also { if (it < java.math.BigDecimal.ONE || it > java.math.BigDecimal("4") || it.scale() > 2) throw XmpException("Unsupported HDR maximum") }?.setScale(2)?.toPlainString() ?: "4.00"
        val controls = HDR_CONTROLS.mapNotNull { name -> value(CRS, name)?.let { raw -> val number = raw.toIntOrNull() ?: throw XmpException("Invalid $name"); if (number !in -100..100) throw XmpException("Invalid $name"); name to number } }.toMap()
        if (mode == "1" && (maximumRaw == null || controls.size != HDR_CONTROLS.size)) throw XmpException("Enabled HDR block is incomplete")
        if (mode != "1" && (maximumRaw != null || controls.isNotEmpty())) throw XmpException("Disabled HDR contains managed values")
        val exposure = value(CRS, "Exposure2012")?.also { raw ->
            val number = raw.toBigDecimalOrNull() ?: throw XmpException("Invalid exposure")
            if (number < java.math.BigDecimal("-5") || number > java.math.BigDecimal("5")) throw XmpException("Exposure outside supported range")
        }
        listOf("CameraProfile", "CameraProfileDigest", "WhiteBalance").forEach { name -> value(CRS, name)?.let { if (it.isEmpty() || it.length > 256 || it.any { character -> character.code < 32 }) throw XmpException("Invalid $name") } }
        return DevelopSettings(mode == "1", maximum, controls, exposure, value(CRS, "CameraProfile"), value(CRS, "CameraProfileDigest"), value(CRS, "WhiteBalance"))
    }

    private fun keywordValues(): List<String> {
        val output = mutableListOf<String>()
        val observed = mutableSetOf<String>()
        listOf(DC to "subject", LR to "hierarchicalSubject").forEach { (namespace, local) ->
            val parents = document.getElementsByTagNameNS(namespace, local)
            val projections = buildList {
                repeat(parents.length) { index ->
                val element = parents.item(index) as Element
                val children = element.childNodes.elements()
                if (children.size != 1 || children.single().namespaceURI != RDF || children.single().localName != "Bag") throw XmpException("Keyword array must use rdf:Bag")
                val items = children.single().childNodes.elements()
                if (items.size > 256 || items.any { it.namespaceURI != RDF || it.localName != "li" || it.attributes.length != 0 || it.childNodes.elements().isNotEmpty() }) throw XmpException("Unsupported keyword items")
                val localValues = mutableListOf<String>()
                val seenInArray = mutableSetOf<String>()
                items.forEach { item ->
                    val normalized = runCatching { normalizeKeyword(item.textContent) }.getOrNull() ?: return@forEach
                    val key = keywordCasefold(normalized)
                    if (!seenInArray.add(key)) throw XmpException("Duplicate managed keyword")
                    localValues += normalized
                }
                add(localValues)
                }
            }
            val folded = projections.map { values -> values.map(::keywordCasefold) }
            if (folded.distinct().size > 1) throw XmpException("Conflicting managed keyword arrays")
            projections.firstOrNull().orEmpty().forEach { normalized ->
                if (observed.add(keywordCasefold(normalized))) output += normalized
            }
        }
        return output
    }

    fun applyEditorial(current: EditorialState, desired: EditorialState, canonicalizeFlag: Boolean): Boolean {
        var changed = false
        if (canonicalizeFlag || current.flag != desired.flag) {
            val desiredPick = when (desired.flag) { Flag.PICK -> "1"; Flag.UNFLAGGED -> "0"; Flag.REJECT -> "-1" }
            val desiredGood = when (desired.flag) { Flag.PICK -> "True"; Flag.UNFLAGGED -> null; Flag.REJECT -> "False" }
            changed = value(XMP_DM, "pick") != desiredPick || rawValues(XMP_DM, "good").distinct() != listOfNotNull(desiredGood)
            set(XMP_DM, "xmpDM:pick", "pick", desiredPick)
            if (desiredGood == null) remove(XMP_DM, "good") else set(XMP_DM, "xmpDM:good", "good", desiredGood)
        }
        if (current.rating != desired.rating) { set(XMP, "xmp:Rating", "Rating", desired.rating.toString()); changed = true }
        if (current.label != desired.label) {
            val label = desired.label
            if (label == null) remove(XMP, "Label") else set(XMP, "xmp:Label", "Label", label.name.lowercase().replaceFirstChar { it.uppercase() })
            changed = true
        }
        if (current.keywords != desired.keywords) { updateKeywords(current.keywords, desired.keywords); changed = true }
        return changed
    }

    fun applyDevelop(updates: Map<String, String?>): Boolean {
        var changed = false
        updates.forEach { (name, desired) ->
            val normalized = when {
                desired == null -> null
                name in HDR_CONTROLS -> desired.toIntOrNull()?.also { require(it in -100..100) }?.toString() ?: throw XmpException("Invalid $name")
                name == "HDREditMode" -> desired.also { require(it in setOf("0", "1")) }
                name == "HDRMaxValue" -> desired.toBigDecimalOrNull()?.also { require(it >= java.math.BigDecimal.ONE && it <= java.math.BigDecimal("4")) }?.setScale(2)?.toPlainString() ?: throw XmpException("Invalid HDR maximum")
                else -> desired.also { require(it.length <= 256 && it.none { character -> character.code < 32 }) }
            }
            val currentRaw = value(CRS, name)
            val current = when {
                currentRaw == null -> null
                name in HDR_CONTROLS -> currentRaw.toIntOrNull()?.toString() ?: currentRaw
                name == "HDRMaxValue" -> currentRaw.toBigDecimalOrNull()?.setScale(2)?.toPlainString() ?: currentRaw
                else -> currentRaw
            }
            if (current != normalized) { if (normalized == null) remove(CRS, name) else set(CRS, "crs:$name", name, normalized); changed = true }
        }
        return changed
    }

    private fun set(namespace: String, qualified: String, local: String, content: String) {
        val refs = references(namespace, local)
        if (refs.isEmpty()) description.setAttributeNS(namespace, qualified, content)
        else refs.forEach { (owner, attribute) -> if (attribute) owner.setAttributeNS(namespace, qualified, content) else owner.textContent = content }
    }

    private fun remove(namespace: String, local: String) {
        val refs = references(namespace, local)
        refs.forEach { (owner, attribute) -> if (attribute) owner.removeAttributeNS(namespace, local) else owner.parentNode.removeChild(owner) }
    }

    private fun updateKeywords(current: List<String>, desired: List<String>) {
        val currentKeys = current.associateBy(::keywordCasefold)
        val desiredKeys = desired.associateBy(::keywordCasefold)
        (currentKeys.keys - desiredKeys.keys).forEach { removeKeywordFromArray(DC, "subject", it); removeKeywordFromArray(LR, "hierarchicalSubject", it) }
        (desiredKeys.keys - currentKeys.keys).forEach { key ->
            val keyword = desiredKeys.getValue(key)
            if ('|' !in keyword) addKeywordToArray(DC, "dc:subject", keyword)
            addKeywordToArray(LR, "lr:hierarchicalSubject", keyword)
        }
    }

    private fun removeKeywordFromArray(namespace: String, local: String, folded: String) {
        val nodes = document.getElementsByTagNameNS(namespace, local)
        if (nodes.length == 0) return
        val properties = buildList { repeat(nodes.length) { add(nodes.item(it) as Element) } }
        properties.forEach { property ->
            val bag = property.childNodes.elements().singleOrNull() ?: throw XmpException("Keyword array must use rdf:Bag")
            bag.childNodes.elements().filter { runCatching { keywordCasefold(it.textContent) }.getOrNull() == folded }.forEach { bag.removeChild(it) }
            if (bag.childNodes.elements().isEmpty()) property.parentNode.removeChild(property)
        }
    }

    private fun addKeywordToArray(namespace: String, qualified: String, keyword: String) {
        val local = qualified.substringAfter(':')
        val nodes = document.getElementsByTagNameNS(namespace, local)
        val bags = if (nodes.length == 0) {
            val property = document.createElementNS(namespace, qualified)
            listOf(document.createElementNS(RDF, "rdf:Bag").also { property.appendChild(it); description.appendChild(property) })
        } else buildList { repeat(nodes.length) { index -> add((nodes.item(index) as Element).childNodes.elements().singleOrNull() ?: throw XmpException("Keyword array must use rdf:Bag")) } }
        bags.forEach { bag -> document.createElementNS(RDF, "rdf:li").also { it.textContent = keyword; bag.appendChild(it) } }
    }

    private fun removeArray(namespace: String, local: String) {
        val nodes = document.getElementsByTagNameNS(namespace, local)
        if (nodes.length > 1) throw XmpException("Duplicate managed keyword array")
        if (nodes.length == 1) nodes.item(0).parentNode.removeChild(nodes.item(0))
    }

    private fun addBag(namespace: String, qualified: String, values: List<String>) {
        val property = document.createElementNS(namespace, qualified)
        val bag = document.createElementNS(RDF, "rdf:Bag")
        values.forEach { value -> document.createElementNS(RDF, "rdf:li").also { it.textContent = value; bag.appendChild(it) } }
        property.appendChild(bag); description.appendChild(property)
    }
}

private fun org.w3c.dom.NodeList.elements(): List<Element> = buildList { repeat(length) { (item(it) as? Element)?.let(::add) } }
private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

internal fun isRecoveryArtifact(name: String, canonicalName: String): Boolean {
    val folded = caseFoldText(name)
    val authority = caseFoldText(canonicalName)
    return folded.startsWith(".$authority.previous.") || folded.startsWith(".$authority.conflict.")
}
