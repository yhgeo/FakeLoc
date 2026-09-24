import java.util.Properties

// ============================================================================
//  app 模块：FakeLoc 主程序 + LSPosed 模块（同一个 APK 两种身份）
// ============================================================================

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 签名配置从 local.properties 读取；文件不存在时自动降级为 debug 签名，
// 保证"只有源码"的机器也能先跑通 assembleDebug。
// 注意：这里必须写 Properties() 而不是 java.util.Properties() ——
// Gradle Kotlin DSL 的工程作用域里 `java` 是 JavaPluginExtension，会把包名遮蔽掉。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(key: String): String? = (localProps.getProperty(key) ?: System.getenv(key))?.trim()?.takeIf { it.isNotEmpty() }

val storeFilePath = prop("fakeloc.storeFile")
val hasReleaseSigning = storeFilePath != null && file(storeFilePath).exists()

android {
    namespace = "com.mo.fakeloc"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mo.fakeloc"
        minSdk = 29          // LSPosed 1.9.2 支持范围；Android 10+
        targetSdk = 34       // Android 14
        versionCode = 10
        versionName = "1.4.2"

        // Xposed 模块标识（LSPosed 读取，用于在模块列表里显示）
        buildConfigField("String", "XPOSED_DESC", "\"LSPosed 虚拟定位模块\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(storeFilePath!!)
                storePassword = prop("fakeloc.storePassword")
                keyAlias = prop("fakeloc.keyAlias")
                keyPassword = prop("fakeloc.keyPassword")
                // Xposed 模块用 v1+v2 双签，兼容性最好
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false          // hook 大量用反射，暂不开混淆
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
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
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0", "META-INF/LGPL2.1",
            "META-INF/*.kotlin_module"
        )
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Xposed API：compileOnly，运行时由 LSPosed 注入，不打进 APK。
    // jar 已内置在 app/libs/，构建无需联网。
    compileOnly(files("libs/xposed-api-82.jar"))

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
