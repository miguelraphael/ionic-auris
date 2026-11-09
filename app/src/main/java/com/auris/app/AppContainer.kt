package com.auris.app

import android.content.Context
import com.auris.app.data.local.AurisDatabase
import com.auris.app.data.local.ExtensionDao
import com.auris.app.data.local.LibraryDao
import com.auris.app.data.local.PlaylistDao
import com.auris.app.data.local.SyncDao
import com.auris.app.data.remote.SupabaseLibraryDataSource
import com.auris.app.data.remote.SupabaseLibraryDataSourceImpl
import com.auris.app.data.remote.kitsu.KitsuClient
import com.auris.app.data.remote.music.DefaultMusicSource
import com.auris.app.data.remote.music.InnerTubeClient
import com.auris.app.data.remote.music.InnerTubeParser
import com.auris.app.data.remote.music.ITunesAlbumArtSource
import com.auris.app.data.remote.music.MusicSource
import com.auris.app.data.remote.music.NewPipeStreamResolver
import com.auris.app.data.repository.ExtensionRepoStore
import com.auris.app.data.repository.ExtensionRepository
import com.auris.app.data.repository.AuthRepository
import com.auris.app.data.repository.ExtensionContentRepository
import com.auris.app.data.repository.KitsuIdentifier
import com.auris.app.data.repository.LibraryRepository
import com.auris.app.data.repository.PlaylistRepository
import com.auris.app.source.ExtensionLoader
import com.auris.app.source.repo.ExtensionRepositoryClient
import com.auris.app.data.sync.SyncScheduler
import com.auris.app.diagnostics.AppStateCollector
import com.auris.app.ui.player.EpisodePlayer
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * Manual dependency container.
 *
 * Hilt is deliberately not used: the graph is small, entirely singleton-scoped, and has no
 * scoping needs beyond "the application". Avoiding an annotation processor keeps builds
 * fast and makes the wiring readable in one file.
 *
 * Every field is `lazy` so launching the app never blocks on network or Supabase setup —
 * opening the library must work offline, first run or not.
 */
interface AppContainer {
    val database: AurisDatabase
    val libraryDao: LibraryDao
    val syncDao: SyncDao
    val playlistDao: PlaylistDao
    val libraryRepository: LibraryRepository
    val playlistRepository: PlaylistRepository
    /** Process-wide connectivity, so the shell can show the offline strip without polling. */
    val networkMonitor: com.auris.app.core.network.NetworkMonitor
    val extensionDao: ExtensionDao
    val extensionRepository: ExtensionRepository

    /**
     * Shared, persisted repository settings.
     *
     * Injected rather than constructed per screen because the URL and the signing key are one
     * trust decision with two consumers: Settings edits it, the install path verifies against it.
     * Two independent copies would mean a fingerprint saved in one is not the value checked in
     * the other.
     */
    val extensionRepoStore: ExtensionRepoStore
    val extensionLoader: ExtensionLoader
    val kitsuClient: KitsuClient
    val innerTubeClient: InnerTubeClient
    /**
     * The music surface the UI depends on.
     *
     * Catalogue from YouTube Music's own API; audio extracted on the device. Screens talk to this,
     * never to the provider directly, so the audio half can change without touching a ViewModel.
     */
    val musicSource: MusicSource
    val kitsuIdentifier: KitsuIdentifier
    val extensionContentRepository: ExtensionContentRepository
    val episodePlayer: EpisodePlayer
    val appStateCollector: AppStateCollector
    val extensionRepositoryClient: ExtensionRepositoryClient
    /** Where installed extension APKs live. Outside the DB so they survive a data reset. */
    val extensionsDir: java.io.File
    /** Process lifetime; owns the auth session mirror. Cancelled only on teardown. */
    val applicationScope: CoroutineScope
    /** Email/password auth, mirrored into sync_sessions for the gate and worker. */
    val authRepository: AuthRepository
    val syncScheduler: SyncScheduler
    val supabaseLibraryDataSource: SupabaseLibraryDataSource
    val supabaseClient: SupabaseClient
    val json: Json
    val appUpdateChecker: com.auris.app.data.remote.updater.AppUpdateChecker
}

class DefaultAppContainer(private val context: Context) : AppContainer {

    override val json: Json by lazy {
        Json {
            // Supabase rows and extension payloads add fields faster than we release.
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
            coerceInputValues = true
        }
    }

    override val database: AurisDatabase by lazy { AurisDatabase.build(context) }

    override val libraryDao: LibraryDao by lazy { database.libraryDao() }

    override val syncDao: SyncDao by lazy { database.syncDao() }

    override val playlistDao: PlaylistDao by lazy { database.playlistDao() }

    override val syncScheduler: SyncScheduler by lazy { SyncScheduler(context) }

    override val networkMonitor: com.auris.app.core.network.NetworkMonitor by lazy {
        com.auris.app.core.network.NetworkMonitor(context.applicationContext)
    }

    private val ioDispatcher: CoroutineDispatcher get() = Dispatchers.IO

    override val supabaseClient: SupabaseClient by lazy {
        check(BuildConfig.SUPABASE_URL.isNotBlank()) {
            "SUPABASE_URL is not set. Add SUPABASE_URL / SUPABASE_ANON_KEY to " +
                "local.properties (see local.properties.example)."
        }
        createSupabaseClient(
            // supabase-kt wants a bare host, not a URL with a scheme.
            supabaseUrl = BuildConfig.SUPABASE_URL.removePrefix("https://")
                .removePrefix("http://")
                .trimEnd('/'),
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
        ) {
            // Auth owns session persistence and access-token refresh, so the sync worker
            // never handles a token itself.
            install(Auth)
            install(Postgrest)
        }
    }

    override val supabaseLibraryDataSource: SupabaseLibraryDataSource by lazy {
        SupabaseLibraryDataSourceImpl(supabaseClient.postgrest)
    }

    override val extensionDao: ExtensionDao by lazy { database.extensionDao() }

    override val extensionRepoStore: ExtensionRepoStore by lazy {
        ExtensionRepoStore(context)
    }

    override val extensionRepository: ExtensionRepository by lazy {
        ExtensionRepository(extensionDao)
    }

    /**
     * Extensions need a host-provided OkHttp client, so the seam they read from
     * ([eu.kanade.tachiyomi.network.NetworkHelperProvider]) is wired here rather than being
     * left to each source.
     */
    override val extensionLoader: ExtensionLoader by lazy {
        eu.kanade.tachiyomi.network.NetworkHelperProvider.install(
            eu.kanade.tachiyomi.network.DefaultNetworkHelper(),
        )
        ExtensionLoader(context)
    }

    /**
     * Shared HTTP client.
     *
     * One client for the whole app: each pool holds idle TLS connections, so a client per
     * call site would hold the same sockets open several times over. Extension *sources*
     * deliberately do not use this — they get the client extensions-lib built against, via
     * [eu.kanade.tachiyomi.network.NetworkHelperProvider], because swapping a host's client
     * under an extension is exactly the incompatibility that breaks parsing.
     */
    private val httpClient: okhttp3.OkHttpClient by lazy { okhttp3.OkHttpClient.Builder().build() }

    /**
     * Kitsu metadata client.
     *
     * Shares the app's OkHttp client so Kitsu and the extension repo reuse the same connection
     * pool. Kitsu is unauthenticated here, so there is no key to inject.
     */
    override val kitsuClient: KitsuClient by lazy { KitsuClient(httpClient, json) }

    /**
     * Metadata identification.
     *
     * Depends on [libraryRepository] for writes and [kitsuClient] for lookups. Constructed
     * lazily so nothing blocks app startup on it — identification is triggered by a screen, not
     * by the library loading.
     */
    /**
     * YouTube Music client.
     *
     * Shares the app's OkHttp client for connection reuse, but nothing else: InnerTube is
     * unauthenticated and the client identity/version are sent as headers, not credentials.
     */
    override val innerTubeClient: InnerTubeClient by lazy {
        InnerTubeClient(httpClient, json, InnerTubeParser())
    }

    /**
     * HTTP client for NewPipeExtractor.
     *
     * Longer than the shared client's defaults because one extraction is several requests plus a
     * parse of YouTube's player script, and the script itself is measured in megabytes. This is
     * still per call, not per track — the extractor makes several calls per resolve, each of which
     * gets this budget.
     */
    private val newPipeHttpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * Cover-art lookups.
     *
     * A short budget on a dedicated client: artwork is cosmetic, so a slow metadata server must
     * not hold up playback. The caller also caps the lookup with `withTimeoutOrNull`.
     */
    private val artworkHttpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * The composed music source.
     */
    override val musicSource: MusicSource by lazy {
        DefaultMusicSource(
            catalogue = innerTubeClient,
            resolver = NewPipeStreamResolver(newPipeHttpClient),
            artwork = ITunesAlbumArtSource(artworkHttpClient),
        )
    }

    override val kitsuIdentifier: KitsuIdentifier by lazy {
        KitsuIdentifier(kitsuClient, libraryRepository::upsert)
    }

    /**
     * Fetches episodes/chapters from extensions for a library entry.
     *
     * Resolves entries back to their source via [extensionLoader], so it must be the same
     * loader instance the extension manager loads into — a second one would hold an empty
     * registry and every lookup would report "source not installed".
     */
    override val extensionContentRepository: ExtensionContentRepository by lazy {
        ExtensionContentRepository(
            registry = extensionLoader,
            persistUnits = libraryRepository::upsertUnits,
            builtInSources = listOf(
                com.auris.app.data.remote.manga.MangaDexSource(httpClient),
                com.auris.app.data.remote.manga.WeebCentralSource(),
                com.auris.app.data.remote.anime.AllAnimeSource(),
                com.auris.app.data.remote.manga.MangaPillSource(),
                com.auris.app.data.remote.manga.AsuraScansSource(),
                com.auris.app.data.remote.manga.AllMangaSource(),
            ),
            storedUnits = { libraryRepository.observeUnits(it).first() },
        )
    }

    /**
     * Episode player.
     *
     * Process-scoped rather than per-screen so navigating between episodes does not release and
     * rebuild the player. It holds a codec, an audio focus request and a wake lock, so its
     * lifetime is tied to the process, not to a composition.
     */
    override val episodePlayer: EpisodePlayer by lazy {
        EpisodePlayer(context.applicationContext)
    }

    /**
     * Gathers the state section of a diagnostic bundle.
     *
     * Takes [database] rather than a version integer so the reported version can never drift
     * from the one Room actually opened.
     */
    override val appStateCollector: AppStateCollector by lazy {
        AppStateCollector(
            context = context.applicationContext,
            databaseVersion = { database.openHelper.readableDatabase.version },
            extensionDao = extensionDao,
            extensionLoader = extensionLoader,
        )
    }

    override val extensionRepositoryClient: ExtensionRepositoryClient by lazy {
        ExtensionRepositoryClient(httpClient, json)
    }

    /**
     * `filesDir/extensions`, not external storage: extension APKs must stay readable by the
     * app process and must not require a runtime permission.
     */
    override val extensionsDir: java.io.File by lazy {
        java.io.File(context.filesDir, "extensions").apply { mkdirs() }
    }

    /**
     * One-time import of the legacy `music_collections` prefs runs on the first playlist
     * read (see `PlaylistRepository`), so no eager import is needed here.
     */
    override val playlistRepository: PlaylistRepository by lazy {
        PlaylistRepository(
            playlistDao = playlistDao,
            syncDao = syncDao,
            context = context.applicationContext,
            ioDispatcher = ioDispatcher,
        )
    }

    override val libraryRepository: LibraryRepository by lazy {
        LibraryRepository(
            libraryDao = libraryDao,
            syncDao = syncDao,
            syncScheduler = syncScheduler,
            ioDispatcher = ioDispatcher,
            json = json,
        )
    }

    override val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + ioDispatcher)
    }

    /**
     * Email/password auth.
     *
     * Lazy like everything else, so a missing Supabase URL fails at the login
     * screen with the container's message rather than at app start.
     */
    override val authRepository: AuthRepository by lazy {
        AuthRepository(
            auth = supabaseClient.auth,
            syncDao = syncDao,
            syncScheduler = syncScheduler,
            remote = supabaseLibraryDataSource,
            appScope = applicationScope,
            ioDispatcher = ioDispatcher,
        )
    }

    override val appUpdateChecker: com.auris.app.data.remote.updater.AppUpdateChecker by lazy {
        com.auris.app.data.remote.updater.AppUpdateChecker(
            context = context.applicationContext,
            httpClient = httpClient,
        )
    }
}
