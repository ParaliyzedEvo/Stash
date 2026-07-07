package com.stash.core.network.dns

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * A [Dns] that survives the device resolver failing under a burst of concurrent
 * lookups.
 *
 * Stash's sync fans out 8 downloads at once. On some networks the system
 * resolver returns `UnknownHostException("No address associated with hostname")`
 * for several of those *simultaneous* lookups even though the same host resolves
 * fine for a single request a moment later. Observed live for `amz.squid.wtf`
 * during sync (every amz call DNS-failed for the whole 8-way burst) while
 * single-request streaming to that exact host worked — the reason downloads
 * "fell flat" while streaming was fine.
 *
 * Three mechanisms, smallest-hammer first:
 *  1. **Positive cache (TTL):** a host resolved once is reused, so the burst
 *     doesn't re-resolve at all.
 *  2. **Per-host coalescing:** concurrent first-time lookups for the same host
 *     serialize behind one lock and share a single real resolution, instead of
 *     firing N simultaneous queries that overwhelm the resolver.
 *  3. **Retry with stale fallback:** a lookup that still fails retries briefly,
 *     and falls back to a stale cached address rather than failing the request.
 */
class ResilientDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val backoffMs: Long = DEFAULT_BACKOFF_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : Dns {

    private data class Entry(val addrs: List<InetAddress>, val atMs: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Any>()

    override fun lookup(hostname: String): List<InetAddress> {
        fresh(hostname)?.let { return it }

        // Coalesce concurrent first-time lookups for this host: only one thread
        // actually resolves; the rest wait and then hit the cache the winner
        // populated. This turns an N-wide simultaneous DNS burst into a single
        // (or serialized) query the resolver can handle.
        synchronized(locks.getOrPut(hostname) { Any() }) {
            fresh(hostname)?.let { return it }

            var last: UnknownHostException? = null
            repeat(maxAttempts) { attempt ->
                try {
                    val addrs = delegate.lookup(hostname)
                    cache[hostname] = Entry(addrs, clock())
                    return addrs
                } catch (e: UnknownHostException) {
                    last = e
                    if (attempt < maxAttempts - 1) sleep(backoffMs * (attempt + 1))
                }
            }
            // Every retry failed — better a stale address than a failed request.
            cache[hostname]?.let { return it.addrs }
            throw last ?: UnknownHostException(hostname)
        }
    }

    private fun fresh(hostname: String): List<InetAddress>? =
        cache[hostname]?.takeIf { clock() - it.atMs < ttlMs }?.addrs

    companion object {
        const val DEFAULT_TTL_MS = 5 * 60_000L
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_BACKOFF_MS = 200L
    }
}
