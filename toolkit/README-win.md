# toolkit-win — Windows 平台实现

> `toolkit:win` 提供 Windows 下的 `AppBasePathProvider` 实现（`WindowsAppPathProvider`），
> 以及 `PlatformWindowHelper` 的占位实现（`WindowsWindowHelper`）。
> 它们通过 `@AutoService(ModuleProvider::class)` + Koin 的 `named(SystemOs.Windows)` 自动装配，
> 应用侧只写 `getPlatformInstance<AppBasePathProvider>()` 就能拿到。

---

## 目录

- [1. 模块内容](#1-模块内容)
- [2. 环境要求](#2-环境要求)
- [3. 发布到本地 Maven 仓库](#3-发布到本地-maven-仓库)
- [4. 引入依赖](#4-引入依赖)
- [5. 最简示例](#5-最简示例)
- [6. Windows 打包目录布局](#6-windows-打包目录布局)
- [7. 路径计算细节](#7-路径计算细节)
- [8. Koin 注册表](#8-koin-注册表)
- [9. `WindowsWindowHelper` 的现状](#9-windowswindowhelper-的现状)
- [10. 注意细节](#10-注意细节)
- [11. API 参考](#11-api-参考)
- [12. 常见问题](#12-常见问题)

---

## 1. 模块内容

```
toolkit/win
└── src/main/kotlin/androidx/jvm/system
    ├── core/WindowsAppPathProvider.kt        // @AutoService(AppBasePathProvider)
    ├── di/WindowsPlatformServiceProvider.kt  // @AutoService(ModuleProvider)
    └── windows/WindowsWindowHelper.kt        // @AutoService(PlatformWindowHelper)，方法体为空
```

依赖：

```kotlin
implementation(project(":toolkit:spi"))
implementation(libs.jna)               // 预留的 Win32 能力
implementation(libs.koin.core)         // 平台模块自己注册 Koin module
ksp(libs.autoService.ksp)              // 生成 META-INF/services
```

---

## 2. 环境要求

| 项目 | 要求 |
| --- | --- |
| JDK | 17+ |
| Kotlin | 2.2.10（编译期） |
| 运行系统 | Windows 10 / 11（`currentOS == SystemOs.Windows`） |
| 打包方式 | Compose 的 `.msi` / 自建目录布局（见第 6 节） |

> 该模块**只应在 Windows 运行时被选中**。把它打进 cross-platform 包是安全的：
> `named(SystemOs.Windows)` 只有在 `currentOS == Windows` 时才会被 `getPlatformInstance()` 命中。

---

## 3. 发布到本地 Maven 仓库

| 项 | 值 |
| --- | --- |
| groupId | `com.github.knightwood.desktop` |
| artifactId | **`toolkit-win`** |
| version | `1.3.0` |

```bash
./gradlew :toolkit:win:publishToMavenLocal
```

产物：

```
C:\Users\<你>\.m2\repository\com\github\knightwood\desktop\toolkit-win\1.3.0\
```

也可以发到自定义仓库：

```bash
# 先改 gradle.properties: my.maven.url=file:///F:/maven-repo
./gradlew :toolkit:win:publish
```

---

## 4. 引入依赖

```kotlin
dependencies {
    implementation("com.github.knightwood.desktop:toolkit-spi:1.3.0")
    runtimeOnly("com.github.knightwood.desktop:toolkit-win:1.3.0")
}
```

Maven：

```xml
<dependency>
  <groupId>com.github.knightwood.desktop</groupId>
  <artifactId>toolkit-win</artifactId>
  <version>1.3.0</version>
  <scope>runtime</scope>
</dependency>
```

> 为什么是 `runtimeOnly`？因为你在代码里只会引用 `toolkit-spi` 定义的**接口**，
> 平台实现只在运行时通过 `ServiceLoader` 被装配，编译期不需要可见。

---

## 5. 最简示例

```kotlin
import androidx.jvm.system.core.AppBasePathProvider
import androidx.jvm.system.core.AppPathProvider
import androidx.jvm.system.core.DesktopToolkitBooster
import androidx.jvm.system.core.keepDirExist
import androidx.jvm.system.di.InstanceContext
import androidx.jvm.system.di.getPlatformInstance
import androidx.jvm.system.di.startUp

fun main() {
    // 1) 应用名决定 C:\Users\<user>\.<appName>
    DesktopToolkitBooster.provideApplicationInfo { appName = "my-app" }
    InstanceContext.startUp()

    // 2) 直接取 Windows 实现（getPlatformInstance 会带 named(SystemOs.Windows)）
    val win: AppBasePathProvider = getPlatformInstance()

    println("installPath  = ${win.installPath}")
    println("configDir    = ${win.configDirPath}")
    println("internalConf = ${win.internalConfigDirPath}")

    // 3) 通常不需要直接取实现，用统一入口即可
    val configDir = AppPathProvider.provider.configDirPath.keepDirExist()
    configDir.resolve("settings.json").toFile().writeText("""{"lang":"zh"}""")
}
```

在 IDE 里运行时，你会看到路径落在项目目录的 `DebugConfFile/` 下
（`ProviderImpl` 检测到 `build/classes` 后切到 `DebugAppPathProvider`）。
想看真实路径，请打包运行，或显式指定实现：

```kotlin
val win = WindowsAppPathProvider()
println(win.configDirPath)
```

---

## 6. Windows 打包目录布局

源码注释画的结构：

```
/应用名称                ← 应用目录顶层（installPath）
     /app                ← 所有 jar（installedJarPath）
     /app.exe            ← exe 入口
     /runtime            ← Java 运行时
     /conf               ← 内置配置
```

以 `C:\Program Files\MyApp` 为例：

```
C:\Program Files\MyApp\
├── MyApp.exe              ← 启动器
├── app\                   ← jar 包目录
│   ├── app.jar
│   └── lib\*.jar
├── runtime\               ← 内置 JDK
└── .conf\                 ← 内置配置（internalConfigDirPath）
```

用户级配置放在用户目录：

```
C:\Users\<user>\.my-app\   ← configDirPath（可写，随用户走）
├── settings.json
├── lockfile.lock
└── logs\
```

**为什么要分两个配置目录？**

| 目录 | 权限 | 典型内容 |
| --- | --- | --- |
| `configDirPath`（`~\.appName`） | 用户可写 | 用户偏好、窗口位置、最近文件、锁文件、日志 |
| `internalConfigDirPath`（`install\.conf`） | 通常只读 | 内置的默认配置、许可证、版本清单 |

`C:\Program Files` 默认需要管理员权限才能写，因此**绝对不能**把用户数据写进 `installPath`。

---

## 7. 路径计算细节

`WindowsAppPathProvider` 的实现（简化）：

```kotlin
@AutoService(AppBasePathProvider::class)
class WindowsAppPathProvider : AppBasePathProvider {
    override val userHome: Path = SystemProperty["user.home"]!!.toPath()

    override val installPath: Path = PathService.getAppJarDirPath().noOptionParent

    override val installedJarPath: Path = PathService.getAppJarDirPath()

    override val installedExePath: Path = installPath

    override val configDirPath: Path get() = userHome.resolve(".${AppInfoProvider.get().appName}")

    override val internalConfigDirPath: Path = installPath.resolve(".conf")
}
```

逐项说明：

| 字段 | 计算方式 | 说明 |
| --- | --- | --- |
| `userHome` | `System.getProperty("user.home")` | 一般为 `C:\Users\<user>` |
| `installedJarPath` | `PathService.getAppJarDirPath()` | 以 `PathService.anyClass` 所在 jar 的目录为准 |
| `installPath` | `installedJarPath.parent` | 假设 jar 在 `install\app` 下 |
| `installedExePath` | `installPath` | 注意：**不是 `bin` 子目录**，exe 与安装目录同级 |
| `configDirPath` | `userHome\.<appName>` | 依赖 `AppInfo.appName`，每次访问重新计算（`get()`） |
| `internalConfigDirPath` | `installPath\.conf` | 注意是点号开头的 `.conf`，不是注释里的 `conf` |

三个容易踩的点：

1. **`installedExePath == installPath`**：源码里 exe 就是安装目录本身（`MyApp.exe` 在顶层）。
   如果你自建布局把 exe 放在 `bin\`，这里需要自己算：`installPath.resolve("bin")`。
2. **`internalConfigDirPath` 是 `.conf`**（隐藏目录），与注释里的 `/conf` 不一致 —— 以代码为准。
3. **`configDirPath` 是 `get()`**：`AppInfo` 变化会立刻反映；但 `AppPathProvider` 内部缓存的
   `DebugAppPathProvider.configDirPath` 也是 `get()`，两者都实时读 `appName`。
   **仍然建议尽早设置 `appName`**，避免日志/文件散落在 `DevJava` 目录里。

---

## 8. Koin 注册表

```kotlin
@AutoService(ModuleProvider::class)
internal open class WindowsPlatformServiceProvider : ModuleProvider {
    private val idn = named(SystemOs.Windows)

    override fun provide(): Module = module {
        single<AppBasePathProvider>(idn) { WindowsAppPathProvider() }
        single<PlatformWindowHelper>(idn) { WindowsWindowHelper() }
    }
}
```

| 接口 | 限定符 | 实现 | 获取方式 |
| --- | --- | --- | --- |
| `AppBasePathProvider` | `named(SystemOs.Windows)` | `WindowsAppPathProvider` | `getPlatformInstance<AppBasePathProvider>()` |
| `PlatformWindowHelper` | `named(SystemOs.Windows)` | `WindowsWindowHelper` | `getPlatformInstance<PlatformWindowHelper>()` |

显式按平台取（当你在非 Windows 上做测试时）：

```kotlin
val winPaths = InstanceContext.get().get<AppBasePathProvider>(named(SystemOs.Windows))
```

> `internal open class` 的意义：KSP 生成的 `META-INF/services` 需要可实例化的类，
> `open` 便于测试时继承覆盖；`internal` 表示不对外暴露（它是纯装配细节）。

---

## 9. `WindowsWindowHelper` 的现状

```kotlin
@AutoService(PlatformWindowHelper::class)
open class WindowsWindowHelper : PlatformWindowHelper {
    override fun bringToFront(desc: WindowDesc) {
        // 空实现
    }
}
```

也就是说：

- `bringToFront(...)` 调用后**什么也不会发生**；
- `bringToBack(...)` / `close(...)` 用的是接口默认空实现；
- 目的：先把 SPI 形态定下来（`WindowDesc` 只需要窗口标题），具体 Win32 调用
  （`FindWindow` + `SetForegroundWindow` + `ShowWindow`）后续用 JNA 补。

`toolkit:win` 已经依赖 `jna`，补实现时大致是：

```kotlin
// 思路示意，仓库中尚未实现
val hwnd = User32.INSTANCE.FindWindow(null, desc.title)
if (hwnd != null) {
    User32.INSTANCE.ShowWindow(hwnd, User32.SW_RESTORE)
    User32.INSTANCE.SetForegroundWindow(hwnd)
}
```

源码里已经有 `androidx.jvm.system.win32.User32` 的 Java 接口定义，可以直接用。

---

## 10. 注意细节

1. **`AppInfoProvider.get()` 已被废弃但仍在此模块内使用**
   `WindowsAppPathProvider.configDirPath` 调的是 `AppInfoProvider.get()`，
   它内部转发到 `DesktopToolkitBooster.getApplicationInfo()`。
   行为一致，但新代码请直接用 `DesktopToolkitBooster`。

2. **路径依赖 `PathService.anyClass`**
   `installPath` 从「某个类的 jar 所在目录」推导。若 `anyClass` 实际来自 `~/.m2`，
   安装目录会算成 Maven 仓库里的路径。应用启动时请显式设置：

   ```kotlin
   PathService.anyClass = MyAppMain::class.java
   ```

3. **Debug 判定在 Windows 上最可靠**
   `isProcessNameIsJava` 检查的是 `command.contains("bin\\java")`，
   正好匹配 Windows 的 `...\bin\java.exe`。所以 IDE 里运行基本一定被判为 Debug。

4. **`configDirPath` 不创建目录**
   需要自己 `keepDirExist()`，否则写文件会抛 `FileNotFoundException`。

5. **不要把 `appName` 设成中文路径非法字符**
   `~\.<appName>` 是真实目录名，避免 `\ / : * ? " < > |`。

6. **`installPath` 假设 jar 在 `install\app`**
   如果你把 jar 平铺在安装目录（`install\*.jar`），`installPath` 会变成 `install` 的父目录 ——
   这时请自定义 `AppBasePathProvider`，或改用 `getInstance(customProvider)`。

7. **`userHome` 用 `okio.Path`**
   与 `java.io.File` / `java.nio.file.Path` 互转：`toFile()` / `toNioPath()`。

---

## 11. API 参考

| 符号 | 类型 | 说明 |
| --- | --- | --- |
| `WindowsAppPathProvider` | `class : AppBasePathProvider` | 6 个路径实现 |
| `userHome` | `okio.Path` | `System.getProperty("user.home")` |
| `installPath` | `okio.Path` | jar 目录的父目录 |
| `installedJarPath` | `okio.Path` | `PathService.getAppJarDirPath()` |
| `installedExePath` | `okio.Path` | 等于 `installPath` |
| `configDirPath` | `okio.Path`（getter） | `~\.<appName>` |
| `internalConfigDirPath` | `okio.Path` | `installPath\.conf` |
| `WindowsPlatformServiceProvider` | `ModuleProvider` | 注册上面两个实现 |
| `WindowsWindowHelper` | `PlatformWindowHelper` | 空实现（占位） |

---

## 12. 常见问题

**Q1：打包后 `configDirPath` 变成了 `C:\Users\xxx\AppData\Roaming\...`？**

不会。这里只认 `user.home`。如果你看到 `AppData`，说明你在用别的库（或者自己拼了路径）。

**Q2：`installPath` 指向了 `.m2` 仓库。**

`PathService.anyClass` 落到了从 Maven 仓库加载的类上。显式设置成自己的主类即可。

**Q3：`configDirPath` 目录名是 `DevJava`。**

`appName` 没设置，且进程名是 `java`。启动最早期调用：

```kotlin
DesktopToolkitBooster.provideApplicationInfo { appName = "my-app" }
```

**Q4：`internalConfigDirPath` 是 `.conf` 还是 `conf`？**

代码是 `.conf`（隐藏目录）。Compose 打包默认生成的也是隐藏目录约定，二者一致。

**Q5：`bringToFront` 没用。**

当前是空实现。需要真正的前置窗口，请自行用 JNA 调 `User32`（仓库已定义 `User32` 接口），
或等待后续版本补全。

**Q6：能不能在 Linux 上跑，用 Windows 的路径实现做测试？**

可以：

```kotlin
val win = InstanceContext.get().get<AppBasePathProvider>(named(SystemOs.Windows))
```

但 `PathService.getAppJarDirPath()` 会用当前运行环境的类路径，得到的路径是「Linux 风格套 Windows 布局」，
仅适合做单元测试断言，不代表真实部署结果。
