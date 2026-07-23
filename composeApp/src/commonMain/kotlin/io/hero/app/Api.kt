package io.hero.app

import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Api is a thin client over the v1 control-plane API. The session cookie is
// managed explicitly (not via the HttpCookies plugin) so it can be persisted for
// "remember me" and restored on the next launch. Pass initialCookie to resume a
// saved session. followRedirects is off so login can read Set-Cookie off the 303.
class Api(private val baseUrl: String, initialCookie: String? = null) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The current hero_cp_session cookie value; null until login succeeds. */
    var sessionCookie: String? = initialCookie
        private set

    private val client = heroHttpClient {
        install(ContentNegotiation) { json(json) }
        // Bounds unary calls (the engine's socket read timeout is 0 so SSE can
        // idle); sseFrames raises its own request timeout to infinite.
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
        followRedirects = false
        defaultRequest {
            sessionCookie?.let { headers.append(HttpHeaders.Cookie, "hero_cp_session=$it") }
        }
    }

    // orThrow surfaces non-2xx responses on calls whose body is otherwise
    // ignored or decodes error JSON into an all-defaults DTO (which would read
    // as success — dialogs closing, lists refreshing, nothing changed).
    private suspend fun HttpResponse.orThrow(): HttpResponse {
        if (!status.isSuccess()) {
            val msg = runCatching { bodyAsText() }.getOrNull()?.trim()?.take(300)?.ifBlank { null }
            throw IllegalStateException(msg ?: "HTTP ${status.value}")
        }
        return this
    }

    /** probe validates a base URL is a reachable HERO control plane by hitting the
     *  unauthenticated auth-methods endpoint (200 + JSON = it's really HERO). */
    suspend fun probe(): Boolean = try {
        client.get("$baseUrl/api/auth/methods").status.value == 200
    } catch (_: Throwable) {
        false
    }

    /** login posts the form (redirects off) and captures the session cookie from
     *  the 303's Set-Cookie header. Success = a cookie was issued. */
    suspend fun login(user: String, password: String): Boolean {
        val resp = client.submitForm(
            url = "$baseUrl/login",
            formParameters = parameters {
                append("user", user)
                append("password", password)
            },
        )
        if (resp.status.value != 303 && !resp.status.isSuccess()) return false
        val setCookie = resp.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        val hero = setCookie.firstOrNull { it.startsWith("hero_cp_session=") } ?: return false
        sessionCookie = hero.substringAfter('=').substringBefore(';')
        return sessionCookie!!.isNotEmpty()
    }

    suspend fun nodes(): List<NodeView> =
        client.get("$baseUrl/api/nodes").body()

    suspend fun sessions(node: String): List<Session> =
        client.get("$baseUrl/api/nodes/${node.enc()}/sessions").body()

    suspend fun send(node: String, id: String, text: String, model: String = "", effort: String = "") {
        client.post("$baseUrl/api/nodes/${node.enc()}/sessions/${id.enc()}/send") {
            contentType(ContentType.Application.Json)
            setBody(SendReq(text, model, effort))
        }.orThrow()
    }

    suspend fun me(): Me {
        val r = client.get("$baseUrl/api/me")
        if (r.status.value != 200) throw IllegalStateException("not authenticated (${r.status.value})")
        return r.body()
    }

    // ---- sessions ----
    suspend fun startSession(node: String, req: StartSessionReq): String {
        val resp = client.post("$baseUrl/api/nodes/${node.enc()}/sessions") {
            contentType(ContentType.Application.Json); setBody(req)
        }
        // Surface the node's validation error (e.g. "cwd is required",
        // "initial_message: text is required") instead of silently returning "".
        if (!resp.status.isSuccess()) {
            val msg = runCatching { resp.bodyAsText() }.getOrNull()?.trim()?.ifBlank { null }
            throw IllegalStateException(msg ?: "create failed (${resp.status.value})")
        }
        return runCatching { resp.body<Map<String, String>>()["id"] ?: "" }.getOrDefault("")
    }

    suspend fun pending(node: String): List<Pending> =
        client.get("$baseUrl/api/nodes/${node.enc()}/pending").body()

    /** attention is the cross-fleet inbox: one aggregate of everything wanting the
     *  user's attention across every accessible connected node (permission/question
     *  + recent finished/errored), each item stamped with its node. */
    suspend fun attention(): List<AttentionItem> =
        client.get("$baseUrl/api/attention").body()

    // ---- conversation: structured window (history + grouped live tail) ----

    /** transcript fetches one grouped display page. offset=null → newest page; the
     *  X-Transcript-* headers carry the cursor for prepending older pages. */
    suspend fun transcript(node: String, id: String, limit: Int, offset: Int? = null): TranscriptPage {
        val resp = client.get("$baseUrl/api/nodes/${node.enc()}/sessions/${id.enc()}/transcript") {
            parameter("limit", limit)
            if (offset != null) parameter("offset", offset)
        }
        val turns: List<Turn> = resp.body()
        val h = resp.headers
        return TranscriptPage(
            turns = turns,
            total = h["X-Transcript-Total"]?.toIntOrNull() ?: turns.size,
            start = h["X-Transcript-Start"]?.toIntOrNull() ?: 0,
            hasMore = h["X-Transcript-Has-More"].equals("true", ignoreCase = true),
        )
    }

    /** liveFrames is the grouped, typed live tail for one session: the /parts SSE
     *  (structured frames only — no raw jsonl), filtered to this session and decoded. */
    fun liveFrames(node: String, session: String): Flow<LiveFrame> =
        sseFrames("$baseUrl/api/nodes/${node.enc()}/parts")
            .filter { it.session_id.isEmpty() || it.session_id == session }
            .mapNotNull { decodeLiveFrame(it, json) }

    suspend fun respond(node: String, id: String, behavior: String) =
        respond(node, id, RespondReq(behavior))

    /** respond resolves a pending interaction with a full request (scope for
     *  "allow always", answers for AskUserQuestion). */
    suspend fun respond(node: String, id: String, req: RespondReq) {
        client.post("$baseUrl/api/nodes/${node.enc()}/pending/${id.enc()}/respond") {
            contentType(ContentType.Application.Json); setBody(req)
        }.orThrow()
    }

    // ---- node management ----
    suspend fun nodeAccess(node: String): NodeAccess =
        client.get("$baseUrl/api/nodes/${node.enc()}/access").body()

    suspend fun addShare(node: String, user: String) {
        client.post("$baseUrl/api/nodes/${node.enc()}/shares") {
            contentType(ContentType.Application.Json); setBody(ShareReq(user))
        }.orThrow()
    }

    suspend fun removeShare(node: String, user: String) {
        client.delete("$baseUrl/api/nodes/${node.enc()}/shares/${user.enc()}").orThrow()
    }

    suspend fun setOwner(node: String, user: String) {
        client.put("$baseUrl/api/nodes/${node.enc()}/owner") {
            contentType(ContentType.Application.Json); setBody(ShareReq(user))
        }.orThrow()
    }

    suspend fun removeNode(node: String) {
        client.delete("$baseUrl/api/nodes/${node.enc()}").orThrow()
    }

    suspend fun mintJoin(req: JoinReq): JoinResult =
        client.post("$baseUrl/api/join") { contentType(ContentType.Application.Json); setBody(req) }.orThrow().body()

    // ---- harness management ----
    suspend fun harness(node: String): HarnessState =
        client.get("$baseUrl/api/nodes/${node.enc()}/harness").body()

    suspend fun setHarnessConfig(node: String, config: JsonElement) {
        client.put("$baseUrl/api/nodes/${node.enc()}/harness/config") {
            contentType(ContentType.Application.Json); setBody(config)
        }.orThrow()
    }

    suspend fun harnessApply(node: String, backend: String): String =
        client.post("$baseUrl/api/nodes/${node.enc()}/harness/apply") {
            contentType(ContentType.Application.Json); setBody(BackendReq(backend))
        }.orThrow().body<MessageResult>().message

    suspend fun harnessInstall(node: String, backend: String): String =
        client.post("$baseUrl/api/nodes/${node.enc()}/harness/install") {
            contentType(ContentType.Application.Json); setBody(BackendReq(backend))
        }.orThrow().body<MessageResult>().message

    // ---- users (admin) ----
    suspend fun users(): List<UserInfo> = client.get("$baseUrl/api/users").body()

    suspend fun createUser(req: CreateUserReq) {
        client.post("$baseUrl/api/users") { contentType(ContentType.Application.Json); setBody(req) }.orThrow()
    }

    suspend fun deleteUser(user: String) { client.delete("$baseUrl/api/users/${user.enc()}").orThrow() }

    suspend fun setPassword(user: String, password: String) {
        client.put("$baseUrl/api/users/${user.enc()}/password") {
            contentType(ContentType.Application.Json); setBody(PasswordReq(password))
        }.orThrow()
    }

    suspend fun setAdmin(user: String, admin: Boolean) {
        client.put("$baseUrl/api/users/${user.enc()}/admin") {
            contentType(ContentType.Application.Json); setBody(AdminReq(admin))
        }.orThrow()
    }

    suspend fun audit(limit: Int = 200): List<AuditRecord> =
        client.get("$baseUrl/api/audit?limit=$limit").body()

    // ---- native push registration (control-plane fleet web push / FCM) ----
    suspend fun subscribePush(sub: PushSub) {
        client.post("$baseUrl/api/push/subscribe") {
            contentType(ContentType.Application.Json); setBody(sub)
        }.orThrow()
    }

    suspend fun unsubscribePush(endpoint: String) {
        client.post("$baseUrl/api/push/unsubscribe") {
            contentType(ContentType.Application.Json); setBody(mapOf("endpoint" to endpoint))
        }.orThrow()
    }

    /** vapidKey fetches the server's VAPID applicationServerKey (base64url) for a
     *  UnifiedPush/Web Push subscription; null when push is unavailable. */
    suspend fun vapidKey(): String? = runCatching {
        client.get("$baseUrl/api/push/vapid-key").body<Map<String, String>>()["key"]
    }.getOrNull()

    // ---- control-plane self-update (the CP binary; nodes/harnesses are separate) ----
    suspend fun fleetHero(): HeroFleet =
        client.get("$baseUrl/api/fleet/hero").body()

    /** setFleetHeroAuto toggles fleet-wide auto-update (re-signs the manifest); returns the new state. */
    suspend fun setFleetHeroAuto(auto: Boolean): Boolean =
        client.put("$baseUrl/api/fleet/hero") {
            contentType(ContentType.Application.Json); setBody(AutoUpdateReq(auto))
        }.orThrow().body<AutoUpdateResp>().auto_update

    /** controlSelfUpdate updates the control plane to the published target; it drains + restarts. */
    suspend fun controlSelfUpdate(): String =
        client.post("$baseUrl/api/control/self-update").orThrow().body<MessageResult>().message

    /** events streams the node's raw+grouped SSE feed (all sessions). */
    fun events(node: String): Flow<Event> = sseFrames("$baseUrl/api/nodes/${node.enc()}/events")

    /** sseFrames reads an SSE endpoint, parsing each `data:` frame into an Event. */
    private fun sseFrames(url: String): Flow<Event> = flow {
        client.prepareGet(url) {
            // A quiet stream is not a stuck request — the client's unary 30s
            // bound must not apply here (reconnect loops live in the callers).
            timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
        }.execute { resp ->
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

/** TranscriptPage fuses a transcript page body with its X-Transcript-* cursor. */
data class TranscriptPage(
    val turns: List<Turn>,
    val total: Int,
    val start: Int,
    val hasMore: Boolean,
)

private fun String.enc(): String = this.encodeURLPathPart()
