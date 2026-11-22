package com.wysebee.agent.android.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/** A Wysebee Cloud on 127.0.0.1 that speaks the link's routes, as wysebee-agent-osx's tests/common. */
class FakeCloud : Dispatcher() {
    val server = MockWebServer().also { it.dispatcher = this; it.start() }
    val url: String get() = server.url("").toString().trimEnd('/')
    @Volatile var claimed = false
    @Volatile var startStatus = 200
    @Volatile var pollStatus = 200
    @Volatile var secretHash: String? = null
    val calls = ConcurrentLinkedQueue<JSONObject>()
    val cancelled = ConcurrentLinkedQueue<String>()
    val results = CopyOnWriteArrayList<JSONObject>()
    val statuses = CopyOnWriteArrayList<JSONObject>()
    val starts = CopyOnWriteArrayList<JSONObject>()
    @Volatile var unpaired = false
    /** Names taken, and whether the agent of each may be reconnected. */
    val taken = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    /** Whose offer it is; null: a cloud older than the preview, which does not know the call. */
    @Volatile var owner: Account? = Account("Ada", "ada@example.com")
    val previews = CopyOnWriteArrayList<JSONObject>()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path ?: ""
        val body = request.body.readUtf8().let { if (it.isBlank()) JSONObject() else JSONObject(it) }
        val auth = request.getHeader("Authorization")?.removePrefix("Bearer ")
        val p = LinkClient.PREFIX
        if (path == "$p/offers/preview") {
            previews += body
            val who = owner ?: return json(404, JSONObject().put("detail", "Not Found"))
            if (body.optString("offer") != OFFER) return json(404, JSONObject().put("detail", "That QR code is not one Wysebee is waiting for.").put("code", "offer_unknown"))
            return json(200, JSONObject().put("user", JSONObject().put("name", who.name).put("email", who.email)).put("expires_in", 500))
        }
        if (path == "$p/start") {
            starts += body
            if (startStatus != 200) return json(startStatus, JSONObject().put("detail", "No such kind.").put("code", "invalid_kind"))
            if (body.has("offer")) {
                if (body.getString("offer") != OFFER) return json(404, JSONObject().put("detail", "That QR code is not one Wysebee is waiting for.").put("code", "offer_unknown"))
                if (taken.containsKey(body.getString("name")) && !(body.optBoolean("reconnect") && taken[body.getString("name")] == true)) {
                    return json(409, JSONObject().put("detail", "There is already an agent with that name.").put("code", "name_taken")
                        .put("agent", JSONObject().put("name", "ada-android").put("label", body.getString("name"))).put("reconnect", taken[body.getString("name")]))
                }
                secretHash = body.getString("secret_hash")
                claimed = true
                return json(200, JSONObject().put("id", "link1").put("code", JSONObject.NULL).put("expires_in", 0)
                    .put("agent", JSONObject().put("name", "phone").put("label", "Phone")))
            }
            secretHash = body.getString("secret_hash")
            return json(200, JSONObject().put("id", "link1").put("code", "ABCDEFGH").put("expires_in", 600))
        }
        if (auth == null || Secrets.sha256Hex(auth) != secretHash) return json(401, JSONObject().put("code", "unpaired"))
        return when {
            path.startsWith("$p/link1/poll") -> {
                statuses += body.getJSONObject("status")
                if (pollStatus != 200) return json(pollStatus, JSONObject().put("code", "x"))
                if (!claimed) return json(200, JSONObject().put("state", "pending").put("expires_in", 500))
                val out = JSONArray()
                while (true) out.put(calls.poll() ?: break)
                val gone = JSONArray()
                while (true) gone.put(cancelled.poll() ?: break)
                if (out.length() == 0 && gone.length() == 0) Thread.sleep(20)
                json(200, JSONObject().put("state", "paired").put("agent", JSONObject().put("name", "phone").put("label", "Phone"))
                    .put("calls", out).put("cancelled", gone))
            }
            path.startsWith("$p/link1/calls/") && path.endsWith("/result") -> {
                results += body.put("call", path.split("/")[6])
                MockResponse().setResponseCode(204)
            }
            path == "$p/link1" && request.method == "DELETE" -> { unpaired = true; MockResponse().setResponseCode(204) }
            else -> json(404, JSONObject().put("code", "nope"))
        }
    }

    private fun json(code: Int, o: JSONObject) = MockResponse().setResponseCode(code).setBody(o.toString())

    companion object {
        /** The one offer this cloud knows. */
        val OFFER = "O".repeat(43)
    }
}

class AgentTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var cloud: FakeCloud
    private lateinit var scope: CoroutineScope
    private val secrets = MemorySecrets()
    private val device = FakeDevice()
    private lateinit var agent: Agent

    @Before fun setUp() {
        cloud = FakeCloud()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        agent = make()
        agent.start()
    }

    private fun make(store: StateStore = StateStore(File(tmp.root, "state.json")), keep: SecretStore = secrets, at: String = cloud.url) = Agent(
        store, keep, LinkClient(LinkClient.client(OkHttpClient())),
        { device }, "own.pkg", at, "Pixel", "0.1.0", scope, Timing(wait = 0, backoff = listOf(20)),
    )

    @After fun tearDown() {
        scope.cancel()
        cloud.server.shutdown()
    }

    private fun until(what: String, test: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) { while (!test()) delay(10) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what (status ${agent.status.value})")
        }
    }

    private fun pair() = runBlocking {
        assertNull(agent.startPairing("  Phone  "))
        assertEquals("ABCDEFGH", agent.state.pending?.code)
        cloud.claimed = true
        until("paired") { agent.state.link != null }
    }

    @Test fun pairsAsAnAndroidAgent() {
        pair()
        val start = cloud.starts.single()
        assertEquals("wysebee-android", start.getString("kind"))
        assertEquals("Phone", start.getString("name"))
        assertEquals("Pixel", start.getString("browser"))
        assertEquals(Link("link1", cloud.url, "phone", "Phone"), agent.state.link)
        assertEquals(cloud.url, agent.cloudUrl())
        until("connected") { agent.status.value.connected }
        assertEquals("Pixel", cloud.statuses.last().getString("browser"))
        assertEquals(false, cloud.statuses.last().getBoolean("paused"))
        // Kept across a restart.
        assertEquals(agent.state, StateStore(File(tmp.root, "state.json")).load())
    }

    @Test fun aSecretThatCannotBeKeptIsSaidSoAndNoCodeIsMade() = runBlocking {
        val broken = object : SecretStore {
            override fun get(): String? = null
            override fun set(secret: String) = throw java.io.FileNotFoundException("secret.bin.tmp: open failed: ENOENT")
            override fun clear() {}
        }
        val said = make(keep = broken).startPairing("Phone")!!
        assertTrue(said, said.startsWith("This phone could not keep the pairing") && said.contains("ENOENT"))
        assertTrue(cloud.starts.isEmpty())
    }

    @Test fun aStateFileThatCannotBeWrittenIsSaidSo() = runBlocking {
        // Its folder is a file: nothing can be written under it.
        val blocked = File(tmp.root, "blocked").apply { writeText("") }
        val other = make(store = StateStore(File(blocked, "state.json")))
        val said = other.startPairing("Phone")!!
        assertTrue(said, said.startsWith("This phone could not keep the pairing"))
        assertNull(other.state.pending)
        assertNull(secrets.get())
    }

    @Test fun anUnreachableCloudIsSaidSoAndForgetsTheSecret() = runBlocking {
        val said = make(at = "http://127.0.0.1:1").startPairing("Phone")!!
        assertTrue(said, said.startsWith("Wysebee Cloud (127.0.0.1) could not be reached"))
        assertNull(secrets.get())
    }

    private fun qrFrom(cloudUrl: String?, offer: String = FakeCloud.OFFER) =
        "wysebee-agent:pair?offer=$offer" + (cloudUrl?.let { "&from=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")

    private suspend fun scanAndPair(a: Agent, name: String, text: String?): String? = a.scan(text) ?: a.pairScanned(name)

    @Test fun aScanNeedsNoNameAndPairsWithTheCloudThatShowedIt() = runBlocking {
        // This phone's own server is elsewhere (and unreachable): the QR code's cloud is used.
        val other = make(at = "http://127.0.0.1:1").also { it.start() }
        assertNull(other.scan(qrFrom(cloud.url + "/")))
        // Whose QR code it is, asked of the cloud that showed it, for the person to confirm.
        assertEquals(Scanned(PairingQr(FakeCloud.OFFER, cloud.url + "/"), cloud.url, Account("Ada", "ada@example.com")), other.status.value.scanned)
        assertEquals(FakeCloud.OFFER, cloud.previews.single().getString("offer"))
        // Nothing is paired until a name is typed.
        assertTrue(cloud.starts.isEmpty())
        assertNotNull(other.pairScanned(" "))
        assertNull(other.pairScanned(" Phone "))
        assertEquals(FakeCloud.OFFER, cloud.starts.single().getString("offer"))
        assertEquals("Phone", cloud.starts.single().getString("name"))
        // No code to type: paired there, and that server is this phone's from now on.
        assertNull(other.state.pending)
        assertNull(other.status.value.scanned)
        assertEquals(Link("link1", cloud.url, "phone", "Phone"), other.state.link)
        assertEquals(cloud.url, other.state.server)
        until("connected") { other.status.value.connected }
    }

    @Test fun aQrCodeNamingNoCloudPairsWithThePhonesOwn() = runBlocking {
        assertNull(agent.scan(qrFrom(null)))
        assertEquals(cloud.url, agent.status.value.scanned?.cloud)
        assertNull(agent.pairScanned("Phone"))
        assertEquals(Link("link1", cloud.url, "phone", "Phone"), agent.state.link)
        // The default server is not written down as a choice.
        assertNull(agent.state.server)
    }

    @Test fun aQrCodeThatIsNotOneOrNamesABadCloudOrIsUnknownIsSaidSo() = runBlocking {
        assertEquals("Scan Wysebee's QR code first.", agent.pairScanned("Phone"))
        for (bad in listOf(null, "", "https://example.com", "wysebee-agent:pair?offer=short", "wysebee-agent:pair?from=x")) {
            assertTrue(agent.scan(bad)!!.startsWith("That is not a Wysebee pairing QR code"))
            assertNull(agent.status.value.scanned)
        }
        // Only an https cloud.
        for (bad in listOf("http://evil.example", "https://evil.example/path", "ftp://x.example")) {
            val said = agent.scan(qrFrom(bad))!!
            assertTrue(said, said.startsWith("This QR code names a server Wysebee Agent cannot pair with ($bad)"))
        }
        assertTrue(cloud.starts.isEmpty())
        // An offer the cloud does not know: its own words, said at the scan.
        assertEquals("That QR code is not one Wysebee is waiting for.", agent.scan(qrFrom(cloud.url, "Q".repeat(43))))
        assertNull(agent.status.value.scanned)
        assertTrue(cloud.starts.isEmpty())
        // A cloud that cannot be reached: said, by its host.
        assertTrue(agent.scan(qrFrom("http://127.0.0.1:1"))!!.startsWith("Wysebee Cloud (127.0.0.1) could not be reached"))
        assertNull(agent.status.value.scanned)
        assertNull(agent.state.link)
        assertNull(secrets.get())
        // A scan can be put aside.
        assertNull(agent.scan(qrFrom(cloud.url)))
        agent.cancelScan()
        assertNull(agent.status.value.scanned)
    }

    @Test fun aCloudThatDoesNotSayWhoseQrCodeItIsStillPairs() = runBlocking {
        // Older than the preview: the account is unknown, and the person is told so by the window.
        cloud.owner = null
        assertNull(agent.scan(qrFrom(cloud.url)))
        assertEquals(Scanned(PairingQr(FakeCloud.OFFER, cloud.url), cloud.url, null), agent.status.value.scanned)
        assertNull(agent.pairScanned("Phone"))
        assertEquals(Link("link1", cloud.url, "phone", "Phone"), agent.state.link)
    }

    @Test fun aScanWhoseNameIsTakenMayReconnect() = runBlocking {
        val qr = qrFrom(cloud.url)
        // Taken by an agent that still has its phone: said, with nothing to reconnect.
        cloud.taken["Busy"] = false
        assertEquals("There is already an agent with that name.", scanAndPair(agent, "Busy", qr))
        assertNull(agent.status.value.reconnect)
        assertNotNull(agent.reconnect())
        // The scan waits for another name: no second scan needed.
        assertNotNull(agent.status.value.scanned)
        // Taken by one with no phone: offered, and done with the same QR code.
        cloud.taken["Ada Android"] = true
        val said = agent.pairScanned("Ada Android")!!
        assertTrue(said, said.startsWith("Ada Android (@ada-android) already has that name, and has no phone now."))
        assertEquals(Reconnect("Ada Android", PairingQr(FakeCloud.OFFER, cloud.url), "ada-android", "Ada Android", cloud.url), agent.status.value.reconnect)
        assertNull(agent.state.link)
        assertNull(agent.reconnect())
        assertTrue(cloud.starts.last().getBoolean("reconnect"))
        assertNull(agent.status.value.reconnect)
        assertEquals(Link("link1", cloud.url, "phone", "Phone"), agent.state.link)
    }

    @Test fun anOfferToReconnectCanBeDeclined() = runBlocking {
        cloud.taken["Ada Android"] = true
        scanAndPair(agent, "Ada Android", qrFrom(null))
        assertNotNull(agent.status.value.reconnect)
        agent.declineReconnect()
        assertNull(agent.status.value.reconnect)
        assertNull(agent.status.value.message)
        agent.pairScanned("Ada Android")
        agent.dismissMessage()
        assertNull(agent.status.value.reconnect)
        // A code pairing never offers it.
        assertFalse(cloud.starts.any { it.has("reconnect") })
    }

    @Test fun thePersonMayChooseTheServer() = runBlocking {
        val store = StateStore(File(tmp.root, "chosen.json"))
        val other = make(store = store, at = "https://app.wysebee.example")
        assertEquals("https://app.wysebee.example", other.cloudUrl())
        assertNull(other.setServer("  Wysebee.Example.org/ "))
        assertEquals("https://wysebee.example.org", other.cloudUrl())
        assertEquals("https://wysebee.example.org", store.load().server)
        for (bad in listOf("", "http://wysebee.example.org", "https://wysebee.example.org/app", "https://a b", "https://user@x.example")) {
            assertEquals("Type the server's https address, such as https://app.wysebee.example.", other.setServer(bad))
        }
        assertEquals("https://wysebee.example.org", other.cloudUrl())
        other.resetServer()
        assertNull(other.state.server)
        // This build's own server is not kept as a choice.
        assertNull(other.setServer("https://app.wysebee.example"))
        assertNull(other.state.server)
    }

    @Test fun aCodePairingGoesToTheChosenServerAndItStaysWhilePaired() = runBlocking {
        val other = make(at = "http://127.0.0.1:1").also { it.start() }
        assertNull(other.setServer(cloud.url))
        assertNull(other.startPairing("Phone"))
        assertEquals(cloud.url, other.state.pending?.cloudUrl)
        assertEquals("Unpair this phone before changing its server.", other.setServer("https://elsewhere.example"))
        cloud.claimed = true
        until("paired") { other.state.link != null }
        assertEquals(cloud.url, other.cloudUrl())
        other.unpair()
        // Unpaired, the next pairing goes to the same server.
        assertEquals(cloud.url, other.cloudUrl())
    }

    @Test fun theWindowMaySaySomething() {
        agent.say("The scanner would not open.")
        assertEquals("The scanner would not open.", agent.status.value.message)
    }

    @Test fun badNamesAndRefusedKinds() = runBlocking {
        assertNotNull(agent.startPairing(" "))
        assertNotNull(agent.startPairing("a\nb"))
        assertNotNull(agent.startPairing("x".repeat(61)))
        cloud.startStatus = 422
        assertEquals("This Wysebee Cloud does not pair Android agents yet.", agent.startPairing("Phone"))
        assertNull(agent.state.pending)
        assertNull(secrets.get())
    }

    @Test fun answersCallsWithOps() {
        pair()
        agent.allow(App("com.example", "Example"))
        device.tree = FakeNode().add(FakeNode(text = "Hi", bounds = Bounds(0, 0, 10, 10)))
        cloud.calls += JSONObject().put("id", "c1").put("op", "android_snapshot").put("args", JSONObject())
        cloud.calls += JSONObject().put("id", "c2").put("op", "rm_rf").put("args", JSONObject())
        until("two results") { cloud.results.size == 2 }
        val byId = cloud.results.associateBy { it.getString("call") }
        assertTrue(byId["c1"]!!.getJSONObject("result").getString("snapshot").contains("[1] TextView \"Hi\""))
        assertEquals(false, byId["c2"]!!.getBoolean("ok"))
    }

    @Test fun changingAllowedAppsLosesNoCall() {
        pair()
        // The cloud answers polls slowly with a call in hand; allowing apps meanwhile must not drop it.
        repeat(5) { i ->
            cloud.calls += JSONObject().put("id", "c$i").put("op", "ping").put("args", JSONObject())
            agent.allow(App("com.app$i", "App $i"))
            agent.setPaused(i % 2 == 0)
        }
        until("five results") { cloud.results.size == 5 }
    }

    @Test fun refusesAppsNotAllowed() {
        pair()
        cloud.calls += JSONObject().put("id", "c1").put("op", "android_snapshot").put("args", JSONObject())
        until("a result") { cloud.results.isNotEmpty() }
        assertTrue(cloud.results.single().getString("error").contains("may not operate"))
    }

    @Test fun pauseIsReportedAndRefuses() {
        pair()
        agent.allow(App("com.example", "Example"))
        agent.setPaused(true)
        until("paused reported") { cloud.statuses.lastOrNull()?.optBoolean("paused") == true }
        cloud.calls += JSONObject().put("id", "c1").put("op", "android_key").put("args", JSONObject().put("key", "home"))
        until("a result") { cloud.results.isNotEmpty() }
        assertEquals(Ops.PAUSED, cloud.results.single().getString("error"))
        agent.setPaused(false)
        until("resumed") { cloud.statuses.last().optBoolean("paused") == false }
    }

    @Test fun allowedAppsAreKeptAndOwnNever() {
        pair()
        agent.allow(App("com.example", "Example"))
        agent.allow(App("com.example", "Example"))
        agent.allow(App("own.pkg", "Me"))
        assertEquals(listOf(AllowedApp("com.example", "Example")), agent.state.allowed)
        // Several ticked at once: added in order, none twice, never this app.
        agent.allow(listOf(App("com.b", "B"), App("com.example", "Example"), App("own.pkg", "Me"), App("com.a", "A"), App("com.b", "B")))
        assertEquals(listOf("com.example", "com.b", "com.a"), agent.state.allowed.map { it.pkg })
        agent.allow(emptyList())
        agent.setSystemUi(true)
        assertTrue(agent.state.systemUi)
        agent.disallow("com.example")
        assertEquals(listOf("com.b", "com.a"), agent.state.allowed.map { it.pkg })
    }

    @Test fun unpairedInWysebeeForgetsEverything() {
        pair()
        agent.allow(App("com.example", "Example"))
        agent.setSystemUi(true)
        cloud.secretHash = "other"
        until("forgotten") { agent.state.link == null }
        assertTrue(agent.state.allowed.isEmpty())
        assertTrue(!agent.state.systemUi)
        assertNull(secrets.get())
        assertEquals("Unpaired in Wysebee.", agent.status.value.message)
    }

    @Test fun unpairHereTellsTheCloud() = runBlocking {
        pair()
        agent.unpair()
        assertNull(agent.state.link)
        assertTrue(cloud.unpaired)
    }

    @Test fun expiredCodeAndCancel() = runBlocking {
        assertNull(agent.startPairing("Phone"))
        agent.cancelPairing()
        assertNull(agent.state.pending)
        assertNull(agent.startPairing("Phone"))
        cloud.pollStatus = 410
        until("expired") { agent.state.pending == null }
        assertTrue(agent.status.value.message!!.contains("ran out"))
    }

    @Test fun outagesBackOffAndRecover() {
        pair()
        cloud.pollStatus = 500
        until("offline") { !agent.status.value.connected }
        cloud.pollStatus = 200
        until("back") { agent.status.value.connected }
    }
}
