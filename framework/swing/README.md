# framework-swing

> 给传统 Swing 应用补上「Activity 式」的现代开发体验：
> 生命周期、ViewModel、SavedState、Intent 跳转、结果回传、状态保存与恢复。
>
> 不改变你的 UI 技术栈 —— 依然是 `JFrame` / `JDialog` / `JPanel`，
> 依然可以继续用 IDEA 的 GUI Designer（`.form` 文件）。

---

## 目录

- [1. 它能带来什么](#1-它能带来什么)
- [2. 模块结构与依赖关系](#2-模块结构与依赖关系)
- [6. 最简示例](#6-最简示例)
- [7. 设计原理](#7-设计原理)
- [8. 核心概念详解](#8-核心概念详解)
- [9. 注意细节](#9-注意细节)
- [11. 常见问题（FAQ）](#11-常见问题faq)

---

## 1. 它能带来什么

原生 Swing 写桌面应用，痛点非常集中：**窗口只给你「事件」，不给你「状态」**。

框架并没有另起一套 UI 体系，它只做一件事：**把 AWT 的窗口事件翻译成 `androidx.lifecycle` 的生命周期状态**，
然后在这条生命周期之上，复用 Android 世界已经被验证过的一整套基础设施（ViewModel、SavedStateRegistry、Lifecycle）。

| 能力 | 原生 Swing | 使用 framework-swing |
| --- | --- | --- |
| 生命周期 | 自己注册 `WindowListener`，在 `windowOpened` / `windowClosing` 里写初始化与清理逻辑 | `onCreate` / `onStart` / `onResume` / `onPause` / `onStop` / `onDestroy` 六个回调 |
| 状态订阅 | 手动加监听、手动移除，容易泄漏 | `lifecycle.addObserver { }`，随窗口销毁自动清理 |
| ViewModel | 自己 new，作用域与置空完全靠自觉 | `viewModels<T>()` / `JavaViewModelProvider`，`ON_DESTROY` 自动 `clear()` |
| 状态保存 | 自己序列化、自己找地方存 | `SavedStateHandle` / `onSaveInstanceState` + `ApplicationSaveStateSaver` |
| 窗口跳转 | 直接 `new XXXFrame().isVisible = true`，强耦合 | `LaunchJFrameIntent` + `JFrameManager.openJFrame(...)`，父子解耦 |
| 结果回传 | 传回调、传接口，双向强引用 | `setResult()` + `SharedFlow` / 回调 / `suspend` 三种消费方式 |
| 单实例窗口 | 自己维护窗口缓存表 | `LaunchMode.SINGLE_INSTANCE` + `onReStart(intent)` |
| 状态恢复 | 无 | 指定 `Token` 后，窗口关闭再打开能拿回上次的数据 |

一句话概括它的设计取向：

> **UI 仍然是 Swing，但「一个窗口」被当成一个带生命周期的组件来管理。**

---

## 2. 模块结构与依赖关系

```
framework:swing
├── core/intent
│   ├── WindowLifecycleAdapter.kt   // Window 事件 → Lifecycle.State 的核心翻译器
│   ├── LaunchJFrameIntent.kt       // 启动窗口的意图（参数 / 结果 / Token）
│   ├── Singularity.kt              // 框架启动入口
│   └── WindowLifecycleAdapter 的监听转发
├── jFrame
│   ├── LifecycleJFrame.kt          // 基础窗口：只有生命周期 + Intent + 状态保存
│   ├── ComponentJFrame.kt          // 完整窗口：再加 ViewModel + SavedStateRegistry
│   ├── JFrameManager.kt            // 窗口注册表 + 窗口栈 + 启动器
│   ├── JFrameResultCallback.kt     // ComponentResult 的类型别名
│   └── ...
├── jDialog/ComponentJDialog.kt     // 带生命周期的 JDialog
├── jPanel/ComponentJPanel.kt       // 「基于 JPanel 的 Fragment」
├── di/SwingServiceInstanceProvider.kt
└── viewmodel
    ├── JFrameViewModelLazy.kt      // Kotlin 的 viewModels<T>() 扩展
    ├── JavaViewModelProvider.kt    // Java 友好版 ViewModelProvider
    └── JavaCreationExtras.kt       // Java 友好的 CreationExtras 构造工具
```

依赖方向：

```
framework:swing
   ├── framework:common     (Token / ApplicationSaveStateSaver / IntentProcessor / OperateIntent ...)
   └── toolkit:spi          (InstanceContext / get() / ModuleProvider ...)
          │
          └── 各平台实现 toolkit:win | toolkit:linux | toolkit:mac（由 ServiceLoader 自动装配）
```

`framework:swing` 是**纯 `kotlin("jvm")` 工程**（不是 KMP），所以它发布出去的 Maven 坐标**没有 `-jvm` 后缀**。

---

## 6. 最简示例

下面两个文件就能跑起来。示例演示了：

1. 启动框架（`Singularity.boot()`）
2. 定义一个带生命周期的窗口（继承 `ComponentJFrame`）
3. 在里面直接使用 `ViewModel`
4. 用 `LaunchJFrameIntent` 打开第二个窗口，并拿到它回传的结果

### 6.1 入口：Main.java

```java
import androidx.jvm.swing.lifecycle.core.intent.Singularity;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;

public class Main {

    public static void main(String[] args) {
        // 1) 设置现代外观（可选，但强烈建议：FlatLaf 的高 DPI 支持比 Metal 好得多）
        FlatLightLaf.setup();
        // 2) 高分屏缩放（可选）
        System.setProperty("flatlaf.uiScale", "1.25");

        // 3) 启动框架：初始化 Koin、注册 Intent 处理器、准备状态保存器
        Singularity.INSTANCE.boot();

        // 4) 在 EDT 上显示主窗口
        SwingUtilities.invokeLater(() -> new MainScreen().setVisible(true));
    }
}
```

`Singularity.boot()` 做的事情只有两件，但都不能省：

```kotlin
object Singularity {
    fun boot() {
        ServiceBooter.bootstrap(logger)          // 启动 Koin 容器、扫描 ModuleProvider
        InstanceContext.get().run { get<JFrameManager>() }  // 提前实例化，注册 JFrameLauncher
    }
}
```

如果跳过它，`JFrameManager.openJFrame(...)` 会因为找不到 `IntentProcessor` 而失败。

### 6.2 主窗口：MainScreen.java

```java
import androidx.compose.desktop.runtime.savestate.Tokens;
import androidx.core.bundle.Bundle;
import androidx.jvm.swing.lifecycle.core.intent.LaunchJFrameIntent;
import androidx.jvm.swing.lifecycle.jFrame.ComponentJFrame;
import androidx.jvm.swing.lifecycle.jFrame.JFrameManager;
import androidx.jvm.swing.lifecycle.jFrame.JFrameResultCallback;
import androidx.compose.desktop.runtime.core.intent.LaunchMode;

import javax.swing.*;
import java.awt.*;

public class MainScreen extends ComponentJFrame {

    public MainScreen() {
        super("主窗口");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 600);
        setLocationRelativeTo(null);

        JButton openButton = new JButton("打开编辑窗口");
        openButton.addActionListener(e -> openEditor());

        setLayout(new BorderLayout());
        add(openButton, BorderLayout.NORTH);
    }

    private void openEditor() {
        // 1) 构造意图：从 this 出发，目标是 EditorScreen，标准启动模式
        LaunchJFrameIntent intent =
                new LaunchJFrameIntent(this, EditorScreen.class, LaunchMode.STANDARD);

        // 2) 指定 Token：让该窗口具备「状态保存与恢复」能力
        intent.setTokenForJava(Tokens.of("editor-screen"));

        // 3) 打开窗口，并注册结果回调
        JFrameManager.openJFrameForResult(intent, new JFrameResultCallback() {
            @Override
            public void invoke(int resultCode, Bundle data) {
                // 注意：回调运行在 Dispatchers.Default 线程，更新 UI 必须切回 EDT
                SwingUtilities.invokeLater(() -> {
                    if (resultCode == ComponentJFrame.SUCCESS) {
                        String text = data == null ? "" : data.getString("text");
                        JOptionPane.showMessageDialog(MainScreen.this, "收到：" + text);
                    } else {
                        JOptionPane.showMessageDialog(MainScreen.this, "已取消");
                    }
                });
            }
        });
    }

    @Override
    public void onCreate(androidx.savedstate.SavedState savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 生命周期起点：此时窗口尚未显示
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 生命周期终点：保存状态、释放资源都在这里
    }
}
```

### 6.3 子窗口：EditorScreen.java

```java
import androidx.core.bundle.Bundle;
import androidx.jvm.swing.lifecycle.jFrame.ComponentJFrame;

import javax.swing.*;

public class EditorScreen extends ComponentJFrame {

    private final JTextArea textArea = new JTextArea();

    public EditorScreen() {
        super("编辑窗口");
        // 关键！JFrame 默认是 HIDE_ON_CLOSE，不会触发 onDestroy
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(600, 400);
        setLocationRelativeTo(null);

        JButton ok = new JButton("确定");
        ok.addActionListener(e -> {
            // 回传结果给调用方
            Bundle data = new Bundle();
            data.putString("text", textArea.getText());
            setResult(ComponentJFrame.SUCCESS, data);
            finish();
        });

        JButton cancel = new JButton("取消");
        cancel.addActionListener(e -> {
            setResult(ComponentJFrame.FAILED, null);
            finish();
        });

        JPanel bottom = new JPanel();
        bottom.add(ok);
        bottom.add(cancel);

        setLayout(new java.awt.BorderLayout());
        add(new JScrollPane(textArea), java.awt.BorderLayout.CENTER);
        add(bottom, java.awt.BorderLayout.SOUTH);
    }
}
```

### 6.4 同样的代码，Kotlin 版本更短

```kotlin
// Main.kt
fun main() {
    FlatLightLaf.setup()
    System.setProperty("flatlaf.uiScale", "1.25")
    Singularity.boot()
    SwingUtilities.invokeLater { MainScreen().isVisible = true }
}

// MainScreen.kt
class MainScreen : ComponentJFrame("主窗口") {
    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        setSize(800, 600)
        setLocationRelativeTo(null)
        contentPane.add(JButton("打开编辑窗口").apply {
            addActionListener { openEditor() }
        })
    }

    private fun openEditor() {
        val intent = LaunchJFrameIntent(this, EditorScreen::class.java, LaunchMode.STANDARD)
            .apply { token = Tokens.of("editor-screen") }

        JFrameManager.openJFrameForResult(intent, JFrameResultCallback { code, data ->
            SwingUtilities.invokeLater {
                if (code == ComponentJFrame.SUCCESS) {
                    JOptionPane.showMessageDialog(this, "收到：${data?.getString("text") ?: ""}")
                }
            }
        })
    }
}

// EditorScreen.kt
class EditorScreen : ComponentJFrame("编辑窗口") {
    private val textArea = JTextArea()
    private val vm: EditorViewModel by viewModels()   // 直接拿到随窗口销毁的 ViewModel

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        setSize(600, 400)
        setLocationRelativeTo(null)
        contentPane.add(JScrollPane(textArea))
        contentPane.add(JButton("确定").apply {
            addActionListener {
                setResult(ComponentJFrame.SUCCESS, Bundle().apply { putString("text", textArea.text) })
                finish()
            }
        }, BorderLayout.SOUTH)
    }
}
```

### 6.5 运行

```bash
./gradlew run            # 应用插件方式
# 或在 IDE 中直接运行 Main.main()
```

### 6.6 再往前一步：给窗口加 ViewModel + 状态保存

```kotlin
class EditorViewModel(
    val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // 自动读写：窗口关闭后再次以同一 Token 打开，内容还在
    var text: String
        get() = savedStateHandle["text"] ?: ""
        set(value) { savedStateHandle["text"] = value }
}

class EditorScreen : ComponentJFrame("编辑窗口") {
    private val vm: EditorViewModel by viewModels()
    ...
}
```

此时 `onDestroy` 会把 `SavedStateRegistry`（也就是 `SavedStateHandle` 里的内容）
写回 `ApplicationSaveStateSaver`，键就是 `LaunchJFrameIntent.token`。

---

## 7. 设计原理

### 7.1 为什么 Swing 能直接复用 `androidx.lifecycle`

`androidx.lifecycle` / `androidx.savedstate` / `androidx.core:core-bundle` 都已经 KMP 化，
其 `jvm` 产物只依赖 Kotlin 标准库与协程，**不依赖 Android 框架**。

框架要做的只是提供三个「Owner」的实现：

| Android 概念 | Swing 中的落点 |
| --- | --- |
| `LifecycleOwner` | `LifecycleJFrame` / `ComponentJDialog` / `ComponentJPanel` |
| `ViewModelStoreOwner` | `ComponentJFrame` / `ComponentJDialog` / `ComponentJPanel` |
| `SavedStateRegistryOwner` | `ComponentJFrame` / `ComponentJDialog` / `ComponentJPanel` |
| `HasDefaultViewModelProviderFactory` | 同上，默认工厂是 `MySavedStateViewModelFactory` |

于是 `lifecycleScope`、`SavedStateHandle`、`by viewModels()` 这些写法几乎可以照抄 Android 的经验。

### 7.2 核心：把 Window 事件翻译成生命周期状态

`WindowLifecycleAdapter` 是整个模块的心脏（`core/intent/WindowLifecycleAdapter.kt`）。
它继承 `WindowAdapter`，同时实现 `WindowFocusListener`（`WindowAdapter` 已实现），
把「打开 / 最小化 / 获得焦点 / 关闭」等离散事件归纳成**四个布尔标志**：

```kotlin
var isOpened = false
var isMinimized = false
var isDisposed = false
var isFocused = false
```

然后每次事件到达，都重新计算一次当前应当处于的生命周期状态：

```kotlin
private fun updateLifecycle() {
    val state = when {
        isDisposed -> Lifecycle.State.DESTROYED
        !isOpened || isMinimized -> Lifecycle.State.CREATED
        isOpened && !isMinimized && isFocused -> Lifecycle.State.RESUMED
        else -> Lifecycle.State.STARTED
    }
    lifecycleDispatcher.invoke(state)
}
```

这段逻辑是**从 `androidx.compose.ui.scene.ComposeContainer.updateLifecycleState` 复制而来的**。
为什么要「复制」而不是「逐事件映射」？因为 AWT 的事件顺序非常反直觉。

作者在源码注释里贴了真实日志：

```
打开窗口：
08:08:30.776  windowActivated
08:08:30.778  windowGainedFocus
08:08:30.779  windowOpened      <-- 注意！opened 在最后

最小化：
08:09:12.403  windowIconified
08:09:12.405  windowLostFocus
08:09:12.405  windowDeactivated

关闭（DISPOSE_ON_CLOSE）：
08:13:18.964  Closing
08:13:18.976  windowLostFocus
08:13:18.976  windowDeactivated
08:13:18.976  Closed
```

如果天真地把 `windowGainedFocus → ON_RESUME`、`windowOpened → ON_CREATE` 直接映射，
打开窗口时会先收到 `ON_RESUME` 再收到 `ON_CREATE` —— `LifecycleRegistry` 会直接抛
`IllegalStateException`（不能从 RESUMED 回到 CREATED）。

所以正确做法是：**先维护标志位，再根据标志位推导目标状态，让 `LifecycleRegistry` 自己算出需要补发哪些事件**。
`LifecycleRegistry.currentState = X` 会自动补齐中间状态（例如从 `INITIALIZED` 直接设为 `RESUMED`，
会依次派发 `ON_CREATE → ON_START → ON_RESUME`）。

状态映射表：

| 条件 | 目标状态 | 等价语义 |
| --- | --- | --- |
| `isDisposed` | `DESTROYED` | 窗口已销毁 |
| `!isOpened` | `CREATED` | 还没显示过 / 已被隐藏 |
| `isMinimized` | `CREATED` | 最小化（≈ 不可见，与 Activity 的 STOPPED 近似但更保守） |
| `isOpened && !isMinimized && isFocused` | `RESUMED` | 可见且有焦点（≈ 前台） |
| 其他（可见但无焦点） | `STARTED` | 可见但用户没在操作 |

### 7.3 为什么必须「劫持」监听器

`Component.addWindowListener` 之类的 AWT 实现是**替换式**的：内部只保存一个 `WindowListener` 引用。
而框架自己占用了这个位置（要放 `WindowLifecycleAdapter`）。
如果用户再调 `addWindowListener(...)`，就会把适配器顶掉 —— 生命周期功能立刻失效，而且很容易发现不了。

框架的解法是：**重写添加/移除方法，把用户的监听器塞进适配器，由适配器转发**。

```kotlin
override fun addWindowListener(listener: WindowListener) {
    this.lifecycleAdapter.windowListener = listener
}
override fun removeWindowListener(listener: WindowListener) {
    this.lifecycleAdapter.windowListener = null
}
```

代价很明确：**同一时刻只支持一个 `WindowListener` / 一个 `WindowFocusListener`**。
如果你需要多个监听器，请合并成一个 `WindowAdapter` 再注册。

框架自身在 `init` 里用的是 `super.addWindowListener(lifecycleAdapter)`
—— 绕开自己的重写，才能真正把适配器挂到 AWT 上。

### 7.4 生命周期回调是怎么被调用的

`LifecycleJFrame` 的 `init` 块做三件事：

```kotlin
init {
    // ① 注册一个观察者，把 Lifecycle.Event 分派到 open 的回调方法
    lifecycleRegistry.addObserver(object : LifecycleEventObserver {
        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            when (event) {
                Lifecycle.Event.ON_CREATE -> onCreate(savedState)
                Lifecycle.Event.ON_START -> onStart()
                Lifecycle.Event.ON_RESUME -> onResume()
                Lifecycle.Event.ON_PAUSE -> onPause()
                Lifecycle.Event.ON_STOP -> onStop()
                Lifecycle.Event.ON_DESTROY -> onDestroy()
                else -> Unit
            }
        }
    })
    // ② 初始化状态
    lifecycleRegistry.currentState = Lifecycle.State.INITIALIZED
    // ③ 把适配器挂到真正的 AWT 监听器上
    super.addWindowListener(lifecycleAdapter)
    super.addWindowFocusListener(lifecycleAdapter)
}
```

调用链可以概括为：

```
AWT 事件（EDT）
   ↓
WindowLifecycleAdapter（标志位 + 推导状态）
   ↓
LifecycleRegistry.currentState = ...
   ↓
LifecycleEventObserver（补发 ON_CREATE / ON_START / ...）
   ↓
LifecycleJFrame.onCreate(savedState) / onStart() / ... （open 方法，可覆写）
```

`ComponentJFrame` 在此基础上又多接了一层：在 `ON_DESTROY` 时清理 `ViewModelStore`，
并在 `onCreate` 里先 `savedStateRegistryController.performRestore(savedState)` 再做状态同步，
保证 `SavedStateHandle` 能拿到上次保存的值。

### 7.5 Intent 分发：所有窗口启动都走同一条路

`JFrameLauncher` 在构造时把自己注册进 `IntentProcessor`：

```kotlin
internal class JFrameLauncher(val stack: List<LifecycleJFrame>) : IJFrameLauncher, InstanceKoinComponent {

    val launchJFrameIntentProcessor = object : IOperateIntentProcessor<LaunchJFrameIntent> {
        override fun process(intent: LaunchJFrameIntent): Boolean {
            start(intent)
            return true
        }
    }

    init {
        getKoin().get<IntentProcessor>().registerProcessor(launchJFrameIntentProcessor)
    }

    override fun start(intent: LaunchJFrameIntent) {
        // ① 单实例模式：栈里已有同类窗口 → 复用
        if (intent.launchMode == LaunchMode.SINGLE_INSTANCE) {
            val old = stack.map { it::class.java to it }
                .find { (clazz, _) -> clazz.name == intent.targetJFrame.name }
            if (old != null) {
                old.second.onReStart(intent)
                return
            }
        }
        // ② 标准模式：在 EDT 上新开一个窗口
        SwingUtilities.invokeLater {
            val jFrame = intent.targetJFrame.getDeclaredConstructor().newInstance()
            jFrame.attach(intent)
            jFrame.isVisible = true
        }
    }
}
```

几个值得注意的设计决定：

1. **必须有无参构造**：`getDeclaredConstructor().newInstance()` 要求目标窗口类有 public 无参构造。
2. **窗口实例由框架创建**，调用方无法干预构造过程 —— 参数只能通过 `intent.data { ... }` 传。
3. **SINGLE_INSTANCE 不重建窗口**，而是回调 `onReStart(intent)`，由窗口自己决定怎么响应（例如刷新数据、跳转 Tab）。
4. **创建与显示一定在 EDT**（`SwingUtilities.invokeLater`），因此从任意线程调用 `openJFrame` 都是安全的。

`attach(intent)` 则把意图和注册表接上：

```kotlin
fun attach(intent: LaunchJFrameIntent) {
    this.intent = intent
    getServiceInstance<JFrameManager>().register(idn, this)
    registered = true
}
```

> `intent` 的类型是 `WeakReferenceDelegate<LaunchJFrameIntent>` —— **弱引用**。
> 如果调用方不持有这个 intent，它随时可能被 GC，窗口就「失忆」了（拿不到参数、`setResult` 会抛异常）。
> 详见[注意细节](#9-注意细节)。

### 7.6 状态保存与恢复的完整链路

```
窗口 A（发起方）
  │  构造 LaunchJFrameIntent，设置 token = Tokens.of("editor")
  ↓
JFrameManager.openJFrame(intent)
  ↓
JFrameLauncher.start(intent) → 在 EDT new 出窗口 B
  ↓
窗口 B.attach(intent) → 窗口 B 的 token 来自 intent.token
  ↓
窗口 B 显示 → WindowLifecycleAdapter → CREATED
  ↓
LifecycleJFrame.onCreate(savedState)
     └─ savedState getter: 若未手动赋值且 token != null，
        就从 ApplicationSaveStateSaver.obtain(token) 取上次保存的快照
  ↓
ComponentJFrame.onCreate(savedInstanceState)
     └─ savedStateRegistryController.performRestore(savedInstanceState)
        → 注册过的 SavedStateProvider 把值还原到 SavedStateHandle
  ↓
用户关闭窗口（DISPOSE_ON_CLOSE）→ DESTROYED
  ↓
onDestroy()
     ├─ savedState?.let { onSaveInstanceState(it) }   // 各 owner 写入自己的数据
     └─ JFrameManager.remove(idn)
  ↓
ApplicationSaveStateSaver 持有 token → SavedState 的映射（内存中）
  ↓
下次以同一 token 打开 → obtain 拿回快照 → 数据回来了
```

三个关键点：

1. **token 是状态的主键**。没有 token，`savedState` 恒为 `null`，`onSaveInstanceState` 不会被调用，
   日志里会出现 `no savedState, not save data`。
2. **`savedState` 只能在 `INITIALIZED` 状态前赋值**，否则抛
   `IllegalStateException("Cannot set SavedState after CREATED")`。
3. **`ApplicationSaveStateSaver` 默认是内存级**。进程退出后状态即丢失；
   需要跨进程持久化时，请参考 `framework-common` 中 `ApplicationSaveStateSaver.export()` / `import()` 的用法，
   在应用退出/启动时自己落盘（`composeApp` 里的 `MainApplication.onDestroy()` 就是范例）。

### 7.7 线程模型

| 场景 | 线程 | 说明 |
| --- | --- | --- |
| 所有生命周期回调（`onCreate` 等） | **EDT** | 因为源头是 AWT 事件 |
| 窗口的创建与 `isVisible = true` | **EDT** | `JFrameLauncher` 用 `invokeLater` 包了一层 |
| `lifecycle.addObserver { }` 的回调 | 同事件源线程 | 默认就是 EDT |
| `JFrameManager.openJFrameForResult(intent, callback)` 的回调 | **`Dispatchers.Default`** | 内部 `mgr.scope.launch(Dispatchers.Default)`，**更新 UI 必须自行 `invokeLater`** |
| `openJFrameForResult(intent)` 返回的 `SharedFlow` 收集者 | 收集者所在上下文 | 用 `flowOn` / 自行切换 |
| `setResult(...)` | 任意线程 | 内部 `tryEmit` 到 `MutableSharedFlow(replay = 1)` |

因此一个常见的错误写法是：

```kotlin
// 错误：回调里直接操作 Swing 组件
JFrameManager.openJFrameForResult(intent, JFrameResultCallback { code, data ->
    textArea.text = data?.getString("text") ?: ""   // 非 EDT，可能引发界面异常
})
```

正确写法：

```kotlin
JFrameManager.openJFrameForResult(intent, JFrameResultCallback { code, data ->
    SwingUtilities.invokeLater { textArea.text = data?.getString("text") ?: "" }
})
```

更推荐的做法是用协程「等一个结果」，把回调地狱变回顺序代码：

```kotlin
lifecycleScope.launch {
    JFrameManager.openJFrame(intent)                 // 先打开窗口
    val result = intent.jFrameResultFlow.first()     // 挂起等待 setResult(...)
    textArea.text = result.data?.getString("text") ?: ""   // 回到 EDT，可直接改 UI
}
```

`jFrameResultFlow` 是 `MutableSharedFlow(replay = 1)`，没有结果时会一直挂起，因此协程取消即自动放弃等待。
框架另外提供了 `JFrameManager.openJFrameForResultSuspend(intent, callback)` 与
`LaunchJFrameIntent.collectJFrameResult { }`：它们会在**调用方协程上下文**里持续收集，
不会在拿到第一个结果后自动结束，适合「长期监听窗口结果」而非「取一次结果」的场景。

> `lifecycleScope` 是 `androidx.lifecycle` 提供的 Kotlin 扩展（随 `lifecycle-runtime` 传递而可用），
> 其默认调度器在桌面端即 Swing 的 EDT，所以在里面直接改 UI 是安全的。

---

## 8. 核心概念详解

### 8.1 启动入口：`Singularity.boot()`

```kotlin
package androidx.jvm.swing.lifecycle.core.intent

object Singularity {
    fun boot() {
        ServiceBooter.bootstrap(logger)
        InstanceContext.get().run { get<JFrameManager>() }
    }
}
```

两行代码，分工明确：

1. `ServiceBooter.bootstrap(logger)` —— 来自 `framework-common`。它负责：
   - 启动 Koin 容器（`InstanceContext.startKoin()`）；
   - 通过 `ServiceLoader` 扫描所有 `ModuleProvider`（`toolkit` 的平台实现也在这里被装配）；
   - 注册 `IntentProcessor`、`ApplicationSaveStateSaver` 等基础设施；
   - 安装全局异常处理器。
2. `get<JFrameManager>()` —— **实例化 `JFrameManager`，从而实例化 `JFrameLauncher`**。
   `JFrameLauncher` 的 `init` 里才把自己注册进 `IntentProcessor`。
   如果不提前创建，`openJFrame` 走到意图分发时会找不到对应处理器。

调用时机：**`main()` 里、创建任何窗口之前**。重复调用 `bootstrap()` 不会报错（内部有幂等保护），
但没必要。

### 8.2 `LifecycleJFrame`：只带生命周期的窗口

```kotlin
open class LifecycleJFrame : JFrame, LifecycleOwner, InstanceKoinComponent
```

它是最小可用窗口，提供：

| 成员 | 说明 |
| --- | --- |
| `lifecycle` / `lifecycleRegistry` | `LifecycleOwner` 实现，可 `addObserver` |
| `intent: LaunchJFrameIntent?` | 启动意图（弱引用持有） |
| `savedState: SavedState?` | 状态快照；未手动赋值且有 token 时自动从 saver 取 |
| `token: Token?` | 来自 `intent.token`（`internal`，外部通过 intent 设置） |
| `attach(intent)` | 绑定意图并注册进 `JFrameManager` |
| `onReStart(intent)` | `SINGLE_INSTANCE` 命中时被回调（**默认空实现，必须自己覆写**） |
| `setResult(code, data)` | 向启动方回传结果 |
| `finish()` | `isVisible = false; dispose()` |
| `onCreate / onStart / onResume / onPause / onStop / onDestroy` | 生命周期回调，均为 `open` |
| `onSaveInstanceState(outState)` | 保存状态 |
| `SUCCESS` / `FAILED` / `DEFAULT_RESULT_FLOW` | 结果码与默认信箱名常量 |

它**没有** ViewModel 与 `SavedStateRegistry`。如果你只需要生命周期 + Intent 跳转，用它最轻。

一个容易忽略的设计：`onDestroy()` 里做的是

```kotlin
open fun onDestroy() {
    savedState?.let { onSaveInstanceState(it) } ?: logger.warn("no savedState, not save data")
    if (registered) {
        getServiceInstance<JFrameManager>().remove(idn)
    }
}
```

也就是说：**状态保存与从管理器注销都发生在 `onDestroy` 里**，而 `onDestroy` 只在窗口真正 `dispose()` 后才触发。

### 8.3 `ComponentJFrame`：完整窗口（推荐）

```kotlin
open class ComponentJFrame : LifecycleJFrame,
    ViewModelStoreOwner,
    HasDefaultViewModelProviderFactory,
    SavedStateRegistryOwner
```

八个可用构造（对应 `JFrame` 的四种 × 是否带 `GraphicsConfiguration`）：

```kotlin
ComponentJFrame()
ComponentJFrame(gc: GraphicsConfiguration?)
ComponentJFrame(title: String?)
ComponentJFrame(title: String?, gc: GraphicsConfiguration?)
```

它比 `LifecycleJFrame` 多的就是「现代组件三件套」：

| 实现 | 作用 |
| --- | --- |
| `ViewModelStoreOwner` | `ViewModel` 随窗口存活，`ON_DESTROY` 时 `viewModelStore.clear()` |
| `HasDefaultViewModelProviderFactory` | 默认工厂为 `MySavedStateViewModelFactory`，支持构造参数 `SavedStateHandle` |
| `SavedStateRegistryOwner` | 提供 `SavedStateRegistry`，`SavedStateHandle` 的读写最终落到这里 |

覆写时 **务必调用 `super`**：

```kotlin
override fun onCreate(savedInstanceState: SavedState?) {
    super.onCreate(savedInstanceState)   // 内部 performRestore + syncLife(ON_CREATE)，绝不能省
    // 你的初始化逻辑
}

override fun onSaveInstanceState(outState: SavedState) {
    super.onSaveInstanceState(outState)   // 内部 performSave
    // 需要额外写入 outState 时再动手
}

override fun onDestroy() {
    super.onDestroy()                     // 内部写回 savedState + 从 JFrameManager 移除
}
```

`ComponentJFrame` 的 `onCreate` 顺序很关键：**先 `performRestore(savedState)`，再同步生命周期**。
这样 `SavedStateHandle` 在读的时候就已经有值了。

### 8.4 生命周期回调的时机

```
new ComponentJFrame()
    │  state = INITIALIZED
    │
setVisible(true)  ──► windowActivated / windowGainedFocus / windowOpened（三个事件，opened 最后）
    │  ⚙ 适配器换算：isOpened = true, isFocused = true → RESUMED
    │  LifecycleRegistry 自动补发：ON_CREATE → ON_START → ON_RESUME
    ├──► onCreate(savedInstanceState)      ← 只触发一次
    ├──► onStart()
    └──► onResume()

用户切走焦点 ──► windowLostFocus → STARTED → onPause()
切回来     ──► windowGainedFocus → RESUMED → onResume()
最小化     ──► windowIconified → CREATED → onPause() + onStop()
取消最小化 ──► windowDeiconified + windowActivated + windowGainedFocus → RESUMED → onStart() + onResume()
dispose()  ──► windowClosed → DESTROYED → onPause() + onStop() + onDestroy()
```

几个反直觉但必须知道的点：

- **`onCreate` 发生在窗口已经「显示」之后**（因为 `windowOpened` 是最晚的一个事件）。
  你在这里设置尺寸、`setLocationRelativeTo` 都还来得及，但窗口可能已经闪现过一次。
  需要「显示前完成初始化」时，请把逻辑放在**构造器**里。
- **最小化 = `CREATED`**，因此会触发 `onStop()`（而不是「什么也不发生」）。
- **恢复**时 `onStart()` / `onResume()` 会**再触发一次**，所以这些回调里不要做「只应执行一次」的事。
- `HIDE_ON_CLOSE`（JFrame 默认）**只隐藏不销毁**，因此 `onDestroy` 永远不触发；
  再次显示时 `windowOpened` 不会重发，生命周期停在 `CREATED`/`STARTED` 一带。

### 8.5 `LaunchJFrameIntent`：窗口之间的粘合剂

```kotlin
class LaunchJFrameIntent : OperateIntent {
    var from: Class<out Any>?              // 谁启动的（internal set，构造器传入）
    var targetJFrame: Class<out LifecycleJFrame>  // 目标窗口类（internal set）
    var launchMode: LaunchMode             // STANDARD / SINGLE_INSTANCE（internal set）
    var mData: Bundle                      // 启动参数（private set，用 data {} 写入）
    var token: Token?                      // 状态保存主键，只能设置一次
}
```

使用套路：

```kotlin
val intent = LaunchJFrameIntent(
    from = this,                          // 传 null 表示不是由窗口启动
    to = EditorScreen::class.java,
    launchMode = LaunchMode.STANDARD,
).data {                                  // 写启动参数
    putString("path", "/tmp/a.txt")
    putInt("mode", 3)
}.apply {
    token = Tokens.of("editor")           // 想要状态保存就设 token
}

JFrameManager.openJFrame(intent)
```

目标窗口里读参数：

```kotlin
override fun onCreate(savedInstanceState: SavedState?) {
    super.onCreate(savedInstanceState)
    val path = intent?.getData<String>("path")     // 没有或类型不符 → null
    val mode = intent?.getData<Int>("mode") ?: 0
}
```

> `from` 与 `launchMode` 的 setter 是 `internal`，所以**只能在构造器里指定**；
> `data { }` 是唯一写参数的入口（`mData` 的 setter 是 `private`）。
> Java 侧设置 token 要用 `setTokenForJava(Tokens.of("..."))`（Kotlin 侧直接 `token = ...`）。

**为什么 token 只允许设置一次？**
因为它是状态保存的键，中途更换会让「之前保存的快照」与「当前窗口」失联，属于典型的设计事故。
重复赋值会抛 `IllegalArgumentException("Token already set")`。

### 8.6 `LaunchMode`：标准与单实例

```kotlin
enum class LaunchMode {
    SINGLE_INSTANCE,
    STANDARD,
}
```

| 模式 | 行为 |
| --- | --- |
| `STANDARD`（默认） | 每次打开都 `newInstance()`，多个实例并存 |
| `SINGLE_INSTANCE` | 先在窗口栈里找**类名相同**的实例；命中则 `onReStart(intent)`，否则新建 |

`SINGLE_INSTANCE` 的配套写法：

```kotlin
class EditorScreen : ComponentJFrame("编辑器") {
    /** 命中单实例时被调用，参数是这一次的新 Intent */
    override fun onReStart(intent: LaunchJFrameIntent) {
        super.onReStart(intent)
        val path = intent.getData<String>("path")
        // 常见做法：把窗口提到前台 + 应用新参数
        toFront()
        requestFocus()
        path?.let { loadFile(it) }
    }
}
```

注意它**不会替你 `toFront()`**，也不会有动画，全部要自己写。

### 8.7 `JFrameManager`：窗口注册表 + 启动器

```kotlin
class JFrameManager : InstanceKoinComponent {
    private val jFrameMap: MutableMap<Token, LifecycleJFrame> = mutableMapOf()
    internal val stack = mutableListOf<LifecycleJFrame>()
    val jFrameStack: List<LifecycleJFrame> get() = stack
    val launcherManager: IJFrameLauncher = JFrameLauncher(stack)

    fun launchJFrame(intent: LaunchJFrameIntent)
    operator fun get(uuid: Token?): LifecycleJFrame?
    operator fun get(cls: Class<out LifecycleJFrame>): LifecycleJFrame?
    fun register(uuid: Token, jFrame: LifecycleJFrame)
    fun remove(uuid: Token)
    fun release()
}
```

四种打开方式（全部 `@JvmStatic`，Java 可直接调）：

```kotlin
// ① 打开，不关心结果
JFrameManager.openJFrame(intent)

// ② 打开并回调（回调在 Dispatchers.Default，拿到首个结果后自动 cancel）
JFrameManager.openJFrameForResult(intent) { code, data -> /* ... */ }

// ③ 打开并返回 SharedFlow（自己 collect，可多次使用）
val flow = JFrameManager.openJFrameForResult(intent)
flow.collect { result -> /* JFrameResult(resultCode, data) */ }

// ④ 打开并在协程里持续收集
lifecycleScope.launch {
    JFrameManager.openJFrameForResultSuspend(intent) { code, data -> /* ... */ }
}
```

查找窗口：

```kotlin
val byToken = JFrameManager.get<Token>(Tokens.of("editor"))   // Kotlin
val byClass = JFrameManager.get(EditorScreen::class.java)     // 多个实例时返回最早注册的那个
```

> `get(uuid)` / `get(cls)` 是 `JFrameManager` 类的 `operator fun`，调用时要先拿到实例，
> 例如 `InstanceContext.get().get<JFrameManager>()[token]`。
> 只有 `companion object` 里的 `openJFrame*` 是静态可直接调的。

关闭全部：

```kotlin
InstanceContext.get().get<JFrameManager>().release()   // 逐个 finish()，然后清空表与栈
```

### 8.8 结果回传：`setResult` 的三种消费姿势

子窗口（发送方）：

```kotlin
setResult(ComponentJFrame.SUCCESS, Bundle().apply { putString("text", "hello") })
finish()
```

父窗口（接收方）三种等价写法：

```kotlin
// A. 回调（注意线程）
JFrameManager.openJFrameForResult(intent) { code, data ->
    SwingUtilities.invokeLater { handle(code, data) }
}

// B. Flow
lifecycleScope.launch {
    intent.jFrameResultFlow.collect { result -> handle(result.resultCode, result.data) }
}

// C. 只取一次（推荐）
lifecycleScope.launch {
    JFrameManager.openJFrame(intent)
    val result = intent.jFrameResultFlow.first()
    handle(result.resultCode, result.data)
}
```

信箱的底层实现就是 `MutableSharedFlow(replay = 1)`：

```kotlin
fun <T : Any> getMailBox(name: String): MutableSharedFlow<T> =
    mailBox.getOrPut(name) { MutableSharedFlow(replay = 1) } as MutableSharedFlow<T>
```

`replay = 1` 意味着**先 `setResult` 再 `collect` 也能拿到**（结果不会被丢掉），
但也意味着同一个 intent 复用第二次时，可能立刻收到上一次的结果 —— 每次启动请构造新的 intent。

如果你需要传递自定义类型（非 `ComponentResult`），可以直接用信箱：

```kotlin
// 子窗口
intent?.getMailBox<String>("progress").emit("50%")
// 父窗口
intent.getMailBox<String>("progress").collect { println(it) }
```

> `setResult` 依赖 `intent`。如果 `intent` 被 GC（见第 9 节），会抛
> `IllegalStateException("jframe_result_flow can't be null")`。

### 8.9 `Token` 与状态保存

```kotlin
val token: Token = Tokens.of("editor-screen")   // 或 Token("editor-screen")
intent.token = token
```

- `Token` 是带 `value: String` 的轻量值对象；
- 它被 `ApplicationSaveStateSaver` 用作 `SavedState` 的键；
- 没有 token → 不保存、不恢复、`savedState` 为 `null`；
- token 相同 → 视为「同一个窗口」，下次打开时会拿到上次关闭时的快照。

自定义保存内容有三种粒度：

```kotlin
// 粒度 1：SavedStateHandle（推荐，自动随 ViewModel 生命周期走）
class MyVm(val handle: SavedStateHandle) : ViewModel() {
    var text: String
        get() = handle["text"] ?: ""
        set(v) { handle["text"] = v }
}

// 粒度 2：SavedStateRegistry（需要自己注册 Provider，返回值是 Bundle）
override fun onCreate(savedInstanceState: SavedState?) {
    super.onCreate(savedInstanceState)
    savedStateRegistry.registerSavedStateProvider("ui") {
        Bundle().apply { putInt("width", width) }
    }
}

// 粒度 3：直接覆写 onSaveInstanceState（最粗暴，什么都自己写）
override fun onSaveInstanceState(outState: SavedState) {
    super.onSaveInstanceState(outState)
    outState.write { putInt("width", width) }        // 注意：用 write { }，不是 outState.putInt(...)
}
```

> **关于 `SavedState` 的 API**
> 桌面端的 `androidx.savedstate.SavedState` **不是 `Bundle`**，它内部是一个
> `MutableMap<String, Any?>`，只提供 `read { }` / `write { }` 两个 DSL 入口：
>
> ```kotlin
> outState.write {
>     putInt("width", width)
>     putString("title", title)
> }
>
> val w = savedState?.read { getIntOrNull("width") } ?: 1024
> ```
>
> 而 `SavedStateRegistry.registerSavedStateProvider { }` 的回调要返回 `androidx.core.bundle.Bundle`
> （它也有 `putInt` / `getInt`），两者不要搞混。
```

想跨进程保留？`ApplicationSaveStateSaver` 本身是内存态的。
参考 `composeApp` 的 `MainApplication`：在应用退出时 `export()` 成文件，启动时 `import()` 回来。

### 8.10 `ComponentJDialog`：带生命周期的对话框

```kotlin
open class ComponentJDialog : JDialog,
    LifecycleOwner,
    ViewModelStoreOwner,
    HasDefaultViewModelProviderFactory,
    SavedStateRegistryOwner
```

16 个构造覆盖了 `JDialog` 的全部分支（`Frame` / `Dialog` / `Window` × 标题 × `modal` / `ModalityType` × `gc`）。

生命周期来源与 `LifecycleJFrame` 完全一致（同一个 `WindowLifecycleAdapter`）。
它更适合「由父窗口管理显示/隐藏」的场景：

```kotlin
class SettingsDialog(owner: Frame?) : ComponentJDialog(owner, "设置", true) {
    private val vm: SettingsViewModel by viewModels()

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE   // 同样建议显式设置
        setSize(480, 320)
        setLocationRelativeTo(owner)
    }

    override fun onDestroy() {
        super.onDestroy()   // 内部会保存 savedState
    }
}

// 显示（模态时会阻塞当前 EDT 调用栈，注意不要在有协程依赖的线程里直接调用）
SettingsDialog(this).isVisible = true
```

> 注意：`ComponentJDialog` **没有** `LaunchJFrameIntent` 支持，也不经过 `JFrameManager`。
> 它是一次性的、由调用方 `new` 出来的对象；需要「结果回传」时请自己用回调/属性。

### 8.11 `ComponentJPanel`：基于 JPanel 的 Fragment

```kotlin
open class ComponentJPanel : JPanel,
    LifecycleOwner,
    ViewModelStoreOwner,
    HasDefaultViewModelProviderFactory,
    SavedStateRegistryOwner
```

它的定位是「**Fragment**」：一个可复用的带生命周期的面板，生命周期**跟随宿主**（`JFrame` / `JDialog` / 其他 `LifecycleOwner`）。

实现方式是监听 `AncestorListener`：

```kotlin
addAncestorListener(object : AncestorListener {
    override fun ancestorAdded(event: AncestorEvent?) {
        // 沿 parent 链向上找最近的 LifecycleOwner
        findLifecycleOwner(event?.component?.parent)?.let { if (it !== hostOwner) bindHost(it) }
    }
    override fun ancestorRemoved(event: AncestorEvent?) {
        unbindHost(destroy = true)   // 被移出容器 → 视为销毁
    }
    override fun ancestorMoved(event: AncestorEvent?) { }
})
```

`bindHost` 会做两件事：

1. 把宿主后续的生命周期事件转发给自己；
2. **对齐宿主当前状态**（宿主已是 `CREATED` → 补发 `onCreate`；`STARTED` → 再补 `onStart`；`RESUMED` → 再补 `onResume`）。

因此它**必须被添加进某个已有 `LifecycleOwner` 的容器**才会「活」起来：

```kotlin
class MainScreen : ComponentJFrame("主窗口") {
    init {
        contentPane.add(EditorPanel())   // 加入后 ancestorAdded → 找到 this → 开始同步生命周期
    }
}

class EditorPanel : ComponentJPanel() {
    private val vm: EditorVm by viewModels()

    override fun onCreate(savedInstanceState: SavedState?) {
        super.onCreate(savedInstanceState)
        add(JLabel("我随宿主一起创建"))
    }

    override fun onDestroy() {
        super.onDestroy()
        // 被移除或宿主销毁时清理
    }
}
```

已知限制：

- 宿主查找是「沿 `parent` 链找第一个 `LifecycleOwner`」，嵌在普通 `JPanel` 里也能找到，但**中途换宿主只支持一次**（`bindHost` 里有 `it !== hostOwner` 检查）；
- `ancestorRemoved` 立即 `unbindHost(destroy = true)`，即**从容器移除 = 销毁**，不支持「移除后再添加回来」；
- 内部 `handleLifecycleEvent` 包了 try/catch 打日志，状态跳变异常不会崩界面，但**也不会通知你**，调试时记得看日志。

### 8.12 ViewModel：Kotlin 用法

```kotlin
@MainThread
inline fun <reified VM : ViewModel> ComponentJFrame.viewModels(
    noinline extrasProducer: (() -> CreationExtras)? = null,
    noinline factoryProducer: (() -> Factory)? = null,
): Lazy<VM>
```

三种常见写法：

```kotlin
// ① 无参 VM（或只有 SavedStateHandle 参数）
private val vm: EditorViewModel by viewModels()

// ② 自定义工厂
private val vm: EditorViewModel by viewModels {
    viewModelFactory {
        initializer { EditorViewModel(loadFromDisk()) }
    }
}

// ③ 通过 CreationExtras 传参（最推荐，VM 不需要知道 View 存在）
private val key = object : CreationExtras.Key<String> {}
private val vm: EditorViewModel by viewModels(
    extrasProducer = {
        MutableCreationExtras(defaultViewModelCreationExtras).apply { this[key] = "file:///a.txt" }
    },
    factoryProducer = {
        viewModelFactory {
            initializer { EditorViewModel(this[key] ?: error("no path")) }
        }
    }
)
```

默认工厂 `MySavedStateViewModelFactory` 的行为：

- 若 `VM` 构造器只有一个 `SavedStateHandle` 参数 → 自动注入；
- 若有无参构造 → 直接 `new`；
- 否则必须提供自定义 `Factory`。

`ViewModel` 的清理时机：`ON_DESTROY` 时 `viewModelStore.clear()`，`viewModelScope` 随之取消。
因此「窗口关了但后台任务还在跑」这种事不会发生 —— 除非你把 VM 提到了更外层的作用域。

### 8.13 ViewModel：Java 用法

`androidx.lifecycle` 的 API 大量使用 Kotlin 的 `KClass` / 默认参数 / 操作符重载，Java 调用很难看。
本模块提供了三个 Java 门面：

```java
// ① 最简单：使用默认工厂 + 默认 CreationExtras
EditorViewModel vm = JavaViewModelProvider.create(this).get(EditorViewModel.class);

// ② 指定工厂（Kotlin 的 companion 里拿）
EditorViewModel vm2 = JavaViewModelProvider
        .create(this, BookEditorViewModel.Companion.getFactory())
        .get(BookEditorViewModel.class);

// ③ 指定 key（同一个 owner 内共存多个同类 VM）
EditorViewModel vm3 = JavaViewModelProvider.create(this).get("main", EditorViewModel.class);
```

带参数的 VM，用 `JavaCreationExtras` 构造 `CreationExtras`：

```java
public final class Keys {
    public static final CreationExtras.Key<Integer> VALUE = new CreationExtras.Key<Integer>() {};
}

CreationExtras extras = JavaCreationExtras.mutableCreationExtrasOf(this, mce -> {
    mce.set(Keys.VALUE, 42);          // Kotlin 的 operator set 在 Java 里就是 set(...)
});

BookEditorViewModel vm = JavaViewModelProvider
        .create(this, BookEditorViewModel.Companion.getFactory(), extras)
        .get(BookEditorViewModel.class);
```

对应的 Kotlin 工厂（`swing/src/test/java/viewmodel/BookEditorViewModel.kt` 有完整示例）：

```kotlin
class BookEditorViewModel(
    val value: Int,
    val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    companion object {
        val creationExtrasKey1 = object : CreationExtras.Key<Int> {}

        val factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
                val v = extras[creationExtrasKey1] ?: 0
                @Suppress("UNCHECKED_CAST")
                return BookEditorViewModel(v, extras.createSavedStateHandle()) as T
            }
        }
    }
}
```

`JavaCreationExtras` 提供的方法：

| 方法 | 说明 |
| --- | --- |
| `EmptyCreationExtras` | 空 extras |
| `mutableCreationExtrasOf(owner, builder)` | 以 owner 的默认 extras 为底，再追加内容 |
| `mutableCreationExtrasOf(builder)` | 从空 extras 开始 |
| `modify(exist, builder)` | 复制并修改已有 extras |

`MutableCreationExtrasBuilder` 是 `fun interface`，Java 里可直接写 lambda。

### 8.14 外观与高分屏

框架不绑定 LookAndFeel，但示例统一用 **FlatLaf**（`com.formdev:flatlaf:3.7.2`，已在本模块依赖中）：

```kotlin
fun main() {
    FlatLightLaf.setup()                        // 或 FlatDarkLaf / FlatIntelliJLaf
    System.setProperty("flatlaf.uiScale", "1.25")   // 必须在 LAF 初始化前后尽早设置
    Singularity.boot()
    SwingUtilities.invokeLater { MainScreen().isVisible = true }
}
```

窗口图标与尺寸（没有 `AndroidManifest`，这些只能自己写）：

```kotlin
class MainScreen : ComponentJFrame("Demo") {
    init {
        iconImage = ImageIO.read(javaClass.getResource("/icons/icon.png"))
        setSize(1024, 720)
        setLocationRelativeTo(null)          // 居中
        minimumSize = Dimension(640, 480)
        defaultCloseOperation = DISPOSE_ON_CLOSE
    }
}
```

### 8.15 与 `toolkit` 配合

`framework-swing` 依赖 `toolkit:spi`，因此下列能力开箱可用（需正确装配平台实现）：

```kotlin
// 应用路径（安装目录 / 用户配置目录 / 内置配置目录）
val provider = AppPathProvider.getInstance()
val configDir = provider.configDirPath.keepDirExist()

// 进程单实例锁
ProcessLocker.lock(configDir.resolve("lockfile.lock").toNioPath())

// 异步启动时释放锁
Runtime.getRuntime().addShutdownHook(Thread { ProcessLocker.unlock() })
```

`composeApp` 的 `MainActivity` / `MainApplication` 里就是这套组合，可以直接照抄。
详见 [`toolkit/README.md`](../../toolkit/README.md)。

---

## 9. 注意细节

以下每一条都对应真实踩过的坑，按重要性排序。

1. **必须设置 `DISPOSE_ON_CLOSE`**
   `JFrame` 默认是 `HIDE_ON_CLOSE`：关窗只是隐藏，不 `dispose`，
   于是 `windowClosed` 不触发 → 生命周期停在 `CREATED` → `onDestroy` 永不执行 →
   **状态不保存、`ViewModel` 不清理、`JFrameManager` 里残留引用**。
   每个窗口的构造器里第一件事就应该是 `defaultCloseOperation = DISPOSE_ON_CLOSE`。

2. **验证被 GC 的 `intent`**
   `LifecycleJFrame.intent` 是 `WeakReferenceDelegate`（弱引用）。
   如果你写 `JFrameManager.openJFrame(LaunchJFrameIntent(this, A::class.java))` —— 匿名对象没人持有，
   随时可能被回收，随后 `intent.getData(...)` 得到 `null`、`setResult` 抛异常、`savedState` 也拿不到。
   **启动方必须持有 intent 变量**（`openJFrameForResult` 系列内部会持有，安全）。

3. **回调线程不是 EDT**
   `openJFrameForResult(intent, callback)` 内部是 `scope.launch(Dispatchers.Default)`，
   回调里直接改 Swing 组件属于未定义行为。要么 `SwingUtilities.invokeLater`，要么用
   `lifecycleScope + jFrameResultFlow.first()`。

4. **`token` 只能设置一次**
   重复赋值抛 `IllegalArgumentException("Token already set")`。
   复用 intent 对象二次启动也会踩到它 → **每次启动新建 intent**。

5. **`savedState` 在 `CREATED` 之后不可写**
   抛 `IllegalStateException("Cannot set SavedState after CREATED")`。
   需要注入自定义快照时，只能在构造器或 `onCreate` 之前做。

6. **`JFrameManager.remove(uuid)` 会留下栈残留（已知缺陷）**
   ```kotlin
   fun remove(uuid: Token) {
       jFrameMap.remove(uuid)        // 先删了
       stack.remove(jFrameMap[uuid]) // 这里拿到的是 null，移除失败
   }
   ```
   结果：`jFrameStack` 里会残留已 `dispose` 的窗口引用；
   `SINGLE_INSTANCE` 匹配的是 `stack`，因此**可能命中一个已销毁的窗口**并在它身上调 `onReStart`
   （窗口不显示，看起来像「点了没反应」）。
   规避：`onReStart` 里做 `isDisplayable` / `isVisible` 判断，必要时自己 `finish()` 后再重开；
   或降低对 `SINGLE_INSTANCE` 的依赖。

7. **`SINGLE_INSTANCE` 只按「类名」匹配**
   同一窗口类的两个不同 token 实例也只会保留一个。需要多实例（比如同时打开两个文档）请用 `STANDARD` + 不同 token。

8. **窗口类必须有 public 无参构造**
   `JFrameLauncher` 用 `getDeclaredConstructor().newInstance()` 创建实例。
   没有无参构造 → `NoSuchMethodException`；构造器不是 public → `IllegalAccessException`。

9. **`onCreate` 晚于窗口显示**
   想在「第一帧之前」做初始化（比如按数据决定窗口尺寸），请写在构造器里。

10. **不要在生命周期回调里做耗时操作**
    所有回调都在 EDT 上。要读文件、连数据库、跑网络，请 `lifecycleScope.launch(Dispatchers.IO) { ... }`，
    回到 UI 时用 `withContext(Dispatchers.Swing)`。

11. **一个窗口只能有一个 `WindowListener` / `WindowFocusListener`**
    框架重写了 `addWindowListener` 等方法（把监听器转发给内部适配器），后注册的会**覆盖**先注册的。
    需要多个监听逻辑时，合并到一个 `WindowAdapter` 里。

12. **给 `ComponentJPanel` 一个宿主**
    它靠 `ancestorAdded` 找宿主，单独 `new` 出来不加进容器的话，生命周期永远停在 `INITIALIZED`，
    `viewModels()` 也不会被清理（因为从没创建过）。

13. **`ComponentJPanel` 移除即销毁**
    `ancestorRemoved` → `unbindHost(destroy = true)`。想做「来回切换的 Tab 页」请用 `CardLayout`
    保留组件（不真正移除），或在销毁时自行把状态写进宿主 VM。

14. **`ApplicationSaveStateSaver` 是内存态**
    进程退出即丢。需要持久化就自己 `export()` / `import()`（见 `composeApp/MainApplication.kt`）。

15. **没有 token 就没有 `savedState`**
    日志里出现 `no savedState, not save data` 就是提醒你：这个窗口不参与状态保存。

16. **`release()` 会关闭所有受管窗口**
    但它不会调用 `System.exit`，也不会停掉 Koin。退出应用请显式 `exitProcess(0)`（或 `System.exit`），
    并在之前手动保存需要持久化的数据。

17. **`forms_rt` 不在传递依赖里**
    使用 IDEA GUI Designer 生成的 `$$$setupUI$$$` 代码时，请自行引入 `com.intellij:forms_rt`。

18. **不要把 `framework-swing` 和 `framework-compose` 混用**
    两套窗口体系各自持有状态保存与 Intent 处理器，混用会出现「状态被另一套覆盖」的诡异问题。

---


## 11. 常见问题（FAQ）

**Q1：`IllegalComponentStateException: contentPane cannot be set to null` 怎么办？**
这是 IDEA GUI Designer 生成的 `BookEditorExample` 之类代码与 Gradle/IDEA 构建方式不匹配导致的。
`framework/swing/src/test/java/Main.java` 顶部注释给了两种解法：

1. **Maven 项目**：Settings → Build, Execution, Deployment → Build Tools → Maven → Runner，
   取消勾选 `Delegate IDE build/run actions to Maven`；
2. **Gradle 项目**：Settings → Build, Execution, Deployment → Build Tools → Gradle，
   把 `Build and run using` 改成 `IntelliJ IDEA`（不要用 Gradle 构建运行）。

本质是：`.form` 的二进制资源没被打进 classpath，`$$$setupUI$$$` 拿不到 `contentPane`。

**Q2：窗口关了，但再打开状态没恢复。**
按顺序检查：

1. 是否设了 `token`？没 token 一定不保存。
2. 是否设了 `DISPOSE_ON_CLOSE`？`HIDE_ON_CLOSE` 不触发 `onDestroy`。
3. 两次打开的 token 是否完全一致（`Tokens.of("a") != Tokens.of("A")`）。
4. 状态是否写进了 `SavedStateHandle` / `SavedStateRegistry` / `onSaveInstanceState` 三者之一。
5. 日志里是否有 `no savedState, not save data`。

**Q3：`JFrameManager.openJFrame(...)` 什么也没发生。**

- 忘了 `Singularity.boot()`（`JFrameLauncher` 未注册）；
- 目标类没有 public 无参构造；
- 目标类不是 `LifecycleJFrame` 子类；
- 构造器里抛了异常（被 EDT 吞掉，看控制台堆栈）。

**Q4：`SINGLE_INSTANCE` 第二次点击没反应。**
`onReStart(intent)` 默认是空实现，必须自己写「提到前台」的逻辑：

```kotlin
override fun onReStart(intent: LaunchJFrameIntent) {
    toFront(); requestFocus()
}
```

另外注意第 9 节第 6 条：窗口栈可能残留已销毁实例，导致命中的是「幽灵窗口」。

**Q5：`setResult` 抛 `IllegalStateException: jframe_result_flow can't be null`。**
说明 `intent` 已经为 `null` —— 被弱引用回收了。
要么窗口不是通过 `JFrameManager` 打开的（没有 `attach`），要么启动方没持有 intent。

**Q6：回调里改 UI 偶发异常 / 界面不刷新。**
回调在 `Dispatchers.Default`。用 `SwingUtilities.invokeLater`，或改用 `lifecycleScope + jFrameResultFlow.first()`。

**Q7：`onCreate` 里设置窗口尺寸，屏幕会闪一下。**
因为 `onCreate` 在 `windowOpened` 之后。把尺寸/位置的设置移到构造器。

**Q8：ViewModel 在窗口关闭后还在跑。**

- `ComponentJFrame` / `ComponentJDialog` / `ComponentJPanel` 才会在 `ON_DESTROY` 清 `ViewModelStore`；
- 若你用的是 `LifecycleJFrame`（不带 VM 支持），`viewModels()` 也无从谈起；
- 若你把 VM 放进了更外层的作用域（比如自己 new 一个 owner），它当然不会随窗口销毁。

**Q9：`NoSuchMethodError` / `AbstractMethodError`（androidx.lifecycle 相关）。**
版本被别的依赖顶高了。用第 5.4 节的 `resolutionStrategy.force { }` 锁定到
`2.11.0-alpha02` / `savedstate 1.3.4-rc01`。

**Q10：能不能不用 Koin / 不用 `Singularity`，自己 `new` 窗口？**
可以。`LifecycleJFrame` 的生命周期能力不依赖 Koin，只要你不用 `intent` 相关功能：

```kotlin
val frame = MyFrame()
frame.isVisible = true   // 生命周期照常工作
```

但 `stateSaver`、`JFrameManager`、`Token` 这些都需要 Koin 容器，
所以「想用完整能力就必须 boot」。另外 `LifecycleJFrame` 实现了 `InstanceKoinComponent`，
其 `get()` / `inject()` 在容器未启动时会抛异常。

**Q11：多个窗口共享同一个 ViewModel？**
`viewModelStore` 是**按 owner** 隔离的。想共享就把 VM 挂在共同的 owner 上
（比如主窗口的 `viewModelStore` 手动传给别人），或干脆用一个单例 Service 承载状态 —— 
后者通常更清晰。

**Q12：怎么让窗口记住「上次的尺寸与位置」？**
放进 `SavedStateHandle` 或 `onSaveInstanceState`，并在 `onCreate` 时应用；
但注意 `onCreate` 晚于显示，所以尺寸恢复建议在构造器里读 `savedState`：

```kotlin
class MainScreen : ComponentJFrame("Demo") {
    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        // 构造器阶段就可以从 saver 拿到上次的快照（onCreate 之前）
        savedState?.read {
            setSize(getIntOrNull("w") ?: 1024, getIntOrNull("h") ?: 720)
            setLocation(getIntOrNull("x") ?: 100, getIntOrNull("y") ?: 100)
        }
    }

    override fun onSaveInstanceState(outState: SavedState) {
        super.onSaveInstanceState(outState)
        outState.write {
            putInt("w", width)
            putInt("h", height)
            putInt("x", x)
            putInt("y", y)
        }
    }
}
```

---

## 相关文档

- [`framework/common/README.md`](../common/README.md)：`ServiceBooter`、`IntentProcessor`、`Token`、`ApplicationSaveStateSaver`、`EventBus`
- [`framework/compose/README.md`](../compose/README.md)：如果更想用 Compose 写桌面 UI
- [`toolkit/README.md`](../../toolkit/README.md)：路径、进程锁、平台窗口控制、开机自启
- 示例代码：`framework/swing/src/test/java/`（`Main.java`、`forms/MainScreen.java`、`viewmodel/BookEditorViewModel.kt`）

