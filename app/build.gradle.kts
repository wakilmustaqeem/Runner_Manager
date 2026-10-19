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
    // TAR.GZ support for the upstream runtime bundle format (Apache-2.0).
    implementation("org.apache.commons:commons-compress:1.28.0")
    // Required by local JVM unit tests; this does not add an Android test runner.
    testImplementation("junit:junit:4.13.2")
}
