
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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

private fun loadFavorites(context: Context): Set<String> =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet("favorites", emptySet())
        ?.toSet() ?: emptySet()

private fun saveFavorites(context: Context, values: Set<String>) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putStringSet("favorites", values.toSet())
        .apply()
}

private fun loadPlaylists(context: Context): Map<String, List<String>> {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("playlists", null) ?: return emptyMap()

    return try {
        val json = JSONObject(raw)
        val result = mutableMapOf<String, List<String>>()
        val keys = json.keys()

        while (keys.hasNext()) {
            val name = keys.next()
            val array = json.getJSONArray(name)
            result[name] = List(array.length()) { array.getString(it) }
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
            val idCol = cursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media._ID
            )
            val titleCol = cursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.TITLE
            )
            val artistCol = cursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.ARTIST
            )
            val dateCol = cursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.DATE_ADDED
            )
            val durationCol = cursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.DURATION
            )
            val typeCol = cursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.MIME_TYPE
            )

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)

                val title = cursor.getString(titleCol)
                    ?.takeIf { it.isNotBlank() }
                    ?: "Unknown song"

                val artist = cursor.getString(artistCol)
                    ?.takeUnless { it.isBlank() || it == "<unknown>" }
                    ?: "Unknown artist"

                val dateAdded = cursor.getLong(dateCol)
                val duration = cursor.getLong(durationCol)
                val mimeType = cursor.getString(typeCol) ?: "audio/*"

                result.add(
                    Song(
                        uri = uri,
                        title = title,
                        artist = artist,
                        dateAdded = dateAdded,
                        duration = duration,
                        mimeType = mimeType
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
                "Music access denied. Check app settings."
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
                val uri = mediaItem?.localConfiguration?.uri
                    ?: return

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

        if (name.isNotEmpty() && name !in playlists) {
            playlists = playlists + (name to emptyList())
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
            onSurface = WHITE
        )
    ) {
        Scaffold(
            containerColor = BG,

            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "Soundwave",
                                color = WHITE,
                                fontSize = 25.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                "YOUR MUSIC. YOUR WAY.",
                                color = RED,
                                fontSize = 10.sp,
                                letterSpacing = 2.sp
                            )
                        }
                    },

                    actions = {
                        IconButton(
                            onClick = { showSettings = true }
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = WHITE
                            )
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
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = RED,
                                modifier = Modifier
                                    .padding(horizontal = 5.dp)
                                    .size(30.dp)
                            )

                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    song.title,
                                    color = WHITE,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold
                                )

                                Text(
                                    song.artist,
                                    color = GREY,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }

                            IconButton(
                                onClick = {
                                    player.seekToPreviousMediaItem()
                                }
                            ) {
                                Icon(
                                    Icons.Default.SkipPrevious,
                                    contentDescription = "Previous",
                                    tint = WHITE
                                )
                            }

                            IconButton(
                                onClick = {
                                    if (playing) player.pause()
                                    else player.play()
                                }
                            ) {
                                AnimatedContent(
                                    targetState = playing,
                                    label = "Play pause"
                                ) { isPlaying ->
                                    Icon(
                                        if (isPlaying) Icons.Default.Pause
                                        else Icons.Default.PlayArrow,
                                        contentDescription = "Play or pause",
                                        tint = RED
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    player.seekToNextMediaItem()
                                }
                            ) {
                                Icon(
                                    Icons.Default.SkipNext,
                                    contentDescription = "Next",
                                    tint = WHITE
                                )
                            }
                        }
                    }

                    NavigationBar(
                        containerColor = Color(0xFF111111)
                    ) {
                        val labels = listOf(
                            "Songs", "Favourites", "Playlists", "Artists"
                        )

                        val icons = listOf(
                            Icons.Default.MusicNote,
                            Icons.Default.Favorite,
                            Icons.Default.QueueMusic,
                            Icons.Default.Person
                        )

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
                                    Icon(
                                        icons[index],
                                        contentDescription = label
                                    )
                                },

                                label = {
                                    Text(
                                        label,
                                        fontSize = 9.sp,
                                        maxLines = 1
                                    )
                                },

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
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp)
            ) {
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search music, artists…") },

                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search",
                            tint = GREY
                        )
                    },

                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = WHITE,
                        unfocusedTextColor = WHITE,
                        focusedBorderColor = RED,
                        unfocusedBorderColor = CARD,
                        focusedLeadingIconColor = RED,
                        unfocusedLeadingIconColor = GREY
                    )
                )

                Spacer(Modifier.height(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (
                        (tab == 2 && selectedPlaylist != null) ||
                        (tab == 3 && selectedArtist != null)
                    ) {
                        IconButton(
                            onClick = {
                                selectedPlaylist = null
                                selectedArtist = null
                                search = ""
                            }
                        ) {
                            Icon(
                                Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = RED
                            )
                        }
                    }

                    val heading = when (tab) {
                        1 -> "Your favourites"
                        2 -> selectedPlaylist ?: "Your playlists"
                        3 -> selectedArtist ?: "Artists"
                        else -> "Your music"
                    }

                    Text(
                        heading,
                        color = WHITE,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    val showingSongs =
                        tab == 0 ||
                            tab == 1 ||
                            (tab == 2 && selectedPlaylist != null) ||
                            (tab == 3 && selectedArtist != null)

                    if (showingSongs) {
                        Box {
                            TextButton(
                                onClick = { sortMenu = true }
                            ) {
                                Text(
                                    sort,
                                    color = RED,
                                    fontSize = 11.sp
                                )

                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "Sort",
                                    tint = RED
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

                    if (tab == 2 && selectedPlaylist == null) {
                        IconButton(
                            onClick = {
                                playlistName = ""
                                showCreate = true
                            }
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Create playlist",
                                tint = RED
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            if (permissionGranted) {
                                refreshCounter++
                            } else {
                                permissionLauncher.launch(audioPermission)
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh music",
                            tint = RED
                        )
                    }
                }

                Text(
                    status,
                    color = GREY,
                    fontSize = 11.sp
                )

                Spacer(Modifier.height(5.dp))

                when {
                    !permissionGranted -> {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.LibraryMusic,
                                    contentDescription = null,
                                    tint = RED,
                                    modifier = Modifier.size(52.dp)
                                )

                                Spacer(Modifier.height(8.dp))

                                Text(
                                    "Allow Soundwave to access your music",
                                    color = WHITE
                                )

                                Spacer(Modifier.height(8.dp))

                                Button(
                                    onClick = {
                                        permissionLauncher.launch(audioPermission)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = RED
                                    )
                                ) {
                                    Text("Grant permission")
                                }
                            }
                        }
                    }

                    tab == 2 && selectedPlaylist == null -> {
                        if (playlistNames.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Create your first playlist with +",
                                    color = GREY
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(playlistNames, key = { it }) { name ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = CARD
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    selectedPlaylist = name
                                                    search = ""
                                                }
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.QueueMusic,
                                                contentDescription = null,
                                                tint = RED,
                                                modifier = Modifier.size(30.dp)
                                            )

                                            Spacer(Modifier.width(12.dp))

                                            Column(
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(
                                                    name,
                                                    color = WHITE,
                                                    fontWeight = FontWeight.SemiBold
                                                )

                                                Text(
                                                    "${playlists[name].orEmpty().size} saved songs",
                                                    color = GREY,
                                                    fontSize = 12.sp
                                                )
                                            }

                                            IconButton(
                                                onClick = {
                                                    playlists = playlists - name
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "Delete playlist",
                                                    tint = GREY
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    tab == 3 && selectedArtist == null -> {
                        if (artists.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No artists found", color = GREY)
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(artists, key = { it }) { artist ->
                                    val count = songs.count {
                                        it.artist == artist
                                    }

                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = CARD
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    selectedArtist = artist
                                                    search = ""
                                                }
                                                .padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.Person,
                                                contentDescription = null,
                                                tint = RED,
                                                modifier = Modifier.size(30.dp)
                                            )

                                            Spacer(Modifier.width(12.dp))

                                            Column {
                                                Text(
                                                    artist,
                                                    color = WHITE,
                                                    fontWeight = FontWeight.SemiBold
                                                )

                                                Text(
                                                    "$count songs",
                                                    color = GREY,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    visibleSongs.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                when (tab) {
                                    1 -> "No favourite songs yet"
                                    2 -> "This playlist has no available songs"
                                    else -> "No songs found"
                                },
                                color = GREY
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(bottom = 8.dp)
                        ) {
                            items(
                                visibleSongs,
                                key = { it.uri.toString() }
                            ) { song ->
                                val isFavorite =
                                    song.uri.toString() in favorites

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = CARD
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                playSong(song, visibleSongs)
                                            }
                                            .padding(
                                                start = 10.dp,
                                                top = 8.dp,
                                                bottom = 8.dp,
                                                end = 4.dp
                                            ),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(42.dp)
                                                .background(
                                                    Color(0xFF351515),
                                                    RoundedCornerShape(9.dp)
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Default.MusicNote,
                                                contentDescription = null,
                                                tint = RED
                                            )
                                        }

                                        Spacer(Modifier.width(10.dp))

                                        Column(
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                song.title,
                                                color = WHITE,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )

                                            Text(
                                                song.artist,
                                                color = GREY,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )

                                            Text(
                                                "${formatDuration(song.duration)} • ${song.mimeType.substringAfter("/")}",
                                                color = GREY,
                                                fontSize = 10.sp,
                                                maxLines = 1
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                toggleFavorite(song)
                                            }
                                        ) {
                                            Icon(
                                                if (isFavorite) {
                                                    Icons.Default.Favorite
                                                } else {
                                                    Icons.Default.FavoriteBorder
                                                },
                                                contentDescription = "Toggle favourite",
                                                tint = if (isFavorite) RED else GREY
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                songToAdd = song
                                                showAddToPlaylist = true
                                            }
                                        ) {
                                            Icon(
                                                Icons.Default.PlaylistAdd,
                                                contentDescription = "Add to playlist",
                                                tint = GREY
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
                containerColor = CARD,
                title = {
                    Text("Create playlist", color = WHITE)
                },
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
                        onClick = { createPlaylist() },
                        enabled = playlistName.isNotBlank() &&
                            playlistName.trim() !in playlists
                    ) {
                        Text("Create", color = RED)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showCreate = false }
                    ) {
                        Text("Cancel", color = GREY)
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
                containerColor = CARD,
                title = {
                    Text("Add to playlist", color = WHITE)
                },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (playlists.isEmpty()) {
                            Text(
                                "You haven't created a playlist yet.",
                                color = GREY
                            )
                        }

                        playlists.keys.sorted().forEach { name ->
                            TextButton(
                                onClick = { addSongToPlaylist(name) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    name,
                                    color = WHITE
                                )
                            }
                        }

                        TextButton(
                            onClick = {
                                showAddToPlaylist = false
                                playlistName = ""
                                showCreate = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                tint = RED
                            )

                            Spacer(Modifier.width(6.dp))

                            Text("Create new playlist", color = RED)
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
                        Text("Done", color = RED)
                    }
                }
            )
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                containerColor = CARD,
                title = {
                    Text("Soundwave settings", color = WHITE)
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Music access: " +
                                if (permissionGranted) "Granted" else "Not granted",
                            color = if (permissionGranted) RED else GREY
                        )

                        Text(
                            "Favourites and playlists are saved on this device.",
                            color = WHITE
                        )

                        Text(
                            "Soundwave scans audio files available to the app on your device.",
                            color = GREY
                        )

                        Button(
                            onClick = {
                                if (permissionGranted) {
                                    refreshCounter++
                                } else {
                                    permissionLauncher.launch(audioPermission)
                                }
                                showSettings = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RED
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (permissionGranted) {
                                    "Rescan music"
                                } else {
                                    "Grant music permission"
                                }
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { showSettings = false }
                    ) {
                        Text("Close", color = RED)
                    }
                }
            )
        }
    }
}
