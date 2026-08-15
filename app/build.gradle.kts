import com.google.firebase.appdistribution.gradle.firebaseAppDistribution
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.firebase.appdistribution)
}

// App Distribution 설정(App ID·테스터 이메일)은 gitignore된 local.properties 에서 읽는다.
// 공개 repo 에 프로젝트 식별자가 올라가지 않게 하고, 사람마다 값을 다르게 둘 수 있게 한다.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
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
        debug {
            // 데모 배포는 debug 변형으로 한다. debug 는 디버그 키스토어로 자동 서명돼
            // 별도 서명 설정 없이 바로 설치된다(release 는 서명 안 하면 설치 불가).
            // 업로드: ./gradlew assembleDebug appDistributionUploadDebug
            firebaseAppDistribution {
                appId = localProps.getProperty("firebase.appId") ?: ""
                artifactType = "APK"
                // 쉼표로 구분한 테스터 이메일. 이들에게 설치 링크 메일이 발송된다.
                testers = localProps.getProperty("firebase.testers") ?: ""
                // 그룹 별칭(쉼표 구분). 초대 링크로 등록한 팀원이 이 그룹에 들어가 릴리스를 본다.
                groups = localProps.getProperty("firebase.groups") ?: ""
                releaseNotes = "WayFinder 데모 빌드"
            }
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