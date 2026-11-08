// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

abstract class GenerateProjectNotices : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val noticeFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val destination = outputDirectory.get().dir("licenses/droidance").asFile
        destination.mkdirs()
        noticeFiles.files.forEach { it.copyTo(destination.resolve(it.name), overwrite = true) }
    }
}

val generateProjectNotices = tasks.register<GenerateProjectNotices>("generateProjectNotices") {
    noticeFiles.from(rootProject.files("LICENSE", "NOTICE"))
    outputDirectory.set(layout.buildDirectory.dir("generated/projectNoticeAssets"))
}

val generateBuiltinSkills = tasks.register<Sync>("generateBuiltinSkills") {
    from(rootProject.file("skills"))
    into(layout.buildDirectory.dir("generated/builtinSkills/skills"))
}

val updateRepository = providers.gradleProperty("droidanceUpdateRepository").getOrElse("")
require(updateRepository.isEmpty() || updateRepository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
    "droidanceUpdateRepository must be owner/repository"
}

val repositoryRegression = providers.gradleProperty("droidanceTestRunner").orNull ==
    "com.droidance.settings.SettingsRegressionTestRunner"


android {
    namespace = "com.droidance"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.droidance"
        minSdk = 28
        targetSdk = 35
        resourceConfigurations += listOf("en", "zh")
        buildConfigField("String", "UPDATE_REPOSITORY", "\"$updateRepository\"")
        versionCode = 13
        versionName = "0.2.1"

        testInstrumentationRunner = providers.gradleProperty("droidanceTestRunner")
            .getOrElse("androidx.test.runner.AndroidJUnitRunner")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isPseudoLocalesEnabled = true
            isMinifyEnabled = false
            applicationIdSuffix = if (repositoryRegression) ".repositorytest" else ".debug"
            manifestPlaceholders["repositoryBackgroundEnabled"] = !repositoryRegression
        }
    }

    lint {
        abortOnError = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    packaging {
        jniLibs {
            // 16 KB 页大小设备兼容性：.so 文件以未压缩方式内嵌，确保页对齐生效
            useLegacyPackaging = false
        }
        resources {
            // Retain each dependency's original license and notice when paths collide.
            merges += setOf(
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
            )
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
            )
        }
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.assets?.addStaticSourceDirectory(layout.buildDirectory.dir("generated/builtinSkills").get().asFile.absolutePath)
        variant.sources.assets?.addGeneratedSourceDirectory(generateProjectNotices, GenerateProjectNotices::outputDirectory)
    }
}

tasks.named("preBuild") { dependsOn(generateBuiltinSkills) }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:agent"))
    implementation(project(":core:device"))
    implementation(project(":core:knowledge"))
    implementation(project(":core:persistence"))
    // AndroidX 核心
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose UI
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.animation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt 依赖注入
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // HTTP transport
    implementation(libs.okhttp)

    // Room 数据库
    implementation(libs.room.runtime)

    // WorkManager 定时任务
    implementation(libs.work.manager)

    // 序列化
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.snakeyaml.engine)

    // 图片加载
    implementation(libs.coil.compose)

    // 协程
    implementation(libs.kotlinx.coroutines.android)

    // Markdown 渲染
    implementation(libs.markdown.renderer.m3)

    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":core:agent")))
    testImplementation(testFixtures(project(":core:model")))
    androidTestImplementation(testFixtures(project(":core:agent")))
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
