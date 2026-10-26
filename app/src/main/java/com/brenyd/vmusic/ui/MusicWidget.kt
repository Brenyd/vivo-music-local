package com.brenyd.vmusic.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.media3.common.Player
import com.brenyd.vmusic.R
import com.brenyd.vmusic.data.Library
import com.brenyd.vmusic.playback.Ids
import com.brenyd.vmusic.playback.PlaybackService

/** Home-screen widget: cover, title, artist and previous / play-pause / next. */
class MusicWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        push(context, last)
    }

    data class Now(val title: String, val artist: String, val art: String?, val playing: Boolean)

    companion object {
        private var last = Now("Music", "Tap to start", null, false)

        /** Called by the playback service on every song change / play-pause. */
        fun update(ctx: Context, player: Player) {
            val path = player.currentMediaItem?.mediaId?.let { Ids.pathOf(it) }
            val s = path?.let { Library.song(it) }
            val md = player.mediaMetadata
            val now = Now(
                s?.title ?: md.title?.toString() ?: "Music",
                s?.artist ?: md.artist?.toString() ?: "",
                s?.artPath,
                player.isPlaying,
            )
            if (now != last) {
                last = now
                push(ctx, now)
            }
        }

        private fun push(ctx: Context, now: Now) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, MusicWidget::class.java))
            if (ids.isEmpty()) return
            val v = RemoteViews(ctx.packageName, R.layout.widget_player)
            v.setTextViewText(R.id.widget_title, now.title)
            v.setTextViewText(R.id.widget_artist, now.artist)
            v.setImageViewResource(R.id.widget_play, if (now.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
            val bmp = now.art?.let { runCatching { BitmapFactory.decodeFile(it, BitmapFactory.Options().apply { inSampleSize = 4 }) }.getOrNull() }
            if (bmp != null) v.setImageViewBitmap(R.id.widget_art, bmp) else v.setImageViewResource(R.id.widget_art, R.drawable.ic_widget_note)

            val open = PendingIntent.getActivity(ctx, 10,
                Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.widget_root, open)
            v.setOnClickPendingIntent(R.id.widget_art, open)
            v.setOnClickPendingIntent(R.id.widget_prev, mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS, 11))
            v.setOnClickPendingIntent(R.id.widget_play, mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 12))
            v.setOnClickPendingIntent(R.id.widget_next, mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT, 13))
            mgr.updateAppWidget(ids, v)
        }

        /** Media button intent handled by Media3's MediaSessionService (works even when the app is closed). */
        private fun mediaKey(ctx: Context, code: Int, req: Int): PendingIntent {
            val i = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(ctx, PlaybackService::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code))
            return PendingIntent.getForegroundService(ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
