import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ks = File(projectDir, "jefz.keystore")
if (!ks.exists()) ks.writeBytes(Base64.getMimeDecoder().decode(File(projectDir, "jefz.keystore.b64").readText()))

android {
    namespace = "com.jefz.keyboard"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.jefz.keyboard"
        minSdk = 24
        targetSdk = 34
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0"
    }
    signingConfigs {
        create("jefz") {
            storeFile = ks
            storePassword = "jefzkey123"
            keyAlias = "jefz"
            keyPassword = "jefzkey123"
            storeType = "pkcs12"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("jefz") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
