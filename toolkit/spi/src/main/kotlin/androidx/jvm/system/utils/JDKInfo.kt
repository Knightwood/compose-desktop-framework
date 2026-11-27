package androidx.jvm.system.utils

data class JDKInfo(
    val vendor: String,
    val version: String,
    val vmName: String,
    val vmVersion: String,
    val home: String,
    val arch: String
)

fun jdkInfo(): JDKInfo {
    val info =JDKInfo(
        vendor = System.getProperty("java.vendor"),
        version = System.getProperty("java.version"),
        vmName = System.getProperty("java.vm.name"),
        vmVersion = System.getProperty("java.vm.version"),
        home = System.getProperty("java.home"),
        arch = System.getProperty("os.arch")
    )
    return info
}
