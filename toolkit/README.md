# toolkit — 桌面平台能力工具箱

> `toolkit` 解决的是「写桌面应用时，总要重复造的那些轮子」：
> 应用装在哪、配置该写哪个目录、怎么保证同一时刻只跑一个实例、
> 怎么按操作系统拿到不同实现、怎么把窗口提到前台、怎么开机自启……
>
> 它的结构非常清晰：**`toolkit:spi` 定义接口与基础设施，`toolkit:win` / `linux` / `mac` 提供平台实现，
> 通过 Koin + `ServiceLoader` 在运行时自动装配。**

---

## 文档地图

本目录下每个模块都有一份独立文档：

| 文档 | 内容 |
| --- | --- |
| **本文（`toolkit/README.md`）** | 模块划分、环境要求、发布与依赖、最简示例、SPI 总体设计、跨平台差异、常见问题 |
| [`README-spi.md`](./README-spi.md) | `toolkit:spi` 全部 API：`InstanceContext`、`AppPathProvider`、`SystemOs`、`ProcessLocker`、`FileUtils`、`PlatformWindowHelper`…… |
| [`README-win.md`](./README-win.md) | Windows 实现：目录布局、路径计算、Koin 注册、已知限制 |
| [`README-linux.md`](./README-linux.md) | Linux 实现：`/opt` 布局、XDG 规范、路径计算 |
| [`README-mac.md`](./README-mac.md) | macOS 实现：`.app` 布局、`Application Support`、依赖的 system property |

> 旧版 [`toolkit/spi/readme.md`](./spi/readme.md) 记录的是早期设计（`Platform` 枚举 + `getPlatformInstance`），
> 现已被 `SystemOs` + `InstanceContext` 取代，本组文档为最新版本。

---

## 目录

- [1. 模块一览](#1-模块一览)
- [5. 最简示例](#5-最简示例)
- [6. 设计原理：SPI + 独立 Koin 容器](#6-设计原理spi--独立-koin-容器)
- [7. 核心能力速览](#7-核心能力速览)
- [8. 三平台目录布局对比](#8-三平台目录布局对比)
- [9. 注意细节](#9-注意细节)
- [10. 常见问题（FAQ）](#10-常见问题faq)

---

## 1. 模块一览

```
toolkit
├── spi      ← 接口 + 基础设施（InstanceContext / SystemOs / AppPathProvider / ProcessLocker / FileUtils ...）
├── win      ← WindowsAppPathProvider + WindowsPlatformServiceProvider（+ WindowsWindowHelper 占位）
├── linux    ← LinuxAppPathProvider + LinuxPlatformServiceProvider
└── mac      ← MacosAppPathProvider + MacPlatformServiceProvider
```

依赖方向（**单向**，平台模块只依赖 spi，spi 不知道任何平台模块的存在）：

```
toolkit:win   ┐
toolkit:linux ├──► toolkit:spi
toolkit:mac   ┘
                   ▲
                   │
        framework:common / framework:compose / framework:swing
```

各模块职责：

| 模块 | 职责 | 是否必须 |
| --- | --- | --- |
| `toolkit:spi` | 定义平台无关接口；提供 Koin 容器（`InstanceContext`）；提供系统探测、进程锁、文件工具 | 必须 |
| `toolkit:win` | `named(SystemOs.Windows)` 的 `AppBasePathProvider`（+ `PlatformWindowHelper`） | Windows 下需要 |
| `toolkit:linux` | `named(SystemOs.Linux)` 的 `AppBasePathProvider` | Linux 下需要 |
| `toolkit:mac` | `named(SystemOs.MacOS)` 的 `AppBasePathProvider` | macOS 下需要 |

三个平台模块可以**同时**进 classpath，运行时只按 `currentOS` 取对应实现 —— 这正是 SPI 的价值。

---

## 5. 最简示例

### 5.1 启动容器并打印所有平台路径

```kotlin
import androidx.jvm.system.core.AppPathProvider
import androidx.jvm.system.di.InstanceContext
import androidx.jvm.system.di.startUp

fun main() {
    // 1) 启动独立 Koin 容器，并自动加载所有 ModuleProvider（平台模块就是靠它被装配进来的）
    InstanceContext.startUp()

    // 2) 取路径提供者。Debug（IDE 中运行）会自动切到 DebugAppPathProvider
    val paths = AppPathProvider.getInstance()

    paths.print()   // 会打印用户目录 / 安装目录 / jar 目录 / exe 目录 / 配置目录
}
```

控制台输出大致是：

```
 用户目录: C:\Users\junya
 安装路径: C:\Program Files\MyApp
 程序jar包路径: C:\Program Files\MyApp\app
 exe文件路径: C:\Program Files\MyApp
 配置目录路径: C:\Users\junya\.MyApp
 内部配置目录路径: C:\Program Files\MyApp\.conf
```

### 5.2 写配置、开目录

```kotlin
import androidx.jvm.system.core.AppPathProvider
import androidx.jvm.system.core.keepDirExist
import androidx.jvm.system.utils.osfileutil.FileUtils
import java.io.File

fun main() {
    InstanceContext.startUp()

    val cfgDir = AppPathProvider.getInstance().configDirPath.keepDirExist()
    val cfgFile = cfgDir.resolve("settings.json").toFile()
    cfgFile.writeText("""{"theme":"dark"}""")

    // 用系统默认程序打开配置文件所在目录（跨平台）
    FileUtils.openFolderOfFile(cfgFile)

    // 用系统默认程序打开文件
    FileUtils.openFile(cfgFile)
}
```

### 5.3 保证单实例运行

```kotlin
import androidx.jvm.system.core.AppPathProvider
import androidx.jvm.system.core.keepDirExist
import androidx.jvm.system.process.ProcessLocker
import androidx.jvm.system.di.InstanceContext
import androidx.jvm.system.di.startUp

fun main() {
    InstanceContext.startUp()

    val lockFile = AppPathProvider.getInstance()
        .configDirPath
        .keepDirExist()
        .resolve("lockfile.lock")
        .toNioPath()

    try {
        ProcessLocker.lock(lockFile)      // 非阻塞；拿不到锁直接抛 RuntimeException
    } catch (e: RuntimeException) {
        // 已经有实例在跑：常见做法是提示用户后退出，或唤起已有窗口
        System.err.println("应用已在运行")
        return
    }

    // 4) 正常启动 UI
    startUi()
}
```

`ProcessLocker.lock` 是**非阻塞**的（内部 `FileChannel.tryLock()`），不需要放进子线程。
锁会在进程结束时由操作系统自动释放，所以大多数情况不用手动 `unlock()`。

### 5.4 在 Compose 桌面应用里配合使用

`composeApp/src/desktopMain/kotlin/com/github/knightwood/example/Main.kt` 就是完整范例：

```kotlin
fun main() = application {
    // 设置应用名（会影响 configDirPath 里的目录名）
    DesktopToolkitBooster.provideApplicationInfo {
        appName = "compose-desktop"
        appDisplayName = "Compose Desktop"
    }
    // 启动框架（内部会 InstanceContext.startUp + 注册 ModuleProvider 提供的模块）
    Singularity.boot()
    ...
}
```

---

## 6. 设计原理：SPI + 独立 Koin 容器

### 6.1 三个角色

```
        ┌───────────────────────────────┐
        │  spi: 接口 + 独立 Koin 容器    │
        │  InstanceContext / ModuleProvider │
        └───────────────┬───────────────┘
                        │ ServiceLoader.load(ModuleProvider::class.java)
        ┌───────────────┴───────────────┐
        │ win / linux / mac             │
        │ @AutoService(ModuleProvider)  │
        │ single<X>(named(SystemOs.X))  │
        └───────────────────────────────┘
```

1. **接口定义在 spi**：`AppBasePathProvider`、`PlatformWindowHelper`、`SystemWindowManager`、`AppStartUpService`。
2. **实现在平台模块**：每个平台模块提供一个 `ModuleProvider`，用 `@AutoService` 生成
   `META-INF/services/androidx.jvm.system.di.ModuleProvider`。
3. **装配在运行时**：`InstanceContext.startKoin()` 里 `ServiceLoader.load(ModuleProvider::class.java)`
   遍历所有实现，把它们的 Koin `Module` 全部加载进来。

### 6.2 为什么 Koin 要「独立」而不是用 `startKoin()`

```kotlin
object InstanceContext : KoinContext {
    private var _koin: Koin? = null
    private var _koinApplication: KoinApplication? = null

    override fun get(): Koin = _koin ?: error("KoinApplication has not been started")

    override fun startKoin(appDeclaration: KoinAppDeclaration): KoinApplication = synchronized(this) {
        if (_koin != null) throw KoinApplicationAlreadyStartedException(...)
        val koinApplication = KoinApplication.init()
        appDeclaration(koinApplication)
        return startKoin(koinApplication)
    }
}
```

Koin 官方的 `startKoin { }` 会把实例登记到全局单例（`GlobalContext`），
在同一个 JVM 里只能有一个，且会被所有库共享 —— 这对「被用作依赖库」的框架非常危险：
宿主项目自己也可能用 Koin，双方会互相污染。

于是 `toolkit:spi` 自己实现 `KoinContext`，**在 `InstanceContext` 内部持有 `_koinApplication` 与 `_koin`**，
完全绕开全局上下文。由此带来的约定是：

- 必须用 `InstanceContext.startUp()` / `InstanceContext.startKoin()` 启动；
- 取实例时用 `InstanceKoinComponent`（它的 `getKoin()` 返回 `InstanceContext.get()`），
  而不是 Koin 默认的 `KoinComponent`；
- `stopKoin()` 只是 `_koin?.close()` 并把 `_koin` 置空，`_koinApplication` 仍保留（便于排查）。

### 6.3 为什么要给实例加「平台限定符」

三种平台实现可以同时存在于 classpath，如果都注册成
`single<AppBasePathProvider> { ... }`，Koin 会因重复定义而报错（或需要 `override`）。
所以每个平台模块都用 `named(SystemOs.X)` 作限定符：

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

取实例时用 spi 提供的语法糖，自动带上当前平台限定符：

```kotlin
inline fun <reified T : Any> getPlatformInstance(): T =
    InstanceContext.get().get<T>(qualifier = named(currentOS))
```

注意限定符是**枚举实例**，不是字符串：`named(SystemOs.Windows)` 与 `named("Windows")` 不是同一个 key。

### 6.4 `@AutoService` 与 KSP

`@AutoService(ModuleProvider::class)` 由 `com.google.auto.service` 的 KSP 处理器在编译期读取，
生成：

```
META-INF/services/androidx.jvm.system.di.ModuleProvider
    androidx.jvm.system.di.WindowsPlatformServiceProvider
```

这也是为什么**平台实现必须作为运行时依赖**存在：没有 `META-INF/services` 文件，
`ServiceLoader` 就什么都找不到。

### 6.5 Debug 与 Release 的路径分叉

`AppPathProvider.getInstance()` 的默认实现是 `ProviderImpl`：

```kotlin
internal val ProviderImpl by lazy {
    val path = PathService.getAppJarDirPath().toFile().absolutePath
    val matches = Regex("build[\\\\/](libs|classes)").containsMatchIn(path)
    val isProcessNameIsJava = ProcessInfoHelper.sampleInfo.isProcessNameIsJava
    if (matches || isProcessNameIsJava) {
        DebugAppPathProvider()          // IDE 里运行
    } else {
        getPlatformInstance<AppBasePathProvider>()   // 打包后的真实安装目录
    }
}
```

判据有两条，满足其一即为 Debug：

1. jar 所在目录路径包含 `build/libs` 或 `build/classes`；
2. 进程名是 `java`（`java -jar` 或 IDE 启动）。

这带来一个非常实用的效果：**开发时配置写在项目目录下**（`DebugConfFile/`），
不会污染你真实的 `~/.appName`；打包后自动切到平台路径。

---

## 7. 核心能力速览

下面列出最常用的 API，详细说明见 [`README-spi.md`](./README-spi.md)。

| 能力 | 入口 | 说明 |
| --- | --- | --- |
| 启动容器 | `InstanceContext.startUp()` | 也可传 Koin 声明块加载自己的模块 |
| 取实例 | `get<T>()` / `inject<T>()`（`InstanceKoinComponent`） | 从独立容器取 |
| 取平台实例 | `getPlatformInstance<T>()` | 自动加 `named(currentOS)` |
| 路径 | `AppPathProvider.getInstance()` | 安装目录、配置目录、jar 目录…… |
| 应用信息 | `DesktopToolkitBooster.getApplicationInfo()` | 应用名会影响配置目录名 |
| 系统识别 | `currentOS` / `currentArch` / `currentOsAndArch` | `SystemOs.Windows/Linux/MacOS` |
| 单实例 | `ProcessLocker.lock(path)` | 基于 `FileChannel.tryLock` |
| 打开文件/目录 | `FileUtils.openFile(...)` | 按平台选择实现 |
| 窗口控制 | `getPlatformInstance<PlatformWindowHelper>()` | 提到前台/后置/关闭（当前为占位实现） |
| 开机自启 | `getAutoStartService()` | 接口已定义，尚未提供实现 |
| 进程信息 | `ProcessInfoHelper.sampleInfo` | 命令行、PID、主类名 |
| JDK 信息 | `jdkInfo()` | vendor / version / home / arch |
| 执行命令 | `execAndWait(arrayOf("cmd", "/c", "dir"))` | 带超时，返回是否成功 |

---

## 8. 三平台目录布局对比

三个平台模块的注释里都画了打包后的目录结构。下面是汇总（以应用名 `MyApp` 为例）：

| 路径字段 | Windows | Linux | macOS |
| --- | --- | --- | --- |
| `userHome` | `C:\Users\<user>` | `/home/<user>` | `/Users/<user>` |
| `installPath` | `C:\Program Files\MyApp` | `/opt/MyApp` | `MyApp.app` 根目录 |
| `installedJarPath` | `C:\Program Files\MyApp\app` | `/opt/MyApp/lib/app` | `MyApp.app/app`（由 `compose.application.resources.dir` 推导） |
| `installedExePath` | `C:\Program Files\MyApp`（exe 与安装目录同级） | `/opt/MyApp/bin` | `.../app/runtime/Contents/Home/lib` |
| `configDirPath` | `C:\Users\<user>\.MyApp` | `$XDG_CONFIG_HOME` 或 `~/.config/.MyApp` | `~/Library/Application Support/MyApp`（自动创建） |
| `internalConfigDirPath` | `C:\Program Files\MyApp\.conf` | `$XDG_DATA_HOME` 或 `~/.local/share/.MyApp` | `MyApp.app/.conf` |

Windows 打包布局：

```
MyApp/
├── app/            ← 所有 jar
├── app.exe         ← 入口
├── runtime/        ← JDK
└── conf/           ← 内置配置
```

Linux 打包布局：

```
/opt/MyApp/
├── share/doc/      ← copyright 等
├── bin/            ← 启动脚本
├── lib/app/        ← jar
└── lib/runtime/    ← JDK
```

macOS 打包布局：

```
MyApp.app/
├── Info.plist
├── MacOS/
├── Resources/
├── app/            ← jar
└── runtime/        ← JDK
```

> 注意：**`internalConfigDirPath` 是「随安装目录走」的只读/半只读配置**，
> `configDirPath` 才是「用户级可写配置」。不要往 `installPath` 里写用户数据 ——
> Windows 的 `Program Files` 通常需要管理员权限，macOS 的 `.app` 一旦签名被改动会失效。

---

## 9. 注意细节

1. **`InstanceContext.startUp()` 只能调用一次**
   第二次会抛 `KoinApplicationAlreadyStartedException`。
   使用 `framework-common` 时请改用 `ServiceBooter.bootstrap(logger)`，它捕获并降级为 warn 日志。

2. **平台实现必须进运行时 classpath**
   只有 `toolkit-spi` 而没有 `toolkit-win/linux/mac` 时，
   `AppPathProvider.getInstance()` 在打包环境下会因找不到实现而抛 `NoDefinitionFoundException`。
   （IDE 下通常看不出来，因为走了 `DebugAppPathProvider`。）

3. **`AppInfo` 要尽早设置**
   `configDirPath` 依赖 `AppInfo.appName`；默认值是 `"untitled"`，
   进程名是 `java` 时会变成 `"DevJava"`。请在启动时用
   `DesktopToolkitBooster.provideApplicationInfo { appName = "..." }` 显式指定。

4. **`AppInfoProvider` 已废弃**
   它只是转发到 `DesktopToolkitBooster`。新代码不要再用。
   （`WindowsAppPathProvider` 内部还在用它，属于历史遗留。）

5. **`setApplicationInfo` 会被缓存**
   `DesktopToolkitBooster.appInfo` 用 `MutableLazy` 延迟初始化，
   多次 `setApplicationInfo` 会覆盖，但**已经算出来的路径不会重算** ——
   所以设置应用名必须在第一次访问路径之前。

6. **`ProcessLocker.lock` 是进程级锁，不是线程级**
   同进程内重复调用会被 `if (this::fileChannel.isInitialized) return` 直接忽略；
   跨进程竞争才会抛 `RuntimeException("Process already running - cannot acquire lock")`。
   另外它是**句柄锁**：进程崩溃时由 OS 释放，不需要清理残留文件。

7. **`FileUtils` 是接口 + 伴生对象委托**
   ```kotlin
   interface FileUtils {
       companion object : FileUtils by getPlatformFileUtil()
   }
   ```
   因此既可以用 `FileUtils.openFile(file)`（伴生对象），也可以 `object : FileUtils` 自定义。

8. **`PlatformWindowHelper` / `SystemWindowManager` / `AppStartUpService` 目前是「预留能力」**
   - `WindowsWindowHelper.bringToFront` 是空实现；
   - `SystemWindowManager` 是空接口；
   - `AppStartUpService` 只有定义，没有任何平台实现（`getAutoStartService()` 会返回 `null`）。

   用之前请先判断 `null` / 自己补实现。

9. **`currentOsAndArch` 会抛异常**
   只支持 `windows-x64` / `macos-x64` / `macos-arm64` / `linux-x64`；
   Windows ARM、Linux ARM 会抛 `IllegalStateException`。需要兼容时自己判断 `currentArch`。

10. **`PathService.anyClass` 有陷阱**
    默认值是 `ProcessInfoHelper.sampleInfo.mainClass`（通过堆栈找 `main`）。
    在 IDE 里如果 `main` 不在你的模块里（比如框架启动的），可能拿到框架的类，
    导致 jar 路径算到 `.m2` 仓库里。**应用启动时应显式赋值**：

    ```kotlin
    PathService.anyClass = MyAppMain::class.java
    ```

11. **不要用 `System.getProperty("user.dir")` 拿安装目录**
    源码注释写得很明确：通过系统服务/计划任务启动时，`user.dir` 可能是 `C:\Windows\System32`。
    请用 `AppPathProvider.installPath`。

12. **`toolkit` 不提供日志配置**
    `logback.xml` 需要应用自己放（`composeApp` 就放在 `resources/logback.xml`）。
    没有实现时 SLF4J 只会打印一条警告，不影响功能。

---

## 10. 常见问题（FAQ）

**Q1：`NoDefinitionFoundException: No definition found for type 'AppBasePathProvider'`**

- 没调用 `InstanceContext.startUp()` / `ServiceBooter.bootstrap()`；
- 或者平台模块没进 classpath（检查 `runtimeOnly("...:toolkit-win:1.3.0")`，以及打包后的
  `META-INF/services/androidx.jvm.system.di.ModuleProvider` 是否存在）。

**Q2：`KoinApplicationAlreadyStartedException`**

`startUp()` 被调用了两次。常见组合是「我先手动 `startUp()`，框架启动时又 `bootstrap()`」。
选一个即可；如果两边都要，用 `ServiceBooter.bootstrap()`（它内部吞掉这个异常）。

**Q3：路径全都指向 `DebugConfFile`，我想测真实路径怎么办？**

因为 IDE 里运行必然被判定为 Debug。两种办法：

1. 打包成安装包再运行（`./gradlew packageDistributionForCurrentOS`）；
2. 手动指定实现：`AppPathProvider.getInstance(WindowsAppPathProvider())`。

**Q4：为什么 `configDirPath` 里的应用名不对（`untitled` / `DevJava`）？**

`AppInfo` 没设置。解决方案：

```kotlin
DesktopToolkitBooster.provideApplicationInfo {
    appName = "my-app"          // 目录名会变成 ~/.my-app
    appDisplayName = "My App"
}
```

注意：必须在**第一次访问路径之前**设置。

**Q5：`ProcessLocker` 拿不到锁，但我确认没有别的实例。**

- 上一次运行崩溃留下的进程确实持有句柄（杀掉进程即可，文件本身不用删）；
- 或者你在同一进程里先调过 `unlock()`，而 `fileChannel` 仍是 initialized 状态 ——
  `lock()` 会直接 return，实际上没上锁。当前实现没有「重新加锁」的支持，需要重启进程。

**Q6：`okio.Path` 和 `java.nio.file.Path` 老是搞混。**

```kotlin
val okioPath: okio.Path = ...
val nioPath: java.nio.file.Path = okioPath.toNioPath()
val back: okio.Path = nioPath.toOkioPath()
```

`ProcessLocker.lock(...)` 要 `java.nio.file.Path`，`AppPathProvider` 全部返回 `okio.Path`。

**Q7：Linux 上设置了 `XDG_CONFIG_HOME`，但配置目录变成了 `$XDG_CONFIG_HOME` 本身？**

这是 `LinuxAppPathProvider` 的已知缺陷（详见 [`README-linux.md`](./README-linux.md)）：
环境变量存在时直接返回该目录，没有拼上 `.appName`。建议：

- 不设置 `XDG_CONFIG_HOME`；或
- 在应用里自己做一层封装（推荐）：`provider.configDirPath / appName`。

**Q8：macOS 上启动就抛 `IllegalStateException: Could not find app path`。**

`MacosAppPathProvider` 依赖 `compose.application.resources.dir` 或 `skiko.library.path`
这两个 system property（由 Compose 的 native 打包任务注入）。
直接 `java -jar` 运行是拿不到的，必须用 `.app` 包启动，或手动设置这两个属性。

**Q9：能不能只依赖 `toolkit-spi`，自己实现一个平台？**

可以。实现 `AppBasePathProvider` + 一个 `@AutoService(ModuleProvider::class)` 的类，
注册 `single<AppBasePathProvider>(named(SystemOs.你的平台))` 即可。
完整示例见 [`README-spi.md`](./README-spi.md) 最后一节「新增一个平台实现」。

**Q10：`toolkit` 和 `framework` 是什么关系？**

`toolkit` 是被 `framework` 复用的底层能力层（`framework:common`、`framework:swing`、`framework:compose` 都依赖
`toolkit:spi`）。它本身与 UI 无关，也可以单独用于任何 JVM 桌面应用。
