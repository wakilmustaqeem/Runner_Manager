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
    // Public ECDSA key from the inspected DroidRunner source; it authenticates runtime manifests, not GitHub accounts.
    .getOrElse("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEAxTe0bAgmrhdoHQnr3PfCEp7UtKOk74dbfTvjJXkuCtbROaczoOQioVHt3bjAb/1ntuKD96n3LKvB4gLbE4c/w==")
val configuredRuntimeManifestUrl = providers.gradleProperty("runtimeManifestUrl")
    .orElse(providers.environmentVariable("FM_RUNTIME_MANIFEST_URL"))
    .getOrElse("https://github.com/m96-chan/DroidRunner/releases/download/runtime-0.3.0/runtime-manifest.json")

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

dependencies { testImplementation("junit:junit:4.13.2") }
