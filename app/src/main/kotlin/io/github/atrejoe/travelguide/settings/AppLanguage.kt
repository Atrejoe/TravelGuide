package io.github.atrejoe.travelguide.settings

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.view.inputmethod.InputMethodManager
import io.github.atrejoe.travelguide.core.lang.LanguageChoice
import java.util.Locale

/**
 * The language of the app's own screens (issue #92), and the phone's language that it and the
 * stories follow unless chosen otherwise: the phone's languages in order, then the languages of
 * its keyboards, and English when the app speaks none of them.
 *
 * Kept in plain shared preferences rather than the settings store, because it is needed
 * synchronously, before an activity or service has its resources.
 */
object AppLanguage {
    private const val PREFS = "ui"
    private const val KEY = "language"

    /** The language chosen for the app's screens, or "" to follow the phone. */
    fun chosen(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    /** Chooses [code] for the app's screens, or "" to follow the phone; activities must be recreated. */
    fun choose(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code).apply()
    }

    /**
     * The phone's languages, most preferred first: a language chosen for this app in the
     * system settings (Android 13+), the phone's own, then those of its keyboards.
     */
    fun phoneLocales(context: Context): List<Locale> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales?.let { list ->
                for (i in 0 until list.size()) add(list[i])
            }
        }
        val system = Resources.getSystem().configuration.locales
        for (i in 0 until system.size()) add(system[i])
        addAll(keyboardLocales(context))
    }

    private fun keyboardLocales(context: Context): List<Locale> = try {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return emptyList()
        imm.enabledInputMethodList
            .flatMap { imm.getEnabledInputMethodSubtypeList(it, true) }
            .mapNotNull { subtype ->
                @Suppress("DEPRECATION")
                val tag = subtype.languageTag.ifEmpty { subtype.locale.replace('_', '-') }
                tag.takeIf { it.isNotEmpty() }?.let(Locale::forLanguageTag)
            }
    } catch (e: RuntimeException) {
        // A keyboard that misbehaves costs us only the fallback.
        emptyList()
    }

    /** The language the phone asks for, as far as the app speaks it, e.g. "nl". */
    fun phoneLanguage(context: Context): String = LanguageChoice.pick(phoneLocales(context).map { it.toLanguageTag() })

    /** The locale for the app's screens: the chosen language, else the phone's, with its region. */
    fun uiLocale(context: Context): Locale {
        val chosen = chosen(context)
        if (chosen.isNotEmpty()) return Locale.forLanguageTag(chosen)
        val code = phoneLanguage(context)
        return phoneLocales(context).firstOrNull { it.language == code } ?: Locale.forLanguageTag(code)
    }

    /** [base] with its resources in the app's language; for an activity's or service's attachBaseContext. */
    fun wrap(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocale(uiLocale(base))
        return base.createConfigurationContext(config)
    }
}
