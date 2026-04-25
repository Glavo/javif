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
                case "moov" -> avisBrandSeen = true;
                default -> {
                }
            }
            input.skipBoxPayload(header);
        }

        if (!compatibleFileTypeSeen) {
            throw new AvifDecodeException(AvifErrorCode.INVALID_FTYP, "Missing AVIF-compatible ftyp box", 0L);
        }
        if (avisBrandSeen) {
            throw unsupported("AVIS image sequence tracks are not implemented in this slice", null);
        }
        if (meta.primaryItemId == 0) {
            throw new AvifDecodeException(AvifErrorCode.MISSING_IMAGE_ITEM, "Primary item is not specified", null);
        }

        Item primaryItem = meta.item(meta.primaryItemId);
        if (primaryItem == null || primaryItem.hasUnsupportedEssentialProperty) {
            throw new AvifDecodeException(AvifErrorCode.MISSING_IMAGE_ITEM, "Primary item is not usable", null);
        }
        if (!"av01".equals(primaryItem.type)) {
            if ("grid".equals(primaryItem.type)) {
                throw unsupported("AVIF image grids are not implemented in this slice", null);
            }
            throw unsupported("Unsupported primary item type: " + primaryItem.type, null);
        }

        ImageSpatialExtents ispe = primaryItem.firstProperty(ImageSpatialExtents.class);
        if (ispe == null) {
            throw new AvifDecodeException(AvifErrorCode.BMFF_PARSE_FAILED, "Primary AV1 item is missing ispe", null);
        }
        Av1Config av1Config = primaryItem.firstProperty(Av1Config.class);
        if (av1Config == null) {
            throw new AvifDecodeException(AvifErrorCode.BMFF_PARSE_FAILED, "Primary AV1 item is missing av1C", null);
        }

        boolean alphaPresent = hasAlphaFor(primaryItem.id);
        if (alphaPresent) {
            throw unsupported("Alpha auxiliary images are not implemented in this slice", null);
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
        return new AvifContainer(info, payload);
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
            }
            input.skipBoxPayload(reference);
        }
    }

    /// Returns whether an alpha auxiliary item exists for the supplied item id.
    ///
    /// @param itemId the master image item id
    /// @return whether an alpha auxiliary item exists for the supplied item id
    private boolean hasAlphaFor(int itemId) {
        for (Item item : meta.items.values()) {
            if (item.auxForId == itemId) {
                AuxiliaryType aux = item.firstProperty(AuxiliaryType.class);
                if (aux != null && "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha".equals(aux.type)) {
                    return true;
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
    private sealed interface Property permits ImageSpatialExtents, Av1Config, ColorProperty, AuxiliaryType, OpaqueProperty, PixelInformation, PixelAspectRatio, CleanAperture, ImageRotation, ImageMirror {
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
}
