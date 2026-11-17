package br.com.lincon.phototool.desktop

import br.com.lincon.phototool.api.*

import br.com.lincon.phototool.domain.ColorLabel
import br.com.lincon.phototool.domain.EditorialCommand
import br.com.lincon.phototool.domain.Flag
import br.com.lincon.phototool.domain.GpsFilter
import br.com.lincon.phototool.domain.PhotoSort
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.http.content.staticResources
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val MAX_MUTATION_BODY_BYTES = 4096
private const val CSRF_HEADER = "X-PhotoTool-CSRF"

internal data class HttpSecurityConfiguration(
    val allowedHosts: Set<String>,
    val scheme: String = "http",
    val csrfToken: String = secureToken(),
) {
    init {
        require(allowedHosts.isNotEmpty() && allowedHosts.all { it.isNotBlank() && '/' !in it && '\\' !in it })
        require(scheme in setOf("http", "https"))
    }

    val normalizedHosts = allowedHosts.mapTo(linkedSetOf()) { it.lowercase() }
}

internal fun Application.mobileCurationModule(
    curation: CurationApplicationService,
    security: HttpSecurityConfiguration,
) {
    val jsonCodec = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }
    install(ContentNegotiation) { json(jsonCodec) }
    install(StatusPages) {
        exception<InvalidQueryException> { call, error -> call.respondError(HttpStatusCode.BadRequest, "invalid-query", error.message ?: "Invalid query") }
        exception<MutationBodyTooLargeException> { call, _ -> call.respondError(HttpStatusCode.PayloadTooLarge, "body-too-large", "Mutation body is too large") }
        exception<SerializationException> { call, _ -> call.respondError(HttpStatusCode.BadRequest, "invalid-json", "Request body does not match the contract") }
        exception<PhotoNotFoundException> { call, _ -> call.respondError(HttpStatusCode.NotFound, "photo-not-found", "Photo is absent from the active snapshot") }
        exception<WriteDisabledException> { call, _ -> call.respondError(HttpStatusCode.Forbidden, "write-disabled", "Write mode is disabled") }
        exception<ProjectionDegradedException> { call, _ -> call.respondError(HttpStatusCode.ServiceUnavailable, "projection-degraded", "The projection requires explicit repair before another mutation") }
        exception<ProjectionBusyException> { call, _ -> call.respondError(HttpStatusCode.Conflict, "projection-busy", "Another local process is updating the editorial projection") }
        exception<SyncInProgressException> { call, _ -> call.respondError(HttpStatusCode.Conflict, "sync-in-progress", "Wait for synchronization to finish") }
        exception<EditorialRevisionConflictException> { call, error ->
            call.respond(HttpStatusCode.Conflict, ApiErrorDto("editorial-revision-conflict", "Editorial state changed; review the authoritative readback", editorial = error.authoritative))
        }
        exception<PartialProjectionException> { call, error ->
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ApiErrorDto(
                    code = "projection-update-failed",
                    message = "XMP was saved, but the durable projection failed and further mutations are blocked",
                    xmpPersisted = true,
                    projectionUpdated = false,
                    editorial = error.result.editorial,
                ),
            )
        }
        exception<XmpException> { call, _ -> call.respondError(HttpStatusCode.UnprocessableEntity, "xmp-write-failed", "XMP could not be safely read or written") }
        exception<PreviewException> { call, error ->
            when (error.failure) {
                PreviewFailure.STALE_SOURCE -> call.respondError(HttpStatusCode.Conflict, "stale-preview", "Preview media changed after indexing")
                PreviewFailure.UNSUPPORTED_MEDIA -> call.respondError(HttpStatusCode.UnsupportedMediaType, "preview-unsupported", "Preview media is malformed or unsupported")
                PreviewFailure.UNSAFE_SOURCE, PreviewFailure.UNSAFE_CACHE -> call.respondError(HttpStatusCode.UnprocessableEntity, "preview-unsafe", "Preview could not be processed within the safety boundary")
                PreviewFailure.UNAVAILABLE -> call.respondError(HttpStatusCode.ServiceUnavailable, "preview-unavailable", "Preview is temporarily unavailable")
            }
        }
        exception<IllegalArgumentException> { call, error -> call.respondError(HttpStatusCode.BadRequest, "invalid-input", error.message?.take(200) ?: "Invalid input") }
        exception<Throwable> { call, _ -> call.respondError(HttpStatusCode.InternalServerError, "internal-error", "The request failed without exposing filesystem details") }
    }

    intercept(ApplicationCallPipeline.Plugins) {
        val currentCall = context
        if (currentCall.request.path().startsWith("/api/v1/") && !currentCall.request.path().endsWith("/preview")) {
            currentCall.response.cacheControl(CacheControl.NoStore(null))
        }
        // Compose/Skiko compiles Wasm and positions accessibility/input elements with inline styles.
        // No general JS eval, external scripts, or external API/image origins are allowed.
        currentCall.response.header("Content-Security-Policy", "default-src 'self'; img-src 'self' data: blob:; font-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'wasm-unsafe-eval'; connect-src 'self'; worker-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        currentCall.response.header("X-Content-Type-Options", "nosniff")
        currentCall.response.header("Referrer-Policy", "no-referrer")
        currentCall.response.header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        currentCall.response.header("Cross-Origin-Resource-Policy", "same-origin")
        val host = currentCall.request.header(HttpHeaders.Host)?.trim()?.lowercase()
        if (host == null || host !in security.normalizedHosts) {
            currentCall.respondError(HttpStatusCode(421, "Misdirected Request"), "host-invalid", "Host is not allowed")
            finish()
        }
    }

    routing {
        get("/") {
            call.response.cacheControl(CacheControl.NoStore(null))
            val bytes = checkNotNull(javaClass.classLoader.getResourceAsStream("compose-web/index.html")) { "Compose Web distribution is missing; run :webApp:wasmJsBrowserDistribution" }.use { it.readBytes() }
            call.respondBytes(bytes, ContentType.Text.Html)
        }
        staticResources("/", "compose-web")

        route("/api/v1") {
            get("/session") { call.respond(SessionDto(security.csrfToken, curation.status().writeEnabled)) }
            get("/status") { call.respond(curation.status()) }
            get("/facets") { call.respond(curation.facets()) }
            get("/photos") { call.respond(curation.page(call.photoQueryInput())) }
            get("/photos/{photoId}") {
                val id = call.parameters["photoId"] ?: throw PhotoNotFoundException()
                call.respond(curation.detail(call.photoQueryInput(), id))
            }
            get("/photos/{photoId}/preview") {
                val id = call.parameters["photoId"] ?: throw PhotoNotFoundException()
                val maximum = call.request.queryParameters["max"]?.toIntOrNull() ?: 900
                val payload = curation.preview(id, maximum)
                val etag = "\"${payload.etag}\""
                call.response.header(HttpHeaders.ETag, etag)
                call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 86_400, visibility = CacheControl.Visibility.Private))
                if (call.request.header(HttpHeaders.IfNoneMatch)?.split(',')?.map(String::trim)?.contains(etag) == true) {
                    call.respond(HttpStatusCode.NotModified)
                } else {
                    call.respondBytes(payload.bytes, ContentType.Image.JPEG)
                }
            }
            get("/photos/{photoId}/original") {
                val payload = curation.originalJpeg(call.photoId())
                val etag = "\"${payload.etag}\""
                call.response.header(HttpHeaders.ETag, etag)
                call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 86_400, visibility = CacheControl.Visibility.Private))
                if (call.request.header(HttpHeaders.IfNoneMatch)?.split(',')?.map(String::trim)?.contains(etag) == true) {
                    call.respond(HttpStatusCode.NotModified)
                } else {
                    call.respondBytes(payload.bytes, ContentType.Image.JPEG)
                }
            }
            get("/photos/{photoId}/region") {
                fun int(name: String): Int = call.request.queryParameters[name]?.toIntOrNull()
                    ?: throw InvalidQueryException("$name must be an integer")
                val payload = curation.region(call.photoId(), int("x"), int("y"), int("w"), int("h"))
                val etag = "\"${payload.etag}\""
                call.response.header(HttpHeaders.ETag, etag)
                call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 86_400, visibility = CacheControl.Visibility.Private))
                if (call.request.header(HttpHeaders.IfNoneMatch)?.split(',')?.map(String::trim)?.contains(etag) == true) {
                    call.respond(HttpStatusCode.NotModified)
                } else {
                    call.respondBytes(payload.bytes, ContentType.Image.JPEG)
                }
            }
            post("/sync") {
                if (!call.verifyMutationRequest(security, requireJson = false)) return@post
                if (call.receiveBoundedBytes().isNotEmpty()) throw InvalidQueryException("Synchronization request body must be empty")
                call.respond(HttpStatusCode.Accepted, curation.requestSync())
            }
            get("/sync") { call.respond(curation.status().sync) }
            post("/photos/{photoId}/flag") {
                if (!call.verifyMutationRequest(security)) return@post
                val request = call.receiveBounded<FlagMutationRequest>(jsonCodec)
                call.respond(curation.mutate(call.photoId(), request.expectedEditorialRevision, EditorialCommand.SetFlag(request.value)))
            }
            post("/photos/{photoId}/rating") {
                if (!call.verifyMutationRequest(security)) return@post
                val request = call.receiveBounded<RatingMutationRequest>(jsonCodec)
                call.respond(curation.mutate(call.photoId(), request.expectedEditorialRevision, EditorialCommand.SetRating(request.value)))
            }
            post("/photos/{photoId}/label") {
                if (!call.verifyMutationRequest(security)) return@post
                val request = call.receiveBounded<LabelMutationRequest>(jsonCodec)
                call.respond(curation.mutate(call.photoId(), request.expectedEditorialRevision, EditorialCommand.SetLabel(request.value)))
            }
            post("/photos/{photoId}/keywords") {
                if (!call.verifyMutationRequest(security)) return@post
                val request = call.receiveBounded<KeywordMutationRequest>(jsonCodec)
                call.respond(curation.mutate(call.photoId(), request.expectedEditorialRevision, EditorialCommand.AddKeyword(request.keyword)))
            }
            delete("/photos/{photoId}/keywords") {
                if (!call.verifyMutationRequest(security)) return@delete
                val request = call.receiveBounded<KeywordMutationRequest>(jsonCodec)
                call.respond(curation.mutate(call.photoId(), request.expectedEditorialRevision, EditorialCommand.RemoveKeyword(request.keyword)))
            }
        }
    }
}

private suspend fun ApplicationCall.verifyMutationRequest(
    security: HttpSecurityConfiguration,
    requireJson: Boolean = true,
): Boolean {
    val host = request.header(HttpHeaders.Host)?.trim()?.lowercase() ?: return false
    val origin = request.header(HttpHeaders.Origin)
    if (!sameOrigin(origin, security.scheme, host)) {
        respondError(HttpStatusCode.Forbidden, "origin-invalid", "Origin must match Host")
        return false
    }
    if (request.header("Sec-Fetch-Site")?.lowercase() !in setOf(null, "same-origin")) {
        respondError(HttpStatusCode.Forbidden, "origin-invalid", "Cross-site mutation is forbidden")
        return false
    }
    val supplied = request.header(CSRF_HEADER)
    if (supplied == null || !constantTimeEquals(supplied, security.csrfToken)) {
        respondError(HttpStatusCode.Forbidden, "csrf-invalid", "CSRF token is invalid")
        return false
    }
    if (requireJson && request.headers[HttpHeaders.ContentType]?.let(ContentType::parse)?.withoutParameters() != ContentType.Application.Json) {
        respondError(HttpStatusCode.UnsupportedMediaType, "content-type-invalid", "Mutation body must be JSON")
        return false
    }
    val length = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    if (length != null && length !in 0..MAX_MUTATION_BODY_BYTES.toLong()) {
        respondError(HttpStatusCode.PayloadTooLarge, "body-too-large", "Mutation body is too large")
        return false
    }
    return true
}

private class MutationBodyTooLargeException : IllegalArgumentException("Mutation body is too large")

private inline suspend fun <reified T> ApplicationCall.receiveBounded(json: Json): T =
    json.decodeFromString(receiveBoundedBytes().toString(StandardCharsets.UTF_8))

private suspend fun ApplicationCall.receiveBoundedBytes(): ByteArray {
    val channel = receiveChannel()
    val output = ByteArrayOutputStream(MAX_MUTATION_BODY_BYTES)
    val buffer = ByteArray(1024)
    var total = 0
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > MAX_MUTATION_BODY_BYTES) throw MutationBodyTooLargeException()
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun ApplicationCall.photoId(): String = parameters["photoId"] ?: throw PhotoNotFoundException()

private fun ApplicationCall.photoQueryInput(): PhotoQueryInput {
    fun value(name: String): String? = request.queryParameters[name]?.trim()?.takeIf(String::isNotEmpty)
    fun integer(name: String, default: Int): Int = value(name)?.toIntOrNull() ?: if (value(name) == null) default else throw InvalidQueryException("$name must be an integer")
    fun <T : Enum<T>> enum(name: String, default: T? = null, values: Array<T>): T? {
        val raw = value(name) ?: return default
        return values.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: throw InvalidQueryException("$name is invalid")
    }
    return PhotoQueryInput(
        search = value("q").orEmpty(),
        keyword = value("keyword"),
        fromDate = value("fromDate"),
        toDate = value("toDate"),
        flag = enum("flag", values = Flag.entries.toTypedArray()),
        camera = value("camera"),
        lens = value("lens"),
        minimumStars = integer("minimumStars", 0),
        gps = enum("gps", GpsFilter.ANY, GpsFilter.entries.toTypedArray())!!,
        folderId = value("folder"),
        sort = enum("sort", PhotoSort.CAPTURE_TIME, PhotoSort.entries.toTypedArray())!!,
        page = integer("page", 1),
        pageSize = integer("pageSize", DEFAULT_PAGE_SIZE),
    )
}

private fun sameOrigin(origin: String?, scheme: String, host: String): Boolean {
    val parsed = runCatching { URI(origin ?: return false) }.getOrNull() ?: return false
    return parsed.scheme.equals(scheme, ignoreCase = true) &&
        parsed.rawAuthority?.lowercase() == host.lowercase() &&
        parsed.rawUserInfo == null && parsed.rawQuery == null && parsed.rawFragment == null && parsed.rawPath.orEmpty().isEmpty()
}

private fun constantTimeEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
    left.toByteArray(StandardCharsets.UTF_8),
    right.toByteArray(StandardCharsets.UTF_8),
)

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String) =
    respond(status, ApiErrorDto(code, message))

private fun secureToken(): String = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

internal data class WebLaunchOptions(
    val library: Path,
    val cache: Path,
    val host: String,
    val port: Int,
    val writeEnabled: Boolean,
    val allowedHosts: Set<String>,
)

internal fun runWebServer(arguments: Array<String>): Int {
    val options = parseWebArguments(arguments)
    val runtime = CurationRuntime.open(options.library, options.cache, options.writeEnabled)
    return try {
        embeddedServer(Netty, host = options.host, port = options.port) {
            mobileCurationModule(runtime.application, HttpSecurityConfiguration(options.allowedHosts))
        }.start(wait = true)
        0
    } finally {
        runtime.close()
    }
}

internal fun parseWebArguments(arguments: Array<String>): WebLaunchOptions {
    var library: Path? = null
    var cache: Path? = null
    var host = "127.0.0.1"
    var port = 8765
    var writeEnabled = false
    val allowedHosts = linkedSetOf<String>()
    var index = 0
    while (index < arguments.size) when (val argument = arguments[index++]) {
        "--library" -> library = Path.of(arguments.getOrNull(index++) ?: error("--library requires a path"))
        "--cache" -> cache = Path.of(arguments.getOrNull(index++) ?: error("--cache requires a path"))
        "--host" -> host = arguments.getOrNull(index++) ?: error("--host requires a value")
        "--port" -> port = arguments.getOrNull(index++)?.toIntOrNull() ?: error("--port requires an integer")
        "--allowed-host" -> allowedHosts += arguments.getOrNull(index++) ?: error("--allowed-host requires a value")
        "--enable-write" -> writeEnabled = true
        "--read-only" -> writeEnabled = false
        else -> error("Unknown web argument: $argument")
    }
    require(host in setOf("127.0.0.1", "::1", "localhost")) { "The first cut binds only to loopback" }
    require(port in 1024..65535) { "Port is outside the supported range" }
    val selectedLibrary = library ?: error("--library is required")
    val selectedCache = cache ?: error("--cache is required")
    if (allowedHosts.isEmpty()) {
        allowedHosts += "127.0.0.1:$port"
        allowedHosts += "localhost:$port"
        if (host == "::1") allowedHosts += "[::1]:$port"
    }
    return WebLaunchOptions(selectedLibrary, selectedCache, host, port, writeEnabled, allowedHosts)
}
