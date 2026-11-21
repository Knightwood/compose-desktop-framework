# toolkit-mac — macOS 平台实现

> `toolkit:mac` 提供 macOS 下的 `AppBasePathProvider` 实现（`MacosAppPathProvider`）。
> 它按 macOS 惯例把用户配置放进 `~/Library/Application Support/<AppName>`，
> 并从 Compose 打包注入的 system property 推导 `.app` 内部路径。

---

## 目录

- [1. 模块内容](#1-模块内容)
- [2. 环境要求](#2-环境要求)
- [3. 发布到本地 Maven 仓库](#3-发布到本地-maven-仓库)
- [4. 引入依赖](#4-引入依赖)
- [5. 最简示例](#5-最简示例)
- [6. `.app` 打包目录布局](#6-app-打包目录布局)
- [7. 路径计算细节](#7-路径计算细节)
- [8. Koin 注册表](#8-koin-注册表)
- [9. 代码签名与沙盒（重要）](#9-代码签名与沙盒重要)
- [10. 注意细节](#10-注意细节)
- [11. API 参考](#11-api-参考)
- [12. 常见问题](#12-常见问题)

---

## 1. 模块内容

```
toolkit/mac
└── src/main/kotlin/androidx/jvm/system
    ├── core/MacosAppPathProvider.kt      // @AutoService(AppBasePathProvider)
    ├── di/MacPlatformServiceProvider.kt  // @AutoService(ModuleProvider)
    └── ui/                               // 预留（当前为空）
```

与 Linux 模块一样，它**没有** `PlatformWindowHelper` 实现。

---

## 2. 环境要求

| 项目 | 要求 |
| --- | --- |
| JDK | 17+ |
| Kotlin | 2.2.10（编译期） |
| 运行系统 | macOS 11+（`currentOS == SystemOs.MacOS`） |
| 架构 | Intel（x64）与 Apple Silicon（arm64）均支持 |
| 打包方式 | Compose 的 `.dmg` / `.pkg`，生成的 `<App>.app` |
| 必需 system property | `compose.application.resources.dir` 或 `skiko.library.path` |

> **关键约束**：直接 `java -jar app.jar` 运行时，这两个属性都不存在，
> `MacosAppPathProvider` 会抛 `IllegalStateException("Could not find app path")`。
> 必须通过打包好的 `.app` 启动，或手动 `-D` 注入。

---

## 3. 发布到本地 Maven 仓库

| 项 | 值 |
| --- | --- |
| groupId | `com.github.knightwood.desktop` |
| artifactId | **`toolkit-mac`** |
| version | `1.3.0` |

```bash
./gradlew :toolkit:mac:publishToMavenLocal
```

产物：

```
~/.m2/repository/com/github/knightwood/desktop/toolkit-mac/1.3.0/
```

---

## 4. 引入依赖

```kotlin
dependencies {
    implementation("com.github.knightwood.desktop:toolkit-spi:1.3.0")
    runtimeOnly("com.github.knightwood.desktop:toolkit-mac:1.3.0")
}
```

Maven：

```xml
<dependency>
  <groupId>com.github.knightwood.desktop</groupId>
  <artifactId>toolkit-mac</artifactId>
  <version>1.3.0</version>
  <scope>runtime</scope>
</dependency>
```

Compose 的打包配置里注意把「当前架构」的 skiko 一起打进去：

```kotlin
compose.desktop {
    application {
        mainClass = "com.example.Main"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Pkg)
            packageName = "My App"
            packageVersion = "1.0.0"
            macOS {
                bundleID = "com.example.myapp"
                // iconFile.set(project.file("icons/icon.icns"))
                // dockName = "My App"
            }
        }
    }
}
```

---

## 5. 最简示例

```kotlin
import androidx.jvm.system.core.AppPathProvider
import androidx.jvm.system.core.DesktopToolkitBooster
import androidx.jvm.system.core.keepDirExist
import androidx.jvm.system.di.InstanceContext
import androidx.jvm.system.di.startUp

fun main() {
    DesktopToolkitBooster.provideApplicationInfo { appName = "My App" }
    InstanceContext.startUp()

    val paths = AppPathProvider.provider
    println("installPath  = ${paths.installPath}")
    println("jarPath      = ${paths.installedJarPath}")
    println("configDir    = ${paths.configDirPath}")

    // 注意：macOS 的实现会自动创建该目录
    val cfg = paths.configDirPath
    cfg.resolve("settings.json").toFile().writeText("""{"theme":"dark"}""")
}
```

`~/Library/Application Support/My App/` 会在第一次访问 `configDirPath` 时**自动创建**
（源码里调用了 `Files.createDirectories`）。

---

## 6. `.app` 打包目录布局

源码注释：

```
MyApp.app/
├── Info.plist
├── MacOS/            ← 可执行 launcher
├── PkgInfo
├── Resources/        ← 图标、资源
├── _CodeSignature/   ← 签名
├── app/              ← jar 目录（installedJarPath）
└── runtime/          ← 内置 JDK
```

用户数据（Apple 规范）：

```
~/Library/Application Support/My App/     ← configDirPath（自动创建）
~/Library/Logs/My App/                    ← 可选：日志
~/Library/Caches/My App/                  ← 可选：缓存
```

> **不要**把用户配置写进 `.app` 内部：一旦签名被修改，Gatekeeper 会拒绝启动。
> `internalConfigDirPath`（`MyApp.app/.conf`）只适合放**只读**的随包资源。

---

## 7. 路径计算细节

```kotlin
@AutoService(AppBasePathProvider::class)
class MacosAppPathProvider : AppBasePathProvider {

    override val userHome: Path = Paths.get(SystemProperty["user.home"]!!).toOkioPath()

    override val installPath: Path = getAppJarPath().noOptionParent.noOptionParent.normalized()
    override val installedJarPath: Path = getAppJarPath()
    override val installedExePath: Path = getAppExePath()

    override val configDirPath: Path = getUserPath()
    override val internalConfigDirPath: Path = installPath.resolve(".conf")

    private fun getAppJarPath(): Path {
        SystemProperty.get("compose.application.resources.dir")?.let { return it.toPath() }
        SystemProperty.get("skiko.library.path")?.let { return it.toPath() }
        throw IllegalStateException("Could not find app path")
    }

    private fun getAppExePath(): Path =
        getAppJarPath().noOptionParent
            .resolve("runtime").resolve("Contents").resolve("Home").resolve("lib")
            .normalized()

    private fun getUserPath(): Path {
        val p = userHome.resolve("Library").resolve("Application Support")
            .resolve(AppInfoProvider.get().appName)
        if (Files.notExists(p.toNioPath())) Files.createDirectories(p.toNioPath())
        return p
    }
}
```

| 字段 | 计算方式 | 示例 |
| --- | --- | --- |
| `installedJarPath` | `compose.application.resources.dir` 或 `skiko.library.path` | `…/MyApp.app/app` |
| `installPath` | jar 目录上两级 | `…/MyApp.app` |
| `installedExePath` | `<jarDir>/../runtime/Contents/Home/lib` | `…/MyApp.app/runtime/Contents/Home/lib` |
| `configDirPath` | `~/Library/Application Support/<appName>`（自动创建） | `~/Library/Application Support/My App` |
| `internalConfigDirPath` | `installPath/.conf` | `…/MyApp.app/.conf` |

三个要点：

1. **`installPath` 是「上两级」**：因为 jar 在 `<App>.app/app`，所以上两级正好是 `.app` 根。
   如果你的打包插件把 jar 放在 `Contents/app`，`installPath` 会变成 `Contents`，
   路径整体会偏移一层 —— 这时请自定义实现。
2. **`installedExePath` 指向内置 JDK 的 `lib`**，不是 launcher 目录。名字有历史包袱，
   别拿它去拼 `MyApp` 可执行文件路径。
3. **`configDirPath` 会创建目录**：这是三个平台里唯一一个「读属性就落盘」的实现，
   在 `--help` 之类纯 CLI 场景里也会产生目录。

---

## 8. Koin 注册表

```kotlin
@AutoService(ModuleProvider::class)
internal open class MacPlatformServiceProvider : ModuleProvider {
    override fun provide(): Module = module {
        single<AppBasePathProvider>(named(SystemOs.MacOS)) { MacosAppPathProvider() }
    }
}
```

| 接口 | 限定符 | 实现 |
| --- | --- | --- |
| `AppBasePathProvider` | `named(SystemOs.MacOS)` | `MacosAppPathProvider` |
| `PlatformWindowHelper` | — | **无** |

---

## 9. 代码签名与沙盒（重要）

| 场景 | 影响 |
| --- | --- |
| 未签名 / 签名失效的 `.app` | Gatekeeper 拦截，双击无反应或提示「已损坏」 |
| 沙盒（App Sandbox）开启 | 只能写容器目录（`~/Library/Containers/<bundleID>/…`）。此时 `~/Library/Application Support/<appName>` 实际会被重定向进容器 |
| 修改 `.app` 内文件 | 破坏签名，需重新签名（`codesign --force --deep --sign - MyApp.app`） |
| Apple Silicon | 未签名二进制需要 `xattr -dr com.apple.quarantine MyApp.app` 才能运行 |

因此：

- `internalConfigDirPath`（`.app` 内部）**只读**；
- 所有可变数据放 `configDirPath`；
- 正式分发前务必签名 + 公证（notarization）。

---

## 10. 注意细节

1. **必须由 `.app` 启动**
   `compose.application.resources.dir` / `skiko.library.path` 由 Compose 的打包启动脚本注入。
   IDE 调试时会被判为 Debug（走 `DebugAppPathProvider`），所以看不出问题；
   一旦 `java -jar` 就会抛 `IllegalStateException`。

2. **`AppInfoProvider` 已废弃但仍被使用**
   `configDirPath` 里调的是 `AppInfoProvider.get()`（内部转发 `DesktopToolkitBooster`）。
   新代码用 `DesktopToolkitBooster.provideApplicationInfo { appName = ... }`。

3. **`appName` 直接作为目录名**
   `~/Library/Application Support/My App`（允许空格，macOS 上很常见）。
   若你更希望用 bundleID 风格，自己覆写实现。

4. **`configDirPath` 有副作用（创建目录）**
   在只跑一次命令行的工具里也会创建。介意的话自己判断或覆写。

5. **`installPath` 依赖打包布局**
   `app/` 的位置决定一切。换打包工具后请重新验证 4 个路径。

6. **没有 `PlatformWindowHelper`**
   想「把已有窗口提到前台」在 macOS 上要借助 AppleScript / Accessibility API，
   当前模块未实现。

7. **ARM64 与 X64 的 skiko 不能混**
   `compose.desktop.currentOs` 只解析当前构建机的 skiko；交叉打包需要显式指定。

8. **`currentOsAndArch` 支持 mac**
   是四个组合里唯一同时支持 `macos-x64` 与 `macos-arm64` 的平台。

---

## 11. API 参考

| 符号 | 类型 | 说明 |
| --- | --- | --- |
| `MacosAppPathProvider` | `class : AppBasePathProvider` | macOS 路径实现 |
| `userHome` | `okio.Path` | `/Users/<user>` |
| `installPath` | `okio.Path` | `<App>.app`（jar 目录上两级，`normalized()`） |
| `installedJarPath` | `okio.Path` | `compose.application.resources.dir` 或 `skiko.library.path` |
| `installedExePath` | `okio.Path` | `<jarDir>/../runtime/Contents/Home/lib` |
| `configDirPath` | `okio.Path` | `~/Library/Application Support/<appName>`（自动创建） |
| `internalConfigDirPath` | `okio.Path` | `<.app>/.conf` |
| `MacPlatformServiceProvider` | `ModuleProvider` | 注册 `AppBasePathProvider` |

---

## 12. 常见问题

**Q1：`IllegalStateException: Could not find app path`**

不是从 `.app` 启动的。用 `open MyApp.app`，或手动注入：

```bash
java -Dcompose.application.resources.dir=/path/to/app -jar app.jar
```

**Q2：`configDirPath` 里的目录名带空格，会不会出问题？**

`okio.Path` / `java.nio.file` 处理空格没问题。但如果你拼接 shell 命令，记得引号包裹。

**Q3：升级应用后配置丢了。**

配置在 `~/Library/Application Support/<appName>`，只有当 `appName` 变了才会「丢」。
改名会切目录，请保持 `appName` 稳定（或自己做一次迁移）。

**Q4：`installedExePath` 名字叫 exe，但拿到的是 JDK 的 lib 目录？**

是的，语义上是「运行时所在目录」。真要找 launcher：

```kotlin
paths.installPath.resolve("MacOS").resolve("MyApp")
```

**Q5：沙盒下写 `configDirPath` 报权限错误？**

开启 App Sandbox 后，`~/Library/Application Support` 会被重定向到容器内。
若仍报错，说明你需要 `com.apple.security.files.user-selected.read-write`
或把数据放进容器目录（`System.getProperty("user.home")` 已指向容器）。

**Q6：能不能在 Windows 上构建 macOS 包？**

Compose 支持交叉打包 `.dmg`，但 skiko native 必须匹配目标架构，
且签名/公证必须在 macOS 上完成。`toolkit-mac` 本身是纯 JVM 代码，不含 native。
