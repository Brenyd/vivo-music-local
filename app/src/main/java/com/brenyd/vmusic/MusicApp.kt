package com.brenyd.vmusic

import android.app.Application
import com.brenyd.vmusic.data.Library

class MusicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Library.init(this)
    }
}
