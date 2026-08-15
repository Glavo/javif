# javif - Pure Java AV1 and AVIF Decoder

[![](https://img.shields.io/maven-central/v/org.glavo/avif?label=Maven%20Central)](https://search.maven.org/artifact/org.glavo/avif)
[![javadoc](https://javadoc.io/badge2/org.glavo/avif/javadoc.svg)](https://javadoc.io/doc/org.glavo/avif)

A dependency-free, pure Java AV1 and AVIF decoder library that supports AVIF still images and animated AVIF images.

We have ported and adapted test cases from libaom, libavif, Firefox, and Chromium to verify its correctness.

## Features

- Pure Java implementation with no native dependencies.
- The core decoder only depends on the `java.base` module, with no dependency on other modules.
- Supports 8-bit, 10-bit, and 12-bit AV1 decoding.
- Supports monochrome, YUV 4:2:0, YUV 4:2:2, and YUV 4:4:4 images.
- Supports AVIF still images and animated AVIF images.
- Supports alpha, image grids, progressive images, gain maps, depth images, and presentation transforms.
- Supports ICC, EXIF, XMP, and CICP metadata.
- Provides optional JavaFX integration for displaying still and animated AVIF images.

## Requirements

- Java 17 or newer

## Download

Gradle:

```kotlin
dependencies {
    implementation("org.glavo:avif:0.1.0")
}
```

Maven:

```xml
<dependency>
    <groupId>org.glavo</groupId>
    <artifactId>avif</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Basic Usage

Decode a whole image at once:

```java
AvifImage image = AvifImage.read(Path.of("sample.avif"));
System.out.println(image.info().width() + "x" + image.info().height());
System.out.println("frames = " + image.frames().size());
System.out.printf("first pixel = %08x%n", image.firstFrame().intPixelBuffer().get(0));
```

Stream frames from an animated AVIF image:

```java
try (InputStream input = Files.newInputStream(Path.of("animated.avif"));
     AvifImageReader reader = AvifImageReader.open(input)) {
    while (true) {
        AvifFrame frame = reader.readFrame();
        if (frame == null) {
            break;
        }
        System.out.println("frame = " + frame.frameIndex());
    }
}
```

### JavaFX Integration

javif's core part only depends on the `java.base` module and does not require JavaFX.

javif also provides optional JavaFX components in the `org.glavo.avif.javafx` package,
which can easily convert an `AvifImage` or `AvifFrame` to a JavaFX `Image`:

```java
// Create a JavaFX image from an AvifImage.
// Animated AVIF images automatically start playing.
// Pass false as the second argument to disable autoplay.
javafx.scene.image.Image fxImage = new AvifFXImage(AvifImage.read(Path.of("sample.avif")));

// Create a JavaFX image from an AvifFrame.
javafx.scene.image.Image fxFrame = new AvifFXImage(AvifImage.read(Path.of("sample.avif")).firstFrame());
```

### javif Image Viewer

We provide a sample application: javif Image Viewer.

This is a simple image viewer based on javif. You can use any Java environment containing JavaFX to run it via `java -jar avif-0.1.0.jar`.

You can download the latest version of javif Image Viewer from [GitHub Releases](https://github.com/Glavo/javif/releases).

## Testing

Run all tests:

```powershell
./gradlew test
```

The test suite includes:

- project-local AV1 and AVIF decoder regression tests
- tests ported and adapted from `libaom`, `libavif`, Firefox, and Chromium
- reference comparisons against `libaom`, `libavif`, and FFmpeg
