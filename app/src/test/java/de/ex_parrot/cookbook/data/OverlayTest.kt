package de.ex_parrot.cookbook.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayTest {
    private fun recipe(name: String, category: String = "") = JsonObject(
        mapOf("name" to JsonPrimitive(name), "recipeCategory" to JsonPrimitive(category), "keywords" to JsonPrimitive("")),
    )

    private fun change(id: Long, type: PendingChange.Type, recipeId: String, name: String, category: String = "", from: String? = null) =
        PendingChange(
            id, type, recipeId, name,
            recipe = if (type == PendingChange.Type.DELETE) null else recipe(name, category),
            fromCategory = from,
        )

    private val base = listOf(
        RecipeStub("1", "Brot", "", "a"),
        RecipeStub("2", "Apfelkuchen", "", "b"),
    )

    @Test
    fun noChanges_sortsByName() {
        assertEquals(listOf("Apfelkuchen", "Brot"), overlayStubs(base, null, emptyList()).map { it.name })
    }

    @Test
    fun deletedRecipesDisappear() {
        val result = overlayStubs(base, null, listOf(change(1, PendingChange.Type.DELETE, "1", "Brot")))
        assertEquals(listOf("2"), result.map { it.id })
    }

    @Test
    fun newRecipesAppearInAllRecipesAndTheirCategory() {
        val created = change(1, PendingChange.Type.CREATE, "local-1", "Zopf", "Backen")
        assertEquals(listOf("2", "1", "local-1"), overlayStubs(base, null, listOf(created)).map { it.id })
        assertEquals(listOf("local-1"), overlayStubs(emptyList(), "Backen", listOf(created)).map { it.id })
        assertTrue(overlayStubs(emptyList(), "Kochen", listOf(created)).isEmpty())
    }

    @Test
    fun renamedRecipeShowsNewName() {
        val updated = change(1, PendingChange.Type.UPDATE, "1", "Bauernbrot")
        assertEquals(listOf("Apfelkuchen", "Bauernbrot"), overlayStubs(base, null, listOf(updated)).map { it.name })
    }

    @Test
    fun movedRecipeLeavesOldCategory() {
        val moved = change(1, PendingChange.Type.UPDATE, "1", "Brot", category = "Backen", from = "Kochen")
        assertTrue(overlayStubs(listOf(base[0]), "Kochen", listOf(moved)).isEmpty())
        assertEquals(listOf("1"), overlayStubs(emptyList(), "Backen", listOf(moved)).map { it.id })
    }

    @Test
    fun categoryCountsFollowChanges() {
        val categories = listOf(Category("Backen", 2), Category("Kochen", 1))
        val changes = listOf(
            change(1, PendingChange.Type.CREATE, "local-1", "Zopf", "Backen"),
            change(2, PendingChange.Type.UPDATE, "5", "Suppe", category = "Vorspeisen", from = "Kochen"),
            change(3, PendingChange.Type.DELETE, "6", "Torte", from = "Backen"),
        )
        val result = overlayCategories(categories, changes).associate { it.name to it.recipeCount }
        assertEquals(mapOf("Backen" to 2, "Vorspeisen" to 1), result)
    }

    @Test
    fun sameContent_comparesEditedFields() {
        val mine = JsonObject(recipe("Brot").toMutableMap().apply { put("recipeIngredient", JsonArray(listOf(JsonPrimitive("Mehl")))) })
        val server = JsonObject(mine + ("dateModified" to JsonPrimitive("2026-01-01")))
        assertTrue(sameContent(mine, server))
        assertFalse(sameContent(mine, JsonObject(server + ("name" to JsonPrimitive("Anderes Brot")))))
        assertFalse(sameContent(null, server))
    }
}
