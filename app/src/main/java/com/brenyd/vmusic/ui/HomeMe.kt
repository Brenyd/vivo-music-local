package com.brenyd.vmusic.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brenyd.vmusic.BuildConfig
import com.brenyd.vmusic.data.Library
import com.brenyd.vmusic.data.Matcher
import com.brenyd.vmusic.playback.Ids
import com.brenyd.vmusic.playback.PlayerConn
import kotlinx.coroutines.launch

@Composable
fun HomeScreen() {
    val songs by Library.songs.collectAsStateWithLifecycle()
    val albums by Library.albums.collectAsStateWithLifecycle()
    val favs by Library.favorites.collectAsStateWithLifecycle()
    val recents by Library.recents.collectAsStateWithLifecycle()
    val lists by Library.playlists.collectAsStateWithLifecycle()
    val recentSongs = remember(recents, songs) { Library.songsFor(recents.take(10)) }
    val newAlbums = remember(albums) { albums.sortedByDescending { a -> a.songs.maxOf { it.dateAdded } }.take(12) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("Home", color = VColors.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 12.dp))
        }
        item {
            // big shuffle card
            Box(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(110.dp).clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFFF7A6E), Color(0xFFE8384F))))
                    .clickable { PlayerConn.shuffleAll(songs) }.padding(20.dp),
            ) {
                Column(Modifier.align(Alignment.CenterStart)) {
                    Text("Shuffle all", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("${plural(songs.size, "song")} on this phone", color = Color(0xE6FFFFFF), fontSize = 14.sp)
                }
                Icon(Icons.Rounded.Shuffle, null, tint = Color.White, modifier = Modifier.align(Alignment.CenterEnd).size(44.dp))
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickTile(Icons.Rounded.Favorite, "Favorites", "${favs.size}", Modifier.weight(1f)) { Ui.push(Dest.PlaylistPage(Ids.FAVORITES)) }
                QuickTile(Icons.Rounded.History, "Recent", "${recents.size.coerceAtMost(100)}", Modifier.weight(1f)) { Ui.push(Dest.PlaylistPage(Ids.RECENT)) }
                QuickTile(Icons.AutoMirrored.Rounded.TrendingUp, "Most played", "", Modifier.weight(1f)) { Ui.push(Dest.PlaylistPage(Ids.MOST)) }
            }
        }
        if (newAlbums.isNotEmpty()) {
            item { SectionTitle("Recently added") }
            item {
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(newAlbums, key = { it.key }) { a ->
                        Column(Modifier.width(120.dp).clickable { Ui.push(Dest.AlbumPage(a.key)) }) {
                            ArtImage(a.artPath, 120.dp, shape = RoundedCornerShape(14.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(a.name, color = VColors.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(a.artist, color = VColors.Text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (lists.isNotEmpty()) {
            item { SectionTitle("My playlists") }
            items(lists, key = { it.id }) { p ->
                val first = remember(p, songs) { Library.songsFor(p.paths.take(1)).firstOrNull() }
                EntityRow(p.name, plural(p.paths.size, "song"), first?.artPath, placeholder = Icons.AutoMirrored.Rounded.QueueMusic) {
                    Ui.push(Dest.PlaylistPage("pl:" + p.id))
                }
            }
        }
        if (recentSongs.isNotEmpty()) {
            item { SectionTitle("Recently played") }
            items(recentSongs, key = { "r" + it.path }) { s ->
                SongRow(s, onClick = { PlayerConn.play(recentSongs, recentSongs.indexOf(s), Ids.RECENT) }, onMore = { Ui.more(s) })
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun QuickTile(icon: ImageVector, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(VColors.Sheet).clickable(onClick = onClick).padding(14.dp),
    ) {
        Icon(icon, null, tint = VColors.Accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, color = VColors.Text, fontSize = 14.sp, maxLines = 1)
        Text(sub, color = VColors.Text2, fontSize = 12.sp)
    }
}

@Composable
fun MeScreen() {
    val songs by Library.songs.collectAsStateWithLifecycle()
    val albums by Library.albums.collectAsStateWithLifecycle()
    val artists by Library.artists.collectAsStateWithLifecycle()
    val lists by Library.playlists.collectAsStateWithLifecycle()
    val matching by Matcher.running.collectAsStateWithLifecycle()
    val status by Matcher.status.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var autoMatch by remember { mutableStateOf(Library.autoMatch) }
    var autoLyrics by remember { mutableStateOf(Library.autoLyrics) }
    var wifiOnly by remember { mutableStateOf(Library.wifiOnly) }
    var skipShort by remember { mutableStateOf(Library.minDurationSec > 0) }
    val matchedCount = songs.count { it.matched }
    val withArt = songs.count { it.artPath != null }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("Me", color = VColors.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 12.dp))
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(VColors.Sheet).padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat("${songs.size}", "Songs"); Stat("${albums.size}", "Albums"); Stat("${artists.size}", "Artists")
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("My playlists", Modifier.weight(1f))
                Icon(Icons.Rounded.Add, "New playlist", tint = VColors.Text, modifier = Modifier.padding(top = 10.dp, end = 8.dp)
                    .size(30.dp).clickable { creating = true })
            }
        }
        if (lists.isEmpty()) item {
            Text("No playlists yet. Tap + to create one.", color = VColors.Text3, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
        items(lists, key = { it.id }) { p ->
            val first = remember(p, songs) { Library.songsFor(p.paths.take(1)).firstOrNull() }
            EntityRow(p.name, plural(p.paths.size, "song"), first?.artPath, placeholder = Icons.AutoMirrored.Rounded.QueueMusic) {
                Ui.push(Dest.PlaylistPage("pl:" + p.id))
            }
        }
        item { SectionTitle("Song info & lyrics") }
        item {
            SettingSwitch("Match covers and song info automatically",
                "Finds missing covers, artists, albums and artist photos online (Deezer, iTunes). Your files aren't changed.", autoMatch) {
                autoMatch = it; Library.autoMatch = it; if (it) Matcher.kick()
            }
        }
        item {
            SettingSwitch("Get lyrics automatically", "Synced lyrics from LRCLIB when a song plays", autoLyrics) {
                autoLyrics = it; Library.autoLyrics = it
            }
        }
        item {
            SettingSwitch("Only on Wi-Fi", "Don't use mobile data to look things up", wifiOnly) { wifiOnly = it; Library.wifiOnly = it }
        }
        item {
            SettingRow(Icons.Rounded.AutoAwesome, if (matching) "Matching…" else "Match all songs now",
                if (matching) status else "$withArt of ${songs.size} have covers · $matchedCount matched online") {
                if (!matching) { Matcher.kick(force = true); Ui.toast = "Matching song info…" }
            }
        }
        item { SectionTitle("Library") }
        item {
            SettingSwitch("Hide short audio", "Skip audio shorter than 30 seconds (ringtones, voice notes)", skipShort) {
                skipShort = it; Library.minDurationSec = if (it) 30 else 0
                Library.scope.launch { Library.scan() }
            }
        }
        item {
            SettingRow(Icons.Rounded.Refresh, "Scan for music", "Look for new or removed songs") {
                Library.scope.launch { Library.scan() }; Ui.toast = "Scanning for music…"
            }
        }
        item {
            Text("Music ${BuildConfig.VERSION_NAME} · local music player inspired by vivo Music",
                color = VColors.Text3, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (creating) TextPrompt("New playlist", "", onDismiss = { creating = false }) {
        Library.createPlaylist(it); creating = false
    }
}

@Composable
private fun Stat(n: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(n, color = VColors.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = VColors.Text2, fontSize = 12.sp)
    }
}

@Composable
fun SettingSwitch(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = VColors.Text, fontSize = 16.sp)
            if (sub != null) Text(sub, color = VColors.Text2, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = VColors.Accent, checkedThumbColor = Color.White))
    }
}

@Composable
fun SettingRow(icon: ImageVector, title: String, sub: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = VColors.Accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = VColors.Text, fontSize = 16.sp)
            if (!sub.isNullOrEmpty()) Text(sub, color = VColors.Text2, fontSize = 12.sp)
        }
    }
}
