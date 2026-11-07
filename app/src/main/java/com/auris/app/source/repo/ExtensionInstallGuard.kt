package com.auris.app.source.repo

import java.util.concurrent.ConcurrentHashMap

/** One writer per APK/.part pair, including while awaiting publisher consent. */
internal class ExtensionInstallGuard {
    private val active = ConcurrentHashMap.newKeySet<String>()

    fun acquire(packageName: String): Boolean = active.add(packageName)
    fun release(packageName: String) { active.remove(packageName) }
}
