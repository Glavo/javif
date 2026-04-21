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

import java.util.Arrays;
import java.util.Objects;

/// Mutable AV1 entropy CDF context seeded with the default tables for supported syntax elements.
///
/// All accessor methods return live mutable arrays intended to be passed directly to `MsacDecoder`
/// decode helpers that update CDFs in place.
@NotNullByDefault
public final class CdfContext {
    /// The transformed default skip CDFs.
    private static final int[][] DEFAULT_SKIP_CDFS = inverse2d(new int[][]{
            {31671},
            {16515},
            {4576}
    });

    /// The transformed default intra/inter decision CDFs.
    private static final int[][] DEFAULT_INTRA_CDFS = inverse2d(new int[][]{
            {806},
            {16662},
            {20186},
            {26538}
    });

    /// The transformed default `intrabc` CDF.
    private static final int[] DEFAULT_INTRABC_CDF = inverse(30531);

    /// The transformed default luma intra-mode CDFs.
    private static final int[][] DEFAULT_Y_MODE_CDFS = inverse2d(new int[][]{
            {22801, 23489, 24293, 24756, 25601, 26123, 26606, 27418, 27945, 29228, 29685, 30349},
            {18673, 19845, 22631, 23318, 23950, 24649, 25527, 27364, 28152, 29701, 29984, 30852},
            {19770, 20979, 23396, 23939, 24241, 24654, 25136, 27073, 27830, 29360, 29730, 30659},
            {20155, 21301, 22838, 23178, 23261, 23533, 23703, 24804, 25352, 26575, 27016, 28049}
    });

    /// The transformed default chroma intra-mode CDFs.
    private static final int[][][] DEFAULT_UV_MODE_CDFS = inverse3d(new int[][][]{
            {
                    {22631, 24152, 25378, 25661, 25986, 26520, 27055, 27923, 28244, 30059, 30941, 31961},
                    {9513, 26881, 26973, 27046, 27118, 27664, 27739, 27824, 28359, 29505, 29800, 31796},
                    {9845, 9915, 28663, 28704, 28757, 28780, 29198, 29822, 29854, 30764, 31777, 32029},
                    {13639, 13897, 14171, 25331, 25606, 25727, 25953, 27148, 28577, 30612, 31355, 32493},
                    {9764, 9835, 9930, 9954, 25386, 27053, 27958, 28148, 28243, 31101, 31744, 32363},
                    {11825, 13589, 13677, 13720, 15048, 29213, 29301, 29458, 29711, 31161, 31441, 32550},
                    {14175, 14399, 16608, 16821, 17718, 17775, 28551, 30200, 30245, 31837, 32342, 32667},
                    {12885, 13038, 14978, 15590, 15673, 15748, 16176, 29128, 29267, 30643, 31961, 32461},
                    {12026, 13661, 13874, 15305, 15490, 15726, 15995, 16273, 28443, 30388, 30767, 32416},
                    {19052, 19840, 20579, 20916, 21150, 21467, 21885, 22719, 23174, 28861, 30379, 32175},
                    {18627, 19649, 20974, 21219, 21492, 21816, 22199, 23119, 23527, 27053, 31397, 32148},
                    {17026, 19004, 19997, 20339, 20586, 21103, 21349, 21907, 22482, 25896, 26541, 31819},
                    {12124, 13759, 14959, 14992, 15007, 15051, 15078, 15166, 15255, 15753, 16039, 16606}
            },
            {
                    {10407, 11208, 12900, 13181, 13823, 14175, 14899, 15656, 15986, 20086, 20995, 22455, 24212},
                    {4532, 19780, 20057, 20215, 20428, 21071, 21199, 21451, 22099, 24228, 24693, 27032, 29472},
                    {5273, 5379, 20177, 20270, 20385, 20439, 20949, 21695, 21774, 23138, 24256, 24703, 26679},
                    {6740, 7167, 7662, 14152, 14536, 14785, 15034, 16741, 18371, 21520, 22206, 23389, 24182},
                    {4987, 5368, 5928, 6068, 19114, 20315, 21857, 22253, 22411, 24911, 25380, 26027, 26376},
                    {5370, 6889, 7247, 7393, 9498, 21114, 21402, 21753, 21981, 24780, 25386, 26517, 27176},
                    {4816, 4961, 7204, 7326, 8765, 8930, 20169, 20682, 20803, 23188, 23763, 24455, 24940},
                    {6608, 6740, 8529, 9049, 9257, 9356, 9735, 18827, 19059, 22336, 23204, 23964, 24793},
                    {5998, 7419, 7781, 8933, 9255, 9549, 9753, 10417, 18898, 22494, 23139, 24764, 25989},
                    {10660, 11298, 12550, 12957, 13322, 13624, 14040, 15004, 15534, 20714, 21789, 23443, 24861},
                    {10522, 11530, 12552, 12963, 13378, 13779, 14245, 15235, 15902, 20102, 22696, 23774, 25838},
                    {10099, 10691, 12639, 13049, 13386, 13665, 14125, 15163, 15636, 19676, 20474, 23519, 25208},
                    {3144, 5087, 7382, 7504, 7593, 7690, 7801, 8064, 8232, 9248, 9875, 10521, 29048}
            }
    });

    /// The transformed default partition CDFs.
    private static final int[][][] DEFAULT_PARTITION_CDFS = inverse3d(new int[][][]{
            {
                    {27899, 28219, 28529, 32484, 32539, 32619, 32639},
                    {6607, 6990, 8268, 32060, 32219, 32338, 32371},
                    {5429, 6676, 7122, 32027, 32227, 32531, 32582},
                    {711, 966, 1172, 32448, 32538, 32617, 32664}
            },
            {
                    {20137, 21547, 23078, 29566, 29837, 30261, 30524, 30892, 31724},
                    {6732, 7490, 9497, 27944, 28250, 28515, 28969, 29630, 30104},
                    {5945, 7663, 8348, 28683, 29117, 29749, 30064, 30298, 32238},
                    {870, 1212, 1487, 31198, 31394, 31574, 31743, 31881, 32332}
            },
            {
                    {18462, 20920, 23124, 27647, 28227, 29049, 29519, 30178, 31544},
                    {7689, 9060, 12056, 24992, 25660, 26182, 26951, 28041, 29052},
                    {6015, 9009, 10062, 24544, 25409, 26545, 27071, 27526, 32047},
                    {1394, 2208, 2796, 28614, 29061, 29466, 29840, 30185, 31899}
            },
            {
                    {15597, 20929, 24571, 26706, 27664, 28821, 29601, 30571, 31902},
                    {7925, 11043, 16785, 22470, 23971, 25043, 26651, 28701, 29834},
                    {5414, 13269, 15111, 20488, 22360, 24500, 25537, 26336, 32117},
                    {2662, 6362, 8614, 20860, 23053, 24778, 26436, 27829, 31171}
            },
            {
                    {19132, 25510, 30392},
                    {13928, 19855, 28540},
                    {12522, 23679, 28629},
                    {9896, 18783, 25853}
            }
    });

    /// The transformed default key-frame luma intra-mode CDFs.
    private static final int[][][] DEFAULT_KEY_FRAME_Y_MODE_CDFS = inverse3d(new int[][][]{
            {
                    {15588, 17027, 19338, 20218, 20682, 21110, 21825, 23244, 24189, 28165, 29093, 30466},
                    {12016, 18066, 19516, 20303, 20719, 21444, 21888, 23032, 24434, 28658, 30172, 31409},
                    {10052, 10771, 22296, 22788, 23055, 23239, 24133, 25620, 26160, 29336, 29929, 31567},
                    {14091, 15406, 16442, 18808, 19136, 19546, 19998, 22096, 24746, 29585, 30958, 32462},
                    {12122, 13265, 15603, 16501, 18609, 20033, 22391, 25583, 26437, 30261, 31073, 32475}
            },
            {
                    {10023, 19585, 20848, 21440, 21832, 22760, 23089, 24023, 25381, 29014, 30482, 31436},
                    {5983, 24099, 24560, 24886, 25066, 25795, 25913, 26423, 27610, 29905, 31276, 31794},
                    {7444, 12781, 20177, 20728, 21077, 21607, 22170, 23405, 24469, 27915, 29090, 30492},
                    {8537, 14689, 15432, 17087, 17408, 18172, 18408, 19825, 24649, 29153, 31096, 32210},
                    {7543, 14231, 15496, 16195, 17905, 20717, 21984, 24516, 26001, 29675, 30981, 31994}
            },
            {
                    {12613, 13591, 21383, 22004, 22312, 22577, 23401, 25055, 25729, 29538, 30305, 32077},
                    {9687, 13470, 18506, 19230, 19604, 20147, 20695, 22062, 23219, 27743, 29211, 30907},
                    {6183, 6505, 26024, 26252, 26366, 26434, 27082, 28354, 28555, 30467, 30794, 32086},
                    {10718, 11734, 14954, 17224, 17565, 17924, 18561, 21523, 23878, 28975, 30287, 32252},
                    {9194, 9858, 16501, 17263, 18424, 19171, 21563, 25961, 26561, 30072, 30737, 32463}
            },
            {
                    {12602, 14399, 15488, 18381, 18778, 19315, 19724, 21419, 25060, 29696, 30917, 32409},
                    {8203, 13821, 14524, 17105, 17439, 18131, 18404, 19468, 25225, 29485, 31158, 32342},
                    {8451, 9731, 15004, 17643, 18012, 18425, 19070, 21538, 24605, 29118, 30078, 32018},
                    {7714, 9048, 9516, 16667, 16817, 16994, 17153, 18767, 26743, 30389, 31536, 32528},
                    {8843, 10280, 11496, 15317, 16652, 17943, 19108, 22718, 25769, 29953, 30983, 32485}
            },
            {
                    {12578, 13671, 15979, 16834, 19075, 20913, 22989, 25449, 26219, 30214, 31150, 32477},
                    {9563, 13626, 15080, 15892, 17756, 20863, 22207, 24236, 25380, 29653, 31143, 32277},
                    {8356, 8901, 17616, 18256, 19350, 20106, 22598, 25947, 26466, 29900, 30523, 32261},
                    {10835, 11815, 13124, 16042, 17018, 18039, 18947, 22753, 24615, 29489, 30883, 32482},
                    {7618, 8288, 9859, 10509, 15386, 18657, 22903, 28776, 29180, 31355, 31802, 32593}
            }
    });

    /// The mutable skip CDFs for skip-flag decoding.
    private final int[][] skipCdfs;

    /// The mutable intra/inter decision CDFs.
    private final int[][] intraCdfs;

    /// The mutable `intrabc` CDF.
    private final int[] intrabcCdf;

    /// The mutable luma intra-mode CDFs.
    private final int[][] yModeCdfs;

    /// The mutable chroma intra-mode CDFs.
    private final int[][][] uvModeCdfs;

    /// The mutable partition CDFs.
    private final int[][][] partitionCdfs;

    /// The mutable key-frame luma intra-mode CDFs.
    private final int[][][] keyFrameYModeCdfs;

    /// Creates a mutable CDF context from already-copied arrays.
    ///
    /// @param skipCdfs the mutable skip CDFs
    /// @param intraCdfs the mutable intra/inter decision CDFs
    /// @param intrabcCdf the mutable `intrabc` CDF
    /// @param yModeCdfs the mutable luma intra-mode CDFs
    /// @param uvModeCdfs the mutable chroma intra-mode CDFs
    /// @param partitionCdfs the mutable partition CDFs
    /// @param keyFrameYModeCdfs the mutable key-frame luma intra-mode CDFs
    private CdfContext(
            int[][] skipCdfs,
            int[][] intraCdfs,
            int[] intrabcCdf,
            int[][] yModeCdfs,
            int[][][] uvModeCdfs,
            int[][][] partitionCdfs,
            int[][][] keyFrameYModeCdfs
    ) {
        this.skipCdfs = Objects.requireNonNull(skipCdfs, "skipCdfs");
        this.intraCdfs = Objects.requireNonNull(intraCdfs, "intraCdfs");
        this.intrabcCdf = Objects.requireNonNull(intrabcCdf, "intrabcCdf");
        this.yModeCdfs = Objects.requireNonNull(yModeCdfs, "yModeCdfs");
        this.uvModeCdfs = Objects.requireNonNull(uvModeCdfs, "uvModeCdfs");
        this.partitionCdfs = Objects.requireNonNull(partitionCdfs, "partitionCdfs");
        this.keyFrameYModeCdfs = Objects.requireNonNull(keyFrameYModeCdfs, "keyFrameYModeCdfs");
    }

    /// Creates a mutable CDF context seeded with the AV1 default tables.
    ///
    /// @return a mutable CDF context seeded with the AV1 default tables
    public static CdfContext createDefault() {
        return new CdfContext(
                deepCopy(DEFAULT_SKIP_CDFS),
                deepCopy(DEFAULT_INTRA_CDFS),
                Arrays.copyOf(DEFAULT_INTRABC_CDF, DEFAULT_INTRABC_CDF.length),
                deepCopy(DEFAULT_Y_MODE_CDFS),
                deepCopy(DEFAULT_UV_MODE_CDFS),
                deepCopy(DEFAULT_PARTITION_CDFS),
                deepCopy(DEFAULT_KEY_FRAME_Y_MODE_CDFS)
        );
    }

    /// Creates a deep copy of this mutable CDF context.
    ///
    /// @return a deep copy of this mutable CDF context
    public CdfContext copy() {
        return new CdfContext(
                deepCopy(skipCdfs),
                deepCopy(intraCdfs),
                Arrays.copyOf(intrabcCdf, intrabcCdf.length),
                deepCopy(yModeCdfs),
                deepCopy(uvModeCdfs),
                deepCopy(partitionCdfs),
                deepCopy(keyFrameYModeCdfs)
        );
    }

    /// Returns the live mutable skip CDF for the supplied context index.
    ///
    /// @param context the zero-based skip context index
    /// @return the live mutable skip CDF for the supplied context index
    public int[] mutableSkipCdf(int context) {
        return skipCdfs[Objects.checkIndex(context, skipCdfs.length)];
    }

    /// Returns the live mutable intra/inter decision CDF for the supplied context index.
    ///
    /// @param context the zero-based intra/inter context index
    /// @return the live mutable intra/inter decision CDF for the supplied context index
    public int[] mutableIntraCdf(int context) {
        return intraCdfs[Objects.checkIndex(context, intraCdfs.length)];
    }

    /// Returns the live mutable `intrabc` CDF.
    ///
    /// @return the live mutable `intrabc` CDF
    public int[] mutableIntrabcCdf() {
        return intrabcCdf;
    }

    /// Returns the live mutable luma intra-mode CDF for the supplied context index.
    ///
    /// @param context the zero-based luma intra-mode context index
    /// @return the live mutable luma intra-mode CDF for the supplied context index
    public int[] mutableYModeCdf(int context) {
        return yModeCdfs[Objects.checkIndex(context, yModeCdfs.length)];
    }

    /// Returns the live mutable key-frame luma intra-mode CDF for the supplied above/left mode classes.
    ///
    /// @param aboveMode the coarsened above-neighbor mode class
    /// @param leftMode the coarsened left-neighbor mode class
    /// @return the live mutable key-frame luma intra-mode CDF
    public int[] mutableKeyFrameYModeCdf(int aboveMode, int leftMode) {
        int[][] row = keyFrameYModeCdfs[Objects.checkIndex(aboveMode, keyFrameYModeCdfs.length)];
        return row[Objects.checkIndex(leftMode, row.length)];
    }

    /// Returns the live mutable chroma intra-mode CDF for the supplied Y-mode context.
    ///
    /// @param cflAllowed whether the active frame allows CFL, selecting the larger UV-mode table
    /// @param yMode the zero-based luma intra-mode index
    /// @return the live mutable chroma intra-mode CDF for the supplied Y-mode context
    public int[] mutableUvModeCdf(boolean cflAllowed, int yMode) {
        int[][] table = uvModeCdfs[cflAllowed ? 1 : 0];
        return table[Objects.checkIndex(yMode, table.length)];
    }

    /// Returns the live mutable partition CDF for the supplied block level and context index.
    ///
    /// @param blockLevel the zero-based block-size level
    /// @param context the zero-based partition context index
    /// @return the live mutable partition CDF for the supplied block level and context index
    public int[] mutablePartitionCdf(int blockLevel, int context) {
        int[][] level = partitionCdfs[Objects.checkIndex(blockLevel, partitionCdfs.length)];
        return level[Objects.checkIndex(context, level.length)];
    }

    /// Converts raw `dav1d` CDF thresholds into inverse-ordered AV1 CDF arrays with a zero count slot.
    ///
    /// @param rawCdf the raw threshold values copied from `dav1d`
    /// @return the transformed inverse-ordered AV1 CDF array
    private static int[] inverse(int... rawCdf) {
        int[] transformed = new int[rawCdf.length + 1];
        for (int i = 0; i < rawCdf.length; i++) {
            transformed[i] = 32768 - rawCdf[i];
        }
        return transformed;
    }

    /// Converts a two-dimensional table of raw `dav1d` thresholds into inverse-ordered AV1 CDF arrays.
    ///
    /// @param rawCdfs the two-dimensional table of raw threshold values
    /// @return the transformed inverse-ordered AV1 CDF arrays
    private static int[][] inverse2d(int[][] rawCdfs) {
        int[][] transformed = new int[rawCdfs.length][];
        for (int i = 0; i < rawCdfs.length; i++) {
            transformed[i] = inverse(rawCdfs[i]);
        }
        return transformed;
    }

    /// Converts a three-dimensional table of raw `dav1d` thresholds into inverse-ordered AV1 CDF arrays.
    ///
    /// @param rawCdfs the three-dimensional table of raw threshold values
    /// @return the transformed inverse-ordered AV1 CDF arrays
    private static int[][][] inverse3d(int[][][] rawCdfs) {
        int[][][] transformed = new int[rawCdfs.length][][];
        for (int i = 0; i < rawCdfs.length; i++) {
            transformed[i] = inverse2d(rawCdfs[i]);
        }
        return transformed;
    }

    /// Creates a deep copy of a two-dimensional integer table.
    ///
    /// @param source the source table to copy
    /// @return a deep copy of the supplied table
    private static int[][] deepCopy(int[][] source) {
        int[][] copy = new int[source.length][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = Arrays.copyOf(source[i], source[i].length);
        }
        return copy;
    }

    /// Creates a deep copy of a three-dimensional integer table.
    ///
    /// @param source the source table to copy
    /// @return a deep copy of the supplied table
    private static int[][][] deepCopy(int[][][] source) {
        int[][][] copy = new int[source.length][][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = deepCopy(source[i]);
        }
        return copy;
    }
}
