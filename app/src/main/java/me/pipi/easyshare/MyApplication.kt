package me.pipi.easyshare

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import me.pipi.easyshare.utils.INTERNAL_BROADCAST_PERMISSION
import me.pipi.easyshare.utils.NotificationUtils
import me.pipi.easyshare.utils.ServiceState
import me.pipi.easyshare.utils.TAG
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MyApplication : Application() {
    private val isBusy = AtomicBoolean()
    private val visibleActivityCount = AtomicInteger()

    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationUtils.createChannels(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit

            override fun onActivityStarted(activity: Activity) {
                visibleActivityCount.incrementAndGet()
            }

            override fun onActivityStopped(activity: Activity) {
                visibleActivityCount.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
            }
        })
    }

    fun setBusy() = if (isBusy.compareAndSet(false, true)) {
        Log.i(TAG, "Setting busy flag")
        sendBroadcast(
            ServiceState.getBusyChangedIntent(true),
            INTERNAL_BROADCAST_PERMISSION
        )
        true
    } else {
        false
    }

    fun clearBusy() {
        Log.i(TAG, "Clearing busy flag")
        isBusy.set(false)
        sendBroadcast(
            ServiceState.getBusyChangedIntent(false),
            INTERNAL_BROADCAST_PERMISSION
        )
    }

    fun getBusy() = isBusy.get()

    fun hasVisibleActivity() = visibleActivityCount.get() > 0

    companion object {
        const val ACTION_BUSY_CHANGED = "me.pipi.easyshare.BUSY_CHANGED"

        private var instance: MyApplication? = null
        fun getInstance() = instance!!
    }
}
