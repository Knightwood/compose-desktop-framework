package androidx.jvm.system.core

import androidx.jvm.system.process.ProcessInfoHelper
import androidx.jvm.system.utils.MutableLazy

object DesktopToolkitBooster {
    private var appInfo: AppInfo by MutableLazy {
        val processSampleInfo = ProcessInfoHelper.sampleInfo
        val isProcessNameIsJava = ProcessInfoHelper.sampleInfo.isProcessNameIsJava
        AppInfo(
            appName = if (isProcessNameIsJava) "DevJava" else processSampleInfo.processName
        )
    }

    fun getApplicationInfo(): AppInfo = appInfo

    fun setApplicationInfo(appInfo: AppInfo) {
        this.appInfo = appInfo
    }

    fun provideApplicationInfo(scope: AppInfo.() -> Unit) {
        val info = AppInfo()
        scope.invoke(info)
        setApplicationInfo(info)
    }

}
