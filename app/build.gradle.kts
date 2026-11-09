plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lighttodo.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lighttodo.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 详情页的拖拽排序用 ItemTouchHelper，必须配 RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Glance 需要 Compose 编译器；运行时的 compose runtime 由 glance 传递依赖带入
    implementation("androidx.compose.runtime:runtime:1.6.8")
    implementation("androidx.glance:glance-appwidget:1.1.0")
}
