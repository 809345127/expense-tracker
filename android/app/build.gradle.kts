import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// 同步服务的地址和 token **不进仓库**（这个仓库是公开的，而那台 VPS 同时在跑别的东西，
// IP 落进公开仓不合适）。这里读的是 local.properties —— 它在 .gitignore 里。
//
// 只是为了开发期省事：装到手机上之后在 app 的「同步设置」页里粘一次同样能用，
// 而且以后换服务器不用重新编译。两条路都留着。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Room 把每个版本的表结构导出成 JSON 存进仓库。
// ⚠️ 这不是可选项：以后加字段/改表要靠这些 JSON 写迁移（Room 不会像 SwiftData 那样自动轻量迁移），
// 而这个 app 的数据只在两台手机和 VPS 上，迁移写错就是真丢。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    // 生成的 DAO 用 Kotlin 而不是 Java（协程/Flow 的支持更干净）
    arg("room.generateKotlin", "true")
}

android {
    namespace = "com.shize.expensetracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shize.expensetracker"
        // minSdk 33 = Android 13。用户那台是 vivo / Android 16，所以门槛可以定高一点，
        // 定高的好处是**少写两处版本分支**：
        //   ① Material You 动态取色（跟系统壁纸配色）要 Android 12+
        //   ② 通知权限要单独申请（POST_NOTIFICATIONS）是 Android 13+ 的规矩，
        //      门槛压在 13 就不用写「13 以上要申请、以下不用」这种分叉——以后做预算提醒会用到
        // 真要装到更老的机器上，把这个数字调低 + 补回那两处分支就行。
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // 开发期的默认值，装到手机上可以在设置页里改
        buildConfigField("String", "DEFAULT_SYNC_URL", "\"${localProps.getProperty("sync.url", "")}\"")
        buildConfigField("String", "DEFAULT_SYNC_TOKEN", "\"${localProps.getProperty("sync.token", "")}\"")
    }

    signingConfigs {
        // 自己签一个长期有效的 keystore。⚠️ keystore 和密码**绝不进仓库**，见 android/.gitignore。
        // 安卓这边没有 iOS 那个「7 天就过期」的限制，签一次装上去就长期能用。
        create("selfsigned") {
            val ks = rootProject.file("keystore.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = localProps.getProperty("keystore.password")
                keyAlias = localProps.getProperty("keystore.alias", "expense")
                keyPassword = localProps.getProperty("keystore.password")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (rootProject.file("keystore.jks").exists()) {
                signingConfig = signingConfigs.getByName("selfsigned")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // ⚠️ 图标必须单独引 —— **material3 不带图标**（我第一版以为带、把这两个去掉了，
    // 结果 `androidx.compose.material.icons` 整个包找不到，编译直接失败）。
    // 版本由 BOM 管（现在锁在 1.7.8，是这两个库最后一个版本、不再更新，但 BOM 仍然管着它们）。
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)

    // 本地库。Room 对位 iOS 那边的 SwiftData
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // 后台同步。WorkManager 是安卓做这件事的标准答案：系统调度、
    // 断网自动等网络回来、进程被杀也不丢任务
    implementation(libs.work.runtime)

    // 桌面小组件。Glance = 用 Compose 写小组件，对位 iOS 的 WidgetKit
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp.logging)

    testImplementation(libs.junit)

    implementation(libs.datastore.preferences)
    // 指纹/人脸解锁，对位 iOS 那边的 Face ID 门
    implementation(libs.biometric)
}
