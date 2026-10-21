package com.example.soundwave

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
fun SoundwaveSplash() {
    val transition = rememberInfiniteTransition(label = "logo")
    val scale = transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101010)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(190.dp)
                .scale(scale.value)
                .background(Color(0xFF1ED760).copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.soundwave_icon_1024),
                contentDescription = "Soundwave logo",
                modifier = Modifier.size(150.dp)
            )
        }
    }
}

@Composable
fun SoundwaveStartup(player: androidx.media3.exoplayer.ExoPlayer) {
    var showSplash by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1500)
        showSplash = false
    }

    if (showSplash) {
        SoundwaveSplash()
    } else {
        SoundwaveScreen(player)
    }
}
