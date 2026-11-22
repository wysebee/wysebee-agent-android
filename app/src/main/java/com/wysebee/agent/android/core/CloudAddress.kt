package com.wysebee.agent.android.core

import java.net.URI

/**
 * A Wysebee Cloud's address as this app keeps it: `https://host[:port]`, nothing after it. The
 * person may type one (the server this phone pairs with, 2026-11-22), and a pairing QR code names
 * the one that showed it. Only https: plain http is taken only for this computer itself (the
 * tests' fake cloud), and the app's network security config refuses cleartext anyway.
 */
object CloudAddress {
    /** [text] as an address (https:// added when no scheme is typed), or null when it is not one. */
    fun normalize(text: String?): String? {
        val raw = text?.trim()?.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) } ?: return null
        val u = runCatching { URI(if ("://" in raw) raw else "https://$raw") }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        val host = u.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (u.rawUserInfo != null || (u.rawPath ?: "").trimEnd('/').isNotEmpty() || u.rawQuery != null || u.rawFragment != null) return null
        val local = host == "127.0.0.1" || host == "localhost"
        if (scheme != "https" && !(scheme == "http" && local)) return null
        return "$scheme://$host${if (u.port == -1) "" else ":${u.port}"}"
    }

    /** The host of an address, for saying it. */
    fun host(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url
}
