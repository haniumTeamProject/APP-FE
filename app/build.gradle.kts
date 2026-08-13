plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.mcsmtp.wayfinder"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "org.mcsmtp.wayfinder"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
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