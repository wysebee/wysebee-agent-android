package com.wysebee.agent.android.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Android's System UI: the notification shade, quick settings and the lock screen. */
const val SYSTEM_UI = "com.android.systemui"

/** An app the person allowed the agent to operate. */
data class AllowedApp(val pkg: String, val label: String)

/** A pairing started here, its code not typed in Wysebee yet. */
data class Pending(val id: String, val code: String, val expiresAt: Long, val name: String, val cloudUrl: String)

/** The link: made with one cloud, and only ever talked to there. */
data class Link(val id: String, val cloudUrl: String, val agentName: String, val agentLabel: String)

/** Everything this app keeps, bar the secret (SecretStore). */
data class State(
    val pending: Pending? = null,
    val link: Link? = null,
    val paused: Boolean = false,
    val allowed: List<AllowedApp> = emptyList(),
    /** The phone's System UI — the notification shade and quick settings — besides the apps
     *  (2026-11-17, the owner's call: a switch of its own, off until the person turns it on). */
    val systemUi: Boolean = false,
    /** The server the person chose to pair with (2026-11-22), or null for this build's default.
     *  A link and a pending pairing keep their own. */
    val server: String? = null,
) {
    /** The server this phone is paired with, is pairing with, or would pair with. */
    fun cloudUrl(default: String): String = link?.cloudUrl ?: pending?.cloudUrl ?: server ?: default

    fun mayOperate(pkg: String, own: String): Boolean =
        pkg != own && (allowed.any { it.pkg == pkg } || (systemUi && pkg == SYSTEM_UI))

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        pending?.let {
            put("pending", JSONObject().put("id", it.id).put("code", it.code).put("expires_at", it.expiresAt)
                .put("name", it.name).put("cloud_url", it.cloudUrl))
        }
        link?.let {
            put("link", JSONObject().put("id", it.id).put("cloud_url", it.cloudUrl)
                .put("agent_name", it.agentName).put("agent_label", it.agentLabel))
        }
        put("paused", paused)
        put("system_ui", systemUi)
        server?.let { put("server", it) }
        put("allowed", JSONArray().apply { allowed.forEach { put(JSONObject().put("package", it.pkg).put("label", it.label)) } })
    }

    companion object {
        fun fromJson(o: JSONObject): State {
            val p = o.optJSONObject("pending")
            val l = o.optJSONObject("link")
            val a = o.optJSONArray("allowed") ?: JSONArray()
            return State(
                pending = p?.let { Pending(it.getString("id"), it.getString("code"), it.getLong("expires_at"), it.getString("name"), it.getString("cloud_url")) },
                link = l?.let { Link(it.getString("id"), it.getString("cloud_url"), it.getString("agent_name"), it.getString("agent_label")) },
                paused = o.optBoolean("paused", false),
                systemUi = o.optBoolean("system_ui", false),
                server = CloudAddress.normalize(o.optString("server").ifEmpty { null }),
                // `allow_all` (an "Every app" switch until 2026-11-17) is no more: only apps listed.
                allowed = (0 until a.length()).mapNotNull { i ->
                    a.optJSONObject(i)?.let { e -> e.optString("package").takeIf(String::isNotEmpty)?.let { AllowedApp(it, e.optString("label").ifEmpty { it }) } }
                }.distinctBy { it.pkg },
            )
        }
    }
}

/** The state file: written beside and renamed over, only when it changed. */
class StateStore(private val file: File) {
    private var last: String? = null

    fun load(): State = try {
        val text = file.readText()
        last = text
        State.fromJson(JSONObject(text))
    } catch (_: Exception) {
        State()
    }

    @Synchronized
    fun save(state: State) {
        val text = state.toJson().toString(2)
        if (text == last) return
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not write ${file.name}" }
        }
        last = text
    }
}
