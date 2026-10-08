plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

val permanentKeystorePath = providers.environmentVariable("TWENTYFOURPI_KEYSTORE_PATH").orNull
val permanentStorePassword = providers.environmentVariable("TWENTYFOURPI_STORE_PASSWORD").orNull
val permanentKeyAlias = providers.environmentVariable("TWENTYFOURPI_KEY_ALIAS").orNull
val permanentKeyPassword = providers.environmentVariable("TWENTYFOURPI_KEY_PASSWORD").orNull
val permanentSigningReady = listOf(
    permanentKeystorePath,
    permanentStorePassword,
    permanentKeyAlias,
    permanentKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.twentyfourpi.lifelog"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.twentyfourpi.lifelog"
        minSdk = 34
        targetSdk = 35
        versionCode = 36
        versionName = "0.19.1"
        buildConfigField("boolean", "ENABLE_OBSERVATION_TOOLS", "false")

        manifestPlaceholders["AMAP_ANDROID_KEY"] = providers.environmentVariable("AMAP_ANDROID_KEY").orElse("").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (permanentSigningReady) {
            create("permanentRelease") {
                storeFile = file(requireNotNull(permanentKeystorePath))
                storePassword = permanentStorePassword
                keyAlias = permanentKeyAlias
                keyPassword = permanentKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ENABLE_OBSERVATION_TOOLS", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("boolean", "ENABLE_OBSERVATION_TOOLS", "false")
            // 未提供永久签名环境变量时生成 unsigned APK，避免意外发布无法覆盖安装的测试签名包。
            signingConfig = if (permanentSigningReady) signingConfigs.getByName("permanentRelease") else null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("observe") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            versionNameSuffix = "-observe"
            buildConfigField("boolean", "ENABLE_OBSERVATION_TOOLS", "true")
            signingConfig = if (permanentSigningReady) signingConfigs.getByName("permanentRelease") else null
            // observe 专用混淆配置（R8 全模式在投影层数据类上崩溃的针对性绕行，见文件内说明）。
            proguardFiles("proguard-observe.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    kapt("androidx.room:room-compiler:2.7.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    implementation("com.amap.api:3dmap-location-search:11.2.100_loc11.2.100_sea9.8.1")
    implementation("net.jafama:jafama:2.3.2")
    // SDK is pinned; only the on-demand map is initialized, never its location/search clients.
    // v0.18 restores the map with JNI keeps and a screen-scoped lifecycle. REST naming remains separate.

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.room:room-testing:2.7.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kapt {
    correctErrorTypes = true
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}
