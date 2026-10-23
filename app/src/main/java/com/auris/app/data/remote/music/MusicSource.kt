package com.auris.app.data.remote.music

/**
 * One named row on the music home screen.
 *
 * The title is what the row is called ("Top Picks for You"); the seed is the search that fills
 * it. Keeping both means the home screen is a list of rows, not a list of queries the UI has to
 * dress up — and a provider can rename a row without the UI changing.
 */
data class MusicShelfSeed(
    val title: String,
    val seed: String,
)

/**
 * The music surface the UI depends on.
 *
 * ### Why this seam exists
 * Two providers with very different lifecycles sit behind it:
 *
 * - **Catalogue** — search and browse — comes from YouTube Music's own InnerTube API. It works
 *   unauthenticated, it is fast, and it is the only source that returns **album** rows:
 *   Invidious search answers with `video` and `channel` results only (verified 2026-10), so
 *   routing the catalogue through it would silently drop albums from every screen.
 * - **Audio** comes from a public Invidious instance, because YouTube refuses to serve a stream
 *   to an unauthenticated first-party client at all (`ARCHITECTURE.md` §4, *Verified 2026-10*).
 *
 * Keeping them apart means the fragile half — one volunteer's server — carries only the half
 * that actually needs it. When it is down the user can still browse, and can still fix it from
 * Settings, instead of staring at an app that has no screen at all.
 *
 * This interface exists so the *UI* does not care which providers are behind it. That is what
 * makes the resolver swappable and testable with a fake, rather than rewiring a ViewModel.
 */
interface MusicSource {
    /**
     * Seeds for the discovery landing page.
     *
     * On the seam rather than read from `InnerTubeClient` by the UI, because which seeds make
     * sense is a property of the catalogue provider.
     */
    val discoverySeeds: List<String>

    /**
     * Named discovery shelves for the music home screen, in display order.
     *
     * A default rather than a required member so a provider only has to supply [discoverySeeds]:
     * untitled seeds still produce a shelf, just with a capitalised query as the heading.
     */
    val discoveryShelves: List<MusicShelfSeed>
        get() = discoverySeeds.map { seed ->
            MusicShelfSeed(seed.replaceFirstChar(Char::uppercase), seed)
        }

    /** Searches the catalogue. */
    suspend fun search(query: String, limit: Int): Result<List<MusicCollectionItem>>

    /** Fetches an album, playlist or other browsable collection, with its track list. */
    suspend fun collection(browseId: String, limit: Int): Result<List<MusicCollectionItem>>

    /**
     * Resolves playable audio for [videoId].
     *
     * A `Result` failure means no provider answered; a [ResolvedAudio] with a null stream and a
     * set [ResolvedAudio.refusal] means one answered and declined.
     */
    suspend fun resolveAudioStream(videoId: String): Result<ResolvedAudio>

    /**
     * Best-effort official cover art for a track.
     *
     * A default of null keeps providers that cannot supply artwork working: the UI falls back to
     * the video thumbnail. Callers must never let a slow or failed lookup block playback.
     */
    suspend fun findArtwork(title: String, artist: String?): String? = null
}

/**
 * Where a track's audio comes from.
 *
 * Narrower than [MusicSource] on purpose: this is the half that varies between providers — a
 * public instance, an authenticated account, a local file — while the catalogue half does not.
 */
interface MusicStreamResolver {
    suspend fun resolveAudioStream(videoId: String): Result<ResolvedAudio>
}

/**
 * The default composition: catalogue from YouTube Music, audio from [resolver].
 *
 * Deliberately a plain delegation with no policy of its own — failover, caching and instance
 * health all belong to the resolver, where they can be tested without a catalogue behind them.
 */
class DefaultMusicSource(
    private val catalogue: InnerTubeClient,
    private val resolver: MusicStreamResolver,
    /** Null in previews and tests that do not need cover lookups. */
    private val artwork: AlbumArtSource? = null,
) : MusicSource {

    override val discoverySeeds: List<String> get() = InnerTubeClient.DISCOVERY_SEEDS

    override val discoveryShelves: List<MusicShelfSeed> get() = InnerTubeClient.DISCOVERY_SHELVES

    override suspend fun search(query: String, limit: Int): Result<List<MusicCollectionItem>> =
        catalogue.search(query, InnerTubeSearchParams.ALL, limit)

    override suspend fun collection(browseId: String, limit: Int): Result<List<MusicCollectionItem>> =
        catalogue.collection(browseId, limit)

    override suspend fun resolveAudioStream(videoId: String): Result<ResolvedAudio> =
        resolver.resolveAudioStream(videoId)

    override suspend fun findArtwork(title: String, artist: String?): String? =
        artwork?.artworkFor(title, artist)
}
