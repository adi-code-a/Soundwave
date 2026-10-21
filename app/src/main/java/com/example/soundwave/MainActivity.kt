package com.example.soundwave

import android.net.Uri
import android.os.Bundle
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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
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

private data class Song(val uri: Uri, val title: String)

class MainActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        setContent { SoundwaveStartup(player!!) }
    }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundwaveScreen(player: ExoPlayer) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var songs by remember { mutableStateOf(listOf<Song>()) }
    var current by remember { mutableStateOf<Song?>(null) }
    var playing by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val added = uris.map { uri ->
            try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "Local track"
            Song(uri, name.substringAfterLast('/').substringAfterLast(':').ifBlank { "Local track" })
        }
        songs = (songs + added).distinctBy { it.uri }
    }
    val bg = Color(0xFF101010); val green = Color(0xFF1ED760)
    MaterialTheme(colorScheme = darkColorScheme(primary = green, background = bg, surface = Color(0xFF1B1B1B))) {
        Scaffold(containerColor = bg, topBar = {
            TopAppBar(title = { Column { Text("Soundwave", fontWeight = FontWeight.Bold, fontSize = 26.sp); Text("Your music. Your way.", color = Color.LightGray, fontSize = 13.sp) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = bg, titleContentColor = Color.White))
        }, bottomBar = {
            current?.let { song ->
                Row(Modifier.fillMaxWidth().background(Color(0xFF222222)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(song.title, color = Color.White, maxLines = 1, fontWeight = FontWeight.SemiBold); Text("Local audio", color = Color.LightGray, fontSize = 12.sp) }
                    IconButton(onClick = { if (playing) player.pause() else player.play(); playing = !playing }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play or pause", tint = green) }
                }
            }
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().background(Color(0xFF1D1D1D), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Color.LightGray)
                    TextField(value = search, onValueChange = { search = it }, placeholder = { Text("Search your library") }, singleLine = true, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent), modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(18.dp))
                Text("Your Library", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { picker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = green, contentColor = Color.Black), shape = RoundedCornerShape(24.dp)) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Add local music", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                if (songs.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("♫", color = green, fontSize = 52.sp); Text("Your music starts here", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text("Tap Add local music to choose audio files", color = Color.LightGray, fontSize = 13.sp) } }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                        items(songs.filter { it.title.contains(search, ignoreCase = true) }) { song ->
                            Row(Modifier.fillMaxWidth().background(Color(0xFF1B1B1B), RoundedCornerShape(12.dp)).clickable {
                                current = song; player.setMediaItem(MediaItem.fromUri(song.uri)); player.prepare(); player.play(); playing = true
                            }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(46.dp).background(Color(0xFF303030), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Text("♫", color = green, fontSize = 24.sp) }
                                Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(song.title, color = Color.White, maxLines = 1, fontWeight = FontWeight.Medium); Text("Local audio", color = Color.LightGray, fontSize = 12.sp) }
                                IconButton(onClick = { current = song; player.setMediaItem(MediaItem.fromUri(song.uri)); player.prepare(); player.play(); playing = true }) { Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = green) }
                            }
                        }
                    }
                }
            }
        }
    }
}
