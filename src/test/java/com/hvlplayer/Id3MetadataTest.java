package com.hvlplayer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Id3MetadataTest {
    @Test
    void readsTextMetadataFromFirstFlacTrack() throws Exception {
        Path path = Path.of("music", "01. Elegie.flac");
        Id3Metadata.Metadata metadata = Id3Metadata.read(path);

        assertEquals("Elegie", metadata.title);
        assertEquals("RPT MCK", metadata.artist);
        assertEquals("HVL", metadata.album);
        assertEquals(1, metadata.trackNumber);
    }

    @Test
    void readsEmbeddedJpegArtwork() throws Exception {
        Path path = Path.of("music", "21. Che Phủ.flac");
        Id3Metadata.Metadata metadata = Id3Metadata.read(path);

        assertEquals(21, metadata.trackNumber);
        assertNotNull(metadata.artwork);
        assertTrue(metadata.artwork.length > 100);
        assertEquals((byte) 0xff, metadata.artwork[0]);
        assertEquals((byte) 0xd8, metadata.artwork[1]);
    }

    @Test
    void readsFlacVorbisCommentsAndEmbeddedJpegArtwork() throws Exception {
        Path path = Path.of("music", "01. Elegie.flac");
        Id3Metadata.Metadata metadata = Id3Metadata.read(path);

        assertEquals("Elegie", metadata.title);
        assertEquals("RPT MCK", metadata.artist);
        assertEquals("HVL", metadata.album);
        assertEquals(1, metadata.trackNumber);
        assertNotNull(metadata.artwork);
        assertTrue(metadata.artwork.length > 100);
        assertEquals((byte) 0xff, metadata.artwork[0]);
        assertEquals((byte) 0xd8, metadata.artwork[1]);
    }

    @Test
    void libraryContainsAllBundledFlacFiles() throws Exception {
        Path directory = Path.of("music");
        long fileCount;
        try (var files = Files.list(directory)) {
            fileCount = files.filter(path -> path.toString().endsWith(".flac")).count();
        }
        assertEquals(30, fileCount);
        assertEquals(30, MusicLibrary.load(directory).size());
    }
}
