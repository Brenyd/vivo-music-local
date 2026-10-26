package com.brenyd.vmusic.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.brenyd.vmusic.data.Song
import com.brenyd.vmusic.playback.PlayerConn
import java.io.File
import java.util.Locale

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    else String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
}

fun plural(n: Int, one: String) = if (n == 1) "1 $one" else "$n ${one}s"

/** Cover / photo with vivo's dark placeholder. */
@Composable
fun ArtImage(
    path: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    placeholder: ImageVector = Icons.Rounded.MusicNote,
) {
    Box(
        modifier.size(size).clip(shape).background(VColors.Placeholder),
        contentAlignment = Alignment.Center,
    ) {
        Icon(placeholder, null, tint = Color(0xFF444444), modifier = Modifier.size(size * 0.45f))
        if (path != null) {
            AsyncImage(
                model = if (path.startsWith("http")) path else File(path),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun Badge(text: String, color: Color) {
    Box(
        Modifier
            .padding(end = 4.dp)
            .border(1.dp, color, RoundedCornerShape(3.dp))
            .padding(horizontal = 3.dp, vertical = 0.dp),
    ) {
        Text(text, color = color, fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** SQ / HQ / CD / HD tag, colored by tier. */
@Composable
fun QualityBadge(tag: String) {
    val c = when (tag) {
        "HD" -> Color(0xFFE6B54A)
        "CD" -> VColors.Blue
        "HQ" -> Color(0xFF5FC29A)
        else -> VColors.Text3
    }
    Badge(tag, c)
}

@Composable
fun MatchedBadge() {
    Box(
        Modifier.padding(end = 4.dp).size(14.dp).clip(CircleShape).background(VColors.Blue),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(10.dp))
    }
}

/** Song row exactly like vivo Music's local list. */
@Composable
fun SongRow(song: Song, onClick: () -> Unit, onMore: () -> Unit, showArt: Boolean = true, index: Int? = null) {
    val current by PlayerConn.currentPath.collectAsStateWithLifecycle()
    val playing = current == song.path
    val isNew = System.currentTimeMillis() / 1000 - song.dateAdded < 7 * 24 * 3600
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showArt) {
            ArtImage(song.art, 52.dp)
            Spacer(Modifier.width(14.dp))
        } else if (index != null) {
            Text("$index", color = VColors.Text3, fontSize = 15.sp, modifier = Modifier.width(36.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                song.title, color = if (playing) VColors.Accent else VColors.Text, fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isNew) Badge("NEW", VColors.NewBadge)
                if (song.matched) MatchedBadge()
                song.quality?.let { QualityBadge(it.tag) }
                Text(
                    "${song.artist} - ${song.album}", color = VColors.Text2, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Text("  |  ", color = VColors.Text3, fontSize = 10.sp)
                Text(fmtTime(song.durationMs), color = VColors.Text2, fontSize = 12.sp)
            }
        }
        IconButton(onClick = onMore) {
            Icon(Icons.Rounded.MoreVert, "More", tint = VColors.Text2)
        }
    }
}

/** "▶ Play all (N songs)   ⇅  ⋮" header. */
@Composable
fun PlayAllRow(count: Int, onPlay: () -> Unit, onSort: (() -> Unit)? = null, onMore: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).clickable(onClick = onPlay).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(Color(0xFFEDEDED)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text("Play all ($count songs)", color = VColors.Text, fontSize = 17.sp)
        }
        if (onSort != null) IconButton(onClick = onSort) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort", tint = VColors.Text) }
        if (onMore != null) IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, "More", tint = VColors.Text) }
    }
}

/** "N in total   ⇅" header. */
@Composable
fun TotalRow(count: Int, onSort: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$count in total", color = VColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (onSort != null) IconButton(onClick = onSort) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort", tint = VColors.Text) }
        else Spacer(Modifier.height(48.dp))
    }
}

/** Two-line row with an image (artists, albums, folders, playlists). */
@Composable
fun EntityRow(
    title: String,
    subtitle: String,
    art: String?,
    circle: Boolean = false,
    placeholder: ImageVector = if (circle) Icons.Rounded.Person else Icons.Rounded.MusicNote,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) leading() else ArtImage(art, 52.dp, shape = if (circle) CircleShape else RoundedCornerShape(10.dp), placeholder = placeholder)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = VColors.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = VColors.Text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun EmptyState(text: String, sub: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 80.dp, start = 32.dp, end = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.MusicNote, null, tint = VColors.Text3, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(text, color = VColors.Text2, fontSize = 15.sp)
        if (sub != null) {
            Spacer(Modifier.height(6.dp))
            Text(sub, color = VColors.Text3, fontSize = 13.sp)
        }
    }
}

/** Grey rounded-square tile with an icon and a label (vivo "more" sheet). */
@Composable
fun GridTile(icon: ImageVector, label: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(62.dp).clip(RoundedCornerShape(16.dp)).background(VColors.Tile)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (enabled) VColors.Text else VColors.Text3, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = if (enabled) VColors.Text else VColors.Text3, fontSize = 13.sp, maxLines = 2,
            lineHeight = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
