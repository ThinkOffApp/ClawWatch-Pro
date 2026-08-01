package com.thinkoff.clawwatch.billing

import android.content.Context
import com.thinkoff.clawwatch.SecurePrefs

/**
 * Entitlement gate for cloud queries (petrus's model, Aug 1 2026):
 *  - BYOK (user's own Anthropic key in SecurePrefs): unmetered, free —
 *    the user pays Anthropic directly. This is also the OFFRAMP for heavy
 *    managed users (claudemm's design: past the allowance you are offered
 *    BYOK, never throttled and never silently billed).
 *  - Subscribed (clawwatch_pro_monthly): unmetered managed queries.
 *  - Otherwise: [FREE_QUERIES] lifetime free managed queries, then the
 *    paywall. The free tier doubles as onboarding — value before key entry.
 *
 * The counter is client-side for UX; the backend meters authoritatively in
 * Phase 3 (a wiped app does not reset the server's count).
 */
object QueryQuota {
    const val FREE_QUERIES = 3
    private const val PREF_USED = "managed_queries_used"

    /**
     * Written by WatchRelay when the watch reports BYOK presence over the
     * Data Layer (/clawwatch/byok-status). The user's Anthropic key lives
     * on the WATCH (ClawRunner's store — a different app, sandbox, and
     * device than this one), so a phone-local key check alone would show
     * a BYOK user a false paywall (claudemm's PR #4 review). The gate
     * reads the merged view: key here OR key reported by the watch.
     */
    const val PREF_WATCH_BYOK = "watch_has_byok_key"

    sealed class Gate {
        object Byok : Gate()
        object Subscribed : Gate()
        data class Free(val remaining: Int) : Gate()
        object Paywall : Gate()
    }

    fun gate(context: Context, subscribed: Boolean): Gate {
        // Despite the name, SecurePrefs.watch() is THIS app's local
        // encrypted store — the accessor predates the phone/watch split
        // and each module has its own copy of SecurePrefs.
        val prefs = SecurePrefs.watch(context)
        val byok = !prefs.getString("anthropic_api_key", null).isNullOrBlank() ||
            prefs.getBoolean(PREF_WATCH_BYOK, false)
        if (byok) return Gate.Byok
        if (subscribed) return Gate.Subscribed
        val used = prefs.getInt(PREF_USED, 0)
        return if (used < FREE_QUERIES) Gate.Free(FREE_QUERIES - used) else Gate.Paywall
    }

    /** Call after a managed (non-BYOK, non-subscribed) query is served. */
    fun recordManagedQuery(context: Context) {
        val prefs = SecurePrefs.watch(context)
        prefs.edit().putInt(PREF_USED, prefs.getInt(PREF_USED, 0) + 1).apply()
    }
}
