package com.brenyd.vmusic.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.brenyd.vmusic.data.Library
import com.brenyd.vmusic.playback.PlayerConn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }

    private var hasPermission by mutableStateOf(false)

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermission()
    }

    private fun audioPermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun refreshPermission() {
        val ok = ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED
        val was = hasPermission
        hasPermission = ok
        if (ok && (!was || !Library.scannedOnce.value)) lifecycleScope.launch { Library.scan() }
    }

    fun requestPermissions() {
        val perms = mutableListOf(audioPermission())
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        askPermission.launch(perms.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        PlayerConn.connect(this)
        refreshPermission()
        if (!hasPermission) requestPermissions()
        handleIntent(intent)
        setContent {
            VTheme {
                if (hasPermission) AppRoot() else PermissionScreen { requestPermissions() }
                val toast = Ui.toast
                LaunchedEffect(toast) {
                    if (toast != null) {
                        Toast.makeText(this@MainActivity, toast, Toast.LENGTH_SHORT).show()
                        Ui.toast = null
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshPermission()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) Ui.playerOpen = true
        // "Open with" an audio file
        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data != null) {
            lifecycleScope.launch {
                Library.scannedOnce.first { it }
                val s = Library.songs.value.firstOrNull { it.uri == data || data.path?.endsWith(it.path) == true }
                if (s != null) { PlayerConn.play(listOf(s)); Ui.playerOpen = true }
            }
        }
    }
}

@Composable
fun PermissionScreen(onAsk: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(VColors.Bg).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.LibraryMusic, null, tint = VColors.Accent, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(20.dp))
        Text("Music needs access to your music files", color = VColors.Text, fontSize = 18.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Allow access to audio so your songs, albums and artists show up here.", color = VColors.Text2,
            fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAsk, colors = ButtonDefaults.buttonColors(containerColor = VColors.Accent)) { Text("Allow") }
    }
}

@Composable
fun AppRoot() {
    BackHandler(enabled = Ui.playerOpen || Ui.stack.isNotEmpty() || Ui.moreFor != null || Ui.infoFor != null ||
        Ui.queueOpen || Ui.sleepOpen || Ui.addToPlaylist != null) { Ui.back() }

    Box(Modifier.fillMaxSize().background(VColors.Bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).statusBarsPadding()) {
                val top = Ui.stack.lastOrNull()
                when (top) {
                    null -> when (Ui.tab) {
                        0 -> HomeScreen()
                        2 -> MeScreen()
                        else -> LocalScreen()
                    }
                    is Dest.Search -> SearchScreen()
                    is Dest.AlbumPage -> AlbumScreen(top.key)
                    is Dest.ArtistPage -> ArtistScreen(top.name)
                    is Dest.FolderPage -> FolderScreen(top.path)
                    is Dest.PlaylistPage -> PlaylistScreen(top.id)
                }
            }
            MiniPlayer()
            if (Ui.stack.isEmpty()) BottomBar() else Spacer(Modifier.navigationBarsPadding())
        }

        AnimatedVisibility(
            visible = Ui.playerOpen,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            NowPlayingScreen()
        }

        Sheets()
    }
}

@Composable
fun MiniPlayer() {
    val song by PlayerConn.current.collectAsStateWithLifecycle()
    val playing by PlayerConn.isPlaying.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().background(VColors.MiniBar).clickable(enabled = song != null) { Ui.playerOpen = true }
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(song?.artPath, 46.dp, shape = RoundedCornerShape(8.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(song?.title ?: "Music, let life shine", color = VColors.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (song != null) Text(song!!.artist, color = VColors.Text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { PlayerConn.toggle() }) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", tint = VColors.Text, modifier = Modifier.size(32.dp))
        }
        IconButton(onClick = { PlayerConn.next() }) {
            Icon(Icons.Rounded.SkipNext, "Next", tint = VColors.Text, modifier = Modifier.size(30.dp))
        }
        IconButton(onClick = { Ui.queueOpen = true }) {
            Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", tint = VColors.Text, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
fun BottomBar() {
    Row(
        Modifier.fillMaxWidth().background(VColors.Bg).navigationBarsPadding().padding(top = 6.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        NavItem(Icons.Rounded.Home, "Home", Ui.tab == 0) { Ui.tab = 0 }
        NavItem(Icons.Rounded.LibraryMusic, "Local", Ui.tab == 1) { Ui.tab = 1 }
        NavItem(Icons.Rounded.Person, "Me", Ui.tab == 2) { Ui.tab = 2 }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) VColors.Accent else Color(0xFF9A9A9A)
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = color, modifier = Modifier.size(28.dp))
        Text(label, color = color, fontSize = 12.sp)
    }
}
