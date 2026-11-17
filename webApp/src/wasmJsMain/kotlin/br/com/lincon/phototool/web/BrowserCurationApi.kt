package br.com.lincon.phototool.web

import br.com.lincon.phototool.api.*
import br.com.lincon.phototool.domain.Query
import br.com.lincon.phototool.domain.EditorialCommand
import br.com.lincon.phototool.remote.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.plugins.expectSuccess
import io.ktor.http.*
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

internal fun browserHttpClient() = HttpClient(Js) {
    expectSuccess = true
    followRedirects = false
    install(HttpTimeout) { requestTimeoutMillis = 30_000 }
}

internal class BrowserCurationApi(private val client: HttpClient) : CurationEditorialApi {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun session(): SessionDto = json.decodeFromString(get("/api/v1/session"))
    override suspend fun status(): SnapshotStatusDto = json.decodeFromString(get("/api/v1/status"))
    override suspend fun page(query: Query, page: Int): PhotoPageDto =
        json.decodeFromString(get("/api/v1/photos", query.httpParameters(page)))
    override suspend fun detail(query: Query, id: String): PhotoDetailDto =
        json.decodeFromString(get("/api/v1/photos/${requirePhotoId(id)}", query.httpParameters()))

    override suspend fun mutate(id: String, revision: String, command: EditorialCommand, csrfToken: String): MutationResultDto {
        val (field, body) = when (command) {
            is EditorialCommand.SetFlag -> "flag" to json.encodeToString(FlagMutationRequest(command.value, revision))
            is EditorialCommand.SetRating -> "rating" to json.encodeToString(RatingMutationRequest(command.value, revision))
            is EditorialCommand.SetLabel -> "label" to json.encodeToString(LabelMutationRequest(command.value, revision))
            is EditorialCommand.AddKeyword -> "keywords" to json.encodeToString(KeywordMutationRequest(command.value, revision))
            is EditorialCommand.RemoveKeyword -> "keywords" to json.encodeToString(KeywordMutationRequest(command.value, revision))
        }
        val response = client.request("/api/v1/photos/${requirePhotoId(id)}/$field") {
            method = if (command is EditorialCommand.RemoveKeyword) HttpMethod.Delete else HttpMethod.Post
            expectSuccess = false
            contentType(ContentType.Application.Json)
            header("X-PhotoTool-CSRF", csrfToken)
            setBody(body)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw EditorialRequestFailed(
            runCatching { json.decodeFromString<ApiErrorDto>(text) }.getOrElse { ApiErrorDto("unconfirmed", "Unconfirmed response") })
        return json.decodeFromString(text)
    }

    private suspend fun get(path: String, parameters: Map<String, String> = emptyMap()): String =
        client.get(path) { url { parameters.forEach { (key, value) -> this.parameters.append(key, value) } } }.bodyAsText()
}
