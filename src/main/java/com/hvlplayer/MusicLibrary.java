package com.hvlplayer;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

final class MusicLibrary {
    private MusicLibrary() {
    }

    static Path locateMusicDirectory() {
        String configured = System.getProperty("hvl.music.dir");
        List<Path> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            candidates.add(Paths.get(configured));
        }

        try {
            Path codeLocation = Paths.get(HvlPlayer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(codeLocation)) {
                codeLocation = codeLocation.getParent();
            }
            candidates.add(codeLocation.resolve("music"));
            candidates.add(codeLocation.resolveSibling("music"));
        } catch (URISyntaxException | RuntimeException ignored) {
            // The development fallback below is enough when a code source is unavailable.
        }

        candidates.add(Paths.get(System.getProperty("user.dir", ".")).resolve("music"));
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return candidates.isEmpty() ? Paths.get("music").toAbsolutePath() : candidates.get(0).toAbsolutePath();
    }

    static List<Track> load(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("Không tìm thấy thư mục nhạc: " + directory);
        }
        try (Stream<Path> files = Files.list(directory)) {
            List<Track> tracks = new ArrayList<>();
            files.filter(Files::isRegularFile)
                    .filter(MusicLibrary::isAudioFile)
                    .forEach(path -> {
                        try {
                            Id3Metadata.Metadata metadata = Id3Metadata.read(path);
                            tracks.add(new Track(
                                    path,
                                    metadata.title,
                                    metadata.artist,
                                    metadata.album,
                                    metadata.trackNumber,
                                    metadata.totalTracks,
                                    AudioInfo.durationSeconds(path),
                                    metadata.artwork));
                        } catch (IOException ignored) {
                            // A damaged file should not prevent the rest of the library from loading.
                        }
                    });
            tracks.sort(Comparator
                    .comparingInt((Track track) -> track.trackNumber() > 0 ? track.trackNumber() : Integer.MAX_VALUE)
                    .thenComparing(track -> track.path().getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
            return List.copyOf(tracks);
        }
    }

    private static boolean isAudioFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".aac")
                || name.endsWith(".wav") || name.endsWith(".aiff") || name.endsWith(".aif")
                || name.endsWith(".flac") || name.endsWith(".ogg");
    }
}
