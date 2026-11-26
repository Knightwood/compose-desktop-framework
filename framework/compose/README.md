# framework-compose

`framework-compose` 是一个把 **Android 的 Activity / Dialog / Fragment 心智模型搬到 Compose Desktop** 之上的窗口框架。

它解决的核心问题不是「怎么画一个窗口」，而是：

> Compose Desktop 里窗口的生死靠 `Window {}` 是否还在重组树上 —— 你必须把它写进某个 `application {}` 里、用状态变量控制它的显隐，否则无法在程序任意位置「打开一个页面」。而 Android 里你只要 `startActivity(intent)`。

本框架提供的答案是：

- 用 `SnapshotStateList` 保存「当前应显示的所有窗口根视图」，在一个唯一的 `application {}` 中遍历渲染；
- 把每个窗口包装成 `Activity`，提供 `onCreate` / `onStart` / `onResume` / `onPause` / `onStop` / `onDestroy` / `onSaveInstanceState` / `onReStart`；
- 提供 `Intent` 传递参数、回传结果、跨窗口信箱；
- 提供 `ComponentActivity` 让 `ViewModel` 与 `rememberSaveable` 真正可用；
- 提供 `ComponentDialog`（模态/非模态/嵌套）与 `Fragment`（视图拆分 + 独立生命周期）；
- 提供整套「应用状态导出 / 导入」，把桌面端的「下次打开恢复上次状态」变成可能。

> 底层公共能力（Koin 容器、Intent 抽象、Token、EventBus、ViewModel 工厂）见 [`framework/common`](../common/README.md)；平台路径/进程/窗口操作见 [`toolkit`](../../toolkit/README.md)。

---

## 目录

- [它能做什么（对比原生 Compose Desktop）](#它能做什么对比原生-compose-desktop)
- [最简示例](#最简示例)
- [设计原理](#设计原理)
- [核心概念与使用](#核心概念与使用)
  - [1. Application 与启动流程](#1-application-与启动流程)
  - [2. ApplicationRootContent：接管应用根视图](#2-applicationrootcontent接管应用根视图)
  - [3. Context 与 LocalContext](#3-context-与-localcontext)
  - [4. Activity：一个窗口就是一个 Activity](#4-activity一个窗口就是一个-activity)
  - [5. ComponentActivity：ViewModel 与 rememberSaveable](#5-componentactivityviewmodel-与-remembersaveable)
  - [6. Intent：参数、结果、信箱](#6-intent参数结果信箱)
  - [7. ActivityManager：启动、栈与结果回传](#7-activitymanager启动栈与结果回传)
  - [8. ComponentDialog：模态 / 非模态 / 嵌套](#8-componentdialog模态--非模态--嵌套)
  - [9. Fragment：视图拆分与独立生命周期](#9-fragment视图拆分与独立生命周期)
  - [10. 状态保存与恢复](#10-状态保存与恢复)
  - [11. 全局异常处理](#11-全局异常处理)
  - [12. 多 Application（实验特性）](#12-多-application实验特性)
- [注意细节](#注意细节)
- [常见问题](#常见问题)

---

## 它能做什么（对比原生 Compose Desktop）

| 能力 | 原生 Compose Desktop | 本框架 |
| --- | --- | --- |
| 打开一个页面 | 在 `application {}` 里用状态变量控制 `Window` 显隐 | `startActivity(Intent(this, XxxActivity::class.java))` |
| 页面间传参 | 自己传 lambda / 全局状态 | `intent.data { putInt(...) }` + `intent.getData<T>(...)` |
| 页面回传结果 | 自己传回调 | `startActivityForResult(intent) { code, data -> }` |
| 页面生命周期 | 无（只有 `ComposeWindow` 的 Swing 事件） | `onCreate/onStart/onResume/onPause/onStop/onDestroy` |
| ViewModel | 需要自己搭 `ViewModelStoreOwner` | `ComponentActivity` / `Fragment` / `ComponentDialog` 都实现了 |
| `rememberSaveable` | 需要自己提供 `SaveableStateRegistry` | `ComponentActivity` 的 `LinkWindow` 内自动可用 |
| 应用退出后恢复页面状态 | 无 | `ApplicationSaveStateSaver` + `Token` |
| 模态弹窗 | 在 `Window` 内调用 `DialogWindow` | `componentDialog<T>(context, modal = true)` |
| 拆分子视图 | 手动拆 Composable | `Fragment` + `FragmentProvider` |

---

## 最简示例

三个文件即可跑起来：`Main.kt`（入口）、`MainActivity.kt`（主窗口）、`MyApplication.kt`（可选）。

### 1. Main.kt

```kotlin
package com.example

import androidx.compose.desktop.runtime.core.startApplication
import androidx.compose.desktop.runtime.utils.setUncaughtExceptionHandler

fun main() {
    // 可选：注册全局异常处理（会弹出 Swing 错误对话框）
    setUncaughtExceptionHandler()

    startApplication<MainActivity, MyApplication>(
        intentBuilder = {
            data { putInt("from", 1) }
        }
    )
}
```

`startApplication` 会阻塞当前线程（内部进入 Compose 主循环），所以不要再写 `application { }`。

### 2. MainActivity.kt

```kotlin
package com.example

import androidx.compose.desktop.runtime.activity.Activity
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import androidx.savedstate.SavedState

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: SavedState?) {
        super.onCreate(savedInstanceState)

        // 读取启动参数
        val from = intent?.getData<Int>("from")

        setContent {
            Window(
                onCloseRequest = { finish() },          // 点关闭按钮 → 结束 Activity
                visible = mVisibility,                  // 与 show()/hide() 联动
                state = rememberWindowState(),
                title = "Hello ($from)"
            ) {
                // 必须调用 LinkWindow，否则 Activity 收不到窗口生命周期
                LinkWindow {
                    MaterialTheme {
                        var count by remember { mutableStateOf(0) }
                        Button(onClick = { count++ }) {
                            Text("Clicked $count")
                        }
                    }
                }
            }
        }
    }
}
```

### 3. MyApplication.kt（可选）

```kotlin
package com.example

import androidx.compose.desktop.runtime.core.Application
import androidx.jvm.system.core.DesktopToolkitBooster

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // 应用名会参与配置目录路径计算（如 ~/.AppName）
        DesktopToolkitBooster.provideApplicationInfo {
            appName = "HelloDesktop"
            isDisplayNameAuto = true
        }

        // Koin / 日志 / 单实例锁等初始化放这里
    }

    override fun onDestroy() {
        super.onDestroy()
        // 这里是最晚的时机：所有窗口都已释放
    }
}
```

### 运行

```bash
./gradlew :your-app:run
```

或直接 IDE 里运行 `MainKt`。

### 下一步：加一个「第二个页面」

```kotlin
// 在 MainActivity 的按钮里
Button(onClick = {
    val intent = Intent(this@MainActivity, DetailActivity::class.java).apply {
        token = Token("detail")             // 启用状态保存（可选）
        data { putString("id", "42") }
    }
    startActivity(intent)
}) { Text("Open Detail") }
```

```kotlin
class DetailActivity : ComponentActivity() {          // 注意：用 ComponentActivity
    override fun onCreate(savedInstanceState: SavedState?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getData<String>("id")
        val value by rememberSaveable { mutableStateOf(id ?: "") }   // 可跨关闭恢复
        setContent {
            Window(onCloseRequest = { finish() }) {
                LinkWindow { Text("id=$value") }
            }
        }
    }
}
```

---

## 设计原理

### 核心：窗口的显隐 = 重组树节点是否存在

Compose Desktop 中，`Window {}` 一旦被调用就会创建 `SwingWindow` 并显示；一旦离开重组树，`DisposableEffect` 触发 `dispose()`，窗口关闭。框架的整个窗口管理就建立在这条规则上：

```kotlin
// 简化后的原理
val windows = SnapshotStateList<@Composable ApplicationScope.() -> Unit>()

application {
    windows.forEach { it() }          // 列表变化 → 重组 → 窗口显示/关闭
}

windows.add { Window(onCloseRequest = {}) { } }   // 打开
windows.removeAt(0)                                // 关闭
```

对应实现是 [`RootViewEntity`](../../framework/compose/src/jvmMain/kotlin/androidx/compose/desktop/runtime/window/RootViewEntity.kt) 与 `RootViewMgr`：

```kotlin
class RootViewEntity<Scope> {
    var isAttached: Boolean = false
    var rootContent: (@Composable Scope.() -> Unit)? = null
        internal set(value) {
            if (field != null) throw IllegalStateException("rootContent already set")
            field = value
        }
}

class RootViewMgr<Scope> {
    private val views = SnapshotStateList<RootViewEntity<Scope>>()

    @Composable
    fun invoke(scope: Scope) {
        views.forEach { current ->
            key(current) { current.rootContent?.invoke(scope) }   // key 避免无谓重组
        }
    }

    @Synchronized fun attach(window: RootViewEntity<Scope>) { if (window.isAttached) return; views.add(window); window.isAttached = true }
    fun deAttach(window: RootViewEntity<Scope>) { window.isAttached = false; views.remove(window) }
    fun clear()
    fun isEmpty()
}
```

`WindowManager` 持有两个 `RootViewMgr`：`windows`（Activity 根视图，作用域 `ApplicationScope`）与 `dialogs`（无宿主弹窗）。

### 唯一的 `application {}`

```kotlin
class WindowManager {
    private var isPrepared = false
    private var applicationScope: ApplicationScope? = null

    fun prepare() {
        if (isPrepared) throw IllegalStateException("Can't prepare more than once")
        application(exitProcessOnExit = false) {
            this@WindowManager.applicationScope = this
            isPrepared = true
            userInsteadApplicationRootContent?.Invoke(this) { AllWindows() } ?: AllWindows()
        }
    }

    @Composable
    private fun ApplicationScope.AllWindows() {
        windows.invoke(this)
        dialogs.invoke(Unit)
    }
}
```

- `prepare()` **会阻塞调用线程**，所以只在 `Singularity.boot()` 里调用一次；
- `exitProcessOnExit = false` 表示 `application {}` 结束时不要直接 `exitProcess`，由 `Application.release()` 统一收尾（停 Koin、取消协程、`exitProcess(0)`）。

### Activity 不产生生命周期，而是**同步** ComposeWindow 的生命周期

`Activity` 自己有一个 `LifecycleRegistry`，但事件来源是 Compose `Window` 内部的 `LocalLifecycleOwner`（即 `ComposeContainer` 的生命周期）。`LinkWindow` 里做的是：

```kotlin
val lc: LifecycleOwner = LocalLifecycleOwner.current
remember { lc.lifecycle.addObserver(parentLifecycleObserver) }
```

`parentLifecycleObserver` 收到事件后：

```kotlin
override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
    lifecycleListener?.onStateChanged(source, event)
    if (event != Lifecycle.Event.ON_CREATE) syncLife(event)   // ON_CREATE 不同步
    when (event) {
        ON_RESUME -> onResume()
        ON_PAUSE  -> onPause()
        ON_STOP   -> onStop()
        ON_DESTROY -> { source.lifecycle.removeObserver(this); onDestroy() }
        ON_START  -> onStart()
        else -> {}
    }
}
```

`ON_CREATE` 被跳过，因为 `Activity.onCreate()` 在 `Window` 出现之前就已经执行了（此时 Activity 已是 `CREATED`），再同步会导致 lifecycle 状态回退。

### 状态保存链路

```
rememberSaveable
   ↕ SaveableStateRegistry
SavedStateRegistry（ComponentActivity）
   ↕ SavedStateRegistryController.performSave / performRestore
SavedState（androidx.savedstate）
   ↕ ApplicationSaveStateSaver.obtain(Token)
Map<Token, SavedState>  ←→ 业务方序列化到磁盘
```

`ProvideAndroidCompositionLocals` 是这段链路的关键：它用 `DisposableSaveableStateRegistry` 在 `LinkWindow` 内提供 `LocalSaveableStateRegistry`，并把 `performSave()` 的结果写进宿主的 `SavedState`。

### 线程模型

| 位置 | 线程 |
| --- | --- |
| `application {}` 主循环、`Window` 内容、Compose 重组 | Compose 主线程（Swing EDT 系列） |
| `ActivityManager.scope` | `MainUIDispatcher`（`org.jetbrains.skiko.MainUIDispatcher`）+ `SupervisorJob` |
| `Application.scope` | `Dispatchers.Default` + `SupervisorJob`，名为 `Application` |
| `ActivityLauncher.start()` | 在 `ActivityManager.scope` 中 `launch`，因此 Activity 的构造与 `attach` 在 UI 线程 |
| `IntentProcessor` 调用方 | 取决于调用者；`startActivity` 内部会切到 UI 线程 |

> `Application.scope` 用 `Dispatchers.Default`，`ActivityManager.scope` 用 `MainUIDispatcher`。`getServiceInstance<ActivityManager>().scope` 是「UI 线程版」的作用域，需要保证顺序时可以用它。

---

## 核心概念与使用

### 1. Application 与启动流程

#### `Singularity.boot` 做了什么

```kotlin
object Singularity {
    internal lateinit var applicationInternal: Application
    fun isApplicationExist(): Boolean

    inline fun <reified T : Activity, reified R : Application> boot(
        awares: Array<Aware> = arrayOf(),
        applicationContent: ApplicationRootContent? = null,
        noinline intentBuilder: (Intent.() -> Unit)? = null,
    )

    fun boot(
        mainActivity: Class<out Activity>,
        applicationClass: Class<out Application> = Application::class.java,
        awares: Array<Aware> = arrayOf(),
        applicationContent: ApplicationRootContent? = null,
        intentBuilder: (Intent.() -> Unit)? = null,
    )

    fun exitAppOrProcess(err: Boolean)
}
```

内部顺序（`synchronized` 保护，重复启动抛 `IllegalStateException("Application already exists")`）：

1. `PathService.anyClass = mainActivity` —— 用于推算安装目录；
2. `ServiceBooter.bootstrap(logger)` —— 启动 Koin 容器；
3. 取出 `ActivityManager` 并 `prepare()`（目前是空实现，占位）；
4. 取出 `WindowManager`，把 `applicationContent` 存到 `userInsteadApplicationRootContent`；
5. 反射创建 `Application`，生成第一个 `Context`，`attach` + `prepare(awares)`；
6. `firstContext.startMainActivity(mainActivity, intentBuilder)` —— `startActivity(Intent(...))`；
7. `getServiceInstance<WindowManager>().prepare()` —— **阻塞进入 Compose 主循环**。

要点：

- `Application.onCreate()` 的调用时机**早于 Compose 主循环启动**，晚于 `ActivityManager` / `WindowManager` 的创建；
- 主 Activity 的 `startActivity` 发生在 `WindowManager.prepare()` 之前，但 `ActivityLauncher` 内部是 `coroutineScope.launch`，所以真正的 `attach` 会排到主循环开始之后，不会死锁；
- 如果想在 `main()` 里做「主循环前」的准备工作（设置 `skiko.renderApi`、Locale、日志），放在 `Application.onCreate` 或 `startApplication` 之前都可以。

#### `Aware`：把初始化逻辑从 Application 里拆出去

```kotlin
sealed interface Aware
fun interface SyncAware  { fun onCreate(application: Application) }
fun interface AsyncAware { suspend fun onCreate(application: Application) }
```

```kotlin
startApplication<MainActivity, MyApplication>(
    awares = arrayOf(
        SyncAware { app -> println("sync init on ${app.applicationContext}") },
        AsyncAware { app -> /* 可以 suspend，例如读网络配置 */ }
    )
)
```

`Application.awareInvoke` 会把它们按同步/异步分组：同步的直接顺序执行，异步的整体丢到 `scope.launch(Dispatchers.Unconfined)` 里。

#### `Application` 的关键成员

```kotlin
open class Application : ContextWrapper(), LifecycleOwner, InstanceKoinComponent {
    val scope: CoroutineScope by inject(named<Application>())
    val lifecycle: Lifecycle
    open fun onCreate()
    open fun onDestroy()
}
```

- `onCreate()` 里会 `handleLifecycleEvent(ON_CREATE)` 并把 `currentState` 设为 `CREATED`；
- `onDestroy()` 由 `release()` 在所有窗口释放之后调用 —— 注释写得很清楚：「要在所有窗口释放后回调，避免在 onDestroy 中导出 SavedState 时还有窗口没结束导致没有状态存入 Saver」。

`release()` 的完整流程：

```
withContext(MainUIDispatcher)
  ON_DESTROY / currentState = DESTROYED
  activityManager().release()      // 所有 Activity.finish()
  windowManager().release()        // 清空 windows/dialogs
  onDestroy()                      // ← 你的导出逻辑在这里
  windowManager().exitApplication()
finally
  InstanceContext.stopKoin()
  scope.cancel()
  exitProcess(0)
```

#### `exitApp()`

```kotlin
// Context 成员（Activity/Fragment/Dialog 内）
exitApp()

// 任意位置
Singularity.exitAppOrProcess(err = false)

// 或者
getServiceInstance<Application>().exitApp()   // Application 继承自 ContextWrapper
```

`exitAppOrProcess` 在 Application 尚未初始化时会直接 `exitProcess`。

#### `runOnUIThread`

```kotlin
// 顶层函数：使用 Application scope + MainUIDispatcher
runOnUIThread { /* 这里的代码在 UI 线程 */ }

// 带锁（互斥）
runOnUIThread(mutex) { /* 持有 mutex 执行 */ }

// 作为 Application 的扩展
application.runOnUIThread { }
```

注意它是 `launch` 而不是 `invokeOnCompletion` 同步执行，调用后立刻返回。

### 2. ApplicationRootContent：接管应用根视图

默认情况下 `WindowManager` 直接在 `application {}` 中渲染所有窗口。但你可能需要：

- 给所有窗口套 `MaterialTheme`、`CompositionLocalProvider`；
- 在最外层包一层 `UncaughtExceptionContent`；
- 显示系统托盘、或显示不属于框架管理的窗口。

这时实现 `ApplicationRootContent`：

```kotlin
fun interface ApplicationRootContent {
    @Composable
    operator fun ApplicationScope.invoke(windows: ComposableContent)
}
```

> **实现契约：必须调用 `windows()`**，否则框架内所有 Activity 窗口都不会显示。

```kotlin
fun main() {
    setUncaughtExceptionHandler()
    startApplication<SplashActivity, MainApplication>(
        applicationContent = object : ApplicationRootContent {
            @Composable
            override fun ApplicationScope.invoke(windows: ComposableContent) {
                UncaughtExceptionContent {
                    MaterialTheme {
                        windows()          // ← 必须
                        SystemTray()       // 自定义内容
                    }
                }
            }
        },
        intentBuilder = {
            launchMode = LaunchMode.STANDARD
            data { putInt("targetId", 1) }
        }
    )
}
```

#### 系统托盘示例（来自 `composeApp`）

```kotlin
@Composable
fun ApplicationScope.SystemTray() {
    val painter = painterResource("icons/app_icon.svg")
    val settingsIcon = rememberVectorPainter(Icons.Default.Settings)
    val exitIcon = rememberVectorPainter(Icons.Default.ExitToApp)

    FixedSystemTray(
        icon = painter,
        tooltip = "hello",
        onLeftClick = {
            mainActivity?.run {
                show()
                composeWindow?.setWindowSizeState(WindowSizeState.Restore)
            }
        },
        menu = remember {
            buildTrayMenu {
                this + FixedTrayMenuItem("settings", icon = settingsIcon)
                this + TraySeparator
                this + FixedTrayMenuItem("exit", icon = exitIcon) {
                    getServiceInstance<Application>().exitApp()
                }
            }
        }
    )
}
```

`FixedSystemTray` 来自同仓库的 `compose-tray` 模块（`androidx.jvm.system.ui.tray`）。

> 托盘 → 「关窗后不退出」需要配合 `hide()` 而不是 `finish()`，并且要保证 `application {}` 不会因为「没有窗口、没有挂起的协程」而自然结束。`composeApp/Main.kt` 的注释里记录了这个坑：需要在 `application {}` 里保留一个长期挂起的 `collectAsState`，否则窗口隐藏后主循环会自行退出。

### 3. Context 与 LocalContext

```kotlin
@Immutable
abstract class Context {
    abstract val application: Application
    abstract val applicationContext: Context

    abstract fun <T : Any> getService(cls: KClass<T>): T
    abstract fun windowManager(): WindowManager
    abstract fun activityManager(): ActivityManager

    abstract val applicationCoroutineScope: CoroutineScope
    abstract val mainCoroutineScope: CoroutineScope

    open fun exitApp()
    open fun startActivity(intent: Intent)
    open suspend fun startActivityForResult(intent: Intent, callback: ActivityResultCallback)
    abstract suspend fun startActivityForResult(intent: Intent): ActivityResult?
}
```

在 Compose 内容里获取：

```kotlin
@Composable
fun Foo() {
    val context = LocalContext.current       // 由 Activity.LinkWindow / Dialog 等提供
    Button(onClick = { context.startActivity(Intent(context, X::class.java)) }) {
        Text("Open")
    }
}
```

`LocalContext` 是 `staticCompositionLocalOf`，**没有提供者时会直接抛错**：

```kotlin
val LocalContext = staticCompositionLocalOf<Context> {
    noLocalProvidedFor("LocalContext")   // error("CompositionLocal LocalContext not present")
}
```

所以：

- `Activity.LinkWindow` 内可用；
- `ComponentDialog.LinkDialogWindow` 内可用；
- `Fragment.Screen()` 内可用（Fragment 会提供）；
- 裸 `application {}` 里**不可用**。

### 4. Activity：一个窗口就是一个 Activity

#### 类结构

```kotlin
abstract class Activity : ThemedContext(), LifecycleOwner, InstanceKoinComponent {
    protected var lifecycleListener: LifecycleEventObserver?
    val stateSaver by inject<ApplicationSaveStateSaver>()
    var intent by WeakReferenceDelegate<Intent>()
    override val lifecycle: Lifecycle

    var composeWindow: ComposeWindow?   // internal set；由 LinkWindow 赋值
    var mVisibility by mutableStateOf(true)
    val multiApplicationToken: ApplicationScopeToken
    val context get() = this

    fun show()
    fun hide()
    fun finish()

    open fun onCreate(savedInstanceState: SavedState?)
    open fun onSaveInstanceState(outState: SavedState)
    open fun onStart()
    open fun onReStart(intent: Intent? = null)
    open fun onResume()
    open fun onPause()
    open fun onStop()
    open fun onDestroy()

    fun setResult(resultCode: Int, data: Bundle? = null)

    protected open fun setContent(content: ApplicationComposableContent)
    @Composable protected open fun FrameWindowScope.LinkWindow(content: @Composable FrameWindowScope.() -> Unit)

    companion object {
        const val DEFAULT_RESULT_FLOW = "activity_result_flow"
        const val SUCCESS = 1
        const val FAILED = 0
    }
}
```

#### `setContent` + `Window` + `LinkWindow` 三段式

```kotlin
override fun onCreate(savedInstanceState: SavedState?) {
    super.onCreate(savedInstanceState)
    setContent {                                  // ① 提供一个「根视图」
        Window(                                   // ② 内部必须调用 Window
            onCloseRequest = { hide() },          //    关闭按钮的行为由你决定
            visible = mVisibility                 //    ③ 绑定 mVisibility 才能 show/hide
        ) {
            LinkWindow {                          // ④ 必须调用，建立生命周期关联
                MyScreen()                        //    你的 UI
            }
        }
    }
}
```

三个约束：

1. `setContent` 的 lambda 里**必须**调用 `Window`（或 `DialogWindow` 做纯弹窗式页面，少见）；
2. `Window` 的 `visible` 参数**必须**绑定 `mVisibility`，否则 `show()` / `hide()` 无效；
3. `Window` 的内容里**必须**调用 `LinkWindow`，否则 Activity 不会同步窗口生命周期，`onResume` 等回调不会触发，`rememberSaveable` 也不可用。

#### 生命周期映射

`Activity` 的 KDoc 给出了权威表格：

| Swing listener callbacks | Lifecycle event | 状态变化 |
| --- | --- | --- |
| `windowIconified`（最小化） | `ON_STOP` | STARTED → CREATED |
| `windowDeiconified`（还原） | `ON_START` | CREATED → STARTED |
| `windowLostFocus`（失焦/隐藏） | `ON_PAUSE` | RESUMED → STARTED |
| `windowGainedFocus`（聚焦/恢复显示） | `ON_RESUME` | STARTED → RESUMED |
| `dispose`（窗口销毁） | `ON_DESTROY` | CREATED → DESTROYED |

补充说明：

- `ON_CREATE` 由 `Activity.onCreate()` 自己触发，不来自窗口；
- 自 `androidx.lifecycle 2.9.0-alpha06` 起 `DESTROYED` 是终态，任何从它迁出的操作都会抛 `IllegalStateException`；
- 想打印生命周期变化，可以设置 `lifecycleListener`（`protected`，子类在 `init {}` 里赋值）：

```kotlin
init {
    lifecycleListener = object : LifecycleEventObserver {
        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            println("state -> $event")
        }
    }
}
```

#### `hide()` 与 `finish()` 的区别

| 方法 | 行为 | 生命周期 | 能否再显示 |
| --- | --- | --- | --- |
| `hide()` | `mVisibility = false` | 窗口隐藏 → `ON_PAUSE`（可能连带 `ON_STOP`） | `show()` 可以恢复 |
| `finish()` | 从 `WindowManager` 移除根视图 | 窗口 `onDispose` → `ON_DESTROY` → `onDestroy()` | 不能（对象已结束） |

`finish()` 的内部逻辑：

```kotlin
open fun finish() {
    if (rootViewEntity.isAttached) {
        windowManager().deAttachWindow(rootViewEntity)
        composeWindow = null
    } else {
        if (multiApplicationToken.isExist) {
            multiApplicationToken.dismiss()
        } else {
            syncLife(ON_DESTROY)   // 没有界面时直接结束生命周期
            onDestroy()
        }
    }
}
```

`onDestroy()` 里做三件事：

```kotlin
open fun onDestroy() {
    savedState?.let { onSaveInstanceState(it) }   // ① 保存状态
    finished = true
    activityManager().remove(idn)                 // ② 从 ActivityManager 注销
    dialogsMgr.clear()                            // ③ 清理挂在本 Activity 上的弹窗
}
```

所以**关闭窗口必须走 `finish()`**，直接 `window.dispose()` 会绕过状态保存。

#### `onReStart`

`SINGLE_INSTANCE` 模式再次启动同类型 Activity 时不会新建实例，而是回调：

```kotlin
open fun onReStart(intent: Intent? = null) { }
```

`composeApp` 里常见写法是让它恢复显示并置顶：

```kotlin
override fun onReStart(intent: Intent?) {
    super.onReStart(intent)
    show()
    composeWindow?.setWindowSizeState(WindowSizeState.Restore)
}
```

#### `composeWindow` 与窗口尺寸

```kotlin
enum class WindowSizeState { Max, Min, Restore }
fun ComposeWindow.setWindowSizeState(state: WindowSizeState)
```

```kotlin
// 最大化
composeWindow?.setWindowSizeState(WindowSizeState.Max)
// 最小化
composeWindow?.setWindowSizeState(WindowSizeState.Min)
// 还原
composeWindow?.setWindowSizeState(WindowSizeState.Restore)
```

> `composeWindow` 只在窗口已经显示后才非 null，`onCreate` 阶段拿不到。

### 5. ComponentActivity：ViewModel 与 rememberSaveable

#### 为什么需要它

原生 `Activity` 只实现了 `LifecycleOwner`，不提供：

- `ViewModelStoreOwner` → `viewModels()` 不可用
- `SavedStateRegistryOwner` → `SavedStateHandle` 不可用
- `LocalSaveableStateRegistry` → `rememberSaveable` 不可用（或无法持久化）

`ComponentActivity` 把这四件事全补齐：

```kotlin
open class ComponentActivity : Activity(),
    ViewModelStoreOwner,
    HasDefaultViewModelProviderFactory,
    SavedStateRegistryOwner
```

#### 初始化时序

```kotlin
init {
    lifecycle.addObserver { if (event == ON_DESTROY) viewModelStore.clear() }
    savedStateRegistryController.performAttach()
    enableSavedStateHandles()
}

override fun onCreate(savedInstanceState: SavedState?) {
    savedStateRegistryController.performRestore(savedState)   // ← 先恢复
    super.onCreate(savedInstanceState)
}

override fun onSaveInstanceState(outState: SavedState) {
    super.onSaveInstanceState(outState)
    savedStateRegistryController.performSave(outState)        // ← 再保存
}
```

`LinkWindow` 重写为：

```kotlin
ProvideAndroidCompositionLocals(
    id = idn.toString(),
    context = this@ComponentActivity,
    activityLifecycleOwner = this@ComponentActivity,
    fragmentLifecycleOwner = null,
    viewModelStoreOwner = this@ComponentActivity,
    savedStateRegistryOwner = this@ComponentActivity,
) {
    content()
}
dialogsMgr.invoke(Unit)
```

`idn` 是 `token ?: Token(qualifiedName)` —— 这个 id 同时是 `SaveableStateRegistry` 的 key 前缀，因此**不同 token 的同一 Activity 不会互相污染**。

#### `rememberSaveable` 示例

```kotlin
class StateTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: SavedState?) {
        super.onCreate(savedInstanceState)
        setContent {
            Window(onCloseRequest = { finish() }) {
                LinkWindow {
                    val text = rememberSaveable { mutableStateOf("init") }
                    Column {
                        Text(text.value)
                        Button(onClick = { text.value = "随机数 ${Random.nextInt()}" }) {
                            Text("生成新值，关闭后再打开看看")
                        }
                    }
                }
            }
        }
    }
}
```

启动它时必须给 `token`，否则 `savedState == null`，什么都不会保存：

```kotlin
val intent = Intent(this, StateTestActivity::class.java).apply {
    token = Token("state-test")
}
startActivity(intent)
```

#### ViewModel 的三种获取方式

`ComponentActivity` 默认工厂是 `MySavedStateViewModelFactory`，它的规则是「**恰好一个 `SavedStateHandle` 参数** → 注入；否则走无参构造」。

**① 最简：仅依赖 `SavedStateHandle`**

```kotlin
class MyViewModel(val handle: SavedStateHandle) : ViewModel()

class MyActivity : ComponentActivity() {
    val vm: MyViewModel by viewModels()
}
```

**② 带自定义参数：自备 Factory + `extrasProducer`**

```kotlin
class TestViewModel1(
    val savedStateHandle: SavedStateHandle,
    val i: Int,
) : ViewModel() {
    val value = savedStateHandle.getStateFlow("odd", i)

    companion object {
        val key = object : CreationExtras.Key<Int> {}
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
                return TestViewModel1(extras.createSavedStateHandle(), extras[key] ?: 90) as T
            }
        }
    }
}

class VMTestActivity : ComponentActivity() {
    // 注意：使用 MutableCreationExtras 手动构造时，必须自己放入两个 owner key
    val vm1: TestViewModel1 by viewModels<TestViewModel1>(
        extrasProducer = {
            MutableCreationExtras().apply {
                this[TestViewModel1.key] = intent?.getData<Int>("random") ?: 11
                this[SAVED_STATE_REGISTRY_OWNER_KEY] = this@VMTestActivity
                this[VIEW_MODEL_STORE_OWNER_KEY] = this@VMTestActivity
            }
        },
        factoryProducer = { TestViewModel1.factory }
    )
}
```

**③ 手动创建（适合在 `onCreate` 里立刻拿到实例）**

```kotlin
val vm = ViewModelProvider.create(
    owner = this,
    creationExtras = mutableCreationExtrasOf {
        this[TestViewModel1.key] = intent?.getData<Int>("random") ?: 11
    },
    factory = TestViewModel1.factory,
)[TestViewModel1::class]
```

`mutableCreationExtrasOf` 会**基于 owner 的默认 extras** 派生，所以 `SAVED_STATE_REGISTRY_OWNER_KEY` / `VIEW_MODEL_STORE_OWNER_KEY` 已经在了，不需要手动塞。

#### 协程作用域

`Activity` 实现了 `LifecycleOwner`，因此可以直接用 `lifecycleScope`：

```kotlin
import androidx.lifecycle.lifecycleScope

lifecycleScope.launch {
    startActivityForResult(intent) { code, data -> /* ... */ }
}
```

注意 `lifecycleScope` 使用的是 `SupervisorJob() + Dispatchers.Main.immediate`。在桌面端 `Dispatchers.Main` 会解析到 Swing EDT（需要 `kotlinx-coroutines-swing` 在 classpath 上）。

> 坑：如果你自己建 `CoroutineScope(Dispatchers.Default)` 时漏了 `SupervisorJob()`，一个子协程抛异常会连带取消整个作用域，后续 `launch` 全部失效。`composeApp/MainActivity.kt` 里专门留了一条注释说明这一点。

### 6. Intent：参数、结果、信箱

#### 类型

```kotlin
@Deprecated("use LaunchActivityIntent instead.", replaceWith = ReplaceWith("LaunchActivityIntent"))
typealias Intent = LaunchActivityIntent

class LaunchActivityIntent : OperateIntent {
    var from: Class<out Any>?        // internal set
    var targetActivity: Class<out Activity>   // internal set
    var launchMode: LaunchMode = LaunchMode.STANDARD
    var mData: Bundle               // private set
    var token: Token?               // 只能设置一次
    var multiApplication: Boolean   // @ApiStatus.Experimental

    constructor(from: Any? = null, to: Class<out Activity>, launchMode: LaunchMode = LaunchMode.STANDARD)

    inline fun <reified T> getData(name: String): T?
    fun data(action: Bundle.() -> Unit): Intent
    fun <T : Any> getMailBox(name: String): MutableSharedFlow<T>
    val activityResultFlow: MutableSharedFlow<ActivityResult>
    suspend fun collectActivityResult(collector: FlowCollector<ActivityResult>)
}
```

#### 传参

```kotlin
val intent = Intent(this, DetailActivity::class.java).apply {
    data {
        putInt("id", 42)
        putString("title", "详情")
        putBoolean("editable", true)
    }
}

// 被启动方
val id = intent?.getData<Int>("id")
val title = intent?.getData<String>("title")

// 或者直接读 Bundle
val same = intent?.mData.get<Int>("id")
```

`getData` 是 `mData.get(name) as? T?`：类型不匹配或不存在都会返回 `null`，不会抛 `ClassCastException`。

#### 传结果的三种写法

**① `startActivityForResult(intent, callback)`（推荐）**

```kotlin
// 启动方
lifecycleScope.launch {
    startActivityForResult(intent) { resultCode, data ->
        println("code=$resultCode data=$data")
    }
}

// 被启动方
setResult(Activity.SUCCESS, bundleOf("odd" to value))
finish()
```

**② 直接订阅 `activityResultFlow`**

```kotlin
ActivityManager.startActivityForResult(intent).collect { result ->
    println("${result.resultCode} ${result.data}")
}
```

**③ 自定义信箱（双向通信，不限于「结果」）**

```kotlin
// A 启动 B 之前
val box = intent.getMailBox<Int>("progress")
lifecycleScope.launch {
    box.collect { percent -> println("进度 $percent") }
}

// B 内部
intent?.getMailBox<Int>("progress")?.tryEmit(50)
```

`getMailBox` 返回 `MutableSharedFlow(replay = 1)`：replay=1 保证「先发后收」也能收到最后一个值。

> `startActivityForResult` 的挂起版本内部就是 `intent.collectActivityResult { ... }`，而 `activityResultFlow` 用的 key 是 `Activity.DEFAULT_RESULT_FLOW = "activity_result_flow"`。

#### `token` 的语义

```kotlin
var token: Token? = null
    set(value) {
        if (field != null) throw IllegalArgumentException("Token already set")
        field = value
    }
```

- `token == null`：Activity 使用 `Token(qualifiedName)` 作为 `idn`，**不启用**状态保存/恢复；
- `token != null`：`idn == token`，`savedState` 从 `ApplicationSaveStateSaver` 取，`ComponentActivity` 的 `rememberSaveable` / `SavedStateHandle` 才会工作。

#### `LaunchMode`

| 值 | 行为 |
| --- | --- |
| `STANDARD`（默认） | 每次都新建实例 |
| `SINGLE_INSTANCE` | 栈中已有同类型（按 `class.name` 比较）则回调 `onReStart(intent)`，不新建 |

`SINGLE_INSTANCE` 的匹配只看**类名**，不看 token。所以两个不同 token 的同类型 Activity 不会并存。

### 7. ActivityManager：启动、栈与结果回传

```kotlin
class ActivityManager : InstanceKoinComponent {
    val scope: CoroutineScope                   // MainUIDispatcher + SupervisorJob
    val activityStack: List<Activity>
    val launcherManager: IActivityLauncher

    fun launchActivity(intent: LaunchActivityIntent)
    fun prepare(): ActivityManager
    operator fun get(uuid: Token?): Activity?
    operator fun get(cls: Class<out Activity>): Activity?
    fun register(uuid: Token, activity: Activity)
    fun remove(uuid: Token)
    fun release()

    companion object {
        fun startActivity(intent: Intent)
        suspend fun startActivityForResultSuspend(intent: Intent, callback: ActivityResultCallback)
        fun startActivityForResult(intent: Intent, callback: ActivityResultCallback)
        fun startActivityForResult(intent: Intent): SharedFlow<ActivityResult>
    }
}
```

#### `remove(uuid)` 的一个已知问题

```kotlin
fun remove(uuid: Token) {
    activityMap.remove(uuid)          // 先移除
    stack.remove(activityMap[uuid])   // 再按 uuid 取，此时已经是 null
}
```

`activityMap.remove(uuid)` 返回被移除的值，但这里没有用它。结果是 `stack` 中会残留已销毁的 Activity。

影响：

- `activityStack` 会越来越长；
- `SINGLE_INSTANCE` 的匹配基于 `stack`，可能命中一个已销毁的实例（其 `mVisibility` 已无效、`composeWindow == null`）。

规避：

- 避免用 `SINGLE_INSTANCE` + 频繁开关同类型窗口的组合；
- 或者在 `onReStart` 里做防御（`if (rootViewEntity.isAttached) show() else 重新 setContent`）；
- 或在自己的分支里修正 `remove` 的实现（记录 `val removed = activityMap.remove(uuid); stack.remove(removed)`）。

#### 从任意位置启动

```kotlin
// ① Context 成员
startActivity(intent)

// ② ActivityManager 静态方法
ActivityManager.startActivity(intent)

// ③ IntentProcessor
val processor = getServiceInstance<IntentProcessor>()
processor.process(intent)

// ④ Launcher
getServiceInstance<IActivityLauncher>().start(intent)
```

四条路径最终都走 `ActivityLauncher.start()`。

### 8. ComponentDialog：模态 / 非模态 / 嵌套

#### 原理

`DialogWindow` 的表现取决于它被调用的位置：

```kotlin
application {
    if (windowVisible) {
        Window(onCloseRequest = {}) {
            DialogWindow(onCloseRequest = {}) { }   // 有父窗口 → 模态
        }
    }
    DialogWindow(onCloseRequest = {}) { }            // 无父窗口 → 非模态
}
```

`ComponentDialog` 通过把「DialogWindow 根视图」挂到不同的 `RootViewMgr` 上来控制模态性：

| `componentDialog(..., modal = )` | 挂载位置 | 效果 |
| --- | --- | --- |
| `modal = true` 且 `context is Activity` | `Activity.attachDialog` → `Activity.dialogsMgr` | 模态（阻塞宿主窗口） |
| `modal = false` | `WindowManager.attachDialog` → 全局 `dialogs` | 非模态 |
| `nestDialog<T>()` | 父 Dialog 的 `childrenDialogs` | 嵌套模态（相对父弹窗） |

#### 定义一个 Dialog

```kotlin
class TestDialog : ComponentDialog() {
    override fun onCreate(savedInstanceState: SavedState?) {
        super.onCreate(savedInstanceState)
        setContentView {
            DialogWindow(
                onCloseRequest = { dismiss() },
                visible = mVisibility.value,      // 注意这里是 .value
            ) {
                LinkDialogWindow {                // 必须调用
                    MaterialTheme {
                        Column {
                            Text("I'm a dialog")
                            Button(onClick = {
                                nestDialog<TestDialog>().show()    // 嵌套
                            }) { Text("Open nested") }
                        }
                    }
                }
            }
        }
    }
}
```

#### 创建与显示

```kotlin
// 顶层函数
val dialog = componentDialog<TestDialog>(
    context = this,          // 任意 Context
    modal = true,
    token = Token("dialog-1")
)
dialog.show()

// 或者在 Dialog 内部生成嵌套
val nested = nestDialog<TestDialog>(Token("nested-1"))
nested.show()
```

#### `show` / `hide` / `dismiss`

| 方法 | 行为 | 可再次显示 |
| --- | --- | --- |
| `show()` | `mVisibility=true` + 挂载根视图 | — |
| `hide()` | `mVisibility=false` | 可以 |
| `dismiss()` | 卸载根视图 → `DialogWindow.onDispose` → `ON_DESTROY` → `onDestroy()` | **不可以**，抛 `IllegalStateException("Already destroyed, cannot show again")` |

`hide()` 之后再 `show()` 是安全的；`dismiss()` 之后对象作废，只能新建。

#### 生命周期

`ComponentDialog` 继承自 `BasicComponent()`，生命周期**跟随 `DialogWindow` 本身**，不跟随宿主。因此：

- `attach(token, context, hostLifecycle)` 中的 `hostLifecycle` 参数被忽略（实现里写死传 `null`）；
- 弹窗关闭即 `ON_DESTROY`，与宿主 Activity 是否还活着无关。

> 也就是说：`modal = true` 只是视觉/交互上的模态，不会让弹窗的生命周期挂在 Activity 上。

### 9. Fragment：视图拆分与独立生命周期

#### 定位

`Fragment` **不是窗口**，它只是在宿主（Activity / ComponentDialog）的 Compose 内容里调用 `Screen()` 来显示的一段视图。它的价值是：

1. 拆解复杂 Activity 的视图结构；
2. 每块视图有自己的 `ViewModel` / `rememberSaveable` / `SavedState`；
3. 每个 Fragment 可以有独立的生命周期（跟随宿主，或独立存在）。

#### 定义

```kotlin
class Fragment1 : Fragment() {
    override fun onCreateView(): ComposableView {
        return ComposableView {
            val text = rememberSaveable { mutableStateOf("rememberSaveable") }
            Column {
                Text(text.value)
                Button(onClick = { text.value = "v=${Random.nextInt()}" }) { Text("随机") }
            }
        }
    }
}
```

#### 三种创建方式

```kotlin
// ① 跟随 Activity 生命周期（Lazy）
val f1 by lifecycle.ownedFragment<Fragment1>(this, Token("123"))

// ② Activity 专用简化写法
val f2 by activityOwnedFragment<Fragment1>(this, Token("124"))

// ③ 由 FragmentProvider 统一管理（同一 hostLifecycle 下复用实例）
val provider = FragmentProvider(this.lifecycle)
val f3 = provider.obtain<Fragment1>(this, Token("123_1"))

// 显示
f3.Screen()
```

#### 显示、隐藏、销毁

```kotlin
f1.Screen()        // 在 @Composable 作用域内调用
f1.hide()          // mVisibility=false
f1.show()          // mVisibility=true
f1.finish()        // 结束（会触发状态保存）

provider.remove(Token("123_1"))   // 从 provider 移除，并 finish()
provider.remove(Fragment1::class) // 未传 token 时用这个
```

#### 状态保存的两个前提

`composeApp/TestFragmentActivity.kt` 的注释总结得很好：

> 通过 `FragmentProvider` 创建的 Fragment 会在调用 `FragmentProvider.remove` 时自动保存状态。
> 未通过 `FragmentProvider` 创建的 Fragment，会跟随 attach 的 host lifecycle 在 `ON_DESTROY` 时自动保存状态。
> **单纯地用 `if` 条件显示/不显示 Fragment 是不会自动保存状态的。**

也就是说：`hide()`/`show()` 只是可见性切换，不触发保存；只有 `finish()`（或宿主销毁、provider 移除）才保存。

#### 独立生命周期

```kotlin
val f = fragment<Fragment1>(hostLifecycle = null, context = this, token = Token("x"))
// 之后可以手动绑定
f.attachHostLifecycle(someLifecycle)
```

`attachHostLifecycle` 只能调用一次，重复调用抛异常。

### 10. 状态保存与恢复

#### 完整链路（以 Activity 为例）

1. 启动时给 `intent.token` 赋值；
2. `Activity.attach` 里 `onCreate(savedState)`，`savedState` 来自 `stateSaver.obtain(token)`；
3. `ComponentActivity.onCreate` 调 `savedStateRegistryController.performRestore(savedState)`；
4. `LinkWindow` → `ProvideAndroidCompositionLocals` → `DisposableSaveableStateRegistry` 注册 `SavedStateProvider`；
5. 关闭时 `finish()` → 窗口移除 → `ON_DESTROY` → `Activity.onDestroy()` → `onSaveInstanceState(savedState)`；
6. `ComponentActivity.onSaveInstanceState` 里 `performSave(outState)`，把 `rememberSaveable` 的内容写回 `SavedState`；
7. `Application.onDestroy` 里 `getService(ApplicationSaveStateSaver::class).export()` 拿到全部状态；
8. 业务方把 `Map<Token, SavedState>` 序列化到磁盘；
9. 下次启动 `Application.onCreate` 里 `import(...)`，回到第 2 步。

#### 序列化怎么写

`androidx.savedstate.SavedState` 本质是一个 `Map<String, Any?>` 包装。示例项目里只是 `logger.info` 打印，实际项目建议：

```kotlin
// 保存
val exported = getService(ApplicationSaveStateSaver::class).export()
val json = Json.encodeToString(exported.mapValues { it.value.toMap() })
File(configDir, "state.json").writeText(json)

// 恢复
val map = Json.decodeFromString<Map<String, Map<String, Any?>>>(json)
getService(ApplicationSaveStateSaver::class).import(
    map.mapValues { (_, v) -> savedState { putAll(v) } }
)
```

> `SavedState` 里的值必须是可序列化的类型（基本类型、String、Bundle、数组等）。`DisposableSaveableStateRegistry` 原本对 `SnapshotMutableState` / `Function` 做了类型限制，但代码注释说明「本想序列化到磁盘，干脆放开类型限制」，最终 `canBeSaved` 恒为 `true`。**这意味着一部分非法类型会在你真正序列化时才会炸**，请自行约束。

#### 各组件保存能力对照

| 组件 | 需要 token | 自动保存时机 |
| --- | --- | --- |
| `Activity` | 是 | `finish()` → `onDestroy()` |
| `ComponentActivity` | 是 | 同上，另加 `SavedStateRegistry` |
| `ComponentDialog` | 是 | `dismiss()` → `onDestroy()` |
| `Fragment` | 是 | `finish()` / 宿主 `ON_DESTROY` / `provider.remove` |
| `ComponentJFrame`（Swing） | 是 | `DISPOSE_ON_CLOSE` → `onDestroy()` |

### 11. 全局异常处理

#### 注册

```kotlin
// 使用默认 Swing 弹窗
setUncaughtExceptionHandler()

// 自定义按钮行为
try {
    setUncaughtExceptionHandler(
        icon = null,
        title = "出错了",
        copyAction = { copyToClipboard(it.stackTraceToString()) },
        continueAction = { /* 忽略 */ },
        closeAppAction = { Singularity.exitAppOrProcess(true) },
    )
} catch (e: Throwable) { /* ... */ }

// 完全接管弹窗
setUncaughtExceptionHandler(dialog = { window, throwable ->
    MyErrorDialog(window, throwable)
})
```

#### Compose 侧捕获

`WindowExceptionHandlerFactory` 是 Compose Desktop 的机制，需要在 `application {}` 内提供：

```kotlin
application {
    UncaughtExceptionContent {      // 内部提供 LocalWindowExceptionHandlerFactory
        windows()
    }
}
```

所以推荐把它放进 `ApplicationRootContent`：

```kotlin
override fun ApplicationScope.invoke(windows: ComposableContent) {
    UncaughtExceptionContent {
        MaterialTheme { windows() }
    }
}
```

#### 默认弹窗的行为

`DefaultUncaughtExceptionHandler` 对两类异常分别处理：

| 来源 | 处理 |
| --- | --- |
| `Thread.uncaughtException`（main 线程、协程线程等） | 弹非模态对话框，按钮：复制错误日志 / 忽略 / 关闭应用 |
| Compose `WindowExceptionHandler` | 先 `dispose()` 出错的窗口，再弹对话框，按钮：复制日志并关闭 / 关闭应用 |

默认 `closeAppAction = { Singularity.exitAppOrProcess(true) }`。

> 主线程早期异常（`main()` 还没进入 Compose）会先弹框架的对话框；如果进程随后退出，JDK 还会再弹一个 "failed to launch JVM"。这是正常现象。

### 12. 多 Application（实验特性）

```kotlin
@ApiStatus.Experimental
var multiApplication: Boolean = false
```

当 `intent.multiApplication == true` 时，`Activity.setContent` 不走全局 `WindowManager`，而是**新起一个线程**，在其中再调用一次 `application(exitProcessOnExit = false) { ... }`：

```kotlin
val thread = thread {
    setUncaughtExceptionHandler()
    try {
        application(exitProcessOnExit = false) {
            if (!multiApplicationToken.destroy) {
                UncaughtExceptionContent { content() }
            }
        }
    } catch (ignore: InterruptedException) { }
}
multiApplicationToken.thread = thread
```

`finish()` 时如果 `multiApplicationToken.isExist`，就 `multiApplicationToken.dismiss()` 来结束这个独立 application。

**源码注释里的警告**：

> 这是测试功能，目前发现启用此功能会造成状态偶尔无法恢复（关闭 Activity 后比较短的间隔内再次启动 Activity，无法恢复状态）。

建议：除非确实需要「两个完全独立的 Compose 主循环」，否则优先用普通 `Activity`。`composeApp` 里的示例虽然打开了很多 `multiApplication = true`，但那只是演示。

---

## 注意细节

1. **`Window` 的 `visible` 必须绑定 `mVisibility`。**
   否则 `show()` / `hide()` 什么都不做。`mVisibility` 是 `var mVisibility by mutableStateOf(true)`。

2. **`LinkWindow` 必须在 `Window` 内部、内容最外层调用。**
   它负责：注册生命周期观察者、记录 `composeWindow`、提供 `LocalContext` 与 `ActivityLifecycleOwner`、渲染 `dialogsMgr`。漏掉它会导致：生命周期不触发、`LocalContext` 报错、模态弹窗不显示。

3. **`ComponentDialog` 的 `mVisibility` 是 `MutableState<Boolean>`，要用 `.value`。**
   `Activity.mVisibility` 是委托属性（`Boolean`），两者 API 不同，不要混。

4. **`ComponentDialog` 的 `LinkDialogWindow` 必须调用。**
   它内部提供 `ProvideAndroidCompositionLocals`（`fragmentLifecycleOwner = this`、`viewModelStoreOwner = this`、`savedStateRegistryOwner = this`）并渲染嵌套弹窗。

5. **`dismiss()` 后不能 `show()`。**
   `destroyed` 标记一旦置位，`show()` 直接抛 `IllegalStateException`。需要重开就 `componentDialog<T>()` 新建一个。

6. **`token` 只能赋值一次，且所有组件共用一套 Token 命名空间。**
   Activity `Token("a")` 和 Dialog `Token("a")` 会共享同一个 `SavedState`。命名时带上类型前缀：`Token("activity-main")`、`Token("dialog-settings")`。

7. **`Activity.finish()` 必须在 UI 线程调用。**
   它内部操作 `SnapshotStateList`（`deAttachWindow`）。从后台线程调用请先切 `runOnUIThread {}`。

8. **不要在 `onCreate` 里读 `composeWindow`。**
   此时窗口尚未创建，`composeWindow == null`。需要窗口对象请在 `LinkWindow` 内或 `onResume` 之后使用。

9. **`SINGLE_INSTANCE` 的 `onReStart` 不会改变生命周期状态。**
   KDoc 明确写了：「此回调不会改变 Window 或 Activity 的生命周期状态」。需要恢复显示要自己 `show()`。

10. **`Fragment.Screen()` 必须在 `@Composable` 作用域内调用。**
    它内部会 `ProvideAndroidCompositionLocals`，在普通函数里调用编译不过。

11. **`FragmentProvider` 的默认 key 是 `cls.hashCode().toString()`。**
    不传 token 时，同类型 Fragment 只能有一个实例。要多个实例必须传不同 token。

12. **`FragmentProvider.remove(Token)` 的 key 与 `obtain` 的 key 必须一致。**
    `obtain` 用 `token?.value`，`remove(Token)` 也用 `token?.value`；`obtain` 不传 token 时用 `cls.hashCode()`，`remove(cls)` 同理。

13. **`ActivityManager.remove` 有栈残留问题**（见上文），避免过度依赖 `SINGLE_INSTANCE`。

14. **`multiApplication = true` 时 `ApplicationRootContent` 不生效。**
    独立 application 的根内容直接是 Activity 的 `content()`，没有经过 `WindowManager`，因此托盘、全局主题等都不会应用到它上面。

15. **升级 Compose 版本后要回归 `ProvideAndroidCompositionLocals`。**
    它覆盖了 Compose 自带的 `LocalSaveableStateRegistry` 等，Compose 内部实现变更可能导致 `rememberSaveable` 失效。

16. **`application {}` 自然结束会退出进程（除非 `exitProcessOnExit = false`）。**
    `WindowManager.prepare()` 已经传了 `false`，但如果你自己额外调 `application {}`，记得处理这一点。

17. **`--add-opens` 风险。**
    `DisposableSaveableStateRegistry` 用反射访问了 `SavedState` 的 `map` 字段与 `Bundle` 的 `bundleData` 字段。若未来 JDK 收紧模块访问，需要在启动参数加 `--add-opens=java.base/...`。目前 JDK 17/21/27 上可用。

18. **`getData<T>` 的 `T` 是擦除的。**
    它靠 `Bundle.get(name)` 的运行时类型做 `as? T?`，泛型不参与判断。`getData<List<String>>("x")` 可能拿到 `ArrayList` 并成功，也可能在后续使用中 `ClassCastException`。

---


## 常见问题

### Q1. 点了关闭按钮程序没退出，只是窗口消失？

看你把 `onCloseRequest` 绑成了什么：

- `{ finish() }`：结束 Activity，如果这是最后一个窗口，`application {}` 会结束 → `release()` → `exitProcess(0)`；
- `{ hide() }`：只是隐藏，程序还在跑（托盘应用通常这么做）。

### Q2. `onResume()` 一直不触发？

90% 是因为漏了 `LinkWindow`，或者 `LinkWindow` 没在 `Window` 内部调用。

检查列表：

1. `Window` 是否真的显示了（`visible = mVisibility` 且 `mVisibility == true`）；
2. `LinkWindow` 是否在 `Window` 的 content lambda 里；
3. 窗口是否获得焦点（失焦会 `ON_PAUSE`）。

### Q3. `rememberSaveable` 关闭再打开还是初始值？

四个必要条件缺一不可：

1. 宿主是 `ComponentActivity`（不是裸 `Activity`）；
2. `intent.token` 非空；
3. 关闭走 `finish()`（触发 `onDestroy` → `onSaveInstanceState`）；
4. 状态真的被 `import` 回去了（`Application.onCreate` 里检查）。

### Q4. `viewModels()` 报 `IllegalStateException: Your activity is not yet attached to the Application instance`？

`ComponentActivity.viewModelStore` 的 getter 里有：

```kotlin
check(Singularity.isApplicationExist()) { "Your activity is not yet attached to the Application instance..." }
```

说明你在 `Singularity.boot` 完成之前就构造/访问了 Activity。常见于在 `Application.onCreate` 里 `new XxxActivity()`，不要在 `Application` 里直接 new 窗口。

### Q5. 模态弹窗为什么没有「模态」效果？

- 检查 `componentDialog(..., modal = true)`；
- 检查 `context is Activity`：代码里只有 `modal && context is Activity` 才会挂到 Activity 的 `dialogsMgr`，否则退化成全局非模态；
- `Fragment` 的 `context` 通常是宿主 Activity，这是 OK 的；`ComponentDialog.nestDialog` 走的是父弹窗路径（`parent != null` 优先），所以嵌套弹窗总是相对父弹窗模态。

### Q6. 关闭主窗口后托盘还在，但点击托盘打不开窗口？

`hide()` 只是把 `visible` 置 `false`，`Window` 仍在重组树上；此时调用 `show()` 应该能恢复。如果打不开，检查：

1. `mVisibility` 是否真的绑定到了 `Window.visible`；
2. 是否在托盘回调里用了旧 Activity 引用（`mainActivity` 是全局 `var`，可能已被置 null）；
3. 是否用了 `finish()`（`finish()` 之后对象作废，`show()` 不会重建窗口）。

### Q7. 应用退出时 `export()` 是空的？

- `Application.onDestroy` 是否被调用（用日志确认）；
- 组件是否都走 `finish()` 关闭；
- `release()` 中 `onDestroy()` 在 `windowManager.release()` 之后调用，理论上状态已经全部写入。如果为空，多半是 `onSaveInstanceState` 没被走到（窗口被 `dispose()` 而不是走框架的 `finish()`）。

### Q8. 窗口白屏 / 花屏 / 菜单不显示？

优先尝试切换渲染后端（在创建第一个窗口之前）：

```kotlin
System.setProperty("skiko.renderApi", "SOFTWARE")   // 或 "OPENGL"
```

项目根 README 曾记录「Linux 下菜单不显示」与 `skiko.renderApi` 有关。

### Q9. 可以在 `Activity` 里用 `Dispatchers.Main` 吗？

可以，但需要 `kotlinx-coroutines-swing` 在 classpath 上（`framework-compose` 已传递依赖）。Compose Desktop 的 `MainUIDispatcher` 是 `org.jetbrains.skiko.MainUIDispatcher`，与 `Dispatchers.Main` 不完全等价；框架内部的 `ActivityManager.scope` 用的是 `MainUIDispatcher`。

### Q10. 我能在 `Fragment` 里再开一个 `Window` 吗？

技术上可以（`Fragment` 的内容也是普通 Compose），但 `Window` 必须位于 `application {}` 作用域内。`Fragment` 没有 `ApplicationScope`，所以不要这么做。需要独立窗口请用 `ComponentDialog` 或 `Activity`。

