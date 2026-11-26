package de.ex_parrot.cookbook.data

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The WebDAV answer is parsed with android.util.Xml, hence Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NextcloudFilesTest {
    private val files = NextcloudFiles(Account("https://cloud.example.com", "ingo", "secret"), OkHttpClient())

    @Test
    fun parseMultistatus_readsFoldersAndImages() {
        val xml = """<?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <d:response>
                <d:href>/remote.php/dav/files/ingo/Rezepte/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><oc:fileid>10</oc:fileid></d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote.php/dav/files/ingo/Rezepte/Bilder%20Ordner/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><oc:fileid>11</oc:fileid></d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote.php/dav/files/ingo/Rezepte/Kuchen+1.jpg</d:href>
                <d:propstat><d:prop><d:resourcetype/><d:getcontenttype>image/jpeg</d:getcontenttype><oc:fileid>12</oc:fileid></d:prop></d:propstat>
              </d:response>
            </d:multistatus>"""
        val entries = files.parseMultistatus(xml, "/remote.php/dav/files/ingo")
        assertEquals(listOf("/Rezepte", "/Rezepte/Bilder Ordner", "/Rezepte/Kuchen+1.jpg"), entries.map { it.path })
        assertEquals(listOf(true, true, false), entries.map { it.isFolder })
        assertEquals("Kuchen+1.jpg", entries[2].name)
        assertEquals("image/jpeg", entries[2].contentType)
        assertEquals("12", entries[2].fileId)
    }
}
