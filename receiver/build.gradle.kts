plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "ru.screenmirror.receiver"
    compileSdk = 35
    defaultConfig { applicationId = "ru.screenmirror.receiver"; minSdk = 26; targetSdk = 35; versionCode = 5; versionName = "0.5.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
}
