package io.hero.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// v1 control-plane API types (see the backend's docs/control-plane-api.md).
// ignoreUnknownKeys is set on the Json instance, so additive fields are safe.

@Serializable
data class NodeView(
    val node_id: String,
    val os: String = "",
    val version: String = "",
    val harnesses: List<String> = emptyList(),
    val scope: String = "",
    val connected: Boolean = false,
    val connected_at: String = "",
    val enrolled: Boolean = false,
    val owner: String = "",
    val shared_with: List<String> = emptyList(),
    // Machine health, present only while connected and reported (nodes without
    // the Metrics RPC omit these — null/zero means "not reported", never 0%).
    val cpu_percent: Double? = null,
    val mem_used: Long = 0,
    val mem_total: Long = 0,
    val disk_used: Long = 0,
    val disk_total: Long = 0,
) {
    val hasMetrics: Boolean get() = cpu_percent != null || mem_total > 0 || disk_total > 0
}

@Serializable
data class Session(
    val id: String,
    val title: String = "",
    val backend: String = "",
    val cwd: String = "",
    val status: String = "",
)

@Serializable
data class Event(
    val session_id: String = "",
    val type: String = "",
    val raw: JsonElement? = null,
)

// ---- structured "display window" (mirrors the node's jsonl.Turn / jsonl.TurnPart) ----
// These are the harness-NEUTRAL projection both backends normalize into (the
// "narrow waist"). The app renders these; it never parses raw jsonl. `type` and
// `role` are deliberately plain String (not enums) so a future kind emitted by a
// newer node deserializes fine and falls through to the renderer's default branch.

@Serializable
data class TurnPart(
    val type: String = "text",              // open set: "text" | "tool" | <future>
    val content: String = "",               // markdown source (text) or tool output
    val toolName: String? = null,
    val toolTarget: String? = null,
    val childSessionId: String? = null,     // P1a: subagent/agent-team drill-in target
    val workflow: WorkflowInfo? = null,     // P1b: dynamic-workflow summary (Claude "Workflow" tool)
)

@Serializable
data class WorkflowInfo(
    val name: String = "",
    val summary: String = "",
    val status: String = "",                // running | completed | failed | …
    val phases: List<WorkflowPhase> = emptyList(),
    val agentCount: Int = 0,
    val totalTokens: Long = 0,
    val totalToolCalls: Int = 0,
    val runId: String = "",
)

@Serializable
data class WorkflowPhase(val title: String = "")

@Serializable
data class Turn(
    val role: String = "",                  // "user" | "assistant" | "system" (+ client-only "error")
    val content: String? = null,            // user / system turns
    val parts: List<TurnPart> = emptyList(),// assistant turns
    val ts: String = "",
    val model: String? = null,
    val uuid: String? = null,               // assistant fork-point; stable list key
)

// LiveFrame is the typed form of one grouped SSE frame, decoded from Event by
// decodeLiveFrame. Not @Serializable — the discriminator lives on Event.type and
// the body shape varies per type, so it is mapped by hand.
sealed interface LiveFrame {
    data class Part(val part: TurnPart, val ts: String?, val model: String?) : LiveFrame
    data class UserTurn(val content: String, val ts: String?) : LiveFrame
    data class Delta(val text: String) : LiveFrame
    data class Status(val status: String) : LiveFrame
    data class Exit(val reason: String?, val assistantUuid: String?, val assistantTs: String?) : LiveFrame
    data class Runtime(val model: String?, val contextWindow: Int?) : LiveFrame
    data class ErrorFrame(val message: String) : LiveFrame
    data object TurnActive : LiveFrame
    data object TurnIdle : LiveFrame
    data class Unknown(val type: String) : LiveFrame   // forward-compat sink; never breaks the stream
}

// Body DTOs for the grouped frame payloads (Event.raw). snake_case matches the
// wire keys — the house style in this file.
@Serializable private data class PartBody(val ts: String? = null, val model: String? = null, val part: TurnPart)
@Serializable private data class UserBody(val content: String = "", val ts: String? = null)
@Serializable private data class DeltaBody(val delta: String = "")
@Serializable private data class StatusBody(val status: String = "")
@Serializable private data class ExitBody(val reason: String? = null, val assistant_uuid: String? = null, val assistant_ts: String? = null)
@Serializable private data class RuntimeBody(val model: String? = null, val context_window: Int? = null)
@Serializable private data class ErrorBody(val message: String = "")

/**
 * decodeLiveFrame maps one node event envelope to a typed LiveFrame. The
 * discriminator is [Event.type], the payload is [Event.raw]. An unknown type maps
 * to [LiveFrame.Unknown]; a malformed body returns null — neither ever throws, so
 * one bad frame can't tear down the live stream. Pure (no I/O) for unit testing.
 */
internal fun decodeLiveFrame(ev: Event, json: Json): LiveFrame? {
    val raw = ev.raw
    return runCatching {
        when (ev.type) {
            "part" -> raw?.let { json.decodeFromJsonElement(PartBody.serializer(), it) }
                ?.let { LiveFrame.Part(it.part, it.ts, it.model) }
            "turn.user" -> raw?.let { json.decodeFromJsonElement(UserBody.serializer(), it) }
                ?.let { LiveFrame.UserTurn(it.content, it.ts) }
            "part.delta" -> raw?.let { LiveFrame.Delta(json.decodeFromJsonElement(DeltaBody.serializer(), it).delta) }
            "turn.status" -> raw?.let { LiveFrame.Status(json.decodeFromJsonElement(StatusBody.serializer(), it).status) }
            "subprocess.exit" -> (raw?.let { json.decodeFromJsonElement(ExitBody.serializer(), it) } ?: ExitBody())
                .let { LiveFrame.Exit(it.reason, it.assistant_uuid, it.assistant_ts) }
            "session.runtime" -> raw?.let { json.decodeFromJsonElement(RuntimeBody.serializer(), it) }
                ?.let { LiveFrame.Runtime(it.model, it.context_window) }
            "error" -> LiveFrame.ErrorFrame(raw?.let { json.decodeFromJsonElement(ErrorBody.serializer(), it).message } ?: "error")
            "turn.active" -> LiveFrame.TurnActive
            "turn.idle" -> LiveFrame.TurnIdle
            "subprocess.started" -> null    // no grouped-view effect
            else -> LiveFrame.Unknown(ev.type)
        }
    }.getOrNull()
}

@Serializable
data class SendReq(val text: String, val model: String = "", val effort: String = "")

@Serializable
data class Me(val user: String = "", val admin: Boolean = false)

// ---- management types (mirror the control plane's management API) ----

@Serializable
data class UserInfo(
    val user: String,
    val admin: Boolean = false,
    val owns: List<String> = emptyList(),
    val shared: List<String> = emptyList(),
)

@Serializable
data class NodeAccess(
    val node_id: String,
    val owner: String = "",
    val shared_with: List<String> = emptyList(),
    val enrolled: Boolean = false,
)

@Serializable
data class JoinResult(
    val script: String = "",
    val node_id: String = "",
    val owner: String = "",
    val expires_at: String = "",
)

@Serializable
data class HarnessState(
    val config_path: String = "",
    val pull_managed: Boolean = false,
    val pull_url: String = "",
    val config: JsonElement? = null,
    val backends: List<HarnessBackend> = emptyList(),
)

@Serializable
data class HarnessBackend(
    val backend: String,
    val enabled: Boolean = false,
    val home: String = "",
    val installed_version: String = "",
    val version_status: String = "",
    val version_range: String = "",
    val install_hint: String = "",
    val installing: Boolean = false,
    val catalog: HarnessCatalog = HarnessCatalog(),
)

@Serializable
data class HarnessCatalog(
    val models: List<HarnessModel> = emptyList(),
    val default: String = "",
    val default_effort: String = "",
    val custom_provider: Boolean = false,
    val provider_name: String = "",
    val base_url: String = "",
    val supports_effort: Boolean = false,
    val effort_levels: List<String> = emptyList(),
    val live_error: String = "",
)

@Serializable
data class HarnessModel(
    val slug: String,
    val label: String = "",
    val default_effort: String = "",
    val hidden: Boolean = false,
)

@Serializable
data class Pending(
    val id: String,
    val session_id: String = "",
    val tool_name: String = "",
    val event: String = "",
    // tool_input is the raw backend request payload; for AskUserQuestion it holds
    // the questions. allow_always is true only when the backend advertised a
    // native "always" rule, gating the "Allow always" (session scope) action.
    val tool_input: JsonElement? = null,
    val allow_always: Boolean = false,
)

// AttentionItem is one entry in the cross-fleet attention inbox (GET
// /api/attention): an actionable permission/question or an informational
// finished/errored turn, stamped with its owning node. Mirrors core.AttentionItem.
@Serializable
data class AttentionItem(
    val node: String = "",
    val session_id: String = "",
    val kind: String = "",           // permission | question | finished | errored
    val id: String = "",
    val tool_name: String = "",
    val tool_input: JsonElement? = null,
    val allow_always: Boolean = false,
    val event: String = "",
    val title: String = "",
    val detail: String = "",
    val created_at: String = "",
)

// AskQuestion / AskOption model an AskUserQuestion tool_input so the inbox can
// render option buttons. Parsed from AttentionItem.tool_input when kind=="question".
@Serializable
data class AskQuestion(
    val question: String = "",
    val header: String = "",
    val multiSelect: Boolean = false,
    val options: List<AskOption> = emptyList(),
)

@Serializable
data class AskOption(val label: String = "", val description: String = "", val preview: String = "")

@Serializable
data class AuditRecord(
    val ts: String = "",
    val action: String = "",
    val user: String = "",
    val node: String = "",
    val detail: String = "",
)

// Request bodies.
@Serializable
data class CreateUserReq(val user: String, val password: String, val admin: Boolean = false)
@Serializable
data class PasswordReq(val password: String)
@Serializable
data class AdminReq(val admin: Boolean)
@Serializable
data class ShareReq(val user: String)
@Serializable
data class JoinReq(val node_id: String = "", val ttl_seconds: Int = 3600)
@Serializable
data class BackendReq(val backend: String = "")
@Serializable
// backend pins the harness explicitly; "" lets the node infer it from the
// model (legacy behavior — wrong on dual-harness nodes with a blank model).
data class StartSessionReq(val cwd: String = "", val initial_message: String = "", val model: String = "", val backend: String = "")
// RespondReq resolves a pending interaction. scope "session" is the "allow
// always" choice (valid only when the pending advertised allow_always); answers
// resolves an AskUserQuestion (behavior "allow"). Defaults are omitted on the
// wire (encodeDefaults is off), so a plain RespondReq("allow") is unchanged.
@Serializable
data class RespondReq(
    val behavior: String,
    val scope: String = "",
    val reason: String = "",
    val answers: Map<String, String>? = null,
)
@Serializable
data class MessageResult(val message: String = "")

// PushSub is what the app POSTs to /api/push/subscribe. A UnifiedPush/Web Push
// registration fills endpoint+keys; a native FCM registration fills fcm_token.
// The control plane stamps the owner from the session — never from here.
@Serializable
data class PushKeys(val p256dh: String = "", val auth: String = "")
@Serializable
data class PushSub(
    val endpoint: String = "",
    val keys: PushKeys = PushKeys(),
    val fcm_token: String = "",
)

// ---- control-plane self-update (the CP binary itself; nodes/harnesses are
// managed separately). Mirrors GET/PUT /api/fleet/hero. ----
@Serializable
data class HeroFleet(
    val version: String = "",                        // published target version
    val running: String = "",                        // the control plane's own running version
    val auto_update: Boolean = false,
    val defined: Boolean = false,                    // something has been published
    val platforms: Map<String, HeroBinEntry> = emptyMap(),
)

@Serializable
data class HeroBinEntry(val sha256: String = "", val size: Long = 0)

@Serializable
data class AutoUpdateReq(val auto_update: Boolean)

@Serializable
data class AutoUpdateResp(val auto_update: Boolean = false, val version: String = "")
