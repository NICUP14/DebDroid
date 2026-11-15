// The DebDroid app's build.
//
// Three things here are generated from the repository rather than written
// down, because a second copy of each would drift silently:
//
//   - the version, read from DEBDROID_VER in debdroid/debdroid_lib.sh
//   - the payload, staged from the list in module/payload.list, which is the
//     same list module/build.sh stages the module zip from
//   - the image checksum the download is verified against, read from
//     rootfs/manifest, so the pin carried in the apk is the manifest's own

import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application") version "9.2.1"
}

val repoDir: File = rootDir.parentFile

// Reads the version from the single source of truth
// NOTE: AGENTS.md section 7 names the files a version lives in. The app is
//       deliberately not one more: it derives both values here, the way
//       module.prop's versionCode is derived (1.2.6 becomes 10206), and
//       tools/check-version.sh fails if a literal ever appears instead.
val debdroidVersion: String = run {
    val lib = repoDir.resolve("debdroid/debdroid_lib.sh").readText()
    Regex("^export DEBDROID_VER='([^']+)'$", RegexOption.MULTILINE)
        .find(lib)?.groupValues?.get(1)
        ?: throw GradleException("Could not read DEBDROID_VER from debdroid/debdroid_lib.sh.")
}

val debdroidVersionCode: Int = run {
    val parts = debdroidVersion.split(".").map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    parts[0] * 10000 + parts[1] * 100 + parts[2]
}

// Stages the payload the installer runs from into one asset
// NOTE: Built from the repository at build time and never committed under
//       src/main/assets, where a second copy of the scripts would drift from
//       the first without anyone noticing.
abstract class StagePayload : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val entries: ListProperty<String>

    @get:Internal
    abstract val repo: DirectoryProperty

    @get:Input
    abstract val epoch: Property<Long>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val root = repo.get().asFile
        val files = sortedMapOf<String, File>()

        for (entry in entries.get()) {
            val source = root.resolve(entry)
            if (!source.exists()) {
                throw GradleException("\"$entry\" is listed in module/payload.list but missing.")
            }
            source.walkTopDown().filter { it.isFile }.forEach {
                files[it.relativeTo(root).invariantSeparatorsPath] = it
            }
        }
        files["install.sh"] = root.resolve("install.sh")

        // NOTE: A checkout made with core.autocrlf=true writes CRLF into the
        //       scripts, and a shell script with CRLF fails on the device with
        //       errors that point nowhere near the cause. The module zip has
        //       the same exposure, but only the app is built on windows hosts.
        val crlf = files.filter { (_, file) ->
            val bytes = file.readBytes()
            val head = bytes.copyOf(minOf(bytes.size, 8192))
            !head.contains(0) && bytes.contains('\r'.code.toByte())
        }.keys
        if (crlf.isNotEmpty()) {
            throw GradleException(
                "CRLF line endings in the payload, which breaks every script on the device:\n  " +
                    crlf.joinToString("\n  ") +
                    "\nCheck the files out with LF endings (git config core.autocrlf false, then re-checkout)."
            )
        }

        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()

        // NOTE: Sorted entries and one fixed timestamp, as module/build.sh does,
        //       so the asset depends on the tree and not on when it was built.
        val time = epoch.get() * 1000
        val digest = MessageDigest.getInstance("SHA-256")
        ZipOutputStream(out.resolve("payload.zip").outputStream().buffered()).use { zip ->
            for ((name, file) in files) {
                val entry = ZipEntry(name)
                entry.time = time
                zip.putNextEntry(entry)
                val bytes = file.readBytes()
                zip.write(bytes)
                zip.closeEntry()
                digest.update(name.toByteArray())
                digest.update(bytes)
            }
        }

        // Lets the app tell whether the copy it unpacked is this one
        out.resolve("payload.id").writeText(digest.digest().joinToString("") { "%02x".format(it) })
    }
}

// Generates the constants the app pins, from the manifest and the version
abstract class GeneratePins : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val version: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        // Fields: id | kind | arch | release | sha256 | size | url | extras...
        val images = manifest.get().asFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line -> line.split("|").map { it.trim() } }
            .filter { it.size >= 6 && it[1] == "IMAGE" && it[4] != "PLACEHOLDER" }

        for (fields in images) {
            if (!Regex("^[0-9a-f]{64}$").matches(fields[4])) {
                throw GradleException("rootfs/manifest: \"${fields[4]}\" is not a sha256.")
            }
        }

        val body = images.joinToString(",\n") { f ->
            "        \"${f[2]}\" to PinnedImage(\"${f[4]}\", ${f[5]}L)"
        }

        val dir = outputDir.get().asFile.resolve("io/github/nicup14/debdroid")
        dir.mkdirs()
        dir.resolve("Pinned.kt").writeText(
            """
            |// Generated by app/build.gradle.kts from debdroid/debdroid_lib.sh and
            |// rootfs/manifest. Do not edit: change those files instead.
            |package io.github.nicup14.debdroid
            |
            |object Pinned {
            |    const val VERSION = "${version.get()}"
            |
            |    // The release this build's image is published under. Pinned to the
            |    // version, not "latest", because the checksum below belongs to it.
            |    const val RELEASE_URL = "https://github.com/NICUP14/DebDroid/releases/download/v${version.get()}"
            |
            |    val IMAGES: Map<String, PinnedImage> = mapOf(
            |$body
            |    )
            |}
            |""".trimMargin()
        )
    }
}

val payloadEntries: List<String> = repoDir.resolve("module/payload.list").readLines()
    .map { it.substringBefore("#").trim() }
    .filter { it.isNotEmpty() }

// NOTE: SOURCE_DATE_EPOCH first, which is what module/build.sh honours too, and
//       a constant otherwise, so an ordinary build is still reproducible.
val payloadEpoch: Long = System.getenv("SOURCE_DATE_EPOCH")?.toLongOrNull() ?: 1700000000L

val stagePayload = tasks.register<StagePayload>("stagePayload") {
    repo.set(repoDir)
    entries.set(payloadEntries)
    epoch.set(payloadEpoch)
    sources.from(payloadEntries.map { repoDir.resolve(it) }, repoDir.resolve("install.sh"))
    outputDir.set(layout.buildDirectory.dir("generated/payload"))
}

val generatePins = tasks.register<GeneratePins>("generatePins") {
    manifest.set(repoDir.resolve("rootfs/manifest"))
    version.set(debdroidVersion)
    outputDir.set(layout.buildDirectory.dir("generated/pins"))
}

android {
    namespace = "io.github.nicup14.debdroid"
    compileSdk = 37
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "io.github.nicup14.debdroid"
        // NOTE: Android 5, the project's floor. It is also why the app uses
        //       nothing but the framework.
        minSdk = 21
        targetSdk = 36
        versionName = debdroidVersion
        versionCode = debdroidVersionCode

        ndk {
            // NOTE: Every abi, although DebDroid ships arm64 binaries only. The
            //       pty library is a few kilobytes, and having it lets the app
            //       start and explain the refusal on an armv7 device instead of
            //       crashing before it can say anything.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    // NOTE: The icon set is used where it lives rather than copied in, so there
    //       is one copy of it. res/icon/README.md describes it.
    sourceSets {
        getByName("main") {
            res.directories.add(repoDir.resolve("res/icon/android/res").path)
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // NOTE: Release signing is configured only when its secrets are present, so
    //       a checkout builds without them and an unsigned release build is
    //       obvious rather than silently signed with a debug key. Where the key
    //       lives is a decision for the maintainer.
    val keystore = System.getenv("DEBDROID_KEYSTORE")
    if (keystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("DEBDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DEBDROID_KEY_ALIAS")
                keyPassword = System.getenv("DEBDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (keystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        // NOTE: Old api levels are the point of this app, so the lint that
        //       nags about targeting them is noise here.
        disable += listOf("OldTargetApi", "ExpiredTargetSdkVersion")
        // NOTE: /sdcard/debdroid and /data/local/debdroid are the shell's own
        //       fixed paths, read by root, never by the app through storage
        //       APIs, so the suggestion to resolve them at runtime is wrong
        //       here. Strings are in code, untranslated, by choice for now.
        disable += listOf("SdCardPath", "SetTextI18n")
        abortOnError = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(stagePayload, StagePayload::outputDir)
        variant.sources.kotlin?.addGeneratedSourceDirectory(generatePins, GeneratePins::outputDir)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
