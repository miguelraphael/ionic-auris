package com.auris.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The public Invidious instances Auris will stream audio through, persisted.
 *
 * ### Why this is a setting and not a constant
 * Audio for the whole music pillar comes from these hosts, and as of 2026-10 **exactly one**
 * public instance has its API open — the rest answer `403 Endpoint disabled`, `401`, or
 * `forbidden`, and the trend is instances closing their APIs rather than opening them. A
 * hardcoded host therefore turns "the volunteer running it closed the API" into "music is
 * broken, with no way to fix it short of a new release".
 *
 * Making the list editable moves that event from a code change to a settings change. It does not
 * make the dependency good; it makes it survivable.
 *
 * ### Why SharedPreferences rather than DataStore
 * The same reasoning as [ExtensionRepoStore]: a handful of strings, read once at startup and
 * written on an explicit user action. DataStore would add a dependency and a coroutine to save
 * nothing.
 */
class InvidiousInstanceStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _instances = MutableStateFlow(read())

    /** The current list, in priority order. Emits on change so the resolver stays in step. */
    val instances: StateFlow<List<String>> = _instances.asStateFlow()

    /** The list as of now, for callers that are not observing. */
    fun current(): List<String> = _instances.value

    /**
     * Replaces the list.
     *
     * Entries are normalised and de-duplicated, and anything that is not an `http(s)` URL is
     * dropped rather than stored — a malformed entry would otherwise fail at playback time with
     * a confusing transport error instead of never being saved.
     *
     * An empty result is honoured rather than replaced with the defaults: a user clearing every
     * instance is making a choice, and resurrecting the built-ins would silently undo it.
     */
    fun save(instances: List<String>) {
        val clean = instances.mapNotNull(::normalise).distinct()
        prefs.edit().putString(KEY_INSTANCES, clean.joinToString(SEPARATOR)).apply()
        _instances.value = clean
    }

    /** Appends [url] if it is valid and not already present. */
    fun add(url: String) {
        val clean = normalise(url) ?: return
        if (_instances.value.contains(clean)) return
        save(_instances.value + clean)
    }

    /** Removes [url], matching on the normalised form. */
    fun remove(url: String) {
        val clean = normalise(url) ?: return
        save(_instances.value.filterNot { it == clean })
    }

    /** Restores the built-in list. Used by "Reset to defaults". */
    fun reset() {
        prefs.edit().remove(KEY_INSTANCES).apply()
        _instances.value = DEFAULT_INSTANCES
    }

    private fun read(): List<String> {
        // Absent key means "never configured", which is different from "configured as empty".
        val stored = prefs.getString(KEY_INSTANCES, null) ?: return DEFAULT_INSTANCES
        if (stored.isBlank()) return emptyList()
        return stored.split(SEPARATOR).mapNotNull(::normalise)
    }

    companion object {
        /**
         * The instances shipped with the app.
         *
         * A single working host leads the list; a second is included as a placeholder the user
         * can replace. Deliberately short: this is an ordered failover list, not a directory, and
         * every dead entry is a round trip spent before playback starts.
         */
        val DEFAULT_INSTANCES = listOf(
            "https://invidious.f5.si",
        )

        private const val PREFS_NAME = "auris_invidious_instances"
        private const val KEY_INSTANCES = "instances"
        private const val SEPARATOR = "\n"

        /**
         * Normalises a user-typed instance URL, or returns null if it cannot be one.
         *
         * Accepts `invidious.f5.si`, `https://invidious.f5.si` and `https://invidious.f5.si/` as
         * the same value, because all three are what a user will paste. A blank path or a
         * trailing slash is stripped so the resolver can append `/api/v1/...` without producing
         * a double slash, which some instances answer with a 404.
         */
        fun normalise(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isBlank()) return null

            val withScheme = when {
                trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed
                "://" in trimmed -> return null // some other scheme; not ours to guess at
                else -> "https://$trimmed"
            }

            val host = withScheme.substringAfter("://")
            // A host with no dot cannot be a public instance, and accepting it would let a typo
            // become a stored entry that fails only at playback.
            if ('.' !in host.substringBefore('/')) return null

            return withScheme
        }
    }
}
