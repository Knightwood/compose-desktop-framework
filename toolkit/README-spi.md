# toolkit-spi — 接口与基础设施

> `toolkit:spi` 是整个 `toolkit` 的地基：它定义**平台无关的接口**，提供**独立的 Koin 容器**，
> 以及一批写桌面应用时反复要用的工具（路径、进程、系统识别、文件操作、命令执行）。
>
> 它**不包含任何平台相关代码** —— 真正的实现由 `toolkit:win` / `toolkit:linux` / `toolkit:mac`
> 通过 `@AutoService` + Koin 在运行时注入。

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 包结构](#2-包结构)
- [3. 环境要求](#3-环境要求)
- [4. 发布到本地 Maven 仓库](#4-发布到本地-maven-仓库)
- [5. 引入依赖](#5-引入依赖)
- [6. 最简示例](#6-最简示例)
- [7. 依赖容器：`InstanceContext`](#7-依赖容器instancecontext)
- [8. SPI 装配机制](#8-spi-装配机制)
- [9. 应用路径：`AppPathProvider`](#9-应用路径apppathprovider)
- [10. 应用信息：`DesktopToolkitBooster`](#10-应用信息desktoptoolkitbooster)
- [11. 系统识别：`SystemOs` / `Arch`](#11-系统识别systemos--arch)
- [12. 进程：`ProcessInfoHelper` / `ProcessLocker`](#12-进程processinfohelper--processlocker)
- [13. 文件：`FileUtils`](#13-文件fileutils)
- [14. 预留能力：窗口控制 / 开机自启](#14-预留能力窗口控制--开机自启)
- [15. 小工具函数](#15-小工具函数)
- [16. 新增一个平台实现](#16-新增一个平台实现)
- [17. 注意细节](#17-注意细节)
- [18. API 参考](#18-api-参考)
- [19. 常见问题](#19-常见问题)

---

## 1. 模块定位

```
toolkit:spi
├── core       ← 面向应用的入口：InstanceContext、AppPathProvider、DesktopToolkitBooster、
│                 SystemWindowManager、AppStartUpService
├── di         ← InstanceContext（KoinContext 实现）、ModuleProvider、InstanceKoinComponent
├── model      ← ProcessSampleInfo
├── process    ← ProcessInfoHelper、ProcessLocker
├── utils      ← SystemOs、SystemProperty、JvmUtils、JDKInfo、Exec、MutableLazy、
│                 OkioPathExtension、osfileutil/*
└── windows    ← PlatformWindowHelper、WindowDesc（注意：包名是 java 源码目录，但内容仍是 Kotlin）
```

它被以下模块依赖：

```
framework:common ──┐
framework:swing  ──┼──► toolkit:spi ◄── toolkit:win / linux / mac
framework:compose─┘
```

也就是说：**只要引入框架，就一定会有 `toolkit-spi`**。

---

## 2. 包结构

| 包 | 主要类型 |
| --- | --- |
| `androidx.jvm.system.di` | `InstanceContext`、`InstanceKoinComponent`、`ModuleProvider`、`getPlatformInstance`、`startUp` |
| `androidx.jvm.system.core` | `AppBasePathProvider`、`AppPathProvider`、`DebugAppPathProvider`、`PathService`、`DesktopToolkitBooster`、`AppInfo`、`AppInfoProvider`（废弃）、`SystemWindowManager`、`AppStartUpService` |
| `androidx.jvm.system.utils` | `SystemOs`、`Arch`、`currentOS`、`currentArch`、`currentOsAndArch`、`SystemProperty`、`JvmUtils`、`JDKInfo`、`execAndWait`、`MutableLazy`、okio 扩展 |
| `androidx.jvm.system.utils.osfileutil` | `FileUtils`、`JVMFileUtils`、`WindowsFileUtils`、`LinuxFileUtils`、`MacOsFileUtils` |
| `androidx.jvm.system.process` | `ProcessInfoHelper`、`ProcessLocker` |
| `androidx.jvm.system.model` | `ProcessSampleInfo` |
| `androidx.jvm.system.windows` | `PlatformWindowHelper`、`WindowDesc`、`asWindowDesc` |

---

## 3. 环境要求

| 项目 | 要求 |
| --- | --- |
| JDK | 17+（`ProcessHandle` / `StackWalker` / `FileChannel.tryLock`） |
| Kotlin | 2.2.10（编译期），运行期 2.0+ |
| Gradle | 8.11.1（Wrapper 已提供） |
| 平台 | Windows / Linux / macOS |

运行期依赖（由本模块传递引入，无需手工对齐）：

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `io.insert-koin:koin-core` | 4.0.2（BOM） | 容器 |
| `com.squareup.okio:okio` | 3.10.2 | `okio.Path` |
| `org.slf4j:slf4j-api` + `slf4j-api-kotlin` | 2.0.15 / 0.0.7 | 日志 |
| `ch.qos.logback:logback-classic` | 1.5.12 | 日志实现 |
| `net.java.dev.jna:jna` / `jna-platform` | 5.16.0 | 平台能力（预留） |
| `kotlinx-coroutines-core` / `-swing` | 1.10.1 | 异步 |
| `com.google.auto.service:auto-service-annotations` | 1.x | `@AutoService` |
| KSP | `2.2.10-2.0.2` | 生成 `META-INF/services` |

---

## 4. 发布到本地 Maven 仓库

| 项 | 值 |
| --- | --- |
| groupId | `com.github.knightwood.desktop` |
| artifactId | **`toolkit-spi`** |
| version | `1.3.0` |
| 后缀 | 无（`kotlin("jvm")` 工程，没有 `-jvm`） |

```bash
# 发布到 ~/.m2/repository
./gradlew :toolkit:spi:publishToMavenLocal

# 发布到自定义仓库（gradle.properties 的 my.maven.url）
./gradlew :toolkit:spi:publish
```

Windows 用户注意把 `gradle.properties` 里的 `my.maven.url=/mnt/f/.m2/repository`
改成 `file:///F:/maven-repo` 之类的本地路径。

---

## 5. 引入依赖

```kotlin
// build.gradle.kts
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // 接口 + 容器 + 工具
    implementation("com.github.knightwood.desktop:toolkit-spi:1.3.0")

    // 别忘了平台实现（否则打包后取不到 AppBasePathProvider）
    runtimeOnly("com.github.knightwood.desktop:toolkit-win:1.3.0")
    runtimeOnly("com.github.knightwood.desktop:toolkit-linux:1.3.0")
    runtimeOnly("com.github.knightwood.desktop:toolkit-mac:1.3.0")

    runtimeOnly("ch.qos.logback:logback-classic:1.5.12")
}
```

Maven：

```xml
<dependency>
  <groupId>com.github.knightwood.desktop</groupId>
  <artifactId>toolkit-spi</artifactId>
  <version>1.3.0</version>
</dependency>
```

---

## 6. 最简示例

```kotlin
import androidx.jvm.system.core.AppPathProvider
import androidx.jvm.system.core.DesktopToolkitBooster
import androidx.jvm.system.core.keepDirExist
import androidx.jvm.system.di.InstanceContext
import androidx.jvm.system.di.startUp
import androidx.jvm.system.process.ProcessLocker
import androidx.jvm.system.utils.currentOsAndArch

fun main() {
    // 1) 先告诉 toolkit 应用叫什么（影响 ~/.appName 目录名）
    DesktopToolkitBooster.provideApplicationInfo {
        appName = "my-app"
        appDisplayName = "My App"
    }

    // 2) 启动独立 Koin 容器，自动装配所有 ModuleProvider
    InstanceContext.startUp()

    // 3) 现在可以使用了
    SystemPropertyExample()
}

private fun SystemPropertyExample() {
    println("平台: $currentOsAndArch")

    val paths = AppPathProvider.getInstance()
    paths.print()

    val cfgDir = paths.configDirPath.keepDirExist()

    try {
        ProcessLocker.lock(cfgDir.resolve("lockfile.lock").toNioPath())
    } catch (e: RuntimeException) {
        println("已有实例在运行: ${e.message}")
        return
    }

    println("配置目录: $cfgDir")
}
```

> 使用 `framework-common` 的项目里，第 2 步等价于 `ServiceBooter.bootstrap(logger)`
> （它内部就是 `InstanceContext.startUp { ... }`，并额外接管 Koin 日志与重复启动异常）。

---

## 7. 依赖容器：`InstanceContext`

### 7.1 为什么是独立容器

Koin 官方的 `startKoin { }` 会把容器塞进全局单例 `GlobalContext`，一个 JVM 只能有一个。
框架作为**被依赖的库**，不能和宿主应用抢这个位置，因此 `toolkit:spi` 自己实现了 `KoinContext`：

```kotlin
object InstanceContext : KoinContext {
    private var _koin: Koin? = null
    private var _koinApplication: KoinApplication? = null

    override fun get(): Koin = _koin ?: error("KoinApplication has not been started")
    override fun getOrNull(): Koin? = _koin

    override fun startKoin(appDeclaration: KoinAppDeclaration): KoinApplication = synchronized(this) {
        if (_koin != null) throw KoinApplicationAlreadyStartedException("A Koin Application has already been started")
        val koinApplication = KoinApplication.init()
        appDeclaration(koinApplication)
        return startKoin(koinApplication)
    }

    override fun startKoin(koinApplication: KoinApplication): KoinApplication = synchronized(this) {
        register(koinApplication)
        koinApplication.createEagerInstances()
        koinApplication.modules(findModules())   // ← 关键：装配所有平台模块
        koinApplication
    }

    override fun stopKoin() = synchronized(this) {
        _koin?.close()
        _koin = null
    }
}
```

要点：

- `get()` 在未启动时**直接 error**（不是返回 null）；
- `startKoin` 只允许一次，第二次抛 `KoinApplicationAlreadyStartedException`；
- `stopKoin()` 只 `close()` 并置空 `_koin`，`_koinApplication` 保留。

### 7.2 启动的两条路径

```kotlin
// ① 最常用：什么都不配置
InstanceContext.startUp()

// ② 加载自己的模块
InstanceContext.startUp {
    modules(myAppModule, myRepositoryModule)
}
```

`startUp` 只是 `startKoin(appDeclaration)` 的别名，定义在同文件底部：

```kotlin
fun InstanceContext.startUp(appDeclaration: KoinAppDeclaration = {}) {
    startKoin(appDeclaration = appDeclaration)
}
```

### 7.3 取实例：`InstanceKoinComponent`

```kotlin
class MyService : InstanceKoinComponent {
    val paths by inject<AppBasePathProvider>()   // 或 inject<...>(named(SystemOs.Windows))
    val saver = get<ProcessLocker>()             // get<T>() 是 inline 扩展
}
```

为什么不是 Koin 原版 `KoinComponent`？
因为它的 `getKoin()` 返回的是 **`InstanceContext.get()`**，而不是全局 Koin：

```kotlin
interface InstanceKoinComponent : KoinComponent {
    override fun getKoin(): Koin = InstanceContext.get()
}
```

这也是框架里所有类（`LifecycleJFrame`、`JFrameManager`、`ActivityManager`……）实现的接口。

还提供了一个全局帮助对象：

```kotlin
object InstanceKoinHelpers {
    fun getKoin(): Koin = InstanceContext.get()
}
```

### 7.4 `get` / `inject` 扩展的细节

```kotlin
inline fun <reified T : Any> InstanceKoinComponent.get(
    qualifier: Qualifier? = null,
    noinline parameters: ParametersDefinition? = null,
): T = if (this is KoinScopeComponent) {
    scope.get(qualifier, parameters)      // 有 scope 时优先用 scope
} else {
    getKoin().get(qualifier, parameters)
}

inline fun <reified T : Any> InstanceKoinComponent.inject(
    qualifier: Qualifier? = null,
    mode: LazyThreadSafetyMode = KoinPlatformTools.defaultLazyMode(),
    noinline parameters: ParametersDefinition? = null,
): Lazy<T> = lazy(mode) { get<T>(qualifier, parameters) }
```

- 实现了 `KoinScopeComponent` 的类会从自己的 `scope` 取（例如 `JFrameManager` 的 `scope`）；
- `inject` 是惰性的，**第一次访问才解析** —— 适合打破循环依赖。

---

## 8. SPI 装配机制

### 8.1 `ModuleProvider`

```kotlin
interface ModuleProvider {
    fun provide(): Module
}
```

平台模块实现它，并用 `@AutoService` 生成 `META-INF/services`。

### 8.2 自动发现

```kotlin
private fun findModules(): MutableList<Module> {
    val modules = mutableListOf<Module>()
    val providers = ServiceLoader.load(ModuleProvider::class.java)
    providers.forEach { modules.add(it.provide()) }
    return modules
}
```

`ServiceLoader` 用的是**线程上下文类加载器**。在 OSGi、某些自定义 ClassLoader、
或 Spring Boot 的 fat jar 场景下可能找不到实现 —— 这时可以手动加载模块：

```kotlin
InstanceContext.startUp {
    modules(WindowsPlatformServiceProvider().provide())   // 手动兜底
}
```

### 8.3 平台限定符

每个平台模块都用 `named(SystemOs.X)` 作为限定符注册：

```kotlin
single<AppBasePathProvider>(named(SystemOs.Windows)) { WindowsAppPathProvider() }
```

取实例：

```kotlin
inline fun <reified T : Any> getPlatformInstance(): T =
    InstanceContext.get().get<T>(qualifier = named(currentOS))
```

> 限定符是**枚举值**，`named(SystemOs.Windows)` ≠ `named("Windows")`。
> 想按名字取要用 `named<SystemOs>(...)` 的同一套写法，别混。

---

## 9. 应用路径：`AppPathProvider`

### 9.1 接口

```kotlin
interface AppBasePathProvider {
    val userHome: OkioPath            // 用户目录
    val installPath: OkioPath         // 安装目录
    val installedJarPath: OkioPath    // jar 目录
    val installedExePath: OkioPath    // exe / 启动器目录
    val configDirPath: OkioPath       // 用户级配置目录（可写）
    val internalConfigDirPath: OkioPath  // 随安装目录的配置目录
    fun print() {}
}
```

**所有类型都是 `okio.Path`**。示例输出（Windows，打包后）：

```
 用户目录: C:\Users\junya
 安装路径: C:\Program Files\MyApp
 程序jar包路径: C:\Program Files\MyApp\app
 exe文件路径: C:\Program Files\MyApp
 配置目录路径: C:\Users\junya\.MyApp
 内部配置目录路径: C:\Program Files\MyApp\.conf
```

### 9.2 入口

```kotlin
class AppPathProvider private constructor(
    private val pathProviderImpl: AppBasePathProvider,
) : AppBasePathProvider by pathProviderImpl {

    companion object {
        fun getInstance(impl: AppBasePathProvider = ProviderImpl): AppBasePathProvider
        val provider get() = getInstance()
    }
}
```

- `getInstance()` 是**懒加载 + 双重检查**的单例；
- 可以传入自定义实现覆盖：`AppPathProvider.getInstance(MyProvider())`（第一次调用有效，之后返回缓存）；
- `AppPathProvider.provider` 只是属性写法。

> 注意返回类型声明为 `AppBasePathProvider`，但**实际返回的对象是 `AppPathProvider`**（它用委托实现了接口）。
> `AppBasePathProvider.print()` 的默认实现是空，而 `AppPathProvider` 重写为 `kLogger.info { toString() }`，
> 所以 `AppPathProvider.getInstance().print()` 会真正打印路径（多态生效）。

### 9.3 Debug 自动判定

```kotlin
internal val ProviderImpl by lazy {
    val path = PathService.getAppJarDirPath().toFile().absolutePath
    val regex = Regex("build[\\\\/](libs|classes)")
    val matches = regex.containsMatchIn(path)
    val isProcessNameIsJava = ProcessInfoHelper.sampleInfo.isProcessNameIsJava
    if (matches || isProcessNameIsJava) DebugAppPathProvider() else getPlatformInstance()
}
```

`DebugAppPathProvider` 的取值：

| 字段 | 值 |
| --- | --- |
| `installPath` | `user.dir`（项目根目录） |
| `installedJarPath` | `<project>/build/libs` |
| `installedExePath` | 空路径 `""` |
| `configDirPath` | `<project>/DebugConfFile/user/.<appName>` |
| `internalConfigDirPath` | `<project>/DebugConfFile/internal/.conf` |

好处：开发时不污染真实用户目录，状态文件就在项目里，方便查看和清理。

### 9.4 `PathService`

```kotlin
object PathService {
    var anyClass: Class<*> by MutableLazy { ProcessInfoHelper.sampleInfo.mainClass }

    fun getAppJarDirPath(): OkioPath               // = getAppJarDirPath(anyClass)
    fun getAppJarDirPath(clazz: Class<*>): OkioPath // 带缓存
}
```

- **不要用 `user.dir` 推导安装目录**（系统服务/计划任务启动时可能是 `C:\Windows\System32`）；
- **必须传入具体 class**：从 `~/.m2` 加载的 jar 和项目目录的 class 会得到不同结果；
- `getAppJarDirPath()` 用的是 `JvmUtils.getJarPath(clazz).noOptionParent`。

### 9.5 路径扩展函数

```kotlin
// 目录不存在则创建
val dir = provider.configDirPath.keepDirExist()

// 文件不存在则创建（父目录必须已存在）
val file = dir.resolve("a.conf").keepFileExist()

// Compose 打包注入的资源目录（依赖 compose.application.resources.dir）
val res = provider.resourcePath()

// Skiko native 库目录（依赖 skiko.library.path）
val skiko = provider.skikoPath()

// JDK 目录（依赖 java.home）
val jvm = provider.jvmPath()
```

后三个在属性不存在时会抛 `FileNotFoundException` / `IllegalStateException`，
只适合「打包后的环境」，IDE 里运行要做好兜底。

---

## 10. 应用信息：`DesktopToolkitBooster`

```kotlin
object DesktopToolkitBooster {
    fun getApplicationInfo(): AppInfo
    fun setApplicationInfo(appInfo: AppInfo)
    fun provideApplicationInfo(scope: AppInfo.() -> Unit)
}

data class AppInfo(
    var appName: String = "untitled",
    var isDevMode: Boolean = false,
    var appDisplayName: String = appName,
)
```

默认值的行为：

```kotlin
private var appInfo: AppInfo by MutableLazy {
    val info = ProcessInfoHelper.sampleInfo
    AppInfo(appName = if (info.isProcessNameIsJava) "DevJava" else info.processName)
}
```

- 打包运行：`appName` = 可执行文件名（去扩展名）；
- IDE / `java -jar` 运行：`appName = "DevJava"`。

**`appName` 会影响 `configDirPath`**：

| 平台 | 配置目录 |
| --- | --- |
| Windows | `~/.<appName>` |
| Linux | `~/.config/.<appName>` |
| macOS | `~/Library/Application Support/<appName>` |

推荐写法（越早越好）：

```kotlin
fun main() {
    DesktopToolkitBooster.provideApplicationInfo {
        appName = "my-app"          // 目录名
        appDisplayName = "My App"   // 展示用
        isDevMode = false
    }
    InstanceContext.startUp()
    ...
}
```

> `AppInfoProvider` 已标记 `@Deprecated("Use DesktopToolkitBooster instead")`，
> 内部只是转发；新代码不要再用。`WindowsAppPathProvider` / `LinuxAppPathProvider` /
> `MacosAppPathProvider` 里仍在使用它（历史原因），行为等价。

---

## 11. 系统识别：`SystemOs` / `Arch`

```kotlin
enum class SystemOs { MacOS, Windows, Linux, Android }

val currentOS: SystemOs      // 由 os.name 推断，未知则 error
val currentArch: Arch        // X64 / Arm64 / X32，未知则 error
val is64Arch: Boolean
val currentOsAndArch: String // windows-x64 / macos-x64 / macos-arm64 / linux-x64
```

辅助方法：

```kotlin
SystemOs.fromString("windows")        // Windows
SystemOs.fromString("darwin")         // null（不认 darwin，只认 mac）
SystemOs.fromExecutableFileExtension("msi")  // Windows
SystemOs.fromExecutableFileExtension("deb")  // Linux
SystemOs.fromExecutableFileExtension("dmg")  // MacOS
getWindowsVersion(osName = "Windows 11", javaOsVersion = "10.0")
```

`currentOsAndArch` 只覆盖四种组合，**其余会抛 `IllegalStateException`**：

| 平台 + 架构 | 结果 |
| --- | --- |
| Windows + 64 位 | `windows-x64` |
| macOS + X64 | `macos-x64` |
| macOS + Arm64 | `macos-arm64` |
| Linux + 64 位 | `linux-x64` |
| 其他（Windows ARM、Linux ARM、32 位） | 抛异常 |

---

## 12. 进程：`ProcessInfoHelper` / `ProcessLocker`

### 12.1 `ProcessInfoHelper`

```kotlin
object ProcessInfoHelper {
    val sampleInfo: ProcessSampleInfo
    fun parseAppName(path: String?): String
}
```

`sampleInfo` 通过 `ProcessHandle.current()` 采集：

```kotlin
data class ProcessSampleInfo(
    var command: String? = null,
    var commandLine: String? = null,
    var pid: Long = 0L,
    var arguments: Array<String> = emptyArray(),
    var user: String? = null,
    var mainClassString: String = "",
) {
    val mainClass: Class<*>            // Class.forName(mainClassString)
    val mainJarPath: String
    val mainJarName: String            // 去扩展名的 jar 名
    val processName: String            // 可执行文件名
    val isProcessNameIsJava: Boolean   // command.contains("bin\\java")
}
```

两个必须知道的实现细节：

1. **`cache` 字段没被赋值** —— 每次访问 `sampleInfo` 都会重新采集（`ProcessHandle` + 堆栈遍历），
   不要放在高频路径里；需要复用时自己 `val info = ProcessInfoHelper.sampleInfo`。
2. **`isProcessNameIsJava` 用的是 Windows 路径分隔符**（`bin\java`），
   在 Linux/macOS 上恒为 `false`。因此「IDE 中运行」的判定在 Linux 上**只靠
   `build/libs` / `build/classes` 正则**，如果你的输出目录不是标准 Gradle 布局，会误判为 Release。

`parseAppName` 的规则：取路径最后一段，若含 `.` 则截到第一个 `.` 之前。

```kotlin
parseAppName("C:\\Program Files\\MyApp\\MyApp.exe")  // "MyApp"
parseAppName("/opt/MyApp/bin/my-app")                // "my-app"
parseAppName(null)                                   // "Unknow"（注意拼写）
```

### 12.2 `ProcessLocker`

```kotlin
object ProcessLocker {
    fun lock(path: Path)    // java.nio.file.Path
    fun unlock()
}
```

实现要点：

```kotlin
fun lock(path: Path) {
    if (this::fileChannel.isInitialized) return       // 同进程只锁一次
    val file = path.toFile()
    if (!file.exists()) file.createNewFile()
    val channel = FileChannel.open(path, READ, WRITE)
    val lock = channel.tryLock()                      // 非阻塞
    if (lock == null) {
        channel.close()
        throw RuntimeException("Process already running - cannot acquire lock")
    }
    fileChannel = channel                             // 持有 channel，锁不主动释放
}
```

- **非阻塞**：不需要放子线程；
- 锁的粒度是**整个 JVM 进程**（同一 JVM 内多个 ClassLoader 共享同一通道）；
- 进程退出时 OS 自动释放，`unlock()` 大多数时候不需要调用；
- 重复 `lock` 会被静默忽略，所以「先 unlock 再 lock」在当前实现里**不会重新加锁**。

典型用法：

```kotlin
val lockPath = AppPathProvider.getInstance()
    .configDirPath.keepDirExist()
    .resolve("lockfile.lock")
    .toNioPath()

try {
    ProcessLocker.lock(lockPath)
} catch (e: RuntimeException) {
    // 已有实例：提示用户，或者通过平台 API 唤起已有窗口
    System.exit(0)
}
```

---

## 13. 文件：`FileUtils`

```kotlin
interface FileUtils {
    fun openFile(file: File): Boolean
    fun openFolderOfFile(file: File): Boolean
    fun openFolder(folder: File): Boolean
    fun canWriteInThisFolder(folder: String): Boolean
    fun isRemovableStorage(path: String): Boolean

    companion object : FileUtils by getPlatformFileUtil()
}
```

按 `currentOS` 选择实现：

| 平台 | 实现 |
| --- | --- |
| Windows | `WindowsFileUtils` |
| Linux | `LinuxFileUtils` |
| macOS | `MacOsFileUtils` |
| Android | `JVMFileUtils`（兜底） |

因为伴生对象做了委托，直接这样用：

```kotlin
FileUtils.openFile(File("/tmp/a.txt"))         // 系统默认程序打开文件
FileUtils.openFolderOfFile(File("/tmp/a.txt")) // 打开文件所在目录
FileUtils.openFolder(File("/tmp"))             // 打开目录
FileUtils.canWriteInThisFolder("/tmp")         // 是否可写
FileUtils.isRemovableStorage("/media/usb")     // 是否可移动存储
```

返回值 `Boolean` 表示**是否成功发起调用**（不保证目标程序真的打开了）。
在无桌面环境（headless Linux）下通常会返回 `false`。

---

## 14. 预留能力：窗口控制 / 开机自启

这三个接口已经定义，但仓库内**没有完整实现**，使用前请判空或自己补齐。

### 14.1 `PlatformWindowHelper`

```kotlin
interface PlatformWindowHelper {
    fun bringToFront(desc: WindowDesc) {}
    fun bringToBack(desc: WindowDesc) {}
    fun close(desc: WindowDesc) {}
}

data class WindowDesc(val title: String)
fun String.asWindowDesc(): WindowDesc = WindowDesc(this)
```

使用：

```kotlin
val helper: PlatformWindowHelper = getPlatformInstance()   // named(currentOS)
helper.bringToFront("My App".asWindowDesc())
```

现状：

| 平台 | 实现 |
| --- | --- |
| Windows | `WindowsWindowHelper`（`@AutoService`，但方法体为空） |
| Linux / macOS | 无实现 → `getPlatformInstance()` 会抛 `NoDefinitionFoundException` |

### 14.2 `SystemWindowManager`

```kotlin
interface SystemWindowManager { }                 // 空接口，等待扩展
fun getSystemWindowManager(): SystemWindowManager? = ServiceLoader.load(...).firstOrNull()
```

### 14.3 `AppStartUpService`

```kotlin
interface AppStartUpService {
    fun followConfig()          // 按配置开关开机启动
    fun isAutoStartUp(): Boolean
    fun makeAutoStartUp()
    fun removeAutoStartUp()
}
fun getAutoStartService(): AppStartUpService? = ServiceLoader.load(...).firstOrNull()
```

仓库内没有任何实现，`getAutoStartService()` 恒为 `null`。
需要时请自行实现：Windows 写注册表 `Run` 项或启动目录快捷方式、macOS 用 `LaunchAgents`、
Linux 用 `~/.config/autostart/*.desktop`，然后用 `@AutoService(AppStartUpService::class)` 注册。

---

## 15. 小工具函数

### 15.1 命令执行

```kotlin
fun execAndWait(command: Array<String>, waitFor: Long = 2_000): Boolean
```

- 进程以 `0` 退出且未超时 → `true`；
- 非零退出 / 超时 / 抛异常 → `false`（内部 `runCatching`，不会抛）。

```kotlin
execAndWait(arrayOf("cmd", "/c", "start", "", "https://example.com"))
execAndWait(arrayOf("open", "https://example.com"))
execAndWait(arrayOf("xdg-open", "https://example.com"))
```

### 15.2 JDK 信息

```kotlin
data class JDKInfo(vendor, version, vmName, vmVersion, home, arch)
fun jdkInfo(): JDKInfo
```

### 15.3 `JvmUtils`

```kotlin
object JvmUtils {
    fun getMainClass(): Class<*>?              // 用 StackWalker 找 main 所在类
    fun getJarPath(clazz: Class<*>): String    // jar 路径；在目录中则返回该目录
}
```

`getJarPath` 的坑（KDoc 里已注明）：

- IDE 中运行返回的是 `build/classes/kotlin/main` 之类的目录；
- Windows 上路径可能带前导 `/`（`/C:/path/to.jar`）；
- jar-in-jar（嵌套 jar）不适用。

### 15.4 `SystemProperty`

```kotlin
object SystemProperty {
    operator fun get(key: String): String?
    fun get(key: String, default: String): String
    operator fun set(key: String, value: String)
    fun os(): String        // os.name
    fun arch(): String      // os.arch
    fun javaVer(): String   // 注意：返回的是 os.version（命名历史遗留）
}
```

> `javaVer()` 返回 `os.version`，不是 JVM 版本。想要 JVM 版本用 `jdkInfo().version`。

### 15.5 okio 扩展

```kotlin
val Path.fileNameRemoveExtension: String   // "a.jar" → "a"
val Path.extension: String                 // "a.jar" → "jar"；无扩展名 → ""
val Path.noOptionParent: Path              // parent ?: this
```

### 15.6 `MutableLazy`

```kotlin
internal class MutableLazy<O : Any>(val initializer: () -> O) : ReadWriteProperty<Any?, O>
```

「懒加载 + 可覆盖」的属性委托，用于 `DesktopToolkitBooster.appInfo`、`PathService.anyClass`。
`internal`，外部不可用。

---

## 16. 新增一个平台实现

假设你要支持一个自定义平台（或替换内置实现），三步：

**① 实现接口**

```kotlin
class MyAppPathProvider : AppBasePathProvider {
    override val userHome = SystemProperty["user.home"]!!.toPath()
    override val installPath = SystemProperty["user.dir"]!!.toPath()
    override val installedJarPath = installPath.resolve("app")
    override val installedExePath = installPath
    override val configDirPath = userHome.resolve(".myapp")
    override val internalConfigDirPath = installPath.resolve(".conf")
}
```

**② 提供 Koin 模块**

```kotlin
@AutoService(ModuleProvider::class)
class MyPlatformServiceProvider : ModuleProvider {
    override fun provide(): Module = module {
        single<AppBasePathProvider>(named(SystemOs.Linux)) { MyAppPathProvider() }
    }
}
```

> 如果只想覆盖某个平台而**不新增平台枚举**，就复用现成的 `SystemOs`：
> 例如你不想用内置的 Linux 实现，就用 `named(SystemOs.Linux)` 注册自己的类，
> 但要注意内置模块也会注册同一个 key —— 后加载的会覆盖先加载的（Koin 4 默认允许 override，
> 若报冲突请调换 `modules` 顺序或移除内置模块依赖）。

**③ 确保 `@AutoService` 处理器在编译期生效**

```kotlin
plugins {
    alias(libs.plugins.ksp)
}
dependencies {
    implementation(libs.autoService.annoations)
    ksp(libs.autoService.ksp)
}
```

编译后检查 `build/classes/.../META-INF/services/androidx.jvm.system.di.ModuleProvider` 是否生成。

---

## 17. 注意细节

1. **`InstanceContext` 不是 Koin 全局容器**
   `KoinPlatformTools.defaultContext()`、`GlobalContext` 都拿不到它。跨库共享时两边要显式传 `Koin` 实例。

2. **`startUp()` 不可重复**
   第二次抛 `KoinApplicationAlreadyStartedException`。用 `ServiceBooter.bootstrap()` 可自动降级。

3. **`get()` 未启动就直接 error**
   `InstanceContext.get()` 会抛 `IllegalStateException("KoinApplication has not been started")`，
   不是返回 null。所以别在静态初始化块里取实例。

4. **`AppPathProvider` 是全局单例**
   `getInstance(impl)` 传入的自定义实现只有**第一次**生效。
   想在运行期切换需要反射/重置 `appPathProvider`（`internal`，外部做不到）—— 所以要在启动时决定。

5. **`PathService.anyClass` 一定要显式指定**
   默认从堆栈找 `main`，在 EDT / 协程线程里第一次访问可能找不到 `main`，退化成栈底类
   → jar 路径算错 → Debug 判定失效。应用启动时写：

   ```kotlin
   PathService.anyClass = MyAppMain::class.java
   ```

6. **`sampleInfo` 没有真正缓存**
   每次访问都重算。需要多次使用时先取到局部变量。

7. **`isProcessNameIsJava` 在 Linux/macOS 上恒为 false**
   判断「IDE 中运行」时要同时看 jar 路径正则，或者直接检查 `gradle` 特征。

8. **`ProcessLocker.lock` 传的是 `java.nio.file.Path`**
   `AppPathProvider` 返回 `okio.Path`，别忘了 `.toNioPath()`。

9. **`ProcessLocker` 不支持「解锁后再锁」**
   `if (this::fileChannel.isInitialized) return` 会让第二次 `lock` 直接返回而不加锁。

10. **`AppInfo` 必须在第一次取路径之前设置**
    否则 `configDirPath` 会用 `untitled` / `DevJava` 算出来，并且**不会重算**。

11. **`print()` 的静态类型陷阱**
    `getInstance()` 的**声明返回类型**是 `AppBasePathProvider`（接口默认 `print()` 为空），
    但实际对象是 `AppPathProvider`，它的 `print()` 已重写为打印路径，所以调用是有效的。
    如果哪天你持有的是别处传来的 `AppBasePathProvider` 纯实现，`print()` 就什么也不干 ——
    想要确定有输出，直接用 `toString()`。

12. **`MacosAppPathProvider` 依赖 Compose 注入的系统属性**
    `compose.application.resources.dir` / `skiko.library.path` 缺失会抛
    `IllegalStateException("Could not find app path")`。

13. **`LinuxAppPathProvider` 在设置了 `XDG_CONFIG_HOME` 时不会拼应用名**
    返回的是该目录本身（见 `README-linux.md`）。

14. **`FileUtils` 的 `canWriteInThisFolder` 传 `String`，其它传 `File`**
    签名不统一，容易写错。

15. **平台模块的包名放在 `src/main/java` 下**
    `androidx.jvm.system.windows.PlatformWindowHelper` / `WindowDesc` 虽然在 `java` 源集目录，
    但内容是 Kotlin 代码（Gradle 的 `java` 目录同样参与 Kotlin 编译）。不要被目录名骗了。

---

## 18. API 参考

### 18.1 容器

| 符号 | 签名 | 说明 |
| --- | --- | --- |
| `InstanceContext` | `object : KoinContext` | 独立 Koin 上下文 |
| `startUp` | `fun InstanceContext.startUp(appDeclaration: KoinAppDeclaration = {})` | 启动 |
| `getPlatformInstance` | `inline fun <reified T> getPlatformInstance(): T` | 按 `currentOS` 取 |
| `InstanceKoinComponent` | `interface : KoinComponent` | `getKoin()` 指向 `InstanceContext` |
| `get` | `inline fun <reified T> InstanceKoinComponent.get(qualifier, parameters): T` | 取实例 |
| `inject` | `inline fun <reified T> InstanceKoinComponent.inject(...): Lazy<T>` | 惰性注入 |
| `ModuleProvider` | `interface { fun provide(): Module }` | SPI 契约 |

### 18.2 路径

| 符号 | 说明 |
| --- | --- |
| `AppBasePathProvider` | 6 个路径 + `print()` |
| `AppPathProvider.getInstance(impl)` / `.provider` | 单例入口 |
| `DebugAppPathProvider` | IDE 下使用的实现 |
| `PathService.getAppJarDirPath()` / `(clazz)` | jar 目录（带缓存） |
| `PathService.anyClass` | 用于路径推导的锚点类 |
| `keepDirExist()` / `keepFileExist()` | 不存在则创建 |
| `resourcePath()` / `skikoPath()` / `jvmPath()` | Compose 打包相关目录 |

### 18.3 系统 / 进程

| 符号 | 说明 |
| --- | --- |
| `SystemOs` / `Arch` | 枚举 |
| `currentOS` / `currentArch` / `is64Arch` / `currentOsAndArch` | 当前环境 |
| `getWindowsVersion(osName, javaOsVersion)` | Windows 版本号 |
| `ProcessInfoHelper.sampleInfo` | 进程信息 |
| `ProcessInfoHelper.parseAppName(path)` | 从路径提取应用名 |
| `ProcessLocker.lock(path)` / `unlock()` | 进程单实例锁 |

### 18.4 工具

| 符号 | 说明 |
| --- | --- |
| `FileUtils`（+4 个实现） | 打开文件/目录、可写性、可移动存储 |
| `PlatformWindowHelper` / `WindowDesc` / `asWindowDesc` | 窗口控制（占位） |
| `SystemWindowManager` / `getSystemWindowManager()` | 空接口 + ServiceLoader |
| `AppStartUpService` / `getAutoStartService()` | 开机自启（无实现） |
| `execAndWait(command, waitFor)` | 执行命令 |
| `jdkInfo()` / `JDKInfo` | JDK 信息 |
| `JvmUtils.getMainClass()` / `getJarPath(clazz)` | 栈/路径工具 |
| `SystemProperty` | System.getProperty 包装 |
| `Path.fileNameRemoveExtension` / `.extension` / `.noOptionParent` | okio 扩展 |

---

## 19. 常见问题

**Q1：`IllegalStateException: KoinApplication has not been started`**

没调用 `InstanceContext.startUp()` / `ServiceBooter.bootstrap()`，或者在容器启动之前就在
`companion object` / 顶层属性里取了实例。

**Q2：`NoDefinitionFoundException`（`AppBasePathProvider`）**

- 没启动容器；
- 平台实现没进 classpath；
- 自定义实现的 `named(...)` 与 `currentOS` 不匹配。

**Q3：为什么 `inject<T>()` 拿不到，但 `get<T>()` 可以？**

`inject` 返回 `Lazy`，真正解析发生在第一次 `getValue()`。若你在 `init` 之前就访问属性，
此时容器可能还没启动。

**Q4：`DesktopToolkitBooster` 设置的应用名没生效？**

`appInfo` 是 `MutableLazy`，已经被读取过就不会重新初始化。
`setApplicationInfo` 会直接覆盖当前值，但**已经计算出的路径不会重算**。
务必在启动最早期设置。

**Q5：`sampleInfo` 里的 `mainClassString` 是框架的类？**

`getMainClass()` 靠当前线程堆栈找 `main`。如果你在非 main 线程第一次访问，
只能退化到栈底类。应用启动时显式设置 `PathService.anyClass` 可规避。

**Q6：`configDirPath` 每次启动都不同？**

- Debug/Release 判定切换（IDE vs 打包）；
- 或 `appName` 不稳定（默认取进程名，`java` 时变成 `DevJava`）。

固定 `appName` 即可。

**Q7：能不能不用 Koin，直接 new `WindowsAppPathProvider`？**

可以，`AppPathProvider.getInstance(WindowsAppPathProvider())` 就绕开了 SPI。
但 `ProcessLocker`、`FileUtils` 之外的 SPI 能力（`PlatformWindowHelper` 等）仍需要容器。

**Q8：`toolkit-spi` 会和我项目里的 Koin 冲突吗？**

不会。它用的是自己的 `InstanceContext`，不碰 `GlobalContext`。
但要注意：你项目里用 `startKoin {}` 注册的实例，`InstanceKoinComponent` 是取不到的（反之亦然）。
需要共享时，用 `InstanceContext.startUp { modules(sharedModule) }` 把模块注册进独立容器。
