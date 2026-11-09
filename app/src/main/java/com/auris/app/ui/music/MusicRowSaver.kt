package com.auris.app.ui.music

import androidx.compose.runtime.saveable.listSaver
import com.auris.app.data.remote.music.MusicResultType

/** Retains the collection/artist under Now Playing when its navigation entry is restored. */
internal val MusicRowSaver = listSaver<MusicRow?, String>(
    save = { row ->
        if (row == null) listOf("none") else listOf(
            row.kind.name, row.title, row.subtitle.orEmpty(), row.artworkUrl.orEmpty(),
            row.id, row.resultType.name, row.albumArtworkUrl.orEmpty(),
        )
    },
    restore = { values ->
        if (values.size == 1) null else MusicRow(
            kind = MusicRow.Kind.valueOf(values[0]), title = values[1],
            subtitle = values[2].ifEmpty { null }, artworkUrl = values[3].ifEmpty { null },
            id = values[4], resultType = MusicResultType.valueOf(values[5]),
            albumArtworkUrl = values[6].ifEmpty { null },
        )
    },
)
