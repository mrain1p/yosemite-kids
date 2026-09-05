package io.yosemitekids.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * App-lifetime scope for fire-and-forget LAN pushes. Config syncs must survive
 * the settings UI closing — a composable-tied scope would cancel them mid-push.
 */
object LanPushScope {
    val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )
}

/** A kid device (TV) this phone administers. */
data class PairedDevice(
    val name: String,
    val host: String,
    val port: Int,
    /** OUR token, presented as X-Token — the credential, not the device's name. */
    val token: String,
    /**
     * The remote device's own identity token, learned from its /status reply.
     * host:port are just where the device happened to live when the QR was
     * scanned — DHCP re-leases and port drift both invalidate them — so this
     * is the only stable way to recognize "our TV" during re-discovery.
     * Null on entries written by older builds until the first successful
     * sync backfills it.
     */
    val id: String? = null,
    /**
     * This peer holds no API key, so it must be compared on a fingerprint
     * computed without one. True for a self-hosted hub, which strips secrets
     * before writing and has no SecretStore to put them back.
     *
     * Recorded here, at enrolment, and deliberately NOT read from the peer's
     * /status. A peer that could assert this about itself could switch off
     * the phone's only content-level check on the key — and a TV holding a
     * revoked key would then read "in sync" while its screening was dead.
     * The one party that must not be trusted for this is the peer being
     * checked.
     */
    val secretless: Boolean = false
) {
    /**
     * Stable list key: the remote identity when known, else the address the
     * device was paired at. Never [token] — every entry carries THIS phone's
     * token, so token-keyed list operations match every entry at once and
     * collapse the list to a single device.
     */
    val key: String get() = id ?: "$host:$port"

    companion object {
        /**
         * The name [io.yosemitekids.app.data.HubEnrolment] gives a hub it joins.
         * A constant because it is load-bearing in two places: the settings
         * screen finds the hub by it, and entries written before [secretless]
         * existed are migrated by it on read.
         */
        const val HUB_NAME = "Yosemite Kids hub"
    }
}

/**
 * Add-or-replace, keyed the same way [PairedDevice.key] is: an entry is the
 * same device if the identities match, or failing that if it lives at the same
 * address — a re-pair of a moved TV (known id, new address) must replace the
 * stale entry, and a re-pair of an id-less legacy entry can only match on
 * where it lives.
 */
internal fun List<PairedDevice>.upsertPaired(device: PairedDevice): List<PairedDevice> =
    filterNot {
        (device.id != null && it.id == device.id) ||
            (it.host == device.host && it.port == device.port)
    } + device

internal fun List<PairedDevice>.removePaired(key: String): List<PairedDevice> =
    filterNot { it.key == key }

internal fun List<PairedDevice>.renamePaired(key: String, newName: String): List<PairedDevice> =
    map { if (it.key == key) it.copy(name = newName) else it }

/** Both roles: the TV's own pairing token, and the phone's list of paired devices. */
class PairingStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    /**
     * What this device IS in the family: a parent administers kid devices, a
     * kid device is administered. Explicit rather than inferred from the
     * paired/approved lists — a freshly reinstalled parent phone has an empty
     * paired list and must not start behaving like a kid device (its search
     * index and master claim ride on this). Set by the first pairing act in
     * either direction; TV hardware defaults to kid.
     */
    enum class Role { PARENT, KID }

    fun role(): Role? =
        prefs.getString("role", null)?.let { runCatching { Role.valueOf(it) }.getOrNull() }

    fun setRole(role: Role) {
        if (role() == null) prefs.edit().putString("role", role.name).apply()
    }

    /**
     * The one deliberate role change: a parent dedicating this phone/tablet to
     * a kid (the no-TV household — nothing else ever shows a pairing QR on a
     * non-TV device). Bypasses [setRole]'s first-writer-wins guard because it
     * is an explicit, confirmed parent action, not an inference. One-way by
     * design: the admin editor never comes back without a reinstall, same as
     * a TV.
     */
    fun dedicateAsKid() {
        prefs.edit().putString("role", Role.KID.name).apply()
    }

    /** This device's stable identity token (a phone presents it when pairing). */
    fun deviceToken(): String =
        prefs.getString("device_token", null) ?: ByteArray(16)
            .also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
            .also { prefs.edit().putString("device_token", it).apply() }

    /**
     * This device's short identity in the change log. Derived from the pairing
     * token rather than the name, because names collide: two parents both
     * carrying a Pixel 7 Pro would otherwise be indistinguishable, and a log
     * whose whole purpose is attribution would credit one for the other's
     * edits. Eight hex is plenty to tell two phones in a house apart, and the
     * config travels the LAN so there is no reason to put more of the token
     * in it than that.
     */
    fun by(): String = deviceToken().take(8)

    /**
     * What the change log calls this device. Defaults to the model name and is
     * editable in Settings, because "Pixel 7 Pro" is no help when both parents
     * have one and "Dad's phone" is exactly what a co-parent needs to read.
     */
    fun myName(): String = prefs.getString("my_name", null)?.takeIf { it.isNotBlank() }
        ?: android.os.Build.MODEL.orEmpty().ifBlank { "This phone" }

    fun setMyName(name: String) {
        val clean = name.trim().take(40)
        prefs.edit().apply {
            if (clean.isBlank()) remove("my_name") else putString("my_name", clean)
        }.apply()
    }

    // --- TV role: which phones may administer this device -------------------

    private fun readMap(key: String): Map<String, String> = runCatching {
        val o = JSONObject(prefs.getString(key, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    private fun writeMap(key: String, map: Map<String, String>) {
        prefs.edit().putString(key, JSONObject(map).toString()).apply()
    }

    /** token → phone name. Empty until the first phone is approved. */
    fun approvedPhones(): Map<String, String> = readMap("approved")

    fun isApproved(token: String): Boolean = approvedPhones().containsKey(token)

    fun approvePhone(token: String, name: String) {
        writeMap("approved", approvedPhones() + (token to name))
        writeMap("pending", pendingRequests() - token)
    }

    /**
     * Requests still waiting on a parent, minus any that have sat longer than
     * [PENDING_TTL_MS]. Five slots is all there are, and without expiry anyone
     * on the LAN could fill them once and lock a real new phone out until an
     * admin came along to deny each one by hand.
     */
    fun pendingRequests(): Map<String, String> {
        val pending = readMap("pending")
        if (pending.isEmpty()) return pending
        val at = readMap("pending_at")
        val live = prunePending(pending, at, System.currentTimeMillis())
        if (live.size != pending.size) {
            writeMap("pending", live)
            writeMap("pending_at", at.filterKeys { it in live })
        }
        return live
    }

    fun addPendingRequest(token: String, name: String) {
        var pending = pendingRequests()
        if (token in pending) return
        val at = readMap("pending_at").toMutableMap()
        if (pending.size >= MAX_PENDING) {
            // Full: the oldest request gives way — it has had its chance, and
            // the phone in the parent's hand right now is the one that matters.
            val oldest = pending.keys.minByOrNull { at[it]?.toLongOrNull() ?: 0L } ?: return
            pending = pending - oldest
            at.remove(oldest)
        }
        // The name is attacker-supplied and lands in SharedPreferences —
        // bound it so a request can't bloat the prefs file.
        writeMap("pending", pending + (token to name.take(40)))
        at[token] = System.currentTimeMillis().toString()
        writeMap("pending_at", at)
    }

    fun denyRequest(token: String) {
        writeMap("pending", pendingRequests() - token)
        writeMap("pending_at", readMap("pending_at") - token)
    }

    companion object {
        const val MAX_PENDING = 5
        const val PENDING_TTL_MS = 10 * 60_000L

        /**
         * The expiry rule on its own: a request with no stamp (written by an
         * older build) is kept — it predates the rule and there is no honest
         * age for it; anything stamped older than the TTL is gone.
         */
        /**
         * The stored form of the paired list. Pure and in the companion so a
         * JVM test can reach it without a Context — the migration below is
         * exactly the kind of thing that is written once, never exercised,
         * and quietly wrong.
         */
        internal fun parsePaired(stored: String?): List<PairedDevice> = runCatching {
            val arr = JSONArray(stored ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val name = o.getString("name")
                PairedDevice(
                    name, o.getString("host"), o.getInt("port"),
                    o.getString("token"), o.optString("id").ifEmpty { null },
                    // Entries written before the flag existed. A hub is the
                    // only peer this phone ever named HUB_NAME, and it wrote
                    // that name itself at enrolment, so the name is this
                    // phone's own record rather than the peer's claim. The
                    // next savePaired persists the flag explicitly, so a
                    // later rename cannot lose it.
                    o.optBoolean("secretless", name == PairedDevice.HUB_NAME)
                )
            }
        }.getOrDefault(emptyList())

        internal fun serializePaired(list: List<PairedDevice>): String {
            val arr = JSONArray()
            list.forEach { d ->
                arr.put(JSONObject().apply {
                    put("name", d.name); put("host", d.host)
                    put("port", d.port); put("token", d.token)
                    d.id?.let { put("id", it) }
                    if (d.secretless) put("secretless", true)
                })
            }
            return arr.toString()
        }

        internal fun prunePending(
            pending: Map<String, String>,
            stampedAt: Map<String, String>,
            now: Long,
            ttlMs: Long = PENDING_TTL_MS
        ): Map<String, String> = pending.filterKeys { token ->
            val at = stampedAt[token]?.toLongOrNull() ?: return@filterKeys true
            now - at < ttlMs
        }
    }

    fun revokePhone(token: String) {
        writeMap("approved", approvedPhones() - token)
    }

    /** Phone role: devices this phone administers. */
    fun paired(): List<PairedDevice> = parsePaired(prefs.getString("paired", "[]"))

    fun addPaired(device: PairedDevice) = savePaired(paired().upsertPaired(device))

    fun removePaired(key: String) = savePaired(paired().removePaired(key))

    /** Parent-chosen display name ("Living room TV"); identity stays [PairedDevice.key]. */
    fun renamePaired(key: String, newName: String) = savePaired(paired().renamePaired(key, newName))

    /**
     * Re-point an entry after re-discovery or an identity backfill. Matched on
     * the old host:port (the only stable key an id-less legacy entry has);
     * everything else about the entry is replaced.
     */
    fun replacePaired(old: PairedDevice, new: PairedDevice) = savePaired(
        paired().map { if (it.host == old.host && it.port == old.port) new else it }
    )

    private fun savePaired(list: List<PairedDevice>) {
        prefs.edit().putString("paired", serializePaired(list)).apply()
        PairingEvents.notifyChanged()
    }
}

/**
 * Bumped after any change to the paired list, so a screen holding a snapshot
 * of it can re-read. The pairing dialog lives in the activity, above every
 * page: a TV paired while Settings was open landed in the store and nowhere
 * on screen until Settings was left and reopened. Process-local, like
 * [DownloadEvents]; the store itself is the truth.
 */
object PairingEvents {
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0)
    fun notifyChanged() { changes.value = changes.value + 1 }
}

/** Fired on the TV when a paired phone pushes new config. */
object ConfigEvents {
    @Volatile
    var onConfigChanged: (() -> Unit)? = null
}

/**
 * Open only while the TV is actually showing its pairing QR, and gates
 * first-admin approval.
 *
 * Without it, any device on the LAN — including a web page in a browser, since
 * a cross-site POST needs no preflight — could claim the admin slot on a fresh
 * install just by asking first. That is worse than it sounds: only an approved
 * phone can approve another one, so a stolen slot leaves the family with no
 * route back except an uninstall, which wipes their curation.
 *
 * The QR on screen is the parent's consent. [keepOpen] is pumped by the
 * pairing screen's existing tick, so the window lapses seconds after that
 * screen goes away.
 */
object PairingWindow {
    private const val LINGER_MS = 15_000L

    @Volatile
    private var openUntil = 0L

    // elapsedRealtime, not wall clock: an NTP correction or timezone change
    // while the QR is up must not silently close (or stretch) the window.
    fun keepOpen() {
        openUntil = android.os.SystemClock.elapsedRealtime() + LINGER_MS
    }

    fun close() {
        openUntil = 0L
    }

    fun isOpen(): Boolean = android.os.SystemClock.elapsedRealtime() < openUntil
}

/**
 * Minimal HTTP listener the TV runs so a paired phone can push config and grants.
 * Plain HTTP on the home LAN, gated by the pairing token — appropriate for the
 * household threat model this app lives in.
 *
 * It does face the whole LAN before any token has been checked, though, so
 * every read here is bounded and connections run on a fixed pool: an
 * unbounded header, body or thread count is a remote crash of the kids' TV
 * that needs no credential at all.
 */
class LanServer(
    private val configStore: ConfigStore,
    /** Applies a grant to the right kid's guard (null profile = legacy/active). */
    private val grantHandler: (minutes: Int, profileId: String?) -> Unit,
    private val pairingStore: PairingStore,
    /** Stats for one kid (`?profile=`), or the device's current kid when null. */
    private val statsProvider: (profileId: String?) -> String = { "{}" },
    private val watchStateProvider: () -> String = { "{}" },
    /** Mergers answer whether the body was even the right shape — a peer that
     *  posts garbage gets a 400 to log, not a "merged" it will trust. */
    private val watchStateMerger: (String) -> Boolean = { false },
    /** A kid's own restyle waiting for the phone to adopt it — see ProfileLooks. */
    private val looksProvider: () -> String = { "{}" },
    /** AI verdict-sharing: serve ours, merge a peer's — see ScreeningStore. */
    private val verdictsProvider: () -> String = { "{}" },
    private val verdictsMerger: (String) -> Boolean = { false },
    /** Search-index sync: per-source status, one source's payload, and a merger. */
    private val indexStatusProvider: () -> String = { "{}" },
    private val indexSourceProvider: (String) -> String? = { null },
    private val indexMerger: (sourceId: String, body: String) -> Boolean = { _, _ -> false },
    /**
     * An accepted config push, with what this device held a moment before it.
     * The diff is what decides whether the kid hears about it — see
     * [KidNotices.configChange]; which kid's rules those are is the caller's
     * to resolve, so both configs travel whole.
     */
    private val onConfigApplied: (before: Whitelist, after: Whitelist) -> Unit = { _, _ -> },
    /**
     * A peer said "something changed, come and look" (`POST /sync-now`).
     *
     * Runs the ordinary reconcile. Kept as a callback rather than calling
     * [ConfigSync] from here because the server must not block one peer's
     * request on a sweep across every other peer.
     */
    private val onSyncRequested: () -> Unit = {},
    /**
     * What this device is — "tv", "tablet" or "phone" — for the badge on the
     * admin phone's device list. A phone cannot see a peer's Build, and
     * the form factor is the one fact about a device that never changes, so
     * it is served here rather than guessed at from a name.
     */
    private val deviceKind: () -> String? = { null }
) {
    @Volatile
    var port: Int = 0
        private set

    private var serverSocket: ServerSocket? = null

    /**
     * Fixed pool rather than a thread per connection: a flood of open sockets
     * must cost a bounded number of threads. When both the pool and its short
     * queue are full the connection is dropped, which keeps the TV responsive
     * instead of letting it be talked into exhaustion.
     */
    private val workers = java.util.concurrent.ThreadPoolExecutor(
        CORE_WORKERS, MAX_WORKERS, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue(MAX_QUEUED)
    ) { r -> Thread(r, "YosemiteKids-lan-worker").apply { isDaemon = true } }
        // Core threads time out too: an idle TV should hold none, but a status
        // poll shouldn't have to wait behind a slow config push either.
        .apply { allowCoreThreadTimeOut(true) }

    /** Last /pair-request per caller address — one every few seconds is plenty. */
    private val pairRequestAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Last /sync-now per caller address. See SYNC_NUDGE_MIN_GAP_MS. */
    private val syncNudgeAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun start() {
        if (serverSocket != null) return
        for (candidate in PORT_RANGE) {
            try {
                serverSocket = ServerSocket(candidate)
                port = candidate
                // Published so LanClient can tell peers where to reach us. A
                // hub cannot work this out for itself: it only ever sees
                // inbound connections, whose source port is ephemeral and
                // says nothing about where we listen.
                boundPort = candidate
                break
            } catch (_: Exception) { /* port busy — try next */ }
        }
        val socket = serverSocket ?: return
        thread(isDaemon = true, name = "YosemiteKids-lan") {
            // A failed accept used to end the loop for the life of the
            // process, with the QR still advertising the dead port. Only a
            // closed socket ends it now; anything else (a transient EMFILE,
            // a network reset) gets a breath and another try.
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    if (socket.isClosed) break
                    android.util.Log.w("YosemiteKids", "lan accept failed, retrying", e)
                    Thread.sleep(250)
                    continue
                }
                val queued = runCatching {
                    workers.execute { runCatching { handle(client) } }
                }.isSuccess
                if (!queued) runCatching { client.close() }
            }
        }
    }

    /** Releases the port; a later [start] binds afresh. */
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = 0
    }

    private fun handle(client: Socket) = client.use { sock ->
        sock.soTimeout = 10_000
        // Bytes, not a Reader: Content-Length counts bytes, and a Reader-based
        // body loop stalls forever on any multi-byte UTF-8 (kid-profile avatar
        // emoji were the first non-ASCII to ever enter a config push — every
        // push then died in a silent socket timeout).
        val input = java.io.BufferedInputStream(sock.getInputStream())
        // A line that never ends is as good as an OOM, so give up past the cap
        // instead of growing the buffer. `aborted` separates that from a clean
        // end of stream, which reads the same to the caller.
        var aborted = false
        fun readLine(): String? {
            val buf = java.io.ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
                if (b == '\n'.code) break
                if (buf.size() >= MAX_LINE_BYTES) {
                    aborted = true
                    return null
                }
                if (b != '\r'.code) buf.write(b)
            }
            return buf.toString("UTF-8")
        }
        val requestLine = readLine() ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val (method, target) = parts

        var contentLength = 0
        var reqToken: String? = null
        var contentType = ""
        var hasOrigin = false
        var headerCount = 0
        while (true) {
            val line = readLine() ?: break
            if (line.isEmpty()) break
            if (++headerCount > MAX_HEADERS) {
                aborted = true
                break
            }
            val lower = line.lowercase()
            when {
                lower.startsWith("content-length:") ->
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                lower.startsWith("x-token:") -> reqToken = line.substringAfter(':').trim()
                lower.startsWith("content-type:") -> contentType = lower.substringAfter(':').trim()
                // Browsers attach Origin to every cross-site request; no real
                // client of this server ever sets it. See the /pair-request gate.
                lower.startsWith("origin:") -> hasOrigin = true
            }
        }
        if (aborted) return

        fun respond(code: Int, text: String) {
            val payload = text.toByteArray()
            sock.getOutputStream().apply {
                // text/plain + nosniff for every reply, JSON included: nothing
                // here is meant for a browser, and the clients parse by shape
                // rather than by content type.
                write(
                    ("HTTP/1.1 $code ${reason(code)}\r\n" +
                        "Content-Length: ${payload.size}\r\n" +
                        "Content-Type: text/plain; charset=utf-8\r\n" +
                        "X-Content-Type-Options: nosniff\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                write(payload)
                flush()
            }
        }

        // Read only once we've decided the request is worth reading — see the
        // auth gate below, which answers unauthenticated callers without ever
        // allocating their body.
        fun readBody(): String {
            if (contentLength <= 0) return ""
            val bytes = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(bytes, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            return String(bytes, 0, read, Charsets.UTF_8)
        }

        val path = target.substringBefore('?')

        // Refusals read (a bounded slice of) the body they refuse before
        // answering: closing a socket with unread bytes in it sends a RST,
        // and the client then sees "connection reset" instead of our 403/413
        // — which on the phone debugged as "the TV is offline".
        fun discardBody() {
            var left = minOf(contentLength, MAX_DISCARD_BYTES)
            val buf = ByteArray(8 * 1024)
            while (left > 0) {
                val n = runCatching { input.read(buf, 0, minOf(buf.size, left)) }.getOrDefault(-1)
                if (n < 0) break
                left -= n
            }
        }

        // Declared length is attacker-controlled and allocated in one go, so
        // this check has to come before any body read. The index push carries a
        // whole channel's video list (a deep channel passes 1 MB easily) and is
        // only ever read behind the approved-token gate, so it gets a larger
        // cap — the declared number alone allocates nothing.
        val bodyCap = if (path == "/index") MAX_INDEX_BODY_BYTES else MAX_BODY_BYTES
        if (contentLength > bodyCap) {
            discardBody()
            respond(413, "too large")
            return
        }

        // "Something changed, come and look." Carries no data and grants
        // nothing: this device then runs the reconcile it would have run on its
        // own timer, pulling from peers it already trusts, authenticating as it
        // always does. So the worst a forged nudge achieves is an early sync.
        //
        // Unauthenticated by necessity — a hub has no credential on a device
        // and deliberately never gets one. It is the component most exposed by
        // design (a NAS, eventually reachable from outside), so it must not be
        // able to command devices; it may only ask them to check in. That is
        // why this is a nudge and not the hub pushing config.
        if (method == "POST" && path == "/sync-now") {
            val caller = sock.inetAddress?.hostAddress ?: "?"
            val now = System.currentTimeMillis()
            val verdict = nudgeVerdict(
                hasOrigin, contentType, now - (syncNudgeAt[caller] ?: Long.MIN_VALUE / 2)
            )
            if (verdict != 200) {
                discardBody()
                respond(verdict, if (verdict == 429) "slow down" else "forbidden"); return
            }
            syncNudgeAt[caller] = now
            if (syncNudgeAt.size > 256) syncNudgeAt.clear()
            discardBody()
            // Answered before the sweep runs, not after: the caller is telling
            // us, not waiting on us, and a hub blocking on one asleep TV would
            // delay the nudge to every other device behind it.
            respond(200, "ok")
            onSyncRequested()
            return
        }

        // Unauthenticated pairing endpoints: requesting is open (approval is the
        // security), so a photographed QR grants nothing by itself.
        if (method == "POST" && path == "/pair-request") {
            // Drive-by defence. A page open in a browser anywhere on this LAN
            // can post here — a simple cross-site POST triggers no preflight —
            // and on a TV with no admin yet that is enough to take the slot.
            // A browser cannot send application/json cross-site without a
            // preflight, and always tells us where it came from.
            if (hasOrigin || !contentType.startsWith("application/json")) {
                discardBody()
                respond(403, "forbidden"); return
            }
            // One request per address every few seconds: the pending list is
            // five slots deep, and a loop on the LAN shouldn't get to churn it.
            val caller = sock.inetAddress?.hostAddress ?: "?"
            val now = System.currentTimeMillis()
            val last = pairRequestAt[caller] ?: 0L
            if (now - last < PAIR_REQUEST_MIN_GAP_MS) {
                discardBody()
                respond(429, "slow down"); return
            }
            pairRequestAt[caller] = now
            if (pairRequestAt.size > 256) pairRequestAt.clear()
            val json = runCatching { JSONObject(readBody()) }.getOrNull()
            val phoneToken = json?.optString("token").orEmpty()
            val phoneName = json?.optString("name").orEmpty().ifEmpty { "Phone" }
            if (!Regex("^[0-9a-f]{32}$").matches(phoneToken)) {
                respond(400, "bad token"); return
            }
            // One decision at a time: two requests racing through the
            // empty-approved check would otherwise both bootstrap as admin.
            val status = synchronized(pairingStore) {
                when {
                    pairingStore.isApproved(phoneToken) -> "approved"
                    pairingStore.approvedPhones().isNotEmpty() -> {
                        pairingStore.addPendingRequest(phoneToken, phoneName)
                        "pending"
                    }
                    // Bootstrap: the very first phone becomes the admin, but only
                    // while the TV is showing its pairing QR — see [PairingWindow].
                    PairingWindow.isOpen() -> {
                        pairingStore.approvePhone(phoneToken, phoneName.take(40))
                        // Accepting an admin makes this device a managed (kid) one.
                        pairingStore.setRole(PairingStore.Role.KID)
                        "approved"
                    }
                    // Nothing to approve against and no open window: say so rather
                    // than parking the request where no one can ever reach it.
                    else -> "closed"
                }
            }
            respond(200, JSONObject().put("status", status).toString())
            return
        }
        if (method == "GET" && path == "/pair-status") {
            val me = Regex("me=([0-9a-f]{32})").find(target)?.groupValues?.get(1)
            val status = when {
                me == null -> "unknown"
                pairingStore.isApproved(me) -> "approved"
                me in pairingStore.pendingRequests() -> "pending"
                else -> "unknown"
            }
            respond(200, JSONObject().put("status", status).toString())
            return
        }

        // Everything else requires an approved phone token.
        if (reqToken == null || !pairingStore.isApproved(reqToken)) {
            discardBody()
            respond(403, "forbidden"); return
        }
        when {
            method == "GET" && path == "/pair-pending" -> {
                val arr = org.json.JSONArray()
                pairingStore.pendingRequests().forEach { (token, name) ->
                    arr.put(JSONObject().put("token", token).put("name", name))
                }
                respond(200, arr.toString())
            }
            method == "POST" && path == "/pair-approve" -> {
                val t = Regex("token=([0-9a-f]{32})").find(target)?.groupValues?.get(1)
                val name = pairingStore.pendingRequests()[t]
                if (t != null && name != null) {
                    pairingStore.approvePhone(t, name)
                    respond(200, "approved")
                } else respond(400, "unknown request")
            }
            method == "POST" && path == "/pair-deny" -> {
                val t = Regex("token=([0-9a-f]{32})").find(target)?.groupValues?.get(1)
                if (t != null) {
                    pairingStore.denyRequest(t)
                    respond(200, "denied")
                } else respond(400, "bad token")
            }
            method == "GET" && path == "/stats" -> {
                // A shared TV has one kid on screen but several in the family;
                // the phone names which one's day it wants to see.
                val profileId = Regex("profile=([0-9a-f]{8})").find(target)?.groupValues?.get(1)
                respond(200, statsProvider(profileId))
            }
            method == "GET" && path == "/looks" -> respond(200, looksProvider())
            method == "GET" && path == "/watchstate" -> respond(200, watchStateProvider())
            method == "POST" && path == "/watchstate" -> {
                if (watchStateMerger(readBody())) respond(200, "merged")
                else respond(400, "bad watch state")
            }
            method == "GET" && path == "/verdicts" -> respond(200, verdictsProvider())
            method == "POST" && path == "/verdicts" -> {
                if (verdictsMerger(readBody())) respond(200, "merged")
                else respond(400, "bad verdicts")
            }
            method == "GET" && path == "/index-status" -> respond(200, indexStatusProvider())
            method == "GET" && path == "/index" -> {
                val id = Regex("source=([A-Za-z0-9_-]{1,$MAX_SOURCE_ID_CHARS})").find(target)?.groupValues?.get(1)
                val json = id?.let(indexSourceProvider)
                if (json != null) respond(200, json) else respond(404, "unknown source")
            }
            method == "POST" && path == "/index" -> {
                // Bounded id: it becomes a filename, and an admin token must
                // not be able to mint arbitrarily long ones (or arbitrarily many).
                val id = Regex("source=([A-Za-z0-9_-]{1,$MAX_SOURCE_ID_CHARS})").find(target)?.groupValues?.get(1)
                if (id == null) {
                    discardBody()
                    respond(400, "bad source")
                } else {
                    // First line is the source state, rest is the video array —
                    // one body, no second round trip.
                    val body = readBody()
                    if (indexMerger(id, body)) respond(200, "merged")
                    else respond(400, "bad index payload")
                }
            }
            method == "GET" && path == "/admins" -> {
                val arr = org.json.JSONArray()
                pairingStore.approvedPhones().forEach { (token, name) ->
                    arr.put(JSONObject().put("token", token).put("name", name))
                }
                respond(200, arr.toString())
            }
            method == "POST" && path == "/admin-revoke" -> {
                val t = Regex("token=([0-9a-f]{32})").find(target)?.groupValues?.get(1)
                when {
                    t == null -> respond(400, "bad token")
                    // Lockout guard: a phone can never revoke itself.
                    t == reqToken -> respond(400, "cannot revoke yourself")
                    !pairingStore.isApproved(t) -> respond(400, "unknown admin")
                    else -> {
                        pairingStore.revokePhone(t)
                        respond(200, "revoked")
                    }
                }
            }
            // The phone unpairing from this device: drop its own approval so
            // it can't keep administering a TV it no longer lists. Refused
            // while it is the only admin — that would strand the device.
            method == "POST" && path == "/admin-leave" -> {
                if (pairingStore.approvedPhones().size <= 1) respond(409, "last admin")
                else {
                    pairingStore.revokePhone(reqToken)
                    respond(200, "left")
                }
            }
            method == "GET" && path == "/status" -> respond(
                200,
                JSONObject()
                    .put("hash", ConfigJson.fingerprint(configStore.load()))
                    .put("updatedAt", configStore.updatedAt())
                    // Additive, and the absence of syncV is the signal: a peer
                    // that does not send it cannot merge, so it is a push-only
                    // destination and no tombstone may ever be derived from a
                    // document it produced.
                    .put("syncV", SyncMeta.VERSION)
                    .put("syncHash", configStore.syncHash())
                    // Our own identity token, so the admin phone can key this
                    // device in deviceProfiles (dedicated-to-a-kid assignment).
                    .put("token", pairingStore.deviceToken())
                    // What build answered: "pushed, still out of sync" is a
                    // version skew nine times in ten, and this is how the
                    // phone gets to say so instead of guessing.
                    .put("versionCode", io.yosemitekids.app.BuildConfig.VERSION_CODE)
                    .put("versionName", io.yosemitekids.app.BuildConfig.VERSION_NAME)
                    // Whether this device already syncs with a hub of its
                    // own. A phone brokering hub access for the TVs it
                    // administers needs to know which ones are already done,
                    // or it mints a second token on every attempt and leaves
                    // orphans enrolled on the hub forever.
                    .put("hub", pairingStore.paired().any { it.secretless })
                    .apply { deviceKind()?.let { put("kind", it) } }
                    .toString()
            )
            // Disaster recovery: a freshly reinstalled phone starts with an empty
            // config while this device still holds the family's full one (blocks,
            // safe-list, channels, rules). Serving it back means a reinstall costs
            // one re-pair, not the curation history.
            method == "GET" && path == "/config" -> respond(200, configStore.rawJson())
            method == "POST" && path == "/config" -> {
                val body = readBody()
                // Merged, not replaced. This used to overwrite the file, so
                // two parents pushing to the same TV meant one of them lost
                // everything they had changed. The before/after pair comes
                // from inside the lock, so the notice this raises cannot
                // describe a transition another worker caused.
                //
                // Attribution is free: the pushing phone already authenticated
                // with a token we can name, so no wire change is needed.
                val who = reqToken?.let { pairingStore.approvedPhones()[it] }
                val outcome = configStore.mergeIncoming(body, who)
                if (outcome != null) {
                    if (outcome.changed) {
                        ConfigEvents.onConfigChanged?.invoke()
                        onConfigApplied(outcome.before, outcome.after)
                    }
                    // The body tells the pusher whether we learned anything and
                    // whether they are now behind us, so a phone can stop
                    // guessing from a bare 200.
                    respond(
                        200,
                        JSONObject()
                            .put("changed", outcome.changed)
                            .put("peerBehind", outcome.peerBehind)
                            .put("hash", ConfigJson.fingerprint(outcome.after))
                            .put("syncHash", ConfigMerge.syncHash(outcome.after.sync))
                            .toString()
                    )
                } else respond(400, "bad config")
            }
            method == "POST" && path == "/player" -> {
                val cmd = Regex("cmd=(pause|play)").find(target)?.groupValues?.get(1)
                when {
                    cmd == null -> respond(400, "bad command")
                    RemotePlayerControl.handler?.invoke(cmd) == true -> respond(200, "ok")
                    else -> respond(409, "nothing playing")
                }
            }
            // "Play this on the TV": the parent picked a video on their phone.
            // The device decides whether it may play (blocks, kid visibility)
            // — the phone only asks. 409 = refused or nothing to show it on.
            method == "POST" && path == "/play" -> {
                val json = runCatching { JSONObject(readBody()) }.getOrNull()
                val req = json?.let {
                    RemotePlayerControl.PlayRequest(
                        url = it.optString("url"),
                        title = it.optString("title"),
                        channel = it.optString("channel"),
                        thumb = it.optString("thumb").ifEmpty { null },
                        timePercent = it.optInt("timePercent", 100).coerceIn(0, 400)
                    )
                }
                when {
                    req == null || req.url.isBlank() -> respond(400, "bad request")
                    RemotePlayerControl.playHandler?.invoke(req) == true -> respond(200, "playing")
                    else -> respond(409, "refused")
                }
            }
            // A phone handing this device a hub to sync with.
            //
            // The device cannot do this for itself: a TV has no settings UI
            // at all — its whole parent screen is a QR code — so there is
            // nowhere to type an address, and a remote is the worst possible
            // way to enter one. The phone is already paired with both ends,
            // so it mints a hub token for this device and posts it here.
            //
            // Stored as an ordinary peer, which is the entire point: the
            // reconcile in MainViewModel is gated only on the paired list
            // being non-empty, never on role or device kind, so a TV that
            // has one starts syncing with no new sync code anywhere.
            method == "POST" && path == "/join-hub" -> {
                val body = readBody()
                val hub = runCatching { JSONObject(body) }.getOrNull()
                val host = hub?.optString("host").orEmpty().trim()
                val hubPort = hub?.optInt("port") ?: 0
                val hubToken = hub?.optString("token").orEmpty().trim()
                if (host.isBlank() || hubPort !in 1..65535 || hubToken.isBlank()) {
                    respond(400, "bad hub")
                } else {
                    pairingStore.addPaired(
                        PairedDevice(
                            name = PairedDevice.HUB_NAME,
                            host = host,
                            port = hubPort,
                            token = hubToken,
                            // The hub strips the API key before writing and
                            // has no keystore to put it back, so it must be
                            // judged on the keyless fingerprint or this
                            // device decides it is out of sync forever.
                            secretless = true
                        )
                    )
                    respond(200, "joined")
                }
            }
            // The other direction, so removing a hub on the phone can undo
            // this rather than leaving every TV pointed at a box that is no
            // longer there.
            method == "POST" && path == "/leave-hub" -> {
                pairingStore.paired().filter { it.secretless }
                    .forEach { pairingStore.removePaired(it.key) }
                respond(200, "left")
            }
            method == "POST" && path == "/grant" -> {
                val minutes = Regex("minutes=(\\d+)").find(target)?.groupValues?.get(1)?.toIntOrNull()
                val profileId = Regex("profile=([0-9a-f]{8})").find(target)?.groupValues?.get(1)
                if (minutes != null && minutes in 1..240) {
                    grantHandler(minutes, profileId)
                    respond(200, "granted")
                } else respond(400, "bad minutes")
            }
            else -> respond(404, "not found")
        }
    }

    companion object {
        /** Where the server may live: first free port wins. One spelling for
         *  the bind loop and the re-discovery sweep, so they can't drift. */
        val PORT_RANGE = 8765..8775

        /**
         * The port this device's server actually bound, or 0 before it has.
         *
         * Read by [LanClient] to announce our address on every outbound LAN
         * call. Static rather than reached through the holder in the ui layer,
         * which data must not depend on — and there is only ever one server
         * per process, so there is nothing to disambiguate.
         */
        @Volatile
        var boundPort: Int = 0
            private set

        /** Generous for a request line or header, far below anything harmful. */
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_HEADERS = 50

        /**
         * Config pushes and verdict merges are the big ones — a family with a
         * long safe-list and a season of cached verdicts still lands well
         * inside this, and it caps what a single request can make us allocate.
         */
        private const val MAX_BODY_BYTES = 1024 * 1024

        /** /index only (see the cap check): ~250 bytes/video means 1 MB tops out
         *  near 4,000 videos, and big channels go far past that. */
        private const val MAX_INDEX_BODY_BYTES = 8 * 1024 * 1024

        private const val CORE_WORKERS = 2
        private const val MAX_WORKERS = 8
        private const val MAX_QUEUED = 16

        /** How much of a refused body is read before answering (see discardBody). */
        private const val MAX_DISCARD_BYTES = 256 * 1024
        private const val PAIR_REQUEST_MIN_GAP_MS = 3_000L

        /**
         * A nudge is cheap to send and costs a full peer sweep to honour, so
         * the gap is wider than pairing's. Ten seconds still collapses a burst
         * of edits in the hub GUI into about one sweep, which is the point.
         */
        internal const val SYNC_NUDGE_MIN_GAP_MS = 10_000L

        /**
         * Whether to honour a `POST /sync-now`: 200, 403 or 429.
         *
         * Pure, and separate from the route, because this is the whole security
         * surface of the one unauthenticated write-ish endpoint on the device —
         * and `LanServer.handle` needs a Context, so nothing about it could
         * otherwise be asserted. The route is a thin caller.
         *
         * @param sinceLastFromCaller ms since this address last nudged us.
         */
        internal fun nudgeVerdict(
            hasOrigin: Boolean,
            contentType: String,
            sinceLastFromCaller: Long
        ): Int = when {
            // Drive-by defence, as on /pair-request. A page open in a browser
            // on this LAN must not be able to put every TV in the house into a
            // sweep on a timer. A browser cannot send application/json
            // cross-site without a preflight, and always says where it came
            // from — so requiring both closes it.
            hasOrigin -> 403
            !contentType.startsWith("application/json") -> 403
            sinceLastFromCaller < SYNC_NUDGE_MIN_GAP_MS -> 429
            else -> 200
        }
        /** Source ids are YouTube ids or URL-path forms — nowhere near this. */
        private const val MAX_SOURCE_ID_CHARS = 64

        private fun reason(code: Int): String = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            429 -> "Too Many Requests"
            else -> "OK"
        }

        /** The TV's LAN IPv4, for the pairing QR. */
        fun localIp(): String? =
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { it.isSiteLocalAddress && it is java.net.Inet4Address }
                ?.hostAddress
    }
}

/**
 * How long to wait before sweeping the subnet for a device again.
 *
 * A flat five minutes was written for a phone sweeping while a parent watched
 * a settings screen. The same code now runs from a periodic worker on a TV
 * nobody is looking at, where a device that is simply switched off would cost
 * a full /24 × port-range scan on every tick, for as long as it stays off —
 * unattended, on living-room hardware, forever.
 *
 * Doubling per consecutive miss keeps the first few attempts prompt (a TV
 * rebooting, a router restarting) and lets a genuine absence go quiet.
 *
 * Backing off is close to free, because the common recovery never needs a
 * sweep at all: a device that comes back on the address it left answers
 * `fullStatus` and is found without one. A sweep is only for a device that
 * MOVED while it was away, which is the rare case.
 *
 * Kept pure, and out of [LanClient], so a JVM unit test can state the rule
 * without a Context or a network.
 */
internal object SweepBackoff {
    /** The first wait, and the one a device that was just found gets again. */
    internal const val BASE_MS = 5 * 60_000L

    /**
     * Four sweeps a day for a device that has been off for a week. Long
     * enough to be negligible, short enough that a TV moved to a new subnet
     * and left off overnight is still found the next day without anyone
     * opening settings.
     */
    internal const val MAX_MS = 6 * 60 * 60_000L

    /** [BASE_MS] doubled once per consecutive miss, capped at [MAX_MS]. */
    fun cooldownMs(misses: Int): Long {
        if (misses <= 0) return BASE_MS
        // Shift rather than pow, and clamp the shift itself: 1L shl 64 is a
        // no-op in the JVM (the count is taken mod 64), so a device missed
        // often enough would silently drop back to five minutes.
        val doubled = BASE_MS shl misses.coerceAtMost(20)
        return if (doubled <= 0L || doubled > MAX_MS) MAX_MS else doubled
    }
}

/**
 * The form factor a device reports on `/status` (`kind`).
 *
 * Three answers, deliberately coarse: a TV is what leanback mode says it is,
 * and the tablet/phone line is the 600dp one Android itself draws. Anything
 * finer would be a guess, and the list this feeds shows no badge sooner
 * than a wrong one.
 */
object DeviceKind {
    const val TV = "tv"
    const val TABLET = "tablet"
    const val PHONE = "phone"

    fun of(context: android.content.Context): String {
        val cfg = context.resources.configuration
        val tv = (context.getSystemService(android.content.Context.UI_MODE_SERVICE)
            as? android.app.UiModeManager)?.currentModeType ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        return when {
            tv -> TV
            cfg.smallestScreenWidthDp >= 600 -> TABLET
            else -> PHONE
        }
    }
}

/** Phone side: pushes to paired TVs. */
object LanClient {

    data class DeviceStatus(
        val hash: String,
        val updatedAt: Long,
        val deviceToken: String?,
        /**
         * The peer's sync-format version, or null for a build that predates it.
         * Null means push-only: this device may send it a config but must
         * never treat what it sends back as a merge source, because a legacy
         * document restamps `updatedAt` at serialization time and therefore
         * always claims to be brand new.
         */
        val syncV: Int? = null,
        val syncHash: String? = null,
        /** The build this peer is running, for a version-skew answer. */
        val versionName: String? = null,
        /** Its versionCode, for "behind on updates" — comparable where versionName is not. */
        val versionCode: Int? = null,
        /** Whether this peer already syncs with a hub of its own. */
        val hasHub: Boolean = false,
        /** "tv", "tablet" or "phone", or null for a build that predates the key. */
        val kind: String? = null
    )

    /** The device's config fingerprint + last-edit time, or null when unreachable. */
    suspend fun status(device: PairedDevice): Pair<String, Long>? =
        fullStatus(device)?.let { it.hash to it.updatedAt }

    /**
     * A paired device stopped answering at its stored host:port — usually a
     * DHCP re-lease after the TV sat powered off, sometimes the server binding
     * a different port in its 8765..8775 range. Sweep our own /24 for a device
     * that (a) accepts our token and (b) reports the identity we paired with,
     * and return the entry re-pointed at wherever it actually is.
     *
     * Identity is the gate against adopting the wrong device: a kid tablet and
     * the TV both accept this phone's token, and both answer /status. An
     * id-less legacy entry (pre-backfill) is only re-pointed when the sweep
     * finds exactly one candidate — ambiguity means keep the stale address and
     * let the parent re-pair, rather than silently binding to the wrong kid.
     *
     * Null result = nothing found (device off / other network) or the cooldown
     * hasn't lapsed. The cooldown keeps the periodic sync from sweeping the
     * LAN every minute while a TV is legitimately powered down.
     */
    suspend fun rediscover(device: PairedDevice): PairedDevice? = withContext(Dispatchers.IO) {
        // A hub is never swept for. Its address was typed in by a parent
        // rather than discovered, it is normally a server with a DHCP
        // reservation or a static lease, and scanning the subnet to guess at
        // something a human can retype is not a trade worth making on TV
        // hardware. If a hub really moved, it gets re-entered. Judged on the
        // flag, not the name: a parent can rename the hub.
        if (device.secretless) return@withContext null
        val now = System.currentTimeMillis()
        // Per device, not global: one cooldown for the whole list meant a
        // powered-off TV's fruitless sweep locked the kid's tablet — sitting
        // right there at a new address — out of re-discovery for five minutes.
        val key = device.key
        val last = lastSweepAt[key] ?: 0L
        if (now - last < SweepBackoff.cooldownMs(sweepMisses[key] ?: 0)) return@withContext null
        lastSweepAt[key] = now
        val prefixes = localV4Prefixes()
        if (prefixes.isEmpty()) return@withContext null

        val candidates = kotlinx.coroutines.coroutineScope {
            val gate = Semaphore(48)
            prefixes.flatMap { prefix -> (1..254).map { "$prefix.$it" } }
                .map { host ->
                    async {
                        gate.withPermit { probeHost(host, device.token) }
                    }
                }.awaitAll().filterNotNull()
        }

        // One line per sweep so "found nothing" and "found the wrong thing"
        // are distinguishable from logcat without a debugger.
        android.util.Log.i("YosemiteKids",
            "lan sweep for ${device.name}: ${candidates.size} candidate(s) " +
                candidates.joinToString { "${it.first.first}:${it.first.second}" }
        )
        val match = when {
            device.id != null -> candidates.firstOrNull { it.second == device.id }
            candidates.size == 1 -> candidates.single()
            else -> null
        } ?: run {
            // Nothing here. Wait longer before the next scan — see SweepBackoff.
            sweepMisses[key] = (sweepMisses[key] ?: 0) + 1
            return@withContext null
        }

        val (hostPort, identity) = match
        // Found: the next failure may sweep straight away — a TV that moves
        // twice in an evening (reboot, router restart) is not an attack.
        lastSweepAt.remove(key)
        sweepMisses.remove(key)
        device.copy(host = hostPort.first, port = hostPort.second, id = identity ?: device.id)
    }

    /**
     * One host's slice of the sweep: walk the server's port range in order.
     * A raw connect classifies each port BEFORE any HTTP: no SYN-ACK inside
     * the timeout means nobody lives at this address at all — skip the whole
     * host, which is what keeps a full-subnet sweep at seconds, not minutes.
     * Refused means the host is alive on some other port; connected-but-mute
     * (a hung app still holding the port) means move on to the next port —
     * an HTTP-level timeout must NOT be read as "dead host".
     */
    private fun probeHost(host: String, token: String): Pair<Pair<String, Int>, String?>? {
        for (port in LanServer.PORT_RANGE) {
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress(host, port), CONNECT_PROBE_MS)
                }
            } catch (e: java.net.SocketTimeoutException) {
                return null // dead host: don't walk ten more ports into the void
            } catch (e: java.io.IOException) {
                continue // refused: alive, just nothing on this port
            }
            try {
                scanClient.newCall(
                    Request.Builder()
                        .url("http://$host:$port/status")
                        .header("X-Token", token)
                        .build()
                ).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        return (host to port) to json.optString("token").ifEmpty { null }
                    }
                    // Answered but refused us (403: another family's device, or
                    // our approval was revoked — nothing a rescan can fix).
                    return null
                }
            } catch (e: java.io.IOException) {
                // Listening but not speaking our HTTP: try the next port.
            }
        }
        return null
    }

    /** The /24 prefixes of our own site-local IPv4 addresses ("192.168.0"). */
    private fun localV4Prefixes(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress.substringBeforeLast('.') }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())

    /** Sweep-only client: hundreds of probes, most of them dead addresses —
     *  the shared client's 5 s connect timeout and retry interceptor would
     *  stretch one sweep into minutes. */
    private val scanClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(1_500, java.util.concurrent.TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val lastSweepAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Consecutive fruitless sweeps per device; the backoff's only input. */
    private val sweepMisses = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private const val CONNECT_PROBE_MS = 400

    /**
     * The LAN's own client. The shared [Http.client] is tuned for the
     * internet — 5 s connect, a retry interceptor, connection-failure retries
     * — which turns one call to a TV that is switched off into ~10 s of
     * waiting, and a settings screen that checks three things per device into
     * half a minute of "checking…". Across a living room a device answers in
     * milliseconds or not at all; a large config push still gets its read time.
     */
    private val lanClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(1_500, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** Status including the device's own identity token (for profile assignment). */
    suspend fun fullStatus(device: PairedDevice): DeviceStatus? = withContext(Dispatchers.IO) {
        runCatching {
            request(device, "GET", "/status", null).use { resp ->
                if (!resp.isSuccessful) {
                    // An HTTP answer is NOT "unreachable" — a 403 here means the
                    // device dropped our approval, which re-pairing fixes and
                    // nothing else will. Say so, or it debugs like a network bug.
                    android.util.Log.i("YosemiteKids", "status ${device.name}: HTTP ${resp.code}"
                    )
                    return@withContext null
                }
                val json = JSONObject(resp.body?.string().orEmpty())
                DeviceStatus(
                    json.getString("hash"),
                    json.optLong("updatedAt", 0L),
                    json.optString("token").ifEmpty { null },
                    syncV = if (json.has("syncV")) json.optInt("syncV") else null,
                    syncHash = json.optString("syncHash").ifEmpty { null },
                    // Both already on the wire and both were being dropped.
                    // The version is how "pushed, still out of sync" gets to
                    // be answered as version skew instead of guessed at.
                    versionName = json.optString("versionName").ifEmpty { null },
                    versionCode = if (json.has("versionCode")) json.optInt("versionCode") else null,
                    hasHub = json.optBoolean("hub", false),
                    kind = json.optString("kind").ifEmpty { null }
                )
            }
        }.getOrNull()
    }

    suspend fun pushConfig(device: PairedDevice, configJson: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                request(device, "POST", "/config", configJson).use { it.isSuccessful }
            }.getOrDefault(false)
        }

    /**
     * Hand a device the hub it should sync with.
     *
     * Brokered rather than done on the device, because a TV has no settings
     * UI to type an address into. The token is minted by this phone against
     * the hub and belongs to that device alone.
     */
    suspend fun sendHubDetails(
        device: PairedDevice,
        hubHost: String,
        hubPort: Int,
        hubToken: String
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("host", hubHost).put("port", hubPort).put("token", hubToken)
                .toString()
            request(device, "POST", "/join-hub", body).use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Undo the above. Best effort: a TV that is off simply keeps its copy. */
    suspend fun clearHub(device: PairedDevice): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            request(device, "POST", "/leave-hub", "{}").use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** The device's full config JSON (channels, blocks, safe-list, rules), or null. */
    suspend fun fetchConfig(device: PairedDevice): String? = withContext(Dispatchers.IO) {
        runCatching {
            request(device, "GET", "/config", null).use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    /** Pause/resume what the TV is playing. False when unreachable or idle. */
    suspend fun playerCommand(device: PairedDevice, cmd: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                request(device, "POST", "/player?cmd=$cmd", "").use { it.isSuccessful }
            }.getOrDefault(false)
        }

    suspend fun grant(device: PairedDevice, minutes: Int, profileId: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            val profileParam = profileId?.let { "&profile=$it" } ?: ""
            runCatching {
                request(device, "POST", "/grant?minutes=$minutes$profileParam", "")
                    .use { it.isSuccessful }
            }.getOrDefault(false)
        }

    /** Ask a TV to pair; returns "approved", "pending", or null (unreachable). */
    suspend fun requestPairing(host: String, port: Int, myName: String, myToken: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                raw(host, port, "POST", "/pair-request",
                    JSONObject().put("name", myName).put("token", myToken).toString(), null
                ).use { resp ->
                    if (!resp.isSuccessful) null
                    else JSONObject(resp.body?.string().orEmpty()).optString("status").ifEmpty { null }
                }
            }.getOrNull()
        }

    /** Poll our request's fate: "approved", "pending", or "unknown" (denied). */
    suspend fun pairStatus(host: String, port: Int, myToken: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                raw(host, port, "GET", "/pair-status?me=$myToken", null, null).use { resp ->
                    if (!resp.isSuccessful) null
                    else JSONObject(resp.body?.string().orEmpty()).optString("status").ifEmpty { null }
                }
            }.getOrNull()
        }

    /** Pending pairing requests on a TV (approver's view): name to token. */
    suspend fun pendingRequests(device: PairedDevice): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                request(device, "GET", "/pair-pending", null).use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val arr = org.json.JSONArray(resp.body?.string().orEmpty())
                    (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        o.getString("name") to o.getString("token")
                    }
                }
            }.getOrDefault(emptyList())
        }

    /** The looks a kid chose on that device and the phone hasn't adopted yet (`{}` when none); null when unreachable. */
    suspend fun looks(device: PairedDevice): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                request(device, "GET", "/looks", null).use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            }.getOrNull()
        }

    /** Raw stats JSON from a device (for one kid when [profileId] is set), or null when unreachable. */
    suspend fun stats(device: PairedDevice, profileId: String? = null): String? =
        withContext(Dispatchers.IO) {
            val query = profileId?.let { "?profile=$it" } ?: ""
            runCatching {
                request(device, "GET", "/stats$query", null).use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            }.getOrNull()
        }

    /**
     * Ask a device to play a video now. True = it started; false = refused
     * (blocked there, nothing on screen to play it) or unreachable.
     */
    suspend fun play(device: PairedDevice, req: RemotePlayerControl.PlayRequest): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("url", req.url)
                .put("title", req.title)
                .put("channel", req.channel)
                .put("thumb", req.thumb ?: "")
                .put("timePercent", req.timePercent)
                .toString()
            runCatching {
                request(device, "POST", "/play", body).use { it.isSuccessful }
            }.getOrDefault(false)
        }

    /**
     * Unpairing: drop our own approval on the device so a forgotten TV can't
     * still be administered by a phone that no longer lists it. Best effort —
     * the device may be off, and the local unpair proceeds regardless.
     */
    suspend fun leave(device: PairedDevice): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            request(device, "POST", "/admin-leave", "").use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun fetchWatchState(device: PairedDevice): String? = withContext(Dispatchers.IO) {
        runCatching {
            request(device, "GET", "/watchstate", null).use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    suspend fun pushWatchState(device: PairedDevice, json: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                request(device, "POST", "/watchstate", json).use { it.isSuccessful }
            }.getOrDefault(false)
        }

    /** A device's AI screening verdicts, or null (unreachable, or a pre-verdict-sharing build). */
    suspend fun fetchVerdicts(device: PairedDevice): String? = withContext(Dispatchers.IO) {
        runCatching {
            request(device, "GET", "/verdicts", null).use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    suspend fun pushVerdicts(device: PairedDevice, json: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                request(device, "POST", "/verdicts", json).use { it.isSuccessful }
            }.getOrDefault(false)
        }

    /** A device's per-source index status (hashes), or null when unreachable. */
    suspend fun indexStatus(device: PairedDevice): String? = withContext(Dispatchers.IO) {
        runCatching {
            request(device, "GET", "/index-status", null).use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    /** Push one indexed source (state line + video array) to a device. */
    suspend fun pushIndexSource(device: PairedDevice, sourceId: String, body: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                request(device, "POST", "/index?source=$sourceId", body).use { it.isSuccessful }
            }.getOrDefault(false)
        }

    /** Approved admin phones on a TV: name to token. */
    suspend fun admins(device: PairedDevice): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                request(device, "GET", "/admins", null).use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val arr = org.json.JSONArray(resp.body?.string().orEmpty())
                    (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        o.getString("name") to o.getString("token")
                    }
                }
            }.getOrDefault(emptyList())
        }

    suspend fun revokeAdmin(device: PairedDevice, token: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                request(device, "POST", "/admin-revoke?token=$token", "").use { it.isSuccessful }
            }.getOrDefault(false)
        }

    suspend fun approveRequest(device: PairedDevice, token: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                request(device, "POST", "/pair-approve?token=$token", "").use { it.isSuccessful }
            }.getOrDefault(false)
        }

    suspend fun denyRequest(device: PairedDevice, token: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                request(device, "POST", "/pair-deny?token=$token", "").use { it.isSuccessful }
            }.getOrDefault(false)
        }

    private fun request(device: PairedDevice, method: String, path: String, body: String?) =
        raw(device.host, device.port, method, path, body, device.token)

    private fun raw(
        host: String, port: Int, method: String, path: String, body: String?, token: String?
    ) = lanClient.newCall(
        Request.Builder()
            .url("http://$host:$port$path")
            .apply { token?.let { header("X-Token", it) } }
            // Where to reach us back. A hub records this against our token so
            // it can push an edit made in its own admin pages, instead of
            // sitting on it until we next happen to ask. Our address it can
            // see; the port it cannot — an inbound connection's source port is
            // ephemeral. Sent on every call, so a DHCP move or a port change
            // corrects itself on the next sync rather than needing a re-pair.
            //
            // A claim, not a credential: it says only where to deliver a
            // config this peer already trusts us to send, and the recipient
            // still authenticates every push. Omitted before our own server
            // has bound, when there would be nowhere to deliver to.
            .apply {
                LanServer.boundPort.takeIf { it > 0 }
                    ?.let { header("X-Device-Port", it.toString()) }
            }
            .method(method, body?.toRequestBody("application/json".toMediaType()))
            .build()
    ).execute()
}
