plugins {
    //`kotlin-dsl` 即 id("org.gradle.kotlin.dsl") 用于gradle kts支持
    `kotlin-dsl`
}

dependencies {
    //版本需与 kotlin-dsl 一致
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.20")
    implementation("com.vanniktech:gradle-maven-publish-plugin:0.34.0")
    val semverVersion = "2.0.0"
    implementation("io.github.z4kn4fein:semver:${semverVersion}")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.0.20")
    implementation("org.jetbrains.compose:compose-gradle-plugin:1.8.2")
}
