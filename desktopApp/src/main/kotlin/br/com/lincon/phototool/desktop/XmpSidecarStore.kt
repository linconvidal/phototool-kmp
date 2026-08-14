package br.com.lincon.phototool.desktop

import br.com.lincon.phototool.domain.*
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
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

/**
 * Adjacent sidecar store. Java NIO has no portable rename-exchange primitive, so
 * replacement uses a conservative same-directory fallback: fsync a sibling temp,
 * atomically displace the expected original to a unique previous name, compare the
 * displaced bytes again, and install with no-replace. A raced displacement is copied
 * to a unique conflict artifact and restored as canonical before failing closed.
 */
class XmpSidecarStore(private val root: Path, private val writeEnabled: Boolean) : AutoCloseable {
    private val locks = ConcurrentHashMap<String, Any>()
    private val realRoot = root.toRealPath()
    private val secure = if (writeEnabled) SecureLibraryBoundary(realRoot, true) else null

    fun read(photo: Photo): EditorialState {
        validatePinnedMedia(photo)
        val sidecar = sidecar(photo)
        validateReadableTopology(photo, sidecar)
        if (!Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) return EditorialState()
        return parse(readBounded(sidecar)).state()
    }

    fun readDevelop(photo: Photo): DevelopSettings {
        validatePinnedMedia(photo)
        val sidecar = sidecar(photo)
        validateReadableTopology(photo, sidecar)
        if (!Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) return DevelopSettings()
        return parse(readBounded(sidecar)).developState()
    }

    fun mutate(photo: Photo, desired: EditorialState): EditorialState {
        val normalized = desired.copy(keywords = desired.keywords.map(::normalizeKeyword).distinctBy(::keywordCasefold))
        require(normalized.rating in 0..5 && normalized.keywords.size <= 256)
        return mutateDocument(photo) { document ->
            val current = document.state()
            if (current == normalized) false else { document.applyEditorial(current, normalized); true }
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

    fun mutateDevelopProperties(photo: Photo, updates: Map<String, String?>): DevelopSettings = mutateProperties(photo, updates).developState()

    private fun mutateProperties(photo: Photo, updates: Map<String, String?>): XmpDocument {
        require(updates.isNotEmpty() && updates.keys.all { it in DEVELOP_FIELDS })
        return mutateDocument(photo) { document -> document.applyDevelop(updates) }
    }

    private fun mutateDocument(photo: Photo, update: (XmpDocument) -> Boolean): XmpDocument {
        check(writeEnabled) { "Launch with --enable-write to permit XMP changes" }
        check(photo.writable) { photo.issue ?: "Ambiguous photo is not writable" }
        val sidecar = sidecar(photo)
        return synchronized(locks.computeIfAbsent(photo.id) { Any() }) {
            validateTopology(photo, sidecar)
            val relative = photo.authorityPath
            val existed = secure!!.exists(relative)
            val original = if (existed) secure.read(relative, MAX_XMP_BYTES) else baseXmp()
            val originalDigest = digest(original)
            val document = parse(original)
            document.state()
            document.developState()
            if (!update(document)) return@synchronized document
            val updated = serialize(document)
            check(updated.size in 1..MAX_XMP_BYTES)
            // Parse and validate every managed property before touching the directory.
            val expected = parse(updated)
            expected.state()
            expected.developState()
            validateTopology(photo, sidecar)
            if (existed && digest(secure.read(relative, MAX_XMP_BYTES)) != originalDigest) throw XmpException("XMP changed concurrently")
            if (!existed && secure.exists(relative)) throw XmpException("XMP appeared concurrently")
            secure.publish(relative, updated, if (existed) original else null, "xmp")
            val installed = parse(secure.read(relative, MAX_XMP_BYTES))
            installed.state()
            installed.developState()
            installed
        }
    }

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
        val attrs = secure?.attributes(expected.path) ?: run {
            val authority = realRoot.resolve(expected.path).normalize()
            if (!authority.startsWith(realRoot) || !Files.isRegularFile(authority, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(authority)) throw XmpException("Media authority changed")
            Files.readAttributes(authority, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }
        val key = attrs.fileKey()?.toString() ?: throw XmpException("Stable media identity unavailable")
        if (!attrs.isRegularFile || key != expected.fileKey || attrs.size() != expected.size || attrs.lastModifiedTime().toMillis() != expected.modifiedMillis) throw XmpException("Media identity is stale")
    }

    private fun validateTopology(photo: Photo, sidecar: Path) {
        val boundary = secure ?: throw XmpException("Secure write boundary unavailable")
        validatePinnedMedia(photo)
        val authorityRelative = photo.rawPath ?: photo.jpegPath ?: throw XmpException("Missing media authority")
        val authorityName = Path.of(authorityRelative).fileName.toString()
        val stem = authorityName.substringBeforeLast('.').lowercase()
        val parent = Path.of(authorityRelative).parent?.toString()?.replace('\\', '/') ?: ""
        val entries = boundary.list(parent)
        val entryNames = entries.map { it.name }
        val regularNames = entries.filter { it.attributes.isRegularFile }.map { it.name }
        val raws = regularNames.filter { classifyMedia(it) == MediaKind.RAW && it.substringBeforeLast('.').lowercase() == stem }.toSet()
        val jpegs = regularNames.filter { classifyMedia(it) == MediaKind.JPEG && it.substringBeforeLast('.').lowercase() == stem }.toSet()
        val indexedRaws = setOfNotNull(photo.rawPath?.let { Path.of(it).fileName.toString() })
        val indexedJpegs = setOfNotNull(photo.jpegPath?.let { Path.of(it).fileName.toString() })
        if (raws != indexedRaws || jpegs != indexedJpegs) throw XmpException("Media topology changed")
        val canonical = regularNames.filter { it.substringBeforeLast('.', it).lowercase() == stem && it.substringAfterLast('.', "").equals("xmp", true) }
        val legacy = regularNames.filter { name ->
            val inner = name.substringBeforeLast('.', "")
            classifyMedia(inner) != null && inner.substringBeforeLast('.').lowercase() == stem && name.substringAfterLast('.', "").equals("xmp", true)
        }
        val expectedName = Path.of(photo.authorityPath).fileName.toString()
        if (canonical.size > 1 || legacy.isNotEmpty() || (canonical.isNotEmpty() && canonical.single() != expectedName)) throw XmpException("XMP authority is ambiguous")
        if (canonical.isEmpty() && entryNames.any { isRecoveryArtifact(it, expectedName) }) throw XmpException("XMP recovery artifact requires manual recovery")
        boundary.attributes(photo.authorityPath)?.let {
            if (!it.isRegularFile || hardLinkCount(sidecar) != 1) throw XmpException("Unsafe XMP sidecar")
        }
    }

    private fun validateReadableTopology(photo: Photo, sidecar: Path) {
        val expectedName = Path.of(photo.authorityPath).fileName.toString()
        val stem = expectedName.substringBeforeLast('.').lowercase()
        val entries = Files.list(sidecar.parent).use { it.toList() }
        val entryNames = entries.map { it.fileName.toString() }
        val regularNames = entries.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }.map { it.fileName.toString() }
        val canonical = regularNames.filter { it.substringBeforeLast('.', it).lowercase() == stem && it.substringAfterLast('.', "").equals("xmp", true) }
        val legacy = regularNames.filter { name ->
            val inner = name.substringBeforeLast('.', "")
            classifyMedia(inner) != null && inner.substringBeforeLast('.').lowercase() == stem && name.substringAfterLast('.', "").equals("xmp", true)
        }
        if (canonical.size > 1 || legacy.isNotEmpty() || (canonical.isNotEmpty() && canonical.single() != expectedName)) throw XmpException("XMP authority is ambiguous")
        if (canonical.isEmpty() && entryNames.any { isRecoveryArtifact(it, expectedName) }) throw XmpException("XMP recovery artifact requires manual recovery")
    }

    private fun hardLinkCount(path: Path): Int = runCatching { (Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() }.getOrDefault(1)
    private fun forceDirectory(path: Path) { runCatching { FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) } } }

    private fun readBounded(path: Path): ByteArray {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path) || hardLinkCount(path) != 1) throw XmpException("Unsafe XMP sidecar")
        val size = Files.size(path)
        if (size <= 0 || size > MAX_XMP_BYTES) throw XmpException("Malformed or oversized XMP")
        return Files.readAllBytes(path).also { bytes ->
            val utf16 = bytes.startsWithBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) || bytes.startsWithBytes(byteArrayOf(0xFE.toByte(), 0xFF.toByte()))
            if (!utf16 && bytes.any { byte -> byte == 0.toByte() }) throw XmpException("XMP contains NUL bytes")
        }
    }

    override fun close() { secure?.close() }

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
            val newline = if ("\r\n" in probe) "\r\n" else "\n"
            return XmlStyle(charset, encoding ?: charset.name(), declaration, bom, newline)
        }
    }
}

private class XmpDocument(val document: Document, val style: XmlStyle) {
    private val description: Element get() {
        val descriptions = document.getElementsByTagNameNS(RDF, "Description")
        if (descriptions.length != 1) throw XmpException("XMP must contain exactly one rdf:Description")
        return descriptions.item(0) as Element
    }

    private fun references(namespace: String, local: String): List<Pair<Element, Boolean>> = buildList {
        if (description.hasAttributeNS(namespace, local)) add(description to true)
        val elements = document.getElementsByTagNameNS(namespace, local)
        repeat(elements.length) { add(elements.item(it) as Element to false) }
    }

    private fun value(namespace: String, local: String): String? {
        val refs = references(namespace, local)
        if (refs.size > 1) throw XmpException("Duplicate managed XMP $local values")
        return refs.singleOrNull()?.let { (owner, attribute) -> if (attribute) owner.getAttributeNS(namespace, local) else owner.textContent.orEmpty() }
    }

    fun state(): EditorialState {
        val flag = when (value(XMP_DM, "pick") to value(XMP_DM, "good")) { (null to null), ("0" to null) -> Flag.UNFLAGGED; "1" to "True" -> Flag.PICK; "-1" to "False" -> Flag.REJECT; else -> throw XmpException("Unsupported flag mapping") }
        val ratingRaw = value(XMP, "Rating")
        val rating = ratingRaw?.toIntOrNull() ?: if (ratingRaw == null) 0 else throw XmpException("Malformed rating")
        if (rating !in 0..5) throw XmpException("Unsupported rating")
        val label = when (value(XMP, "Label")) { null -> null; "Red" -> ColorLabel.RED; "Yellow" -> ColorLabel.YELLOW; "Green" -> ColorLabel.GREEN; else -> throw XmpException("Unsupported label") }
        return EditorialState(flag, rating, label, keywordValues())
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
            val seenInArray = mutableSetOf<String>()
            val parents = document.getElementsByTagNameNS(namespace, local)
            if (parents.length > 1) throw XmpException("Ambiguous keyword array")
            if (parents.length == 1) {
                val element = parents.item(0) as Element
                val children = element.childNodes.elements()
                if (children.size != 1 || children.single().namespaceURI != RDF || children.single().localName != "Bag") throw XmpException("Keyword array must use rdf:Bag")
                val items = children.single().childNodes.elements()
                if (items.size > 256 || items.any { it.namespaceURI != RDF || it.localName != "li" || it.attributes.length != 0 || it.childNodes.elements().isNotEmpty() }) throw XmpException("Unsupported keyword items")
                items.forEach { item ->
                    val normalized = normalizeKeyword(item.textContent)
                    val key = keywordCasefold(normalized)
                    if (!seenInArray.add(key)) throw XmpException("Duplicate managed keyword")
                    if (observed.add(key)) output += normalized
                }
            }
        }
        return output
    }

    fun applyEditorial(current: EditorialState, desired: EditorialState) {
        if (current.flag != desired.flag) when (desired.flag) { Flag.PICK -> { set(XMP_DM, "xmpDM:pick", "pick", "1"); set(XMP_DM, "xmpDM:good", "good", "True") }; Flag.UNFLAGGED -> { set(XMP_DM, "xmpDM:pick", "pick", "0"); remove(XMP_DM, "good") }; Flag.REJECT -> { set(XMP_DM, "xmpDM:pick", "pick", "-1"); set(XMP_DM, "xmpDM:good", "good", "False") } }
        if (current.rating != desired.rating) set(XMP, "xmp:Rating", "Rating", desired.rating.toString())
        if (current.label != desired.label) {
            val label = desired.label
            if (label == null) remove(XMP, "Label") else set(XMP, "xmp:Label", "Label", label.name.lowercase().replaceFirstChar { it.uppercase() })
        }
        if (current.keywords != desired.keywords) updateKeywords(current.keywords, desired.keywords)
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
        if (refs.size > 1) throw XmpException("Duplicate managed XMP $local values")
        if (refs.isEmpty()) description.setAttributeNS(namespace, qualified, content)
        else refs.single().let { (owner, attribute) -> if (attribute) owner.setAttributeNS(namespace, qualified, content) else owner.textContent = content }
    }

    private fun remove(namespace: String, local: String) {
        val refs = references(namespace, local)
        if (refs.size > 1) throw XmpException("Duplicate managed XMP $local values")
        refs.singleOrNull()?.let { (owner, attribute) -> if (attribute) owner.removeAttributeNS(namespace, local) else owner.parentNode.removeChild(owner) }
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
        if (nodes.length > 1) throw XmpException("Duplicate managed keyword array")
        if (nodes.length == 0) return
        val property = nodes.item(0) as Element
        val bag = property.childNodes.elements().singleOrNull() ?: throw XmpException("Keyword array must use rdf:Bag")
        bag.childNodes.elements().filter { keywordCasefold(it.textContent) == folded }.forEach { bag.removeChild(it) }
        if (bag.childNodes.elements().isEmpty()) property.parentNode.removeChild(property)
    }

    private fun addKeywordToArray(namespace: String, qualified: String, keyword: String) {
        val local = qualified.substringAfter(':')
        val nodes = document.getElementsByTagNameNS(namespace, local)
        if (nodes.length > 1) throw XmpException("Duplicate managed keyword array")
        val bag = if (nodes.length == 0) {
            val property = document.createElementNS(namespace, qualified)
            document.createElementNS(RDF, "rdf:Bag").also { property.appendChild(it); description.appendChild(property) }
        } else (nodes.item(0) as Element).childNodes.elements().singleOrNull() ?: throw XmpException("Keyword array must use rdf:Bag")
        document.createElementNS(RDF, "rdf:li").also { it.textContent = keyword; bag.appendChild(it) }
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
    val folded = name.lowercase()
    val authority = canonicalName.lowercase()
    return folded.startsWith(".$authority.previous.") || folded.startsWith(".$authority.conflict.")
}
