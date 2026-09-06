package io.yosemitekids.app.data

import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Joining a Docker hub.
 *
 * Two steps, and the split is the security: the device asks to join and is
 * given a code, then a human holding the hub's admin secret approves that
 * code. The code proves someone is at the device; the admin token proves they
 * may add one. Neither alone is enough.
 *
 * What comes back is an ordinary [PairedDevice]. That is the whole trick — the
 * hub answers `/status` and `/config` exactly as a TV does, so once it is in
 * the paired list the existing reconcile treats it as a peer and no sync code
 * had to learn anything new.
 */
object HubEnrolment {

    /**
     * The port a hub listens on unless its compose file says otherwise —
     * the bottom of the range the app's own LAN server uses, so a parent
     * who has seen one number has seen both. The join form assumes it when
     * the address typed carries none; the hub card omits it when it holds.
     */
    const val DEFAULT_PORT = 8765

    /**
     * Its own client rather than [Http.client]: a hub is on the LAN or one hop
     * away, so the internet client's five-second connect and retry
     * interceptor would turn "wrong address" into a ten-second wait behind a
     * spinner. Same reasoning as `LanClient`'s own client.
     */
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** Why joining failed, in terms a parent can act on. */
    sealed interface Failure {
        /** Nothing answered — wrong address, hub not running, different network. */
        data object Unreachable : Failure
        /** Something answered but it is not a Yosemite Kids hub. */
        data object NotAHub : Failure
        /**
         * The secret was refused.
         *
         * One case, not two, because there is one header: the hub decides
         * whether what arrived was the password or the recovery token
         * ([HubTokens.verifyAdminSecret] on that side), and this phone never
         * asks a parent which of the two they are holding.
         */
        data object BadAdminSecret : Failure
        /**
         * Too many wrong secrets: the hub is refusing every attempt, the
         * right one included, for [retryAfterSeconds].
         *
         * Its own case because 429 used to arrive here as [NotAHub] —
         * "something answered, but it isn't a Yosemite Kids hub" — which sends
         * a parent to check an address that was right all along, while the
         * lockout they actually hit doubles behind them.
         */
        data class Throttled(val retryAfterSeconds: Int) : Failure
        /** The hub refused the code: expired, or too many wrong guesses. */
        data class Refused(val reason: String) : Failure
    }

    class HubError(val failure: Failure) : Exception(failure.toString())

    /**
     * What a hub says about itself before anyone has authenticated: whether it
     * has been claimed with a password.
     *
     * The only thing it decides is which word the phone's one secret field
     * uses. A hub too old to answer `/setup` reads as `hasPassword = false`,
     * which is what it is — that build has no password to have.
     */
    data class Setup(val hasPassword: Boolean)

    /**
     * [host] may be typed by a parent, so it is accepted in the forms people
     * actually write: bare address, with a port, or with a scheme.
     */
    private fun base(host: String, port: Int): String {
        val trimmed = host.trim().removeSuffix("/")
        val withScheme = if (trimmed.startsWith("http")) trimmed else "http://$trimmed"
        // A port already in the address wins over the field, since someone who
        // typed one meant it.
        return if (Regex(":\\d+$").containsMatchIn(withScheme)) withScheme else "$withScheme:$port"
    }

    /**
     * Is something there, is it a Yosemite Kids hub, and has it been claimed?
     *
     * `/health` answers the first two, as it always has. `/setup` answers the
     * third, unauthenticated and with exactly one key, so the field a parent
     * is about to type into can be labelled correctly before they type.
     *
     * A hub older than `/setup` does not 404 it: `HubServer` registers `"/"`
     * last, so an unknown GET comes back 200 with the admin page's HTML. That
     * is why this reads the body rather than the status — anything that is not
     * the one JSON object means "no password", which is the truth on every
     * build that predates one.
     */
    suspend fun probe(host: String, port: Int): Result<Setup> = withContext(Dispatchers.IO) {
        runCatching {
            val root = base(host, port)
            val req = okhttp3.Request.Builder().url("$root/health").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw HubError(Failure.NotAHub)
            }
            Setup(hasPassword = get(root, "/setup").let { (status, body) ->
                status == 200 && runCatching { JSONObject(body).optBoolean("password") }.getOrDefault(false)
            })
        }.recoverCatching { e ->
            throw if (e is HubError) e else HubError(Failure.Unreachable)
        }
    }

    /**
     * Join, and come back as a paired device.
     *
     * [deviceName] is what the hub will show beside this device's changes, so
     * it is the phone's name rather than anything generated.
     */
    suspend fun join(
        host: String,
        port: Int,
        adminToken: String,
        deviceName: String
    ): Result<PairedDevice> = withContext(Dispatchers.IO) {
        runCatching {
            val root = base(host, port)

            val token = mint(root, adminToken, deviceName)

            // Stored like any other peer. From here the ordinary reconcile
            // takes over — the hub is simply a device that never sleeps.
            PairedDevice(
                name = PairedDevice.HUB_NAME,
                host = host.trim().removePrefix("http://").removePrefix("https://")
                    .substringBefore(':').removeSuffix("/"),
                port = Regex(":(\\d+)$").find(host.trim())?.groupValues?.get(1)?.toIntOrNull() ?: port,
                token = token,
                // Recorded now, by the side that knows: this is a hub, and
                // this phone put it in the list. Nothing the peer says can
                // set that.
                isHub = true,
                // Keyless until the hub's own /status says otherwise. A hub
                // with no key store strips the API key before writing, so its
                // fingerprint is the keyless one and comparing it against this
                // phone's full form could never match. The sweep flips this
                // from `holdsKey` — which is a claim about the peer's own
                // storage and not about this phone's checks.
                secretless = true
            )
        }.recoverCatching { e ->
            throw if (e is HubError) e else HubError(Failure.Unreachable)
        }
    }

    /**
     * Mint a hub token for a device that is not this phone.
     *
     * A TV cannot enrol itself: its entire parent settings screen is a QR
     * code, so there is no field to type a hub address into, and a remote is
     * the worst imaginable way to enter one. This phone is already paired
     * with both the TV and the hub, so it does the introduction.
     *
     * [deviceName] is what the hub lists the device as, so it should be the
     * name the parent already calls it — "Living Room", not a token.
     */
    suspend fun tokenFor(
        host: String,
        port: Int,
        adminToken: String,
        deviceName: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching { mint(base(host, port), adminToken, deviceName) }
            .recoverCatching { e -> throw if (e is HubError) e else HubError(Failure.Unreachable) }
    }

    /** enrol + approve, shared by [join] and [tokenFor] so they cannot drift. */
    private fun mint(root: String, adminToken: String, deviceName: String): String {
        val code = post(root, "/enrol", JSONObject().put("name", deviceName).toString(), null)
            .let { (status, body) ->
                if (status != 200) throw HubError(Failure.NotAHub)
                JSONObject(body).optString("code").takeIf { it.isNotBlank() }
                    ?: throw HubError(Failure.NotAHub)
            }
        return post(root, "/approve", JSONObject().put("code", code).toString(), adminToken)
            .let { (status, body) ->
                when (status) {
                    200 -> JSONObject(body).optString("token").takeIf { it.isNotBlank() }
                        ?: throw HubError(Failure.NotAHub)
                    401 -> throw HubError(Failure.BadAdminSecret)
                    // The hub's own number, not a guess: it names the wait in
                    // the body and repeats it in Retry-After, and the two are
                    // the same value. A default of a full window is the safe
                    // way round — telling a parent to come back too early is
                    // another failed attempt, which lengthens the lockout.
                    429 -> throw HubError(
                        Failure.Throttled(
                            runCatching { JSONObject(body).optInt("retryAfter") }.getOrDefault(0)
                                .takeIf { it > 0 } ?: DEFAULT_RETRY_AFTER_SECONDS
                        )
                    )
                    409 -> throw HubError(
                        Failure.Refused(JSONObject(body).optString("refused", "UNKNOWN_CODE"))
                    )
                    else -> throw HubError(Failure.NotAHub)
                }
            }
    }

    /** What to tell a parent when a 429 carries no number of its own. */
    private const val DEFAULT_RETRY_AFTER_SECONDS = 15 * 60

    private fun post(root: String, path: String, body: String, adminToken: String?): Pair<Int, String> {
        val req = okhttp3.Request.Builder()
            .url("$root$path")
            // One header for one secret. The hub decides whether what arrived
            // was the password or the recovery token; this side never asks a
            // parent to classify what they are holding, and never keeps it.
            .apply { adminToken?.let { header("X-Admin-Token", it) } }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            return resp.code to (resp.body?.string().orEmpty())
        }
    }

    private fun get(root: String, path: String): Pair<Int, String> {
        val req = okhttp3.Request.Builder().url("$root$path").get().build()
        client.newCall(req).execute().use { resp ->
            return resp.code to (resp.body?.string().orEmpty())
        }
    }
}
