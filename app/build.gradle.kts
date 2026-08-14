plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.mcsmtp.wayfinder"
    // AGP 8.13 은 API 36 까지 지원한다. 37 은 AGP 9 전용이라 여기서 못 쓴다.
    compileSdk = 36

    defaultConfig {
        applicationId = "org.mcsmtp.wayfinder"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // AGP 9 의 optimization {} 블록 대신 구버전 DSL 을 쓴다.
            isMinifyEnabled = false
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
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.fragment.ktx)

    // REST + WebSocket
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    // JSON 파싱 (assets 목 데이터 · 서버 응답)
    implementation(libs.gson)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}