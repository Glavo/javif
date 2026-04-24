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

    /// The transformed default skip-mode CDFs.
    private static final int[][] DEFAULT_SKIP_MODE_CDFS = inverse2d(new int[][]{
            {32621},
            {20708},
            {8127}
    });

    /// The transformed default intra/inter decision CDFs.
    private static final int[][] DEFAULT_INTRA_CDFS = inverse2d(new int[][]{
            {806},
            {16662},
            {20186},
            {26538}
    });

    /// The transformed default compound-reference decision CDFs.
    private static final int[][] DEFAULT_COMPOUND_REFERENCE_CDFS = inverse2d(new int[][]{
            {26828},
            {24035},
            {12031},
            {10640},
            {2901}
    });

    /// The transformed default compound-direction decision CDFs.
    private static final int[][] DEFAULT_COMPOUND_DIRECTION_CDFS = inverse2d(new int[][]{
            {1198},
            {2070},
            {9166},
            {7499},
            {22475}
    });

    /// The transformed default single-reference selection CDFs.
    private static final int[][][] DEFAULT_SINGLE_REFERENCE_CDFS = inverse3d(new int[][][]{
            {{4897}, {16973}, {29744}},
            {{1555}, {16751}, {30279}},
            {{4236}, {19647}, {31194}},
            {{8650}, {24773}, {31895}},
            {{904}, {11014}, {26875}},
            {{1444}, {15087}, {30304}}
    });

    /// The transformed default compound forward-reference selection CDFs.
    private static final int[][][] DEFAULT_COMPOUND_FORWARD_REFERENCE_CDFS = inverse3d(new int[][][]{
            {{4946}, {19891}, {30731}},
            {{9468}, {22441}, {31059}},
            {{1503}, {15160}, {27544}}
    });

    /// The transformed default compound backward-reference selection CDFs.
    private static final int[][][] DEFAULT_COMPOUND_BACKWARD_REFERENCE_CDFS = inverse3d(new int[][][]{
            {{2235}, {17182}, {30606}},
            {{1423}, {15175}, {30489}}
    });

    /// The transformed default compound unidirectional-reference selection CDFs.
    private static final int[][][] DEFAULT_COMPOUND_UNIDIRECTIONAL_REFERENCE_CDFS = inverse3d(new int[][][]{
            {{5284}, {23152}, {31774}},
            {{3865}, {14173}, {25120}},
            {{3128}, {15270}, {26710}}
    });

    /// The transformed default single-reference new-motion-vector CDFs.
    private static final int[][] DEFAULT_SINGLE_INTER_NEWMV_CDFS = inverse2d(new int[][]{
            {24035},
            {16630},
            {15339},
            {8386},
            {12222},
            {4676}
    });

    /// The transformed default single-reference global-motion CDFs.
    private static final int[][] DEFAULT_SINGLE_INTER_GLOBALMV_CDFS = inverse2d(new int[][]{
            {2175},
            {1054}
    });

    /// The transformed default single-reference reference-motion-vector CDFs.
    private static final int[][] DEFAULT_SINGLE_INTER_REFERENCE_MV_CDFS = inverse2d(new int[][]{
            {23974},
            {24188},
            {17848},
            {28622},
            {24312},
            {19923}
    });

    /// The transformed default dynamic-reference-list selection CDFs.
    private static final int[][] DEFAULT_DRL_CDFS = inverse2d(new int[][]{
            {13104},
            {24560},
            {18945}
    });

    /// The transformed default compound inter-mode CDFs.
    private static final int[][] DEFAULT_COMPOUND_INTER_MODE_CDFS = inverse2d(new int[][]{
            {7760, 13823, 15808, 17641, 19156, 20666, 26891},
            {10730, 19452, 21145, 22749, 24039, 25131, 28724},
            {10664, 20221, 21588, 22906, 24295, 25387, 28436},
            {13298, 16984, 20471, 24182, 25067, 25736, 26422},
            {18904, 23325, 25242, 27432, 27898, 28258, 30758},
            {10725, 17454, 20124, 22820, 24195, 25168, 26046},
            {17125, 24273, 25814, 27492, 28214, 28704, 30592},
            {13046, 23214, 24505, 25942, 27435, 28442, 29330}
    });

    /// The transformed default inter-intra enable CDFs.
    private static final int[][] DEFAULT_INTER_INTRA_CDFS = inverse2d(new int[][]{
            {16384},
            {26887},
            {27597},
            {30237}
    });

    /// The transformed default inter-intra prediction-mode CDFs.
    private static final int[][] DEFAULT_INTER_INTRA_MODE_CDFS = inverse2d(new int[][]{
            {8192, 16384, 24576},
            {1875, 11082, 27332},
            {2473, 9996, 26388},
            {4238, 11537, 25926}
    });

    /// The transformed default inter-intra wedge enable CDFs.
    private static final int[][] DEFAULT_INTER_INTRA_WEDGE_CDFS = inverse2d(new int[][]{
            {20036},
            {24957},
            {26704},
            {27530},
            {29564},
            {29444},
            {26872}
    });

    /// The transformed default wedge-index CDFs.
    private static final int[][] DEFAULT_WEDGE_INDEX_CDFS = inverse2d(new int[][]{
            {2438, 4440, 6599, 8663, 11005, 12874, 15751, 18094, 20359, 22362, 24127, 25702, 27752, 29450, 31171},
            {806, 3266, 6005, 6738, 7218, 7367, 7771, 14588, 16323, 17367, 18452, 19422, 22839, 26127, 29629},
            {2779, 3738, 4683, 7213, 7775, 8017, 8655, 14357, 17939, 21332, 24520, 27470, 29456, 30529, 31656},
            {1684, 3625, 5675, 7108, 9302, 11274, 14429, 17144, 19163, 20961, 22884, 24471, 26719, 28714, 30877},
            {1142, 3491, 6277, 7314, 8089, 8355, 9023, 13624, 15369, 16730, 18114, 19313, 22521, 26012, 29550},
            {2742, 4195, 5727, 8035, 8980, 9336, 10146, 14124, 17270, 20533, 23434, 25972, 27944, 29570, 31416},
            {1727, 3948, 6101, 7796, 9841, 12344, 15766, 18944, 20638, 22038, 23963, 25311, 26988, 28766, 31012},
            {154, 987, 1925, 2051, 2088, 2111, 2151, 23033, 23703, 24284, 24985, 25684, 27259, 28883, 30911},
            {1135, 1322, 1493, 2635, 2696, 2737, 2770, 21016, 22935, 25057, 27251, 29173, 30089, 30960, 31933}
    });

    /// The transformed default switchable interpolation-filter CDFs.
    private static final int[][][] DEFAULT_INTERPOLATION_FILTER_CDFS = inverse3d(new int[][][]{
            {
                    {31935, 32720},
                    {5568, 32719},
                    {422, 2938},
                    {28244, 32608},
                    {31206, 31953},
                    {4862, 32121},
                    {770, 1152},
                    {20889, 25637}
            },
            {
                    {31910, 32724},
                    {4120, 32712},
                    {305, 2247},
                    {27403, 32636},
                    {31022, 32009},
                    {2963, 32093},
                    {601, 943},
                    {14969, 21398}
            }
    });

    /// The transformed default transform-size CDFs.
    private static final int[][][] DEFAULT_TRANSFORM_SIZE_CDFS = inverse3d(new int[][][]{
            {
                    {19968},
                    {19968},
                    {24320}
            },
            {
                    {12272, 30172},
                    {12272, 30172},
                    {18677, 30848}
            },
            {
                    {12986, 15180},
                    {12986, 15180},
                    {24302, 25602}
            },
            {
                    {5782, 11475},
                    {5782, 11475},
                    {16803, 22759}
            }
    });

    /// The transformed default inter transform-partition CDFs.
    private static final int[][] DEFAULT_TRANSFORM_PARTITION_CDFS = inverse2d(new int[][]{
            {28581},
            {23846},
            {20847},
            {24315},
            {18196},
            {12133},
            {18791},
            {10887},
            {11005},
            {27179},
            {20004},
            {11281},
            {26549},
            {19308},
            {14224},
            {28015},
            {21546},
            {14400},
            {28165},
            {22401},
            {16088}
    });

    /// The transformed default coefficient-skip CDFs grouped by AV1 transform-context class.
    private static final int[][][] DEFAULT_COEFFICIENT_SKIP_CDFS = inverse3d(new int[][][]{
            {
                    {31849}, {5892}, {12112}, {21935}, {20289}, {27473}, {32487},
                    {7654}, {19473}, {29984}, {9961}, {30242}, {32117}
            },
            {
                    {31548}, {1549}, {10130}, {16656}, {18591}, {26308}, {32537},
                    {5403}, {18096}, {30003}, {16384}, {16384}, {16384}
            },
            {
                    {29957}, {5391}, {18039}, {23566}, {22431}, {25822}, {32197},
                    {3778}, {15336}, {28981}, {16384}, {16384}, {16384}
            },
            {
                    {17920}, {1818}, {7282}, {25273}, {10923}, {31554}, {32624},
                    {1366}, {15628}, {30462}, {146}, {5132}, {31657}
            },
            {
                    {6308}, {117}, {1638}, {2161}, {16384}, {10923}, {30247},
                    {16384}, {16384}, {16384}, {16384}, {16384}, {16384}
            }
    });

    /// The transformed default end-of-block prefix CDFs grouped by clamped transform area.
    private static final int[][][][] DEFAULT_END_OF_BLOCK_PREFIX_CDFS = inverse4d(new int[][][][]{
            {
                    {
                            {840, 1039, 1980, 4895},
                            {370, 671, 1883, 4471}
                    },
                    {
                            {3247, 4950, 9688, 14563},
                            {1904, 3354, 7763, 14647}
                    }
            },
            {
                    {
                            {400, 520, 977, 2102, 6542},
                            {210, 405, 1315, 3326, 7537}
                    },
                    {
                            {2636, 4273, 7588, 11794, 20401},
                            {1786, 3179, 6902, 11357, 19054}
                    }
            },
            {
                    {
                            {329, 498, 1101, 1784, 3265, 7758},
                            {335, 730, 1459, 5494, 8755, 12997}
                    },
                    {
                            {3505, 5304, 10086, 13814, 17684, 23370},
                            {1563, 2700, 4876, 10911, 14706, 22480}
                    }
            },
            {
                    {
                            {219, 482, 1140, 2091, 3680, 6028, 12586},
                            {371, 699, 1254, 4830, 9479, 12562, 17497}
                    },
                    {
                            {5245, 7456, 12880, 15852, 20033, 23932, 27608},
                            {2054, 3472, 5869, 14232, 18242, 20590, 26752}
                    }
            },
            {
                    {
                            {310, 584, 1887, 3589, 6168, 8611, 11352, 15652},
                            {998, 1850, 2998, 5604, 17341, 19888, 22899, 25583}
                    },
                    {
                            {2520, 3240, 5952, 8870, 12577, 17558, 19954, 24168},
                            {2203, 4130, 7435, 10739, 20652, 23681, 25609, 27261}
                    }
            },
            {
                    {
                            {641, 983, 3707, 5430, 10234, 14958, 18788, 23412, 26061},
                            {641, 983, 3707, 5430, 10234, 14958, 18788, 23412, 26061}
                    },
                    {
                            {5095, 6446, 9996, 13354, 16017, 17986, 20919, 26129, 29140},
                            {5095, 6446, 9996, 13354, 16017, 17986, 20919, 26129, 29140}
                    }
            },
            {
                    {
                            {393, 421, 751, 1623, 3160, 6352, 13345, 18047, 22571, 25830},
                            {393, 421, 751, 1623, 3160, 6352, 13345, 18047, 22571, 25830}
                    },
                    {
                            {1865, 1988, 2930, 4242, 10533, 16538, 21354, 27255, 28546, 31784},
                            {1865, 1988, 2930, 4242, 10533, 16538, 21354, 27255, 28546, 31784}
                    }
            }
    });

    /// The transformed default end-of-block base-token CDFs grouped by AV1 transform-context class.
    private static final int[][][][] DEFAULT_END_OF_BLOCK_BASE_TOKEN_CDFS = inverse4d(new int[][][][]{
            {
                    {
                            {17837, 29055},
                            {29600, 31446},
                            {30844, 31878},
                            {24926, 28948}
                    },
                    {
                            {21365, 30026},
                            {30512, 32423},
                            {31658, 32621},
                            {29630, 31881}
                    }
            },
            {
                    {
                            {5717, 26477},
                            {30491, 31703},
                            {31550, 32158},
                            {29648, 31491}
                    },
                    {
                            {12608, 27820},
                            {30680, 32225},
                            {30809, 32335},
                            {31299, 32423}
                    }
            },
            {
                    {
                            {1786, 12612},
                            {30663, 31625},
                            {32339, 32468},
                            {31148, 31833}
                    },
                    {
                            {18857, 23865},
                            {31428, 32428},
                            {31744, 32373},
                            {31775, 32526}
                    }
            },
            {
                    {
                            {1787, 2532},
                            {30832, 31662},
                            {31824, 32682},
                            {32133, 32569}
                    },
                    {
                            {13751, 22235},
                            {32089, 32409},
                            {27084, 27920},
                            {29291, 32594}
                    }
            },
            {
                    {
                            {1725, 3449},
                            {31102, 31935},
                            {32457, 32613},
                            {32412, 32649}
                    },
                    {
                            {10923, 21845},
                            {10923, 21845},
                            {10923, 21845},
                            {10923, 21845}
                    }
            }
    });

    /// The transformed default end-of-block high-bit CDFs grouped by AV1 transform-context class.
    private static final int[][][][] DEFAULT_END_OF_BLOCK_HIGH_BIT_CDFS = inverse4d(new int[][][][]{
            {
                    {
                            {16961}, {17223}, {7621}, {16384}, {16384}, {16384}, {16384}, {16384}, {16384}
                    },
                    {
                            {19069}, {22525}, {13377}, {16384}, {16384}, {16384}, {16384}, {16384}, {16384}
                    }
            },
            {
                    {
                            {20401}, {17025}, {12845}, {12873}, {14094}, {16384}, {16384}, {16384}, {16384}
                    },
                    {
                            {20681}, {20701}, {15250}, {15017}, {14928}, {16384}, {16384}, {16384}, {16384}
                    }
            },
            {
                    {
                            {23905}, {17194}, {16170}, {17695}, {13826}, {15810}, {12036}, {16384}, {16384}
                    },
                    {
                            {23959}, {20799}, {19021}, {16203}, {17886}, {14144}, {12010}, {16384}, {16384}
                    }
            },
            {
                    {
                            {27399}, {16327}, {18071}, {19584}, {20721}, {18432}, {19560}, {10150}, {8805}
                    },
                    {
                            {24932}, {20833}, {12027}, {16670}, {19914}, {15106}, {17662}, {13783}, {28756}
                    }
            },
            {
                    {
                            {23406}, {21845}, {18432}, {16384}, {17096}, {12561}, {17320}, {22395}, {21370}
                    },
                    {
                            {16384}, {16384}, {16384}, {16384}, {16384}, {16384}, {16384}, {16384}, {16384}
                    }
            }
    });

    /// The transformed default coefficient base-token CDFs for base-token context `0`.
    ///
    /// This compact fallback remains used for non-`TX_4X4` residual paths that still only decode
    /// the first non-zero AC coefficient.
    private static final int[][][] DEFAULT_BASE_TOKEN_CONTEXT0_CDFS = inverse3d(new int[][][]{
            {
                    {4034, 8930, 12727},
                    {6302, 16444, 21761}
            },
            {
                    {4536, 10072, 14001},
                    {6037, 16771, 21957}
            },
            {
                    {5487, 10460, 13708},
                    {5673, 14302, 19711}
            },
            {
                    {5141, 7096, 8260},
                    {2461, 7013, 9371}
            },
            {
                    {601, 983, 1311},
                    {8192, 16384, 24576}
            }
    });

    /// The transformed default coefficient base-token CDFs for all `TX_4X4` contexts.
    ///
    /// These tables are copied from the first `dav1d` transform-context group and cover the
    /// two-dimensional 4x4 residual path implemented by `TileResidualSyntaxReader`.
    private static final int[][][] DEFAULT_BASE_TOKEN_GROUP0_CDFS = inverse3d(new int[][][]{
            {
                    {4034, 8930, 12727},
                    {18082, 29741, 31877},
                    {12596, 26124, 30493},
                    {9446, 21118, 27005},
                    {6308, 15141, 21279},
                    {2463, 6357, 9783},
                    {20667, 30546, 31929},
                    {13043, 26123, 30134},
                    {8151, 18757, 24778},
                    {5255, 12839, 18632},
                    {2820, 7206, 11161},
                    {8192, 16384, 24576},
                    {8192, 16384, 24576},
                    {8192, 16384, 24576},
                    {8192, 16384, 24576},
                    {8192, 16384, 24576},
                    {8192, 16384, 24576},
                    {8192, 16384, 24576},
                    {8192, 16384, 24576},
                    {8192, 16384, 24576},
                    {8192, 16384, 24576},
                    {15736, 27553, 30604},
                    {11210, 23794, 28787},
                    {5947, 13874, 19701},
                    {4215, 9323, 13891},
                    {2833, 6462, 10059}
            },
            {
                    {6302, 16444, 21761},
                    {23040, 31538, 32475},
                    {15196, 28452, 31496},
                    {10020, 22946, 28514},
                    {6533, 16862, 23501},
                    {3538, 9816, 15076},
                    {24444, 31875, 32525},
                    {15881, 28924, 31635},
                    {9922, 22873, 28466},
                    {6527, 16966, 23691},
                    {4114, 11303, 17220},
                    {8192, 16384, 24576},
                    {8192, 16384, 24576},
                    {8192, 16384, 24576},
                    {8192, 16384, 24576},
                    {8192, 16384, 24576},
                    {8192, 16384, 24576},
                    {8192, 16384, 24576},
                    {8192, 16384, 24576},
                    {8192, 16384, 24576},
                    {8192, 16384, 24576},
                    {20201, 30770, 32209},
                    {14754, 28071, 31258},
                    {8378, 20186, 26517},
                    {5916, 15299, 21978},
                    {4268, 11583, 17901}
            }
    });

    /// The transformed default DC-sign CDFs for luma and chroma planes.
    private static final int[][][] DEFAULT_DC_SIGN_CDFS = inverse3d(new int[][][]{
            {
                    {16000},
                    {13056},
                    {18816}
            },
            {
                    {15232},
                    {12928},
                    {17280}
            }
    });

    /// The transformed default DC high-token CDFs for the `br_tok` context `0`.
    private static final int[][][] DEFAULT_DC_HIGH_TOKEN_CDFS = inverse3d(new int[][][]{
            {
                    {14298, 20718, 24174},
                    {15967, 22905, 26286}
            },
            {
                    {14406, 20862, 24414},
                    {15460, 21696, 25469}
            },
            {
                    {10563, 16233, 19763},
                    {10870, 16684, 20949}
            },
            {
                    {2331, 3662, 5244},
                    {5842, 9229, 10838}
            }
    });

    /// The transformed default high-token CDFs for the last scanned coefficient at `br_tok` context `7`.
    ///
    /// This compact fallback remains used for non-`TX_4X4` residual paths that still only decode
    /// the first non-zero AC coefficient.
    private static final int[][][] DEFAULT_END_OF_BLOCK_HIGH_TOKEN_CONTEXT7_CDFS = inverse3d(new int[][][]{
            {
                    {14392, 19951, 22756},
                    {23032, 28815, 30936}
            },
            {
                    {14430, 20046, 22882},
                    {19856, 26920, 29828}
            },
            {
                    {11246, 16404, 19689},
                    {13819, 19159, 23026}
            },
            {
                    {7672, 13286, 17469},
                    {9714, 17254, 20444}
            }
    });

    /// The transformed default coefficient high-token CDFs for all `TX_4X4` contexts.
    ///
    /// These tables are copied from the first `dav1d` transform-context group and cover the
    /// two-dimensional 4x4 residual path implemented by `TileResidualSyntaxReader`.
    private static final int[][][] DEFAULT_HIGH_TOKEN_GROUP0_CDFS = inverse3d(new int[][][]{
            {
                    {14298, 20718, 24174},
                    {12536, 19601, 23789},
                    {8712, 15051, 19503},
                    {6170, 11327, 15434},
                    {4742, 8926, 12538},
                    {3803, 7317, 10546},
                    {1696, 3317, 4871},
                    {14392, 19951, 22756},
                    {15978, 23218, 26818},
                    {12187, 19474, 23889},
                    {9176, 15640, 20259},
                    {7068, 12655, 17028},
                    {5656, 10442, 14472},
                    {2580, 4992, 7244},
                    {12136, 18049, 21426},
                    {13784, 20721, 24481},
                    {10836, 17621, 21900},
                    {8372, 14444, 18847},
                    {6523, 11779, 16000},
                    {5337, 9898, 13760},
                    {3034, 5860, 8462}
            },
            {
                    {15967, 22905, 26286},
                    {13534, 20654, 24579},
                    {9504, 16092, 20535},
                    {6975, 12568, 16903},
                    {5364, 10091, 14020},
                    {4357, 8370, 11857},
                    {2506, 4934, 7218},
                    {23032, 28815, 30936},
                    {19540, 26704, 29719},
                    {15158, 22969, 27097},
                    {11408, 18865, 23650},
                    {8885, 15448, 20250},
                    {7108, 12853, 17416},
                    {4231, 8041, 11480},
                    {19823, 26490, 29156},
                    {18890, 25929, 28932},
                    {15660, 23491, 27433},
                    {12147, 19776, 24488},
                    {9728, 16774, 21649},
                    {7919, 14277, 19066},
                    {5440, 10170, 14185}
            }
    });

    /// The transformed default delta-q CDF.
    private static final int[] DEFAULT_DELTA_Q_CDF = inverse(608, 648, 91);

    /// The transformed default delta-lf CDFs.
    private static final int[][] DEFAULT_DELTA_LF_CDFS = inverse2d(new int[][]{
            {608, 648, 91},
            {608, 648, 91},
            {608, 648, 91},
            {608, 648, 91},
            {608, 648, 91}
    });

    /// The transformed default motion-vector joint CDF.
    private static final int[] DEFAULT_MOTION_VECTOR_JOINT_CDF = inverse(4096, 11264, 19328);

    /// The transformed default motion-vector class CDFs for vertical and horizontal components.
    private static final int[][] DEFAULT_MOTION_VECTOR_CLASS_CDFS = inverse2d(new int[][]{
            {28672, 30976, 31858, 32320, 32551, 32656, 32740, 32757, 32762, 32767},
            {28672, 30976, 31858, 32320, 32551, 32656, 32740, 32757, 32762, 32767}
    });

    /// The transformed default motion-vector sign CDFs for vertical and horizontal components.
    private static final int[][] DEFAULT_MOTION_VECTOR_SIGN_CDFS = inverse2d(new int[][]{
            {16384},
            {16384}
    });

    /// The transformed default class-0 motion-vector magnitude CDFs for vertical and horizontal components.
    private static final int[][] DEFAULT_MOTION_VECTOR_CLASS0_CDFS = inverse2d(new int[][]{
            {27648},
            {27648}
    });

    /// The transformed default class-0 fractional motion-vector CDFs for vertical and horizontal components.
    private static final int[][][] DEFAULT_MOTION_VECTOR_CLASS0_FP_CDFS = inverse3d(new int[][][]{
            {
                    {16384, 24576, 26624},
                    {12288, 21248, 24128}
            },
            {
                    {16384, 24576, 26624},
                    {12288, 21248, 24128}
            }
    });

    /// The transformed default class-0 high-precision motion-vector CDFs.
    private static final int[][] DEFAULT_MOTION_VECTOR_CLASS0_HP_CDFS = inverse2d(new int[][]{
            {20480},
            {20480}
    });

    /// The transformed default non-class-0 motion-vector bit CDFs for vertical and horizontal components.
    private static final int[][][] DEFAULT_MOTION_VECTOR_CLASSN_CDFS = inverse3d(new int[][][]{
            {
                    {17408}, {17920}, {18944}, {20480}, {22528},
                    {24576}, {28672}, {29952}, {29952}, {30720}
            },
            {
                    {17408}, {17920}, {18944}, {20480}, {22528},
                    {24576}, {28672}, {29952}, {29952}, {30720}
            }
    });

    /// The transformed default non-class-0 fractional motion-vector CDFs.
    private static final int[][] DEFAULT_MOTION_VECTOR_CLASSN_FP_CDFS = inverse2d(new int[][]{
            {8192, 17408, 21248},
            {8192, 17408, 21248}
    });

    /// The transformed default non-class-0 high-precision motion-vector CDFs.
    private static final int[][] DEFAULT_MOTION_VECTOR_CLASSN_HP_CDFS = inverse2d(new int[][]{
            {16384},
            {16384}
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

    /// The transformed default `use_filter_intra` CDFs.
    private static final int[][] DEFAULT_USE_FILTER_INTRA_CDFS = inverse2d(new int[][]{
            {4621},
            {6743},
            {5893},
            {7866},
            {12551},
            {9394},
            {12408},
            {14301},
            {12756},
            {22343},
            {16384},
            {16384},
            {16384},
            {16384},
            {16384},
            {16384},
            {12770},
            {10368},
            {20229},
            {18101},
            {16384},
            {16384}
    });

    /// The transformed default filter-intra-mode CDF.
    private static final int[] DEFAULT_FILTER_INTRA_CDF = inverse(8949, 12776, 17211, 29558);

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

    /// The transformed default luma palette-use CDFs.
    private static final int[][][] DEFAULT_LUMA_PALETTE_CDFS = inverse3d(new int[][][]{
            {
                    {31676},
                    {3419},
                    {1261}
            },
            {
                    {31912},
                    {2859},
                    {980}
            },
            {
                    {31823},
                    {3400},
                    {781}
            },
            {
                    {32030},
                    {3561},
                    {904}
            },
            {
                    {32309},
                    {7337},
                    {1462}
            },
            {
                    {32265},
                    {4015},
                    {1521}
            },
            {
                    {32450},
                    {7946},
                    {129}
            }
    });

    /// The transformed default palette-size CDFs for luma and chroma planes.
    private static final int[][][] DEFAULT_PALETTE_SIZE_CDFS = inverse3d(new int[][][]{
            {
                    {7952, 13000, 18149, 21478, 25527, 29241},
                    {7139, 11421, 16195, 19544, 23666, 28073},
                    {7788, 12741, 17325, 20500, 24315, 28530},
                    {8271, 14064, 18246, 21564, 25071, 28533},
                    {12725, 19180, 21863, 24839, 27535, 30120},
                    {9711, 14888, 16923, 21052, 25661, 27875},
                    {14940, 20797, 21678, 24186, 27033, 28999}
            },
            {
                    {8713, 19979, 27128, 29609, 31331, 32272},
                    {5839, 15573, 23581, 26947, 29848, 31700},
                    {4426, 11260, 17999, 21483, 25863, 29430},
                    {3228, 9464, 14993, 18089, 22523, 27420},
                    {3768, 8886, 13091, 17852, 22495, 27207},
                    {2464, 8451, 12861, 21632, 25525, 28555},
                    {1269, 5435, 10433, 18963, 21700, 25865}
            }
    });

    /// The transformed default chroma palette-use CDFs.
    private static final int[][] DEFAULT_CHROMA_PALETTE_CDFS = inverse2d(new int[][]{
            {32461},
            {21488}
    });

    /// The transformed default palette color-map CDFs for luma and chroma planes.
    private static final int[][][][] DEFAULT_COLOR_MAP_CDFS = inverse4d(new int[][][][]{
            {
                    {
                            {28710}, {16384}, {10553}, {27036}, {31603}
                    },
                    {
                            {27877, 30490}, {11532, 25697}, {6544, 30234}, {23018, 28072}, {31915, 32385}
                    },
                    {
                            {25572, 28046, 30045}, {9478, 21590, 27256}, {7248, 26837, 29824}, {19167, 24486, 28349}, {31400, 31825, 32250}
                    },
                    {
                            {24779, 26955, 28576, 30282}, {8669, 20364, 24073, 28093}, {4255, 27565, 29377, 31067}, {19864, 23674, 26716, 29530}, {31646, 31893, 32147, 32426}
                    },
                    {
                            {23132, 25407, 26970, 28435, 30073}, {7443, 17242, 20717, 24762, 27982}, {6300, 24862, 26944, 28784, 30671}, {18916, 22895, 25267, 27435, 29652}, {31270, 31550, 31808, 32059, 32353}
                    },
                    {
                            {23105, 25199, 26464, 27684, 28931, 30318}, {6950, 15447, 18952, 22681, 25567, 28563}, {7560, 23474, 25490, 27203, 28921, 30708}, {18544, 22373, 24457, 26195, 28119, 30045}, {31198, 31451, 31670, 31882, 32123, 32391}
                    },
                    {
                            {21689, 23883, 25163, 26352, 27506, 28827, 30195}, {6892, 15385, 17840, 21606, 24287, 26753, 29204}, {5651, 23182, 25042, 26518, 27982, 29392, 30900}, {19349, 22578, 24418, 25994, 27524, 29031, 30448}, {31028, 31270, 31504, 31705, 31927, 32153, 32392}
                    }
            },
            {
                    {
                            {29089}, {16384}, {8713}, {29257}, {31610}
                    },
                    {
                            {25257, 29145}, {12287, 27293}, {7033, 27960}, {20145, 25405}, {30608, 31639}
                    },
                    {
                            {24210, 27175, 29903}, {9888, 22386, 27214}, {5901, 26053, 29293}, {18318, 22152, 28333}, {30459, 31136, 31926}
                    },
                    {
                            {22980, 25479, 27781, 29986}, {8413, 21408, 24859, 28874}, {2257, 29449, 30594, 31598}, {19189, 21202, 25915, 28620}, {31844, 32044, 32281, 32518}
                    },
                    {
                            {22217, 24567, 26637, 28683, 30548}, {7307, 16406, 19636, 24632, 28424}, {4441, 25064, 26879, 28942, 30919}, {17210, 20528, 23319, 26750, 29582}, {30674, 30953, 31396, 31735, 32207}
                    },
                    {
                            {21239, 23168, 25044, 26962, 28705, 30506}, {6545, 15012, 18004, 21817, 25503, 28701}, {3448, 26295, 27437, 28704, 30126, 31442}, {15889, 18323, 21704, 24698, 26976, 29690}, {30988, 31204, 31479, 31734, 31983, 32325}
                    },
                    {
                            {21442, 23288, 24758, 26246, 27649, 28980, 30563}, {5863, 14933, 17552, 20668, 23683, 26411, 29273}, {3415, 25810, 26877, 27990, 29223, 30394, 31618}, {17965, 20084, 22232, 23974, 26274, 28402, 30390}, {31190, 31329, 31516, 31679, 31825, 32026, 32322}
                    }
            }
    });

    /// The transformed default segmentation-prediction CDFs.
    private static final int[][] DEFAULT_SEGMENT_PREDICTION_CDFS = inverse2d(new int[][]{
            {16384},
            {16384},
            {16384}
    });

    /// The transformed default segment-id CDFs.
    private static final int[][] DEFAULT_SEGMENT_ID_CDFS = inverse2d(new int[][]{
            {5622, 7893, 16093, 18233, 27809, 28373, 32533},
            {14274, 18230, 22557, 24935, 29980, 30851, 32344},
            {27527, 28487, 28723, 28890, 32397, 32647, 32679}
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

    /// The transformed default directional angle-delta CDFs.
    private static final int[][] DEFAULT_ANGLE_DELTA_CDFS = inverse2d(new int[][]{
            {2180, 5032, 7567, 22776, 26989, 30217},
            {2301, 5608, 8801, 23487, 26974, 30330},
            {3780, 11018, 13699, 19354, 23083, 31286},
            {4581, 11226, 15147, 17138, 21834, 28397},
            {1737, 10927, 14509, 19588, 22745, 28823},
            {2664, 10176, 12485, 17650, 21600, 30495},
            {2240, 11096, 15453, 20341, 22561, 28917},
            {3605, 10428, 12459, 17676, 21244, 30655}
    });

    /// The transformed default CFL-sign CDF.
    private static final int[] DEFAULT_CFL_SIGN_CDF = inverse(1418, 2123, 13340, 18405, 26972, 28343, 32294);

    /// The transformed default CFL-alpha CDFs.
    private static final int[][] DEFAULT_CFL_ALPHA_CDFS = inverse2d(new int[][]{
            {7637, 20719, 31401, 32481, 32657, 32688, 32692, 32696, 32700, 32704, 32708, 32712, 32716, 32720, 32724},
            {14365, 23603, 28135, 31168, 32167, 32395, 32487, 32573, 32620, 32647, 32668, 32672, 32676, 32680, 32684},
            {11532, 22380, 28445, 31360, 32349, 32523, 32584, 32649, 32673, 32677, 32681, 32685, 32689, 32693, 32697},
            {26990, 31402, 32282, 32571, 32692, 32696, 32700, 32704, 32708, 32712, 32716, 32720, 32724, 32728, 32732},
            {17248, 26058, 28904, 30608, 31305, 31877, 32126, 32321, 32394, 32464, 32516, 32560, 32576, 32593, 32622},
            {14738, 21678, 25779, 27901, 29024, 30302, 30980, 31843, 32144, 32413, 32520, 32594, 32622, 32656, 32660}
    });

    /// The mutable skip CDFs for skip-flag decoding.
    private final int[][] skipCdfs;

    /// The mutable skip-mode CDFs for skip-mode decoding.
    private final int[][] skipModeCdfs;

    /// The mutable intra/inter decision CDFs.
    private final int[][] intraCdfs;

    /// The mutable compound-reference decision CDFs.
    private final int[][] compoundReferenceCdfs;

    /// The mutable compound-direction decision CDFs.
    private final int[][] compoundDirectionCdfs;

    /// The mutable single-reference selection CDFs.
    private final int[][][] singleReferenceCdfs;

    /// The mutable compound forward-reference selection CDFs.
    private final int[][][] compoundForwardReferenceCdfs;

    /// The mutable compound backward-reference selection CDFs.
    private final int[][][] compoundBackwardReferenceCdfs;

    /// The mutable compound unidirectional-reference selection CDFs.
    private final int[][][] compoundUnidirectionalReferenceCdfs;

    /// The mutable single-reference new-motion-vector CDFs.
    private final int[][] singleInterNewMvCdfs;

    /// The mutable single-reference global-motion CDFs.
    private final int[][] singleInterGlobalMvCdfs;

    /// The mutable single-reference reference-motion-vector CDFs.
    private final int[][] singleInterReferenceMvCdfs;

    /// The mutable dynamic-reference-list selection CDFs.
    private final int[][] drlCdfs;

    /// The mutable compound inter-mode CDFs.
    private final int[][] compoundInterModeCdfs;

    /// The mutable inter-intra enable CDFs.
    private final int[][] interIntraCdfs;

    /// The mutable inter-intra prediction-mode CDFs.
    private final int[][] interIntraModeCdfs;

    /// The mutable inter-intra wedge enable CDFs.
    private final int[][] interIntraWedgeCdfs;

    /// The mutable wedge-index CDFs.
    private final int[][] wedgeIndexCdfs;

    /// The mutable switchable interpolation-filter CDFs.
    private final int[][][] interpolationFilterCdfs;

    /// The mutable transform-size CDFs.
    private final int[][][] transformSizeCdfs;

    /// The mutable inter transform-partition CDFs.
    private final int[][] transformPartitionCdfs;

    /// The mutable coefficient-skip CDFs grouped by AV1 transform-context class.
    private final int[][][] coefficientSkipCdfs;

    /// The mutable end-of-block prefix CDFs grouped by clamped transform area.
    private final int[][][][] endOfBlockPrefixCdfs;

    /// The mutable end-of-block base-token CDFs grouped by AV1 transform-context class.
    private final int[][][][] endOfBlockBaseTokenCdfs;

    /// The mutable end-of-block high-bit CDFs grouped by AV1 transform-context class.
    private final int[][][][] endOfBlockHighBitCdfs;

    /// The mutable coefficient base-token CDFs for base-token context `0`.
    private final int[][][] baseTokenContext0Cdfs;

    /// The mutable coefficient base-token CDFs for the supported `TX_4X4` context range.
    private final int[][][] baseTokenGroup0Cdfs;

    /// The mutable DC-sign CDFs for luma and chroma planes.
    private final int[][][] dcSignCdfs;

    /// The mutable DC high-token CDFs for the `br_tok` context `0`.
    private final int[][][] dcHighTokenCdfs;

    /// The mutable high-token CDFs for the last scanned coefficient at `br_tok` context `7`.
    private final int[][][] endOfBlockHighTokenContext7Cdfs;

    /// The mutable coefficient high-token CDFs for the supported `TX_4X4` context range.
    private final int[][][] highTokenGroup0Cdfs;

    /// The mutable delta-q CDF.
    private final int[] deltaQCdf;

    /// The mutable delta-lf CDFs.
    private final int[][] deltaLfCdfs;

    /// The mutable motion-vector joint CDF.
    private final int[] motionVectorJointCdf;

    /// The mutable motion-vector class CDFs for vertical and horizontal components.
    private final int[][] motionVectorClassCdfs;

    /// The mutable motion-vector sign CDFs for vertical and horizontal components.
    private final int[][] motionVectorSignCdfs;

    /// The mutable class-0 motion-vector magnitude CDFs for vertical and horizontal components.
    private final int[][] motionVectorClass0Cdfs;

    /// The mutable class-0 fractional motion-vector CDFs for vertical and horizontal components.
    private final int[][][] motionVectorClass0FpCdfs;

    /// The mutable class-0 high-precision motion-vector CDFs for vertical and horizontal components.
    private final int[][] motionVectorClass0HpCdfs;

    /// The mutable non-class-0 motion-vector bit CDFs for vertical and horizontal components.
    private final int[][][] motionVectorClassNCdfs;

    /// The mutable non-class-0 fractional motion-vector CDFs for vertical and horizontal components.
    private final int[][] motionVectorClassNFpCdfs;

    /// The mutable non-class-0 high-precision motion-vector CDFs for vertical and horizontal components.
    private final int[][] motionVectorClassNHpCdfs;

    /// The mutable `intrabc` CDF.
    private final int[] intrabcCdf;

    /// The mutable luma intra-mode CDFs.
    private final int[][] yModeCdfs;

    /// The mutable `use_filter_intra` CDFs.
    private final int[][] useFilterIntraCdfs;

    /// The mutable filter-intra-mode CDF.
    private final int[] filterIntraCdf;

    /// The mutable chroma intra-mode CDFs.
    private final int[][][] uvModeCdfs;

    /// The mutable partition CDFs.
    private final int[][][] partitionCdfs;

    /// The mutable luma palette-use CDFs.
    private final int[][][] lumaPaletteCdfs;

    /// The mutable palette-size CDFs for luma and chroma planes.
    private final int[][][] paletteSizeCdfs;

    /// The mutable chroma palette-use CDFs.
    private final int[][] chromaPaletteCdfs;

    /// The mutable palette color-map CDFs for luma and chroma planes.
    private final int[][][][] colorMapCdfs;

    /// The mutable segmentation-prediction CDFs.
    private final int[][] segmentPredictionCdfs;

    /// The mutable segment-id CDFs.
    private final int[][] segmentIdCdfs;

    /// The mutable key-frame luma intra-mode CDFs.
    private final int[][][] keyFrameYModeCdfs;

    /// The mutable directional angle-delta CDFs.
    private final int[][] angleDeltaCdfs;

    /// The mutable CFL-sign CDF.
    private final int[] cflSignCdf;

    /// The mutable CFL-alpha CDFs.
    private final int[][] cflAlphaCdfs;

    /// Creates a mutable CDF context from already-copied arrays.
    ///
    /// @param skipCdfs the mutable skip CDFs
    /// @param skipModeCdfs the mutable skip-mode CDFs
    /// @param intraCdfs the mutable intra/inter decision CDFs
    /// @param compoundReferenceCdfs the mutable compound-reference decision CDFs
    /// @param compoundDirectionCdfs the mutable compound-direction decision CDFs
    /// @param singleReferenceCdfs the mutable single-reference selection CDFs
    /// @param compoundForwardReferenceCdfs the mutable compound forward-reference selection CDFs
    /// @param compoundBackwardReferenceCdfs the mutable compound backward-reference selection CDFs
    /// @param compoundUnidirectionalReferenceCdfs the mutable compound unidirectional-reference selection CDFs
    /// @param singleInterNewMvCdfs the mutable single-reference new-motion-vector CDFs
    /// @param singleInterGlobalMvCdfs the mutable single-reference global-motion CDFs
    /// @param singleInterReferenceMvCdfs the mutable single-reference reference-motion-vector CDFs
    /// @param drlCdfs the mutable dynamic-reference-list selection CDFs
    /// @param compoundInterModeCdfs the mutable compound inter-mode CDFs
    /// @param interIntraCdfs the mutable inter-intra enable CDFs
    /// @param interIntraModeCdfs the mutable inter-intra prediction-mode CDFs
    /// @param interIntraWedgeCdfs the mutable inter-intra wedge enable CDFs
    /// @param wedgeIndexCdfs the mutable wedge-index CDFs
    /// @param interpolationFilterCdfs the mutable switchable interpolation-filter CDFs
    /// @param transformSizeCdfs the mutable transform-size CDFs
    /// @param transformPartitionCdfs the mutable inter transform-partition CDFs
    /// @param coefficientSkipCdfs the mutable coefficient-skip CDFs grouped by AV1 transform-context class
    /// @param endOfBlockPrefixCdfs the mutable end-of-block prefix CDFs
    /// @param endOfBlockBaseTokenCdfs the mutable end-of-block base-token CDFs
    /// @param endOfBlockHighBitCdfs the mutable end-of-block high-bit CDFs
    /// @param baseTokenContext0Cdfs the mutable coefficient base-token CDFs for base-token context `0`
    /// @param baseTokenGroup0Cdfs the mutable coefficient base-token CDFs for the supported `TX_4X4` context range
    /// @param dcSignCdfs the mutable DC-sign CDFs
    /// @param dcHighTokenCdfs the mutable DC high-token CDFs
    /// @param endOfBlockHighTokenContext7Cdfs the mutable high-token CDFs for `br_tok` context `7`
    /// @param highTokenGroup0Cdfs the mutable coefficient high-token CDFs for the supported `TX_4X4` context range
    /// @param deltaQCdf the mutable delta-q CDF
    /// @param deltaLfCdfs the mutable delta-lf CDFs
    /// @param motionVectorJointCdf the mutable motion-vector joint CDF
    /// @param motionVectorClassCdfs the mutable motion-vector class CDFs
    /// @param motionVectorSignCdfs the mutable motion-vector sign CDFs
    /// @param motionVectorClass0Cdfs the mutable class-0 motion-vector magnitude CDFs
    /// @param motionVectorClass0FpCdfs the mutable class-0 fractional motion-vector CDFs
    /// @param motionVectorClass0HpCdfs the mutable class-0 high-precision motion-vector CDFs
    /// @param motionVectorClassNCdfs the mutable non-class-0 motion-vector bit CDFs
    /// @param motionVectorClassNFpCdfs the mutable non-class-0 fractional motion-vector CDFs
    /// @param motionVectorClassNHpCdfs the mutable non-class-0 high-precision motion-vector CDFs
    /// @param intrabcCdf the mutable `intrabc` CDF
    /// @param yModeCdfs the mutable luma intra-mode CDFs
    /// @param useFilterIntraCdfs the mutable `use_filter_intra` CDFs
    /// @param filterIntraCdf the mutable filter-intra-mode CDF
    /// @param uvModeCdfs the mutable chroma intra-mode CDFs
    /// @param partitionCdfs the mutable partition CDFs
    /// @param lumaPaletteCdfs the mutable luma palette-use CDFs
    /// @param paletteSizeCdfs the mutable palette-size CDFs
    /// @param chromaPaletteCdfs the mutable chroma palette-use CDFs
    /// @param colorMapCdfs the mutable palette color-map CDFs
    /// @param segmentPredictionCdfs the mutable segmentation-prediction CDFs
    /// @param segmentIdCdfs the mutable segment-id CDFs
    /// @param keyFrameYModeCdfs the mutable key-frame luma intra-mode CDFs
    /// @param angleDeltaCdfs the mutable directional angle-delta CDFs
    /// @param cflSignCdf the mutable CFL-sign CDF
    /// @param cflAlphaCdfs the mutable CFL-alpha CDFs
    private CdfContext(
            int[][] skipCdfs,
            int[][] skipModeCdfs,
            int[][] intraCdfs,
            int[][] compoundReferenceCdfs,
            int[][] compoundDirectionCdfs,
            int[][][] singleReferenceCdfs,
            int[][][] compoundForwardReferenceCdfs,
            int[][][] compoundBackwardReferenceCdfs,
            int[][][] compoundUnidirectionalReferenceCdfs,
            int[][] singleInterNewMvCdfs,
            int[][] singleInterGlobalMvCdfs,
            int[][] singleInterReferenceMvCdfs,
            int[][] drlCdfs,
            int[][] compoundInterModeCdfs,
            int[][] interIntraCdfs,
            int[][] interIntraModeCdfs,
            int[][] interIntraWedgeCdfs,
            int[][] wedgeIndexCdfs,
            int[][][] interpolationFilterCdfs,
            int[][][] transformSizeCdfs,
            int[][] transformPartitionCdfs,
            int[][][] coefficientSkipCdfs,
            int[][][][] endOfBlockPrefixCdfs,
            int[][][][] endOfBlockBaseTokenCdfs,
            int[][][][] endOfBlockHighBitCdfs,
            int[][][] baseTokenContext0Cdfs,
            int[][][] baseTokenGroup0Cdfs,
            int[][][] dcSignCdfs,
            int[][][] dcHighTokenCdfs,
            int[][][] endOfBlockHighTokenContext7Cdfs,
            int[][][] highTokenGroup0Cdfs,
            int[] deltaQCdf,
            int[][] deltaLfCdfs,
            int[] motionVectorJointCdf,
            int[][] motionVectorClassCdfs,
            int[][] motionVectorSignCdfs,
            int[][] motionVectorClass0Cdfs,
            int[][][] motionVectorClass0FpCdfs,
            int[][] motionVectorClass0HpCdfs,
            int[][][] motionVectorClassNCdfs,
            int[][] motionVectorClassNFpCdfs,
            int[][] motionVectorClassNHpCdfs,
            int[] intrabcCdf,
            int[][] yModeCdfs,
            int[][] useFilterIntraCdfs,
            int[] filterIntraCdf,
            int[][][] uvModeCdfs,
            int[][][] partitionCdfs,
            int[][][] lumaPaletteCdfs,
            int[][][] paletteSizeCdfs,
            int[][] chromaPaletteCdfs,
            int[][][][] colorMapCdfs,
            int[][] segmentPredictionCdfs,
            int[][] segmentIdCdfs,
            int[][][] keyFrameYModeCdfs,
            int[][] angleDeltaCdfs,
            int[] cflSignCdf,
            int[][] cflAlphaCdfs
    ) {
        this.skipCdfs = Objects.requireNonNull(skipCdfs, "skipCdfs");
        this.skipModeCdfs = Objects.requireNonNull(skipModeCdfs, "skipModeCdfs");
        this.intraCdfs = Objects.requireNonNull(intraCdfs, "intraCdfs");
        this.compoundReferenceCdfs = Objects.requireNonNull(compoundReferenceCdfs, "compoundReferenceCdfs");
        this.compoundDirectionCdfs = Objects.requireNonNull(compoundDirectionCdfs, "compoundDirectionCdfs");
        this.singleReferenceCdfs = Objects.requireNonNull(singleReferenceCdfs, "singleReferenceCdfs");
        this.compoundForwardReferenceCdfs = Objects.requireNonNull(compoundForwardReferenceCdfs, "compoundForwardReferenceCdfs");
        this.compoundBackwardReferenceCdfs = Objects.requireNonNull(compoundBackwardReferenceCdfs, "compoundBackwardReferenceCdfs");
        this.compoundUnidirectionalReferenceCdfs = Objects.requireNonNull(compoundUnidirectionalReferenceCdfs, "compoundUnidirectionalReferenceCdfs");
        this.singleInterNewMvCdfs = Objects.requireNonNull(singleInterNewMvCdfs, "singleInterNewMvCdfs");
        this.singleInterGlobalMvCdfs = Objects.requireNonNull(singleInterGlobalMvCdfs, "singleInterGlobalMvCdfs");
        this.singleInterReferenceMvCdfs = Objects.requireNonNull(singleInterReferenceMvCdfs, "singleInterReferenceMvCdfs");
        this.drlCdfs = Objects.requireNonNull(drlCdfs, "drlCdfs");
        this.compoundInterModeCdfs = Objects.requireNonNull(compoundInterModeCdfs, "compoundInterModeCdfs");
        this.interIntraCdfs = Objects.requireNonNull(interIntraCdfs, "interIntraCdfs");
        this.interIntraModeCdfs = Objects.requireNonNull(interIntraModeCdfs, "interIntraModeCdfs");
        this.interIntraWedgeCdfs = Objects.requireNonNull(interIntraWedgeCdfs, "interIntraWedgeCdfs");
        this.wedgeIndexCdfs = Objects.requireNonNull(wedgeIndexCdfs, "wedgeIndexCdfs");
        this.interpolationFilterCdfs = Objects.requireNonNull(interpolationFilterCdfs, "interpolationFilterCdfs");
        this.transformSizeCdfs = Objects.requireNonNull(transformSizeCdfs, "transformSizeCdfs");
        this.transformPartitionCdfs = Objects.requireNonNull(transformPartitionCdfs, "transformPartitionCdfs");
        this.coefficientSkipCdfs = Objects.requireNonNull(coefficientSkipCdfs, "coefficientSkipCdfs");
        this.endOfBlockPrefixCdfs = Objects.requireNonNull(endOfBlockPrefixCdfs, "endOfBlockPrefixCdfs");
        this.endOfBlockBaseTokenCdfs = Objects.requireNonNull(endOfBlockBaseTokenCdfs, "endOfBlockBaseTokenCdfs");
        this.endOfBlockHighBitCdfs = Objects.requireNonNull(endOfBlockHighBitCdfs, "endOfBlockHighBitCdfs");
        this.baseTokenContext0Cdfs = Objects.requireNonNull(baseTokenContext0Cdfs, "baseTokenContext0Cdfs");
        this.baseTokenGroup0Cdfs = Objects.requireNonNull(baseTokenGroup0Cdfs, "baseTokenGroup0Cdfs");
        this.dcSignCdfs = Objects.requireNonNull(dcSignCdfs, "dcSignCdfs");
        this.dcHighTokenCdfs = Objects.requireNonNull(dcHighTokenCdfs, "dcHighTokenCdfs");
        this.endOfBlockHighTokenContext7Cdfs = Objects.requireNonNull(
                endOfBlockHighTokenContext7Cdfs,
                "endOfBlockHighTokenContext7Cdfs"
        );
        this.highTokenGroup0Cdfs = Objects.requireNonNull(highTokenGroup0Cdfs, "highTokenGroup0Cdfs");
        this.deltaQCdf = Objects.requireNonNull(deltaQCdf, "deltaQCdf");
        this.deltaLfCdfs = Objects.requireNonNull(deltaLfCdfs, "deltaLfCdfs");
        this.motionVectorJointCdf = Objects.requireNonNull(motionVectorJointCdf, "motionVectorJointCdf");
        this.motionVectorClassCdfs = Objects.requireNonNull(motionVectorClassCdfs, "motionVectorClassCdfs");
        this.motionVectorSignCdfs = Objects.requireNonNull(motionVectorSignCdfs, "motionVectorSignCdfs");
        this.motionVectorClass0Cdfs = Objects.requireNonNull(motionVectorClass0Cdfs, "motionVectorClass0Cdfs");
        this.motionVectorClass0FpCdfs = Objects.requireNonNull(motionVectorClass0FpCdfs, "motionVectorClass0FpCdfs");
        this.motionVectorClass0HpCdfs = Objects.requireNonNull(motionVectorClass0HpCdfs, "motionVectorClass0HpCdfs");
        this.motionVectorClassNCdfs = Objects.requireNonNull(motionVectorClassNCdfs, "motionVectorClassNCdfs");
        this.motionVectorClassNFpCdfs = Objects.requireNonNull(motionVectorClassNFpCdfs, "motionVectorClassNFpCdfs");
        this.motionVectorClassNHpCdfs = Objects.requireNonNull(motionVectorClassNHpCdfs, "motionVectorClassNHpCdfs");
        this.intrabcCdf = Objects.requireNonNull(intrabcCdf, "intrabcCdf");
        this.yModeCdfs = Objects.requireNonNull(yModeCdfs, "yModeCdfs");
        this.useFilterIntraCdfs = Objects.requireNonNull(useFilterIntraCdfs, "useFilterIntraCdfs");
        this.filterIntraCdf = Objects.requireNonNull(filterIntraCdf, "filterIntraCdf");
        this.uvModeCdfs = Objects.requireNonNull(uvModeCdfs, "uvModeCdfs");
        this.partitionCdfs = Objects.requireNonNull(partitionCdfs, "partitionCdfs");
        this.lumaPaletteCdfs = Objects.requireNonNull(lumaPaletteCdfs, "lumaPaletteCdfs");
        this.paletteSizeCdfs = Objects.requireNonNull(paletteSizeCdfs, "paletteSizeCdfs");
        this.chromaPaletteCdfs = Objects.requireNonNull(chromaPaletteCdfs, "chromaPaletteCdfs");
        this.colorMapCdfs = Objects.requireNonNull(colorMapCdfs, "colorMapCdfs");
        this.segmentPredictionCdfs = Objects.requireNonNull(segmentPredictionCdfs, "segmentPredictionCdfs");
        this.segmentIdCdfs = Objects.requireNonNull(segmentIdCdfs, "segmentIdCdfs");
        this.keyFrameYModeCdfs = Objects.requireNonNull(keyFrameYModeCdfs, "keyFrameYModeCdfs");
        this.angleDeltaCdfs = Objects.requireNonNull(angleDeltaCdfs, "angleDeltaCdfs");
        this.cflSignCdf = Objects.requireNonNull(cflSignCdf, "cflSignCdf");
        this.cflAlphaCdfs = Objects.requireNonNull(cflAlphaCdfs, "cflAlphaCdfs");
    }

    /// Creates a mutable CDF context seeded with the AV1 default tables.
    ///
    /// @return a mutable CDF context seeded with the AV1 default tables
    public static CdfContext createDefault() {
        return new CdfContext(
                deepCopy(DEFAULT_SKIP_CDFS),
                deepCopy(DEFAULT_SKIP_MODE_CDFS),
                deepCopy(DEFAULT_INTRA_CDFS),
                deepCopy(DEFAULT_COMPOUND_REFERENCE_CDFS),
                deepCopy(DEFAULT_COMPOUND_DIRECTION_CDFS),
                deepCopy(DEFAULT_SINGLE_REFERENCE_CDFS),
                deepCopy(DEFAULT_COMPOUND_FORWARD_REFERENCE_CDFS),
                deepCopy(DEFAULT_COMPOUND_BACKWARD_REFERENCE_CDFS),
                deepCopy(DEFAULT_COMPOUND_UNIDIRECTIONAL_REFERENCE_CDFS),
                deepCopy(DEFAULT_SINGLE_INTER_NEWMV_CDFS),
                deepCopy(DEFAULT_SINGLE_INTER_GLOBALMV_CDFS),
                deepCopy(DEFAULT_SINGLE_INTER_REFERENCE_MV_CDFS),
                deepCopy(DEFAULT_DRL_CDFS),
                deepCopy(DEFAULT_COMPOUND_INTER_MODE_CDFS),
                deepCopy(DEFAULT_INTER_INTRA_CDFS),
                deepCopy(DEFAULT_INTER_INTRA_MODE_CDFS),
                deepCopy(DEFAULT_INTER_INTRA_WEDGE_CDFS),
                deepCopy(DEFAULT_WEDGE_INDEX_CDFS),
                deepCopy(DEFAULT_INTERPOLATION_FILTER_CDFS),
                deepCopy(DEFAULT_TRANSFORM_SIZE_CDFS),
                deepCopy(DEFAULT_TRANSFORM_PARTITION_CDFS),
                deepCopy(DEFAULT_COEFFICIENT_SKIP_CDFS),
                deepCopy(DEFAULT_END_OF_BLOCK_PREFIX_CDFS),
                deepCopy(DEFAULT_END_OF_BLOCK_BASE_TOKEN_CDFS),
                deepCopy(DEFAULT_END_OF_BLOCK_HIGH_BIT_CDFS),
                deepCopy(DEFAULT_BASE_TOKEN_CONTEXT0_CDFS),
                deepCopy(DEFAULT_BASE_TOKEN_GROUP0_CDFS),
                deepCopy(DEFAULT_DC_SIGN_CDFS),
                deepCopy(DEFAULT_DC_HIGH_TOKEN_CDFS),
                deepCopy(DEFAULT_END_OF_BLOCK_HIGH_TOKEN_CONTEXT7_CDFS),
                deepCopy(DEFAULT_HIGH_TOKEN_GROUP0_CDFS),
                Arrays.copyOf(DEFAULT_DELTA_Q_CDF, DEFAULT_DELTA_Q_CDF.length),
                deepCopy(DEFAULT_DELTA_LF_CDFS),
                Arrays.copyOf(DEFAULT_MOTION_VECTOR_JOINT_CDF, DEFAULT_MOTION_VECTOR_JOINT_CDF.length),
                deepCopy(DEFAULT_MOTION_VECTOR_CLASS_CDFS),
                deepCopy(DEFAULT_MOTION_VECTOR_SIGN_CDFS),
                deepCopy(DEFAULT_MOTION_VECTOR_CLASS0_CDFS),
                deepCopy(DEFAULT_MOTION_VECTOR_CLASS0_FP_CDFS),
                deepCopy(DEFAULT_MOTION_VECTOR_CLASS0_HP_CDFS),
                deepCopy(DEFAULT_MOTION_VECTOR_CLASSN_CDFS),
                deepCopy(DEFAULT_MOTION_VECTOR_CLASSN_FP_CDFS),
                deepCopy(DEFAULT_MOTION_VECTOR_CLASSN_HP_CDFS),
                Arrays.copyOf(DEFAULT_INTRABC_CDF, DEFAULT_INTRABC_CDF.length),
                deepCopy(DEFAULT_Y_MODE_CDFS),
                deepCopy(DEFAULT_USE_FILTER_INTRA_CDFS),
                Arrays.copyOf(DEFAULT_FILTER_INTRA_CDF, DEFAULT_FILTER_INTRA_CDF.length),
                deepCopy(DEFAULT_UV_MODE_CDFS),
                deepCopy(DEFAULT_PARTITION_CDFS),
                deepCopy(DEFAULT_LUMA_PALETTE_CDFS),
                deepCopy(DEFAULT_PALETTE_SIZE_CDFS),
                deepCopy(DEFAULT_CHROMA_PALETTE_CDFS),
                deepCopy(DEFAULT_COLOR_MAP_CDFS),
                deepCopy(DEFAULT_SEGMENT_PREDICTION_CDFS),
                deepCopy(DEFAULT_SEGMENT_ID_CDFS),
                deepCopy(DEFAULT_KEY_FRAME_Y_MODE_CDFS),
                deepCopy(DEFAULT_ANGLE_DELTA_CDFS),
                Arrays.copyOf(DEFAULT_CFL_SIGN_CDF, DEFAULT_CFL_SIGN_CDF.length),
                deepCopy(DEFAULT_CFL_ALPHA_CDFS)
        );
    }

    /// Creates a deep copy of this mutable CDF context.
    ///
    /// @return a deep copy of this mutable CDF context
    public CdfContext copy() {
        return new CdfContext(
                deepCopy(skipCdfs),
                deepCopy(skipModeCdfs),
                deepCopy(intraCdfs),
                deepCopy(compoundReferenceCdfs),
                deepCopy(compoundDirectionCdfs),
                deepCopy(singleReferenceCdfs),
                deepCopy(compoundForwardReferenceCdfs),
                deepCopy(compoundBackwardReferenceCdfs),
                deepCopy(compoundUnidirectionalReferenceCdfs),
                deepCopy(singleInterNewMvCdfs),
                deepCopy(singleInterGlobalMvCdfs),
                deepCopy(singleInterReferenceMvCdfs),
                deepCopy(drlCdfs),
                deepCopy(compoundInterModeCdfs),
                deepCopy(interIntraCdfs),
                deepCopy(interIntraModeCdfs),
                deepCopy(interIntraWedgeCdfs),
                deepCopy(wedgeIndexCdfs),
                deepCopy(interpolationFilterCdfs),
                deepCopy(transformSizeCdfs),
                deepCopy(transformPartitionCdfs),
                deepCopy(coefficientSkipCdfs),
                deepCopy(endOfBlockPrefixCdfs),
                deepCopy(endOfBlockBaseTokenCdfs),
                deepCopy(endOfBlockHighBitCdfs),
                deepCopy(baseTokenContext0Cdfs),
                deepCopy(baseTokenGroup0Cdfs),
                deepCopy(dcSignCdfs),
                deepCopy(dcHighTokenCdfs),
                deepCopy(endOfBlockHighTokenContext7Cdfs),
                deepCopy(highTokenGroup0Cdfs),
                Arrays.copyOf(deltaQCdf, deltaQCdf.length),
                deepCopy(deltaLfCdfs),
                Arrays.copyOf(motionVectorJointCdf, motionVectorJointCdf.length),
                deepCopy(motionVectorClassCdfs),
                deepCopy(motionVectorSignCdfs),
                deepCopy(motionVectorClass0Cdfs),
                deepCopy(motionVectorClass0FpCdfs),
                deepCopy(motionVectorClass0HpCdfs),
                deepCopy(motionVectorClassNCdfs),
                deepCopy(motionVectorClassNFpCdfs),
                deepCopy(motionVectorClassNHpCdfs),
                Arrays.copyOf(intrabcCdf, intrabcCdf.length),
                deepCopy(yModeCdfs),
                deepCopy(useFilterIntraCdfs),
                Arrays.copyOf(filterIntraCdf, filterIntraCdf.length),
                deepCopy(uvModeCdfs),
                deepCopy(partitionCdfs),
                deepCopy(lumaPaletteCdfs),
                deepCopy(paletteSizeCdfs),
                deepCopy(chromaPaletteCdfs),
                deepCopy(colorMapCdfs),
                deepCopy(segmentPredictionCdfs),
                deepCopy(segmentIdCdfs),
                deepCopy(keyFrameYModeCdfs),
                deepCopy(angleDeltaCdfs),
                Arrays.copyOf(cflSignCdf, cflSignCdf.length),
                deepCopy(cflAlphaCdfs)
        );
    }

    /// Returns the live mutable skip CDF for the supplied context index.
    ///
    /// @param context the zero-based skip context index
    /// @return the live mutable skip CDF for the supplied context index
    public int[] mutableSkipCdf(int context) {
        return skipCdfs[Objects.checkIndex(context, skipCdfs.length)];
    }

    /// Returns the live mutable skip-mode CDF for the supplied context index.
    ///
    /// @param context the zero-based skip-mode context index
    /// @return the live mutable skip-mode CDF for the supplied context index
    public int[] mutableSkipModeCdf(int context) {
        return skipModeCdfs[Objects.checkIndex(context, skipModeCdfs.length)];
    }

    /// Returns the live mutable intra/inter decision CDF for the supplied context index.
    ///
    /// @param context the zero-based intra/inter context index
    /// @return the live mutable intra/inter decision CDF for the supplied context index
    public int[] mutableIntraCdf(int context) {
        return intraCdfs[Objects.checkIndex(context, intraCdfs.length)];
    }

    /// Returns the live mutable compound-reference decision CDF for the supplied context index.
    ///
    /// @param context the zero-based compound-reference context index in `[0, 5)`
    /// @return the live mutable compound-reference decision CDF for the supplied context index
    public int[] mutableCompoundReferenceCdf(int context) {
        return compoundReferenceCdfs[Objects.checkIndex(context, compoundReferenceCdfs.length)];
    }

    /// Returns the live mutable compound-direction decision CDF for the supplied context index.
    ///
    /// @param context the zero-based compound-direction context index in `[0, 5)`
    /// @return the live mutable compound-direction decision CDF for the supplied context index
    public int[] mutableCompoundDirectionCdf(int context) {
        return compoundDirectionCdfs[Objects.checkIndex(context, compoundDirectionCdfs.length)];
    }

    /// Returns the live mutable single-reference selection CDF for the supplied table and context.
    ///
    /// @param tableIndex the zero-based single-reference table index in `[0, 6)`
    /// @param context the zero-based context index in `[0, 3)`
    /// @return the live mutable single-reference selection CDF for the supplied inputs
    public int[] mutableSingleReferenceCdf(int tableIndex, int context) {
        int[][] table = singleReferenceCdfs[Objects.checkIndex(tableIndex, singleReferenceCdfs.length)];
        return table[Objects.checkIndex(context, table.length)];
    }

    /// Returns the live mutable compound forward-reference selection CDF for the supplied table and context.
    ///
    /// @param tableIndex the zero-based compound forward-reference table index in `[0, 3)`
    /// @param context the zero-based context index in `[0, 3)`
    /// @return the live mutable compound forward-reference selection CDF for the supplied inputs
    public int[] mutableCompoundForwardReferenceCdf(int tableIndex, int context) {
        int[][] table = compoundForwardReferenceCdfs[Objects.checkIndex(tableIndex, compoundForwardReferenceCdfs.length)];
        return table[Objects.checkIndex(context, table.length)];
    }

    /// Returns the live mutable compound backward-reference selection CDF for the supplied table and context.
    ///
    /// @param tableIndex the zero-based compound backward-reference table index in `[0, 2)`
    /// @param context the zero-based context index in `[0, 3)`
    /// @return the live mutable compound backward-reference selection CDF for the supplied inputs
    public int[] mutableCompoundBackwardReferenceCdf(int tableIndex, int context) {
        int[][] table = compoundBackwardReferenceCdfs[Objects.checkIndex(tableIndex, compoundBackwardReferenceCdfs.length)];
        return table[Objects.checkIndex(context, table.length)];
    }

    /// Returns the live mutable compound unidirectional-reference selection CDF for the supplied table and context.
    ///
    /// @param tableIndex the zero-based compound unidirectional-reference table index in `[0, 3)`
    /// @param context the zero-based context index in `[0, 3)`
    /// @return the live mutable compound unidirectional-reference selection CDF for the supplied inputs
    public int[] mutableCompoundUnidirectionalReferenceCdf(int tableIndex, int context) {
        int[][] table = compoundUnidirectionalReferenceCdfs[Objects.checkIndex(tableIndex, compoundUnidirectionalReferenceCdfs.length)];
        return table[Objects.checkIndex(context, table.length)];
    }

    /// Returns the live mutable single-reference new-motion-vector CDF for the supplied context index.
    ///
    /// @param context the zero-based single-reference new-motion-vector context index in `[0, 6)`
    /// @return the live mutable single-reference new-motion-vector CDF for the supplied context index
    public int[] mutableSingleInterNewMvCdf(int context) {
        return singleInterNewMvCdfs[Objects.checkIndex(context, singleInterNewMvCdfs.length)];
    }

    /// Returns the live mutable single-reference global-motion CDF for the supplied context index.
    ///
    /// @param context the zero-based single-reference global-motion context index in `[0, 2)`
    /// @return the live mutable single-reference global-motion CDF for the supplied context index
    public int[] mutableSingleInterGlobalMvCdf(int context) {
        return singleInterGlobalMvCdfs[Objects.checkIndex(context, singleInterGlobalMvCdfs.length)];
    }

    /// Returns the live mutable single-reference reference-motion-vector CDF for the supplied context index.
    ///
    /// @param context the zero-based single-reference reference-motion-vector context index in `[0, 6)`
    /// @return the live mutable single-reference reference-motion-vector CDF for the supplied context index
    public int[] mutableSingleInterReferenceMvCdf(int context) {
        return singleInterReferenceMvCdfs[Objects.checkIndex(context, singleInterReferenceMvCdfs.length)];
    }

    /// Returns the live mutable dynamic-reference-list selection CDF for the supplied context index.
    ///
    /// @param context the zero-based dynamic-reference-list context index in `[0, 3)`
    /// @return the live mutable dynamic-reference-list selection CDF for the supplied context index
    public int[] mutableDrlCdf(int context) {
        return drlCdfs[Objects.checkIndex(context, drlCdfs.length)];
    }

    /// Returns the live mutable compound inter-mode CDF for the supplied context index.
    ///
    /// @param context the zero-based compound inter-mode context index in `[0, 8)`
    /// @return the live mutable compound inter-mode CDF for the supplied context index
    public int[] mutableCompoundInterModeCdf(int context) {
        return compoundInterModeCdfs[Objects.checkIndex(context, compoundInterModeCdfs.length)];
    }

    /// Returns the live mutable inter-intra enable CDF for the supplied context index.
    ///
    /// @param context the zero-based inter-intra context index in `[0, 4)`
    /// @return the live mutable inter-intra enable CDF for the supplied context index
    public int[] mutableInterIntraCdf(int context) {
        return interIntraCdfs[Objects.checkIndex(context, interIntraCdfs.length)];
    }

    /// Returns the live mutable inter-intra prediction-mode CDF for the supplied context index.
    ///
    /// @param context the zero-based inter-intra mode context index in `[0, 4)`
    /// @return the live mutable inter-intra prediction-mode CDF for the supplied context index
    public int[] mutableInterIntraModeCdf(int context) {
        return interIntraModeCdfs[Objects.checkIndex(context, interIntraModeCdfs.length)];
    }

    /// Returns the live mutable inter-intra wedge enable CDF for the supplied context index.
    ///
    /// @param context the zero-based wedge context index in `[0, 7)`
    /// @return the live mutable inter-intra wedge enable CDF for the supplied context index
    public int[] mutableInterIntraWedgeCdf(int context) {
        return interIntraWedgeCdfs[Objects.checkIndex(context, interIntraWedgeCdfs.length)];
    }

    /// Returns the live mutable wedge-index CDF for the supplied context index.
    ///
    /// @param context the zero-based wedge context index in `[0, 9)`
    /// @return the live mutable wedge-index CDF for the supplied context index
    public int[] mutableWedgeIndexCdf(int context) {
        return wedgeIndexCdfs[Objects.checkIndex(context, wedgeIndexCdfs.length)];
    }

    /// Returns the live mutable switchable interpolation-filter CDF for the supplied direction and context.
    ///
    /// Direction `0` selects the horizontal filter symbol and direction `1` selects the vertical
    /// filter symbol.
    ///
    /// @param direction the zero-based interpolation-filter direction index in `[0, 2)`
    /// @param context the zero-based switchable interpolation-filter context index in `[0, 8)`
    /// @return the live mutable switchable interpolation-filter CDF for the supplied direction and context
    public int[] mutableInterpolationFilterCdf(int direction, int context) {
        int[][] directionCdfs = interpolationFilterCdfs[Objects.checkIndex(direction, interpolationFilterCdfs.length)];
        return directionCdfs[Objects.checkIndex(context, directionCdfs.length)];
    }

    /// Returns the live mutable transform-size CDF for the supplied max-size table and context.
    ///
    /// @param tableIndex the zero-based max-transform-size table index in `[0, 4)`
    /// @param context the zero-based transform-size context index in `[0, 3)`
    /// @return the live mutable transform-size CDF for the supplied inputs
    public int[] mutableTransformSizeCdf(int tableIndex, int context) {
        int[][] table = transformSizeCdfs[Objects.checkIndex(tableIndex, transformSizeCdfs.length)];
        return table[Objects.checkIndex(context, table.length)];
    }

    /// Returns the live mutable inter transform-partition CDF for the supplied table and context.
    ///
    /// The table index follows `dav1d`'s `cat` formula from `read_tx_tree()`.
    ///
    /// @param tableIndex the zero-based inter transform-partition table index in `[0, 7)`
    /// @param context the zero-based context index in `[0, 3)`
    /// @return the live mutable inter transform-partition CDF for the supplied inputs
    public int[] mutableTransformPartitionCdf(int tableIndex, int context) {
        int baseIndex = Objects.checkIndex(tableIndex, 7) * 3;
        return transformPartitionCdfs[baseIndex + Objects.checkIndex(context, 3)];
    }

    /// Returns the live mutable coefficient-skip CDF for the supplied transform-context group and context index.
    ///
    /// @param transformContextIndex the zero-based AV1 transform-context group index in `[0, 5)`
    /// @param context the zero-based coefficient-skip context index in `[0, 13)`
    /// @return the live mutable coefficient-skip CDF for the supplied inputs
    public int[] mutableCoefficientSkipCdf(int transformContextIndex, int context) {
        int[][] table = coefficientSkipCdfs[Objects.checkIndex(transformContextIndex, coefficientSkipCdfs.length)];
        return table[Objects.checkIndex(context, table.length)];
    }

    /// Returns the live mutable end-of-block prefix CDF for the supplied transform-area context.
    ///
    /// @param tx2dSizeContext the clamped transform-area context in `[0, 7)`
    /// @param chroma whether the syntax belongs to a chroma plane
    /// @param oneDimensional whether the active transform type belongs to a 1D transform class
    /// @return the live mutable end-of-block prefix CDF for the supplied inputs
    public int[] mutableEndOfBlockPrefixCdf(int tx2dSizeContext, boolean chroma, boolean oneDimensional) {
        int[][][] areaTable = endOfBlockPrefixCdfs[Objects.checkIndex(tx2dSizeContext, endOfBlockPrefixCdfs.length)];
        int[][] chromaTable = areaTable[chroma ? 1 : 0];
        return chromaTable[oneDimensional ? 1 : 0];
    }

    /// Returns the live mutable end-of-block base-token CDF for the supplied transform context.
    ///
    /// @param transformContextIndex the AV1 transform-context group index in `[0, 5)`
    /// @param chroma whether the syntax belongs to a chroma plane
    /// @param context the zero-based end-of-block base-token context in `[0, 4)`
    /// @return the live mutable end-of-block base-token CDF for the supplied inputs
    public int[] mutableEndOfBlockBaseTokenCdf(int transformContextIndex, boolean chroma, int context) {
        int[][][] transformTable = endOfBlockBaseTokenCdfs[Objects.checkIndex(transformContextIndex, endOfBlockBaseTokenCdfs.length)];
        int[][] chromaTable = transformTable[chroma ? 1 : 0];
        return chromaTable[Objects.checkIndex(context, chromaTable.length)];
    }

    /// Returns the live mutable end-of-block high-bit CDF for the supplied transform context.
    ///
    /// @param transformContextIndex the AV1 transform-context group index in `[0, 5)`
    /// @param chroma whether the syntax belongs to a chroma plane
    /// @param context the zero-based end-of-block high-bit context index in `[0, 9)`
    /// @return the live mutable end-of-block high-bit CDF for the supplied inputs
    public int[] mutableEndOfBlockHighBitCdf(int transformContextIndex, boolean chroma, int context) {
        int[][][] transformTable = endOfBlockHighBitCdfs[Objects.checkIndex(transformContextIndex, endOfBlockHighBitCdfs.length)];
        int[][] chromaTable = transformTable[chroma ? 1 : 0];
        return chromaTable[Objects.checkIndex(context, chromaTable.length)];
    }

    /// Returns the live mutable coefficient base-token CDF for the supplied transform context and base-token context.
    ///
    /// The current implementation exposes the full supported `TX_4X4` context range for transform
    /// context group `0`, and falls back to base-token context `0` for the larger-transform paths
    /// that still decode only the first non-zero AC coefficient.
    ///
    /// @param transformContextIndex the AV1 transform-context group index in `[0, 5)`
    /// @param chroma whether the syntax belongs to a chroma plane
    /// @param context the zero-based base-token context index
    /// @return the live mutable coefficient base-token CDF for the supplied inputs
    public int[] mutableBaseTokenCdf(int transformContextIndex, boolean chroma, int context) {
        if (transformContextIndex == 0) {
            int[][] planeTable = baseTokenGroup0Cdfs[chroma ? 1 : 0];
            if (context >= 0 && context < planeTable.length) {
                return planeTable[context];
            }
        }
        if (context != 0) {
            throw new IllegalArgumentException("Unsupported base-token context: " + context);
        }
        int[][] transformTable = baseTokenContext0Cdfs[Objects.checkIndex(transformContextIndex, baseTokenContext0Cdfs.length)];
        return transformTable[chroma ? 1 : 0];
    }

    /// Returns the live mutable DC-sign CDF for the supplied plane and context.
    ///
    /// @param chroma whether the syntax belongs to a chroma plane
    /// @param context the zero-based DC-sign context in `[0, 3)`
    /// @return the live mutable DC-sign CDF for the supplied plane and context
    public int[] mutableDcSignCdf(boolean chroma, int context) {
        int[][] planeTable = dcSignCdfs[chroma ? 1 : 0];
        return planeTable[Objects.checkIndex(context, planeTable.length)];
    }

    /// Returns the live mutable DC high-token CDF for the supplied transform context and plane.
    ///
    /// @param transformContextIndex the AV1 transform-context group index in `[0, 4)`
    /// @param chroma whether the syntax belongs to a chroma plane
    /// @return the live mutable DC high-token CDF for the supplied inputs
    public int[] mutableDcHighTokenCdf(int transformContextIndex, boolean chroma) {
        int[][] transformTable = dcHighTokenCdfs[Objects.checkIndex(transformContextIndex, dcHighTokenCdfs.length)];
        return transformTable[chroma ? 1 : 0];
    }

    /// Returns the live mutable coefficient high-token CDF for the supplied transform context and `br_tok` context.
    ///
    /// The current implementation exposes the full supported `TX_4X4` context range for transform
    /// context group `0`, and falls back to `br_tok` contexts `0` and `7` for the larger-transform
    /// paths that still decode only the first non-zero AC coefficient.
    ///
    /// @param transformContextIndex the AV1 transform-context group index in `[0, 5)`
    /// @param chroma whether the syntax belongs to a chroma plane
    /// @param context the zero-based `br_tok` context index
    /// @return the live mutable coefficient high-token CDF for the supplied inputs
    public int[] mutableHighTokenCdf(int transformContextIndex, boolean chroma, int context) {
        if (transformContextIndex == 0) {
            int[][] planeTable = highTokenGroup0Cdfs[chroma ? 1 : 0];
            if (context >= 0 && context < planeTable.length) {
                return planeTable[context];
            }
        }
        if (context == 0) {
            return mutableDcHighTokenCdf(transformContextIndex, chroma);
        }
        if (context != 7) {
            throw new IllegalArgumentException("Unsupported high-token context: " + context);
        }
        int[][] transformTable = endOfBlockHighTokenContext7Cdfs[
                Objects.checkIndex(Math.min(transformContextIndex, 3), endOfBlockHighTokenContext7Cdfs.length)
        ];
        return transformTable[chroma ? 1 : 0];
    }

    /// Returns the live mutable delta-q CDF.
    ///
    /// @return the live mutable delta-q CDF
    public int[] mutableDeltaQCdf() {
        return deltaQCdf;
    }

    /// Returns the live mutable delta-lf CDF for the supplied context index.
    ///
    /// @param context the zero-based delta-lf context index in `[0, 5)`
    /// @return the live mutable delta-lf CDF for the supplied context index
    public int[] mutableDeltaLfCdf(int context) {
        return deltaLfCdfs[Objects.checkIndex(context, deltaLfCdfs.length)];
    }

    /// Returns the live mutable motion-vector joint CDF.
    ///
    /// @return the live mutable motion-vector joint CDF
    public int[] mutableMotionVectorJointCdf() {
        return motionVectorJointCdf;
    }

    /// Returns the live mutable motion-vector class CDF for the supplied component.
    ///
    /// @param component the zero-based motion-vector component index, where `0` is vertical and `1` is horizontal
    /// @return the live mutable motion-vector class CDF for the supplied component
    public int[] mutableMotionVectorClassCdf(int component) {
        return motionVectorClassCdfs[Objects.checkIndex(component, motionVectorClassCdfs.length)];
    }

    /// Returns the live mutable motion-vector sign CDF for the supplied component.
    ///
    /// @param component the zero-based motion-vector component index, where `0` is vertical and `1` is horizontal
    /// @return the live mutable motion-vector sign CDF for the supplied component
    public int[] mutableMotionVectorSignCdf(int component) {
        return motionVectorSignCdfs[Objects.checkIndex(component, motionVectorSignCdfs.length)];
    }

    /// Returns the live mutable class-0 motion-vector magnitude CDF for the supplied component.
    ///
    /// @param component the zero-based motion-vector component index, where `0` is vertical and `1` is horizontal
    /// @return the live mutable class-0 motion-vector magnitude CDF for the supplied component
    public int[] mutableMotionVectorClass0Cdf(int component) {
        return motionVectorClass0Cdfs[Objects.checkIndex(component, motionVectorClass0Cdfs.length)];
    }

    /// Returns the live mutable class-0 fractional motion-vector CDF for the supplied component and integer bit.
    ///
    /// @param component the zero-based motion-vector component index, where `0` is vertical and `1` is horizontal
    /// @param integerBit the decoded class-0 integer bit in `[0, 2)`
    /// @return the live mutable class-0 fractional motion-vector CDF for the supplied inputs
    public int[] mutableMotionVectorClass0FpCdf(int component, int integerBit) {
        int[][] tables = motionVectorClass0FpCdfs[Objects.checkIndex(component, motionVectorClass0FpCdfs.length)];
        return tables[Objects.checkIndex(integerBit, tables.length)];
    }

    /// Returns the live mutable class-0 high-precision motion-vector CDF for the supplied component.
    ///
    /// @param component the zero-based motion-vector component index, where `0` is vertical and `1` is horizontal
    /// @return the live mutable class-0 high-precision motion-vector CDF for the supplied component
    public int[] mutableMotionVectorClass0HpCdf(int component) {
        return motionVectorClass0HpCdfs[Objects.checkIndex(component, motionVectorClass0HpCdfs.length)];
    }

    /// Returns the live mutable non-class-0 motion-vector bit CDF for the supplied component and bit index.
    ///
    /// @param component the zero-based motion-vector component index, where `0` is vertical and `1` is horizontal
    /// @param bitIndex the zero-based motion-vector class bit index in `[0, 10)`
    /// @return the live mutable non-class-0 motion-vector bit CDF for the supplied inputs
    public int[] mutableMotionVectorClassNCdf(int component, int bitIndex) {
        int[][] tables = motionVectorClassNCdfs[Objects.checkIndex(component, motionVectorClassNCdfs.length)];
        return tables[Objects.checkIndex(bitIndex, tables.length)];
    }

    /// Returns the live mutable non-class-0 fractional motion-vector CDF for the supplied component.
    ///
    /// @param component the zero-based motion-vector component index, where `0` is vertical and `1` is horizontal
    /// @return the live mutable non-class-0 fractional motion-vector CDF for the supplied component
    public int[] mutableMotionVectorClassNFpCdf(int component) {
        return motionVectorClassNFpCdfs[Objects.checkIndex(component, motionVectorClassNFpCdfs.length)];
    }

    /// Returns the live mutable non-class-0 high-precision motion-vector CDF for the supplied component.
    ///
    /// @param component the zero-based motion-vector component index, where `0` is vertical and `1` is horizontal
    /// @return the live mutable non-class-0 high-precision motion-vector CDF for the supplied component
    public int[] mutableMotionVectorClassNHpCdf(int component) {
        return motionVectorClassNHpCdfs[Objects.checkIndex(component, motionVectorClassNHpCdfs.length)];
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

    /// Returns the live mutable `use_filter_intra` CDF for the supplied block-size index.
    ///
    /// @param sizeIndex the zero-based block-size index in `dav1d` `N_BS_SIZES` order
    /// @return the live mutable `use_filter_intra` CDF for the supplied block-size index
    public int[] mutableUseFilterIntraCdf(int sizeIndex) {
        return useFilterIntraCdfs[Objects.checkIndex(sizeIndex, useFilterIntraCdfs.length)];
    }

    /// Returns the live mutable filter-intra-mode CDF.
    ///
    /// @return the live mutable filter-intra-mode CDF
    public int[] mutableFilterIntraCdf() {
        return filterIntraCdf;
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

    /// Returns the live mutable luma palette-use CDF for the supplied size and palette contexts.
    ///
    /// @param sizeContext the zero-based palette size context in `[0, 7)`
    /// @param paletteContext the zero-based above/left palette context in `[0, 3)`
    /// @return the live mutable luma palette-use CDF for the supplied contexts
    public int[] mutableLumaPaletteCdf(int sizeContext, int paletteContext) {
        int[][] level = lumaPaletteCdfs[Objects.checkIndex(sizeContext, lumaPaletteCdfs.length)];
        return level[Objects.checkIndex(paletteContext, level.length)];
    }

    /// Returns the live mutable palette-size CDF for the supplied plane and size context.
    ///
    /// @param plane the palette plane index, where `0` is luma and `1` is chroma
    /// @param sizeContext the zero-based palette size context in `[0, 7)`
    /// @return the live mutable palette-size CDF for the supplied plane and size context
    public int[] mutablePaletteSizeCdf(int plane, int sizeContext) {
        int[][] table = paletteSizeCdfs[Objects.checkIndex(plane, paletteSizeCdfs.length)];
        return table[Objects.checkIndex(sizeContext, table.length)];
    }

    /// Returns the live mutable chroma palette-use CDF for the supplied context index.
    ///
    /// @param paletteContext the zero-based chroma palette context in `[0, 2)`
    /// @return the live mutable chroma palette-use CDF for the supplied context index
    public int[] mutableChromaPaletteCdf(int paletteContext) {
        return chromaPaletteCdfs[Objects.checkIndex(paletteContext, chromaPaletteCdfs.length)];
    }

    /// Returns the live mutable palette color-map CDF for the supplied plane, palette size, and context.
    ///
    /// @param plane the palette plane index, where `0` is luma and `1` is chroma
    /// @param paletteSizeIndex the zero-based palette-size-minus-two index in `[0, 7)`
    /// @param context the zero-based color-map context in `[0, 5)`
    /// @return the live mutable palette color-map CDF for the supplied inputs
    public int[] mutableColorMapCdf(int plane, int paletteSizeIndex, int context) {
        int[][][] planeTable = colorMapCdfs[Objects.checkIndex(plane, colorMapCdfs.length)];
        int[][] sizeTable = planeTable[Objects.checkIndex(paletteSizeIndex, planeTable.length)];
        return sizeTable[Objects.checkIndex(context, sizeTable.length)];
    }

    /// Returns the live mutable segmentation-prediction CDF for the supplied context index.
    ///
    /// @param context the zero-based segmentation-prediction context index in `[0, 3)`
    /// @return the live mutable segmentation-prediction CDF for the supplied context index
    public int[] mutableSegmentPredictionCdf(int context) {
        return segmentPredictionCdfs[Objects.checkIndex(context, segmentPredictionCdfs.length)];
    }

    /// Returns the live mutable segment-id CDF for the supplied segment context.
    ///
    /// @param context the zero-based segment-id context index in `[0, 3)`
    /// @return the live mutable segment-id CDF for the supplied segment context
    public int[] mutableSegmentIdCdf(int context) {
        return segmentIdCdfs[Objects.checkIndex(context, segmentIdCdfs.length)];
    }

    /// Returns the live mutable directional angle-delta CDF for the supplied directional mode index.
    ///
    /// @param directionalModeIndex the zero-based directional-mode index in `[0, 8)`
    /// @return the live mutable directional angle-delta CDF for the supplied directional mode index
    public int[] mutableAngleDeltaCdf(int directionalModeIndex) {
        return angleDeltaCdfs[Objects.checkIndex(directionalModeIndex, angleDeltaCdfs.length)];
    }

    /// Returns the live mutable CFL-sign CDF.
    ///
    /// @return the live mutable CFL-sign CDF
    public int[] mutableCflSignCdf() {
        return cflSignCdf;
    }

    /// Returns the live mutable CFL-alpha CDF for the supplied sign context.
    ///
    /// @param context the zero-based CFL-alpha context index in `[0, 6)`
    /// @return the live mutable CFL-alpha CDF for the supplied sign context
    public int[] mutableCflAlphaCdf(int context) {
        return cflAlphaCdfs[Objects.checkIndex(context, cflAlphaCdfs.length)];
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

    /// Converts a four-dimensional table of raw `dav1d` thresholds into inverse-ordered AV1 CDF arrays.
    ///
    /// @param rawCdfs the four-dimensional table of raw threshold values
    /// @return the transformed inverse-ordered AV1 CDF arrays
    private static int[][][][] inverse4d(int[][][][] rawCdfs) {
        int[][][][] transformed = new int[rawCdfs.length][][][];
        for (int i = 0; i < rawCdfs.length; i++) {
            transformed[i] = inverse3d(rawCdfs[i]);
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

    /// Creates a deep copy of a four-dimensional integer table.
    ///
    /// @param source the source table to copy
    /// @return a deep copy of the supplied table
    private static int[][][][] deepCopy(int[][][][] source) {
        int[][][][] copy = new int[source.length][][][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = deepCopy(source[i]);
        }
        return copy;
    }
}
