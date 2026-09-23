package com.hvlplayer;

import javax.swing.SwingUtilities;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.IntConsumer;

/** Bridges macOS Now Playing commands to the Swing player. */
final class MacMediaKeys {
    static final int TOGGLE = 0;
    static final int PLAY = 1;
    static final int PAUSE = 2;
    static final int PREVIOUS = 3;
    static final int NEXT = 4;

    private final IntConsumer commandHandler;
    private boolean started;
    private Track publishedTrack;
    private int publishedState = -1;
    private long publishedSecond = -1;

    MacMediaKeys(IntConsumer commandHandler) {
        this.commandHandler = commandHandler;
    }

    void start() {
        if (started || !System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("mac")) {
            return;
        }
        try {
            String configured = System.getProperty("hvl.media.keys.library");
            Path library;
            if (configured != null && !configured.isBlank()) {
                library = Path.of(configured);
            } else {
                Path code = Path.of(HvlPlayer.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI());
                library = (Files.isDirectory(code) ? code : code.getParent())
                        .resolve("libhvlmediakeys.dylib");
            }
            System.load(library.toAbsolutePath().toString());
            started = nativeStart();
        } catch (UnsatisfiedLinkError | SecurityException | URISyntaxException exception) {
            System.err.println("HVL: Không thể bật phím media: " + exception.getMessage());
        }
    }

    void update(Track track, boolean playing, boolean paused, double seconds) {
        if (!started) {
            return;
        }
        int state = playing ? 1 : paused ? 2 : 0;
        long wholeSecond = Math.max(0, (long) seconds);
        if (track == publishedTrack && state == publishedState && wholeSecond == publishedSecond) {
            return;
        }
        publishedTrack = track;
        publishedState = state;
        publishedSecond = wholeSecond;
        nativeUpdate(track == null ? "" : track.title(),
                track == null ? "" : track.artist(),
                track == null ? 0 : track.durationSeconds(), seconds, state);
    }

    void stop() {
        if (started) {
            nativeStop();
            started = false;
        }
    }

    @SuppressWarnings("unused") // Called from the native remote-command handlers.
    private void onCommand(int command) {
        SwingUtilities.invokeLater(() -> commandHandler.accept(command));
    }

    private native boolean nativeStart();

    private native void nativeUpdate(String title, String artist,
            double durationSeconds, double elapsedSeconds, int state);

    private native void nativeStop();
}
