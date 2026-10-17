plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.asmrone.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.asmrone.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // 封面加载
    implementation(libs.coil.compose)
    // 液态玻璃：底部导航 / 顶栏药丸 / 迷你播放器的真实背景模糊
    implementation(libs.haze)

    // 详情页里播放「视频」类型的轨道（文件树中的 .mp4）
    //
    // ⚠️ 这是本工程**唯一**一处为功能引入的新依赖，打破了原先"零新增依赖"的约定
    //    （见 AudioController 的类注释）。理由：视频轨道单个可达 1.8GB，
    //    必须流式播放，而 Media3 是这件事的事实标准。
    //    音频播放**仍然**用平台自带 MediaPlayer，不受影响。
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // ── 单元测试 ──────────────────────────────────────────────
    // 这些测试只跑纯 JVM 逻辑（收藏缓存的 JSON 编解码、默认收藏夹选取规则），
    // 不需要设备。org.json 必须用**真实实现**：android.jar 里的同名类
    // 只是抛异常的桩，用它测出来的结果没有意义。
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
