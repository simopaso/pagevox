package fi.paso.pagevox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manual is picked by language at load time, so a wrong answer here means a
 * user lands on a manual they can't read — or on a file that isn't in the APK
 * at all, which renders as an error page.
 */
class ManualUrlTest {

    @Test
    fun translatedLanguagesGetTheirOwnManual() {
        assertEquals("file:///android_asset/manual-fi.html", manualUrlForLanguage("fi"))
        assertEquals("file:///android_asset/manual-sv.html", manualUrlForLanguage("sv"))
        assertEquals("file:///android_asset/manual-de.html", manualUrlForLanguage("de"))
        assertEquals("file:///android_asset/manual-fr.html", manualUrlForLanguage("fr"))
    }

    @Test
    fun everythingElseFallsBackToEnglish() {
        assertEquals("file:///android_asset/manual.html", manualUrlForLanguage("en"))
        assertEquals("file:///android_asset/manual.html", manualUrlForLanguage("es"))
        assertEquals("file:///android_asset/manual.html", manualUrlForLanguage(""))
        assertEquals("file:///android_asset/manual.html", manualUrlForLanguage(null))
    }

    @Test
    fun languageTagCaseDoesNotMatter() {
        // Locale.getDefault().language is lowercase in practice, but a stray
        // uppercase tag must not silently drop the user to English.
        assertEquals("file:///android_asset/manual-de.html", manualUrlForLanguage("DE"))
    }

    @Test
    fun everyManualIsRecognisedAsOne() {
        // Drives the "re-resolve a stored home page after a language change"
        // path in SettingsRepository — miss a variant and that user keeps
        // landing on the old language's manual forever.
        listOf("en", "fi", "sv", "de", "fr").forEach {
            assertTrue(it, isManualUrl(manualUrlForLanguage(it)))
        }
        assertFalse(isManualUrl("https://example.com/manual.html"))
    }
}
