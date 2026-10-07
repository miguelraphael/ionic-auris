package com.auris.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.auris.app.core.model.MediaType
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.local.entity.LibraryUnitEntity
import com.auris.app.data.repository.ExtensionContentRepository
import com.auris.app.data.repository.KitsuIdentifier
import com.auris.app.data.repository.LibraryRepository
import com.auris.app.source.SourceUnit
import com.auris.app.source.StreamTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Drives the media detail screen: entry metadata, Kitsu identification, and extension units.
 *
 * ### Three independent concerns, deliberately not serialised
 * Room metadata, the Kitsu lookup and the extension unit fetch are launched **separately**.
 * Awaiting them in sequence would make a slow Kitsu call delay the episode list by seconds for
 * no reason — the list is what the user came for. A Kitsu outage must also not be able to
 * prevent units from loading, or the reverse.
 *
 * ### Identification is triggered, not awaited
 * [identifyIfNeeded] fires once per entry and never blocks the UI. The screen shows the
 * extension's own title immediately and Kitsu's richer metadata replaces it when it lands;
 * waiting on a network call before showing a title the user just tapped feels broken.
 */
class MediaDetailViewModel(
    private val entryId: String,
    private val libraryRepository: LibraryRepository,
    private val identifier: KitsuIdentifier,
    private val contentRepository: ExtensionContentRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MediaDetailUiState())
    val state: StateFlow<MediaDetailUiState> = _state.asStateFlow()

    /**
     * Guards so each side effect fires once per entry.
     *
     * Necessary because this class writes to Room, and every write re-emits the observed entry.
     * Without the guards, saving a `kitsuId` would immediately trigger a second Kitsu search,
     * which would then save nothing, and the loop would continue.
     */
    private var identificationStarted = false
    private var unitsStarted = false

    /**
     * Whether the Kitsu lookup has finished, one way or the other.
     *
     * A plain flag rather than deriving it from `isMetadataLoading` / `metadataUnavailable`:
     * those two cannot distinguish "not started" from "started and in flight" from "finished",
     * and this class must not re-arm the spinner on each Room emission.
     */
    private var identificationDone = false

    /** Latest entry, needed by [onUnitClick] which is invoked outside a `collect`. */
    private var currentEntry: LibraryEntryEntity? = null

    init {
        // Entry and stored units are observed as one stream so the screen can never render an
        // entry against a unit list belonging to a different entry.
        viewModelScope.launch {
            combine(
                libraryRepository.observeEntry(entryId),
                libraryRepository.observeUnits(entryId),
            ) { entry, units -> entry to units }
                .collect { (entry, units) ->
                    if (entry == null) {
                        _state.value = _state.value.copy(
                            error = "This entry is no longer in your library",
                        )
                        return@collect
                    }

                    currentEntry = entry
                    _state.value = _state.value.withEntry(entry, units)

                    if (!identificationStarted) {
                        identificationStarted = true
                        identifyIfNeeded(entry)
                    }
                    if (!unitsStarted) {
                        unitsStarted = true
                        refreshUnits()
                    }
                }
        }
    }

    /**
     * Fetches Kitsu metadata for an entry that has never been identified.
     *
     * Skipped for music, which Kitsu does not catalogue, and for entries already carrying a
     * `kitsuId` — otherwise every screen open would re-search.
     */
    private fun identifyIfNeeded(entry: LibraryEntryEntity) {
        if (entry.kitsuId != null) {
            identificationDone = true
            _state.value = _state.value.copy(isMetadataLoading = false)
            return
        }
        if (entry.mediaType == MediaType.MUSIC) {
            identificationDone = true
            _state.value = _state.value.copy(
                isMetadataLoading = false,
                // Stated rather than silently omitted, so a bare title reads as "no catalogue
                // data" rather than "still loading".
                metadataUnavailable = true,
            )
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(isMetadataLoading = true)

            val match = identifier.identifyAndPersist(entry)

            // On success the Room write re-emits the entry, which is what actually puts the
            // enriched metadata on screen. Only the "nothing found" path is handled here, so the
            // spinner cannot spin forever.
            identificationDone = true
            if (match == null) {
                // On success the Room write re-emits the entry, which is what puts the enriched
                // metadata on screen. Only the miss is handled here so the spinner cannot spin
                // forever.
                _state.value = _state.value.copy(
                    isMetadataLoading = false,
                    metadataUnavailable = true,
                )
            }
        }
    }

    /**
     * Re-fetches units from the extension and persists them.
     *
     * The stored list from Room is already on screen; this refreshes it. A failure leaves the
     * stored list untouched — a stored list is still the best information available when the
     * network is down, and clearing it would turn a transient error into data loss.
     */
    fun refreshUnits() {
        val entry = currentEntry ?: return

        viewModelScope.launch {
            _state.value = _state.value.copy(isUnitsLoading = true, error = null)

            contentRepository.refreshUnits(entry)
                .onSuccess {
                    // Room re-emits with the new units; the flag clears on that emission too, so
                    // it is cleared here only to cover the "returned but identical" case.
                    _state.value = _state.value.copy(isUnitsLoading = false)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        isUnitsLoading = false,
                        // An uninstalled source is permanent; a network failure is not.
                        // Collapsing them would show "unavailable" for a title that loads fine
                        // next time.
                        error = error.toUserMessage(),
                    )
                }
        }
    }

    /**
     * Handles a unit tap.
     *
     * Manga navigates straight to the reader: a chapter needs no resolution step, so a loading
     * state would only slow it down. Anime resolves a stream first, because most sources return
     * an embed page rather than a media URL, and handing that to a player would fail silently
     * once playback started.
     */
    fun onUnitClick(
        unit: SourceUnit,
        onNavigateToUnit: (Int) -> Unit,
        onPlay: (SourceUnit, List<StreamTarget>) -> Unit,
    ) {
        val entry = currentEntry ?: return

        if (entry.mediaType == MediaType.MANGA) {
            onNavigateToUnit(unit.index)
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(isResolvingStream = true)

            contentRepository.fetchStreams(entry, unit)
                .onSuccess { targets ->
                    _state.value = _state.value.copy(isResolvingStream = false)

                    if (targets.isEmpty()) {
                        // Empty is a real answer from a real source, not a failure: the episode is
                        // listed but nothing is playable yet.
                        _state.value = _state.value.copy(
                            message = "No playable stream for this episode yet",
                        )
                    } else {
                        onPlay(unit, targets)
                    }
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        isResolvingStream = false,
                        message = error.toUserMessage(),
                    )
                }
        }
    }

    /** User-triggered retry for both Kitsu and the extension. */
    fun retry() {
        currentEntry?.let { entry ->
            if (entry.kitsuId == null && entry.mediaType != MediaType.MUSIC) {
                identifyIfNeeded(entry)
            }
        }
        refreshUnits()
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /**
     * Projects a Room row plus its units onto screen state, without clobbering in-flight flags.
     *
     * The transient flags are preserved deliberately: a units refresh writes to Room, which
     * re-emits here, and clearing `isResolvingStream` on that emission would hide the spinner
     * for a *different* operation that may still be running.
     */
    private fun MediaDetailUiState.withEntry(
        entry: LibraryEntryEntity,
        units: List<LibraryUnitEntity>,
    ) = copy(
        mediaType = entry.mediaType,
        title = entry.title,
        originalTitle = entry.kitsuOriginalTitle,
        synopsis = entry.description,
        posterUrl = entry.artworkUrl,
        coverUrl = entry.kitsuCoverUrl,
        genres = entry.genres,
        averageRating = entry.kitsuRating,
        status = entry.status.name,
        totalUnits = entry.totalUnits.takeIf { it > 0 },
        currentUnitIndex = entry.currentUnitIndex,
        units = units.map { it.toSourceUnit() },
        watchedUnitIndices = units.filter { it.isWatched }.map { it.unitIndex }.toSet(),
        // The spinner follows whether we are still waiting, so an unrelated Room emission (a
        // progress write, say) cannot re-arm or prematurely clear it.
        isMetadataLoading = entry.kitsuId == null && !identificationDone,
        metadataUnavailable = metadataUnavailable,
        error = error,
    )

    /** Room rows store the source-relative id; the screen needs the display shape. */
    private fun LibraryUnitEntity.toSourceUnit() = SourceUnit(
        // Nullable in Room (a unit can exist before its source id is known), but the screen needs
        // a value. A blank id is inert: the extension will simply fail to match it, which is
        // better than a crash or a silent row that does nothing when tapped.
        url = sourceUnitId.orEmpty(),
        index = unitIndex,
        title = title,
        thumbnailUrl = thumbnailUrl,
        uploadTimestamp = updatedAt,
    )

    private fun Throwable.toUserMessage(): String =
        if (this is ExtensionContentRepository.UnavailableException) {
            "The extension providing this content is not installed"
        } else {
            "Could not reach the source. Check your connection and try again."
        }

    private class Factory(
        private val entryId: String,
        private val libraryRepository: LibraryRepository,
        private val identifier: KitsuIdentifier,
        private val contentRepository: ExtensionContentRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MediaDetailViewModel(entryId, libraryRepository, identifier, contentRepository) as T
    }

    companion object {
        fun factory(
            entryId: String,
            libraryRepository: LibraryRepository,
            identifier: KitsuIdentifier,
            contentRepository: ExtensionContentRepository,
        ): ViewModelProvider.Factory =
            Factory(entryId, libraryRepository, identifier, contentRepository)
    }
}