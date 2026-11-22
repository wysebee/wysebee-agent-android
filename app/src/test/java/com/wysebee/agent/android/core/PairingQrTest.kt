package com.wysebee.agent.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingQrTest {
    private val token = "aB3_-".repeat(8) + "xyz"

    @Test fun readsWhatWysebeeShows() {
        assertEquals(PairingQr(token, "https://dev.example.com"), PairingQr.parse(" wysebee-agent:pair?offer=$token&from=https%3A%2F%2Fdev.example.com\n"))
        assertEquals(PairingQr(token, null), PairingQr.parse("wysebee-agent:pair?offer=$token"))
        assertEquals(PairingQr(token, null), PairingQr.parse("wysebee-agent:pair?from=&offer=$token&other=1&=x"))
    }

    @Test fun refusesAnythingElse() {
        for (bad in listOf(null, "", "hello", "https://app.wysebee.com/?offer=$token", "wysebee-agent:other?offer=$token", "wysebee-agent:pair?offer=${token}x", "wysebee-agent:pair?offer=${token.dropLast(1)}!", "wysebee-agent:pair?offer=%E0%A4%A")) {
            assertNull(bad, PairingQr.parse(bad))
        }
    }

    @Test fun cloudAddresses() {
        assertEquals("https://tunnel.example.com", CloudAddress.normalize(" https://Tunnel.Example.com/ "))
        assertEquals("https://app.wysebee.com", CloudAddress.normalize("app.wysebee.com"))
        assertEquals("https://x.example:8443", CloudAddress.normalize("https://x.example:8443"))
        assertEquals("http://127.0.0.1:9", CloudAddress.normalize("http://127.0.0.1:9"))
        for (bad in listOf(null, "", " ", "http://x.example", "https://x.example/path", "https://x.example?q=1", "https://u@x.example", "https://", "ftp://x.example", "not a url")) {
            assertNull(bad, CloudAddress.normalize(bad))
        }
        assertEquals("tunnel.example.com", CloudAddress.host("https://tunnel.example.com"))
    }
}
