plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
fun escapedBuildConfig(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "")

val configuredGitHubClientId = providers.gradleProperty("githubAppClientId")
    .orElse(providers.environmentVariable("GITHUB_APP_CLIENT_ID"))
    .getOrElse("")
val configuredRuntimeSigningKeys = providers.gradleProperty("runtimeSigningKeys")
    .orElse(providers.environmentVariable("FM_RUNTIME_SIGNING_KEYS"))
    // Deliberately empty by default: FM must own and protect the private signing key.
    // Never trust another project runtime key for code that will execute CI jobs.
    .getOrElse("")
val configuredRuntimeManifestUrl = providers.gradleProperty("runtimeManifestUrl")
    .orElse(providers.environmentVariable("FM_RUNTIME_MANIFEST_URL"))
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
        buildConfigField("String", "GITHUB_APP_CLIENT_ID", "\"${escapedBuildConfig(configuredGitHubClientId)}\"")
        buildConfigField("String", "FM_RUNTIME_SIGNING_KEYS", "\"${escapedBuildConfig(configuredRuntimeSigningKeys)}\"")
        buildConfigField("String", "FM_RUNTIME_MANIFEST_URL", "\"${escapedBuildConfig(configuredRuntimeManifestUrl)}\"")
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // TAR.GZ decoding; extraction policy remains FM-owned and fail-closed.
    implementation("org.apache.commons:commons-compress:1.28.0")
    testImplementation("junit:junit:4.13.2")
}
