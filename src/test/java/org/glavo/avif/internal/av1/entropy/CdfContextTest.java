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
package org.glavo.avif.internal.av1.entropy;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/// Tests for `CdfContext`.
@NotNullByDefault
final class CdfContextTest {
    /// Verifies that the default context matches the transformed `dav1d` tables for supported syntax elements.
    @Test
    void createsDefaultContextFromDav1dTables() {
        CdfContext context = CdfContext.createDefault();

        assertArrayEquals(new int[]{1097, 0}, context.mutableSkipCdf(0));
        assertArrayEquals(new int[]{147, 0}, context.mutableSkipModeCdf(0));
        assertArrayEquals(new int[]{12060, 0}, context.mutableSkipModeCdf(1));
        assertArrayEquals(new int[]{24641, 0}, context.mutableSkipModeCdf(2));
        assertArrayEquals(new int[]{16106, 0}, context.mutableIntraCdf(1));
        assertArrayEquals(new int[]{5940, 0}, context.mutableCompoundReferenceCdf(0));
        assertArrayEquals(new int[]{30698, 0}, context.mutableCompoundDirectionCdf(1));
        assertArrayEquals(new int[]{27871, 0}, context.mutableSingleReferenceCdf(0, 0));
        assertArrayEquals(new int[]{23300, 0}, context.mutableCompoundForwardReferenceCdf(1, 0));
        assertArrayEquals(new int[]{30533, 0}, context.mutableCompoundBackwardReferenceCdf(0, 0));
        assertArrayEquals(new int[]{29640, 0}, context.mutableCompoundUnidirectionalReferenceCdf(2, 0));
        assertArrayEquals(new int[]{8733, 0}, context.mutableSingleInterNewMvCdf(0));
        assertArrayEquals(new int[]{30593, 0}, context.mutableSingleInterGlobalMvCdf(0));
        assertArrayEquals(new int[]{8794, 0}, context.mutableSingleInterReferenceMvCdf(0));
        assertArrayEquals(new int[]{19664, 0}, context.mutableDrlCdf(0));
        assertArrayEquals(new int[]{25008, 18945, 16960, 15127, 13612, 12102, 5877, 0}, context.mutableCompoundInterModeCdf(0));
        assertArrayEquals(new int[]{28672, 21504, 13440, 0}, context.mutableMotionVectorJointCdf());
        assertArrayEquals(new int[]{4096, 1792, 910, 448, 217, 112, 28, 11, 6, 1, 0}, context.mutableMotionVectorClassCdf(0));
        assertArrayEquals(new int[]{16384, 0}, context.mutableMotionVectorSignCdf(0));
        assertArrayEquals(new int[]{5120, 0}, context.mutableMotionVectorClass0Cdf(0));
        assertArrayEquals(new int[]{16384, 8192, 6144, 0}, context.mutableMotionVectorClass0FpCdf(0, 0));
        assertArrayEquals(new int[]{12288, 0}, context.mutableMotionVectorClass0HpCdf(0));
        assertArrayEquals(new int[]{15360, 0}, context.mutableMotionVectorClassNCdf(0, 0));
        assertArrayEquals(new int[]{24576, 15360, 11520, 0}, context.mutableMotionVectorClassNFpCdf(0));
        assertArrayEquals(new int[]{16384, 0}, context.mutableMotionVectorClassNHpCdf(0));
        assertArrayEquals(new int[]{2237, 0}, context.mutableIntrabcCdf());
        assertArrayEquals(new int[]{16384, 0}, context.mutableSegmentPredictionCdf(0));
        assertArrayEquals(new int[]{27146, 24875, 16675, 14535, 4959, 4395, 235, 0}, context.mutableSegmentIdCdf(0));

        assertEquals(13, context.mutableYModeCdf(0).length);
        assertEquals(9967, context.mutableYModeCdf(0)[0]);
        assertEquals(2419, context.mutableYModeCdf(0)[11]);

        assertArrayEquals(new int[]{28147, 0}, context.mutableUseFilterIntraCdf(0));
        assertArrayEquals(new int[]{23819, 19992, 15557, 3210, 0}, context.mutableFilterIntraCdf());

        assertEquals(13, context.mutableKeyFrameYModeCdf(0, 0).length);
        assertEquals(17180, context.mutableKeyFrameYModeCdf(0, 0)[0]);
        assertEquals(2302, context.mutableKeyFrameYModeCdf(0, 0)[11]);

        assertEquals(13, context.mutableUvModeCdf(false, 0).length);
        assertEquals(10137, context.mutableUvModeCdf(false, 0)[0]);
        assertEquals(14, context.mutableUvModeCdf(true, 12).length);
        assertEquals(3720, context.mutableUvModeCdf(true, 12)[12]);

        assertEquals(8, context.mutablePartitionCdf(0, 0).length);
        assertEquals(4869, context.mutablePartitionCdf(0, 0)[0]);
        assertEquals(10, context.mutablePartitionCdf(1, 0).length);
        assertEquals(12631, context.mutablePartitionCdf(1, 0)[0]);
        assertEquals(4, context.mutablePartitionCdf(4, 3).length);
        assertEquals(22872, context.mutablePartitionCdf(4, 3)[0]);

        assertArrayEquals(new int[]{1092, 0}, context.mutableLumaPaletteCdf(0, 0));
        assertArrayEquals(new int[]{24816, 19768, 14619, 11290, 7241, 3527, 0}, context.mutablePaletteSizeCdf(0, 0));
        assertArrayEquals(new int[]{307, 0}, context.mutableChromaPaletteCdf(0));
        assertArrayEquals(new int[]{4058, 0}, context.mutableColorMapCdf(0, 0, 0));
        assertArrayEquals(new int[]{924, 724, 487, 250, 0}, context.mutableColorMapCdf(1, 3, 4));
    }

    /// Verifies that copying the context deep-copies all mutable tables.
    @Test
    void copyDeepCopiesMutableTables() {
        CdfContext original = CdfContext.createDefault();
        CdfContext copy = original.copy();

        assertNotSame(original.mutableSkipCdf(0), copy.mutableSkipCdf(0));
        assertNotSame(original.mutableSkipModeCdf(0), copy.mutableSkipModeCdf(0));
        assertNotSame(original.mutableCompoundReferenceCdf(0), copy.mutableCompoundReferenceCdf(0));
        assertNotSame(original.mutableCompoundDirectionCdf(0), copy.mutableCompoundDirectionCdf(0));
        assertNotSame(original.mutableSingleReferenceCdf(0, 0), copy.mutableSingleReferenceCdf(0, 0));
        assertNotSame(original.mutableCompoundForwardReferenceCdf(0, 0), copy.mutableCompoundForwardReferenceCdf(0, 0));
        assertNotSame(original.mutableCompoundBackwardReferenceCdf(0, 0), copy.mutableCompoundBackwardReferenceCdf(0, 0));
        assertNotSame(original.mutableCompoundUnidirectionalReferenceCdf(0, 0), copy.mutableCompoundUnidirectionalReferenceCdf(0, 0));
        assertNotSame(original.mutableSingleInterNewMvCdf(0), copy.mutableSingleInterNewMvCdf(0));
        assertNotSame(original.mutableSingleInterGlobalMvCdf(0), copy.mutableSingleInterGlobalMvCdf(0));
        assertNotSame(original.mutableSingleInterReferenceMvCdf(0), copy.mutableSingleInterReferenceMvCdf(0));
        assertNotSame(original.mutableDrlCdf(0), copy.mutableDrlCdf(0));
        assertNotSame(original.mutableCompoundInterModeCdf(0), copy.mutableCompoundInterModeCdf(0));
        assertNotSame(original.mutableMotionVectorJointCdf(), copy.mutableMotionVectorJointCdf());
        assertNotSame(original.mutableMotionVectorClassCdf(0), copy.mutableMotionVectorClassCdf(0));
        assertNotSame(original.mutableMotionVectorSignCdf(0), copy.mutableMotionVectorSignCdf(0));
        assertNotSame(original.mutableMotionVectorClass0Cdf(0), copy.mutableMotionVectorClass0Cdf(0));
        assertNotSame(original.mutableMotionVectorClass0FpCdf(0, 0), copy.mutableMotionVectorClass0FpCdf(0, 0));
        assertNotSame(original.mutableMotionVectorClass0HpCdf(0), copy.mutableMotionVectorClass0HpCdf(0));
        assertNotSame(original.mutableMotionVectorClassNCdf(0, 0), copy.mutableMotionVectorClassNCdf(0, 0));
        assertNotSame(original.mutableMotionVectorClassNFpCdf(0), copy.mutableMotionVectorClassNFpCdf(0));
        assertNotSame(original.mutableMotionVectorClassNHpCdf(0), copy.mutableMotionVectorClassNHpCdf(0));
        assertNotSame(original.mutableUseFilterIntraCdf(0), copy.mutableUseFilterIntraCdf(0));
        assertNotSame(original.mutableFilterIntraCdf(), copy.mutableFilterIntraCdf());
        assertNotSame(original.mutableUvModeCdf(true, 0), copy.mutableUvModeCdf(true, 0));
        assertNotSame(original.mutablePartitionCdf(0, 0), copy.mutablePartitionCdf(0, 0));
        assertNotSame(original.mutableLumaPaletteCdf(0, 0), copy.mutableLumaPaletteCdf(0, 0));
        assertNotSame(original.mutablePaletteSizeCdf(0, 0), copy.mutablePaletteSizeCdf(0, 0));
        assertNotSame(original.mutableChromaPaletteCdf(0), copy.mutableChromaPaletteCdf(0));
        assertNotSame(original.mutableColorMapCdf(0, 0, 0), copy.mutableColorMapCdf(0, 0, 0));
        assertNotSame(original.mutableSegmentPredictionCdf(0), copy.mutableSegmentPredictionCdf(0));
        assertNotSame(original.mutableSegmentIdCdf(0), copy.mutableSegmentIdCdf(0));
        assertNotSame(original.mutableKeyFrameYModeCdf(0, 0), copy.mutableKeyFrameYModeCdf(0, 0));

        copy.mutableSkipCdf(0)[0] = 77;
        copy.mutableSkipModeCdf(0)[0] = 88;
        copy.mutableCompoundReferenceCdf(0)[0] = 89;
        copy.mutableCompoundDirectionCdf(0)[0] = 90;
        copy.mutableSingleReferenceCdf(0, 0)[0] = 91;
        copy.mutableCompoundForwardReferenceCdf(0, 0)[0] = 92;
        copy.mutableCompoundBackwardReferenceCdf(0, 0)[0] = 93;
        copy.mutableCompoundUnidirectionalReferenceCdf(0, 0)[0] = 94;
        copy.mutableSingleInterNewMvCdf(0)[0] = 95;
        copy.mutableSingleInterGlobalMvCdf(0)[0] = 96;
        copy.mutableSingleInterReferenceMvCdf(0)[0] = 97;
        copy.mutableDrlCdf(0)[0] = 98;
        copy.mutableCompoundInterModeCdf(0)[0] = 99;
        copy.mutableMotionVectorJointCdf()[0] = 101;
        copy.mutableMotionVectorClassCdf(0)[0] = 102;
        copy.mutableMotionVectorSignCdf(0)[0] = 103;
        copy.mutableMotionVectorClass0Cdf(0)[0] = 104;
        copy.mutableMotionVectorClass0FpCdf(0, 0)[0] = 105;
        copy.mutableMotionVectorClass0HpCdf(0)[0] = 106;
        copy.mutableMotionVectorClassNCdf(0, 0)[0] = 107;
        copy.mutableMotionVectorClassNFpCdf(0)[0] = 108;
        copy.mutableMotionVectorClassNHpCdf(0)[0] = 109;
        copy.mutableUseFilterIntraCdf(0)[0] = 66;
        copy.mutableFilterIntraCdf()[0] = 55;
        copy.mutableUvModeCdf(true, 0)[0] = 99;
        copy.mutablePartitionCdf(0, 0)[0] = 100;
        copy.mutableLumaPaletteCdf(0, 0)[0] = 144;
        copy.mutablePaletteSizeCdf(0, 0)[0] = 155;
        copy.mutableChromaPaletteCdf(0)[0] = 166;
        copy.mutableColorMapCdf(0, 0, 0)[0] = 177;
        copy.mutableSegmentPredictionCdf(0)[0] = 123;
        copy.mutableSegmentIdCdf(0)[0] = 321;
        copy.mutableKeyFrameYModeCdf(0, 0)[0] = 111;

        assertEquals(1097, original.mutableSkipCdf(0)[0]);
        assertEquals(147, original.mutableSkipModeCdf(0)[0]);
        assertEquals(5940, original.mutableCompoundReferenceCdf(0)[0]);
        assertEquals(31570, original.mutableCompoundDirectionCdf(0)[0]);
        assertEquals(27871, original.mutableSingleReferenceCdf(0, 0)[0]);
        assertEquals(27822, original.mutableCompoundForwardReferenceCdf(0, 0)[0]);
        assertEquals(30533, original.mutableCompoundBackwardReferenceCdf(0, 0)[0]);
        assertEquals(27484, original.mutableCompoundUnidirectionalReferenceCdf(0, 0)[0]);
        assertEquals(8733, original.mutableSingleInterNewMvCdf(0)[0]);
        assertEquals(30593, original.mutableSingleInterGlobalMvCdf(0)[0]);
        assertEquals(8794, original.mutableSingleInterReferenceMvCdf(0)[0]);
        assertEquals(19664, original.mutableDrlCdf(0)[0]);
        assertEquals(25008, original.mutableCompoundInterModeCdf(0)[0]);
        assertEquals(28672, original.mutableMotionVectorJointCdf()[0]);
        assertEquals(4096, original.mutableMotionVectorClassCdf(0)[0]);
        assertEquals(16384, original.mutableMotionVectorSignCdf(0)[0]);
        assertEquals(5120, original.mutableMotionVectorClass0Cdf(0)[0]);
        assertEquals(16384, original.mutableMotionVectorClass0FpCdf(0, 0)[0]);
        assertEquals(12288, original.mutableMotionVectorClass0HpCdf(0)[0]);
        assertEquals(15360, original.mutableMotionVectorClassNCdf(0, 0)[0]);
        assertEquals(24576, original.mutableMotionVectorClassNFpCdf(0)[0]);
        assertEquals(16384, original.mutableMotionVectorClassNHpCdf(0)[0]);
        assertEquals(28147, original.mutableUseFilterIntraCdf(0)[0]);
        assertEquals(23819, original.mutableFilterIntraCdf()[0]);
        assertEquals(22361, original.mutableUvModeCdf(true, 0)[0]);
        assertEquals(4869, original.mutablePartitionCdf(0, 0)[0]);
        assertEquals(1092, original.mutableLumaPaletteCdf(0, 0)[0]);
        assertEquals(24816, original.mutablePaletteSizeCdf(0, 0)[0]);
        assertEquals(307, original.mutableChromaPaletteCdf(0)[0]);
        assertEquals(4058, original.mutableColorMapCdf(0, 0, 0)[0]);
        assertEquals(16384, original.mutableSegmentPredictionCdf(0)[0]);
        assertEquals(27146, original.mutableSegmentIdCdf(0)[0]);
        assertEquals(17180, original.mutableKeyFrameYModeCdf(0, 0)[0]);
    }
}
