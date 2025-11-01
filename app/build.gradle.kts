import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt")
    alias(libs.plugins.google.gms.google.services)
}

android {
    namespace = "com.example.capstone"
    compileSdk = 36
    viewBinding.isEnabled=true  // 뷰바인딩

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.example.capstone"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "TMAP_API_KEY", "\"${getTmapApiKey()}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)

    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("com.google.guava:guava:33.0.0-android")

    val camerax_version = "1.5.0"
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // 이거 2개 추가
    implementation("androidx.core:core-splashscreen:1.0.1")  // 앱 시작 화면
    implementation("androidx.preference:preference-ktx:1.2.1")  // 안드로이드 설정창


    // VideoCapture
    implementation("androidx.camera:camera-video:${camerax_version}")

    implementation("androidx.camera:camera-core:${camerax_version}")
    implementation("androidx.camera:camera-camera2:${camerax_version}")
    implementation("androidx.camera:camera-lifecycle:${camerax_version}")
    implementation("androidx.camera:camera-view:${camerax_version}")

    implementation("com.github.bumptech.glide:glide:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")

    // Tmap API
    implementation(files("libs/tmap-sdk-3.0.aar"))
    implementation(files("libs/vsm-tmap-sdk-v2-android-1.7.45.aar"))
}

// Tmap 앱키 안전하게 읽기
fun getTmapApiKey(): String {
    val properties = Properties()
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        FileInputStream(localPropsFile).use { properties.load(it) }
    }
    return properties.getProperty("TMAP_API_KEY", "")
}