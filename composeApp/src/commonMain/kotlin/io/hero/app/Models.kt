package io.hero.app

import kotlinx.serialization.Serializable
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
)

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

@Serializable
data class SendReq(val text: String)

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
)

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
data class StartSessionReq(val cwd: String = "", val initial_message: String = "", val model: String = "")
@Serializable
data class RespondReq(val behavior: String)
@Serializable
data class MessageResult(val message: String = "")
