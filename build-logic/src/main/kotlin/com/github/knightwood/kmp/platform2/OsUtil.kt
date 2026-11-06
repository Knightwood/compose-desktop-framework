package com.github.knightwood.kmp.platform2

import com.github.knightwood.kmp.platform2.OS.*

enum class OS(val id: String) {
    Linux("linux"),
    Windows("windows"),
    MacOS("macos");
    companion object{
        fun fromString(platformName: String): OS? {
            return when (platformName.lowercase()) {
                "windows" -> Windows
                "linux" -> Linux
                "mac" -> Linux
                else -> null
            }
        }

        fun fromExecutableFileExtension(fileExtension: String): OS? {
            return when (fileExtension.lowercase()) {
                "exe", "msi" -> Windows
                "deb", "rpm" -> Linux
                "dmg", "pkg" -> MacOS
                else -> null
            }
        }
    }
}

enum class Arch(val id: String) {
    X64("x64"),
    Arm64("arm64"),
    X32("x32");

    companion object {
        val DefinedArchStrings = mapOf(
            X64 to listOf(
                "amd64", "x64", "x86_64"
            ),
            Arm64 to listOf(
                "arm64", "aarch64"
            ),
            X32 to listOf(
                "x86",
            ),
        )

        fun fromString(archName: String):Arch? {
            val a = archName.lowercase()
            return Arch.DefinedArchStrings.entries.firstOrNull {
                a in it.value
            }?.key
        }
        fun getCurrentArchFromJVMProperty(): Arch {
            val osString = System.getProperty("os.arch").lowercase()
            return requireNotNull(Arch.fromString(osString)) {
                "this arch is not recognized: $osString"
            }
        }

    }
}


data class Target(val os: OS, val arch: Arch) {
    val id: String
        get() = "${os.id}-${arch.id}"
}

val currentTarget by lazy {
    Target(currentSystemOS, currentArch)
}

val currentArch by lazy {
    val osArch = System.getProperty("os.arch")
    when (osArch) {
        "x86_64", "amd64" -> Arch.X64
        "aarch64" -> Arch.Arm64
        else -> error("Unsupported OS arch: $osArch")
    }
}

val currentSystemOS: OS by lazy {
    val os = System.getProperty("os.name")
    when {
        os.equals("Mac OS X", ignoreCase = true) -> MacOS
        os.contains("mac") -> MacOS
        os.contains("darwin") -> MacOS
        os.startsWith("Win", ignoreCase = true) -> Windows
        os.startsWith("Linux", ignoreCase = true) -> Linux
        else -> error("Unknown OS name: $os")
    }
}

