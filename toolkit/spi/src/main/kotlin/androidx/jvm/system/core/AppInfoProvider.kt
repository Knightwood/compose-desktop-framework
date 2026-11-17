package androidx.jvm.system.core

import androidx.jvm.system.process.ProcessInfoHelper
import androidx.jvm.system.utils.JvmUtils
import androidx.jvm.system.utils.MutableLazy

/**
 * 应用启动后，将应用信息放到这里，方便其他模块使用
 *
 * 需要在使用[AppPathProvider]之前就初始化
 */
@Deprecated("Use DesktopToolkitBooster instead")
object AppInfoProvider {
    /**
     * 获取应用信息
     */
    fun get(): AppInfo {
        return DesktopToolkitBooster.getApplicationInfo()
    }

    /**
     * 提供应用信息
     */
    fun provide(scope: AppInfo.() -> Unit) {
        val info = AppInfo()
        scope.invoke(info)
        DesktopToolkitBooster.setApplicationInfo(info)
    }

    override fun toString(): String {
        return DesktopToolkitBooster.getApplicationInfo().toString()
    }

}

/**
 * App info
 * @constructor Creates a new AppInfo
 * @property appName the app name
 * @property isDevMode the is dev mode
 * @property appDisplayName the app display name
 */
data class AppInfo(
    var appName: String = "untitled",
    /*  非必须字段  */
    var isDevMode: Boolean = false,
    var appDisplayName: String = appName,
)
