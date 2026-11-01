plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.tinycast.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.tinycast.android"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("droidcastVersionCode").orElse("6").get().toInt().also { require(it in 6..2_100_000_000) }
        versionName = providers.gradleProperty("droidcastVersionName").orElse("0.5.0-nightly").get().also { require(Regex("[A-Za-z0-9.+-]{1,60}").matches(it)) }
        val commit = providers.gradleProperty("droidcastBuildCommit").orElse("local").get()
        require(commit == "local" || Regex("[a-f0-9]{40}").matches(commit))
        buildConfigField("String", "BUILD_COMMIT", "\"$commit\"")
        manifestPlaceholders["buildCommit"] = commit
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Separate disposable package for performance runs; never an updater/release artifact.
    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".benchmark"
            versionNameSuffix = "-benchmark"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += "release"
        }
    }
    testBuildType = providers.gradleProperty("droidcastTestBuildType").orElse("debug").get().also {
        require(it in setOf("debug", "benchmark"))
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-core:1.7.6")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.6")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.6")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.6")
}
