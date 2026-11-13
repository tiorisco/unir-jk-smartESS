import java.util.Properties

// Credenciales del broker MQTT: en local.properties (fuera de git).
//   mqtt.host=xxxx.ala.eu-central-1.emqxsl.com
//   mqtt.user=usuario_SOLO_LECTURA
//   mqtt.password=...
val local = Properties().apply {
    val archivo = rootProject.file("local.properties")
    if (archivo.exists()) archivo.inputStream().use { load(it) }
}

// Firma de la version para la familia: claves en key.properties (fuera de git).
val firma = Properties().apply {
    val archivo = rootProject.file("key.properties")
    if (archivo.exists()) archivo.inputStream().use { load(it) }
}

fun propiedad(clave: String) = (local.getProperty(clave) ?: "").replace("\\", "\\\\").replace("\"", "\\\"")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mrsmanuel.monitorsolar"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mrsmanuel.monitorsolar"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "MQTT_HOST", "\"${propiedad("mqtt.host")}\"")
        buildConfigField("String", "MQTT_USER", "\"${propiedad("mqtt.user")}\"")
        buildConfigField("String", "MQTT_PASSWORD", "\"${propiedad("mqtt.password")}\"")
        // Cuenta de SmartESS (nombre de usuario, no el correo). Queda dentro del APK:
        // vale para uso personal, no para publicar la app tal cual.
        buildConfigField("String", "SMARTESS_USER", "\"${propiedad("smartess.user")}\"")
        buildConfigField("String", "SMARTESS_PASSWORD", "\"${propiedad("smartess.password")}\"")
        // Colector en el servidor (histórico). El token es el de LECTURA (SOLAR_APP_TOKEN).
        buildConfigField("String", "SERVIDOR_URL", "\"${local.getProperty("servidor.url") ?: "https://tu-servidor.duckdns.org"}\"")
        buildConfigField("String", "SERVIDOR_TOKEN", "\"${propiedad("servidor.token")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("familia") {
            storeFile = firma.getProperty("storeFile")?.let { rootProject.file(it) }
            storePassword = firma.getProperty("storePassword")
            keyAlias = firma.getProperty("keyAlias")
            keyPassword = firma.getProperty("keyPassword")
        }
    }

    buildTypes {
        // ./gradlew assembleFamilia  ->  APK firmado para repartir.
        // NO lleva la cuenta de SmartESS: el inversor sale del colector del servidor.
        create("familia") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("familia")
            applicationIdSuffix = ".familia"
            versionNameSuffix = "-familia"
            buildConfigField("String", "SMARTESS_USER", "\"\"")
            buildConfigField("String", "SMARTESS_PASSWORD", "\"\"")
            // Si existe un usuario MQTT propio de la familia (solo lectura), se usa ese
            if (local.getProperty("familia.mqtt.user") != null) {
                buildConfigField("String", "MQTT_USER", "\"${propiedad("familia.mqtt.user")}\"")
                buildConfigField("String", "MQTT_PASSWORD", "\"${propiedad("familia.mqtt.password")}\"")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // El cliente MQTT de HiveMQ trae estos metadatos duplicados
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("com.hivemq:hivemq-mqtt-client:1.3.3")
}
