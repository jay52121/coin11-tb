plugins {
    id("com.android.application")
}

android {
    namespace = "com.coin11.taojinbi"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.coin11.taojinbi"
        minSdk = 30
        targetSdk = 36
        versionCode = 6
        versionName = "0.3.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}
