package io.yosemitekids.app.data

import java.io.File

/**
 * Write [text] to this file so that a reader never sees half of it.
 *
 * Every store in this product keeps its state as one JSON document, and a
 * plain `writeText` truncates before it writes: a crash, a power cut or a
 * container stopped mid-write leaves a file that parses as nothing. What
 * follows is worse than the loss, because the readers are lenient by design —
 * a torn `config.json` reads as a family with no children, a torn source index
 * reads as a channel never crawled — so the damage arrives as *plausible
 * emptiness* rather than as an error somebody would chase.
 *
 * Sibling temp file, flushed to the platter, then renamed over the target:
 * the rename is the atomic step, so the file is either entirely the old
 * document or entirely the new one. `ATOMIC_MOVE` is what says so on the
 * filesystems that can promise it; where it cannot be promised the plain move
 * is still a single directory operation and is what every store here used
 * before this existed.
 *
 * Deliberately **not** a delete-then-rename fallback, which four stores grew
 * independently: if the second rename also fails, the delete has already taken
 * the only good copy, and the next read is the plausible emptiness above.
 *
 * The temp file is left behind on a failed write rather than cleaned up, so an
 * owner looking at the volume can see what the last attempt held.
 */
fun File.writeAtomically(text: String) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, "$name.tmp")
    java.io.FileOutputStream(tmp).use { out ->
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
        // The bytes, not just the name: a rename that lands before the data
        // does is how a power cut turns an atomic write into an empty file.
        runCatching { out.fd.sync() }
    }
    runCatching {
        java.nio.file.Files.move(
            tmp.toPath(), toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE
        )
    }.getOrElse {
        // Some filesystems cannot promise an atomic move across the same
        // directory (and Android's older ones refuse the option outright).
        // A plain replace is still one directory operation.
        java.nio.file.Files.move(
            tmp.toPath(), toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
    }
}
