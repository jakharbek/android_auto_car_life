import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing: path to a .properties file (storeFile, storePassword, keyAlias, keyPassword) in MONZA_SIGNING_PROPERTIES.
// storeFile is relative to that file. Without it the release build is unsigned.
val signingFile: File? = System.getenv("MONZA_SIGNING_PROPERTIES")?.let(::File)?.takeIf { it.isFile }
val signingProps = Properties().apply { signingFile?.inputStream()?.use { load(it) } }

android {
    namespace = "com.jakharbek.monzaauto"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jakharbek.monzaauto"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (signingFile != null) {
            create("release") {
                storeFile = signingFile.parentFile.resolve(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
