package com.brenyd.vmusic.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.brenyd.vmusic.data.Art
import com.brenyd.vmusic.data.Library
import com.brenyd.vmusic.data.Lyrics
import com.brenyd.vmusic.data.Matcher
import com.brenyd.vmusic.data.Online
import com.brenyd.vmusic.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** vivo-style "Song information" sheet: view, edit, match online, restore. */
@Composable
fun SongInfoSheet(initial: Song) {
    val songs by Library.songs.collectAsStateWithLifecycle()
    val s = songs.firstOrNull { it.path == initial.path } ?: initial
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var title by remember(s.path) { mutableStateOf(s.title) }
    var artist by remember(s.path) { mutableStateOf(s.artist) }
    var album by remember(s.path) { mutableStateOf(s.album) }
    var number by remember(s.path) { mutableStateOf(if (s.track > 0) s.track.toString() else "") }
    var pickedCand by remember { mutableStateOf<Online.Candidate?>(null) }
    var pickedCover by remember { mutableStateOf<Uri?>(null) }
    var matching by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<Online.Candidate>?>(null) }
    var lyricsOpen by remember { mutableStateOf(false) }
    val lyricsPreview = remember(s.path, LyricsRefresh.tick) { Lyrics.cached(s.path)?.lines?.take(3)?.joinToString(" ") { it.text } }
    val close = { Ui.infoFor = null }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            if (editing) { pickedCover = uri; pickedCand = pickedCand?.copy(coverUrl = null) }
            else scope.launch {
                val f = withContext(Dispatchers.IO) { Art.importCover(s.path, uri) }
                if (f != null) Library.updateOverride(s.path) { it.copy(art = f.absolutePath) } else Ui.toast = "Couldn't use that image"
            }
        }
    }

    VSheet(close) {
        Column(Modifier.fillMaxWidth().heightIn(max = 760.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, end = 12.dp), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = close) { Icon(Icons.Rounded.Close, "Close", tint = VColors.Text, modifier = Modifier.size(30.dp)) }
            }
            // cover with "Change cover"
            Box(Modifier.align(Alignment.CenterHorizontally).size(170.dp).clip(RoundedCornerShape(10.dp))
                .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                when {
                    pickedCover != null -> AsyncImage(pickedCover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    pickedCand?.coverUrl != null -> AsyncImage(pickedCand!!.coverUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else -> ArtImage(s.artPath, 170.dp)
                }
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x66000000)).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Edit, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Change cover", color = Color.White, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(20.dp))
            if (!editing) {
                InfoLine("Song name", s.title)
                InfoLine("Artist", s.artist)
                InfoLine("Album", s.album)
                InfoLine("Number", if (s.track > 0) s.track.toString() else "-")
            } else {
                EditLine("Song name", title) { title = it }
                EditLine("Artists", artist) { artist = it }
                EditLine("Albums", album) { album = it }
                EditLine("No.", number) { number = it.filter { c -> c.isDigit() }.take(3) }
            }
            Row(Modifier.fillMaxWidth().clickable { lyricsOpen = true }.padding(horizontal = 28.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Lyrics:  " + (lyricsPreview ?: "No lyrics for the song"), color = VColors.Text, fontSize = 17.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = VColors.Text2)
            }
            Text("More information", color = VColors.Text2, fontSize = 15.sp, modifier = Modifier.padding(start = 28.dp, top = 8.dp, bottom = 6.dp))
            val q = s.quality
            val fmt = q?.label ?: s.path.substringAfterLast('.', "").uppercase(Locale.US)
            val kbps = when { q != null && q.kbps > 0 -> "${q.kbps} kbps"; s.bitrate > 0 -> "${s.bitrate / 1000} kbps"; else -> "-" }
            Text("Quality: ${q?.tag ?: "-"}   ·   $fmt   ·   Bitrate: $kbps   ·   Size: ${String.format(Locale.US, "%.1f MB", s.size / 1048576.0)}" +
                (if (s.year > 0) "   ·   Year: ${s.year}" else ""), color = VColors.Text3, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 28.dp))
            Text(prettyPath(s.path), color = VColors.Text3, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp))
            Spacer(Modifier.height(16.dp))

            // buttons
            if (!editing) {
                Box(Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(30.dp))
                    .border(2.dp, VColors.Accent, RoundedCornerShape(30.dp))
                    .clickable { editing = true }.padding(horizontal = 36.dp, vertical = 14.dp)) {
                    Text("Restore/Change information", color = VColors.Accent, fontSize = 18.sp)
                }
                Text(if (s.matched) "The song has been matched" else "Song information from the file", color = VColors.Text2, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 20.dp))
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(27.dp)).background(Color(0xFF3A3A3A))
                        .clickable(enabled = !matching) {
                            matching = true
                            scope.launch {
                                val res = withContext(Dispatchers.IO) { runCatching { Online.searchTrack(title, artist, s.durationMs) }.getOrDefault(emptyList()) }
                                matching = false
                                if (res.isEmpty()) Ui.toast = "No matches found online" else candidates = res.take(15)
                            }
                        }, contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (matching) { CircularProgressIndicator(Modifier.size(18.dp), color = VColors.Accent, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
                            Text("Matching", color = VColors.Text, fontSize = 18.sp)
                        }
                    }
                    Box(Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(27.dp)).background(VColors.Accent)
                        .clickable {
                            val cand = pickedCand
                            val cover = pickedCover
                            scope.launch {
                                val artFile = withContext(Dispatchers.IO) {
                                    when {
                                        cover != null -> Art.importCover(s.path, cover)?.absolutePath
                                        cand?.coverUrl != null -> Online.bytes(cand.coverUrl)?.let { b ->
                                            val f = Art.matchedFile(s.path); if (Art.saveImage(b, f)) f.absolutePath else null
                                        }
                                        else -> null
                                    }
                                }
                                Library.updateOverride(s.path) { o ->
                                    o.copy(
                                        title = title.trim().ifBlank { null }?.takeIf { it != s.fileTitle },
                                        artist = artist.trim().ifBlank { null }?.takeIf { it != s.fileArtist },
                                        album = album.trim().ifBlank { null }?.takeIf { it != s.fileAlbum },
                                        track = number.toIntOrNull(),
                                        year = cand?.year ?: o.year,
                                        art = artFile ?: o.art,
                                        matched = o.matched || cand != null,
                                    )
                                }
                                Lyrics.forget(s.path); LyricsRefresh.tick++
                                editing = false; pickedCand = null; pickedCover = null
                                Ui.toast = "Saved"
                            }
                        }, contentAlignment = Alignment.Center) {
                        Text("Save", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    }
                }
                TextButton(onClick = {
                    Library.restore(s.path); LyricsRefresh.tick++
                    editing = false; pickedCand = null; pickedCover = null
                    title = s.fileTitle; artist = s.fileArtist; album = s.fileAlbum
                    Ui.toast = "Restored the file's own information"
                }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp)) {
                    Text("Restore original information", color = VColors.Text2)
                }
            }
        }
    }

    candidates?.let { list ->
        AlertDialog(
            onDismissRequest = { candidates = null },
            containerColor = VColors.Sheet,
            title = { Text("Choose the right song", color = VColors.Text) },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(list) { c ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                            pickedCand = c; pickedCover = null
                            title = c.title; artist = c.artist; if (c.album.isNotBlank()) album = c.album
                            c.track?.let { number = it.toString() }
                            candidates = null
                            scope.launch {
                                val e = withContext(Dispatchers.IO) { runCatching { Online.enrich(c) }.getOrDefault(c) }
                                if (pickedCand == c) { pickedCand = e; e.track?.let { number = it.toString() } }
                            }
                        }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(VColors.Placeholder)) {
                                if (c.coverUrl != null) AsyncImage(c.coverUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.title, color = VColors.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${c.artist} - ${c.album}", color = VColors.Text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${c.source}  ·  ${c.durationMs?.let { fmtTime(it) } ?: "?"}  ·  ${(c.score * 100).toInt()}% match",
                                    color = VColors.Text3, fontSize = 11.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { candidates = null }) { Text("Cancel", color = VColors.Text2) } },
        )
    }

    if (lyricsOpen) LyricsEditor(s) { lyricsOpen = false }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Text("$label:  $value", color = VColors.Text, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 13.dp))
}

@Composable
private fun EditLine(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, singleLine = true, label = { Text(label) }, colors = vFieldColors(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
    )
}

@Suppress("unused")
private fun exists(p: String?) = p != null && File(p).exists()

@Suppress("unused")
private val center = TextAlign.Center

@Suppress("unused")
private fun unusedMatcherRef() = Matcher.running
