// ============================================================================
//  FakeLoc —— LSPosed 虚拟定位模块
//  本仓库只放“源代码”，所有编译期依赖（SDK / NDK / Gradle 缓存 / 签名）都在
//  外部工具链目录（默认 D:\Android\toolchain），通过 local.properties 与
//  环境变量注入，源码与工具链完全解耦。
// ============================================================================

pluginManagement {
    repositories {
        // 国内镜像优先，jitpack 放最后兜底（该网络环境下 jitpack 常卡住）
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://www.jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
        // Xposed API 已以本地 jar 形式内置（app/libs/xposed-api-82.jar），
        // 因此这里不再依赖 https://api.xposed.info/ ，构建可完全离线。
        maven { url = uri("https://www.jitpack.io") }
    }
}

rootProject.name = "FakeLoc"
include(":app")
