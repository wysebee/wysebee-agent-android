package com.wysebee.agent.android.core

import java.net.URLDecoder

/**
 * A pairing QR code Wysebee shows (the link protocol, "Pairing by a QR code"):
 * `wysebee-agent:pair?offer=<token>&from=<the cloud that showed it>`. The phone pairs with that
 * cloud (2026-11-22, the owner's call), after showing the person which it is; one naming no
 * cloud pairs with the phone's own server.
 */
data class PairingQr(val offer: String, val from: String?) {
    companion object {
        private val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")

        /** The QR code's offer, or null when the text is not a Wysebee pairing QR code. */
        fun parse(text: String?): PairingQr? {
            val raw = text?.trim() ?: return null
            if (!raw.startsWith("wysebee-agent:pair?")) return null
            val query = raw.substringAfter('?')
            val fields = query.split('&').mapNotNull { part ->
                val key = part.substringBefore('=', "")
                if (key.isEmpty()) null else key to runCatching { URLDecoder.decode(part.substringAfter('='), "UTF-8") }.getOrNull()
            }.toMap()
            val offer = fields["offer"]?.takeIf { TOKEN.matches(it) } ?: return null
            return PairingQr(offer, fields["from"]?.takeIf { it.isNotBlank() })
        }
    }
}
