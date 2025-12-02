import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt")
    alias(libs.plugins.google.gms.google.services)
    id("com.google.devtools.ksp")
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

        manifestPlaceholders["NAVER_MAP_CLIENT_ID"] = getNaverMapClientId()
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "USE_DUMMY_BIKE_DATA", "true")
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "USE_DUMMY_BIKE_DATA", "false")
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

    implementation("com.google.android.gms:play-services-location:21.0.1")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("com.google.guava:guava:33.0.0-android")
    implementation(libs.androidx.media3.exoplayer)

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

    implementation("com.google.android.gms:play-services-location:21.0.1")

    // 네이버 지도 SDK
    implementation("com.naver.maps:map-sdk:3.23.0")
    // Room db 설정
    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    ksp("androidx.room:room-compiler:$room_version")

    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation (files("libs/ffmpeg-kit-full-gpl-6.0-2.LTS.aar")) // [웹 사이트에서 aar 파일 다운로드 받은 후 안드로이드 프로젝트 libs 폴더에 추가]
    implementation ("com.arthenica:smart-exception-java:0.2.1")

    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-ui:1.2.0")
    implementation("androidx.media3:media3-common:1.2.0")

}

fun getNaverMapClientId(): String {
    val properties = Properties()
    val localPropsFile = rootProject.file("local.properties")

    if (localPropsFile.exists()) {
        FileInputStream(localPropsFile).use { properties.load(it) }
    }

    return properties.getProperty("NAVER_MAP_CLIENT_ID", "")
}
