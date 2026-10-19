import java.util.Properties

plugins {
    id("com.android.application")
}

// 버전은 PC 버전과 같이 booking_assistant/__init__.py 하나에서 정한다. 1.2.3 → versionCode 10203.
val appVersion: String = Regex("__version__\\s*=\\s*\"([^\"]+)\"")
    .find(rootProject.file("../booking_assistant/__init__.py").readText())!!.groupValues[1]
val appVersionCode: Int = appVersion.split(".").map { it.toInt() }
    .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

// 서명 키는 저장소 밖(사용자 폴더의 .booking_assistant)에 둔다.
// android-signing.properties에 키 파일 이름과 비밀번호가 있다. 업데이트는 같은 키로 서명한 APK만 설치된다.
val signingDir = File(System.getProperty("user.home"), ".booking_assistant")
val signing = Properties().apply {
    val file = File(signingDir, "android-signing.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "dev.bookingassistant.nol"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.bookingassistant.nol"
        minSdk = 30
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        if (signing.isNotEmpty()) {
            create("release") {
                storeFile = File(signingDir, signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
