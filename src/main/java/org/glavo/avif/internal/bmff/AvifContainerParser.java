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

import org.glavo.avif.AvifColorInfo;
import org.glavo.avif.AvifDecodeException;
import org.glavo.avif.AvifErrorCode;
import org.glavo.avif.AvifImageInfo;
import org.glavo.avif.decode.PixelFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Parser for the AVIF primary-item subset of BMFF.
@NotNullByDefault
public final class AvifContainerParser {
    /// The source bytes.
    private final byte @Unmodifiable [] source;
    /// The parsed metadata state.
    private final MetaState meta = new MetaState();
    /// Whether an AVIF-compatible `ftyp` box was parsed.
    private boolean compatibleFileTypeSeen;
    /// Whether an `avis` brand was parsed.
    private boolean avisBrandSeen;

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

        boolean alphaPresent = false;
        byte @Nullable [] alphaPayload = null;
        Item alphaItem = findAlphaItem(primaryItem.id);
        if (alphaItem != null) {
            if (!"av01".equals(alphaItem.type)) {
                throw unsupported("Unsupported alpha auxiliary item type: " + alphaItem.type, null);
            }
            if (alphaItem.hasUnsupportedEssentialProperty) {
                throw new AvifDecodeException(AvifErrorCode.MISSING_IMAGE_ITEM, "Alpha auxiliary item is not usable", null);
            }
            ImageSpatialExtents alphaIspe = alphaItem.firstProperty(ImageSpatialExtents.class);
            if (alphaIspe == null) {
                throw new AvifDecodeException(AvifErrorCode.BMFF_PARSE_FAILED, "Alpha auxiliary item is missing ispe", null);
            }
            if (alphaIspe.width != ispe.width || alphaIspe.height != ispe.height) {
                throw unsupported(
                        "Alpha auxiliary image with different dimensions than the master image is not implemented in this slice",
                        null
                );
            }
            if (alphaItem.firstProperty(Av1Config.class) == null) {
                throw new AvifDecodeException(AvifErrorCode.BMFF_PARSE_FAILED, "Alpha auxiliary item is missing av1C", null);
            }
            alphaPresent = true;
            alphaPayload = mergeItemExtents(alphaItem);
        }

        byte[] payload = mergeItemExtents(primaryItem);
        AvifImageInfo info = new AvifImageInfo(
                ispe.width,
                ispe.height,
                av1Config.bitDepth(),
                av1Config.pixelFormat(),
                alphaPresent,
                false,
                1,
                primaryItem.firstProperty(AvifColorInfo.class)
        );
        int[] transformParams = extractTransformParams(primaryItem, ispe.width, ispe.height);

        return new AvifContainer(info, payload, alphaPayload,
                transformParams[0], transformParams[1], transformParams[2], transformParams[3],
                transformParams[4], transformParams[5]);
    }

    /// Parses a grid derived image container.
    ///
    /// @param gridItem the primary grid item
    /// @return the parsed AVIF container
    /// @throws AvifDecodeException if the grid is malformed or unsupported
    private AvifContainer parseGridContainer(Item gridItem) throws AvifDecodeException {
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

        ImageSpatialExtents representativeIspe = null;
        Av1Config representativeAv1C = null;
        List<byte[]> cellPayloads = new ArrayList<>(expectedCellCount);

        for (int i = 0; i < expectedCellCount; i++) {
            int cellId = cellIds.get(i);
            Item cellItem = meta.item(cellId);
            if (cellItem == null) {
                throw parseFailed("grid dimg cell item not found: " + cellId, 0);
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

        boolean alphaPresent = hasGridAlpha(gridItem.id, cellIds);

        AvifImageInfo info = new AvifImageInfo(
                outputWidth,
                outputHeight,
                representativeAv1C.bitDepth(),
                representativeAv1C.pixelFormat(),
                alphaPresent,
                false,
                1,
                gridItem.firstProperty(AvifColorInfo.class)
        );

        byte[][] payloads = cellPayloads.toArray(byte[][]::new);
        int[] transforms = extractTransformParams(gridItem, outputWidth, outputHeight);
        return new AvifContainer(info, payloads, rows, columns, outputWidth, outputHeight,
                transforms[0], transforms[1], transforms[2], transforms[3],
                transforms[4], transforms[5]);
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

    /// Returns whether a grid image has alpha auxiliary images.
    ///
    /// @param gridItemId the grid item id
    /// @param cellIds the grid cell item ids
    /// @return whether alpha is present
    private boolean hasGridAlpha(int gridItemId, List<Integer> cellIds) {
        for (Item item : meta.items.values()) {
            if (item.auxForId == gridItemId) {
                AuxiliaryType aux = item.firstProperty(AuxiliaryType.class);
                if (aux != null && "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha".equals(aux.type)) {
                    return true;
                }
            }
        }
        return false;
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
        while (input.remaining() >= 4) {
            String brand = input.readFourCc();
            hasAvif |= "avif".equals(brand);
            hasAvis |= "avis".equals(brand);
        }
        if (input.remaining() != 0) {
            throw parseFailed("ftyp compatible brands length is not divisible by four", input.offset());
        }
        compatibleFileTypeSeen = hasAvif || hasAvis;
        avisBrandSeen = hasAvis;
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
        int horizOffN = checkedU32ToInt(input.readU32(), input.offset() - 4);
        int horizOffD = checkedU32ToInt(input.readU32(), input.offset() - 4);
        int vertOffN = checkedU32ToInt(input.readU32(), input.offset() - 4);
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
        if (s.sampleSizes.isEmpty()) {
            throw new AvifDecodeException(AvifErrorCode.BMFF_PARSE_FAILED, "Sequence has no samples", null);
        }
        if (s.seqHeaderObu == null) {
            throw new AvifDecodeException(AvifErrorCode.BMFF_PARSE_FAILED, "Sequence is missing av1C sequence header", null);
        }
        int sampleCount = s.sampleSizes.size();
        int chunkOff = s.chunkOffsets.isEmpty() ? 0 : s.chunkOffsets.get(0);
        int bytesOff = 0;
        List<byte[]> payloads = new ArrayList<>(sampleCount);
        List<Integer> deltas = new ArrayList<>(sampleCount);
        for (int i = 0; i < sampleCount; i++) {
            int sz = s.sampleSizes.get(i);
            long off = (long) chunkOff + bytesOff;
            if (off + sz > source.length)
                throw new AvifDecodeException(AvifErrorCode.TRUNCATED_DATA, "Sample outside source: " + i, off);
            byte[] sampleData = new byte[sz];
            System.arraycopy(source, (int) off, sampleData, 0, sz);
            byte[] data = new byte[s.seqHeaderObu.length + sampleData.length];
            System.arraycopy(s.seqHeaderObu, 0, data, 0, s.seqHeaderObu.length);
            System.arraycopy(sampleData, 0, data, s.seqHeaderObu.length, sampleData.length);
            payloads.add(data);
            bytesOff += sz;
            deltas.add(i < s.sampleDeltas.size() ? s.sampleDeltas.get(i) : 1);
        }
        int ts = s.mediaTimescale > 0 ? s.mediaTimescale : 30;
        long dur = s.mediaDuration > 0 ? s.mediaDuration : sampleCount;
        AvifImageInfo info = new AvifImageInfo(
                s.width > 0 ? s.width : 1,
                s.height > 0 ? s.height : 1,
                s.bitDepth > 0 ? s.bitDepth : 8,
                s.pixelFormat != null ? s.pixelFormat : PixelFormat.I420, false, true, sampleCount, s.colr);
        return new AvifContainer(info,
                payloads.toArray(byte[][]::new),
                deltas.stream().mapToInt(Integer::intValue).toArray(),
                sampleCount, ts, dur);
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
                BoxInput trakPayload = input.slice(child.payloadOffset(), child.payloadSize());
                parseMoovTrack(trakPayload);
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
        if (fb.version == 1) {
            input.readU64();
        } else {
            input.readU32();
        }
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
                }
                default -> {}
            }
            input.skipBoxPayload(child);
        }
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
                item.properties.add(property);
            }
        }
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
                    toItem.auxForId = fromItem.id;
                }
                if ("dimg".equals(reference.type())) {
                    toItem.dimgForId = fromItem.id;
                    fromItem.dimgCellIds.add(toItem.id);
                }
                if ("prog".equals(reference.type())) {
                    fromItem.progDeps.add(toItem.id);
                }
            }
            input.skipBoxPayload(reference);
        }
    }

    /// Finds the alpha auxiliary item for the supplied master image item id.
    ///
    /// @param itemId the master image item id
    /// @return the alpha auxiliary item, or `null`
    private @Nullable Item findAlphaItem(int itemId) {
        for (Item item : meta.items.values()) {
            if (item.auxForId == itemId) {
                AuxiliaryType aux = item.firstProperty(AuxiliaryType.class);
                if (aux != null && "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha".equals(aux.type)) {
                    return item;
                }
            }
        }
        return null;
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

    /// Mutable parser state for one root `meta` box.
    @NotNullByDefault
    private static final class MetaState {
        /// The primary item id from `pitm`.
        private int primaryItemId;
        /// Parsed items keyed by item id.
        private final Map<Integer, Item> items = new HashMap<>();
        /// Parsed item properties from `ipco`.
        private final List<Property> properties = new ArrayList<>();
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
        private int width;
        private int height;
        private int bitDepth;
        private @Nullable PixelFormat pixelFormat;
        private @Nullable AvifColorInfo colr;
        private int mediaTimescale;
        private long mediaDuration;
        private byte @Nullable [] seqHeaderObu;
        private final List<Integer> sampleDeltas = new ArrayList<>();
        private final List<Integer> chunkOffsets = new ArrayList<>();
        private final List<Integer> sampleSizes = new ArrayList<>();
        private final List<Integer> syncSamples = new ArrayList<>();
    }

    /// Mutable parser state for one item.
    @NotNullByDefault
    private static final class Item {
        /// The item id.
        private final int id;
        /// The item type.
        private String type = "";
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
        /// The grid item id for dimg cell items, or 0.
        private int dimgForId;
        /// The dimg cell item ids for a grid item, in row-major order.
        private final List<Integer> dimgCellIds = new ArrayList<>();
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
    private sealed interface Property permits ImageSpatialExtents, Av1Config, ColorProperty, AuxiliaryType, OpaqueProperty, PixelInformation, PixelAspectRatio, CleanAperture, ImageRotation, ImageMirror, OperatingPoint {
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
        private PixelFormat pixelFormat() {
            if (monochrome) {
                return PixelFormat.I400;
            }
            if (chromaSubsamplingX && chromaSubsamplingY) {
                return PixelFormat.I420;
            }
            if (chromaSubsamplingX) {
                return PixelFormat.I422;
            }
            return PixelFormat.I444;
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
