package com.brenyd.vmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brenyd.vmusic.data.Library
import com.brenyd.vmusic.data.Song
import com.brenyd.vmusic.data.UNKNOWN_ARTIST
import com.brenyd.vmusic.playback.Ids
import com.brenyd.vmusic.playback.PlayerConn
import com.brenyd.vmusic.playback.Tree
import kotlinx.coroutines.launch

@Composable
fun TopBar(title: String, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { Ui.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = VColors.Text) }
        Text(title, color = VColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}

/** Shared layout for album / artist / folder / playlist pages. */
@Composable
fun SongListPage(
    title: String,
    songs: List<Song>,
    parentId: String,
    header: @Composable () -> Unit,
    playlistId: String? = null,
    numbered: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title, actions)
        LazyColumn(Modifier.fillMaxSize()) {
            item { header() }
            item { PlayAllRow(songs.size, onPlay = { PlayerConn.play(songs, 0, parentId) }) }
            if (songs.isEmpty()) item { EmptyState("No songs here") }
            items(songs, key = { it.path }) { s ->
                SongRow(
                    s, showArt = !numbered, index = if (numbered) (if (s.track > 0) s.track else songs.indexOf(s) + 1) else null,
                    onClick = { PlayerConn.play(songs, songs.indexOf(s), parentId) },
                    onMore = { Ui.more(s, playlistId) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun BigHeader(art: String?, title: String, subtitle: String, circle: Boolean = false, placeholder: ImageVector? = null) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (placeholder != null && art == null) {
            Box(Modifier.size(120.dp).clip(RoundedCornerShape(16.dp)).background(VColors.Placeholder), contentAlignment = Alignment.Center) {
                Icon(placeholder, null, tint = VColors.Accent, modifier = Modifier.size(54.dp))
            }
        } else {
            ArtImage(art, 120.dp, shape = if (circle) CircleShape else RoundedCornerShape(14.dp),
                placeholder = if (circle) Icons.Rounded.Person else Icons.Rounded.MusicNote)
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = VColors.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = VColors.Text2, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun AlbumScreen(key: String) {
    val albums by Library.albums.collectAsStateWithLifecycle()
    val a = albums.firstOrNull { it.key == key } ?: run { TopBar("Album"); EmptyState("Album not found"); return }
    val year = a.songs.firstOrNull { it.year > 0 }?.year
    SongListPage(a.name, a.songs, Ids.album(a.key), numbered = true, header = {
        BigHeader(a.artPath, a.name, listOfNotNull(a.artist, year?.toString(), plural(a.songs.size, "song")).joinToString("  ·  "))
    })
}

@Composable
fun ArtistScreen(name: String) {
    val artists by Library.artists.collectAsStateWithLifecycle()
    val a = artists.firstOrNull { it.name == name } ?: run { TopBar("Artist"); EmptyState("Artist not found"); return }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val albumsCount = a.songs.map { it.album }.distinct().size
    SongListPage(a.name, a.songs, Ids.artist(a.name), header = {
        BigHeader(a.artPath, a.name, "${plural(a.songs.size, "song")}  ·  ${plural(albumsCount, "album")}", circle = true)
    }, actions = {
        if (a.name != UNKNOWN_ARTIST) IconButton(onClick = {
            scope.launch {
                Ui.toast = "Looking for a photo of ${a.name}…"
                val ok = com.brenyd.vmusic.data.Matcher.fetchArtistImage(a.name)
                Ui.toast = if (ok) "Artist photo updated" else "No photo found"
            }
        }) { Icon(Icons.Rounded.Person, "Get artist photo", tint = VColors.Text) }
    })
}

@Composable
fun FolderScreen(path: String) {
    val folders by Library.folders.collectAsStateWithLifecycle()
    val f = folders.firstOrNull { it.path == path } ?: run { TopBar("Folder"); EmptyState("Folder not found"); return }
    SongListPage(f.name, f.songs, Ids.folder(f.path), header = {
        BigHeader(null, f.name, prettyPath(f.path), placeholder = Icons.Rounded.Folder)
    })
}

@Composable
fun PlaylistScreen(id: String) {
    val songsAll by Library.songs.collectAsStateWithLifecycle()
    val favs by Library.favorites.collectAsStateWithLifecycle()
    val recents by Library.recents.collectAsStateWithLifecycle()
    val counts by Library.playCounts.collectAsStateWithLifecycle()
    val lists by Library.playlists.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    val (title, icon) = when (id) {
        Ids.FAVORITES -> "Favorites" to Icons.Rounded.Favorite
        Ids.RECENT -> "Recently played" to Icons.Rounded.History
        Ids.MOST -> "Most played" to Icons.AutoMirrored.Rounded.TrendingUp
        else -> (lists.firstOrNull { "pl:" + it.id == id }?.name ?: "Playlist") to Icons.AutoMirrored.Rounded.QueueMusic
    }
    val songs = remember(id, songsAll, favs, recents, counts, lists) { Tree.songsOf(id) }
    val userList = lists.firstOrNull { "pl:" + it.id == id }
    SongListPage(title, songs, id, playlistId = userList?.id, header = {
        BigHeader(songs.firstNotNullOfOrNull { it.artPath }, title, plural(songs.size, "song"), placeholder = icon)
    }, actions = {
        if (userList != null) {
            IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, "Rename", tint = VColors.Text) }
            IconButton(onClick = { Library.deletePlaylist(userList.id); Ui.back(); Ui.toast = "Playlist deleted" }) {
                Icon(Icons.Rounded.Delete, "Delete", tint = VColors.Text)
            }
        }
    })
    if (renaming && userList != null) {
        TextPrompt("Rename playlist", userList.name, onDismiss = { renaming = false }) {
            Library.renamePlaylist(userList.id, it); renaming = false
        }
    }
}

@Composable
fun SearchScreen() {
    var q by remember { mutableStateOf("") }
    val songs by Library.songs.collectAsStateWithLifecycle()
    val artists by Library.artists.collectAsStateWithLifecycle()
    val albums by Library.albums.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val songRes = remember(q, songs) { if (q.isBlank()) emptyList() else Tree.searchSongs(q) }
    val artRes = remember(q, artists) { if (q.isBlank()) emptyList() else artists.filter { it.name.contains(q.trim(), true) }.take(5) }
    val albRes = remember(q, albums) { if (q.isBlank()) emptyList() else albums.filter { it.name.contains(q.trim(), true) }.take(5) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { Ui.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = VColors.Text) }
            Row(
                Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(23.dp)).background(VColors.Field).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = VColors.Text2, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Text("Search local music", color = VColors.Text2, fontSize = 16.sp)
                    BasicTextField(q, { q = it }, singleLine = true,
                        textStyle = TextStyle(color = VColors.Text, fontSize = 16.sp),
                        cursorBrush = SolidColor(VColors.Accent), modifier = Modifier.fillMaxWidth().focusRequester(focus))
                }
                if (q.isNotEmpty()) Icon(Icons.Rounded.Close, "Clear", tint = VColors.Text2,
                    modifier = Modifier.size(20.dp).clickable { q = "" })
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            if (artRes.isNotEmpty()) {
                item { SectionTitle("Artists") }
                items(artRes, key = { "a" + it.name }) { a ->
                    EntityRow(a.name, plural(a.songs.size, "song"), a.artPath, circle = true) { Ui.push(Dest.ArtistPage(a.name)) }
                }
            }
            if (albRes.isNotEmpty()) {
                item { SectionTitle("Albums") }
                items(albRes, key = { "b" + it.key }) { a ->
                    EntityRow(a.name, a.artist, a.artPath) { Ui.push(Dest.AlbumPage(a.key)) }
                }
            }
            if (songRes.isNotEmpty()) {
                item { SectionTitle("Songs") }
                items(songRes, key = { "s" + it.path }) { s ->
                    SongRow(s, onClick = { PlayerConn.play(songRes, songRes.indexOf(s), "search") }, onMore = { Ui.more(s) })
                }
            }
            if (q.isNotBlank() && songRes.isEmpty() && artRes.isEmpty() && albRes.isEmpty()) item { EmptyState("No results") }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun SectionTitle(t: String, modifier: Modifier = Modifier) {
    Text(t, color = VColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp))
}

/** Unused helper kept for gradients on pages. */
val fadeBrush = Brush.verticalGradient(listOf(Color(0x33F05D56), Color.Transparent))
