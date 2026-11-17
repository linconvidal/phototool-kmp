package br.com.lincon.phototool.desktop

import br.com.lincon.phototool.domain.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import javax.imageio.ImageIO

/** Isolated validation fixture. No scanner, sync command, or caller-supplied library/cache paths. */
internal fun createComposeWebFixture(): Pair<Path, Path> {
    val root = Files.createTempDirectory("phototool-compose-candidate-")
    val originals = Files.createDirectory(root.resolve("synthetic-originals"))
    val library = Files.createDirectory(root.resolve("library"))
    val cache = Files.createDirectory(root.resolve("cache"))
    val photos = (1..36).map { index ->
        val stem = "IMG_${1000 + index}"
        val name = "$stem.JPG"
        val width = if (index % 3 == 0) 600 else 1000
        val height = if (index % 3 == 0) 900 else 650
        val source = originals.resolve(name)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.paint = GradientPaint(0f, 0f, Color((40 + index * 13) % 255, (70 + index * 19) % 255, (100 + index * 23) % 255),
                width.toFloat(), height.toFloat(), Color((170 + index * 7) % 255, (120 + index * 11) % 255, (60 + index * 17) % 255))
            graphics.fillRect(0, 0, width, height)
            graphics.color = Color.WHITE
            graphics.drawOval(width / 4, height / 4, width / 2, height / 2)
            graphics.drawString("PhotoTool Compose fixture $index", 30, 50)
        } finally { graphics.dispose() }
        check(ImageIO.write(image, "jpeg", source.toFile()))
        val file = Files.copy(source, library.resolve(name))
        val attrs = Files.readAttributes(file, BasicFileAttributes::class.java)
        val identity = MediaIdentity(name, attrs.fileKey().toString(), attrs.size(), attrs.lastModifiedTime().toMillis())
        val authority = "$stem.xmp"
        val editorial = EditorialState(
            flag = Flag.entries[index % 3], rating = index % 6,
            label = ColorLabel.entries.getOrNull(index % 4),
            keywords = listOf("Rua") + (if (index % 2 == 0) listOf("São Paulo") else emptyList()) +
                (if (index % 3 == 0) listOf("personal") else emptyList()),
        )
        // Initial synthetic test input, copied before any application mutation.
        Files.writeString(originals.resolve(authority), """
            <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about=""
              xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:xmpDM="http://ns.adobe.com/xmp/1.0/DynamicMedia/" xmlns:dc="http://purl.org/dc/elements/1.1/"
              xmp:Rating="${editorial.rating}" xmp:Label="${editorial.label?.name?.lowercase()?.replaceFirstChar { it.uppercase() }.orEmpty()}" xmpDM:pick="${when (editorial.flag) { Flag.PICK -> 1; Flag.REJECT -> -1; else -> 0 }}">
              <dc:subject><rdf:Bag>${editorial.keywords.joinToString("") { "<rdf:li>$it</rdf:li>" }}</rdf:Bag></dc:subject>
            </rdf:Description></rdf:RDF></x:xmpmeta>
        """.trimIndent())
        Files.copy(originals.resolve(authority), library.resolve(authority))
        val id = MessageDigest.getInstance("SHA-256").digest(caseFoldText("$name\u0000$authority").toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        Photo(id, "", stem, authority, jpegPath = name, sourceIdentity = identity, previewIdentity = identity,
            previewPlan = PreviewPlan(PreviewStrategy.DIRECT_JPEG, 0, identity.size, width, height),
            metadata = ObservedMetadata(capturedAt = "2026-06-${(index % 28 + 1).toString().padStart(2, '0')}T14:20:00",
                camera = if (index % 2 == 0) "FUJIFILM X-Pro2" else "Canon EOS R5", lens = if (index % 2 == 0) "XF23mmF2" else "RF50mm",
                width = width, height = height, focalLength = 23.0, aperture = 2.0, exposureSeconds = .008, iso = 400,
                latitude = if (index % 2 == 0) -23.55 else null, longitude = if (index % 2 == 0) -46.63 else null,
                status = MetadataStatus.AVAILABLE), editorial = editorial)
    }
    PhotoCache(cache, library).publish(photos)
    return library to cache
}

internal data class ComposeCandidateOptions(val port: Int, val bindAddress: String, val writeEnabled: Boolean)

internal fun parseComposeCandidateOptions(arguments: Array<String>): ComposeCandidateOptions {
    val writeOption = "--enable-fixture-write"
    require(arguments.count { it == writeOption } <= 1)
    val positional = arguments.filterNot { it == writeOption }
    require(positional.size <= 2 && positional.none { it.startsWith("--") }) {
        "Usage: composeWebCandidate [port] [local-ipv4-address] [--enable-fixture-write]; no library/cache paths"
    }
    val port = positional.firstOrNull()?.toInt() ?: 8893
    require(port in 1024..65535)
    val bindAddress = positional.getOrNull(1) ?: "127.0.0.1"
    val address = java.net.InetAddress.getByName(bindAddress)
    require(address is java.net.Inet4Address && address.hostAddress == bindAddress &&
        !address.isAnyLocalAddress && java.net.NetworkInterface.getByInetAddress(address) != null) {
        "Bind to an explicit IPv4 address assigned to this machine, never a wildcard"
    }
    return ComposeCandidateOptions(port, bindAddress, writeOption in arguments)
}

/** Always creates its own synthetic copied fixture; opt-in never accepts a real library. */
object ComposeWebCandidate {
@JvmStatic fun main(arguments: Array<String>) {
    val (port, bindAddress, writeEnabled) = parseComposeCandidateOptions(arguments)
    val address = java.net.InetAddress.getByName(bindAddress)
    val (library, cache) = createComposeWebFixture()
    val runtime = CurationRuntime.open(library, cache, writeEnabled = writeEnabled)
    val host = "$bindAddress:$port"
    check(runtime.application.status().photoCount == 36)
    check(runtime.application.status().sync.phase == "IDLE")
    println("COMPOSE_CANDIDATE pid=${ProcessHandle.current().pid()} url=http://$host library=$library cache=$cache writeEnabled=$writeEnabled sync=IDLE")
    Runtime.getRuntime().addShutdownHook(Thread { runtime.close() })
    embeddedServer(Netty, host = bindAddress, port = port) {
        val allowedHosts = if (address.isLoopbackAddress) setOf(host, "localhost:$port") else setOf(host)
        mobileCurationModule(runtime.application, HttpSecurityConfiguration(allowedHosts))
    }.start(wait = true)
}
}
