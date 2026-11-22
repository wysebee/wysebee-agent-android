package com.wysebee.agent.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StateTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun secrets() {
        val s = Secrets.newSecret()
        assertEquals(43, s.length)
        assertTrue(s.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Secrets.sha256Hex("abc"))
        val m = MemorySecrets()
        m.set("x"); assertEquals("x", m.get()); m.clear(); assertNull(m.get())
    }

    @Test fun stateRoundTripsAndPolicy() {
        val file = File(tmp.root, "dir/state.json")
        val store = StateStore(file)
        assertEquals(State(), store.load())
        val s = State(
            pending = Pending("p", "ABCDEFGH", 5, "Phone", "https://c"),
            link = Link("l", "https://c", "phone", "Phone"),
            paused = true, systemUi = true,
            allowed = listOf(AllowedApp("com.example", "Example")),
        )
        store.save(s)
        store.save(s)
        assertEquals(s, StateStore(file).load())
        assertTrue(s.mayOperate("com.example", "own"))
        assertTrue(!s.mayOperate("com.other", "own"))
        assertTrue(!s.copy(allowed = listOf(AllowedApp("own", "Me"))).mayOperate("own", "own"))
        assertTrue(s.mayOperate(SYSTEM_UI, "own"))
        assertTrue(!s.copy(systemUi = false).mayOperate(SYSTEM_UI, "own"))
        // A file from an early build that had "every app" on: only its list counts now.
        file.writeText("""{"allow_all": true, "allowed": [{"package": "com.example", "label": "Example"}]}""")
        assertEquals(State(allowed = listOf(AllowedApp("com.example", "Example"))), StateStore(file).load())
        file.writeText("{broken")
        assertEquals(State(), StateStore(file).load())
    }
}
