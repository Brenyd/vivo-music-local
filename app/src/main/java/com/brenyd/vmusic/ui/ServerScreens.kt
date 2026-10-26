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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brenyd.vmusic.data.Remote
import com.brenyd.vmusic.data.RemoteEntry
import com.brenyd.vmusic.data.Servers
import com.brenyd.vmusic.playback.PlayerConn
import kotlinx.coroutines.launch

/** "Network libraries" rows for the Me tab. */
@Composable
fun ServersSection() {
    val servers by Servers.list.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    Column {
        servers.forEach { s ->
            EntityRow(s.name, "${s.typeLabel} · ${s.url}", null, leading = {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(VColors.Tile), contentAlignment = Alignment.Center) {
                    Icon(if (s.type == "subsonic") Icons.Rounded.Dns else Icons.Rounded.Folder, null, tint = VColors.Accent)
                }
            }, trailing = {
                IconButton(onClick = { Servers.remove(s.id) }) { Icon(Icons.Rounded.Delete, "Remove", tint = VColors.Text3) }
            }) { Ui.push(Dest.ServerPage(s.id, null, s.name)) }
        }
        SettingRow(Icons.Rounded.Dns, "Add a server or network folder", "Navidrome / Subsonic, WebDAV, SMB (NAS or PC share)") { adding = true }
    }
    if (adding) AddServerDialog { adding = false }
}

@Composable
private fun AddServerDialog(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf("subsonic") }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val hint = when (type) {
        "subsonic" -> "https://music.example.com"
        "webdav" -> "https://nas.local/dav/Music"
        else -> "smb://192.168.1.10/Music"
    }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = VColors.Sheet,
        title = { Text("Add a server", color = VColors.Text) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("subsonic" to "Subsonic", "webdav" to "WebDAV", "smb" to "SMB").forEach { (k, l) ->
                        Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if (type == k) VColors.Accent else VColors.Tile)
                            .clickable { type = k }.padding(horizontal = 12.dp, vertical = 7.dp)) { Text(l, color = VColors.Text, fontSize = 13.sp) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(url, { url = it.trim() }, label = { Text("Address") }, placeholder = { Text(hint, color = VColors.Text3) },
                    singleLine = true, colors = vFieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, label = { Text(if (type == "smb") "User (DOMAIN\\user, empty = guest)" else "User") },
                    singleLine = true, colors = vFieldColors(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, colors = vFieldColors(),
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, colors = vFieldColors(),
                    modifier = Modifier.fillMaxWidth())
                if (error != null) Text(error!!, color = VColors.Accent, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && url.isNotBlank(), onClick = {
                busy = true; error = null
                val fixed = when {
                    type == "smb" && !url.startsWith("smb://") -> "smb://" + url.removePrefix("\\\\").replace('\\', '/')
                    type != "smb" && !url.startsWith("http") -> "https://$url"
                    else -> url
                }
                scope.launch {
                    val s = Servers.add(type, name, fixed, user, pass)
                    val err = Remote.test(s)
                    busy = false
                    if (err == null) { Ui.toast = "Connected to ${s.name}"; onDone(); Ui.push(Dest.ServerPage(s.id, null, s.name)) }
                    else { Servers.remove(s.id); error = "Couldn't connect: $err" }
                }
            }) { Text(if (busy) "Connecting…" else "Add", color = VColors.Accent) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel", color = VColors.Text2) } },
    )
}

/** Browse a server: Subsonic menus/artists/albums or WebDAV/SMB folders. */
@Composable
fun ServerScreen(serverId: String, browseId: String?, title: String) {
    val server = Servers.get(serverId)
    var entries by remember(serverId, browseId) { mutableStateOf<List<RemoteEntry>?>(null) }
    var error by remember(serverId, browseId) { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(serverId, browseId) {
        if (server == null) return@LaunchedEffect
        try { entries = Remote.browse(server, browseId) } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(title)
        if (server == null) { EmptyState("Server not found"); return }
        if (server.type == "subsonic" && browseId == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(46.dp).clip(RoundedCornerShape(23.dp))
                .background(VColors.Field).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = VColors.Text2, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search ${server.name}", color = VColors.Text2, fontSize = 16.sp)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = VColors.Text, fontSize = 16.sp),
                        cursorBrush = SolidColor(VColors.Accent), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            scope.launch { try { entries = Remote.search(server, query) } catch (e: Exception) { error = e.message } }
                        }), modifier = Modifier.fillMaxWidth())
                }
            }
        }
        val list = entries
        when {
            error != null -> EmptyState("Couldn't load", error)
            list == null -> Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = VColors.Accent)
            }
            else -> {
                val songs = list.mapNotNull { it.song }
                LazyColumn(Modifier.fillMaxSize()) {
                    if (songs.isNotEmpty()) item {
                        PlayAllRow(songs.size, onPlay = { PlayerConn.play(songs, 0, "remote") }, onMore = { PlayerConn.shuffleAll(songs) })
                    }
                    if (list.isEmpty()) item { EmptyState("Nothing here") }
                    items(list, key = { it.id }) { e ->
                        val s = e.song
                        if (s != null) {
                            SongRow(s, onClick = { PlayerConn.play(songs, songs.indexOf(s), "remote") }, onMore = { Ui.more(s) })
                        } else {
                            val shape = if (e.kind == "artist") CircleShape else RoundedCornerShape(12.dp)
                            EntityRow(e.title, e.subtitle, e.artUrl, circle = e.kind == "artist", leading = {
                                if (e.artUrl != null) ArtImage(e.artUrl, 52.dp, shape = shape)
                                else Box(Modifier.size(52.dp).clip(shape).background(VColors.Placeholder), contentAlignment = Alignment.Center) {
                                    Icon(when (e.kind) { "artist" -> Icons.Rounded.Person; "album" -> Icons.Rounded.Album; else -> Icons.Rounded.Folder },
                                        null, tint = if (e.kind == "folder") Color(0xFFF7D46A) else VColors.Text3, modifier = Modifier.size(30.dp))
                                }
                            }) { Ui.push(Dest.ServerPage(serverId, e.id, e.title)) }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}
