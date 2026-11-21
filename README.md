<p align="center"><h1 align="center">compose &amp; swing</h1></p>
<p align="center"><h2 align="center">桌面窗口框架</h2></p>

<p align="center">
	<em><code>❯ 在桌面端写出 Android 一样的窗口体验</code></em>
</p>
<p align="center">
	<img src="https://img.shields.io/github/license/Knightwood/desktop-runtime?style=default&logo=opensourceinitiative&logoColor=white&color=0080ff" alt="license">
	<img src="https://img.shields.io/github/last-commit/Knightwood/desktop-runtime?style=default&logo=git&logoColor=white&color=0080ff" alt="last-commit">
	<img src="https://img.shields.io/github/languages/top/Knightwood/desktop-runtime?style=default&color=0080ff" alt="repo-top-language">
	<img src="https://img.shields.io/github/languages/count/Knightwood/desktop-runtime?style=default&color=0080ff" alt="repo-language-count">
</p>
<br>

## Overview

一套给 **Compose Desktop** 与 **Swing** 用的桌面窗口框架，为compose桌面端带来类似android的开发体验：

- **Compose 端**：`Application`、`Activity`、`ComponentDialog`、`Fragment`、`Intent`、`Context`，
  每个组件都带 `Lifecycle` / `ViewModel` / `SavedState`；
- **Swing 端**：`ComponentJFrame`、`ComponentJDialog`、`ComponentJPanel`，
  同样具备生命周期、ViewModel、SavedState，甚至可以继续用 IDEA 的 GUI Designer；
- **两端共享**：同一套 `IntentProcessor`、`ApplicationSaveStateSaver`、`Token`、`EventBus`，
  可以互相打开对方的窗口，也可以把整个应用的状态导出、下次启动再恢复；
- **底层 `toolkit`**：跨平台的路径、进程单实例锁、系统识别、文件操作等能力，通过 SPI 自动装配。

> **窗口不再是一个 `JFrame`/`Window`，而是一个有生命周期的组件。**

---

## 文档导航

本仓库的详细文档按模块拆分，请按需阅读：

| 模块 | 文档 | 适合谁 |
| --- | --- | --- |
| `framework:common` | **[framework/common/README.md](framework/common/README.md)** | 想了解 `ServiceBooter`、`IntentProcessor`、`Token`、`ApplicationSaveStateSaver`、`EventBus` 的机制 |
| `framework:compose` | **[framework/compose/README.md](framework/compose/README.md)** | 用 Compose Desktop 写应用，需要 Activity / Dialog / Fragment / 窗口管理 |
| `framework:swing` | **[framework/swing/README.md](framework/swing/README.md)** | 用 Swing（含 Java、GUI Designer）写应用，需要生命周期 / ViewModel / Intent 跳转 |
| `toolkit` 总览 | **[toolkit/README.md](toolkit/README.md)** | 想用路径、单实例锁、系统识别等平台能力 |
| `toolkit:spi` | **[toolkit/README-spi.md](toolkit/README-spi.md)** | 想了解 SPI 装配机制，或自己扩展一个平台实现 |
| `toolkit:win` | **[toolkit/README-win.md](toolkit/README-win.md)** | Windows 打包布局与路径计算 |
| `toolkit:linux` | **[toolkit/README-linux.md](toolkit/README-linux.md)** | Linux / XDG 规范相关 |
| `toolkit:mac` | **[toolkit/README-mac.md](toolkit/README-mac.md)** | macOS `.app` 布局与签名注意事项 |

示例代码位置：

- Compose：本仓库的 [`composeApp`](composeApp) 模块（可直接运行）
- Swing：`framework/swing/src/test/java`（`Main.java`、`forms/MainScreen.java`、`viewmodel/BookEditorViewModel.kt`）

---

## 快速开始

### 1. 添加依赖

```bash
git clone https://github.com/Knightwood/desktop-runtime.git
cd desktop-runtime
./gradlew publishToMavenLocal
```

```kotlin
// build.gradle.kts
val version = "1.3.0"
val group = "com.github.knightwood.desktop"

repositories {
    mavenLocal()
    google()
    mavenCentral()
}

dependencies {
    // —— Compose 应用 ——
    implementation("$group:framework-common:$version")
    implementation("$group:framework-compose:$version")

    // —— 或 Swing 应用 ——
    // implementation("$group:framework-common:$version")
    // implementation("$group:framework-swing:$version")

    // —— 平台能力（三平台都加上也没问题，运行时会按 OS 选择）——
    implementation("$group:toolkit-spi:$version")
    runtimeOnly("$group:toolkit-win:$version")
    runtimeOnly("$group:toolkit-linux:$version")
    runtimeOnly("$group:toolkit-mac:$version")

    // —— 托盘（可选）——
    implementation("$group:compose-tray:$version")
}
```

> **Maven 用户注意**：`framework-common` 与 `framework-compose` 是 KMP 工程，
> 在纯 Maven 中要使用带 `-jvm` 后缀的坐标（如 `framework-compose-jvm`）；
> Gradle 会自动通过模块元数据解析到对应变体，直接写 `framework-compose` 即可。
> `framework-swing`、`toolkit-*`、`compose-tray` 是普通 JVM 工程，没有后缀。

1.3.0 之前的老坐标（仅作历史参考）：

```kotlin
implementation("com.github.knightwood:desktop-runtime:1.0.0") { isChanging = true }
implementation("com.github.knightwood:jvm-system-spi:1.0.0") { isChanging = true }
implementation("com.github.knightwood:jvm-system-win:1.0.0")
```

### 2. Compose：三步写一个应用

```kotlin
// ① 可选：Application 负责数据与逻辑初始化
class MainApplication : Application()

// ② Activity = 一个窗口
class MainActivity : ComponentActivity() {

    private val vm: TestViewModel by viewModels()

    override fun onCreate(savedInstanceState: SavedState?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state = rememberWindowState()
            Window(onCloseRequest = { finish() }, state = state) {
                // 把 Activity 的生命周期链接到真实的 ComposeWindow
                LinkWindow {
                    Button(onClick = {
                        // 跳转到另一个窗口，并拿回结果
                        val intent = LaunchActivityIntent(this@MainActivity, DetailActivity::class.java)
                            .apply { token = Token("detail-1") }
                        lifecycleScope.launch {
                            startActivityForResult(intent) { code, data ->
                                println("result=$code data=$data")
                            }
                        }
                    }) { Text("打开详情") }
                }
            }
        }
    }
}

// ③ 启动
fun main() {
    startApplication<MainActivity, MainApplication>()
    // 没有初始化逻辑时也可以用 Application 基类：
    // startApplication<MainActivity, Application>()
}
```

要点：

- `setContent { }` 里的根视图**必须自己调用 `Window`**，否则不会显示窗口；
- `Window` 的内容要包在 `LinkWindow { }` 里，Activity 的生命周期才会与窗口同步；
- 没有 `token` 的 Activity **不会**保存/恢复状态。

完整示例见 [framework/compose/README.md](framework/compose/README.md) 与 `composeApp` 模块。

### 3. Swing：三步写一个应用

```java
// ① 主窗口
public class MainScreen extends ComponentJFrame {

    public MainScreen() {
        super("主窗口");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);   // 必须：否则 onDestroy 不触发
        setSize(800, 600);
        setLocationRelativeTo(null);
    }

    void openEditor() {
        LaunchJFrameIntent intent = new LaunchJFrameIntent(this, EditorScreen.class, LaunchMode.STANDARD);
        intent.setTokenForJava(Tokens.of("editor"));         // Java 中不能用 value class，用 Tokens.of
        JFrameManager.openJFrameForResult(intent, (resultCode, data) -> {
            // 回调在 Dispatchers.Default，更新 UI 请切回 EDT
            SwingUtilities.invokeLater(() -> System.out.println(data));
        });
    }
}

// ② 入口
public class Main {
    public static void main(String[] args) {
        FlatLightLaf.setup();
        System.setProperty("flatlaf.uiScale", "1.25");
        Singularity.INSTANCE.boot();                          // 启动框架
        SwingUtilities.invokeLater(() -> new MainScreen().setVisible(true));
    }
}
```

要点：

- **必须** `Singularity.INSTANCE.boot()`，否则 Intent 分发器没有注册；
- 目标窗口要有 public 无参构造（框架用反射创建）；
- 直接 `setVisible(true)` 的窗口拿不到 Intent 参数，也回传不了结果。

完整示例见 [framework/swing/README.md](framework/swing/README.md) 与 `framework/swing/src/test/java`。

---

## 设计概览

### 窗口管理：用一个可观察列表驱动重组

Compose 的 `application { }` 只能有一个。框架把每个「Activity 根视图」放进 `SnapshotStateList`，
在应用根视图中遍历调用：

```kotlin
typealias ComposeContent = @Composable () -> Unit
val windows = SnapshotStateList<ComposeContent>()

application {
    windows.forEach { it() }
}

// 打开窗口 = add，关闭窗口 = remove
windows.add(window1)
windows.remove(window1)
```

列表变化触发重组：加入则 `Window` 出现，移除则 `Window` 从树上剥离并走到 `ON_DESTROY`。
**框架内所有窗口共享同一个 `application {}`**，因此托盘、全局 `CompositionLocal`、
未捕获异常弹窗等都可以放在 `ApplicationRootContent` 里统一处理。

### 生命周期

Activity 本身不产生生命周期事件，而是同步底层 `ComposeWindow` 的状态：

| Swing listener 回调 | Lifecycle 事件 | 状态变化 |
| --- | --- | --- |
| `windowIconified`（最小化） | `ON_STOP` | STARTED → CREATED |
| `windowDeiconified`（还原） | `ON_START` | CREATED → STARTED |
| `windowLostFocus`（失焦 / 隐藏） | `ON_PAUSE` | RESUMED → STARTED |
| `windowGainedFocus`（获焦 / 恢复显示） | `ON_RESUME` | STARTED → RESUMED |
| `dispose`（移除 window） | `ON_DESTROY` | CREATED → DESTROYED |

Swing 侧同一套映射被封装成 `WindowLifecycleAdapter`：**即使你的窗口类不能继承框架基类**，
也可以把它挂到自己的 `JFrame` / `JDialog` 上，获得生命周期能力。
（映射逻辑参考自 `androidx.compose.ui.scene.ComposeContainer.updateLifecycleState`。）

### 组件间通信：Intent + Token + 信箱

- **`Intent` / `LaunchJFrameIntent`**：描述「从哪来、到哪去、带什么参数、用什么启动模式」；
- **`Token`**：组件身份，同时是**状态保存的主键**。没有 token 就不保存状态；
- **信箱（`getMailBox` / `setResult`）**：父子窗口不直接持有引用，靠 `SharedFlow` 传数据与结果；
- **`LaunchMode.SINGLE_INSTANCE`**：命中同类窗口时回调 `onReStart(intent)`，而不是新建。

### 状态保存：内存 + 可导出

```
各组件 onSaveInstanceState / SavedStateHandle
        ↓ performSave
SavedStateRegistry → SavedState（androidx.savedstate）
        ↓
ApplicationSaveStateSaver: Map<Token, SavedState>
        ↓ export() / import()
磁盘（由应用自己决定序列化格式）
```

```kotlin
class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 启动时把磁盘里的状态导回内存，之后打开的窗口才能恢复
        getService(ApplicationSaveStateSaver::class).import(mutableMapOf())
    }

    override fun onDestroy() {
        super.onDestroy()
        // 所有窗口关闭后导出（`composeApp` 里有落盘实现）
        getService(ApplicationSaveStateSaver::class).export()
    }
}
```

### 平台能力：SPI + 独立 Koin 容器

`toolkit:spi` 定义接口（`AppBasePathProvider`、`PlatformWindowHelper` 等）并提供一个**独立的 Koin 容器**
（`InstanceContext`，不占用 Koin 全局上下文）；`toolkit:win/linux/mac` 用 `@AutoService(ModuleProvider)`
注册 `named(SystemOs.X)` 的实现，运行时按 `currentOS` 取用。详见 [toolkit/README.md](toolkit/README.md)。

---

## 混用 Compose 与 Swing

两套框架可以同时存在：

- 它们共享同一个 `ApplicationSaveStateSaver` 与 `IntentProcessor`（Intent 的解析各自实现）；
- 因此可以在 Compose 的 Activity 里打开 `ComponentJFrame`，也可以反过来；
- **但不建议把两套框架的窗口体系深度混在一起**：状态保存与启动模式各自独立，
  混用容易出现「状态被另一边覆盖」的问题。跨框架请只通过 `Token` + `ApplicationSaveStateSaver` 交互。

---

## 仓库结构

```
desktop-activity/
├── framework/
│   ├── common/      # 共享基础设施：ServiceBooter、Intent、Token、SaveState、EventBus、ViewModel 工厂
│   ├── compose/     # Compose 窗口框架：Application / Activity / Dialog / Fragment / WindowManager
│   └── swing/       # Swing 窗口框架：ComponentJFrame / JDialog / JPanel / JFrameManager
├── toolkit/
│   ├── spi/         # 平台接口 + InstanceContext + 路径/进程/系统工具
│   ├── win/         # Windows 实现
│   ├── linux/       # Linux 实现
│   └── mac/         # macOS 实现
├── compose-tray/    # Compose 托盘支持
├── composeApp/      # Compose 示例应用（可运行）
├── build-logic/     # 发布配置（vanniktech maven publish）
└── gradle/          # 版本目录
```

要求：**JDK 17+**、Gradle 8.11.1（Wrapper 已提供）、Kotlin 2.2.10、Compose Multiplatform 1.11.1。

---

## 常见问题（速查）

| 现象 | 原因 / 处理 |
| --- | --- |
| Compose 窗口不显示 | `setContent` 里没有调用 `Window`，或没有把内容包进 `LinkWindow` |
| Activity 关掉后再打开状态没恢复 | 启动时没设置 `token` |
| Swing 窗口关闭后 `onDestroy` 不执行 | `JFrame` 默认 `HIDE_ON_CLOSE`，改成 `DISPOSE_ON_CLOSE` |
| `openJFrame` 没反应 | 忘记 `Singularity.boot()`，或目标类没有 public 无参构造 |
| 结果回调里改 UI 出异常 | 回调在 `Dispatchers.Default`，需 `SwingUtilities.invokeLater` |
| `NoDefinitionFoundException: AppBasePathProvider` | 没引入 `toolkit-win/linux/mac`，或没启动容器 |
| `Cannot set SavedState after CREATED` | `savedState` 只能在窗口创建前赋值 |
| IDEA GUI Designer 报 `contentPane cannot be set to null` | 把 Gradle 的 `Build and run using` 改成 `IntelliJ IDEA` |

更多问题见各模块 README 的 FAQ 章节。

---

## License

见 [LICENSE](LICENSE)。
