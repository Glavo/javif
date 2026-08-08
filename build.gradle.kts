import java.lang.module.ModuleDescriptor
import java.lang.module.ModuleFinder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.kotlin.dsl.attributes
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    id("java-library")
    id("jacoco")
    id("maven-publish")
    id("signing")
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
    id("org.glavo.load-maven-publish-properties") version "0.1.0"
    id("de.undercouch.download") version "5.7.0"
    id("org.glavo.gradle-wrapper-neo") version "0.2.0"
}

group = "org.glavo"

if (version == Project.DEFAULT_VERSION) {
    version = "0.1.0" + "-SNAPSHOT"
}

description = "Pure Java implementation of AV1 decoding and AVIF reading library"

val releaseVersionPattern =
    Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")
val configuredReleaseVersion = version.toString()
var mavenPublicationSigningConfigured = false

fun releaseProperty(name: String): String? =
    findProperty(name)?.toString()?.takeIf(String::isNotBlank)

java {
    withJavadocJar()
    withSourcesJar()
}

repositories {
    mavenCentral()
}

val viewerRuntime = configurations.create("viewerRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()

    val javafxVersion = "21.0.10"
    val javafxOS = when {
        osName.contains("win") -> "win"
        osName.contains("mac") -> "mac"
        osName.contains("linux") -> "linux"
        else -> null
    }
    val javafxArch = when (osArch) {
        "amd64", "x86-64", "x64" -> ""
        "aarch64", "arm64" -> "-aarch64"
        else -> null
    }

    fun javafx(module: String) {
        if (javafxOS != null && javafxArch != null) {
            val notation = "org.openjfx:javafx-$module:$javafxVersion:${javafxOS}${javafxArch}"

            compileOnly(notation)
            testCompileOnly(notation)
            testRuntimeOnly(notation)
            add(viewerRuntime.name, notation)
        }
    }

    javafx("base")
    javafx("controls")
    javafx("graphics")

    compileOnly("org.jetbrains:annotations:26.1.0")
    testCompileOnly("org.jetbrains:annotations:26.1.0")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.bytedeco:ffmpeg-platform:8.0.1-1.5.13")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val runtimeClasspathConfiguration = configurations.named("runtimeClasspath")
val mainJar = tasks.named<Jar>("jar")

tasks.register("verifyNoRuntimeDependencies") {
    group = "verification"
    description = "Verifies that the core library has no external runtime dependencies."
    dependsOn(mainJar)

    doLast {
        val externalComponents = runtimeClasspathConfiguration.get()
            .incoming
            .resolutionResult
            .allComponents
            .filter { it.id !is ProjectComponentIdentifier }

        check(externalComponents.isEmpty()) {
            externalComponents.joinToString(
                prefix = "runtimeClasspath contains external dependencies:\n",
                separator = "\n",
            ) { component -> "- ${component.id.displayName}" }
        }

        val descriptor = ModuleFinder.of(mainJar.get().archiveFile.get().asFile.toPath())
            .find("org.glavo.avif")
            .orElseThrow { GradleException("Main JAR does not contain the org.glavo.avif module") }
            .descriptor()
        val requiredRuntimeModules = descriptor.requires()
            .filter { ModuleDescriptor.Requires.Modifier.STATIC !in it.modifiers() }
            .map { it.name() }
            .toSet()

        check(requiredRuntimeModules == setOf("java.base")) {
            "org.glavo.avif requires runtime modules other than java.base: ${requiredRuntimeModules.sorted().joinToString()}"
        }
    }
}

val verifyReleaseVersion = tasks.register("verifyReleaseVersion") {
    group = "verification"
    description = "Verifies that the project uses a valid non-SNAPSHOT release version."
    inputs.property("releaseVersion", configuredReleaseVersion)

    doLast {
        if (!releaseVersionPattern.matches(configuredReleaseVersion)
                || configuredReleaseVersion.endsWith("-SNAPSHOT", ignoreCase = true)) {
            throw GradleException(
                "Release version must use <major>.<minor>.<patch> with an optional pre-release suffix: " +
                        configuredReleaseVersion,
            )
        }
    }
}

val requiredReleaseProperties = listOf(
    "sonatypeUsername",
    "sonatypePassword",
    "signing.key",
    "signing.password",
)
val configuredReleaseProperties = requiredReleaseProperties.associateWith(::releaseProperty)

val verifyReleaseConfiguration = tasks.register("verifyReleaseConfiguration") {
    group = "verification"
    description = "Verifies the credentials and signing key required for a Maven Central release."
    dependsOn(verifyReleaseVersion)

    doLast {
        val missingProperties = requiredReleaseProperties.filter { configuredReleaseProperties[it] == null }
        if (missingProperties.isNotEmpty()) {
            throw GradleException(
                missingProperties.joinToString(
                    prefix = "Missing Maven Central release properties: ",
                    separator = ", ",
                ),
            )
        }
        if (!mavenPublicationSigningConfigured) {
            throw GradleException("The Maven publication is not configured for signing")
        }
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(17)
}

val mainClassName = "org.glavo.avif.javafx.AvifViewerApp"

tasks.jar {
    manifest.attributes(
        "Main-Class" to mainClassName,
    )
}

tasks.withType<Jar>().configureEach {
    from(layout.projectDirectory.file("LICENSE")) {
        into("META-INF")
    }
}

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs the JavaFX AVIF viewer."
    dependsOn(tasks.classes)
    classpath(sourceSets["main"].runtimeClasspath, viewerRuntime)
    mainClass.set(mainClassName)

    if (this.javaVersion >= JavaVersion.VERSION_25) {
        jvmArgs("--enable-native-access=javafx.graphics")
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("aom-corpus", "argon-corpus", "firefox-corpus", "chromium-corpus")
    }
    systemProperty(
        "org.bytedeco.javacpp.cachedir",
        layout.buildDirectory.dir("javacpp-cache").get().asFile.absolutePath,
    )

    if (this.javaVersion >= JavaVersion.VERSION_25) {
        jvmArgs("--enable-native-access=javafx.graphics")
    }
}

val testCorpusCacheDirectory = providers.provider {
    layout.projectDirectory.dir("external/test-corpora")
}
val libavifCommit = "b54eac58daf563e9150cc6abce7631ac71b999aa"
val libavifArchiveSha256 = "a9ef36092cf8e70b6ef9aad6d89f041a1d0880d43bee8eed12dcbc1a965f21ec"
val libavifZip = testCorpusCacheDirectory.map { it.file("libavif-$libavifCommit.zip") }
val aomAvifCommit = "bf4c18d1f3971069b75e87d6ee469790589f4f09"
val aomAvifArchiveSha256 = "840d9c5330d5d7965b4838b282a333810027a15166ccb80c034ef4bc5951cd2c"
val aomAvifZip = testCorpusCacheDirectory.map { it.file("av1-avif-$aomAvifCommit.zip") }
val aomAvifTestResourcesDirectory = layout.buildDirectory.dir("aom-avif-test-resources")
val argonAv1Version = "2.1.1"
val argonAv1ArchiveName = "argon_coveragetool_av1_base_and_extended_profiles_v$argonAv1Version.zip"
val argonAv1ArchiveSha256 = "33ff3d2ca3c53706c00a49c7c752dcedf431a8e771d369a45351c15198a4c242"
val argonAv1Zip = testCorpusCacheDirectory.map { it.file(argonAv1ArchiveName) }
val firefoxCommit = "ac91bfcce1bf3240e2dce40f47c372e76bc4f26c"
val firefoxAvifTestResourcesDirectory = testCorpusCacheDirectory.map { it.dir("firefox-avif-$firefoxCommit") }
val firefoxGtestResourcesDirectory = firefoxAvifTestResourcesDirectory.map {
    it.dir("firefox-avif-test-data/gtest")
}
val firefoxCrashResourcesDirectory = firefoxAvifTestResourcesDirectory.map {
    it.dir("firefox-avif-test-data/crashtests")
}
val firefoxGtestResourceNames = listOf(
    "blend.avif",
    "bug-1655846.avif",
    "downscaled.avif",
    "first-frame-green.avif",
    "green.avif",
    "hdlr-nonzero-reserved-bug-1727033.avif",
    "large.avif",
    "multilayer.avif",
    "stackcheck.avif",
    "transparent.avif",
    "valid-avif-colr-nclx-and-prof.avif",
) + listOf(8, 10, 12).flatMap { bitDepth ->
    listOf("full", "limited").flatMap { range ->
        listOf("bt601", "bt709", "bt2020", "grayscale").map { matrix ->
            "gray-235-${bitDepth}bit-$range-range-$matrix.avif"
        }
    }
} + listOf(8, 10, 12).flatMap { bitDepth ->
    listOf("yuv420", "yuv422", "yuv444").map { chroma ->
        "transparent-green-50pct-${bitDepth}bit-$chroma.avif"
    }
}
val firefoxCrashResourceNames = listOf(
    "1814553.avif",
    "1814561.avif",
    "1814677.avif",
    "1814708.avif",
    "1814741.avif",
    "1814774.avif",
    "1817108.avif",
    "1848717-1.avif",
    "1910211-1.avif",
)
val chromiumCommit = "ddb449c8c2536723346df7ea26ca13d99857c302"
val chromiumAvifTestResourcesDirectory = testCorpusCacheDirectory.map { it.dir("chromium-avif-$chromiumCommit") }
val chromiumAvifResourcesDirectory = chromiumAvifTestResourcesDirectory.map {
    it.dir("chromium-avif-test-data")
}
val chromiumAvifResourceNames = listOf(
    "README.md",
    "blue-and-magenta-crop-invalid.avif",
    "blue-and-magenta-crop.avif",
    "dice_444_10b_grid4x3.avif",
    "gainmap-sdr-srgb-to-hdr-wcg-rec2020.avif",
    "gracehopper_422_12b_grid2x4.avif",
    "green-no-alpha-ispe.avif",
    "hdr-base-with-yuv400-gainmap.avif",
    "red-and-purple-and-blue.avif",
    "red-and-purple-crop.avif",
    "red-at-12-oclock-with-color-profile-lossy.avif",
    "red-at-12-oclock-with-color-profile-truncated.avif",
    "red-at-12-oclock-with-color-profile-with-wrong-frame-header.avif",
    "red-full-range-angle-1-420-8bpc.avif",
    "red-full-range-angle-2-mode-0-420-8bpc.avif",
    "red-full-range-angle-3-mode-1-420-8bpc.avif",
    "red-full-range-bt2020-hlg-444-10bpc.avif",
    "red-full-range-bt2020-hlg-444-12bpc.avif",
    "red-full-range-bt2020-pq-444-10bpc.avif",
    "red-full-range-bt2020-pq-444-12bpc.avif",
    "red-full-range-bt709-444-8bpc.avif",
    "red-full-range-mode-0-420-8bpc.avif",
    "red-full-range-mode-1-420-8bpc.avif",
    "red-full-range-unspecified-420-8bpc.avif",
    "red-icc-version-zero.avif",
    "red-unsupported-transfer.avif",
    "silver-400-matrix-0.avif",
    "silver-400-matrix-6.avif",
    "silver-full-range-srgb-420-8bpc.avif",
    "small-with-gainmap-iso-gammazero.avif",
    "small-with-gainmap-iso-hdrbase.avif",
    "small-with-gainmap-iso-usealtcolorspace-differenticc.avif",
    "small-with-gainmap-iso-usealtcolorspace.avif",
    "small-with-gainmap-iso.avif",
    "star-animated-8bpc-1-repetition.avif",
    "star-animated-8bpc-10-repetition.avif",
    "star-animated-8bpc-infinite-repetition.avif",
    "tiger_3layer_1res.avif",
    "tiger_3layer_3res.avif",
    "tiger_420_8b_grid1x13.avif",
) + listOf(8, 10, 12).flatMap { bitDepth ->
    listOf("full", "limited").map { range ->
        "alpha-mask-$range-range-${bitDepth}bpc.avif"
    }
} + listOf(8, 10, 12).map { bitDepth ->
    "red-at-12-oclock-with-color-profile-${bitDepth}bpc.avif"
} + listOf(8, 10, 12).map { bitDepth ->
    "red-full-range-420-${bitDepth}bpc.avif"
} + listOf(8, 10, 12).flatMap { bitDepth ->
    listOf(420, 422, 444).map { chroma ->
        "red-limited-range-$chroma-${bitDepth}bpc.avif"
    }
} + listOf(8, 10, 12).map { bitDepth ->
    "red-with-alpha-${bitDepth}bpc.avif"
} + listOf(8, 10, 12).flatMap { bitDepth ->
    listOf(
        "star-animated-${bitDepth}bpc.avif",
        "star-animated-${bitDepth}bpc-with-alpha.avif",
    )
}

val downloadLibavif = tasks.register<de.undercouch.gradle.tasks.download.Download>("downloadLibavif") {
    src("https://github.com/AOMediaCodec/libavif/archive/$libavifCommit.zip")
    dest(libavifZip)
    overwrite(false)
}

val downloadAomAvifTestFiles =
    tasks.register<de.undercouch.gradle.tasks.download.Download>("downloadAomAvifTestFiles") {
        src("https://github.com/AOMediaCodec/av1-avif/archive/$aomAvifCommit.zip")
        dest(aomAvifZip)
        overwrite(false)
        onlyIf("the pinned AOMedia archive is not already cached") {
            !aomAvifZip.get().asFile.isFile
        }
    }

val downloadArgonAv1Streams =
    tasks.register<de.undercouch.gradle.tasks.download.Download>("downloadArgonAv1Streams") {
        group = "verification"
        description = "Downloads the pinned Argon Streams AV1 corpus."
        src("https://aom-cwg-av1-argon-streams-public.s3.us-east-1.amazonaws.com/$argonAv1ArchiveName")
        dest(argonAv1Zip)
        overwrite(false)
        onlyIf("the pinned Argon Streams archive is not already cached") {
            !argonAv1Zip.get().asFile.isFile
        }
    }

val downloadFirefoxAvifGtestResources =
    tasks.register<de.undercouch.gradle.tasks.download.Download>("downloadFirefoxAvifGtestResources") {
        group = "verification"
        description = "Downloads the selected pinned Firefox AVIF gtest resources."
        src(firefoxGtestResourceNames.map { fileName ->
            "https://raw.githubusercontent.com/mozilla-firefox/firefox/$firefoxCommit/image/test/gtest/$fileName"
        })
        dest(firefoxGtestResourcesDirectory)
        overwrite(false)
        onlyIf("one or more pinned Firefox AVIF gtest resources are not already cached") {
            firefoxGtestResourceNames.any { fileName ->
                !firefoxGtestResourcesDirectory.get().file(fileName).asFile.isFile
            }
        }
    }

val downloadFirefoxAvifCrashResources =
    tasks.register<de.undercouch.gradle.tasks.download.Download>("downloadFirefoxAvifCrashResources") {
        group = "verification"
        description = "Downloads the selected pinned Firefox AVIF crash-test resources."
        src(firefoxCrashResourceNames.map { fileName ->
            "https://raw.githubusercontent.com/mozilla-firefox/firefox/$firefoxCommit/image/test/crashtests/$fileName"
        })
        dest(firefoxCrashResourcesDirectory)
        overwrite(false)
        onlyIf("one or more pinned Firefox AVIF crash-test resources are not already cached") {
            firefoxCrashResourceNames.any { fileName ->
                !firefoxCrashResourcesDirectory.get().file(fileName).asFile.isFile
            }
        }
    }

val downloadFirefoxAvifTests = tasks.register("downloadFirefoxAvifTests") {
    group = "verification"
    description = "Downloads the selected pinned Firefox AVIF test resources."
    dependsOn(downloadFirefoxAvifGtestResources, downloadFirefoxAvifCrashResources)
}

val downloadChromiumAvifTests =
    tasks.register<de.undercouch.gradle.tasks.download.Download>("downloadChromiumAvifTests") {
        group = "verification"
        description = "Downloads the selected pinned Chromium AVIF image-decoder resources."
        src(chromiumAvifResourceNames.map { fileName ->
            "https://raw.githubusercontent.com/chromium/chromium/$chromiumCommit/" +
                    "third_party/blink/web_tests/images/resources/avif/$fileName"
        })
        dest(chromiumAvifResourcesDirectory)
        overwrite(false)
        onlyIf("one or more pinned Chromium AVIF resources are not already cached") {
            chromiumAvifResourceNames.any { fileName ->
                !chromiumAvifResourcesDirectory.get().file(fileName).asFile.isFile
            }
        }
    }

fun aggregateCorpusSha256(directory: File, resourceNames: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    for (resourceName in resourceNames.sorted()) {
        val resource = directory.resolve(resourceName)
        check(resource.isFile) { "Missing corpus resource: $resource" }
        digest.update(resourceName.toByteArray(StandardCharsets.UTF_8))
        digest.update(0.toByte())
        resource.inputStream().buffered().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) {
                    break
                }
                digest.update(buffer, 0, count)
            }
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}

fun fileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    file.inputStream().buffered().use { input ->
        while (true) {
            val count = input.read(buffer)
            if (count < 0) {
                break
            }
            digest.update(buffer, 0, count)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}

val verifyLibavifArchive = tasks.register("verifyLibavifArchive") {
    group = "verification"
    description = "Verifies the pinned libavif test-data archive."
    dependsOn(downloadLibavif)

    inputs.file(libavifZip)
    inputs.property("expectedSha256", libavifArchiveSha256)
    val verificationMarker = layout.buildDirectory.file("verified-test-corpora/libavif-$libavifCommit.sha256")
    outputs.file(verificationMarker)

    doLast {
        val actualSha256 = fileSha256(libavifZip.get().asFile)
        check(actualSha256 == libavifArchiveSha256) {
            "Unexpected libavif test-data archive digest: $actualSha256"
        }
        val marker = verificationMarker.get().asFile
        marker.parentFile.mkdirs()
        marker.writeText(actualSha256 + "\n", StandardCharsets.UTF_8)
    }
}

val verifyAomAvifArchive = tasks.register("verifyAomAvifArchive") {
    group = "verification"
    description = "Verifies the pinned AOMedia AVIF test archive."
    dependsOn(downloadAomAvifTestFiles)

    inputs.file(aomAvifZip)
    inputs.property("expectedSha256", aomAvifArchiveSha256)
    val verificationMarker = layout.buildDirectory.file("verified-test-corpora/av1-avif-$aomAvifCommit.sha256")
    outputs.file(verificationMarker)

    doLast {
        val actualSha256 = fileSha256(aomAvifZip.get().asFile)
        check(actualSha256 == aomAvifArchiveSha256) {
            "Unexpected AOMedia AVIF test archive digest: $actualSha256"
        }
        val marker = verificationMarker.get().asFile
        marker.parentFile.mkdirs()
        marker.writeText(actualSha256 + "\n", StandardCharsets.UTF_8)
    }
}

val verifyArgonAv1Archive = tasks.register("verifyArgonAv1Archive") {
    group = "verification"
    description = "Verifies the pinned Argon Streams AV1 corpus archive."
    dependsOn(downloadArgonAv1Streams)

    inputs.file(argonAv1Zip)
    inputs.property("expectedSha256", argonAv1ArchiveSha256)
    val verificationMarker = layout.buildDirectory.file("verified-test-corpora/$argonAv1ArchiveName.sha256")
    outputs.file(verificationMarker)

    doLast {
        val archive = argonAv1Zip.get().asFile
        val actualSha256 = fileSha256(archive)
        check(actualSha256 == argonAv1ArchiveSha256) {
            "Unexpected Argon Streams AV1 archive digest: $actualSha256"
        }
        val marker = verificationMarker.get().asFile
        marker.parentFile.mkdirs()
        marker.writeText(actualSha256 + "\n", StandardCharsets.UTF_8)
    }
}

val verifyFirefoxAvifTestResources = tasks.register("verifyFirefoxAvifTestResources") {
    group = "verification"
    description = "Verifies the pinned Firefox AVIF resource selections."
    dependsOn(downloadFirefoxAvifTests)

    doLast {
        val gtestHash = aggregateCorpusSha256(firefoxGtestResourcesDirectory.get().asFile, firefoxGtestResourceNames)
        check(gtestHash == "a87147ca266d04e9a2fba1f6ec0d08f448080f1d3b85b27e9a98d21a06d073b3") {
            "Unexpected Firefox AVIF gtest resource digest: $gtestHash"
        }
        val crashHash = aggregateCorpusSha256(firefoxCrashResourcesDirectory.get().asFile, firefoxCrashResourceNames)
        check(crashHash == "f1ed713e679177f364c08245904afc61db09bdbe2971c036212944f3be3cad24") {
            "Unexpected Firefox AVIF crash-test resource digest: $crashHash"
        }
    }
}

val verifyChromiumAvifTestResources = tasks.register("verifyChromiumAvifTestResources") {
    group = "verification"
    description = "Verifies the pinned Chromium AVIF resource selection."
    dependsOn(downloadChromiumAvifTests)

    doLast {
        val hash = aggregateCorpusSha256(chromiumAvifResourcesDirectory.get().asFile, chromiumAvifResourceNames)
        check(hash == "009014d4bfc94b1058d3e3bbff3fc3545ad3c3267c75d0ab272fc7aeb7051c6b") {
            "Unexpected Chromium AVIF resource digest: $hash"
        }
    }
}

val prepareAomAvifTestResources = tasks.register<Sync>("prepareAomAvifTestResources") {
    dependsOn(verifyAomAvifArchive)
    inputs.property("aomAvifResourceSetVersion", 2)
    into(aomAvifTestResourcesDirectory)

    from(zipTree(aomAvifZip)) {
        includeEmptyDirs = false

        val rootDirName = "av1-avif-$aomAvifCommit"
        val copiedRootFileNames = setOf("LICENSE")
        val copiedTestFileNames = setOf("COPYING")

        eachFile {
            val pathSegments = relativePath.segments.toList()
            val fileName = pathSegments.lastOrNull()
            val copiedPngReference = fileName?.endsWith(".png") == true
                    && !(pathSegments.size > 3
                    && pathSegments[2] == "Netflix"
                    && fileName.startsWith("original_"))
            val copiedTestFile = fileName != null && (
                    fileName in copiedTestFileNames
                            || fileName.endsWith(".avif")
                            || fileName.endsWith(".avifs")
                            || copiedPngReference
                            || fileName.endsWith(".md")
                            || fileName.endsWith(".txt")
                    )

            when {
                pathSegments.size == 2
                        && pathSegments[0] == rootDirName
                        && pathSegments[1] in copiedRootFileNames -> {
                    relativePath = RelativePath(true, "aom-av1-avif-test-data", pathSegments[1])
                }

                pathSegments.size > 2
                        && pathSegments[0] == rootDirName
                        && pathSegments[1] == "testFiles"
                        && copiedTestFile -> {
                    relativePath = RelativePath(
                        true,
                        *(listOf("aom-av1-avif-test-data")
                                + pathSegments.subList(2, pathSegments.size)).toTypedArray(),
                    )
                }

                else -> exclude()
            }
        }
    }
}

tasks.register<Test>("aomAvifTest") {
    group = "verification"
    description = "Runs the AOMedia AVIF corpus tests."
    dependsOn(tasks.testClasses)
    dependsOn(prepareAomAvifTestResources)

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath + files(aomAvifTestResourcesDirectory)
    maxHeapSize = "2g"
    extensions.configure<JacocoTaskExtension> {
        isEnabled = false
    }

    useJUnitPlatform {
        includeTags("aom-corpus")
    }
    systemProperty(
        "org.bytedeco.javacpp.cachedir",
        layout.buildDirectory.dir("javacpp-cache").get().asFile.absolutePath,
    )

    if (this.javaVersion >= JavaVersion.VERSION_25) {
        jvmArgs("--enable-native-access=javafx.graphics")
    }
}

tasks.register<Test>("argonAv1Test") {
    group = "verification"
    description = "Runs the Argon Streams AV1 corpus tests."
    dependsOn(tasks.testClasses)
    dependsOn(verifyArgonAv1Archive)

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    maxHeapSize = providers.gradleProperty("argonAv1MaxHeap").getOrElse("4g")
    extensions.configure<JacocoTaskExtension> {
        isEnabled = false
    }

    useJUnitPlatform {
        includeTags("argon-corpus")
    }
    systemProperty(
        "org.glavo.avif.argon.archive",
        argonAv1Zip.get().asFile.absolutePath,
    )
    providers.gradleProperty("argonAv1Case").orNull?.let { selectedCase ->
        systemProperty("org.glavo.avif.argon.case", selectedCase)
    }
    providers.gradleProperty("argonAv1Shard").orNull?.let { selectedShard ->
        systemProperty("org.glavo.avif.argon.shard", selectedShard)
    }
    providers.gradleProperty("argonAv1OperatingPoint").orNull?.let { selectedOperatingPoint ->
        systemProperty("org.glavo.avif.argon.operatingPoint", selectedOperatingPoint)
    }
    providers.gradleProperty("argonAv1Output").orNull?.let { selectedOutput ->
        systemProperty("org.glavo.avif.argon.output", selectedOutput)
    }
    providers.gradleProperty("argonAv1Jfr").orNull?.let { recordingPath ->
        val recordingFile = layout.projectDirectory.file(recordingPath).asFile
        val recordingRepository = layout.buildDirectory.dir("jfr-repository").get().asFile
        doFirst {
            recordingFile.parentFile.mkdirs()
            recordingRepository.mkdirs()
        }
        jvmArgs(
            "-XX:FlightRecorderOptions=repository=${recordingRepository.absolutePath}",
            "-XX:StartFlightRecording=filename=${recordingFile.absolutePath},settings=profile,dumponexit=true",
        )
    }
    if (providers.gradleProperty("argonAv1TraceFrames").isPresent) {
        systemProperty("org.glavo.avif.argon.traceFrames", "true")
    }
}

tasks.register<Test>("firefoxAvifTest") {
    group = "verification"
    description = "Runs the selected Firefox AVIF compatibility and regression tests."
    dependsOn(tasks.testClasses)
    dependsOn(verifyFirefoxAvifTestResources)

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath + files(firefoxAvifTestResourcesDirectory)
    maxHeapSize = "2g"
    extensions.configure<JacocoTaskExtension> {
        isEnabled = false
    }

    useJUnitPlatform {
        includeTags("firefox-corpus")
    }
}

tasks.register<Test>("chromiumAvifTest") {
    group = "verification"
    description = "Runs the selected Chromium AVIF compatibility and regression tests."
    dependsOn(tasks.testClasses)
    dependsOn(verifyChromiumAvifTestResources)

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath + files(chromiumAvifTestResourcesDirectory)
    maxHeapSize = "2g"
    extensions.configure<JacocoTaskExtension> {
        isEnabled = false
    }

    useJUnitPlatform {
        includeTags("chromium-corpus")
    }
}

tasks.processTestResources {
    dependsOn(verifyLibavifArchive)

    from(zipTree(libavifZip)) {
        includeEmptyDirs = false

        val rootDirName = "libavif-$libavifCommit"
        val dataDir = listOf(rootDirName, "tests", "data")

        eachFile {
            val pathSegments = relativePath.segments.toList()
            if (pathSegments.size > 3 && pathSegments.subList(0, 3) == dataDir) {
                relativePath = RelativePath(
                    true,
                    *(listOf("libavif-test-data") + pathSegments.subList(3, pathSegments.size)).toTypedArray(),
                )
            } else {
                exclude()
            }
        }
    }

}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        csv.required.set(true)
        html.required.set(true)
    }
}

tasks.withType<Javadoc> {
    doFirst {
        if (JavaVersion.current() < JavaVersion.VERSION_23) {
            throw GradleException("Javadoc generation requires JDK 23 or newer for Markdown documentation comments")
        }
    }

    (options as StandardJavadocDocletOptions).also {
        it.jFlags!!.addAll(listOf("-Duser.language=en", "-Duser.country=", "-Duser.variant="))

        it.encoding("UTF-8")
        it.addBooleanOption("html5", true)
        it.addBooleanOption("quiet", true)

        it.tags!!.addAll(
            listOf(
                "apiNote:a:API Note:",
                "implNote:a:Implementation Note:",
                "implSpec:a:Implementation Specification:",
            )
        )
    }
}


tasks.withType<GenerateModuleMetadata> {
    enabled = false
}

publishing.publications.create<MavenPublication>("maven") {
    groupId = project.group.toString()
    version = project.version.toString()
    artifactId = project.name

    from(components["java"])

    pom {
        name.set(project.name)
        description.set(project.description)
        url.set("https://github.com/Glavo/javif")

        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }

        developers {
            developer {
                id.set("Glavo")
                name.set("Glavo")
                email.set("zjx001202@gmail.com")
            }
        }

        scm {
            url.set("https://github.com/Glavo/javif")
            connection.set("scm:git:https://github.com/Glavo/javif.git")
            developerConnection.set("scm:git:ssh://git@github.com/Glavo/javif.git")
            tag.set("v${project.version}")
        }
    }
}

val mavenPublication = publishing.publications.named<MavenPublication>("maven")
val signingKey = releaseProperty("signing.key")
if (signingKey != null) {
    signing {
        useInMemoryPgpKeys(
            releaseProperty("signing.keyId"),
            signingKey,
            releaseProperty("signing.password"),
        )
        sign(mavenPublication.get())
    }
    mavenPublicationSigningConfigured = true
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
            username.set(providers.provider { releaseProperty("sonatypeUsername") ?: "" })
            password.set(providers.provider { releaseProperty("sonatypePassword") ?: "" })
            stagingProfileId.set(providers.provider {
                releaseProperty("sonatypeStagingProfileId") ?: project.group.toString()
            })
        }
    }
}

val remoteReleaseTasks = setOf(
    "publish",
    "publishToSonatype",
    "publishAllPublicationsToSonatypeRepository",
    "publishMavenPublicationToSonatypeRepository",
    "initializeSonatypeStagingRepository",
    "closeSonatypeStagingRepository",
    "closeStagingRepositories",
    "releaseSonatypeStagingRepository",
    "releaseStagingRepositories",
    "closeAndReleaseSonatypeStagingRepository",
    "closeAndReleaseStagingRepositories",
)
tasks.configureEach {
    if (name in remoteReleaseTasks) {
        dependsOn(verifyReleaseConfiguration)
    }
}
