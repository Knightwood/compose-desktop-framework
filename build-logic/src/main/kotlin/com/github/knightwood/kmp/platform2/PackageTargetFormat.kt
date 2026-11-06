/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package com.github.knightwood.kmp.platform2;

import com.github.knightwood.kmp.platform2.PackageTargetFormat.*
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

enum class PackageTargetFormat(
    val id: String,
    val targetOS: OS
) {
    AppImage("app-image", currentSystemOS),
    Deb("deb", OS.Linux),
    Rpm("rpm", OS.Linux),
    Dmg("dmg", OS.MacOS),
    Pkg("pkg", OS.MacOS),
    Exe("exe", OS.Windows),
    Msi("msi", OS.Windows);

    val isCompatibleWithCurrentOS: Boolean by lazy { isCompatibleWith(currentSystemOS) }

    internal fun isCompatibleWith(os: OS): Boolean = os == targetOS

    val outputDirName: String
        get() = if (this == AppImage) "app" else id

    val fileExt: String
        get() {
            check(this != AppImage) { "$this cannot have a file extension" }
            return ".$id"
        }
}

fun TargetFormat.asPackageTargetFormat(): PackageTargetFormat {
    return when (this) {
        TargetFormat.AppImage -> AppImage
        TargetFormat.Deb -> Deb
        TargetFormat.Rpm -> Rpm
        TargetFormat.Dmg -> Dmg
        TargetFormat.Pkg -> Pkg
        TargetFormat.Exe -> Exe
        TargetFormat.Msi -> Msi
    }
}

fun PackageTargetFormat.asTargetFormat(): TargetFormat {
    return when (this) {
        AppImage -> TargetFormat.AppImage
        Deb -> TargetFormat.Deb
        Rpm -> TargetFormat.Rpm
        Dmg -> TargetFormat.Dmg
        Pkg -> TargetFormat.Pkg
        Exe -> TargetFormat.Exe
        Msi -> TargetFormat.Msi
    }
}

