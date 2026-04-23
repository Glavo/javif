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
package org.glavo.avif.internal.av1.recon;

import org.jetbrains.annotations.NotNullByDefault;

/// Shared AV1 QTX lookup tables used by the current reconstruction subset.
///
/// For the current 8-bit rollout, luma and chroma dequantization both use the same QTX tables
/// after applying plane-specific DC/AC delta adjustments to the block-local `qindex`.
@NotNullByDefault
final class QuantizerTables {
    /// The 8-bit AV1 QTX lookup table for DC coefficients.
    private static final int[] DC_QLOOKUP_8_QTX = {
            4, 8, 8, 9, 10, 11, 12, 12, 13, 14, 15, 16, 17, 18,
            19, 19, 20, 21, 22, 23, 24, 25, 26, 26, 27, 28, 29, 30,
            31, 32, 32, 33, 34, 35, 36, 37, 38, 38, 39, 40, 41, 42,
            43, 43, 44, 45, 46, 47, 48, 48, 49, 50, 51, 52, 53, 53,
            54, 55, 56, 57, 57, 58, 59, 60, 61, 62, 62, 63, 64, 65,
            66, 66, 67, 68, 69, 70, 70, 71, 72, 73, 74, 74, 75, 76,
            77, 78, 78, 79, 80, 81, 81, 82, 83, 84, 85, 85, 87, 88,
            90, 92, 93, 95, 96, 98, 99, 101, 102, 104, 105, 107, 108, 110,
            111, 113, 114, 116, 117, 118, 120, 121, 123, 125, 127, 129, 131, 134,
            136, 138, 140, 142, 144, 146, 148, 150, 152, 154, 156, 158, 161, 164,
            166, 169, 172, 174, 177, 180, 182, 185, 187, 190, 192, 195, 199, 202,
            205, 208, 211, 214, 217, 220, 223, 226, 230, 233, 237, 240, 243, 247,
            250, 253, 257, 261, 265, 269, 272, 276, 280, 284, 288, 292, 296, 300,
            304, 309, 313, 317, 322, 326, 330, 335, 340, 344, 349, 354, 359, 364,
            369, 374, 379, 384, 389, 395, 400, 406, 411, 417, 423, 429, 435, 441,
            447, 454, 461, 467, 475, 482, 489, 497, 505, 513, 522, 530, 539, 549,
            559, 569, 579, 590, 602, 614, 626, 640, 654, 668, 684, 700, 717, 736,
            755, 775, 796, 819, 843, 869, 896, 925, 955, 988, 1022, 1058, 1098, 1139,
            1184, 1232, 1282, 1336
    };

    /// The 8-bit AV1 QTX lookup table for AC coefficients.
    private static final int[] AC_QLOOKUP_8_QTX = {
            4, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32,
            33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45,
            46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58,
            59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71,
            72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84,
            85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95, 96, 97,
            98, 99, 100, 101, 102, 104, 106, 108, 110, 112, 114, 116, 118,
            120, 122, 124, 126, 128, 130, 132, 134, 136, 138, 140, 142, 144,
            146, 148, 150, 152, 155, 158, 161, 164, 167, 170, 173, 176, 179,
            182, 185, 188, 191, 194, 197, 200, 203, 207, 211, 215, 219, 223,
            227, 231, 235, 239, 243, 247, 251, 255, 260, 265, 270, 275, 280,
            285, 290, 295, 300, 305, 311, 317, 323, 329, 335, 341, 347, 353,
            359, 366, 373, 380, 387, 394, 401, 408, 416, 424, 432, 440, 448,
            456, 465, 474, 483, 492, 501, 510, 520, 530, 540, 550, 560, 571,
            582, 593, 604, 615, 627, 639, 651, 663, 676, 689, 702, 715, 729,
            743, 757, 771, 786, 801, 816, 832, 848, 864, 881, 898, 915, 933,
            951, 969, 988, 1007, 1026, 1046, 1066, 1087, 1108, 1129, 1151, 1173, 1196,
            1219, 1243, 1267, 1292, 1317, 1343, 1369, 1396, 1423, 1451, 1479, 1508, 1537,
            1567, 1597, 1628, 1660, 1692, 1725, 1759, 1793, 1828
    };

    /// Prevents instantiation of this utility class.
    private QuantizerTables() {
    }

    /// Returns the active 8-bit DC dequantizer after clamping one derived qindex.
    ///
    /// @param qIndex the derived qindex to clamp and resolve
    /// @return the active 8-bit DC dequantizer
    static int dcQuantizer8(int qIndex) {
        return DC_QLOOKUP_8_QTX[clampedQIndex(qIndex)];
    }

    /// Returns the active 8-bit AC dequantizer after clamping one qindex.
    ///
    /// @param qIndex the qindex to clamp and resolve
    /// @return the active 8-bit AC dequantizer
    static int acQuantizer8(int qIndex) {
        return AC_QLOOKUP_8_QTX[clampedQIndex(qIndex)];
    }

    /// Clamps one derived quantizer index into the legal AV1 lookup-table range.
    ///
    /// @param qIndex the derived quantizer index to clamp
    /// @return the legal lookup-table index
    static int clampedQIndex(int qIndex) {
        if (qIndex <= 0) {
            return 0;
        }
        if (qIndex >= 255) {
            return 255;
        }
        return qIndex;
    }
}
