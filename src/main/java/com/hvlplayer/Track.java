package com.hvlplayer;

import java.nio.file.Path;

/** A song in the bundled library. */
public final class Track {
    private final Path path;
    private final String title;
    private final String artist;
    private final String album;
    private final int trackNumber;
    private final int totalTracks;
    private final long durationSeconds;
    private final byte[] artwork;

    public Track(
            Path path,
            String title,
            String artist,
            String album,
            int trackNumber,
            int totalTracks,
            long durationSeconds,
            byte[] artwork) {
        this.path = path;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.trackNumber = trackNumber;
        this.totalTracks = totalTracks;
        this.durationSeconds = durationSeconds;
        this.artwork = artwork;
    }

    public Path path() {
        return path;
    }

    public String title() {
        return title;
    }

    public String artist() {
        return artist;
    }

    public String album() {
        return album;
    }

    public int trackNumber() {
        return trackNumber;
    }

    public int totalTracks() {
        return totalTracks;
    }

    public long durationSeconds() {
        return durationSeconds;
    }

    public byte[] artwork() {
        return artwork;
    }

    public String durationText() {
        if (durationSeconds <= 0) {
            return "--:--";
        }
        long minutes = durationSeconds / 60;
        long seconds = durationSeconds % 60;
        return "%d:%02d".formatted(minutes, seconds);
    }

    @Override
    public String toString() {
        return title;
    }
}
