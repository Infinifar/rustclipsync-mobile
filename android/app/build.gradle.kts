plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val disposableIntegration = providers.gradleProperty("disposableIntegration").orNull == "true"
val revision = providers.environmentVariable("CI_COMMIT_SHORT_SHA").orElse("local").get()
require(revision == "local" || revision.matches(Regex("[0-9a-f]{7,40}")))
android {
    namespace = "io.github.infinifar.rustclipsync"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "io.github.infinifar.rustclipsync" + if (disposableIntegration) ".disposable" else ""
        minSdk = 31
        targetSdk = 36
        versionCode = 8
        versionName = if (disposableIntegration) "0.2.1-disposable" else "0.2.1"
        buildConfigField("String", "BUILD_REVISION", "\"$revision\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildFeatures { compose = true; buildConfig = true; aidl = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets["main"].java.srcDir("../../build/bindings")
    if (disposableIntegration) sourceSets["androidTest"].java.srcDir("src/disposableTest/java")
    packaging { jniLibs.useLegacyPackaging = false }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation("com.github.d4rken-org.porter-api:sdk:0.9.0")
    implementation("com.github.d4rken-org.porter-api:shizuku-compat:0.9.0")
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
