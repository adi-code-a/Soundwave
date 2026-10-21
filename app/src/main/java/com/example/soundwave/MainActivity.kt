
package com.example.soundwave

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject

private data class Song(val uri: Uri, val title: String)

private const val PREFS_NAME = "soundwave_storage"

private fun loadFavorites(context: Context): Set<String> {
    val prefs = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )
    return prefs.getStringSet("favorites", emptySet())
        ?.toSet() ?: emptySet()
}

private fun saveFavorites(context: Context, favorites: Set<String>) {
    context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    ).edit()
        .putStringSet("favorites", favorites.toSet())
        .apply()
}

private fun readSong(context: Context, uri: Uri): Song {
    var title = uri.lastPathSegment
        ?.substringAfterLast('/')
        ?.substringAfterLast(':')
        ?: "Local track"

    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null, null, null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(
                OpenableColumns.DISPLAY_NAME
            )
            if (index >= 0) {
                title = cursor.getString(index) ?: title
            }
        }
    }

    return Song(uri, title.ifBlank { "Local track" })
}

private fun loadPlaylists(context: Context): Map<String, List<Song>> {
    val prefs = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )
    val saved = prefs.getString("playlists_json", null)
        ?: return emptyMap()

    return try {
        val json = JSONObject(saved)
        val result = mutableMapOf<String, List<Song>>()
        val keys = json.keys()

        while (keys.hasNext()) {
            val name = keys.next()
            val uris = json.getJSONArray(name)
            val restored = mutableListOf<Song>()

            for (i in 0 until uris.length()) {
                val uri = Uri.parse(uris.getString(i))
                try {
                    restored.add(readSong(context, uri))
                } catch (_: Exception) {
                    // Ignore files that cannot be accessed.
                }
            }
            result[name] = restored
        }
        result
    } catch (_: Exception) {
        emptyMap()
    }
}

private fun savePlaylists(
    context: Context,
    playlists: Map<String, List<Song>>
) {
    val json = JSONObject()

    playlists.forEach { (name, songs) ->
        json.put(
            name,
            JSONArray(songs.map { it.uri.toString() })
        )
    }

    context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    ).edit()
        .putString("playlists_json", json.toString())
        .apply()
}

class MainActivity : ComponentActivity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        setContent {
            SoundwaveScreen(player!!)
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundwaveScreen(player: ExoPlayer) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var songs by remember { mutableStateOf(listOf<Song>()) }
    var current by remember { mutableStateOf<Song?>(null) }
    var playing by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) }

    // Load saved data when the screen starts.
    var favorites by remember {
        mutableStateOf(loadFavorites(context))
    }
    var playlists by remember {
        mutableStateOf(loadPlaylists(context))
    }

    // Save automatically whenever either value changes.
    LaunchedEffect(favorites) {
        saveFavorites(context, favorites)
    }

    LaunchedEffect(playlists) {
        savePlaylists(context, playlists)
    }

    var showCreatePlaylist by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var songForPlaylist by remember { mutableStateOf<Song?>(null) }
    var showPlaylistPicker by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val added = uris.mapNotNull { uri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Some providers do not support persistent permissions.
            }

            try {
                readSong(context, uri)
            } catch (_: Exception) {
                null
            }
        }

        songs = (songs + added).distinctBy { it.uri }
    }

    val bg = Color(0xFF101010)
    val surface = Color(0xFF202020)
    val red = Color(0xFFFF3030)
    val white = Color.White
    val muted = Color.LightGray

    fun playSong(song: Song) {
        current = song
        player.setMediaItem(MediaItem.fromUri(song.uri))
        player.prepare()
        player.play()
        playing = true
    }

    val visibleSongs = when (selectedTab) {
        1 -> songs.filter { it.uri.toString() in favorites }
        else -> songs
    }.filter { it.title.contains(search, ignoreCase = true) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = red,
            background = bg,
            surface = surface
        )
    ) {
        Scaffold(
            containerColor = bg,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "Soundwave",
                                fontWeight = FontWeight.Bold,
                                fontSize = 25.sp
                            )
                            Text(
                                "Your music. Your way.",
                                color = muted,
                                fontSize = 12.sp
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = bg,
                        titleContentColor = white
                    )
                )
            },
            bottomBar = {
                current?.let { song ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(surface)
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                song.title,
                                color = white,
                                maxLines = 1,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Local audio",
                                color = muted,
                                fontSize = 12.sp
                            )
                        }
                        IconButton(onClick = {
                            if (playing) {
                                player.pause()
                                playing = false
                            } else {
                                player.play()
                                playing = true
                            }
                        }) {
                            Icon(
                                if (playing) Icons.Default.Pause
                                else Icons.Default.PlayArrow,
                                contentDescription = "Play or pause",
                                tint = red
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                Modifier.padding(padding)
                    .fillMaxSize()
                    .padding(horizontal = 12.dp)
            ) {
                Spacer(Modifier.height(8.dp))

                Row(
                    Modifier.fillMaxWidth()
                        .background(surface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = muted
                    )
                    TextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text("Search music") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = white,
                            unfocusedTextColor = white,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Compact tabs that fit on one line.
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = bg,
                    contentColor = red
                ) {
                    listOf("Songs", "Favourites", "Playlists").forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                    title,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                when (selectedTab) {
                    0 -> {
                        Button(
                            onClick = {
                                picker.launch(arrayOf("audio/*"))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = red,
                                contentColor = white
                            )
                        ) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Add local music")
                        }
                    }

                    1 -> Text(
                        "Your favourite songs",
                        color = white,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )

                    2 -> {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Your playlists",
                                color = white,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    playlistName = ""
                                    showCreatePlaylist = true
                                }
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    tint = red
                                )
                                Text("Create", color = red)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (selectedTab == 2) {
                    if (playlists.isEmpty()) {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No playlists yet. Tap Create.",
                                color = muted
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(playlists.keys.toList()) { name ->
                                Column(
                                    Modifier.fillMaxWidth()
                                        .background(
                                            surface,
                                            RoundedCornerShape(12.dp)
                                        )
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        name,
                                        color = white,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "${playlists[name]?.size ?: 0} songs",
                                        color = muted,
                                        fontSize = 12.sp
                                    )

                                    playlists[name].orEmpty().forEach { song ->
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .clickable { playSong(song) }
                                                .padding(vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                song.title,
                                                color = white,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1
                                            )
                                            IconButton(
                                                onClick = {
                                                    playlists = playlists +
                                                        (name to playlists[name].orEmpty()
                                                            .filterNot {
                                                                it.uri == song.uri
                                                            })
                                                }
                                            ) {
                                                Text("×", color = red)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (visibleSongs.isEmpty()) {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (selectedTab == 1)
                                "No favourites yet. Tap ♡ beside a song."
                            else
                                "Add local music to get started.",
                            color = muted
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(
                            visibleSongs,
                            key = { it.uri.toString() }
                        ) { song ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(
                                        surface,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { playSong(song) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(42.dp)
                                        .background(
                                            Color(0xFF303030),
                                            RoundedCornerShape(8.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("♫", color = red, fontSize = 23.sp)
                                }

                                Spacer(Modifier.width(10.dp))

                                Column(Modifier.weight(1f)) {
                                    Text(
                                        song.title,
                                        color = white,
                                        maxLines = 1,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        "Local audio",
                                        color = muted,
                                        fontSize = 11.sp
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        favorites = if (
                                            song.uri.toString() in favorites
                                        ) {
                                            favorites - song.uri.toString()
                                        } else {
                                            favorites + song.uri.toString()
                                        }
                                    }
                                ) {
                                    Icon(
                                        if (song.uri.toString() in favorites)
                                            Icons.Default.Favorite
                                        else
                                            Icons.Default.FavoriteBorder,
                                        contentDescription = "Toggle favourite",
                                        tint = red
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        songForPlaylist = song
                                        showPlaylistPicker = true
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Add to playlist",
                                        tint = white
                                    )
                                }

                                IconButton(
                                    onClick = { playSong(song) }
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = "Play",
                                        tint = red
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showCreatePlaylist) {
            AlertDialog(
                onDismissRequest = {
                    showCreatePlaylist = false
                },
                title = { Text("Create playlist") },
                text = {
                    OutlinedTextField(
                        value = playlistName,
                        onValueChange = { playlistName = it },
                        label = { Text("Playlist name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val name = playlistName.trim()
                            if (name.isNotEmpty() &&
                                name !in playlists
                            ) {
                                playlists = playlists + (name to emptyList())
                            }
                            showCreatePlaylist = false
                        }
                    ) {
                        Text("Create")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showCreatePlaylist = false
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showPlaylistPicker) {
            AlertDialog(
                onDismissRequest = {
                    showPlaylistPicker = false
                },
                title = {
                    Text("Add to playlist")
                },
                text = {
                    if (playlists.isEmpty()) {
                        Text("Create a playlist first in the Playlists tab.")
                    } else {
                        Column {
                            playlists.keys.forEach { name ->
                                Text(
                                    name,
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable {
                                            val song = songForPlaylist
                                            if (song != null) {
                                                val existing =
                                                    playlists[name].orEmpty()
                                                if (existing.none {
                                                    it.uri == song.uri
                                                }) {
                                                    playlists = playlists +
                                                        (name to (existing + song))
                                                }
                                            }
                                            showPlaylistPicker = false
                                        }
                                        .padding(12.dp),
                                    color = white
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showPlaylistPicker = false
                        }
                    ) {
                        Text("Close")
                    }
                }
            )
        }
    }
}
