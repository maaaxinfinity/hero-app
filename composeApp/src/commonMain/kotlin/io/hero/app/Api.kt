package io.hero.app

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

// Api is a thin client over the v1 control-plane API. The engine (CIO on
// desktop/Android, Darwin on iOS) is picked up from the platform source set's
// classpath, so common code just constructs HttpClient { }.
class Api(private val baseUrl: String) {
    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpCookies) // keeps the session cookie set by /login
    }

    /** login posts the form; success is a 2xx or the 303 redirect the server sends. */
    suspend fun login(user: String, password: String): Boolean {
        val resp = client.submitForm(
            url = "$baseUrl/login",
            formParameters = parameters {
                append("user", user)
                append("password", password)
            },
        )
        return resp.status.isSuccess() || resp.status.value == 303
    }

    suspend fun nodes(): List<NodeView> =
        client.get("$baseUrl/api/nodes").body()

    suspend fun sessions(node: String): List<Session> =
        client.get("$baseUrl/api/nodes/${node.enc()}/sessions").body()

    suspend fun send(node: String, id: String, text: String) {
        client.post("$baseUrl/api/nodes/${node.enc()}/sessions/${id.enc()}/send") {
            contentType(ContentType.Application.Json)
            setBody(SendReq(text))
        }
    }

    /** events streams a session's SSE feed, parsing `data:` frames into Events. */
    fun events(node: String): Flow<Event> = flow {
        client.prepareGet("$baseUrl/api/nodes/${node.enc()}/events").execute { resp ->
            val ch = resp.bodyAsChannel()
            while (true) {
                val line = ch.readUTF8Line() ?: break
                if (line.startsWith("data:")) {
                    val data = line.removePrefix("data:").trim()
                    if (data.isNotEmpty()) {
                        emit(json.decodeFromString(Event.serializer(), data))
                    }
                }
            }
        }
    }
}

private fun String.enc(): String = this.encodeURLPathPart()
