plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val configuredGitHubClientId = providers.gradleProperty("githubAppClientId")
    .orElse(providers.environmentVariable("GITHUB_APP_CLIENT_ID"))
    .getOrElse("")

android {
    buildFeatures { buildConfig = true }
    namespace = "com.fm.runnermanager"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.fm.runnermanager"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GITHUB_APP_CLIENT_ID", "\"${configuredGitHubClientId.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
