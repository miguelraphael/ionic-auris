package eu.kanade.tachiyomi.animesource

import androidx.preference.PreferenceScreen

/**
 * An anime source that lets the user change how it behaves — a base URL, a login, a mirror
 * preference.
 *
 * ### Shape is dictated by the binary
 * Read out of a real published extension rather than assumed. The extension's source class
 * declares:
 *
 * ```
 * setupPreferenceScreen:(Landroidx/preference/PreferenceScreen;)V
 * ```
 *
 * and lists this interface in its `implements` clause. That single method is the whole contract.
 *
 * ### Why it does not extend [AnimeSource]
 * The real extension implements this on a class that *already* extends `HttpSource`, so making it
 * extend `AnimeSource` would add no capability while making every abstract member a second,
 * redundant path for it to satisfy. Standalone is the smaller surface and links identically: the
 * host tests `is ConfigurableAnimeSource` and calls [setupPreferenceScreen].
 *
 * ### Why it matters even though we do not render a preference screen yet
 * The extension calls `setupPreferenceScreen` from inside its own configuration flow in some
 * builds, so a missing interface is not merely "a feature we have not built" — it is a
 * `NoClassDefFoundError` that stops the extension loading at all, which is how this was found.
 */
interface ConfigurableAnimeSource {

    /**
     * Populates [preferenceScreen] with this source's settings.
     *
     * The extension controls what it adds; the host only supplies the screen to add to.
     */
    fun setupPreferenceScreen(preferenceScreen: PreferenceScreen)
}
