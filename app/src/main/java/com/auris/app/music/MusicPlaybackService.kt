package com.auris.app.music

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while music plays should *not* stop playback — that is the point
        // of a background service. Playback stops when the queue ends, the user pauses, or the
        // service is killed.
        if (player?.isPlaying != true) {
            stopSelf()
        }
    }

    override fun onDestroy() {
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
