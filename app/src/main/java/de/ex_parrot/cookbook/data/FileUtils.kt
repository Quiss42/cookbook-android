package de.ex_parrot.cookbook.data

import java.io.File

/**
 * Writes [text] via a temporary file and a rename, so the file is never left
 * half-written if the app is killed in the middle.
 */
internal fun File.writeTextAtomically(text: String) {
    parentFile?.mkdirs()
    val temp = File("$path.tmp")
    temp.writeText(text)
    if (!temp.renameTo(this)) {
        delete()
        check(temp.renameTo(this)) { "Could not replace $this" }
    }
}
