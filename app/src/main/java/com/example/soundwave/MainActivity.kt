 
package com.example.soundwave
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import android.media.MediaMetadataRetriever
import android.graphics.BitmapFactory

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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        ?.toSet()
        ?: emptySet()

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
                val uri = ContentUris.withAppendedId(
                    collection,
                    cursor.getLong(idColumn)
                )

                val title = cursor.getString(titleColumn)
                    ?.takeIf { it.isNotBlank() }
                    ?: "Unknown song"

                val artist = cursor.getString(artistColumn)
                    ?.takeUnless { it.isBlank() || it == "<unknown>" }
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
    if (milliseconds <= 0L) return "0:00"
    val seconds = milliseconds / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

class MainActivity : ComponentActivity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer

        setContent {
            var showSplash by remember { mutableStateOf(true) }

            LaunchedEffect(Unit) {
                delay(1800)
                showSplash = false
            }

            if (showSplash) {
                val transition = rememberInfiniteTransition(
                    label = "soundwave_splash"
                )

                val logoScale by transition.animateFloat(
                    initialValue = 0.88f,
                    targetValue = 1.08f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(
                            700,
                            easing = FastOutSlowInEasing
                        ),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "logo_scale"
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BG),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            painter = painterResource(
                                id = R.drawable.ic_soundwave_launcher
                            ),
                            contentDescription = "Soundwave logo",
                            modifier = Modifier
                                .size(120.dp)
                                .scale(logoScale)
                        )

                        Spacer(Modifier.height(22.dp))

                        Text(
                            "SOUNDWAVE",
                            color = WHITE,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 4.sp
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            "YOUR MUSIC. YOUR WAY.",
                            color = RED,
                            fontSize = 10.sp,
                            letterSpacing = 2.sp
                        )
                    }
                }
            } else {
                SoundwaveScreen(exoPlayer)
            }
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
    val context = LocalContext.current

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
    var fullPlayer by remember { mutableStateOf(false) }

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                audioPermission
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var status by remember { mutableStateOf("Ready to play") }
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
    var shuffleEnabled by remember {
        mutableStateOf(player.shuffleModeEnabled)
    }
    var repeatMode by remember { mutableIntStateOf(player.repeatMode) }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            permissionGranted = granted
            status = if (granted) {
                "Music access granted"
            } else {
                "Music access denied. Allow audio access in settings."
            }
        }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            permissionLauncher.launch(audioPermission)
        }
    }

    LaunchedEffect(permissionGranted, refreshCounter) {
        if (permissionGranted) {
            status = "Scanning your music..."
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
        onDispose { player.removeListener(listener) }
    }

    fun playSong(song: Song, queue: List<Song>) {
        val actualQueue = queue.ifEmpty { listOf(song) }
        val index = actualQueue.indexOfFirst { it.uri == song.uri }
            .coerceAtLeast(0)

        current = song
        playQueue = actualQueue

        player.setMediaItems(actualQueue.map {
            MediaItem.fromUri(it.uri)
        })
        player.seekTo(index, 0L)
        player.prepare()
        player.play()
        fullPlayer = true
    }

    fun toggleFavorite(song: Song) {
        val key = song.uri.toString()
        favorites = if (key in favorites) favorites - key
        else favorites + key
    }

    fun createPlaylist() {
        val name = playlistName.trim()

        when {
            name.isEmpty() -> status = "Enter a playlist name."
            name in playlists -> status = "That playlist already exists."
            else -> {
                playlists = playlists + (name to emptyList())
                status = "Created playlist: $name"
            }
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

    fun requestAddToPlaylist(song: Song) {
        songToAdd = song
        showAddToPlaylist = true
    }

    fun toggleShuffle() {
        shuffleEnabled = !shuffleEnabled
        player.shuffleModeEnabled = shuffleEnabled
    }

    fun cycleRepeat() {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        player.repeatMode = repeatMode
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

        2 -> selectedPlaylist?.let { name ->
            val savedUris = playlists[name].orEmpty().toSet()
            songs.filter { it.uri.toString() in savedUris }
        }.orEmpty()

        3 -> selectedArtist?.let { artist ->
            songs.filter { it.artist == artist }
        }.orEmpty()

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

    BackHandler(enabled = fullPlayer) {
        fullPlayer = false
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
                if (!fullPlayer) {
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
                            if (tab == 2) {
                                TextButton(onClick = {
                                    showCreate = true
                                }) {
                                    Text("+ Playlist", color = RED)
                                }
                            }
                            TextButton(onClick = {
                                showSettings = true
                            }) {
                                Text("⚙", color = WHITE, fontSize = 23.sp)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = BG
                        )
                    )
                }
            },

            bottomBar = {
                if (!fullPlayer) {
                    Column {
                        current?.let { song ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF241414))
                                    .clickable { fullPlayer = true }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(43.dp)
                                        .background(
                                            Color(0xFF351515),
                                            RoundedCornerShape(10.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    SongArtwork(song, Modifier.size(43.dp), 10.dp)
                                }

                                Spacer(Modifier.width(10.dp))

                                Column(Modifier.weight(1f)) {
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
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                TextButton(onClick = {
                                    player.seekToPreviousMediaItem()
                                }) {
                                    Text("⏮", color = WHITE, fontSize = 19.sp)
                                }

                                TextButton(onClick = {
                                    if (playing) player.pause()
                                    else player.play()
                                }) {
                                    Text(
                                        if (playing) "Ⅱ" else "▶",
                                        color = RED,
                                        fontSize = 22.sp
                                    )
                                }

                                TextButton(onClick = {
                                    player.seekToNextMediaItem()
                                }) {
                                    Text("⏭", color = WHITE, fontSize = 19.sp)
                                }
                            }
                        }

                        NavigationBar(
                            containerColor = Color(0xFF111111)
                        ) {
                            val labels = listOf(
                                "Songs", "Favourites", "Playlists", "Artists"
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
                                            symbols[index],
                                            fontSize = 18.sp
                                        )
                                    },
                                    label = {
                                        Text(label, fontSize = 9.sp, maxLines = 1)
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
            }
        ) { innerPadding ->

            if (fullPlayer) {
                FullPlayerScreen(
                    player = player,
                    current = current,
                    playing = playing,
                    favorites = favorites,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    onBack = { fullPlayer = false },
                    onToggleFavorite = {
                        current?.let { toggleFavorite(it) }
                    },
                    onAddToPlaylist = {
                        current?.let { requestAddToPlaylist(it) }
                    },
                    onToggleShuffle = { toggleShuffle() },
                    onCycleRepeat = { cycleRepeat() }
                )
            } else {
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
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = RED,
                            cursorColor = RED,
                            focusedLabelColor = RED
                        )
                    )

                    Spacer(Modifier.height(8.dp))

                    if (tab == 0 || tab == 1 ||
                        (tab == 2 && selectedPlaylist != null) ||
                        (tab == 3 && selectedArtist != null)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                when (tab) {
                                    1 -> "Favourites"
                                    2 -> selectedPlaylist ?: "Playlists"
                                    3 -> selectedArtist ?: "Artists"
                                    else -> "All songs"
                                },
                                color = WHITE,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )

                            Box {
                                TextButton(onClick = {
                                    sortMenu = true
                                }) {
                                    Text("Sort: $sort ▾", color = RED)
                                }

                                DropdownMenu(
                                    expanded = sortMenu,
                                    onDismissRequest = { sortMenu = false },
                                    modifier = Modifier.background(CARD)
                                ) {
                                    listOf(
                                        "Name", "Artist", "Date added",
                                        "Type", "Duration"
                                    ).forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option, color = WHITE) },
                                            onClick = {
                                                sort = option
                                                sortMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    when {
                        !permissionGranted -> {
                            EmptyMessage(
                                "Music permission is needed to scan your songs."
                            )
                        }

                        tab == 2 && selectedPlaylist == null -> {
                            Text(
                                "Your playlists",
                                color = WHITE,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )

                            if (playlistNames.isEmpty()) {
                                EmptyMessage(
                                    "No playlists yet. Tap + Playlist to create one."
                                )
                            } else {
                                LazyColumn {
                                    items(playlistNames) { name ->
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                                .clickable {
                                                    selectedPlaylist = name
                                                    search = ""
                                                },
                                            colors = CardDefaults.cardColors(
                                                containerColor = CARD
                                            ),
                                            shape = RoundedCornerShape(14.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(16.dp),
                                                verticalAlignment =
                                                    Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    "▤",
                                                    color = RED,
                                                    fontSize = 27.sp
                                                )
                                                Spacer(Modifier.width(12.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(
                                                        name,
                                                        color = WHITE,
                                                        fontWeight =
                                                            FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        "${playlists[name].orEmpty().size} saved songs",
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
                        }

                        tab == 3 && selectedArtist == null -> {
                            Text(
                                "Artists",
                                color = WHITE,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )

                            if (artists.isEmpty()) {
                                EmptyMessage("No artists found.")
                            } else {
                                LazyColumn {
                                    items(artists) { artist ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                                .background(
                                                    CARD,
                                                    RoundedCornerShape(12.dp)
                                                )
                                                .clickable {
                                                    selectedArtist = artist
                                                    search = ""
                                                }
                                                .padding(16.dp),
                                            verticalAlignment =
                                                Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "♫",
                                                color = RED,
                                                fontSize = 24.sp
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Text(
                                                artist,
                                                color = WHITE,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text("›", color = RED, fontSize = 25.sp)
                                        }
                                    }
                                }
                            }
                        }

                        else -> {
                            if (tab == 2 && selectedPlaylist != null) {
                                TextButton(onClick = {
                                    selectedPlaylist = null
                                    search = ""
                                }) {
                                    Text("‹ All playlists", color = RED)
                                }
                            }

                            if (tab == 3 && selectedArtist != null) {
                                TextButton(onClick = {
                                    selectedArtist = null
                                    search = ""
                                }) {
                                    Text("‹ All artists", color = RED)
                                }
                            }

                            Text(
                                "${visibleSongs.size} songs",
                                color = GREY,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 5.dp)
                            )

                            if (visibleSongs.isEmpty()) {
                                EmptyMessage(
                                    if (songs.isEmpty()) status
                                    else "No songs found here."
                                )
                            } else {
                                LazyColumn {
                                    items(
                                        items = visibleSongs,
                                        key = { it.uri.toString() }
                                    ) { song ->
                                        SongRow(
                                            song = song,
                                            isFavorite =
                                                song.uri.toString() in favorites,
                                            onPlay = {
                                                playSong(song, visibleSongs)
                                            },
                                            onToggleFavorite = {
                                                toggleFavorite(song)
                                            },
                                            onAddToPlaylist = {
                                                requestAddToPlaylist(song)
                                            },
                                            onPlayNext = {
                                                val queue = playQueue.toMutableList()
                                                queue.removeAll {
                                                    it.uri == song.uri
                                                }
                                                val insertAt =
                                                    (player.currentMediaItemIndex + 1)
                                                        .coerceIn(0, queue.size)
                                                queue.add(insertAt, song)
                                                playQueue = queue

                                                if (current == null) {
                                                    playSong(song, listOf(song))
                                                } else {
                                                    player.addMediaItem(
                                                        player.currentMediaItemIndex + 1,
                                                        MediaItem.fromUri(song.uri)
                                                    )
                                                    Toast.makeText(
                                                        context,
                                                        "Added to the queue",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        )
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
                title = { Text("Create playlist", color = WHITE) },
                text = {
                    OutlinedTextField(
                        value = playlistName,
                        onValueChange = { playlistName = it },
                        label = { Text("Playlist name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = { createPlaylist() }) {
                        Text("Create", color = RED)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreate = false }) {
                        Text("Cancel", color = GREY)
                    }
                }
            )
        }

        if (showAddToPlaylist) {
            AlertDialog(
                onDismissRequest = { showAddToPlaylist = false },
                containerColor = CARD,
                title = { Text("Add to playlist", color = WHITE) },
                text = {
                    if (playlists.isEmpty()) {
                        Text(
                            "You have no playlists yet. Create one from the Playlists tab first.",
                            color = WHITE
                        )
                    } else {
                        Column(
                            modifier = Modifier.verticalScroll(
                                rememberScrollState()
                            )
                        ) {
                            playlists.keys.sorted().forEach { name ->
                                TextButton(
                                    onClick = { addSongToPlaylist(name) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(name, color = WHITE)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAddToPlaylist = false }) {
                        Text("Close", color = RED)
                    }
                },
                dismissButton = {
                    if (playlists.isEmpty()) {
                        TextButton(onClick = {
                            showAddToPlaylist = false
                            showCreate = true
                        }) {
                            Text("Create playlist", color = RED)
                        }
                    }
                }
            )
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                containerColor = CARD,
                title = { Text("Settings", color = WHITE) },
                text = {
                    Column {
                        Text(
                            "Soundwave Music Player",
                            color = RED,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Theme: Red and black", color = WHITE)
                        Text("Music library: ${songs.size} songs", color = GREY)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Favourites and playlists are saved on this device.",
                            color = GREY,
                            fontSize = 12.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showSettings = false
                        refreshCounter++
                    }) {
                        Text("Rescan music", color = RED)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSettings = false }) {
                        Text("Close", color = WHITE)
                    }
                }
            )
        }
    }
}


@Composable
private fun SongArtwork(
    song: Song,
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 10.dp
) {
    val context = LocalContext.current
    val artwork by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = song.uri
    ) {
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, song.uri)
                retriever.embeddedPicture?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            } catch (_: Exception) {
                null
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(Color(0xFF351515)),
        contentAlignment = Alignment.Center
    ) {
        if (artwork != null) {
            Image(
                bitmap = artwork!!,
                contentDescription = "Album artwork for ${song.title}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text("♫", color = RED, fontSize = 25.sp)
        }
    }
}

@Composable
private fun SongRow(
    song: Song,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onPlayNext: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(CARD, RoundedCornerShape(12.dp))
            .clickable { onPlay() }
            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(43.dp)
                .background(
                    Color(0xFF351515),
                    RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            SongArtwork(song, Modifier.size(43.dp), 10.dp)
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
            Spacer(Modifier.height(3.dp))
            Text(
                song.artist,
                color = GREY,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${formatDuration(song.duration)} · ${song.mimeType}",
                color = GREY,
                fontSize = 10.sp,
                maxLines = 1
            )
        }

        Box {
            TextButton(onClick = { menuExpanded = true }) {
                Text("⋮", color = WHITE, fontSize = 25.sp)
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(CARD)
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (isFavorite) "Remove favourite"
                            else "Add to favourites",
                            color = WHITE
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onToggleFavorite()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Add to playlist", color = WHITE) },
                    onClick = {
                        menuExpanded = false
                        onAddToPlaylist()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Play next", color = WHITE) },
                    onClick = {
                        menuExpanded = false
                        onPlayNext()
                    }
                )
            }
        }
    }
}

@Composable
private fun EmptyMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            message,
            color = GREY,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun FullPlayerScreen(
    player: ExoPlayer,
    current: Song?,
    playing: Boolean,
    favorites: Set<String>,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }

    val isFavorite = current?.uri?.toString() in favorites

    LaunchedEffect(current, player) {
        while (true) {
            if (!dragging) {
                position = player.currentPosition.coerceAtLeast(0L)
            }
            duration = player.duration.takeIf {
                it > 0L && it != androidx.media3.common.C.TIME_UNSET
            } ?: (current?.duration ?: 0L)
            delay(400)
        }
    }

    val repeatLabel = when (repeatMode) {
        Player.REPEAT_MODE_ONE -> "Repeat one"
        Player.REPEAT_MODE_ALL -> "Repeat all"
        else -> "Repeat off"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF351010), BG, BG)
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 22.dp)
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("⌄", color = WHITE, fontSize = 30.sp)
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .background(CARD, RoundedCornerShape(24.dp))
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { showLyrics = false },
                    modifier = Modifier.background(
                        if (!showLyrics) RED else Color.Transparent,
                        RoundedCornerShape(20.dp)
                    )
                ) {
                    Text(
                        "Cover",
                        color = WHITE,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                TextButton(
                    onClick = { showLyrics = true },
                    modifier = Modifier.background(
                        if (showLyrics) RED else Color.Transparent,
                        RoundedCornerShape(20.dp)
                    )
                ) {
                    Text(
                        "Lyrics",
                        color = WHITE,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Box {
                TextButton(onClick = { menuExpanded = true }) {
                    Text("⋮", color = WHITE, fontSize = 28.sp)
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(CARD)
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (isFavorite) "Remove favourite"
                                else "Add to favourites",
                                color = WHITE
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onToggleFavorite()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to playlist", color = WHITE) },
                        onClick = {
                            menuExpanded = false
                            onAddToPlaylist()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (shuffleEnabled) "Turn shuffle off"
                                else "Turn shuffle on",
                                color = WHITE
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onToggleShuffle()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(repeatLabel, color = WHITE) },
                        onClick = {
                            menuExpanded = false
                            onCycleRepeat()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Song details", color = WHITE) },
                        onClick = {
                            menuExpanded = false
                            showDetails = true
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Artwork / lyrics area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (showLyrics) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CARD, RoundedCornerShape(24.dp))
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "LYRICS",
                        color = RED,
                        fontSize = 12.sp,
                        letterSpacing = 3.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        current?.title ?: "Nothing playing",
                        color = WHITE,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Lyrics aren't available in this version yet.",
                        color = GREY,
                        fontSize = 14.sp
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        Color(0xFF511919),
                                        Color(0xFF1A1010),
                                        Color(0xFF331010)
                                    )
                                ),
                                RoundedCornerShape(26.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        current?.let {
                            SongArtwork(
                                it,
                                Modifier.fillMaxSize(),
                                26.dp
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        // Song title, artist and favourite
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    current?.title ?: "Nothing playing",
                    color = WHITE,
                    fontWeight = FontWeight.Bold,
                    fontSize = 23.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    current?.artist ?: "Choose a song from your library",
                    color = GREY,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            TextButton(onClick = onToggleFavorite) {
                Text(
                    if (isFavorite) "♥" else "♡",
                    color = RED,
                    fontSize = 30.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Seek bar and timestamps
        val safeDuration = duration.coerceAtLeast(1L)

        Slider(
            value = if (dragging) seekPosition
            else position.coerceIn(0L, safeDuration).toFloat(),
            onValueChange = {
                dragging = true
                seekPosition = it
            },
            onValueChangeFinished = {
                player.seekTo(seekPosition.toLong())
                position = seekPosition.toLong()
                dragging = false
            },
            valueRange = 0f..safeDuration.toFloat(),
            enabled = current != null,
            colors = SliderDefaults.colors(
                thumbColor = RED,
                activeTrackColor = RED,
                inactiveTrackColor = Color(0xFF555555)
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                formatDuration(
                    if (dragging) seekPosition.toLong() else position
                ),
                color = GREY,
                fontSize = 12.sp
            )
            Text(
                formatDuration(duration),
                color = GREY,
                fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(16.dp))

        // Playback controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onToggleShuffle) {
                Text(
                    "⇄",
                    color = if (shuffleEnabled) RED else GREY,
                    fontSize = 22.sp
                )
            }

            TextButton(onClick = {
                player.seekToPreviousMediaItem()
            }) {
                Text("⏮", color = WHITE, fontSize = 27.sp)
            }

            Box(
                modifier = Modifier
                    .size(70.dp)
                    .background(
                        Color.Transparent,
                        RoundedCornerShape(50)
                    ),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Color.Transparent,
                            RoundedCornerShape(50)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    TextButton(
                        onClick = {
                            if (playing) player.pause() else player.play()
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                RED,
                                RoundedCornerShape(50)
                            )
                    ) {
                        Text(
                            if (playing) "Ⅱ" else "▶",
                            color = WHITE,
                            fontSize = 28.sp
                        )
                    }
                }
            }

            TextButton(onClick = {
                player.seekToNextMediaItem()
            }) {
                Text("⏭", color = WHITE, fontSize = 27.sp)
            }

            TextButton(onClick = onCycleRepeat) {
                Text(
                    if (repeatMode == Player.REPEAT_MODE_ONE) "↻¹" else "↻",
                    color = if (repeatMode == Player.REPEAT_MODE_OFF)
                        GREY else RED,
                    fontSize = 22.sp
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "$repeatLabel   ·   ${if (shuffleEnabled) "Shuffle on" else "Shuffle off"}",
            modifier = Modifier.fillMaxWidth(),
            color = GREY,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(10.dp))
    }

    if (showDetails && current != null) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            containerColor = CARD,
            title = { Text("Song details", color = WHITE) },
            text = {
                Column {
                    Text("Title: ${current.title}", color = WHITE)
                    Spacer(Modifier.height(8.dp))
                    Text("Artist: ${current.artist}", color = WHITE)
                    Spacer(Modifier.height(8.dp))
                    Text("Type: ${current.mimeType}", color = GREY)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Duration: ${formatDuration(current.duration)}",
                        color = GREY
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetails = false }) {
                    Text("Close", color = RED)
                }
            }
        )
    }
}

