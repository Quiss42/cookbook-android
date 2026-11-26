package de.ex_parrot.cookbook.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageToolsTest {
    @Test
    fun findPageImage_readsOpenGraphImage() {
        val html = """<html><head><meta property="og:image" content="https://img.example.com/a.jpg?x=1&amp;y=2"></head></html>"""
        assertEquals("https://img.example.com/a.jpg?x=1&y=2", findPageImage(html, "https://example.com/recipe"))
    }

    @Test
    fun findPageImage_acceptsReversedAttributesAndRelativeAddresses() {
        val html = """<meta content="/pics/b.png" property="og:image" />"""
        assertEquals("https://example.com/pics/b.png", findPageImage(html, "https://example.com/recipe/1"))
    }

    @Test
    fun findPageImage_fallsBackToTwitterImage() {
        val html = """<meta name="twitter:image" content="https://example.com/t.jpg">"""
        assertEquals("https://example.com/t.jpg", findPageImage(html, "https://example.com/"))
    }

    @Test
    fun findPageImage_returnsNullWithoutImage() {
        assertNull(findPageImage("<html><body>Hallo</body></html>", "https://example.com/"))
    }

    @Test
    fun readLimited_readsSmallData() {
        val data = ByteArray(1000) { it.toByte() }
        assertArrayEquals(data, readLimited(data.inputStream(), 1000))
    }

    @Test(expected = ImageTooLargeException::class)
    fun readLimited_stopsAtLimit() {
        readLimited(ByteArray(1001).inputStream(), 1000)
    }

    @Test
    fun contentErrors_doNotCountAsUnreachable() {
        assertEquals(false, isUnreachable(NoImageException()))
        assertEquals(false, isUnreachable(ImageTooLargeException()))
        assertEquals(false, isUnreachable(HttpException(404, "missing")))
        assertEquals(true, isUnreachable(HttpException(503, "down")))
        assertEquals(true, isUnreachable(java.net.UnknownHostException()))
        assertEquals(true, isUnreachable(InvalidResponseException("captive portal")))
    }
}
