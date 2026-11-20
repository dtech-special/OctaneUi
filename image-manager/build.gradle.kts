plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "dev.octaneui.image"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    // OkHttp and serialization types appear in the public API (Bootstrapper, ImageMetadata), hence api and not implementation.
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    implementation(libs.commons.compress)
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
