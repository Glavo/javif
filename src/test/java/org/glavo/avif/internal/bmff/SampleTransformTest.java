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
package org.glavo.avif.internal.bmff;

import org.glavo.avif.AvifBitDepth;
import org.glavo.avif.Av1ChromaFormat;
import org.glavo.avif.DecodedPlane;
import org.glavo.avif.DecodedPlanes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests Sample Transform postfix expression evaluation and validation.
@NotNullByDefault
final class SampleTransformTest {
    /// The constant operand token.
    private static final int CONSTANT = 0;
    /// The unary negation token.
    private static final int NEGATION = 64;
    /// The unary absolute-value token.
    private static final int ABSOLUTE = 65;
    /// The unary bitwise-not token.
    private static final int NOT = 66;
    /// The unary bit-scan-reverse token.
    private static final int BIT_SCAN_REVERSE = 67;
    /// The binary addition token.
    private static final int SUM = 128;
    /// The binary subtraction token.
    private static final int DIFFERENCE = 129;
    /// The binary multiplication token.
    private static final int PRODUCT = 130;
    /// The binary division token.
    private static final int QUOTIENT = 131;
    /// The binary bitwise-and token.
    private static final int AND = 132;
    /// The binary bitwise-or token.
    private static final int OR = 133;
    /// The binary bitwise-exclusive-or token.
    private static final int XOR = 134;
    /// The binary exponentiation token.
    private static final int POWER = 135;
    /// The binary minimum token.
    private static final int MINIMUM = 136;
    /// The binary maximum token.
    private static final int MAXIMUM = 137;

    /// Verifies all defined unary and binary operators, including their edge cases.
    @Test
    void evaluatesEveryDefinedOperator() {
        assertEquals(5, evaluate(
                new int[]{CONSTANT, NEGATION, CONSTANT, SUM},
                new long[]{5, 0, 10, 0},
                new int[]{0}
        ));
        assertEquals(7, evaluate(new int[]{CONSTANT, ABSOLUTE}, new long[]{-7, 0}, new int[]{0}));
        assertEquals(1, evaluate(
                new int[]{CONSTANT, NOT, CONSTANT, SUM},
                new long[]{0, 0, 2, 0},
                new int[]{0}
        ));
        assertEquals(4, evaluate(new int[]{CONSTANT, BIT_SCAN_REVERSE}, new long[]{16, 0}, new int[]{0}));
        assertEquals(0, evaluate(new int[]{CONSTANT, BIT_SCAN_REVERSE}, new long[]{-1, 0}, new int[]{0}));

        assertEquals(12, evaluate(binary(SUM), constants(7, 5), new int[]{0}));
        assertEquals(2, evaluate(binary(DIFFERENCE), constants(7, 5), new int[]{0}));
        assertEquals(35, evaluate(binary(PRODUCT), constants(7, 5), new int[]{0}));
        assertEquals(3, evaluate(binary(QUOTIENT), constants(7, 2), new int[]{0}));
        assertEquals(7, evaluate(binary(QUOTIENT), constants(7, 0), new int[]{0}));
        assertEquals(2, evaluate(binary(AND), constants(6, 3), new int[]{0}));
        assertEquals(7, evaluate(binary(OR), constants(6, 3), new int[]{0}));
        assertEquals(5, evaluate(binary(XOR), constants(6, 3), new int[]{0}));
        assertEquals(81, evaluate(binary(POWER), constants(3, 4), new int[]{0}));
        assertEquals(0, evaluate(binary(POWER), constants(3, -1), new int[]{0}));
        assertEquals(3, evaluate(binary(MINIMUM), constants(6, 3), new int[]{0}));
        assertEquals(6, evaluate(binary(MAXIMUM), constants(6, 3), new int[]{0}));
    }

    /// Verifies input operands, signed saturation, and final unsigned clamping.
    @Test
    void evaluatesInputsAndClampsResults() {
        assertEquals(300, evaluate(new int[]{1, 2, SUM}, new long[3], new int[]{100, 200}));
        assertEquals(65535, evaluate(binary(SUM), constants(Integer.MAX_VALUE, 1), new int[]{0}));
        assertEquals(65535, evaluate(binary(PRODUCT), constants(Integer.MAX_VALUE, 2), new int[]{0}));
        assertEquals(65535, evaluate(binary(POWER), constants(2, 31), new int[]{0}));
        assertEquals(0, evaluate(binary(DIFFERENCE), constants(1, 2), new int[]{0}));
        assertEquals(65535, evaluate(
                new int[]{CONSTANT, ABSOLUTE},
                new long[]{Integer.MIN_VALUE, 0},
                new int[]{0}
        ));
    }

    /// Verifies every signaled intermediate width saturates at its own signed bounds.
    @Test
    void saturatesEveryIntermediateBitDepth() {
        assertEquals(127, evaluate(8, binary(SUM), constants(100, 100), new int[]{0}));
        assertEquals(32_767, evaluate(16, binary(SUM), constants(20_000, 20_000), new int[]{0}));
        assertEquals(65_535, evaluate(32, binary(SUM), constants(Integer.MAX_VALUE, 1), new int[]{0}));
        assertEquals(65_535, evaluate(64, binary(SUM), constants(Long.MAX_VALUE, 1), new int[]{0}));
        assertEquals(65_535, evaluate(
                64,
                new int[]{CONSTANT, ABSOLUTE},
                new long[]{Long.MIN_VALUE, 0},
                new int[]{0}
        ));
    }

    /// Verifies large exponents use saturated 64-bit arithmetic without iteration by exponent.
    @Test
    void evaluatesLargePowerInLogarithmicTime() {
        assertEquals(65_535, evaluate(64, binary(POWER), constants(2, Long.MAX_VALUE), new int[]{0}));
        assertEquals(0, evaluate(64, binary(POWER), constants(2, -1), new int[]{0}));
    }

    /// Verifies limited-range color output and full-range alpha output use their nominal bounds.
    @Test
    void clampsColorAndAlphaToTheirDeclaredRanges() {
        SampleTransform lowTransform = new SampleTransform(
                AvifBitDepth.TEN_BITS,
                false,
                32,
                new int[]{CONSTANT},
                new long[]{0},
                inputs(1),
                0
        );
        DecodedPlanes lowColor = lowTransform.apply(new DecodedPlanes[]{threeComponentPlanes()});
        assertEquals(64, lowColor.lumaPlane().sample(0, 0));
        assertEquals(64, Objects.requireNonNull(lowColor.chromaUPlane(), "lowColor.chromaUPlane").sample(0, 0));
        assertEquals(64, Objects.requireNonNull(lowColor.chromaVPlane(), "lowColor.chromaVPlane").sample(0, 0));

        SampleTransform highTransform = new SampleTransform(
                AvifBitDepth.TEN_BITS,
                false,
                32,
                new int[]{CONSTANT},
                new long[]{2_000},
                inputs(1),
                0
        );
        DecodedPlanes highColor = highTransform.apply(new DecodedPlanes[]{threeComponentPlanes()});
        assertEquals(940, highColor.lumaPlane().sample(0, 0));
        assertEquals(960, Objects.requireNonNull(highColor.chromaUPlane(), "highColor.chromaUPlane").sample(0, 0));
        assertEquals(960, Objects.requireNonNull(highColor.chromaVPlane(), "highColor.chromaVPlane").sample(0, 0));
        assertEquals(1_023, highTransform.applyAlpha(new DecodedPlanes[]{monochromePlanes(0)})
                .lumaPlane().sample(0, 0));
        assertFalse(highTransform.fullRange());
    }

    /// Verifies malformed postfix expressions are rejected during construction.
    @Test
    void rejectsMalformedExpressions() {
        assertThrows(IllegalArgumentException.class, () -> createTransform(new int[0], new long[0], 1));
        assertThrows(IllegalArgumentException.class,
                () -> createTransform(new int[]{2}, new long[1], 1));
        assertThrows(IllegalArgumentException.class,
                () -> createTransform(new int[]{63}, new long[1], 1));
        assertThrows(IllegalArgumentException.class,
                () -> createTransform(new int[]{SUM}, new long[1], 1));
        assertThrows(IllegalArgumentException.class,
                () -> createTransform(new int[]{CONSTANT, CONSTANT}, new long[2], 1));
        assertThrows(IllegalArgumentException.class,
                () -> new SampleTransform(
                        AvifBitDepth.SIXTEEN_BITS,
                        true,
                        32,
                        new int[]{CONSTANT},
                        new long[0],
                        inputs(1),
                        0
                ));
        assertThrows(IllegalArgumentException.class,
                () -> new SampleTransform(
                        AvifBitDepth.SIXTEEN_BITS,
                        true,
                        24,
                        new int[]{CONSTANT},
                        new long[]{0},
                        inputs(1),
                        0
                ));
        assertThrows(IllegalArgumentException.class,
                () -> new SampleTransform(
                        AvifBitDepth.SIXTEEN_BITS,
                        true,
                        8,
                        new int[]{CONSTANT},
                        new long[]{128},
                        inputs(1),
                        0
                ));
    }

    /// Creates a two-constant binary expression token sequence.
    ///
    /// @param operator the binary operator token
    /// @return the postfix token sequence
    private static int @Unmodifiable [] binary(int operator) {
        return new int[]{CONSTANT, CONSTANT, operator};
    }

    /// Creates constant storage for a two-constant binary expression.
    ///
    /// @param left the left constant
    /// @param right the right constant
    /// @return token-aligned constant storage
    private static long @Unmodifiable [] constants(long left, long right) {
        return new long[]{left, right, 0};
    }

    /// Evaluates one expression against one-pixel monochrome inputs.
    ///
    /// @param tokenCodes the postfix token sequence
    /// @param constantValues token-aligned constant storage
    /// @param inputSamples one unsigned sample per input image
    /// @return the reconstructed unsigned sample
    private static int evaluate(
            int @Unmodifiable [] tokenCodes,
            long @Unmodifiable [] constantValues,
            int @Unmodifiable [] inputSamples
    ) {
        return evaluate(32, tokenCodes, constantValues, inputSamples);
    }

    /// Evaluates one expression with an explicit intermediate bit depth.
    ///
    /// @param intermediateBitDepth the signed intermediate arithmetic bit depth
    /// @param tokenCodes the postfix token sequence
    /// @param constantValues token-aligned constant storage
    /// @param inputSamples one unsigned sample per input image
    /// @return the reconstructed unsigned sample
    private static int evaluate(
            int intermediateBitDepth,
            int @Unmodifiable [] tokenCodes,
            long @Unmodifiable [] constantValues,
            int @Unmodifiable [] inputSamples
    ) {
        SampleTransform transform = createTransform(
                intermediateBitDepth,
                tokenCodes,
                constantValues,
                inputSamples.length
        );
        DecodedPlanes[] planes = new DecodedPlanes[inputSamples.length];
        for (int i = 0; i < inputSamples.length; i++) {
            planes[i] = monochromePlanes(inputSamples[i]);
        }
        return transform.apply(planes).lumaPlane().sample(0, 0);
    }

    /// Creates a Sample Transform with the requested number of placeholder source descriptors.
    ///
    /// @param tokenCodes the postfix token sequence
    /// @param constantValues token-aligned constant storage
    /// @param inputCount the input descriptor count
    /// @return the Sample Transform
    private static SampleTransform createTransform(
            int @Unmodifiable [] tokenCodes,
            long @Unmodifiable [] constantValues,
            int inputCount
    ) {
        return createTransform(32, tokenCodes, constantValues, inputCount);
    }

    /// Creates a Sample Transform with an explicit intermediate bit depth.
    ///
    /// @param intermediateBitDepth the signed intermediate arithmetic bit depth
    /// @param tokenCodes the postfix token sequence
    /// @param constantValues token-aligned constant storage
    /// @param inputCount the input descriptor count
    /// @return the Sample Transform
    private static SampleTransform createTransform(
            int intermediateBitDepth,
            int @Unmodifiable [] tokenCodes,
            long @Unmodifiable [] constantValues,
            int inputCount
    ) {
        return new SampleTransform(
                AvifBitDepth.SIXTEEN_BITS,
                true,
                intermediateBitDepth,
                tokenCodes,
                constantValues,
                inputs(inputCount),
                0
        );
    }

    /// Creates placeholder input descriptors.
    ///
    /// @param inputCount the descriptor count
    /// @return the input descriptors
    private static SampleTransform.Input @Unmodifiable [] inputs(int inputCount) {
        SampleTransform.Input[] inputs = new SampleTransform.Input[inputCount];
        for (int i = 0; i < inputCount; i++) {
            inputs[i] = new SampleTransform.Input(
                    AvifImageSource.item(new byte[]{0}, 0, 1, 1),
                    AvifBitDepth.EIGHT_BITS,
                    null
            );
        }
        return inputs;
    }

    /// Creates a one-pixel monochrome plane set.
    ///
    /// @param sample the unsigned input sample
    /// @return the decoded plane set
    private static DecodedPlanes monochromePlanes(int sample) {
        return new DecodedPlanes(
                AvifBitDepth.SIXTEEN_BITS,
                Av1ChromaFormat.MONOCHROME,
                1,
                1,
                1,
                1,
                new DecodedPlane(1, 1, 1, new short[]{(short) sample}),
                null,
                null
        );
    }

    /// Creates one-pixel three-component input planes for constant-only expressions.
    ///
    /// @return the decoded plane set
    private static DecodedPlanes threeComponentPlanes() {
        DecodedPlane plane = new DecodedPlane(1, 1, 1, new short[]{0});
        return new DecodedPlanes(
                AvifBitDepth.EIGHT_BITS,
                Av1ChromaFormat.YUV444,
                1,
                1,
                1,
                1,
                plane,
                plane,
                plane
        );
    }
}
