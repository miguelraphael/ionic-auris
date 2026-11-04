package com.auris.app.music

import android.content.Context
import com.auris.app.ui.music.MusicRow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Device-local playlists. */
internal class MusicLocalCollections(private val context: Context) {
    private val prefs = context.getSharedPreferences("music_collections", Context.MODE_PRIVATE)
    data class Playlist(val id: String, val name: String, val tracks: List<MusicRow>)

    fun playlists(): List<Playlist> {
        val array = JSONArray(prefs.getString("playlists", "[]"))
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Playlist(item.getString("id"), item.getString("name"), decode(item.getJSONArray("tracks")))
        }
    }

    fun add(name: String, row: MusicRow, id: String? = null) {
        val lists = playlists().toMutableList()
        val index = lists.indexOfFirst { it.id == id }
        if (index < 0) lists += Playlist(UUID.randomUUID().toString(), name, listOf(row))
        else lists[index] = lists[index].copy(tracks = (lists[index].tracks + row).distinctBy { it.id })
        val array = JSONArray()
        lists.forEach { list -> array.put(JSONObject().put("id", list.id).put("name", list.name)
            .put("tracks", encode(list.tracks))) }
        prefs.edit().putString("playlists", array.toString()).apply()
    }

    private fun encode(rows: List<MusicRow>) = JSONArray().apply {
        rows.forEach { put(JSONObject().put("id", it.id).put("title", it.title)
            .put("artist", it.subtitle.orEmpty()).put("art", it.artworkUrl.orEmpty())) }
    }
    private fun decode(array: JSONArray) = (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        MusicRow(MusicRow.Kind.TRACK, item.getString("title"), item.optString("artist"),
            item.optString("art").takeIf { it.isNotBlank() }, item.getString("id"))
    }
}
