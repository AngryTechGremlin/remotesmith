plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.angrytechgremlin.remotesmith"
    compileSdk = 36

    defaultConfig {
        // Permanent: an app id can never change once an APK has been published.
        applicationId = "io.github.angrytechgremlin.remotesmith"
        // Android 13: the Bluetooth calls this app relies on took their current form there,
        // and every maintained LineageOS TV build is newer.
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // F-Droid does not accept the signing block that lists dependencies; there are none anyway.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // The banner exists at the one density TV launchers use.
        disable += "IconMissingDensityFolder"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
