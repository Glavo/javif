# javif

`javif` is a Java 17 decoder for AV1 low-overhead bitstreams, AVIF still images, and AVIS image
sequences. The core API is implemented in pure Java and has no runtime dependency outside the
`java.base` module. An optional JavaFX adapter and desktop viewer are included in the same module.

## Features

- Decodes 8-, 10-, and 12-bit AV1 content in monochrome, YUV 4:2:0, YUV 4:2:2, and YUV 4:4:4 layouts.
- Reads AVIF items and AVIS tracks, including grids, alpha, depth, gain maps, progressive images,
  presentation transforms, and Exif, XMP, ICC, and CICP metadata.
- Exposes raw YUV planes or packed non-premultiplied 8-bit and 16-bit ARGB pixels.
- Applies loop filtering, CDEF, loop restoration, super-resolution, and optional film grain.
- Provides a JavaFX `WritableImage` adapter and a desktop viewer with animated-sequence timing.
- Runs without native libraries or mandatory third-party runtime dependencies.

The detailed implemented surface, deliberate boundaries, and verification baseline are recorded in
[PLANS.md](PLANS.md).

## Requirements

- JDK 17 or newer.
- JavaFX 21 or newer only when using `org.glavo.avif.javafx` or the desktop viewer.

## Build

Run the release gate with the repository-local Gradle user home:

```text
./gradlew -g .gradle-user-home cleanTest test javadoc assemble verifyNoRuntimeDependencies
```

The main, source, and Javadoc JARs are written to `build/libs`.

## Decode an AVIF image

```java
import org.glavo.avif.AvifFrame;
import org.glavo.avif.AvifImage;

import java.nio.IntBuffer;
import java.nio.file.Path;

AvifImage image = AvifImage.read(Path.of("image.avif"));
AvifFrame frame = image.firstFrame();
IntBuffer argb = frame.intPixelBuffer();

System.out.printf("%dx%d, frames=%d%n",
        image.info().width(), image.info().height(), image.frames().size());
System.out.printf("first ARGB pixel: %08x%n", argb.get(0));
```

Use `new AvifFXImage(image)` to adapt fully decoded content to JavaFX. Use
`AvifImageReader` when frames should be decoded lazily or when raw YUV planes are needed.

Use `AvifImageReader.readRawColorPlanes(int)` when a caller needs the decoded YUV planes instead of
the built-in CICP-to-RGB conversion.

Create a reusable immutable factory when decoding options must differ from the defaults:

```java
import org.glavo.avif.AvifFrame;
import org.glavo.avif.AvifImageReader;
import org.glavo.avif.AvifImageReaderFactory;
import org.glavo.avif.AvifPixelFormat;

import java.nio.file.Path;

AvifImageReaderFactory factory = AvifImageReaderFactory.DEFAULT
        .withOutputPixelFormat(AvifPixelFormat.ARGB_8888)
        .withInputSizeLimit(64L * 1024 * 1024);

try (AvifImageReader reader = factory.open(Path.of("image.avif"))) {
    AvifFrame frame = reader.readFrame(0);
}
```

The JPMS module name is `org.glavo.avif`. Its supported public packages are:

- `org.glavo.avif` for AVIF and AVIS decoding;
- `org.glavo.avif.av1` for raw AV1 low-overhead bitstreams;
- `org.glavo.avif.javafx` for the optional JavaFX adapter and viewer.

## Desktop viewer

Open the viewer without an initial file:

```text
./gradlew -g .gradle-user-home run
```

Or pass an AVIF file directly:

```text
./gradlew -g .gradle-user-home run --args="path/to/image.avif"
```

The viewer supports the file chooser, drag and drop, panning, still images, and timed AVIS playback.

## Extended corpus tests

The ordinary `test` task excludes the large external corpora. Run them explicitly when their pinned
archives are available or can be downloaded:

```text
./gradlew -g .gradle-user-home aomAvifTest
./gradlew -g .gradle-user-home argonAv1Test
./gradlew -g .gradle-user-home firefoxAvifTest
./gradlew -g .gradle-user-home chromiumAvifTest
```

The Firefox and Chromium tasks download small, revision-pinned selections from the browser test
suites and verify aggregate SHA-256 digests before running compatibility tests. They cover color
conversion matrices, bit depths, chroma layouts, alpha, animation, transforms, grids, scalable
images, gain maps, malformed inputs, and crash regressions. See
`src/test/resources/browser-corpora/README.md` for provenance and the deliberately adapted
browser-specific assertions.

The Argon archive is several gigabytes. The `Corpus Check` GitHub Actions workflow therefore keeps
the external corpus gates manual, caches their pinned inputs independently, and runs all 25 Argon
categories as separate matrix jobs. The baseline Argon gate covers all 3,586 reference
streams—including regular, special low-overhead, Annex B core, Large Scale Tile, stress, and
profile-switching streams—plus all 335 malformed conformance streams across the three profiles.
Extended jobs also validate film-grain presentation output and every distinct declared
operating-point output represented by Argon's 89,239 pre-grain and 89,239 film-grain reference
digests. The stress categories use separate long-running CI jobs. The gate uses a 4 GB test heap by
default, can be split by category or narrowed to one stream, and keeps the heap configurable for
constrained or unusually large workers. Shards are one-based and can be combined with
`category/all`:

```text
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1Case=profile0_not_annexb_special/all
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1Case=profile0_not_annexb_special/all -PargonAv1Shard=1/8
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1Case=profile0_not_annexb_special/test17.obu
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1Case=profile0_core/all
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1Case=profile0_core_special/all
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1Case=profile0_error/all
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1Case=profile1_error/all
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1Case=profile2_error/all
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1Case=profile0_large_scale_tile/all -PargonAv1Shard=1/20
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1Case=profile2_large_scale_tile_special/all -PargonAv1Shard=1/12
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1Case=profile_switching/all
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1Case=profile0_stress/all -PargonAv1Shard=1/8
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1Case=profile0_not_annexb/all -PargonAv1OperatingPoint=distinct
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1Case=profile0_not_annexb/test12153.obu -PargonAv1OperatingPoint=all -PargonAv1Output=both
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1Case=profile0_core/all -PargonAv1OperatingPoint=distinct -PargonAv1Output=film-grain -PargonAv1Shard=1/8
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1MaxHeap=6g
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1Case=profile0_not_annexb_special/all -PargonAv1Jfr=build/profiles/argon.jfr
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1Case=profile1_not_annexb_special/test52.obu -PargonAv1TraceFrames
```

## License

This project is licensed under the [Apache License 2.0](LICENSE).
