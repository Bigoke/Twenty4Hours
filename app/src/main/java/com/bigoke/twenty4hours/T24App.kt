package com.bigoke.twenty4hours

import android.app.Application
import com.bigoke.twenty4hours.alarm.Notifier
import com.bigoke.twenty4hours.data.Stores
import com.bigoke.twenty4hours.widget.WidgetUpdater

class T24App : Application() {
    override fun onCreate() {
        super.onCreate()
        Stores.init(this)
        Notifier.createChannels(this)
        Stores.onAlarmsChanged = { WidgetUpdater.update(this) }
    }
}
