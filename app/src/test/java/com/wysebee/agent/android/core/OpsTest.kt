package com.wysebee.agent.android.core

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpsTest {
    private val own = "com.wysebee.agent.android"
    private var policy = Policy(paused = false, allowed = setOf("com.example"))
    private val device = FakeDevice()
    private var service: Device? = device
    private val ops = Ops({ service }, { policy }, own)

    private suspend fun ok(op: String, args: String = "{}"): JSONObject {
        val out = ops.run(op, JSONObject(args))
        assertTrue("expected ok, got $out", out is Outcome.Ok)
        return (out as Outcome.Ok).result as JSONObject
    }

    private suspend fun failed(op: String, args: String = "{}"): String {
        val out = ops.run(op, JSONObject(args))
        assertTrue("expected a refusal, got $out", out is Outcome.Failed)
        return (out as Outcome.Failed).error
    }

    private fun screen(): Triple<FakeNode, FakeNode, FakeNode> {
        val button = FakeNode(className = "android.widget.Button", text = "Sign in", clickable = true, bounds = Bounds(100, 200, 300, 260))
        val field = FakeNode(className = "android.widget.EditText", hint = "Email", editable = true, clickable = true, bounds = Bounds(0, 100, 1080, 180))
        val root = FakeNode(className = "android.widget.FrameLayout").add(field, button)
        device.tree = root
        return Triple(root, field, button)
    }

    @Test fun pingAnswersEvenPaused() = runTest {
        policy = policy.copy(paused = true)
        assertEquals(true, ok("ping").getBoolean("pong"))
    }

    @Test fun pausedRefusesEverything() = runTest {
        policy = policy.copy(paused = true)
        assertEquals(Ops.PAUSED, failed("android_snapshot"))
    }

    @Test fun unknownOpIsRefused() = runTest {
        assertTrue(failed("local_run").contains("does not know"))
    }

    @Test fun serviceOffSaysSo() = runTest {
        service = null
        assertEquals(Ops.NO_SERVICE, failed("android_snapshot"))
    }

    @Test fun snapshotListsElementsWithRefs() = runTest {
        screen()
        val out = ok("android_snapshot")
        val text = out.getString("snapshot")
        assertTrue(text, text.contains("App: Example (com.example)"))
        assertTrue(text, text.contains("[1] EditText hint \"Email\" tap input @540,140"))
        assertTrue(text, text.contains("[2] Button \"Sign in\" tap @200,230"))
        assertEquals(1080, out.getJSONObject("screen").getInt("width"))
    }

    @Test fun snapshotWithImageAddsPicture() = runTest {
        screen()
        val out = ok("android_snapshot", """{"with_image": true}""")
        assertEquals("image/jpeg", out.getString("mime"))
        assertEquals("AQID", out.getString("image"))
        device.shotFails = true
        assertTrue(!ok("android_snapshot", """{"with_image": true}""").has("image"))
    }

    @Test fun appNotAllowedIsNeitherReadNorTouched() = runTest {
        screen()
        device.app = "com.other"
        val error = failed("android_snapshot")
        assertTrue(error, error.contains("Other (com.other)") && error.contains("may not operate"))
        failed("android_tap", """{"x": 5, "y": 5}""")
        failed("android_screenshot")
        assertTrue(device.gestures.isEmpty())
    }

    @Test fun ownAppIsNeverOperatedEvenListed() = runTest {
        policy = policy.copy(allowed = setOf("com.example", own))
        device.app = own
        assertTrue(failed("android_snapshot").contains("Wysebee Agent itself"))
        assertTrue(failed("android_launch", """{"package": "$own"}""").contains("may not open"))
        assertEquals(1, ok("android_apps").getJSONArray("apps").length())
    }

    @Test fun appsListsOnlyAllowed() = runTest {
        policy = policy.copy(allowed = setOf("com.example", "com.gone"))
        val out = ok("android_apps")
        // There is no "every app": only the list.
        assertTrue(!out.has("all_apps"))
        // By name; one the phone cannot name is listed by its package.
        val apps = out.getJSONArray("apps")
        assertEquals(listOf("com.gone" to "com.gone", "com.example" to "Example"), (0 until apps.length()).map { apps.getJSONObject(it).let { a -> a.getString("package") to a.getString("label") } })
    }

    @Test fun noPictureOfBankingOrPasswordApps() = runTest {
        device.installed += App("com.cibc.android.mobi", "CIBC Banking")
        policy = policy.copy(allowed = setOf("com.cibc.android.mobi"))
        device.app = "com.cibc.android.mobi"
        screen()
        val refused = failed("android_screenshot")
        assertTrue(refused, refused.startsWith(Ops.NO_PICTURE) && refused.contains("android_snapshot"))
        // A snapshot still reads it as text, says why it has no picture, and takes none.
        val out = ok("android_snapshot", """{"with_image": true}""")
        assertTrue(!out.has("image"))
        assertTrue(out.getString("snapshot").contains(Ops.NO_PICTURE))
        // Without asking for a picture, nothing is said of one.
        assertTrue(!ok("android_snapshot").getString("snapshot").contains(Ops.NO_PICTURE))
    }

    @Test fun launchOnlyAllowedAndInstalled() = runTest {
        device.app = "com.launcher"
        assertEquals("com.example", ok("android_launch", """{"package": "com.example"}""").getString("app"))
        assertTrue(failed("android_launch", """{"package": "com.other"}""").contains("may not open"))
        policy = policy.copy(allowed = setOf("com.example", "com.gone"))
        assertTrue(failed("android_launch", """{"package": "com.gone"}""").contains("not installed"))
        assertTrue(failed("android_launch").contains("Say which app"))
    }

    @Test fun tapByRefClicksTheElement() = runTest {
        val (_, _, button) = screen()
        ok("android_snapshot")
        assertEquals("tapped", ok("android_tap", """{"ref": 2}""").getString("done"))
        assertEquals(listOf("click"), button.done)
    }

    @Test fun tapByRefFallsBackToAncestorThenGesture() = runTest {
        val label = FakeNode(text = "Row", bounds = Bounds(0, 0, 200, 100))
        val row = FakeNode(className = "android.widget.LinearLayout", clickable = true).add(label)
        device.tree = FakeNode().add(row)
        ok("android_snapshot")
        // [1] is the row (clickable), [2] its label (not clickable): the label's tap clicks the row.
        assertEquals("tapped", ok("android_tap", """{"ref": 2}""").getString("done"))
        assertEquals(listOf("click"), row.done)
        row.clickable = false
        ok("android_snapshot")
        assertEquals("tapped at 100,50", ok("android_tap", """{"ref": 1}""").getString("done"))
        assertEquals("long-pressed at 100,50", ok("android_tap", """{"ref": 1, "long": true}""").getString("done"))
    }

    @Test fun longPressByRef() = runTest {
        val item = FakeNode(text = "Item", longClickable = true)
        device.tree = FakeNode().add(item)
        ok("android_snapshot")
        assertEquals("long-pressed", ok("android_tap", """{"ref": 1, "long": true}""").getString("done"))
    }

    @Test fun staleOrUnknownRefsAreRefused() = runTest {
        val (_, _, button) = screen()
        assertTrue(failed("android_tap", """{"ref": 2}""").contains("take a new android_snapshot"))
        ok("android_snapshot")
        button.gone = true
        assertTrue(failed("android_tap", """{"ref": 2}""").contains("no longer on the screen"))
    }

    @Test fun refInAnotherAppIsRefused() = runTest {
        val (_, _, button) = screen()
        ok("android_snapshot")
        button.packageName = "com.other"
        assertTrue(failed("android_tap", """{"ref": 2}""").contains("belongs to com.other"))
    }

    @Test fun tapByPointChecksTheScreen() = runTest {
        screen()
        ok("android_tap", """{"x": 10, "y": 20}""")
        assertEquals("tap:10,20,60", device.gestures.single())
        assertTrue(failed("android_tap", """{"x": 5000, "y": 20}""").contains("off the screen"))
        assertTrue(failed("android_tap").contains("Say where"))
    }

    @Test fun typeIntoAField() = runTest {
        val (_, field, _) = screen()
        ok("android_snapshot")
        val out = ok("android_type", """{"ref": 1, "text": "a@b.c", "submit": true}""")
        assertEquals(true, out.getBoolean("submitted"))
        assertEquals(listOf("focus", "click", "setText:a@b.c", "enter"), field.done)
        ok("android_type", """{"ref": 1, "text": "d", "clear": false}""")
        assertEquals("a@b.cd", field.text)
    }

    @Test fun typeIntoTheFocusedField() = runTest {
        val (_, field, _) = screen()
        assertTrue(failed("android_type", """{"text": "x"}""").contains("No text field has focus"))
        field.focused = true
        device.focusedField = field
        ok("android_type", """{"text": "x"}""")
        assertEquals("x", field.text)
    }

    @Test fun typeFollowsTheFocusFromAStandInField() = runTest {
        val (_, bar, _) = screen()
        bar.acceptsText = false
        ok("android_snapshot")
        assertTrue(failed("android_type", """{"ref": 1, "text": "x"}""").contains("did not take"))
        val real = FakeNode(className = "android.widget.EditText", editable = true, focused = true)
        device.focusedField = real
        ok("android_type", """{"ref": 1, "text": "Ada"}""")
        assertEquals("Ada", real.text)
        real.password = true
        assertTrue(failed("android_type", """{"ref": 1, "text": "x"}""").contains("did not take"))
        real.password = false
        real.packageName = "com.other"
        assertTrue(failed("android_type", """{"ref": 1, "text": "x"}""").contains("did not take"))
        real.packageName = "com.example"
        real.acceptsText = false
        assertTrue(failed("android_type", """{"ref": 1, "text": "x"}""").contains("did not take"))
    }

    @Test fun typeRefusals() = runTest {
        val (_, field, button) = screen()
        ok("android_snapshot")
        assertTrue(failed("android_type", """{"ref": 1}""").contains("Say what to type"))
        assertTrue(failed("android_type", """{"ref": 2, "text": "x"}""").contains("not a text field"))
        field.password = true
        assertTrue(failed("android_type", """{"ref": 1, "text": "x"}""").contains("password"))
        field.password = false
        field.acceptsText = false
        assertTrue(failed("android_type", """{"ref": 1, "text": "x"}""").contains("did not take"))
        field.acceptsText = true
        field.hasEnter = false
        assertTrue(failed("android_type", """{"ref": 1, "text": "x", "submit": true}""").contains("no Enter action"))
        assertTrue(failed("android_type", """{"ref": 1, "text": "${"x".repeat(10_001)}"}""").contains("At most"))
        assertTrue(button.done.isEmpty())
    }

    @Test fun swipe() = runTest {
        screen()
        ok("android_swipe", """{"x1": 500, "y1": 2000, "x2": 500, "y2": 400}""")
        assertEquals("swipe:500,2000>500,400", device.gestures.single())
    }

    @Test fun scrollAListOrSwipeTheScreen() = runTest {
        val list = FakeNode(className = "androidx.recyclerview.widget.RecyclerView", scrollable = true, bounds = Bounds(0, 0, 1080, 2000))
        val item = FakeNode(text = "One", bounds = Bounds(0, 0, 1080, 100))
        list.add(item)
        device.tree = FakeNode().add(list)
        ok("android_snapshot")
        assertEquals("scrolled", ok("android_scroll", """{"ref": 2, "direction": "down"}""").getString("done"))
        assertEquals(listOf("scroll:down"), list.done)
        list.scrolls = false
        assertEquals("swiped", ok("android_scroll", """{"ref": 1, "direction": "down"}""").getString("done"))
        assertEquals("swipe:540,1500>540,500", device.gestures.last())
        ok("android_scroll", """{"direction": "up"}""")
        assertEquals("swipe:540,600>540,1800", device.gestures.last())
        ok("android_scroll", """{"direction": "left"}""")
        ok("android_scroll", """{"direction": "right"}""")
        assertTrue(failed("android_scroll", """{"direction": "sideways"}""").contains("which way"))
    }

    @Test fun keysWorkInAnyApp() = runTest {
        device.app = "com.other"
        assertEquals("home", ok("android_key", """{"key": "home"}""").getString("pressed"))
        assertTrue(failed("android_key", """{"key": "power"}""").contains("Say which key"))
    }

    @Test fun screenshot() = runTest {
        screen()
        val out = ok("android_screenshot")
        assertEquals(576, out.getInt("width"))
        device.shotFails = true
        assertTrue(failed("android_screenshot").contains("does not allow screenshots"))
    }

    @Test fun nothingOnScreen() = runTest {
        device.app = null
        assertEquals(Ops.NOTHING, failed("android_snapshot"))
        device.app = "com.example"
        device.tree = null
        assertEquals(Ops.NOTHING, failed("android_snapshot"))
    }

    @Test fun systemUiOnlyWhenTurnedOn() = runTest {
        device.installed += App(SYSTEM_UI, "System UI")
        device.app = SYSTEM_UI
        screen()
        val refused = failed("android_snapshot")
        assertTrue(refused, refused.contains("turn on System UI"))
        assertEquals(false, ok("android_apps").getBoolean("system_ui"))
        policy = policy.copy(systemUi = true)
        // The notification shade, read and touched like an allowed app, pictures too.
        assertTrue(ok("android_snapshot").getString("snapshot").contains("App: System UI ($SYSTEM_UI)"))
        ok("android_screenshot")
        ok("android_tap", """{"x": 10, "y": 10}""")
        assertEquals(true, ok("android_apps").getBoolean("system_ui"))
        // Said by its own field, not listed as an app.
        val apps = ok("android_apps").getJSONArray("apps")
        assertEquals(listOf("com.example"), (0 until apps.length()).map { apps.getJSONObject(it).getString("package") })
    }

    @Test fun nothingWhileLocked() = runTest {
        policy = policy.copy(systemUi = true)
        device.isLocked = true
        for (op in listOf("android_snapshot", "android_screenshot", "android_key", "android_launch", "android_apps")) {
            assertEquals(op, Ops.LOCKED, failed(op, """{"key": "home", "package": "com.example"}"""))
        }
        assertTrue(device.gestures.isEmpty() && device.launched.isEmpty())
        // ping still answers: the phone is there, only locked.
        ok("ping")
    }
}

