package com.auris.app.music

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import com.auris.app.AurisApplication

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Background audio host.
 *
 * ### Why a Service at all
 * An `ExoPlayer` held by a ViewModel dies with the Activity, so music stops the moment the user
 * locks the screen or switches apps. A [MediaSessionService] gives the player a process-scoped
 * home, which is what makes background playback possible at all.
 *
 * ### Audio focus
 * [AudioAttributes] with `USAGE_MEDIA` and `CONTENT_TYPE_MUSIC` is what lets ExoPlayer manage
 * focus. Declaring the attributes is mandatory — without them ExoPlayer does not request focus
 * and behaviour with calls, navigation prompts and other apps is undefined.
 *
 * ### Notification
 * The notification itself comes from [DefaultMediaNotificationProvider], which also starts and
 * stops the foreground service and builds the transport pending-intents. Only the channel is
 * configured here — see [PlayerNotification] for why.
 *
 * ### Lifecycle
 * The player is created in [onCreate] and released in [onDestroy], *not* on unbind: unbind
 * happens routinely when a screen rotates, and stopping there would interrupt playback.
 */
@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sleepTimer = SleepTimer(scope, SystemClock::elapsedRealtime,
        onDeadlineChanged = { sleepDeadline = it; publishModes() },
        onExpire = { player?.pause() })
    private var smartJob: Job? = null
    private var suggestionSeed: String? = null
    private var sleepDeadline = 0L
    private var smartShuffle = false
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var equalizer: android.media.audiofx.Equalizer? = null
    private var hadSession = false

    override fun onCreate() {
        super.onCreate()

        PlayerNotification.ensureChannel(this)

        val attributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
            // Pause when headphones are unplugged. Without this, audio keeps playing out of the
            // speaker, which is the single most complained-about music-app bug.
            .setHandleAudioBecomingNoisy(true)
            // Keeps the CPU awake across a long track. NETWORK-scoped rather than FULL so the
            // device can still sleep between tracks.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val mediaSession = MediaSession.Builder(this, exo)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    if (!isAllowedController(controller)) return MediaSession.ConnectionResult.reject()
                    val base = super.onConnect(session, controller)
                    val commands = base.availableSessionCommands.buildUpon()
                    if (controller.uid == android.os.Process.myUid()) {
                        commands.add(SessionCommand(SET_SLEEP_TIMER, Bundle.EMPTY))
                        commands.add(SessionCommand(SET_SMART_SHUFFLE, Bundle.EMPTY))
                        commands.add(SessionCommand(SET_EQUALIZER, Bundle.EMPTY))
                    }
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands.build())
                        .setAvailablePlayerCommands(base.availablePlayerCommands)
                        .build()
                }

                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                    command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (controller.uid != android.os.Process.myUid()) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    }
                    when (command.customAction) {
                        SET_SLEEP_TIMER -> setSleepTimer(args.getLong(SLEEP_DURATION))
                        SET_SMART_SHUFFLE -> setSmartShuffle(args.getBoolean(SMART_ENABLED))
                        SET_EQUALIZER -> {
                            val eq = equalizer ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                            val band = args.getInt("band")
                            if (band in 0 until eq.numberOfBands) {
                                eq.setBandLevel(band.toShort(), args.getInt("level").coerceIn(
                                    eq.bandLevelRange[0].toInt(), eq.bandLevelRange[1].toInt()).toShort())
                                eq.enabled = true
                                getSharedPreferences("music_equalizer", MODE_PRIVATE).edit()
                                    .putInt("band:$band", eq.getBandLevel(band.toShort()).toInt()).apply()
                                publishModes()
                            }
                        }
                        else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .setId(SESSION_ID)
            .setSessionActivity(pendingIntentToOpenApp())
            .build()

        // Only the channel id and notification id are configured. media3 1.5's builder has no
        // `setSmallIcon`, and `MediaNotification`'s constructor takes the icon only when
        // constructing a new notification — so a custom status-bar icon would mean rebuilding
        // media3's notification wholesale, which is exactly the fragility this avoids. The
        // default is the app icon, which is the right mark for us anyway.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(PlayerNotification.CHANNEL_ID)
                .setNotificationId(PlayerNotification.notificationId())
                .build(),
        )

        player = exo
        session = mediaSession
        exo.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                equalizer?.release()
                equalizer = runCatching {
                    android.media.audiofx.Equalizer(0, audioSessionId).apply {
                        val prefs = getSharedPreferences("music_equalizer", MODE_PRIVATE)
                        for (band in 0 until numberOfBands) setBandLevel(band.toShort(),
                            prefs.getInt("band:$band", 0).coerceIn(bandLevelRange[0].toInt(), bandLevelRange[1].toInt()).toShort())
                        enabled = true
                    }
                }.getOrNull()
                publishModes()
            }
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                    events.contains(Player.EVENT_REPEAT_MODE_CHANGED)) {
                    loadSuggestions()
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                // A track that ends before its successor is inserted enters ENDED; a
                // late insert alone never resumes the player.
                val exo = player ?: return
                if (playbackState == Player.STATE_ENDED && exo.hasNextMediaItem()) {
                    exo.play()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val exo = player ?: return
                // A dead stream stops the player instead of advancing; skip it when
                // the queue continues (never when the user asked for repeat).
                if (exo.repeatMode == Player.REPEAT_MODE_OFF && exo.hasNextMediaItem()) {
                    exo.seekToNextMediaItem()
                    exo.play()
                }
            }
        })

        scope.launch {
            val container = (application as AurisApplication).container
            container.authRepository.session.collect { next ->
                if (shouldStopOnSessionChange(hadSession, next != null)) stopForSignOut()
                hadSession = next != null
            }
        }
    }

    private fun stopForSignOut() {
        sleepTimer.set(0)
        smartJob?.cancel()
        smartJob = null
        val exo = player
        exo?.stop()
        exo?.clearMediaItems()
        equalizer?.release()
        equalizer = null
        exo?.release()
        session?.release()
        player = null
        session = null
        hadSession = false
        stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publishModes() {
        session?.setSessionExtras(Bundle().apply {
            putLong(SLEEP_DEADLINE, sleepDeadline)
            putBoolean(SMART_ENABLED, smartShuffle)
            equalizer?.let { eq ->
                putIntArray("eqFrequencies", (0 until eq.numberOfBands).map { eq.getCenterFreq(it.toShort()) / 1000 }.toIntArray())
                putIntArray("eqLevels", (0 until eq.numberOfBands).map { eq.getBandLevel(it.toShort()).toInt() }.toIntArray())
                putInt("eqMin", eq.bandLevelRange[0].toInt())
                putInt("eqMax", eq.bandLevelRange[1].toInt())
            }
        })
    }

    private fun setSleepTimer(durationMs: Long) {
        sleepTimer.set(durationMs)
    }

    private fun setSmartShuffle(enabled: Boolean) {
        smartJob?.cancel()
        suggestionSeed = null
        smartShuffle = enabled
        publishModes()
        val exo = player ?: return
        if (!enabled) {
            for (index in exo.mediaItemCount - 1 downTo 0) {
                if (index != exo.currentMediaItemIndex &&
                    exo.getMediaItemAt(index).mediaMetadata.extras?.getBoolean(SMART_ITEM) == true) {
                    exo.removeMediaItem(index)
                }
            }
            loadSuggestions()
            return
        }
        exo.shuffleModeEnabled = true
        loadSuggestions()
    }

    private fun loadSuggestions() {
        val exo = player ?: return
        val seed = exo.currentMediaItem ?: return
        // Continue at the end of a queue without changing the user's repeat or shuffle mode.
        if (!smartShuffle && (exo.repeatMode != Player.REPEAT_MODE_OFF || exo.hasNextMediaItem())) return
        if (smartJob?.isActive == true && suggestionSeed == seed.mediaId) return
        smartJob?.cancel()
        suggestionSeed = seed.mediaId
        val artist = seed.mediaMetadata.artist?.toString()
        smartJob = scope.launch {
            try {
                val container = (application as AurisApplication).container
                val source = container.musicSource
                val existing = (0 until exo.mediaItemCount).map { exo.getMediaItemAt(it).mediaId }.toSet()
                val candidates = MusicSuggestions.suggest(
                    source, container.libraryRepository.observeLibrary().first(),
                    seedArtist = artist, excludedIds = existing, limit = 3,
                )
                for (track in candidates) {
                    val stream = source.resolveAudioStream(track.videoId).getOrNull()?.stream
                        ?.takeIf { it.isPlayable() } ?: continue
                    currentCoroutineContext().ensureActive()
                    if (exo !== player) return@launch
                    // A new selection replaces the queue while recommendations are resolving.
                    if (exo.currentMediaItem?.mediaId != seed.mediaId ||
                        (!smartShuffle && exo.repeatMode != Player.REPEAT_MODE_OFF)) return@launch
                    if ((0 until exo.mediaItemCount).any { exo.getMediaItemAt(it).mediaId == track.videoId }) continue
                    val item = buildMediaItem(track.videoId, track.title, track.artist, track.album,
                        track.largeArtworkUrl ?: track.artworkUrl, stream.url, stream.mimeType)
                    currentCoroutineContext().ensureActive()
                    if (exo !== player) return@launch
                    exo.addMediaItem(item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon()
                        .setExtras(Bundle().apply { putBoolean(SMART_ITEM, true) }).build()).build())
                }
                if (exo !== player) return@launch
                // Suggestions that arrive after the queue ended sit unplayed otherwise.
                resumeIfEnded(exo)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("AurisMusic", "Could not load playback suggestions", error)
            }
        }
    }

    // Media3 derives isTrusted from the system media-control trust check. Keep system/notification
    // controllers working while rejecting arbitrary installed apps before exposing the session.
    internal fun isAllowedController(controller: MediaSession.ControllerInfo): Boolean =
        controller.uid == android.os.Process.myUid() || controller.isTrusted

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (isAllowedController(controllerInfo)) session else null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while music plays should *not* stop playback — that is the point
        // of a background service. Playback stops when the queue ends, the user pauses, or the
        // service is killed.
        if (player?.isPlaying != true) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        equalizer?.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        player = null
        super.onDestroy()
    }

    /** Opens the app when the notification is tapped. */
    private fun pendingIntentToOpenApp(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_OPEN_APP,
        Intent(this, com.auris.app.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        // IMMUTABLE: the intent carries nothing another app could usefully fill in, and
        // mutability is forbidden from Android 12.
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        /**
         * Stable session id.
         *
         * A controller binds by id, so it must survive a process restart or the notification's
         * buttons would bind to a fresh session each time the app is opened.
         */
        const val SET_SLEEP_TIMER = "auris.SET_SLEEP_TIMER"
        const val SET_EQUALIZER = "auris.SET_EQUALIZER"
        const val SLEEP_DURATION = "durationMs"
        const val SLEEP_DEADLINE = "sleepDeadline"
        const val SET_SMART_SHUFFLE = "auris.SET_SMART_SHUFFLE"
        const val SMART_ENABLED = "smartShuffle"
        private const val SMART_ITEM = "smartItem"
        const val SESSION_ID = "auris-music"

        private const val REQUEST_OPEN_APP = 1001
    }
}

/**
 * Builds the [MediaItem] for one track.
 *
 * ### Why the metadata is filled in
 * The shade and lock screen render whatever [MediaMetadata] carries. A bare item shows an empty
 * title and no artwork, which is the most visible sign of a half-finished player — so title,
 * artist, album and artwork are all populated from the domain model.
 */
@OptIn(UnstableApi::class)
fun buildMediaItem(
    videoId: String,
    title: String,
    artist: String?,
    album: String?,
    artworkUrl: String?,
    streamUrl: String,
    /**
     * The stream's container type, when the resolver knows it.
     *
     * Passed through so an Opus/WebM stream is not announced to the extractor factory as
     * `audio/mp4`; a wrong hint is worse than none, because the factory trusts it. Null or an
     * unrecognised type falls back to [AUDIO_MIME_TYPE].
     */
    mimeType: String? = null,
): MediaItem = MediaItem.Builder()
    .setMediaId(videoId)
    .setUri(streamUrl)
    .setMimeType(declaredAudioMime(mimeType) ?: AUDIO_MIME_TYPE)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setAlbumArtist(artist)
            .setArtworkUri(artworkUrl?.let(android.net.Uri::parse))
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build(),
    )
    .build()

/**
 * True when a real session was present and just became null (sign-out, delete, expiry).
 * The cold-start null is not a sign-out: [com.auris.app.data.repository.AuthRepository.session]
 * awaits initialized, so the first null cannot mean the user signed out during playback.
 */
internal fun shouldStopOnSessionChange(hadSession: Boolean, hasSession: Boolean): Boolean =
    hadSession && !hasSession

/**
 * Re-drives a player that reached ENDED before a late-inserted next item arrived.
 *
 * ExoPlayer does not resume on its own after media items are added to an ENDED
 * player, so without this the queue stalls: the late item sits unplayed and the
 * track never reaches Recently Played. The next item must exist before resuming —
 * otherwise nothing would advance, only the ended track would replay.
 * Returns true when a resume was issued.
 */
internal fun resumeIfEnded(player: Player): Boolean {
    if (player.playbackState != Player.STATE_ENDED || !player.hasNextMediaItem()) return false
    player.prepare()
    player.play()
    return true
}

/**
 * The default hint, used when the resolver does not name a container.
 *
 * AAC in MP4 is what most providers hand back and what this device has played. Stating a type
 * avoids a probe request; when the resolver *does* know the container, it is passed through
 * instead, because a wrong hint is worse than none.
 */
private const val AUDIO_MIME_TYPE = "audio/mp4"

/**
 * Reduces a resolver's MIME type to one media3 can act on.
 *
 * Providers name the container *with parameters* (`audio/webm; codecs="opus"`), which is not a
 * MIME type the extractor factory will match. Anything outside the set is dropped rather than
 * guessed: with no hint the factory sniffs the stream, which is correct even if slightly slower.
 */
private fun declaredAudioMime(mimeType: String?): String? =
    mimeType?.substringBefore(';')?.trim()?.takeIf { it in SUPPORTED_AUDIO_MIME_TYPES }

private val SUPPORTED_AUDIO_MIME_TYPES = setOf(
    "audio/mp4",
    "audio/webm",
    "audio/ogg",
    "audio/mpeg",
    "audio/aac",
)
