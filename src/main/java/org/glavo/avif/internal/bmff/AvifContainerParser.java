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

import org.glavo.avif.AvifAuxiliaryImageInfo;
import org.glavo.avif.AvifColorInfo;
import org.glavo.avif.AvifBitDepth;
import org.glavo.avif.AvifDecodeException;
import org.glavo.avif.AvifErrorCode;
import org.glavo.avif.AvifGainMapInfo;
import org.glavo.avif.AvifImageInfo;
import org.glavo.avif.AvifPixelFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Parser for the AVIF primary-item subset of BMFF.
@NotNullByDefault
public final class AvifContainerParser {
    /// The MIME content type used by AVIF XMP metadata items.
    private static final String XMP_CONTENT_TYPE = "application/rdf+xml";
    /// The maximum gain-map metadata version supported by this parser.
    private static final int SUPPORTED_GAIN_MAP_METADATA_VERSION = 0;

    /// The source bytes.
    private final byte @Unmodifiable [] source;
    /// The parsed metadata state.
    private final MetaState meta = new MetaState();
    /// Whether an AVIF-compatible `ftyp` box was parsed.
    private boolean compatibleFileTypeSeen;
    /// Whether an `avis` brand was parsed.
    private boolean avisBrandSeen;
    /// Whether a `tmap` compatible brand was parsed.
    private boolean tmapBrandSeen;

    /// Creates an AVIF container parser.
    ///
    /// @param source the source bytes
    private AvifContainerParser(byte[] source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /// Parses AVIF container data.
    ///
    /// @param source the source bytes
    /// @return parsed AVIF container data
    /// @throws AvifDecodeException if the container is malformed or unsupported
    public static AvifContainer parse(byte[] source) throws AvifDecodeException {
        return new AvifContainerParser(source).parse();
    }

    /// Parses AVIF container data.
    ///
    /// @return parsed AVIF container data
    /// @throws AvifDecodeException if the container is malformed or unsupported
    private AvifContainer parse() throws AvifDecodeException {
        BoxInput input = new BoxInput(source);
        while (input.hasRemaining()) {
            BoxHeader header = input.readBoxHeader();
            BoxInput payload = input.slice(header.payloadOffset(), header.payloadSize());
            switch (header.type()) {
                case "ftyp" -> parseFileType(payload);
                case "meta" -> parseMeta(header, payload);
                case "moov" -> {
                    avisBrandSeen = true;
                    parseMoov(payload);
                }
                default -> {
                }
            }
            input.skipBoxPayload(header);
        }

        if (!compatibleFileTypeSeen) {
            throw new AvifDecodeException(AvifErrorCode.INVALID_FTYP, "Missing AVIF-compatible ftyp box", 0L);
        }
        if (avisBrandSeen) {
            return parseSequenceImage();
        }
        if (meta.primaryItemId == 0) {
            throw new AvifDecodeException(AvifErrorCode.MISSING_IMAGE_ITEM, "Primary item is not specified", null);
        }

        Item primaryItem = meta.item(meta.primaryItemId);
        if (primaryItem == null || primaryItem.hasUnsupportedEssentialProperty) {
            throw new AvifDecodeException(AvifErrorCode.MISSING_IMAGE_ITEM, "Primary item is not usable", null);
        }
        if (!"av01".equals(primaryItem.type) && !"grid".equals(primaryItem.type)) {
            throw unsupported("Unsupported primary item type: " + primaryItem.type, null);
        }

        if ("grid".equals(primaryItem.type)) {
            return parseGridContainer(primaryItem);
        }

        if (hasLayeredStructure(primaryItem)) {
            throw unsupported(
                    "Layered/scalable AVIF images with operating points are not implemented in this slice",
                    null
            );
        }

        ImageSpatialExtents ispe = primaryItem.firstProperty(ImageSpatialExtents.class);
        if (ispe == null) {
            throw new AvifDecodeException(AvifErrorCode.BMFF_PARSE_FAILED, "Primary AV1 item is missing ispe", null);
        }
        Av1Config av1Config = primaryItem.firstProperty(Av1Config.class);
        if (av1Config == null) {
            throw new AvifDecodeException(AvifErrorCode.BMFF_PARSE_FAILED, "Primary AV1 item is missing av1C", null);
        }

        AuxiliaryPayloads alphaPayloads = parseAuxiliaryPayloads(
                primaryItem,
                AvifAuxiliaryImageInfo.ALPHA_TYPE,
                "Alpha",
                ispe.width,
                ispe.height
        );
        AuxiliaryPayloads depthPayloads = parseAuxiliaryPayloads(
                primaryItem,
                AvifAuxiliaryImageInfo.DEPTH_TYPE,
                "Depth",
                ispe.width,
                ispe.height
        );

        byte[] payload = mergeItemExtents(primaryItem);
        MetadataPayloads metadata = collectMetadataPayloads(primaryItem);
        int[] transformParams = extractTransformParams(primaryItem, ispe.width, ispe.height);
        GainMapPayloads gainMapPayloads = gainMapPayloads(primaryItem.id);
        AvifImageInfo info = new AvifImageInfo(
                ispe.width,
                ispe.height,
                AvifBitDepth.fromBits(av1Config.bitDepth()),
                av1Config.pixelFormat(),
                alphaPayloads.present(),
                false,
                1,
                primaryItem.firstProperty(AvifColorInfo.class),
                metadata.iccProfile,
                metadata.exif,
                metadata.xmp,
                0,
                0,
                null,
                transformParams[0],
                transformParams[1],
                transformParams[2],
                transformParams[3],
                transformParams[4],
                transformParams[5],
                null,
                auxiliaryImages(primaryItem.id),
                gainMapPayloads.info
        );

        return new AvifContainer(info, payload,
                alphaPayloads.itemPayload,
                depthPayloads.itemPayload,
                gainMapPayloads.itemPayload,
                gainMapPayloads.gridCellPayloads,
                gainMapPayloads.gridRows, gainMapPayloads.gridColumns,
                gainMapPayloads.gridOutputWidth, gainMapPayloads.gridOutputHeight,
                transformParams[0], transformParams[1], transformParams[2], transformParams[3],
                transformParams[4], transformParams[5]);
    }

    /// Parses a grid derived image container.
    ///
    /// @param gridItem the primary grid item
    /// @return the parsed AVIF container
    /// @throws AvifDecodeException if the grid is malformed or unsupported
    private AvifContainer parseGridContainer(Item gridItem) throws AvifDecodeException {
        GridPayloads colorGrid = parseGridPayloads(gridItem);
        AuxiliaryPayloads alphaPayloads = parseGridAuxiliaryPayloads(
                gridItem,
                colorGrid,
                AvifAuxiliaryImageInfo.ALPHA_TYPE,
                "Alpha"
        );
        AuxiliaryPayloads depthPayloads = parseGridAuxiliaryPayloads(
                gridItem,
                colorGrid,
                AvifAuxiliaryImageInfo.DEPTH_TYPE,
                "Depth"
        );
        MetadataPayloads metadata = collectMetadataPayloads(gridItem);
        int[] transforms = extractTransformParams(gridItem, colorGrid.outputWidth, colorGrid.outputHeight);
        GainMapPayloads gainMapPayloads = gainMapPayloads(gridItem.id);

        AvifImageInfo info = new AvifImageInfo(
                colorGrid.outputWidth,
                colorGrid.outputHeight,
                AvifBitDepth.fromBits(colorGrid.representativeAv1C.bitDepth()),
                colorGrid.representativeAv1C.pixelFormat(),
                alphaPayloads.present(),
                false,
                1,
                gridItem.firstProperty(AvifColorInfo.class),
                metadata.iccProfile,
                metadata.exif,
                metadata.xmp,
                0,
                0,
                null,
                transforms[0],
                transforms[1],
                transforms[2],
                transforms[3],
                transforms[4],
                transforms[5],
                null,
                auxiliaryImages(gridItem.id),
                gainMapPayloads.info
        );

        return new AvifContainer(info, colorGrid.cellPayloads,
                alphaPayloads.itemPayload,
                alphaPayloads.gridCellPayloads,
                depthPayloads.itemPayload,
                depthPayloads.gridCellPayloads,
                gainMapPayloads.itemPayload,
                gainMapPayloads.gridCellPayloads,
                gainMapPayloads.gridRows, gainMapPayloads.gridColumns,
                gainMapPayloads.gridOutputWidth, gainMapPayloads.gridOutputHeight,
                colorGrid.rows, colorGrid.columns, colorGrid.outputWidth, colorGrid.outputHeight,
                alphaPayloads.gridRows, alphaPayloads.gridColumns,
                alphaPayloads.gridOutputWidth, alphaPayloads.gridOutputHeight,
                depthPayloads.gridRows, depthPayloads.gridColumns,
                depthPayloads.gridOutputWidth, depthPayloads.gridOutputHeight,
                transforms[0], transforms[1], transforms[2], transforms[3],
                transforms[4], transforms[5]);
    }

    /// Parses the payloads and geometry for one `grid` derived image item.
    ///
    /// @param gridItem the grid item
    /// @return parsed grid payloads and geometry
    /// @throws AvifDecodeException if the grid is malformed or unsupported
    private GridPayloads parseGridPayloads(Item gridItem) throws AvifDecodeException {
        byte[] gridPayload = mergeItemExtents(gridItem);
        BoxInput input = new BoxInput(gridPayload);
        int version = input.readU8();
        int flags = input.readU8();
        if (version != 0) {
            throw unsupported("Unsupported grid version: " + version, null);
        }
        if (flags != 0) {
            throw parseFailed("grid flags must be zero", 1);
        }
        int columnsMinusOne = input.readU8();
        int rowsMinusOne = input.readU8();
        int rows = rowsMinusOne + 1;
        int columns = columnsMinusOne + 1;
        if (rows <= 0 || columns <= 0) {
            throw parseFailed("grid dimensions must be positive", 2);
        }

        int outputWidth = -1;
        int outputHeight = -1;
        if (input.remaining() >= 4) {
            outputWidth = input.readU16();
            outputHeight = input.readU16();
            if (outputWidth <= 0 || outputHeight <= 0) {
                throw parseFailed("grid output dimensions must be positive", input.offset());
            }
        }

        int expectedCellCount = rows * columns;
        List<Integer> cellIds = gridItem.dimgCellIds;
        if (cellIds.size() != expectedCellCount) {
            throw parseFailed(
                    "grid dimg cell count mismatch: expected " + expectedCellCount + " but got " + cellIds.size(),
                    0
            );
        }
        HashSet<Integer> uniqueCellIds = new HashSet<>(expectedCellCount);
        for (int cellId : cellIds) {
            if (!uniqueCellIds.add(cellId)) {
                throw parseFailed("grid dimg cell is referenced more than once: " + cellId, 0);
            }
        }

        ImageSpatialExtents representativeIspe = null;
        Av1Config representativeAv1C = null;
        List<byte[]> cellPayloads = new ArrayList<>(expectedCellCount);

        for (int i = 0; i < expectedCellCount; i++) {
            int cellId = cellIds.get(i);
            Item cellItem = meta.item(cellId);
            if (cellItem == null) {
                throw parseFailed("grid dimg cell item not found: " + cellId, 0);
            }
            if (cellItem.sharedDerivedImageReference) {
                throw unsupported("Grid cell item is shared by multiple derived images: " + cellId, null);
            }
            if (!"av01".equals(cellItem.type)) {
                throw unsupported("Unsupported grid cell item type: " + cellItem.type, null);
            }
            if (cellItem.hasUnsupportedEssentialProperty) {
                throw new AvifDecodeException(
                        AvifErrorCode.MISSING_IMAGE_ITEM,
                        "Grid cell item is not usable: " + cellId,
                        null
                );
            }
            ImageSpatialExtents cellIspe = cellItem.firstProperty(ImageSpatialExtents.class);
            if (cellIspe == null) {
                throw new AvifDecodeException(
                        AvifErrorCode.BMFF_PARSE_FAILED,
                        "Grid cell item is missing ispe: " + cellId,
                        null
                );
            }
            Av1Config cellAv1C = cellItem.firstProperty(Av1Config.class);
            if (cellAv1C == null) {
                throw new AvifDecodeException(
                        AvifErrorCode.BMFF_PARSE_FAILED,
                        "Grid cell item is missing av1C: " + cellId,
                        null
                );
            }
            if (representativeIspe == null) {
                representativeIspe = cellIspe;
                representativeAv1C = cellAv1C;
            }
            cellPayloads.add(mergeItemExtents(cellItem));
        }

        assert representativeIspe != null;
        assert representativeAv1C != null;

        if (outputWidth < 0) {
            outputWidth = computeGridOutputWidth(rows, columns, cellIds);
        }
        if (outputHeight < 0) {
            outputHeight = computeGridOutputHeight(rows, columns, cellIds);
        }

        byte[][] payloads = cellPayloads.toArray(byte[][]::new);
        return new GridPayloads(rows, columns, outputWidth, outputHeight, representativeAv1C, payloads);
    }

    /// Parses AV1 auxiliary payloads for a non-grid color item.
    ///
    /// @param imageItem the color image item
    /// @param auxiliaryType the auxiliary image type string
    /// @param label the diagnostic auxiliary label
    /// @param expectedWidth the expected auxiliary width
    /// @param expectedHeight the expected auxiliary height
    /// @return auxiliary payload data, or empty data when no matching auxiliary image is present
    /// @throws AvifDecodeException if auxiliary data is malformed or unsupported
    private AuxiliaryPayloads parseAuxiliaryPayloads(
            Item imageItem,
            String auxiliaryType,
            String label,
            int expectedWidth,
            int expectedHeight
    ) throws AvifDecodeException {
        Item auxiliaryItem = findAuxiliaryItem(imageItem.id, auxiliaryType);
        if (auxiliaryItem == null) {
            return AuxiliaryPayloads.empty();
        }
        if (auxiliaryItem.hasUnsupportedEssentialProperty) {
            throw new AvifDecodeException(
                    AvifErrorCode.MISSING_IMAGE_ITEM,
                    label + " auxiliary item is not usable: " + auxiliaryItem.id,
                    null
            );
        }
        if (!"av01".equals(auxiliaryItem.type)) {
            throw unsupported("Unsupported " + label + " auxiliary item type: " + auxiliaryItem.type, null);
        }
        validateAuxiliaryItemDimensions(auxiliaryItem, label, expectedWidth, expectedHeight);
        return AuxiliaryPayloads.item(mergeItemExtents(auxiliaryItem));
    }

    /// Parses auxiliary payloads for a grid color item.
    ///
    /// @param gridItem the color grid item
    /// @param colorGrid the parsed color grid
    /// @param auxiliaryType the auxiliary image type string
    /// @param label the diagnostic auxiliary label
    /// @return auxiliary payload data, or empty data when no matching auxiliary image is present
    /// @throws AvifDecodeException if auxiliary data is malformed or unsupported
    private AuxiliaryPayloads parseGridAuxiliaryPayloads(
            Item gridItem,
            GridPayloads colorGrid,
            String auxiliaryType,
            String label
    ) throws AvifDecodeException {
        Item auxiliaryItem = findAuxiliaryItem(gridItem.id, auxiliaryType);
        if (auxiliaryItem != null) {
            if (auxiliaryItem.hasUnsupportedEssentialProperty) {
                throw new AvifDecodeException(
                        AvifErrorCode.MISSING_IMAGE_ITEM,
                        label + " auxiliary item is not usable: " + auxiliaryItem.id,
                        null
                );
            }
            if ("grid".equals(auxiliaryItem.type)) {
                GridPayloads auxiliaryGrid = parseGridPayloads(auxiliaryItem);
                validateAuxiliaryGridDimensions(colorGrid, auxiliaryGrid, label);
                return AuxiliaryPayloads.grid(auxiliaryGrid);
            }
            if (!"av01".equals(auxiliaryItem.type)) {
                throw unsupported("Unsupported " + label + " auxiliary item type: " + auxiliaryItem.type, null);
            }
            validateAuxiliaryItemDimensions(auxiliaryItem, label, colorGrid.outputWidth, colorGrid.outputHeight);
            return AuxiliaryPayloads.item(mergeItemExtents(auxiliaryItem));
        }

        return parsePerCellGridAuxiliaryPayloads(gridItem, colorGrid, auxiliaryType, label);
    }

    /// Parses the legacy grid-auxiliary shape where each color cell has its own auxiliary item.
    ///
    /// @param gridItem the color grid item
    /// @param colorGrid the parsed color grid
    /// @param auxiliaryType the auxiliary image type string
    /// @param label the diagnostic auxiliary label
    /// @return auxiliary payload data, or empty data when no complete per-cell auxiliary set is present
    /// @throws AvifDecodeException if auxiliary data is malformed or unsupported
    private AuxiliaryPayloads parsePerCellGridAuxiliaryPayloads(
            Item gridItem,
            GridPayloads colorGrid,
            String auxiliaryType,
            String label
    ) throws AvifDecodeException {
        List<Integer> cellIds = gridItem.dimgCellIds;
        byte[][] auxiliaryCellPayloads = new byte[cellIds.size()][];
        for (int i = 0; i < cellIds.size(); i++) {
            int cellId = cellIds.get(i);
            Item colorCellItem = meta.requireItem(cellId);
            Item auxiliaryCellItem = findAuxiliaryItem(cellId, auxiliaryType);
            if (auxiliaryCellItem == null) {
                return AuxiliaryPayloads.empty();
            }
            if (!"av01".equals(auxiliaryCellItem.type)) {
                throw unsupported(
                        "Unsupported per-cell " + label + " auxiliary item type: " + auxiliaryCellItem.type,
                        null
                );
            }
            if (auxiliaryCellItem.dimgForId != 0) {
                throw unsupported(
                        "Per-cell " + label + " auxiliary item is also a derived-image cell: " + auxiliaryCellItem.id,
                        null
                );
            }
            if (auxiliaryCellItem.hasUnsupportedEssentialProperty) {
                throw new AvifDecodeException(
                        AvifErrorCode.MISSING_IMAGE_ITEM,
                        "Per-cell " + label + " auxiliary item is not usable: " + auxiliaryCellItem.id,
                        null
                );
            }
            ImageSpatialExtents colorIspe = colorCellItem.firstProperty(ImageSpatialExtents.class);
            assert colorIspe != null;
            validateAuxiliaryItemDimensions(auxiliaryCellItem, label, colorIspe.width, colorIspe.height);
            auxiliaryCellPayloads[i] = mergeItemExtents(auxiliaryCellItem);
        }
        return AuxiliaryPayloads.grid(new GridPayloads(
                colorGrid.rows,
                colorGrid.columns,
                colorGrid.outputWidth,
                colorGrid.outputHeight,
                colorGrid.representativeAv1C,
                auxiliaryCellPayloads
        ));
    }

    /// Validates that an auxiliary grid matches the color grid canvas dimensions.
    ///
    /// @param colorGrid the color grid
    /// @param auxiliaryGrid the auxiliary grid
    /// @param label the diagnostic auxiliary label
    /// @throws AvifDecodeException if dimensions differ
    private static void validateAuxiliaryGridDimensions(
            GridPayloads colorGrid,
            GridPayloads auxiliaryGrid,
            String label
    ) throws AvifDecodeException {
        if (auxiliaryGrid.outputWidth != colorGrid.outputWidth || auxiliaryGrid.outputHeight != colorGrid.outputHeight) {
            throw unsupported(
                    label + " grid with different dimensions than the master grid is not implemented in this slice",
                    null
            );
        }
    }

    /// Validates one auxiliary item against the expected output dimensions.
    ///
    /// @param auxiliaryItem the auxiliary item
    /// @param label the diagnostic auxiliary label
    /// @param expectedWidth the expected auxiliary width
    /// @param expectedHeight the expected auxiliary height
    /// @throws AvifDecodeException if the item is malformed or has unsupported dimensions
    private void validateAuxiliaryItemDimensions(
            Item auxiliaryItem,
            String label,
            int expectedWidth,
            int expectedHeight
    ) throws AvifDecodeException {
        ImageSpatialExtents auxiliaryIspe = auxiliaryItem.firstProperty(ImageSpatialExtents.class);
        if (auxiliaryIspe == null) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    label + " auxiliary item is missing ispe: " + auxiliaryItem.id,
                    null
            );
        }
        if (auxiliaryIspe.width != expectedWidth || auxiliaryIspe.height != expectedHeight) {
            throw unsupported(
                    label + " auxiliary image with different dimensions than the master image is not implemented in this slice",
                    null
            );
        }
        if (auxiliaryItem.firstProperty(Av1Config.class) == null) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    label + " auxiliary item is missing av1C: " + auxiliaryItem.id,
                    null
            );
        }
    }

    /// Collects metadata payloads associated with one rendered image item.
    ///
    /// @param imageItem the color or grid image item
    /// @return the associated metadata payloads
    /// @throws AvifDecodeException if metadata item extents are malformed
    private MetadataPayloads collectMetadataPayloads(Item imageItem) throws AvifDecodeException {
        byte[] iccProfile = null;
        IccColorProfile iccProperty = imageItem.firstProperty(IccColorProfile.class);
        if (iccProperty != null) {
            iccProfile = iccProperty.profile();
        }

        byte[] exif = null;
        byte[] xmp = null;
        for (Item item : meta.items.values()) {
            if (item.descForId != imageItem.id) {
                continue;
            }
            if (exif == null && "Exif".equals(item.type)) {
                exif = exifPayload(item);
            } else if (xmp == null
                    && "mime".equals(item.type)
                    && (XMP_CONTENT_TYPE.equals(item.contentType) || "XMP".equals(item.name))) {
                xmp = mergeItemExtents(item);
            }
        }
        return new MetadataPayloads(iccProfile, exif, xmp);
    }

    /// Reads and normalizes an Exif metadata item.
    ///
    /// @param item the Exif item
    /// @return the Exif payload without the AVIF Exif header offset field
    /// @throws AvifDecodeException if the Exif item is malformed
    private byte[] exifPayload(Item item) throws AvifDecodeException {
        byte[] payload = mergeItemExtents(item);
        if (payload.length < 4) {
            throw parseFailed("Exif item is missing exif_tiff_header_offset: " + item.id, 0);
        }
        return Arrays.copyOfRange(payload, 4, payload.length);
    }

    /// Extracts transform parameters from the properties of one item.
    ///
    /// @param item the item whose properties are searched
    /// @param imageWidth the image width before clap
    /// @param imageHeight the image height before clap
    /// @return an array of [clapCropX, clapCropY, clapCropWidth, clapCropHeight, rotationCode, mirrorAxis]
    private static int[] extractTransformParams(Item item, int imageWidth, int imageHeight) {
        int clapCropX = -1;
        int clapCropY = -1;
        int clapCropWidth = -1;
        int clapCropHeight = -1;
        int rotationCode = -1;
        int mirrorAxis = -1;

        CleanAperture clap = item.firstProperty(CleanAperture.class);
        if (clap != null) {
            clapCropX = clap.horizOffN / clap.horizOffD;
            clapCropY = clap.vertOffN / clap.vertOffD;
            clapCropWidth = (clap.cleanApertureWidthN + clap.cleanApertureWidthD - 1) / clap.cleanApertureWidthD;
            clapCropHeight = (clap.cleanApertureHeightN + clap.cleanApertureHeightD - 1) / clap.cleanApertureHeightD;
            if (clapCropX < 0 || clapCropY < 0
                    || clapCropWidth <= 0 || clapCropHeight <= 0
                    || clapCropX + clapCropWidth > imageWidth
                    || clapCropY + clapCropHeight > imageHeight) {
                clapCropWidth = Math.min(clapCropWidth, imageWidth - clapCropX);
                clapCropHeight = Math.min(clapCropHeight, imageHeight - clapCropY);
                if (clapCropWidth <= 0 || clapCropHeight <= 0) {
                    clapCropX = -1;
                    clapCropY = -1;
                    clapCropWidth = -1;
                    clapCropHeight = -1;
                }
            }
        }

        ImageRotation irot = item.firstProperty(ImageRotation.class);
        if (irot != null) {
            rotationCode = irot.rotation;
        }

        ImageMirror imir = item.firstProperty(ImageMirror.class);
        if (imir != null) {
            mirrorAxis = imir.axis;
        }

        return new int[]{clapCropX, clapCropY, clapCropWidth, clapCropHeight, rotationCode, mirrorAxis};
    }

    /// Returns whether the item has a layered progressive structure.
    ///
    /// @param item the primary item
    /// @return whether the item has a layered progressive structure
    private boolean hasLayeredStructure(Item item) {
        OperatingPoint primaryOp = item.firstProperty(OperatingPoint.class);
        for (int depId : item.progDeps) {
            Item depItem = meta.item(depId);
            if (depItem != null) {
                OperatingPoint depOp = depItem.firstProperty(OperatingPoint.class);
                if (primaryOp != null && depOp != null && depOp.operatingPoint != primaryOp.operatingPoint) {
                    return true;
                }
                if (primaryOp == null && depOp != null) {
                    return true;
                }
                if (depItem.firstProperty(OperatingPoint.class) != null && primaryOp == null) {
                    return true;
                }
            }
        }
        return false;
    }

    /// Computes grid output width from cell ispe dimensions.
    ///
    /// @param rows the grid row count
    /// @param columns the grid column count
    /// @param cellIds the cell item ids in row-major order
    /// @return the computed output width
    /// @throws AvifDecodeException if cells in the same column have different widths
    private int computeGridOutputWidth(int rows, int columns, List<Integer> cellIds) throws AvifDecodeException {
        int[] columnWidths = new int[columns];
        for (int col = 0; col < columns; col++) {
            int cellIndex = col;
            Item cellItem = meta.requireItem(cellIds.get(cellIndex));
            ImageSpatialExtents ispe = cellItem.firstProperty(ImageSpatialExtents.class);
            assert ispe != null;
            columnWidths[col] = ispe.width;
        }
        for (int row = 1; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                int cellIndex = row * columns + col;
                Item cellItem = meta.requireItem(cellIds.get(cellIndex));
                ImageSpatialExtents ispe = cellItem.firstProperty(ImageSpatialExtents.class);
                assert ispe != null;
                if (ispe.width != columnWidths[col]) {
                    throw unsupported(
                            "Grid cell width mismatch in column " + col + " is not implemented in this slice",
                            null
                    );
                }
            }
        }
        int totalWidth = 0;
        for (int col = 0; col < columns; col++) {
            totalWidth = checkedU32ToInt(checkedAdd(totalWidth, columnWidths[col], 0), 0);
        }
        return totalWidth;
    }

    /// Computes grid output height from cell ispe dimensions.
    ///
    /// @param rows the grid row count
    /// @param columns the grid column count
    /// @param cellIds the cell item ids in row-major order
    /// @return the computed output height
    /// @throws AvifDecodeException if cells in the same row have different heights
    private int computeGridOutputHeight(int rows, int columns, List<Integer> cellIds) throws AvifDecodeException {
        int[] rowHeights = new int[rows];
        for (int row = 0; row < rows; row++) {
            int cellIndex = row * columns;
            Item cellItem = meta.requireItem(cellIds.get(cellIndex));
            ImageSpatialExtents ispe = cellItem.firstProperty(ImageSpatialExtents.class);
            assert ispe != null;
            rowHeights[row] = ispe.height;
        }
        for (int row = 1; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                int cellIndex = row * columns + col;
                Item cellItem = meta.requireItem(cellIds.get(cellIndex));
                ImageSpatialExtents ispe = cellItem.firstProperty(ImageSpatialExtents.class);
                assert ispe != null;
                if (ispe.height != rowHeights[row]) {
                    throw unsupported(
                            "Grid cell height mismatch in row " + row + " is not implemented in this slice",
                            null
                    );
                }
            }
        }
        int totalHeight = 0;
        for (int row = 0; row < rows; row++) {
            totalHeight = checkedU32ToInt(checkedAdd(totalHeight, rowHeights[row], 0), 0);
        }
        return totalHeight;
    }

    /// Parses an `ftyp` box.
    ///
    /// @param input the box payload input
    /// @throws AvifDecodeException if the box is malformed
    private void parseFileType(BoxInput input) throws AvifDecodeException {
        String majorBrand = input.readFourCc();
        input.skip(4);

        boolean hasAvif = "avif".equals(majorBrand);
        boolean hasAvis = "avis".equals(majorBrand);
        boolean hasTmap = "tmap".equals(majorBrand);
        while (input.remaining() >= 4) {
            String brand = input.readFourCc();
            hasAvif |= "avif".equals(brand);
            hasAvis |= "avis".equals(brand);
            hasTmap |= "tmap".equals(brand);
        }
        if (input.remaining() != 0) {
            throw parseFailed("ftyp compatible brands length is not divisible by four", input.offset());
        }
        compatibleFileTypeSeen = hasAvif || hasAvis;
        avisBrandSeen = hasAvis;
        tmapBrandSeen = hasTmap;
    }

    /// Parses a root `meta` box.
    ///
    /// @param header the enclosing box header
    /// @param input the box payload input
    /// @throws AvifDecodeException if the box is malformed
    private void parseMeta(BoxHeader header, BoxInput input) throws AvifDecodeException {
        readFullBox(input);
        boolean firstChild = true;
        Set<String> uniqueBoxes = new HashSet<>();
        while (input.hasRemaining()) {
            BoxHeader child = input.readBoxHeader();
            BoxInput payload = input.slice(child.payloadOffset(), child.payloadSize());
            if (firstChild && !"hdlr".equals(child.type())) {
                throw parseFailed("meta box must start with hdlr", child.offset());
            }
            firstChild = false;

            switch (child.type()) {
                case "hdlr" -> {
                    unique(uniqueBoxes, "hdlr", child.offset());
                    parseHandler(payload);
                }
                case "pitm" -> {
                    unique(uniqueBoxes, "pitm", child.offset());
                    parsePrimaryItem(payload);
                }
                case "iloc" -> {
                    unique(uniqueBoxes, "iloc", child.offset());
                    parseItemLocation(payload);
                }
                case "iinf" -> {
                    unique(uniqueBoxes, "iinf", child.offset());
                    parseItemInfo(payload);
                }
                case "iprp" -> {
                    unique(uniqueBoxes, "iprp", child.offset());
                    parseItemProperties(child, payload);
                }
                case "iref" -> {
                    unique(uniqueBoxes, "iref", child.offset());
                    parseItemReference(payload);
                }
                case "grpl" -> {
                    unique(uniqueBoxes, "grpl", child.offset());
                    parseGroupsList(payload);
                }
                case "idat" -> {
                    unique(uniqueBoxes, "idat", child.offset());
                    meta.idat = payload.readBytes(payload.remaining());
                }
                default -> {
                }
            }
            input.skipBoxPayload(child);
        }
        if (firstChild) {
            throw parseFailed("meta box has no child boxes", header.offset());
        }
    }

    /// Parses a `hdlr` box.
    ///
    /// @param input the box payload input
    /// @throws AvifDecodeException if the box is malformed
    private static void parseHandler(BoxInput input) throws AvifDecodeException {
        readFullBox(input);
        long preDefined = input.readU32();
        if (preDefined != 0) {
            throw parseFailed("hdlr pre_defined must be zero", input.offset() - 4);
        }
        String handlerType = input.readFourCc();
        if (!"pict".equals(handlerType)) {
            throw parseFailed("meta handler_type must be pict", input.offset() - 4);
        }
    }

    /// Parses a `pitm` box.
    ///
    /// @param input the box payload input
    /// @throws AvifDecodeException if the box is malformed
    private void parsePrimaryItem(BoxInput input) throws AvifDecodeException {
        FullBox fullBox = readFullBox(input);
        meta.primaryItemId = fullBox.version == 0 ? input.readU16() : checkedU32ToInt(input.readU32(), input.offset() - 4);
    }

    /// Parses an `iloc` box.
    ///
    /// @param input the box payload input
    /// @throws AvifDecodeException if the box is malformed
    private void parseItemLocation(BoxInput input) throws AvifDecodeException {
        FullBox fullBox = readFullBox(input);
        if (fullBox.version > 2) {
            throw unsupported("Unsupported iloc version: " + fullBox.version, input.offset());
        }
        int packedSizes = input.readU8();
        int offsetSize = packedSizes >>> 4;
        int lengthSize = packedSizes & 0x0F;
        int packedBaseAndIndex = input.readU8();
        int baseOffsetSize = packedBaseAndIndex >>> 4;
        int indexSize = fullBox.version == 0 ? 0 : (packedBaseAndIndex & 0x0F);
        validateIlocFieldSize(offsetSize, input.offset());
        validateIlocFieldSize(lengthSize, input.offset());
        validateIlocFieldSize(baseOffsetSize, input.offset());
        validateIlocFieldSize(indexSize, input.offset());

        int itemCount = fullBox.version < 2 ? input.readU16() : checkedU32ToInt(input.readU32(), input.offset() - 4);
        for (int i = 0; i < itemCount; i++) {
            int itemId = fullBox.version < 2 ? input.readU16() : checkedU32ToInt(input.readU32(), input.offset() - 4);
            Item item = meta.requireItem(itemId);
            int constructionMethod = 0;
            if (fullBox.version > 0) {
                int packedConstruction = input.readU16();
                if ((packedConstruction & 0xFFF0) != 0) {
                    throw parseFailed("iloc reserved bits must be zero", input.offset() - 2);
                }
                constructionMethod = packedConstruction & 0x000F;
                if (constructionMethod != 0 && constructionMethod != 1) {
                    throw unsupported("Unsupported iloc construction method: " + constructionMethod, input.offset() - 2);
                }
            }
            input.skip(2);
            long baseOffset = readUx(input, baseOffsetSize);
            int extentCount = input.readU16();
            item.extents.clear();
            item.idatStored = constructionMethod == 1;
            for (int extentIndex = 0; extentIndex < extentCount; extentIndex++) {
                if (indexSize > 0) {
                    readUx(input, indexSize);
                }
                long extentOffset = readUx(input, offsetSize);
                long extentLength = readUx(input, lengthSize);
                long absoluteOffset = checkedAdd(baseOffset, extentOffset, input.offset());
                item.extents.add(new Extent(absoluteOffset, checkedU64ToInt(extentLength, input.offset())));
            }
        }
    }

    /// Parses an `iinf` box.
    ///
    /// @param input the box payload input
    /// @throws AvifDecodeException if the box is malformed
    private void parseItemInfo(BoxInput input) throws AvifDecodeException {
        FullBox fullBox = readFullBox(input);
        int entryCount = fullBox.version == 0 ? input.readU16() : checkedU32ToInt(input.readU32(), input.offset() - 4);
        for (int i = 0; i < entryCount; i++) {
            BoxHeader infe = input.readBoxHeader();
            if (!"infe".equals(infe.type())) {
                throw parseFailed("iinf entry is not infe", infe.offset());
            }
            parseItemInfoEntry(input.slice(infe.payloadOffset(), infe.payloadSize()), infe.offset());
            input.skipBoxPayload(infe);
        }
    }

    /// Parses an `infe` item info entry.
    ///
    /// @param input the box payload input
    /// @param boxOffset the enclosing box offset
    /// @throws AvifDecodeException if the box is malformed
    private void parseItemInfoEntry(BoxInput input, int boxOffset) throws AvifDecodeException {
        FullBox fullBox = readFullBox(input);
        if (fullBox.version < 2 || fullBox.version > 3) {
            throw unsupported("Unsupported infe version: " + fullBox.version, boxOffset);
        }
        int itemId = fullBox.version == 2 ? input.readU16() : checkedU32ToInt(input.readU32(), input.offset() - 4);
        input.skip(2);
        Item item = meta.requireItem(itemId);
        item.type = input.readFourCc();
        item.name = readNullTerminatedString(input);
        if ("mime".equals(item.type)) {
            item.contentType = readNullTerminatedString(input);
            if (input.hasRemaining()) {
                item.contentEncoding = readNullTerminatedString(input);
            }
        }
    }

    /// Parses an `iprp` box.
    ///
    /// @param header the enclosing box header
    /// @param input the box payload input
    /// @throws AvifDecodeException if the box is malformed
    private void parseItemProperties(BoxHeader header, BoxInput input) throws AvifDecodeException {
        while (input.hasRemaining()) {
            BoxHeader child = input.readBoxHeader();
            BoxInput payload = input.slice(child.payloadOffset(), child.payloadSize());
            switch (child.type()) {
                case "ipco" -> parseItemPropertyContainer(child, payload);
                case "ipma" -> parseItemPropertyAssociation(payload);
                default -> {
                }
            }
            input.skipBoxPayload(child);
        }
        if (meta.properties.isEmpty()) {
            throw parseFailed("iprp contains no parsed properties", header.offset());
        }
    }

    /// Parses an `ipco` box.
    ///
    /// @param header the enclosing box header
    /// @param input the box payload input
    /// @throws AvifDecodeException if the box is malformed
    private void parseItemPropertyContainer(BoxHeader header, BoxInput input) throws AvifDecodeException {
        while (input.hasRemaining()) {
            BoxHeader propertyHeader = input.readBoxHeader();
            BoxInput payload = input.slice(propertyHeader.payloadOffset(), propertyHeader.payloadSize());
            meta.properties.add(parseProperty(propertyHeader, payload));
            input.skipBoxPayload(propertyHeader);
        }
        if (meta.properties.isEmpty()) {
            throw parseFailed("ipco contains no properties", header.offset());
        }
    }

    /// Parses one item property.
    ///
    /// @param header the property box header
    /// @param input the property payload input
    /// @return the parsed property
    /// @throws AvifDecodeException if the property is malformed
    private static Property parseProperty(BoxHeader header, BoxInput input) throws AvifDecodeException {
        return switch (header.type()) {
            case "ispe" -> parseIspe(input);
            case "av1C" -> parseAv1C(input);
            case "colr" -> parseColr(input);
            case "auxC" -> parseAuxC(input);
            case "pixi" -> parsePixi(input);
            case "pasp" -> parsePasp(input);
            case "clap" -> parseClap(input);
            case "irot" -> parseIrot(input);
            case "imir" -> parseImir(input);
            case "a1op" -> parseA1op(input);
            default -> new OpaqueProperty(header.type());
        };
    }

    /// Parses an `ispe` property.
    ///
    /// @param input the property payload input
    /// @return the parsed property
    /// @throws AvifDecodeException if the property is malformed
    private static ImageSpatialExtents parseIspe(BoxInput input) throws AvifDecodeException {
        readFullBox(input);
        int width = checkedU32ToInt(input.readU32(), input.offset() - 4);
        int height = checkedU32ToInt(input.readU32(), input.offset() - 4);
        if (width <= 0 || height <= 0) {
            throw parseFailed("ispe dimensions must be positive", input.offset());
        }
        return new ImageSpatialExtents(width, height);
    }

    /// Parses an `av1C` property.
    ///
    /// @param input the property payload input
    /// @return the parsed property
    /// @throws AvifDecodeException if the property is malformed
    private static Av1Config parseAv1C(BoxInput input) throws AvifDecodeException {
        int first = input.readU8();
        if ((first >>> 7) != 1 || (first & 0x7F) != 1) {
            throw parseFailed("av1C marker/version is invalid", input.offset() - 1);
        }
        int second = input.readU8();
        int third = input.readU8();
        input.readU8();
        int seqProfile = second >>> 5;
        int seqLevelIdx0 = second & 0x1F;
        boolean highBitDepth = ((third >>> 6) & 1) != 0;
        boolean twelveBit = ((third >>> 5) & 1) != 0;
        boolean monochrome = ((third >>> 4) & 1) != 0;
        boolean chromaSubsamplingX = ((third >>> 3) & 1) != 0;
        boolean chromaSubsamplingY = ((third >>> 2) & 1) != 0;
        int chromaSamplePosition = third & 0x03;
        return new Av1Config(
                seqProfile,
                seqLevelIdx0,
                highBitDepth,
                twelveBit,
                monochrome,
                chromaSubsamplingX,
                chromaSubsamplingY,
                chromaSamplePosition
        );
    }

    /// Parses a `colr` property.
    ///
    /// @param input the property payload input
    /// @return the parsed property
    /// @throws AvifDecodeException if the property is malformed
    private static Property parseColr(BoxInput input) throws AvifDecodeException {
        String colourType = input.readFourCc();
        if ("prof".equals(colourType) || "rICC".equals(colourType)) {
            byte[] profile = input.readBytes(input.remaining());
            if (profile.length == 0) {
                throw parseFailed("ICC color profile payload is empty", input.offset());
            }
            return new IccColorProfile(profile);
        }
        if (!"nclx".equals(colourType)) {
            return new OpaqueProperty("colr");
        }
        int colorPrimaries = input.readU16();
        int transferCharacteristics = input.readU16();
        int matrixCoefficients = input.readU16();
        int packedRange = input.readU8();
        if ((packedRange & 0x7F) != 0) {
            throw parseFailed("nclx reserved bits must be zero", input.offset() - 1);
        }
        return new ColorProperty(new AvifColorInfo(
                colorPrimaries,
                transferCharacteristics,
                matrixCoefficients,
                (packedRange & 0x80) != 0
        ));
    }

    /// Parses an `auxC` property.
    ///
    /// @param input the property payload input
    /// @return the parsed property
    /// @throws AvifDecodeException if the property is malformed
    private static AuxiliaryType parseAuxC(BoxInput input) throws AvifDecodeException {
        readFullBox(input);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (input.hasRemaining()) {
            int value = input.readU8();
            if (value == 0) {
                break;
            }
            output.write(value);
        }
        return new AuxiliaryType(output.toString(java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    /// Parses a `pixi` property.
    ///
    /// @param input the property payload input
    /// @return the parsed property
    /// @throws AvifDecodeException if the property is malformed
    private static PixelInformation parsePixi(BoxInput input) throws AvifDecodeException {
        readFullBox(input);
        int numChannels = input.readU8();
        if (numChannels < 1) {
            throw parseFailed("pixi must have at least one channel", input.offset() - 1);
        }
        if (numChannels > input.remaining()) {
            throw parseFailed("pixi numChannels exceeds available payload", input.offset() - 1);
        }
        int[] bitsPerChannel = new int[numChannels];
        for (int i = 0; i < numChannels; i++) {
            bitsPerChannel[i] = input.readU8();
        }
        return new PixelInformation(bitsPerChannel);
    }

    /// Parses a `pasp` property.
    ///
    /// @param input the property payload input
    /// @return the parsed property
    /// @throws AvifDecodeException if the property is malformed
    private static PixelAspectRatio parsePasp(BoxInput input) throws AvifDecodeException {
        int hSpacing = checkedU32ToInt(input.readU32(), input.offset() - 4);
        int vSpacing = checkedU32ToInt(input.readU32(), input.offset() - 4);
        if (hSpacing <= 0 || vSpacing <= 0) {
            throw parseFailed("pasp spacing values must be positive", input.offset());
        }
        return new PixelAspectRatio(hSpacing, vSpacing);
    }

    /// Parses a `clap` property.
    ///
    /// @param input the property payload input
    /// @return the parsed property
    /// @throws AvifDecodeException if the property is malformed
    private static CleanAperture parseClap(BoxInput input) throws AvifDecodeException {
        int cleanApertureWidthN = checkedU32ToInt(input.readU32(), input.offset() - 4);
        int cleanApertureWidthD = checkedU32ToInt(input.readU32(), input.offset() - 4);
        int cleanApertureHeightN = checkedU32ToInt(input.readU32(), input.offset() - 4);
        int cleanApertureHeightD = checkedU32ToInt(input.readU32(), input.offset() - 4);
        int horizOffN = (int) input.readU32();
        int horizOffD = checkedU32ToInt(input.readU32(), input.offset() - 4);
        int vertOffN = (int) input.readU32();
        int vertOffD = checkedU32ToInt(input.readU32(), input.offset() - 4);
        if (cleanApertureWidthD <= 0 || cleanApertureHeightD <= 0 || horizOffD <= 0 || vertOffD <= 0) {
            throw parseFailed("clap denominator values must be positive", input.offset());
        }
        return new CleanAperture(
                cleanApertureWidthN, cleanApertureWidthD,
                cleanApertureHeightN, cleanApertureHeightD,
                horizOffN, horizOffD,
                vertOffN, vertOffD
        );
    }

    /// Parses an `irot` property.
    ///
    /// @param input the property payload input
    /// @return the parsed property
    /// @throws AvifDecodeException if the property is malformed
    private static ImageRotation parseIrot(BoxInput input) throws AvifDecodeException {
        int packed = input.readU8();
        if ((packed & 0xFC) != 0) {
            throw parseFailed("irot reserved bits must be zero", input.offset() - 1);
        }
        int rotation = packed & 0x03;
        return new ImageRotation(rotation);
    }

    /// Parses an `imir` property.
    ///
    /// @param input the property payload input
    /// @return the parsed property
    /// @throws AvifDecodeException if the property is malformed
    private static ImageMirror parseImir(BoxInput input) throws AvifDecodeException {
        int packed = input.readU8();
        if ((packed & 0xFE) != 0) {
            throw parseFailed("imir reserved bits must be zero", input.offset() - 1);
        }
        int axis = packed & 0x01;
        if (axis != 0 && axis != 1) {
            throw parseFailed("imir axis must be 0 or 1", input.offset() - 1);
        }
        return new ImageMirror(axis);
    }

    /// Parses an `a1op` property.
    ///
    /// @param input the property payload input
    /// @return the parsed property
    /// @throws AvifDecodeException if the property is malformed
    private static OperatingPoint parseA1op(BoxInput input) throws AvifDecodeException {
        readFullBox(input);
        int operatingPoint = input.readU8();
        if (operatingPoint > 31) {
            throw parseFailed("a1op operating point exceeds maximum", input.offset() - 1);
        }
        return new OperatingPoint(operatingPoint);
    }

    /// Creates an AVIF container from parsed sequence data.
    private AvifContainer parseSequenceImage() throws AvifDecodeException {
        MoovState s = meta.moovState;
        SequencePayloads colorPayloads = sequencePayloads(s, "Color sequence");
        byte @Nullable [][] alphaPayloads = sequenceAuxiliaryPayloads(
                meta.moovAlphaState,
                colorPayloads.sampleCount,
                "Alpha sequence"
        );
        byte @Nullable [][] depthPayloads = sequenceAuxiliaryPayloads(
                meta.moovDepthState,
                colorPayloads.sampleCount,
                "Depth sequence"
        );
        int ts = s.mediaTimescale > 0 ? s.mediaTimescale : 30;
        long dur = s.mediaDuration > 0 ? s.mediaDuration : colorPayloads.sampleCount;
        AvifImageInfo info = new AvifImageInfo(
                s.width > 0 ? s.width : 1,
                s.height > 0 ? s.height : 1,
                AvifBitDepth.fromBits(s.bitDepth > 0 ? s.bitDepth : 8),
                s.pixelFormat != null ? s.pixelFormat : AvifPixelFormat.I420,
                alphaPayloads != null,
                true,
                colorPayloads.sampleCount,
                s.colr,
                s.iccProfile,
                null,
                null,
                ts,
                dur,
                colorPayloads.frameDeltas,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                meta.moovAuxiliaryTypes.toArray(String[]::new));
        return new AvifContainer(info,
                colorPayloads.payloads,
                alphaPayloads,
                depthPayloads,
                colorPayloads.frameDeltas,
                colorPayloads.sampleCount, ts, dur);
    }

    /// Extracts sample payloads and frame deltas from one AVIS track.
    ///
    /// @param track the parsed track state
    /// @param label the diagnostic label
    /// @return extracted sample payloads
    /// @throws AvifDecodeException if sample data is missing or truncated
    private SequencePayloads sequencePayloads(MoovState track, String label) throws AvifDecodeException {
        if (track.sampleSizes.isEmpty()) {
            throw new AvifDecodeException(AvifErrorCode.BMFF_PARSE_FAILED, label + " has no samples", null);
        }
        int sampleCount = track.sampleSizes.size();
        int chunkOff = track.chunkOffsets.isEmpty() ? 0 : track.chunkOffsets.get(0);
        int bytesOff = 0;
        byte[][] payloads = new byte[sampleCount][];
        int[] deltas = new int[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            int size = track.sampleSizes.get(i);
            long offset = (long) chunkOff + bytesOff;
            if (offset + size > source.length) {
                throw new AvifDecodeException(
                        AvifErrorCode.TRUNCATED_DATA,
                        label + " sample outside source: " + i,
                        offset
                );
            }
            byte[] payload = new byte[size];
            System.arraycopy(source, (int) offset, payload, 0, size);
            payloads[i] = payload;
            bytesOff += size;
            deltas[i] = i < track.sampleDeltas.size() ? track.sampleDeltas.get(i) : 1;
        }
        return new SequencePayloads(payloads, deltas, sampleCount);
    }

    /// Extracts auxiliary sample payloads and validates the sample count.
    ///
    /// @param track the parsed auxiliary track state, or `null`
    /// @param colorSampleCount the color track sample count
    /// @param label the diagnostic label
    /// @return extracted auxiliary payloads, or `null`
    /// @throws AvifDecodeException if the auxiliary sample table is malformed
    private byte @Nullable [][] sequenceAuxiliaryPayloads(
            @Nullable MoovState track,
            int colorSampleCount,
            String label
    ) throws AvifDecodeException {
        if (track == null) {
            return null;
        }
        SequencePayloads payloads = sequencePayloads(track, label);
        if (payloads.sampleCount != colorSampleCount) {
            throw unsupported(
                    label + " sample count does not match color sequence: " + payloads.sampleCount
                            + " != " + colorSampleCount,
                    null
            );
        }
        return payloads.payloads;
    }

    /// Parses a `moov` box for AVIS image sequences.
    ///
    /// Navigates to the sample table inside the first video track and extracts frame metadata.
    ///
    /// @param input the moov box payload
    /// @throws AvifDecodeException if the box is malformed
    private void parseMoov(BoxInput input) throws AvifDecodeException {
        while (input.hasRemaining()) {
            BoxHeader child = input.readBoxHeader();
            if ("trak".equals(child.type())) {
                MoovState selectedTrack = meta.moovState.copy();
                meta.moovState.copyFrom(new MoovState());
                BoxInput trakPayload = input.slice(child.payloadOffset(), child.payloadSize());
                parseMoovTrack(trakPayload);
                MoovState parsedTrack = meta.moovState.copy();
                if (parsedTrack.auxiliaryType != null && !meta.moovAuxiliaryTypes.contains(parsedTrack.auxiliaryType)) {
                    meta.moovAuxiliaryTypes.add(parsedTrack.auxiliaryType);
                }
                if (AvifAuxiliaryImageInfo.ALPHA_TYPE.equals(parsedTrack.auxiliaryType) && meta.moovAlphaState == null) {
                    meta.moovAlphaState = parsedTrack;
                }
                if (AvifAuxiliaryImageInfo.DEPTH_TYPE.equals(parsedTrack.auxiliaryType) && meta.moovDepthState == null) {
                    meta.moovDepthState = parsedTrack;
                }
                if (selectedTrack.seqHeaderObu != null
                        || parsedTrack.seqHeaderObu == null
                        || parsedTrack.auxiliaryType != null) {
                    meta.moovState.copyFrom(selectedTrack);
                }
            }
            input.skipBoxPayload(child);
        }
    }

    /// Parses a `trak` box and extracts video track metadata.
    private void parseMoovTrack(BoxInput input) throws AvifDecodeException {
        while (input.hasRemaining()) {
            BoxHeader child = input.readBoxHeader();
            BoxInput payload = input.slice(child.payloadOffset(), child.payloadSize());
            switch (child.type()) {
                case "tkhd" -> parseMoovTkhd(payload);
                case "mdia" -> parseMoovMdia(payload);
                default -> {}
            }
            input.skipBoxPayload(child);
        }
    }

    /// Parses a `tkhd` box for track dimensions.
    private void parseMoovTkhd(BoxInput input) throws AvifDecodeException {
        FullBox fb = readFullBox(input);
        if (fb.version == 1) {
            input.skip(16);
        } else {
            input.skip(8);
        }
        input.readU32();
        input.skip(4);
        input.skip(fb.version == 1 ? 8 : 4);
        input.skip(52);
        long w = input.readU32();
        long h = input.readU32();
        int width = checkedU32ToInt(w >>> 16, input.offset());
        int height = checkedU32ToInt(h >>> 16, input.offset());
        if (width > 0 && meta.moovState.width == 0) {
            meta.moovState.width = width;
        }
        if (height > 0 && meta.moovState.height == 0) {
            meta.moovState.height = height;
        }
    }

    /// Parses a `mdia` box to reach the media information and sample table.
    private void parseMoovMdia(BoxInput input) throws AvifDecodeException {
        while (input.hasRemaining()) {
            BoxHeader child = input.readBoxHeader();
            BoxInput payload = input.slice(child.payloadOffset(), child.payloadSize());
            switch (child.type()) {
                case "mdhd" -> parseMoovMdhd(payload);
                case "minf" -> parseMoovMinf(payload);
                default -> {}
            }
            input.skipBoxPayload(child);
        }
    }

    /// Parses an `mdhd` box for media timescale and duration.
    private void parseMoovMdhd(BoxInput input) throws AvifDecodeException {
        FullBox fb = readFullBox(input);
        if (fb.version == 1) {
            input.skip(16);
        } else {
            input.skip(8);
        }
        int timescale = checkedU32ToInt(input.readU32(), input.offset() - 4);
        long duration = fb.version == 1 ? input.readU64() : input.readU32();
        meta.moovState.mediaTimescale = timescale;
        meta.moovState.mediaDuration = duration;
    }

    /// Parses a `minf` box to reach the sample table.
    private void parseMoovMinf(BoxInput input) throws AvifDecodeException {
        while (input.hasRemaining()) {
            BoxHeader child = input.readBoxHeader();
            if ("stbl".equals(child.type())) {
                BoxInput stblPayload = input.slice(child.payloadOffset(), child.payloadSize());
                parseMoovStbl(stblPayload);
            }
            input.skipBoxPayload(child);
        }
    }

    /// Parses a `stbl` (sample table) box and extracts all available sample metadata.
    private void parseMoovStbl(BoxInput input) throws AvifDecodeException {
        while (input.hasRemaining()) {
            BoxHeader child = input.readBoxHeader();
            BoxInput payload = input.slice(child.payloadOffset(), child.payloadSize());
            switch (child.type()) {
                case "stsd" -> parseMoovStsd(payload);
                case "stts" -> parseMoovStts(payload);
                case "stco" -> parseMoovStco(payload);
                case "stsz" -> parseMoovStsz(payload);
                case "stss" -> parseMoovStss(payload);
                default -> {}
            }
            input.skipBoxPayload(child);
        }
    }

    /// Parses `stsd` and extracts the av01 sample entry with av1C config.
    private void parseMoovStsd(BoxInput input) throws AvifDecodeException {
        readFullBox(input);
        int entryCount = checkedU32ToInt(input.readU32(), input.offset() - 4);
        for (int i = 0; i < Math.min(entryCount, 1); i++) {
            BoxHeader entry = input.readBoxHeader();
            if ("av01".equals(entry.type())) {
                BoxInput av01i = input.slice(entry.payloadOffset(), entry.payloadSize());
                parseMoovAv01Entry(av01i);
            }
            input.skipBoxPayload(entry);
        }
    }

    /// Extracts av1C and colr from an av01 sample entry.
    private void parseMoovAv01Entry(BoxInput input) throws AvifDecodeException {
        input.skip(24);
        int w = input.readU16();
        int h = input.readU16();
        if (w > 0 && meta.moovState.width == 0) meta.moovState.width = w;
        if (h > 0 && meta.moovState.height == 0) meta.moovState.height = h;
        input.skip(50);
        while (input.hasRemaining()) {
            BoxHeader child = input.readBoxHeader();
            BoxInput payload = input.slice(child.payloadOffset(), child.payloadSize());
            switch (child.type()) {
                case "av1C" -> {
                    int av1cPos = payload.offset();
                    Av1Config c = parseAv1C(payload);
                    meta.moovState.bitDepth = c.bitDepth();
                    meta.moovState.pixelFormat = c.pixelFormat();
                    meta.moovState.seqHeaderObu = c.seqHeaderObu(
                            meta.moovState.width > 0 ? meta.moovState.width : 150,
                            meta.moovState.height > 0 ? meta.moovState.height : 150
                    );
                }
                case "colr" -> {
                    Property p = parseColr(payload);
                    if (p instanceof ColorProperty cp) meta.moovState.colr = cp.colorInfo;
                    if (p instanceof IccColorProfile icc) meta.moovState.iccProfile = icc.profile();
                }
                case "auxi" -> meta.moovState.auxiliaryType = parseMoovAuxiliaryType(payload);
                default -> {}
            }
            input.skipBoxPayload(child);
        }
    }

    /// Parses an `auxi` sample-entry box for an AVIS auxiliary track.
    ///
    /// @param input the box payload input
    /// @return the auxiliary image type string
    /// @throws AvifDecodeException if the box is malformed or unsupported
    private static String parseMoovAuxiliaryType(BoxInput input) throws AvifDecodeException {
        FullBox fullBox = readFullBox(input);
        if (fullBox.version != 0 || fullBox.flags != 0) {
            throw unsupported("Unsupported auxi version/flags", input.offset());
        }
        return readNullTerminatedString(input);
    }

    /// Parses `stts` for sample timing deltas.
    private void parseMoovStts(BoxInput input) throws AvifDecodeException {
        input.skip(4);
        int n = checkedU32ToInt(input.readU32(), input.offset() - 4);
        for (int i = 0; i < n; i++) {
            int sc = checkedU32ToInt(input.readU32(), input.offset() - 4);
            int sd = checkedU32ToInt(input.readU32(), input.offset() - 4);
            for (int j = 0; j < sc; j++) meta.moovState.sampleDeltas.add(sd);
        }
    }

    /// Parses `stco` for chunk offsets.
    private void parseMoovStco(BoxInput input) throws AvifDecodeException {
        input.skip(4);
        int n = checkedU32ToInt(input.readU32(), input.offset() - 4);
        for (int i = 0; i < n; i++)
            meta.moovState.chunkOffsets.add(checkedU32ToInt(input.readU32(), input.offset() - 4));
    }

    /// Parses `stsz` for sample sizes.
    private void parseMoovStsz(BoxInput input) throws AvifDecodeException {
        input.skip(4);
        int ss = checkedU32ToInt(input.readU32(), input.offset() - 4);
        int sc = checkedU32ToInt(input.readU32(), input.offset() - 4);
        if (ss == 0) {
            for (int i = 0; i < sc; i++)
                meta.moovState.sampleSizes.add(checkedU32ToInt(input.readU32(), input.offset() - 4));
        } else {
            for (int i = 0; i < sc; i++) meta.moovState.sampleSizes.add(ss);
        }
    }

    /// Parses `stss` for sync sample indices.
    private void parseMoovStss(BoxInput input) throws AvifDecodeException {
        input.skip(4);
        int n = checkedU32ToInt(input.readU32(), input.offset() - 4);
        for (int i = 0; i < n; i++)
            meta.moovState.syncSamples.add(checkedU32ToInt(input.readU32(), input.offset() - 4));
    }

    /// Extracts the SEQUENCE_HEADER OBU bytes from av1C config OBUs.
    private static byte @Nullable [] buildSequenceObu(byte[] configObus) {
        int i = 0;
        while (i < configObus.length) {
            if (i >= configObus.length) break;
            int obuHeader = Byte.toUnsignedInt(configObus[i]);
            int obuType = (obuHeader >>> 3) & 0x1F;
            boolean hasSize = (obuHeader & 2) != 0;
            int obuSize = 0;
            if (hasSize) {
                int sizeOffset = i + 1;
                obuSize = readLeb128Int(configObus, sizeOffset);
                int lebSize = leb128ByteCount(configObus, sizeOffset);
                int obuEnd = i + 1 + lebSize + obuSize;
                if (obuType == 1 && obuEnd <= configObus.length) {
                    int totalSize = 1 + lebSize + obuSize;
                    byte[] result = new byte[totalSize];
                    System.arraycopy(configObus, i, result, 0, totalSize);
                    return result;
                }
                i = obuEnd;
            } else {
                break;
            }
        }
        return null;
    }

    /// Reads a LEB128 unsigned integer from a byte array.
    private static int readLeb128Int(byte[] data, int offset) {
        int value = 0;
        int shift = 0;
        for (int i = 0; i < 5; i++) {
            int b = Byte.toUnsignedInt(data[offset + i]);
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return value;
    }

    /// Returns the byte count of a LEB128-encoded value.
    private static int leb128ByteCount(byte[] data, int offset) {
        int count = 0;
        for (int i = 0; i < 5; i++) {
            count++;
            if ((Byte.toUnsignedInt(data[offset + i]) & 0x80) == 0) break;
        }
        return count;
    }

    /// Parses an `ipma` box.
    ///
    /// @param input the box payload input
    /// @throws AvifDecodeException if the box is malformed
    private void parseItemPropertyAssociation(BoxInput input) throws AvifDecodeException {
        FullBox fullBox = readFullBox(input);
        if (fullBox.version > 1 || (fullBox.flags & ~1) != 0) {
            throw unsupported("Unsupported ipma version/flags", input.offset());
        }
        boolean widePropertyIndex = (fullBox.flags & 1) != 0;
        int entryCount = checkedU32ToInt(input.readU32(), input.offset() - 4);
        for (int entryIndex = 0; entryIndex < entryCount; entryIndex++) {
            int itemId = fullBox.version == 0 ? input.readU16() : checkedU32ToInt(input.readU32(), input.offset() - 4);
            Item item = meta.requireItem(itemId);
            int associationCount = input.readU8();
            for (int associationIndex = 0; associationIndex < associationCount; associationIndex++) {
                int rawAssociation = widePropertyIndex ? input.readU16() : input.readU8();
                boolean essential = (rawAssociation & (widePropertyIndex ? 0x8000 : 0x80)) != 0;
                int propertyIndex = rawAssociation & (widePropertyIndex ? 0x7FFF : 0x7F);
                if (propertyIndex == 0) {
                    if (essential) {
                        throw parseFailed("ipma property index zero cannot be essential", input.offset());
                    }
                    continue;
                }
                int zeroBasedIndex = propertyIndex - 1;
                if (zeroBasedIndex >= meta.properties.size()) {
                    throw parseFailed("ipma references a missing property", input.offset());
                }
                Property property = meta.properties.get(zeroBasedIndex);
                if (property instanceof OpaqueProperty && essential) {
                    item.hasUnsupportedEssentialProperty = true;
                }
                if (!essential && isTransformativeProperty(property)) {
                    throw parseFailed("Transformative item property must be marked essential", input.offset());
                }
                item.properties.add(property);
            }
        }
    }

    /// Returns whether a parsed item property changes the rendered image geometry.
    ///
    /// @param property the parsed item property
    /// @return whether the property is a transformative item property
    private static boolean isTransformativeProperty(Property property) {
        return property instanceof CleanAperture
                || property instanceof ImageRotation
                || property instanceof ImageMirror;
    }

    /// Parses an `iref` box.
    ///
    /// @param input the box payload input
    /// @throws AvifDecodeException if the box is malformed
    private void parseItemReference(BoxInput input) throws AvifDecodeException {
        FullBox fullBox = readFullBox(input);
        if (fullBox.version > 1) {
            throw unsupported("Unsupported iref version: " + fullBox.version, input.offset());
        }
        while (input.hasRemaining()) {
            BoxHeader reference = input.readBoxHeader();
            BoxInput payload = input.slice(reference.payloadOffset(), reference.payloadSize());
            int fromId = fullBox.version == 0 ? payload.readU16() : checkedU32ToInt(payload.readU32(), payload.offset() - 4);
            int referenceCount = payload.readU16();
            Item fromItem = meta.requireItem(fromId);
            for (int i = 0; i < referenceCount; i++) {
                int toId = fullBox.version == 0 ? payload.readU16() : checkedU32ToInt(payload.readU32(), payload.offset() - 4);
                Item toItem = meta.requireItem(toId);
                if ("auxl".equals(reference.type())) {
                    fromItem.auxForId = toItem.id;
                }
                if ("dimg".equals(reference.type())) {
                    if (toItem.dimgForId != 0 && toItem.dimgForId != fromItem.id) {
                        toItem.sharedDerivedImageReference = true;
                    }
                    toItem.dimgForId = fromItem.id;
                    fromItem.dimgCellIds.add(toItem.id);
                }
                if ("prog".equals(reference.type())) {
                    fromItem.progDeps.add(toItem.id);
                }
                if ("cdsc".equals(reference.type())) {
                    fromItem.descForId = toItem.id;
                }
            }
            input.skipBoxPayload(reference);
        }
    }

    /// Parses a `grpl` box.
    ///
    /// @param input the box payload input
    /// @throws AvifDecodeException if the box is malformed
    private void parseGroupsList(BoxInput input) throws AvifDecodeException {
        while (input.hasRemaining()) {
            BoxHeader group = input.readBoxHeader();
            BoxInput payload = input.slice(group.payloadOffset(), group.payloadSize());
            readFullBox(payload);
            int groupId = checkedU32ToInt(payload.readU32(), payload.offset() - 4);
            int entityCount = checkedU32ToInt(payload.readU32(), payload.offset() - 4);
            int[] entityIds = new int[entityCount];
            for (int i = 0; i < entityCount; i++) {
                entityIds[i] = checkedU32ToInt(payload.readU32(), payload.offset() - 4);
            }
            meta.entityGroups.add(new EntityGroup(group.type(), groupId, entityIds));
            input.skipBoxPayload(group);
        }
    }

    /// Finds an auxiliary item for the supplied master image item id.
    ///
    /// @param itemId the master image item id
    /// @param auxiliaryType the requested auxiliary image type string
    /// @return the auxiliary item, or `null`
    private @Nullable Item findAuxiliaryItem(int itemId, String auxiliaryType) {
        Item masterItem = meta.item(itemId);
        for (Item item : meta.items.values()) {
            if (item.auxForId == itemId || (masterItem != null && masterItem.auxForId == item.id)) {
                AuxiliaryType aux = item.firstProperty(AuxiliaryType.class);
                if (aux != null && auxiliaryType.equals(aux.type)) {
                    return item;
                }
            }
        }
        return null;
    }

    /// Returns auxiliary image descriptors associated with one master image item.
    ///
    /// @param itemId the master image item id
    /// @return auxiliary image descriptors
    private AvifAuxiliaryImageInfo @Unmodifiable [] auxiliaryImages(int itemId) {
        Item masterItem = meta.item(itemId);
        ArrayList<AvifAuxiliaryImageInfo> images = new ArrayList<>();
        for (Item item : meta.items.values()) {
            if (item.auxForId == itemId || (masterItem != null && masterItem.auxForId == item.id)) {
                AuxiliaryType aux = item.firstProperty(AuxiliaryType.class);
                if (aux != null) {
                    images.add(auxiliaryImageInfo(item, aux));
                }
            }
        }
        return images.toArray(AvifAuxiliaryImageInfo[]::new);
    }

    /// Creates a public auxiliary image descriptor from one parsed item.
    ///
    /// @param item the auxiliary item
    /// @param auxiliaryType the parsed auxiliary image type
    /// @return an auxiliary image descriptor
    private static AvifAuxiliaryImageInfo auxiliaryImageInfo(Item item, AuxiliaryType auxiliaryType) {
        @Nullable ImageSpatialExtents ispe = item.firstProperty(ImageSpatialExtents.class);
        @Nullable Av1Config av1Config = item.firstProperty(Av1Config.class);
        int width = ispe != null ? ispe.width : -1;
        int height = ispe != null ? ispe.height : -1;
        @Nullable AvifBitDepth bitDepth = av1Config != null ? AvifBitDepth.fromBits(av1Config.bitDepth()) : null;
        @Nullable AvifPixelFormat pixelFormat = av1Config != null ? av1Config.pixelFormat() : null;
        return new AvifAuxiliaryImageInfo(item.id, auxiliaryType.type, item.type, width, height, bitDepth, pixelFormat);
    }

    /// Returns the gain-map descriptor and decodable payloads associated with one base image item.
    ///
    /// @param baseItemId the base image item id
    /// @return the gain-map descriptor and payloads, or empty data
    /// @throws AvifDecodeException if the `tmap` metadata payload is malformed
    private GainMapPayloads gainMapPayloads(int baseItemId) throws AvifDecodeException {
        if (!tmapBrandSeen) {
            return GainMapPayloads.empty();
        }
        for (Item item : meta.items.values()) {
            if (!"tmap".equals(item.type) || item.hasUnsupportedEssentialProperty) {
                continue;
            }
            if (item.dimgCellIds.size() != 2) {
                continue;
            }
            int referencedBaseItemId = item.dimgCellIds.get(0);
            int gainMapItemId = item.dimgCellIds.get(1);
            if (referencedBaseItemId != baseItemId || gainMapItemId == baseItemId) {
                continue;
            }
            if (!isPreferredAlternativeTo(item.id, baseItemId)) {
                continue;
            }
            Item gainMapItem = meta.item(gainMapItemId);
            if (gainMapItem == null || gainMapItem.hasUnsupportedEssentialProperty) {
                return GainMapPayloads.empty();
            }

            ToneMapMetadataVersions versions = toneMapMetadataVersions(item);
            ItemDimensions toneMappedDimensions = itemDimensions(item);

            if ("grid".equals(gainMapItem.type)) {
                GridPayloads grid = parseGridPayloads(gainMapItem);
                AvifGainMapInfo info = gainMapInfo(
                        item,
                        baseItemId,
                        gainMapItem,
                        toneMappedDimensions,
                        new ItemDimensions(grid.outputWidth, grid.outputHeight),
                        AvifBitDepth.fromBits(grid.representativeAv1C.bitDepth()),
                        grid.representativeAv1C.pixelFormat(),
                        versions
                );
                return GainMapPayloads.grid(info, grid);
            }

            ItemDimensions gainMapDimensions = itemDimensions(gainMapItem);
            @Nullable Av1Config gainMapAv1Config = "av01".equals(gainMapItem.type)
                    ? itemAv1Config(gainMapItem)
                    : null;
            @Nullable AvifBitDepth gainMapBitDepth = gainMapAv1Config != null
                    ? AvifBitDepth.fromBits(gainMapAv1Config.bitDepth())
                    : null;
            @Nullable AvifPixelFormat gainMapPixelFormat = gainMapAv1Config != null
                    ? gainMapAv1Config.pixelFormat()
                    : null;
            AvifGainMapInfo info = gainMapInfo(
                    item,
                    baseItemId,
                    gainMapItem,
                    toneMappedDimensions,
                    gainMapDimensions,
                    gainMapBitDepth,
                    gainMapPixelFormat,
                    versions
            );
            if ("av01".equals(gainMapItem.type)) {
                return GainMapPayloads.item(info, mergeItemExtents(gainMapItem));
            }
            return GainMapPayloads.descriptorOnly(info);
        }
        return GainMapPayloads.empty();
    }

    /// Creates a gain-map descriptor from parsed association metadata.
    ///
    /// @param toneMappedItem the `tmap` derived image item
    /// @param baseItemId the base image item id
    /// @param gainMapItem the gain-map image item
    /// @param toneMappedDimensions the tone-mapped item dimensions
    /// @param gainMapDimensions the gain-map item dimensions
    /// @param gainMapBitDepth the gain-map AV1 bit depth, or `null`
    /// @param gainMapPixelFormat the gain-map AV1 pixel format, or `null`
    /// @param versions the parsed tone-map metadata version fields
    /// @return a gain-map descriptor
    private static AvifGainMapInfo gainMapInfo(
            Item toneMappedItem,
            int baseItemId,
            Item gainMapItem,
            ItemDimensions toneMappedDimensions,
            ItemDimensions gainMapDimensions,
            @Nullable AvifBitDepth gainMapBitDepth,
            @Nullable AvifPixelFormat gainMapPixelFormat,
            ToneMapMetadataVersions versions
    ) {
        return new AvifGainMapInfo(
                toneMappedItem.id,
                baseItemId,
                gainMapItem.id,
                toneMappedItem.type,
                gainMapItem.type,
                toneMappedDimensions.width,
                toneMappedDimensions.height,
                gainMapDimensions.width,
                gainMapDimensions.height,
                gainMapBitDepth,
                gainMapPixelFormat,
                versions.version,
                versions.minimumVersion,
                versions.writerVersion,
                versions.supported()
        );
    }

    /// Parses only the version fields from one `tmap` metadata payload.
    ///
    /// @param item the `tmap` item
    /// @return parsed `tmap` metadata version fields
    /// @throws AvifDecodeException if the payload is malformed
    private ToneMapMetadataVersions toneMapMetadataVersions(Item item) throws AvifDecodeException {
        byte[] payload = mergeItemExtents(item);
        if (payload.length < 5) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    "tmap item payload is too short: " + item.id,
                    null
            );
        }
        BoxInput input = new BoxInput(payload);
        int version = input.readU8();
        int minimumVersion = input.readU16();
        int writerVersion = input.readU16();
        return new ToneMapMetadataVersions(version, minimumVersion, writerVersion);
    }

    /// Returns image dimensions from an item's `ispe` property.
    ///
    /// @param item the image item
    /// @return known or unknown item dimensions
    private static ItemDimensions itemDimensions(Item item) {
        @Nullable ImageSpatialExtents ispe = item.firstProperty(ImageSpatialExtents.class);
        return ispe != null ? new ItemDimensions(ispe.width, ispe.height) : ItemDimensions.UNKNOWN;
    }

    /// Returns the AV1 configuration for an image item or a representative grid cell.
    ///
    /// @param item the item to inspect
    /// @return the AV1 configuration, or `null`
    private @Nullable Av1Config itemAv1Config(Item item) {
        @Nullable Av1Config av1Config = item.firstProperty(Av1Config.class);
        if (av1Config != null) {
            return av1Config;
        }
        if (!"grid".equals(item.type)) {
            return null;
        }
        for (int cellId : item.dimgCellIds) {
            Item cell = meta.item(cellId);
            if (cell != null) {
                av1Config = cell.firstProperty(Av1Config.class);
                if (av1Config != null) {
                    return av1Config;
                }
            }
        }
        return null;
    }

    /// Returns whether one entity id is a preferred alternative to another.
    ///
    /// @param preferredId the entity id that must appear first
    /// @param alternativeId the entity id that must appear after `preferredId`
    /// @return whether `preferredId` is a preferred alternative to `alternativeId`
    private boolean isPreferredAlternativeTo(int preferredId, int alternativeId) {
        for (EntityGroup group : meta.entityGroups) {
            if (!"altr".equals(group.type)) {
                continue;
            }
            boolean preferredSeen = false;
            for (int entityId : group.entityIds) {
                if (entityId == preferredId) {
                    preferredSeen = true;
                } else if (entityId == alternativeId) {
                    return preferredSeen;
                }
            }
        }
        return false;
    }

    /// Merges item extents into one contiguous payload.
    ///
    /// @param item the item whose extents should be merged
    /// @return one contiguous item payload
    /// @throws AvifDecodeException if the item data is malformed or truncated
    private byte[] mergeItemExtents(Item item) throws AvifDecodeException {
        if (item.extents.isEmpty()) {
            throw new AvifDecodeException(AvifErrorCode.TRUNCATED_DATA, "Item has no extents: " + item.id, null);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] storage = item.idatStored ? meta.idat : source;
        if (item.idatStored && storage == null) {
            throw new AvifDecodeException(AvifErrorCode.TRUNCATED_DATA, "Item is stored in missing idat: " + item.id, null);
        }
        Objects.requireNonNull(storage, "storage");
        for (Extent extent : item.extents) {
            if (extent.offset < 0 || extent.offset > storage.length || extent.length > storage.length - extent.offset) {
                throw new AvifDecodeException(
                        AvifErrorCode.TRUNCATED_DATA,
                        "Item extent is outside available data: " + item.id,
                        extent.offset
                );
            }
            output.write(storage, (int) extent.offset, extent.length);
        }
        return output.toByteArray();
    }

    /// Reads one full-box header.
    ///
    /// @param input the box payload input
    /// @return one full-box header
    /// @throws AvifDecodeException if the box is truncated
    private static FullBox readFullBox(BoxInput input) throws AvifDecodeException {
        int version = input.readU8();
        int flags = input.readU24();
        return new FullBox(version, flags);
    }

    /// Ensures one unique child box is not repeated.
    ///
    /// @param seen the seen box types
    /// @param type the current box type
    /// @param offset the current box offset
    /// @throws AvifDecodeException if the box repeats
    private static void unique(Set<String> seen, String type, int offset) throws AvifDecodeException {
        if (!seen.add(type)) {
            throw parseFailed("duplicate unique box: " + type, offset);
        }
    }

    /// Reads a BMFF variable-width unsigned integer.
    ///
    /// @param input the input to read from
    /// @param byteCount the encoded byte count
    /// @return the parsed value
    /// @throws AvifDecodeException if the size is unsupported or the input is truncated
    private static long readUx(BoxInput input, int byteCount) throws AvifDecodeException {
        return switch (byteCount) {
            case 0 -> 0L;
            case 4 -> input.readU32();
            case 8 -> input.readU64();
            default -> throw parseFailed("unsupported variable integer byte count: " + byteCount, input.offset());
        };
    }

    /// Validates an `iloc` variable integer field size.
    ///
    /// @param byteCount the byte count to validate
    /// @param offset the associated byte offset
    /// @throws AvifDecodeException if the byte count is invalid
    private static void validateIlocFieldSize(int byteCount, int offset) throws AvifDecodeException {
        if (byteCount != 0 && byteCount != 4 && byteCount != 8) {
            throw parseFailed("invalid iloc field size: " + byteCount, offset);
        }
    }

    /// Adds two unsigned offsets with overflow checking.
    ///
    /// @param left the left value
    /// @param right the right value
    /// @param offset the associated byte offset
    /// @return the checked sum
    /// @throws AvifDecodeException if the sum overflows
    private static long checkedAdd(long left, long right, int offset) throws AvifDecodeException {
        if (right > Long.MAX_VALUE - left) {
            throw parseFailed("integer overflow while merging iloc offsets", offset);
        }
        return left + right;
    }

    /// Converts a parsed unsigned 32-bit value to `int`.
    ///
    /// @param value the parsed unsigned value
    /// @param offset the associated byte offset
    /// @return the converted value
    /// @throws AvifDecodeException if the value exceeds `Integer.MAX_VALUE`
    private static int checkedU32ToInt(long value, int offset) throws AvifDecodeException {
        if (value > Integer.MAX_VALUE) {
            throw parseFailed("32-bit value exceeds supported range: " + value, offset);
        }
        return (int) value;
    }

    /// Converts a parsed unsigned 64-bit value to `int`.
    ///
    /// @param value the parsed unsigned value
    /// @param offset the associated byte offset
    /// @return the converted value
    /// @throws AvifDecodeException if the value exceeds `Integer.MAX_VALUE`
    private static int checkedU64ToInt(long value, int offset) throws AvifDecodeException {
        if (value > Integer.MAX_VALUE) {
            throw parseFailed("64-bit value exceeds supported range: " + value, offset);
        }
        return (int) value;
    }

    /// Creates a BMFF parse failure.
    ///
    /// @param message the failure message
    /// @param offset the associated byte offset
    /// @return a BMFF parse failure
    private static AvifDecodeException parseFailed(String message, long offset) {
        return new AvifDecodeException(AvifErrorCode.BMFF_PARSE_FAILED, message, offset);
    }

    /// Creates an unsupported-feature failure.
    ///
    /// @param message the failure message
    /// @param offset the associated byte offset, or `null`
    /// @return an unsupported-feature failure
    private static AvifDecodeException unsupported(String message, @Nullable Integer offset) {
        return new AvifDecodeException(
                AvifErrorCode.UNSUPPORTED_FEATURE,
                message,
                offset == null ? null : (long) offset
        );
    }

    /// Parsed item dimensions, or an unknown marker.
    @NotNullByDefault
    private static final class ItemDimensions {
        /// Unknown item dimensions.
        private static final ItemDimensions UNKNOWN = new ItemDimensions(-1, -1);

        /// The item width in pixels, or -1 when unknown.
        private final int width;
        /// The item height in pixels, or -1 when unknown.
        private final int height;

        /// Creates item dimensions.
        ///
        /// @param width the item width in pixels, or -1 when unknown
        /// @param height the item height in pixels, or -1 when unknown
        private ItemDimensions(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    /// Extracted AVIS sample payloads for one track.
    @NotNullByDefault
    private static final class SequencePayloads {
        /// The AV1 OBU payloads for each sample in order.
        private final byte @Unmodifiable [] @Unmodifiable [] payloads;
        /// The frame duration deltas in media timescale units.
        private final int @Unmodifiable [] frameDeltas;
        /// The number of samples.
        private final int sampleCount;

        /// Creates extracted AVIS sample payloads.
        ///
        /// @param payloads the AV1 OBU payloads for each sample in order
        /// @param frameDeltas the frame duration deltas in media timescale units
        /// @param sampleCount the number of samples
        private SequencePayloads(
                byte @Unmodifiable [] @Unmodifiable [] payloads,
                int @Unmodifiable [] frameDeltas,
                int sampleCount
        ) {
            this.payloads = Objects.requireNonNull(payloads, "payloads");
            this.frameDeltas = Objects.requireNonNull(frameDeltas, "frameDeltas");
            this.sampleCount = sampleCount;
        }
    }

    /// Parsed `tmap` metadata version fields.
    @NotNullByDefault
    private static final class ToneMapMetadataVersions {
        /// The metadata version field.
        private final int version;
        /// The minimum supported metadata version field.
        private final int minimumVersion;
        /// The writer metadata version field.
        private final int writerVersion;

        /// Creates parsed `tmap` metadata version fields.
        ///
        /// @param version the metadata version field
        /// @param minimumVersion the minimum supported metadata version field
        /// @param writerVersion the writer metadata version field
        private ToneMapMetadataVersions(int version, int minimumVersion, int writerVersion) {
            this.version = version;
            this.minimumVersion = minimumVersion;
            this.writerVersion = writerVersion;
        }

        /// Returns whether this implementation supports these metadata version fields.
        ///
        /// @return whether this implementation supports these metadata version fields
        private boolean supported() {
            return version == 0
                    && minimumVersion <= SUPPORTED_GAIN_MAP_METADATA_VERSION
                    && writerVersion >= minimumVersion;
        }
    }

    /// Parsed BMFF entity group.
    @NotNullByDefault
    private static final class EntityGroup {
        /// The grouping type.
        private final String type;
        /// The group id.
        private final int groupId;
        /// The entity ids in group order.
        private final int @Unmodifiable [] entityIds;

        /// Creates a parsed entity group.
        ///
        /// @param type the grouping type
        /// @param groupId the group id
        /// @param entityIds the entity ids in group order
        private EntityGroup(String type, int groupId, int[] entityIds) {
            this.type = Objects.requireNonNull(type, "type");
            this.groupId = groupId;
            this.entityIds = entityIds.clone();
        }
    }

    /// Mutable parser state for one root `meta` box.
    @NotNullByDefault
    private static final class MetaState {
        /// The primary item id from `pitm`.
        private int primaryItemId;
        /// Parsed items keyed by item id.
        private final Map<Integer, Item> items = new HashMap<>();
        /// Parsed item properties from `ipco`.
        private final List<Property> properties = new ArrayList<>();
        /// Parsed entity groups from `grpl`.
        private final List<EntityGroup> entityGroups = new ArrayList<>();
        /// Auxiliary image type strings parsed from AVIS auxiliary tracks.
        private final List<String> moovAuxiliaryTypes = new ArrayList<>();
        /// The parsed AVIS alpha auxiliary track state, or `null`.
        private @Nullable MoovState moovAlphaState;
        /// The parsed AVIS depth auxiliary track state, or `null`.
        private @Nullable MoovState moovDepthState;
        /// The optional `idat` payload for construction method 1.
        private @Nullable byte[] idat;
        /// The parsed AVIS moov state.
        private final MoovState moovState = new MoovState();

        /// Returns an existing item or creates a new one.
        ///
        /// @param itemId the item id
        /// @return an existing item or a new item
        private Item requireItem(int itemId) {
            return items.computeIfAbsent(itemId, Item::new);
        }

        /// Returns one item by id.
        ///
        /// @param itemId the item id
        /// @return the item, or `null`
        private @Nullable Item item(int itemId) {
            return items.get(itemId);
        }
    }

    /// Mutable parser state for AVIS moov parsing.
    @NotNullByDefault
    private static final class MoovState {
        /// The parsed image sequence width.
        private int width;
        /// The parsed image sequence height.
        private int height;
        /// The parsed image sequence bit depth.
        private int bitDepth;
        /// The parsed image sequence pixel format, or `null`.
        private @Nullable AvifPixelFormat pixelFormat;
        /// The parsed image sequence color information, or `null`.
        private @Nullable AvifColorInfo colr;
        /// The parsed image sequence ICC profile payload, or `null`.
        private byte @Nullable [] iccProfile;
        /// The parsed image sequence media timescale.
        private int mediaTimescale;
        /// The parsed image sequence media duration.
        private long mediaDuration;
        /// The parsed AV1 sequence header OBU, or `null` before an AV1 track is found.
        private byte @Nullable [] seqHeaderObu;
        /// The AVIS auxiliary track type, or `null` for the selected color track.
        private @Nullable String auxiliaryType;
        /// The parsed sample timing deltas.
        private final List<Integer> sampleDeltas = new ArrayList<>();
        /// The parsed chunk offsets.
        private final List<Integer> chunkOffsets = new ArrayList<>();
        /// The parsed sample sizes.
        private final List<Integer> sampleSizes = new ArrayList<>();
        /// The parsed sync sample indices.
        private final List<Integer> syncSamples = new ArrayList<>();

        /// Creates a copy of this sequence track state.
        ///
        /// @return an independent copy of this state
        private MoovState copy() {
            MoovState copy = new MoovState();
            copy.copyFrom(this);
            return copy;
        }

        /// Copies all values from another sequence track state.
        ///
        /// @param other the source state
        private void copyFrom(MoovState other) {
            width = other.width;
            height = other.height;
            bitDepth = other.bitDepth;
            pixelFormat = other.pixelFormat;
            colr = other.colr;
            iccProfile = other.iccProfile == null ? null : other.iccProfile.clone();
            mediaTimescale = other.mediaTimescale;
            mediaDuration = other.mediaDuration;
            seqHeaderObu = other.seqHeaderObu == null ? null : other.seqHeaderObu.clone();
            auxiliaryType = other.auxiliaryType;
            sampleDeltas.clear();
            sampleDeltas.addAll(other.sampleDeltas);
            chunkOffsets.clear();
            chunkOffsets.addAll(other.chunkOffsets);
            sampleSizes.clear();
            sampleSizes.addAll(other.sampleSizes);
            syncSamples.clear();
            syncSamples.addAll(other.syncSamples);
        }
    }

    /// Parsed grid payloads and geometry.
    @NotNullByDefault
    private static final class GridPayloads {
        /// The grid row count.
        private final int rows;
        /// The grid column count.
        private final int columns;
        /// The reconstructed output width.
        private final int outputWidth;
        /// The reconstructed output height.
        private final int outputHeight;
        /// The representative AV1 configuration from the first grid cell.
        private final Av1Config representativeAv1C;
        /// The grid cell AV1 OBU payloads in row-major order.
        private final byte @Unmodifiable [] @Unmodifiable [] cellPayloads;

        /// Creates parsed grid payloads and geometry.
        ///
        /// @param rows the grid row count
        /// @param columns the grid column count
        /// @param outputWidth the reconstructed output width
        /// @param outputHeight the reconstructed output height
        /// @param representativeAv1C the representative AV1 configuration from the first grid cell
        /// @param cellPayloads the grid cell AV1 OBU payloads in row-major order
        private GridPayloads(
                int rows,
                int columns,
                int outputWidth,
                int outputHeight,
                Av1Config representativeAv1C,
                byte @Unmodifiable [] @Unmodifiable [] cellPayloads
        ) {
            this.rows = rows;
            this.columns = columns;
            this.outputWidth = outputWidth;
            this.outputHeight = outputHeight;
            this.representativeAv1C = Objects.requireNonNull(representativeAv1C, "representativeAv1C");
            this.cellPayloads = Objects.requireNonNull(cellPayloads, "cellPayloads");
        }
    }

    /// Parsed auxiliary payloads.
    @NotNullByDefault
    private static final class AuxiliaryPayloads {
        /// The standalone auxiliary item payload, or `null`.
        private final byte @Nullable [] itemPayload;
        /// The auxiliary grid cell AV1 OBU payloads in row-major order, or `null`.
        private final byte @Unmodifiable [] @Nullable @Unmodifiable [] gridCellPayloads;
        /// The auxiliary grid row count.
        private final int gridRows;
        /// The auxiliary grid column count.
        private final int gridColumns;
        /// The auxiliary grid reconstructed output width.
        private final int gridOutputWidth;
        /// The auxiliary grid reconstructed output height.
        private final int gridOutputHeight;

        /// Creates parsed auxiliary payloads.
        ///
        /// @param itemPayload the standalone auxiliary item payload, or `null`
        /// @param gridCellPayloads the auxiliary grid cell AV1 OBU payloads, or `null`
        /// @param gridRows the auxiliary grid row count
        /// @param gridColumns the auxiliary grid column count
        /// @param gridOutputWidth the auxiliary grid reconstructed output width
        /// @param gridOutputHeight the auxiliary grid reconstructed output height
        private AuxiliaryPayloads(
                byte @Nullable [] itemPayload,
                byte @Unmodifiable [] @Nullable @Unmodifiable [] gridCellPayloads,
                int gridRows,
                int gridColumns,
                int gridOutputWidth,
                int gridOutputHeight
        ) {
            this.itemPayload = itemPayload;
            this.gridCellPayloads = gridCellPayloads;
            this.gridRows = gridRows;
            this.gridColumns = gridColumns;
            this.gridOutputWidth = gridOutputWidth;
            this.gridOutputHeight = gridOutputHeight;
        }

        /// Creates empty auxiliary payloads.
        ///
        /// @return empty auxiliary payloads
        private static AuxiliaryPayloads empty() {
            return new AuxiliaryPayloads(null, null, 0, 0, 0, 0);
        }

        /// Creates auxiliary payloads from one standalone item.
        ///
        /// @param itemPayload the standalone auxiliary item payload
        /// @return auxiliary payloads for the item
        private static AuxiliaryPayloads item(byte[] itemPayload) {
            return new AuxiliaryPayloads(Objects.requireNonNull(itemPayload, "itemPayload"),
                    null, 0, 0, 0, 0);
        }

        /// Creates auxiliary payloads from grid data.
        ///
        /// @param grid the parsed auxiliary grid
        /// @return auxiliary payloads for the grid
        private static AuxiliaryPayloads grid(GridPayloads grid) {
            return new AuxiliaryPayloads(null, grid.cellPayloads, grid.rows, grid.columns,
                    grid.outputWidth, grid.outputHeight);
        }

        /// Returns whether any auxiliary payload is present.
        ///
        /// @return whether an auxiliary image is present
        private boolean present() {
            return itemPayload != null || gridCellPayloads != null;
        }
    }

    /// Parsed gain-map descriptor and decodable image payloads.
    @NotNullByDefault
    private static final class GainMapPayloads {
        /// The public gain-map descriptor, or `null`.
        private final @Nullable AvifGainMapInfo info;
        /// The standalone gain-map item payload, or `null`.
        private final byte @Nullable [] itemPayload;
        /// The gain-map grid cell AV1 OBU payloads in row-major order, or `null`.
        private final byte @Unmodifiable [] @Nullable @Unmodifiable [] gridCellPayloads;
        /// The gain-map grid row count.
        private final int gridRows;
        /// The gain-map grid column count.
        private final int gridColumns;
        /// The gain-map grid reconstructed output width.
        private final int gridOutputWidth;
        /// The gain-map grid reconstructed output height.
        private final int gridOutputHeight;

        /// Creates parsed gain-map payloads.
        ///
        /// @param info the public gain-map descriptor, or `null`
        /// @param itemPayload the standalone gain-map item payload, or `null`
        /// @param gridCellPayloads the gain-map grid cell AV1 OBU payloads, or `null`
        /// @param gridRows the gain-map grid row count
        /// @param gridColumns the gain-map grid column count
        /// @param gridOutputWidth the gain-map grid reconstructed output width
        /// @param gridOutputHeight the gain-map grid reconstructed output height
        private GainMapPayloads(
                @Nullable AvifGainMapInfo info,
                byte @Nullable [] itemPayload,
                byte @Unmodifiable [] @Nullable @Unmodifiable [] gridCellPayloads,
                int gridRows,
                int gridColumns,
                int gridOutputWidth,
                int gridOutputHeight
        ) {
            this.info = info;
            this.itemPayload = itemPayload;
            this.gridCellPayloads = gridCellPayloads;
            this.gridRows = gridRows;
            this.gridColumns = gridColumns;
            this.gridOutputWidth = gridOutputWidth;
            this.gridOutputHeight = gridOutputHeight;
        }

        /// Creates empty gain-map payloads.
        ///
        /// @return empty gain-map payloads
        private static GainMapPayloads empty() {
            return new GainMapPayloads(null, null, null, 0, 0, 0, 0);
        }

        /// Creates descriptor-only gain-map payloads.
        ///
        /// @param info the public gain-map descriptor
        /// @return descriptor-only gain-map payloads
        private static GainMapPayloads descriptorOnly(AvifGainMapInfo info) {
            return new GainMapPayloads(Objects.requireNonNull(info, "info"), null, null, 0, 0, 0, 0);
        }

        /// Creates gain-map payloads from one standalone item.
        ///
        /// @param info the public gain-map descriptor
        /// @param itemPayload the standalone gain-map item payload
        /// @return gain-map payloads for the item
        private static GainMapPayloads item(AvifGainMapInfo info, byte[] itemPayload) {
            return new GainMapPayloads(
                    Objects.requireNonNull(info, "info"),
                    Objects.requireNonNull(itemPayload, "itemPayload"),
                    null,
                    0,
                    0,
                    0,
                    0
            );
        }

        /// Creates gain-map payloads from grid data.
        ///
        /// @param info the public gain-map descriptor
        /// @param grid the parsed gain-map grid
        /// @return gain-map payloads for the grid
        private static GainMapPayloads grid(AvifGainMapInfo info, GridPayloads grid) {
            return new GainMapPayloads(
                    Objects.requireNonNull(info, "info"),
                    null,
                    grid.cellPayloads,
                    grid.rows,
                    grid.columns,
                    grid.outputWidth,
                    grid.outputHeight
            );
        }
    }

    /// Parsed image metadata payloads.
    @NotNullByDefault
    private static final class MetadataPayloads {
        /// The ICC profile payload, or `null`.
        private final byte @Nullable [] iccProfile;
        /// The Exif metadata payload, or `null`.
        private final byte @Nullable [] exif;
        /// The XMP metadata payload, or `null`.
        private final byte @Nullable [] xmp;

        /// Creates parsed metadata payloads.
        ///
        /// @param iccProfile the ICC profile payload, or `null`
        /// @param exif the Exif metadata payload, or `null`
        /// @param xmp the XMP metadata payload, or `null`
        private MetadataPayloads(byte @Nullable [] iccProfile, byte @Nullable [] exif, byte @Nullable [] xmp) {
            this.iccProfile = iccProfile;
            this.exif = exif;
            this.xmp = xmp;
        }
    }

    /// Mutable parser state for one item.
    @NotNullByDefault
    private static final class Item {
        /// The item id.
        private final int id;
        /// The item type.
        private String type = "";
        /// The item name from `infe`.
        private String name = "";
        /// The MIME content type for `mime` items, or an empty string.
        private String contentType = "";
        /// The MIME content encoding for `mime` items, or an empty string.
        private String contentEncoding = "";
        /// Whether the item extents are stored in `idat`.
        private boolean idatStored;
        /// The item extents.
        private final List<Extent> extents = new ArrayList<>();
        /// The associated item properties.
        private final List<Property> properties = new ArrayList<>();
        /// Whether the item has an unsupported essential property.
        private boolean hasUnsupportedEssentialProperty;
        /// The master image item id for auxiliary items.
        private int auxForId;
        /// The image item id described by this metadata item, or 0.
        private int descForId;
        /// The grid item id for dimg cell items, or 0.
        private int dimgForId;
        /// The dimg cell item ids for a grid item, in row-major order.
        private final List<Integer> dimgCellIds = new ArrayList<>();
        /// Whether this cell item is referenced by multiple derived-image items.
        private boolean sharedDerivedImageReference;
        /// The progressive dependency item ids from `prog` references.
        private final List<Integer> progDeps = new ArrayList<>();

        /// Creates item parser state.
        ///
        /// @param id the item id
        private Item(int id) {
            this.id = id;
        }

        /// Finds the first associated property assignable to one type.
        ///
        /// @param type the property class
        /// @param <T> the property type
        /// @return the first matching property, or `null`
        private <T> @Nullable T firstProperty(Class<T> type) {
            for (Property property : properties) {
                if (type.isInstance(property)) {
                    return type.cast(property);
                }
                if (type == AvifColorInfo.class && property instanceof ColorProperty colorProperty) {
                    return type.cast(colorProperty.colorInfo);
                }
            }
            return null;
        }
    }

    /// One item extent.
    @NotNullByDefault
    private static final class Extent {
        /// The extent byte offset.
        private final long offset;
        /// The extent byte length.
        private final int length;

        /// Creates one item extent.
        ///
        /// @param offset the extent byte offset
        /// @param length the extent byte length
        private Extent(long offset, int length) {
            this.offset = offset;
            this.length = length;
        }
    }

    /// One full-box header.
    @NotNullByDefault
    private static final class FullBox {
        /// The full-box version.
        private final int version;
        /// The full-box flags.
        private final int flags;

        /// Creates one full-box header.
        ///
        /// @param version the full-box version
        /// @param flags the full-box flags
        private FullBox(int version, int flags) {
            this.version = version;
            this.flags = flags;
        }
    }

    /// Marker interface for parsed item properties.
    @NotNullByDefault
    private sealed interface Property permits ImageSpatialExtents, Av1Config, ColorProperty, IccColorProfile, AuxiliaryType, OpaqueProperty, PixelInformation, PixelAspectRatio, CleanAperture, ImageRotation, ImageMirror, OperatingPoint {
    }

    /// Parsed `ispe` item property.
    @NotNullByDefault
    private static final class ImageSpatialExtents implements Property {
        /// The image width.
        private final int width;
        /// The image height.
        private final int height;

        /// Creates parsed image spatial extents.
        ///
        /// @param width the image width
        /// @param height the image height
        private ImageSpatialExtents(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    /// Parsed `av1C` item property.
    @NotNullByDefault
    private static final class Av1Config implements Property {
        /// The AV1 sequence profile.
        private final int seqProfile;
        /// The AV1 sequence level index.
        private final int seqLevelIdx0;
        /// Whether high bit depth is signaled.
        private final boolean highBitDepth;
        /// Whether twelve-bit depth is signaled.
        private final boolean twelveBit;
        /// Whether monochrome output is signaled.
        private final boolean monochrome;
        /// Whether horizontal chroma subsampling is signaled.
        private final boolean chromaSubsamplingX;
        /// Whether vertical chroma subsampling is signaled.
        private final boolean chromaSubsamplingY;
        /// The chroma sample position.
        private final int chromaSamplePosition;

        /// Creates parsed AV1 codec configuration.
        ///
        /// @param seqProfile the AV1 sequence profile
        /// @param seqLevelIdx0 the AV1 sequence level index
        /// @param highBitDepth whether high bit depth is signaled
        /// @param twelveBit whether twelve-bit depth is signaled
        /// @param monochrome whether monochrome output is signaled
        /// @param chromaSubsamplingX whether horizontal chroma subsampling is signaled
        /// @param chromaSubsamplingY whether vertical chroma subsampling is signaled
        /// @param chromaSamplePosition the chroma sample position
        private Av1Config(
                int seqProfile,
                int seqLevelIdx0,
                boolean highBitDepth,
                boolean twelveBit,
                boolean monochrome,
                boolean chromaSubsamplingX,
                boolean chromaSubsamplingY,
                int chromaSamplePosition
        ) {
            this.seqProfile = seqProfile;
            this.seqLevelIdx0 = seqLevelIdx0;
            this.highBitDepth = highBitDepth;
            this.twelveBit = twelveBit;
            this.monochrome = monochrome;
            this.chromaSubsamplingX = chromaSubsamplingX;
            this.chromaSubsamplingY = chromaSubsamplingY;
            this.chromaSamplePosition = chromaSamplePosition;
        }

        /// Returns the decoded bit depth.
        ///
        /// @return the decoded bit depth
        private int bitDepth() {
            return highBitDepth ? (twelveBit ? 12 : 10) : 8;
        }

        /// Returns the AV1 chroma sampling layout.
        ///
        /// @return the AV1 chroma sampling layout
        private AvifPixelFormat pixelFormat() {
            if (monochrome) {
                return AvifPixelFormat.I400;
            }
            if (chromaSubsamplingX && chromaSubsamplingY) {
                return AvifPixelFormat.I420;
            }
            if (chromaSubsamplingX) {
                return AvifPixelFormat.I422;
            }
            return AvifPixelFormat.I444;
        }

        /// Constructs a minimal AV1 SEQUENCE_HEADER OBU from this configuration.
        ///
        /// @param width the image width
        /// @param height the image height
        /// @return the SEQUENCE_HEADER OBU bytes ready for decoding
        private byte @Unmodifiable [] seqHeaderObu(int width, int height) {
            byte[] payload = reducedStillSeqHdrPayload(width, height);
            ByteArrayOutputStream obu = new ByteArrayOutputStream();
            obu.write((1 << 3) | (1 << 1));
            writeLeb128(obu, payload.length);
            obu.writeBytes(payload);
            return obu.toByteArray();
        }

        /// Builds the payload portion of a reduced still-picture SEQUENCE_HEADER.
        private byte[] reducedStillSeqHdrPayload(int frameWidth, int frameHeight) {
            int mfw = frameWidth - 1;
            int mfh = frameHeight - 1;
            int fb = 32 - Integer.numberOfLeadingZeros(mfw);
            if (fb < 1) fb = 1;
            int fh = 32 - Integer.numberOfLeadingZeros(mfh);
            if (fh < 1) fh = 1;
            SeqBitWriter w = new SeqBitWriter();
            w.bits(0, 3);
            w.flag(true);
            w.flag(true);
            w.bits(0, 3);
            w.bits(0, 5);
            w.bits(fb, 4);
            w.bits(fh, 4);
            w.bits(mfw, fb + 1);
            w.bits(mfh, fh + 1);
            w.flag(false);
            w.flag(true);
            w.flag(true);
            w.flag(false);
            w.flag(true);
            w.flag(true);
            w.flag(false);
            if (monochrome) {
                w.flag(true);
                w.flag(false);
                w.flag(true);
                w.flag(true);
            } else {
                w.flag(false);
                w.flag(false);
                w.flag(false);
                w.bits(1, 2);
                w.flag(true);
                w.flag(true);
            }
            w.flag(false);
            w.trail();
            return w.toBytes();
        }
    }

    /// Writes a LEB128 unsigned value to the stream.
    private static void writeLeb128(ByteArrayOutputStream out, int value) {
        int v = value;
        do {
            int b = v & 0x7F;
            v >>>= 7;
            if (v != 0) b |= 0x80;
            out.write(b);
        } while (v != 0);
    }

    /// Reads one null-terminated UTF-8 string.
    ///
    /// @param input the source input
    /// @return the decoded string without the terminator
    /// @throws AvifDecodeException if the string is not terminated within this input
    private static String readNullTerminatedString(BoxInput input) throws AvifDecodeException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (input.hasRemaining()) {
            int value = input.readU8();
            if (value == 0) {
                return output.toString(StandardCharsets.UTF_8);
            }
            output.write(value);
        }
        throw parseFailed("unterminated BMFF string", input.offset());
    }

    /// Minimal MSB-first bit writer for sequence header construction.
    @NotNullByDefault
    private static final class SeqBitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int cur;
        private int cnt;

        private void flag(boolean v) { bit(v ? 1 : 0); }
        private void bits(long v, int w) {
            for (int b = w - 1; b >= 0; b--) bit((int) ((v >>> b) & 1L));
        }
        private void trail() { bit(1); while (cnt != 0) bit(0); }

        private void bit(int b) {
            cur = (cur << 1) | (b & 1);
            if (++cnt == 8) { out.write(cur); cur = 0; cnt = 0; }
        }
        private byte[] toBytes() {
            if (cnt > 0) throw new IllegalStateException("not byte aligned");
            return out.toByteArray();
        }
    }

    /// Parsed `colr` item property carrying `nclx` color information.
    @NotNullByDefault
    private static final class ColorProperty implements Property {
        /// The parsed color information.
        private final AvifColorInfo colorInfo;

        /// Creates a color property.
        ///
        /// @param colorInfo the parsed color information
        private ColorProperty(AvifColorInfo colorInfo) {
            this.colorInfo = Objects.requireNonNull(colorInfo, "colorInfo");
        }
    }

    /// Parsed `colr` item property carrying an ICC profile.
    @NotNullByDefault
    private static final class IccColorProfile implements Property {
        /// The ICC profile payload.
        private final byte @Unmodifiable [] profile;

        /// Creates an ICC color profile property.
        ///
        /// @param profile the ICC profile payload
        private IccColorProfile(byte[] profile) {
            this.profile = profile.clone();
        }

        /// Returns a copy of the ICC profile payload.
        ///
        /// @return the ICC profile payload
        private byte @Unmodifiable [] profile() {
            return profile.clone();
        }
    }

    /// Parsed `auxC` item property.
    @NotNullByDefault
    private static final class AuxiliaryType implements Property {
        /// The auxiliary image type string.
        private final String type;

        /// Creates an auxiliary type property.
        ///
        /// @param type the auxiliary image type string
        private AuxiliaryType(String type) {
            this.type = Objects.requireNonNull(type, "type");
        }
    }

    /// Opaque or currently unsupported item property.
    @NotNullByDefault
    private static final class OpaqueProperty implements Property {
        /// The property type.
        private final String type;

        /// Creates an opaque property marker.
        ///
        /// @param type the property type
        private OpaqueProperty(String type) {
            this.type = Objects.requireNonNull(type, "type");
        }
    }

    /// Parsed `pixi` item property.
    @NotNullByDefault
    private static final class PixelInformation implements Property {
        /// The bits-per-channel array.
        private final int @Unmodifiable [] bitsPerChannel;

        /// Creates a pixel information property.
        ///
        /// @param bitsPerChannel the bits-per-channel array
        private PixelInformation(int[] bitsPerChannel) {
            this.bitsPerChannel = bitsPerChannel.clone();
        }
    }

    /// Parsed `pasp` item property.
    @NotNullByDefault
    private static final class PixelAspectRatio implements Property {
        /// The horizontal relative spacing.
        private final int hSpacing;
        /// The vertical relative spacing.
        private final int vSpacing;

        /// Creates a pixel aspect ratio property.
        ///
        /// @param hSpacing the horizontal relative spacing
        /// @param vSpacing the vertical relative spacing
        private PixelAspectRatio(int hSpacing, int vSpacing) {
            this.hSpacing = hSpacing;
            this.vSpacing = vSpacing;
        }
    }

    /// Parsed `clap` item property.
    @NotNullByDefault
    private static final class CleanAperture implements Property {
        /// The clean aperture width numerator.
        private final int cleanApertureWidthN;
        /// The clean aperture width denominator.
        private final int cleanApertureWidthD;
        /// The clean aperture height numerator.
        private final int cleanApertureHeightN;
        /// The clean aperture height denominator.
        private final int cleanApertureHeightD;
        /// The horizontal offset numerator.
        private final int horizOffN;
        /// The horizontal offset denominator.
        private final int horizOffD;
        /// The vertical offset numerator.
        private final int vertOffN;
        /// The vertical offset denominator.
        private final int vertOffD;

        /// Creates a clean aperture property.
        ///
        /// @param cleanApertureWidthN the clean aperture width numerator
        /// @param cleanApertureWidthD the clean aperture width denominator
        /// @param cleanApertureHeightN the clean aperture height numerator
        /// @param cleanApertureHeightD the clean aperture height denominator
        /// @param horizOffN the horizontal offset numerator
        /// @param horizOffD the horizontal offset denominator
        /// @param vertOffN the vertical offset numerator
        /// @param vertOffD the vertical offset denominator
        private CleanAperture(
                int cleanApertureWidthN, int cleanApertureWidthD,
                int cleanApertureHeightN, int cleanApertureHeightD,
                int horizOffN, int horizOffD,
                int vertOffN, int vertOffD
        ) {
            this.cleanApertureWidthN = cleanApertureWidthN;
            this.cleanApertureWidthD = cleanApertureWidthD;
            this.cleanApertureHeightN = cleanApertureHeightN;
            this.cleanApertureHeightD = cleanApertureHeightD;
            this.horizOffN = horizOffN;
            this.horizOffD = horizOffD;
            this.vertOffN = vertOffN;
            this.vertOffD = vertOffD;
        }
    }

    /// Parsed `irot` item property.
    @NotNullByDefault
    private static final class ImageRotation implements Property {
        /// The rotation angle code.
        private final int rotation;

        /// Creates an image rotation property.
        ///
        /// @param rotation the rotation angle code
        private ImageRotation(int rotation) {
            this.rotation = rotation;
        }
    }

    /// Parsed `imir` item property.
    @NotNullByDefault
    private static final class ImageMirror implements Property {
        /// The mirror axis.
        private final int axis;

        /// Creates an image mirror property.
        ///
        /// @param axis the mirror axis
        private ImageMirror(int axis) {
            this.axis = axis;
        }
    }

    /// Parsed `a1op` item property.
    @NotNullByDefault
    private static final class OperatingPoint implements Property {
        /// The operating point index.
        private final int operatingPoint;

        /// Creates an operating point property.
        ///
        /// @param operatingPoint the operating point index
        private OperatingPoint(int operatingPoint) {
            this.operatingPoint = operatingPoint;
        }
    }
}
