import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

group = "com.github.vishalsgithub"
version = "1.0.0"

android {
    namespace = "com.xpertnest.liveness"
    compileSdk = 36
    // Only needed to build the library itself; apps get the compiled .so files.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // Engine + JNI bridge; ncnn is downloaded once by CMake (see CMakeLists.txt).
    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val cameraxVersion = "1.5.3"

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.vishalsgithub"
            artifactId = "face-liveness-android"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Face Liveness for Android")
                description.set("On-device face liveness (anti-spoofing) detection for Android, powered by ncnn.")
                url.set("https://github.com/vishalsgithub/face-liveness-android")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
            }
        }
    }
}
