package com.example.soundwave

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class Song(
    val uri: Uri,
    val title: String,
    val artist: String,
    val dateAdded: Long,
    val duration: Long,
    val mimeType: String
)

private const val PREFS = "soundwave_storage"

private val BG = Color(0xFF0A0A0A)
private val CARD = Color(0xFF191919)
private val RED = Color(0xFFFF3030)
private val WHITE = Color(0xFFF5F5F5)
private val GREY = Color(0xFFAAAAAA)

private fun loadFavorites(context: Context): Set<String> {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet("favorites", emptySet())
        ?.toSet()
        ?: emptySet()
}

private fun saveFavorites(context: Context, values: Set<String>) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putStringSet("favorites", values.toSet())
        .apply()
}

private fun loadPlaylists(context: Context): Map<String, List<String>> {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("playlists", null)
        ?: return emptyMap()

    return try {
        val json = JSONObject(raw)
        val result = mutableMapOf<String, List<String>>()
        val keys = json.keys()

        while (keys.hasNext()) {
            val name = keys.next()
            val array = json.getJSONArray(name)
            result[name] = List(array.length()) { index ->
                array.getString(index)
            }
        }

        result
    } catch (_: Exception) {
        emptyMap()
    }
}

private fun savePlaylists(
    context: Context,
    playlists: Map<String, List<String>>
) {
    val json = JSONObject()

    playlists.forEach { (name, uris) ->
        json.put(name, JSONArray(uris))
    }

    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString("playlists", json.toString())
        .apply()
}

private suspend fun scanMusic(context: Context): List<Song> =
    withContext(Dispatchers.IO) {
        val result = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val columns = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE
        )

        context.contentResolver.query(
            collection,
            columns,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { cursor ->

            val idColumn = cursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media._ID
            )
            val titleColumn = cursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.TITLE
            )
            val artistColumn = cursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.ARTIST
            )
            val dateColumn = cursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.DATE_ADDED
            )
            val durationColumn = cursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.DURATION
            )
            val typeColumn = cursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.MIME_TYPE
            )

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(collection, id)

                val title = cursor.getString(titleColumn)
                    ?.takeIf { it.isNotBlank() }
                    ?: "Unknown song"

                val artist = cursor.getString(artistColumn)
                    ?.takeUnless {
                        it.isBlank() || it == "<unknown>"
                    }
                    ?: "Unknown artist"

                result.add(
                    Song(
                        uri = uri,
                        title = title,
                        artist = artist,
                        dateAdded = cursor.getLong(dateColumn),
                        duration = cursor.getLong(durationColumn),
                        mimeType = cursor.getString(typeColumn) ?: "audio/*"
                    )
                )
            }
        }

        result
    }

private fun formatDuration(milliseconds: Long): String {
    if (milliseconds <= 0L) return "--:--"

    val seconds = milliseconds / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

class MainActivity : ComponentActivity() {

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        player = ExoPlayer.Builder(this).build()

        setContent {
            SoundwaveScreen(player = player!!)
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

    val audioPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    var songs by remember { mutableStateOf(emptyList<Song>()) }
    var favorites by remember { mutableStateOf(loadFavorites(context)) }
    var playlists by remember { mutableStateOf(loadPlaylists(context)) }

    var current by remember { mutableStateOf<Song?>(null) }
    var playQueue by remember { mutableStateOf(emptyList<Song>()) }
    var playing by remember { mutableStateOf(false) }

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                audioPermission
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var status by remember { mutableStateOf("Checking music access…") }
    var tab by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("Name") }
    var sortMenu by remember { mutableStateOf(false) }

    var showSettings by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }

    var playlistName by remember { mutableStateOf("") }
    var songToAdd by remember { mutableStateOf<Song?>(null) }
    var selectedArtist by remember { mutableStateOf<String?>(null) }
    var selectedPlaylist by remember { mutableStateOf<String?>(null) }
    var refreshCounter by remember { mutableIntStateOf(0) }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            permissionGranted = granted

            status = if (granted) {
                "Music access granted"
            } else {
                "Music access denied. Grant permission in settings."
            }
        }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            permissionLauncher.launch(audioPermission)
        }
    }

    LaunchedEffect(permissionGranted, refreshCounter) {
        if (permissionGranted) {
            status = "Scanning your music…"

            try {
                songs = scanMusic(context)
                status = "${songs.size} songs in your library"
            } catch (_: Exception) {
                status = "Could not scan music. Check app permissions."
            }
        }
    }

    LaunchedEffect(favorites) {
        saveFavorites(context, favorites)
    }

    LaunchedEffect(playlists) {
        savePlaylists(context, playlists)
    }

    DisposableEffect(player, playQueue) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int
            ) {
                val uri = mediaItem?.localConfiguration?.uri ?: return

                playQueue.firstOrNull { it.uri == uri }?.let {
                    current = it
                }
            }
        }

        player.addListener(listener)

        onDispose {
            player.removeListener(listener)
        }
    }

    fun playSong(song: Song, queue: List<Song>) {
        val actualQueue = queue.ifEmpty { listOf(song) }
        val index = actualQueue.indexOfFirst { it.uri == song.uri }
            .coerceAtLeast(0)

        current = song
        playQueue = actualQueue

        player.setMediaItems(
            actualQueue.map { MediaItem.fromUri(it.uri) }
        )
        player.seekTo(index, 0L)
        player.prepare()
        player.play()
    }

    fun toggleFavorite(song: Song) {
        val key = song.uri.toString()

        favorites = if (key in favorites) {
            favorites - key
        } else {
            favorites + key
        }
    }

    fun createPlaylist() {
        val name = playlistName.trim()

        if (name.isEmpty()) {
            status = "Enter a playlist name."
        } else if (name in playlists) {
            status = "That playlist already exists."
        } else {
            playlists = playlists + (name to emptyList())
            status = "Created playlist: $name"
        }

        playlistName = ""
        showCreate = false
    }

    fun addSongToPlaylist(name: String) {
        val song = songToAdd ?: return
        val uri = song.uri.toString()
        val existing = playlists[name].orEmpty()

        playlists = playlists + (
            name to (existing + uri).distinct()
        )

        songToAdd = null
        showAddToPlaylist = false

        Toast.makeText(
            context,
            "Added to $name",
            Toast.LENGTH_SHORT
        ).show()
    }

    val artists = songs
        .map { it.artist }
        .distinct()
        .filter { it.contains(search, ignoreCase = true) }
        .sorted()

    val playlistNames = playlists.keys
        .filter { it.contains(search, ignoreCase = true) }
        .sorted()

    val baseSongs = when (tab) {
        1 -> songs.filter { it.uri.toString() in favorites }

        2 -> {
            selectedPlaylist?.let { name ->
                val savedUris = playlists[name].orEmpty().toSet()
                songs.filter { it.uri.toString() in savedUris }
            }.orEmpty()
        }

        3 -> {
            selectedArtist?.let { artist ->
                songs.filter { it.artist == artist }
            }.orEmpty()
        }

        else -> songs
    }

    val visibleSongs = baseSongs
        .filter {
            it.title.contains(search, ignoreCase = true) ||
                it.artist.contains(search, ignoreCase = true) ||
                it.mimeType.contains(search, ignoreCase = true)
        }
        .let { list ->
            when (sort) {
                "Artist" -> list.sortedBy { it.artist.lowercase() }
                "Date added" -> list.sortedByDescending { it.dateAdded }
                "Type" -> list.sortedBy { it.mimeType.lowercase() }
                "Duration" -> list.sortedByDescending { it.duration }
                else -> list.sortedBy { it.title.lowercase() }
            }
        }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = RED,
            background = BG,
            surface = CARD,
            onSurface = WHITE,
            onBackground = WHITE
        )
    ) {
        Scaffold(
            containerColor = BG,

            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Soundwave",
                                color = WHITE,
                                fontSize = 25.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "YOUR MUSIC. YOUR WAY.",
                                color = RED,
                                fontSize = 10.sp,
                                letterSpacing = 2.sp
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = { showSettings = true }
                        ) {
                            Text("⚙", color = WHITE, fontSize = 22.sp)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = BG
                    )
                )
            },

            bottomBar = {
                Column {
                    current?.let { song ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF241818))
                                .padding(horizontal = 4.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "♫",
                                color = RED,
                                fontSize = 27.sp,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )

                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = song.title,
                                    color = WHITE,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = song.artist,
                                    color = GREY,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            TextButton(
                                onClick = {
                                    player.seekToPreviousMediaItem()
                                }
                            ) {
                                Text("◀|", color = WHITE)
                            }

                            TextButton(
                                onClick = {
                                    if (playing) player.pause()
                                    else player.play()
                                }
                            ) {
                                Text(
                                    text = if (playing) "Ⅱ" else "▶",
                                    color = RED,
                                    fontSize = 20.sp
                                )
                            }

                            TextButton(
                                onClick = {
                                    player.seekToNextMediaItem()
                                }
                            ) {
                                Text("|▶", color = WHITE)
                            }
                        }
                    }

                    NavigationBar(
                        containerColor = Color(0xFF111111)
                    ) {
                        val labels = listOf(
                            "Songs",
                            "Favourites",
                            "Playlists",
                            "Artists"
                        )

                        val symbols = listOf("♫", "♥", "▤", "♟")

                        labels.forEachIndexed { index, label ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = {
                                    tab = index
                                    search = ""
                                    selectedArtist = null
                                    selectedPlaylist = null
                                },
                                icon = {
                                    Text(
                                        text = symbols[index],
                                        fontSize = 18.sp
                                    )
                                },
                                label = {
                                    Text(
                                        text = label,
                                        fontSize = 9.sp,
                                        maxLines = 1
                                    )
                                },
                                alwaysShowLabel = true,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = RED,
                                    selectedTextColor = RED,
                                    indicatorColor = Color(0xFF351515),
                                    unselectedIconColor = GREY,
                                    unselectedTextColor = GREY
                                )
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search music") },
                    placeholder = { Text("Song, artist or file type") },
                    trailingIcon = {
                        if (search.isNotEmpty()) {
                            TextButton(onClick = { search = "" }) {
                                Text("Clear")
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RED,
                        focusedLabelColor = RED,
                        cursorColor = RED
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = status,
                    color = GREY,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (!permissionGranted) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Allow Soundwave to access your music.",
                            color = WHITE
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                permissionLauncher.launch(audioPermission)
                            }
                        ) {
                            Text("Grant music access")
                        }
                    }
                } else if (tab == 3 && selectedArtist == null) {
                    Text(
                        text = "Artists",
                        color = WHITE,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (artists.isEmpty()) {
                        Text(
                            text = "No artists found.",
                            color = GREY
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            items(artists, key = { it }) { artist ->
                                val count = songs.count {
                                    it.artist == artist
                                }

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable {
                                            selectedArtist = artist
                                        },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = CARD
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "♫",
                                            color = RED,
                                            fontSize = 25.sp
                                        )

                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(start = 12.dp)
                                        ) {
                                            Text(
                                                text = artist,
                                                color = WHITE,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "$count songs",
                                                color = GREY,
                                                fontSize = 12.sp
                                            )
                                        }

                                        Text("›", color = RED, fontSize = 25.sp)
                                    }
                                }
                            }
                        }
                    }
                } else if (tab == 2 && selectedPlaylist == null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Playlists",
                            color = WHITE,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )

                        Button(
                            onClick = {
                                playlistName = ""
                                showCreate = true
                            }
                        ) {
                            Text("+ New")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (playlistNames.isEmpty()) {
                        Text(
                            text = "No playlists yet. Create one to get started.",
                            color = GREY
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            items(playlistNames, key = { it }) { name ->
                                val count = playlists[name].orEmpty().size

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable {
                                            selectedPlaylist = name
                                        },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = CARD
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "▤",
                                            color = RED,
                                            fontSize = 24.sp
                                        )

                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(start = 12.dp)
                                        ) {
                                            Text(
                                                text = name,
                                                color = WHITE,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "$count saved tracks",
                                                color = GREY,
                                                fontSize = 12.sp
                                            )
                                        }

                                        Text("›", color = RED, fontSize = 25.sp)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            if (tab == 2 && selectedPlaylist != null) {
                                TextButton(
                                    onClick = { selectedPlaylist = null },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("← All playlists", color = RED)
                                }
                            }

                            if (tab == 3 && selectedArtist != null) {
                                TextButton(
                                    onClick = { selectedArtist = null },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("← All artists", color = RED)
                                }
                            }

                            Text(
                                text = when (tab) {
                                    1 -> "Favourites"
                                    2 -> selectedPlaylist ?: "Playlist"
                                    3 -> selectedArtist ?: "Artist"
                                    else -> "All songs"
                                },
                                color = WHITE,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = "${visibleSongs.size} songs",
                                color = GREY,
                                fontSize = 11.sp
                            )
                        }

                        Box {
                            OutlinedButton(
                                onClick = { sortMenu = true },
                                contentPadding = PaddingValues(
                                    horizontal = 10.dp,
                                    vertical = 6.dp
                                )
                            ) {
                                Text(
                                    text = "Sort",
                                    color = RED
                                )
                            }

                            DropdownMenu(
                                expanded = sortMenu,
                                onDismissRequest = { sortMenu = false }
                            ) {
                                listOf(
                                    "Name",
                                    "Artist",
                                    "Date added",
                                    "Type",
                                    "Duration"
                                ).forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        onClick = {
                                            sort = option
                                            sortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (visibleSongs.isEmpty()) {
                        Text(
                            text = when (tab) {
                                1 -> "No favourite songs yet. Tap ♡ beside a song."
                                2 -> "This playlist has no matching songs."
                                3 -> "No songs found for this artist."
                                else -> "No songs found on this device."
                            },
                            color = GREY
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            items(
                                items = visibleSongs,
                                key = { it.uri.toString() }
                            ) { song ->
                                val isFavorite =
                                    song.uri.toString() in favorites

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = CARD
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                playSong(song, visibleSongs)
                                            }
                                            .padding(
                                                start = 10.dp,
                                                end = 4.dp,
                                                top = 10.dp,
                                                bottom = 10.dp
                                            ),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (current?.uri == song.uri) {
                                                "▶"
                                            } else {
                                                "♫"
                                            },
                                            color = RED,
                                            fontSize = 22.sp
                                        )

                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(horizontal = 10.dp)
                                        ) {
                                            Text(
                                                text = song.title,
                                                color = WHITE,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )

                                            Text(
                                                text = song.artist,
                                                color = GREY,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )

                                            Text(
                                                text = formatDuration(song.duration),
                                                color = GREY,
                                                fontSize = 10.sp
                                            )
                                        }

                                        TextButton(
                                            onClick = {
                                                toggleFavorite(song)
                                            },
                                            contentPadding = PaddingValues(4.dp)
                                        ) {
                                            Text(
                                                text = if (isFavorite) "♥" else "♡",
                                                color = RED,
                                                fontSize = 21.sp
                                            )
                                        }

                                        TextButton(
                                            onClick = {
                                                songToAdd = song
                                                showAddToPlaylist = true
                                            },
                                            contentPadding = PaddingValues(4.dp)
                                        ) {
                                            Text(
                                                text = "+",
                                                color = WHITE,
                                                fontSize = 21.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showCreate) {
            AlertDialog(
                onDismissRequest = { showCreate = false },
                title = {
                    Text("Create playlist")
                },
                text = {
                    OutlinedTextField(
                        value = playlistName,
                        onValueChange = { playlistName = it },
                        singleLine = true,
                        label = { Text("Playlist name") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = RED,
                            focusedLabelColor = RED,
                            cursorColor = RED
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = { createPlaylist() }) {
                        Text("Create", color = RED)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreate = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showAddToPlaylist) {
            AlertDialog(
                onDismissRequest = {
                    showAddToPlaylist = false
                    songToAdd = null
                },
                title = {
                    Text("Add to playlist")
                },
                text = {
                    if (playlists.isEmpty()) {
                        Text("You haven't created any playlists yet.")
                    } else {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 300.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            playlists.keys.sorted().forEach { name ->
                                TextButton(
                                    onClick = { addSongToPlaylist(name) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = name,
                                        color = WHITE
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showAddToPlaylist = false
                            songToAdd = null
                        }
                    ) {
                        Text("Close")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showAddToPlaylist = false
                            playlistName = ""
                            showCreate = true
                        }
                    ) {
                        Text("New playlist", color = RED)
                    }
                }
            )
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = {
                    Text("Soundwave settings")
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "Local music player",
                            color = WHITE,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Music library: $status",
                            color = GREY,
                            fontSize = 12.sp
                        )

                        TextButton(
                            onClick = {
                                refreshCounter++
                            }
                        ) {
                            Text("Rescan music", color = RED)
                        }

                        TextButton(
                            onClick = {
                                permissionLauncher.launch(audioPermission)
                            }
                        ) {
                            Text("Request music permission", color = RED)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Favourites and playlists are saved on this device.",
                            color = GREY,
                            fontSize = 12.sp
                        )

                        Text(
                            text = "Soundwave • Local Music",
                            color = GREY,
                            fontSize = 12.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSettings = false }) {
                        Text("Done", color = RED)
                    }
                }
            )
        }
    }
}