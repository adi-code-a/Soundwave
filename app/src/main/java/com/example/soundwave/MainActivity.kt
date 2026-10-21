
package com.example.soundwave

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

private fun prefs(context: Context) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

private fun loadFavorites(context: Context): Set<String> =
    prefs(context).getStringSet("favorites", emptySet())
        ?.toSet() ?: emptySet()

private fun saveFavorites(context: Context, values: Set<String>) {
    prefs(context).edit()
        .putStringSet("favorites", values.toSet()).apply()
}

private fun loadPlaylists(
    context: Context
): Map<String, List<String>> {
    val raw = prefs(context).getString("playlists", null)
        ?: return emptyMap()

    return try {
        val json = JSONObject(raw)
        val result = mutableMapOf<String, List<String>>()
        val keys = json.keys()

        while (keys.hasNext()) {
            val key = keys.next()
            val array = json.getJSONArray(key)
            result[key] = List(array.length()) { array.getString(it) }
        }
        result
    } catch (_: Exception) {
        emptyMap()
    }
}

private fun savePlaylists(
    context: Context,
    values: Map<String, List<String>>
) {
    val json = JSONObject()
    values.forEach { (name, uris) ->
        json.put(name, JSONArray(uris))
    }
    prefs(context).edit().putString("playlists", json.toString()).apply()
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
            val idCol = cursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media._ID
            )
            val titleCol = cursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.TITLE
            )
            val artistCol = cursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.ARTIST
            )

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                val title = cursor.getString(titleCol) ?: "Unknown song"
                val artist = cursor.getString(artistCol)
                    ?.takeUnless { it == "<unknown>" }
                    ?: "Unknown artist"

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
    var status by remember { mutableStateOf("Preparing library…") }
    var tab by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("Name") }
    var sortMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var songToAdd by remember { mutableStateOf<Song?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (!granted) {
            status = "Music access denied. Allow it in app settings."
        }
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            permissionLauncher.launch(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            status = "Scanning your music…"
            try {
                songs = scanMusic(context)
                status = if (songs.isEmpty()) {
                    "No audio found. Try refreshing after adding music."
                } else {
                    "${songs.size} songs in your library"
                }
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

    val artists = songs.map { it.artist }.distinct().sorted()

    val visibleSongs = when (tab) {
        1 -> songs.filter { it.uri.toString() in favorites }
        3 -> songs.filter { it.artist.contains(search, true) }
        else -> songs.filter {
            it.title.contains(search, true) ||
                it.artist.contains(search, true)
        }
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
                        Column(
                            Modifier.fillMaxWidth()
                                .background(Color(0xFF242020))
                                .clickable { player.play() }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(42.dp)
                                        .background(
                                            Color(0xFF391414),
                                            RoundedCornerShape(10.dp)
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
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        song.title,
                                        color = WHITE,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                    Text(
                                        song.artist,
                                        color = GREY,
                                        fontSize = 12.sp,
                                        maxLines = 1
                                    )
                                }
                                IconButton(onClick = {
                                    if (playing) player.pause()
                                    else player.play()
                                }) {
                                    AnimatedContent(
                                        targetState = playing,
                                        label = "play-pause"
                                    ) { isPlaying ->
                                        Icon(
                                            if (isPlaying) Icons.Default.Pause
                                            else Icons.Default.PlayArrow,
                                            contentDescription = "Play or pause",
                                            tint = RED
                                        )
                                    }
                                }
                            }
                            val progress by remember {
                                derivedStateOf {
                                    if (player.duration > 0)
                                        player.currentPosition.toFloat() /
                                            player.duration
                                    else 0f
                                }
                            }
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                                    .padding(top = 6.dp)
                                    .height(2.dp),
                                color = RED,
                                trackColor = Color(0xFF494040)
                            )
                        }
                    }

                    NavigationBar(containerColor = Color(0xFF111111)) {
                        val labels = listOf(
                            "Songs", "Favourites", "Playlists", "Artists"
                        )
                        val icons = listOf(
                            Icons.Default.MusicNote,
                            Icons.Default.Favorite,
                            Icons.Default.List,
                            Icons.Default.Person
                        )

                        labels.forEachIndexed { index, label ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = {
                                    Icon(icons[index], contentDescription = label)
                                },
                                label = {
                                    Text(label, fontSize = 9.sp, maxLines = 1)
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
                Modifier.fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 14.dp)
            ) {
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search your music") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null
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

                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOf(
                            "Your music", "Your favourites",
                            "Your playlists", "Artists"
                        )[tab],
                        color = WHITE,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )

                    if (tab == 0 || tab == 3) {
                        Box {
                            TextButton(onClick = { sortMenu = true }) {
                                Text(sort, color = RED, fontSize = 12.sp)
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
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Create playlist",
                                tint = RED
                            )
                        }
                    }

                    IconButton(onClick = {
                        if (permissionGranted) {
                            status = "Rescanning…"
                        } else {
                            permissionLauncher.launch(
                                Manifest.permission.READ_EXTERNAL_STORAGE
                            )
                        }
                    }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh library",
                            tint = RED
                        )
                    }
                }

                Text(status, color = GREY, fontSize = 11.sp)

                Spacer(Modifier.height(6.dp))

                when {
                    !permissionGranted -> {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = RED,
                                    modifier = Modifier.size(56.dp)
                                )
                                Text(
                                    "Let Soundwave access your music",
                                    color = WHITE,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(8.dp))
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
                                    Text("Grant music access")
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
                                    val playlistSongs = playlists[name].orEmpty()
                                        .mapNotNull { uri ->
                                            songs.find {
                                                it.uri.toString() == uri
                                            }
                                        }

                                    Column(
                                        Modifier.fillMaxWidth()
                                            .background(
                                                CARD,
                                                RoundedCornerShape(14.dp)
                                            )
                                            .animateContentSize()
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            name,
                                            color = WHITE,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "${playlists[name]?.size ?: 0} saved tracks",
                                            color = GREY,
                                            fontSize = 12.sp
                                        )

                                        playlistSongs.forEach { song ->
                                            Row(
                                                Modifier.fillMaxWidth()
                                                    .clickable {
                                                        playSong(song)
                                                    }
                                                    .padding(vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    song.title,
                                                    color = WHITE,
                                                    modifier = Modifier.weight(1f),
                                                    maxLines = 1
                                                )
                                                IconButton(onClick = {
                                                    playlists = playlists +
                                                        (name to playlists[name]
                                                            .orEmpty()
                                                            .filterNot {
                                                                it == song.uri.toString()
                                                            })
                                                }) {
                                                    Icon(
                                                        Icons.Default.Close,
                                                        contentDescription = "Remove from playlist",
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

                    tab == 3 && search.isBlank() -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(artists.filter {
                                it.contains(search, true)
                            }) { artist ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(
                                            CARD,
                                            RoundedCornerShape(12.dp)
                                        )
                                        .clickable { search = artist }
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = null,
                                        tint = RED
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(artist, color = WHITE)
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
                                    contentDescription = null,
                                    tint = RED,
                                    modifier = Modifier.size(54.dp)
                                )
                                Text(
                                    if (tab == 1) "No favourites yet"
                                    else "Your music will appear here",
                                    color = WHITE,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "Songs are scanned from your device",
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
                            items(
                                visibleSongs,
                                key = { it.uri.toString() }
                            ) { song ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(
                                            CARD,
                                            RoundedCornerShape(13.dp)
                                        )
                                        .clickable { playSong(song) }
                                        .padding(9.dp)
                                        .animateContentSize(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier.size(46.dp)
                                            .background(
                                                Color(0xFF351515),
                                                RoundedCornerShape(10.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.MusicNote,
                                            contentDescription = null,
                                            tint = RED,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }

                                    Spacer(Modifier.width(10.dp))

                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            song.title,
                                            color = WHITE,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            song.artist,
                                            color = GREY,
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                    }

                                    IconButton(onClick = {
                                        val uri = song.uri.toString()
                                        favorites = if (uri in favorites) {
                                            favorites - uri
                                        } else {
                                            favorites + uri
                                        }
                                    }) {
                                        AnimatedContent(
                                            targetState =
                                                song.uri.toString() in favorites,
                                            label = "favourite"
                                        ) { isFavorite ->
                                            Icon(
                                                if (isFavorite)
                                                    Icons.Default.Favorite
                                                else
                                                    Icons.Default.FavoriteBorder,
                                                contentDescription = "Toggle favourite",
                                                tint = RED
                                            )
                                        }
                                    }

                                    IconButton(onClick = {
                                        songToAdd = song
                                    }) {
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = "Add to playlist",
                                            tint = GREY
                                        )
                                    }

                                    IconButton(onClick = { playSong(song) }) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = "Play",
                                            tint = WHITE
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
                title = { Text("New playlist") },
                text = {
                    OutlinedTextField(
                        value = playlistName,
                        onValueChange = { playlistName = it },
                        singleLine = true,
                        label = { Text("Playlist name") }
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
                        Text("Create a playlist first in the Playlists tab.")
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
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Theme: Black & Red")
                        Text("Library: Automatic device scan")
                        Text("Favourites and playlists: Saved locally")
                        Text("Music found: ${songs.size}")
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showSettings = false
                    }) {
                        Text("Done", color = RED)
                    }
                }
            )
        }
    }
}
