package com.auris.app.source.repo

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Repository transport must be encrypted even on paths not currently wired into the UI. */
fun isSecureRepositoryUrl(value: String): Boolean {
    val url = value.toHttpUrlOrNull() ?: return false
    return url.isHttps && url.username.isEmpty() && url.password.isEmpty()
}
