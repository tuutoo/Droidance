// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

plugins {
    id("droidance.android.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}
val verifyNativeInputs = tasks.register<Exec>("verifyNativeInputs") {
    workingDir(rootDir)
    commandLine("python3", "scripts/verify_native.py")
}
tasks.named("preBuild") { dependsOn(verifyNativeInputs) }
android {
    namespace = "com.droidance.core.device"
    ndkVersion = providers.gradleProperty("droidanceNdkVersion").get()
    buildFeatures {
        aidl = true
    }
}
dependencies {
    implementation(project(":core:agent"))
    implementation(libs.rhino)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Hilt / Javax
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)

}
