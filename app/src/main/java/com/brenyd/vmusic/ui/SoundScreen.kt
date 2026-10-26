package com.brenyd.vmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brenyd.vmusic.playback.AudioFx
import java.util.Locale
import kotlin.math.roundToInt

@Composable
private fun vSlider() = SliderDefaults.colors(
    thumbColor = VColors.Text, activeTrackColor = VColors.Accent, inactiveTrackColor = VColors.Tile,
)

@Composable
fun SoundScreen() {
    val eq by AudioFx.state.collectAsStateWithLifecycle()
    val usb by AudioFx.usbStatus.collectAsStateWithLifecycle()
    var eqOn by remember { mutableStateOf(AudioFx.eqEnabled) }
    var bass by remember { mutableFloatStateOf(AudioFx.bassStrength.toFloat()) }
    var rgMode by remember { mutableStateOf(AudioFx.replayGainMode) }
    var preamp by remember { mutableFloatStateOf(AudioFx.replayGainPreamp) }
    var fade by remember { mutableFloatStateOf(AudioFx.fadeSeconds.toFloat()) }
    var hiRes by remember { mutableStateOf(AudioFx.hiResOutput) }
    var bitPerfect by remember { mutableStateOf(AudioFx.bitPerfect) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Sound")
        LazyColumn(Modifier.fillMaxSize()) {
            // ---------------------------------------------------------- equalizer
            item { SectionTitle("Equalizer") }
            item {
                SettingSwitch("Equalizer", if (eq.available) "${eq.bands.size}-band equalizer and bass boost" else "Starts when music plays", eqOn) {
                    eqOn = it; AudioFx.eqEnabled = it
                }
            }
            if (eq.available && eqOn) {
                if (eq.presets.isNotEmpty()) item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(eq.presets) { i, name ->
                            Box(Modifier.clip(RoundedCornerShape(18.dp)).background(VColors.Tile).clickable { AudioFx.usePreset(i) }
                                .padding(horizontal = 14.dp, vertical = 8.dp)) {
                                Text(name, color = VColors.Text, fontSize = 14.sp)
                            }
                        }
                    }
                }
                eq.bands.forEach { b ->
                    item(key = "band${b.index}") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(hz(b.centerHz), color = VColors.Text2, fontSize = 13.sp, modifier = Modifier.width(64.dp))
                            Slider(
                                value = b.level.toFloat(), onValueChange = { AudioFx.setBand(b.index, it.roundToInt()) },
                                valueRange = eq.min.toFloat()..eq.max.toFloat(), colors = vSlider(), modifier = Modifier.weight(1f),
                            )
                            Text(String.format(Locale.US, "%+.0f dB", b.level / 100f), color = VColors.Text2, fontSize = 12.sp,
                                modifier = Modifier.width(52.dp).padding(start = 6.dp))
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Bass", color = VColors.Text2, fontSize = 13.sp, modifier = Modifier.width(64.dp))
                        Slider(bass, { bass = it }, onValueChangeFinished = { AudioFx.bassStrength = bass.roundToInt() },
                            valueRange = 0f..1000f, colors = vSlider(), modifier = Modifier.weight(1f))
                        Text("${(bass / 10).roundToInt()}%", color = VColors.Text2, fontSize = 12.sp, modifier = Modifier.width(52.dp).padding(start = 6.dp))
                    }
                }
                item {
                    TextButton(onClick = { AudioFx.resetEq(); bass = 0f; AudioFx.bassStrength = 0 }, modifier = Modifier.padding(start = 8.dp)) {
                        Text("Reset", color = VColors.Accent)
                    }
                }
            }

            // ---------------------------------------------------------- ReplayGain
            item { SectionTitle("Volume leveling (ReplayGain)") }
            item {
                Text("Plays every song at the same loudness using the ReplayGain tags in your files.", color = VColors.Text2, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("off" to "Off", "track" to "Track", "album" to "Album").forEach { (k, label) ->
                        val sel = rgMode == k
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(if (sel) VColors.Accent else VColors.Tile)
                            .clickable { rgMode = k; AudioFx.replayGainMode = k }.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center) {
                            Text(label, color = VColors.Text, fontSize = 15.sp, fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal)
                        }
                    }
                }
            }
            if (rgMode != "off") item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Pre-amp", color = VColors.Text2, fontSize = 13.sp, modifier = Modifier.width(64.dp))
                    Slider(preamp, { preamp = (it * 2).roundToInt() / 2f }, onValueChangeFinished = { AudioFx.replayGainPreamp = preamp },
                        valueRange = -12f..6f, colors = vSlider(), modifier = Modifier.weight(1f))
                    Text(String.format(Locale.US, "%+.1f dB", preamp), color = VColors.Text2, fontSize = 12.sp, modifier = Modifier.width(60.dp).padding(start = 6.dp))
                }
            }

            // ---------------------------------------------------------- transitions
            item { SectionTitle("Between songs") }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(if (fade < 1f) "Gapless (no fade)" else "Fade out / in: ${fade.roundToInt()} s", color = VColors.Text, fontSize = 16.sp)
                    Text("Albums play gaplessly. Turn this up to fade between songs instead.", color = VColors.Text2, fontSize = 12.sp)
                    Slider(fade, { fade = it.roundToInt().toFloat() }, onValueChangeFinished = { AudioFx.fadeSeconds = fade.roundToInt() },
                        valueRange = 0f..12f, steps = 11, colors = vSlider())
                }
            }

            // ---------------------------------------------------------- output
            item { SectionTitle("Output") }
            item {
                SettingSwitch("Hi-res output", "Keeps 24-bit and high sample-rate files at full resolution (32-bit float). Takes effect after restarting the app.", hiRes) {
                    hiRes = it; AudioFx.hiResOutput = it
                }
            }
            item {
                SettingSwitch("Bit-perfect USB DAC", "Android 14+: sends audio to a USB DAC untouched, at the file's own sample rate. Volume is then set on the DAC. $usb", bitPerfect) {
                    bitPerfect = it; AudioFx.bitPerfect = it
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

private fun hz(h: Int) = if (h >= 1000) String.format(Locale.US, if (h % 1000 == 0) "%.0f kHz" else "%.1f kHz", h / 1000f) else "$h Hz"
