package de.ex_parrot.cookbook.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PendingStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = PendingStore(File(folder.root, "pending.json"), File(folder.root, "images"))

    @Test
    fun removeIfUnchanged_removesUntouchedEntry() {
        val store = store()
        val change = PendingChange(1, PendingChange.Type.DELETE, "7", "Brot")
        store.update { it + change }
        assertTrue(store.removeIfUnchanged(change))
        assertTrue(store.changes.value.isEmpty())
    }

    @Test
    fun removeIfUnchanged_keepsEntryEditedMeanwhile() {
        val store = store()
        val change = PendingChange(1, PendingChange.Type.CREATE, "local-1", "Brot")
        store.update { it + change }
        // Edited again while the upload was running.
        store.update { list -> list.map { it.copy(name = "Bauernbrot") } }
        assertFalse(store.removeIfUnchanged(change))
        assertEquals("Bauernbrot", store.changes.value.single().name)
    }

    @Test
    fun changesSurviveReloading() {
        store().update { it + PendingChange(1, PendingChange.Type.DELETE, "7", "Brot") }
        assertEquals("7", store().changes.value.single().recipeId)
        // No temporary file is left behind.
        assertFalse(File(folder.root, "pending.json.tmp").exists())
    }

    @Test
    fun writeTextAtomically_replacesExistingFile() {
        val file = File(folder.root, "a/b.txt")
        file.writeTextAtomically("eins")
        file.writeTextAtomically("zwei")
        assertEquals("zwei", file.readText())
    }
}
