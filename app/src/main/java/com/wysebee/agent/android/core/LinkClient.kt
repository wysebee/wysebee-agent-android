package com.wysebee.agent.android.core

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call as HttpCall
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One call the cloud handed out. */
data class Call(val id: String, val op: String, val args: JSONObject)

/** A refusal from the cloud: its status, its `code` and its English `detail`. */
class CloudRefusal(val status: Int, val code: String?, message: String, val body: JSONObject = JSONObject()) : IOException(message)

/** The Wysebee account a QR code was shown by: its name and email, either may be blank. */
data class Account(val name: String, val email: String)

/** The link protocol's HTTP calls: preview a QR code's offer, start, poll, result, unpair. */
class LinkClient(private val http: OkHttpClient) {
    sealed interface Poll {
        data class Pending(val expiresIn: Int) : Poll
        data class Paired(val agentName: String, val agentLabel: String, val calls: List<Call>, val cancelled: List<String>) : Poll
        data class Refused(val reason: String, val message: String) : Poll
        data object Unpaired : Poll
        data object Expired : Poll
    }

    /** A pairing started: its code to show — or, started with a QR code's offer, the agent it
     *  was paired with at once (and no code). */
    data class Started(val id: String, val code: String, val expiresIn: Int, val agent: Pair<String, String>? = null)

    /** Whose QR code [offer] is, before pairing with it: looking takes nothing.
     *  Null when the cloud is older than this call; refused `offer_unknown` when it is no offer. */
    suspend fun previewOffer(cloud: String, offer: String): Account? {
        val o = try {
            send(post("$cloud$PREFIX/offers/preview", JSONObject().put("offer", offer), null))
        } catch (e: CloudRefusal) {
            // A cloud without the call answers 404 or 405 with no code of ours.
            if (e.code == null && (e.status == 404 || e.status == 405)) return null
            throw e
        }
        val user = o.optJSONObject("user") ?: return null
        return Account(user.optString("name").trim(), user.optString("email").trim())
    }

    suspend fun start(cloud: String, kind: String, name: String, secretHash: String, device: String, version: String, offer: String? = null, reconnect: Boolean = false): Started {
        val body = JSONObject().put("kind", kind).put("name", name).put("secret_hash", secretHash)
            .put("browser", device).put("extension_version", version)
        if (offer != null) body.put("offer", offer)
        if (reconnect) body.put("reconnect", true)
        val o = send(post("$cloud$PREFIX/start", body, null))
        val agent = o.optJSONObject("agent")?.let { a -> a.optString("name").takeIf(String::isNotEmpty)?.let { it to a.optString("label").ifEmpty { it } } }
        return Started(o.getString("id"), if (o.isNull("code")) "" else o.optString("code"), o.optInt("expires_in", 600), agent)
    }

    suspend fun poll(cloud: String, id: String, secret: String, status: JSONObject, wait: Int): Poll {
        val req = post("$cloud$PREFIX/$id/poll?wait=$wait", JSONObject().put("status", status), secret)
        val o = try {
            send(req)
        } catch (e: CloudRefusal) {
            return when (e.status) {
                401 -> Poll.Unpaired
                410 -> Poll.Expired
                else -> throw e
            }
        }
        return when (o.optString("state")) {
            "pending" -> Poll.Pending(o.optInt("expires_in", 0))
            "refused" -> Poll.Refused(o.optString("reason"), o.optString("message").ifEmpty { "Wysebee refused the pairing." })
            "paired" -> {
                val agent = o.optJSONObject("agent") ?: JSONObject()
                val calls = o.optJSONArray("calls") ?: JSONArray()
                val cancelled = o.optJSONArray("cancelled") ?: JSONArray()
                Poll.Paired(
                    agent.optString("name"), agent.optString("label").ifEmpty { agent.optString("name") },
                    (0 until calls.length()).mapNotNull { i ->
                        val c = calls.optJSONObject(i) ?: return@mapNotNull null
                        val cid = c.optString("id").ifEmpty { return@mapNotNull null }
                        Call(cid, c.optString("op"), c.optJSONObject("args") ?: JSONObject())
                    },
                    (0 until cancelled.length()).map { cancelled.optString(it) }.filter(String::isNotEmpty),
                )
            }
            else -> throw IOException("The cloud answered the poll with something unknown.")
        }
    }

    /** Answers a call; a call that is gone (404) is dropped. */
    suspend fun result(cloud: String, id: String, secret: String, callId: String, outcome: Outcome) {
        val body = when (outcome) {
            is Outcome.Ok -> JSONObject().put("ok", true).put("result", outcome.result)
            is Outcome.Failed -> JSONObject().put("ok", false).put("error", outcome.error)
        }
        try {
            send(post("$cloud$PREFIX/$id/calls/$callId/result", body, secret))
        } catch (e: CloudRefusal) {
            if (e.status != 404) throw e
        }
    }

    suspend fun unpair(cloud: String, id: String, secret: String) {
        try {
            send(Request.Builder().url("$cloud$PREFIX/$id").delete().header("Authorization", "Bearer $secret").build())
        } catch (e: CloudRefusal) {
            if (e.status != 401 && e.status != 404) throw e
        }
    }

    private fun post(url: String, body: JSONObject, secret: String?): Request =
        Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).apply {
            if (secret != null) header("Authorization", "Bearer $secret")
        }.build()

    private suspend fun send(request: Request): JSONObject {
        http.newCall(request).await().use { res ->
            val text = res.body?.string().orEmpty()
            val o = runCatching { if (text.isBlank()) JSONObject() else JSONObject(text) }.getOrElse { JSONObject() }
            if (!res.isSuccessful) {
                throw CloudRefusal(res.code, o.optString("code").ifEmpty { null },
                    o.optString("detail").ifEmpty { "Wysebee Cloud answered ${res.code}." }, o)
            }
            return o
        }
    }

    companion object {
        const val PREFIX = "/api/v1/agent-links"
        private val JSON = "application/json".toMediaType()

        /** No redirects: the secret goes only to the cloud the link was made with. */
        fun client(base: OkHttpClient = OkHttpClient()): OkHttpClient = base.newBuilder()
            .followRedirects(false).followSslRedirects(false)
            .readTimeout(java.time.Duration.ofSeconds(45))
            .build()
    }
}

suspend fun HttpCall.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: HttpCall, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
        override fun onResponse(call: HttpCall, response: Response) {
            cont.resume(response) { _, _, _ -> response.close() }
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
