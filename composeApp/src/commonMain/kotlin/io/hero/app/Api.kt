package io.hero.app

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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

    suspend fun me(): Me = client.get("$baseUrl/api/me").body()

    // ---- sessions ----
    suspend fun startSession(node: String, req: StartSessionReq): String {
        val resp = client.post("$baseUrl/api/nodes/${node.enc()}/sessions") {
            contentType(ContentType.Application.Json); setBody(req)
        }
        return runCatching { resp.body<Map<String, String>>()["id"] ?: "" }.getOrDefault("")
    }

    suspend fun pending(node: String): List<Pending> =
        client.get("$baseUrl/api/nodes/${node.enc()}/pending").body()

    suspend fun respond(node: String, id: String, behavior: String) {
        client.post("$baseUrl/api/nodes/${node.enc()}/pending/${id.enc()}/respond") {
            contentType(ContentType.Application.Json); setBody(RespondReq(behavior))
        }
    }

    // ---- node management ----
    suspend fun nodeAccess(node: String): NodeAccess =
        client.get("$baseUrl/api/nodes/${node.enc()}/access").body()

    suspend fun addShare(node: String, user: String) {
        client.post("$baseUrl/api/nodes/${node.enc()}/shares") {
            contentType(ContentType.Application.Json); setBody(ShareReq(user))
        }
    }

    suspend fun removeShare(node: String, user: String) {
        client.delete("$baseUrl/api/nodes/${node.enc()}/shares/${user.enc()}")
    }

    suspend fun setOwner(node: String, user: String) {
        client.put("$baseUrl/api/nodes/${node.enc()}/owner") {
            contentType(ContentType.Application.Json); setBody(ShareReq(user))
        }
    }

    suspend fun removeNode(node: String) {
        client.delete("$baseUrl/api/nodes/${node.enc()}")
    }

    suspend fun mintJoin(req: JoinReq): JoinResult =
        client.post("$baseUrl/api/join") { contentType(ContentType.Application.Json); setBody(req) }.body()

    // ---- harness management ----
    suspend fun harness(node: String): HarnessState =
        client.get("$baseUrl/api/nodes/${node.enc()}/harness").body()

    suspend fun setHarnessConfig(node: String, config: JsonElement) {
        client.put("$baseUrl/api/nodes/${node.enc()}/harness/config") {
            contentType(ContentType.Application.Json); setBody(config)
        }
    }

    suspend fun harnessApply(node: String, backend: String): String =
        client.post("$baseUrl/api/nodes/${node.enc()}/harness/apply") {
            contentType(ContentType.Application.Json); setBody(BackendReq(backend))
        }.body<MessageResult>().message

    suspend fun harnessInstall(node: String, backend: String): String =
        client.post("$baseUrl/api/nodes/${node.enc()}/harness/install") {
            contentType(ContentType.Application.Json); setBody(BackendReq(backend))
        }.body<MessageResult>().message

    // ---- users (admin) ----
    suspend fun users(): List<UserInfo> = client.get("$baseUrl/api/users").body()

    suspend fun createUser(req: CreateUserReq) {
        client.post("$baseUrl/api/users") { contentType(ContentType.Application.Json); setBody(req) }
    }

    suspend fun deleteUser(user: String) { client.delete("$baseUrl/api/users/${user.enc()}") }

    suspend fun setPassword(user: String, password: String) {
        client.put("$baseUrl/api/users/${user.enc()}/password") {
            contentType(ContentType.Application.Json); setBody(PasswordReq(password))
        }
    }

    suspend fun setAdmin(user: String, admin: Boolean) {
        client.put("$baseUrl/api/users/${user.enc()}/admin") {
            contentType(ContentType.Application.Json); setBody(AdminReq(admin))
        }
    }

    suspend fun audit(limit: Int = 200): List<AuditRecord> =
        client.get("$baseUrl/api/audit?limit=$limit").body()

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
