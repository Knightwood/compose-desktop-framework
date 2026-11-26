# framework-common

`framework-common` 是整个桌面框架的**公共层**。它不依赖 Compose，也不依赖任何具体的 UI 框架，只负责三件事：

1. **服务容器**：启动并托管一个独立的 Koin 实例（`InstanceContext`），通过 `ServiceLoader` 自动装载各平台/各模块注册的 Koin `Module`。
2. **操作意图（Intent）抽象与分发**：定义 `OperateIntent`、`LaunchMode`、`ComponentResult`，并由 `IntentProcessor` 按 intent 的具体类型把它路由给对应的处理器。
3. **状态与生命周期基础设施**：`ApplicationSaveStateSaver` + `Token` 提供跨窗口/跨进程运行的保存状态存取；`EventBus` / `IMailBox` 提供按生命周期自动回收的事件总线；`MySavedStateViewModelFactory`、`CreationExtras` 扩展补齐了 JVM 端 ViewModel 的创建能力。

> 上层模块：Compose 侧的窗口/Activity 框架见 [`framework/compose`](../compose/README.md)，Swing 侧的 JFrame/JDialog 生命周期框架见 [`framework/swing`](../swing/README.md)，平台能力（路径、进程、窗口操作等）见 [`toolkit`](../../toolkit/README.md)。

---

## 目录

- [模块定位与依赖关系](#模块定位与依赖关系)
- [设计原理](#设计原理)
- [核心概念与使用](#核心概念与使用)
  - [1. 服务容器：InstanceContext 与 ServiceBooter](#1-服务容器instancecontext-与-servicebooter)
  - [2. 意图分发：OperateIntent 与 IntentProcessor](#2-意图分发operateintent-与-intentprocessor)
  - [3. 状态存储：ApplicationSaveStateSaver 与 Token](#3-状态存储applicationsavestatesaver-与-token)
  - [4. 事件总线：EventBus 与 IMailBox](#4-事件总线eventbus-与-imailbox)
  - [5. ViewModel：工厂、CreationExtras 与 Java 兼容](#5-viewmodel工厂creationextras-与-java-兼容)
  - [6. 通用工具类](#6-通用工具类)
- [注意细节](#注意细节)
- [常见问题](#常见问题)

---

## 模块定位与依赖关系

```
            ┌───────────────────────────┐
            │      framework:compose    │  Activity / Window / Dialog / Fragment
            └────────────┬──────────────┘
                         │ implementation
            ┌────────────▼──────────────┐
            │      framework:common     │  ★ 本模块
            │  ServiceBooter / Intent   │
            │  SaveState / EventBus /VM │
            └────────────┬──────────────┘
                         │ implementation
            ┌────────────▼──────────────┐
            │        toolkit:spi        │  Koin 实例上下文、路径、进程、平台
            └────────────┬──────────────┘
                         │ ServiceLoader / @AutoService
      ┌──────────────────┼──────────────────┐
      │                  │                  │
 toolkit:win       toolkit:linux      toolkit:mac
```

- `framework-common` 只声明 `api` 级别的 JAR（`libs/*.jar`）与少量 `implementation` 依赖；
- 它的 Koin 容器使用**独立的 `InstanceContext`**，不是 Koin 的全局 `GlobalContext`，因此不会与宿主应用自己的 `startKoin {}` 冲突；
- 所有框架内部服务都注册在这个容器中，通过 `getService<T>()` / `getServiceInstance<T>()` 获取。

### 包结构

| 包 | 内容 |
| --- | --- |
| `androidx.compose.desktop.runtime.core` | `ServiceBooter`、`getServiceInstance` |
| `androidx.compose.desktop.runtime.core.intent` | `OperateIntent`、`LaunchMode`、`ComponentResult`、`ComponentResultCallback`、`IntentProcessor`、`IOperateIntentProcessor` |
| `androidx.compose.desktop.runtime.savestate` | `ApplicationSaveStateSaver`、`Token`、`IToken`、`Tokens` |
| `androidx.compose.desktop.runtime.di` | `CommonServiceInstanceProvider`（`@AutoService(ModuleProvider::class)`） |
| `androidx.compose.desktop.runtime.utils` | `WeakReferenceDelegate`、`SwingExtension`、`SwingErrorDialog`、`systemVer` |
| `androidx.compose.desktop.runtime.utils.eventbus` | `EventBus`、`IEventBus`、`IMailBox`、`FlowMailBox`、`BusHolder` |
| `androidx.compose.desktop.runtime.viewmodel` | `JvmViewModelProviders`、`ViewModelProviders`、`MySavedStateViewModelFactory`、`ViewModelProvider.create`、`mutableCreationExtrasOf` |

> 注意：包名沿用了 `androidx.compose.desktop.runtime.*` 前缀，是为了让上层 Compose 模块的 import 看起来与 Android 原生一致（如 `Lifecycle`、`ComponentActivity` 的使用体验），并非真的属于 androidx 官方产物。

---


## 设计原理

### 为什么用独立的 Koin 实例

Koin 默认的 `GlobalContext` 是**进程级单例**，一旦宿主应用自己调用 `startKoin {}`，框架再去 `startKoin` 就会抛 `KoinApplicationAlreadyStartedException`。而框架需要保证：

1. 宿主应用可以自由使用自己的 Koin；
2. 框架服务不会被宿主同名定义覆盖；
3. 框架能在 `Application.onDestroy` 时干净地 `stopKoin()`。

因此 `toolkit:spi` 中的 `InstanceContext` 直接实现 `KoinContext` 接口，自己持有 `_koin` / `_koinApplication`：

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

    private fun findModules(): MutableList<Module> {
        // 通过 ServiceLoader 发现所有 @AutoService(ModuleProvider::class) 的实现
        val providers = ServiceLoader.load(ModuleProvider::class.java)
        return providers.mapTo(mutableListOf()) { it.provide() }
    }
}
```

同时提供了 `InstanceKoinComponent`：

```kotlin
interface InstanceKoinComponent : KoinComponent {
    override fun getKoin(): Koin = InstanceContext.get()
}
```

框架内所有需要注入的类（`ActivityManager`、`WindowManager`、`JFrameManager`、各类 `Fragment`/`ComponentDialog` …）都实现了它，所以 `by inject<T>(named(...))` 默认从 `InstanceContext` 取，而不是全局 Koin。

### 模块自动装配：ModuleProvider + AutoService

`toolkit:spi` 定义了：

```kotlin
interface ModuleProvider {
    fun provide(): Module
}
```

各模块（框架自身、三个平台模块）只写：

```kotlin
@AutoService(ModuleProvider::class)
class CommonServiceInstanceProvider : ModuleProvider {
    override fun provide() = module {
        singleOf(::IntentProcessor)
        singleOf(::ApplicationSaveStateSaver)
    }
}
```

`@AutoService` 由 KSP 在编译期生成 `META-INF/services/...` 文件，运行期 `ServiceLoader` 就能发现它。好处是**新增平台或新增服务时不需要改动任何中心化注册表**，也不会产生类加载顺序问题。

### 意图分发的设计取舍

Android 的 `Intent` 是「数据 + 系统解析」的混合体；桌面端没有系统级组件解析器，因此这里拆成两半：

- `OperateIntent`：**纯标记接口**，代表「一件事」；
- `IOperateIntentProcessor<T>`：能处理某类 `OperateIntent` 的服务。

`IntentProcessor` 内部维护 `Map<Class<out OperateIntent>, (OperateIntent) -> Boolean>`，`process(intent)` 时按 `intent::class.java` **精确匹配**（不做父类回退），命中则返回处理器结果，未命中返回 `false`。

这样 Compose 侧的 `LaunchActivityIntent` 与 Swing 侧的 `LaunchJFrameIntent` 可以互不感知，各自把自己的 Launcher 注册进来即可。上层 `Context.startActivity()` / `JFrameManager.openJFrame()` 最终都会汇入这条链路。

#### 最简示例

下面这段代码不启动任何 UI，只演示「启动容器 → 注册意图处理器 → 投递意图 → 存取保存状态」这条主干。

```kotlin
import androidx.compose.desktop.runtime.core.ServiceBooter
import androidx.compose.desktop.runtime.core.intent.IOperateIntentProcessor
import androidx.compose.desktop.runtime.core.intent.IntentProcessor
import androidx.compose.desktop.runtime.core.intent.OperateIntent
import androidx.compose.desktop.runtime.core.intent.registerProcessor
import androidx.compose.desktop.runtime.savestate.ApplicationSaveStateSaver
import androidx.compose.desktop.runtime.savestate.Token
import org.slf4j.LoggerFactory

/** 1. 自定义一种意图 */
class SayHelloIntent(val who: String) : OperateIntent

fun main() {
    val logger = LoggerFactory.getLogger("demo")

    // 2. 启动框架的 Koin 容器（会通过 ServiceLoader 装载各模块提供的 Module）
    ServiceBooter.bootstrap(logger)

    // 3. 取出公共层内置的服务
    val intentProcessor = ServiceBooter.getService(IntentProcessor::class)
    val stateSaver = ServiceBooter.getService(ApplicationSaveStateSaver::class)

    // 4. 为自定义意图注册处理器
    intentProcessor.registerProcessor(object : IOperateIntentProcessor<SayHelloIntent> {
        override fun process(intent: SayHelloIntent): Boolean {
            println("Hello, ${intent.who}")
            return true
        }
    })

    // 5. 投递意图（也可以直接调用处理器）
    val handled = intentProcessor.process(SayHelloIntent("desktop"))

    // 6. 用 Token 存取一份 SavedState
    val token = Token("demo-page")
    stateSaver.obtain(token).putString("name", "knightwood")

    // 7. 导出全部状态，方便写盘
    println("handled=$handled, exported=${stateSaver.export()}")
}
```

要点：

- `ServiceBooter.bootstrap(logger)` 只应调用一次；重复启动会捕获 `KoinApplicationAlreadyStartedException` 并打一条 warn，不会崩溃。
- `getService(Class<T>)` 与 `getService(KClass<T>)` 两个重载都有，Java 侧用 `service.getService(ApplicationSaveStateSaver.class)`。
- 如果没有引入任何平台模块（`toolkit-win/linux/mac`），容器仍能启动，只是取不到 `AppBasePathProvider` 等平台实现。

---


---

## 核心概念与使用

### 1. 服务容器：InstanceContext 与 ServiceBooter

#### ServiceBooter

```kotlin
object ServiceBooter {
    fun bootstrap(logger: Logger)
    fun getKoin(): Koin
    fun <T : Any> getService(cls: KClass<T>): T
    fun <T : Any> getService(cls: Class<T>): T
}

inline fun <reified T : Any> getServiceInstance(): T
```

`bootstrap` 做的事情：

1. 调用 `InstanceContext.startUp { ... }`，把 Koin 自己的日志桥接到传入的 SLF4J `Logger`；
2. `startUp` → `startKoin` → `findModules()`，把所有 `ModuleProvider` 提供的 Module 装载进来；
3. 捕获 `KoinApplicationAlreadyStartedException`，只打 warn。

> `bootstrap` 传入的 logger 只用于 Koin 内部日志。框架自身日志统一通过 `logFor("xxx")` 获取。

#### 获取服务

```kotlin
// 任意位置（Kotlin）
val windowManager = getServiceInstance<WindowManager>()
val saver = getServiceInstance<ApplicationSaveStateSaver>()

// Kotlin，非 reified 场景
ServiceBooter.getService(IntentProcessor::class)

// Java
IntentProcessor processor = ServiceBooter.INSTANCE.getService(IntentProcessor.class);
```

在实现了 `Context` 的组件里（`Activity`、`Fragment`、`ComponentDialog`）还有一个更短的写法：

```kotlin
val processor = getService(IntentProcessor::class)   // Context 的成员方法
```

#### 自定义服务

很多场景下你希望把自己的对象也放进这个容器，供框架内任意组件获取。

```kotlin
val appModule = module {
    single { MyRepository(get()) }
    single<MyLogger>(named("file")) { FileLogger() }
}

// 方式 A：在 Application.onCreate 里加载（推荐，容器此时已启动）
getKoin().loadModules(listOf(appModule))

// 方式 B：作为 ModuleProvider 注册（跨进程自动装配）
@AutoService(ModuleProvider::class)
class MyModuleProvider : ModuleProvider {
    override fun provide() = appModule
}
```

Compose 侧 `composeApp` 示例里用的就是方式 A 的变体 —— 但那是对**宿主自己的** Koin 调用 `startKoin { modules(AppDi.modules) }`。两者互不影响，请注意不要混用 `getKoin()`：

| 写法 | 取哪个容器 |
| --- | --- |
| `InstanceKoinComponent.getKoin()` / `InstanceContext.get()` | 框架容器 |
| `org.koin.core.context.GlobalContext.get()` / `KoinComponent` 默认实现 | 宿主全局容器 |

### 2. 意图分发：OperateIntent 与 IntentProcessor

#### 类型定义

```kotlin
interface OperateIntent

enum class LaunchMode {
    SINGLE_INSTANCE,   // 同类型窗口只保留一个实例
    STANDARD;          // 允许并存多个实例

    operator fun plus(data: Any?): Pair<LaunchMode, Any?>
}

data class ComponentResult(val resultCode: Int, val data: Bundle?)

fun interface ComponentResultCallback {
    fun invoke(resultCode: Int, data: Bundle?)
}
```

`LaunchMode.plus` 只是语法糖，方便写成 `LaunchMode.STANDARD + payload`。

#### 注册与投递

```kotlin
// 注册：同类型后注册者覆盖先注册者
intentProcessor.registerProcessor(object : IOperateIntentProcessor<SayHelloIntent> {
    override fun process(intent: SayHelloIntent): Boolean = true
})

// reified 版本
intentProcessor.registerProcessor<SayHelloIntent> { intent -> true }

// 投递：未找到处理器返回 false（不会抛异常）
val ok: Boolean = intentProcessor.process(SayHelloIntent("a"))
```

#### 与上层框架的关系

| 模块 | 意图类型 | 处理器 | 最终效果 |
| --- | --- | --- | --- |
| `framework:compose` | `LaunchActivityIntent`（别名 `Intent`） | `ActivityLauncher` | 反射实例化 Activity 并在 Compose 主循环中显示窗口 |
| `framework:swing` | `LaunchJFrameIntent` | `JFrameLauncher` | 在 EDT 上实例化 JFrame 并 `isVisible = true` |

两个 Launcher 都在自己的 `init {}` 中完成注册，而 Launcher 又是由 Koin 的 `single` 创建，所以**第一次取到 `ActivityManager` / `JFrameManager` 时注册才生效**。这也是 Swing 侧 `Singularity.boot()` 里要提前 `get<JFrameManager>()` 的原因。

### 3. 状态存储：ApplicationSaveStateSaver 与 Token

#### 为什么需要它

桌面端没有 Android 的 `onSaveInstanceState` + 系统进程恢复机制。框架的方案是：

1. 每个需要保存状态的组件持有一个 `Token`；
2. `ApplicationSaveStateSaver` 用 `Map<Token, SavedState>` 保存所有组件的状态；
3. 应用退出前 `export()` 把 Map 交给业务方写盘（JSON / 自定义序列化 / 数据库都可以）；
4. 下次启动时在 `Application.onCreate` 里 `import(...)`，之后启动的组件就能拿到上次的状态。

#### API

```kotlin
class ApplicationSaveStateSaver {
    fun obtain(token: Token): SavedState          // 取或创建
    fun obtain(token: IToken): SavedState         // Java 兼容
    fun remove(token: Token)
    fun remove(token: IToken)
    fun release()                                 // 清空全部
    fun export(): Map<Token, SavedState>
    fun import(data: Map<Token, SavedState>): ApplicationSaveStateSaver
}
```

#### Token：为什么是 value class + 接口

```kotlin
interface IToken { val value: String }

@JvmInline
value class Token(override val value: String) : IToken

object Tokens {
    @JvmStatic
    fun of(value: String): IToken = Token(value)
}
```

- Kotlin 侧直接用 `Token("main")`，零装箱开销；
- **Java 侧不能 `new Token(...)`**：`value class` 构造器会被内联/修饰，且出现在签名里会 name mangling。所以 `Tokens.of("main")` 返回 `IToken` 接口类型，Java 只依赖接口即可。
- `ApplicationSaveStateSaver.obtain(IToken)` 与 `remove(IToken)` 就是为 Java 准备的桥接重载。

```kotlin
// Kotlin
stateSaver.obtain(Token("main")).putString("tab", "settings")

// Java
SavedState state = saver.obtain(Tokens.of("main"));
state.putString("tab", "settings");
```

#### 生命周期时序

```
Application.onCreate
  ├─ import(上次导出的 Map)        ← 必须早于第一个 Activity 启动
  └─ ...
Activity.create
  └─ intent.token 非空 → savedState = saver.obtain(token)
Activity.onDestroy
  └─ onSaveInstanceState(savedState) → 各组件写入自己的数据
Application.onDestroy
  └─ saver.export() → 业务方写盘
```

> `import` 的调用时机**必须早于第一个 Activity / JFrame / Fragment 启动**，因为 `obtain()` 只有在 key 不存在时才创建新实例；之后再 import 会覆盖掉已经建立的引用关系，导致组件写进旧 `SavedState` 的数据丢失。

### 4. 事件总线：EventBus 与 IMailBox

#### 设计要点

- `EventBus` **绑定 `LifecycleOwner`**：宿主 `ON_DESTROY` 时自动 `destroy()`，取消内部协程作用域并把自己从 `BusHolder` 移除；
- 一个 `EventBus` 内有多个**信箱（`IMailBox`）**，按 `String` key 区分；
- 每个信箱可独立配置是否「粘性」（`replay = 1`）；
- 同时提供**两套 API**：`submit/observe` 走内置协程作用域（非挂起），`post/collect` 需要自己提供协程（挂起）。

#### 获取 EventBus

```kotlin
// 全局总线：生命周期跟随 BusHolder（应用级）
val global = EventBus.instance                  // = EventBus.obtain(false, BusHolder)
val stickyGlobal = EventBus.obtain(sticky = true, name = "app")

// 跟随某个组件生命周期
val bus = EventBus.obtain(lifecycleOwner = this, sticky = false, name = "detail-page")

// 查询（不存在或已销毁返回 null）
val maybe: EventBus? = EventBus.get("detail-page")

// 手动销毁
EventBus.destroy("detail-page")
EventBus.destroyAll()
```

相同 `name` 会返回同一个实例；`sticky` 只在**首次创建**时生效，后续 `obtain` 传入不同的 `sticky` 不会改变已有实例。

#### 收发事件

```kotlin
val box = bus["bottom_navigation"]        // 不存在则用 MailBoxFactory 创建

// 发送任意类型
box.submit(1)
box.submit("text")

// 接收（不做类型过滤）
box.observe { value -> println(value) }

// 只接收 Int（reified 扩展，内部做 is T 过滤）
box.observe<Int> { value -> println(value) }

// 挂起版本
suspend fun work() {
    box.post(42)
    box.collect<Int> { value -> println(value) }
}
```

#### 邮箱的缓冲策略

```kotlin
data class Ini(
    val replay: Int = 0,
    val extraBufferCapacity: Int = 0,
    val onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
) : Serializable {
    constructor(sticky: Boolean) : this(if (sticky) 1 else 0)
}
```

- `replay = 0`：无粘性，订阅者只能收到订阅之后的事件；
- `replay = 1`：粘性，新订阅者会立刻收到最后一个事件（常用于「状态」而非「事件」）；
- 总缓冲 = `replay + extraBufferCapacity`；
- 溢出策略：`SUSPEND`（默认，背压）/ `DROP_OLDEST` / `DROP_LATEST`。

#### 自定义信箱实现

```kotlin
bus.setMailBoxFactory { sticky, scope ->
    FlowMailBox(scope, FlowMailBox.Ini(sticky))
}
```

`MailBoxFactory` 是 `fun interface`，默认实现就是 `FlowMailBox`。理论上可以换成基于 `LiveData`、`Channel` 的实现。

### 5. ViewModel：工厂、CreationExtras 与 Java 兼容

#### 问题背景

`androidx.lifecycle` 的 `ViewModelProvider` 在 JVM 上没有 `SavedStateViewModelFactory`（那是 Android 专属），默认工厂也只支持无参构造。本模块补齐了这些：

| 组件 | 作用 |
| --- | --- |
| `JvmViewModelProviders.createViewModel(modelClass)` | 反射调用无参构造，失败抛 `RuntimeException("Cannot create an instance of ...")` |
| `ViewModelProviders.getDefaultFactory(owner)` | `owner is HasDefaultViewModelProviderFactory` 则用它的工厂，否则 `DefaultViewModelProviderFactory` |
| `ViewModelProviders.getDefaultCreationExtras(owner)` | 同上，否则 `CreationExtras.Empty` |
| `MySavedStateViewModelFactory` | 优先找 `(SavedStateHandle)` 单参构造；否则退回无参构造 |
| `ViewModelProvider.Companion.create(owner, factory, creationExtras)` | 免去手动拼 `owner.viewModelStore` |
| `ViewModelStoreOwner.mutableCreationExtrasOf {}` | 基于 owner 默认 extras 派生新的可变 extras |
| `CreationExtras.toMutable {}` | 基于已有 extras 派生 |

#### `MySavedStateViewModelFactory` 的匹配规则

```kotlin
private val VIEWMODEL_SIGNATURE = listOf<Class<*>>(SavedStateHandle::class.java)

internal fun <T : ViewModel> createVM(modelClass: Class<T>, extras: CreationExtras): T {
    val constructor = findMatchingConstructor(modelClass, VIEWMODEL_SIGNATURE)
        ?: return JvmViewModelProviders.createViewModel(modelClass)   // 无参构造
    val savedStateHandle = extras.createSavedStateHandle()
    return constructor.newInstance(savedStateHandle)
}
```

- 构造参数列表**必须完全等于** `[SavedStateHandle]`；
- 如果参数个数相同、类型集合相同但顺序不同，抛 `UnsupportedOperationException("... must have parameters in the proper order")`；
- 需要「`SavedStateHandle` + 自定义参数」时，自己写 `ViewModelProvider.Factory`（见 [`framework/compose`](../compose/README.md#viewmodel) 的 `TestViewModel1` 示例）。

#### CreationExtras 的典型用法

```kotlin
// 1) 从 owner 默认 extras 派生，只补自己关心的 key
val extras = mutableCreationExtrasOf {
    this[MyViewModel.KEY] = intent?.getData<Int>("random") ?: 11
}

// 2) 完全手动构造（需要自己塞生命周期相关 key）
val manual = MutableCreationExtras().apply {
    this[MyViewModel.KEY] = 1
    this[SAVED_STATE_REGISTRY_OWNER_KEY] = this@MyActivity
    this[VIEW_MODEL_STORE_OWNER_KEY] = this@MyActivity
}

// 3) 从已有 extras 修改
val modified = defaultExtras.toMutable { this[KEY] = 2 }
```

> 如果没有把 `SAVED_STATE_REGISTRY_OWNER_KEY` / `VIEW_MODEL_STORE_OWNER_KEY` 放进去，`extras.createSavedStateHandle()` 会抛异常。使用 `mutableCreationExtrasOf {}` 时会自动继承 owner 的两个 key，通常不需要手动塞。

#### Java 兼容

Java 侧请使用 `framework:swing` 提供的两个辅助类（`framework-common` 里没有 Java 专用封装）：

```java
JavaViewModelProvider.create(owner, BookEditorViewModel.Companion.getFactory())
        .get(BookEditorViewModel.class);

CreationExtras extras = JavaCreationExtras.mutableCreationExtrasOf(owner, e -> e.set(KEY, 1));
```

### 6. 通用工具类

#### `WeakReferenceDelegate` / `WeakReferenceReadableDelegate`

```kotlin
class SomeHolder {
    var intent by WeakReferenceDelegate<MyIntent>()   // 只能赋值一次，之后赋值抛 IllegalStateException("Already initialized")
    var data by WeakReferenceReadableDelegate<MyData>(initial)  // 可以清空（赋 null），但不能替换成别的非 null 值
}
```

框架用它持有 `Intent`：启动方（Activity A）持有 intent 强引用，被启动方（Activity B）只弱引用它，避免 A 已销毁时 B 还拖住 A 的对象图。

#### `SwingExtension`

```kotlin
SwingExtension.writeClipboard("text")
val text = SwingExtension.readClipboard()
```

#### `SwingErrorDialog`

```kotlin
SwingErrorDialog.showErrorDialog(throwable)   // JOptionPane ERROR_MESSAGE
```

用于在不引入 Compose 的 Swing 场景下弹一个错误框。

#### `systemVer`

```kotlin
val systemVer: String   // Windows: "10"/"11" 之类；macOS: java.version；Linux: ""
```

通过 `toolkit:spi` 的 `currentOS` 与 `SystemProperty` 组合得到，`lazy` 求值。

---

## 注意细节

1. **`Token` 只能设置一次。**
   `LaunchActivityIntent.token` 与 `LaunchJFrameIntent.token` 的 setter 都有 `if (field != null) throw IllegalArgumentException("Token already set")`。这意味着不能先设 token、再改 token；需要复用 intent 时请新建一个。

2. **`Token` 是存取状态的「钥匙」，不是 ID 生成器。**
   两个不同窗口用同一个 `Token("x")` 会共享同一份 `SavedState`，互相覆盖。经验做法是按「组件类型 + 业务主键」拼：`Token("editor-${bookId}")`。

3. **Java 不能 `new Token(...)`。**
   必须 `Tokens.of("x")`，返回类型是 `IToken`。

4. **`import()` 的时机。**
   必须在第一个窗口/Activity 启动之前。放在 `Application.onCreate` 里最稳。

5. **`export()` 返回的是内部 Map 的引用（不是拷贝）。**
   `fun export(): Map<Token, SavedState> = allSavedState`，所以不要缓存这个 Map 之后继续让框架往里写，序列化请立刻做。

6. **`EventBus` 的 `sticky` 只在首次创建时生效。**
   同一个 `name` 二次 `obtain` 时传不同 `sticky` 无效。

7. **`IMailBox.observe` 会立即启动一个协程。**
   它挂在 `EventBus` 的 `scope`（`Dispatchers.Default` + `SupervisorJob`）上，回收依赖 `IEventBus.destroy()`。如果宿主生命周期一直不到 `DESTROYED`，收集器就会一直活着。

8. **`MySavedStateViewModelFactory` 只认「恰好一个 `SavedStateHandle` 参数」。**
   多参数构造必须自备 Factory，否则会退化到无参构造，然后因为找不到无参构造而抛 `RuntimeException`。

9. **`ServiceBooter` 的日志桥接是单向的。**
   只把 Koin 的 `Level.DEBUG/INFO/ERROR/WARNING/NONE` 映射到 SLF4J，不改变宿主 logger 的级别。

10. **`framework-common` 并非「纯 Kotlin 无 Swing」。**
    `utils` 包里用到了 `java.awt.Toolkit` 与 `javax.swing.JOptionPane`（剪贴板、错误弹窗）。因此它只能跑在带 AWT/Swing 的桌面 JVM 上，不能用于 headless 服务端（除非不使用这些类，且启动参数里 `-Djava.awt.headless=true` 也不会有问题 —— 但 `SwingErrorDialog` 会抛 `HeadlessException`）。

---

## 常见问题

### Q1. 启动时报 `KoinApplicationAlreadyStartedException`？

不是异常，`ServiceBooter.bootstrap` 已捕获并降级为 warn。出现它说明容器已经启动过（例如 `Singularity.boot` 与手动 `bootstrap` 重复调用）。如果你确实需要重启容器，先 `InstanceContext.stopKoin()`。

### Q2. `getServiceInstance<T>()` 报 `NoDefinitionFoundException`？

- 该服务没有被任何 `ModuleProvider` 注册，或
- 你取的是平台专属服务（如 `AppBasePathProvider`），但当前进程没有引入对应平台模块，或
- 平台服务用了 `named(SystemOs.X)` 限定，必须用 `getPlatformInstance<T>()` 而不是 `getServiceInstance<T>()`。

### Q3. 为什么 `ApplicationSaveStateSaver.import()` 之后状态还是空的？

三个最常见原因：

1. `import()` 调用晚于第一个 Activity/JFrame 启动 —— `obtain()` 已经创建了新的 `SavedState`；
2. `Token` 不一致（大小写、拼接方式、Java 侧用了 `Tokens.of` 但 Kotlin 侧用 `Token`，值不同）；
3. 该组件根本没有 `token`（`intent.token == null` 时框架不启用状态保存）。

### Q4. `EventBus` 的事件收不到？

- 检查 `sticky`：`replay = 0` 时，先 `submit` 后 `observe` 是收不到的；
- 检查 `name`：不同 `name` 就是不同总线；
- 检查类型过滤：`observe<Int>` 会丢弃非 `Int` 的事件，而 `submit(1L)` 是 `Long`；
- 检查宿主是否已 `DESTROYED`：总线销毁后 `scope` 已被 `cancel`，`observe` 启动的协程不会执行。

### Q5. ViewModel 里 `savedStateHandle` 是空的？

- `SavedStateHandle` 的数据来自宿主的 `SavedStateRegistry`，需要宿主实现 `SavedStateRegistryOwner` 并在 `onCreate` 里 `performRestore(savedState)`；
- 宿主必须启用 token（否则 `savedState == null`，`performRestore(null)` 不会恢复任何东西）；
- 关闭窗口必须走 `finish()`（触发 `onDestroy` → `onSaveInstanceState`），直接 `dispose()` 或 `HIDE_ON_CLOSE` 不会保存。
