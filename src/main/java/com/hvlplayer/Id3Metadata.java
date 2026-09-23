package com.hvlplayer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

/** Small ID3v2 reader with support for text frames and embedded APIC artwork. */
final class Id3Metadata {
    private static final int MAX_TAG_BYTES = 64 * 1024 * 1024;

    private Id3Metadata() {
    }

    static Metadata read(Path path) throws IOException {
        Metadata metadata = new Metadata();
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            if (file.length() >= 4) {
                byte[] magic = new byte[4];
                file.readFully(magic);
                if (magic[0] == 'f' && magic[1] == 'L' && magic[2] == 'a' && magic[3] == 'C') {
                    parseFlacBlocks(file, metadata);
                } else if (magic[0] == 'I' && magic[1] == 'D' && magic[2] == '3' && file.length() >= 10) {
                    file.seek(0);
                    parseId3v2(file, metadata);
                }
            }

            if (metadata.title == null || metadata.title.isBlank()) {
                readId3v1(file, metadata);
            }
        }

        String fileTitle = path.getFileName().toString().replaceFirst("(?i)\\.[^.]+$", "");
        metadata.title = fallback(metadata.title, fileTitle);
        metadata.artist = fallback(metadata.artist, "Nghệ sĩ chưa rõ");
        metadata.album = fallback(metadata.album, "HVL");
        return metadata;
    }

    private static void parseId3v2(RandomAccessFile file, Metadata metadata) throws IOException {
        byte[] header = new byte[10];
        file.readFully(header);
        int version = header[3] & 0xff;
        int flags = header[5] & 0xff;
        int tagSize = syncSafeInt(header, 6);
        if (tagSize <= 0 || tagSize > MAX_TAG_BYTES || tagSize > file.length() - 10) {
            return;
        }
        byte[] tag = new byte[tagSize];
        file.readFully(tag);
        if ((flags & 0x80) != 0) {
            tag = removeUnsynchronization(tag);
        }
        parseFrames(tag, version, flags, metadata);
    }

    private static void parseFlacBlocks(RandomAccessFile file, Metadata metadata) throws IOException {
        boolean lastBlock = false;
        while (!lastBlock && file.getFilePointer() + 4 <= file.length()) {
            int blockHeader = file.readUnsignedByte();
            lastBlock = (blockHeader & 0x80) != 0;
            int blockType = blockHeader & 0x7f;
            int blockSize = (file.readUnsignedByte() << 16)
                    | (file.readUnsignedByte() << 8)
                    | file.readUnsignedByte();
            if (blockSize < 0 || blockSize > MAX_TAG_BYTES || blockSize > file.length() - file.getFilePointer()) {
                return;
            }
            byte[] block = new byte[blockSize];
            file.readFully(block);
            if (blockType == 4) {
                parseVorbisComments(block, metadata);
            } else if (blockType == 6 && metadata.artwork == null) {
                metadata.artwork = parseFlacPicture(block);
            }
        }
    }

    private static void parseVorbisComments(byte[] block, Metadata metadata) {
        int position = 0;
        int vendorLength = littleEndianInt(block, position);
        position += 4;
        if (vendorLength < 0 || vendorLength > block.length - position) {
            return;
        }
        position += vendorLength;
        int commentCount = littleEndianInt(block, position);
        position += 4;
        if (commentCount < 0) {
            return;
        }
        for (int i = 0; i < commentCount && position + 4 <= block.length; i++) {
            int commentLength = littleEndianInt(block, position);
            position += 4;
            if (commentLength < 0 || commentLength > block.length - position) {
                return;
            }
            String comment = new String(block, position, commentLength, StandardCharsets.UTF_8);
            position += commentLength;
            int separator = comment.indexOf('=');
            if (separator < 1) {
                continue;
            }
            String key = comment.substring(0, separator).toUpperCase(java.util.Locale.ROOT);
            String value = comment.substring(separator + 1).trim();
            switch (key) {
                case "TITLE" -> metadata.title = value;
                case "ARTIST" -> metadata.artist = value;
                case "ALBUM" -> metadata.album = value;
                case "TRACKNUMBER", "TRACK" -> parseTrack(value, metadata);
                case "TOTALTRACKS", "TOTALTRACKSNUMBER" -> metadata.totalTracks = parseInt(value);
                default -> {
                    // Other Vorbis comments are not required for the compact player UI.
                }
            }
        }
    }

    private static byte[] parseFlacPicture(byte[] block) {
        int position = 0;
        if (block.length < 32) {
            return null;
        }
        position += 4; // Picture type.
        int mimeLength = bigEndianInt(block, position);
        position += 4;
        if (mimeLength < 0 || mimeLength > block.length - position) {
            return null;
        }
        position += mimeLength;
        int descriptionLength = bigEndianInt(block, position);
        position += 4;
        if (descriptionLength < 0 || descriptionLength > block.length - position) {
            return null;
        }
        position += descriptionLength;
        if (position + 20 > block.length) {
            return null;
        }
        position += 16; // Width, height, color depth and palette colors.
        int imageLength = bigEndianInt(block, position);
        position += 4;
        if (imageLength <= 0 || imageLength > block.length - position) {
            return null;
        }
        return Arrays.copyOfRange(block, position, position + imageLength);
    }

    private static void parseFrames(byte[] tag, int version, int flags, Metadata metadata) {
        int position = 0;
        if ((flags & 0x40) != 0 && tag.length >= 4) {
            if (version == 3) {
                int extendedSize = int32(tag, 0);
                position = Math.min(tag.length, 4 + Math.max(0, extendedSize));
            } else if (version >= 4) {
                int extendedSize = syncSafeInt(tag, 0);
                position = Math.min(tag.length, Math.max(0, extendedSize));
            }
        }

        while (position + 10 <= tag.length) {
            String id = ascii(tag, position, 4);
            if (id.isBlank() || id.charAt(0) == 0 || !validFrameId(id)) {
                break;
            }
            int frameSize = version >= 4 ? syncSafeInt(tag, position + 4) : int32(tag, position + 4);
            if (frameSize <= 0 || frameSize > tag.length - position - 10) {
                break;
            }
            int dataStart = position + 10;
            byte[] data = Arrays.copyOfRange(tag, dataStart, dataStart + frameSize);
            switch (id) {
                case "TIT2" -> metadata.title = textFrame(data);
                case "TPE1" -> metadata.artist = textFrame(data);
                case "TALB" -> metadata.album = textFrame(data);
                case "TRCK" -> parseTrack(textFrame(data), metadata);
                case "APIC" -> {
                    if (metadata.artwork == null) {
                        metadata.artwork = apicFrame(data);
                    }
                }
                default -> {
                    // Other frames are not needed for the compact player UI.
                }
            }
            position = dataStart + frameSize;
        }
    }

    private static void parseTrack(String value, Metadata metadata) {
        if (value == null || value.isBlank()) {
            return;
        }
        String[] pieces = value.split("/", 2);
        metadata.trackNumber = parseInt(pieces[0]);
        if (pieces.length == 2) {
            metadata.totalTracks = parseInt(pieces[1]);
        }
    }

    private static byte[] apicFrame(byte[] data) {
        if (data.length < 4) {
            return null;
        }
        int encoding = data[0] & 0xff;
        int mimeEnd = indexOfZero(data, 1, 1);
        if (mimeEnd < 0 || mimeEnd + 2 >= data.length) {
            return null;
        }
        int pictureTypeIndex = mimeEnd + 1;
        int descriptionStart = pictureTypeIndex + 1;
        int imageStart;
        if (encoding == 1 || encoding == 2) {
            int descriptionEnd = indexOfZeroPair(data, descriptionStart);
            imageStart = descriptionEnd >= 0 ? descriptionEnd + 2 : descriptionStart;
        } else {
            int descriptionEnd = indexOfZero(data, descriptionStart, 1);
            imageStart = descriptionEnd >= 0 ? descriptionEnd + 1 : descriptionStart;
        }
        if (imageStart >= data.length) {
            return null;
        }
        return Arrays.copyOfRange(data, imageStart, data.length);
    }

    private static String textFrame(byte[] data) {
        if (data.length <= 1) {
            return "";
        }
        int encoding = data[0] & 0xff;
        Charset charset = switch (encoding) {
            case 1 -> StandardCharsets.UTF_16;
            case 2 -> StandardCharsets.UTF_16BE;
            case 3 -> StandardCharsets.UTF_8;
            default -> StandardCharsets.ISO_8859_1;
        };
        String value = new String(data, 1, data.length - 1, charset);
        int nullPosition = value.indexOf('\0');
        if (nullPosition >= 0) {
            value = value.substring(0, nullPosition);
        }
        return value.replace('\ufffe', ' ').trim();
    }

    private static void readId3v1(RandomAccessFile file, Metadata metadata) throws IOException {
        if (file.length() < 128) {
            return;
        }
        file.seek(file.length() - 128);
        byte[] tag = new byte[128];
        file.readFully(tag);
        if (tag[0] != 'T' || tag[1] != 'A' || tag[2] != 'G') {
            return;
        }
        metadata.title = fallback(metadata.title, id3v1String(tag, 3, 30));
        metadata.artist = fallback(metadata.artist, id3v1String(tag, 33, 30));
        metadata.album = fallback(metadata.album, id3v1String(tag, 63, 30));
    }

    private static String id3v1String(byte[] data, int start, int length) {
        return new String(data, start, length, StandardCharsets.ISO_8859_1).replace('\0', ' ').trim();
    }

    private static byte[] removeUnsynchronization(byte[] data) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(data.length);
        for (int i = 0; i < data.length; i++) {
            output.write(data[i]);
            if ((data[i] & 0xff) == 0xff && i + 1 < data.length && data[i + 1] == 0) {
                i++;
            }
        }
        return output.toByteArray();
    }

    private static int indexOfZero(byte[] data, int start, int step) {
        for (int i = start; i < data.length; i += step) {
            if (data[i] == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfZeroPair(byte[] data, int start) {
        for (int i = start; i + 1 < data.length; i += 2) {
            if (data[i] == 0 && data[i + 1] == 0) {
                return i;
            }
        }
        return -1;
    }

    private static boolean validFrameId(String id) {
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    private static String ascii(byte[] data, int start, int length) {
        return new String(data, start, length, StandardCharsets.US_ASCII);
    }

    private static int syncSafeInt(byte[] data, int start) {
        if (start + 4 > data.length) {
            return 0;
        }
        return ((data[start] & 0x7f) << 21)
                | ((data[start + 1] & 0x7f) << 14)
                | ((data[start + 2] & 0x7f) << 7)
                | (data[start + 3] & 0x7f);
    }

    private static int int32(byte[] data, int start) {
        if (start + 4 > data.length) {
            return 0;
        }
        return ByteBuffer.wrap(data, start, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    private static int littleEndianInt(byte[] data, int start) {
        if (start < 0 || start + 4 > data.length) {
            return -1;
        }
        return (data[start] & 0xff)
                | ((data[start + 1] & 0xff) << 8)
                | ((data[start + 2] & 0xff) << 16)
                | ((data[start + 3] & 0xff) << 24);
    }

    private static int bigEndianInt(byte[] data, int start) {
        if (start < 0 || start + 4 > data.length) {
            return -1;
        }
        return ((data[start] & 0xff) << 24)
                | ((data[start + 1] & 0xff) << 16)
                | ((data[start + 2] & 0xff) << 8)
                | (data[start + 3] & 0xff);
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static final class Metadata {
        String title;
        String artist;
        String album;
        int trackNumber;
        int totalTracks;
        byte[] artwork;
    }
}
