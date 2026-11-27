package com.github.knightwood.kmp.versioning

import com.github.knightwood.kmp.platform2.OS.*
import com.github.knightwood.kmp.platform2.currentSystemOS
import io.github.z4kn4fein.semver.Version
import io.github.z4kn4fein.semver.toVersion
import org.gradle.api.Project
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

fun Project.getAppVersion(fallback: String = "1.0.0"): Version {
    return runCatching { project.version as Version }.getOrDefault(fallback.toVersion())
}

fun Project.getAppVersionString(fallback: String = "1.0.0"): String {
    return runCatching { project.version.toString() }.getOrDefault(fallback)
}

private fun guessTargetFormatBasedOnCurrentOs() = when (currentSystemOS) {
    Linux -> TargetFormat.Deb
    MacOS -> TargetFormat.Dmg
    Windows -> TargetFormat.Msi
    else -> error("we are executing gradle in desktop :D")
}

fun Project.getAppVersionStringForPackaging(targetFormat: TargetFormat? = null): String {
    val v = getAppVersion()
    val simple = { v.run { "$major.$minor.$patch" } }
    val semantic = { v.toString() }
    val forRpm = { semantic().replace("-", "_") }
    return when (targetFormat ?: guessTargetFormatBasedOnCurrentOs()) {
        TargetFormat.Rpm -> forRpm()
        TargetFormat.Deb, TargetFormat.AppImage -> semantic()
        TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg, TargetFormat.Pkg -> simple()
    }
}
