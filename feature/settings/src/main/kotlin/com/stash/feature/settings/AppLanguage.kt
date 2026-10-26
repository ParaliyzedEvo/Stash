package com.stash.feature.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale
import android.content.Context
import org.xmlpull.v1.XmlPullParser

object AppLanguage {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /** Languages the build ships, read from AGP's generated locale config. */
    fun supported(context: Context): List<String> {
        val fallback = listOf("en")
        return try {
            val id = context.resources.getIdentifier(
                "_generated_res_locale_config", "xml", context.packageName,
            )
            if (id == 0) return fallback
            val tags = mutableListOf<String>()
            context.resources.getXml(id).use { parser ->
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                        parser.getAttributeValue(ANDROID_NS, "name")?.let(tags::add)
                    }
                }
            }
            tags.ifEmpty { fallback }.sortedBy { nativeName(it) }
        } catch (e: Exception) {
            fallback
        }
    }

    /** null = follow system. */
    fun current(context: Context): String? {
        val first = AppCompatDelegate.getApplicationLocales()[0] ?: return null
        val all = supported(context)
        return all.firstOrNull { it.equals(first.toLanguageTag(), ignoreCase = true) }
            ?: all.firstOrNull { Locale.forLanguageTag(it).language == first.language }
    }

    fun set(tag: String?) {
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag),
        )
    }

    /** Language name in its own script: Deutsch, 한국어, العربية. */
    fun nativeName(tag: String): String {
        val l = Locale.forLanguageTag(tag)
        return l.getDisplayLanguage(l).replaceFirstChar { it.titlecase(l) }
    }
}