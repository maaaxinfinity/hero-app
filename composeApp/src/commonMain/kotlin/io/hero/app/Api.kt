package io.hero.app

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Api is a thin client over the v1 control-plane API. The session cookie is
// managed explicitly (not via the HttpCookies plugin) so it can be persisted for
// "remember me" and restored on the next launch. Pass initialCookie to resume a
// saved session. followRedirects is off so login can read Set-Cookie off the 303.
// clientBuilder exists so the contract tests can run every call against a
// controlled engine (MockEngine); production always uses heroHttpClient.
// @JvmOverloads so the release-artifact smoke (Java) can construct Api(baseUrl,
// cookie) with the REAL default clientBuilder (::heroHttpClient) — Kotlin
// otherwise exposes only the full-arity constructor to Java, and the smoke's
// 2-arg call stopped compiling once clientBuilder was added (the desktop release
// gate has failed to build since).
class Api @JvmOverloads constructor(
    private val baseUrl: String,
    initialCookie: String? = null,
    clientBuilder: (HttpClientConfig<*>.() -> Unit) -> HttpClient = ::heroHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The current hero_cp_session cookie value; null until login succeeds. */
    var sessionCookie: String? = initialCookie
        private set

    private val client = clientBuilder {
        install(ContentNegotiation) { json(json) }
        // Bounds unary calls (the engine's socket read timeout is 0 so SSE can
        // idle); sseFrames raises its own request timeout to infinite.
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
        followRedirects = false
        defaultRequest {
            // Native (non-browser) CSRF credential: the control-plane boundary
            // admits a state-changing request that carries EITHER a same-origin
            // Origin (browsers) OR this dedicated header (apps/plugins, which
            // send no Origin). Set on every request — harmless on reads, and it
            // guarantees no mutation added later can forget it and 403.
            headers.append(HERO_CLIENT_HEADER, HERO_CLIENT_VALUE)
            sessionCookie?.let { headers.append(HttpHeaders.Cookie, "hero_cp_session=$it") }
        }
    }

    // orThrow surfaces non-2xx responses on calls whose body is otherwise
    // ignored or decodes error JSON into an all-defaults DTO (which would read
    // as success — dialogs closing, lists refreshing, nothing changed, or a
    // GET like harness()/fleetHero() presenting an error object as authoritative
    // empty truth). The status is checked FIRST; the error body is read through
    // a hard pre-decode byte ceiling (bodyAsText() had none — an arbitrarily
    // large error body was fully materialized before the display take());
    // the result is a typed ApiHttpException carrying {status, operation,
    // operation id} so callers and logs can correlate a failure with the
    // mutation that caused it.
    private suspend fun HttpResponse.orThrow(operation: String, operationId: String? = null): HttpResponse {
        if (!status.isSuccess()) {
            val detail = runCatchingCancellable { boundedBodyText(bodyAsChannel(), MAX_ERROR_BODY_BYTES) }.getOrNull()
            throw ApiHttpException(status.value, operation, operationId, detail)
        }
        return this
    }

    // read wraps one GET in the explicit read-retry contract: mutations are
    // NEVER replayed by the app or the transport (retryOnConnectionFailure is
    // off), while reads — which are safe to re-issue — get a small number of
    // attempts with jittered, budgeted delays for TRANSIENT failures only
    // (connection resets, 5xx/408/429). Deterministic client errors, decode
    // failures, and cancellation re-throw immediately. This replaces "the
    // caller may re-issue reads" folklore with one bounded policy.
    private suspend fun <T> read(operation: String, block: suspend () -> T): T {
        val start = TimeSource.Monotonic.markNow()
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (!isTransientReadError(t)) throw t
                val pause = readRetryDelayMs(READ_RETRY, attempt, start.elapsedNow().inWholeMilliseconds)
                    ?: throw t
                delay(pause)
                attempt += 1
            }
        }
    }

    /** close releases the underlying HttpClient: its connection pool, the OkHttp
     *  engine's dispatcher coroutines, and any in-flight SSE calls (they run on
     *  this client). The single app-session owner closes an Api when it replaces
     *  it (probe/login/change-server/sign-out) or leaves composition; an Api must
     *  not be used after close(). Mirrors the updater client's finally-close. */
    fun close() {
        client.close()
    }

    /** probe validates a base URL is a reachable HERO control plane by hitting the
     *  unauthenticated auth-methods endpoint (200 + JSON = it's really HERO). */
    suspend fun probe(): Boolean = try {
        client.get("$baseUrl/api/auth/methods").status.value == 200
    } catch (c: CancellationException) {
        throw c
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

    suspend fun nodes(): List<NodeView> = read("nodes") {
        client.get("$baseUrl/api/nodes").orThrow("nodes").body()
    }

    suspend fun sessions(node: String): List<Session> = read("sessions") {
        client.get("$baseUrl/api/nodes/${node.enc()}/sessions").orThrow("sessions").body()
    }

    /** nodeModels loads the node's LIGHTWEIGHT model/effort picker snapshot (read
     *  scope — open to every granted user, unlike the admin-only harness DTO that
     *  403s a shared user into an empty picker). An old node without the capability
     *  answers HTTP 501; orThrow surfaces it as an ApiHttpException whose [code] is
     *  "model_catalog_unsupported", which the picker maps to a graceful "no switch"
     *  degradation. A 501 is deterministic (a capability gap, not a hiccup), so the
     *  read layer does NOT retry it (see isTransientReadError). */
    suspend fun nodeModels(node: String): ModelCatalogSnapshot = read("models") {
        client.get("$baseUrl/api/nodes/${node.enc()}/models").orThrow("models").body()
    }

    suspend fun send(
        node: String, id: String, text: String,
        model: String = "", effort: String = "",
        operationId: String = newOperationId(),
    ) {
        client.post("$baseUrl/api/nodes/${node.enc()}/sessions/${id.enc()}/send") {
            operation(operationId)
            contentType(ContentType.Application.Json)
            setBody(SendReq(text, model, effort))
        }.orThrow("send", operationId)
    }

    /** me is the authoritative identity read. It THROWS on any non-200 or a
     *  malformed body: both login paths must treat "couldn't confirm who I am"
     *  as a failed bootstrap, never substitute a locally guessed Me. */
    suspend fun me(): Me = read("me") {
        val r = client.get("$baseUrl/api/me")
        if (r.status.value != 200) {
            val detail = if (r.status.value == 401 || r.status.value == 403) "not authenticated (${r.status.value})"
            else runCatchingCancellable { boundedBodyText(r.bodyAsChannel(), MAX_ERROR_BODY_BYTES) }.getOrNull()
            throw ApiHttpException(r.status.value, "me", null, detail)
        }
        r.body()
    }

    // ---- sessions ----
    suspend fun startSession(node: String, req: StartSessionReq, operationId: String = newOperationId()): String {
        val resp = client.post("$baseUrl/api/nodes/${node.enc()}/sessions") {
            operation(operationId)
            contentType(ContentType.Application.Json); setBody(req)
        }
        // Surface the node's validation error (e.g. "cwd is required",
        // "initial_message: text is required") instead of silently returning "".
        // The error body is byte-bounded BEFORE decode and display-capped after:
        // an arbitrarily large error body must become neither a heap spike nor
        // a megabyte exception message in the dialog.
        if (!resp.status.isSuccess()) {
            val detail = runCatchingCancellable { boundedBodyText(resp.bodyAsChannel(), MAX_ERROR_BODY_BYTES) }.getOrNull()
            throw ApiHttpException(resp.status.value, "create session", operationId, detail)
        }
        return runCatchingCancellable { resp.body<Map<String, String>>()["id"] ?: "" }.getOrDefault("")
    }

    suspend fun pending(node: String): List<Pending> = read("pending") {
        client.get("$baseUrl/api/nodes/${node.enc()}/pending").orThrow("pending").body()
    }

    /** attention is the cross-fleet inbox: one aggregate of everything wanting the
     *  user's attention across every accessible connected node (permission/question
     *  + recent finished/errored), each item stamped with its node. */
    suspend fun attention(): List<AttentionItem> = read("attention") {
        client.get("$baseUrl/api/attention").orThrow("attention").body()
    }

    // ---- conversation: structured window (history + grouped live tail) ----

    /** transcript fetches one grouped display page. offset=null → newest page; the
     *  X-Transcript-* headers carry the cursor for prepending older pages, plus —
     *  while the node has a live /parts feed — the stream watermark captured
     *  BEFORE the page was read (directly usable as a Last-Event-ID). */
    suspend fun transcript(node: String, id: String, limit: Int, offset: Int? = null): TranscriptPage = read("transcript") {
        val resp = client.get("$baseUrl/api/nodes/${node.enc()}/sessions/${id.enc()}/transcript") {
            parameter("limit", limit)
            if (offset != null) parameter("offset", offset)
        }.orThrow("transcript")
        val turns: List<Turn> = resp.body()
        val h = resp.headers
        TranscriptPage(
            turns = turns,
            total = h["X-Transcript-Total"]?.toIntOrNull() ?: turns.size,
            start = h["X-Transcript-Start"]?.toIntOrNull() ?: 0,
            hasMore = h["X-Transcript-Has-More"].equals("true", ignoreCase = true),
            streamSeq = h[TRANSCRIPT_SEQ_HEADER]?.takeIf { it.isNotEmpty() },
        )
    }

    /** liveFrames is the grouped, typed live tail for one session: the /parts SSE
     *  (structured frames only — no raw jsonl), scoped server-side to this session
     *  (the client filter stays as belt and braces), opted into the resume cursor
     *  (`replay=1` — every sequenced frame carries `id: <epoch>-<seq>`), and asking
     *  the server to drop `part.delta` before the wire (an old server keeps
     *  sending them; reduce's Delta branch still absorbs those).
     *
     *  [lastEventId] (the last id applied, or a transcript watermark) is sent as
     *  `Last-Event-ID`; [onEventId] reports each frame's id AFTER the collector
     *  has applied it (so a resume from it can never skip an unapplied frame);
     *  [onSubscribed] runs once the handshake is validated, before any frame is
     *  consumed, with the server's verdict: resumed=true means every sequenced
     *  frame after [lastEventId] will be delivered — the caller may skip its
     *  truth-up re-read; false (gap, stripped header, epoch change, old server)
     *  means the caller must reconcile from a fresh transcript snapshot. */
    fun liveFrames(
        node: String, session: String,
        lastEventId: String? = null,
        onEventId: (String) -> Unit = {},
        onSubscribed: suspend (resumed: Boolean) -> Unit = {},
    ): Flow<LiveFrame> =
        sseFrames(
            "$baseUrl/api/nodes/${node.enc()}/parts" +
                "?session=${session.encodeURLParameter()}&replay=1&include_deltas=false",
            lastEventId, onEventId, onSubscribed,
        )
            .filter { it.session_id.isEmpty() || it.session_id == session }
            .mapNotNull { decodeLiveFrame(it, json) }

    suspend fun respond(node: String, id: String, behavior: String, operationId: String = newOperationId()) =
        respond(node, id, RespondReq(behavior), operationId)

    /** respond resolves a pending interaction with a full request (scope for
     *  "allow always", answers for AskUserQuestion). */
    suspend fun respond(node: String, id: String, req: RespondReq, operationId: String = newOperationId()) {
        client.post("$baseUrl/api/nodes/${node.enc()}/pending/${id.enc()}/respond") {
            operation(operationId)
            contentType(ContentType.Application.Json); setBody(req)
        }.orThrow("respond", operationId)
    }

    // ---- node management ----
    suspend fun nodeAccess(node: String): NodeAccess = read("access") {
        client.get("$baseUrl/api/nodes/${node.enc()}/access").orThrow("access").body()
    }

    suspend fun addShare(node: String, user: String, operationId: String = newOperationId()) {
        client.post("$baseUrl/api/nodes/${node.enc()}/shares") {
            operation(operationId)
            contentType(ContentType.Application.Json); setBody(ShareReq(user))
        }.orThrow("share", operationId)
    }

    suspend fun removeShare(node: String, user: String, operationId: String = newOperationId()) {
        client.delete("$baseUrl/api/nodes/${node.enc()}/shares/${user.enc()}") {
            operation(operationId)
        }.orThrow("remove share", operationId)
    }

    suspend fun setOwner(node: String, user: String, operationId: String = newOperationId()) {
        client.put("$baseUrl/api/nodes/${node.enc()}/owner") {
            operation(operationId)
            contentType(ContentType.Application.Json); setBody(ShareReq(user))
        }.orThrow("transfer owner", operationId)
    }

    suspend fun removeNode(node: String, operationId: String = newOperationId()) {
        client.delete("$baseUrl/api/nodes/${node.enc()}") { operation(operationId) }
            .orThrow("remove node", operationId)
    }

    suspend fun mintJoin(req: JoinReq, operationId: String = newOperationId()): JoinResult =
        client.post("$baseUrl/api/join") {
            operation(operationId)
            contentType(ContentType.Application.Json); setBody(req)
        }.orThrow("mint join", operationId).body()

    // ---- harness management ----
    suspend fun harness(node: String): HarnessState = read("harness") {
        client.get("$baseUrl/api/nodes/${node.enc()}/harness").orThrow("harness").body()
    }

    suspend fun setHarnessConfig(node: String, config: JsonElement, operationId: String = newOperationId()) {
        client.put("$baseUrl/api/nodes/${node.enc()}/harness/config") {
            operation(operationId)
            contentType(ContentType.Application.Json); setBody(config)
        }.orThrow("save harness config", operationId)
    }

    suspend fun harnessApply(node: String, backend: String, operationId: String = newOperationId()): String =
        client.post("$baseUrl/api/nodes/${node.enc()}/harness/apply") {
            operation(operationId)
            contentType(ContentType.Application.Json); setBody(BackendReq(backend))
        }.orThrow("apply harness", operationId).body<MessageResult>().message

    suspend fun harnessInstall(node: String, backend: String, operationId: String = newOperationId()): String =
        client.post("$baseUrl/api/nodes/${node.enc()}/harness/install") {
            operation(operationId)
            contentType(ContentType.Application.Json); setBody(BackendReq(backend))
        }.orThrow("install harness", operationId).body<MessageResult>().message

    // ---- users (admin) ----
    suspend fun users(): List<UserInfo> = read("users") {
        client.get("$baseUrl/api/users").orThrow("users").body()
    }

    suspend fun createUser(req: CreateUserReq, operationId: String = newOperationId()) {
        client.post("$baseUrl/api/users") {
            operation(operationId)
            contentType(ContentType.Application.Json); setBody(req)
        }.orThrow("create user", operationId)
    }

    suspend fun deleteUser(user: String, operationId: String = newOperationId()) {
        client.delete("$baseUrl/api/users/${user.enc()}") { operation(operationId) }
            .orThrow("delete user", operationId)
    }

    suspend fun setPassword(user: String, password: String, operationId: String = newOperationId()) {
        client.put("$baseUrl/api/users/${user.enc()}/password") {
            operation(operationId)
            contentType(ContentType.Application.Json); setBody(PasswordReq(password))
        }.orThrow("set password", operationId)
    }

    suspend fun setAdmin(user: String, admin: Boolean, operationId: String = newOperationId()) {
        client.put("$baseUrl/api/users/${user.enc()}/admin") {
            operation(operationId)
            contentType(ContentType.Application.Json); setBody(AdminReq(admin))
        }.orThrow("set admin", operationId)
    }

    suspend fun audit(limit: Int = 200): List<AuditRecord> = read("audit") {
        client.get("$baseUrl/api/audit?limit=$limit").orThrow("audit").body()
    }

    // ---- native push registration (control-plane fleet web push / FCM) ----
    suspend fun subscribePush(sub: PushSub, operationId: String = newOperationId()) {
        client.post("$baseUrl/api/push/subscribe") {
            operation(operationId)
            contentType(ContentType.Application.Json); setBody(sub)
        }.orThrow("subscribe push", operationId)
    }

    suspend fun unsubscribePush(endpoint: String, operationId: String = newOperationId()) {
        client.post("$baseUrl/api/push/unsubscribe") {
            operation(operationId)
            contentType(ContentType.Application.Json); setBody(mapOf("endpoint" to endpoint))
        }.orThrow("unsubscribe push", operationId)
    }

    /** vapidKey fetches the server's VAPID applicationServerKey (base64url) for a
     *  UnifiedPush/Web Push subscription; null when push is unavailable. */
    suspend fun vapidKey(): String? = runCatchingCancellable {
        read("vapid key") {
            client.get("$baseUrl/api/push/vapid-key").orThrow("vapid key").body<Map<String, String>>()["key"]
        }
    }.getOrNull()

    // ---- control-plane self-update (the CP binary; nodes/harnesses are separate) ----
    suspend fun fleetHero(): HeroFleet = read("fleet hero") {
        client.get("$baseUrl/api/fleet/hero").orThrow("fleet hero").body()
    }

    /** setFleetHeroPolicy flips the split rollout-policy bits (the server
     *  re-signs the manifest in place): a null bit is omitted from the wire and
     *  leaves that policy unchanged, so the control and node toggles never move
     *  each other. [expectedGeneration] is the CAS base — a stale view is
     *  refused with 409 ([isStaleConflict]) and must reload-then-redecide
     *  instead of silently clobbering the toggle that won the race. */
    suspend fun setFleetHeroPolicy(
        control: Boolean? = null,
        node: Boolean? = null,
        expectedGeneration: Long? = null,
        operationId: String = newOperationId(),
    ): AutoUpdateResp =
        client.put("$baseUrl/api/fleet/hero") {
            operation(operationId)
            contentType(ContentType.Application.Json)
            setBody(AutoUpdateReq(control, node, expectedGeneration))
        }.orThrow("set update policy", operationId).body()

    /** setFleetHeroAutoLegacy drives a legacy (pre-split) server's single
     *  auto_update bit — the degraded mode ControlScreen falls back to when
     *  fleetHero() carried no split-policy fields. Never sent to a split-policy
     *  server (it would 400 on the missing per-scope bits). */
    suspend fun setFleetHeroAutoLegacy(auto: Boolean, operationId: String = newOperationId()): AutoUpdateResp =
        client.put("$baseUrl/api/fleet/hero") {
            operation(operationId)
            contentType(ContentType.Application.Json); setBody(LegacyAutoUpdateReq(auto))
        }.orThrow("set auto-update", operationId).body()

    /** controlSelfUpdate updates the control plane to the published target; it
     *  drains + restarts. The receipt carries the server's persistent operation
     *  id + target generation on a split-policy server — the identity the
     *  in-progress presentation converges on — and only message (nulls) on a
     *  legacy one. */
    suspend fun controlSelfUpdate(operationId: String = newOperationId()): ControlUpdateResp =
        client.post("$baseUrl/api/control/self-update") { operation(operationId) }
            .orThrow("control self-update", operationId).body()

    /** events streams the node's raw+grouped SSE feed (all sessions). */
    fun events(node: String): Flow<Event> = sseFrames("$baseUrl/api/nodes/${node.enc()}/events")

    /** sseFrames reads an SSE endpoint, parsing each `data:` frame into an Event.
     *  The handshake is validated before any line is consumed: 401/403 throw the
     *  permanent [StreamAuthException], any other non-2xx a transient error, and a
     *  2xx that is not actually an SSE stream (204 no-body, or a proxy's 200
     *  HTML/JSON page) the permanent [StreamProtocolException] — previously such a
     *  body was ignored line by line and bare-reconnected forever. [onSubscribed]
     *  fires after validation, before the first read, carrying the resume verdict
     *  ([resumedHandshake] over the X-Parts-* answer to [lastEventId]); callers
     *  without a cursor get resumed=false — the status-quo truth-up posture. */
    private fun sseFrames(
        url: String,
        lastEventId: String? = null,
        onEventId: (String) -> Unit = {},
        onSubscribed: suspend (resumed: Boolean) -> Unit = {},
    ): Flow<Event> = flow {
        client.prepareGet(url) {
            // A quiet stream is not a stuck request — the client's unary 30s
            // bound must not apply here (reconnect loops live in the callers).
            timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
            if (lastEventId != null) header(LAST_EVENT_ID_HEADER, lastEventId)
        }.execute { resp ->
            // A non-2xx (401 after logout, 502/offline via a proxy) is an error,
            // not an empty stream: surface it so the caller's reconnect/error path
            // runs instead of treating a short error body as a cleanly-ended stream.
            if (!resp.status.isSuccess()) {
                if (resp.status.value == 401 || resp.status.value == 403) {
                    throw StreamAuthException(resp.status.value)
                }
                throw IllegalStateException("stream ${resp.status.value}")
            }
            // isSuccess() also admits bodyless 204s and any 2xx a proxy fabricates;
            // require the SSE content type before treating the body as a stream.
            if (resp.status.value == 204) throw StreamProtocolException("empty stream (204 No Content)")
            val ct = resp.contentType()
            if (ct == null || !ct.match(ContentType.Text.EventStream)) {
                throw StreamProtocolException("not an event stream: ${ct ?: "no content type"}")
            }
            onSubscribed(resumedHandshake(lastEventId, resp.headers[PARTS_EPOCH_HEADER], resp.headers[PARTS_REPLAY_HEADER]))
            val lines = SseLineReader(resp.bodyAsChannel(), MAX_SSE_LINE)
            // The server writes `id: <epoch>-<seq>` BEFORE its frame's data line.
            // The id is committed via [onEventId] only after emit() returns — the
            // collector has applied (or knowingly skipped) the frame by then, so
            // resuming from a committed id never skips an unapplied frame
            // (EventSource's dispatch-time semantics). Comment lines (the ": hb"
            // heartbeat) and blank separators fall through both branches ignored.
            var pendingId: String? = null
            while (true) {
                val line = lines.readLine() ?: break
                when {
                    line.startsWith("data:") -> {
                        val data = line.removePrefix("data:").trim()
                        if (data.isNotEmpty()) {
                            emit(json.decodeFromString(Event.serializer(), data))
                        }
                        pendingId?.let(onEventId)
                        pendingId = null
                    }
                    line.startsWith("id:") -> {
                        line.removePrefix("id:").trim().takeIf { it.isNotEmpty() }?.let { pendingId = it }
                    }
                }
            }
        }
    }
}

// MAX_SSE_LINE bounds one SSE line to the node RPC message ceiling (16 MiB); a
// grouped frame is already capped there upstream, so a longer line is malformed.
private const val MAX_SSE_LINE = 16 * 1024 * 1024

/** HERO_OP_HEADER carries the client operation id of one logical mutation. The
 *  transport never replays mutations (retryOnConnectionFailure is off), but a
 *  user-driven retry after a lost response is still at-least-once from the
 *  server's point of view; a persistent id per logical operation is what lets
 *  the control plane/node dedupe re-submissions before side effects and lets a
 *  typed error be correlated with the exact operation that failed. Reusing the
 *  SAME id for an explicit retry of the SAME logical operation (StartSessionDialog,
 *  the management mutation runner) is the client half of that contract. */
internal const val HERO_OP_HEADER = "X-Hero-Op"

/** HERO_CLIENT_HEADER is the native-client CSRF credential the control-plane
 *  boundary requires on every state-changing request. A browser proves same
 *  origin with an Origin header it cannot forge cross-site; a native client
 *  (this app) sends no Origin, so it instead carries the session cookie PLUS
 *  this dedicated header — one a cross-site "simple request" cannot attach.
 *  The server checks only that it is present (non-empty). */
internal const val HERO_CLIENT_HEADER = "X-Hero-Client"

/** The value sent for [HERO_CLIENT_HEADER]. The server checks presence, not the
 *  value; a stable identifier aids its logs/telemetry. */
internal const val HERO_CLIENT_VALUE = "hero-app"

/** MAX_ERROR_BODY_BYTES is the hard pre-decode ceiling on how much of a FAILED
 *  response's body is ever read off the wire. bodyAsText() had no bound — the
 *  display take(300) capped what was shown, not what was fetched/allocated. */
internal const val MAX_ERROR_BODY_BYTES = 8 * 1024

/** ERROR_DISPLAY_CHARS caps how much error-body text ends up in an exception
 *  message (and so in a status line/dialog). */
internal const val ERROR_DISPLAY_CHARS = 300

private fun HttpRequestBuilder.operation(id: String) {
    header(HERO_OP_HEADER, id)
}

/** newOperationId mints the client operation id for one logical mutation:
 *  128 random bits, hex. Unique per logical operation, stable across explicit
 *  retries of that same operation. */
internal fun newOperationId(): String {
    val b = Random.nextBytes(16)
    val sb = StringBuilder(32)
    for (x in b) {
        val v = x.toInt() and 0xff
        sb.append(HEX[v ushr 4]).append(HEX[v and 0xf])
    }
    return sb.toString()
}

private val HEX = "0123456789abcdef".toCharArray()

/** ApiHttpException is the typed non-2xx result of one call: the HTTP status,
 *  the logical operation name, the client operation id (mutations), a bounded,
 *  display-capped detail from the error body, and — when the control plane
 *  answered a typed {error, code} envelope — the discriminable [code] (e.g.
 *  "model_switch_unsupported" / "model_catalog_unsupported") a caller branches on
 *  to tell a capability gap apart from a generic failure. Everything a caller
 *  needs to decide retryability and show a message — without ever having read
 *  more than MAX_ERROR_BODY_BYTES of the failure. */
class ApiHttpException(
    val status: Int,
    val operation: String,
    val operationId: String? = null,
    detail: String? = null,
) : IllegalStateException(httpErrorMessage(status, operation, detail)) {
    /** The control plane's typed error code when the body was a {error, code}
     *  envelope (additive; null for a plain-text error or a bodyless failure). */
    val code: String? = errorCode(detail)
}

/** ErrorEnvelope is the control plane's typed error body: a human [error] and a
 *  machine [code] (writeJSON({error, code}) on the negotiated-capability 501s).
 *  Most errors are still plain text (http.Error), so parsing is best-effort. */
@Serializable
private data class ErrorEnvelope(val error: String = "", val code: String = "")

private val ERROR_ENVELOPE_JSON = Json { ignoreUnknownKeys = true }

/** parseErrorEnvelope decodes a {error, code} body, best-effort: null for a
 *  plain-text error, a body that doesn't start with '{', or one truncated at the
 *  MAX_ERROR_BODY_BYTES ceiling (an incomplete JSON just fails to decode). Never
 *  throws. Pure. */
private fun parseErrorEnvelope(detail: String?): ErrorEnvelope? {
    val d = detail?.trim() ?: return null
    if (!d.startsWith("{")) return null
    return runCatching { ERROR_ENVELOPE_JSON.decodeFromString(ErrorEnvelope.serializer(), d) }.getOrNull()
}

/** errorCode extracts the typed control-plane error code from a failure body, or
 *  null when the body carried none. Pure; unit-tested. */
internal fun errorCode(detail: String?): String? = parseErrorEnvelope(detail)?.code?.ifBlank { null }

/** httpErrorMessage renders a typed HTTP failure for humans: the typed envelope's
 *  [error] message when the body was one (so a status line shows "node does not
 *  support …" instead of raw JSON), else the (already byte-bounded) body detail,
 *  display-capped to ERROR_DISPLAY_CHARS — or a "<operation> failed (HTTP
 *  <status>)" fallback. Pure; unit-tested. */
internal fun httpErrorMessage(status: Int, operation: String, detail: String?): String {
    val friendly = parseErrorEnvelope(detail)?.error?.ifBlank { null } ?: detail
    val d = friendly?.trim()?.take(ERROR_DISPLAY_CHARS)?.ifBlank { null }
    return d ?: "$operation failed (HTTP $status)"
}

/** boundedBodyText reads AT MOST [cap] bytes from [ch] and decodes them —
 *  the pre-decode budget for error bodies. The rest of the body is cancelled,
 *  never buffered; a multi-byte sequence cut at the cap decodes to a
 *  replacement character instead of throwing. */
internal suspend fun boundedBodyText(ch: ByteReadChannel, cap: Int): String {
    val buf = ByteArray(cap)
    var filled = 0
    while (filled < cap) {
        val n = ch.readAvailable(buf, filled, cap - filled)
        if (n == -1) break
        filled += n
    }
    ch.cancel(null) // drop the remainder of an arbitrarily large error body
    return buf.decodeToString(0, filled)
}

/** ReadRetryPolicy bounds the explicit GET retry: a small attempt count, a
 *  full-jitter exponential delay, and a total elapsed budget (deadline) that
 *  stops retrying even when attempts remain. Mutations never use this. */
internal data class ReadRetryPolicy(
    val attempts: Int = 3,
    val baseDelayMs: Long = 200,
    val maxDelayMs: Long = 2_000,
    val budgetMs: Long = 8_000,
)

private val READ_RETRY = ReadRetryPolicy()

/** readRetryDelayMs decides whether failed read attempt [attempt] (0-based) may
 *  retry under [policy]: null = give up (attempts exhausted or the deadline
 *  budget is spent); otherwise the jittered delay to sleep first — a uniform
 *  draw from [0, min(base·2^attempt, max)]. Pure given [random]; unit-tested. */
internal fun readRetryDelayMs(
    policy: ReadRetryPolicy,
    attempt: Int,
    elapsedMs: Long,
    random: Random = Random.Default,
): Long? {
    if (attempt >= policy.attempts - 1) return null
    if (elapsedMs >= policy.budgetMs) return null
    val exp = attempt.coerceIn(0, 20) // 2^20 · base is far past any sane max
    val ceiling = (policy.baseDelayMs shl exp).coerceIn(policy.baseDelayMs, policy.maxDelayMs)
    val d = random.nextLong(ceiling + 1)
    return if (elapsedMs + d > policy.budgetMs) null else d
}

/** isTransientReadError classifies one read failure for the retry loop: only
 *  connection-level trouble and 5xx/408/429 are worth re-asking; deterministic
 *  4xx, malformed-body decode failures, and cancellation are not. Pure. */
internal fun isTransientReadError(t: Throwable): Boolean = when (t) {
    is CancellationException -> false
    // 501 (Not Implemented) is a DETERMINISTIC capability gap — an old node that
    // lacks the model catalog / mid-turn switch — not a transient server fault, so
    // it fails fast (the picker/send map its typed code instead of re-asking).
    is ApiHttpException -> (t.status >= 500 && t.status != 501) || t.status == 408 || t.status == 429
    is ContentConvertException -> false
    is SerializationException -> false
    else -> true
}

/** isStaleConflict classifies one mutation failure as the server's
 *  optimistic-concurrency refusal (HTTP 409 on the policy PUT: the
 *  expected_generation no longer matches what is published — a racing toggle
 *  or a republish won). The fix is reload-then-redecide against the fresh
 *  view; a blind resubmit would do exactly the silent clobbering the CAS
 *  exists to prevent. Pure; the discriminator callers branch on. */
internal fun isStaleConflict(t: Throwable): Boolean =
    t is ApiHttpException && t.status == 409

/** StreamAuthException: the live stream was refused with 401/403 — the session
 *  cookie is gone or insufficient. Permanent: reconnecting cannot fix it, the
 *  caller must stop its retry loop and surface the failure (the app's existing
 *  401 posture — an explicit error, like Api.me()/orThrow — not a silent loop). */
class StreamAuthException(val status: Int) : IllegalStateException(
    if (status == 401) "authentication expired (401) — sign in again" else "access denied (403)",
)

/** StreamProtocolException: the endpoint answered success but not with an SSE
 *  stream — a 204 without a body, or a proxy/captive portal's 200 HTML/JSON.
 *  Permanent for this URL: retrying re-fetches the same wrong document. */
class StreamProtocolException(message: String) : IllegalStateException(message)

/** isPermanentStreamError classifies a live-stream failure for the reconnect
 *  loop: permanent errors (auth, wrong protocol) stop it and surface; everything
 *  else — EOF, resets, 5xx, over-limit lines — retries with capped backoff. */
internal fun isPermanentStreamError(t: Throwable): Boolean =
    t is StreamAuthException || t is StreamProtocolException

// ---- /parts live-stream resume cursor (control-plane-api.md § live-stream resume) ----

/** The SSE resume request header (what EventSource would send). */
internal const val LAST_EVENT_ID_HEADER = "Last-Event-ID"

/** The stream epoch announced on every replay=1 handshake. */
internal const val PARTS_EPOCH_HEADER = "X-Parts-Stream-Epoch"

/** The server's answer to a Last-Event-ID: "resume" or "gap". */
internal const val PARTS_REPLAY_HEADER = "X-Parts-Replay"

/** The transcript snapshot watermark — directly usable as a Last-Event-ID. */
internal const val TRANSCRIPT_SEQ_HEADER = "X-Transcript-Stream-Seq"

/** eventIdEpoch extracts the epoch of one `<epoch>-<seq>` stream id; null when
 *  the id doesn't have that shape. A malformed id must never crash the stream —
 *  it just cannot prove epoch continuity, so a resume claim over it is refused
 *  and the caller falls back to the truth-up path. Pure; unit-tested. */
internal fun eventIdEpoch(id: String): String? {
    val cut = id.lastIndexOf('-')
    if (cut <= 0 || cut == id.length - 1) return null
    for (i in cut + 1 until id.length) if (id[i] !in '0'..'9') return null
    return id.substring(0, cut)
}

/** resumedHandshake decides whether one validated /parts handshake RESUMED the
 *  stream after [sentId]: every sequenced frame since it will be delivered
 *  exactly once, so the reconnect-time truth-up re-read may be skipped — the
 *  point of the resume cursor. Resume is claimed only by an explicit
 *  `X-Parts-Replay: resume` answering an id we actually sent, cross-checked
 *  against the announced epoch when present (an epoch change invalidates the
 *  id even if an intermediary mangled the verdict). Everything else — gap, a
 *  stripped header (the contract says assume gap), an old server that sent
 *  neither ids nor headers, no id sent — reports false: the caller must
 *  reconcile from a fresh snapshot, the pre-cursor status quo. Pure. */
internal fun resumedHandshake(sentId: String?, epoch: String?, replay: String?): Boolean =
    sentId != null && replay.equals("resume", ignoreCase = true) &&
        (epoch == null || eventIdEpoch(sentId) == epoch)

/** SseLineReader reads newline-delimited SSE lines with a STRICT encoded-byte
 *  ceiling, counted BEFORE any String is materialized. Ktor's readUTF8Line(max)
 *  is only a soft bound: it checks the running total while the delimiter hasn't
 *  been seen yet, so a line whose newline and over-limit tail arrive in the same
 *  buffer is accepted past the declared max (measured +47 bytes on the OkHttp
 *  path). This reader owns the bytes: it scans for '\n' in its own buffer and
 *  throws once more than [maxBytes] line bytes accumulate without a delimiter —
 *  a legal line (≤ maxBytes + the newline) always fits the capped buffer, so the
 *  budget is exact and nothing over it is ever decoded. */
internal class SseLineReader(private val ch: ByteReadChannel, private val maxBytes: Int) {
    private var buf = ByteArray(minOf(8 * 1024, maxBytes + 1))
    private var start = 0
    private var end = 0
    private var eof = false

    /** readLine returns the next line without its terminator (\r\n or \n), the
     *  final unterminated tail at EOF, or null once drained. */
    suspend fun readLine(): String? {
        var scanFrom = start
        while (true) {
            var nl = -1
            var i = scanFrom
            while (i < end) {
                if (buf[i] == NL) { nl = i; break }
                i++
            }
            if (nl >= 0) {
                var lineEnd = nl
                if (lineEnd > start && buf[lineEnd - 1] == CR) lineEnd--
                if (lineEnd - start > maxBytes) throw IllegalStateException(overLimit()) // defensive; capacity forbids it
                val line = buf.decodeToString(start, lineEnd)
                start = nl + 1
                return line
            }
            scanFrom = end
            if (eof) {
                if (start == end) return null
                if (end - start > maxBytes) throw IllegalStateException(overLimit())
                val line = buf.decodeToString(start, end)
                start = end
                return line
            }
            // The whole buffered run is one unterminated line: enforce the budget
            // on the byte count, before any decode.
            if (end - start > maxBytes) throw IllegalStateException(overLimit())
            if (end == buf.size) {
                if (start > 0) {
                    // Compact the pending line to the front to make room.
                    buf.copyInto(buf, 0, start, end)
                    end -= start
                    scanFrom -= start
                    start = 0
                } else {
                    // Grow toward the hard cap: maxBytes line bytes + 1 delimiter.
                    buf = buf.copyOf(minOf(buf.size.toLong() * 2, maxBytes.toLong() + 1).toInt())
                }
            }
            val n = ch.readAvailable(buf, end, buf.size - end)
            if (n == -1) eof = true else end += n
        }
    }

    private fun overLimit() = "SSE line exceeds $maxBytes bytes"

    private companion object {
        const val NL = '\n'.code.toByte()
        const val CR = '\r'.code.toByte()
    }
}

/** TranscriptPage fuses a transcript page body with its X-Transcript-* cursor
 *  and, when the node has a live /parts feed, the snapshot watermark
 *  ([streamSeq], `<epoch>-<seq>` captured BEFORE the page was read) — the
 *  Last-Event-ID that closes the snapshot→subscribe gap. null on an old server
 *  or while no live feed exists; `<epoch>-0` is legal (a silent session). */
data class TranscriptPage(
    val turns: List<Turn>,
    val total: Int,
    val start: Int,
    val hasMore: Boolean,
    val streamSeq: String? = null,
)

private fun String.enc(): String = this.encodeURLPathPart()

// runCatchingCancellable is runCatching that never swallows coroutine
// cancellation. kotlin.runCatching catches Throwable, and CancellationException
// is a Throwable, so a cancelled effect wrapped in runCatching falls into
// onFailure and keeps running its non-suspend tail — writing loading=false,
// publishing a stale snapshot, or rendering a navigation-cancel as a business
// error. This rethrows CancellationException so the cancelled coroutine tears
// down, and only turns genuine failures into Result.failure. Used at every
// suspend-call site across the effects/handlers instead of bare runCatching.
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Result.failure(t)
    }
