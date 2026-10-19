plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 正式版签名。
 *
 * ## 为什么不把密钥写进仓库
 *
 * 发布用密钥一旦进过 Git 历史就等于泄露，只能换新密钥重发。所以这里只从
 * **环境变量**读，读不到就退回 debug 签名 —— 退回时打出来的包**不能发布**，
 * 但可以装到自己手机上跑正式版（R8 已开启，性能行为与发布包一致）。
 *
 * 发布前设置这四个环境变量即可，构建脚本本身一行都不用改：
 *
 * ```
 * ASMRONE_KEYSTORE          密钥文件绝对路径（.jks / .keystore）
 * ASMRONE_KEYSTORE_PASSWORD 仓库口令
 * ASMRONE_KEY_ALIAS         密钥别名
 * ASMRONE_KEY_PASSWORD      密钥口令
 * ```
 */
val releaseKeystore = System.getenv("ASMRONE_KEYSTORE")?.takeIf { it.isNotBlank() }
val releaseStorePass = System.getenv("ASMRONE_KEYSTORE_PASSWORD")
val releaseAlias = System.getenv("ASMRONE_KEY_ALIAS")
val releaseKeyPass = System.getenv("ASMRONE_KEY_PASSWORD")
val hasReleaseSigning = releaseKeystore != null && releaseAlias != null

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

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = releaseStorePass
                keyAlias = releaseAlias
                keyPassword = releaseKeyPass
                // v1 给老系统，v2/v3 给新系统（安装校验更快）
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }

        release {
            /**
             * 代码压缩 / 内联 / 资源收缩 —— **正式版的核心**。
             *
             * 之前 release 是 `optimization { enable = false }`，等于正式包与 debug 包
             * 完全一样没被优化：类没内联、Compose 运行时检查都在、方法数更多，
             * 帧时间自然更差。
             *
             * 这里用 `isMinifyEnabled`（AGP 的成熟路径）而不是 AGP 9 新增的
             * `optimization.enable` —— 后者要求额外开启 `android.r8.gradual.support`
             * 实验开关，正式包不适合押在还在演进的管线上。
             */
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // 没有发布密钥时退回 debug 签名：保证「能装、能跑、R8 已开、可测性能」
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }

            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.kotlin_module",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
            )
        }
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
    // ⚠️ 不引入 material-icons-extended：本工程图标全部是自带的 Fluent 矢量图
    //    （res/drawable/ic_*_fluent.xml + painterResource），一行 Icons.* 都没用到。
    //    那个库在正式包里仍占可观的 dex 体积，属于纯浪费。

    // 封面加载
    implementation(libs.coil.compose)
    // 液态玻璃（Backdrop）：底部导航 / 顶栏药丸 / 迷你播放器的真实折射与色散
    // 与 comicApp 完全同一套实现与版本；Haze 已整体移除（它只能模糊，做不出折射）
    implementation(libs.backdrop)
    implementation(libs.kyant.shapes)

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
