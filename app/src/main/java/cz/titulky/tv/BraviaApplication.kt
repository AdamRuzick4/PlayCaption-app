package cz.titulky.tv

import android.app.Application

class BraviaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Pady.hlidej(this)
        Diagnostika.start(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Diagnostika.note("onTrimMemory level=$level")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Diagnostika.note("onLowMemory")
    }
}
