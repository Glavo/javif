# javif

`javif` is a Java 17 decoder for AV1 low-overhead bitstreams, AVIF still images, and AVIS image
sequences. The core API is implemented in pure Java and has no runtime dependency outside the
`java.base` module. An optional JavaFX adapter and desktop viewer are included in the same module.

## Features

- Decodes 8-, 10-, and 12-bit AV1 content in I400, I420, I422, and I444 layouts.
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
import org.glavo.avif.AvifImageInfo;
import org.glavo.avif.AvifImageReader;

import java.nio.IntBuffer;
import java.nio.file.Path;

try (AvifImageReader reader = AvifImageReader.open(Path.of("image.avif"))) {
    AvifImageInfo info = reader.info();
    AvifFrame frame = reader.readFrame(0);
    IntBuffer argb = frame.intPixelBuffer();

    System.out.printf("%dx%d, frames=%d%n", info.width(), info.height(), info.frameCount());
    System.out.printf("first ARGB pixel: %08x%n", argb.get(0));
}
```

Use `AvifImageReader.readRawColorPlanes(int)` when a caller needs the decoded YUV planes instead of
the built-in CICP-to-RGB conversion.

The JPMS module name is `org.glavo.avif`. Its supported public packages are:

- `org.glavo.avif` for AVIF and AVIS decoding;
- `org.glavo.avif.decode` for raw AV1 low-overhead bitstreams;
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
```

The second archive is several gigabytes. The `Corpus Check` GitHub Actions workflow therefore keeps
both corpus gates manual, caches their pinned archives independently, and runs the twelve Argon
categories as separate matrix jobs. The Argon gate covers all regular and special low-overhead
streams plus the Annex B core and core-special streams for all three profiles, and uses a 4 GB test
heap by default. It can be split by category or narrowed to one stream, and the heap remains
configurable for constrained or unusually large workers. Shards are one-based and can be combined
with `category/all`:

```text
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1Case=profile0_not_annexb_special/all
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1Case=profile0_not_annexb_special/all -PargonAv1Shard=1/8
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1Case=profile0_not_annexb_special/test17.obu
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1Case=profile0_core/all
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1Case=profile0_core_special/all
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1MaxHeap=6g
./gradlew -g .gradle-user-home argonAv1Test -PargonAv1Case=profile1_not_annexb_special/test52.obu -PargonAv1TraceFrames
```

## License

This project is licensed under the [Apache License 2.0](LICENSE).
