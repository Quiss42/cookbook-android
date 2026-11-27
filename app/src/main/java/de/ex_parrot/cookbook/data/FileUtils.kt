package de.ex_parrot.cookbook.data

import java.io.File
import java.io.FileOutputStream

/**
 * Writes [text] via a temporary file, forces it to the storage (fsync) and
 * renames it, so the file is never left empty or half-written, also after a
 * crash or power loss. The folder must exist: writes into a deleted folder
 * (e.g. after logout) fail instead of bringing it back.
 */
internal fun File.writeTextAtomically(text: String) {
    val temp = File("$path.tmp")
    FileOutputStream(temp).use { out ->
        out.write(text.toByteArray())
        out.flush()
        out.fd.sync()
    }
    if (!temp.renameTo(this)) {
        delete()
        check(temp.renameTo(this)) { "Could not replace $this" }
    }
}

/** Moves a file that could not be read aside (name.bad-<time>) instead of overwriting it later. */
internal fun File.keepAsBad() {
    if (exists()) renameTo(File("$path.bad-${System.currentTimeMillis()}"))
}
