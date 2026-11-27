
enum class PlatformName {
    MacOS, Windows, Linux,
}

val currentPlatform: PlatformName by lazy {
    val os = System.getProperty("os.name")
    when {
        os.equals("Mac OS X", ignoreCase = true)
                || os.contains("mac")
                || os.contains("darwin") -> PlatformName.MacOS

        os.startsWith("Win", ignoreCase = true) -> PlatformName.Windows
        os.startsWith("Linux", ignoreCase = true) -> PlatformName.Linux
        else -> error("Unknown OS name: $os")
    }
}

