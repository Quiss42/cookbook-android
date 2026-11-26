package de.ex_parrot.cookbook.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration

class ModelsTest {
    @Test
    fun localizeDecimals_usesGivenSeparator() {
        assertEquals("12,5 g Zucker", localizeDecimals("12.5 g Zucker", ','))
        assertEquals("0,25 l", localizeDecimals("0,25 l", ','))
        assertEquals("12.5 g", localizeDecimals("12,5 g", '.'))
    }

    @Test
    fun localizeDecimals_keepsThousandsSeparators() {
        assertEquals("1.000 g Mehl", localizeDecimals("1.000 g Mehl", ','))
        assertEquals("1.250.000", localizeDecimals("1.250.000", ','))
    }

    @Test
    fun localizeDecimals_leavesWholeNumbersAndTextAlone() {
        assertEquals("2 Eier, 3 Äpfel", localizeDecimals("2 Eier, 3 Äpfel", '.'))
    }

    @Test
    fun parseIsoDuration_readsServerValues() {
        assertEquals(Duration.ofMinutes(90), parseIsoDuration("PT1H30M"))
        assertEquals(Duration.ofMinutes(45), parseIsoDuration(" PT0H45M "))
        assertNull(parseIsoDuration(""))
        assertNull(parseIsoDuration("PT0H0M"))
        assertNull(parseIsoDuration("soon"))
    }

    @Test
    fun normalizeServerUrl_addsSchemeAndStripsSuffixes() {
        assertEquals("https://cloud.example.com", normalizeServerUrl(" cloud.example.com/ "))
        assertEquals("http://192.168.1.2/nextcloud", normalizeServerUrl("http://192.168.1.2/nextcloud/index.php"))
    }

    @Test
    fun isNewer_comparesNumberByNumber() {
        assertEquals(true, UpdateChecker.isNewer("0.10.0", "0.9.9"))
        assertEquals(true, UpdateChecker.isNewer("0.7.0", "0.6.4"))
        assertEquals(false, UpdateChecker.isNewer("0.6.4", "0.6.4"))
        assertEquals(false, UpdateChecker.isNewer("0.6.3", "0.6.10"))
        assertEquals(true, UpdateChecker.isNewer("1.0", "0.99.99"))
    }
}
