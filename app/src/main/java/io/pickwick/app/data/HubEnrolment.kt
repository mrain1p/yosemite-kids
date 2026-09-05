package io.pickwick.app.data

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
        /** Something answered but it is not a Pickwick hub. */
        data object NotAHub : Failure
        /** The admin token was refused. */
        data object BadAdminToken : Failure
        /** The hub refused the code: expired, or too many wrong guesses. */
        data class Refused(val reason: String) : Failure
    }

    class HubError(val failure: Failure) : Exception(failure.toString())

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

    /** Is something there, and is it a Pickwick hub? Checked before asking to join. */
    suspend fun probe(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = okhttp3.Request.Builder().url("${base(host, port)}/health").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw HubError(Failure.NotAHub)
            }
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
                // Recorded now, by the side that knows. The hub strips the
                // API key before writing and has no SecretStore to put it
                // back, so its config fingerprint is permanently the
                // keyless one and comparing it against this phone's full
                // fingerprint can never match.
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
                    401 -> throw HubError(Failure.BadAdminToken)
                    409 -> throw HubError(
                        Failure.Refused(JSONObject(body).optString("refused", "UNKNOWN_CODE"))
                    )
                    else -> throw HubError(Failure.NotAHub)
                }
            }
    }

    private fun post(root: String, path: String, body: String, adminToken: String?): Pair<Int, String> {
        val req = okhttp3.Request.Builder()
            .url("$root$path")
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply { adminToken?.let { header("X-Admin-Token", it) } }
            .build()
        client.newCall(req).execute().use { resp ->
            return resp.code to (resp.body?.string().orEmpty())
        }
    }


}
