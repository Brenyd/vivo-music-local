package com.brenyd.vmusic.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.audiofx.AudioEffect
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Subject
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import com.brenyd.vmusic.data.Library
import com.brenyd.vmusic.data.Lyrics
import com.brenyd.vmusic.data.Song
import com.brenyd.vmusic.playback.PlayMode
import com.brenyd.vmusic.playback.PlayerConn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Dark color taken from the album art, like vivo Music's player background. */
@Composable
fun rememberArtColor(path: String?): Color {
    val c by produceState(Color(0xFF1A1A1A), path) {
        value = withContext(Dispatchers.IO) {
            if (path == null) return@withContext Color(0xFF1A1A1A)
            runCatching {
                val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 }) ?: return@runCatching null
                val p = Palette.from(bmp).maximumColorCount(16).generate()
                val rgb = (p.darkMutedSwatch ?: p.mutedSwatch ?: p.darkVibrantSwatch ?: p.dominantSwatch)?.rgb ?: return@runCatching null
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(rgb, hsv)
                hsv[1] = hsv[1].coerceAtMost(0.55f)
                hsv[2] = hsv[2].coerceIn(0.16f, 0.30f)
                Color(android.graphics.Color.HSVToColor(hsv))
            }.getOrNull() ?: Color(0xFF1A1A1A)
        }
    }
    val anim by animateColorAsState(c, tween(600), label = "artColor")
    return anim
}

/** Loads lyrics for the current song (cache → LRCLIB). */
@Composable
fun rememberLyrics(song: Song?): Lyrics.Doc? {
    val d by produceState<Lyrics.Doc?>(null, song?.path, song?.title, song?.artist) {
        value = null
        if (song != null) value = runCatching { Lyrics.load(song) }.getOrNull()
    }
    return d
}

object LyricsRefresh { var tick by mutableStateOf(0) }

@Composable
fun NowPlayingScreen() {
    val song by PlayerConn.current.collectAsStateWithLifecycle()
    val playing by PlayerConn.isPlaying.collectAsStateWithLifecycle()
    val pos by PlayerConn.position.collectAsStateWithLifecycle()
    val dur by PlayerConn.duration.collectAsStateWithLifecycle()
    val mode by PlayerConn.mode.collectAsStateWithLifecycle()
    val favs by Library.favorites.collectAsStateWithLifecycle()
    val s = song
    val bg = rememberArtColor(s?.artPath)
    val tick = LyricsRefresh.tick
    val lyrics = key2(s, tick)
    var showLyrics by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(0f to bg, 0.55f to Color(0xFF0A0A0A), 1f to Color.Black))
            .pointerInput(Unit) { detectTapGestures { } } // swallow taps
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Ui.playerOpen = false }) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Close", tint = VColors.Text, modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showLyrics = !showLyrics }) {
                    Icon(Icons.Rounded.Subject, "Lyrics", tint = if (showLyrics) VColors.Accent else VColors.Text2)
                }
            }

            AnimatedContent(showLyrics, Modifier.weight(1f), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "lyr") { full ->
                if (full) {
                    FullLyrics(lyrics, pos, onTapOutside = { showLyrics = false })
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.height(8.dp))
                        VinylCover(s?.artPath, playing, swipe = { dir -> if (dir < 0) PlayerConn.next() else PlayerConn.previous() })
                        Spacer(Modifier.height(28.dp))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s?.title ?: "Not playing", color = VColors.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(s?.artist ?: "", color = VColors.Text2, fontSize = 16.sp, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                                            .clickable(enabled = s != null) { s?.let { Ui.push(Dest.ArtistPage(Library.splitArtists(it.artist).first())) } })
                                    if (s != null && s.album.isNotBlank()) {
                                        Spacer(Modifier.width(12.dp))
                                        Box(Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0x33FFFFFF))
                                            .clickable { Library.albums.value.firstOrNull { a -> a.songs.any { it.path == s.path } }?.let { Ui.push(Dest.AlbumPage(it.key)) } }
                                            .padding(horizontal = 14.dp, vertical = 6.dp)) {
                                            Text("Album", color = VColors.Text, fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                            val fav = s != null && s.path in favs
                            Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0x33FFFFFF))
                                .clickable(enabled = s != null) { s?.let { Library.toggleFavorite(it.path) } },
                                contentAlignment = Alignment.Center) {
                                Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favorite",
                                    tint = if (fav) VColors.Accent else VColors.Text, modifier = Modifier.size(28.dp))
                            }
                        }
                        Spacer(Modifier.height(26.dp))
                        LyricsPreview(lyrics, pos) { showLyrics = true }
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            // action row
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                ActionIcon(Icons.Rounded.Equalizer, "Sound effects") {
                    runCatching {
                        ctx.startActivity(Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, ctx.packageName)
                            .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC))
                    }.onFailure { Ui.toast = "No equalizer on this phone" }
                }
                ActionIcon(Icons.Rounded.Timer, "Sleep timer") { Ui.sleepOpen = true }
                ActionIcon(Icons.Rounded.Subject, "Lyrics") { showLyrics = !showLyrics }
                ActionIcon(Icons.Rounded.Share, "Share") { s?.let { shareSong(ctx, it) } }
                ActionIcon(Icons.Rounded.MoreVert, "More") { s?.let { Ui.more(it) } }
            }
            Spacer(Modifier.height(14.dp))
            SeekBar(pos, dur)
            Spacer(Modifier.height(10.dp))
            // transport
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { PlayerConn.cycleMode() }) {
                    Icon(when (mode) { PlayMode.LOOP_ALL -> Icons.Rounded.Repeat; PlayMode.LOOP_ONE -> Icons.Rounded.RepeatOne; PlayMode.SHUFFLE -> Icons.Rounded.Shuffle },
                        "Play mode", tint = VColors.Text, modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = { PlayerConn.previous() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous", tint = VColors.Text, modifier = Modifier.size(44.dp))
                }
                Box(Modifier.size(84.dp).clip(CircleShape).background(Color(0xFF2A2A2A)).clickable { PlayerConn.toggle() },
                    contentAlignment = Alignment.Center) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", tint = VColors.Text, modifier = Modifier.size(44.dp))
                }
                IconButton(onClick = { PlayerConn.next() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Next", tint = VColors.Text, modifier = Modifier.size(44.dp))
                }
                IconButton(onClick = { Ui.queueOpen = true }) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", tint = VColors.Text, modifier = Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun key2(s: Song?, tick: Int): Lyrics.Doc? {
    // re-load when the song changes or the lyrics were edited (tick)
    val d by produceState<Lyrics.Doc?>(null, s?.path, s?.title, s?.artist, tick) {
        value = null
        if (s != null) value = runCatching { Lyrics.load(s) }.getOrNull()
    }
    return d
}

@Composable
private fun ActionIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Icon(icon, desc, tint = VColors.Text2, modifier = Modifier.size(28.dp))
    }
}

/** Album art with a black vinyl record sliding out behind it on the right. */
@Composable
fun VinylCover(art: String?, playing: Boolean, swipe: (Int) -> Unit) {
    val rot = rememberInfiniteTransition(label = "vinyl")
    val angle by rot.animateFloat(0f, 360f, infiniteRepeatable(tween(20000, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    var frozen by remember { mutableFloatStateOf(0f) }
    val shown = if (playing) angle else frozen
    LaunchedEffect(playing) { if (!playing) frozen = angle }
    var drag by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(start = 24.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (drag < -120) swipe(-1) else if (drag > 120) swipe(1); drag = 0f },
                    onDragCancel = { drag = 0f },
                ) { _, d -> drag += d }
            }
    ) {
        val size = maxWidth * 0.80f
        Box(Modifier.height(size).fillMaxWidth()) {
            // record
            Canvas(Modifier.size(size * 0.94f).offset(x = size * 0.30f, y = size * 0.03f).rotate(shown)) {
                val r = this.size.minDimension / 2
                val c = Offset(r, r)
                drawCircle(Color(0xFF111111), r, c)
                var rr = r * 0.95f
                while (rr > r * 0.40f) { drawCircle(Color(0xFF1C1C1C), rr, c, style = Stroke(1f)); rr -= r * 0.035f }
                drawCircle(Color(0xFF2B2B2B), r * 0.33f, c)
                drawCircle(Color(0xFF0A0A0A), r * 0.04f, c)
            }
            ArtImage(art, size, Modifier.shadow(16.dp, RoundedCornerShape(10.dp)), shape = RoundedCornerShape(10.dp))
        }
    }
}

@Composable
private fun LyricsPreview(doc: Lyrics.Doc?, pos: Long, onClick: () -> Unit) {
    val lines = doc?.lines.orEmpty()
    val (a, b) = if (doc == null || lines.isEmpty()) {
        "" to ""
    } else if (doc.synced) {
        val i = doc.indexAt(pos + 300)
        (lines.getOrNull(i.coerceAtLeast(0))?.text ?: "") to (lines.getOrNull(i + 1)?.text ?: "")
    } else lines[0].text to (lines.getOrNull(1)?.text ?: "")
    Column(Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
        .padding(horizontal = 24.dp)) {
        if (doc == null) {
            Text("", fontSize = 18.sp)
        } else {
            Text(a.ifBlank { "♪" }, color = VColors.Text, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Text(b, color = VColors.Text2, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FullLyrics(doc: Lyrics.Doc?, pos: Long, onTapOutside: () -> Unit) {
    if (doc == null || doc.lines.isEmpty()) {
        Box(Modifier.fillMaxSize().clickable(onClick = onTapOutside), contentAlignment = Alignment.Center) {
            Text("No lyrics for this song\nAdd them in Song information", color = VColors.Text2, fontSize = 16.sp, textAlign = TextAlign.Center)
        }
        return
    }
    val state = rememberLazyListState()
    val cur = doc.indexAt(pos + 300)
    LaunchedEffect(cur) {
        if (doc.synced && cur >= 0) state.animateScrollToItem((cur - 3).coerceAtLeast(0))
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 120.dp)) {
        itemsIndexed(doc.lines) { i, l ->
            val active = doc.synced && i == cur
            Text(
                l.text.ifBlank { "♪" },
                color = if (active) VColors.Text else VColors.Text3,
                fontSize = if (active) 21.sp else 17.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                    .clickable(enabled = doc.synced) { PlayerConn.seekTo(l.timeMs) },
            )
        }
    }
}

@Composable
fun SeekBar(pos: Long, dur: Long) {
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    val frac = dragFrac ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(30.dp)
                .pointerInput(dur) {
                    detectTapGestures { o -> if (dur > 0) PlayerConn.seekTo((o.x / size.width * dur).toLong()) }
                }
                .pointerInput(dur) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragFrac = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { dragFrac?.let { if (dur > 0) PlayerConn.seekTo((it * dur).toLong()) }; dragFrac = null },
                        onDragCancel = { dragFrac = null },
                    ) { change, _ -> dragFrac = (change.position.x / size.width).coerceIn(0f, 1f) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val w = maxWidth
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF)))
            Box(Modifier.width(w * frac).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xCCFFFFFF)))
            Box(Modifier.offset(x = (w * frac) - 9.dp).size(18.dp).clip(CircleShape).background(Color.White))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(fmtTime(if (dragFrac != null) (dragFrac!! * dur).toLong() else pos), color = VColors.Text2, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(fmtTime(dur), color = VColors.Text2, fontSize = 12.sp)
        }
    }
}

fun shareSong(ctx: android.content.Context, s: Song) {
    runCatching {
        val i = Intent(Intent.ACTION_SEND).setType(s.mime.ifBlank { "audio/*" })
            .putExtra(Intent.EXTRA_STREAM, s.uri)
            .putExtra(Intent.EXTRA_TEXT, "${s.title} - ${s.artist}")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Share song").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
