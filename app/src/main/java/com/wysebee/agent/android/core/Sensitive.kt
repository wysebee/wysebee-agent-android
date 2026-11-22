package com.wysebee.agent.android.core

/**
 * Apps the agent never takes a picture of: banking, payment,
 * investing and crypto apps, and password managers and authenticators. Their screens show
 * balances, card numbers, one-time codes and passwords, and a screenshot passes through Wysebee
 * Cloud to the agent's AI. Android has no "banking" category, so an app is one of these by its
 * package (well-known ones, listed) or by a word in its package or its name.
 *
 * It only stops pictures: the agent may still read and operate such an app as text, if the person
 * allowed it, and never a password field. Many of these apps also forbid screenshots themselves
 * (`FLAG_SECURE`), which [Ops] says as such.
 */
object Sensitive {
    /** Well-known ones whose package says nothing of what they are. */
    private val PACKAGES = setOf(
        // Password managers and authenticators.
        "com.x8bit.bitwarden", "com.onepassword.android", "com.agilebits.onepassword", "com.lastpass.lpandroid",
        "com.dashlane", "keepass2android.keepass2android", "keepass2android.keepass2android_nonet", "com.kunzisoft.keepass.free",
        "com.kunzisoft.keepass.libre", "in.sinew.enpass", "com.nordpass.android.app.password.manager", "proton.android.pass",
        "com.keepersecurity.passwordmanager", "com.callpod.android_apps.keeper", "com.google.android.apps.authenticator2",
        "com.azure.authenticator", "com.authy.authy", "com.duosecurity.duomobile", "org.fedorahosted.freeotp", "com.beemdevelopment.aegis",
        "com.yubico.yubioath",
        // Banks, brokers and payments.
        "com.cibc.android.mobi", "com.rbc.mobile.android", "com.td", "com.bmo.mobile", "com.scotiabank.banking",
        "com.scotiabank.trade", "ca.pcfinancial.bank", "com.questrade.questmobile", "com.wealthsimple", "com.wealthsimple.trade",
        "com.desjardins.mobile", "com.tangerine.android", "com.chase.sig.android", "com.wf.wellsfargomobile", "com.infonow.bofa",
        "com.citi.citimobile", "uk.co.hsbc.hsbcukmobilebanking", "com.revolut.revolut", "com.transferwise.android",
        "com.paypal.android.p2pmobile", "com.venmo", "com.squareup.cash", "com.eg.android.AlipayGphone", "com.tencent.mm.wallet",
        "jp.ne.paypay.android.app", "jp.mufg.bk.applisp.app", "com.wealthnavi.amami", "com.felicanetworks.mfm.main",
        "com.mobilesuica.msb.android", "com.solium.shareworks", "com.coinbase.android", "com.binance.dev",
        "com.google.android.apps.walletnfcrel", "com.samsung.android.spay",
    )

    /** Words that make a package or a name one of these: matched in any part of a package,
     *  and as a word (or, for CJK, anywhere) in a name. */
    private val PACKAGE_WORDS = listOf(
        "bank", "banking", "password", "passwords", "authenticator", "otp", "2fa", "vault", "keepass",
        "wallet", "pay", "invest", "trade", "trading", "broker", "crypto", "coin", "finance", "financial",
    )
    private val NAME_WORDS = Regex(
        "\\b(bank|banking|password|passwords|authenticator|wallet|pay|paypal|invest|investing|trade|trading|broker|crypto|bitcoin|finance|financial)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val NAME_CJK = listOf("銀行", "银行", "證券", "证券", "支付", "钱包", "錢包", "パスワード", "密码", "密碼", "財布", "ウォレット")

    /** Whether [pkg] (named [label], when known) is one the agent never takes a picture of. */
    fun isSensitive(pkg: String, label: String? = null): Boolean {
        if (pkg in PACKAGES) return true
        val parts = pkg.lowercase().split('.', '_')
        if (parts.any { part -> PACKAGE_WORDS.any { word -> part == word || part.startsWith(word) || part.endsWith(word) } }) return true
        val name = label ?: return false
        return NAME_WORDS.containsMatchIn(name) || NAME_CJK.any { it in name }
    }
}
