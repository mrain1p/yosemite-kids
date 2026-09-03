package io.pickwick.hub

import java.io.File

/**
 * The one precondition the hub cannot start without: a writable data volume.
 *
 * This exists because getting it wrong produced the worst possible failure.
 * The volume was denied, HubTokens wrote its first file, and the container
 * died with a FileNotFoundException on devices.json.tmp — from a stack trace
 * whose deepest frame is FileOutputStream.open0, nothing tells you the cause
 * is a bind mount's ownership. restart:unless-stopped then repeated that trace
 * every few seconds, so the log filled with the symptom and never once
 * mentioned the admin token, which is the only thing a parent needs from it.
 *
 * A precondition that is checked once, before anything is opened, can say what
 * is actually wrong. Checked implicitly by the first write, it cannot.
 *
 * The writability probe is injected so this is testable without needing a
 * directory the test process is genuinely forbidden to write — which is not
 * something a unit test can arrange portably, least of all on Windows.
 */
internal fun dataDirProblem(
    dir: File,
    canWrite: (File) -> Boolean = ::probeByWriting
): String? {
    if (!dir.exists() && !dir.mkdirs()) {
        return listOf(
            "The data directory ${dir.absolutePath} does not exist and could not be created.",
            "In Docker this is the folder mounted at /data. Check the volume line in",
            "docker-compose.yml points somewhere that exists on the host."
        ).joinToString(System.lineSeparator())
    }
    if (!dir.isDirectory) {
        return "The data path ${dir.absolutePath} exists but is not a directory."
    }
    if (!canWrite(dir)) {
        return listOf(
            "The data directory ${dir.absolutePath} is not writable.",
            "",
            "The hub keeps config.json and devices.json here, so it cannot start.",
            "In Docker this is a mounted host folder, and its ownership is the",
            "host's, not the container's. On a NAS an ACL may also be overriding",
            "the ownership, in which case chown alone will not be enough.",
            "",
            "See the permissions section of docs/HUB.md."
        ).joinToString(System.lineSeparator())
    }
    return null
}

/**
 * Actually create a file. File.canWrite() consults the permission bits and is
 * wrong precisely where it matters — an ACL that denies the write, or a
 * read-only mount, both report writable.
 */
private fun probeByWriting(dir: File): Boolean {
    val probe = File(dir, ".pickwick-write-test")
    return try {
        probe.writeText("")
        true
    } catch (e: Exception) {
        false
    } finally {
        probe.delete()
    }
}
