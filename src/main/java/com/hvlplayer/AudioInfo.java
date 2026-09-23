package com.hvlplayer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/** Reads duration using macOS metadata when available, with a Java Sound fallback. */
final class AudioInfo {
    private static final Pattern DURATION = Pattern.compile("estimated duration:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*sec");

    private AudioInfo() {
    }

    static long durationSeconds(Path path) {
        if (isMac()) {
            Process process = null;
            try {
                ProcessBuilder builder = new ProcessBuilder("/usr/bin/afinfo", path.toString())
                        .redirectErrorStream(true);
                builder.environment().put("LC_ALL", "C");
                process = builder.start();
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append('\n');
                    }
                }
                process.waitFor();
                Matcher matcher = DURATION.matcher(output);
                if (matcher.find()) {
                    return Math.max(0L, Math.round(Double.parseDouble(matcher.group(1))));
                }
            } catch (IOException | RuntimeException ignored) {
                if (process != null) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                if (process != null) {
                    process.destroyForcibly();
                }
                Thread.currentThread().interrupt();
                return 0L;
            }
        }
        return durationWithJavaSound(path);
    }

    private static long durationWithJavaSound(Path path) {
        try {
            AudioFileFormat info = AudioSystem.getAudioFileFormat(path.toFile());
            long frameLength = info.getFrameLength();
            float frameRate = info.getFormat().getFrameRate();
            if (!(frameRate > 0)) {
                frameRate = info.getFormat().getSampleRate();
            }
            if (frameLength > 0 && frameRate > 0) {
                return Math.max(0L, Math.round(frameLength / frameRate));
            }
        } catch (UnsupportedAudioFileException | IOException | RuntimeException ignored) {
            // Some formats do not expose a duration through Java Sound.
        }
        return 0L;
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
    }

    static String formatSeconds(double value) {
        if (!Double.isFinite(value) || value < 0) {
            value = 0;
        }
        long seconds = Math.round(value);
        return "%d:%02d".formatted(seconds / 60, seconds % 60);
    }
}
