package io.github.infinifar.rustclipsync

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.activity.ComponentActivity
import java.util.Locale

/** Add a language here, in locales_config.xml and in values-<tag>/strings.xml. */
internal enum class AppLanguage(val tag: String, val nativeName: String) {
    SYSTEM("", "System"), CHINESE("zh-CN", "中文（简体）"), ENGLISH("en", "English");
}

internal object AppLanguages {
    fun selected(context: Context): AppLanguage {
        val tag = if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        } else context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("language", "") ?: ""
        return AppLanguage.entries.firstOrNull { it.tag == tag } ?: AppLanguage.SYSTEM
    }

    fun context(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val language = selected(base)
        if (language == AppLanguage.SYSTEM) return base
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(language.tag)))
        })
    }

    fun select(activity: ComponentActivity, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
        } else {
            activity.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("language", language.tag).apply()
            activity.recreate()
        }
    }
}

internal fun Context.translated(@androidx.annotation.StringRes resource: Int, vararg arguments: Any): String =
    AppLanguages.context(this).getString(resource, *arguments)
