package sh.paseo.android

import android.app.Application
import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

class PaseoApplication : Application(), ViewModelStoreOwner, Application.ActivityLifecycleCallbacks {
    override val viewModelStore = ViewModelStore()
    private val activities = mutableSetOf<Activity>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {
        activities += activity
    }

    override fun onActivityDestroyed(activity: Activity) {
        activities -= activity
        if (activities.isEmpty() && !activity.isChangingConfigurations) {
            mainHandler.postDelayed({
                if (activities.isEmpty()) viewModelStore.clear()
            }, 500)
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
}
