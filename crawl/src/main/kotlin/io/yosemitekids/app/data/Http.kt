package io.yosemitekids.app.data

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One shared OkHttpClient for the whole app (extractor, whitelist, thumbnails).
 *
 * DNS strategy — built for devices behind flaky or filtering resolvers:
 *  - successful lookups are cached for 10 minutes (a stall can never repeat per-host)
 *  - the system resolver gets at most 2 seconds before we race to DNS-over-HTTPS
 *  - after one system failure, DoH is preferred for the next 5 minutes
 */
object Http {

    /**
     * The hub's outbound allow-list. Null on a phone or TV, where the client
     * fetches whatever the kid's screen needs; the hub arms it at startup
     * with [HUB_HOSTS], and from then on this client reaches YouTube and
     * nothing else — before DNS, before a socket. Guard 7 in scripts/check.*
     * checks the hub arms it and that the list names only YouTube's hosts.
     */
    @Volatile private var allowedHosts: Set<String>? = null

    /** YouTube and the CDNs its pages and thumbnails come from. Bare domains; subdomains match. */
    val HUB_HOSTS: Set<String> = setOf(
        "youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "ggpht.com", "googleusercontent.com"
    )

    fun restrictTo(hosts: Set<String>?) { allowedHosts = hosts }

    /** [host] itself, or any subdomain of it — never a lookalike such as youtube.com.evil.example. */
    fun hostAllowed(host: String, allowed: Set<String>): Boolean =
        allowed.any { host == it || host.endsWith(".$it") }

    private object HostAllowList : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val allowed = allowedHosts ?: return chain.proceed(chain.request())
            val host = chain.request().url.host
            if (!hostAllowed(host, allowed)) {
                throw IOException("refused: $host is not on this hub's allow-list")
            }
            return chain.proceed(chain.request())
        }
    }

    private val bootstrap = OkHttpClient()

    private val doh = DnsOverHttps.Builder()
        .client(bootstrap)
        .url("https://1.1.1.1/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("1.1.1.1"),
            InetAddress.getByName("1.0.0.1")
        )
        // IPv4 answers only: devices on broken-IPv6 networks get IPv6-only DNS
        // responses and then ENETUNREACH on every connect.
        .includeIPv6(false)
        .build()

    private object ResilientDns : Dns {
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
        private const val SYSTEM_DNS_BUDGET_S = 2L
        private const val DOH_PREFERRED_MS = 5 * 60 * 1000L

        /**
         * googlevideo stream hosts are effectively unique per video
         * (rr3---sn-….googlevideo.com), so without a cap a TV that stays up for
         * weeks accumulates hostnames forever. Entries expire on read but were
         * never removed.
         */
        private const val MAX_CACHE_ENTRIES = 256

        private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
        private val executor = Executors.newCachedThreadPool { r ->
            Thread(r, "YosemiteKids-dns").apply { isDaemon = true }
        }

        @Volatile
        private var dohPreferredUntil = 0L

        override fun lookup(hostname: String): List<InetAddress> {
            // An armed client is the hub: it has a resolver of its own, and
            // DNS-over-HTTPS would make 1.1.1.1 a silent third outbound host.
            if (allowedHosts != null) return Dns.SYSTEM.lookup(hostname)
            val now = System.currentTimeMillis()
            cache[hostname]?.let { (ts, addrs) -> if (now - ts < CACHE_TTL_MS) return addrs }

            val addrs = if (now < dohPreferredUntil) {
                runCatching { doh.lookup(hostname) }
                    .getOrElse { Dns.SYSTEM.lookup(hostname) }
            } else {
                systemWithBudget(hostname)
            }
            // IPv4 first: routers with broken IPv6 silently blackhole connections.
            // If the answer is IPv6-ONLY, it's useless on such networks — force an
            // IPv4 answer via DoH rather than fail with ENETUNREACH.
            val v4 = addrs.filter { it is java.net.Inet4Address }
            val sorted = if (v4.isNotEmpty()) {
                v4 + (addrs - v4.toSet())
            } else {
                println("Yosemite Kids: IPv6-only DNS answer for $hostname — fetching IPv4 via DoH")
                runCatching { doh.lookup(hostname).filter { it is java.net.Inet4Address } }
                    .getOrNull()?.takeIf { it.isNotEmpty() } ?: addrs
            }
            if (cache.size >= MAX_CACHE_ENTRIES) {
                cache.entries.removeIf { now - it.value.first >= CACHE_TTL_MS }
                // All still fresh (a burst of unique hosts): dropping everything
                // costs one extra lookup each, versus growing without bound.
                if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
            }
            cache[hostname] = now to sorted
            return sorted
        }

        /** System DNS with a hard 2s budget, then DoH. */
        private fun systemWithBudget(hostname: String): List<InetAddress> {
            val task = executor.submit(Callable { Dns.SYSTEM.lookup(hostname) })
            return try {
                task.get(SYSTEM_DNS_BUDGET_S, TimeUnit.SECONDS)
            } catch (e: Exception) {
                task.cancel(true)
                println("Yosemite Kids: system DNS slow/failed for $hostname — using DoH")
                dohPreferredUntil = System.currentTimeMillis() + DOH_PREFERRED_MS
                doh.lookup(hostname)
            }
        }
    }

    /** One quick retry for failed GETs (network hiccup or 5xx) — covers thumbnails too. */
    private object RetryInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            return try {
                val response = chain.proceed(request)
                if (request.method == "GET" && response.code in 500..599) {
                    response.close()
                    Thread.sleep(400)
                    chain.proceed(request)
                } else response
            } catch (e: IOException) {
                if (request.method != "GET") throw e
                Thread.sleep(400)
                chain.proceed(request)
            }
        }
    }

    /**
     * High-latency links are round-trip-bound: OkHttp's default of 5 concurrent
     * requests per host loads a 30-thumbnail grid in six serial waves. Widen it so
     * the visible grid loads in roughly one.
     */
    private val dispatcher = okhttp3.Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 16
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .dns(ResilientDns)
        // First, so a refused host never reaches the retry or the network.
        .addInterceptor(HostAllowList)
        .addInterceptor(RetryInterceptor)
        // Short connect timeout: a dead address (broken IPv6 route) fails over
        // to the next one quickly instead of stalling the whole request.
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
