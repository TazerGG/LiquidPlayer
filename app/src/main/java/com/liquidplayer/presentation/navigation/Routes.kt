package com.liquidplayer.presentation.navigation

import android.net.Uri

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val PLAYLISTS = "playlists"
    const val FAVORITES = "favorites"
    const val SETTINGS = "settings"
    const val FOLDERS = "folders"
    const val NOW_PLAYING = "now_playing"
    const val PLAYLIST = "playlist/{playlistId}"
    const val COLLECTION = "collection/{type}?key={key}"

    fun playlist(id: Long) = "playlist/$id"
    fun collection(type: String, key: String) = "collection/$type?key=${Uri.encode(key)}"
}
