package io.candy.tick.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList

/** Apply only the locale override so density, font scale and other configuration still come from the caller. */
internal fun Context.forAppLanguage(language: String): Context {
    val locales = if (language.isBlank()) Resources.getSystem().configuration.locales else LocaleList.forLanguageTags(language)
    if (resources.configuration.locales == locales) return this
    return createConfigurationContext(Configuration().apply { setLocales(locales) })
}
