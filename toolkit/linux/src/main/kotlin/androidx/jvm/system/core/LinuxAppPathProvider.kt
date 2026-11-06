package androidx.jvm.system.core
import androidx.jvm.system.utils.SystemProperty
import androidx.jvm.system.utils.noOptionParent
import com.google.auto.service.AutoService
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import java.nio.file.Paths

/**
 * ```
 * ./opt/AppName/                     ---顶层目录
 * ./opt/AppName/share/doc/           ---copyright文件
 * ./opt/AppName/bin/                 ---exe文件
 * ./opt/AppName/lib/app/             ---jar文件
 * ./opt/AppName/lib/runtime/         ---jdk文件
 * ```
 */
@AutoService(AppBasePathProvider::class)
class LinuxAppPathProvider : AppBasePathProvider {
    override val userHome: Path = Paths.get(SystemProperty["user.home"]!!).toOkioPath()

    override val installPath: Path get() = installedJarPath.noOptionParent.noOptionParent

    override val installedJarPath: Path get() = PathService.getAppJarDirPath()

    override val installedExePath: Path get() = installPath.resolve("bin")

    override val configDirPath: Path get() = getConfPath()

    override val internalConfigDirPath: Path get() = getDataPath()

    /**
     * 配置目录 (~/.config)：存放用户可修改的配置文件。
     * 对应环境变量 $XDG_CONFIG_HOME，默认值就是 ~/.config。
     * 这里放的是程序设置、偏好选项等，用户通常会主动编辑或通过程序界面更改。
     */
    private fun getConfPath(): Path {
        val path = System.getenv("XDG_CONFIG_HOME")
            ?: "${System.getProperty("user.home")}/.config/.${AppInfoProvider.get().appName}"
        return path.toPath()
    }

    /**
     * 数据目录 (~/.local/share)：存放程序自己管理的数据文件。
     * 对应 $XDG_DATA_HOME，默认值是 ~/.local/share。
     * 例如地址簿、数据库、缓存之外的重要应用数据等。这些文件通常由程序读写，用户不应手动干预。
     */
    private fun getDataPath(): Path {
        val path = System.getenv("XDG_DATA_HOME")
            ?: "${System.getProperty("user.home")}/.local/share/.${AppInfoProvider.get().appName}"
        return path.toPath()
    }
}
