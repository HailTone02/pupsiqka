package com.pupsikcall.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory

class AppLocaleSupportTest {
    private val resourceRoot = Paths.get("src/main/res")
    private val supportedTags = listOf("en", "ru", "sq", "de", "tr", "ar", "it", "fr")

    @Test
    fun supportedLocalesAndNativeNamesMatchAndroidLocaleConfig() {
        val arrays = parse(resourceRoot.resolve("values/arrays.xml"))
        val codes = readArray(arrays, "supported_language_codes")
        val names = readArray(arrays, "supported_language_names")

        assertEquals(supportedTags, codes)
        assertEquals(
            listOf("English", "Русский", "Shqip", "Deutsch", "Türkçe", "العربية", "Italiano", "Français"),
            names,
        )

        val localeConfig = parse(resourceRoot.resolve("xml/locales_config.xml"))
        val configuredTags = localeConfig.getElementsByTagName("locale").let { nodes ->
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(ANDROID_NS, "name") }
        }
        assertEquals(supportedTags, configuredTags)
    }

    @Test
    fun everyLocaleUsesValidEnglishFallbackStringKeys() {
        val fallback = readStrings(resourceRoot.resolve("values/strings.xml"))
        assertTrue(fallback.isNotEmpty())

        supportedTags.drop(1).forEach { languageTag ->
            val translated = readStrings(resourceRoot.resolve("values-$languageTag/strings.xml"))
            assertTrue("Unknown strings for $languageTag", fallback.keys.containsAll(translated.keys))
            translated.forEach { (key, value) ->
                assertFalse("Empty $key translation for $languageTag", value.isBlank())
            }
            assertEquals("HailTone", translated.getValue("app_name"))
        }
    }

    @Test
    fun translatedUiCopyDoesNotFallBackToEnglishPlaceholders() {
        val fallback = readStrings(resourceRoot.resolve("values/strings.xml"))
        val unchangedByDesign = setOf("app_name", "peer_a", "peer_b", "demo_contact_name", "password", "contacts", "online", "offline")

        supportedTags.drop(1).forEach { languageTag ->
            val translated = readStrings(resourceRoot.resolve("values-$languageTag/strings.xml"))
            fallback.forEach { (key, english) ->
                val explicitTranslation = translated[key]
                if (key !in unchangedByDesign && explicitTranslation != null) {
                    assertTrue("English placeholder for $key in $languageTag", explicitTranslation != english)
                }
            }
        }
    }

    @Test
    fun languageSelectionPrefersAppLocaleThenSystemAndFallsBackToEnglish() {
        assertEquals(1, selectedLanguageIndex(supportedTags, "ru-RU,en", "de-DE"))
        assertEquals(7, selectedLanguageIndex(supportedTags, "", "fr-CA"))
        assertEquals(0, selectedLanguageIndex(supportedTags, "", "es-ES"))
    }

    @Test
    fun appLocaleIsPersistedAndArabicRtlIsEnabled() {
        val manifest = parse(Paths.get("src/main/AndroidManifest.xml"))
        val application = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("true", application.getAttributeNS(ANDROID_NS, "supportsRtl"))
        assertEquals("@xml/locales_config", application.getAttributeNS(ANDROID_NS, "localeConfig"))

        val metadata = application.getElementsByTagName("meta-data")
        val localeStorage = (0 until metadata.length)
            .map { metadata.item(it) as Element }
            .firstOrNull { it.getAttributeNS(ANDROID_NS, "name") == "autoStoreLocales" }
        assertNotNull(localeStorage)
        assertEquals("true", localeStorage!!.getAttributeNS(ANDROID_NS, "value"))

        val arabicResources = readStrings(resourceRoot.resolve("values-ar/strings.xml"))
        assertEquals("جهات الاتصال", arabicResources["contacts"])
    }

    private fun readStrings(file: java.nio.file.Path): Map<String, String> {
        val nodes = parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent
        }
    }

    private fun readArray(document: org.w3c.dom.Document, name: String): List<String> {
        val arrays = document.getElementsByTagName("string-array")
        val array = (0 until arrays.length)
            .map { arrays.item(it) as Element }
            .first { it.getAttribute("name") == name }
        val items = array.getElementsByTagName("item")
        return (0 until items.length).map { items.item(it).textContent }
    }

    private fun parse(file: java.nio.file.Path) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(file.toFile())

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}