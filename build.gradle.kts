import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.kotlin.dsl.attributes

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
    javafx("swing") // For Benchmark

    compileOnlyApi("org.jetbrains:annotations:26.1.0")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.bytedeco:ffmpeg-platform:8.0.1-1.5.13")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val runtimeClasspathConfiguration = configurations.named("runtimeClasspath")

tasks.register("verifyNoRuntimeDependencies") {
    group = "verification"
    description = "Verifies that the core library has no external runtime dependencies."

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
        excludeTags("aom-corpus", "argon-corpus")
    }
    systemProperty(
        "org.bytedeco.javacpp.cachedir",
        layout.buildDirectory.dir("javacpp-cache").get().asFile.absolutePath,
    )

    if (this.javaVersion >= JavaVersion.VERSION_25) {
        jvmArgs("--enable-native-access=javafx.graphics")
    }
}

val libavifCommit = "b54eac58daf563e9150cc6abce7631ac71b999aa"
val libavifZip = layout.buildDirectory.file("downloads/libavif-$libavifCommit.zip")
val aomAvifCommit = "bf4c18d1f3971069b75e87d6ee469790589f4f09"
val aomAvifZip = layout.buildDirectory.file("downloads/av1-avif-$aomAvifCommit.zip")
val aomAvifTestResourcesDirectory = layout.buildDirectory.dir("aom-avif-test-resources")
val argonAv1Version = "2.1.1"
val argonAv1ArchiveName = "argon_coveragetool_av1_base_and_extended_profiles_v$argonAv1Version.zip"
val argonAv1Zip = layout.buildDirectory.file("downloads/$argonAv1ArchiveName")

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

val prepareAomAvifTestResources = tasks.register<Sync>("prepareAomAvifTestResources") {
    dependsOn(downloadAomAvifTestFiles)
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
    dependsOn(downloadArgonAv1Streams)

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    maxHeapSize = "2g"

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
}

tasks.processTestResources {
    dependsOn(downloadLibavif)

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
    (options as StandardJavadocDocletOptions).also {
        it.jFlags!!.addAll(listOf("-Duser.language=en", "-Duser.country=", "-Duser.variant="))

        it.encoding("UTF-8")
        it.addBooleanOption("html5", true)
        it.addStringOption("Xdoclint:none", "-quiet")

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
        }
    }
}
