import com.android.build.api.variant.FilterConfiguration
import org.gradle.api.tasks.Delete
import java.util.Properties

plugins {
    id("com.android.application")
}

val pluginVersionName = "1.0.0"
val rimePlugins = "hchunhui/librime-lua lotem/librime-octagram rime/librime-predict"
// One APK per ABI; override with -PbuildABI=arm64-v8a,armeabi-v7a when building
// only a subset. The universal APK is only produced for a full build.
val supportedABIs = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
val buildAbi = providers.gradleProperty("buildABI").orNull?.takeIf { it.isNotBlank() }

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val ndkVersionName = localProperties.getProperty("rime.ndkVersion", "29.0.14206865")
val cmakeVersion = localProperties.getProperty("rime.cmakeVersion", "4.2.1")
val buildToolsVersionName = localProperties.getProperty("rime.buildToolsVersion", "37.0.0")

// Standard Android signing setup. Local builds can copy
// keystore.properties.example to keystore.properties; CI should inject the
// keystore through environment variables instead. Neither the real properties
// file nor the keystore is committed.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val storeFilePath = providers.environmentVariable("KEYSTORE_FILE").orNull
    ?: keystoreProperties.getProperty("storeFile")
val storePasswordValue = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
    ?: keystoreProperties.getProperty("storePassword")
val keyAliasValue = providers.environmentVariable("KEY_ALIAS").orNull
    ?: keystoreProperties.getProperty("keyAlias")
val keyPasswordValue = providers.environmentVariable("KEY_PASSWORD").orNull
    ?: keystoreProperties.getProperty("keyPassword")
val storeFileResolved = storeFilePath?.takeIf { it.isNotBlank() }?.let { rootProject.file(it) }
val hasReleaseSigning = storeFileResolved?.isFile == true &&
    !storePasswordValue.isNullOrBlank() &&
    !keyAliasValue.isNullOrBlank() &&
    !keyPasswordValue.isNullOrBlank()

// Plugin-local build cache. Pass -PrimeBuildRoot=... to use another directory.
val rimeBuildRoot = providers.gradleProperty("rimeBuildRoot")
    .map { rootProject.file(it).absolutePath }
    .getOrElse(rootProject.layout.projectDirectory.dir(".cxx/rime").asFile.absolutePath)
val boostArchive = rootProject.layout.projectDirectory.file("boost_1_89_0.tar.gz").asFile.absolutePath

android {
    namespace = "org.rime.plugin.template"
    compileSdk = 36
    this.buildToolsVersion = buildToolsVersionName
    ndkVersion = ndkVersionName

    defaultConfig {
        applicationId = "org.rime.plugin.template"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = pluginVersionName

        externalNativeBuild {
            cmake {
                targets("plugin_template")
                arguments(
                    "-DANDROID_STL=c++_static",
                    "-DRIME_PLUGINS=$rimePlugins",
                    "-DRIME_BUILD_ROOT=$rimeBuildRoot",
                    "-DRIME_BOOST_ARCHIVE=$boostArchive",
                    "-DRIME_JNILIBS_DIR=${layout.buildDirectory.get().asFile}/generated/rimeJniLibs",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            version = cmakeVersion
            path("src/main/cpp/CMakeLists.txt")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = storeFileResolved
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["pluginChannel"] = "debug"
        }
        release {
            isMinifyEnabled = false
            manifestPlaceholders["pluginChannel"] = "release"
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Per-ABI split APKs; a universal APK is added only for a full build.
    splits {
        abi {
            isEnable = true
            isUniversalApk = buildAbi == null
            reset()
            buildAbi?.split(",")?.forEach { include(it) }
                ?: supportedABIs.forEach { include(it) }
        }
    }

    packaging {
        jniLibs {
            // Extract librime.so into this plugin's nativeLibraryDir so a
            // frontend can dlopen it without copying it into its data directory.
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main").jniLibs.srcDir(
            layout.buildDirectory.dir("generated/rimeJniLibs").get().asFile,
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.register<Delete>("rimeClean") {
    group = "rime"
    description = "Delete the plugin-local librime build cache"
    delete(rootProject.layout.projectDirectory.dir(".cxx/rime"))
}

androidComponents {
    onVariants { variant ->
        val applicationId = variant.applicationId.get()
        variant.outputs.forEach { output ->
            // ABI split outputs carry an ABI filter; the universal APK does not.
            val abi = output.filters
                .find { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier
                ?: "universal"
            output.outputFileName.set(
                "$applicationId-$pluginVersionName-${variant.name}-$abi.apk",
            )
        }
    }
}

base {
    archivesName.set("rime-plugin-template-$pluginVersionName")
}
