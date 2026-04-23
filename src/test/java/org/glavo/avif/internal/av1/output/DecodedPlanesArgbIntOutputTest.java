/*
 * Copyright 2026 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glavo.avif.internal.av1.output;

import org.glavo.avif.decode.ArgbIntFrame;
import org.glavo.avif.decode.FrameType;
import org.glavo.avif.decode.PixelFormat;
import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Contract tests for the first 8-bit ARGB output layer built on `DecodedPlanes`.
///
/// These tests intentionally discover the main-source converter reflectively so they can compile
/// before `org.glavo.avif.internal.av1.output` exists. Once the output package is added, the tests
/// enforce deterministic `I400`, `I420`, `I422`, and `I444` pixel packing behavior.
@NotNullByDefault
final class DecodedPlanesArgbIntOutputTest {
    /// The test frame type supplied to converters that accept frame metadata.
    private static final FrameType TEST_FRAME_TYPE = FrameType.KEY;

    /// The test visibility flag supplied to converters that accept frame metadata.
    private static final boolean TEST_VISIBLE = true;

    /// The test presentation index supplied to converters that accept frame metadata.
    private static final long TEST_PRESENTATION_INDEX = 7L;

    /// The discovered output invoker, or `null` when the main-source output package does not exist yet.
    private static @Nullable OutputInvoker outputInvoker;

    /// Discovers the main-source output converter before running any pixel assertions.
    ///
    /// Missing output classes are treated as a temporary integration gap and skip this test class.
    ///
    /// @throws IOException if package resources cannot be enumerated
    /// @throws ReflectiveOperationException if converter discovery encounters an invalid reflective target
    /// @throws URISyntaxException if package resources cannot be resolved to filesystem paths
    @BeforeAll
    static void discoverOutputInvoker() throws IOException, ReflectiveOperationException, URISyntaxException {
        outputInvoker = OutputInvoker.discover();
        assumeTrue(outputInvoker != null, "No output converter is present yet under org.glavo.avif.internal.av1.output");
    }

    /// Verifies that 8-bit monochrome planes become opaque grayscale ARGB pixels and ignore stride padding.
    ///
    /// @throws ReflectiveOperationException if reflective output invocation fails
    @Test
    void convertsEightBitI400SamplesIntoOpaqueArgbPixels() throws ReflectiveOperationException {
        DecodedPlanes planes = new DecodedPlanes(
                8,
                PixelFormat.I400,
                3,
                2,
                3,
                2,
                plane(3, 2, 4, 0, 64, 255, 9, 12, 128, 200, 7),
                null,
                null
        );

        ConvertedOutput output = requireOutputInvoker().convert(planes);

        assertArrayEquals(
                new int[]{
                        0xFF000000,
                        0xFF404040,
                        0xFFFFFFFF,
                        0xFF0C0C0C,
                        0xFF808080,
                        0xFFC8C8C8
                },
                output.pixels()
        );
        assertOpaquePixels(output.pixels());
        assertFrameMetadata(output.frame(), planes);
    }

    /// Verifies that 8-bit `I420` output reuses one chroma sample for each 2x2 luma block and packs `AARRGGBB`.
    ///
    /// The left block uses neutral chroma so its pixels must stay grayscale. The right block uses strongly
    /// blue-biased chroma so channel extraction can validate the non-premultiplied ARGB byte order without
    /// depending on one exact rounding formula.
    ///
    /// @throws ReflectiveOperationException if reflective output invocation fails
    @Test
    void convertsEightBitI420SamplesUsingSharedChromaIntoOpaqueArgbPixels() throws ReflectiveOperationException {
        DecodedPlanes planes = new DecodedPlanes(
                8,
                PixelFormat.I420,
                4,
                2,
                4,
                2,
                plane(4, 2, 5, 100, 100, 100, 100, 3, 100, 100, 100, 100, 4),
                plane(2, 1, 3, 128, 255, 5),
                plane(2, 1, 3, 128, 0, 6)
        );

        ConvertedOutput output = requireOutputInvoker().convert(planes);
        int[] pixels = output.pixels();

        assertEquals(8, pixels.length);
        assertEquals(0xFF646464, pixels[0]);
        assertEquals(0xFF646464, pixels[1]);
        assertEquals(0xFF646464, pixels[4]);
        assertEquals(0xFF646464, pixels[5]);

        assertEquals(pixels[2], pixels[3]);
        assertEquals(pixels[2], pixels[6]);
        assertEquals(pixels[2], pixels[7]);
        assertNotEquals(0xFF646464, pixels[2]);
        assertEquals(0xFF, alpha(pixels[2]));
        assertTrue(blue(pixels[2]) > green(pixels[2]));
        assertTrue(green(pixels[2]) > red(pixels[2]));

        assertOpaquePixels(pixels);
        assertFrameMetadata(output.frame(), planes);
    }

    /// Verifies that 8-bit `I422` output shares chroma horizontally within each row but not across rows.
    ///
    /// The top row uses neutral chroma for the left pair and blue-biased chroma for the right pair. The
    /// bottom row switches to two different chroma pairs so the expected pixels also catch accidental
    /// `I420`-style vertical chroma reuse.
    ///
    /// @throws ReflectiveOperationException if reflective output invocation fails
    @Test
    void convertsEightBitI422SamplesUsingRowSpecificHorizontallySharedChromaIntoOpaqueArgbPixels()
            throws ReflectiveOperationException {
        DecodedPlanes planes = new DecodedPlanes(
                8,
                PixelFormat.I422,
                4,
                2,
                4,
                2,
                plane(4, 2, 5, 40, 90, 140, 190, 1, 60, 110, 160, 210, 2),
                plane(2, 2, 3, 128, 180, 3, 90, 150, 4),
                plane(2, 2, 3, 128, 70, 5, 220, 160, 6)
        );

        ConvertedOutput output = requireOutputInvoker().convert(planes);

        assertArrayEquals(
                new int[]{
                        0xFF282828,
                        0xFF5A5A5A,
                        0xFF3BA4E8,
                        0xFF6DD6FF,
                        0xFFBD0700,
                        0xFFEF392B,
                        0xFFCD82C7,
                        0xFFFFB4F9
                },
                output.pixels()
        );
        assertOpaquePixels(output.pixels());
        assertFrameMetadata(output.frame(), planes);
    }

    /// Verifies that 8-bit `I444` output uses one chroma pair per luma sample with no subsampling.
    ///
    /// Every visible pixel uses a different YUV triplet, while stride padding stays outside the render
    /// rectangle. Exact packed pixels ensure the converter preserves the intended `AARRGGBB` byte order.
    ///
    /// @throws ReflectiveOperationException if reflective output invocation fails
    @Test
    void convertsEightBitI444SamplesUsingPerPixelChromaIntoOpaqueArgbPixels() throws ReflectiveOperationException {
        DecodedPlanes planes = new DecodedPlanes(
                8,
                PixelFormat.I444,
                4,
                2,
                4,
                2,
                plane(4, 2, 5, 20, 80, 140, 200, 1, 35, 95, 155, 215, 2),
                plane(4, 2, 5, 128, 160, 96, 200, 3, 140, 110, 180, 70, 4),
                plane(4, 2, 5, 128, 90, 210, 40, 5, 150, 70, 100, 220, 6)
        );

        ConvertedOutput output = requireOutputInvoker().convert(planes);

        assertArrayEquals(
                new int[]{
                        0xFF141414,
                        0xFF1B6089,
                        0xFFFF5C53,
                        0xFF4DEEFF,
                        0xFF420F38,
                        0xFF0E8F3F,
                        0xFF749DF7,
                        0xFFFFA970
                },
                output.pixels()
        );
        assertOpaquePixels(output.pixels());
        assertFrameMetadata(output.frame(), planes);
    }

    /// Returns the discovered output invoker.
    ///
    /// @return the discovered output invoker
    private static OutputInvoker requireOutputInvoker() {
        return Objects.requireNonNull(outputInvoker, "outputInvoker");
    }

    /// Asserts opaque `0xFF` alpha for every packed ARGB pixel.
    ///
    /// @param pixels the packed ARGB pixels to validate
    private static void assertOpaquePixels(int[] pixels) {
        for (int pixel : pixels) {
            assertEquals(0xFF, alpha(pixel));
        }
    }

    /// Asserts frame metadata when the converter already returns an `ArgbIntFrame`.
    ///
    /// Plain pixel packers are allowed temporarily while the output package is still being wired
    /// into the public frame layer.
    ///
    /// @param frame the returned frame, or `null` for temporary pixel-only converters
    /// @param planes the source decoded planes
    private static void assertFrameMetadata(@Nullable ArgbIntFrame frame, DecodedPlanes planes) {
        if (frame == null) {
            return;
        }

        assertEquals(planes.renderWidth(), frame.width());
        assertEquals(planes.renderHeight(), frame.height());
        assertEquals(planes.bitDepth(), frame.bitDepth());
        assertEquals(planes.pixelFormat(), frame.pixelFormat());

        OutputInvoker invoker = requireOutputInvoker();
        if (invoker.acceptsFrameType()) {
            assertEquals(TEST_FRAME_TYPE, frame.frameType());
        }
        if (invoker.acceptsVisible()) {
            assertEquals(TEST_VISIBLE, frame.visible());
        }
        if (invoker.acceptsPresentationIndex()) {
            assertEquals(TEST_PRESENTATION_INDEX, frame.presentationIndex());
        }
    }

    /// Creates one immutable decoded plane from unsigned integer sample values.
    ///
    /// @param width the plane width in samples
    /// @param height the plane height in samples
    /// @param stride the plane stride in samples
    /// @param values the unsigned sample values in row-major order
    /// @return one immutable decoded plane
    private static DecodedPlane plane(int width, int height, int stride, int... values) {
        short[] samples = new short[values.length];
        for (int i = 0; i < values.length; i++) {
            samples[i] = (short) values[i];
        }
        return new DecodedPlane(width, height, stride, samples);
    }

    /// Returns the packed alpha component from one `0xAARRGGBB` pixel.
    ///
    /// @param pixel the packed ARGB pixel
    /// @return the packed alpha component
    private static int alpha(int pixel) {
        return (pixel >>> 24) & 0xFF;
    }

    /// Returns the packed red component from one `0xAARRGGBB` pixel.
    ///
    /// @param pixel the packed ARGB pixel
    /// @return the packed red component
    private static int red(int pixel) {
        return (pixel >>> 16) & 0xFF;
    }

    /// Returns the packed green component from one `0xAARRGGBB` pixel.
    ///
    /// @param pixel the packed ARGB pixel
    /// @return the packed green component
    private static int green(int pixel) {
        return (pixel >>> 8) & 0xFF;
    }

    /// Returns the packed blue component from one `0xAARRGGBB` pixel.
    ///
    /// @param pixel the packed ARGB pixel
    /// @return the packed blue component
    private static int blue(int pixel) {
        return pixel & 0xFF;
    }

    /// Reflective adapter for one discovered output-conversion entry point.
    @NotNullByDefault
    private static final class OutputInvoker {
        /// The converter method to invoke.
        private final Method method;

        /// The zero-argument constructor used for instance methods, or `null` for static methods.
        private final @Nullable Constructor<?> constructor;

        /// Whether the converter accepts one `FrameType` argument.
        private final boolean acceptsFrameType;

        /// Whether the converter accepts one visibility flag.
        private final boolean acceptsVisible;

        /// Whether the converter accepts one presentation-index argument.
        private final boolean acceptsPresentationIndex;

        /// Creates one reflective output invoker.
        ///
        /// @param method the converter method to invoke
        /// @param constructor the constructor used for instance methods, or `null` for static methods
        /// @param acceptsFrameType whether the converter accepts one `FrameType` argument
        /// @param acceptsVisible whether the converter accepts one visibility flag
        /// @param acceptsPresentationIndex whether the converter accepts one presentation-index argument
        private OutputInvoker(
                Method method,
                @Nullable Constructor<?> constructor,
                boolean acceptsFrameType,
                boolean acceptsVisible,
                boolean acceptsPresentationIndex
        ) {
            this.method = method;
            this.constructor = constructor;
            this.acceptsFrameType = acceptsFrameType;
            this.acceptsVisible = acceptsVisible;
            this.acceptsPresentationIndex = acceptsPresentationIndex;
        }

        /// Discovers the best available output-conversion entry point in the main-source output package.
        ///
        /// @return the discovered output invoker, or `null` if no compatible converter exists yet
        /// @throws IOException if package resources cannot be enumerated
        /// @throws ReflectiveOperationException if converter discovery encounters an invalid reflective target
        /// @throws URISyntaxException if package resources cannot be resolved to filesystem paths
        public static @Nullable OutputInvoker discover() throws IOException, ReflectiveOperationException, URISyntaxException {
            List<Candidate> candidates = new ArrayList<>();
            for (Class<?> outputClass : loadOutputClasses()) {
                for (Method method : outputClass.getDeclaredMethods()) {
                    @Nullable Candidate candidate = Candidate.create(outputClass, method);
                    if (candidate != null) {
                        candidates.add(candidate);
                    }
                }
            }

            if (candidates.isEmpty()) {
                return null;
            }

            return candidates.stream().max(Comparator.naturalOrder()).orElseThrow().toInvoker();
        }

        /// Converts one decoded-plane snapshot with the discovered converter.
        ///
        /// @param planes the decoded planes to convert
        /// @return the converted pixel payload
        /// @throws ReflectiveOperationException if reflective invocation fails
        public ConvertedOutput convert(DecodedPlanes planes) throws ReflectiveOperationException {
            Object target = constructor == null ? null : newInstance(constructor);
            Object[] arguments = buildArguments(planes);
            Object result = invoke(method, target, arguments);
            if (result instanceof ArgbIntFrame frame) {
                return new ConvertedOutput(frame, frame.pixels());
            }
            if (result instanceof int[] pixels) {
                return new ConvertedOutput(null, pixels);
            }
            throw new AssertionError("Unsupported output type: " + result.getClass().getName());
        }

        /// Returns whether the converter accepts one `FrameType` argument.
        ///
        /// @return whether the converter accepts one `FrameType` argument
        public boolean acceptsFrameType() {
            return acceptsFrameType;
        }

        /// Returns whether the converter accepts one visibility flag.
        ///
        /// @return whether the converter accepts one visibility flag
        public boolean acceptsVisible() {
            return acceptsVisible;
        }

        /// Returns whether the converter accepts one presentation-index argument.
        ///
        /// @return whether the converter accepts one presentation-index argument
        public boolean acceptsPresentationIndex() {
            return acceptsPresentationIndex;
        }

        /// Builds the argument array for one converter invocation.
        ///
        /// @param planes the decoded planes to pass to the converter
        /// @return the full reflective argument array
        private Object[] buildArguments(DecodedPlanes planes) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            Object[] arguments = new Object[parameterTypes.length];
            arguments[0] = planes;
            for (int i = 1; i < parameterTypes.length; i++) {
                Class<?> parameterType = parameterTypes[i];
                if (parameterType == FrameType.class) {
                    arguments[i] = TEST_FRAME_TYPE;
                } else if (parameterType == boolean.class || parameterType == Boolean.class) {
                    arguments[i] = TEST_VISIBLE;
                } else if (parameterType == long.class || parameterType == Long.class) {
                    arguments[i] = TEST_PRESENTATION_INDEX;
                } else if (parameterType == int.class || parameterType == Integer.class) {
                    arguments[i] = (int) TEST_PRESENTATION_INDEX;
                } else {
                    throw new AssertionError("Unsupported converter parameter: " + parameterType.getName());
                }
            }
            return arguments;
        }

        /// Loads concrete classes from `org.glavo.avif.internal.av1.output`.
        ///
        /// @return the discovered output classes
        /// @throws IOException if package resources cannot be enumerated
        /// @throws URISyntaxException if package resources cannot be resolved to filesystem paths
        /// @throws ClassNotFoundException if a listed class cannot be loaded
        private static List<Class<?>> loadOutputClasses() throws IOException, URISyntaxException, ClassNotFoundException {
            List<Class<?>> classes = new ArrayList<>();
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            Enumeration<URL> resources = classLoader.getResources("org/glavo/avif/internal/av1/output");
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                if (!"file".equals(resource.getProtocol())) {
                    continue;
                }
                Path directory = Path.of(resource.toURI());
                if (!Files.isDirectory(directory)) {
                    continue;
                }
                try (Stream<Path> entries = Files.list(directory)) {
                    for (Path entry : entries.toList()) {
                        String fileName = entry.getFileName().toString();
                        if (!fileName.endsWith(".class") || fileName.contains("$")) {
                            continue;
                        }
                        String className = "org.glavo.avif.internal.av1.output."
                                + fileName.substring(0, fileName.length() - 6);
                        classes.add(Class.forName(className));
                    }
                }
            }
            return classes;
        }

        /// Creates one instance for a non-static converter method.
        ///
        /// @param constructor the zero-argument constructor to invoke
        /// @return the new converter instance
        /// @throws ReflectiveOperationException if reflective construction fails
        private static Object newInstance(Constructor<?> constructor) throws ReflectiveOperationException {
            try {
                constructor.trySetAccessible();
                return constructor.newInstance();
            } catch (InvocationTargetException exception) {
                throw rethrowTargetException(exception);
            }
        }

        /// Invokes one converter method.
        ///
        /// @param method the converter method to invoke
        /// @param target the invocation target, or `null` for static methods
        /// @param arguments the reflective arguments
        /// @return the converter result
        /// @throws ReflectiveOperationException if reflective invocation fails
        private static Object invoke(Method method, @Nullable Object target, Object[] arguments)
                throws ReflectiveOperationException {
            try {
                method.trySetAccessible();
                return method.invoke(target, arguments);
            } catch (InvocationTargetException exception) {
                throw rethrowTargetException(exception);
            }
        }

        /// Re-throws one reflective target exception as a checked reflective failure.
        ///
        /// @param exception the wrapper thrown by reflection
        /// @return never returns normally
        /// @throws ReflectiveOperationException always
        private static ReflectiveOperationException rethrowTargetException(InvocationTargetException exception)
                throws ReflectiveOperationException {
            Throwable cause = exception.getTargetException();
            if (cause instanceof ReflectiveOperationException reflectiveOperationException) {
                throw reflectiveOperationException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new ReflectiveOperationException(cause);
        }
    }

    /// Comparable description of one compatible converter method candidate.
    @NotNullByDefault
    private static final class Candidate implements Comparable<Candidate> {
        /// The candidate converter method.
        private final Method method;

        /// The zero-argument constructor for instance methods, or `null` for static methods.
        private final @Nullable Constructor<?> constructor;

        /// The selection score for this candidate.
        private final int score;

        /// Whether the converter accepts one `FrameType` argument.
        private final boolean acceptsFrameType;

        /// Whether the converter accepts one visibility flag.
        private final boolean acceptsVisible;

        /// Whether the converter accepts one presentation-index argument.
        private final boolean acceptsPresentationIndex;

        /// Creates one candidate.
        ///
        /// @param method the candidate method
        /// @param constructor the zero-argument constructor for instance methods, or `null` for static methods
        /// @param score the selection score for this candidate
        /// @param acceptsFrameType whether the converter accepts one `FrameType` argument
        /// @param acceptsVisible whether the converter accepts one visibility flag
        /// @param acceptsPresentationIndex whether the converter accepts one presentation-index argument
        private Candidate(
                Method method,
                @Nullable Constructor<?> constructor,
                int score,
                boolean acceptsFrameType,
                boolean acceptsVisible,
                boolean acceptsPresentationIndex
        ) {
            this.method = method;
            this.constructor = constructor;
            this.score = score;
            this.acceptsFrameType = acceptsFrameType;
            this.acceptsVisible = acceptsVisible;
            this.acceptsPresentationIndex = acceptsPresentationIndex;
        }

        /// Creates one candidate from a discovered method when the signature is compatible.
        ///
        /// @param ownerClass the declaring class
        /// @param method the discovered method
        /// @return the compatible candidate, or `null` if the method does not match the expected contract
        public static @Nullable Candidate create(Class<?> ownerClass, Method method) {
            Class<?> returnType = method.getReturnType();
            if (returnType != ArgbIntFrame.class && returnType != int[].class) {
                return null;
            }

            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length == 0 || parameterTypes[0] != DecodedPlanes.class) {
                return null;
            }

            boolean acceptsFrameType = false;
            boolean acceptsVisible = false;
            boolean acceptsPresentationIndex = false;
            for (int i = 1; i < parameterTypes.length; i++) {
                Class<?> parameterType = parameterTypes[i];
                if (parameterType == FrameType.class) {
                    if (acceptsFrameType) {
                        return null;
                    }
                    acceptsFrameType = true;
                } else if (parameterType == boolean.class || parameterType == Boolean.class) {
                    if (acceptsVisible) {
                        return null;
                    }
                    acceptsVisible = true;
                } else if (parameterType == long.class || parameterType == Long.class
                        || parameterType == int.class || parameterType == Integer.class) {
                    if (acceptsPresentationIndex) {
                        return null;
                    }
                    acceptsPresentationIndex = true;
                } else {
                    return null;
                }
            }

            @Nullable Constructor<?> constructor = null;
            if (!Modifier.isStatic(method.getModifiers())) {
                try {
                    constructor = ownerClass.getDeclaredConstructor();
                } catch (NoSuchMethodException exception) {
                    return null;
                }
            }

            int score = score(ownerClass, method);
            return new Candidate(method, constructor, score, acceptsFrameType, acceptsVisible, acceptsPresentationIndex);
        }

        /// Converts this candidate into an executable output invoker.
        ///
        /// @return the executable output invoker
        public OutputInvoker toInvoker() {
            return new OutputInvoker(method, constructor, acceptsFrameType, acceptsVisible, acceptsPresentationIndex);
        }

        /// Compares two candidates by score and then by stable reflective identity.
        ///
        /// @param other the other candidate
        /// @return the comparison result
        @Override
        public int compareTo(Candidate other) {
            int scoreComparison = Integer.compare(score, other.score);
            if (scoreComparison != 0) {
                return scoreComparison;
            }

            int classComparison = method.getDeclaringClass().getName()
                    .compareTo(other.method.getDeclaringClass().getName());
            if (classComparison != 0) {
                return classComparison;
            }

            int methodComparison = method.getName().compareTo(other.method.getName());
            if (methodComparison != 0) {
                return methodComparison;
            }

            return Integer.compare(method.getParameterCount(), other.method.getParameterCount());
        }

        /// Computes the selection score for one compatible candidate.
        ///
        /// @param ownerClass the declaring class
        /// @param method the candidate method
        /// @return the candidate score
        private static int score(Class<?> ownerClass, Method method) {
            int score = method.getReturnType() == ArgbIntFrame.class ? 1_000 : 500;
            if (Modifier.isPublic(method.getModifiers())) {
                score += 100;
            }
            if (Modifier.isStatic(method.getModifiers())) {
                score += 50;
            }

            String ownerName = ownerClass.getSimpleName().toLowerCase(Locale.ROOT);
            String methodName = method.getName().toLowerCase(Locale.ROOT);
            if (ownerName.contains("argb")) {
                score += 20;
            }
            if (methodName.contains("argb")) {
                score += 20;
            }
            if (methodName.contains("convert")) {
                score += 10;
            }
            if (methodName.contains("frame")) {
                score += 5;
            }

            return score - method.getParameterCount();
        }
    }

    /// The converted result used by the tests.
    @NotNullByDefault
    private static final class ConvertedOutput {
        /// The converted frame, or `null` when the converter currently exposes only packed pixels.
        private final @Nullable ArgbIntFrame frame;

        /// The packed non-premultiplied ARGB pixels.
        private final int[] pixels;

        /// Creates one converted output snapshot.
        ///
        /// @param frame the converted frame, or `null` for pixel-only converters
        /// @param pixels the packed non-premultiplied ARGB pixels
        private ConvertedOutput(@Nullable ArgbIntFrame frame, int[] pixels) {
            this.frame = frame;
            this.pixels = Objects.requireNonNull(pixels, "pixels");
        }

        /// Returns the converted frame, or `null` when only pixels are available.
        ///
        /// @return the converted frame, or `null`
        public @Nullable ArgbIntFrame frame() {
            return frame;
        }

        /// Returns the packed non-premultiplied ARGB pixels.
        ///
        /// @return the packed non-premultiplied ARGB pixels
        public int[] pixels() {
            return pixels;
        }
    }
}
