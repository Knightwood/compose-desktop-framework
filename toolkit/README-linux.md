# toolkit-linux — Linux 平台实现

> `toolkit:linux` 提供 Linux 下的 `AppBasePathProvider` 实现（`LinuxAppPathProvider`）。
> 它遵循 **FHS**（`/opt/<AppName>`）与 **XDG Base Directory** 规范
> （`XDG_CONFIG_HOME` / `XDG_DATA_HOME`），并通过 `@AutoService` + Koin 自动装配。

---

## 目录

- [1. 模块内容](#1-模块内容)
- [2. 环境要求](#2-环境要求)
- [3. 发布到本地 Maven 仓库](#3-发布到本地-maven-仓库)
- [4. 引入依赖](#4-引入依赖)
- [5. 最简示例](#5-最简示例)
- [6. Linux 打包目录布局](#6-linux-打包目录布局)
- [7. 路径计算细节（含 XDG 的已知缺陷）](#7-路径计算细节含-xdg-的已知缺陷)
- [8. Koin 注册表](#8-koin-注册表)
- [9. 桌面集成（.desktop / 单实例 / 自启）](#9-桌面集成desktop--单实例--自启)
- [10. 注意细节](#10-注意细节)
- [11. API 参考](#11-api-参考)
- [12. 常见问题](#12-常见问题)

---

## 1. 模块内容

```
toolkit/linux
└── src/main/kotlin/androidx/jvm/system
    ├── core/LinuxAppPathProvider.kt        // @AutoService(AppBasePathProvider)
    ├── di/LinuxPlatformServiceProvider.kt  // @AutoService(ModuleProvider)
    └── ui/                                 // 预留（当前为空）
```

相比 Windows 模块，它**没有** `PlatformWindowHelper` 的实现 ——
所以 Linux 上 `getPlatformInstance<PlatformWindowHelper>()` 会抛 `NoDefinitionFoundException`。

---

## 2. 环境要求

| 项目 | 要求 |
| --- | --- |
| JDK | 17+ |
| Kotlin | 2.2.10（编译期） |
| 运行系统 | 任意现代发行版（`currentOS == SystemOs.Linux`） |
| 桌面环境 | 需要 X11 / Wayland 会话才能显示窗口（`DISPLAY` / `WAYLAND_DISPLAY`） |
| 打包方式 | Compose 的 `.deb` / `.rpm`，或自定义 `/opt/<AppName>` 布局 |

> **无桌面环境的服务器**上仍可使用 `toolkit:linux` 的路径与进程能力，
> 但 `FileUtils.openFile(...)` 之类会返回 `false`，Swing/Compose 窗口无法创建。

---

## 3. 发布到本地 Maven 仓库

| 项 | 值 |
| --- | --- |
| groupId | `com.github.knightwood.desktop` |
| artifactId | **`toolkit-linux`** |
| version | `1.3.0` |

```bash
./gradlew :toolkit:linux:publishToMavenLocal
```

产物：

```
~/.m2/repository/com/github/knightwood/desktop/toolkit-linux/1.3.0/
```

---

## 4. 引入依赖

```kotlin
dependencies {
    implementation("com.github.knightwood.desktop:toolkit-spi:1.3.0")
    runtimeOnly("com.github.knightwood.desktop:toolkit-linux:1.3.0")

    // 窗口/文件/日志等仍需自行准备（spi 已传递引入大部分）
    runtimeOnly("ch.qos.logback:logback-classic:1.5.12")
}
```

Maven：

```xml
<dependency>
  <groupId>com.github.knightwood.desktop</groupId>
  <artifactId>toolkit-linux</artifactId>
  <version>1.3.0</version>
  <scope>runtime</scope>
</dependency>
```

---

## 5. 最简示例

```kotlin
import androidx.jvm.system.core.AppPathProvider
import androidx.jvm.system.core.DesktopToolkitBooster
import androidx.jvm.system.core.keepDirExist
import androidx.jvm.system.di.InstanceContext
import androidx.jvm.system.di.startUp
import androidx.jvm.system.process.ProcessLocker
import androidx.jvm.system.utils.currentOS

fun main() {
    DesktopToolkitBooster.provideApplicationInfo { appName = "my-app" }
    InstanceContext.startUp()

    println("currentOS = $currentOS")   // Linux

    val paths = AppPathProvider.provider
    println("installPath  = ${paths.installPath}")
    println("configDir    = ${paths.configDirPath}")
    println("internalConf = ${paths.internalConfigDirPath}")

    val configDir = paths.configDirPath.keepDirExist()
    try {
        ProcessLocker.lock(configDir.resolve("lockfile.lock").toNioPath())
    } catch (e: RuntimeException) {
        println("已有实例在运行")
        return
    }

    // 用系统默认程序打开配置目录（xdg-open）
    androidx.jvm.system.utils.osfileutil.FileUtils.openFolder(configDir.toFile())
}
```

典型输出（打包安装后）：

```
installPath  = /opt/my-app
configDir    = /home/junya/.config/.my-app
internalConf = /home/junya/.local/share/.my-app
```

---

## 6. Linux 打包目录布局

源码注释给出的结构：

```
./opt/AppName/                 ← 顶层目录（installPath）
./opt/AppName/share/doc/       ← copyright / 文档
./opt/AppName/bin/             ← 可执行文件 / 启动脚本
./opt/AppName/lib/app/         ← jar 文件（installedJarPath）
./opt/AppName/lib/runtime/     ← 内置 JDK
```

实际安装后：

```
/opt/my-app/
├── bin/
│   └── my-app                  ← 启动脚本
├── lib/
│   ├── app/
│   │   ├── app.jar
│   │   └── *.jar
│   └── runtime/                ← 内置 JRE
└── share/
    └── doc/
        └── copyright
```

用户数据（XDG 规范）：

```
~/.config/.my-app/                  ← configDirPath（用户可写配置）
~/.local/share/.my-app/             ← internalConfigDirPath（程序自管理数据）
```

> **注意**：这里的 `internalConfigDirPath` 在 Linux 上不是「安装目录下的 conf」，
> 而是 `$XDG_DATA_HOME`（默认 `~/.local/share`）—— 与 Windows/macOS 的语义不同。
> 因为 `/opt` 通常需要 root 权限，Linux 生态约定程序数据放 XDG 目录。

---

## 7. 路径计算细节（含 XDG 的已知缺陷）

```kotlin
@AutoService(AppBasePathProvider::class)
class LinuxAppPathProvider : AppBasePathProvider {
    override val userHome: Path = Paths.get(SystemProperty["user.home"]!!).toOkioPath()

    override val installPath: Path get() = installedJarPath.noOptionParent.noOptionParent
    override val installedJarPath: Path get() = PathService.getAppJarDirPath()
    override val installedExePath: Path get() = installPath.resolve("bin")

    override val configDirPath: Path get() = getConfPath()
    override val internalConfigDirPath: Path get() = getDataPath()

    private fun getConfPath(): Path {
        val path = System.getenv("XDG_CONFIG_HOME")
            ?: "${System.getProperty("user.home")}/.config/.${AppInfoProvider.get().appName}"
        return path.toPath()
    }

    private fun getDataPath(): Path {
        val path = System.getenv("XDG_DATA_HOME")
            ?: "${System.getProperty("user.home")}/.local/share/.${AppInfoProvider.get().appName}"
        return path.toPath()
    }
}
```

| 字段 | 计算方式 | 备注 |
| --- | --- | --- |
| `installedJarPath` | `PathService.getAppJarDirPath()` | 通常 `/opt/my-app/lib/app` |
| `installPath` | `installedJarPath.parent.parent` | `lib/app` → `lib` → `/opt/my-app` |
| `installedExePath` | `installPath/bin` | 与 Windows 不同，这里是 `bin` 子目录 |
| `configDirPath` | `$XDG_CONFIG_HOME` 或 `~/.config/.<appName>` | **见下方缺陷说明** |
| `internalConfigDirPath` | `$XDG_DATA_HOME` 或 `~/.local/share/.<appName>` | 同上 |

### ⚠️ 已知缺陷：设置 XDG 变量后丢失应用名

```kotlin
val path = System.getenv("XDG_CONFIG_HOME") ?: "$userHome/.config/.$appName"
```

当 `XDG_CONFIG_HOME` **存在**时，直接返回该变量本身，**没有拼接 `.appName`**。
后果：

| 环境 | `configDirPath` |
| --- | --- |
| 未设置 `XDG_CONFIG_HOME` | `~/.config/.my-app` ✅ |
| 设置了 `XDG_CONFIG_HOME=/home/u/.config` | `/home/u/.config` ❌（所有应用共用同一目录） |

`XDG_DATA_HOME` 同理。

**规避方式（三选一）**：

1. 不设置这两个环境变量（多数桌面发行版默认就不设置）；
2. 应用层自己再降一层：

   ```kotlin
   val appConfig = paths.configDirPath.resolve(appName).keepDirExist()
   ```
3. 自己实现 `AppBasePathProvider` 并用 `AppPathProvider.getInstance(custom)` 覆盖。

由于 `XDG_CONFIG_HOME` 在 GNOME/KDE 等环境里**经常被显式设置**，生产项目建议直接采用方案 2/3。

---

## 8. Koin 注册表

```kotlin
@AutoService(ModuleProvider::class)
internal open class LinuxPlatformServiceProvider : ModuleProvider {
    override fun provide(): Module = module {
        single<AppBasePathProvider>(named(SystemOs.Linux)) { LinuxAppPathProvider() }
    }
}
```

| 接口 | 限定符 | 实现 |
| --- | --- | --- |
| `AppBasePathProvider` | `named(SystemOs.Linux)` | `LinuxAppPathProvider` |
| `PlatformWindowHelper` | — | **无**（取用会抛 `NoDefinitionFoundException`） |

显式取：

```kotlin
val linuxPaths = InstanceContext.get().get<AppBasePathProvider>(named(SystemOs.Linux))
```

---

## 9. 桌面集成（.desktop / 单实例 / 自启）

`toolkit` 不负责打包 `.deb`，但下面这些是 Linux 上必需的手工工作。

### 9.1 桌面快捷方式

`/usr/share/applications/my-app.desktop`（或 `~/.local/share/applications/`）：

```ini
[Desktop Entry]
Type=Application
Name=My App
Comment=Compose Desktop demo
Exec=/opt/my-app/bin/my-app %U
Icon=/opt/my-app/share/icons/my-app.png
Terminal=false
Categories=Utility;
StartupWMClass=my-app
```

`StartupWMClass` 要和窗口的 WM_CLASS 一致，否则任务栏会显示成两个图标。

### 9.2 单实例

```kotlin
val lockFile = AppPathProvider.provider
    .configDirPath.keepDirExist()
    .resolve("lockfile.lock")
    .toNioPath()

runCatching { ProcessLocker.lock(lockFile) }
    .onFailure {
        // 想唤起已有窗口，可在拿到窗口标题后用平台能力实现；
        // 当前 toolkit 没有 Linux 的 PlatformWindowHelper 实现，
        // 也可以简单地提示用户后退出。
        System.err.println("My App is already running")
        return
    }
```

### 9.3 开机自启（需自行实现 `AppStartUpService`）

XDG 约定的做法是写一个 `.desktop` 到 autostart 目录：

```
~/.config/autostart/my-app.desktop
```

内容与 9.1 相同（可加 `X-GNOME-Autostart-enabled=true`）。
实现 `AppStartUpService` 后用 `@AutoService(AppStartUpService::class)` 注册，即可被
`getAutoStartService()` 找到。

---

## 10. 注意细节

1. **`internalConfigDirPath` 语义与 Windows/macOS 不同**
   Linux 上它指向 XDG data 目录，不是安装目录。代码里若假设「它在 installPath 下」会出错。

2. **XDG 变量会导致目录丢失应用名**（见第 7 节）。

3. **不会自动创建目录**
   `configDirPath` 只是拼字符串，写入前必须 `keepDirExist()`。
   （macOS 的实现会自动 `createDirectories`，Linux 不会。）

4. **`installPath` 假设 jar 在 `lib/app`**
   如果你的布局是 `lib/*.jar`，父目录只有一层，`installPath` 会少一级。
   请自定义实现或调整打包脚本。

5. **目录名以 `.` 开头**
   `~/.config/.my-app` 是**隐藏目录**（`ls` 默认看不到）。这是为了让 `~/.config` 保持整洁，
   但和多数 Linux 应用的约定（`~/.config/my-app`，无前导点）不同。
   如果你在意规范一致性，自己在应用层改。

6. **`PlatformWindowHelper` 无实现**
   `getPlatformInstance<PlatformWindowHelper>()` 在 Linux 上必抛异常。用前判：
   `InstanceContext.getOrNull()?.getOrNull<PlatformWindowHelper>(named(currentOS))`。

7. **`FileUtils` 依赖 `xdg-open`**
   `openFile` / `openFolder` 在极简桌面环境（如纯 WM）可能失败，返回 `false`。

8. **没有 `getWindowsVersion` 之类**
   Linux 发行版信息请自己读 `/etc/os-release`；`SystemProperty.javaVer()` 返回的是 `os.version`
   （内核版本），不是 JVM 版本。

9. **`currentOS` 只认 `Linux` 前缀**
   `os.name` 在 Linux 上恒为 `Linux`，不会带发行版名。要识别发行版请读 `os-release`。

10. **Debug 判定在 Linux 上依赖路径正则**
    `ProcessSampleInfo.isProcessNameIsJava` 是 `command.contains("bin\\java")`（Windows 分隔符），
    在 Linux 上恒为 `false`。IDE 里能正确切到 `DebugAppPathProvider`，
    靠的是 `Regex("build[\\\\/](libs|classes)")` 命中 `build/classes`。

---

## 11. API 参考

| 符号 | 类型 | 说明 |
| --- | --- | --- |
| `LinuxAppPathProvider` | `class : AppBasePathProvider` | Linux 路径实现 |
| `userHome` | `okio.Path` | `~` |
| `installPath` | `okio.Path`（getter） | `/opt/<app>`（jar 目录上两级） |
| `installedJarPath` | `okio.Path`（getter） | `/opt/<app>/lib/app` |
| `installedExePath` | `okio.Path`（getter） | `/opt/<app>/bin` |
| `configDirPath` | `okio.Path`（getter） | `$XDG_CONFIG_HOME` 或 `~/.config/.<app>` |
| `internalConfigDirPath` | `okio.Path`（getter） | `$XDG_DATA_HOME` 或 `~/.local/share/.<app>` |
| `LinuxPlatformServiceProvider` | `ModuleProvider` | 注册 `AppBasePathProvider` |

---

## 12. 常见问题

**Q1：`configDirPath` 变成了 `/home/u/.config`（没有应用名）。**

`XDG_CONFIG_HOME` 被设置了。参考第 7 节的规避方式。

**Q2：`installPath` 算出来是 `/opt` 而不是 `/opt/my-app`。**

jar 不在 `lib/app` 下。检查打包脚本，或自定义 `AppBasePathProvider`。

**Q3：`NoDefinitionFoundException: PlatformWindowHelper`**

Linux 模块没实现它。要么不用，要么自己实现并注册。

**Q4：`ProcessLocker` 报「已有实例在运行」，但 `ps` 看不到进程。**

锁可能被上一个异常退出的进程持有（僵尸/未释放句柄）。`kill -9` 掉残留进程即可；
锁文件本身不需要删除。

**Q5：`FileUtils.openFile` 返回 false。**

headless 环境，或没有 `xdg-open` / `gio`。可以 fallback 到
`Desktop.getDesktop().open(file)`（同样依赖桌面环境）。

**Q6：Wayland 下窗口位置/置顶无效。**

Wayland 出于安全设计不允许应用全局控制窗口。`toFront()`、`setLocation` 等可能被忽略，
这是平台限制而非框架问题。
