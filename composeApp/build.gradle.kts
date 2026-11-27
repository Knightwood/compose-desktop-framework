import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.dsl.TargetFormat.*
import org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
import org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg
import org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe
import org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi
import org.jetbrains.compose.desktop.application.dsl.TargetFormat.Pkg
import org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension
import java.util.*
import com.github.knightwood.kmp.versioning.*
import com.github.knightwood.kmp.platform2.*
import io.github.z4kn4fein.semver.toVersion

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    id("empty")
}

version = "1.0.0".toVersion()

/**
 * 美化的应用名称-这里使用中文，nsis打包会闪退
 */
fun Project.getPrettifiedAppName(): String {
    return "桌面示例"
}

/**
 * 这个不会显示出来，但是很重要 exe的名称，安装路径名称。 不能有空格
 */
fun Project.getAppName(): String {
    return "DesktopExample"
}

fun getVendor() = "宇宙和平公司"

fun Project.getAppPackageFile(test: Boolean): String {
    if (test)
        return "build/compose/binaries/main/app/${getAppName()}"
    else
        return "build/compose/binaries/main-release/app/${getAppName()}"
}

fun Project.getCustomIcon(): File {
    return when (currentSystemOS) {
        OS.Linux -> project.file("resources/icons/icon.png")
        OS.Windows -> project.file("resources/icons/icon.ico")
        OS.MacOS -> project.file("resources/icons/icon.icns")
        else-> throw IllegalStateException("Unsupported platform: ${currentSystemOS.name}")
    }
}


kotlin {
    jvm("desktop")

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(libs.okio)
            implementation(libs.kotlin.coroutines.core)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.compose.icon.core)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.kotlin.serialization)
            //datastore
            implementation(libs.androidx.datastore)
            implementation(libs.androidx.datastore.preferences)

            // SLF4J
            implementation("org.slf4j:slf4j-api:2.0.15")
            implementation("com.github.knightwood:slf4j-api-kotlin:0.0.7")
            implementation("ch.qos.logback:logback-classic:1.5.12")
            //spi
            implementation(libs.autoService.annoations)

            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            val semverVersion = "2.0.0"
            implementation("io.github.z4kn4fein:semver:${semverVersion}")
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs){
                exclude("org.jetbrains.compose.material")
            }
            implementation(libs.core.bundle)
            implementation(libs.kotlin.coroutines.swing)
            implementation(project(":framework:common"))
            implementation(project(":framework:compose"))
            implementation(project(":compose-tray"))
            implementation(project(":toolkit:spi"))
            when (currentSystemOS) {
                OS.Linux -> {
                    implementation(project(":toolkit:linux"))
                }
                OS.Windows -> {
                    implementation(project(":toolkit:win"))
                }
                else -> {
                    implementation(project(":toolkit:mac"))
                }
            }
//            implementation("com.dorkbox:SystemTray:4.4")
        }
    }
}

//kmp还不能在上面添加ksp的依赖，上面看起来也能用implementation这样的函数，
//实际上只是域平时我们使用的implementation函数名字相同，其实现和作用域是不同的。
dependencies{
    ksp(libs.autoService.ksp) //等同于 add("ksp",libs.autoService.ksp)
}

compose.resources {
    publicResClass = true
    packageOfResClass = "me.i18n.resources"
    generateResClass = auto
}



compose.desktop {
    application {
//        jvmArgs += listOf("-Dfile.encoding=GBK")
//        jvmArgs -= listOf("-Dfile.encoding=UTF-8")

        mainClass = "com.github.knightwood.example.MainKt"
//        javaHome = System.getenv("JAVA_HOME")
        buildTypes.release {
            proguard {
                version.set("7.5.0")
                isEnabled = false  // false to disable proguard
//                optimize = true
//                obfuscate = true //混淆。目前，room会因为这个而报错
            }
        }
        nativeDistributions {
            modules("java.instrument", "jdk.unsupported", "java.naming")
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = getAppName()//"com.github.knightwood.example"
            packageVersion = getAppVersionStringForPackaging()
            val menuGroupName = getPrettifiedAppName()
            windows {
                exePackageVersion = getAppVersionStringForPackaging(Exe)
                msiPackageVersion = getAppVersionStringForPackaging(Msi)
                // 一个唯一的 ID，当更新的版本比安装的版本更新时，它使用户能够通过安装程序更新应用程序。对于单个应用程序，该值必须保持不变；
                upgradeUuid = properties["INSTALLER.WINDOWS.UPGRADE_UUID"]?.toString()
                iconFile = getCustomIcon()
                //true：安装到用户目录。如果为false，则安装到program目录，但会导致无法使用单进程模式。
                perUserInstall = true
                console = false
                dirChooser = true
                shortcut = true
                menuGroup = menuGroupName
                menu = true
            }
            linux {
                debPackageVersion = getAppVersionStringForPackaging(Deb)
                rpmPackageVersion = getAppVersionStringForPackaging(Rpm)
                appCategory = "Network"
                iconFile = getCustomIcon()
                menuGroup = menuGroupName
                shortcut = true
            }
            macOS {
                pkgPackageVersion = getAppVersionStringForPackaging(Pkg)
                dmgPackageVersion = getAppVersionStringForPackaging(Dmg)
                iconFile = getCustomIcon()
            }
        }
    }
}
