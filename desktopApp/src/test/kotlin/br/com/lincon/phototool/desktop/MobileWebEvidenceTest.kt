package br.com.lincon.phototool.desktop

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MobileWebEvidenceTest {
    @Test fun renderFixtureGalleryAndDetailWhenExplicitlyRequested() {
        val libraryValue = System.getenv("PHOTOTOOL_WEB_EVIDENCE_LIBRARY")
        val outputValue = System.getenv("PHOTOTOOL_WEB_EVIDENCE_OUTPUT")
        assumeTrue("Mobile web evidence requires explicit fixture and output paths", libraryValue != null && outputValue != null)
        val sourceLibrary = Path.of(checkNotNull(libraryValue)).toRealPath()
        val output = Path.of(checkNotNull(outputValue)).toRealPath()
        require(Files.isDirectory(sourceLibrary) && Files.isDirectory(output))
        require(!output.startsWith(sourceLibrary) && !sourceLibrary.startsWith(output)) { "Evidence output and source fixture must be disjoint" }
        val sourceMedia = mediaFingerprints(sourceLibrary)
        val disposable = createTempDirectory("mobile-web-evidence-fixture-")
        val library = disposable.resolve("library")
        val cache = disposable.resolve("cache")
        copyFixture(sourceLibrary, library)
        Files.createDirectory(cache)

        val runtime = CurationRuntime.open(library, cache, writeEnabled = false)
        val sync = runtime.application.requestSync()
        assertTrue(sync.started)
        runtime.application.awaitIdle()
        assertEquals("COMPLETE", runtime.application.status().sync.phase)
        val fixturePage = runtime.application.page(PhotoQueryInput(pageSize = 24))
        fixturePage.items.forEach { runtime.application.preview(it.id, 900) }
        val port = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val host = "127.0.0.1:$port"
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            mobileCurationModule(runtime.application, HttpSecurityConfiguration(setOf(host), csrfToken = "evidence-read-only"))
        }
        val serverPid = ProcessHandle.current().pid()
        println("mobile-web-evidence-server pid=$serverPid bind=127.0.0.1:$port library=fixture cache=disposable")
        server.start(wait = false)
        try {
            val photoId = fixturePage.items.first().id.also { runtime.application.preview(it, 1600) }
            val renders = listOf(
                Render("phototool-kmp-mobile-web-gallery-390x844.png", 390, 844, "http://$host/"),
                Render("phototool-kmp-mobile-web-detail-412x915.png", 412, 915, "http://$host/?photo=$photoId"),
                Render("phototool-kmp-mobile-web-gallery-1440x1000.png", 1440, 1000, "http://$host/"),
            )
            renders.forEach { render -> capture(render, output) }
        } finally {
            server.stop(1_000, 5_000)
            runtime.close()
            assertEquals(sourceMedia, mediaFingerprints(sourceLibrary), "Evidence setup must not mutate its source fixture")
            deleteTree(disposable)
            println("mobile-web-evidence-server stopped pid=$serverPid")
        }
    }

    private fun copyFixture(source: Path, target: Path) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!attrs.isSymbolicLink && attrs.isDirectory)
                Files.createDirectory(target.resolve(source.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(attrs.isRegularFile && !attrs.isSymbolicLink && !Files.isSymbolicLink(file))
                Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun mediaFingerprints(root: Path): Map<String, String> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
            .toList().associate { path ->
                val digest = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(path, StandardOpenOption.READ).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                root.relativize(path).toString() to digest.digest().joinToString("") { "%02x".format(it) }
            }
    }

    private fun capture(render: Render, output: Path) {
        val target = output.resolve(render.name).normalize()
        require(target.parent == output && (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) ||
            Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target) &&
                runCatching { (Files.getAttribute(target, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1 }.getOrDefault(false))) { "Evidence target is unsafe" }
        val profile = createTempDirectory("chrome-mobile-evidence-")
        val captureScript = Path.of("..", "scripts", "capture-mobile-web.mjs").toAbsolutePath().normalize()
        require(Files.isRegularFile(captureScript))
        val process = ProcessBuilder(
            "node",
            captureScript.toString(),
            render.url,
            target.toString(),
            render.width.toString(),
            render.height.toString(),
            profile.toString(),
        ).inheritIO().start()
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroy()
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Chrome evidence process ${process.pid()} did not stop")
                error("Chrome evidence timed out")
            }
            assertEquals(0, process.exitValue())
            assertTrue(Files.size(target) > 20_000, "${render.name} must contain a judged render")
            val image = ImageIO.read(target.toFile())
            assertEquals(render.width, image.width)
            assertEquals(render.height, image.height)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            deleteTree(profile)
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!attrs.isSymbolicLink && !Files.isSymbolicLink(file))
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private data class Render(val name: String, val width: Int, val height: Int, val url: String)
}
