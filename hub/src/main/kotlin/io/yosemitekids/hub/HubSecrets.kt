package io.yosemitekids.hub

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * The one credential this box is allowed to hold: the family's AI API key.
 *
 * In a file of its own, and that is the whole design. The key must never enter
 * `config.json`, because that document is served to every enrolled device, is
 * copied into `versions/` five deep, is rendered by the admin page, and is
 * what `GET /api/backup` hands a parent to keep somewhere else. One field in
 * one shared document is four leaks, and each of them would look like it was
 * working.
 *
 * So `HubStore.commit` still strips `ai.apiKey` from everything it writes, and
 * the key is overlaid back on only in the two places that need it: the
 * fingerprint a phone holding the same key would compute, and `GET /config` to
 * a parent. That mirrors `ConfigStore.withSecrets`/`rawJson` on the phone,
 * which solves the same problem with a Keystore this box does not have.
 *
 * **It is not encrypted, and it cannot honestly be.** A NAS has no hardware
 * keystore and this container has no secret of its own to key one with — a
 * key derived from something on the same volume protects against nothing. The
 * file is readable by root, by anything else sharing the bind mount, and by
 * whoever holds a backup of that folder. `docs/HUB.md` says so in those words,
 * and recommends a separate provider key with a spending cap, because the
 * honest threat model is "someone got into the NAS" = "rotate the key".
 */
class HubSecrets(dataDir: File) {

    private val file = File(dataDir, "secrets.json")
    private val lock = Any()

    /** The stored key, or "" when there is none. Never logged, never returned to a page. */
    fun apiKey(): String = synchronized(lock) {
        runCatching { JSONObject(file.readText()).optString(KEY) }.getOrDefault("")
    }

    fun hasKey(): Boolean = apiKey().isNotBlank()

    /**
     * The last four characters, which is the most a page may ever be shown.
     *
     * Enough for a parent to tell "the key I pasted" from "some other key",
     * which is the only question the GUI has to answer. Blank when there is no
     * key, and blank for anything short enough that four characters would be
     * most of it.
     */
    fun tail(): String = apiKey().let { if (it.length >= 8) it.takeLast(4) else "" }

    /** Set, replace or (with a blank) clear it. */
    fun setApiKey(key: String) = synchronized(lock) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            file.delete()
            return
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().put(KEY, trimmed).toString())
        // Before the rename, so the file is never briefly world-readable
        // under its real name.
        restrict(tmp)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                file.writeText(JSONObject().put(KEY, trimmed).toString())
                restrict(file)
                tmp.delete()
            }
        }
    }

    /**
     * Owner-only, best effort, and **never fatal**.
     *
     * A bind mount on a Synology carries ACLs that make POSIX permission bits
     * advisory at best, and a volume on an SMB or exFAT backing store has no
     * concept of them at all — see the volume-permission section of
     * `docs/HUB.md`, which cost an evening once already. Refusing to store the
     * key because the filesystem will not narrow it would take a feature away
     * from every family on such a box in exchange for no security anywhere.
     */
    private fun restrict(f: File) {
        runCatching {
            Files.setPosixFilePermissions(
                f.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            )
        }.onFailure {
            runCatching {
                f.setReadable(false, false)
                f.setWritable(false, false)
                f.setReadable(true, true)
                f.setWritable(true, true)
            }
        }
    }

    private companion object {
        const val KEY = "aiApiKey"
    }
}
