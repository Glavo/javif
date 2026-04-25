plugins {
    id("java-library")
    id("jacoco")
    id("maven-publish")
    id("signing")
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
    id("org.glavo.load-maven-publish-properties") version "0.1.0"
    id("de.undercouch.download") version "5.7.0"
}

group = "org.glavo"

if (version == Project.DEFAULT_VERSION) {
    version = "0.1.0" + "-SNAPSHOT"
}

description = "Pure Java implementation of AV1 decoding and AVIF reading library"

repositories {
    mavenCentral()
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

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(17)
}

val mainClassName = "org.glavo.avif.javafx.AvifViewerApp"

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs the JavaFX AVIF viewer."
    dependsOn(tasks.classes)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set(mainClassName)
}

tasks.test {
    useJUnitPlatform()
    testLogging.showStandardStreams = true
    systemProperty(
        "org.bytedeco.javacpp.cachedir",
        layout.buildDirectory.dir("javacpp-cache").get().asFile.absolutePath,
    )

    if (this.javaVersion >= JavaVersion.VERSION_25) {
        jvmArgs("--enable-native-access=javafx.graphics")
    }
}

val dav1dCommit = "c5726277ffa8764665ea08f865e46912a41f2309"
val libavifCommit = "b54eac58daf563e9150cc6abce7631ac71b999aa"
val libavifZip = layout.buildDirectory.file("downloads/libavif-$libavifCommit.zip")

val downloadLibavif by tasks.registering(de.undercouch.gradle.tasks.download.Download::class) {
    src("https://github.com/AOMediaCodec/libavif/archive/$libavifCommit.zip")
    dest(libavifZip)
    overwrite(false)
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
        it.addStringOption("link", "https://docs.oracle.com/en/java/javase/25/docs/api/")
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
        url.set("https://github.com/Glavo/jwebp")

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
            url.set("https://github.com/Glavo/jwebp")
        }
    }
}
