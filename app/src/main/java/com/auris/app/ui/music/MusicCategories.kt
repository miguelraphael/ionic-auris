package com.auris.app.ui.music

import androidx.compose.ui.graphics.Color

/**
 * One tile on the Search landing.
 *
 * The [query] is separate from the [title] because the heading is written for the screen ("Film,
 * TV & Stage") while the catalogue wants the words people actually search. Keeping both means
 * the tile copy can be polished without silently changing what is fetched.
 *
 * Colours are a gradient pair rather than artwork: real category covers are licensed images we
 * cannot ship, and a flat gradient with the title over it keeps the same shelf-of-covers rhythm
 * without borrowing anything.
 */
data class MusicCategory(
    val title: String,
    val query: String,
    val colors: List<Color>,
)

/**
 * The Search landing, in display order.
 *
 * The list leans toward what the configured content country (PH) actually listens to — OPM and
 * K-Pop above the generic Western genres — because a category row that mostly returns nothing
 * familiar is worse than a shorter one.
 */
val MUSIC_CATEGORIES: List<MusicCategory> = listOf(
    MusicCategory(
        title = "Live Music",
        query = "live music",
        colors = listOf(Color(0xFFE8305A), Color(0xFF6E1030)),
    ),
    MusicCategory(
        title = "Pop",
        query = "pop hits",
        colors = listOf(Color(0xFFFF5E8A), Color(0xFFB01E86)),
    ),
    MusicCategory(
        title = "Original Pilipino Music",
        query = "OPM",
        colors = listOf(Color(0xFFFFB13B), Color(0xFFC2521A)),
    ),
    MusicCategory(
        title = "K-Pop",
        query = "K-Pop",
        colors = listOf(Color(0xFF8B5CF6), Color(0xFF3B1E8C)),
    ),
    MusicCategory(
        title = "Alternative",
        query = "alternative",
        colors = listOf(Color(0xFF9ACD5A), Color(0xFF2F5D1F)),
    ),
    MusicCategory(
        title = "Hip-Hop/Rap",
        query = "hip hop rap",
        colors = listOf(Color(0xFF4BA3FF), Color(0xFF123C82)),
    ),
    MusicCategory(
        title = "R&B",
        query = "R&B",
        colors = listOf(Color(0xFFC084FC), Color(0xFF7A1FA2)),
    ),
    MusicCategory(
        title = "Rock",
        query = "rock",
        colors = listOf(Color(0xFFF07818), Color(0xFF6B1D00)),
    ),
    MusicCategory(
        title = "Film, TV & Stage",
        query = "movie soundtrack",
        colors = listOf(Color(0xFF2DD4BF), Color(0xFF0B4F57)),
    ),
    MusicCategory(
        title = "Charts",
        query = "top hits",
        colors = listOf(Color(0xFFFFD54F), Color(0xFFB36A00)),
    ),
)
