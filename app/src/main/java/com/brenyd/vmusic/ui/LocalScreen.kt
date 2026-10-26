package com.brenyd.vmusic.ui

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brenyd.vmusic.data.Library
import com.brenyd.vmusic.data.Matcher
import com.brenyd.vmusic.data.SortOrder
import com.brenyd.vmusic.playback.Ids
import com.brenyd.vmusic.playback.PlayerConn
import kotlinx.coroutines.launch

private val LOCAL_TABS = listOf("Songs", "Artists", "Albums", "Folders")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocalScreen() {
    val pager = rememberPagerState(initialPage = Ui.localTab) { LOCAL_TABS.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { Ui.localTab = it } }

    Column(Modifier.fillMaxSize()) {
        SearchBarRow()
        // tabs
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceAround) {
            LOCAL_TABS.forEachIndexed { i, t ->
                val sel = pager.currentPage == i
                Column(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { scope.launch { pager.animateScrollToPage(i) } }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(t, color = if (sel) VColors.Text else VColors.Text2, fontSize = 17.sp,
                        fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal)
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.width(28.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (sel) VColors.Accent else Color.Transparent))
                }
            }
        }
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            when (page) {
                0 -> SongsPage()
                1 -> ArtistsPage()
                2 -> AlbumsPage()
                else -> FoldersPage()
            }
        }
    }
}

@Composable
fun SearchBarRow() {
    val scanning by Library.scanning.collectAsStateWithLifecycle()
    val matching by Matcher.running.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(23.dp)).background(VColors.Field)
                .clickable { Ui.push(Dest.Search) }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = VColors.Text2, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text("Search local music", color = VColors.Text2, fontSize = 16.sp)
        }
        Box(Modifier.size(56.dp).clip(CircleShape).clickable {
            Library.scope.launch { Library.scan(); Matcher.kick() }
            Ui.toast = "Scanning for music…"
        }, contentAlignment = Alignment.Center) {
            if (scanning || matching) CircularProgressIndicator(Modifier.size(26.dp), color = VColors.Text2, strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Add, "Scan", tint = VColors.Text, modifier = Modifier.size(32.dp))
            Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = 2.dp).clip(RoundedCornerShape(10.dp))
                .background(VColors.Accent).padding(horizontal = 5.dp, vertical = 1.dp)) {
                Text("Scan", color = Color.White, fontSize = 10.sp)
            }
        }
    }
    val status by Matcher.status.collectAsStateWithLifecycle()
    if (status.isNotEmpty()) {
        Text(status, color = VColors.Text3, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
    }
}

@Composable
fun SongsPage() {
    val songs by Library.songs.collectAsStateWithLifecycle()
    var order by remember { mutableStateOf(Library.songSort) }
    var sortOpen by remember { mutableStateOf(false) }
    val sorted = remember(songs, order) { Library.sorted(songs, order) }
    val scanned by Library.scannedOnce.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            PlayAllRow(sorted.size, onPlay = { PlayerConn.play(sorted, 0, Ids.SONGS) }, onSort = { sortOpen = true },
                onMore = { PlayerConn.shuffleAll(sorted) })
        }
        if (sorted.isEmpty()) item {
            if (scanned) EmptyState("No local music found", "Put music files on your phone, then tap Scan")
            else EmptyState("Scanning…")
        }
        items(sorted, key = { it.path }) { s ->
            SongRow(s, onClick = { PlayerConn.play(sorted, sorted.indexOf(s), Ids.SONGS) }, onMore = { Ui.more(s) })
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (sortOpen) SortSheet(order, onDismiss = { sortOpen = false }) { order = it; Library.songSort = it; sortOpen = false }
}

@Composable
fun ArtistsPage() {
    val artists by Library.artists.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize()) {
        item { TotalRow(artists.size) }
        items(artists, key = { it.name }) { a ->
            EntityRow(a.name, plural(a.songs.size, "song"), a.imagePath ?: a.artPath, circle = true) { Ui.push(Dest.ArtistPage(a.name)) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun AlbumsPage() {
    val albums by Library.albums.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize()) {
        item { TotalRow(albums.size) }
        items(albums, key = { it.key }) { a ->
            EntityRow(a.name, "${a.artist}  |  ${plural(a.songs.size, "song")}", a.artPath,
                leading = { AlbumThumb(a.artPath, 52.dp) }) { Ui.push(Dest.AlbumPage(a.key)) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun FoldersPage() {
    val folders by Library.folders.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize()) {
        item { TotalRow(folders.size) }
        items(folders, key = { it.path }) { f ->
            Row(
                Modifier.fillMaxWidth().clickable { Ui.push(Dest.FolderPage(f.path)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(VColors.Placeholder), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Folder, null, tint = Color(0xFFF7D46A), modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, color = VColors.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Row {
                        Text(plural(f.songs.size, "song"), color = VColors.Text2, fontSize = 12.sp)
                        Spacer(Modifier.width(20.dp))
                        Text(prettyPath(f.path).let { if (it.length > 42) "…" + it.takeLast(40) else it }, color = VColors.Text2,
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

fun prettyPath(p: String): String = p.replace(Regex("^/storage/emulated/0"), "Phone storage")

/** Album cover with a sliver of vinyl peeking out on the right, like vivo Music's album list. */
@Composable
fun AlbumThumb(path: String?, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.width(size + 6.dp).height(size)) {
        Box(Modifier.size(size).offset(x = 6.dp).clip(CircleShape).background(Color(0xFF151515)))
        ArtImage(path, size)
    }
}

@Composable
fun SortSheet(current: SortOrder, onDismiss: () -> Unit, onPick: (SortOrder) -> Unit) {
    VSheet(onDismiss) {
        Text("Sort by", color = VColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(20.dp))
        SortOrder.entries.forEach { o ->
            Row(Modifier.fillMaxWidth().clickable { onPick(o) }.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text(o.label, color = if (o == current) VColors.Accent else VColors.Text, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
