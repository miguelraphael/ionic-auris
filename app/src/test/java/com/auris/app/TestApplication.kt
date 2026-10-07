package com.auris.app

import android.app.Application

/**
 * Minimal Application for Robolectric unit tests.
 *
 * The production [AurisApplication] schedules a WorkManager periodic sync in `onCreate`.
 * WorkManager is deliberately un-initialized under Robolectric (its `InitializationProvider`
 * is removed in the manifest), so booting the real Application would fail every test for a
 * reason unrelated to what is under test. This stub lets the DAO tests exercise a real
 * SQLite database without dragging in app startup.
 */
class TestApplication : Application()