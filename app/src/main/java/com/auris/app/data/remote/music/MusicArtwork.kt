package com.auris.app.data.remote.music

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Request display-sized artwork only from hosts with known image-size conventions. */
internal fun nowPlayingArtworkUrl(original: String?): String? {
    val url = original?.toHttpUrlOrNull() ?: return original
    val host = url.host
    return when {
        host.endsWith(".mzstatic.com") -> url.newBuilder()
            .encodedPath(url.encodedPath.replace(Regex("/\\d+x\\d+bb\\."), "/1200x1200bb."))
            .build().toString()
        host.endsWith(".googleusercontent.com") || host.endsWith(".ggpht.com") -> {
            val path = url.encodedPath.replace(Regex("=w\\d+(?:-h\\d+)?"), "=w1200-h1200")
                .replace(Regex("=s\\d+"), "=s1200")
            url.newBuilder().encodedPath(path).build().toString()
        }
        host == "i.ytimg.com" && (url.encodedPath.startsWith("/vi/") || url.encodedPath.startsWith("/vi_webp/")) -> {
            val path = url.encodedPath.replace(Regex("/(?:hqdefault|mqdefault|sddefault|default)\\.(jpg|webp)$"), "/maxresdefault.$1")
            if (path == url.encodedPath) original else url.newBuilder().encodedPath(path).query(null).build().toString()
        }
        else -> original
    }
}
