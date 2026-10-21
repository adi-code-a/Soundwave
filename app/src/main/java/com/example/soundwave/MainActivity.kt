
package com.example.soundwave
import android.os.Build
import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
    val artist: String
)

private const val PREFS = "soundwave_storage"
private val BG = Color(0xFF0A0A0A)
private val CARD = Color(0xFF191919)
private val RED = Color(0xFFFF3030)
private val WHITE = Color(0xFFF5F5F5)
private val GREY = Color(0xFFAAAAAA)

private fun loadFavorites(context: Context): Set<String> =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet("favorites", emptySet())?.toSet() ?: emptySet()

private fun saveFavorites(context: Context, values: Set<String>) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putStringSet("favorites", values.toSet()).apply()
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
        .edit().putString("playlists", json.toString()).apply()
}

private suspend fun scanMusic(context: Context): List<Song> =
    withContext(Dispatchers.IO) {
        val result = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
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

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(collection, id)
                val title = cursor.getString(titleColumn) ?: "Unknown song"
                val artist = cursor.getString(artistColumn)
                    ?.takeUnless { it == "<unknown>" } ?: "Unknown artist"
                result.add(Song(uri, title, artist))
            }
        }
        result
    }

class MainActivity : ComponentActivity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        setContent { SoundwaveScreen(player!!) }
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

    var songs by remember { mutableStateOf(emptyList<Song>()) }
    var favorites by remember { mutableStateOf(loadFavorites(context)) }
    var playlists by remember { mutableStateOf(loadPlaylists(context)) }
    var current by remember { mutableStateOf<Song?>(null) }
    var playing by remember { mutableStateOf(false) }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
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
    var playlistName by remember { mutableStateOf("") }
    var songToAdd by remember { mutableStateOf<Song?>(null) }
    var selectedArtist by remember { mutableStateOf<String?>(null) }
    var refreshCounter by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (!granted) status = "Music access denied. Allow access in Settings."
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
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

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    fun playSong(song: Song) {
        current = song
        player.setMediaItem(MediaItem.fromUri(song.uri))
        player.prepare()
        player.play()
    }

    val artists = songs.map { it.artist }.distinct()
        .filter { it.contains(search, true) }.sorted()

    val visibleSongs = when (tab) {
        1 -> songs.filter { it.uri.toString() in favorites }
        3 -> songs.filter { it.artist == selectedArtist }
        else -> songs
    }.filter {
        it.title.contains(search, true) || it.artist.contains(search, true)
    }.let { list ->
        when (sort) {
            "Artist" -> list.sortedBy { it.artist.lowercase() }
            else -> list.sortedBy { it.title.lowercase() }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = RED,
            background = BG,
            surface = CARD
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
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, "Settings", tint = WHITE)
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
                        Column(
                            Modifier.fillMaxWidth()
                                .background(Color(0xFF241818))
                                .clickable { player.play() }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(42.dp)
                                        .background(
                                            Color(0xFF401515),
                                            RoundedCornerShape(10.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.MusicNote,
                                        null,
                                        tint = RED
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        song.title,
                                        color = WHITE,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                    Text(song.artist, color = GREY, fontSize = 12.sp)
                                }
                                IconButton(onClick = {
                                    if (playing) player.pause() else player.play()
                                }) {
                                    AnimatedContent(
                                        targetState = playing,
                                        label = "play pause"
                                    ) { isPlaying ->
                                        Icon(
                                            if (isPlaying) Icons.Default.Pause
                                            else Icons.Default.PlayArrow,
                                            "Play or pause",
                                            tint = RED
                                        )
                                    }
                                }
                            }
                        }
                    }

                    NavigationBar(containerColor = Color(0xFF111111)) {
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
                                },
                                icon = { Icon(icons[index], label) },
                                label = { Text(label, fontSize = 9.sp, maxLines = 1) },
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
                Modifier.fillMaxSize().padding(padding)
                    .padding(horizontal = 12.dp)
            ) {
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search your music") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, null, tint = GREY)
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

                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (tab) {
                            1 -> "Your favourites"
                            2 -> "Your playlists"
                            3 -> selectedArtist ?: "Artists"
                            else -> "Your music"
                        },
                        color = WHITE,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )

                    if (tab == 0 || tab == 3 && selectedArtist != null) {
                        Box {
                            TextButton(onClick = { sortMenu = true }) {
                                Text(sort, color = RED, fontSize = 12.sp)
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    null,
                                    tint = RED
                                )
                            }
                            DropdownMenu(
                                expanded = sortMenu,
                                onDismissRequest = { sortMenu = false }
                            ) {
                                listOf("Name", "Artist").forEach { option ->
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

                    if (tab == 2) {
                        IconButton(onClick = {
                            playlistName = ""
                            showCreate = true
                        }) {
                            Icon(Icons.Default.Add, "Create playlist", tint = RED)
                        }
                    }

                    IconButton(onClick = {
                        if (permissionGranted) refreshCounter++
                        else permissionLauncher.launch(
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        )
                    }) {
                        Icon(Icons.Default.Refresh, "Refresh library", tint = RED)
                    }
                }

                Text(status, color = GREY, fontSize = 11.sp)
                Spacer(Modifier.height(5.dp))

                when {
                    !permissionGranted -> {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.LibraryMusic,
                                    null,
                                    tint = RED,
                                    modifier = Modifier.size(54.dp)
                                )
                                Text("Allow Soundwave to access your music",
                                    color = WHITE)
                                Button(
                                    onClick = {
                                        permissionLauncher.launch(
                                            Manifest.permission.READ_EXTERNAL_STORAGE
                                        )
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

                    tab == 2 -> {
                        if (playlists.isEmpty()) {
                            Box(
                                Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Create your first playlist with +",
                                    color = GREY
                                )
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(playlists.keys.toList()) { name ->
                                    Column(
                                        Modifier.fillMaxWidth()
                                            .background(CARD, RoundedCornerShape(13.dp))
                                            .animateContentSize()
                                            .padding(12.dp)
                                    ) {
                                        Text(name, color = WHITE,
                                            fontWeight = FontWeight.Bold)
                                        Text(
                                            "${playlists[name]?.size ?: 0} saved songs",
                                            color = GREY,
                                            fontSize = 12.sp
                                        )
                                        playlists[name].orEmpty().forEach { uri ->
                                            val song = songs.find {
                                                it.uri.toString() == uri
                                            }
                                            Row(
                                                Modifier.fillMaxWidth()
                                                    .padding(top = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    song?.title ?: "Unavailable song",
                                                    color = WHITE,
                                                    modifier = Modifier.weight(1f),
                                                    maxLines = 1
                                                )
                                                if (song != null) {
                                                    IconButton(onClick = {
                                                        playSong(song)
                                                    }) {
                                                        Icon(Icons.Default.PlayArrow,
                                                            "Play", tint = RED)
                                                    }
                                                }
                                                IconButton(onClick = {
                                                    playlists = playlists +
                                                        (name to playlists[name]
                                                            .orEmpty().filterNot { it == uri })
                                                }) {
                                                    Icon(Icons.Default.Close,
                                                        "Remove song", tint = GREY)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    tab == 3 && selectedArtist == null -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            items(artists) { artist ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(CARD, RoundedCornerShape(13.dp))
                                        .clickable {
                                            selectedArtist = artist
                                            search = ""
                                        }
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier.size(42.dp)
                                            .background(
                                                Color(0xFF351515),
                                                RoundedCornerShape(50)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Person, null, tint = RED)
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(artist, color = WHITE,
                                            fontWeight = FontWeight.Medium)
                                        Text(
                                            "${songs.count { it.artist == artist }} songs",
                                            color = GREY,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    visibleSongs.isEmpty() -> {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.MusicNote,
                                    null,
                                    tint = RED,
                                    modifier = Modifier.size(52.dp)
                                )
                                Text(
                                    if (tab == 1) "No favourites yet"
                                    else "No songs found",
                                    color = WHITE,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "Music is scanned from your device",
                                    color = GREY,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    else -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            items(visibleSongs, key = { it.uri.toString() }) { song ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(CARD, RoundedCornerShape(13.dp))
                                        .clickable { playSong(song) }
                                        .animateContentSize()
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier.size(45.dp)
                                            .background(
                                                Color(0xFF351515),
                                                RoundedCornerShape(10.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.MusicNote, null,
                                            tint = RED, modifier = Modifier.size(25.dp))
                                    }
                                    Spacer(Modifier.width(9.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(song.title, color = WHITE,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1)
                                        Spacer(Modifier.height(3.dp))
                                        Text(song.artist, color = GREY,
                                            fontSize = 11.sp, maxLines = 1)
                                    }
                                    IconButton(onClick = {
                                        val uri = song.uri.toString()
                                        favorites = if (uri in favorites)
                                            favorites - uri else favorites + uri
                                    }) {
                                        AnimatedContent(
                                            targetState = song.uri.toString() in favorites,
                                            label = "favorite"
                                        ) { favorite ->
                                            Icon(
                                                if (favorite) Icons.Default.Favorite
                                                else Icons.Default.FavoriteBorder,
                                                "Toggle favourite",
                                                tint = RED
                                            )
                                        }
                                    }
                                    IconButton(onClick = { songToAdd = song }) {
                                        Icon(Icons.Default.Add, "Add to playlist",
                                            tint = GREY)
                                    }
                                    IconButton(onClick = { playSong(song) }) {
                                        Icon(Icons.Default.PlayArrow, "Play",
                                            tint = WHITE)
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
                    TextButton(onClick = {
                        val name = playlistName.trim()
                        if (name.isNotEmpty() && name !in playlists) {
                            playlists = playlists + (name to emptyList())
                        }
                        showCreate = false
                    }) {
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

        songToAdd?.let { song ->
            AlertDialog(
                onDismissRequest = { songToAdd = null },
                title = { Text("Add to playlist") },
                text = {
                    if (playlists.isEmpty()) {
                        Text("Create a playlist first.")
                    } else {
                        Column {
                            playlists.keys.forEach { name ->
                                Text(
                                    name,
                                    color = WHITE,
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable {
                                            val uri = song.uri.toString()
                                            val old = playlists[name].orEmpty()
                                            if (uri !in old) {
                                                playlists = playlists +
                                                    (name to (old + uri))
                                            }
                                            songToAdd = null
                                        }
                                        .padding(12.dp)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { songToAdd = null }) {
                        Text("Close")
                    }
                }
            )
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("Soundwave Settings") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Theme: Black & Red")
                        Text("Library: Automatic device scan")
                        Text("Favourites and playlists: Saved locally")
                        Text("Songs found: ${songs.size}")
                        Text("Sort order: $sort")
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
