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
