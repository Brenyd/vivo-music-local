package com.brenyd.vmusic.ui

import android.app.Activity
import android.app.RecoverableSecurityException
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brenyd.vmusic.data.Library
import com.brenyd.vmusic.data.Lyrics
import com.brenyd.vmusic.data.Song
import com.brenyd.vmusic.playback.PlayerConn
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VSheet(onDismiss: () -> Unit, skipPartial: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartial),
        containerColor = VColors.Sheet,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = null,
    ) {
        Column(Modifier.navigationBarsPadding()) { content() }
    }
}

/** All bottom sheets, driven by [Ui]. */
@Composable
fun Sheets() {
    Ui.moreFor?.let { MoreSheet(it, Ui.moreContextPlaylist) }
    Ui.infoFor?.let { SongInfoSheet(it) }
    Ui.addToPlaylist?.let { AddToPlaylistSheet(it) }
    if (Ui.queueOpen) QueueSheet()
    if (Ui.sleepOpen) SleepSheet()
    Ui.deleteFor?.let { DeleteDialog(it) }
    if (Ui.hiddenOpen) HiddenSheet()
}

/** Permanently delete a song file (Android asks for confirmation on 11+). */
@Composable
private fun DeleteDialog(song: Song) {
    val ctx = LocalContext.current
    fun done() {
        PlayerConn.removePath(song.path)
        Library.removeDeleted(song.path)
        Ui.toast = "Deleted \"${song.title}\""
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            // Android 10 only grants access; the delete has to be repeated
            if (Build.VERSION.SDK_INT < 30) runCatching { ctx.contentResolver.delete(song.uri, null, null) }
            done()
        } else Ui.toast = "Not deleted"
        Ui.deleteFor = null
    }
    AlertDialog(
        onDismissRequest = { Ui.deleteFor = null },
        containerColor = VColors.Sheet,
        title = { Text("Delete song?", color = VColors.Text) },
        text = { Text("\"${song.title}\" will be permanently deleted from your phone.", color = VColors.Text2) },
        confirmButton = {
            TextButton(onClick = {
                try {
                    if (Build.VERSION.SDK_INT >= 30) {
                        val pi = MediaStore.createDeleteRequest(ctx.contentResolver, listOf(song.uri))
                        launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    } else {
                        val n = ctx.contentResolver.delete(song.uri, null, null)
                        if (n > 0) done() else Ui.toast = "Couldn't delete the song"
                        Ui.deleteFor = null
                    }
                } catch (e: Exception) {
                    if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException) {
                        launcher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                    } else {
                        Ui.toast = "Couldn't delete the song"
                        Ui.deleteFor = null
                    }
                }
            }) { Text("Delete", color = VColors.Accent) }
        },
        dismissButton = { TextButton(onClick = { Ui.deleteFor = null }) { Text("Cancel", color = VColors.Text2) } },
    )
}

@Composable
private fun HiddenSheet() {
    val hidden by Library.hiddenSongs.collectAsStateWithLifecycle()
    VSheet({ Ui.hiddenOpen = false }) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Hidden songs (${hidden.size})", color = VColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (hidden.isNotEmpty()) TextButton(onClick = { Library.unhide(hidden.map { it.path }) }) { Text("Show all", color = VColors.Accent) }
        }
        if (hidden.isEmpty()) Text("No hidden songs", color = VColors.Text2, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            itemsIndexed(hidden) { _, s ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ArtImage(s.artPath, 46.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, color = VColors.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.artist, color = VColors.Text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { Library.unhide(listOf(s.path)) }) { Text("Unhide", color = VColors.Accent) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun MoreSheet(song: Song, playlistId: String?) {
    val ctx = LocalContext.current
    val s = Library.songs.collectAsStateWithLifecycle().value.firstOrNull { it.path == song.path } ?: song
    val close = { Ui.moreFor = null }
    VSheet(close) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Song: ${s.title}", color = VColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (s.matched) "Song information matched online" else "${s.artist} - ${s.album}", color = VColors.Text2, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { close(); Ui.infoFor = s }) { Icon(Icons.Rounded.Info, "Song information", tint = VColors.Text) }
            IconButton(onClick = close) { Icon(Icons.Rounded.Close, "Close", tint = VColors.Text) }
        }
        val tiles = buildList<Triple<androidx.compose.ui.graphics.vector.ImageVector, String, () -> Unit>> {
            add(Triple(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") { close(); Ui.addToPlaylist = listOf(s) })
            add(Triple(Icons.Rounded.PlaylistPlay, "Play next") { close(); PlayerConn.playNext(s); Ui.toast = "Will play next" })
            add(Triple(Icons.Rounded.Timer, "Sleep timer") { close(); Ui.sleepOpen = true })
            add(Triple(Icons.Rounded.Person, "View artist") { close(); Ui.push(Dest.ArtistPage(Library.splitArtists(s.artist).first())) })
            add(Triple(Icons.Rounded.Album, "View album") {
                close()
                Library.albums.value.firstOrNull { a -> a.songs.any { it.path == s.path } }?.let { Ui.push(Dest.AlbumPage(it.key)) }
            })
            add(Triple(Icons.Rounded.Description, "Song information") { close(); Ui.infoFor = s })
            add(Triple(Icons.Rounded.Share, "Share") { close(); shareSong(ctx, s) })
            if (playlistId != null) add(Triple(Icons.Rounded.PlaylistRemove, "Remove from playlist") {
                close(); Library.removeFromPlaylist(playlistId, s.path)
            })
            add(Triple(Icons.Rounded.VisibilityOff, "Hide song") {
                close(); Library.hide(listOf(s.path)); Ui.toast = "Hidden. Find it in Me > Hidden songs"
            })
            add(Triple(Icons.Rounded.Delete, "Delete") { close(); Ui.deleteFor = s })
        }
        tiles.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                row.forEach { (icon, label, action) -> GridTile(icon, label, modifier = Modifier.weight(1f)) { action() } }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun AddToPlaylistSheet(songs: List<Song>) {
    val lists by Library.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    val close = { Ui.addToPlaylist = null }
    VSheet(close) {
        Text("Add to playlist", color = VColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(24.dp))
        EntityRow("New playlist", "", null, leading = {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(VColors.Tile), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, null, tint = VColors.Text)
            }
        }) { creating = true }
        EntityRow("Favorites", "", null, leading = {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(VColors.Tile), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Favorite, null, tint = VColors.Accent)
            }
        }) { Library.addFavorites(songs.map { it.path }); close(); Ui.toast = "Added to Favorites" }
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            itemsIndexed(lists) { _, p ->
                EntityRow(p.name, plural(p.paths.size, "song"), Library.songsFor(p.paths.take(1)).firstOrNull()?.artPath) {
                    Library.addToPlaylist(p.id, songs.map { it.path }); close(); Ui.toast = "Added to ${p.name}"
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    if (creating) TextPrompt("New playlist", "", onDismiss = { creating = false }) { name ->
        Library.createPlaylist(name, songs.map { it.path }); creating = false; close(); Ui.toast = "Added to $name"
    }
}

@Composable
private fun QueueSheet() {
    val queue by PlayerConn.queue.collectAsStateWithLifecycle()
    val idx by PlayerConn.queueIndex.collectAsStateWithLifecycle()
    val state = rememberLazyListState()
    LaunchedEffect(Unit) { if (idx > 2) state.scrollToItem(idx - 2) }
    VSheet({ Ui.queueOpen = false }) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Playing queue (${queue.size})", color = VColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            TextButton(onClick = { PlayerConn.clearQueue(); Ui.queueOpen = false }) { Text("Clear", color = VColors.Text2) }
        }
        LazyColumn(Modifier.heightIn(max = 520.dp), state = state) {
            itemsIndexed(queue) { i, s ->
                Row(Modifier.fillMaxWidth().clickable { PlayerConn.playAt(i) }.padding(start = 24.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, color = if (i == idx) VColors.Accent else VColors.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.artist, color = if (i == idx) VColors.Accent else VColors.Text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { PlayerConn.removeAt(i) }) { Icon(Icons.Rounded.Close, "Remove", tint = VColors.Text3) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SleepSheet() {
    val endsAt by PlayerConn.sleepEndsAt.collectAsStateWithLifecycle()
    val close = { Ui.sleepOpen = false }
    VSheet(close) {
        Text("Sleep timer", color = VColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(24.dp))
        endsAt?.let {
            val left = ((it - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
            Text("Stops in about $left min", color = VColors.Accent, fontSize = 14.sp, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
        }
        listOf(10, 15, 20, 30, 45, 60, 90).forEach { m ->
            Row(Modifier.fillMaxWidth().clickable { PlayerConn.setSleepTimer(m); close(); Ui.toast = "Music will stop in $m minutes" }
                .padding(horizontal = 24.dp, vertical = 13.dp)) {
                Text("$m minutes", color = VColors.Text, fontSize = 16.sp)
            }
        }
        if (endsAt != null) Row(Modifier.fillMaxWidth().clickable { PlayerConn.setSleepTimer(null); close() }.padding(horizontal = 24.dp, vertical = 13.dp)) {
            Text("Turn off timer", color = VColors.Accent, fontSize = 16.sp)
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Simple single-field dialog. */
@Composable
fun TextPrompt(title: String, initial: String, multiline: Boolean = false, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VColors.Sheet,
        title = { Text(title, color = VColors.Text) },
        text = {
            OutlinedTextField(
                text, { text = it }, singleLine = !multiline, minLines = if (multiline) 6 else 1, maxLines = if (multiline) 14 else 1,
                colors = vFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { if (multiline || text.isNotBlank()) onOk(text.trim()) }) { Text("OK", color = VColors.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = VColors.Text2) } },
    )
}

@Composable
fun vFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = VColors.Text, unfocusedTextColor = VColors.Text,
    focusedBorderColor = VColors.Accent, unfocusedBorderColor = VColors.Text3,
    cursorColor = VColors.Accent, focusedLabelColor = VColors.Accent, unfocusedLabelColor = VColors.Text2,
)

/** Lyrics editor: paste/edit lyrics (LRC timestamps supported) or search again online. */
@Composable
fun LyricsEditor(song: Song, onDismiss: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var text by remember { mutableStateOf(Lyrics.cached(song.path)?.raw.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VColors.Sheet,
        title = { Text("Lyrics", color = VColors.Text) },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, minLines = 8, maxLines = 16, colors = vFieldColors(),
                    placeholder = { Text("Paste lyrics here. [mm:ss.xx] timestamps make them scroll.", color = VColors.Text3) },
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val d = Lyrics.refetch(song)
                        busy = false
                        if (d != null) { text = d.raw; LyricsRefresh.tick++ } else Ui.toast = "No lyrics found online"
                    }
                }) { Text(if (busy) "Searching…" else "Search online (LRCLIB)", color = VColors.Accent) }
            }
        },
        confirmButton = { TextButton(onClick = { Lyrics.save(song.path, text); LyricsRefresh.tick++; onDismiss() }) { Text("Save", color = VColors.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = VColors.Text2) } },
    )
}
