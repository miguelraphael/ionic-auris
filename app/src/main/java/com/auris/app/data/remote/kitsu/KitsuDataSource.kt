package com.auris.app.data.remote.kitsu

/**
 * The Kitsu operations the rest of the app depends on.
 *
 * ### Why this exists
 * [KitsuClient] is a final class with an OkHttp dependency, so it cannot be substituted in a
 * unit test without a mocking framework or an open class. The identifier's behaviour — caching,
 * failure tolerance, which fields it may write — is worth testing directly, and that needs a
 * seam.
 *
 * A narrow interface is the honest shape of the dependency anyway: the identifier uses three
 * calls, and depending on those three rather than the whole client means a future Kitsu
 * endpoint cannot accidentally become part of this contract.
 */
interface KitsuDataSource {

    suspend fun searchAnime(query: String, limit: Int = 10, offset: Int = 0): Result<KitsuPage<KitsuAnimeAttributes>>

    suspend fun searchManga(query: String, limit: Int = 10, offset: Int = 0): Result<KitsuPage<KitsuMangaAttributes>>

    suspend fun anime(id: Long): Result<KitsuAnimeAttributes>

    suspend fun manga(id: Long): Result<KitsuMangaAttributes>
}