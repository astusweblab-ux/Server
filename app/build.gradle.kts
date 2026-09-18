import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ru.serverastus.monitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.serverastus.monitor"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "1.7.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
    outputs.upToDateWhen { false }
}


dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    // Виджет на рабочий стол — Compose-подобный API для AppWidget, без RemoteViews вручную.
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Чистый Java-клиент SSH: поддерживает ed25519/rsa-sha2/curve25519, которые
    // требует современный OpenSSH. Старый com.jcraft:jsch не умеет ни то, ни другое.
    implementation("com.github.mwiede:jsch:2.28.7")

    testImplementation("junit:junit:4.13.2")
    // JVM-тесты читают config.json: в unit-тестах android.jar подменяется заглушкой,
    // поэтому org.json (тот же API, что и на устройстве) нужен на тестовом classpath.
    // Версия не древняя: у org.json:20090211 нет JSONArray.remove(int), который есть
    // в Android с самого начала.
    testImplementation("org.json:json:20240303")
}
