package com.auris.app.data.repository

import kotlinx.coroutines.sync.Mutex

/** Serializes account transitions with local writes and sync passes. */
internal object AccountAccess {
    val mutex = Mutex()
}
