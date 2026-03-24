plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.terminal_heat_sink.bluetoothtokeyboardinput.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.security.crypto)
    implementation(libs.gson)
    implementation(libs.tink.android)
}
