plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

android {
    namespace = "com.codex.indown"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.codex.indown"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.3.0"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.register("exportReleaseApk") {
    group = "build"
    description = "릴리즈 APK를 apks/InstaDown-release-v0.3.0.apk 로 내보냅니다."
    dependsOn("assembleRelease")

    doLast {
        val sourceApk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        check(sourceApk.exists()) { "APK를 찾을 수 없습니다: ${sourceApk.absolutePath}" }

        val outputDir = rootProject.layout.projectDirectory.dir("apks").asFile
        outputDir.mkdirs()

        val versionName = android.defaultConfig.versionName ?: "0.3.0"
        val targetApk = outputDir.resolve("InstaDown-release-v$versionName.apk")
        sourceApk.copyTo(targetApk, overwrite = true)

        val publicReleaseDir = rootProject.layout.projectDirectory.dir("release").asFile
        publicReleaseDir.mkdirs()
        sourceApk.copyTo(publicReleaseDir.resolve("InstaDown-latest.apk"), overwrite = true)

        val apkNamePattern = Regex("""(InstaDown|InDown)-release-v.*\.apk""")
        outputDir
            .listFiles { file -> file.isFile && apkNamePattern.matches(file.name) }
            ?.sortedByDescending { file -> file.lastModified() }
            ?.drop(3)
            ?.forEach { oldApk ->
                if (!oldApk.delete()) {
                    logger.warn("이전 APK를 삭제하지 못했습니다: ${oldApk.absolutePath}")
                }
            }

        logger.lifecycle("${targetApk.relativeTo(rootProject.projectDir)} 내보내기 완료")
    }
}

dependencies {
    val composeVersion = "1.7.5"
    val lifecycleVersion = "2.8.7"

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended:$composeVersion")
    implementation("androidx.compose.ui:ui:$composeVersion")
    implementation("androidx.compose.ui:ui-tooling-preview:$composeVersion")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleVersion")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling:$composeVersion")
}
